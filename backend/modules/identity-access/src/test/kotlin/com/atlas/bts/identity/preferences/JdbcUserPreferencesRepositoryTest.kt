// JdbcUserPreferencesRepository 통합 테스트 — Testcontainers PostgreSQL + Flyway 전체 적용 (FR-PF-01 Task 2)

package com.atlas.bts.identity.preferences

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
 * [JdbcUserPreferencesRepository] 통합 테스트 (FR-PF-01 Task 2).
 *
 * @JdbcTest + Testcontainers PostgreSQL + Flyway 전체 마이그레이션(V001~V031 포함) 자동 적용.
 * `user_preferences.user_id` 는 `users.id` FK 이므로, 각 테스트 전에 users 행을 먼저 INSERT해
 * FK 제약을 충족시킨다(StoredPasswordCredentialRepositoryTest 선례와 동일 패턴).
 */
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JdbcUserPreferencesRepository::class)
class JdbcUserPreferencesRepositoryTest {
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
    @Suppress("VarCouldBeVal") // @Autowired lateinit var 는 val 불가(주입). detekt false-positive 억제
    private lateinit var repo: JdbcUserPreferencesRepository

    @Autowired
    @Suppress("VarCouldBeVal") // @Autowired lateinit var 는 val 불가(주입). detekt false-positive 억제
    private lateinit var jdbc: NamedParameterJdbcTemplate

    private lateinit var userId: UUID

    @BeforeEach
    fun setUp() {
        jdbc.update("DELETE FROM user_preferences", emptyMap<String, Any>())
        jdbc.update("DELETE FROM users", emptyMap<String, Any>())

        // users 픽스처 — user_preferences FK 충족
        userId = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO users (id, username) VALUES (:id, :username)",
            mapOf("id" to userId, "username" to "test-user-$userId"),
        )
    }

    @Test
    fun `findByUserId 없음 — null 반환`() {
        assertThat(repo.findByUserId(userId)).isNull()
    }

    @Test
    fun `upsert 신규 INSERT 후 findByUserId로 조회 가능`() {
        repo.upsert(UserPreferences(userId, "dark", "en", "us"))

        val found = repo.findByUserId(userId)

        assertThat(found).isNotNull()
        assertThat(found!!.userId).isEqualTo(userId)
        assertThat(found.theme).isEqualTo("dark")
        assertThat(found.locale).isEqualTo("en")
        assertThat(found.dateFormat).isEqualTo("us")
    }

    @Test
    fun `upsert 멱등 — 같은 user_id 두 번 호출 시 마지막 값으로 갱신(ON CONFLICT DO UPDATE)`() {
        repo.upsert(UserPreferences(userId, "light", "ko", "iso"))
        repo.upsert(UserPreferences(userId, "dark", "en", "kr"))

        val found = repo.findByUserId(userId)

        assertThat(found).isNotNull()
        assertThat(found!!.theme).isEqualTo("dark")
        assertThat(found.locale).isEqualTo("en")
        assertThat(found.dateFormat).isEqualTo("kr")
    }

    @Test
    fun `upsert startPage 지정 시 findByUserId로 왕복`() {
        val prefs = UserPreferences(userId, "dark", "en", "us")
        repo.upsert(prefs.copy(startPage = "my_issues"))

        val found = repo.findByUserId(userId)

        assertThat(found).isNotNull()
        assertThat(found!!.startPage).isEqualTo("my_issues")
    }

    @Test
    fun `upsert startPage 미지정 시 도메인 기본값으로 저장 후 findByUserId로 왕복`() {
        repo.upsert(UserPreferences(userId, "dark", "en", "us"))

        val found = repo.findByUserId(userId)

        assertThat(found).isNotNull()
        assertThat(found!!.startPage).isEqualTo(UserPreferences.DEFAULT_START_PAGE)
    }

    @Test
    fun `users CASCADE 삭제 — users 행 삭제 시 user_preferences 자동 삭제`() {
        repo.upsert(UserPreferences(userId, "dark", "en", "us"))

        jdbc.update("DELETE FROM users WHERE id = :id", mapOf("id" to userId))

        assertThat(repo.findByUserId(userId)).isNull()
    }
}
