// 프로젝트 멤버 역할(PROJECT_ADMIN/MEMBER)을 정의하는 enum (FR-PM-01)

package com.atlas.bts.identity.project

/**
 * 프로젝트 내 사용자 역할.
 *
 * BTS 프로젝트 관리(FR-PM-01) 범위의 두 역할만 정의한다.
 * SDD §8에 언급된 세분화 역할(VIEWER, TRIAGER 등)은 후속 FR에서 확장한다.
 *
 * ## 역할 정의
 * - [PROJECT_ADMIN]: 프로젝트 설정 변경 및 멤버 관리 권한을 가진 관리자.
 * - [MEMBER]: 이슈 조회·생성·댓글 등 일반 작업 권한을 가진 일반 멤버.
 *
 * ## [from] 정규화 규칙
 * 입력값은 정확한 enum name과 일치해야 한다. 소문자·별칭·공백 모두 불허.
 * 유효하지 않은 값은 [IllegalArgumentException]을 발생시키며, 상위 레이어가
 * 이를 HTTP 422 `invalid_role`로 매핑한다.
 *
 * @see docs/decisions/2026-06-01-project-membership-model.md 역할 2종 확정 ADR
 */
enum class ProjectRole {

    /** 프로젝트 설정 변경 및 멤버 관리 권한을 가진 관리자 역할. */
    PROJECT_ADMIN,

    /** 이슈 조회·생성·댓글 등 일반 작업 권한을 가진 일반 멤버 역할. */
    MEMBER;

    companion object {

        /**
         * 문자열 [raw]를 [ProjectRole]로 변환한다.
         *
         * 허용값은 enum name과 정확히 일치하는 경우만이다: `"PROJECT_ADMIN"`, `"MEMBER"`.
         * 대소문자 구분을 적용하며, 소문자·별칭·공백 포함 입력은 모두 거부한다.
         *
         * @param raw API 요청 등에서 전달된 역할 문자열
         * @return 일치하는 [ProjectRole]
         * @throws IllegalArgumentException [raw]가 허용된 enum name과 일치하지 않을 때
         */
        fun from(raw: String): ProjectRole =
            entries.find { it.name == raw }
                ?: throw IllegalArgumentException(
                    "알 수 없는 ProjectRole: '$raw'. 허용값: ${entries.map { it.name }}"
                )
    }
}
