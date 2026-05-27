# FR-IS-01 D6 — IssueDetail.tsx 프론트 UI (TanStack Query 캐싱 + 낙관적 업데이트)

> slug: fr-is-01-d6-issuedetail-tsx-ui-tanstack-query
> type: ui
> agent: frontend-engineer
> primary_bc: issue-tracking
> 생성: 2026-05-27

## Brief

FR-IS-01 (이슈 CRUD + 상태 전이 검증 + 알림)의 D6 단계 — 프론트 UI.

- 대상. `IssueDetail.tsx` — 이슈 상세 화면. TanStack Query 캐싱 + 낙관적 업데이트.
- 백엔드 D1~D5 완료. `GET/POST/PATCH/DELETE /api/v1/issues` (PR #17 비즈니스 로직 + PR #23 codereview cleanup — sealed Result port + PATCH partial RFC 7396 + PR #24 Flyway namespace 격리).
- 담당 흐름. designer (디자인 스펙) → frontend-engineer (TSX 구현).
- 진척 문서 근거. `docs/plan/product/issue-tracking.md` §2.1.1 D6.

분류 결과. type=ui, agent=frontend-engineer, BC=issue-tracking.

선행 컨텍스트.
- D7 (E2E)은 풀스택 기동 필요 → 백엔드 clean build 버그(메모리 backend-clean-build-broken, project-workflow jOOQ task 의존 미선언) 선결. D6(프론트)는 무관.
- 관련 learnings. TanStack Router code-based adapter 패턴 (#11/#13), vi.mock 좁은 범위 (#20), lint-staged 병렬 race (#20), 이슈키 재사용 금지/IssueKeyRedirect (D7 직결).

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
