// 전역 상태 카탈로그 Repository — 조회·쓰기·참조 카운트·워크플로우 역조회

package com.bts.workflow.status.repository

import com.bts.workflow.jooq.tables.Statuses.Companion.STATUSES
import com.bts.workflow.jooq.tables.WorkflowStatuses.Companion.WORKFLOW_STATUSES
import com.bts.workflow.jooq.tables.Workflows.Companion.WORKFLOWS
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.util.UUID

/** 전역 카탈로그의 상태 1건. 도메인 aggregate 가 아니라 카탈로그 행의 읽기 모델이다. */
data class StatusRow(
    val id: UUID,
    val key: String,
    val name: String,
    val description: String?,
    val category: String,
    val isSystem: Boolean,
)

/**
 * 전역 상태 카탈로그 Repository.
 *
 * ### 소프트 삭제 규약
 * 모든 조회에 `deleted_at IS NULL` 을 **명시적으로** 붙인다. 공통 필터 래퍼가 없으므로
 * (`DATA.md §3`) 빠뜨리면 지운 상태가 목록과 편성 후보에 되살아난다.
 */
@Repository
class StatusRepository(
    private val dsl: DSLContext,
) {
    /** 살아 있는 상태 전체. 키 순으로 준다. */
    fun findAllLive(): List<StatusRow> =
        dsl
            .select(
                STATUSES.ID,
                STATUSES.KEY,
                STATUSES.NAME,
                STATUSES.DESCRIPTION,
                STATUSES.CATEGORY,
                STATUSES.IS_SYSTEM,
            ).from(STATUSES)
            .where(STATUSES.DELETED_AT.isNull)
            .orderBy(STATUSES.KEY)
            .fetch()
            .map { it.toRow() }

    /** 살아 있는 상태 1건. 없으면 null. */
    fun findLiveById(id: UUID): StatusRow? =
        dsl
            .select(
                STATUSES.ID,
                STATUSES.KEY,
                STATUSES.NAME,
                STATUSES.DESCRIPTION,
                STATUSES.CATEGORY,
                STATUSES.IS_SYSTEM,
            ).from(STATUSES)
            .where(STATUSES.ID.eq(id))
            .and(STATUSES.DELETED_AT.isNull)
            .fetchOne()
            ?.toRow()

    /** 살아 있는 상태 중 같은 key 가 있는지. */
    fun existsLiveByKey(key: String): Boolean =
        dsl.fetchExists(
            dsl.selectOne().from(STATUSES).where(STATUSES.KEY.eq(key)).and(STATUSES.DELETED_AT.isNull),
        )

    /**
     * 살아 있는 상태 중 이름이 **대소문자 무시** 기준으로 겹치는지. [excludeId] 는 자기 자신 제외용.
     *
     * `uq_statuses_lower_name` 과 같은 판정을 애플리케이션에서 먼저 해 사용자에게 원인을 알려 준다.
     * DB 제약만 믿으면 드라이버 예외 메시지가 그대로 노출된다.
     */
    fun existsLiveByLowerName(
        name: String,
        excludeId: UUID? = null,
    ): Boolean {
        var condition = DSL.lower(STATUSES.NAME).eq(name.lowercase()).and(STATUSES.DELETED_AT.isNull)
        if (excludeId != null) {
            condition = condition.and(STATUSES.ID.ne(excludeId))
        }
        return dsl.fetchExists(dsl.selectOne().from(STATUSES).where(condition))
    }

    /** 이 상태를 편성한 워크플로우 수. 0 이어야 지울 수 있다. */
    fun countWorkflowUsage(statusId: UUID): Int =
        dsl
            .selectCount()
            .from(WORKFLOW_STATUSES)
            .where(WORKFLOW_STATUSES.STATUS_ID.eq(statusId))
            .fetchOne(0, Int::class.java) ?: 0

    /**
     * 이 상태를 편성한 워크플로우의 key 목록.
     *
     * ### 왜 역인덱스를 캐시에 심지 않는가
     * `WorkflowCache` 는 `key → Workflow` 맵 하나뿐이다. 상태→워크플로우 역인덱스를 거기 새로 두면
     * 편성이 바뀔 때마다 갈라지는 **두 번째 리스트**가 된다. DB 가 이미 그 관계의 정본이므로
     * 무효화 대상은 그때그때 역조회로 얻는다.
     */
    fun findWorkflowKeysUsing(statusId: UUID): List<String> =
        dsl
            .select(WORKFLOWS.KEY)
            .from(WORKFLOW_STATUSES)
            .join(WORKFLOWS)
            .on(WORKFLOWS.ID.eq(WORKFLOW_STATUSES.WORKFLOW_ID))
            .where(WORKFLOW_STATUSES.STATUS_ID.eq(statusId))
            .and(WORKFLOWS.DELETED_AT.isNull)
            .fetch(WORKFLOWS.KEY)
            .filterNotNull()

    /** 상태를 만든다. `is_system` 은 항상 false — 시스템 예약은 마이그레이션만 부여한다. */
    fun insert(
        key: String,
        name: String,
        description: String?,
        category: String,
    ): UUID =
        dsl
            .insertInto(STATUSES)
            .set(STATUSES.KEY, key)
            .set(STATUSES.NAME, name)
            .set(STATUSES.DESCRIPTION, description)
            .set(STATUSES.CATEGORY, category)
            .returning(STATUSES.ID)
            .fetchOne(STATUSES.ID)!!

    /** 이름·설명·카테고리를 고친다. `key` 는 건드리지 않는다. */
    fun update(
        id: UUID,
        name: String,
        description: String?,
        category: String,
    ) {
        dsl
            .update(STATUSES)
            .set(STATUSES.NAME, name)
            .set(STATUSES.DESCRIPTION, description)
            .set(STATUSES.CATEGORY, category)
            .set(STATUSES.UPDATED_AT, OffsetDateTime.now())
            .where(STATUSES.ID.eq(id))
            .execute()
    }

    /** 소프트 삭제. 행을 지우지 않는다(`DATA.md §1.2`). */
    fun softDelete(id: UUID) {
        dsl
            .update(STATUSES)
            .set(STATUSES.DELETED_AT, OffsetDateTime.now())
            .set(STATUSES.UPDATED_AT, OffsetDateTime.now())
            .where(STATUSES.ID.eq(id))
            .execute()
    }

    private fun org.jooq.Record.toRow(): StatusRow =
        StatusRow(
            id = this[STATUSES.ID]!!,
            key = this[STATUSES.KEY]!!,
            name = this[STATUSES.NAME]!!,
            description = this[STATUSES.DESCRIPTION],
            category = this[STATUSES.CATEGORY]!!,
            isSystem = this[STATUSES.IS_SYSTEM]!!,
        )
}
