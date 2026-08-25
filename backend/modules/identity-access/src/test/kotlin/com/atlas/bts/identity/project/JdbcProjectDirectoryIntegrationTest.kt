// ProjectDirectory 포트 통합 테스트 — exists() 존재·소프트삭제·미존재 + resolveKeyToId() 3케이스 검증

package com.atlas.bts.identity.project

import com.atlas.bts.identity.support.SharedPostgres
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
 * | 케이스 | 메서드 | 조건 | 기대값 |
 * |---|---|---|---|
 * | 존재 | exists | projects 행 있음 + deleted_at IS NULL | true |
 * | 소프트삭제 | exists | deleted_at 설정됨 | false |
 * | 미존재 | exists | 해당 UUID 행 없음 | false |
 * | key 조회 성공 | resolveKeyToId | key 행 있음 + deleted_at IS NULL | 해당 UUID |
 * | key 소프트삭제 | resolveKeyToId | deleted_at 설정됨 | null |
 * | key 미존재 | resolveKeyToId | 해당 key 행 없음 | null |
 */
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JdbcProjectDirectory::class)
class JdbcProjectDirectoryIntegrationTest {
    companion object {
        /**
         * 공용 컨테이너의 템플릿 DB 를 복제한 전용 데이터베이스.
         *
         * 격리는 그대로이고 컨테이너 기동과 마이그레이션 재적용만 사라진다.
         * 근거와 주의점은 [com.atlas.bts.identity.support.SharedPostgres] 헤더.
         */
        @JvmStatic
        val postgres = SharedPostgres.freshDatabase()

        @DynamicPropertySource
        @JvmStatic
        fun postgresProps(r: DynamicPropertyRegistry) {
            r.add("spring.datasource.url") { postgres.jdbcUrl }
            r.add("spring.datasource.username") { postgres.username }
            r.add("spring.datasource.password") { postgres.password }
            // 템플릿 DB 에서 이미 적용됐다 — 여기서 다시 돌리면 이 최적화가 무의미해진다
            r.add("spring.flyway.enabled") { "false" }
            // 공용 컨테이너라 커넥션 한도도 공유한다. context 캐시가 쌓이면 기본 풀(10)로는
            // max_connections 를 넘긴다 — SharedPostgres 헤더 참조.
            r.add("spring.datasource.hikari.maximum-pool-size") { SharedPostgres.MAX_POOL_SIZE }
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
                key VARCHAR(10) UNIQUE,
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

    @Test
    fun `resolveKeyToId — key 행이 존재하고 deleted_at IS NULL이면 해당 UUID를 반환한다`() {
        val projectId = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO projects (id, key, deleted_at) VALUES (:id, :key, NULL)",
            mapOf("id" to projectId, "key" to "ATLAS"),
        )

        assertThat(projectDirectory.resolveKeyToId("ATLAS")).isEqualTo(projectId)
    }

    @Test
    fun `resolveKeyToId — deleted_at이 설정된 소프트삭제 key이면 null을 반환한다`() {
        val projectId = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO projects (id, key, deleted_at) VALUES (:id, :key, NOW())",
            mapOf("id" to projectId, "key" to "DELETED"),
        )

        assertThat(projectDirectory.resolveKeyToId("DELETED")).isNull()
    }

    @Test
    fun `resolveKeyToId — 존재하지 않는 key이면 null을 반환한다`() {
        assertThat(projectDirectory.resolveKeyToId("GHOST")).isNull()
    }
}
