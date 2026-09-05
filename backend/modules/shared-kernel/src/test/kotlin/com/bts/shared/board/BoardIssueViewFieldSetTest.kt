// cross-BC 포트 VO BoardIssueView 의 필드 이름 집합을 정확히 고정하는 판별식 (부채 177 Task 14 주 방어선)

package com.bts.shared.board

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import kotlin.reflect.full.memberProperties

/**
 * [BoardIssueView] 필드 이름 집합 고정 판별식.
 *
 * ### 왜 있나 — 이 PR 이 만드는 결합은 직접 import 가 아니다
 *
 * 「agile-planning 이 issue-tracking 을 직접 import 하지 않는다」는 이미 세 겹으로 막혀 있다.
 * ① agile-planning 의 gradle 의존에 issue-tracking 이 없어 **컴파일 자체가 안 되고**
 * ② [com.bts.agileplanning.architecture.AgilePlanningBcArchTest] 룰 1 이 바이트코드 의존을 막고
 * ③ `scripts/workflow/bc-isolation-agile-planning.test.ts` 가 소스 텍스트를 훑는다.
 *
 * 그런데 부채 177 이 **실제로 넓힌** 결합은 그 축이 아니라 **shared-kernel 포트 경유**다 —
 * [BoardIssueView.customFields] 가 그것이다. 커스텀 필드는 issue-tracking BC 소유인데
 * 이 VO 를 타고 agile-planning 으로 흘러 들어온다. 직접 import 판별식은 그 흐름 앞에서
 * **초록인 채로 통과**한다(리뷰 BLOCKER B1).
 *
 * 포트를 넓히는 것 자체는 허용된 경로다. 그러므로 처방은 금지가 아니라 **가시화**다 —
 * 필드가 하나라도 늘거나 줄면 이 테스트가 red 가 되어, cross-BC 계약 변경이
 * 리뷰어의 눈을 반드시 거치게 한다.
 *
 * ### [BoardIssueViewContractTest] 와의 분담
 *
 * 그쪽은 **주 생성자 파라미터의 순서·optional 여부**를 본다(위치 인자 호출자 보호).
 * 이쪽은 [kotlin.reflect.full.memberProperties] 로 **프로퍼티 이름 집합 전체**를 본다 —
 * 주 생성자 밖에서 `val` 로 덧붙인 프로퍼티까지 잡히므로 축이 다르다.
 *
 * ### 부분집합이 아니라 정확한 동등이다
 *
 * `containsAll` 로 두면 필드가 늘어도 초록이라 판별식이 존재 의미를 잃는다.
 * 그래서 양방향 차집합 0(= [org.assertj.core.api.IterableAssert.containsExactlyInAnyOrderElementsOf])만 쓴다.
 */
class BoardIssueViewFieldSetTest {
    /**
     * 현재 cross-BC 계약에 실린 [BoardIssueView] 의 필드 이름 전량.
     *
     * ★이 목록을 고칠 때는 「왜 늘었나」를 PR 설명에 적어라. 그것이 이 판별식의 유일한 목적이다.
     */
    private val expectedFieldNames =
        setOf(
            "key",
            "summary",
            "currentStateKey",
            "assigneeId",
            "priority",
            "version",
            "typeKey",
            "epicKey",
            "rank",
            "labels",
            "originalEstimateSeconds",
            // ★부채 177 Task 5 가 넓힌 자리 — 커스텀 필드 값은 issue-tracking BC 소유이고
            //   agile-planning 은 그대로 미러 노출만 한다(`rank` 와 같은 취급).
            "customFields",
        )

    @Test
    fun `BoardIssueView 의 필드 이름 집합이 계약 목록과 정확히 일치한다`() {
        val actualFieldNames = BoardIssueView::class.memberProperties.map { it.name }.toSet()

        assertThat(actualFieldNames)
            .`as`(
                "cross-BC 포트 VO 의 필드가 바뀌었다 — 계약 변경이면 expectedFieldNames 를 함께 고치고 " +
                    "PR 설명에 사유를 남겨라 (부채 177 Task 14 · 리뷰 BLOCKER B1)",
            ).containsExactlyInAnyOrderElementsOf(expectedFieldNames)
    }

    @Test
    fun `계약 목록이 비어 있지 않다 — 빈 집합끼리 비교하는 가짜 초록 차단`() {
        // expectedFieldNames 가 실수로 비면 위 단언은 「실제도 비었나」만 보게 되고,
        // 리플렉션이 아무것도 못 읽는 상황과 구분되지 않는다.
        assertThat(expectedFieldNames).hasSize(12)
    }

    @Test
    fun `리플렉션이 실제 클래스를 읽는다 — 카나리 필드 key 와 currentStateKey 가 잡힌다`() {
        // 개수 단언만으로는 「리플렉션이 다른 것을 읽고 있다」를 못 가른다.
        // 포트가 존재하는 한 절대 사라지지 않는 두 필드를 카나리로 못박는다.
        val actualFieldNames = BoardIssueView::class.memberProperties.map { it.name }.toSet()

        assertThat(actualFieldNames).contains("key", "currentStateKey")
    }
}
