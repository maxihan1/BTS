// PAT 셀프서비스 DTO — 발급 요청·발급 응답(raw token 1회 노출)·목록 요약·목록 봉투 (FR-API-04 Task 5)

package com.atlas.bts.identity.web.dto

import com.atlas.bts.identity.pat.IssuedPersonalAccessToken
import com.atlas.bts.identity.pat.PersonalAccessToken
import java.time.Instant
import java.util.UUID

/**
 * PAT 발급 요청 바디 (POST /api/v1/users/me/pats).
 *
 * name 공백·scope 화이트리스트·만료 범위(1..365)·개수 상한 검증은 전부
 * [com.atlas.bts.identity.pat.PersonalAccessTokenService.issue] 가 수행한다(단일 검증 출처).
 * 따라서 이 DTO 에는 bean validation 을 두지 않고 컨트롤러는 위임만 한다.
 *
 * @property name 사용자 지정 레이블(공백 불가 — 서비스 검증).
 * @property scopes 요청 scope 목록(화이트리스트 — 서비스 검증).
 * @property expiresInDays 만료까지 일수(1..365, null/무기한 금지 — 서비스 검증).
 */
data class CreatePatRequest(
    val name: String,
    val scopes: List<String>,
    val expiresInDays: Int?,
)

/**
 * PAT 발급 성공(201) 응답 — raw token 을 **여기서만 1회** 노출한다(EC-26).
 *
 * `token_hash`(SHA-256)·`userId` 는 절대 담지 않는다(§1.1.1). [token] 은 이후 어디에서도 재조회할 수 없으므로
 * 클라이언트가 이 응답에서 즉시 보관해야 한다.
 *
 * @property id 발급된 PAT 식별자 (직렬화 키: id).
 * @property name 사용자 지정 레이블 (직렬화 키: name).
 * @property scopes 정규화된 scope 목록 (직렬화 키: scopes).
 * @property token prefix 포함 raw PAT token — **1회 노출**. 로그/재조회 금지 (직렬화 키: token).
 * @property expiresAt 만료 시각 (직렬화 키: expiresAt).
 * @property createdAt 발급 시각 (직렬화 키: createdAt).
 */
data class IssuedPatResponse(
    val id: UUID,
    val name: String,
    val scopes: List<String>,
    val token: String,
    val expiresAt: Instant?,
    val createdAt: Instant,
) {
    companion object {
        /** [IssuedPersonalAccessToken] → 발급 응답 DTO. raw token 은 이 매핑에서만 노출된다. */
        fun from(issued: IssuedPersonalAccessToken): IssuedPatResponse =
            IssuedPatResponse(
                id = issued.token.id,
                name = issued.token.name,
                scopes = issued.token.scopes,
                token = issued.rawToken,
                expiresAt = issued.token.expiresAt,
                createdAt = issued.token.createdAt,
            )
    }
}

/**
 * PAT 목록 요약 항목 (GET /api/v1/users/me/pats 배열 원소).
 *
 * raw token 은 존재하지 않고, `token_hash`(비밀값)·`userId`(내부 식별자)도 노출하지 않는다(§1.1.1).
 * 만료 배지 표시를 위해 [expiresAt] 을 포함한다.
 *
 * @property id PAT 식별자 (직렬화 키: id).
 * @property name 사용자 지정 레이블 (직렬화 키: name).
 * @property scopes 허용 scope 목록 (직렬화 키: scopes).
 * @property expiresAt 만료 시각. null=무기한(발급 경로는 항상 만료 부여) (직렬화 키: expiresAt).
 * @property lastUsedAt 마지막 사용 시각. null=미사용 (직렬화 키: lastUsedAt).
 * @property createdAt 발급 시각 (직렬화 키: createdAt).
 */
data class PatSummaryResponse(
    val id: UUID,
    val name: String,
    val scopes: List<String>,
    val expiresAt: Instant?,
    val lastUsedAt: Instant?,
    val createdAt: Instant,
) {
    companion object {
        /** [PersonalAccessToken] → 목록 요약 DTO. `tokenHash`/`userId` 는 매핑에서 제외한다(§1.1.1). */
        fun from(pat: PersonalAccessToken): PatSummaryResponse =
            PatSummaryResponse(
                id = pat.id,
                name = pat.name,
                scopes = pat.scopes,
                expiresAt = pat.expiresAt,
                lastUsedAt = pat.lastUsedAt,
                createdAt = pat.createdAt,
            )
    }
}

/**
 * PAT 목록(200) 응답 봉투.
 *
 * @property pats 본인 PAT 요약 목록(만료 포함, 없으면 빈 목록) (직렬화 키: pats).
 */
data class PatListResponse(
    val pats: List<PatSummaryResponse>,
) {
    companion object {
        /** 도메인 PAT 목록 → 요약 봉투 DTO. */
        fun from(tokens: List<PersonalAccessToken>): PatListResponse =
            PatListResponse(
                pats = tokens.map(PatSummaryResponse::from),
            )
    }
}
