// 보드 카드 커스텀 필드가 cross-BC 포트를 넘기 전에 열람 권한으로 마스킹되는지 검증 — FR-PM-07 × FR-BD-01

package com.bts.issue.adapter.outbound.board

import com.bts.issue.domain.ActorId
import com.bts.issue.domain.Issue
import com.bts.issue.domain.IssueId
import com.bts.issue.domain.IssueKey
import com.bts.issue.repository.IssueTestcontainersBase
import com.bts.shared.board.BoardCardFilter
import com.bts.shared.board.BoardIssuePage
import com.bts.shared.board.BoardIssueView
import com.bts.shared.issue.IssueTypeId
import com.bts.shared.permission.FieldKind
import com.bts.shared.permission.FieldPermissionResolver
import com.bts.shared.permission.FieldRef
import com.bts.shared.permission.IssueSecurityAccess
import com.bts.shared.permission.IssueSecurityDirectory
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * 보드 카드 커스텀 필드 열람 권한 마스킹 검증 (FR-PM-07 × FR-BD-01, 부채 177 Task 25).
 *
 * ### 무엇이 결함이었나
 *
 * issue-tracking 자기 REST 경로는 `IssueResponse.maskInvisible` + `maskFieldsForPage` 로
 * 열람 권한 없는 커스텀 필드 키를 제거한다. **cross-BC 포트 경로에는 그 게이트가 없었다** —
 * `BoardIssueView.customFields` 가 원시 값으로 채워지므로, 보드 응답이 이 값을 미러하는 순간
 * 열람 권한 없는 커스텀 필드가 카드에 실려 나간다. 마스킹 주체는 **어댑터**다(스펙 C-6) —
 * 포트 밖으로는 이미 안전한 값만 나가고 소비측(agile-planning)은 다시 거르지 않는다.
 *
 * ### 대조군을 한 쌍으로 둔다
 *
 * [M1] 은 **권한 없는 뷰어**가 제한 키를 못 보는 것을, [M2] 는 **권한 있는 뷰어**가 같은 키를
 * **보는 것**을 잰다. 뒤엣것이 없으면 「커스텀 필드를 전부 지우는」 구현도 초록이다.
 * 두 판정은 같은 행·같은 키를 쓰고 **뷰어만 다르다** — 차이의 원인이 권한 하나로 좁혀진다.
 *
 * ### N+1 (M4/M5)
 *
 * 권한 판정은 **페이지당 1회**여야 한다. 카드마다 부르면 보드 한 번에 수백 회 판정이 된다.
 * [BoardIssueLookupCustomFieldsTest] C4/C5 가 SQL 문 수를, 여기 [M4]/[M5] 가 판정 호출 수를
 * 각각 고정한다 — 두 축이 서로를 대신하지 않는다(판정을 SQL 없이 도는 구현도 N+1 일 수 있다).
 */
class BoardIssueLookupMaskingTest : IssueTestcontainersBase() {
    private companion object {
        /** 열람 권한이 제한된 커스텀 필드 키 — 권한 없는 뷰어에게서 사라져야 한다. */
        const val RESTRICTED_KEY = "salary"

        /** 제한 규칙이 없는 커스텀 필드 키 — 누구에게나 남아야 한다(전부 지우는 구현 검출용). */
        const val ALLOWED_KEY = "severity"
    }

    /** accessibleLevels 를 고정 반환하는 stub — 이 테스트는 보안 등급이 아니라 필드 권한을 본다. */
    private class StubSecurityDirectory : IssueSecurityDirectory {
        override fun levelBelongsToProjectScheme(
            levelId: UUID,
            projectKey: String,
        ): Boolean = true

        override fun accessibleLevels(
            actorId: UUID,
            projectKey: String,
        ): IssueSecurityAccess =
            IssueSecurityAccess(
                unrestricted = true,
                staticLevelIds = emptySet(),
                reporterLevelIds = emptySet(),
                assigneeLevelIds = emptySet(),
            )
    }

    /**
     * [blindViewer] 에게만 [RESTRICTED_KEY] 를 감추는 stub — 호출 인자와 횟수를 기록한다.
     *
     * 다른 뷰어에게는 candidates 를 그대로 돌려준다(규칙 없는 키는 항상 포함 — 포트 계약 EC1).
     */
    private class RecordingFieldPermissionResolver(
        private val blindViewer: UUID,
    ) : FieldPermissionResolver {
        val visibleCalls = AtomicInteger(0)
        val seenProjectIds = mutableListOf<UUID>()
        val seenCandidates = mutableListOf<Set<FieldRef>>()

        override fun visibleFields(
            actorId: UUID,
            projectId: UUID,
            candidates: Set<FieldRef>,
        ): Set<FieldRef> {
            visibleCalls.incrementAndGet()
            seenProjectIds += projectId
            seenCandidates += candidates
            return if (actorId == blindViewer) {
                candidates - FieldRef(FieldKind.CUSTOM, RESTRICTED_KEY)
            } else {
                candidates
            }
        }

        override fun editableFields(
            actorId: UUID,
            projectId: UUID,
            candidates: Set<FieldRef>,
        ): Set<FieldRef> = candidates
    }

    private fun adapter(resolver: FieldPermissionResolver): BoardIssueLookupAdapter =
        BoardIssueLookupAdapter(repository, StubSecurityDirectory(), resolver)

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

    /** 제한 키와 허용 키를 **함께** 가진 이슈 1건을 넣는다 — 한 행에서 두 축이 갈린다. */
    private fun insertIssue(seq: Long): Issue =
        repository.insert(
            Issue.create(
                id = IssueId(UUID.randomUUID()),
                key = IssueKey.of("TPRJ", seq),
                projectId = testProjectId,
                typeId = resolveTypeId("task"),
                summary = "masking card $seq",
                reporterId = ActorId(UUID.randomUUID()),
                currentStateKey = "open",
                priority = 3,
                customFields = mapOf(RESTRICTED_KEY to 9000, ALLOWED_KEY to "high"),
            ),
        )

    private fun insertIssues(count: Int) = repeat(count) { insertIssue(seq = 1L + it) }

    private fun cardsOf(page: BoardIssuePage): Map<String, BoardIssueView> = page.issues.associateBy { it.key }

    @Test
    fun `M1 - 열람 권한 없는 뷰어의 보드 카드에서 제한 커스텀 필드가 사라진다`() {
        val blindViewer = UUID.randomUUID()
        val resolver = RecordingFieldPermissionResolver(blindViewer)
        insertIssue(seq = 1)

        val cards = cardsOf(adapter(resolver).listVisibleIssuesByProject("TPRJ", blindViewer, BoardCardFilter.EMPTY))

        assertThat(cards.getValue("TPRJ-1").customFields)
            .describedAs("열람 권한 없는 커스텀 필드는 포트 밖으로 나가면 안 된다 — 소비측은 다시 거르지 않는다")
            .doesNotContainKey(RESTRICTED_KEY)
        assertThat(cards.getValue("TPRJ-1").customFields)
            .describedAs("같은 카드의 제한 없는 키는 값까지 그대로 남는다 — 전부 지우는 구현 검출")
            .containsEntry(ALLOWED_KEY, "high")
        assertThat(resolver.seenProjectIds)
            .describedAs("판정은 카드가 실제로 속한 프로젝트 스코프로 물어야 한다")
            .containsExactly(testProjectId)
    }

    @Test
    fun `M2 - 열람 권한 있는 뷰어는 같은 커스텀 필드를 그대로 본다`() {
        val blindViewer = UUID.randomUUID()
        val permittedViewer = UUID.randomUUID()
        val resolver = RecordingFieldPermissionResolver(blindViewer)
        insertIssue(seq = 1)

        val cards =
            cardsOf(adapter(resolver).listVisibleIssuesByProject("TPRJ", permittedViewer, BoardCardFilter.EMPTY))

        // M1 과 같은 행·같은 키다. 뷰어만 다르다 — 차이의 원인이 권한 하나로 좁혀진다.
        assertThat(cards.getValue("TPRJ-1").customFields)
            .describedAs("권한 있는 뷰어에게는 맵 전체가 값까지 그대로 실린다 — 이 축이 없으면 전부 지우는 구현이 통과한다")
            .containsExactlyInAnyOrderEntriesOf(mapOf<String, Any?>(RESTRICTED_KEY to 9000, ALLOWED_KEY to "high"))
    }

    @Test
    fun `M3 - 백로그 경로 카드도 같은 마스킹을 받는다`() {
        val blindViewer = UUID.randomUUID()
        val resolver = RecordingFieldPermissionResolver(blindViewer)
        insertIssue(seq = 1)

        // 백로그는 2-인자 오버로드를 쓴다. 한쪽만 막으면 다른 쪽으로 그대로 샌다.
        val cards = cardsOf(adapter(resolver).listVisibleIssuesByProject("TPRJ", blindViewer))

        assertThat(cards.getValue("TPRJ-1").customFields).doesNotContainKey(RESTRICTED_KEY)
        assertThat(cards.getValue("TPRJ-1").customFields)
            .describedAs("백로그 경로에서도 제한 없는 키는 남는다")
            .containsEntry(ALLOWED_KEY, "high")
    }

    @Test
    fun `M4 - 보드 경로는 카드가 33건이어도 필드 권한 판정이 1회다`() {
        val blindViewer = UUID.randomUUID()
        val resolver = RecordingFieldPermissionResolver(blindViewer)
        insertIssues(count = 33)

        val page = adapter(resolver).listVisibleIssuesByProject("TPRJ", blindViewer, BoardCardFilter.EMPTY)

        assertThat(page.issues).hasSize(33)
        assertThat(resolver.visibleCalls.get())
            .describedAs("카드마다 부르면 33회가 된다 — 페이지당 1회로 고정한다")
            .isEqualTo(1)
        assertThat(resolver.seenCandidates.single())
            .describedAs("candidates 는 페이지 내 커스텀 필드 키의 합집합이다")
            .containsExactlyInAnyOrder(
                FieldRef(FieldKind.CUSTOM, RESTRICTED_KEY),
                FieldRef(FieldKind.CUSTOM, ALLOWED_KEY),
            )
    }

    @Test
    fun `M5 - 백로그 경로도 카드가 33건이어도 필드 권한 판정이 1회다`() {
        val blindViewer = UUID.randomUUID()
        val resolver = RecordingFieldPermissionResolver(blindViewer)
        insertIssues(count = 33)

        val page = adapter(resolver).listVisibleIssuesByProject("TPRJ", blindViewer)

        assertThat(page.issues).hasSize(33)
        assertThat(resolver.visibleCalls.get())
            .describedAs("2-인자 오버로드가 3-인자로 위임하므로 백로그도 1회여야 한다")
            .isEqualTo(1)
    }
}
