// 칸반 보드 카드 목록 cross-BC 조회 포트 (agile-planning → issue-tracking 위임)

package com.bts.shared.board

import java.util.UUID

/**
 * 보드 카드(이슈) 목록 cross-BC 조회 포트 — agile-planning BC 용 (FR-BD-01).
 *
 * agile-planning BC 가 보드 조회 시 프로젝트 내 가시 이슈 목록을 얻기 위해 이 포트를 호출한다.
 * 구현체는 issue-tracking BC 가 제공하며, 두 BC 는 shared-kernel 을 통해 간접 의존한다.
 * agile-planning 은 issue-tracking 을 직접 gradle 의존하지 않는다.
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
 * 빈 목록을 반환해 보드를 안전하게 표시한다.
 * 데이터 조회 실패는 보안 판단이 아니므로 fail-safe 방향이 적절하다
 * (권한 resolver 의 fail-closed 와 다른 방향 — IssuePermissionResolver 참조).
 *
 * ### visibility 필터 책임
 *
 * viewer 가 볼 수 없는 보안 등급 이슈는 구현체가 SQL 수준에서 필터해야 한다.
 * 이 포트를 소비하는 agile-planning 은 필터 여부를 알지 못한다.
 * 필터 미적용 시 보안 등급 이슈 데이터 누출로 이어지므로 구현체 책임이 중요하다.
 *
 * @see BoardIssueView
 */
interface BoardIssueLookupPort {
    /**
     * 프로젝트의 가시 이슈 목록을 반환한다.
     *
     * viewer 가 볼 수 없는 보안 등급 이슈는 결과에서 제외된다.
     * soft-deleted 이슈는 포함하지 않는다.
     * 이 메서드는 읽기 전용이며 부수 효과가 없다.
     *
     * @param projectKey 조회할 프로젝트 키. 예: `"ATLAS"`.
     * @param viewerUserId 보드를 조회하는 사용자 UUID. visibility 필터 기준.
     * @return 가시 이슈 목록. adapter 부재 또는 조회 불가 시 빈 리스트.
     */
    fun listVisibleIssuesByProject(
        projectKey: String,
        viewerUserId: UUID,
    ): List<BoardIssueView> = emptyList()
}

/**
 * 보드 카드 단위 이슈 뷰 VO.
 *
 * [BoardIssueLookupPort.listVisibleIssuesByProject] 가 반환하는 읽기 전용 값 객체.
 * 보드 컬럼 배치([currentStateKey])와 카드 정렬([priority])에 필요한 최소 필드만 포함한다.
 *
 * @property key 이슈 키. 예: `"PROJ-1"`.
 * @property summary 이슈 제목. 카드 UI 에 표시.
 * @property currentStateKey 이슈의 현재 워크플로우 상태 키. 컬럼 배치 기준.
 * @property assigneeId 담당자 UUID. 미배정이면 null.
 * @property priority 우선순위 값. 숫자 작을수록 높은 우선순위. 컬럼 내 정렬 기준.
 * @property version 낙관적 락(OCC) 버전. 카드 이동(전이) 시 expectedVersion 으로 사용.
 */
data class BoardIssueView(
    val key: String,
    val summary: String,
    val currentStateKey: String,
    val assigneeId: UUID?,
    val priority: Int,
    val version: Long,
)
