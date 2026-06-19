// 프로젝트 멤버/관리자 수신자 cross-BC 조회 adapter (ProjectRecipientLookupPort 구현체)

package com.atlas.bts.identity.project

import com.bts.shared.issue.ProjectRecipientLookupPort
import com.bts.shared.issue.ProjectRecipients
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * [ProjectRecipientLookupPort] identity-access BC 구현체 (FR-NT-03 Task 5).
 *
 * notification BC 가 프로젝트 범위 알림 대상자를 결정할 때 이 어댑터를 호출한다.
 * [ProjectDirectory.resolveKeyToId] 로 projectKey → UUID 를 해석하고,
 * [ProjectMembershipRepository.listByProject] 로 멤버 목록을 조회해
 * memberIds(전체) / adminIds(PROJECT_ADMIN 한정)로 분류한다.
 *
 * ## fail-safe 방향
 * - projectKey 가 존재하지 않거나 소프트 삭제된 경우 [ProjectRecipients.empty] 를 반환한다.
 * - 알림 누락이 과발송보다 안전하다.
 *
 * ## BC 격리
 * shared-kernel [ProjectRecipientLookupPort] 만 구현한다.
 * notification BC 는 이 클래스를 직접 import 하지 않는다.
 *
 * @param projectDirectory projectKey → UUID 변환 포트
 * @param membershipRepository 멤버십 목록 조회 repository
 */
@Component
class ProjectRecipientLookupAdapter(
    private val projectDirectory: ProjectDirectory,
    private val membershipRepository: ProjectMembershipRepository,
) : ProjectRecipientLookupPort {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 주어진 projectKey 의 전체 멤버 UUID 목록과 관리자 UUID 목록을 반환한다.
     *
     * 프로젝트가 존재하지 않거나 소프트 삭제된 경우 [ProjectRecipients.empty] 를 반환한다(fail-safe).
     *
     * @param projectKey "PROJ" 형태의 프로젝트 키
     * @return 멤버·관리자 UUID 목록. 조회 불가 시 빈 수신자.
     */
    @Transactional(readOnly = true)
    override fun findProjectRecipients(projectKey: String): ProjectRecipients {
        log.debug("findProjectRecipients projectKey={}", projectKey)

        val projectId =
            projectDirectory.resolveKeyToId(projectKey)
                ?: run {
                    log.debug("findProjectRecipients projectKey={} — 프로젝트 미존재 또는 소프트 삭제, 빈 수신자 반환", projectKey)
                    return ProjectRecipients.empty()
                }

        val memberships = membershipRepository.listByProject(projectId)
        val adminIds =
            memberships
                .filter { it.role == ProjectRole.PROJECT_ADMIN }
                .map { it.userId }

        return ProjectRecipients(
            memberIds = memberships.map { it.userId },
            adminIds = adminIds,
        )
    }
}
