// FR-TL-02 Task 2 — IssueLinkRepository.findBlocksEdgesAmong 통합 테스트 + EXPLAIN 검증

package com.bts.issue.link.repository

import com.bts.issue.domain.ActorId
import com.bts.issue.domain.Issue
import com.bts.issue.domain.IssueId
import com.bts.issue.domain.IssueKey
import com.bts.issue.jooq.tables.references.ISSUE_LINKS
import com.bts.issue.link.domain.IssueLink
import com.bts.issue.link.domain.LinkType
import com.bts.issue.repository.IssueTestcontainersBase
import com.bts.shared.issue.IssueTypeId
import org.assertj.core.api.Assertions.assertThat
import org.jooq.conf.ParamType
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.sql.DriverManager
import java.util.UUID

/**
 * [IssueLinkRepository.findBlocksEdgesAmong] Testcontainers 통합 테스트 (FR-TL-02 Task 2).
 *
 * [IssueTestcontainersBase] JVM singleton PostgreSQL 컨테이너를 재사용한다.
 * Spring ApplicationContext 없이 DSLContext 를 직접 조합한다.
 *
 * ## 검증 시나리오
 * - T2-A. blocks 엣지 + 타입 필터 + 집합-외 target 필터 동시 검증 (positive + negative control 포함).
 * - T2-B. 빈 집합 입력 → 빈 리스트 반환 (short-circuit).
 */
class IssueLinkRepositoryDepsTest : IssueTestcontainersBase() {
    /** V003 seed task 타입 id. value class 는 lateinit 불가 → nullable var. */
    private var taskTypeId: IssueTypeId? = null

    private lateinit var linkRepository: IssueLinkRepository

    private val log = LoggerFactory.getLogger(javaClass)

    // ── setup ──────────────────────────────────────────────────────────────────

    /**
     * 부모 [IssueTestcontainersBase.bootstrap] 이 @BeforeAll 로 먼저 실행된다.
     * 각 테스트 전 IssueLinkRepository 초기화 + taskTypeId 조회.
     */
    @BeforeEach
    fun setupLinkRepository() {
        linkRepository = IssueLinkRepository(dsl)
        if (taskTypeId == null) {
            taskTypeId = loadTaskTypeId()
        }
    }

    /** 각 테스트 전 issue_links 전체 삭제. issues 는 부모 cleanIssues() 가 처리. */
    @BeforeEach
    fun cleanLinks() {
        DriverManager.getConnection(
            IssueTestcontainersBase.postgres.jdbcUrl,
            IssueTestcontainersBase.postgres.username,
            IssueTestcontainersBase.postgres.password,
        ).use { conn ->
            conn.createStatement().use { it.execute("DELETE FROM issue_links") }
        }
    }

    // ── 헬퍼 ──────────────────────────────────────────────────────────────────

    private fun requireTaskTypeId(): IssueTypeId = requireNotNull(taskTypeId) { "taskTypeId 가 초기화되지 않았습니다." }

    /**
     * V003 시드에서 'task' 이슈 타입 id 를 조회한다.
     * 중첩 깊이를 낮추기 위해 setupLinkRepository 에서 분리.
     */
    @Suppress("NestedBlockDepth")
    private fun loadTaskTypeId(): IssueTypeId =
        DriverManager.getConnection(
            IssueTestcontainersBase.postgres.jdbcUrl,
            IssueTestcontainersBase.postgres.username,
            IssueTestcontainersBase.postgres.password,
        ).use { conn ->
            conn.prepareStatement(
                "SELECT id FROM issue_types WHERE key = 'task' AND deleted_at IS NULL LIMIT 1",
            ).use { stmt ->
                stmt.executeQuery().use { rs ->
                    check(rs.next()) { "V003 마이그레이션에서 task 타입이 없습니다." }
                    IssueTypeId(rs.getLong(1))
                }
            }
        }

    /** 이슈 1건을 삽입하고 반환한다. */
    private fun insertIssue(seqNum: Long): Issue =
        repository.insert(
            Issue.create(
                id = IssueId(UUID.randomUUID()),
                key = IssueKey.of("TPRJ", seqNum),
                projectId = testProjectId,
                typeId = requireTaskTypeId(),
                summary = "타임라인 의존 통합 테스트 이슈 $seqNum",
                reporterId = ActorId(UUID.randomUUID()),
                currentStateKey = "open",
            ),
        )

    // ── T2-A. blocks 엣지 + 타입 필터 + 집합-외 필터 ─────────────────────────

    /**
     * Given  이슈 A, B, C, D + 링크 (A blocks B), (A relates C), (A blocks D)
     * When   findBlocksEdgesAmong(setOf(A.id, B.id, C.id))
     * Then   (A→B) blocks 엣지만 반환 (positive control).
     *        relates(A→C) 는 타입 필터로 제외 (negative: 타입 필터 실동작).
     *        blocks(A→D) 는 D 가 집합 밖이므로 제외 (negative: 집합-외 target 필터 실동작).
     */
    @Test
    fun `T2-A - findBlocksEdgesAmong - blocks 엣지만 양끝 집합 안에서 반환`() {
        val issueA = insertIssue(1L)
        val issueB = insertIssue(2L)
        val issueC = insertIssue(3L)
        val issueD = insertIssue(4L)

        linkRepository.insert(IssueLink.create(issueA.id.value, issueB.id.value, LinkType.BLOCKS))
        linkRepository.insert(IssueLink.create(issueA.id.value, issueC.id.value, LinkType.RELATES))
        linkRepository.insert(IssueLink.create(issueA.id.value, issueD.id.value, LinkType.BLOCKS))

        val querySet = setOf(issueA.id.value, issueB.id.value, issueC.id.value)
        val result = linkRepository.findBlocksEdgesAmong(querySet)

        // positive control: (A→B) blocks 엣지 존재 확인
        assertThat(result).hasSize(1)
        assertThat(result[0].sourceId).isEqualTo(issueA.id.value)
        assertThat(result[0].targetId).isEqualTo(issueB.id.value)

        // negative: relates(A→C) 는 타입 필터로 제외됨을 확인
        assertThat(result).noneMatch { it.targetId == issueC.id.value }

        // negative: D 는 querySet 밖 → blocks(A→D) 도 제외됨을 확인
        assertThat(result).noneMatch { it.targetId == issueD.id.value }
    }

    // ── T2-B. 빈 집합 short-circuit ──────────────────────────────────────────

    /**
     * Given  빈 집합
     * When   findBlocksEdgesAmong(emptySet())
     * Then   빈 리스트 반환 (short-circuit, 쿼리 미실행)
     */
    @Test
    fun `T2-B - findBlocksEdgesAmong - 빈 집합 입력 시 빈 리스트 반환`() {
        val result = linkRepository.findBlocksEdgesAmong(emptySet())
        assertThat(result).isEmpty()
    }

    // ── EXPLAIN 검증 — jOOQ 렌더 SQL 기준 인덱스 사용 여부 기록 ─────────────────

    /**
     * findBlocksEdgesAmong 가 생성하는 jOOQ 렌더 SQL 을 EXPLAIN 으로 확인하고 결과를 로그에 기록한다.
     *
     * 소규모 테스트 데이터에서는 PostgreSQL 이 Seq Scan 을 선택할 수 있으므로 plan 자체를 assert 하지 않는다.
     * 대신 로그 출력을 보고에 기재한다.
     */
    @Test
    @Suppress("NestedBlockDepth")
    fun `EXPLAIN - findBlocksEdgesAmong jOOQ 렌더 SQL 실행 계획 기록`() {
        val id1 = UUID.randomUUID()
        val id2 = UUID.randomUUID()
        val inlinedSql =
            dsl.select(ISSUE_LINKS.SOURCE_ID, ISSUE_LINKS.TARGET_ID)
                .from(ISSUE_LINKS)
                .where(ISSUE_LINKS.LINK_TYPE.eq(LinkType.BLOCKS.code))
                .and(ISSUE_LINKS.SOURCE_ID.`in`(listOf(id1, id2)))
                .and(ISSUE_LINKS.TARGET_ID.`in`(listOf(id1, id2)))
                .orderBy(ISSUE_LINKS.SOURCE_ID, ISSUE_LINKS.TARGET_ID)
                .limit(IssueLinkRepository.DEPS_FETCH_LIMIT + 1)
                .getSQL(ParamType.INLINED)

        log.info("findBlocksEdgesAmong 렌더 SQL (inlined): {}", inlinedSql)

        val plan =
            DriverManager.getConnection(
                IssueTestcontainersBase.postgres.jdbcUrl,
                IssueTestcontainersBase.postgres.username,
                IssueTestcontainersBase.postgres.password,
            ).use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.executeQuery("EXPLAIN $inlinedSql").use { rs ->
                        val lines = mutableListOf<String>()
                        while (rs.next()) lines.add(rs.getString(1))
                        lines.joinToString("\n")
                    }
                }
            }

        log.info("EXPLAIN 결과:\n{}", plan)

        if (plan.contains("idx_issue_links_source_id")) {
            log.info("인덱스 확인: idx_issue_links_source_id 사용")
        } else {
            log.warn(
                "idx_issue_links_source_id 미사용 (소규모 테스트 데이터 Seq Scan — " +
                    "운영 환경 충분한 행 수에서는 인덱스 활용 예상)",
            )
        }
    }
}
