// 전역 권한 부여 행 — global_permission_grants 테이블 매핑 도메인 모델 (FR-PM-10)

package com.atlas.bts.identity.permission

import java.time.Instant
import java.util.UUID

/**
 * 전역 권한 부여 대상의 종류 (ADR D-4 — 다형 참조의 종류 축).
 *
 * `grantee_id` 가 무엇을 가리키는지 결정한다. DB 는 `CHECK (grantee_type IN ('USER','GROUP'))` 로
 * 같은 두 값만 허용하므로, 이 enum 에 값을 추가하려면 V036 의 CHECK 도 함께 확장해야 한다.
 */
enum class GranteeType {
    /** `grantee_id` 가 `users.id` — 사용자에게 직접 부여. */
    USER,

    /** `grantee_id` 가 `user_groups.id` — 그룹 멤버 전원에게 `group_memberships` 경유로 전파. */
    GROUP,
}

/**
 * 전역 권한 부여 1행 (FR-PM-10, ADR `2026-07-17-global-permission-grants.md`).
 *
 * "누가 어떤 전역 권한을 갖는가"를 저장한다. 프로젝트 축이 없는 권한(`CREATE_PROJECT` 등)이
 * 대상이며, 판정은 `grant 보유 OR isSystemAdmin` 이다(ADR D-2 — SYSTEM_ADMIN 은 grant 없이도 보유).
 *
 * @property id 행 식별자. 회수([GlobalPermissionGrantRepository.revoke])의 대상 키다.
 * @property permission 전역 권한코드. SDD 12(`docs/sdd/12-permissions.md`) 정본이며 DB CHECK 로
 *   현재 `CREATE_PROJECT` 1종만 허용한다 — 이 테이블은 권한코드를 사용자 입력(REST 바디)으로 받는
 *   유일한 곳이라 DB CHECK + 서비스 검증 이중 방어를 둔다 (ADR D-1).
 * @property granteeType 부여 대상 종류. [granteeId] 의 해석을 결정한다.
 * @property granteeId [granteeType] 에 따라 `users.id` 또는 `user_groups.id`. 다형 참조라 DB FK 가
 *   없으며(ADR D-4), 존재 검증은 서비스 층 책임이다.
 * @property grantedBy 이 grant 를 부여한 SYSTEM_ADMIN 의 `users.id`. 감사 흔적이라 nullable 이 아니고
 *   기본값도 없다 — 부여 체인의 시작점을 놓치면 GROUP 전파 전체가 무기록이 된다 (ADR D-5).
 * @property createdAt 부여 시각. DB `NOW()` 가 채운다.
 */
data class GlobalPermissionGrant(
    val id: UUID,
    val permission: String,
    val granteeType: GranteeType,
    val granteeId: UUID,
    val grantedBy: UUID,
    val createdAt: Instant,
)
