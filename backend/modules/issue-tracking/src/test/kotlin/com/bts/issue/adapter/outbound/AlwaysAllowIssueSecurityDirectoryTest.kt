// AlwaysAllowIssueSecurityDirectory 단위 테스트 — non-prod stub 동작 + prod 차단 검증

package com.bts.issue.adapter.outbound

import com.bts.shared.permission.IssueSecurityDirectory
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.util.UUID

/**
 * [AlwaysAllowIssueSecurityDirectory] 단위 테스트.
 *
 * 검증 항목.
 * 1. levelBelongsToProjectScheme — 임의 levelId/projectKey 에 항상 true 를 반환한다.
 * 2. accessibleLevels — unrestricted=true (필터 미적용 신호) 를 반환한다.
 * 3. accessibleLevels — staticLevelIds/reporterLevelIds/assigneeLevelIds 모두 빈 집합.
 * 4. test profile 에서 Bean 이 [IssueSecurityDirectory] 로 등록된다.
 * 5. prod profile 에서 Bean 이 등록되지 않는다 (stub 운영 노출 차단).
 */
class AlwaysAllowIssueSecurityDirectoryTest {
    private val actorId = UUID.fromString("00000000-0000-4000-8000-000000000001")
    private val levelId = UUID.fromString("00000000-0000-4000-8000-000000000002")
    private val projectKey = "ATLAS"

    private val contextRunner =
        ApplicationContextRunner()
            .withUserConfiguration(AlwaysAllowIssueSecurityDirectory::class.java)

    // ── 1. levelBelongsToProjectScheme — 항상 true ──────────────────────────

    @Test
    fun `levelBelongsToProjectScheme 는 임의 인자에 대해 항상 true 를 반환한다`() {
        val directory = AlwaysAllowIssueSecurityDirectory()

        val result = directory.levelBelongsToProjectScheme(levelId, projectKey)

        assertThat(result).isTrue()
    }

    // ── 2. accessibleLevels — unrestricted=true ──────────────────────────────

    @Test
    fun `accessibleLevels 는 unrestricted=true 를 반환한다`() {
        val directory = AlwaysAllowIssueSecurityDirectory()

        val access = directory.accessibleLevels(actorId, projectKey)

        assertThat(access.unrestricted).isTrue()
    }

    // ── 3. accessibleLevels — 모든 levelIds 집합 비어있음 ────────────────────

    @Test
    fun `accessibleLevels 의 staticLevelIds 는 빈 집합이다`() {
        val directory = AlwaysAllowIssueSecurityDirectory()

        val access = directory.accessibleLevels(actorId, projectKey)

        assertThat(access.staticLevelIds).isEmpty()
    }

    @Test
    fun `accessibleLevels 의 reporterLevelIds 는 빈 집합이다`() {
        val directory = AlwaysAllowIssueSecurityDirectory()

        val access = directory.accessibleLevels(actorId, projectKey)

        assertThat(access.reporterLevelIds).isEmpty()
    }

    @Test
    fun `accessibleLevels 의 assigneeLevelIds 는 빈 집합이다`() {
        val directory = AlwaysAllowIssueSecurityDirectory()

        val access = directory.accessibleLevels(actorId, projectKey)

        assertThat(access.assigneeLevelIds).isEmpty()
    }

    // ── 4. test profile: Bean 등록 확인 ─────────────────────────────────────

    @Test
    fun `test profile 에서 AlwaysAllowIssueSecurityDirectory 는 IssueSecurityDirectory 로 등록된다`() {
        contextRunner
            .withPropertyValues("spring.profiles.active=test")
            .run { ctx ->
                assertThat(ctx).hasSingleBean(IssueSecurityDirectory::class.java)
                assertThat(ctx.getBean(IssueSecurityDirectory::class.java))
                    .isInstanceOf(AlwaysAllowIssueSecurityDirectory::class.java)
            }
    }

    // ── 5. prod profile: Bean 미등록 (운영 stub 차단) ────────────────────────

    /**
     * prod profile 활성화 시 `@Profile("!prod")` 조건으로 Bean 이 등록되지 않음을 검증한다.
     *
     * 운영 환경에서 stub 이 동작하면 모든 보안 등급 검사를 우회하므로,
     * 이 테스트가 실패하는 것은 즉각적인 보안 위협을 의미한다.
     */
    @Test
    fun `prod profile 에서 AlwaysAllowIssueSecurityDirectory 는 Bean 으로 등록되지 않는다`() {
        contextRunner
            .withPropertyValues("spring.profiles.active=prod")
            .run { ctx ->
                assertThat(ctx).doesNotHaveBean(AlwaysAllowIssueSecurityDirectory::class.java)
                assertThat(ctx).doesNotHaveBean(IssueSecurityDirectory::class.java)
            }
    }
}
