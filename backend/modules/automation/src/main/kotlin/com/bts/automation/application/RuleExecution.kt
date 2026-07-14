// 자동화 룰 실행 이력 애그리거트 — 1회 실행의 트리거·결과 스냅샷 (FR-AT-05 Task 2)

package com.bts.automation.application

import com.bts.automation.domain.TriggerType
import com.fasterxml.jackson.databind.JsonNode
import java.time.Instant
import java.util.UUID

/**
 * 자동화 룰 실행 1건의 이력(audit trail, FR-AT-05).
 *
 * `rule_executions` 테이블(V305) 한 행에 대응하는 불변 값 객체다. [RuleExecutionRepository] 가 영속/조회를
 * 담당하고, 이 레코드는 실행 시각 스냅샷을 그대로 담는다 — 룰이 이후 변경·소프트 삭제돼도 이력은
 * 영향받지 않는다(감사 독립성 NFR-4, [RuleExecutionRepository] 클래스 KDoc "룰 테이블 조인 없음" 참조).
 *
 * ## BC 격리
 * [triggerEvent] 는 issue-tracking 등 다른 BC 의 도메인 타입이 아니라 [JsonNode] 로만 다룬다 —
 * automation 은 다른 BC 의 내부 타입을 직접 import 하지 않는다(DEVELOPMENT.md §1).
 *
 * @property id 실행 이력 PK.
 * @property ruleId 실행된 룰 id(`automation_rules.id` 를 논리적으로 참조 — 하드 FK 없음, NFR-4).
 * @property projectKey 룰 소속 프로젝트 키(비정규화 — 룰 조인 없이 스코프 목록/권한 판정).
 * @property triggerType fire-time 트리거 타입.
 * @property triggerEvent 실행 당시 원본 트리거 payload(replay 재료). issue-tracking 타입을 담지 않는다.
 * @property issueKey 대상 이슈 키. SCHEDULED/WEBHOOK 등 이슈 무관 실행은 `null`.
 * @property status 실행 결과 집계 상태([ActionExecutor] 재사용).
 * @property outcomes 액션별 실행 결과(position 순, [ActionExecutor] 재사용).
 * @property replayedFrom 이 실행이 replay 로 생성됐다면 원본 실행 id. 최초 실행이면 `null`.
 * @property startedAt 실행 시작 시각(UTC).
 * @property finishedAt 실행 종료 시각(UTC).
 */
data class RuleExecution(
    val id: UUID,
    val ruleId: UUID,
    val projectKey: String,
    val triggerType: TriggerType,
    val triggerEvent: JsonNode,
    val issueKey: String?,
    val status: ActionExecutionStatus,
    val outcomes: List<ActionOutcome>,
    val replayedFrom: UUID?,
    val startedAt: Instant,
    val finishedAt: Instant,
)
