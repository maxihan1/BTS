// 여러 이슈의 가용 전이 목록에서 toStateKey 기준 교집합을 구하는 순수 함수

package com.bts.issue.bulk.application

import com.bts.shared.workflow.AvailableTransitionView

/**
 * 여러 이슈의 가용 전이 교집합 연산 객체.
 *
 * 프론트엔드 `lib/transition-intersection.ts`의 `intersectTransitions` 시맨틱을 Kotlin으로 1:1 포팅한다.
 * 결과 순서는 첫 번째 이슈의 등장 순서를 따르며, 교집합 항목의 필드값은 첫 번째 이슈 기준으로 채택한다.
 */
object TransitionIntersection {

    /**
     * 이슈별 가용 전이 목록의 교집합을 반환한다.
     *
     * @param perIssue 이슈마다 조회한 가용 전이 목록의 목록. 각 원소가 한 이슈의 전이 목록이다.
     * @return 모든 이슈에 공통으로 존재하는 전이 목록. 순서는 첫 번째 이슈의 등장 순서.
     *         [perIssue] 가 비어 있거나 어느 이슈의 목록이 비어 있으면 빈 목록을 반환한다.
     */
    fun intersect(perIssue: List<List<AvailableTransitionView>>): List<AvailableTransitionView> {
        if (perIssue.isEmpty()) return emptyList()

        val first = perIssue.first()
        if (first.isEmpty()) return emptyList()

        val rest = perIssue.drop(1)

        // 나머지 이슈 중 빈 목록이 하나라도 있으면 교집합은 항상 비어 있다.
        if (rest.any { it.isEmpty() }) return emptyList()

        // 나머지 각 이슈의 toStateKey Set 을 미리 구한다.
        val restSets: List<Set<String>> = rest.map { transitions ->
            transitions.mapTo(mutableSetOf()) { it.toStateKey }
        }

        val seen = mutableSetOf<String>()
        val result = mutableListOf<AvailableTransitionView>()

        for (transition in first) {
            val key = transition.toStateKey

            // dedup — 첫 번째 이슈 내에 동일 toStateKey 가 여러 번 등장하면 첫 번째만 취한다.
            if (!seen.add(key)) continue

            // 모든 나머지 이슈의 Set 에 이 toStateKey 가 존재해야 교집합 원소다.
            if (restSets.all { key in it }) {
                result.add(transition)
            }
        }

        return result
    }
}
