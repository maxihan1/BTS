// UserLookupAdapter 통합 테스트 — 사용자 존재/username 해석/이메일 조회 검증 (FR-IS-03·FR-MN-01·FR-NT-02)

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
 * | T-05 | bob(소문자) 삽입 후 "Bob"/"BOB" 으로 조회 | 대소문자 무시 — bob 의 id 로 매칭 |
 * | T-06 | "Carol"/"carol" 동시 존재 → "carol" 조회 | 과다매칭 허용 — 2개 id 반환 |
 * | T-07 | A(display_name="홍길동") + B(display_name=null) + 미존재 id 로 표시명 일괄 조회 | A→"홍길동"(표시명), B→username 폴백, 미존재 제외 |
 * | T-08 | 빈 입력으로 표시명 일괄 조회 | 빈 맵 (DB 쿼리 생략) |
 * | T-09 | 삽입된 사용자 id 로 findEmailById 조회 | 삽입 시 지정한 email 반환 |
 * | T-10 | 미존재 랜덤 UUID 로 findEmailById 조회 | null 반환 |
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

    /**
     * T-07 의 display_name=null 사용자 시드용 — [UserRepository.save] 는 displayName 이 non-null 이라
     * null 표시명을 만들 수 없으므로, 테스트에서 직접 INSERT 한다 (named parameter 바인딩).
     */
    @Autowired
    lateinit var jdbc: NamedParameterJdbcTemplate

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

    /**
     * T-05: DB 에 소문자 "bob-suffix" 로 저장된 사용자를 "Bob-suffix" / "BOB-suffix" 로 조회해도
     * 대소문자 무시(LOWER 비교) 매칭으로 bob 의 id 를 반환한다 (FR-MN-01 case-insensitive 변경).
     */
    @Test
    fun `T-05 findIdsByUsernames matches case-insensitively`() {
        val suffix = UUID.randomUUID().toString().take(8)
        val lowerUsername = "bob-$suffix"
        val savedBob =
            userRepository.save(
                username = lowerUsername,
                email = "bob-ci-$suffix@example.com",
                displayName = "Bob CI",
            )

        val upperInput = lowerUsername.uppercase()
        val capitalInput = lowerUsername.replaceFirstChar { it.uppercase() }

        val result = userLookupPort.findIdsByUsernames(setOf(upperInput, capitalInput))

        assertThat(result.values)
            .`as`("대소문자만 다른 입력 '%s', '%s' 이 bob 의 id 로 매칭되어야 합니다.", upperInput, capitalInput)
            .containsOnly(savedBob.id)
    }

    /**
     * T-06: DB 에 "Carol-suffix"(대문자 C) 와 "carol-suffix"(소문자 c) 두 사용자가 동시 존재할 때,
     * "carol-suffix" 로 조회하면 LOWER 비교 과다매칭으로 두 id 가 모두 반환된다.
     *
     * 이 동작은 알려진 트레이드오프로 고정한다 — username UNIQUE 제약이 대소문자를 구분하므로
     * 두 row 가 공존할 수 있고, LOWER IN 쿼리는 둘 다 매칭한다.
     * 호출자(publishMentions)는 id 집합만 사용하므로 멘션 알림이 두 사용자에게 모두 전달된다.
     */
    @Test
    fun `T-06 findIdsByUsernames returns both ids when uppercase and lowercase username coexist`() {
        val suffix = UUID.randomUUID().toString().take(8)
        val upperUsername = "Carol-$suffix"
        val lowerUsername = "carol-$suffix"

        val savedUpper =
            userRepository.save(
                username = upperUsername,
                email = "carol-upper-$suffix@example.com",
                displayName = "Carol Upper",
            )
        val savedLower =
            userRepository.save(
                username = lowerUsername,
                email = "carol-lower-$suffix@example.com",
                displayName = "Carol Lower",
            )

        val result = userLookupPort.findIdsByUsernames(setOf(lowerUsername))

        assertThat(result.values)
            .`as`(
                "대소문자만 다른 '%s'/'%s' 가 동시 존재할 때 '%s' 조회 시 두 id 가 모두 반환되어야 합니다.",
                upperUsername,
                lowerUsername,
                lowerUsername,
            )
            .containsExactlyInAnyOrder(savedUpper.id, savedLower.id)
    }

    /**
     * display_name 이 null 인 사용자를 직접 INSERT 한다 — [UserRepository.save] 는 displayName non-null 이라
     * COALESCE 폴백 검증용 null 표시명을 만들 수 없으므로 named parameter INSERT 로 시드한다.
     *
     * @return INSERT 된 사용자 UUID
     */
    private fun insertUserWithNullDisplayName(
        username: String,
        email: String,
    ): UUID {
        val id = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO users (id, username, email, display_name) VALUES (:id, :username, :email, NULL)",
            mapOf("id" to id, "username" to username, "email" to email),
        )
        return id
    }

    /**
     * T-07: A(display_name="홍길동") + B(display_name=null) + 미존재 id 로
     * [UserLookupPort.findDisplayNamesByIds] 호출 시,
     * A 는 표시명("홍길동"), B 는 display_name 이 null 이라 username 으로 폴백된 값을 반환하고,
     * 미존재 id 는 결과 맵에서 제외된다 (COALESCE(display_name, username) — Jira 식 표시명).
     */
    @Test
    fun `T-07 findDisplayNamesByIds returns display_name with username fallback and excludes missing`() {
        val suffix = UUID.randomUUID().toString().take(8)
        val userA =
            userRepository.save(
                username = "hong-$suffix",
                email = "hong-$suffix@example.com",
                displayName = "홍길동",
            )
        val bobUsername = "bob-$suffix"
        val userBId = insertUserWithNullDisplayName(bobUsername, "bob-$suffix@example.com")
        val ghostId = UUID.randomUUID()

        val result = userLookupPort.findDisplayNamesByIds(setOf(userA.id, userBId, ghostId))

        @Suppress("UNCHECKED_CAST")
        val mapAssert = assertThat(result) as MapAssert<UUID, String>
        mapAssert.containsOnlyKeys(userA.id, userBId)
        assertThat(result[userA.id])
            .`as`("display_name 이 있는 A 는 표시명 '홍길동' 을 반환해야 합니다.")
            .isEqualTo("홍길동")
        assertThat(result[userBId])
            .`as`("display_name 이 null 인 B 는 username '%s' 으로 폴백되어야 합니다.", bobUsername)
            .isEqualTo(bobUsername)
        mapAssert.doesNotContainKey(ghostId)
    }

    /**
     * T-08: 빈 집합으로 [UserLookupPort.findDisplayNamesByIds] 호출 시 빈 맵을 반환한다 (DB 쿼리 생략).
     */
    @Test
    fun `T-08 findDisplayNamesByIds returns empty map for empty input`() {
        val result = userLookupPort.findDisplayNamesByIds(emptySet())

        @Suppress("UNCHECKED_CAST")
        (assertThat(result) as MapAssert<UUID, String>)
            .`as`("빈 입력에 대해 emptyMap 을 기대했으나 결과가 있습니다.")
            .isEmpty()
    }

    /**
     * T-09: 삽입된 사용자 UUID 로 [UserLookupPort.findEmailById] 호출 시 해당 email 을 반환한다 (FR-NT-02 Task 2).
     *
     * [BeforeEach] 에서 seededUserId 와 함께 "lookup@example.com" 이 삽입된다.
     * findEmailById(seededUserId) 가 그 이메일을 반환해야 한다.
     */
    @Test
    fun `T-09 findEmailById returns email for an existing user`() {
        val result = userLookupPort.findEmailById(seededUserId)

        assertThat(result)
            .`as`("삽입된 사용자 UUID=%s 에 대해 email='lookup@example.com' 을 기대했으나 '%s' 을 반환했습니다.", seededUserId, result)
            .isEqualTo("lookup@example.com")
    }

    /**
     * T-10: DB 에 없는 랜덤 UUID 로 [UserLookupPort.findEmailById] 호출 시 null 을 반환한다 (FR-NT-02 Task 2).
     *
     * 행 부재 시 null 을 반환하는 것이 [UserLookupPort.findEmailById] 의 계약이다.
     * default 구현(null)이 아닌 실제 DB 조회 후 null 을 반환하는지 검증한다.
     */
    @Test
    fun `T-10 findEmailById returns null for a non-existent user`() {
        val nonExistentId = UUID.randomUUID()

        val result = userLookupPort.findEmailById(nonExistentId)

        assertThat(result)
            .`as`("미존재 UUID=%s 에 대해 null 을 기대했으나 '%s' 을 반환했습니다.", nonExistentId, result)
            .isNull()
    }
}
