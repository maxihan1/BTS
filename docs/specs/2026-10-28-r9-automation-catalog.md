# R9 Automation 카탈로그 — 트리거 · 액션 확장 스펙 (FR-AT-01 · FR-AT-02)

대상: FR-AT-01(트리거) · FR-AT-02(액션) 의 **범위 보강**. 새 FR 을 만들지 않는다(FR 총수 불변).
근거: 갭 JAU-5 · JAU-6(`docs/design/2026-10-28-jira-cloud-rea-gap.md`, REA 관찰 2026-10-28 · 결정 D1).
로드맵: `docs/design/jira-parity-roadmap.md` 「2차 캠페인」 R9 행. BTS 기준 `main` `cb9565f90`.

## 1. 지금 있는 것

| 구분 | 값 | 정본 |
|---|---|---|
| 트리거 6종 | ISSUE_CREATED · ISSUE_UPDATED(`fields` 필터 선택) · ISSUE_COMMENTED · SCHEDULED · WEBHOOK · PR_MERGED | `automation/domain/TriggerType.kt` · DB `ck_automation_rules_trigger_type`(V309) |
| 액션 5종 | SET_FIELD · ASSIGN · ADD_COMMENT · CALL_WEBHOOK · SET_FIX_VERSIONS | `automation/domain/ActionType.kt` · DB `ck_automation_actions_action_type`(V306) |
| 이슈 이벤트 경로 | issue-tracking `IssueEventPublisher.isAutomationPublishable` 이 created · updated · commented 만 `q_automation_events` 로 fan-out → `AutomationEventWorker` → `TriggerMatcher` | |
| 이슈 쓰기 경로 | shared-kernel `IssueMutationPort`(setField · assign · addComment · setFixVersions) → issue-tracking `AutomationIssueMutationAdapter` | |
| 루프 가드 | executionDepth > 10 · (ruleId, issueKey) 60초 억제 | `AutomationExecutionWorker` |

이미 발행되지만 automation 이 못 받는 이벤트: `issue.transitioned`(fromState · toState · actorId · cause) · `issue.assigned`(actorId).

## 2. 추가할 것 — 단계별

각 단계는 BC 마다 PR 하나(issue-tracking → automation → web 순). 한 단계가 끝나야 다음 단계를 연다.

### 단계 A — 트리거: 상태 전환됨 · 할당됨 · 필드 값 변경됨

| ID | 동작 | 결정 |
|---|---|---|
| A1 | `ISSUE_TRANSITIONED` — 상태 전환 시 발화. config `{fromStates?: string[], toStates?: string[]}` (상태 키). 비우면 모든 전환 | `cause != null`(관리자 상태 이관 `STATUS_MIGRATION`)은 **발화하지 않는다** — 이관 1회가 N건 발화로 번지는 것을 막는다(Jira 도 워크플로 이관은 전환 트리거를 부르지 않는다). 거르는 곳은 automation `TriggerMatcher`(producer 는 그대로 싣는다 — cause 계약 F11) |
| A2 | `ISSUE_ASSIGNED` — 담당자가 실제로 바뀔 때 발화(지정 · 변경 · 해제). config 없음 | 이벤트에 새 담당자 id 가 없다. 조건(FR-AT-03)은 최신 스냅샷으로 평가하므로 「담당자 = X 일 때」는 조건으로 건다. 이벤트 계약은 바꾸지 않는다 |
| A3 | 「필드 값 변경됨」 — 새 enum 을 만들지 않는다. 기존 `ISSUE_UPDATED` + `fields` 필터가 같은 능력이다 | web 표시명만 「이슈 수정」→「필드 값 변경됨」, `fields` 입력을 필드 선택 목록으로 |

- issue-tracking PR: `isAutomationPublishable` 에서 `IssueTransitioned` · `IssueAssigned` → `true`. 큐 · 이벤트 모양 무변경.
- automation PR: `TriggerType` 2종(+ 단계 B 의 MANUAL) + **V311** CHECK 9종(DROP → ADD, 이름 유지, COMMENT 재발행 — `automation_rules` · `rule_executions` 둘 다) · `TriggerConfig` 검증(ISSUE_TRANSITIONED 의 fromStates/toStates = 비지 않은 문자열 배열) · `TriggerMatcher` 매핑 + 상태 필터 + cause 거르기 · `RuleConflictAnalyzer` 의 트리거 ↔ 액션 사이클 표(전환 액션 도입 전이라 ASSIGN → ISSUE_ASSIGNED 만 추가) · YAML codec(Jackson enum 이라 그대로 통과 — 별도 테스트 없음).
- web PR: `triggerTypeSchema` 9종, 폼에 두 트리거 + 전환 트리거의 From/To 상태 다중 선택(프로젝트 워크플로 상태 목록), 목록 배지 라벨.

### 단계 B — 트리거: 수동 실행

| ID | 동작 | 결정 |
|---|---|---|
| B1 | `MANUAL` — 이슈 상세에서 사람이 골라 실행. config 없음 | `POST /api/v1/projects/{projectKey}/automation/manual-rules/{ruleId}/run` body `{issueKey}` → 202. 권한 = 요청자가 그 이슈 **열람 가능**(`IssueSnapshotPort` 요청자 가시성 — 보안 수준 포함) + 룰이 `MANUAL` · enabled · 같은 프로젝트. 하나라도 틀리면 404(존재 숨김). 실행은 큐(`q_automation_execution`)로 — triggerEvent `{type:"manual", issueKey, projectKey, actorId}` 를 **서버가 만든다**(요청 body 의 다른 값은 싣지 않는다 — 프로젝트 경계 게이트 전제 유지). 액션은 룰 actor 권한(Jira 와 같음) |
| B2 | 이슈 상세 헤더 ⚡ 「자동화」 버튼(Jira 이슈 화면 자리) — 이 프로젝트의 MANUAL 룰 목록, 고르면 실행 + 토스트 | `GET /api/v1/projects/{projectKey}/automation/manual-rules` → `[{id, name}]`. 권한 = 프로젝트 BROWSE(기존 룰 목록은 `MANAGE_AUTOMATION` 이라 따로 둔다). 같은 룰 · 같은 이슈를 60초 안에 다시 부르면 실행 워커의 루프 억제 창에 걸려 조용히 건너뛴다(알려진 한계) |

- automation PR: 단계 A 와 **같은 PR** 이다(같은 BC · 마이그레이션 V311 한 번). 형식이 틀린 이슈 키(`PROJ-123` 아님)는 포트 조회 전에 404 — issue-tracking 어댑터가 형식 오류를 500 으로 던지기 때문.

### 단계 C — 액션: 이슈 전환 · 이슈/하위 작업 생성 · 연결 · 관찰자

shared-kernel `IssueMutationPort` 에 4 메서드 + 커맨드 4종. issue-tracking 어댑터는 기존 서비스에 위임(도메인 우회 금지 · actor 권한 · dryRun = 롤백).

| ID | ActionType | config | 위임 대상 | 비고 |
|---|---|---|---|---|
| C1 | `TRANSITION` | `{toState}` | `IssueApplicationService.transition` | 필수 화면 · 검증기 실패 = 그 액션 FAILED. 루프 — 전환 액션 ↔ 전환 트리거가 사이클 표에 들어간다 |
| C2 | `CREATE_ISSUE` | `{summary(템플릿), issueTypeKey, asSubtask?: boolean}` | `IssueApplicationService.create` | `asSubtask=true` 면 parentKey = 트리거 이슈(R1b 의 `parentKey` · 422 규칙 그대로). 대상 프로젝트 = 룰 프로젝트 고정 |
| C3 | `LINK_ISSUE` | `{linkTypeId, targetIssueKey(템플릿)}` | `IssueLinkService` | 대상 키도 룰 프로젝트 소속만(경계 게이트 확장) |
| C4 | `ADD_WATCHER` | `{userId}` 또는 `{fromField: "reporter"\|"assignee"}` | `IssueWatcherService.watch` | |

- **V312** — `ck_automation_actions_action_type` 9종 + `action_type` 폭 확인(`CREATE_ISSUE` 12자 — VARCHAR(20) 안).
- web: `ActionConfigEditor` 에 4 편집기.

### 단계 D — 트리거: 스프린트 · 버전 / 액션: 이메일

| ID | 무엇 | 필요한 것 |
|---|---|---|
| D1 | `SPRINT_STARTED` · `SPRINT_COMPLETED` | agile-planning 이 스프린트 이벤트를 pgmq 로 발행하지 않는다 — 새 큐(agile-planning 소유) · 이벤트 계약 · automation 구독 워커. 이슈 없는 트리거라 이슈 액션은 「스프린트의 이슈들」 브랜치(JAU-8, P2) 없이는 쓸 데가 적다 |
| D2 | `VERSION_RELEASED` | 버전은 issue-tracking 소유 — 릴리스 이벤트 발행 추가 |
| D3 | `SEND_EMAIL` | 받는 사람(담당자 · 보고자 · 관찰자 · 지정 사용자) · 제목/본문 템플릿. notification BC 의 `EmailChannelSender` 로 pgmq 위임(새 큐). 사외 발송이라 **SECURITY** 렌즈 — 수신자는 사내 사용자 id 만(임의 주소 금지) |

단계 D 는 이 스펙에서 방향만 정한다. 착수 전 이벤트 계약을 따로 확정한다.

## 3. 안 하는 것

- 트리거 안 조건 · 액션 사이 조건 · IF/ELSE(JAU-7) · 브랜치(JAU-8) · 스마트 값 패널(JAU-9).
- 이슈 복제 액션 · 이슈 편집(다중 필드) 액션 · 변수 · 조회 표 — 갭 행에 없는 것.
- 전체 화면 캔버스 편집기(JAU-4).

## 4. 위험

- 마이그레이션(V311 · V312) — CHECK 상위집합 교체라 backfill · 실패 없음(V306 · V309 선례).
- shared-kernel(단계 C 포트 확장) — consumer-owns-stub(`StubIssueMutationPort`) · slack-integration 소비자 컴파일 확인.
- 보안 — 수동 실행 엔드포인트(이슈 열람 권한 · 서버가 triggerEvent 생성), 이메일(사내 수신자만).
- 루프 — 전환/할당/생성 액션이 같은 프로젝트 트리거를 다시 부른다. 런타임 가드(depth 10 · 60초 억제) + 충돌 분석 사이클 표 갱신.

## 5. FR 문서

FR-AT-01 · FR-AT-02 의 제목 범위가 늘어난다 — 단계 PR 마다 `docs/plan/product/automation.md` 해당 § 에 「보강」 한 줄과 SDD 표 행 갱신, `bash scripts/verify-master-plan.sh` 0. FR 수 · D 마커는 그대로.
