// 워크플로우 스킴 Application Service — Scheme CRUD 5 메서드 (create/find/update/softDelete/list)

package com.bts.workflow.scheme.application

import com.bts.workflow.port.outbound.ActorId
import com.bts.workflow.scheme.domain.WorkflowScheme
import com.bts.workflow.scheme.domain.WorkflowSchemeKey
import com.bts.workflow.scheme.exception.SchemeInUseException
import com.bts.workflow.scheme.exception.SchemeStandardFieldLockedException
import com.bts.workflow.scheme.exception.SchemeStandardNotDeletableException
import com.bts.workflow.scheme.exception.WorkflowSchemeNotFoundException
import com.bts.workflow.scheme.port.outbound.WorkflowSchemePermission
import com.bts.workflow.scheme.port.outbound.WorkflowSchemePermissionResolver
import com.bts.workflow.scheme.port.outbound.WorkflowSchemeScope
import com.bts.workflow.scheme.repository.ProjectWorkflowSchemeAssignmentRepository
import com.bts.workflow.scheme.repository.WorkflowSchemeRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 워크플로우 스킴 Application Service.
 *
 * Scheme CRUD 5 메서드를 담당한다.
 * Repository 호출과 도메인 불변식(invariant) 위임만 수행한다 — SQL 직접 작성 금지 (DATA.md §5).
 *
 * ## PR #6 learning — @Service 구체 클래스 부착
 * interface 가 아닌 구체 클래스에 @Service 를 부착해야 Spring AOP 프록시가
 * @Transactional 을 정상 적용한다 (interface proxy 시 @Transactional 무력화 방지).
 *
 * ## 권한 검증
 * 모든 mutating 메서드(create/update/softDelete) 진입 직후
 * [WorkflowSchemePermissionResolver.requirePermission] 을 호출한다.
 * 권한이 없으면 resolver 가 예외를 던진다 (Guard 패턴).
 *
 * @param schemeRepo 워크플로우 스킴 Repository.
 * @param assignmentRepo 프로젝트-스킴 할당 Repository (S7 사용 중 검증용).
 * @param permissionResolver 스킴 권한 평가 outbound port.
 */
@Service
@Transactional
class WorkflowSchemeApplicationService(
    private val schemeRepo: WorkflowSchemeRepository,
    private val assignmentRepo: ProjectWorkflowSchemeAssignmentRepository,
    private val permissionResolver: WorkflowSchemePermissionResolver,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 새 워크플로우 스킴을 생성한다.
     *
     * @param actor 작업 수행 행위자. MANAGE_SCHEME 권한이 필요하다.
     * @param key 스킴 식별 키. URL-safe 소문자 슬러그.
     * @param name 스킴 이름. 빈 문자열 불허.
     * @param description 스킴 설명. null 허용.
     * @param isDefault 표준 스킴 여부. 기본값 false.
     * @return 저장된 스킴 (DB 생성 id 포함).
     */
    fun create(
        actor: ActorId,
        key: WorkflowSchemeKey,
        name: String,
        description: String?,
        isDefault: Boolean = false,
    ): WorkflowScheme {
        permissionResolver.requirePermission(actor, WorkflowSchemePermission.MANAGE_SCHEME, WorkflowSchemeScope.Global)
        log.info("create scheme: actor={} key={}", actor.raw, key.value)
        val scheme = WorkflowScheme.create(key = key, name = name, description = description, isDefault = isDefault)
        return schemeRepo.save(scheme)
    }

    /**
     * key 로 활성 스킴을 조회한다.
     *
     * @param key 조회할 스킴 키.
     * @return 활성 스킴.
     * @throws WorkflowSchemeNotFoundException key 에 해당하는 활성 스킴이 없을 때.
     */
    @Transactional(readOnly = true)
    fun find(key: WorkflowSchemeKey): WorkflowScheme =
        schemeRepo.findByKey(key) ?: throw WorkflowSchemeNotFoundException(key.value)

    /**
     * 스킴의 가변 필드(name / description / isDefault) 를 변경한다.
     *
     * EC-4 D11 — 표준 스킴(isDefault=true)의 name / description / isDefault 변경은 차단한다.
     * key 는 항상 immutable 이므로 변경 파라미터에서 제외한다.
     *
     * @param actor 작업 수행 행위자. MANAGE_SCHEME 권한이 필요하다.
     * @param key 수정할 스킴 키.
     * @param newName 새 이름.
     * @param newDescription 새 설명. null 허용.
     * @param newIsDefault 새 isDefault 값.
     * @return 변경된 스킴.
     * @throws WorkflowSchemeNotFoundException 스킴이 없을 때.
     * @throws com.bts.workflow.scheme.exception.SchemeStandardFieldLockedException 표준 스킴의 잠긴 필드를 변경하려 할 때.
     */
    fun update(
        actor: ActorId,
        key: WorkflowSchemeKey,
        newName: String,
        newDescription: String?,
        newIsDefault: Boolean,
    ): WorkflowScheme {
        permissionResolver.requirePermission(actor, WorkflowSchemePermission.MANAGE_SCHEME, WorkflowSchemeScope.Global)
        val existing = schemeRepo.findByKey(key) ?: throw WorkflowSchemeNotFoundException(key.value)

        if (existing.isDefault) {
            validateStandardFieldNotChanged(existing, newName, newDescription, newIsDefault)
        }

        log.info("update scheme: actor={} key={}", actor.raw, key.value)
        val updated = WorkflowScheme.reconstruct(
            id = requireNotNull(existing.id) { "scheme.id must not be null" },
            key = existing.key,
            name = newName,
            description = newDescription,
            isDefault = newIsDefault,
            createdAt = existing.createdAt,
            updatedAt = existing.updatedAt,
            deletedAt = existing.deletedAt,
        )
        return schemeRepo.update(updated)
    }

    /**
     * 스킴을 soft-delete 한다.
     *
     * S6 — 표준 스킴(isDefault=true) 삭제 불가.
     * S7 — 하나 이상의 프로젝트에 할당된 스킴 삭제 불가.
     *
     * @param actor 작업 수행 행위자. MANAGE_SCHEME 권한이 필요하다.
     * @param key 삭제할 스킴 키.
     * @throws WorkflowSchemeNotFoundException 스킴이 없을 때.
     * @throws SchemeStandardNotDeletableException S6 — 표준 스킴 삭제 시도 시.
     * @throws SchemeInUseException S7 — 사용 중인 스킴 삭제 시도 시.
     */
    fun softDelete(actor: ActorId, key: WorkflowSchemeKey) {
        permissionResolver.requirePermission(actor, WorkflowSchemePermission.MANAGE_SCHEME, WorkflowSchemeScope.Global)
        val scheme = schemeRepo.findByKey(key) ?: throw WorkflowSchemeNotFoundException(key.value)

        // S6 — 표준 스킴 삭제 불가
        if (scheme.isDefault) {
            throw SchemeStandardNotDeletableException(key.value)
        }

        val schemeId = requireNotNull(scheme.id) { "scheme.id must not be null" }

        // S7 — 사용 중인 스킴 삭제 불가
        if (assignmentRepo.existsBySchemeId(schemeId)) {
            throw SchemeInUseException(usedByProjects = emptyList())
        }

        log.info("softDelete scheme: actor={} key={}", actor.raw, key.value)
        schemeRepo.softDelete(schemeId)
    }

    /**
     * 활성 스킴 전체 목록을 반환한다.
     *
     * @return 활성 스킴 목록. 비어 있을 수 있음.
     */
    @Transactional(readOnly = true)
    fun list(): List<WorkflowScheme> = schemeRepo.findAll()

    // ── 내부 헬퍼 ─────────────────────────────────────────────────────────────

    /**
     * EC-4 D11 — 표준 스킴의 잠긴 필드(name/description/isDefault) 변경 시도를 차단한다.
     *
     * 표준 스킴의 name / description / isDefault 는 변경 불가 필드다.
     * 현재 값과 다를 때 [com.bts.workflow.scheme.exception.SchemeStandardFieldLockedException] 을 던진다.
     */
    private fun validateStandardFieldNotChanged(
        existing: WorkflowScheme,
        newName: String,
        newDescription: String?,
        newIsDefault: Boolean,
    ) {
        if (existing.name != newName) {
            throw SchemeStandardFieldLockedException(existing.key.value, "name")
        }
        if (existing.description != newDescription) {
            throw SchemeStandardFieldLockedException(existing.key.value, "description")
        }
        if (existing.isDefault != newIsDefault) {
            throw SchemeStandardFieldLockedException(existing.key.value, "is_default")
        }
    }
}
