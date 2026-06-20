// 워크로그 집계 REST 컨트롤러 — GET /api/v1/worklogs/aggregate (FR-TT-02)

package com.bts.issue.worklog.aggregate.web

import com.bts.issue.adapter.inbound.rest.CurrentActor
import com.bts.issue.worklog.aggregate.application.WorklogAggregateService
import com.bts.issue.worklog.aggregate.domain.AggregateGranularity
import com.bts.issue.worklog.aggregate.domain.WorklogAggregateDimension
import com.bts.issue.worklog.aggregate.web.dto.WorklogAggregateResponse
import com.bts.issue.worklog.web.DataResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * 워크로그 집계 REST 컨트롤러 (FR-TT-02).
 *
 * ## 엔드포인트
 * - GET /api/v1/worklogs/aggregate — 프로젝트 워크로그 집계 (200)
 *
 * ### 쿼리 파라미터
 * | 이름          | 필수  | 설명                                      |
 * |-------------|-----|------------------------------------------|
 * | project     | ✓   | 집계 대상 프로젝트 키 (예: "TPRJ")              |
 * | by          | ✓   | 집계 차원: issue / user / period           |
 * | granularity | 조건부 | by=period 일 때 필수: day / week / month   |
 * | from        | -   | 시작일 YYYY-MM-DD (포함)                    |
 * | to          | -   | 종료일 YYYY-MM-DD (포함)                    |
 *
 * ### 파라미터 검증 전략 (enum 자동 바인딩 회피)
 * Spring MVC 가 enum 파라미터를 자동 바인딩하면 `MethodArgumentTypeMismatchException` 이 발생하여
 * 기존 핸들러가 없으면 500 으로 변질된다. 따라서 `by`, `granularity` 모두 [String] 으로 수신 후
 * 수동으로 파싱한다. 파싱 실패 시 명시적 400 [ResponseStatusException] 을 던진다.
 *
 * ### ActorId 결선 순서
 * [CurrentActor.current] 를 최상단에서 호출하여 미인증/nil-UUID/비-UUID 주체를 401 로 거부한다.
 * 파라미터 검증보다 먼저 수행하여 미인증자가 파라미터 오류 정보로 존재를 probe 하지 못하도록 한다.
 *
 * ### C6 — by≠period + granularity 동반
 * `by=issue` 또는 `by=user` 에 `granularity` 를 전달해도 400 이 아닌 200 으로 관대 처리한다.
 * `granularity` 는 무시되고 응답에도 포함하지 않는다.
 *
 * @param service 워크로그 집계 유스케이스 서비스.
 */
@RestController
@RequestMapping("/api/v1/worklogs")
class WorklogAggregateController(
    private val service: WorklogAggregateService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 프로젝트 워크로그를 차원별로 집계한다.
     *
     * @param project     집계 대상 프로젝트 키.
     * @param by          집계 차원 문자열 (issue / user / period — 대소문자 무관).
     * @param granularity 기간 버킷 단위 문자열 (day / week / month). by=period 일 때만 적용.
     * @param from        시작일 YYYY-MM-DD. null 이면 필터 없음.
     * @param to          종료일 YYYY-MM-DD. null 이면 필터 없음.
     * @return 200 OK + [WorklogAggregateResponse].
     * @throws ResponseStatusException 400 — project 누락·blank / by 무효 / granularity 무효 /
     *                                         from·to 형식 무효 / from > to.
     * @throws ResponseStatusException 401 — 미인증 / nil-UUID / 비-UUID 주체.
     * @throws com.bts.issue.domain.IssueAccessDeniedException → 403.
     */
    @GetMapping("/aggregate")
    @Suppress("ThrowsCount") // 파라미터별 독립 검증으로 명확한 400 메시지 위해 분리 throw 유지
    fun aggregate(
        @RequestParam(required = false) project: String?,
        @RequestParam(required = false) by: String?,
        @RequestParam(required = false) granularity: String?,
        @RequestParam(required = false) from: String?,
        @RequestParam(required = false) to: String?,
    ): DataResponse<WorklogAggregateResponse> {
        // 1. actor 추출 최상단 — 미인증·nil-UUID·비-UUID → 401 (probe 방지)
        val actor = CurrentActor.current()

        // 2. project 검증
        val projectKey = validateProject(project)

        // 3. by 검증 (String → enum 수동 파싱)
        val dimension = parseDimension(by)

        // 4. granularity 검증 (by=period 일 때 필수, by≠period 이면 무시 — C6 관대 처리)
        val parsedGranularity = parseGranularity(granularity = granularity, dimension = dimension)

        // 5. from/to 검증 (LocalDate.parse 형식 검증 → Instant 변환)
        val fromDate = parseLocalDate(from, "from")
        val toDate = parseLocalDate(to, "to")
        validateDateRange(fromDate, toDate)

        // Instant 변환 (서비스 계약)
        val fromInstant = fromDate?.atStartOfDay(ZoneOffset.UTC)?.toInstant()
        val toInstant = toDate?.atStartOfDay(ZoneOffset.UTC)?.toInstant()

        log.info(
            "WorklogAggregateController.aggregate actor={} project={} by={} granularity={} from={} to={}",
            actor.value,
            projectKey,
            dimension,
            parsedGranularity,
            fromInstant,
            toInstant,
        )

        // 6. 서비스 호출
        val result =
            service.aggregate(
                actorId = actor,
                projectKey = projectKey,
                dimension = dimension,
                granularity = parsedGranularity,
                from = fromInstant,
                to = toInstant,
            )

        // 7. 응답 변환
        val response =
            WorklogAggregateResponse.from(
                result = result,
                dimension = dimension,
                granularity = parsedGranularity,
                from = fromDate,
                to = toDate,
            )

        return DataResponse(data = response)
    }

    // ── private helpers ────────────────────────────────────────────────────────

    /**
     * project 파라미터를 검증한다.
     *
     * null 또는 blank 이면 400 [ResponseStatusException] 을 던진다.
     *
     * @param project 쿼리 파라미터 값.
     * @return 공백 제거된 프로젝트 키.
     * @throws ResponseStatusException 400 — null 또는 blank.
     */
    private fun validateProject(project: String?): String {
        if (project.isNullOrBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "project 파라미터는 필수입니다.")
        }
        return project.trim()
    }

    /**
     * by 파라미터 문자열을 [WorklogAggregateDimension] 으로 파싱한다.
     *
     * null 또는 유효하지 않은 값이면 400 [ResponseStatusException] 을 던진다.
     * 파싱은 대소문자를 무시한다.
     *
     * @param by 쿼리 파라미터 값.
     * @return 파싱된 [WorklogAggregateDimension].
     * @throws ResponseStatusException 400 — null 또는 issue/user/period 외의 값.
     */
    private fun parseDimension(by: String?): WorklogAggregateDimension {
        if (by.isNullOrBlank()) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "by 파라미터는 필수입니다. 허용 값: issue, user, period",
            )
        }
        return runCatching { WorklogAggregateDimension.valueOf(by.uppercase()) }
            .getOrElse {
                throw ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "by 파라미터가 유효하지 않습니다. 허용 값: issue, user, period",
                )
            }
    }

    /**
     * granularity 파라미터 문자열을 [AggregateGranularity] 로 파싱한다.
     *
     * - by=period 이면 granularity 가 필수이며 기본값은 day. 무효 값이면 400.
     * - by≠period 이면 granularity 를 무시하고 null 반환 (C6 관대 처리).
     *
     * @param granularity 쿼리 파라미터 값.
     * @param dimension   집계 차원.
     * @return 파싱된 [AggregateGranularity] 또는 null.
     * @throws ResponseStatusException 400 — by=period 이고 무효 값.
     */
    @Suppress("ReturnCount") // C6 관대처리·기본값·파싱 실패 3-branch 분리가 가독성 최선
    private fun parseGranularity(
        granularity: String?,
        dimension: WorklogAggregateDimension,
    ): AggregateGranularity? {
        // by≠period → granularity 무시 (C6 관대 처리)
        if (dimension != WorklogAggregateDimension.PERIOD) {
            return null
        }
        // by=period: granularity 기본값 = day
        if (granularity.isNullOrBlank()) {
            return AggregateGranularity.DAY
        }
        return runCatching { AggregateGranularity.valueOf(granularity.uppercase()) }
            .getOrElse {
                throw ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "granularity 파라미터가 유효하지 않습니다. 허용 값: day, week, month",
                )
            }
    }

    /**
     * YYYY-MM-DD 형식의 날짜 문자열을 [LocalDate] 로 파싱한다.
     *
     * null 이면 null 반환. 형식 오류 시 400 [ResponseStatusException] 을 던진다.
     *
     * @param value     파싱할 날짜 문자열.
     * @param paramName 에러 메시지용 파라미터 이름 (from / to).
     * @return 파싱된 [LocalDate] 또는 null.
     * @throws ResponseStatusException 400 — YYYY-MM-DD 형식이 아닌 경우.
     */
    private fun parseLocalDate(
        value: String?,
        paramName: String,
    ): LocalDate? {
        if (value == null) return null
        return runCatching { LocalDate.parse(value) }
            .getOrElse {
                throw ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "$paramName 파라미터 형식이 올바르지 않습니다. YYYY-MM-DD 형식이어야 합니다.",
                )
            }
    }

    /**
     * from > to 검증.
     *
     * from 과 to 가 모두 전달되었을 때 from 이 to 보다 늦으면 400 을 던진다.
     *
     * @param from 시작일.
     * @param to   종료일.
     * @throws ResponseStatusException 400 — from > to.
     */
    private fun validateDateRange(
        from: LocalDate?,
        to: LocalDate?,
    ) {
        if (from != null && to != null && from.isAfter(to)) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "from 이 to 보다 늦을 수 없습니다.")
        }
    }
}
