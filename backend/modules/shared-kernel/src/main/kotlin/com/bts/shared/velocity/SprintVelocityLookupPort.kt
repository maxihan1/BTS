// cross-BC 벨로시티 원천 데이터 조회 포트 (agile-planning → issue-tracking 위임) — FR-RP-02

package com.bts.shared.velocity

import java.util.UUID

/**
 * 스프린트 벨로시티 원천 데이터 cross-BC 조회 포트 — agile-planning BC 용 (FR-RP-02).
 *
 * agile-planning BC 가 여러 스프린트의 벨로시티(계획 대비 완료 작업량)를 계산할 때,
 * 각 스프린트에 속한 이슈들의 계획(commitment) 추정 시간과 완료(completed) 추정 시간 합계를
 * 얻기 위해 이 포트를 호출한다. 구현체는 issue-tracking BC 가 제공하며, 두 BC 는
 * shared-kernel 을 통해 간접 의존한다. agile-planning 은 issue-tracking 을 직접 gradle
 * 의존하지 않는다.
 *
 * ### BC 격리 사유 — shared-kernel 배치
 *
 * agile-planning 과 issue-tracking 이 shared-kernel 만 공유 의존한다.
 * agile-planning 이 issue-tracking 내부를 직접 import 하면 BC 경계가 무너지고
 * 순환 의존 위험이 생긴다. 이 포트를 shared-kernel 에 배치함으로써 두 BC 는 서로를
 * gradle 수준에서 의존하지 않는다 (BC 격리 룰, ArchUnit 강제).
 *
 * ### 의존 방향
 * ```
 * agile-planning ──(port)──▶ shared-kernel ◀──(impl)──  issue-tracking
 * ```
 *
 * ### fail-safe default 구현
 *
 * issue-tracking adapter 가 등록되지 않은 환경(테스트 stub, 단계적 배포)에서도
 * 빈 map 을 반환해 벨로시티 계산이 예외 없이 안전하게 진행된다.
 * 데이터 조회 실패는 보안 판단이 아니므로 fail-safe 방향이 적절하다
 * (권한 resolver 의 fail-closed 와 다른 방향 — IssuePermissionResolver 참조).
 *
 * @see VelocityContribution
 */
interface SprintVelocityLookupPort {
    /**
     * 스프린트별 이슈 키 집합의 벨로시티 원천 데이터를 [VelocityContribution] map 으로 반환한다.
     *
     * soft-deleted 이슈는 결과에서 제외된다. 이 메서드는 읽기 전용이며 부수 효과가 없다.
     *
     * ### viewer 가시 이슈만 집계 — 이슈별 보안(security_level) 필터
     *
     * 프로젝트 BROWSE 권한만으로는 이슈별 보안 등급([com.bts.shared.permission.IssueScope.Issue])으로
     * 차단된 기밀 이슈의 시간값이 집계에 섞여 viewer 가 간접 추론할 수 있다. 따라서 구현체는
     * [viewerUserId] 가 볼 수 없는 이슈의 estimate 를 집계에서 제외한다. 이 경우 벨로시티는
     * viewer 스코프의 부분값이 될 수 있으며 이는 의도된 동작이다. 가시성 판정은 issue-tracking BC 의
     * 정본 보안 술어를 재사용해야 하며 별도 복제 판정 경로를 만들면 안 된다(보안갭 방지).
     *
     * @param issueKeysBySprint 스프린트 UUID → 해당 스프린트에 속한 이슈 키 집합. 값이 빈 집합인
     *   스프린트는 구현체가 조기 반환해야 한다(jOOQ 빈 `IN` 절 함정 방지) — 이 경우 해당 스프린트는
     *   계획 0 · 완료 0 으로 귀결된다.
     * @param projectKey 이슈들이 속한 프로젝트 키. 보안 술어의 프로젝트 스코프 판정에 사용된다.
     * @param viewerUserId 벨로시티를 조회하는 viewer UUID. 이슈별 가시성 필터 기준.
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
 * [SprintVelocityLookupPort.fetchVelocitySource] 가 반환하는 읽기 전용 값 객체.
 * 계획(commitment) 추정 시간 합계와 완료(completed) 추정 시간 합계를 담는다.
 *
 * @property commitmentSeconds 스프린트에 계획된 이슈들의 추정 시간 합계(초).
 *   NULL 추정치는 0 으로 간주해 합산한다.
 * @property completedSeconds 스프린트에서 완료 상태로 전이된 이슈들의 추정 시간 합계(초).
 */
data class VelocityContribution(
    val commitmentSeconds: Long,
    val completedSeconds: Long,
)
