# FR-LK-02 D6/D7 — 이슈 링크 그래프 프론트엔드 시각화 + E2E

> slug: fr-lk-02-d6-d7-graph-ui
> type: ui
> agent: frontend-engineer
> 생성: 2026-06-14

## Brief

사용자 원문: `FR-LK-02 D6/D7`

이슈 링크 그래프 시각화 프론트엔드(D6) + E2E(D7).
백엔드 D1~D5는 #138로 완료 — `GET /api/v1/issues/{key}/graph?depth={1..3}` (기본 2).
응답: `DataResponse<{center, depth, nodes:[{key,summary,statusKey,depth}], edges:[{from,to,type}], truncated}>`.
노드 상한 NODE_CAP=100, 초과 시 truncated=true. edge.type 대문자(BLOCKS/RELATES/DUPLICATES/CLONES/PARENT).

classify 결과: type=ui(E2E 키워드로 qa 오판정 → ui 교정, FR-LK-01 선례), agent=frontend-engineer, primary_bc=issue-tracking.

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
