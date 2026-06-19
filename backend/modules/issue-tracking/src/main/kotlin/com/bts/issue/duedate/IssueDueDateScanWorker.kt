// 매일 마감일을 스캔해 임박·지연 이슈에 도메인 이벤트를 발행하는 스케줄러 워커

package com.bts.issue.duedate

import com.bts.issue.repository.IssueDueScanItem
import com.bts.issue.repository.IssueRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * 마감일 스캔 배치 워커.
 *
 * 매일 [SCAN_CRON] 에 실행되어 KST 기준 오늘 날짜로 임박·지연 이슈를 조회하고
 * [IssueDueEventEmitter] 를 통해 per-이슈 트랜잭션으로 도메인 이벤트를 발행한다.
 *
 * ## 왜 @Transactional 이 없는가
 * 이 클래스는 오케스트레이션만 담당한다. 이벤트 발행은 [IssueDueEventEmitter] 가
 * 각 이슈마다 독립된 트랜잭션을 시작하므로, 워커가 트랜잭션을 열면 오히려
 * per-이슈 결함격리가 깨진다.
 *
 * ## KST 기준 날짜
 * 사용자가 인식하는 캘린더 날짜가 KST 이므로 [Clock] 에 Asia/Seoul 시간대를 적용해
 * [LocalDate.now] 를 구한다. 이벤트의 [occurredAt] 은 UTC 자정 [java.time.Instant] 로
 * 통일해 소비자(notification 워커)의 dedupKey 가 날짜별로 결정적이도록 한다.
 *
 * ## Clock 주입 — 시각 의존
 * [Clock.fixed] 로 교체하면 테스트에서 "오늘" 날짜를 핀(pin)할 수 있다.
 * 기본값 [Clock.systemUTC] — Spring 컨텍스트에서 별도 Clock 빈이 없을 때 사용된다.
 *
 * @param repo [IssueRepository]. 임박·지연 이슈 목록 조회 담당.
 * @param emitter [IssueDueEventEmitter]. per-이슈 트랜잭션 이벤트 발행 담당.
 * @param clock 현재 시각 소스. 기본값 UTC. 테스트에서 [Clock.fixed] 로 교체 가능.
 */
@Component
class IssueDueDateScanWorker(
    private val repo: IssueRepository,
    private val emitter: IssueDueEventEmitter,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 마감일 스캔을 실행한다.
     *
     * 실행 주기: [SCAN_CRON] (기본 매일 UTC 00:00 = KST 09:00).
     * 임박 기준: 내일(today + 1일) 마감. 지연 기준: 오늘보다 과거 마감.
     *
     * 각 이슈 이벤트 발행은 try-catch 로 감싸 한 이슈 실패가 전체 루프를 멈추지 않게 한다.
     */
    @Scheduled(cron = SCAN_CRON)
    fun scan() {
        val today = LocalDate.now(clock.withZone(KST_ZONE))
        val occurredAt = today.atStartOfDay(ZoneOffset.UTC).toInstant()

        val dueSoon = repo.findOpenIssuesDueOn(today.plusDays(1))
        val overdue = repo.findOpenOverdueIssues(today)

        var dueSoonPublished = 0
        var overduePublished = 0

        for (item in dueSoon) {
            publishSafely(item) {
                emitter.emitDueSoon(item, occurredAt)
                dueSoonPublished++
            }
        }

        for (item in overdue) {
            publishSafely(item) {
                emitter.emitOverdue(item, occurredAt)
                overduePublished++
            }
        }

        log.info(
            "due_scan_published dueSoon={} overdue={}",
            dueSoonPublished,
            overduePublished,
        )
    }

    /**
     * 이벤트 발행 블록을 실행하고 예외를 격리한다.
     *
     * 발행 실패 시 issueKey 만 로그하고 다음 이슈로 계속 진행한다 (PII 미포함).
     * Exception 을 의도적으로 폭넓게 catch 하는 이유: 트랜잭션 오류·pgmq 오류·런타임 오류 등
     * 어떤 예외가 발생해도 루프가 중단되면 안 되는 결함격리(fault isolation) 요구사항이기 때문이다.
     */
    @Suppress("TooGenericExceptionCaught")
    private inline fun publishSafely(
        item: IssueDueScanItem,
        block: () -> Unit,
    ) {
        try {
            block()
        } catch (e: Exception) {
            log.error("due_scan_emit_failed issueKey={} error={}", item.issueKey, e.message)
        }
    }

    companion object {
        /** KST(한국 표준시) 시간대 — 사용자 캘린더 날짜 기준. */
        val KST_ZONE: ZoneId = ZoneId.of("Asia/Seoul")

        /**
         * 스캔 배치 실행 cron 표현식 (Spring cron 형식: 초 분 시 일 월 요일).
         *
         * 기본값: 매일 00:00 UTC (= KST 09:00) — 한국 사용자가 업무를 시작하기 직전 스캔.
         * 운영 환경에서 변경이 필요하면 `bts.issue.due-scan.cron` 프로퍼티로 override 가능.
         */
        const val SCAN_CRON = "\${bts.issue.due-scan.cron:0 0 0 * * *}"
    }
}
