// IssueImportAdapter 통합 테스트 — 행 원자성/권한 위임/이메일·이름 매핑 (FR-IM-01 Task 8, 실 repo+시드)

package com.bts.issue.adapter.outbound.imports

import com.bts.issue.adapter.inbound.rest.IssueControllerTransitionIntegrationTest.TestConfig
import com.bts.issue.application.IssueApplicationService
import com.bts.issue.application.IssueImportStatusService
import com.bts.issue.attachment.application.AttachmentScanUnavailableException
import com.bts.issue.attachment.application.AttachmentStoragePort
import com.bts.issue.attachment.application.IssueAttachmentService
import com.bts.issue.attachment.application.ScanVerdict
import com.bts.issue.attachment.application.VirusScanPort
import com.bts.issue.attachment.domain.Attachment
import com.bts.issue.attachment.repository.AttachmentRepository
import com.bts.issue.comment.application.CommentApplicationService
import com.bts.issue.comment.repository.CommentRepository
import com.bts.issue.component.application.ComponentApplicationService
import com.bts.issue.component.repository.ComponentRepository
import com.bts.issue.domain.ActorId
import com.bts.issue.domain.IssueKey
import com.bts.issue.event.IssueEventPublisher
import com.bts.issue.history.IssueChangeHistoryRepository
import com.bts.issue.history.IssueHistoryRecorder
import com.bts.issue.history.JdbcIssueChangeHistoryRepository
import com.bts.issue.project.ProjectLookup
import com.bts.issue.project.repository.ProjectLeadRepository
import com.bts.issue.project.repository.ProjectLookupRepository
import com.bts.issue.repository.IssueRepository
import com.bts.issue.resolution.repository.ResolutionRepository
import com.bts.issue.type.repository.IssueTypeRepository
import com.bts.issue.version.application.VersionApplicationService
import com.bts.issue.version.repository.VersionRepository
import com.bts.issue.worklog.application.WorklogService
import com.bts.issue.worklog.domain.Worklog
import com.bts.issue.worklog.repository.WorklogRepository
import com.bts.shared.issue.ImportAttachment
import com.bts.shared.issue.ImportAttachmentSource
import com.bts.shared.issue.ImportChangeGroup
import com.bts.shared.issue.ImportChangeItem
import com.bts.shared.issue.ImportComment
import com.bts.shared.issue.ImportWorklog
import com.bts.shared.issue.IssueImportCommand
import com.bts.shared.issue.IssueImportResult
import com.bts.shared.issue.IssueTypeKey
import com.bts.shared.permission.ComponentPermission
import com.bts.shared.permission.ComponentPermissionResolver
import com.bts.shared.permission.IssuePermission
import com.bts.shared.permission.IssuePermissionResolver
import com.bts.shared.permission.IssueScope
import com.bts.shared.permission.VersionPermission
import com.bts.shared.permission.VersionPermissionResolver
import com.bts.shared.user.UserLookupPort
import com.bts.shared.workflow.ProjectKey
import com.bts.shared.workflow.WorkflowStateCatalog
import com.bts.shared.workflow.WorkflowStateView
import com.bts.workflow.adapter.inbound.WorkflowTransitionAdapter
import com.bts.workflow.scheme.adapter.inbound.WorkflowKeyResolverImpl
import io.mockk.mockk
import org.flywaydb.core.Flyway
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.junit.jupiter.SpringExtension
import org.springframework.test.context.web.WebAppConfiguration
import org.springframework.web.context.WebApplicationContext
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.sql.DriverManager
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * [IssueImportAdapter] 전 구간 Testcontainers 통합 테스트 (FR-IM-01 Task 8).
 *
 * 어댑터 → [IssueApplicationService] → 실 repository → 실 PostgreSQL 전 구간을 검증한다.
 * mockk 로는 표면화되지 않는 트랜잭션 경계(행 원자성)·권한 위임·jOOQ 매핑을 실증한다.
 *
 * ## 검증 시나리오
 * - S1. 코어 필드 + typeName 매칭(대소문자무시) + priority/labels/assignee 반영
 * - S2. reporterEmail 매칭 → reporterId=매칭 사용자 / 미매칭 → requesterUserId 폴백
 * - S3. assigneeEmail 매칭 → assigneeId=매칭 사용자 / 미매칭 → assigneeId=null
 * - S4. typeName 미매칭 → Task 폴백 + warning
 * - S5. componentNames 일부 미발견 → 매칭분만 연결 + 미발견 warning
 * - S6. CREATE_ISSUE 권한 없는 requester → Failure(FORBIDDEN), 이슈 미생성
 * - S7. dryRun=true → 이슈/이벤트 미생성, 검증 결과만 반환
 * - S8(CONCERN #1). update 강제 실패(priority 범위 밖) → 이슈 롤백 + IssueCreated 이벤트 미발행
 * - S9(CONCERN C1). dryRun 은 update-유발 행(priority/assignee)에 대해 UPDATE 권한도 미러 예측한다 —
 *   실제 경로(executeImport)가 priority/labels 지정 시 updateIssue, assignee 매칭 시 changeAssignee 를
 *   호출해 IssuePermission.UPDATE 를 검증하므로, dryRun 도 동일 조건에서 UPDATE 를 확인해야
 *   "dryRun 성공 → 실제 실행 시 FORBIDDEN" 불일치를 막는다.
 */
@Suppress("LargeClass") // Task 4 — 컴포넌트/버전 자동생성+상태 반영 시나리오 10건 추가로 임계 초과, 분리 실익 없음
@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [TestConfig::class, IssueImportAdapterTest.ImportTestConfig::class])
@WebAppConfiguration
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IssueImportAdapterTest {
    /**
     * import 어댑터 통합 테스트 보조 설정.
     *
     * 실 [ComponentRepository] + 선택적 거부 [IssuePermissionResolver] + fixture [UserLookupPort] 로
     * [TestConfig] 의 stub 들을 대체하고, 이를 사용하는 [IssueApplicationService] 를 [Primary] 로 재조립한다.
     */
    @Configuration
    @Suppress("LongParameterList")
    open class ImportTestConfig {
        // [TestConfig.dslContext] 는 DSL.using(rawDataSource, ...) 로 구성돼 jOOQ 가 Spring 트랜잭션 동기화를
        // 거치지 않고 매 쿼리마다 DriverManagerDataSource.getConnection() 을 직접 호출해 autoCommit=true 인
        // 별도 커넥션을 매번 새로 연다(jOOQ DataSourceConnectionProvider 는 Spring-aware 조회를 하지 않음).
        // 그 결과 createIssue 의 INSERT 가 즉시 커밋돼버려 이후 setRollbackOnly() 가 무력화된다
        // (행 원자성 CONCERN #1 RED 단계에서 실제로 표면화됨 — 다른 기존 통합 테스트는 실패 지점이 항상
        // insert 이전(validate)이라 이 gap 을 드러낸 적이 없었다). TransactionAwareDataSourceProxy 로 감싸
        // jOOQ 의 getConnection() 이 DataSourceUtils 를 경유해 스레드 바인딩된 트랜잭션 커넥션을 재사용하도록
        // 강제한다 — Spring Boot 의 실제 jOOQ 자동구성(JooqAutoConfiguration)이 프로덕션에서 하는 것과 동일한
        // 보정을 이 테스트 컨텍스트에도 적용한다.
        @Bean
        @Primary
        open fun importTransactionAwareDslContext(dataSource: DriverManagerDataSource): DSLContext =
            DSL.using(TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES)

        @Bean
        open fun importComponentRepository(dsl: DSLContext): ComponentRepository = ComponentRepository(dsl)

        // 실 repository 필요 — mockk(relaxed=true) 는 UUID? 반환 메서드에도 nil UUID 를 fabricate 해
        // ActorId(nil UUID) 로 이어져 resolveDefaultAssignee 가 예기치 않게 실패한다(AutoAssignTestConfig 동형 함정).
        @Bean
        open fun importProjectLeadRepository(dsl: DSLContext): ProjectLeadRepository = ProjectLeadRepository(dsl)

        @Bean
        open fun importVersionRepository(dsl: DSLContext): VersionRepository = VersionRepository(dsl)

        @Bean
        @Primary
        open fun importUserLookupPort(): UserLookupPort = FakeImportUserLookupPort()

        @Bean
        @Primary
        open fun importPermissionResolver(): IssuePermissionResolver =
            SelectiveDenyPermissionResolver(
                denied =
                    setOf(
                        FORBIDDEN_REQUESTER_ID to IssuePermission.CREATE,
                        UPDATE_DENIED_REQUESTER_ID to IssuePermission.UPDATE,
                        TRANSITION_DENIED_REQUESTER_ID to IssuePermission.TRANSITION,
                    ),
                revokeUpdateAfterFirstCallFor = TOCTOU_ATTACHMENT_REQUESTER_ID,
            )

        // ── Task 4 — 컴포넌트/버전 자동생성 + 상태 반영 보조 빈 ────────────────────

        @Bean
        open fun importProjectLookupRepository(dsl: DSLContext): ProjectLookupRepository = ProjectLookupRepository(dsl)

        @Bean
        open fun importProjectLookup(repository: ProjectLookupRepository): ProjectLookup = ProjectLookup(repository)

        /** 컴포넌트 CREATE 는 [AUTO_CREATE_ALLOWED_REQUESTER_ID] 만 허용, 그 외(NORMAL_REQUESTER_ID 포함)는 기본 거부. */
        @Bean
        open fun importComponentPermissionResolver(): ComponentPermissionResolver =
            SelectiveAllowComponentPermissionResolver(allowed = setOf(AUTO_CREATE_ALLOWED_REQUESTER_ID))

        /** 버전 CREATE 도 컴포넌트와 동형 — [AUTO_CREATE_ALLOWED_REQUESTER_ID] 만 허용, 기본 거부. */
        @Bean
        open fun importVersionPermissionResolver(): VersionPermissionResolver =
            SelectiveAllowVersionPermissionResolver(allowed = setOf(AUTO_CREATE_ALLOWED_REQUESTER_ID))

        @Bean
        open fun importComponentApplicationService(
            permissionResolver: ComponentPermissionResolver,
            projectLookup: ProjectLookup,
            userLookupPort: UserLookupPort,
            repo: ComponentRepository,
        ): ComponentApplicationService =
            ComponentApplicationService(
                permissionResolver = permissionResolver,
                projectLookup = projectLookup,
                userLookupPort = userLookupPort,
                repo = repo,
            )

        @Bean
        open fun importVersionApplicationService(
            permissionResolver: VersionPermissionResolver,
            projectLookup: ProjectLookup,
            repo: VersionRepository,
            clock: Clock,
        ): VersionApplicationService =
            VersionApplicationService(
                permissionResolver = permissionResolver,
                projectLookup = projectLookup,
                repo = repo,
                clock = clock,
            )

        // 고정 상태 목록만 반환하는 테스트 전용 WorkflowStateCatalog — project-workflow BC 의 실제
        // 스킴 결선(WorkflowResolverImpl 등)은 이 어댑터 테스트의 책임 범위 밖이다(BC 격리).
        // IssueImportStatusService 자체의 상태 매칭 로직은 IssueImportStatusServiceTest(mockk)가
        // 이미 단위 검증했으므로, 여기서는 어댑터의 호출·OCC 버전 스레딩·경고 강등만 검증한다.
        @Bean
        open fun importWorkflowStateCatalog(): WorkflowStateCatalog =
            FixedStatesWorkflowStateCatalog(
                states =
                    listOf(
                        WorkflowStateView(key = "open", name = "Open"),
                        WorkflowStateView(key = "in_progress", name = "In Progress"),
                        WorkflowStateView(key = "done", name = "Done", isDone = true, category = "DONE"),
                    ),
            )

        @Bean
        open fun importIssueImportStatusService(
            issueRepository: IssueRepository,
            workflowStateCatalog: WorkflowStateCatalog,
            permissionResolver: IssuePermissionResolver,
            issueTypeRepository: IssueTypeRepository,
        ): IssueImportStatusService =
            IssueImportStatusService(
                issueRepository = issueRepository,
                workflowStateCatalog = workflowStateCatalog,
                permissionResolver = permissionResolver,
                issueTypeRepository = issueTypeRepository,
            )

        @Bean
        @Primary
        open fun issueApplicationServiceForImport(
            repo: IssueRepository,
            issueTypeRepository: IssueTypeRepository,
            resolutionRepository: ResolutionRepository,
            eventPublisher: IssueEventPublisher,
            permissionResolver: IssuePermissionResolver,
            workflowTransitionAdapter: WorkflowTransitionAdapter,
            workflowKeyResolver: WorkflowKeyResolverImpl,
            userLookupPort: UserLookupPort,
            componentRepository: ComponentRepository,
            projectLeadRepository: ProjectLeadRepository,
            versionRepository: VersionRepository,
            clock: Clock,
        ): IssueApplicationService =
            IssueApplicationService(
                repo = repo,
                issueTypeRepository = issueTypeRepository,
                resolutionRepository = resolutionRepository,
                eventPublisher = eventPublisher,
                permissionResolver = permissionResolver,
                workflowPort = workflowTransitionAdapter,
                workflowKeyResolver = workflowKeyResolver,
                userLookupPort = userLookupPort,
                componentRepository = componentRepository,
                projectLeadRepository = projectLeadRepository,
                versionRepository = versionRepository,
                clock = clock,
                historyRecorder = mockk(relaxed = true),
            )

        // ── Task 7(PR3) — 댓글/worklog 동반 생성 보조 빈 ────────────────────────────

        @Bean
        open fun importCommentRepository(dsl: DSLContext): CommentRepository = CommentRepository(dsl)

        @Bean
        open fun importCommentApplicationService(
            commentRepository: CommentRepository,
            issueRepository: IssueRepository,
            permissionResolver: IssuePermissionResolver,
            clock: Clock,
        ): CommentApplicationService =
            CommentApplicationService(
                commentRepository = commentRepository,
                issueRepository = issueRepository,
                permissionResolver = permissionResolver,
                clock = clock,
            )

        @Bean
        open fun importWorklogRepository(dsl: DSLContext): WorklogRepository = WorklogRepository(dsl)

        // FaultInjectingWorklogService 로 감싼다 — S31(예상외 throw → 행 원자성 안전망) 전용, 그 외
        // 모든 시나리오는 UNEXPECTED_THROW_MARKER 를 쓰지 않으므로 실 WorklogService 로 그대로 위임된다.
        @Bean
        open fun importWorklogService(
            worklogRepository: WorklogRepository,
            issueRepository: IssueRepository,
            permissionResolver: IssuePermissionResolver,
        ): WorklogService =
            FaultInjectingWorklogService(
                worklogRepository = worklogRepository,
                issueRepository = issueRepository,
                permissionResolver = permissionResolver,
                historyRecorder = mockk(relaxed = true),
            )

        // ── Task 9(PR4) — 첨부/이력 동반 생성 보조 빈 ────────────────────────────────
        // CONCERN-4 — fake AttachmentStoragePort(no-op put/remove)·fake VirusScanPort(선택적
        // 미가용 트리거)·실 AttachmentRepository(issue_attachments round-trip). AttachmentRepository 는
        // [FaultInjectingAttachmentRepository] 로 감싸 S44(Task9-S10b) 행 원자성 안전망만 검증하고,
        // 그 외 모든 시나리오는 super.insert 로 그대로 위임돼 실 DB round-trip 이 보존된다
        // ([FaultInjectingWorklogService] 와 동형).

        @Bean
        open fun importAttachmentRepository(dsl: DSLContext): AttachmentRepository {
            return FaultInjectingAttachmentRepository(dsl)
        }

        // 반환 타입을 구현체(NoOpAttachmentStoragePort)로 선언 — S46(C2 hot-fix)이 lastPutReceivedByteCount
        // 를 검증하려면 테스트가 이 구체 타입으로 autowire 해야 한다(AttachmentStoragePort 인터페이스에는
        // 없는 테스트 전용 계측 필드). IssueAttachmentService(storagePort: AttachmentStoragePort) 주입은
        // 서브타입이라 그대로 성립한다.
        @Bean
        open fun importAttachmentStoragePort(): NoOpAttachmentStoragePort = NoOpAttachmentStoragePort()

        @Bean
        open fun importVirusScanPort(): VirusScanPort = SelectivelyUnavailableVirusScanPort()

        @Bean
        @Suppress("LongParameterList")
        open fun importIssueAttachmentService(
            storagePort: AttachmentStoragePort,
            attachmentRepository: AttachmentRepository,
            permissionResolver: IssuePermissionResolver,
            issueRepository: IssueRepository,
            scanPort: VirusScanPort,
            clock: Clock,
        ): IssueAttachmentService =
            IssueAttachmentService(
                storagePort = storagePort,
                attachmentRepository = attachmentRepository,
                permissionResolver = permissionResolver,
                issueRepository = issueRepository,
                scanPort = scanPort,
                clock = clock,
            )

        // NamedParameterJdbcTemplate 은 raw dataSource 로도 Spring 트랜잭션에 올바르게 참여한다
        // (JdbcTemplate 계열은 DataSourceUtils.getConnection 을 내부적으로 항상 사용 — jOOQ 의
        // DataSourceConnectionProvider 와 달리 TransactionAwareDataSourceProxy 래핑이 불필요하다.
        // IssueChangeHistoryE2EIntegrationTest.HistoryE2EConfig 와 동일 근거·동일 패턴).
        @Bean
        open fun importChangeHistoryJdbcTemplate(dataSource: DriverManagerDataSource): NamedParameterJdbcTemplate =
            NamedParameterJdbcTemplate(dataSource)

        @Bean
        open fun importIssueChangeHistoryRepository(jdbc: NamedParameterJdbcTemplate): IssueChangeHistoryRepository =
            JdbcIssueChangeHistoryRepository(jdbc)

        // detector/resolver 는 recordImported 가 우회하므로 mockk(relaxed=true) 로 충분하다(CONCERN-4).
        // repository 는 실 빈 — occurredAt/actorId 실제 저장·조회를 검증해야 하기 때문이다.
        @Bean
        open fun importIssueHistoryRecorder(repository: IssueChangeHistoryRepository): IssueHistoryRecorder =
            IssueHistoryRecorder(
                detector = mockk(relaxed = true),
                resolver = mockk(relaxed = true),
                repository = repository,
            )

        @Bean
        @Suppress("LongParameterList")
        open fun issueImportAdapter(
            issueApplicationService: IssueApplicationService,
            issueRepository: IssueRepository,
            issueTypeRepository: IssueTypeRepository,
            componentRepository: ComponentRepository,
            userLookupPort: UserLookupPort,
            permissionResolver: IssuePermissionResolver,
            componentApplicationService: ComponentApplicationService,
            versionApplicationService: VersionApplicationService,
            versionRepository: VersionRepository,
            componentPermissionResolver: ComponentPermissionResolver,
            versionPermissionResolver: VersionPermissionResolver,
            issueImportStatusService: IssueImportStatusService,
            commentApplicationService: CommentApplicationService,
            worklogService: WorklogService,
            attachmentService: IssueAttachmentService,
            historyRecorder: IssueHistoryRecorder,
        ): IssueImportAdapter =
            IssueImportAdapter(
                issueApplicationService = issueApplicationService,
                issueRepository = issueRepository,
                issueTypeRepository = issueTypeRepository,
                componentRepository = componentRepository,
                userLookupPort = userLookupPort,
                permissionResolver = permissionResolver,
                componentApplicationService = componentApplicationService,
                versionApplicationService = versionApplicationService,
                versionRepository = versionRepository,
                componentPermissionResolver = componentPermissionResolver,
                versionPermissionResolver = versionPermissionResolver,
                issueImportStatusService = issueImportStatusService,
                commentApplicationService = commentApplicationService,
                worklogService = worklogService,
                attachmentService = attachmentService,
                historyRecorder = historyRecorder,
            )
    }

    /**
     * (actor, permission) 조합을 지정해 거부하고 그 외에는 모두 허용하는 테스트 전용 resolver.
     *
     * [revokeUpdateAfterFirstCallFor] 가 지정되면 그 actor 의 UPDATE 판정만 최초 1회는 허용하고
     * 이후 호출부터는 거부한다(S45 hot-fix — [IssueImportAdapter.applyAttachments] 사전체크(1회차)는
     * 통과시키되 곧바로 이어지는 [IssueAttachmentService.upload] 내부 checkPermission(2회차)에서
     * 거부해, 사전체크 통과 이후 실행 시점에만 권한이 취소되는 레이스를 재현한다 — 둘 다 동일
     * (actor, UPDATE, [IssueScope.Issue]) 조합이라 호출 순서로만 구분할 수 있다).
     */
    private class SelectiveDenyPermissionResolver(
        private val denied: Set<Pair<UUID, IssuePermission>>,
        private val revokeUpdateAfterFirstCallFor: UUID? = null,
    ) : IssuePermissionResolver {
        private val updateCallCounts = mutableMapOf<UUID, Int>()

        override fun hasPermission(
            actorId: UUID,
            permission: IssuePermission,
            scope: IssueScope,
        ): Boolean {
            if ((actorId to permission) in denied) return false
            return isUpdateAllowed(actorId, permission)
        }

        /** [revokeUpdateAfterFirstCallFor] 의 최초 1회만 허용하는 TOCTOU 판정 — [hasPermission] 분리. */
        private fun isUpdateAllowed(
            actorId: UUID,
            permission: IssuePermission,
        ): Boolean {
            if (permission != IssuePermission.UPDATE || actorId != revokeUpdateAfterFirstCallFor) return true
            val callCount = (updateCallCounts[actorId] ?: 0) + 1
            updateCallCounts[actorId] = callCount
            return callCount == 1
        }
    }

    /**
     * actorId 가 [allowed] 집합에 있을 때만 컴포넌트 CREATE 를 허용하고 그 외에는 모두 거부하는
     * 테스트 전용 resolver — 기본 거부(NORMAL_REQUESTER_ID 등 기존 시나리오의 actor 는 모두 미포함이라
     * 기존 S1~S9 는 영향받지 않는다).
     */
    private class SelectiveAllowComponentPermissionResolver(
        private val allowed: Set<UUID>,
    ) : ComponentPermissionResolver {
        override fun hasPermission(
            actorId: UUID,
            permission: ComponentPermission,
            projectId: UUID,
        ): Boolean = actorId in allowed
    }

    /** [SelectiveAllowComponentPermissionResolver] 와 동형 — 버전 CREATE 전용. */
    private class SelectiveAllowVersionPermissionResolver(
        private val allowed: Set<UUID>,
    ) : VersionPermissionResolver {
        override fun hasPermission(
            actorId: UUID,
            permission: VersionPermission,
            projectId: UUID,
        ): Boolean = actorId in allowed
    }

    /**
     * [WorklogService.createImported] 를 그대로 위임하되, `comment` 가 [UNEXPECTED_THROW_MARKER] 일
     * 때만 의도적으로 예상외 [RuntimeException] 을 던지는 테스트 전용 spy(S31 전용).
     *
     * 행 원자성 안전망(★2 KDoc 참조)이 "사전체크로 걸러지지 않는 예상외 예외"가 실제로 나더라도
     * 여전히 이슈까지 롤백하는지 실 tx 로 검증하기 위한 fault-injection — 마커를 쓰지 않는 다른
     * 모든 시나리오(S24/S25/S27/S29 등)는 `super.createImported` 로 그대로 위임돼 영향받지 않는다.
     * kotlin-spring 컴파일러 플러그인이 `@Service` 를 자동 open 하므로(all-open) 별도 `open` 없이도
     * 서브클래싱·override 가능하다.
     */
    private class FaultInjectingWorklogService(
        worklogRepository: WorklogRepository,
        issueRepository: IssueRepository,
        permissionResolver: IssuePermissionResolver,
        historyRecorder: IssueHistoryRecorder,
    ) : WorklogService(worklogRepository, issueRepository, permissionResolver, historyRecorder) {
        override fun createImported(
            actor: ActorId,
            issueKey: IssueKey,
            authorId: ActorId,
            timeSpentSeconds: Int,
            startedAt: Instant,
            comment: String?,
        ): Worklog {
            if (comment == UNEXPECTED_THROW_MARKER) {
                error("S31 예상외 throw 시뮬레이션")
            }
            return super.createImported(actor, issueKey, authorId, timeSpentSeconds, startedAt, comment)
        }
    }

    /**
     * filename 이 [UNEXPECTED_ATTACHMENT_THROW_MARKER] 일 때만 insert 에서 의도적으로 예상외
     * [RuntimeException] 을 던지는 테스트 전용 spy(S44/Task9-S10b) — [FaultInjectingWorklogService] 와
     * 동형. 마커를 쓰지 않는 모든 시나리오는 `super.insert` 로 그대로 위임돼 실 DB round-trip 이 보존된다.
     *
     * insert() 가 `error(...)` 로 super 호출 이전에 즉시 throw 하므로 실제 DB write 는 발생하지 않는다 —
     * 사전체크(길이 등)가 커버하지 않는 "예상외" 예외가 발생해도 [IssueImportAdapter.importIssue] 의
     * catch 블록이 트랜잭션 전체를 rollback-only 로 표시해 이슈까지 함께 롤백하는지가 검증 대상이다
     * (BLOCKER-2 — insert throw 는 행 원자성 롤백 대상이지 best-effort 경고 대상이 아님).
     */
    private class FaultInjectingAttachmentRepository(
        dsl: DSLContext,
    ) : AttachmentRepository(dsl) {
        override fun insert(attachment: Attachment) {
            if (attachment.filename == UNEXPECTED_ATTACHMENT_THROW_MARKER) {
                error("S44 예상외 throw 시뮬레이션")
            }
            super.insert(attachment)
        }
    }

    /**
     * MinIO 를 실제로 호출하지 않는 테스트 전용 storage port(CONCERN-4) — 이 테스트는
     * `issue_attachments` round-trip 만 검증하면 충분하고 실 MinIO 컨테이너는 불필요하다.
     *
     * [put] 은 [size] 만큼만 bound-read 한다 — 실제 [com.bts.issue.attachment.adapter.MinioStorageAdapter.put]
     * 이 `PutObjectArgs.stream(input, size, ...)` 로 정확히 size 바이트만 읽는 동작을 재현한다(S46,
     * C2 hot-fix). [size] 가 호출자(어댑터)로부터 신뢰할 수 없는 값을 받으면 여기서도 동일하게
     * 절단이 재현돼야 회귀를 표면화할 수 있다. [lastPutReceivedByteCount] 로 마지막 호출이 실제
     * bound-read 한 바이트 수를 기록해 절단 여부를 검증한다.
     *
     * `private` 이 아니다 — S46(C2 hot-fix)의 `@Autowired lateinit var attachmentStoragePort:
     * NoOpAttachmentStoragePort` 필드와 [ImportTestConfig.importAttachmentStoragePort] 의 반환
     * 타입이 이 구체 타입을 그대로 노출해야 하는데, Kotlin 은 public 멤버가 `private-in-class`
     * 타입을 노출하는 것을 컴파일 에러로 막는다.
     */
    class NoOpAttachmentStoragePort : AttachmentStoragePort {
        /** 마지막 [put] 호출이 실제로 bound-read 한 바이트 수(S46 검증용). */
        var lastPutReceivedByteCount: Int = 0
            private set

        override fun put(
            storageKey: String,
            input: InputStream,
            size: Long,
            contentType: String,
        ) {
            lastPutReceivedByteCount = input.readNBytes(size.toInt()).size
        }

        override fun get(storageKey: String): InputStream = ByteArrayInputStream(ByteArray(0))

        override fun remove(storageKey: String) {
            // no-op
        }
    }

    /**
     * 스트림 내용이 [SCAN_UNAVAILABLE_TRIGGER_BYTES] 와 일치할 때만 스캔 미가용 예외를 던지고,
     * 그 외에는 CLEAN 을 반환하는 테스트 전용 스캐너(S37/Task9-S4). [VirusScanPort.scan] 은 filename 을
     * 받지 않으므로(스트림만 받음) 파일별 분기는 내용 기반으로 트리거한다.
     */
    private class SelectivelyUnavailableVirusScanPort : VirusScanPort {
        override fun scan(input: InputStream): ScanVerdict {
            val bytes = input.readBytes()
            if (bytes.contentEquals(SCAN_UNAVAILABLE_TRIGGER_BYTES)) {
                throw AttachmentScanUnavailableException("test-trigger: scanner unavailable")
            }
            return ScanVerdict.CLEAN
        }
    }

    /**
     * [open] 이 반환한 스트림의 close() 호출 여부를 기록하는 테스트 전용 래퍼(BLOCKER-1 검증) —
     * 어댑터가 [ImportAttachmentSource.open] 스트림을 반드시 `.use { }` 로 닫는지 실증한다.
     */
    private class CloseTrackingInputStream(
        private val delegate: InputStream,
    ) : InputStream() {
        var closed: Boolean = false
            private set

        override fun read(): Int = delegate.read()

        override fun read(
            b: ByteArray,
            off: Int,
            len: Int,
        ): Int = delegate.read(b, off, len)

        override fun close() {
            closed = true
            delegate.close()
        }
    }

    /**
     * filename → 바이트 내용 매핑을 제공하는 테스트 전용 [ImportAttachmentSource].
     * 미매칭 filename 은 null(스킵)을 반환한다. [open] 이 반환하는 모든 스트림을 [CloseTrackingInputStream]
     * 으로 감싸 [openedStreams] 에 기록해, 어댑터가 실제로 close() 하는지(BLOCKER-1) 검증할 수 있게 한다.
     */
    private class FixtureImportAttachmentSource(
        private val filesByName: Map<String, ByteArray>,
    ) : ImportAttachmentSource {
        val openedStreams = mutableListOf<CloseTrackingInputStream>()

        override fun open(
            filename: String,
            sourceKey: String?,
        ): InputStream? {
            val bytes = filesByName[filename] ?: return null
            val tracker = CloseTrackingInputStream(ByteArrayInputStream(bytes))
            openedStreams += tracker
            return tracker
        }
    }

    /**
     * [remaining] 바이트만큼 고정 바이트(0)를 지연 생성하는 테스트 전용 [InputStream](S47, C2 hot-fix
     * TOO_LARGE 경계 테스트 전용) — 100MB + 1 바이트를 실제 [ByteArray] 로 미리 전량 할당하지 않고도
     * 경계값 스트림을 만들기 위함이다.
     */
    private class FixedLengthInputStream(
        private var remaining: Long,
    ) : InputStream() {
        override fun read(): Int {
            if (remaining <= 0) return -1
            remaining--
            return 0
        }

        override fun read(
            b: ByteArray,
            off: Int,
            len: Int,
        ): Int {
            if (remaining <= 0) return -1
            val toRead = minOf(len.toLong(), remaining).toInt()
            remaining -= toRead
            return toRead
        }
    }

    /**
     * [filename] 요청 시 [totalBytes] 길이의 [FixedLengthInputStream] 을 제공하는 테스트 전용
     * [ImportAttachmentSource](S47, C2 hot-fix TOO_LARGE 경계 테스트 전용).
     */
    private class OversizedImportAttachmentSource(
        private val filename: String,
        private val totalBytes: Long,
    ) : ImportAttachmentSource {
        override fun open(
            filename: String,
            sourceKey: String?,
        ): InputStream? {
            if (filename != this.filename) return null
            return FixedLengthInputStream(totalBytes)
        }
    }

    /**
     * 고정 상태 목록만 반환하는 테스트 전용 [WorkflowStateCatalog].
     *
     * project-workflow BC 의 실제 스킴 결선은 이 어댑터 테스트의 책임 범위 밖이다(BC 격리) —
     * [ImportTestConfig.importWorkflowStateCatalog] KDoc 참조.
     *
     * `open` 필수 — [WorkflowStateCatalog.listStates] 의 인터페이스 레벨 `@Transactional` 을
     * [TestConfig] 의 `@EnableTransactionManagement(proxyTargetClass = true)` 가 CGLIB 서브클래싱
     * 으로 감싸려 시도하는데, Kotlin 클래스는 기본 final 이라 `open` 없이는 Enhancer 가 실패한다.
     */
    private open class FixedStatesWorkflowStateCatalog(
        private val states: List<WorkflowStateView>,
    ) : WorkflowStateCatalog {
        override fun listStates(
            projectKey: ProjectKey,
            issueTypeKey: IssueTypeKey?,
        ): List<WorkflowStateView> = states
    }

    /** 고정 fixture 이메일→UUID 매핑을 제공하는 테스트 전용 [UserLookupPort]. */
    private class FakeImportUserLookupPort : UserLookupPort {
        override fun exists(userId: UUID): Boolean = true

        override fun resolveByEmails(emails: Set<String>): Map<String, UUID> = FIXTURES.filterKeys { it in emails }

        companion object {
            private val FIXTURES =
                mapOf(
                    ALICE_EMAIL to ALICE_ID,
                    BOB_EMAIL to BOB_ID,
                )
        }
    }

    @Autowired
    lateinit var webApplicationContext: WebApplicationContext

    @Autowired
    lateinit var issueImportAdapter: IssueImportAdapter

    @Autowired
    lateinit var issueRepository: IssueRepository

    /** S46(C2 hot-fix) — [NoOpAttachmentStoragePort.lastPutReceivedByteCount] 검증용 구체 타입 autowire. */
    @Autowired
    lateinit var attachmentStoragePort: NoOpAttachmentStoragePort

    companion object {
        private const val PROJECT_KEY = "IMPORT"

        /** CREATE_ISSUE 권한을 보유한 정상 요청자. */
        val NORMAL_REQUESTER_ID: UUID = UUID.fromString("00000000-0000-4000-8000-000000000201")

        /** [SelectiveDenyPermissionResolver] 가 CREATE_ISSUE 를 거부하도록 지정한 요청자. */
        val FORBIDDEN_REQUESTER_ID: UUID = UUID.fromString("00000000-0000-4000-8000-000000000202")

        /**
         * [SelectiveDenyPermissionResolver] 가 EDIT_ISSUE(UPDATE) 만 거부하도록 지정한 요청자
         * (CREATE_ISSUE 는 보유). S9(CONCERN C1) — dryRun 이 update-유발 행에 대해 UPDATE 권한도
         * 미러 예측하는지 검증한다.
         */
        val UPDATE_DENIED_REQUESTER_ID: UUID = UUID.fromString("00000000-0000-4000-8000-000000000205")

        /**
         * [SelectiveAllowComponentPermissionResolver]/[SelectiveAllowVersionPermissionResolver] 가
         * 컴포넌트/버전 CREATE 를 허용하도록 지정한 요청자(S10/S12/S16/S18). 그 외 모든 actor(
         * NORMAL_REQUESTER_ID 포함)는 기본 거부 — 기존 S5(컴포넌트 미발견 skip) 시나리오 불변.
         */
        val AUTO_CREATE_ALLOWED_REQUESTER_ID: UUID = UUID.fromString("00000000-0000-4000-8000-000000000206")

        /** [SelectiveDenyPermissionResolver] 가 TRANSITION 만 거부하도록 지정한 요청자(S15/S19). */
        val TRANSITION_DENIED_REQUESTER_ID: UUID = UUID.fromString("00000000-0000-4000-8000-000000000207")

        /**
         * [SelectiveDenyPermissionResolver] 가 UPDATE 판정을 최초 1회만 허용하고 이후 거부하도록
         * 지정한 요청자(S45, C3 hot-fix — 사전체크 통과 이후 실행 시점 권한 취소 레이스 시뮬레이션).
         */
        val TOCTOU_ATTACHMENT_REQUESTER_ID: UUID = UUID.fromString("00000000-0000-4000-8000-000000000208")

        /** reporterEmail 매칭 fixture. */
        val ALICE_ID: UUID = UUID.fromString("00000000-0000-4000-8000-000000000203")
        const val ALICE_EMAIL = "alice@example.com"

        /** assigneeEmail 매칭 fixture. */
        val BOB_ID: UUID = UUID.fromString("00000000-0000-4000-8000-000000000204")
        const val BOB_EMAIL = "bob@example.com"

        /** S17 — 사전 시드된 기존 버전 이름(자동생성 권한과 무관하게 이름 매칭만으로 링크된다). */
        const val EXISTING_VERSION_NAME = "v1.0-existing"

        /** S31 — [FaultInjectingWorklogService] 가 이 값을 comment 로 받으면 의도적으로 예상외 예외를 던진다. */
        const val UNEXPECTED_THROW_MARKER = "__unexpected_throw__"

        /** S44(Task9-S10b) — [FaultInjectingAttachmentRepository] 가 이 filename 이면 의도적으로 예상외 예외를 던진다. */
        const val UNEXPECTED_ATTACHMENT_THROW_MARKER = "__attachment_unexpected_throw__.png"

        /** S37(Task9-S4) — [SelectivelyUnavailableVirusScanPort] 가 이 바이트와 일치하면 스캔 미가용 예외를 던진다. */
        val SCAN_UNAVAILABLE_TRIGGER_BYTES: ByteArray = byteArrayOf(9, 9, 9, 9)

        /**
         * S47(C2 hot-fix) — [IssueImportAdapter] 의 `MAX_ATTACHMENT_UPLOAD_BYTES`(private) 와
         * 동일한 100MB 상한값의 테스트 전용 사본. 어댑터 private const 는 이 테스트에서 직접
         * 참조할 수 없어 값만 그대로 복제한다.
         */
        const val MAX_ATTACHMENT_UPLOAD_BYTES_FOR_TEST: Long = 100L * 1024 * 1024

        private var migrated = false
        private var seeded = false

        /** S5 — 매칭 대상 컴포넌트. */
        lateinit var backendComponentId: UUID

        /** S17 — 매칭 대상 버전(사전 시드). */
        lateinit var existingVersionId: UUID
    }

    @BeforeAll
    fun setUpAll() {
        if (!migrated) {
            applyMigrations()
            migrated = true
        }
        if (!seeded) {
            seedProjectAndComponents()
            seeded = true
        }
    }

    @BeforeEach
    fun cleanState() {
        conn().use { c ->
            c.createStatement().use { stmt ->
                stmt.execute(
                    "DELETE FROM issue_components WHERE issue_id IN " +
                        "(SELECT id FROM issues WHERE key LIKE '$PROJECT_KEY-%')",
                )
                // issue_change_group/item 은 issues 로 FK 가 없다(이력 보존 우선 — V018 주석) — issue_key 로 직접 정리.
                // issue_attachments 는 issue_id ON DELETE CASCADE 라 아래 issues 삭제로 자동 정리된다.
                stmt.execute(
                    "DELETE FROM issue_change_item WHERE group_id IN " +
                        "(SELECT id FROM issue_change_group WHERE issue_key LIKE '$PROJECT_KEY-%')",
                )
                stmt.execute("DELETE FROM issue_change_group WHERE issue_key LIKE '$PROJECT_KEY-%'")
                stmt.execute("DELETE FROM issues WHERE key LIKE '$PROJECT_KEY-%'")
                stmt.execute("UPDATE projects SET key_sequence = 0 WHERE key = '$PROJECT_KEY'")
                stmt.execute("SELECT pgmq.purge_queue('q_issue_events')")
            }
        }
    }

    // ── S1. 코어 필드 + typeName 매칭 + priority/labels/assignee 반영 ─────────────

    @Test
    fun `S1 코어 필드 cmd - 이슈 생성 성공과 issueKey 반환 및 priority labels assignee typeName 반영`() {
        val cmd =
            IssueImportCommand(
                projectKey = PROJECT_KEY,
                requesterUserId = NORMAL_REQUESTER_ID,
                summary = "S1 코어 필드 임포트 테스트",
                typeName = "bug",
                priority = 1,
                assigneeEmail = BOB_EMAIL,
                labels = listOf("backend", "urgent"),
            )

        val result = issueImportAdapter.importIssue(cmd)

        check(result is IssueImportResult.Success) { "Success 여야 하지만 $result 입니다." }
        assert(result.issueKey.startsWith("$PROJECT_KEY-")) {
            "issueKey 가 $PROJECT_KEY- 로 시작해야 하지만 ${result.issueKey} 입니다."
        }

        val saved = issueRepository.findByKey(com.bts.issue.domain.IssueKey(result.issueKey))
        checkNotNull(saved) { "생성된 이슈를 DB 에서 찾을 수 없습니다: ${result.issueKey}" }
        assert(saved.priority == 1) { "priority 가 1 이어야 하지만 ${saved.priority} 입니다." }
        assert(saved.labels == listOf("backend", "urgent")) {
            "labels 가 [backend, urgent] 여야 하지만 ${saved.labels} 입니다."
        }
        assert(saved.assigneeId?.value == BOB_ID) {
            "assigneeId 가 Bob 이어야 하지만 ${saved.assigneeId?.value} 입니다."
        }
        assert(saved.reporterId.value == NORMAL_REQUESTER_ID) {
            "reporterEmail 미지정이므로 reporterId 가 requesterUserId 여야 하지만 ${saved.reporterId.value} 입니다."
        }

        val typeKey = fetchTypeKey(result.issueKey)
        assert(typeKey == "bug") { "typeName='bug' 매칭이므로 typeKey='bug' 여야 하지만 $typeKey 입니다." }
    }

    // ── S2. reporterEmail 매칭/미매칭 ────────────────────────────────────────────

    @Test
    fun `S2 reporterEmail 매칭 - reporterId가 매칭된 사용자로 설정된다`() {
        val cmd =
            IssueImportCommand(
                projectKey = PROJECT_KEY,
                requesterUserId = NORMAL_REQUESTER_ID,
                summary = "S2 reporter 매칭 테스트",
                reporterEmail = ALICE_EMAIL,
            )

        val result = issueImportAdapter.importIssue(cmd)

        check(result is IssueImportResult.Success) { "Success 여야 하지만 $result 입니다." }
        val saved = issueRepository.findByKey(com.bts.issue.domain.IssueKey(result.issueKey))
        checkNotNull(saved)
        assert(saved.reporterId.value == ALICE_ID) {
            "reporterEmail 매칭이므로 reporterId 가 Alice 여야 하지만 ${saved.reporterId.value} 입니다."
        }
    }

    @Test
    fun `S2 reporterEmail 미매칭 - reporterId가 requesterUserId로 폴백된다`() {
        val cmd =
            IssueImportCommand(
                projectKey = PROJECT_KEY,
                requesterUserId = NORMAL_REQUESTER_ID,
                summary = "S2 reporter 미매칭 테스트",
                reporterEmail = "unknown@example.com",
            )

        val result = issueImportAdapter.importIssue(cmd)

        check(result is IssueImportResult.Success) { "Success 여야 하지만 $result 입니다." }
        val saved = issueRepository.findByKey(com.bts.issue.domain.IssueKey(result.issueKey))
        checkNotNull(saved)
        assert(saved.reporterId.value == NORMAL_REQUESTER_ID) {
            "reporterEmail 미매칭이므로 reporterId 가 requesterUserId 로 폴백돼야 하지만 ${saved.reporterId.value} 입니다."
        }
    }

    // ── S3. assigneeEmail 매칭/미매칭 ────────────────────────────────────────────

    @Test
    fun `S3 assigneeEmail 매칭 - assigneeId가 매칭된 사용자로 설정된다`() {
        val cmd =
            IssueImportCommand(
                projectKey = PROJECT_KEY,
                requesterUserId = NORMAL_REQUESTER_ID,
                summary = "S3 assignee 매칭 테스트",
                assigneeEmail = BOB_EMAIL,
            )

        val result = issueImportAdapter.importIssue(cmd)

        check(result is IssueImportResult.Success) { "Success 여야 하지만 $result 입니다." }
        val saved = issueRepository.findByKey(com.bts.issue.domain.IssueKey(result.issueKey))
        checkNotNull(saved)
        assert(saved.assigneeId?.value == BOB_ID) {
            "assigneeEmail 매칭이므로 assigneeId 가 Bob 이어야 하지만 ${saved.assigneeId?.value} 입니다."
        }
    }

    @Test
    fun `S3 assigneeEmail 미매칭 - assigneeId가 null로 유지된다`() {
        val cmd =
            IssueImportCommand(
                projectKey = PROJECT_KEY,
                requesterUserId = NORMAL_REQUESTER_ID,
                summary = "S3 assignee 미매칭 테스트",
                assigneeEmail = "unknown@example.com",
            )

        val result = issueImportAdapter.importIssue(cmd)

        check(result is IssueImportResult.Success) { "Success 여야 하지만 $result 입니다." }
        val saved = issueRepository.findByKey(com.bts.issue.domain.IssueKey(result.issueKey))
        checkNotNull(saved)
        assert(saved.assigneeId == null) {
            "assigneeEmail 미매칭이므로 assigneeId 가 null 이어야 하지만 ${saved.assigneeId?.value} 입니다."
        }
    }

    // ── S4. typeName 미매칭 → Task 폴백 + warning ────────────────────────────────

    @Test
    fun `S4 typeName 미매칭 - Task 타입으로 폴백하고 warning을 남긴다`() {
        val cmd =
            IssueImportCommand(
                projectKey = PROJECT_KEY,
                requesterUserId = NORMAL_REQUESTER_ID,
                summary = "S4 typeName 미매칭 테스트",
                typeName = "Improvement",
            )

        val result = issueImportAdapter.importIssue(cmd)

        check(result is IssueImportResult.Success) { "Success 여야 하지만 $result 입니다." }
        assert(result.warnings.isNotEmpty()) { "typeName 미매칭 warning 이 있어야 하지만 비어 있습니다." }

        val typeKey = fetchTypeKey(result.issueKey)
        assert(typeKey == "task") { "typeName 미매칭이므로 Task 로 폴백돼야 하지만 typeKey=$typeKey 입니다." }
    }

    // ── S5. componentNames 일부 미발견 → 매칭분만 연결 + warning ──────────────────

    @Test
    fun `S5 componentNames 일부 미발견 - 매칭된 컴포넌트만 연결하고 미발견 이름은 warning으로 남긴다`() {
        val cmd =
            IssueImportCommand(
                projectKey = PROJECT_KEY,
                requesterUserId = NORMAL_REQUESTER_ID,
                summary = "S5 component 매칭 테스트",
                componentNames = listOf("backend", "Nonexistent"),
            )

        val result = issueImportAdapter.importIssue(cmd)

        check(result is IssueImportResult.Success) { "Success 여야 하지만 $result 입니다." }
        assert(result.warnings.any { it.contains("Nonexistent") }) {
            "미발견 컴포넌트 이름에 대한 warning 이 있어야 하지만 ${result.warnings} 입니다."
        }

        val connectedComponentIds = fetchComponentIds(result.issueKey)
        assert(connectedComponentIds == setOf(backendComponentId)) {
            "매칭된 backend 컴포넌트만 연결돼야 하지만 $connectedComponentIds 입니다."
        }
    }

    // ── S6. CREATE_ISSUE 권한 없는 requester → Failure(FORBIDDEN), 이슈 미생성 ────

    @Test
    fun `S6 CREATE_ISSUE 권한 없는 requester - Failure FORBIDDEN 이고 이슈가 생성되지 않는다`() {
        val cmd =
            IssueImportCommand(
                projectKey = PROJECT_KEY,
                requesterUserId = FORBIDDEN_REQUESTER_ID,
                summary = "S6 권한 없음 테스트",
            )

        val result = issueImportAdapter.importIssue(cmd)

        check(result is IssueImportResult.Failure) { "Failure 여야 하지만 $result 입니다." }
        assert(result.reasonCode == IssueImportResult.FORBIDDEN) {
            "reasonCode 가 FORBIDDEN 이어야 하지만 ${result.reasonCode} 입니다."
        }
        assert(countImportIssues() == 0) { "권한 거부 시 이슈가 생성되지 않아야 하지만 ${countImportIssues()} 개 존재합니다." }
    }

    // ── S7. dryRun=true → 이슈/이벤트 미생성, 검증 결과만 반환 ───────────────────

    @Test
    fun `S7 dryRun true - 이슈를 생성하지 않고 검증 결과만 반환한다`() {
        val cmd =
            IssueImportCommand(
                projectKey = PROJECT_KEY,
                requesterUserId = NORMAL_REQUESTER_ID,
                summary = "S7 dryRun 테스트",
                priority = 2,
                dryRun = true,
            )

        val result = issueImportAdapter.importIssue(cmd)

        check(result is IssueImportResult.Success) { "Success 여야 하지만 $result 입니다." }
        assert(result.issueKey == IssueImportAdapter.DRY_RUN_MARKER) {
            "dryRun 은 마커 값을 반환해야 하지만 ${result.issueKey} 입니다."
        }
        assert(countImportIssues() == 0) { "dryRun 은 이슈를 생성하지 않아야 하지만 ${countImportIssues()} 개 존재합니다." }
        assert(fetchKeySequence() == 0L) { "dryRun 은 key_sequence 를 증가시키지 않아야 하지만 ${fetchKeySequence()} 입니다." }
    }

    // ── S8(CONCERN #1). update 강제 실패 → 이슈 롤백 + IssueCreated 이벤트 미발행 ──

    /**
     * 행 원자성 — create 는 성공하지만 후속 updateIssue(priority 범위 밖=99)가
     * IllegalArgumentException 을 던지는 경우, 트랜잭션 전체가 롤백돼야 한다.
     *
     * Given priority=99(1..5 범위 밖)
     * When  importIssue 호출
     * Then  Failure(VALIDATION) 반환
     * And   이슈가 DB 에 남아있지 않음(create 롤백)
     * And   q_issue_events 큐에 IssueCreated 메시지가 없음(pgmq.send 도 같은 트랜잭션이라 함께 롤백)
     */
    @Test
    fun `S8 update 강제 실패시 이슈가 롤백되고 IssueCreated 이벤트가 발행되지 않는다`() {
        val cmd =
            IssueImportCommand(
                projectKey = PROJECT_KEY,
                requesterUserId = NORMAL_REQUESTER_ID,
                summary = "S8 행원자성 테스트",
                priority = 99,
            )

        val result = issueImportAdapter.importIssue(cmd)

        check(result is IssueImportResult.Failure) { "Failure 여야 하지만 $result 입니다." }
        assert(result.reasonCode == IssueImportResult.VALIDATION) {
            "reasonCode 가 VALIDATION 이어야 하지만 ${result.reasonCode} 입니다."
        }
        assert(countImportIssues() == 0) {
            "update 실패 시 create 까지 롤백되어 이슈가 없어야 하지만 ${countImportIssues()} 개 존재합니다."
        }
        assert(readIssueEventsQueue().isEmpty()) {
            "롤백된 트랜잭션의 IssueCreated 이벤트는 q_issue_events 에 존재하지 않아야 합니다."
        }
    }

    // ── S9(C1). dryRun — update-유발 행은 UPDATE 권한도 미러 예측 ────────────────

    /**
     * priority 지정 행은 실제 경로(executeImport)에서 updateIssue(IssuePermission.UPDATE)를
     * 호출한다. UPDATE 권한이 없는 [UPDATE_DENIED_REQUESTER_ID] 로 dryRun 하면 실제 실행과
     * 동일하게 FORBIDDEN 이어야 한다(수정 전에는 CREATE 만 확인해 Success 를 오보 — RED).
     */
    @Test
    fun `S9 dryRun priority 지정 - UPDATE 권한 없는 actor는 FORBIDDEN을 반환한다`() {
        val cmd =
            IssueImportCommand(
                projectKey = PROJECT_KEY,
                requesterUserId = UPDATE_DENIED_REQUESTER_ID,
                summary = "S9 update 권한 없음 - priority",
                priority = 2,
                dryRun = true,
            )

        val result = issueImportAdapter.importIssue(cmd)

        check(result is IssueImportResult.Failure) { "Failure 여야 하지만 $result 입니다." }
        assert(result.reasonCode == IssueImportResult.FORBIDDEN) {
            "reasonCode 가 FORBIDDEN 이어야 하지만 ${result.reasonCode} 입니다."
        }
        assert(countImportIssues() == 0) { "dryRun 은 이슈를 생성하지 않아야 하지만 ${countImportIssues()} 개 존재합니다." }
    }

    /**
     * labels 지정 행도 priority 와 동일하게 updateIssue 경로를 유발하므로 UPDATE 권한 없는
     * actor 는 dryRun 에서도 FORBIDDEN 이어야 한다.
     */
    @Test
    fun `S9 dryRun labels 지정 - UPDATE 권한 없는 actor는 FORBIDDEN을 반환한다`() {
        val cmd =
            IssueImportCommand(
                projectKey = PROJECT_KEY,
                requesterUserId = UPDATE_DENIED_REQUESTER_ID,
                summary = "S9 update 권한 없음 - labels",
                labels = listOf("urgent"),
                dryRun = true,
            )

        val result = issueImportAdapter.importIssue(cmd)

        check(result is IssueImportResult.Failure) { "Failure 여야 하지만 $result 입니다." }
        assert(result.reasonCode == IssueImportResult.FORBIDDEN) {
            "reasonCode 가 FORBIDDEN 이어야 하지만 ${result.reasonCode} 입니다."
        }
    }

    /**
     * assigneeEmail 매칭 행은 실제 경로에서 changeAssignee(IssuePermission.UPDATE)를 호출한다.
     * UPDATE 권한 없는 actor 는 dryRun 에서도 FORBIDDEN 이어야 한다.
     */
    @Test
    fun `S9 dryRun assignee 매칭 - UPDATE 권한 없는 actor는 FORBIDDEN을 반환한다`() {
        val cmd =
            IssueImportCommand(
                projectKey = PROJECT_KEY,
                requesterUserId = UPDATE_DENIED_REQUESTER_ID,
                summary = "S9 update 권한 없음 - assignee",
                assigneeEmail = BOB_EMAIL,
                dryRun = true,
            )

        val result = issueImportAdapter.importIssue(cmd)

        check(result is IssueImportResult.Failure) { "Failure 여야 하지만 $result 입니다." }
        assert(result.reasonCode == IssueImportResult.FORBIDDEN) {
            "reasonCode 가 FORBIDDEN 이어야 하지만 ${result.reasonCode} 입니다."
        }
    }

    /**
     * assigneeEmail 이 미매칭이면 실제 경로에서 changeAssignee 를 호출하지 않으므로(resolution.assigneeId
     * == null) UPDATE 를 유발하지 않는다. UPDATE 권한이 없어도 Success 여야 한다.
     */
    @Test
    fun `S9 dryRun assignee 미매칭 - UPDATE 권한 없어도 Success이다`() {
        val cmd =
            IssueImportCommand(
                projectKey = PROJECT_KEY,
                requesterUserId = UPDATE_DENIED_REQUESTER_ID,
                summary = "S9 update 권한 없음 - assignee 미매칭",
                assigneeEmail = "unknown@example.com",
                dryRun = true,
            )

        val result = issueImportAdapter.importIssue(cmd)

        check(result is IssueImportResult.Success) { "Success 여야 하지만 $result 입니다." }
        assert(result.issueKey == IssueImportAdapter.DRY_RUN_MARKER)
    }

    /**
     * summary/description 만 있는 행(priority/labels/assignee 모두 없음)은 실제 경로에서
     * createIssue 만 호출하고 updateIssue/changeAssignee 는 유발하지 않는다. UPDATE 권한이
     * 없어도 CREATE 만으로 Success 여야 한다(실제 경로와 일치).
     */
    @Test
    fun `S9 dryRun summary description만 - UPDATE 미유발 행은 UPDATE 권한 없어도 Success이다`() {
        val cmd =
            IssueImportCommand(
                projectKey = PROJECT_KEY,
                requesterUserId = UPDATE_DENIED_REQUESTER_ID,
                summary = "S9 update 권한 없음 - update 미유발",
                description = "본문만 있는 행",
                dryRun = true,
            )

        val result = issueImportAdapter.importIssue(cmd)

        check(result is IssueImportResult.Success) { "Success 여야 하지만 $result 입니다." }
        assert(result.issueKey == IssueImportAdapter.DRY_RUN_MARKER) {
            "dryRun 은 마커 값을 반환해야 하지만 ${result.issueKey} 입니다."
        }
        assert(countImportIssues() == 0) { "dryRun 은 이슈를 생성하지 않아야 하지만 ${countImportIssues()} 개 존재합니다." }
    }

    // ── S10. 컴포넌트 자동생성 — CREATE 권한 있으면 create 후 연결 ──────────────────

    @Test
    fun `S10 컴포넌트 미존재 CREATE 권한 있음 - 자동생성 후 이슈에 연결된다`() {
        val cmd =
            IssueImportCommand(
                projectKey = PROJECT_KEY,
                requesterUserId = AUTO_CREATE_ALLOWED_REQUESTER_ID,
                summary = "S10 컴포넌트 자동생성 테스트",
                componentNames = listOf("AutoComponent"),
            )

        val result = issueImportAdapter.importIssue(cmd)

        check(result is IssueImportResult.Success) { "Success 여야 하지만 $result 입니다." }
        val createdComponentId = findComponentIdOrNull(PROJECT_KEY, "AutoComponent")
        checkNotNull(createdComponentId) { "CREATE 권한이 있으므로 컴포넌트가 자동생성돼야 합니다." }
        assert(fetchComponentIds(result.issueKey) == setOf(createdComponentId)) {
            "자동생성된 컴포넌트가 이슈에 연결돼야 하지만 ${fetchComponentIds(result.issueKey)} 입니다."
        }
    }

    // ── S11. 컴포넌트 자동생성 — CREATE 권한 없으면 create 미호출 + 경고 ───────────

    @Test
    fun `S11 컴포넌트 미존재 CREATE 권한 없음 - create 미호출 경고만 남기고 이슈는 생성된다`() {
        val cmd =
            IssueImportCommand(
                projectKey = PROJECT_KEY,
                requesterUserId = NORMAL_REQUESTER_ID,
                summary = "S11 컴포넌트 자동생성 권한없음 테스트",
                componentNames = listOf("NoPermComponent"),
            )

        val result = issueImportAdapter.importIssue(cmd)

        check(result is IssueImportResult.Success) { "이슈 생성 자체는 성공해야 하지만 $result 입니다." }
        assert(result.warnings.any { it.contains("NoPermComponent") }) {
            "생성 권한 없음 경고가 있어야 하지만 ${result.warnings} 입니다."
        }
        assert(findComponentIdOrNull(PROJECT_KEY, "NoPermComponent") == null) {
            "CREATE 권한이 없으므로 컴포넌트가 생성되지 않아야 합니다."
        }
        assert(fetchComponentIds(result.issueKey).isEmpty()) {
            "연결된 컴포넌트가 없어야 하지만 ${fetchComponentIds(result.issueKey)} 입니다."
        }
    }

    // ── S12. 버전 자동생성 — CREATE 권한 있으면 create 후 fix/affects 연결 ─────────

    @Test
    fun `S12 fix affects 버전 미존재 CREATE 권한 있음 - 자동생성 후 각각 연결된다`() {
        val cmd =
            IssueImportCommand(
                projectKey = PROJECT_KEY,
                requesterUserId = AUTO_CREATE_ALLOWED_REQUESTER_ID,
                summary = "S12 버전 자동생성 테스트",
                fixVersionNames = listOf("AutoFixVersion"),
                affectsVersionNames = listOf("AutoAffectsVersion"),
            )

        val result = issueImportAdapter.importIssue(cmd)

        check(result is IssueImportResult.Success) { "Success 여야 하지만 $result 입니다." }
        val fixVersionId = findVersionIdOrNull(PROJECT_KEY, "AutoFixVersion")
        val affectsVersionId = findVersionIdOrNull(PROJECT_KEY, "AutoAffectsVersion")
        checkNotNull(fixVersionId) { "CREATE 권한이 있으므로 fix 버전이 자동생성돼야 합니다." }
        checkNotNull(affectsVersionId) { "CREATE 권한이 있으므로 affects 버전이 자동생성돼야 합니다." }
        assert(fetchFixVersionIds(result.issueKey) == setOf(fixVersionId)) {
            "자동생성된 fix 버전이 연결돼야 하지만 ${fetchFixVersionIds(result.issueKey)} 입니다."
        }
        assert(fetchAffectsVersionIds(result.issueKey) == setOf(affectsVersionId)) {
            "자동생성된 affects 버전이 연결돼야 하지만 ${fetchAffectsVersionIds(result.issueKey)} 입니다."
        }
    }

    // ── S13. 버전 자동생성 — CREATE 권한 없으면 create 미호출 + 경고 ───────────────

    @Test
    fun `S13 fix 버전 미존재 CREATE 권한 없음 - create 미호출 경고만 남기고 이슈는 생성된다`() {
        val cmd =
            IssueImportCommand(
                projectKey = PROJECT_KEY,
                requesterUserId = NORMAL_REQUESTER_ID,
                summary = "S13 버전 자동생성 권한없음 테스트",
                fixVersionNames = listOf("NoPermVersion"),
            )

        val result = issueImportAdapter.importIssue(cmd)

        check(result is IssueImportResult.Success) { "이슈 생성 자체는 성공해야 하지만 $result 입니다." }
        assert(result.warnings.any { it.contains("NoPermVersion") }) {
            "생성 권한 없음 경고가 있어야 하지만 ${result.warnings} 입니다."
        }
        assert(findVersionIdOrNull(PROJECT_KEY, "NoPermVersion") == null) {
            "CREATE 권한이 없으므로 버전이 생성되지 않아야 합니다."
        }
        assert(fetchFixVersionIds(result.issueKey).isEmpty()) {
            "연결된 fix 버전이 없어야 하지만 ${fetchFixVersionIds(result.issueKey)} 입니다."
        }
    }

    // ── S14~S16. statusName 반영 — Applied/NoMatch/NoPermission (best-effort) ────

    @Test
    fun `S14 statusName 매칭 - 상태가 direct-set 되고 이슈가 새 상태로 저장된다`() {
        val cmd =
            IssueImportCommand(
                projectKey = PROJECT_KEY,
                requesterUserId = NORMAL_REQUESTER_ID,
                summary = "S14 상태 반영 테스트",
                statusName = "In Progress",
            )

        val result = issueImportAdapter.importIssue(cmd)

        check(result is IssueImportResult.Success) { "Success 여야 하지만 $result 입니다." }
        val saved = issueRepository.findByKey(com.bts.issue.domain.IssueKey(result.issueKey))
        checkNotNull(saved)
        assert(saved.currentStateKey == "in_progress") {
            "statusName 매칭이므로 in_progress 로 direct-set 돼야 하지만 ${saved.currentStateKey} 입니다."
        }
    }

    @Test
    fun `S15 statusName 미매칭 - 경고를 남기고 시작 상태를 유지한다`() {
        val cmd =
            IssueImportCommand(
                projectKey = PROJECT_KEY,
                requesterUserId = NORMAL_REQUESTER_ID,
                summary = "S15 상태 미매칭 테스트",
                statusName = "Nonexistent Status",
            )

        val result = issueImportAdapter.importIssue(cmd)

        check(result is IssueImportResult.Success) { "Success 여야 하지만 $result 입니다." }
        assert(result.warnings.any { it.contains("Nonexistent Status") }) {
            "상태 미매칭 경고가 있어야 하지만 ${result.warnings} 입니다."
        }
        val saved = issueRepository.findByKey(com.bts.issue.domain.IssueKey(result.issueKey))
        checkNotNull(saved)
        assert(saved.currentStateKey == "open") {
            "미매칭이므로 시작 상태(open)를 유지해야 하지만 ${saved.currentStateKey} 입니다."
        }
    }

    @Test
    fun `S16 statusName 지정 - TRANSITION 권한 없으면 경고를 남기고 시작 상태를 유지한다`() {
        val cmd =
            IssueImportCommand(
                projectKey = PROJECT_KEY,
                requesterUserId = TRANSITION_DENIED_REQUESTER_ID,
                summary = "S16 상태 권한없음 테스트",
                statusName = "In Progress",
            )

        val result = issueImportAdapter.importIssue(cmd)

        check(result is IssueImportResult.Success) { "Success 여야 하지만 $result 입니다." }
        assert(result.warnings.isNotEmpty()) { "TRANSITION 권한없음 경고가 있어야 하지만 비어 있습니다." }
        val saved = issueRepository.findByKey(com.bts.issue.domain.IssueKey(result.issueKey))
        checkNotNull(saved)
        assert(saved.currentStateKey == "open") {
            "TRANSITION 권한이 없으므로 시작 상태(open)를 유지해야 하지만 ${saved.currentStateKey} 입니다."
        }
    }

    // ── S17~S20(C1 확장). dryRun — 컴포넌트/버전 자동생성·링크 미러 ────────────────

    /**
     * dryRun 은 CREATE 권한이 있어도 실제 [com.bts.issue.component.application.ComponentApplicationService.create]
     * 를 호출하지 않는다 — 권한 확인만 수행한다(★ 최우선 제약).
     */
    @Test
    fun `S17 dryRun 컴포넌트 미존재 CREATE 권한 있음 - 권한만 확인하고 실제 생성은 하지 않는다`() {
        val cmd =
            IssueImportCommand(
                projectKey = PROJECT_KEY,
                requesterUserId = AUTO_CREATE_ALLOWED_REQUESTER_ID,
                summary = "S17 dryRun 컴포넌트 미생성 테스트",
                componentNames = listOf("DryRunComponent"),
                dryRun = true,
            )

        val result = issueImportAdapter.importIssue(cmd)

        check(result is IssueImportResult.Success) { "Success 여야 하지만 $result 입니다." }
        assert(findComponentIdOrNull(PROJECT_KEY, "DryRunComponent") == null) {
            "dryRun 은 실제 컴포넌트를 생성하지 않아야 합니다."
        }
    }

    /**
     * fix/affects 버전 링크는 [com.bts.issue.application.IssueApplicationService.changeFixVersions]/
     * [com.bts.issue.application.IssueApplicationService.changeAffectsVersions] 를 통해 UPDATE 권한을
     * 요구한다(§UPDATE 미러 확장, CONCERN C1). 이미 존재하는 버전 이름을 사용해 CREATE 권한과
     * 무관하게(매칭이라 자동생성 불필요) UPDATE 권한 부재만 분리 검증한다.
     */
    @Test
    fun `S18 dryRun fixVersionNames 기존 버전 매칭 - UPDATE 권한 없는 actor는 FORBIDDEN을 반환한다`() {
        val cmd =
            IssueImportCommand(
                projectKey = PROJECT_KEY,
                requesterUserId = UPDATE_DENIED_REQUESTER_ID,
                summary = "S18 dryRun 버전링크 UPDATE 권한없음 테스트",
                fixVersionNames = listOf(EXISTING_VERSION_NAME),
                dryRun = true,
            )

        val result = issueImportAdapter.importIssue(cmd)

        check(result is IssueImportResult.Failure) { "Failure 여야 하지만 $result 입니다." }
        assert(result.reasonCode == IssueImportResult.FORBIDDEN) {
            "reasonCode 가 FORBIDDEN 이어야 하지만 ${result.reasonCode} 입니다."
        }
    }

    /**
     * 미매칭+CREATE 권한 있는 버전 이름도(실제 생성 없이) 센티널로 대체돼 "링크를 유발한다" 는
     * 판정에 반영된다 — UPDATE 권한까지 있으면 FORBIDDEN 으로 잘못 예측하지 않고 Success 여야 한다.
     * dryRun 이므로 실제 [com.bts.issue.version.application.VersionApplicationService.create] 는
     * 호출되지 않는다.
     */
    @Test
    fun `S19 dryRun fixVersionNames 미존재 CREATE UPDATE 권한 모두 있음 - Success이고 실제 생성은 없다`() {
        val cmd =
            IssueImportCommand(
                projectKey = PROJECT_KEY,
                requesterUserId = AUTO_CREATE_ALLOWED_REQUESTER_ID,
                summary = "S19 dryRun 버전링크 권한있음 테스트",
                fixVersionNames = listOf("DryRunSentinelVersion"),
                dryRun = true,
            )

        val result = issueImportAdapter.importIssue(cmd)

        check(result is IssueImportResult.Success) { "Success 여야 하지만 $result 입니다." }
        assert(findVersionIdOrNull(PROJECT_KEY, "DryRunSentinelVersion") == null) {
            "dryRun 은 실제 버전을 생성하지 않아야 합니다."
        }
    }

    /**
     * statusName 반영은 [com.bts.issue.application.IssueImportStatusService] 가 best-effort 로
     * 처리하므로(권한 없어도 예외 없음) TRANSITION 권한 미리보기도 FORBIDDEN 이 아닌 경고로만
     * 남는다 — dryRun 도 Success 를 유지한다.
     */
    @Test
    fun `S20 dryRun statusName 지정 - TRANSITION 권한 없어도 Success이고 경고를 남긴다`() {
        val cmd =
            IssueImportCommand(
                projectKey = PROJECT_KEY,
                requesterUserId = TRANSITION_DENIED_REQUESTER_ID,
                summary = "S20 dryRun 상태 권한없음 테스트",
                statusName = "In Progress",
                dryRun = true,
            )

        val result = issueImportAdapter.importIssue(cmd)

        check(result is IssueImportResult.Success) { "Success 여야 하지만 $result 입니다." }
        assert(result.warnings.isNotEmpty()) { "TRANSITION 권한없음 경고가 있어야 하지만 비어 있습니다." }
    }

    // ── S21. 자동생성 tx 원자성 — 컴포넌트 auto-create 후 행 실패 시 컴포넌트도 롤백(고아 0) ──

    /**
     * BLOCKER(tx 오염) 검증 확장(T6 ⑤) — 컴포넌트 자동생성은 행 트랜잭션에 참여하므로, 자동생성
     * 뒤 후속 updateIssue(priority 범위 밖=99)가 실패하면 이슈뿐 아니라 **자동생성된 컴포넌트까지
     * 함께 롤백**돼야 한다(고아 0). 권한이 있는 경로(create 실제 호출)에서도 정상 실패가 tx 오염
     * 없이 깔끔히 롤백됨을 실증한다 — 사전 체크 설계의 정합성 최종 확인.
     *
     * Given CREATE 권한 있는 requester + 미존재 컴포넌트 "RollbackComponent" + priority=99
     * When  importIssue 호출
     * Then  Failure(VALIDATION)
     * And   이슈 0건(create 롤백)
     * And   "RollbackComponent" 미존재(auto-create 롤백 — 고아 없음)
     */
    @Test
    fun `S21 컴포넌트 자동생성 후 update 실패시 이슈와 자동생성 컴포넌트가 함께 롤백된다`() {
        val cmd =
            IssueImportCommand(
                projectKey = PROJECT_KEY,
                requesterUserId = AUTO_CREATE_ALLOWED_REQUESTER_ID,
                summary = "S21 자동생성 롤백 테스트",
                componentNames = listOf("RollbackComponent"),
                priority = 99,
            )

        val result = issueImportAdapter.importIssue(cmd)

        check(result is IssueImportResult.Failure) { "Failure 여야 하지만 $result 입니다." }
        assert(result.reasonCode == IssueImportResult.VALIDATION) {
            "reasonCode 가 VALIDATION 이어야 하지만 ${result.reasonCode} 입니다."
        }
        assert(countImportIssues() == 0) {
            "update 실패 시 이슈가 롤백돼야 하지만 ${countImportIssues()} 개 존재합니다."
        }
        assert(findComponentIdOrNull(PROJECT_KEY, "RollbackComponent") == null) {
            "행 실패 시 자동생성된 컴포넌트도 롤백돼 고아가 없어야 합니다."
        }
    }

    // ── S22. dry-run 상태 name 미매칭 미리보기 (CONCERN-A) ──────────────────────

    /**
     * dry-run 경고 미러 갭 수정(CONCERN-A) — TRANSITION 권한이 있어도 statusName 이 대상 워크플로우
     * 상태 목록(Open/In Progress/Done)에 없으면, 실제 실행의 NoMatch 경고를 dry-run 에서도 미리
     * 산출해야 한다. 권한 경고(S20)만이 아니라 name 미매칭도 미리보기로 노출한다.
     *
     * Given TRANSITION 권한 있는 requester(NORMAL) + 미존재 statusName "Frozen"
     * When  dryRun importIssue 호출
     * Then  Success (best-effort — 행 유효성엔 영향 없음)
     * And   "찾을 수 없어" 경고 포함 (실행 경로 NoMatch 미러)
     */
    @Test
    fun `S22 dryRun statusName 미매칭 - TRANSITION 권한 있어도 미매칭 경고를 미리 남긴다`() {
        val cmd =
            IssueImportCommand(
                projectKey = PROJECT_KEY,
                requesterUserId = NORMAL_REQUESTER_ID,
                summary = "S22 dryRun 상태 미매칭 미리보기 테스트",
                statusName = "Frozen",
                dryRun = true,
            )

        val result = issueImportAdapter.importIssue(cmd)

        check(result is IssueImportResult.Success) { "Success 여야 하지만 $result 입니다." }
        assert(result.warnings.any { it.contains("Frozen") && it.contains("찾을 수 없어") }) {
            "미매칭 상태 경고가 있어야 하지만 경고 목록은 ${result.warnings} 입니다."
        }
    }

    // ── S23. 한 셀 내 중복 컴포넌트 이름 de-dup (NIT-1) ─────────────────────────

    /**
     * NIT-1 수정 — Jira 다중값 셀이 같은 이름을 중복 포함("Dup","Dup")하면, de-dup 없이는 미매칭
     * 이름을 같은 트랜잭션 안에서 두 번 생성 시도해 partial-unique 23505 로 행 전체가 실패한다.
     * de-dup(distinct) 후에는 한 번만 생성돼 정상 링크된다.
     *
     * Given CREATE 권한 있는 requester + 미존재 컴포넌트 이름 중복 ["Dup", "Dup"]
     * When  importIssue 호출
     * Then  Success (23505 행 실패 없음)
     * And   컴포넌트 "Dup" 1건 생성 + 이슈에 1개 링크
     */
    @Test
    fun `S23 한 셀 내 중복 컴포넌트 이름은 de-dup 되어 한 번만 자동생성된다`() {
        val cmd =
            IssueImportCommand(
                projectKey = PROJECT_KEY,
                requesterUserId = AUTO_CREATE_ALLOWED_REQUESTER_ID,
                summary = "S23 중복 컴포넌트 이름 de-dup 테스트",
                componentNames = listOf("Dup", "Dup"),
            )

        val result = issueImportAdapter.importIssue(cmd)

        check(result is IssueImportResult.Success) { "Success 여야 하지만 $result 입니다(중복 이름이 23505 로 실패하면 안 됨)." }
        assert(findComponentIdOrNull(PROJECT_KEY, "Dup") != null) {
            "컴포넌트 'Dup' 이 자동생성돼 있어야 합니다."
        }
        assert(fetchComponentIds(result.issueKey).size == 1) {
            "중복 이름은 하나로 링크돼야 하지만 ${fetchComponentIds(result.issueKey).size} 개 링크됐습니다."
        }
    }

    // ── S24~S31(PR3, Task 7). 댓글/worklog 동반 생성 — 권한 사전체크·best-effort 집약·행 원자성 ──

    @Test
    fun `S24 댓글 생성 - author가 보존되고 조회로 확인된다`() {
        val cmd =
            IssueImportCommand(
                projectKey = PROJECT_KEY,
                requesterUserId = NORMAL_REQUESTER_ID,
                summary = "S24 댓글 생성 테스트",
                comments = listOf(ImportComment(body = "원본 댓글", authorEmail = ALICE_EMAIL)),
            )

        val result = issueImportAdapter.importIssue(cmd)

        check(result is IssueImportResult.Success) { "Success 여야 하지만 $result 입니다." }
        val authorIds = fetchCommentAuthorIds(result.issueKey)
        assert(authorIds == listOf(ALICE_ID)) { "댓글 작성자가 Alice 로 보존돼야 하지만 $authorIds 입니다." }
    }

    @Test
    fun `S25 worklog 생성 - author가 보존되고 issues version이 불변한다`() {
        val cmd =
            IssueImportCommand(
                projectKey = PROJECT_KEY,
                requesterUserId = NORMAL_REQUESTER_ID,
                summary = "S25 worklog 생성 테스트",
                worklogs = listOf(ImportWorklog(timeSpentSeconds = 3600, authorEmail = BOB_EMAIL)),
            )

        val result = issueImportAdapter.importIssue(cmd)

        check(result is IssueImportResult.Success) { "Success 여야 하지만 $result 입니다." }
        val authorIds = fetchWorklogAuthorIds(result.issueKey)
        assert(authorIds == listOf(BOB_ID)) { "worklog 작성자가 Bob 이어야 하지만 $authorIds 입니다." }
        val saved = issueRepository.findByKey(IssueKey(result.issueKey))
        checkNotNull(saved)
        assert(saved.version == 1L) {
            "worklog 롤업은 issues.version 을 증가시키지 않아야(no-bump) 하지만 ${saved.version} 입니다."
        }
    }

    @Test
    fun `S26 UPDATE 권한 없음 - 댓글 worklog 모두 스킵되고 집약 경고를 남기며 이슈는 생성된다`() {
        val cmd =
            IssueImportCommand(
                projectKey = PROJECT_KEY,
                requesterUserId = UPDATE_DENIED_REQUESTER_ID,
                summary = "S26 댓글 worklog 권한없음 테스트",
                comments = listOf(ImportComment(body = "댓글1"), ImportComment(body = "댓글2")),
                worklogs = listOf(ImportWorklog(timeSpentSeconds = 60)),
            )

        val result = issueImportAdapter.importIssue(cmd)

        check(result is IssueImportResult.Success) { "이슈 생성 자체는 성공해야 하지만 $result 입니다." }
        assert(result.warnings.any { it.contains("댓글") && it.contains("2") }) {
            "댓글 집약 경고가 있어야 하지만 ${result.warnings} 입니다."
        }
        assert(result.warnings.any { it.contains("워크로그") && it.contains("1") }) {
            "워크로그 집약 경고가 있어야 하지만 ${result.warnings} 입니다."
        }
        assert(fetchCommentAuthorIds(result.issueKey).isEmpty()) { "권한 없으면 댓글이 생성되지 않아야 합니다." }
        assert(fetchWorklogAuthorIds(result.issueKey).isEmpty()) { "권한 없으면 worklog 이 생성되지 않아야 합니다." }
    }

    @Test
    fun `S27 worklog timeSpent 0 이하 - 스킵되고 경고를 남기며 CHECK 위반 없이 이슈는 생성된다`() {
        val cmd =
            IssueImportCommand(
                projectKey = PROJECT_KEY,
                requesterUserId = NORMAL_REQUESTER_ID,
                summary = "S27 worklog timeSpent 0 이하 테스트",
                worklogs = listOf(ImportWorklog(timeSpentSeconds = 0), ImportWorklog(timeSpentSeconds = -10)),
            )

        val result = issueImportAdapter.importIssue(cmd)

        check(result is IssueImportResult.Success) { "Success 여야 하지만 $result 입니다(23514 로 행 실패하면 안 됨)." }
        assert(result.warnings.any { it.contains("워크로그") && it.contains("2") }) {
            "timeSpent 경고가 있어야 하지만 ${result.warnings} 입니다."
        }
        assert(fetchWorklogAuthorIds(result.issueKey).isEmpty()) { "timeSpent 0 이하는 생성되지 않아야 합니다." }
    }

    @Test
    fun `S28 댓글 authorEmail 미매칭 - requester로 폴백되고 경고를 남긴다`() {
        val cmd =
            IssueImportCommand(
                projectKey = PROJECT_KEY,
                requesterUserId = NORMAL_REQUESTER_ID,
                summary = "S28 댓글 author 미매칭 테스트",
                comments = listOf(ImportComment(body = "댓글", authorEmail = "unknown@example.com")),
            )

        val result = issueImportAdapter.importIssue(cmd)

        check(result is IssueImportResult.Success) { "Success 여야 하지만 $result 입니다." }
        assert(result.warnings.any { it.contains("댓글") }) { "author 미매칭 경고가 있어야 하지만 ${result.warnings} 입니다." }
        assert(fetchCommentAuthorIds(result.issueKey) == listOf(NORMAL_REQUESTER_ID)) {
            "author 미매칭이므로 requester 로 폴백돼야 하지만 ${fetchCommentAuthorIds(result.issueKey)} 입니다."
        }
    }

    @Test
    fun `S28 worklog authorEmail 미매칭 - requester로 폴백되고 경고를 남긴다`() {
        val cmd =
            IssueImportCommand(
                projectKey = PROJECT_KEY,
                requesterUserId = NORMAL_REQUESTER_ID,
                summary = "S28 worklog author 미매칭 테스트",
                worklogs = listOf(ImportWorklog(timeSpentSeconds = 60, authorEmail = "unknown@example.com")),
            )

        val result = issueImportAdapter.importIssue(cmd)

        check(result is IssueImportResult.Success) { "Success 여야 하지만 $result 입니다." }
        assert(result.warnings.any { it.contains("워크로그") }) { "author 미매칭 경고가 있어야 하지만 ${result.warnings} 입니다." }
        assert(fetchWorklogAuthorIds(result.issueKey) == listOf(NORMAL_REQUESTER_ID)) {
            "author 미매칭이므로 requester 로 폴백돼야 하지만 ${fetchWorklogAuthorIds(result.issueKey)} 입니다."
        }
    }

    @Test
    fun `S29 worklog startedAt 없음 - import 실행 시각으로 대체되고 경고를 남기며 정상 생성된다`() {
        val cmd =
            IssueImportCommand(
                projectKey = PROJECT_KEY,
                requesterUserId = NORMAL_REQUESTER_ID,
                summary = "S29 worklog startedAt 없음 테스트",
                worklogs = listOf(ImportWorklog(timeSpentSeconds = 60, startedAt = null)),
            )

        val result = issueImportAdapter.importIssue(cmd)

        check(result is IssueImportResult.Success) { "Success 여야 하지만 $result 입니다." }
        assert(result.warnings.any { it.contains("시작 시각") }) {
            "startedAt 부재 경고가 있어야 하지만 ${result.warnings} 입니다."
        }
        assert(fetchWorklogAuthorIds(result.issueKey).size == 1) { "startedAt 없어도 worklog 은 생성돼야 합니다." }
    }

    /**
     * dry-run 별도 경고 경로(★C3) — UPDATE 권한 없는 댓글/worklog 는 [rowTriggersUpdate] 하드 FORBIDDEN
     * 판정에 절대 엮이지 않는다(CONCERN-A 재발 방지). 실행 시(S26) best-effort 스킵과 동일하게
     * dry-run 도 경고만 남기고 Success 를 유지해야 한다.
     */
    @Test
    fun `S30 dryRun UPDATE 권한 없는 댓글 worklog - FORBIDDEN 아닌 Success이고 경고만 남기며 insert 없다`() {
        val cmd =
            IssueImportCommand(
                projectKey = PROJECT_KEY,
                requesterUserId = UPDATE_DENIED_REQUESTER_ID,
                summary = "S30 dryRun 댓글 worklog 권한없음 테스트",
                comments = listOf(ImportComment(body = "댓글")),
                worklogs = listOf(ImportWorklog(timeSpentSeconds = 60)),
                dryRun = true,
            )

        val result = issueImportAdapter.importIssue(cmd)

        check(result is IssueImportResult.Success) { "FORBIDDEN 아닌 Success 여야 하지만 $result 입니다." }
        assert(result.warnings.any { it.contains("댓글") }) { "댓글 경고가 있어야 하지만 ${result.warnings} 입니다." }
        assert(result.warnings.any { it.contains("워크로그") }) { "워크로그 경고가 있어야 하지만 ${result.warnings} 입니다." }
        assert(countImportIssues() == 0) { "dryRun 은 이슈를 생성하지 않아야 하지만 ${countImportIssues()} 개 존재합니다." }
    }

    /**
     * 행 원자성 안전망 실증(★2) — 사전체크가 커버하지 않는 예상외 예외가 worklog 처리 중 발생해도
     * [importIssue] 의 catch 블록이 트랜잭션 전체를 rollback-only 로 표시해 직전 삽입된 이슈까지
     * 함께 롤백해야 한다. [FaultInjectingWorklogService] 로 실제 tx 경계에서 검증한다(mockk 로는
     * 표면화되지 않음 — 메모리 advisory-lock-bigint-toctou 동형 함정).
     */
    @Test
    fun `S31 worklog 처리 중 예상외 throw - 이슈까지 함께 롤백된다`() {
        val cmd =
            IssueImportCommand(
                projectKey = PROJECT_KEY,
                requesterUserId = NORMAL_REQUESTER_ID,
                summary = "S31 예상외 throw 행원자성 테스트",
                worklogs = listOf(ImportWorklog(timeSpentSeconds = 60, comment = UNEXPECTED_THROW_MARKER)),
            )

        val result = issueImportAdapter.importIssue(cmd)

        check(result is IssueImportResult.Failure) { "Failure 여야 하지만 $result 입니다." }
        assert(countImportIssues() == 0) {
            "예상외 throw 시 이슈까지 롤백돼야 하지만 ${countImportIssues()} 개 존재합니다."
        }
    }

    /**
     * S1/R7/E5 결함 수정 검증(PR3, Task 8) — [ImportComment.createdAt] 이 저장 단계에서 버려지지 않고
     * 보존되며, `created_at` ASC 정렬이 import 시각이 아닌 원본 시각 기준으로 동작함을 확인한다.
     *
     * list 순서를 원본 시각 역순(나중 댓글 먼저)으로 넣어, 정렬이 insertion 순서가 아니라 DB
     * `created_at` 기준임을 실증한다.
     */
    @Test
    fun `S32 댓글 createdAt - 원본 시각이 보존되고 created_at ASC 로 정렬된다`() {
        val earlier = Instant.parse("2019-01-01T09:00:00Z")
        val later = Instant.parse("2019-06-01T09:00:00Z")
        val cmd =
            IssueImportCommand(
                projectKey = PROJECT_KEY,
                requesterUserId = NORMAL_REQUESTER_ID,
                summary = "S32 댓글 createdAt 보존 테스트",
                comments =
                    listOf(
                        ImportComment(body = "나중 댓글", createdAt = later),
                        ImportComment(body = "먼저 댓글", createdAt = earlier),
                    ),
            )

        val result = issueImportAdapter.importIssue(cmd)

        check(result is IssueImportResult.Success) { "Success 여야 하지만 $result 입니다." }
        val createdAts = fetchCommentCreatedAts(result.issueKey)
        assert(createdAts == listOf(earlier, later)) {
            "댓글 createdAt 이 원본 시각 보존 + created_at ASC 로 정렬돼야 하지만 " +
                "$createdAts 입니다(import 실행 시각으로 뭉치면 안 됨)."
        }
    }

    /**
     * dry-run/실행 미러 정합(코드리뷰 CONCERN C-1) — [applyWorklogItem] 은 timeSpentSeconds≤0 인
     * worklog 를 생성 전에 스킵하며, 그 항목은 unmatchedAuthor/missingStartedAt 을 애초에 판정하지
     * 않는다([WorklogApplyOutcome] KDoc — invalidTimeSpent=true 인 항목은 항상 unmatchedAuthor/
     * missingStartedAt=false). 따라서 dry-run 미리보기([warnWorklogsPreview])도 생성될 항목
     * (timeSpent>0)만 unmatched/missingStartedAt 을 집계해야 한다 — 그렇지 않으면 "생성되지도 않을
     * 항목"에 대해 dry-run 이 실행보다 과다 경고를 남긴다(S27/S28/S29 실행부와 대조).
     */
    @Test
    fun `S33 dryRun worklog timeSpent 0 이하 + author 미매칭 + startedAt 없음 - 실행부 스킵과 정합해 timeSpent 경고만 남는다`() {
        val cmd =
            IssueImportCommand(
                projectKey = PROJECT_KEY,
                requesterUserId = NORMAL_REQUESTER_ID,
                summary = "S33 dryRun worklog 스킵 정합 테스트",
                worklogs =
                    listOf(
                        ImportWorklog(
                            timeSpentSeconds = 0,
                            authorEmail = "unknown@example.com",
                            startedAt = null,
                        ),
                    ),
                dryRun = true,
            )

        val result = issueImportAdapter.importIssue(cmd)

        check(result is IssueImportResult.Success) { "Success 여야 하지만 $result 입니다." }
        assert(result.warnings.any { it.contains("소요 시간이 0 이하") }) {
            "timeSpent 경고가 있어야 하지만 ${result.warnings} 입니다."
        }
        assert(result.warnings.none { it.contains("작성자 이메일이 매칭되지 않아") }) {
            "실행부는 timeSpent≤0 항목을 생성 전 스킵해 author 미매칭을 판정하지 않으므로 dry-run 도 " +
                "author 경고를 남기면 안 되지만 ${result.warnings} 입니다."
        }
        assert(result.warnings.none { it.contains("시작 시각이 없어") }) {
            "실행부는 timeSpent≤0 항목을 생성 전 스킵해 startedAt 을 판정하지 않으므로 dry-run 도 " +
                "startedAt 경고를 남기면 안 되지만 ${result.warnings} 입니다."
        }
    }

    // ── S34~S44(PR4, Task 9). 첨부 upload·이력 recordImported — 사전체크·best-effort 집약·행 원자성 ──
    // 각 제목의 (Task9-Sx) 는 plan Task 9 명세의 시나리오 번호(S1~S10b)와의 대응 표기다.

    /**
     * (Task9-S1) 첨부 upload — 원본 시각/uploader 보존 + sizeBytes/contentType 유추 + 스트림 close(BLOCKER-1).
     */
    @Test
    fun `S34 첨부 upload - 원본 시각 uploader가 보존되고 스트림이 close된다`() {
        val bytes = byteArrayOf(1, 2, 3, 4, 5)
        val source = FixtureImportAttachmentSource(mapOf("photo.png" to bytes))
        val originalCreatedAt = Instant.parse("2018-03-01T00:00:00Z")
        val cmd =
            IssueImportCommand(
                projectKey = PROJECT_KEY,
                requesterUserId = NORMAL_REQUESTER_ID,
                summary = "S34 첨부 upload 테스트",
                sourceKey = "JIRA-500",
                attachments =
                    listOf(
                        ImportAttachment(
                            filename = "photo.png",
                            authorEmail = BOB_EMAIL,
                            createdAt = originalCreatedAt,
                            sizeBytes = null,
                        ),
                    ),
            )

        val result = issueImportAdapter.importIssue(cmd, source)

        check(result is IssueImportResult.Success) { "Success 여야 하지만 $result 입니다." }
        val attachments = fetchAttachments(result.issueKey)
        assert(attachments.size == 1) { "첨부가 1건 저장돼야 하지만 ${attachments.size}건입니다." }
        val saved = attachments.single()
        assert(saved.filename == "photo.png") { "filename 이 보존돼야 하지만 ${saved.filename} 입니다." }
        assert(saved.contentType == "image/png") {
            "mimeType 미지정 시 확장자 기반 유추(image/png)여야 하지만 ${saved.contentType} 입니다."
        }
        assert(saved.sizeBytes == bytes.size.toLong()) {
            "sizeBytes 미지정 시 실제 스트림 바이트 수로 유추돼야 하지만 ${saved.sizeBytes} 입니다."
        }
        assert(saved.uploadedBy == BOB_ID) { "authorEmail 매칭이므로 uploadedBy 가 Bob 이어야 하지만 ${saved.uploadedBy} 입니다." }
        assert(saved.createdAt == originalCreatedAt) { "createdAt 이 원본 시각으로 보존돼야 하지만 ${saved.createdAt} 입니다." }
        assert(source.openedStreams.isNotEmpty() && source.openedStreams.all { it.closed }) {
            "어댑터가 open() 이 반환한 스트림을 반드시 close 해야 합니다(BLOCKER-1, FD 누수 방지)."
        }
    }

    /**
     * (Task9-S2) changelog recordImported — occurredAt/actor 보존 + Jira→BTS field 매핑 테이블 적용.
     */
    @Test
    fun `S35 changelog recordImported - occurredAt actor가 보존되고 field가 BTS 필드로 매핑된다`() {
        val occurredAt = Instant.parse("2017-05-01T09:00:00Z")
        val cmd =
            IssueImportCommand(
                projectKey = PROJECT_KEY,
                requesterUserId = NORMAL_REQUESTER_ID,
                summary = "S35 changelog 매핑 테스트",
                sourceKey = "JIRA-510",
                changelog =
                    listOf(
                        ImportChangeGroup(
                            authorEmail = ALICE_EMAIL,
                            occurredAt = occurredAt,
                            items =
                                listOf(
                                    ImportChangeItem(field = "status", fromValue = "Open", toValue = "In Progress"),
                                    ImportChangeItem(field = "issuetype", fromValue = "Bug", toValue = "Task"),
                                    ImportChangeItem(field = "Fix Version", fromValue = null, toValue = "1.0"),
                                ),
                        ),
                    ),
            )

        val result = issueImportAdapter.importIssue(cmd)

        check(result is IssueImportResult.Success) { "Success 여야 하지만 $result 입니다." }
        val groups = fetchChangeGroups(result.issueKey)
        assert(groups.size == 1) { "그룹이 1건 기록돼야 하지만 ${groups.size}건입니다." }
        val group = groups.single()
        assert(group.actorId == ALICE_ID) { "actorId 가 Alice 여야 하지만 ${group.actorId} 입니다." }
        assert(group.createdAt == occurredAt) { "createdAt 이 원본 발생 시각으로 보존돼야 하지만 ${group.createdAt} 입니다." }
        val fields = group.items.map { it.field }
        assert(fields == listOf("status", "type", "fixVersions")) {
            "status/issuetype/Fix Version 이 각각 status/type/fixVersions 로 매핑돼야 하지만 $fields 입니다."
        }
        val fixVersionItem = group.items.first { it.field == "fixVersions" }
        assert(fixVersionItem.fromValue == null && fixVersionItem.toValue == "1.0") {
            "fromValue/toValue 는 Jira 원본 문자열 그대로 보존돼야 하지만 $fixVersionItem 입니다."
        }
    }

    /**
     * (Task9-S3) UPDATE 권한 없음 — 첨부/이력 모두 스킵되고 집약 경고를 남기며 이슈는 생성된다.
     */
    @Test
    fun `S36 UPDATE 권한 없음 - 첨부 이력 모두 스킵되고 집약 경고를 남기며 이슈는 생성된다`() {
        val source = FixtureImportAttachmentSource(mapOf("a.png" to byteArrayOf(1, 2, 3)))
        val cmd =
            IssueImportCommand(
                projectKey = PROJECT_KEY,
                requesterUserId = UPDATE_DENIED_REQUESTER_ID,
                summary = "S36 첨부 이력 권한없음 테스트",
                sourceKey = "JIRA-511",
                attachments = listOf(ImportAttachment(filename = "a.png", mimeType = "image/png")),
                changelog =
                    listOf(
                        ImportChangeGroup(
                            occurredAt = Instant.parse("2020-01-01T00:00:00Z"),
                            items = listOf(ImportChangeItem(field = "priority", fromValue = "1", toValue = "2")),
                        ),
                    ),
            )

        val result = issueImportAdapter.importIssue(cmd, source)

        check(result is IssueImportResult.Success) { "이슈 생성 자체는 성공해야 하지만 $result 입니다." }
        assert(result.warnings.any { it.contains("첨부") && it.contains("권한") }) {
            "첨부 권한없음 집약 경고가 있어야 하지만 ${result.warnings} 입니다."
        }
        assert(result.warnings.any { it.contains("변경 이력") && it.contains("권한") }) {
            "변경 이력 권한없음 집약 경고가 있어야 하지만 ${result.warnings} 입니다."
        }
        assert(fetchAttachments(result.issueKey).isEmpty()) { "권한 없으면 첨부가 생성되지 않아야 합니다." }
        assert(fetchChangeGroups(result.issueKey).isEmpty()) { "권한 없으면 이력이 생성되지 않아야 합니다." }
    }

    /**
     * (Task9-S4) MIME 거부 / 스캔 미가용 / 원본 없음(zip 부재) — 첨부별 경고를 남기고 이슈는 커밋된다.
     */
    @Test
    fun `S37 MIME 거부 스캔 미가용 원본없음 - 첨부별 경고를 남기고 이슈는 커밋된다`() {
        val source =
            FixtureImportAttachmentSource(
                mapOf(
                    "virus.exe" to byteArrayOf(1, 2, 3),
                    "trigger.png" to SCAN_UNAVAILABLE_TRIGGER_BYTES,
                    // "missing.png" 는 source 에 없음 → NOT_FOUND(zip 부재) 경로.
                ),
            )
        val cmd =
            IssueImportCommand(
                projectKey = PROJECT_KEY,
                requesterUserId = NORMAL_REQUESTER_ID,
                summary = "S37 첨부 실패 유형 테스트",
                sourceKey = "JIRA-512",
                attachments =
                    listOf(
                        ImportAttachment(filename = "virus.exe", mimeType = "application/x-msdownload"),
                        ImportAttachment(filename = "trigger.png", mimeType = "image/png"),
                        ImportAttachment(filename = "missing.png", mimeType = "image/png"),
                    ),
            )

        val result = issueImportAdapter.importIssue(cmd, source)

        check(result is IssueImportResult.Success) { "이슈 생성은 성공해야 하지만 $result 입니다." }
        assert(fetchAttachments(result.issueKey).isEmpty()) {
            "3건 모두 실패해야 하지만 ${fetchAttachments(result.issueKey)} 가 저장됐습니다."
        }
        assert(result.warnings.any { it.contains("허용되지 않는") }) { "MIME 거부 경고가 있어야 하지만 ${result.warnings} 입니다." }
        assert(result.warnings.any { it.contains("바이러스 스캔을 수행") }) {
            "스캔 미가용 경고가 있어야 하지만 ${result.warnings} 입니다."
        }
        assert(result.warnings.any { it.contains("원본 파일을 찾을 수 없") }) {
            "원본 없음 경고가 있어야 하지만 ${result.warnings} 입니다."
        }
    }

    /**
     * (Task9-S5) author 미해석 — 첨부는 requester 로 폴백, 이력은 actorId=null(요청자 아님, 비대칭 규칙).
     */
    @Test
    fun `S38 author 미해석 - 첨부는 requester로 폴백되고 이력은 actorId가 null로 저장된다`() {
        val source = FixtureImportAttachmentSource(mapOf("ghost.png" to byteArrayOf(1, 2, 3, 4)))
        val cmd =
            IssueImportCommand(
                projectKey = PROJECT_KEY,
                requesterUserId = NORMAL_REQUESTER_ID,
                summary = "S38 author 미해석 테스트",
                sourceKey = "JIRA-513",
                attachments =
                    listOf(
                        ImportAttachment(
                            filename = "ghost.png",
                            mimeType = "image/png",
                            authorEmail = "ghost@example.com",
                        ),
                    ),
                changelog =
                    listOf(
                        ImportChangeGroup(
                            authorEmail = "ghost@example.com",
                            occurredAt = Instant.parse("2020-02-01T00:00:00Z"),
                            items = listOf(ImportChangeItem(field = "priority", fromValue = "1", toValue = "2")),
                        ),
                    ),
            )

        val result = issueImportAdapter.importIssue(cmd, source)

        check(result is IssueImportResult.Success) { "Success 여야 하지만 $result 입니다." }
        val attachment = fetchAttachments(result.issueKey).single()
        assert(attachment.uploadedBy == NORMAL_REQUESTER_ID) {
            "첨부 author 미매칭이므로 uploadedBy 가 requester 로 폴백돼야 하지만 ${attachment.uploadedBy} 입니다."
        }
        val group = fetchChangeGroups(result.issueKey).single()
        assert(group.actorId == null) {
            "이력 author 미매칭이면 requester 로 폴백하지 않고 actorId 가 null 이어야 하지만 ${group.actorId} 입니다."
        }
    }

    /**
     * (Task9-S6) 미매칭 field — 스킵 + 경고. 그룹 내 일부 미매핑은 해당 item 만 스킵하고, 전 item
     * 미매핑이면 그룹 자체를 스킵한다.
     */
    @Test
    fun `S39 미매칭 field - 스킵되고 경고를 남기며 전부 미매핑인 그룹은 통째로 스킵된다`() {
        val cmd =
            IssueImportCommand(
                projectKey = PROJECT_KEY,
                requesterUserId = NORMAL_REQUESTER_ID,
                summary = "S39 미매칭 field 테스트",
                sourceKey = "JIRA-514",
                changelog =
                    listOf(
                        ImportChangeGroup(
                            occurredAt = Instant.parse("2020-03-01T00:00:00Z"),
                            items =
                                listOf(
                                    ImportChangeItem(field = "priority", fromValue = "1", toValue = "2"),
                                    ImportChangeItem(field = "customfield_10001", fromValue = "a", toValue = "b"),
                                ),
                        ),
                        ImportChangeGroup(
                            occurredAt = Instant.parse("2020-03-02T00:00:00Z"),
                            items =
                                listOf(
                                    ImportChangeItem(field = "customfield_99999", fromValue = "x", toValue = "y"),
                                ),
                        ),
                    ),
            )

        val result = issueImportAdapter.importIssue(cmd)

        check(result is IssueImportResult.Success) { "Success 여야 하지만 $result 입니다." }
        val groups = fetchChangeGroups(result.issueKey)
        assert(groups.size == 1) { "전부 미매핑인 두 번째 그룹은 스킵돼야 하지만 ${groups.size}건 기록됐습니다." }
        assert(groups.single().items.map { it.field } == listOf("priority")) {
            "미매핑 item 은 스킵되고 매핑된 priority 만 남아야 하지만 ${groups.single().items} 입니다."
        }
        assert(result.warnings.any { it.contains("2") && it.contains("매핑") }) {
            "미매핑 field 2건 집약 경고가 있어야 하지만 ${result.warnings} 입니다."
        }
    }

    /**
     * (Task9-S7) occurredAt 파싱 실패(null) — 그룹 전체를 스킵하고 경고를 남긴다.
     */
    @Test
    fun `S40 occurredAt 파싱 실패 - 그룹 전체가 스킵되고 경고를 남긴다`() {
        val cmd =
            IssueImportCommand(
                projectKey = PROJECT_KEY,
                requesterUserId = NORMAL_REQUESTER_ID,
                summary = "S40 occurredAt 파싱 실패 테스트",
                sourceKey = "JIRA-515",
                changelog =
                    listOf(
                        ImportChangeGroup(
                            occurredAt = null,
                            items = listOf(ImportChangeItem(field = "priority", fromValue = "1", toValue = "2")),
                        ),
                    ),
            )

        val result = issueImportAdapter.importIssue(cmd)

        check(result is IssueImportResult.Success) { "Success 여야 하지만 $result 입니다." }
        assert(fetchChangeGroups(result.issueKey).isEmpty()) { "occurredAt 없는 그룹은 기록되면 안 됩니다." }
        assert(result.warnings.any { it.contains("시각") }) { "시각 확인불가 경고가 있어야 하지만 ${result.warnings} 입니다." }
    }

    /**
     * (Task9-S8) 이슈당 이력 상한(1000건) 초과 — 초과분은 스킵되고 경고를 남긴다.
     */
    @Test
    fun `S41 이력 상한 1000건 초과 - 1000건만 기록되고 초과분은 스킵되며 경고를 남긴다`() {
        val groups =
            (1..1001).map { i ->
                ImportChangeGroup(
                    occurredAt = Instant.parse("2020-01-01T00:00:00Z").plusSeconds(i.toLong()),
                    items = listOf(ImportChangeItem(field = "priority", fromValue = "1", toValue = "2")),
                )
            }
        val cmd =
            IssueImportCommand(
                projectKey = PROJECT_KEY,
                requesterUserId = NORMAL_REQUESTER_ID,
                summary = "S41 이력 상한 테스트",
                sourceKey = "JIRA-516",
                changelog = groups,
            )

        val result = issueImportAdapter.importIssue(cmd)

        check(result is IssueImportResult.Success) { "Success 여야 하지만 $result 입니다." }
        assert(fetchChangeGroups(result.issueKey).size == 1000) {
            "상한 1000건만 기록돼야 하지만 ${fetchChangeGroups(result.issueKey).size}건입니다."
        }
        assert(result.warnings.any { it.contains("상한") }) { "상한 초과 경고가 있어야 하지만 ${result.warnings} 입니다." }
    }

    /**
     * (Task9-S9) dry-run — 첨부/이력 권한없음 미리보기는 FORBIDDEN 하드 실패로 엮이지 않고
     * 별도 경고 경로로만 남는다([warnCommentsWorklogsIfNeeded]·S30 과 동일 원칙, CONCERN-A 재발 방지).
     */
    @Test
    fun `S42 dryRun 첨부 이력 권한없음 - FORBIDDEN 아닌 Success이고 경고만 남기며 insert 없다`() {
        val cmd =
            IssueImportCommand(
                projectKey = PROJECT_KEY,
                requesterUserId = UPDATE_DENIED_REQUESTER_ID,
                summary = "S42 dryRun 첨부 이력 권한없음 테스트",
                sourceKey = "JIRA-517",
                attachments = listOf(ImportAttachment(filename = "x.png")),
                changelog =
                    listOf(
                        ImportChangeGroup(
                            occurredAt = Instant.parse("2020-01-01T00:00:00Z"),
                            items = listOf(ImportChangeItem(field = "priority", fromValue = "1", toValue = "2")),
                        ),
                    ),
                dryRun = true,
            )

        val result = issueImportAdapter.importIssue(cmd)

        check(result is IssueImportResult.Success) { "FORBIDDEN 아닌 Success 여야 하지만 $result 입니다." }
        assert(result.warnings.any { it.contains("첨부") }) { "첨부 경고가 있어야 하지만 ${result.warnings} 입니다." }
        assert(result.warnings.any { it.contains("변경 이력") }) { "변경 이력 경고가 있어야 하지만 ${result.warnings} 입니다." }
        assert(countImportIssues() == 0) { "dryRun 은 이슈를 생성하지 않아야 하지만 ${countImportIssues()} 개 존재합니다." }
    }

    /**
     * (Task9-S10a) 첨부 파일명 500자 초과 — BLOCKER-2 사전체크로 첨부만 스킵되고, insert throw
     * 없이(=행 롤백 없이) 이슈는 커밋된다.
     */
    @Test
    fun `S43 첨부 파일명 500자 초과 - 사전체크로 스킵되고 insert throw 없이 이슈는 커밋된다`() {
        val longFilename = "a".repeat(501) + ".png"
        val source = FixtureImportAttachmentSource(mapOf(longFilename to byteArrayOf(1, 2, 3)))
        val cmd =
            IssueImportCommand(
                projectKey = PROJECT_KEY,
                requesterUserId = NORMAL_REQUESTER_ID,
                summary = "S43 파일명 초과 테스트",
                sourceKey = "JIRA-518",
                attachments = listOf(ImportAttachment(filename = longFilename, mimeType = "image/png")),
            )

        val result = issueImportAdapter.importIssue(cmd, source)

        check(result is IssueImportResult.Success) { "사전체크로 스킵되고 이슈는 커밋돼야 하지만 $result 입니다." }
        assert(fetchAttachments(result.issueKey).isEmpty()) { "길이 초과 첨부는 저장되면 안 됩니다." }
        assert(result.warnings.any { it.contains("파일명") }) { "파일명 초과 경고가 있어야 하지만 ${result.warnings} 입니다." }
    }

    /**
     * (Task9-S10b) 첨부 insert 중 예상외 throw — [FaultInjectingAttachmentRepository] 로 실제 tx 경계에서
     * 이슈까지 함께 롤백되는지 검증한다(BLOCKER-2 — insert throw 는 best-effort 로 강등하면 안 됨).
     */
    @Test
    fun `S44 첨부 insert 중 예상외 throw - 이슈까지 함께 롤백된다`() {
        val source =
            FixtureImportAttachmentSource(mapOf(UNEXPECTED_ATTACHMENT_THROW_MARKER to byteArrayOf(1, 2, 3, 4)))
        val cmd =
            IssueImportCommand(
                projectKey = PROJECT_KEY,
                requesterUserId = NORMAL_REQUESTER_ID,
                summary = "S44 첨부 예상외 throw 행원자성 테스트",
                sourceKey = "JIRA-519",
                attachments =
                    listOf(
                        ImportAttachment(filename = UNEXPECTED_ATTACHMENT_THROW_MARKER, mimeType = "image/png"),
                    ),
            )

        val result = issueImportAdapter.importIssue(cmd, source)

        check(result is IssueImportResult.Failure) { "Failure 여야 하지만 $result 입니다." }
        assert(countImportIssues() == 0) {
            "예상외 throw 시 이슈까지 롤백돼야 하지만 ${countImportIssues()} 개 존재합니다."
        }
    }

    // ── S45~S47(코드리뷰 CONCERN-2/3 hot-fix). sizeBytes 신뢰경계 + zip-bomb 방어 + 권한예외 전파 ──

    /**
     * (C3 hot-fix) 첨부 사전체크 통과 이후 실제 upload 시점의 권한 거부(TOCTOU) —
     * [com.bts.issue.domain.IssueAccessDeniedException] 이 더 이상 스킵-경고로 강등되지 않고
     * 그대로 전파돼 행 전체가 롤백된다(CONCERN-3). [S36]([applyAttachments] 사전체크 자체가 거부하는
     * 경우)과 대칭 — 이 시나리오는 사전체크는 통과하되 그 직후 실행 시점에만 거부되는 레이스를
     * [TOCTOU_ATTACHMENT_REQUESTER_ID]([SelectiveDenyPermissionResolver] revokeUpdateAfterFirstCallFor)
     * 로 시뮬레이션한다.
     */
    @Test
    fun `S45 첨부 upload 시점 권한거부 TOCTOU - 스킵경고로 강등되지 않고 행 전체가 롤백된다`() {
        val source = FixtureImportAttachmentSource(mapOf("race.png" to byteArrayOf(1, 2, 3)))
        val cmd =
            IssueImportCommand(
                projectKey = PROJECT_KEY,
                requesterUserId = TOCTOU_ATTACHMENT_REQUESTER_ID,
                summary = "S45 첨부 TOCTOU 권한거부 테스트",
                sourceKey = "JIRA-520",
                attachments = listOf(ImportAttachment(filename = "race.png", mimeType = "image/png")),
            )

        val result = issueImportAdapter.importIssue(cmd, source)

        check(result is IssueImportResult.Failure) {
            "사전체크 이후 거부는 스킵-경고가 아닌 행 전체 Failure 여야 하지만 $result 입니다."
        }
        assert(result.reasonCode == IssueImportResult.FORBIDDEN) {
            "reasonCode 가 FORBIDDEN 이어야 하지만 ${result.reasonCode} 입니다."
        }
        assert(countImportIssues() == 0) {
            "권한 거부가 전파되면 이슈까지 함께 롤백돼야 하지만 ${countImportIssues()} 개 존재합니다."
        }
    }

    /**
     * (C2 hot-fix) Jira 메타 sizeBytes 가 실제 스트림보다 작음 — 절단 없이 실제 전체 바이트가
     * 저장된다. [com.bts.issue.attachment.adapter.MinioStorageAdapter.put] 은 Content-Length(size)
     * 만큼만 읽으므로, 신뢰할 수 없는 작은 메타값을 그대로 넘기면 초과분이 조용히 버려진다(CONCERN-2).
     * 항상 실제 스트림 바이트를 세어 upload 해야 한다 — [attachmentStoragePort] 가 실제로 bound-read
     * 한 바이트 수까지 함께 검증한다.
     */
    @Test
    fun `S46 첨부 메타 sizeBytes가 실제보다 작음 - 절단 없이 실제 전체 바이트가 저장된다`() {
        val actualBytes = ByteArray(10) { it.toByte() }
        val source = FixtureImportAttachmentSource(mapOf("meta-mismatch.png" to actualBytes))
        val cmd =
            IssueImportCommand(
                projectKey = PROJECT_KEY,
                requesterUserId = NORMAL_REQUESTER_ID,
                summary = "S46 첨부 sizeBytes 신뢰경계 테스트",
                sourceKey = "JIRA-521",
                attachments =
                    listOf(
                        ImportAttachment(
                            filename = "meta-mismatch.png",
                            mimeType = "image/png",
                            // Jira 보고값(참고용) — 실제(10바이트)보다 작다.
                            sizeBytes = 3,
                        ),
                    ),
            )

        val result = issueImportAdapter.importIssue(cmd, source)

        check(result is IssueImportResult.Success) { "Success 여야 하지만 $result 입니다." }
        val saved = fetchAttachments(result.issueKey).single()
        assert(saved.sizeBytes == actualBytes.size.toLong()) {
            "메타 sizeBytes(3)를 신뢰해 절단되면 안 되고 실제 크기(${actualBytes.size})로 저장돼야 하지만 " +
                "${saved.sizeBytes} 입니다."
        }
        assert(attachmentStoragePort.lastPutReceivedByteCount == actualBytes.size) {
            "MinIO put 이 실제로 받은 바이트 수도 절단 없이 전체(${actualBytes.size})여야 하지만 " +
                "${attachmentStoragePort.lastPutReceivedByteCount} 입니다."
        }
    }

    /**
     * (C2 hot-fix) 100MB 초과 스트림 — zip-bomb 방어(bounded read) 검증. Jira 보고 sizeBytes 와
     * 무관하게 실제 스트림이 상한(100MB)을 초과하면 업로드를 시도하지 않고 TOO_LARGE 로 스킵 +
     * 경고를 남기며, 이슈 자체는 커밋된다.
     */
    @Test
    fun `S47 첨부 100MB 초과 스트림 - TOO_LARGE로 스킵되고 경고를 남기며 이슈는 커밋된다`() {
        val oversizedByteCount = MAX_ATTACHMENT_UPLOAD_BYTES_FOR_TEST + 1
        val source = OversizedImportAttachmentSource("huge.bin", oversizedByteCount)
        val cmd =
            IssueImportCommand(
                projectKey = PROJECT_KEY,
                requesterUserId = NORMAL_REQUESTER_ID,
                summary = "S47 첨부 100MB 초과 테스트",
                sourceKey = "JIRA-522",
                attachments =
                    listOf(
                        ImportAttachment(
                            filename = "huge.bin",
                            mimeType = "application/octet-stream",
                            // Jira 보고값(참고용, 실제 상한 초과 여부와 무관 — 실제 스트림 크기로만 판정한다).
                            sizeBytes = 10,
                        ),
                    ),
            )

        val result = issueImportAdapter.importIssue(cmd, source)

        check(result is IssueImportResult.Success) { "이슈 생성은 성공해야 하지만 $result 입니다." }
        assert(fetchAttachments(result.issueKey).isEmpty()) {
            "100MB 초과 첨부는 저장되면 안 되지만 ${fetchAttachments(result.issueKey)} 가 저장됐습니다."
        }
        assert(result.warnings.any { it.contains("상한") && it.contains("100MB") }) {
            "TOO_LARGE 경고가 있어야 하지만 ${result.warnings} 입니다."
        }
    }

    // ── private helpers ───────────────────────────────────────────────────────

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

    @Suppress("LongMethod")
    private fun seedProjectAndComponents() {
        conn().use { c ->
            c.autoCommit = false
            c.prepareStatement(
                "INSERT INTO projects (key, name) VALUES (?, ?) ON CONFLICT (key) DO NOTHING",
            ).use { stmt ->
                stmt.setString(1, PROJECT_KEY)
                stmt.setString(2, "Import Adapter Integration Test Project")
                stmt.executeUpdate()
            }

            // software-default 워크플로우 + open 상태 (createIssue 의 resolveStart 가 필요로 함)
            val wfId =
                c.prepareStatement(
                    "INSERT INTO workflows (key, name) VALUES ('software-default', '소프트웨어 개발 기본 워크플로우') " +
                        "ON CONFLICT (key) DO UPDATE SET name = EXCLUDED.name RETURNING id",
                ).use { stmt ->
                    stmt.executeQuery().use { rs ->
                        rs.next()
                        rs.getObject(1) as UUID
                    }
                }

            c.prepareStatement(
                "INSERT INTO workflow_states (workflow_id, key, name, category, display_order) " +
                    "VALUES (?, 'open', 'Open', 'TODO', 0) " +
                    "ON CONFLICT (workflow_id, key) DO UPDATE SET display_order = EXCLUDED.display_order",
            ).use { stmt ->
                stmt.setObject(1, wfId)
                stmt.executeUpdate()
            }

            // import-scheme 독립 생성 — 다른 통합 테스트의 scheme 과 충돌 방지(싱글톤 DB 공유)
            c.createStatement().use { stmt ->
                stmt.execute(
                    """
                    INSERT INTO workflow_schemes (key, name, is_default)
                    VALUES ('import-scheme', 'Import Adapter Test Scheme', false)
                    ON CONFLICT (key) DO NOTHING
                    """.trimIndent(),
                )
            }

            c.createStatement().use { stmt ->
                stmt.execute(
                    """
                    INSERT INTO workflow_scheme_issue_type_mappings (scheme_id, issue_type_id, workflow_id)
                    SELECT s.id, NULL, '$wfId'
                    FROM workflow_schemes s
                    WHERE s.key = 'import-scheme'
                      AND NOT EXISTS (
                        SELECT 1 FROM workflow_scheme_issue_type_mappings m
                        WHERE m.scheme_id = s.id AND m.issue_type_id IS NULL
                      )
                    """.trimIndent(),
                )
            }

            c.createStatement().use { stmt ->
                stmt.execute(
                    """
                    INSERT INTO project_workflow_scheme_assignments (project_id, workflow_scheme_id, assigned_at, assigned_by)
                    SELECT p.id, s.id, NOW(), '00000000-0000-0000-0000-000000000000'::uuid
                    FROM projects p, workflow_schemes s
                    WHERE p.key = '$PROJECT_KEY'
                      AND s.key = 'import-scheme'
                    ON CONFLICT (project_id) DO NOTHING
                    """.trimIndent(),
                )
            }

            c.commit()
        }

        backendComponentId = insertComponent(PROJECT_KEY, "Backend")
        existingVersionId = insertVersion(PROJECT_KEY, EXISTING_VERSION_NAME)
    }

    @Suppress("NestedBlockDepth") // conn/stmt/rs 3단 use 중첩 — JDBC 표준 패턴, 분리 실익 없음
    private fun insertComponent(
        projectKey: String,
        name: String,
    ): UUID {
        val projectId = fetchProjectId(projectKey)
        return conn().use { c ->
            c.prepareStatement(
                "INSERT INTO components (project_id, name) VALUES (?, ?) ON CONFLICT DO NOTHING RETURNING id",
            ).use { stmt ->
                stmt.setObject(1, projectId)
                stmt.setString(2, name)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) return rs.getObject(1) as UUID
                }
            }
            fetchComponentId(projectId, name)
        }
    }

    private fun fetchComponentId(
        projectId: UUID,
        name: String,
    ): UUID =
        conn().use { c ->
            c.prepareStatement("SELECT id FROM components WHERE project_id = ? AND name = ?").use { stmt ->
                stmt.setObject(1, projectId)
                stmt.setString(2, name)
                stmt.executeQuery().use { rs ->
                    check(rs.next()) { "$name 컴포넌트가 없습니다." }
                    rs.getObject(1) as UUID
                }
            }
        }

    /** S10/S11/S17 — 자동생성 실행 후(또는 이미 존재 시) id 조회, 미존재면 null(생성 안 됨 검증용). */
    @Suppress("NestedBlockDepth") // conn/stmt/rs 3단 use 중첩 — JDBC 표준 패턴, 분리 실익 없음
    private fun findComponentIdOrNull(
        projectKey: String,
        name: String,
    ): UUID? {
        val projectId = fetchProjectId(projectKey)
        return conn().use { c ->
            c.prepareStatement("SELECT id FROM components WHERE project_id = ? AND name = ?").use { stmt ->
                stmt.setObject(1, projectId)
                stmt.setString(2, name)
                stmt.executeQuery().use { rs -> if (rs.next()) rs.getObject(1) as UUID else null }
            }
        }
    }

    @Suppress("NestedBlockDepth") // conn/stmt/rs 3단 use 중첩 — JDBC 표준 패턴, 분리 실익 없음
    private fun insertVersion(
        projectKey: String,
        name: String,
    ): UUID {
        val projectId = fetchProjectId(projectKey)
        return conn().use { c ->
            c.prepareStatement(
                "INSERT INTO versions (project_id, name) VALUES (?, ?) ON CONFLICT DO NOTHING RETURNING id",
            ).use { stmt ->
                stmt.setObject(1, projectId)
                stmt.setString(2, name)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) return rs.getObject(1) as UUID
                }
            }
            checkNotNull(findVersionIdOrNull(projectKey, name)) { "$name 버전이 없습니다." }
        }
    }

    /** S12/S13/S19 — 자동생성 실행 후(또는 이미 존재 시) id 조회, 미존재면 null(생성 안 됨 검증용). */
    @Suppress("NestedBlockDepth") // conn/stmt/rs 3단 use 중첩 — JDBC 표준 패턴, 분리 실익 없음
    private fun findVersionIdOrNull(
        projectKey: String,
        name: String,
    ): UUID? {
        val projectId = fetchProjectId(projectKey)
        return conn().use { c ->
            c.prepareStatement("SELECT id FROM versions WHERE project_id = ? AND name = ?").use { stmt ->
                stmt.setObject(1, projectId)
                stmt.setString(2, name)
                stmt.executeQuery().use { rs -> if (rs.next()) rs.getObject(1) as UUID else null }
            }
        }
    }

    private fun fetchProjectId(projectKey: String): UUID =
        conn().use { c ->
            c.prepareStatement("SELECT id FROM projects WHERE key = ?").use { stmt ->
                stmt.setString(1, projectKey)
                stmt.executeQuery().use { rs ->
                    check(rs.next()) { "$projectKey 프로젝트가 없습니다." }
                    rs.getObject(1) as UUID
                }
            }
        }

    @Suppress("NestedBlockDepth") // conn/stmt/rs 3단 use 중첩 — JDBC 표준 패턴, 분리 실익 없음
    private fun fetchTypeKey(issueKey: String): String? =
        conn().use { c ->
            c.prepareStatement(
                "SELECT it.key FROM issues i JOIN issue_types it ON i.type_id = it.id WHERE i.key = ?",
            ).use { stmt ->
                stmt.setString(1, issueKey)
                stmt.executeQuery().use { rs ->
                    if (!rs.next()) return null
                    rs.getString(1)
                }
            }
        }

    @Suppress("NestedBlockDepth") // conn/stmt/rs 3단 use 중첩 — JDBC 표준 패턴, 분리 실익 없음
    private fun fetchComponentIds(issueKey: String): Set<UUID> =
        conn().use { c ->
            c.prepareStatement(
                "SELECT ic.component_id FROM issue_components ic JOIN issues i ON ic.issue_id = i.id WHERE i.key = ?",
            ).use { stmt ->
                stmt.setString(1, issueKey)
                stmt.executeQuery().use { rs ->
                    val ids = mutableSetOf<UUID>()
                    while (rs.next()) ids += rs.getObject(1) as UUID
                    ids
                }
            }
        }

    /** S12/S18 — 이슈에 연결된 "영향받는 버전" id 목록. */
    @Suppress("NestedBlockDepth") // conn/stmt/rs 3단 use 중첩 — JDBC 표준 패턴, 분리 실익 없음
    private fun fetchAffectsVersionIds(issueKey: String): Set<UUID> =
        conn().use { c ->
            c.prepareStatement(
                "SELECT iav.version_id FROM issue_affects_versions iav " +
                    "JOIN issues i ON iav.issue_id = i.id WHERE i.key = ?",
            ).use { stmt ->
                stmt.setString(1, issueKey)
                stmt.executeQuery().use { rs ->
                    val ids = mutableSetOf<UUID>()
                    while (rs.next()) ids += rs.getObject(1) as UUID
                    ids
                }
            }
        }

    /** S12 — 이슈에 연결된 "수정 예정 버전" id 목록. */
    @Suppress("NestedBlockDepth") // conn/stmt/rs 3단 use 중첩 — JDBC 표준 패턴, 분리 실익 없음
    private fun fetchFixVersionIds(issueKey: String): Set<UUID> =
        conn().use { c ->
            c.prepareStatement(
                "SELECT ifv.version_id FROM issue_fix_versions ifv " +
                    "JOIN issues i ON ifv.issue_id = i.id WHERE i.key = ?",
            ).use { stmt ->
                stmt.setString(1, issueKey)
                stmt.executeQuery().use { rs ->
                    val ids = mutableSetOf<UUID>()
                    while (rs.next()) ids += rs.getObject(1) as UUID
                    ids
                }
            }
        }

    /** S24/S26/S28 — 이슈에 연결된 활성 댓글 작성자 id 목록(`created_at` 오름차순). */
    @Suppress("NestedBlockDepth") // conn/stmt/rs 3단 use 중첩 — JDBC 표준 패턴, 분리 실익 없음
    private fun fetchCommentAuthorIds(issueKey: String): List<UUID> =
        conn().use { c ->
            c.prepareStatement(
                "SELECT c.author_id FROM comments c JOIN issues i ON c.issue_id = i.id " +
                    "WHERE i.key = ? ORDER BY c.created_at",
            ).use { stmt ->
                stmt.setString(1, issueKey)
                stmt.executeQuery().use { rs ->
                    val ids = mutableListOf<UUID>()
                    while (rs.next()) ids += rs.getObject(1) as UUID
                    ids
                }
            }
        }

    /** S32 — 이슈에 연결된 활성 댓글 `created_at` 목록(오름차순, 원본 시각 보존 검증용). */
    @Suppress("NestedBlockDepth") // conn/stmt/rs 3단 use 중첩 — JDBC 표준 패턴, 분리 실익 없음
    private fun fetchCommentCreatedAts(issueKey: String): List<Instant> =
        conn().use { c ->
            c.prepareStatement(
                "SELECT c.created_at FROM comments c JOIN issues i ON c.issue_id = i.id " +
                    "WHERE i.key = ? ORDER BY c.created_at",
            ).use { stmt ->
                stmt.setString(1, issueKey)
                stmt.executeQuery().use { rs ->
                    val createdAts = mutableListOf<Instant>()
                    while (rs.next()) createdAts += rs.getTimestamp(1).toInstant()
                    createdAts
                }
            }
        }

    /** S25/S26/S27/S28/S29 — 이슈에 연결된 활성 worklog 작성자 id 목록(`created_at` 오름차순). */
    @Suppress("NestedBlockDepth") // conn/stmt/rs 3단 use 중첩 — JDBC 표준 패턴, 분리 실익 없음
    private fun fetchWorklogAuthorIds(issueKey: String): List<UUID> =
        conn().use { c ->
            c.prepareStatement(
                "SELECT w.author_id FROM worklogs w JOIN issues i ON w.issue_id = i.id " +
                    "WHERE i.key = ? ORDER BY w.created_at",
            ).use { stmt ->
                stmt.setString(1, issueKey)
                stmt.executeQuery().use { rs ->
                    val ids = mutableListOf<UUID>()
                    while (rs.next()) ids += rs.getObject(1) as UUID
                    ids
                }
            }
        }

    /** S34/S37/S38/S43 — 첨부 조회 결과 1행([fetchAttachments]). */
    private data class AttachmentRow(
        val filename: String,
        val contentType: String,
        val sizeBytes: Long,
        val uploadedBy: UUID,
        val createdAt: Instant,
    )

    /** S34/S37/S38/S43 — 이슈에 저장된 첨부 목록(`created_at` 오름차순). */
    @Suppress("NestedBlockDepth") // conn/stmt/rs 3단 use 중첩 — JDBC 표준 패턴, 분리 실익 없음
    private fun fetchAttachments(issueKey: String): List<AttachmentRow> =
        conn().use { c ->
            c.prepareStatement(
                "SELECT a.filename, a.content_type, a.size_bytes, a.uploaded_by, a.created_at " +
                    "FROM issue_attachments a JOIN issues i ON a.issue_id = i.id " +
                    "WHERE i.key = ? ORDER BY a.created_at",
            ).use { stmt ->
                stmt.setString(1, issueKey)
                stmt.executeQuery().use { rs ->
                    val rows = mutableListOf<AttachmentRow>()
                    while (rs.next()) {
                        rows +=
                            AttachmentRow(
                                filename = rs.getString(1),
                                contentType = rs.getString(2),
                                sizeBytes = rs.getLong(3),
                                uploadedBy = rs.getObject(4) as UUID,
                                createdAt = rs.getTimestamp(5).toInstant(),
                            )
                    }
                    rows
                }
            }
        }

    /** S35/S38/S39/S40/S41 — 변경 이력 항목 1건([fetchChangeGroups] 의 [ChangeGroupRow.items] 원소). */
    private data class ChangeItemRow(val field: String, val fromValue: String?, val toValue: String?)

    /** S35/S38/S39/S40/S41 — 변경 이력 그룹 1건(items 포함). */
    private data class ChangeGroupRow(val actorId: UUID?, val createdAt: Instant, val items: List<ChangeItemRow>)

    /** 이슈에 기록된 변경 이력 그룹 목록(`id` 오름차순, items 포함). */
    @Suppress("NestedBlockDepth") // conn/stmt/rs 3단 use 중첩 — JDBC 표준 패턴, 분리 실익 없음
    private fun fetchChangeGroups(issueKey: String): List<ChangeGroupRow> {
        val rawGroups =
            conn().use { c ->
                c.prepareStatement(
                    "SELECT id, actor_id, created_at FROM issue_change_group WHERE issue_key = ? ORDER BY id",
                ).use { stmt ->
                    stmt.setString(1, issueKey)
                    stmt.executeQuery().use { rs ->
                        val rows = mutableListOf<Triple<Long, UUID?, Instant>>()
                        while (rs.next()) {
                            rows += Triple(rs.getLong(1), rs.getObject(2) as UUID?, rs.getTimestamp(3).toInstant())
                        }
                        rows
                    }
                }
            }
        return rawGroups.map { (groupId, actorId, createdAt) ->
            ChangeGroupRow(actorId, createdAt, fetchChangeItems(groupId))
        }
    }

    /** [fetchChangeGroups] 가 groupId 로 items 를 별쿼리 조회한다(`id` 오름차순). */
    @Suppress("NestedBlockDepth") // conn/stmt/rs 3단 use 중첩 — JDBC 표준 패턴, 분리 실익 없음
    private fun fetchChangeItems(groupId: Long): List<ChangeItemRow> =
        conn().use { c ->
            c.prepareStatement(
                "SELECT field, from_value, to_value FROM issue_change_item WHERE group_id = ? ORDER BY id",
            ).use { stmt ->
                stmt.setLong(1, groupId)
                stmt.executeQuery().use { rs ->
                    val items = mutableListOf<ChangeItemRow>()
                    while (rs.next()) items += ChangeItemRow(rs.getString(1), rs.getString(2), rs.getString(3))
                    items
                }
            }
        }

    private fun countImportIssues(): Int =
        conn().use { c ->
            c.prepareStatement("SELECT COUNT(*) FROM issues WHERE key LIKE '$PROJECT_KEY-%'").use { stmt ->
                stmt.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }

    private fun fetchKeySequence(): Long =
        conn().use { c ->
            c.prepareStatement("SELECT key_sequence FROM projects WHERE key = ?").use { stmt ->
                stmt.setString(1, PROJECT_KEY)
                stmt.executeQuery().use { rs ->
                    check(rs.next()) { "$PROJECT_KEY 프로젝트가 없습니다." }
                    rs.getLong(1)
                }
            }
        }

    /** q_issue_events 큐에서 최대 10건을 읽어 반환한다(visibility_timeout=1초). */
    @Suppress("NestedBlockDepth") // conn/stmt/rs 3단 use 중첩 — JDBC 표준 패턴, 분리 실익 없음
    private fun readIssueEventsQueue(): List<String> =
        conn().use { c ->
            c.prepareStatement("SELECT message FROM pgmq.read(?, ?, ?)").use { stmt ->
                stmt.setString(1, "q_issue_events")
                stmt.setInt(2, 1)
                stmt.setInt(3, 10)
                stmt.executeQuery().use { rs ->
                    val messages = mutableListOf<String>()
                    while (rs.next()) messages += rs.getString(1)
                    messages
                }
            }
        }

    private fun conn() =
        DriverManager.getConnection(
            TestConfig.postgres.jdbcUrl,
            TestConfig.postgres.username,
            TestConfig.postgres.password,
        )
}
