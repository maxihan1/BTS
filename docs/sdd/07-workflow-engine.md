# 07. 워크플로우 엔진

> 워크플로우 엔진의 구체적 구현은 `.claude/skills/atlas-workflow-engine/SKILL.md` 참조.

> **2026-08-18 — §7.1 의 두 항목이 대체 결정을 받았다.** 전환 identity 와 정의 저장 위치다.
> 무엇이 바뀌고 무엇이 그대로인지는 아래 **§7.5** 가 적는다. §7.1~§7.4 본문은 **오늘 돌아가는 코드
> 그대로** 남긴다 — 구현되지 않은 설계를 현재형으로 적어 둔 것이 구 ADR 2건을 몇 달째 거짓으로
> 만든 원인이었다(`docs/adr/2026-08-18-workflow-db-as-source-of-truth.md` §구 ADR 은 구현과 이미
> 어긋나 있었다).

## 7.1 설계 개요

Atlas의 워크플로우 엔진은 FSM(유한 상태 기계) 기반. Jira의 Workflow 개념을 단순화하여 채택.

### 핵심 기능

- 상태 (State) + 카테고리 (TODO/IN_PROGRESS/DONE)
- 전환 (Transition): from → to 매핑 — **FR-WF-05 에서 전환 ID 로 대체된다** (§7.5)
- Validator: 전환 가능 여부 검증
- Post-function: 전환 후 후처리
- WorkflowScheme: 프로젝트 + 이슈 타입별 매핑
- YAML 정의 (Git으로 버전 관리) — **FR-WF-04 에서 DB 정본으로 대체된다** (§7.5)

## 7.2 표준 워크플로우 4종

| 키 | 용도 | 상태 흐름 |
|---|---|---|
| `software-default` | 일반 SW 개발 | To Do → In Progress → Review → Done |
| `bug-tracking` | 버그 관리 | New → Triage → In Progress → Verified → Closed |
| `simple` | 단순 | Open → Closed |
| `kanban-basic` | 칸반 기본 | Backlog → To Do → In Progress → Done |

## 7.3 Validator 표준

| 타입 식별자 | 구현 클래스 | 용도 |
|---|---|---|
| `RequiredField` | `RequiredFieldValidator` | 특정 필드 입력 필수 |
| `permission-check` | `PermissionValidator` | 권한 보유 검증 |
| `not-status-category` | `NotStatusCategoryValidator` | 특정 카테고리 진입 불가 |
| `CustomExpression` | `CustomExpressionValidator` | SpEL 표현식 |

> **타입 식별자 열이 정본이다** — `DefaultWorkflowValidatorFactory.create(type, config)` 가 받는
> 문자열 그대로이고, 다른 값을 쓰면 `지원하지 않는 validator type` 예외가 난다. 표기가 섞여 있는
> 것(`RequiredField` 와 `permission-check`)은 구현 순서가 남긴 역사적 사실이며 **코드가 정본**이다.
> 각주. ADR [validator-terminology](../adr/2026-05-21-workflow-validator-terminology.md) 가 정한 것은
> **구현 클래스 이름**이지 런타임 `type` 문자열이 아니므로, 이 표의 2026-08-25 정정은 그 ADR 과
> 충돌하지 않는다(클래스 이름은 그대로 병기한다).
> 표와 팩토리 `when` 분기의 일치는 `scripts/workflow/validator-type-catalog.test.ts` 가 강제한다.

## 7.4 Post-function 표준

| 타입 식별자 | 구현 클래스 | 용도 |
|---|---|---|
| `SET_FIELD` | `SetFieldPostAction` | 필드 자동 설정 (예: resolution=fixed) |
| `NOTIFY` | `NotifyPostAction` | 알림 발송 |
| `ADD_WATCHER` | `AddWatcherPostAction` | Watcher 자동 추가 |
| `RUN_AUTOMATION` | `RunAutomationPostAction` | 자동화 규칙 실행 |
| `CALL_WEBHOOK` | `CallWebhookPostAction` | 외부 시스템 통지 |

> 타입 식별자 열이 정본인 것은 §7.3 각주와 같다 — `DefaultWorkflowPostActionFactory.create(type, config)`
> 가 받는 문자열 그대로다(이쪽은 전부 SCREAMING_SNAKE_CASE). 행 순서도 팩토리 `when` 분기 순서다.

## 7.5 편집 가능 워크플로우 — FR-WF-04~07 (2026-08-18 결정 · 미구현)

**이 절은 결정만 적는다. 코드는 아직 §7.1~§7.4 그대로다.** 워크플로우를 화면에서 만들고 고칠 방법이
없다는 구조적 부재를 닫기로 했고, 그 과정에서 §7.1 의 두 항목이 뒤집혔다.

| §7.1 이 적은 것 | 새 결정 | 근거 ADR | 담당 FR |
|---|---|---|---|
| 전환 = `from → to` 매핑 | 전환 identity 를 **전환 ID** 로. 같은 상태쌍에 이름이 다른 전환 여럿, `kind` 로 전역(GLOBAL)·최초(INITIAL) 전환 표현 | [transition-id-identity](../adr/2026-08-18-workflow-transition-id-identity.md) | FR-WF-05 · FR-WF-06 |
| YAML 정의(Git 버전 관리)가 정본 | **DB 가 정본.** YAML 은 빈 DB 최초 1회 부트스트랩과 「기본값으로 복원」의 기준으로만 남고, 재기동 시 DB 를 YAML 로 되돌리는 동작은 폐지 | [db-as-source-of-truth](../adr/2026-08-18-workflow-db-as-source-of-truth.md) | FR-WF-04 |
| (없음 — 상태가 워크플로우 종속) | 상태를 **사이트 전역 카탈로그**로 승격(Jira Cloud 동일). 상태 키는 불변 | [global-status-catalog](../adr/2026-08-18-workflow-global-status-catalog.md) | FR-WF-04 |
| (없음 — 편집 화면 자체가 없음) | 목록 모드 + `@xyflow/react` 다이어그램 모드 2종. Gantt 가 자체 SVG 를 택한 선례와 왜 갈리는지는 ADR 본문의 대조표 | [editor-canvas-library](../adr/2026-08-18-workflow-editor-canvas-library.md) | FR-WF-04 · FR-WF-05 |

§7.2 표준 워크플로우 4종은 **삭제되지 않는다** — 편집 가능해지고, 「기본값으로 복원」의 기준으로
남는다. §7.3 Validator · §7.4 Post-function 표준 표도 그대로이며, FR-WF-06 이 그 표의 항목을
화면에서 붙였다 뗐다 할 수 있게 한다.

D 단계 진척은 [`docs/plan/product/project-workflow.md` §2.4~§2.7](../plan/product/project-workflow.md).

## 7.6 다음 챕터

- 구체 구현 → `.claude/skills/atlas-workflow-engine/SKILL.md`
- 데이터 모델 → [05. 데이터 모델](05-data-model.md) 5.5
- 자동화 엔진과의 통합 → [08. 자동화 엔진](08-automation-engine.md)
