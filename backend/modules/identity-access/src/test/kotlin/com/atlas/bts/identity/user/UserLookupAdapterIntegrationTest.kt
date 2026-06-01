// UserLookupAdapter 통합 테스트 — 실제 PostgreSQL 에서 사용자 존재 여부 검증 (FR-IS-03 Task 3)

package com.atlas.bts.identity.user

import com.bts.shared.user.UserLookupPort
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID

/**
 * [UserLookupAdapter] 통합 테스트 (FR-IS-03 Task 3 / ADR 2026-06-01-issue-assignee-user-lookup-port).
 *
 * ## 목적
 * [UserLookupPort.exists] 가 실제 PostgreSQL + Flyway 스키마 위에서 올바르게 동작하는지 검증한다.
 * - 존재하는 사용자 UUID → true
 * - 존재하지 않는 UUID → false
 *
 * ## 테스트 환경
 * - `@SpringBootTest(RANDOM_PORT)` — 실제 Spring Boot 컨텍스트 전체 구동.
 * - Testcontainers PostgreSQL 16 — Flyway V001 이상 자동 마이그레이션 적용.
 * - 테스트 전 [UserRepository.save] 로 시드 사용자 삽입 — Flyway 시드 의존 없음 (멱등성 보장).
 *
 * ## 검증 시나리오
 * | 번호 | 시나리오 | 기대 결과 |
 * |---|---|---|
 * | T-01 | 존재하는 사용자 UUID 조회 | true |
 * | T-02 | 존재하지 않는 랜덤 UUID 조회 | false |
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientAutoConfiguration",
    ],
)
@Testcontainers
class UserLookupAdapterIntegrationTest {
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

    /** 각 테스트 전 시드 사용자 삽입 — UPSERT 이므로 멱등 */
    private lateinit var seededUserId: UUID

    @BeforeEach
    fun prepareSeedUser() {
        val user =
            userRepository.save(
                username = "lookup-test-user-${UUID.randomUUID()}",
                email = "lookup@example.com",
                displayName = "Lookup Test User",
            )
        seededUserId = user.id
    }

    /**
     * T-01: 존재하는 사용자 UUID 로 [UserLookupPort.exists] 호출 시 true 를 반환한다.
     */
    @Test
    fun `T-01 exists returns true for a user that was inserted`() {
        val result = userLookupPort.exists(seededUserId)

        assertThat(result)
            .`as`("삽입된 사용자 UUID=%s 에 대해 exists()=true 를 기대했으나 false 를 반환했습니다.", seededUserId)
            .isTrue()
    }

    /**
     * T-02: DB 에 없는 랜덤 UUID 로 [UserLookupPort.exists] 호출 시 false 를 반환한다.
     */
    @Test
    fun `T-02 exists returns false for a random UUID that was never inserted`() {
        val randomId = UUID.randomUUID()

        val result = userLookupPort.exists(randomId)

        assertThat(result)
            .`as`("미존재 UUID=%s 에 대해 exists()=false 를 기대했으나 true 를 반환했습니다.", randomId)
            .isFalse()
    }
}
