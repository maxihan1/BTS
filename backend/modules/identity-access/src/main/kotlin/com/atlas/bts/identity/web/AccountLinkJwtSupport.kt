// 계정 연결 컨트롤러 공통 인증 헬퍼 — JWT subject+sid 추출/step-up 게이트/표준 errorResponse (FR-AU-08b Task 11)

package com.atlas.bts.identity.web

import com.atlas.bts.identity.account.StepUpService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * JWT 로부터 추출된 계정 연결용 인증 클레임 (web 패키지 공용).
 *
 * [AccountLinkController] 와 SsoAccountLinkController 가 공유한다. 이름은 web 패키지 내 고유하다
 * ([AuthController] 의 동명 top-level `JwtClaims` 와의 redeclaration 충돌 회피).
 *
 * @param userId JWT subject UUID — 인증 사용자 ID.
 * @param currentSid JWT `sid` 클레임 UUID — 현재 요청 세션 ID. 클레임 부재/파싱 실패 시 null.
 */
data class AccountLinkClaims(
    val userId: UUID,
    val currentSid: UUID?,
)

/**
 * 계정 연결 셀프서비스 컨트롤러들이 공유하는 인증/게이팅 헬퍼 (FR-AU-08b Task 11 / 리뷰 B2).
 *
 * ## 추출 배경
 * LDAP 동기 연결([AccountLinkController]) 과 SSO 리다이렉트 연결(SsoAccountLinkController) 이
 * 같은 보안 규칙(JWT 출처 sid·step-up 신선도·계정 열거 0 errorResponse)을 따른다. 이 로직을
 * 컨트롤러별 private 메서드로 복붙하면 한쪽만 고쳐 보안 drift 가 생긴다. 단일 컴포넌트로 추출해
 * 두 컨트롤러가 같은 구현을 호출하게 한다.
 *
 * ## sid 신뢰 출처 (FR8 / 1차 불변식 계승)
 * step-up 윈도우를 부여·검사할 sid 는 오직 인증된 JWT 의 `sid` 클레임에서만 추출한다. 요청
 * 바디/헤더/쿼리의 sid 는 일절 수용하지 않는다. PAT 인증(jwt=null)은 stateless 라 sid 가 없어
 * [resolveClaims] 가 null 을 반환하고, 호출 측이 403 으로 차단한다.
 *
 * ## step-up 게이팅 (fail-safe)
 * [requireStepUp] 은 sid 가 null 이거나 [StepUpService.isValid] 가 false 면 403 `step_up_required`
 * 응답을 반환한다. sid 가 null 이면 [StepUpService] 를 호출조차 하지 않고 즉시 차단한다.
 */
@Component
class AccountLinkJwtSupport(
    private val stepUpService: StepUpService,
) {
    /**
     * JWT principal 에서 userId(subject)와 currentSid(`sid` 클레임)를 동시에 추출한다.
     *
     * PAT 인증 시 [jwt] 가 null 이므로 null 을 반환한다(호출 측에서 403). subject 가 유효 UUID 가
     * 아니면 null 을 반환한다. sid 클레임 부재/파싱 실패 시 [AccountLinkClaims.currentSid] 는 null 이다.
     *
     * @param jwt 인증 JWT principal. PAT 인증 시 null.
     * @return 추출된 클레임, PAT/subject 형식 오류 시 null.
     *
     * ReturnCount 억제 — null guard early return 이 중첩 if 보다 가독성 우수(DEVELOPMENT.md §2.3).
     */
    @Suppress("ReturnCount")
    fun resolveClaims(jwt: Jwt?): AccountLinkClaims? {
        if (jwt == null) return null
        val userId = runCatching { UUID.fromString(jwt.subject) }.getOrNull() ?: return null
        val currentSid =
            jwt.getClaimAsString("sid")?.let {
                runCatching { UUID.fromString(it) }.getOrNull()
            }
        return AccountLinkClaims(userId = userId, currentSid = currentSid)
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
    fun requireStepUp(sid: UUID?): ResponseEntity<Map<String, String>>? {
        val valid = sid != null && stepUpService.isValid(sid)
        return if (valid) null else errorResponse(HttpStatus.FORBIDDEN, ERROR_STEP_UP_REQUIRED)
    }

    /** `{"error": <code>}` 본문을 가진 [status] 응답을 생성한다. */
    fun errorResponse(
        status: HttpStatus,
        errorCode: String,
    ): ResponseEntity<Map<String, String>> = ResponseEntity.status(status).body(mapOf("error" to errorCode))

    companion object {
        /** step-up 미충족 시 공통 에러코드 — 계정 연결 컨트롤러들이 공유. */
        const val ERROR_STEP_UP_REQUIRED = "step_up_required"
    }
}
