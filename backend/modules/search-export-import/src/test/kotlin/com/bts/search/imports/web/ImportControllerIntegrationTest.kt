// ImportController 첨부 zip 업로드 통합테스트 — 실 MinIO+PostgreSQL Testcontainers, POST /imports 두-part 계약 (FR-IM-01 PR4 Task 7)

package com.bts.search.imports.web

import com.bts.search.imports.job.application.ImportJobService
import com.bts.search.imports.job.event.ImportJobEnqueuePublisher
import com.bts.search.imports.job.repository.ImportJobRepository
import com.bts.search.imports.job.storage.MinioImportStorageAdapter
import com.bts.search.imports.job.storage.MinioImportStorageConfig
import com.bts.search.imports.job.storage.MinioImportStorageException
import com.bts.search.jooq.tables.references.IMPORT_JOBS
import com.bts.shared.permission.IssuePermission
import com.bts.shared.permission.IssuePermissionResolver
import com.bts.shared.permission.IssueScope
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.minio.MinioClient
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.mock.web.MockMultipartFile
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.junit.jupiter.SpringExtension
import org.springframework.test.context.web.WebAppConfiguration
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.EnableTransactionManagement
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.context.WebApplicationContext
import org.springframework.web.servlet.config.annotation.EnableWebMvc
import org.testcontainers.containers.MinIOContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * [ImportController] 첨부 zip 업로드(PR4 Task 7) 풀스택 통합테스트.
 *
 * 컨트롤러 → 서비스 → jOOQ Repository → **실 PostgreSQL(Testcontainers)** + **실 MinIO(Testcontainers)**
 * end-to-end. `POST /api/v1/imports` 가 `file`(매니페스트) + `attachmentsZip`(첨부, optional) 두
 * multipart part 를 받아 zip 을 별도 오브젝트로 저장하고 `import_jobs.attachments_object_key` 에
 * 기록하는지 검증한다.
 *
 * cross-BC 포트([IssuePermissionResolver])는 search test-boot 에 실 구현이 없으므로
 * [TestConfig.StubIssuePermissionResolver] 로 대체한다 — 기본 빈 집합이라 **fail-closed**(모두 거부)이며
 * 테스트가 actor UUID 를 명시 등록해야 통과한다(fail-open 금지, 교훈 crossbc-resolver-nullable-fail-open).
 * [OutboundWebhookControllerIntegrationTest][com.bts.search.webhook.web.OutboundWebhookControllerIntegrationTest]
 * 의 `StubSystemPermissionResolver` 와 동일 패턴이다.
 *
 * ## 검증 (plan Task 7 RED 시나리오)
 * - (i) JSON + attachmentsZip → zip 이 `{projectKey}/{jobId}-attachments.zip` 키로 MinIO 에 별도 저장되고
 *   `import_jobs.attachments_object_key` 에 기록된다.
 * - (ii) attachmentsZip 미첨부 → attachments_object_key = null (하위호환).
 * - (iii) CSV + attachmentsZip → zip 은 무시되어 attachments_object_key = null 이고 MinIO 에도 저장되지 않는다.
 *
 * `attachmentsObjectKey` 는 [com.bts.search.imports.web.dto.ImportJobResponse] 에 노출되지 않으므로
 * (Task 7 허용 파일 범위 밖 — 폴링 API 노출은 향후 Task 로 남는다), 이 테스트는 DB([dsl])를 직접
 * 조회해 검증한다.
 *
 * ## 트랜잭션
 * 형제 [WebhookIntegrationConfig][com.bts.search.webhook.web.WebhookIntegrationConfig] 와 달리
 * [ImportJobService] 생성자가 [TransactionTemplate] 을 직접 요구하므로(persist+enqueue 트랜잭션 경계,
 * [ImportJobService] KDoc §트랜잭션 경계 참조) 실 [PlatformTransactionManager] 를 등록한다
 * ([com.bts.search.export.job.event.ExportJobEnqueuePublisherTest] 동일 패턴).
 */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [ImportControllerIntegrationTest.TestConfig::class])
@WebAppConfiguration
class ImportControllerIntegrationTest {
    @Configuration
    @EnableWebMvc
    @EnableTransactionManagement(proxyTargetClass = true)
    open class TestConfig {
        @Bean
        open fun dataSource(): DriverManagerDataSource {
            Flyway
                .configure()
                .dataSource(pg.jdbcUrl, pg.username, pg.password)
                .placeholderReplacement(false)
                .locations("classpath:db/migration/search-export-import")
                .load()
                .migrate()
            return DriverManagerDataSource(pg.jdbcUrl, pg.username, pg.password)
        }

        @Bean
        open fun transactionManager(dataSource: DriverManagerDataSource): PlatformTransactionManager =
            DataSourceTransactionManager(dataSource)

        @Bean
        open fun dslContext(dataSource: DriverManagerDataSource): DSLContext {
            return DSL.using(dataSource, SQLDialect.POSTGRES)
        }

        @Bean
        open fun transactionTemplate(transactionManager: PlatformTransactionManager): TransactionTemplate =
            TransactionTemplate(transactionManager)

        @Bean
        open fun repository(dsl: DSLContext): ImportJobRepository = ImportJobRepository(dsl)

        @Bean
        open fun enqueuePublisher(dsl: DSLContext): ImportJobEnqueuePublisher = ImportJobEnqueuePublisher(dsl)

        @Bean
        open fun minioClient(): MinioClient =
            MinioClient.builder()
                .endpoint(minio.s3URL)
                .credentials(minio.userName, minio.password)
                .build()

        @Bean
        open fun storageProperties(): MinioImportStorageConfig.Properties =
            MinioImportStorageConfig.Properties(
                endpoint = minio.s3URL,
                accessKey = minio.userName,
                secretKey = minio.password,
                importBucket = TEST_BUCKET,
            )

        /** [MinioImportStorageAdapter.ensureBucket] 을 빈 생성 시점에 즉시 호출해 테스트 실행 전 버킷을 보장한다. */
        @Bean
        open fun storage(
            minioClient: MinioClient,
            properties: MinioImportStorageConfig.Properties,
        ): MinioImportStorageAdapter = MinioImportStorageAdapter(minioClient, properties).apply { ensureBucket() }

        @Bean
        open fun permissionResolver(): StubIssuePermissionResolver = StubIssuePermissionResolver()

        @Bean
        open fun service(
            repository: ImportJobRepository,
            storage: MinioImportStorageAdapter,
            enqueuePublisher: ImportJobEnqueuePublisher,
            permissionResolver: StubIssuePermissionResolver,
            transactionTemplate: TransactionTemplate,
        ): ImportJobService {
            return ImportJobService(repository, storage, enqueuePublisher, permissionResolver, transactionTemplate)
        }

        @Bean
        open fun controller(service: ImportJobService): ImportController = ImportController(service)

        @Bean
        open fun exceptionHandler(): ImportExceptionHandler = ImportExceptionHandler()

        /**
         * 테스트가 actor UUID 를 명시 등록하는 fail-closed [IssuePermissionResolver] stub.
         * [allowed] 집합에 등록된 UUID 만 CREATE_ISSUE 로 판정한다. 기본은 비어 있어 모두 거부한다.
         */
        class StubIssuePermissionResolver : IssuePermissionResolver {
            val allowed: MutableSet<UUID> = ConcurrentHashMap.newKeySet()

            override fun hasPermission(
                actorId: UUID,
                permission: IssuePermission,
                scope: IssueScope,
            ): Boolean = actorId in allowed
        }

        companion object {
            /**
             * JVM 단위 singleton PostgreSQL container.
             * quay.io/tembo/pg16-pgmq:latest — search-export-import 마이그레이션 체인의 V602(pgmq 확장)
             * 때문에 postgres:16-alpine 으로는 실패한다(ADR 2026-05-22-pgmq-postgres-image, 형제
             * `WebhookIntegrationConfig` 동일 패턴). Ryuk 이 JVM 종료 시 자동 정리한다.
             */
            @JvmStatic
            val pg: PostgreSQLContainer<*> =
                PostgreSQLContainer(
                    DockerImageName
                        .parse("quay.io/tembo/pg16-pgmq:latest")
                        .asCompatibleSubstituteFor("postgres"),
                ).withDatabaseName("bts_import_controller_it")
                    .withUsername("bts")
                    .withPassword("bts_test")
                    .apply { start() }

            /** JVM 단위 singleton MinIO container — `MinioImportStorageAdapterTest` 와 동일 pinned 버전. */
            @JvmStatic
            val minio: MinIOContainer =
                MinIOContainer(
                    DockerImageName.parse("quay.io/minio/minio:RELEASE.2023-09-04T19-57-37Z")
                        .asCompatibleSubstituteFor("minio/minio"),
                )
                    .apply { start() }

            /** 이 테스트 전용 버킷 — 다른 Import 통합테스트의 `bts-imports-test` 와 물리적으로 분리한다. */
            const val TEST_BUCKET = "bts-imports-controller-it"
        }
    }

    @Autowired
    private lateinit var webApplicationContext: WebApplicationContext

    @Autowired
    private lateinit var dsl: DSLContext

    @Autowired
    private lateinit var storage: MinioImportStorageAdapter

    @Autowired
    private lateinit var permissionResolver: TestConfig.StubIssuePermissionResolver

    private lateinit var mockMvc: MockMvc
    private val mapper: ObjectMapper = ObjectMapper().registerKotlinModule()
    private val actorId: UUID = UUID.fromString("aaaaaaaa-0000-0000-0000-0000000000a1")
    private val projectKey = "ATLAS"

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build()
        dsl.deleteFrom(IMPORT_JOBS).execute()
        permissionResolver.allowed.clear()
        permissionResolver.allowed.add(actorId)
        authenticate(actorId)
    }

    @AfterEach
    fun tearDown() {
        SecurityContextHolder.clearContext()
    }

    // ── (i) JSON + attachmentsZip → zip 별도 저장 + attachments_object_key 기록 ──────

    @Test
    fun `JSON 매니페스트와 attachmentsZip 함께 업로드하면 zip이 별도 오브젝트로 저장되고 attachments_object_key에 기록된다`() {
        val zipBytes = "PKfake-zip-bytes".toByteArray()

        val json = performUpload(jsonFile(), zipFile(zipBytes), format = "JSON")
        val jobId = extractJobId(json)

        val storedKey =
            fetchAttachmentsObjectKey(jobId)
                ?: error("attachments_object_key 가 null 입니다 — zip 저장이 수행되지 않았습니다.")
        assertThat(storedKey).isEqualTo("$projectKey/$jobId-attachments.zip")

        val storedBytes = storage.get(storedKey).use { it.readBytes() }
        assertThat(storedBytes).isEqualTo(zipBytes)
    }

    // ── (ii) attachmentsZip 없음 → key=null(하위호환) ────────────────────────────

    @Test
    fun `attachmentsZip part 없이 업로드하면 attachments_object_key는 null이다`() {
        val json =
            mockMvc
                .perform(
                    multipart("/api/v1/imports")
                        .file(jsonFile())
                        .param("projectKey", projectKey)
                        .param("format", "JSON"),
                )
                .andExpect(status().isAccepted)
                .andReturn()
                .response.contentAsString
        val jobId = extractJobId(json)

        assertThat(fetchAttachmentsObjectKey(jobId)).isNull()
    }

    // ── (iii) CSV + attachmentsZip → zip 무시(key=null, MinIO 미저장) ────────────

    @Test
    fun `CSV 매니페스트와 attachmentsZip 함께 업로드하면 zip은 무시되어 attachments_object_key가 null이고 MinIO에도 저장되지 않는다`() {
        val zipBytes = "PKignored-zip-bytes".toByteArray()

        val json = performUpload(csvFile(), zipFile(zipBytes), format = "CSV")
        val jobId = extractJobId(json)

        assertThat(fetchAttachmentsObjectKey(jobId)).isNull()

        val wouldBeKey = "$projectKey/$jobId-attachments.zip"
        assertThatThrownBy { storage.get(wouldBeKey) }
            .isInstanceOf(MinioImportStorageException::class.java)
    }

    // ── private helpers ────────────────────────────────────────────────────────

    private fun performUpload(
        manifest: MockMultipartFile,
        zip: MockMultipartFile,
        format: String,
    ): String =
        mockMvc
            .perform(
                multipart("/api/v1/imports")
                    .file(manifest)
                    .file(zip)
                    .param("projectKey", projectKey)
                    .param("format", format),
            )
            .andExpect(status().isAccepted)
            .andReturn()
            .response.contentAsString

    private fun extractJobId(json: String): String = mapper.readValue<Map<String, Any?>>(json)["jobId"].toString()

    private fun fetchAttachmentsObjectKey(jobId: String): String? =
        dsl
            .select(IMPORT_JOBS.ATTACHMENTS_OBJECT_KEY)
            .from(IMPORT_JOBS)
            .where(IMPORT_JOBS.ID.eq(UUID.fromString(jobId)))
            .fetchOne(IMPORT_JOBS.ATTACHMENTS_OBJECT_KEY)

    private fun jsonFile(): MockMultipartFile =
        MockMultipartFile(
            "file",
            "issues.json",
            "application/json",
            """[{"summary":"Test issue"}]""".toByteArray(),
        )

    private fun csvFile(): MockMultipartFile =
        MockMultipartFile(
            "file",
            "issues.csv",
            "text/csv",
            "summary\nTest issue\n".toByteArray(),
        )

    private fun zipFile(content: ByteArray): MockMultipartFile =
        MockMultipartFile("attachmentsZip", "attachments.zip", "application/zip", content)

    private fun authenticate(userId: UUID) {
        val auth =
            UsernamePasswordAuthenticationToken(
                userId.toString(),
                null,
                listOf(SimpleGrantedAuthority("ROLE_USER")),
            )
        SecurityContextHolder.getContext().authentication = auth
    }
}
