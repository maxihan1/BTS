// CfdCalculator 순수 도메인 계산 로직 단위테스트 (초기상태 접힘·전이·재오픈·창 경계 엣지)

package com.bts.issue.cfd.domain

import com.bts.issue.statushistory.StatusCategory
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * [CfdCalculator] 순수 함수 단위테스트.
 *
 * 외부 의존(DB, Spring, mock) 없이 원시 타입/VO 입출력만 검증한다.
 *
 * 검증 시나리오.
 * - 항상 TODO인 단일 이슈, 창 전 구간 카운트 1
 * - 창 내 TODO→IN_PROGRESS→DONE 전이로 띠 이동
 * - 창 이전 생성된 이슈는 from 시점 상태로 역산되어 접힘
 * - 창 이후 생성된 이슈는 제외
 * - DONE→IN_PROGRESS 재오픈으로 doneCount 감소(비-단조 증가 검증)
 * - 빈 이슈 목록은 전 구간 0
 * - 창 경계 양끝 포함(inclusive)
 * - 결과는 날짜 오름차순 정렬
 * - fromCategoryString 은 미지정/null 값을 TODO 로 폴백
 */
class CfdCalculatorTest {
    @Test
    fun `single always-TODO issue counts 1 in todo every day`() {
        val from = LocalDate.of(2026, 7, 1)
        val to = LocalDate.of(2026, 7, 5)
        val issue =
            CfdIssueTimeline(
                createdDate = from,
                segments = listOf(CfdSegment(from, StatusCategory.TODO)),
            )

        val points = CfdCalculator.calculate(from, to, listOf(issue))

        assertThat(points).hasSize(5)
        assertThat(points).allSatisfy { point ->
            assertThat(point.todoCount).isEqualTo(1)
            assertThat(point.inProgressCount).isEqualTo(0)
            assertThat(point.doneCount).isEqualTo(0)
        }
    }

    @Test
    fun `issue transitions TODO to IN_PROGRESS to DONE mid-window shifts bands`() {
        val from = LocalDate.of(2026, 7, 1)
        val to = LocalDate.of(2026, 7, 6)
        val issue =
            CfdIssueTimeline(
                createdDate = from,
                segments =
                    listOf(
                        CfdSegment(LocalDate.of(2026, 7, 1), StatusCategory.TODO),
                        CfdSegment(LocalDate.of(2026, 7, 3), StatusCategory.IN_PROGRESS),
                        CfdSegment(LocalDate.of(2026, 7, 5), StatusCategory.DONE),
                    ),
            )

        val points = CfdCalculator.calculate(from, to, listOf(issue))
        val byDate = points.associateBy { it.date }

        assertThat(byDate.getValue(LocalDate.of(2026, 7, 1)).todoCount).isEqualTo(1)
        assertThat(byDate.getValue(LocalDate.of(2026, 7, 2)).todoCount).isEqualTo(1)
        assertThat(byDate.getValue(LocalDate.of(2026, 7, 3)).inProgressCount).isEqualTo(1)
        assertThat(byDate.getValue(LocalDate.of(2026, 7, 4)).inProgressCount).isEqualTo(1)
        assertThat(byDate.getValue(LocalDate.of(2026, 7, 5)).doneCount).isEqualTo(1)
        assertThat(byDate.getValue(LocalDate.of(2026, 7, 6)).doneCount).isEqualTo(1)
        // 이동한 날짜엔 이전 카테고리가 0으로 빠져야 한다 (합계=1 유지)
        assertThat(byDate.getValue(LocalDate.of(2026, 7, 3)).todoCount).isEqualTo(0)
        assertThat(byDate.getValue(LocalDate.of(2026, 7, 5)).inProgressCount).isEqualTo(0)
    }

    @Test
    fun `issue created before window uses reconstructed status at from`() {
        val from = LocalDate.of(2026, 7, 10)
        val to = LocalDate.of(2026, 7, 15)
        val issue =
            CfdIssueTimeline(
                createdDate = LocalDate.of(2026, 7, 1),
                segments =
                    listOf(
                        CfdSegment(LocalDate.of(2026, 7, 1), StatusCategory.TODO),
                        CfdSegment(LocalDate.of(2026, 7, 5), StatusCategory.IN_PROGRESS),
                        CfdSegment(LocalDate.of(2026, 7, 12), StatusCategory.DONE),
                    ),
            )

        val points = CfdCalculator.calculate(from, to, listOf(issue))
        val byDate = points.associateBy { it.date }

        // 07-05 전이가 창(07-10) 이전이므로 from 시점 상태는 IN_PROGRESS 로 접혀 들어간다
        assertThat(byDate.getValue(LocalDate.of(2026, 7, 10)).inProgressCount).isEqualTo(1)
        assertThat(byDate.getValue(LocalDate.of(2026, 7, 10)).todoCount).isEqualTo(0)
        assertThat(byDate.getValue(LocalDate.of(2026, 7, 11)).inProgressCount).isEqualTo(1)
        // 07-12 전이는 창 내부이므로 그날부터 DONE 으로 반영된다
        assertThat(byDate.getValue(LocalDate.of(2026, 7, 12)).doneCount).isEqualTo(1)
        assertThat(byDate.getValue(LocalDate.of(2026, 7, 12)).inProgressCount).isEqualTo(0)
        assertThat(byDate.getValue(LocalDate.of(2026, 7, 15)).doneCount).isEqualTo(1)
    }

    @Test
    fun `issue created after to is excluded`() {
        val from = LocalDate.of(2026, 7, 1)
        val to = LocalDate.of(2026, 7, 5)
        val issue =
            CfdIssueTimeline(
                createdDate = LocalDate.of(2026, 7, 10),
                segments = listOf(CfdSegment(LocalDate.of(2026, 7, 10), StatusCategory.TODO)),
            )

        val points = CfdCalculator.calculate(from, to, listOf(issue))

        assertThat(points).hasSize(5)
        assertThat(points).allSatisfy { point ->
            assertThat(point.todoCount).isEqualTo(0)
            assertThat(point.inProgressCount).isEqualTo(0)
            assertThat(point.doneCount).isEqualTo(0)
        }
    }

    @Test
    fun `reopen DONE to IN_PROGRESS decreases doneCount`() {
        val from = LocalDate.of(2026, 7, 1)
        val to = LocalDate.of(2026, 7, 6)
        val issue =
            CfdIssueTimeline(
                createdDate = from,
                segments =
                    listOf(
                        CfdSegment(LocalDate.of(2026, 7, 1), StatusCategory.TODO),
                        CfdSegment(LocalDate.of(2026, 7, 2), StatusCategory.DONE),
                        CfdSegment(LocalDate.of(2026, 7, 4), StatusCategory.IN_PROGRESS),
                    ),
            )

        val points = CfdCalculator.calculate(from, to, listOf(issue))
        val byDate = points.associateBy { it.date }

        assertThat(byDate.getValue(LocalDate.of(2026, 7, 2)).doneCount).isEqualTo(1)
        assertThat(byDate.getValue(LocalDate.of(2026, 7, 3)).doneCount).isEqualTo(1)
        // 재오픈 — doneCount 가 1 -> 0 으로 감소한다 (monotonic 아님)
        assertThat(byDate.getValue(LocalDate.of(2026, 7, 4)).doneCount).isEqualTo(0)
        assertThat(byDate.getValue(LocalDate.of(2026, 7, 4)).inProgressCount).isEqualTo(1)
        assertThat(byDate.getValue(LocalDate.of(2026, 7, 6)).inProgressCount).isEqualTo(1)
    }

    @Test
    fun `empty issue list yields all-zero points across window`() {
        val from = LocalDate.of(2026, 7, 1)
        val to = LocalDate.of(2026, 7, 5)

        val points = CfdCalculator.calculate(from, to, emptyList())

        assertThat(points).hasSize(5)
        assertThat(points).allSatisfy { point ->
            assertThat(point.todoCount).isEqualTo(0)
            assertThat(point.inProgressCount).isEqualTo(0)
            assertThat(point.doneCount).isEqualTo(0)
        }
    }

    @Test
    fun `window bounds inclusive both ends`() {
        val from = LocalDate.of(2026, 7, 1)
        val to = LocalDate.of(2026, 7, 10)

        val points = CfdCalculator.calculate(from, to, emptyList())

        assertThat(points).hasSize(10)
        assertThat(points.first().date).isEqualTo(from)
        assertThat(points.last().date).isEqualTo(to)

        // 단일일 창(from == to)도 0 나눗셈 없이 정확히 1개 지점을 산출해야 한다
        val singleDayPoints = CfdCalculator.calculate(from, from, emptyList())
        assertThat(singleDayPoints).hasSize(1)
        assertThat(singleDayPoints.single().date).isEqualTo(from)
    }

    @Test
    fun `points sorted ascending by date`() {
        val from = LocalDate.of(2026, 7, 1)
        val to = LocalDate.of(2026, 7, 5)
        val issue =
            CfdIssueTimeline(
                createdDate = from,
                segments = listOf(CfdSegment(from, StatusCategory.TODO)),
            )

        val points = CfdCalculator.calculate(from, to, listOf(issue))

        assertThat(points.map { it.date }).isSorted()
        assertThat(points.zipWithNext()).allSatisfy { (prev, next) ->
            assertThat(prev.date).isBefore(next.date)
        }
    }

    @Test
    fun `fromCategoryString maps unknown or null to TODO`() {
        assertThat(StatusCategory.fromCategoryString("DONE")).isEqualTo(StatusCategory.DONE)
        assertThat(StatusCategory.fromCategoryString("IN_PROGRESS")).isEqualTo(StatusCategory.IN_PROGRESS)
        assertThat(StatusCategory.fromCategoryString("TODO")).isEqualTo(StatusCategory.TODO)
        // 대소문자 무관
        assertThat(StatusCategory.fromCategoryString("done")).isEqualTo(StatusCategory.DONE)
        // 비표준/null → TODO 폴백
        assertThat(StatusCategory.fromCategoryString("FOO")).isEqualTo(StatusCategory.TODO)
        assertThat(StatusCategory.fromCategoryString(null)).isEqualTo(StatusCategory.TODO)
    }
}
