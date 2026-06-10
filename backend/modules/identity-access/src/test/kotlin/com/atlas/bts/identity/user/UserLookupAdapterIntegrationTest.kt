// UserLookupAdapter 통합 테스트 — 실제 PostgreSQL 에서 사용자 존재 여부 및 username 일괄 해석 검증 (FR-IS-03 Task 3 / FR-MN-01 Task 3)

package com.atlas.bts.identity.user

import com.bts.shared.user.UserLookupPort
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.MapAssert
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
 * [UserLookupAdapter] 통합 테스트 (FR-IS-03 Task 3 / FR-MN-01 Task 3 / ADR 2026-06-01-issue-assignee-user-lookup-port).
 *
 * ## 목적
 * [UserLookupPort.exists] 및 [UserLookupPort.findIdsByUsernames] 가 실제 PostgreSQL + Flyway 스키마 위에서
 * 올바르게 동작하는지 검증한다.
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
 * | T-03 | alice/bob 삽입 후 alice+bob+ghost 로 일괄 조회 | alice/bob 각 id 포함, ghost 제외 |
 * | T-04 | 빈 입력으로 일괄 조회 | 빈 맵 |
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

    /** T-03/T-04 용 시드 사용자 id */
    private lateinit var aliceId: UUID
    private lateinit var bobId: UUID

    @BeforeEach
    fun prepareSeedUser() {
        val user =
            userRepository.save(
                username = "lookup-test-user-${UUID.randomUUID()}",
                email = "lookup@example.com",
                displayName = "Lookup Test User",
            )
        seededUserId = user.id

        // T-03 용 고정 username 시드 — UUID suffix 로 충돌 방지
        val suffix = UUID.randomUUID().toString().take(8)
        val alice =
            userRepository.save(
                username = "alice-$suffix",
                email = "alice-$suffix@example.com",
                displayName = "Alice",
            )
        val bob =
            userRepository.save(
                username = "bob-$suffix",
                email = "bob-$suffix@example.com",
                displayName = "Bob",
            )
        aliceId = alice.id
        bobId = bob.id

        // T-03 에서 실제 username 으로 조회하기 위해 suffix 저장
        aliceSuffix = suffix
    }

    private lateinit var aliceSuffix: String

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

    /**
     * T-03: alice/bob 삽입 후 alice+bob+ghost username 으로 [UserLookupPort.findIdsByUsernames] 호출 시
     * alice 와 bob 각자의 id 가 포함되고, 미존재 username(ghost-suffix) 은 결과에서 제외된다.
     */
    @Test
    fun `T-03 findIdsByUsernames returns alice and bob ids and excludes ghost`() {
        val suffix = aliceSuffix
        val aliceUsername = "alice-$suffix"
        val bobUsername = "bob-$suffix"
        val ghostUsername = "ghost-$suffix"

        val result = userLookupPort.findIdsByUsernames(setOf(aliceUsername, bobUsername, ghostUsername))

        @Suppress("UNCHECKED_CAST")
        val mapAssert = assertThat(result) as MapAssert<String, UUID>
        mapAssert.containsOnlyKeys(aliceUsername, bobUsername)
        assertThat(result[aliceUsername])
            .`as`("alice 의 id 가 시드와 일치해야 합니다.")
            .isEqualTo(aliceId)
        assertThat(result[bobUsername])
            .`as`("bob 의 id 가 시드와 일치해야 합니다.")
            .isEqualTo(bobId)
        mapAssert.doesNotContainKey(ghostUsername)
    }

    /**
     * T-04: 빈 집합으로 [UserLookupPort.findIdsByUsernames] 호출 시 빈 맵을 반환한다 (DB 쿼리 생략).
     */
    @Test
    fun `T-04 findIdsByUsernames returns empty map for empty input`() {
        val result = userLookupPort.findIdsByUsernames(emptySet())

        @Suppress("UNCHECKED_CAST")
        (assertThat(result) as MapAssert<String, UUID>)
            .`as`("빈 입력에 대해 emptyMap 을 기대했으나 결과가 있습니다.")
            .isEmpty()
    }
}
