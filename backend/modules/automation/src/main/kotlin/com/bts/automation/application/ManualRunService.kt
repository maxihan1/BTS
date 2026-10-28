// 수동 실행(MANUAL) 트리거 유스케이스 — 실행 가능한 룰 목록 + 이슈 하나로 실행 큐 적재 (R9 · JAU-5 · FR-AT-01)

package com.bts.automation.application

import com.bts.automation.adapter.AutomationExecutionEnqueuer
import com.bts.automation.adapter.AutomationRuleRepository
import com.bts.automation.domain.AutomationRule
import com.bts.automation.domain.TriggerType
import com.bts.shared.issue.IssueSnapshotPort
import com.bts.shared.permission.IssuePermission
import com.bts.shared.permission.IssuePermissionResolver
import com.bts.shared.permission.IssueScope
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * 이슈 화면의 「자동화 ▸」 메뉴가 쓰는 수동 실행 유스케이스(spec `2026-10-28-r9-automation-catalog` §B).
 *
 * 룰 관리(`MANAGE_AUTOMATION`)가 아니라 **이슈를 보는 사람**이 부르는 경로다.
 * - [listRunnable] — 프로젝트 BROWSE 권한이 있으면 enabled MANUAL 룰을 돌려준다(이름만 노출).
 * - [run] — 요청자가 그 이슈를 볼 수 있으면(보안 수준 포함 — [IssueSnapshotPort] 가 요청자 가시성으로 조회)
 *   룰을 실행 큐에 넣는다. 액션은 룰 actor 권한으로 돈다(Jira 수동 트리거와 같음 — 룰을 MANUAL 로 연 관리자의 선택).
 *
 * ## triggerEvent 는 서버가 만든다
 * 요청 body 에서는 `issueKey` 만 받는다. 실행 큐 payload 는 `{type:"manual", issueKey, projectKey, actorId}` 로
 * 서버가 조립한다 — 외부 값이 액션 템플릿 · 프로젝트 경계 게이트(`ActionExecutor`)로 새지 않게.
 *
 * ## 존재 숨김 — 전부 404
 * 이슈가 다른 프로젝트 소속 · 안 보임 · 없음, 룰이 없음 · 다른 프로젝트 · MANUAL 아님 · 꺼짐은 모두
 * [ManualRunNotFoundException] 하나로 404 다. 무엇이 틀렸는지 구분해 주면 볼 수 없는 이슈/룰의 존재가 샌다.
 */
@Service
class ManualRunService(
    private val ruleRepository: AutomationRuleRepository,
    private val enqueuer: AutomationExecutionEnqueuer,
    private val issueSnapshotPort: IssueSnapshotPort,
    private val issuePermissionResolver: IssuePermissionResolver,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * [projectKey] 의 실행 가능한(enabled · 미삭제) MANUAL 룰 목록.
     *
     * @throws ManualRunForbiddenException 프로젝트 BROWSE 권한이 없을 때(403).
     */
    fun listRunnable(
        actorId: UUID,
        projectKey: String,
    ): List<AutomationRule> {
        if (!issuePermissionResolver.hasPermission(actorId, IssuePermission.BROWSE, IssueScope.Project(projectKey))) {
            throw ManualRunForbiddenException()
        }
        return ruleRepository.findEnabledByProjectAndTriggerType(projectKey, TriggerType.MANUAL)
    }

    /**
     * MANUAL 룰 [ruleId] 를 이슈 [issueKey] 로 실행 큐에 넣는다(비동기 — 결과는 실행 이력).
     *
     * @throws ManualRunNotFoundException 이슈 · 룰 조건이 하나라도 안 맞을 때(클래스 KDoc §존재 숨김).
     */
    fun run(
        actorId: UUID,
        projectKey: String,
        ruleId: UUID,
        issueKey: String,
    ) {
        // 형식 검사가 포트 조회보다 먼저다 — prod 어댑터는 틀린 형식에 IllegalArgumentException(500)을 던진다.
        val issueVisible =
            issueKeyPattern(projectKey).matches(issueKey) && issueSnapshotPort.fetch(actorId, issueKey) != null
        val rule = ruleRepository.findById(ruleId)?.takeIf { it.isManualRunnableIn(projectKey) }
        if (!issueVisible || rule == null) throw ManualRunNotFoundException()
        val triggerEvent =
            objectMapper.createObjectNode().apply {
                put("type", "manual")
                put("issueKey", issueKey)
                put("projectKey", projectKey)
                put("actorId", actorId.toString())
            }
        // 액션은 룰 actor 이름으로 남는다 — 누가 눌렀는지는 실행 이력(best-effort)과 이 로그에 남긴다.
        log.info("automation_manual_run_enqueued ruleId={} issueKey={} requester={}", rule.id, issueKey, actorId)
        enqueuer.enqueue(rule.id, TriggerType.MANUAL, triggerEvent)
    }

    /** `PROJ-123` 형식(번호는 1 이상) — issue-tracking `IssueKey` 규칙과 같은 모양만 통과. */
    private fun issueKeyPattern(projectKey: String): Regex = Regex("^${Regex.escape(projectKey)}-[1-9][0-9]{0,17}$")
}

/** 이 프로젝트의 켜진 MANUAL 룰인가. */
private fun AutomationRule.isManualRunnableIn(projectKey: String): Boolean =
    this.projectKey == projectKey && triggerType == TriggerType.MANUAL && enabled

/** 수동 실행 대상(이슈 · 룰)을 찾을 수 없거나 볼 수 없을 때 — 404. */
class ManualRunNotFoundException : RuntimeException("실행할 수 있는 자동화 규칙이나 이슈를 찾을 수 없습니다.")

/** 프로젝트를 볼 권한이 없을 때 — 403. */
class ManualRunForbiddenException : RuntimeException("이 프로젝트를 볼 권한이 없습니다.")
