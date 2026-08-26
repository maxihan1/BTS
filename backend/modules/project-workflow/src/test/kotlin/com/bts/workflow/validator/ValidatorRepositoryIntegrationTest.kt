// ValidatorRepository 통합 테스트 — Testcontainers + Flyway 전량 + workflow/state/transition 시드

package com.bts.workflow.validator

import com.bts.workflow.testsupport.insertWorkflowStatus
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
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
import java.util.UUID

/**
 * ValidatorRepository Testcontainers 통합 테스트.
 *
 * `workflow_validators` 는 V200 기존 테이블이고, `transition_id` 는 `workflow_transitions` 로 향하는
 * **FK(ON DELETE CASCADE)** 다. 그래서 전환 행 픽스처를 먼저 심어야 INSERT 자체가 성립한다 —
 * 컨테이너·마이그레이션 배선과 workflow/state 시드는 형제인 `PostActionRepositoryIntegrationTest` 와 같다.
 *
 * 검증 범위.
 * - findByTransitionId: display_order ASC 정렬
 * - findByTransitionId: display_order 동률이면 id ASC 로 확정 (2차 키)
 * - insert → findByTransitionId: config JSONB 왕복
 * - update: type / config / displayOrder 갱신 반영
 * - deleteById: 그 행만 빠지고 나머지는 남는다
 *
 * 전환은 **테스트마다 새로 만든다**. 하나를 공유하면 앞선 테스트가 남긴 행이 정렬·삭제 단언을 오염시키고,
 * 그 오염을 `@TestMethodOrder` 로 막으면 실행 순서에 기대는 테스트가 된다.
 * V207 이 `UNIQUE(workflow_id, from_state_id, to_state_id)` 를 풀었으므로 같은 상태쌍에 여러 전환을 둘 수 있다.
 */
@Testcontainers
class ValidatorRepositoryIntegrationTest {
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

        lateinit var repository: ValidatorRepository
        private lateinit var fromStateId: UUID
        private lateinit var toStateId: UUID
        private lateinit var workflowId: UUID

        @BeforeAll
        @JvmStatic
        fun setup() {
            migrateToV200()
            createIssueTypesStub()
            migrateRemaining()
            seedWorkflowAndStates()

            val dataSource = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            repository = ValidatorRepository(DSL.using(dataSource, SQLDialect.POSTGRES), ObjectMapper())
        }

        /** 1단계 — V200 까지 적용 (cross-BC issue-tracking 포함). */
        private fun migrateToV200() {
            flyway().target("200").load().migrate()
        }

        /** 2단계 — 나머지 마이그레이션 전체 적용. */
        private fun migrateRemaining() {
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

        /** issue_types 스텁 — V201+ FK 통과용. */
        private fun createIssueTypesStub() {
            connection().use { conn ->
                conn.prepareStatement(
                    """
                    CREATE TABLE IF NOT EXISTS issue_types (
                        id          BIGSERIAL    PRIMARY KEY,
                        key         VARCHAR(30)  NOT NULL UNIQUE,
                        name        VARCHAR(255) NOT NULL,
                        is_standard BOOLEAN      NOT NULL DEFAULT FALSE,
                        created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
                        updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
                        deleted_at  TIMESTAMPTZ
                    )
                    """.trimIndent(),
                ).use { it.execute() }
            }
        }

        /** 워크플로우 1개 + 상태 2개. 전환은 테스트마다 [newTransition] 이 만든다. */
        private fun seedWorkflowAndStates() {
            connection().use { conn ->
                conn.prepareStatement(
                    "INSERT INTO workflows (key, name) VALUES (?, ?)" +
                        " ON CONFLICT (key) WHERE deleted_at IS NULL DO NOTHING",
                ).use { stmt ->
                    stmt.setString(1, "validator-test-wf")
                    stmt.setString(2, "validator 테스트 워크플로우")
                    stmt.executeUpdate()
                }

                workflowId =
                    conn.prepareStatement("SELECT id FROM workflows WHERE key = ?").use { stmt ->
                        stmt.setString(1, "validator-test-wf")
                        stmt.executeQuery().use { rs ->
                            rs.next()
                            rs.getObject(1) as UUID
                        }
                    }

                fromStateId = insertWorkflowStatus(conn, workflowId, "open", "Open", "TODO", 1)
                toStateId = insertWorkflowStatus(conn, workflowId, "done", "Done", "DONE", 2)
            }
        }

        /** 빈 전환 1개를 새로 심고 그 id 를 준다. 테스트 간 격리의 단위다. */
        fun newTransition(name: String): UUID =
            connection().use { conn ->
                conn.prepareStatement(
                    "INSERT INTO workflow_transitions (workflow_id, from_state_id, to_state_id, name)" +
                        " VALUES (?, ?, ?, ?) RETURNING id",
                ).use { stmt ->
                    stmt.setObject(1, workflowId)
                    stmt.setObject(2, fromStateId)
                    stmt.setObject(3, toStateId)
                    stmt.setString(4, name)
                    stmt.executeQuery().use { rs ->
                        rs.next()
                        rs.getObject(1) as UUID
                    }
                }
            }

        /**
         * 컨테이너에 새 JDBC 커넥션 1개.
         *
         * 식(`=`) 본문으로 접으면 한 줄이 120자를 넘어 detekt `MaxLineLength` 에 걸린다.
         * 반대로 두 줄로 펼치면 ktlint 가 「본문이 시그니처와 같은 줄에 들어간다」로 되돌리라 한다.
         * 두 규칙이 서로 반대를 요구하므로 블록 본문이 유일한 교집합이다.
         */
        private fun connection(): Connection {
            return DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)
        }

        /**
         * `id` 를 **명시 지정**해 validator 행 1건을 심는다. `display_order` 는 0 고정이다.
         *
         * [ValidatorRepository.insert] 를 쓰지 않는 이유. 그쪽은 id 를 `gen_random_uuid()` 에 맡기므로
         * 삽입 순서와 id 순서의 관계가 실행마다 달라진다. 2차 키 단언은 **삽입 순서와 id 오름차순이
         * 어긋난 상태**를 재현해야 뜻이 있고, 어긋남이 우연에 달려 있으면 그 단언은 「가끔 red」가 된다.
         * 그래서 id 를 테스트가 쥔다.
         *
         * @param transitionId 소속 전환 UUID.
         * @param id 심을 행의 UUID. 호출자가 정한다.
         * @param type 규칙 타입 식별자.
         */
        fun insertValidatorWithId(
            transitionId: UUID,
            id: UUID,
            type: String,
        ) {
            connection().use { conn ->
                conn.prepareStatement(
                    "INSERT INTO workflow_validators (id, transition_id, type, display_order)" +
                        " VALUES (?, ?, ?, 0)",
                ).use { stmt ->
                    stmt.setObject(1, id)
                    stmt.setObject(2, transitionId)
                    stmt.setString(3, type)
                    stmt.executeUpdate()
                }
            }
        }
    }

    @Test
    fun `findByTransitionId 는 display_order ASC 로 돌려준다`() {
        val transitionId = newTransition("정렬 검증 전환")
        repository.insert(transitionId, "not-status-category", mapOf("category" to "DONE"), 30)
        repository.insert(transitionId, "RequiredField", mapOf("field" to "resolution"), 10)
        repository.insert(transitionId, "permission-check", mapOf("permission" to "EDIT_ISSUE"), 20)

        val result = repository.findByTransitionId(transitionId)

        assertThat(result.map { it.displayOrder }).containsExactly(10, 20, 30)
        assertThat(result.map { it.type })
            .containsExactly("RequiredField", "permission-check", "not-status-category")
    }

    /**
     * display_order 동률의 확정 순서 계약.
     *
     * 형제인 위 테스트는 10·20·30 을 써 **동률 경로를 한 번도 타지 않는다.** 그런데 `display_order` 는
     * `NOT NULL DEFAULT 0` 이라 요청이 값을 생략하면 한 전환의 모든 행이 0 이 되고, 그때 단일 키 정렬은
     * 전순서가 아니라 PostgreSQL 이 순서를 보장하지 않는다 — 쓰기 한 번이 관리자 화면의 행 순서를 뒤섞는다.
     * `TransitionRuleRepository.findByTransitionId` 의 2차 키 `id ASC` 가 그 확정 장치이고 이 테스트가
     * 그것을 지키는 유일한 자리다.
     *
     * ★ **삽입 순서를 id 오름차순과 일부러 어긋나게** 넣는다(3 → 1 → 2). 같은 순서로 넣으면 2차 키를
     * 지워도 「삽입 순서대로 나왔을 뿐」이 그대로 통과해 아무것도 못 지킨다.
     */
    @Test
    fun `findByTransitionId 는 display_order 동률을 id ASC 로 확정한다`() {
        val transitionId = newTransition("동률 확정 전환")
        insertValidatorWithId(transitionId, TIE_ID_ASC_3, "not-status-category")
        insertValidatorWithId(transitionId, TIE_ID_ASC_1, "RequiredField")
        insertValidatorWithId(transitionId, TIE_ID_ASC_2, "permission-check")

        val result = repository.findByTransitionId(transitionId)

        assertThat(result.map { it.displayOrder }).containsExactly(0, 0, 0)
        assertThat(result.map { it.id })
            .containsExactly(TIE_ID_ASC_1, TIE_ID_ASC_2, TIE_ID_ASC_3)
            .isSorted()
    }

    @Test
    fun `insert 한 행을 config JSONB 그대로 읽는다`() {
        val transitionId = newTransition("config 왕복 전환")
        val config =
            mapOf(
                "field" to "resolution",
                "required" to true,
                "threshold" to 3,
                "allowed" to listOf("done", "closed"),
                "nested" to mapOf("scope" to "ISSUE"),
            )

        val inserted = repository.insert(transitionId, "RequiredField", config, 0)

        assertThat(inserted.transitionId).isEqualTo(transitionId)
        assertThat(inserted.type).isEqualTo("RequiredField")

        val found = repository.findByTransitionId(transitionId).single()
        assertThat(found.id).isEqualTo(inserted.id)
        assertThat(found.config).isEqualTo(config)
    }

    @Test
    fun `update 가 type · config · displayOrder 를 바꾼다`() {
        val transitionId = newTransition("수정 검증 전환")
        val row = repository.insert(transitionId, "RequiredField", mapOf("field" to "resolution"), 5)

        val updated =
            repository.update(row.id, "not-status-category", mapOf("category" to "TODO"), 42)

        assertThat(updated.id).isEqualTo(row.id)
        assertThat(updated.transitionId).isEqualTo(transitionId)

        val found = repository.findByTransitionId(transitionId).single()
        assertThat(found.type).isEqualTo("not-status-category")
        assertThat(found.config).isEqualTo(mapOf("category" to "TODO"))
        assertThat(found.displayOrder).isEqualTo(42)
    }

    @Test
    fun `deleteById 후 findByTransitionId 가 그 행을 빼고 돌려준다`() {
        val transitionId = newTransition("삭제 검증 전환")
        val doomed = repository.insert(transitionId, "RequiredField", mapOf("field" to "resolution"), 0)
        val survivor = repository.insert(transitionId, "permission-check", mapOf("permission" to "EDIT_ISSUE"), 1)

        repository.deleteById(doomed.id)

        val result = repository.findByTransitionId(transitionId)
        assertThat(result.map { it.id }).containsExactly(survivor.id)
    }
}

/**
 * 동률 확정 검증용 고정 id 3개. **이름의 숫자가 곧 오름차순 자리**다.
 *
 * 마지막 한 바이트만 `01` · `02` · `03` 으로 다르다. 그래서 PostgreSQL 의 바이트열 비교와
 * [UUID.compareTo] 가 같은 순서를 주고, 단언의 `isSorted()` 가 기대값 리터럴이 정말 오름차순인지까지
 * 함께 검사한다 — 누가 세 리터럴의 자리를 바꾸면 그 자리에서 red 다.
 */
private val TIE_ID_ASC_1: UUID = UUID.fromString("00000000-0000-4000-8000-000000000001")

/** [TIE_ID_ASC_1] 의 둘째 자리. */
private val TIE_ID_ASC_2: UUID = UUID.fromString("00000000-0000-4000-8000-000000000002")

/** [TIE_ID_ASC_1] 의 셋째 자리. */
private val TIE_ID_ASC_3: UUID = UUID.fromString("00000000-0000-4000-8000-000000000003")
