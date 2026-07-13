// AlwaysUnrestrictedIssueSecurityClassification 단위 테스트 — 항상 미제한 + @Profile("!prod") 격리 + Bean 프로파일 배타 검증

package com.bts.slack.config

import com.bts.shared.issue.IssueSecurityClassificationPort
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Profile

/**
 * [AlwaysUnrestrictedIssueSecurityClassification] 단위 테스트.
 *
 * 검증 항목.
 * 1. 임의 issueKey 에 대해 isSecurityRestricted 가 `false`(제한 없음 → 게시 허용)를 반환한다.
 * 2. 클래스가 `@Profile("!prod")` 를 보유한다(리플렉션) — prod 에서는 절대 로드되지 않는다.
 * 3. test profile 에서 Bean 이 [IssueSecurityClassificationPort] 로 등록된다.
 * 4. prod profile 에서 Bean 이 등록되지 않는다 — allow-all stub 의 운영 노출 차단(보안 위협 가드).
 */
class AlwaysUnrestrictedIssueSecurityClassificationTest {
    private val contextRunner =
        ApplicationContextRunner()
            .withUserConfiguration(AlwaysUnrestrictedIssueSecurityClassification::class.java)

    // ── 1. 항상 미제한 ────────────────────────────────────────────────────────

    @Test
    fun `isSecurityRestricted 는 임의 issueKey 에 대해 false 를 반환한다`() {
        val stub = AlwaysUnrestrictedIssueSecurityClassification()

        assertThat(stub.isSecurityRestricted("ATLAS-1")).isFalse()
        assertThat(stub.isSecurityRestricted("ANY-999")).isFalse()
    }

    // ── 2. @Profile("!prod") 어노테이션 보유 (리플렉션) ───────────────────────

    @Test
    fun `클래스는 Profile !prod 를 보유한다`() {
        val profile =
            AlwaysUnrestrictedIssueSecurityClassification::class.java
                .getAnnotation(Profile::class.java)

        assertThat(profile).isNotNull()
        assertThat(profile.value).containsExactly("!prod")
    }

    // ── 3. test profile: Bean 등록 확인 ─────────────────────────────────────

    @Test
    fun `test profile 에서 stub 은 IssueSecurityClassificationPort 로 등록된다`() {
        contextRunner
            .withPropertyValues("spring.profiles.active=test")
            .run { ctx ->
                assertThat(ctx).hasSingleBean(IssueSecurityClassificationPort::class.java)
                assertThat(ctx.getBean(IssueSecurityClassificationPort::class.java))
                    .isInstanceOf(AlwaysUnrestrictedIssueSecurityClassification::class.java)
            }
    }

    // ── 4. prod profile: Bean 미등록 (운영 allow-all 차단) ────────────────────

    @Test
    fun `prod profile 에서 stub 은 Bean 으로 등록되지 않는다`() {
        contextRunner
            .withPropertyValues("spring.profiles.active=prod")
            .run { ctx ->
                assertThat(ctx).doesNotHaveBean(AlwaysUnrestrictedIssueSecurityClassification::class.java)
                assertThat(ctx).doesNotHaveBean(IssueSecurityClassificationPort::class.java)
            }
    }
}
