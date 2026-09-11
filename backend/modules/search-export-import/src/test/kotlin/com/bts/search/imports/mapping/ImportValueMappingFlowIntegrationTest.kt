// FR-IM-02 PR-C 값 매핑 흐름 전체 통합테스트 — analyze→collectValues→confirm(valueMappings)→워커 처리까지 실 PostgreSQL+MinIO 관통 (Task 8)

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
 * FR-IM-02 PR-C 값 매핑 흐름(`analyze → collectValues → confirm(valueMappings) → 워커 처리`) 풀스택
 * 통합테스트.
 *
 * [ImportJobService.analyze] → [ImportMappingService.collectValues] → [ImportMappingService.confirm] →
 * [ImportJobWorker.pollAndProcess](내부에서 [ImportJobProcessor.process] 호출)을 **실 PostgreSQL
 * (pgmq, Testcontainers)** + **실 MinIO(Testcontainers)** 위에서 관통시켜, CSV 원본에 등장하는
 * 상태/유형/우선순위 이름이 사용자가 확정한 값 매핑대로 [IssueImportCommand] 의
 * `statusName`/`typeName`/`priority`로 세팅되는지 검증한다.
 *
 * `IssueImportPort`(shared-kernel cross-BC 쓰기 포트)는 [ImportUserMappingFlowIntegrationTest] 와
 * 동일하게 [TestConfig.CapturingIssueImportPort] 로 대체한다(test-assembled 표준,
 * no-cross-bc-deployment-assembly). `IssueTypeCatalog`/`WorkflowStateCatalog` 도 동일 이유로
 * [TestConfig.SeededIssueTypeCatalog]/[TestConfig.SeededWorkflowStateCatalog] 로 대체하되, 이
 * 테스트는 [ImportMappingService.collectValues] 의 자동추천 계산이 실제로 매치되도록 생성자로 받은
 * 고정 목록을 그대로 반환한다(`issue-tracking` `IssueImportAdapterTest.FixedStatesWorkflowStateCatalog`
 * 와 동일한 생성자-주입 fixture 패턴) — `PR-A`/`PR-B` 흐름 테스트의 `FixedIssueTypeCatalog`/
 * `FixedWorkflowStateCatalog`(항상 빈 목록)와 달리, 이 값 매핑 흐름 테스트에서는 추천 계산 자체가
 * 검증 대상이라 실제 후보 목록을 반환해야 한다. **인스턴스 필드를 테스트가 mutate 하는 방식은 쓰지
 * 않는다** — `@EnableTransactionManagement(proxyTargetClass = true)` 가 만드는 CGLIB 프록시는
 * `@Transactional` 인터페이스 메서드([IssueTypeCatalog.listTypes]/[WorkflowStateCatalog.listStates])
 * 만 오버라이드하고, Kotlin 프로퍼티 getter 는 기본 `final` 이라 오버라이드 대상이 아닌 별도 mutable
 * 프로퍼티에 접근하면 Objenesis 가 생성자를 건너뛰고 만든 프록시 자체 인스턴스의(실제 target 이
 * 아닌) 미초기화 필드가 조회되어 NPE 가 난다 — 생성자 인자로 고정하면 이 문제 자체가 성립하지 않는다.
 *
 * ## 검증 (plan Task 8 acceptance 시나리오)
 *
 * - (a) 전체 flow. 임의 헤더 CSV(`Título`/`Estado`/`Tipo`/`Prioridad`)를 [ImportJobService.analyze]
 *   로 접수하고 필드 매핑(`summary`/`status`/`type`/`priority`)을 확정한 뒤
 *   [ImportMappingService.collectValues] 를 호출하면, 소스 상태/유형/우선순위 이름(`"In Progress"`/
 *   `"Task"`/`"High"`)이 각각 정규화되어 수집되고, [TestConfig.SeededWorkflowStateCatalog]/
 *   [TestConfig.SeededIssueTypeCatalog]/`ImportRowParser.canonicalPriorityNames` 와 정규화 정확일치하는
 *   자동추천(`"In Progress"`/`"Task"`/`"High"`)이 채워진다. [ImportMappingService.confirm] 을
 *   `valueMappings`(상태→`"진행 중"`, 유형→`"작업"`, 우선순위→`"Highest"` — 자동추천과 다른 값으로
 *   사용자가 명시 override)으로 확정하면 [ImportValueMappingRepository] 에 세 매핑이 저장되고
 *   status=PENDING 으로 전환한다. [ImportJobWorker.pollAndProcess] 로 워커를 직접 호출하면 COMPLETED
 *   로 전환되고, [TestConfig.CapturingIssueImportPort] 가 캡처한 커맨드의 `typeName`=`"작업"`,
 *   `statusName`=`"진행 중"`, `priority`=1(canonical `"Highest"` 의 숫자 값)로 세팅된다.
 * - (b) 회귀. 값매핑 미제공(`confirm` 에 `valueMappings` 인자 생략, 기본값 빈 목록)으로 확정한 job 은
 *   [ImportValueMappingRepository] 에 매핑이 저장되지 않고, 워커가 처리한 커맨드의 `typeName`/
 *   `statusName`/`priority` 가 원본 소스 값 그대로(`"Task"`/`"In Progress"`/2 — canonical `"High"` 의
 *   숫자 값) 유지된다 — `valueMappings` 기본값 도입이 기존 호출부(값 매핑 없이 confirm 하던 PR-A/PR-B
 *   호출부)를 깨지 않았는지 검증한다.
 *
 * ## 트랜잭션 배선
 *
 * [ImportMappingFlowIntegrationTest]/[ImportUserMappingFlowIntegrationTest] 와 동일 패턴 —
 * `dataSource`/`transactionManager`/`dslContext` 세 빈이 동일한 [DriverManagerDataSource] 인스턴스를
 * 공유해야 [ImportJobEnqueuePublisher.enqueue]([org.springframework.transaction.annotation.Propagation.MANDATORY])
 * 가 [TransactionTemplate] 이 연 트랜잭션 안에서 정상 동작한다.
 */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [ImportValueMappingFlowIntegrationTest.TestConfig::class])
class ImportValueMappingFlowIntegrationTest {
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

        /**
         * [UserLookupPort] cross-BC 포트의 test-assembled 최소 stub (no-cross-bc-deployment-assembly) —
         * 이 값 매핑 흐름 테스트는 `confirm` 을 `userMappings` 생략(기본값 빈 목록)으로만 호출하므로
         * [ImportMappingService.confirm] 이 [UserLookupPort] 를 실제로 호출하지 않는다. 배선 컴파일만
         * 목적이라 `exists` 외 override 가 필요 없다([ImportMappingFlowIntegrationTest] 와 동일 패턴).
         */
        @Bean
        open fun userLookupPort(): UserLookupPort =
            object : UserLookupPort {
                override fun exists(userId: UUID): Boolean = false
            }

        /**
         * TYPE 자동추천이 소스 "Task" 와 정규화 정확일치하도록 고정 목록을 생성자로 주입한다. "작업" 은
         * confirm 이 override 로 선택할 대상 값(자동추천과 다른 값)이다.
         */
        @Bean
        open fun issueTypeCatalog(): SeededIssueTypeCatalog =
            SeededIssueTypeCatalog(
                listOf(
                    IssueTypeRef(key = "task", name = "Task"),
                    IssueTypeRef(key = "translated-task", name = "작업"),
                ),
            )

        /** STATUS 자동추천이 소스 "In Progress" 와 정규화 정확일치하도록 고정 목록을 생성자로 주입한다. */
        @Bean
        open fun workflowStateCatalog(): SeededWorkflowStateCatalog =
            SeededWorkflowStateCatalog(
                listOf(
                    WorkflowStateView(
                        key = "in-progress",
                        name = "In Progress",
                        isDone = false,
                        category = "IN_PROGRESS",
                        displayOrder = 1,
                    ),
                ),
            )

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
            userLookupPort: UserLookupPort,
            importUserMappingRepository: ImportUserMappingRepository,
            issueTypeCatalog: SeededIssueTypeCatalog,
            workflowStateCatalog: SeededWorkflowStateCatalog,
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
         * [IssueImportPort] stub — 값 매핑대로 커맨드가 조립됐는지 검증하는 이 테스트의 핵심 장치.
         *
         * [ImportUserMappingFlowIntegrationTest.TestConfig.CapturingIssueImportPort] 와 동일 패턴 —
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
         * 생성자로 받은 고정 목록만 반환해 [ImportMappingService.collectValues] 의 TYPE 자동추천이
         * 실제로 매치되도록 하는 [IssueTypeCatalog] fake(FR-IM-02 PR-C).
         *
         * `issue-tracking` `IssueImportAdapterTest.FixedStatesWorkflowStateCatalog` 와 동일한 생성자
         * 주입 fixture 패턴이다 — 테스트 인스턴스 필드를 나중에 mutate 하는 방식(예: `MutableList` 프로퍼티에
         * 직접 채워 넣기)은 쓰지 않는다. `@EnableTransactionManagement(proxyTargetClass = true)` 가 만드는
         * CGLIB 프록시는 [IssueTypeCatalog.listTypes] 처럼 `@Transactional` 인 인터페이스 오버라이드
         * 메서드만 오버라이드할 수 있고, Kotlin 프로퍼티 getter 는 기본 `final` 이라 그런 별도 mutable
         * 프로퍼티에 접근하면 Objenesis 가 생성자를 건너뛰고 만든 프록시 인스턴스 자체의(실제 target 이
         * 아닌) 미초기화 필드가 조회되어 NPE 가 난다 — 생성자 인자로 고정해 이 문제를 원천 차단한다.
         *
         * `open` 필수 — [IssueTypeCatalog.listTypes] 의 인터페이스 레벨 `@Transactional` 을 [TestConfig]
         * 의 `@EnableTransactionManagement(proxyTargetClass = true)` 가 CGLIB 서브클래싱으로 감싸려
         * 시도하는데, Kotlin 클래스는 기본 final 이라 `open` 없이는 Enhancer 가 실패한다
         * ([ImportMappingFlowIntegrationTest.TestConfig.FixedIssueTypeCatalog] 와 동일 근거).
         */
        open class SeededIssueTypeCatalog(
            private val types: List<IssueTypeRef>,
        ) : IssueTypeCatalog {
            override fun listTypes(): List<IssueTypeRef> = types
        }

        /** [SeededIssueTypeCatalog] 와 동일 근거 — collectValues STATUS 자동추천용 고정 목록 [WorkflowStateCatalog] fake. */
        open class SeededWorkflowStateCatalog(
            private val states: List<WorkflowStateView>,
        ) : WorkflowStateCatalog {
            override fun listStates(
                projectKey: ProjectKey,
                issueTypeKey: IssueTypeKey?,
            ): List<WorkflowStateView> = states
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
                ).withDatabaseName("bts_import_value_mapping_flow_it")
                    .withUsername("bts")
                    .withPassword("bts_test")
                    .apply { start() }

            /** JVM 단위 singleton MinIO container — 다른 Import 통합테스트와 동일 pinned 버전. */
            @JvmStatic
            val minio: MinIOContainer =
                MinIOContainer(
                    DockerImageName.parse("quay.io/minio/minio:RELEASE.2023-09-04T19-57-37Z")
                        .asCompatibleSubstituteFor("minio/minio"),
                )
                    .apply { start() }

            /** 이 테스트 전용 버킷 — 다른 Import 통합테스트의 버킷과 물리적으로 분리한다. */
            const val TEST_BUCKET = "bts-imports-value-mapping-flow-it"
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
    private lateinit var importValueMappingRepository: ImportValueMappingRepository

    @Autowired
    @Suppress("VarCouldBeVal")
    private lateinit var dsl: DSLContext

    @Autowired
    @Suppress("VarCouldBeVal")
    private lateinit var permissionResolver: TestConfig.StubIssuePermissionResolver

    @Autowired
    @Suppress("VarCouldBeVal")
    private lateinit var issueImportPort: TestConfig.CapturingIssueImportPort

    private val actorId: UUID = UUID.fromString("aaaaaaaa-0000-0000-0000-0000000000c1")
    private val projectKey = "ATLAS"

    @BeforeEach
    fun setUp() {
        dsl.deleteFrom(IMPORT_JOBS).execute() // import_value_mappings 는 FK ON DELETE CASCADE(V608)로 동반 삭제된다.
        runCatching { dsl.execute("SELECT pgmq.purge_queue(?)", ImportJobWorker.QUEUE_NAME) }
        permissionResolver.allowed.clear()
        permissionResolver.allowed.add(actorId)
        issueImportPort.capturedCommands.clear()
    }

    // ── (a) 전체 flow — collectValues 추천 + confirm(valueMappings) 로 워커 커맨드 치환 ─

    @Test
    fun `collectValues로 수집된 상태 유형 우선순위가 confirm 값매핑대로 워커 커맨드에 치환된다`() {
        val csv =
            "Título,Estado,Tipo,Prioridad\n" +
                "버그입니다,In Progress,Task,High\n"

        val analysis = importJobService.analyze(analyzeCommand(csv))
        assertThat(analysis.job.status).isEqualTo(ImportJobStatus.AWAITING_MAPPING)
        assertThat(analysis.sourceFields).containsExactly("Título", "Estado", "Tipo", "Prioridad")
        val jobId = analysis.job.id

        val fieldMappings =
            mapOf(
                "Título" to TargetField.SUMMARY.key,
                "Estado" to TargetField.STATUS.key,
                "Tipo" to TargetField.TYPE.key,
                "Prioridad" to TargetField.PRIORITY.key,
            )

        val collected = importMappingService.collectValues(jobId, actorId, fieldMappings)
        assertThat(collected.values.getValue(ValueTargetField.STATUS))
            .containsExactly(ValueCollectionEntry(sourceValue = "in progress", suggestedTargetValue = "In Progress"))
        assertThat(collected.values.getValue(ValueTargetField.TYPE))
            .containsExactly(ValueCollectionEntry(sourceValue = "task", suggestedTargetValue = "Task"))
        assertThat(collected.values.getValue(ValueTargetField.PRIORITY))
            .containsExactly(ValueCollectionEntry(sourceValue = "high", suggestedTargetValue = "High"))

        // 사용자가 자동추천과 다른 대상 값으로 명시 override 한다 — 단순 추천 echo 가 아님을 증명.
        val confirmed =
            importMappingService.confirm(
                jobId = jobId,
                actor = actorId,
                fieldMappings = fieldMappings,
                dryRun = false,
                valueMappings =
                    listOf(
                        Triple(ValueTargetField.STATUS, "In Progress", "진행 중"),
                        Triple(ValueTargetField.TYPE, "Task", "작업"),
                        Triple(ValueTargetField.PRIORITY, "High", "Highest"),
                    ),
            )
        assertThat(confirmed.status).isEqualTo(ImportJobStatus.PENDING)
        assertThat(importValueMappingRepository.findByJobId(jobId))
            .containsExactlyInAnyOrderEntriesOf(
                mapOf(
                    (ValueTargetField.STATUS to "in progress") to "진행 중",
                    (ValueTargetField.TYPE to "task") to "작업",
                    (ValueTargetField.PRIORITY to "high") to "Highest",
                ),
            )

        importJobWorker.pollAndProcess()

        assertThat(importJobRepository.findStatus(jobId)).isEqualTo(ImportJobStatus.COMPLETED)
        assertThat(issueImportPort.capturedCommands).hasSize(1)
        assertCommandReflectsValueMappings(issueImportPort.capturedCommands.single())
    }

    /**
     * 워커가 처리한 캡처 커맨드가 값 매핑(상태→"진행 중", 유형→"작업", 우선순위→"Highest")대로
     * 세팅됐는지 단언하는 헬퍼 — 위 테스트 메서드의 LongMethod(detekt) 방지 목적으로 분리했다.
     */
    private fun assertCommandReflectsValueMappings(command: IssueImportCommand) {
        assertThat(command.typeName).isEqualTo("작업")
        assertThat(command.statusName).isEqualTo("진행 중")
        assertThat(command.priority).isEqualTo(1) // canonical "Highest" 의 숫자 값(1)
    }

    // ── (b) 회귀 — 값매핑 없이 confirm 하면 커맨드가 원본 소스 값 그대로 유지된다 ──────

    @Test
    fun `값 매핑 없이 confirm 하면 워커 커맨드의 상태 유형 우선순위가 원본 그대로 유지된다 (기존 호출부 불변, 회귀)`() {
        val csv =
            "Título,Estado,Tipo,Prioridad\n" +
                "다른버그입니다,In Progress,Task,High\n"

        val analysis = importJobService.analyze(analyzeCommand(csv))
        val jobId = analysis.job.id

        val fieldMappings =
            mapOf(
                "Título" to TargetField.SUMMARY.key,
                "Estado" to TargetField.STATUS.key,
                "Tipo" to TargetField.TYPE.key,
                "Prioridad" to TargetField.PRIORITY.key,
            )

        // valueMappings 인자를 생략 — 값 매핑 없이 confirm 하던 PR-A/PR-B 시절 기존 호출부와 동일한 형태.
        importMappingService.confirm(jobId = jobId, actor = actorId, fieldMappings = fieldMappings, dryRun = false)
        assertThat(importValueMappingRepository.findByJobId(jobId)).isEmpty()

        importJobWorker.pollAndProcess()

        assertThat(importJobRepository.findStatus(jobId)).isEqualTo(ImportJobStatus.COMPLETED)
        assertThat(issueImportPort.capturedCommands).hasSize(1)
        val command = issueImportPort.capturedCommands.single()
        assertThat(command.typeName).isEqualTo("Task")
        assertThat(command.statusName).isEqualTo("In Progress")
        assertThat(command.priority).isEqualTo(2) // canonical "High" 의 숫자 값(2), 원본 그대로
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
