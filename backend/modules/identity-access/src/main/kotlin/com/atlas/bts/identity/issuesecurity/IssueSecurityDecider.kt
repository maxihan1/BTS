// 이슈 보안 등급 멤버십 충족을 판정하는 순수 함수(I/O 없음, 호출자가 데이터 주입) (FR-PM-06)

package com.atlas.bts.identity.issuesecurity

import java.util.UUID

/**
 * 이슈 보안 등급([IssueSecurityLevel]) 멤버십 충족 여부를 판정하는 순수 함수.
 *
 * 멤버 리스트·actor 역할·그룹 소속 등 판정에 필요한 모든 사실은 인자로 주입받으며,
 * 이 객체는 DB·네트워크 등 어떤 I/O도 수행하지 않는다(조회는 호출자 책임). 그 덕에
 * 결정 로직을 격리해 단위 테스트로 망라할 수 있다.
 *
 * ## 관리자 우회 없음 (ADR §결정5)
 * 시스템 관리자라도 등급 멤버가 아니면 통과하지 못한다. 이 함수에는 admin 단락 경로가 없으며,
 * 멤버십(OR)만이 유일한 통과 수단이다.
 *
 * 판정에 필요한 사실(actor·이슈·멤버·역할·그룹)을 모두 인자로 주입하는 순수 함수라 파라미터가 많다
 * (I/O 분리의 대가). 이 때문에 [LongParameterList]를 의도적으로 억제한다.
 *
 * @see docs/decisions/2026-06-06-issue-security-level-scheme-model.md 설계 결정 ADR
 */
@Suppress("LongParameterList")
object IssueSecurityDecider {
    /**
     * actor가 지정 보안 등급을 통과할 자격이 있는지 판정한다.
     *
     * 판정 순서.
     * 1. [securityLevelId]가 `null`이면 공개 이슈이므로 무조건 통과(`true`).
     * 2. 등급은 지정됐으나 [members]가 비어 있으면(고아 등급) 보수적으로 차단(`false`).
     *    등급 미지정(공개)과 멤버 0(차단)은 의미가 다르므로 구분한다.
     * 3. [members] 중 하나라도 충족하면 통과(OR). 어느 것도 충족 못 하면 차단.
     *
     * 멤버 타입별 충족 규칙.
     * - [MemberType.REPORTER]: actor가 이슈 보고자([reporterId])와 동일.
     * - [MemberType.ASSIGNEE]: 이슈가 할당돼 있고 actor가 담당자([assigneeId])와 동일.
     * - [MemberType.USER]: 멤버 값(UUID 문자열)이 actor와 동일.
     * - [MemberType.GROUP]: 멤버 값(그룹 UUID)에 actor가 소속([actorGroupIds] 포함).
     * - [MemberType.PROJECT_ROLE]: actor가 프로젝트 멤버이고 역할명이 멤버 값과 동일.
     *
     * @param actorId 접근을 시도하는 사용자 식별자.
     * @param securityLevelId 이슈에 지정된 보안 등급 식별자. `null`이면 공개.
     * @param reporterId 이슈 보고자 식별자.
     * @param assigneeId 이슈 담당자 식별자. 미할당이면 `null`.
     * @param members 해당 보안 등급의 멤버 목록. 고아 등급이면 빈 리스트.
     * @param actorProjectRole actor의 프로젝트 역할명. 프로젝트 비멤버이면 `null`.
     * @param actorGroupIds actor가 소속된 그룹 식별자 집합.
     * @return 멤버십(OR) 또는 공개로 통과하면 `true`, 아니면 `false`.
     */
    fun isAllowed(
        actorId: UUID,
        securityLevelId: UUID?,
        reporterId: UUID,
        assigneeId: UUID?,
        members: List<SecurityLevelMember>,
        actorProjectRole: String?,
        actorGroupIds: Set<UUID>,
    ): Boolean {
        // 1. 등급 미지정 = 공개 이슈.
        if (securityLevelId == null) return true

        // 2. 고아 등급(멤버 0)은 공개와 구분해 보수적으로 차단(C4).
        //    멤버가 있으면 그중 하나라도 충족하면 통과(OR). isEmpty 시 any가 false라 차단도 함께 성립.
        return members.any { member ->
            satisfies(
                member = member,
                actorId = actorId,
                reporterId = reporterId,
                assigneeId = assigneeId,
                actorProjectRole = actorProjectRole,
                actorGroupIds = actorGroupIds,
            )
        }
    }

    /**
     * 단일 멤버가 actor에 의해 충족되는지 평가한다.
     *
     * [MemberType] 전 타입을 명시적으로 분기한다(`else` 없음). 새 멤버 타입이 추가되면
     * 컴파일이 실패하므로 판정 규칙 갱신을 강제한다.
     */
    private fun satisfies(
        member: SecurityLevelMember,
        actorId: UUID,
        reporterId: UUID,
        assigneeId: UUID?,
        actorProjectRole: String?,
        actorGroupIds: Set<UUID>,
    ): Boolean =
        when (member.memberType) {
            MemberType.REPORTER -> actorId == reporterId
            MemberType.ASSIGNEE -> assigneeId != null && actorId == assigneeId
            MemberType.USER -> member.memberValue == actorId.toString()
            MemberType.GROUP -> member.memberValue?.let { actorGroupIds.contains(UUID.fromString(it)) } ?: false
            MemberType.PROJECT_ROLE -> actorProjectRole != null && actorProjectRole == member.memberValue
        }
}
