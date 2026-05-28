// 프로젝트 키 VO — shared-kernel published language (issue-tracking · project-workflow 공용)

package com.bts.shared.workflow

/**
 * 프로젝트 식별 키 VO.
 *
 * issue-tracking BC 와 project-workflow BC 가 SPI 경계에서 공유하는 프로젝트 키 값 객체.
 * project-workflow BC 내부에도 동명의 VO ([com.bts.workflow.scheme.domain.ProjectKey])가 존재하지만,
 * consumer BC(issue-tracking 등)가 project-workflow 내부를 직접 import 하는 것은 BC 격리 위반이므로
 * shared-kernel 에 published language 로 복제한다.
 * 두 VO 의 유효성 규칙은 동일하게 유지되어야 한다.
 *
 * 유효 정규식: `^[A-Z][A-Z0-9]{1,9}$`
 * - 첫 글자: 대문자 알파벳
 * - 후속 문자: 대문자 알파벳 또는 숫자 1~9자
 * - 총 길이: 2자 이상 10자 이하
 *
 * 유효 예: `ATLAS`, `PROJ1`, `BTS`
 * 위반 예: `atlas` (소문자), `1PROJ` (숫자 시작)
 *
 * @property value 프로젝트 키 원문. 예: `"ATLAS"`, `"BTS"`
 */
@JvmInline
value class ProjectKey(val value: String) {
    init {
        require(REGEX.matches(value)) { "Invalid project key: '$value'. Must match ${REGEX.pattern}" }
    }

    companion object {
        /** 프로젝트 키 유효성 정규식. 대문자 알파벳 시작, 대문자·숫자 조합 2~10자. */
        val REGEX = Regex("^[A-Z][A-Z0-9]{1,9}$")

        /**
         * String 원시값으로부터 [ProjectKey]를 생성한다.
         *
         * SPI 경계에서 consumer BC 가 `String` 으로 받은 프로젝트 키를 타입 안전하게 변환할 때 사용한다.
         *
         * @param value 프로젝트 키 문자열. [REGEX] 패턴을 만족해야 한다.
         * @throws IllegalArgumentException 패턴 위반 시.
         */
        fun of(value: String): ProjectKey = ProjectKey(value)
    }
}
