// SecurityContext 인증 주체를 issue-tracking ActorId 로 변환하는 헬퍼

package com.bts.issue.adapter.inbound.rest

import com.bts.issue.domain.ActorId
import org.springframework.http.HttpStatus
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * Spring Security 의 [SecurityContextHolder] 에 저장된 인증 주체를 issue-tracking BC 의 [ActorId] 로 변환한다.
 *
 * FR-PM-06 PR-B actor 결선의 공통 추출 지점이다. [IssueController] 가 하드코딩 sentinel actor 대신
 * 실제 인증 주체를 actor 로 사용하도록 한 곳에서 변환을 수행한다.
 *
 * 도메인 결정 A(프레임워크 중립): `org.springframework.security.oauth2.jwt.Jwt` 등 특정 인증 방식 타입에
 * 의존하지 않고 일반 [Authentication] 만 사용한다. 따라서 Local/LDAP/SAML/OIDC 어떤 인증 방식이든
 * `authentication.name` 이 사용자 UUID 인 한 동일하게 동작한다.
 *
 * project-workflow `com.bts.workflow.web.CurrentActor` 와 달리 issue-tracking [ActorId] 는 UUID 래퍼이며
 * nil-UUID(모두 0)를 `require` 가드로 거부한다. 따라서 (1) `authentication.name` 의 UUID 형식 오류와
 * (2) nil-UUID 거부 두 경로 모두 401(UNAUTHORIZED)로 변환한다.
 */
object CurrentActor {
    private const val UNAUTHENTICATED_MESSAGE = "Authentication required"

    /**
     * 현재 [SecurityContextHolder] 의 인증 주체를 [ActorId] 로 반환한다.
     *
     * 인증되지 않았거나(`null`/`isAuthenticated == false`/익명) 주체 식별자가 UUID 형식이 아니거나
     * nil-UUID 이면 401(UNAUTHORIZED) [ResponseStatusException] 을 던진다.
     *
     * @return 인증 주체의 UUID 식별자로 만든 [ActorId].
     * @throws ResponseStatusException 인증이 없거나 주체가 유효한 비-nil UUID 가 아닐 때(401).
     */
    fun current(): ActorId {
        val authentication: Authentication =
            SecurityContextHolder.getContext().authentication
                ?.takeIf(::isAuthenticated)
                ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, UNAUTHENTICATED_MESSAGE)
        return try {
            // UUID.fromString 형식 오류와 ActorId 의 nil-UUID require 위반 모두 IllegalArgumentException 이다.
            ActorId(UUID.fromString(authentication.name))
        } catch (e: IllegalArgumentException) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, UNAUTHENTICATED_MESSAGE, e)
        }
    }

    /**
     * [authentication] 이 실제 인증된 주체인지 판별한다.
     *
     * `isAuthenticated == false` 이거나 익명 토큰([AnonymousAuthenticationToken]) 이면 미인증으로 본다.
     */
    private fun isAuthenticated(authentication: Authentication): Boolean =
        authentication.isAuthenticated &&
            authentication !is AnonymousAuthenticationToken
}
