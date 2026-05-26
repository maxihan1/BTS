// 프로젝트-워크플로우 스킴 할당 Repository — project_workflow_scheme_assignments UPSERT/조회/삭제

package com.bts.workflow.scheme.repository

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
class ProjectWorkflowSchemeAssignmentRepository(private val dsl: DSLContext) {
    private val log = LoggerFactory.getLogger(javaClass)

    // ── 테이블 / 컬럼 상수 ────────────────────────────────────────────────────

    private val TABLE = DSL.table("project_workflow_scheme_assignments")
    private val PROJECT_ID = DSL.field("project_id", Long::class.java)
    private val WORKFLOW_SCHEME_ID = DSL.field("workflow_scheme_id", Long::class.java)
    private val ASSIGNED_AT = DSL.field("assigned_at", java.time.OffsetDateTime::class.java)
    private val ASSIGNED_BY = DSL.field("assigned_by", UUID::class.java)

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
    fun findByProjectId(projectId: Long): ProjectWorkflowSchemeAssignment? {
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
     * project_id 에 대한 스킴 할당을 삭제한다.
     *
     * 존재하지 않는 `projectId` 에 대해 예외 없이 no-op 으로 처리한다.
     *
     * @param projectId 삭제할 프로젝트 ID.
     */
    @Transactional
    fun deleteByProjectId(projectId: Long) {
        log.info("deleteByProjectId projectId={}", projectId)

        dsl.deleteFrom(TABLE)
            .where(PROJECT_ID.eq(projectId))
            .execute()
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
