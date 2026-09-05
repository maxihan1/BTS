// 보드 카드 조회가 FR-IS-10 커스텀 필드 값을 카드까지 나르는지 검증 — 보드·백로그 두 경로

package com.bts.issue.repository

import com.bts.issue.adapter.outbound.board.BoardIssueLookupAdapter
import com.bts.issue.domain.ActorId
import com.bts.issue.domain.Issue
import com.bts.issue.domain.IssueId
import com.bts.issue.domain.IssueKey
import com.bts.shared.board.BoardCardFilter
import com.bts.shared.board.BoardIssueView
import com.bts.shared.issue.IssueTypeId
import com.bts.shared.permission.IssueSecurityAccess
import com.bts.shared.permission.IssueSecurityDirectory
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.util.UUID

/**
 * 보드 카드 커스텀 필드 매핑 검증 (FR-IS-10 × FR-BD-01).
 *
 * ### 무엇을 재는가
 *
 * `issues.custom_fields` JSONB 값이 **카드까지** 도달하는지 본다. 경로는
 * `IssueRepository.listVisibleForBoard`(조회) → `BoardIssueEntry`(중간 DTO) →
 * `BoardIssueLookupAdapter.toBoardIssueView()`(포트 DTO) 세 곳이며,
 * **한 곳만 빠져도 값이 화면 쪽에 닿지 않는다**. C3 이 앞 두 구간을, C1/C2 가 끝 구간을 각각 잰다.
 *
 * ### 대조군 없이는 아무것도 못 잰다
 *
 * **서로 다른 값 2건 + 빈 맵 대조군 1건**을 넣고 **키 단위로** 대조한다 ([BoardCardDensityTest] 와 동형).
 * 값이 전부 같으면 카드가 뒤바뀌어도 초록이다.
 *
 * ### 보드와 백로그를 따로 재는 이유
 *
 * 백로그(`BacklogApplicationService`)는 2-인자 오버로드를, 보드(`BoardApplicationService`)는
 * 3-인자 오버로드를 호출한다. 한쪽만 재면 다른 쪽 매핑 누락이 초록으로 통과한다.
 */
class BoardIssueLookupCustomFieldsTest : IssueTestcontainersBase() {
    /** accessibleLevels 를 고정 반환하는 stub — 이 테스트는 가시성이 아니라 매핑을 본다. */
    private class StubSecurityDirectory(
        private val next: IssueSecurityAccess,
    ) : IssueSecurityDirectory {
        override fun levelBelongsToProjectScheme(
            levelId: UUID,
            projectKey: String,
        ): Boolean = true

        override fun accessibleLevels(
            actorId: UUID,
            projectKey: String,
        ): IssueSecurityAccess = next
    }

    private fun unrestricted() =
        IssueSecurityAccess(
            unrestricted = true,
            staticLevelIds = emptySet(),
            reporterLevelIds = emptySet(),
            assigneeLevelIds = emptySet(),
        )

    private fun adapter(): BoardIssueLookupAdapter = BoardIssueLookupAdapter(repository, StubSecurityDirectory(unrestricted()))

    /** `issue_types.key` 로 타입 id 를 해석한다. V003 시드의 표준 5종을 그대로 쓴다. */
    private fun resolveTypeId(typeKey: String): IssueTypeId =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement("SELECT id FROM issue_types WHERE key = ? AND deleted_at IS NULL LIMIT 1")
                .use { stmt ->
                    stmt.setString(1, typeKey)
                    stmt.executeQuery().use { rs ->
                        check(rs.next()) { "issue_types 에 '$typeKey' 가 없습니다." }
                        IssueTypeId(rs.getLong(1))
                    }
                }
        }

    /** 커스텀 필드 값을 담은 이슈 1건을 넣는다. */
    private fun insertIssue(
        seq: Long,
        customFields: Map<String, Any?>,
    ): Issue =
        repository.insert(
            Issue.create(
                id = IssueId(UUID.randomUUID()),
                key = IssueKey.of("TPRJ", seq),
                projectId = testProjectId,
                typeId = resolveTypeId("task"),
                summary = "custom field card $seq",
                reporterId = ActorId(UUID.randomUUID()),
                currentStateKey = "open",
                priority = 3,
                customFields = customFields,
            ),
        )

    /** 값이 서로 다른 2건 + 빈 맵 대조군 1건을 넣는다. */
    private fun seedContrastingIssues() {
        insertIssue(seq = 1, customFields = mapOf("severity" to "high", "storyPoints" to 5))
        insertIssue(seq = 2, customFields = mapOf("severity" to "low"))
        insertIssue(seq = 3, customFields = emptyMap())
    }

    /** 대조군 3건에 대해 카드가 각자의 커스텀 필드를 정확히 갖는지 단언한다. */
    private fun assertContrastingCards(byKey: Map<String, BoardIssueView>) {
        assertThat(byKey.getValue("TPRJ-1").customFields)
            .describedAs("여러 키를 가진 이슈는 맵 전체가 그대로 실려야 한다")
            .containsExactlyInAnyOrderEntriesOf(mapOf("severity" to "high", "storyPoints" to 5))
        assertThat(byKey.getValue("TPRJ-2").customFields)
            .describedAs("이슈마다 서로 다른 값을 가진다 — 카드가 뒤바뀌면 여기서 깨진다")
            .containsExactlyInAnyOrderEntriesOf(mapOf<String, Any?>("severity" to "low"))
        assertThat(byKey.getValue("TPRJ-3").customFields)
            .describedAs("커스텀 필드가 없으면 null 이 아니라 빈 맵이다")
            .isEmpty()
    }

    @Test
    fun `C1 - 보드 경로 카드는 이슈별 커스텀 필드를 각자 정확히 갖는다`() {
        val viewer = UUID.randomUUID()
        seedContrastingIssues()

        val byKey =
            adapter()
                .listVisibleIssuesByProject("TPRJ", viewer, BoardCardFilter.EMPTY)
                .issues
                .associateBy { it.key }

        assertContrastingCards(byKey)
    }

    @Test
    fun `C2 - 백로그 경로 카드도 이슈별 커스텀 필드를 각자 정확히 갖는다`() {
        val viewer = UUID.randomUUID()
        seedContrastingIssues()

        val byKey =
            adapter()
                .listVisibleIssuesByProject("TPRJ", viewer)
                .issues
                .associateBy { it.key }

        assertContrastingCards(byKey)
    }

    @Test
    fun `C3 - 리포지터리 조회 결과에는 커스텀 필드가 이미 실려 있다`() {
        val viewer = UUID.randomUUID()
        seedContrastingIssues()

        val byKey =
            repository
                .listVisibleForBoard("TPRJ", viewer, unrestricted())
                .entries
                .associateBy { it.issue.key.value }

        // 앞 두 구간(조회 → 중간 DTO)이 이미 값을 나른다면, 남은 결손은 어댑터 매핑 한 곳이다.
        assertThat(byKey.getValue("TPRJ-1").issue.customFields)
            .containsExactlyInAnyOrderEntriesOf(mapOf("severity" to "high", "storyPoints" to 5))
        assertThat(byKey.getValue("TPRJ-2").issue.customFields)
            .containsExactlyInAnyOrderEntriesOf(mapOf<String, Any?>("severity" to "low"))
        assertThat(byKey.getValue("TPRJ-3").issue.customFields).isEmpty()
    }
}
