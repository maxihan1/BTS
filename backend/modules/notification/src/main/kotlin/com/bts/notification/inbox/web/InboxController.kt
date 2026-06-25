// 개인 알림 보관함(Inbox) REST API 컨트롤러 — 목록 조회·미읽음 카운트·읽음/보관 변경·일괄 읽음 5종 엔드포인트

package com.bts.notification.inbox.web

import com.bts.notification.inbox.application.InboxService
import com.bts.notification.inbox.web.dto.ArchiveRequest
import com.bts.notification.inbox.web.dto.InboxItemResponse
import com.bts.notification.inbox.web.dto.ReadAllRequest
import com.bts.notification.inbox.web.dto.ReadAllResponse
import com.bts.notification.inbox.web.dto.ReadRequest
import com.bts.notification.inbox.web.dto.UnreadCountResponse
import com.bts.notification.repository.InboxQuery
import com.bts.notification.repository.InboxTab
import com.bts.notification.web.DataResponse
import com.bts.notification.web.currentActorId
import org.slf4j.LoggerFactory
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.web.PageableDefault
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

/** size cap 상수 — EC9: 페이지 크기 최댓값을 초과하면 100 으로 강제 제한한다. */
private const val MAX_PAGE_SIZE = 100

/**
 * 개인 알림 보관함(Inbox) REST API 컨트롤러.
 *
 * 엔드포인트 목록.
 * - GET    /api/v1/users/me/inbox             — 목록 조회 (탭/검색/페이지네이션)
 * - GET    /api/v1/users/me/inbox/unread-count — 미읽음 카운트
 * - PATCH  /api/v1/users/me/inbox/{id}/read   — 읽음 상태 변경 (204)
 * - PATCH  /api/v1/users/me/inbox/{id}/archive — 보관 상태 변경 (204)
 * - POST   /api/v1/users/me/inbox/read-all    — 일괄 읽음 처리
 *
 * 인증 정책.
 * [currentActorId] 헬퍼로 SecurityContext 에서 UUID 주체를 추출한다.
 * 미인증·비-UUID 주체는 401 로 거부한다.
 * actor 추출은 반드시 리소스 조회보다 먼저 수행한다
 * (memory: auth-extraction-before-resource-lookup 교훈).
 *
 * PATCH 응답 정책.
 * service.markRead / markArchive 는 Unit 을 반환한다 (Task 5 범위). 단건 재조회 없이
 * 204 No Content 로 응답한다. spec 원안이 200+InboxItemResponse 이나, service 시그니처 변경은
 * Task 5 영역이므로 임의 수정 금지 → **204 No Content** 로 단순화한다.
 *
 * @param service Inbox 비즈니스 로직 서비스
 */
@RestController
@RequestMapping("/api/v1/users/me/inbox")
class InboxController(
    private val service: InboxService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Inbox 목록을 탭/검색 조건 + 페이지네이션으로 조회한다.
     *
     * - 기본 탭: ALL (보관 안 된 전체)
     * - size cap: [MAX_PAGE_SIZE] 초과 시 [MAX_PAGE_SIZE] 로 강제 제한 (EC9)
     * - senderId, from, to 파라미터 형식 오류 시 Spring 이 400 으로 처리한다 ([InboxExceptionHandler] 경유).
     *
     * @param tab      탭 구분 (ALL / UNREAD / ARCHIVED), 기본값 ALL
     * @param q        제목 부분일치 검색 키워드
     * @param senderId 발신자 UUID 필터
     * @param issueKey 이슈 키 필터
     * @param from     생성 시각 하한 (ISO-8601)
     * @param to       생성 시각 상한 (ISO-8601)
     * @param pageable 페이지 정보, 기본 size=20
     * @return 200 OK + Page<InboxItemResponse>
     */
    @GetMapping
    @Suppress("LongParameterList") // REST 쿼리 파라미터(tab/q/senderId/issueKey/from/to/pageable) — 분리 불가
    fun listInbox(
        @RequestParam(required = false) tab: InboxTab?,
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) senderId: UUID?,
        @RequestParam(required = false) issueKey: String?,
        @RequestParam(required = false) from: Instant?,
        @RequestParam(required = false) to: Instant?,
        @PageableDefault(size = 20) pageable: Pageable,
    ): Page<InboxItemResponse> {
        val actorId = currentActorId()
        val query = InboxQuery(
            tab = tab ?: InboxTab.ALL,
            q = q,
            senderId = senderId,
            issueKey = issueKey,
            from = from,
            to = to,
        )
        val cappedPageable = capPageSize(pageable)
        log.debug("InboxController.listInbox actorId={} query={} pageable={}", actorId, query, cappedPageable)
        return service.listInbox(actorId, query, cappedPageable)
            .map { InboxItemResponse.from(it) }
    }

    /**
     * 수신자의 미읽음 IN_APP 알림 수를 반환한다.
     *
     * @return 200 OK + DataResponse<UnreadCountResponse>
     */
    @GetMapping("/unread-count")
    fun unreadCount(): ResponseEntity<DataResponse<UnreadCountResponse>> {
        val actorId = currentActorId()
        log.debug("InboxController.unreadCount actorId={}", actorId)
        val count = service.unreadCount(actorId)
        return ResponseEntity.ok(DataResponse(UnreadCountResponse(count)))
    }

    /**
     * 알림 1건의 읽음 상태를 변경한다.
     *
     * service.markRead 는 Unit 반환이므로 204 No Content 로 응답한다.
     * 대상 없음/타인 소유이면 [com.bts.notification.inbox.application.InboxItemNotFoundException] →
     * [InboxExceptionHandler] 가 404 로 변환한다.
     *
     * @param id   대상 알림 ID
     * @param body 읽음 상태 변경 요청
     */
    @PatchMapping("/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun markRead(
        @PathVariable id: UUID,
        @RequestBody body: ReadRequest,
    ) {
        val actorId = currentActorId()
        log.info("InboxController.markRead actorId={} id={} read={}", actorId, id, body.read)
        service.markRead(actorId, id, body.read)
    }

    /**
     * 알림 1건의 보관 상태를 변경한다.
     *
     * service.markArchive 는 Unit 반환이므로 204 No Content 로 응답한다.
     * 대상 없음/타인 소유이면 [com.bts.notification.inbox.application.InboxItemNotFoundException] →
     * [InboxExceptionHandler] 가 404 로 변환한다.
     *
     * @param id   대상 알림 ID
     * @param body 보관 상태 변경 요청
     */
    @PatchMapping("/{id}/archive")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun markArchive(
        @PathVariable id: UUID,
        @RequestBody body: ArchiveRequest,
    ) {
        val actorId = currentActorId()
        log.info("InboxController.markArchive actorId={} id={} archived={}", actorId, id, body.archived)
        service.markArchive(actorId, id, body.archived)
    }

    /**
     * 본인의 미읽음 알림을 일괄 읽음 처리한다.
     *
     * ids 가 null 또는 빈 목록이면 미읽음 전체를 처리한다.
     * 변경 건수가 0 이어도 정상이다.
     *
     * @param body 읽음 처리할 ID 목록 (선택)
     * @return 200 OK + DataResponse<ReadAllResponse>
     */
    @PostMapping("/read-all")
    fun readAll(
        @RequestBody body: ReadAllRequest,
    ): ResponseEntity<DataResponse<ReadAllResponse>> {
        val actorId = currentActorId()
        log.info("InboxController.readAll actorId={} ids={}", actorId, body.ids)
        val updated = service.readAll(actorId, body.ids)
        return ResponseEntity.ok(DataResponse(ReadAllResponse(updated)))
    }

    /**
     * 페이지 크기를 [MAX_PAGE_SIZE] 로 상한 제한한다.
     *
     * EC9 대응: 클라이언트가 과도한 size 를 요청해도 서버에서 강제 제한한다.
     *
     * @param pageable 원본 페이지 정보
     * @return size 가 [MAX_PAGE_SIZE] 이하로 조정된 Pageable
     */
    private fun capPageSize(pageable: Pageable): Pageable {
        val cappedSize = minOf(pageable.pageSize, MAX_PAGE_SIZE)
        return if (cappedSize == pageable.pageSize) {
            pageable
        } else {
            org.springframework.data.domain.PageRequest.of(pageable.pageNumber, cappedSize, pageable.sort)
        }
    }
}
