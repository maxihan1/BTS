// 워크플로우 권한 평가 범위 sealed interface(공용) — 스킴·정의 두 판정기가 함께 쓴다 (FR-WF-08 D8).

package com.bts.shared.permission

/**
 * 워크플로우 권한 평가의 적용 범위. **스킴 판정기와 정의 판정기가 같은 타입을 받는다.**
 *
 * when 식에서 else 분기 없이 컴파일러가 완전성(exhaustiveness)을 보장한다.
 *
 * ## 왜 `WorkflowSchemeScope` 를 일반화하지 않았나 (FR-WF-08 D8)
 *
 * 이 타입은 원래 `WorkflowSchemeScope` 였고 스킴 판정기만 썼다. FR-WF-08 이 워크플로우 **정의**
 * 판정기에도 스코프를 들이면서 두 갈래가 생겼다.
 *
 * - 이름을 그대로 두고 정의 판정기가 그 타입을 받게 하면, 이름이 스킴을 뜻하는데 정의 편집을
 *   담게 된다. [WorkflowDefinitionPermission] 의 KDoc 이 **이미 그 함정을 이름으로 경고**하고
 *   있고, 이 저장소는 같은 이유로 enum 을 둘로 갈라 놓았다.
 * - 반대로 정의 전용 스코프 타입을 따로 두면, 두 타입은 **서로를 검사하지 않는 두 목록**이 된다.
 *   한쪽에만 새 스코프가 추가돼도 다른 쪽은 조용히 통과한다.
 *
 * ⇒ 중립 이름 하나를 두고 둘이 함께 쓴다. 스코프가 늘면 두 판정기가 **같은 컴파일 에러**로 멈춘다.
 *
 * ## 배치 (BC 격리)
 * FR-PM-04 D2 — project-workflow → shared-kernel 로 이동했다. [Project.key] 가 plain String
 * 이라 이동에 BC 의존이 없다(identity 도메인 타입 미참조).
 *
 * ## 구현체 설명
 * - [Global] — 사이트 전역 자원. 예. 전역 공유 스킴·워크플로우, 전역 상태 카탈로그. SYSTEM_ADMIN 소관.
 * - [Project] — 그 프로젝트 소유 자원. 예. 프로젝트 전용 워크플로우 편집, 스킴 배정.
 */
sealed interface WorkflowScope {
    /**
     * 시스템 전역 권한 범위.
     *
     * 프로젝트에 국한되지 않는 자원(전역 공유 스킴·워크플로우, 전역 상태 카탈로그)에 쓴다.
     * data object 이므로 참조 동일성이 보장된다.
     */
    data object Global : WorkflowScope

    /**
     * 특정 프로젝트 권한 범위.
     *
     * 그 프로젝트가 소유한 자원(전용 워크플로우·스킴)과 프로젝트 단위 작업(ASSIGN_SCHEME)에 쓴다.
     *
     * @param key 프로젝트 식별 키. 예. "ATLAS".
     */
    data class Project(val key: String) : WorkflowScope
}
