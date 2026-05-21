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
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.expression.spel.SpelEvaluationException
import org.springframework.expression.spel.SpelParseException
import java.util.concurrent.ExecutorService
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
 * 4. SimpleEvaluationContext 로 임의 클래스 메서드(T(System).exit) 호출이 차단된다.
 */
class SpelEvaluatorTest {

    private lateinit var executor: ExecutorService
    private lateinit var evaluator: SpelEvaluator

    private val sampleRoot = DefaultSpelRoot(
        issue = DefaultIssueView(
            key = "PROJ-1",
            priority = "HIGH",
            fields = mapOf("customField" to "value"),
        ),
        actor = DefaultActorView(
            userId = "user-42",
            roles = setOf("DEVELOPER"),
        ),
    )

    @BeforeEach
    fun setUp() {
        executor = mockk()
    }

    // ── Case 1. 유효한 표현식 평가 ──────────────────────────────────────────────

    @Test
    fun `유효한 SpEL 표현식이 Boolean true 로 평가된다`() {
        // SpelEvaluator 는 executor 를 사용해 표현식을 평가한다.
        // 실제 평가 로직이 Callable 안에 있으므로, Future 를 실행하고 결과를 반환하는 방식으로 테스트한다.
        val realExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
        val realEvaluator = SpelEvaluator(realExecutor)

        try {
            val result = realEvaluator.evaluate("issue.priority == 'HIGH'", sampleRoot)
            assertThat(result).isTrue()
        } finally {
            realExecutor.shutdown()
        }
    }

    @Test
    fun `유효한 SpEL 표현식이 Boolean false 로 평가된다`() {
        val realExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
        val realEvaluator = SpelEvaluator(realExecutor)

        try {
            val result = realEvaluator.evaluate("issue.priority == 'LOW'", sampleRoot)
            assertThat(result).isFalse()
        } finally {
            realExecutor.shutdown()
        }
    }

    // ── Case 2. 문법 오류 표현식 → 예외 ───────────────────────────────────────────

    @Test
    fun `문법 오류 표현식은 SpelParseException 을 던진다`() {
        val realExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
        val realEvaluator = SpelEvaluator(realExecutor)

        try {
            assertThatThrownBy {
                realEvaluator.evaluate("+++", sampleRoot)
            }.isInstanceOfAny(
                SpelParseException::class.java,
                SpelEvaluationException::class.java,
                IllegalArgumentException::class.java,
            )
        } finally {
            realExecutor.shutdown()
        }
    }

    // ── Case 3. 50ms timeout 초과 → WorkflowExpressionTimeoutException ─────────

    @Test
    fun `executor 가 TimeoutException 을 던지면 WorkflowExpressionTimeoutException 으로 변환된다`() {
        // mock executor — Future.get(50, MILLISECONDS) 호출 시 TimeoutException 을 던지도록 설정
        val mockFuture = mockk<Future<Boolean>>()
        every { executor.submit(any<java.util.concurrent.Callable<Boolean>>()) } returns mockFuture
        every { mockFuture.get(50L, TimeUnit.MILLISECONDS) } throws TimeoutException("simulated timeout")
        every { mockFuture.cancel(true) } returns true

        evaluator = SpelEvaluator(executor)

        assertThatThrownBy {
            evaluator.evaluate("issue.priority == 'HIGH'", sampleRoot)
        }.isInstanceOf(WorkflowExpressionTimeoutException::class.java)
            .satisfies({ ex ->
                val timeoutEx = ex as WorkflowExpressionTimeoutException
                assertThat(timeoutEx.expression).isEqualTo("issue.priority == 'HIGH'")
                assertThat(timeoutEx.timeoutMillis).isEqualTo(50L)
                assertThat(timeoutEx.cause).isInstanceOf(TimeoutException::class.java)
            })

        // timeout 후 Future.cancel(true) 호출 — 스레드 정리 확인
        verify(exactly = 1) { mockFuture.cancel(true) }
    }

    // ── Case 4. SimpleEvaluationContext — 임의 클래스 메서드 호출 차단 ───────────

    @Test
    fun `SimpleEvaluationContext 는 T(System) 타입 참조를 차단한다`() {
        // SimpleEvaluationContext.forReadOnlyDataBinding() 은 TypeLocator 를 비활성화한다.
        // T(System).exit(0) 같은 임의 Java 클래스 접근은 SpelEvaluationException 을 던진다.
        val realExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
        val realEvaluator = SpelEvaluator(realExecutor)

        try {
            assertThatThrownBy {
                realEvaluator.evaluate("T(java.lang.System).exit(0) == null", sampleRoot)
            }.isInstanceOfAny(
                SpelEvaluationException::class.java,
                SpelParseException::class.java,
                WorkflowExpressionTimeoutException::class.java,
            )
        } finally {
            realExecutor.shutdown()
        }
    }
}
