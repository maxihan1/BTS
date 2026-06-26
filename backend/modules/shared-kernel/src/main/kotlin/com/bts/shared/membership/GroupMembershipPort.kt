// 그룹 멤버십 조회 포트 — 사용자가 속한 그룹 ID 집합을 cross-BC 방식으로 반환

package com.bts.shared.membership

import java.util.UUID

/**
 * 사용자가 속한 그룹 ID 집합을 조회하는 cross-BC 포트.
 *
 * 필터 공유 가시성(FR-SR-03) 판정에서 팀/그룹 단위 공유 접근권을 확인하기 위해
 * identity-access BC 구현이 이 포트를 제공한다. 소비 측(search-export-import BC)은
 * 이 포트만을 통해 그룹 멤버십을 조회한다.
 *
 * ### default 구현 금지 (fail-closed)
 * **default 구현 없음.** 소비 BC 에서 이 포트의 Bean 이 등록되지 않으면 부팅 자체가
 * 실패하도록 설계된 안전망이다. allow-all default 를 추가하면 그룹 멤버십 판정이
 * 우회되어 공유 가시성 누출이 발생하므로 절대 금지한다.
 *
 * ### 새 보안 경로 금지
 * 그룹 멤버십 기반 가시성 판정은 이 포트로만 이루어진다. 이 포트 외부에서 별도의
 * 그룹 멤버십 조회 경로를 만드는 것을 금지한다.
 *
 * ### BC 경계 규칙
 * 파라미터와 반환 타입은 원시 타입(UUID/String/Set)만 사용한다.
 * identity-access 또는 타 BC 도메인 타입을 이 인터페이스에 사용하면
 * `SharedKernelBoundaryArchTest` 가 빌드를 차단한다.
 *
 * ### 빈 Set 의미
 * 빈 Set 반환 = "멤버십 0개"(fail-closed 방향). 사용자가 어느 그룹에도 속하지 않음을
 * 의미하며 allow-all 이 아니다.
 *
 * @see com.bts.shared.permission.IssueVisibilityPort 동일 fail-closed 원칙을 따르는 선례 포트
 */
interface GroupMembershipPort {
    /**
     * 주어진 사용자 ID([userId])가 속한 그룹의 ID 집합을 반환한다.
     *
     * 그룹 멤버십이 없거나 조회 불가 시 빈 Set 을 반환해야 한다(구현 계약, fail-closed).
     *
     * @param userId 조회 대상 사용자 UUID
     * @return 사용자가 속한 그룹 ID 문자열 집합. 빈 Set 은 "멤버십 0개"를 의미한다.
     */
    fun groupIdsOf(userId: UUID): Set<String>
}
