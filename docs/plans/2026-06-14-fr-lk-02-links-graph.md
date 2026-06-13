# FR-LK-02 링크 그래프 시각화 (백엔드 D1~D5)

> slug: fr-lk-02-links-graph
> type: api
> agent: backend-engineer
> primary_bc: issue-tracking
> 생성: 2026-06-14

## Brief

FR-LK-02 (§5.3.2) — 링크 그래프 시각화. FR-LK-01(완료, #135/#136)이 만든
`issue_links`(blocks/relates/duplicates/clones 4종, UUID FK) + `issues.parent_id`
데이터를 노드/엣지 그래프 형태로 노출한다.

이번 PR 범위 = **백엔드 D1~D5**.
- D1. 도메인 (그래프 노드/엣지 표현)
- D2. 명세 — 노드/엣지 표현
- D3. 데이터 모델 — (활용, 신규 테이블 없음)
- D4. 백엔드 — `GET /api/v1/issues/{key}/graph`
- D5. 백엔드 테스트

프론트 D6(SVG/force-directed) + D7(E2E)는 후속 PR(`ui/fr-lk-02-d6-d7-...`).

선행. §5.3.1 FR-LK-01 완료.

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
