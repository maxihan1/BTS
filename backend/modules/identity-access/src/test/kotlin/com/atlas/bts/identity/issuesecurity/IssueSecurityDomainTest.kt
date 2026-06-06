// 이슈 보안 수준 도메인 모델(스킴/등급/멤버) 불변식·다형 검증 테스트 (FR-PM-06)

package com.atlas.bts.identity.issuesecurity

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

class IssueSecurityDomainTest {
    // ----- IssueSecurityScheme.create -----

    @Test
    fun `Scheme create는 name 앞뒤 공백을 trim한다`() {
        val scheme = IssueSecurityScheme.create(name = "  임원 전용  ", description = null)

        assertThat(scheme.name).isEqualTo("임원 전용")
        assertThat(scheme.id).isNull()
        assertThat(scheme.createdAt).isNull()
        assertThat(scheme.updatedAt).isNull()
    }

    @Test
    fun `Scheme create는 description 빈 문자열을 null로 정규화한다`() {
        val scheme = IssueSecurityScheme.create(name = "기밀", description = "   ")

        assertThat(scheme.description).isNull()
    }

    @Test
    fun `Scheme create는 빈 name에 예외를 던진다`() {
        assertThatThrownBy { IssueSecurityScheme.create(name = "   ", description = null) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `Scheme create는 255자 초과 name에 예외를 던진다`() {
        assertThatThrownBy { IssueSecurityScheme.create(name = "a".repeat(256), description = null) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `Scheme create는 500자 초과 description에 예외를 던진다`() {
        assertThatThrownBy { IssueSecurityScheme.create(name = "기밀", description = "a".repeat(501)) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    // ----- IssueSecurityLevel.create -----

    @Test
    fun `Level create는 name을 trim하고 필드를 보존한다`() {
        val schemeId = UUID.randomUUID()
        val level =
            IssueSecurityLevel.create(
                schemeId = schemeId,
                name = "  임원만  ",
                description = null,
                isDefault = true,
            )

        assertThat(level.schemeId).isEqualTo(schemeId)
        assertThat(level.name).isEqualTo("임원만")
        assertThat(level.isDefault).isTrue()
        assertThat(level.id).isNull()
        assertThat(level.createdAt).isNull()
    }

    @Test
    fun `Level create는 빈 name에 예외를 던진다`() {
        assertThatThrownBy {
            IssueSecurityLevel.create(
                schemeId = UUID.randomUUID(),
                name = "",
                description = null,
                isDefault = false,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `Level create는 255자 초과 name에 예외를 던진다`() {
        assertThatThrownBy {
            IssueSecurityLevel.create(
                schemeId = UUID.randomUUID(),
                name = "a".repeat(256),
                description = null,
                isDefault = false,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    // ----- MemberType enum -----

    @Test
    fun `MemberType은 5종을 가진다`() {
        assertThat(MemberType.entries).containsExactlyInAnyOrder(
            MemberType.REPORTER,
            MemberType.ASSIGNEE,
            MemberType.USER,
            MemberType.PROJECT_ROLE,
            MemberType.GROUP,
        )
    }

    // ----- SecurityLevelMember.create 다형 검증 -----

    @Test
    fun `Member create는 USER 타입에 UUID memberValue를 허용한다`() {
        val levelId = UUID.randomUUID()
        val userId = UUID.randomUUID().toString()
        val member =
            SecurityLevelMember.create(
                levelId = levelId,
                memberType = MemberType.USER,
                memberValue = userId,
            )

        assertThat(member.levelId).isEqualTo(levelId)
        assertThat(member.memberType).isEqualTo(MemberType.USER)
        assertThat(member.memberValue).isEqualTo(userId)
        assertThat(member.id).isNull()
        assertThat(member.createdAt).isNull()
    }

    @Test
    fun `Member create는 GROUP 타입에 UUID memberValue를 허용한다`() {
        val member =
            SecurityLevelMember.create(
                levelId = UUID.randomUUID(),
                memberType = MemberType.GROUP,
                memberValue = UUID.randomUUID().toString(),
            )

        assertThat(member.memberType).isEqualTo(MemberType.GROUP)
    }

    @Test
    fun `Member create는 USER 타입에 비-UUID memberValue면 예외를 던진다`() {
        assertThatThrownBy {
            SecurityLevelMember.create(
                levelId = UUID.randomUUID(),
                memberType = MemberType.USER,
                memberValue = "not-a-uuid",
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `Member create는 USER 타입에 비표준 UUID 형식이면 예외를 던진다 (N3)`() {
        // UUID.fromString 은 "1-1-1-1-1" 같은 비표준 단축형도 관대하게 허용한다.
        // 표준 8-4-4-4-12 hex 가 아니면 1차 도메인 검증에서 거부해야 한다.
        assertThatThrownBy {
            SecurityLevelMember.create(
                levelId = UUID.randomUUID(),
                memberType = MemberType.USER,
                memberValue = "1-1-1-1-1",
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `Member create는 GROUP 타입에 비표준 UUID 형식이면 예외를 던진다 (N3)`() {
        assertThatThrownBy {
            SecurityLevelMember.create(
                levelId = UUID.randomUUID(),
                memberType = MemberType.GROUP,
                memberValue = "123-456-789-abc-def",
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `Member create는 USER 타입에 null memberValue면 예외를 던진다`() {
        assertThatThrownBy {
            SecurityLevelMember.create(
                levelId = UUID.randomUUID(),
                memberType = MemberType.USER,
                memberValue = null,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `Member create는 PROJECT_ROLE에 PROJECT_ADMIN과 MEMBER를 허용한다`() {
        val admin =
            SecurityLevelMember.create(
                levelId = UUID.randomUUID(),
                memberType = MemberType.PROJECT_ROLE,
                memberValue = "PROJECT_ADMIN",
            )
        val member =
            SecurityLevelMember.create(
                levelId = UUID.randomUUID(),
                memberType = MemberType.PROJECT_ROLE,
                memberValue = "MEMBER",
            )

        assertThat(admin.memberValue).isEqualTo("PROJECT_ADMIN")
        assertThat(member.memberValue).isEqualTo("MEMBER")
    }

    @Test
    fun `Member create는 PROJECT_ROLE에 미지원 역할이면 예외를 던진다`() {
        assertThatThrownBy {
            SecurityLevelMember.create(
                levelId = UUID.randomUUID(),
                memberType = MemberType.PROJECT_ROLE,
                memberValue = "OWNER",
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `Member create는 PROJECT_ROLE에 null memberValue면 예외를 던진다`() {
        assertThatThrownBy {
            SecurityLevelMember.create(
                levelId = UUID.randomUUID(),
                memberType = MemberType.PROJECT_ROLE,
                memberValue = null,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `Member create는 REPORTER에 null memberValue를 허용한다`() {
        val member =
            SecurityLevelMember.create(
                levelId = UUID.randomUUID(),
                memberType = MemberType.REPORTER,
                memberValue = null,
            )

        assertThat(member.memberType).isEqualTo(MemberType.REPORTER)
        assertThat(member.memberValue).isNull()
    }

    @Test
    fun `Member create는 ASSIGNEE에 null memberValue를 허용한다`() {
        val member =
            SecurityLevelMember.create(
                levelId = UUID.randomUUID(),
                memberType = MemberType.ASSIGNEE,
                memberValue = null,
            )

        assertThat(member.memberValue).isNull()
    }

    @Test
    fun `Member create는 REPORTER에 memberValue 동반 시 예외를 던진다`() {
        assertThatThrownBy {
            SecurityLevelMember.create(
                levelId = UUID.randomUUID(),
                memberType = MemberType.REPORTER,
                memberValue = UUID.randomUUID().toString(),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `Member create는 ASSIGNEE에 memberValue 동반 시 예외를 던진다`() {
        assertThatThrownBy {
            SecurityLevelMember.create(
                levelId = UUID.randomUUID(),
                memberType = MemberType.ASSIGNEE,
                memberValue = "anything",
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
