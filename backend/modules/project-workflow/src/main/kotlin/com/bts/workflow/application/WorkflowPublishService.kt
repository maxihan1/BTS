// 워크플로우 발행 — 초안 검증 · 이관 필요 판정 · 정규 테이블 교체 · 이력 적재 · 캐시 무효화

package com.bts.workflow.application

import com.bts.shared.permission.WorkflowDefinitionPermission
import com.bts.shared.permission.WorkflowDefinitionPermissionResolver
import com.bts.workflow.application.port.IssueStatusUsagePort
import com.bts.workflow.cache.WorkflowCache
import com.bts.workflow.domain.WorkflowDraftDefinition
import com.bts.workflow.domain.exception.WorkflowInvalidRequestException
import com.bts.workflow.domain.exception.WorkflowNotFoundException
import com.bts.workflow.domain.exception.WorkflowPublishMappingRequiredException
import com.bts.workflow.domain.exception.WorkflowVersionConflictException
import com.bts.workflow.repository.WorkflowDraftRepository
import com.bts.workflow.repository.WorkflowPublicationRepository
import com.bts.workflow.repository.WorkflowPublishRepository
import com.bts.workflow.repository.WorkflowVersionRow
import com.bts.workflow.scheme.repository.ProjectWorkflowSchemeAssignmentRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * 초안을 정규 테이블로 발행한다.
 *
 * ### 발행 전에는 런타임이 초안을 보지 않는다
 * 이 FR 의 핵심이다. 초안은 `workflow_drafts` JSONB 에만 있고 읽기 경로
 * (`WorkflowRepository` · `WorkflowCache`)는 그 테이블을 **아예 조회하지 않는다**. 「샐 수 있는데
 * 안 새도록 조심한다」가 아니라 「샐 경로가 없다」가 초안을 JSONB 로 둔 이유다.
 *
 * ### 이관 매핑을 아직 받지 않는다 — Jira 방식의 앞 절반
 * Jira Cloud 는 발행 요청이 `statusMappings` 를 함께 받아 **실제로 이슈를 옮긴다**
 * (support.atlassian.com · developer.atlassian.com, 2026-08-26 조회). 그런데 이슈 UPDATE 는
 * issue-tracking BC 소유라 이 BC 가 실행할 수 없다(다중 BC 트랜잭션 금지 · `DATA.md §6`).
 *
 * 받지도 못할 파라미터를 미리 뚫는 것은 미완성 코드다(`DEVELOPMENT.md` 절대 규칙 16). 그래서
 * 지금은 **막고, 무엇이 막는지 알린다** — [WorkflowPublishMappingRequiredException.pending] 이
 * 상태별 잔여 건수를 담아 화면이 이관 모달을 그릴 재료가 된다. 매핑 수용과 이관 실행은 로드맵
 * **PR 7** 이 한 몸으로 채운다.
 *
 * ### `LongParameterList` 억제 사유
 * 발행은 초안·정의·이력·규칙·이슈사용량·권한·캐시 일곱 가지를 한 트랜잭션에서 조율하는
 * 오케스트레이션이고, 협력자 수가 곧 그 일의 크기다(detekt 임계는 7 **이상**에서 발동).
 * 규칙 재삽입은 이미 [DraftRuleWriter] 로 떼어 냈다 — 남은 일곱은 각자 다른 이유로 존재해
 * 더 묶으면 「발행 저장소」 같은 이름뿐인 묶음이 생기고 응집도가 오히려 나빠진다.
 * 전역 임계값은 건드리지 않는다.
 */
@Suppress("LongParameterList")
@Service
class WorkflowPublishService(
    private val draftRepository: WorkflowDraftRepository,
    private val publishRepository: WorkflowPublishRepository,
    private val publicationRepository: WorkflowPublicationRepository,
    private val ruleWriter: DraftRuleWriter,
    private val issueStatusUsagePort: IssueStatusUsagePort,
    private val schemeAssignmentRepository: ProjectWorkflowSchemeAssignmentRepository,
    private val permissionResolver: WorkflowDefinitionPermissionResolver,
    private val cache: WorkflowCache,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 발행하지 않고 무엇이 바뀌는지만 계산한다. Jira 의 `validateOnly` 에 해당한다.
     *
     * 화면이 발행 버튼을 누르기 전에 이 결과로 이관 모달을 미리 띄운다 — 눌러 보고 409 를 받는
     * 것보다 낫다.
     */
    @Transactional(readOnly = true)
    fun preview(
        actorId: UUID,
        key: String,
    ): PublishPreview {
        permissionResolver.requirePermission(actorId, WorkflowDefinitionPermission.UPDATE)
        val workflow = requireLive(key)
        val draft = requireDraft(key, workflow.id)
        val definition = draft.definition
        validateDefinition(key, definition)

        val removed = removedStatusKeys(workflow.id, definition)
        return PublishPreview(
            baseVersion = draft.baseVersion,
            currentVersion = workflow.version,
            removedStatusKeys = removed.toList(),
            pendingIssueCounts = pendingIssueCounts(workflow.id, removed),
        )
    }

    /**
     * 초안을 발행한다.
     *
     * ### 충돌 판정의 기준은 **저장된 초안의 `base_version`** 이다
     * 요청 본문 값이 아니다. 요청 값을 그대로 CAS 인자로 쓰면 화면이
     * [preview] 의 `currentVersion` 을 되실어 보내는 것만으로 락이 풀린다 — 게다가 409 응답 문구가
     * 「다시 불러온 뒤 시도하라」이므로 **그 안내를 따르는 것이 곧 우회 경로**가 된다.
     * 요청 값은 「화면이 무엇을 보고 있다고 믿는가」의 대조용으로만 쓰고, 저장된 값과 다르면 거절한다.
     *
     * @param actorId 발행자. 권한 판정과 이력의 `published_by` 에 함께 쓴다.
     * @param baseVersion 화면이 들고 있던 버전. 저장된 초안의 앵커와 다르면 409.
     * @return 이번 발행의 회차.
     * @throws WorkflowNotFoundException 워크플로우가 없거나 소프트 삭제됐을 때
     * @throws WorkflowInvalidRequestException 초안이 없거나 상태가 카탈로그에 없을 때
     * @throws WorkflowVersionConflictException 그 사이 다른 세션이 먼저 발행했을 때
     * @throws WorkflowPublishMappingRequiredException 빠지는 상태에 이슈가 남아 있을 때
     */
    @Transactional
    fun publish(
        actorId: UUID,
        key: String,
        baseVersion: Long,
    ): Int {
        permissionResolver.requirePermission(actorId, WorkflowDefinitionPermission.PUBLISH)
        val workflowId = requireLive(key).id
        val draft = requireDraft(key, workflowId)
        val definition = draft.definition
        validateDefinition(key, definition)
        requireKeyMatchesPath(key, definition)

        if (draft.baseVersion != baseVersion) {
            // 화면이 든 버전과 초안이 매인 버전이 다르다. 초안 내용은 저 앵커를 보고 만들어진 것이므로
            // 「화면이 새 버전을 봤다」고 해서 그 내용이 새 버전 위에서 옳아지지 않는다.
            throw WorkflowVersionConflictException(key, baseVersion, draft.baseVersion)
        }

        val statusIds = requireStatusCatalog(key, definition)
        requireNoPendingIssues(key, workflowId, definition)

        var versionNo = 0
        cache.withWriteLock(key) {
            if (!publishRepository.bumpVersionIfMatches(workflowId, draft.baseVersion)) {
                // affected 0 은 「없음」과 「충돌」 둘 다를 뜻한다. 재조회로 404 와 409 를 가른다.
                val actual = requireLive(key).version
                throw WorkflowVersionConflictException(key, draft.baseVersion, actual)
            }

            val transitionIds = publishRepository.replaceDefinition(workflowId, definition, statusIds)
            ruleWriter.writeAll(definition, transitionIds)

            versionNo = publicationRepository.nextVersionNo(workflowId)
            publicationRepository.insert(workflowId, versionNo, definition, actorId)
            draftRepository.deleteByWorkflowId(workflowId)
        }

        log.info("워크플로우 발행 완료. key={} versionNo={} baseVersion={}", key, versionNo, baseVersion)
        return versionNo
    }

    // ── 내부 ──────────────────────────────────────────────────────────────────

    private fun requireLive(key: String): WorkflowVersionRow {
        val row = publishRepository.findLiveByKey(key)
        return row ?: throw WorkflowNotFoundException(key)
    }

    private fun requireDraft(
        key: String,
        workflowId: UUID,
    ) = draftRepository.findByWorkflowId(workflowId)
        ?: throw WorkflowInvalidRequestException(key, "발행할 초안이 없다. 먼저 초안을 저장할 것")

    /**
     * 초안이 발행 가능한 상태인지 본다 — 상태·전환 invariant 와 규칙 관문을 한 자리에서 태운다.
     *
     * ### 왜 감싸는가
     * `toWorkflow()` 는 `IllegalArgumentException` 을, 관문은 [TransitionRuleRejected] 를 던지는데
     * 이 BC 의 advice 중 그 둘을 잡는 것이 없다. 맨몸으로 두면 **400 이어야 할 것이 500 으로**
     * 나가고, 화면은 재시도 말고 할 게 없으며 관리자는 무엇이 잘못됐는지 못 본다.
     * 저장 경로(`WorkflowDraftService.validate`)가 이미 같은 형태로 감싸고 있다.
     */
    private fun validateDefinition(
        key: String,
        definition: WorkflowDraftDefinition,
    ) {
        try {
            definition.toWorkflow()
        } catch (ex: IllegalArgumentException) {
            throw WorkflowInvalidRequestException(key, ex.message ?: "초안 정의가 규칙을 어겼다", ex)
        }
        try {
            ruleWriter.checkAll(definition)
        } catch (ex: TransitionRuleRejected) {
            throw WorkflowInvalidRequestException(key, ex.message ?: "전환 규칙이 관문을 지나지 못했다", ex)
        }
        requireExactlyOneInitial(key, definition)
    }

    /**
     * 발행하는 정의에는 시작 전환이 **정확히 1개** 있어야 한다.
     *
     * ### 왜 저장이 아니라 발행인가
     * 저장까지 막으면 새로 만든 워크플로우가 초안 편집기에서 처음부터 못 쓰인다 —
     * `WorkflowCommandService.create` 는 상태만 심고 전환을 하나도 만들지 않으므로, 편집기가
     * `GET /draft` 로 받은 본문을 그대로 `PUT` 해도 400 이 된다. 초안은 원래 불완전한 중간 상태다.
     * 운영에 나가는 순간에만 완전해야 하고, 그 순간이 발행이다.
     *
     * ### 왜 공용 `Workflow.of` 가 아닌가
     * 그 invariant 는 `initialCount <= 1` 이라 0개를 통과시키는데, `WorkflowRepository` 의 **읽기
     * 경로**도 그 함수를 쓴다. 거기서 `== 1` 로 조이면 INITIAL 0개인 기존 행 하나가 그 워크플로우
     * 조회 전체를 죽인다.
     *
     * 막지 않으면 발행이 `WorkflowCommandService.deleteTransition` 의 「최초 전환은 삭제할 수
     * 없습니다」를 우회하는 두 번째 경로가 된다. 그 뒤 `WorkflowKeyResolverImpl` 이 `?:` 로
     * `displayOrder` 최소 상태를 시작 상태로 쓰는데 그 값도 클라이언트가 정한다 — 「완료」에 0 을
     * 주면 그 워크플로우를 쓰는 모든 프로젝트의 신규 이슈가 완료 상태로 생성된다.
     */
    private fun requireExactlyOneInitial(
        key: String,
        definition: WorkflowDraftDefinition,
    ) {
        val count = definition.initialTransitionCount()
        if (count != 1) {
            throw WorkflowInvalidRequestException(
                key,
                "이슈가 처음 놓일 상태를 정하는 시작 전환이 정확히 1개여야 발행할 수 있다 (현재 ${count}개)",
            )
        }
    }

    /**
     * 초안의 상태 키가 전부 전역 카탈로그에 있는지 본다.
     *
     * 발행이 상태를 슬쩍 만들면 카탈로그 규칙(V203 `uq_statuses_lower_name` — 이름 대소문자 무시
     * 유일)을 우회하는 두 번째 경로가 생긴다. 상태 생성은 `POST /api/v1/statuses` 의 일이다.
     */
    private fun requireStatusCatalog(
        key: String,
        definition: WorkflowDraftDefinition,
    ): Map<String, UUID> {
        val keys = definition.states.map { it.key }
        val found = publishRepository.findCatalogStatuses(keys)
        val missing = keys.filterNot { found.containsKey(it) }
        if (missing.isNotEmpty()) {
            throw WorkflowInvalidRequestException(
                key,
                "상태 ${missing.joinToString(" · ")} 가 전역 카탈로그에 없다. 상태를 먼저 만들 것",
            )
        }
        // 받아 놓고 버리지 않는다 — 초안의 이름·카테고리가 카탈로그와 다르면 거절한다(절대 규칙 16).
        requireStatesMatchCatalog(key, definition, found)
        return found.mapValues { it.value.id }
    }

    /**
     * 발행하면 이 워크플로우에서 빠지는 상태 키. 현재 편성에는 있고 초안에는 없는 것들이다.
     *
     * ### 근거는 DB 다 — 캐시가 아니고, 못 읽으면 빈 집합도 아니다
     * 종전에는 `cache.findByKey(key)?.states … ?: emptySet()` 이었다. 두 가지가 틀렸다.
     *
     * 1. **캐시는 스테일할 수 있다.** [com.bts.workflow.cache.WorkflowCache] 가 무효화를 커밋
     *    전에 하고 읽기 경로는 락을 잡지 않아, 그 창에 들어온 리더가 옛 정의를 다시 올려 놓는다.
     *    좌변이 실제보다 작아지면 **이슈가 남은 상태가 차집합에서 빠져** 발행이 그냥 통과한다.
     * 2. **`?: emptySet()` 은 fail-open 이다.** 판정 근거를 못 읽었으면 「빠지는 것 없음」이
     *    아니라 거부여야 한다. 판정이 사라지는데 예외도 로그도 없는 것이 가장 나쁜 형태다.
     */
    private fun removedStatusKeys(
        workflowId: UUID,
        definition: WorkflowDraftDefinition,
    ): Set<String> {
        val current = publishRepository.findComposedStatusKeys(workflowId)
        return current - definition.states.map { it.key }.toSet()
    }

    /**
     * 빠지는 상태별 잔여 이슈 수. 0건인 상태는 담지 않는다 — 막을 이유가 없다.
     *
     * ### 세는 범위는 이 워크플로우를 쓰는 프로젝트로 좁힌다
     * 상태 키는 전역이라 키만으로 세면 **다른 워크플로우를 쓰는 이슈까지** 잡혀 발행이 과하게
     * 막힌다. 스킴 할당을 거슬러 프로젝트 id 를 얻어 그 범위 안에서만 센다.
     *
     * ### 조회는 한 번뿐이다 (NFR N1)
     * 아래 `associateWith` 는 빠지는 상태 수만큼 돈다. 그 안에서 프로젝트를 되물으면 상태마다
     * 3단 JOIN 이 한 번씩 나간다 — 스코프는 상태와 무관하므로 루프 **밖에서** 한 번만 읽는다.
     */
    private fun pendingIssueCounts(
        workflowId: UUID,
        removed: Set<String>,
    ): Map<String, Long> {
        val projectIds = schemeAssignmentRepository.findProjectRefsByWorkflowId(workflowId).map { it.id }.toSet()
        return removed
            .associateWith { issueStatusUsagePort.countIssuesInStatus(it, projectIds) }
            .filterValues { it > 0 }
    }

    private fun requireNoPendingIssues(
        key: String,
        workflowId: UUID,
        definition: WorkflowDraftDefinition,
    ) {
        val pending = pendingIssueCounts(workflowId, removedStatusKeys(workflowId, definition))
        if (pending.isNotEmpty()) {
            throw WorkflowPublishMappingRequiredException(key, pending)
        }
    }
}

/**
 * 발행 전 미리보기 결과.
 *
 * @property baseVersion 초안이 들고 있는 버전. 발행 요청에 그대로 실어 보낸다.
 * @property currentVersion 지금 DB 의 버전. [baseVersion] 과 다르면 이미 남이 발행한 것이다.
 * @property removedStatusKeys 발행하면 이 워크플로우에서 빠지는 상태 키.
 * @property pendingIssueCounts 그중 이슈가 남아 있는 상태와 그 건수. 비어 있지 않으면 발행이 막힌다.
 */
data class PublishPreview(
    val baseVersion: Long,
    val currentVersion: Long,
    val removedStatusKeys: List<String>,
    val pendingIssueCounts: Map<String, Long>,
)
