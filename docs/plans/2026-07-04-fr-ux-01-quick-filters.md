# FR-UX-01 — 퀵 필터 (보드 상단 즉시 필터)

> slug: fr-ux-01-quick-filters
> type: feature (백엔드 D1~D5 + 프론트 D6 + E2E D7 풀스택)
> agent: backend-engineer (진입점) → frontend-engineer (D6) → qa-engineer (D7)
> primary_bc: agile-planning (잠정 — board_quick_filters가 board_id 종속, bts-domain에서 확정)
> 생성: 2026-07-04

## Brief

사용자 원문: "fr-ux-01 진행" (FR-UX-01 퀵 필터).

product 문서 근거 (`docs/plan/product/personalization.md §4.1`):
- **우선순위**: 필수 | **선행**: agile-planning §2.1 (칸반보드) | **Plan slug(문서)**: `personal/quick-filters`
- D1. 도메인 — QuickFilter (backend-engineer)
- D2. 명세 — 보드별 사전 정의 필터 (backend-engineer)
- D3. 데이터 모델 — `board_quick_filters(board_id, name, query)` (db-engineer)
- D4. 백엔드 — CRUD API + 보드 응답에 포함 (backend-engineer)
- D5. 백엔드 테스트 (backend-engineer)
- D6. 프론트 UI — 보드 상단 필터 칩 (designer → frontend-engineer)
- D7. E2E (qa-engineer)

classify 정정 메모:
- classify.ts는 type=backend, primary_bc=agile-planning 판정. "보드" 키워드로 agile-planning 잡음 → 데이터 모델(board_id 종속) 근거상 타당.
- 실제로는 풀스택 feature이므로 type=feature로 정정. UI task는 plan에서 frontend-engineer dispatch.
- personalization은 product 문서상 논리 그룹일 뿐 실제 백엔드 모듈 아님 (FR-UX-02=favorites, FR-UX-03=notification-dashboard).

병렬 작업 주의: FR-IM-02 (Import 매핑 UI) worktree 동시 진행 중. 프론트 필터 인프라(BoardFilterBar.tsx 등) 충돌 여부 spec/plan에서 선점 확인.

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
