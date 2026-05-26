// AlwaysAllowWorkflowSchemePermissionResolver 단위 테스트 — Bean 프로파일 격리 + WARN 로그 발생 검증

package com.bts.workflow.scheme.adapter.outbound

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.bts.workflow.port.outbound.ActorId
import com.bts.workflow.scheme.port.outbound.WorkflowSchemePermission
import com.bts.workflow.scheme.port.outbound.WorkflowSchemePermissionResolver
import com.bts.workflow.scheme.port.outbound.WorkflowSchemeScope
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.boot.test.context.runner.ApplicationContextRunner

/**
 * [AlwaysAllowWorkflowSchemePermissionResolver] 단위 테스트.
 *
 * 검증 항목.
 * 1. MANAGE_SCHEME + WorkflowSchemeScope.Global — requirePermission 이 예외를 던지지 않는다.
 * 2. ASSIGN_SCHEME + WorkflowSchemeScope.Project — requirePermission 이 예외를 던지지 않는다.
 * 3. 모든 호출에서 WARN 레벨 로그가 발생한다.
 * 4. WARN 로그 메시지에 stub 임시 구현 표시와 FR-PM-04 참조가 포함된다.
 * 5. WARN 로그 메시지에 PII 가 포함되지 않는다.
 * 6. test profile 에서 Bean 이 [WorkflowSchemePermissionResolver] 로 등록된다.
 * 7. prod profile 에서 Bean 이 등록되지 않는다 (stub 운영 노출 차단).
 */
class AlwaysAllowWorkflowSchemePermissionResolverTest {
    private val actor = ActorId("user-uuid-1234")

    // ── ApplicationContextRunner 픽스처 ──────────────────────────────────────
    private val contextRunner =
        ApplicationContextRunner()
            .withUserConfiguration(AlwaysAllowWorkflowSchemePermissionResolver::class.java)

    // ── 1. MANAGE_SCHEME + Global — 예외 없이 통과 ───────────────────────────

    @Test
    fun `MANAGE_SCHEME 권한 + Global scope 에서 requirePermission 은 예외를 던지지 않는다`() {
        val resolver = AlwaysAllowWorkflowSchemePermissionResolver()

        resolver.requirePermission(actor, WorkflowSchemePermission.MANAGE_SCHEME, WorkflowSchemeScope.Global)
        // 예외가 없으면 통과
    }

    // ── 2. ASSIGN_SCHEME + Project — 예외 없이 통과 ──────────────────────────

    @Test
    fun `ASSIGN_SCHEME 권한 + Project scope 에서 requirePermission 은 예외를 던지지 않는다`() {
        val resolver = AlwaysAllowWorkflowSchemePermissionResolver()

        resolver.requirePermission(actor, WorkflowSchemePermission.ASSIGN_SCHEME, WorkflowSchemeScope.Project("ATLAS"))
        // 예외가 없으면 통과
    }

    // ── 3. WARN 로그 발생 검증 ────────────────────────────────────────────────

    @Test
    fun `requirePermission 호출 시 WARN 레벨 로그가 발생한다`() {
        val resolver = AlwaysAllowWorkflowSchemePermissionResolver()

        val logger = LoggerFactory.getLogger(AlwaysAllowWorkflowSchemePermissionResolver::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().also { it.start() }
        logger.addAppender(appender)

        try {
            resolver.requirePermission(actor, WorkflowSchemePermission.MANAGE_SCHEME, WorkflowSchemeScope.Global)

            assertThat(appender.list)
                .filteredOn { it.level == Level.WARN }
                .isNotEmpty()
        } finally {
            logger.detachAppender(appender)
        }
    }

    // ── 4. WARN 로그 메시지 포맷 검증 — stub + FR-PM-04 포함 ─────────────────

    /**
     * WARN 로그 메시지에 stub 임시 구현 표시와 FR-PM-04 참조가 있음을 검증한다.
     *
     * - "stub" 키워드: 임시 구현임을 명시
     * - "FR-PM-04": 정식 RBAC 교체 대상 기능 식별자
     */
    @Test
    fun `WARN 로그 메시지는 stub 임을 명시하고 FR-PM-04 를 참조한다`() {
        val resolver = AlwaysAllowWorkflowSchemePermissionResolver()

        val logger = LoggerFactory.getLogger(AlwaysAllowWorkflowSchemePermissionResolver::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().also { it.start() }
        logger.addAppender(appender)

        try {
            resolver.requirePermission(actor, WorkflowSchemePermission.ASSIGN_SCHEME, WorkflowSchemeScope.Project("ATLAS"))

            val warnLogs = appender.list.filter { it.level == Level.WARN }
            assertThat(warnLogs).isNotEmpty()

            val formattedMessage = warnLogs.first().formattedMessage
            assertThat(formattedMessage).contains("stub")
            assertThat(formattedMessage).contains("FR-PM-04")
            // permission 과 scope 가 로그에 포함돼야 한다 (enum/sealed — PII 없음)
            assertThat(formattedMessage).contains("ASSIGN_SCHEME")
            assertThat(formattedMessage).contains("ATLAS")
        } finally {
            logger.detachAppender(appender)
        }
    }

    // ── 5. PII 미포함 검증 ────────────────────────────────────────────────────

    /**
     * WARN 로그 메시지에 PII 가 포함되지 않음을 검증한다.
     *
     * - actorId: raw 식별자 문자열 (PII 아님 — 서비스 내부 UUID/ID 값)
     * - permission: enum 이름 (PII 아님)
     * - scope: sealed class 표현 (PII 아님)
     *
     * 이메일, 이름, 전화번호 등 실제 개인정보가 포함되지 않는지 확인한다.
     */
    @Test
    fun `WARN 로그 메시지는 PII 를 포함하지 않는다`() {
        val resolver = AlwaysAllowWorkflowSchemePermissionResolver()

        val logger = LoggerFactory.getLogger(AlwaysAllowWorkflowSchemePermissionResolver::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().also { it.start() }
        logger.addAppender(appender)

        try {
            resolver.requirePermission(actor, WorkflowSchemePermission.MANAGE_SCHEME, WorkflowSchemeScope.Global)

            val warnLogs = appender.list.filter { it.level == Level.WARN }
            assertThat(warnLogs).isNotEmpty()

            val formattedMessage = warnLogs.first().formattedMessage
            // 이메일 패턴이 포함되지 않아야 한다
            assertThat(formattedMessage)
                .doesNotContainPattern("[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}")
        } finally {
            logger.detachAppender(appender)
        }
    }

    // ── 6. test profile: Bean 등록 확인 ─────────────────────────────────────

    @Test
    fun `test profile 에서 AlwaysAllowWorkflowSchemePermissionResolver 는 WorkflowSchemePermissionResolver 로 등록된다`() {
        contextRunner
            .withPropertyValues("spring.profiles.active=test")
            .run { ctx ->
                assertThat(ctx).hasSingleBean(WorkflowSchemePermissionResolver::class.java)
                assertThat(ctx.getBean(WorkflowSchemePermissionResolver::class.java))
                    .isInstanceOf(AlwaysAllowWorkflowSchemePermissionResolver::class.java)
            }
    }

    // ── 7. prod profile: Bean 미등록 (운영 stub 차단) ────────────────────────

    /**
     * prod profile 활성화 시 `@Profile("!prod")` 조건으로 Bean 이 등록되지 않음을 검증한다.
     *
     * 운영 환경에서 stub 이 동작하면 스킴 권한 검사를 완전히 우회하므로
     * 이 테스트가 실패하는 것은 즉각적인 보안 위협을 의미한다.
     */
    @Test
    fun `prod profile 에서 AlwaysAllowWorkflowSchemePermissionResolver 는 Bean 으로 등록되지 않는다`() {
        contextRunner
            .withPropertyValues("spring.profiles.active=prod")
            .run { ctx ->
                assertThat(ctx).doesNotHaveBean(AlwaysAllowWorkflowSchemePermissionResolver::class.java)
                assertThat(ctx).doesNotHaveBean(WorkflowSchemePermissionResolver::class.java)
            }
    }
}
