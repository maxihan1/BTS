// 유효 스킴 해석 + 권한 판정 통합테스트 — JdbcPermissionSchemeRepository

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
 * [JdbcPermissionSchemeRepository] 통합테스트.
 *
 * ## 전제
 * - V001~V008 Flyway 마이그레이션이 자동 적용되어 기본 스킴(UUID 00000000-…-0001)과
 *   역할-권한 매트릭스 5행이 시드된 상태에서 시작한다.
 * - project_permission_scheme 에는 시드 없음 → 모든 projectId가 기본 스킴 fallback.
 *
 * ## 검증 시나리오
 * (a) 미매핑 projectId → 기본 스킴: PROJECT_ADMIN의 DELETE_ISSUE = true, MEMBER의 DELETE_ISSUE = false
 * (b) 미매핑 projectId → 기본 스킴: MEMBER의 CREATE_ISSUE = true
 *
 * PermissionSchemaMigrationTest의 @JdbcTest + @DynamicPropertySource 컨테이너 패턴 복제.
 */
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import(JdbcPermissionSchemeRepository::class)
class JdbcPermissionSchemeRepositoryTest {

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
     * 기본 스킴에서 PROJECT_ADMIN은 DELETE_ISSUE 권한을 보유하고 MEMBER는 보유하지 않는다.
     */
    @Test
    fun `미매핑 프로젝트는 기본 스킴으로 ADMIN의 DELETE_ISSUE 허용`() {
        val projectId = UUID.randomUUID() // project_permission_scheme 매핑 없음

        assertThat(repo.roleHasPermission(projectId, "PROJECT_ADMIN", "DELETE_ISSUE")).isTrue()
        assertThat(repo.roleHasPermission(projectId, "MEMBER", "DELETE_ISSUE")).isFalse()
    }

    /**
     * 미매핑 projectId에서 MEMBER는 CREATE_ISSUE 권한을 보유한다.
     * (기본 스킴 시드: MEMBER = CREATE_ISSUE + EDIT_ISSUE)
     */
    @Test
    fun `미매핑 프로젝트는 기본 스킴으로 MEMBER의 CREATE_ISSUE 허용`() {
        val projectId = UUID.randomUUID() // project_permission_scheme 매핑 없음

        assertThat(repo.roleHasPermission(projectId, "MEMBER", "CREATE_ISSUE")).isTrue()
    }
}
