// JdbcSlackUserMappingRepository 역방향 조회 통합 테스트 — slack_user_id→user_id fail-closed 검증 (FR-SL-03 Task 4)

package com.bts.slack.persistence

import com.bts.slack.support.SharedPostgres
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.util.UUID
import javax.sql.DataSource

/**
 * [JdbcSlackUserMappingRepository.findUserIdBySlackUserId] 통합 테스트 (FR-SL-03 Task 4).
 *
 * [JdbcSlackInstallRepositoryTest] 와 동형 — Spring 컨텍스트 없이 Testcontainers PostgreSQL 에
 * V700~V702 마이그레이션(V702 = 역방향 조회용 `(slack_user_id, team_id)` UNIQUE 인덱스)을 직접 적용하고
 * [NamedParameterJdbcTemplate] 을 손수 구성해 리포지토리를 직접 생성한다.
 *
 * ## 검증 시나리오
 * - 매핑 존재 → user_id 반환
 * - team_id 불일치 → null
 * - slack_user_id 미존재 → null
 * - **다중행 방어(2차 방어)** — V702 UNIQUE 인덱스(1차 방어)를 테스트 안에서 일시적으로 제거해
 *   같은 (slack_user_id, team_id) 조합으로 두 행을 강제 삽입한 뒤에도 쿼리 로직이 `null` 을 반환함을
 *   확인한다(잘못된 고권한 viewer 임의 선택 차단, `advisory-lock-bigint-toctou`/`crossbc-resolver-nullable-fail-open`
 *   계열 fail-closed 원칙과 동일 사상).
 */
class JdbcSlackUserMappingRepositoryReverseTest {
    companion object {
        /** 공용 컨테이너에서 템플릿을 복제한 이 클래스 전용 DB. */
        @JvmStatic
        val postgres = SharedPostgres.freshDatabase()
    }

    private lateinit var jdbc: NamedParameterJdbcTemplate
    private lateinit var repository: JdbcSlackUserMappingRepository

    @BeforeEach
    fun setUp() {
        val dataSource: DataSource = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        jdbc = NamedParameterJdbcTemplate(dataSource)
        jdbc.update("DELETE FROM user_slack_mapping", emptyMap<String, Any>())
        repository = JdbcSlackUserMappingRepository(jdbc)
    }

    private fun insertMapping(
        userId: UUID,
        slackUserId: String,
        teamId: String,
    ) {
        jdbc.update(
            "INSERT INTO user_slack_mapping (user_id, slack_user_id, team_id) VALUES (:userId, :slackUserId, :teamId)",
            mapOf("userId" to userId, "slackUserId" to slackUserId, "teamId" to teamId),
        )
    }

    // ── 매핑 존재 → user_id 반환 ─────────────────────────────────────────────

    @Test
    fun `findUserIdBySlackUserId — 매핑이 존재하면 user_id 를 반환한다`() {
        val userId = UUID.randomUUID()
        insertMapping(userId, slackUserId = "U0AAA111", teamId = "T_WORKSPACE_A")

        val found = repository.findUserIdBySlackUserId(slackUserId = "U0AAA111", teamId = "T_WORKSPACE_A")

        assertThat(found).isEqualTo(userId)
    }

    // ── team_id 불일치 → null ────────────────────────────────────────────────

    @Test
    fun `findUserIdBySlackUserId — slack_user_id 는 맞지만 team_id 가 다르면 null 을 반환한다`() {
        insertMapping(UUID.randomUUID(), slackUserId = "U0AAA111", teamId = "T_WORKSPACE_A")

        val found = repository.findUserIdBySlackUserId(slackUserId = "U0AAA111", teamId = "T_WORKSPACE_B")

        assertThat(found).isNull()
    }

    // ── 미존재 slack_user_id → null ──────────────────────────────────────────

    @Test
    fun `findUserIdBySlackUserId — 매핑이 존재하지 않으면 null 을 반환한다`() {
        val found = repository.findUserIdBySlackUserId(slackUserId = "U_UNKNOWN", teamId = "T_WORKSPACE_A")

        assertThat(found).isNull()
    }

    // ── 다중행 방어(2차 방어) — UNIQUE 인덱스(1차 방어)를 일시 제거해 강제 재현 ──────

    @Test
    fun `findUserIdBySlackUserId — 같은 slack_user_id+team_id 로 다중행이 존재하면 null 을 반환한다(fail-closed, 임의 선택 금지)`() {
        // V702 UNIQUE 인덱스(1차 방어)를 일시적으로 제거해야만 같은 (slack_user_id, team_id) 조합의
        // 두 번째 행 삽입이 가능하다 — 쿼리 로직(2차 방어)이 인덱스 없이도 독립적으로 방어함을 증명한다.
        jdbc.update("DROP INDEX idx_user_slack_mapping_slack_user", emptyMap<String, Any>())
        try {
            insertMapping(UUID.randomUUID(), slackUserId = "U0DUP000", teamId = "T_WORKSPACE_A")
            insertMapping(UUID.randomUUID(), slackUserId = "U0DUP000", teamId = "T_WORKSPACE_A")

            val found = repository.findUserIdBySlackUserId(slackUserId = "U0DUP000", teamId = "T_WORKSPACE_A")

            assertThat(found).isNull()
        } finally {
            // 다음 테스트(및 이후 실행)를 위해 인덱스를 원복한다 — 테스트 간 스키마 상태 오염 방지.
            // 중복 행을 먼저 정리해야 UNIQUE 인덱스 재생성이 위반 없이 성공한다.
            jdbc.update(
                "DELETE FROM user_slack_mapping WHERE slack_user_id = :slackUserId",
                mapOf("slackUserId" to "U0DUP000"),
            )
            jdbc.update(
                "CREATE UNIQUE INDEX idx_user_slack_mapping_slack_user ON user_slack_mapping (slack_user_id, team_id)",
                emptyMap<String, Any>(),
            )
        }
    }
}
