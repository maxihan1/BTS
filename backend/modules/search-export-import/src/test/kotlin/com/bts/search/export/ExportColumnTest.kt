// ExportColumn 도메인 타입 단위 테스트 — 9컬럼 1:1 매핑 / 헤더 라벨 / extract / parse 결정성

package com.bts.search.export

import com.bts.search.web.SearchValidationException
import com.bts.shared.search.IssueSearchHit
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Instant
import java.util.UUID

/**
 * [ExportColumn] 단위 테스트.
 *
 * 검증 항목.
 * - enum 값이 [IssueSearchHit] 9필드와 정확히 1:1 대응
 * - 영문 헤더 라벨(Key/Summary/Type/Status/Assignee ID/Priority/Priority Name/Project/Updated At)
 * - [ExportColumn.extract] — assigneeId null→"", updatedAt→ISO-8601 UTC, priority→문자열
 * - [ExportColumn.parse] null/빈 → 전체 9컬럼(표준 순서), 부분집합 → 요청 순서 무관 enum 선언 순서,
 *   미지원 필드명 → [SearchValidationException]
 */
class ExportColumnTest {

    private val fixedInstant = Instant.parse("2024-03-15T10:30:00Z")
    private val fixedUuid = UUID.fromString("00000000-0000-0000-0000-000000000001")

    private fun sampleHit(assigneeId: UUID? = fixedUuid): IssueSearchHit =
        IssueSearchHit(
            key = "PROJ-1",
            summary = "Test issue",
            typeKey = "bug",
            currentStateKey = "open",
            assigneeId = assigneeId,
            priority = 2,
            priorityName = "High",
            projectKey = "PROJ",
            updatedAt = fixedInstant,
        )

    // ── enum 구조 ──────────────────────────────────────────────────────────────

    @Test
    fun `enum 값이 정확히 9개이다`() {
        assertThat(ExportColumn.entries).hasSize(9)
    }

    @Test
    fun `영문 헤더 라벨이 표준 선언 순서와 일치한다`() {
        val expected =
            listOf(
                "Key",
                "Summary",
                "Type",
                "Status",
                "Assignee ID",
                "Priority",
                "Priority Name",
                "Project",
                "Updated At",
            )
        assertThat(ExportColumn.entries.map { it.headerLabel }).isEqualTo(expected)
    }

    // ── extract ───────────────────────────────────────────────────────────────

    @Test
    fun `extract가 IssueSearchHit 9필드를 올바른 문자열로 추출한다`() {
        val hit = sampleHit()
        assertThat(ExportColumn.KEY.extract(hit)).isEqualTo("PROJ-1")
        assertThat(ExportColumn.SUMMARY.extract(hit)).isEqualTo("Test issue")
        assertThat(ExportColumn.TYPE.extract(hit)).isEqualTo("bug")
        assertThat(ExportColumn.STATUS.extract(hit)).isEqualTo("open")
        assertThat(ExportColumn.ASSIGNEE_ID.extract(hit)).isEqualTo(fixedUuid.toString())
        assertThat(ExportColumn.PRIORITY.extract(hit)).isEqualTo("2")
        assertThat(ExportColumn.PRIORITY_NAME.extract(hit)).isEqualTo("High")
        assertThat(ExportColumn.PROJECT.extract(hit)).isEqualTo("PROJ")
        assertThat(ExportColumn.UPDATED_AT.extract(hit)).isEqualTo("2024-03-15T10:30:00Z")
    }

    @Test
    fun `assigneeId가 null이면 빈 문자열을 반환한다`() {
        val hit = sampleHit(assigneeId = null)
        assertThat(ExportColumn.ASSIGNEE_ID.extract(hit)).isEqualTo("")
    }

    @Test
    fun `updatedAt은 ISO-8601 UTC 문자열로 반환한다`() {
        val instant = Instant.parse("2024-12-31T23:59:59Z")
        val hit = sampleHit().copy(updatedAt = instant)
        assertThat(ExportColumn.UPDATED_AT.extract(hit)).isEqualTo("2024-12-31T23:59:59Z")
    }

    @Test
    fun `priority는 정수를 문자열로 변환한다`() {
        val hit = sampleHit().copy(priority = 5)
        assertThat(ExportColumn.PRIORITY.extract(hit)).isEqualTo("5")
    }

    // ── parse — null/빈 → 전체 ────────────────────────────────────────────────

    @Test
    fun `parse에 null을 전달하면 전체 9컬럼을 enum 선언 순서로 반환한다`() {
        val result = ExportColumn.parse(null)
        assertThat(result).isEqualTo(ExportColumn.entries.toList())
    }

    @Test
    fun `parse에 빈 목록을 전달하면 전체 9컬럼을 enum 선언 순서로 반환한다`() {
        val result = ExportColumn.parse(emptyList())
        assertThat(result).isEqualTo(ExportColumn.entries.toList())
    }

    // ── parse — 부분집합 → 표준 순서 ─────────────────────────────────────────

    @Test
    fun `parse가 부분집합을 요청 순서 무관하게 enum 선언 순서로 정렬 반환한다`() {
        // 요청 순서: PRIORITY(5), KEY(0), STATUS(3) → 표준 정렬: KEY(0), STATUS(3), PRIORITY(5)
        val result = ExportColumn.parse(listOf("PRIORITY", "KEY", "STATUS"))
        assertThat(result).containsExactly(ExportColumn.KEY, ExportColumn.STATUS, ExportColumn.PRIORITY)
    }

    @ParameterizedTest
    @ValueSource(strings = ["KEY", "SUMMARY", "TYPE", "STATUS", "ASSIGNEE_ID", "PRIORITY", "PRIORITY_NAME", "PROJECT", "UPDATED_AT"])
    fun `parse가 단일 유효 컬럼명을 처리한다`(name: String) {
        val result = ExportColumn.parse(listOf(name))
        assertThat(result).hasSize(1)
        assertThat(result.first().name).isEqualTo(name)
    }

    // ── parse — 미지원 필드명 → SearchValidationException ─────────────────────

    @Test
    fun `parse에 미지원 필드명을 전달하면 SearchValidationException을 던진다`() {
        assertThatThrownBy { ExportColumn.parse(listOf("KEY", "UNKNOWN_FIELD")) }
            .isInstanceOf(SearchValidationException::class.java)
            .hasMessageContaining("UNKNOWN_FIELD")
    }

    @Test
    fun `parse에 소문자 필드명을 전달하면 SearchValidationException을 던진다`() {
        // enum 이름은 대문자 기준 — 소문자는 미지원
        assertThatThrownBy { ExportColumn.parse(listOf("key")) }
            .isInstanceOf(SearchValidationException::class.java)
    }
}
