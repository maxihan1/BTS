// 규칙 충돌 1건을 표현하는 값 객체 (FR-AT-04)

package com.bts.automation.domain

import java.util.UUID

/**
 * [com.bts.automation.application.RuleConflictAnalyzer]가 산출하는 규칙 충돌 1건.
 *
 * DB에 영속되지 않는다 — 저장(생성/수정) 응답에 실려 즉시 UI로 전달되는 순수 값 객체다.
 * 모든 필드는 `val`로 선언해 생성 이후 변경할 수 없다.
 *
 * @property type 충돌 종류([ConflictType] 4종 중 하나)
 * @property severity 충돌 심각도. 현재는 항상 [ConflictSeverity.WARNING]
 * @property ruleIds 충돌에 관련된 규칙 ID 목록([of] 팩토리로 생성 시 정렬 보장)
 * @property detail 충돌 내용을 설명하는 사용자 노출용 한국어 메시지
 */
data class RuleConflict(
    val type: ConflictType,
    val severity: ConflictSeverity,
    val ruleIds: List<UUID>,
    val detail: String,
) {
    companion object {
        /**
         * `ruleIds`를 정렬된 순서로 정규화해 [RuleConflict]를 생성하는 팩토리 메서드.
         *
         * 같은 충돌(예: 사이클 A→B→A와 B→A→B)이 규칙 탐색 순서에 따라 다른 `ruleIds` 순서로
         * 산출될 수 있는데, 정렬로 정규화해두면 호출자가 동등성 비교만으로 중복 충돌을 dedup할 수 있다.
         * `ruleIds` 중복 원소는 제거하지 않는다 — dedup은 상위 분석 로직(호출자)의 책임이다.
         *
         * @param type 충돌 종류
         * @param ruleIds 충돌에 관련된 규칙 ID 목록(임의 순서 허용)
         * @param detail 충돌 내용을 설명하는 사용자 노출용 한국어 메시지
         * @param severity 충돌 심각도. 기본값은 [ConflictSeverity.WARNING]
         * @return `ruleIds`가 오름차순 정렬된 [RuleConflict] 인스턴스
         */
        fun of(
            type: ConflictType,
            ruleIds: List<UUID>,
            detail: String,
            severity: ConflictSeverity = ConflictSeverity.WARNING,
        ): RuleConflict =
            RuleConflict(
                type = type,
                severity = severity,
                ruleIds = ruleIds.sorted(),
                detail = detail,
            )
    }
}
