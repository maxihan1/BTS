// 이슈 보안 등급 멤버십 충족 판정 순수 함수 단위 테스트 (FR-PM-06)

package com.atlas.bts.identity.issuesecurity

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class IssueSecurityDeciderTest {
    private val actorId = UUID.fromString("00000000-0000-4000-8000-000000000001")
    private val reporterId = UUID.fromString("00000000-0000-4000-8000-000000000002")
    private val assigneeId = UUID.fromString("00000000-0000-4000-8000-000000000003")
    private val groupId = UUID.fromString("00000000-0000-4000-8000-000000000004")
    private val levelId = UUID.fromString("00000000-0000-4000-8000-0000000000aa")

    private fun member(
        type: MemberType,
        value: String?,
    ) = SecurityLevelMember(
        id = UUID.randomUUID(),
        levelId = levelId,
        memberType = type,
        memberValue = value,
        createdAt = null,
    )

    @Suppress("LongParameterList") // 판정 함수 입력 7종을 기본값으로 노출하는 테스트 빌더(케이스별 일부만 override).
    private fun decide(
        actor: UUID = actorId,
        level: UUID? = levelId,
        reporter: UUID = reporterId,
        assignee: UUID? = assigneeId,
        members: List<SecurityLevelMember>,
        role: String? = null,
        groups: Set<UUID> = emptySet(),
    ): Boolean =
        IssueSecurityDecider.isAllowed(
            actorId = actor,
            securityLevelId = level,
            reporterId = reporter,
            assigneeId = assignee,
            members = members,
            actorProjectRole = role,
            actorGroupIds = groups,
        )

    // ----- 등급 미지정(공개) -----

    @Test
    fun `등급이 NULL이면 공개로 간주해 true`() {
        assertThat(
            decide(level = null, members = emptyList()),
        ).isTrue()
    }

    // ----- 고아 등급(멤버 0) -----

    @Test
    fun `등급이 지정됐으나 멤버가 0이면 보수적으로 차단해 false`() {
        assertThat(
            decide(members = emptyList()),
        ).isFalse()
    }

    // ----- REPORTER -----

    @Test
    fun `REPORTER 멤버이고 actor가 보고자이면 true`() {
        assertThat(
            decide(
                actor = reporterId,
                members = listOf(member(MemberType.REPORTER, null)),
            ),
        ).isTrue()
    }

    @Test
    fun `REPORTER 멤버이나 actor가 보고자가 아니면 false`() {
        assertThat(
            decide(members = listOf(member(MemberType.REPORTER, null))),
        ).isFalse()
    }

    // ----- ASSIGNEE -----

    @Test
    fun `ASSIGNEE 멤버이고 actor가 담당자이면 true`() {
        assertThat(
            decide(
                actor = assigneeId,
                members = listOf(member(MemberType.ASSIGNEE, null)),
            ),
        ).isTrue()
    }

    @Test
    fun `ASSIGNEE 멤버이나 이슈가 미할당이면 false`() {
        assertThat(
            decide(
                assignee = null,
                members = listOf(member(MemberType.ASSIGNEE, null)),
            ),
        ).isFalse()
    }

    // ----- USER -----

    @Test
    fun `USER 멤버 값이 actor UUID와 같으면 true`() {
        assertThat(
            decide(members = listOf(member(MemberType.USER, actorId.toString()))),
        ).isTrue()
    }

    @Test
    fun `USER 멤버 값이 actor UUID와 다르면 false`() {
        assertThat(
            decide(members = listOf(member(MemberType.USER, reporterId.toString()))),
        ).isFalse()
    }

    // ----- GROUP -----

    @Test
    fun `GROUP 멤버 그룹에 actor가 소속이면 true`() {
        assertThat(
            decide(
                members = listOf(member(MemberType.GROUP, groupId.toString())),
                groups = setOf(groupId),
            ),
        ).isTrue()
    }

    @Test
    fun `GROUP 멤버 그룹에 actor가 미소속이면 false`() {
        assertThat(
            decide(
                members = listOf(member(MemberType.GROUP, groupId.toString())),
                groups = emptySet(),
            ),
        ).isFalse()
    }

    // ----- PROJECT_ROLE -----

    @Test
    fun `PROJECT_ROLE 멤버 값이 actor 역할과 일치하면 true`() {
        assertThat(
            decide(
                members = listOf(member(MemberType.PROJECT_ROLE, "MEMBER")),
                role = "MEMBER",
            ),
        ).isTrue()
    }

    @Test
    fun `PROJECT_ROLE 멤버 값이 actor 역할과 다르면 false`() {
        assertThat(
            decide(
                members = listOf(member(MemberType.PROJECT_ROLE, "PROJECT_ADMIN")),
                role = "MEMBER",
            ),
        ).isFalse()
    }

    @Test
    fun `PROJECT_ROLE 멤버이나 actor가 프로젝트 비멤버(role null)이면 false`() {
        assertThat(
            decide(
                members = listOf(member(MemberType.PROJECT_ROLE, "MEMBER")),
                role = null,
            ),
        ).isFalse()
    }

    // ----- 다중 멤버 OR -----

    @Test
    fun `여러 멤버 중 하나라도 충족하면 OR로 true(REPORTER 불충족 USER 충족)`() {
        assertThat(
            decide(
                members =
                    listOf(
                        member(MemberType.REPORTER, null),
                        member(MemberType.USER, actorId.toString()),
                    ),
            ),
        ).isTrue()
    }

    @Test
    fun `actor가 보고자이면서 USER 멤버 대상이어도 OR로 true`() {
        assertThat(
            decide(
                actor = reporterId,
                members =
                    listOf(
                        member(MemberType.REPORTER, null),
                        member(MemberType.USER, reporterId.toString()),
                    ),
            ),
        ).isTrue()
    }

    @Test
    fun `여러 멤버 모두 불충족이면 false`() {
        assertThat(
            decide(
                members =
                    listOf(
                        member(MemberType.REPORTER, null),
                        member(MemberType.ASSIGNEE, null),
                        member(MemberType.USER, reporterId.toString()),
                    ),
                assignee = null,
            ),
        ).isFalse()
    }
}
