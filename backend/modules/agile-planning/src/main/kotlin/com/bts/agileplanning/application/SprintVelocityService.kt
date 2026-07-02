// 스프린트 벨로시티 오케스트레이션 애플리케이션 서비스 — agile-planning BC (FR-RP-02)

package com.bts.agileplanning.application

import com.bts.agileplanning.domain.Sprint
import com.bts.agileplanning.domain.SprintStatus
import com.bts.agileplanning.domain.velocity.SprintVelocityResult
import com.bts.agileplanning.domain.velocity.VelocityPoint
import com.bts.agileplanning.repository.SprintRepository
import com.bts.shared.permission.IssuePermission
import com.bts.shared.permission.IssuePermissionResolver
import com.bts.shared.permission.IssueScope
import com.bts.shared.velocity.SprintVelocityLookupPort
import com.bts.shared.velocity.VelocityContribution
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * 스프린트 벨로시티(Velocity) 시계열을 조회하는 애플리케이션 서비스.
 *
 * COMPLETED 스프린트 최근 N개를 대상으로 계획(commitment) 대비 완료(completed) 작업량을
 * 조립한다. cross-BC 통신은 shared-kernel 포트([SprintVelocityLookupPort],
 * [IssuePermissionResolver])만 사용한다. issue-tracking 내부를 직접 import 하지 않는다(BC 격리).
 *
 * ## 처리 순서
 * 1. 프로젝트 BROWSE 권한 판정([SprintBurndownService], [BacklogApplicationService] 게이트 미러) — 거부 시 403.
 * 2. `limit` 을 [MIN_LIMIT]~[MAX_LIMIT] 범위로 클램프.
 * 3. [SprintRepository.findByProject] 로 COMPLETED 스프린트를 created_at 오름차순으로 조회한 뒤
 *    `takeLast(limit)` 로 최근 N개만 선택(오름차순 유지).
 * 4. [SprintRepository.findIssueKeysByProject] 로 프로젝트 전체 스프린트의 이슈 키를 단일 쿼리로
 *    조회(N+1 차단)한 뒤 선택된 스프린트로만 필터링.
 * 5. [SprintVelocityLookupPort.fetchVelocitySource] 1회 호출로 스프린트별 원천 데이터를 조회.
 * 6. 포트가 특정 sprintId 를 반환하지 않으면 [VelocityContribution]`(0, 0)` 기본값으로 처리한다.
 *
 * ## 보안 그레인
 * 프로젝트 BROWSE 를 게이트로 판정한 뒤, 원천 조회 포트에 actor 를 전달해 이슈별
 * [IssueScope.Issue] 가시성(security_level) 필터를 위임한다([SprintBurndownService] 와 동일 패턴).
 *
 * @param velocityPort 벨로시티 원천 데이터 cross-BC 조회 포트(issue-tracking 구현).
 * @param sprintRepository sprints / sprint_issues jOOQ repository.
 * @param permissionResolver cross-BC 권한 판정 포트(fail-closed, non-null 주입).
 */
@Service
class SprintVelocityService(
    private val velocityPort: SprintVelocityLookupPort,
    private val sprintRepository: SprintRepository,
    private val permissionResolver: IssuePermissionResolver,
) {
    /**
     * 프로젝트의 최근 완료 스프린트 벨로시티 시계열을 계산해 반환한다.
     *
     * @param actorId 조회 행위자 UUID.
     * @param projectKey 조회할 프로젝트 키.
     * @param limit 조회할 최근 COMPLETED 스프린트 개수. [MIN_LIMIT]~[MAX_LIMIT] 범위로 클램프한다.
     * @return [SprintVelocityResult] — 시간순 오름차순 스프린트별 지점 + 평균.
     * @throws ResponseStatusException 403 — 프로젝트 BROWSE 권한 미충족.
     */
    @Transactional(readOnly = true)
    fun getVelocity(
        actorId: UUID,
        projectKey: String,
        limit: Int,
    ): SprintVelocityResult {
        requireBrowsePermission(actorId, projectKey)
        val clampedLimit = limit.coerceIn(MIN_LIMIT, MAX_LIMIT)

        val selectedSprints =
            sprintRepository.findByProject(projectKey, SprintStatus.COMPLETED).takeLast(clampedLimit)
        val issueKeysBySprint = buildIssueKeysBySprint(projectKey, selectedSprints)
        val contributions = velocityPort.fetchVelocitySource(issueKeysBySprint, projectKey, actorId)

        val points = selectedSprints.map { sprint -> toVelocityPoint(sprint, contributions[sprint.id]) }
        return SprintVelocityResult.of(projectKey, points)
    }

    /**
     * [projectKey] 에 대한 프로젝트 BROWSE 권한을 판정하고 미충족 시 403 을 던진다.
     *
     * fail-closed — permissionResolver 는 non-null 주입이므로 빈 부재 시 부팅이 실패한다.
     * 거부 메시지는 일반화되어 내부 정보를 노출하지 않는다.
     */
    private fun requireBrowsePermission(
        actorId: UUID,
        projectKey: String,
    ) {
        val allowed =
            permissionResolver.hasPermission(actorId, IssuePermission.BROWSE, IssueScope.Project(projectKey))
        if (!allowed) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "접근 권한이 없습니다.")
        }
    }

    /**
     * 프로젝트 전체 sprintId → issueKeys 맵을 단일 쿼리로 조회한 뒤 [selectedSprints] 로만 필터링해
     * `Set` 으로 변환한다. 이슈가 없는 스프린트는 원본 맵에 키가 없으므로 자연히 제외된다
     * (포트 쪽에서 `contributions[sprintId] ?: VelocityContribution(0, 0)` 기본 처리와 동치).
     */
    private fun buildIssueKeysBySprint(
        projectKey: String,
        selectedSprints: List<Sprint>,
    ): Map<UUID, Set<String>> {
        val selectedIds = selectedSprints.map { it.id }.toSet()
        return sprintRepository.findIssueKeysByProject(projectKey)
            .filterKeys { it in selectedIds }
            .mapValues { (_, issueKeys) -> issueKeys.toSet() }
    }

    /**
     * [sprint] 와 포트가 반환한 [contribution] 을 [VelocityPoint] 로 조립한다.
     *
     * [contribution] 이 null 이면(포트 미반환) 계획·완료 시간을 0 으로 기본 처리한다.
     */
    private fun toVelocityPoint(
        sprint: Sprint,
        contribution: VelocityContribution?,
    ): VelocityPoint {
        val resolved = contribution ?: VelocityContribution(commitmentSeconds = 0L, completedSeconds = 0L)
        return VelocityPoint(
            sprintId = sprint.id,
            name = sprint.name,
            startDate = sprint.startDate,
            endDate = sprint.endDate,
            commitmentSeconds = resolved.commitmentSeconds,
            completedSeconds = resolved.completedSeconds,
        )
    }

    companion object {
        /** limit 클램프 하한 — 컨트롤러 기본값 지정은 상위 계층 책임. */
        private const val MIN_LIMIT = 1

        /** limit 클램프 상한 — 대량 조회로 인한 과도한 응답 크기를 방지한다. */
        private const val MAX_LIMIT = 50
    }
}
