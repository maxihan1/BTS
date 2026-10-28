// 프로젝트-워크플로우 스킴 할당 Repository — project_workflow_scheme_assignments UPSERT/조회/삭제

package com.bts.workflow.scheme.repository

import com.bts.shared.issue.StatusMigrationTypeFilter
import com.bts.workflow.scheme.domain.ProjectWorkflowSchemeAssignment
import com.bts.workflow.scheme.domain.WorkflowSchemeId
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * 워크플로우에 연결된 프로젝트 한 건의 참조.
 *
 * @property id projects.id (UUID).
 * @property key projects.key — 이슈 키 접두사 (예: "BTS").
 */
data class ProjectRef(
    val id: UUID,
    val key: String,
)

/**
 * 이관이 건드릴 프로젝트 한 건과, 그 프로젝트에서 이 워크플로우가 맡는 이슈 타입.
 *
 * @property ref 프로젝트 참조.
 * @property typeFilter 이 프로젝트의 스킴에서 이 워크플로우가 맡는 이슈 타입. 계산 규칙은
 *   [ProjectWorkflowSchemeAssignmentRepository.findMigrationScopesByWorkflowId].
 */
data class ProjectMigrationScope(
    val ref: ProjectRef,
    val typeFilter: StatusMigrationTypeFilter,
)

/**
 * `project_workflow_scheme_assignments` 테이블 전용 Repository.
 *
 * ## 책임 범위
 * 본 Repository 는 `project_workflow_scheme_assignments` 테이블만 다룬다.
 * `projects` 테이블은 다른 BC(project-management) 소유이므로 INSERT/UPDATE 절대 금지.
 * `workflow_schemes` 테이블은 FK 검증용 읽기 전용 참조만 허용한다 (PR #14 Cross-table UPSERT 금지 learning).
 *
 * ## UPSERT 전략
 * `project_id` 는 `PRIMARY KEY` 이므로 동일 프로젝트 재할당 시
 * `ON CONFLICT (project_id) DO UPDATE SET ...` 으로 기존 row 를 교체한다.
 *
 * ## jOOQ generated 클래스 부재
 * jOOQ codegen 은 V001 기반으로만 실행되어 V004 scheme 테이블이 포함되지 않는다.
 * `DSL.table()` / `DSL.field()` 로 타입 안전하게 쿼리를 구성한다.
 * Wave-2 종료 후 generateJooq 태스크를 재실행하면 generated 클래스로 교체 가능.
 *
 * @param dsl jOOQ DSLContext — SQL 을 코드로 안전하게 작성하는 라이브러리의 핵심 진입점.
 */
@Repository
@Suppress("PropertyName", "VariableNaming") // jOOQ 필드 상수 — SQL 컬럼명 매칭 (UPPER_SNAKE_CASE). codegen 도입 시 typed table 로 교체 예정.
class ProjectWorkflowSchemeAssignmentRepository(private val dsl: DSLContext) {
    private val log = LoggerFactory.getLogger(javaClass)

    // ── 테이블 / 컬럼 상수 ────────────────────────────────────────────────────

    private val TABLE = DSL.table("project_workflow_scheme_assignments")
    private val PROJECT_ID = DSL.field("project_id", UUID::class.java)
    private val WORKFLOW_SCHEME_ID = DSL.field("workflow_scheme_id", Long::class.java)
    private val ASSIGNED_AT = DSL.field("assigned_at", java.time.OffsetDateTime::class.java)
    private val ASSIGNED_BY = DSL.field("assigned_by", UUID::class.java)

    // ── 역방향 조회(findProjectRefsByWorkflowId) 전용 3단 JOIN 컬럼 ──────────────
    // 위 상수와 달리 테이블 한정자를 붙인다. JOIN 하면 id 가 매핑 표와 projects 양쪽에 있어
    // ambiguous 가 되고, 할당 표와 매핑 표는 스킴 FK 컬럼명이 서로 다르기 때문이다
    // (할당 표 = workflow_scheme_id · 매핑 표 = scheme_id).
    private val MAPPINGS = DSL.table("workflow_scheme_issue_type_mappings")
    private val PROJECTS = DSL.table("projects")
    private val M_SCHEME_ID = DSL.field("workflow_scheme_issue_type_mappings.scheme_id", Long::class.java)
    private val M_WORKFLOW_ID = DSL.field("workflow_scheme_issue_type_mappings.workflow_id", UUID::class.java)
    private val M_ISSUE_TYPE_ID = DSL.field("workflow_scheme_issue_type_mappings.issue_type_id", Long::class.java)
    private val A_SCHEME_ID = DSL.field("project_workflow_scheme_assignments.workflow_scheme_id", Long::class.java)
    private val A_PROJECT_ID = DSL.field("project_workflow_scheme_assignments.project_id", UUID::class.java)
    private val P_ID = DSL.field("projects.id", UUID::class.java)
    private val P_KEY = DSL.field("projects.key", String::class.java)
    private val P_DELETED_AT = DSL.field("projects.deleted_at", java.time.OffsetDateTime::class.java)
    private val P_ARCHIVED_AT = DSL.field("projects.archived_at", java.time.OffsetDateTime::class.java)

    private val SCHEMES = DSL.table("workflow_schemes")
    private val SC_ID = DSL.field("workflow_schemes.id", Long::class.java)
    private val SC_DELETED_AT = DSL.field("workflow_schemes.deleted_at", java.time.OffsetDateTime::class.java)

    /**
     * 프로젝트에 워크플로우 스킴을 할당하거나 기존 할당을 교체한다.
     *
     * `project_id` PK 충돌 시 `ON CONFLICT (project_id) DO UPDATE SET` 으로
     * `workflow_scheme_id`, `assigned_at`, `assigned_by` 를 최신 값으로 교체한다.
     *
     * @param assignment 저장할 [ProjectWorkflowSchemeAssignment]. `projects` 테이블 미터치.
     */
    @Transactional
    fun saveAssignment(assignment: ProjectWorkflowSchemeAssignment) {
        log.info(
            "saveAssignment projectId={} schemeId={} assignedBy={}",
            assignment.projectId,
            assignment.workflowSchemeId.value,
            assignment.assignedBy,
        )

        // raw SQL dsl.execute 는 args 의 타입 추론을 안 하므로 OffsetDateTime 이
        // PostgreSQL JDBC 의 setString 으로 fallback (character varying) → TIMESTAMPTZ 컬럼과 타입 불일치.
        // java.sql.Timestamp 는 JDBC 표준 timestamp 타입이라 PG 가 TIMESTAMPTZ 로 안전하게 받는다.
        dsl.execute(
            "INSERT INTO project_workflow_scheme_assignments" +
                " (project_id, workflow_scheme_id, assigned_at, assigned_by)" +
                " VALUES (?, ?, ?, ?)" +
                " ON CONFLICT (project_id) DO UPDATE SET" +
                " workflow_scheme_id = EXCLUDED.workflow_scheme_id," +
                " assigned_at = EXCLUDED.assigned_at," +
                " assigned_by = EXCLUDED.assigned_by",
            assignment.projectId,
            assignment.workflowSchemeId.value,
            java.sql.Timestamp.from(assignment.assignedAt),
            assignment.assignedBy,
        )
    }

    /**
     * project_id 로 할당 정보를 조회한다.
     *
     * @param projectId 조회할 프로젝트 ID.
     * @return 할당된 [ProjectWorkflowSchemeAssignment], 없으면 null.
     */
    @Transactional(readOnly = true)
    fun findByProjectId(projectId: UUID): ProjectWorkflowSchemeAssignment? {
        val record =
            dsl
                .select(PROJECT_ID, WORKFLOW_SCHEME_ID, ASSIGNED_AT, ASSIGNED_BY)
                .from(TABLE)
                .where(PROJECT_ID.eq(projectId))
                .fetchOne() ?: return null

        return record.toAssignment()
    }

    /**
     * 특정 스킴 ID 에 대한 할당(assignment) 이 하나 이상 존재하는지 확인한다.
     *
     * S7 softDelete 사용 중 차단 검증에 사용한다.
     * `SELECT EXISTS(SELECT 1 FROM project_workflow_scheme_assignments WHERE workflow_scheme_id = ?)`.
     *
     * @param schemeId 확인할 스킴 식별자.
     * @return 해당 스킴에 할당된 프로젝트가 하나 이상 있으면 true.
     */
    @Transactional(readOnly = true)
    fun existsBySchemeId(schemeId: WorkflowSchemeId): Boolean =
        dsl
            .selectOne()
            .from(TABLE)
            .where(WORKFLOW_SCHEME_ID.eq(schemeId.value))
            .limit(1)
            .fetchOne() != null

    /**
     * 스킴에 할당된 프로젝트 중 살아 있고 아카이브되지 않은 것의 id.
     *
     * 매핑을 고칠 때 이슈가 새 워크플로우에 없는 상태에 남는지 세는 범위다(WF-02-1). 아카이브를
     * 빼는 이유 — 매핑 변경에는 상태 이관 경로가 없어 아카이브 이슈는 해제 전에는 옮길 수 없으므로,
     * 넣으면 매핑 변경이 **관리자가 풀 수 없게** 막힌다. 그 대가로 아카이브 이슈가 워크플로우에 없는
     * 상태로 남을 수 있다(WF-07-4 잔여). 발행 · 상태 제거 카운트는 이관이 있어 아카이브를 넣는다.
     *
     * @param schemeId 조회할 스킴.
     * @return 프로젝트 id 집합. 할당이 없으면 빈 집합.
     */
    @Transactional(readOnly = true)
    fun findActiveProjectIdsBySchemeId(schemeId: WorkflowSchemeId): Set<UUID> =
        dsl
            .select(P_ID)
            .from(TABLE)
            .join(PROJECTS).on(P_ID.eq(A_PROJECT_ID))
            .where(A_SCHEME_ID.eq(schemeId.value))
            .and(P_DELETED_AT.isNull)
            .and(P_ARCHIVED_AT.isNull)
            .fetchSet(P_ID)
            .filterNotNull()
            .toSet()

    /**
     * 스킴에 할당된 살아 있는 프로젝트 id — **아카이브 포함**.
     *
     * 스킴 게시(R11)는 이관 경로가 있어 아카이브 이슈도 옮긴다. 빼면 그 이슈가 워크플로우에 없는
     * 상태로 남는다(WF-07-4). [findActiveProjectIdsBySchemeId] 는 이관 없는 매핑 추가 · 삭제용이다.
     *
     * @param schemeId 조회할 스킴.
     * @return 프로젝트 id 집합. 할당이 없으면 빈 집합.
     */
    @Transactional(readOnly = true)
    fun findLiveProjectIdsBySchemeId(schemeId: WorkflowSchemeId): Set<UUID> =
        dsl
            .select(P_ID)
            .from(TABLE)
            .join(PROJECTS).on(P_ID.eq(A_PROJECT_ID))
            .where(A_SCHEME_ID.eq(schemeId.value))
            .and(P_DELETED_AT.isNull)
            .fetchSet(P_ID)
            .filterNotNull()
            .toSet()

    /**
     * project_id 에 대한 스킴 할당을 삭제한다.
     *
     * 존재하지 않는 `projectId` 에 대해 예외 없이 no-op 으로 처리한다.
     *
     * @param projectId 삭제할 프로젝트 ID.
     */
    @Transactional
    fun deleteByProjectId(projectId: UUID) {
        log.info("deleteByProjectId projectId={}", projectId)

        dsl.deleteFrom(TABLE)
            .where(PROJECT_ID.eq(projectId))
            .execute()
    }

    /**
     * 워크플로우를 참조하는 스킴에 할당된 활성 프로젝트를 조회한다.
     *
     * ## 왜 정방향 조회로는 안 되는가
     * 이 BC 가 이미 가진 조회는 전부 **프로젝트에서 출발한다** — [findByProjectId] 는 프로젝트가
     * 어느 스킴에 붙어 있는지 묻고, `ProjectLookupPort.findIdByKey` 는 키로 프로젝트 id 를 찾는다.
     * 그런데 워크플로우 발행 경로가 채워야 하는
     * `IssueStatusMigrationPort.StatusMigrationCommand.projectKeys` 는 **워크플로우에서 출발한다**.
     * 정방향 조회로 이 값을 얻으려면 전체 프로젝트를 훑으며 한 건씩 스킴을 되묻는 N+1 이 되고,
     * 그마저도 스킴이 이슈 타입별로 여러 워크플로우를 가리키는 구조를 다시 풀어야 한다.
     * 그래서 매핑 표에서 거슬러 올라가는 3단 JOIN 을 한 번에 던진다.
     *
     * ## DISTINCT 가 필요한 이유
     * 한 스킴이 default 매핑과 이슈 타입별 매핑으로 **같은 워크플로우를 여러 번** 가리킬 수 있다.
     * 그러면 프로젝트 한 건이 매핑 수만큼 중복돼 나오므로 DISTINCT 로 접는다.
     *
     * 소프트 삭제(`deleted_at`)된 프로젝트는 제외하고 아카이브는 **포함**한다(WF-07-4). 상태 제거
     * 차단 카운트가 이 범위로 세는데, 아카이브 이슈를 빼면 그 이슈만 남은 상태가 빠져 워크플로우에
     * 없는 상태로 남는다. 이관이 아카이브 이슈도 옮기므로 세어도 관리자가 풀 수 있다.
     *
     * @param workflowId 조회할 워크플로우 UUID.
     * @return 소프트 삭제되지 않은 프로젝트(아카이브 포함) 참조 목록. 연결된 프로젝트가 없으면 빈 목록.
     */
    @Transactional(readOnly = true)
    fun findProjectRefsByWorkflowId(workflowId: UUID): List<ProjectRef> =
        // 같은 JOIN 을 두 벌 두지 않으려고 이관 범위 조회를 그대로 쓴다 — 두 집합이 어긋날 수 없다.
        findMigrationScopesByWorkflowId(workflowId).map { it.ref }

    /**
     * 이관이 대상으로 삼을 프로젝트와 각 프로젝트의 이슈 타입 필터. **아카이브를 포함**한다.
     *
     * ## 왜 아카이브를 넣는가 (WF-07-4)
     * 범위에서 빼면 워커가 그 프로젝트를 안 보고 그 이슈들이 워크플로우에 없는 상태로 남는다.
     * 워커(`BulkItemApplier`)는 이관에서 아카이브 가드를 건너뛰어 그 이슈도 옮긴다. 발행 차단
     * 카운트도 같은 범위로 센다 — 「이관하고 나면 발행된다」가 성립하려면 두 집합이 같아야 한다.
     *
     * ## 스킴 소프트 삭제를 본다
     * `WorkflowSchemeRepository.softDelete` 는 `deleted_at` 만 세우고 매핑·할당 행을 남긴다
     * (V201 의 CASCADE 는 하드 삭제에만 걸린다). 그것을 안 보면 **지운 스킴에 붙어 있던 프로젝트가
     * 계속 잡혀** 발행 차단 카운트가 과다 집계되고, 이관도 그 프로젝트를 범위에 싣는다.
     *
     * ## DISTINCT 가 필요한 이유
     * 한 스킴이 default 매핑과 이슈 타입별 매핑으로 **같은 워크플로우를 여러 번** 가리킬 수 있다.
     *
     * ## 워커와 같은 축으로 거른다
     * 소프트 삭제만 제외하는 것은 워커의 대상 조회(`BulkOperationRepository.statusMigrationTargets`)
     * 가 `projects.deleted_at` 만 거는 것과 **일부러 맞춘 것**이다. 여기서 더 좁히면 서비스가 센
     * 상한과 워커가 실제로 담는 건수가 갈라진다.
     *
     * ## 이슈 타입 필터 — 스킴은 `(scheme_id, issue_type_id) → workflow_id` 다 (WF-07-2)
     * 한 프로젝트가 Bug→WF1 · Task→WF2 를 쓰면 WF1 이 빼는 상태가 WF2 에서는 살아 있을 수 있다.
     * 프로젝트로만 좁히면 WF1 이관이 WF2 의 Task 까지 옮긴다. 그래서 프로젝트의 스킴 매핑에서
     * 이 워크플로우가 맡는 타입을 계산한다 — 런타임 해석(`WorkflowResolver`: 명시 매핑 먼저, 없으면
     * default)과 같은 규칙이다.
     *
     * - 이 워크플로우가 그 스킴의 **default** 면 「다른 워크플로우에 명시된 타입을 **뺀** 전부」.
     * - 아니면 「이 워크플로우에 명시된 타입**만**」.
     *
     * 한 프로젝트는 스킴 하나에만 할당되므로(`project_id` PK) 프로젝트마다 필터는 하나다.
     *
     * ## 잔여 창 — 큐잉 뒤 스킴 매핑이 바뀌면
     * 필터는 큐잉 시점의 매핑으로 굳는다. 큐잉과 워커 실행 사이에 형제 워크플로우에 타입별 매핑이
     * 새로 붙으면 default 쪽 제외 목록에 그 타입이 없어 함께 옮겨진다. 워커는 큐잉 직후 돌므로 창은
     * 짧고, 스킴 변경의 `requireNoStrandedIssues` 가 새 워크플로우에 없는 상태를 막는다. 닫지는 않았다.
     *
     * @param workflowId 이관할 워크플로우 UUID.
     * @return 소프트 삭제되지 않은 프로젝트(아카이브 포함)와 필터. 연결이 없으면 빈 목록.
     */
    @Transactional(readOnly = true)
    fun findMigrationScopesByWorkflowId(workflowId: UUID): List<ProjectMigrationScope> {
        val projects =
            dsl
                .selectDistinct(M_SCHEME_ID, P_ID, P_KEY)
                .from(MAPPINGS)
                .join(SCHEMES).on(SC_ID.eq(M_SCHEME_ID))
                .join(TABLE).on(A_SCHEME_ID.eq(M_SCHEME_ID))
                .join(PROJECTS).on(P_ID.eq(A_PROJECT_ID))
                .where(M_WORKFLOW_ID.eq(workflowId))
                .and(SC_DELETED_AT.isNull)
                .and(P_DELETED_AT.isNull)
                .fetch()
        if (projects.isEmpty()) return emptyList()

        val filters =
            dsl
                .select(M_SCHEME_ID, M_ISSUE_TYPE_ID, M_WORKFLOW_ID)
                .from(MAPPINGS)
                .where(M_SCHEME_ID.`in`(projects.map { it.get(M_SCHEME_ID) }.toSet()))
                .fetch()
                .groupBy { it.get(M_SCHEME_ID) }
                .mapValues { (_, rows) ->
                    typeFilterOf(workflowId, rows.map { it.get(M_ISSUE_TYPE_ID) to it.get(M_WORKFLOW_ID) })
                }

        return projects.map { record ->
            ProjectMigrationScope(
                ref =
                    ProjectRef(
                        id = record.get(P_ID) ?: error("projects.id is null — PK 제약 위반"),
                        key = record.get(P_KEY) ?: error("projects.key is null — NOT NULL 제약 위반"),
                    ),
                typeFilter = filters[record.get(M_SCHEME_ID)] ?: error("스킴 매핑이 사라졌다 — 같은 트랜잭션에서 읽었다"),
            )
        }
    }

    /**
     * 스킴 한 개의 매핑 `(issue_type_id, workflow_id)` 에서 [workflowId] 가 맡는 이슈 타입을 계산한다.
     * 규칙은 [findMigrationScopesByWorkflowId] KDoc.
     */
    private fun typeFilterOf(
        workflowId: UUID,
        mappings: List<Pair<Long?, UUID?>>,
    ): StatusMigrationTypeFilter {
        val isDefault = mappings.any { (typeId, wf) -> typeId == null && wf == workflowId }
        return if (isDefault) {
            StatusMigrationTypeFilter(
                issueTypeIds = mappings.filter { (_, wf) -> wf != workflowId }.mapNotNull { it.first }.toSet(),
                exceptListed = true,
            )
        } else {
            StatusMigrationTypeFilter(
                issueTypeIds = mappings.filter { (_, wf) -> wf == workflowId }.mapNotNull { it.first }.toSet(),
                exceptListed = false,
            )
        }
    }

    // ── 내부 변환 ─────────────────────────────────────────────────────────────

    /**
     * jOOQ Record → [ProjectWorkflowSchemeAssignment] 변환.
     *
     * `assigned_at` 은 DB 에서 TIMESTAMPTZ 로 읽히므로 [java.time.OffsetDateTime] → [Instant] 로 변환한다.
     */
    private fun org.jooq.Record.toAssignment(): ProjectWorkflowSchemeAssignment {
        val schemeIdValue = this[WORKFLOW_SCHEME_ID] ?: error("workflow_scheme_id is null — DB 제약 위반")
        val assignedAtValue = this[ASSIGNED_AT] ?: error("assigned_at is null — DB 제약 위반")
        val assignedByValue = this[ASSIGNED_BY] ?: error("assigned_by is null — DB 제약 위반")

        return ProjectWorkflowSchemeAssignment(
            projectId = this[PROJECT_ID] ?: error("project_id is null — DB 제약 위반"),
            workflowSchemeId = WorkflowSchemeId(schemeIdValue),
            assignedAt = assignedAtValue.toInstant(),
            assignedBy = assignedByValue,
        )
    }
}
