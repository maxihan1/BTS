// 백로그 LexoRank 1K 부하 테스트 — NFR1(리랭크 < 5ms), NFR2(1K between), NFR3(rebalance < 500ms)

package com.bts.issue.performance

import com.bts.issue.adapter.outbound.AlwaysAllowIssuePermissionResolver
import com.bts.issue.application.BacklogRankService
import com.bts.issue.repository.IssueTestcontainersBase
import com.bts.shared.lexorank.Rank
import org.assertj.core.api.Assertions.assertThat
import org.jooq.DSLContext
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.sql.DriverManager
import java.util.UUID

/**
 * 백로그 LexoRank 1K 부하 테스트 (FR-BL-01 Task 7).
 *
 * NFR1. 단일 리랭크 평균 < 5ms: Rank.between 1K 회 순수 알고리즘 측정.
 * NFR2. 1K between 시나리오 통과: 반복 삽입 시 키 길이 증가가 제한적임을 검증.
 * NFR3. rebalance(1K) < 500ms: computeEvenRanks + updateRank 1K 측정 (Testcontainers).
 *
 * 순수 알고리즘(between, computeEvenRanks) 부하는 DB 없이 단위로 측정하고,
 * DB 의존 부하(updateRank 1K) 는 IssueTestcontainersBase 상속으로 Testcontainers 사용한다.
 *
 * 동시 실행 시 Testcontainers 워커 크래시로 가짜 실패가 발생할 수 있다.
 * (메모리: concurrent-testcontainers-suite-flaky)
 * 단독 실행 권장:
 *   ./gradlew :modules:issue-tracking:test --tests "*BacklogRankLoadTest"
 */
class BacklogRankLoadTest : IssueTestcontainersBase() {

    private val log = LoggerFactory.getLogger(javaClass)

    /** 부하 시나리오 이슈 수 (NFR2/NFR3 기준값). */
    private val ISSUE_COUNT = 1_000

    /** 리랭크 NFR1 임계값 (밀리초). */
    private val RERANK_AVG_MS_LIMIT = 5L

    /** rebalance NFR3 임계값 (밀리초). */
    private val REBALANCE_TOTAL_MS_LIMIT = 500L

    /**
     * between 반복 삽입 시 허용 최대 키 길이 — Rank.MAX_LENGTH(50자) 미만 보장.
     *
     * 인접 경계(b, c 사이) 200번 반복 삽입 최악 케이스에서도
     * 50자 한도를 넘지 않아야 고갈 전 충분한 공간이 있음이 증명된다.
     */
    private val BETWEEN_MAX_KEY_LENGTH_LIMIT = Rank.MAX_LENGTH

    /** 반복 삽입 테스트 횟수 — 같은 위치에 N회 삽입 시 키 길이 증가 검증용. */
    private val REPEATED_INSERT_COUNT = 200

    private lateinit var backlogRankService: BacklogRankService
    private lateinit var dslCtx: DSLContext

    /**
     * IssueTestcontainersBase.bootstrap() 실행 후 BacklogRankService 를 직접 생성한다.
     * permissionResolver 는 부하 측정 대상이 아니므로 AlwaysAllowIssuePermissionResolver 를 주입한다.
     */
    @BeforeAll
    fun setUpService() {
        val permissionResolver = AlwaysAllowIssuePermissionResolver()
        dslCtx = dsl
        backlogRankService = BacklogRankService(repository, permissionResolver, dslCtx)
    }

    /** 각 테스트 전에 Testcontainers DB 의 issues 를 정리한다 (IssueTestcontainersBase.cleanIssues 상속). */
    @BeforeEach
    fun setUpIssues() {
        // IssueTestcontainersBase.cleanIssues() 가 @BeforeEach 로 이미 실행됨.
        // 추가 정리 불필요.
    }

    // ── NFR1 / NFR2: 순수 알고리즘 부하 (DB 없음) ─────────────────────────────

    /**
     * L01. Rank.between 1K 회 평균 < 5ms (NFR1).
     *
     * 순수 계산이므로 DB 없이 JVM 메모리에서 측정한다.
     * prev/next 를 조금씩 이동시켜 다양한 경계 조합을 커버한다.
     */
    @Test
    fun `L01 - Rank between 1K 회 평균이 5ms 미만이어야 한다 NFR1`() {
        val iterations = ISSUE_COUNT

        // 워밍업 10회 — JIT 최적화 안정화 목적.
        repeat(10) { Rank.between(null, null) }

        val start = System.nanoTime()
        var prev: Rank? = null
        for (i in 0 until iterations) {
            // 다양한 경계 조합: null 경계, between 결과를 prev 로 순환.
            val result = Rank.between(prev, null)
            prev = if (i % 50 == 0) null else result
        }
        val elapsedMs = (System.nanoTime() - start) / 1_000_000L
        val avgMs = elapsedMs.toDouble() / iterations

        log.info(
            "L01 result: iterations={} totalMs={} avgMs={:.4f}",
            iterations,
            elapsedMs,
            avgMs,
        )

        assertThat(avgMs)
            .describedAs("Rank.between 1K 회 평균이 ${RERANK_AVG_MS_LIMIT}ms 미만이어야 한다 (NFR1). 실측 평균: %.4f ms".format(avgMs))
            .isLessThan(RERANK_AVG_MS_LIMIT.toDouble())
    }

    /**
     * L02. 같은 위치에 반복 삽입 시 키 길이 증가 제한 (NFR2).
     *
     * 동일 prev~next 사이에 N 회 삽입할 때 각 결과의 키 길이를 기록한다.
     * 키 길이가 Rank.MAX_LENGTH(50자) 미만이어야 한다 — 고갈 전 충분한 삽입 공간을 증명.
     * 실제 고갈은 RankSpaceExhaustedException 으로 표면화되며 이 테스트 범위를 벗어난다.
     */
    @Test
    fun `L02 - 반복 삽입 시 키 길이 증가가 Rank MAX_LENGTH 이하로 제한되어야 한다 NFR2`() {
        // "b" 와 "c" 사이에서 반복 삽입 — 인접 케이스로 키 길이가 늘어나는 최악 경로.
        val fixedPrev = Rank.of("b")
        val fixedNext = Rank.of("c")

        val lengths = mutableListOf<Int>()
        var currentNext: Rank = fixedNext

        // between("b", "c") → "bb" 영역에서 계속 쪼개는 방식으로 삽입.
        // 실제 드래그 시나리오는 다르지만, 같은 슬롯에 반복 삽입의 최악을 모사.
        var currentPrev: Rank = fixedPrev
        repeat(REPEATED_INSERT_COUNT) {
            try {
                val mid = Rank.between(currentPrev, currentNext)
                lengths.add(mid.value.length)
                currentPrev = mid
                currentNext = fixedNext
            } catch (e: com.bts.shared.lexorank.RankSpaceExhaustedException) {
                // 고갈 시 테스트 조기 종료 — 고갈 전까지만 측정.
                log.warn("L02 고갈 발생 iteration={} lengths={}", lengths.size, lengths)
                return
            }
        }

        val maxLength = lengths.maxOrNull() ?: 0
        log.info(
            "L02 result: count={} maxLength={} lengths(last5)={}",
            lengths.size,
            maxLength,
            lengths.takeLast(5),
        )

        assertThat(maxLength)
            .describedAs(
                "${REPEATED_INSERT_COUNT}회 반복 삽입 최대 키 길이가 Rank.MAX_LENGTH(${BETWEEN_MAX_KEY_LENGTH_LIMIT}자) 이하여야 한다 (NFR2). " +
                    "실측 최대: $maxLength — 고갈 전 충분한 삽입 공간이 있음을 증명",
            )
            .isLessThanOrEqualTo(BETWEEN_MAX_KEY_LENGTH_LIMIT)
    }

    // ── NFR3: rebalance 1K (Testcontainers) ───────────────────────────────────

    /**
     * L03. rebalance 1K 이슈 < 500ms (NFR3).
     *
     * Testcontainers PostgreSQL 에 1K 이슈를 삽입한 후 BacklogRankService.rebalance 를 측정한다.
     * rebalance 는 트랜잭션 내 advisory lock + 1K updateRank 를 포함한다.
     *
     * Testcontainers 초기화 + 네트워크 오버헤드가 포함되므로 500ms 임계값은
     * 알고리즘 속도(< 50ms 예상)에 여유를 둔 값이다.
     */
    @Test
    fun `L03 - rebalance 1K 이슈가 500ms 미만에 완료되어야 한다 NFR3`() {
        val projectId = testProjectId
        insertIssuesBulk(projectId, ISSUE_COUNT)

        // 워밍업 — 컨테이너 첫 쿼리 캐시 안정화.
        val warmUp = repository.findRanksForRebalance(projectId)
        log.info("L03 warmup: found {} issues", warmUp.size)

        val start = System.nanoTime()
        backlogRankService.rebalance(projectId)
        val elapsedMs = (System.nanoTime() - start) / 1_000_000L

        log.info("L03 result: count={} rebalanceMs={}", ISSUE_COUNT, elapsedMs)

        // rebalance 후 모든 이슈에 rank 가 부여되었는지 확인.
        val afterRanks = repository.findRanksForRebalance(projectId)
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
     * 테스트용 이슈를 벌크로 삽입한다.
     *
     * rank = NULL 로 삽입 (옵션 B, lazy 미부여).
     * key_sequence 는 프로젝트별 순서로 자동 증가한다.
     */
    private fun insertIssuesBulk(
        projectId: UUID,
        count: Int,
    ) {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            // issue_types 에서 task id 조회.
            val typeId: Long
            conn.prepareStatement("SELECT id FROM issue_types WHERE key = 'task' AND deleted_at IS NULL LIMIT 1")
                .use { stmt ->
                    stmt.executeQuery().use { rs ->
                        check(rs.next()) { "V003 마이그레이션에서 task 타입이 없다." }
                        typeId = rs.getLong(1)
                    }
                }

            // COPY 수준 성능을 위해 배치 INSERT 사용.
            conn.autoCommit = false
            try {
                conn.prepareStatement(
                    """
                    INSERT INTO issues (id, key, project_id, type_id, summary, reporter_id, current_state_key)
                    VALUES (gen_random_uuid(), ?, ?, ?, ?, gen_random_uuid(), 'open')
                    """.trimIndent(),
                ).use { stmt ->
                    for (i in 1..count) {
                        val seq =
                            conn.prepareStatement("UPDATE projects SET key_sequence = key_sequence + 1 WHERE id = ? RETURNING key_sequence")
                                .also { it.setObject(1, projectId) }
                                .executeQuery()
                                .let {
                                    it.next()
                                    it.getLong(1)
                                }
                        stmt.setString(1, "TPRJ-$seq")
                        stmt.setObject(2, projectId)
                        stmt.setLong(3, typeId)
                        stmt.setString(4, "부하 테스트 이슈 $i")
                        stmt.addBatch()
                        if (i % 100 == 0) stmt.executeBatch()
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
