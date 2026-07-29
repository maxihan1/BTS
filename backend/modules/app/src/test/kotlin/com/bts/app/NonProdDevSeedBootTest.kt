// 비-prod 조립 부팅 시 dev 시드가 실제 로그인 가능한 상태를 만드는지 실서버로 검증 (양성 봉인)

package com.bts.app

import com.atlas.bts.identity.credential.LocalCredentialService
import com.atlas.bts.identity.credential.StoredPasswordCredentialRepository
import com.atlas.bts.identity.mfa.MfaEnforcementPolicy
import com.atlas.bts.identity.user.UserRepository
import com.bts.shared.permission.GlobalPermissionCodes
import com.bts.shared.permission.SystemPermissionResolver
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * 비-prod 조립 부팅의 **양성 대조군** — dev 시드가 실제로 로그인 가능한 상태를 만들었는지 실서버로 확인한다.
 *
 * 음성 대조군은 [ProdDevSeedAbsenceBootTest] 다. 둘은 **서로의 대조군**이며 한쪽만 있으면 봉인이 절반만 닫힌다
 * (learning `seal-closes-only-half-by-default`).
 *
 * ## 왜 「행이 있다」가 아니라 「로그인이 된다」인가
 * 행 존재는 판별력이 약하다 — 해시가 깨져도, 권한이 없어도 행은 있다. 이 작업의 목적은
 * *"브라우저로 손검증할 수 있게 한다"* 이므로 계약도 **실 HTTP 로그인 200** 이어야 한다.
 *
 * ## ★ `SYSTEM_ADMIN` 을 주면 안 된다 (plan-eng-review 이슈 1)
 * `MfaEnforcementPolicy.kt:24` *"관리자(SYSTEM_ADMIN)는 무조건 강제 대상이다"* · `:71`
 * `systemPermissionResolver.isSystemAdmin(userId) ||` → `JwtIssuer.kt:109` 가 `mfa_enrollment_required=true` 를
 * 토큰에 박고 → `MfaEnrollmentGateFilter.kt:54` 가 허용목록 밖 **전 경로를 403** 으로 막는다.
 * 즉 관리자로 시드하면 **로그인은 되지만 어떤 화면에도 못 간다** — 목적이 파괴된다.
 * 그래서 시드는 `CREATE_PROJECT` **전역 부여**만 준다
 * (`IdentityAccessSystemPermissionResolver.kt:66` = `hasGrant(...) || isSystemAdmin(...)` 의 앞항).
 * [`mfa 강제 등록 대상이 아니다`] 단언이 그 회귀를 막는 가드다.
 *
 * ## ★ JDK HttpClient 를 쓰는 이유
 * [ProdAssemblyHttpTestBase] 의 `TestRestTemplate` 은 `:modules:app` 에 Apache HttpComponents5 가 없어
 * `HttpURLConnection` 으로 떨어지고 4xx 응답 **본문을 못 읽는다**
 * (learning `bts-assembly-test-pat-bearer-and-httpclient`). 응답 본문이 판별자이므로 원 응답을 직접 관측한다.
 *
 * ## ★★ 공유 dev postgres(5433)를 쓰면 이 테스트는 **공허하다**
 * 실측(2026-07-29) — 로컬 dev DB 에는 `alice`(2026-07-10 생성, `data-dev.sql` 산물)와
 * `CREATE_PROJECT` 전역 부여(2026-07-28 생성)가 **이미 있었다**. 그 DB 로 돌리면 시더를 통째로 지워도
 * 네 테스트가 전부 초록이다 — 판별력 0. 볼륨 `infra_bts-postgres-data` 가 영속이라 컨테이너를 지워도 남는다.
 *
 * 그래서 **매 실행마다 빈 DB** 를 띄운다. Flyway 가 스키마만 만든 상태에서 시작하므로
 * `alice` 가 존재한다는 사실 자체가 **시더가 일했다는 증거**가 된다.
 *
 * 이미지는 `quay.io/tembo/pg16-pgmq` 여야 한다 — 일반 `postgres:16` 은 pgmq 확장이 없어 마이그레이션에서 죽는다
 * (`backend-ci.yml:102-103` 의 같은 제약).
 *
 * ## 사전 조건
 * Docker 데몬만 있으면 된다 (dev postgres 5433 불필요).
 * [NonProdAssemblyBootTest] 와 같은 `@Tag("nonprod-assembly")` 라 전용 `nonProdAssemblyTest` 태스크(별도 JVM)에서만 돈다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@Tag("nonprod-assembly")
class NonProdDevSeedBootTest {
    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var localCredentialService: LocalCredentialService

    @Autowired
    private lateinit var systemPermissionResolver: SystemPermissionResolver

    @Autowired
    private lateinit var mfaEnforcementPolicy: MfaEnforcementPolicy

    @Autowired
    private lateinit var seeder: NonProdDevSeeder

    @Autowired
    private lateinit var credentialRepository: StoredPasswordCredentialRepository

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @LocalServerPort
    private var port: Int = 0

    companion object {
        /** 시드 기본 신원. `data-dev.sql` 이 이미 문서화한 값과 동일하므로 새 비밀이 도입되지 않는다(D8). */
        const val SEED_USERNAME = "alice"
        const val SEED_PASSWORD = "password"

        /** 매 실행마다 빈 DB. 선재 데이터가 없어야 「시더가 만들었다」가 성립한다. */
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer(
                DockerImageName.parse("quay.io/tembo/pg16-pgmq:latest")
                    .asCompatibleSubstituteFor("postgres"),
            ).withDatabaseName("bts").withUsername("bts").withPassword("bts")

        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
            registry.add("bts.auth.issuer-uri") { "http://localhost:8080" }
            registry.add("bts.slack.signing-secret") { ProdAssemblyHttpTestBase.TEST_SLACK_SIGNING_SECRET }
            registry.add("bts.automation-encryption.key") { ProdAssemblyHttpTestBase.TEST_AUTOMATION_ENCRYPTION_KEY }
            registry.add("bts.automation-encryption.salt") { ProdAssemblyHttpTestBase.TEST_AUTOMATION_ENCRYPTION_SALT }
        }
    }

    private fun seededUserId() =
        requireNotNull(userRepository.findByUsername(SEED_USERNAME)) {
            "dev 시드가 '$SEED_USERNAME' 사용자를 만들지 않았다"
        }.id

    @Test
    fun `시드 사용자와 자격증명이 생기고 실제 비밀번호로 검증된다`() {
        // 행 존재만으로는 해시가 유효한지 알 수 없다 — 실 Argon2 검증 통과가 계약이다.
        val verified = localCredentialService.verifyForUser(seededUserId(), SEED_PASSWORD.toCharArray())

        assertThat(verified)
            .describedAs("시드된 평문 비밀번호로 실 Argon2 검증이 통과해야 한다")
            .isTrue()
    }

    @Test
    fun `프로젝트 생성 권한은 있고 시스템 관리자는 아니다`() {
        val userId = seededUserId()

        assertThat(systemPermissionResolver.hasGlobalPermission(userId, GlobalPermissionCodes.CREATE_PROJECT))
            .describedAs("전역 부여로 프로젝트 생성이 열려야 한다")
            .isTrue()

        assertThat(systemPermissionResolver.isSystemAdmin(userId))
            .describedAs("★이슈 1 — 관리자로 올리면 MFA 게이트가 전 경로를 403 으로 막는다")
            .isFalse()
    }

    @Test
    fun `mfa 강제 등록 대상이 아니다`() {
        // ★이슈 1 회귀 가드. 누군가 SYSTEM_ADMIN 을 되살리면 여기서 즉시 red 가 된다.
        assertThat(mfaEnforcementPolicy.evaluate(seededUserId()))
            .describedAs("MFA 강제 등록 대상이면 로그인 후 전 화면이 403 이 된다")
            .isFalse()
    }

    @Test
    fun `재실행해도 행이 늘지 않고 기존 값이 바뀌지 않는다`() {
        // 부팅 시 이미 1회 돌았다. 여기서 한 번 더 부르면 「재부팅」과 같은 상황이 된다.
        val before = snapshot()

        seeder.seed()

        assertThat(snapshot())
            .describedAs("멱등 — 재실행이 행을 늘리거나 기존 값을 바꾸면 안 된다 (기존=%s)", before)
            .isEqualTo(before)
    }

    @Test
    fun `기존 비밀번호가 달라도 덮어쓰지 않는다`() {
        // 사람이 dev 에서 비밀번호를 바꿨는데 재부팅이 조용히 되돌리면 안 된다(E6).
        // 시드 값과 다른 해시로 바꾼 뒤 재실행해도 그 해시가 유지돼야 한다.
        val userId = seededUserId()
        try {
            localCredentialService.store(userId, "changed-by-human".toCharArray(), mustChange = false)
            val humanHash = credentialRepository.findByUserId(userId)?.passwordHash

            seeder.seed()

            assertThat(credentialRepository.findByUserId(userId)?.passwordHash)
                .describedAs("사람이 바꾼 비밀번호를 시드가 되돌리면 안 된다")
                .isEqualTo(humanHash)
        } finally {
            // 이 테스트만 공유 컨텍스트의 상태를 바꾼다. JUnit 실행 순서는 보장되지 않으므로
            // 반드시 원복해야 로그인·검증 테스트가 순서에 따라 깨지지 않는다.
            localCredentialService.store(userId, SEED_PASSWORD.toCharArray(), mustChange = false)
        }
    }

    /** 멱등 판정용 관측 — 행 수 + 자격증명 해시. 값이 하나라도 바뀌면 멱등이 깨진 것이다. */
    private fun snapshot(): List<Any?> {
        val userId = seededUserId()
        return listOf(
            jdbcTemplate.queryForObject("SELECT COUNT(*) FROM users", Long::class.java),
            jdbcTemplate.queryForObject("SELECT COUNT(*) FROM local_credentials", Long::class.java),
            jdbcTemplate.queryForObject("SELECT COUNT(*) FROM global_permission_grants", Long::class.java),
            credentialRepository.findByUserId(userId)?.passwordHash,
        )
    }

    @Test
    fun `실 HTTP 로그인이 200 을 반환한다`() {
        val body = """{"provider":"local","username":"$SEED_USERNAME","password":"$SEED_PASSWORD"}"""
        val request =
            HttpRequest.newBuilder(URI.create("http://localhost:$port/api/v1/auth/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()

        val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())

        assertThat(response.statusCode())
            .describedAs("실 로그인 응답 본문=%s", response.body())
            .isEqualTo(200)
    }
}
