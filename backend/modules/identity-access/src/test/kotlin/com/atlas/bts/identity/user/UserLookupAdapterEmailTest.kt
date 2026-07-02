// UserLookupAdapter.resolveByEmails 통합 테스트 — 이메일→userId 일괄 해석 매핑 검증 (FR-IM-01 Task 4)

package com.atlas.bts.identity.user

import com.bts.shared.user.UserLookupPort
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.MapAssert
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID

/**
 * [UserLookupAdapter.resolveByEmails] 통합 테스트 (FR-IM-01 Task 4).
 *
 * ## 목적
 * [UserLookupPort.resolveByEmails] 가 실제 PostgreSQL + Flyway 스키마 위에서
 * 이메일 → userId 매핑을 올바르게 해석하는지 검증한다.
 * users.email 은 nullable 이고 UNIQUE 제약이 없으므로(V001), 다중 매칭 시 fail-safe(결과 제외)
 * 동작을 함께 검증한다.
 *
 * ## 테스트 환경
 * - `@SpringBootTest(RANDOM_PORT)` — 실제 Spring Boot 컨텍스트 전체 구동.
 * - Testcontainers PostgreSQL 16 — Flyway V001 이상 자동 마이그레이션 적용.
 * - 테스트마다 UUID suffix 로 시드 데이터를 격리해 충돌을 방지한다.
 *
 * ## 검증 시나리오
 * | 번호 | 시나리오 | 기대 결과 |
 * |---|---|---|
 * | T-01 | "bob-suffix@x.com"(소문자) 시드 후 "Bob-suffix@X.com"(대문자 포함) 조회 | lower 매칭으로 시드 사용자 id 1건 반환 |
 * | T-02 | 동일 lower(email) 로 2행 시드 후 그 이메일로 조회 | 결과에서 제외(fail-safe — 다중 매칭 드롭) |
 * | T-03 | email=NULL 인 사용자 시드 후 임의 이메일로 조회 | 매칭되지 않음 (IN 절 자연 제외) |
 * | T-04 | 빈 입력 | 빈 맵 (DB 쿼리 생략) |
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "spring.autoconfigure.exclude=" +
            "org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientAutoConfiguration",
    ],
)
@Testcontainers
class UserLookupAdapterEmailTest {
    companion object {
        /** Testcontainers PostgreSQL 16 — Flyway V001 이상 마이그레이션 적용 대상 */
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer("postgres:16-alpine")
                .withDatabaseName("bts_test")
                .withUsername("bts")
                .withPassword("bts_test")

        /**
         * Spring Boot 의 DataSource / Flyway / bts.security 설정을 Testcontainers 좌표로 교체.
         */
        @DynamicPropertySource
        @JvmStatic
        fun configureProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
            registry.add("bts.auth.issuer-uri") { "http://localhost:8090" }
            registry.add("bts.security.cors.allowed-origins") { "http://localhost:5173" }
            registry.add("spring.ldap.urls") { "ldap://localhost:389" }
            registry.add("spring.ldap.base") { "dc=bts,dc=local" }
        }
    }

    @Autowired
    lateinit var userLookupPort: UserLookupPort

    @Autowired
    lateinit var userRepository: UserRepository

    /**
     * T-03 의 email=NULL 사용자 시드용 — [UserRepository.save] 는 email 이 String? 이라 null 을 넘길 수 있지만,
     * NULL 시드임을 테스트에서 명시적으로 드러내기 위해 직접 INSERT 한다 (named parameter 바인딩).
     */
    @Autowired
    lateinit var jdbc: NamedParameterJdbcTemplate

    /** 각 테스트를 서로 격리하기 위한 고유 suffix */
    private lateinit var suffix: String

    @BeforeEach
    fun prepareSuffix() {
        suffix = UUID.randomUUID().toString().take(8)
    }

    /**
     * email=NULL 사용자를 직접 INSERT 한다 (SQL 인젝션 방어: named parameter 바인딩).
     *
     * @return INSERT 된 사용자 UUID
     */
    private fun insertUserWithNullEmail(username: String): UUID {
        val id = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO users (id, username, email, display_name) VALUES (:id, :username, NULL, :displayName)",
            mapOf("id" to id, "username" to username, "displayName" to "Null Email User"),
        )
        return id
    }

    /**
     * T-01: "bob-suffix@x.com"(소문자) 시드 후 "Bob-suffix@X.com"(대문자 포함)으로 조회하면
     * lower 비교 매칭으로 시드된 사용자 id 1건이 반환된다. 반환 맵의 키는 lower 정규화된 이메일이다.
     */
    @Test
    fun `T-01 resolveByEmails matches case-insensitively for a single row`() {
        val lowerEmail = "bob-$suffix@x.com"
        val saved = userRepository.save(username = "bob-$suffix", email = lowerEmail, displayName = "Bob")

        val upperInput = "Bob-$suffix@X.com"
        val result = userLookupPort.resolveByEmails(setOf(upperInput))

        @Suppress("UNCHECKED_CAST")
        val mapAssert = assertThat(result) as MapAssert<String, UUID>
        mapAssert.containsOnlyKeys(lowerEmail)
        assertThat(result[lowerEmail])
            .`as`("lower 정규화된 이메일 '%s' 키로 시드된 사용자 id 가 반환되어야 합니다.", lowerEmail)
            .isEqualTo(saved.id)
    }

    /**
     * T-02: 같은 lower(email) 값을 가진 두 사용자가 존재할 때, 그 이메일로 조회하면
     * 다중 매칭 fail-safe 규칙에 따라 결과 맵에서 제외된다 — 잘못된 사용자 배정보다 안전.
     */
    @Test
    fun `T-02 resolveByEmails excludes email with multiple matches`() {
        val duplicateEmail = "dup-$suffix@x.com"
        userRepository.save(username = "dup-a-$suffix", email = duplicateEmail, displayName = "Dup A")
        userRepository.save(username = "dup-b-$suffix", email = duplicateEmail, displayName = "Dup B")

        val result = userLookupPort.resolveByEmails(setOf(duplicateEmail))

        @Suppress("UNCHECKED_CAST")
        (assertThat(result) as MapAssert<String, UUID>)
            .`as`("다중 매칭된 이메일 '%s' 은 fail-safe 로 결과에서 제외되어야 합니다.", duplicateEmail)
            .doesNotContainKey(duplicateEmail)
    }

    /**
     * T-03: email=NULL 인 사용자는 `WHERE lower(email) IN (:emails)` 매칭 대상이 아니므로
     * 어떤 이메일로 조회해도 결과에 나타나지 않는다.
     */
    @Test
    fun `T-03 resolveByEmails does not match users with null email`() {
        insertUserWithNullEmail("null-email-$suffix")
        val neverAssignedEmail = "ghost-$suffix@x.com"

        val result = userLookupPort.resolveByEmails(setOf(neverAssignedEmail))

        @Suppress("UNCHECKED_CAST")
        (assertThat(result) as MapAssert<String, UUID>)
            .`as`("NULL email 사용자는 어떤 조회에도 매칭되지 않아야 합니다.")
            .isEmpty()
    }

    /**
     * T-04: 빈 집합으로 조회하면 DB 쿼리 없이 빈 맵을 즉시 반환한다.
     */
    @Test
    fun `T-04 resolveByEmails returns empty map for empty input`() {
        val result = userLookupPort.resolveByEmails(emptySet())

        @Suppress("UNCHECKED_CAST")
        (assertThat(result) as MapAssert<String, UUID>)
            .`as`("빈 입력에 대해 emptyMap 을 기대했으나 결과가 있습니다.")
            .isEmpty()
    }
}
