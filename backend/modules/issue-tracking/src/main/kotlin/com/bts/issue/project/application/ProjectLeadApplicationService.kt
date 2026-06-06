// 프로젝트 리드 지정·해제·조회 ApplicationService — ProjectLookup + UserLookupPort 경유

package com.bts.issue.project.application

import com.bts.issue.project.ProjectLookup
import com.bts.issue.project.domain.ProjectLeadNotFoundException
import com.bts.issue.project.domain.ProjectLeadProjectNotFoundException
import com.bts.issue.project.repository.ProjectLeadRepository
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
 * **흐름 — changeLead.**
 * 1. [ProjectLookup.resolve] 로 projectIdOrKey → 활성 프로젝트 UUID 해석.
 *    null 이면 [ProjectLeadProjectNotFoundException] 을 직접 던진다
 *    (ProjectLookup 은 null 반환만 하며 throw 하지 않으므로 서비스가 처리).
 * 2. leadUserId non-null 이면 [UserLookupPort.exists] 검증 — false 면 [ProjectLeadNotFoundException].
 * 3. [ProjectLeadRepository.updateLead] 로 lead_user_id 갱신.
 *
 * **트랜잭션.**
 * 클래스 레벨 @Transactional 이 기본(읽기/쓰기 모두). changeLead 는 write 이므로
 * 추가 선언 불요. DEVELOPMENT.md §1 — public service 메서드 전체 @Transactional 명시 원칙에 따라
 * 클래스 레벨로 커버한다.
 */
@Service
@Transactional
class ProjectLeadApplicationService(
    private val repository: ProjectLeadRepository,
    private val projectLookup: ProjectLookup,
    private val userLookupPort: UserLookupPort,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 프로젝트 리드를 지정하거나 해제한다.
     *
     * @param actorId 요청 행위자 UUID (향후 권한 검증 확장용 — 현재는 로깅만).
     * @param projectIdOrKey 대상 프로젝트 UUID 문자열 또는 projectKey.
     * @param leadUserId 지정할 리드 사용자 UUID. null 이면 해제.
     * @return [ProjectLeadResult] — projectId + leadUserId.
     * @throws ProjectLeadProjectNotFoundException 프로젝트가 미존재하거나 소프트삭제된 경우 (404 의도).
     * @throws ProjectLeadNotFoundException leadUserId 가 non-null 이면서 시스템에 존재하지 않는 경우 (422 의도).
     */
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

        val projectId = projectLookup.resolve(projectIdOrKey)
            ?: throw ProjectLeadProjectNotFoundException(projectIdOrKey)

        validateLead(leadUserId)

        repository.updateLead(projectId, leadUserId)

        return ProjectLeadResult(projectId = projectId, leadUserId = leadUserId)
    }

    // ── private helpers ───────────────────────────────────────────────────────

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
