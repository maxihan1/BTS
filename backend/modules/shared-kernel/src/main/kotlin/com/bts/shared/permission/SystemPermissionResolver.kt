// 전역 시스템 관리자 판정 포트(공용). actorId는 UUID(BC별 ActorId/UserId 별칭의 공통 분모).

package com.bts.shared.permission

import java.util.UUID

/**
 * 전역(시스템) 권한 평가 outbound port — 전 BC 공용.
 *
 * 행위자가 시스템 전역 관리자(SYSTEM_ADMIN)인지 여부를 판정한다.
 * identity-access BC 가 [com.atlas.bts.identity.permission.IdentityAccessSystemPermissionResolver]
 * 로 adapter 를 제공한다 (FR-PM-08).
 *
 * FR-PM-10 에서 [hasGlobalPermission] 이 default 메서드로 확장되어, SYSTEM_ADMIN 여부에 더해
 * 개별 전역 권한(예 CREATE_PROJECT) 부여까지 판정한다.
 *
 * ## 소비처
 * FR-PM-04(전역 관리자 전용 기능)가 이 포트를 소비하여 시스템 수준 작업 권한을 게이트한다.
 * 호출자는 `actor.value` 로 UUID 를 추출하여 전달한다.
 *
 * ## 계약 타입 배치 (BC 격리)
 * 이 interface 는 shared-kernel 에 두어 소비 BC 들이 identity-access 내부를 역방향
 * 의존하지 않게 한다. 시그니처는 `actorId: UUID` 와 `Boolean` 만 사용하며,
 * identity 도메인 타입(`SystemRole` 등)을 의도적으로 참조하지 않는다 — shared-kernel
 * 이 identity 도메인을 import 하면 역의존이 발생하기 때문이다.
 *
 * ## actorId 타입 — UUID (BC 공통 분모)
 * 각 BC 는 `ActorId`, `UserId` 등 자체 별칭을 사용할 수 있으나, 공용 포트 시그니처는
 * `java.util.UUID` 를 사용해 BC 간 타입 결합을 제거한다.
 */
interface SystemPermissionResolver {
    /**
     * 주어진 행위자([actorId])가 시스템 전역 관리자인지 판정한다.
     *
     * @param actorId 권한 평가 대상 행위자 UUID.
     * @return 전역 관리자이면 `true`, 아니면 `false`.
     */
    fun isSystemAdmin(actorId: UUID): Boolean

    /**
     * 행위자가 전역 권한 [permission] 을 보유하는지 판정한다 (FR-PM-10).
     *
     * ## default 가 isSystemAdmin 위임인 이유 (fail-safe)
     * 이 메서드는 기존 인터페이스에 나중에 추가됐다. 구현체 6곳(prod 2 + test 4)이 override 없이
     * 살아남아야 하므로 default 를 둔다. 의미는 "SYSTEM_ADMIN 이면 모든 전역 권한 보유" —
     * 실제 prod 판정(grant OR isSystemAdmin)의 부분집합이라 fail-safe 방향이다.
     *
     * ## prod 어댑터는 반드시 override 한다
     * override 를 잊으면 판정이 SYSTEM_ADMIN 전용으로 되돌아가 FR-PM-10 이 조용히 무력화된다.
     * IdentityAccessSystemPermissionResolver 가 override 하며, 그 회귀는
     * "grant 보유 비-SYSTEM_ADMIN 이 true 를 받는다" 테스트가 잡는다.
     *
     * @param actorId 판정 대상 사용자 UUID.
     * @param permission 전역 권한코드. SDD 12 정본 (예 CREATE_PROJECT).
     * @return 보유 시 `true`. 판정 불가/미부여는 `false` (fail-closed).
     */
    fun hasGlobalPermission(
        actorId: UUID,
        permission: String,
    ): Boolean = isSystemAdmin(actorId)
}
