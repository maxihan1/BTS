// 대시보드 개별 공유 자식 엔티티 — (dashboard_id, user_id) 단위 지정 공유

package com.bts.notification.dashboard.domain

import java.util.UUID

/**
 * 대시보드 공유 자식 엔티티.
 *
 * TEAM visibility 에서만 유효하며, 특정 사용자에게 대시보드 조회 권한을 부여한다.
 * Dashboard Aggregate 를 통해서만 변경된다(직접 생성·수정 금지).
 *
 * user_id 는 identity-access users.id 를 논리적으로 참조한다 (BC 격리상 FK 없음).
 * 자체 deleted_at 이 없으며 부모 Dashboard 의 deleted_at 을 따른다.
 *
 * @param dashboardId 공유 대상 대시보드 식별자
 * @param userId 공유받는 사용자 식별자
 */
data class DashboardShare(
    val dashboardId: UUID,
    val userId: UUID,
)
