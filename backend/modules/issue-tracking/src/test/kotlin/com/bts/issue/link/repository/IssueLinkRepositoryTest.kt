// IssueLinkRepositoryTest — IssueLinkRepository CRUD + 재귀 CTE Testcontainers 통합 테스트 (FR-LK-01 Task 3)

package com.bts.issue.link.repository

import com.bts.issue.domain.ActorId
import com.bts.issue.domain.Issue
import com.bts.issue.domain.IssueId
import com.bts.issue.domain.IssueKey
import com.bts.issue.link.domain.IssueLink
import com.bts.issue.link.domain.LinkType
import com.bts.issue.repository.IssueRepository
import com.bts.issue.repository.IssueTestcontainersBase
import com.bts.shared.issue.IssueTypeId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.sql.DriverManager
import java.util.UUID

/**
 * [IssueLinkRepository] CRUD + 재귀 CTE(existsBlocksPath) Testcontainers 통합 테스트.
 *
 * [IssueTestcontainersBase] JVM singleton PostgreSQL 컨테이너를 재사용한다.
 * Spring ApplicationContext 없이 DSLContext 를 직접 조합한다.
 *
 * 테스트 시나리오 (FR-LK-01 Task 3).
 * - T3-A. insert — 새 링크 삽입 시 생성된 BIGINT id 반환.
 * - T3-B. findBySourceId — source=issueId 인 링크 목록 반환.
 * - T3-C. findByTargetId — target=issueId 인 링크 목록 반환.
 * - T3-D. existsLink — 중복 검사 true/false 반환.
 * - T3-E. deleteById — 삭제 성공 true / 존재하지 않으면 false.
 * - T3-F. existsBlocksPath — 직접 blocks 경로 (A→B) 도달성.
 * - T3-G. existsBlocksPath — 간접 경로 길이 2+ (A→B→C) 도달성.
 * - T3-H. existsBlocksPath — 경로 없는 케이스 false.
 * - T3-I. FK CASCADE — 이슈 하드 삭제 시 링크 행 자동 정리.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class IssueLinkRepositoryTest : IssueTestcontainersBase() {

    /** V003 seed task 타입 id. value class 는 lateinit 불가 → nullable var. */
    private var taskTypeId: IssueTypeId? = null

    private lateinit var linkRepository: IssueLinkRepository

    // ── setup ─────────────────────────────────────────────────────────────────

    /**
     * 부모 [IssueTestcontainersBase.bootstrap] 이 @BeforeAll 로 먼저 실행된다.
     * 각 테스트 전 IssueLinkRepository 초기화 + taskTypeId 조회 + issue_links 초기화.
     */
    @BeforeEach
    fun setupLinkRepository() {
        linkRepository = IssueLinkRepository(dsl)

        if (taskTypeId == null) {
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
                        taskTypeId = IssueTypeId(rs.getLong(1))
                    }
                }
            }
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

    private fun requireTaskTypeId(): IssueTypeId =
        requireNotNull(taskTypeId) { "taskTypeId 가 초기화되지 않았습니다." }

    /** 이슈 1건을 삽입하고 반환한다. */
    private fun insertIssue(seqNum: Long): Issue =
        repository.insert(
            Issue.create(
                id = IssueId(UUID.randomUUID()),
                key = IssueKey.of("TPRJ", seqNum),
                projectId = testProjectId,
                typeId = requireTaskTypeId(),
                summary = "링크 통합 테스트 이슈 $seqNum",
                reporterId = ActorId(UUID.randomUUID()),
                currentStateKey = "open",
            ),
        )

    /**
     * issue_links 테이블에서 linkId 에 해당하는 행이 있는지 직접 조회한다.
     * 삭제 검증용 헬퍼.
     */
    @Suppress("NestedBlockDepth")
    private fun rawLinkExists(linkId: Long): Boolean =
        DriverManager.getConnection(
            IssueTestcontainersBase.postgres.jdbcUrl,
            IssueTestcontainersBase.postgres.username,
            IssueTestcontainersBase.postgres.password,
        ).use { conn ->
            conn.prepareStatement("SELECT COUNT(*) FROM issue_links WHERE id = ?").use { stmt ->
                stmt.setLong(1, linkId)
                stmt.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1) > 0
                }
            }
        }

    // ── T3-A. insert — 생성된 BIGINT id 반환 ─────────────────────────────────

    /**
     * Given  이슈 A, B 존재
     * When   insert(A blocks B)
     * Then   반환 id 는 양수 BIGINT.
     */
    @Test
    @Order(1)
    fun `T3-A - insert - 새 링크 삽입 시 생성된 BIGINT id 반환`() {
        val issueA = insertIssue(1L)
        val issueB = insertIssue(2L)

        val link = IssueLink.create(issueA.id.value, issueB.id.value, LinkType.BLOCKS)
        val returned = linkRepository.insert(link)

        assertThat(returned.id).isNotNull().isGreaterThan(0L)
        assertThat(returned.sourceId).isEqualTo(issueA.id.value)
        assertThat(returned.targetId).isEqualTo(issueB.id.value)
        assertThat(returned.linkType).isEqualTo(LinkType.BLOCKS)
    }

    // ── T3-B. findBySourceId — source=issueId 링크 목록 ──────────────────────

    /**
     * Given  A blocks B, A relates C 두 링크 삽입
     * When   findBySourceId(A.id)
     * Then   두 링크 모두 반환, C를 source 로 조회 시 빈 목록.
     */
    @Test
    @Order(2)
    fun `T3-B - findBySourceId - source 이슈 기준 링크 목록 반환`() {
        val issueA = insertIssue(1L)
        val issueB = insertIssue(2L)
        val issueC = insertIssue(3L)

        linkRepository.insert(IssueLink.create(issueA.id.value, issueB.id.value, LinkType.BLOCKS))
        linkRepository.insert(IssueLink.create(issueA.id.value, issueC.id.value, LinkType.RELATES))

        val results = linkRepository.findBySourceId(issueA.id.value)

        assertThat(results).hasSize(2)
        assertThat(results.map { it.sourceId }).containsOnly(issueA.id.value)
        assertThat(results.map { it.targetId }).containsExactlyInAnyOrder(issueB.id.value, issueC.id.value)

        // C가 source 인 링크는 없음
        assertThat(linkRepository.findBySourceId(issueC.id.value)).isEmpty()
    }

    // ── T3-C. findByTargetId — target=issueId 링크 목록 ──────────────────────

    /**
     * Given  A blocks C, B relates C 두 링크 삽입
     * When   findByTargetId(C.id)
     * Then   두 링크 모두 반환, A를 target 으로 조회 시 빈 목록.
     */
    @Test
    @Order(3)
    fun `T3-C - findByTargetId - target 이슈 기준 링크 목록 반환`() {
        val issueA = insertIssue(1L)
        val issueB = insertIssue(2L)
        val issueC = insertIssue(3L)

        linkRepository.insert(IssueLink.create(issueA.id.value, issueC.id.value, LinkType.BLOCKS))
        linkRepository.insert(IssueLink.create(issueB.id.value, issueC.id.value, LinkType.RELATES))

        val results = linkRepository.findByTargetId(issueC.id.value)

        assertThat(results).hasSize(2)
        assertThat(results.map { it.targetId }).containsOnly(issueC.id.value)
        assertThat(results.map { it.sourceId }).containsExactlyInAnyOrder(issueA.id.value, issueB.id.value)

        // A가 target 인 링크는 없음
        assertThat(linkRepository.findByTargetId(issueA.id.value)).isEmpty()
    }

    // ── T3-D. existsLink — 중복 검사 boolean ─────────────────────────────────

    /**
     * Given  A blocks B 링크 삽입
     * When   existsLink(A, B, BLOCKS)
     * Then   true 반환.
     * When   existsLink(A, B, RELATES)
     * Then   false 반환 (다른 linkType).
     */
    @Test
    @Order(4)
    fun `T3-D - existsLink - 중복 검사 true 및 false`() {
        val issueA = insertIssue(1L)
        val issueB = insertIssue(2L)

        linkRepository.insert(IssueLink.create(issueA.id.value, issueB.id.value, LinkType.BLOCKS))

        assertThat(linkRepository.existsLink(issueA.id.value, issueB.id.value, LinkType.BLOCKS)).isTrue()
        // 다른 linkType 은 별개
        assertThat(linkRepository.existsLink(issueA.id.value, issueB.id.value, LinkType.RELATES)).isFalse()
        // 역방향도 별개
        assertThat(linkRepository.existsLink(issueB.id.value, issueA.id.value, LinkType.BLOCKS)).isFalse()
    }

    // ── T3-E. deleteById — 삭제 성공/실패 boolean ────────────────────────────

    /**
     * Given  A blocks B 링크 삽입
     * When   deleteById(linkId)
     * Then   true 반환 + 행 삭제.
     * When   deleteById(linkId) 재호출
     * Then   false 반환 (이미 없음).
     */
    @Test
    @Order(5)
    fun `T3-E - deleteById - 삭제 성공 true 및 없으면 false`() {
        val issueA = insertIssue(1L)
        val issueB = insertIssue(2L)

        val link = linkRepository.insert(IssueLink.create(issueA.id.value, issueB.id.value, LinkType.BLOCKS))
        val linkId = requireNotNull(link.id) { "insert 후 id 는 null 이 아니어야 한다." }

        val firstDelete = linkRepository.deleteById(linkId)
        assertThat(firstDelete).isTrue()
        assertThat(rawLinkExists(linkId)).isFalse()

        val secondDelete = linkRepository.deleteById(linkId)
        assertThat(secondDelete).isFalse()
    }

    // ── T3-F. existsBlocksPath — 직접 경로 (A→B) ─────────────────────────────

    /**
     * Given  A blocks B
     * When   existsBlocksPath(A.id, B.id)
     * Then   true.
     */
    @Test
    @Order(6)
    fun `T3-F - existsBlocksPath - 직접 blocks 경로 탐지`() {
        val issueA = insertIssue(1L)
        val issueB = insertIssue(2L)

        linkRepository.insert(IssueLink.create(issueA.id.value, issueB.id.value, LinkType.BLOCKS))

        assertThat(linkRepository.existsBlocksPath(issueA.id.value, issueB.id.value)).isTrue()
    }

    // ── T3-G. existsBlocksPath — 간접 경로 길이 2+ (A→B→C) ──────────────────

    /**
     * Given  A blocks B, B blocks C
     * When   existsBlocksPath(A.id, C.id)
     * Then   true (재귀 CTE 경유).
     * And    existsBlocksPath(C.id, A.id)
     * Then   false (역방향 경로 없음).
     */
    @Test
    @Order(7)
    fun `T3-G - existsBlocksPath - 간접 경로 길이 2 이상 도달성`() {
        val issueA = insertIssue(1L)
        val issueB = insertIssue(2L)
        val issueC = insertIssue(3L)

        linkRepository.insert(IssueLink.create(issueA.id.value, issueB.id.value, LinkType.BLOCKS))
        linkRepository.insert(IssueLink.create(issueB.id.value, issueC.id.value, LinkType.BLOCKS))

        // A → B → C 경로 있음
        assertThat(linkRepository.existsBlocksPath(issueA.id.value, issueC.id.value)).isTrue()
        // C → A 역방향 경로 없음
        assertThat(linkRepository.existsBlocksPath(issueC.id.value, issueA.id.value)).isFalse()
    }

    // ── T3-H. existsBlocksPath — 경로 없는 케이스 false ──────────────────────

    /**
     * Given  A relates B (blocks 아님)
     * When   existsBlocksPath(A.id, B.id)
     * Then   false (relates 는 blocks 그래프 제외).
     */
    @Test
    @Order(8)
    fun `T3-H - existsBlocksPath - 경로 없는 케이스 false`() {
        val issueA = insertIssue(1L)
        val issueB = insertIssue(2L)

        // blocks 가 아닌 relates 링크
        linkRepository.insert(IssueLink.create(issueA.id.value, issueB.id.value, LinkType.RELATES))

        assertThat(linkRepository.existsBlocksPath(issueA.id.value, issueB.id.value)).isFalse()
    }

    // ── T3-I. FK CASCADE — 이슈 하드 삭제 시 링크 자동 정리 ──────────────────

    /**
     * Given  A blocks B 링크 삽입
     * When   issues 테이블에서 A 하드 삭제
     * Then   issue_links 행도 자동 삭제 (ON DELETE CASCADE).
     */
    @Test
    @Order(9)
    fun `T3-I - FK CASCADE - 이슈 하드 삭제 시 링크 행 자동 정리`() {
        val issueA = insertIssue(1L)
        val issueB = insertIssue(2L)

        val link = linkRepository.insert(IssueLink.create(issueA.id.value, issueB.id.value, LinkType.BLOCKS))
        val linkId = requireNotNull(link.id)

        // A 이슈를 하드 삭제
        DriverManager.getConnection(
            IssueTestcontainersBase.postgres.jdbcUrl,
            IssueTestcontainersBase.postgres.username,
            IssueTestcontainersBase.postgres.password,
        ).use { conn ->
            conn.prepareStatement("DELETE FROM issues WHERE id = ?").use { stmt ->
                stmt.setObject(1, issueA.id.value)
                stmt.executeUpdate()
            }
        }

        // FK ON DELETE CASCADE 로 링크 행도 삭제되었어야 함
        assertThat(rawLinkExists(linkId)).isFalse()
    }
}
