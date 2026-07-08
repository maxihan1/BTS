// action-key_combo 바인딩 값 객체 + trigger 파생/형식 파싱 — FR-PF-03 Task 2 (순수 도메인, DB/Spring 무관)

package com.atlas.bts.identity.keymap

/** leader-key 시퀀스를 여는 키 — FR-UX-05 `LEADER_KEY` 와 동일 값을 유지하는 것이 계약. */
const val LEADER_KEY: String = "g"

/** leader key_combo 의 두 토큰(`"g"`, 다음 키) 사이 구분자(공백 1칸). */
private const val LEADER_SEPARATOR: String = " "

/** single/leader 어느 쪽이든 "한 번의 키 입력"에 해당하는 토큰 길이. */
private const val KEY_TOKEN_LENGTH: Int = 1

/**
 * key_combo 가 발화하는 방식 — FR-UX-05 `ShortcutTrigger` 미러.
 *
 * - [SINGLE]: 수정자 없이 그 키가 눌리면 즉시 발화.
 * - [LEADER]: [LEADER_KEY] 직후(타임아웃 이내) 다음 키가 눌리면 발화.
 */
enum class KeymapTrigger { SINGLE, LEADER }

/**
 * action 하나에 배정된 key_combo — 순수 값 객체(DB/Spring 무관).
 *
 * [action] 은 검증 전 원시 문자열이다 — 화이트리스트 위반 여부는 [KeymapValidator] 가 판정하므로
 * 이 시점에는 [KeymapAction] 으로 강제 변환하지 않는다(알 수 없는 action 도 위반 목록에 담아
 * 반환하기 위함).
 *
 * @property action action 안정 식별자(원시 문자열, 화이트리스트 밖일 수 있음).
 * @property keyCombo key_combo 원시 문자열(형식 위반·빈 값일 수 있음).
 */
data class KeymapBinding(
    val action: String,
    val keyCombo: String,
) {
    /** [keyCombo] 를 [LEADER_SEPARATOR] 기준으로 나눈 토큰 — trigger 파생/형식 검사가 공유. */
    private val tokens: List<String>
        get() = keyCombo.split(LEADER_SEPARATOR)

    /**
     * [keyCombo] 형식에서 파생한 발화 방식.
     *
     * `"g <key>"`(공백 1칸, 정확히 2토큰, 첫 토큰이 [LEADER_KEY])면 [KeymapTrigger.LEADER],
     * 그 외는 전부 [KeymapTrigger.SINGLE] 로 취급한다 — 형식 유효성 자체는 검사하지 않는다
     * ([hasValidFormat] 이 검사).
     */
    val trigger: KeymapTrigger
        get() = if (tokens.size == 2 && tokens[0] == LEADER_KEY) KeymapTrigger.LEADER else KeymapTrigger.SINGLE

    /**
     * [keyCombo] 가 single(1글자) 또는 `"g <key>"` leader(공백 1칸, 2토큰, 두 번째 토큰 1글자)
     * 형식을 만족하는지 검사한다.
     *
     * 빈 값(공백 포함)은 별도 규칙(빈값 금지, [KeymapValidator])이 담당하므로 이 함수는
     * 빈 값도 형식 위반(false)으로 취급해 이중 판정을 피한다.
     *
     * @return 형식을 만족하면 true
     */
    fun hasValidFormat(): Boolean =
        when {
            keyCombo.isBlank() -> false
            tokens.size == 1 -> keyCombo.length == KEY_TOKEN_LENGTH
            tokens.size == 2 -> tokens[0] == LEADER_KEY && tokens[1].length == KEY_TOKEN_LENGTH
            else -> false
        }

    /**
     * leader continuation 키(두 번째 토큰)를 반환한다.
     *
     * @return [trigger] 가 [KeymapTrigger.LEADER] 이면 두 번째 토큰, 아니면 null.
     */
    fun leaderContinuationKey(): String? = if (trigger == KeymapTrigger.LEADER) tokens[1] else null
}
