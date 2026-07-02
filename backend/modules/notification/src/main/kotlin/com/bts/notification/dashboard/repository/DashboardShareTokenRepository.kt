// 대시보드 공유 토큰(dashboard_share_tokens) jOOQ Repository

package com.bts.notification.dashboard.repository

import com.bts.notification.dashboard.domain.DashboardShareToken
import com.bts.notification.jooq.tables.records.DashboardShareTokensRecord
import com.bts.notification.jooq.tables.references.DASHBOARDS
import com.bts.notification.jooq.tables.references.DASHBOARD_SHARE_TOKENS
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.ZoneOffset
import java.util.UUID

/**
 * dashboard_share_tokens 테이블의 CRUD 를 담당하는 Repository.
 *
 * jOOQ DSL 만 사용한다 — SQL 문자열 결합 금지 (DATA.md §5).
 * 모든 public 메서드에 @Transactional 을 명시한다 (DATA.md §6).
 * 만료(expiresAt) 판정은 여기서 하지 않는다 — Service 레이어가 DashboardShareToken.isExpired 로 판정.
 *
 * @param dsl jOOQ DSLContext — SQL 을 코드로 안전하게 작성하는 라이브러리의 핵심 진입점
 */
@Repository
class DashboardShareTokenRepository(
    private val dsl: DSLContext,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 공유 토큰 1건을 삽입하고 DB 에서 생성된 행을 반환한다.
     *
     * @param token 저장할 공유 토큰 도메인 객체
     * @return DB 에서 읽어온 최신 행
     */
    @Transactional
    fun insert(token: DashboardShareToken): DashboardShareToken {
        log.debug("공유 토큰 삽입 — id={}, dashboardId={}", token.id, token.dashboardId)

        val record =
            dsl.insertInto(DASHBOARD_SHARE_TOKENS)
                .set(DASHBOARD_SHARE_TOKENS.ID, token.id)
                .set(DASHBOARD_SHARE_TOKENS.DASHBOARD_ID, token.dashboardId)
                .set(DASHBOARD_SHARE_TOKENS.TOKEN_HASH, token.tokenHash)
                .set(DASHBOARD_SHARE_TOKENS.CREATED_BY, token.createdBy)
                .set(DASHBOARD_SHARE_TOKENS.CREATED_AT, token.createdAt.atOffset(ZoneOffset.UTC))
                .set(DASHBOARD_SHARE_TOKENS.EXPIRES_AT, token.expiresAt?.atOffset(ZoneOffset.UTC))
                .returning()
                .fetchOne()
                ?: error("INSERT 후 행 반환 실패 — id=${token.id}")

        return toDomain(record)
    }

    /**
     * 토큰 해시로 활성(부모 대시보드가 소프트 삭제되지 않은) 공유 토큰을 조회한다.
     *
     * dashboards JOIN 으로 부모 deleted_at IS NULL 필터를 적용한다.
     * 만료(expiresAt) 여부는 판정하지 않는다 — 호출자(Service)가 Clock 을 주입받아 판정.
     *
     * @param tokenHash 조회할 토큰의 SHA-256 hex 해시
     * @return 조회된 공유 토큰 도메인 객체, 없거나 부모가 삭제됐으면 null
     */
    @Suppress("SpreadOperator")
    @Transactional(readOnly = true)
    fun findActiveByTokenHash(tokenHash: String): DashboardShareToken? {
        val record =
            dsl.select(*DASHBOARD_SHARE_TOKENS.fields())
                .from(DASHBOARD_SHARE_TOKENS)
                .join(DASHBOARDS).on(DASHBOARD_SHARE_TOKENS.DASHBOARD_ID.eq(DASHBOARDS.ID))
                .where(DASHBOARD_SHARE_TOKENS.TOKEN_HASH.eq(tokenHash))
                .and(DASHBOARDS.DELETED_AT.isNull)
                .fetchOneInto(DASHBOARD_SHARE_TOKENS)
        return record?.let { toDomain(it) }
    }

    /**
     * 공유 토큰을 삭제한다(하드 삭제 — 취소는 별도 상태 필드 없이 row 삭제로 처리).
     *
     * 소유 대시보드 스코프(dashboardId)도 조건에 포함해 다른 대시보드의 토큰을 잘못 삭제하지 못하도록 한다.
     *
     * @param id 삭제할 공유 토큰 식별자
     * @param dashboardId 소유 대시보드 식별자 (교차 삭제 차단)
     * @return 영향받은 행 수 (0 = 미존재 또는 소유 대시보드 불일치, 1 = 성공)
     */
    @Transactional
    fun deleteById(
        id: UUID,
        dashboardId: UUID,
    ): Int {
        log.debug("공유 토큰 삭제 — id={}, dashboardId={}", id, dashboardId)

        return dsl.deleteFrom(DASHBOARD_SHARE_TOKENS)
            .where(DASHBOARD_SHARE_TOKENS.ID.eq(id))
            .and(DASHBOARD_SHARE_TOKENS.DASHBOARD_ID.eq(dashboardId))
            .execute()
    }

    /**
     * 대시보드에 발급된 공유 토큰 메타 목록을 안정 정렬(created_at asc, id asc)로 조회한다.
     *
     * @param dashboardId 조회 대상 대시보드 식별자
     * @return 발급 토큰 목록 (없으면 빈 목록)
     */
    @Transactional(readOnly = true)
    fun listByDashboard(dashboardId: UUID): List<DashboardShareToken> {
        return dsl.selectFrom(DASHBOARD_SHARE_TOKENS)
            .where(DASHBOARD_SHARE_TOKENS.DASHBOARD_ID.eq(dashboardId))
            .orderBy(DASHBOARD_SHARE_TOKENS.CREATED_AT.asc(), DASHBOARD_SHARE_TOKENS.ID.asc())
            .fetch()
            .map { toDomain(it) }
    }

    /**
     * 대시보드에 발급된 공유 토큰 수를 센다.
     *
     * @param dashboardId 조회 대상 대시보드 식별자
     * @return 발급 토큰 수
     */
    @Transactional(readOnly = true)
    fun countByDashboard(dashboardId: UUID): Int {
        return dsl.fetchCount(
            DASHBOARD_SHARE_TOKENS,
            DASHBOARD_SHARE_TOKENS.DASHBOARD_ID.eq(dashboardId),
        )
    }

    /**
     * jOOQ DashboardShareTokensRecord 를 도메인 DashboardShareToken 으로 변환한다.
     *
     * @param record jOOQ 에서 읽어온 typed record
     * @return 변환된 도메인 객체
     */
    private fun toDomain(record: DashboardShareTokensRecord): DashboardShareToken =
        DashboardShareToken(
            id = record.id,
            dashboardId = record.dashboardId,
            tokenHash = record.tokenHash,
            createdBy = record.createdBy,
            createdAt = record.createdAt.toInstant(),
            expiresAt = record.expiresAt?.toInstant(),
        )
}
