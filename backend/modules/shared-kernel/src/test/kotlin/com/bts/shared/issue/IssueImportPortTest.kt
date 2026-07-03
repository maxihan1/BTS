// IssueImportPort default 구현 fail-closed 계약 + IssueImportResult factory 단위 테스트
package com.bts.shared.issue

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.time.Instant
import java.util.UUID

/**
 * [IssueImportPort.importIssue] default 구현 및 [IssueImportResult] factory 단위 테스트 (FR-IM-01 Task 3).
 *
 * adapter(issue-tracking 구현체)가 등록되지 않은 환경에서 default 구현이
 * 성공으로 위장하지 않고 명시적 실패([IssueImportResult.ADAPTER_UNAVAILABLE])를
 * 반환하는 fail-closed 계약을 검증한다.
 * Spring 컨텍스트 없이 순수 단위 테스트로 실행한다.
 */
class IssueImportPortTest {
    private fun sampleCommand(): IssueImportCommand =
        IssueImportCommand(
            projectKey = "PROJ",
            requesterUserId = UUID.randomUUID(),
            summary = "마이그레이션 대상 이슈",
        )

    @Test
    fun `importIssue default 구현은 fail-closed 실패 결과를 반환한다`() {
        val port = object : IssueImportPort {}

        val result = port.importIssue(sampleCommand())

        assertThat(result).isInstanceOf(IssueImportResult.Failure::class.java)
        val failure = result as IssueImportResult.Failure
        assertThat(failure.reasonCode).isEqualTo(IssueImportResult.ADAPTER_UNAVAILABLE)
    }

    @Test
    fun `importIssue default 구현은 성공으로 위장하지 않는다`() {
        val port = object : IssueImportPort {}

        val result = port.importIssue(sampleCommand())

        assertThat(result).isNotInstanceOf(IssueImportResult.Success::class.java)
    }

    @Test
    fun `IssueImportResult success factory 는 issueKey 를 담은 Success 를 생성한다`() {
        val result = IssueImportResult.success("PROJ-1")

        assertThat(result).isInstanceOf(IssueImportResult.Success::class.java)
        val success = result as IssueImportResult.Success
        assertThat(success.issueKey).isEqualTo("PROJ-1")
        assertThat(success.warnings).isEmpty()
    }

    @Test
    fun `IssueImportResult success factory 는 warnings 를 함께 담을 수 있다`() {
        val result = IssueImportResult.success("PROJ-2", warnings = listOf("컴포넌트 미발견 — 스킵"))

        val success = result as IssueImportResult.Success
        assertThat(success.warnings).containsExactly("컴포넌트 미발견 — 스킵")
    }

    @Test
    fun `IssueImportResult failure factory 는 reasonCode 와 message 를 담은 Failure 를 생성한다`() {
        val result = IssueImportResult.failure(IssueImportResult.FORBIDDEN, "CREATE_ISSUE 권한 없음")

        assertThat(result).isInstanceOf(IssueImportResult.Failure::class.java)
        val failure = result as IssueImportResult.Failure
        assertThat(failure.reasonCode).isEqualTo(IssueImportResult.FORBIDDEN)
        assertThat(failure.message).isEqualTo("CREATE_ISSUE 권한 없음")
    }

    @Test
    fun `IssueImportResult failure factory 는 message 없이도 생성할 수 있다`() {
        val result = IssueImportResult.failure(IssueImportResult.UNKNOWN)

        assertThat(result).isInstanceOf(IssueImportResult.Failure::class.java)
        val failure = result as IssueImportResult.Failure
        assertThat(failure.message).isNull()
    }

    @Test
    fun `IssueImportCommand 는 필수 3개 필드만 지정해도 신규 필드 기본값을 갖는다`() {
        val command = sampleCommand()

        assertThat(command.statusName).isNull()
        assertThat(command.fixVersionNames).isEmpty()
        assertThat(command.affectsVersionNames).isEmpty()
    }

    @Test
    fun `IssueImportCommand 는 필수 3개 필드만 지정해도 기존 필드 기본값이 무회귀 유지된다`() {
        val command = sampleCommand()

        assertThat(command.typeName).isNull()
        assertThat(command.description).isNull()
        assertThat(command.priority).isNull()
        assertThat(command.reporterEmail).isNull()
        assertThat(command.assigneeEmail).isNull()
        assertThat(command.labels).isEmpty()
        assertThat(command.componentNames).isEmpty()
        assertThat(command.dryRun).isFalse()
    }

    @Test
    fun `IssueImportCommand 는 필수 3개 필드만 지정해도 comments worklogs 기본값이 emptyList 다`() {
        val command = sampleCommand()

        assertThat(command.comments).isEmpty()
        assertThat(command.worklogs).isEmpty()
    }

    @Test
    fun `ImportComment 는 body 만 필수이고 authorEmail createdAt 은 기본값 null 이다`() {
        val comment = ImportComment(body = "댓글 본문")

        assertThat(comment.body).isEqualTo("댓글 본문")
        assertThat(comment.authorEmail).isNull()
        assertThat(comment.createdAt).isNull()
    }

    @Test
    fun `ImportComment 는 authorEmail createdAt 을 명시적으로 지정할 수 있다`() {
        val createdAt = Instant.parse("2026-07-01T00:00:00Z")

        val comment = ImportComment(body = "댓글 본문", authorEmail = "reporter@example.com", createdAt = createdAt)

        assertThat(comment.authorEmail).isEqualTo("reporter@example.com")
        assertThat(comment.createdAt).isEqualTo(createdAt)
    }

    @Test
    fun `ImportWorklog 는 timeSpentSeconds 만 필수이고 나머지는 기본값 null 이다`() {
        val worklog = ImportWorklog(timeSpentSeconds = 3600)

        assertThat(worklog.timeSpentSeconds).isEqualTo(3600)
        assertThat(worklog.startedAt).isNull()
        assertThat(worklog.authorEmail).isNull()
        assertThat(worklog.comment).isNull()
    }

    @Test
    fun `ImportWorklog 는 startedAt authorEmail comment 를 명시적으로 지정할 수 있다`() {
        val startedAt = Instant.parse("2026-07-01T09:00:00Z")

        val worklog =
            ImportWorklog(
                timeSpentSeconds = 1800,
                startedAt = startedAt,
                authorEmail = "worker@example.com",
                comment = "작업 내용",
            )

        assertThat(worklog.startedAt).isEqualTo(startedAt)
        assertThat(worklog.authorEmail).isEqualTo("worker@example.com")
        assertThat(worklog.comment).isEqualTo("작업 내용")
    }

    @Test
    fun `IssueImportCommand 는 comments worklogs 를 명시적으로 지정할 수 있다`() {
        val comment = ImportComment(body = "댓글")
        val worklog = ImportWorklog(timeSpentSeconds = 60)

        val command = sampleCommand().copy(comments = listOf(comment), worklogs = listOf(worklog))

        assertThat(command.comments).containsExactly(comment)
        assertThat(command.worklogs).containsExactly(worklog)
    }

    @Test
    fun `IssueImportCommand 는 필수 3개 필드만 지정해도 sourceKey attachments changelog 기본값을 갖는다`() {
        val command = sampleCommand()

        assertThat(command.sourceKey).isNull()
        assertThat(command.attachments).isEmpty()
        assertThat(command.changelog).isEmpty()
    }

    @Test
    fun `IssueImportCommand 는 sourceKey attachments changelog 를 명시적으로 지정할 수 있다`() {
        val attachment = ImportAttachment(filename = "screenshot.png")
        val changeGroup = ImportChangeGroup(items = listOf(ImportChangeItem(field = "status")))

        val command =
            sampleCommand().copy(
                sourceKey = "JIRA-1",
                attachments = listOf(attachment),
                changelog = listOf(changeGroup),
            )

        assertThat(command.sourceKey).isEqualTo("JIRA-1")
        assertThat(command.attachments).containsExactly(attachment)
        assertThat(command.changelog).containsExactly(changeGroup)
    }

    @Test
    fun `ImportAttachment 는 filename 만 필수이고 나머지는 기본값 null 이다`() {
        val attachment = ImportAttachment(filename = "screenshot.png")

        assertThat(attachment.filename).isEqualTo("screenshot.png")
        assertThat(attachment.authorEmail).isNull()
        assertThat(attachment.createdAt).isNull()
        assertThat(attachment.mimeType).isNull()
        assertThat(attachment.sizeBytes).isNull()
    }

    @Test
    fun `ImportAttachment 는 필드를 명시적으로 지정할 수 있다`() {
        val createdAt = Instant.parse("2026-07-01T00:00:00Z")

        val attachment =
            ImportAttachment(
                filename = "screenshot.png",
                authorEmail = "reporter@example.com",
                createdAt = createdAt,
                mimeType = "image/png",
                sizeBytes = 1024L,
            )

        assertThat(attachment.authorEmail).isEqualTo("reporter@example.com")
        assertThat(attachment.createdAt).isEqualTo(createdAt)
        assertThat(attachment.mimeType).isEqualTo("image/png")
        assertThat(attachment.sizeBytes).isEqualTo(1024L)
    }

    @Test
    fun `ImportChangeGroup 은 모든 필드가 기본값을 갖는다`() {
        val group = ImportChangeGroup()

        assertThat(group.authorEmail).isNull()
        assertThat(group.occurredAt).isNull()
        assertThat(group.items).isEmpty()
    }

    @Test
    fun `ImportChangeGroup 은 authorEmail occurredAt items 를 명시적으로 지정할 수 있다`() {
        val occurredAt = Instant.parse("2026-07-01T09:00:00Z")
        val item = ImportChangeItem(field = "status", fromValue = "To Do", toValue = "In Progress")

        val group =
            ImportChangeGroup(
                authorEmail = "worker@example.com",
                occurredAt = occurredAt,
                items = listOf(item),
            )

        assertThat(group.authorEmail).isEqualTo("worker@example.com")
        assertThat(group.occurredAt).isEqualTo(occurredAt)
        assertThat(group.items).containsExactly(item)
    }

    @Test
    fun `ImportChangeItem 은 field 만 필수이고 fromValue toValue 는 기본값 null 이다`() {
        val item = ImportChangeItem(field = "priority")

        assertThat(item.field).isEqualTo("priority")
        assertThat(item.fromValue).isNull()
        assertThat(item.toValue).isNull()
    }

    @Test
    fun `ImportChangeItem 은 fromValue toValue 를 명시적으로 지정할 수 있다`() {
        val item = ImportChangeItem(field = "priority", fromValue = "Low", toValue = "High")

        assertThat(item.fromValue).isEqualTo("Low")
        assertThat(item.toValue).isEqualTo("High")
    }

    @Test
    fun `ImportAttachmentSource open 은 fun interface 로 InputStream 을 반환할 수 있다`() {
        val bytes = "content".toByteArray()
        val source = ImportAttachmentSource { _, _ -> ByteArrayInputStream(bytes) }

        val stream = source.open("screenshot.png", "JIRA-1")

        assertThat(stream).isNotNull
        assertThat(stream!!.readBytes()).isEqualTo(bytes)
    }

    @Test
    fun `ImportAttachmentSource open 은 미매칭 시 null 을 반환할 수 있다`() {
        val source = ImportAttachmentSource { _, _ -> null }

        val stream = source.open("missing.png", null)

        assertThat(stream).isNull()
    }

    @Test
    fun `importIssue 2-arg 오버로드 default 구현은 fail-closed 실패 결과를 반환한다`() {
        val port = object : IssueImportPort {}

        val result = port.importIssue(sampleCommand(), null)

        assertThat(result).isInstanceOf(IssueImportResult.Failure::class.java)
        val failure = result as IssueImportResult.Failure
        assertThat(failure.reasonCode).isEqualTo(IssueImportResult.ADAPTER_UNAVAILABLE)
    }

    @Test
    fun `importIssue 1-arg 호출은 어댑터가 override 한 2-arg 구현으로 위임된다`() {
        val port =
            object : IssueImportPort {
                override fun importIssue(
                    cmd: IssueImportCommand,
                    attachments: ImportAttachmentSource?,
                ): IssueImportResult {
                    return IssueImportResult.success("PROJ-1")
                }
            }

        val result = port.importIssue(sampleCommand())

        assertThat(result).isInstanceOf(IssueImportResult.Success::class.java)
        assertThat((result as IssueImportResult.Success).issueKey).isEqualTo("PROJ-1")
    }
}
