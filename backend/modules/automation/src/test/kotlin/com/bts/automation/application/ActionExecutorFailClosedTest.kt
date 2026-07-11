// ActionExecutor fail-closed 부팅 검증 — IssueMutationPort 빈 부재 시 컨텍스트 부팅이 실패해야 한다 (FR-AT-02 C4)

package com.bts.automation.application

import com.bts.automation.adapter.AutomationActionRepository
import com.bts.automation.adapter.WebhookActionClient
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.springframework.beans.factory.NoSuchBeanDefinitionException
import org.springframework.beans.factory.UnsatisfiedDependencyException
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import java.util.function.Supplier

/**
 * [ActionExecutor] fail-closed 부팅 계약 검증 (FR-AT-02 코드리뷰 C4).
 *
 * cross-BC 포트 [com.bts.shared.issue.IssueMutationPort] 는 prod 어댑터(issue-tracking
 * `@Profile("prod")` 구현)가 조립되지 않으면 컨텍스트 부팅이 실패해야 한다 — silent no-op drop 없이
 * 시끄럽게 부팅을 막는 것이 fail-closed 다([IssueMutationPort] KDoc "fail-closed — default 구현 없음").
 *
 * 이 테스트는 [ActionExecutor] 의 나머지 협력자는 모두 등록하되 [IssueMutationPort] 빈만 일부러
 * 빼고 실제 Spring 컨텍스트를 refresh 해, 생성자 주입이 [NoSuchBeanDefinitionException] 을 근본
 * 원인으로 부팅에 실패함을 검증한다. 만약 누군가 포트 주입을 nullable(`IssueMutationPort?`)로 바꿔
 * fail-open 으로 되돌리면([[crossbc-resolver-nullable-fail-open]] 트랩), Spring 이 null 을 주입해
 * 부팅이 오히려 **성공**하므로 이 테스트가 실패로 회귀를 잡는다. (앞선 리뷰 관찰 2 — 리플렉션
 * 프록시에서 실 부팅 검증으로 승격.)
 *
 * prod 전체 조립 자체는 별도 후속(전역 조립 트랙, ADR C4)이며, 이 테스트는 그 조립이 도착했을 때
 * fail-closed 계약이 유지됨을 보장한다.
 */
class ActionExecutorFailClosedTest : StringSpec({
    "IssueMutationPort 빈이 없으면 ActionExecutor 배선이 부팅 시 NoSuchBeanDefinitionException 으로 실패한다(fail-closed)" {
        val ctx = AnnotationConfigApplicationContext()
        // ActionExecutor 의 나머지 협력자는 등록하되, IssueMutationPort 는 일부러 등록하지 않는다.
        ctx.registerBean(WebhookActionClient::class.java, Supplier { mockk<WebhookActionClient>() })
        ctx.registerBean(AutomationActionRepository::class.java, Supplier { mockk<AutomationActionRepository>() })
        ctx.registerBean(ObjectMapper::class.java, Supplier { ObjectMapper() })
        ctx.registerBean(TemplateRenderer::class.java, Supplier { TemplateRenderer })
        ctx.register(ActionExecutor::class.java)

        val ex = shouldThrow<UnsatisfiedDependencyException> { ctx.refresh() }

        // 근본 원인이 IssueMutationPort 빈 부재여야 한다 — 다른 협력자는 모두 등록했으므로 유일한 미충족.
        var cause: Throwable? = ex
        while (cause != null && cause !is NoSuchBeanDefinitionException) {
            cause = cause.cause
        }
        (cause is NoSuchBeanDefinitionException) shouldBe true

        ctx.close()
    }
})
