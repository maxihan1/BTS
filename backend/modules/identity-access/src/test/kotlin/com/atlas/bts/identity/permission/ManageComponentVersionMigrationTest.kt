// V009 마이그레이션 검증 — 기본 스킴에 MANAGE_COMPONENTS/MANAGE_VERSIONS 시드(PROJECT_ADMIN 전용, MEMBER 미부여)

package com.atlas.bts.identity.permission

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID

/**
 * V009 마이그레이션 검증 — 기본 스킴(00000000-…-0001)에 MANAGE_COMPONENTS/MANAGE_VERSIONS 2행 시드.
 *
 * ## 전제
 * - V001~V009 Flyway 마이그레이션이 자동 적용되어 기본 스킴과 매트릭스가 시드된 상태에서 시작한다.
 * - project_permission_scheme 에는 시드 없음 → 모든 projectId가 기본 스킴 fallback.
 *
 * ## 검증 시나리오 (FR-PM-03 D4, PROJECT_ADMIN 전용)
 * - PROJECT_ADMIN × MANAGE_COMPONENTS = true (기본 스킴 fallback)
 * - PROJECT_ADMIN × MANAGE_VERSIONS   = true
 * - MEMBER        × MANAGE_COMPONENTS = false (미부여 → 403)
 * - MEMBER        × MANAGE_VERSIONS   = false
 *
 * JdbcPermissionSchemeRepositoryTest 의 @JdbcTest + @DynamicPropertySource 컨테이너 패턴 복제.
 */
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import(JdbcPermissionSchemeRepository::class)
class ManageComponentVersionMigrationTest {
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
    private lateinit var repo: PermissionSchemeRepository

    /**
     * 미매핑 projectId는 기본 스킴으로 fallback한다.
     * 기본 스킴에서 PROJECT_ADMIN은 MANAGE_COMPONENTS/MANAGE_VERSIONS 권한을 보유한다.
     */
    @Test
    fun `미매핑 프로젝트는 기본 스킴으로 PROJECT_ADMIN의 MANAGE_COMPONENTS, MANAGE_VERSIONS 허용`() {
        val projectId = UUID.randomUUID() // project_permission_scheme 매핑 없음

        assertThat(repo.roleHasPermission(projectId, "PROJECT_ADMIN", "MANAGE_COMPONENTS")).isTrue()
        assertThat(repo.roleHasPermission(projectId, "PROJECT_ADMIN", "MANAGE_VERSIONS")).isTrue()
    }

    /**
     * MEMBER에는 MANAGE_* 권한이 부여되지 않는다(행정 성격, Jira 'Administer Projects' 계열).
     * 매트릭스 미보유 → false → 호출부에서 403.
     */
    @Test
    fun `미매핑 프로젝트에서 MEMBER의 MANAGE_COMPONENTS, MANAGE_VERSIONS 거부`() {
        val projectId = UUID.randomUUID() // project_permission_scheme 매핑 없음

        assertThat(repo.roleHasPermission(projectId, "MEMBER", "MANAGE_COMPONENTS")).isFalse()
        assertThat(repo.roleHasPermission(projectId, "MEMBER", "MANAGE_VERSIONS")).isFalse()
    }
}
