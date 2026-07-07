// Slack 워크스페이스 설치 영속화 포트 — upsert(멱등) + team_id 조회 (FR-SL-01 Task 7)

package com.bts.slack.application

import com.bts.slack.domain.SlackInstall

/**
 * `slack_installs` 영속화 포트.
 *
 * 구현체는 [com.bts.slack.persistence.JdbcSlackInstallRepository] — JdbcTemplate 기반
 * (jOOQ 미도입, ADR D6 — slack_installs 단일 테이블 단순 CRUD).
 */
interface SlackInstallRepository {
    /**
     * 워크스페이스 설치를 저장한다.
     *
     * 같은 [SlackInstall.teamId] 가 이미 존재하면 최신 값으로 갱신한다(업서트, 멱등 —
     * V700 마이그레이션 `ON CONFLICT (team_id) DO UPDATE`, last-write-wins).
     */
    fun upsert(install: SlackInstall)

    /**
     * team_id 로 설치를 조회한다. 존재하지 않으면 null.
     */
    fun findByTeamId(teamId: String): SlackInstall?
}
