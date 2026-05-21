// SpEL 평가기 — SimpleEvaluationContext + Future timeout 50ms (sandbox 3중 방어)

package com.bts.workflow.expression

import com.bts.workflow.domain.exception.WorkflowExpressionTimeoutException
import com.bts.workflow.domain.expression.ActorView
import com.bts.workflow.domain.expression.DefaultActorView
import com.bts.workflow.domain.expression.DefaultIssueView
import com.bts.workflow.domain.expression.IssueView
import org.springframework.expression.spel.standard.SpelExpressionParser
import org.springframework.expression.spel.support.SimpleEvaluationContext
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * 워크플로우 전이 조건 표현식을 평가하는 SpEL(Spring Expression Language) 평가기.
 *
 * ### 보안 모델 (sandbox 3중 방어)
 *
 * 1. **SimpleEvaluationContext** — `StandardEvaluationContext` 대신 제한 모드를 사용한다.
 *    임의 Java 클래스 접근(`T(...)` 타입 참조), 리플렉션(실행 중 클래스 구조 탐색),
 *    bean 참조를 모두 차단한다. getter 를 통한 property 읽기만 허용한다.
 *
 * 2. **SpelRoot (getter-only sealed interface)** — 표현식이 접근할 수 있는 루트 객체를
 *    `IssueView` + `ActorView` 두 개의 getter-only data class 로 제한한다.
 *    action 메서드가 0개이므로 `SimpleEvaluationContext` 와 함께 사용해도
 *    side-effect 가 발생하지 않는다.
 *
 * 3. **50ms timeout** — `ExecutorService + Future.get(50, MILLISECONDS)` 패턴으로
 *    무한 루프 또는 DoS(서비스 거부) 형태의 악의적 표현식을 50ms 안에 강제 중단한다.
 *    타임아웃 발생 시 `Future.cancel(true)` 로 스레드를 정리하고
 *    [WorkflowExpressionTimeoutException] 으로 변환해 던진다.
 *
 * ### 사용자 입력 표현식 직접 평가 금지
 * 표현식은 반드시 워크플로우 정의 YAML 또는 seed 데이터에서만 로드해야 한다.
 * 관리자(Admin) 만 편집할 수 있는 소스 경로에서만 표현식이 유입되어야 한다.
 * 일반 사용자가 API 를 통해 임의 표현식을 전달하는 경로를 절대로 만들지 않는다.
 *
 * @param executor 표현식 평가를 실행할 [ExecutorService].
 *   Spring `@Configuration` 에서 `ThreadPoolExecutor(corePool=2)` 로 주입한다.
 *   테스트에서는 mock 으로 교체해 timeout 동작을 결정적으로 검증한다.
 */
class SpelEvaluator(private val executor: ExecutorService) {

    private val parser = SpelExpressionParser()

    /**
     * SpEL 표현식을 [root] 컨텍스트에서 평가해 Boolean 결과를 반환한다.
     *
     * @param expression 평가할 SpEL 표현식 문자열. 예: `"issue.priority == 'HIGH' and actor.roles.contains('DEVELOPER')"`.
     * @param root 표현식이 참조할 루트 컨텍스트 객체.
     * @return 표현식 평가 결과. 평가 결과가 null 이면 false 로 처리한다.
     * @throws org.springframework.expression.spel.SpelParseException 표현식 문법 오류 시.
     * @throws org.springframework.expression.spel.SpelEvaluationException 평가 중 오류 시.
     * @throws WorkflowExpressionTimeoutException 평가가 50ms 를 초과한 경우.
     */
    fun evaluate(expression: String, root: SpelRoot): Boolean {
        val context = SimpleEvaluationContext
            .forReadOnlyDataBinding()
            .build()

        // RootAdapter: SpelRoot 의 issue/actor 두 객체를 단일 루트로 노출하는 어댑터.
        // SpEL 은 루트 객체가 하나여야 하므로, issue.* 와 actor.* 모두 접근할 수 있도록
        // SpelRootAdapter 를 루트로 설정한다.
        val rootAdapter = SpelRootAdapter(root)

        val parsed = parser.parseExpression(expression)

        val future = executor.submit<Boolean> {
            parsed.getValue(context, rootAdapter, Boolean::class.java) ?: false
        }

        return try {
            future.get(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            future.cancel(true)
            throw WorkflowExpressionTimeoutException(
                expression = expression,
                timeoutMillis = TIMEOUT_MILLIS,
                cause = e,
            )
        }
    }

    companion object {
        /** 표현식 평가 최대 허용 시간 (밀리초). DoS 차단을 위해 50ms 로 고정한다. */
        const val TIMEOUT_MILLIS = 50L
    }
}

/**
 * SpEL 평가 루트 컨텍스트를 정의하는 sealed interface.
 *
 * SpEL 표현식에서 `issue.priority`, `actor.roles` 처럼 두 도메인 객체에 동시 접근하려면
 * 단일 루트 객체가 두 객체를 모두 담고 있어야 한다.
 *
 * `sealed interface` 는 Kotlin 에서 "이 인터페이스의 구현체는 이 파일(모듈) 안에만 존재한다" 는
 * 제약을 컴파일러가 강제한다. 외부에서 임의 구현체를 주입할 수 없다.
 */
sealed interface SpelRoot {
    /** 표현식에서 `issue.*` 로 접근할 이슈 뷰. getter-only, action 메서드 0개. */
    val issue: IssueView

    /** 표현식에서 `actor.*` 로 접근할 액터 뷰. getter-only, action 메서드 0개. */
    val actor: ActorView
}

/**
 * [SpelRoot] 의 기본 구현체.
 *
 * data class 로 선언해 equals/hashCode/toString/copy 를 컴파일러가 자동 생성한다.
 * 사용자 정의 메서드를 추가하지 않아 SpEL surface 를 getter-only 로 유지한다.
 *
 * @param issue 이슈 뷰 인스턴스.
 * @param actor 액터 뷰 인스턴스.
 */
data class DefaultSpelRoot(
    override val issue: IssueView,
    override val actor: ActorView,
) : SpelRoot

/**
 * [SpelRoot] 를 SpEL 루트 객체로 노출하기 위한 어댑터.
 *
 * SpEL 에서 `issue.priority` 처럼 표현식을 쓰려면 루트 객체에 `getIssue()` 또는 `issue` property 가
 * 있어야 한다. [SpelRoot] 가 이미 `issue` / `actor` property 를 가지므로
 * [SpelRoot] 를 직접 어댑터로 사용하면 된다.
 *
 * 단, SimpleEvaluationContext 의 DataBinding 모드는 루트 객체의 property 를
 * getter 를 통해 읽으므로, sealed interface 보다 구체 data class 를 루트로 전달해야
 * Kotlin synthetic property getter 를 올바르게 인식한다.
 * 이 클래스는 [SpelRoot] 의 property 를 공개 getter 로 위임한다.
 */
internal class SpelRootAdapter(private val root: SpelRoot) {
    /** SpEL 에서 `issue.*` 로 접근하는 이슈 뷰. */
    val issue: IssueView get() = root.issue

    /** SpEL 에서 `actor.*` 로 접근하는 액터 뷰. */
    val actor: ActorView get() = root.actor
}
