// 전역 권한 부여 관리 엔드포인트 — grant/revoke/list 3종 SYSTEM_ADMIN 가드 (FR-PM-10 Task 6)

package com.atlas.bts.identity.web

import com.atlas.bts.identity.permission.DuplicateGrantException
import com.atlas.bts.identity.permission.GlobalPermissionGrant
import com.atlas.bts.identity.permission.GlobalPermissionGrantException
import com.atlas.bts.identity.permission.GlobalPermissionGrantService
import com.atlas.bts.identity.permission.GrantNotFoundException
import com.atlas.bts.identity.permission.GranteeNotFoundException
import com.atlas.bts.identity.permission.GranteeType
import com.atlas.bts.identity.permission.UnknownPermissionException
import com.atlas.bts.identity.web.support.UNAUTHORIZED_RESPONSE
import com.atlas.bts.identity.web.support.requireSystemAdmin
import com.atlas.bts.identity.web.support.resolveActorId
import com.bts.shared.permission.SystemPermissionResolver
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

/**
 * 전역 권한 부여 관리 컨트롤러 (FR-PM-10 Task 6).
 *
 * ## 엔드포인트 (모두 /api/v1/admin/global-permissions 하위, 전부 SYSTEM_ADMIN 전용)
 * - [grantPermission]: POST 전역 권한 부여 → 201
 * - [listGrants]: GET 부여 목록 → 200
 * - [revokeGrant]: DELETE 부여 회수 → 204
 *
 * ## 이중 가드 (DEVELOPMENT.md §1.1 #4)
 * 1. 클래스 레벨 [PreAuthorize]("isAuthenticated()") — 미인증 요청을 필터 체인에서 차단.
 * 2. 각 핸들러가 `requireSystemAdmin`(web/support/ControllerAuthSupport.kt)으로 DB 기반
 *    [SystemPermissionResolver.isSystemAdmin] 을 수동 평가한다. [UserGroupController] 동형.
 *
 * > 🛑 **`@PreAuthorize("hasRole('SYSTEM_ADMIN')")` 를 쓰지 않는다.** JWT claim 이 stale 일 수 있고
 * > **PAT 경로에는 role claim 이 아예 없다** — `PatAuthenticationFilter` 는 `ROLE_PAT` 만 부여하므로
 * > 선언적 `hasRole` 게이트는 PAT 에서 **동작하지 않는다**(전부 403). 두 인증 경로에서 일관된 전역
 * > 관리자 판정을 보장하려고 DB 진실원천을 직접 조회한다 (FR-PM-04/리뷰 C2 결정).
 * > 이 결정은 `GlobalPermissionGrantControllerTest` 의 **PAT 양성 테스트**가 잠근다 — 선언적 게이트로
 * > 되돌리면 그 테스트가 403 으로 fail 한다.
 *
 * ## Actor 추출 (`resolveActorId`, web/support/ControllerAuthSupport.kt 공용)
 * - jwt != null → JWT subject 를 UUID 로 파싱.
 * - jwt == null → SecurityContext principal(String, `PatAuthenticationFilter` 설정)을 UUID 로 파싱.
 * - 둘 다 실패 → null → 401.
 *
 * 추출된 actor 는 가드 대상이자 **`grantedBy` 감사 흔적의 출처**다 (ADR D-5) — 부여자를 클라이언트
 * 입력이 아니라 인증 결과에서만 취한다.
 *
 * ## 에러 매핑 ([mapDomainException])
 * 도메인 예외를 snake_case `error` 코드 + HTTP 상태로 인라인 매핑한다(RestControllerAdvice 없음,
 * [UserGroupController] 선례). **타입으로만 분기하고 예외 `message` 는 응답에 싣지 않는다** — Guard
 * 예외 message 가 HTTP detail 로 새어 내부 사정을 노출한 FR-PM-04 사고의 회귀 방지다.
 *
 * ## 보안 (SecurityConfig 무변경)
 * /api 하위 전체에 대한 기존 authenticated 규칙이 라우트를 커버하므로 신규 라우트 등록은 하지 않는다.
 *
 * @see docs/decisions/2026-07-17-global-permission-grants.md 설계 결정 ADR
 */
@RestController
@RequestMapping("/api/v1/admin/global-permissions")
@PreAuthorize("isAuthenticated()")
class GlobalPermissionGrantController(
    private val grantService: GlobalPermissionGrantService,
    private val systemPermissionResolver: SystemPermissionResolver,
) {
    /**
     * POST /api/v1/admin/global-permissions — 전역 권한 부여.
     *
     * `grantedBy` 는 요청 바디가 아니라 **인증된 actor** 에서 취한다 (ADR D-5).
     *
     * ReturnCount 억제 — 가드 2겹(403/401)은 각각 즉시 반환해야 한다. 조기 반환을 하나로 접으면 권한
     * 판정 결과를 변수로 들고 다니다 분기를 놓치는 fail-open 통로가 된다. 거부는 즉시 종료가 안전하다.
     *
     * @return 201 [GlobalPermissionGrantResponse] 또는 에러 응답(400/401/403/404/409).
     */
    @Suppress("ReturnCount") // 가드 2겹(403/401) 즉시 반환 — 상세는 KDoc
    @PostMapping
    fun grantPermission(
        @AuthenticationPrincipal jwt: Jwt?,
        @RequestBody body: GrantGlobalPermissionRequest,
    ): ResponseEntity<*> {
        systemPermissionResolver.requireSystemAdmin(jwt)?.let { return it }
        val actorId = resolveActorId(jwt) ?: return UNAUTHORIZED_RESPONSE

        return runHandler {
            val created =
                grantService.grant(
                    permission = body.permission,
                    granteeType = body.granteeType,
                    granteeId = body.granteeId,
                    grantedBy = actorId,
                )
            ResponseEntity.status(HttpStatus.CREATED).body(GlobalPermissionGrantResponse.from(created))
        }
    }

    /**
     * GET /api/v1/admin/global-permissions — 부여된 전역 권한 전량 조회.
     *
     * @return 200 `[GlobalPermissionGrantResponse]` 또는 에러 응답(401/403).
     */
    @GetMapping
    fun listGrants(
        @AuthenticationPrincipal jwt: Jwt?,
    ): ResponseEntity<*> {
        systemPermissionResolver.requireSystemAdmin(jwt)?.let { return it }

        return runHandler {
            ResponseEntity.ok(grantService.list().map { GlobalPermissionGrantResponse.from(it) })
        }
    }

    /**
     * DELETE /api/v1/admin/global-permissions/{grantId} — 부여 회수 (hard delete, ADR D-5).
     *
     * 멱등이 아니다 — 없는 grant 회수는 404 다. "지웠다고 믿었는데 대상이 없었다"를 숨기지 않는다.
     *
     * @return 204 No Content 또는 에러 응답(401/403/404).
     */
    @DeleteMapping("/{grantId}")
    fun revokeGrant(
        @AuthenticationPrincipal jwt: Jwt?,
        @PathVariable grantId: UUID,
    ): ResponseEntity<*> {
        systemPermissionResolver.requireSystemAdmin(jwt)?.let { return it }

        return runHandler {
            grantService.revoke(grantId)
            ResponseEntity.noContent().build<Unit>()
        }
    }

    // ── 내부 헬퍼 ────────────────────────────────────────────────────────────

    /**
     * 핸들러 본문을 실행하고 도메인 예외만 HTTP 응답으로 매핑한다.
     *
     * [GlobalPermissionGrantException] sealed 계층만 잡는다(광범위 catch 금지, silently swallow 금지).
     * 그 외 예외는 잡지 않고 전파시켜 전역 처리에 위임한다.
     */
    private inline fun runHandler(block: () -> ResponseEntity<*>): ResponseEntity<*> =
        try {
            block()
        } catch (ex: GlobalPermissionGrantException) {
            mapDomainException(ex)
        }

    /**
     * 도메인 예외([GlobalPermissionGrantException])를 snake_case `error` 코드 + HTTP 상태로 매핑한다.
     *
     * **타입으로만 분기한다** — `ex.message` 를 응답에 싣지 않는다(내부 사정 누출 방지, FR-PM-04).
     */
    private fun mapDomainException(ex: GlobalPermissionGrantException): ResponseEntity<Map<String, String>> =
        when (ex) {
            is GranteeNotFoundException -> errorResponse(HttpStatus.NOT_FOUND, "grantee_not_found")
            is GrantNotFoundException -> errorResponse(HttpStatus.NOT_FOUND, "grant_not_found")
            is DuplicateGrantException -> errorResponse(HttpStatus.CONFLICT, "grant_already_exists")
            is UnknownPermissionException -> errorResponse(HttpStatus.BAD_REQUEST, "unknown_permission")
        }

    private companion object {
        /** 주어진 상태/코드로 error 키 단일 맵 응답을 만든다. */
        fun errorResponse(
            status: HttpStatus,
            code: String,
        ): ResponseEntity<Map<String, String>> = ResponseEntity.status(status).body(mapOf("error" to code))
    }
}

/**
 * 전역 권한 부여 요청 바디 (FR-PM-10 Task 6).
 *
 * **`grantedBy` 가 없는 것이 의도다** — 부여자는 클라이언트 입력이 아니라 인증된 actor 에서만 취한다
 * (ADR D-5. 바디로 받으면 감사 흔적을 호출자가 위조할 수 있다).
 *
 * @param permission 전역 권한코드. 화이트리스트 검증은 `GlobalPermissionGrantService` 가 소유한다.
 * @param granteeType 부여 대상 종류(USER/GROUP). 미지 값은 Jackson 이 400 으로 거부한다.
 * @param granteeId USER 면 `users.id`, GROUP 이면 `user_groups.id`.
 */
data class GrantGlobalPermissionRequest(
    val permission: String,
    val granteeType: GranteeType,
    val granteeId: UUID,
)

/**
 * 전역 권한 부여 응답 DTO (FR-PM-10 Task 6).
 *
 * @param id 부여 행 식별자. 회수(DELETE)의 대상 키다.
 * @param permission 전역 권한코드.
 * @param granteeType 부여 대상 종류.
 * @param granteeId 부여 대상 식별자.
 * @param grantedBy 부여한 SYSTEM_ADMIN 의 `users.id` (감사 흔적, ADR D-5).
 * @param createdAt 부여 시각.
 */
data class GlobalPermissionGrantResponse(
    val id: UUID,
    val permission: String,
    val granteeType: GranteeType,
    val granteeId: UUID,
    val grantedBy: UUID,
    val createdAt: Instant,
) {
    companion object {
        /** 영속 [GlobalPermissionGrant] 로부터 응답을 만든다. */
        fun from(grant: GlobalPermissionGrant): GlobalPermissionGrantResponse =
            GlobalPermissionGrantResponse(
                id = grant.id,
                permission = grant.permission,
                granteeType = grant.granteeType,
                granteeId = grant.granteeId,
                grantedBy = grant.grantedBy,
                createdAt = grant.createdAt,
            )
    }
}
