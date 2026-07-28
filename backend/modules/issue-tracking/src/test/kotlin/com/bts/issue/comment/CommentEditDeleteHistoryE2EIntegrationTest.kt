// 댓글 수정→삭제→이력 마스킹 관통 E2E 통합 테스트 — 컨트롤러부터 실 PostgreSQL 이력 조회까지 (FR-CO-02)

package com.bts.issue.comment

import com.bts.issue.adapter.inbound.rest.IssueControllerTransitionIntegrationTest.TestConfig
import com.bts.issue.adapter.outbound.AlwaysAllowIssuePermissionResolver
import com.bts.issue.application.ChangelogGroupView
import com.bts.issue.application.IssueApplicationService
import com.bts.issue.application.IssueChangelogService
import com.bts.issue.comment.application.CommentApplicationService
import com.bts.issue.comment.repository.CommentRepository
import com.bts.issue.comment.web.CommentController
import com.bts.issue.comment.web.CommentExceptionHandler
import com.bts.issue.domain.ActorId
import com.bts.issue.domain.IssueKey
import com.bts.issue.event.IssueEventPublisher
import com.bts.issue.history.IssueChangeDetector
import com.bts.issue.history.IssueChangeHistoryRepository
import com.bts.issue.history.IssueChangeItem
import com.bts.issue.history.IssueChangeLabelResolver
import com.bts.issue.history.IssueHistoryRecorder
import com.bts.issue.history.JdbcIssueChangeHistoryRepository
import com.bts.issue.project.archive.ProjectArchiveGuard
import com.bts.issue.project.archive.repository.ProjectArchiveStateRepository
import com.bts.issue.repository.IssueRepository
import com.bts.shared.user.UserLookupPort
import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
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
import org.springframework.data.domain.PageRequest
import org.springframework.http.MediaType
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.junit.jupiter.SpringExtension
import org.springframework.test.context.web.WebAppConfiguration
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import java.sql.Connection
import java.sql.DriverManager
import java.time.Clock
import java.util.UUID

/**
 * 댓글 수정 → 삭제 → 이력 조회 마스킹을 **한 테스트로 관통**하는 E2E 통합 테스트 (FR-CO-02).
 *
 * ## 왜 이 파일이 필요한가 — 기존 댓글 테스트 3종이 못 덮는 구간
 * | 기존 파일 | 성격 | 못 덮는 것 |
 * |---|---|---|
 * | `comment/web/CommentControllerIntegrationTest` | MockMvc 슬라이스(서비스 stub) | 서비스 아래 전부 |
 * | `comment/application/CommentApplicationServiceTest` | mock | 실 DB 기록·조회 |
 * | `comment/repository/CommentRepositoryTest` | 실 DB, 리포지토리 단층 | 이력 기록·마스킹 |
 *
 * 각 층은 개별 검증되지만 **컨트롤러 → 서비스 → 리포지토리 → [IssueHistoryRecorder] →
 * `issue_change_group`/`issue_change_item` → 조회 시 마스킹** 이 이어붙은 상태로 실 PostgreSQL 을
 * 통과한 적이 없었다. 특히 "삭제 후 이력 본문이 실제로 가려지는가" 는 mock 위에서만 단정돼 있었다.
 *
 * ## 배선 — [TestConfig] 재사용 + 필요한 빈만 추가
 * `history/IssueChangeHistoryE2EIntegrationTest` 를 선례로 삼아 같은 singleton Testcontainers
 * [TestConfig] (Testcontainers = 테스트용 DB 를 도커로 자동 실행하는 라이브러리) 를 재사용하고,
 * [CommentHistoryE2EConfig] 로 댓글 경로의 실 빈만 추가 wire 한다.
 *
 * ## 관통 범위의 경계
 * 쓰기(작성·수정·삭제)는 **MockMvc 로 실제 REST 요청**을 보내 컨트롤러부터 태운다.
 * 읽기는 [IssueChangelogService.findChangelog] 를 직접 호출한다 — 마스킹이 사는 계층이 거기이고,
 * changelog 엔드포인트는 `IssueController` 안에 있어 [TestConfig] 의 컨트롤러 빈과 중복 매핑이
 * 되기 때문이다(같은 핸들러 2개 등록 → 컨텍스트 기동 실패).
 */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(
    classes = [
        TestConfig::class,
        CommentEditDeleteHistoryE2EIntegrationTest.CommentHistoryE2EConfig::class,
    ],
)
@WebAppConfiguration
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CommentEditDeleteHistoryE2EIntegrationTest {
    /**
     * 댓글 쓰기 경로 + 이력 조회 경로의 실 빈을 추가로 wire 하는 보조 설정.
     *
     * [TestConfig] 는 `historyRecorder` 를 relaxed mock 으로 두므로 이력이 DB 에 남지 않는다.
     * 여기서 [IssueHistoryRecorder] 와 [JdbcIssueChangeHistoryRepository] 를 실물로 올려
     * 댓글 수정 이력이 실제 테이블에 쓰이게 한다.
     */
    @Configuration
    open class CommentHistoryE2EConfig {
        @Bean
        open fun commentE2ENamedParameterJdbcTemplate(dataSource: DriverManagerDataSource): NamedParameterJdbcTemplate =
            NamedParameterJdbcTemplate(dataSource)

        @Bean
        open fun commentE2EChangeHistoryRepository(jdbc: NamedParameterJdbcTemplate): IssueChangeHistoryRepository =
            JdbcIssueChangeHistoryRepository(jdbc)

        @Bean
        open fun commentE2EChangeDetector(): IssueChangeDetector = IssueChangeDetector()

        /**
         * 라벨 resolver 는 **strict mock** 이다 — 스텁을 하나도 등록하지 않았으므로 호출되는 순간 실패한다.
         *
         * [IssueHistoryRecorder.recordCommentEdited] 는 detector 도 resolver 도 경유하지 않고
         * repository 에 직접 위임한다. 그 사실을 KDoc 이 아니라 **실패로** 고정하려고 relaxed 가 아닌
         * strict 를 쓴다. 누군가 댓글 이력 경로에 라벨 해석을 끼워 넣으면 이 테스트가 즉시 깨진다.
         */
        @Bean
        open fun commentE2ELabelResolver(): IssueChangeLabelResolver = mockk()

        @Bean
        open fun commentE2EHistoryRecorder(
            detector: IssueChangeDetector,
            resolver: IssueChangeLabelResolver,
            repository: IssueChangeHistoryRepository,
        ): IssueHistoryRecorder = IssueHistoryRecorder(detector, resolver, repository)

        @Bean
        open fun commentE2ECommentRepository(dsl: DSLContext): CommentRepository = CommentRepository(dsl)

        @Bean
        open fun commentE2EProjectArchiveStateRepository(dsl: DSLContext): ProjectArchiveStateRepository =
            ProjectArchiveStateRepository(dsl)

        @Bean
        open fun commentE2EProjectArchiveGuard(repository: ProjectArchiveStateRepository): ProjectArchiveGuard =
            ProjectArchiveGuard(repository)

        @Bean
        @Suppress("LongParameterList") // 서비스 생성자 인자 그대로 — 조립 빈 메서드는 분리 불가
        open fun commentE2EApplicationService(
            commentRepository: CommentRepository,
            issueRepository: IssueRepository,
            permissionResolver: AlwaysAllowIssuePermissionResolver,
            eventPublisher: IssueEventPublisher,
            archiveGuard: ProjectArchiveGuard,
            historyRecorder: IssueHistoryRecorder,
            clock: Clock,
        ): CommentApplicationService =
            CommentApplicationService(
                commentRepository = commentRepository,
                issueRepository = issueRepository,
                permissionResolver = permissionResolver,
                eventPublisher = eventPublisher,
                archiveGuard = archiveGuard,
                historyRecorder = historyRecorder,
                clock = clock,
            )

        @Bean
        open fun commentE2EController(svc: CommentApplicationService): CommentController = CommentController(svc)

        @Bean
        open fun commentE2EExceptionHandler(): CommentExceptionHandler = CommentExceptionHandler()

        @Bean
        open fun commentE2EChangelogService(
            issueApplicationService: IssueApplicationService,
            changeHistoryRepository: IssueChangeHistoryRepository,
            userLookupPort: UserLookupPort,
            issueRepository: IssueRepository,
            commentRepository: CommentRepository,
        ): IssueChangelogService =
            IssueChangelogService(
                issueApplicationService = issueApplicationService,
                changeHistoryRepository = changeHistoryRepository,
                userLookupPort = userLookupPort,
                issueRepository = issueRepository,
                commentRepository = commentRepository,
            )
    }

    @Autowired
    lateinit var webApplicationContext: WebApplicationContext

    @Autowired
    lateinit var changelogService: IssueChangelogService

    @Autowired
    lateinit var objectMapper: ObjectMapper

    lateinit var mockMvc: MockMvc

    companion object {
        /** 이 테스트 전용 프로젝트 키. 다른 통합 테스트와 같은 컨테이너를 쓰므로 데이터를 이 접두사로 격리한다. */
        private const val PROJECT_KEY = "COMHIST"

        /** 댓글 작성자 = 수정자 = 삭제자. CurrentActor 가 읽는 SecurityContext principal 과 같은 값이어야 한다. */
        private const val ACTOR_UUID = "11111111-1111-4111-8111-111111111111"

        /** 수정 전 본문. 이력 `from_value` 에 남아야 한다. */
        private const val BODY_BEFORE = "수정 전 본문 — 무해합니다"

        /** 수정 후 본문. 이력 `to_value` 에 남았다가 삭제 후 가려져야 한다. */
        private const val BODY_AFTER = "수정 후 본문 — 이게 삭제 후 가려져야 한다"

        private var bootstrapped = false
    }

    private val actor = ActorId(UUID.fromString(ACTOR_UUID))

    @BeforeAll
    fun setUpAll() {
        if (bootstrapped) return
        applyMigrations()
        seedProject()
        bootstrapped = true
    }

    @BeforeEach
    fun setUpEach() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build()
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(
                ACTOR_UUID,
                null,
                listOf(SimpleGrantedAuthority("ROLE_USER")),
            )
        cleanOwnData()
    }

    @AfterEach
    fun clearSecurityContext() {
        SecurityContextHolder.clearContext()
    }

    // ── 관통 시나리오 ─────────────────────────────────────────────────────────

    /**
     * 댓글 작성 → 수정 → 이력 기록 확인 → 삭제 → 이력 본문 마스킹까지 한 번에 관통한다.
     *
     * Given  실 PostgreSQL 에 이슈 1건 + REST 로 작성한 댓글 1건
     * When   `PATCH /api/v1/issues/{key}/comments/{commentId}` 로 본문을 A→B 로 수정
     * Then   `issue_change_item` 에 `field="comment:{id}"`, from=A, to=B 가 기록되고
     *        조회 API 에서도 그 값이 보인다 (삭제 전이므로 마스킹 없음)
     * When   `DELETE /api/v1/issues/{key}/comments/{commentId}` 로 소프트 삭제
     * Then   같은 이력 항목이 **목록에는 남되 값·라벨 4종이 전부 null 로 가려진다**
     * And    DB 원본 행은 그대로다 (append-only 감사 이력 — 지우는 게 아니라 조회 시 가린다)
     *
     * 삭제 전 단언이 있어야 삭제 후의 null 이 "원래 항상 null 이었다" 와 구분된다 — 비-공허 판별자.
     */
    @Test
    @Suppress("LongMethod") // 관통 시나리오 1건. 단계를 쪼개면 "이어붙인 상태" 를 검증하지 못한다.
    fun `댓글을 수정하면 이력에 본문이 남고 삭제하면 그 본문이 조회 시 가려진다`() {
        // ── 1. 이슈 1건 픽스처 ────────────────────────────────────────────────
        val issueKey = insertIssue()

        // ── 2. 댓글 작성 (컨트롤러 경유) ────────────────────────────────────────
        val createResult =
            mockMvc.perform(
                post("/api/v1/issues/$issueKey/comments")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"body":"$BODY_BEFORE"}"""),
            ).andExpect(status().isCreated)
                .andReturn()
        val commentId = readCommentId(createResult.response.contentAsString)

        // ── 3. 댓글 수정 (컨트롤러 경유) ────────────────────────────────────────
        mockMvc.perform(
            patch("/api/v1/issues/$issueKey/comments/$commentId")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"body":"$BODY_AFTER"}"""),
        ).andExpect(status().isOk)

        // ── 4. 삭제 전 이력 — from/to 에 실제 본문이 보인다 ──────────────────────
        val beforeDelete = fetchCommentHistoryItem(issueKey, commentId)
        assertThat(beforeDelete)
            .describedAs("댓글 수정 이력이 실 DB 에 기록되고 조회 경로로 돌아와야 한다")
            .isNotNull
        assertThat(beforeDelete?.fromValue)
            .describedAs("삭제 전에는 수정 전 본문이 그대로 보인다")
            .isEqualTo(BODY_BEFORE)
        assertThat(beforeDelete?.toValue)
            .describedAs("삭제 전에는 수정 후 본문이 그대로 보인다")
            .isEqualTo(BODY_AFTER)

        // ── 5. 댓글 삭제 (컨트롤러 경유) ────────────────────────────────────────
        mockMvc.perform(delete("/api/v1/issues/$issueKey/comments/$commentId"))
            .andExpect(status().isNoContent)

        // ── 6. 삭제 후 이력 — 항목은 남고 값·라벨만 가려진다 ──────────────────────
        val afterDelete = fetchCommentHistoryItem(issueKey, commentId)
        assertThat(afterDelete)
            .describedAs("항목 자체는 남아야 한다 — '삭제된 댓글이 수정된 적 있다' 는 사실은 감사 대상")
            .isNotNull
        assertThat(afterDelete?.fromValue)
            .describedAs("삭제 후에는 수정 전 본문이 가려져야 한다")
            .isNull()
        assertThat(afterDelete?.toValue)
            .describedAs("삭제 후에는 수정 후 본문이 가려져야 한다")
            .isNull()
        assertThat(afterDelete?.fromLabel).isNull()
        assertThat(afterDelete?.toLabel).isNull()

        // ── 7. DB 원본은 보존 — 지우는 게 아니라 조회 시 가리는 것이다 ─────────────
        val (rawFrom, rawTo) = fetchRawHistoryValues(commentId)
        assertThat(rawFrom)
            .describedAs("append-only 감사 이력이므로 DB 행의 from_value 는 그대로여야 한다")
            .isEqualTo(BODY_BEFORE)
        assertThat(rawTo)
            .describedAs("append-only 감사 이력이므로 DB 행의 to_value 는 그대로여야 한다")
            .isEqualTo(BODY_AFTER)
    }

    // ── private helpers ───────────────────────────────────────────────────────

    /** `{"data":{"id":"..."}}` 응답에서 댓글 UUID 를 꺼낸다. */
    private fun readCommentId(responseBody: String): UUID {
        val idText =
            objectMapper.readTree(responseBody).path("data").path("id").asText(null)
                ?: error("댓글 작성 응답에 data.id 가 없습니다: $responseBody")
        return UUID.fromString(idText)
    }

    /**
     * 이력 조회 경로([IssueChangelogService.findChangelog])를 태워 해당 댓글의 변경 항목을 찾는다.
     *
     * 마스킹은 이 경로 안에서 일어난다 — 리포지토리를 직접 읽으면 마스킹을 건너뛰므로 의미가 없다.
     */
    private fun fetchCommentHistoryItem(
        issueKey: String,
        commentId: UUID,
    ): IssueChangeItem? {
        val page = changelogService.findChangelog(actor, IssueKey(issueKey), PageRequest.of(0, PAGE_SIZE))
        return page.content
            .flatMap(ChangelogGroupView::items)
            .find { it.field == "${IssueHistoryRecorder.COMMENT_FIELD_PREFIX}$commentId" }
    }

    /** 마스킹을 우회해 `issue_change_item` 원본 행의 from/to 를 직접 읽는다. */
    @Suppress("NestedBlockDepth")
    private fun fetchRawHistoryValues(commentId: UUID): Pair<String?, String?> =
        conn().use { c ->
            c.prepareStatement(
                "SELECT from_value, to_value FROM issue_change_item WHERE field = ?",
            ).use { stmt ->
                stmt.setString(1, "${IssueHistoryRecorder.COMMENT_FIELD_PREFIX}$commentId")
                stmt.executeQuery().use { rs ->
                    check(rs.next()) { "issue_change_item 에 댓글 이력 행이 없습니다 (commentId=$commentId)." }
                    rs.getString(1) to rs.getString(2)
                }
            }
        }

    /**
     * 이 테스트가 만든 데이터만 지운다.
     *
     * 같은 JVM singleton 컨테이너를 다른 통합 테스트와 공유하므로 테이블 전체 `DELETE` 를 쓰지 않는다 —
     * 동시 실행 중인 형제 테스트의 행까지 날아간다.
     */
    private fun cleanOwnData() {
        conn().use { c ->
            c.createStatement().use { stmt ->
                stmt.execute(
                    "DELETE FROM issue_change_item WHERE group_id IN " +
                        "(SELECT id FROM issue_change_group WHERE issue_key LIKE '$PROJECT_KEY-%')",
                )
                stmt.execute("DELETE FROM issue_change_group WHERE issue_key LIKE '$PROJECT_KEY-%'")
                stmt.execute(
                    "DELETE FROM comments WHERE issue_id IN " +
                        "(SELECT id FROM issues WHERE key LIKE '$PROJECT_KEY-%')",
                )
                stmt.execute("DELETE FROM issues WHERE key LIKE '$PROJECT_KEY-%'")
                stmt.execute("UPDATE projects SET key_sequence = 0 WHERE key = '$PROJECT_KEY'")
            }
        }
    }

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

    /** 이 테스트 전용 프로젝트 1건을 시드한다. */
    private fun seedProject() {
        conn().use { c ->
            c.prepareStatement(
                "INSERT INTO projects (key, name) VALUES (?, ?) ON CONFLICT (key) DO NOTHING",
            ).use { stmt ->
                stmt.setString(1, PROJECT_KEY)
                stmt.setString(2, "Comment History E2E Test Project")
                stmt.executeUpdate()
            }
        }
    }

    /**
     * 시나리오용 이슈 1건을 SQL 로 직접 삽입하고 이슈 키를 반환한다.
     *
     * 이슈 **생성** 은 이 테스트가 검증하려는 구간이 아니라 픽스처다. 그래서
     * `IssueApplicationService.createIssue` 대신 SQL 삽입을 쓴다 —
     * `IssueControllerTransitionIntegrationTest` 가 같은 [TestConfig] 위에서 쓰는 방식과 같다.
     * (그 서비스는 [TestConfig] 에서 component/projectLead 저장소가 mock 이라 기본 담당자 해석이
     * nil UUID 를 돌려주고, 픽스처 준비 단계에서 무관한 실패를 낸다.)
     */
    private fun insertIssue(): String {
        val issueKey = "$PROJECT_KEY-1"
        conn().use { c ->
            c.prepareStatement(
                "INSERT INTO issues (key, project_id, summary, reporter_id, current_state_key, version, type_id) " +
                    "SELECT ?, p.id, ?, ?::uuid, 'open', 1, t.id " +
                    "FROM projects p, issue_types t " +
                    "WHERE p.key = ? AND t.key = 'task' AND t.deleted_at IS NULL",
            ).use { stmt ->
                stmt.setString(1, issueKey)
                stmt.setString(2, "댓글 이력 관통 시나리오 이슈")
                stmt.setString(3, ACTOR_UUID)
                stmt.setString(4, PROJECT_KEY)
                check(stmt.executeUpdate() == 1) { "이슈 픽스처 삽입 실패 — projects/issue_types 시드를 확인하세요." }
            }
        }
        return issueKey
    }

    private fun conn(): Connection =
        DriverManager.getConnection(
            TestConfig.postgres.jdbcUrl,
            TestConfig.postgres.username,
            TestConfig.postgres.password,
        )
}

/** 이력 조회 페이지 크기 — 이 시나리오는 그룹이 1건뿐이라 충분히 크게 잡는다. */
private const val PAGE_SIZE = 50
