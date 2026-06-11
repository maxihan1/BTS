# FR-HS-02 — 히스토리 조회 UI

> slug: fr-hs-02-history-ui
> type: feature
> primary_bc: issue-tracking
> agent: backend-engineer (주) + frontend-engineer (UI) + qa-engineer (E2E)
> plan_slug: issue/history-ui
> 생성: 2026-06-11

## Brief

FR-HS-02 — 이슈 변경 이력 **조회 UI**. 선행 FR-HS-01(PR #115, 기록 전용)이 `issue_change_group` + `issue_change_item` 2테이블에 append-only로 적재한 이력을 조회·표시한다.

product 문서 `docs/plan/product/issue-tracking.md §5.1.2` D1~D7:
- D1. 도메인 (backend-engineer)
- D2. 명세 — 페이지네이션, 필드 필터 (backend-engineer)
- D3. 데이터 모델 — (활용, 신규 테이블 없음) (db-engineer)
- D4. 백엔드 — `GET /api/v1/issues/{key}/history` (backend-engineer)
- D5. 백엔드 테스트 (backend-engineer)
- D6. 프론트 UI — 타임라인 형식 (designer → frontend-engineer)
- D7. E2E (qa-engineer)

classify-task가 'E2E' 키워드로 qa 오판 → product 문서 근거 feature로 정정.

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
