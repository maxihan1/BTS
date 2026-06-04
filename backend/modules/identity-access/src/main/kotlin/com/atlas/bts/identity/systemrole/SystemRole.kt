// 전역 시스템 역할(SYSTEM_ADMIN)을 정의하는 enum (FR-PM-08)

package com.atlas.bts.identity.systemrole

/**
 * 전역(시스템) 사용자 역할.
 *
 * 프로젝트 단위 역할인 [com.atlas.bts.identity.project.ProjectRole]과는 **완전히 별개의 축**이다.
 * SystemRole은 특정 프로젝트에 종속되지 않고 시스템 전체에 적용되는 권한을 나타낸다
 * (docs/decisions/2026-06-04-system-admin-role.md D1).
 *
 * ## 역할 정의
 * - [SYSTEM_ADMIN]: 시스템 전역 관리자. 프로젝트와 무관하게 시스템 수준 작업 권한을 가진다.
 *
 * ## [from] 정규화 규칙
 * 입력값은 정확한 enum name과 일치해야 한다. 소문자·별칭·공백 모두 불허.
 * 유효하지 않은 값은 [IllegalArgumentException]을 발생시키며, 상위 레이어가
 * 이를 적절한 HTTP 오류로 매핑한다.
 *
 * @see docs/decisions/2026-06-04-system-admin-role.md 전역 관리자 역할 확정 ADR (D1/D2)
 */
enum class SystemRole {
    /** 시스템 전역 관리자 역할. 프로젝트에 종속되지 않는 시스템 수준 권한을 가진다. */
    SYSTEM_ADMIN,
    ;

    companion object {
        /**
         * 문자열 [raw]를 [SystemRole]로 변환한다.
         *
         * 허용값은 enum name과 정확히 일치하는 경우만이다: `"SYSTEM_ADMIN"`.
         * 대소문자 구분을 적용하며, 소문자·별칭·공백 포함 입력은 모두 거부한다.
         *
         * @param raw API 요청 등에서 전달된 역할 문자열
         * @return 일치하는 [SystemRole]
         * @throws IllegalArgumentException [raw]가 허용된 enum name과 일치하지 않을 때
         */
        fun from(raw: String): SystemRole =
            entries.find { it.name == raw }
                ?: throw IllegalArgumentException(
                    "알 수 없는 SystemRole: '$raw'. 허용값: ${entries.map { it.name }}",
                )
    }
}
