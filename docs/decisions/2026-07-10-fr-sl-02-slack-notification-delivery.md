# ADR: FR-SL-02 — Slack 알림 발송, 비동기 pgmq 큐 전달 + BC 격리

> 날짜: 2026-07-10
> 상태: Accepted (Maxi 게이트 확정)
> 관련 FR: FR-SL-02 (알림 발송 DM + 채널)
> 관련 ADR: [2026-07-07-fr-sl-01-slack-bot-app.md](2026-07-07-fr-sl-01-slack-bot-app.md) (slack-integration BC·slack-api-client·SecretEncryptor) · [2026-06-14-fr-nt-05-webhook-dispatch-ssrf.md](2026-06-14-fr-nt-05-webhook-dispatch-ssrf.md) (외부 발송 워커·dead-letter·백오프 패턴)
> 관련 PR: PR #252 (백엔드 코어 D1~D5)

## 맥락

FR-SL-02는 이슈/워크플로우 이벤트를 Slack으로 발송한다(SDD §9.1 채널: Slack DM = 멘션·할당 즉시 알림, Slack 채널 = 프로젝트 활동 피드).

기존 알림 파이프라인은 이미 Slack을 **부분적으로** 예상하고 있다.
- `com.bts.notification.domain.Channel` enum에 `SLACK`이 존재한다.
- `NotificationWorker`(pgmq `q_issue_events` consumer)는 정책 평가 → 수신자 해석 → 구독 필터 → 채널 sender 발송을 수행하며, SLACK 수신자는 사용자 opt-out 필터(`UserSubscription.isConfigurable`)를 **무조건 통과**한다(EC9).
- 그러나 SLACK을 지원하는 `NotificationChannelSender` 구현이 없어 `notification_worker_no_sender_for_channel` 경고만 남고 폐기되며, 시드된 `notification_policies`(V401/V403)가 전부 `IN_APP`이라 애초에 SLACK 수신자가 생성되지 않는다.

FR-SL-01이 slack-integration BC를 신설하며 `slack_installs`(team_id별 봇 토큰, AES-256-GCM 암호화), `slack-api-client`(공식 SDK client 층), `SecretEncryptor`를 확보했다. 실제 `chat.postMessage`는 이 자산이 있는 slack-integration BC에서만 수행 가능하다.

핵심 설계 질문: **notification BC가 "이 사용자에게 Slack 알림" 을 결정한 뒤, 실제 전송을 어느 BC에서 어떻게 하는가.**

## 결정

### D1. 전송 = 비동기 pgmq 큐 (`q_slack_deliveries`)

notification BC가 SLACK 수신자에 대해 `q_slack_deliveries` 큐에 **JSON 이벤트를 발행**하고, slack-integration BC의 **자체 워커**가 이를 소비해 `chat.postMessage`를 호출한다.

**대안 (동기 shared-kernel 포트) 기각 이유.** notification 워커가 `SlackMessagePort.send()`를 동기 호출하면 구현이 단순하지만, ① Slack API 429/네트워크 지연에 notification 워커가 블록되어 다른 채널(인앱/이메일) 알림까지 지연되고, ② 지수 백오프·재시도를 notification 워커 안에서 구현해야 해 slack-integration BC의 책임이 notification으로 새며, ③ FR-SL-01 도메인 노트의 "Slack API 429 → 지수 백오프 + pgmq 재시도" 계획과 어긋난다.

**비동기 큐 채택 근거.**
- webhook(FR-NT-05)의 `q_transition_events` → `WebhookDispatchWorker` 선례와 **동형**. 외부 HTTP 발송 + rate limit이라는 동일한 형태.
- 429 백오프·dead-letter(archive)·재전달(at-least-once)이 slack-integration 워커 안에 캡슐화된다.
- notification 워커가 Slack API 지연에 블록되지 않는다.

### D2. BC 격리 = pgmq JSON 계약 (코드 import 0)

- **notification BC**: `SlackChannelSender`(implements `NotificationChannelSender`, `supports(SLACK)`)가 `send()`에서 `q_slack_deliveries`에 JSON을 write한다. slack-integration 코드를 **import하지 않는다**. 워커 자체는 무변경(sender 자동 주입).
- **slack-integration BC**: `SlackDeliveryWorker`가 `q_slack_deliveries`를 소비한다. notification 도메인 타입(`Notification` 등)을 **import하지 않는다**. pgmq JSON을 wire 포맷 그대로 방어적 파싱한다(`NotificationWorker`/`WebhookDispatchWorker` 관례).
- 신규 shared-kernel 포트 **불요**. 큐 이름 + JSON 계약만 문서화(양쪽 상수 중복 허용, KDoc에 계약 명시).

**JSON 계약(초안, 스펙에서 확정).** `{ "recipientUserId": "<uuid>", "eventType": "issue.mentioned", "issueKey": "PROJ-123", "title": "...", "body": "...", "occurredAt": "<iso-8601>" }`. slack-integration이 `recipientUserId` → `user_slack_mapping` → `slack_user_id`(DM 대상) 해석, 미매핑 시 skip(graceful).

### D2-b. 할당 이벤트 파이프라인 수리 (brainstorming 발견, Maxi 게이트)

`issue.assigned`를 발행하는 생산자가 **존재하지 않아** 현재 IN_APP 할당 알림조차 dormant다(`changeAssignee`는 q_issue_events에 아무것도 발행하지 않고 `recordHistory`만 수행). 반면 notification 소비 측은 **이미 완전 배선**돼 있다 — `NotificationEventType.ISSUE_ASSIGNED`, `NotificationWorker`의 generic buildSourceEvent 경로, V401 `issue.assigned/ASSIGNEE·PREVIOUS_ASSIGNEE/IN_APP` 정책, `EventRecipientResolver`의 ASSIGNEE(현재 담당자)·PREVIOUS_ASSIGNEE(issue_change_item 이력 파싱) 해석.

**결정.** issue-tracking에 `IssueAssigned(issueKey, actorId, occurredAt)` 이벤트를 신설하고 `changeAssignee`가 발행한다(생산자만 수리). notification 워커/resolver는 무변경. 부작용으로 IN_APP 할당 알림도 이때부터 발화(SDD §9.1.2가 의도한 기능의 지연된 완성). SLACK 정책 시드는 `issue.mentioned`·`issue.assigned` 2종.

### D3. 데이터 모델 = `user_slack_mapping` (신규)

`user_slack_mapping(user_id UUID, slack_user_id TEXT, team_id TEXT, linked_at TIMESTAMPTZ)`. Atlas user ↔ Slack user 매핑. slack-integration 모듈 소유(V701+). DM 대상 해석과 다중 워크스페이스(team_id) 대비. 현 사내 단일 워크스페이스 가정 하 해석 단순화하되 team_id 컬럼은 보존.

### D4. 상태 의미 — Notification.SENT = "채널 핸드오프"

비동기 큐 방식에서 notification 행의 `SENT`는 "`q_slack_deliveries`에 enqueue 성공"을 의미한다(인앱=STOMP push, 이메일=SMTP accept와 동일 계층 의미). 실제 Slack 전달 성공/실패는 slack-integration 워커의 로그/재시도에서 추적한다. `SlackChannelSender.send()`가 enqueue 실패 시 예외 → notification 워커의 best-effort `deliver()`가 PENDING 유지(기존 계약).

## 범위 밖 (후속)

- **채널↔프로젝트 라우팅** = FR-SL-06. 이번엔 DM 중심 + `chat.postMessage`가 channel 대상도 받는 primitive만.
- **D6 사용자↔Slack 매핑 UI + D7 E2E** = 후속 PR (FR-SL-01의 2-PR 분할과 동일).
- **SLACK 수신자 생성 정책 시드** = 스펙에서 확정(멘션·할당 최소 시드 vs FR-NT-04 구독).

## 결과

- notification/slack-integration 양방향 코드 의존 0. 큐 JSON만 공유.
- Slack rate limit이 인앱/이메일 알림에 영향 없음.
- FR-SL-06(채널 라우팅)이 같은 `q_slack_deliveries` 경로를 확장 재사용 가능.
