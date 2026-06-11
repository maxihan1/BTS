// 프로젝트 require_2fa 토글 ApplicationService — SYSTEM_ADMIN 전용 (FR-MF-04 Task 3)

package com.bts.issue.project.application

import com.bts.issue.project.ProjectLookup
import com.bts.issue.project.domain.Require2faForbiddenException
import com.bts.issue.project.domain.Require2faProjectNotFoundException
import com.bts.issue.project.repository.ProjectRequire2faRepository
import com.bts.shared.permission.SystemPermissionResolver
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * require_2fa 토글 결과 — projectId + projectKey + requireTwoFactor.
 *
 * 컨트롤러가 응답 DTO 로 변환할 때 이 결과를 사용한다.
 *
 * @property projectId 프로젝트 UUID.
 * @property projectKey 프로젝트 키 (예: "BTS").
 * @property requireTwoFactor 갱신된 require_2fa 값.
 */
data class Require2faResult(
    val projectId: UUID,
    val projectKey: String,
    val requireTwoFactor: Boolean,
)

/**
 * 프로젝트 require_2fa(민감 프로젝트 여부) 토글 ApplicationService.
 *
 * SYSTEM_ADMIN 만 호출 가능하다. [SystemPermissionResolver.isSystemAdmin] 거부 시
 * [Require2faForbiddenException] 을 던진다.
 *
 * ## 흐름
 * 1. [ProjectLookup.resolve] 로 projectIdOrKey → 활성 프로젝트 UUID 해석.
 *    null 이면 [Require2faProjectNotFoundException].
 * 2. [SystemPermissionResolver.isSystemAdmin] 으로 SYSTEM_ADMIN 검증.
 *    false 이면 [Require2faForbiddenException].
 * 3. [ProjectRequire2faRepository.updateRequire2fa] 로 DB 갱신.
 *
 * ## 트랜잭션
 * 클래스 레벨 @Transactional 이 기본(읽기/쓰기 모두).
 * DEVELOPMENT.md §1 — public service 메서드 전체 @Transactional 명시 원칙에 따라 클래스 레벨로 커버한다.
 */
@Service
@Transactional
class ProjectRequire2faApplicationService(
    private val repository: ProjectRequire2faRepository,
    private val projectLookup: ProjectLookup,
    private val systemPermissionResolver: SystemPermissionResolver,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 프로젝트의 require_2fa(민감 프로젝트 여부)를 토글한다.
     *
     * @param actorId 요청 행위자 UUID — SYSTEM_ADMIN 검증 대상.
     * @param projectIdOrKey 대상 프로젝트 UUID 문자열 또는 projectKey.
     * @param projectKey 조회에 사용된 프로젝트 키(응답 구성 용도). projectIdOrKey 가 key 면 그대로, UUID 면 서비스가 조회.
     * @param requireTwoFactor 설정할 값. true 면 민감 프로젝트 활성, false 면 해제.
     * @return [Require2faResult] — projectId + projectKey + requireTwoFactor.
     * @throws Require2faProjectNotFoundException 프로젝트가 미존재하거나 소프트삭제된 경우 (404 의도).
     * @throws Require2faForbiddenException 행위자가 SYSTEM_ADMIN 이 아닌 경우 (403 의도).
     */
    fun toggle(
        actorId: UUID,
        projectIdOrKey: String,
        requireTwoFactor: Boolean,
    ): Require2faResult {
        log.info(
            "ProjectRequire2faApplicationService.toggle actor={} projectIdOrKey={} requireTwoFactor={}",
            actorId,
            projectIdOrKey,
            requireTwoFactor,
        )

        val projectId =
            projectLookup.resolve(projectIdOrKey)
                ?: throw Require2faProjectNotFoundException(projectIdOrKey)

        if (!systemPermissionResolver.isSystemAdmin(actorId)) {
            throw Require2faForbiddenException(actorId)
        }

        repository.updateRequire2fa(projectId, requireTwoFactor)

        return Require2faResult(
            projectId = projectId,
            projectKey = projectIdOrKey,
            requireTwoFactor = requireTwoFactor,
        )
    }
}
