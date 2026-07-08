// user_keymap 테이블 접근 인터페이스 구현체 (FR-PF-03)

package com.atlas.bts.identity.keymap

import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.sql.ResultSet
import java.util.UUID

/**
 * [UserKeymapRepository] JDBC 구현체 (FR-PF-03).
 *
 * **트랜잭션 경계 (DATA.md §6)**:
 * 클래스 레벨 @Transactional(REQUIRED) — 호출 측 트랜잭션에 참여하거나 새로 시작.
 * 읽기 전용 메서드는 @Transactional(readOnly = true) 로 오버라이드.
 *
 * **replace-all 설계**:
 * [replaceOverrides] 는 DELETE 후 INSERT 를 같은 트랜잭션 안에서 수행해 원자적으로 교체한다.
 * user_keymap 은 override 만 저장하므로 부분 UPSERT 보다 전체 교체가 시맨틱에 더 맞는다 — 사용자가
 * 특정 action 을 기본값으로 되돌리는 것(override 목록에서 제거)도 이 메서드 하나로 표현된다.
 * override 하드 삭제는 개인 설정 토글이라 정당하다(DATA.md §3, favorites/saved_filters 선례).
 *
 * SQL 인젝션 방어: 모든 파라미터를 NamedParameterJdbcTemplate 에 바인딩 (DEVELOPMENT.md §1.3).
 */
@Repository
@Transactional(propagation = Propagation.REQUIRED, isolation = Isolation.READ_COMMITTED)
class JdbcUserKeymapRepository(
    private val jdbc: NamedParameterJdbcTemplate,
) : UserKeymapRepository {
    @Transactional(readOnly = true)
    override fun findByUserId(userId: UUID): List<KeymapBinding> =
        jdbc.query(SQL_FIND_BY_USER_ID, mapOf("userId" to userId), KeymapBindingRowMapper)

    override fun replaceOverrides(
        userId: UUID,
        overrides: List<KeymapBinding>,
    ) {
        jdbc.update(SQL_DELETE_BY_USER_ID, mapOf("userId" to userId))
        if (overrides.isEmpty()) return
        val batchParams =
            overrides
                .map { mapOf("userId" to userId, "action" to it.action, "keyCombo" to it.keyCombo) }
                .toTypedArray()
        jdbc.batchUpdate(SQL_INSERT, batchParams)
    }

    // ── SQL 상수 ─────────────────────────────────────────────────────────────

    private companion object {
        const val SQL_FIND_BY_USER_ID = """
            SELECT action, key_combo
            FROM user_keymap
            WHERE user_id = :userId
            ORDER BY action
        """

        const val SQL_DELETE_BY_USER_ID = "DELETE FROM user_keymap WHERE user_id = :userId"

        const val SQL_INSERT = """
            INSERT INTO user_keymap (user_id, action, key_combo)
            VALUES (:userId, :action, :keyCombo)
        """
    }
}

/** user_keymap RowMapper — ResultSet → KeymapBinding 변환 */
private object KeymapBindingRowMapper : RowMapper<KeymapBinding> {
    override fun mapRow(
        rs: ResultSet,
        rowNum: Int,
    ): KeymapBinding =
        KeymapBinding(
            action = rs.getString("action"),
            keyCombo = rs.getString("key_combo"),
        )
}
