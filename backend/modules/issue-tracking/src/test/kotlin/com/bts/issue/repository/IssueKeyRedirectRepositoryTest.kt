// IssueKeyRedirectRepository 통합 테스트 — append-only insert + 체인 순회 + 방어 검증 (FR-MV-01 Task 2)

package com.bts.issue.repository

import com.bts.issue.domain.IssueKey
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.sql.DriverManager

/**
 * IssueKeyRedirectRepository 통합 테스트.
 *
 * [IssueTestcontainersBase] JVM singleton PostgreSQL 컨테이너를 재사용한다.
 * Spring ApplicationContext 없이 DSLContext 를 직접 조합한다.
 *
 * 테스트 시나리오 (FR-MV-01 Task 2).
 * - T2-A. insert 후 findCurrentKey 단순 단건 조회.
 * - T2-B. 체인 순회 — A→B, B→C 삽입 후 findCurrentKey(A) = C.
 * - T2-C. 중간 체인 조회 — findCurrentKey(B) = C.
 * - T2-D. 미존재 old_key 조회 시 null 반환.
 * - T2-E. 중복 old_key insert 시 PK 위반 예외.
 * - T2-F. max-hop 초과(사이클 방어) — MAX_HOPS 이상 체인은 마지막 도달 키 반환(루프 탈출 보장).
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class IssueKeyRedirectRepositoryTest : IssueTestcontainersBase() {
    private lateinit var redirectRepository: IssueKeyRedirectRepository

    @BeforeEach
    fun setupRedirectRepository() {
        redirectRepository = IssueKeyRedirectRepository(dsl)
    }

    @BeforeEach
    fun cleanRedirects() {
        val pg = IssueTestcontainersBase.postgres
        DriverManager.getConnection(pg.jdbcUrl, pg.username, pg.password).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("DELETE FROM issue_key_redirects")
            }
        }
    }

    // ── T2-A. insert + findCurrentKey 단순 단건 ──────────────────────────────

    /**
     * Given  issue_key_redirects 비어 있음
     * When   insert(OLD-1, NEW-1) 후 findCurrentKey(OLD-1)
     * Then   "NEW-1" 반환.
     */
    @Test
    @Order(1)
    fun `T2-A - insert 후 findCurrentKey 가 newKey 를 반환한다`() {
        val oldKey = IssueKey.of("TPRJ", 1L)
        val newKey = IssueKey.of("TPRJ", 2L)

        redirectRepository.insert(oldKey, newKey)
        val result = redirectRepository.findCurrentKey(oldKey)

        assertThat(result).isEqualTo(newKey)
    }

    // ── T2-B. 체인 순회 — A→B, B→C → findCurrentKey(A) = C ─────────────────

    /**
     * Given  A→B, B→C 로 체인 연결
     * When   findCurrentKey(A)
     * Then   C 반환 (중간 키 건너뛰고 최종까지 순회).
     */
    @Test
    @Order(2)
    fun `T2-B - 체인 A-B-C 에서 findCurrentKey(A) 는 C 를 반환한다`() {
        val keyA = IssueKey.of("TPRJ", 10L)
        val keyB = IssueKey.of("TPRJ", 20L)
        val keyC = IssueKey.of("TPRJ", 30L)

        redirectRepository.insert(keyA, keyB)
        redirectRepository.insert(keyB, keyC)
        val result = redirectRepository.findCurrentKey(keyA)

        assertThat(result).isEqualTo(keyC)
    }

    // ── T2-C. 중간 체인 조회 — findCurrentKey(B) = C ─────────────────────────

    /**
     * Given  A→B, B→C 로 체인 연결
     * When   findCurrentKey(B)
     * Then   C 반환 (B 부터 이어서 순회).
     */
    @Test
    @Order(3)
    fun `T2-C - 체인 A-B-C 에서 findCurrentKey(B) 는 C 를 반환한다`() {
        val keyA = IssueKey.of("TPRJ", 10L)
        val keyB = IssueKey.of("TPRJ", 20L)
        val keyC = IssueKey.of("TPRJ", 30L)

        redirectRepository.insert(keyA, keyB)
        redirectRepository.insert(keyB, keyC)
        val result = redirectRepository.findCurrentKey(keyB)

        assertThat(result).isEqualTo(keyC)
    }

    // ── T2-D. 미존재 old_key 조회 시 null ────────────────────────────────────

    /**
     * Given  issue_key_redirects 비어 있음
     * When   findCurrentKey(UNKNOWN-999)
     * Then   null 반환.
     */
    @Test
    @Order(4)
    fun `T2-D - 존재하지 않는 oldKey 조회 시 null 을 반환한다`() {
        val unknownKey = IssueKey.of("TPRJ", 999L)

        val result = redirectRepository.findCurrentKey(unknownKey)

        assertThat(result).isNull()
    }

    // ── T2-E. 중복 old_key insert 시 PK 위반 예외 ────────────────────────────

    /**
     * Given  OLD-1 → NEW-1 이미 존재
     * When   OLD-1 → NEW-2 를 다시 insert
     * Then   PK 위반 예외 발생 (append-only, 덮어쓰기 불가).
     */
    @Test
    @Order(5)
    fun `T2-E - 중복 oldKey insert 시 PK 위반 예외가 발생한다`() {
        val oldKey = IssueKey.of("TPRJ", 1L)
        val newKey1 = IssueKey.of("TPRJ", 2L)
        val newKey2 = IssueKey.of("TPRJ", 3L)

        redirectRepository.insert(oldKey, newKey1)

        org.junit.jupiter.api.assertThrows<Exception> {
            redirectRepository.insert(oldKey, newKey2)
        }
    }

    // ── T2-F. max-hop 초과 방어 — 루프 탈출 보장 ─────────────────────────────

    /**
     * Given  MAX_HOPS + 2 개 체인 삽입 (선형 체인, 사이클 없음)
     * When   findCurrentKey(첫 번째 키)
     * Then   예외 없이 반환됨 (MAX_HOPS 에서 탈출).
     *        실제 마지막 키가 반환될 수도 있고, MAX_HOPS 에서 중단한 키가 반환될 수도 있음.
     *        중요한 것은 무한 루프 없이 반환된다는 것.
     */
    @Test
    @Order(6)
    fun `T2-F - MAX_HOPS 초과 체인에서 무한 루프 없이 반환된다`() {
        // MAX_HOPS(50) 를 초과하는 52 단계 체인 삽입
        val chainLength = 52
        val keys = (1..chainLength + 1).map { IssueKey.of("TPRJ", (100L + it)) }

        for (i in 0 until chainLength) {
            redirectRepository.insert(keys[i], keys[i + 1])
        }

        // 예외 없이 반환되어야 한다
        val result = redirectRepository.findCurrentKey(keys[0])
        assertThat(result).isNotNull()
    }
}
