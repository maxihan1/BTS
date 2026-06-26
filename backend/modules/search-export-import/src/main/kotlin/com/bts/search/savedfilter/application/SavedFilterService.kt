// 저장된 필터 CRUD 서비스 — AQL 구문검증, owner 게이트, OCC (FR-SR-03)

package com.bts.search.savedfilter.application

import com.bts.search.aql.AqlLexException
import com.bts.search.aql.AqlLexer
import com.bts.search.aql.AqlParser
import com.bts.search.aql.AqlSyntaxException
import com.bts.search.savedfilter.domain.SavedFilter
import org.jooq.exception.DataAccessException
import org.slf4j.LoggerFactory
import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * 저장된 AQL 필터 CRUD 서비스.
 *
 * ## 책임
 * - AQL 구문 검증 ([validateAql]) — create/update 시 렉서·파서 재사용. 오류 시 [SavedFilterValidationException].
 * - owner 게이트 — actorId != filter.ownerId 면 update/delete 시 [SavedFilterForbiddenException],
 *   get 시 [SavedFilterNotFoundException] (존재 은닉, 403 아님).
 * - 이름 중복 409 dual-catch — [DuplicateKeyException](Spring) + [DataAccessException](jOOQ SQLState 23505)
 *   양 경로 모두 [SavedFilterDuplicateNameException] 으로 변환한다.
 *   (운영: JooqAutoConfiguration 이 Spring 변환기 자동 등록 / 테스트 수동 DSLContext: native 23505 경로)
 * - OCC — [SavedFilterRepository.update] 가 null 반환 시 [SavedFilterConflictException].
 * - projectKey 불변 — update 요청에 projectKey 파라미터가 없으며, 기존 필터의 projectKey 를 그대로 유지한다.
 *
 * ## 트랜잭션
 * 클래스 레벨 `@Transactional` 이 모든 public 메서드에 적용된다.
 * 읽기 전용 메서드에는 `readOnly = true` 를 별도 선언해 최적화한다.
 * self-invocation 이 없으므로 Spring 프록시 우회 문제는 발생하지 않는다.
 *
 * @param repository 저장된 필터 영속성 포트 (DIP 경계).
 */
@Service
@Transactional
class SavedFilterService(
    private val repository: SavedFilterRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 새 필터를 생성한다.
     *
     * @param actorId 소유자 사용자 UUID.
     * @param name 필터 이름.
     * @param aqlQuery 저장할 AQL 쿼리 문자열.
     * @param projectKey 필터가 적용되는 프로젝트 키.
     * @return 저장된 필터 도메인 객체.
     * @throws SavedFilterValidationException AQL 구문 오류.
     * @throws SavedFilterDuplicateNameException 같은 owner 내 이름 중복.
     */
    fun create(
        actorId: UUID,
        name: String,
        aqlQuery: String,
        projectKey: String,
    ): SavedFilter {
        validateAql(aqlQuery)
        val filter = SavedFilter.create(ownerId = actorId, name = name, aqlQuery = aqlQuery, projectKey = projectKey)
        log.info("SavedFilterService.create actor={} name={} projectKey={}", actorId, name, projectKey)
        return try {
            repository.save(filter)
        } catch (e: DuplicateKeyException) {
            throw SavedFilterDuplicateNameException(name)
        } catch (e: DataAccessException) {
            if (e.sqlState() == "23505") throw SavedFilterDuplicateNameException(name)
            throw e
        }
    }

    /**
     * 필터를 ID와 actor 로 조회한다. actor 가 owner 가 아니면 404 (존재 은닉).
     *
     * @param id 조회할 필터 식별자.
     * @param actorId 요청자 사용자 UUID.
     * @return 소유한 필터 도메인 객체.
     * @throws SavedFilterNotFoundException 존재하지 않거나 타 owner 필터.
     */
    @Transactional(readOnly = true)
    fun getByIdForOwner(
        id: UUID,
        actorId: UUID,
    ): SavedFilter {
        val filter = repository.findById(id) ?: throw SavedFilterNotFoundException(id)
        if (filter.ownerId != actorId) throw SavedFilterNotFoundException(id)
        return filter
    }

    /**
     * actor 소유 필터 목록을 반환한다.
     *
     * @param actorId 소유자 사용자 UUID.
     * @return actor 소유 필터 목록 (created_at ASC 정렬).
     */
    @Transactional(readOnly = true)
    fun listByOwner(actorId: UUID): List<SavedFilter> = repository.findByOwner(actorId)

    /**
     * 필터를 수정한다.
     *
     * projectKey 는 변경 불가 — 기존 필터의 값을 유지한다 (FR-10).
     * version 은 OCC 키로 사용된다.
     *
     * @param id 수정할 필터 식별자.
     * @param actorId 요청자 사용자 UUID.
     * @param name 새 필터 이름.
     * @param aqlQuery 새 AQL 쿼리 문자열.
     * @param version 클라이언트가 보유한 현재 버전 (OCC 검사용).
     * @return 갱신된 필터 도메인 객체.
     * @throws SavedFilterNotFoundException 필터가 존재하지 않는 경우.
     * @throws SavedFilterForbiddenException actor 가 owner 가 아닌 경우.
     * @throws SavedFilterValidationException AQL 구문 오류.
     * @throws SavedFilterConflictException OCC 충돌 (stale version).
     */
    fun update(
        id: UUID,
        actorId: UUID,
        name: String,
        aqlQuery: String,
        version: Long,
    ): SavedFilter {
        val existing = repository.findById(id) ?: throw SavedFilterNotFoundException(id)
        if (existing.ownerId != actorId) throw SavedFilterForbiddenException(id)
        validateAql(aqlQuery)
        // projectKey 는 기존 값 유지 — update 파라미터에 포함하지 않는다 (FR-10).
        val toUpdate = existing.copy(name = name, aqlQuery = aqlQuery, version = version)
        log.info("SavedFilterService.update id={} actor={} name={}", id, actorId, name)
        return repository.update(toUpdate) ?: throw SavedFilterConflictException(id)
    }

    /**
     * 필터를 삭제한다.
     *
     * @param id 삭제할 필터 식별자.
     * @param actorId 요청자 사용자 UUID.
     * @throws SavedFilterNotFoundException 필터가 존재하지 않는 경우.
     * @throws SavedFilterForbiddenException actor 가 owner 가 아닌 경우.
     */
    fun delete(
        id: UUID,
        actorId: UUID,
    ) {
        val existing = repository.findById(id) ?: throw SavedFilterNotFoundException(id)
        if (existing.ownerId != actorId) throw SavedFilterForbiddenException(id)
        log.info("SavedFilterService.delete id={} actor={}", id, actorId)
        repository.deleteById(id)
    }

    // ── private helpers ───────────────────────────────────────────────────────

    /**
     * AQL 쿼리 문자열의 구문을 검증한다.
     *
     * [AqlLexer.tokenize] + [AqlParser.parse] 를 순서대로 호출하며,
     * [AqlLexException] 또는 [AqlSyntaxException] 발생 시 [SavedFilterValidationException] 으로 변환한다.
     * 원인 예외를 cause 로 보존하여 디버그 추적을 가능하게 한다.
     *
     * @param aqlQuery 검증할 AQL 쿼리 문자열.
     * @throws SavedFilterValidationException 구문 오류 시.
     */
    private fun validateAql(aqlQuery: String) {
        try {
            val tokens = AqlLexer(aqlQuery).tokenize()
            AqlParser(tokens).parse()
        } catch (e: AqlLexException) {
            throw SavedFilterValidationException("AQL 구문 오류: ${e.message}", e)
        } catch (e: AqlSyntaxException) {
            throw SavedFilterValidationException("AQL 구문 오류: ${e.message}", e)
        }
    }
}
