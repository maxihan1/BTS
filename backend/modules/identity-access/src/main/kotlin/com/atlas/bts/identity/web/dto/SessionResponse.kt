// GET /api/v1/auth/sessions 응답 단건 DTO — spec §FR-2 필드 1:1 매핑 (deviceFingerprint 제외, NFR-2)

package com.atlas.bts.identity.web.dto

import com.fasterxml.jackson.annotation.JsonProperty
import java.time.Instant
import java.util.UUID

/**
 * 활성 세션 목록 응답 항목 DTO (FR-AU-09 Task 2 / spec §FR-2).
 *
 * ## 포함 필드 (spec §FR-2 단일 진실원천)
 * - [sid]: 세션 식별자 UUID
 * - [providerId]: 인증 Provider 식별자 (예: "local", "ldap-corp")
 * - [userAgent]: 로그인 시 User-Agent 헤더. 미제공 시 null (EC-6)
 * - [ipAddress]: 로그인 시 클라이언트 IP. 미제공 시 null (EC-6)
 * - [lastSeenAt]: 마지막 활동 시각 (ISO-8601 UTC)
 * - [createdAt]: 세션 생성(로그인) 시각 (ISO-8601 UTC)
 * - [current]: 요청 JWT 의 `sid` 클레임과 일치 여부. 현재 세션이면 true
 *
 * ## 의도적 제외 (NFR-2)
 * `deviceFingerprint` — 내부 신뢰 디바이스 판별용 해시값. 응답 노출 금지.
 *
 * ## frontend Zod 스키마 정합 (NFR-3)
 * 이 DTO 의 필드명이 단일 진실원천. Zod 스키마는 이 DTO 와 1:1 일치해야 한다.
 *
 * @param sid 세션 식별자
 * @param providerId 인증 Provider 식별자
 * @param userAgent 로그인 시 User-Agent (null 허용)
 * @param ipAddress 로그인 시 클라이언트 IP (null 허용)
 * @param lastSeenAt 마지막 활동 시각
 * @param createdAt 세션 생성 시각
 * @param current 현재 요청 세션 여부
 */
data class SessionResponse(
    val sid: UUID,
    @JsonProperty("providerId") val providerId: String,
    val userAgent: String?,
    val ipAddress: String?,
    val lastSeenAt: Instant,
    val createdAt: Instant,
    val current: Boolean,
)
