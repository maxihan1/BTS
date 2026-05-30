// IssuePriority / IssueImpact 이름 매핑 단위 테스트 — number↔name 양방향, 범위 밖 입력 처리

package com.bts.issue.domain

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * [IssuePriority] 및 [IssueImpact] 이름 매핑 단위 테스트.
 *
 * Spring 컨텍스트 없이 순수 단위 테스트로 실행한다.
 * 테스트 케이스 (IssuePriority).
 * - priority_number_to_name — 1~5 숫자가 Highest/High/Medium/Low/Lowest 로 매핑된다.
 * - priority_name_to_number — Highest/High/Medium/Low/Lowest 가 1~5 로 역매핑된다.
 * - priority_fromNumber_out_of_range — 0 또는 6 을 fromNumber 에 넘기면 IllegalArgumentException 을 던진다.
 * - priority_fromName_unknown — 미정의 이름을 fromName 에 넘기면 IllegalArgumentException 을 던진다.
 *
 * 테스트 케이스 (IssueImpact).
 * - impact_number_to_name — 1~3 숫자가 High/Medium/Low 로 매핑된다.
 * - impact_name_to_number — High/Medium/Low 가 1~3 으로 역매핑된다.
 * - impact_fromNumber_out_of_range — 0 또는 4 를 fromNumber 에 넘기면 IllegalArgumentException 을 던진다.
 * - impact_fromName_unknown — 미정의 이름을 fromName 에 넘기면 IllegalArgumentException 을 던진다.
 */
class IssuePriorityTest {

    // ─── IssuePriority: number → name ───────────────────────────────────────

    @Test
    fun `priority_number_to_name — 1은 Highest, 2는 High, 3는 Medium, 4는 Low, 5는 Lowest`() {
        assertThat(IssuePriority.fromNumber(1).name).isEqualTo("Highest")
        assertThat(IssuePriority.fromNumber(2).name).isEqualTo("High")
        assertThat(IssuePriority.fromNumber(3).name).isEqualTo("Medium")
        assertThat(IssuePriority.fromNumber(4).name).isEqualTo("Low")
        assertThat(IssuePriority.fromNumber(5).name).isEqualTo("Lowest")
    }

    @Test
    fun `priority_name_to_number — Highest는 1, High는 2, Medium는 3, Low는 4, Lowest는 5`() {
        assertThat(IssuePriority.fromName("Highest").number).isEqualTo(1)
        assertThat(IssuePriority.fromName("High").number).isEqualTo(2)
        assertThat(IssuePriority.fromName("Medium").number).isEqualTo(3)
        assertThat(IssuePriority.fromName("Low").number).isEqualTo(4)
        assertThat(IssuePriority.fromName("Lowest").number).isEqualTo(5)
    }

    @Test
    fun `priority_fromNumber_out_of_range — 0 입력 시 IllegalArgumentException`() {
        assertThatThrownBy { IssuePriority.fromNumber(0) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `priority_fromNumber_out_of_range — 6 입력 시 IllegalArgumentException`() {
        assertThatThrownBy { IssuePriority.fromNumber(6) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `priority_fromName_unknown — 미정의 이름 입력 시 IllegalArgumentException`() {
        assertThatThrownBy { IssuePriority.fromName("Critical") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    // ─── IssueImpact: number → name ─────────────────────────────────────────

    @Test
    fun `impact_number_to_name — 1은 High, 2는 Medium, 3는 Low`() {
        assertThat(IssueImpact.fromNumber(1).name).isEqualTo("High")
        assertThat(IssueImpact.fromNumber(2).name).isEqualTo("Medium")
        assertThat(IssueImpact.fromNumber(3).name).isEqualTo("Low")
    }

    @Test
    fun `impact_name_to_number — High는 1, Medium는 2, Low는 3`() {
        assertThat(IssueImpact.fromName("High").number).isEqualTo(1)
        assertThat(IssueImpact.fromName("Medium").number).isEqualTo(2)
        assertThat(IssueImpact.fromName("Low").number).isEqualTo(3)
    }

    @Test
    fun `impact_fromNumber_out_of_range — 0 입력 시 IllegalArgumentException`() {
        assertThatThrownBy { IssueImpact.fromNumber(0) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `impact_fromNumber_out_of_range — 4 입력 시 IllegalArgumentException`() {
        assertThatThrownBy { IssueImpact.fromNumber(4) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `impact_fromName_unknown — 미정의 이름 입력 시 IllegalArgumentException`() {
        assertThatThrownBy { IssueImpact.fromName("Critical") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
