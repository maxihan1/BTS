// WorkflowSchemeRepository — workflow_schemes 테이블 jOOQ CRUD
// (save/findByKey/softDelete/findAll/countAssignedProjects/findAllWithCounts)

package com.bts.workflow.scheme.repository

import com.bts.workflow.scheme.domain.WorkflowScheme
import com.bts.workflow.scheme.domain.WorkflowSchemeId
import com.bts.workflow.scheme.domain.WorkflowSchemeKey
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.jooq.impl.DSL.field
import org.jooq.impl.DSL.name
import org.jooq.impl.DSL.table
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * 워크플로우 스킴에 대한 프로젝트 수 + 매핑 수 카운트를 담은 결과 행.
 *
 * [WorkflowSchemeRepository.findAllWithCounts] 가 반환한다.
 *
 * @property scheme 대상 스킴.
 * @property usedByProjectsCount 이 스킴을 사용 중인 프로젝트 수 (project_workflow_scheme_assignments JOIN).
 * @property mappingsCount 이 스킴에 등록된 이슈타입-워크플로우 매핑 수 (workflow_scheme_issue_type_mappings JOIN).
 */
data class SchemeCountRow(
    val scheme: WorkflowScheme,
    val usedByProjectsCount: Long,
    val mappingsCount: Long,
)

/**
 * 워크플로우 스킴 Repository.
 *
 * jOOQ DSLContext 를 통해 `workflow_schemes` 테이블에 접근한다.
 * V004 마이그레이션 이후 jOOQ codegen 이 `WorkflowSchemes` 테이블 클래스를 생성하면
 * generated 참조로 대체 예정 (REFACTOR 단계에서 codegen 트리거 포함).
 *
 * ## 설계 결정
 *
 * - softDelete: `deleted_at` 컬럼을 `NOW()` 로 SET. 물리 삭제 없음 (DATA.md §3).
 * - findByKey / findAll: `WHERE deleted_at IS NULL` 필터로 활성 스킴만 반환.
 * - save: `RETURNING id` 로 DB 생성 id 를 즉시 반환 — `WorkflowSchemeId` 할당.
 *
 * @param dsl jOOQ DSL 컨텍스트 (SQL 을 코드로 안전하게 작성하는 라이브러리의 핵심 진입점).
 */
@Repository
// jOOQ 필드 상수 — SQL 컬럼명 매칭 (UPPER_SNAKE_CASE). codegen 도입 시 typed table 로 교체 예정.
// TooManyFunctions — 스킴 CRUD 6 + 카운트 2 + 소유 스코프 조회 1 + 변환 2. 한 테이블의 접근 경로라
// 쪼개면 트랜잭션 경계와 컬럼 참조 상수가 두 곳으로 갈린다.
@Suppress("PropertyName", "VariableNaming", "TooManyFunctions")
class WorkflowSchemeRepository(private val dsl: DSLContext) {
    private val log = LoggerFactory.getLogger(javaClass)

    // ── 테이블 / 컬럼 참조 ────────────────────────────────────────────────────────
    // jOOQ codegen 이 workflow_schemes 클래스를 생성하기 전까지 DSL.table/field 로 참조.
    // REFACTOR 단계에서 V004 포함 codegen 재실행 후 generated 참조로 교체.
    private val WORKFLOW_SCHEMES = table(name("workflow_schemes"))
    private val ID = field(name("id"), Long::class.java)
    private val KEY = field(name("key"), String::class.java)
    private val NAME = field(name("name"), String::class.java)
    private val DESCRIPTION = field(name("description"), String::class.java)
    private val IS_DEFAULT = field(name("is_default"), Boolean::class.java)

    // FR-WF-08 소유 프로젝트 — NULL = 전역 템플릿. V209.
    private val PROJECT_ID = field(name("project_id"), UUID::class.java)
    private val CREATED_AT = field(name("created_at"), OffsetDateTime::class.java)
    private val UPDATED_AT = field(name("updated_at"), OffsetDateTime::class.java)
    private val DELETED_AT = field(name("deleted_at"), OffsetDateTime::class.java)

    // 테이블명으로 한정한 컬럼 참조 — findAllWithCounts 가 JOIN·서브쿼리를 함께 쓰므로 필요하다.
    // toCountRow 가 같은 참조를 봐야 해서 메서드 지역이 아니라 클래스 수준에 둔다.
    private val WS_ID = field(name("workflow_schemes", "id"), Long::class.java)
    private val WS_KEY = field(name("workflow_schemes", "key"), String::class.java)
    private val WS_NAME = field(name("workflow_schemes", "name"), String::class.java)
    private val WS_DESCRIPTION = field(name("workflow_schemes", "description"), String::class.java)
    private val WS_IS_DEFAULT = field(name("workflow_schemes", "is_default"), Boolean::class.java)
    private val WS_PROJECT_ID = field(name("workflow_schemes", "project_id"), UUID::class.java)
    private val WS_CREATED_AT = field(name("workflow_schemes", "created_at"), OffsetDateTime::class.java)
    private val WS_UPDATED_AT = field(name("workflow_schemes", "updated_at"), OffsetDateTime::class.java)
    private val WS_DELETED_AT = field(name("workflow_schemes", "deleted_at"), OffsetDateTime::class.java)

    /**
     * WorkflowScheme 을 INSERT 하고 DB 생성 id 가 할당된 새 인스턴스를 반환한다.
     *
     * `RETURNING id` 로 BIGSERIAL 자동 증가 id 를 즉시 획득한다.
     *
     * @param scheme 저장할 스킴. [WorkflowScheme.id] 는 null 이어야 한다 (신규 저장).
     * @return [WorkflowSchemeId] 가 할당된 인스턴스.
     */
    @Transactional
    fun save(scheme: WorkflowScheme): WorkflowScheme {
        log.debug("workflow scheme save: key={}", scheme.key.value)

        val record =
            dsl
                .insertInto(WORKFLOW_SCHEMES)
                .columns(KEY, NAME, DESCRIPTION, IS_DEFAULT, PROJECT_ID, CREATED_AT, UPDATED_AT, DELETED_AT)
                .values(
                    scheme.key.value,
                    scheme.name,
                    scheme.description,
                    scheme.isDefault,
                    scheme.projectId,
                    scheme.createdAt.toOffsetDateTime(),
                    scheme.updatedAt.toOffsetDateTime(),
                    scheme.deletedAt?.toOffsetDateTime(),
                )
                .returning(ID, KEY, NAME, DESCRIPTION, IS_DEFAULT, PROJECT_ID, CREATED_AT, UPDATED_AT, DELETED_AT)
                .fetchOne() ?: error("workflow_schemes INSERT 후 RETURNING 결과 없음 — key=${scheme.key.value}")

        return record.toWorkflowScheme()
    }

    /**
     * id 로 활성 WorkflowScheme 을 조회한다.
     *
     * `WHERE id = :id AND deleted_at IS NULL` 필터 적용.
     * T21 findAssignedScheme D10 auto-assign 흐름에서 saveAssignment 후 scheme 재조회에 사용한다.
     *
     * @param id 조회할 스킴 식별자.
     * @return 존재하는 활성 스킴, 부재 또는 soft-delete 된 경우 null.
     */
    @Transactional(readOnly = true)
    fun findById(id: WorkflowSchemeId): WorkflowScheme? {
        val record =
            dsl
                .selectFrom(WORKFLOW_SCHEMES)
                .where(ID.eq(id.value).and(DELETED_AT.isNull))
                .fetchOne()

        return record?.toWorkflowScheme()
    }

    /**
     * key 로 활성 WorkflowScheme 을 조회한다.
     *
     * `WHERE key = :key AND deleted_at IS NULL` 필터 적용.
     *
     * @param key 조회할 스킴 키.
     * @return 존재하는 활성 스킴, 부재 또는 soft-delete 된 경우 null.
     */
    @Transactional(readOnly = true)
    fun findByKey(key: WorkflowSchemeKey): WorkflowScheme? {
        val record =
            dsl
                .selectFrom(WORKFLOW_SCHEMES)
                .where(KEY.eq(key.value).and(DELETED_AT.isNull))
                .fetchOne()

        return record?.toWorkflowScheme()
    }

    /**
     * 모든 활성 WorkflowScheme 목록을 반환한다.
     *
     * `WHERE deleted_at IS NULL` 필터 적용. soft-delete 된 스킴은 제외.
     *
     * @return 활성 스킴 목록. 비어 있을 수 있음.
     */
    @Transactional(readOnly = true)
    fun findAll(): List<WorkflowScheme> =
        dsl
            .selectFrom(WORKFLOW_SCHEMES)
            .where(DELETED_AT.isNull)
            .fetch()
            .map { it.toWorkflowScheme() }

    /**
     * [projectId] 프로젝트가 쓸 수 있는 활성 스킴 목록을 반환한다 — 전역 템플릿 + 그 프로젝트 전용.
     *
     * 남의 프로젝트 전용 스킴은 제외한다. 프로젝트 설정 화면과 배정 후보 목록이 이 경로를 탄다.
     * 전역 관리자 목록([findAll])과 달리 스코프가 프로젝트 하나로 좁혀져 있다(FR-WF-08).
     *
     * @param projectId 대상 프로젝트 `projects.id`.
     * @return 전역 + 해당 프로젝트 소유 활성 스킴. 비어 있을 수 있음.
     */
    @Transactional(readOnly = true)
    fun findAllForProject(projectId: UUID): List<WorkflowScheme> =
        dsl
            .selectFrom(WORKFLOW_SCHEMES)
            .where(DELETED_AT.isNull.and(PROJECT_ID.isNull.or(PROJECT_ID.eq(projectId))))
            .fetch()
            .map { it.toWorkflowScheme() }

    /**
     * 스킴 필드를 UPDATE 한다.
     *
     * `UPDATE workflow_schemes SET name=?, description=?, is_default=?, updated_at=NOW() WHERE id=?` 실행.
     * key 는 immutable 이므로 변경 대상에서 제외한다 (EC-4 D11 표준 스킴 필드 잠금은 Application Service 계층에서 적용).
     *
     * @param scheme 갱신할 스킴. [WorkflowScheme.id] 는 non-null 이어야 한다.
     * @return 갱신된 스킴 인스턴스.
     */
    @Transactional
    fun update(scheme: WorkflowScheme): WorkflowScheme {
        val id = requireNotNull(scheme.id) { "WorkflowScheme.id must not be null for update" }
        log.debug("workflow scheme update: id={} key={}", id.value, scheme.key.value)

        val now = OffsetDateTime.now(ZoneOffset.UTC)
        dsl
            .update(WORKFLOW_SCHEMES)
            .set(NAME, scheme.name)
            .set(DESCRIPTION, scheme.description)
            .set(IS_DEFAULT, scheme.isDefault)
            .set(UPDATED_AT, now)
            .where(ID.eq(id.value))
            .execute()

        return WorkflowScheme.reconstruct(
            id = id,
            key = scheme.key,
            name = scheme.name,
            description = scheme.description,
            isDefault = scheme.isDefault,
            projectId = scheme.projectId,
            createdAt = scheme.createdAt,
            updatedAt = now.toInstant(),
            deletedAt = scheme.deletedAt,
        )
    }

    /**
     * id 에 해당하는 스킴을 soft-delete 한다.
     *
     * `UPDATE workflow_schemes SET deleted_at = NOW() WHERE id = :id` 실행.
     * 물리 삭제 없음 (DATA.md §3).
     *
     * @param id soft-delete 할 스킴 식별자.
     */
    @Transactional
    fun softDelete(id: WorkflowSchemeId) {
        log.debug("workflow scheme softDelete: id={}", id.value)

        dsl
            .update(WORKFLOW_SCHEMES)
            .set(DELETED_AT, OffsetDateTime.now(ZoneOffset.UTC))
            .where(ID.eq(id.value))
            .execute()
    }

    /**
     * 지정된 스킴을 사용 중인 프로젝트 수를 반환한다.
     *
     * `project_workflow_scheme_assignments` 테이블에서 `scheme_id = :id` 인 행 수를 COUNT 한다.
     *
     * @param id 카운트할 스킴 식별자.
     * @return 할당된 프로젝트 수. 0 이상.
     */
    @Transactional(readOnly = true)
    fun countAssignedProjects(id: WorkflowSchemeId): Long {
        val ASSIGNMENTS = table(name("project_workflow_scheme_assignments"))
        val A_WORKFLOW_SCHEME_ID = field(name("workflow_scheme_id"), Long::class.java)

        return dsl
            .selectCount()
            .from(ASSIGNMENTS)
            .where(A_WORKFLOW_SCHEME_ID.eq(id.value))
            .fetchOne()
            ?.value1()
            ?.toLong()
            ?: 0L
    }

    /**
     * 활성 스킴 전체 목록을 프로젝트 수 + 매핑 수 카운트와 함께 반환한다.
     *
     * ## 구현 방식 — 스칼라 서브쿼리 (옵션 B)
     *
     * LEFT JOIN 2회를 사용하면 두 테이블의 행이 서로 곱해지는 cartesian product 문제가 발생한다.
     * 예. 매핑 3건 + 할당 2건인 스킴 → join 후 6행 → COUNT(*) = 6 (실제값 오염).
     *
     * 대신 각 카운트를 독립 스칼라 서브쿼리로 계산한다.
     * - `project_count` : `SELECT COUNT(*) FROM project_workflow_scheme_assignments WHERE workflow_scheme_id = ws.id`
     * - `mapping_count` : `SELECT COUNT(*) FROM workflow_scheme_issue_type_mappings WHERE scheme_id = ws.id`
     *
     * 서브쿼리 방식은 cartesian product 자체가 발생하지 않고 GROUP BY 도 불필요하다.
     * jOOQ `dsl.selectCount().from(...).where(...).asField<Long>("alias")` 패턴으로 표현한다.
     *
     * @return [SchemeCountRow] 목록. 비어 있을 수 있음.
     */
    @Transactional(readOnly = true)
    fun findAllWithCounts(): List<SchemeCountRow> = fetchCountRows(DSL.trueCondition())

    /**
     * [projectId] 프로젝트의 **스킴 관리 목록**을 카운트와 함께 반환한다.
     *
     * ★ [findAllWithCounts] 는 전량을 준다. 프로젝트 설정 화면이 그걸 그대로 쓰면 남의 프로젝트
     * 전용 스킴이 이름째 보인다(FR-WF-08). 쿼리는 같은 뼈대를 쓰고 **조건만** 다르다 — 복사하면
     * 카운트 정의가 바뀌는 날 한쪽만 고쳐져 두 목록이 다른 숫자를 보여 준다.
     *
     * @param projectId 대상 프로젝트 `projects.id`.
     * @return [SchemeCountRow] 목록. 비어 있을 수 있음.
     */
    @Transactional(readOnly = true)
    fun findAllWithCountsForProject(projectId: UUID): List<SchemeCountRow> {
        // 블록 본문으로 둔다 — 식 본문이면 ktlint 가 「한 줄로 합쳐라」를, detekt 가 「120자를
        // 넘지 마라」를 요구해 서로 배타가 된다(저장소 관례).
        return fetchCountRows(managedByProjectCondition(projectId))
    }

    /**
     * 「이 프로젝트의 스킴 관리 목록에 무엇이 들어가는가」를 정하는 단 하나의 조건.
     *
     * 소프트 삭제 필터는 [fetchCountRows] 가 이미 건다 — 여기서는 **소유**만 정한다.
     *
     * ## 전역 템플릿을 넣는 이유 (Maxi 확정 2026-09-10)
     *
     * ★**지금 배정된 스킴이 대개 전역이다.** EC-1 D10 이 신규 프로젝트에 `software-scheme` 을
     * 자동 배정하는데 V201 시드는 전부 `project_id IS NULL` 이다. 전역을 빼면 프로젝트 관리자가
     * **자기 프로젝트가 실제로 쓰는 스킴을 관리 화면에서 못 본다.**
     *
     * 「못 고치는 행이 섞인다」는 문제는 화면이 받는다 — 전역 행에는 편집 대신 복제를 준다
     * (PR ④ 워크플로우 목록과 같은 처방). 이 저장소는 「목록에서 숨기기」가 아니라 「목록에 두되
     * 액션을 가르기」를 택해 왔다(표준 스킴 잠금 · `ForbiddenSchemeCard`).
     *
     * ## 남의 프로젝트 스킴을 빼는 이유
     *
     * 그쪽은 권한 표현이 아니라 **정보 노출**이다. 이름만으로도 그 팀이 무슨 흐름을 쓰는지 샌다.
     * 같은 조건 안에서 두 규칙이 갈리는 지점이 여기다 — 전역은 보이고, 남의 것은 안 보인다.
     *
     * @param projectId 대상 프로젝트 `projects.id`.
     * @return 목록에 포함할 행을 고르는 jOOQ 조건.
     */
    private fun managedByProjectCondition(projectId: UUID): Condition {
        return WS_PROJECT_ID.isNull.or(WS_PROJECT_ID.eq(projectId))
    }

    /** [findAllWithCounts] · [findAllWithCountsForProject] 공용 조회. 조건만 갈린다. */
    private fun fetchCountRows(condition: Condition): List<SchemeCountRow> {
        val ASSIGNMENTS = table(name("project_workflow_scheme_assignments"))
        val MAPPINGS_TABLE = table(name("workflow_scheme_issue_type_mappings"))
        val A_WORKFLOW_SCHEME_ID =
            field(name("project_workflow_scheme_assignments", "workflow_scheme_id"), Long::class.java)
        val M_SCHEME_ID = field(name("workflow_scheme_issue_type_mappings", "scheme_id"), Long::class.java)

        // 스칼라 서브쿼리 — LEFT JOIN cartesian product 없이 독립 카운트 계산
        val projectCount =
            dsl.selectCount()
                .from(ASSIGNMENTS)
                .where(A_WORKFLOW_SCHEME_ID.eq(WS_ID))
                .asField<Long>("project_count")
        val mappingCount =
            dsl.selectCount()
                .from(MAPPINGS_TABLE)
                .where(M_SCHEME_ID.eq(WS_ID))
                .asField<Long>("mapping_count")

        return dsl
            .select(
                WS_ID,
                WS_KEY,
                WS_NAME,
                WS_DESCRIPTION,
                WS_IS_DEFAULT,
                WS_PROJECT_ID,
                WS_CREATED_AT,
                WS_UPDATED_AT,
                WS_DELETED_AT,
                projectCount,
                mappingCount,
            )
            .from(WORKFLOW_SCHEMES)
            .where(WS_DELETED_AT.isNull)
            .and(condition)
            .fetch()
            .map { rec -> rec.toCountRow() }
    }

    /**
     * [findAllWithCounts] 결과 행을 [SchemeCountRow] 로 옮긴다.
     *
     * 컬럼 참조가 `workflow_schemes.` 로 한정된 별도 필드라 [toWorkflowScheme] 을 재사용할 수 없다.
     */
    private fun org.jooq.Record.toCountRow(): SchemeCountRow {
        val scheme =
            WorkflowScheme.reconstruct(
                id = WorkflowSchemeId(this[WS_ID] ?: error("workflow_schemes.id NOT NULL")),
                key = WorkflowSchemeKey(this[WS_KEY] ?: error("workflow_schemes.key NOT NULL")),
                name = this[WS_NAME] ?: error("workflow_schemes.name NOT NULL"),
                description = this[WS_DESCRIPTION],
                isDefault = this[WS_IS_DEFAULT] ?: error("workflow_schemes.is_default NOT NULL"),
                projectId = this[WS_PROJECT_ID],
                createdAt = (this[WS_CREATED_AT] ?: error("workflow_schemes.created_at NOT NULL")).toInstant(),
                updatedAt = (this[WS_UPDATED_AT] ?: error("workflow_schemes.updated_at NOT NULL")).toInstant(),
                deletedAt = this[WS_DELETED_AT]?.toInstant(),
            )
        return SchemeCountRow(
            scheme = scheme,
            usedByProjectsCount = this.get("project_count", Long::class.java) ?: 0L,
            mappingsCount = this.get("mapping_count", Long::class.java) ?: 0L,
        )
    }

    // ── 내부 변환 ──────────────────────────────────────────────────────────────

    /**
     * jOOQ Record → WorkflowScheme 도메인 객체 변환.
     *
     * WorkflowScheme 생성자가 private 이므로 리플렉션 없이 복원하기 위해
     * 별도 companion factory `reconstruct` 를 사용한다.
     */
    private fun org.jooq.Record.toWorkflowScheme(): WorkflowScheme =
        WorkflowScheme.reconstruct(
            id = WorkflowSchemeId(this[ID] ?: error("workflow_schemes.id NOT NULL constraint violated: row $this")),
            key = WorkflowSchemeKey(this[KEY] ?: error("workflow_schemes.key NOT NULL constraint violated: row $this")),
            name = this[NAME] ?: error("workflow_schemes.name NOT NULL constraint violated: row $this"),
            description = this[DESCRIPTION],
            isDefault = this[IS_DEFAULT] ?: error("workflow_schemes.is_default NOT NULL"),
            projectId = this[PROJECT_ID],
            createdAt = (this[CREATED_AT] ?: error("workflow_schemes.created_at NOT NULL")).toInstant(),
            updatedAt = (this[UPDATED_AT] ?: error("workflow_schemes.updated_at NOT NULL")).toInstant(),
            deletedAt = this[DELETED_AT]?.toInstant(),
        )

    // ── Instant ↔ OffsetDateTime 변환 헬퍼 ──────────────────────────────────

    private fun Instant.toOffsetDateTime(): OffsetDateTime = OffsetDateTime.ofInstant(this, ZoneOffset.UTC)
}
