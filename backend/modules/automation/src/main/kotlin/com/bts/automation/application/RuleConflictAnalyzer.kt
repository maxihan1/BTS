// 자동화 규칙 집합의 CYCLE(액션→트리거 유발 방향 그래프 DFS) 충돌을 정적 분석한다 (FR-AT-04 Task 2)

package com.bts.automation.application

import com.bts.automation.domain.Action
import com.bts.automation.domain.AutomationRule
import com.bts.automation.domain.ConflictType
import com.bts.automation.domain.RuleConflict
import com.bts.automation.domain.TriggerType
import java.util.UUID

/**
 * 자동화 규칙 집합을 정적 분석해 [RuleConflict] 목록을 산출한다.
 *
 * 현재는 [ConflictType.CYCLE] 검출만 담당한다 — FIELD_CONFLICT/PRIORITY_AMBIGUITY/PERMISSION_MISSING 은
 * 후속 Task 3/4에서 이 클래스의 `analyze()` 에 파트가 추가된다. 결과는 DB에 영속되지 않는
 * 요청-응답 계산값이다(스펙 FR-1).
 *
 * ## CYCLE 판정 (스펙 FR-3)
 * 방향 그래프를 구성한다 — 노드는 [AutomationRule.enabled] 가 `true` 인 규칙만(disabled 규칙은
 * 발화하지 않으므로 그래프에서 제외). 엣지 `A → B` 는 A 의 어떤 액션이 B 의 트리거를 유발할 수 있다는
 * 뜻이며, 이 유발 가능성은 [actionTriggers] 가 판정한다(액션→트리거 매핑은 automation 내부 판정,
 * cross-BC import 없음). self-loop(A → A)도 유효한 사이클로 취급한다.
 *
 * 조건([com.bts.automation.domain.Condition])은 무시한다 — B 의 조건이 실제로 참이 될지는 정적으로
 * 판정하지 않는다(조건 겹침 판정은 SAT 급 난제). 트리거 유발 가능성만으로 엣지를 그어 일부 false
 * positive 는 허용하되 실제 사이클을 놓치지 않는다(보수적 근사).
 */
class RuleConflictAnalyzer {
    /**
     * [rules] 를 정적 분석해 검출된 [RuleConflict] 목록을 반환한다(현재는 CYCLE 만).
     *
     * @param rules 분석 대상 규칙 집합(보통 한 프로젝트의 삭제되지 않은 전체 규칙, 스펙 FR-2)
     * @return 검출된 [RuleConflict] 목록. 같은 사이클이 여러 경로로 중복 검출돼도 1건으로 dedup 된다.
     *   순서는 보장하지 않는다
     */
    fun analyze(rules: List<AutomationRule>): List<RuleConflict> {
        val enabledRules = rules.filter { it.enabled }
        val graph = buildTriggerGraph(enabledRules)
        val ruleNames = enabledRules.associate { it.id to it.name }
        return CycleDetector(graph, ruleNames).detect().distinct()
    }

    /** [rules] 로 인접 목록(`ruleId -> 유발 대상 ruleId 목록`)을 구성한다. 다중 엣지를 허용한다(dedup은 호출자 책임). */
    private fun buildTriggerGraph(rules: List<AutomationRule>): Map<UUID, List<UUID>> =
        rules.associate { source -> source.id to edgesFrom(source, rules) }

    /** [source] 의 각 액션이 유발하는 [rules] 중 대상 규칙 id 목록(액션 1개가 여러 규칙을 유발할 수 있음). */
    private fun edgesFrom(
        source: AutomationRule,
        rules: List<AutomationRule>,
    ): List<UUID> =
        source.actions.flatMap { action ->
            rules.filter { target -> actionTriggers(action, target) }.map { it.id }
        }

    /**
     * [action] 이 [target] 의 트리거를 유발할 수 있으면 `true`(스펙 FR-3 매핑 표).
     *
     * sealed [Action] 의 4개 하위 타입에 대해 exhaustive `when` 으로 분기한다 — `ActionType` enum 기반
     * 매핑을 별도로 만들지 않는다(기존 repo/executor/response 3곳 매핑과의 중복 회피, plan DRY 노트).
     */
    private fun actionTriggers(
        action: Action,
        target: AutomationRule,
    ): Boolean =
        when (action) {
            is Action.SetFieldAction -> triggersIssueUpdated(target, action.field)
            is Action.AssignAction -> triggersIssueUpdated(target, ASSIGNEE_FIELD)
            is Action.AddCommentAction -> target.triggerType == TriggerType.ISSUE_COMMENTED
            is Action.CallWebhookAction -> false
        }

    /** [target] 이 ISSUE_UPDATED 트리거이고 [field] 변경에 발화하면 `true`([TriggerMatcher.matchesFieldFilter] 재사용). */
    private fun triggersIssueUpdated(
        target: AutomationRule,
        field: String,
    ): Boolean =
        target.triggerType == TriggerType.ISSUE_UPDATED &&
            TriggerMatcher.matchesFieldFilter(target.triggerConfig, setOf(field))

    companion object {
        /**
         * [Action.AssignAction] 이 유발하는 `issue.updated` 이벤트의 필드명 근사값.
         *
         * 실제 issue-tracking 이벤트의 `updatedFields` 원소 이름은 이 BC 소관이 아니라 automation
         * 내부 상수로 근사한다(cross-BC import 없이 정적 분석). 실제 정합 확인은 통합 테스트
         * (FR-AT-04 Task 6) 범위다.
         */
        private const val ASSIGNEE_FIELD = "assignee"
    }
}

/**
 * [graph] 에서 back-edge 기반 DFS 로 사이클을 찾는 헬퍼(3색 방문 상태 WHITE/GRAY/BLACK).
 *
 * 한 사이클이 다중 엣지(같은 소스 규칙의 여러 액션이 같은 대상을 유발)로 인해 여러 번 검출될 수
 * 있다 — 이 경우도 같은 [RuleConflict] 값을 산출하므로, 상위 [RuleConflictAnalyzer.analyze] 가
 * `distinct()` 로 dedup 한다.
 */
private class CycleDetector(
    private val graph: Map<UUID, List<UUID>>,
    private val ruleNames: Map<UUID, String>,
) {
    private enum class Color { WHITE, GRAY, BLACK }

    private val color = graph.keys.associateWithTo(mutableMapOf()) { Color.WHITE }
    private val stack = mutableListOf<UUID>()
    private val found = mutableListOf<RuleConflict>()

    /** [graph] 의 모든 미방문 노드에서 DFS 를 시작해 발견한 [RuleConflict] 목록(dedup 전, 원시)을 반환한다. */
    fun detect(): List<RuleConflict> {
        graph.keys.filter { color[it] == Color.WHITE }.forEach { visit(it) }
        return found
    }

    private fun visit(nodeId: UUID) {
        color[nodeId] = Color.GRAY
        stack.add(nodeId)
        graph[nodeId].orEmpty().forEach { neighbor -> visitNeighbor(neighbor) }
        stack.removeAt(stack.lastIndex)
        color[nodeId] = Color.BLACK
    }

    private fun visitNeighbor(neighbor: UUID) {
        when (color[neighbor]) {
            Color.WHITE -> visit(neighbor)
            Color.GRAY -> found += cycleConflict(neighbor)
            Color.BLACK, null -> Unit
        }
    }

    /** [backTarget] 으로의 back-edge 를 [stack] 에서 잘라내 사이클 경로를 만들고 [RuleConflict] 로 변환한다. */
    private fun cycleConflict(backTarget: UUID): RuleConflict {
        val startIndex = stack.indexOf(backTarget)
        val cycle = rotateToMinimum(stack.subList(startIndex, stack.size))
        return RuleConflict.of(type = ConflictType.CYCLE, ruleIds = cycle, detail = cycleDetail(cycle))
    }

    /** [cycle] 을 가장 작은 규칙 id 로 시작하도록 회전시켜 정규화한다(DFS 진입점과 무관한 일관된 순서). */
    private fun rotateToMinimum(cycle: List<UUID>): List<UUID> {
        val minIndex = cycle.indices.minByOrNull { cycle[it] } ?: 0
        return cycle.drop(minIndex) + cycle.take(minIndex)
    }

    /** [cycle] 경로를 사람이 읽을 수 있는 한국어 사이클 설명으로 변환한다(규칙 이름 사용, id 폴백). */
    private fun cycleDetail(cycle: List<UUID>): String {
        val labels = cycle.map { ruleNames[it] ?: it.toString() }
        val path = (labels + labels.first()).joinToString(" → ")
        return "규칙 $path 가 서로의 트리거를 유발해 무한 루프가 될 수 있습니다."
    }
}
