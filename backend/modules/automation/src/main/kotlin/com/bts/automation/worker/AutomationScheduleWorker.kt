// automation_rules 의 SCHEDULED cron 트리거를 폴링해 발화 시각 도달 룰을 실행 큐로 넘기는 워커 (FR-AT-01 Task 8)

package com.bts.automation.worker

import com.bts.automation.adapter.AutomationExecutionEnqueuer
import com.bts.automation.adapter.AutomationRuleRepository
import com.bts.automation.domain.AutomationRule
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.scheduling.support.CronExpression
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * SCHEDULED 트리거 cron 폴링 워커 (FR-AT-01 Task 8, 스펙 S6/FR5/EC5, ADR D4).
 *
 * [AutomationRuleRepository.findScheduledDue] 로 발화 시각(`next_fire_at`)이 지난 SCHEDULED·enabled 룰을
 * 조회해 [AutomationExecutionEnqueuer] 로 `q_automation_execution` 에 적재하고, cron 기준 다음 발화
 * 시각으로 `next_fire_at` 을 갱신한다.
 *
 * ## nextFireAt 최초 초기화는 이 워커의 책임이 아니다
 * [AutomationRuleRepository.findScheduledDue] 의 WHERE 절이 `next_fire_at IS NOT NULL` 을 요구하므로,
 * 아직 한 번도 발화 시각이 계산되지 않은(`nextFireAt == null`) 신규 SCHEDULED 룰은 애초에 이 워커의
 * 조회 대상에 들어오지 않는다. 룰 생성/활성화 시점의 최초 `nextFireAt` 계산은 서비스 계층(Task 6
 * `AutomationRuleService`) 책임이며, 이 워커는 이미 `nextFireAt` 이 설정된 룰의 **후속** 발화만 다룬다.
 *
 * ## 중복 억제(EC5) — 갱신할 next_fire_at 은 항상 폴링 시각(now) 기준
 * 다음 발화 시각은 `rule.nextFireAt` 이 아니라 **폴링 시각(now)** 을 기준으로 cron 의 다음 occurrence 를
 * 계산한다. 따라서 워커가 오래 멈춰 있다가 재기동해 한 룰의 cron 주기가 여러 번 지났더라도, 이번
 * 폴링에서 정확히 1회만 발화하고 `next_fire_at` 은 곧바로 미래(now 이후 최초 occurrence)로 갱신된다 —
 * 지난 주기를 하나씩 소급 발화(catch-up)하지 않는다.
 *
 * ## 처리 순서 — enqueue 먼저, next_fire_at 갱신은 그 다음
 * 두 단계 사이에 실패하면(예: DB 순단) 이 룰은 여전히 "발화 대상"으로 남아 다음 폴링에서 재시도된다.
 * 반대 순서였다면 enqueue 실패 시 발화가 영구히 유실될 수 있다 — 유실보다 중복을 택한다
 * (NFR5, at-least-once·중복 허용은 FR-AT-02 액션의 멱등 책임).
 *
 * ## 결함격리 — 룰 단위 try/catch
 * 한 룰 처리 중 예외(cron 파싱 실패·enqueue 실패 등)가 나도 로그만 남기고 다음 룰을 계속 처리한다
 * (`IssueDueDateScanWorker` 동형 — 한 폴링에서 여러 룰이 함께 조회되므로 한 룰의 실패가 나머지를
 * 막으면 안 된다).
 *
 * ## `@Transactional` 없음 — 의도적 설계
 * [pollAndFire] 는 이미 각자 `@Transactional` 인 [AutomationRuleRepository]/[AutomationExecutionEnqueuer]
 * 메서드들을 오케스트레이션만 한다. 워커 레벨에서 트랜잭션을 열면 여러 룰 처리가 한 트랜잭션으로
 * 묶여 한 룰의 실패가 나머지 룰의 커밋까지 롤백시킨다(`IssueDueDateScanWorker`/`WebhookDispatchWorker`
 * 동형 결정).
 *
 * ## Clock 주입
 * `now` 는 주입된 [Clock] 에서 얻는다. 기본값 [Clock.systemUTC] — 별도 Clock 빈이 없는 컨텍스트에서도
 * 부팅되며, 테스트에서 [Clock.fixed] 로 교체해 결정적으로 검증한다(agile-planning
 * `SprintBurndownService` 동일 Clock 기본값 관례).
 *
 * ## cron 평가 타임존 — UTC(스펙 G3)
 * `trigger_config.cron` 은 항상 UTC 로 평가한다. 프로젝트-로컬 타임존은 후속 범위.
 *
 * @param repository SCHEDULED 룰 조회 + `next_fire_at` no-bump 갱신.
 * @param enqueuer 발화 결과를 `q_automation_execution` 에 적재.
 * @param objectMapper triggerConfig 파싱 + SCHEDULED 의 빈 triggerEvent(`{}`) 생성(ADR D4 §G6 — SCHEDULED
 *   는 사용자 액터/원천 이벤트가 없으므로 빈 객체를 전달한다).
 * @param clock 폴링 기준 시각(now) 소스. 기본값 UTC.
 */
@Component
class AutomationScheduleWorker(
    private val repository: AutomationRuleRepository,
    private val enqueuer: AutomationExecutionEnqueuer,
    private val objectMapper: ObjectMapper,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * SCHEDULED 발화 대상 룰을 폴링해 실행 큐에 적재하고 `next_fire_at` 을 갱신한다.
     *
     * 실행 주기는 `bts.automation.schedule-worker.poll-interval-ms` 프로퍼티(기본
     * [DEFAULT_POLL_INTERVAL_MS]ms)로 조정 가능하다. 실제 스케줄 결선(`@EnableScheduling`)은 Task 11
     * 범위이며, Task 8 테스트는 이 메서드를 직접 호출한다(plan-eng-review E5).
     */
    @Scheduled(fixedDelayString = "\${bts.automation.schedule-worker.poll-interval-ms:$DEFAULT_POLL_INTERVAL_MS}")
    fun pollAndFire() {
        val now = clock.instant()
        repository.findScheduledDue(now).forEach { rule -> fireRule(rule, now) }
    }

    /** 한 룰을 발화(enqueue)하고 next_fire_at 을 갱신한다. 예외는 로그만 남기고 다음 룰로 계속 진행한다. */
    @Suppress("TooGenericExceptionCaught")
    private fun fireRule(
        rule: AutomationRule,
        now: Instant,
    ) {
        try {
            val next = computeNextFireAt(rule, now)
            if (next == null) {
                log.error("automation_schedule_worker_no_next_fire_at ruleId={}", rule.id)
                return
            }
            enqueuer.enqueue(rule.id, rule.triggerType, objectMapper.createObjectNode())
            repository.updateNextFireAt(rule.id, next)
            log.info("automation_schedule_worker_fired ruleId={} nextFireAt={}", rule.id, next)
        } catch (e: Exception) {
            log.error("automation_schedule_worker_fire_failed ruleId={} error={}", rule.id, e.message, e)
        }
    }

    /**
     * [rule] 의 cron 이 [now] 이후 다시 매칭될 시각을 UTC 로 계산한다.
     *
     * cron 검색 범위 내(Spring [CronExpression] 내부 상한) 매칭되는 미래 시각이 없으면 `null` 을
     * 반환한다(예: `2월 30일` 처럼 영원히 매칭되지 않는 표현식 — [TriggerConfig.validate] 는 파싱
     * 가능 여부만 검증하므로 이런 표현식도 형식상 통과할 수 있다).
     */
    private fun computeNextFireAt(
        rule: AutomationRule,
        now: Instant,
    ): Instant? {
        val cron =
            objectMapper.readTree(rule.triggerConfig).get(FIELD_CRON)?.asText()
                ?: error("SCHEDULED 룰(${rule.id})의 triggerConfig 에 cron 필드가 없습니다.")
        val nowUtc = LocalDateTime.ofInstant(now, ZoneOffset.UTC)
        return CronExpression.parse(cron).next(nowUtc)?.toInstant(ZoneOffset.UTC)
    }

    private companion object {
        /** triggerConfig JSON 의 cron 필드명 — [com.bts.automation.domain.TriggerConfig] 와 동일 계약. */
        const val FIELD_CRON = "cron"

        /** 폴링 주기 기본값(ms) — 프로퍼티 미설정 시 사용. */
        const val DEFAULT_POLL_INTERVAL_MS = 1000
    }
}
