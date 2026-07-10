// SlackUserMappingService 통합 테스트 — link/unlink/resolveByUserId round-trip (FR-SL-02 Task 5)

package com.bts.slack.application

import com.bts.slack.SlackIntegrationTestBootApplication
import com.bts.slack.SlackTestcontainersConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID

/**
 * [SlackUserMappingService] 통합 테스트 (FR-SL-02 Task 5).
 *
 * [SlackIntegrationTestBootApplication] test-boot 컨텍스트에 [SlackTestcontainersConfig] 를 `@Import`해
 * Testcontainers PostgreSQL(V701 `user_slack_mapping`) 위에서 실 서비스+리포지토리 빈을 검증한다
 * ([com.bts.slack.SlackContextLoadTest] 동일 배선 재사용).
 *
 * ## 검증 시나리오
 * - link → resolveByUserId round-trip: slackUserId·teamId 일치.
 * - 같은 userId 재link(upsert): 최신 slackUserId/teamId 로 갱신되고 행 수는 1개 유지.
 * - unlink 후 resolveByUserId == null.
 * - 미매핑 userId resolveByUserId == null.
 */
@SpringBootTest(
    classes = [SlackIntegrationTestBootApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
)
@Import(SlackTestcontainersConfig::class)
class SlackUserMappingServiceIntegrationTest {
    @Autowired
    private lateinit var service: SlackUserMappingService

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @BeforeEach
    fun cleanUp() {
        jdbcTemplate.update("DELETE FROM user_slack_mapping")
    }

    @Test
    fun `link 후 resolveByUserId 로 slackUserId teamId 가 복원된다`() {
        val userId = UUID.randomUUID()

        service.link(userId, slackUserId = "U0USER1", teamId = "T_WORKSPACE_A")
        val resolved = service.resolveByUserId(userId)

        assertThat(resolved).isNotNull
        assertThat(resolved!!.userId).isEqualTo(userId)
        assertThat(resolved.slackUserId).isEqualTo("U0USER1")
        assertThat(resolved.teamId).isEqualTo("T_WORKSPACE_A")
    }

    @Test
    fun `같은 userId 재link 시 최신 slackUserId teamId 로 갱신되고 행 수는 1개로 유지된다`() {
        val userId = UUID.randomUUID()
        service.link(userId, slackUserId = "U0OLD", teamId = "T_OLD")

        service.link(userId, slackUserId = "U0NEW", teamId = "T_NEW")

        val resolved = service.resolveByUserId(userId)
        assertThat(resolved).isNotNull
        assertThat(resolved!!.slackUserId).isEqualTo("U0NEW")
        assertThat(resolved.teamId).isEqualTo("T_NEW")

        val count =
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_slack_mapping WHERE user_id = ?",
                Int::class.java,
                userId,
            )
        assertThat(count).isEqualTo(1)
    }

    @Test
    fun `unlink 후 resolveByUserId 는 null 을 반환한다`() {
        val userId = UUID.randomUUID()
        service.link(userId, slackUserId = "U0USER1", teamId = "T_WORKSPACE_A")

        service.unlink(userId)

        assertThat(service.resolveByUserId(userId)).isNull()
    }

    @Test
    fun `미매핑 userId 는 resolveByUserId 가 null 을 반환한다`() {
        assertThat(service.resolveByUserId(UUID.randomUUID())).isNull()
    }
}
