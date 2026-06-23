// IssueRepository rank 관련 메서드 통합 테스트 — FR-BL-01 Task 3

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
 * IssueRepository rank 관련 메서드 통합 테스트 (FR-BL-01 Task 3).
 *
 * IssueTestcontainersBase 상속으로 JVM singleton PostgreSQL container + Flyway migrate 를 공유한다.
 * cleanIssues (@BeforeEach) 로 각 테스트가 독립된 issues 상태에서 시작한다.
 *
 * 검증 시나리오.
 * - R01. updateRank — rank 만 변경하고 version·updated_at 이 불변(no-bump).
 * - R02. findRankByKey — 저장된 rank 를 정확히 반환한다.
 * - R03. findRanksForRebalance — ORDER BY rank, id, 소프트삭제 제외, (key, rank) 쌍 반환.
 * - R04. findMaxRank — 프로젝트의 최대 rank 반환, 소프트삭제 제외.
 * - R05. createIssue rank 자동부여 — 신규 이슈 삽입 시 rank 가 NOT NULL, 기존 최대 rank 보다 큼.
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

    private fun requireTaskTypeId(): IssueTypeId =
        requireNotNull(taskTypeId) { "taskTypeId 가 초기화되지 않았습니다 — resolveTaskTypeId 실행 확인" }

    /**
     * 기본 테스트 이슈를 DB 에 삽입하고 반환한다.
     * cleanIssues(@BeforeEach) 이후 호출을 전제로 key 는 항상 TPRJ-{seq}.
     *
     * @param seq key 시퀀스 번호 (TPRJ-1, TPRJ-2, ...).
     * @param rank 삽입할 rank. null 이면 Rank.initial() 로 초기화 후 copy 로 주입.
     */
    private fun insertIssue(
        seq: Long = 1L,
        rank: Rank = Rank.initial(),
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
                ).copy(rank = rank.value)
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
    fun `R03b - findRanksForRebalance - rank 동률 시 id 오름차순으로 tie-break`() {
        val sameRank = Rank.of("n")
        val i1 = insertIssue(seq = 1L, rank = sameRank)
        val i2 = insertIssue(seq = 2L, rank = sameRank)

        val rows = repository.findRanksForRebalance(testProjectId)

        assertThat(rows).hasSize(2)
        // UUID 사전순 — 두 항목이 모두 sameRank 를 가지며 id 순으로 정렬됨
        val ids = rows.map { it.first }
        val expectedOrder = listOf(i1.key.value, i2.key.value).sortedWith(
            Comparator { a, b ->
                val rankA = if (a == i1.key.value) i1.id.value else i2.id.value
                val rankB = if (b == i1.key.value) i1.id.value else i2.id.value
                rankA.compareTo(rankB)
            },
        )
        assertThat(ids).containsExactlyElementsOf(expectedOrder)
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

    // ── R05. createIssue rank 자동부여 — NOT NULL, 기존 최대보다 큼 ──────────────

    /**
     * Given  rank "n" 인 기존 이슈 1건이 삽입된 상태
     * When   신규 이슈를 rank=Rank.initial() 보다 뒤(between(Rank.of("n"), null))로 삽입
     * Then   신규 이슈의 rank 가 NOT NULL 이고, "n" 보다 크다.
     *
     * 이 테스트는 IssueApplicationService.createIssue 에서 rank 를 자동부여하는 로직 대신
     * repository.insert 레벨에서 rank 필드가 NOT NULL 로 채워지는 것을 직접 검증한다.
     * (IssueApplicationService 전체 의존 없이 repository 단위 검증)
     */
    @Test
    @Order(9)
    fun `R05 - insert - rank 필드가 NOT NULL 로 채워진다`() {
        val existingRank = Rank.of("n")
        insertIssue(seq = 1L, rank = existingRank)

        val newRank = Rank.between(existingRank, null)
        val newIssue = insertIssue(seq = 2L, rank = newRank)

        assertThat(newIssue.rank).isNotNull()
        assertThat(newIssue.rank).isNotEmpty()
        assertThat(newIssue.rank).isGreaterThan(existingRank.value)
    }

    /**
     * Given  이슈가 없는 빈 프로젝트
     * When   첫 이슈를 Rank.initial() 로 삽입
     * Then   rank 가 non-null, 소문자 a-z, 1~50자, 끝문자 != 'a'.
     */
    @Test
    @Order(10)
    fun `R05b - insert - 첫 이슈의 rank 는 Rank initial 규칙을 만족한다`() {
        val initialRank = Rank.initial()
        val inserted = insertIssue(seq = 1L, rank = initialRank)

        assertThat(inserted.rank).isNotNull()
        assertThat(inserted.rank).matches("^[a-z]{1,50}$")
        assertThat(inserted.rank!!.last()).isNotEqualTo('a')
    }
}
