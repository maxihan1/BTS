// 발행 통합 테스트 — 발행 전 런타임 불변 · 캐시 무효화 · 버전 충돌 · 이관 필요 판정

package com.bts.workflow.application

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
import com.bts.workflow.domain.exception.WorkflowNotFoundException
import com.bts.workflow.domain.exception.WorkflowPublishMappingRequiredException
import com.bts.workflow.domain.exception.WorkflowVersionConflictException
import com.bts.workflow.postaction.PostActionRepository
import com.bts.workflow.repository.WorkflowDraftRepository
import com.bts.workflow.repository.WorkflowPublicationRepository
import com.bts.workflow.repository.WorkflowPublishRepository
import com.bts.workflow.repository.WorkflowRepository
import com.bts.workflow.testsupport.insertWorkflowStatus
import com.bts.workflow.validator.ValidatorRepository
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.sql.DriverManager
import java.util.UUID

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

    /** 상태별 이슈 수를 테스트가 정하는 스텁. 실제 어댑터는 issues 를 읽지만 그 테이블은 여기 없다. */
    private class StubIssueStatusUsage : IssueStatusUsagePort {
        val counts = mutableMapOf<String, Long>()

        override fun countIssuesInStatus(statusKey: String): Long = counts[statusKey] ?: 0
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
    private val permissions = SwitchableWorkflowPermissions()

    private val service =
        WorkflowPublishService(
            draftRepository = draftRepository,
            publishRepository = publishRepository,
            publicationRepository = publicationRepository,
            ruleWriter =
                DraftRuleWriter(
                    ValidatorRepository(dsl, objectMapper),
                    PostActionRepository(dsl, objectMapper),
                ),
            issueStatusUsagePort = issueUsage,
            permissionResolver = permissions,
            cache = cache,
        )

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
            states = listOf(DraftStateDto(key = "open", name = "열림", category = "TODO", displayOrder = 0)),
            transitions = listOf(DraftTransitionDto(from = null, to = "open", name = "이슈 생성", kind = "INITIAL")),
        )

    private fun draftKeepingBoth(key: String) =
        WorkflowDraftDefinition(
            key = key,
            name = "이름만 바꿨다",
            states =
                listOf(
                    DraftStateDto(key = "open", name = "열림", category = "TODO", displayOrder = 0),
                    DraftStateDto(key = "done", name = "완료", category = "DONE", displayOrder = 1),
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

    // ── 거절 경로 ─────────────────────────────────────────────────────────────

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
    var allow: Boolean = true

    override fun requirePermission(
        actorId: UUID,
        permission: WorkflowDefinitionPermission,
    ) {
        if (!allow) throw WorkflowDefinitionAccessDeniedException(actorId, permission)
    }
}

/** 테스트 행위자. 권한 판정과 published_by 에 함께 쓰인다. */
val ACTOR: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000aa")
