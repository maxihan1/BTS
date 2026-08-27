// issue-tracking V038 마이그레이션 검증 — bulk_operations.operation_type CHECK 가 STATUS_MIGRATION 을 허용하는지 확인

package com.bts.issue.bulk.db

import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.sql.DriverManager
import java.sql.SQLException

/**
 * Flyway V001~V038 전체 체인 적용 후 V038 변경사항을 검증한다.
 * Spring 컨텍스트 없이 Testcontainers PostgreSQL 에서 직접 실행한다.
 *
 * 검증 범위.
 * (a) operation_type='STATUS_MIGRATION' INSERT 성공 — V038 이 CHECK 를 넓혔다
 * (b) operation_type='NONSENSE' INSERT 거부 — CHECK 가 살아 있다는 비-공허 짝.
 *     위반 메시지의 제약 이름이 V008 의 chk_bulk_operations_operation_type 그대로인지도 함께 본다.
 *     이름을 바꾸면 다음 사람이 「어느 쪽이 진짜인가」를 못 푼다
 * (c) 기존 BULK_EDIT · BULK_TRANSITION INSERT 가 여전히 성공 — 회귀 방어
 * (d) jOOQ 코드젠 입력 `db/codegen/init_codegen.sql` 의 같은 CHECK 가 DB 와 같은 값 집합을 선언 —
 *     두 스키마 정의가 서로를 검사하지 않으면 다음 CHECK 변경 때 같은 자리에서 또 갈린다
 *
 * 이미지 선택 이유.
 * V002 마이그레이션이 pgmq 확장을 요구하므로 postgres:16-alpine 사용 불가.
 * quay.io/tembo/pg16-pgmq:latest (pgmq 사전 설치) 로 전체 마이그레이션 체인 실행.
 * V008MigrationIntegrationTest 와 동일 결정.
 *
 * 공용 dev DB 의 선재 행이 가짜 그린을 만들지 않도록 픽스처가 자기 행을 직접 INSERT 하고
 * 돌려받은 id 로만 판정한다 (DATA.md §4 마이그레이션 테스트 규칙).
 */
@Testcontainers
class V038MigrationIntegrationTest {
    companion object {
        private const val CONSTRAINT_NAME = "chk_bulk_operations_operation_type"
        private const val MIRROR_RESOURCE = "/db/codegen/init_codegen.sql"
        private val QUOTED_LITERAL = Regex("""'([^']*)'""")

        // quay.io/tembo/pg16-pgmq:latest — V002 pgmq 확장 요구로 인해 tembo 이미지 사용.
        // asCompatibleSubstituteFor("postgres"): Testcontainers 이미지 호환성 검증 우회.
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

        @BeforeAll
        @JvmStatic
        fun applyMigrations() {
            Flyway.configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .placeholderReplacement(false)
                .locations("classpath:db/migration/issue-tracking")
                .load()
                .migrate()
        }
    }

    // ── 헬퍼 ──────────────────────────────────────────────────────────────────

    private fun conn() = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)

    // operation_type 만 바꿔 최소 유효 row 를 넣고 생성된 id 를 돌려준다. CHECK 위반이면 SQLException.
    // JDBC use{} 표준 중첩(conn→stmt→rs) — 테스트 헬퍼라 분해 이득 없음
    @Suppress("NestedBlockDepth")
    private fun insertBulkOperation(operationType: String): String =
        conn().use { c ->
            c.prepareStatement(
                """
                INSERT INTO bulk_operations
                    (operation_type, status, actor_id, payload, total_count, created_at)
                VALUES
                    (?, 'PENDING', gen_random_uuid(), '{}', 0, now())
                RETURNING id::text
                """.trimIndent(),
            ).use { stmt ->
                stmt.setString(1, operationType)
                stmt.executeQuery().use { rs ->
                    rs.next()
                    rs.getString(1)
                }
            }
        }

    // 저장된 행을 id 로 되읽는다. 행이 없으면 null.
    @Suppress("NestedBlockDepth")
    private fun operationTypeOf(id: String): String? =
        conn().use { c ->
            c.prepareStatement(
                "SELECT operation_type FROM bulk_operations WHERE id = ?::uuid",
            ).use { stmt ->
                stmt.setString(1, id)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) rs.getString(1) else null
                }
            }
        }

    // ── (a) STATUS_MIGRATION 이 허용된다 ─────────────────────────────────────

    @Test
    fun `V038 bulk_operations 에 STATUS_MIGRATION 을 삽입하면 성공한다`() {
        val id = insertBulkOperation("STATUS_MIGRATION")

        assertThat(operationTypeOf(id))
            .describedAs("V038 이 CHECK 를 넓혔으므로 STATUS_MIGRATION 행이 저장돼 있어야 한다")
            .isEqualTo("STATUS_MIGRATION")
    }

    // ── (b) 정의되지 않은 값은 여전히 거부된다 (비-공허 짝) ────────────────────

    @Test
    fun `V038 이후에도 정의되지 않은 operation_type 은 CHECK 위반으로 거부된다`() {
        assertThrows<SQLException> {
            insertBulkOperation("NONSENSE")
        }.also { ex ->
            assertThat(ex.message)
                .describedAs(
                    "CHECK 가 살아 있어야 하고, 제약 이름은 V008 의 chk_bulk_operations_operation_type 그대로여야 한다",
                )
                .containsIgnoringCase("chk_bulk_operations_operation_type")
        }
    }

    // ── (c) 기존 2값 회귀 ────────────────────────────────────────────────────

    @Test
    fun `V038 이후에도 기존 BULK_EDIT 와 BULK_TRANSITION 삽입이 성공한다`() {
        val editId = insertBulkOperation("BULK_EDIT")
        val transitionId = insertBulkOperation("BULK_TRANSITION")

        assertThat(operationTypeOf(editId))
            .describedAs("V038 이 기존 BULK_EDIT 를 깨뜨리지 않아야 한다")
            .isEqualTo("BULK_EDIT")
        assertThat(operationTypeOf(transitionId))
            .describedAs("V038 이 기존 BULK_TRANSITION 을 깨뜨리지 않아야 한다")
            .isEqualTo("BULK_TRANSITION")
    }

    // ── (d) 코드젠 미러 차집합 가드 ──────────────────────────────────────────
    //
    // `init_codegen.sql` 은 jOOQ 코드 생성의 **유일한 입력**이자 손으로 유지하는 사본이다
    // (build.gradle.kts 의 codegenMirrorFile). Flyway 가 세우는 스키마와 별개 파일이라,
    // 마이그레이션만 고치고 미러를 잊으면 두 정의가 조용히 갈라진다 —
    // 이 저장소가 반복해 물린 「두 목록이 서로를 검사하지 않는다」 양식이다.
    // 지금 당장 안 터지더라도 다음 CHECK 변경이 같은 자리에서 또 갈린다.
    //
    // 한쪽 방향만 보면 반대쪽이 커지는 드리프트를 놓치므로 **양방향 차집합**을 각각 단언한다.

    @Test
    fun `codegen 미러의 operation_type CHECK 가 마이그레이션과 같은 값 집합을 선언한다`() {
        val migrated = migratedAllowedValues()
        val mirrored = mirrorAllowedValues()

        assertThat(migrated)
            .describedAs("DB 제약 정의 파싱 결과가 비었다 — 드리프트가 아니라 파서가 깨진 것이다")
            .isNotEmpty()

        assertThat(mirrored - migrated)
            .describedAs("미러에만 있는 값 — 코드젠은 아는데 마이그레이션이 거부한다")
            .isEmpty()

        assertThat(migrated - mirrored)
            .describedAs("마이그레이션에만 있는 값 — CHECK 를 넓히고 %s 미러를 안 고쳤다", MIRROR_RESOURCE)
            .isEmpty()
    }

    /**
     * 마이그레이션 체인이 적용된 DB 의 [CONSTRAINT_NAME] 이 실제로 허용하는 값 집합.
     *
     * PostgreSQL 은 `IN (...)` 을 `= ANY (ARRAY[...])` 로 정규화해 돌려주므로 `IN` 문자열을 찾으면 안 된다.
     * 정의 문자열 안의 작은따옴표 리터럴이 곧 허용값 전부다.
     */
    @Suppress("NestedBlockDepth")
    private fun migratedAllowedValues(): Set<String> =
        conn().use { c ->
            c.prepareStatement(
                """
                SELECT pg_get_constraintdef(oid)
                FROM pg_constraint
                WHERE conname = ? AND conrelid = 'bulk_operations'::regclass
                """.trimIndent(),
            ).use { stmt ->
                stmt.setString(1, CONSTRAINT_NAME)
                stmt.executeQuery().use { rs ->
                    check(rs.next()) { "bulk_operations 에 제약 $CONSTRAINT_NAME 이 없다" }
                    quotedLiterals(rs.getString(1))
                }
            }
        }

    /**
     * 코드젠 미러 파일이 선언한 허용값 집합.
     *
     * 제약 선언을 앵커로 잡고 **그 제약 블록 안에서만** `IN (...)` 을 읽는다.
     * 창을 안 좁히면 미러가 이 CHECK 를 통째로 잃었을 때 아래쪽 다른 제약의 목록을 잘못 집어
     * 판별식이 공허해진다.
     */
    private fun mirrorAllowedValues(): Set<String> {
        val sql =
            javaClass.getResource(MIRROR_RESOURCE)?.readText()
                ?: error("$MIRROR_RESOURCE 이 클래스패스에 없다 — jOOQ 코드젠 입력이 사라졌다")
        val anchor =
            Regex("""CONSTRAINT\s+$CONSTRAINT_NAME\b""").find(sql)
                ?: error("$MIRROR_RESOURCE 에 $CONSTRAINT_NAME 선언이 없다 — 미러가 CHECK 를 잃었다")
        val block = sql.substring(anchor.range.last + 1).substringBefore("CONSTRAINT")
        val inList =
            Regex("""IN\s*\(([^)]*)\)""").find(block)
                ?: error("$MIRROR_RESOURCE 의 $CONSTRAINT_NAME 에 IN 목록이 없다 — 표기를 바꿨다면 이 판별식도 고쳐라")
        return quotedLiterals(inList.groupValues[1])
    }

    // 작은따옴표 리터럴만 뽑는다. 허용값에 따옴표가 들어가는 날이 오면 이 파서를 다시 봐야 한다.
    private fun quotedLiterals(text: String): Set<String> = QUOTED_LITERAL.findAll(text).map { it.groupValues[1] }.toSet()
}
