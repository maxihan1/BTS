# 07. 워크플로우 엔진

> 워크플로우 엔진의 구체적 구현은 `.claude/skills/atlas-workflow-engine/SKILL.md` 참조.

## 7.1 설계 개요

Atlas의 워크플로우 엔진은 FSM(유한 상태 기계) 기반. Jira의 Workflow 개념을 단순화하여 채택.

### 핵심 기능

- 상태 (State) + 카테고리 (TODO/IN_PROGRESS/DONE)
- 전이 (Transition): from → to 매핑
- Validator: 전이 가능 여부 검증
- Post-function: 전이 후 후처리
- WorkflowScheme: 프로젝트 + 이슈 타입별 매핑
- YAML 정의 (Git으로 버전 관리)

## 7.2 표준 워크플로우 4종

| 키 | 용도 | 상태 흐름 |
|---|---|---|
| `software-default` | 일반 SW 개발 | To Do → In Progress → Review → Done |
| `bug-tracking` | 버그 관리 | New → Triage → In Progress → Verified → Closed |
| `simple` | 단순 | Open → Closed |
| `kanban-basic` | 칸반 기본 | Backlog → To Do → In Progress → Done |

## 7.3 Validator 표준

| 타입 | 용도 |
|---|---|
| `RequiredField` | 특정 필드 입력 필수 |
| `Permission` | 권한 보유 검증 |
| `NotStatusCategory` | 특정 카테고리 진입 불가 |
| `CustomExpression` | SpEL 표현식 |

## 7.4 Post-function 표준

| 타입 | 용도 |
|---|---|
| `SetField` | 필드 자동 설정 (예: resolution=fixed) |
| `AddWatcher` | Watcher 자동 추가 |
| `Notify` | 알림 발송 |
| `CallWebhook` | 외부 시스템 통지 |
| `RunAutomation` | 자동화 규칙 실행 |

## 7.5 다음 챕터

- 구체 구현 → `.claude/skills/atlas-workflow-engine/SKILL.md`
- 데이터 모델 → [05. 데이터 모델](05-data-model.md) 5.5
- 자동화 엔진과의 통합 → [08. 자동화 엔진](08-automation-engine.md)
