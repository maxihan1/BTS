// CFD(누적 흐름도) 조회 REST 컨트롤러 — GET /api/v1/projects/{projectKey}/cfd (FR-RP-03 Task 5)

package com.bts.issue.cfd.web

import com.bts.issue.adapter.inbound.rest.CurrentActor
import com.bts.issue.cfd.application.CfdService
import com.bts.issue.cfd.web.dto.CfdResponse
import com.bts.issue.config.BEARER_AUTH_SCHEME
import com.bts.issue.worklog.web.DataResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.Clock
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * CFD(Cumulative Flow Diagram, 누적 흐름도) 조회 REST 컨트롤러 (FR-RP-03 Task 5).
 *
 * ## 엔드포인트
 * - GET /api/v1/projects/{projectKey}/cfd — 프로젝트 CFD 시계열 조회 (200)
 *
 * ### ActorId 결선 순서
 * [CurrentActor.current] 를 최상단에서 호출하여 미인증/nil-UUID/비-UUID 주체를 401 로 거부한다.
 * 파라미터 검증보다 먼저 수행하여 미인증자가 파라미터 오류 정보로 존재를 probe 하지 못하도록 한다
 * ([com.bts.issue.worklog.aggregate.web.WorklogAggregateController] 선례).
 *
 * ### 창 해석 (from/to 생략 처리)
 * - 둘 다 생략 → to=오늘([Clock] 기준 UTC), from=to-([DEFAULT_WINDOW_DAYS]-1)일(총 30일 창).
 * - to 만 생략 → to=오늘.
 * - from 만 생략 → from=to-([DEFAULT_WINDOW_DAYS]-1)일.
 * - 해석된 실제 창은 [CfdResponse] 의 from/to 로 echo 되어 프론트가 실제 적용 범위를 알 수 있다.
 *
 * ### Clock 주입
 * 모듈에 전역 [Clock] 빈이 없으므로 [Clock.systemUTC] 를 기본값으로 둔다
 * (컴포넌트 스캔 시 `NoSuchBeanDefinitionException` 방지 — FR-EX-01 `ExportService` 선례).
 * 테스트는 [Clock.fixed] 고정 인스턴스를 생성자로 주입해 결정적으로 검증한다.
 *
 * @param service CFD 조회 유스케이스 서비스.
 * @param clock 창 기본값(오늘 날짜) 결정을 위한 시계.
 */
@Tag(name = "CFD", description = "누적 흐름도(Cumulative Flow Diagram) 조회 API (FR-RP-03)")
@RestController
@RequestMapping("/api/v1/projects")
class CfdController(
    private val service: CfdService,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 프로젝트의 CFD(누적 흐름도) 시계열을 조회한다.
     *
     * @param projectKey 대상 프로젝트 키.
     * @param from 창 시작일 YYYY-MM-DD. null 이면 [resolveWindow] 가 보완한다.
     * @param to 창 종료일 YYYY-MM-DD. null 이면 [resolveWindow] 가 보완한다.
     * @return 200 OK + [CfdResponse] (해석된 실제 from/to 를 echo).
     * @throws ResponseStatusException 400 — from·to 형식 무효 / from > to /
     *                                         창 길이 [MAX_WINDOW_DAYS] 일 초과.
     * @throws ResponseStatusException 401 — 미인증 / nil-UUID / 비-UUID 주체.
     * @throws com.bts.issue.domain.IssueAccessDeniedException → 403.
     */
    @Operation(
        operationId = "getCfd",
        summary = "프로젝트 CFD(누적 흐름도) 조회",
        description = "from/to(YYYY-MM-DD, 생략 시 최근 30일)로 지정한 창의 날짜별 상태 카테고리 3띠 누적 카운트 시계열을 반환한다.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "CFD 시계열 조회 결과"),
        ApiResponse(responseCode = "400", description = "파라미터 검증 오류", content = [Content()]),
        ApiResponse(responseCode = "401", description = "미인증", content = [Content()]),
        ApiResponse(responseCode = "403", description = "프로젝트 BROWSE 권한 없음", content = [Content()]),
    )
    @SecurityRequirement(name = BEARER_AUTH_SCHEME)
    @GetMapping("/{projectKey}/cfd")
    fun cfd(
        @PathVariable projectKey: String,
        @RequestParam(required = false) from: String?,
        @RequestParam(required = false) to: String?,
    ): DataResponse<CfdResponse> {
        // 1. actor 추출 최상단 — 미인증·nil-UUID·비-UUID → 401 (probe 방지)
        val actor = CurrentActor.current()

        // 2. from/to 파싱 (형식 오류 → 400)
        val fromDate = parseLocalDate(from, "from")
        val toDate = parseLocalDate(to, "to")

        // 3. 창 해석 (생략된 값 보완) + 4. 검증 (from > to, 창 길이 상한)
        val (resolvedFrom, resolvedTo) = resolveWindow(fromDate, toDate)
        validateWindow(resolvedFrom, resolvedTo)

        log.info(
            "CfdController.cfd actor={} projectKey={} from={} to={}",
            actor.value,
            projectKey,
            resolvedFrom,
            resolvedTo,
        )

        // 5. 서비스 호출 + 응답 변환
        val result = service.getCfd(actor, projectKey, resolvedFrom, resolvedTo)
        return DataResponse(data = CfdResponse.from(result))
    }

    // ── private helpers ────────────────────────────────────────────────────────

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
     * 생략된 from/to 를 보완해 실제 조회 창을 해석한다.
     *
     * to 를 먼저 확정(생략 시 [clock] 기준 오늘)한 뒤, from 을 확정(생략 시 확정된 to 에서
     * [DEFAULT_WINDOW_DAYS]-1 일을 뺀 날짜)한다. from 만 생략된 경우에도 이 순서로 동일하게 적용된다.
     *
     * @param from 파싱된 from(생략 시 null).
     * @param to   파싱된 to(생략 시 null).
     * @return 해석된 (from, to) 쌍.
     */
    private fun resolveWindow(
        from: LocalDate?,
        to: LocalDate?,
    ): Pair<LocalDate, LocalDate> {
        val resolvedTo = to ?: LocalDate.now(clock)
        val resolvedFrom = from ?: resolvedTo.minusDays((DEFAULT_WINDOW_DAYS - 1).toLong())
        return resolvedFrom to resolvedTo
    }

    /**
     * 해석된 창을 검증한다.
     *
     * from > to 이거나 창 길이(양 끝 포함, [ChronoUnit.DAYS] + 1)가 [MAX_WINDOW_DAYS] 를 초과하면
     * 400 [ResponseStatusException] 을 던진다.
     *
     * @param from 해석된 창 시작일.
     * @param to   해석된 창 종료일.
     * @throws ResponseStatusException 400 — from > to / 창 길이 상한 초과.
     */
    private fun validateWindow(
        from: LocalDate,
        to: LocalDate,
    ) {
        if (from.isAfter(to)) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "from 이 to 보다 늦을 수 없습니다.")
        }
        val windowLength = ChronoUnit.DAYS.between(from, to) + 1
        if (windowLength > MAX_WINDOW_DAYS) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "조회 창은 최대 ${MAX_WINDOW_DAYS}일까지 가능합니다.",
            )
        }
    }

    private companion object {
        /** from/to 둘 다 생략 시 기본 창 길이(일, 양 끝 포함). */
        const val DEFAULT_WINDOW_DAYS = 30

        /** 허용되는 최대 창 길이(일, 양 끝 포함). */
        const val MAX_WINDOW_DAYS = 180
    }
}
