// IssueImportPort default 구현 fail-closed 계약 + IssueImportResult factory 단위 테스트
package com.bts.shared.issue

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
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
}
