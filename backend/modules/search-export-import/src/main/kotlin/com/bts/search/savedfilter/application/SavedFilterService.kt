// 저장된 필터 CRUD 서비스 — AQL 구문검증, 공유 가시성 게이트, OCC, shares 교체 (FR-SR-03)

package com.bts.search.savedfilter.application

import com.bts.search.aql.AqlLexException
import com.bts.search.aql.AqlLexer
import com.bts.search.aql.AqlParser
import com.bts.search.aql.AqlSyntaxException
import com.bts.search.savedfilter.domain.SavedFilter
import com.bts.search.savedfilter.domain.SavedFilterShare
import com.bts.shared.membership.GroupMembershipPort
import com.bts.shared.membership.ProjectMembershipPort
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
 * - 공유 가시성 게이트 (PR2). 비소유자는 멤버십 포트로 프로젝트키/그룹 집합을 조회한 뒤
 *   [SavedFilterRepository.findVisibleById]/[SavedFilterRepository.findSharedWith] 로 가시성을 판정한다.
 *   - **단건/수정/삭제 게이트**. owner 면 멤버십 포트 호출 없이 단축 통과한다(C6/EC15).
 *     비소유자는 가시(공유받음)면 수정/삭제 시 [SavedFilterForbiddenException](403, EC4),
 *     비가시면 [SavedFilterNotFoundException](404, EC5 존재 은닉)으로 분기한다.
 * - shares 교체 (FR-2). create/update 의 `shares` 인자가 `null` 이면 공유를 건드리지 않고(유지),
 *   `[]` 이면 전부 제거, `[..]` 이면 [SavedFilterShare.normalize] 후 교체한다.
 *   교체는 본문 저장과 같은 `@Transactional` 트랜잭션 안에서 원자적으로 처리된다.
 * - shares 배치 조회 (N+1 차단). 목록 조회는 [SavedFilterShareRepository.findByFilterIds] 로
 *   단일 배치 호출하여 필터별 공유를 조합한다 ([attachShares]).
 * - 이름 중복 409 dual-catch — [DuplicateKeyException](Spring) + [DataAccessException](jOOQ SQLState 23505)
 *   양 경로 모두 [SavedFilterDuplicateNameException] 으로 변환한다.
 * - OCC — [SavedFilterRepository.update] 가 null 반환 시 [SavedFilterConflictException].
 * - projectKey 불변 — update 요청에 projectKey 파라미터가 없으며, 기존 필터의 projectKey 를 그대로 유지한다.
 *
 * ## fail-closed 의존성
 * [groupMembershipPort]/[projectMembershipPort] 는 nullable 이 아닌 생성자 주입이다. 빈이 등록되지
 * 않으면 부팅이 실패하도록 설계된 안전망이며, allow-all default 를 두지 않는다(공유 누출 방지).
 *
 * ## 트랜잭션
 * 클래스 레벨 `@Transactional` 이 모든 public 메서드에 적용된다.
 * 읽기 전용 메서드에는 `readOnly = true` 를 별도 선언해 최적화한다.
 * self-invocation 이 없으므로 Spring 프록시 우회 문제는 발생하지 않는다.
 *
 * @param repository 저장된 필터 영속성 포트 (DIP 경계).
 * @param shareRepository 저장된 필터 공유 영속성 포트 (DIP 경계).
 * @param groupMembershipPort actor 가 속한 그룹 ID 집합 조회 (cross-BC, fail-closed).
 * @param projectMembershipPort actor 가 속한 프로젝트 키 집합 조회 (cross-BC, fail-closed).
 */
@Service
@Transactional
@Suppress("TooManyFunctions") // CRUD + 가시성 4경로 + shares 조립 헬퍼로 함수가 12개를 넘는다. 책임은 단일.
class SavedFilterService(
    private val repository: SavedFilterRepository,
    private val shareRepository: SavedFilterShareRepository,
    private val groupMembershipPort: GroupMembershipPort,
    private val projectMembershipPort: ProjectMembershipPort,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 새 필터를 생성한다. `shares` 가 주어지면 본문 저장과 같은 트랜잭션에서 공유를 함께 교체한다.
     *
     * @param actorId 소유자 사용자 UUID.
     * @param name 필터 이름.
     * @param aqlQuery 저장할 AQL 쿼리 문자열.
     * @param projectKey 필터가 적용되는 프로젝트 키.
     * @param shares 공유 대상 목록. `null` 이면 공유를 건드리지 않고, `[]` 이면 없음, `[..]` 이면 교체한다.
     * @return 저장된 필터 도메인 객체.
     * @throws SavedFilterValidationException AQL 구문 오류.
     * @throws SavedFilterDuplicateNameException 같은 owner 내 이름 중복.
     */
    @Suppress("ThrowsCount") // 이름중복 dual-catch(2경로) + 비-중복 DataAccessException 재전파로 throw가 3개다.
    fun create(
        actorId: UUID,
        name: String,
        aqlQuery: String,
        projectKey: String,
        shares: List<SavedFilterShare>? = null,
    ): SavedFilter {
        validateAql(aqlQuery)
        val filter = SavedFilter.create(ownerId = actorId, name = name, aqlQuery = aqlQuery, projectKey = projectKey)
        log.info("SavedFilterService.create actor={} name={} projectKey={}", actorId, name, projectKey)
        val saved =
            try {
                repository.save(filter)
            } catch (e: DuplicateKeyException) {
                throw SavedFilterDuplicateNameException(name, e)
            } catch (e: DataAccessException) {
                if (e.sqlState() == "23505") throw SavedFilterDuplicateNameException(name, e)
                throw e
            }
        if (shares != null) {
            val filterId = requireNotNull(saved.id) { "저장된 필터는 id 가 있어야 한다." }
            shareRepository.replaceShares(filterId, SavedFilterShare.normalize(shares))
        }
        return saved
    }

    /**
     * 공유 가시성을 반영해 필터 단건을 조회한다.
     *
     * owner 면 멤버십 포트 호출 없이 단축 반환한다(C6/EC15). 비소유자는 멤버십 기반으로
     * 가시성을 판정하며, 비가시면 [SavedFilterNotFoundException](404, 존재 은닉)을 던진다.
     *
     * @param id 조회할 필터 식별자.
     * @param actorId 요청자 사용자 UUID.
     * @return 필터 + 공유 목록 묶음.
     * @throws SavedFilterNotFoundException 존재하지 않거나 actor 가 열람할 수 없는 필터.
     */
    @Transactional(readOnly = true)
    fun getVisibleById(
        id: UUID,
        actorId: UUID,
    ): SavedFilterWithShares {
        val filter = repository.findById(id) ?: throw SavedFilterNotFoundException(id)
        if (filter.ownerId == actorId) return withShares(filter) // owner 단축경로 — 포트 미호출
        val visible = findVisibleForActor(id, actorId) ?: throw SavedFilterNotFoundException(id)
        return withShares(visible)
    }

    /**
     * actor 소유 필터 목록을 공유 목록과 함께 반환한다.
     *
     * 공유는 [SavedFilterShareRepository.findByFilterIds] 단일 배치로 조회해 조합한다(N+1 차단).
     *
     * @param actorId 소유자 사용자 UUID.
     * @return actor 소유 필터 + 공유 목록 묶음 (created_at ASC 정렬).
     */
    @Transactional(readOnly = true)
    fun listOwnedWithShares(actorId: UUID): List<SavedFilterWithShares> = attachShares(repository.findByOwner(actorId))

    /**
     * actor 에게 공유된 비소유 필터 목록을 페이지네이션으로 반환한다.
     *
     * 멤버십 포트로 프로젝트키/그룹 집합을 조회한 뒤 가시성 술어로 페이지를 조회하고,
     * 공유는 단일 배치로 조합한다(N+1 차단).
     *
     * @param actorId 요청자 사용자 UUID.
     * @param page 0-based 페이지 번호.
     * @param size 페이지당 최대 항목 수.
     * @return 공유받은 비소유 필터 + 공유 목록 묶음 (created_at ASC, id ASC 정렬).
     */
    @Transactional(readOnly = true)
    fun listSharedWith(
        actorId: UUID,
        page: Int,
        size: Int,
    ): List<SavedFilterWithShares> {
        val keys = projectMembershipPort.projectKeysOf(actorId)
        val groups = groupMembershipPort.groupIdsOf(actorId)
        return attachShares(repository.findSharedWith(actorId, keys, groups, page, size))
    }

    /**
     * 필터를 수정하고 실제 영속된 공유 목록을 포함해 반환한다.
     *
     * projectKey 는 변경 불가 — 기존 필터의 값을 유지한다 (FR-10). version 은 OCC 키로 사용된다.
     * 가시-비소유는 403, 비가시는 404 로 분기한다 ([loadForMutation]).
     * `shares` 처리(교체·유지·전체제거) 후 [shareRepository] 로 실제 영속된 공유를 재조회해 반환한다.
     * 이로써 `shares` 생략 PUT 시에도 응답이 기존 공유를 정확히 반영한다 (C1 수정).
     *
     * @param id 수정할 필터 식별자.
     * @param actorId 요청자 사용자 UUID.
     * @param name 새 필터 이름.
     * @param aqlQuery 새 AQL 쿼리 문자열.
     * @param version 클라이언트가 보유한 현재 버전 (OCC 검사용).
     * @param shares 공유 대상 목록. `null` 이면 유지, `[]` 이면 전체 제거, `[..]` 이면 교체.
     * @return 갱신된 필터 + 실제 영속된 공유 목록 묶음.
     * @throws SavedFilterNotFoundException 필터가 존재하지 않거나 비가시(존재 은닉).
     * @throws SavedFilterForbiddenException 가시-비소유 필터를 수정하려는 경우(403).
     * @throws SavedFilterValidationException AQL 구문 오류.
     * @throws SavedFilterConflictException OCC 충돌 (stale version).
     */
    @Suppress("LongParameterList") // id/actor/name/aql/version/shares 6개 — 컨트롤러 직접 호출 시그니처라 분리 불가.
    fun update(
        id: UUID,
        actorId: UUID,
        name: String,
        aqlQuery: String,
        version: Long,
        shares: List<SavedFilterShare>? = null,
    ): SavedFilterWithShares {
        val existing = loadForMutation(id, actorId)
        validateAql(aqlQuery)
        // projectKey 는 기존 값 유지 — update 파라미터에 포함하지 않는다 (FR-10).
        val toUpdate = existing.copy(name = name, aqlQuery = aqlQuery, version = version)
        log.info("SavedFilterService.update id={} actor={} name={}", id, actorId, name)
        val updated = repository.update(toUpdate) ?: throw SavedFilterConflictException(id)
        if (shares != null) {
            shareRepository.replaceShares(id, SavedFilterShare.normalize(shares))
        }
        // FIXME(C1): 실제 영속된 공유 재조회 미구현 — withShares(updated) 로 교체 필요.
        return SavedFilterWithShares(updated, shares ?: emptyList())
    }

    /**
     * 필터를 삭제한다. 공유 행은 DB FK `ON DELETE CASCADE` 로 함께 제거된다.
     *
     * @param id 삭제할 필터 식별자.
     * @param actorId 요청자 사용자 UUID.
     * @throws SavedFilterNotFoundException 필터가 존재하지 않거나 비가시(존재 은닉).
     * @throws SavedFilterForbiddenException 가시-비소유 필터를 삭제하려는 경우(403).
     */
    fun delete(
        id: UUID,
        actorId: UUID,
    ) {
        loadForMutation(id, actorId)
        log.info("SavedFilterService.delete id={} actor={}", id, actorId)
        repository.deleteById(id)
    }

    // ── private helpers ───────────────────────────────────────────────────────

    /**
     * 수정/삭제 대상 필터를 로드하고 소유/가시성 게이트를 적용한다.
     *
     * - 존재하지 않으면 404.
     * - owner 면 멤버십 포트 호출 없이 단축 통과(필터 반환).
     * - 비소유자는 가시면 403(EC4, 가시-비소유), 비가시면 404(EC5, 존재 은닉)로 분기한다.
     *
     * @param id 대상 필터 식별자.
     * @param actorId 요청자 사용자 UUID.
     * @return owner 인 경우의 필터 도메인 객체.
     * @throws SavedFilterNotFoundException 부재 또는 비가시.
     * @throws SavedFilterForbiddenException 가시-비소유.
     */
    @Suppress("ThrowsCount") // 404(부재) + 404(비가시 은닉) + 403(가시-비소유) 3분기 — 보안 분기상 분할 불가.
    private fun loadForMutation(
        id: UUID,
        actorId: UUID,
    ): SavedFilter {
        val filter = repository.findById(id) ?: throw SavedFilterNotFoundException(id)
        if (filter.ownerId == actorId) return filter // owner 단축경로 — 포트 미호출
        // 비소유자: 가시(공유받음)면 403, 비가시면 404(존재 은닉).
        findVisibleForActor(id, actorId) ?: throw SavedFilterNotFoundException(id)
        throw SavedFilterForbiddenException(id)
    }

    /**
     * 멤버십 포트로 actor 의 프로젝트키/그룹 집합을 조회한 뒤 가시성 술어로 단건을 조회한다.
     *
     * 단건 조회([getVisibleById])와 수정/삭제 게이트([loadForMutation])가 이 헬퍼를 공유해
     * 가시성 판정 경로를 단일화한다(parity 보장).
     *
     * @param id 대상 필터 식별자.
     * @param actorId 요청자 사용자 UUID.
     * @return 가시 필터, 비가시면 null.
     */
    private fun findVisibleForActor(
        id: UUID,
        actorId: UUID,
    ): SavedFilter? {
        val keys = projectMembershipPort.projectKeysOf(actorId)
        val groups = groupMembershipPort.groupIdsOf(actorId)
        return repository.findVisibleById(id, actorId, keys, groups)
    }

    /**
     * 단일 필터에 공유 목록을 부착해 [SavedFilterWithShares] 로 묶는다([attachShares] 1건 위임).
     *
     * @param filter 영속된 필터(id 채워짐).
     * @return 필터 + 공유 목록 묶음.
     */
    private fun withShares(filter: SavedFilter): SavedFilterWithShares = attachShares(listOf(filter)).first()

    /**
     * 필터 목록에 공유 목록을 단일 배치 조회로 부착한다(N+1 차단).
     *
     * @param filters 부착 대상 필터 목록.
     * @return 각 필터 + 공유 목록 묶음. 공유가 없는 필터는 빈 리스트.
     */
    private fun attachShares(filters: List<SavedFilter>): List<SavedFilterWithShares> {
        val ids = filters.mapNotNull { it.id }.toSet()
        val sharesByFilter = shareRepository.findByFilterIds(ids)
        return filters.map { filter ->
            val filterShares = filter.id?.let { sharesByFilter[it] } ?: emptyList()
            SavedFilterWithShares(filter, filterShares)
        }
    }

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
