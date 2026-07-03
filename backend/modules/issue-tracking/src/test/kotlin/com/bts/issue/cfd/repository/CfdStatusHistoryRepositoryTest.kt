// CfdStatusHistoryRepository Testcontainers 통합 테스트 — status 전이 이력 배치 조회 검증 (FR-RP-03 Task 3)

package com.bts.issue.cfd.repository

import com.bts.issue.jooq.tables.references.ISSUE_CHANGE_GROUP
import com.bts.issue.jooq.tables.references.ISSUE_CHANGE_ITEM
import com.bts.issue.repository.IssueTestcontainersBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * [CfdStatusHistoryRepository.fetchStatusChanges] 통합 테스트 (FR-RP-03 Task 3).
 *
 * [IssueTestcontainersBase] 의 JVM singleton PostgreSQL + Flyway 마이그레이션(V018 포함)을 재사용한다.
 * `issue_change_group`/`issue_change_item` 은 `issues` 로 FK 를 두지 않으므로(이력 보존 우선),
 * 임의의 UUID 를 issueId 로 사용해 그룹/항목을 직접 시드한다.
 *
 * ## 검증 시나리오
 * - CFD-T3-1. `field='status'` 항목만 반환한다(같은 그룹의 assignee/summary 변경은 제외).
 * - CFD-T3-2. `(issueId, changedAt ASC, groupId ASC)` 순으로 정렬한다.
 * - CFD-T3-3. 여러 이슈를 배치 조회하면 issueId 별로 결과가 구분된다.
 * - CFD-T3-4. 빈 issueIds 는 빈 리스트를 즉시 반환한다.
 */
class CfdStatusHistoryRepositoryTest : IssueTestcontainersBase() {
    private val cfdRepository: CfdStatusHistoryRepository by lazy { CfdStatusHistoryRepository(dsl) }

    /**
     * `issue_change_group` 1행 + `issue_change_item` N행을 시드하고 group id 를 반환한다.
     *
     * @param issueId 소속 이슈 UUID.
     * @param createdAt 그룹 생성(=전이 발생) 시각. 정렬 검증을 위해 명시적으로 과거 시각을 지정한다.
     * @param items (field, fromValue, toValue) 트리플 목록.
     */
    private fun seedGroup(
        issueId: UUID,
        createdAt: OffsetDateTime,
        items: List<Triple<String, String?, String?>>,
    ): Long {
        val groupId =
            dsl.insertInto(ISSUE_CHANGE_GROUP)
                .set(ISSUE_CHANGE_GROUP.ISSUE_ID, issueId)
                .set(ISSUE_CHANGE_GROUP.ISSUE_KEY, "TPRJ-1")
                .set(ISSUE_CHANGE_GROUP.CREATED_AT, createdAt)
                .returning(ISSUE_CHANGE_GROUP.ID)
                .fetchOne()
                ?.get(ISSUE_CHANGE_GROUP.ID)
                ?: error("issue_change_group INSERT RETURNING id 값이 없음")

        items.forEach { (field, fromValue, toValue) ->
            dsl.insertInto(ISSUE_CHANGE_ITEM)
                .set(ISSUE_CHANGE_ITEM.GROUP_ID, groupId)
                .set(ISSUE_CHANGE_ITEM.FIELD, field)
                .set(ISSUE_CHANGE_ITEM.FROM_VALUE, fromValue)
                .set(ISSUE_CHANGE_ITEM.TO_VALUE, toValue)
                .execute()
        }

        return groupId
    }

    // ── CFD-T3-1. field='status' 항목만 반환 ──────────────────────────────────

    @Test
    fun `CFD-T3-1 - field가 status인 항목만 반환하고 assignee summary 변경은 제외한다`() {
        val issueId = UUID.randomUUID()
        seedGroup(
            issueId = issueId,
            createdAt = OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC),
            items =
                listOf(
                    Triple("status", "open", "in_progress"),
                    Triple("assignee", null, UUID.randomUUID().toString()),
                    Triple("summary", "이전 요약", "새 요약"),
                ),
        )

        val rows = cfdRepository.fetchStatusChanges(setOf(issueId))

        assertThat(rows).hasSize(1)
        val row = rows.single()
        assertThat(row.issueId).isEqualTo(issueId)
        assertThat(row.fromValue).isEqualTo("open")
        assertThat(row.toValue).isEqualTo("in_progress")
    }

    // ── CFD-T3-2. (issueId, changedAt ASC, groupId ASC) 정렬 ──────────────────

    @Test
    fun `CFD-T3-2 - 여러 전이를 changedAt 오름차순 groupId 오름차순으로 반환한다`() {
        val issueId = UUID.randomUUID()
        val t1 = OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC)
        val t2 = OffsetDateTime.of(2026, 1, 2, 0, 0, 0, 0, ZoneOffset.UTC)
        val t3 = OffsetDateTime.of(2026, 1, 3, 0, 0, 0, 0, ZoneOffset.UTC)

        // 시드 순서를 시간 순서와 뒤섞어 정렬이 created_at 기준임을 검증한다.
        seedGroup(issueId, t3, listOf(Triple("status", "in_progress", "done")))
        seedGroup(issueId, t1, listOf(Triple("status", "open", "in_progress")))
        seedGroup(issueId, t2, listOf(Triple("status", "in_progress", "in_review")))

        val rows = cfdRepository.fetchStatusChanges(setOf(issueId))

        assertThat(rows).hasSize(3)
        assertThat(rows.map { it.toValue }).containsExactly("in_progress", "in_review", "done")
        assertThat(rows.map { it.changedAt }).isSorted
    }

    // ── CFD-T3-3. 여러 이슈 배치 조회 ──────────────────────────────────────────

    @Test
    fun `CFD-T3-3 - 여러 이슈를 배치 조회하면 issueId 별로 결과가 구분된다`() {
        val issueId1 = UUID.randomUUID()
        val issueId2 = UUID.randomUUID()
        val baseTime = OffsetDateTime.of(2026, 2, 1, 0, 0, 0, 0, ZoneOffset.UTC)

        seedGroup(issueId1, baseTime, listOf(Triple("status", "open", "done")))
        seedGroup(issueId2, baseTime, listOf(Triple("status", "open", "in_progress")))

        val rows = cfdRepository.fetchStatusChanges(setOf(issueId1, issueId2))

        assertThat(rows).hasSize(2)
        assertThat(rows.map { it.issueId }).containsExactlyInAnyOrder(issueId1, issueId2)

        val row1 = rows.first { it.issueId == issueId1 }
        assertThat(row1.toValue).isEqualTo("done")
        val row2 = rows.first { it.issueId == issueId2 }
        assertThat(row2.toValue).isEqualTo("in_progress")
    }

    // ── CFD-T3-4. 빈 issueIds → 빈 리스트 ─────────────────────────────────────

    @Test
    fun `CFD-T3-4 - 빈 issueIds로 조회하면 빈 리스트를 반환한다`() {
        val rows = cfdRepository.fetchStatusChanges(emptySet())

        assertThat(rows).isEmpty()
    }
}
