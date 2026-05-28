// 이슈 타입 read-only 뷰 — BC 격리 outbound port 계약용 shared-kernel 정본

package com.bts.shared.issue

/**
 * 이슈 타입 read-only 뷰 (shared-kernel).
 *
 * project-workflow BC 가 issue-tracking BC 의 IssueType 정보를 view layer 용으로만 참조할 때
 * 사용하는 경량 DTO 다.
 *
 * ## 설계 결정
 *
 * - issue-tracking 의 도메인 엔티티 [com.bts.issue.type.domain.IssueType] 을 직접 import 하면
 *   BC 격리 룰(ArchUnit 빌드 실패) 을 위반한다.
 * - 대신 shared-kernel 에 `key + name` 만 담은 최소 DTO 를 두고,
 *   issue-tracking 의 [com.bts.issue.type.adapter.outbound.IssueTypeLookupAdapter] 가
 *   IssueType → IssueTypeRef 변환을 담당한다.
 * - project-workflow 는 이 타입만 의존하므로 BC 역방향 의존이 발생하지 않는다.
 *
 * 결정 근거: docs/adr/2026-05-28-workflow-scheme-frontend-view-layer-cross-bc-lookup.md
 *
 * @property key URL-safe 소문자 슬러그 키. 예: `"task"`, `"bug"`.
 * @property name 표시 이름. 예: `"Task"`, `"Bug"`.
 */
data class IssueTypeRef(
    val key: String,
    val name: String,
)
