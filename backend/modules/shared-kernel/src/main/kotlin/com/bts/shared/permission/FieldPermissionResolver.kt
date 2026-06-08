// 필드 수준 권한 판정 cross-BC 포트(공용) — prod 실판정은 FR-PM-07 PR-B(identity-access)에서 채운다.

package com.bts.shared.permission

import java.util.UUID

/**
 * 필드의 종류 — 코어 필드와 커스텀 필드의 네임스페이스를 분리한다.
 *
 * 코어 필드(예: `summary`, `priority`)와 커스텀 필드는 같은 `key` 문자열을 가질 수 있으므로,
 * [FieldRef] 의 동등성 판정에 [FieldKind] 를 함께 사용해 동명 충돌을 방지한다.
 *
 * @property CORE 이슈에 내장된 코어 필드.
 * @property CUSTOM FR-IS-10 으로 정의되는 사용자 정의 커스텀 필드.
 */
enum class FieldKind {
    CORE,
    CUSTOM,
}

/**
 * 권한 판정 대상이 되는 필드 참조 — `(kind, key)` 의 값 객체.
 *
 * `data class` 이므로 `kind` 와 `key` 가 모두 같을 때만 동등([equals])하며 해시코드가 일치한다.
 * 코어/커스텀 동명 key 는 [kind] 로 구분된다(예: `FieldRef(CORE, "summary")` ≠ `FieldRef(CUSTOM, "summary")`).
 *
 * @property kind 필드 종류. [FieldKind.CORE] 또는 [FieldKind.CUSTOM].
 * @property key 필드 식별 키. 코어는 화이트리스트 필드명, 커스텀은 정의 키.
 */
data class FieldRef(val kind: FieldKind, val key: String)

/**
 * 필드 수준 권한 평가 outbound port — 전 BC 공용.
 *
 * issue-tracking BC 가 이슈 직렬화(`IssueResponse.from`)와 편집 게이트(`updateIssue`)에서
 * 이 interface 를 통해 actor 가 볼/편집할 수 있는 필드 집합을 질의한다.
 * prod 실판정 adapter `IdentityAccessFieldPermissionResolver`(`@Profile("prod")`)는
 * actor 의 그룹 멤버십 + `field_permissions` 규칙으로 집합을 계산한다(FR-PM-07 PR-B).
 * 개발/스테이징 환경에서는 `AlwaysAllowFieldPermissionResolver`(`@Profile("!prod")`) stub 이
 * candidates 를 그대로 반환해 issue-tracking 통합테스트 부팅을 보장한다.
 *
 * ## 포트 계약 (spec docs/specs/2026-06-08-fr-pm-07-field-permissions.md §6)
 * - **규칙 없는 key 는 항상 포함** — 필드 권한은 opt-in 제한이다. `field_permissions` 에
 *   규칙이 0건인 필드는 candidates 에 있으면 결과에 그대로 남는다(제한 없음, EC1).
 * - **EDIT ⊃ VIEW** — 편집 가능([editableFields])한 필드는 반드시 열람 가능([visibleFields])하다.
 *   EDIT 규칙만 있고 VIEW 규칙이 없는 필드도 열람·편집 모두 허용된다(EC3).
 * - **관리자 우회 없음** — prod 구현은 `isSystemAdmin`/`isProjectAdmin` 을 참조하지 않는다.
 *   그룹 비멤버인 관리자는 제한 필드를 보지 못한다(F7, EC10). 다른 권한 포트와 달리
 *   관리자 전역 통과(global admin) 경로가 없음에 유의한다.
 *
 * ## actorId 타입 — UUID (BC 공통 분모)
 * 각 BC 는 `ActorId`, `UserId` 등 자체 별칭을 사용할 수 있으나,
 * 공용 포트 시그니처는 `java.util.UUID` 를 사용해 BC 간 타입 결합을 제거한다.
 * 호출자는 `actor.value` 로 UUID 를 추출하여 전달한다.
 *
 * ## ArchUnit 강제
 * - 소비자(Service/Controller 계층)는 이 interface 만 의존한다. 구체 구현체 직접 import 금지.
 * - `com.atlas.bts.identity.*` 의 클래스를 issue-tracking 에서 직접 import 하면 빌드 실패 (BC 격리 룰).
 *
 * @see FieldRef
 * @see FieldKind
 */
interface FieldPermissionResolver {
    /**
     * [actorId] 가 [projectId] 내에서 열람 가능한 필드를 [candidates] 중에서 골라 반환한다.
     *
     * 규칙이 없는 필드는 항상 결과에 포함되며(opt-in 제한), 관리자 우회는 적용되지 않는다.
     *
     * @param actorId 권한 평가 대상 행위자 UUID. nil UUID 는 호출 이전 인증 단계에서 이미 거부된다.
     * @param projectId 필드가 속한 프로젝트의 UUID. 필드 권한은 항상 프로젝트 스코프.
     * @param candidates 판정 대상 필드 집합.
     * @return [candidates] 중 actor 가 열람 가능한 필드의 부분집합.
     */
    fun visibleFields(
        actorId: UUID,
        projectId: UUID,
        candidates: Set<FieldRef>,
    ): Set<FieldRef>

    /**
     * [actorId] 가 [projectId] 내에서 편집 가능한 필드를 [candidates] 중에서 골라 반환한다.
     *
     * 반환 집합은 [visibleFields] 의 결과를 항상 포함한다(EDIT ⊃ VIEW).
     * 규칙이 없는 필드는 항상 결과에 포함되며(opt-in 제한), 관리자 우회는 적용되지 않는다.
     *
     * @param actorId 권한 평가 대상 행위자 UUID. nil UUID 는 호출 이전 인증 단계에서 이미 거부된다.
     * @param projectId 필드가 속한 프로젝트의 UUID. 필드 권한은 항상 프로젝트 스코프.
     * @param candidates 판정 대상 필드 집합.
     * @return [candidates] 중 actor 가 편집 가능한 필드의 부분집합.
     */
    fun editableFields(
        actorId: UUID,
        projectId: UUID,
        candidates: Set<FieldRef>,
    ): Set<FieldRef>
}
