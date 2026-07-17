// 스킴-이슈타입 매핑 Repository — jOOQ DSL 기반 CRUD, partial UNIQUE INDEX 위반 → MappingDuplicateException 변환

package com.bts.workflow.scheme.repository

import com.bts.shared.issue.IssueTypeId
import com.bts.workflow.jooq.tables.Workflows.Companion.WORKFLOWS
import com.bts.workflow.scheme.domain.SchemeIssueTypeMapping
import com.bts.workflow.scheme.domain.WorkflowSchemeId
import com.bts.workflow.scheme.exception.MappingDefaultDuplicateException
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
@Suppress("PropertyName", "VariableNaming") // jOOQ 필드 상수 — SQL 컬럼명 매칭 (UPPER_SNAKE_CASE). codegen 도입 시 typed table 로 교체 예정.
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

    // workflow_schemes 테이블도 workflow_scheme_issue_type_mappings 와 마찬가지로 jOOQ codegen 범위 밖 —
    // repairDefaultMappings 에서 스킴 key → id 조회용으로만 사용한다 (untyped 참조).
    private val SCHEME_TABLE = DSL.table("workflow_schemes")
    private val SCHEME_ID = DSL.field("workflow_schemes.id", Long::class.java)
    private val SCHEME_KEY = DSL.field("workflow_schemes.key", String::class.java)

    // 표준 스킴 key → 표준 워크플로우 key 매핑 (V201:126-130 seed CASE 문과 동일).
    private val defaultWorkflowKeyBySchemeKey =
        mapOf(
            "software-scheme" to "software-default",
            "bug-tracking-scheme" to "bug-tracking",
            "simple-scheme" to "simple",
            "kanban-scheme" to "kanban-basic",
        )

    /**
     * 매핑을 저장하고 DB 에서 할당된 id 를 포함한 [SchemeIssueTypeMapping] 을 반환한다.
     *
     * @param mapping 저장할 매핑. [SchemeIssueTypeMapping.id] 는 null 이어야 한다 (신규 삽입).
     * @return id 가 할당된 [SchemeIssueTypeMapping].
     * @throws MappingDuplicateException (scheme_id, issue_type_id) UNIQUE 또는
     *   ix_scheme_default_mapping partial UNIQUE INDEX 위반 시.
     * @suppress InstanceOfCheckForException — jOOQ native DataAccessException 의 cause chain 에서
     * SQLException(sqlState=23505) 을 판별하는 unwrap 패턴. jOOQ DataAccessException API 가
     * sqlState 를 직접 노출하지 않으므로 cause traversal 이 불가피하다.
     */
    @Suppress("InstanceOfCheckForException")
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
            throw translateUniqueViolation(ex, mapping, context = "Spring 변환")
        } catch (ex: org.jooq.exception.DataAccessException) {
            // Spring ExceptionTranslator 비활성 컨텍스트 (예: 통합 테스트의 plain DSL.using()) —
            // jOOQ native DataAccessException 으로 도착. cause SQLException 의 sqlstate "23505"
            // (unique_violation) 인 경우만 예외 분기 변환.
            val sqlEx =
                generateSequence(ex as Throwable?) { it.cause }
                    .filterIsInstance<java.sql.SQLException>()
                    .firstOrNull()
            if (sqlEx?.sqlState == "23505") {
                throw translateUniqueViolation(ex, mapping, context = "jOOQ native")
            }
            throw ex
        }
    }

    /**
     * UNIQUE 위반 예외에서 violated constraint name 을 읽어 도메인 예외로 변환한다.
     *
     * Spring 변환 경로([DataIntegrityViolationException])와 jOOQ native 경로
     * ([org.jooq.exception.DataAccessException]) 양쪽에서 공유하는 변환 로직.
     * cause chain 에서 [org.postgresql.util.PSQLException] 을 찾아 constraint name 을 추출한다.
     *
     * @param ex 원본 예외 (Throwable 로 받아 두 경로 모두 처리).
     * @param mapping INSERT 를 시도한 매핑 (로그/에러 메시지용).
     * @param context 로그 구분 레이블 ("Spring 변환" / "jOOQ native").
     * @return 변환된 도메인 예외 ([MappingDefaultDuplicateException] 또는 [MappingDuplicateException]).
     */
    private fun translateUniqueViolation(
        ex: Throwable,
        mapping: SchemeIssueTypeMapping,
        context: String,
    ): RuntimeException {
        val constraintName =
            generateSequence(ex) { it.cause }
                .filterIsInstance<org.postgresql.util.PSQLException>()
                .firstOrNull()
                ?.serverErrorMessage?.constraint
        if (constraintName == "ix_scheme_default_mapping") {
            log.warn("addMapping default mapping 중복 ({}) — scheme_id={}", context, mapping.schemeId.value)
            return MappingDefaultDuplicateException(schemeKey = mapping.schemeId.value.toString())
        }
        log.warn(
            "addMapping UNIQUE 위반 ({}) — scheme_id={} issue_type_id={}",
            context,
            mapping.schemeId.value,
            mapping.issueTypeId?.value,
        )
        return MappingDuplicateException(
            schemeKey = mapping.schemeId.value.toString(),
            issueTypeKey = mapping.issueTypeId?.value?.toString() ?: "null(default)",
        )
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
     * 이슈 타입 키로 [IssueTypeId] 를 조회한다.
     *
     * project-workflow BC 가 issue-tracking BC 의 내부 Repository 를 직접 import 하지 않도록
     * `issue_types` 테이블을 이 Repository 에서 읽기 전용으로 접근한다 (NFR-7 BC 격리 준수).
     *
     * @param issueTypeKey 조회할 이슈 타입 키 (예: "bug", "story").
     * @return 해당 키의 [IssueTypeId], 존재하지 않으면 null.
     */
    @Transactional(readOnly = true)
    fun findIssueTypeIdByKey(issueTypeKey: String): IssueTypeId? {
        val ISSUE_TYPES = DSL.table("issue_types")
        val IT_ID = DSL.field("issue_types.id", Long::class.java)
        val IT_KEY = DSL.field("issue_types.key", String::class.java)

        return dsl
            .select(IT_ID)
            .from(ISSUE_TYPES)
            .where(IT_KEY.eq(issueTypeKey))
            .fetchOne()
            ?.let { rec ->
                val rawId = rec.get(IT_ID) ?: return null
                IssueTypeId(rawId)
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

    /**
     * 표준 4 스킴의 default mapping(issue_type_id IS NULL) 을 복구한다.
     *
     * R6(빈 DB 백필)와 R6-B(YAML 재시드로 workflow UUID 가 바뀐 뒤 dangling 매핑 수리) 를 공통 처리한다.
     * 두 부분으로 구성된다.
     * 1. dangling 수리 — workflow_id 가 더 이상 workflows 에 존재하지 않는(옛 UUID) default mapping 을
     *    현재 유효한 workflow id 로 갱신한다.
     * 2. 없는 것만 insert — 아직 default mapping 이 없는 스킴에 한해 신규 생성한다.
     *
     * admin 이 REST 로 변경한 유효한 default mapping 은 두 조건 모두 해당하지 않으므로 그대로 보존된다.
     */
    @Transactional
    fun repairDefaultMappings() {
        defaultWorkflowKeyBySchemeKey.forEach { (schemeKey, workflowKey) ->
            val schemeId = findSchemeIdByKey(schemeKey) ?: return@forEach
            val workflowId = findWorkflowIdByKey(workflowKey) ?: return@forEach

            repairDanglingDefaultMapping(schemeId, workflowId)
            insertMissingDefaultMapping(schemeId, workflowId)
        }
    }

    private fun findSchemeIdByKey(schemeKey: String): Long? =
        dsl
            .select(SCHEME_ID)
            .from(SCHEME_TABLE)
            .where(SCHEME_KEY.eq(schemeKey))
            .fetchOne(SCHEME_ID)

    private fun findWorkflowIdByKey(workflowKey: String): UUID? =
        dsl
            .select(WORKFLOWS.ID)
            .from(WORKFLOWS)
            .where(WORKFLOWS.KEY.eq(workflowKey))
            .fetchOne(WORKFLOWS.ID)

    /** workflow_id 가 더 이상 workflows 에 존재하지 않는(dangling) default mapping 을 유효한 workflowId 로 갱신한다. */
    private fun repairDanglingDefaultMapping(
        schemeId: Long,
        workflowId: UUID,
    ) {
        val updated =
            dsl
                .update(TABLE)
                .set(COL_WORKFLOW_ID, workflowId)
                .where(COL_SCHEME_ID.eq(schemeId))
                .and(COL_ISSUE_TYPE_ID.isNull)
                .and(COL_WORKFLOW_ID.notIn(DSL.select(WORKFLOWS.ID).from(WORKFLOWS)))
                .execute()
        if (updated > 0) {
            log.info("repairDefaultMappings dangling 매핑 수리 — scheme_id={} workflow_id={}", schemeId, workflowId)
        }
    }

    /** 스킴에 default mapping 이 없는 경우에만 신규 생성한다. 이미 있으면(admin 설정 포함) 무변경. */
    private fun insertMissingDefaultMapping(
        schemeId: Long,
        workflowId: UUID,
    ) {
        val alreadyExists =
            dsl.fetchExists(
                DSL
                    .selectOne()
                    .from(TABLE)
                    .where(COL_SCHEME_ID.eq(schemeId))
                    .and(COL_ISSUE_TYPE_ID.isNull),
            )
        if (alreadyExists) return

        dsl
            .insertInto(TABLE)
            .columns(COL_SCHEME_ID, COL_ISSUE_TYPE_ID, COL_WORKFLOW_ID)
            .values(schemeId, null, workflowId)
            .execute()
        log.info("repairDefaultMappings default 매핑 백필 — scheme_id={} workflow_id={}", schemeId, workflowId)
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
