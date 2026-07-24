// IssueRepository.listWithType 정렬 허용목록(whitelist) 통합 테스트 (FR-UX-06 Phase 5 PR18 Task 1)

package com.bts.issue.repository

import com.bts.issue.domain.ActorId
import com.bts.issue.domain.Issue
import com.bts.issue.domain.IssueId
import com.bts.issue.domain.IssueKey
import com.bts.shared.board.BoardCardFilter
import com.bts.shared.issue.IssueTypeId
import com.bts.shared.permission.IssueSecurityAccess
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import java.nio.ByteBuffer
import java.sql.DriverManager
import java.sql.Timestamp
import java.time.OffsetDateTime
import java.util.UUID

/**
 * IssueRepository.listWithType 정렬 허용목록(whitelist) 통합 테스트 (FR-UX-06 Phase 5 PR18 Task 1).
 *
 * `listWithType` 의 content 쿼리가 `Pageable.sort` 를 허용목록으로 적용하는지 실 PostgreSQL
 * (Testcontainers — 테스트용 DB를 도커로 자동 실행하는 라이브러리) 에서 검증한다.
 *
 * ## 테스트 시나리오
 * - S1. `priority,desc` 정렬 — 허용 필드는 그대로 적용되어 우선순위 값 내림차순으로 반환.
 * - EC1. 허용목록 외 필드(`assigneeId`) — 예외 없이 기본 정렬(`created_at DESC`)로 대체.
 * - N2. 정렬 무지정(unsorted [Pageable]) — 기존 `created_at DESC` 그대로 유지(무회귀,
 *   load-bearing — 화이트리스트 도입이 기존 무정렬 호출부(보드 등)를 깨지 않는지 확인).
 * - C1. 허용 필드 값이 동값(tie)일 때 — id 를 안정 tiebreaker 로 사용해 결과 순서가 결정적임을
 *   보장(코드리뷰 C1). tiebreaker 가 없으면 동값 행의 순서가 DB 미정의라 페이지네이션에서
 *   행 중복/누락 위험이 생긴다.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class IssueRepositoryListSortTest : IssueTestcontainersBase() {
    private var taskTypeId: IssueTypeId? = null

    /** V003 seed 에서 task 타입 id 조회. */
    @BeforeAll
    fun resolveTaskTypeId() {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement("SELECT id FROM issue_types WHERE key = 'task' AND deleted_at IS NULL LIMIT 1")
                .use { stmt ->
                    stmt.executeQuery().use { rs ->
                        check(rs.next()) { "V003 마이그레이션에서 task 타입이 없습니다." }
                        taskTypeId = IssueTypeId(rs.getLong(1))
                    }
                }
        }
    }

    private fun requireTaskTypeId(): IssueTypeId = requireNotNull(taskTypeId) { "taskTypeId 가 초기화되지 않았습니다." }

    /** unrestricted=true 접근권한 — 보안등급 필터 미적용 빠른경로. */
    private val unrestrictedAccess =
        IssueSecurityAccess(
            unrestricted = true,
            staticLevelIds = emptySet(),
            reporterLevelIds = emptySet(),
            assigneeLevelIds = emptySet(),
        )

    /**
     * priority 정렬 검증용 이슈 생성 helper. `Issue.create` 경로로 삽입해 도메인 불변식을 통과시킨다.
     *
     * @param seq IssueKey 고유성을 위한 순번.
     * @param priority 우선순위 숫자 1(Highest)..5(Lowest).
     */
    private fun buildIssue(
        seq: Long,
        priority: Int,
    ): Issue =
        Issue.create(
            id = IssueId(UUID.randomUUID()),
            key = IssueKey.of("TPRJ", seq),
            projectId = testProjectId,
            typeId = requireTaskTypeId(),
            summary = "sort-test seq=$seq priority=$priority",
            reporterId = ActorId(UUID.randomUUID()),
            currentStateKey = "open",
            priority = priority,
        )

    /**
     * `created_at` 결정론적 제어용 JDBC 직접 삽입 helper.
     *
     * `Issue.create()` 는 `Instant.now()` 를 사용하므로 `created_at` 순서를 제어할 수 없다.
     * fallback(EC1)·무회귀(N2) 시나리오는 `created_at` 순서 검증이 핵심이므로, JDBC 로 직접
     * `created_at` 을 명시 설정한다(IssueRepositoryTest.insertIssueAt 패턴 준용).
     *
     * @param key issues.key 값. 예: "TPRJ-1".
     * @param createdAt 명시적 생성 시각.
     */
    private fun insertIssueAt(
        key: String,
        createdAt: OffsetDateTime,
    ): UUID {
        val id = UUID.randomUUID()
        val ts = Timestamp.from(createdAt.toInstant())
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                """INSERT INTO issues
                   (id, key, project_id, summary, reporter_id, current_state_key, version, type_id, created_at, updated_at)
                   VALUES (?, ?, ?, ?, ?, ?, 1, ?, ?, ?)""",
            ).use { stmt ->
                stmt.setObject(1, id)
                stmt.setString(2, key)
                stmt.setObject(3, testProjectId)
                stmt.setString(4, "Sort test: $key")
                stmt.setObject(5, UUID.randomUUID())
                stmt.setString(6, "open")
                stmt.setLong(7, requireTaskTypeId().value)
                stmt.setTimestamp(8, ts)
                stmt.setTimestamp(9, ts)
                stmt.executeUpdate()
            }
        }
        return id
    }

    /**
     * PostgreSQL 의 `uuid` 컬럼 정렬 순서(16바이트 부호없는 바이트열 비교)를 재현하는 comparator.
     *
     * **함정**: `java.util.UUID.compareTo()` 는 mostSignificantBits/leastSignificantBits 를
     * **부호 있는(signed)** long 으로 비교한다. 반면 PostgreSQL 은 uuid 값을 16바이트 부호 없는
     * 바이트열로 비교한다. 첫 바이트가 `0x80` 이상인 UUID 가 섞이면 두 비교 결과가 어긋난다
     * (예: `"a783..."` 는 PG 기준 최댓값이지만, Java `compareTo()` 기준으로는 최솟값이다 —
     * mostSignificantBits 의 부호 비트가 켜져 음수로 취급되기 때문).
     * [IssueRepository]의 `ORDER BY id DESC` 결과를 검증하는 테스트 오라클은 반드시 이 비교자를
     * 사용해야 한다 — `sortedDescending()`(Java `compareTo()` 기반) 사용 시 거짓 실패가 난다.
     */
    private fun pgUuidComparator(): Comparator<UUID> =
        Comparator { a, b ->
            val aBytes = uuidToBytes(a)
            val bBytes = uuidToBytes(b)
            aBytes.indices.firstNotNullOfOrNull { i ->
                val diff = (aBytes[i].toInt() and 0xFF) - (bBytes[i].toInt() and 0xFF)
                if (diff != 0) diff else null
            } ?: 0
        }

    /** [UUID] 를 16바이트 배열(most/least significant bits, big-endian)로 변환한다. */
    private fun uuidToBytes(uuid: UUID): ByteArray =
        ByteBuffer.allocate(16)
            .putLong(uuid.mostSignificantBits)
            .putLong(uuid.leastSignificantBits)
            .array()

    // ── S1. priority,desc 정렬 — 허용 필드 적용 ─────────────────────────────

    /**
     * Given  priority 3, 1, 5 인 이슈 3건(삽입 순서를 정렬 결과와 다르게 섞음)
     * When   `Sort.by(DESC, "priority")` 로 listWithType 호출
     * Then   결과가 priority 값 내림차순(5, 3, 1) 으로 반환된다.
     */
    @Test
    @Order(1)
    fun `S1 - priority desc 정렬 요청 시 우선순위 값 내림차순으로 반환한다`() {
        val actor = UUID.randomUUID()
        repository.insert(buildIssue(seq = 1, priority = 3))
        repository.insert(buildIssue(seq = 2, priority = 1))
        repository.insert(buildIssue(seq = 3, priority = 5))

        val sort = Sort.by(Sort.Direction.DESC, "priority")
        val page =
            repository.listWithType(
                "TPRJ",
                PageRequest.of(0, 10, sort),
                actor,
                unrestrictedAccess,
                BoardCardFilter.EMPTY,
            )

        assertThat(page.content.map { it.priority }).containsExactly(5, 3, 1)
    }

    // ── EC1. 허용목록 외 필드 — 예외 아닌 기본 정렬 fallback ────────────────

    /**
     * Given  created_at 이 오래된 순서로 삽입된 이슈 3건(t1 < t2 < t3)
     * When   허용목록에 없는 필드(`assigneeId,asc`) 로 listWithType 호출
     * Then   예외를 던지지 않고 기본 정렬(created_at DESC) 로 대체되어 t3, t2, t1 순서로 반환된다.
     */
    @Test
    @Order(2)
    fun `EC1 - 허용목록 외 필드는 예외 없이 기본 정렬로 대체된다`() {
        val actor = UUID.randomUUID()
        val base = OffsetDateTime.now()
        insertIssueAt("TPRJ-1", base.minusMinutes(2))
        insertIssueAt("TPRJ-2", base.minusMinutes(1))
        insertIssueAt("TPRJ-3", base)

        val sort = Sort.by(Sort.Direction.ASC, "assigneeId")
        val page =
            repository.listWithType(
                "TPRJ",
                PageRequest.of(0, 10, sort),
                actor,
                unrestrictedAccess,
                BoardCardFilter.EMPTY,
            )

        assertThat(page.content.map { it.key }).containsExactly("TPRJ-3", "TPRJ-2", "TPRJ-1")
    }

    // ── N2. 정렬 무지정(unsorted) — 기존 동작 무회귀 ────────────────────────

    /**
     * Given  created_at 이 오래된 순서로 삽입된 이슈 3건(t1 < t2 < t3)
     * When   정렬을 지정하지 않은(unsorted) [PageRequest] 로 listWithType 호출
     * Then   기존 동작 그대로 created_at DESC 순서(t3, t2, t1) 로 반환된다.
     *
     * load-bearing — 화이트리스트 도입 이후에도 무정렬 호출부(보드 등 [listWithType] 의 기존
     * 호출자)의 기본 정렬이 깨지지 않아야 한다. mutation 가드 — 현재 고정 `orderBy` 를 그대로
     * 두면 S1 이 red 나므로, S1 을 통과시키면서 이 테스트도 함께 통과해야 한다.
     */
    @Test
    @Order(3)
    fun `N2 - 정렬 무지정 시 기존 created_at desc 정렬을 유지한다`() {
        val actor = UUID.randomUUID()
        val base = OffsetDateTime.now()
        insertIssueAt("TPRJ-1", base.minusMinutes(2))
        insertIssueAt("TPRJ-2", base.minusMinutes(1))
        insertIssueAt("TPRJ-3", base)

        val page =
            repository.listWithType(
                "TPRJ",
                PageRequest.of(0, 10),
                actor,
                unrestrictedAccess,
                BoardCardFilter.EMPTY,
            )

        assertThat(page.content.map { it.key }).containsExactly("TPRJ-3", "TPRJ-2", "TPRJ-1")
    }

    // ── C1. priority 동값(tie) — id tiebreaker 로 결정적 정렬 ──────────────

    /**
     * Given  priority 값이 모두 동일(3)한 이슈 3건
     * When   `Sort.by(DESC, "priority")` 로 listWithType 호출
     * Then   허용 필드가 동값이라도 id 내림차순(tiebreaker)으로 항상 동일한 순서가 나온다.
     *
     * load-bearing — [IssueRepository.buildListOrderBy] 가 허용 필드만 반환하면 동값 tie 는
     * DB 미정의 순서가 되어 페이지네이션에서 행 중복/누락을 유발할 수 있다(코드리뷰 C1).
     * id 를 안정 tiebreaker 로 항상 append 해야 이 단언을 통과한다.
     */
    @Test
    @Order(4)
    fun `C1 - priority 동값일 때 id desc tiebreaker로 결정적 정렬된다`() {
        val actor = UUID.randomUUID()
        val inserted =
            listOf(
                repository.insert(buildIssue(seq = 1, priority = 3)),
                repository.insert(buildIssue(seq = 2, priority = 3)),
                repository.insert(buildIssue(seq = 3, priority = 3)),
            )
        val expectedIds = inserted.map { it.id.value }.sortedWith(pgUuidComparator().reversed())

        val sort = Sort.by(Sort.Direction.DESC, "priority")
        val page =
            repository.listWithType(
                "TPRJ",
                PageRequest.of(0, 10, sort),
                actor,
                unrestrictedAccess,
                BoardCardFilter.EMPTY,
            )

        assertThat(page.content.map { it.id }).containsExactlyElementsOf(expectedIds)
    }
}
