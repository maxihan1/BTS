# FR-IS-01 D7 Cleanup — PR #32 codereview 위임 4건

> slug: fr-is-01-d7-cleanup
> type: qa
> agent: qa-engineer
> 생성: 2026-05-28
> 브랜치: claude/context-restore-uP6lK (remote execution 환경, worktree 미사용)

## Brief

사용자 원문. "pr32-merged-fr-is-01-d7-e2e-4-scenarios-shipped-next-cleanup 작업 진행해줘".

직전 컨텍스트. PR #32 (FR-IS-01 D7 E2E 4 시나리오) 머지 완료 (`ee5412f`, 2026-05-28). `docs/plan/product/issue-tracking.md §2.1.1` D7 "부분 통과" 메모 반영. `docs/plans/2026-05-28-fr-is-01-d7-e2e-issue-tracking-playwright-happy-ed.md` §리뷰 결과 §Advisory CONCERN 종합 (BLOCKER 0) 에서 "별 cleanup PR" 위임된 4건이 본 PR scope.

본 PR scope (4건, Maxi 명시 선택).

| # | severity | 위치 | 내용 |
|---|---|---|---|
| L1 | LOW | `apps/web/e2e/fixtures/issue-fixtures.ts:53-54` | `createIssueViaUI` 가 hardcoded `'ATLAS-42'` 키 — mock `createdIssueFixture.key` 변경 시 silent break |
| L2 | LOW | `apps/web/src/mocks/issue-handlers.ts:38-41` | `resetIssueState()` export 호출처 0 — plan §C1 advisory 약속 미충족, dead code 우려 |
| PE1 | PRE_EXISTING | `apps/web/src/mocks/handlers.test.ts` PATCH/POST | MSW unhandled exception `Body is unusable: Body has already been read` (body consumption race) |
| E2E-5 | gap-I | `apps/web/e2e/issue-edit-conflict.spec.ts` (신규) | 동시 편집 409 회귀 가드 — PR #26 EC-1 이미 구현, 회귀 가드만 추가 |

classify 결과.
- type: qa
- agent: qa-engineer
- primary_bc: issue-tracking (수동 보강, classify 자동 추론 null)
- slug: fr-is-01-d7-cleanup (자동 생성 slug `fr-is-01-d7-cleanup-pr-pr-32-codereview-4-l1-hardc` 단축)

## 도메인 정리 (← /bts-domain 채움, 또는 fast-track 스킵 명시)

## 스펙 (← /bts-spec Phase A 채움, 또는 fast-track 스킵 명시)

## Brainstorming Check (← /bts-spec Phase B 채움, 또는 fast-track 스킵 명시)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움 / fast-track 시 codereview 결과)
