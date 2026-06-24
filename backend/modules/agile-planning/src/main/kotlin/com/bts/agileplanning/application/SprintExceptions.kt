// 스프린트 애플리케이션 계층 예외 군 — agile-planning BC (FR-BL-02)

package com.bts.agileplanning.application

import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException

/**
 * 스프린트를 찾을 수 없을 때 던지는 예외.
 *
 * soft-deleted 스프린트 또는 존재하지 않는 스프린트에 접근할 때 발생한다.
 * OCC(낙관적 잠금) 충돌로 updateMeta/updateStatus 가 null 을 반환할 때도 사용한다.
 * 내부 식별자(sprintId 등)는 message 에 포함하지 않는다.
 */
class SprintNotFoundException : ResponseStatusException(HttpStatus.NOT_FOUND, "스프린트를 찾을 수 없습니다.")

/**
 * COMPLETED 스프린트에 이슈를 할당하려 할 때 던지는 예외 (E5).
 *
 * repo.assignIssue 가 COMPLETED 가드로 0 행을 반환했을 때 서비스가 던진다.
 * 내부 sprintId 나 issueKey 는 message 에 포함하지 않는다.
 */
class SprintCompletedAssignException :
    ResponseStatusException(HttpStatus.CONFLICT, "완료된 스프린트에는 이슈를 할당하거나 제거할 수 없습니다.")
