// 캘린더 피드 관리 API 응답 DTO — 발급 응답(원문 토큰 1회)·상태 조회 (FR-CA-02 Task 5)

package com.atlas.bts.identity.calendar

import java.time.Instant

/**
 * `POST /api/v1/users/me/calendar/feed` 발급 응답.
 *
 * 원문 토큰([token])과 그것이 박힌 구독 URL([feedUrl])은 **발급 응답에서 단 한 번만** 노출된다.
 * 이후 [CalendarFeedController.getFeedStatus] 조회에는 원문/해시가 포함되지 않는다(재노출 불가).
 *
 * @property feedUrl 외부 캘린더 앱이 구독할 익명 피드 URL (`{issuer}/ical/feed/{token}.ics`).
 * @property token 불투명 토큰 평문. 발급 응답으로만 노출되며 서버는 SHA-256 해시만 저장한다.
 * @property createdAt 토큰 발급(rotate) 시각.
 */
data class IssueFeedResponse(
    val feedUrl: String,
    val token: String,
    val createdAt: Instant,
)

/**
 * `GET /api/v1/users/me/calendar/feed` 상태 응답. 원문 토큰/해시는 절대 포함하지 않는다.
 *
 * @property enabled 활성 캘린더 피드 토큰 존재 여부.
 * @property createdAt 활성 토큰이 있으면 발급 시각, 없으면 null.
 */
data class FeedStatusResponse(
    val enabled: Boolean,
    val createdAt: Instant?,
)
