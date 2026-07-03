// IssueHistoryRecorder.recordImported — Jira changelog 재생 시 detector 우회·과거 createdAt 명시 삽입 검증

package com.bts.issue.history

import com.bts.issue.component.repository.ComponentRepository
import com.bts.issue.repository.IssueRepository
import com.bts.issue.repository.IssueTestcontainersBase
import com.bts.issue.resolution.repository.ResolutionRepository
import com.bts.issue.type.repository.IssueTypeRepository
import com.bts.issue.version.repository.VersionRepository
import com.bts.shared.permission.IssueSecurityAccess
import com.bts.shared.permission.IssueSecurityDirectory
import com.bts.shared.user.UserLookupPort
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.sql.DriverManager
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * [IssueHistoryRecorder.recordImported] 통합 테스트 (FR-IM-01 PR4 Task 3).
 *
 * **Testcontainers 실 Postgres 선택 이유.**
 * `IssueChangeGroup.createdAt` 명시 삽입 여부는 DB `DEFAULT NOW()` 발동을 실제로 우회하는지가
 * 핵심 검증 대상이다. mock repository 로는 실제 SQL 분기(COALESCE 대신 두 SQL 분기)가
 * 올바르게 TIMESTAMPTZ 를 저장하는지 확인할 수 없으므로 [IssueTestcontainersBase] 를 상속해
 * 실 PostgreSQL 컨테이너에 기록·조회한다.
 *
 * **mockk 미사용.**
 * cross-BC 포트([UserLookupPort], [IssueSecurityDirectory])는 [IssueChangeLabelResolver] 생성자
 * 요구사항을 채우기 위한 자리이지만, `recordImported` 는 resolver 를 전혀 호출하지 않는다.
 * 이를 증명하기 위해 mockk 대신 호출 시 즉시 실패하는 순수 Kotlin 객체를 사용한다 — 만약
 * `recordImported` 구현이 실수로 resolver 를 경유하면 이 스텁이 즉시 에러를 던져 테스트가
 * 실패한다(가짜그린 방지).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IssueChangeImportHistoryTest : IssueTestcontainersBase() {
    private lateinit var historyRepository: IssueChangeHistoryRepository
    private lateinit var recorder: IssueHistoryRecorder
    private var historyBootstrapped = false

    /** resolver 가 호출되면 안 되므로, 호출 시 즉시 실패하는 UserLookupPort 스텁. */
    private object UnusedUserLookupPort : UserLookupPort {
        override fun exists(userId: UUID): Boolean {
            error("recordImported 는 resolver 를 경유하면 안 된다 — UserLookupPort.exists 호출됨")
        }
    }

    /** resolver 가 호출되면 안 되므로, 호출 시 즉시 실패하는 IssueSecurityDirectory 스텁. */
    private object UnusedIssueSecurityDirectory : IssueSecurityDirectory {
        override fun levelBelongsToProjectScheme(
            levelId: UUID,
            projectKey: String,
        ): Boolean = error("recordImported 는 resolver 를 경유하면 안 된다 — IssueSecurityDirectory 호출됨")

        override fun accessibleLevels(
            actorId: UUID,
            projectKey: String,
        ): IssueSecurityAccess = error("recordImported 는 resolver 를 경유하면 안 된다 — IssueSecurityDirectory 호출됨")
    }

    @BeforeAll
    fun setUpHistory() {
        if (historyBootstrapped) return

        val dataSource = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        val jdbc = NamedParameterJdbcTemplate(dataSource)
        historyRepository = JdbcIssueChangeHistoryRepository(jdbc)

        val detector = IssueChangeDetector()
        val resolver =
            IssueChangeLabelResolver(
                issueTypeRepository = IssueTypeRepository(dsl),
                resolutionRepository = ResolutionRepository(dsl),
                componentRepository = ComponentRepository(dsl),
                versionRepository = VersionRepository(dsl),
                userLookupPort = UnusedUserLookupPort,
                issueSecurityDirectory = UnusedIssueSecurityDirectory,
                issueRepository = IssueRepository(dsl),
            )
        recorder = IssueHistoryRecorder(detector = detector, resolver = resolver, repository = historyRepository)

        historyBootstrapped = true
    }

    @BeforeEach
    fun cleanHistoryTables() {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("DELETE FROM issue_change_item")
                stmt.execute("DELETE FROM issue_change_group")
            }
        }
    }

    // ── (i) createdAt 명시 삽입 ────────────────────────────────────────────────

    @Test
    fun `group의 createdAt이 과거 시각이면 그대로 삽입되고 조회 시 그 시각을 반환한다`() {
        val issueId = UUID.randomUUID()
        val pastCreatedAt = Instant.parse("2020-01-01T00:00:00Z")
        val group =
            IssueChangeGroup(
                issueId = issueId,
                issueKey = "IMP-1",
                actorId = UUID.randomUUID(),
                items =
                    listOf(
                        IssueChangeItem(
                            field = "status",
                            fromValue = "1",
                            toValue = "3",
                            fromLabel = "Open",
                            toLabel = "Done",
                        ),
                    ),
                createdAt = pastCreatedAt,
            )

        recorder.recordImported(group)

        val found = historyRepository.findByIssue(issueId)
        assertThat(found).hasSize(1)
        assertThat(found.first().createdAt).isEqualTo(pastCreatedAt)
        // NOW() 폴백이 아니라 과거 시각이 그대로 저장됐는지 재확인 — 회귀 방지
        assertThat(found.first().createdAt).isBefore(Instant.now().minus(1, ChronoUnit.DAYS))
    }

    // ── (ii) actorId — 원본 author, null 허용 ─────────────────────────────────

    @Test
    fun `actorId는 원본 author UUID를 저장한다`() {
        val issueId = UUID.randomUUID()
        val originalAuthorId = UUID.randomUUID()
        val group =
            IssueChangeGroup(
                issueId = issueId,
                issueKey = "IMP-2",
                actorId = originalAuthorId,
                items = emptyList(),
                createdAt = Instant.parse("2021-05-01T00:00:00Z"),
            )

        recorder.recordImported(group)

        val found = historyRepository.findByIssue(issueId)
        assertThat(found).hasSize(1)
        assertThat(found.first().actorId).isEqualTo(originalAuthorId)
    }

    @Test
    fun `actorId가 null이면 null로 저장된다 (author 식별 불가 Jira 계정 등)`() {
        val issueId = UUID.randomUUID()
        val group =
            IssueChangeGroup(
                issueId = issueId,
                issueKey = "IMP-3",
                actorId = null,
                items = emptyList(),
                createdAt = Instant.parse("2021-06-01T00:00:00Z"),
            )

        recorder.recordImported(group)

        val found = historyRepository.findByIssue(issueId)
        assertThat(found).hasSize(1)
        assertThat(found.first().actorId).isNull()
    }

    // ── (iii)+(iv) items 그대로 저장 — detector 미경유 (임의 항목 기록) ────────────

    @Test
    fun `items를 detector 변환 없이 그대로 저장한다 (임의 field도 허용)`() {
        val issueId = UUID.randomUUID()
        // "external_import_marker" 는 실제 detector 가 절대 생성하지 않는 임의 field 명.
        // detector 를 거쳤다면 이런 field 는 존재할 수 없다 — 그대로 저장됨을 통해 우회를 증명한다.
        val items =
            listOf(
                IssueChangeItem(
                    field = "external_import_marker",
                    fromValue = "raw-from",
                    toValue = "raw-to",
                    fromLabel = "원본 라벨(from)",
                    toLabel = "원본 라벨(to)",
                ),
                IssueChangeItem(
                    field = "assignee",
                    fromValue = null,
                    toValue = UUID.randomUUID().toString(),
                    // resolver 가 호출되지 않으므로 fromLabel/toLabel 도 import 시점 값 그대로 저장돼야 한다.
                    fromLabel = null,
                    toLabel = "이미 조립된 표시명",
                ),
            )
        val group =
            IssueChangeGroup(
                issueId = issueId,
                issueKey = "IMP-4",
                actorId = UUID.randomUUID(),
                items = items,
                createdAt = Instant.parse("2022-01-01T00:00:00Z"),
            )

        recorder.recordImported(group)

        val found = historyRepository.findByIssue(issueId)
        assertThat(found).hasSize(1)
        assertThat(found.first().items).hasSize(2)

        val marker = found.first().items.first { it.field == "external_import_marker" }
        assertThat(marker.fromValue).isEqualTo("raw-from")
        assertThat(marker.toValue).isEqualTo("raw-to")
        assertThat(marker.fromLabel).isEqualTo("원본 라벨(from)")
        assertThat(marker.toLabel).isEqualTo("원본 라벨(to)")

        val assignee = found.first().items.first { it.field == "assignee" }
        assertThat(assignee.fromValue).isNull()
        assertThat(assignee.toValue).isEqualTo(items[1].toValue)
        assertThat(assignee.fromLabel).isNull()
        assertThat(assignee.toLabel).isEqualTo("이미 조립된 표시명")
    }

    // ── (v) createdAt=null → NOW() 폴백 (하위호환) ────────────────────────────

    @Test
    fun `createdAt이 null이면 DB DEFAULT NOW()로 폴백한다 (하위호환)`() {
        val issueId = UUID.randomUUID()
        val before = Instant.now().minusSeconds(5)
        val group =
            IssueChangeGroup(
                issueId = issueId,
                issueKey = "IMP-5",
                actorId = null,
                items = emptyList(),
                createdAt = null,
            )

        recorder.recordImported(group)

        val found = historyRepository.findByIssue(issueId)
        assertThat(found).hasSize(1)
        assertThat(found.first().createdAt).isNotNull()
        assertThat(found.first().createdAt).isAfterOrEqualTo(before)
    }
}
