// 전환 읽기/쓰기 통합 테스트 — workflow_statuses 로 id·kind 를 복원하고 수정이 출발지를 정확히 바꾸는가

package com.bts.workflow.repository

import com.bts.workflow.domain.TransitionKind
import com.bts.workflow.testsupport.SharedPostgres
import com.bts.workflow.testsupport.insertWorkflowStatus
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.Types
import java.util.UUID

/** 전환 1행의 시드 명세. [fromStatusKey] 가 null 이면 출발지 없는 전환(GLOBAL·INITIAL)이다. */
private data class TransitionSeed(
    val kind: String,
    val name: String,
    val fromStatusKey: String?,
    val toStatusKey: String,
    val displayOrder: Int,
)

/** UUID 컬럼에 null 을 명시적으로 넣는다. `setObject(i, null)` 은 타입 추론이 갈려 드라이버마다 다르다. */
private fun PreparedStatement.setUuidOrNull(
    index: Int,
    value: UUID?,
) {
    if (value == null) setNull(index, Types.OTHER) else setObject(index, value)
}

/**
 * `WorkflowRepository` 의 전환 읽기 — 전환 identity 가 `id` 로 옮겨간 뒤의 복원 계약 (FR-WF-05).
 *
 * 빈 DB 에 마이그레이션만 돌리면 INITIAL 백필 대상이 0행이라 **출발지 없는 행 자체가 생기지 않는다**.
 * 그래서 INITIAL·GLOBAL 행을 **손으로 심어** 그 모양을 재현한다.
 *
 * 워크플로우 2개를 쓴다. `legacy-normal` 은 NORMAL 행만 두어 `id`·`kind`·순서 계약을 보고,
 * `global-initial` 은 출발지 없는 INITIAL·GLOBAL 을 섞는다.
 */
class WorkflowRepositoryTransitionTest {
    companion object {
        val postgres = SharedPostgres.freshDatabase()

        private val stateSeeds =
            listOf(
                Triple("open", "열림", "TODO"),
                Triple("in-progress", "진행 중", "IN_PROGRESS"),
                Triple("done", "완료", "DONE"),
            )

        /** NORMAL 전환만. 삽입 순서와 display_order 를 **일부러 어긋나게** 둔다. */
        private val legacySeeds =
            listOf(
                TransitionSeed("NORMAL", "완료", "open", "done", 2),
                TransitionSeed("NORMAL", "조건부 승인", "open", "done", 3),
                TransitionSeed("NORMAL", "시작", "open", "in-progress", 1),
            )

        /** 출발지 없는 INITIAL·GLOBAL 이 섞인 워크플로우 — V207 백필 직후의 실제 모양. */
        private val originlessSeeds =
            listOf(
                TransitionSeed("INITIAL", "이슈 생성", null, "open", 0),
                TransitionSeed("GLOBAL", "즉시 완료", null, "done", 1),
                TransitionSeed("NORMAL", "시작", "open", "in-progress", 2),
            )

        lateinit var repository: WorkflowRepository

        /** 쓰기 경로. 수정 결과를 보려면 `updateTransition` 을 실제로 불러야 한다. */
        lateinit var writeRepository: WorkflowWriteRepository

        /** 전환 이름 → DB 가 부여한 `workflow_transitions.id`. 읽기가 그대로 채웠는지 대조한다. */
        lateinit var seededTransitionIds: Map<String, UUID>

        @BeforeAll
        @JvmStatic
        fun setup() {
            seededTransitionIds = seedData()
            val dataSource = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            val dsl = DSL.using(dataSource, SQLDialect.POSTGRES)
            repository = WorkflowRepository(dsl)
            writeRepository = WorkflowWriteRepository(dsl)
        }

        private fun seedData(): Map<String, UUID> =
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
                conn.autoCommit = false
                val ids =
                    seedWorkflow(conn, "legacy-normal", legacySeeds) +
                        seedWorkflow(conn, "global-initial", originlessSeeds)
                conn.commit()
                ids
            }

        /** 워크플로우 1개 + 상태 3개 + 전환 목록을 심고 「전환 이름 → id」를 돌려준다. */
        private fun seedWorkflow(
            conn: Connection,
            key: String,
            seeds: List<TransitionSeed>,
        ): Map<String, UUID> {
            val workflowId = insertWorkflowRow(conn, key)
            val ids =
                stateSeeds.withIndex().associate { (order, spec) ->
                    val (stateKey, name, category) = spec
                    stateKey to insertWorkflowStatus(conn, workflowId, stateKey, name, category, order)
                }
            return seeds.associate { seed -> "$key/${seed.name}" to insertTransition(conn, workflowId, seed, ids) }
        }

        private fun insertWorkflowRow(
            conn: Connection,
            key: String,
        ): UUID =
            conn.prepareStatement("INSERT INTO workflows (key, name) VALUES (?, ?) RETURNING id").use { stmt ->
                stmt.setString(1, key)
                stmt.setString(2, key)
                stmt.executeQuery().use { rs ->
                    rs.next()
                    rs.getObject(1) as UUID
                }
            }

        /** 그 워크플로우의 전역 카탈로그 편성 행(`workflow_statuses.id`)을 상태 키로 찾아 둔다. */
        private fun compositionIds(
            conn: Connection,
            workflowId: UUID,
        ): Map<String, UUID> =
            conn.prepareStatement(
                "SELECT s.key, ws.id FROM workflow_statuses ws" +
                    " JOIN statuses s ON s.id = ws.status_id" +
                    " WHERE ws.workflow_id = ?",
            ).use { stmt ->
                stmt.setObject(1, workflowId)
                stmt.executeQuery().use { rs ->
                    buildMap { while (rs.next()) put(rs.getString(1), rs.getObject(2) as UUID) }
                }
            }

        /** 전환 1행. [ids] 는 상태 키 → `workflow_statuses.id` 다. */
        private fun insertTransition(
            conn: Connection,
            workflowId: UUID,
            seed: TransitionSeed,
            ids: Map<String, UUID>,
        ): UUID =
            conn.prepareStatement(
                "INSERT INTO workflow_transitions" +
                    " (workflow_id, kind, name, from_status_id, to_status_id, display_order)" +
                    " VALUES (?, ?, ?, ?, ?, ?) RETURNING id",
            ).use { stmt ->
                stmt.setObject(1, workflowId)
                stmt.setString(2, seed.kind)
                stmt.setString(3, seed.name)
                stmt.setUuidOrNull(4, seed.fromStatusKey?.let { ids.getValue(it) })
                stmt.setObject(5, ids.getValue(seed.toStatusKey))
                stmt.setInt(6, seed.displayOrder)
                stmt.executeQuery().use { rs ->
                    rs.next()
                    rs.getObject(1) as UUID
                }
            }

        /**
         * 테스트 전용 워크플로우 1개를 새로 심는다. `@BeforeAll` 픽스처는 여러 테스트가 함께 읽으므로
         * **쓰기 테스트는 공유 픽스처를 건드리면 안 된다** — 실행 순서에 따라 옆 단언이 무너진다.
         */
        private fun seedFreshWorkflow(
            key: String,
            seeds: List<TransitionSeed>,
        ): Map<String, UUID> =
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
                conn.autoCommit = false
                val ids = seedWorkflow(conn, key, seeds)
                conn.commit()
                ids
            }

        /** 그 워크플로우의 상태 키 → `workflow_statuses.id`. 쓰기 API 에 넘길 값이다. */
        private fun compositionIdsOf(workflowKey: String): Map<String, UUID> =
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
                compositionIds(conn, requireNotNull(writeRepository.findLiveIdByKey(workflowKey)))
            }
    }

    // ── RED 1. id · kind 매핑 ──────────────────────────────────────────────────

    @Test
    fun `읽기가 transition 의 id 와 kind 를 채운다`() {
        val workflow = repository.findByKey("legacy-normal")

        val start = workflow?.transitions?.firstOrNull { it.name == "시작" }
        assertThat(start).describedAs("legacy-normal 의 '시작' 전환을 못 읽었다").isNotNull()
        assertThat(start!!.id)
            .describedAs("전환 id 가 DB 행의 id 여야 한다 — 매핑이 빠지면 도메인 기본값(임의 UUID)이 새어 나온다")
            .isEqualTo(seededTransitionIds.getValue("legacy-normal/시작"))
        assertThat(start.kind).isEqualTo(TransitionKind.NORMAL)
    }

    // ── RED 2. GLOBAL 전환의 fromStateKey ─────────────────────────────────────

    @Test
    fun `GLOBAL 전환은 fromStateKey 가 null 로 읽힌다`() {
        val workflow = repository.findByKey("global-initial")

        val global = workflow?.transitions?.firstOrNull { it.name == "즉시 완료" }
        assertThat(global).describedAs("global-initial 의 GLOBAL 전환을 못 읽었다").isNotNull()
        assertThat(global!!.kind).isEqualTo(TransitionKind.GLOBAL)
        assertThat(global.fromStateKey).isNull()
        assertThat(global.toStateKey).isEqualTo("done")
    }

    // ── RED 3. 같은 상태쌍 다중 전환 ──────────────────────────────────────────

    @Test
    fun `같은 상태쌍 2행이 각각 다른 id 로 읽힌다`() {
        val workflow = repository.findByKey("legacy-normal")

        val openToDone =
            workflow?.transitions.orEmpty().filter { it.fromStateKey == "open" && it.toStateKey == "done" }
        assertThat(openToDone).hasSize(2)
        assertThat(openToDone.map { it.name }).containsExactlyInAnyOrder("완료", "조건부 승인")
        assertThat(openToDone.map { it.id }.toSet())
            .describedAs("같은 상태쌍이라도 전환 id 는 서로 달라야 한다 — id 가 1급 식별자다")
            .hasSize(2)
    }

    // ── RED 4. 출발지 없는 INITIAL 행 ──────────────────────────────────────────

    @Test
    fun `출발지 없는 INITIAL 행이 있어도 읽기가 성공한다`() {
        assertThatCode { repository.findByKey("global-initial") }.doesNotThrowAnyException()

        val workflow = repository.findByKey("global-initial")
        val initial = workflow?.transitions?.firstOrNull { it.kind == TransitionKind.INITIAL }
        assertThat(initial).describedAs("INITIAL 전환을 못 읽었다").isNotNull()
        assertThat(initial!!.fromStateKey).isNull()
        assertThat(initial.toStateKey).isEqualTo("open")

        val normal = workflow.transitions.firstOrNull { it.name == "시작" }
        assertThat(normal?.fromStateKey)
            .describedAs("같은 워크플로우의 NORMAL 전환은 종전대로 출발 상태가 읽혀야 한다")
            .isEqualTo("open")
    }

    @Test
    fun `전환은 display_order 순서로 읽힌다`() {
        val workflow = repository.findByKey("legacy-normal")

        assertThat(workflow?.transitions.orEmpty().map { it.name })
            .describedAs("V207 이 row_number() 로 채운 display_order 가 편집기 표시 순서의 정본이다")
            .containsExactly("시작", "완료", "조건부 승인")
    }

    // ── RED 5. 수정이 출발지를 지운다 ──────────────────────────────────────────

    @Test
    fun `updateTransition 이 GLOBAL 로 바꾸면 출발 상태가 사라진다`() {
        val key = "update-to-global"
        val transitionId =
            seedFreshWorkflow(key, listOf(TransitionSeed("NORMAL", "완료", "open", "done", 1)))
                .getValue("$key/완료")

        writeRepository.updateTransition(
            transitionId = transitionId,
            kind = TransitionKind.GLOBAL.name,
            name = "즉시 완료",
            fromStatusId = null,
            toStatusId = compositionIdsOf(key).getValue("done"),
        )

        val updated = repository.findByKey(key)?.transitions?.firstOrNull { it.id == transitionId }
        assertThat(updated?.kind).isEqualTo(TransitionKind.GLOBAL)
        assertThat(updated?.fromStateKey).isNull()
        assertThat(updated?.toStateKey).isEqualTo("done")
    }

    // ── RED 6. 수정이 도착지를 바꾼다 ──────────────────────────────────────────

    @Test
    fun `updateTransition 이 도착 상태를 새로 지정한 상태로 바꾼다`() {
        val key = "update-target-state"
        val transitionId =
            seedFreshWorkflow(key, listOf(TransitionSeed("NORMAL", "시작", "open", "done", 1)))
                .getValue("$key/시작")
        val composition = compositionIdsOf(key)

        writeRepository.updateTransition(
            transitionId = transitionId,
            kind = TransitionKind.NORMAL.name,
            name = "시작",
            fromStatusId = composition.getValue("open"),
            toStatusId = composition.getValue("in-progress"),
        )

        val updated = repository.findByKey(key)?.transitions?.firstOrNull { it.id == transitionId }
        assertThat(updated?.toStateKey)
            .describedAs("도착지는 새로 지정한 상태여야 한다")
            .isEqualTo("in-progress")
    }
}
