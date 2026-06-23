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

## 도메인 정리

- **BC(구현)**: issue-tracking (새 BC 신설 안 함 — Maxi 확정)
- **BC(논리 소속)**: search-export-import 유지 (fr-index 매핑 무변경, 논리≠물리)
- **필터 의미론**: 필드 내 OR + 필드 간 AND (BoardCardFilter 동형, Maxi 확정). 임의 AND/OR 트리는 FR-SR-02(AQL) 영역.
- **영향 엔티티**: Issue (기존). 신규 엔티티 0.
- **재사용 자산**:
  - `BoardCardFilter` (shared-kernel/.../board/BoardCardFilter.kt) — assignee/label/component 필터 VO
  - `IssueRepository.buildFilterCondition` / `buildSecurityCondition` / `listWithType` / `listVisibleForBoard` (issue-tracking)
  - `IssueSecurityDirectory.accessibleLevels` (visibility 필터)
  - `IssueController.GET /api/v1/issues` (현재 projectKey+pageable → 필터 파라미터 추가)
- **신규 필요**: status(`current_state_key`) 필터 (기존 BoardCardFilter에 없음). project는 기존 projectKey로 충족.
- **새 용어**: "이슈 필터(Issue Filter)" — 다중 필드 조합 조건 VO. BoardCardFilter 확장/유사. glossary 추가는 Maxi 승인 대기.
- **기존 결정 충돌**: 없음. 관련 ADR `2026-06-02-issue-permission-query-api`(권한 쿼리) / `2026-06-03-version-component-permission-query-and-gating`와 정합(visibility 필터 재사용).
- **관련 ADR**: [docs/decisions/2026-06-23-fr-sr-01-issue-filter-bc.md](../decisions/2026-06-23-fr-sr-01-issue-filter-bc.md) (생성됨)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
