// IssueImportCommand/VO userId 필드(사용자 매핑 PR2) 기본값·세팅 단위 테스트
package com.bts.shared.issue

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * [IssueImportCommand] 및 4개 VO([ImportComment]/[ImportWorklog]/[ImportAttachment]/[ImportChangeGroup])
 * 의 `*UserId` 필드(FR-IM-02 PR-B 사용자 매핑) 기본값 및 세팅 단위 테스트다.
 *
 * 이메일 기반 매핑([IssueImportCommand.reporterEmail] 등)이 있는 채로 프로세서가
 * 명시적으로 해석한 사용자 UUID 를 담을 수 있는지, 그리고 미지정 시 기존 호출부에
 * 회귀가 없도록 기본값이 null 인지를 검증한다.
 */
class IssueImportCommandTest {
    private fun sampleCommand(): IssueImportCommand =
        IssueImportCommand(
            projectKey = "PROJ",
            requesterUserId = UUID.randomUUID(),
            summary = "마이그레이션 대상 이슈",
        )

    @Test
    fun `IssueImportCommand 는 reporterUserId assigneeUserId 기본값이 null 이다`() {
        val command = sampleCommand()

        assertThat(command.reporterUserId).isNull()
        assertThat(command.assigneeUserId).isNull()
    }

    @Test
    fun `IssueImportCommand 는 reporterUserId assigneeUserId 를 명시적으로 지정할 수 있다`() {
        val reporterUserId = UUID.randomUUID()
        val assigneeUserId = UUID.randomUUID()

        val command =
            sampleCommand().copy(
                reporterUserId = reporterUserId,
                assigneeUserId = assigneeUserId,
            )

        assertThat(command.reporterUserId).isEqualTo(reporterUserId)
        assertThat(command.assigneeUserId).isEqualTo(assigneeUserId)
    }

    @Test
    fun `ImportComment 는 authorUserId 기본값이 null 이다`() {
        val comment = ImportComment(body = "댓글 본문")

        assertThat(comment.authorUserId).isNull()
    }

    @Test
    fun `ImportComment 는 authorUserId 를 명시적으로 지정할 수 있다`() {
        val authorUserId = UUID.randomUUID()

        val comment = ImportComment(body = "댓글 본문", authorUserId = authorUserId)

        assertThat(comment.authorUserId).isEqualTo(authorUserId)
    }

    @Test
    fun `ImportWorklog 는 authorUserId 기본값이 null 이다`() {
        val worklog = ImportWorklog(timeSpentSeconds = 3600)

        assertThat(worklog.authorUserId).isNull()
    }

    @Test
    fun `ImportWorklog 는 authorUserId 를 명시적으로 지정할 수 있다`() {
        val authorUserId = UUID.randomUUID()

        val worklog = ImportWorklog(timeSpentSeconds = 3600, authorUserId = authorUserId)

        assertThat(worklog.authorUserId).isEqualTo(authorUserId)
    }

    @Test
    fun `ImportAttachment 는 authorUserId 기본값이 null 이다`() {
        val attachment = ImportAttachment(filename = "screenshot.png")

        assertThat(attachment.authorUserId).isNull()
    }

    @Test
    fun `ImportAttachment 는 authorUserId 를 명시적으로 지정할 수 있다`() {
        val authorUserId = UUID.randomUUID()

        val attachment = ImportAttachment(filename = "screenshot.png", authorUserId = authorUserId)

        assertThat(attachment.authorUserId).isEqualTo(authorUserId)
    }

    @Test
    fun `ImportChangeGroup 은 authorUserId 기본값이 null 이다`() {
        val group = ImportChangeGroup()

        assertThat(group.authorUserId).isNull()
    }

    @Test
    fun `ImportChangeGroup 은 authorUserId 를 명시적으로 지정할 수 있다`() {
        val authorUserId = UUID.randomUUID()

        val group = ImportChangeGroup(authorUserId = authorUserId)

        assertThat(group.authorUserId).isEqualTo(authorUserId)
    }
}
