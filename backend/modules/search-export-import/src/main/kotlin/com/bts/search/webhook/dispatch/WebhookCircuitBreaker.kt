// 인메모리 Webhook circuit breaker — 구독별 연속 실패 시 일시 차단 (FR-API-03 PR3 Task 5)
package com.bts.search.webhook.dispatch

import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 아웃바운드 webhook 구독(id) 단위로 연속 발송 실패를 추적해 일시 차단(open)하는 circuit breaker.
 *
 * ## 정책
 * - [THRESHOLD]회 연속 실패하면 open 으로 전환되고, [OPEN_DURATION] 동안 [isOpen] 이 true 를 반환한다.
 * - open 유지 시간이 지나면 half-open 상태로 간주해 [isOpen] 이 false 를 반환, 탐침(1회 호출)을 허용한다.
 * - 탐침이 [recordSuccess] 로 이어지면 상태가 완전히 리셋(closed, 카운터 0)된다.
 * - 탐침이 [recordFailure] 로 이어지면(이미 임계 이상 누적된 상태라) 즉시 다시 open 된다.
 *
 * ## in-memory 단일 인스턴스 전제
 * 상태는 [ConcurrentHashMap] 에만 보관한다. CLAUDE.md 의 단일 호스트 토폴로지를 전제로 하며,
 * 다중 인스턴스로 스케일 아웃하면 실패 카운터가 인스턴스별로 독립적으로 누적되어
 * 실질 차단 임계치가 `THRESHOLD × 인스턴스 수`로 완화된다(Maxi 확정 수용 범위). 또한 프로세스
 * 재시작 시 모든 상태가 소실되어 즉시 closed 로 리셋된다(fail-open 방향 — 재시작 직후는 열어둠).
 *
 * @property clock 시각 출처. 기본값은 시스템 UTC 시계.
 */
@Component
class WebhookCircuitBreaker(
    private val clock: Clock = Clock.systemUTC(),
) {
    /** 구독 id → 현재 상태([State]). 상태가 없으면 closed(정상)로 취급한다. */
    private val states: ConcurrentHashMap<UUID, State> = ConcurrentHashMap()

    /**
     * [id] 구독의 발송 성공을 기록한다. 실패 카운터를 완전히 리셋한다(closed 로 전환).
     *
     * half-open 상태에서 탐침이 성공했을 때도 이 메서드로 상태를 닫는다.
     */
    fun recordSuccess(id: UUID) {
        states.remove(id)
    }

    /**
     * [id] 구독의 발송 실패를 기록한다. 실패 카운터를 누적하고, [THRESHOLD] 이상이면
     * (또는 이미 그 이상이던 상태에서 다시 실패하면) 현재 시각을 기준으로 open 시각을 갱신한다.
     */
    fun recordFailure(id: UUID) {
        states.compute(id) { _, existing ->
            val failureCount = (existing?.failureCount ?: 0) + 1
            val openedAt = if (failureCount >= THRESHOLD) clock.instant() else null
            State(failureCount = failureCount, openedAt = openedAt)
        }
    }

    /**
     * [id] 구독이 현재 open(차단) 상태인지 반환한다.
     *
     * 기록이 없거나 아직 [THRESHOLD] 미만이면 false(closed). open 시각으로부터
     * [OPEN_DURATION] 이 지나지 않았으면 true(open), 지났으면 half-open 으로 간주해
     * false(탐침 허용)를 반환한다. 경계(정확히 [OPEN_DURATION] 경과 시점)는 half-open 쪽으로
     * 판정한다 — [isWithinOpenWindow] 비교가 `<`(엄격한 미만)이기 때문이다.
     */
    fun isOpen(id: UUID): Boolean {
        val openedAt = states[id]?.openedAt ?: return false
        return isWithinOpenWindow(openedAt)
    }

    /** [openedAt] 이후 [OPEN_DURATION] 이 아직 지나지 않았으면 true. */
    private fun isWithinOpenWindow(openedAt: Instant): Boolean {
        val elapsed = Duration.between(openedAt, clock.instant())
        return elapsed < OPEN_DURATION
    }

    /**
     * 구독 하나의 circuit 상태 — 누적 실패 횟수와 open 전환 시각.
     *
     * @property failureCount 마지막 성공 이후 누적된 연속 실패 횟수.
     * @property openedAt open 으로 전환된 시각. [THRESHOLD] 미만이면 `null`(아직 closed).
     */
    private data class State(
        val failureCount: Int,
        val openedAt: Instant?,
    )

    companion object {
        /** 연속 실패가 이 값 이상이면 open 으로 전환한다. */
        private const val THRESHOLD = 5

        /** open 전환 후 half-open(탐침 허용)으로 넘어가기까지의 유지 시간. */
        private val OPEN_DURATION: Duration = Duration.ofSeconds(60)
    }
}
