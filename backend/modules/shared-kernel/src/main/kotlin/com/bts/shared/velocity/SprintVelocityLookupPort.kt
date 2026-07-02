// cross-BC 벨로시티 원천 데이터 조회 포트 (agile-planning → issue-tracking 위임) — FR-RP-02

package com.bts.shared.velocity

import java.util.UUID

/**
 * 스프린트 벨로시티 원천 데이터 cross-BC 조회 포트 — agile-planning BC 용 (FR-RP-02).
 */
interface SprintVelocityLookupPort {
    /**
     * 스프린트별 이슈 키 집합의 벨로시티 원천 데이터를 [VelocityContribution] map 으로 반환한다.
     *
     * @param issueKeysBySprint 스프린트 UUID → 해당 스프린트에 속한 이슈 키 집합.
     * @param projectKey 이슈들이 속한 프로젝트 키.
     * @param viewerUserId 벨로시티를 조회하는 viewer UUID.
     * @return 스프린트 UUID → [VelocityContribution] map. adapter 부재 또는 조회 불가 시 빈 map(fail-safe).
     */
    fun fetchVelocitySource(
        issueKeysBySprint: Map<UUID, Set<String>>,
        projectKey: String,
        viewerUserId: UUID,
    ): Map<UUID, VelocityContribution> = emptyMap()
}

/**
 * 스프린트 벨로시티 원천 데이터 VO.
 *
 * @property commitmentSeconds 스프린트에 계획된 이슈들의 추정 시간 합계(초).
 * @property completedSeconds 스프린트에서 완료 상태로 전이된 이슈들의 추정 시간 합계(초).
 */
data class VelocityContribution(
    val commitmentSeconds: Long,
    val completedSeconds: Long,
)
