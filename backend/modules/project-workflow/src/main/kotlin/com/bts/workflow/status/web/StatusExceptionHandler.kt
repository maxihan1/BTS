// 전역 상태 카탈로그 예외 → HTTP 상태 매핑 — 원인마다 사용자가 할 수 있는 행동을 다르게 안내한다

package com.bts.workflow.status.web

import com.bts.workflow.status.domain.exception.StatusInUseException
import com.bts.workflow.status.domain.exception.StatusKeyConflictException
import com.bts.workflow.status.domain.exception.StatusNameConflictException
import com.bts.workflow.status.domain.exception.StatusNotFoundException
import com.bts.workflow.status.domain.exception.StatusProtectedException
import com.bts.workflow.web.ErrorBody
import com.bts.workflow.web.ErrorResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * [StatusController] 전용 예외 핸들러.
 *
 * ### 문구가 원인을 뭉뚱그리지 않는다
 * 409 하나에 원인이 셋이다 — key 중복 · 이름 중복 · 사용 중 · 시스템 예약.
 * 사용자가 **먼저 할 수 있는 행동**을 앞에 두고, 「관리자에게 문의」 같은 막다른 길로 단정하지 않는다
 * (MEMORY `permission-assert-before-existence-makes-403-lie` §처방 1·2).
 */
@RestControllerAdvice(assignableTypes = [StatusController::class])
class StatusExceptionHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 상태 부재 — 404. */
    @ExceptionHandler(StatusNotFoundException::class)
    fun handleNotFound(ex: StatusNotFoundException): ResponseEntity<ErrorResponse> {
        log.info("STATUS_404 id='{}'", ex.statusId)
        return error(HttpStatus.NOT_FOUND, "STATUS_NOT_FOUND", "상태를 찾을 수 없습니다.")
    }

    /** key 중복 — 409. 소프트 삭제된 key 는 여기 걸리지 않는다. */
    @ExceptionHandler(StatusKeyConflictException::class)
    fun handleKeyConflict(ex: StatusKeyConflictException): ResponseEntity<ErrorResponse> {
        log.info("STATUS_409_KEY key='{}'", ex.statusKey)
        return error(
            HttpStatus.CONFLICT,
            "STATUS_KEY_CONFLICT",
            "이미 쓰이고 있는 상태 키입니다. 다른 키를 입력해 주세요.",
        )
    }

    /** 이름 중복(대소문자 무시) — 409. */
    @ExceptionHandler(StatusNameConflictException::class)
    fun handleNameConflict(ex: StatusNameConflictException): ResponseEntity<ErrorResponse> {
        log.info("STATUS_409_NAME name='{}'", ex.statusName)
        return error(
            HttpStatus.CONFLICT,
            "STATUS_NAME_CONFLICT",
            "같은 이름의 상태가 이미 있습니다. 대소문자만 다른 이름도 같은 것으로 봅니다.",
        )
    }

    /** 워크플로우가 편성 중 — 409. 뗄 수 있으므로 그 행동을 안내한다. */
    @ExceptionHandler(StatusInUseException::class)
    fun handleInUse(ex: StatusInUseException): ResponseEntity<ErrorResponse> {
        log.info("STATUS_409_IN_USE key='{}' workflows={}", ex.statusKey, ex.workflowCount)
        return error(
            HttpStatus.CONFLICT,
            "STATUS_IN_USE",
            "워크플로우 ${ex.workflowCount}곳이 이 상태를 쓰고 있습니다. 그 워크플로우에서 먼저 뺀 뒤 삭제해 주세요.",
        )
    }

    /** 시스템 예약 — 409. 사용자가 할 수 있는 일이 없으므로 그렇게 말한다. */
    @ExceptionHandler(StatusProtectedException::class)
    fun handleProtected(ex: StatusProtectedException): ResponseEntity<ErrorResponse> {
        log.info("STATUS_409_PROTECTED key='{}'", ex.statusKey)
        return error(
            HttpStatus.CONFLICT,
            "STATUS_PROTECTED",
            "시스템이 예약한 상태라 지울 수 없습니다. 이름은 바꿀 수 있습니다.",
        )
    }

    private fun error(
        status: HttpStatus,
        code: String,
        message: String,
    ): ResponseEntity<ErrorResponse> = ResponseEntity.status(status).body(ErrorResponse(ErrorBody(code, message)))
}
