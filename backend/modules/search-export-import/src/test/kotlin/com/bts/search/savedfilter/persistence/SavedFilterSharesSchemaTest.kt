// V601 saved_filter_shares 검증 — CHECK·UNIQUE NULLS NOT DISTINCT·FK CASCADE (FR-SR-03 PR2)

package com.bts.search.savedfilter.persistence

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jooq.exception.DataAccessException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Flyway V600~ 마이그레이션 체인 적용 후 V601 `saved_filter_shares` 테이블을 검증한다.
 *
 * Testcontainers (테스트용 DB 를 도커로 자동 실행하는 라이브러리) PG16-alpine 위에서
 * `SearchPersistenceTestBase` 의 singleton 컨테이너 + Flyway 마이그레이션을 재사용한다.
 *
 * 검증 범위 (FR-SR-03 PR2 plan Task 1 / ADR D2).
 * - saved_filter_shares 테이블 존재 + created_at TIMESTAMPTZ
 * - (a) share_type CHECK — PROJECT/GROUP/AUTHENTICATED 만 허용, 그 외 값 위반
 * - (b) target CHECK — (share_type='AUTHENTICATED') = (target_id IS NULL).
 *       AUTHENTICATED 만 target NULL, 그 외 타입은 target 필수. 위반 4경로 검증.
 * - (c) UNIQUE(filter_id, share_type, target_id) NULLS NOT DISTINCT —
 *       AUTHENTICATED(target NULL) 중복도 위반(PG 기본 UNIQUE 는 NULL 을 서로 다르게 봄).
 * - (d) FK filter_id → saved_filters(id) ON DELETE CASCADE — 부모 삭제 시 자식 자동 삭제.
 *
 * 검증은 information_schema 조회와 실제 INSERT 위반 시도(제약명 단언)로 한다.
 * 테스트 DSLContext 는 Spring 없이 수동 구성되므로 제약 위반은 jOOQ-native DataAccessException 으로 전파된다.
 *
 * 참조. FR-SR-03 PR2 plan Task 1 / ADR `2026-06-26-fr-sr-03-saved-filters.md` D2 /
 * DATA.md §4 TIMESTAMPTZ·§4.1 V번호 범위·§7 FK 인덱스 / 메모리 pg-null-distinct-on-conflict-idempotency.
 */
class SavedFilterSharesSchemaTest : SearchPersistenceTestBase() {
    @AfterEach
    fun clean() {
        // saved_filters 삭제 → FK CASCADE 로 saved_filter_shares 자식도 정리.
        dsl.execute("DELETE FROM saved_filters")
    }

    // ── 헬퍼 ──────────────────────────────────────────────────────────────────

    /** 부모 saved_filters 한 행 INSERT — 생성된 id 반환. owner/name 은 충돌 회피용 랜덤. */
    private fun insertSavedFilter(): UUID {
        val id = UUID.randomUUID()
        dsl.execute(
            "INSERT INTO saved_filters (id, owner_id, name, aql_query, project_key) VALUES (?, ?, ?, ?, ?)",
            id,
            UUID.randomUUID(),
            "필터-${UUID.randomUUID()}",
            "status = OPEN",
            "ATLAS",
        )
        return id
    }

    /** saved_filter_shares 한 행 INSERT — 제약 위반 시 DataAccessException 전파. */
    private fun insertShare(
        filterId: UUID,
        shareType: String,
        targetId: String?,
    ) {
        dsl.execute(
            "INSERT INTO saved_filter_shares (filter_id, share_type, target_id) VALUES (?, ?, ?)",
            filterId,
            shareType,
            targetId,
        )
    }

    private fun tableExists(name: String): Boolean =
        dsl.fetchOne(
            "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'public' AND table_name = ?",
            name,
        )!!.get(0, Long::class.java) > 0

    private fun columnDataType(
        table: String,
        column: String,
    ): String? =
        dsl.fetchOne(
            "SELECT data_type FROM information_schema.columns" +
                " WHERE table_schema = 'public' AND table_name = ? AND column_name = ?",
            table,
            column,
        )?.get(0, String::class.java)

    private fun shareCount(filterId: UUID): Long =
        dsl.fetchOne(
            "SELECT COUNT(*) FROM saved_filter_shares WHERE filter_id = ?",
            filterId,
        )!!.get(0, Long::class.java)

    // ── 테이블/컬럼 존재 ───────────────────────────────────────────────────────

    @Test
    fun `V601 saved_filter_shares 테이블 존재`() {
        assertThat(tableExists("saved_filter_shares")).isTrue()
    }

    @Test
    fun `V601 created_at 은 timestamptz`() {
        assertThat(columnDataType("saved_filter_shares", "created_at"))
            .isEqualTo("timestamp with time zone")
    }

    // ── (a) share_type CHECK (PROJECT/GROUP/AUTHENTICATED) ─────────────────────

    @Test
    fun `V601 share_type 허용값 PROJECT GROUP AUTHENTICATED 는 INSERT 가능`() {
        val filterId = insertSavedFilter()
        // PROJECT/GROUP 은 target 필수, AUTHENTICATED 는 target NULL — 모두 CHECK 통과해야 한다.
        insertShare(filterId, "PROJECT", "ATLAS")
        insertShare(filterId, "GROUP", "group-1")
        insertShare(filterId, "AUTHENTICATED", null)
        assertThat(shareCount(filterId)).isEqualTo(3L)
    }

    @Test
    fun `V601 share_type 허용 외 값은 CHECK 위반`() {
        val filterId = insertSavedFilter()
        assertThatThrownBy { insertShare(filterId, "INVALID", "x") }
            .isInstanceOf(DataAccessException::class.java)
            .hasMessageContaining("share_type_check")
    }

    // ── (b) target CHECK ((share_type='AUTHENTICATED') = (target_id IS NULL)) ──

    @Test
    fun `V601 AUTHENTICATED 는 target_id NULL 이어야 한다`() {
        val filterId = insertSavedFilter()
        // AUTHENTICATED 인데 target_id 가 NULL 이 아니면 위반.
        assertThatThrownBy { insertShare(filterId, "AUTHENTICATED", "should-be-null") }
            .isInstanceOf(DataAccessException::class.java)
            .hasMessageContaining("ck_saved_filter_shares_target")
    }

    @Test
    fun `V601 PROJECT 는 target_id 가 NULL 이면 위반`() {
        val filterId = insertSavedFilter()
        // AUTHENTICATED 가 아닌데 target_id 가 NULL 이면 위반.
        assertThatThrownBy { insertShare(filterId, "PROJECT", null) }
            .isInstanceOf(DataAccessException::class.java)
            .hasMessageContaining("ck_saved_filter_shares_target")
    }

    @Test
    fun `V601 GROUP 은 target_id 가 NULL 이면 위반`() {
        val filterId = insertSavedFilter()
        assertThatThrownBy { insertShare(filterId, "GROUP", null) }
            .isInstanceOf(DataAccessException::class.java)
            .hasMessageContaining("ck_saved_filter_shares_target")
    }

    // ── (c) UNIQUE(filter_id, share_type, target_id) NULLS NOT DISTINCT ────────

    @Test
    fun `V601 같은 filter_id share_type target_id 조합 중복은 UNIQUE 위반`() {
        val filterId = insertSavedFilter()
        insertShare(filterId, "PROJECT", "ATLAS")
        assertThatThrownBy { insertShare(filterId, "PROJECT", "ATLAS") }
            .isInstanceOf(DataAccessException::class.java)
            .hasMessageContaining("uq_saved_filter_shares")
    }

    @Test
    fun `V601 AUTHENTICATED target NULL 중복도 UNIQUE 위반 (NULLS NOT DISTINCT)`() {
        val filterId = insertSavedFilter()
        insertShare(filterId, "AUTHENTICATED", null)
        // PG 기본 UNIQUE 는 NULL 을 서로 다르게 봐 중복 허용. NULLS NOT DISTINCT 라야 위반.
        assertThatThrownBy { insertShare(filterId, "AUTHENTICATED", null) }
            .isInstanceOf(DataAccessException::class.java)
            .hasMessageContaining("uq_saved_filter_shares")
    }

    @Test
    fun `V601 같은 share_type 이라도 target_id 가 다르면 허용`() {
        val filterId = insertSavedFilter()
        insertShare(filterId, "PROJECT", "ATLAS")
        insertShare(filterId, "PROJECT", "OTHER")
        assertThat(shareCount(filterId)).isEqualTo(2L)
    }

    // ── (d) FK filter_id → saved_filters(id) ON DELETE CASCADE ────────────────

    @Test
    fun `V601 존재하지 않는 filter_id 는 FK 위반`() {
        assertThatThrownBy { insertShare(UUID.randomUUID(), "PROJECT", "ATLAS") }
            .isInstanceOf(DataAccessException::class.java)
            .hasMessageContaining("saved_filter_shares_filter_id_fkey")
    }

    @Test
    fun `V601 부모 saved_filters 삭제 시 자식 shares 도 CASCADE 삭제`() {
        val filterId = insertSavedFilter()
        insertShare(filterId, "PROJECT", "ATLAS")
        insertShare(filterId, "AUTHENTICATED", null)
        assertThat(shareCount(filterId)).isEqualTo(2L)

        dsl.execute("DELETE FROM saved_filters WHERE id = ?", filterId)

        assertThat(shareCount(filterId)).isZero()
    }
}
