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
        definition.toWorkflow()

        val removed = removedStatusKeys(key, definition)
        return PublishPreview(
            baseVersion = draft.baseVersion,
            currentVersion = workflow.version,
            removedStatusKeys = removed.toList(),
            pendingIssueCounts = pendingIssueCounts(removed),
        )
    }

    /**
     * 초안을 발행한다.
     *
     * @param actorId 발행자. 권한 판정과 이력의 `published_by` 에 함께 쓴다.
     * @param baseVersion 클라이언트가 들고 있던 버전. 지금 DB 값과 다르면 409.
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
        val definition = requireDraft(key, workflowId).definition
        definition.toWorkflow()

        val statusIds = requireStatusCatalog(key, definition)
        requireNoPendingIssues(key, definition)

        var versionNo = 0
        cache.withWriteLock(key) {
            if (!publishRepository.bumpVersionIfMatches(workflowId, baseVersion)) {
                // affected 0 은 「없음」과 「충돌」 둘 다를 뜻한다. 재조회로 404 와 409 를 가른다.
                val actual = requireLive(key).version
                throw WorkflowVersionConflictException(key, baseVersion, actual)
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
        val found = publishRepository.findStatusIdsByKeys(keys)
        val missing = keys.filterNot { found.containsKey(it) }
        if (missing.isNotEmpty()) {
            throw WorkflowInvalidRequestException(
                key,
                "상태 ${missing.joinToString(" · ")} 가 전역 카탈로그에 없다. 상태를 먼저 만들 것",
            )
        }
        return found
    }

    /** 발행하면 이 워크플로우에서 빠지는 상태 키. 현재 편성에는 있고 초안에는 없는 것들이다. */
    private fun removedStatusKeys(
        key: String,
        definition: WorkflowDraftDefinition,
    ): Set<String> {
        val current = cache.findByKey(key)?.states?.map { it.key }?.toSet() ?: emptySet()
        return current - definition.states.map { it.key }.toSet()
    }

    /** 빠지는 상태별 잔여 이슈 수. 0건인 상태는 담지 않는다 — 막을 이유가 없다. */
    private fun pendingIssueCounts(removed: Set<String>): Map<String, Long> =
        removed.associateWith { issueStatusUsagePort.countIssuesInStatus(it) }
            .filterValues { it > 0 }

    private fun requireNoPendingIssues(
        key: String,
        definition: WorkflowDraftDefinition,
    ) {
        val pending = pendingIssueCounts(removedStatusKeys(key, definition))
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
