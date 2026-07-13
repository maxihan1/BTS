// 자동화 규칙 집합의 CYCLE·FIELD_CONFLICT·PRIORITY_AMBIGUITY 충돌을 정적 분석한다 (FR-AT-04 Task 2/3)

package com.bts.automation.application

import com.bts.automation.domain.Action
import com.bts.automation.domain.AutomationRule
import com.bts.automation.domain.ConflictType
import com.bts.automation.domain.RuleConflict
import com.bts.automation.domain.TriggerType
import com.fasterxml.jackson.databind.ObjectMapper
import java.util.UUID

/**
 * 자동화 규칙 집합을 정적 분석해 [RuleConflict] 목록을 산출한다.
 *
 * [ConflictType.CYCLE]([CycleDetector])과 [ConflictType.FIELD_CONFLICT]/[ConflictType.PRIORITY_AMBIGUITY]
 * ([FieldPriorityAnalyzer])를 담당한다 — [ConflictType.PERMISSION_MISSING] 은 후속 Task 4에서 이 클래스의
 * `analyze()` 에 파트가 추가된다. 결과는 DB에 영속되지 않는 요청-응답 계산값이다(스펙 FR-1).
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
     * [rules] 를 정적 분석해 검출된 [RuleConflict] 목록을 반환한다(CYCLE + FIELD_CONFLICT +
     * PRIORITY_AMBIGUITY, 스펙 FR-3/FR-4/FR-5).
     *
     * @param rules 분석 대상 규칙 집합(보통 한 프로젝트의 삭제되지 않은 전체 규칙, 스펙 FR-2)
     * @return 검출된 [RuleConflict] 목록. 같은 충돌이 여러 경로로 중복 검출돼도 1건으로 dedup 된다.
     *   순서는 보장하지 않는다
     */
    fun analyze(rules: List<AutomationRule>): List<RuleConflict> {
        val enabledRules = rules.filter { it.enabled }
        val graph = buildTriggerGraph(enabledRules)
        val ruleNames = enabledRules.associate { it.id to it.name }
        val cycleConflicts = CycleDetector(graph, ruleNames).detect()
        val fieldPriorityConflicts = FieldPriorityAnalyzer(enabledRules).detect()
        return (cycleConflicts + fieldPriorityConflicts).distinct()
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

/**
 * [rules] 중 "동시 매칭 가능한" 규칙 쌍을 대상으로 [ConflictType.FIELD_CONFLICT] 와
 * [ConflictType.PRIORITY_AMBIGUITY] 를 검출하는 헬퍼(FR-AT-04 Task 3, 스펙 FR-4/FR-5). [rules] 는
 * 이미 [AutomationRule.enabled] 로 걸러진 목록이어야 한다(disabled 규칙은 발화하지 않으므로 제외).
 *
 * ## 동시 매칭 판정 ([coFire])
 * 두 규칙이 같은 이벤트로 동시에 발화할 수 있으면 동시 매칭이다 — `triggerType` 이 같고, ISSUE_UPDATED
 * 라면 두 규칙의 `triggerConfig` `fields` 필터가 겹치거나 한쪽이 비어있어야 한다(비어있으면 모든
 * update 에 발화, 스펙 FR4). 그 외 트리거 타입은 타입 일치만으로 동시 매칭이다.
 *
 * ## FIELD_CONFLICT (스펙 FR-4)
 * 동시 매칭 쌍 사이에 같은 field 를 다른 value 로 SET 하는 [Action.SetFieldAction] 조합이 있으면
 * 검출한다([conflictingFieldNames]). 같은 규칙 내부의 액션 목록에도 같은 판정을 적용한다(트리거
 * 동시 매칭을 자기 자신과 비교할 필요가 없으므로 [coFire] 를 거치지 않는다) — 이때 `ruleIds` 는 자기
 * 자신 하나뿐이다.
 *
 * ## PRIORITY_AMBIGUITY (스펙 FR-5)
 * 동시 매칭 쌍이 모두 관측 가능한 부수효과([hasObservableSideEffect]) 액션을 보유하면 검출한다. 단,
 * 같은 쌍이 이미 FIELD_CONFLICT 로 검출됐다면 억제한다 — FIELD_CONFLICT 는 확정된 특수 케이스이므로
 * 더 막연한 PRIORITY_AMBIGUITY 를 같은 쌍에 중복 리포트하지 않는다(스펙 Brainstorming #3).
 */
private class FieldPriorityAnalyzer(private val rules: List<AutomationRule>) {
    /** [rules] 를 분석해 검출된 [RuleConflict] 목록을 반환한다(FIELD_CONFLICT + PRIORITY_AMBIGUITY). */
    fun detect(): List<RuleConflict> {
        val internal = rules.flatMap { internalFieldConflicts(it) }
        val pairs = coFiringPairs()
        val cross = pairs.flatMap { (a, b) -> crossFieldConflicts(a, b) }
        val conflictedPairs = cross.map { it.ruleIds }.toSet()
        val priority = pairs.mapNotNull { (a, b) -> priorityAmbiguity(a, b, conflictedPairs) }
        return internal + cross + priority
    }

    /** [rules] 중 [coFire] 로 동시 매칭 판정되는 순서 없는 쌍 전부(자기 자신 제외, 중복 없음). */
    private fun coFiringPairs(): List<Pair<AutomationRule, AutomationRule>> =
        rules.indices.flatMap { i ->
            (i + 1 until rules.size).mapNotNull { j ->
                (rules[i] to rules[j]).takeIf { (a, b) -> coFire(a, b) }
            }
        }

    /** [a]·[b] 가 같은 이벤트로 동시에 발화할 수 있으면 `true`(트리거 타입 일치 + ISSUE_UPDATED 필드 겹침). */
    private fun coFire(
        a: AutomationRule,
        b: AutomationRule,
    ): Boolean =
        when {
            a.triggerType != b.triggerType -> false
            a.triggerType != TriggerType.ISSUE_UPDATED -> true
            else -> fieldsCoFire(a.triggerConfig, b.triggerConfig)
        }

    /**
     * 두 ISSUE_UPDATED `triggerConfig` 의 `fields` 필터가 겹치거나 한쪽이 비어있으면 `true`.
     *
     * 실제 겹침 판정은 [TriggerMatcher.matchesFieldFilter] 를 재사용한다(CYCLE 판정의
     * [RuleConflictAnalyzer.triggersIssueUpdated] 가 쓰는 것과 같은 헬퍼) — "필터가 비었으면 전체
     * 발화, 아니면 교집합" 규칙을 두 곳에서 따로 구현하지 않는다.
     */
    private fun fieldsCoFire(
        configA: String,
        configB: String,
    ): Boolean {
        val candidates = configuredFields(configA) + configuredFields(configB)
        if (candidates.isEmpty()) return true
        return candidates.any {
            TriggerMatcher.matchesFieldFilter(configA, setOf(it)) &&
                TriggerMatcher.matchesFieldFilter(configB, setOf(it))
        }
    }

    /** [a]·[b] 사이의 [conflictingFieldNames] 를 각각 [ConflictType.FIELD_CONFLICT] 로 변환한다(ruleIds=[a,b]). */
    private fun crossFieldConflicts(
        a: AutomationRule,
        b: AutomationRule,
    ): List<RuleConflict> =
        conflictingFieldNames(a.actions, b.actions).map { field ->
            RuleConflict.of(
                type = ConflictType.FIELD_CONFLICT,
                ruleIds = listOf(a.id, b.id),
                detail = "규칙 '${a.name}'과(와) '${b.name}'가 동시에 발화할 때 " +
                    "필드 '$field'에 서로 다른 값을 설정해 충돌합니다.",
            )
        }

    /** [rule] 내부 액션 목록에서 [conflictingFieldNames] 를 찾아 [ConflictType.FIELD_CONFLICT] 로 변환한다(ruleIds=[self]). */
    private fun internalFieldConflicts(rule: AutomationRule): List<RuleConflict> =
        conflictingFieldNames(rule.actions, rule.actions).map { field ->
            RuleConflict.of(
                type = ConflictType.FIELD_CONFLICT,
                ruleIds = listOf(rule.id),
                detail = "규칙 '${rule.name}' 내부의 액션들이 필드 '$field'에 서로 다른 값을 설정해 충돌합니다.",
            )
        }

    /** [actionsA]×[actionsB] 의 [Action.SetFieldAction] 조합 중 같은 field·다른 value 인 필드명 집합(중복 제거). */
    private fun conflictingFieldNames(
        actionsA: List<Action>,
        actionsB: List<Action>,
    ): Set<String> =
        actionsA.filterIsInstance<Action.SetFieldAction>().flatMap { fa ->
            actionsB
                .filterIsInstance<Action.SetFieldAction>()
                .filter { fb -> fb.field == fa.field && fb.value != fa.value }
                .map { fa.field }
        }.toSet()

    /**
     * [a]·[b] 가 모두 관측 가능한 부수효과를 가지고 [conflictedPairs] 에 없으면 [ConflictType.PRIORITY_AMBIGUITY]
     * 를 반환한다. 그 외에는 `null`(부수효과 없음 또는 같은 쌍이 이미 FIELD_CONFLICT 로 억제됨).
     */
    private fun priorityAmbiguity(
        a: AutomationRule,
        b: AutomationRule,
        conflictedPairs: Set<List<UUID>>,
    ): RuleConflict? {
        val pairIds = listOf(a.id, b.id).sorted()
        val eligible = hasObservableSideEffect(a) && hasObservableSideEffect(b) && pairIds !in conflictedPairs
        if (!eligible) return null
        return RuleConflict.of(
            type = ConflictType.PRIORITY_AMBIGUITY,
            ruleIds = pairIds,
            detail = "규칙 '${a.name}'과(와) '${b.name}'가 같은 트리거에 동시에 매칭될 수 있어 " +
                "실행 순서가 정해지지 않습니다.",
        )
    }

    /** [rule] 이 사용자가 관측 가능한 부수효과 액션(SET_FIELD/ASSIGN/ADD_COMMENT)을 하나라도 보유하면 `true`. */
    private fun hasObservableSideEffect(rule: AutomationRule): Boolean =
        rule.actions.any {
            it is Action.SetFieldAction || it is Action.AssignAction || it is Action.AddCommentAction
        }
}

private const val TRIGGER_CONFIG_FIELDS_KEY = "fields"

private val triggerConfigObjectMapper = ObjectMapper()

/**
 * `triggerConfig` JSON(`{"fields":[...]}`) 의 `fields` 배열을 문자열 집합으로 파싱한다.
 *
 * [FieldPriorityAnalyzer.fieldsCoFire] 가 동시 매칭 후보 필드를 뽑는 용도로만 쓴다 — 실제 "필터가
 * 비었으면 전체 발화, 아니면 교집합"이라는 매칭 의미론은 이 함수가 아니라
 * [TriggerMatcher.matchesFieldFilter] 가 갖는다(단일 진실 공급원 유지, 이 함수는 후보 열거용 파싱만
 * 담당). [com.bts.automation.domain.TriggerConfig]/[TriggerMatcher] 의 동명 private 파싱 로직과
 * 형식이 겹치지만, 두 파일 모두 이 모듈의 비공개 구현이라 직접 재사용할 공개 API가 없다(모듈 내 최소
 * 중복 허용, 파일별 자기완결 파싱 관례는 기존 domain 파일들도 동형).
 */
private fun configuredFields(triggerConfig: String): Set<String> {
    val fieldsNode = triggerConfigObjectMapper.readTree(triggerConfig).path(TRIGGER_CONFIG_FIELDS_KEY)
    if (!fieldsNode.isArray) return emptySet()
    return fieldsNode.mapNotNull { it.asText(null)?.takeIf(String::isNotBlank) }.toSet()
}
