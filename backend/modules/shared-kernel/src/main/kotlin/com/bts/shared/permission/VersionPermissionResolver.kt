// 버전 권한 판정 포트(공용) — prod 실판정은 FR-PM-03 이연.

package com.bts.shared.permission

import java.util.UUID

/**
 * 버전 권한 평가 outbound port — 전 BC 공용.
 *
 * issue-tracking BC 의 VersionController/VersionApplicationService 가 이 interface 를 통해
 * 권한 판정을 요청한다. 개발/스테이징 환경에서는 issue-tracking 내부의
 * `AlwaysAllowVersionPermissionResolver` (`@Profile("!prod")`) stub 이 활성화된다.
 *
 * prod 실판정 adapter(prod impl)는 FR-PM-03 이 채운다. 그 전까지 prod 프로파일에는
 * 이 포트를 채우는 구현이 없으므로, 운영 부팅 시 실제 adapter 없이는
 * `BeanCreationException` 으로 부팅이 차단된다(임시 우회 stub 금지).
 *
 * ## [IssuePermissionResolver] 와의 차이 — scope 단순화
 * 버전은 항상 프로젝트 스코프에 종속된다(Global/Issue 범위가 없음).
 * 따라서 [IssueScope] 같은 sealed 계층 대신 `projectId: UUID` 를 직접 받는다.
 * ([ComponentPermissionResolver] 와 동형.)
 *
 * ## actorId 타입 — UUID (BC 공통 분모)
 * 각 BC 는 `ActorId`, `UserId` 등 자체 별칭을 사용할 수 있으나,
 * 공용 포트 시그니처는 `java.util.UUID` 를 사용해 BC 간 타입 결합을 제거한다.
 * 호출자는 `actor.value` 로 UUID 를 추출하여 전달한다.
 *
 * ## 호출 위치
 * VersionApplicationService 의 각 mutation(CREATE/UPDATE/DELETE) 메서드 진입 직후.
 * `@PreAuthorize` SpEL 표현식 대신 명시적 메서드 호출로 권한을 검증한다(IssuePermissionResolver 동형).
 *
 * ## ArchUnit 강제
 * - 소비자(Service/Controller 계층)는 이 interface 만 의존한다. 구체 구현체 직접 import 금지.
 * - `com.atlas.bts.identity.*` 의 클래스를 issue-tracking 에서 직접 import 하면 빌드 실패 (BC 격리 룰).
 *
 * @see VersionPermission
 */
interface VersionPermissionResolver {
    /**
     * 주어진 행위자([actorId])가 특정 프로젝트([projectId]) 내에서 요청 권한([permission])을 보유하는지 판정한다.
     *
     * @param actorId 권한 평가 대상 행위자 UUID. nil UUID 는 호출 이전 인증 단계에서 이미 거부된다.
     * @param permission 검증할 버전 권한. [VersionPermission] 참조.
     * @param projectId 버전이 속한 프로젝트의 UUID. 버전 권한은 항상 프로젝트 스코프.
     * @return 권한 있으면 `true`, 없으면 `false`.
     */
    fun hasPermission(
        actorId: UUID,
        permission: VersionPermission,
        projectId: UUID,
    ): Boolean
}
