// AqlFields.SORTABLE_FIELDS ↔ IssueRepository.buildOrderBy drift 가드 테스트 (FR-SR-04 S2)

package com.bts.issue.repository

import com.bts.shared.permission.IssueSecurityAccess
import com.bts.shared.search.AqlField
import com.bts.shared.search.AqlFields
import com.bts.shared.search.AqlNode
import com.bts.shared.search.AqlOperator
import com.bts.shared.search.AqlSort
import com.bts.shared.search.AqlValue
import com.bts.shared.search.SortDirection
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * AqlFields.SORTABLE_FIELDS ↔ IssueRepository.buildOrderBy drift 가드 (FR-SR-04 S2).
 *
 * SORTABLE_FIELDS(shared-kernel) 와 buildOrderBy when 분기(issue-tracking) 는 모듈이 달라
 * 자동 가드가 없다. 한쪽에만 필드를 추가하면 silent drift 가 발생한다.
 * (BTS 반복 사고 패턴 — enum/화이트리스트 카운트 가드 깨짐과 동형)
 *
 * 이 테스트는 SORTABLE_FIELDS ⊆ buildOrderBy 처리집합 관계를 코드로 박제한다.
 * SORTABLE_FIELDS 에 필드를 추가하고 buildOrderBy when 분기를 추가하지 않으면 이 테스트가 깨진다.
 *
 * buildOrderBy 는 selectCount() 보다 먼저 호출되므로 이슈 시드 없이도 검증 가능하다.
 * (B1-REG-01 동일 패턴 — 정렬 검증은 DB 상태에 무관하게 항상 실행됨)
 *
 * 역방향(buildOrderBy 추가 → SORTABLE_FIELDS 미추가) 미가드 이유.
 *   buildOrderBy 에 "due_date" 를 추가해도 AqlParser 가 SORTABLE_FIELDS 로 먼저 검증하므로
 *   ORDER BY due_date AQL 쿼리는 AqlSyntaxException 으로 차단된다.
 *   따라서 역방향은 기능적으로 무해하며 별도 가드 불필요.
 */
class AqlSortableFieldsDriftTest : IssueTestcontainersBase() {
    /** unrestricted=true 접근권한 — 보안등급 필터 미적용. */
    private val unrestrictedAccess =
        IssueSecurityAccess(
            unrestricted = true,
            staticLevelIds = emptySet(),
            reporterLevelIds = emptySet(),
            assigneeLevelIds = emptySet(),
        )

    /**
     * 주어진 정렬 필드로 searchByAql 을 호출하는 헬퍼.
     *
     * buildOrderBy 는 selectCount() 보다 먼저 실행되므로
     * DB 에 이슈가 없어도 정렬 검증이 즉시 수행된다.
     */
    private fun callSearchByAqlWithSort(fieldName: String) {
        repository.searchByAql(
            projectKey = "TPRJ",
            ast =
                AqlNode.Comparison(
                    field = AqlField("text"),
                    op = AqlOperator.CONTAINS,
                    values = listOf(AqlValue.Str("x")),
                ),
            sort = listOf(AqlSort(AqlField(fieldName), SortDirection.ASC)),
            actor = UUID.randomUUID(),
            access = unrestrictedAccess,
            page = 0,
            size = 10,
        )
    }

    /**
     * SORTABLE_FIELDS 의 모든 필드로 정렬 시 searchByAql 이 예외 없이 성공한다.
     *
     * SORTABLE_FIELDS ⊆ buildOrderBy 처리집합 단언.
     * SORTABLE_FIELDS 에 필드를 추가하고 buildOrderBy when 분기를 누락하면
     * IllegalArgumentException 이 발생해 이 테스트가 깨진다.
     *
     * 현재 SORTABLE_FIELDS: status, summary, priority, created_at, updated_at (5개).
     */
    @Test
    fun `SORTABLE_FIELDS 의 모든 필드로 정렬 시 searchByAql 이 예외 없이 성공한다`() {
        // vacuous 가드: SORTABLE_FIELDS 가 비어 있으면 루프가 실행되지 않아 아무것도 검증하지 않는다.
        assertThat(AqlFields.SORTABLE_FIELDS)
            .describedAs("SORTABLE_FIELDS 가 비어 있으면 이 루프는 vacuous — 최소 1개 이상이어야 한다")
            .isNotEmpty()

        for (fieldName in AqlFields.SORTABLE_FIELDS) {
            val thrownException = runCatching { callSearchByAqlWithSort(fieldName) }.exceptionOrNull()
            assertThat(thrownException)
                .describedAs(
                    "SORTABLE_FIELDS['$fieldName'] 로 정렬 시 예외가 없어야 한다. " +
                        "buildOrderBy when 분기가 누락됐으면 IllegalArgumentException 이 발생한다.",
                )
                .isNull()
        }
    }
}
