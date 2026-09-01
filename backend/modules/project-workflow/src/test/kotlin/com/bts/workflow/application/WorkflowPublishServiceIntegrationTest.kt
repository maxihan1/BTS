// 발행 통합 테스트 — 발행 전 런타임 불변 · 캐시 무효화 · 버전 충돌 · 이관 필요 판정

package com.bts.workflow.application

import com.bts.shared.issue.IssueStatusMigrationPort
import com.bts.shared.issue.StatusMigrationCommand
import com.bts.shared.issue.StatusMigrationMapping
import com.bts.shared.permission.WorkflowDefinitionAccessDeniedException
import com.bts.shared.permission.WorkflowDefinitionPermission
import com.bts.shared.permission.WorkflowDefinitionPermissionResolver
import com.bts.workflow.application.port.IssueStatusUsagePort
import com.bts.workflow.cache.WorkflowCache
import com.bts.workflow.domain.DraftRuleDto
import com.bts.workflow.domain.DraftStateDto
import com.bts.workflow.domain.DraftTransitionDto
import com.bts.workflow.domain.WorkflowDraftDefinition
import com.bts.workflow.domain.exception.WorkflowInvalidRequestException
import com.bts.workflow.domain.exception.WorkflowMigrationInvalidMappingException
import com.bts.workflow.domain.exception.WorkflowNotFoundException
import com.bts.workflow.domain.exception.WorkflowPublishMappingRequiredException
import com.bts.workflow.domain.exception.WorkflowVersionConflictException
import com.bts.workflow.engine.DefaultWorkflowPostActionFactory
import com.bts.workflow.engine.DefaultWorkflowValidatorFactory
import com.bts.workflow.expression.SpelEvaluator
import com.bts.workflow.port.outbound.ActorId
import com.bts.workflow.port.outbound.PermissionResolver
import com.bts.workflow.port.outbound.Scope
import com.bts.workflow.postaction.PostActionRepository
import com.bts.workflow.repository.WorkflowDraftRepository
import com.bts.workflow.repository.WorkflowPublicationRepository
import com.bts.workflow.repository.WorkflowPublishRepository
import com.bts.workflow.repository.WorkflowRepository
import com.bts.workflow.scheme.repository.ProjectRef
import com.bts.workflow.scheme.repository.ProjectWorkflowSchemeAssignmentRepository
import com.bts.workflow.testsupport.insertWorkflowStatus
import com.bts.workflow.validator.ValidatorRepository
import com.bts.workflow.web.WorkflowDraftController
import com.bts.workflow.web.dto.MigrateRequest
import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.catchThrowable
import org.flywaydb.core.Flyway
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.Executors

/**
 * [WorkflowPublishService] Testcontainers 통합 테스트.
 *
 * ### 이 테스트가 지키는 가장 중요한 계약
 * **초안을 고쳐도 발행 전에는 런타임이 옛 정의를 본다.** FR-WF-07 이 존재하는 이유가 그것이고,
 * 여기서 깨지면 「편집 중간 상태가 운영에 샌다」는 원래 문제로 그대로 돌아간다.
 *
 * ### 조립을 손으로 한다
 * `@SpringBootTest` 대신 생성자 주입으로 조립한다. 이 서비스가 쓰는 협력자가 전부 DSLContext
 * 하나에서 나오고, 컨텍스트를 띄우면 시드 부트스트랩(`YamlSeedService`)이 함께 돌아 표준 4종이
 * 들어온다 — 그러면 「이 테스트가 만든 워크플로우」와 시드가 섞여 상태 집합 단언이 흔들린다.
 *
 * `WorkflowCache.withWriteLock` 은 `@Transactional` 이지만 프록시 없이 직접 호출된다.
 * `pg_try_advisory_xact_lock` 은 트랜잭션 밖이면 즉시 해제될 뿐 획득 자체는 성공하므로 동작에
 * 영향이 없다. 동시 발행은 lock 이 아니라 `workflows.version` 낙관적 락이 막고, 그것을 순차
 * 호출로 재현한다.
 *
 * 참조. FR-WF-07 D5 · ADR 2026-08-18-workflow-db-as-source-of-truth
 */
@Testcontainers
class WorkflowPublishServiceIntegrationTest {
    companion object {
        private val temboImage: DockerImageName =
            DockerImageName.parse("quay.io/tembo/pg16-pgmq:latest")
                .asCompatibleSubstituteFor("postgres")

        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer(temboImage)
                .withDatabaseName("bts_test")
                .withUsername("bts")
                .withPassword("bts_test")

        /** 순서가 load-bearing 이다 — issue_types 를 먼저 만들면 Flyway 가 non-empty 스키마를 거부한다. */
        @BeforeAll
        @JvmStatic
        fun applyMigrations() {
            flyway().target("200").load().migrate()
            createIssueTypes()
            flyway().load().migrate()
        }

        @JvmStatic
        private fun flyway() =
            Flyway.configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .placeholderReplacement(false)
                .locations(
                    "classpath:db/migration/issue-tracking",
                    "classpath:db/migration/project-workflow",
                )

        @JvmStatic
        private fun createIssueTypes() {
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.execute(
                        "CREATE TABLE IF NOT EXISTS issue_types (" +
                            "id BIGSERIAL PRIMARY KEY, key VARCHAR(30) NOT NULL UNIQUE, " +
                            "name VARCHAR(255) NOT NULL, is_standard BOOLEAN NOT NULL DEFAULT FALSE, " +
                            "created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), " +
                            "updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), deleted_at TIMESTAMPTZ)",
                    )
                }
            }
        }
    }

    /**
     * 상태별 이슈 수를 테스트가 정하는 스텁. 실제 어댑터는 issues 를 읽지만 그 테이블은 여기 없다.
     *
     * ### ★받은 `projectIds` 를 버리지 않고 기록한다
     * 인자를 그냥 무시하면 서비스가 빈 집합을 넘기든 남의 프로젝트를 넘기든 전 테스트가 초록이다.
     * 그러면 이 PR 의 이름이 「결선」인데 **결선의 인자 전달에 판정이 하나도 없게** 된다.
     */
    private class StubIssueStatusUsage : IssueStatusUsagePort {
        val counts = mutableMapOf<String, Long>()

        /** 호출마다 받은 프로젝트 스코프. 호출 순서대로 쌓인다. */
        val receivedProjectIds = mutableListOf<Set<UUID>>()

        /**
         * 상태별 **호출 순서** 응답. 앞에서부터 소비하고 바닥나면 [counts] 로 떨어진다.
         *
         * 교체 직후 유입(F10)을 흉내내는 유일한 방법이다 — 같은 상태를 두 번 세는데 두 번째만
         * 0 이 아니어야 「교체 전에는 비었고 그 사이 들어왔다」가 재현된다.
         */
        val queued = mutableMapOf<String, ArrayDeque<Long>>()

        override fun countIssuesInStatus(
            statusKey: String,
            projectIds: Set<UUID>,
        ): Long {
            receivedProjectIds += projectIds
            queued[statusKey]?.removeFirstOrNull()?.let { return it }
            return counts[statusKey] ?: 0
        }
    }

    /**
     * 이관 큐잉을 가로채 커맨드를 기록하는 스파이. 실물 어댑터는 issue-tracking 소유라 이 모듈에 없다.
     *
     * ### ★도착 상태를 카탈로그와 대조한다 — 실물 어댑터와 같은 축
     * `WorkflowStatusMigrationAdapter.requireReferencedKeysExist` 가 하는 일이다. 기록만 하는 스파이로
     * 두면 서비스에서 `requireStatusCatalog` 를 지워도 전 테스트가 초록이라 F12 판정이 공허해진다 —
     * 운영에서는 그 상태가 어댑터까지 가서 `IllegalArgumentException` 이 되고, 이 BC 의 advice 중
     * 그것을 잡는 것이 없어(`WorkflowExceptionHandler:168` 이 명시) **500** 이 나간다.
     *
     * 기록을 검증보다 **먼저** 한다 — 거부된 호출도 「포트에 닿았다」는 사실 자체가 판정 대상이다.
     */
    private class SpyStatusMigrationPort(
        private val publishRepository: WorkflowPublishRepository,
    ) : IssueStatusMigrationPort {
        /** 호출마다 받은 커맨드. 호출 순서대로 쌓인다. */
        val received = mutableListOf<StatusMigrationCommand>()

        override fun enqueueStatusMigration(cmd: StatusMigrationCommand): UUID {
            received += cmd
            val targets = cmd.mappings.map { it.toStatusKey }
            val known = publishRepository.findCatalogStatuses(targets).keys
            val missing = targets.filterNot { it in known }
            require(missing.isEmpty()) { "statusMigration target status keys not found: $missing" }
            return BULK_OPERATION_ID
        }
    }

    /**
     * 역방향 조회 횟수를 세는 저장소. NFR N1 「조회는 상태마다 반복하지 말고 1회만」을 잴 수 있게 한다.
     *
     * 세지 않으면 `removed.associateWith { … }` 안으로 조회가 들어가도(상태 수만큼 3단 JOIN) 결과가
     * 같아서 전 테스트가 초록이다 — N+1 은 값이 아니라 횟수로만 드러난다.
     */
    private class CountingSchemeAssignments(dsl: DSLContext) : ProjectWorkflowSchemeAssignmentRepository(dsl) {
        var lookupCount = 0

        override fun findProjectRefsByWorkflowId(workflowId: UUID): List<ProjectRef> {
            lookupCount++
            return super.findProjectRefsByWorkflowId(workflowId)
        }
    }

    private val dsl =
        DSL.using(
            DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password),
            SQLDialect.POSTGRES,
        )
    private val objectMapper = ObjectMapper()
    private val draftRepository = WorkflowDraftRepository(dsl, objectMapper)
    private val publishRepository = WorkflowPublishRepository(dsl)
    private val publicationRepository = WorkflowPublicationRepository(dsl, objectMapper)
    private val cache = WorkflowCache(WorkflowRepository(dsl), dsl)
    private val issueUsage = StubIssueStatusUsage()
    private val migrationPort = SpyStatusMigrationPort(publishRepository)
    private val schemeAssignments = CountingSchemeAssignments(dsl)
    private val permissions = SwitchableWorkflowPermissions()

    /**
     * 운영과 **같은 설정**의 웹 매퍼. Boot 의 `JacksonAutoConfiguration` 이 쓰는 빌더 그대로다.
     *
     * 손으로 만든 `ObjectMapper()` 는 `FAIL_ON_UNKNOWN_PROPERTIES` 가 켜져 있어 모르는 필드를 400 으로
     * 거절하지만, 이 빌더는 그것을 **끈다** — 운영은 모르는 필드를 조용히 버린다. 위조 차단 판정이
     * 그 차이 위에 서면 안 되므로 빌더를 직접 쓴다.
     */
    private val webMapper: ObjectMapper = Jackson2ObjectMapperBuilder.json().build()

    /**
     * ★ **실물 팩토리**로 조립한다. 스텁을 쓰면 「지원 type 의 정본은 팩토리」라는 계약이
     * 이 테스트에서만 성립하지 않게 되고, `CustomExpression` 이 실제로 만들어지는지를
     * 확인하지 못해 `isEditable` 관문이 공허해진다.
     */
    private val ruleGuard =
        TransitionRuleGuard(
            DefaultWorkflowValidatorFactory(
                object : PermissionResolver {
                    override fun hasPermission(
                        actorId: ActorId,
                        permission: String,
                        scope: Scope,
                    ): Boolean = true
                },
                SpelEvaluator(Executors.newSingleThreadExecutor()),
            ),
            DefaultWorkflowPostActionFactory(),
        )

    private val service =
        WorkflowPublishService(
            draftRepository = draftRepository,
            publishRepository = publishRepository,
            publicationRepository = publicationRepository,
            ruleWriter =
                DraftRuleWriter(
                    ValidatorRepository(dsl, objectMapper),
                    PostActionRepository(dsl, objectMapper),
                    ruleGuard,
                ),
            issueStatusUsagePort = issueUsage,
            issueStatusMigrationPort = migrationPort,
            schemeAssignmentRepository = schemeAssignments,
            permissionResolver = permissions,
            cache = cache,
        )

    /**
     * 위조 차단 판정 전용 — 요청 본문이 **실제로 흐르는 경로**를 재현한다.
     *
     * 초안 서비스는 이 경로에 쓰이지 않으므로 목으로 둔다. 컨트롤러를 끼우는 이유는 하나다 —
     * 서비스만 직접 부르면 「본문의 `projectKeys` 를 컨트롤러가 실어 보낸다」는 회귀를 못 잡는다.
     */
    private val controller = WorkflowDraftController(mockk(relaxed = true), service)

    /** [CurrentActor] 가 SecurityContext 를 읽으므로 컨트롤러를 부르는 동안만 채워 둔다. */
    private fun <T> withActor(
        actor: UUID,
        block: () -> T,
    ): T {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(actor.toString(), null, emptyList())
        return try {
            block()
        } finally {
            SecurityContextHolder.clearContext()
        }
    }

    // ── 픽스처 ────────────────────────────────────────────────────────────────

    /**
     * `open`·`done` 2상태 + 전환 2개를 가진 워크플로우를 심는다.
     *
     * @return 워크플로우 key. 테스트마다 달라야 캐시와 uq_workflows_key 가 서로 간섭하지 않는다.
     */
    private fun seedWorkflow(): String {
        val key = "wf-pub-${UUID.randomUUID().toString().take(8)}"
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            val workflowId =
                conn.createStatement().use { stmt ->
                    stmt.executeQuery(
                        "INSERT INTO workflows (key, name) VALUES ('$key', '발행 테스트') RETURNING id",
                    ).use { rs ->
                        rs.next()
                        UUID.fromString(rs.getString(1))
                    }
                }
            insertWorkflowStatus(conn, workflowId, "open", "열림 $key", "TODO", 0)
            insertWorkflowStatus(conn, workflowId, "done", "완료 $key", "DONE", 1)

            val ids = statusCompositionIds(conn, workflowId)
            insertTransition(conn, workflowId, "INITIAL", "이슈 생성", null, ids.getValue("open"))
            insertTransition(conn, workflowId, "NORMAL", "완료하기", ids.getValue("open"), ids.getValue("done"))
        }
        return key
    }

    private fun statusCompositionIds(
        conn: java.sql.Connection,
        workflowId: UUID,
    ): Map<String, UUID> {
        val result = mutableMapOf<String, UUID>()
        conn.createStatement().use { stmt ->
            stmt.executeQuery(
                "SELECT s.key, ws.id FROM workflow_statuses ws" +
                    " JOIN statuses s ON s.id = ws.status_id WHERE ws.workflow_id = '$workflowId'",
            ).use { rs ->
                while (rs.next()) result[rs.getString(1)] = UUID.fromString(rs.getString(2))
            }
        }
        return result
    }

    @Suppress("LongParameterList")
    private fun insertTransition(
        conn: java.sql.Connection,
        workflowId: UUID,
        kind: String,
        name: String,
        fromId: UUID?,
        toId: UUID,
    ) {
        val from = fromId?.let { "'$it'" } ?: "NULL"
        conn.createStatement().use {
            it.execute(
                "INSERT INTO workflow_transitions" +
                    " (workflow_id, kind, name, from_status_id, to_status_id, display_order)" +
                    " VALUES ('$workflowId', '$kind', '$name', $from, '$toId', 0)",
            )
        }
    }

    private fun workflowId(key: String): UUID = publishRepository.findLiveByKey(key)!!.id

    /** `open` 만 남기고 `done` 을 빼는 초안. 상태를 지우는 편집의 최소 형태다. */
    private fun draftWithoutDone(key: String) =
        WorkflowDraftDefinition(
            key = key,
            name = "이름을 바꿨다",
            states = listOf(DraftStateDto(key = "open", name = "열림 $key", category = "TODO", displayOrder = 0)),
            transitions = listOf(DraftTransitionDto(from = null, to = "open", name = "이슈 생성", kind = "INITIAL")),
        )

    private fun draftKeepingBoth(key: String) =
        WorkflowDraftDefinition(
            key = key,
            name = "이름만 바꿨다",
            states =
                listOf(
                    DraftStateDto(key = "open", name = "열림 $key", category = "TODO", displayOrder = 0),
                    DraftStateDto(key = "done", name = "완료 $key", category = "DONE", displayOrder = 1),
                ),
            transitions =
                listOf(
                    DraftTransitionDto(from = null, to = "open", name = "이슈 생성", kind = "INITIAL"),
                    DraftTransitionDto(
                        from = "open",
                        to = "done",
                        name = "완료하기",
                        validators =
                            listOf(DraftRuleDto(type = "RequiredField", config = mapOf("field" to "resolution"))),
                    ),
                ),
        )

    /**
     * `open`·`done` 을 그대로 두고 `완료하기` 전환에 **주어진 규칙만** 매단 초안.
     *
     * 규칙 관문 테스트가 쓴다 — 통과/거부의 차이가 규칙 하나에서만 오도록 나머지를 고정한다.
     */
    private fun draftWithRules(
        key: String,
        validators: List<DraftRuleDto> = emptyList(),
        postActions: List<DraftRuleDto> = emptyList(),
    ) = WorkflowDraftDefinition(
        key = key,
        name = "규칙 관문 테스트",
        states =
            listOf(
                DraftStateDto(key = "open", name = "열림 $key", category = "TODO", displayOrder = 0),
                DraftStateDto(key = "done", name = "완료 $key", category = "DONE", displayOrder = 1),
            ),
        transitions =
            listOf(
                DraftTransitionDto(from = null, to = "open", name = "이슈 생성", kind = "INITIAL"),
                DraftTransitionDto(
                    from = "open",
                    to = "done",
                    name = "완료하기",
                    validators = validators,
                    postActions = postActions,
                ),
            ),
    )

    /** 상태 키 하나가 전역 카탈로그에 없는 초안. 이관의 도착지가 카탈로그 밖일 때를 만든다. */
    private fun draftWithUnknownStatus(key: String) =
        WorkflowDraftDefinition(
            key = key,
            name = "카탈로그 밖 상태를 쓰는 초안",
            states = listOf(DraftStateDto(key = "없는상태", name = "미등록", category = "TODO", displayOrder = 0)),
            transitions = listOf(DraftTransitionDto(from = null, to = "없는상태", name = "생성", kind = "INITIAL")),
        )

    // ── 스킴 결선 픽스처 ──────────────────────────────────────────────────────

    /**
     * 이 워크플로우를 default 매핑으로 가리키는 스킴을 만들고 활성 프로젝트 2건을 붙인다.
     *
     * ### ★미끼를 함께 심는다
     * 다른 워크플로우를 가리키는 스킴에 프로젝트 1건을 붙여 두지 않으면 「저장소가 전체 프로젝트를
     * 돌려준다」와 「그 워크플로우의 프로젝트만 돌려준다」가 같은 결과가 되어 스코프 단언이 공허해진다.
     *
     * @return 이 워크플로우에 붙은 두 프로젝트의 id.
     */
    private fun attachTwoProjects(workflowId: UUID): Pair<UUID, UUID> {
        val scheme = insertScheme()
        insertDefaultMapping(scheme, workflowId)
        val alpha = insertProject()
        val beta = insertProject()
        insertAssignment(alpha, scheme)
        insertAssignment(beta, scheme)

        val decoyScheme = insertScheme()
        insertDefaultMapping(decoyScheme, insertBareWorkflow())
        insertAssignment(insertProject(), decoyScheme)

        return alpha to beta
    }

    private fun insertScheme(): Long {
        val key = "pub-scope-${UUID.randomUUID().toString().take(8)}"
        dsl.execute("INSERT INTO workflow_schemes (key, name) VALUES (?, ?)", key, "발행 스코프 $key")
        return dsl.fetchOne("SELECT id FROM workflow_schemes WHERE key = ?", key)
            ?.get("id", Long::class.java)
            ?: error("workflow_schemes INSERT 실패")
    }

    /** `issue_type_id` NULL = 그 스킴의 default 워크플로우. 타입별 매핑까지 심을 이유가 없다. */
    private fun insertDefaultMapping(
        schemeId: Long,
        workflowId: UUID,
    ) {
        dsl.execute(
            "INSERT INTO workflow_scheme_issue_type_mappings (scheme_id, issue_type_id, workflow_id)" +
                " VALUES (?, NULL, ?)",
            schemeId,
            workflowId,
        )
    }

    private fun insertProject(): UUID {
        val id = UUID.randomUUID()
        // projects.key 는 ^[A-Z][A-Z0-9]{1,9}$ 를 요구한다 — 앞자리를 문자로 고정한다.
        val key = "P" + UUID.randomUUID().toString().replace("-", "").take(8).uppercase()
        dsl.execute("INSERT INTO projects (id, key, name) VALUES (?, ?, ?)", id, key, "발행 스코프 $key")
        return id
    }

    /** 이관 커맨드가 싣는 것은 id 가 아니라 `projects.key` 다 — 픽스처가 무작위로 만들므로 되읽는다. */
    private fun projectKey(projectId: UUID): String =
        dsl.fetchOne("SELECT key FROM projects WHERE id = ?", projectId)
            ?.get("key", String::class.java)
            ?: error("projects 조회 실패 id=$projectId")

    private fun insertAssignment(
        projectId: UUID,
        schemeId: Long,
    ) {
        dsl.execute(
            "INSERT INTO project_workflow_scheme_assignments" +
                " (project_id, workflow_scheme_id, assigned_by) VALUES (?, ?, ?)",
            projectId,
            schemeId,
            ACTOR,
        )
    }

    /** 미끼 스킴이 가리킬 워크플로우. 상태도 전환도 없이 FK 만 만족시키면 된다. */
    private fun insertBareWorkflow(): UUID {
        val id = UUID.randomUUID()
        val key = "wf-decoy-${id.toString().take(8)}"
        dsl.execute("INSERT INTO workflows (id, key, name) VALUES (?, ?, ?)", id, key, "미끼 워크플로우")
        return id
    }

    /**
     * 전역 카탈로그(`statuses`)에만 상태를 심는다 — 이 워크플로우의 편성에는 **넣지 않는다.**
     *
     * F16 픽스처다. 카탈로그에 없으면 `requireStatusCatalog` 가 먼저 잡아 F16 판정까지 오지도
     * 못하므로, 「카탈로그에는 있고 live 편성에는 없다」는 상태를 일부러 만든다.
     *
     * ### ★키는 호출자가 유일하게 만들어 넘긴다
     * 카탈로그는 **컨테이너 전체가 공유하는 전역 표**다. `review` 같은 흔한 키를 고정으로 쓰면
     * 시드나 다른 테스트가 먼저 심어 둔 카테고리와 어긋나고, 그 순간 `requireStatesMatchCatalog`
     * 가 먼저 400 을 던져 **F16 판정에 도달조차 못 한다**(실측: `review` 는 `IN_PROGRESS` 였다).
     * 그래서 `ON CONFLICT` 로 덮지 않는다 — 덮으면 남의 상태를 조용히 바꾼다.
     */
    private fun insertCatalogOnlyStatus(
        statusKey: String,
        name: String,
    ) {
        dsl.execute("INSERT INTO statuses (key, name, category) VALUES (?, ?, 'TODO')", statusKey, name)
    }

    /** `done` 을 빼면서 **초안에만 있는** 신규 상태를 더하는 초안. 카테고리는 카탈로그와 맞춘다. */
    private fun draftAddingStatus(
        key: String,
        statusKey: String,
        statusName: String,
    ) = WorkflowDraftDefinition(
        key = key,
        name = "신규 상태를 더한 초안",
        states =
            listOf(
                DraftStateDto(key = "open", name = "열림 $key", category = "TODO", displayOrder = 0),
                DraftStateDto(key = statusKey, name = statusName, category = "TODO", displayOrder = 1),
            ),
        transitions = listOf(DraftTransitionDto(from = null, to = "open", name = "이슈 생성", kind = "INITIAL")),
    )

    /**
     * 이 워크플로우를 default 로 가리키면서 **형제 워크플로우도** 이슈 타입별로 가리키는 스킴.
     *
     * F11 이 막아야 하는 조합 그 자체다 — 이관 워커가 이슈 타입 축을 안 보므로 이 스킴에서
     * 이관을 허용하면 형제 워크플로우를 쓰는 이슈까지 함께 옮겨진다.
     */
    private fun attachSchemeWithSibling(workflowId: UUID) {
        val scheme = insertScheme()
        insertDefaultMapping(scheme, workflowId)

        val typeKey = "BUG-${UUID.randomUUID().toString().take(6)}"
        dsl.execute("INSERT INTO issue_types (key, name) VALUES (?, ?)", typeKey, "버그")
        val issueTypeId =
            dsl.fetchOne("SELECT id FROM issue_types WHERE key = ?", typeKey)
                ?.get("id", Long::class.java)
                ?: error("issue_types INSERT 실패")

        dsl.execute(
            "INSERT INTO workflow_scheme_issue_type_mappings (scheme_id, issue_type_id, workflow_id)" +
                " VALUES (?, ?, ?)",
            scheme,
            issueTypeId,
            insertBareWorkflow(),
        )
        insertAssignment(insertProject(), scheme)
    }

    // ── ★ 이 FR 의 심장 ───────────────────────────────────────────────────────

    @Test
    fun `초안을 고쳐도 발행 전에는 런타임이 옛 정의를 본다`() {
        val key = seedWorkflow()
        val before = cache.findByKey(key)!!

        draftRepository.upsert(workflowId(key), draftWithoutDone(key), baseVersion = 0, updatedBy = null)
        cache.invalidate(key)

        // 초안은 상태를 하나로 줄이고 이름도 바꿨다. 그럼에도 읽기 경로는 아무것도 달라지지 않아야 한다.
        val after = cache.findByKey(key)!!
        assertThat(after.states.map { it.key }).containsExactlyInAnyOrderElementsOf(before.states.map { it.key })
        assertThat(after.states).hasSize(2)
        assertThat(after.name).isEqualTo("발행 테스트")
    }

    // ── 발행 ──────────────────────────────────────────────────────────────────

    @Test
    fun `발행하면 정규 테이블이 초안대로 바뀐다`() {
        val key = seedWorkflow()
        draftRepository.upsert(workflowId(key), draftKeepingBoth(key), baseVersion = 0, updatedBy = null)

        service.publish(ACTOR, key, baseVersion = 0)

        val published = cache.findByKey(key)!!
        assertThat(published.name).isEqualTo("이름만 바꿨다")
        assertThat(published.transitions.map { it.name }).contains("완료하기")
    }

    @Test
    fun `발행 후 캐시가 갱신된다`() {
        val key = seedWorkflow()
        // 캐시를 먼저 데운다 — 무효화가 없으면 옛 값이 그대로 남는다.
        assertThat(cache.findByKey(key)!!.name).isEqualTo("발행 테스트")
        draftRepository.upsert(workflowId(key), draftKeepingBoth(key), baseVersion = 0, updatedBy = null)

        service.publish(ACTOR, key, baseVersion = 0)

        assertThat(cache.findByKey(key)!!.name).isEqualTo("이름만 바꿨다")
    }

    @Test
    fun `발행하면 초안이 사라진다`() {
        val key = seedWorkflow()
        draftRepository.upsert(workflowId(key), draftKeepingBoth(key), baseVersion = 0, updatedBy = null)

        service.publish(ACTOR, key, baseVersion = 0)

        assertThat(draftRepository.findByWorkflowId(workflowId(key))).isNull()
    }

    @Test
    fun `규칙이 초안대로 다시 심긴다`() {
        // 전환을 전량 교체하면 규칙이 CASCADE 로 사라진다. 재삽입이 빠지면 화면에서 걸어 둔
        // 검증기가 발행과 함께 조용히 없어진다.
        val key = seedWorkflow()
        draftRepository.upsert(workflowId(key), draftKeepingBoth(key), baseVersion = 0, updatedBy = null)

        service.publish(ACTOR, key, baseVersion = 0)

        val validators =
            dsl.fetchCount(
                DSL.table("workflow_validators v"),
                DSL.condition(
                    "v.transition_id IN (SELECT id FROM workflow_transitions WHERE workflow_id = ?)",
                    workflowId(key),
                ),
            )
        assertThat(validators).isEqualTo(1)
    }

    // ── 발행 이력 ─────────────────────────────────────────────────────────────

    @Test
    fun `발행할 때마다 이력 회차가 늘어난다`() {
        val key = seedWorkflow()
        val id = workflowId(key)

        draftRepository.upsert(id, draftKeepingBoth(key), baseVersion = 0, updatedBy = null)
        val first = service.publish(ACTOR, key, baseVersion = 0)

        draftRepository.upsert(id, draftKeepingBoth(key), baseVersion = 1, updatedBy = null)
        val second = service.publish(ACTOR, key, baseVersion = 1)

        assertThat(first).isEqualTo(1)
        assertThat(second).isEqualTo(2)
        assertThat(publicationRepository.countByWorkflowId(id)).isEqualTo(2)
    }

    // ── 낙관적 락 ─────────────────────────────────────────────────────────────

    @Test
    fun `그 사이 남이 먼저 발행했으면 409 다`() {
        val key = seedWorkflow()
        val id = workflowId(key)
        draftRepository.upsert(id, draftKeepingBoth(key), baseVersion = 0, updatedBy = null)
        service.publish(ACTOR, key, baseVersion = 0)

        // 두 번째 세션은 아직 옛 버전(0)을 들고 있다.
        draftRepository.upsert(id, draftKeepingBoth(key), baseVersion = 0, updatedBy = null)

        assertThatThrownBy { service.publish(ACTOR, key, baseVersion = 0) }
            .isInstanceOf(WorkflowVersionConflictException::class.java)
    }

    /**
     * 위 테스트는 요청 값과 저장된 값이 **둘 다 0** 이라 두 값이 갈리는 갈래를 밟지 않는다.
     * 여기서 그 갈래를 연다 — 낙관적 락의 기준은 **서버가 저장해 둔 초안의 base_version** 이고
     * 요청 본문 값이 아니다. 요청 값을 기준으로 삼으면 화면이 `preview` 의 `currentVersion` 을
     * 그대로 되실어 보내는 것만으로 락이 풀린다.
     */
    @Test
    fun `요청이 저장된 초안의 base_version 과 다르면 발행이 막힌다`() {
        val key = seedWorkflow()
        val id = workflowId(key)
        draftRepository.upsert(id, draftKeepingBoth(key), baseVersion = 0, updatedBy = null)

        // 남이 이름만 바꿔 version 이 올랐다. 초안 행은 그대로라 base_version 은 여전히 0 이다.
        dsl.execute("UPDATE workflows SET version = 1 WHERE key = ?", key)

        // 화면이 「다시 불러온」 값 1 을 실어 보낸다 — 초안 내용은 여전히 버전 0 을 보고 만든 것이다.
        assertThatThrownBy { service.publish(ACTOR, key, baseVersion = 1) }
            .isInstanceOf(WorkflowVersionConflictException::class.java)
    }

    // ── ★ 규칙 관문 — 발행은 편집 API 와 같은 문을 지난다 ─────────────────────
    //
    // 발행은 `workflow_validators` · `workflow_post_actions` 에 쓰는 **두 번째 경로**다.
    // 전용 편집 API 가 세운 관문(팩토리 dry-run · 웹훅 스킴 · 편집 가능 허용목록)을 지나지 않으면
    // 「초안 저장 → 발행」 두 번으로 그 관문이 통째로 우회된다.

    /**
     * `CustomExpression` 이 편집 허용목록에서 빠진 근거는 `expression/SpelEvaluator` 의
     * 「일반 사용자가 API 를 통해 임의 표현식을 전달하는 경로를 **절대로 만들지 않는다**」이다.
     * 발행이 그 경로를 열면 그 결정이 무효가 된다.
     */
    @Test
    fun `편집 API 가 막는 validator 타입은 발행으로도 들어가지 못한다`() {
        val key = seedWorkflow()
        draftRepository.upsert(
            workflowId(key),
            draftWithRules(key, validators = listOf(DraftRuleDto("CustomExpression", mapOf("expression" to "1 == 1")))),
            baseVersion = 0,
            updatedBy = null,
        )

        assertThatThrownBy { service.publish(ACTOR, key, baseVersion = 0) }
            .isInstanceOf(WorkflowInvalidRequestException::class.java)
    }

    /** 웹훅 url 스킴 제한은 전용 API 에만 있었다. 발행이 그것을 건너뛰면 SSRF 표면이 열린다. */
    @Test
    fun `임의 스킴 웹훅 url 은 발행이 거부한다`() {
        val key = seedWorkflow()
        draftRepository.upsert(
            workflowId(key),
            draftWithRules(
                key,
                postActions = listOf(DraftRuleDto("CALL_WEBHOOK", mapOf("url" to "file:///etc/passwd"))),
            ),
            baseVersion = 0,
            updatedBy = null,
        )

        assertThatThrownBy { service.publish(ACTOR, key, baseVersion = 0) }
            .isInstanceOf(WorkflowInvalidRequestException::class.java)
    }

    /**
     * ★ 발행은 `PUBLISH` 를 요구한다 — 편집 권한(`UPDATE`)만으로는 안 된다.
     *
     * 「권한이 아예 없으면 막힌다」만 걸어 두면 두 권한의 **분리**가 검증되지 않는다. 실제로
     * `WorkflowPublishService` 의 `PUBLISH` 를 `UPDATE` 로 바꿔도 전 테스트가 초록이었고,
     * 그러면 초안만 고칠 수 있는 사용자가 운영에 발행할 수 있게 된다.
     * 컨트롤러 KDoc 이 이 분리를 명시적 계약으로 선언해 둔 자리다.
     */
    @Test
    fun `편집 권한만 있고 발행 권한이 없으면 발행이 막힌다`() {
        val key = seedWorkflow()
        draftRepository.upsert(workflowId(key), draftKeepingBoth(key), baseVersion = 0, updatedBy = null)
        permissions.deny += WorkflowDefinitionPermission.PUBLISH

        try {
            assertThatThrownBy { service.publish(ACTOR, key, baseVersion = 0) }
                .isInstanceOf(WorkflowDefinitionAccessDeniedException::class.java)

            // 같은 행위자가 미리보기(UPDATE)는 여전히 할 수 있어야 한다 — 분리가 실재한다는 증거다.
            assertThat(service.preview(ACTOR, key).baseVersion).isEqualTo(0)
        } finally {
            permissions.deny.clear()
        }
    }

    /**
     * ★ 이관 필요 판정의 근거는 **DB** 여야 한다 — 인메모리 캐시가 아니다.
     *
     * `WorkflowCache` 는 무효화를 **커밋 전**에 하고 읽기 경로는 락을 잡지 않는다. 무효화와
     * 커밋 사이에 들어온 리더가 옛 정의를 다시 캐시에 올리면 그 값이 다음 발행까지 남는다.
     * 그 스테일 값을 게이트의 입력으로 쓰면 「빠지는 상태」 집합이 실제보다 작아지고,
     * **이슈가 남은 상태가 그 차집합에서 빠져** 409 없이 발행이 성사된다 —
     * `WorkflowPublishMappingRequiredException` KDoc 이 「FR-WF-07 이 닫으려는 결함이 정확히
     * 그것」이라 적은 상태로 이슈가 떨어진다.
     *
     * 여기서는 그 창을 직접 만든다 — 캐시를 채운 뒤 편성에 상태를 하나 더하고 무효화하지 않는다.
     */
    @Test
    fun `이관 필요 판정은 스테일 캐시가 아니라 DB 편성을 본다`() {
        val key = seedWorkflow()
        val id = workflowId(key)

        // 캐시를 {open, done} 로 채운다.
        cache.findByKey(key)

        // 편성에 review 가 늘었는데 무효화가 없다 — 커밋 전 무효화가 만드는 창의 결과다.
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            insertWorkflowStatus(conn, id, "review", "검토 $key", "IN_PROGRESS", 2)
        }
        issueUsage.counts["review"] = 5

        // 초안은 open 만 남긴다 — done 과 review 가 함께 빠지고, review 에는 이슈 5건이 있다.
        draftRepository.upsert(id, draftWithoutDone(key), baseVersion = 0, updatedBy = null)

        assertThatThrownBy { service.publish(ACTOR, key, baseVersion = 0) }
            .isInstanceOf(WorkflowPublishMappingRequiredException::class.java)
            .extracting { (it as WorkflowPublishMappingRequiredException).pending }
            .isEqualTo(mapOf("review" to 5L))
    }

    /**
     * ★ 저장 경계만 막으면 초안이 저장소로 직접 심어지는 경로가 남는다 — 발행도 함께 막는다.
     *
     * 발행되면 `WorkflowKeyResolverImpl` 이 `?:` 로 `displayOrder` 최소 상태를 시작 상태로 쓰고,
     * 그 값은 클라이언트가 정한다. 「완료」에 0 을 주면 그 워크플로우를 쓰는 **모든 프로젝트의
     * 신규 이슈가 완료 상태로 생성된다.**
     */
    @Test
    fun `INITIAL 전환이 없는 초안은 발행도 막는다`() {
        val key = seedWorkflow()
        draftRepository.upsert(
            workflowId(key),
            WorkflowDraftDefinition(
                key = key,
                name = "시작 전환이 없는 초안",
                states = listOf(DraftStateDto(key = "open", name = "열림 $key", category = "TODO", displayOrder = 0)),
                transitions = emptyList(),
            ),
            baseVersion = 0,
            updatedBy = null,
        )

        assertThatThrownBy { service.publish(ACTOR, key, baseVersion = 0) }
            .isInstanceOf(WorkflowInvalidRequestException::class.java)
    }

    /**
     * 오타 하나가 같은 결함의 다른 얼굴이다 — 저장 200 · 발행 200 뒤, 그 전환을 처음 시도한
     * 이슈에서야 터진다. 관리자는 발행이 성공했으므로 원인을 알 방법이 없다.
     */
    @Test
    fun `미지원 규칙 타입은 발행이 거부한다`() {
        val key = seedWorkflow()
        draftRepository.upsert(
            workflowId(key),
            draftWithRules(key, validators = listOf(DraftRuleDto("RequiredFeild", mapOf("field" to "resolution")))),
            baseVersion = 0,
            updatedBy = null,
        )

        assertThatThrownBy { service.publish(ACTOR, key, baseVersion = 0) }
            .isInstanceOf(WorkflowInvalidRequestException::class.java)
    }

    // ── 이관 필요 판정 (Jira 방식의 앞 절반) ──────────────────────────────────

    @Test
    fun `빠지는 상태에 이슈가 남아 있으면 발행이 막히고 상태별 건수를 알려준다`() {
        val key = seedWorkflow()
        issueUsage.counts["done"] = 3
        draftRepository.upsert(workflowId(key), draftWithoutDone(key), baseVersion = 0, updatedBy = null)

        assertThatThrownBy { service.publish(ACTOR, key, baseVersion = 0) }
            .isInstanceOf(WorkflowPublishMappingRequiredException::class.java)
            .extracting { (it as WorkflowPublishMappingRequiredException).pending }
            .isEqualTo(mapOf("done" to 3L))

        issueUsage.counts.clear()
    }

    @Test
    fun `빠지는 상태에 이슈가 없으면 그대로 발행된다`() {
        val key = seedWorkflow()
        draftRepository.upsert(workflowId(key), draftWithoutDone(key), baseVersion = 0, updatedBy = null)

        service.publish(ACTOR, key, baseVersion = 0)

        assertThat(cache.findByKey(key)!!.states.map { it.key }).containsExactly("open")
    }

    // ── preview ───────────────────────────────────────────────────────────────

    @Test
    fun `preview 는 무엇이 빠지는지 알려주되 발행하지 않는다`() {
        val key = seedWorkflow()
        issueUsage.counts["done"] = 2
        draftRepository.upsert(workflowId(key), draftWithoutDone(key), baseVersion = 0, updatedBy = null)

        val preview = service.preview(ACTOR, key)

        assertThat(preview.removedStatusKeys).containsExactly("done")
        assertThat(preview.pendingIssueCounts).isEqualTo(mapOf("done" to 2L))
        assertThat(preview.currentVersion).isZero()
        // 발행되지 않았다 — 초안도 정규 테이블도 그대로다.
        assertThat(draftRepository.findByWorkflowId(workflowId(key))).isNotNull
        assertThat(cache.findByKey(key)!!.states).hasSize(2)

        issueUsage.counts.clear()
    }

    // ── ★ 결선 인자 판정 — 스코프가 실제로 포트까지 가는가 ────────────────────
    //
    // 어댑터에 `project_id IN (…)` 을 넣어도 **서비스가 그 집합을 안 넘기면** 아무것도 달라지지
    // 않는다. 빈 집합이면 어댑터가 0 을 돌려주어 발행이 그냥 통과하고(fail-open), 남의 프로젝트가
    // 섞이면 종전처럼 과하게 막힌다. 둘 다 값이 아니라 **인자**에서만 드러난다.

    @Test
    fun `서비스가 그 워크플로우의 프로젝트 id 집합을 포트에 그대로 넘긴다`() {
        val key = seedWorkflow()
        val id = workflowId(key)
        val (alpha, beta) = attachTwoProjects(id)
        draftRepository.upsert(id, draftWithoutDone(key), baseVersion = 0, updatedBy = null)

        service.preview(ACTOR, key)

        // 빠지는 상태는 done 하나 — 호출도 한 번이다.
        assertThat(issueUsage.receivedProjectIds)
            .describedAs("빠지는 상태가 있으면 포트가 반드시 불린다 — 0회면 판정 자체가 없다")
            .hasSize(1)
        assertThat(issueUsage.receivedProjectIds.single())
            .describedAs("「비어 있지 않다」로 약하게 재면 남의 프로젝트가 섞여도 통과한다")
            .containsExactlyInAnyOrder(alpha, beta)
    }

    @Test
    fun `프로젝트 조회는 빠지는 상태 수와 무관하게 한 번만 한다`() {
        val key = seedWorkflow()
        val id = workflowId(key)
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            insertWorkflowStatus(conn, id, "review", "검토 $key", "IN_PROGRESS", 2)
        }
        attachTwoProjects(id)
        draftRepository.upsert(id, draftWithoutDone(key), baseVersion = 0, updatedBy = null)

        service.preview(ACTOR, key)

        assertThat(issueUsage.receivedProjectIds)
            .describedAs("done·review 가 함께 빠진다 — 포트는 상태마다 불린다")
            .hasSize(2)
        assertThat(schemeAssignments.lookupCount)
            .describedAs("associateWith 안에서 조회하면 상태 수만큼 3단 JOIN 이 돈다 (NFR N1)")
            .isEqualTo(1)
    }

    // ── ★ 상태 이관 큐잉 (F1·F2·F3·F6·F12) ───────────────────────────────────
    //
    // 포트도 어댑터도 워커도 이미 있는데 **부르는 코드가 없어** 관리자가 이관을 시작할 방법이 없었다.
    // 여기 있는 것이 그 호출자의 계약이다.

    @Test
    fun `정상 매핑이 큐잉되고 bulkOperationId 를 돌려준다`() {
        val key = seedWorkflow()
        val id = workflowId(key)
        attachTwoProjects(id)
        draftRepository.upsert(id, draftWithoutDone(key), baseVersion = 0, updatedBy = null)

        val bulkOperationId =
            service.migrate(ACTOR, key, baseVersion = 0, mappings = listOf(StatusMigrationMapping("done", "open")))

        assertThat(bulkOperationId).isEqualTo(BULK_OPERATION_ID)
        assertThat(migrationPort.received.single().actorUserId)
            .describedAs("워커에는 SecurityContext 가 없다 — actor 는 커맨드가 실어 나른다")
            .isEqualTo(ACTOR)
        assertThat(migrationPort.received.single().mappings)
            .containsExactly(StatusMigrationMapping("done", "open"))
        // F4 — migrate 는 project-workflow 를 읽기만 한다. 발행까지 해 버리면 초안이 사라진다.
        assertThat(draftRepository.findByWorkflowId(id)).isNotNull
    }

    /**
     * ★ `projectKeys` 는 요청에서 오지 않는다 — 포트 KDoc 이 「사용자 입력 금지, 호출자가 자기 발행
     * 트랜잭션 안에서 직접 조회해 채운다」로 계약한 자리다. 위조 요청이 남의 프로젝트 이슈를 옮기는
     * 것을 막는 **유일한** 장치이므로 판정이 없으면 계약이 없는 것과 같다.
     *
     * ### 왜 「알 수 없는 필드가 400 인가」로 재지 않는가
     * 그 계약은 **운영에서 거짓**이다. Boot 의 [Jackson2ObjectMapperBuilder] 가
     * `FAIL_ON_UNKNOWN_PROPERTIES` 를 꺼 두므로 운영은 모르는 필드를 조용히 버리고 202 를 준다.
     * 슬라이스에서만 참인 계약을 재면 위조 요청이 실제로 어디까지 가는지는 아무도 안 본다.
     * 그래서 운영과 같은 빌더로 본문을 읽은 뒤 **포트가 실제로 받은 범위**를 잰다.
     *
     * ### ★서비스를 직접 부르지 않고 **컨트롤러를 지난다**
     * 뮤테이션으로 실측했다. 서비스만 부르면 「`projectKeys` 를 선택 파라미터로 열고 조회 결과에
     * 합친다」는 형태를 **한 건도 잡지 못한다** — 테스트가 그 인자를 안 넘기니 기본값이 적용돼
     * 전 테스트가 초록이고, 운영에서는 컨트롤러가 본문 값을 실어 `HACKED` 가 그대로 포트까지 간다.
     * 본문이 실제로 흐르는 경로에서 재야 판정이 된다.
     */
    @Test
    fun `요청에 projectKeys 를 실어도 포트는 조회로 얻은 집합만 받는다`() {
        val key = seedWorkflow()
        val id = workflowId(key)
        val (alpha, beta) = attachTwoProjects(id)
        draftRepository.upsert(id, draftWithoutDone(key), baseVersion = 0, updatedBy = null)

        val body =
            """{"baseVersion":0,"mappings":[{"fromStatusKey":"done","toStatusKey":"open"}],""" +
                """"projectKeys":["HACKED"]}"""

        withActor(ACTOR) {
            controller.migrate(key, webMapper.readValue(body, MigrateRequest::class.java))
        }

        assertThat(migrationPort.received.single().projectKeys)
            .describedAs("「HACKED 가 없다」로 약하게 재면 조회 결과가 통째로 비어도 통과한다")
            .containsExactlyInAnyOrder(projectKey(alpha), projectKey(beta))
    }

    @Test
    fun `migrate 도 저장된 초안의 base_version 과 다르면 막힌다`() {
        val key = seedWorkflow()
        val id = workflowId(key)
        attachTwoProjects(id)
        draftRepository.upsert(id, draftWithoutDone(key), baseVersion = 0, updatedBy = null)
        // 남이 이름만 바꿔 version 이 올랐다. 초안 행은 그대로라 base_version 은 여전히 0 이다.
        dsl.execute("UPDATE workflows SET version = 1 WHERE key = ?", key)

        assertThatThrownBy {
            service.migrate(ACTOR, key, baseVersion = 1, mappings = listOf(StatusMigrationMapping("done", "open")))
        }.isInstanceOf(WorkflowVersionConflictException::class.java)

        assertThat(migrationPort.received)
            .describedAs("거절된 요청이 큐잉까지 갔으면 롤백해도 pgmq 메시지는 남을 수 있다")
            .isEmpty()
    }

    /**
     * ★ 400 이어야 한다 — 500 이 아니다.
     *
     * 초안에는 있는데 전역 카탈로그에 없는 상태를 도착지로 실으면, 전처리에 `requireStatusCatalog`
     * 가 없을 때 그 키가 어댑터까지 간다. 어댑터는 `IllegalArgumentException` 을 던지는데 이 BC 의
     * advice 중 그것을 잡는 것이 없어(`WorkflowExceptionHandler:168` 이 「IAE 를 잡지 않는다」고
     * 명시) 응답이 **500** 이 된다. 관리자는 무엇이 잘못됐는지 못 보고 재시도 말고 할 게 없다.
     */
    @Test
    fun `카탈로그에 없는 상태가 초안에 있으면 400 이고 500 이 아니다`() {
        val key = seedWorkflow()
        val id = workflowId(key)
        attachTwoProjects(id)
        draftRepository.upsert(id, draftWithUnknownStatus(key), baseVersion = 0, updatedBy = null)

        assertThatThrownBy {
            service.migrate(ACTOR, key, baseVersion = 0, mappings = listOf(StatusMigrationMapping("done", "없는상태")))
        }
            .describedAs("IllegalArgumentException 이면 이 BC 에 IAE advice 가 없어 500 으로 나간다")
            .isInstanceOf(WorkflowInvalidRequestException::class.java)
            .hasMessageContaining("없는상태")

        assertThat(migrationPort.received)
            .describedAs("포트에 닿았다면 400 이 아니라 어댑터의 IAE 가 응답을 정한 것이다")
            .isEmpty()
    }

    // ── 뒤쪽 창 재카운트 (Task 6 · F10 · E8 · E9) ──────────────────────────────

    /**
     * ★ 교체와 카운트 사이에 들어온 이슈를 잡는다 (F10 · E8).
     *
     * 첫 검사가 0 을 봤어도 `replaceDefinition` 이 끝날 때까지 그 상태로 이슈가 들어올 수 있다.
     * 재카운트가 없으면 그 이슈들은 **어느 워크플로우도 모르는 상태**에 남는다.
     *
     * ### 이 판정이 재는 진짜 대상은 「같은 집합으로 다시 세는가」다
     * `requireNoPendingIssues(key, workflowId, definition)` 를 그냥 재호출하면 그 함수가
     * `removedStatusKeys` 를 **다시 계산**하는데, `replaceDefinition` 뒤에는
     * `findComposedStatusKeys` 가 새 편성을 돌려주므로 차집합이 **항상 빈 집합**이 된다.
     * 그러면 예외 자체가 안 난다 — 판정이 있는데 아무것도 안 세는 형태
     * (`[[invariant-satisfied-by-helptext-not-logic]]` 과 같은 양식)다.
     *
     * 그래서 예외가 나는 것만이 아니라 **그 안에 `done` 이 담겼는지**를 함께 본다. 담겼다면
     * 교체 전 집합을 재사용했다는 증거다.
     *
     * ### 롤백은 여기서 재지 않는다 — 잴 수 없어서다
     * 이 클래스는 `@SpringBootTest` 없이 손으로 조립하므로 `@Transactional` 이 프록시 없이
     * 호출된다(클래스 KDoc). 트랜잭션 경계가 없으니 예외가 나도 이미 쓴 것이 되돌아가지 않는다.
     * 롤백은 Spring 이 경계에서 보장하는 것이고 그 경계의 실재는 `ProjectWorkflowContextBootTest`
     * 가 컨텍스트로 검증한다. 여기서 「정의가 그대로다」를 단언하면 **코드가 옳아도 red** 다.
     */
    @Test
    fun `교체 직후 유입이 있으면 발행이 막히고 교체 전 집합으로 센다`() {
        val key = seedWorkflow()
        val id = workflowId(key)
        attachTwoProjects(id)
        draftRepository.upsert(id, draftWithoutDone(key), baseVersion = 0, updatedBy = null)
        // 1회차 0 — 교체 전 검사는 통과한다. 2회차 1 — 그 사이 한 건이 들어왔다.
        issueUsage.queued["done"] = ArrayDeque(listOf(0L, 1L))

        // ★한 번만 부른다 — queued 는 호출 순서로 소비되므로 두 번 부르면 두 번째는 바닥나 0 이 된다.
        val thrown = catchThrowable { service.publish(ACTOR, key, baseVersion = 0) }

        assertThat(thrown)
            .describedAs("재카운트가 없으면 교체가 그대로 커밋되고 그 이슈는 어느 워크플로우도 모르는 상태에 남는다")
            .isInstanceOf(WorkflowPublishMappingRequiredException::class.java)
        assertThat((thrown as WorkflowPublishMappingRequiredException).pending)
            .describedAs("교체 뒤 집합을 다시 계산했다면 차집합이 비어 done 이 담기지 않는다")
            .containsKey("done")
    }

    /**
     * ★ 이관을 큐잉했다고 발행이 열리지 않는다 (E9).
     *
     * `migrate` 는 일괄작업을 **큐잉만** 한다. 워커가 아직 안 돌았거나 일부가 FAILED 로 끝나면
     * 이슈는 그대로 남아 있고, 그 상태에서 발행하면 그 이슈들이 사라진 상태에 갇힌다.
     * 그러니 이관 요청을 보냈다는 사실 자체는 발행 조건이 아니다 — **실제 잔여 건수**만이 조건이다.
     */
    @Test
    fun `이관을 큐잉했어도 이슈가 남아 있으면 재발행이 막힌다`() {
        val key = seedWorkflow()
        val id = workflowId(key)
        attachTwoProjects(id)
        draftRepository.upsert(id, draftWithoutDone(key), baseVersion = 0, updatedBy = null)
        issueUsage.counts["done"] = 3

        val bulkOperationId =
            service.migrate(ACTOR, key, baseVersion = 0, mappings = listOf(StatusMigrationMapping("done", "open")))
        assertThat(bulkOperationId).isEqualTo(BULK_OPERATION_ID)

        assertThatThrownBy { service.publish(ACTOR, key, baseVersion = 0) }
            .describedAs("큐잉은 약속일 뿐이다 — 워커가 실패하면 이슈는 그대로 남는다")
            .isInstanceOf(WorkflowPublishMappingRequiredException::class.java)
    }

    // ── 매핑 가드 (Task 4 · F7·F8·F11·F13·F14·F16 · E2·E4) ────────────────────
    //
    // 여기 있는 판정은 전부 **포트에 닿기 전에** 막혀야 한다. 큐잉까지 갔다가 트랜잭션을
    // 되감아도 pgmq 메시지는 남을 수 있어(`migrate 도 저장된 초안의 base_version …` 이 같은
    // 이유로 포트 미호출을 단언한다) 「예외가 났다」만으로는 안전이 증명되지 않는다.

    /**
     * ★ 빠지지도 않는 상태를 출발지로 실으면 **멀쩡한 이슈가 통째로 옮겨진다.**
     *
     * 워커는 `fromStatusKey` 를 그대로 믿고 긁는다. 이 가드가 없으면 `open` 을 실은 요청 하나가
     * 살아 있는 상태의 이슈 전량을 이동시킨다 — 되돌릴 방법은 역방향 일괄작업뿐이다.
     */
    @Test
    fun `제거되지 않는 상태를 fromStatusKey 로 실으면 거부한다`() {
        val key = seedWorkflow()
        val id = workflowId(key)
        attachTwoProjects(id)
        draftRepository.upsert(id, draftWithoutDone(key), baseVersion = 0, updatedBy = null)

        assertThatThrownBy {
            // `done` 만 빠진다. `open` 은 남는 상태다.
            service.migrate(ACTOR, key, baseVersion = 0, mappings = listOf(StatusMigrationMapping("open", "open")))
        }
            .isInstanceOf(WorkflowMigrationInvalidMappingException::class.java)
            .hasMessageContaining("open")

        assertThat(migrationPort.received)
            .describedAs("포트에 닿았으면 롤백해도 pgmq 메시지는 남을 수 있다")
            .isEmpty()
    }

    /**
     * ★ 도착지가 초안에 없으면 이관 뒤 이슈가 **어느 상태에도 속하지 않는다.**
     *
     * `done` 은 이 초안이 빼는 상태다. 거기로 옮기면 발행 직후 그 이슈들이 다시 「빠지는 상태에
     * 남은 이슈」가 되어 발행이 영원히 막힌다(E3 연쇄).
     */
    @Test
    fun `toStatusKey 가 초안 상태 집합에 없으면 거부한다`() {
        val key = seedWorkflow()
        val id = workflowId(key)
        attachTwoProjects(id)
        draftRepository.upsert(id, draftWithoutDone(key), baseVersion = 0, updatedBy = null)

        assertThatThrownBy {
            service.migrate(ACTOR, key, baseVersion = 0, mappings = listOf(StatusMigrationMapping("done", "done")))
        }
            .isInstanceOf(WorkflowMigrationInvalidMappingException::class.java)
            .hasMessageContaining("done")

        assertThat(migrationPort.received).isEmpty()
    }

    /**
     * ★ 빈 매핑은 「아무것도 안 옮기는 일괄작업」을 만든다.
     *
     * 관리자는 202 와 id 를 받고 진행률을 보러 가지만 옮겨진 것이 없다. 그 뒤 발행은 여전히 409 라
     * **무엇이 잘못됐는지 알 방법이 없다.** 화면이 매핑을 안 실어 보낸 버그가 이 형태로 새어 나간다.
     */
    @Test
    fun `mappings 가 비면 거부한다`() {
        val key = seedWorkflow()
        val id = workflowId(key)
        attachTwoProjects(id)
        draftRepository.upsert(id, draftWithoutDone(key), baseVersion = 0, updatedBy = null)

        assertThatThrownBy {
            service.migrate(ACTOR, key, baseVersion = 0, mappings = emptyList())
        }.isInstanceOf(WorkflowMigrationInvalidMappingException::class.java)

        assertThat(migrationPort.received).isEmpty()
    }

    /**
     * ★ 같은 출발지가 두 번 오면 **어느 도착지가 이기는지 워커만 안다.**
     *
     * 목록 순서에 의존하는 조용한 승자는 재현이 안 되는 데이터 이동을 만든다. 화면이 매핑 행을
     * 복제하는 버그가 여기서 걸리지 않으면 운영에서만 드러난다.
     */
    @Test
    fun `같은 fromStatusKey 가 두 번 오면 거부한다`() {
        val key = seedWorkflow()
        val id = workflowId(key)
        attachTwoProjects(id)
        draftRepository.upsert(id, draftWithoutDone(key), baseVersion = 0, updatedBy = null)

        assertThatThrownBy {
            service.migrate(
                ACTOR,
                key,
                baseVersion = 0,
                mappings =
                    listOf(
                        StatusMigrationMapping("done", "open"),
                        StatusMigrationMapping("done", "open"),
                    ),
            )
        }.isInstanceOf(WorkflowMigrationInvalidMappingException::class.java)

        assertThat(migrationPort.received).isEmpty()
    }

    /**
     * ★ F16 — 초안에만 있는 신규 상태로 옮기면 **발행 전에 유령이 생긴다.**
     *
     * 이관은 발행보다 먼저 실행되므로(D2 로 둘을 쪼갠 부작용), 도착지가 아직 live 편성에 없으면
     * 그 사이 이슈들은 이 워크플로우가 모르는 상태에 놓인다. 지라는 매핑과 발행이 한 조작이라
     * 이 위험이 없다 — X5 분할이 새로 연 창이므로 여기서 닫는다.
     *
     * `review` 는 전역 카탈로그에는 있고(그래야 `requireStatusCatalog` 를 지나 이 판정까지 온다)
     * 이 워크플로우의 편성에는 없다.
     */
    @Test
    fun `toStatusKey 가 live 편성에 없으면 거부한다`() {
        val key = seedWorkflow()
        val id = workflowId(key)
        attachTwoProjects(id)
        // 카탈로그는 컨테이너 전역이라 흔한 키를 고정으로 쓰면 남이 심은 카테고리와 어긋난다.
        val pending = "migr-pending-${UUID.randomUUID().toString().take(8)}"
        insertCatalogOnlyStatus(pending, "검토 대기")
        draftRepository.upsert(id, draftAddingStatus(key, pending, "검토 대기"), baseVersion = 0, updatedBy = null)

        assertThatThrownBy {
            service.migrate(ACTOR, key, baseVersion = 0, mappings = listOf(StatusMigrationMapping("done", pending)))
        }
            .isInstanceOf(WorkflowMigrationInvalidMappingException::class.java)
            .hasMessageContaining(pending)

        assertThat(migrationPort.received)
            .describedAs("발행 전에 큐잉되면 이슈가 live 에 없는 상태로 옮겨진다")
            .isEmpty()
    }

    /**
     * ★ F11 fail-closed — 스킴은 `(scheme_id, issue_type_id) → workflow_id` 인데 이관 워커는
     * **이슈 타입 축을 통째로 무시한다**(`BulkOperationRepository.statusMigrationTargets` 가
     * `current_state_key`·`deleted_at`·`project_id` 만 건다. 장부 145).
     *
     * 그래서 한 프로젝트가 Bug→WF1 · Task→WF2 를 쓰면 **WF1 이관이 WF2 이슈까지 옮긴다** —
     * 과다 이동은 데이터 손상이다. 포트에 그 축이 없어(N3 충돌) 이 PR 에서 고칠 수 없으므로
     * **조합 자체를 안 연다**. 스킴이 이 워크플로우 하나만 쓸 때만 이관을 허용한다.
     */
    @Test
    fun `스킴이 다른 워크플로우도 매핑하면 거부한다`() {
        val key = seedWorkflow()
        val id = workflowId(key)
        attachSchemeWithSibling(id)
        draftRepository.upsert(id, draftWithoutDone(key), baseVersion = 0, updatedBy = null)

        assertThatThrownBy {
            service.migrate(ACTOR, key, baseVersion = 0, mappings = listOf(StatusMigrationMapping("done", "open")))
        }.isInstanceOf(WorkflowMigrationInvalidMappingException::class.java)

        assertThat(migrationPort.received)
            .describedAs("이슈 타입 축이 없는 채로 큐잉하면 형제 워크플로우의 이슈까지 옮겨진다")
            .isEmpty()
    }

    /**
     * ★ F13 — 범위가 비면 어댑터의 `require` 가 터지고 그것이 **500** 으로 나간다.
     *
     * 이 BC 에는 `IllegalArgumentException` 을 잡는 advice 가 없다(`WorkflowExceptionHandler:168`
     * 이 「IAE 를 잡지 않는다」고 명시). 스킴에 안 붙은 워크플로우를 이관하려는 것은 관리자의
     * 입력 오류이므로 400 이어야 하고, 그 판정은 포트를 부르기 **전에** 나야 한다.
     */
    @Test
    fun `projectKeys 가 비면 포트를 부르기 전에 400 이다`() {
        val key = seedWorkflow()
        val id = workflowId(key)
        // attachTwoProjects 를 부르지 않는다 — 이 워크플로우는 어느 스킴에도 안 붙어 있다.
        draftRepository.upsert(id, draftWithoutDone(key), baseVersion = 0, updatedBy = null)

        assertThatThrownBy {
            service.migrate(ACTOR, key, baseVersion = 0, mappings = listOf(StatusMigrationMapping("done", "open")))
        }.isInstanceOf(WorkflowMigrationInvalidMappingException::class.java)

        assertThat(migrationPort.received)
            .describedAs("포트에 닿으면 어댑터 IAE 가 응답을 정해 400 이 아니라 500 이 된다")
            .isEmpty()
    }

    /**
     * ★ F14 — 상한 초과는 **출구 없는 막다른 길**이다.
     *
     * `BulkOperationRepository:141-149` 는 상한을 넘으면 **아무것도 적재하지 않고** 개수만 돌려주고
     * 작업은 FAILED 로 끝난다. 이슈는 한 건도 안 옮겨졌는데 발행은 계속 409 라, 관리자는 같은
     * 요청을 반복하는 것 말고 할 수 있는 일이 없다. 그래서 큐잉 전에 미리 세어 막고 **실제 건수**를
     * 알려 준다 — 「너무 많다」만으로는 얼마나 줄여야 하는지 모른다.
     */
    @Test
    fun `대상이 1000 을 넘으면 400 과 실제 건수를 돌려준다`() {
        val key = seedWorkflow()
        val id = workflowId(key)
        attachTwoProjects(id)
        draftRepository.upsert(id, draftWithoutDone(key), baseVersion = 0, updatedBy = null)
        issueUsage.counts["done"] = 1001

        assertThatThrownBy {
            service.migrate(ACTOR, key, baseVersion = 0, mappings = listOf(StatusMigrationMapping("done", "open")))
        }
            .isInstanceOf(WorkflowMigrationInvalidMappingException::class.java)
            .describedAs("「너무 많다」만 알려 주면 얼마나 줄여야 하는지 모른다")
            .hasMessageContaining("1001")

        assertThat(migrationPort.received)
            .describedAs("큐잉되면 워커가 아무것도 적재 않고 FAILED 로 끝나 출구가 없다")
            .isEmpty()
    }

    // ── 거절 경로 ─────────────────────────────────────────────────────────────
    /**
     * ★ C-10 — 권한 검사가 **첫 줄**이라는 계약을 판정으로 잠근다 (F5).
     *
     * ### 왜 403 만 재면 안 되는가
     * 포트를 먼저 부르고 **나중에** 던져도 응답은 똑같이 403 이다. 그 구현에서는 권한 없는 행위자의
     * 요청이 이미 일괄작업을 큐잉한 뒤이고, 트랜잭션을 되감아도 pgmq 메시지는 남는 경로가 있다.
     * 그러니 상태 코드는 이 계약을 **전혀 재지 못한다** — 포트 호출 횟수가 유일하게 관찰 가능한 신호다.
     *
     * ### 왜 publish 축은 안 재는가
     * D2 로 `POST /publish` 는 포트를 **어떤 경로로도** 부르지 않는다. 「publish 도 포트를 부르지
     * 않는다」는 권한 검사를 지우든 맨 아래로 옮기든 항상 참이라 **어떤 뮤테이션으로도 red 가 되지
     * 않는다** — `[[unreachable-state-fixture-is-fake-green]]` 양식이라 계획 단계에서 제거했다.
     *
     * ### 왜 `prepareDraft` 안에 권한 검사를 넣지 않았는가
     * 넣으면 이 계약이 **헬퍼 호출 순서에 숨어** 호출부만 보고는 확인할 수 없게 된다.
     * 호출자마다 첫 줄에 두는 것이 그것을 눈에 보이게 하는 유일한 방법이다.
     */
    @Test
    fun `권한 없는 actor 의 migrate 는 포트를 부르지 않는다`() {
        val key = seedWorkflow()
        val id = workflowId(key)
        attachTwoProjects(id)
        draftRepository.upsert(id, draftWithoutDone(key), baseVersion = 0, updatedBy = null)
        permissions.deny += WorkflowDefinitionPermission.PUBLISH

        try {
            assertThatThrownBy {
                service.migrate(
                    ACTOR,
                    key,
                    baseVersion = 0,
                    mappings = listOf(StatusMigrationMapping("done", "open")),
                )
            }.isInstanceOf(WorkflowDefinitionAccessDeniedException::class.java)

            assertThat(migrationPort.received)
                .describedAs("포트를 먼저 부르고 나중에 던져도 403 이다 — 호출 횟수만이 순서를 잰다")
                .isEmpty()
        } finally {
            permissions.deny.clear()
        }
    }


    @Test
    fun `초안이 없으면 발행할 수 없다`() {
        assertThatThrownBy { service.publish(ACTOR, seedWorkflow(), baseVersion = 0) }
            .isInstanceOf(WorkflowInvalidRequestException::class.java)
    }

    @Test
    fun `없는 워크플로우는 404 다`() {
        assertThatThrownBy { service.publish(ACTOR, "wf-없음", baseVersion = 0) }
            .isInstanceOf(WorkflowNotFoundException::class.java)
    }

    @Test
    fun `발행 권한이 없으면 막힌다`() {
        // 보안 표면이라 가드가 실제로 막는지 본다 — 붙였다는 사실만으로는 증명되지 않는다.
        val key = seedWorkflow()
        draftRepository.upsert(workflowId(key), draftKeepingBoth(key), baseVersion = 0, updatedBy = null)
        permissions.allow = false

        try {
            assertThatThrownBy { service.publish(ACTOR, key, baseVersion = 0) }
                .isInstanceOf(WorkflowDefinitionAccessDeniedException::class.java)
        } finally {
            permissions.allow = true
        }
    }

    @Test
    fun `카탈로그에 없는 상태를 가리키는 초안은 거절한다`() {
        // 발행이 상태를 슬쩍 만들면 카탈로그의 이름 유일 규칙을 우회하는 두 번째 경로가 생긴다.
        val key = seedWorkflow()
        val definition =
            WorkflowDraftDefinition(
                key = key,
                name = "새 상태를 쓰는 초안",
                states = listOf(DraftStateDto(key = "없는상태", name = "미등록", category = "TODO", displayOrder = 0)),
                transitions =
                    listOf(DraftTransitionDto(from = null, to = "없는상태", name = "생성", kind = "INITIAL")),
            )
        draftRepository.upsert(workflowId(key), definition, baseVersion = 0, updatedBy = null)

        assertThatThrownBy { service.publish(ACTOR, key, baseVersion = 0) }
            .isInstanceOf(WorkflowInvalidRequestException::class.java)
            .hasMessageContaining("없는상태")
    }
}

/** 판정을 뒤집을 수 있는 권한 리졸버. 거부는 운영 리졸버와 같은 예외로 낸다. */
class SwitchableWorkflowPermissions : WorkflowDefinitionPermissionResolver {
    /** 전부 거부. 「권한이 아예 없는 행위자」를 재현한다. */
    var allow: Boolean = true

    /**
     * ★ **개별 거부.** 이것이 없으면 `permission` 인자를 아무도 읽지 않아
     * 「UPDATE 면 되는데 PUBLISH 를 요구한다」와 그 반대가 테스트에서 구별되지 않는다.
     * 실제로 `WorkflowPublishService` 의 `PUBLISH` 를 `UPDATE` 로 바꿔도 전 테스트가 초록이었다 —
     * 컨트롤러 KDoc 이 두 권한의 분리를 명시적 계약으로 선언해 놓은 자리라 가짜 그린이다.
     */
    val deny: MutableSet<WorkflowDefinitionPermission> = mutableSetOf()

    override fun requirePermission(
        actorId: UUID,
        permission: WorkflowDefinitionPermission,
    ) {
        if (!allow || permission in deny) {
            throw WorkflowDefinitionAccessDeniedException(actorId, permission)
        }
    }
}

/** 테스트 행위자. 권한 판정과 published_by 에 함께 쓰인다. */
val ACTOR: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000aa")

/** 스파이 포트가 돌려주는 일괄작업 id. 고정값이라야 「그 값이 그대로 나왔는가」를 잴 수 있다. */
val BULK_OPERATION_ID: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000b0")
