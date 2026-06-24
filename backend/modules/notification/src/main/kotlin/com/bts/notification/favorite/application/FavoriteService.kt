// 즐겨찾기 애플리케이션 서비스 — 타입 파싱·도메인 불변식 위임·트랜잭션 경계 담당

package com.bts.notification.favorite.application

import com.bts.notification.favorite.domain.Favorite
import com.bts.notification.favorite.domain.FavoriteTargetType
import com.bts.notification.favorite.repository.FavoriteRepository
import com.bts.notification.favorite.repository.SaveResult
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * 즐겨찾기 CRUD 를 담당하는 애플리케이션 서비스.
 *
 * 형식 검증 정책.
 * - targetTypeRaw 파싱은 [FavoriteTargetType.from] 에 위임 — 무효값이면 [com.bts.notification.favorite.domain.FavoriteDomainException] 발생.
 * - targetId 불변식 검증은 [Favorite.create] 에 위임 — 빈 문자열·255자 초과 시 예외 발생.
 * - notification 모듈은 Bean Validation provider 가 없으므로 @Valid 어노테이션을 사용하지 않는다.
 *
 * 트랜잭션 정책 (DATA.md §6).
 * - 쓰기 메서드([addFavorite], [removeFavorite]): @Transactional (기본값 — REQUIRED)
 * - 읽기 메서드([listFavorites]): @Transactional(readOnly = true)
 *
 * @param repository favorites 테이블 jOOQ 저장소
 */
@Service
class FavoriteService(
    private val repository: FavoriteRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 즐겨찾기를 추가한다.
     *
     * 동일한 (userId, targetType, targetId) 가 이미 존재하면 [SaveResult.created] = false 로 반환한다 (멱등).
     *
     * @param actorId 즐겨찾기를 등록하는 사용자 ID
     * @param targetTypeRaw wire 문자열 형식의 대상 타입 (ISSUE / FILTER / DASHBOARD / PROJECT)
     * @param targetId 즐겨찾기 대상 식별자 (1~255자)
     * @return [SaveResult] — 저장된 즐겨찾기 + 신규 생성 여부
     * @throws com.bts.notification.favorite.domain.FavoriteDomainException targetTypeRaw 가 무효하거나 targetId 가 빈 문자열·초과 길이인 경우
     */
    @Transactional
    fun addFavorite(actorId: UUID, targetTypeRaw: String, targetId: String): SaveResult {
        val type = FavoriteTargetType.from(targetTypeRaw)
        val favorite = Favorite.create(actorId, type, targetId)
        val result = repository.save(favorite)
        log.info(
            "즐겨찾기 추가 — userId={}, targetType={}, targetId={}, created={}",
            actorId, type, targetId, result.created,
        )
        return result
    }

    /**
     * 즐겨찾기를 삭제한다.
     *
     * 대상이 존재하지 않아도 예외 없이 정상 완료된다 (멱등).
     *
     * @param actorId 삭제 주체 사용자 ID
     * @param targetTypeRaw wire 문자열 형식의 대상 타입
     * @param targetId 즐겨찾기 대상 식별자
     * @throws com.bts.notification.favorite.domain.FavoriteDomainException targetTypeRaw 가 무효한 경우
     */
    @Transactional
    fun removeFavorite(actorId: UUID, targetTypeRaw: String, targetId: String) {
        val type = FavoriteTargetType.from(targetTypeRaw)
        repository.deleteByTarget(actorId, type, targetId)
        log.info("즐겨찾기 삭제 요청 — userId={}, targetType={}, targetId={}", actorId, type, targetId)
    }

    /**
     * 사용자의 즐겨찾기 목록을 조회한다.
     *
     * @param actorId 조회 주체 사용자 ID
     * @param targetTypeRaw 필터할 대상 타입 wire 문자열 (null 이면 전체 반환)
     * @return 즐겨찾기 목록 (created_at DESC 정렬)
     * @throws com.bts.notification.favorite.domain.FavoriteDomainException targetTypeRaw 가 지정됐지만 무효한 경우
     */
    @Transactional(readOnly = true)
    fun listFavorites(actorId: UUID, targetTypeRaw: String?): List<Favorite> {
        val type = targetTypeRaw?.let { FavoriteTargetType.from(it) }
        return repository.findByUser(actorId, type)
    }
}
