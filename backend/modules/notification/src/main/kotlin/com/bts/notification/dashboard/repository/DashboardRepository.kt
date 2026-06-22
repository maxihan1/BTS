// 대시보드 CRUD + shares + 목록 UNION 페이지네이션 jOOQ Repository

package com.bts.notification.dashboard.repository

import com.bts.notification.dashboard.domain.Dashboard
import com.bts.notification.dashboard.domain.DashboardVisibility
import com.bts.notification.jooq.tables.references.DASHBOARD_SHARES
import com.bts.notification.jooq.tables.references.DASHBOARDS
import org.jooq.DSLContext
import org.jooq.JSONB
import org.jooq.Record
import org.jooq.SelectSeekStep1
import org.jooq.impl.DSL
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * dashboards + dashboard_shares 테이블의 CRUD 를 담당하는 Repository.
 *
 * jOOQ DSL 만 사용한다 — SQL 문자열 결합 금지 (DATA.md §5).
 * 모든 public 메서드에 @Transactional 을 명시한다 (DATA.md §6).
 * 소프트 삭제(deleted_at IS NULL 필터)를 모든 단건/목록/update/delete 쿼리에 적용한다.
 *
 * @param dsl jOOQ DSLContext — SQL 을 코드로 안전하게 작성하는 라이브러리의 핵심 진입점
 */
@Repository
class DashboardRepository(
    private val dsl: DSLContext,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 대시보드 1건을 삽입하고 DB 에서 생성된 행(타임스탬프 포함)을 반환한다.
     *
     * shares 는 insert 후 별도 replaceShares 로 저장한다 (Service 레이어에서 단일 트랜잭션).
     *
     * @param dashboard 저장할 대시보드 도메인 객체
     * @return DB 에서 읽어온 최신 행
     */
    @Transactional
    fun insert(dashboard: Dashboard): Dashboard {
        log.debug("대시보드 삽입 — id={}, ownerId={}", dashboard.id, dashboard.ownerId)

        val record =
            dsl.insertInto(DASHBOARDS)
                .set(DASHBOARDS.ID, dashboard.id)
                .set(DASHBOARDS.OWNER_ID, dashboard.ownerId)
                .set(DASHBOARDS.NAME, dashboard.name)
                .set(DASHBOARDS.DESCRIPTION, dashboard.description)
                .set(DASHBOARDS.VISIBILITY, dashboard.visibility.name)
                .set(DASHBOARDS.LAYOUT, JSONB.valueOf(dashboard.layout))
                .set(DASHBOARDS.VERSION, dashboard.version)
                .returning()
                .fetchOne()
                ?: error("INSERT 후 행 반환 실패 — id=${dashboard.id}")

        return toDomain(record)
    }

    /**
     * id 로 대시보드 1건을 조회한다 (deleted_at IS NULL 필터 포함).
     *
     * @param id 조회할 대시보드 식별자
     * @return 조회된 대시보드 도메인 객체, 없거나 삭제됐으면 null
     */
    @Transactional(readOnly = true)
    fun findById(id: UUID): Dashboard? {
        val record =
            dsl.selectFrom(DASHBOARDS)
                .where(DASHBOARDS.ID.eq(id))
                .and(DASHBOARDS.DELETED_AT.isNull)
                .fetchOne()
        return record?.let { toDomain(it) }
    }

    /**
     * 대시보드 필드와 version 을 OCC 조건으로 업데이트한다.
     *
     * WHERE id = ? AND version = ? AND deleted_at IS NULL 조건으로 갱신.
     * 반환 rowcount 0 이면 버전 불일치(OCC 충돌) 또는 소프트 삭제 — 서비스가 409 변환.
     *
     * @param dashboard version 이 +1 된 최신 도메인 객체 (applyPatch 결과)
     * @return 영향받은 행 수 (0 = 충돌 또는 없음, 1 = 성공)
     */
    @Transactional
    fun update(dashboard: Dashboard): Int {
        log.debug("대시보드 업데이트 — id={}, version={}", dashboard.id, dashboard.version)

        // DB 의 version 은 현재 도메인 객체 version - 1 (applyPatch 가 +1 했으므로)
        val prevVersion = dashboard.version - 1

        return dsl.update(DASHBOARDS)
            .set(DASHBOARDS.NAME, dashboard.name)
            .set(DASHBOARDS.DESCRIPTION, dashboard.description)
            .set(DASHBOARDS.VISIBILITY, dashboard.visibility.name)
            .set(DASHBOARDS.LAYOUT, JSONB.valueOf(dashboard.layout))
            .set(DASHBOARDS.VERSION, dashboard.version)
            .set(DASHBOARDS.UPDATED_AT, dashboard.updatedAt.atOffset(ZoneOffset.UTC))
            .where(DASHBOARDS.ID.eq(dashboard.id))
            .and(DASHBOARDS.VERSION.eq(prevVersion))
            .and(DASHBOARDS.DELETED_AT.isNull)
            .execute()
    }

    /**
     * 대시보드를 소프트 삭제한다 (deleted_at = now() 설정).
     *
     * 하드 DELETE 는 사용하지 않는다 (DATA.md §3).
     * 이미 삭제된 행은 조건에 걸리지 않아 rowcount 0 이 반환된다(멱등).
     *
     * @param id 삭제할 대시보드 식별자
     * @return 영향받은 행 수 (0 = 미존재 또는 이미 삭제, 1 = 성공)
     */
    @Transactional
    fun softDelete(id: UUID): Int {
        log.debug("대시보드 소프트 삭제 — id={}", id)

        return dsl.update(DASHBOARDS)
            .set(DASHBOARDS.DELETED_AT, DSL.currentOffsetDateTime())
            .where(DASHBOARDS.ID.eq(id))
            .and(DASHBOARDS.DELETED_AT.isNull)
            .execute()
    }

    /**
     * 대시보드 공유 사용자 목록을 교체한다 (기존 전부 삭제 후 신규 삽입).
     *
     * TEAM visibility 에서 PATCH 요청 시 sharedUserIds 를 통째 replace 한다.
     * Dashboard aggregate 가 ownerId 제거 + visibility 정규화를 완료한 집합을 전달한다.
     *
     * @param dashboardId 공유 대상 대시보드 식별자
     * @param userIds 교체할 공유 사용자 ID 집합 (빈 집합이면 전체 제거)
     */
    @Transactional
    fun replaceShares(dashboardId: UUID, userIds: Set<UUID>) {
        log.debug("dashboard_shares 교체 — dashboardId={}, size={}", dashboardId, userIds.size)

        dsl.deleteFrom(DASHBOARD_SHARES)
            .where(DASHBOARD_SHARES.DASHBOARD_ID.eq(dashboardId))
            .execute()

        if (userIds.isEmpty()) return

        val insertStep =
            dsl.insertInto(DASHBOARD_SHARES, DASHBOARD_SHARES.DASHBOARD_ID, DASHBOARD_SHARES.USER_ID)

        userIds.forEach { userId ->
            insertStep.values(dashboardId, userId)
        }

        insertStep.execute()
    }

    /**
     * 대시보드의 공유 사용자 ID 목록을 조회한다.
     *
     * 부모 대시보드가 소프트 삭제된 경우 shares 를 가려야 하므로 부모 JOIN 필터를 적용한다.
     *
     * @param dashboardId 공유 대상 대시보드 식별자
     * @return 공유 사용자 ID 목록 (부모 삭제 시 빈 목록)
     */
    @Transactional(readOnly = true)
    fun findSharesByDashboardId(dashboardId: UUID): List<UUID> {
        return dsl.select(DASHBOARD_SHARES.USER_ID)
            .from(DASHBOARD_SHARES)
            .join(DASHBOARDS).on(DASHBOARD_SHARES.DASHBOARD_ID.eq(DASHBOARDS.ID))
            .where(DASHBOARD_SHARES.DASHBOARD_ID.eq(dashboardId))
            .and(DASHBOARDS.DELETED_AT.isNull)
            .fetch()
            .mapNotNull { it.value1() }
    }

    /**
     * 행위자(actor)가 접근 가능한 대시보드 목록을 페이지네이션으로 조회한다.
     *
     * 접근 가능 범위 = owned(소유) UNION shared-to-me(TEAM 공유 대상) UNION ORG(전체 공개).
     * UNION 으로 중복을 제거하고(owned 이면서 ORG 는 1건), updatedAt desc 정렬.
     *
     * C2 원칙: total count 는 items 쿼리와 분리된 별도 COUNT 서브쿼리 — cartesian product 방지.
     *
     * @param actorId 조회 주체 사용자 ID
     * @param limit 페이지 크기 (상한은 Service 레이어에서 적용)
     * @param offset 건너뛸 항목 수
     * @return 페이지네이션 결과 (items + total)
     */
    @Transactional(readOnly = true)
    fun findPage(actorId: UUID, limit: Int, offset: Int): DashboardPage {
        val unionQuery = buildUnionQuery(actorId)

        val total =
            dsl.fetchCount(dsl.select(DSL.asterisk()).from(unionQuery.asTable("sub")))

        val items =
            dsl.select(DSL.asterisk())
                .from(unionQuery.asTable("paged"))
                .orderBy(DSL.field("updated_at").desc())
                .limit(limit)
                .offset(offset)
                .fetch()
                .map { toDomainFromRecord(it) }

        return DashboardPage(items = items, total = total)
    }

    /**
     * owned UNION shared-to-me UNION ORG 서브쿼리를 생성한다.
     *
     * 각 절은 deleted_at IS NULL 필터를 독립적으로 포함해 삭제된 항목이 어느 절에서도 나오지 않도록 한다.
     *
     * @param actorId 조회 주체 사용자 ID
     * @return UNION 서브쿼리
     */
    private fun buildUnionQuery(actorId: UUID): SelectSeekStep1<*, *> {
        val cols = arrayOf(
            DASHBOARDS.ID,
            DASHBOARDS.OWNER_ID,
            DASHBOARDS.NAME,
            DASHBOARDS.DESCRIPTION,
            DASHBOARDS.VISIBILITY,
            DASHBOARDS.LAYOUT,
            DASHBOARDS.CREATED_AT,
            DASHBOARDS.UPDATED_AT,
            DASHBOARDS.DELETED_AT,
            DASHBOARDS.VERSION,
        )

        // 1) owned — 본인 소유 대시보드
        val ownedQuery =
            dsl.select(*cols)
                .from(DASHBOARDS)
                .where(DASHBOARDS.OWNER_ID.eq(actorId))
                .and(DASHBOARDS.DELETED_AT.isNull)

        // 2) shared-to-me — TEAM 공유받은 대시보드
        val sharedIds =
            dsl.select(DASHBOARD_SHARES.DASHBOARD_ID)
                .from(DASHBOARD_SHARES)
                .where(DASHBOARD_SHARES.USER_ID.eq(actorId))

        val sharedQuery =
            dsl.select(*cols)
                .from(DASHBOARDS)
                .where(DASHBOARDS.ID.`in`(sharedIds))
                .and(DASHBOARDS.VISIBILITY.eq(DashboardVisibility.TEAM.name))
                .and(DASHBOARDS.DELETED_AT.isNull)

        // 3) ORG — 전체 공개 대시보드
        val orgQuery =
            dsl.select(*cols)
                .from(DASHBOARDS)
                .where(DASHBOARDS.VISIBILITY.eq(DashboardVisibility.ORG.name))
                .and(DASHBOARDS.DELETED_AT.isNull)

        // UNION(중복 제거) — owned 이면서 ORG 인 경우 1건으로 합산
        @Suppress("UNCHECKED_CAST")
        return ownedQuery
            .union(sharedQuery)
            .union(orgQuery)
            .orderBy(DSL.field("updated_at").desc()) as SelectSeekStep1<*, *>
    }

    /**
     * jOOQ DashboardsRecord 를 도메인 Dashboard 로 변환한다.
     *
     * @param record jOOQ 에서 읽어온 typed record
     * @return 변환된 도메인 객체
     */
    private fun toDomain(record: com.bts.notification.jooq.tables.records.DashboardsRecord): Dashboard {
        val id = record.id ?: error("id 가 null — DB 데이터 손상")
        val ownerId = record.ownerId ?: error("owner_id 가 null — id=$id")
        val name = record.name ?: error("name 이 null — id=$id")
        val visibility =
            DashboardVisibility.valueOf(
                record.visibility ?: error("visibility 가 null — id=$id"),
            )
        val layout = record.layout?.data() ?: "[]"
        val createdAt =
            record.createdAt?.toInstant() ?: error("created_at 이 null — id=$id")
        val updatedAt =
            record.updatedAt?.toInstant() ?: error("updated_at 이 null — id=$id")
        val deletedAt: Instant? = record.deletedAt?.toInstant()
        val version = record.version ?: error("version 이 null — id=$id")

        // shares 는 별도 조회 (단건 조회 시 service 레이어에서 합산)
        return Dashboard(
            id = id,
            ownerId = ownerId,
            name = name,
            description = record.description,
            visibility = visibility,
            layout = layout,
            sharedUserIds = emptySet(),
            createdAt = createdAt,
            updatedAt = updatedAt,
            deletedAt = deletedAt,
            version = version,
        )
    }

    /**
     * jOOQ generic Record (UNION 결과) 를 도메인 Dashboard 로 변환한다.
     *
     * UNION 결과는 typed record 가 아니라 generic Record 이므로 컬럼명으로 접근한다.
     *
     * @param record UNION 쿼리에서 반환된 jOOQ generic Record
     * @return 변환된 도메인 객체
     */
    private fun toDomainFromRecord(record: Record): Dashboard {
        val id = record.get("id", UUID::class.java) ?: error("id 가 null — UNION 결과")
        val ownerId = record.get("owner_id", UUID::class.java) ?: error("owner_id 가 null — id=$id")
        val name = record.get("name", String::class.java) ?: error("name 이 null — id=$id")
        val visibilityStr = record.get("visibility", String::class.java) ?: error("visibility 가 null — id=$id")
        val visibility = DashboardVisibility.valueOf(visibilityStr)
        val layoutJsonb = record.get("layout", JSONB::class.java)
        val layout = layoutJsonb?.data() ?: "[]"
        val createdAt = record.get("created_at", java.time.OffsetDateTime::class.java)
            ?.toInstant() ?: error("created_at 이 null — id=$id")
        val updatedAt = record.get("updated_at", java.time.OffsetDateTime::class.java)
            ?.toInstant() ?: error("updated_at 이 null — id=$id")
        val deletedAtOdt = record.get("deleted_at", java.time.OffsetDateTime::class.java)
        val deletedAt: Instant? = deletedAtOdt?.toInstant()
        val version = record.get("version", Long::class.java) ?: error("version 이 null — id=$id")

        return Dashboard(
            id = id,
            ownerId = ownerId,
            name = name,
            description = record.get("description", String::class.java),
            visibility = visibility,
            layout = layout,
            sharedUserIds = emptySet(),
            createdAt = createdAt,
            updatedAt = updatedAt,
            deletedAt = deletedAt,
            version = version,
        )
    }
}
