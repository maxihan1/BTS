# 08. 자동화 엔진

## 8.1 설계 개요

자동화 엔진은 **TCA (Trigger-Condition-Action)** 모델 기반.

```
이벤트 발생 → 트리거 매칭 → 조건 평가 → 액션 실행
```

## 8.2 트리거 종류

| 트리거 | 설명 |
|---|---|
| `issue.created` | 이슈 생성 |
| `issue.updated` | 이슈 변경 (필드별 필터 가능) |
| `issue.transitioned` | 상태 전이 |
| `issue.commented` | 댓글 추가 |
| `issue.assigned` | 담당자 변경 |
| `schedule.cron` | 스케줄 (예: 매일 09:00) |
| `webhook.received` | 외부 Webhook |
| `pr.merged` | PR 머지 (GitHub/GitLab 연동) |

## 8.3 조건 표현식

JSONLogic 기반.

```yaml
condition:
  and:
    - {"==": [{"var": "issue.type"}, "Bug"]}
    - {"==": [{"var": "issue.priority"}, "Highest"]}
    - {"!=": [{"var": "issue.assignee"}, null]}
```

또는 SpEL (Spring Expression Language):

```yaml
condition: "#issue.type == 'Bug' && #issue.priority == 'Highest' && #issue.assignee != null"
```

## 8.4 액션 종류

| 액션 | 설명 |
|---|---|
| `SetField` | 필드 변경 |
| `Assign` | 담당자 설정 |
| `AddComment` | 댓글 추가 (템플릿 변수 지원) |
| `AddLabel` | 라벨 추가 |
| `Transition` | 상태 전이 (워크플로우 거침) |
| `CallWebhook` | 외부 API 호출 |
| `SendNotification` | 알림 발송 (사용자/채널 지정) |
| `CreateIssue` | 새 이슈 생성 |
| `RunSubrule` | 다른 자동화 규칙 호출 |

## 8.5 규칙 YAML 예시

```yaml
rule:
  id: auto-assign-bug-to-lead
  name: "버그를 컴포넌트 리드에게 자동 할당"
  enabled: true
  trigger:
    type: issue.created
    project: PROJ
  conditions:
    - {"==": [{"var": "issue.type"}, "Bug"]}
    - {"!=": [{"var": "issue.components"}, []]}
  actions:
    - type: assign
      target: "{{ issue.components[0].lead }}"
    - type: add-comment
      body: "자동으로 {{ assignee.name }}에게 할당되었습니다."
    - type: add-label
      labels: ["auto-assigned"]
```

## 8.6 실행 이력 (디버깅용)

```
AutomationRunLog:
  - rule_id
  - issue_id
  - trigger_event
  - condition_result (true/false + 평가 과정)
  - actions_executed: [{action, result, error}]
  - started_at, finished_at
  - status: SUCCESS / PARTIAL / FAILED
```

UI에서 "이 이슈에 영향을 준 자동화" 조회 가능.

## 8.7 충돌 정적 분석 (FR-AT-04)

규칙 저장 시 프로젝트의 규칙 집합을 자동 검증한다. 충돌 **4종**을 검출하며 전부 soft 경고(WARNING)로
저장을 막지 않는다 — 저장 응답 `conflicts` 배열에 담아 반환한다(별도 엔드포인트·테이블 없음).

- **CYCLE (사이클)** — 무한 루프 가능성. 한 규칙의 액션이 다른 규칙의 트리거를 유발하는 방향 그래프에서
  사이클(A → B → A, self-loop 포함)을 DFS로 감지.
- **FIELD_CONFLICT (필드 충돌)** — 같은 트리거에 동시 매칭되는 규칙들(또는 한 규칙 내 액션들)이 같은
  필드를 서로 다른 값으로 SET.
- **PRIORITY_AMBIGUITY (우선순위 모호)** — 같은 트리거에 매칭되는 규칙이 2개 이상이고 실행 순서가
  생성 시각·id로만 결정되어(우선순위 컬럼 부재) 결과가 순서에 의존.
- **PERMISSION_MISSING (권한 부족)** — 규칙의 실행 주체(rule actor)가 액션 대상의 프로젝트 레벨 이슈
  편집 권한이 없어 런타임에 fail-closed로 막힐 액션. (프로젝트 레벨 근사 — 이슈별 보안등급은 런타임 방어.)

## 8.8 PR 머지 연동 (FR-AT-07)

```yaml
rule:
  id: fix-version-on-pr-merge
  trigger:
    type: webhook.received
    source: github
    event: pull_request.merged
  conditions:
    - PR 본문에 "Closes PROJ-N" 또는 "Fixes PROJ-N" 패턴 매칭
  actions:
    - type: set-field
      target_issue: "{{ extracted_issue_key }}"
      field: fix_version_ids
      value: ["{{ pr.target_branch_version }}"]
```

## 8.9 다음 챕터

- 워크플로우 엔진과의 통합 → [07. 워크플로우 엔진](07-workflow-engine.md)
- 알림 / Slack → [09. 알림 + Slack](09-notifications-slack.md)
