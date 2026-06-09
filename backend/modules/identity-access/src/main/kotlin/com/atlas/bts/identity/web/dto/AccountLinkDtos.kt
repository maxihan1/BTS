// 계정 연결(account linking) 엔드포인트 요청/응답 DTO 모음 (FR-AU-08 Task 6)

package com.atlas.bts.identity.web.dto

import com.atlas.bts.identity.account.ReauthMethod
import com.atlas.bts.identity.spi.ProviderType
import java.time.Instant
import java.util.UUID

/**
 * POST /api/v1/auth/account/reauth 요청 body — 민감동작 직전 재인증 챌린지 (FR-AU-08).
 *
 * ## sid 미수용 (FR9 / 리뷰 B1)
 * 이 DTO 에는 `sid` 필드가 없다. step-up 윈도우를 부여할 세션 식별자는 오직 인증된 JWT 의
 * `sid` 클레임에서만 추출한다. 클라이언트가 바디에 `sid` 를 넣어도 Jackson 이 알 수 없는 필드로
 * 무시하므로(역직렬화 기본 정책) 위조 sid 가 흘러들지 않는다.
 *
 * @param method 재인증 수단 — LOCAL(로컬 비밀번호) 또는 LDAP(bind).
 * @param password 평문 비밀번호 — 컨트롤러가 즉시 [CharArray] 로 변환해 서비스에 전달한다.
 * @param providerId LDAP 재인증 대상 authn_providers.id. LOCAL 이면 무시.
 * @param username LDAP 사용자명(uid). LOCAL 이면 무시.
 */
data class ReauthRequest(
    val method: ReauthMethod,
    val password: String,
    val providerId: UUID? = null,
    val username: String? = null,
)

/**
 * POST /api/v1/auth/account/links 요청 body — 외부 계정 연결 (FR-AU-08).
 *
 * @param providerId 연결 대상 authn_providers.id.
 * @param username 외부 IdP 사용자명(uid).
 * @param password 평문 비밀번호 — 컨트롤러가 즉시 [CharArray] 로 변환해 서비스에 전달한다.
 */
data class LinkAccountRequest(
    val providerId: UUID,
    val username: String,
    val password: String,
)

/**
 * POST /api/v1/auth/account/reauth 성공 응답 — step-up 윈도우 만료시각만 노출 (FR-AU-08).
 *
 * ## 노출 최소화 (FR9 / 리뷰 B1)
 * sid·step-up 토큰 등 세션 식별 값은 응답에 절대 포함하지 않는다. 프론트는 만료시각만 알면
 * "지금부터 이 시각까지 민감동작이 추가 인증 없이 가능"함을 표시할 수 있다.
 *
 * @param stepUpExpiresAt step-up 윈도우 만료 시각(ISO-8601 UTC).
 */
data class ReauthResponse(
    val stepUpExpiresAt: Instant,
)

/**
 * GET /api/v1/auth/account/links 응답 단건 — 외부 계정 연결 1개의 표시용 뷰 (FR-AU-08).
 *
 * ## externalSubject 마스킹 (PII)
 * 원본 externalSubject(LDAP DN 등)는 응답에 노출하지 않는다. 컨트롤러가 앞 일부만 남기고
 * 나머지를 `***` 로 가린 [externalSubjectMasked] 만 내려준다.
 *
 * @param id user_external_accounts 행 식별자.
 * @param providerId 연결 provider 식별자.
 * @param providerName provider 표시명. provider row 부재 시 null.
 * @param providerType provider 유형. provider row 부재 시 null.
 * @param providerEnabled provider 활성 여부. provider row 부재 시 false.
 * @param externalSubjectMasked 마스킹된 외부 식별자(앞 일부 + ***).
 * @param linkedAt 연결 시각(ISO-8601 UTC) — ExternalAccount.createdAt.
 * @param lastLoginAt 마지막 로그인 시각(ISO-8601 UTC). 한 번도 로그인하지 않았으면 null.
 */
data class AccountLinkResponse(
    val id: UUID,
    val providerId: UUID,
    val providerName: String?,
    val providerType: ProviderType?,
    val providerEnabled: Boolean,
    val externalSubjectMasked: String,
    val linkedAt: Instant,
    val lastLoginAt: Instant?,
)

/**
 * GET /api/v1/auth/account/links 전체 응답 — 연결 목록 + 로컬 비밀번호 보유 여부 (FR-AU-08).
 *
 * @param links 연결된 외부 계정 목록(마스킹 적용).
 * @param hasLocalPassword LOCAL 비밀번호 보유 여부 — 프론트가 로그인 수단 표시·판단에 사용.
 */
data class AccountLinksResponse(
    val links: List<AccountLinkResponse>,
    val hasLocalPassword: Boolean,
)
