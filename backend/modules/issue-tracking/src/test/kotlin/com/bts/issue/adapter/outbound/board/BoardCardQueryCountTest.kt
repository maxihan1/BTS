// 보드 카드 조회의 SQL 실행 횟수를 세어 N+1 회귀를 막는 가드 — FR-UX-14 B2 정본 지정 성공 판정식

package com.bts.issue.adapter.outbound.board

import com.bts.issue.domain.ActorId
import com.bts.issue.domain.Issue
import com.bts.issue.domain.IssueId
import com.bts.issue.domain.IssueKey
import com.bts.issue.repository.IssueRepository
import com.bts.issue.repository.IssueTestcontainersBase
import com.bts.shared.issue.IssueTypeId
import com.bts.shared.permission.IssueSecurityAccess
import org.assertj.core.api.Assertions.assertThat
import org.jooq.ExecuteContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.jooq.impl.DefaultConfiguration
import org.jooq.impl.DefaultExecuteListener
import org.jooq.impl.DefaultExecuteListenerProvider
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * 보드 카드 조회 N+1 회귀 가드 (FR-UX-14 B2).
 *
 * FR-UX-14 정본이 지정한 **성공 판정식**이다 — 라벨은 `TEXT[]` 컬럼이라 조인이 없고 유형은
 * 단일 N:1 조인이므로, **카드 건수가 늘어도 실행되는 SQL 문 수가 변하지 않아야** 한다.
 *
 * ### 왜 별도 파일인가
 *
 * [BoardIssueLookupAdapterTest] 는 가시성·매핑을 보고, 이 클래스는 **쿼리 개수**만 본다.
 * 성격이 달라 섞으면 읽기 어렵고, 같은 파일이면 병렬 실행에서 서로를 막는다.
 *
 * ### 계측 방법
 *
 * [IssueTestcontainersBase] 가 `IssueRepository(dsl)` 로 **생성자 주입**하므로,
 * 리스너를 붙인 별도 `Configuration` 으로 계측 전용 저장소를 하나 더 만들어 쓴다.
 * 컨테이너는 재사용한다(새로 띄우지 않는다).
 *
 * ### 이 가드가 공허하지 않다는 확인
 *
 * 어댑터 매핑을 일부러 망가뜨려 [BoardIssueLookupAdapterTest] 의 D1 이 FAILED 가 되는 것을
 * 확인했고(2026-08-07), 이 클래스도 같은 방식으로 카드당 추가 조회를 넣으면 빨간불이 된다.
 * **도달 불가능한 상태를 지키는 테스트는 초록인 채 아무것도 재지 않는다.**
 */
class BoardCardQueryCountTest : IssueTestcontainersBase() {
    /** 실행된 SQL 문 수를 세는 jOOQ 리스너 — 이 가드 전용. */
    private class QueryCountListener : DefaultExecuteListener() {
        val count = AtomicInteger(0)

        override fun executeStart(ctx: ExecuteContext) {
            count.incrementAndGet()
        }
    }

    private fun unrestricted() =
        IssueSecurityAccess(
            unrestricted = true,
            staticLevelIds = emptySet(),
            reporterLevelIds = emptySet(),
            assigneeLevelIds = emptySet(),
        )

    /** `issue_types.key` 로 타입 id 를 해석한다 (V003 표준 5종). */
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
     * 이슈를 [count] 건 넣는다.
     *
     * key 충돌을 피하려고 [startSeq] 부터 시작한다 — 같은 JVM 싱글턴 컨테이너를 다른 테스트
     * 클래스와 공유하므로 낮은 seq 는 이미 쓰였을 수 있다.
     * 유형을 번갈아 넣어 조인이 단일 유형에서만 도는 상황을 만들지 않는다.
     */
    private fun seedIssues(
        startSeq: Long,
        count: Int,
    ) {
        val types = listOf("bug", "story", "task")
        repeat(count) { i ->
            repository.insert(
                Issue.create(
                    id = IssueId(UUID.randomUUID()),
                    key = IssueKey.of("TPRJ", startSeq + i),
                    projectId = testProjectId,
                    typeId = resolveTypeId(types[i % types.size]),
                    summary = "query count card ${startSeq + i}",
                    reporterId = ActorId(UUID.randomUUID()),
                    currentStateKey = "open",
                    priority = 3,
                    labels = listOf("l${i % 2}"),
                ),
            )
        }
    }

    /** 계측 전용 저장소로 보드 조회를 1회 수행하고 실행된 SQL 문 수를 돌려준다. */
    private fun countQueriesForBoardFetch(actor: UUID): Int {
        val listener = QueryCountListener()
        val dataSource =
            org.springframework.jdbc.datasource.DriverManagerDataSource(
                postgres.jdbcUrl,
                postgres.username,
                postgres.password,
            )
        val countingDsl =
            DSL.using(
                DefaultConfiguration()
                    .set(dataSource)
                    .set(SQLDialect.POSTGRES)
                    .set(DefaultExecuteListenerProvider(listener)),
            )

        IssueRepository(countingDsl).listVisibleForBoard("TPRJ", actor, unrestricted())

        return listener.count.get()
    }

    @Test
    fun `보드 카드 조회 쿼리 수는 카드 건수와 무관하게 일정하다`() {
        val actor = UUID.randomUUID()

        seedIssues(startSeq = 9_000, count = 3)
        val withFewCards = countQueriesForBoardFetch(actor)

        // 카드를 10배로 늘린다. 카드당 추가 조회가 있으면 여기서 쿼리 수가 벌어진다.
        seedIssues(startSeq = 9_100, count = 30)
        val withManyCards = countQueriesForBoardFetch(actor)

        assertThat(withFewCards)
            .describedAs("보드 조회는 단일 쿼리여야 한다 — 카드 3건 기준")
            .isEqualTo(1)
        assertThat(withManyCards)
            .describedAs("카드가 33건으로 늘어도 쿼리 수는 그대로여야 한다 (N+1 회귀 가드)")
            .isEqualTo(withFewCards)
    }
}
