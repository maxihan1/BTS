// IssueRepository rank 관련 메서드 통합 테스트 — FR-BL-01 Task 3 (옵션 B: nullable + lazy)

package com.bts.issue.repository

import com.bts.issue.domain.ActorId
import com.bts.issue.domain.Issue
import com.bts.issue.domain.IssueId
import com.bts.issue.domain.IssueKey
import com.bts.shared.issue.IssueTypeId
import com.bts.shared.lexorank.Rank
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.sql.DriverManager
import java.util.UUID

/**
 * IssueRepository rank 관련 메서드 통합 테스트 (FR-BL-01 Task 3, 옵션 B: nullable + lazy).
 *
 * IssueTestcontainersBase 상속으로 JVM singleton PostgreSQL container + Flyway migrate 를 공유한다.
 * cleanIssues (@BeforeEach) 로 각 테스트가 독립된 issues 상태에서 시작한다.
 *
 * 검증 시나리오.
 * - R01. updateRank — rank 만 변경하고 version·updated_at 이 불변(no-bump).
 * - R02. findRankByKey — 저장된 rank 를 정확히 반환한다.
 * - R03. findRanksForRebalance — ORDER BY rank NULLS LAST, created_at, id, 소프트삭제 제외, (key, rank?) 쌍 반환.
 * - R04. findMaxRank — 프로젝트의 최대 rank 반환, 소프트삭제 제외.
 * - R05. insert nullable rank — 신규 이슈를 rank=null 로 삽입 가능 (옵션 B, lazy 부여).
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class IssueRankRepositoryTest : IssueTestcontainersBase() {
    /**
     * V003 seed 에서 task 타입 id 를 조회한다.
     * IssueTypeId 는 value class 라 lateinit 불가 — var + null 허용.
     */
    private var taskTypeId: IssueTypeId? = null

    @BeforeAll
    fun resolveTaskTypeId() {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            val sql = "SELECT id FROM issue_types WHERE key = 'task' AND deleted_at IS NULL LIMIT 1"
            conn.prepareStatement(sql).use { stmt ->
                stmt.executeQuery().use { rs ->
                    check(rs.next()) { "V003 마이그레이션에서 task 타입이 없습니다." }
                    taskTypeId = IssueTypeId(rs.getLong(1))
                }
            }
        }
    }

    private fun requireTaskTypeId(): IssueTypeId {
        return requireNotNull(taskTypeId) { "taskTypeId 미초기화 — resolveTaskTypeId 확인" }
    }

    /**
     * 기본 테스트 이슈를 DB 에 삽입하고 반환한다.
     * cleanIssues(@BeforeEach) 이후 호출을 전제로 key 는 항상 TPRJ-{seq}.
     *
     * @param seq key 시퀀스 번호 (TPRJ-1, TPRJ-2, ...).
     * @param rank 삽입할 rank. null 이면 rank=null 로 삽입 (옵션 B, lazy 미부여).
     */
    private fun insertIssue(
        seq: Long = 1L,
        rank: Rank? = null,
    ): Issue {
        val key = IssueKey.of("TPRJ", seq)
        val issue =
            Issue
                .create(
                    id = IssueId(UUID.randomUUID()),
                    key = key,
                    projectId = testProjectId,
                    typeId = requireTaskTypeId(),
                    summary = "rank 테스트 이슈 $seq",
                    reporterId = ActorId(UUID.randomUUID()),
                    currentStateKey = "open",
                ).copy(rank = rank?.value)
        return repository.insert(issue)
    }

    // ── R01. updateRank — no-bump 검증 ────────────────────────────────────────

    /**
     * Given  이슈가 삽입된 직후 상태 (version=1, updated_at=T0)
     * When   updateRank 로 rank 를 변경
     * Then   DB 에서 rank 만 변경되고, version 과 updated_at 이 불변이다.
     */
    @Test
    @Order(1)
    fun `R01 - updateRank - rank 만 변경하고 version 과 updated_at 이 불변이다`() {
        val inserted = insertIssue(seq = 1L, rank = Rank.of("n"))

        val versionBefore = inserted.version
        val updatedAtBefore = inserted.updatedAt

        val newRank = Rank.of("z")
        repository.updateRank(inserted.key, newRank.value)

        val found = requireNotNull(repository.findByKey(inserted.key)) { "findByKey 결과가 null" }

        assertThat(found.rank).isEqualTo(newRank.value)
        assertThat(found.version).isEqualTo(versionBefore)
        assertThat(found.updatedAt).isEqualTo(updatedAtBefore)
    }

    // ── R02. findRankByKey — 저장된 rank 반환 ─────────────────────────────────

    /**
     * Given  rank="n" 로 삽입된 이슈
     * When   findRankByKey 호출
     * Then   "n" 을 반환한다.
     */
    @Test
    @Order(2)
    fun `R02 - findRankByKey - 저장된 rank 를 반환한다`() {
        val rank = Rank.of("n")
        val inserted = insertIssue(seq = 1L, rank = rank)

        val found = repository.findRankByKey(inserted.key)

        assertThat(found).isEqualTo(rank.value)
    }

    /**
     * Given  존재하지 않는 이슈 키
     * When   findRankByKey 호출
     * Then   null 을 반환한다.
     */
    @Test
    @Order(3)
    fun `R02b - findRankByKey - 존재하지 않는 키는 null 반환`() {
        val notExistKey = IssueKey.of("TPRJ", 999L)
        val found = repository.findRankByKey(notExistKey)
        assertThat(found).isNull()
    }

    // ── R03. findRanksForRebalance — ORDER BY rank,id, 소프트삭제 제외 ─────────

    /**
     * Given  프로젝트에 이슈 3건 (rank 역순 삽입), 소프트삭제 이슈 1건
     * When   findRanksForRebalance 호출
     * Then   rank, id 오름차순으로 정렬된 (key, rank) 목록 반환 (소프트삭제 제외).
     */
    @Test
    @Order(4)
    fun `R03 - findRanksForRebalance - rank 오름차순으로 반환하고 소프트삭제 제외`() {
        val rankZ = Rank.of("z")
        val rankN = Rank.of("n")
        val rankB = Rank.of("b")

        val i1 = insertIssue(seq = 1L, rank = rankZ)
        val i2 = insertIssue(seq = 2L, rank = rankN)
        val i3 = insertIssue(seq = 3L, rank = rankB)

        // 소프트삭제 이슈 1건 추가
        val i4 = insertIssue(seq = 4L, rank = Rank.of("m"))
        repository.softDelete(i4.key)

        val rows = repository.findRanksForRebalance(testProjectId)

        // rank 오름차순: b < n < z, 소프트삭제(m) 제외
        assertThat(rows).hasSize(3)
        assertThat(rows.map { it.first }).containsExactly(i3.key.value, i2.key.value, i1.key.value)
        assertThat(rows.map { it.second }).containsExactly(rankB.value, rankN.value, rankZ.value)
    }

    /**
     * Given  같은 rank 를 가진 이슈 2건 (tie)
     * When   findRanksForRebalance 호출
     * Then   rank 동일 시 id 오름차순(UUID lexicographic) 으로 tie-break 된다.
     */
    @Test
    @Order(5)
    fun `R03b - findRanksForRebalance - rank 동률 시 결정적 순서를 보장한다`() {
        val sameRank = Rank.of("n")
        insertIssue(seq = 1L, rank = sameRank)
        insertIssue(seq = 2L, rank = sameRank)

        val rows = repository.findRanksForRebalance(testProjectId)

        assertThat(rows).hasSize(2)
        // 두 행 모두 sameRank 를 가진다.
        assertThat(rows.map { it.second }).containsOnly(sameRank.value)
        // 같은 질의를 두 번 실행하면 항상 동일한 순서가 반환된다 (ORDER BY rank, id 결정적 정렬).
        val rows2 = repository.findRanksForRebalance(testProjectId)
        assertThat(rows.map { it.first }).containsExactlyElementsOf(rows2.map { it.first })
    }

    // ── R04. findMaxRank — 프로젝트 최대 rank, 소프트삭제 제외 ─────────────────

    /**
     * Given  rank "b","n","z" 로 이슈 3건 삽입
     * When   findMaxRank 호출
     * Then   "z" 반환 (사전순 최대).
     */
    @Test
    @Order(6)
    fun `R04 - findMaxRank - 프로젝트의 사전순 최대 rank 를 반환한다`() {
        insertIssue(seq = 1L, rank = Rank.of("b"))
        insertIssue(seq = 2L, rank = Rank.of("n"))
        insertIssue(seq = 3L, rank = Rank.of("z"))

        val maxRank = repository.findMaxRank(testProjectId)

        assertThat(maxRank).isEqualTo("z")
    }

    /**
     * Given  이슈가 하나도 없는 프로젝트
     * When   findMaxRank 호출
     * Then   null 반환.
     */
    @Test
    @Order(7)
    fun `R04b - findMaxRank - 이슈가 없으면 null 반환`() {
        val maxRank = repository.findMaxRank(testProjectId)
        assertThat(maxRank).isNull()
    }

    /**
     * Given  rank "z" 인 이슈 1건이 소프트삭제됨, rank "n" 인 이슈 1건이 활성
     * When   findMaxRank 호출
     * Then   소프트삭제("z")를 제외하고 "n" 반환.
     */
    @Test
    @Order(8)
    fun `R04c - findMaxRank - 소프트삭제 이슈를 제외하고 최대 rank 를 반환한다`() {
        insertIssue(seq = 1L, rank = Rank.of("n"))
        val deleted = insertIssue(seq = 2L, rank = Rank.of("z"))
        repository.softDelete(deleted.key)

        val maxRank = repository.findMaxRank(testProjectId)

        assertThat(maxRank).isEqualTo("n")
    }

    // ── R05. insert nullable rank (옵션 B, lazy) ─────────────────────────────

    /**
     * Given  이슈가 없는 빈 프로젝트
     * When   신규 이슈를 rank=null 로 삽입 (옵션 B, lazy 미부여)
     * Then   DB 에서 rank=null 로 저장된다 (NOT NULL 위반 없음).
     */
    @Test
    @Order(9)
    fun `R05 - insert - rank null 로 삽입 가능 (옵션 B, lazy 부여)`() {
        // rank 미지정 = null (기본값)
        val inserted = insertIssue(seq = 1L)

        assertThat(inserted.rank).isNull()
    }

    /**
     * Given  rank=null 이슈가 있는 프로젝트
     * When   findMaxRank 호출
     * Then   rank=null 이슈는 max 계산에서 무시되고 null 반환.
     */
    @Test
    @Order(10)
    fun `R05b - findMaxRank - rank null 이슈만 있으면 null 반환`() {
        insertIssue(seq = 1L) // rank=null
        insertIssue(seq = 2L) // rank=null

        val maxRank = repository.findMaxRank(testProjectId)

        assertThat(maxRank).isNull()
    }

    /**
     * Given  rank 있는 이슈와 rank=null 이슈가 혼재
     * When   findRanksForRebalance 호출
     * Then   rank null 이슈가 NULLS LAST 로 맨 뒤에 위치한다.
     */
    @Test
    @Order(11)
    fun `R05c - findRanksForRebalance - rank null 이슈는 NULLS LAST 로 맨 뒤`() {
        val rankN = Rank.of("n")
        val i1 = insertIssue(seq = 1L, rank = rankN)
        val i2 = insertIssue(seq = 2L) // rank=null

        val rows = repository.findRanksForRebalance(testProjectId)

        assertThat(rows).hasSize(2)
        // rank 있는 이슈가 앞, rank null 이슈가 뒤
        assertThat(rows[0].first).isEqualTo(i1.key.value)
        assertThat(rows[0].second).isEqualTo(rankN.value)
        assertThat(rows[1].first).isEqualTo(i2.key.value)
        assertThat(rows[1].second).isNull()
    }
}
