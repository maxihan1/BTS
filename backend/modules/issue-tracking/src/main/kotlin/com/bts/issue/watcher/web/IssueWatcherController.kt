// 이슈 워처 REST 컨트롤러 — GET/POST/DELETE watchers 엔드포인트 (FR-WT-01)

package com.bts.issue.watcher.web

import com.bts.issue.adapter.inbound.rest.CurrentActor
import com.bts.issue.adapter.inbound.rest.DataResponse
import com.bts.issue.domain.IssueKey
import com.bts.issue.watcher.application.IssueWatcherService
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * 이슈 워처 REST 컨트롤러.
 *
 * 엔드포인트 목록.
 * - GET    /api/v1/issues/{key}/watchers          — 워처 목록 조회 (FR-WT-01)
 * - POST   /api/v1/issues/{key}/watchers          — 워처 추가 (self 또는 타인)
 * - DELETE /api/v1/issues/{key}/watchers/{userId} — 워처 제거 (멱등)
 *
 * ### ActorId 결선
 * [CurrentActor.current] 로 SecurityContextHolder 의 인증 주체를 actor 로 추출한다.
 * 미인증·익명·비-UUID·nil-UUID 주체는 401(UNAUTHORIZED)로 거부한다.
 * actor 추출은 리소스 조회보다 앞서 수행하여 미인증자가 404 로 리소스 존재를 probe 하지 못하게 한다.
 *
 * ### POST 멱등
 * 이미 watch 중인 경우 [IssueWatcherService.watch] 는 ON CONFLICT DO NOTHING 으로 조용히 처리한다.
 * 컨트롤러는 항상 201 을 반환한다.
 *
 * ### DELETE 멱등
 * 존재하지 않는 워처를 제거해도 204 를 반환한다.
 * [IssueWatcherService.unwatch] 가 내부적으로 무시한다.
 *
 * @param service 워처 유스케이스 서비스.
 */
@RestController
@RequestMapping("/api/v1/issues/{key}/watchers")
class IssueWatcherController(
    private val service: IssueWatcherService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 이슈 워처 목록을 조회한다.
     *
     * @param key path variable 이슈 키 문자열.
     * @return 200 OK + [DataResponse]<[WatcherListResponse]> (watchers, count, isWatching).
     * @throws com.bts.issue.domain.IssueNotFoundException 이슈 미존재 또는 소프트 삭제 시 → 404.
     * @throws com.bts.issue.domain.IssueAccessDeniedException VIEW 권한 미보유 시 → 403.
     */
    @GetMapping
    fun listWatchers(
        @PathVariable key: String,
    ): ResponseEntity<DataResponse<WatcherListResponse>> {
        log.info("IssueWatcherController.listWatchers key={}", key)
        val actor = CurrentActor.current()
        val result = service.listWatchers(IssueKey(key), actor)
        val response =
            WatcherListResponse(
                watchers =
                    result.watchers.map { entry ->
                        WatcherSummary(
                            userId = entry.userId,
                            displayName = entry.displayName,
                        )
                    },
                count = result.count,
                isWatching = result.isWatching,
            )
        return ResponseEntity.ok(DataResponse(data = response))
    }

    /**
     * 이슈 워처를 추가한다.
     *
     * body 없이 요청하면 self(actor 본인)를, [AddWatcherRequest.userId] 를 명시하면 해당 사용자를 추가한다.
     *
     * @param key path variable 이슈 키 문자열.
     * @param request 요청 바디. null 이면 self 추가.
     * @return 201 Created (멱등 — 이미 존재해도 201).
     * @throws com.bts.issue.domain.IssueNotFoundException 이슈 미존재 또는 소프트 삭제 시 → 404.
     * @throws com.bts.issue.domain.IssueAccessDeniedException self=VIEW/타인=UPDATE 권한 미보유 시 → 403.
     * @throws com.bts.issue.watcher.application.WatcherUserNotFoundException 대상 사용자 미존재 시 → 422.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun addWatcher(
        @PathVariable key: String,
        @RequestBody(required = false) request: AddWatcherRequest?,
    ): ResponseEntity<Void> {
        log.info("IssueWatcherController.addWatcher key={} targetUserId={}", key, request?.userId)
        val actor = CurrentActor.current()
        service.watch(IssueKey(key), actor, request?.userId)
        return ResponseEntity.status(HttpStatus.CREATED).build()
    }

    /**
     * 이슈 워처를 제거한다.
     *
     * 워처가 존재하지 않아도 204 를 반환한다 (멱등).
     *
     * @param key path variable 이슈 키 문자열.
     * @param userId path variable 제거할 워처 사용자 UUID.
     * @throws com.bts.issue.domain.IssueNotFoundException 이슈 미존재 또는 소프트 삭제 시 → 404.
     * @throws com.bts.issue.domain.IssueAccessDeniedException self=VIEW/타인=UPDATE 권한 미보유 시 → 403.
     */
    @DeleteMapping("/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun removeWatcher(
        @PathVariable key: String,
        @PathVariable userId: UUID,
    ) {
        log.info("IssueWatcherController.removeWatcher key={} userId={}", key, userId)
        val actor = CurrentActor.current()
        service.unwatch(IssueKey(key), actor, userId)
    }
}
