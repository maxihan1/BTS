# FR-WF-06 D1·D2·D4·D5 — 워크플로우 전환 규칙(validator) CRUD API

> 티어: T2
> slug: backend-workflow-transition-rule-crud
> type: api
> agent: backend-engineer
> 생성: 2026-08-25

## Brief

Maxi 원문 — 「fr-wf-06 진행하자」.

FR-WF-06 은 전환 규칙(조건/검증기/후처리)을 화면에서 편집하게 하는 FR 이다. 엔진은 이미 규칙을
`workflow_validators` / `workflow_post_actions` (`type` + `config` JSONB) 에서 읽고, Jira 의 조건/검증기
구분도 `ValidatorPhase` 로 표현돼 있다. **없는 것은 편집 수단뿐**이라 CRUD API 와 화면만 얹는다.

**이번 PR 범위 = D1 · D2 · D4 · D5 (백엔드).** D6(편집 다이얼로그)·D7(E2E)은 후속 PR —
FR-WF-04·FR-WF-05 가 쓴 분할 관례를 따른다 (Maxi 결정, 2026-08-25).
**D3 비해당** — 두 테이블 모두 V200 기존 테이블이고 스키마 변경이 없다 (착수 시 실측 재확인 완료).

### classify 결과

`{ type: api, agent: backend-engineer, primary_bc: project-workflow, tier(title-only): T1 }`
→ **선언 티어 T2**. `detect-tier.ts` 로 예상 변경 경로를 실측하면 `API` · `BE_MAIN` · `TEST` · `DOC`
표면이 잡히고 tier=T2, `unmapped` 0 건이다. classify 의 T1 은 제목만 보는 추정이라 채택하지 않는다.

### 착수 시점 실측 (파일 존재 ≠ 기능 존재)

learnings 2026-07-17 「REST 노출이 없으면 기능이 없는 것이다」에 따라 컨트롤러의 HTTP 매핑을 직접 셌다.

- project-workflow 컨트롤러 **6종** — `WorkflowController` · `WorkflowStatusCompositionController` ·
  `StatusController` · `WorkflowSchemeController` · `ProjectWorkflowSchemeController` ·
  `PostActionController`. **validator 컨트롤러는 0종**이다.
- validator 구현체 4종(`RequiredFieldValidator` · `PermissionValidator` · `NotStatusCategoryValidator` ·
  `CustomExpressionValidator`)과 팩토리(`DefaultWorkflowValidatorFactory`)는 있다. **읽기 경로만 있고
  쓰기 경로가 없다.**
- post-action 쪽은 `PostActionAdminService` · `PostActionRepository` · `PostActionController` 가 이미 있고,
  `PostActionTransitionResolver.resolveById` 가 **UUID 세그먼트를 이미 받는다** → D2 의
  「post-action 경로를 transitionId 로 정렬」은 상당 부분 선반영 상태다. 스펙 단계에서 잔여분을 확정한다.

### 부수 결정 — type 표기는 코드가 정본 (Maxi 결정, 2026-08-25)

SDD §7.3·§7.4 표와 코드 실측이 **9행 중 7행** 어긋나 있다.

| 코드 실측 (`override val type`) | SDD 표 | 일치 |
|---|---|---|
| `RequiredField` | `RequiredField` | ✅ |
| `permission-check` | `Permission` | ❌ |
| `not-status-category` | `NotStatusCategory` | ❌ |
| `CustomExpression` | `CustomExpression` | ✅ |
| `SET_FIELD` `ADD_WATCHER` `NOTIFY` `CALL_WEBHOOK` `RUN_AUTOMATION` | `SetField` `AddWatcher` `Notify` `CallWebhook` `RunAutomation` | ❌ ×5 |

**코드를 정본으로 두고 SDD 표를 실측에 맞춘다.** 코드 표기를 바꾸면 별칭 경로·기존 DB 행 확인·씨앗
재검증이 붙어 범위가 늘고, FR-WF-06 이 선언한 「CRUD 와 화면만 얹는다」를 벗어난다.
대신 **표 ↔ 팩토리 `when` 분기를 대조하는 판별식**을 새로 넣어 재발을 막는다 —
메모리 `two-lists-never-check-each-other` 의 지배 결함 양식이고, 처방은 차집합 판별식 + 비-공허 짝이다.

## 도메인 정리 (← /bts-spec §1 채움)

## 스펙 (← /bts-spec §2 채움)

## Sanity Check (← /bts-spec §3 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
