// 보드 카드 필터 VO — 담당자·미할당·라벨·컴포넌트·워크플로우 상태·이슈 키 필터 조건을 담는 불변 값 객체

package com.bts.shared.board

import java.util.UUID

/**
 * 보드 카드 필터 VO (Value Object).
 *
 * 보드 카드(이슈) 목록 조회 시 적용할 필터 조건을 담는 불변 값 객체.
 * 이 VO 는 순수 데이터만 담으며, 필터 술어를 직접 평가하지 않는다.
 *
 * ### 필드 조합 규칙
 *
 * 동일 필드 내 값들은 **OR** 로 해석한다.
 * 예: `assigneeIds = [A, B]` 이면 담당자가 A 또는 B 인 이슈를 포함한다.
 * 예: `statusKeys = ["TODO", "IN_PROGRESS"]` 이면 상태가 TODO 또는 IN_PROGRESS 인 이슈를 포함한다.
 *
 * 서로 다른 필드 간은 **AND** 로 해석한다.
 * 예: `assigneeIds = [A]` 이고 `labels = ["bug"]` 이면
 * 담당자가 A 이면서 라벨이 "bug" 인 이슈만 포함한다.
 *
 * 이 AND/OR 술어는 소비측(issue-tracking SQL adapter)이 SQL 수준에서 적용하며,
 * 이 VO 는 조건 값을 전달하는 역할만 한다.
 *
 * ### 빈 필터
 *
 * 모든 필드가 기본값이면 [isEmpty] 가 true 이며, 무필터와 동일하다.
 * [EMPTY] 싱글턴을 사용하면 된다.
 *
 * @property assigneeIds 담당자 UUID 목록. 비어 있으면 담당자 필터 미적용.
 * @property includeUnassigned true 이면 미배정(담당자 없는) 이슈를 포함한다.
 *   [assigneeIds] 와 함께 지정하면 '지정된 담당자들 또는 미배정'으로 OR 결합된다.
 * @property labels 라벨 이름 목록. 비어 있으면 라벨 필터 미적용.
 * @property componentIds 컴포넌트 UUID 목록. 비어 있으면 컴포넌트 필터 미적용.
 * @property statusKeys 워크플로우 상태 키 목록. 비어 있으면 상태 필터 미적용.
 *   같은 필드 내 값들은 OR 로 해석한다.
 * @property issueKeys 이슈 키 화이트리스트. 비어 있으면 키 필터 미적용(다른 필드와 같은 규약).
 *
 *   🛑 **서버 내부 전용이다.** [statusKeys] 와 같은 취급으로 `BoardFilterQueryParser` 가 이 필드를
 *   **파싱하지도 직렬화하지도 않는다** — 클라이언트가 쿼리 파라미터로 이슈 키를 주입할 경로도,
 *   퀵필터 문자열에 새어 나갈 경로도 없다.
 *
 *   용도는 스크럼 보드다. 활성 스프린트의 이슈 키를 여기 실어 **`BOARD_CARD_FETCH_LIMIT` 자르기보다
 *   앞에서** SQL 술어로 거른다. 싣지 않으면 프로젝트 이슈를 1,001건 먼저 자른 뒤 스프린트로 거르게
 *   되어 오래된 활성 스프린트 이슈가 경고 없이 증발한다 (FR-BD-04 PR ④).
 *
 *   ⚠️ **빈 목록은 「해당 이슈 없음」이 아니라 「필터 미적용」이다.** 스프린트 이슈가 0건일 때 빈
 *   목록을 실어 보내면 전량 조회가 된다 — 호출자가 그 분기를 스스로 단락시켜야 한다.
 */
data class BoardCardFilter(
    /** 담당자 UUID 목록. 비어 있으면 담당자 필터 미적용. */
    val assigneeIds: List<UUID> = emptyList(),
    /** true 이면 미배정 이슈를 결과에 포함한다. */
    val includeUnassigned: Boolean = false,
    /** 라벨 이름 목록. 비어 있으면 라벨 필터 미적용. */
    val labels: List<String> = emptyList(),
    /** 컴포넌트 UUID 목록. 비어 있으면 컴포넌트 필터 미적용. */
    val componentIds: List<UUID> = emptyList(),
    /** 워크플로우 상태 키 목록. 비어 있으면 상태 필터 미적용. */
    val statusKeys: List<String> = emptyList(),
    /** 이슈 키 화이트리스트. 비어 있으면 키 필터 미적용. **서버 내부 전용** — KDoc 참조. */
    val issueKeys: List<String> = emptyList(),
) {
    /**
     * 모든 필드가 기본값(빈 목록, false)이면 true 를 반환한다.
     *
     * 빈 필터는 무필터와 동일하므로 소비측이 이 값을 확인해 최적화하거나
     * 3-인자 default 위임([BoardIssueLookupPort.listVisibleIssuesByProject])이
     * 사실상 무필터임을 명시할 때 사용한다.
     *
     * @return 필터 조건이 하나도 없으면 true.
     */
    fun isEmpty(): Boolean {
        return assigneeIds.isEmpty() &&
            !includeUnassigned &&
            labels.isEmpty() &&
            componentIds.isEmpty() &&
            statusKeys.isEmpty() &&
            issueKeys.isEmpty()
    }

    companion object {
        /** 필터 조건이 없는 기본 인스턴스. 무필터 호출 시 재사용한다. */
        val EMPTY: BoardCardFilter = BoardCardFilter()
    }
}
