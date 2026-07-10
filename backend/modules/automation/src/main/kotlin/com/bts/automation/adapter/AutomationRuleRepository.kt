// AutomationRule 영속 어댑터 — JdbcTemplate 기반 CRUD·이벤트/웹훅/스케줄 조회·OCC (FR-AT-01 Task 4)

package com.bts.automation.adapter

import com.bts.automation.domain.AutomationRule
import com.bts.automation.domain.TriggerType
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * [AutomationRule] 영속 어댑터 (FR-AT-01 Task 4).
 *
 * automation 모듈의 첫 `@Repository`. jOOQ 미도입(단일 테이블 단순 CRUD, DATA.md §5)이므로
 * [NamedParameterJdbcTemplate] `:param` 바인딩만 사용하고 SQL 문자열 결합은 하지 않는다
 * (DEVELOPMENT.md §1 절대 규칙 — SQL 인젝션 방어).
 *
 * ## 소프트 삭제
 * 모든 조회는 `deleted_at IS NULL` 로 소프트 삭제 행을 제외한다(DATA.md §3). 물리 삭제는 없다.
 *
 * ## OCC(낙관적 동시성 제어)
 * [update] 는 [AutomationRule.version] 이 애그리거트 동작(rename/enable/disable/updateConfig)으로
 * 이미 +1 된 새 버전을 담고 있다고 전제한다. 따라서 DB 의 기대 버전은 `version - 1` 이며, 그 행이
 * 없으면(다른 트랜잭션이 먼저 갱신했거나 삭제됨) [OptimisticLockingFailureException] 을 던진다.
 *
 * ## no-bump 사이드카 갱신
 * [updateNextFireAt] 는 스케줄러의 발화 부기(bookkeeping)만 수행하므로 OCC 버전을 bump 하지
 * 않는다([[no-bump-sidecar-version-double-bump]] — 사이드카 갱신이 버전을 올리면 사용자 편집과
 * 경쟁해 OCC 이중 bump 사고가 난다).
 *
 * @param jdbc named parameter 바인딩 [NamedParameterJdbcTemplate].
 */
@Repository
class AutomationRuleRepository(
    private val jdbc: NamedParameterJdbcTemplate,
) {
    /**
     * 신규 [rule] 을 삽입한다. 애그리거트가 이미 확정한 id·시각·version 을 그대로 저장한다.
     *
     * @param rule 저장할 [AutomationRule] 애그리거트.
     */
    @Transactional
    fun save(rule: AutomationRule) {
        jdbc.update(SQL_INSERT, insertParams(rule))
    }

    /**
     * OCC 버전 검사와 함께 [rule] 을 갱신한다.
     *
     * @param rule 갱신할 애그리거트(동작 메서드로 version 이 이미 +1 된 상태).
     * @throws OptimisticLockingFailureException 기대 버전(version-1)의 활성 행이 없을 때.
     */
    @Transactional
    fun update(rule: AutomationRule) {
        val affected = jdbc.update(SQL_UPDATE, updateParams(rule))
        if (affected == 0) {
            throw OptimisticLockingFailureException(
                "룰(${rule.id}) 갱신 실패 — 다른 변경이 먼저 반영되었거나 삭제되었습니다(기대 version=${rule.version - 1}).",
            )
        }
    }

    /**
     * [id] 룰을 소프트 삭제한다(`deleted_at` 설정). 이미 삭제된 룰에는 영향이 없다(멱등).
     *
     * @param id 삭제할 룰 id.
     * @param now 삭제 시각.
     */
    @Transactional
    fun softDelete(
        id: UUID,
        now: Instant,
    ) {
        jdbc.update(
            SQL_SOFT_DELETE,
            MapSqlParameterSource()
                .addValue("id", id)
                .addValue("now", now.atOffset(ZoneOffset.UTC)),
        )
    }

    /**
     * 스케줄러 발화 부기 갱신 — `next_fire_at` 만 갱신하고 OCC 버전은 bump 하지 않는다(no-bump).
     *
     * @param id 대상 룰 id.
     * @param next 다음 발화 예정 시각.
     */
    @Transactional
    fun updateNextFireAt(
        id: UUID,
        next: Instant,
    ) {
        jdbc.update(
            SQL_UPDATE_NEXT_FIRE_AT,
            MapSqlParameterSource()
                .addValue("id", id)
                .addValue("nextFireAt", next.atOffset(ZoneOffset.UTC)),
        )
    }

    /**
     * [id] 활성(미삭제) 룰을 조회한다.
     *
     * @param id 룰 id.
     * @return 활성 룰 또는 `null`.
     */
    @Transactional(readOnly = true)
    fun findById(id: UUID): AutomationRule? =
        jdbc.query(SQL_FIND_BY_ID, MapSqlParameterSource("id", id)) { rs, _ -> mapRule(rs) }
            .firstOrNull()

    /**
     * [projectKey] 프로젝트의 활성(미삭제) 룰 목록을 반환한다.
     *
     * @param projectKey 프로젝트 키.
     * @return 생성순 활성 룰 목록.
     */
    @Transactional(readOnly = true)
    fun findByProject(projectKey: String): List<AutomationRule> =
        jdbc.query(SQL_FIND_BY_PROJECT, MapSqlParameterSource("projectKey", projectKey)) { rs, _ -> mapRule(rs) }

    /**
     * 이슈 이벤트 매칭용 — [projectKey]·[triggerType] 이 일치하는 enabled·미삭제 룰을 반환한다.
     *
     * @param projectKey 프로젝트 키.
     * @param triggerType 매칭할 트리거 타입.
     * @return 매칭 대상 활성 룰 목록.
     */
    @Transactional(readOnly = true)
    fun findEnabledByProjectAndTriggerType(
        projectKey: String,
        triggerType: TriggerType,
    ): List<AutomationRule> =
        jdbc.query(
            SQL_FIND_ENABLED_BY_PROJECT_AND_TYPE,
            MapSqlParameterSource()
                .addValue("projectKey", projectKey)
                .addValue("triggerType", triggerType.name),
        ) { rs, _ -> mapRule(rs) }

    /**
     * 웹훅 인바운드용 — [tokenHash] 로 enabled·미삭제 WEBHOOK 룰을 조회한다.
     *
     * @param tokenHash 인바운드 토큰의 SHA-256 해시.
     * @return 매칭 룰 또는 `null`.
     */
    @Transactional(readOnly = true)
    fun findByWebhookTokenHash(tokenHash: String): AutomationRule? =
        jdbc.query(SQL_FIND_BY_WEBHOOK_TOKEN_HASH, MapSqlParameterSource("tokenHash", tokenHash)) { rs, _ ->
            mapRule(rs)
        }.firstOrNull()

    /**
     * 스케줄 발화 대상 조회 — `next_fire_at <= now` 인 SCHEDULED·enabled·미삭제 룰을 반환한다.
     *
     * @param now 발화 기준 시각.
     * @return 발화 대상 룰 목록.
     */
    @Transactional(readOnly = true)
    fun findScheduledDue(now: Instant): List<AutomationRule> =
        jdbc.query(
            SQL_FIND_SCHEDULED_DUE,
            MapSqlParameterSource("now", now.atOffset(ZoneOffset.UTC)),
        ) { rs, _ -> mapRule(rs) }

    private fun insertParams(rule: AutomationRule): MapSqlParameterSource =
        MapSqlParameterSource()
            .addValue("id", rule.id)
            .addValue("projectKey", rule.projectKey)
            .addValue("name", rule.name)
            .addValue("enabled", rule.enabled)
            .addValue("triggerType", rule.triggerType.name)
            .addValue("triggerConfig", rule.triggerConfig)
            .addValue("webhookTokenHash", rule.webhookTokenHash)
            .addValue("nextFireAt", rule.nextFireAt?.atOffset(ZoneOffset.UTC))
            .addValue("createdBy", rule.createdBy)
            .addValue("createdAt", rule.createdAt.atOffset(ZoneOffset.UTC))
            .addValue("updatedAt", rule.updatedAt.atOffset(ZoneOffset.UTC))
            .addValue("version", rule.version)

    private fun updateParams(rule: AutomationRule): MapSqlParameterSource =
        MapSqlParameterSource()
            .addValue("id", rule.id)
            .addValue("name", rule.name)
            .addValue("enabled", rule.enabled)
            .addValue("triggerConfig", rule.triggerConfig)
            .addValue("webhookTokenHash", rule.webhookTokenHash)
            .addValue("nextFireAt", rule.nextFireAt?.atOffset(ZoneOffset.UTC))
            .addValue("updatedAt", rule.updatedAt.atOffset(ZoneOffset.UTC))
            .addValue("version", rule.version)
            .addValue("expectedVersion", rule.version - 1)

    private fun mapRule(rs: ResultSet): AutomationRule =
        AutomationRule(
            id = rs.getObject("id", UUID::class.java),
            projectKey = rs.getString("project_key"),
            name = rs.getString("name"),
            enabled = rs.getBoolean("enabled"),
            triggerType = TriggerType.valueOf(rs.getString("trigger_type")),
            triggerConfig = rs.getString("trigger_config"),
            webhookTokenHash = rs.getString("webhook_token_hash"),
            nextFireAt = rs.getObject("next_fire_at", OffsetDateTime::class.java)?.toInstant(),
            createdBy = rs.getObject("created_by", UUID::class.java),
            createdAt = rs.getObject("created_at", OffsetDateTime::class.java).toInstant(),
            updatedAt = rs.getObject("updated_at", OffsetDateTime::class.java).toInstant(),
            deletedAt = rs.getObject("deleted_at", OffsetDateTime::class.java)?.toInstant(),
            version = rs.getLong("version"),
        )

    private companion object {
        const val SQL_INSERT = """
            INSERT INTO automation_rules
                (id, project_key, name, enabled, trigger_type, trigger_config, webhook_token_hash,
                 next_fire_at, created_by, created_at, updated_at, version)
            VALUES
                (:id, :projectKey, :name, :enabled, :triggerType, CAST(:triggerConfig AS jsonb),
                 :webhookTokenHash, :nextFireAt, :createdBy, :createdAt, :updatedAt, :version)
        """

        const val SQL_UPDATE = """
            UPDATE automation_rules SET
                name = :name,
                enabled = :enabled,
                trigger_config = CAST(:triggerConfig AS jsonb),
                webhook_token_hash = :webhookTokenHash,
                next_fire_at = :nextFireAt,
                updated_at = :updatedAt,
                version = :version
            WHERE id = :id AND version = :expectedVersion AND deleted_at IS NULL
        """

        const val SQL_SOFT_DELETE = """
            UPDATE automation_rules SET deleted_at = :now
            WHERE id = :id AND deleted_at IS NULL
        """

        const val SQL_UPDATE_NEXT_FIRE_AT = """
            UPDATE automation_rules SET next_fire_at = :nextFireAt
            WHERE id = :id AND deleted_at IS NULL
        """

        const val SQL_SELECT_COLUMNS = """
            SELECT id, project_key, name, enabled, trigger_type, trigger_config, webhook_token_hash,
                   next_fire_at, created_by, created_at, updated_at, deleted_at, version
            FROM automation_rules
        """

        const val SQL_FIND_BY_ID = "$SQL_SELECT_COLUMNS WHERE id = :id AND deleted_at IS NULL"

        const val SQL_FIND_BY_PROJECT = """
            $SQL_SELECT_COLUMNS
            WHERE project_key = :projectKey AND deleted_at IS NULL
            ORDER BY created_at, id
        """

        const val SQL_FIND_ENABLED_BY_PROJECT_AND_TYPE = """
            $SQL_SELECT_COLUMNS
            WHERE project_key = :projectKey AND trigger_type = :triggerType
              AND enabled = true AND deleted_at IS NULL
            ORDER BY created_at, id
        """

        const val SQL_FIND_BY_WEBHOOK_TOKEN_HASH = """
            $SQL_SELECT_COLUMNS
            WHERE webhook_token_hash = :tokenHash AND enabled = true AND deleted_at IS NULL
        """

        const val SQL_FIND_SCHEDULED_DUE = """
            $SQL_SELECT_COLUMNS
            WHERE trigger_type = 'SCHEDULED' AND enabled = true AND deleted_at IS NULL
              AND next_fire_at IS NOT NULL AND next_fire_at <= :now
            ORDER BY next_fire_at, id
        """
    }
}
