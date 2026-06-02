// 컴포넌트 CRUD ApplicationService — 권한·프로젝트·리드 검증, 도메인 경유, repo 위임

package com.bts.issue.component.application

import com.bts.issue.component.domain.Component
import com.bts.issue.component.domain.ComponentAccessDeniedException
import com.bts.issue.component.domain.ComponentLeadNotFoundException
import com.bts.issue.component.domain.ComponentNotFoundException
import com.bts.issue.component.domain.ComponentProjectNotFoundException
import com.bts.issue.component.domain.DuplicateComponentNameException
import com.bts.issue.component.repository.ComponentRepository
import com.bts.shared.permission.ComponentPermission
import com.bts.shared.permission.ComponentPermissionResolver
import com.bts.shared.user.UserLookupPort
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/** 23505 unique_violation SQLState 상수. */
private const val SQL_STATE_UNIQUE_VIOLATION = "23505"

/**
 * 컴포넌트 CRUD ApplicationService.
 *
 * 권한 검증, 프로젝트 존재 확인, 리드 사용자 실재 확인, 도메인 Aggregate 경유,
 * Repository 위임까지 한 흐름을 조율한다.
 *
 * **도메인 우회 금지 (메모리 patch-merge-domain-bypass).**
 * 영속 값은 반드시 도메인 메서드(rename/changeLead/changeDescription/softDelete)의
 * 산출물에서 가져온다. raw 입력을 repo 로 직접 전달하지 않는다.
 *
 * **트랜잭션 경계.**
 * 클래스 레벨 `@Transactional` 이 기본(읽기/쓰기 모두). 읽기 전용 메서드는
 * `@Transactional(readOnly = true)` 를 오버라이드한다.
 *
 * 공개 메서드 6개 + private 헬퍼 6개 = 12개. TooManyFunctions 임계값 11 초과이나
 * 명세 요구 메서드 수로 파일 분리는 과도 — Suppress 처리.
 */
@Suppress("TooManyFunctions")
@Service
@Transactional
class ComponentApplicationService(
    private val permissionResolver: ComponentPermissionResolver,
    private val projectLookup: ProjectLookup,
    private val userLookupPort: UserLookupPort,
    private val repo: ComponentRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 새 컴포넌트를 생성한다.
     *
     * 흐름.
     * 1. 프로젝트 resolve — 미존재 시 [ComponentProjectNotFoundException].
     * 2. CREATE 권한 검증 — 거부 시 [ComponentAccessDeniedException].
     * 3. leadUserId non-null 이면 [UserLookupPort.exists] 검증 — 미존재 시 [ComponentLeadNotFoundException].
     * 4. 도메인 [Component.create] 호출.
     * 5. [ComponentRepository.insert] — 23505 위반 시 [DuplicateComponentNameException].
     *
     * @param actorId 생성 행위자 UUID.
     * @param projectIdOrKey 프로젝트 UUID 또는 projectKey.
     * @param name 컴포넌트 이름. trim 후 1~255자.
     * @param description 선택적 설명. null 허용.
     * @param leadUserId 리드 사용자 UUID. null 이면 미지정.
     * @return 생성된 [Component].
     * @throws ComponentProjectNotFoundException 프로젝트가 존재하지 않을 때.
     * @throws ComponentAccessDeniedException 권한이 없을 때.
     * @throws ComponentLeadNotFoundException 리드 사용자가 존재하지 않을 때.
     * @throws DuplicateComponentNameException 동일 프로젝트 내 이름이 중복될 때.
     */
    @Suppress("ThrowsCount")
    fun create(
        actorId: UUID,
        projectIdOrKey: String,
        name: String,
        description: String?,
        leadUserId: UUID?,
    ): Component {
        val projectId = resolveProject(projectIdOrKey)
        assertPermission(actorId, ComponentPermission.CREATE, projectId)
        validateLead(leadUserId)

        val domain =
            Component.create(
                projectId = projectId,
                name = name,
                description = description,
                leadUserId = leadUserId,
            )
        return tryInsert(domain, name)
    }

    /**
     * 컴포넌트의 이름과 설명을 수정한다.
     *
     * name / description 각각 null 이면 기존 값 유지 (3-state PATCH).
     * 리드 변경은 [changeLead] 전용 메서드를 사용한다.
     *
     * 흐름.
     * 1. 프로젝트 resolve — 미존재 시 [ComponentProjectNotFoundException].
     * 2. UPDATE 권한 검증.
     * 3. 컴포넌트 존재 확인 — 미존재 시 [ComponentNotFoundException].
     * 4. 도메인 [Component.rename] / [Component.changeDescription] 경유.
     * 5. [ComponentRepository.update] 위임.
     *
     * @param actorId 수정 행위자 UUID.
     * @param projectIdOrKey 프로젝트 UUID 또는 projectKey.
     * @param componentId 수정할 컴포넌트 UUID.
     * @param name 새 이름. null 이면 기존 유지.
     * @param description 새 설명. null 이면 기존 유지.
     * @return 수정된 [Component].
     * @throws ComponentProjectNotFoundException 프로젝트가 존재하지 않을 때.
     * @throws ComponentAccessDeniedException 권한이 없을 때.
     * @throws ComponentNotFoundException 컴포넌트가 존재하지 않을 때.
     */
    fun update(
        actorId: UUID,
        projectIdOrKey: String,
        componentId: UUID,
        name: String?,
        description: String?,
    ): Component {
        val projectId = resolveProject(projectIdOrKey)
        assertPermission(actorId, ComponentPermission.UPDATE, projectId)
        val existing = findActiveComponent(componentId, projectId)

        val updated = applyNameAndDescription(existing, name, description)
        log.info("component_updated id={} projectId={} actor={}", componentId, projectId, actorId)
        return repo.update(updated)
    }

    /**
     * 컴포넌트 리드를 변경하거나 해제한다.
     *
     * null 전달 시 리드 해제 (2-state: 지정 / 해제).
     *
     * 흐름.
     * 1. 프로젝트 resolve — 미존재 시 [ComponentProjectNotFoundException].
     * 2. UPDATE 권한 검증.
     * 3. 컴포넌트 존재 확인 — 미존재 시 [ComponentNotFoundException].
     * 4. leadUserId non-null 이면 [UserLookupPort.exists] 검증 — 미존재 시 [ComponentLeadNotFoundException].
     * 5. 도메인 [Component.changeLead] 경유.
     * 6. [ComponentRepository.update] 위임.
     *
     * @param actorId 수정 행위자 UUID.
     * @param projectIdOrKey 프로젝트 UUID 또는 projectKey.
     * @param componentId 수정할 컴포넌트 UUID.
     * @param leadUserId 새 리드 UUID. null 이면 해제.
     * @return 수정된 [Component].
     * @throws ComponentProjectNotFoundException 프로젝트가 존재하지 않을 때.
     * @throws ComponentAccessDeniedException 권한이 없을 때.
     * @throws ComponentNotFoundException 컴포넌트가 존재하지 않을 때.
     * @throws ComponentLeadNotFoundException 리드 사용자가 존재하지 않을 때.
     */
    @Suppress("ThrowsCount")
    fun changeLead(
        actorId: UUID,
        projectIdOrKey: String,
        componentId: UUID,
        leadUserId: UUID?,
    ): Component {
        val projectId = resolveProject(projectIdOrKey)
        assertPermission(actorId, ComponentPermission.UPDATE, projectId)
        val existing = findActiveComponent(componentId, projectId)
        validateLead(leadUserId)

        val updated = existing.changeLead(leadUserId)
        log.info(
            "component_lead_changed id={} projectId={} leadUserId={} actor={}",
            componentId,
            projectId,
            leadUserId,
            actorId,
        )
        return repo.update(updated)
    }

    /**
     * 컴포넌트를 소프트 삭제한다.
     *
     * 흐름.
     * 1. 프로젝트 resolve — 미존재 시 [ComponentProjectNotFoundException].
     * 2. DELETE 권한 검증.
     * 3. 컴포넌트 존재 확인 — 미존재 시 [ComponentNotFoundException].
     * 4. [ComponentRepository.softDelete] 위임.
     *
     * @param actorId 삭제 행위자 UUID.
     * @param projectIdOrKey 프로젝트 UUID 또는 projectKey.
     * @param componentId 삭제할 컴포넌트 UUID.
     * @throws ComponentProjectNotFoundException 프로젝트가 존재하지 않을 때.
     * @throws ComponentAccessDeniedException 권한이 없을 때.
     * @throws ComponentNotFoundException 컴포넌트가 존재하지 않을 때.
     */
    fun delete(
        actorId: UUID,
        projectIdOrKey: String,
        componentId: UUID,
    ) {
        val projectId = resolveProject(projectIdOrKey)
        assertPermission(actorId, ComponentPermission.DELETE, projectId)
        findActiveComponent(componentId, projectId)
        repo.softDelete(componentId, projectId)
        log.info("component_deleted id={} projectId={} actor={}", componentId, projectId, actorId)
    }

    /**
     * 단건 컴포넌트를 조회한다.
     *
     * READ 는 권한 게이트 없음 (Jira 동일 정책).
     *
     * @param actorId 조회 행위자 UUID. (로깅 목적)
     * @param projectIdOrKey 프로젝트 UUID 또는 projectKey.
     * @param componentId 조회할 컴포넌트 UUID.
     * @return 활성 [Component].
     * @throws ComponentProjectNotFoundException 프로젝트가 존재하지 않을 때.
     * @throws ComponentNotFoundException 컴포넌트가 존재하지 않을 때.
     */
    @Transactional(readOnly = true)
    fun getById(
        actorId: UUID,
        projectIdOrKey: String,
        componentId: UUID,
    ): Component {
        val projectId = resolveProject(projectIdOrKey)
        log.debug("component_get_by_id id={} projectId={} actor={}", componentId, projectId, actorId)
        return findActiveComponent(componentId, projectId)
    }

    /**
     * 프로젝트 소속 활성 컴포넌트 목록을 조회한다.
     *
     * READ 는 권한 게이트 없음 (Jira 동일 정책). name 오름차순 정렬은 repo 에서 처리.
     *
     * @param actorId 조회 행위자 UUID. (로깅 목적)
     * @param projectIdOrKey 프로젝트 UUID 또는 projectKey.
     * @return 활성 [Component] 리스트. 없으면 빈 리스트.
     * @throws ComponentProjectNotFoundException 프로젝트가 존재하지 않을 때.
     */
    @Transactional(readOnly = true)
    fun listByProject(
        actorId: UUID,
        projectIdOrKey: String,
    ): List<Component> {
        val projectId = resolveProject(projectIdOrKey)
        log.debug("component_list_by_project projectId={} actor={}", projectId, actorId)
        return repo.findByProject(projectId)
    }

    // ── private helpers ───────────────────────────────────────────────────────

    /**
     * projectIdOrKey 를 활성 프로젝트 UUID 로 해석한다.
     * 미존재 시 [ComponentProjectNotFoundException] 을 던진다.
     */
    private fun resolveProject(projectIdOrKey: String): UUID =
        projectLookup.resolve(projectIdOrKey)
            ?: throw ComponentProjectNotFoundException(projectIdOrKey)

    /**
     * 행위자의 권한을 검증한다. 거부 시 [ComponentAccessDeniedException] 을 던진다.
     */
    private fun assertPermission(
        actorId: UUID,
        permission: ComponentPermission,
        projectId: UUID,
    ) {
        if (!permissionResolver.hasPermission(actorId, permission, projectId)) {
            throw ComponentAccessDeniedException(actorId, permission, projectId)
        }
    }

    /**
     * 활성 컴포넌트를 조회한다. 미존재 시 [ComponentNotFoundException] 을 던진다.
     */
    private fun findActiveComponent(
        componentId: UUID,
        projectId: UUID,
    ): Component =
        repo.findById(componentId, projectId)
            ?: throw ComponentNotFoundException(componentId)

    /**
     * leadUserId non-null 이면 [UserLookupPort.exists] 로 실재 검증한다.
     * 미존재 시 [ComponentLeadNotFoundException] 을 던진다.
     */
    private fun validateLead(leadUserId: UUID?) {
        if (leadUserId != null && !userLookupPort.exists(leadUserId)) {
            throw ComponentLeadNotFoundException(leadUserId)
        }
    }

    /**
     * name/description null-safe 도메인 메서드 적용 — null 이면 기존 값 유지 (3-state PATCH).
     *
     * 도메인 메서드(rename/changeDescription)를 반드시 경유해 불변식을 보장한다.
     */
    private fun applyNameAndDescription(
        component: Component,
        name: String?,
        description: String?,
    ): Component {
        var result = component
        if (name != null) {
            result = result.rename(name)
        }
        if (description != null) {
            result = result.changeDescription(description)
        }
        return result
    }

    /**
     * [ComponentRepository.insert] 를 호출하고, 23505 SQLState 위반은
     * [DuplicateComponentNameException] 으로 변환한다.
     *
     * jOOQ 는 Spring PersistenceExceptionTranslator 가 개입하지 않을 경우
     * [DataIntegrityViolationException] 대신 [org.jooq.exception.IntegrityConstraintViolationException]
     * 을 직접 던진다. 두 케이스를 모두 처리한다.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun tryInsert(
        component: Component,
        name: String,
    ): Component =
        try {
            repo.insert(component)
        } catch (ex: RuntimeException) {
            translateUniqueViolation(ex, name)
        }

    /**
     * 런타임 예외가 23505 unique_violation 에서 비롯됐으면 [DuplicateComponentNameException] 으로 변환한다.
     * 그렇지 않으면 원 예외를 그대로 re-throw 한다.
     */
    @Suppress("ThrowsCount")
    private fun translateUniqueViolation(
        ex: RuntimeException,
        name: String,
    ): Nothing {
        val sqlEx =
            generateSequence(ex.cause) { it.cause }
                .filterIsInstance<java.sql.SQLException>()
                .firstOrNull()
        if (sqlEx?.sqlState == SQL_STATE_UNIQUE_VIOLATION) {
            log.warn("Duplicate component name={} projectId={}", name, ex.message)
            throw DuplicateComponentNameException(name)
        }
        throw ex
    }
}
