// 보드 카드 밀도 3필드(유형·라벨·추정)가 실 DB 값 그대로 카드까지 도달하는지 검증 — FR-UX-14 B2

package com.bts.issue.adapter.outbound.board

import com.bts.issue.domain.ActorId
import com.bts.issue.domain.Issue
import com.bts.issue.domain.IssueId
import com.bts.issue.domain.IssueKey
import com.bts.issue.repository.IssueTestcontainersBase
import com.bts.shared.issue.IssueTypeId
import com.bts.shared.permission.IssueSecurityAccess
import com.bts.shared.permission.IssueSecurityDirectory
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.util.UUID

/**
 * 보드 카드 밀도 3필드 매핑 검증 (FR-UX-14 B2).
 *
 * ### 왜 별도 파일인가
 *
 * [BoardIssueLookupAdapterTest] 는 **가시성**(보안 등급 필터)을 보고, 이 클래스는 **필드 매핑**을 본다.
 * 성격이 다르고, 같은 파일에 두면 detekt `LargeClass` 임계를 넘는다.
 * [BoardCardQueryCountTest] 를 별도 파일로 둔 것과 같은 이유다.
 *
 * ### 대조군 없이는 아무것도 못 잰다
 *
 * 축마다 **서로 다른 값 2건 + 빈 값 대조군 1건**을 넣고 **키 단위로** 대조한다.
 * 값이 전부 같으면 매핑이 뒤바뀌거나 조인이 틀린 행을 물어와도 초록이다 (스펙 §9.3 GAP-1).
 * 증인은 팩토리 헬퍼가 만든 VO 가 아니라 여기 실제로 넣은 행이다 (GAP-2).
 *
 * ### 이 가드가 공허하지 않다는 확인
 *
 * 어댑터의 `labels` 매핑을 `emptyList()` 로 망가뜨려 [D1 - 보드 카드는 이슈별 유형 라벨 추정을 각자 정확히 갖는다]
 * 가 FAILED 되는 것을 확인했다(2026-08-07). **도달 불가능한 상태를 지키는 테스트는
 * 초록인 채 아무것도 재지 않는다.**
 */
class BoardCardDensityTest : IssueTestcontainersBase() {
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

    private fun adapter(): BoardIssueLookupAdapter =
        BoardIssueLookupAdapter(
            repository,
            StubSecurityDirectory(unrestricted()),
        )

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

    /**
     * 밀도 3필드를 실제 값으로 채운 이슈를 넣는다.
     *
     * `originalEstimateSeconds` 는 [Issue.create] 인자에 없으므로 copy 로 채운다.
     */
    private fun insertDensityIssue(
        seq: Long,
        typeKey: String,
        labels: List<String>,
        estimate: Int?,
    ): Issue =
        repository.insert(
            Issue.create(
                id = IssueId(UUID.randomUUID()),
                key = IssueKey.of("TPRJ", seq),
                projectId = testProjectId,
                typeId = resolveTypeId(typeKey),
                summary = "density card $seq",
                reporterId = ActorId(UUID.randomUUID()),
                currentStateKey = "open",
                priority = 3,
                labels = labels,
            ).copy(originalEstimateSeconds = estimate),
        )

    @Test
    fun `D1 - 보드 카드는 이슈별 유형 라벨 추정을 각자 정확히 갖는다`() {
        val viewer = UUID.randomUUID()
        insertDensityIssue(seq = 1, typeKey = "bug", labels = listOf("urgent", "api"), estimate = 3600)
        insertDensityIssue(seq = 2, typeKey = "story", labels = listOf("docs"), estimate = 7200)
        insertDensityIssue(seq = 3, typeKey = "task", labels = emptyList(), estimate = null)

        val byKey =
            adapter()
                .listVisibleIssuesByProject("TPRJ", viewer)
                .issues
                .associateBy { it.key }

        // 유형 — 단일 타입만 검사하면 조인이 항상 첫 행을 물어와도 통과한다.
        assertThat(byKey.getValue("TPRJ-1").typeKey).isEqualTo("bug")
        assertThat(byKey.getValue("TPRJ-2").typeKey).isEqualTo("story")
        assertThat(byKey.getValue("TPRJ-3").typeKey).isEqualTo("task")

        // 라벨 — 서로 다른 2건 + 빈 배열 대조군. 빈 값은 null 이 아니라 빈 리스트여야 한다.
        assertThat(byKey.getValue("TPRJ-1").labels).containsExactly("urgent", "api")
        assertThat(byKey.getValue("TPRJ-2").labels).containsExactly("docs")
        assertThat(byKey.getValue("TPRJ-3").labels).isEmpty()

        // 추정 — 서로 다른 2건 + null 대조군.
        assertThat(byKey.getValue("TPRJ-1").originalEstimateSeconds).isEqualTo(3600)
        assertThat(byKey.getValue("TPRJ-2").originalEstimateSeconds).isEqualTo(7200)
        assertThat(byKey.getValue("TPRJ-3").originalEstimateSeconds).isNull()
    }

    @Test
    fun `D2 - type JOIN 이 붙어도 카드 수가 이슈 수와 같고 중복이 없다`() {
        val viewer = UUID.randomUUID()
        insertDensityIssue(seq = 1, typeKey = "bug", labels = emptyList(), estimate = null)
        insertDensityIssue(seq = 2, typeKey = "story", labels = emptyList(), estimate = null)
        insertDensityIssue(seq = 3, typeKey = "epic", labels = emptyList(), estimate = null)

        val page = adapter().listVisibleIssuesByProject("TPRJ", viewer)

        // ISSUE_TYPES 조인이 N:1 이 아니면 행이 불어나고, 그러면 LIMIT+1 truncated 판정이 깨진다.
        assertThat(page.issues).hasSize(3)
        assertThat(page.issues.map { it.key }).doesNotHaveDuplicates()
        assertThat(page.truncated).isFalse()
    }
}
