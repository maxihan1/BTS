# FR-RP-02 벨로시티 차트 (Velocity Chart)

> slug: fr-rp-02-velocity
> type: feature (classify 원출력 qa→오분류 정정, E2E/recharts 키워드 반응)
> agent: backend-engineer (프론트 D6/D7은 plan 메타 agent로 frontend-engineer 지정)
> primary_bc: notification-dashboard (엔드포인트는 agile-planning co-located 가능, FR-RP-01 선례)
> 생성: 2026-07-02

## Brief

여러 과거 스프린트에 걸쳐 "완료한 작업량"을 막대 차트로 보여주는 벨로시티 리포트.
팀이 스프린트마다 얼마나 처리하는지 추세를 파악. 선행 FR-RP-01(번다운) 완료.

- 백엔드: `GET /api/v1/projects/{id}/velocity` (product 문서 §4.2)
- 프론트: recharts 바 차트 (WorklogAggregateChart/BurndownChart 선례 재사용)
- E2E: Playwright

**핵심 결정 포인트 (domain/spec에서 확정)**.
- 벨로시티 지표 = 스토리포인트 vs 완료? FR-RP-01에서 "스토리포인트 미구현 → 추정 시간(초)"으로 확정한 이력.
  벨로시티도 동일하게 추정 시간(초) 기반으로 갈지, 이슈 수 기반으로 갈지 결정 필요.
- "완료" 판정 = 워크플로우 상태 카테고리 DONE 기준 (FR-EP-02 선례: WorkflowStateCatalog.listStates → category).
- 대상 스프린트 = 완료(CLOSED)된 최근 N개 스프린트.
- PR 분할 = FR-RP-01 선례(백엔드 D1~D5 / 프론트 D6/D7 2 PR) 따를지 단일 PR로 갈지.

## 도메인 정리

### BC / 모듈
- 논리 BC: **notification-dashboard** (fr-index §4.2 라벨 유지)
- 구현 모듈: **agile-planning** (엔드포인트가 Sprint 애그리거트와 co-located, FR-RP-01 선례와 동일 판정). 총 FR 카운트 불변.

### 신규 용어 (glossary 추가 대상 — Maxi 승인 후 미러)
- **벨로시티 / Velocity** — 여러 완료(`COMPLETED`) 스프린트에 걸쳐 **계획량(commitment)** 과 **완료량(completed)** 을 추정 시간(초) 기준으로 스프린트별 집계한 애자일 리포트. 팀의 처리 추세 파악용. 스토리포인트 미구현이라 초 단위로 측정(FR-RP-01 번다운과 동일). `GET /api/v1/projects/{projectKey}/velocity`.

### Maxi 확정 결정 (2026-07-02 domain 게이트)
1. **지표 단위 = 추정 시간(초)**. `issues.original_estimate_seconds` 합. 스토리포인트 필드 미존재(전수 grep 0건) → 초 기반 확정. FR-RP-01 일관.
2. **막대 = 계획(Commitment) vs 완료(Completed) 2막대**. Jira 벨로시티 정석.
   - Commitment(계획량) = 스프린트에 현재 속한 **가시(visible)** 이슈들의 `Σ original_estimate_seconds`.
   - Completed(완료량) = 그중 **현재 워크플로우 상태 카테고리가 DONE** 인 이슈들의 `Σ original_estimate_seconds`.
3. **PR 분할 = 백엔드/프론트 2 PR**. **이번 세션 = 백엔드 PR(D1~D5)**. 프론트 D6/D7 + E2E는 후속 PR.

### 완료 판정 방식 (on-the-fly, FR-RP-01 deviation 계승)
- "완료" = **조회 시점의 현재 상태**가 DONE 카테고리(스냅샷/`issue_history` 미사용). 완료된 스프린트는 역사적으로 확정돼 이슈 상태 변동이 거의 없으므로 현재 상태 기준이 타당. `issue_history` 기반 "스프린트 종료 시점 완료"는 FR-RP-03/04 도구로 위임.
- DONE 판정 = `WorkflowStateCatalog.listStates(projectKey, issueTypeKey)` → `category==DONE` (FR-EP-02 선례). 타입별 캐싱으로 N+1 차단, `WorkflowSchemeNoDefaultException` 폴백(미할당 타입 이슈는 미완료 취급, 500 차단).

### 대상 스프린트
- `SprintRepository.findByProject(projectKey, COMPLETED)` 재사용. 최근 N개(기본 상한, spec에서 확정 — 예: 10), 정렬 = end_date/created_at desc.

### 재사용 자원 (신규 스키마 0)
- Sprint 모델: `sprints`, `sprint_issues`(조인). `findByProject(projectKey, COMPLETED)` + `findIssueKeysByProject`(N+1 차단 배치).
- 추정치: `issues.original_estimate_seconds`(INT NULL, V027).
- 보안: 프로젝트 **BROWSE** 권한 + **이슈별 가시성 필터**(`filterVisibleIssueKeys` → `buildActiveSecureWhere` 재사용). 기밀 이슈 누출 차단.
- 완료 판정: `WorkflowStateCatalog` 포트(shared-kernel 정의, project-workflow 구현), `StateCategory{TODO,IN_PROGRESS,DONE}`.

### 신규 cross-BC 포트 (필요)
- 기존 `SprintBurndownLookupPort.fetchBurndownSource`는 "스프린트 전체 이슈의 추정시간 합(scope)"만 반환 → **완료분 미구분**. 벨로시티는 이슈별 (estimate, DONE 여부)가 필요.
- **신규 포트 `SprintVelocityLookupPort`**(shared-kernel 정의, **issue-tracking 구현**). 시그니처(초안): 스프린트별 issue-key 집합 + viewer → 스프린트별 `{commitmentSeconds, completedSeconds}` 반환. issue-tracking 어댑터가 가시성 필터 + `original_estimate_seconds` 합 + `WorkflowStateCatalog` DONE 판정을 한 곳에서 수행(FR-EP-02가 issue-tracking에서 WorkflowStateCatalog 소비하는 선례 존재). 정확한 시그니처는 plan에서 확정.

### 기존 결정 충돌
- 없음. FR-RP-01/FR-EP-02 패턴의 자연스러운 확장.

### 관련 ADR
- [docs/decisions/2026-07-02-fr-rp-02-velocity.md](../decisions/2026-07-02-fr-rp-02-velocity.md) (생성됨)
- 선행: `2026-07-02-fr-rp-01-burndown-burnup`, FR-EP-02 진행률 결정.

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
