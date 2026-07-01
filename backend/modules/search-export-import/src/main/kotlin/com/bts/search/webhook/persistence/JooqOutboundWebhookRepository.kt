// 아웃바운드 webhook 구독 jOOQ Repository 구현 — outbound_webhooks 테이블 CRUD + 소프트삭제 + OCC (FR-API-03 PR2)

package com.bts.search.webhook.persistence

import com.bts.search.jooq.tables.records.OutboundWebhooksRecord
import com.bts.search.jooq.tables.references.OUTBOUND_WEBHOOKS
import com.bts.search.webhook.application.OutboundWebhookRepository
import com.bts.search.webhook.domain.OutboundWebhook
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * [OutboundWebhookRepository] 의 jOOQ 구현체.
 *
 * jOOQ DSL 만 사용한다 — SQL 문자열 결합 금지 (DATA.md §5).
 * 모든 public 메서드에 [Transactional] 을 명시한다 (DATA.md §6).
 *
 * ## 소프트 삭제
 * [findById] / [listAll] / [update] 는 모두 `deleted_at IS NULL` 조건을 적용한다.
 * [softDelete] 는 물리 DELETE 대신 `deleted_at = now()` 를 세팅한다(DATA.md §1.2).
 *
 * ## OCC (낙관적 동시성 제어)
 * [update] 는 `WHERE id = ? AND version = ?` 으로 정확한 버전에만 UPDATE 를 실행한다.
 * 동시에 다른 트랜잭션이 먼저 수정했거나 소프트 삭제됐으면 0행 → null 반환으로 충돌을 신호한다.
 *
 * ## event_filter 배열 매핑
 * PostgreSQL `text[]` 컬럼은 jOOQ 가 `Array<String?>` 로 생성한다. 도메인의 `List<String>` 과
 * 왕복 변환은 [toDbArray] / [OutboundWebhooksRecord.toDomain] 이 담당한다.
 *
 * @param dsl jOOQ DSLContext — SQL 을 코드로 안전하게 작성하는 라이브러리의 핵심 진입점
 */
@Repository
class JooqOutboundWebhookRepository(
    private val dsl: DSLContext,
) : OutboundWebhookRepository {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 새 구독을 INSERT 하고 DB 가 채운 id / 타임스탬프를 포함해 반환한다.
     *
     * id = UUID.randomUUID(), created_at / updated_at = DB DEFAULT now(), version = DB DEFAULT 0.
     */
    @Transactional
    override fun save(webhook: OutboundWebhook): OutboundWebhook {
        val id = UUID.randomUUID()
        log.debug("웹훅 구독 저장 — createdBy={}, name={}", webhook.createdBy, webhook.name)

        val record =
            dsl.insertInto(OUTBOUND_WEBHOOKS)
                .set(OUTBOUND_WEBHOOKS.ID, id)
                .set(OUTBOUND_WEBHOOKS.NAME, webhook.name)
                .set(OUTBOUND_WEBHOOKS.URL, webhook.url)
                .set(OUTBOUND_WEBHOOKS.SECRET_ENCRYPTED, webhook.secretEncrypted)
                .set(OUTBOUND_WEBHOOKS.EVENT_FILTER, webhook.eventFilter.toDbArray())
                .set(OUTBOUND_WEBHOOKS.PROJECT_KEY, webhook.projectKey)
                .set(OUTBOUND_WEBHOOKS.ENABLED, webhook.enabled)
                .set(OUTBOUND_WEBHOOKS.CREATED_BY, webhook.createdBy)
                .returning()
                .fetchOne()
                ?: error("INSERT 후 RETURNING 실패 — createdBy=${webhook.createdBy}, name=${webhook.name}")

        return record.toDomain()
    }

    /**
     * id 로 구독을 조회한다. 소프트 삭제된 행은 제외한다.
     */
    @Transactional(readOnly = true)
    override fun findById(id: UUID): OutboundWebhook? =
        dsl.selectFrom(OUTBOUND_WEBHOOKS)
            .where(OUTBOUND_WEBHOOKS.ID.eq(id))
            .and(OUTBOUND_WEBHOOKS.DELETED_AT.isNull)
            .fetchOne()
            ?.toDomain()

    /**
     * 소프트 삭제되지 않은 구독 목록을 `created_at DESC, id ASC` 안정 정렬로 페이지네이션 반환한다.
     */
    @Transactional(readOnly = true)
    override fun listAll(
        page: Int,
        size: Int,
    ): List<OutboundWebhook> =
        dsl.selectFrom(OUTBOUND_WEBHOOKS)
            .where(OUTBOUND_WEBHOOKS.DELETED_AT.isNull)
            .orderBy(OUTBOUND_WEBHOOKS.CREATED_AT.desc(), OUTBOUND_WEBHOOKS.ID.asc())
            .limit(size)
            .offset(page.toLong() * size.toLong())
            .fetch()
            .map { it.toDomain() }

    /**
     * OCC UPDATE — `WHERE id = ? AND version = ? AND deleted_at IS NULL` 로 정확한 버전을 갱신한다.
     *
     * SET name/url/secret_encrypted/event_filter/project_key/enabled, version = version + 1,
     * updated_at = now(). RETURNING 으로 갱신된 행을 반환한다.
     * 0행이면 OCC 충돌(stale version) 또는 소프트 삭제된 행 — null 반환.
     */
    @Transactional
    override fun update(webhook: OutboundWebhook): OutboundWebhook? {
        val id = webhook.id ?: error("update 호출 시 webhook.id 는 null 이 될 수 없습니다.")
        log.debug("웹훅 구독 업데이트 — id={}, version={}", id, webhook.version)

        val record =
            dsl.update(OUTBOUND_WEBHOOKS)
                .set(OUTBOUND_WEBHOOKS.NAME, webhook.name)
                .set(OUTBOUND_WEBHOOKS.URL, webhook.url)
                .set(OUTBOUND_WEBHOOKS.SECRET_ENCRYPTED, webhook.secretEncrypted)
                .set(OUTBOUND_WEBHOOKS.EVENT_FILTER, webhook.eventFilter.toDbArray())
                .set(OUTBOUND_WEBHOOKS.PROJECT_KEY, webhook.projectKey)
                .set(OUTBOUND_WEBHOOKS.ENABLED, webhook.enabled)
                .set(OUTBOUND_WEBHOOKS.VERSION, OUTBOUND_WEBHOOKS.VERSION.add(1))
                .set(OUTBOUND_WEBHOOKS.UPDATED_AT, OffsetDateTime.now(ZoneOffset.UTC))
                .where(OUTBOUND_WEBHOOKS.ID.eq(id))
                .and(OUTBOUND_WEBHOOKS.VERSION.eq(webhook.version))
                .and(OUTBOUND_WEBHOOKS.DELETED_AT.isNull)
                .returning()
                .fetchOne()

        if (record == null) {
            log.debug("OCC 충돌 또는 행 부재(소프트삭제 포함) — id={}, version={}", id, webhook.version)
        }

        return record?.toDomain()
    }

    /**
     * id 로 구독을 소프트 삭제한다 (`deleted_at = now()`).
     *
     * `WHERE id = ? AND deleted_at IS NULL` 조건이라 이미 삭제된 행에는 재적용되지 않는다(멱등).
     */
    @Transactional
    override fun softDelete(id: UUID): Boolean {
        log.debug("웹훅 구독 소프트 삭제 — id={}", id)

        val updated =
            dsl.update(OUTBOUND_WEBHOOKS)
                .set(OUTBOUND_WEBHOOKS.DELETED_AT, OffsetDateTime.now(ZoneOffset.UTC))
                .where(OUTBOUND_WEBHOOKS.ID.eq(id))
                .and(OUTBOUND_WEBHOOKS.DELETED_AT.isNull)
                .execute()

        return updated > 0
    }

    // ── private mapper ─────────────────────────────────────────────────────────

    /**
     * jOOQ OutboundWebhooksRecord 를 도메인 OutboundWebhook 으로 변환한다.
     *
     * created_at / updated_at 은 TIMESTAMPTZ(OffsetDateTime) → Instant 로 변환한다.
     * event_filter 는 `Array<String?>` → `List<String>` 으로 변환하며, null 요소는 filterNotNull 로 방어한다.
     */
    private fun OutboundWebhooksRecord.toDomain(): OutboundWebhook =
        OutboundWebhook(
            id = id,
            name = name,
            url = url,
            secretEncrypted = secretEncrypted,
            eventFilter = eventFilter.filterNotNull(),
            projectKey = projectKey,
            enabled = enabled ?: error("outbound_webhooks.enabled 가 null — id=$id"),
            createdBy = createdBy,
            createdAt = createdAt?.toInstant(),
            updatedAt = updatedAt?.toInstant(),
            version = version ?: error("outbound_webhooks.version 이 null — id=$id"),
        )

    /**
     * 도메인 [List]<[String]> 을 PostgreSQL `text[]` 에 저장하기 위한 [Array]<[String]?> 로 변환한다.
     *
     * 빈 리스트는 빈 배열로 변환한다. null 요소 없이 String 만 포함하므로 typed null-array 를 사용한다
     * (issue-tracking IssueRepository.toDbArray 와 동일 패턴).
     */
    private fun List<String>.toDbArray(): Array<String?> = map { it as String? }.toTypedArray()
}
