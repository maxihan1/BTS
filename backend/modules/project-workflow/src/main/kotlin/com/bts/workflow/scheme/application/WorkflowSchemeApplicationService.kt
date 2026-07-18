// 워크플로우 스킴 Application Service — Scheme CRUD 5 메서드 + Mapping CRUD 2 메서드 (addMapping/deleteMapping)
// + Project assign 2 메서드 (assignToProject/findAssignedScheme) + View 2 메서드 (findDetail/listWithCounts)

package com.bts.workflow.scheme.application

import com.bts.shared.issue.IssueTypeId
import com.bts.shared.permission.WorkflowSchemePermission
import com.bts.shared.permission.WorkflowSchemePermissionResolver
import com.bts.shared.permission.WorkflowSchemeScope
import com.bts.workflow.domain.exception.WorkflowNotFoundException
import com.bts.workflow.port.outbound.ActorId
import com.bts.workflow.port.outbound.toUuid
import com.bts.workflow.repository.WorkflowRepository
import com.bts.workflow.scheme.adapter.outbound.WorkflowSchemeEventPublisher
import com.bts.workflow.scheme.application.port.IssueTypeLookupPort
import com.bts.workflow.scheme.domain.ProjectWorkflowSchemeAssignment
import com.bts.workflow.scheme.domain.SchemeIssueTypeMapping
import com.bts.workflow.scheme.domain.WorkflowScheme
import com.bts.workflow.scheme.domain.WorkflowSchemeKey
import com.bts.workflow.scheme.event.WorkflowSchemeAssignedEvent
import com.bts.workflow.scheme.exception.IssueTypeNotFoundException
import com.bts.workflow.scheme.exception.SchemeInUseException
import com.bts.workflow.scheme.exception.SchemeStandardFieldLockedException
import com.bts.workflow.scheme.exception.SchemeStandardNotDeletableException
import com.bts.workflow.scheme.exception.WorkflowSchemeNotFoundException
import com.bts.workflow.scheme.repository.ProjectWorkflowSchemeAssignmentRepository
import com.bts.workflow.scheme.repository.SchemeCountRow
import com.bts.workflow.scheme.repository.SchemeIssueTypeMappingRepository
import com.bts.workflow.scheme.repository.WorkflowSchemeRepository
import com.bts.workflow.scheme.web.dto.MappingResponseDetail
import com.bts.workflow.scheme.web.dto.WorkflowSchemeDetailResponse
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

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
 * @param assignmentRepo 프로젝트-스킴 할당 Repository (S7 사용 중 검증용, assignToProject 에도 사용).
 * @param mappingRepo 스킴-이슈타입 매핑 Repository.
 * @param eventPublisher 워크플로우 스킴 도메인 이벤트 pgmq publisher (Propagation.MANDATORY).
 * @param permissionResolver 스킴 권한 평가 outbound port.
 * @param workflowRepo 워크플로우 조회 Repository.
 * @param issueTypeLookupPort 이슈타입 정보 조회 outbound port (issue-tracking BC SPI).
 *
 * @suppress TooManyFunctions — Scheme CRUD(create/find/findDetail/listWithCounts/update/softDelete/list) 7 +
 * Mapping 관리(addMapping/addMappingByKeys/deleteMapping) 3 + Assignment(assignToProject/findAssignedScheme) 2
 * + private helper(validateStandardFieldNotChanged, buildMappingDetail) 2 = 14개.
 * 스킴 Application Service 의 본질적 use case 범위. 별도 서비스로 분리하면 트랜잭션 경계가 깨지거나 순환 의존이 발생한다.
 */
@Suppress("TooManyFunctions", "LongParameterList")
@Service
@Transactional
class WorkflowSchemeApplicationService(
    private val schemeRepo: WorkflowSchemeRepository,
    private val assignmentRepo: ProjectWorkflowSchemeAssignmentRepository,
    private val mappingRepo: SchemeIssueTypeMappingRepository,
    private val eventPublisher: WorkflowSchemeEventPublisher,
    private val permissionResolver: WorkflowSchemePermissionResolver,
    private val workflowRepo: WorkflowRepository,
    private val issueTypeLookupPort: IssueTypeLookupPort,
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
        permissionResolver.requirePermission(
            actor.toUuid(),
            WorkflowSchemePermission.MANAGE_SCHEME,
            WorkflowSchemeScope.Global,
        )
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
    @Suppress("MaxLineLength") // 단순 위임 한 줄 — 분할 시 function-signature ktlint 위반
    @Transactional(readOnly = true)
    fun find(key: WorkflowSchemeKey): WorkflowScheme = schemeRepo.findByKey(key) ?: throw WorkflowSchemeNotFoundException(key.value)

    /**
     * key 로 활성 스킴을 매핑 + 카운트와 함께 단건 조회한다.
     *
     * 스킴 조회 후 매핑 목록 → IssueTypeLookupPort → workflowRepo.findByIds 순으로 cross-BC lookup 을 수행한다.
     * 단일 `@Transactional(readOnly = true)` 안에서 실행된다. IssueTypeLookupPort adapter 가
     * `@Transactional(readOnly = true)` 이므로 트랜잭션 Propagation 호환 (REQUIRED 기본값 상속).
     *
     * @param key 조회할 스킴 키.
     * @return 매핑 + 카운트 동봉 응답 DTO.
     * @throws WorkflowSchemeNotFoundException key 에 해당하는 활성 스킴이 없을 때.
     */
    @Transactional(readOnly = true)
    fun findDetail(key: WorkflowSchemeKey): WorkflowSchemeDetailResponse {
        val scheme = schemeRepo.findByKey(key) ?: throw WorkflowSchemeNotFoundException(key.value)
        val schemeId = requireNotNull(scheme.id) { "scheme.id must not be null" }
        val mappings = mappingRepo.findBySchemeId(schemeId)
        val usedByProjectsCount = schemeRepo.countAssignedProjects(schemeId)
        val mappingDetails = buildMappingDetails(mappings)
        return WorkflowSchemeDetailResponse.from(scheme, usedByProjectsCount, mappingDetails)
    }

    /**
     * 활성 스킴 전체 목록을 프로젝트 수 + 매핑 수 카운트와 함께 반환한다.
     *
     * `schemeRepo.findAllWithCounts()` 단일 jOOQ 쿼리로 카운트를 일괄 조회한다.
     * 목록 응답에서는 mappings 필드가 빈 리스트로 반환된다 (성능 최적화 — 목록은 상세 불필요).
     *
     * @return [WorkflowSchemeDetailResponse] 목록. 비어 있을 수 있음.
     */
    @Transactional(readOnly = true)
    fun listWithCounts(): List<WorkflowSchemeDetailResponse> =
        schemeRepo.findAllWithCounts().map { row ->
            toListItemResponse(row)
        }

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
        permissionResolver.requirePermission(
            actor.toUuid(),
            WorkflowSchemePermission.MANAGE_SCHEME,
            WorkflowSchemeScope.Global,
        )
        val existing = schemeRepo.findByKey(key) ?: throw WorkflowSchemeNotFoundException(key.value)

        if (existing.isDefault) {
            validateStandardFieldNotChanged(existing, newName, newDescription, newIsDefault)
        }

        log.info("update scheme: actor={} key={}", actor.raw, key.value)
        val updated =
            WorkflowScheme.reconstruct(
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
    fun softDelete(
        actor: ActorId,
        key: WorkflowSchemeKey,
    ) {
        permissionResolver.requirePermission(
            actor.toUuid(),
            WorkflowSchemePermission.MANAGE_SCHEME,
            WorkflowSchemeScope.Global,
        )
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

    /**
     * 스킴에 이슈타입-워크플로우 매핑을 추가한다.
     *
     * [issueTypeId] 가 null 이면 default mapping (명시적 매핑이 없는 이슈 타입 전체에 적용) 이다.
     * 스킴 당 default mapping 은 최대 1개 허용된다.
     *
     * ## UNIQUE 위반 시 예외 전파
     * - (scheme_id, issue_type_id) 중복 → [com.bts.workflow.scheme.exception.MappingDuplicateException]
     * - (scheme_id) WHERE issue_type_id IS NULL 중복 (default mapping 이미 존재) →
     *   [com.bts.workflow.scheme.exception.MappingDefaultDuplicateException]
     *
     * UNIQUE 위반 감지는 Repository 계층(`ix_scheme_default_mapping` partial UNIQUE INDEX)에서 수행되어
     * 위 두 예외 중 하나로 변환되어 도착한다. Application layer 는 예외를 그대로 전파한다.
     *
     * ## EC-2 note
     * 커스텀 스킴 생성 직후 default mapping 이 0개인 상태를 감지해 강제 추가를 유도하는 검증은
     * 후속 task (assignToProject 흐름) 에서 처리한다.
     *
     * @param actor 작업 수행 행위자. MANAGE_SCHEME 권한이 필요하다.
     * @param schemeKey 매핑을 추가할 스킴 키.
     * @param issueTypeId 매핑 대상 이슈 타입 식별자. null = default mapping.
     * @param workflowId 사용할 워크플로우 UUID.
     * @return 저장된 매핑 (DB 생성 id 포함).
     * @throws WorkflowSchemeNotFoundException 스킴이 없을 때.
     * @throws com.bts.workflow.scheme.exception.MappingDuplicateException (scheme_id, issue_type_id) UNIQUE 위반 시.
     * @throws com.bts.workflow.scheme.exception.MappingDefaultDuplicateException default mapping 중복 시.
     */
    fun addMapping(
        actor: ActorId,
        schemeKey: WorkflowSchemeKey,
        issueTypeId: IssueTypeId?,
        workflowId: UUID,
    ): SchemeIssueTypeMapping {
        permissionResolver.requirePermission(
            actor.toUuid(),
            WorkflowSchemePermission.MANAGE_SCHEME,
            WorkflowSchemeScope.Global,
        )
        val scheme = schemeRepo.findByKey(schemeKey) ?: throw WorkflowSchemeNotFoundException(schemeKey.value)
        val schemeId = requireNotNull(scheme.id) { "scheme.id must not be null" }
        log.info("addMapping: actor={} schemeKey={} issueTypeId={}", actor.raw, schemeKey.value, issueTypeId?.value)
        val mapping =
            SchemeIssueTypeMapping(
                id = null,
                schemeId = schemeId,
                issueTypeId = issueTypeId,
                workflowId = workflowId,
                createdAt = Instant.now(),
            )
        return mappingRepo.addMapping(mapping)
    }

    /**
     * 스킴에 이슈타입-워크플로우 매핑을 key 기반으로 추가한다.
     *
     * 컨트롤러 레이어에서 받은 [issueTypeKey] / [workflowKey] 문자열을 DB ID 로 변환한 뒤
     * [addMapping] 에 위임한다.
     *
     * - [issueTypeKey] null → default mapping (issue_type_id IS NULL).
     * - [workflowKey] 미존재 → [WorkflowNotFoundException] (404).
     * - [issueTypeKey] 미존재 → [IssueTypeNotFoundException] (404).
     *
     * @param actor 작업 수행 행위자. MANAGE_SCHEME 권한이 필요하다.
     * @param schemeKey 매핑을 추가할 스킴 키.
     * @param issueTypeKey 매핑 대상 이슈 타입 키. null = default mapping.
     * @param workflowKey 사용할 워크플로우 키.
     * @return 저장된 매핑 (DB 생성 id 포함).
     * @throws WorkflowSchemeNotFoundException 스킴이 없을 때.
     * @throws WorkflowNotFoundException workflowKey 에 해당하는 워크플로우가 없을 때.
     * @throws IssueTypeNotFoundException issueTypeKey 에 해당하는 이슈 타입이 없을 때.
     */
    fun addMappingByKeys(
        actor: ActorId,
        schemeKey: WorkflowSchemeKey,
        issueTypeKey: String?,
        workflowKey: String,
    ): SchemeIssueTypeMapping {
        val workflowId =
            workflowRepo.findIdByKey(workflowKey)
                ?: throw WorkflowNotFoundException(workflowKey)

        val issueTypeId: IssueTypeId? =
            if (issueTypeKey != null) {
                mappingRepo.findIssueTypeIdByKey(issueTypeKey)
                    ?: throw IssueTypeNotFoundException(issueTypeKey)
            } else {
                null
            }

        return addMapping(actor, schemeKey, issueTypeId, workflowId)
    }

    /**
     * 스킴에서 이슈타입-워크플로우 매핑을 삭제한다.
     *
     * 존재하지 않는 [mappingId] 에 대해서는 no-op 으로 처리된다 (Repository 동작 일치).
     *
     * @param actor 작업 수행 행위자. MANAGE_SCHEME 권한이 필요하다.
     * @param mappingId 삭제할 매핑 PK.
     */
    fun deleteMapping(
        actor: ActorId,
        mappingId: Long,
    ) {
        permissionResolver.requirePermission(
            actor.toUuid(),
            WorkflowSchemePermission.MANAGE_SCHEME,
            WorkflowSchemeScope.Global,
        )
        log.info("deleteMapping: actor={} mappingId={}", actor.raw, mappingId)
        mappingRepo.deleteMapping(mappingId)
    }

    /**
     * 프로젝트에 워크플로우 스킴을 배정(UPSERT)하고 배정 결과를 반환한다.
     *
     * ## 권한
     * 사용자 명시 배정(실 actor UUID)은 [WorkflowSchemePermission.ASSIGN_SCHEME] —
     * [WorkflowSchemeScope.Project] 범위 검증을 통과해야 한다(프로젝트 어드민 레벨 권한).
     * 단, [SYSTEM_ACTOR](nil UUID sentinel)로 호출된 EC-1 D10 auto-assign 은 권한 검사를 우회한다
     * (아래 "## EC-1 D10 auto-assign 권한 우회" 참조).
     *
     * ## 트랜잭션
     * `@Transactional` 클래스 어노테이션 상속. [assignmentRepo.saveAssignment] 와
     * [eventPublisher.publish] 가 동일 트랜잭션 안에서 실행된다 (outbox 패턴).
     * [eventPublisher] 는 [Propagation.MANDATORY] 이므로 별도 처리 불필요.
     *
     * ## EC-1 D10 auto-assign 권한 우회 (SYSTEM_ACTOR 한정)
     * [findAssignedScheme] · [com.bts.workflow.scheme.adapter.inbound.WorkflowResolverImpl.resolveFor] 이
     * assignment 없는 신규 프로젝트를 감지하면 [SYSTEM_ACTOR] 로 이 메서드를 호출한다
     * (`assigned_by = 00000000-0000-0000-0000-000000000000`).
     *
     * **왜 우회하는가.** prod 판정기
     * [com.atlas.bts.identity.permission.IdentityAccessWorkflowSchemePermissionResolver] 의 Project 범위
     * 판정은 `membershipRepo.findByProjectAndUser` 로 actor 의 **프로젝트 멤버십**을 먼저 요구한다.
     * SYSTEM_ACTOR 는 `users` 에 존재하지 않는 합성 sentinel 이라 어떤 프로젝트의 멤버도 될 수 없으므로,
     * 우회하지 않으면 EC-1 D10 auto-assign 이 prod 에서 **항상** [WorkflowSchemeAccessDeniedException](500)
     * 으로 실패한다(FR-PJ-01 T12 S10 이 실측으로 드러낸 선재 결함, `infra/local/seed-project.sql:10-12` 문서화).
     *
     * **왜 안전한가 (악용 표면 없음). load-bearing 방어는 아래 3가지다 — 이 함수의 외부 도달 경로는
     * project-workflow 의 [ProjectWorkflowSchemeController] 이지 issue-tracking 이 아님에 주의.**
     * - **외부 진입점이 우회 전에 무조건 권한을 검사한다.** 유일한 외부 도달 경로
     *   [ProjectWorkflowSchemeController.assignScheme] 은 이 서비스를 호출하기 **전에** 실 actor 로
     *   `permissionResolver.requirePermission(ASSIGN_SCHEME, Project)` 를 수행한다. 이 우회는 그 컨트롤러
     *   게이트를 건드리지 않는다 — 설령 nil actor 가 컨트롤러에 도달해도 서비스 우회 전에 컨트롤러가 막는다.
     *   (⚠️ project-workflow `ActorId`([port/outbound/PermissionResolver.kt])는 nil UUID 를 거부하지 않는다 —
     *   UUID 정규식만 검사한다. issue-tracking `ActorId` 와 달리 nil 방어가 없으므로, 컨트롤러 선게이트를
     *   "중복"으로 제거하면 이 우회 근거가 무너진다. 컨트롤러 `requirePermission` 은 load-bearing 이다.)
     * - **nil sentinel 은 실제 주체가 아니다.** prod 판정기가 비멤버를 거부하므로(위 "왜 우회하는가"), nil 은
     *   설령 검사에 도달해도 어차피 거부된다. 우회는 그 거부를 "auto-assign 만" 통과시키는 것이지 실 사용자에게
     *   권한을 부여하지 않는다. 실 actor UUID(RFC 4122 V4)는 nil sentinel 과 충돌 불가라 사용자 배정 경로는 그대로 권한 검사.
     * - **내부 호출부는 표준 스킴(`software-scheme`)만 배정한다** — 커스텀 스킴을 SYSTEM_ACTOR 로 배정하는
     *   경로는 존재하지 않는다. 호출부는 [findAssignedScheme]·[WorkflowResolverImpl.resolveFor] 둘뿐, 둘 다 하드코딩.
     *
     * @param actor 작업 수행 행위자. 실 actor 는 ASSIGN_SCHEME 권한이 필요하다.
     *   [SYSTEM_ACTOR](EC-1 D10 auto-assign)는 권한 검사를 우회한다.
     * @param projectId 스킴을 배정할 프로젝트 UUID (projects.id UUID — V202 에서 BIGINT → UUID 정정).
     * @param projectKey 권한 범위 결정에 사용할 프로젝트 키 (예. "ATLAS").
     * @param schemeKey 배정할 스킴 키.
     * @return 저장된 [ProjectWorkflowSchemeAssignment].
     * @throws WorkflowSchemeNotFoundException [schemeKey] 에 해당하는 활성 스킴이 없을 때.
     */
    fun assignToProject(
        actor: ActorId,
        projectId: UUID,
        projectKey: String,
        schemeKey: WorkflowSchemeKey,
    ): ProjectWorkflowSchemeAssignment {
        val actorUuid = actor.toUuid()
        // EC-1 D10 auto-assign 우회 — SYSTEM_ACTOR(nil UUID sentinel)만 권한 검사를 건너뛴다.
        // 사용자 명시 배정(실 actor UUID)은 아래 requirePermission 을 그대로 통과해야 한다.
        // 우회가 안전한 이유·범위는 아래 KDoc "## EC-1 D10 auto-assign 권한 우회" 참조.
        if (actorUuid != SYSTEM_ACTOR_UUID) {
            permissionResolver.requirePermission(
                actorUuid,
                WorkflowSchemePermission.ASSIGN_SCHEME,
                WorkflowSchemeScope.Project(projectKey),
            )
        }
        val scheme = schemeRepo.findByKey(schemeKey) ?: throw WorkflowSchemeNotFoundException(schemeKey.value)
        val schemeId = requireNotNull(scheme.id) { "scheme.id must not be null" }

        val now = Instant.now()
        val assignment =
            ProjectWorkflowSchemeAssignment(
                projectId = projectId,
                workflowSchemeId = schemeId,
                assignedAt = now,
                assignedBy = actorUuid,
            )
        assignmentRepo.saveAssignment(assignment)
        log.info("assignToProject: actor={} projectId={} schemeKey={}", actor.raw, projectId, schemeKey.value)
        eventPublisher.publish(
            WorkflowSchemeAssignedEvent(
                schemeId = schemeId,
                projectId = projectId,
                assignedBy = actorUuid,
                occurredAt = now,
            ),
        )
        return assignment
    }

    /**
     * 프로젝트에 배정된 워크플로우 스킴을 반환한다.
     *
     * ## EC-1 D10 — software-scheme auto-assign
     * assignment 가 없는 신규 프로젝트의 경우, `software-scheme` 을 SYSTEM_ACTOR 로 1회 자동 배정한다.
     * 배정 후 해당 스킴을 반환한다. 이후 호출부터는 assignment 가 존재하므로 auto-assign 이 재실행되지 않는다.
     *
     * @param projectId 조회할 프로젝트 UUID (projects.id UUID — V202 에서 BIGINT → UUID 정정).
     * @param projectKey auto-assign 시 권한 범위 결정에 사용할 프로젝트 키.
     * @return 배정된 [WorkflowScheme].
     * @throws WorkflowSchemeNotFoundException assignment 는 있지만 scheme 이 soft-delete 된 경우.
     */
    @Transactional
    fun findAssignedScheme(
        projectId: UUID,
        projectKey: String,
    ): WorkflowScheme {
        val assignment =
            assignmentRepo.findByProjectId(projectId)
                ?: run {
                    // EC-1 D10 — assignment 없으면 software-scheme 1회 auto-assign (assigned_by = SYSTEM_ACTOR)
                    log.info(
                        "findAssignedScheme: no assignment for projectId={}, auto-assigning software-scheme",
                        projectId,
                    )
                    val autoAssignment = assignToProject(SYSTEM_ACTOR, projectId, projectKey, SOFTWARE_SCHEME_KEY)
                    return schemeRepo.findById(autoAssignment.workflowSchemeId)
                        ?: throw WorkflowSchemeNotFoundException(SOFTWARE_SCHEME_KEY.value)
                }
        return schemeRepo.findById(assignment.workflowSchemeId)
            ?: throw WorkflowSchemeNotFoundException(assignment.workflowSchemeId.value.toString())
    }

    // ── 내부 헬퍼 ─────────────────────────────────────────────────────────────

    /**
     * 매핑 목록에서 IssueType + Workflow 정보를 조회해 [MappingResponseDetail] 리스트를 빌드한다.
     *
     * @param mappings 변환할 매핑 도메인 객체 목록.
     * @return [MappingResponseDetail] 목록.
     */
    private fun buildMappingDetails(mappings: List<SchemeIssueTypeMapping>): List<MappingResponseDetail> {
        if (mappings.isEmpty()) return emptyList()

        val issueTypeIds = mappings.mapNotNull { it.issueTypeId }
        val issueTypeRefMap = if (issueTypeIds.isEmpty()) emptyMap() else issueTypeLookupPort.lookup(issueTypeIds)

        val workflowIds = mappings.map { it.workflowId }.distinct()
        val workflowMap = workflowRepo.findByIds(workflowIds)

        return mappings.map { mapping ->
            val issueTypeRef = mapping.issueTypeId?.let { issueTypeRefMap[it] }
            val workflow =
                workflowMap[mapping.workflowId]
                    ?: error("Workflow not found for id=${mapping.workflowId}")
            MappingResponseDetail.from(mapping, issueTypeRef, workflow)
        }
    }

    /**
     * [SchemeCountRow] 를 목록 응답 [WorkflowSchemeDetailResponse] 로 변환한다.
     *
     * 목록 응답에서는 mappings 는 빈 리스트로, mappingsCount 는 DB JOIN 카운트를 반영한다.
     *
     * @param row DB COUNT JOIN 결과 행.
     * @return 카운트 동봉 응답 DTO (mappings = emptyList()).
     */
    private fun toListItemResponse(row: SchemeCountRow): WorkflowSchemeDetailResponse =
        WorkflowSchemeDetailResponse(
            id = requireNotNull(row.scheme.id?.value) { "WorkflowScheme.id must not be null" },
            key = row.scheme.key.value,
            name = row.scheme.name,
            description = row.scheme.description,
            isDefault = row.scheme.isDefault,
            createdAt = row.scheme.createdAt.toString(),
            updatedAt = row.scheme.updatedAt.toString(),
            usedByProjectsCount = row.usedByProjectsCount,
            mappingsCount = row.mappingsCount,
            mappings = emptyList(),
        )

    companion object {
        /**
         * 시스템 자동 배정에 사용하는 sentinel UUID.
         *
         * 사용자 요청 없이 시스템이 자동 배정(EC-1 D10 software-scheme auto-assign)을 수행할 때
         * `assigned_by` 컬럼에 기록된다.
         * 실제 사용자 UUID 는 RFC 4122 V4 형식이므로 올-제로 UUID 와 충돌하지 않는다.
         */
        val SYSTEM_ACTOR_UUID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000000")

        /** EC-1 D10 auto-assign 에 사용하는 system actor [ActorId]. */
        val SYSTEM_ACTOR: ActorId = ActorId(SYSTEM_ACTOR_UUID.toString())

        /** EC-1 D10 auto-assign 기본 스킴 키. */
        val SOFTWARE_SCHEME_KEY: WorkflowSchemeKey = WorkflowSchemeKey("software-scheme")
    }

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
