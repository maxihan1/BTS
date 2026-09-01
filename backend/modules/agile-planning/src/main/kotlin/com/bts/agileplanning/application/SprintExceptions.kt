// 스프린트 애플리케이션 계층 예외 군 — agile-planning BC (FR-BL-02)

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

/**
 * COMPLETED 스프린트에 이슈를 할당하려 할 때 던지는 예외 (E5).
 *
 * repo.assignIssue 가 COMPLETED 가드로 0 행을 반환했을 때 서비스가 던진다.
 * 내부 sprintId 나 issueKey 는 message 에 포함하지 않는다.
 */
class SprintCompletedAssignException :
    ResponseStatusException(HttpStatus.CONFLICT, "완료된 스프린트에는 이슈를 할당할 수 없습니다.")

/**
 * 동시 할당 요청으로 UNIQUE(issue_key) 제약이 위반될 때 던지는 예외 (409).
 *
 * assignIssue 의 INSERT 단계에서 DataIntegrityViolationException 또는
 * jOOQ IntegrityConstraintViolationException 이 발생하면 서비스가 이 예외로 변환한다.
 * 내부 식별자(sprintId, issueKey)는 message 에 포함하지 않는다.
 */
class SprintIssueConflictException :
    ResponseStatusException(HttpStatus.CONFLICT, "이슈 할당 중 충돌이 발생했습니다. 다시 시도해 주세요.")

/**
 * OCC(낙관적 잠금) 버전 충돌 시 던지는 예외 (409).
 *
 * updateMeta / updateStatus 가 null 을 반환했을 때 스프린트가 존재하면(재조회 성공)
 * 버전 충돌로 판단해 서비스가 던진다.
 * 내부 식별자(sprintId, version)는 message 에 포함하지 않는다.
 */
class SprintVersionConflictException :
    ResponseStatusException(HttpStatus.CONFLICT, "다른 변경과 충돌이 발생했습니다. 최신 버전을 다시 조회한 뒤 재시도해 주세요.")

/**
 * 보드에 이미 ACTIVE 스프린트가 있는데 또 시작하려 할 때 던지는 예외 (409 · FR-BD-04).
 *
 * Jira Cloud 는 *"If you want to have more than one active sprint at a time, you'll need to enable
 * parallel sprints"* 로 **보드당 1개**를 기본으로 못박는다(2026-09-01 조회 · Cloud).
 * 이 예외가 `agile-planning.md §3.2` 의 Deviation(PR #182) ⑤ 「동시 ACTIVE 다중 허용」을 뒤집는다.
 *
 * **기존 다중 활성 행은 깨지 않는다** — 가드는 [SprintApplicationService.start] 시점에만 걸고
 * V506 의 `idx_sprints_board_active` 도 UNIQUE 가 아니다.
 *
 * ★ 전용 [com.bts.agileplanning.web.SprintExceptionHandler] 핸들러가 반드시 필요하다. 이 타입이
 * [ResponseStatusException] 을 상속하므로 핸들러가 없으면 상태 전파 핸들러가 잡아 errorCode 를
 * `AGILE_CONFLICT` 로 덮어쓴다 — 형제 409 3형제가 실제로 그렇게 뭉뚱그려져 있다.
 *
 * 내부 식별자(sprintId, boardId)는 message 에 포함하지 않는다.
 */
class SprintAlreadyActiveException :
    ResponseStatusException(HttpStatus.CONFLICT, "이 보드에는 이미 시작된 스프린트가 있습니다. 먼저 완료해 주세요.")
