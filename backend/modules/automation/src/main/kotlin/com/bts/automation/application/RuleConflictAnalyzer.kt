// 자동화 규칙 집합의 CYCLE·FIELD_CONFLICT·PRIORITY_AMBIGUITY·PERMISSION_MISSING 충돌을 정적 분석한다 (FR-AT-04 Task 2/3/4)

package com.bts.automation.application

import com.bts.automation.domain.Action
import com.bts.automation.domain.AutomationRule
import com.bts.automation.domain.ConflictType
import com.bts.automation.domain.RuleConflict
import com.bts.automation.domain.TriggerType
import com.bts.shared.permission.IssuePermission
import com.bts.shared.permission.IssuePermissionResolver
import com.bts.shared.permission.IssueScope
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * 자동화 규칙 집합을 정적 분석해 [RuleConflict] 목록을 산출한다.
 *
 * [ConflictType.CYCLE]([CycleDetector]), [ConflictType.FIELD_CONFLICT]/[ConflictType.PRIORITY_AMBIGUITY]
 * ([FieldPriorityAnalyzer]), [ConflictType.PERMISSION_MISSING]([PermissionAnalyzer])을 모두 담당한다.
 * 결과는 DB에 영속되지 않는 요청-응답 계산값이다(스펙 FR-1).
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
 *
 * ## PERMISSION_MISSING 판정 (스펙 FR-6) — cross-BC 신규 소비
 * [issuePermissionResolver]([IssuePermissionResolver], shared-kernel)에 각 규칙의 실행 주체
 * (`actorUserId`)가 액션이 요구하는 권한을 **프로젝트 레벨**(`IssueScope.Project`)에서 보유하는지
 * 근사 판정을 위임한다 — 저장 시점엔 구체 이슈가 없어 이슈별 보안등급까지는 검증하지 못한다(false
 * negative 는 실행 시점 fail-closed 방어에 위임, 프로젝트 레벨조차 없으면 확실히 실패하는 케이스만
 * 잡는다). non-null 생성자 주입으로 요구해([[crossbc-resolver-nullable-fail-open]] fail-open 회귀
 * 방지) automation 컨텍스트 부팅 시 이 포트 빈이 반드시 있어야 한다 — non-prod 는
 * `StubIssuePermissionResolver`(AlwaysAllow, `com.bts.automation` 테스트 패키지)가, prod 조립
 * (`:modules:app`)은 identity-access `@Profile("prod")` 어댑터가 제공한다. non-prod stub 특성상
 * PERMISSION_MISSING 은 prod 에서만 실질 검출된다(스펙 FR-6 Brainstorming #5).
 *
 * @property issuePermissionResolver 이슈 권한 판정 outbound 포트(shared-kernel 공용, PERMISSION_MISSING 전용)
 */
@Component
class RuleConflictAnalyzer(
    private val issuePermissionResolver: IssuePermissionResolver,
) {
    /**
     * [rules] 를 정적 분석해 검출된 [RuleConflict] 목록을 반환한다(CYCLE + FIELD_CONFLICT +
     * PRIORITY_AMBIGUITY + PERMISSION_MISSING, 스펙 FR-3/FR-4/FR-5/FR-6).
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
        val permissionConflicts = PermissionAnalyzer(enabledRules, issuePermissionResolver).detect()
        return (cycleConflicts + fieldPriorityConflicts + permissionConflicts).distinct()
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

    /** [target] 이 ISSUE_UPDATED 트리거이고 [field] 변경에 발화하면 `true`([matchesField] 공유 헬퍼 재사용). */
    private fun triggersIssueUpdated(
        target: AutomationRule,
        field: String,
    ): Boolean = target.triggerType == TriggerType.ISSUE_UPDATED && matchesField(target.triggerConfig, field)

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
     * 실제 겹침 판정은 [matchesField] 를 재사용한다(CYCLE 판정의
     * [RuleConflictAnalyzer.triggersIssueUpdated] 가 쓰는 것과 같은 공유 헬퍼) — "필터가 비었으면
     * 전체 발화, 아니면 교집합" 규칙을 두 곳에서 따로 구현하지 않는다.
     */
    private fun fieldsCoFire(
        configA: String,
        configB: String,
    ): Boolean {
        val candidates = configuredFields(configA) + configuredFields(configB)
        if (candidates.isEmpty()) return true
        return candidates.any { matchesField(configA, it) && matchesField(configB, it) }
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
                detail =
                    "규칙 '${a.name}'과(와) '${b.name}'가 동시에 발화할 때 " +
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
            detail =
                "규칙 '${a.name}'과(와) '${b.name}'가 같은 트리거에 동시에 매칭될 수 있어 " +
                    "실행 순서가 정해지지 않습니다.",
        )
    }

    /** [rule] 이 사용자가 관측 가능한 부수효과 액션(SET_FIELD/ASSIGN/ADD_COMMENT)을 하나라도 보유하면 `true`. */
    private fun hasObservableSideEffect(rule: AutomationRule): Boolean =
        rule.actions.any {
            it is Action.SetFieldAction || it is Action.AssignAction || it is Action.AddCommentAction
        }
}

/**
 * [rules] 중 실행 주체(`actorUserId`)가 액션이 요구하는 이슈 권한을 프로젝트 레벨에서 보유하지 못하는
 * 경우를 검출하는 헬퍼(FR-AT-04 Task 4, 스펙 FR-6). [rules] 는 이미 [AutomationRule.enabled] 로 걸러진
 * 목록이어야 한다(disabled 규칙은 발화하지 않으므로 제외).
 *
 * ## 권한 매핑 (스펙 FR-6 표)
 * [Action.SetFieldAction]/[Action.AssignAction] → [com.bts.shared.permission.IssuePermission.UPDATE].
 * [Action.AddCommentAction] 은 `IssuePermission` 에 댓글 전용 권한이 없어 분석 대상에서 제외하고,
 * [Action.CallWebhookAction] 은 이슈 권한과 무관한 외부 HTTP 호출이라 마찬가지로 제외한다
 * ([requiredPermission] exhaustive `when`).
 *
 * ## 메모이제이션 (스펙 Brainstorming #4)
 * `(actorId, projectKey, permission)` 키로 [cache] 에 저장해, 같은 조합을 여러 규칙·액션이 반복
 * 요구해도 [issuePermissionResolver] 는 고유 조합 수만큼만 호출된다 — N규칙×M액션이 아니라
 * 고유 (actor, projectKey, permission) 수에 비례(NFR 1s 안전 마진).
 */
private class PermissionAnalyzer(
    private val rules: List<AutomationRule>,
    private val issuePermissionResolver: IssuePermissionResolver,
) {
    private val cache = mutableMapOf<PermissionCacheKey, Boolean>()

    /** [rules] 를 분석해 검출된 [RuleConflict] 목록을 반환한다(PERMISSION_MISSING 전용). */
    fun detect(): List<RuleConflict> = rules.flatMap { rule -> missingPermissionConflicts(rule) }

    /** [rule] 의 액션이 요구하는 권한 중 [rule] 의 actor 가 보유하지 못한 권한마다 [RuleConflict] 1건. */
    private fun missingPermissionConflicts(rule: AutomationRule): List<RuleConflict> =
        actionsByRequiredPermission(rule).mapNotNull { (permission, actions) ->
            missingPermissionConflict(rule, permission, actions)
        }

    /** [rule] 의 액션을 [requiredPermission] 기준으로 그룹핑한다(제외 대상 액션은 결과에서 빠진다). */
    private fun actionsByRequiredPermission(rule: AutomationRule): Map<IssuePermission, List<Action>> =
        rule.actions
            .mapNotNull { action -> requiredPermission(action)?.let { it to action } }
            .groupBy({ it.first }, { it.second })

    /**
     * [rule] 의 actor 가 [permission] 을 보유하지 못하면 [RuleConflict] 를, 보유하면 `null` 을 반환한다.
     *
     * @param actions [permission] 을 요구한 [rule] 의 액션들(detail 메시지의 "해당 액션 종류"용, 스펙 FR-6)
     */
    private fun missingPermissionConflict(
        rule: AutomationRule,
        permission: IssuePermission,
        actions: List<Action>,
    ): RuleConflict? {
        if (hasPermission(rule.actorUserId, rule.projectKey, permission)) return null
        return buildPermissionMissingConflict(rule, permission, actions)
    }

    /**
     * [missingPermissionConflict] 이 검출한 위반 1건을 사람이 읽을 수 있는 한국어 [RuleConflict] 로
     * 조립한다. detail 에는 ruleId·actorId·부족 권한·해당 액션 종류를 모두 담는다(스펙 FR-6 "권한 미보유
     * 시 PERMISSION_MISSING 경고" 항목).
     */
    private fun buildPermissionMissingConflict(
        rule: AutomationRule,
        permission: IssuePermission,
        actions: List<Action>,
    ): RuleConflict {
        val actionKinds = actions.map(::actionKindLabel).distinct().joinToString(", ")
        return RuleConflict.of(
            type = ConflictType.PERMISSION_MISSING,
            ruleIds = listOf(rule.id),
            detail =
                "규칙 '${rule.name}'(id=${rule.id})의 실행 주체(actorId=${rule.actorUserId})가 " +
                    "프로젝트 '${rule.projectKey}'에서 $permission 권한이 없어 " +
                    "$actionKinds 액션이 실행 시점에 실패할 수 있습니다.",
        )
    }

    /**
     * [actorId] 가 [projectKey] 프로젝트에서 [permission] 을 보유하는지 [cache] 를 거쳐 판정한다.
     *
     * 같은 키가 이미 캐시에 있으면 [issuePermissionResolver] 를 다시 호출하지 않는다(메모이제이션).
     */
    private fun hasPermission(
        actorId: UUID,
        projectKey: String,
        permission: IssuePermission,
    ): Boolean =
        cache.getOrPut(PermissionCacheKey(actorId, projectKey, permission)) {
            issuePermissionResolver.hasPermission(actorId, permission, IssueScope.Project(projectKey))
        }

    /**
     * [action] 이 요구하는 [IssuePermission](스펙 FR-6 표). `AddCommentAction`/`CallWebhookAction` 은
     * 분석 대상이 아니므로 `null`(호출자가 [actionsByRequiredPermission] 에서 걸러낸다).
     */
    private fun requiredPermission(action: Action): IssuePermission? =
        when (action) {
            is Action.SetFieldAction -> IssuePermission.UPDATE
            is Action.AssignAction -> IssuePermission.UPDATE
            is Action.AddCommentAction -> null
            is Action.CallWebhookAction -> null
        }

    /**
     * [action] 의 한국어 표시 라벨(PERMISSION_MISSING detail 메시지 전용 —
     * [com.bts.automation.domain.ActionType] enum과 별개의 로컬 매핑).
     */
    private fun actionKindLabel(action: Action): String =
        when (action) {
            is Action.SetFieldAction -> "필드 설정"
            is Action.AssignAction -> "담당자 지정"
            is Action.AddCommentAction -> "댓글 추가"
            is Action.CallWebhookAction -> "웹훅 호출"
        }
}

/** [PermissionAnalyzer] 권한 조회 메모이제이션 키. actor·프로젝트·권한 종류가 모두 같아야 캐시 hit. */
private data class PermissionCacheKey(
    val actorId: UUID,
    val projectKey: String,
    val permission: IssuePermission,
)

private const val TRIGGER_CONFIG_FIELDS_KEY = "fields"

private val triggerConfigObjectMapper = ObjectMapper()

/**
 * `triggerConfig` 의 ISSUE_UPDATED 필드 필터가 `field` 변경에 발화하면 `true`.
 *
 * [TriggerMatcher.matchesFieldFilter] 를 감싸는 파일 전역 공유 헬퍼다 — "필터가 비었으면 전체 발화,
 * 아니면 교집합"이라는 단일 매칭 의미론을 [RuleConflictAnalyzer.triggersIssueUpdated](CYCLE 판정)와
 * [FieldPriorityAnalyzer.fieldsCoFire](FIELD_CONFLICT/PRIORITY_AMBIGUITY 동시 매칭 판정) 양쪽에서
 * 재사용해 같은 규칙을 두 번 구현하지 않는다.
 */
private fun matchesField(
    triggerConfig: String,
    field: String,
): Boolean = TriggerMatcher.matchesFieldFilter(triggerConfig, setOf(field))

/**
 * `triggerConfig` JSON(`{"fields":[...]}`) 의 `fields` 배열을 문자열 집합으로 파싱한다.
 *
 * [FieldPriorityAnalyzer.fieldsCoFire] 가 동시 매칭 후보 필드를 뽑는 용도로만 쓴다 — 실제 매칭
 * 의미론은 이 함수가 아니라 [matchesField] 가 갖는다(단일 진실 공급원 유지, 이 함수는 후보 열거용
 * 파싱만 담당). [com.bts.automation.domain.TriggerConfig]/[TriggerMatcher] 의 동명 private 파싱
 * 로직과 형식이 겹치지만, 두 파일 모두 이 모듈의 비공개 구현이라 직접 재사용할 공개 API가 없다(모듈 내
 * 최소 중복 허용, 파일별 자기완결 파싱 관례는 기존 domain 파일들도 동형).
 */
private fun configuredFields(triggerConfig: String): Set<String> {
    val fieldsNode = triggerConfigObjectMapper.readTree(triggerConfig).path(TRIGGER_CONFIG_FIELDS_KEY)
    if (!fieldsNode.isArray) return emptySet()
    return fieldsNode.mapNotNull { it.asText(null)?.takeIf(String::isNotBlank) }.toSet()
}
