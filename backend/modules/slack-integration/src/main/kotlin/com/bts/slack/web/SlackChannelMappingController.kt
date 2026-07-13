// 프로젝트 관리자가 Slack 채널↔프로젝트 매핑을 CRUD 하는 REST 컨트롤러 — JWT 전용 (FR-SL-06 Task 7)

package com.bts.slack.web

import com.bts.slack.application.SlackChannelMappingService
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * 프로젝트 관리자가 Slack 채널↔프로젝트 매핑을 CRUD 하는 REST 컨트롤러 (FR-SL-06 Task 7).
 *
 * spec API 표 4 endpoint.
 * - `POST   /api/v1/slack/channel-mappings`      — 생성(201 + 매핑 뷰).
 * - `GET    /api/v1/slack/channel-mappings`      — `projectKey` 쿼리 파라미터로 목록 조회(200).
 * - `PATCH  /api/v1/slack/channel-mappings/{id}` — 채널/이벤트 필터 부분 수정(200 + 매핑 뷰).
 * - `DELETE /api/v1/slack/channel-mappings/{id}` — 삭제(204).
 *
 * 실제 권한 판정(프로젝트 관리자 게이트, fail-closed)과 트랜잭션 경계는 이 컨트롤러가 아니라
 * [SlackChannelMappingService] 가 담당한다 — 이 컨트롤러는 인증 주체 추출과 요청/응답 DTO 변환만 한다.
 *
 * ## 현재 사용자 식별 — `@AuthenticationPrincipal Jwt?` 타입 기반 (PAT 401)
 * [SlackConnectionController] 선례를 그대로 따른다. principal 이 [Jwt] 가 아니면(PAT 등) `null` 이 주입되어
 * [currentUserId] 가 401 로 거부한다. `SlackActorExtractor` 를 재사용하지 않는 이유도 동일하다 — 그것은
 * `Authentication.name` 을 UUID 로 파싱할 뿐이라 PAT 인증 주체도 통과시켜 이 매핑 관리 API 를 PAT 토큰으로
 * 호출할 수 있게 만든다(리뷰 BLOCKER 재현 회피).
 *
 * ## 예외 → HTTP 매핑
 * 예외는 [SlackChannelMappingExceptionHandler](이 컨트롤러 전용 스코프)가 상태코드로 변환한다.
 *
 * ## DTO↔도메인 매핑
 * 응답 변환은 [ChannelMappingResponse.from] 이 담당한다(automation `AutomationRuleResponse.from` 동형
 * 컨벤션) — 이 컨트롤러는 서비스 호출 결과를 그대로 넘기기만 한다.
 *
 * ## 비노출 (§1.1.2)
 * 응답([ChannelMappingResponse])은 `team_id` 를 담지 않는다 — 매핑 생성 시 내부적으로만 해석되는 값이다.
 *
 * @param service 채널 매핑 CRUD 오케스트레이션 서비스(권한 게이트 포함).
 */
@RestController
@RequestMapping("/api/v1/slack/channel-mappings")
class SlackChannelMappingController(
    private val service: SlackChannelMappingService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * POST `/api/v1/slack/channel-mappings` — 새 채널↔프로젝트 매핑을 생성한다.
     *
     * @param jwt 인증 JWT principal. PAT 등 미지원 인증이면 401.
     * @param request 생성 요청 바디.
     * @return 201 Created + 생성된 매핑 뷰.
     */
    @PostMapping
    fun create(
        @AuthenticationPrincipal jwt: Jwt?,
        @RequestBody request: CreateChannelMappingRequest,
    ): ResponseEntity<ChannelMappingResponse> {
        val actorId = currentUserId(jwt)
        val created =
            service.create(
                actorId = actorId,
                projectKey = request.projectKey,
                channelId = request.channelId,
                channelName = request.channelName,
                eventTypes = request.eventTypes,
            )
        log.info(
            "SLACK_CHANNEL_MAPPING_CREATE actor={} projectKey={} id={}",
            actorId,
            request.projectKey,
            created.id,
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(ChannelMappingResponse.from(created))
    }

    /**
     * GET `/api/v1/slack/channel-mappings?projectKey=` — 프로젝트에 속한 채널 매핑 목록을 조회한다.
     *
     * @param jwt 인증 JWT principal. PAT 등 미지원 인증이면 401.
     * @param projectKey 조회 대상 프로젝트 키(쿼리 파라미터).
     * @return 200 OK + 매핑 뷰 목록.
     */
    @GetMapping
    fun list(
        @AuthenticationPrincipal jwt: Jwt?,
        @RequestParam projectKey: String,
    ): List<ChannelMappingResponse> {
        val actorId = currentUserId(jwt)
        return service.list(actorId, projectKey).map(ChannelMappingResponse::from)
    }

    /**
     * PATCH `/api/v1/slack/channel-mappings/{id}` — 기존 매핑의 채널/이벤트 필터를 부분 수정한다.
     *
     * @param jwt 인증 JWT principal. PAT 등 미지원 인증이면 401.
     * @param id 수정 대상 매핑 id(경로 변수).
     * @param request 부분 수정 요청 바디(null 필드는 미변경).
     * @return 200 OK + 변경된 매핑 뷰.
     */
    @PatchMapping("/{id}")
    fun update(
        @AuthenticationPrincipal jwt: Jwt?,
        @PathVariable id: UUID,
        @RequestBody request: UpdateChannelMappingRequest,
    ): ChannelMappingResponse {
        val actorId = currentUserId(jwt)
        val updated =
            service.update(
                actorId = actorId,
                id = id,
                channelId = request.channelId,
                channelName = request.channelName,
                eventTypes = request.eventTypes,
            )
        log.info("SLACK_CHANNEL_MAPPING_UPDATE actor={} id={}", actorId, id)
        return ChannelMappingResponse.from(updated)
    }

    /**
     * DELETE `/api/v1/slack/channel-mappings/{id}` — 매핑을 삭제한다.
     *
     * @param jwt 인증 JWT principal. PAT 등 미지원 인증이면 401.
     * @param id 삭제 대상 매핑 id(경로 변수).
     */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(
        @AuthenticationPrincipal jwt: Jwt?,
        @PathVariable id: UUID,
    ) {
        val actorId = currentUserId(jwt)
        service.delete(actorId, id)
        log.info("SLACK_CHANNEL_MAPPING_DELETE actor={} id={}", actorId, id)
    }

    // ── private helpers ───────────────────────────────────────────────────────

    /**
     * JWT subject(UUID) 로 현재 사용자를 식별한다([SlackConnectionController] 와 동일 원칙).
     *
     * @param jwt [AuthenticationPrincipal] 로 주입된 JWT. PAT 등 미지원 인증이면 null.
     * @return 현재 사용자 UUID.
     * @throws ResponseStatusException subject 가 없거나 UUID 형식이 아니면 401.
     */
    private fun currentUserId(jwt: Jwt?): UUID =
        jwt?.subject?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
}
