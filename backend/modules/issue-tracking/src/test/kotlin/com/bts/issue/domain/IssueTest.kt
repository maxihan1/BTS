// Issue Aggregate Root 단위 테스트 — factory, invariants, version, deletedAt

package com.bts.issue.domain

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * [Issue] Aggregate Root 단위 테스트.
 *
 * Spring 컨텍스트 없이 순수 단위 테스트로 실행한다.
 * 테스트 케이스.
 * - create_returns_issue — 유효한 인자로 Issue 를 생성하면 모든 필드가 기대 값과 일치한다.
 * - create_sets_version_to_one — 생성 직후 version 은 항상 1 이다.
 * - create_sets_deletedAt_null — 생성 직후 deletedAt 은 항상 null 이다.
 * - reject_blank_summary — summary 가 빈 문자열이면 IllegalArgumentException 을 던진다.
 * - reject_whitespace_only_summary — summary 가 공백만이면 IllegalArgumentException 을 던진다.
 * - reject_summary_over_255 — summary 가 256자 이상이면 IllegalArgumentException 을 던진다.
 * - accept_summary_exactly_255 — summary 가 정확히 255자이면 정상 생성한다.
 */
class IssueTest {
    private val validId = IssueId(UUID.randomUUID())
    private val validKey = IssueKey.of("PROJ", 1L)
    private val validProjectId: UUID = UUID.randomUUID()
    private val validSummary = "Fix login bug"
    private val validReporterId = ActorId(UUID.randomUUID())
    private val validStateKey = "OPEN"

    @Test
    fun `create_returns_issue — 유효한 인자로 Issue 를 생성하면 모든 필드가 기대 값과 일치한다`() {
        val issue =
            Issue.create(
                id = validId,
                key = validKey,
                projectId = validProjectId,
                summary = validSummary,
                reporterId = validReporterId,
                currentStateKey = validStateKey,
            )

        assertThat(issue.id).isEqualTo(validId)
        assertThat(issue.key).isEqualTo(validKey)
        assertThat(issue.projectId).isEqualTo(validProjectId)
        assertThat(issue.summary).isEqualTo(validSummary)
        assertThat(issue.reporterId).isEqualTo(validReporterId)
        assertThat(issue.currentStateKey).isEqualTo(validStateKey)
    }

    @Test
    fun `create_sets_version_to_one — 생성 직후 version 은 항상 1 이다`() {
        val issue =
            Issue.create(
                id = validId,
                key = validKey,
                projectId = validProjectId,
                summary = validSummary,
                reporterId = validReporterId,
                currentStateKey = validStateKey,
            )

        assertThat(issue.version).isEqualTo(1L)
    }

    @Test
    fun `create_sets_deletedAt_null — 생성 직후 deletedAt 은 항상 null 이다`() {
        val issue =
            Issue.create(
                id = validId,
                key = validKey,
                projectId = validProjectId,
                summary = validSummary,
                reporterId = validReporterId,
                currentStateKey = validStateKey,
            )

        assertThat(issue.deletedAt).isNull()
    }

    @Test
    fun `reject_blank_summary — summary 가 빈 문자열이면 IllegalArgumentException 을 던진다`() {
        assertThatThrownBy {
            Issue.create(
                id = validId,
                key = validKey,
                projectId = validProjectId,
                summary = "",
                reporterId = validReporterId,
                currentStateKey = validStateKey,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `reject_whitespace_only_summary — summary 가 공백만이면 IllegalArgumentException 을 던진다`() {
        assertThatThrownBy {
            Issue.create(
                id = validId,
                key = validKey,
                projectId = validProjectId,
                summary = "   ",
                reporterId = validReporterId,
                currentStateKey = validStateKey,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `reject_summary_over_255 — summary 가 256자 이상이면 IllegalArgumentException 을 던진다`() {
        val tooLong = "a".repeat(256)

        assertThatThrownBy {
            Issue.create(
                id = validId,
                key = validKey,
                projectId = validProjectId,
                summary = tooLong,
                reporterId = validReporterId,
                currentStateKey = validStateKey,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `accept_summary_exactly_255 — summary 가 정확히 255자이면 정상 생성한다`() {
        val exactly255 = "a".repeat(255)

        val issue =
            Issue.create(
                id = validId,
                key = validKey,
                projectId = validProjectId,
                summary = exactly255,
                reporterId = validReporterId,
                currentStateKey = validStateKey,
            )

        assertThat(issue.summary).hasSize(255)
    }
}
