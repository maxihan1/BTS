# FR-SR-01 — 이슈 필터 (다중 필드 조합)

> slug: fr-sr-01-issue-filter
> type: api
> agent: backend-engineer
> primary_bc: issue-tracking (domain 단계에서 BC 경계 확정)
> 생성: 2026-06-23

## Brief

FR-SR-01 이슈 필터 (다중 필드 조합) — search-export-import BC의 첫 FR.
`GET /api/v1/issues?filter=...` 다중 필드 조합(status, assignee_id, project_id, label) AND/OR jOOQ 동적 쿼리.
백엔드 D1~D5 우선. 새 BC(search-export-import) 부트스트랩 여부 vs issue-tracking 확장은 domain 단계에서 결정.

- classify: type=api, agent=backend-engineer, primary_bc=issue-tracking
- product 문서: docs/plan/product/search-export-import.md §2.1
- §0 진입 조건: FR-AU-09(PAT) 완료 ✓ / issue-tracking 이슈 데이터 안정 ✓ / §1 AQL 파서 PoC는 FR-SR-02용(본 FR 무관)

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
