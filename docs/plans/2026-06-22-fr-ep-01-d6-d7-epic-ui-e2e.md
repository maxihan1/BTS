# FR-EP-01 D6/D7 — 에픽 페이지 + 자식 이슈 연결 UI + 보드 EPIC 스윔레인

> slug: fr-ep-01-d6-d7-epic-ui-e2e
> type: ui
> agent: frontend-engineer
> primary_bc: agile-planning (보드) + issue-tracking (에픽/자식 연결 — 백엔드 계약)
> 생성: 2026-06-22

## Brief

FR-EP-01(에픽 이슈 타입 + 자식 이슈 연결)의 D6/D7 프론트 구현. 백엔드 D1~D5는 PR #174로 완료됨.

작업 범위.
1. 에픽 페이지 + 자식 이슈 목록 UI
2. 에픽 연결/해제 UI
3. 보드 EPIC 스윔레인 enum 추가 (FR-BD-03 #173에서 이연됨)
4. changelog 라벨 "에픽" (field="epic")

확정된 백엔드 계약 (#174).
- `POST /api/v1/issues/{epicKey}/epic-children` — 자식 연결
- `DELETE /api/v1/issues/{epicKey}/epic-children` — 연결 해제
- `GET /api/v1/issues/{epicKey}/epic-children` — 자식 목록
- `IssueResponse.epic` — {key, summary} 단건 (parent 동형 self-join, 단건 GET만 채움)
- changelog `field="epic"` — 한글 라벨 "에픽" 매핑 필요
- 보드 스윔레인 EPIC: BoardCardResponse.epic view-layer patch 필요 가능성 (PRIORITY 스윔레인 #173 옵션C 선례)

완료 시 FR-EP-01 전체 [x] 마킹 (69/123 → 70/123).

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
