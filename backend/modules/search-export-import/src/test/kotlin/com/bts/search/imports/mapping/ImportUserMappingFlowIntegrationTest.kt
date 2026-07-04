// FR-IM-02 PR-B 사용자 매핑 흐름 전체 통합테스트 — analyze→collectUsers→confirm(userMappings)→워커 처리까지 실 PostgreSQL+MinIO 관통 (Task 9)

package com.bts.search.imports.mapping

import com.bts.search.imports.job.application.ImportAnalyzeCommand
import com.bts.search.imports.job.application.ImportErrorLogWriter
import com.bts.search.imports.job.application.ImportJobProcessor
import com.bts.search.imports.job.application.ImportJobService
import com.bts.search.imports.job.domain.ImportJobStatus
import com.bts.search.imports.job.event.ImportJobEnqueuePublisher
import com.bts.search.imports.job.repository.ImportJobRepository
import com.bts.search.imports.job.storage.MinioImportStorageAdapter
import com.bts.search.imports.job.storage.MinioImportStorageConfig
import com.bts.search.imports.job.worker.ImportJobWorker
import com.bts.search.imports.mapping.repository.ImportMappingRepository
import com.bts.search.imports.mapping.repository.ImportUserMappingRepository
import com.bts.search.imports.mapping.repository.ImportValueMappingRepository
import com.bts.search.jooq.tables.references.IMPORT_JOBS
import com.bts.shared.issue.ImportAttachmentSource
import com.bts.shared.issue.IssueImportCommand
import com.bts.shared.issue.IssueImportPort
import com.bts.shared.issue.IssueImportResult
import com.bts.shared.issue.IssueTypeCatalog
import com.bts.shared.issue.IssueTypeKey
import com.bts.shared.issue.IssueTypeRef
import com.bts.shared.permission.IssuePermission
import com.bts.shared.permission.IssuePermissionResolver
import com.bts.shared.permission.IssueScope
import com.bts.shared.user.UserLookupPort
import com.bts.shared.workflow.ProjectKey
import com.bts.shared.workflow.WorkflowStateCatalog
import com.bts.shared.workflow.WorkflowStateView
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
 * FR-IM-02 PR-B 사용자 매핑 흐름(`analyze → collectUsers → confirm(userMappings) → 워커 처리`) 풀스택
 * 통합테스트.
 *
 * [ImportJobService.analyze] → [ImportMappingService.collectUsers] → [ImportMappingService.confirm] →
 * [ImportJobWorker.pollAndProcess](내부에서 [ImportJobProcessor.process] 호출)을 **실 PostgreSQL
 * (pgmq, Testcontainers)** + **실 MinIO(Testcontainers)** 위에서 관통시켜, CSV 원본에 등장하는
 * 보고자/담당자/댓글 작성자 이메일이 사용자가 확정한 사용자 매핑대로 [IssueImportCommand] 의
 * `reporterUserId`/`assigneeUserId`(+ 동반 VO [com.bts.shared.issue.ImportComment.authorUserId])로
 * 세팅되는지 검증한다.
 *
 * `IssueImportPort`(shared-kernel cross-BC 쓰기 포트)는 [ImportMappingFlowIntegrationTest] 와 동일하게
 * [TestConfig.CapturingIssueImportPort] 로 대체한다(test-assembled 표준, no-cross-bc-deployment-assembly).
 * `UserLookupPort` 도 동일 이유로 [TestConfig.SeededUserLookupPort] 로 대체하되, 이 테스트는 이메일→추천
 * userId(`resolveByEmails`)와 userId→표시명(`findDisplayNamesByIds`) 응답을 테스트가 직접 시딩할 수
 * 있는 가변 맵으로 구현한다 — [ImportMappingService.collectUsers] 의 추천 계산과 `confirm` 의 대상
 * 사용자 실재 검증이 모두 이 포트를 거치기 때문이다.
 *
 * ## 검증 (plan Task 9 RED 시나리오)
 * - (i) 임의 헤더 CSV(`Título`/`보고자`/`담당`/`Comment`/`비고`)를 [ImportJobService.analyze] 로
 *   접수하면 status=AWAITING_MAPPING, sourceFields 에 다섯 헤더가 감지된다.
 * - (ii) [ImportMappingService.collectUsers] 를 필드 매핑(`Título→summary, 보고자→reporter,
 *   담당→assignee, 비고→IGNORE`)으로 호출하면, 원본을 전량 스캔해 보고자/담당자/댓글 작성자 이메일
 *   (`bob@corp.com`/`alice@corp.com`/`carol@corp.com`) 세 건을 distinct·정렬된 목록으로 반환하고,
 *   [TestConfig.SeededUserLookupPort] 에 미리 시딩해 둔 `alice@corp.com` 만 추천 userId/표시명이 채워
 *   진다(bob/carol 은 추천 없음 — 매핑 UI 가 수동 선택을 요구하는 케이스).
 * - (iii) [ImportMappingService.confirm] 으로 위 필드 매핑 + 사용자 매핑(`bob@corp.com→bobUserId,
 *   alice@corp.com→aliceUserId, carol@corp.com→carolUserId`, 셋 다 실재 사용자로 시딩됨)을 확정하면
 *   [ImportUserMappingRepository] 에 세 매핑이 저장되고 status=PENDING 으로 전이한다.
 * - (iv) [ImportJobWorker.pollAndProcess] 로 워커를 직접 호출하면 COMPLETED 로 전환되고,
 *   [TestConfig.CapturingIssueImportPort] 가 캡처한 커맨드의 `reporterUserId`=bobUserId,
 *   `assigneeUserId`=aliceUserId, 댓글 VO 의 `authorUserId`=carolUserId 로 세팅된다 — 이메일 필드
 *   (`reporterEmail`/`assigneeEmail`/댓글 `authorEmail`)는 그대로 보존된다(어댑터 폴백 경로 불변).
 *   `비고`(IGNORE) 컬럼 값은 어떤 필드에도 반영되지 않는다.
 *
 * ## 회귀 — 사용자 매핑 없이 confirm 하면 커맨드 userId 필드가 전부 null
 *
 * [ImportMappingService.confirm] 을 `userMappings` 생략(기본값 빈 목록)으로 호출한 job 은
 * [ImportUserMappingRepository] 에 매핑이 저장되지 않고, 워커가 처리한 커맨드의 `reporterUserId`/
 * `assigneeUserId` 가 전부 null 이어야 한다 — 이메일 필드는 여전히 채워져(기존 어댑터 이메일 폴백
 * 경로가 살아있는지 확인) `userMappings` 기본값 도입이 기존 호출부(사용자 매핑 없이 confirm 하던
 * PR-A 호출부)를 깨지 않았는지 검증한다.
 *
 * ## 트랜잭션 배선
 *
 * [ImportMappingFlowIntegrationTest] 와 동일 패턴 — `dataSource`/`transactionManager`/`dslContext`
 * 세 빈이 동일한 [DriverManagerDataSource] 인스턴스를 공유해야 [ImportJobEnqueuePublisher.enqueue]
 * ([org.springframework.transaction.annotation.Propagation.MANDATORY])가 [TransactionTemplate] 이
 * 연 트랜잭션 안에서 정상 동작한다.
 */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [ImportUserMappingFlowIntegrationTest.TestConfig::class])
class ImportUserMappingFlowIntegrationTest {
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
        open fun importUserMappingRepository(dsl: DSLContext): ImportUserMappingRepository {
            return ImportUserMappingRepository(dsl)
        }

        @Bean
        open fun importValueMappingRepository(dsl: DSLContext): ImportValueMappingRepository {
            return ImportValueMappingRepository(dsl)
        }

        @Bean
        open fun userLookupPort(): SeededUserLookupPort = SeededUserLookupPort()

        @Bean
        open fun issueTypeCatalog(): FixedIssueTypeCatalog = FixedIssueTypeCatalog()

        @Bean
        open fun workflowStateCatalog(): FixedWorkflowStateCatalog = FixedWorkflowStateCatalog()

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
        @Suppress("LongParameterList") // 테스트 빈 조립 — ImportMappingService 생성자 협력자를 그대로 나열
        open fun importMappingService(
            importMappingRepository: ImportMappingRepository,
            importJobRepository: ImportJobRepository,
            enqueuePublisher: ImportJobEnqueuePublisher,
            storage: MinioImportStorageAdapter,
            transactionTemplate: TransactionTemplate,
            userLookupPort: SeededUserLookupPort,
            importUserMappingRepository: ImportUserMappingRepository,
            issueTypeCatalog: FixedIssueTypeCatalog,
            workflowStateCatalog: FixedWorkflowStateCatalog,
            importValueMappingRepository: ImportValueMappingRepository,
        ): ImportMappingService {
            return ImportMappingService(
                importMappingRepository,
                importJobRepository,
                enqueuePublisher,
                storage,
                transactionTemplate,
                userLookupPort,
                importUserMappingRepository,
                issueTypeCatalog,
                workflowStateCatalog,
                importValueMappingRepository,
            )
        }

        @Bean
        @Suppress("LongParameterList") // 테스트 빈 조립 — ImportJobProcessor 생성자 협력자를 그대로 나열
        open fun importJobProcessor(
            issueImportPort: CapturingIssueImportPort,
            storage: MinioImportStorageAdapter,
            importJobRepository: ImportJobRepository,
            importMappingRepository: ImportMappingRepository,
            importUserMappingRepository: ImportUserMappingRepository,
            importValueMappingRepository: ImportValueMappingRepository,
            errorLogWriter: ImportErrorLogWriter,
        ): ImportJobProcessor {
            return ImportJobProcessor(
                issueImportPort,
                storage,
                importJobRepository,
                importMappingRepository,
                importUserMappingRepository,
                importValueMappingRepository,
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
         * [IssueImportPort] stub — 사용자 매핑대로 커맨드가 조립됐는지 검증하는 이 테스트의 핵심 장치.
         *
         * [ImportMappingFlowIntegrationTest.TestConfig.CapturingIssueImportPort] 와 동일 패턴 —
         * [ImportAttachmentSource] 를 받는 2-arg 오버로드만 override 한다(1-arg default 가
         * `importIssue(cmd, null)` 로 위임하므로, 첨부 미포함인 이 테스트의 행 처리는 항상 이 메서드로
         * 귀결된다).
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

        /**
         * [UserLookupPort] cross-BC 포트의 test-assembled 시딩 가능 fake(no-cross-bc-deployment-assembly).
         *
         * [suggestionsByEmail] 은 [ImportMappingService.collectUsers] 가 `resolveByEmails` 로 조회하는
         * "이메일→추천 BTS 사용자 UUID" 응답을, [displayNamesById] 는 `collectUsers` 의 추천 표시명과
         * `confirm` 의 대상 사용자 실재 검증([ImportMappingService] KDoc §사용자 매핑 검증 참조)이 공용으로
         * 쓰는 "UUID→표시명" 응답을 시뮬레이션한다. 테스트가 각 시나리오에 필요한 항목만 채워 넣는다 —
         * 예를 들어 `alice@corp.com` 만 [suggestionsByEmail] 에 있으면(추천 매칭), bob/carol 은
         * [displayNamesById] 에는 있지만(confirm 의 대상 사용자 실재 검증 통과용) 추천 매칭은 없는
         * 상태를 표현할 수 있다.
         */
        class SeededUserLookupPort : UserLookupPort {
            val suggestionsByEmail: MutableMap<String, UUID> = ConcurrentHashMap()
            val displayNamesById: MutableMap<UUID, String> = ConcurrentHashMap()

            override fun exists(userId: UUID): Boolean = displayNamesById.containsKey(userId)

            override fun resolveByEmails(emails: Set<String>): Map<String, UUID> =
                emails.mapNotNull { email -> suggestionsByEmail[email]?.let { email to it } }.toMap()

            override fun findDisplayNamesByIds(ids: Set<UUID>): Map<UUID, String> =
                ids.mapNotNull { id -> displayNamesById[id]?.let { id to it } }.toMap()
        }

        /**
         * 고정 빈 목록만 반환하는 테스트 전용 [IssueTypeCatalog] (FR-IM-02 PR-C) — 이 통합테스트는
         * `confirm` 을 `valueMappings` 생략(기본값 빈 목록)으로만 호출하므로
         * [ImportMappingService.confirm]/[ImportMappingService.collectValues] 가 이 포트를 실제로
         * 호출하지 않는다. 배선 컴파일만 목적이다.
         *
         * `open` 필수 — [IssueTypeCatalog.listTypes] 의 인터페이스 레벨 `@Transactional` 을 [TestConfig]
         * 의 `@EnableTransactionManagement(proxyTargetClass = true)` 가 CGLIB 서브클래싱으로 감싸려
         * 시도하는데, Kotlin 클래스는 기본 final 이라 `open` 없이는 Enhancer 가 실패한다
         * (`IssueImportAdapterTest.FixedStatesWorkflowStateCatalog` 와 동일 근거).
         */
        open class FixedIssueTypeCatalog : IssueTypeCatalog {
            override fun listTypes(): List<IssueTypeRef> = emptyList()
        }

        /** [FixedIssueTypeCatalog] 와 동일 근거 — 고정 빈 목록만 반환하는 테스트 전용 [WorkflowStateCatalog]. */
        open class FixedWorkflowStateCatalog : WorkflowStateCatalog {
            override fun listStates(
                projectKey: ProjectKey,
                issueTypeKey: IssueTypeKey?,
            ): List<WorkflowStateView> = emptyList()
        }

        companion object {
            /**
             * JVM 단위 singleton PostgreSQL container.
             * quay.io/tembo/pg16-pgmq:latest — V604(pgmq 확장) 때문에 postgres:16-alpine 으로는 실패한다
             * (ADR 2026-05-22-pgmq-postgres-image, `ImportMappingFlowIntegrationTest` 동일 패턴).
             */
            @JvmStatic
            val pg: PostgreSQLContainer<*> =
                PostgreSQLContainer(
                    DockerImageName
                        .parse("quay.io/tembo/pg16-pgmq:latest")
                        .asCompatibleSubstituteFor("postgres"),
                ).withDatabaseName("bts_import_user_mapping_flow_it")
                    .withUsername("bts")
                    .withPassword("bts_test")
                    .apply { start() }

            /** JVM 단위 singleton MinIO container — 다른 Import 통합테스트와 동일 pinned 버전. */
            @JvmStatic
            val minio: MinIOContainer =
                MinIOContainer("minio/minio:RELEASE.2023-09-04T19-57-37Z")
                    .apply { start() }

            /** 이 테스트 전용 버킷 — 다른 Import 통합테스트의 버킷과 물리적으로 분리한다. */
            const val TEST_BUCKET = "bts-imports-user-mapping-flow-it"
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
    private lateinit var importUserMappingRepository: ImportUserMappingRepository

    @Autowired
    @Suppress("VarCouldBeVal")
    private lateinit var dsl: DSLContext

    @Autowired
    @Suppress("VarCouldBeVal")
    private lateinit var permissionResolver: TestConfig.StubIssuePermissionResolver

    @Autowired
    @Suppress("VarCouldBeVal")
    private lateinit var issueImportPort: TestConfig.CapturingIssueImportPort

    @Autowired
    @Suppress("VarCouldBeVal")
    private lateinit var userLookupPort: TestConfig.SeededUserLookupPort

    private val actorId: UUID = UUID.fromString("aaaaaaaa-0000-0000-0000-0000000000b1")
    private val projectKey = "ATLAS"

    private val bobUserId: UUID = UUID.randomUUID()
    private val aliceUserId: UUID = UUID.randomUUID()
    private val carolUserId: UUID = UUID.randomUUID()

    @BeforeEach
    fun setUp() {
        dsl.deleteFrom(IMPORT_JOBS).execute() // import_user_mappings 는 FK ON DELETE CASCADE(V607)로 동반 삭제된다.
        runCatching { dsl.execute("SELECT pgmq.purge_queue(?)", ImportJobWorker.QUEUE_NAME) }
        permissionResolver.allowed.clear()
        permissionResolver.allowed.add(actorId)
        issueImportPort.capturedCommands.clear()
        userLookupPort.suggestionsByEmail.clear()
        userLookupPort.displayNamesById.clear()
    }

    // ── (i)~(iv) analyze → collectUsers → confirm(userMappings) → 워커 처리 ─────

    @Test
    fun `보고자 담당자 댓글작성자 이메일이 collectUsers로 수집되고 confirm 사용자매핑대로 워커가 커맨드에 userId를 세팅한다`() {
        val csv =
            "Título,보고자,담당,Comment,비고\n" +
                "버그입니다,bob@corp.com,alice@corp.com,2024-01-01;carol@corp.com;코멘트내용,무시할값\n"

        val analysis = importJobService.analyze(analyzeCommand(csv))
        assertThat(analysis.job.status).isEqualTo(ImportJobStatus.AWAITING_MAPPING)
        assertThat(analysis.sourceFields).containsExactly("Título", "보고자", "담당", "Comment", "비고")
        val jobId = analysis.job.id

        val fieldMappings =
            mapOf(
                "Título" to TargetField.SUMMARY.key,
                "보고자" to TargetField.REPORTER.key,
                "담당" to TargetField.ASSIGNEE.key,
                "비고" to TargetField.IGNORE_KEY,
            )

        // alice@corp.com 만 기존 BTS 사용자 추천이 있고, bob/carol 은 수동 선택이 필요한 케이스.
        userLookupPort.suggestionsByEmail["alice@corp.com"] = aliceUserId
        userLookupPort.displayNamesById[aliceUserId] = "Alice"
        // confirm 시 대상 사용자 실재 검증에 필요 — bob/carol 은 추천은 없지만 실재 사용자다.
        userLookupPort.displayNamesById[bobUserId] = "Bob"
        userLookupPort.displayNamesById[carolUserId] = "Carol"

        val collected = importMappingService.collectUsers(jobId, actorId, fieldMappings)
        assertThat(collected.users)
            .containsExactly(
                UserCollectionEntry("alice@corp.com", aliceUserId, "Alice"),
                UserCollectionEntry("bob@corp.com", null, null),
                UserCollectionEntry("carol@corp.com", null, null),
            )

        val confirmed =
            importMappingService.confirm(
                jobId = jobId,
                actor = actorId,
                fieldMappings = fieldMappings,
                dryRun = false,
                userMappings =
                    listOf(
                        "bob@corp.com" to bobUserId,
                        "alice@corp.com" to aliceUserId,
                        "carol@corp.com" to carolUserId,
                    ),
            )
        assertThat(confirmed.status).isEqualTo(ImportJobStatus.PENDING)
        assertThat(importUserMappingRepository.findByJobId(jobId))
            .containsExactlyInAnyOrderEntriesOf(
                mapOf(
                    "bob@corp.com" to bobUserId,
                    "alice@corp.com" to aliceUserId,
                    "carol@corp.com" to carolUserId,
                ),
            )

        importJobWorker.pollAndProcess()

        assertThat(importJobRepository.findStatus(jobId)).isEqualTo(ImportJobStatus.COMPLETED)
        assertThat(issueImportPort.capturedCommands).hasSize(1)
        assertCommandReflectsUserMappings(issueImportPort.capturedCommands.single())
    }

    /**
     * 워커가 처리한 캡처 커맨드가 사용자 매핑([bobUserId]/[aliceUserId]/[carolUserId])대로 세팅됐는지
     * 단언하는 헬퍼 — 위 테스트 메서드의 LongMethod(detekt) 방지 목적으로 분리했다.
     */
    private fun assertCommandReflectsUserMappings(command: IssueImportCommand) {
        assertThat(command.reporterEmail).isEqualTo("bob@corp.com")
        assertThat(command.assigneeEmail).isEqualTo("alice@corp.com")
        assertThat(command.reporterUserId).isEqualTo(bobUserId)
        assertThat(command.assigneeUserId).isEqualTo(aliceUserId)
        assertThat(command.description).isNull() // "비고" 는 IGNORE 라 어떤 필드에도 반영되지 않는다.

        assertThat(command.comments).hasSize(1)
        val comment = command.comments.single()
        assertThat(comment.body).isEqualTo("코멘트내용")
        assertThat(comment.authorEmail).isEqualTo("carol@corp.com")
        assertThat(comment.authorUserId).isEqualTo(carolUserId)
    }

    // ── 회귀 — 사용자매핑 없이 confirm 하면 커맨드 userId 필드가 전부 null ────────

    @Test
    fun `사용자 매핑 없이 confirm 하면 커맨드 userId 필드가 전부 null 이다 (기존 이메일 폴백 동작 불변, 회귀)`() {
        val csv = "Título,보고자,담당\n다른버그입니다,dave@corp.com,eve@corp.com\n"

        val analysis = importJobService.analyze(analyzeCommand(csv))
        val jobId = analysis.job.id

        val fieldMappings =
            mapOf(
                "Título" to TargetField.SUMMARY.key,
                "보고자" to TargetField.REPORTER.key,
                "담당" to TargetField.ASSIGNEE.key,
            )

        // userMappings 인자를 생략 — PR-A 시절부터 있던 기존 confirm 호출부와 동일한 형태.
        importMappingService.confirm(jobId = jobId, actor = actorId, fieldMappings = fieldMappings, dryRun = false)
        assertThat(importUserMappingRepository.findByJobId(jobId)).isEmpty()

        importJobWorker.pollAndProcess()

        assertThat(importJobRepository.findStatus(jobId)).isEqualTo(ImportJobStatus.COMPLETED)
        assertThat(issueImportPort.capturedCommands).hasSize(1)
        val command = issueImportPort.capturedCommands.single()
        assertThat(command.reporterEmail).isEqualTo("dave@corp.com")
        assertThat(command.assigneeEmail).isEqualTo("eve@corp.com")
        assertThat(command.reporterUserId).isNull()
        assertThat(command.assigneeUserId).isNull()
    }

    // ── private helpers ────────────────────────────────────────────────────────

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
}
