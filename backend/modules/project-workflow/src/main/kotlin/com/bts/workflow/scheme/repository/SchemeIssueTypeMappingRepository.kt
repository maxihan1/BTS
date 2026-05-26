// 스킴-이슈타입 매핑 Repository — jOOQ DSL 기반 CRUD, partial UNIQUE INDEX 위반 → MappingDuplicateException 변환

package com.bts.workflow.scheme.repository

import com.bts.issue.type.domain.IssueTypeId
import com.bts.workflow.scheme.domain.SchemeIssueTypeMapping
import com.bts.workflow.scheme.domain.WorkflowSchemeId
import com.bts.workflow.scheme.exception.MappingDuplicateException
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

/**
 * 워크플로우 스킴-이슈타입 매핑 Repository.
 *
 * `workflow_scheme_issue_type_mappings` 테이블에 대한 CRUD 를 담당한다.
 * V004 마이그레이션으로 생성된 테이블이며 jOOQ codegen 범위(V001 only) 밖이므로
 * `DSL.table()` / `DSL.field()` 동적 참조 방식을 사용한다.
 *
 * ## 제약 처리
 * - `uq_scheme_issue_type` UNIQUE constraint — (scheme_id, issue_type_id) 중복 시 [DataIntegrityViolationException] → [MappingDuplicateException] 변환.
 * - `ix_scheme_default_mapping` partial UNIQUE INDEX — (scheme_id) WHERE issue_type_id IS NULL 중복 시 동일 변환.
 *
 * @property dsl jOOQ DSLContext (SQL을 코드로 안전하게 작성하는 라이브러리의 핵심 진입점).
 */
@Repository
class SchemeIssueTypeMappingRepository(private val dsl: DSLContext) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 매핑을 저장하고 DB 에서 할당된 id 를 포함한 [SchemeIssueTypeMapping] 을 반환한다.
     *
     * @param mapping 저장할 매핑. [SchemeIssueTypeMapping.id] 는 null 이어야 한다 (신규 삽입).
     * @return id 가 할당된 [SchemeIssueTypeMapping].
     * @throws MappingDuplicateException (scheme_id, issue_type_id) UNIQUE 또는 partial UNIQUE INDEX 위반 시.
     */
    fun addMapping(mapping: SchemeIssueTypeMapping): SchemeIssueTypeMapping {
        try {
            val record =
                dsl
                    .insertInto(TABLE)
                    .columns(COL_SCHEME_ID, COL_ISSUE_TYPE_ID, COL_WORKFLOW_ID, COL_CREATED_AT)
                    .values(
                        mapping.schemeId.value,
                        mapping.issueTypeId?.value,
                        mapping.workflowId,
                        mapping.createdAt,
                    )
                    .returningResult(COL_ID, COL_SCHEME_ID, COL_ISSUE_TYPE_ID, COL_WORKFLOW_ID, COL_CREATED_AT)
                    .fetchOne()
                    ?: error("INSERT RETURNING 결과가 없습니다 — 예기치 않은 상태")

            return record.toMapping()
        } catch (ex: DataIntegrityViolationException) {
            log.warn(
                "addMapping UNIQUE 위반 — scheme_id={} issue_type_id={}",
                mapping.schemeId.value,
                mapping.issueTypeId?.value,
            )
            throw MappingDuplicateException(
                schemeKey = mapping.schemeId.value.toString(),
                issueTypeKey = mapping.issueTypeId?.value?.toString() ?: "null(default)",
            )
        }
    }

    /**
     * 특정 스킴에 속한 모든 매핑을 반환한다.
     *
     * @param schemeId 조회할 스킴 식별자.
     * @return 해당 스킴의 매핑 목록. 매핑이 없으면 빈 목록.
     */
    fun findBySchemeId(schemeId: WorkflowSchemeId): List<SchemeIssueTypeMapping> =
        dsl
            .select(COL_ID, COL_SCHEME_ID, COL_ISSUE_TYPE_ID, COL_WORKFLOW_ID, COL_CREATED_AT)
            .from(TABLE)
            .where(COL_SCHEME_ID.eq(schemeId.value))
            .fetch()
            .map { it.toMapping() }

    /**
     * 스킴의 default mapping (issue_type_id IS NULL) 을 반환한다.
     *
     * Jira `workflowschemeentity.issuetype = NULL` 패턴 align.
     * 명시적으로 매핑되지 않은 이슈 타입 전부에 적용되는 워크플로우를 가리킨다.
     *
     * @param schemeId 조회할 스킴 식별자.
     * @return default mapping, 없으면 null.
     */
    fun findDefaultMapping(schemeId: WorkflowSchemeId): SchemeIssueTypeMapping? =
        dsl
            .select(COL_ID, COL_SCHEME_ID, COL_ISSUE_TYPE_ID, COL_WORKFLOW_ID, COL_CREATED_AT)
            .from(TABLE)
            .where(COL_SCHEME_ID.eq(schemeId.value))
            .and(COL_ISSUE_TYPE_ID.isNull)
            .fetchOne()
            ?.toMapping()

    /**
     * 스킴 내 특정 이슈 타입에 대한 매핑을 반환한다.
     *
     * @param schemeId 조회할 스킴 식별자.
     * @param issueTypeId 조회할 이슈 타입 식별자.
     * @return 해당 이슈 타입의 매핑, 없으면 null.
     */
    fun findByIssueType(
        schemeId: WorkflowSchemeId,
        issueTypeId: IssueTypeId,
    ): SchemeIssueTypeMapping? =
        dsl
            .select(COL_ID, COL_SCHEME_ID, COL_ISSUE_TYPE_ID, COL_WORKFLOW_ID, COL_CREATED_AT)
            .from(TABLE)
            .where(COL_SCHEME_ID.eq(schemeId.value))
            .and(COL_ISSUE_TYPE_ID.eq(issueTypeId.value))
            .fetchOne()
            ?.toMapping()

    /**
     * 매핑을 삭제한다.
     *
     * 존재하지 않는 id 에 대해 예외 없이 no-op 처리한다.
     *
     * @param id 삭제할 매핑의 PK.
     */
    fun deleteMapping(id: Long) {
        dsl
            .deleteFrom(TABLE)
            .where(COL_ID.eq(id))
            .execute()
    }

    // ── 내부 변환 ───────────────────────────────────────────────────────────────

    /**
     * jOOQ Record → [SchemeIssueTypeMapping] 변환.
     *
     * COL_ISSUE_TYPE_ID 가 NULL 인 경우 issueTypeId = null (default mapping).
     * COL_WORKFLOW_ID 는 UUID 타입으로 캐스팅한다 (workflows.id UUID).
     */
    private fun org.jooq.Record.toMapping(): SchemeIssueTypeMapping {
        val rawIssueTypeId = get(COL_ISSUE_TYPE_ID)
        return SchemeIssueTypeMapping(
            id = get(COL_ID) as Long,
            schemeId = WorkflowSchemeId(get(COL_SCHEME_ID) as Long),
            issueTypeId = rawIssueTypeId?.let { IssueTypeId(it as Long) },
            workflowId = get(COL_WORKFLOW_ID) as UUID,
            createdAt = get(COL_CREATED_AT) as Instant,
        )
    }

    companion object {
        // V004 테이블은 jOOQ codegen 범위(V001 only) 밖 — DSL.table()/DSL.field() 동적 참조 사용.
        private val TABLE = DSL.table("workflow_scheme_issue_type_mappings")
        private val COL_ID = DSL.field("id", Long::class.java)
        private val COL_SCHEME_ID = DSL.field("scheme_id", Long::class.java)
        private val COL_ISSUE_TYPE_ID = DSL.field("issue_type_id", Long::class.java)
        private val COL_WORKFLOW_ID = DSL.field("workflow_id", UUID::class.java)
        private val COL_CREATED_AT = DSL.field("created_at", Instant::class.java)
    }
}
