// 규칙 하이드레이션의 SQL 실행 횟수를 세어 N+1 회귀를 막는 가드 — 절대 시간이 아니라 왕복 수를 본다
package com.bts.automation.adapter

import com.bts.automation.AutomationTestcontainersBase
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.lang.reflect.Proxy
import java.sql.Connection
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource

/**
 * 하이드레이션 N+1 회귀 가드 (FR-AT-04 성능 · 2026-09-09).
 *
 * ## 왜 시간이 아니라 쿼리 수인가
 *
 * 종전 가드는 [com.bts.automation.integration.RuleConflictAnalysisIntegrationTest] 의
 * **성능 스모크**(절대 시간 5,000ms) 하나뿐이었고, 그것이 두 가지를 못 했다.
 *
 * 1. **상한이 NFR 의 5배였다.** 제품 NFR 은 「규칙 충돌 정적 분석 1초」이고 `#268` 실측이
 *    0.876s 였는데 상한은 5s 다 — **5배 후퇴해도 초록**이라 그 구간이 감시 밖이었다.
 * 2. **머신이 바뀌면 의미가 증발했다.** 같은 코드가 맥 0.876s · 2코어 리눅스 12.583s 다.
 *    「3~5배 여유」가 **어느 기계 기준인지** 없어 환경이 바뀌자 계약이 사라졌다.
 *
 * 쿼리 수는 **머신과 무관하다.** 규칙이 3개든 30개든 왕복이 일정하면 N+1 이 없는 것이고,
 * 규칙 수에 비례해 늘면 있는 것이다. 느린 기계에서도 같은 판정을 낸다.
 *
 * 선례는 `issue-tracking` 의 `BoardCardQueryCountTest`(카드 10배에도 쿼리 수 불변)다.
 * 그쪽은 jOOQ `ExecuteListener` 를 쓰지만 automation 은 [NamedParameterJdbcTemplate] 이라
 * [DataSource] 를 감싸 `prepareStatement` 호출을 세는 방식으로 같은 일을 한다.
 *
 * ## 이 가드는 성능 스모크를 대체하지 않는다
 *
 * 쿼리 수는 **왕복 횟수**만 본다. 쿼리 하나가 느려지는 회귀(인덱스 소실 등)는 못 잡는다.
 * 보는 것이 달라 둘 다 있어야 한다.
 */
class HydrationQueryCountTest {
    /**
     * `prepareStatement` 호출 횟수를 세는 [DataSource] 래퍼.
     *
     * 커넥션을 프록시로 감싸 통째로 위임하되 `prepareStatement` 만 가로채 센다 —
     * 다른 메서드의 동작은 바꾸지 않는다.
     */
    private class CountingDataSource(
        private val delegate: DataSource,
    ) : DataSource by delegate {
        private val count = AtomicInteger(0)

        override fun getConnection(): Connection = wrap(delegate.connection)

        override fun getConnection(
            username: String?,
            password: String?,
        ): Connection = wrap(delegate.getConnection(username, password))

        private fun wrap(conn: Connection): Connection =
            Proxy.newProxyInstance(
                Connection::class.java.classLoader,
                arrayOf(Connection::class.java),
            ) { _, method, args ->
                if (method.name == "prepareStatement") count.incrementAndGet()
                if (args == null) method.invoke(conn) else method.invoke(conn, *args)
            } as Connection

        fun measure(block: () -> Unit): Int {
            count.set(0)
            block()
            return count.get()
        }
    }

    @Test
    fun `하이드레이션 쿼리 수는 규칙 건수와 무관하게 일정하다 (N+1 회귀 가드)`() {
        val postgres = AutomationTestcontainersBase.postgres
        val counting =
            CountingDataSource(
                DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password),
            )
        val jdbc = NamedParameterJdbcTemplate(counting)
        val actions = AutomationActionRepository(jdbc, ObjectMapper())
        val conditions = AutomationConditionRepository(jdbc)

        // 실제 행이 없어도 된다 — 이 가드가 보는 것은 **결과**가 아니라 **왕복 횟수**다.
        val few = List(3) { UUID.randomUUID() }
        val many = List(30) { UUID.randomUUID() }

        val fewQueries =
            counting.measure {
                actions.findByRuleIds(few)
                conditions.findByRuleIds(few)
            }
        val manyQueries =
            counting.measure {
                actions.findByRuleIds(many)
                conditions.findByRuleIds(many)
            }

        assertThat(manyQueries)
            .describedAs(
                "규칙이 3건 → 30건으로 늘어도 쿼리 수는 그대로여야 한다 (N+1 회귀 가드).\n" +
                    "늘었다면 하이드레이션이 규칙마다 조회하고 있다 — 2코어 VM 에서 100규칙 2,331ms 가\n" +
                    "그 상태의 실측이었고, 배치로 바꿔 1,363ms 가 됐다.",
            ).isEqualTo(fewQueries)

        // ★양성 대조군. 두 리포지토리가 각각 1회 = 2회여야 한다. 0 이면 계측기가 고장난 것이고,
        //   그때 위 단언은 「0 == 0」으로 언제나 통과하는 공허한 가드가 된다.
        assertThat(fewQueries)
            .describedAs("계측기가 쿼리를 하나도 못 셌다 — 위 단언이 공허해진다")
            .isEqualTo(2)
    }

    @Test
    fun `빈 입력은 DB 를 아예 치지 않는다`() {
        val postgres = AutomationTestcontainersBase.postgres
        val counting =
            CountingDataSource(
                DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password),
            )
        val jdbc = NamedParameterJdbcTemplate(counting)

        val queries =
            counting.measure {
                AutomationActionRepository(jdbc, ObjectMapper()).findByRuleIds(emptyList())
                AutomationConditionRepository(jdbc).findByRuleIds(emptyList())
            }

        assertThat(queries)
            .describedAs("빈 목록에 `IN ()` 를 보내면 SQL 문법 오류다 — 호출 전에 걸러야 한다")
            .isZero()
    }
}
