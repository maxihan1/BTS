<!-- FR-IS-01 transition wiring slice — IssueApplicationService/IssueController가 project-workflow BC의 WorkflowResolver를 SPI 경유 호출하도록 연결하는 백엔드 작업 plan -->

# 이슈 상태 전이 백엔드 wiring — WorkflowResolver consumer 연결

> slug: wiring-workflowresolver-consumer-fr-is-01-transiti
> type: backend
> agent: backend-engineer
> 주 BC: **issue-tracking** (classify는 `project-workflow`로 분류했으나, 실 변경 영역이 `IssueController`/`IssueApplicationService`라서 정정)
> 부 BC: project-workflow (SPI 경유 호출만, 코드 변경 없음 또는 최소)
> 생성: 2026-05-28
> 관련 메모: [[issue-transition-backend-gap]] 해소 trigger

## Brief

PR #18에서 project-workflow BC에 추가된 `WorkflowResolver` (SPI outbound port — 프로젝트 키 + 이슈 타입 키 기준으로 적용 워크플로우 결정)를, issue-tracking BC가 PR #25에서 분리한 공통 SPI 경계를 통해 실제로 호출하도록 연결한다.

### 현재 문제 (issue-transition-backend-gap)

1. `IssueController.kt:179`. `AppTransitionIssueRequest(workflowKey = "DEFAULT", ...)`로 하드코딩. 그러나 시드된 워크플로우는 `software-default` / `bug-tracking` / `kanban-basic` / `simple` — `"DEFAULT"` 미시드
2. `IssueApplicationService.kt:92`. `currentStateKey = "OPEN"`(대문자). 워크플로우 상태 키는 `software-default.yaml`의 `open` / `in_progress` / `in_review` / `done` / `closed`(소문자). 케이스 불일치

→ 단위 테스트는 `WorkflowTransitionPort`를 mock해서 통과하지만 런타임에 전이가 동작하지 않음.

### 본 PR 변경 (예상 — 구체는 /bts-spec, /bts-plan에서 확정)

1. `IssueController` (또는 application service) 가 `WorkflowResolver.resolveFor(projectKey, issueTypeKey)` 호출 → 실제 워크플로우 키/엔티티 획득 → `AppTransitionIssueRequest` 또는 동등 경로에 전달
2. `IssueApplicationService.kt:92`의 초기 상태 키를 워크플로우의 시작 상태(`open` 소문자)와 정렬
3. 필요 시 software-scheme 자동 배정(WorkflowResolver 주석상 EC-1, D10) 검증 — DB에 `project_workflow_scheme_assignments` 자동 매핑이 정상 동작하는지

### 목표

이슈 상태 전이가 런타임에 실제 동작하게 만든다. 후속 FR-IS-01 D7 E2E(생성 → 조회 → 수정 → **상태 전이** → 소프트 삭제 → 키 영속성) 풀시나리오를 돌릴 수 있는 상태로 unblock.

### 제약

- **BC 격리**. PR #25에서 분리한 공통 SPI 경계를 통해서만 호출. issue-tracking에서 project-workflow 내부 클래스 직접 import 금지
- 백엔드 작업 단독 (프론트 UI / E2E는 D6 / D7 별 slice)
- 본 PR scope에서는 전이 UI 신규 생성 없음 — 백엔드 wiring + 단위/통합 테스트만

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
