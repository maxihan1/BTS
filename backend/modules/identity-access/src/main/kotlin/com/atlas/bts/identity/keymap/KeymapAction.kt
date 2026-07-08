// 단축키 action 화이트리스트 5종 + 기본 key_combo — FR-PF-03 Task 2 (순수 도메인, DB/Spring 무관)

package com.atlas.bts.identity.keymap

/**
 * 커스터마이즈 가능한 단축키 action 화이트리스트 5종 (FR-PF-03 FR3-1).
 *
 * FR-UX-05 `apps/web/src/components/keyboard-shortcuts/shortcuts.ts` 의 `SHORTCUTS` 와
 * action ID·[defaultKeyCombo] 값이 1:1 대응해야 한다(계약) — 값을 바꿀 때 두 곳을 함께 갱신한다.
 *
 * @property id 안정 식별자 — `user_keymap.action` CHECK 제약·API 요청/응답 문자열과 동일.
 * @property defaultKeyCombo 사용자가 재배치하지 않았을 때 적용되는 기본 key_combo.
 * @property displayName 설정 UI에 노출할 한국어 표시명.
 */
enum class KeymapAction(
    val id: String,
    val defaultKeyCombo: String,
    val displayName: String,
) {
    HELP("help", "?", "단축키 도움말"),
    CREATE_ISSUE("create-issue", "c", "새 이슈 생성"),
    SEARCH("search", "/", "검색으로 이동"),
    GOTO_MY_ISSUES("goto-my-issues", "g i", "내 이슈로 이동"),
    GOTO_DASHBOARD("goto-dashboard", "g d", "대시보드로 이동"),
    ;

    companion object {
        /** [id] 화이트리스트 전체 집합 — [KeymapValidator] 의 완비 검증에 사용. */
        val WHITELIST_IDS: Set<String> = entries.map { it.id }.toSet()

        /** 기본 키맵 — action 5종 전부를 [defaultKeyCombo] 로 채운 [KeymapBinding] 목록. */
        val DEFAULT_BINDINGS: List<KeymapBinding> =
            entries.map { KeymapBinding(action = it.id, keyCombo = it.defaultKeyCombo) }

        /**
         * [id] 문자열로 [KeymapAction] 을 찾는다.
         *
         * @param id action 안정 식별자
         * @return 일치하는 action, 없으면 null(화이트리스트 밖 — 호출자가 위반 처리)
         */
        fun fromId(id: String): KeymapAction? = entries.find { it.id == id }
    }
}
