// global_permission_grants 테이블 접근 JdbcTemplate 리포지토리 — 부여/회수/목록/판정 (FR-PM-10)

package com.atlas.bts.identity.permission

import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.sql.ResultSet
import java.util.UUID

/**
 * `global_permission_grants` 테이블 접근 리포지토리 (FR-PM-10 Task 4).
 *
 * "누가 어떤 전역 권한을 갖는가"를 부여([grant]) / 회수([revoke]) / 목록([list]) / 판정([hasGrant]) 한다.
 * ADR `docs/decisions/2026-07-17-global-permission-grants.md` 의 구현체다.
 *
 * ## 판정의 위치 — 여기가 전부가 아니다
 * [hasGrant] 는 **grant 축만** 본다. 전역 권한의 최종 판정식은 `grant 보유 OR isSystemAdmin` 이며
 * (ADR D-2), `OR isSystemAdmin` 항은 [IdentityAccessSystemPermissionResolver] 가 합성한다.
 * 이 리포지토리를 단독으로 권한 판정에 쓰면 SYSTEM_ADMIN 이 탈락한다.
 *
 * ## 보안 계약
 * - **판정 불가·미부여는 전부 `false`** — 권한 판정 경로의 기본값은 거부로 수렴한다(ADR D-2).
 * - **[grant] 는 중복을 삼키지 않는다** — `ON CONFLICT DO NOTHING` 을 쓰지 않아 UNIQUE 위반이
 *   `DuplicateKeyException` 으로 전파되고 서비스가 409 로 매핑한다. 관리자는 "이미 부여돼 있다"를
 *   알아야 한다(ADR D-1).
 * - **[revoke] 는 hard DELETE** 이며 0행이면 `false` 를 반환해 호출 측이 404 로 거부한다 —
 *   "지웠다고 믿었는데 대상이 없었다"를 조용히 성공으로 만들지 않는다(ADR D-5).
 * - **`grantee_id` 존재 검증은 이 계층의 책임이 아니다** — 다형 참조라 DB FK 가 없고(ADR D-4),
 *   서비스가 `granteeType` 에 따라 users/user_groups 를 조회해 404 로 거부한다. FK 생략의 대가다.
 *
 * ## 트랜잭션 경계 (DATA.md §6)
 * 클래스 레벨 `@Transactional(REQUIRED, READ_COMMITTED)` — 호출 측 트랜잭션에 참여하거나 새로 시작한다.
 * 읽기 전용 [list]/[hasGrant] 는 `readOnly = true` 로 오버라이드한다. `CalendarFeedTokenRepository` 선례.
 *
 * ## SQL 인젝션 방어
 * 모든 파라미터를 NamedParameterJdbcTemplate `:param` 바인딩(prepared statement)으로 처리하며
 * SQL 문자열 결합은 하지 않는다(DEVELOPMENT.md §1.3). [grant] 의 `permission` 은 REST 바디에서
 * 오는 사용자 입력이지만 바인딩 값이라 구문에 개입할 수 없고, V036 CHECK 가 값 자체도 제한한다.
 */
@Repository
@Transactional(propagation = Propagation.REQUIRED, isolation = Isolation.READ_COMMITTED)
class GlobalPermissionGrantRepository(
    private val jdbc: NamedParameterJdbcTemplate,
) {
    /**
     * 전역 권한을 사용자/그룹에게 부여하고 DB 가 채운 행을 반환한다.
     *
     * @param permission 전역 권한코드. V036 CHECK 가 허용하는 값만 저장된다(위반 시
     *   `DataIntegrityViolationException`). 서비스가 400 으로 선검증하는 것이 정상 경로이며,
     *   이 CHECK 는 그 검증을 빠져나온 값에 대한 2차 방어다(ADR D-1).
     * @param grantedBy 부여한 SYSTEM_ADMIN 의 `users.id`. **기본값을 두지 않는다** — 기본값은
     *   감사 흔적을 조용히 위조하는 통로가 되고, GROUP 부여는 멤버십 변경만으로 권한이 전파되므로
     *   부여 체인의 시작점을 놓치면 전체가 무기록이 된다(ADR D-5).
     * @return DB 에 반영된 [GlobalPermissionGrant] (id/createdAt 은 DB 기본값 기준)
     * @throws org.springframework.dao.DuplicateKeyException 같은 (permission, granteeType, granteeId)
     *   조합이 이미 있을 때. 호출 측이 409 로 매핑한다.
     */
    fun grant(
        permission: String,
        granteeType: GranteeType,
        granteeId: UUID,
        grantedBy: UUID,
    ): GlobalPermissionGrant =
        requireNotNull(
            jdbc.query(
                SQL_GRANT,
                mapOf(
                    "permission" to permission,
                    "granteeType" to granteeType.name,
                    "granteeId" to granteeId,
                    "grantedBy" to grantedBy,
                ),
                GrantRowMapper,
            ).firstOrNull(),
        ) { "INSERT ... RETURNING 이 행을 반환하지 않았습니다 (전역 권한 부여 실패)." }

    /**
     * 부여된 전역 권한을 회수한다 — hard DELETE (ADR D-5).
     *
     * 회수 이력은 남지 않는다(`revoked_at`/`revoked_by` 소프트 삭제는 후속 — ADR 잔여 위험 2).
     *
     * @param id 회수할 [GlobalPermissionGrant.id]
     * @return 실제로 삭제됐으면 true. 대상이 없으면 false — 호출 측이 404 로 거부한다.
     */
    fun revoke(id: UUID): Boolean = jdbc.update(SQL_REVOKE, mapOf("id" to id)) > 0

    /**
     * 부여된 전역 권한 전량을 반환한다 (관리 UI 목록).
     *
     * `grantee_id` 에 FK 가 없어 삭제된 사용자의 고아 행이 잔존할 수 있으며(ADR D-4/잔여 위험 1),
     * 이 목록이 그 유령 행을 **사후 발견하는 유일한 통로**다. 필터링하지 않는 것이 의도다.
     *
     * @return 부여 시각 오름차순. 없으면 빈 목록.
     */
    @Transactional(readOnly = true)
    fun list(): List<GlobalPermissionGrant> = jdbc.query(SQL_LIST, GrantRowMapper)

    /**
     * [actorId] 가 [permission] 을 grant 로 보유하는지 판정한다 — USER 직접 ∪ GROUP 경유.
     *
     * **SYSTEM_ADMIN 은 여기서 판정되지 않는다** (ADR D-2 — 상위 판정기가 `OR isSystemAdmin` 을 합성).
     *
     * GROUP 가지가 `group_memberships` 를 경유하므로 그룹 탈퇴/그룹 삭제(V015 CASCADE)가
     * 권한 회수로 전파된다. USER 가지에는 `users` JOIN 이 없어 그 전파가 없다(ADR D-4 표).
     *
     * @param permission 조회할 권한코드. 읽기 경로라 CHECK 대상이 아니므로 미등록 코드를 넘겨도
     *   예외 없이 false 로 수렴한다(fail-closed).
     * @return 보유하면 true. 미부여/판정 불가는 false.
     */
    @Transactional(readOnly = true)
    fun hasGrant(
        actorId: UUID,
        permission: String,
    ): Boolean =
        jdbc.queryForObject(
            SQL_HAS_GRANT,
            mapOf("permission" to permission, "actorId" to actorId),
            Boolean::class.java,
        ) ?: false

    // ── SQL 상수 ─────────────────────────────────────────────────────────────────

    private companion object {
        /**
         * 전역 권한 부여 INSERT — DB 기본값(id/created_at)까지 RETURNING 으로 회수.
         *
         * **`ON CONFLICT` 를 붙이지 않는 것이 계약이다** (ADR D-1). 붙이면 중복 부여가 0행을 반환해
         * [grant] 의 requireNotNull 이 터지고, 409 여야 할 응답이 500 으로 변질된다.
         */
        const val SQL_GRANT = """
            INSERT INTO global_permission_grants (permission, grantee_type, grantee_id, granted_by)
            VALUES (:permission, :granteeType, :granteeId, :grantedBy)
            RETURNING id, permission, grantee_type, grantee_id, granted_by, created_at
        """

        /** 부여 회수 — hard delete (ADR D-5). 영향 행 수로 존재 여부를 판별한다. */
        const val SQL_REVOKE = """
            DELETE FROM global_permission_grants
            WHERE id = :id
        """

        /** 부여 전량 조회 — 고아 행도 숨기지 않는다(ADR D-4 사후 발견 통로). */
        const val SQL_LIST = """
            SELECT id, permission, grantee_type, grantee_id, granted_by, created_at
            FROM global_permission_grants
            ORDER BY created_at, id
        """

        /**
         * 전역 권한 보유 판정 — USER 직접 부여 ∪ GROUP 경유 부여.
         *
         * `UNIQUE (permission, grantee_type, grantee_id)` 가 만든 btree 를 permission 선두로 탄다
         * (별도 인덱스 불요 — ADR D-1). GROUP 서브쿼리는 `ix_group_memberships_user`(V015)가 커버한다.
         * EXISTS 라 첫 매칭에서 단락 평가되고, 매칭이 없으면 false 로 수렴한다(fail-closed).
         */
        const val SQL_HAS_GRANT = """
            SELECT EXISTS (
                SELECT 1
                FROM global_permission_grants g
                WHERE g.permission = :permission
                  AND (
                        (g.grantee_type = 'USER'  AND g.grantee_id = :actorId)
                     OR (g.grantee_type = 'GROUP' AND g.grantee_id IN (
                            SELECT gm.group_id FROM group_memberships gm WHERE gm.user_id = :actorId
                        ))
                  )
            )
        """
    }
}

/** global_permission_grants 행을 [GlobalPermissionGrant] 도메인 모델로 변환하는 RowMapper. */
private object GrantRowMapper : RowMapper<GlobalPermissionGrant> {
    override fun mapRow(
        rs: ResultSet,
        rowNum: Int,
    ): GlobalPermissionGrant =
        GlobalPermissionGrant(
            id = rs.getObject("id", UUID::class.java),
            permission = rs.getString("permission"),
            granteeType = GranteeType.valueOf(rs.getString("grantee_type")),
            granteeId = rs.getObject("grantee_id", UUID::class.java),
            grantedBy = rs.getObject("granted_by", UUID::class.java),
            createdAt = rs.getTimestamp("created_at").toInstant(),
        )
}
