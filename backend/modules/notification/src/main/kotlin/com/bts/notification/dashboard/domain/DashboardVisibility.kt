// 대시보드 공개 범위 enum — PRIVATE(소유자만) / TEAM(지정 사용자) / ORG(인증 사용자 전체)

package com.bts.notification.dashboard.domain

/**
 * 대시보드 공개 범위를 정의하는 열거형.
 *
 * DB 에는 TEXT 컬럼으로 저장되며 name() 문자열(PRIVATE/TEAM/ORG)을 사용한다.
 * PUBLIC visibility 는 FR-DB-03(URL 공유/임베드) 범위로 이번 구현에서 제외한다.
 */
enum class DashboardVisibility {
    /** 소유자(owner)만 조회·수정 가능. 공유 대상 없음. */
    PRIVATE,

    /** 소유자 + dashboard_shares 에 명시된 사용자만 조회 가능. 수정은 소유자만. */
    TEAM,

    /** 모든 인증 사용자가 조회 가능. 수정은 소유자만. */
    ORG,
}
