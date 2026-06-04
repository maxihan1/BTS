# FR-CM-02 — 이슈에 다중 컴포넌트 할당

> slug: fr-cm-02-issue-components
> type: feature
> agent: backend-engineer (+ db-engineer, frontend-engineer, qa-engineer)
> primary BC: issue-tracking
> 생성: 2026-06-04

## Brief

FR-CM-02 이슈에 다중 컴포넌트 할당. 한 이슈에 여러 컴포넌트(프로젝트 하위 영역 분류)를 붙인다.

- 선행: FR-CM-01(컴포넌트 CRUD, PR #59/#64) · FR-IS-03(담당자 전용 서브리소스 PATCH, PR #49/#51) · FR-PM-03(컴포넌트 권한 prod resolver, PR #70/#72) 모두 완료.
- D1 도메인: Issue Aggregate에 componentIds 다중 연결.
- D3 데이터: issue_components 다대다 테이블 (+ jOOQ init_codegen 미러).
- D4 백엔드: 이슈 컴포넌트 할당/해제 엔드포인트 (FR-IS-03 전용 서브리소스 패턴).
- D6 프론트: 다중 컴포넌트 셀렉터 UI.
- D7 E2E: Playwright.

분류 메모: classify-task 키워드 휴리스틱이 qa/migration으로 오분류 → Maxi 확인 후 feature 전체체인으로 override.

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
