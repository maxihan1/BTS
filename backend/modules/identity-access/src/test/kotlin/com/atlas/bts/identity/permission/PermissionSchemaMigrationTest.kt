// V008 마이그레이션 검증 — 기본 권한 스킴 시드 + 매트릭스 5행 + project_permission_scheme 테이블 존재 확인

package com.atlas.bts.identity.permission

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * Flyway V001~V008 마이그레이션 자동 적용 후 권한 스킴 테이블 + 기본 시드를 검증한다.
 *
 * ## 검증 항목
 * - role_permissions JOIN permission_schemes WHERE is_default = TRUE 의 행 수 = 5 (ADMIN 3 + MEMBER 2)
 * - permission_schemes 중 is_default = TRUE 인 행 = 1 (부분 유니크 인덱스 보장)
 * - project_permission_scheme 테이블 존재 (시드 없음, 모두 기본 스킴 fallback)
 *
 * ProjectMembershipRepositoryIntegrationTest 의 @JdbcTest + @DynamicPropertySource 패턴을 복제.
 */
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class PermissionSchemaMigrationTest {
    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer("postgres:16-alpine")
                .withDatabaseName("bts_test")
                .withUsername("bts")
                .withPassword("bts_test")

        @DynamicPropertySource
        @JvmStatic
        fun postgresProps(r: DynamicPropertyRegistry) {
            r.add("spring.datasource.url") { postgres.jdbcUrl }
            r.add("spring.datasource.username") { postgres.username }
            r.add("spring.datasource.password") { postgres.password }
            r.add("spring.flyway.enabled") { "true" }
        }
    }

    @Autowired
    private lateinit var jdbc: NamedParameterJdbcTemplate

    @Test
    fun `기본 스킴과 매트릭스가 시드된다`() {
        // role_permissions × permission_schemes(is_default=TRUE) JOIN 결과 8행 기대
        // PROJECT_ADMIN(6행): CREATE_ISSUE, EDIT_ISSUE, DELETE_ISSUE,
        //   MANAGE_COMPONENTS, MANAGE_VERSIONS, MANAGE_WORKFLOW
        // MEMBER(2행): CREATE_ISSUE, EDIT_ISSUE
        // (V009 — FR-PM-03이 PROJECT_ADMIN에 MANAGE_COMPONENTS/MANAGE_VERSIONS 2행 추가)
        // (V013 — FR-PM-04가 PROJECT_ADMIN에 MANAGE_WORKFLOW 1행 추가)
        val count =
            jdbc.queryForObject(
                """
            SELECT count(*)
            FROM role_permissions rp
            JOIN permission_schemes ps ON rp.scheme_id = ps.id
            WHERE ps.is_default = TRUE
            """,
                mapOf<String, Any>(),
                Int::class.java,
            )
        assertThat(count).isEqualTo(8)
    }

    @Test
    fun `기본 스킴 PROJECT_ADMIN이 MANAGE_WORKFLOW를 보유한다`() {
        // V013 — FR-PM-04: 워크플로우 스킴 배정(ASSIGN_SCHEME/Project) 권한 판정의 정본 코드.
        // 기본 스킴(00000000-…-001) PROJECT_ADMIN 역할에 MANAGE_WORKFLOW(SDD 12.3) 1행이 시드되어야 한다.
        val count =
            jdbc.queryForObject(
                """
            SELECT count(*)
            FROM role_permissions rp
            JOIN permission_schemes ps ON rp.scheme_id = ps.id
            WHERE ps.is_default = TRUE
              AND rp.role = 'PROJECT_ADMIN'
              AND rp.permission_code = 'MANAGE_WORKFLOW'
            """,
                mapOf<String, Any>(),
                Int::class.java,
            )
        assertThat(count).isEqualTo(1)
    }

    @Test
    fun `기본 스킴은 단 하나만 존재한다`() {
        // uq_permission_schemes_default 부분 유니크 인덱스로 보장
        val defaultCount =
            jdbc.queryForObject(
                "SELECT count(*) FROM permission_schemes WHERE is_default = TRUE",
                mapOf<String, Any>(),
                Int::class.java,
            )
        assertThat(defaultCount).isEqualTo(1)
    }

    @Test
    fun `project_permission_scheme 테이블이 존재한다`() {
        // 시드는 없고 테이블만 존재 — 미매핑 프로젝트는 기본 스킴 fallback 동작
        val tableCount =
            jdbc.queryForObject(
                """
            SELECT count(*)
            FROM information_schema.tables
            WHERE table_schema = 'public'
              AND table_name = 'project_permission_scheme'
            """,
                mapOf<String, Any>(),
                Int::class.java,
            )
        assertThat(tableCount).isEqualTo(1)
    }
}
