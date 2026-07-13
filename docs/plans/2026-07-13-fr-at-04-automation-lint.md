# FR-AT-04 규칙 충돌 정적 분석

> slug: fr-at-04-automation-lint
> type: backend
> agent: backend-engineer
> primary_bc: automation
> 생성: 2026-07-13

## Brief

FR-AT-04 규칙 충돌 정적 분석 — automation 규칙 저장 전 사이클/우선순위 모호성/필드 충돌 검출 lint.

automation 규칙 여러 개가 서로 충돌하는지를 저장(생성/수정) 전에 정적으로 검사한다.
- 사이클: 규칙 A의 액션이 규칙 B의 트리거를 유발하고 B가 다시 A를 유발하는 정적 루프 (런타임 가드는 FR-AT-02, 정적 검출은 여기)
- 우선순위 모호성: 같은 트리거에 복수 규칙 매칭 + 실행 순서 미결정
- 필드 충돌: 두 규칙이 같은 필드를 다른 값으로 SET

product 문서: docs/plan/product/automation.md §2.4
D1 도메인(RuleConflict) · D2 명세 · D3 데이터(활용) · D4 백엔드(저장 전 lint) · D5 테스트 · D6 UI(경고 모달) · D7 E2E

## 도메인 정리

- **BC**: automation (단일). cross-BC는 shared-kernel 권한 조회 포트만 (AutomationBcArchTest 강제)
- **영향 엔티티** (전부 기존, 읽기 전용): AutomationRule / Action(SetField·Assign·AddComment·CallWebhook) / Condition(And·Or·Not·Comparison) / TriggerType(ISSUE_CREATED·ISSUE_UPDATED·ISSUE_COMMENTED·SCHEDULED·WEBHOOK)
- **신규 도메인 개념**:
  - `RuleConflict` (규칙 충돌, 값 객체) — `{ type, severity, ruleIds, detail }`
  - `ConflictType` enum **4종**: `CYCLE` / `FIELD_CONFLICT` / `PRIORITY_AMBIGUITY` / `PERMISSION_MISSING` (Maxi 확정 — product §2.4 3종 + SDD 8.7 3종의 합집합)
  - `RuleConflictAnalyzer` (application 서비스) — 프로젝트 규칙 집합을 정적 분석
- **강제성**: 전부 경고(soft). 어떤 충돌도 저장을 막지 않음. 저장 성공 + 응답 DTO에 `conflicts` 배열 포함 (Maxi 확정). `severity`는 UI 표현용이며 현재 전부 WARNING
- **분석 방식**: 별도 엔드포인트 없음. 기존 `POST`/`PATCH .../automation/rules` 저장 경로가 저장 후 lint 수행 → 응답에 `conflicts` 포함 (Maxi 확정)
- **신규 테이블**: 없음 (product D3 "활용"). 기존 `automation_rules`/`automation_actions`/`automation_conditions`를 읽어 분석, 결과 미영속
- **충돌 판정 규칙 (초안, spec에서 정밀화)**:
  - `CYCLE`: 규칙 그래프 DFS. 엣지 A→B = A의 액션이 B의 트리거를 유발. `SetFieldAction.field` ∩ `ISSUE_UPDATED.triggerConfig.fields` / `AssignAction`→`ISSUE_UPDATED`(assignee) / `AddCommentAction`→`ISSUE_COMMENTED`. `CallWebhookAction`은 외부 유입이라 정적 엣지 없음
  - `FIELD_CONFLICT`: 같은 `(projectKey, triggerType[+겹치는 fields])`에 매칭되는 규칙들의 `SetFieldAction` 중 `field` 동일 & `value(JsonNode)` 상이
  - `PRIORITY_AMBIGUITY`: 같은 `(projectKey, triggerType)`+겹치는 조건에 enabled 규칙 2개+ 이고 순서 결정 필드가 `created_at, id`뿐 (automation_rules에 priority 컬럼 부재가 근거)
  - `PERMISSION_MISSING`: rule actor(`actor_user_id`)가 액션 대상(필드 편집/담당 지정/댓글 작성)의 프로젝트 레벨 권한 부재. 저장 시점 구체 이슈 없음 → 프로젝트 권한으로 근사. cross-BC 권한 포트 필요 (기존 `AutomationPermissionResolver` 확장 또는 신규 포트 — spec 정밀화)
- **기존 결정 충돌**: 없음. 오히려 FR-AT-02 `AutomationExecutionWorker` KDoc의 "견고한 사이클 검출은 FR-AT-04 위임"을 완성
- **문서 drift 해소 (이 PR에서 전수 동기화)**: product §2.4(3종)·SDD 8.7(3종) → **4종**으로 정렬 (CLAUDE.md §FR/범위 변경 전수 동기화 규칙 — verify-master-plan 통과 필수)
- **관련 ADR**: docs/decisions/2026-07-13-fr-at-04-conflict-analysis.md (spec 확정 후 생성)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
