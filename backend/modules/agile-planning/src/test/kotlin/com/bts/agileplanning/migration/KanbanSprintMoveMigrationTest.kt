// V507 이관 검증 — 칸반 보드에 붙은 스프린트를 프로젝트 스크럼 보드로 옮기고 보드 신설·컬럼 복제·멱등을 확인 (부채 165)

package com.bts.agileplanning.migration

import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.Timestamp
import java.sql.Types
import java.time.Instant
import java.util.UUID

/**
 * `V507__move_kanban_sprints_to_scrum_board.sql` 의 **이관 결과**를 검증한다 (R1~R7).
 *
 * `V506` 은 「그때 존재하던」 스프린트를 전부 스크럼 보드에 붙였다. 그 뒤에 칸반 보드를 지정해 만들어진
 * 스프린트는 백로그·보드 어느 화면에도 안 나온다(부채 165). `V507` 이 그 잔여를 정리한다.
 *
 * ## 왜 전용 컨테이너인가
 * [com.bts.agileplanning.AgilePlanningTestcontainersConfig] 의 공유 컨테이너는 `migrateOnce` 가 무조건
 * **최신까지** 밀어버려 「특정 버전 시점에 데이터 심기」가 불가능하다. 여기서는 `@Container` 로 전용
 * 컨테이너를 띄우고 **5단**으로 나눈다.
 *
 * 1. `target("504")` — `board_type`·`board_id` 가 아직 없는 시점
 * 2. **V506 시대 픽스처** 시드 (`BKFL`·`NOBD`·`ALLDEL`) — E10 회귀 단언의 재료
 * 3. `target("506")` — `V505`·`V506` 적용. 이때 위 픽스처가 백필된다
 * 4. **V507 시대 픽스처** 시드 (`KMOVE`·`KHAVE`·`KDEL`·`KDEAD`·`KTIE`) — 칸반 보드에 붙은 스프린트
 * 5. target 없이 `migrate()` — `V507` 적용
 *
 * ★ 5단계에 `target("507")` 을 쓰지 않는다. 버전을 숫자로 고정하면 나중에 `V508` 이 들어올 때 이 클래스가
 * 통째로 죽는다([SprintBoardIdBackfillMigrationTest] `:30-31` 과 같은 이유). 3단계의 `506` 은 반대로
 * **고정이 본질**이다 — 「V506 직후 V507 직전」이 이 클래스가 재현하려는 상태 그 자체다.
 *
 * ★ 깨끗한 컨테이너에서 잰다. 공유 개발 DB 의 선재 행은 「마이그레이션이 안 넣었는데 데이터가 있는」
 * 가짜 그린을 만든다(memory: shared-dev-db-preexisting-rows-fake-green).
 *
 * ## 실행 순서를 고정하는 이유
 * 멱등 테스트(E11)는 **DB 를 바꾼다** — 칸반 소속 스프린트를 새로 심고 `V507` SQL 을 다시 돌린다.
 * 순서가 안 정해지면 다른 테스트가 그 잔여를 먼저 보게 돼 결과가 실행마다 갈린다. [Order] 로 못박는다.
 *
 * ## ACTIVE 충돌 (R3~R6 · 편차 X9)
 * 이관은 `board_id` 만 옮기지 않는다. 목표 보드의 활성 스프린트가 둘이 되면 `findActiveByBoard` 가
 * `limit(1)` 이라 한쪽이 화면에서 사라지므로, **이관 대상**의 `status` 를 `PLANNED` 로 내린다
 * (ADR 2026-09-03 `D2`). 기존 스크럼 보드의 원래 ACTIVE 는 어떤 경우에도 안 건드린다(R6) — 그 경계를
 * 지키는지가 이 클래스의 후반부(Order 5~10)다.
 *
 * 참조. `docs/specs/2026-09-03-kanban-sprint-move-and-lock-budget.md` R1~R7 · E1~E5 · E10 · E11.
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class KanbanSprintMoveMigrationTest {
    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))
                .withDatabaseName("bts_agileplanning_kanban_move_test")
                .withUsername("bts")
                .withPassword("bts_test")

        /** `V507` SQL 원문 경로 — E11 이 Flyway 밖에서 이 파일을 직접 한 번 더 돌린다. */
        const val V507_RESOURCE = "/db/migration/agile-planning/V507__move_kanban_sprints_to_scrum_board.sql"

        // ── V506 시대 픽스처 (E10 회귀용 · 선례와 같은 모양) ────────────────────────

        /** `BKFL` 의 컬럼 복제 소스 — 가장 오래된 **활성** 칸반. */
        private val bkflSourceKanbanId: UUID = UUID.randomUUID()

        /** 더 최근 칸반 — `V506` ③ 의 `ORDER BY` 를 뒤집으면 이게 복제돼 red 가 난다. */
        private val bkflNewerKanbanId: UUID = UUID.randomUUID()

        /** 가장 오래됐지만 soft-deleted 인 칸반 — `deleted_at IS NULL` 을 지우면 이게 선택돼 red 가 난다. */
        private val bkflDeletedKanbanId: UUID = UUID.randomUUID()

        private val bkflActiveSprintId: UUID = UUID.randomUUID()

        private val bkflDeletedSprintId: UUID = UUID.randomUUID()

        /** 보드가 하나도 없는 프로젝트의 스프린트 (V506 E-1). */
        private val nobdSprintId: UUID = UUID.randomUUID()

        /** 스프린트가 전부 soft-deleted 인 프로젝트의 유일한 스프린트 (V506 ② 의 필터 실수 탐지용). */
        private val alldelSprintId: UUID = UUID.randomUUID()

        /** `BKFL` 소스 칸반의 컬럼 — 신설 스크럼 보드가 그대로 복제해야 한다. category 를 셋 다 다르게 둔다. */
        private val bkflSourceColumns =
            listOf(
                ColumnSeed("open", "TODO"),
                ColumnSeed("in-progress", "IN_PROGRESS"),
                ColumnSeed("closed", "DONE"),
            )

        // ── V507 시대 픽스처 ─────────────────────────────────────────────────────

        /** `KMOVE` 의 컬럼 복제 소스 — 가장 오래된 **활성** 칸반. */
        private val kmoveSourceKanbanId: UUID = UUID.randomUUID()

        /** `KMOVE` 의 더 최근 칸반 — `ORDER BY (created_at, id)` 를 뒤집으면 이게 복제돼 red 가 난다. */
        private val kmoveNewerKanbanId: UUID = UUID.randomUUID()

        /** `KMOVE` 의 soft-deleted 칸반 — 가장 오래됐다. `deleted_at IS NULL` 을 지우면 이게 복제돼 red 가 난다. */
        private val kmoveDeletedKanbanId: UUID = UUID.randomUUID()

        /** 소스 칸반에 붙은 계획 스프린트 — 옮겨져야 한다 (R1). */
        private val kmovePlannedSprintId: UUID = UUID.randomUUID()

        /** **다른** 칸반 보드에 붙은 완료 스프린트 — 같은 스크럼 보드로 모이고 상태는 안 바뀐다 (E2·E4). */
        private val kmoveCompletedSprintId: UUID = UUID.randomUUID()

        /** soft-deleted 스프린트 — 대상에서 제외돼 칸반 보드에 그대로 남는다 (E5). */
        private val kmoveDeletedSprintId: UUID = UUID.randomUUID()

        /**
         * `KMOVE` 소스 칸반의 컬럼.
         *
         * ★ `state_key`·`name`·`category`·`wip_limit` 를 전부 다르게 둔다. 하나라도 같은 값으로 묶으면
         * 해당 열을 리터럴로 바꾸는 뮤테이션이 초록으로 통과한다. `wip_limit` 는 NULL 도 한 칸 섞는다 —
         * 전부 non-NULL 이면 `c.wip_limit` 를 `NULL` 로 바꾸는 뮤테이션만 잡고 그 반대는 못 잡는다.
         */
        private val kmoveSourceColumns =
            listOf(
                ColumnSeed("open", "TODO", wipLimit = null),
                ColumnSeed("doing", "IN_PROGRESS", wipLimit = 3),
                ColumnSeed("done", "DONE", wipLimit = 7),
            )

        /** `KHAVE` — 스크럼 보드가 **이미 둘** 있는 프로젝트. 칸반 보드에도 스프린트가 하나 붙어 있다. */
        private val khaveKanbanId: UUID = UUID.randomUUID()

        /** `KHAVE` 의 더 오래된 스크럼 보드 — `(created_at, id)` 최선두라 이관 목적지다 (E3). */
        private val khaveOldScrumId: UUID = UUID.randomUUID()

        /** `KHAVE` 의 더 최근 스크럼 보드 — 목적지가 아니다. */
        private val khaveNewScrumId: UUID = UUID.randomUUID()

        /** 칸반에 붙어 있어 옮겨질 스프린트. */
        private val khaveMovedSprintId: UUID = UUID.randomUUID()

        /** 이미 스크럼(그것도 최선두가 아닌 쪽)에 붙어 있어 **건드리면 안 되는** 스프린트. */
        private val khaveStaySprintId: UUID = UUID.randomUUID()

        /** `KDEL` — 칸반에 붙은 스프린트가 **soft-deleted 뿐**인 프로젝트. 스크럼 보드가 생기면 안 된다 (E5). */
        private val kdelKanbanId: UUID = UUID.randomUUID()

        private val kdelDeletedSprintId: UUID = UUID.randomUUID()

        /** `KDEAD` — 스크럼 보드가 **soft-deleted** 뿐인 프로젝트. 살아있는 보드를 새로 얻어야 한다. */
        private val kdeadKanbanId: UUID = UUID.randomUUID()

        private val kdeadDeletedScrumId: UUID = UUID.randomUUID()

        private val kdeadSprintId: UUID = UUID.randomUUID()

        // ── ACTIVE 충돌 픽스처 (R3·R4·R5·R6 · 편차 X9) ───────────────────────────

        /** `KMOVE` 의 이관 대상 ACTIVE 중 `(created_at, id)` **최선두** — 목표 보드에 기존 ACTIVE 가 없어 살아남는다. */
        private val kmoveOldActiveId: UUID = UUID.randomUUID()

        /** `KMOVE` 의 더 최근 ACTIVE. **다른** 칸반에 붙어 있다 — 같은 목표 보드로 모여 PLANNED 로 내려간다 (R5·E2). */
        private val kmoveNewActiveId: UUID = UUID.randomUUID()

        /**
         * `KHAVE` 의 목표 스크럼 보드(`khaveOldScrum`)에 **원래 있던** ACTIVE. 어떤 경우에도 안 건드린다 (R6).
         *
         * ★ 이관 대상보다 **더 최근**으로 심는다. 「보드마다 (created_at, id) 최선두만 ACTIVE」를 기존 행까지
         *   싸잡아 적용하는 구현이면 이 행이 강등되고 이관 대상이 살아남아 R4·R6 이 동시에 red 가 된다.
         *   나이가 반대 방향이면 그 구현이 초록으로 통과해 판정이 사라진다.
         */
        private val khaveIncumbentId: UUID = UUID.randomUUID()

        /** 칸반에 붙은 ACTIVE — 목표 보드에 기존 ACTIVE 가 있으므로 전부 PLANNED 로 내려간다 (R4). */
        private val khaveMovedActiveId: UUID = UUID.randomUUID()

        /** `KTIE` — `created_at` 이 **정확히 동점**인 ACTIVE 둘만 있는 프로젝트. 판정을 가르는 건 `id` 뿐이다. */
        private val ktieKanbanId: UUID = UUID.randomUUID()

        /**
         * 동점 조에서 **id 가 작은** 쪽 — 살아남아야 한다.
         *
         * ★ 리터럴로 고정한다. 랜덤 UUID 두 개면 어느 쪽이 작은지가 실행마다 갈려 판정 자체가 흔들린다.
         */
        private val ktieWinnerId: UUID = UUID.fromString("11111111-1111-4111-8111-111111111111")

        /** 동점 조에서 id 가 큰 쪽 — PLANNED 로 내려간다. */
        private val ktieLoserId: UUID = UUID.fromString("22222222-2222-4222-8222-222222222222")

        private fun flyway(target: String?) =
            Flyway.configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .placeholderReplacement(false)
                .locations("classpath:db/migration/agile-planning")
                .let { if (target == null) it else it.target(target) }
                .load()

        /** 5단 부팅 — 두 시대의 픽스처를 각각 제 시점에 심어야 백필과 이관이 서로 다른 데이터를 만난다. */
        @BeforeAll
        @JvmStatic
        fun migrateWithTwoSeedsInBetween() {
            flyway("504").migrate()
            conn().use { c ->
                c.autoCommit = false
                seedV506Era(c)
                c.commit()
            }
            flyway("506").migrate()
            conn().use { c ->
                c.autoCommit = false
                seedV507Era(c)
                c.commit()
            }
            flyway(null).migrate()
        }

        /** `V506` 백필이 마주칠 「기존 데이터」. 이 시점 boards 에는 `board_type` 이, sprints 에는 `board_id` 가 없다. */
        private fun seedV506Era(c: Connection) {
            // created_at 을 명시로 벌린다 — 기본값 now() 로 심으면 같은 트랜잭션이라 시각이 같아 정렬이 무의미해진다.
            val gone = listOf(ColumnSeed("gone", "DONE"))
            val newer = listOf(ColumnSeed("newer", "IN_PROGRESS"))
            seedBoard(c, BoardSeed(bkflDeletedKanbanId, "BKFL", "BKFL 삭제된 보드", 40, deleted = true, columns = gone))
            seedBoard(c, BoardSeed(bkflSourceKanbanId, "BKFL", "BKFL 개발 보드", 30, columns = bkflSourceColumns))
            seedBoard(c, BoardSeed(bkflNewerKanbanId, "BKFL", "BKFL 새 보드", 10, columns = newer))
            seedSprint(c, SprintSeed(bkflActiveSprintId, "BKFL", "ACTIVE"))
            seedSprint(c, SprintSeed(bkflDeletedSprintId, "BKFL", "COMPLETED", deleted = true))
            seedSprint(c, SprintSeed(nobdSprintId, "NOBD", "PLANNED"))
            seedSprint(c, SprintSeed(alldelSprintId, "ALLDEL", "COMPLETED", deleted = true))
        }

        /** `V506` 이 끝난 뒤 「칸반 보드를 지정해 만들어진」 스프린트. `V507` 이 정리해야 할 잔여다. */
        private fun seedV507Era(c: Connection) {
            seedKmove(c)
            seedKhave(c)
            seedKtie(c)

            // KDEL — 칸반 소속 스프린트가 soft-deleted 뿐이라 옮길 것이 없다.
            seedBoard(c, kanban(kdelKanbanId, "KDEL", "KDEL 칸반", 15, columns = listOf(ColumnSeed("d", "TODO"))))
            seedSprint(c, SprintSeed(kdelDeletedSprintId, "KDEL", "PLANNED", deleted = true, boardId = kdelKanbanId))

            // KDEAD — 유일한 스크럼 보드가 soft-deleted 라 「있는 것」으로 치면 안 된다.
            val deadCols = listOf(ColumnSeed("y", "TODO"))
            seedBoard(c, kanban(kdeadKanbanId, "KDEAD", "KDEAD 칸반", 18, columns = listOf(ColumnSeed("x", "TODO", 2))))
            seedBoard(c, scrum(kdeadDeletedScrumId, "KDEAD", "KDEAD 죽은 스크럼", 12, deadCols).copy(deleted = true))
            seedSprint(c, SprintSeed(kdeadSprintId, "KDEAD", "PLANNED", boardId = kdeadKanbanId))
        }

        /** KMOVE — 스크럼 보드가 없고 칸반 보드 셋에 스프린트가 흩어져 있다. 보드 신설·컬럼 복제의 주 무대다. */
        private fun seedKmove(c: Connection) {
            val gone = listOf(ColumnSeed("gone", "DONE", 1))
            val newer = listOf(ColumnSeed("newer", "IN_PROGRESS", 9))
            val src = kmoveSourceKanbanId
            seedBoard(c, kanban(kmoveDeletedKanbanId, "KMOVE", "KMOVE 삭제된 보드", 40, gone).copy(deleted = true))
            seedBoard(c, kanban(src, "KMOVE", "KMOVE 개발 보드", 30, columns = kmoveSourceColumns))
            seedBoard(c, kanban(kmoveNewerKanbanId, "KMOVE", "KMOVE 새 보드", 10, columns = newer))
            seedSprint(c, SprintSeed(kmovePlannedSprintId, "KMOVE", "PLANNED", boardId = src))
            seedSprint(c, SprintSeed(kmoveCompletedSprintId, "KMOVE", "COMPLETED", boardId = kmoveNewerKanbanId))
            seedSprint(c, SprintSeed(kmoveDeletedSprintId, "KMOVE", "PLANNED", deleted = true, boardId = src))
            // 목표 스크럼 보드는 V507 이 신설한다 → 기존 ACTIVE 가 없다. 둘 중 더 오래된 쪽만 ACTIVE 로 남는다 (R5).
            val other = kmoveNewerKanbanId
            seedSprint(c, SprintSeed(kmoveOldActiveId, "KMOVE", "ACTIVE", boardId = src, daysAgo = 9))
            seedSprint(c, SprintSeed(kmoveNewActiveId, "KMOVE", "ACTIVE", boardId = other, daysAgo = 4))
        }

        /** KHAVE — 스크럼 보드가 이미 둘이다. 신설 금지(R7)와 목적지 선정(E3)을 동시에 잰다. */
        private fun seedKhave(c: Connection) {
            val oldScrumColumns = listOf(ColumnSeed("s1", "TODO"))
            val newScrumColumns = listOf(ColumnSeed("s2", "TODO"))
            seedBoard(c, kanban(khaveKanbanId, "KHAVE", "KHAVE 칸반", 25, columns = listOf(ColumnSeed("k", "TODO"))))
            seedBoard(c, scrum(khaveOldScrumId, "KHAVE", "KHAVE 스크럼 보드", 20, columns = oldScrumColumns))
            seedBoard(c, scrum(khaveNewScrumId, "KHAVE", "KHAVE 두 번째 스크럼", 5, columns = newScrumColumns))
            seedSprint(c, SprintSeed(khaveMovedSprintId, "KHAVE", "PLANNED", boardId = khaveKanbanId))
            seedSprint(c, SprintSeed(khaveStaySprintId, "KHAVE", "PLANNED", boardId = khaveNewScrumId))
            // 목표 보드에 원래 있던 ACTIVE (R6). 이관 대상보다 **나중**이라야 R4·R6 을 동시에 잰다.
            seedSprint(c, SprintSeed(khaveIncumbentId, "KHAVE", "ACTIVE", boardId = khaveOldScrumId, daysAgo = 2))
            seedSprint(c, SprintSeed(khaveMovedActiveId, "KHAVE", "ACTIVE", boardId = khaveKanbanId, daysAgo = 8))
        }

        /**
         * KTIE — `created_at` 이 동점인 ACTIVE 둘. 같은 트랜잭션의 `now()` 는 고정값이라 `daysAgo` 가 같으면
         * 두 행의 시각이 **정확히** 같아진다. 그래서 `(created_at, id)` 의 두 번째 열만 판정을 가른다.
         *
         * ★ 심는 **순서**가 판정의 재료다. id 가 큰 loser 를 먼저 심어 물리 순서에서 앞세운다 — `ORDER BY` 에서
         *   `id` 를 빼면 동점 구간의 정렬이 물리 순서로 남아 loser 가 살아남고 tie-break 테스트가 red 가 된다.
         *   순서를 뒤집으면 `created_at` 만으로도 우연히 초록이라 판정이 사라진다.
         */
        private fun seedKtie(c: Connection) {
            seedBoard(c, kanban(ktieKanbanId, "KTIE", "KTIE 칸반", 22, listOf(ColumnSeed("t", "TODO"))))
            seedSprint(c, SprintSeed(ktieLoserId, "KTIE", "ACTIVE", boardId = ktieKanbanId, daysAgo = 7))
            seedSprint(c, SprintSeed(ktieWinnerId, "KTIE", "ACTIVE", boardId = ktieKanbanId, daysAgo = 7))
        }

        /** V507 시대 칸반 보드 시드 — `board_type` 을 매 줄에 적지 않게 줄인다. 삭제 상태는 `copy` 로 얹는다. */
        private fun kanban(
            id: UUID,
            projectKey: String,
            name: String,
            daysAgo: Int,
            columns: List<ColumnSeed> = emptyList(),
        ) = BoardSeed(id, projectKey, name, daysAgo, boardType = "KANBAN", columns = columns)

        /** V507 시대 스크럼 보드 시드 — [kanban] 과 짝을 이룬다. */
        private fun scrum(
            id: UUID,
            projectKey: String,
            name: String,
            daysAgo: Int,
            columns: List<ColumnSeed> = emptyList(),
        ) = BoardSeed(id, projectKey, name, daysAgo, boardType = "SCRUM", columns = columns)

        /** 전용 컨테이너에 새 커넥션을 연다. 호출자가 `use` 로 닫는다. */
        fun conn(): Connection = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)

        /** 보드 컬럼 하나의 시드 명세. `name` 은 `state_key` 에서 파생해 복제 단언이 열마다 다른 값을 보게 한다. */
        data class ColumnSeed(
            val stateKey: String,
            val category: String,
            val wipLimit: Int? = null,
            val name: String = "컬럼 $stateKey",
        )

        /** 보드 하나의 시드 명세. `daysAgo` 가 클수록 오래된 보드다. `boardType` 이 null 이면 V504 시점(컬럼 부재)이다. */
        data class BoardSeed(
            val id: UUID,
            val projectKey: String,
            val name: String,
            val daysAgo: Int,
            val deleted: Boolean = false,
            val boardType: String? = null,
            val columns: List<ColumnSeed> = emptyList(),
        )

        /**
         * 스프린트 하나의 시드 명세. `boardId` 가 null 이면 V504 시점(`board_id` 컬럼 부재)이다.
         *
         * `daysAgo` 가 null 이면 `created_at` 을 DB 기본값(`now()`)에 맡긴다. 값을 주면 나이를 벌려 심는데,
         * `now()` 는 **트랜잭션 고정값**이라 같은 `daysAgo` 두 행은 시각이 정확히 같아진다 — 동점 픽스처의 재료다.
         */
        data class SprintSeed(
            val id: UUID,
            val projectKey: String,
            val status: String,
            val deleted: Boolean = false,
            val boardId: UUID? = null,
            val daysAgo: Int? = null,
        )

        /** 보드 1개와 그 컬럼을 심는다. `board_type` 은 V506 이후 시드에서만 명시한다. */
        private fun seedBoard(
            c: Connection,
            seed: BoardSeed,
        ) {
            val typeCol = seed.boardType?.let { ", board_type" } ?: ""
            val typeVal = seed.boardType?.let { ", '$it'" } ?: ""
            c.prepareStatement(
                "INSERT INTO boards (id, project_key, name, created_at, deleted_at$typeCol)" +
                    " VALUES (?, ?, ?, now() - make_interval(days => ?), ?$typeVal)",
            ).use { stmt ->
                stmt.setObject(1, seed.id)
                stmt.setString(2, seed.projectKey)
                stmt.setString(3, seed.name)
                stmt.setInt(4, seed.daysAgo)
                stmt.setObject(5, if (seed.deleted) Timestamp.from(Instant.now()) else null)
                stmt.execute()
            }
            seed.columns.forEachIndexed { order, col -> seedColumn(c, seed.id, order, col) }
        }

        /** 보드 컬럼 1개를 심는다. `wip_limit` 은 NULL 도 실어야 해서 `setNull` 로 타입을 명시한다. */
        private fun seedColumn(
            c: Connection,
            boardId: UUID,
            order: Int,
            col: ColumnSeed,
        ) {
            c.prepareStatement(
                "INSERT INTO board_columns (id, board_id, state_key, name, category, display_order, wip_limit)" +
                    " VALUES (?, ?, ?, ?, ?, ?, ?)",
            ).use { stmt ->
                stmt.setObject(1, UUID.randomUUID())
                stmt.setObject(2, boardId)
                stmt.setString(3, col.stateKey)
                stmt.setString(4, col.name)
                stmt.setString(5, col.category)
                stmt.setInt(6, order)
                if (col.wipLimit == null) stmt.setNull(7, Types.INTEGER) else stmt.setInt(7, col.wipLimit)
                stmt.execute()
            }
        }

        /** 스프린트 1개를 심는다. 이름은 어느 단언도 보지 않으므로 id 에서 파생한다. */
        fun seedSprint(
            c: Connection,
            seed: SprintSeed,
        ) {
            val boardCol = seed.boardId?.let { ", board_id" } ?: ""
            val boardVal = seed.boardId?.let { ", ?" } ?: ""
            // 나이는 Int 라 문자열로 엮어도 주입 경로가 없다. `?` 로 빼면 boardId 와 파라미터 인덱스가 얽힌다.
            val agedCol = seed.daysAgo?.let { ", created_at" } ?: ""
            val agedVal = seed.daysAgo?.let { ", now() - make_interval(days => $it)" } ?: ""
            c.prepareStatement(
                "INSERT INTO sprints (id, project_key, name, status, deleted_at$boardCol$agedCol)" +
                    " VALUES (?, ?, ?, ?, ${if (seed.deleted) "now()" else "NULL"}$boardVal$agedVal)",
            ).use { stmt ->
                stmt.setObject(1, seed.id)
                stmt.setString(2, seed.projectKey)
                stmt.setString(3, "Sprint ${seed.id.toString().take(4)}")
                stmt.setString(4, seed.status)
                seed.boardId?.let { stmt.setObject(5, it) }
                stmt.execute()
            }
        }
    }

    // ── 조회 헬퍼 ──────────────────────────────────────────────────────────────

    // 커넥션을 닫으면 statement 와 result set 도 함께 닫힌다 — 중첩 use 를 쌓지 않는다.
    private fun <T> queryOne(
        sql: String,
        vararg params: Any?,
        read: (ResultSet) -> T,
    ): T? {
        conn().use { c ->
            val rs = prepared(c, sql, params).executeQuery()
            return if (rs.next()) read(rs) else null
        }
    }

    // generateSequence 를 쓰지 않는다 — read 가 null 을 돌려주는 열(wip_limit)에서 순회가 조기 종료된다.
    private fun <T> queryList(
        sql: String,
        vararg params: Any?,
        read: (ResultSet) -> T,
    ): List<T> {
        conn().use { c ->
            val rs = prepared(c, sql, params).executeQuery()
            val rows = mutableListOf<T>()
            while (rs.next()) rows += read(rs)
            return rows
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

    // BoardRepository.findScrumBoardIdByProject 와 같은 규칙 — 활성 스크럼 중 (created_at, id) 최선두 (E3).
    private fun liveScrumBoardIdOf(projectKey: String): UUID? =
        queryOne(
            "SELECT id FROM boards WHERE project_key = ? AND board_type = 'SCRUM' AND deleted_at IS NULL" +
                " ORDER BY created_at, id",
            projectKey,
        ) { it.getObject(1) as UUID }

    private fun liveScrumBoardCountOf(projectKey: String): Int =
        queryOne(
            "SELECT COUNT(*) FROM boards WHERE project_key = ? AND board_type = 'SCRUM' AND deleted_at IS NULL",
            projectKey,
        ) { it.getInt(1) } ?: 0

    private fun boardCount(): Int = queryOne("SELECT COUNT(*) FROM boards") { it.getInt(1) } ?: 0

    private fun columnValues(
        boardId: UUID?,
        column: String,
    ): List<Any?> =
        queryList(
            "SELECT $column FROM board_columns WHERE board_id = ? ORDER BY display_order",
            boardId,
        ) { it.getObject(1) }

    private fun statusOf(sprintId: UUID): String? =
        queryOne("SELECT status FROM sprints WHERE id = ?", sprintId) { it.getString(1) }

    // 강등된 행만 version 이 오른다 — 「무엇을 건드렸나」를 status 보다 정확히 가리키는 지표다.
    private fun versionOf(sprintId: UUID): Long? =
        queryOne("SELECT version FROM sprints WHERE id = ?", sprintId) { it.getLong(1) }

    private fun timestampOf(
        sprintId: UUID,
        column: String,
    ): Timestamp? = queryOne("SELECT $column FROM sprints WHERE id = ?", sprintId) { it.getTimestamp(1) }

    // 보드당 활성 스프린트 수 — R3 사후 불변식과 R6 의 「기존 것이 살아남았나」를 함께 잰다.
    private fun activeCountOf(boardId: UUID?): Int =
        queryOne(
            "SELECT COUNT(*) FROM sprints WHERE board_id = ? AND status = 'ACTIVE' AND deleted_at IS NULL",
            boardId,
        ) { it.getInt(1) } ?: 0

    /** `V507` SQL 원문. Flyway 밖에서 직접 재실행하는 두 테스트(E11 멱등 · tie-break 반복 실행)가 함께 쓴다. */
    private fun v507Sql(): String {
        val stream = checkNotNull(javaClass.getResourceAsStream(V507_RESOURCE)) { "클래스패스에 $V507_RESOURCE 가 없다" }
        return stream.bufferedReader().use { it.readText() }
    }

    // ── ① R1 · 칸반 소속 스프린트 이관 ─────────────────────────────────────────

    @Test
    @Order(1)
    fun `칸반 보드에 붙은 스프린트가 프로젝트의 스크럼 보드로 옮겨진다`() {
        val kmoveScrumId = liveScrumBoardIdOf("KMOVE")
        assertThat(kmoveScrumId).isNotNull()

        // E2 — 여러 칸반 보드에 흩어져 있어도 전부 같은 스크럼 보드로 모인다.
        assertThat(boardIdOf(kmovePlannedSprintId)).isEqualTo(kmoveScrumId)
        assertThat(boardIdOf(kmoveCompletedSprintId)).isEqualTo(kmoveScrumId)

        // E4 — 이관은 board_id 만 옮긴다. 상태는 건드리지 않는다.
        val status = queryOne("SELECT status FROM sprints WHERE id = ?", kmoveCompletedSprintId) { it.getString(1) }
        assertThat(status).isEqualTo("COMPLETED")

        // E5 — soft-deleted 스프린트는 대상이 아니다. 칸반 보드에 그대로 남는다.
        assertThat(boardIdOf(kmoveDeletedSprintId)).isEqualTo(kmoveSourceKanbanId)

        // E3 — 스크럼 보드가 둘이면 (created_at, id) 최선두로 간다. ORDER BY 를 지우면 여기가 흔들린다.
        assertThat(boardIdOf(khaveMovedSprintId)).isEqualTo(khaveOldScrumId)

        // 이미 스크럼에 붙은 스프린트는 최선두가 아니어도 건드리지 않는다.
        // 대상 조건에서 board_type = 'KANBAN' 을 지우면 이 스프린트가 khaveOldScrum 으로 끌려가 red 가 난다.
        assertThat(boardIdOf(khaveStaySprintId)).isEqualTo(khaveNewScrumId)
    }

    // ── ② R2 · 스크럼 보드 신설 + 컬럼 복제 ────────────────────────────────────

    @Test
    @Order(2)
    fun `스크럼 보드가 없던 프로젝트에 신설되고 컬럼이 wip_limit 까지 복제된다`() {
        val kmoveScrumId = liveScrumBoardIdOf("KMOVE")
        // 이름 규칙은 BoardApplicationService.ensureScrumBoard·V506 ② 와 문자열이 같아야 한다.
        assertThat(queryOne("SELECT name FROM boards WHERE id = ?", kmoveScrumId) { it.getString(1) })
            .isEqualTo("KMOVE 스크럼 보드")

        // 복제 소스는 **가장 오래된 활성** 칸반이다 — 더 최근("newer")도, soft-deleted("gone")도 아니다.
        assertThat(columnValues(kmoveScrumId, "state_key")).containsExactly("open", "doing", "done")
        assertThat(columnValues(kmoveScrumId, "name")).containsExactly("컬럼 open", "컬럼 doing", "컬럼 done")
        assertThat(columnValues(kmoveScrumId, "category")).containsExactly("TODO", "IN_PROGRESS", "DONE")
        // wip_limit 을 안 보면 V507 의 `c.wip_limit` 를 NULL 리터럴로 바꾸는 뮤테이션이 초록으로 통과한다.
        assertThat(columnValues(kmoveScrumId, "wip_limit")).containsExactly(null, 3, 7)

        // NOT EXISTS 가드 — 스크럼 보드가 이미 있으면 더 만들지 않는다. 가드를 지우면 3개가 돼 red 다.
        assertThat(liveScrumBoardCountOf("KHAVE")).isEqualTo(2)

        // E5 — 칸반 소속 스프린트가 soft-deleted 뿐이면 옮길 것이 없으므로 보드도 안 생긴다.
        assertThat(liveScrumBoardCountOf("KDEL")).isZero()
        assertThat(boardIdOf(kdelDeletedSprintId)).isEqualTo(kdelKanbanId)

        // soft-deleted 스크럼 보드는 「있는 것」으로 치지 않는다 — 살아있는 보드를 새로 얻어야 한다.
        val kdeadScrumId = liveScrumBoardIdOf("KDEAD")
        assertThat(kdeadScrumId).isNotNull().isNotEqualTo(kdeadDeletedScrumId)
        assertThat(boardIdOf(kdeadSprintId)).isEqualTo(kdeadScrumId)
        assertThat(columnValues(kdeadScrumId, "state_key")).containsExactly("x")
    }

    // ── ③ R7 · 멱등 (E11 — Flyway 밖에서 직접 재실행) ──────────────────────────

    @Test
    @Order(3)
    fun `재적용해도 스크럼 보드가 중복 생성되지 않는다`() {
        // Flyway 는 같은 버전을 두 번 적용하지 않으므로 멱등을 Flyway 로는 못 잰다. SQL 원문을 직접 돌린다.
        val sql = v507Sql()

        // ★ 그냥 재실행만 하면 대상이 0건이라 「가드가 없어도 초록」인 공허한 테스트가 된다.
        //   칸반 소속 스프린트를 새로 심어 보드 신설 INSERT 의 대상 프로젝트에 KMOVE 가 다시 오르게 만든다.
        val replaySprintId = UUID.randomUUID()
        conn().use { c ->
            c.autoCommit = false
            seedSprint(c, SprintSeed(replaySprintId, "KMOVE", "PLANNED", boardId = kmoveSourceKanbanId))
            c.commit()
        }

        val before = boardCount()
        val kmoveScrumId = liveScrumBoardIdOf("KMOVE")
        val columnsBefore = columnValues(kmoveScrumId, "state_key")

        conn().use { c -> c.createStatement().use { it.execute(sql) } }

        // 보드가 늘지 않는다 — NOT EXISTS 가드가 없으면 KMOVE 에 두 번째 스크럼 보드가 생겨 red 다.
        assertThat(boardCount()).isEqualTo(before)
        assertThat(liveScrumBoardCountOf("KMOVE")).isEqualTo(1)
        // 컬럼도 늘지 않는다 — 복제를 신설 보드로 한정하지 않으면 기존 스크럼 보드에 컬럼이 겹쳐 쌓인다.
        assertThat(columnValues(kmoveScrumId, "state_key")).isEqualTo(columnsBefore)
        // 새로 심은 잔여 스프린트는 재실행이 정리한다.
        assertThat(boardIdOf(replaySprintId)).isEqualTo(kmoveScrumId)
    }

    // ── ④ E10 · V506 백필 6건 회귀 ────────────────────────────────────────────

    @Test
    @Order(4)
    fun `V507 적용 후에도 V506 백필 6건의 단언이 전부 성립한다`() {
        // 1. 스프린트 전량이 board_id 를 갖고 NOT NULL 이다.
        assertThat(queryOne("SELECT COUNT(*) FROM sprints WHERE board_id IS NULL") { it.getInt(1) }).isZero()
        assertThat(boardIdOf(bkflDeletedSprintId)).isNotNull()
        assertThat(boardIdOf(alldelSprintId)).isEqualTo(liveScrumBoardIdOf("ALLDEL"))
        assertThat(
            queryOne(
                "SELECT is_nullable FROM information_schema.columns WHERE table_schema = 'public'" +
                    " AND table_name = 'sprints' AND column_name = 'board_id'",
            ) { it.getString(1) },
        ).isEqualTo("NO")

        // 2. 기존 칸반 보드는 이름·종류·컬럼이 무변경이다.
        assertThat(queryOne("SELECT name FROM boards WHERE id = ?", bkflSourceKanbanId) { it.getString(1) })
            .isEqualTo("BKFL 개발 보드")
        assertThat(queryOne("SELECT board_type FROM boards WHERE id = ?", bkflSourceKanbanId) { it.getString(1) })
            .isEqualTo("KANBAN")
        assertThat(columnValues(bkflSourceKanbanId, "state_key")).containsExactly("open", "in-progress", "closed")

        // 3. V506 신설 스크럼 보드가 그대로다 — V507 이 네 번째 보드를 만들지 않았다.
        val bkflScrumId = liveScrumBoardIdOf("BKFL")
        assertThat(queryOne("SELECT name FROM boards WHERE id = ?", bkflScrumId) { it.getString(1) })
            .isEqualTo("BKFL 스크럼 보드")
        assertThat(columnValues(bkflScrumId, "state_key")).containsExactly("open", "in-progress", "closed")
        assertThat(columnValues(bkflScrumId, "category"))
            .containsExactlyElementsOf(bkflSourceColumns.map { it.category })
        assertThat(boardIdOf(bkflActiveSprintId)).isEqualTo(bkflScrumId)
        assertThat(boardIdOf(bkflDeletedSprintId)).isEqualTo(bkflScrumId)
        assertThat(liveScrumBoardCountOf("BKFL")).isEqualTo(1)
        assertThat(liveScrumBoardCountOf("NOBD")).isEqualTo(1)
        assertThat(liveScrumBoardCountOf("ALLDEL")).isEqualTo(1)

        // 4. E-1 — 보드가 없던 프로젝트는 스크럼 보드를 얻되 컬럼은 0개다.
        val nobdScrumId = liveScrumBoardIdOf("NOBD")
        assertThat(boardIdOf(nobdSprintId)).isEqualTo(nobdScrumId)
        assertThat(columnValues(nobdScrumId, "state_key")).isEmpty()

        // 5. 부분 인덱스가 술어까지 그대로다 — V507 은 DDL 을 건드리지 않는다.
        val indexDef =
            queryOne(
                "SELECT indexdef FROM pg_indexes WHERE schemaname = 'public' AND indexname = ?",
                "idx_sprints_board_active",
            ) { it.getString(1) }
        assertThat(indexDef).isNotNull().contains("board_id").contains("deleted_at IS NULL").contains("'ACTIVE'")
        // ★ UNIQUE 로 승격하지 않는다 — V506:66-68 의 사유(PR #182 Deviation ⑤)가 여전히 유효하다.
        assertThat(indexDef).doesNotContain("UNIQUE")

        // 6. board_type CHECK 가 그대로다.
        val checkClause =
            queryOne(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = ?",
                "boards_board_type_allowed",
            ) { it.getString(1) }
        assertThat(checkClause).isNotNull().contains("SCRUM").contains("KANBAN")
    }

    // ── ⑤ R4 · 목표 보드에 기존 ACTIVE 가 있으면 이관 대상은 전부 내려간다 ───────

    @Test
    @Order(5)
    fun `목표 보드에 기존 ACTIVE 가 있으면 이관 대상 ACTIVE 는 PLANNED 가 된다`() {
        // KHAVE 의 목적지(khaveOldScrum)에는 원래 ACTIVE 가 하나 있다 → 옮겨 오는 ACTIVE 는 전부 내려간다.
        assertThat(statusOf(khaveMovedActiveId)).isEqualTo("PLANNED")
        assertThat(boardIdOf(khaveMovedActiveId)).isEqualTo(khaveOldScrumId)

        // 내린 행만 version·updated_at 이 오른다. 이관만 된 행(board_id 만 바뀜)과 여기서 갈린다.
        assertThat(versionOf(khaveMovedActiveId)).isEqualTo(1L)
        assertThat(versionOf(khaveMovedSprintId)).isZero()
        assertThat(timestampOf(khaveMovedActiveId, "updated_at"))
            .isAfter(timestampOf(khaveMovedSprintId, "updated_at"))
    }

    // ── ⑥ R5 · 기존 ACTIVE 가 없으면 (created_at, id) 최선두 1건만 남는다 ────────

    @Test
    @Order(6)
    fun `목표 보드에 ACTIVE 가 없으면 이관 대상 중 created_at id 최앞 1건만 ACTIVE 다`() {
        // KMOVE 의 목적지는 V507 이 방금 만든 보드라 기존 ACTIVE 가 없다 → 최선두 1건이 ACTIVE 를 유지한다.
        val kmoveScrumId = liveScrumBoardIdOf("KMOVE")
        assertThat(statusOf(kmoveOldActiveId)).isEqualTo("ACTIVE")
        assertThat(statusOf(kmoveNewActiveId)).isEqualTo("PLANNED")

        // 둘 다 옮겨는 진다 — 상태 조정이 이관을 대신하지 않는다.
        assertThat(boardIdOf(kmoveOldActiveId)).isEqualTo(kmoveScrumId)
        assertThat(boardIdOf(kmoveNewActiveId)).isEqualTo(kmoveScrumId)

        // 안 내린 행은 version 도 그대로다(Task 1 의 이관과 같은 취급).
        assertThat(versionOf(kmoveOldActiveId)).isZero()
        assertThat(versionOf(kmoveNewActiveId)).isEqualTo(1L)
        assertThat(activeCountOf(kmoveScrumId)).isEqualTo(1)
    }

    // ── ⑦ R6 · 기존 스크럼 보드의 원래 ACTIVE 는 성역이다 ───────────────────────

    @Test
    @Order(7)
    fun `기존 스크럼 보드의 원래 ACTIVE 스프린트는 상태도 소속도 그대로다`() {
        assertThat(statusOf(khaveIncumbentId)).isEqualTo("ACTIVE")
        assertThat(boardIdOf(khaveIncumbentId)).isEqualTo(khaveOldScrumId)
        assertThat(versionOf(khaveIncumbentId)).isZero()

        // 이 행은 이관 대상보다 **나중에** 만들어졌다. 「보드마다 최선두만 ACTIVE」를 기존 행까지 적용하는
        // 구현이면 여기가 뒤집혀 red 다 — 강등 대상이 「칸반에서 옮겨 오는 행」으로 한정됐다는 증거다.
        assertThat(activeCountOf(khaveOldScrumId)).isEqualTo(1)

        // 스크럼에 원래 있던 PLANNED 도 마찬가지 — WHERE 가 새면 version 이 오른다.
        assertThat(versionOf(khaveStaySprintId)).isZero()
    }

    // ── ⑧ E4 · COMPLETED 는 상태를 안 건드린다 ─────────────────────────────────

    @Test
    @Order(8)
    fun `COMPLETED 스프린트는 board_id 만 옮기고 상태는 그대로다`() {
        assertThat(boardIdOf(kmoveCompletedSprintId)).isEqualTo(liveScrumBoardIdOf("KMOVE"))
        // 강등 UPDATE 에서 `status = 'ACTIVE'` 필터를 지우면 COMPLETED 가 PLANNED 로 되살아나 red 다.
        assertThat(statusOf(kmoveCompletedSprintId)).isEqualTo("COMPLETED")
        assertThat(versionOf(kmoveCompletedSprintId)).isZero()
    }

    // ── ⑨ R3 · 사후 불변식 (완료 기준 2) ───────────────────────────────────────

    @Test
    @Order(9)
    fun `이관 후 어느 board_id 에도 ACTIVE 가 2건 이상 없다`() {
        // 완료 기준 2 의 SQL 을 그대로 단언한다. 특정 픽스처가 아니라 **DB 전체**를 훑는다.
        val offenders =
            queryList(
                "SELECT board_id FROM sprints WHERE status = 'ACTIVE' AND deleted_at IS NULL" +
                    " GROUP BY board_id HAVING COUNT(*) > 1",
            ) { it.getObject(1) as UUID }
        assertThat(offenders).isEmpty()
    }

    // ── ⑩ R5 tie-break · created_at 동점은 id 로 가른다 ────────────────────────

    @Test
    @Order(10)
    fun `created_at 동점이면 id 로 갈라 같은 스프린트가 반복 실행에서도 남는다`() {
        // 동점이 진짜인지부터 잰다. 시각이 갈리면 이 테스트는 (created_at, id) 가 아니라 created_at 을 재게 된다.
        assertThat(timestampOf(ktieWinnerId, "created_at")).isEqualTo(timestampOf(ktieLoserId, "created_at"))

        // id 가 작은 쪽이 남는다. ORDER BY 에서 id 를 빼면 물리 순서상 먼저인 loser 가 남아 red 다.
        assertThat(statusOf(ktieWinnerId)).isEqualTo("ACTIVE")
        assertThat(statusOf(ktieLoserId)).isEqualTo("PLANNED")

        // 반복 실행 — Flyway 밖에서 SQL 을 한 번 더 돌려도 살아남는 쪽이 갈리지 않는다.
        conn().use { c -> c.createStatement().use { it.execute(v507Sql()) } }

        assertThat(statusOf(ktieWinnerId)).isEqualTo("ACTIVE")
        assertThat(statusOf(ktieLoserId)).isEqualTo("PLANNED")
        // 재실행이 이미 내려간 행을 또 내리지 않는다 — 강등 대상이 칸반 소속으로 한정됐다는 증거다.
        assertThat(versionOf(ktieWinnerId)).isZero()
        assertThat(versionOf(ktieLoserId)).isEqualTo(1L)
    }
}
