// 대시보드 공유 토큰 관리 REST API 요청·응답 DTO — 원문 노출은 발급 응답 1회로 한정

package com.bts.notification.dashboard.web.dto

import com.bts.notification.dashboard.application.IssuedShareToken
import com.bts.notification.dashboard.domain.DashboardShareToken
import com.fasterxml.jackson.annotation.JsonInclude
import java.time.Instant
import java.util.UUID

/**
 * 공유 토큰 발급 요청 DTO.
 *
 * expiresAt 은 선택이다(생략 또는 null = 무기한).
 *
 * @param expiresAt 만료 시각 (선택)
 */
data class IssueShareTokenRequest(
    val expiresAt: Instant? = null,
)

/**
 * 공유 토큰 발급 응답 DTO — 원문 토큰을 포함하는 유일한 응답이다.
 *
 * 이 응답 이후로는 원문이 다시 노출되지 않는다(DB 에는 해시만 저장). expiresAt 이 null 이면
 * 직렬화에서 생략한다.
 *
 * @param id 공유 토큰 식별자
 * @param token 원문 공유 토큰 (1회 노출)
 * @param createdAt 발급 시각
 * @param expiresAt 만료 시각 (null 이면 직렬화 생략)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class IssuedShareTokenResponse(
    val id: UUID,
    val token: String,
    val createdAt: Instant,
    val expiresAt: Instant?,
) {
    companion object {
        /**
         * 서비스 발급 결과를 응답 DTO 로 변환한다.
         *
         * @param issued 서비스가 반환한 발급 결과(원문 + 저장된 엔티티)
         * @return 직렬화 준비된 응답 DTO
         */
        fun from(issued: IssuedShareToken): IssuedShareTokenResponse =
            IssuedShareTokenResponse(
                id = issued.token.id,
                token = issued.plaintext,
                createdAt = issued.token.createdAt,
                expiresAt = issued.token.expiresAt,
            )
    }
}

/**
 * 공유 토큰 목록 조회용 요약 DTO — 원문·해시 필드를 아예 갖지 않는다.
 *
 * tokenHash 필드 자체를 두지 않아 목록 API 가 어떤 경로로도 토큰 값을 노출하지 않도록 설계 시점에
 * 차단한다(유출 회귀가드 EC-9).
 *
 * @param id 공유 토큰 식별자
 * @param createdAt 발급 시각
 * @param expiresAt 만료 시각 (null 이면 직렬화 생략)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ShareTokenSummaryResponse(
    val id: UUID,
    val createdAt: Instant,
    val expiresAt: Instant?,
) {
    companion object {
        /**
         * 도메인 공유 토큰 엔티티를 요약 응답 DTO 로 변환한다.
         *
         * @param token 변환 대상 도메인 엔티티
         * @return 직렬화 준비된 요약 응답 DTO
         */
        fun from(token: DashboardShareToken): ShareTokenSummaryResponse =
            ShareTokenSummaryResponse(
                id = token.id,
                createdAt = token.createdAt,
                expiresAt = token.expiresAt,
            )
    }
}

/**
 * 공유 토큰 목록 응답 DTO.
 *
 * @param items 발급된 공유 토큰 요약 목록
 */
data class ShareTokenListResponse(
    val items: List<ShareTokenSummaryResponse>,
)
