// 대시보드 목록 페이지네이션 응답 — items + total count

package com.bts.notification.dashboard.repository

import com.bts.notification.dashboard.domain.Dashboard

/**
 * DashboardRepository.findPage 의 페이지네이션 결과.
 *
 * items 는 현재 페이지의 대시보드 목록(updatedAt desc 정렬)이며,
 * total 은 페이지네이션과 무관한 전체 접근 가능 대시보드 수다(별도 COUNT 서브쿼리, C2).
 *
 * @param items 현재 페이지 대시보드 목록
 * @param total 전체 접근 가능 대시보드 수
 */
data class DashboardPage(
    val items: List<Dashboard>,
    val total: Int,
)
