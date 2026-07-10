# FR-SL-02 Slack 알림 발송

> slug: fr-sl-02-slack-notify
> type: backend
> agent: backend-engineer
> BC: slack-integration (com.bts.slack)
> 생성: 2026-07-10

## Brief

FR-SL-02 — 이슈/워크플로우 이벤트를 Slack 채널로 발송.
classify: type=backend, agent=backend-engineer.
(주의) classify가 primary_bc=issue-tracking으로 오판. 실제 구현 BC=slack-integration,
이슈/워크플로우는 이벤트 소스(cross-BC, pgmq 이벤트 구독)일 뿐.

## 도메인 정리

- **BC**: slack-integration (`com.bts.slack`). classify의 primary_bc=issue-tracking 오판 → slack-integration 정정 확정. 이슈/워크플로우는 cross-BC 이벤트 소스일 뿐.
- **이번 범위 (Maxi 게이트 확정)**: 백엔드 코어 **D1~D5**. FR-SL-01과 동일한 2-PR 분할 (D6 사용자↔Slack 매핑 UI + D7 E2E는 후속 PR). 채널↔프로젝트 라우팅은 FR-SL-06. 이번엔 **DM 중심 + 채널 전송 primitive**만.
- **전송 아키텍처 (Maxi 게이트 확정)**: **비동기 pgmq 큐**. notification BC가 `q_slack_deliveries` 큐에 JSON 이벤트 발행 → slack-integration의 자체 워커가 소비해 `chat.postMessage`. 429 백오프·dead-letter는 slack 쪽에서 처리. webhook(`q_transition_events` → WebhookDispatchWorker) 선례와 동형.

### 핵심 발견 (기존 인프라)
- `com.bts.notification.domain.Channel` enum에 **SLACK 이미 존재**. `NotificationWorker`(pgmq consumer)는 SLACK 수신자를 처리하도록 설계됨(구독 필터 EC9에서 SLACK 무조건 통과). 그러나 ① SLACK 지원 `NotificationChannelSender` 부재 → `no_sender_for_channel` 경고 후 폐기, ② 시드 정책이 전부 `IN_APP`(V401/V403) → SLACK 수신자 미생성.
- FR-SL-01 자산: `slack_installs`(team_id별 봇 토큰, AES-256-GCM `slackSecretEncryptor`), `slack-api-client`(client 층, Bolt 미도입), `SecretEncryptor`, cross-BC `SystemPermissionResolver`.
- shared-kernel 포트 다수 존재(UserLookupPort 등). 이번 비동기 큐 방식은 **cross-BC 코드 import 0**(pgmq JSON 계약만) — 신규 shared 포트 불요.

### 영향 엔티티 / 신규
- **SlackUser** (신규 `user_slack_mapping(user_id, slack_user_id, team_id, linked_at)`) — Atlas user ↔ Slack user 매핑. DM 대상 해석.
- **q_slack_deliveries** (신규 pgmq 큐) — notification → slack 전달 경로.
- notification BC: `SlackChannelSender`(implements `NotificationChannelSender`, supports SLACK) → 큐 발행 (slack 코드 import 0).
- slack-integration BC: `SlackDeliveryWorker`(q_slack_deliveries consumer) + `SlackMessageClient`(chat.postMessage) + Block Kit 포맷터.

### 열린 스펙 질문 (→ /bts-spec)
1. SLACK 수신자를 어떻게 생성하나 — SLACK `notification_policies` 시드(멘션·할당, SDD §9.4 "멘션 시 Slack DM") vs 사용자 구독 설정(FR-NT-04). MVP 관찰가능성 위해 최소 시드 제안.
2. user_slack_mapping 생성(linking) 메커니즘 — Slack OIDC "Sign in with Slack" vs 백엔드 매핑 엔드포인트. D6(UI) 이전 백엔드가 수용할 계약 범위.
3. 다중 워크스페이스 vs 단일 워크스페이스 가정(현 사내 1워크스페이스). team_id 저장은 하되 해석 단순화.

- **기존 결정 충돌**: 없음. webhook(FR-NT-05) 비동기 디스패치 패턴 계승.
- **관련 ADR**: `docs/decisions/2026-07-10-fr-sl-02-slack-notification-delivery.md` (생성) · [2026-07-07-fr-sl-01-slack-bot-app](../decisions/2026-07-07-fr-sl-01-slack-bot-app.md) · [2026-06-14-fr-nt-05-webhook-dispatch-ssrf](../decisions/2026-06-14-fr-nt-05-webhook-dispatch-ssrf.md)

## 스펙

전체 스펙. [docs/specs/2026-07-10-fr-sl-02-slack-notify.md](../specs/2026-07-10-fr-sl-02-slack-notify.md)

핵심 시나리오 3줄.
- Slack 계정 연결 사용자가 이슈에서 멘션되면 Slack DM 도착(멘션 정책 시드, end-to-end 동작).
- 이슈가 할당되면 Slack DM 도착 — 단 `issue.assigned` 이벤트 생산자가 부재해 issue-tracking `changeAssignee`에 `IssueAssigned` 발행을 신설(IN_APP 할당 알림도 함께 살아남).
- 미연결 사용자는 매핑 없음 → graceful skip(인앱은 정상). 429/재전달은 slack 워커에서 백오프·dead-letter.

**영향 BC 3개** (모든 경계 pgmq JSON, 코드 import 0).
- issue-tracking: `IssueAssigned` 이벤트 신설 + `changeAssignee` 발행.
- notification: `SlackChannelSender`(→ q_slack_deliveries) + 큐 생성 + SLACK 정책 시드 2종. 워커/resolver 무변경.
- slack-integration: `user_slack_mapping`·`slack_delivery_log`(V701) + `SlackDeliveryWorker` + `SlackMessageClient`(chat.postMessage) + Block Kit.

## Brainstorming Check

✅ 통과 (2 gap 실코드 검증 + Maxi 게이트).
- Gap A: 할당 이벤트 생산자 부재(할당 알림 dormant) → Maxi 결정=파이프라인 수리 포함. notification 소비 측 이미 완전 배선 → 워커 무변경.
- Gap B: `publishable=false`는 카탈로그 UI 메타일 뿐 발송 미강제 → SLACK 멘션 시드 동작.

## Plan

### 공유 계약 (모든 경계 = pgmq JSON, 코드 import 0)

`q_slack_deliveries` 메시지 JSON (T3 발행 ↔ T7 소비, 양쪽 이 계약 준수):
```json
{ "recipientUserId": "<uuid>", "eventType": "issue.mentioned",
  "issueKey": "PROJ-123", "title": "PROJ-123 에서 멘션되었습니다",
  "occurredAt": "<iso-8601>", "dedupKey": "<notification.dedupKey>" }
```

---

### Task 1. IssueAssigned 이벤트 신설 + changeAssignee 발행 (issue-tracking)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/event/IssueDomainEvent.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/event/IssueEventPublisher.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/application/IssueApplicationService.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/application/IssueAssigneeEventTest.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/application/IssueApplicationServiceTest.kt`]
- depends-on: []

**RED**. `IssueAssigneeEventTest` — `changeAssignee`가 담당자 변경 시 `IssueAssigned(issueKey, actorId, occurredAt)`를 `eventPublisher.publish`로 발행하는지 검증(mock publisher). no-op(동일 담당자)이면 발행 0건도 단언. 실패 예상: `IssueAssigned` 클래스 없음.

**GREEN**.
- `IssueDomainEvent.kt` — `@JsonTypeName("issue.assigned")` `IssueAssigned(issueKey: IssueKey, actorId: ActorId, occurredAt: Instant)` 추가 + `JsonSubTypes.Type` 등록.
- **`IssueEventPublisher.kt` (C2 컴파일 결합)** — `isWebhookPublishable`의 exhaustive `when(event)`(L70-76, else 없음)에 `is IssueAssigned -> false` 추가(알림 이벤트, webhook 대상 아님 — IssueUpdated/Mentioned와 동일). **미추가 시 컴파일 실패**.
- `IssueApplicationService.changeAssignee` — `recordHistory` 이후 `eventPublisher.publish(IssueAssigned(key, actor, <occurredAt>))`. `assigneeChanged` 분기 안에서만(no-op 조기반환 뒤).
- **`IssueApplicationServiceTest.kt` (B3 strict-mock 회귀)** — `eventPublisher = mockk()`(strict). `changeAssignee` happy-path context (a·c)의 `beforeEach`에 `every { eventPublisher.publish(any()) } just Runs` 스텁 추가. **미추가 시 미스텁 호출 → MockKException red**.

**REFACTOR**. occurredAt 소스를 형제 이벤트(IssueCreated/IssueTransitioned)와 동일하게(Clock 주입/Instant.now 확인 후 일치). KDoc.

**검증**. `./gradlew :backend:modules:issue-tracking:test`(전체 — B3 회귀 확인, 특정 테스트만 X)
**유의**. sealed class 추가 → issue-tracking 내 `when(event: IssueDomainEvent)` exhaustive 분기 전수 grep(IssueEventPublisher 확인됨, 그 외도) 후 처리. notification은 JSON type 파싱이라 무영향. occurredAt dedupKey 결정성(memory Clock 주입).

---

### Task 2. q_slack_deliveries 큐 + SLACK notification_policies 시드 (notification)

**메타**.
- agent: `db-engineer`
- files: [`backend/modules/notification/src/main/resources/db/migration/notification/V409__slack_notification_delivery.sql`, `backend/modules/notification/src/test/kotlin/.../repository/NotificationPolicyRepositoryIntegrationTest.kt`, `backend/modules/notification/src/test/kotlin/com/bts/notification/NotificationDeliveryEndToEndIntegrationTest.kt`]
- depends-on: []

**RED**. 정책 repository/카운트 테스트 — `issue.mentioned/MENTIONED/SLACK`·`issue.assigned/ASSIGNEE/SLACK` 2행 존재 기대. 실패: 행 없음.

**GREEN**. **V409**(B1 — V408 이미 `dashboard_share_tokens` 점유) —
- `CREATE EXTENSION IF NOT EXISTS pgmq CASCADE; SELECT pgmq.create('q_slack_deliveries');` (**C1 — producer-creates 관례. producer=notification의 SlackChannelSender**. 기존 8개 큐 모두 producer 모듈 생성과 정합. spec FR2와 일치.)
- `INSERT INTO notification_policies (event_type, recipient_role, channel, enabled, project_key, created_by) VALUES ('issue.mentioned','MENTIONED','SLACK',TRUE,NULL,NULL),('issue.assigned','ASSIGNEE','SLACK',TRUE,NULL,NULL)`.

**REFACTOR**. 마이그레이션 L1 주석(FR-SL-02, SDD §9.4).

**★B2 회귀 (필수)**. 전역 SLACK 멘션 정책 추가 → 멘션 이벤트가 이제 **IN_APP + SLACK 2 수신자** 생성 → `NotificationDeliveryEndToEndIntegrationTest`의 멘션 단언이 깨짐. 수정.
- L236 `COUNT(*) ... event_type='issue.mentioned'` `isEqualTo(1L)` → **2L**(또는 `channel='IN_APP'` 필터 추가로 IN_APP만 카운트 — 더 명시적, 권장).
- L240 `fetchOne(SELECT status ...)` → 2행이 되어 `TooManyRowsException` → `channel='IN_APP'` 조건 추가로 단일 행 유지.
- L307 `COUNT(*) ... issue_key=?` `isEqualTo(1L)` → 채널 필터 or 2L.
- SLACK 행은 unmapped라도 생성됨(BC 격리상 notification은 매핑 모름) — SlackChannelSender가 큐 발행, 워커가 skip. 이 동작을 테스트 주석에 명시.

**검증**. `./gradlew :backend:modules:notification:test`(전체 — B2 E2E 회귀 확인)
**유의**. 기존 정책 **카운트 단언**(SchemaMigrationTest 류) 갱신 필수(memory `fr-pm-permission-seed-migration-test-coupling`, `enum-add-breaks-crossmodule-count-guard`). 전 모듈 grep으로 정책 수 하드코딩 단언 확인.

---

### Task 3. SlackChannelSender — SLACK 수신자 → q_slack_deliveries 발행 (notification)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/notification/src/main/kotlin/com/bts/notification/channel/SlackChannelSender.kt`, `backend/modules/notification/src/test/kotlin/com/bts/notification/channel/SlackChannelSenderTest.kt`]
- depends-on: []

**RED**. 단위 테스트(DSLContext mock) — `supports(Channel.SLACK)==true`, 다른 채널 false. `send(notification)`이 `dsl.execute("SELECT pgmq.send(?, ?::jsonb)", "q_slack_deliveries", <공유계약 JSON>)` 호출(recipientUserId·eventType·issueKey·title·occurredAt·dedupKey). 실패: 클래스 없음.

**GREEN**. `@Component SlackChannelSender(dsl, objectMapper)` — `supports`/`send`. JSON = ObjectMapper로 공유 계약 필드 직렬화 후 pgmq.send.

**REFACTOR**. QUEUE_NAME 상수, KDoc(BC 격리 — slack import 0, pgmq JSON 경계).

**검증**. `./gradlew :backend:modules:notification:test --tests "*SlackChannelSenderTest*"`
**유의**. slack-integration import 0. Notification.dedupKey(channel=SLACK 포함) 그대로 전달. 워커는 무변경(sender 자동 주입).

---

### Task 4. slack V701 마이그레이션 — user_slack_mapping · slack_delivery_log (slack-integration)

**메타**.
- agent: `db-engineer`
- files: [`backend/modules/slack-integration/src/main/resources/db/migration/slack-integration/V701__slack_notification_delivery.sql`, `backend/modules/slack-integration/src/test/kotlin/.../SlackMigrationSchemaTest.kt`]
- depends-on: []

**RED**. 스키마 테스트 — `user_slack_mapping`(user_id PK, slack_user_id, team_id, linked_at)·`slack_delivery_log`(dedup_key PK, sent_at) 존재 기대. 실패: 미존재.

**GREEN**. `CREATE TABLE user_slack_mapping (user_id UUID PRIMARY KEY, slack_user_id TEXT NOT NULL, team_id TEXT NOT NULL, linked_at TIMESTAMPTZ NOT NULL DEFAULT now())` + `CREATE TABLE slack_delivery_log (dedup_key TEXT PRIMARY KEY, sent_at TIMESTAMPTZ NOT NULL DEFAULT now())`.

**REFACTOR**. L1 주석(FR-SL-02). 인덱스(slack_user_id 조회는 PK user_id 경유라 불요).

**검증**. `./gradlew :backend:modules:slack-integration:test --tests "*SlackMigrationSchemaTest*"`
**유의**. **큐(q_slack_deliveries)는 T2 notification 마이그레이션이 생성**(C1 producer-creates 정합). slack 워커 통합 테스트(T7)는 큐가 자기 모듈 마이그레이션에 없으므로 **테스트 셋업에서 `pgmq.create('q_slack_deliveries')`(idempotent) 배선**해 self-contained 유지(memory `bts-cross-bc-test-migration`). V700 다음 = V701 확인됨. slack은 JdbcTemplate이라 jOOQ codegen 무관.

---

### Task 5. SlackUserMappingRepository + Service — link/unlink/resolve (slack-integration)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/slack-integration/src/main/kotlin/com/bts/slack/persistence/JdbcSlackUserMappingRepository.kt`, `backend/modules/slack-integration/src/main/kotlin/com/bts/slack/application/SlackUserMappingRepository.kt`, `backend/modules/slack-integration/src/main/kotlin/com/bts/slack/application/SlackUserMappingService.kt`, `backend/modules/slack-integration/src/test/kotlin/.../SlackUserMappingServiceIntegrationTest.kt`]
- depends-on: [4]

**RED**. Testcontainers 통합 — link(userId, slackUserId, teamId) → resolveByUserId(userId) round-trip, unlink 후 null, upsert(재link 시 갱신). 실패: repo/service 없음.

**GREEN**. `NamedParameterJdbcTemplate` upsert(ON CONFLICT user_id) / select / delete. `SlackUserMappingService` link/unlink/resolveByUserId. `SlackUserMapping` 도메인/DTO.

**REFACTOR**. `:param` 바인딩(SQL injection 방어, FR-SL-01 관례). KDoc.

**검증**. `./gradlew :backend:modules:slack-integration:test --tests "*SlackUserMappingServiceIntegrationTest*"`
**유의**. 신규 @Repository/@Service test-boot 배선(memory `new-bc-first-repository-testboot-context-regression` — FR-SL-01이 이미 첫 @Repository 해소, 기존 테스트 config 재사용). team_id 저장(단일 워크스페이스 가정이나 컬럼 보존).

---

### Task 6. SlackMessageClient + Block Kit 렌더러 — chat.postMessage (slack-integration)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/slack-integration/src/main/kotlin/com/bts/slack/message/SlackMessageClient.kt`, `backend/modules/slack-integration/src/main/kotlin/com/bts/slack/message/SlackBlockKitRenderer.kt`, `backend/modules/slack-integration/src/main/kotlin/com/bts/slack/config/SlackProperties.kt`, `backend/modules/slack-integration/src/test/kotlin/.../SlackMessageClientTest.kt`, `.../SlackBlockKitRendererTest.kt`]
- depends-on: []

**RED**.
- `SlackBlockKitRendererTest` — render(title, issueKey) → 이슈 링크(`{base-url}/issues/{issueKey}`) 포함 Block Kit.
- `SlackMessageClientTest`(MethodsClient mock) — `postDirectMessage(botToken, slackUserId, blocks)`가 `chatPostMessage`를 token·channel=slackUserId·blocks로 호출. 성공/Slack 오류 코드 분기 반환.

**GREEN**. `MethodsClient.chatPostMessage { req -> req.token(t).channel(target).blocksAsString(...) }` 래퍼 + Block Kit 빌더. `SlackProperties`에 `bts.atlas.base-url` 추가.

**REFACTOR**. 결과 sealed(Sent/Failed/PermanentError) — 429/5xx vs 4xx(invalid_auth/channel_not_found) 분기. 토큰 로그 미노출.

**검증**. `./gradlew :backend:modules:slack-integration:test --tests "*SlackMessageClient*" --tests "*SlackBlockKitRenderer*"`
**유의**. 토큰 평문 미노출(FR-SL-01 3중 관례). DM 대상=slack_user_id(chat.postMessage가 IM 자동 해석; 필요 시 conversations.open 고려 — impl에서 SDK 동작 확인). base-url config 신규.

---

### Task 7. SlackDeliveryWorker — q_slack_deliveries consumer (slack-integration)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/slack-integration/src/main/kotlin/com/bts/slack/worker/SlackDeliveryWorker.kt`, `backend/modules/slack-integration/src/main/kotlin/com/bts/slack/persistence/JdbcSlackDeliveryLogRepository.kt`, `backend/modules/slack-integration/src/main/kotlin/com/bts/slack/application/SlackInstallService.kt`, `backend/modules/slack-integration/src/test/kotlin/.../SlackDeliveryWorkerIntegrationTest.kt`]
- depends-on: [4, 5, 6]

**RED**. 워커 테스트(MethodsClient mock + Testcontainers, 셋업에서 `pgmq.create('q_slack_deliveries')`) —
- 매핑 있음 → chat.postMessage 호출 + slack_delivery_log 기록 + pgmq.delete.
- 매핑 없음(EC1) → postMessage 미호출 + delete(skip).
- install 없음(EC2) → 경고 + delete.
- **429/5xx(EC3, S4) → postMessage 실패 → slack_delivery_log 기록 안 됨 + delete 안 함(재전달) → 재시도 시 정상 전송**(B4 회귀 테스트 — 전송 실패가 dedup에 박제돼 영구 유실되지 않음을 단언).
- 이미 전송됨(EC5, slack_delivery_log에 dedupKey 존재) → postMessage 미호출 + delete(재전달분 skip).
- 4xx 영구 오류(invalid_auth/channel_not_found) → postMessage 실패 + delete(재시도 무의미) + 경고.
- poison/parse 실패(EC4) → read_ct>MAX archive.

**GREEN** (★B4 — dedup 순서 수정. 전송 **성공 후** 기록).
`@Scheduled` poll → `pgmq.read`(JdbcTemplate) → JSON 파싱 → resolveByUserId(미매핑 delete) → SlackInstallService `resolveBotToken`(단일 install, 미존재 delete) → **slack_delivery_log 사전 조회(dedupKey 존재 시 skip+delete, 재전달 중복 차단)** → SlackBlockKitRenderer + SlackMessageClient 전송 → **성공 시에만 slack_delivery_log INSERT + delete** / 429·5xx 재전달(delete·log 안 함) / 4xx delete / 예외 read_ct>MAX archive.
`SlackInstallService`에 내부 `resolveBotToken()` 추가(복호화, 미노출).

**REFACTOR**. VT/BATCH webhook 워커 참고(VT=60, BATCH=5, read timeout×BATCH<VT). KDoc(@Transactional 없음 이유, BC 격리, dead-letter, **B4 dedup 순서 근거 — NotificationWorker.deliver() 교훈 인용**).

**검증**. `./gradlew :backend:modules:slack-integration:test --tests "*SlackDeliveryWorkerIntegrationTest*"`
**유의**.
- **★B4 (dedup 순서)**. slack_delivery_log 기록은 **전송 성공 후**. "전송 전 기록"은 429 실패 시 재전달분이 UNIQUE 충돌로 영구 skip → DM 유실(NotificationWorker.deliver() KDoc L269-296 "발송 전 SENT 박제" 교훈의 정확한 재현). 사전 조회(check)는 성공한 이전 시도의 재전달 중복만 차단.
- **★C3 (토큰 미노출)**. `resolveBotToken` 복호화 평문이 로그/예외/DTO에 미노출. SlackInstall.toString REDACTED 확인, SlackMessageClient 토큰 로그 금지.
- **첫 @Scheduled → @EnableScheduling 결선**(memory `module-first-scheduled-worker-detektmain-traps`, notification `SchedulingConfiguration @Profile("!test")` + 테스트 config 선례).
- @Transactional 금지(pgmq vt). 새 협력자 주입 → test-boot NoSuchBean/@MockBean(memory `new-crossbc-dep-openapi-mockbean-regression`). detektMain type-resolved 엄격.

## Plan 메타

- task 수: 7
- 예상 wave: 3 (Wave1: T1·T2·T3·T4·T6 [depends []], Wave2: T5 [dep 4], Wave3: T7 [dep 4·5·6]). 같은 모듈 task는 Gradle 컴파일 직렬(memory `bts-plan-wave-gradle-module-compile`).
- 예상 시간: 직렬 ~25분, wave 병렬 ~12분.
- TDD 강제: yes (test 커밋 선행 자동 검증).
- 영향 3 BC: issue-tracking(T1) · notification(T2·T3) · slack-integration(T4~T7). 모든 경계 pgmq JSON.
- 추가 검증: ktlintCheck, detekt (type-resolved), 각 모듈 test.

## 리뷰 결과

### plan-eng-review (2026-07-10, 독립 sub-agent, 실코드 대조)

**BLOCKER 4건 — 모두 plan 수정으로 해소**.
- **B1 (V번호 충돌)**. Task 2가 V408 신설이나 `V408__dashboard_share_tokens` 이미 점유 → **V409로 수정** (실코드 확인).
- **B2 (전역 SLACK 시드가 E2E 회귀)**. 전역 멘션 SLACK 정책 → 멘션이 IN_APP+SLACK 2행 → `NotificationDeliveryEndToEndIntegrationTest`의 `isEqualTo(1L)`·`fetchOne` 깨짐(TooManyRowsException) → **Task 2 files에 E2E 테스트 추가 + 채널 필터/2L 단언 수정**.
- **B3 (changeAssignee strict-mock 회귀)**. `IssueApplicationServiceTest` strict mockk가 publish 미스텁 → MockKException → **Task 1 files에 해당 테스트 추가 + `every{publish} just Runs` 스텁**.
- **B4 (dedup 순서 결함)**. "전송 전 slack_delivery_log INSERT"는 429 실패 시 재전달분을 UNIQUE 충돌로 영구 skip → DM 유실(인용한 교훈의 정반대 적용) → **T7을 "전송 성공 후 기록 + 사전 조회로 재전달 중복만 차단"으로 재설계**.

**CONCERN 3건 — 반영**.
- **C1 (큐 위치 모순)**. plan(slack V701)이 spec(notification producer-creates)과 모순 + 기존 8큐 전부 producer 생성 → **큐를 notification T2로 이동, slack 워커 테스트는 pgmq.create로 self-contained**.
- **C2 (IssueEventPublisher exhaustive when)**. `IssueAssigned` 추가 시 `isWebhookPublishable` 컴파일 실패 → **Task 1 files에 IssueEventPublisher.kt 추가 + `is IssueAssigned -> false`**.
- **C3 (resolveBotToken 미노출)**. 복호화 경로 신설 → T7 유의에 토큰 로그/DTO 미노출 명시.

**검증됨(PASS)**: 할당 파이프라인 dormant 사실 · changeAssignee 트랜잭션 안전 · notification 소비측 issue.assigned 완전 배선 · EC9 SLACK 무조건 통과 · 첫 @Scheduled 트랩 실재.

**BLOCKER 잔여: 없음** (전부 plan 반영 완료). 게이트 1 진입 가능.
