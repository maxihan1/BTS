// 전역 시스템 관리자 판정 포트(공용). actorId는 UUID(BC별 ActorId/UserId 별칭의 공통 분모).

package com.bts.shared.permission

import java.util.UUID

/**
 * 전역(시스템) 권한 평가 outbound port — 전 BC 공용.
 *
 * 행위자가 시스템 전역 관리자(SYSTEM_ADMIN)인지 여부만 판정한다.
 * identity-access BC 가 [com.atlas.bts.identity.permission.IdentityAccessSystemPermissionResolver]
 * 로 adapter 를 제공한다 (FR-PM-08).
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
}
