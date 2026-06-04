// 권한 시스템 outbound port — identity-access PR #8 후속 PR 에서 IdentityAccessPermissionResolver 로 어댑터 연결

package com.bts.workflow.port.outbound

import java.util.UUID

/**
 * 권한 평가 요청자(Actor)를 식별하는 타입 안전 값 래퍼(Value Object).
 *
 * String 을 직접 받는 대신 이 타입을 사용해 잘못된 호출자 ID 가 권한 평가 경로에 진입하지 못하도록 한다.
 * blank 값과 UUID 형식이 아닌 값은 생성 시점에 즉시 거부한다.
 *
 * BC 의 audit 정확성을 위해 UUID String 강제.
 * 비-UUID actor 표현은 별도 sentinel (예: SYSTEM_ACTOR_UUID) 을 UUID String 으로 변환한 후에만 전달 가능.
 *
 * @param raw 원시 UUID 문자열 (예. "550e8400-e29b-41d4-a716-446655440000"). blank 불가, UUID 형식 필수.
 * @throws IllegalArgumentException raw 가 blank 이거나 UUID 형식이 아닌 경우
 */
@JvmInline
value class ActorId(val raw: String) {
    init {
        require(raw.isNotBlank()) { "ActorId must not be blank" }
        require(UUID_REGEX.matches(raw)) { "ActorId must be a valid UUID String: '$raw'" }
    }

    companion object {
        private val UUID_REGEX =
            Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
    }
}

/**
 * [ActorId] 를 [java.util.UUID] 로 변환한다.
 *
 * shared-kernel 의 공용 권한 포트(예. `com.bts.shared.permission.WorkflowSchemePermissionResolver`)는
 * BC 공통 분모로 `UUID` 시그니처를 사용한다(FR-PM-04 D2). project-workflow 호출부가
 * `UUID.fromString(actor.raw)` 를 곳곳에서 중복하지 않도록 변환을 한 곳으로 모은다.
 *
 * [ActorId] 의 init 검증이 raw 가 UUID 형식임을 이미 보장하므로 변환은 항상 안전하다.
 *
 * @return raw UUID 문자열을 파싱한 [UUID].
 */
fun ActorId.toUuid(): UUID = UUID.fromString(raw)

/**
 * 권한 평가 outbound port.
 *
 * project-workflow BC 는 권한 판정의 세부 구현(identity-access BC)을 직접 호출하지 않고
 * 이 인터페이스만 경유한다. BC 격리 원칙(직접 import 금지, 이벤트 또는 port 경유)을 준수한다.
 *
 * ## 어댑터 연결 시점
 * identity-access PR #8 머지 이후 별도 PR 에서 `IdentityAccessPermissionResolver` 어댑터를 구현해
 * Spring Bean 으로 이 인터페이스에 바인딩한다.
 * 그때까지 `@Profile("!prod")` 환경에서 `AlwaysAllowPermissionResolver` stub 이 동작한다 (Task 27).
 *
 * ## 보안 계약
 * - 모든 권한 검사는 반드시 이 인터페이스를 통해야 한다. direct DB 접근 금지.
 * - [actorId] 는 항상 검증된 [ActorId] 타입이어야 한다. raw String 직접 전달 금지.
 * - 권한 없는 경우 예외를 던지지 않고 [Boolean] false 를 반환한다. 호출자가 예외 전환 책임을 진다.
 *
 * 참조. FR-WF-01, docs/sdd/ §12-permissions, identity-access.md
 */
interface PermissionResolver {
    /**
     * 주어진 액터가 지정된 범위에서 요청한 권한을 보유하는지 판정한다.
     *
     * @param actorId 권한을 판정할 액터 식별자.
     * @param permission 검사할 권한 문자열 (예. "CREATE_ISSUE", "ADMIN_WORKFLOW").
     * @param scope 권한 평가 범위. [Scope.Global], [Scope.Project], [Scope.Issue] 중 하나.
     * @return 권한을 보유하면 true, 아니면 false.
     */
    fun hasPermission(
        actorId: ActorId,
        permission: String,
        scope: Scope,
    ): Boolean
}
