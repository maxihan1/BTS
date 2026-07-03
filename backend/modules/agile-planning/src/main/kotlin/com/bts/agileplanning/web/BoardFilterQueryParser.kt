// 보드 카드 필터 쿼리 파라미터를 BoardCardFilter VO 로 파싱하는 객체 — agile-planning BC

package com.bts.agileplanning.web

import com.bts.shared.board.BoardCardFilter
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * 보드 카드 필터 쿼리 파라미터를 [BoardCardFilter] VO 로 파싱한다.
 *
 * GET /api/v1/boards/{id} 요청의 `assignee`, `label`, `component` 쿼리 파라미터를 받아
 * [BoardCardFilter] 로 조립한다.
 *
 * ### 파싱 규칙
 *
 * - 모든 값은 trim 처리하며, blank(공백만) 값은 무시한다.
 * - `assignee` 값이 [SENTINEL_UNASSIGNED]("unassigned", 대소문자 구분) 이면 [BoardCardFilter.includeUnassigned]=true.
 *   그 외 값은 UUID 로 파싱해 [BoardCardFilter.assigneeIds] 에 추가한다.
 *   UUID 파싱 실패 시 [ResponseStatusException] 400 을 던진다.
 * - `component` 값은 UUID 로 파싱해 [BoardCardFilter.componentIds] 에 추가한다.
 *   UUID 파싱 실패 시 [ResponseStatusException] 400 을 던진다.
 * - `label` 값은 문자열 그대로 [BoardCardFilter.labels] 에 추가한다.
 * - 세 파라미터가 모두 비거나 blank 이면 [BoardCardFilter.EMPTY] 를 반환한다 (EC2).
 *
 * ### 400 에러 경로
 *
 * [ResponseStatusException](BAD_REQUEST) 을 던진다.
 * [BoardExceptionHandler.handleResponseStatus] 가 명시 핸들러로 등록되어 있어
 * catch-all(Exception) 핸들러에 의한 500 변질이 발생하지 않는다
 * (memory: catch-all-exceptionhandler-swallows-responsestatusexception 교훈).
 */
object BoardFilterQueryParser {
    /** 미배정 이슈를 포함하도록 지정하는 `assignee` 파라미터 센티널 값 (대소문자 구분). */
    private const val SENTINEL_UNASSIGNED = "unassigned"

    /** `assignee` 쿼리 파라미터 키. */
    private const val PARAM_ASSIGNEE = "assignee"

    /** `label` 쿼리 파라미터 키. */
    private const val PARAM_LABEL = "label"

    /** `component` 쿼리 파라미터 키. */
    private const val PARAM_COMPONENT = "component"

    /**
     * 쿼리 파라미터 목록을 [BoardCardFilter] VO 로 파싱한다.
     *
     * @param assignee assignee 파라미터 값 목록. UUID 또는 [SENTINEL_UNASSIGNED] 만 허용.
     * @param label label 파라미터 값 목록. 문자열 그대로 사용.
     * @param component component 파라미터 값 목록. UUID 만 허용.
     * @return 파싱 결과 [BoardCardFilter]. 모두 비어 있으면 [BoardCardFilter.EMPTY].
     * @throws ResponseStatusException 400 — assignee 또는 component 에 유효하지 않은 UUID 값이 있을 때.
     */
    fun parse(
        assignee: List<String>,
        label: List<String>,
        component: List<String>,
    ): BoardCardFilter {
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
                assigneeIds = assigneeIds,
                includeUnassigned = includeUnassigned,
                labels = labels,
                componentIds = componentIds,
            )
        return if (filter.isEmpty()) {
            BoardCardFilter.EMPTY
        } else {
            filter
        }
    }

    /**
     * [BoardCardFilter] 를 정규 쿼리스트링으로 직렬화한다.
     *
     * `assignee=<uuid>&label=<name>&component=<uuid>` 형식(`?` 없음)으로 조립한다.
     * `assignee`(UUID 오름차순) → `unassigned` 센티널 → `label`(문자열 오름차순) → `component`(UUID 오름차순)
     * 순서로 필드를 배치하며, 각 목록은 trim 후 중복을 제거한다. `statusKeys` 는 직렬화하지 않는다
     * (board GET 이 아직 상태 필터를 지원하지 않는 범위 밖 — 지원 시 parse/serialize 동반 확장).
     * 값은 [URLEncoder] (UTF-8, `application/x-www-form-urlencoded`: 공백 → `+`) 로 인코딩한다.
     *
     * @param filter 직렬화할 [BoardCardFilter].
     * @return 정규 쿼리스트링. 필터 조건이 없으면 빈 문자열.
     */
    fun serialize(filter: BoardCardFilter): String {
        val pairs = mutableListOf<Pair<String, String>>()

        filter.assigneeIds.distinct().sorted().forEach { pairs.add(PARAM_ASSIGNEE to it.toString()) }
        if (filter.includeUnassigned) {
            pairs.add(PARAM_ASSIGNEE to SENTINEL_UNASSIGNED)
        }
        filter.labels.map { it.trim() }.filter { it.isNotBlank() }.distinct().sorted().forEach {
            pairs.add(PARAM_LABEL to it)
        }
        filter.componentIds.distinct().sorted().forEach { pairs.add(PARAM_COMPONENT to it.toString()) }

        return pairs.joinToString("&") { (key, value) -> "$key=${encode(value)}" }
    }

    /**
     * 정규 쿼리스트링을 [BoardCardFilter] 로 역직렬화한다.
     *
     * `&` 로 파라미터 쌍을 분리하고, 각 쌍을 첫 `=` 기준으로 key/value 로 나눈 뒤
     * [URLDecoder] (UTF-8, `+` → 공백) 로 디코딩한다. `assignee`/`label`/`component` 키만 인식하며,
     * 그 외 키는 무시한다. 추출한 값 목록은 [parse] 에 위임해 UUID 검증·센티널 처리를 재사용한다.
     *
     * @param query [serialize] 가 생성한 형식의 쿼리스트링(또는 동등한 형식). 빈 문자열이면 [BoardCardFilter.EMPTY].
     * @return 파싱된 [BoardCardFilter].
     * @throws ResponseStatusException 400 — assignee 또는 component 값이 유효한 UUID 형식이 아닐 때.
     */
    fun deserialize(query: String): BoardCardFilter {
        if (query.isBlank()) {
            return BoardCardFilter.EMPTY
        }

        val assignee = mutableListOf<String>()
        val label = mutableListOf<String>()
        val component = mutableListOf<String>()

        for (pair in query.split("&")) {
            if (pair.isBlank()) continue
            val separatorIndex = pair.indexOf('=')
            if (separatorIndex < 0) continue
            val key = decode(pair.substring(0, separatorIndex))
            val value = decode(pair.substring(separatorIndex + 1))
            when (key) {
                PARAM_ASSIGNEE -> assignee.add(value)
                PARAM_LABEL -> label.add(value)
                PARAM_COMPONENT -> component.add(value)
            }
        }

        return parse(assignee = assignee, label = label, component = component)
    }

    /** [URLEncoder] 로 UTF-8 `application/x-www-form-urlencoded` 인코딩한다 (공백 → `+`). */
    private fun encode(value: String): String {
        return URLEncoder.encode(value, StandardCharsets.UTF_8)
    }

    /** [URLDecoder] 로 UTF-8 `application/x-www-form-urlencoded` 디코딩한다 (`+` → 공백). */
    private fun decode(value: String): String {
        return URLDecoder.decode(value, StandardCharsets.UTF_8)
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
                "AGILE_BOARD_FILTER_INVALID_UUID: '$paramName' 파라미터 값이 유효한 UUID 형식이 아닙니다: $value",
                e,
            )
        }
    }
}
