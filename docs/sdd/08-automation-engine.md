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
| `SetFixVersions` | 수정 예정 버전(Fix Version) 설정 |
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

> 위 예시는 개념 설명용(일부 액션/템플릿은 미구현). 실제 구현된 import/export 계약은 §8.5.1.

### 8.5.1 YAML import/export (FR-AT-06, GitOps)

자동화 규칙을 YAML로 내보내고(`GET`) 올려 upsert하는(`POST`) GitOps 백엔드. 규칙 1개 = `automation_rules` 1행 + `automation_actions` N행 + `automation_conditions` 0..1행을 YAML 문서 하나로 왕복(신규 마이그레이션 없음, V300/V302/V304 활용).

```
GET  /api/v1/projects/{projectKey}/automation/rules/export   → 200 application/yaml;charset=UTF-8
POST /api/v1/projects/{projectKey}/automation/rules/import    (@RequestBody YAML) → 200 AutomationImportResponse
```

권한 = `MANAGE_AUTOMATION`(리소스 접근 이전 fail-closed). 경로는 프로젝트 스코프(기존 automation 엔드포인트 정렬 — SDD 원안 flat 경로에서 deviation).

**YAML 스키마 v1**.

```yaml
version: 1
projectKey: PROJ
rules:
  - id: 550e8400-e29b-41d4-a716-446655440000   # 선택(export는 항상 채움, 손 작성 시 생략=새 규칙)
    name: "버그 자동 할당"
    enabled: true
    actorUserId: 123e4567-e89b-12d3-a456-426614174000   # 선택(생략 시 import 호출자)
    trigger: { type: ISSUE_CREATED, config: {} }
    condition: { and: [ {"==": [{"var": "issue.type"}, "Bug"]} ] }   # 선택(생략=조건 없음)
    actions:
      - { type: SET_FIELD, config: { field: priority, value: 1 } }
```

- **식별 = UUID id 기준 upsert**. id 있고 이 프로젝트 존재→UPDATE / id 있고 전역 미존재→id 보존 CREATE(멱등) / id 있고 타 프로젝트·소프트삭제 소유→400(PK 전역 유일성) / id 부재→새 UUID CREATE.
- **원자성 = atomic fail-closed**. 단일 트랜잭션, 하나라도 실패 시 전량 롤백. 충돌 정적 분석(§8.7)은 커밋 후 별도 호출로 응답 `conflicts`에 병합(rollback-only 오염 회피).
- **검증 재사용**. name≤200·trigger cron/fields·조건 MAX_DEPTH=10/MAX_NODES=100/FIELD_WHITELIST·action url http(s) 전부 기존 도메인 파서 통과(import가 검증 우회 없음).
- **비밀 미노출**. export에 webhook 토큰/해시·OCC version·nextFireAt 미포함. 생성된 WEBHOOK 규칙 토큰은 응답 `webhookTokens`로 1회 노출.
- **wire 비대칭 흡수**. trigger/action config·condition은 YAML 객체 ↔ 내부 JSON 문자열(도메인 파서 입력 형식)로 변환. YAML mapper는 내부 전용(전역 JSON ObjectMapper 오염 금지).
- **응답** `AutomationImportResponse{created, updated, total, ruleIds, webhookTokens?, conflicts?}`. 상한 `MAX_IMPORT_RULES=500`→413.
- **round-trip 시맨틱**. 동일 프로젝트 멱등 재적용(GitOps apply)·migrate/restore(원본 규칙 부재)는 id 보존으로 재현. 원본을 살린 채 타 프로젝트 복사는 YAML에서 `id:` 제거(새 규칙 생성).

## 8.6 실행 이력 (디버깅용)

```
AutomationRunLog (구현 테이블. rule_executions, FR-AT-05 V305):
  - rule_id            (하드 FK 없음 — 감사 독립성. 룰 하드삭제돼도 이력 보존)
  - issue_key          (구 issue_id. 이슈 무관 트리거 SCHEDULED/WEBHOOK 은 NULL)
  - project_key        (비정규화 — 스코프 목록/권한 판정, 룰 조인 회피)
  - trigger_type       (fire-time 트리거 타입)
  - trigger_event      (원본 payload. replay 동기 재실행 재료)
  - actions_executed → outcomes: [{position, actionType, success, error}]
  - replayed_from      (이 이력이 replay 로 생성됐으면 원본 실행 id, 아니면 NULL)
  - started_at, finished_at
  - status: SUCCESS / PARTIAL / FAILED / SKIPPED   (SKIPPED = 조건 게이트 FR-AT-03 불충족)
```

기록 범위는 실행 시도분(ActionExecutor 호출)만 — 억제창 스킵·malformed 는 미기록. UI에서 "이 이슈에 영향을 준 자동화" 조회 가능(`issue_key` 필터). `POST /api/v1/automation/executions/{id}/replay` 로 저장된 trigger_event 를 현재 룰 정의에 동기 재실행. 조건 평가 과정(`condition_result`) 상세 추적은 향후 확장(현재 status=SKIPPED 로 조건 불충족만 표시).

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
    type: pr.merged            # TriggerType.PR_MERGED
    config:
      targetBranch: main       # 선택 — 미지정 시 전체 브랜치에서 발화
  actions:
    - { type: SET_FIX_VERSIONS, config: { versionIds: ["6f1c2b8e-...-uuid"] } }
```

> **PR-C(#278) 구현 완료 기준으로 정정됐다.** 이전 스케치는 `webhook.received` + `conditions` +
> `{{ pr.target_branch_version }}` 를 썼으나 셋 다 실물과 다르다.
>
> - **트리거는 `pr.merged`**(`TriggerType.PR_MERGED`) — `webhook.received`(`TriggerType.WEBHOOK`)와는
>   별개 타입이다(위 §트리거 표 참조). config 는 `targetBranch` 하나가 선택이다.
> - **이슈 키 매칭은 rule `conditions` 가 아니라 수신부 책임**이다. `PrIssueKeyExtractor` 가 PR 제목·본문에서
>   `Closes/Fixes/Resolves PROJ-42` 형태를 추출한다(키워드 3계열 + 활용형, 키워드 없이 이슈 키만 있으면
>   **추출하지 않는다**). 규칙 작성자가 조건으로 기술하는 대상이 아니다.
> - **`versionIds` 는 템플릿 보간을 지원하지 않는다** — UUID 문자열 배열만 받는다(빈 배열은 전체 해제).
>   `{{ }}` 치환은 COMMENT `body` 와 WEBHOOK `url` 전용이므로(ADR D3b), "PR 대상 브랜치의 버전"을 동적으로
>   해석하는 기능은 존재하지 않는다. 버전 UUID 를 명시해야 한다.
>
> **★ PR_MERGED 는 제3의 경로다.** 다른 트리거와 달리 `q_automation_events`(issue-tracking 소유)를 타지
> 않는다. `GitWebhookController` 가 서명 검증 후 룰을 **직접 조회해 동기 enqueue** 한다. 따라서
> `TriggerMatcher` 의 wire 맵에 `pr.merged` 를 추가하면 **죽은 코드**가 된다.

## 8.9 다음 챕터

- 워크플로우 엔진과의 통합 → [07. 워크플로우 엔진](07-workflow-engine.md)
- 알림 / Slack → [09. 알림 + Slack](09-notifications-slack.md)
