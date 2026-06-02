// AlwaysAllowIssuePermissionResolver 단위 테스트 — Bean 프로파일 격리 + WARN 로그 발생 검증

package com.bts.issue.adapter.outbound

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.bts.shared.permission.IssuePermission
import com.bts.shared.permission.IssuePermissionResolver
import com.bts.shared.permission.IssueScope
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.util.UUID

/**
 * [AlwaysAllowIssuePermissionResolver] 단위 테스트.
 *
 * 검증 항목.
 * 1. CREATE + IssueScope.Project — hasPermission 이 true 를 반환한다.
 * 2. SOFT_DELETE + IssueScope.Issue — hasPermission 이 true 를 반환한다.
 * 3. VIEW + IssueScope.Global — hasPermission 이 true 를 반환한다.
 * 4. 모든 호출에서 WARN 레벨 로그가 발생한다.
 * 5. test profile 에서 Bean 이 [IssuePermissionResolver] 로 등록된다.
 * 6. prod profile 에서 Bean 이 등록되지 않는다 (stub 운영 노출 차단).
 */
class AlwaysAllowIssuePermissionResolverTest {
    private val actorId = UUID.randomUUID()

    // ── ApplicationContextRunner 픽스처 ─────────────────────────────────────
    private val contextRunner =
        ApplicationContextRunner()
            .withUserConfiguration(AlwaysAllowIssuePermissionResolver::class.java)

    // ── 1. CREATE + IssueScope.Project — true ────────────────────────────────

    @Test
    fun `CREATE 권한 + Project scope 에서 hasPermission 은 true 를 반환한다`() {
        val resolver = AlwaysAllowIssuePermissionResolver()

        val result = resolver.hasPermission(actorId, IssuePermission.CREATE, IssueScope.Project("ATLAS"))

        assertThat(result).isTrue()
    }

    // ── 2. SOFT_DELETE + IssueScope.Issue — true ────────────────────────────

    @Test
    fun `SOFT_DELETE 권한 + Issue scope 에서 hasPermission 은 true 를 반환한다`() {
        val resolver = AlwaysAllowIssuePermissionResolver()

        val result = resolver.hasPermission(actorId, IssuePermission.SOFT_DELETE, IssueScope.Issue("ATLAS-1"))

        assertThat(result).isTrue()
    }

    // ── 3. VIEW + IssueScope.Global — true ──────────────────────────────────

    @Test
    fun `VIEW 권한 + Global scope 에서 hasPermission 은 true 를 반환한다`() {
        val resolver = AlwaysAllowIssuePermissionResolver()

        val result = resolver.hasPermission(actorId, IssuePermission.VIEW, IssueScope.Global)

        assertThat(result).isTrue()
    }

    // ── 4. WARN 로그 발생 및 메시지 포맷 검증 ──────────────────────────────────

    @Test
    fun `hasPermission 호출 시 WARN 레벨 로그가 발생한다`() {
        val resolver = AlwaysAllowIssuePermissionResolver()

        val logger = LoggerFactory.getLogger(AlwaysAllowIssuePermissionResolver::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().also { it.start() }
        logger.addAppender(appender)

        try {
            resolver.hasPermission(actorId, IssuePermission.CREATE, IssueScope.Project("ATLAS"))

            assertThat(appender.list)
                .filteredOn { it.level == Level.WARN }
                .isNotEmpty()
        } finally {
            logger.detachAppender(appender)
        }
    }

    /**
     * WARN 로그 메시지에 PII 가 포함되지 않음을 검증한다.
     *
     * - actorId: UUID (PII 아님 — 식별자일 뿐, 이름/이메일 등 개인정보 아님)
     * - permission: enum 이름 (PII 아님)
     * - scope: sealed class 표현 (PII 아님)
     *
     * 로그에 이메일, 이름, 전화번호 등 실제 개인정보가 포함되지 않는지 확인한다.
     * 추가로 메시지에 "stub" 키워드와 "FR-AU-12" 가 포함되어 임시 구현임을 명시함을 검증한다.
     */
    @Test
    fun `WARN 로그 메시지는 stub 임을 명시하고 PII 를 포함하지 않는다`() {
        val resolver = AlwaysAllowIssuePermissionResolver()

        val logger = LoggerFactory.getLogger(AlwaysAllowIssuePermissionResolver::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().also { it.start() }
        logger.addAppender(appender)

        try {
            resolver.hasPermission(actorId, IssuePermission.SOFT_DELETE, IssueScope.Issue("ATLAS-1"))

            val warnLogs = appender.list.filter { it.level == Level.WARN }
            assertThat(warnLogs).isNotEmpty()

            val formattedMessage = warnLogs.first().formattedMessage
            // stub 임시 구현 표시가 있어야 한다
            assertThat(formattedMessage).contains("stub")
            assertThat(formattedMessage).contains("FR-AU-12")
            // permission 과 scope 가 로그에 포함돼야 한다 (enum/sealed — PII 없음)
            assertThat(formattedMessage).contains("SOFT_DELETE")
            assertThat(formattedMessage).contains("ATLAS-1")
            // 이메일, 이름 등 개인정보 패턴은 포함하지 않는다
            assertThat(formattedMessage).doesNotContainPattern("[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}")
        } finally {
            logger.detachAppender(appender)
        }
    }

    // ── 5. test profile: Bean 등록 확인 ─────────────────────────────────────

    @Test
    fun `test profile 에서 AlwaysAllowIssuePermissionResolver 는 IssuePermissionResolver 로 등록된다`() {
        contextRunner
            .withPropertyValues("spring.profiles.active=test")
            .run { ctx ->
                assertThat(ctx).hasSingleBean(IssuePermissionResolver::class.java)
                assertThat(ctx.getBean(IssuePermissionResolver::class.java))
                    .isInstanceOf(AlwaysAllowIssuePermissionResolver::class.java)
            }
    }

    // ── 6. prod profile: Bean 미등록 (운영 stub 차단) ────────────────────────

    /**
     * prod profile 활성화 시 `@Profile("!prod")` 조건으로 Bean 이 등록되지 않음을 검증한다.
     *
     * 운영 환경에서 stub 이 동작하면 모든 권한 검사를 우회하므로 (hasPermission 항상 true),
     * 이 테스트가 실패하는 것은 즉각적인 보안 위협을 의미한다.
     */
    @Test
    fun `prod profile 에서 AlwaysAllowIssuePermissionResolver 는 Bean 으로 등록되지 않는다`() {
        contextRunner
            .withPropertyValues("spring.profiles.active=prod")
            .run { ctx ->
                assertThat(ctx).doesNotHaveBean(AlwaysAllowIssuePermissionResolver::class.java)
                assertThat(ctx).doesNotHaveBean(IssuePermissionResolver::class.java)
            }
    }
}
