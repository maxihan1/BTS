// 워크로그 REST 컨트롤러 — CRUD 엔드포인트 (FR-TT-01)

package com.bts.issue.worklog.web

import com.bts.issue.adapter.inbound.rest.CurrentActor
import com.bts.issue.domain.IssueKey
import com.bts.issue.worklog.application.WorklogService
import com.bts.issue.worklog.web.dto.AddWorklogRequest
import com.bts.issue.worklog.web.dto.UpdateWorklogRequest
import com.bts.issue.worklog.web.dto.WorklogResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * 워크로그 REST 컨트롤러 (FR-TT-01).
 *
 * 엔드포인트 목록.
 * - POST   /api/v1/issues/{key}/worklogs                — 워크로그 추가 (201)
 * - GET    /api/v1/issues/{key}/worklogs                — 워크로그 목록 + 추정 요약 조회 (200)
 * - PATCH  /api/v1/issues/{key}/worklogs/{worklogId}   — 워크로그 수정 (200)
 * - DELETE /api/v1/issues/{key}/worklogs/{worklogId}   — 워크로그 삭제 (204)
 *
 * ### ActorId 결선
 * [CurrentActor.current] 로 SecurityContextHolder 의 인증 주체를 actor 로 추출한다.
 * 미인증·익명·비-UUID·nil-UUID 주체는 401(UNAUTHORIZED) 로 거부한다.
 * actor 추출은 리소스 조회보다 앞서 수행하여 미인증자가 404 로 리소스 존재를 probe 하지 못하게 한다.
 *
 * ### 입력 검증
 * Bean Validation 대신 컨트롤러 명시 검증으로 400 을 보장한다.
 * DB CHECK 제약은 트랜잭션 내부에서 500 으로 표면화되므로 요청 단에서 먼저 차단한다.
 * - timeSpentSeconds ≤ 0 → 400
 * - newRemainingEstimateSeconds < 0 → 400
 * - startedAt 미전달 → 400
 * - timeSpentSeconds 미전달 → 400
 *
 * @param service 워크로그 유스케이스 서비스.
 */
@RestController
@RequestMapping("/api/v1/issues/{key}/worklogs")
class WorklogController(
    private val service: WorklogService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 워크로그를 추가한다.
     *
     * @param key path variable 이슈 키 문자열.
     * @param request 요청 바디.
     * @return 201 Created + [WorklogResponse].
     * @throws ResponseStatusException 400 — 유효성 검증 실패 (timeSpentSeconds ≤ 0 등).
     * @throws com.bts.issue.domain.IssueAccessDeniedException UPDATE 권한 미보유 시 → 403.
     * @throws com.bts.issue.domain.IssueNotFoundException 이슈 미존재·소프트 삭제 시 → 404.
     */
    @PostMapping
    @Suppress("ThrowsCount") // 요청 유효성(timeSpent≤0, newRemaining<0 등) 단계별로 명확한 400 메시지 위해 분리 throw 유지
    fun addWorklog(
        @PathVariable key: String,
        @RequestBody request: AddWorklogRequest,
    ): ResponseEntity<DataResponse<WorklogResponse>> {
        val actor = CurrentActor.current()
        log.info("WorklogController.addWorklog key={} actor={}", key, actor.value)

        val timeSpent =
            request.timeSpentSeconds
                ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "timeSpentSeconds 는 필수입니다.")
        if (timeSpent <= 0) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "timeSpentSeconds 는 1 이상이어야 합니다.")
        }

        val startedAt =
            request.startedAt
                ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "startedAt 은 필수입니다.")

        val remaining = request.newRemainingEstimateSeconds
        if (remaining != null && remaining < 0) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "newRemainingEstimateSeconds 는 0 이상이어야 합니다.")
        }

        val worklog =
            service.create(
                actor = actor,
                issueKey = IssueKey(key),
                timeSpentSeconds = timeSpent,
                startedAt = startedAt,
                comment = request.comment,
                newRemainingEstimateSeconds = remaining,
            )

        val response = WorklogResponse.from(worklog, key)
        return ResponseEntity.status(HttpStatus.CREATED).body(DataResponse(data = response))
    }

    /**
     * 이슈의 워크로그 목록과 추정 요약을 조회한다.
     *
     * @param key path variable 이슈 키 문자열.
     * @return 200 OK + [WorklogListResponse] (worklogs 배열 + summary).
     * @throws com.bts.issue.domain.IssueAccessDeniedException VIEW 권한 미보유 시 → 403.
     * @throws com.bts.issue.domain.IssueNotFoundException 이슈 미존재·소프트 삭제 시 → 404.
     */
    @GetMapping
    fun listWorklogs(
        @PathVariable key: String,
    ): ResponseEntity<DataResponse<WorklogListResponse>> {
        val actor = CurrentActor.current()
        log.info("WorklogController.listWorklogs key={} actor={}", key, actor.value)

        val view = service.listForIssue(actor = actor, issueKey = IssueKey(key))

        val response =
            WorklogListResponse(
                worklogs = view.worklogs.map { WorklogResponse.from(it, key) },
                summary =
                    WorklogSummary(
                        originalEstimateSeconds = view.originalEstimateSeconds,
                        timeSpentSeconds = view.timeSpentSeconds,
                        remainingEstimateSeconds = view.remainingEstimateSeconds,
                    ),
            )
        return ResponseEntity.ok(DataResponse(data = response))
    }

    /**
     * 워크로그를 수정한다.
     *
     * @param key path variable 이슈 키 문자열.
     * @param worklogId path variable 워크로그 UUID.
     * @param request 수정 요청 바디.
     * @return 200 OK + 수정된 [WorklogResponse].
     * @throws ResponseStatusException 400 — timeSpentSeconds ≤ 0.
     * @throws com.bts.issue.domain.IssueAccessDeniedException UPDATE 권한 미보유 또는 타인 수정 시 → 403.
     * @throws com.bts.issue.domain.IssueNotFoundException 이슈·워크로그 미존재·이슈 불일치 시 → 404.
     */
    @PatchMapping("/{worklogId}")
    fun updateWorklog(
        @PathVariable key: String,
        @PathVariable worklogId: UUID,
        @RequestBody request: UpdateWorklogRequest,
    ): ResponseEntity<DataResponse<WorklogResponse>> {
        val actor = CurrentActor.current()
        log.info("WorklogController.updateWorklog key={} worklogId={} actor={}", key, worklogId, actor.value)

        val timeSpent = request.timeSpentSeconds
        if (timeSpent != null && timeSpent <= 0) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "timeSpentSeconds 는 1 이상이어야 합니다.")
        }

        val worklog =
            service.update(
                actor = actor,
                issueKey = IssueKey(key),
                worklogId = worklogId,
                timeSpentSeconds = timeSpent,
                startedAt = request.startedAt,
                comment = request.comment,
            )

        val response = WorklogResponse.from(worklog, key)
        return ResponseEntity.ok(DataResponse(data = response))
    }

    /**
     * 워크로그를 소프트 삭제한다.
     *
     * @param key path variable 이슈 키 문자열.
     * @param worklogId path variable 삭제할 워크로그 UUID.
     * @throws com.bts.issue.domain.IssueAccessDeniedException UPDATE 권한 미보유 또는 타인 삭제 시 → 403.
     * @throws com.bts.issue.domain.IssueNotFoundException 이슈·워크로그 미존재·이슈 불일치 시 → 404.
     */
    @DeleteMapping("/{worklogId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteWorklog(
        @PathVariable key: String,
        @PathVariable worklogId: UUID,
    ) {
        val actor = CurrentActor.current()
        log.info("WorklogController.deleteWorklog key={} worklogId={} actor={}", key, worklogId, actor.value)
        service.delete(actor = actor, issueKey = IssueKey(key), worklogId = worklogId)
    }
}

/**
 * GET /worklogs 응답 DTO.
 *
 * worklogs 목록과 이슈 시간 추정 요약을 함께 반환한다.
 *
 * @property worklogs 활성 워크로그 목록.
 * @property summary  이슈 시간 추정 요약.
 */
data class WorklogListResponse(
    val worklogs: List<WorklogResponse>,
    val summary: WorklogSummary,
)

/**
 * 이슈 시간 추정 요약 DTO.
 *
 * @property originalEstimateSeconds  최초 추정 시간(초). null 이면 미추정.
 * @property timeSpentSeconds         누적 기록 작업 시간(초).
 * @property remainingEstimateSeconds 잔여 추정 시간(초). null 이면 미추정.
 */
data class WorklogSummary(
    val originalEstimateSeconds: Int?,
    val timeSpentSeconds: Int,
    val remainingEstimateSeconds: Int?,
)

/**
 * 단일 데이터 래퍼 응답 DTO.
 *
 * 성공 응답을 `{"data": ...}` 형식으로 감싼다.
 *
 * @param T 래핑할 데이터 타입.
 * @property data 실제 응답 데이터.
 */
data class DataResponse<T>(val data: T)
