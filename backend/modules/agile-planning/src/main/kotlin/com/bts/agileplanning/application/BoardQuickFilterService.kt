// 퀵필터 검증 + CRUD 유스케이스 애플리케이션 서비스 — agile-planning BC (FR-UX-01)

package com.bts.agileplanning.application

import com.bts.agileplanning.domain.QuickFilter
import com.bts.agileplanning.repository.BoardQuickFilterRepository
import com.bts.agileplanning.web.BoardFilterQueryParser
import com.bts.shared.board.BoardCardFilter
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * 보드당 퀵필터 개수 상한(soft cap). [QuickFilterLimitExceededException] KDoc 참고 — 원자성 없음(TOCTOU 가능).
 */
private const val MAX_QUICK_FILTERS_PER_BOARD = 20

/**
 * 퀵필터 검증 + CRUD 유스케이스 애플리케이션 서비스.
 *
 * 권한 판정(BROWSE/CREATE)은 이 서비스의 책임이 아니다 — 컨트롤러(T6)가 [BoardApplicationService] 와
 * 동일한 패턴으로 보드 메타 조회(404) → 권한 판정(403) 순서를 수행한 뒤 이 서비스를 호출한다.
 *
 * ## query 검증 순서 (create/update 공통)
 * 1. [BoardFilterQueryParser.deserialize] 로 파싱 — UUID 형식 오류는 400 그대로 전파(EC4).
 * 2. 파싱 결과가 [BoardCardFilter.isEmpty] 이면 [QuickFilterEmptyQueryException] 400(EC1).
 * 3. [BoardQuickFilterRepository.countByBoardId] 가 [MAX_QUICK_FILTERS_PER_BOARD] 이상이면
 *    [QuickFilterLimitExceededException] 409(EC3, soft cap).
 * 4. [BoardFilterQueryParser.serialize] 로 정규화한 문자열을 저장한다.
 *
 * ## 이름 중복(EC2)
 * UNIQUE(board_id, name) 제약 위반은 Spring [DataIntegrityViolationException] 또는 jOOQ-native
 * `IntegrityConstraintViolationException` 두 경로 중 하나로 나타난다(memory:
 * jooq-exception-translator-409-dependency). 두 경로 모두 잡아 [QuickFilterNameConflictException] 로
 * 변환한다(선례 `SprintApplicationService.tryAssignIssue`).
 *
 * @param repository board_quick_filters jOOQ repository.
 */
@Service
@Transactional
class BoardQuickFilterService(
    private val repository: BoardQuickFilterRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 퀵필터를 생성한다.
     *
     * @param boardId 소속 보드 UUID.
     * @param name 칩에 표시될 이름.
     * @param query `BoardFilterQueryParser` 형식의 필터 조건 문자열. 저장 전 정규화된다.
     * @return 저장된 퀵필터.
     * @throws QuickFilterEmptyQueryException 400 — 빈 필터 조건(EC1).
     * @throws QuickFilterLimitExceededException 409 — 보드당 20건 상한 초과(EC3).
     * @throws QuickFilterNameConflictException 409 — 같은 보드 내 이름 중복(EC2).
     */
    @Transactional
    fun create(
        boardId: UUID,
        name: String,
        query: String,
    ): QuickFilter {
        log.debug("퀵필터 생성 시작 — boardId={}, name={}", boardId, name)
        val normalizedQuery = validateAndNormalize(boardId, query)
        val quickFilter =
            QuickFilter(id = UUID.randomUUID(), boardId = boardId, name = name, query = normalizedQuery)
        val saved = tryPersist { repository.insert(quickFilter) }
        log.debug("퀵필터 생성 완료 — id={}, boardId={}", saved.id, boardId)
        return saved
    }

    /**
     * 퀵필터의 name/query 를 갱신한다.
     *
     * @param boardId 소속 보드 UUID.
     * @param filterId 갱신 대상 퀵필터 UUID.
     * @param name 새 이름.
     * @param query 새 필터 조건 문자열. 저장 전 정규화된다.
     * @return 갱신된 퀵필터.
     * @throws QuickFilterNotFoundException 404 — 대상 미존재 또는 타 보드 소속(EC5).
     * @throws QuickFilterEmptyQueryException 400 — 빈 필터 조건(EC1).
     * @throws QuickFilterLimitExceededException 409 — 보드당 20건 상한 초과(EC3).
     * @throws QuickFilterNameConflictException 409 — 같은 보드 내 이름 중복(EC2).
     */
    @Transactional
    fun update(
        boardId: UUID,
        filterId: UUID,
        name: String,
        query: String,
    ): QuickFilter {
        repository.findByIdAndBoardId(filterId, boardId) ?: throw QuickFilterNotFoundException()

        val normalizedQuery = validateAndNormalize(boardId, query)
        val quickFilter = QuickFilter(id = filterId, boardId = boardId, name = name, query = normalizedQuery)
        val saved = tryPersist { repository.update(quickFilter) }
        log.debug("퀵필터 갱신 완료 — id={}, boardId={}", filterId, boardId)
        return saved
    }

    /**
     * 퀵필터를 삭제한다.
     *
     * @param boardId 소속 보드 UUID.
     * @param filterId 삭제 대상 퀵필터 UUID.
     * @throws QuickFilterNotFoundException 404 — 대상 미존재 또는 타 보드 소속(EC5).
     */
    @Transactional
    fun delete(
        boardId: UUID,
        filterId: UUID,
    ) {
        repository.findByIdAndBoardId(filterId, boardId) ?: throw QuickFilterNotFoundException()
        repository.delete(filterId, boardId)
        log.debug("퀵필터 삭제 완료 — id={}, boardId={}", filterId, boardId)
    }

    /**
     * 보드에 속한 퀵필터 목록을 created_at ASC 순으로 반환한다.
     *
     * @param boardId 조회할 보드 UUID.
     * @return 퀵필터 목록.
     */
    @Transactional(readOnly = true)
    fun list(boardId: UUID): List<QuickFilter> = repository.findByBoardId(boardId)

    /**
     * query 문자열을 파싱·검증하고 정규화된 문자열을 반환한다 (EC1/EC3/EC4 공통 검증).
     *
     * @param boardId soft cap(EC3) 조회 대상 보드 UUID.
     * @param query 원본 필터 조건 문자열.
     * @return [BoardFilterQueryParser.serialize] 로 정규화된 문자열.
     * @throws QuickFilterEmptyQueryException 400 — 빈 필터 조건(EC1).
     * @throws QuickFilterLimitExceededException 409 — 보드당 20건 상한 초과(EC3).
     */
    private fun validateAndNormalize(
        boardId: UUID,
        query: String,
    ): String {
        val filter = BoardFilterQueryParser.deserialize(query)
        if (filter.isEmpty()) {
            throw QuickFilterEmptyQueryException()
        }
        if (repository.countByBoardId(boardId) >= MAX_QUICK_FILTERS_PER_BOARD) {
            throw QuickFilterLimitExceededException()
        }
        return BoardFilterQueryParser.serialize(filter)
    }

    /**
     * [block] 을 실행하고 UNIQUE(board_id, name) 제약 위반을 [QuickFilterNameConflictException] 으로 변환한다.
     *
     * jOOQ 는 Spring PersistenceExceptionTranslator 가 개입하지 않을 경우
     * [DataIntegrityViolationException] 대신 `org.jooq.exception.IntegrityConstraintViolationException`
     * 을 직접 던진다. 두 케이스를 모두 처리한다(선례 `SprintApplicationService.tryAssignIssue`).
     *
     * SwallowedException — catch 목적이 409 도메인 예외 변환이므로 원 예외를 재던지지 않는 것이 의도된 설계.
     * ThrowsCount — UNIQUE 위반 두 경로(Spring/jOOQ)를 명시 catch 해 두 번 throw 하는 것이 의도된 설계.
     *
     * @param block 제약 위반 가능성이 있는 repository 호출.
     * @return [block] 결과.
     * @throws QuickFilterNameConflictException 409 — UNIQUE(board_id, name) 제약 위반(EC2).
     */
    @Suppress("SwallowedException", "ThrowsCount")
    private fun tryPersist(block: () -> QuickFilter): QuickFilter =
        try {
            block()
        } catch (ex: DataIntegrityViolationException) {
            // Spring PersistenceExceptionTranslator 가 개입한 경우
            log.warn("퀵필터 이름 UNIQUE 제약 위반(Spring)")
            throw QuickFilterNameConflictException()
        } catch (ex: org.jooq.exception.IntegrityConstraintViolationException) {
            // translator 미개입 시 jOOQ 가 직접 던지는 제약 위반
            log.warn("퀵필터 이름 UNIQUE 제약 위반(jOOQ)")
            throw QuickFilterNameConflictException()
        }
}
