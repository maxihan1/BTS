<!-- FR-AT-01 자동화 트리거 — automation BC 착수 기반 결정 (트리거 모델·이벤트 통합·큐 토폴로지·권한 결선) -->

# ADR — FR-AT-01 자동화 트리거 (automation BC 착수)

- 날짜: 2026-07-10
- 상태: Accepted
- 관련 FR: FR-AT-01 (automation BC 첫 기능)
- 관련 SDD: 08장 (자동화 엔진)
- 관련 PR: #251

## 맥락

automation BC(TCA — Trigger-Condition-Action 자동화 엔진, 7 FR)의 첫 기능이자 BTS **9번째 Gradle
모듈**(`backend/modules/automation`, `com.bts.automation`) 착수 지점. FR-AT-01은 그중 **트리거**
절반만 담당한다 — 조건(FR-AT-03)·액션(FR-AT-02)은 후행 FR.

착수 전 코드 조사로 다음이 확인됐다.

- 이슈 이벤트는 이미 `IssueEventPublisher`가 `q_issue_events` 큐로 발행 중
  (`issue.created`/`issue.updated`/`issue.transitioned`/`issue.soft_deleted`/`issue.mentioned`/
  `issue.due_soon`/`issue.overdue` — sealed `IssueDomainEvent` 7종).
- `q_issue_events`는 **NotificationWorker 전용 경쟁소비(competing-consumer) 큐**
  (`NotificationWorker.kt:516` 주석 명시). 다른 소비자가 읽으면 알림 메시지를 훔쳐간다.
- 독립 소비자는 **전용 큐 + 발행 시점 fan-out** 패턴으로 분리한다 — `q_webhook_events`가 선례
  (FR-API-03이 아웃바운드 Webhook용으로 `IssueEventPublisher` dual-send 추가).
- **`issue.commented` 이벤트는 부재** — 댓글 기능은 있으나 도메인 이벤트를 발행하지 않는다.

## 결정

### D1. 트리거 도메인 모델 — AutomationRule 다형성

`AutomationRule` 애그리거트가 트리거를 보유한다. FR-AT-01 범위에서는 **트리거만** 저장한다.

- `automation_rules(id, project_key, name, enabled, trigger_type, trigger_config JSONB, created_by, created_at, updated_at, version)`
- `trigger_type` enum 5종: `ISSUE_CREATED` / `ISSUE_UPDATED` / `ISSUE_COMMENTED` / `SCHEDULED` / `WEBHOOK`
  (product 문서 명명 정본. SDD 8.2의 8종 중 `issue.transitioned`/`issue.assigned`는 이 FR 범위 밖,
  `pr.merged`는 FR-AT-07).
- `trigger_config`는 트리거별 형식만 검증(favorites/가젯 선례 — cross-BC 존재 검증 안 함). 예:
  `ISSUE_UPDATED`는 `{fields: [...]}` 필터, `SCHEDULED`는 `{cron: "0 9 * * *"}`, `WEBHOOK`은 발급 토큰.
- 조건·액션 컬럼은 FR-AT-02/03이 추가(현 스키마에 미포함).

### D2. 이벤트 통합 — 전용 큐 `q_automation_events` + fan-out

automation 소비자는 `q_issue_events`를 읽지 **않는다**(notification 전용). 대신:

- 신규 큐 `q_automation_events`.
- `IssueEventPublisher`를 확장해 automation 관심 이벤트를 이 큐로 **fan-out**(`q_webhook_events` dual-send과 동형).
  automation 대상 = `issue.created` / `issue.updated` / `issue.commented`.
- automation `AutomationEventWorker`(`@Scheduled` 폴링, pgmq consumer)가 `q_automation_events`를 소비.
- **cross-BC touch 명시**: `IssueEventPublisher`(issue-tracking BC) 변경은 BC 격리 예외.
  producer가 downstream 소비자에게 fan-out하는 확립된 패턴(FR-API-03 선례). plan §리스크에 기록.

### D3. `issue.commented` 신규 이벤트 (issue-tracking 확장)

COMMENTED 트리거를 위해 issue-tracking에 `IssueCommented` 이벤트를 추가한다(Maxi 확정 — 5종 전부).

- `IssueDomainEvent` sealed 인터페이스에 `IssueCommented(issueKey, projectKey, commentId, actorId, occurredAt)` 추가.
- 댓글 생성 서비스가 같은 트랜잭션에서 `IssueEventPublisher.publish(IssueCommented(...))` 호출
  (`Propagation.MANDATORY` outbox 패턴 준수).
- **회귀 주의**: sealed 서브타입 추가 → `isWebhookPublishable`의 exhaustive `when` 및 다른 소비 지점
  exhaustive `when`이 컴파일 실패. 전 모듈 `when (event)` grep으로 전수 갱신(`IssueCommented -> false`
  기본, over-send 무해화). [[enum-add-breaks-crossmodule-count-guard]] 패턴.

### D4. 발화 결과 — `q_automation_execution` enqueue (FR-AT-02 이음선)

액션(FR-AT-02) 부재 상태에서 매칭된 트리거의 결과(Maxi 확정 — 실행 큐 enqueue).

- 이벤트 도착 → enabled 룰 중 `trigger_type` + `trigger_config` 매칭 → **`q_automation_execution` 큐에
  `{ruleId, triggerType, triggerEvent}` 적재**.
- FR-AT-02 액션 executor가 이 큐를 소비(현 FR은 미구현 — 이음선만).
- FR-AT-01 테스트는 매칭 후 큐 메시지 도달을 단언(end-to-end 관측). FR-AT-05 `rule_executions`와 **미중복**.
- 무한루프 방지(automation.md 결정 — 체인 깊이 10)는 액션이 새 이벤트를 유발하는 FR-AT-02 시점 도입.

### D5. MANAGE_AUTOMATION 권한 동반 결선

product §0 진입조건. `MANAGE_AUTOMATION` 권한 코드를 automation BC 착수와 함께 결선(dead 시드 회피).

- 룰 CRUD API는 `MANAGE_AUTOMATION` 가드. cross-BC 권한은 identity-access `SystemPermissionResolver`
  fail-closed 창구 재사용(권한코드+resolver, role 직접조회 금지 — [[crossbc-permission-resolver-not-role-lookup]]).
- 권한코드 시드는 `SchemaMigrationTest` 카운트를 깬다([[fr-pm-permission-seed-migration-test-coupling]]) — 동반 갱신.

### D6. PR 범위 — 백엔드 코어 D1~D5

이번 PR = D1~D5(도메인·명세·데이터모델·백엔드·Testcontainers). D6(트리거 선택 UI)·D7(E2E)는 후속 PR
(slack-integration FR-SL-01 선례: PR#244 코어 → PR#247 UI).

## 결과 / 파급

- 신규 모듈 `backend/modules/automation` — `settings.gradle` 등록 + test-boot 조립 배선
  ([[new-bc-first-repository-testboot-context-regression]]: 빈-컨텍스트 test-boot에 첫 @Repository/@Service →
  Testcontainers @TestConfiguration 배선 필요).
- 첫 `@Scheduled` 워커 → `@EnableScheduling` 결선 필수 + detektMain type-resolved 엄격
  ([[module-first-scheduled-worker-detektmain-traps]]).
- pgmq consumer 메시지 생명주기(read→처리→archive/delete) 필수 준수([[pgmq-consumer-message-lifecycle-p0]]).
- cross-BC touch 2건(issue-tracking): `q_automation_events` fan-out + `IssueCommented` 이벤트. BC 격리 예외 명시.
- Flyway V번호 — automation 모듈 마이그레이션 prefix 정책 확인([[migration-vnumber-concurrent-branch-collision]]).

## 대안 (기각)

- **automation이 `q_issue_events` 직접 소비** — notification과 경쟁소비, 메시지 도난. 기각.
- **발화 시 rule_executions row 기록** — FR-AT-05 실행이력 테이블 선점. enqueue 이음선이 더 깨끗. 기각.
- **COMMENTED 보류(4종)** — fan-out이 이미 issue-tracking을 건드리므로 IssueCommented 추가는 증분. Maxi 5종 확정.
