# FR-NT-02 이메일/Webhook 채널 — 알림 채널 완성 (백엔드 D1·D4·D5)

> slug: fr-nt-02-email-webhook-channels
> type: backend
> agent: backend-engineer
> primary_bc: notification
> 생성: 2026-06-14

## Brief

FR-NT-02(알림 채널)의 인앱 채널은 이미 머지됨(PR #126 백엔드 + PR #137 프론트/E2E).
이번 PR은 **이메일 채널**을 기존 `NotificationChannelSender` 추상 위에 구현한다.
**Webhook 채널은 별도 후속 FR로 분리**(아래 결정 참조). FR-NT-02 전체는 Webhook 후속이라 부분완료 유지.

**범위 (이메일 채널, 백엔드 D1·D4·D5 이메일 부분)**.
1. **EmailChannelSender** — `supports(EMAIL)`, `JavaMailSender`(Spring Mail)로 발송.
   Testcontainers MailHog로 SMTP 수신 검증(product §2.2 D5 정본 방식).
2. `UserLookupPort`에 `recipientUserId → email` 조회 메서드를 **default 구현으로 추가**
   (기존 fake ~35개 보호, fail-safe 빈 응답). 실 구현은 identity-access `UserLookupAdapter`가 override.
3. product §2.2 D1·D4·D5의 **이메일 부분 반영** + Webhook을 별도 FR로 분리하는 결정을
   ADR/product에 동기화. FR-NT-02 전체는 부분완료 유지(`[~]`), FR 카운트 불변(122).

**Webhook 분리 결정 (Maxi 확정 2026-06-14)**.
- Webhook 채널은 `NotificationChannelSender`(per-user 모델)가 아니라
  **`WebhookRequested` 이벤트 디스패처**로 구현하기로 방향 확정(옵션 B).
- 그러나 코드 실측 결과 `WebhookRequested`(전이 post-action `CallWebhookPostAction` 발행)는
  **현재 어느 pgmq 큐에도 발행되지 않음** — 전이 API 응답(`TransitionResponseDto.events`)에만 존재.
- 제대로 구현하려면 ① project-workflow BC에 전이 emitEvents pgmq 발행 파이프라인(4종 post-action 공통),
  ② notification BC에 소비+HTTP POST 디스패처가 필요 → cross-BC, 범위 큼.
- 따라서 **Webhook = 전용 후속 FR**로 분리(전이-이벤트 발행 파이프라인 설계 포함). 이번 PR 범위 밖.

**기존 코드 사실(실측)**.
- `Channel` enum(`domain/Channel.kt`)에 `EMAIL` 이미 정의됨 — enum 추가 불필요.
- `NotificationWorker.kt:191`이 `channelSenders.firstOrNull { it.supports(recipient.channel) }`로
  디스패치, sender 리스트는 Spring 자동 주입(`List<NotificationChannelSender>`) → **새 sender 빈만 등록**(워커 수정 불요).
- `NotificationPolicy.channel`이 채널 결정 → `EventRecipientResolver`가 `match.channel`을
  `ResolvedRecipient.channel`로 전달 → 정책에 channel=EMAIL이면 수신자가 EMAIL로 생성됨(resolver 수정 불요).
- `deliver()`(`NotificationWorker.kt:213`)가 send 예외를 **best-effort로 삼킴**(로그+PENDING fallback) →
  이메일도 동일 패턴(능동 재시도 없음, SDD에 채널별 재시도 명세 없음).
- `users` 테이블에 `email` 컬럼 존재(V001). `UserLookupPort`(shared-kernel) 기존 cross-BC 포트 재사용.

**범위 밖**. Webhook 채널(별도 FR), Slack 채널(slack-integration BC), Teams 채널.

## 도메인 정리

- **BC**: notification (단일 BC, cross-BC import 없음)
- **영향 엔티티**: 없음 (기존 `Notification` aggregate / `NotificationChannelSender` 추상 재사용)
- **새 용어**: 없음 (Channel·EMAIL·NotificationChannelSender 모두 기존 용어). glossary 갱신 불요.
- **신규 컴포넌트**: `EmailChannelSender`(notification.channel) — `InAppChannelSender` 형판 동형.
- **cross-BC 포트 확장**: `UserLookupPort`(shared-kernel)에 이메일 조회 메서드 1개를 default로 추가.
  실 구현은 identity-access `UserLookupAdapter`가 override. learnings `interface-extension-default-method`
  준수(추상 추가 금지 — 기존 인라인 fake ~35개 컴파일 보호, default는 fail-safe 빈 응답).
- **기존 결정 충돌**: 없음. ADR [2026-06-12-notification-inapp-channel-delivery](../decisions/2026-06-12-notification-inapp-channel-delivery.md)
  결정 1·결과영향이 "이메일 채널 = 동일 추상 위 후속 PR"로 명시 → 계획된 연장.
- **결정 deviation (이번 PR로 ADR 보완)**: ADR 결정 2가 FR-NT-02 채널 = 인앱+이메일+Webhook(3종)이라 했으나,
  Webhook은 per-user 알림 모델과 맞지 않고(URL 출처 부재) 전이-이벤트 디스패처로 재설계 필요 +
  발행 파이프라인이 cross-BC라, **Webhook을 전용 후속 FR로 분리**(Maxi 확정 2026-06-14).
  ADR amendment + product 동기화 필요(스펙/plan 단계에서 처리).
- **관련 ADR**: [2026-06-12-notification-inapp-channel-delivery](../decisions/2026-06-12-notification-inapp-channel-delivery.md)(보완 대상),
  [2026-06-11-notification-policy-bc-bootstrap](../decisions/2026-06-11-notification-policy-bc-bootstrap.md)
- **재시도 정책**: 채널별 능동 재시도 없음. pgmq at-least-once + dead-letter(ADR 결정5) + send 실패 시
  PENDING fallback(`deliver()` best-effort)으로 일관. SDD §9에 채널별 재시도 명세 없음 확인.

## 스펙

전체 스펙. [docs/specs/2026-06-14-fr-nt-02-email-webhook-channels.md](../specs/2026-06-14-fr-nt-02-email-webhook-channels.md)

핵심 요약.
- `EmailChannelSender`(supports(EMAIL)) — `MimeMessageHelper`(UTF-8)로 수신자 이메일에 발송, 제목=notification.title.
- `UserLookupPort.findEmailById(userId): String?` default 메서드 추가(identity-access adapter override).
- 실패(이메일 부재/SMTP 다운)는 예외→`deliver()` best-effort PENDING(워커/재시도/스키마 무변경).
- MailHog Testcontainers로 SMTP 발송→수신 + 한국어 제목 보존 검증.

## Brainstorming Check

✅ 통과 (1회, 직접 적대적 점검). gap 4건 보강 — 한국어 인코딩(MimeMessageHelper), from 주소, SMTP 타임아웃, FAILED 미사용 명시. 미해결: 신규 mail 의존성(절대규칙#17, 게이트1 승인).

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
