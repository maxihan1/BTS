// V508 검증 — board_column_states 신설·백필·복합 FK·NOT NULL 완화·멱등을 확인 (컬럼:상태 1:N)

package com.bts.agileplanning.migration

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
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
import java.sql.SQLException
import java.util.UUID

/**
 * `V508__board_column_states.sql` 을 검증한다 (R1·R2·N1·E6 + gap G1 + ceo-4 복합 FK).
 *
 * `board_columns` 각 행이 워크플로우 상태 **1개**에 1:1 매핑돼 있었다(`V500:39` 의
 * `UNIQUE (board_id, state_key)`). `V508` 이 그 매핑을 연결 테이블로 분리해 컬럼 하나가 상태
 * **0개 이상**을 담게 한다.
 *
 * ## 왜 전용 컨테이너인가
 * [com.bts.agileplanning.AgilePlanningTestcontainersConfig] 의 공유 컨테이너는 무조건 최신까지
 * 밀어버려 「특정 버전 시점에 데이터 심기」가 불가능하다. `V507` 선례([KanbanSprintMoveMigrationTest])
 * 와 같은 이유로 `@Container` 로 전용 컨테이너를 띄우고 **3단**으로 나눈다 —
 * `target("507")` 로 이전 시대를 만들고, 픽스처를 심고, 마지막에 `V508` 을 태운다.
 *
 * ★ `target("508")` 을 쓰지 않는다. 버전을 숫자로 고정하면 `V509` 가 들어올 때 이 클래스가
 * 조용히 그것까지 돌게 된다 — `V507` 선례가 같은 판단을 적었다.
 *
 * ## 이 클래스가 지는 판정 5축
 *
 * | 축 | 무엇 | 근거 |
 * |---|---|---|
 * | ① 백필 무손실 | 행 수·값 집합이 `board_columns` 와 정확히 같다 | R2·N1 |
 * | ② X1 유지 | 한 상태가 한 보드에서 두 컬럼에 못 간다 | X1·D3 |
 * | ③ **복합 FK** | 컬럼의 실제 소유 보드와 다른 `board_id` 를 DB 가 거부한다 | **ceo-4** |
 * | ④ NOT NULL 완화 | `board_columns.state_key` 가 NULL 을 받는다 | **gap G1** |
 * | ⑤ 멱등 | SQL 을 직접 재실행해도 행이 안 는다 | E6 |
 *
 * ★ **③ 이 이 클래스에서 가장 중요하다.** `board_column_states` 는 X1(한 상태는 한 컬럼에만)을
 * `UNIQUE (board_id, state_key)` 로 지키는데, 그 `board_id` 는 비정규화 값이다. FK 두 개
 * (`column_id`→`board_columns` · `board_id`→`boards`)만으로는 **둘 사이의 정합을 아무도 안 지킨다** —
 * 애플리케이션이 컬럼의 실제 소유 보드와 다른 `board_id` 를 쓰면 UNIQUE 가 엉뚱한 것을 지키고
 * X1 이 조용히 무너진다. 복합 FK 가 그것을 닫는다(plan 리뷰 ceo-4).
 *
 * ★ ⑤ 는 Flyway 로 잴 수 없다 — 같은 버전을 두 번 적용하지 않는다. SQL 원문을 JDBC 로 직접
 * 한 번 더 돌려서 잰다(`V507` 선례와 같은 방법).
 *
 * 참조. `docs/specs/2026-09-03-board-column-multi-state.md` R1·R2·N1·E1·E6 · 편차 X1 ·
 * `docs/adr/2026-09-03-board-column-multi-state.md` D1·D3.
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class BoardColumnStatesMigrationTest {
    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))
                .withDatabaseName("bts_agileplanning_column_states_test")
                .withUsername("bts")
                .withPassword("bts_test")

        /** `V508` SQL 원문 경로 — E6 이 Flyway 밖에서 이 파일을 직접 한 번 더 돌린다. */
        const val V508_RESOURCE = "/db/migration/agile-planning/V508__board_column_states.sql"

        /** `V508` 적용 **직전**의 `board_columns` 스냅샷. 백필 무손실(①)의 기준선이다. */
        private lateinit var columnsBefore: List<ColumnRow>

        private fun flyway(target: String?) =
            Flyway.configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .placeholderReplacement(false)
                .locations("classpath:db/migration/agile-planning")
                .let { if (target == null) it else it.target(target) }
                .load()

        /**
         * 3단 부팅 — `V507` 까지 올린 뒤 픽스처를 심고 `V508` 을 태운다.
         *
         * 픽스처를 `V507` **뒤에** 심는 이유는 `V508` 이 백필할 대상을 확정하기 위해서다.
         * 그 전에 심으면 선행 마이그레이션들이 데이터를 옮겨 기준선이 흔들린다.
         */
        @BeforeAll
        @JvmStatic
        fun migrateWithSeedInBetween() {
            flyway("507").migrate()
            conn().use { c ->
                c.autoCommit = false
                seedBoardsWithColumns(c)
                c.commit()
            }
            columnsBefore = readColumns()
            flyway(null).migrate()
        }

        /**
         * 보드 2개와 컬럼 5개를 심는다.
         *
         * ```
         *   BOARD-A (SCRUM)          BOARD-B (KANBAN)
         *     ├ todo        (0)        ├ backlog     (0)
         *     ├ in_progress (1)        └ done        (1)
         *     └ done        (2)
         * ```
         *
         * 두 보드에 **같은 `done` 키**가 있다 — X1 은 「한 **보드**에서 한 컬럼에만」이므로
         * 보드가 다르면 같은 키가 공존해야 한다. ② 판정이 그 경계를 잰다.
         */
        private fun seedBoardsWithColumns(c: Connection) {
            val boardA = seedBoard(c, "ALPHA", "알파 스크럼 보드", "SCRUM")
            val boardB = seedBoard(c, "BETA", "베타 칸반 보드", "KANBAN")
            seedColumn(c, boardA, "todo", "할 일", "TODO", 0)
            seedColumn(c, boardA, "in_progress", "진행 중", "IN_PROGRESS", 1)
            seedColumn(c, boardA, "done", "완료", "DONE", 2)
            seedColumn(c, boardB, "backlog", "백로그", "TODO", 0)
            seedColumn(c, boardB, "done", "완료", "DONE", 1)
        }

        private fun seedBoard(
            c: Connection,
            projectKey: String,
            name: String,
            boardType: String,
        ): UUID {
            val id = UUID.randomUUID()
            // board_type 은 enum 이 아니라 문자열 컬럼이다 — KanbanSprintMoveMigrationTest 선례와 같다.
            c.prepareStatement(
                "INSERT INTO boards (id, project_key, name, board_type) VALUES (?, ?, ?, ?)",
            ).use { ps ->
                ps.setObject(1, id)
                ps.setString(2, projectKey)
                ps.setString(3, name)
                ps.setString(4, boardType)
                ps.executeUpdate()
            }
            return id
        }

        @Suppress("LongParameterList") // board_columns 컬럼과 1:1 — VO 로 묶으면 어느 칸을 심었는지 흐려진다
        private fun seedColumn(
            c: Connection,
            boardId: UUID,
            stateKey: String,
            name: String,
            category: String,
            displayOrder: Int,
        ): UUID {
            val id = UUID.randomUUID()
            c.prepareStatement(
                """
                INSERT INTO board_columns (id, board_id, state_key, name, category, display_order)
                VALUES (?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { ps ->
                ps.setObject(1, id)
                ps.setObject(2, boardId)
                ps.setString(3, stateKey)
                ps.setString(4, name)
                ps.setString(5, category)
                ps.setInt(6, displayOrder)
                ps.executeUpdate()
            }
            return id
        }

        private fun readColumns(): List<ColumnRow> =
            conn().use { c ->
                c.prepareStatement(
                    "SELECT id, board_id, state_key, display_order FROM board_columns ORDER BY id",
                ).use { ps ->
                    ps.executeQuery().use { rs ->
                        generateSequence { if (rs.next()) rowOf(rs) else null }.toList()
                    }
                }
            }

        private fun rowOf(rs: java.sql.ResultSet) =
            ColumnRow(
                id = rs.getObject("id", UUID::class.java),
                boardId = rs.getObject("board_id", UUID::class.java),
                stateKey = rs.getString("state_key"),
                displayOrder = rs.getInt("display_order"),
            )

        private fun scalarLong(sql: String): Long =
            conn().use { c ->
                c.createStatement().use { st ->
                    st.executeQuery(sql).use { rs ->
                        rs.next()
                        rs.getLong(1)
                    }
                }
            }

        fun conn(): Connection = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)

        data class ColumnRow(val id: UUID, val boardId: UUID, val stateKey: String?, val displayOrder: Int)
    }

    // ── ① 백필 무손실 (R2 · N1) ──────────────────────────────────────────────────

    @Test
    @Order(1)
    fun `백필 후 board_column_states 행 수가 적용 전 board_columns 행 수와 같다`() {
        val stateRows = scalarLong("SELECT count(*) FROM board_column_states")

        assertThat(stateRows).isEqualTo(columnsBefore.size.toLong())
        assertThat(stateRows).isEqualTo(5L)
    }

    @Test
    @Order(2)
    fun `백필된 board_id state_key 쌍이 적용 전 board_columns 의 것과 완전히 같다`() {
        val migrated =
            conn().use { c ->
                c.prepareStatement("SELECT board_id, state_key FROM board_column_states").use { ps ->
                    ps.executeQuery().use { rs ->
                        generateSequence {
                            if (rs.next()) {
                                rs.getObject("board_id", UUID::class.java) to rs.getString("state_key")
                            } else {
                                null
                            }
                        }.toSet()
                    }
                }
            }

        val expected = columnsBefore.map { it.boardId to it.stateKey }.toSet()
        assertThat(migrated).isEqualTo(expected)
    }

    @Test
    @Order(3)
    fun `백필이 column_id 를 원래 컬럼에 정확히 잇는다`() {
        // 컬럼과 연결 행이 1:1 로 대응하고 state_key 가 같아야 한다.
        val mismatched =
            scalarLong(
                """
                SELECT count(*)
                FROM board_column_states s
                JOIN board_columns c ON c.id = s.column_id
                WHERE c.state_key IS DISTINCT FROM s.state_key
                   OR c.board_id  IS DISTINCT FROM s.board_id
                """.trimIndent(),
            )

        assertThat(mismatched).isZero()
    }

    // ── ② X1 유지 (편차 X1 · ADR D3) ────────────────────────────────────────────

    @Test
    @Order(4)
    fun `한 보드에서 같은 상태를 두 컬럼에 매핑하면 거부된다`() {
        val (boardId, takenKey) =
            conn().use { c ->
                val sql = "SELECT board_id, state_key FROM board_column_states LIMIT 1"
                c.prepareStatement(sql).use { ps ->
                    ps.executeQuery().use { rs ->
                        rs.next()
                        rs.getObject("board_id", UUID::class.java) to rs.getString("state_key")
                    }
                }
            }
        // 같은 보드에 새 컬럼을 만들고 이미 쓰이는 상태를 매핑한다.
        val newColumnId =
            conn().use { c ->
                c.autoCommit = true
                seedColumn(c, boardId, "spare_key_for_x1", "여분", "TODO", 99)
            }

        assertThatThrownBy {
            conn().use { c ->
                c.prepareStatement(
                    "INSERT INTO board_column_states (column_id, board_id, state_key) VALUES (?, ?, ?)",
                ).use { ps ->
                    ps.setObject(1, newColumnId)
                    ps.setObject(2, boardId)
                    ps.setString(3, takenKey)
                    ps.executeUpdate()
                }
            }
        }.isInstanceOf(SQLException::class.java)
    }

    @Test
    @Order(5)
    fun `보드가 다르면 같은 상태 키가 공존한다`() {
        // 픽스처가 ALPHA·BETA 양쪽에 done 을 심었다. X1 은 보드 단위 제약이므로 둘 다 살아 있어야 한다.
        val doneRows = scalarLong("SELECT count(*) FROM board_column_states WHERE state_key = 'done'")

        assertThat(doneRows).isEqualTo(2L)
    }

    // ── ③ 복합 FK — ceo 리뷰 CONCERN-4 ──────────────────────────────────────────

    @Test
    @Order(6)
    fun `컬럼의 실제 소유 보드와 다른 board_id 를 쓰면 복합 FK 가 거부한다`() {
        // 이것이 없으면 UNIQUE(board_id, state_key) 가 엉뚱한 것을 지키고 X1 이 조용히 무너진다.
        val columnId =
            conn().use { c ->
                c.prepareStatement("SELECT id FROM board_columns LIMIT 1").use { ps ->
                    ps.executeQuery().use { rs ->
                        rs.next()
                        rs.getObject("id", UUID::class.java)
                    }
                }
            }
        val otherBoardId =
            conn().use { c ->
                c.prepareStatement(
                    """
                    SELECT b.id FROM boards b
                    WHERE b.id <> (SELECT board_id FROM board_columns WHERE id = ?)
                    LIMIT 1
                    """.trimIndent(),
                ).use { ps ->
                    ps.setObject(1, columnId)
                    ps.executeQuery().use { rs ->
                        rs.next()
                        rs.getObject("id", UUID::class.java)
                    }
                }
            }

        assertThatThrownBy {
            conn().use { c ->
                c.prepareStatement(
                    "INSERT INTO board_column_states (column_id, board_id, state_key) VALUES (?, ?, ?)",
                ).use { ps ->
                    ps.setObject(1, columnId)
                    ps.setObject(2, otherBoardId)
                    ps.setString(3, "mismatched_board_probe")
                    ps.executeUpdate()
                }
            }
        }.isInstanceOf(SQLException::class.java)
    }

    // ── ④ NOT NULL 완화 — gap G1 ────────────────────────────────────────────────

    @Test
    @Order(7)
    fun `board_columns state_key 가 NULL 을 허용한다`() {
        // 상태 0개 컬럼(E1·N4)을 이중 기록 창에서 표현하려면 필수다.
        val nullable =
            conn().use { c ->
                c.prepareStatement(
                    """
                    SELECT is_nullable FROM information_schema.columns
                    WHERE table_name = 'board_columns' AND column_name = 'state_key'
                    """.trimIndent(),
                ).use { ps ->
                    ps.executeQuery().use { rs ->
                        rs.next()
                        rs.getString("is_nullable")
                    }
                }
            }

        assertThat(nullable).isEqualTo("YES")
    }

    // ── FK 인덱스 (N3 · DATA.md §7) ─────────────────────────────────────────────

    @Test
    @Order(8)
    fun `column_id 전용 인덱스가 존재한다`() {
        // board_id 는 UNIQUE(board_id, state_key) 의 leftmost prefix 가 덮으므로 따로 두지 않는다.
        val indexes =
            conn().use { c ->
                val sql = "SELECT indexdef FROM pg_indexes WHERE tablename = 'board_column_states'"
                c.prepareStatement(sql).use { ps ->
                    ps.executeQuery().use { rs ->
                        generateSequence { if (rs.next()) rs.getString("indexdef") else null }.toList()
                    }
                }
            }

        assertThat(indexes).anyMatch { it.contains("(column_id)") }
    }

    // ── CASCADE (R10 의 DB 층) ──────────────────────────────────────────────────

    @Test
    @Order(9)
    fun `컬럼을 지우면 board_column_states 행이 함께 지워진다`() {
        val boardId = seedBoardFor("GAMMA")
        val columnId = conn().use { c -> seedColumn(c, boardId, "cascade_probe", "삭제 실험", "TODO", 0) }
        conn().use { c ->
            c.prepareStatement(
                "INSERT INTO board_column_states (column_id, board_id, state_key) VALUES (?, ?, ?)",
            ).use { ps ->
                ps.setObject(1, columnId)
                ps.setObject(2, boardId)
                ps.setString(3, "cascade_probe")
                ps.executeUpdate()
            }
        }

        conn().use { c ->
            c.prepareStatement("DELETE FROM board_columns WHERE id = ?").use { ps ->
                ps.setObject(1, columnId)
                ps.executeUpdate()
            }
        }

        val remaining =
            conn().use { c ->
                c.prepareStatement("SELECT count(*) FROM board_column_states WHERE column_id = ?").use { ps ->
                    ps.setObject(1, columnId)
                    ps.executeQuery().use { rs ->
                        rs.next()
                        rs.getLong(1)
                    }
                }
            }

        assertThat(remaining).isZero()
    }

    // ── ⑤ 멱등 (E6) ────────────────────────────────────────────────────────────

    @Test
    @Order(10)
    fun `V508 SQL 을 JDBC 로 직접 재실행해도 행이 늘지 않는다`() {
        // Flyway 는 같은 버전을 두 번 안 돌린다. 멱등은 Flyway 밖에서만 잴 수 있다.
        //
        // ★ **두 번 돌려 1차↔2차를 비교한다.** 「적용 전 ↔ 1차 후」를 비교하면 안 된다 —
        //   앞선 테스트(Order 4·9)가 만든 컬럼은 V508 이 처음 돌 때 없었으므로 1차 재실행이
        //   그것을 백필하는 것이 **올바른 동작**이다. 그 증가를 비멱등으로 세면 V508 이 옳은데도
        //   red 가 난다(실측으로 한 번 겪었다). 멱등은 「같은 입력에 같은 결과」이고,
        //   그 입력이 고정되는 시점은 1차 재실행 **뒤**다.
        val sql =
            requireNotNull(javaClass.getResourceAsStream(V508_RESOURCE)) {
                "V508 SQL 리소스를 찾을 수 없다: $V508_RESOURCE"
            }.bufferedReader().readText()

        runRaw(sql)
        val afterFirst = scalarLong("SELECT count(*) FROM board_column_states")
        runRaw(sql)
        val afterSecond = scalarLong("SELECT count(*) FROM board_column_states")

        assertThat(afterSecond).isEqualTo(afterFirst)
    }

    /** SQL 원문을 그대로 실행한다 — DDL 재실행이 죽지 않는지도 함께 잰다(`ADD CONSTRAINT` 함정). */
    private fun runRaw(sql: String) {
        conn().use { c ->
            c.autoCommit = true
            c.createStatement().use { st -> st.execute(sql) }
        }
    }

    private fun seedBoardFor(projectKey: String): UUID =
        conn().use { c ->
            c.autoCommit = true
            seedBoard(c, projectKey, "$projectKey 보드", "SCRUM")
        }
}
