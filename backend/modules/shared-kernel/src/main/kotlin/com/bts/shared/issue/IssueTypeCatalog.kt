// 전역 이슈타입 전체 목록 조회 SPI — Import 값 매핑 위저드가 이슈타입 후보를 얻기 위해 사용 (WorkflowStateCatalog 미러)

package com.bts.shared.issue

import org.springframework.transaction.annotation.Transactional

/**
 * 전역 이슈타입 전체 목록을 반환하는 SPI.
 *
 * Import(CSV/JSON) 값 매핑 위저드가 소스 파일의 이슈타입 컬럼 값을 BTS 이슈타입에 매핑하기 위해
 * 후보 목록을 조회할 때 호출한다. `com.bts.shared.workflow.WorkflowStateCatalog` 를 미러한
 * 읽기 전용 published language SPI 다.
 *
 * ### 이슈타입은 전역(global) 스코프
 *
 * `issue_types` 테이블에는 project 스코프 컬럼이 없다 — 이슈타입은 프로젝트별이 아니라
 * 워크스페이스 전체에서 공유되는 전역 카탈로그다. 따라서 이 SPI 는 프로젝트 파라미터를
 * 받지 않는다.
 *
 * ### 구현 책임
 *
 * - 구현체. `issue-tracking` BC 의 `IssueTypeCatalogAdapter`.
 * - consumer. `search-export-import` BC 의 Import 값 매핑 서비스 (FR-IM-02 PR-C).
 *
 * ### 읽기 전용 · 부수 효과 없음
 *
 * 이 SPI 는 DB 쓰기(INSERT/UPDATE/DELETE) 를 일절 수행하지 않는다.
 *
 * ADR 근거. `docs/decisions/2026-05-27-shared-kernel-extraction.md`,
 * `docs/adr/2026-05-28-workflow-scheme-frontend-view-layer-cross-bc-lookup.md`
 * (`WorkflowStateCatalog` 도입 결정과 동일 정신).
 *
 * @see IssueTypeRef
 */
interface IssueTypeCatalog {
    /**
     * 전역 이슈타입 전체 목록을 반환한다.
     *
     * **읽기 전용 · 부수 효과 없음.** 이 메서드는 DB 쓰기나 이벤트 발행을 수행하지 않는다.
     *
     * @return 활성(소프트 삭제되지 않은) 이슈타입 [IssueTypeRef] 리스트. 순서는 구현체가 정의한다.
     *   이슈타입이 없으면 빈 리스트를 반환한다.
     */
    @Transactional(readOnly = true)
    fun listTypes(): List<IssueTypeRef>
}
