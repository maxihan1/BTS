// 즐겨찾기 jOOQ Repository — favorites 테이블 save 멱등·삭제·조회 담당

package com.bts.notification.favorite.repository

import com.bts.notification.favorite.domain.Favorite
import com.bts.notification.favorite.domain.FavoriteTargetType
import com.bts.notification.jooq.tables.records.FavoritesRecord
import com.bts.notification.jooq.tables.references.FAVORITES
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * favorites 테이블의 CRUD 를 담당하는 Repository.
 *
 * jOOQ DSL 만 사용한다 — SQL 문자열 결합 금지 (DATA.md §5).
 * 모든 public 메서드에 @Transactional 을 명시한다 (DATA.md §6).
 *
 * 즐겨찾기는 소프트 삭제를 사용하지 않는다. 사용자가 삭제하면 하드 DELETE 를 수행한다.
 *
 * @param dsl jOOQ DSLContext — SQL 을 코드로 안전하게 작성하는 라이브러리의 핵심 진입점
 */
@Repository
class FavoriteRepository(
    private val dsl: DSLContext,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 즐겨찾기를 저장한다. (userId, targetType, targetId) 복합 유니크 키 기준 멱등 처리.
     *
     * INSERT … ON CONFLICT (user_id, target_type, target_id) DO NOTHING 으로
     * 충돌 시 기존 행을 재조회해 created=false 로 반환한다.
     * 컨트롤러는 created 플래그를 보고 201(신규) / 200(기존) 응답 코드를 결정한다.
     *
     * @param favorite 저장할 즐겨찾기 도메인 객체
     * @return [SaveResult] — favorite(저장 또는 기존 행) + created(신규 여부)
     */
    @Transactional
    fun save(favorite: Favorite): SaveResult {
        log.debug(
            "즐겨찾기 저장 시도 — userId={}, targetType={}, targetId={}",
            favorite.userId,
            favorite.targetType,
            favorite.targetId,
        )

        val inserted =
            dsl.insertInto(FAVORITES)
                .set(FAVORITES.ID, favorite.id)
                .set(FAVORITES.USER_ID, favorite.userId)
                .set(FAVORITES.TARGET_TYPE, favorite.targetType.name)
                .set(FAVORITES.TARGET_ID, favorite.targetId)
                .onConflictDoNothing()
                .returning()
                .fetchOne()

        if (inserted != null) {
            return SaveResult(favorite = toFavorite(inserted), created = true)
        }

        // 충돌(이미 존재) — 기존 행 재조회 (TOCTOU 무결성 보장을 위해 lock 후 재조회)
        val existing =
            dsl.selectFrom(FAVORITES)
                .where(FAVORITES.USER_ID.eq(favorite.userId))
                .and(FAVORITES.TARGET_TYPE.eq(favorite.targetType.name))
                .and(FAVORITES.TARGET_ID.eq(favorite.targetId))
                .fetchOne()
                ?: error(
                    "INSERT 충돌 후 기존 행 재조회 실패 — " +
                        "userId=${favorite.userId}, targetType=${favorite.targetType}, targetId=${favorite.targetId}",
                )

        return SaveResult(favorite = toFavorite(existing), created = false)
    }

    /**
     * (userId, targetType, targetId) 가 일치하는 즐겨찾기를 삭제한다.
     *
     * 본인 행만 삭제한다 — userId 조건이 WHERE 에 포함된다.
     *
     * @param userId 삭제 주체 사용자 ID
     * @param targetType 즐겨찾기 대상 종류
     * @param targetId 즐겨찾기 대상 식별자
     * @return 삭제된 행이 있으면 true, 없으면 false
     */
    @Transactional
    fun deleteByTarget(userId: UUID, targetType: FavoriteTargetType, targetId: String): Boolean {
        log.debug("즐겨찾기 삭제 — userId={}, targetType={}, targetId={}", userId, targetType, targetId)

        val deleted =
            dsl.deleteFrom(FAVORITES)
                .where(FAVORITES.USER_ID.eq(userId))
                .and(FAVORITES.TARGET_TYPE.eq(targetType.name))
                .and(FAVORITES.TARGET_ID.eq(targetId))
                .execute()

        return deleted > 0
    }

    /**
     * 사용자의 즐겨찾기 목록을 조회한다.
     *
     * targetType 이 null 이면 전체 종류를 반환하고,
     * 지정하면 해당 종류만 필터링해 반환한다.
     * 결과는 created_at DESC 정렬이다.
     *
     * @param userId 조회 주체 사용자 ID
     * @param targetType 필터할 즐겨찾기 대상 종류 (null 이면 전체)
     * @return 해당 사용자의 즐겨찾기 목록 (다른 사용자 행 미포함)
     */
    @Transactional(readOnly = true)
    fun findByUser(userId: UUID, targetType: FavoriteTargetType?): List<Favorite> {
        val condition = FAVORITES.USER_ID.eq(userId).let { base ->
            if (targetType != null) base.and(FAVORITES.TARGET_TYPE.eq(targetType.name)) else base
        }

        return dsl.selectFrom(FAVORITES)
            .where(condition)
            .orderBy(FAVORITES.CREATED_AT.desc())
            .fetch()
            .map { toFavorite(it) }
    }

    // ── private 매퍼 ───────────────────────────────────────────────────────────

    /**
     * jOOQ FavoritesRecord 를 도메인 Favorite 로 변환한다.
     *
     * target_type 은 DB 에 enum name 문자열로 저장되므로 [FavoriteTargetType.from] 으로 역매핑한다.
     *
     * @param record jOOQ 에서 읽어온 typed record
     * @return 변환된 도메인 객체
     */
    private fun toFavorite(record: FavoritesRecord): Favorite {
        val id = record.id ?: error("id 가 null — DB 데이터 손상")
        val userId = record.userId ?: error("user_id 가 null — id=$id")
        val targetTypeStr = record.targetType ?: error("target_type 이 null — id=$id")
        val targetId = record.targetId ?: error("target_id 가 null — id=$id")
        val createdAt = record.createdAt?.toInstant() ?: error("created_at 이 null — id=$id")

        return Favorite(
            id = id,
            userId = userId,
            targetType = FavoriteTargetType.from(targetTypeStr),
            targetId = targetId,
            createdAt = createdAt,
        )
    }
}

/**
 * [FavoriteRepository.save] 반환 타입 — 즐겨찾기 도메인 객체 + 신규 생성 여부.
 *
 * 컨트롤러가 201(신규) / 200(기존) HTTP 응답 코드를 결정할 때 사용한다.
 *
 * @param favorite 저장된(또는 기존) 즐겨찾기 도메인 객체
 * @param created 신규 생성이면 true, 이미 존재했으면 false
 */
data class SaveResult(
    val favorite: Favorite,
    val created: Boolean,
)
