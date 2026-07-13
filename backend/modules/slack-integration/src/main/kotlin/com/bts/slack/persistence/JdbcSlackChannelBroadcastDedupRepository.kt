// SlackChannelBroadcastDedupRepository JdbcTemplate 구현체 — slack_channel_broadcast_log dedup (FR-SL-06 PR-B Task 3)

package com.bts.slack.persistence

import com.bts.slack.application.SlackChannelBroadcastDedupRepository
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

@Repository
class JdbcSlackChannelBroadcastDedupRepository(
    private val jdbcTemplate: JdbcTemplate,
) : SlackChannelBroadcastDedupRepository {
    @Transactional(readOnly = true)
    override fun existsPosted(dedupKey: String): Boolean =
        jdbcTemplate.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM slack_channel_broadcast_log WHERE dedup_key = ?)",
            Boolean::class.java,
            dedupKey,
        ) ?: false

    @Transactional
    override fun recordPosted(dedupKey: String) {
        jdbcTemplate.update(
            "INSERT INTO slack_channel_broadcast_log (dedup_key) VALUES (?) ON CONFLICT (dedup_key) DO NOTHING",
            dedupKey,
        )
    }
}
