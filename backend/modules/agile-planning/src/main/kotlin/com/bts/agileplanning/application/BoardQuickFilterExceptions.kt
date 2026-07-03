// 퀵필터 애플리케이션 계층 예외 군 — agile-planning BC (FR-UX-01)

package com.bts.agileplanning.application

import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException

/**
 * 퀵필터를 찾을 수 없을 때 던지는 예외 (EC5).
 *
 * boardId 소속이 아니거나 존재하지 않는 filterId 로 update/delete 를 시도할 때 발생한다.
 * 내부 식별자(filterId, boardId)는 message 에 포함하지 않는다.
 */
class QuickFilterNotFoundException :
    ResponseStatusException(HttpStatus.NOT_FOUND, "퀵필터를 찾을 수 없습니다.")

/**
 * 빈 필터 조건으로 퀵필터를 저장하려 할 때 던지는 예외 (EC1).
 *
 * [com.bts.agileplanning.web.BoardFilterQueryParser.deserialize] 결과가
 * [com.bts.shared.board.BoardCardFilter.isEmpty] 이면 발생한다.
 */
class QuickFilterEmptyQueryException :
    ResponseStatusException(HttpStatus.BAD_REQUEST, "빈 필터 조건은 퀵필터로 저장할 수 없습니다.")

/**
 * 보드당 퀵필터 개수 상한을 초과할 때 던지는 예외 (EC3).
 *
 * [BoardQuickFilterService] 의 `MAX_QUICK_FILTERS_PER_BOARD`(soft cap) 참고 — `countByBoardId` 조회 후
 * 판단하므로 동시 요청 사이에 원자성이 없다(TOCTOU 가능, 리뷰 C8 수용). 정확한 정합 보장이 아니라
 * 사용자 실수를 막기 위한 안내성 상한이다.
 */
class QuickFilterLimitExceededException :
    ResponseStatusException(HttpStatus.CONFLICT, "보드당 퀵필터는 최대 20개까지 저장할 수 있습니다.")

/**
 * 같은 보드 내 퀵필터 이름이 중복될 때 던지는 예외 (EC2).
 *
 * UNIQUE(board_id, name) 제약 위반은 Spring [org.springframework.dao.DataIntegrityViolationException]
 * 또는 jOOQ-native `org.jooq.exception.IntegrityConstraintViolationException` 두 경로 중 하나로
 * 감지되며, [BoardQuickFilterService] 가 두 경로 모두 잡아 이 예외로 변환한다
 * (memory: jooq-exception-translator-409-dependency, 선례 `SprintApplicationService.tryAssignIssue`).
 */
class QuickFilterNameConflictException :
    ResponseStatusException(HttpStatus.CONFLICT, "같은 이름의 퀵필터가 이미 있습니다.")
