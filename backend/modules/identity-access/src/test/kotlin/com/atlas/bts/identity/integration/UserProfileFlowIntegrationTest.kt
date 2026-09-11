// 프로필 조회/3-state PATCH + 아바타 업로드-다운로드-삭제 end-to-end 통합테스트 — prod 부팅 + 실제 JWT + MinIO (FR-PR-01 Task 6)

package com.atlas.bts.identity.integration

import com.atlas.bts.identity.profile.avatar.AvatarTypePolicy
import com.atlas.bts.identity.provider.ldap.AutoProvisionService
import com.atlas.bts.identity.provider.ldap.ExternalAccountRepository
import com.atlas.bts.identity.provider.ldap.LdapProvider
import com.atlas.bts.identity.provider.ldap.LdapProviderConfigService
import com.atlas.bts.identity.support.SharedPostgres
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.core.io.ByteArrayResource
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.ldap.core.LdapTemplate
import org.springframework.mock.web.MockMultipartFile
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.util.LinkedMultiValueMap
import org.testcontainers.containers.MinIOContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.Security
import java.time.Instant
import java.util.UUID

/**
 * [com.atlas.bts.identity.web.UserProfileController] end-to-end 통합테스트 (FR-PR-01 Task 6).
 *
 * ## 목적
 * 프로필/아바타 전체 스택(SecurityFilterChain → JWT 인증 → 컨트롤러 → [com.atlas.bts.identity.profile.UserProfileService]
 * → PostgreSQL(users/user_profiles) + MinIO)을 실제 부팅으로 검증한다. 단위/슬라이스 테스트로는 잡히지 않는
 * JWT 인증 회로, 3-state PATCH 영속, MinIO 아바타 바이너리 왕복, 다운로드 보안 헤더를 실제 인프라로 확인한다.
 *
 * ## 부팅 패턴 (identity-access prod randomport boot recipe)
 * [MyProjectPermissionIntegrationTest] 의 `@SpringBootTest(RANDOM_PORT)` + `@AutoConfigureMockMvc` +
 * `@ActiveProfiles("prod")` + Testcontainers + LDAP `@MockBean` + 임시 RSA PEM(JwtEncoder/JwtDecoder 서명키)
 * 레시피를 미러한다. 아바타 스토리지 검증을 위해 [MinioAvatarStorageAdapterTest] 의 [MinIOContainer] 셋업을
 * 합쳐 `bts.minio.*` 를 컨테이너 좌표로 wire 한다.
 *
 * MockMvc 는 DispatcherServlet 로 직접 디스패치하므로 서블릿 컨테이너의 multipart 크기 제한을 우회한다 —
 * 5MB 초과 업로드가 [AvatarTypePolicy] 의 앱-레벨 크기 검증(→ 400)에 도달함을 보장한다.
 *
 * ## 검증 시나리오 (Task 6 명세 7개)
 * | 번호 | 시나리오 | 기대 결과 |
 * |---|---|---|
 * | 1 | 토큰 없이 GET /me/profile | 401 |
 * | 2 | user_profiles 행 없는 신규 사용자 GET | 200 + defaults(UTC/null/null) |
 * | 3 | 3-state PATCH(갱신 → 삭제 → 무효 timezone → 빈 displayName) | 200/200/400/400 |
 * | 4 | 아바타 업로드 → 다운로드(nosniff) → 삭제 → 재조회 | 200/200/204/404 |
 * | 5 | application/pdf · 5MB 초과 업로드 | 400/400 |
 * | 6 | 인증된 동료가 타인의 아바타 조회 | 200 |
 * | 7 | 미인증이 미존재 아바타 조회(리소스 조회보다 인증 먼저) | 401(404 아님) |
 *
 * ## CSRF
 * JWT Bearer 인증 상태변경 요청(POST/PATCH/DELETE)에는 [csrf] 후처리기로 유효 CSRF 토큰을 첨부한다
 * (CSRF 우회가 아니라 표준 테스트 위생 — 인증은 여전히 실제 Bearer JWT 로 구동한다).
 *
 * @see com.atlas.bts.identity.web.UserProfileController
 * @see com.atlas.bts.identity.profile.UserProfileService
 *
 * ## ★`@Testcontainers` 를 남겨 둔 이유
 * postgres 는 [com.atlas.bts.identity.support.SharedPostgres] 로 옮겼지만 이 파일에는
 * MinIO `@Container` 가 남아 있다. 그 컨테이너의 수명은 여전히 JUnit 이 관리해야 한다 —
 * 어노테이션을 떼면 `Mapped port can only be obtained after the container is started` 가 난다.
 */
@Testcontainers
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "spring.autoconfigure.exclude=" +
            "org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientAutoConfiguration",
    ],
)
@AutoConfigureMockMvc
@ActiveProfiles("prod")
class UserProfileFlowIntegrationTest {
    companion object {
        /**
         * 공용 컨테이너의 템플릿 DB 를 복제한 전용 데이터베이스.
         *
         * 격리는 그대로이고 컨테이너 기동과 마이그레이션 재적용만 사라진다.
         * 근거와 주의점은 [com.atlas.bts.identity.support.SharedPostgres] 헤더.
         */
        @JvmStatic
        val postgres = SharedPostgres.freshDatabase()

        /** JVM 단위 singleton MinIO — 아바타 바이너리 put/get/delete 실 스토리지. */
        @Container
        @JvmStatic
        val minio: MinIOContainer = MinIOContainer(
                DockerImageName.parse("quay.io/minio/minio:RELEASE.2023-09-04T19-57-37Z")
                    .asCompatibleSubstituteFor("minio/minio"),
            )

        /**
         * Spring Boot 데이터소스/JWT/CORS/LDAP/MinIO 설정을 Testcontainers 좌표로 교체한다.
         *
         * prod 프로파일은 `bts.auth.jwt.private-key-pem-path`(RSA PEM) 를 fail-fast 로 요구하므로
         * [pemFilePath] 를 주입한다. `bts.minio.*` 는 relaxed binding(access-key→accessKey) 로
         * [com.atlas.bts.identity.profile.avatar.MinioAvatarStorageConfig.Properties] 에 바인딩된다.
         */
        @DynamicPropertySource
        @JvmStatic
        fun configureProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
            // 템플릿 DB 에서 이미 적용됐다 — 여기서 다시 돌리면 이 최적화가 무의미해진다
            registry.add("spring.flyway.enabled") { "false" }
            // 공용 컨테이너라 커넥션 한도도 공유한다. context 캐시가 쌓이면
            // 기본 풀(10)로는 max_connections 를 넘긴다 — SharedPostgres 헤더 참조.
            registry.add("spring.datasource.hikari.maximum-pool-size") { SharedPostgres.MAX_POOL_SIZE }
            registry.add("bts.auth.issuer-uri") { "http://localhost:8090" }
            registry.add("bts.security.cors.allowed-origins") { "http://localhost:5173" }
            registry.add("spring.ldap.urls") { "ldap://localhost:389" }
            registry.add("spring.ldap.base") { "dc=bts,dc=local" }
            registry.add("bts.auth.jwt.private-key-pem-path") { pemFilePath }
            registry.add("bts.minio.endpoint") { minio.s3URL }
            registry.add("bts.minio.access-key") { minio.userName }
            registry.add("bts.minio.secret-key") { minio.password }
            registry.add("bts.minio.avatar-bucket") { "bts-avatars-test" }
        }

        /**
         * 테스트용 임시 RSA 2048 PEM(PKCS#8) 파일 ([MyProjectPermissionIntegrationTest] 미러).
         *
         * BouncyCastle provider 등록 후 생성한다. JwtEncoder 가 이 키로 서명하고 JwtDecoder 가 같은
         * 키 쌍의 공개키로 검증하므로 실제 발급→검증 왕복이 성립한다.
         */
        val pemFilePath: String =
            run {
                if (Security.getProvider("BC") == null) {
                    Security.addProvider(BouncyCastleProvider())
                }
                val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
                val pemContent =
                    buildString {
                        appendLine("-----BEGIN PRIVATE KEY-----")
                        val mimeEncoder = java.util.Base64.getMimeEncoder(64, "\n".toByteArray())
                        appendLine(mimeEncoder.encodeToString(keyPair.private.encoded))
                        append("-----END PRIVATE KEY-----")
                    }
                val tmpFile = Files.createTempFile("bts-test-key-", ".pem")
                Files.writeString(tmpFile, pemContent)
                tmpFile.toAbsolutePath().toString()
            }
    }

    // ── LDAP Bean 목킹 — 실제 LDAP 서버 없이 컨텍스트 부팅 (MyProjectPermissionIntegrationTest 선례) ──
    @MockBean lateinit var ldapProvider: LdapProvider

    @MockBean lateinit var ldapProviderConfigService: LdapProviderConfigService

    @MockBean lateinit var externalAccountRepository: ExternalAccountRepository

    @MockBean lateinit var autoProvisionService: AutoProvisionService

    @MockBean lateinit var ldapTemplate: LdapTemplate

    // @Suppress("VarCouldBeVal") — Spring 이 reflection 으로 주입하는 lateinit 은 소스상 재대입이 없어
    // detekt 가 val 로 오판한다(신규 코드 @Suppress 관례). lateinit 특성상 var 여야 한다.
    @Autowired
    @Suppress("VarCouldBeVal")
    private lateinit var mockMvc: MockMvc

    @Autowired
    @Suppress("VarCouldBeVal")
    private lateinit var jwtEncoder: JwtEncoder

    @Autowired
    @Suppress("VarCouldBeVal")
    private lateinit var jdbc: NamedParameterJdbcTemplate

    @Autowired
    @Suppress("VarCouldBeVal")
    private lateinit var objectMapper: ObjectMapper

    // 실 서블릿 멀티파트 경로(서블릿이 바디를 실제 파싱) 검증용 — MockMvc 는 서블릿 파싱을 우회한다.
    @Autowired
    @Suppress("VarCouldBeVal")
    private lateinit var restTemplate: TestRestTemplate

    @LocalServerPort
    @Suppress("VarCouldBeVal")
    private var port: Int = 0

    // ── 픽스처 식별값 ──────────────────────────────────────────────────────────
    private val meId: UUID = UUID.fromString("0f000000-0000-0000-0000-000000000601")
    private val peerId: UUID = UUID.fromString("0f000000-0000-0000-0000-000000000602")
    private val meSessionId: UUID = UUID.fromString("0f000000-0000-0000-0000-0000000006a1")
    private val peerSessionId: UUID = UUID.fromString("0f000000-0000-0000-0000-0000000006a2")

    private val meUsername = "prprofile_me"
    private val meDisplayName = "PR Profile Me"
    private val peerUsername = "prprofile_peer"
    private val peerDisplayName = "PR Profile Peer"

    /** 소량 PNG(매직 바이트 + 패딩) — MIME 만 정책 판정에 쓰이므로 유효 이미지일 필요 없음. */
    private val pngBytes: ByteArray =
        byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x01, 0x02, 0x03, 0x04)

    @BeforeEach
    fun setUp() {
        cleanTestData()
        seedUsers()
        seedSessions()
    }

    // ── 시나리오 1. 미인증 프로필 조회 ─────────────────────────────────────────

    @Test
    fun `토큰 없이 프로필을 조회하면 401`() {
        mockMvc.perform(
            get("/api/v1/users/me/profile").accept(MediaType.APPLICATION_JSON),
        ).andExpect(status().isUnauthorized)
    }

    // ── 시나리오 2. 신규 사용자 기본값 ─────────────────────────────────────────

    @Test
    fun `프로필 행이 없는 신규 사용자는 users 값 + 기본값으로 조회된다`() {
        val profile = getProfile(issueJwt(meId, meSessionId))

        assertThat(profile["userId"]).isEqualTo(meId.toString())
        assertThat(profile["username"]).isEqualTo(meUsername)
        assertThat(profile["displayName"]).isEqualTo(meDisplayName)
        assertThat(profile["avatarUrl"]).isNull()
        assertThat(profile["timezone"]).isEqualTo("UTC")
        assertThat(profile["department"]).isNull()
    }

    // ── 시나리오 3. 3-state PATCH ──────────────────────────────────────────────

    @Test
    fun `3-state PATCH — 갱신 후 department 삭제, 무효 timezone과 빈 displayName은 400`() {
        val token = issueJwt(meId, meSessionId)

        // (1) displayName/timezone/department 명시 값 갱신
        performPatch(token, """{"displayName":"Renamed","timezone":"Asia/Seoul","department":"Platform"}""")
            .andExpect(status().isOk)
        getProfile(token).let { profile ->
            assertThat(profile["displayName"]).isEqualTo("Renamed")
            assertThat(profile["timezone"]).isEqualTo("Asia/Seoul")
            assertThat(profile["department"]).isEqualTo("Platform")
        }

        // (2) department 명시 null → 삭제(다른 필드는 보존)
        performPatch(token, """{"department":null}""").andExpect(status().isOk)
        getProfile(token).let { profile ->
            assertThat(profile["department"]).isNull()
            assertThat(profile["timezone"]).isEqualTo("Asia/Seoul")
            assertThat(profile["displayName"]).isEqualTo("Renamed")
        }

        // (3) 무효 timezone → 400
        performPatch(token, """{"timezone":"Mars/Nowhere"}""").andExpect(status().isBadRequest)

        // (4) 빈 displayName → 400
        performPatch(token, """{"displayName":""}""").andExpect(status().isBadRequest)
    }

    // ── 시나리오 4. 아바타 업로드 → 다운로드 → 삭제 → 재조회 ────────────────────

    @Test
    fun `아바타 업로드 다운로드 삭제 왕복 — 다운로드에 nosniff 헤더와 바이트 일치`() {
        val token = issueJwt(meId, meSessionId)

        // 업로드 → 200 + avatarUrl 파생
        mockMvc.perform(
            multipart("/api/v1/users/me/profile/avatar")
                .file(MockMultipartFile("file", "avatar.png", MediaType.IMAGE_PNG_VALUE, pngBytes))
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                .with(csrf()),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.avatarUrl").value("/api/v1/users/$meId/avatar"))

        // 업로드 후 프로필 GET 도 avatarUrl 을 파생 노출한다(avatarObjectKey → URL 매핑 경로 검증)
        assertThat(getProfile(token)["avatarUrl"]).isEqualTo("/api/v1/users/$meId/avatar")

        // 다운로드 → 200 + nosniff + image/png + 바이트 일치
        val downloaded =
            mockMvc.perform(
                get("/api/v1/users/$meId/avatar").header(HttpHeaders.AUTHORIZATION, "Bearer $token"),
            )
                .andExpect(status().isOk)
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, containsString("image/png")))
                .andReturn().response.contentAsByteArray
        assertThat(downloaded).isEqualTo(pngBytes)

        // 삭제 → 204
        mockMvc.perform(
            delete("/api/v1/users/me/profile/avatar")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                .with(csrf()),
        ).andExpect(status().isNoContent)

        // 삭제 후 재조회 → 404
        mockMvc.perform(
            get("/api/v1/users/$meId/avatar").header(HttpHeaders.AUTHORIZATION, "Bearer $token"),
        ).andExpect(status().isNotFound)

        // 삭제 후 프로필 GET 의 avatarUrl 도 다시 null 로 파생된다
        assertThat(getProfile(token)["avatarUrl"]).isNull()
    }

    // ── 시나리오 5. 무효 업로드 (앱 정책) ──────────────────────────────────────

    /**
     * 아바타 업로드 앱-레벨 검증(MIME 화이트리스트 + [AvatarTypePolicy] 5MB 크기 상한) → 400.
     *
     * MockMvc 는 DispatcherServlet 로 직접 디스패치해 서블릿 멀티파트 크기 제한을 우회하므로 이 테스트는
     * 앱 정책만 검증한다(5MB 초과 바이트가 컨트롤러 [AvatarTypePolicy] 까지 도달해 400). 서블릿 하드
     * 상한(max-file-size) 경로는 실 HTTP(TestRestTemplate)를 태우는 아래 시나리오 5b 가 담당한다
     * (2MB 정상 → 200, 6MB 초과 → 400).
     */
    @Test
    fun `허용되지 않는 MIME과 5MB 초과 업로드는 400`() {
        val token = issueJwt(meId, meSessionId)

        // application/pdf → MIME 화이트리스트 밖 → 400
        mockMvc.perform(
            multipart("/api/v1/users/me/profile/avatar")
                .file(MockMultipartFile("file", "doc.pdf", MediaType.APPLICATION_PDF_VALUE, byteArrayOf(1, 2, 3, 4)))
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                .with(csrf()),
        ).andExpect(status().isBadRequest)

        // 5MB 초과(image/png) → 크기 정책 위반 → 400
        val oversized = ByteArray(AvatarTypePolicy.MAX_BYTES.toInt() + 1)
        mockMvc.perform(
            multipart("/api/v1/users/me/profile/avatar")
                .file(MockMultipartFile("file", "big.png", MediaType.IMAGE_PNG_VALUE, oversized))
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                .with(csrf()),
        ).andExpect(status().isBadRequest)
    }

    // ── 시나리오 5b. 실 서블릿 경로 크기 상한 (회귀 방지) ──────────────────────

    /**
     * 실제 HTTP 멀티파트 업로드로 서블릿 크기 상한 동작을 검증한다(MockMvc 가 우회하는 서블릿 경로).
     *
     * ## 회귀 방지 핵심
     * 서블릿 기본 max-file-size 는 1MB 라, 이 설정이 없으면 정상 2MB 아바타가 컨트롤러 검증 이전에
     * MaxUploadSizeExceededException 으로 거부돼 500 이 된다(prod 에서 2MB 아바타 업로드가 깨짐).
     * application.yml 에서 6MB 로 올렸으므로 2MB 는 200 이어야 한다 — 이것이 버그의 본질이다.
     *
     * 5.5MB(앱 정책 5MB 초과 & 서블릿 6MB 미만)는 서블릿을 통과해 컨트롤러가 바디를 끝까지 읽은 뒤
     * [AvatarTypePolicy] 로 400 을 반환한다. 바디가 완전히 소비돼 연결 리셋 없이 400 을 온전히 관측한다.
     * 이 케이스도 회귀를 함께 잡는다 — 서블릿 상한이 1MB 기본이면 5.5MB 는 400 이 아니라 500 이 된다.
     * (서블릿 하드 상한 6MB 초과 시의 MaxUploadSizeExceededException 핸들러는 백스톱으로 남겨두되, 그
     * 경로의 실 HTTP 관측은 Tomcat 의 즉시 100-continue + 스트림 도중 거부 시 연결 리셋 때문에 불안정하다.)
     */
    @Test
    fun `실 서블릿 경로 — 2MB 아바타는 200, 5·5MB는 400`() {
        val token = issueJwt(meId, meSessionId)

        // ~2MB 유효 PNG — 상한을 6MB 로 올린 덕에 서블릿 1MB 기본에 걸리지 않고 정상 저장돼야 한다.
        val twoMb = ByteArray(2 * 1024 * 1024).also { pngBytes.copyInto(it) }
        assertThat(uploadAvatarOverHttp(token, twoMb).statusCode).isEqualTo(HttpStatus.OK)

        // 5.5MB — 앱 정책(5MB) 초과, 서블릿(6MB) 미만 → 컨트롤러가 바디 전체를 읽고 앱 정책으로 400(클린).
        val overLimit = ByteArray(5 * 1024 * 1024 + 512 * 1024).also { pngBytes.copyInto(it) }
        assertThat(uploadAvatarOverHttp(token, overLimit).statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
    }

    // ── 시나리오 6. 동료 아바타 조회 ───────────────────────────────────────────

    @Test
    fun `인증된 동료는 타인의 아바타를 조회할 수 있다`() {
        // user A(me) 가 아바타 업로드
        val meToken = issueJwt(meId, meSessionId)
        mockMvc.perform(
            multipart("/api/v1/users/me/profile/avatar")
                .file(MockMultipartFile("file", "avatar.png", MediaType.IMAGE_PNG_VALUE, pngBytes))
                .header(HttpHeaders.AUTHORIZATION, "Bearer $meToken")
                .with(csrf()),
        ).andExpect(status().isOk)

        // user B(peer) 가 A 의 아바타를 조회 → 200
        val peerToken = issueJwt(peerId, peerSessionId)
        mockMvc.perform(
            get("/api/v1/users/$meId/avatar").header(HttpHeaders.AUTHORIZATION, "Bearer $peerToken"),
        )
            .andExpect(status().isOk)
            .andExpect(header().string("X-Content-Type-Options", "nosniff"))
    }

    // ── 시나리오 7. 인증 우선(리소스 조회보다 먼저) ────────────────────────────

    @Test
    fun `미인증이 미존재 아바타를 조회하면 리소스 조회보다 먼저 401`() {
        // 존재하지 않는 사용자 아바타를 토큰 없이 조회 — 404 가 아니라 401 이어야 한다
        // (auth-extraction-before-resource-lookup — 인증 실패는 리소스 조회보다 먼저).
        mockMvc.perform(
            get("/api/v1/users/${UUID.randomUUID()}/avatar"),
        ).andExpect(status().isUnauthorized)
    }

    // ── 헬퍼 ───────────────────────────────────────────────────────────────────

    /**
     * GET /me/profile 응답을 파싱해 필드 맵으로 반환한다(200 단언 포함).
     *
     * jsonPath 의 null/부재 모호성을 피하려 응답 본문을 [Map] 으로 역직렬화해 값을 직접 단언한다.
     */
    private fun getProfile(token: String): Map<String, Any?> {
        val body =
            mockMvc.perform(
                get("/api/v1/users/me/profile")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                    .accept(MediaType.APPLICATION_JSON),
            )
                .andExpect(status().isOk)
                .andReturn().response.contentAsString
        return objectMapper.readValue(body, object : TypeReference<Map<String, Any?>>() {})
    }

    /** PATCH /me/profile 를 JSON 바디로 수행한다(CSRF 첨부, 상태 단언은 호출자). */
    private fun performPatch(
        token: String,
        json: String,
    ) = mockMvc.perform(
        patch("/api/v1/users/me/profile")
            .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
            .contentType(MediaType.APPLICATION_JSON)
            .content(json)
            .with(csrf()),
    )

    /**
     * 실제 HTTP(TestRestTemplate)로 image/png 멀티파트 아바타를 업로드한다(서블릿 파싱 경로).
     *
     * JWT Bearer 인증 요청은 CSRF 를 면제받으므로(SecurityConfig — oauth2ResourceServer) CSRF 토큰은
     * 붙이지 않는다. part content-type 을 image/png 로 명시해 [AvatarTypePolicy] MIME 검증을 통과시킨다.
     * [TestRestTemplate] 은 4xx 를 예외로 던지지 않고 [ResponseEntity] 로 반환하므로 상태코드를 직접 단언한다.
     *
     * @param token Bearer 액세스 토큰.
     * @param bytes 업로드 바이트.
     * @return 상태코드 단언용 응답.
     */
    private fun uploadAvatarOverHttp(
        token: String,
        bytes: ByteArray,
    ): ResponseEntity<String> {
        val filePart =
            HttpEntity(
                object : ByteArrayResource(bytes) {
                    override fun getFilename(): String = "avatar.png"
                },
                HttpHeaders().apply { contentType = MediaType.IMAGE_PNG },
            )
        val body = LinkedMultiValueMap<String, Any>().apply { add("file", filePart) }
        val headers =
            HttpHeaders().apply {
                contentType = MediaType.MULTIPART_FORM_DATA
                set(HttpHeaders.AUTHORIZATION, "Bearer $token")
            }
        return restTemplate.postForEntity(
            "http://localhost:$port/api/v1/users/me/profile/avatar",
            HttpEntity(body, headers),
            String::class.java,
        )
    }

    /**
     * 주어진 userId 를 subject, sessionId 를 sid claim 으로 하는 JWT 를 발급한다
     * ([MyProjectPermissionIntegrationTest.issueJwt] 미러).
     *
     * sid claim 은 [com.atlas.bts.identity.jwt.SidRevokeJwtConverter] 가 sessions 테이블에서 활성 세션을
     * 조회하는 데 필요하다 — [seedSessions] 로 사전에 심는다.
     */
    private fun issueJwt(
        userId: UUID,
        sessionId: UUID,
    ): String {
        val now = Instant.now()
        val claims =
            JwtClaimsSet.builder()
                .subject(userId.toString())
                .issuer("http://localhost:8090")
                .issuedAt(now)
                .expiresAt(now.plusSeconds(3600))
                .claim("sid", sessionId.toString())
                .build()
        return jwtEncoder.encode(JwtEncoderParameters.from(claims)).tokenValue
    }

    /** 테스트 픽스처(user_profiles → sessions → users) 삭제. FK CASCADE 이나 순서 안전을 위해 명시 삭제. */
    private fun cleanTestData() {
        val userIds = listOf(meId, peerId)
        jdbc.update("DELETE FROM user_profiles WHERE user_id IN (:ids)", mapOf("ids" to userIds))
        jdbc.update(
            "DELETE FROM sessions WHERE id IN (:ids)",
            mapOf("ids" to listOf(meSessionId, peerSessionId)),
        )
        jdbc.update("DELETE FROM users WHERE id IN (:ids)", mapOf("ids" to userIds))
    }

    private fun seedUsers() {
        listOf(
            Triple(meId, meUsername, meDisplayName),
            Triple(peerId, peerUsername, peerDisplayName),
        ).forEach { (id, username, displayName) ->
            jdbc.update(
                "INSERT INTO users (id, username, email, display_name) VALUES (:id, :username, :email, :displayName)" +
                    " ON CONFLICT (id) DO NOTHING",
                mapOf(
                    "id" to id,
                    "username" to username,
                    "email" to "$username@example.com",
                    "displayName" to displayName,
                ),
            )
        }
    }

    /**
     * 각 사용자에 대응하는 활성 세션(revoked_at NULL + expires_at 미래)을 심는다.
     *
     * [com.atlas.bts.identity.jwt.SidRevokeJwtConverter] 가 JWT sid claim 으로 이 세션을 조회하므로,
     * 세션이 없으면 발급한 JWT 가 revoke 로 간주돼 401 이 된다.
     */
    private fun seedSessions() {
        val now = Instant.now()
        val expiresAt = now.plusSeconds(3600)
        listOf(
            Pair(meSessionId, meId),
            Pair(peerSessionId, peerId),
        ).forEach { (sessionId, userId) ->
            jdbc.update(
                """
                INSERT INTO sessions (id, user_id, provider_id, created_at, expires_at, last_seen_at)
                VALUES (:id, :userId, :providerId, :createdAt, :expiresAt, :lastSeenAt)
                ON CONFLICT (id) DO NOTHING
                """,
                mapOf(
                    "id" to sessionId,
                    "userId" to userId,
                    "providerId" to "local",
                    "createdAt" to java.sql.Timestamp.from(now),
                    "expiresAt" to java.sql.Timestamp.from(expiresAt),
                    "lastSeenAt" to java.sql.Timestamp.from(now),
                ),
            )
        }
    }
}
