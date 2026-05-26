# FR-IS-01 D4 + D5 마무리 — codereview cleanup + Wave 6 백엔드 테스트

> slug. `issue-tracking-bc-fr-is-01-cleanup-tests`
> type. backend (manual override — classify 자동 결과 qa/qa-engineer 가 작업 본질과 어긋남)
> agent. backend-engineer (Wave 6 test 인프라 도 src/ 영역 포함 → qa-engineer 룰 위반 회피)
> primary BC. issue-tracking
> 생성. 2026-05-26

## Brief

PR #17 머지 후 미결 후속 작업 일괄 정리 PR.

**(D4) 백엔드 API 마무리** — PR #17 의 codereview CONCERN 3건.
- **C-2 sealed Result port** — `WorkflowTransitionPort` 시그니처를 `sealed Result` 로 변경하여 `IssueApplicationService.transitionIssue` 의 `catch (WorkflowValidatorFailureException)` 자체를 제거. BC 격리 본질 차단 (issue-tracking 이 project-workflow domain exception import 0건).
- **C-3 DATA.md advisory_lock 정합** — DATA.md §5 의 `dsl.execute(rawSql)` 금지와 `IssueRepository.incrementKeySequence` 의 `pg_advisory_xact_lock` + `pgmq.send` 실제 사용 사이 정합. ADR 발행 또는 DATA.md 단서 추가.
- **C-5 PATCH 시맨틱 fix** — `UpdateIssueRequest.summary` 의 `null` 의도를 RFC 7396 (JSON Merge Patch) 시맨틱에 맞게 정정. null = "값 변경 안 함" vs `""` = "빈 문자열로 설정". 현재는 null 을 통과시켜 도메인 invariant 위반.

**(D5) 백엔드 테스트 마무리** — PR #17 후속 PR 로 미뤄둔 Wave 6 6 task.
- **ArchUnit 룰** — (1) BC 격리 (issue-tracking 이 project-workflow.domain 직접 import 금지, port.outbound 만 허용) + (2) jOOQ 화이트리스트 (jooq.generated.* 는 repository 패키지에서만 import).
- **Testcontainers singleton 정비** — `IssueRepositoryTest` 의 Flyway 충돌 known issue 해소. PR #8 learnings #2 패턴 (`.apply { start() }` JVM singleton + Ryuk cleanup).
- **Kotest property test** — Issue aggregate invariant (key 형식 / state 전이 / soft delete 키 보존) × 1000건.

C-6 (SYSTEM_ACTOR_UUID + workflowKey="DEFAULT" hardcoded) 은 FR-AU-10 (시스템 액터 모델) 의존 → 본 PR scope 제외.

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
