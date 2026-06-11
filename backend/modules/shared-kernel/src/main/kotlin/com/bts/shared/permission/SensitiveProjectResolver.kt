// 민감 프로젝트(require_2fa=true) 존재 여부 판정 포트(공용) — identity-access MFA 강제 창구.

package com.bts.shared.permission

import java.util.UUID

/**
 * 민감 프로젝트 여부 판정 outbound port — 전 BC 공용.
 *
 * "주어진 프로젝트 집합 중 MFA(2FA) 강제 대상(require_2fa=true)인 것이 하나라도 있는가"를 판정한다.
 * issue-tracking BC 가 [com.bts.issue.project.adapter.IssueTrackingSensitiveProjectResolver]
 * 로 adapter 를 제공한다 (FR-MF-04).
 *
 * ## 소비처
 * identity-access BC 가 로그인 완료 시 세션에 연결된 프로젝트 id 집합을 전달하여
 * MFA 강제 여부를 판정한다(FR-MF-04 Task 3).
 *
 * ## 계약 타입 배치 (BC 격리)
 * 이 interface 는 shared-kernel 에 두어 소비 BC 들이 issue-tracking 내부를 역방향
 * 의존하지 않게 한다. 시그니처는 `Set<UUID>` 와 `Boolean` 만 사용하며,
 * issue-tracking 도메인 타입(`ProjectId` 등)을 의도적으로 참조하지 않는다.
 *
 * ## projectIds 타입 — Set<UUID> (BC 공통 분모)
 * 각 BC 는 `ProjectId` 등 자체 별칭을 사용할 수 있으나, 공용 포트 시그니처는
 * `java.util.UUID` 를 사용해 BC 간 타입 결합을 제거한다.
 */
interface SensitiveProjectResolver {
    /**
     * 주어진 프로젝트 id 집합 중 민감 프로젝트(require_2fa=true)가 하나라도 있는지 판정한다.
     *
     * 빈 집합([projectIds].isEmpty())은 DB 조회 없이 즉시 `false` 를 반환한다.
     * soft-deleted(deleted_at IS NOT NULL) 프로젝트는 민감 프로젝트로 간주하지 않는다.
     *
     * @param projectIds 판정 대상 프로젝트 UUID 집합.
     * @return 민감 프로젝트가 하나라도 포함되면 `true`, 없으면 `false`.
     */
    fun anyRequiresMfa(projectIds: Set<UUID>): Boolean
}
