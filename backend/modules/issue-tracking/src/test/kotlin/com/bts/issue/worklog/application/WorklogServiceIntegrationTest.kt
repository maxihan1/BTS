// WorklogService 통합 테스트 — 권한·자동차감·집계·이력 오케스트레이션 Testcontainers 검증 (FR-TT-01 Task 5)

package com.bts.issue.worklog.application

import com.bts.issue.adapter.inbound.rest.IssueControllerTransitionIntegrationTest.TestConfig
import com.bts.issue.component.repository.ComponentRepository
import com.bts.issue.domain.ActorId
import com.bts.issue.domain.IssueAccessDeniedException
import com.bts.issue.domain.IssueKey
import com.bts.issue.domain.IssueNotFoundException
import com.bts.issue.history.IssueChangeDetector
import com.bts.issue.history.IssueChangeHistoryRepository
import com.bts.issue.history.IssueChangeLabelResolver
import com.bts.issue.history.IssueHistoryRecorder
import com.bts.issue.history.JdbcIssueChangeHistoryRepository
import com.bts.issue.project.archive.ProjectArchiveGuard
import com.bts.issue.project.archive.repository.ProjectArchiveStateRepository
import com.bts.issue.project.archive.ProjectArchivedException
import com.bts.issue.repository.IssueRepository
import com.bts.issue.resolution.repository.ResolutionRepository
import com.bts.issue.type.repository.IssueTypeRepository
import com.bts.issue.version.repository.VersionRepository
import com.bts.issue.worklog.repository.WorklogRepository
import com.bts.shared.permission.IssuePermission
import com.bts.shared.permission.IssuePermissionResolver
import com.bts.shared.permission.IssueScope
import com.bts.shared.user.UserLookupPort
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.jooq.DSLContext
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
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.junit.jupiter.SpringExtension
import org.springframework.test.context.web.WebAppConfiguration
import java.sql.DriverManager
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * WorklogService Testcontainers 통합 테스트 (FR-TT-01 Task 5).
 *
 * [TestConfig] singleton Testcontainers PostgreSQL + Flyway + 기본 빈을 재사용하고,
 * [WorklogServiceTestConfig] 로 WorklogService 및 실 이력 빈을 추가 등록한다.
 *
 * ## 검증 시나리오
 * - (1) create 자동차감: remaining=8h, 2h 기록 → time_spent=2h, remaining=6h.
 * - (2) create override: newRemaining=4h → remaining=4h.
 * - (3) create remaining NULL: remaining 미설정 → 기록 후 remaining=null, time_spent 증가.
 * - (4) update: time_spent 재집계, remaining 미조정.
 * - (5) delete: 소프트삭제 후 time_spent 재집계(제외), remaining 미복원.
 * - (6) 권한거부: UPDATE 없는 actor → 403. VIEW 있는 actor list 200, create 는 403.
 * - (7) 404 순서: 미존재 이슈 create → 404. 타 이슈 worklogId → 404.
 * - (8) author 한정: 타인 worklog update/delete → 403.
 * - (9) changelog: create 후 remaining 변경 → 이력에 remainingEstimate 항목 존재.
 * - (10) CONCERN-1 회귀가드: 순차 2건 create(각 2h, remaining=8h 시작) → 최종 remaining=4h, time_spent=4h.
 */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(
    classes = [
        TestConfig::class,
        WorklogServiceIntegrationTest.WorklogServiceTestConfig::class,
    ],
)
@WebAppConfiguration
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WorklogServiceIntegrationTest {
    /**
     * WorklogService 통합 테스트 전용 추가 설정.
     *
     * [TestConfig] 의 Postgres/jOOQ/IssueRepository 위에 다음 빈을 추가 등록한다.
     * - [WorklogRepository] — worklogs 테이블 저장소
     * - [IssueChangeDetector], [IssueChangeLabelResolver], [JdbcIssueChangeHistoryRepository] — 실 이력 빈
     * - [IssueHistoryRecorder] — 실 이력 recorder
     * - [WorklogService] — 테스트 대상 서비스
     * - [denyablePermissionResolver] — DENY_ACTOR 거부, VIEW_ONLY_ACTOR 는 VIEW 만 허용 (@Primary 대체)
     */
    @Configuration
    open class WorklogServiceTestConfig {
        @Bean
        open fun worklogRepository(
            dsl: DSLContext,
            clock: Clock,
        ): WorklogRepository = WorklogRepository(dsl, clock)

        @Bean
        open fun namedParameterJdbcTemplate(dataSource: DriverManagerDataSource): NamedParameterJdbcTemplate =
            NamedParameterJdbcTemplate(dataSource)

        @Bean
        open fun issueChangeHistoryRepository(jdbc: NamedParameterJdbcTemplate): IssueChangeHistoryRepository =
            JdbcIssueChangeHistoryRepository(jdbc)

        @Bean
        open fun issueChangeDetector(): IssueChangeDetector = IssueChangeDetector()

        /**
         * IssueChangeLabelResolver — 실 인스턴스.
         *
         * remainingEstimate 는 스칼라 passthrough 라 라벨 lookup 없음.
         * component/version/user/security 의존성들은 relaxed mock 으로 주입한다.
         */
        @Bean
        open fun issueChangeLabelResolver(
            issueTypeRepository: IssueTypeRepository,
            resolutionRepository: ResolutionRepository,
            issueRepository: IssueRepository,
        ): IssueChangeLabelResolver =
            IssueChangeLabelResolver(
                issueTypeRepository = issueTypeRepository,
                resolutionRepository = resolutionRepository,
                componentRepository = mockk<ComponentRepository>(relaxed = true),
                versionRepository = mockk<VersionRepository>(relaxed = true),
                userLookupPort = mockk<UserLookupPort>(relaxed = true),
                issueSecurityDirectory =
                    com.bts.issue.adapter.outbound.AlwaysAllowIssueSecurityDirectory(),
                issueRepository = issueRepository,
            )

        @Bean
        open fun issueHistoryRecorder(
            detector: IssueChangeDetector,
            resolver: IssueChangeLabelResolver,
            repository: IssueChangeHistoryRepository,
        ): IssueHistoryRecorder = IssueHistoryRecorder(detector, resolver, repository)

        /** FR-PJ-04 PR-4 Task 9 — 실 ProjectArchiveGuard(공유 dsl 위). archived_at IS NOT NULL 판정만 함. */
        @Bean
        open fun projectArchiveGuard(dsl: DSLContext): ProjectArchiveGuard =
            ProjectArchiveGuard(ProjectArchiveStateRepository(dsl))

        @Bean
        open fun worklogService(
            worklogRepository: WorklogRepository,
            issueRepository: IssueRepository,
            permissionResolver: IssuePermissionResolver,
            historyRecorder: IssueHistoryRecorder,
            archiveGuard: ProjectArchiveGuard,
            clock: Clock,
        ): WorklogService =
            WorklogService(
                worklogRepository = worklogRepository,
                issueRepository = issueRepository,
                permissionResolver = permissionResolver,
                historyRecorder = historyRecorder,
                archiveGuard = archiveGuard,
                clock = clock,
            )

        /**
         * @Primary IssuePermissionResolver.
         *
         * - [DENY_ACTOR_UUID]: 모든 권한 거부.
         * - [VIEW_ONLY_ACTOR_UUID]: VIEW 만 허용, UPDATE 거부.
         * - 그 외: 모두 허용.
         */
        @Bean
        @Primary
        open fun denyablePermissionResolver(): IssuePermissionResolver =
            object : IssuePermissionResolver {
                override fun hasPermission(
                    actorId: UUID,
                    permission: IssuePermission,
                    scope: IssueScope,
                ): Boolean =
                    when (actorId) {
                        DENY_ACTOR_UUID -> false
                        VIEW_ONLY_ACTOR_UUID -> permission == IssuePermission.VIEW
                        else -> true
                    }
            }
    }

    @Autowired
    lateinit var worklogService: WorklogService

    @Autowired
    lateinit var issueChangeHistoryRepository: IssueChangeHistoryRepository

    companion object {
        private const val PROJECT_KEY = "WLSVC"

        /** FR-PJ-04 PR-4 Task 9 — 아카이브 잠금 판별자 전용 프로젝트. */
        private const val ARCHIVED_PROJECT_KEY = "WLARCH"

        val ACTOR_UUID: UUID = UUID.fromString("11111111-1111-4111-8111-111111111111")
        val OTHER_ACTOR_UUID: UUID = UUID.fromString("22222222-2222-4222-8222-222222222222")
        val DENY_ACTOR_UUID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000099")
        val VIEW_ONLY_ACTOR_UUID: UUID = UUID.fromString("33333333-3333-4333-8333-333333333333")

        val ACTOR: ActorId = ActorId(ACTOR_UUID)
        val OTHER_ACTOR: ActorId = ActorId(OTHER_ACTOR_UUID)
        val DENY_ACTOR: ActorId = ActorId(DENY_ACTOR_UUID)
        val VIEW_ONLY_ACTOR: ActorId = ActorId(VIEW_ONLY_ACTOR_UUID)

        private const val H8 = 8 * 3600
        private const val H2 = 2 * 3600
        private const val H4 = 4 * 3600
        private const val H6 = 6 * 3600

        private var migrated = false
        private var seeded = false
    }

    // ── 초기화 ────────────────────────────────────────────────────────────────────

    @BeforeAll
    fun setUpAll() {
        if (!migrated) {
            applyMigrations()
            migrated = true
        }
        if (!seeded) {
            seedProject()
            seeded = true
        }
    }

    @BeforeEach
    fun cleanBetweenTests() {
        conn().use { c ->
            c.createStatement().use { stmt ->
                // 이력 삭제 (issue_change_item → issue_change_group 순서 — FK)
                stmt.execute("DELETE FROM issue_change_item")
                stmt.execute("DELETE FROM issue_change_group")
                // worklogs → issues 순서
                stmt.execute("DELETE FROM worklogs")
                stmt.execute("DELETE FROM issues WHERE key LIKE '$PROJECT_KEY-%'")
                stmt.execute("UPDATE projects SET key_sequence = 0 WHERE key = '$PROJECT_KEY'")
                // FR-PJ-04 PR-4 Task 9 — 아카이브 프로젝트 이슈도 매 테스트 초기화.
                stmt.execute("DELETE FROM issues WHERE key LIKE '$ARCHIVED_PROJECT_KEY-%'")
                stmt.execute("UPDATE projects SET key_sequence = 0 WHERE key = '$ARCHIVED_PROJECT_KEY'")
            }
        }
    }

    // ── (1) create 자동차감 ────────────────────────────────────────────────────────

    /**
     * (1) create 자동차감.
     *
     * Given  remaining=8h 이슈
     * When   2h worklog 추가 (newRemaining=null)
     * Then   time_spent=2h, remaining=6h (8h - 2h).
     */
    @Test
    fun `(1) create 자동차감 — 2h 기록 후 remaining=6h time_spent=2h`() {
        val issueKey = insertIssue(remainingSeconds = H8)

        val worklog =
            worklogService.create(
                actor = ACTOR,
                issueKey = IssueKey(issueKey),
                timeSpentSeconds = H2,
                startedAt = Instant.now(),
                comment = "2h 작업",
                newRemainingEstimateSeconds = null,
            )

        assertThat(worklog.timeSpentSeconds).isEqualTo(H2)
        assertThat(worklog.authorId).isEqualTo(ACTOR_UUID)

        val list = worklogService.listForIssue(ACTOR, IssueKey(issueKey))
        assertThat(list.timeSpentSeconds).isEqualTo(H2)
        assertThat(list.remainingEstimateSeconds).isEqualTo(H6)
    }

    // ── (2) create override ───────────────────────────────────────────────────────

    /**
     * (2) create override — newRemaining=4h 직접 지정.
     *
     * Given  remaining=8h 이슈
     * When   2h worklog 추가 + newRemaining=4h
     * Then   time_spent=2h, remaining=4h.
     */
    @Test
    fun `(2) create override — newRemaining=4h 지정 시 remaining=4h`() {
        val issueKey = insertIssue(remainingSeconds = H8)

        worklogService.create(
            actor = ACTOR,
            issueKey = IssueKey(issueKey),
            timeSpentSeconds = H2,
            startedAt = Instant.now(),
            comment = null,
            newRemainingEstimateSeconds = H4,
        )

        val list = worklogService.listForIssue(ACTOR, IssueKey(issueKey))
        assertThat(list.timeSpentSeconds).isEqualTo(H2)
        assertThat(list.remainingEstimateSeconds).isEqualTo(H4)
    }

    // ── (3) create remaining NULL ─────────────────────────────────────────────────

    /**
     * (3) remaining NULL — original/remaining 미설정 이슈에 worklog 추가.
     *
     * Given  originalEstimate=null, remaining=null 이슈
     * When   2h worklog 추가
     * Then   time_spent=2h, remaining=null (NULL 유지).
     */
    @Test
    fun `(3) remaining NULL — 미추정 이슈에 기록 후 remaining=null time_spent 증가`() {
        val issueKey = insertIssue(remainingSeconds = null)

        worklogService.create(
            actor = ACTOR,
            issueKey = IssueKey(issueKey),
            timeSpentSeconds = H2,
            startedAt = Instant.now(),
            comment = null,
            newRemainingEstimateSeconds = null,
        )

        val list = worklogService.listForIssue(ACTOR, IssueKey(issueKey))
        assertThat(list.timeSpentSeconds).isEqualTo(H2)
        assertThat(list.remainingEstimateSeconds).isNull()
    }

    // ── (4) update ────────────────────────────────────────────────────────────────

    /**
     * (4) update — time_spent 재집계, remaining 미조정.
     *
     * Given  remaining=8h 이슈, 2h worklog 1건
     * When   worklog 를 3h 로 수정
     * Then   time_spent=3h, remaining=6h (update 는 remaining 미조정).
     */
    @Test
    fun `(4) update — 시간 수정 후 time_spent 재집계, remaining 미조정`() {
        val issueKey = insertIssue(remainingSeconds = H8)

        val worklog =
            worklogService.create(
                actor = ACTOR,
                issueKey = IssueKey(issueKey),
                timeSpentSeconds = H2,
                startedAt = Instant.now(),
                comment = null,
                newRemainingEstimateSeconds = null,
            )

        // create 후 remaining=8h-2h=6h
        val threeHours = H2 + 3600
        worklogService.update(
            actor = ACTOR,
            issueKey = IssueKey(issueKey),
            worklogId = worklog.id,
            timeSpentSeconds = threeHours,
            startedAt = null,
            comment = null,
        )

        val list = worklogService.listForIssue(ACTOR, IssueKey(issueKey))
        assertThat(list.timeSpentSeconds).isEqualTo(threeHours)
        // update 는 remaining 미조정 — 여전히 6h
        assertThat(list.remainingEstimateSeconds).isEqualTo(H6)
    }

    // ── (5) delete ────────────────────────────────────────────────────────────────

    /**
     * (5) delete — 소프트삭제 후 time_spent 재집계(제외), remaining 미복원.
     *
     * Given  remaining=8h 이슈, 2h worklog 1건 (자동차감으로 remaining=6h)
     * When   worklog 삭제
     * Then   time_spent=0, remaining=6h (삭제 후에도 remaining 복원 안 함).
     */
    @Test
    fun `(5) delete — 소프트삭제 후 time_spent=0 remaining 미복원`() {
        val issueKey = insertIssue(remainingSeconds = H8)

        val worklog =
            worklogService.create(
                actor = ACTOR,
                issueKey = IssueKey(issueKey),
                timeSpentSeconds = H2,
                startedAt = Instant.now(),
                comment = null,
                newRemainingEstimateSeconds = null,
            )

        worklogService.delete(
            actor = ACTOR,
            issueKey = IssueKey(issueKey),
            worklogId = worklog.id,
        )

        val list = worklogService.listForIssue(ACTOR, IssueKey(issueKey))
        assertThat(list.worklogs).isEmpty()
        assertThat(list.timeSpentSeconds).isEqualTo(0)
        // remaining 미복원 — 6h 유지
        assertThat(list.remainingEstimateSeconds).isEqualTo(H6)
    }

    // ── (6) 권한 거부 ──────────────────────────────────────────────────────────────

    /**
     * (6a) UPDATE 없는 actor — create 403.
     */
    @Test
    fun `(6a) 권한 거부 — UPDATE 없는 actor create 403`() {
        val issueKey = insertIssue(remainingSeconds = null)

        assertThatThrownBy {
            worklogService.create(
                actor = DENY_ACTOR,
                issueKey = IssueKey(issueKey),
                timeSpentSeconds = H2,
                startedAt = Instant.now(),
                comment = null,
                newRemainingEstimateSeconds = null,
            )
        }.isInstanceOf(IssueAccessDeniedException::class.java)
    }

    /**
     * (6b) VIEW 만 있는 actor — list 성공(200), create 403.
     */
    @Test
    fun `(6b) VIEW 전용 actor — listForIssue 성공 create 403`() {
        val issueKey = insertIssue(remainingSeconds = null)

        // listForIssue — VIEW 권한으로 성공
        val list = worklogService.listForIssue(VIEW_ONLY_ACTOR, IssueKey(issueKey))
        assertThat(list).isNotNull

        // create — UPDATE 없어서 403
        assertThatThrownBy {
            worklogService.create(
                actor = VIEW_ONLY_ACTOR,
                issueKey = IssueKey(issueKey),
                timeSpentSeconds = H2,
                startedAt = Instant.now(),
                comment = null,
                newRemainingEstimateSeconds = null,
            )
        }.isInstanceOf(IssueAccessDeniedException::class.java)
    }

    // ── (7) 404 순서 ──────────────────────────────────────────────────────────────

    /**
     * (7a) 미존재 이슈 create → 404.
     *
     * 권한 검증(UPDATE 통과) → 이슈 resolve → 404.
     */
    @Test
    fun `(7a) 미존재 이슈 create — 권한 통과 후 404`() {
        assertThatThrownBy {
            worklogService.create(
                actor = ACTOR,
                issueKey = IssueKey("WLSVC-99999"),
                timeSpentSeconds = H2,
                startedAt = Instant.now(),
                comment = null,
                newRemainingEstimateSeconds = null,
            )
        }.isInstanceOf(IssueNotFoundException::class.java)
    }

    /**
     * (7b) 타 이슈의 worklogId → update 404.
     *
     * 이슈 A 의 worklog 를 이슈 B 경로로 update → 404.
     */
    @Test
    fun `(7b) 타 이슈 worklogId update — 이슈 불일치 404`() {
        val issueKeyA = insertIssue(remainingSeconds = null)
        val issueKeyB = insertIssue(remainingSeconds = null)

        val worklogOfA =
            worklogService.create(
                actor = ACTOR,
                issueKey = IssueKey(issueKeyA),
                timeSpentSeconds = H2,
                startedAt = Instant.now(),
                comment = null,
                newRemainingEstimateSeconds = null,
            )

        // 이슈 B 경로에서 이슈 A 의 worklog 를 update → 404
        assertThatThrownBy {
            worklogService.update(
                actor = ACTOR,
                issueKey = IssueKey(issueKeyB),
                worklogId = worklogOfA.id,
                timeSpentSeconds = H2,
                startedAt = null,
                comment = null,
            )
        }.isInstanceOf(IssueNotFoundException::class.java)
    }

    // ── (8) author 한정 ───────────────────────────────────────────────────────────

    /**
     * (8a) 타인 worklog update — 403.
     */
    @Test
    fun `(8a) 타인 worklog update — 403`() {
        val issueKey = insertIssue(remainingSeconds = null)

        val worklog =
            worklogService.create(
                actor = ACTOR,
                issueKey = IssueKey(issueKey),
                timeSpentSeconds = H2,
                startedAt = Instant.now(),
                comment = null,
                newRemainingEstimateSeconds = null,
            )

        assertThatThrownBy {
            worklogService.update(
                actor = OTHER_ACTOR,
                issueKey = IssueKey(issueKey),
                worklogId = worklog.id,
                timeSpentSeconds = H4,
                startedAt = null,
                comment = null,
            )
        }.isInstanceOf(IssueAccessDeniedException::class.java)
    }

    /**
     * (8b) 타인 worklog delete — 403.
     */
    @Test
    fun `(8b) 타인 worklog delete — 403`() {
        val issueKey = insertIssue(remainingSeconds = null)

        val worklog =
            worklogService.create(
                actor = ACTOR,
                issueKey = IssueKey(issueKey),
                timeSpentSeconds = H2,
                startedAt = Instant.now(),
                comment = null,
                newRemainingEstimateSeconds = null,
            )

        assertThatThrownBy {
            worklogService.delete(
                actor = OTHER_ACTOR,
                issueKey = IssueKey(issueKey),
                worklogId = worklog.id,
            )
        }.isInstanceOf(IssueAccessDeniedException::class.java)
    }

    // ── (9) changelog ──────────────────────────────────────────────────────────────

    /**
     * (9) changelog — create 시 remaining 변경 → 이력 remainingEstimate 항목 존재.
     *
     * IssueHistoryRecorder.record(before, after) 가 호출되고 detector 가 remainingEstimate 차이를 감지한다.
     * JdbcIssueChangeHistoryRepository 로 실 DB 검증.
     */
    @Test
    fun `(9) changelog — create 후 remainingEstimate 이력 항목 존재`() {
        val issueKey = insertIssue(remainingSeconds = H8)

        worklogService.create(
            actor = ACTOR,
            issueKey = IssueKey(issueKey),
            timeSpentSeconds = H2,
            startedAt = Instant.now(),
            comment = "이력 검증",
            newRemainingEstimateSeconds = null,
        )

        val issueId = fetchIssueId(issueKey)
        val groups = issueChangeHistoryRepository.findByIssue(issueId)
        assertThat(groups).isNotEmpty

        val allItems = groups.flatMap { it.items }
        val remainingItems = allItems.filter { it.field == "remainingEstimate" }
        assertThat(remainingItems).isNotEmpty
        // before=8h, after=6h
        assertThat(remainingItems.first().fromValue).isEqualTo(H8.toString())
        assertThat(remainingItems.first().toValue).isEqualTo(H6.toString())
    }

    // ── (11) 소프트삭제 후 update → 404 ─────────────────────────────────────────────

    /**
     * (11) 소프트삭제된 worklog update → 404.
     *
     * Given  worklog 1건 추가 후 소프트삭제 (findById = null 상태)
     * When   update 호출
     * Then   IssueNotFoundException(404) 발생.
     *
     * 이 경로는 step1(findById)에서 null 반환 → IssueNotFoundException.
     * 동시 삭제 레이스 윈도우 방어 가드(worklogRepository.update 반환 0 체크)는
     * step1 통과 후 커밋 완료된 삭제를 처리하며, 결정적 재현이 어렵기 때문에
     * 이 테스트로 step1 경로를 검증한다.
     */
    @Test
    fun `(11) 소프트삭제된 worklog update — 404`() {
        val issueKey = insertIssue(remainingSeconds = null)

        val worklog =
            worklogService.create(
                actor = ACTOR,
                issueKey = IssueKey(issueKey),
                timeSpentSeconds = H2,
                startedAt = Instant.now(),
                comment = null,
                newRemainingEstimateSeconds = null,
            )

        // worklog 소프트삭제 — findById 가 null 을 반환하는 상태로 만든다
        conn().use { c ->
            c.prepareStatement("UPDATE worklogs SET deleted_at = NOW() WHERE id = ?").use { stmt ->
                stmt.setObject(1, worklog.id)
                stmt.executeUpdate()
            }
        }

        assertThatThrownBy {
            worklogService.update(
                actor = ACTOR,
                issueKey = IssueKey(issueKey),
                worklogId = worklog.id,
                timeSpentSeconds = H4,
                startedAt = null,
                comment = null,
            )
        }.isInstanceOf(IssueNotFoundException::class.java)
    }

    // ── (10) CONCERN-1 순차 2건 누적 정확성 ──────────────────────────────────────────

    /**
     * (10) CONCERN-1 회귀가드 — 순차 2건 create.
     *
     * Given  remaining=8h 이슈
     * When   2h worklog 순차 2건 추가
     * Then   time_spent=4h, remaining=4h (8h - 2h - 2h).
     * 원자 SQL SUM 이 read-modify-write 가 아닌 집계 기반임을 단언한다.
     */
    @Test
    fun `(10) CONCERN-1 순차 2건 — time_spent=4h remaining=4h`() {
        val issueKey = insertIssue(remainingSeconds = H8)

        worklogService.create(
            actor = ACTOR,
            issueKey = IssueKey(issueKey),
            timeSpentSeconds = H2,
            startedAt = Instant.now(),
            comment = "1번째",
            newRemainingEstimateSeconds = null,
        )

        worklogService.create(
            actor = ACTOR,
            issueKey = IssueKey(issueKey),
            timeSpentSeconds = H2,
            startedAt = Instant.now(),
            comment = "2번째",
            newRemainingEstimateSeconds = null,
        )

        val list = worklogService.listForIssue(ACTOR, IssueKey(issueKey))
        assertThat(list.timeSpentSeconds).isEqualTo(H4)
        assertThat(list.remainingEstimateSeconds).isEqualTo(H4)
    }

    // ── 아카이브 잠금 (FR-PJ-04 PR-4 Task 9) ────────────────────────────────────────

    /**
     * 쓰기 4종(create/createImported/update/delete)의 아카이브 잠금 판별자.
     *
     * 각 메서드마다 (아카이브된 프로젝트 이슈 → [ProjectArchivedException]) + (활성 프로젝트 이슈 → 2xx,
     * C2 판별자 baseline)을 짝짓는다. `WLARCH` 는 [seedProject] 가 `archived_at NOT NULL` 로 시딩한다.
     */
    @Test
    fun `아카이브 잠금 — create 는 아카이브된 프로젝트 이슈에 409(ProjectArchivedException)`() {
        val archivedIssueKey = insertIssueInArchivedProject()

        assertThatThrownBy {
            worklogService.create(
                actor = ACTOR,
                issueKey = IssueKey(archivedIssueKey),
                timeSpentSeconds = H2,
                startedAt = Instant.now(),
                comment = "잠금 확인",
                newRemainingEstimateSeconds = null,
            )
        }.isInstanceOf(ProjectArchivedException::class.java)
    }

    @Test
    fun `아카이브 잠금 — create 는 활성 프로젝트 이슈에 2xx (판별자 baseline)`() {
        val activeIssueKey = insertIssue(remainingSeconds = H8)

        val worklog =
            worklogService.create(
                actor = ACTOR,
                issueKey = IssueKey(activeIssueKey),
                timeSpentSeconds = H2,
                startedAt = Instant.now(),
                comment = "잠금 확인",
                newRemainingEstimateSeconds = null,
            )

        assertThat(worklog.timeSpentSeconds).isEqualTo(H2)
    }

    @Test
    fun `아카이브 잠금 — createImported 는 아카이브된 프로젝트 이슈에 409(ProjectArchivedException)`() {
        val archivedIssueKey = insertIssueInArchivedProject()

        assertThatThrownBy {
            worklogService.createImported(
                actor = ACTOR,
                issueKey = IssueKey(archivedIssueKey),
                authorId = OTHER_ACTOR,
                timeSpentSeconds = H2,
                startedAt = Instant.now(),
                comment = "import 잠금 확인",
            )
        }.isInstanceOf(ProjectArchivedException::class.java)
    }

    @Test
    fun `아카이브 잠금 — createImported 는 활성 프로젝트 이슈에 2xx (판별자 baseline)`() {
        val activeIssueKey = insertIssue(remainingSeconds = H8)

        val worklog =
            worklogService.createImported(
                actor = ACTOR,
                issueKey = IssueKey(activeIssueKey),
                authorId = OTHER_ACTOR,
                timeSpentSeconds = H2,
                startedAt = Instant.now(),
                comment = "import 잠금 확인",
            )

        assertThat(worklog.authorId).isEqualTo(OTHER_ACTOR_UUID)
    }

    @Test
    fun `아카이브 잠금 — update 는 아카이브된 프로젝트 이슈에 409(ProjectArchivedException)`() {
        val archivedIssueKey = insertIssueInArchivedProject()
        // update 대상 worklog 확보 — checkPermission/archiveGuard 가 먼저 걸리므로 실제로는 worklog 미존재라도
        // ProjectArchivedException 이 findById 이전에 던져진다(D-ORDER).
        assertThatThrownBy {
            worklogService.update(
                actor = ACTOR,
                issueKey = IssueKey(archivedIssueKey),
                worklogId = UUID.randomUUID(),
                timeSpentSeconds = H2,
                startedAt = null,
                comment = null,
            )
        }.isInstanceOf(ProjectArchivedException::class.java)
    }

    @Test
    fun `아카이브 잠금 — update 는 활성 프로젝트 이슈에 2xx (판별자 baseline)`() {
        val activeIssueKey = insertIssue(remainingSeconds = H8)
        val worklog =
            worklogService.create(
                actor = ACTOR,
                issueKey = IssueKey(activeIssueKey),
                timeSpentSeconds = H2,
                startedAt = Instant.now(),
                comment = "원본",
                newRemainingEstimateSeconds = null,
            )

        val updated =
            worklogService.update(
                actor = ACTOR,
                issueKey = IssueKey(activeIssueKey),
                worklogId = worklog.id,
                timeSpentSeconds = H4,
                startedAt = null,
                comment = null,
            )

        assertThat(updated.timeSpentSeconds).isEqualTo(H4)
    }

    @Test
    fun `아카이브 잠금 — delete 는 아카이브된 프로젝트 이슈에 409(ProjectArchivedException)`() {
        val archivedIssueKey = insertIssueInArchivedProject()

        assertThatThrownBy {
            worklogService.delete(
                actor = ACTOR,
                issueKey = IssueKey(archivedIssueKey),
                worklogId = UUID.randomUUID(),
            )
        }.isInstanceOf(ProjectArchivedException::class.java)
    }

    @Test
    fun `아카이브 잠금 — delete 는 활성 프로젝트 이슈에 2xx (판별자 baseline)`() {
        val activeIssueKey = insertIssue(remainingSeconds = H8)
        val worklog =
            worklogService.create(
                actor = ACTOR,
                issueKey = IssueKey(activeIssueKey),
                timeSpentSeconds = H2,
                startedAt = Instant.now(),
                comment = "삭제 대상",
                newRemainingEstimateSeconds = null,
            )

        worklogService.delete(actor = ACTOR, issueKey = IssueKey(activeIssueKey), worklogId = worklog.id)

        val list = worklogService.listForIssue(ACTOR, IssueKey(activeIssueKey))
        assertThat(list.worklogs).isEmpty()
    }

    // ── private helpers ───────────────────────────────────────────────────────────

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

    private fun seedProject() {
        conn().use { c ->
            c.prepareStatement(
                "INSERT INTO projects (key, name) VALUES (?, ?) ON CONFLICT (key) DO NOTHING",
            ).use { stmt ->
                stmt.setString(1, PROJECT_KEY)
                stmt.setString(2, "Worklog Service Test Project")
                stmt.executeUpdate()
            }
            // FR-PJ-04 PR-4 Task 9 — 아카이브된 프로젝트 (archived_at NOT NULL) 시딩.
            c.prepareStatement(
                "INSERT INTO projects (key, name, archived_at) VALUES (?, ?, NOW()) " +
                    "ON CONFLICT (key) DO UPDATE SET archived_at = NOW()",
            ).use { stmt ->
                stmt.setString(1, ARCHIVED_PROJECT_KEY)
                stmt.setString(2, "Worklog Service Archived Test Project")
                stmt.executeUpdate()
            }
        }
    }

    /**
     * [ARCHIVED_PROJECT_KEY] 소속 이슈를 1건 삽입하고 이슈 키를 반환한다 (FR-PJ-04 PR-4 Task 9 전용).
     *
     * [insertIssue] 와 동형이나 project 를 [ARCHIVED_PROJECT_KEY] 로 고정한다.
     */
    private fun insertIssueInArchivedProject(): String =
        conn().use { c ->
            c.autoCommit = false

            val seq =
                c.prepareStatement(
                    "UPDATE projects SET key_sequence = key_sequence + 1 WHERE key = ? RETURNING key_sequence",
                ).use { stmt ->
                    stmt.setString(1, ARCHIVED_PROJECT_KEY)
                    stmt.executeQuery().use { rs ->
                        rs.next()
                        rs.getLong(1)
                    }
                }

            val issueKey = "$ARCHIVED_PROJECT_KEY-$seq"
            val projectId =
                c.prepareStatement("SELECT id FROM projects WHERE key = ?").use { stmt ->
                    stmt.setString(1, ARCHIVED_PROJECT_KEY)
                    stmt.executeQuery().use { rs ->
                        rs.next()
                        rs.getObject(1) as UUID
                    }
                }
            val taskTypeId =
                c.prepareStatement(
                    "SELECT id FROM issue_types WHERE key = 'task' AND deleted_at IS NULL LIMIT 1",
                ).use { stmt ->
                    stmt.executeQuery().use { rs ->
                        check(rs.next()) { "task 타입 없음 — V003 마이그레이션 확인 필요." }
                        rs.getLong(1)
                    }
                }

            c.prepareStatement(
                "INSERT INTO issues " +
                    "(key, project_id, summary, reporter_id, current_state_key, version, type_id, " +
                    "remaining_estimate_seconds) VALUES (?, ?, ?, ?, 'open', 1, ?, ?)",
            ).use { stmt ->
                stmt.setString(1, issueKey)
                stmt.setObject(2, projectId)
                stmt.setString(3, "아카이브 잠금 테스트 이슈")
                stmt.setObject(4, ACTOR_UUID)
                stmt.setLong(5, taskTypeId)
                stmt.setInt(6, H8)
                stmt.executeUpdate()
            }

            c.commit()
            issueKey
        }

    /**
     * 이슈를 직접 삽입하고 이슈 키를 반환한다.
     *
     * @param remainingSeconds remaining_estimate_seconds. null 이면 미추정.
     */
    @Suppress("NestedBlockDepth", "LongMethod")
    private fun insertIssue(remainingSeconds: Int?): String =
        conn().use { c ->
            c.autoCommit = false

            val seq =
                c.prepareStatement(
                    "UPDATE projects SET key_sequence = key_sequence + 1 WHERE key = ? RETURNING key_sequence",
                ).use { stmt ->
                    stmt.setString(1, PROJECT_KEY)
                    stmt.executeQuery().use { rs ->
                        rs.next()
                        rs.getLong(1)
                    }
                }

            val issueKey = "$PROJECT_KEY-$seq"
            val projectId =
                c.prepareStatement("SELECT id FROM projects WHERE key = ?").use { stmt ->
                    stmt.setString(1, PROJECT_KEY)
                    stmt.executeQuery().use { rs ->
                        rs.next()
                        rs.getObject(1) as UUID
                    }
                }
            val taskTypeId =
                c.prepareStatement(
                    "SELECT id FROM issue_types WHERE key = 'task' AND deleted_at IS NULL LIMIT 1",
                ).use { stmt ->
                    stmt.executeQuery().use { rs ->
                        check(rs.next()) { "task 타입 없음 — V003 마이그레이션 확인 필요." }
                        rs.getLong(1)
                    }
                }

            if (remainingSeconds != null) {
                c.prepareStatement(
                    "INSERT INTO issues " +
                        "(key, project_id, summary, reporter_id, current_state_key, version, type_id, " +
                        "remaining_estimate_seconds) VALUES (?, ?, ?, ?, 'open', 1, ?, ?)",
                ).use { stmt ->
                    stmt.setString(1, issueKey)
                    stmt.setObject(2, projectId)
                    stmt.setString(3, "워크로그 서비스 테스트 이슈")
                    stmt.setObject(4, ACTOR_UUID)
                    stmt.setLong(5, taskTypeId)
                    stmt.setInt(6, remainingSeconds)
                    stmt.executeUpdate()
                }
            } else {
                c.prepareStatement(
                    "INSERT INTO issues " +
                        "(key, project_id, summary, reporter_id, current_state_key, version, type_id) " +
                        "VALUES (?, ?, ?, ?, 'open', 1, ?)",
                ).use { stmt ->
                    stmt.setString(1, issueKey)
                    stmt.setObject(2, projectId)
                    stmt.setString(3, "워크로그 서비스 테스트 이슈")
                    stmt.setObject(4, ACTOR_UUID)
                    stmt.setLong(5, taskTypeId)
                    stmt.executeUpdate()
                }
            }

            c.commit()
            issueKey
        }

    /** 이슈 키로 issues.id 를 조회한다 (이력 검증용). */
    private fun fetchIssueId(issueKey: String): UUID =
        conn().use { c ->
            c.prepareStatement("SELECT id FROM issues WHERE key = ?").use { stmt ->
                stmt.setString(1, issueKey)
                stmt.executeQuery().use { rs ->
                    check(rs.next()) { "이슈 미존재: $issueKey" }
                    rs.getObject(1) as UUID
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
