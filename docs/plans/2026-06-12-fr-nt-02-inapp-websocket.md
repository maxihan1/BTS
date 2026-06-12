# FR-NT-02 — 알림 채널 (발송 코어 + 인앱 WebSocket)

> slug: fr-nt-02-inapp-websocket
> type: backend
> agent: backend-engineer (프론트 task는 frontend-engineer, WebSocket 인증은 security-engineer 검토)
> primary_bc: notification
> 생성: 2026-06-12

## Brief

**사용자 원문**. "fr-nt-02 진행해줘"

**FR**. FR-NT-02 — 채널 (이메일/인앱/Slack/Teams/Webhook). product `docs/plan/product/notification-dashboard.md` §2.2, SDD 09장.

**게이트 확정 사항 (2026-06-12, Maxi)**.
1. **이번 PR 범위 = 인앱(WebSocket) 우선 vertical slice**. 발송 코어(notifications 테이블 + Notification 도메인 + pgmq consumer + Channel 발송 추상) + STOMP WebSocket 서버/클라이언트 + 실시간 토스트(sonner 기존). 선행 §1 STOMP WebSocket 재연결 PoC를 이 PR에 흡수(지수 백오프 5s→60s, 재연결 통합 테스트). **이메일/Webhook 채널은 동일 Channel 추상 위에 얹는 후속 PR**.
2. **FR-NT-02 채널 집합 확정 = 인앱 + 이메일 + Webhook (3종)**. Slack은 slack-integration BC에 위임, Teams는 범위 제외(향후 Webhook으로 대체 가능). → FR 제목/명세 drift이므로 fr-index·SDD·product·README·Obsidian 전수 동기화 필요(이번 PR에서 처리, `bash scripts/verify-master-plan.sh` 통과 필수).

**현황 실측 (2026-06-12)**.
- 있음. `NotificationPolicy` 도메인 + `NotificationPolicyEvaluator`(평가 엔진), `Channel` enum, `NotificationEventType` enum (FR-NT-01, PR #118/#124). 프론트 `sonner` 토스트 컴포넌트(`apps/web/src/components/ui/sonner.tsx`).
- 없음. pgmq consumer, STOMP WebSocket 서버·클라이언트, `notifications` 테이블, Channel 발송 구현체.
- 참고. 멘션 등 producer 측은 pgmq 이벤트 발행 중(FR-MN-01 IssueMentioned, PR #114). 이번 PR은 consumer + 발송.

## 도메인 정리

- **BC**. notification (primary). cross-BC 조회로 issue-tracking 데이터 참조(shared-kernel 포트 경유, 직접 import 금지).
- **영향 엔티티**.
  - `Notification` (신규 aggregate) — 수신자(userId), event_type, payload, channel, status(pending/sent/failed), read_at. `notifications` 테이블(신규).
  - `NotificationWorker` (신규) — pgmq `q_issue_events` consumer. `BulkOperationWorker` 패턴.
  - `NotificationChannelSender` (신규 추상) + `InAppChannelSender`(WebSocket 구현체, 이번 PR 유일 구현).
  - 활용(기존, FR-NT-01). `NotificationPolicyEvaluator`(평가 엔진), `Channel`/`NotificationEventType` enum, `UserLookupPort`(shared-kernel).
  - 신규 cross-BC 포트. `IssueRecipientLookupPort`(shared-kernel) — issue-tracking이 adapter 구현(assignee/reporter 조회, 이벤트 payload 갭 보완).
- **새 용어(glossary 후보, Maxi 승인 대기)**. NotificationWorker(알림 워커), Fanout(수신자 복제), Channel 발송 추상(NotificationChannelSender), 인앱 채널(In-App/WebSocket).
- **기존 결정 충돌**. 없음. FR-NT-01 ADR 결정 2(전달은 FR-NT-02)와 정합. FR 제목 채널 5종 → 3종 drift는 결정 2에서 정정(전수 동기화).
- **수신자 경계**. 멘션(payload) + 담당자/리포터(cross-BC 포트 조회). 워처/role 해석은 FR-NT-03. (게이트 확정 2026-06-12)
- **관련 ADR**. [docs/decisions/2026-06-12-notification-inapp-channel-delivery.md](../decisions/2026-06-12-notification-inapp-channel-delivery.md) (생성됨, 결정 1~6)
- **미해결(스펙에서 확정)**. (a) 담당자/리포터 해석 = 포트 조회 vs 이벤트 payload 확장, (b) WebSocket 인증 방식(세션 쿠키 vs STOMP CONNECT JWT, security 검토), (c) notifications 멱등 키 설계, (d) q_issue_events 단일 consumer 미래 fanout.

## 스펙

전체 스펙. [docs/specs/2026-06-12-fr-nt-02-inapp-websocket.md](../specs/2026-06-12-fr-nt-02-inapp-websocket.md)

핵심 시나리오 요약.
- 멘션/담당자/리포터 이벤트 → NotificationWorker(q_issue_events 소비) → 정책 평가 → Notification 기록 + STOMP 실시간 푸시 → sonner 토스트 (지연 p95 < 1s).
- 수신자 = 멘션 payload + 담당/리포터(IssueRecipientLookupPort cross-BC 조회). 워처/role은 FR-NT-03.
- WebSocket 인증 = STOMP CONNECT frame JWT(기존 디코더 재사용, STATELESS라 세션쿠키 불가, security 검토).
- 멱등 = notifications.dedup_key UNIQUE(재전달 중복 0). 메시지 생명주기 = delete/archive/vt 재전달.

확정된 기술 결정(미해결 4건).
- (a) 담당/리포터 = cross-BC `IssueRecipientLookupPort`(shared-kernel) 조회. 이벤트 payload 확장 아님(BC 격리).
- (b) WebSocket 인증 = STOMP CONNECT JWT(STATELESS 제약상 유일). security-engineer 검토.
- (c) 멱등 키 = hash(event_type+issue_key+occurredAt+recipient+channel) UNIQUE.
- (d) 큐 = q_issue_events 단일 consumer. 미래 automation/slack fanout은 후속.

신규 의존성. `@stomp/stompjs`(1종, Maxi 승인) + `spring-boot-starter-websocket`(Spring 공식). `reconnecting-websocket` 미도입.

## Brainstorming Check

✅ 통과 (1회 iteration). 신규 의존성 reconnecting-websocket 불필요(stompjs 내장) 발견 → Maxi 1종 승인. 렌더링 간단화/status 의미/CAS 불요/E2E WS 전략 보강. 상세는 스펙 파일 §Brainstorming Check.

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
