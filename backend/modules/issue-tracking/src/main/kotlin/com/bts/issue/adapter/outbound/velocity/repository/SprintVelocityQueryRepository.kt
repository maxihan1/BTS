// 스프린트 벨로시티 jOOQ 조회 리포지토리 — ISSUES 스칼라 컬럼 단일 조회 (FR-RP-02 Task 6 — ArchUnit 룰2 준수 위한 jOOQ 추출)

package com.bts.issue.adapter.outbound.velocity.repository

import com.bts.issue.jooq.tables.references.ISSUES
import org.jooq.DSLContext
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

/**
 * [com.bts.issue.adapter.outbound.velocity.SprintVelocityLookupAdapter] 전용 jOOQ 조회 리포지토리 (FR-RP-02 Task 6).
 *
 * ArchUnit 룰 2([com.bts.issue.architecture.IssueBcArchTest.jooqGeneratedMustOnlyBeUsedInRepositoryLayer]) —
 * jOOQ 생성 코드(`com.bts.issue.jooq..`)는 `..repository..` 패키지에서만 접촉 가능해야 하므로,
 * 어댑터에 있던 [ISSUES] 스칼라 컬럼 조회를 이 클래스로 추출했다(동작 변경 없음, 순수 이동).
 *
 * @param dsl jOOQ DSLContext.
 */
@Repository
class SprintVelocityQueryRepository(
    private val dsl: DSLContext,
) {
    /** 가시 이슈들의 (키, 추정 시간, 현재 상태 키, 타입 id) 를 스칼라 컬럼만으로 단일 조회한다 (cartesian 위험 없음). */
    @Transactional(readOnly = true)
    fun fetchVelocityRows(issueKeys: Set<String>): List<VelocityRow> =
        dsl
            .select(ISSUES.KEY, ISSUES.ORIGINAL_ESTIMATE_SECONDS, ISSUES.CURRENT_STATE_KEY, ISSUES.TYPE_ID)
            .from(ISSUES)
            .where(ISSUES.KEY.`in`(issueKeys))
            .and(ISSUES.DELETED_AT.isNull)
            .fetch { record ->
                VelocityRow(
                    issueKey = record.get(ISSUES.KEY) ?: error("issues.key must not be null"),
                    estimateSeconds = record.get(ISSUES.ORIGINAL_ESTIMATE_SECONDS)?.toLong() ?: 0L,
                    currentStateKey =
                        record.get(ISSUES.CURRENT_STATE_KEY)
                            ?: error("issues.current_state_key must not be null"),
                    typeId = record.get(ISSUES.TYPE_ID) ?: error("issues.type_id must not be null"),
                )
            }
}

/** [SprintVelocityQueryRepository.fetchVelocityRows] 조회 결과 1행. */
data class VelocityRow(
    val issueKey: String,
    val estimateSeconds: Long,
    val currentStateKey: String,
    val typeId: Long,
)
