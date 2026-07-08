// action×key_combo 바인딩 목록의 충돌·형식 검증 6종 — FR-PF-03 Task 2 (순수 도메인, DB/Spring 무관)

package com.atlas.bts.identity.keymap

/**
 * 단일 key_combo 검증/충돌 위반 하나.
 *
 * [category] 로 위반 유형을 구분한다 — 호출자(`UserKeymapService`, Task 4)가 이 값으로
 * `KeymapValidationException`(VALIDATION)/`KeymapConflictException`(CONFLICT) 중 어느 것을
 * 던질지 분기한다.
 */
sealed class KeymapViolation {
    /** 위반이 속하는 카테고리. */
    enum class Category { VALIDATION, CONFLICT }

    /** 이 위반이 속하는 카테고리. */
    abstract val category: Category

    /** action 화이트리스트 위반 — 5종 미완비([missing]) 또는 화이트리스트 밖 action([unknown]) 존재. */
    data class Whitelist(val missing: Set<String>, val unknown: Set<String>) : KeymapViolation() {
        override val category: Category = Category.VALIDATION
    }

    /** key_combo 형식 위반 — single(1글자) 도 `"g <key>"` leader 도 아님. */
    data class Format(val action: String, val keyCombo: String) : KeymapViolation() {
        override val category: Category = Category.VALIDATION
    }

    /** key_combo 가 빈 값(공백 포함). */
    data class Blank(val action: String) : KeymapViolation() {
        override val category: Category = Category.VALIDATION
    }

    /** 완전 중복 — 둘 이상의 [actions] 이 같은 [keyCombo] 를 가짐. */
    data class Duplicate(val keyCombo: String, val actions: Set<String>) : KeymapViolation() {
        override val category: Category = Category.CONFLICT
    }

    /** leader 접두 충돌 — single `g`([singleAction]) 와 leader(`g X`, [leaderActions]) 가 공존. */
    data class LeaderPrefix(val singleAction: String, val leaderActions: Set<String>) : KeymapViolation() {
        override val category: Category = Category.CONFLICT
    }

    /** dead leader combo — continuation 키가 leader 키([LEADER_KEY])와 같은 `g g`. */
    data class DeadLeader(val action: String) : KeymapViolation() {
        override val category: Category = Category.CONFLICT
    }
}

/**
 * action×key_combo 바인딩 목록의 충돌·형식을 검증하는 순수 도메인 로직 (FR-PF-03 FR3).
 *
 * DB/Spring 무관 — `UserKeymapService`(Task 4) 가 PATCH 요청 바인딩을 저장하기 전에 호출하고,
 * 반환된 위반 목록이 비어 있지 않으면 [KeymapViolation.category] 로 예외 타입을 분기해 던진다.
 */
object KeymapValidator {
    /**
     * [bindings] 를 6종 규칙으로 검증한다.
     *
     * 검증 순서 — (1) 화이트리스트 완비 (2) 빈값 (3) 형식 (4) 완전중복 (5) leader 접두 충돌
     * (6) dead leader combo. (4)~(6) 은 (2)~(3) 을 통과한(형식이 유효하고 비어있지 않은) 바인딩
     * 사이에서만 판정한다 — 형식이 깨진 바인딩끼리 비교하는 것은 무의미하기 때문이다.
     *
     * @param bindings 검증 대상 action×key_combo 목록
     * @return 위반 목록(비어 있으면 통과)
     */
    fun validate(bindings: List<KeymapBinding>): List<KeymapViolation> {
        val structurallyValid = bindings.filter { it.keyCombo.isNotBlank() && it.hasValidFormat() }

        return checkWhitelist(bindings) +
            checkBlank(bindings) +
            checkFormat(bindings) +
            checkDuplicates(structurallyValid) +
            checkLeaderPrefix(structurallyValid) +
            checkDeadLeader(structurallyValid)
    }

    /** 화이트리스트 완비 검사(FR3-1) — action 5종 누락·불명 action 존재 여부. */
    private fun checkWhitelist(bindings: List<KeymapBinding>): List<KeymapViolation> {
        val actual = bindings.map { it.action }.toSet()
        val missing = KeymapAction.WHITELIST_IDS - actual
        val unknown = actual - KeymapAction.WHITELIST_IDS
        return if (missing.isNotEmpty() || unknown.isNotEmpty()) {
            listOf(KeymapViolation.Whitelist(missing, unknown))
        } else {
            emptyList()
        }
    }

    /** 빈값 금지 검사(FR3-3). */
    private fun checkBlank(bindings: List<KeymapBinding>): List<KeymapViolation> =
        bindings.filter { it.keyCombo.isBlank() }.map { KeymapViolation.Blank(it.action) }

    /** key_combo 형식 검사(FR3-2) — 빈 값은 [checkBlank] 가 담당하므로 제외. */
    private fun checkFormat(bindings: List<KeymapBinding>): List<KeymapViolation> =
        bindings
            .filter { it.keyCombo.isNotBlank() && !it.hasValidFormat() }
            .map { KeymapViolation.Format(it.action, it.keyCombo) }

    /** 완전 중복 검사(FR3-4) — 같은 key_combo 를 가진 action 이 둘 이상. */
    private fun checkDuplicates(bindings: List<KeymapBinding>): List<KeymapViolation> =
        bindings
            .groupBy { it.keyCombo }
            .filterValues { it.size > 1 }
            .map { (combo, group) -> KeymapViolation.Duplicate(combo, group.map { it.action }.toSet()) }

    /** leader 접두 충돌 검사(FR3-5) — single `g` 와 leader(`g X`) 공존. */
    private fun checkLeaderPrefix(bindings: List<KeymapBinding>): List<KeymapViolation> {
        val singleLeaderKey = bindings.find { it.trigger == KeymapTrigger.SINGLE && it.keyCombo == LEADER_KEY }
        val leaderBindings = bindings.filter { it.trigger == KeymapTrigger.LEADER }
        return if (singleLeaderKey != null && leaderBindings.isNotEmpty()) {
            listOf(KeymapViolation.LeaderPrefix(singleLeaderKey.action, leaderBindings.map { it.action }.toSet()))
        } else {
            emptyList()
        }
    }

    /** dead leader combo 검사(FR3-6) — continuation 키가 leader 키와 같은 `g g`. */
    private fun checkDeadLeader(bindings: List<KeymapBinding>): List<KeymapViolation> =
        bindings
            .filter { it.trigger == KeymapTrigger.LEADER && it.leaderContinuationKey() == LEADER_KEY }
            .map { KeymapViolation.DeadLeader(it.action) }
}
