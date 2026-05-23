<!-- FR-IS-01 issue-tracking BC 비즈니스 로직 (Wave 2~6) — PR #14 부트스트랩 위에서 작성 -->

# FR-IS-01 issue-tracking BC 비즈니스 로직 (Wave 2~6 후속 PR)

> slug. `issue-tracking-bc-fr-is-01-business-logic`
> type. `backend` (api 수준, PR #14 의 부트스트랩 위에 비즈니스 로직)
> primary agent. `backend-engineer`
> 보조 agent. `db-engineer`, `security-engineer` (검토)
> BC. `issue-tracking`
> 생성. 2026-05-23

## Brief

PR #14 (`#14` 머지 — `333b709`) 가 제공한 issue-tracking BC 부트스트랩 (모듈 + VO + V001/V002 마이그레이션 + IssuePermissionResolver port + AlwaysAllow stub + dev seed + pgmq 이미지) 위에서 **비즈니스 로직** 구현.

### 사용자 원문

> "wave 2 후속 pr 시작하자" + AskUserQuestion 결정 — "Wave 2~6 한꺼번에 (PR #14 계획 그대로)"

### 작업 범위 (Wave 2~6, 26 task)

`docs/plans/2026-05-22-issue-tracking-bc-fr-is-01-crud.md` (main 머지본) §Plan 의 Wave 2~6 발췌.

- **Wave 2** (4 task, 병렬). Issue Aggregate + IssueId VO / Custom Exceptions / IssueDomainEvent sealed / IssueResponse DTO
- **Wave 3** (2 task, 병렬). IssueRepository (jOOQ) / IssueEventPublisher (pgmq enqueue)
- **Wave 4** (6 task, 같은 파일 직렬). IssueApplicationService — `createIssue` / `findByKey` / `updateIssue` / `transitionIssue` / `softDeleteIssue` / `listIssues`
- **Wave 5** (5 task, 병렬 — files 분리). IssueController 5 엔드포인트 + IssueExceptionHandler (RFC 7807 ProblemDetail)
- **Wave 6** (6 task). ArchUnit (BC 격리 + @Transactional Bean) / Testcontainers `IssueTestcontainersBase` (singleton) / EC-1 race condition 통합 / EC-2~7 통합 6 시나리오 / Kotest property test (키 영속성 invariant)

### 본 PR scope 외 (명시 제외)

- D6 Frontend (Wave 7) — design-shotgun → IssueDetail.tsx + IssueList + TanStack Query — 후속 PR (PR #13 머지 후 시작 가능 — 본 PR 머지 후 검토)
- D7 E2E + NFR (Wave 8) — Playwright S1~S6 + k6 NFR — 후속 PR

### PR #14 후속 정리 (Wave 2~6 진행 중 권장 fix)

PR #14 머지 직후 남은 4 CONCERN — 본 PR 진행 중 적정 시점에 정리.

| CONCERN | PR #14 결정 | 본 PR 처리 시점 |
|---|---|---|
| C-1 nu.studer.jooq private 필드 reflection | 후속 정리 | Wave 4 ApplicationService 진입 직전 검토 (jOOQ DSL 활용 시점) |
| C-2 init_codegen.sql 중복 유지 | V003 도입 PR 자동화 | 본 PR 은 V003 미도입, 현행 유지 |
| C-4 WARN 로그 폭증 | Wave 2 ApplicationService 검토 | T16 createIssue 도입 시 로그 sampling 검토 |
| C-5 docker-compose `:latest` 태그 | prod 진입 시 BLOCKER | 본 PR 미터치 |

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
