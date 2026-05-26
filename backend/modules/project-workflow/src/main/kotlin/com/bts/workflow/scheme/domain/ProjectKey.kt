// 프로젝트 키 VO — project-workflow BC 내부 사용 (BC 격리, ArchUnit NFR-7 준수)

package com.bts.workflow.scheme.domain

/**
 * 프로젝트 식별 키 VO.
 *
 * project-workflow BC 내부에서만 사용하는 프로젝트 키 값 객체.
 * ArchUnit NFR-7 에 따라 project-workflow BC 는 issue-tracking domain 패키지를 import 할 수 없으므로,
 * cross-BC 공유 없이 각 BC 가 동일한 키 의미론을 자체 VO 로 보유한다.
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
        /**
         * 프로젝트 키 정규식.
         *
         * 규칙 요약.
         * - `^[A-Z]` — 대문자 알파벳으로 시작
         * - `[A-Z0-9]{1,9}` — 대문자·숫자 조합 후속 1~9자
         * - 총 길이 2~10자
         */
        val REGEX = Regex("^[A-Z][A-Z0-9]{1,9}$")
    }
}
