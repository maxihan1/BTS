# FR-WF-02 D7 E2E — 워크플로우 스킴 Playwright 시나리오

> slug. fr-wf-02-d7-e2e-crud-playwright
> type. qa
> agent. qa-engineer
> 생성. 2026-05-29

## Brief

PR #31 ([ui] FR-WF-02 D6) 가 머지하면서 워크플로우 스킴(Workflow Scheme) 관리 UI 의 단위/통합 테스트는 완료. 그러나 spec §2.2 의 D7 (E2E) 항목은 미완료 — 유일한 미완 deliverable. 본 PR 가 그 마지막 항목을 채워서 FR-WF-02 BC 완료에 도달하는 것이 목표.

### 사용자 원문

`FR-WF-02 D7 E2E 작업하자 — 스킴 CRUD + 매핑 편집 + 표준 보호 + 사용 중 삭제 차단 모달 + 프로젝트 할당 Playwright 시나리오`

### classify-task 결과

- type. qa
- agent. qa-engineer
- slug. fr-wf-02-d7-e2e-crud-playwright
- primary_bc. null (frontend E2E 영역)

### 시나리오 후보 (사용자 명시 5건)

1. **스킴 CRUD** — 목록 → 생성 → 상세 → 수정 → 삭제 happy path
2. **매핑 편집** — 이슈 타입 ↔ 워크플로우 매핑 추가/변경/제거
3. **표준 보호** — `isDefault: true` 스킴 삭제/수정 제약 검증
4. **사용 중 삭제 차단 모달** — `SchemeInUseException` 발생 시 `SchemeInUseModal` 노출 + `usedByProjects` 리스트
5. **프로젝트 할당** — 프로젝트 ↔ 스킴 assign/reassign/unassign

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
