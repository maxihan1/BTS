// 스프린트 애플리케이션 계층 예외 정의 — agile-planning BC (FR-BL-02)

package com.bts.agileplanning.application

import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException

/**
 * 스프린트를 찾을 수 없을 때 던지는 예외.
 *
 * soft-deleted 스프린트 또는 존재하지 않는 스프린트에 접근할 때 발생한다.
 * 내부 식별자(sprintId 등)는 message 에 포함하지 않는다.
 */
class SprintNotFoundException : ResponseStatusException(HttpStatus.NOT_FOUND, "스프린트를 찾을 수 없습니다.")
