// 사용자 실재 검증 cross-BC 포트 — issue-tracking 이 assignee 존재 확인에 사용

package com.bts.shared.user

import java.util.UUID

/**
 * 사용자 존재 여부 cross-BC 조회 포트 (FR-IS-03 Task 3 / ADR 2026-06-01-issue-assignee-user-lookup-port).
 *
 * issue-tracking BC 가 assignee 배정 전 사용자 실재를 검증할 때 이 인터페이스를 호출한다.
 * 구현체는 identity-access BC 가 제공하며, 양쪽 BC 는 shared-kernel 을 통해 간접 의존한다.
 * issue-tracking 은 identity-access 를 직접 gradle 의존하지 않는다.
 *
 * ### 의존 방향
 * ```
 * issue-tracking  ──(implementation)──▶  shared-kernel ◀──(implementation)──  identity-access
 * ```
 *
 * ### "실재" 정의
 * users 테이블에 해당 id 행이 존재하면 실재로 간주한다.
 * is_active / deleted_at 같은 소프트 삭제 컬럼은 users 테이블에 없으므로 행 존재 여부만 확인한다
 * (V001 스키마 기준 — id / username / email / display_name / created_at / updated_at).
 */
interface UserLookupPort {
    /**
     * 주어진 사용자 UUID 가 실재하는지(users 테이블 행 존재) 확인한다.
     *
     * 이 메서드는 읽기 전용이며 부수 효과가 없다.
     * 호출자는 트랜잭션 컨텍스트 없이도 호출할 수 있다.
     *
     * @param userId 확인할 사용자의 UUID
     * @return 행이 존재하면 true, 없으면 false
     */
    fun exists(userId: UUID): Boolean
}
