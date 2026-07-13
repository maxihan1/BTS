// SlackChannelBroadcastDedupRepository JdbcTemplate 구현체 — slack_channel_broadcast_log dedup (FR-SL-06 PR-B Task 3)

package com.bts.slack.persistence

import com.bts.slack.application.SlackChannelBroadcastDedupRepository
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

/**
 * [SlackChannelBroadcastDedupRepository] JdbcTemplate 구현체 (FR-SL-06 PR-B Task 3).
 *
 * **SQL 인젝션 방어:** 모든 파라미터를 `?` 위치 바인딩으로 처리하며, SQL 문자열 결합은 하지 않는다
 * (DATA.md §5). `dedup_key` 는 단일 컬럼 PK 이므로 [JdbcTemplate] 순정 API(배열 바인딩 불필요)로 충분하다.
 *
 * **멱등 기록:** [recordPosted] 는 `ON CONFLICT (dedup_key) DO NOTHING` 으로 같은 key 재삽입을 무시한다 —
 * pgmq(`q_slack_channel_broadcasts`) at-least-once 재전달로 워커가 같은 (이벤트, 채널)을 두 번 처리해도
 * 예외가 나지 않는다([JdbcSlackChannelMappingRepository] 동형의 JdbcTemplate repo 패턴).
 */
@Repository
class JdbcSlackChannelBroadcastDedupRepository(
    private val jdbcTemplate: JdbcTemplate,
) : SlackChannelBroadcastDedupRepository {
    /**
     * 조회 전용 트랜잭션 — 쓰기 잠금을 잡지 않는다 (DATA.md §6 읽기 전용 규칙).
     *
     * 블록 body 사용 — 표현식 body 로 한 줄에 두면 detekt MaxLineLength(120) 를 넘고, 줄바꿈하면 ktlint
     * function-signature 가 한 줄로 되돌리라 요구해 서로 충돌한다(learnings: ktlint↔detekt 라인길이).
     */
    @Transactional(readOnly = true)
    override fun existsPosted(dedupKey: String): Boolean {
        return jdbcTemplate.queryForObject(SQL_EXISTS, Boolean::class.java, dedupKey) ?: false
    }

    @Transactional
    override fun recordPosted(dedupKey: String) {
        jdbcTemplate.update(SQL_INSERT, dedupKey)
    }

    private companion object {
        /** dedup_key 게시 기록 존재 여부 — `EXISTS` 로 PK 인덱스만 확인한다(행 반환 없음). */
        const val SQL_EXISTS = "SELECT EXISTS(SELECT 1 FROM slack_channel_broadcast_log WHERE dedup_key = ?)"

        /** 게시 기록 삽입 — 같은 dedup_key(이벤트레벨 해시 + channelId) 재삽입은 `ON CONFLICT DO NOTHING` 으로 무시(멱등). */
        const val SQL_INSERT =
            "INSERT INTO slack_channel_broadcast_log (dedup_key) VALUES (?) ON CONFLICT (dedup_key) DO NOTHING"
    }
}
