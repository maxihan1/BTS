// V016 마이그레이션 검증 — 이슈 보안 스킴/등급/멤버/프로젝트적용 테이블 존재 + 제약(UNIQUE/CHECK/CASCADE/RESTRICT) 확인 (FR-PM-06 PR-A)

package com.atlas.bts.identity.issuesecurity

import com.atlas.bts.identity.support.SharedPostgres
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/**
 * Flyway V001~V016 마이그레이션 자동 적용 후 이슈 보안 수준 스키마를 검증한다.
 *
 * ## 검증 항목
 * - 4개 테이블 존재: issue_security_schemes / issue_security_levels /
 *   issue_security_level_members / project_issue_security_schemes
 * - scheme.name UNIQUE
 * - level (scheme_id, name) UNIQUE
 * - 스킴당 is_default 최대 1 (부분 유니크 인덱스 uq_security_level_one_default)
 * - 스킴 삭제 → levels → members 연쇄 삭제 (ON DELETE CASCADE)
 * - member_type CHECK 제약 (5종만 허용)
 * - member (level_id, member_type, member_value) UNIQUE
 * - project_issue_security_schemes.scheme_id FK ON DELETE RESTRICT (적용 중 스킴 삭제 차단)
 *
 * PermissionSchemaMigrationTest 의 @JdbcTest + @DynamicPropertySource 패턴을 복제.
 */
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class IssueSecuritySchemaMigrationTest {
    companion object {
        /**
         * 공용 컨테이너의 템플릿 DB 를 복제한 전용 데이터베이스.
         *
         * 격리는 그대로이고 컨테이너 기동과 마이그레이션 재적용만 사라진다.
         * 근거와 주의점은 [com.atlas.bts.identity.support.SharedPostgres] 헤더.
         */
        @JvmStatic
        val postgres = SharedPostgres.freshDatabase()

        @DynamicPropertySource
        @JvmStatic
        fun postgresProps(r: DynamicPropertyRegistry) {
            r.add("spring.datasource.url") { postgres.jdbcUrl }
            r.add("spring.datasource.username") { postgres.username }
            r.add("spring.datasource.password") { postgres.password }
            // 템플릿 DB 에서 이미 적용됐다 — 여기서 다시 돌리면 이 최적화가 무의미해진다
            r.add("spring.flyway.enabled") { "false" }
            // 공용 컨테이너라 커넥션 한도도 공유한다. context 캐시가 쌓이면 기본 풀(10)로는
            // max_connections 를 넘긴다 — SharedPostgres 헤더 참조.
            r.add("spring.datasource.hikari.maximum-pool-size") { SharedPostgres.MAX_POOL_SIZE }
        }
    }

    @Autowired
    private lateinit var jdbc: NamedParameterJdbcTemplate

    private fun tableExists(name: String): Int =
        jdbc.queryForObject(
            """
            SELECT count(*)
            FROM information_schema.tables
            WHERE table_schema = 'public'
              AND table_name = :name
            """,
            mapOf("name" to name),
            Int::class.java,
        )!!

    private fun insertScheme(name: String): String =
        jdbc.queryForObject(
            "INSERT INTO issue_security_schemes (name) VALUES (:name) RETURNING id::text",
            mapOf("name" to name),
            String::class.java,
        )!!

    private fun insertLevel(
        schemeId: String,
        name: String,
        isDefault: Boolean = false,
    ): String =
        jdbc.queryForObject(
            """
            INSERT INTO issue_security_levels (scheme_id, name, is_default)
            VALUES (CAST(:schemeId AS UUID), :name, :isDefault)
            RETURNING id::text
            """,
            mapOf("schemeId" to schemeId, "name" to name, "isDefault" to isDefault),
            String::class.java,
        )!!

    @Test
    fun `4개 보안 수준 테이블이 존재한다`() {
        assertThat(tableExists("issue_security_schemes")).isEqualTo(1)
        assertThat(tableExists("issue_security_levels")).isEqualTo(1)
        assertThat(tableExists("issue_security_level_members")).isEqualTo(1)
        assertThat(tableExists("project_issue_security_schemes")).isEqualTo(1)
    }

    @Test
    fun `스킴 name은 전역 UNIQUE다`() {
        insertScheme("Confidential")
        assertThatThrownBy { insertScheme("Confidential") }
            .isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `등급 name은 스킴 내 UNIQUE다`() {
        val schemeId = insertScheme("Scheme-LevelUnique")
        insertLevel(schemeId, "Executives")
        // 같은 스킴 내 같은 이름 → 위반.
        // (@JdbcTest 단일 트랜잭션이라 위반 후 같은 트랜잭션 내 추가 INSERT 불가 → 위반 단언만.)
        assertThatThrownBy { insertLevel(schemeId, "Executives") }
            .isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `다른 스킴이면 같은 등급 이름을 허용한다`() {
        val schemeA = insertScheme("Scheme-LevelName-A")
        val schemeB = insertScheme("Scheme-LevelName-B")
        // (scheme_id, name) 복합 UNIQUE라 스킴이 다르면 같은 이름 등급 허용.
        insertLevel(schemeA, "Executives")
        insertLevel(schemeB, "Executives")
    }

    @Test
    fun `스킴당 기본 등급은 최대 1개다`() {
        val schemeId = insertScheme("Scheme-Default")
        insertLevel(schemeId, "First", isDefault = true)
        // 같은 스킴에 둘째 기본 등급 → 부분 유니크 인덱스 위반.
        // (단일 트랜잭션이라 위반 후 추가 INSERT 불가 → 위반 단언만.)
        assertThatThrownBy { insertLevel(schemeId, "Second", isDefault = true) }
            .isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `is_default false 등급은 스킴당 여러 개 허용한다`() {
        val schemeId = insertScheme("Scheme-NonDefault")
        // 부분 유니크 인덱스는 is_default=TRUE 행에만 적용 → false 등급은 제한 없음.
        insertLevel(schemeId, "Lvl1", isDefault = false)
        insertLevel(schemeId, "Lvl2", isDefault = false)
        insertLevel(schemeId, "Lvl3", isDefault = false)
    }

    @Test
    fun `스킴 삭제 시 등급과 멤버가 연쇄 삭제된다`() {
        val schemeId = insertScheme("Scheme-Cascade")
        val levelId = insertLevel(schemeId, "Internal")
        jdbc.update(
            """
            INSERT INTO issue_security_level_members (level_id, member_type, member_value)
            VALUES (CAST(:levelId AS UUID), 'PROJECT_ROLE', 'PROJECT_ADMIN')
            """,
            mapOf("levelId" to levelId),
        )

        jdbc.update(
            "DELETE FROM issue_security_schemes WHERE id = CAST(:id AS UUID)",
            mapOf("id" to schemeId),
        )

        val levelCount =
            jdbc.queryForObject(
                "SELECT count(*) FROM issue_security_levels WHERE scheme_id = CAST(:id AS UUID)",
                mapOf("id" to schemeId),
                Int::class.java,
            )
        val memberCount =
            jdbc.queryForObject(
                "SELECT count(*) FROM issue_security_level_members WHERE level_id = CAST(:id AS UUID)",
                mapOf("id" to levelId),
                Int::class.java,
            )
        assertThat(levelCount).isEqualTo(0)
        assertThat(memberCount).isEqualTo(0)
    }

    @Test
    fun `member_type은 5종만 허용한다 (CHECK 제약)`() {
        val schemeId = insertScheme("Scheme-Check")
        val levelId = insertLevel(schemeId, "Lvl")

        // 허용된 5종은 통과
        listOf(
            "REPORTER" to null,
            "ASSIGNEE" to null,
            "USER" to "00000000-0000-4000-8000-000000000001",
            "PROJECT_ROLE" to "MEMBER",
            "GROUP" to "00000000-0000-4000-8000-000000000002",
        ).forEach { (type, value) ->
            jdbc.update(
                """
                INSERT INTO issue_security_level_members (level_id, member_type, member_value)
                VALUES (CAST(:levelId AS UUID), :type, :value)
                """,
                mapOf("levelId" to levelId, "type" to type, "value" to value),
            )
        }

        // 허용되지 않은 타입 → CHECK 위반
        assertThatThrownBy {
            jdbc.update(
                """
                INSERT INTO issue_security_level_members (level_id, member_type, member_value)
                VALUES (CAST(:levelId AS UUID), 'WATCHER', NULL)
                """,
                mapOf("levelId" to levelId),
            )
        }.isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `멤버는 level_id member_type member_value 조합 UNIQUE다`() {
        val schemeId = insertScheme("Scheme-MemberUnique")
        val levelId = insertLevel(schemeId, "Lvl")
        jdbc.update(
            """
            INSERT INTO issue_security_level_members (level_id, member_type, member_value)
            VALUES (CAST(:levelId AS UUID), 'USER', '00000000-0000-4000-8000-000000000003')
            """,
            mapOf("levelId" to levelId),
        )
        assertThatThrownBy {
            jdbc.update(
                """
                INSERT INTO issue_security_level_members (level_id, member_type, member_value)
                VALUES (CAST(:levelId AS UUID), 'USER', '00000000-0000-4000-8000-000000000003')
                """,
                mapOf("levelId" to levelId),
            )
        }.isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `프로젝트에 적용 중인 스킴은 삭제가 차단된다 (FK RESTRICT)`() {
        val schemeId = insertScheme("Scheme-InUse")
        val projectId = "00000000-0000-4000-8000-0000000000aa"
        jdbc.update(
            """
            INSERT INTO project_issue_security_schemes (project_id, scheme_id)
            VALUES (CAST(:projectId AS UUID), CAST(:schemeId AS UUID))
            """,
            mapOf("projectId" to projectId, "schemeId" to schemeId),
        )

        // 적용 중인 스킴 삭제 → ON DELETE RESTRICT 위반
        assertThatThrownBy {
            jdbc.update(
                "DELETE FROM issue_security_schemes WHERE id = CAST(:id AS UUID)",
                mapOf("id" to schemeId),
            )
        }.isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `project_id는 프로젝트당 단일 적용을 보장한다 (PK)`() {
        val schemeA = insertScheme("Scheme-PK-A")
        val schemeB = insertScheme("Scheme-PK-B")
        val projectId = "00000000-0000-4000-8000-0000000000bb"
        jdbc.update(
            """
            INSERT INTO project_issue_security_schemes (project_id, scheme_id)
            VALUES (CAST(:projectId AS UUID), CAST(:schemeId AS UUID))
            """,
            mapOf("projectId" to projectId, "schemeId" to schemeA),
        )
        assertThatThrownBy {
            jdbc.update(
                """
                INSERT INTO project_issue_security_schemes (project_id, scheme_id)
                VALUES (CAST(:projectId AS UUID), CAST(:schemeId AS UUID))
                """,
                mapOf("projectId" to projectId, "schemeId" to schemeB),
            )
        }.isInstanceOf(DataIntegrityViolationException::class.java)
    }
}
