// 웹훅 규칙을 여러 이슈에 건다 — 몸 조건으로 건너뛰기 · 상태 검색 · 몸의 키 목록 · 200 상한 · 이슈마다 큐 적재 (BTS-F2-01)

package com.bts.automation.application

import com.bts.automation.adapter.AutomationExecutionEnqueuer
import com.bts.automation.adapter.RuleExecutionRepository
import com.bts.automation.domain.AutomationRule
import com.bts.automation.domain.TriggerConfig
import com.bts.automation.domain.TriggerType
import com.bts.automation.domain.WebhookTargeting
import com.bts.shared.search.AqlField
import com.bts.shared.search.AqlNode
import com.bts.shared.search.AqlOperator
import com.bts.shared.search.AqlValue
import com.bts.shared.search.IssueSearchPort
import com.bts.shared.search.IssueSearchQuery
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

/**
 * WEBHOOK 규칙의 triggerConfig 대상 고르기 칸([WebhookTargeting])을 웹훅 몸에 대어 규칙이 걸릴 이슈를 고르고,
 * 키마다 `issueKey` 를 붙인 메시지를 한 트랜잭션으로 큐에 다시 넣는다. 그 메시지들이 지금 경로(프로젝트 경계 게이트 ·
 * 조건 게이트 · 억제창 · 이력)를 그대로 탄다 — 다른 프로젝트 키는 이슈마다 [ActionExecutor] 가 지금처럼 막는다.
 *
 * - 대상이 [MAX_TARGETS] 를 넘으면 하나도 안 넣고 「상한 넘음」 이력 한 건([ActionExecutor.targetLimitExceeded])을 남긴다.
 * - 상태 검색은 룰 작성자([AutomationRule.createdBy]) 가시성이다 — 조건 게이트와 같은 이유(작성자가 볼 수 있는 것만 관측).
 *
 * @param issueSearchPort issue-tracking 이 구현하는 검색 포트. 어댑터가 없는 test-boot 에선 포트의 기본 구현(빈 결과)을 쓴다.
 */
@Component
class WebhookFanOut(
    private val issueSearchPort: ObjectProvider<IssueSearchPort>,
    private val enqueuer: AutomationExecutionEnqueuer,
    private val actionExecutor: ActionExecutor,
    private val ruleExecutionRepository: RuleExecutionRepository,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 웹훅 메시지 하나를 대상 고르기로 처리한다. 여기서 끝냈으면(건너뜀 · 큐에 나눠 넣음 · 상한 넘음) `true`,
     * 대상 칸이 없거나 이미 대상 이슈가 있으면 `false` — 호출자가 지금처럼 한 번 실행한다.
     *
     * @param issueKey 메시지에 이미 대상 이슈가 있으면(이슈마다 나눈 메시지 · 몸에 issueKey 를 실은 웹훅) 다시 고르지 않는다.
     */
    fun handle(
        rule: AutomationRule,
        triggerEvent: JsonNode,
        issueKey: String?,
    ): Boolean {
        val targeting = TriggerConfig.webhookTargeting(rule.triggerConfig)
        val skip = targeting.skipWhenTrue?.let { triggerEvent.path(it).asBoolean(false) } == true
        val targeted = issueKey == null && (targeting.keysField != null || targeting.targetStates != null)
        when {
            skip -> log.info("automation_webhook_skipped ruleId={} field={}", rule.id, targeting.skipWhenTrue)
            targeted -> fanOut(rule, targeting, triggerEvent)
        }
        return skip || targeted
    }

    private fun fanOut(
        rule: AutomationRule,
        targeting: WebhookTargeting,
        triggerEvent: JsonNode,
    ) {
        val (keys, total) = targetKeys(rule, targeting, triggerEvent)
        if (total > MAX_TARGETS) {
            val now = Instant.now()
            val result = actionExecutor.targetLimitExceeded(rule)
            ruleExecutionRepository.save(
                RuleExecution(
                    UUID.randomUUID(), rule.id, rule.projectKey, TriggerType.WEBHOOK, triggerEvent,
                    issueKey = null, status = result.status, outcomes = result.outcomes,
                    replayedFrom = null, startedAt = now, finishedAt = now,
                ),
            )
            log.warn("automation_webhook_target_limit_exceeded ruleId={} count={}", rule.id, total)
            return
        }
        val base = triggerEvent as? ObjectNode ?: objectMapper.createObjectNode()
        enqueuer.enqueueAll(rule.id, TriggerType.WEBHOOK, keys.map { base.deepCopy().put(FIELD_ISSUE_KEY, it) })
        log.info("automation_webhook_fanned_out ruleId={} count={}", rule.id, keys.size)
    }

    /** 대상 키와 전체 수. 상태 검색은 [MAX_TARGETS] 개까지만 받고 전체 수는 검색 총수로 본다. */
    private fun targetKeys(
        rule: AutomationRule,
        targeting: WebhookTargeting,
        triggerEvent: JsonNode,
    ): Pair<List<String>, Long> {
        val keysField = targeting.keysField
        if (keysField != null) {
            val keys =
                triggerEvent.path(keysField)
                    .filter { it.isTextual && it.asText().isNotBlank() }
                    .map { it.asText() }
                    .distinct()
            return keys to keys.size.toLong()
        }
        val states = targeting.targetStates.orEmpty().map { AqlValue.Str(it) }
        val query =
            IssueSearchQuery(
                projectKey = rule.projectKey,
                ast = AqlNode.Comparison(AqlField("status"), AqlOperator.IN, states),
                sort = emptyList(),
                viewerUserId = rule.createdBy,
                page = 0,
                size = MAX_TARGETS,
            )
        val page = issueSearchPort.getIfAvailable { object : IssueSearchPort {} }.search(query)
        return page.items.map { it.key } to page.total
    }

    companion object {
        /** 한 번에 걸 수 있는 이슈 수 — 넘으면 하나도 안 바꾼다. */
        const val MAX_TARGETS = 200

        private const val FIELD_ISSUE_KEY = "issueKey"
    }
}
