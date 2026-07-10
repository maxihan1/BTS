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

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
