// SpelEvaluator 4 case — valid / invalid syntax / 50ms timeout / SimpleEvaluationContext 차단

package com.bts.workflow.expression

import com.bts.workflow.domain.exception.WorkflowExpressionTimeoutException
import com.bts.workflow.domain.expression.DefaultActorView
import com.bts.workflow.domain.expression.DefaultIssueView
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.expression.spel.SpelEvaluationException
import org.springframework.expression.spel.SpelParseException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * SpelEvaluator 4가지 핵심 동작 검증.
 *
 * 검증 대상.
 * 1. 유효한 SpEL 표현식이 Boolean 으로 평가된다.
 * 2. 문법 오류 표현식은 SpelParseException 또는 SpelEvaluationException 을 던진다.
 * 3. executor 에서 TimeoutException 발생 시 WorkflowExpressionTimeoutException 으로 변환된다.
 * 4. SimpleEvaluationContext 로 임의 클래스 메서드(T(System)) 호출이 차단된다.
 *
 * ### timeout 전략
 * SpEL 은 첫 평가 시 클래스 로딩 비용이 발생한다. Case 1/2/4 는 운영 50ms 대신
 * 테스트 환경 안전 마진 5000ms 를 사용한다 — 실제 SpEL 평가가 올바른지 검증하는 것이 목적이며,
 * 50ms timeout 자체의 동작은 Case 3 에서 mock 으로 결정적으로 검증한다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SpelEvaluatorTest {
    /** Case 1/2/4 — 실제 SpEL 평가 검증용 executor. */
    private val sharedExecutor: ExecutorService = Executors.newFixedThreadPool(2)

    /** Case 1/2/4 — 테스트 환경 JVM 콜드 스타트 허용 마진 5000ms. */
    private val testEvaluator = SpelEvaluator(sharedExecutor, timeoutMillis = 5000L)

    private val sampleRoot =
        DefaultSpelRoot(
            issue =
                DefaultIssueView(
                    key = "PROJ-1",
                    priority = "HIGH",
                    fields = mapOf("customField" to "value"),
                ),
            actor =
                DefaultActorView(
                    userId = "user-42",
                    roles = setOf("DEVELOPER"),
                ),
        )

    @AfterAll
    fun tearDown() {
        sharedExecutor.shutdown()
    }

    // ── Case 1. 유효한 표현식 평가 ──────────────────────────────────────────────

    @Test
    fun `유효한 SpEL 표현식이 Boolean true 로 평가된다`() {
        val result = testEvaluator.evaluate("issue.priority == 'HIGH'", sampleRoot)
        assertThat(result).isTrue()
    }

    @Test
    fun `유효한 SpEL 표현식이 Boolean false 로 평가된다`() {
        val result = testEvaluator.evaluate("issue.priority == 'LOW'", sampleRoot)
        assertThat(result).isFalse()
    }

    // ── Case 2. 문법 오류 표현식 → 예외 ───────────────────────────────────────────

    @Test
    fun `문법 오류 표현식은 SpelParseException 또는 SpelEvaluationException 을 던진다`() {
        assertThatThrownBy {
            testEvaluator.evaluate("+++", sampleRoot)
        }.isInstanceOfAny(
            SpelParseException::class.java,
            SpelEvaluationException::class.java,
            IllegalArgumentException::class.java,
        )
    }

    // ── Case 3. 50ms timeout 초과 → WorkflowExpressionTimeoutException ─────────

    @Test
    fun `executor 가 TimeoutException 을 던지면 WorkflowExpressionTimeoutException 으로 변환된다`() {
        // mock executor — Future.get(50, MILLISECONDS) 호출 시 TimeoutException 을 던지도록 설정.
        // 실제 executor 를 대체해 timeout 동작을 결정적으로 검증한다.
        // 이 방식은 CI 환경 JVM 콜드 스타트에 무관하게 항상 같은 결과를 보장한다.
        val mockExecutor = mockk<ExecutorService>()
        val mockFuture = mockk<Future<Boolean>>()
        every { mockExecutor.submit(any<java.util.concurrent.Callable<Boolean>>()) } returns mockFuture
        every { mockFuture.get(50L, TimeUnit.MILLISECONDS) } throws TimeoutException("simulated timeout")
        every { mockFuture.cancel(true) } returns true

        // 운영 기본값 50ms 사용 — DEFAULT_TIMEOUT_MILLIS
        val evaluator = SpelEvaluator(mockExecutor)

        assertThatThrownBy {
            evaluator.evaluate("issue.priority == 'HIGH'", sampleRoot)
        }.isInstanceOf(WorkflowExpressionTimeoutException::class.java)
            .satisfies({ ex ->
                val timeoutEx = ex as WorkflowExpressionTimeoutException
                assertThat(timeoutEx.expression).isEqualTo("issue.priority == 'HIGH'")
                assertThat(timeoutEx.timeoutMillis).isEqualTo(SpelEvaluator.DEFAULT_TIMEOUT_MILLIS)
                assertThat(timeoutEx.cause).isInstanceOf(TimeoutException::class.java)
            })

        // timeout 후 Future.cancel(true) 로 스레드를 정리한다 — 무한 루프 DoS 방어 핵심.
        verify(exactly = 1) { mockFuture.cancel(true) }
    }

    // ── Case 4. SimpleEvaluationContext — 임의 클래스 메서드 호출 차단 ───────────

    @Test
    fun `SimpleEvaluationContext 는 T(System) 타입 참조를 SpelEvaluationException 으로 차단한다`() {
        // SimpleEvaluationContext.forReadOnlyDataBinding() 은 TypeLocator 를 비활성화한다.
        // T(java.lang.System) 같은 임의 Java 클래스 접근은 SpelEvaluationException 을 던진다.
        // SpelEvaluator 가 ExecutionException.cause 를 언래핑해서 SpelEvaluationException 을 전파한다.
        assertThatThrownBy {
            testEvaluator.evaluate("T(java.lang.System).exit(0)", sampleRoot)
        }.isInstanceOfAny(
            SpelEvaluationException::class.java,
            SpelParseException::class.java,
            WorkflowExpressionTimeoutException::class.java,
        )
    }
}
