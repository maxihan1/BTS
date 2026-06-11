// SensitiveProjectResolver issue-tracking adapter 통합 테스트 — jOOQ + Testcontainers PostgreSQL (FR-MF-04)

package com.bts.issue.project.adapter

import com.bts.issue.repository.IssueTestcontainersBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.sql.DriverManager
import java.util.UUID

/**
 * [IssueTrackingSensitiveProjectResolver] 통합 테스트.
 *
 * [IssueTestcontainersBase] JVM singleton PostgreSQL 컨테이너를 재사용한다.
 * Spring ApplicationContext 없이 DSLContext 를 직접 조합한다.
 *
 * 검증 시나리오.
 * - T2-A. require_2fa=true 프로젝트 id 를 포함하면 true.
 * - T2-B. require_2fa=false 프로젝트만 포함하면 false.
 * - T2-C. 존재하지 않는 id 만 포함하면 false.
 * - T2-D. soft-deleted(deleted_at NOT NULL) + require_2fa=true 는 제외 → false.
 * - T2-E. 빈 집합 → 쿼리 없이 즉시 false.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IssueTrackingSensitiveProjectResolverTest : IssueTestcontainersBase() {

    private lateinit var resolver: IssueTrackingSensitiveProjectResolver

    /** 각 테스트용 프로젝트 UUID 들 — @BeforeEach 에서 매번 재삽입 */
    private lateinit var sensitiveId: UUID
    private lateinit var normalId: UUID
    private lateinit var deletedSensitiveId: UUID

    @BeforeEach
    fun setupProjects() {
        resolver = IssueTrackingSensitiveProjectResolver(dsl)

        // 이전 테스트 데이터 정리 (issues + 테스트용 프로젝트 행)
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("DELETE FROM issues")
                stmt.execute(
                    "DELETE FROM projects WHERE key IN ('SENS','NORM','DELSENS')",
                )
            }

            // T2-A용: require_2fa=true, 활성 프로젝트
            sensitiveId = UUID.randomUUID()
            conn.prepareStatement(
                "INSERT INTO projects (id, key, name, require_2fa) VALUES (?,'SENS','Sensitive Project',true)",
            ).use { stmt ->
                stmt.setObject(1, sensitiveId)
                stmt.executeUpdate()
            }

            // T2-B용: require_2fa=false(기본값), 활성 프로젝트
            normalId = UUID.randomUUID()
            conn.prepareStatement(
                "INSERT INTO projects (id, key, name, require_2fa) VALUES (?,'NORM','Normal Project',false)",
            ).use { stmt ->
                stmt.setObject(1, normalId)
                stmt.executeUpdate()
            }

            // T2-D용: require_2fa=true 이지만 soft-deleted
            deletedSensitiveId = UUID.randomUUID()
            conn.prepareStatement(
                "INSERT INTO projects (id, key, name, require_2fa, deleted_at) VALUES (?,'DELSENS','Deleted Sensitive',true,NOW())",
            ).use { stmt ->
                stmt.setObject(1, deletedSensitiveId)
                stmt.executeUpdate()
            }
        }
    }

    @Test
    fun `T2-A require_2fa true 프로젝트 포함 시 true 반환`() {
        val result = resolver.anyRequiresMfa(setOf(sensitiveId, normalId))
        assertThat(result).isTrue()
    }

    @Test
    fun `T2-B require_2fa false 프로젝트만 포함 시 false 반환`() {
        val result = resolver.anyRequiresMfa(setOf(normalId))
        assertThat(result).isFalse()
    }

    @Test
    fun `T2-C 존재하지 않는 id 만 포함 시 false 반환`() {
        val result = resolver.anyRequiresMfa(setOf(UUID.randomUUID(), UUID.randomUUID()))
        assertThat(result).isFalse()
    }

    @Test
    fun `T2-D soft-deleted require_2fa true 프로젝트는 제외되어 false 반환`() {
        val result = resolver.anyRequiresMfa(setOf(deletedSensitiveId))
        assertThat(result).isFalse()
    }

    @Test
    fun `T2-E 빈 집합 전달 시 즉시 false 반환`() {
        val result = resolver.anyRequiresMfa(emptySet<UUID>())
        assertThat(result).isFalse()
    }
}
