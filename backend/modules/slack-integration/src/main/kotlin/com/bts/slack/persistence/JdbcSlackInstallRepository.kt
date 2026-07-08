// SlackInstallRepository JdbcTemplate 구현체 — slack_installs upsert/조회 (FR-SL-01 Task 7)

package com.bts.slack.persistence

import com.bts.slack.application.SlackInstallRepository
import com.bts.slack.domain.SlackInstall
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.sql.ResultSet
import java.util.UUID

/**
 * [SlackInstallRepository] JdbcTemplate 구현체 (FR-SL-01 Task 7).
 *
 * **SQL 인젝션 방어:** 모든 파라미터를 [NamedParameterJdbcTemplate] `:param` 바인딩으로 처리하며,
 * SQL 문자열 결합은 하지 않는다 (DATA.md §5).
 *
 * **멱등 업서트:** `ON CONFLICT (team_id) DO UPDATE` — 같은 워크스페이스 재설치 시 최신 값으로
 * 갱신하고 `updated_at` 을 `now()` 로 새로 찍는다(last-write-wins, V700 마이그레이션 주석 동형).
 */
@Repository
class JdbcSlackInstallRepository(
    private val jdbc: NamedParameterJdbcTemplate,
) : SlackInstallRepository {
    @Transactional
    override fun upsert(install: SlackInstall) {
        jdbc.update(
            SQL_UPSERT,
            mapOf(
                "teamId" to install.teamId,
                "teamName" to install.teamName,
                "botUserId" to install.botUserId,
                "appId" to install.appId,
                "botTokenEncrypted" to install.botTokenEncrypted,
                "scopes" to install.scopes,
                "isEnterpriseInstall" to install.isEnterpriseInstall,
                "installedBy" to install.installedBy,
            ),
        )
    }

    /** 조회 전용 트랜잭션 — 쓰기 잠금을 잡지 않는다 (DATA.md §6 읽기 전용 규칙). */
    @Transactional(readOnly = true)
    override fun findByTeamId(teamId: String): SlackInstall? =
        jdbc.query(SQL_FIND_BY_TEAM_ID, mapOf("teamId" to teamId), SlackInstallRowMapper)
            .firstOrNull()

    private companion object {
        /** 워크스페이스 설치 멱등 업서트 — UNIQUE(team_id) 위반 시 최신 값으로 갱신(last-write-wins). */
        const val SQL_UPSERT = """
            INSERT INTO slack_installs
                (team_id, team_name, bot_user_id, app_id, bot_token_encrypted, scopes,
                 is_enterprise_install, installed_by)
            VALUES
                (:teamId, :teamName, :botUserId, :appId, :botTokenEncrypted, :scopes,
                 :isEnterpriseInstall, :installedBy)
            ON CONFLICT (team_id) DO UPDATE SET
                team_name = EXCLUDED.team_name,
                bot_user_id = EXCLUDED.bot_user_id,
                app_id = EXCLUDED.app_id,
                bot_token_encrypted = EXCLUDED.bot_token_encrypted,
                scopes = EXCLUDED.scopes,
                is_enterprise_install = EXCLUDED.is_enterprise_install,
                installed_by = EXCLUDED.installed_by,
                updated_at = now()
        """

        /** team_id 단건 조회. */
        const val SQL_FIND_BY_TEAM_ID = """
            SELECT team_id, team_name, bot_user_id, app_id, bot_token_encrypted, scopes,
                   is_enterprise_install, installed_by
            FROM slack_installs
            WHERE team_id = :teamId
        """
    }
}

/** `slack_installs` 한 행 → [SlackInstall] VO 매핑. */
private object SlackInstallRowMapper : RowMapper<SlackInstall> {
    override fun mapRow(
        rs: ResultSet,
        rowNum: Int,
    ): SlackInstall =
        SlackInstall(
            teamId = rs.getString("team_id"),
            teamName = rs.getString("team_name"),
            botUserId = rs.getString("bot_user_id"),
            appId = rs.getString("app_id"),
            botTokenEncrypted = rs.getString("bot_token_encrypted"),
            scopes = rs.getString("scopes"),
            isEnterpriseInstall = rs.getBoolean("is_enterprise_install"),
            installedBy = rs.getObject("installed_by", UUID::class.java),
        )
}
