// 전환 읽기/쓰기 통합 테스트 — workflow_statuses 로 id·kind 를 복원하고 쓰기가 구 세대 컬럼을 남기지 않는가

package com.bts.workflow.repository

import com.bts.workflow.domain.TransitionKind
import com.bts.workflow.testsupport.insertWorkflowStatus
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
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

/** 상태 키 → id 매핑 2벌. [composition] 은 `workflow_statuses.id`, [legacy] 는 구형 `workflow_states.id`. */
private data class StatusIds(
    val composition: Map<String, UUID>,
    val legacy: Map<String, UUID>,
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
 * ### ★ 이 테스트가 막는 프로덕션 전용 결함
 * V207 의 INITIAL 백필 행은 구 컬럼 `from_state_id`·`to_state_id` 가 **NULL** 이다. 읽기가 아직
 * 구 컬럼을 `as UUID` 로 캐스팅하면 그 행에서 `NullPointerException` 이 난다. 빈 DB 에 마이그레이션만
 * 돌리면 백필 대상이 0행이라 **INITIAL 행 자체가 생기지 않아** 이 결함이 테스트에 드러나지 않는다.
 * 그래서 여기서는 구 컬럼이 NULL 인 INITIAL·GLOBAL 행을 **손으로 심어** 그 상태를 재현한다.
 *
 * 워크플로우 2개를 쓴다. `legacy-normal` 은 구 컬럼이 채워진 NORMAL 행만 두어 `id`·`kind`·순서
 * 계약을 보고, `global-initial` 은 구 컬럼이 NULL 인 INITIAL·GLOBAL 을 섞어 그 결함을 본다.
 */
@Testcontainers
class WorkflowRepositoryTransitionTest {
    companion object {
        // quay.io/tembo/pg16-pgmq:latest — V004 pgmq 확장 요구. ADR 2026-05-22-pgmq-postgres-image.
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

        private val stateSeeds =
            listOf(
                Triple("open", "열림", "TODO"),
                Triple("in-progress", "진행 중", "IN_PROGRESS"),
                Triple("done", "완료", "DONE"),
            )

        /** 구 컬럼이 채워진 NORMAL 전환만. 삽입 순서와 display_order 를 **일부러 어긋나게** 둔다. */
        private val legacySeeds =
            listOf(
                TransitionSeed("NORMAL", "완료", "open", "done", 2),
                TransitionSeed("NORMAL", "조건부 승인", "open", "done", 3),
                TransitionSeed("NORMAL", "시작", "open", "in-progress", 1),
            )

        /** 구 컬럼이 NULL 인 INITIAL·GLOBAL 이 섞인 워크플로우 — V207 백필 직후의 실제 모양. */
        private val originlessSeeds =
            listOf(
                TransitionSeed("INITIAL", "이슈 생성", null, "open", 0),
                TransitionSeed("GLOBAL", "즉시 완료", null, "done", 1),
                TransitionSeed("NORMAL", "시작", "open", "in-progress", 2),
            )

        lateinit var repository: WorkflowRepository

        /** 쓰기 경로. 구 세대 컬럼이 정리되는지 보려면 `updateTransition` 을 실제로 불러야 한다. */
        lateinit var writeRepository: WorkflowWriteRepository

        /** 전환 이름 → DB 가 부여한 `workflow_transitions.id`. 읽기가 그대로 채웠는지 대조한다. */
        lateinit var seededTransitionIds: Map<String, UUID>

        @BeforeAll
        @JvmStatic
        fun setup() {
            migrate()
            seededTransitionIds = seedData()
            val dataSource = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            val dsl = DSL.using(dataSource, SQLDialect.POSTGRES)
            repository = WorkflowRepository(dsl)
            writeRepository = WorkflowWriteRepository(dsl)
        }

        /** Flyway 2단계 — V201(workflow_schemes) 이 issue_types 를 cross-BC FK 로 요구한다. */
        private fun migrate() {
            flyway().target("200").load().migrate()
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
            flyway().load().migrate()
        }

        private fun flyway() =
            Flyway.configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .placeholderReplacement(false)
                .locations(
                    "classpath:db/migration/issue-tracking",
                    "classpath:db/migration/project-workflow",
                )

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
            val legacy =
                stateSeeds.withIndex().associate { (order, spec) ->
                    val (stateKey, name, category) = spec
                    stateKey to insertWorkflowStatus(conn, workflowId, stateKey, name, category, order)
                }
            val ids = StatusIds(composition = compositionIds(conn, workflowId), legacy = legacy)
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

        /**
         * 전환 1행. **구 컬럼은 NORMAL 일 때만 채운다** — GLOBAL·INITIAL 을 NULL 로 두는 것이
         * V207 백필 직후의 실제 모양이고, 이 테스트가 재현하려는 상태다.
         */
        private fun insertTransition(
            conn: Connection,
            workflowId: UUID,
            seed: TransitionSeed,
            ids: StatusIds,
        ): UUID {
            val normal = seed.kind == "NORMAL"
            return conn.prepareStatement(
                "INSERT INTO workflow_transitions" +
                    " (workflow_id, kind, name, from_status_id, to_status_id, display_order," +
                    " from_state_id, to_state_id)" +
                    " VALUES (?, ?, ?, ?, ?, ?, ?, ?) RETURNING id",
            ).use { stmt ->
                stmt.setObject(1, workflowId)
                stmt.setString(2, seed.kind)
                stmt.setString(3, seed.name)
                stmt.setUuidOrNull(4, seed.fromStatusKey?.let { ids.composition.getValue(it) })
                stmt.setObject(5, ids.composition.getValue(seed.toStatusKey))
                stmt.setInt(6, seed.displayOrder)
                stmt.setUuidOrNull(7, if (normal) ids.legacy.getValue(seed.fromStatusKey.orEmpty()) else null)
                stmt.setUuidOrNull(8, if (normal) ids.legacy.getValue(seed.toStatusKey) else null)
                stmt.executeQuery().use { rs ->
                    rs.next()
                    rs.getObject(1) as UUID
                }
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

        /** 그 워크플로우의 상태 키 → `workflow_statuses.id`. 쓰기 API 에 넘길 **신** 컬럼 값이다. */
        private fun compositionIdsOf(workflowKey: String): Map<String, UUID> =
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
                compositionIds(conn, requireNotNull(writeRepository.findLiveIdByKey(workflowKey)))
            }

        /** 그 전환 행의 **구** 컬럼 실측값. 읽기를 거치지 않고 저장된 값 자체를 본다. */
        private fun legacyColumnsOf(transitionId: UUID): LegacyColumns =
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
                conn.prepareStatement(
                    "SELECT from_state_id, to_state_id FROM workflow_transitions WHERE id = ?",
                ).use { stmt ->
                    stmt.setObject(1, transitionId)
                    stmt.executeQuery().use { rs ->
                        rs.next()
                        LegacyColumns(rs.getObject(1) as UUID?, rs.getObject(2) as UUID?)
                    }
                }
            }

        /** 신 세대 쓰기가 다녀간 행 수. 0 이면 아래 판별식이 저절로 통과하므로 함께 단언한다. */
        private fun newGenerationRowCount(): Int =
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
                conn.prepareStatement(
                    "SELECT count(*) FROM workflow_transitions WHERE to_status_id IS NOT NULL",
                ).use { stmt ->
                    stmt.executeQuery().use { rs ->
                        rs.next()
                        rs.getInt(1)
                    }
                }
            }

        /** 불변식을 어긴 전환의 이름 목록. 비어 있어야 한다. 질의 정본은 [DIVERGENCE_SQL]. */
        private fun divergentTransitionNames(): List<String> =
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
                conn.prepareStatement(DIVERGENCE_SQL).use { stmt ->
                    stmt.executeQuery().use { rs ->
                        buildList { while (rs.next()) add(rs.getString(1)) }
                    }
                }
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

    // ── RED 4. ★ 프로덕션 전용 함정 — 구 컬럼이 NULL 인 행 ────────────────────

    @Test
    fun `구 컬럼이 NULL 인 INITIAL 행이 있어도 읽기가 성공한다`() {
        assertThatCode { repository.findByKey("global-initial") }
            .describedAs(
                "V207 백필이 만든 INITIAL 행은 from_state_id·to_state_id 가 NULL 이다. " +
                    "읽기가 구 컬럼을 as UUID 로 캐스팅하면 여기서 NPE 가 난다 — workflow_statuses 를 봐야 한다",
            ).doesNotThrowAnyException()

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

    // ── RED 5. ★ 수정이 구 세대를 남기면 읽기 폴백이 그것을 되살린다 ──────────

    @Test
    fun `updateTransition 이 GLOBAL 로 바꾸면 구 출발 상태가 되살아나지 않는다`() {
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

        val legacy = legacyColumnsOf(transitionId)
        assertThat(legacy.fromStateId)
            .describedAs(
                "GLOBAL 로 바꿔 from_status_id 를 NULL 로 만들었는데 구 from_state_id 가 남았다. " +
                    "읽기는 신 컬럼이 NULL 이면 구 컬럼으로 폴백하므로 바꾸기 전 출발 상태가 되살아난다",
            ).isNull()
        assertThat(legacy.toStateId)
            .describedAs("신 컬럼이 정본이므로 구 도착 컬럼도 함께 비워야 두 세대가 갈라지지 않는다")
            .isNull()

        assertThatCode { repository.findByKey(key) }
            .describedAs(
                "구 출발 컬럼이 남으면 폴백이 fromStateKey 를 채운다. GLOBAL 인데 출발지가 있는 꼴이라 " +
                    "Workflow.of() invariant 6 이 IllegalArgumentException 을 던지고 워크플로우 전체가 못 읽힌다",
            ).doesNotThrowAnyException()

        val updated = repository.findByKey(key)?.transitions?.firstOrNull { it.id == transitionId }
        assertThat(updated?.kind).isEqualTo(TransitionKind.GLOBAL)
        assertThat(updated?.fromStateKey).isNull()
        assertThat(updated?.toStateKey).isEqualTo("done")
    }

    // ── RED 6. 판별식 — 신·구가 다른 상태를 가리키는 행이 생기지 않는다 ───────

    @Test
    fun `쓰기 뒤에도 신 세대와 구 세대가 다른 상태를 가리키는 전환이 없다`() {
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

        // 비-공허 짝. 검사 대상 행이 0 이면 아래 판별식은 아무것도 막지 않은 채 초록이 된다.
        assertThat(newGenerationRowCount())
            .describedAs("신 컬럼이 찬 전환이 하나도 없다 — 판별식이 공허하게 통과한다")
            .isGreaterThan(0)

        assertThat(divergentTransitionNames())
            .describedAs(
                "신 컬럼(workflow_statuses)과 구 컬럼(workflow_states)이 서로 다른 상태를 가리키는 행이 " +
                    "남았다. 읽기 폴백이 그 구 값을 되살리는 순간 조용한 오답이 된다 — 신 컬럼을 쓰는 " +
                    "경로는 구 컬럼을 같은 값으로 맞추거나 NULL 로 비워야 한다 (wave 공통 불변식)",
            ).isEmpty()

        val updated = repository.findByKey(key)?.transitions?.firstOrNull { it.id == transitionId }
        assertThat(updated?.toStateKey)
            .describedAs("신 컬럼이 정본이므로 도착지는 새로 지정한 상태여야 한다")
            .isEqualTo("in-progress")
    }
}

/** 전환 1행의 **구 세대** 컬럼 실측값. 둘 다 null 인 것이 신 세대 쓰기가 지나간 행의 모양이다. */
private data class LegacyColumns(
    val fromStateId: UUID?,
    val toStateId: UUID?,
)

/**
 * 신·구 두 세대가 **서로 다른 상태를 가리키는** 전환 행을 찾는다 — 이 wave 공통 불변식의 판별식이다.
 *
 * 두 세대는 상태 key 를 다리로 이어져 있다. 신 컬럼은 `workflow_statuses` → `statuses.key`,
 * 구 컬럼은 `workflow_states.key` 다. **신 컬럼이 찬 행만** 본다 — 구 컬럼만 있는 행은 아직 이주
 * 전이고 읽기 폴백의 정당한 대상이다 (3단 분할 2단계).
 *
 * `from` 은 신 컬럼이 NULL 인데 구 컬럼에 값이 있는 경우도 어긋남으로 센다. 그 조합이 바로 폴백이
 * 지워진 출발 상태를 되살리는 자리라 `IS DISTINCT FROM` 으로 NULL 까지 대조한다.
 *
 * LEFT JOIN 이 여럿이지만 전부 PK 대조라 행이 곱으로 늘지 않고 집계도 하지 않는다
 * (PR #31 의 cartesian product 사고와는 다른 모양이다).
 */
private val DIVERGENCE_SQL =
    """
    SELECT t.name
      FROM workflow_transitions t
      LEFT JOIN workflow_statuses nf ON nf.id = t.from_status_id
      LEFT JOIN statuses ns ON ns.id = nf.status_id
      LEFT JOIN workflow_statuses nt ON nt.id = t.to_status_id
      LEFT JOIN statuses nd ON nd.id = nt.status_id
      LEFT JOIN workflow_states lf ON lf.id = t.from_state_id
      LEFT JOIN workflow_states lt ON lt.id = t.to_state_id
     WHERE t.to_status_id IS NOT NULL
       AND ((t.from_state_id IS NOT NULL AND lf.key IS DISTINCT FROM ns.key)
         OR (t.to_state_id IS NOT NULL AND lt.key IS DISTINCT FROM nd.key))
    """.trimIndent()
