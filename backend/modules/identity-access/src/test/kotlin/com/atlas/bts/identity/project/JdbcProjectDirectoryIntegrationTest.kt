// ProjectDirectory 포트 통합 테스트 — exists() 존재·소프트삭제·미존재 3케이스 검증

package com.atlas.bts.identity.project

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID

/**
 * JdbcProjectDirectory 통합 테스트 (FR-PM-01 Task 4).
 *
 * ## 목적
 * identity-access BC 내에서 cross-BC projects 테이블을 read-only로 조회하는
 * ProjectDirectory 포트 구현체의 exists() 메서드를 실제 PostgreSQL로 검증한다.
 *
 * ## 테스트 환경
 * - `@JdbcTest` — DataSource + JdbcTemplate 슬라이스만 로드. Spring Security 필터 없음.
 * - Testcontainers PostgreSQL 16 — Flyway V001~V006 자동 마이그레이션 적용.
 * - projects 테이블은 issue-tracking 소유라 identity-access Flyway에 없음.
 *   setUp()에서 `CREATE TABLE IF NOT EXISTS projects (id UUID PRIMARY KEY, deleted_at TIMESTAMPTZ)`
 *   를 직접 생성하고 테스트 종료 후 DROP.
 *
 * ## 검증 시나리오
 * | 케이스 | 조건 | 기대값 |
 * |---|---|---|
 * | 존재 | projects 행 있음 + deleted_at IS NULL | true |
 * | 소프트삭제 | deleted_at 설정됨 | false |
 * | 미존재 | 해당 UUID 행 없음 | false |
 */
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JdbcProjectDirectory::class)
@Testcontainers
class JdbcProjectDirectoryIntegrationTest {

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer("postgres:16-alpine")
                .withDatabaseName("bts_test")
                .withUsername("bts")
                .withPassword("bts_test")

        @DynamicPropertySource
        @JvmStatic
        fun postgresProps(r: DynamicPropertyRegistry) {
            r.add("spring.datasource.url") { postgres.jdbcUrl }
            r.add("spring.datasource.username") { postgres.username }
            r.add("spring.datasource.password") { postgres.password }
            r.add("spring.flyway.enabled") { "true" }
        }
    }

    @Autowired
    private lateinit var projectDirectory: JdbcProjectDirectory

    @Autowired
    private lateinit var jdbc: NamedParameterJdbcTemplate

    @BeforeEach
    fun setUp() {
        // projects 테이블은 issue-tracking 소유 — identity-access Flyway에 없으므로 직접 생성.
        // id + deleted_at 두 컬럼만 의존 (ADR D2: cross-BC는 최소 컬럼만 의존).
        jdbc.jdbcTemplate.execute(
            """
            CREATE TABLE IF NOT EXISTS projects (
                id UUID PRIMARY KEY,
                deleted_at TIMESTAMPTZ
            )
            """.trimIndent(),
        )
        jdbc.update("DELETE FROM projects", emptyMap<String, Any>())
    }

    @Test
    fun `exists — 행 존재하고 deleted_at IS NULL이면 true를 반환한다`() {
        val projectId = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO projects (id, deleted_at) VALUES (:id, NULL)",
            mapOf("id" to projectId),
        )

        assertThat(projectDirectory.exists(projectId)).isTrue()
    }

    @Test
    fun `exists — deleted_at이 설정된 소프트삭제 행이면 false를 반환한다`() {
        val projectId = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO projects (id, deleted_at) VALUES (:id, NOW())",
            mapOf("id" to projectId),
        )

        assertThat(projectDirectory.exists(projectId)).isFalse()
    }

    @Test
    fun `exists — 해당 UUID 행이 존재하지 않으면 false를 반환한다`() {
        val absentId = UUID.randomUUID()

        assertThat(projectDirectory.exists(absentId)).isFalse()
    }
}
