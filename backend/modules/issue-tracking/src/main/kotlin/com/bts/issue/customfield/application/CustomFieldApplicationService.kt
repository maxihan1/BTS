// 커스텀 필드 정의 CRUD ApplicationService — 권한 게이트, 프로젝트 존재 확인, 불변식 보호, Repository 위임

package com.bts.issue.customfield.application

import com.bts.issue.customfield.domain.CustomFieldAccessDeniedException
import com.bts.issue.customfield.domain.CustomFieldDefinition
import com.bts.issue.customfield.domain.CustomFieldNotFoundException
import com.bts.issue.customfield.domain.CustomFieldOption
import com.bts.issue.customfield.domain.CustomFieldProjectNotFoundException
import com.bts.issue.customfield.domain.DuplicateCustomFieldKeyException
import com.bts.issue.customfield.domain.FieldType
import com.bts.issue.customfield.domain.ImmutableFieldTypeChangeException
import com.bts.issue.customfield.repository.CustomFieldDefinitionRepository
import com.bts.issue.project.ProjectLookup
import com.bts.issue.project.archive.ProjectArchiveGuard
import com.bts.shared.permission.CustomFieldPermission
import com.bts.shared.permission.CustomFieldPermissionResolver
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/** 23505 unique_violation SQLState 상수. */
private const val SQL_STATE_UNIQUE_VIOLATION = "23505"

/**
 * 커스텀 필드 정의 CRUD ApplicationService.
 *
 * 권한 검증, 프로젝트 존재 확인, fieldType/key 불변식 보호, Repository 위임까지
 * 한 흐름을 조율한다. [ComponentApplicationService] 와 동형.
 *
 * **도메인 우회 금지 (메모리 patch-merge-domain-bypass).**
 * 수정 시 기존 필드를 조회한 후 도메인 메서드(copy)를 경유하여 값을 갱신하고
 * repo 로 위임한다. raw 입력을 repo 에 직접 전달하지 않는다.
 *
 * **트랜잭션 경계.**
 * 클래스 레벨 `@Transactional` 이 기본(읽기/쓰기 모두). 읽기 전용 메서드는
 * `@Transactional(readOnly = true)` 를 오버라이드한다.
 */
@Suppress("TooManyFunctions")
@Service
@Transactional
class CustomFieldApplicationService(
    private val permissionResolver: CustomFieldPermissionResolver,
    private val projectLookup: ProjectLookup,
    private val repo: CustomFieldDefinitionRepository,
    private val archiveGuard: ProjectArchiveGuard,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 새 커스텀 필드 정의를 생성한다.
     *
     * 흐름.
     * 1. 프로젝트 resolve — 미존재 시 [CustomFieldProjectNotFoundException].
     * 2. CREATE 권한 검증 — 거부 시 [CustomFieldAccessDeniedException].
     * 3. 도메인 [CustomFieldDefinition.create] 호출.
     * 4. [CustomFieldDefinitionRepository.save] — 23505 위반 시 [DuplicateCustomFieldKeyException].
     *
     * @param actorId 생성 행위자 UUID.
     * @param projectIdOrKey 프로젝트 UUID 또는 projectKey.
     * @param key 필드 식별자. URL-safe 소문자.
     * @param name 사용자에게 노출되는 필드 이름.
     * @param fieldType 필드 데이터 타입.
     * @param required 필수 여부.
     * @param displayOrder 표시 순서.
     * @param options 선택지 목록. 선택형 타입이면 반드시 1건 이상.
     * @return 생성된 [CustomFieldDefinition].
     * @throws CustomFieldProjectNotFoundException 프로젝트가 존재하지 않을 때.
     * @throws CustomFieldAccessDeniedException 권한이 없을 때.
     * @throws DuplicateCustomFieldKeyException 동일 프로젝트 내 key 가 중복될 때.
     */
    @Suppress("LongParameterList")
    fun create(
        actorId: UUID,
        projectIdOrKey: String,
        key: String,
        name: String,
        fieldType: FieldType,
        required: Boolean,
        displayOrder: Int,
        options: List<CustomFieldOption>,
    ): CustomFieldDefinition {
        val projectId = resolveProject(projectIdOrKey)
        assertPermission(actorId, CustomFieldPermission.CREATE, projectId)
        // TASK9-RED-PENDING archiveGuard.check(projectId)

        val domain =
            CustomFieldDefinition.create(
                projectId = projectId,
                key = key,
                name = name,
                fieldType = fieldType,
                required = required,
                displayOrder = displayOrder,
                options = options,
            )
        return trySave(domain, key)
    }

    /**
     * 기존 커스텀 필드 정의를 수정한다.
     *
     * fieldType / key 는 생성 후 불변. 변경 시도 시 [ImmutableFieldTypeChangeException] 발생.
     * 각 파라미터가 null 이면 기존 값을 유지한다(3-state PATCH).
     *
     * 흐름.
     * 1. 프로젝트 resolve — 미존재 시 [CustomFieldProjectNotFoundException].
     * 2. UPDATE 권한 검증.
     * 3. 기존 필드 조회 — 미존재 시 [CustomFieldNotFoundException].
     * 4. fieldType / key 불변식 검증 — [ImmutableFieldTypeChangeException].
     * 5. 변경된 도메인 객체를 [CustomFieldDefinitionRepository.update] 로 위임.
     *
     * @param actorId 수정 행위자 UUID.
     * @param projectIdOrKey 프로젝트 UUID 또는 projectKey.
     * @param fieldId 수정할 필드 정의 UUID.
     * @param name 새 이름. null 이면 기존 유지.
     * @param description 새 설명. null 이면 기존 유지.
     * @param fieldType 필드 타입. 기존 값과 달라지면 예외.
     * @param key 필드 key. 기존 값과 달라지면 예외.
     * @param required 필수 여부. null 이면 기존 유지.
     * @param displayOrder 표시 순서. null 이면 기존 유지.
     * @param options 선택지 목록(전체 교체). null 이면 기존 유지.
     * @return 수정된 [CustomFieldDefinition].
     * @throws CustomFieldProjectNotFoundException 프로젝트가 존재하지 않을 때.
     * @throws CustomFieldAccessDeniedException 권한이 없을 때.
     * @throws CustomFieldNotFoundException 필드 정의가 존재하지 않을 때.
     * @throws ImmutableFieldTypeChangeException fieldType 또는 key 변경 시도 시.
     */
    @Suppress("LongParameterList", "ThrowsCount")
    fun update(
        actorId: UUID,
        projectIdOrKey: String,
        fieldId: UUID,
        name: String?,
        description: String?,
        fieldType: FieldType?,
        key: String?,
        required: Boolean?,
        displayOrder: Int?,
        options: List<CustomFieldOption>?,
    ): CustomFieldDefinition {
        val projectId = resolveProject(projectIdOrKey)
        assertPermission(actorId, CustomFieldPermission.UPDATE, projectId)
        // TASK9-RED-PENDING archiveGuard.check(projectId)

        val existing = findActiveField(fieldId, projectId)

        if (fieldType != null && fieldType != existing.fieldType) {
            throw ImmutableFieldTypeChangeException("fieldType")
        }
        if (key != null && key != existing.key) {
            throw ImmutableFieldTypeChangeException("key")
        }

        val updated =
            existing.withChanges(
                name = name,
                description = description,
                required = required,
                displayOrder = displayOrder,
                options = options,
            )
        log.info("custom_field_updated id={} projectId={} actor={}", fieldId, projectId, actorId)
        return repo.update(updated)
    }

    /**
     * 커스텀 필드 정의를 소프트 삭제한다.
     *
     * 흐름.
     * 1. 프로젝트 resolve — 미존재 시 [CustomFieldProjectNotFoundException].
     * 2. DELETE 권한 검증.
     * 3. 필드 존재 확인 — 미존재 시 [CustomFieldNotFoundException].
     * 4. [CustomFieldDefinitionRepository.softDelete] 위임.
     *
     * @param actorId 삭제 행위자 UUID.
     * @param projectIdOrKey 프로젝트 UUID 또는 projectKey.
     * @param fieldId 삭제할 필드 정의 UUID.
     * @throws CustomFieldProjectNotFoundException 프로젝트가 존재하지 않을 때.
     * @throws CustomFieldAccessDeniedException 권한이 없을 때.
     * @throws CustomFieldNotFoundException 필드 정의가 존재하지 않을 때.
     */
    fun softDelete(
        actorId: UUID,
        projectIdOrKey: String,
        fieldId: UUID,
    ) {
        val projectId = resolveProject(projectIdOrKey)
        assertPermission(actorId, CustomFieldPermission.DELETE, projectId)
        // TASK9-RED-PENDING archiveGuard.check(projectId)
        findActiveField(fieldId, projectId)
        repo.softDelete(fieldId, projectId)
        log.info("custom_field_deleted id={} projectId={} actor={}", fieldId, projectId, actorId)
    }

    /**
     * 단건 커스텀 필드 정의를 조회한다.
     *
     * READ 는 권한 게이트 없음 (컴포넌트 동일 정책).
     *
     * @param actorId 조회 행위자 UUID. (로깅 목적)
     * @param projectIdOrKey 프로젝트 UUID 또는 projectKey.
     * @param fieldId 조회할 필드 정의 UUID.
     * @return 활성 [CustomFieldDefinition].
     * @throws CustomFieldProjectNotFoundException 프로젝트가 존재하지 않을 때.
     * @throws CustomFieldNotFoundException 필드 정의가 존재하지 않을 때.
     */
    @Transactional(readOnly = true)
    fun getById(
        actorId: UUID,
        projectIdOrKey: String,
        fieldId: UUID,
    ): CustomFieldDefinition {
        val projectId = resolveProject(projectIdOrKey)
        log.debug("custom_field_get_by_id id={} projectId={} actor={}", fieldId, projectId, actorId)
        return findActiveField(fieldId, projectId)
    }

    /**
     * 프로젝트 소속 활성 커스텀 필드 정의 목록을 조회한다.
     *
     * READ 는 권한 게이트 없음 (컴포넌트 동일 정책). display_order 오름차순 정렬은 repo 에서 처리.
     *
     * @param actorId 조회 행위자 UUID. (로깅 목적)
     * @param projectIdOrKey 프로젝트 UUID 또는 projectKey.
     * @return 활성 [CustomFieldDefinition] 리스트. 없으면 빈 리스트.
     * @throws CustomFieldProjectNotFoundException 프로젝트가 존재하지 않을 때.
     */
    @Transactional(readOnly = true)
    fun listByProject(
        actorId: UUID,
        projectIdOrKey: String,
    ): List<CustomFieldDefinition> {
        val projectId = resolveProject(projectIdOrKey)
        log.debug("custom_field_list_by_project projectId={} actor={}", projectId, actorId)
        return repo.findActiveByProject(projectId)
    }

    // ── private helpers ───────────────────────────────────────────────────────

    /**
     * projectIdOrKey 를 활성 프로젝트 UUID 로 해석한다.
     * 미존재 시 [CustomFieldProjectNotFoundException] 을 던진다.
     */
    private fun resolveProject(projectIdOrKey: String): UUID =
        projectLookup.resolve(projectIdOrKey)
            ?: throw CustomFieldProjectNotFoundException(projectIdOrKey)

    /**
     * 행위자의 권한을 검증한다. 거부 시 [CustomFieldAccessDeniedException] 을 던진다.
     */
    private fun assertPermission(
        actorId: UUID,
        permission: CustomFieldPermission,
        projectId: UUID,
    ) {
        if (!permissionResolver.hasPermission(actorId, permission, projectId)) {
            throw CustomFieldAccessDeniedException(actorId, projectId)
        }
    }

    /**
     * 활성 커스텀 필드 정의를 조회한다. 미존재 시 [CustomFieldNotFoundException] 을 던진다.
     */
    private fun findActiveField(
        fieldId: UUID,
        projectId: UUID,
    ): CustomFieldDefinition =
        repo.findById(fieldId, projectId)
            ?: throw CustomFieldNotFoundException(fieldId)

    /**
     * [CustomFieldDefinitionRepository.save] 를 호출하고, 23505 SQLState 위반은
     * [DuplicateCustomFieldKeyException] 으로 변환한다.
     */
    private fun trySave(
        definition: CustomFieldDefinition,
        key: String,
    ): CustomFieldDefinition =
        try {
            repo.save(definition)
        } catch (ex: DataIntegrityViolationException) {
            translateUniqueViolation(ex, key)
        } catch (ex: org.jooq.exception.IntegrityConstraintViolationException) {
            translateUniqueViolation(ex, key)
        }

    /**
     * 제약 위반 예외가 23505 unique_violation 에서 비롯됐으면 [DuplicateCustomFieldKeyException] 으로
     * 변환한다. 그렇지 않으면 원 예외를 그대로 re-throw 한다.
     */
    @Suppress("ThrowsCount")
    private fun translateUniqueViolation(
        ex: RuntimeException,
        key: String,
    ): Nothing {
        val sqlEx =
            generateSequence(ex.cause) { it.cause }
                .filterIsInstance<java.sql.SQLException>()
                .firstOrNull()
        if (sqlEx?.sqlState == SQL_STATE_UNIQUE_VIOLATION) {
            log.warn("Duplicate custom field key={}", key)
            throw DuplicateCustomFieldKeyException(key)
        }
        throw ex
    }
}
