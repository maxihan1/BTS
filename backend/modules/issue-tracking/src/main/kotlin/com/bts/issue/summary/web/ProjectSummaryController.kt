// 프로젝트 요약·활동 조회 REST 컨트롤러 — GET /api/v1/projects/{projectKey}/{summary,activity}

package com.bts.issue.summary.web

import com.bts.issue.adapter.inbound.rest.CurrentActor
import com.bts.issue.config.BEARER_AUTH_SCHEME
import com.bts.issue.summary.application.ProjectSummaryService
import com.bts.issue.summary.web.dto.ProjectActivityResponse
import com.bts.issue.summary.web.dto.ProjectSummaryResponse
import com.bts.issue.worklog.web.DataResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
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

/**
 * 프로젝트 요약 화면과 활동 피드 조회 REST 컨트롤러 (Jira 패리티 캠페인 PR ③).
 *
 * ## 엔드포인트
 * - `GET /api/v1/projects/{projectKey}/summary` — 카드 4종 + 분포 4종 (200)
 * - `GET /api/v1/projects/{projectKey}/activity` — 프로젝트 스코프 변경 이력 피드 (200)
 *
 * ### ActorId 결선 순서
 * [CurrentActor.current] 를 **최상단**에서 호출해 미인증/nil-UUID/비-UUID 주체를 401 로 거부한다.
 * 파라미터 검증보다 먼저 수행해야 미인증자가 파라미터 오류 메시지로 프로젝트 존재를 probe 하지
 * 못한다([com.bts.issue.cfd.web.CfdController] 선례).
 *
 * ### 창 파라미터가 없는 이유
 * 요약 창은 Jira 클라우드 스펙대로 고정이다 — 카드는 7일, 상태 개요의 DONE 버킷은 2주.
 * 화면에 기간 선택 UI 가 없으므로 `days` 같은 파라미터는 아무도 쓰지 않는 유연성이 된다.
 *
 * @param service 요약·활동 조회 유스케이스 서비스.
 */
@Tag(name = "ProjectSummary", description = "프로젝트 요약 화면 조회 API (Jira 패리티 J4)")
@RestController
@RequestMapping("/api/v1/projects")
class ProjectSummaryController(
    private val service: ProjectSummaryService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 프로젝트 요약 한 벌을 조회한다.
     *
     * @param projectKey 대상 프로젝트 키.
     * @return 200 OK + [ProjectSummaryResponse].
     * @throws ResponseStatusException 401 — 미인증 / nil-UUID / 비-UUID 주체.
     * @throws com.bts.issue.domain.IssueAccessDeniedException → 403.
     */
    @Operation(
        operationId = "getProjectSummary",
        summary = "프로젝트 요약 조회",
        description =
            "최근 7일 완료·업데이트·생성과 향후 7일 마감 카드, 상태·우선순위·유형·담당자 분포를 반환한다. " +
                "상태 개요의 DONE 버킷만 최근 2주 안에 완료된 이슈로 한정된다.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "프로젝트 요약"),
        ApiResponse(responseCode = "401", description = "미인증", content = [Content()]),
        ApiResponse(responseCode = "403", description = "프로젝트 BROWSE 권한 없음", content = [Content()]),
    )
    @SecurityRequirement(name = BEARER_AUTH_SCHEME)
    @GetMapping("/{projectKey}/summary")
    fun summary(
        @PathVariable projectKey: String,
    ): DataResponse<ProjectSummaryResponse> {
        // actor 추출 최상단 — 미인증·nil-UUID·비-UUID → 401 (probe 방지)
        val actor = CurrentActor.current()

        log.info("ProjectSummaryController.summary actor={} projectKey={}", actor.value, projectKey)

        return DataResponse(data = ProjectSummaryResponse.from(service.getSummary(actor, projectKey)))
    }

    /**
     * 프로젝트 활동 피드를 최신순으로 조회한다.
     *
     * @param projectKey 대상 프로젝트 키.
     * @param limit 반환할 최대 변경 그룹 수. 생략 시 [DEFAULT_ACTIVITY_LIMIT].
     * @return 200 OK + [ProjectActivityResponse].
     * @throws ResponseStatusException 400 — [limit] 이 1~[MAX_ACTIVITY_LIMIT] 범위 밖.
     * @throws ResponseStatusException 401 — 미인증 / nil-UUID / 비-UUID 주체.
     * @throws com.bts.issue.domain.IssueAccessDeniedException → 403.
     */
    @Operation(
        operationId = "getProjectActivity",
        summary = "프로젝트 활동 피드 조회",
        description = "프로젝트 안에서 일어난 변경 이력을 최신순으로 반환한다. 한 항목 = 한 변경 그룹(한 트랜잭션).",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "활동 피드"),
        ApiResponse(responseCode = "400", description = "limit 범위 오류", content = [Content()]),
        ApiResponse(responseCode = "401", description = "미인증", content = [Content()]),
        ApiResponse(responseCode = "403", description = "프로젝트 BROWSE 권한 없음", content = [Content()]),
    )
    @SecurityRequirement(name = BEARER_AUTH_SCHEME)
    @GetMapping("/{projectKey}/activity")
    fun activity(
        @PathVariable projectKey: String,
        @Parameter(
            description = "반환할 최대 변경 그룹 수. 기본 20, 1~50. 범위 밖이면 400 — 조용히 자르지 않는다.",
            schema = Schema(type = "integer", minimum = "1", maximum = "50", defaultValue = "20"),
        )
        @RequestParam(required = false) limit: Int?,
    ): DataResponse<ProjectActivityResponse> {
        // 1. actor 추출 최상단 — 파라미터 검증보다 먼저 (probe 방지)
        val actor = CurrentActor.current()

        // 2. limit 검증 — 상한을 조용히 잘라 주면 호출자가 자기 요청이 무시된 걸 모른다.
        val resolvedLimit = limit ?: DEFAULT_ACTIVITY_LIMIT
        if (resolvedLimit < 1 || resolvedLimit > MAX_ACTIVITY_LIMIT) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "limit 은 1 이상 $MAX_ACTIVITY_LIMIT 이하여야 합니다.",
            )
        }

        log.info(
            "ProjectSummaryController.activity actor={} projectKey={} limit={}",
            actor.value,
            projectKey,
            resolvedLimit,
        )

        return DataResponse(
            data = ProjectActivityResponse.from(service.getActivity(actor, projectKey, resolvedLimit)),
        )
    }

    private companion object {
        /** `limit` 생략 시 기본 활동 항목 수. */
        const val DEFAULT_ACTIVITY_LIMIT = 20

        /** 허용되는 최대 활동 항목 수. */
        const val MAX_ACTIVITY_LIMIT = 50
    }
}
