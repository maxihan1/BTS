// SecurityContext 인증 주체를 워크플로우 actor(ActorId)로 변환하는 헬퍼

package com.bts.workflow.web

import com.bts.workflow.port.outbound.ActorId
import org.springframework.http.HttpStatus
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.server.ResponseStatusException

/**
 * Spring Security 의 [SecurityContextHolder] 에 저장된 인증 주체를 워크플로우 BC 의 [ActorId] 로 변환한다.
 *
 * FR-PM-04 후속 actor 결선의 공통 추출 지점이다. 워크플로우 스킴 컨트롤러가 하드코딩 sentinel actor 대신
 * 실제 인증 주체를 actor 로 사용하도록 한 곳에서 변환을 수행한다.
 *
 * 도메인 결정 A(프레임워크 중립): `org.springframework.security.oauth2.jwt.Jwt` 등 특정 인증 방식 타입에
 * 의존하지 않고 일반 [Authentication] 만 사용한다. 따라서 Local/LDAP/SAML/OIDC 어떤 인증 방식이든
 * `authentication.name` 이 사용자 UUID 인 한 동일하게 동작한다.
 *
 * 자세한 배경은 ADR `docs/decisions/2026-06-05-workflow-scheme-controller-actor-wiring.md` 참조.
 */
object CurrentActor {
    private const val UNAUTHENTICATED_MESSAGE = "Authentication required"

    /**
     * 현재 [SecurityContextHolder] 의 인증 주체를 [ActorId] 로 반환한다.
     *
     * 인증되지 않았거나(`null`/`isAuthenticated == false`/익명) 주체 식별자가 UUID 형식이 아니면
     * 401(UNAUTHORIZED) [ResponseStatusException] 을 던진다.
     *
     * @return 인증 주체의 UUID 식별자로 만든 [ActorId].
     * @throws ResponseStatusException 인증이 없거나 주체가 유효한 UUID 가 아닐 때(401).
     */
    fun current(): ActorId {
        val authentication: Authentication? = SecurityContextHolder.getContext().authentication
        if (authentication == null ||
            !authentication.isAuthenticated ||
            authentication is AnonymousAuthenticationToken
        ) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, UNAUTHENTICATED_MESSAGE)
        }
        return try {
            ActorId(authentication.name)
        } catch (e: IllegalArgumentException) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, UNAUTHENTICATED_MESSAGE, e)
        }
    }
}
