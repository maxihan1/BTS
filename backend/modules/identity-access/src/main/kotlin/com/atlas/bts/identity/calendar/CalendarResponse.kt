// 개인 캘린더 응답 DTO — 이슈/워크로그 이벤트 목록 (FR-CA-01)

package com.atlas.bts.identity.calendar

import java.time.LocalDate
import java.util.UUID

/**
 * 개인 캘린더 조회 응답 (FR-CA-01).
 *
 * [CalendarService.getCalendar] 가 조립하는 최종 응답 — 조회 창(원본 [from]/[to]), 조회에 사용된
 * 사용자 timezone(해석된 IANA 존 id — 무효/미설정이면 fallback UTC), 담당 이슈 일정과 worklog
 * 이벤트, 그리고 상류 페이지 중 하나라도 잘렸으면 true 인 [truncated] 로 구성된다.
 *
 * @property from 조회 시작일(포함, 요청 파라미터 그대로).
 * @property to 조회 종료일(포함, 요청 파라미터 그대로).
 * @property timezone 조회에 사용된 IANA timezone id(사용자 프로필 값 또는 fallback `"UTC"`).
 * @property issueEvents 담당 이슈 일정 — `(startDate ?: dueDate, key)` 오름차순 정렬.
 * @property worklogEvents worklog 이벤트 — `(date, startedAt)` 오름차순 정렬.
 * @property truncated [issueEvents] 또는 [worklogEvents] 중 하나라도 상류 LIMIT 초과로 잘렸으면 true.
 */
data class CalendarResponse(
    val from: LocalDate,
    val to: LocalDate,
    val timezone: String,
    val issueEvents: List<CalendarIssueEvent>,
    val worklogEvents: List<CalendarWorklogEvent>,
    val truncated: Boolean,
)

/**
 * 캘린더에 표시할 담당 이슈 이벤트 (FR-CA-01).
 *
 * [com.bts.shared.calendar.UserCalendarLookupPort.listAssignedScheduledIssues] 조회 결과를 그대로
 * 옮긴 identity-access 측 응답 DTO.
 *
 * @property key 이슈 키. 예: `"PROJ-1"`.
 * @property summary 이슈 제목.
 * @property issueType 이슈 타입 키(소문자). 예: `"task"`.
 * @property currentStateKey 현재 워크플로우 상태 키.
 * @property startDate 시작일. 미설정이면 null.
 * @property dueDate 마감일. 미설정이면 null.
 */
data class CalendarIssueEvent(
    val key: String,
    val summary: String,
    val issueType: String,
    val currentStateKey: String,
    val startDate: LocalDate?,
    val dueDate: LocalDate?,
)

/**
 * 캘린더에 표시할 worklog 이벤트 (FR-CA-01).
 *
 * [com.bts.shared.calendar.UserCalendarLookupPort.listWorklogs] 가 반환하는 UTC 기준 `startedAt`
 * ([java.time.Instant])을 사용자 timezone 으로 변환한 로컬 [date] 로 매핑한 결과다 —
 * 원본 Instant 는 노출하지 않는다.
 *
 * @property id worklog 고유 id.
 * @property issueKey worklog 가 속한 이슈 키.
 * @property issueSummary 이슈 제목. 보안 등급으로 마스킹됐으면 null(구현체 책임 그대로 전달).
 * @property date 사용자 timezone 기준 로컬 날짜(캘린더 표시 칸).
 * @property timeSpentSeconds 작업 소요 시간(초).
 */
data class CalendarWorklogEvent(
    val id: UUID,
    val issueKey: String,
    val issueSummary: String?,
    val date: LocalDate,
    val timeSpentSeconds: Int,
)
