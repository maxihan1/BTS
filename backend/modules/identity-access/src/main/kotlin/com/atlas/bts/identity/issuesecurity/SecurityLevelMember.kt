// 이슈 보안 등급 멤버 도메인 모델 — 멤버 타입별 memberValue 다형 검증 (FR-PM-06)

package com.atlas.bts.identity.issuesecurity

import java.time.Instant
import java.util.UUID

/**
 * 보안 등급 멤버 타입.
 *
 * 어떤 actor가 보안 등급을 통과하는지 규정하는 5종 다형 타입이다.
 * - [REPORTER]: 이슈 보고자. `memberValue` 없음.
 * - [ASSIGNEE]: 이슈 담당자. `memberValue` 없음.
 * - [USER]: 특정 사용자. `memberValue`는 사용자 UUID.
 * - [PROJECT_ROLE]: 프로젝트 역할(`PROJECT_ADMIN`/`MEMBER`). `memberValue`는 역할 문자열.
 * - [GROUP]: 사용자 그룹(FR-PM-09). `memberValue`는 그룹 UUID.
 */
enum class MemberType {
    REPORTER,
    ASSIGNEE,
    USER,
    PROJECT_ROLE,
    GROUP,
}

/**
 * 이슈 보안 등급 멤버 도메인 모델.
 *
 * 보안 등급([IssueSecurityLevel])을 통과할 수 있는 한 멤버를 표현한다. [memberType]에 따라
 * [memberValue]의 의미·형식이 달라지는 다형 모델이며, `issue_security_level_members` 테이블 행과
 * 1:1 매핑된다. 모든 필드는 불변(val)이다.
 *
 * ## 생성 규칙
 * 신규 멤버는 반드시 [create] 팩토리를 경유해 [memberType]별 [memberValue] 규칙을 강제한다.
 * 서비스 계층은 이 검증을 우회하지 않는다(도메인 우회 금지).
 *
 * ## 필드
 * - [id]: 멤버 식별자. 신규 생성 시 `null`이며 영속 계층이 채운다.
 * - [levelId]: 소속 보안 등급 식별자.
 * - [memberType]: 멤버 타입.
 * - [memberValue]: 타입별 값. USER/GROUP=UUID 문자열, PROJECT_ROLE=역할 문자열, REPORTER/ASSIGNEE=`null`.
 * - [createdAt]: 최초 생성 시각. 신규 생성 시 `null`이며 영속 계층이 채운다.
 *
 * @see docs/decisions/2026-06-06-issue-security-level-scheme-model.md 설계 결정 ADR
 */
data class SecurityLevelMember(
    val id: UUID?,
    val levelId: UUID,
    val memberType: MemberType,
    val memberValue: String?,
    val createdAt: Instant?,
) {
    companion object {
        /** [MemberType.PROJECT_ROLE]에서 [memberValue]로 허용되는 역할 문자열. */
        val ALLOWED_PROJECT_ROLES = setOf("PROJECT_ADMIN", "MEMBER")

        /**
         * [memberType]별 다형 규칙을 검증해 신규 [SecurityLevelMember]를 생성한다.
         *
         * - USER/GROUP: [memberValue]는 UUID 형식 문자열이어야 한다.
         * - PROJECT_ROLE: [memberValue]는 [ALLOWED_PROJECT_ROLES] 중 하나여야 한다.
         * - REPORTER/ASSIGNEE: [memberValue]는 `null`이어야 한다(동반 시 예외).
         *
         * @param levelId 소속 보안 등급 식별자.
         * @param memberType 멤버 타입.
         * @param memberValue 타입별 값 (nullable).
         * @return 불변식을 만족하는 신규 [SecurityLevelMember] ([id]/타임스탬프는 `null`).
         * @throws IllegalArgumentException 타입별 [memberValue] 규칙을 위반한 경우.
         */
        fun create(
            levelId: UUID,
            memberType: MemberType,
            memberValue: String?,
        ): SecurityLevelMember {
            val normalizedValue = validateMemberValue(memberType, memberValue)
            return SecurityLevelMember(
                id = null,
                levelId = levelId,
                memberType = memberType,
                memberValue = normalizedValue,
                createdAt = null,
            )
        }

        /**
         * [memberType]별 [memberValue] 규칙을 검증하고 정규화된 값을 반환한다.
         *
         * @return USER/GROUP/PROJECT_ROLE은 검증된 값, REPORTER/ASSIGNEE는 `null`.
         * @throws IllegalArgumentException 규칙 위반 시.
         */
        private fun validateMemberValue(
            memberType: MemberType,
            memberValue: String?,
        ): String? =
            when (memberType) {
                MemberType.USER, MemberType.GROUP -> {
                    requireNotNull(memberValue) { "${memberType.name} 멤버는 UUID 값이 필요합니다." }
                    require(isUuid(memberValue)) { "${memberType.name} 멤버 값은 UUID 형식이어야 합니다." }
                    memberValue
                }
                MemberType.PROJECT_ROLE -> {
                    requireNotNull(memberValue) { "PROJECT_ROLE 멤버는 역할 값이 필요합니다." }
                    require(memberValue in ALLOWED_PROJECT_ROLES) {
                        "PROJECT_ROLE 멤버 값은 $ALLOWED_PROJECT_ROLES 중 하나여야 합니다."
                    }
                    memberValue
                }
                MemberType.REPORTER, MemberType.ASSIGNEE -> {
                    require(memberValue == null) { "${memberType.name} 멤버는 값을 가질 수 없습니다." }
                    null
                }
            }

        /** 문자열이 UUID 형식인지 검사한다. */
        private fun isUuid(value: String): Boolean =
            try {
                UUID.fromString(value)
                true
            } catch (_: IllegalArgumentException) {
                false
            }
    }
}
