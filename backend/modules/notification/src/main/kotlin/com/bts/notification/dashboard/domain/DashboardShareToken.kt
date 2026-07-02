// 대시보드 공유 토큰 도메인 엔티티 — 불투명 토큰의 SHA-256 해시만 보관(원문 미저장)

package com.bts.notification.dashboard.domain

import java.time.Instant
import java.util.UUID

/**
 * 대시보드 공유 토큰 엔티티.
 *
 * 대시보드를 URL 공유(불투명 랜덤 토큰)로 노출하기 위한 ephemeral credential.
 * 보안 규칙상 원문 토큰은 DB 에 저장하지 않으며, SHA-256 hex 해시(tokenHash)만 보관한다.
 * 원문은 발급 응답에서 단 한 번만 노출되고(MintedShareToken.plaintext), 이후에는 재현 불가능하다.
 *
 * 취소는 하드 삭제(row 삭제)로 처리하며 별도 상태 필드를 두지 않는다.
 * visibility(PRIVATE/TEAM/ORG) 와 직교하는 독립 공유 모델이다.
 *
 * 모든 필드는 val 로 선언해 생성 이후 외부에서 변경 불가.
 *
 * @param id 공유 토큰 식별자 (UUID)
 * @param dashboardId 대상 대시보드 식별자
 * @param tokenHash 원문 토큰의 SHA-256 hex(소문자 64자) 해시 — 조회 시 재해싱 비교용
 * @param createdBy 발급한 사용자 ID (identity-access users.id, 논리 참조)
 * @param createdAt 발급 시각
 * @param expiresAt 만료 시각 (null = 무기한)
 */
data class DashboardShareToken(
    val id: UUID,
    val dashboardId: UUID,
    val tokenHash: String,
    val createdBy: UUID,
    val createdAt: Instant,
    val expiresAt: Instant?,
) {
    companion object {
        /** 대시보드당 활성 공유 토큰 최대 개수 */
        const val MAX_SHARE_TOKENS: Int = 20
    }

    /**
     * 주어진 시각 기준 이 토큰이 만료되었는지 판정한다.
     *
     * expiresAt 이 null 이면 무기한 유효하므로 항상 false.
     * expiresAt <= now (경계 포함) 이면 만료로 간주해 true 를 반환한다.
     *
     * @param now 판정 기준 시각 (호출자 Clock 에서 주입)
     * @return 만료 여부
     */
    fun isExpired(now: Instant): Boolean = expiresAt != null && !expiresAt.isAfter(now)
}
