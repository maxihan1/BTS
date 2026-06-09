// 계정 연결 셀프서비스 엔드포인트 — 목록/재인증/연결/해제 (JWT 전용, step-up 게이팅) (FR-AU-08 Task 6)

package com.atlas.bts.identity.web

import com.atlas.bts.identity.account.AccountLinkAuthException
import com.atlas.bts.identity.account.AccountLinkConflictException
import com.atlas.bts.identity.account.AccountLinkLastMethodException
import com.atlas.bts.identity.account.AccountLinkNotFoundException
import com.atlas.bts.identity.account.AccountLinkService
import com.atlas.bts.identity.account.AccountLinkView
import com.atlas.bts.identity.account.LinkOutcome
import com.atlas.bts.identity.account.ReauthChallengeFailedException
import com.atlas.bts.identity.account.ReauthMethod
import com.atlas.bts.identity.account.ReauthService
import com.atlas.bts.identity.account.StepUpService
import com.atlas.bts.identity.provider.ldap.ProviderUnavailableException
import com.atlas.bts.identity.web.dto.AccountLinkResponse
import com.atlas.bts.identity.web.dto.AccountLinksResponse
import com.atlas.bts.identity.web.dto.LinkAccountRequest
import com.atlas.bts.identity.web.dto.ReauthRequest
import com.atlas.bts.identity.web.dto.ReauthResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.util.UUID

/**
 * 사용자 본인의 외부 계정 연결을 관리하는 셀프서비스 컨트롤러 (FR-AU-08 / SDD §19).
 *
 * ## 엔드포인트
 * - [listLinks]: GET /links — 연결 목록 조회(externalSubject 마스킹).
 * - [reauth]: POST /reauth — 민감동작 직전 재인증 → step-up 윈도우 부여.
 * - [link]: POST /links — 외부 계정 연결(step-up 필요).
 * - [unlink]: DELETE /links/{id} — 외부 계정 해제(step-up 필요).
 *
 * ## JWT 전용 (PAT 차단)
 * 계정 연결 셀프서비스는 "현재 세션(sid)" 개념이 필수다(step-up 윈도우가 sid 단위). PAT 는
 * stateless 자격증명이라 sid 가 없으므로 모든 엔드포인트에서 **403** 으로 차단한다
 * ([AuthController] 세션 셀프서비스의 PAT 차단 선례와 동일 원칙).
 *
 * ## sid 신뢰 출처 (FR9 / 리뷰 B1)
 * step-up 윈도우를 부여·검사할 sid 는 오직 인증된 JWT 의 `sid` 클레임에서만 추출한다.
 * 요청 바디/헤더/쿼리의 sid 는 일절 수용하지 않으며, reauth 응답에도 sid/토큰을 노출하지 않는다
 * (만료시각만 반환).
 *
 * ## step-up 게이팅
 * [link]·[unlink] 는 [StepUpService.isValid] 로 현재 sid 가 유효 step-up 윈도우 안인지 먼저 확인한다.
 * 유효하지 않으면 서비스 호출 없이 **403 `step_up_required`** 로 즉시 거부한다.
 *
 * ## 예외 → HTTP 매핑
 * account 도메인 예외는 이 컨트롤러 **로컬 [@ExceptionHandler]** 가 상태로 변환한다(전역
 * @ControllerAdvice 미사용 — 타 컨트롤러 영향/catch-all 변질 선례 회피). [ProviderUnavailableException]
 * (LDAP 불가) 은 [AccountLinkService.link] → [com.atlas.bts.identity.provider.ldap.LdapProvider.bindForLinking]
 * 이 서버 장애 시 **실제로 전파**하며(C1 — EC3 503 실배선), catch-all 이 500 으로 변질시키지 않도록
 * [link] 안에서 직접 503 으로 응답한다.
 *
 * ## 트랜잭션 경계
 * @Transactional 없음 — service layer([AccountLinkService]/[ReauthService]) 가 각자 보장한다.
 *
 * ## 보안 (DEVELOPMENT.md §1.1)
 * 로그에 externalSubject(DN)/비밀번호/사용자명을 남기지 않는다. password 는 String 으로 수신 즉시
 * [CharArray] 로 변환해 서비스에 넘긴다(서비스가 wipe 책임).
 */
@RestController
@RequestMapping("/api/v1/auth/account")
// TooManyFunctions 억제 — 4 엔드포인트 + 5 예외 핸들러 + 응집된 private 헬퍼로 단일 책임(계정 연결)에 묶인다.
// UnusedParameter 억제 — @ExceptionHandler 메서드는 매핑을 위해 예외 타입 파라미터가 필수이나 본문에서 미사용한다.
@Suppress("TooManyFunctions", "UnusedParameter")
class AccountLinkController(
    private val accountLinkService: AccountLinkService,
    private val reauthService: ReauthService,
    private val stepUpService: StepUpService,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(AccountLinkController::class.java)

    /**
     * GET /api/v1/auth/account/links — 본인 외부 계정 연결 목록 조회 (FR-AU-08).
     *
     * PAT 면 403. JWT 면 [AccountLinkService.listLinks] 결과를 마스킹해 200 으로 반환한다.
     *
     * @param jwt 인증 JWT principal. PAT 인증 시 null.
     * @return 200 [AccountLinksResponse] / 403 PAT / 401 미인증(필터)
     */
    @GetMapping("/links")
    fun listLinks(
        @AuthenticationPrincipal jwt: Jwt?,
    ): ResponseEntity<*> {
        val claims = resolveJwtClaims(jwt) ?: return PAT_FORBIDDEN_RESPONSE

        val links = accountLinkService.listLinks(claims.userId)
        return ResponseEntity.ok(
            AccountLinksResponse(
                links = links.links.map { it.toResponse() },
                hasLocalPassword = links.hasLocalPassword,
            ),
        )
    }

    /**
     * POST /api/v1/auth/account/reauth — 재인증 챌린지 → step-up 윈도우 부여 (FR-AU-08).
     *
     * sid 는 JWT 클레임에서만 추출한다(바디 sid 무시 — FR9). method 별로 [ReauthService] 를 호출하고
     * 성공 시 만료시각만 담은 200 을 반환한다. 실패는 로컬 핸들러가 401 로 변환한다.
     *
     * method 별 필수 필드(LOCAL=password, LDAP=providerId/username/password)가 누락이면 서비스 호출 전
     * **400 `reauth_fields_required`** 로 거부한다(클라이언트 입력 오류 — C2).
     *
     * @param jwt 인증 JWT principal. PAT 인증 시 null → 403.
     * @param body 재인증 요청(method/password/(+LDAP providerId,username)).
     * @return 200 [ReauthResponse] / 400 입력누락 / 401 실패(핸들러) / 403 PAT
     *
     * ReturnCount 억제 — PAT/sid 부재 guard early return 이 중첩 if 보다 가독성 우수(DEVELOPMENT.md §2.3).
     */
    @Suppress("ReturnCount")
    @PostMapping("/reauth")
    fun reauth(
        @AuthenticationPrincipal jwt: Jwt?,
        @RequestBody body: ReauthRequest,
    ): ResponseEntity<*> {
        val claims = resolveJwtClaims(jwt) ?: return PAT_FORBIDDEN_RESPONSE
        val sid = claims.currentSid ?: return PAT_FORBIDDEN_RESPONSE

        when (body.method) {
            ReauthMethod.LOCAL -> {
                // 로컬은 비밀번호만 필수 — 누락/공백은 클라이언트 입력 오류이므로 400(서비스 호출 전 거부).
                if (body.password.isBlank()) {
                    return errorResponse(HttpStatus.BAD_REQUEST, ERROR_REAUTH_FIELDS_REQUIRED)
                }
                reauthService.reauthenticateLocal(claims.userId, sid, body.password.toCharArray())
            }
            ReauthMethod.LDAP -> {
                // LDAP 은 providerId/username/password 모두 필수 — 하나라도 누락이면 400
                // (requireNotNull 의 IllegalArgumentException 이 핸들러 없이 500 으로 새는 것을 막는다 — C2).
                val providerId = body.providerId
                val username = body.username
                if (providerId == null || username.isNullOrBlank() || body.password.isBlank()) {
                    return errorResponse(HttpStatus.BAD_REQUEST, ERROR_REAUTH_FIELDS_REQUIRED)
                }
                reauthService.reauthenticateLdap(
                    claims.userId,
                    sid,
                    providerId,
                    username,
                    body.password.toCharArray(),
                )
            }
        }

        return ResponseEntity.ok(ReauthResponse(stepUpExpiresAt = clock.instant().plus(StepUpService.STEP_UP_TTL)))
    }

    /**
     * POST /api/v1/auth/account/links — 외부 계정 연결 (step-up 필요) (FR-AU-08).
     *
     * step-up 미충족이면 서비스 호출 없이 403. 유효하면 [AccountLinkService.link] 로 연결한다.
     * 신규는 201, 멱등(이미 본인 소유)은 200 으로 응답한다. [ProviderUnavailableException]
     * (LDAP 불가) 은 catch-all 변질 방지를 위해 여기서 직접 503 으로 처리한다.
     *
     * @param jwt 인증 JWT principal. PAT 인증 시 null → 403.
     * @param body 연결 요청(providerId/username/password).
     * @return 201 신규 / 200 멱등 / 403 PAT|step_up_required / 401|409 핸들러 / 503 provider_unavailable
     *
     * ReturnCount 억제 — PAT/step-up guard early return 이 중첩 if 보다 가독성 우수(DEVELOPMENT.md §2.3).
     */
    @Suppress("ReturnCount")
    @PostMapping("/links")
    fun link(
        @AuthenticationPrincipal jwt: Jwt?,
        @RequestBody body: LinkAccountRequest,
    ): ResponseEntity<*> {
        val claims = resolveJwtClaims(jwt) ?: return PAT_FORBIDDEN_RESPONSE
        requireStepUp(claims.currentSid)?.let { return it }

        return try {
            val outcome =
                accountLinkService.link(
                    claims.userId,
                    body.providerId,
                    body.username,
                    body.password.toCharArray(),
                )
            // 신규(Created)는 201, 멱등(AlreadyLinked)은 200 — body 는 동일(toResponse).
            val status = if (outcome is LinkOutcome.Created) HttpStatus.CREATED else HttpStatus.OK
            ResponseEntity.status(status).body(outcome.view.toResponse())
        } catch (ex: ProviderUnavailableException) {
            // 503 직접 생성 — catch-all 핸들러가 500 으로 변질시키지 않도록. password/PII 미로깅.
            log.warn("account link provider unavailable: providerType={}", ex.providerType)
            errorResponse(HttpStatus.SERVICE_UNAVAILABLE, ERROR_PROVIDER_UNAVAILABLE)
        }
    }

    /**
     * DELETE /api/v1/auth/account/links/{id} — 외부 계정 해제 (step-up 필요) (FR-AU-08).
     *
     * step-up 미충족이면 서비스 호출 없이 403. 유효하면 [AccountLinkService.unlink] 로 해제한다.
     * 마지막 수단(409)·미소유/미존재(404) 는 로컬 핸들러가 변환한다.
     *
     * @param jwt 인증 JWT principal. PAT 인증 시 null → 403.
     * @param id 해제할 연결(user_external_accounts) 식별자.
     * @return 204 / 403 PAT|step_up_required / 409|404 핸들러 / 400 UUID 형식(필터)
     *
     * ReturnCount 억제 — PAT/step-up guard early return 이 중첩 if 보다 가독성 우수(DEVELOPMENT.md §2.3).
     */
    @Suppress("ReturnCount")
    @DeleteMapping("/links/{id}")
    fun unlink(
        @AuthenticationPrincipal jwt: Jwt?,
        @PathVariable id: UUID,
    ): ResponseEntity<*> {
        val claims = resolveJwtClaims(jwt) ?: return PAT_FORBIDDEN_RESPONSE
        requireStepUp(claims.currentSid)?.let { return it }

        accountLinkService.unlink(claims.userId, id)
        return ResponseEntity.noContent().build<Void>()
    }

    // ── 로컬 예외 핸들러 (account 도메인 예외 → HTTP) ─────────────────────────────

    /** bind 실패(EC12) → 401. 계정 열거 0 — 일반화 에러코드만 노출. */
    @ExceptionHandler(AccountLinkAuthException::class)
    fun handleAuth(ex: AccountLinkAuthException): ResponseEntity<Map<String, String>> =
        errorResponse(HttpStatus.UNAUTHORIZED, ERROR_LINK_AUTH_FAILED)

    /** 재인증 실패 → 401. 수단·원인 비구분(계정 열거 0). */
    @ExceptionHandler(ReauthChallengeFailedException::class)
    fun handleReauthFailed(ex: ReauthChallengeFailedException): ResponseEntity<Map<String, String>> =
        errorResponse(HttpStatus.UNAUTHORIZED, ERROR_REAUTH_FAILED)

    /** 타계정 선점 → 409. 어느 user 인지 비노출. */
    @ExceptionHandler(AccountLinkConflictException::class)
    fun handleConflict(ex: AccountLinkConflictException): ResponseEntity<Map<String, String>> =
        errorResponse(HttpStatus.CONFLICT, ERROR_ACCOUNT_ALREADY_LINKED)

    /** 마지막 로그인 수단 해제 시도 → 409(영구 락 방지). */
    @ExceptionHandler(AccountLinkLastMethodException::class)
    fun handleLastMethod(ex: AccountLinkLastMethodException): ResponseEntity<Map<String, String>> =
        errorResponse(HttpStatus.CONFLICT, ERROR_LAST_LOGIN_METHOD)

    /** 미소유/미존재 연결 → 404(존재 probe 방지). */
    @ExceptionHandler(AccountLinkNotFoundException::class)
    fun handleNotFound(ex: AccountLinkNotFoundException): ResponseEntity<Void> = ResponseEntity.notFound().build()

    // ── private helpers ───────────────────────────────────────────────────────

    /**
     * JWT principal 에서 userId(subject) 와 currentSid(sid 클레임) 를 추출한다.
     *
     * PAT 인증 시 [jwt] 가 null 이므로 null 을 반환한다(호출 측에서 403). subject 가 유효 UUID 가
     * 아니면 null 을 반환한다. sid 클레임 부재/파싱 실패 시 [JwtClaims.currentSid] 는 null 이다.
     *
     * ReturnCount 억제 — null guard early return 이 가독성 우수.
     */
    @Suppress("ReturnCount")
    private fun resolveJwtClaims(jwt: Jwt?): AccountLinkJwtClaims? {
        if (jwt == null) return null
        val userId = runCatching { UUID.fromString(jwt.subject) }.getOrNull() ?: return null
        val currentSid =
            jwt.getClaimAsString("sid")?.let {
                runCatching { UUID.fromString(it) }.getOrNull()
            }
        return AccountLinkJwtClaims(userId = userId, currentSid = currentSid)
    }

    /**
     * step-up 게이팅 — 유효하면 null, 아니면 403 응답을 반환한다.
     *
     * sid 가 null(클레임 부재) 이거나 [StepUpService.isValid] 가 false 면 fail-safe 로 403 을 돌려준다.
     * 호출 측은 반환값이 null 이 아니면 즉시 그 응답을 반환해 서비스 호출을 건너뛴다.
     *
     * @param sid JWT 에서 추출한 현재 세션 식별자(없으면 null).
     * @return 통과 시 null, 미충족 시 403 `step_up_required`.
     */
    private fun requireStepUp(sid: UUID?): ResponseEntity<Map<String, String>>? {
        val valid = sid != null && stepUpService.isValid(sid)
        return if (valid) null else errorResponse(HttpStatus.FORBIDDEN, ERROR_STEP_UP_REQUIRED)
    }

    /** [AccountLinkView] → 마스킹 적용 응답 DTO. linkedAt/lastLoginAt 은 그대로 surface(S1). */
    private fun AccountLinkView.toResponse(): AccountLinkResponse =
        AccountLinkResponse(
            id = id,
            providerId = providerId,
            providerName = providerName,
            providerType = providerType,
            providerEnabled = providerEnabled,
            externalSubjectMasked = maskSubject(externalSubject),
            linkedAt = linkedAt,
            lastLoginAt = lastLoginAt,
        )

    /**
     * externalSubject 를 앞 [MASK_VISIBLE_PREFIX] 자만 남기고 나머지를 `***` 로 가린다 (PII).
     *
     * 길이가 [MASK_VISIBLE_PREFIX] 이하면 전체가 그대로 노출된 뒤 `***` 가 붙는다(짧은 값은
     * 마스킹되지 않는다). 실제 입력은 LDAP DN(예: `uid=...,ou=...,dc=...`)으로 항상 prefix 보다
     * 길어 끝부분(고유 식별자)이 가려지므로 PII 노출 위험이 없다.
     */
    private fun maskSubject(externalSubject: String): String {
        val visible = externalSubject.take(MASK_VISIBLE_PREFIX)
        return "$visible$MASK_SUFFIX"
    }

    /** `{"error": <code>}` 본문을 가진 [status] 응답을 생성한다. */
    private fun errorResponse(
        status: HttpStatus,
        errorCode: String,
    ): ResponseEntity<Map<String, String>> = ResponseEntity.status(status).body(mapOf("error" to errorCode))

    private companion object {
        /** externalSubject 마스킹 시 앞에서 노출할 글자 수. */
        const val MASK_VISIBLE_PREFIX = 6

        /** 마스킹 접미사 — 나머지 글자를 가린다. */
        const val MASK_SUFFIX = "***"

        const val ERROR_STEP_UP_REQUIRED = "step_up_required"
        const val ERROR_LINK_AUTH_FAILED = "link_authentication_failed"
        const val ERROR_REAUTH_FAILED = "reauth_failed"

        /** reauth 입력 누락(method 별 필수 필드 부재) — 클라이언트 입력 오류(400). */
        const val ERROR_REAUTH_FIELDS_REQUIRED = "reauth_fields_required"
        const val ERROR_ACCOUNT_ALREADY_LINKED = "account_already_linked"
        const val ERROR_LAST_LOGIN_METHOD = "last_login_method"
        const val ERROR_PROVIDER_UNAVAILABLE = "provider_unavailable"

        /** PAT 인증 시 계정 연결 셀프서비스 불가 응답 — 모든 엔드포인트 공용. */
        val PAT_FORBIDDEN_RESPONSE: ResponseEntity<Map<String, String>> =
            ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(mapOf("error" to "account_linking_requires_interactive_login"))
    }
}

/**
 * JWT 로부터 추출된 인증 클레임 (AccountLinkController 전용).
 *
 * 이름은 web 패키지 내 고유다 — [AuthController] 의 동명 top-level `JwtClaims` 와의
 * redeclaration 충돌을 피하기 위해 접두사를 둔다.
 *
 * @param userId JWT subject UUID — 인증 사용자 ID.
 * @param currentSid JWT sid 클레임 UUID — 현재 요청 세션 ID. 클레임 부재/파싱 실패 시 null.
 */
private data class AccountLinkJwtClaims(val userId: UUID, val currentSid: UUID?)
