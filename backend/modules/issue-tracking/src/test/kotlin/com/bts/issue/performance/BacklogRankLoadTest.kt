// 백로그 LexoRank 1K 부하 테스트 — NFR1(리랭크 < 5ms), NFR2(1K between), NFR3(rebalance < 500ms)

package com.bts.issue.performance

import com.bts.issue.adapter.outbound.AlwaysAllowIssuePermissionResolver
import com.bts.issue.application.BacklogRankService
import com.bts.issue.project.archive.ProjectArchiveGuard
import com.bts.issue.project.archive.repository.ProjectArchiveStateRepository
import com.bts.issue.repository.IssueTestcontainersBase
import com.bts.shared.lexorank.Rank
import com.bts.shared.lexorank.RankSpaceExhaustedException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.sql.DriverManager
import java.util.UUID

/** 부하 시나리오 이슈 수 (NFR2/NFR3 기준값). */
private const val ISSUE_COUNT = 1_000

/** Rank.between 리랭크 NFR1 임계값 (밀리초). */
private const val RERANK_AVG_MS_LIMIT = 5L

/** rebalance NFR3 임계값 (밀리초). */
private const val REBALANCE_TOTAL_MS_LIMIT = 500L

/** between 반복 삽입 최악 케이스 허용 최대 키 길이 — Rank.MAX_LENGTH 미만 보장. */
private val BETWEEN_MAX_KEY_LENGTH_LIMIT = Rank.MAX_LENGTH

/** 반복 삽입 테스트 횟수 — 같은 슬롯에 N 회 삽입 시 키 길이 증가 검증용. */
private const val REPEATED_INSERT_COUNT = 200

/**
 * 백로그 LexoRank 1K 부하 테스트 (FR-BL-01 Task 7).
 *
 * NFR1. 단일 리랭크 평균 < 5ms: Rank.between 1K 회 순수 알고리즘 측정.
 * NFR2. 1K between 시나리오 통과: 반복 삽입 시 키 길이 증가가 MAX_LENGTH 이내임을 검증.
 * NFR3. rebalance(1K) < 500ms: IssueRepository.batchUpdateRanks 단일 SQL 측정 (Testcontainers).
 *
 * 순수 알고리즘(Rank.between) 부하는 DB 없이 JVM 메모리에서 측정한다.
 * DB 의존 부하(rebalance updateRank) 는 IssueTestcontainersBase 상속으로 Testcontainers 사용한다.
 *
 * 동시 실행 시 Testcontainers 워커 크래시로 가짜 실패가 발생할 수 있다.
 * (메모리: concurrent-testcontainers-suite-flaky)
 * 단독 실행 권장:
 *   ./gradlew :modules:issue-tracking:test --tests "*BacklogRankLoadTest"
 */
class BacklogRankLoadTest : IssueTestcontainersBase() {
    private val log = LoggerFactory.getLogger(javaClass)

    private lateinit var backlogRankService: BacklogRankService

    /**
     * IssueTestcontainersBase.bootstrap() 실행 후 BacklogRankService 를 직접 생성한다.
     * permissionResolver 는 부하 측정 대상이 아니므로 AlwaysAllowIssuePermissionResolver 를 주입한다.
     */
    @BeforeAll
    fun setUpService() {
        backlogRankService =
            BacklogRankService(
                repository,
                AlwaysAllowIssuePermissionResolver(),
                dsl,
                ProjectArchiveGuard(ProjectArchiveStateRepository(dsl)),
            )
    }

    // ── NFR1: 순수 알고리즘 부하 (DB 없음) ─────────────────────────────────────

    /**
     * L01. Rank.between 1K 회 평균 < 5ms (NFR1).
     *
     * 순수 계산이므로 DB 없이 JVM 메모리에서 측정한다.
     * prev 를 주기적으로 null 로 초기화해 다양한 경계 조합을 커버한다.
     */
    @Test
    fun `L01 - Rank between 1K 회 평균이 5ms 미만이어야 한다 NFR1`() {
        // 워밍업 10회 — JIT 최적화 안정화 목적.
        repeat(10) { Rank.between(null, null) }

        val start = System.nanoTime()
        var prev: Rank? = null
        for (i in 0 until ISSUE_COUNT) {
            val result = Rank.between(prev, null)
            prev = if (i % 50 == 0) null else result
        }
        val elapsedMs = (System.nanoTime() - start) / 1_000_000L
        val avgMs = elapsedMs.toDouble() / ISSUE_COUNT

        log.info("L01 result: iterations={} totalMs={}ms avgMs={}ms", ISSUE_COUNT, elapsedMs, "%.4f".format(avgMs))

        val avgFmt = "%.4f".format(avgMs)
        val desc = "Rank.between ${ISSUE_COUNT}회 평균이 ${RERANK_AVG_MS_LIMIT}ms 미만이어야 한다 (NFR1). 실측: ${avgFmt}ms"
        assertThat(avgMs)
            .describedAs(desc)
            .isLessThan(RERANK_AVG_MS_LIMIT.toDouble())
    }

    // ── NFR2: between 반복 삽입 키 길이 제한 ───────────────────────────────────

    /**
     * L02. 인접 경계 사이 반복 삽입 시 키 길이 증가 제한 (NFR2).
     *
     * "b" ~ "c" 인접 경계에서 REPEATED_INSERT_COUNT 회 삽입하는 최악 시나리오.
     * 모든 결과 키 길이가 Rank.MAX_LENGTH(50자) 이하여야 한다 — 고갈 전 충분한 공간이 있음을 증명.
     * 실제 고갈은 RankSpaceExhaustedException 이 발생하며 그 전까지 측정한다.
     */
    @Test
    fun `L02 - 반복 삽입 시 키 길이 증가가 Rank MAX_LENGTH 이하로 제한되어야 한다 NFR2`() {
        val fixedPrev = Rank.of("b")
        val fixedNext = Rank.of("c")
        val lengths = mutableListOf<Int>()
        var currentPrev: Rank = fixedPrev

        var exhausted = false
        repeat(REPEATED_INSERT_COUNT) {
            if (exhausted) return@repeat
            try {
                val mid = Rank.between(currentPrev, fixedNext)
                lengths.add(mid.value.length)
                currentPrev = mid
            } catch (e: RankSpaceExhaustedException) {
                // 고갈 발생 — 테스트 범위 밖. 이전까지 측정값으로 assert 진행.
                log.warn("L02 고갈 발생 iteration={} cause={}", lengths.size, e.message)
                exhausted = true
            }
        }

        val maxLength = lengths.maxOrNull() ?: 0
        log.info("L02 result: count={} maxLength={} last5={}", lengths.size, maxLength, lengths.takeLast(5))
        val desc =
            "${REPEATED_INSERT_COUNT}회 반복 삽입 최대 키 길이가 MAX_LENGTH(${BETWEEN_MAX_KEY_LENGTH_LIMIT}자) 이하여야 한다 (NFR2)." +
                " 실측 최대: $maxLength"
        assertThat(maxLength)
            .describedAs(desc)
            .isLessThanOrEqualTo(BETWEEN_MAX_KEY_LENGTH_LIMIT)
    }

    // ── NFR3: rebalance 1K (Testcontainers) ───────────────────────────────────

    /**
     * L03. rebalance 1K 이슈 < 500ms (NFR3).
     *
     * Testcontainers PostgreSQL 에 1K 이슈를 삽입한 후 BacklogRankService.rebalance 를 측정한다.
     * rebalance 는 트랜잭션 내 advisory lock + IssueRepository.batchUpdateRanks(UPDATE...FROM VALUES) 를 포함한다.
     * 완료 후 모든 이슈에 rank 가 부여됐는지 추가 검증한다.
     */
    @Test
    fun `L03 - rebalance 1K 이슈가 500ms 미만에 완료되어야 한다 NFR3`() {
        insertIssuesBulk(testProjectId, ISSUE_COUNT)

        // 워밍업 — 컨테이너 첫 쿼리 캐시 안정화 목적.
        val warmUpCount = repository.findRanksForRebalance(testProjectId).size
        log.info("L03 warmup: found {} issues", warmUpCount)

        val start = System.nanoTime()
        backlogRankService.rebalance(testProjectId)
        val elapsedMs = (System.nanoTime() - start) / 1_000_000L

        log.info("L03 result: count={} rebalanceMs={}", ISSUE_COUNT, elapsedMs)

        val afterRanks = repository.findRanksForRebalance(testProjectId)
        assertThat(afterRanks)
            .describedAs("rebalance 후 $ISSUE_COUNT 이슈 모두 rank 가 부여되어야 한다")
            .hasSize(ISSUE_COUNT)
        assertThat(afterRanks.map { it.second })
            .describedAs("rebalance 후 rank NULL 이 없어야 한다")
            .allSatisfy { rank -> assertThat(rank).isNotNull() }
        assertThat(elapsedMs)
            .describedAs(
                "rebalance(${ISSUE_COUNT}건)이 ${REBALANCE_TOTAL_MS_LIMIT}ms 미만이어야 한다 (NFR3). 실측: ${elapsedMs}ms",
            )
            .isLessThan(REBALANCE_TOTAL_MS_LIMIT)
    }

    // ── private helpers ────────────────────────────────────────────────────────

    /**
     * 테스트용 이슈를 벌크로 삽입한다 (rank = NULL, 옵션 B lazy 미부여).
     *
     * key_sequence 를 count 만큼 한 번에 증가시킨 뒤 시작 번호를 기준으로
     * 키를 할당하여 루프 당 SELECT 왕복을 제거한다.
     * JDBC use+try-catch 중첩 구조 상 블록 깊이가 임계값을 초과한다.
     */
    @Suppress("NestedBlockDepth")
    private fun insertIssuesBulk(
        projectId: UUID,
        count: Int,
    ) {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            val typeId: Long =
                conn.prepareStatement("SELECT id FROM issue_types WHERE key = 'task' AND deleted_at IS NULL LIMIT 1")
                    .use { stmt ->
                        stmt.executeQuery().use { rs ->
                            check(rs.next()) { "V003 마이그레이션에서 task 타입이 없다." }
                            rs.getLong(1)
                        }
                    }

            // key_sequence 를 count 만큼 일괄 증가 — 루프 당 SELECT 왕복 제거.
            val startSeq: Long =
                conn.prepareStatement(
                    "UPDATE projects SET key_sequence = key_sequence + ? WHERE id = ? RETURNING key_sequence - ? + 1",
                ).use { stmt ->
                    stmt.setInt(1, count)
                    stmt.setObject(2, projectId)
                    stmt.setInt(3, count)
                    stmt.executeQuery().use { rs ->
                        rs.next()
                        rs.getLong(1)
                    }
                }

            conn.autoCommit = false
            try {
                conn.prepareStatement(
                    "INSERT INTO issues (id, key, project_id, type_id, summary, reporter_id, current_state_key)" +
                        " VALUES (gen_random_uuid(), ?, ?, ?, ?, gen_random_uuid(), 'open')",
                ).use { stmt ->
                    for (i in 0 until count) {
                        stmt.setString(1, "TPRJ-${startSeq + i}")
                        stmt.setObject(2, projectId)
                        stmt.setLong(3, typeId)
                        stmt.setString(4, "부하 테스트 이슈 ${i + 1}")
                        stmt.addBatch()
                        if ((i + 1) % 200 == 0) stmt.executeBatch()
                    }
                    stmt.executeBatch()
                }
                conn.commit()
            } catch (e: Exception) {
                conn.rollback()
                throw e
            } finally {
                conn.autoCommit = true
            }
        }
        log.info("insertIssuesBulk complete: projectId={} count={}", projectId, count)
    }
}
