// 즐겨찾기 Aggregate — 사용자가 등록한 즐겨찾기 항목을 표현하는 불변 도메인 모델

package com.bts.notification.favorite.domain

import java.time.Instant
import java.util.UUID

/**
 * 즐겨찾기 Aggregate Root.
 *
 * 사용자가 특정 대상(이슈·필터·대시보드·프로젝트)을 즐겨찾기에 등록한 항목을 표현한다.
 * 모든 필드는 val 로 선언해 한 번 생성된 이후 외부에서 변경 불가.
 * 팩토리 메서드 [create] 를 통해서만 생성하며, 생성 시 모든 불변식을 검증한다.
 *
 * 주의: notification 모듈은 Bean Validation provider 가 없으므로
 * @Valid / @NotBlank 어노테이션이 무동작이다. 검증은 이 클래스 내 명시 코드로만 수행한다.
 *
 * @param id 즐겨찾기 식별자 (UUID)
 * @param userId 즐겨찾기를 등록한 사용자 ID (identity-access users.id, 논리 참조)
 * @param targetType 즐겨찾기 대상 종류
 * @param targetId 즐겨찾기 대상 식별자 (1~255자 문자열)
 * @param createdAt 생성 시각
 */
data class Favorite(
    val id: UUID,
    val userId: UUID,
    val targetType: FavoriteTargetType,
    val targetId: String,
    val createdAt: Instant,
) {
    companion object {
        /** targetId 최대 길이 */
        const val MAX_TARGET_ID_LENGTH: Int = 255

        /**
         * Favorite 인스턴스를 생성하는 팩토리 메서드.
         *
         * 생성 시 모든 불변식을 검사한다.
         * - targetId 가 빈 문자열이거나 공백만 있으면 [FavoriteDomainException]
         * - targetId 가 [MAX_TARGET_ID_LENGTH] 초과이면 [FavoriteDomainException]
         * - 정상이면 id(UUID)·createdAt(Instant) 이 자동 부여된 인스턴스를 반환한다.
         *
         * @param userId 즐겨찾기를 등록하는 사용자 ID
         * @param targetType 즐겨찾기 대상 종류
         * @param targetId 즐겨찾기 대상 식별자
         * @return 불변식이 검증된 새 Favorite 인스턴스
         * @throws FavoriteDomainException 불변식 위반 시
         */
        fun create(
            userId: UUID,
            targetType: FavoriteTargetType,
            targetId: String,
        ): Favorite {
            validateTargetId(targetId)
            return Favorite(
                id = UUID.randomUUID(),
                userId = userId,
                targetType = targetType,
                targetId = targetId,
                createdAt = Instant.now(),
            )
        }

        private fun validateTargetId(targetId: String) {
            if (targetId.isBlank()) {
                throw FavoriteDomainException("즐겨찾기 대상 식별자는 빈 문자열 또는 공백일 수 없습니다.")
            }
            if (targetId.length > MAX_TARGET_ID_LENGTH) {
                throw FavoriteDomainException(
                    "즐겨찾기 대상 식별자는 ${MAX_TARGET_ID_LENGTH}자 이하여야 합니다. 현재: ${targetId.length}자",
                )
            }
        }
    }
}
