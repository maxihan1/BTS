// SlackChannelMappingController 예외를 HTTP 상태로 변환 — 컨트롤러 전용 스코프 (FR-SL-06 Task 7)

package com.bts.slack.web

import com.bts.slack.application.SlackChannelMappingConflictException
import com.bts.slack.application.SlackChannelMappingNotFoundException
import com.bts.slack.application.SlackChannelMappingPermissionDeniedException
import com.bts.slack.application.WorkspaceNotInstalledException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.server.ResponseStatusException

/**
 * [SlackChannelMappingController] 예외를 HTTP 상태로 변환한다 (FR-SL-06 Task 7).
 *
 * [assignableTypes] 를 [SlackChannelMappingController] 하나로 한정해([SlackConnectionExceptionHandler]
 * 동형) 형제/타 BC 컨트롤러를 가로채지 않는다(교훈 domain-exception-http-handler-basepackage-scope).
 *
 * ## catch-all 안티패턴 회피 (교훈 catch-all-exceptionhandler-swallows-responsestatusexception)
 * [ResponseStatusException] 은 [handleResponseStatus] 가 상태를 그대로 전파한다 —
 * `SlackChannelMappingController.currentUserId` 가 던지는 401 이 [handleInternal] catch-all 에 삼켜져
 * 500 으로 변질되지 않는다.
 *
 * ## 예외 → 상태 매핑
 * [SlackChannelMappingPermissionDeniedException] 403 · [SlackChannelMappingNotFoundException] 404 ·
 * [SlackChannelMappingConflictException]/[WorkspaceNotInstalledException] 409 ·
 * `IllegalArgumentException`(eventTypes 도메인 검증, [SlackChannelMappingConflictException] 등과 무관한
 * 순수 stdlib 예외) 400 · 프레임워크 바인딩 실패(비-UUID `{id}`·바디 형식/필수필드 오류·필수 쿼리 누락)
 * 400([handleMalformedRequest] 이 catch-all 보다 먼저 걷어내 500 이 되지 않게 한다).
 *
 * ## 누출 차단 (§1.1.2)
 * 도메인/서비스 예외의 message 는 projectKey·channelId·teamId·매핑 id 등 가변 값을 담지 않으므로 그대로
 * 노출하지 않고, 이 핸들러가 별도로 작성한 사용자 노출용 고정 메시지만 응답에 담는다.
 */
@RestControllerAdvice(assignableTypes = [SlackChannelMappingController::class])
class SlackChannelMappingExceptionHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 행위자가 대상 프로젝트의 채널 매핑 관리 권한이 없음(fail-closed) → 403. */
    @ExceptionHandler(SlackChannelMappingPermissionDeniedException::class)
    fun handlePermissionDenied(): ResponseEntity<Map<String, String>> {
        log.info("SLACK_CHANNEL_MAPPING_403 permission_denied")
        return errorResponse(HttpStatus.FORBIDDEN, SlackChannelMappingErrorCode.PERMISSION_DENIED)
    }

    /** 대상 매핑 id 가 존재하지 않음 → 404. */
    @ExceptionHandler(SlackChannelMappingNotFoundException::class)
    fun handleNotFound(): ResponseEntity<Map<String, String>> {
        log.info("SLACK_CHANNEL_MAPPING_404 not_found")
        return errorResponse(HttpStatus.NOT_FOUND, SlackChannelMappingErrorCode.NOT_FOUND)
    }

    /** 같은 team+project+channel 매핑이 이미 존재함(V704 UNIQUE 위반) → 409. */
    @ExceptionHandler(SlackChannelMappingConflictException::class)
    fun handleConflict(): ResponseEntity<Map<String, String>> {
        log.info("SLACK_CHANNEL_MAPPING_409 conflict")
        return errorResponse(HttpStatus.CONFLICT, SlackChannelMappingErrorCode.CONFLICT)
    }

    /** Slack 워크스페이스 설치가 0건이라 team_id 를 해석할 수 없음(EC12) → 409. */
    @ExceptionHandler(WorkspaceNotInstalledException::class)
    fun handleWorkspaceNotInstalled(): ResponseEntity<Map<String, String>> {
        log.info("SLACK_CHANNEL_MAPPING_409 workspace_not_installed")
        return errorResponse(HttpStatus.CONFLICT, SlackChannelMappingErrorCode.WORKSPACE_NOT_INSTALLED)
    }

    /** eventTypes 가 비었거나 미지 wireValue 를 포함함(도메인 `init` 검증, EC1) → 400. */
    @ExceptionHandler(IllegalArgumentException::class)
    fun handleInvalidEventTypes(): ResponseEntity<Map<String, String>> {
        log.info("SLACK_CHANNEL_MAPPING_400 invalid_event_types")
        return errorResponse(HttpStatus.BAD_REQUEST, SlackChannelMappingErrorCode.INVALID_EVENT_TYPES)
    }

    /** [ResponseStatusException] — `currentUserId` 의 401 등 명시 상태를 그대로 전파한다. */
    @ExceptionHandler(ResponseStatusException::class)
    fun handleResponseStatus(ex: ResponseStatusException): ResponseEntity<Map<String, String>> {
        val status = HttpStatus.valueOf(ex.statusCode.value())
        log.info("SLACK_CHANNEL_MAPPING_{} response_status", status.value())
        return errorResponse(status, SlackChannelMappingErrorCode.UNAUTHENTICATED)
    }

    /**
     * 프레임워크 요청 바인딩 실패 → 400 (500 아님).
     *
     * - [MethodArgumentTypeMismatchException] — `{id}` 경로변수가 비-UUID.
     * - [HttpMessageNotReadableException] — 요청 바디 JSON 형식/필수필드 오류(Kotlin non-null 파라미터 누락 포함).
     * - [MissingServletRequestParameterException] — 필수 쿼리 `projectKey` 누락.
     *
     * 세 예외 모두 [handleInternal] catch-all 보다 구체적이라 우선 매칭되어 malformed 입력을 클라이언트
     * 오류(400)로 응답한다. 원본 예외 message 는 노출하지 않고 고정 일반 문구만 담는다(§1.1.2).
     */
    @ExceptionHandler(
        MethodArgumentTypeMismatchException::class,
        HttpMessageNotReadableException::class,
        MissingServletRequestParameterException::class,
    )
    fun handleMalformedRequest(): ResponseEntity<Map<String, String>> {
        log.info("SLACK_CHANNEL_MAPPING_400 malformed_request")
        return errorResponse(HttpStatus.BAD_REQUEST, SlackChannelMappingErrorCode.BAD_REQUEST)
    }

    /** 분류되지 않은 모든 예외 → 500. */
    @ExceptionHandler(Exception::class)
    fun handleInternal(ex: Exception): ResponseEntity<Map<String, String>> {
        log.error("SLACK_CHANNEL_MAPPING_500 internal_error", ex)
        return errorResponse(HttpStatus.INTERNAL_SERVER_ERROR, SlackChannelMappingErrorCode.INTERNAL_ERROR)
    }

    // ── private helpers ───────────────────────────────────────────────────────

    /** `{"code":..., "message":...}` 본문을 가진 [status] 응답을 생성한다(SlackConnectionController 형식 일관). */
    private fun errorResponse(
        status: HttpStatus,
        error: SlackChannelMappingErrorCode,
    ): ResponseEntity<Map<String, String>> =
        ResponseEntity.status(status).body(mapOf("code" to error.code, "message" to error.defaultMessage))
}

/**
 * `SlackChannelMappingController` 예외 → HTTP 응답 코드값 (FR-SL-06 Task 7).
 *
 * [defaultMessage] 는 사용자 노출용 고정 일반 문구이며, 원본 예외 message·projectKey·channelId·teamId·
 * 매핑 id 등 어떤 가변/비밀 값도 보간하지 않는다(§1.1.2).
 *
 * @property code 프론트 계약 문자열(에러 코드 prefix `SLACK_` 고정).
 * @property defaultMessage 응답에 실리는 사용자 노출용 메시지.
 */
private enum class SlackChannelMappingErrorCode(val code: String, val defaultMessage: String) {
    PERMISSION_DENIED("SLACK_CHANNEL_MAPPING_FORBIDDEN", "이 프로젝트의 채널 매핑을 관리할 권한이 없습니다."),
    NOT_FOUND("SLACK_CHANNEL_MAPPING_NOT_FOUND", "채널 매핑을 찾을 수 없습니다."),
    CONFLICT("SLACK_CHANNEL_MAPPING_CONFLICT", "이미 동일한 채널 매핑이 존재합니다."),
    WORKSPACE_NOT_INSTALLED("WORKSPACE_NOT_INSTALLED", "Slack 워크스페이스가 설치되어 있지 않습니다."),
    INVALID_EVENT_TYPES("SLACK_CHANNEL_MAPPING_INVALID", "이벤트 유형 값이 올바르지 않습니다."),
    BAD_REQUEST("SLACK_CHANNEL_MAPPING_BAD_REQUEST", "요청 형식이 올바르지 않습니다."),
    UNAUTHENTICATED("SLACK_UNAUTHENTICATED", "인증이 필요합니다. 세션이 만료되었을 수 있습니다."),
    INTERNAL_ERROR("SLACK_CHANNEL_MAPPING_INTERNAL_ERROR", "서버 내부 오류가 발생했습니다."),
}
