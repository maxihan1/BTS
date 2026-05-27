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
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID

/**
 * 워크플로우 스킴-이슈타입 매핑 Repository.
 *
 * `workflow_scheme_issue_type_mappings` 테이블에 대한 CRUD 를 담당한다.
 * V004 마이그레이션으로 생성된 테이블이며 jOOQ codegen 범위(V001 only) 밖이므로
 * `DSL.table()` / `DSL.field()` 동적 참조 방식을 사용한다.
 * Wave-2 종료 후 generateJooq 태스크를 재실행하면 generated 클래스로 교체 가능.
 *
 * ## 제약 처리
 * - `uq_scheme_issue_type` UNIQUE constraint — (scheme_id, issue_type_id) 중복 시
 *   [DataIntegrityViolationException] → [MappingDuplicateException] 변환.
 * - `ix_scheme_default_mapping` partial UNIQUE INDEX — (scheme_id) WHERE issue_type_id IS NULL 중복 시
 *   동일 변환. PostgreSQL 에서 NULL ≠ NULL 로 UNIQUE constraint 만으로는 default mapping 중복 방지
 *   불가하므로 partial UNIQUE INDEX 로 보완한 구조를 Repository 에서 동일하게 처리한다.
 *
 * @property dsl jOOQ DSLContext (SQL을 코드로 안전하게 작성하는 라이브러리의 핵심 진입점).
 */
@Repository
@Suppress("PropertyName") // jOOQ 필드 상수 — SQL 컬럼명 매칭 (UPPER_SNAKE_CASE). codegen 도입 시 typed table 로 교체 예정.
class SchemeIssueTypeMappingRepository(private val dsl: DSLContext) {
    private val log = LoggerFactory.getLogger(javaClass)

    // V004 테이블은 jOOQ codegen 범위(V001 only) 밖 — DSL.table()/DSL.field() 동적 참조 사용.
    // T14 ProjectWorkflowSchemeAssignmentRepository 와 동일 패턴.
    private val TABLE = DSL.table("workflow_scheme_issue_type_mappings")
    private val COL_ID = DSL.field("id", Long::class.java)
    private val COL_SCHEME_ID = DSL.field("scheme_id", Long::class.java)
    private val COL_ISSUE_TYPE_ID = DSL.field("issue_type_id", Long::class.java)
    private val COL_WORKFLOW_ID = DSL.field("workflow_id", UUID::class.java)

    // TIMESTAMPTZ → jOOQ 는 OffsetDateTime 으로 읽음. toInstant() 변환은 toMapping() 에서 처리.
    private val COL_CREATED_AT = DSL.field("created_at", OffsetDateTime::class.java)

    /**
     * 매핑을 저장하고 DB 에서 할당된 id 를 포함한 [SchemeIssueTypeMapping] 을 반환한다.
     *
     * @param mapping 저장할 매핑. [SchemeIssueTypeMapping.id] 는 null 이어야 한다 (신규 삽입).
     * @return id 가 할당된 [SchemeIssueTypeMapping].
     * @throws MappingDuplicateException (scheme_id, issue_type_id) UNIQUE 또는
     *   ix_scheme_default_mapping partial UNIQUE INDEX 위반 시.
     */
    @Transactional
    fun addMapping(mapping: SchemeIssueTypeMapping): SchemeIssueTypeMapping {
        try {
            val record =
                dsl
                    .insertInto(TABLE)
                    .columns(COL_SCHEME_ID, COL_ISSUE_TYPE_ID, COL_WORKFLOW_ID)
                    .values(
                        mapping.schemeId.value,
                        mapping.issueTypeId?.value,
                        mapping.workflowId,
                    )
                    .returningResult(COL_ID, COL_SCHEME_ID, COL_ISSUE_TYPE_ID, COL_WORKFLOW_ID, COL_CREATED_AT)
                    .fetchOne()
                    ?: error("INSERT RETURNING 결과가 없습니다 — 예기치 않은 상태")

            return record.toMapping()
        } catch (ex: DataIntegrityViolationException) {
            // Spring 의 ExceptionTranslator 가 활성화된 컨텍스트 (운영) — DataIntegrityViolationException 으로 도착.
            log.warn(
                "addMapping UNIQUE 위반 (Spring 변환) — scheme_id={} issue_type_id={}",
                mapping.schemeId.value,
                mapping.issueTypeId?.value,
            )
            throw MappingDuplicateException(
                schemeKey = mapping.schemeId.value.toString(),
                issueTypeKey = mapping.issueTypeId?.value?.toString() ?: "null(default)",
            )
        } catch (ex: org.jooq.exception.DataAccessException) {
            // Spring ExceptionTranslator 비활성 컨텍스트 (예: 통합 테스트의 plain DSL.using()) —
            // jOOQ native DataAccessException 으로 도착. cause SQLException 의 sqlstate "23505"
            // (unique_violation) 인 경우만 MappingDuplicateException 변환.
            val sqlEx =
                generateSequence(ex as Throwable?) { it.cause }
                    .firstOrNull { it is java.sql.SQLException } as? java.sql.SQLException
            if (sqlEx?.sqlState == "23505") {
                log.warn(
                    "addMapping UNIQUE 위반 (jOOQ native) — scheme_id={} issue_type_id={}",
                    mapping.schemeId.value,
                    mapping.issueTypeId?.value,
                )
                throw MappingDuplicateException(
                    schemeKey = mapping.schemeId.value.toString(),
                    issueTypeKey = mapping.issueTypeId?.value?.toString() ?: "null(default)",
                )
            }
            throw ex
        }
    }

    /**
     * 특정 스킴에 속한 모든 매핑을 반환한다.
     *
     * @param schemeId 조회할 스킴 식별자.
     * @return 해당 스킴의 매핑 목록. 매핑이 없으면 빈 목록.
     */
    @Transactional(readOnly = true)
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
     * `ix_scheme_default_mapping` partial UNIQUE INDEX 에 의해 스킴당 최대 1건이 보장된다.
     *
     * @param schemeId 조회할 스킴 식별자.
     * @return default mapping, 없으면 null.
     */
    @Transactional(readOnly = true)
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
    @Transactional(readOnly = true)
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
     * 스킴 내 특정 이슈 타입 **키** 에 대한 매핑을 반환한다.
     *
     * WorkflowResolverImpl 에서 issueTypeKey(String) → mapping 변환에 사용한다.
     * application layer 가 issue-tracking BC 의 IssueTypeId 를 직접 조회하지 않도록
     * Repository 내부에서 issue_types JOIN 을 수행한다 (BC 격리 컨벤션 준수).
     *
     * issue_types 테이블은 issue-tracking BC 소유이므로 읽기 전용 JOIN 만 허용한다.
     * 쓰기(INSERT/UPDATE/DELETE) 금지.
     *
     * @param schemeId 조회할 스킴 식별자.
     * @param issueTypeKey 조회할 이슈 타입 키 (예: "story", "bug").
     * @return 해당 이슈 타입 키의 매핑, 없으면 null.
     */
    @Transactional(readOnly = true)
    fun findByIssueTypeKey(
        schemeId: WorkflowSchemeId,
        issueTypeKey: String,
    ): SchemeIssueTypeMapping? {
        val ISSUE_TYPES = DSL.table("issue_types")
        val IT_ID = DSL.field("issue_types.id", Long::class.java)
        val IT_KEY = DSL.field("issue_types.key", String::class.java)

        // JOIN 시 COL_ID(= "id") 가 양 테이블에 모두 있어 ambiguous — 테이블 한정자 명시.
        val M_ID = DSL.field("workflow_scheme_issue_type_mappings.id", Long::class.java)
        val M_SCHEME_ID = DSL.field("workflow_scheme_issue_type_mappings.scheme_id", Long::class.java)
        val M_ISSUE_TYPE_ID = DSL.field("workflow_scheme_issue_type_mappings.issue_type_id", Long::class.java)
        val M_WORKFLOW_ID = DSL.field("workflow_scheme_issue_type_mappings.workflow_id", UUID::class.java)
        val M_CREATED_AT = DSL.field("workflow_scheme_issue_type_mappings.created_at", OffsetDateTime::class.java)

        return dsl
            .select(M_ID, M_SCHEME_ID, M_ISSUE_TYPE_ID, M_WORKFLOW_ID, M_CREATED_AT)
            .from(TABLE)
            .join(ISSUE_TYPES).on(COL_ISSUE_TYPE_ID.eq(IT_ID))
            .where(M_SCHEME_ID.eq(schemeId.value))
            .and(IT_KEY.eq(issueTypeKey))
            .fetchOne()
            ?.let { rec ->
                val rawIssueTypeId = rec.get(M_ISSUE_TYPE_ID)
                val createdAtOdt = rec.get(M_CREATED_AT) ?: error("created_at is null — DB NOT NULL 제약 위반")
                SchemeIssueTypeMapping(
                    id = rec.get(M_ID) ?: error("id is null — DB BIGSERIAL 제약 위반"),
                    schemeId = WorkflowSchemeId(rec.get(M_SCHEME_ID) ?: error("scheme_id is null")),
                    issueTypeId = rawIssueTypeId?.let { IssueTypeId(it) },
                    workflowId = rec.get(M_WORKFLOW_ID) ?: error("workflow_id is null"),
                    createdAt = createdAtOdt.toInstant(),
                )
            }
    }

    /**
     * 매핑을 삭제한다.
     *
     * 존재하지 않는 id 에 대해 예외 없이 no-op 으로 처리한다.
     *
     * @param id 삭제할 매핑의 PK.
     */
    @Transactional
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
     * COL_CREATED_AT 은 TIMESTAMPTZ → jOOQ OffsetDateTime → Instant 변환 (DATA.md §4 TIMESTAMPTZ 강제).
     */
    private fun org.jooq.Record.toMapping(): SchemeIssueTypeMapping {
        val rawIssueTypeId = get(COL_ISSUE_TYPE_ID)
        val createdAtOdt =
            get(COL_CREATED_AT) ?: error("created_at is null — DB NOT NULL 제약 위반")
        return SchemeIssueTypeMapping(
            id = get(COL_ID) ?: error("id is null — DB BIGSERIAL 제약 위반"),
            schemeId = WorkflowSchemeId(get(COL_SCHEME_ID) ?: error("scheme_id is null — DB NOT NULL 제약 위반")),
            issueTypeId = rawIssueTypeId?.let { IssueTypeId(it) },
            workflowId = get(COL_WORKFLOW_ID) ?: error("workflow_id is null — DB NOT NULL 제약 위반"),
            createdAt = createdAtOdt.toInstant(),
        )
    }
}
