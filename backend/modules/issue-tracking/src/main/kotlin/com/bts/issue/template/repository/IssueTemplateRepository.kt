// 이슈 템플릿 Repository — issue_templates 테이블 jOOQ DSL 접근
package com.bts.issue.template.repository

import com.bts.issue.jooq.tables.references.ISSUE_TEMPLATES
import com.bts.issue.template.domain.IssueTemplate
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * 이슈 템플릿 Repository.
 *
 * jOOQ DSLContext 를 통해 `issue_templates` 테이블에 접근한다.
 * 모든 public 메서드는 `@Transactional` 을 명시한다 (DATA.md §6, DEVELOPMENT.md §절대규칙).
 *
 * 소프트 삭제 전략(DATA.md §3).
 * - 활성 조건: `deleted_at IS NULL`.
 * - 물리 삭제(DELETE) 금지. `softDelete` 는 `deleted_at` 을 현재 UTC 시각으로 설정한다.
 *
 * 부분 유니크 인덱스(`ux_issue_templates_project_type_active`) 위반은 그대로 전파한다.
 * 중복 예외([com.bts.issue.template.domain.DuplicateIssueTemplateException])로의 변환은
 * ApplicationService 책임이다.
 *
 * - [insert] — 새 템플릿을 삽입하고 삽입된 [IssueTemplate] 을 반환.
 * - [findById] — UUID 조건으로 활성 단건 조회.
 * - [findByProject] — 프로젝트 소속 활성 템플릿 목록 조회.
 * - [update] — name/content 갱신.
 * - [softDelete] — deleted_at 설정 (물리 삭제 금지).
 * - [existsActive] — (projectId, issueTypeId) 조합으로 활성 템플릿 존재 여부 확인.
 * - [findActiveContentByProjectAndType] — 활성 1건의 content 반환 (이슈 생성 안전망용).
 */
@Repository
class IssueTemplateRepository(
    private val dsl: DSLContext,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 새 이슈 템플릿을 삽입하고 삽입된 [IssueTemplate] 을 반환한다.
     *
     * 부분 유니크 인덱스 전제 —
     * `ux_issue_templates_project_type_active (project_id, issue_type_id) WHERE deleted_at IS NULL`.
     * 소프트 삭제된 동일 (project, type) 조합은 재삽입 허용된다.
     *
     * @param template 저장할 [IssueTemplate]. [IssueTemplate.id] 는 도메인 팩토리가 생성한 UUID.
     * @return 삽입 완료된 [IssueTemplate].
     * @throws org.springframework.dao.DataAccessException 부분 유니크 인덱스 위반 시.
     */
    @Transactional
    fun insert(template: IssueTemplate): IssueTemplate {
        log.debug(
            "Inserting issue template id={} projectId={} issueTypeId={}",
            template.id,
            template.projectId,
            template.issueTypeId,
        )
        val now = OffsetDateTime.now(ZoneOffset.UTC)

        dsl.insertInto(ISSUE_TEMPLATES)
            .set(ISSUE_TEMPLATES.ID, template.id)
            .set(ISSUE_TEMPLATES.PROJECT_ID, template.projectId)
            .set(ISSUE_TEMPLATES.ISSUE_TYPE_ID, template.issueTypeId)
            .set(ISSUE_TEMPLATES.NAME, template.name)
            .set(ISSUE_TEMPLATES.CONTENT, template.content)
            .set(ISSUE_TEMPLATES.CREATED_AT, now)
            .set(ISSUE_TEMPLATES.UPDATED_AT, now)
            .execute()

        return template
    }

    /**
     * 템플릿 UUID 조건으로 활성(`deleted_at IS NULL`) 단건을 조회한다.
     *
     * @param id 조회할 템플릿 UUID.
     * @return 활성 [IssueTemplate], 없으면 null.
     */
    @Transactional(readOnly = true)
    fun findById(id: UUID): IssueTemplate? =
        dsl.selectFrom(ISSUE_TEMPLATES)
            .where(ISSUE_TEMPLATES.ID.eq(id))
            .and(ISSUE_TEMPLATES.DELETED_AT.isNull)
            .fetchOne()
            ?.let { toTemplate(it) }

    /**
     * 프로젝트 소속 활성(`deleted_at IS NULL`) 템플릿 목록을 반환한다.
     *
     * @param projectId 조회할 프로젝트 UUID.
     * @return 활성 [IssueTemplate] 리스트. 없으면 빈 리스트.
     */
    @Transactional(readOnly = true)
    fun findByProject(projectId: UUID): List<IssueTemplate> =
        dsl.selectFrom(ISSUE_TEMPLATES)
            .where(ISSUE_TEMPLATES.PROJECT_ID.eq(projectId))
            .and(ISSUE_TEMPLATES.DELETED_AT.isNull)
            .fetch()
            .map { toTemplate(it) }

    /**
     * 이슈 템플릿의 name 과 content 를 갱신한다.
     *
     * 활성(`deleted_at IS NULL`) 조건에서만 갱신한다.
     * 소프트 삭제된 템플릿은 영향 행 0 (멱등 처리).
     *
     * @param id 갱신할 템플릿 UUID.
     * @param name 새 템플릿 이름.
     * @param content 새 템플릿 본문 내용.
     */
    @Transactional
    fun update(
        id: UUID,
        name: String,
        content: String,
    ) {
        log.debug("Updating issue template id={}", id)
        dsl.update(ISSUE_TEMPLATES)
            .set(ISSUE_TEMPLATES.NAME, name)
            .set(ISSUE_TEMPLATES.CONTENT, content)
            .set(ISSUE_TEMPLATES.UPDATED_AT, OffsetDateTime.now(ZoneOffset.UTC))
            .where(ISSUE_TEMPLATES.ID.eq(id))
            .and(ISSUE_TEMPLATES.DELETED_AT.isNull)
            .execute()
    }

    /**
     * 이슈 템플릿을 소프트 삭제한다.
     *
     * `deleted_at` 을 현재 UTC 시각으로 설정한다.
     * 이미 삭제된 템플릿은 영향 행 0 반환 (멱등 처리).
     * 물리 삭제(DELETE) 금지 — DATA.md §3.
     *
     * @param id 삭제할 템플릿 UUID.
     */
    @Transactional
    fun softDelete(id: UUID) {
        log.debug("Soft-deleting issue template id={}", id)
        dsl.update(ISSUE_TEMPLATES)
            .set(ISSUE_TEMPLATES.DELETED_AT, OffsetDateTime.now(ZoneOffset.UTC))
            .where(ISSUE_TEMPLATES.ID.eq(id))
            .and(ISSUE_TEMPLATES.DELETED_AT.isNull)
            .execute()
    }

    /**
     * (projectId, issueTypeId) 조합으로 활성 템플릿 존재 여부를 확인한다.
     *
     * @param projectId 확인할 프로젝트 UUID.
     * @param issueTypeId 확인할 이슈 타입 BIGINT.
     * @return 활성 템플릿이 존재하면 true, 없으면 false.
     */
    @Transactional(readOnly = true)
    fun existsActive(
        projectId: UUID,
        issueTypeId: Long,
    ): Boolean =
        dsl.fetchExists(
            dsl.selectOne()
                .from(ISSUE_TEMPLATES)
                .where(ISSUE_TEMPLATES.PROJECT_ID.eq(projectId))
                .and(ISSUE_TEMPLATES.ISSUE_TYPE_ID.eq(issueTypeId))
                .and(ISSUE_TEMPLATES.DELETED_AT.isNull),
        )

    /**
     * (projectId, issueTypeId) 조합의 활성 템플릿 content 를 반환한다.
     *
     * 이슈 생성 안전망(옵션 C)에서 사용된다.
     * 활성 템플릿이 없으면 null 을 반환한다.
     *
     * @param projectId 조회할 프로젝트 UUID.
     * @param issueTypeId 조회할 이슈 타입 BIGINT.
     * @return 활성 템플릿의 content 문자열, 없으면 null.
     */
    @Transactional(readOnly = true)
    fun findActiveContentByProjectAndType(
        projectId: UUID,
        issueTypeId: Long,
    ): String? =
        dsl.select(ISSUE_TEMPLATES.CONTENT)
            .from(ISSUE_TEMPLATES)
            .where(ISSUE_TEMPLATES.PROJECT_ID.eq(projectId))
            .and(ISSUE_TEMPLATES.ISSUE_TYPE_ID.eq(issueTypeId))
            .and(ISSUE_TEMPLATES.DELETED_AT.isNull)
            .limit(1)
            .fetchOne()
            ?.get(ISSUE_TEMPLATES.CONTENT)

    // ── private helpers ───────────────────────────────────────────────────────

    /**
     * jOOQ [com.bts.issue.jooq.tables.records.IssueTemplatesRecord] 를
     * [IssueTemplate] 도메인 객체로 변환한다.
     *
     * NOT NULL 컬럼(id, project_id, issue_type_id, name, content)이 null 이면 DB 정합 이상이므로
     * `error()` 로 빠른 실패를 유도한다.
     *
     * @param record 변환할 jOOQ 레코드.
     * @return 변환된 [IssueTemplate].
     */
    private fun toTemplate(record: com.bts.issue.jooq.tables.records.IssueTemplatesRecord): IssueTemplate =
        IssueTemplate(
            id = record.id ?: error("issue_templates.id must not be null"),
            projectId = record.projectId ?: error("issue_templates.project_id must not be null"),
            issueTypeId = record.issueTypeId ?: error("issue_templates.issue_type_id must not be null"),
            name = record.name ?: error("issue_templates.name must not be null"),
            content = record.content ?: error("issue_templates.content must not be null"),
            createdAt = toInstant(record.createdAt ?: error("issue_templates.created_at must not be null")),
            updatedAt = toInstant(record.updatedAt ?: error("issue_templates.updated_at must not be null")),
            deletedAt = record.deletedAt?.let { toInstant(it) },
        )

    /**
     * [OffsetDateTime] 을 [Instant] 로 변환한다.
     *
     * @param odt 변환할 [OffsetDateTime].
     * @return 동등한 [Instant].
     */
    private fun toInstant(odt: OffsetDateTime): Instant = odt.toInstant()
}
