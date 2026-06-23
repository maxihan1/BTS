// 이슈 목록 조회 쿼리 파라미터를 BoardCardFilter VO 로 파싱하는 객체 — issue-tracking BC

package com.bts.issue.adapter.inbound.rest

import com.bts.shared.board.BoardCardFilter
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * 이슈 목록 조회 쿼리 파라미터를 [BoardCardFilter] VO 로 파싱한다.
 *
 * GET /api/v1/issues 요청의 `status`, `assignee`, `label`, `component` 쿼리 파라미터를 받아
 * [BoardCardFilter] 로 조립한다.
 *
 * ### 파싱 규칙
 *
 * - 모든 값은 trim 처리하며, blank(공백만) 값은 무시한다.
 * - `status` 값은 문자열 그대로 [BoardCardFilter.statusKeys] 에 추가한다
 *   (소문자 워크플로우 상태 키).
 * - `assignee` 값이 [SENTINEL_UNASSIGNED]("unassigned", 대소문자 구분) 이면
 *   [BoardCardFilter.includeUnassigned]=true. 그 외 값은 UUID 로 파싱해
 *   [BoardCardFilter.assigneeIds] 에 추가한다. UUID 파싱 실패 시 [ResponseStatusException] 400.
 * - `label` 값은 문자열 그대로 [BoardCardFilter.labels] 에 추가한다.
 * - `component` 값은 UUID 로 파싱해 [BoardCardFilter.componentIds] 에 추가한다.
 *   UUID 파싱 실패 시 [ResponseStatusException] 400.
 * - 모든 파라미터가 비거나 blank 이면 [BoardCardFilter.EMPTY] 를 반환한다.
 *
 * ### 400 에러 경로
 *
 * [ResponseStatusException](BAD_REQUEST) 을 던진다.
 * [IssueExceptionHandler.handleResponseStatus] 가 명시 핸들러로 등록되어 있어
 * catch-all(Exception) 핸들러에 의한 500 변질이 발생하지 않는다.
 *
 * ### BC 격리
 *
 * agile-planning BC 의 `BoardFilterQueryParser` 와 동형이지만, 직접 import 하지 않고
 * issue-tracking BC 자체 구현으로 유지한다. status 파라미터 지원이 추가된 이슈 전용 파서이다.
 */
object IssueFilterQueryParser {
    /** 미배정 이슈를 포함하도록 지정하는 `assignee` 파라미터 센티널 값 (대소문자 구분). */
    private const val SENTINEL_UNASSIGNED = "unassigned"

    /**
     * 쿼리 파라미터 목록을 [BoardCardFilter] VO 로 파싱한다.
     *
     * @param status status 파라미터 값 목록. 소문자 워크플로우 상태 키 문자열.
     * @param assignee assignee 파라미터 값 목록. UUID 또는 [SENTINEL_UNASSIGNED] 만 허용.
     * @param label label 파라미터 값 목록. 문자열 그대로 사용.
     * @param component component 파라미터 값 목록. UUID 만 허용.
     * @return 파싱 결과 [BoardCardFilter]. 모두 비어 있으면 [BoardCardFilter.EMPTY].
     * @throws ResponseStatusException 400 — assignee 또는 component 에 유효하지 않은 UUID 값이 있을 때.
     */
    fun parse(
        status: List<String>,
        assignee: List<String>,
        label: List<String>,
        component: List<String>,
    ): BoardCardFilter {
        val statusKeys = status.map { it.trim() }.filter { it.isNotBlank() }

        val assigneeIds = mutableListOf<UUID>()
        var includeUnassigned = false
        for (raw in assignee) {
            val value = raw.trim()
            if (value.isBlank()) continue
            if (value == SENTINEL_UNASSIGNED) {
                includeUnassigned = true
            } else {
                assigneeIds.add(parseUuid(value, "assignee"))
            }
        }

        val labels = label.map { it.trim() }.filter { it.isNotBlank() }

        val componentIds = mutableListOf<UUID>()
        for (raw in component) {
            val value = raw.trim()
            if (value.isBlank()) continue
            componentIds.add(parseUuid(value, "component"))
        }

        val filter =
            BoardCardFilter(
                statusKeys = statusKeys,
                assigneeIds = assigneeIds,
                includeUnassigned = includeUnassigned,
                labels = labels,
                componentIds = componentIds,
            )
        return if (filter.isEmpty()) BoardCardFilter.EMPTY else filter
    }

    /**
     * 문자열을 [UUID] 로 파싱한다.
     *
     * 파싱 실패 시 [ResponseStatusException] 400 을 던진다.
     *
     * @param value 파싱할 문자열.
     * @param paramName 에러 메시지에 포함할 파라미터 이름.
     * @return 파싱된 [UUID].
     * @throws ResponseStatusException 400 — UUID 형식이 아닐 때.
     */
    private fun parseUuid(
        value: String,
        paramName: String,
    ): UUID {
        return try {
            UUID.fromString(value)
        } catch (e: IllegalArgumentException) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "ISSUE_FILTER_INVALID_UUID: '$paramName' 파라미터 값이 유효한 UUID 형식이 아닙니다: $value",
                e,
            )
        }
    }
}
