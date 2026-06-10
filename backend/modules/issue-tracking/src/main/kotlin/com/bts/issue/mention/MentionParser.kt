// 이슈 본문에서 @username 멘션을 추출하는 무상태 유틸

package com.bts.issue.mention

/**
 * 이슈 본문 텍스트에서 `@username` 멘션 집합을 추출하는 무상태 유틸.
 *
 * 주입 빈(@Component)이 아닌 순수 함수(object)로 설계되어 있으며,
 * `IssueApplicationService` 생성자를 변경하지 않고 직접 호출할 수 있다.
 *
 * ## 선처리 순서
 * 1. 펜스 코드 블록(`` ``` ... ``` ``) 제거 — 코드 내 `@` 는 멘션 아님.
 * 2. 인라인 코드 스팬(`` `...` ``) 제거 — 동일 이유.
 * 3. 멘션 정규식으로 username 캡처.
 *
 * ## 한계 (best-effort)
 * 불균형 백틱(예: `` `unclosed ``) 이 있으면 코드 스팬 제거가 의도대로 동작하지 않을 수 있다.
 * 이 경우 오추출된 username 은 [UserLookupPort.findIdsByUsernames] 해석 실패로 자동 드롭되므로
 * 기능적 오동작은 없지만, 멘션이 무시될 수 있다. v1 수용 — 정밀 파서는 FR-MN-02(렌더링)에서 도입.
 */
object MentionParser {
    /**
     * 펜스 코드 블록 패턴 — `` ``` `` 로 시작·끝나는 블록(DOTALL, non-greedy).
     *
     * DOTALL 플래그는 줄바꿈을 포함한 임의 문자를 `.` 로 매칭하기 위해 필요하다.
     */
    private val FENCE_CODE_BLOCK = Regex("```.*?```", setOf(RegexOption.DOT_MATCHES_ALL))

    /**
     * 인라인 코드 스팬 패턴 — 단일 백틱으로 감싼 텍스트(non-greedy).
     *
     * 펜스 코드 블록 제거 후에 적용하여 오동작을 방지한다.
     */
    private val INLINE_CODE_SPAN = Regex("`.*?`")

    /**
     * `@username` 멘션 추출 정규식.
     *
     * - `(?<![A-Za-z0-9._-])` — negative lookbehind: 앞 문자가 username 허용 문자이면 비매칭.
     *   이메일(`user@example.com`)의 `@` 는 앞에 문자가 있으므로 매칭되지 않는다.
     * - `@` — 리터럴 at sign.
     * - `([A-Za-z0-9](?:[A-Za-z0-9._-]*[A-Za-z0-9])?)` — 캡처 그룹:
     *   영숫자로 시작하고 영숫자로 끝나는 username. 가운데에는 `.`, `_`, `-` 허용.
     *   길이 1인 경우(단일 영숫자)도 매칭된다.
     *   마지막 문자가 문장부호(`@alice.`)이면 영숫자 경계에서 잘려 `alice` 만 캡처된다.
     */
    private val MENTION_PATTERN = Regex("(?<![A-Za-z0-9._-])@([A-Za-z0-9](?:[A-Za-z0-9._-]*[A-Za-z0-9])?)")

    /**
     * 주어진 텍스트에서 `@username` 멘션 집합을 추출한다.
     *
     * - [text] 가 null 이거나 blank 이면 [emptySet] 을 반환한다.
     * - 코드 블록·코드 스팬 내부의 `@username` 은 제외된다 (F2, EC-8).
     * - 이메일 주소(`user@host`)의 `@` 는 제외된다 (EC-6).
     * - 중복 username 은 집합으로 dedup 된다 (EC-2).
     * - 문장부호로 끝나는 경우 영숫자 경계까지만 캡처된다 (EC-10).
     *
     * @param text 이슈 본문 텍스트 (nullable)
     * @return 추출된 username 집합 (삽입 순서 보존)
     */
    fun extract(text: String?): Set<String> {
        if (text.isNullOrBlank()) return emptySet()

        val withoutFence = FENCE_CODE_BLOCK.replace(text, "")
        val stripped = INLINE_CODE_SPAN.replace(withoutFence, "")

        val result = LinkedHashSet<String>()
        MENTION_PATTERN.findAll(stripped).forEach { match ->
            result.add(match.groupValues[1])
        }
        return result
    }
}
