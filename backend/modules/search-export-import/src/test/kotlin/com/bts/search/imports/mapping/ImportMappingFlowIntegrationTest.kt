// FR-IM-02 매핑 흐름 전체 통합테스트 — analyze→confirm→워커 처리(COMPLETED)까지 실 PostgreSQL(pgmq)+MinIO
// Testcontainers 로 관통하고, canonical 즉시경로(accept) 회귀도 같이 검증한다 (PR-A Task 10)

package com.bts.search.imports.mapping

import com.bts.search.imports.job.application.ImportAcceptCommand
import com.bts.search.imports.job.application.ImportAnalyzeCommand
import com.bts.search.imports.job.application.ImportErrorLogWriter
import com.bts.search.imports.job.application.ImportJobProcessor
import com.bts.search.imports.job.application.ImportJobService
import com.bts.search.imports.job.domain.ImportJobId
import com.bts.search.imports.job.domain.ImportJobStatus
import com.bts.search.imports.job.event.ImportJobEnqueuePublisher
import com.bts.search.imports.job.repository.ImportJobRepository
import com.bts.search.imports.job.storage.MinioImportStorageAdapter
import com.bts.search.imports.job.storage.MinioImportStorageConfig
import com.bts.search.imports.job.worker.ImportJobWorker
import com.bts.search.imports.mapping.repository.ImportMappingRepository
import com.bts.search.imports.mapping.repository.ImportUserMappingRepository
import com.bts.search.jooq.tables.references.IMPORT_JOBS
import com.bts.shared.issue.ImportAttachmentSource
import com.bts.shared.issue.IssueImportCommand
import com.bts.shared.issue.IssueImportPort
import com.bts.shared.issue.IssueImportResult
import com.bts.shared.permission.IssuePermission
import com.bts.shared.permission.IssuePermissionResolver
import com.bts.shared.permission.IssueScope
import com.bts.shared.user.UserLookupPort
import io.minio.MinioClient
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.junit.jupiter.SpringExtension
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.EnableTransactionManagement
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.MinIOContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.io.ByteArrayInputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * FR-IM-02 매핑 UI 2단계 흐름(`analyze → confirm → 워커 처리`) 풀스택 통합테스트.
 *
 * [ImportJobService.analyze] → [ImportMappingService.confirm] → [ImportJobWorker.pollAndProcess]
 * (내부에서 [ImportJobProcessor.process] 호출) 을 **실 PostgreSQL(pgmq, Testcontainers)** +
 * **실 MinIO(Testcontainers)** 위에서 관통시켜, 임의 헤더(비-canonical) CSV 가 사용자가 확정한 필드
 * 매핑대로 이슈 생성 커맨드로 변환되는지 검증한다.
 *
 * `IssueImportPort`(shared-kernel cross-BC 쓰기 포트)는 search 모듈 test-boot 에 실 구현이 없으므로
 * [TestConfig.CapturingIssueImportPort] 로 대체한다 — [ImportControllerIntegrationTest][com.bts.search.imports.web.ImportControllerIntegrationTest]
 * 의 `StubIssuePermissionResolver` 와 동일하게 test-assembled 표준(no-cross-bc-deployment-assembly)을
 * 따르되, 이 테스트는 success 결과를 반환하며 전달받은 [IssueImportCommand] 를 캡처해 매핑 결과를
 * 실제로 단언한다(vacuous 금지 — "COMPLETED 만 확인"으로 끝내지 않는다).
 *
 * ## 검증 (plan Task 10 RED 시나리오)
 * - (i) 임의 헤더 CSV(`Título`/`담당`/`비고`) 를 [ImportJobService.analyze] 로 접수하면
 *   status=AWAITING_MAPPING, sourceFields 에 세 헤더가 감지된다.
 * - (ii) [ImportMappingService.confirm] 으로 `Título→summary, 담당→assignee, 비고→IGNORE` 매핑을
 *   확정하면 status=PENDING 으로 전이하고 [ImportMappingRepository] 에 매핑이 저장된다.
 * - (iii) [ImportJobWorker.pollAndProcess] 로 워커를 직접 호출하면 COMPLETED 로 전환되고,
 *   [TestConfig.CapturingIssueImportPort] 가 캡처한 커맨드의 `summary` 가 매핑된 소스 셀 값
 *   (`"버그입니다"`)과 일치한다 — `비고`(IGNORE) 컬럼 값은 어떤 필드에도 반영되지 않는다.
 * - (iv) canonical 헤더(`summary`) CSV 를 매핑 없이 [ImportJobService.accept] 즉시경로로 접수해도
 *   여전히 정상 처리된다(회귀) — [ImportMappingRepository] 에 매핑 행이 없어도 canonical 파싱이
 *   그대로 동작해야 한다.
 * - (v)(선택) [ImportMappingService.confirm] 을 `dryRun=true` 로 호출하면 `import_jobs.dry_run` 컬럼이
 *   영속되고, 워커가 그 값을 읽어 dry-run 커맨드로 처리한다(dryrun-fix 회귀 방지).
 *
 * ## 트랜잭션 배선
 *
 * [ImportJobService]/[ImportMappingService] 생성자가 [TransactionTemplate] 을 직접 요구하므로
 * (persist+enqueue 원자성, `ImportJobService` KDoc §트랜잭션 경계) 실 [PlatformTransactionManager] 를
 * 등록한다. `dataSource`/`transactionManager`/`dslContext` 세 빈이 **동일한 [DriverManagerDataSource]
 * 인스턴스**를 공유해야 [ImportJobEnqueuePublisher.enqueue]([org.springframework.transaction.annotation.Propagation.MANDATORY])
 * 가 [TransactionTemplate] 이 연 트랜잭션 안에서 정상 동작한다 — [ImportControllerIntegrationTest][com.bts.search.imports.web.ImportControllerIntegrationTest]
 * 와 동일 패턴.
 */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [ImportMappingFlowIntegrationTest.TestConfig::class])
class ImportMappingFlowIntegrationTest {
    @Configuration
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
        open fun importJobRepository(dsl: DSLContext): ImportJobRepository = ImportJobRepository(dsl)

        @Bean
        open fun importMappingRepository(dsl: DSLContext): ImportMappingRepository = ImportMappingRepository(dsl)

        @Bean
        open fun importUserMappingRepository(dsl: DSLContext): ImportUserMappingRepository =
            ImportUserMappingRepository(dsl)

        /**
         * [UserLookupPort] cross-BC 포트의 test-assembled 최소 stub (no-cross-bc-deployment-assembly) —
         * 이 흐름 테스트는 `confirm` 을 `userMappings` 생략(기본값 빈 목록)으로만 호출하므로
         * [ImportMappingService.confirm] 이 [UserLookupPort] 를 실제로 호출하지 않는다. 배선 컴파일만
         * 목적이라 `exists` 외 override 가 필요 없다.
         */
        @Bean
        open fun userLookupPort(): UserLookupPort =
            object : UserLookupPort {
                override fun exists(userId: UUID): Boolean = false
            }

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
        open fun issueImportPort(): CapturingIssueImportPort = CapturingIssueImportPort()

        @Bean
        open fun errorLogWriter(): ImportErrorLogWriter = ImportErrorLogWriter()

        @Bean
        open fun importJobService(
            repository: ImportJobRepository,
            storage: MinioImportStorageAdapter,
            enqueuePublisher: ImportJobEnqueuePublisher,
            permissionResolver: StubIssuePermissionResolver,
            transactionTemplate: TransactionTemplate,
        ): ImportJobService {
            return ImportJobService(repository, storage, enqueuePublisher, permissionResolver, transactionTemplate)
        }

        @Bean
        open fun importMappingService(
            importMappingRepository: ImportMappingRepository,
            importJobRepository: ImportJobRepository,
            enqueuePublisher: ImportJobEnqueuePublisher,
            storage: MinioImportStorageAdapter,
            transactionTemplate: TransactionTemplate,
            userLookupPort: UserLookupPort,
            importUserMappingRepository: ImportUserMappingRepository,
        ): ImportMappingService {
            return ImportMappingService(
                importMappingRepository,
                importJobRepository,
                enqueuePublisher,
                storage,
                transactionTemplate,
                userLookupPort,
                importUserMappingRepository,
            )
        }

        @Bean
        open fun importJobProcessor(
            issueImportPort: CapturingIssueImportPort,
            storage: MinioImportStorageAdapter,
            importJobRepository: ImportJobRepository,
            importMappingRepository: ImportMappingRepository,
            errorLogWriter: ImportErrorLogWriter,
        ): ImportJobProcessor {
            return ImportJobProcessor(
                issueImportPort,
                storage,
                importJobRepository,
                importMappingRepository,
                errorLogWriter,
            )
        }

        @Bean
        open fun importJobWorker(
            dsl: DSLContext,
            importJobRepository: ImportJobRepository,
            importJobProcessor: ImportJobProcessor,
        ): ImportJobWorker = ImportJobWorker(dsl, importJobRepository, importJobProcessor)

        /**
         * 테스트가 actor UUID 를 명시 등록하는 fail-closed [IssuePermissionResolver] stub.
         * [allowed] 집합에 등록된 UUID 만 CREATE 로 판정한다. 기본은 비어 있어 모두 거부한다.
         */
        class StubIssuePermissionResolver : IssuePermissionResolver {
            val allowed: MutableSet<UUID> = ConcurrentHashMap.newKeySet()

            override fun hasPermission(
                actorId: UUID,
                permission: IssuePermission,
                scope: IssueScope,
            ): Boolean = actorId in allowed
        }

        /**
         * 전달받은 [IssueImportCommand] 를 순서대로 캡처하고 항상 성공을 반환하는
         * [IssueImportPort] stub — 매핑대로 커맨드가 조립됐는지 검증하는 이 테스트의 핵심 장치.
         *
         * [ImportAttachmentSource] 를 받는 2-arg 오버로드만 override 한다 — [IssueImportPort] 의
         * 1-arg default 구현이 `importIssue(cmd, null)` 로 위임하므로([IssueImportPort] KDoc §주의)
         * 첨부 소스가 없는(analyze/accept 모두 zip 미첨부) 이 테스트의 행 처리는 항상 이 메서드로
         * 귀결된다.
         */
        class CapturingIssueImportPort : IssueImportPort {
            val capturedCommands: MutableList<IssueImportCommand> = mutableListOf()

            override fun importIssue(
                cmd: IssueImportCommand,
                attachments: ImportAttachmentSource?,
            ): IssueImportResult {
                capturedCommands += cmd
                return IssueImportResult.success("ATLAS-${capturedCommands.size}")
            }
        }

        companion object {
            /**
             * JVM 단위 singleton PostgreSQL container.
             * quay.io/tembo/pg16-pgmq:latest — V604(pgmq 확장) 때문에 postgres:16-alpine 으로는 실패한다
             * (ADR 2026-05-22-pgmq-postgres-image, `ImportControllerIntegrationTest` 동일 패턴).
             */
            @JvmStatic
            val pg: PostgreSQLContainer<*> =
                PostgreSQLContainer(
                    DockerImageName
                        .parse("quay.io/tembo/pg16-pgmq:latest")
                        .asCompatibleSubstituteFor("postgres"),
                ).withDatabaseName("bts_import_mapping_flow_it")
                    .withUsername("bts")
                    .withPassword("bts_test")
                    .apply { start() }

            /** JVM 단위 singleton MinIO container — `ImportControllerIntegrationTest` 와 동일 pinned 버전. */
            @JvmStatic
            val minio: MinIOContainer =
                MinIOContainer("minio/minio:RELEASE.2023-09-04T19-57-37Z")
                    .apply { start() }

            /** 이 테스트 전용 버킷 — 다른 Import 통합테스트의 버킷과 물리적으로 분리한다. */
            const val TEST_BUCKET = "bts-imports-mapping-flow-it"
        }
    }

    @Autowired
    @Suppress("VarCouldBeVal")
    private lateinit var importJobService: ImportJobService

    @Autowired
    @Suppress("VarCouldBeVal")
    private lateinit var importMappingService: ImportMappingService

    @Autowired
    @Suppress("VarCouldBeVal")
    private lateinit var importJobWorker: ImportJobWorker

    @Autowired
    @Suppress("VarCouldBeVal")
    private lateinit var importJobRepository: ImportJobRepository

    @Autowired
    @Suppress("VarCouldBeVal")
    private lateinit var importMappingRepository: ImportMappingRepository

    @Autowired
    @Suppress("VarCouldBeVal")
    private lateinit var dsl: DSLContext

    @Autowired
    @Suppress("VarCouldBeVal")
    private lateinit var permissionResolver: TestConfig.StubIssuePermissionResolver

    @Autowired
    @Suppress("VarCouldBeVal")
    private lateinit var issueImportPort: TestConfig.CapturingIssueImportPort

    private val actorId: UUID = UUID.fromString("aaaaaaaa-0000-0000-0000-0000000000b1")
    private val projectKey = "ATLAS"

    @BeforeEach
    fun setUp() {
        dsl.deleteFrom(IMPORT_JOBS).execute() // import_mappings 는 FK ON DELETE CASCADE(V606)로 동반 삭제된다.
        runCatching { dsl.execute("SELECT pgmq.purge_queue(?)", ImportJobWorker.QUEUE_NAME) }
        permissionResolver.allowed.clear()
        permissionResolver.allowed.add(actorId)
        issueImportPort.capturedCommands.clear()
    }

    // ── (i)~(iii) analyze → confirm → 워커 처리, 매핑대로 summary/assignee 반영 ──────

    @Test
    fun `임의 헤더 CSV는 analyze 로 매핑UI 진입 후 confirm 매핑대로 워커가 처리해 COMPLETED 되고 비고는 무시된다`() {
        val csv = "Título,담당,비고\n버그입니다,alice@corp.com,무시할값\n"

        val analysis = importJobService.analyze(analyzeCommand(csv))
        assertThat(analysis.job.status).isEqualTo(ImportJobStatus.AWAITING_MAPPING)
        assertThat(analysis.sourceFields).containsExactly("Título", "담당", "비고")
        val jobId = analysis.job.id

        val confirmed =
            importMappingService.confirm(
                jobId = jobId,
                actor = actorId,
                fieldMappings =
                    mapOf(
                        "Título" to TargetField.SUMMARY.key,
                        "담당" to TargetField.ASSIGNEE.key,
                        "비고" to TargetField.IGNORE_KEY,
                    ),
                dryRun = false,
            )
        assertThat(confirmed.status).isEqualTo(ImportJobStatus.PENDING)
        assertThat(importMappingRepository.findByJobId(jobId))
            .containsExactlyInAnyOrderEntriesOf(
                mapOf(
                    "Título" to TargetField.SUMMARY.key,
                    "담당" to TargetField.ASSIGNEE.key,
                    "비고" to TargetField.IGNORE_KEY,
                ),
            )

        importJobWorker.pollAndProcess()

        assertThat(importJobRepository.findStatus(jobId)).isEqualTo(ImportJobStatus.COMPLETED)
        assertThat(issueImportPort.capturedCommands).hasSize(1)
        val command = issueImportPort.capturedCommands.single()
        assertThat(command.summary).isEqualTo("버그입니다")
        assertThat(command.assigneeEmail).isEqualTo("alice@corp.com")
        assertThat(command.description).isNull() // "비고" 는 IGNORE 라 어떤 필드에도 반영되지 않는다.
    }

    // ── (iv) canonical 즉시경로(accept) 회귀 — 매핑 없이도 정상 처리 ───────────────

    @Test
    fun `canonical 헤더 CSV는 accept 즉시경로로 매핑 없이도 정상 COMPLETED 된다 (회귀)`() {
        val csv = "summary\n캐노니컬 이슈\n"

        val job = importJobService.accept(acceptCommand(csv))
        assertThat(job.status).isEqualTo(ImportJobStatus.PENDING)
        assertThat(importMappingRepository.findByJobId(job.id)).isEmpty()

        importJobWorker.pollAndProcess()

        assertThat(importJobRepository.findStatus(job.id)).isEqualTo(ImportJobStatus.COMPLETED)
        assertThat(issueImportPort.capturedCommands).hasSize(1)
        assertThat(issueImportPort.capturedCommands.single().summary).isEqualTo("캐노니컬 이슈")
    }

    // ── (v) dryRun 확정 — dry_run 컬럼 영속 + 워커가 확정값을 읽어 처리 ────────────

    @Test
    fun `confirm 을 dryRun true 로 호출하면 dry_run 이 영속되고 워커가 dryRun true 커맨드로 처리한다`() {
        val csv = "Título\n드라이런 이슈\n"

        val analysis = importJobService.analyze(analyzeCommand(csv))
        val jobId = analysis.job.id

        val confirmed =
            importMappingService.confirm(
                jobId = jobId,
                actor = actorId,
                fieldMappings = mapOf("Título" to TargetField.SUMMARY.key),
                dryRun = true,
            )
        assertThat(confirmed.dryRun).isTrue()
        assertThat(fetchDryRun(jobId)).isTrue()

        importJobWorker.pollAndProcess()

        assertThat(importJobRepository.findStatus(jobId)).isEqualTo(ImportJobStatus.COMPLETED)
        assertThat(issueImportPort.capturedCommands.single().dryRun).isTrue()
    }

    // ── private helpers ────────────────────────────────────────────────────────

    private fun fetchDryRun(jobId: ImportJobId): Boolean? =
        dsl.select(IMPORT_JOBS.DRY_RUN)
            .from(IMPORT_JOBS)
            .where(IMPORT_JOBS.ID.eq(jobId.value))
            .fetchOne(IMPORT_JOBS.DRY_RUN)

    private fun analyzeCommand(csv: String): ImportAnalyzeCommand {
        val bytes = csv.toByteArray(Charsets.UTF_8)
        return ImportAnalyzeCommand(
            projectKey = projectKey,
            format = "CSV",
            filename = "issues.csv",
            contentType = null,
            sizeBytes = bytes.size.toLong(),
            inputStream = ByteArrayInputStream(bytes),
            requesterUserId = actorId,
        )
    }

    private fun acceptCommand(csv: String): ImportAcceptCommand {
        val bytes = csv.toByteArray(Charsets.UTF_8)
        return ImportAcceptCommand(
            projectKey = projectKey,
            format = "CSV",
            dryRun = false,
            filename = "issues.csv",
            contentType = null,
            sizeBytes = bytes.size.toLong(),
            inputStream = ByteArrayInputStream(bytes),
            requesterUserId = actorId,
        )
    }
}
