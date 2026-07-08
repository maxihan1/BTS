// KeymapValidator 단위 테스트 — 충돌 검출 6종 + 정상 케이스 (FR-PF-03 Task 2)

package com.atlas.bts.identity.keymap

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * [KeymapValidator] 단위 테스트 (FR-PF-03 Task 2).
 *
 * 순수 도메인 로직 — DB/Spring 무관, mock 불요.
 *
 * ## 테스트 시나리오
 * - 정상: 기본 키맵, single 재배치, single↔leader 변환 → 위반 없음.
 * - 위반 6종: 화이트리스트(누락/불명), 형식(2글자 single/`g` 없는 leader/3토큰), 빈값,
 *   완전중복, leader 접두 충돌, dead leader combo(`g g`) — 각각 독립 시나리오로 격리 검증.
 */
class KeymapValidatorTest {
    // ── 정상 케이스 ──────────────────────────────────────────────────────────

    @Test
    fun `validate — 기본 키맵은 위반 없음`() {
        val violations = KeymapValidator.validate(KeymapAction.DEFAULT_BINDINGS)

        assertThat(violations).isEmpty()
    }

    @Test
    fun `validate — single 재배치(create-issue c 에서 n)는 위반 없음`() {
        val bindings = defaultBindingsWith(KeymapAction.CREATE_ISSUE to "n")

        assertThat(KeymapValidator.validate(bindings)).isEmpty()
    }

    @Test
    fun `validate — single에서 leader로 변환(create-issue c 에서 g c)은 위반 없음`() {
        val bindings = defaultBindingsWith(KeymapAction.CREATE_ISSUE to "g c")

        assertThat(KeymapValidator.validate(bindings)).isEmpty()
    }

    @Test
    fun `validate — leader에서 single로 변환(goto-my-issues g i 에서 x)은 위반 없음`() {
        val bindings = defaultBindingsWith(KeymapAction.GOTO_MY_ISSUES to "x")

        assertThat(KeymapValidator.validate(bindings)).isEmpty()
    }

    // ── 1. 화이트리스트 ──────────────────────────────────────────────────────

    @Test
    fun `validate — action 누락(5종 미완비)은 Whitelist 위반`() {
        val bindings =
            KeymapAction.entries
                .filterNot { it == KeymapAction.GOTO_DASHBOARD }
                .map { KeymapBinding(it.id, it.defaultKeyCombo) }

        val violations = KeymapValidator.validate(bindings)

        val whitelist = violations.single() as KeymapViolation.Whitelist
        assertThat(whitelist.category).isEqualTo(KeymapViolation.Category.VALIDATION)
        assertThat(whitelist.missing).containsExactly("goto-dashboard")
        assertThat(whitelist.unknown).isEmpty()
    }

    @Test
    fun `validate — 불명 action 존재는 Whitelist 위반(missing+unknown 함께 보고)`() {
        val bindings =
            KeymapAction.entries.map { action ->
                val id = if (action == KeymapAction.GOTO_DASHBOARD) "bogus-action" else action.id
                KeymapBinding(id, action.defaultKeyCombo)
            }

        val violations = KeymapValidator.validate(bindings)

        val whitelist = violations.single() as KeymapViolation.Whitelist
        assertThat(whitelist.missing).containsExactly("goto-dashboard")
        assertThat(whitelist.unknown).containsExactly("bogus-action")
    }

    // ── 2. 형식 ──────────────────────────────────────────────────────────────

    @Test
    fun `validate — 2글자 single(형식 위반)은 Format 위반`() {
        val bindings = defaultBindingsWith(KeymapAction.HELP to "ab")

        val violations = KeymapValidator.validate(bindings)

        val format = violations.single() as KeymapViolation.Format
        assertThat(format.category).isEqualTo(KeymapViolation.Category.VALIDATION)
        assertThat(format.action).isEqualTo("help")
        assertThat(format.keyCombo).isEqualTo("ab")
    }

    @Test
    fun `validate — g 없는 leader(형식 위반)는 Format 위반`() {
        val bindings = defaultBindingsWith(KeymapAction.GOTO_MY_ISSUES to "x i")

        val violations = KeymapValidator.validate(bindings)

        val format = violations.single() as KeymapViolation.Format
        assertThat(format.action).isEqualTo("goto-my-issues")
        assertThat(format.keyCombo).isEqualTo("x i")
    }

    @Test
    fun `validate — 3토큰(형식 위반)은 Format 위반`() {
        val bindings = defaultBindingsWith(KeymapAction.GOTO_MY_ISSUES to "g a b")

        val violations = KeymapValidator.validate(bindings)

        val format = violations.single() as KeymapViolation.Format
        assertThat(format.keyCombo).isEqualTo("g a b")
    }

    // ── 3. 빈값 ──────────────────────────────────────────────────────────────

    @Test
    fun `validate — 빈 key_combo는 Blank 위반(Format 위반과 중복 보고 안 함)`() {
        val bindings = defaultBindingsWith(KeymapAction.SEARCH to "")

        val violations = KeymapValidator.validate(bindings)

        val blank = violations.single() as KeymapViolation.Blank
        assertThat(blank.category).isEqualTo(KeymapViolation.Category.VALIDATION)
        assertThat(blank.action).isEqualTo("search")
    }

    // ── 4. 완전중복 ──────────────────────────────────────────────────────────

    @Test
    fun `validate — 두 action이 같은 key_combo는 Duplicate 위반`() {
        val bindings = defaultBindingsWith(KeymapAction.SEARCH to "c")

        val violations = KeymapValidator.validate(bindings)

        val duplicate = violations.single() as KeymapViolation.Duplicate
        assertThat(duplicate.category).isEqualTo(KeymapViolation.Category.CONFLICT)
        assertThat(duplicate.keyCombo).isEqualTo("c")
        assertThat(duplicate.actions).containsExactlyInAnyOrder("create-issue", "search")
    }

    // ── 5. leader 접두 충돌 ──────────────────────────────────────────────────

    @Test
    fun `validate — single g 와 leader(g X)가 공존하면 LeaderPrefix 위반`() {
        val bindings = defaultBindingsWith(KeymapAction.HELP to "g")

        val violations = KeymapValidator.validate(bindings)

        val leaderPrefix = violations.single() as KeymapViolation.LeaderPrefix
        assertThat(leaderPrefix.category).isEqualTo(KeymapViolation.Category.CONFLICT)
        assertThat(leaderPrefix.singleAction).isEqualTo("help")
        assertThat(leaderPrefix.leaderActions).containsExactlyInAnyOrder("goto-my-issues", "goto-dashboard")
    }

    // ── 6. dead leader combo ─────────────────────────────────────────────────

    @Test
    fun `validate — g g (dead leader combo)는 DeadLeader 위반`() {
        val bindings = defaultBindingsWith(KeymapAction.GOTO_MY_ISSUES to "g g")

        val violations = KeymapValidator.validate(bindings)

        val deadLeader = violations.single() as KeymapViolation.DeadLeader
        assertThat(deadLeader.category).isEqualTo(KeymapViolation.Category.CONFLICT)
        assertThat(deadLeader.action).isEqualTo("goto-my-issues")
    }

    // ── helper ───────────────────────────────────────────────────────────────

    /** 기본 키맵에서 일부 action 만 [overrides] 로 교체한 5종 완비 바인딩 목록. */
    private fun defaultBindingsWith(vararg overrides: Pair<KeymapAction, String>): List<KeymapBinding> {
        val overrideMap = overrides.toMap()
        return KeymapAction.entries.map { action ->
            KeymapBinding(action.id, overrideMap[action] ?: action.defaultKeyCombo)
        }
    }
}
