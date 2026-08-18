// Slack unfurl/slash 명령 @Async 처리를 위한 경계 executor + @EnableAsync 배선 (FR-SL-03 Task 9 / FR-SL-04 Task 7)

package com.bts.slack.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy

/** `@Value` 기본값은 컴파일 타임 상수여야 하므로 top-level const로 둔다([com.bts.slack.config.DEFAULT_SLACK_SCOPES] 동일 관례). */
private const val DEFAULT_UNFURL_CORE_POOL_SIZE = 2
private const val DEFAULT_UNFURL_MAX_POOL_SIZE = 4
private const val DEFAULT_UNFURL_QUEUE_CAPACITY = 100

/** slash 명령 전용 executor 기본값(FR-SL-04 Task 7) — unfurl과 동일 골격, 별도 프로퍼티 네임스페이스. */
private const val DEFAULT_COMMAND_CORE_POOL_SIZE = 2
private const val DEFAULT_COMMAND_MAX_POOL_SIZE = 4
private const val DEFAULT_COMMAND_QUEUE_CAPACITY = 100

/**
 * Slack unfurl 처리(`@Async` 카드 렌더 + `chat.unfurl` 호출, FR-SL-03 ADR D4)와 `/atlas` slash 명령 처리
 * (FR-SL-04 Task 7)를 위한 `@Async` executor 설정. 두 기능이 서로 다른 경계 스레드풀
 * ([SLACK_UNFURL_EXECUTOR_BEAN_NAME]/[SLACK_COMMAND_EXECUTOR_BEAN_NAME])을 쓰도록 이 클래스가
 * `@EnableAsync` 진입점을 한 곳에 모은다.
 *
 * ## 왜 `@Async` 인가 (3초 룰)
 * Slack Events API/slash 명령 모두 3초 내 200 ack가 없으면 요청을 재전송한다. 컨트롤러는 서명 검증 후
 * 즉시 200을 반환하고, 이슈 조회·카드 렌더·외부 API 호출처럼 3초를 넘길 수 있는 작업은 이 executor들로
 * 넘긴다.
 *
 * ## 왜 경계(bounded) executor 인가 — [org.springframework.core.task.SimpleAsyncTaskExecutor] 금지
 * Spring 기본 `SimpleAsyncTaskExecutor`는 요청마다 새 스레드를 무제한으로 만든다. Slack 채널에 링크가
 * 몰리거나 slash 명령이 몰리면 스레드가 무한정 늘어나 OOM으로 이어질 수 있다. 각 executor는
 * core/max/queue를 명시해 동시 처리량에 상한을 둔다 — 상한을 넘는 요청은 큐에서 대기하다가, 큐도 가득 차면
 * 호출 스레드가 직접 처리한다([java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy], 유실 대신 배압).
 *
 * ## 왜 unfurl과 slash가 executor를 공유하지 않는가
 * [SLACK_UNFURL_EXECUTOR_BEAN_NAME]은 이름이 unfurl 전용이라 slash에 재사용하면 의미가 어긋나고,
 * unfurl 트래픽 폭주가 slash 응답 지연으로 전환(noisy neighbor)되는 것도 막는다.
 *
 * ## 왜 이 클래스가 [SlackUnfurlService][com.bts.slack.unfurl.SlackUnfurlService]/
 * [SlashCommandService][com.bts.slack.command.SlashCommandService]와 분리되어 있는가
 * `@EnableAsync`는 별도 설정 클래스에 두는 것이 관례다 — 서비스 클래스에 두면 self-invocation 시
 * 프록시를 타지 않아 `@Async`가 무력화되는 사고와 섞여 헷갈리기 쉽다([SlackSchedulingConfiguration]과
 * 동일하게 진입점을 분리한다).
 */
@Configuration(proxyBeanMethods = false)
@EnableAsync
class SlackAsyncConfig(
    @param:Value("\${bts.slack.unfurl-executor.core-pool-size:$DEFAULT_UNFURL_CORE_POOL_SIZE}")
    private val unfurlCorePoolSize: Int,
    @param:Value("\${bts.slack.unfurl-executor.max-pool-size:$DEFAULT_UNFURL_MAX_POOL_SIZE}")
    private val unfurlMaxPoolSize: Int,
    @param:Value("\${bts.slack.unfurl-executor.queue-capacity:$DEFAULT_UNFURL_QUEUE_CAPACITY}")
    private val unfurlQueueCapacity: Int,
    @param:Value("\${bts.slack.command-executor.core-pool-size:$DEFAULT_COMMAND_CORE_POOL_SIZE}")
    private val commandCorePoolSize: Int,
    @param:Value("\${bts.slack.command-executor.max-pool-size:$DEFAULT_COMMAND_MAX_POOL_SIZE}")
    private val commandMaxPoolSize: Int,
    @param:Value("\${bts.slack.command-executor.queue-capacity:$DEFAULT_COMMAND_QUEUE_CAPACITY}")
    private val commandQueueCapacity: Int,
) {
    /**
     * Slack unfurl 처리 전용 경계 스레드풀. 다른 `@Async` 작업과 스레드 자원을 공유하지 않도록
     * [SLACK_UNFURL_EXECUTOR_BEAN_NAME]으로 명시해, `@Async("slackUnfurlExecutor")`가 이 빈만 사용하게 한다.
     * core/max/queue는 `bts.slack.unfurl-executor.*` 프로퍼티로 환경별 조정 가능(미설정 시 안전한 기본값).
     */
    @Bean(SLACK_UNFURL_EXECUTOR_BEAN_NAME)
    fun slackUnfurlExecutor(): ThreadPoolTaskExecutor =
        ThreadPoolTaskExecutor().apply {
            corePoolSize = unfurlCorePoolSize
            maxPoolSize = unfurlMaxPoolSize
            queueCapacity = unfurlQueueCapacity
            setThreadNamePrefix(THREAD_NAME_PREFIX)
            setRejectedExecutionHandler(CallerRunsPolicy())
            initialize()
        }

    /**
     * `/atlas` slash 명령 처리(파싱→매핑 해석→핸들러 실행→`response_url` 전송) 전용 경계 스레드풀
     * (FR-SL-04 Task 7).
     *
     * [slackUnfurlExecutor]와 스레드 자원을 공유하지 않는다 — 이름이 unfurl 전용이라 slash에
     * 재사용하면 의미가 어긋나고, unfurl 트래픽 폭주가 slash 응답 지연으로 전환되는 것도 막는다.
     * 골격(경계 pool + [CallerRunsPolicy] 배압)은 [slackUnfurlExecutor]와 동일하며, core/max/queue는
     * `bts.slack.command-executor.*` 프로퍼티로 별도 조정 가능하다(미설정 시 안전한 기본값).
     */
    @Bean(SLACK_COMMAND_EXECUTOR_BEAN_NAME)
    fun slackCommandExecutor(): ThreadPoolTaskExecutor =
        ThreadPoolTaskExecutor().apply {
            corePoolSize = commandCorePoolSize
            maxPoolSize = commandMaxPoolSize
            queueCapacity = commandQueueCapacity
            setThreadNamePrefix(COMMAND_THREAD_NAME_PREFIX)
            setRejectedExecutionHandler(CallerRunsPolicy())
            initialize()
        }

    companion object {
        /** `@Async("slackUnfurlExecutor")`가 참조하는 빈 이름. */
        const val SLACK_UNFURL_EXECUTOR_BEAN_NAME = "slackUnfurlExecutor"

        /** `@Async("slackCommandExecutor")`가 참조하는 빈 이름(FR-SL-04 Task 7). */
        const val SLACK_COMMAND_EXECUTOR_BEAN_NAME = "slackCommandExecutor"

        private const val THREAD_NAME_PREFIX = "slack-unfurl-"
        private const val COMMAND_THREAD_NAME_PREFIX = "slack-command-"
    }
}
