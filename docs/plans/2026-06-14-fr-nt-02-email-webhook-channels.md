# FR-NT-02 이메일/Webhook 채널 — 알림 채널 완성 (백엔드 D1·D4·D5)

> slug: fr-nt-02-email-webhook-channels
> type: backend
> agent: backend-engineer
> primary_bc: notification
> 생성: 2026-06-14

## Brief

FR-NT-02(알림 채널)의 인앱 채널은 이미 머지됨(PR #126 백엔드 + PR #137 프론트/E2E).
남은 **이메일 채널**과 **Webhook 채널**을 기존 `NotificationChannelSender` 추상 위에 구현해
FR-NT-02 전체를 완료(`[~]`→완료)로 전환한다.

**범위 (백엔드 D1·D4·D5)**.
1. **EmailChannelSender** — `supports(EMAIL)`, `JavaMailSender`(Spring Mail)로 발송.
   Testcontainers MailHog로 SMTP 수신 검증(product §2.2 D5 정본 방식).
2. **WebhookChannelSender** — `supports(WEBHOOK)`, 외부 HTTP POST 호출.
   D2 재시도 정책 연동. MockWebServer/WireMock로 검증.
3. 두 채널 완료 후 `docs/plan/product/notification-dashboard.md §2.2`의
   D1·D4·D5 `[~]`→`[x]`, FR-NT-02 부분완료→완료 전환,
   fr-index/README/CLAUDE 진척 카운트 전수 동기화(`scripts/verify-master-plan.sh` 통과).

**기존 코드 사실(실측)**.
- `Channel` enum(`domain/Channel.kt`)에 `EMAIL`, `WEBHOOK` 이미 정의됨 — enum 추가 불필요.
- `NotificationWorker.kt:191`이 `channelSenders.firstOrNull { it.supports(recipient.channel) }`로
  디스패치, sender 리스트는 Spring 자동 주입(`List<NotificationChannelSender>`) → **새 sender 빈만 등록**(워커 수정 불요).
- 현재 EMAIL/WEBHOOK 알림은 매칭 sender 없어 `notification_worker_no_sender_for_channel` 로그만 남기고 드롭됨.

**범위 밖**. Slack 채널(slack-integration BC), Teams 채널(FR-NT-02 PR 범위 아님).

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
