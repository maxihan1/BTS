// git_webhook_deliveries 보존기간(7일) 경과 배달 기록을 정리하는 배치 워커 (FR-AT-07 PR-C Task 18)

package com.bts.automation.worker

import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.ZoneOffset

/**
 * `git_webhook_deliveries` 보존기간 경과 행을 정리하는 배치 워커 (FR-AT-07 PR-C Task 18, 스펙 §3.8, V308
 * KDoc "보존 정책 정리 배치(T18)").
 *
 * ## 왜 필요한가
 * `git_webhook_deliveries` 는 append-only 배달 수신 로그다(V308 — 소프트 삭제 대상이 아니다, 배달 수신은
 * 사실 기록이라 수정하지 않는다). 정리 배치가 없으면 공격자 없이 정직한 트래픽만으로도 무한 증식한다.
 * 이 테이블은 세션/임시 토큰과 동일 성격의 TTL(Time-To-Live) GC 대상이다(DATA.md §3 — 하드 삭제 허용 영역).
 *
 * ## 삭제 기준
 * `received_at < now - RETENTION_DAYS` 인 행을 단일 DELETE 문으로 직접 삭제한다. issue-tracking
 * `BulkOperationCleanupWorker` 와 달리 별도 조회(find) 단계 없이 곧바로 DELETE 하는 이유는, 이 테이블에는
 * 삭제 전 순회가 필요한 자식 테이블도, best-effort 로 처리할 외부 I/O(MinIO 등)도 없기 때문이다.
 *
 * ## 인덱스
 * V308 이 만든 `ix_git_webhook_deliveries_received_at` 를 그대로 스캔 경로로 사용한다.
 *
 * ## 시각 의존 — Clock 주입
 * [Clock] 을 주입받아 "현재 시각"을 계산한다. 기본값 [Clock.systemUTC] — automation 모듈에 별도 Clock 빈이
 * 없는 컨텍스트에서도 부팅되며([com.bts.automation.worker.AutomationScheduleWorker] 동일 관례), 테스트에서
 * [Clock.fixed] 로 교체해 결정적으로 검증한다.
 *
 * ## `@Transactional` — 단일 DELETE 문
 * 단일 SQL 문 실행이라 트랜잭션 경계가 곧 이 메서드 전체다(DATA.md §1.4). export/bulk cleanup 워커처럼
 * 외부 I/O 를 겸하지 않으므로 트랜잭션을 배제할 이유가 없다.
 *
 * @param jdbcTemplate `?` positional 바인딩 [JdbcTemplate]. `git_webhook_deliveries` DELETE 실행.
 * @param clock 현재 시각 소스. 기본값 UTC. 테스트에서 [Clock.fixed] 로 교체 가능.
 */
@Component
class GitWebhookDeliveryCleanupWorker(
    private val jdbcTemplate: JdbcTemplate,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 보존기간([RETENTION_DAYS]일) 경과한 `git_webhook_deliveries` 행을 삭제한다.
     *
     * 실행 주기: [CLEANUP_CRON](매일 04:15 UTC). 삭제 대상이 없어도 예외 없이 정상 종료한다.
     */
    @Scheduled(cron = CLEANUP_CRON)
    @Transactional
    fun purgeExpiredDeliveries() {
        val threshold = clock.instant().minus(RETENTION_DURATION)
        val deleted = jdbcTemplate.update(SQL_DELETE_EXPIRED, threshold.atOffset(ZoneOffset.UTC))
        log.info("git_webhook_deliveries_purged deleted={} threshold={}", deleted, threshold)
    }

    private companion object {
        /** 배달 기록 보존 기간(일). 이 기간이 지난 행은 [purgeExpiredDeliveries] 에서 영구 삭제된다(스펙 §3.8). */
        const val RETENTION_DAYS: Long = 7L

        /** [RETENTION_DAYS] 를 [Duration] 으로 변환한 값 — 기준 시각 계산에 사용. */
        val RETENTION_DURATION: Duration = Duration.ofDays(RETENTION_DAYS)

        /**
         * 삭제 배치 실행 cron 표현식(Spring cron 형식: 초 분 시 일 월 요일).
         *
         * 기본값: 매일 04:15 UTC — search-export-import `ExportJobCleanupWorker`(04:00)/issue-tracking
         * `BulkOperationCleanupWorker`(03:00) 와 겹치지 않는 시간대. 운영 환경에서 변경이 필요하면
         * `bts.automation.git-webhook-cleanup.cron` 프로퍼티로 override 가능.
         */
        const val CLEANUP_CRON = "\${bts.automation.git-webhook-cleanup.cron:0 15 4 * * *}"

        /** `received_at` 이 보존기간 이전인 행 삭제 — `?` positional 바인딩(DATA.md §5), WHERE 필수(DATA.md §1.2). */
        const val SQL_DELETE_EXPIRED = "DELETE FROM git_webhook_deliveries WHERE received_at < ?"
    }
}
