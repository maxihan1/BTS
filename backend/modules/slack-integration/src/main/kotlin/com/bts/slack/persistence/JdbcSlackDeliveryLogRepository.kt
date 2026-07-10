// SlackDeliveryLogRepository JdbcTemplate 구현체 — slack_delivery_log 존재확인/멱등기록 (FR-SL-02 Task 7)

package com.bts.slack.persistence

import com.bts.slack.application.SlackDeliveryLogRepository
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

/**
 * [SlackDeliveryLogRepository] JdbcTemplate 구현체 (FR-SL-02 Task 7).
 *
 * **SQL 인젝션 방어:** 모든 파라미터를 [NamedParameterJdbcTemplate] `:param` 바인딩으로 처리한다(DATA.md §5).
 *
 * **멱등 기록:** `ON CONFLICT (dedup_key) DO NOTHING` — 동시성/재전달로 같은 dedupKey 가 두 번 기록되어도
 * 한 행만 남는다(V701 `slack_delivery_log` PK = dedup_key).
 */
@Repository
class JdbcSlackDeliveryLogRepository(
    private val jdbc: NamedParameterJdbcTemplate,
) : SlackDeliveryLogRepository {
    /** 조회 전용 — 쓰기 잠금을 잡지 않는다 (DATA.md §6). */
    @Transactional(readOnly = true)
    override fun exists(dedupKey: String): Boolean =
        jdbc.queryForObject(
            SQL_EXISTS,
            mapOf("dedupKey" to dedupKey),
            Boolean::class.java,
        ) ?: false

    @Transactional
    override fun record(dedupKey: String) {
        jdbc.update(SQL_INSERT, mapOf("dedupKey" to dedupKey))
    }

    private companion object {
        /** dedupKey 전송 이력 존재 여부. */
        const val SQL_EXISTS = "SELECT EXISTS(SELECT 1 FROM slack_delivery_log WHERE dedup_key = :dedupKey)"

        /** 전송 성공 이력 멱등 기록 — 재전달/동시성에도 한 행만 남긴다. */
        const val SQL_INSERT = """
            INSERT INTO slack_delivery_log (dedup_key) VALUES (:dedupKey)
            ON CONFLICT (dedup_key) DO NOTHING
        """
    }
}
