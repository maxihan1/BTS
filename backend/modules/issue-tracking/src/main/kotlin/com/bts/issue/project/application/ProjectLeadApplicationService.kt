// 프로젝트 리드 지정·해제 ApplicationService — 컴포넌트 UPDATE 권한 가드 + ProjectLookup + UserLookupPort 경유

package com.bts.issue.project.application

import com.bts.issue.project.ProjectLookup
import com.bts.issue.project.archive.ProjectArchiveGuard
import com.bts.issue.project.domain.ProjectLeadAccessDeniedException
import com.bts.issue.project.domain.ProjectLeadNotFoundException
import com.bts.issue.project.domain.ProjectLeadProjectNotFoundException
import com.bts.issue.project.repository.ProjectLeadRepository
import com.bts.shared.permission.ComponentPermission
import com.bts.shared.permission.ComponentPermissionResolver
import com.bts.shared.user.UserLookupPort
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * 컴포넌트 리드 changeLead 호출 결과와 동형 — projectId + leadUserId 반환.
 *
 * 컨트롤러가 응답 DTO 로 변환할 때 이 결과를 사용한다.
 */
data class ProjectLeadResult(
    val projectId: UUID,
    val leadUserId: UUID?,
)

/**
 * 프로젝트 리드 지정·해제·조회 ApplicationService.
 *
 * [com.bts.issue.component.application.ComponentApplicationService.changeLead] 와 동형 패턴.
 *
 * **흐름 — changeLead (컴포넌트 동형: 존재 → 권한 → 입력검증 → 영속).**
 * 1. [ProjectLookup.resolve] 로 projectIdOrKey → 활성 프로젝트 UUID 해석.
 *    null 이면 [ProjectLeadProjectNotFoundException] 을 직접 던진다
 *    (ProjectLookup 은 null 반환만 하며 throw 하지 않으므로 서비스가 처리).
 * 2. [ComponentPermissionResolver] 로 UPDATE 권한 검증 — 거부 시 [ProjectLeadAccessDeniedException].
 * 3. leadUserId non-null 이면 [UserLookupPort.exists] 검증 — false 면 [ProjectLeadNotFoundException].
 * 4. [ProjectLeadRepository.updateLead] 로 lead_user_id 갱신.
 *
 * **권한 게이트 — 컴포넌트 UPDATE 재사용.**
 * 프로젝트 리드는 컴포넌트 기본담당자(component lead) 폴백이므로
 * [com.bts.issue.component.application.ComponentApplicationService.changeLead] 와 동일하게
 * [ComponentPermission.UPDATE](MANAGE_COMPONENTS, PROJECT_ADMIN 전용)로 게이트한다.
 * 별도 권한코드/마이그레이션을 두지 않는다 — 의미가 정합하기 때문.
 * prod 실판정은 identity-access 의 IdentityAccessComponentPermissionResolver(동일 포트)가,
 * non-prod 는 AlwaysAllowComponentPermissionResolver 가 담당한다.
 *
 * **트랜잭션.**
 * 클래스 레벨 @Transactional 이 기본(읽기/쓰기 모두). changeLead 는 write 이므로
 * 추가 선언 불요. DEVELOPMENT.md §1 — public service 메서드 전체 @Transactional 명시 원칙에 따라
 * 클래스 레벨로 커버한다.
 *
 * **아카이브 잠금(FR-PJ-04 PR-4 Task 7 — PJ3-2).** [ProjectArchiveGuard.check] 를
 * [assertPermission] 직후, leadUserId 검증·repository 갱신 이전에 호출한다(D-ORDER). 미인가
 * actor 가 409 로 아카이브 상태를 알아내지 못하도록 permission 검증을 항상 먼저 통과시킨다.
 */
@Service
@Transactional
class ProjectLeadApplicationService(
    private val repository: ProjectLeadRepository,
    private val projectLookup: ProjectLookup,
    private val userLookupPort: UserLookupPort,
    private val permissionResolver: ComponentPermissionResolver,
    private val archiveGuard: ProjectArchiveGuard,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 프로젝트 리드를 조회한다.
     *
     * READ 는 컴포넌트 READ 정책과 동일하게 권한 게이트 없음.
     *
     * @param projectIdOrKey 대상 프로젝트 UUID 문자열 또는 projectKey.
     * @return [ProjectLeadResult] — projectId + leadUserId (null 이면 미지정).
     * @throws ProjectLeadProjectNotFoundException 프로젝트가 미존재하거나 소프트삭제된 경우 (404 의도).
     */
    @Transactional(readOnly = true)
    fun getLead(projectIdOrKey: String): ProjectLeadResult {
        log.debug("ProjectLeadApplicationService.getLead projectIdOrKey={}", projectIdOrKey)

        val projectId =
            projectLookup.resolve(projectIdOrKey)
                ?: throw ProjectLeadProjectNotFoundException(projectIdOrKey)

        val leadUserId = repository.findLeadUserId(projectId)

        return ProjectLeadResult(projectId = projectId, leadUserId = leadUserId)
    }

    /**
     * 프로젝트 리드를 지정하거나 해제한다.
     *
     * @param actorId 요청 행위자 UUID — 컴포넌트 UPDATE 권한 검증 대상.
     * @param projectIdOrKey 대상 프로젝트 UUID 문자열 또는 projectKey.
     * @param leadUserId 지정할 리드 사용자 UUID. null 이면 해제.
     * @return [ProjectLeadResult] — projectId + leadUserId.
     * @throws ProjectLeadProjectNotFoundException 프로젝트가 미존재하거나 소프트삭제된 경우 (404 의도).
     * @throws ProjectLeadAccessDeniedException 행위자에게 컴포넌트 UPDATE 권한이 없는 경우 (403 의도).
     * @throws ProjectLeadNotFoundException leadUserId 가 non-null 이면서 시스템에 존재하지 않는 경우 (422 의도).
     */
    @Suppress("ThrowsCount")
    fun changeLead(
        actorId: UUID,
        projectIdOrKey: String,
        leadUserId: UUID?,
    ): ProjectLeadResult {
        log.debug(
            "ProjectLeadApplicationService.changeLead actor={} projectIdOrKey={} leadUserId={}",
            actorId,
            projectIdOrKey,
            leadUserId,
        )

        val projectId =
            projectLookup.resolve(projectIdOrKey)
                ?: throw ProjectLeadProjectNotFoundException(projectIdOrKey)

        assertPermission(actorId, projectId)
        archiveGuard.check(projectId)

        validateLead(leadUserId)

        repository.updateLead(projectId, leadUserId)

        return ProjectLeadResult(projectId = projectId, leadUserId = leadUserId)
    }

    // ── private helpers ───────────────────────────────────────────────────────

    /**
     * 행위자의 컴포넌트 [ComponentPermission.UPDATE] 권한을 검증한다.
     * 거부 시 [ProjectLeadAccessDeniedException] 을 던진다.
     *
     * [com.bts.issue.component.application.ComponentApplicationService.assertPermission] 과 동형.
     * 프로젝트 리드는 컴포넌트 기본담당자 폴백이므로 동일한 UPDATE 권한으로 게이트한다.
     */
    private fun assertPermission(
        actorId: UUID,
        projectId: UUID,
    ) {
        if (!permissionResolver.hasPermission(actorId, ComponentPermission.UPDATE, projectId)) {
            throw ProjectLeadAccessDeniedException(actorId, ComponentPermission.UPDATE, projectId)
        }
    }

    /**
     * leadUserId non-null 이면 [UserLookupPort.exists] 로 실재 검증한다.
     * 미존재 시 [ProjectLeadNotFoundException] 을 던진다.
     *
     * [com.bts.issue.component.application.ComponentApplicationService.validateLead] 와 동형.
     */
    private fun validateLead(leadUserId: UUID?) {
        if (leadUserId != null && !userLookupPort.exists(leadUserId)) {
            throw ProjectLeadNotFoundException(leadUserId)
        }
    }
}
