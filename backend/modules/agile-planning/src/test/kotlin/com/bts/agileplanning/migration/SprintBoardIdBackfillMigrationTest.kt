// V506 백필 검증 — 마이그레이션 전 데이터를 심고 sprints.board_id 채움·기존 보드 무변경을 확인 (FR-BD-04 D5)

package com.bts.agileplanning.migration

import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/**
 * `V506__sprint_board_id.sql` 의 **백필 결과**를 검증한다.
 *
 * 형제 [SprintSchemaMigrationTest]·[BoardSchemaMigrationTest] 는 컬럼·제약의 **구조**만 본다.
 * 구조가 맞아도 백필이 틀리면 사용자는 스프린트를 잃거나 영원히 빈 보드를 받는다. 이 클래스가 그 간극을 맡는다.
 *
 * ## 왜 전용 컨테이너인가
 * [com.bts.agileplanning.AgilePlanningTestcontainersConfig] 의 공유 컨테이너는 `migrateOnce` 가 무조건
 * **최신까지** 밀어버려 「V505 이전 상태에 데이터 심기」가 불가능하다. 여기서는 `@Container` 로 전용
 * 컨테이너를 띄우고 3단으로 나눈다 — `target("504")` → seed → target 없이 `migrate()`.
 *
 * ★ 마지막 단계에 `target("506")` 을 쓰지 않는다. 버전을 숫자로 고정하면 나중에 V507 이 들어올 때
 * 이 클래스가 통째로 죽는다(`V207MigrationTest:114` 가 같은 함정을 명시한다).
 *
 * ★ 깨끗한 컨테이너에서 잰다. 공유 개발 DB 의 선재 행은 「마이그레이션이 안 넣었는데 데이터가 있는」
 * 가짜 그린을 만든다(memory: shared-dev-db-preexisting-rows-fake-green).
 *
 * ## 심는 데이터 (V504 시점 — 이때는 board_type·board_id 컬럼이 아직 없다)
 * - `BKFL` — 칸반이 될 보드 1개 + 컬럼 3개 · 스프린트 2건(ACTIVE 1 + **soft-deleted 1**)
 * - `NOBD` — **보드가 하나도 없는** 프로젝트 + 스프린트 1건 (스펙 E-1)
 *
 * ## 검증
 * 1. 스프린트 **전량**(soft-deleted 포함)이 `board_id` 를 갖는다 — NOT NULL 승격이 통과했다
 * 2. 기존 칸반 보드의 이름·컬럼이 **무변경**이고 종류가 `KANBAN` 이다
 * 3. 신설 스크럼 보드는 이름 규칙을 따르고 컬럼이 소스 칸반의 **복제**다
 * 4. **E-1** — 보드 0개였던 프로젝트도 스크럼 보드를 얻고, 컬럼은 0개이며, 승격이 통과한다
 * 5. 부분 인덱스 `idx_sprints_board_active` 가 술어까지 맞다
 * 6. `boards_board_type_allowed` CHECK 가 존재한다
 *
 * 참조. `docs/specs/2026-09-01-board-scrum-schema.md` §데이터 모델·엣지 케이스 /
 * `docs/adr/2026-09-01-board-type-and-active-sprint.md` D1·D2.
 */
@Testcontainers
class SprintBoardIdBackfillMigrationTest {
    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))
                .withDatabaseName("bts_agileplanning_backfill_test")
                .withUsername("bts")
                .withPassword("bts_test")

        /** 컬럼을 복제할 소스가 되는 기존 보드의 id — **가장 오래된 활성 칸반**이라 ③ 이 이걸 골라야 한다. */
        private val kanbanBoardId: UUID = UUID.randomUUID()

        /**
         * 더 **최근**에 만들어진 두 번째 칸반 보드. 컬럼 구성이 소스와 다르다.
         *
         * ③ 의 `ORDER BY b.created_at, b.id LIMIT 1` 을 DESC 로 뒤집으면 이 보드가 복제되므로 red 가 난다.
         * 이 픽스처가 없으면 정렬을 통째로 지워도 6개 테스트가 전부 초록이었다(리뷰 지적).
         */
        private val newerKanbanBoardId: UUID = UUID.randomUUID()

        /**
         * **soft-deleted** 칸반 보드 — 가장 오래됐지만 삭제됐으므로 복제 소스가 되면 안 된다.
         *
         * ③ 의 `AND b.deleted_at IS NULL` 을 지우면 이 보드가 선택돼 red 가 난다.
         */
        private val deletedKanbanBoardId: UUID = UUID.randomUUID()

        /** `BKFL` 의 ACTIVE 스프린트. */
        private val activeSprintId: UUID = UUID.randomUUID()

        /** `BKFL` 의 soft-deleted 스프린트 — 백필이 이것도 챙겨야 NOT NULL 승격이 통과한다. */
        private val deletedSprintId: UUID = UUID.randomUUID()

        /** 보드가 하나도 없는 프로젝트의 스프린트 (E-1). */
        private val orphanSprintId: UUID = UUID.randomUUID()

        /**
         * **스프린트가 전부 soft-deleted 인 프로젝트**의 유일한 스프린트.
         *
         * V506 ② 가 `deleted_at IS NULL` 로 거르면 이 프로젝트만 스크럼 보드를 못 얻고,
         * ④ 가 붙일 보드를 못 찾아 ⑤ NOT NULL 승격이 **마이그레이션째 실패**한다.
         * 다른 픽스처는 살아있는 스프린트를 하나씩 갖고 있어 그 실수를 드러내지 못한다.
         */
        private val allDeletedSprintId: UUID = UUID.randomUUID()

        /**
         * 소스 칸반 보드의 컬럼 — 신설 스크럼 보드가 이것을 그대로 복제해야 한다.
         *
         * ★ category 를 셋 다 다르게 둔다. 전부 같은 값이면 V506 ③ 의 `c.category` 를 리터럴로 바꾸는
         * 뮤테이션이 초록으로 통과한다 — 복제 **소스 선택**만 지키고 복제 **내용**은 안 지키게 된다.
         */
        private val SOURCE_COLUMNS = listOf("open" to "TODO", "in-progress" to "IN_PROGRESS", "closed" to "DONE")

        private fun flyway(target: String?) =
            Flyway.configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .placeholderReplacement(false)
                .locations("classpath:db/migration/agile-planning")
                .let { if (target == null) it else it.target(target) }
                .load()

        @BeforeAll
        @JvmStatic
        fun migrateWithSeedInBetween() {
            // 1단계 — V505 직전까지만. 이 시점 boards 에는 board_type 이, sprints 에는 board_id 가 없다.
            flyway("504").migrate()

            // 2단계 — 마이그레이션이 마주칠 「기존 데이터」를 심는다.
            conn().use { c ->
                c.autoCommit = false
                // ★ 세 보드의 created_at 을 명시로 벌린다 — ③ 의 「가장 오래된 활성」 규칙을 구별하려면
                // 삭제된 것이 가장 오래되고, 소스가 그다음, 더 최근 것이 마지막이어야 한다.
                seedKanban(c, KanbanSeed(deletedKanbanBoardId, "BKFL 삭제된 보드", 30, true, listOf("gone" to "DONE")))
                seedKanban(c, KanbanSeed(kanbanBoardId, "BKFL 개발 보드", 20, false, SOURCE_COLUMNS))
                seedKanban(c, KanbanSeed(newerKanbanBoardId, "BKFL 새 보드", 10, false, listOf("newer" to "IN_PROGRESS")))
                seedSprint(c, activeSprintId, "BKFL", status = "ACTIVE", deleted = false)
                seedSprint(c, deletedSprintId, "BKFL", status = "COMPLETED", deleted = true)
                seedSprint(c, orphanSprintId, "NOBD", status = "PLANNED", deleted = false)
                seedSprint(c, allDeletedSprintId, "ALLDEL", status = "COMPLETED", deleted = true)
                c.commit()
            }

            // 3단계 — 나머지 전량. ★ target 을 고정하지 않는다(미래 V507 이 이 클래스를 죽이지 않도록).
            flyway(null).migrate()
        }

        private fun conn(): Connection =
            DriverManager.getConnection(
                postgres.jdbcUrl,
                postgres.username,
                postgres.password,
            )

        /** 칸반 보드 하나의 시드 명세. `daysAgo` 가 클수록 오래된 보드다. */
        private data class KanbanSeed(
            val boardId: UUID,
            val name: String,
            val daysAgo: Int,
            val deleted: Boolean,
            val columns: List<Pair<String, String>>,
        )

        /**
         * 칸반이 될 보드 1개와 그 컬럼을 심는다.
         *
         * `created_at` 을 명시로 벌려 ③ 의 `ORDER BY b.created_at` 규칙이 결정적으로 판정되게 한다.
         * 기본값 `now()` 로 세 보드를 심으면 같은 트랜잭션 안이라 시각이 동일해 정렬이 무의미해진다.
         */
        private fun seedKanban(
            c: Connection,
            seed: KanbanSeed,
        ) {
            c.prepareStatement(
                "INSERT INTO boards (id, project_key, name, created_at, deleted_at)" +
                    " VALUES (?, ?, ?, now() - make_interval(days => ?), ?)",
            ).use { stmt ->
                stmt.setObject(1, seed.boardId)
                stmt.setString(2, "BKFL")
                stmt.setString(3, seed.name)
                stmt.setInt(4, seed.daysAgo)
                stmt.setObject(5, if (seed.deleted) Timestamp.from(Instant.now()) else null)
                stmt.execute()
            }
            seed.columns.forEachIndexed { order, (stateKey, category) ->
                c.prepareStatement(
                    "INSERT INTO board_columns (id, board_id, state_key, name, category, display_order)" +
                        " VALUES (?, ?, ?, ?, ?, ?)",
                ).use { stmt ->
                    stmt.setObject(1, UUID.randomUUID())
                    stmt.setObject(2, seed.boardId)
                    stmt.setString(3, stateKey)
                    stmt.setString(4, "컬럼 $stateKey")
                    stmt.setString(5, category)
                    stmt.setInt(6, order)
                    stmt.execute()
                }
            }
        }

        /** 이름은 어느 단언도 보지 않으므로 id 에서 파생한다. */
        private fun seedSprint(
            c: Connection,
            id: UUID,
            projectKey: String,
            status: String,
            deleted: Boolean,
        ) {
            c.prepareStatement(
                "INSERT INTO sprints (id, project_key, name, status, deleted_at)" +
                    " VALUES (?, ?, ?, ?, ${if (deleted) "now()" else "NULL"})",
            ).use { stmt ->
                stmt.setObject(1, id)
                stmt.setString(2, projectKey)
                stmt.setString(3, "Sprint ${id.toString().take(4)}")
                stmt.setString(4, status)
                stmt.execute()
            }
        }
    }

    // ── 헬퍼 ──────────────────────────────────────────────────────────────────

    // 커넥션을 닫으면 statement 와 result set 도 함께 닫힌다 — 중첩 use 를 쌓지 않는다.
    private fun <T> queryOne(
        sql: String,
        vararg params: Any?,
        read: (java.sql.ResultSet) -> T,
    ): T? {
        conn().use { c ->
            val rs = prepared(c, sql, params).executeQuery()
            return if (rs.next()) read(rs) else null
        }
    }

    private fun queryStrings(
        sql: String,
        vararg params: Any?,
    ): List<String> {
        conn().use { c ->
            val rs = prepared(c, sql, params).executeQuery()
            return generateSequence { if (rs.next()) rs.getString(1) else null }.toList()
        }
    }

    private fun prepared(
        c: Connection,
        sql: String,
        params: Array<out Any?>,
    ): java.sql.PreparedStatement =
        c.prepareStatement(sql).also { stmt ->
            params.forEachIndexed { i, p -> stmt.setObject(i + 1, p) }
        }

    private fun boardIdOf(sprintId: UUID): UUID? =
        queryOne("SELECT board_id FROM sprints WHERE id = ?", sprintId) { it.getObject(1) as UUID? }

    // ORDER BY 를 둔다 — 중복 스크럼 보드가 생기는 회귀가 「실행마다 결과가 흔들리는 플레이키」가
    // 아니라 아래 scrumBoardCountOf 단언의 red 로 드러나게 한다.
    private fun scrumBoardIdOf(projectKey: String): UUID? =
        queryOne(
            "SELECT id FROM boards WHERE project_key = ? AND board_type = 'SCRUM' ORDER BY created_at, id",
            projectKey,
        ) { it.getObject(1) as UUID }

    private fun scrumBoardCountOf(projectKey: String): Int =
        queryOne(
            "SELECT COUNT(*) FROM boards WHERE project_key = ? AND board_type = 'SCRUM'",
            projectKey,
        ) { it.getInt(1) } ?: 0

    // ── ① 전량이 board_id 를 갖는다 (soft-deleted 포함) ────────────────────────

    @Test
    fun `백필 후 모든 스프린트가 board_id 를 갖고 NOT NULL 이다`() {
        val nullCount = queryOne("SELECT COUNT(*) FROM sprints WHERE board_id IS NULL") { it.getInt(1) }
        assertThat(nullCount).isZero()

        // soft-deleted 스프린트도 챙겨야 한다 — 빠뜨리면 NOT NULL 승격 자체가 실패했을 것이다.
        assertThat(boardIdOf(deletedSprintId)).isNotNull()

        // ★ 결정적인 경우. 스프린트가 **전부** 삭제된 프로젝트도 보드를 얻어야 한다.
        // 다른 픽스처는 살아있는 스프린트를 하나씩 갖고 있어 ② 의 deleted_at 필터 실수를 못 드러낸다.
        assertThat(boardIdOf(allDeletedSprintId)).isEqualTo(scrumBoardIdOf("ALLDEL"))

        val isNullable =
            queryOne(
                "SELECT is_nullable FROM information_schema.columns" +
                    " WHERE table_schema = 'public' AND table_name = 'sprints' AND column_name = 'board_id'",
            ) { it.getString(1) }
        assertThat(isNullable).isEqualTo("NO")
    }

    // ── ② 기존 칸반 보드 무변경 ───────────────────────────────────────────────

    @Test
    fun `기존 보드는 이름과 컬럼이 그대로이고 종류가 KANBAN 이다`() {
        val name = queryOne("SELECT name FROM boards WHERE id = ?", kanbanBoardId) { it.getString(1) }
        assertThat(name).isEqualTo("BKFL 개발 보드")

        // ★ 승격하지 않는다. 승격하면 카드가 활성 스프린트 것만 남아 사용자가 보던 것이 사라진다(ADR A-3 기각).
        val boardType = queryOne("SELECT board_type FROM boards WHERE id = ?", kanbanBoardId) { it.getString(1) }
        assertThat(boardType).isEqualTo("KANBAN")

        val columns =
            queryStrings(
                "SELECT state_key FROM board_columns WHERE board_id = ? ORDER BY display_order",
                kanbanBoardId,
            )
        assertThat(columns).containsExactly("open", "in-progress", "closed")
    }

    // ── ③ 신설 스크럼 보드 + 컬럼 복제 ────────────────────────────────────────

    @Test
    fun `스프린트를 가진 프로젝트에 스크럼 보드가 신설되고 컬럼이 복제된다`() {
        val scrumBoardId = scrumBoardIdOf("BKFL")
        assertThat(scrumBoardId).isNotNull()

        val name = queryOne("SELECT name FROM boards WHERE id = ?", scrumBoardId) { it.getString(1) }
        // 이름 규칙은 BoardApplicationService.ensureScrumBoard 와 문자열이 같아야 한다 — 둘이 갈리면
        // 마이그레이션이 만든 보드와 앱이 만든 보드가 한 프로젝트에 둘 생긴다.
        assertThat(name).isEqualTo("BKFL 스크럼 보드")

        val columns =
            queryStrings(
                "SELECT state_key FROM board_columns WHERE board_id = ? ORDER BY display_order",
                scrumBoardId,
            )
        assertThat(columns).containsExactly("open", "in-progress", "closed")

        // 스프린트 전량이 신설 보드에 붙는다.
        assertThat(boardIdOf(activeSprintId)).isEqualTo(scrumBoardId)
        assertThat(boardIdOf(deletedSprintId)).isEqualTo(scrumBoardId)

        // ★ V506 ④ 가 「(project_key, SCRUM) 이 유일하다」는 전제 위에 선다. ② 의 DISTINCT 를 지우면
        // BKFL 은 스프린트 2건이라 보드가 2개 생기는데, ④ 의 UPDATE 는 임의 행을 골라 **조용히 성공**한다.
        // 이 단언이 없으면 그 위반이 red 가 아니라 비결정적 플레이키로만 드러난다.
        assertThat(scrumBoardCountOf("BKFL")).isEqualTo(1)
        assertThat(scrumBoardCountOf("NOBD")).isEqualTo(1)
        assertThat(scrumBoardCountOf("ALLDEL")).isEqualTo(1)

        // ★ 복제 소스는 **가장 오래된 활성** 칸반이다. 더 최근 보드("newer")도, soft-deleted 보드("gone")도 아니다.
        assertThat(columns).doesNotContain("newer", "gone")

        // ★ 소스 선택뿐 아니라 **복제 내용**도 지킨다. category 를 안 보면 V506 ③ 의 `c.category` 를
        // 리터럴로 바꾸는 뮤테이션이 초록으로 통과한다 — 세 값이 서로 달라야 그 구멍이 닫힌다.
        assertThat(
            queryStrings(
                "SELECT category FROM board_columns WHERE board_id = ? ORDER BY display_order",
                scrumBoardId,
            ),
        ).containsExactlyElementsOf(SOURCE_COLUMNS.map { it.second })
    }

    // ── ④ E-1. 보드가 하나도 없던 프로젝트 ────────────────────────────────────

    @Test
    fun `E-1 보드가 없던 프로젝트도 스크럼 보드를 얻고 컬럼은 0개다`() {
        val scrumBoardId = scrumBoardIdOf("NOBD")
        assertThat(scrumBoardId).isNotNull()
        assertThat(boardIdOf(orphanSprintId)).isEqualTo(scrumBoardId)

        // 복제할 칸반 보드가 없으므로 컬럼이 없다. 이 상태가 NOT NULL 승격을 막지 않아야 한다.
        val columnCount =
            queryOne("SELECT COUNT(*) FROM board_columns WHERE board_id = ?", scrumBoardId) { it.getInt(1) }
        assertThat(columnCount).isZero()

        // 영원히 빈 보드로 남지 않는 것은 조회 시 자가 치유의 책임이다
        // (BoardApplicationServiceTest `컬럼이 0개인 보드는 조회 시 컬럼이 시드되고 영속된다`).
    }

    // ── ⑤ 활성 스프린트 부분 인덱스 ───────────────────────────────────────────

    @Test
    fun `idx_sprints_board_active 가 부분 술어까지 갖춰 존재한다`() {
        val indexDef =
            queryOne(
                "SELECT indexdef FROM pg_indexes WHERE schemaname = 'public' AND indexname = ?",
                "idx_sprints_board_active",
            ) { it.getString(1) }

        assertThat(indexDef).isNotNull()
        assertThat(indexDef).contains("board_id")
        // 전체 인덱스면 COMPLETED 가 쌓일수록 커진다 — 활성만 인덱싱한다는 것이 설계의 요점이다.
        assertThat(indexDef).contains("deleted_at IS NULL")
        assertThat(indexDef).contains("'ACTIVE'")
        // ★ UNIQUE 가 아니다. 기존 다중 ACTIVE 행(PR #182 Deviation ⑤)을 깨지 않는다.
        assertThat(indexDef).doesNotContain("UNIQUE")
    }

    // ── ⑥ board_type CHECK ────────────────────────────────────────────────────

    @Test
    fun `boards_board_type_allowed CHECK 가 존재한다`() {
        val checkClause =
            queryOne(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = ?",
                "boards_board_type_allowed",
            ) { it.getString(1) }

        assertThat(checkClause).isNotNull()
        assertThat(checkClause).contains("SCRUM")
        assertThat(checkClause).contains("KANBAN")
    }
}
