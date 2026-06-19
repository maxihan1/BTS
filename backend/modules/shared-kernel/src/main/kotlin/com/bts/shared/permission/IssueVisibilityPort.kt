// 이슈 VIEW 가시성(매트릭스+보안등급 결합) 판정 포트 — notification 수신자 필터링 전용

package com.bts.shared.permission

import java.util.UUID

/**
 * 이슈 VIEW 가시성(매트릭스+보안등급 결합) 판정의 단일 source of truth.
 *
 * identity-access BC 구현에서 이미 검증된 권한 판정 로직을 재사용한다.
 * notification BC 가 알림 수신자 후보군을 확정하기 전에 이 포트를 통해
 * 실제로 이슈를 볼 수 있는 사용자만 필터링한다.
 *
 * ### 새 보안 경로 금지
 * 이 포트 또는 구현체 외부에 별도의 가시성 판정 경로를 만드는 것을 금지한다.
 * 보안 판정은 반드시 이 인터페이스 하나를 통해서만 이루어진다.
 *
 * ### default 금지 (fail-closed)
 * **default 구현 없음.** 빈 Bean 부재 시 notification BC 부팅 실패가 의도된 안전망이다.
 * allow-all default 는 이슈 보안등급을 우회하는 보안 누출을 초래하므로 절대 금지한다.
 * 이는 수신자 포트의 fail-safe(빈 반환)와 다른 방향 — 가시성 판정은 보안 결정이다.
 *
 * ### BC 경계 규칙
 * identity-access / issue-tracking 타입을 이 interface 에 사용하면
 * `SharedKernelBoundaryArchTest` 가 빌드를 차단한다.
 * 파라미터는 원시 타입(String, UUID, Set)만 사용한다.
 *
 * @see IssueSecurityDirectory
 */
interface IssueVisibilityPort {
    /**
     * 후보 사용자 집합에서 이슈([issueKey])를 볼 수 있는 사용자 ID 만 반환한다.
     *
     * 권한 매트릭스(VIEW_ISSUE)와 보안등급 접근 가능 여부를 결합하여 판정한다.
     * 이슈가 존재하지 않거나 판정 불가 시 빈 집합을 반환해야 한다(구현 계약).
     *
     * @param issueKey "PROJ-1" 형태의 이슈 키
     * @param candidateUserIds 수신자 후보 UUID 집합
     * @return [candidateUserIds] 중 이슈를 볼 수 있는 UUID 집합
     */
    fun filterVisibleUserIds(
        issueKey: String,
        candidateUserIds: Set<UUID>,
    ): Set<UUID>
}
