// 대시보드 도메인 불변식 위반 예외 — HTTP 레이어에서 400 으로 매핑

package com.bts.notification.dashboard.domain

/**
 * Dashboard Aggregate 불변식 위반 시 발생하는 도메인 예외.
 *
 * HTTP 레이어에서 400 Bad Request 로 매핑된다.
 * Web 레이어와 의존 방향을 분리하기 위해 도메인 패키지에 선언한다.
 *
 * @param message 위반 내용을 설명하는 메시지
 */
class DashboardDomainException(message: String) : RuntimeException(message)
