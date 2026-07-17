// GitWebhook 영속 어댑터 — 등록/조회/소프트삭제 + 배달 dedup (FR-AT-07 PR-C Task 6)

package com.bts.automation.adapter

import com.bts.automation.domain.GitProvider
import com.bts.automation.domain.GitWebhook
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * [GitWebhook] 영속 어댑터 (FR-AT-07 PR-C Task 6).
 *
 * `git_webhooks`(V307)/`git_webhook_deliveries`(V308) 테이블을 다룬다. [AutomationRuleRepository]와
 * 달리 `?` positional 바인딩 [JdbcTemplate]을 쓴다([AutomationExecutionEnqueuer] 선례 동형) — SQL
 * 문자열 결합은 하지 않는다(DEVELOPMENT.md §1 절대 규칙).
 *
 * ## 소프트 삭제 (`git_webhooks`)
 * [findByTokenHash]/[findByProjectKey] 는 `deleted_at IS NULL` 로 소프트 삭제 행을 제외한다(DATA.md
 * §1.2). V307 의 부분 UNIQUE(`uq_git_webhooks_token_hash ... WHERE deleted_at IS NULL`)와 같은
 * 술어라 인덱스가 그대로 쓰인다. 물리 삭제 메서드는 두지 않는다(DEVELOPMENT.md §1.2-7).
 *
 * ## `git_webhook_deliveries` 는 append-only — 소프트 삭제 대상이 아니다
 * V308 KDoc 대로 배달 수신 로그는 사실 기록이라 삭제 메서드가 없다. [insertDelivery] 는 복합
 * PK(`webhook_id, delivery_id`) 충돌을 `ON CONFLICT DO NOTHING` 으로 무시하고 반영 행 수로 신규
 * 여부를 판정한다 — "먼저 조회 후 없으면 INSERT" 는 동시 재전송에서 TOCTOU 로 뚫리므로 쓰지 않는다.
 *
 * ## 트랜잭션 전파
 * [insertDelivery] 는 [AutomationExecutionEnqueuer.enqueue] KDoc(호출자 트랜잭션 참여, REQUIRED
 * 전파) 과 동형으로 기본 `@Transactional`(REQUIRED) 을 쓴다 — 호출자(웹훅 컨트롤러, Task 9)가 이미
 * 연 트랜잭션이 있으면 그 안에서 원자적으로 커밋되고, 없으면 독립 트랜잭션으로 실행된다.
 *
 * @param jdbc positional `?` 바인딩 [JdbcTemplate].
 */
@Repository
class GitWebhookRepository(
    private val jdbc: JdbcTemplate,
) {
    /**
     * 신규 [webhook] 을 삽입한다. id·createdAt 은 호출자가 이미 확정한 값을 그대로 저장한다
     * ([AutomationRuleRepository.save] 관례 동형).
     *
     * @param webhook 저장할 [GitWebhook].
     */
    @Transactional
    fun insert(webhook: GitWebhook) {
        jdbc.update(
            SQL_INSERT,
            webhook.id,
            webhook.projectKey,
            webhook.provider.name,
            webhook.tokenHash,
            webhook.secretEncrypted,
            webhook.createdAt.atOffset(ZoneOffset.UTC),
            webhook.createdBy,
        )
    }

    /**
     * [tokenHash] 로 활성(미삭제) 웹훅을 조회한다. 인바운드 웹훅 요청이 이 조회로 등록행을 찾는다.
     *
     * @param tokenHash 인바운드 URL 토큰의 SHA-256 해시.
     * @return 활성 웹훅 또는 `null`.
     */
    @Transactional(readOnly = true)
    fun findByTokenHash(tokenHash: String): GitWebhook? =
        jdbc.query(SQL_FIND_BY_TOKEN_HASH, GitWebhookRowMapper, tokenHash).firstOrNull()

    /**
     * [projectKey] 프로젝트의 활성(미삭제) 웹훅 목록을 반환한다.
     *
     * @param projectKey 프로젝트 키.
     * @return 생성순 활성 웹훅 목록.
     */
    @Transactional(readOnly = true)
    fun findByProjectKey(projectKey: String): List<GitWebhook> =
        jdbc.query(SQL_FIND_BY_PROJECT_KEY, GitWebhookRowMapper, projectKey)

    /**
     * [id] 웹훅을 소프트 삭제한다(`deleted_at` 설정). 이미 삭제된 웹훅에는 영향이 없다(멱등).
     *
     * @param id 삭제할 웹훅 id.
     * @param now 삭제 시각.
     */
    @Transactional
    fun softDelete(
        id: UUID,
        now: Instant,
    ) {
        jdbc.update(SQL_SOFT_DELETE, now.atOffset(ZoneOffset.UTC), id)
    }

    /**
     * `(webhookId, deliveryId)` 배달 기록을 삽입해 중복 수신을 방어한다(클래스 KDoc "append-only" 참조).
     *
     * @param webhookId 배달을 수신한 웹훅 id.
     * @param deliveryId provider 배달 식별자(`X-GitHub-Delivery`/`X-Gitlab-Event-UUID`).
     * @param receivedAt 수신 시각.
     * @return 신규 배달이면 `true`, 이미 기록된(재전송) 배달이면 `false`.
     */
    @Transactional
    fun insertDelivery(
        webhookId: UUID,
        deliveryId: String,
        receivedAt: Instant,
    ): Boolean {
        val affected = jdbc.update(SQL_INSERT_DELIVERY, webhookId, deliveryId, receivedAt.atOffset(ZoneOffset.UTC))
        return affected > 0
    }

    private companion object {
        const val SQL_INSERT = """
            INSERT INTO git_webhooks
                (id, project_key, provider, token_hash, secret_encrypted, created_at, created_by)
            VALUES (?, ?, ?, ?, ?, ?, ?)
        """

        const val SQL_SELECT_COLUMNS = """
            SELECT id, project_key, provider, token_hash, secret_encrypted, created_at, created_by, deleted_at
            FROM git_webhooks
        """

        const val SQL_FIND_BY_TOKEN_HASH = "$SQL_SELECT_COLUMNS WHERE token_hash = ? AND deleted_at IS NULL"

        const val SQL_FIND_BY_PROJECT_KEY = """
            $SQL_SELECT_COLUMNS
            WHERE project_key = ? AND deleted_at IS NULL
            ORDER BY created_at, id
        """

        const val SQL_SOFT_DELETE = "UPDATE git_webhooks SET deleted_at = ? WHERE id = ? AND deleted_at IS NULL"

        const val SQL_INSERT_DELIVERY = """
            INSERT INTO git_webhook_deliveries (webhook_id, delivery_id, received_at)
            VALUES (?, ?, ?)
            ON CONFLICT DO NOTHING
        """
    }
}

/**
 * `git_webhooks` 한 행 → [GitWebhook] 매핑 ([AutomationRuleRepository] 의 `AutomationRuleRowMapper`
 * 선례 동형). timestamptz 컬럼은 [OffsetDateTime] 으로 읽어 [Instant] 로 정규화하고, `deleted_at` 은
 * SQL NULL 이면 `null` 로 매핑한다.
 */
private object GitWebhookRowMapper : RowMapper<GitWebhook> {
    override fun mapRow(
        rs: ResultSet,
        rowNum: Int,
    ): GitWebhook =
        GitWebhook(
            id = rs.getObject("id", UUID::class.java),
            projectKey = rs.getString("project_key"),
            provider = GitProvider.valueOf(rs.getString("provider")),
            tokenHash = rs.getString("token_hash"),
            secretEncrypted = rs.getString("secret_encrypted"),
            createdAt = rs.getObject("created_at", OffsetDateTime::class.java).toInstant(),
            createdBy = rs.getObject("created_by", UUID::class.java),
            deletedAt = rs.getObject("deleted_at", OffsetDateTime::class.java)?.toInstant(),
        )
}
