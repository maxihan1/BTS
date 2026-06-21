// 보드 카드 cross-BC 조회 adapter 통합 테스트 — accessibleLevels + 목록 보안필터 정석 재사용 검증.

package com.bts.issue.adapter.outbound.board

import com.bts.issue.domain.ActorId
import com.bts.issue.domain.Issue
import com.bts.issue.domain.IssueId
import com.bts.issue.domain.IssueKey
import com.bts.issue.repository.IssueRepository
import com.bts.issue.repository.IssueTestcontainersBase
import com.bts.shared.board.BoardCardFilter
import com.bts.shared.board.BoardIssuePage
import com.bts.shared.issue.IssueTypeId
import com.bts.shared.permission.IssueSecurityAccess
import com.bts.shared.permission.IssueSecurityDirectory
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.sql.DriverManager
import java.util.UUID

/**
 * [BoardIssueLookupAdapter] 통합 테스트 (FR-BD-01 Task 4).
 *
 * 보드 카드 목록 cross-BC 조회는 **목록 보안필터 정석**([IssueSecurityListFilterTest] S1~S8 동형)을
 * 재사용해야 한다. 수신자용 단건 위임(IssueVisibilityPort/IssueSecurityDecider)은 N+1 + 목록
 * 부적합이라 금지 — 멤버 타입 누락 시 제목 누출(FR-NT-03 BLOCKER) 재발 방지.
 *
 * 이 테스트는 손수 만든 [IssueSecurityAccess] 를 반환하는 stub [IssueSecurityDirectory] 를 주입해,
 * adapter 가 `accessibleLevels` → SQL WHERE 술어(`buildSecurityCondition`) 푸시다운 경로로
 * 비가시 행을 content 에서 제외하는지 검증한다.
 *
 * 공유 Testcontainers 인스턴스: [IssueTestcontainersBase.postgres] JVM singleton 재사용.
 *
 * ## 테스트 시나리오
 * - S1. security_level_id=NULL 이슈는 항상 노출.
 * - S2. staticLevelIds 포함 등급 이슈는 노출, 비멤버 등급은 제외.
 * - S3. REPORTER 등급 이슈는 viewer 가 reporter 일 때만 노출.
 * - S4. ASSIGNEE 등급 이슈는 viewer 가 assignee 일 때만 노출.
 * - S5. soft-deleted(deleted_at) 이슈는 제외.
 * - S6. priority/currentStateKey/assignee/version/summary/key 매핑 정확.
 * - S7. unrestricted=true 이면 등급 무관 전부 노출(빠른경로).
 * - S8. 혼합 등급 + soft-deleted — 노출 대상만 정확히 분리(N+1 없이 단일 쿼리).
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class BoardIssueLookupAdapterTest : IssueTestcontainersBase() {
    private var taskTypeId: IssueTypeId? = null

    /**
     * 주입할 [IssueSecurityAccess] 를 테스트마다 교체하는 stub directory.
     *
     * adapter 가 `accessibleLevels(viewerUserId, projectKey)` 를 호출하면 [next] 를 반환한다.
     * 손수 만든 access 로 SQL 술어 동작만 검증하므로 실 멤버십 조회는 하지 않는다.
     */
    private class StubSecurityDirectory(
        var next: IssueSecurityAccess,
    ) : IssueSecurityDirectory {
        override fun levelBelongsToProjectScheme(
            levelId: UUID,
            projectKey: String,
        ): Boolean = true

        override fun accessibleLevels(
            actorId: UUID,
            projectKey: String,
        ): IssueSecurityAccess = next
    }

    private fun unrestricted() =
        IssueSecurityAccess(
            unrestricted = true,
            staticLevelIds = emptySet(),
            reporterLevelIds = emptySet(),
            assigneeLevelIds = emptySet(),
        )

    private fun restricted(
        staticLevelIds: Set<UUID> = emptySet(),
        reporterLevelIds: Set<UUID> = emptySet(),
        assigneeLevelIds: Set<UUID> = emptySet(),
    ) = IssueSecurityAccess(
        unrestricted = false,
        staticLevelIds = staticLevelIds,
        reporterLevelIds = reporterLevelIds,
        assigneeLevelIds = assigneeLevelIds,
    )

    /** resolveTaskTypeId — V003 seed 에서 task 타입 id 조회. */
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

    /** stub directory + 실 repository 로 adapter 구성. access 는 테스트마다 교체. */
    private fun adapterWith(access: IssueSecurityAccess): BoardIssueLookupAdapter =
        BoardIssueLookupAdapter(repository, StubSecurityDirectory(access))

    /**
     * 테스트용 이슈 생성 helper.
     *
     * @param seq issues.key_sequence 증분값 — IssueKey 고유성에 사용.
     * @param reporterId 이슈 보고자 UUID.
     * @param assigneeId 이슈 담당자 UUID. null 이면 미배정.
     * @param securityLevelId 보안 등급 UUID. null 이면 공개(등급 없음).
     * @param currentStateKey 현재 워크플로우 상태 키.
     * @param priority 우선순위.
     */
    @Suppress("LongParameterList")
    private fun insertIssue(
        seq: Long,
        reporterId: UUID = UUID.randomUUID(),
        assigneeId: UUID? = null,
        securityLevelId: UUID? = null,
        currentStateKey: String = "open",
        priority: Int = 3,
    ): Issue =
        repository.insert(
            Issue.create(
                id = IssueId(UUID.randomUUID()),
                key = IssueKey.of("TPRJ", seq),
                projectId = testProjectId,
                typeId = requireTaskTypeId(),
                summary = "board card $seq",
                reporterId = ActorId(reporterId),
                currentStateKey = currentStateKey,
                priority = priority,
                assigneeId = assigneeId?.let { ActorId(it) },
                securityLevelId = securityLevelId,
            ),
        )

    /** issues.deleted_at 를 NOW() 로 직접 설정해 soft-delete 상태를 만든다. */
    private fun softDelete(key: String) {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement("UPDATE issues SET deleted_at = NOW() WHERE key = ?").use { stmt ->
                stmt.setString(1, key)
                stmt.executeUpdate()
            }
        }
    }

    // ── S1. NULL 등급 이슈는 항상 노출 ─────────────────────────────────────────

    @Test
    @Order(1)
    fun `S1 - NULL 등급 이슈는 항상 노출된다`() {
        val viewer = UUID.randomUUID()
        val excludedLevel = UUID.randomUUID()
        insertIssue(seq = 1, securityLevelId = null)
        insertIssue(seq = 2, securityLevelId = excludedLevel)

        val result = adapterWith(restricted()).listVisibleIssuesByProject("TPRJ", viewer)

        assertThat(result.issues).hasSize(1)
        assertThat(result.issues.first().summary).isEqualTo("board card 1")
    }

    // ── S2. staticLevelIds 포함 등급만 노출 ───────────────────────────────────

    @Test
    @Order(2)
    fun `S2 - staticLevelIds 에 포함된 등급만 노출하고 비멤버 등급은 제외한다`() {
        val viewer = UUID.randomUUID()
        val staticLevel = UUID.randomUUID()
        val excludedLevel = UUID.randomUUID()
        insertIssue(seq = 1, securityLevelId = null)
        insertIssue(seq = 2, securityLevelId = staticLevel)
        insertIssue(seq = 3, securityLevelId = excludedLevel)

        val result =
            adapterWith(restricted(staticLevelIds = setOf(staticLevel)))
                .listVisibleIssuesByProject("TPRJ", viewer)

        assertThat(result.issues.map { it.summary }).containsExactlyInAnyOrder("board card 1", "board card 2")
    }

    // ── S3. REPORTER 등급 — viewer 가 reporter 일 때만 노출 ─────────────────

    @Test
    @Order(3)
    fun `S3 - REPORTER 등급 이슈는 viewer 가 reporter 일 때만 노출된다`() {
        val viewer = UUID.randomUUID()
        val other = UUID.randomUUID()
        val reporterLevel = UUID.randomUUID()
        insertIssue(seq = 1, reporterId = viewer, securityLevelId = reporterLevel)
        insertIssue(seq = 2, reporterId = other, securityLevelId = reporterLevel)

        val result =
            adapterWith(restricted(reporterLevelIds = setOf(reporterLevel)))
                .listVisibleIssuesByProject("TPRJ", viewer)

        assertThat(result.issues).hasSize(1)
        assertThat(result.issues.first().summary).isEqualTo("board card 1")
    }

    // ── S4. ASSIGNEE 등급 — viewer 가 assignee 일 때만 노출 ────────────────

    @Test
    @Order(4)
    fun `S4 - ASSIGNEE 등급 이슈는 viewer 가 assignee 일 때만 노출된다`() {
        val viewer = UUID.randomUUID()
        val other = UUID.randomUUID()
        val assigneeLevel = UUID.randomUUID()
        insertIssue(seq = 1, assigneeId = viewer, securityLevelId = assigneeLevel)
        insertIssue(seq = 2, assigneeId = other, securityLevelId = assigneeLevel)

        val result =
            adapterWith(restricted(assigneeLevelIds = setOf(assigneeLevel)))
                .listVisibleIssuesByProject("TPRJ", viewer)

        assertThat(result.issues).hasSize(1)
        assertThat(result.issues.first().summary).isEqualTo("board card 1")
    }

    // ── S5. soft-deleted 이슈는 제외 ──────────────────────────────────────────

    @Test
    @Order(5)
    fun `S5 - soft-deleted 이슈는 제외된다`() {
        val viewer = UUID.randomUUID()
        insertIssue(seq = 1, securityLevelId = null)
        insertIssue(seq = 2, securityLevelId = null)
        softDelete("TPRJ-2")

        val result = adapterWith(unrestricted()).listVisibleIssuesByProject("TPRJ", viewer)

        assertThat(result.issues).hasSize(1)
        assertThat(result.issues.first().key).isEqualTo("TPRJ-1")
    }

    // ── S6. 필드 매핑 정확성 ──────────────────────────────────────────────────

    @Test
    @Order(6)
    fun `S6 - priority currentStateKey assignee version summary key 가 정확히 매핑된다`() {
        val viewer = UUID.randomUUID()
        val assignee = UUID.randomUUID()
        val inserted =
            insertIssue(
                seq = 1,
                assigneeId = assignee,
                securityLevelId = null,
                currentStateKey = "in_progress",
                priority = 1,
            )

        val result = adapterWith(unrestricted()).listVisibleIssuesByProject("TPRJ", viewer)

        assertThat(result.issues).hasSize(1)
        val card = result.issues.first()
        assertThat(card.key).isEqualTo("TPRJ-1")
        assertThat(card.summary).isEqualTo("board card 1")
        assertThat(card.currentStateKey).isEqualTo("in_progress")
        assertThat(card.assigneeId).isEqualTo(assignee)
        assertThat(card.priority).isEqualTo(1)
        assertThat(card.version).isEqualTo(inserted.version)
    }

    // ── S7. unrestricted=true 빠른경로 ────────────────────────────────────────

    @Test
    @Order(7)
    fun `S7 - unrestricted=true 이면 등급 무관 전부 노출된다`() {
        val viewer = UUID.randomUUID()
        insertIssue(seq = 1, securityLevelId = null)
        insertIssue(seq = 2, securityLevelId = UUID.randomUUID())
        insertIssue(seq = 3, securityLevelId = UUID.randomUUID())

        val result = adapterWith(unrestricted()).listVisibleIssuesByProject("TPRJ", viewer)

        assertThat(result.issues).hasSize(3)
    }

    // ── S8. 혼합 등급 + soft-deleted ──────────────────────────────────────────

    @Test
    @Order(8)
    fun `S8 - 혼합 등급과 soft-deleted 가 섞여도 노출 대상만 정확히 분리한다`() {
        val viewer = UUID.randomUUID()
        val staticLevel = UUID.randomUUID()
        val reporterLevel = UUID.randomUUID()
        val assigneeLevel = UUID.randomUUID()
        val excludedLevel = UUID.randomUUID()

        insertIssue(seq = 1, securityLevelId = null) // 노출
        insertIssue(seq = 2, securityLevelId = staticLevel) // 노출
        insertIssue(seq = 3, reporterId = viewer, securityLevelId = reporterLevel) // 노출
        insertIssue(seq = 4, assigneeId = viewer, securityLevelId = assigneeLevel) // 노출
        insertIssue(seq = 5, securityLevelId = excludedLevel) // 제외(비멤버)
        insertIssue(seq = 6, securityLevelId = null) // soft-delete → 제외
        softDelete("TPRJ-6")

        val result =
            adapterWith(
                restricted(
                    staticLevelIds = setOf(staticLevel),
                    reporterLevelIds = setOf(reporterLevel),
                    assigneeLevelIds = setOf(assigneeLevel),
                ),
            ).listVisibleIssuesByProject("TPRJ", viewer)

        assertThat(result.issues.map { it.key })
            .containsExactlyInAnyOrder("TPRJ-1", "TPRJ-2", "TPRJ-3", "TPRJ-4")
        assertThat(result.truncated).isFalse()
    }

    // ── S9. EC7 — 필터+truncated 순서 회귀 가드 ────────────────────────────────
    //
    // 불변식: 필터는 WHERE 절에서 LIMIT 보다 먼저 적용된다.
    //   "필터 후 LIMIT" = 올바른 동작  →  매칭 이슈만 카운트
    //   "LIMIT 후 필터" = 잘못된 동작  →  LIMIT+1 행 fetch 후 메모리 필터, truncated 거짓 양성
    //
    // 시드 구성:
    //   - 필터에 매칭 "안" 되는 이슈 LIMIT+1 건 (assigneeIds=[targetAssignee] 필터에 해당 없는 이슈)
    //   - 필터에 매칭 "되는" 이슈 3건 (targetAssignee 담당)
    //
    // 단언:
    //   (a) 반환 건수 = 3  (매칭 이슈만)
    //   (b) truncated = false  (필터 후 집합이 LIMIT 이하)
    //
    // 회귀 가드 근거:
    //   만약 LIMIT+1 행을 먼저 fetch 하고 메모리에서 필터를 적용한다면,
    //   LIMIT+1 건의 비매칭 이슈가 fetch 되어 (a) 빈 목록 또는 (b) truncated=true 가 되어 이 단언이 실패한다.

    /**
     * EC7 회귀 가드용 대량 삽입 helper.
     *
     * 단일 커넥션 + JDBC addBatch/executeBatch 로 왕복(round-trip) 최소화.
     * assigneeId 로 [otherAssignee] 를 주입해 필터에 "매칭 안 되는" 이슈 [count] 건을 삽입한다.
     * key 는 `TPRJ-1` ~ `TPRJ-[count]` 형식 (호출 전 `cleanIssues()` 가 key_sequence=0 을 보장).
     */
    @Suppress("NestedBlockDepth")
    private fun insertNonMatchingIssuesBatch(
        count: Int,
        otherAssignee: UUID,
    ) {
        val typeId = requireTaskTypeId().value
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.autoCommit = false
            conn.prepareStatement(
                "INSERT INTO issues " +
                    "(id, key, project_id, type_id, summary, reporter_id, assignee_id, current_state_key, priority) " +
                    "VALUES (gen_random_uuid(), ?, ?, ?, ?, gen_random_uuid(), ?, 'open', 3)",
            ).use { stmt ->
                for (seq in 1..count) {
                    stmt.setString(1, "TPRJ-$seq")
                    stmt.setObject(2, testProjectId)
                    stmt.setLong(3, typeId)
                    stmt.setString(4, "non-match $seq")
                    stmt.setObject(5, otherAssignee)
                    stmt.addBatch()
                    if (seq % 100 == 0) stmt.executeBatch()
                }
                stmt.executeBatch()
            }
            conn.commit()
        }
    }

    @Test
    @Order(9)
    fun `S9 - EC7 필터 후 LIMIT 적용 불변식 - 매칭 이슈가 LIMIT 이하면 truncated=false 이고 비매칭 이슈가 LIMIT+1건이어도 누락 없다`() {
        val targetAssignee = UUID.randomUUID()
        val otherAssignee = UUID.randomUUID()

        // 필터에 매칭 "안" 되는 이슈 LIMIT+1 건 삽입
        val nonMatchCount = IssueRepository.BOARD_CARD_FETCH_LIMIT + 1
        insertNonMatchingIssuesBatch(nonMatchCount, otherAssignee)

        // 필터에 매칭 "되는" 이슈 3건 삽입 (seq는 nonMatchCount+1 부터)
        val matchStart = nonMatchCount + 1
        for (seq in matchStart..(matchStart + 2)) {
            insertIssue(seq = seq.toLong(), assigneeId = targetAssignee, securityLevelId = null)
        }

        // 필터 적용: targetAssignee 담당 이슈만
        val filter = BoardCardFilter(assigneeIds = listOf(targetAssignee))
        val result = adapterWithFilter(unrestricted(), filter)

        // (a) 매칭 이슈 3건만 반환 — 비매칭 LIMIT+1 건은 WHERE 절에서 제거되어야 함
        assertThat(result.issues).hasSize(3)
        assertThat(result.issues.map { it.assigneeId }).allMatch { it == targetAssignee }
        // (b) 필터 후 집합이 LIMIT 이하 → truncated=false
        // 만약 "LIMIT 후 필터"였다면 비매칭 LIMIT+1 건이 먼저 잘려 truncated=true 가 되거나 매칭이 누락된다.
        assertThat(result.truncated).isFalse()
    }

    // ── 필터 헬퍼 ─────────────────────────────────────────────────────────────

    /**
     * components 테이블에 테스트용 컴포넌트 1건을 직접 삽입하고 UUID 를 반환한다.
     *
     * @param name 컴포넌트 이름. 테스트 간 충돌 방지를 위해 호출 측에서 고유값을 전달한다.
     * @return 삽입된 컴포넌트 UUID.
     */
    private fun insertComponent(name: String): UUID {
        val id = UUID.randomUUID()
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                "INSERT INTO components (id, project_id, name) VALUES (?, ?, ?)",
            ).use { stmt ->
                stmt.setObject(1, id)
                stmt.setObject(2, testProjectId)
                stmt.setString(3, name)
                stmt.executeUpdate()
            }
        }
        return id
    }

    /**
     * issue_components 조인 테이블에 이슈↔컴포넌트 연결을 직접 삽입한다.
     *
     * ON DELETE CASCADE 가 적용되어 있어 cleanIssues() 의 DELETE FROM issues 시 자동 정리된다.
     *
     * @param issueId 연결할 이슈 UUID.
     * @param componentId 연결할 컴포넌트 UUID.
     */
    private fun linkComponent(
        issueId: UUID,
        componentId: UUID,
    ) {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                "INSERT INTO issue_components (issue_id, component_id) VALUES (?, ?)",
            ).use { stmt ->
                stmt.setObject(1, issueId)
                stmt.setObject(2, componentId)
                stmt.executeUpdate()
            }
        }
    }

    /**
     * issues.labels 를 직접 UPDATE 해 라벨 배열을 설정한다.
     *
     * Issue.create 경로에서는 labels 를 지정할 수 없어 insert 후 raw SQL 로 덮어쓴다.
     *
     * @param issueId 이슈 UUID.
     * @param labels 설정할 라벨 이름 배열.
     */
    private fun setLabels(
        issueId: UUID,
        labels: List<String>,
    ) {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                "UPDATE issues SET labels = ? WHERE id = ?",
            ).use { stmt ->
                val arr = conn.createArrayOf("text", labels.toTypedArray())
                stmt.setArray(1, arr)
                stmt.setObject(2, issueId)
                stmt.executeUpdate()
            }
        }
    }

    /** 3-인자 filter 오버로드를 호출하는 adapter. */
    private fun adapterWithFilter(
        access: IssueSecurityAccess,
        filter: BoardCardFilter,
    ): BoardIssuePage = adapterWith(access).listVisibleIssuesByProject("TPRJ", UUID.randomUUID(), filter)

    // ── F1. assignee 단일 필터 ────────────────────────────────────────────────

    @Test
    @Order(10)
    fun `F1 - assigneeIds 단일 필터는 해당 담당자의 이슈만 반환한다`() {
        val assignee = UUID.randomUUID()
        val other = UUID.randomUUID()
        insertIssue(seq = 1, assigneeId = assignee, securityLevelId = null)
        insertIssue(seq = 2, assigneeId = other, securityLevelId = null)
        insertIssue(seq = 3, securityLevelId = null) // 미배정

        val filter = BoardCardFilter(assigneeIds = listOf(assignee))
        val result = adapterWithFilter(unrestricted(), filter)

        assertThat(result.issues.map { it.key }).containsExactly("TPRJ-1")
    }

    // ── F2. assignee 다중(OR) 필터 ───────────────────────────────────────────

    @Test
    @Order(11)
    fun `F2 - assigneeIds 다중 필터는 해당 담당자들 중 하나인 이슈를 OR 로 반환한다`() {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        val c = UUID.randomUUID()
        insertIssue(seq = 1, assigneeId = a, securityLevelId = null)
        insertIssue(seq = 2, assigneeId = b, securityLevelId = null)
        insertIssue(seq = 3, assigneeId = c, securityLevelId = null)

        val filter = BoardCardFilter(assigneeIds = listOf(a, b))
        val result = adapterWithFilter(unrestricted(), filter)

        assertThat(result.issues.map { it.key }).containsExactlyInAnyOrder("TPRJ-1", "TPRJ-2")
    }

    // ── F3. includeUnassigned 단독 ───────────────────────────────────────────

    @Test
    @Order(12)
    fun `F3 - includeUnassigned=true 이면 담당자 없는 이슈만 반환한다`() {
        val assignee = UUID.randomUUID()
        insertIssue(seq = 1, assigneeId = assignee, securityLevelId = null)
        insertIssue(seq = 2, securityLevelId = null) // 미배정
        insertIssue(seq = 3, securityLevelId = null) // 미배정

        val filter = BoardCardFilter(includeUnassigned = true)
        val result = adapterWithFilter(unrestricted(), filter)

        assertThat(result.issues.map { it.key }).containsExactlyInAnyOrder("TPRJ-2", "TPRJ-3")
    }

    // ── F4. assigneeIds + includeUnassigned 혼합(OR) ─────────────────────────

    @Test
    @Order(13)
    fun `F4 - assigneeIds 와 includeUnassigned 가 함께이면 지정 담당자 또는 미배정 이슈를 OR 로 반환한다`() {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        insertIssue(seq = 1, assigneeId = a, securityLevelId = null)
        insertIssue(seq = 2, assigneeId = b, securityLevelId = null)
        insertIssue(seq = 3, securityLevelId = null) // 미배정

        val filter = BoardCardFilter(assigneeIds = listOf(a), includeUnassigned = true)
        val result = adapterWithFilter(unrestricted(), filter)

        assertThat(result.issues.map { it.key }).containsExactlyInAnyOrder("TPRJ-1", "TPRJ-3")
    }

    // ── F5. label 단일 필터 ──────────────────────────────────────────────────

    @Test
    @Order(14)
    fun `F5 - labels 단일 필터는 해당 라벨을 가진 이슈만 반환한다`() {
        val i1 = insertIssue(seq = 1, securityLevelId = null)
        val i2 = insertIssue(seq = 2, securityLevelId = null)
        insertIssue(seq = 3, securityLevelId = null)
        setLabels(i1.id.value, listOf("bug", "urgent"))
        setLabels(i2.id.value, listOf("feature"))

        val filter = BoardCardFilter(labels = listOf("bug"))
        val result = adapterWithFilter(unrestricted(), filter)

        assertThat(result.issues.map { it.key }).containsExactly("TPRJ-1")
    }

    // ── F6. label 다중(OR, overlap) ──────────────────────────────────────────

    @Test
    @Order(15)
    fun `F6 - labels 다중 필터는 지정된 라벨 중 하나라도 포함한 이슈를 OR 로 반환한다`() {
        val i1 = insertIssue(seq = 1, securityLevelId = null)
        val i2 = insertIssue(seq = 2, securityLevelId = null)
        val i3 = insertIssue(seq = 3, securityLevelId = null)
        setLabels(i1.id.value, listOf("bug"))
        setLabels(i2.id.value, listOf("feature"))
        setLabels(i3.id.value, listOf("docs"))

        val filter = BoardCardFilter(labels = listOf("bug", "feature"))
        val result = adapterWithFilter(unrestricted(), filter)

        assertThat(result.issues.map { it.key }).containsExactlyInAnyOrder("TPRJ-1", "TPRJ-2")
    }

    // ── F7. label 대소문자 정확 일치 ─────────────────────────────────────────

    @Test
    @Order(16)
    fun `F7 - labels 필터는 대소문자 정확 일치로 검사한다`() {
        val i1 = insertIssue(seq = 1, securityLevelId = null)
        val i2 = insertIssue(seq = 2, securityLevelId = null)
        setLabels(i1.id.value, listOf("Bug"))
        setLabels(i2.id.value, listOf("bug"))

        val filter = BoardCardFilter(labels = listOf("bug"))
        val result = adapterWithFilter(unrestricted(), filter)

        assertThat(result.issues.map { it.key }).containsExactly("TPRJ-2")
    }

    // ── F8. component 단일 필터 (EXISTS 서브쿼리) ─────────────────────────────

    @Test
    @Order(17)
    fun `F8 - componentIds 단일 필터는 해당 컴포넌트에 속한 이슈만 반환한다`() {
        val comp1 = insertComponent("backend")
        val comp2 = insertComponent("frontend")
        val i1 = insertIssue(seq = 1, securityLevelId = null)
        val i2 = insertIssue(seq = 2, securityLevelId = null)
        insertIssue(seq = 3, securityLevelId = null)
        linkComponent(i1.id.value, comp1)
        linkComponent(i2.id.value, comp2)

        val filter = BoardCardFilter(componentIds = listOf(comp1))
        val result = adapterWithFilter(unrestricted(), filter)

        assertThat(result.issues.map { it.key }).containsExactly("TPRJ-1")
    }

    // ── F9. component 다중(OR) 필터 ──────────────────────────────────────────

    @Test
    @Order(18)
    fun `F9 - componentIds 다중 필터는 지정된 컴포넌트 중 하나라도 포함한 이슈를 OR 로 반환한다`() {
        val comp1 = insertComponent("api")
        val comp2 = insertComponent("db")
        val comp3 = insertComponent("ui")
        val i1 = insertIssue(seq = 1, securityLevelId = null)
        val i2 = insertIssue(seq = 2, securityLevelId = null)
        val i3 = insertIssue(seq = 3, securityLevelId = null)
        linkComponent(i1.id.value, comp1)
        linkComponent(i2.id.value, comp2)
        linkComponent(i3.id.value, comp3)

        val filter = BoardCardFilter(componentIds = listOf(comp1, comp2))
        val result = adapterWithFilter(unrestricted(), filter)

        assertThat(result.issues.map { it.key }).containsExactlyInAnyOrder("TPRJ-1", "TPRJ-2")
    }

    // ── F10. 필드간 AND (assignee + label) ───────────────────────────────────

    @Test
    @Order(19)
    fun `F10 - assignee 와 label 필터를 동시에 지정하면 둘 다 만족하는 이슈만 반환한다`() {
        val assignee = UUID.randomUUID()
        val i1 = insertIssue(seq = 1, assigneeId = assignee, securityLevelId = null)
        val i2 = insertIssue(seq = 2, assigneeId = assignee, securityLevelId = null)
        val i3 = insertIssue(seq = 3, securityLevelId = null)
        setLabels(i1.id.value, listOf("bug"))
        setLabels(i3.id.value, listOf("bug"))

        // i1 만 assignee AND bug 동시 만족
        val filter = BoardCardFilter(assigneeIds = listOf(assignee), labels = listOf("bug"))
        val result = adapterWithFilter(unrestricted(), filter)

        assertThat(result.issues.map { it.key }).containsExactly("TPRJ-1")
        // i2 는 assignee O, bug X → 제외
        // i3 는 assignee X, bug O → 제외
    }

    // ── F11. EC2 회귀: EMPTY 필터 = 무필터 ──────────────────────────────────

    @Test
    @Order(20)
    fun `F11 - BoardCardFilter EMPTY 이면 무필터와 동일한 결과를 반환한다`() {
        val assignee = UUID.randomUUID()
        insertIssue(seq = 1, assigneeId = assignee, securityLevelId = null)
        insertIssue(seq = 2, securityLevelId = null)

        val resultNoFilter = adapterWith(unrestricted()).listVisibleIssuesByProject("TPRJ", UUID.randomUUID())
        val resultEmptyFilter = adapterWithFilter(unrestricted(), BoardCardFilter.EMPTY)

        assertThat(resultEmptyFilter.issues.map { it.key })
            .containsExactlyInAnyOrderElementsOf(resultNoFilter.issues.map { it.key })
        assertThat(resultEmptyFilter.truncated).isEqualTo(resultNoFilter.truncated)
    }

    // ── F12. EC8 visibility 우선: 보안등급 이슈는 필터로 지정해도 제외 ────────

    @Test
    @Order(21)
    fun `F12 - viewer 가 볼 수 없는 보안 등급 이슈는 assignee 필터로 지정해도 제외된다`() {
        val viewer = UUID.randomUUID()
        val secretLevel = UUID.randomUUID()
        // viewer 가 assignee 이지만 보안 등급을 볼 수 없는 이슈
        insertIssue(seq = 1, assigneeId = viewer, securityLevelId = secretLevel)
        // 접근 가능한 일반 이슈
        insertIssue(seq = 2, assigneeId = viewer, securityLevelId = null)

        // restricted access — secretLevel 포함 안 함
        val filter = BoardCardFilter(assigneeIds = listOf(viewer))
        val adapter = BoardIssueLookupAdapter(repository, StubSecurityDirectory(restricted()))
        val result = adapter.listVisibleIssuesByProject("TPRJ", viewer, filter)

        // seq=1 은 비가시 등급이므로 제외, seq=2 만 반환
        assertThat(result.issues.map { it.key }).containsExactly("TPRJ-2")
    }
}
