// 이슈 템플릿 CRUD+resolve ApplicationService — 권한 게이트, issueType 선검증, 중복 409 변환, Repository 위임

package com.bts.issue.template.application

import com.bts.issue.template.domain.DuplicateIssueTemplateException
import com.bts.issue.template.domain.IssueTemplate
import com.bts.issue.template.domain.IssueTemplateAccessDeniedException
import com.bts.issue.template.domain.IssueTemplateNotFoundException
import com.bts.issue.template.repository.IssueTemplateRepository
import com.bts.issue.type.domain.IssueTypeNotFoundException
import com.bts.issue.type.repository.IssueTypeRepository
import com.bts.shared.issue.IssueTypeId
import com.bts.shared.permission.TemplatePermission
import com.bts.shared.permission.TemplatePermissionResolver
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/** 23505 unique_violation SQLState 상수. */
private const val SQL_STATE_UNIQUE_VIOLATION = "23505"

/**
 * 이슈 템플릿 CRUD + resolve ApplicationService.
 *
 * 권한 검증, issueType 활성 선검증, 중복 변환, Repository 위임을 한 흐름으로 조율한다.
 * [com.bts.issue.customfield.application.CustomFieldApplicationService] 와 동형.
 *
 * **권한 게이팅.**
 * mutation(create/update/delete) 진입 직후 [TemplatePermissionResolver.hasPermission] 을 호출한다.
 * READ(getById/listByProject) 와 resolve 는 권한 게이트 없음(커스텀 필드 동일 정책).
 *
 * **issueTypeId 선검증.**
 * create 시 FK 위반 전에 [IssueTypeRepository.findById] 로 활성 존재를 확인한다.
 * 미존재 시 [IssueTypeNotFoundException] 을 던진다.
 *
 * **중복 409 변환.**
 * [IssueTemplateRepository.insert] 에서 발생하는 `DataIntegrityViolationException` 또는
 * `org.jooq.exception.IntegrityConstraintViolationException` 의 SQLState 가 23505 이면
 * [DuplicateIssueTemplateException] 으로 변환한다 (메모리 jooq-exception-translator-409-dependency).
 *
 * **트랜잭션 경계.**
 * 클래스 레벨 `@Transactional` (읽기/쓰기 모두). 읽기 전용 메서드는 `readOnly = true` 오버라이드.
 */
@Service
@Transactional
class IssueTemplateApplicationService(
    private val permissionResolver: TemplatePermissionResolver,
    private val repo: IssueTemplateRepository,
    private val issueTypeRepository: IssueTypeRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 새 이슈 템플릿을 생성한다.
     *
     * 흐름.
     * 1. CREATE 권한 검증 — 거부 시 [IssueTemplateAccessDeniedException].
     * 2. issueTypeId 활성 존재 선검증 — 미존재 시 [IssueTypeNotFoundException].
     * 3. 도메인 [IssueTemplate.create] 호출.
     * 4. [IssueTemplateRepository.insert] — 23505 위반 시 [DuplicateIssueTemplateException].
     *
     * @param actorId 생성 행위자 UUID.
     * @param projectId 템플릿이 속할 프로젝트 UUID.
     * @param issueTypeId 연결할 이슈 타입 BIGINT.
     * @param name 템플릿 이름. trim 후 빈 문자열 불가, 최대 100자.
     * @param content 이슈 본문 내용. trim 후 빈 문자열 불가.
     * @return 생성된 [IssueTemplate].
     * @throws IssueTemplateAccessDeniedException CREATE 권한이 없을 때.
     * @throws IssueTypeNotFoundException issueTypeId 에 해당하는 활성 이슈 타입이 없을 때.
     * @throws DuplicateIssueTemplateException 동일 (project, type) 활성 템플릿이 이미 존재할 때.
     */
    fun create(
        actorId: UUID,
        projectId: UUID,
        issueTypeId: Long,
        name: String,
        content: String,
    ): IssueTemplate {
        assertPermission(actorId, TemplatePermission.CREATE, projectId)
        assertIssueTypeExists(issueTypeId)

        val domain =
            IssueTemplate.create(
                projectId = projectId,
                issueTypeId = issueTypeId,
                name = name,
                content = content,
            )
        return tryInsert(domain, name)
    }

    /**
     * 이슈 템플릿의 name 과 content 를 수정한다.
     *
     * null = 무변경(sentinel 정책). 도메인 [IssueTemplate.withChanges] 를 경유하여
     * create 와 동일한 불변식 검증이 적용된다. (메모리: patch-merge-domain-bypass)
     *
     * 흐름.
     * 1. UPDATE 권한 검증.
     * 2. 기존 템플릿 조회 — 미존재 시 [IssueTemplateNotFoundException].
     * 3. [IssueTemplate.withChanges] — 불변식 위반 시 [com.bts.issue.template.domain.InvalidIssueTemplateException].
     * 4. [IssueTemplateRepository.update] 위임.
     *
     * @param actorId 수정 행위자 UUID.
     * @param projectId 대상 프로젝트 UUID.
     * @param templateId 수정할 템플릿 UUID.
     * @param name 새 템플릿 이름. null 이면 기존 유지. 전달 시 blank 는 422 거부됨.
     * @param content 새 템플릿 본문 내용. null 이면 기존 유지. 전달 시 blank 는 422 거부됨.
     * @return 수정된 [IssueTemplate].
     * @throws IssueTemplateAccessDeniedException UPDATE 권한이 없을 때.
     * @throws IssueTemplateNotFoundException 템플릿이 존재하지 않을 때.
     * @throws com.bts.issue.template.domain.InvalidIssueTemplateException name/content 불변식 위반 시.
     */
    fun update(
        actorId: UUID,
        projectId: UUID,
        templateId: UUID,
        name: String?,
        content: String?,
    ): IssueTemplate {
        assertPermission(actorId, TemplatePermission.UPDATE, projectId)
        val existing = findActiveTemplate(templateId)
        val updated = existing.withChanges(name = name, content = content)
        repo.update(templateId, updated.name, updated.content)
        log.info("issue_template_updated id={} projectId={} actor={}", templateId, projectId, actorId)
        return updated
    }

    /**
     * 이슈 템플릿을 소프트 삭제한다.
     *
     * 흐름.
     * 1. DELETE 권한 검증.
     * 2. 기존 템플릿 조회 — 미존재 시 [IssueTemplateNotFoundException].
     * 3. [IssueTemplateRepository.softDelete] 위임.
     *
     * @param actorId 삭제 행위자 UUID.
     * @param projectId 대상 프로젝트 UUID.
     * @param templateId 삭제할 템플릿 UUID.
     * @throws IssueTemplateAccessDeniedException DELETE 권한이 없을 때.
     * @throws IssueTemplateNotFoundException 템플릿이 존재하지 않을 때.
     */
    fun delete(
        actorId: UUID,
        projectId: UUID,
        templateId: UUID,
    ) {
        assertPermission(actorId, TemplatePermission.DELETE, projectId)
        findActiveTemplate(templateId)
        repo.softDelete(templateId)
        log.info("issue_template_deleted id={} projectId={} actor={}", templateId, projectId, actorId)
    }

    /**
     * 단건 이슈 템플릿을 조회한다.
     *
     * READ 는 권한 게이트 없음.
     *
     * @param actorId 조회 행위자 UUID. (로깅 목적)
     * @param templateId 조회할 템플릿 UUID.
     * @return 활성 [IssueTemplate].
     * @throws IssueTemplateNotFoundException 템플릿이 존재하지 않을 때.
     */
    @Transactional(readOnly = true)
    fun getById(
        actorId: UUID,
        templateId: UUID,
    ): IssueTemplate {
        log.debug("issue_template_get_by_id id={} actor={}", templateId, actorId)
        return findActiveTemplate(templateId)
    }

    /**
     * 프로젝트 소속 활성 이슈 템플릿 목록을 조회한다.
     *
     * READ 는 권한 게이트 없음.
     *
     * @param actorId 조회 행위자 UUID. (로깅 목적)
     * @param projectId 조회할 프로젝트 UUID.
     * @return 활성 [IssueTemplate] 리스트. 없으면 빈 리스트.
     */
    @Transactional(readOnly = true)
    fun listByProject(
        actorId: UUID,
        projectId: UUID,
    ): List<IssueTemplate> {
        log.debug("issue_template_list_by_project projectId={} actor={}", projectId, actorId)
        return repo.findByProject(projectId)
    }

    /**
     * (projectId, issueTypeId) 조합의 활성 템플릿 content 를 반환한다.
     *
     * 이슈 생성 안전망(옵션 C)에서 사용한다.
     * 활성 템플릿이 없으면 null 을 반환한다. READ 미게이트.
     *
     * @param projectId 조회할 프로젝트 UUID.
     * @param issueTypeId 조회할 이슈 타입 BIGINT.
     * @return 활성 템플릿의 content 문자열, 없으면 null.
     */
    @Transactional(readOnly = true)
    fun resolve(
        projectId: UUID,
        issueTypeId: Long,
    ): String? = repo.findActiveContentByProjectAndType(projectId, issueTypeId)

    // ── private helpers ───────────────────────────────────────────────────────

    /**
     * 행위자의 권한을 검증한다. 거부 시 [IssueTemplateAccessDeniedException] 을 던진다.
     */
    private fun assertPermission(
        actorId: UUID,
        permission: TemplatePermission,
        projectId: UUID,
    ) {
        if (!permissionResolver.hasPermission(actorId, permission, projectId)) {
            throw IssueTemplateAccessDeniedException(actorId, projectId)
        }
    }

    /**
     * issueTypeId 에 해당하는 활성 이슈 타입이 존재하는지 선검증한다.
     * 미존재 시 [IssueTypeNotFoundException] 을 던진다.
     */
    private fun assertIssueTypeExists(issueTypeId: Long) {
        val id = IssueTypeId(issueTypeId)
        if (issueTypeRepository.findById(id) == null) {
            throw IssueTypeNotFoundException(id)
        }
    }

    /**
     * 활성 이슈 템플릿을 조회한다. 미존재 시 [IssueTemplateNotFoundException] 을 던진다.
     */
    private fun findActiveTemplate(templateId: UUID): IssueTemplate =
        repo.findById(templateId) ?: throw IssueTemplateNotFoundException(templateId)

    /**
     * [IssueTemplateRepository.insert] 를 호출하고, 23505 SQLState 위반은
     * [DuplicateIssueTemplateException] 으로 변환한다.
     */
    private fun tryInsert(
        template: IssueTemplate,
        name: String,
    ): IssueTemplate =
        try {
            repo.insert(template)
        } catch (ex: DataIntegrityViolationException) {
            translateUniqueViolation(ex, name)
        } catch (ex: org.jooq.exception.IntegrityConstraintViolationException) {
            translateUniqueViolation(ex, name)
        }

    /**
     * 제약 위반 예외가 23505 unique_violation 에서 비롯됐으면 [DuplicateIssueTemplateException] 으로
     * 변환한다. 그렇지 않으면 원 예외를 그대로 re-throw 한다.
     */
    @Suppress("ThrowsCount")
    private fun translateUniqueViolation(
        ex: RuntimeException,
        name: String,
    ): Nothing {
        val sqlEx =
            generateSequence(ex.cause) { it.cause }
                .filterIsInstance<java.sql.SQLException>()
                .firstOrNull()
        if (sqlEx?.sqlState == SQL_STATE_UNIQUE_VIOLATION) {
            log.warn("Duplicate issue template name={}", name)
            throw DuplicateIssueTemplateException(name)
        }
        throw ex
    }
}
