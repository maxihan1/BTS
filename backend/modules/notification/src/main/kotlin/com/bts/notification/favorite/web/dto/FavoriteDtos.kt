// 즐겨찾기 REST API 요청·응답 DTO — 직렬화 계약 및 컨트롤러 반환 타입 정의

package com.bts.notification.favorite.web.dto

import com.bts.notification.favorite.domain.Favorite
import java.time.Instant
import java.util.UUID

/**
 * 즐겨찾기 등록 요청 DTO.
 *
 * targetType 유효성 검증은 도메인 [com.bts.notification.favorite.domain.FavoriteTargetType.from] 에서 수행한다.
 * targetId 불변식 검증은 도메인 [com.bts.notification.favorite.domain.Favorite.create] 에서 수행한다.
 * notification 모듈은 Bean Validation provider 가 없으므로 @field:NotBlank 어노테이션이 무동작이다.
 *
 * @param targetType 즐겨찾기 대상 종류 문자열 (ISSUE / FILTER / DASHBOARD / PROJECT)
 * @param targetId 즐겨찾기 대상 식별자 (1~255자)
 */
data class CreateFavoriteRequest(
    val targetType: String,
    val targetId: String,
)

/**
 * 즐겨찾기 단건 응답 DTO.
 *
 * POST 신규 등록(201) 및 중복 멱등(200) 응답에 모두 사용한다.
 *
 * @param id 즐겨찾기 식별자
 * @param targetType 즐겨찾기 대상 종류 문자열
 * @param targetId 즐겨찾기 대상 식별자
 * @param createdAt 등록 시각 (ISO 8601)
 */
data class FavoriteResponse(
    val id: UUID,
    val targetType: String,
    val targetId: String,
    val createdAt: Instant,
) {
    companion object {
        /**
         * 도메인 [Favorite] 를 응답 DTO 로 변환한다.
         *
         * @param domain 변환 대상 도메인 객체
         * @return 직렬화 준비된 응답 DTO
         */
        fun from(domain: Favorite): FavoriteResponse =
            FavoriteResponse(
                id = domain.id,
                targetType = domain.targetType.name,
                targetId = domain.targetId,
                createdAt = domain.createdAt,
            )
    }
}

/**
 * 즐겨찾기 목록 응답 DTO.
 *
 * GET /api/v1/favorites 응답의 data 필드로 사용한다.
 * created_at DESC 정렬은 서비스 레이어에서 보장한다.
 *
 * @param items 즐겨찾기 항목 목록
 */
data class FavoriteListResponse(
    val items: List<FavoriteResponse>,
)
