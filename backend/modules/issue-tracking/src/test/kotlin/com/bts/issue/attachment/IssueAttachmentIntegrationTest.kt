// 이슈 첨부 파일 end-to-end 통합 테스트 — 라운드트립, 삭제 후 부재, 크기 한도, 권한 거부, 교차 이슈 (FR-AC-01 Task 6)
@file:Suppress("MaxLineLength")

package com.bts.issue.attachment

import com.bts.issue.adapter.inbound.rest.IssueControllerTransitionIntegrationTest.TestConfig
import com.bts.issue.attachment.adapter.MinioStorageAdapter
import com.bts.issue.attachment.adapter.MinioStorageConfig
import com.bts.issue.attachment.application.IssueAttachmentService
import com.bts.issue.attachment.repository.AttachmentRepository
import com.bts.issue.attachment.web.AttachmentExceptionHandler
import com.bts.issue.attachment.web.IssueAttachmentController
import com.bts.issue.repository.IssueRepository
import com.bts.shared.permission.IssuePermission
import com.bts.shared.permission.IssuePermissionResolver
import com.bts.shared.permission.IssueScope
import io.minio.MinioClient
import jakarta.servlet.Filter
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse
import jakarta.servlet.http.HttpServletRequest
import org.flywaydb.core.Flyway
import org.jooq.DSLContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.mock.web.MockMultipartFile
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.junit.jupiter.SpringExtension
import org.springframework.test.context.web.WebAppConfiguration
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import org.springframework.web.multipart.MaxUploadSizeExceededException
import java.sql.DriverManager
import java.time.Clock
import java.util.UUID

/**
 * 이슈 첨부 파일 end-to-end 통합 테스트 (FR-AC-01 Task 6).
 *
 * Testcontainers PostgreSQL([TestConfig.postgres]) + MinIO([AttachmentMinioTestcontainersBase.minioContainer])
 * + 전체 Spring MVC 컨텍스트(MockMvc) 위에서 `/api/v1/issues/{key}/attachments` 4개 엔드포인트를 검증한다.
 *
 * ## 검증 시나리오
 * - (a) 라운드트립: 업로드 201 → 목록 1건 → 다운로드 바이트 동일 → 삭제 204.
 * - (b) 삭제 후 부재: DELETE → 목록 0건 + 다운로드 404.
 * - (c) 크기 한도: 테스트 전용 1MB 한도 초과 업로드 → 413.
 * - (d) 권한 거부: UPDATE 미보유(DENY_ACTOR) 업로드/삭제 → 403, VIEW 미보유 목록/다운로드 → 403.
 * - (e) 교차 이슈: 이슈 A 첨부를 이슈 B 경로로 다운로드/삭제 → 404.
 *
 * ## 부팅 설정
 * [TestConfig] (Postgres singleton + Flyway migrate + issue-tracking + project-workflow BC 빈) 를 재사용한다.
 * [AttachmentTestConfig] 는 MinIO 빈(MinioClient, MinioStorageAdapter) +
 * 첨부 서비스/컨트롤러/예외 핸들러를 추가 등록한다.
 *
 * ## 크기 한도 오버라이드 (CONCERN-B)
 * 실제 100MB 스트림 생성은 비현실적이므로 [TestPropertySource] 로 max-file-size=1MB 로 축소한다.
 * prod 설정(100MB)은 `application-test.yml` 에 존재하며 이 테스트만 오버라이드한다.
 *
 * ## 권한 거부 패턴
 * [AttachmentTestConfig.denyablePermissionResolver] 가 [DENY_ACTOR_UUID] 를 거부하는
 * @Primary IssuePermissionResolver 를 등록해 [AlwaysAllowIssuePermissionResolver] 를 대체한다.
 * IssueVersionLinksIntegrationTest 동형 패턴.
 */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(
    classes = [
        TestConfig::class,
        IssueAttachmentIntegrationTest.AttachmentTestConfig::class,
    ],
)
@WebAppConfiguration
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IssueAttachmentIntegrationTest {

    /**
     * 첨부 파일 통합 테스트 전용 추가 설정.
     *
     * [TestConfig] 의 Postgres/jOOQ/IssueRepository 위에 다음 빈을 추가 등록한다.
     * - [MinioClient] — MinIO 컨테이너 동적 endpoint/credentials 주입
     * - [MinioStorageConfig.Properties] — bucket 이름 포함
     * - [MinioStorageAdapter] — [AttachmentStoragePort] 구현체
     * - [AttachmentRepository] — 첨부 메타데이터 jOOQ 저장소
     * - [IssueAttachmentService] — 첨부 유스케이스 서비스
     * - [IssueAttachmentController] — REST 컨트롤러
     * - [AttachmentExceptionHandler] — 예외→HTTP 변환 핸들러
     * - [denyablePermissionResolver] — DENY_ACTOR 거부, 나머지 허용 (@Primary로 AlwaysAllow 대체)
     */
    @Configuration
    open class AttachmentTestConfig {

        /**
         * MinIO 접속 설정.
         * [AttachmentMinioTestcontainersBase.minioContainer] 의 동적 endpoint/credentials 를 바인딩한다.
         */
        @Bean
        open fun minioProperties(): MinioStorageConfig.Properties =
            MinioStorageConfig.Properties(
                endpoint = AttachmentMinioTestcontainersBase.minioContainer.s3URL,
                accessKey = AttachmentMinioTestcontainersBase.minioContainer.userName,
                secretKey = AttachmentMinioTestcontainersBase.minioContainer.password,
                bucket = TEST_BUCKET,
            )

        /** [MinioClient] — Testcontainers MinIO 컨테이너에 연결된 클라이언트. */
        @Bean
        open fun minioClient(properties: MinioStorageConfig.Properties): MinioClient =
            MinioClient.builder()
                .endpoint(properties.endpoint)
                .credentials(properties.accessKey, properties.secretKey)
                .build()

        /**
         * [MinioStorageAdapter] — [AttachmentStoragePort] 구현체.
         * [ensureBucket] 을 직접 호출해 bucket 을 보장한다.
         * (ApplicationRunner 의존 없이 테스트 컨텍스트에서 bucket 을 사전 생성.)
         */
        @Bean
        open fun minioStorageAdapter(
            minioClient: MinioClient,
            properties: MinioStorageConfig.Properties,
        ): MinioStorageAdapter {
            val adapter = MinioStorageAdapter(minioClient, properties)
            adapter.ensureBucket()
            return adapter
        }

        /** [AttachmentRepository] — issue_attachments 테이블 jOOQ 저장소. */
        @Bean
        open fun attachmentRepository(dsl: DSLContext): AttachmentRepository =
            AttachmentRepository(dsl)

        /** [IssueAttachmentService] — upload/list/download/delete 유스케이스 서비스. */
        @Bean
        open fun issueAttachmentService(
            storagePort: MinioStorageAdapter,
            attachmentRepository: AttachmentRepository,
            permissionResolver: IssuePermissionResolver,
            issueRepository: IssueRepository,
            clock: Clock,
        ): IssueAttachmentService =
            IssueAttachmentService(
                storagePort = storagePort,
                attachmentRepository = attachmentRepository,
                permissionResolver = permissionResolver,
                issueRepository = issueRepository,
                clock = clock,
            )

        /** [IssueAttachmentController] — 4개 엔드포인트 컨트롤러. */
        @Bean
        open fun issueAttachmentController(service: IssueAttachmentService): IssueAttachmentController =
            IssueAttachmentController(service)

        /** [AttachmentExceptionHandler] — 예외→HTTP 변환 핸들러. */
        @Bean
        open fun attachmentExceptionHandler(): AttachmentExceptionHandler =
            AttachmentExceptionHandler()

        /**
         * @Primary IssuePermissionResolver — [DENY_ACTOR_UUID] 는 모든 권한을 거부하고, 나머지는 허용.
         *
         * [AlwaysAllowIssuePermissionResolver] (@Profile("!prod")) 를 @Primary 로 대체한다.
         * IssueVersionLinksIntegrationTest 동형 패턴 (권한 거부 시나리오 실증).
         */
        @Bean
        @Primary
        open fun denyablePermissionResolver(): IssuePermissionResolver =
            object : IssuePermissionResolver {
                override fun hasPermission(
                    actorId: UUID,
                    permission: IssuePermission,
                    scope: IssueScope,
                ): Boolean = actorId != DENY_ACTOR_UUID
            }
    }

    @Autowired
    lateinit var webApplicationContext: WebApplicationContext

    private lateinit var mockMvc: MockMvc

    /**
     * (c) 크기 한도 검증 전용 MockMvc — [SizeLimitFilter](1MB) 를 추가.
     *
     * MockMvc 는 실제 서블릿 컨테이너 없이 동작하므로 Spring 의 `max-file-size` 설정이 발동하지 않는다.
     * [SizeLimitFilter] 가 Content-Length 를 확인해 1MB 초과 시 [MaxUploadSizeExceededException] 을 발생시킨다.
     */
    private lateinit var mockMvcWithSizeFilter: MockMvc

    companion object {
        private const val TEST_BUCKET = "bts-attachments-int-test"
        private const val PROJECT_A = "ATTCH"
        private const val PROJECT_B = "ATTCHB"

        /** 권한 거부 시나리오용 액터 UUID — AttachmentTestConfig.denyablePermissionResolver 가 거부. */
        val DENY_ACTOR_UUID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000099")

        /** 정상 액터 UUID. */
        val ACTOR_UUID: UUID = UUID.fromString("11111111-1111-4111-8111-111111111111")

        private var migrated = false
        private var seeded = false
    }

    // ── 초기화 ───────────────────────────────────────────────────────────────────

    @BeforeAll
    fun setUpAll() {
        if (!migrated) {
            applyMigrations()
            migrated = true
        }
        if (!seeded) {
            seedProjects()
            seeded = true
        }
    }

    @BeforeEach
    fun setUpEach() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build()
        // (c) 크기 한도 전용 MockMvc — SizeLimitFilter(1MB) 장착.
        // prod 100MB 값은 application-test.yml 에 보존 (설정값 검증은 주석으로 명시, 명세 CONCERN-B).
        mockMvcWithSizeFilter = MockMvcBuilders.webAppContextSetup(webApplicationContext)
            .addFilter(SizeLimitFilter(maxBytes = 1 * 1024 * 1024L))
            .build()
        // 정상 액터로 SecurityContext 를 초기화한다.
        // DENY_ACTOR 시나리오는 개별 테스트에서 교체한다.
        setActor(ACTOR_UUID)

        // 각 테스트 전 issue_attachments + issues 를 정리해 독립성을 보장한다.
        conn().use { c ->
            c.createStatement().use { stmt ->
                stmt.execute("DELETE FROM issue_attachments")
                stmt.execute("DELETE FROM issues WHERE key LIKE '$PROJECT_A-%' OR key LIKE '$PROJECT_B-%'")
                stmt.execute("UPDATE projects SET key_sequence = 0 WHERE key IN ('$PROJECT_A', '$PROJECT_B')")
            }
        }
    }

    @AfterEach
    fun clearSecurityContext() {
        SecurityContextHolder.clearContext()
    }

    // ── (a) 라운드트립 ────────────────────────────────────────────────────────────

    /**
     * (a) 라운드트립 — 업로드 → 목록 1건 → 다운로드 바이트 동일 → 삭제 204.
     *
     * Given  이슈 1건 시드
     * When   POST 업로드 (multipart, 512바이트)
     * Then   201 Created + body.data.id 존재
     * When   GET 목록
     * Then   200 + data 배열 1건 + data[0].id == 업로드 id
     * When   GET /{id} 다운로드
     * Then   200 + 응답 바이트 == 업로드 바이트 (동일성 단언)
     * When   DELETE /{id}
     * Then   204 No Content
     */
    @Test
    fun `(a) 라운드트립 — 업로드 후 목록 1건 + 다운로드 바이트 동일 + 삭제 204`() {
        val issueKey = insertIssue(PROJECT_A, "라운드트립 검증 이슈")
        val fileBytes = buildTestBytes(size = 512)
        val file = MockMultipartFile("file", "round-trip.bin", "application/octet-stream", fileBytes)

        // POST 업로드 → 201
        val uploadResult = mockMvc.perform(
            multipart("/api/v1/issues/$issueKey/attachments").file(file),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.data.id").exists())
            .andExpect(jsonPath("$.data.filename").value("round-trip.bin"))
            .andExpect(jsonPath("$.data.sizeBytes").value(512))
            .andReturn()

        val attachmentId = extractAttachmentId(uploadResult.response.contentAsString)

        // GET 목록 → 200, 1건
        mockMvc.perform(get("/api/v1/issues/$issueKey/attachments"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data").isArray)
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].id").value(attachmentId.toString()))

        // GET /{id} 다운로드 → 200, 바이트 동일성 단언
        val downloadResult = mockMvc.perform(get("/api/v1/issues/$issueKey/attachments/$attachmentId"))
            .andExpect(status().isOk)
            .andReturn()

        val downloadedBytes = downloadResult.response.contentAsByteArray
        assert(downloadedBytes.contentEquals(fileBytes)) {
            "다운로드 바이트가 업로드 바이트와 다릅니다. 업로드=${fileBytes.size}, 다운로드=${downloadedBytes.size}"
        }

        // DELETE → 204
        mockMvc.perform(delete("/api/v1/issues/$issueKey/attachments/$attachmentId"))
            .andExpect(status().isNoContent)
    }

    // ── (b) 삭제 후 부재 ──────────────────────────────────────────────────────────

    /**
     * (b) 삭제 후 부재 — DELETE 후 목록 0건 + 다운로드 404.
     *
     * Given  이슈 1건 + 첨부 업로드
     * When   DELETE /{id}
     * Then   204
     * When   GET 목록
     * Then   200 + data 배열 0건
     * When   GET /{id} 다운로드
     * Then   404 ISSUE_NOT_FOUND
     */
    @Test
    fun `(b) 삭제 후 부재 — 목록 0건 + 다운로드 404`() {
        val issueKey = insertIssue(PROJECT_A, "삭제 후 부재 검증 이슈")
        val attachmentId = uploadSmallFile(issueKey)

        // DELETE → 204
        mockMvc.perform(delete("/api/v1/issues/$issueKey/attachments/$attachmentId"))
            .andExpect(status().isNoContent)

        // GET 목록 → 0건
        mockMvc.perform(get("/api/v1/issues/$issueKey/attachments"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.length()").value(0))

        // GET /{id} 다운로드 → 404
        // DB 행이 삭제됐으므로 findAttachmentForIssue → IssueNotFoundException → 404.
        mockMvc.perform(get("/api/v1/issues/$issueKey/attachments/$attachmentId"))
            .andExpect(status().isNotFound)
    }

    // ── (c) 크기 한도 413 ────────────────────────────────────────────────────────

    /**
     * (c) 크기 한도 초과 → 413 Payload Too Large.
     *
     * MockMvc 환경에서는 Spring의 `spring.servlet.multipart.max-file-size` 속성이 실제 서블릿 컨테이너
     * 없이는 발동하지 않는다(MockHttpServletRequest 는 getParts() 크기 검증을 생략).
     * 따라서 이 테스트는 `mockMvcWithSizeFilter` — [SizeLimitFilter] 를 통해 1MB 임계값을 초과하는
     * 요청을 받으면 [MaxUploadSizeExceededException] 을 직접 발생시키는 전용 MockMvc 인스턴스 — 를 사용한다.
     *
     * [AttachmentExceptionHandler] 는 [MaxUploadSizeExceededException] → 413 으로 변환한다.
     *
     * prod 설정값(100MB)은 `application-test.yml` 에 존재한다(실제 100MB 스트림 생성 금지).
     *
     * @see application-test.yml (spring.servlet.multipart.max-file-size=100MB — prod 기본값)
     * @see SizeLimitFilter 테스트 전용 크기 제한 필터 (Content-Length 기반 1MB 임계값)
     */
    @Test
    fun `(c) 크기 한도 초과 — 413 Payload Too Large`() {
        val issueKey = insertIssue(PROJECT_A, "크기 한도 검증 이슈")
        // 2MB = 테스트 전용 1MB 한도(SizeLimitFilter) 초과. prod 100MB 스트림 생성 금지.
        val oversizedFile = MockMultipartFile(
            "file",
            "big.bin",
            "application/octet-stream",
            ByteArray(2 * 1024 * 1024) { 0 },
        )

        // mockMvc 대신 SizeLimitFilter 가 장착된 전용 인스턴스를 사용한다.
        mockMvcWithSizeFilter.perform(
            multipart("/api/v1/issues/$issueKey/attachments").file(oversizedFile),
        )
            .andExpect(status().isPayloadTooLarge)
    }

    // ── (d) 권한 거부 ─────────────────────────────────────────────────────────────

    /**
     * (d-1) UPDATE 미보유 액터 — 업로드 403.
     *
     * Given  DENY_ACTOR 로 SecurityContext 설정
     * When   POST 업로드
     * Then   403 Forbidden
     */
    @Test
    fun `(d-1) UPDATE 권한 미보유 액터 업로드 — 403 Forbidden`() {
        val issueKey = insertIssue(PROJECT_A, "권한 거부 업로드 검증 이슈")
        setActor(DENY_ACTOR_UUID)

        val file = MockMultipartFile("file", "denied.bin", "application/octet-stream", byteArrayOf(1, 2, 3))

        mockMvc.perform(
            multipart("/api/v1/issues/$issueKey/attachments").file(file),
        )
            .andExpect(status().isForbidden)
    }

    /**
     * (d-2) UPDATE 미보유 액터 — 삭제 403.
     *
     * Given  정상 액터로 업로드 후, DENY_ACTOR 로 전환
     * When   DELETE
     * Then   403 Forbidden
     */
    @Test
    fun `(d-2) UPDATE 권한 미보유 액터 삭제 — 403 Forbidden`() {
        val issueKey = insertIssue(PROJECT_A, "권한 거부 삭제 검증 이슈")
        val attachmentId = uploadSmallFile(issueKey)

        setActor(DENY_ACTOR_UUID)

        mockMvc.perform(delete("/api/v1/issues/$issueKey/attachments/$attachmentId"))
            .andExpect(status().isForbidden)
    }

    /**
     * (d-3) VIEW 미보유 액터 — 목록 403.
     *
     * Given  DENY_ACTOR 로 SecurityContext 설정
     * When   GET 목록
     * Then   403 Forbidden
     */
    @Test
    fun `(d-3) VIEW 권한 미보유 액터 목록 — 403 Forbidden`() {
        val issueKey = insertIssue(PROJECT_A, "권한 거부 목록 검증 이슈")
        setActor(DENY_ACTOR_UUID)

        mockMvc.perform(get("/api/v1/issues/$issueKey/attachments"))
            .andExpect(status().isForbidden)
    }

    /**
     * (d-4) VIEW 미보유 액터 — 다운로드 403.
     *
     * Given  정상 액터로 업로드 후, DENY_ACTOR 로 전환
     * When   GET /{id} 다운로드
     * Then   403 Forbidden
     */
    @Test
    fun `(d-4) VIEW 권한 미보유 액터 다운로드 — 403 Forbidden`() {
        val issueKey = insertIssue(PROJECT_A, "권한 거부 다운로드 검증 이슈")
        val attachmentId = uploadSmallFile(issueKey)

        setActor(DENY_ACTOR_UUID)

        mockMvc.perform(get("/api/v1/issues/$issueKey/attachments/$attachmentId"))
            .andExpect(status().isForbidden)
    }

    // ── (e) 교차 이슈 격리 ───────────────────────────────────────────────────────

    /**
     * (e-1) 교차 이슈 다운로드 → 404.
     *
     * Given  이슈 A 에 첨부를 업로드, 이슈 B 존재
     * When   GET /api/v1/issues/{issueB}/attachments/{attachmentId-of-A}
     * Then   404 — issueId 불일치로 [IssueNotFoundException] 발생
     */
    @Test
    fun `(e-1) 교차 이슈 다운로드 — 이슈 B 경로로 이슈 A 첨부 접근 404`() {
        val issueKeyA = insertIssue(PROJECT_A, "교차 이슈 원본 이슈 A")
        val issueKeyB = insertIssue(PROJECT_B, "교차 이슈 타 이슈 B")
        val attachmentId = uploadSmallFile(issueKeyA)

        mockMvc.perform(get("/api/v1/issues/$issueKeyB/attachments/$attachmentId"))
            .andExpect(status().isNotFound)
    }

    /**
     * (e-2) 교차 이슈 삭제 → 404.
     *
     * Given  이슈 A 에 첨부를 업로드, 이슈 B 존재
     * When   DELETE /api/v1/issues/{issueB}/attachments/{attachmentId-of-A}
     * Then   404
     */
    @Test
    fun `(e-2) 교차 이슈 삭제 — 이슈 B 경로로 이슈 A 첨부 삭제 404`() {
        val issueKeyA = insertIssue(PROJECT_A, "교차 이슈 삭제 원본 A")
        val issueKeyB = insertIssue(PROJECT_B, "교차 이슈 삭제 타 B")
        val attachmentId = uploadSmallFile(issueKeyA)

        mockMvc.perform(delete("/api/v1/issues/$issueKeyB/attachments/$attachmentId"))
            .andExpect(status().isNotFound)
    }

    // ── private helpers ───────────────────────────────────────────────────────────

    /**
     * Flyway 마이그레이션 적용.
     * [TestConfig.postgres] 의 동일 컨테이너에 issue-tracking + project-workflow 마이그레이션을 적용한다.
     * V023__issue_attachments.sql 포함.
     */
    private fun applyMigrations() {
        Flyway.configure()
            .dataSource(
                TestConfig.postgres.jdbcUrl,
                TestConfig.postgres.username,
                TestConfig.postgres.password,
            )
            .placeholderReplacement(false)
            .locations(
                "classpath:db/migration/issue-tracking",
                "classpath:db/migration/project-workflow",
            )
            .load()
            .migrate()
    }

    /**
     * 통합 테스트 전용 프로젝트 두 건 시드.
     * [PROJECT_A] — 라운드트립/삭제/권한/크기 시나리오용.
     * [PROJECT_B] — 교차 이슈 시나리오용.
     */
    private fun seedProjects() {
        conn().use { c ->
            for (pair in listOf(PROJECT_A to "Attachment Test Project A", PROJECT_B to "Attachment Test Project B")) {
                c.prepareStatement(
                    "INSERT INTO projects (key, name) VALUES (?, ?) ON CONFLICT (key) DO NOTHING",
                ).use { stmt ->
                    stmt.setString(1, pair.first)
                    stmt.setString(2, pair.second)
                    stmt.executeUpdate()
                }
            }
        }
    }

    /**
     * 이슈를 DB에 직접 삽입하고 이슈 키를 반환한다.
     *
     * @param projectKey 프로젝트 키.
     * @param summary 이슈 제목.
     * @return 생성된 이슈 키 (예: "ATTCH-1").
     */
    private fun insertIssue(projectKey: String, summary: String): String {
        return conn().use { c ->
            c.autoCommit = false
            val seq = c.prepareStatement(
                "UPDATE projects SET key_sequence = key_sequence + 1 WHERE key = ? RETURNING key_sequence",
            ).use { stmt ->
                stmt.setString(1, projectKey)
                stmt.executeQuery().use { rs ->
                    rs.next()
                    rs.getLong(1)
                }
            }
            val issueKey = "$projectKey-$seq"
            val projectId = c.prepareStatement("SELECT id FROM projects WHERE key = ?").use { stmt ->
                stmt.setString(1, projectKey)
                stmt.executeQuery().use { rs ->
                    rs.next()
                    rs.getObject(1) as UUID
                }
            }
            val taskTypeId = c.prepareStatement(
                "SELECT id FROM issue_types WHERE key = 'task' AND deleted_at IS NULL LIMIT 1",
            ).use { stmt ->
                stmt.executeQuery().use { rs ->
                    check(rs.next()) { "task 타입 없음 — V003 마이그레이션 확인 필요." }
                    rs.getLong(1)
                }
            }
            c.prepareStatement(
                "INSERT INTO issues (key, project_id, summary, reporter_id, current_state_key, version, type_id) " +
                    "VALUES (?, ?, ?, ?, 'open', 1, ?)",
            ).use { stmt ->
                stmt.setString(1, issueKey)
                stmt.setObject(2, projectId)
                stmt.setString(3, summary)
                stmt.setObject(4, ACTOR_UUID)
                stmt.setLong(5, taskTypeId)
                stmt.executeUpdate()
            }
            c.commit()
            issueKey
        }
    }

    /**
     * 작은 파일(64바이트)을 업로드하고 [UUID] 를 반환하는 헬퍼.
     * 삭제/권한/교차 이슈 시나리오에서 공통으로 사용한다.
     *
     * @param issueKey 업로드 대상 이슈 키.
     * @return 업로드된 첨부 UUID.
     */
    private fun uploadSmallFile(issueKey: String): UUID {
        val file = MockMultipartFile(
            "file",
            "small.txt",
            "text/plain",
            buildTestBytes(size = 64),
        )
        val result = mockMvc.perform(
            multipart("/api/v1/issues/$issueKey/attachments").file(file),
        )
            .andExpect(status().isCreated)
            .andReturn()
        return extractAttachmentId(result.response.contentAsString)
    }

    /**
     * JSON 응답 문자열에서 `data.id` 필드를 파싱해 [UUID] 로 반환한다.
     *
     * @param json 응답 JSON 문자열.
     * @return 첨부 UUID.
     */
    private fun extractAttachmentId(json: String): UUID {
        // {"data":{"id":"xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx",...}}
        val match = Regex(""""id"\s*:\s*"([0-9a-f\-]{36})"""").find(json)
            ?: error("응답 JSON 에서 data.id 를 찾을 수 없음: $json")
        return UUID.fromString(match.groupValues[1])
    }

    /**
     * 지정된 크기의 순차 바이트 배열을 생성한다.
     * 다운로드 동일성 단언에 사용할 수 있도록 고정 패턴(0x00~0xFF 순환)을 사용한다.
     *
     * @param size 생성할 바이트 수.
     * @return 고정 패턴 바이트 배열.
     */
    private fun buildTestBytes(size: Int): ByteArray =
        ByteArray(size) { idx -> (idx % 256).toByte() }

    /**
     * SecurityContext 의 인증 주체를 교체한다.
     *
     * @param actorUuid 교체할 액터 UUID.
     */
    private fun setActor(actorUuid: UUID) {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(
                actorUuid.toString(),
                null,
                listOf(SimpleGrantedAuthority("ROLE_USER")),
            )
    }

    /** [TestConfig.postgres] 에 직접 연결하는 JDBC 커넥션을 반환한다. */
    private fun conn() = DriverManager.getConnection(
        TestConfig.postgres.jdbcUrl,
        TestConfig.postgres.username,
        TestConfig.postgres.password,
    )
}

/**
 * (c) 크기 한도 시나리오 전용 MockMvc 필터.
 *
 * MockMvc 는 실제 서블릿 컨테이너 없이 동작하므로 `spring.servlet.multipart.max-file-size` 속성이
 * [org.springframework.web.multipart.MaxUploadSizeExceededException] 을 자동 발생시키지 않는다.
 * 이 필터는 `Content-Length` 헤더를 확인해 [maxBytes] 초과 시 예외를 발생시켜 413 경로를 실증한다.
 *
 * @param maxBytes 허용 최대 바이트 수. 이 값을 초과하는 Content-Length 는 거부된다.
 */
class SizeLimitFilter(private val maxBytes: Long) : Filter {
    override fun doFilter(request: ServletRequest, response: ServletResponse, chain: FilterChain) {
        val contentLength = (request as HttpServletRequest).contentLengthLong
        if (contentLength > maxBytes) {
            throw MaxUploadSizeExceededException(maxBytes)
        }
        chain.doFilter(request, response)
    }
}
