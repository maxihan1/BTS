// PATCH /api/v1/projects/{projectIdOrKey}/versions/{id}/dates 요청 바디 DTO — 날짜 지정/해제

package com.bts.issue.version.web.dto

import java.time.LocalDate

/**
 * 버전 날짜 변경 REST 요청 바디.
 *
 * /dates 전용 서브리소스. 2-state each: LocalDate = 지정, null = 해제.
 * [ComponentController.changeLead] (/{id}/lead) 와 동형 패턴.
 *
 * 날짜 선후 관계(startDate ≤ releaseDate)는 도메인이 강제하지 않는다(Jira 기본 동작).
 *
 * @property startDate 새 시작일. null 이면 미지정 상태로 전환.
 * @property releaseDate 새 릴리스 예정일. null 이면 미지정 상태로 전환.
 */
data class ChangeVersionDatesRequest(
    val startDate: LocalDate? = null,
    val releaseDate: LocalDate? = null,
)
