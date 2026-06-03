// WorkflowEngine 관련 Bean 설정 — SpelEvaluator, ExecutorService @Bean 등록

package com.bts.workflow.engine

import com.bts.workflow.expression.SpelEvaluator
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * WorkflowEngine 관련 Spring Bean 설정.
 *
 * [SpelEvaluator] 는 [ExecutorService] 를 받아 50ms timeout 안에 SpEL 표현식을 평가한다.
 * 두 Bean 을 이 Configuration 에서 일괄 선언해 배선 실수를 방지한다.
 *
 * ## prod 프로파일 주의
 * prod 프로파일은 [com.bts.workflow.port.outbound.PermissionResolver] 빈 부재
 * (AlwaysAllowPermissionResolver 는 @Profile("!prod") 전용).
 * FR-PM-04 에서 IdentityAccessPermissionResolver(@Profile("prod")) 구현 필요.
 * 현 단계 검증은 test-assembled(!prod) 환경 기준이다.
 *
 * ## ExecutorService shutdown
 * [spelExecutorService] 는 `destroyMethod = "shutdown"` 으로 선언하므로
 * Spring 컨텍스트 종료 시 스레드 풀이 정상 종료된다.
 */
@Configuration(proxyBeanMethods = false)
class WorkflowEngineConfig {
    /**
     * [SpelEvaluator] 의 표현식 평가 스레드 풀.
     *
     * `newCachedThreadPool` 을 사용한다. SpEL 평가 요청은 트랜잭션 안에서 짧게 실행되며
     * 50ms timeout 이후 Future.cancel 로 즉시 정리되므로 스레드 누수 위험이 낮다.
     * 동시 전이 요청이 많을 때도 요청당 스레드를 할당해 지연 없이 처리한다.
     *
     * @return shutdown 시 `ExecutorService.shutdown()` 이 호출되는 캐시 스레드 풀.
     */
    @Bean(destroyMethod = "shutdown")
    fun spelExecutorService(): ExecutorService = Executors.newCachedThreadPool()

    /**
     * SpEL 워크플로우 조건 표현식 평가기.
     *
     * [spelExecutorService] 를 주입받아 50ms timeout + SimpleEvaluationContext sandbox 로
     * 표현식을 안전하게 평가한다.
     *
     * SpelEvaluator 생성자 시그니처: `SpelEvaluator(executor: ExecutorService, timeoutMillis: Long = 50L)`.
     * timeoutMillis 는 기본값 50ms 를 사용한다.
     *
     * @param executor 표현식 평가를 실행할 [ExecutorService]. [spelExecutorService] 주입.
     * @return [SpelEvaluator] 인스턴스.
     */
    @Bean
    fun spelEvaluator(executor: ExecutorService): SpelEvaluator = SpelEvaluator(executor = executor)
}
