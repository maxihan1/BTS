// 로그인 사용자가 접근 가능한 프로젝트 목록을 조회하는 읽기 전용 Application Service (FR-PJ PR-3 Task 3)

package com.bts.issue.project.query

import com.bts.issue.project.domain.Project
import com.bts.issue.project.repository.ProjectQueryRepository
import com.bts.shared.membership.ProjectMembershipPort
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * 로그인 사용자가 접근 가능한 프로젝트 목록을 조회하는 읽기 전용 Application Service.
 *
 * 접근 가능 여부는 [ProjectMembershipPort] (cross-BC 창구)가 반환한 프로젝트 key 집합으로
 * 결정한다. 이 포트 외부에서 별도의 멤버십 조회 경로를 만들지 않는다(포트 KDoc 계약).
 *
 * ### fail-closed (PJ2-4)
 * [ProjectMembershipPort.projectKeysOf] 가 빈 Set 을 반환하면(멤버십 0개) 조회 자체를 생략하지
 * 않고 그대로 [ProjectQueryRepository.findAccessibleByKeys] 에 전달한다. 그 메서드는 빈 Set 이면
 * 즉시 빈 목록을 반환하므로 결과적으로 존재 누설이 0이다. nullable 술어로 조기 return 하지 않는다.
 *
 * @param projectQueryRepository 프로젝트 목록/설정 조회 전담 읽기 전용 Repository.
 * @param projectMembershipPort 사용자의 프로젝트 멤버십 key 집합을 조회하는 cross-BC 포트.
 */
@Service
@Transactional(readOnly = true)
class ProjectQueryService(
    private val projectQueryRepository: ProjectQueryRepository,
    private val projectMembershipPort: ProjectMembershipPort,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * [actorId] 가 접근 가능한 활성 프로젝트 목록을 name 오름차순으로 반환한다.
     *
     * @param actorId 조회 주체 사용자 UUID.
     * @return 접근 가능한 프로젝트 목록. 멤버십이 없으면 빈 목록(fail-closed).
     */
    fun listAccessible(actorId: UUID): List<Project> {
        val keys = projectMembershipPort.projectKeysOf(actorId)
        log.debug("ProjectQueryService.listAccessible actorId={} keyCount={}", actorId, keys.size)
        return projectQueryRepository.findAccessibleByKeys(keys)
    }
}
