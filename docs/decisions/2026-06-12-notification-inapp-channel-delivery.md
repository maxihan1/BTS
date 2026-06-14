# ADR: FR-NT-02 알림 채널 — 인앱(WebSocket) 발송 코어 first slice + 채널 집합 확정

> 날짜: 2026-06-12
> 상태: 제안 (게이트 1 승인 대기)
> BC: notification
> 관련 SDD: §9.1.3 (발송 흐름), §9.2 (인앱 Inbox)
> 관련 ADR: [2026-06-11-notification-policy-bc-bootstrap](2026-06-11-notification-policy-bc-bootstrap.md), [2026-06-02-bulk-operation-async-architecture](../adr/2026-06-02-bulk-operation-async-architecture.md)
> 관련 FR: FR-NT-02 (선행 FR-NT-01 #118/#124, FR-MN-01 #114)

## 맥락

FR-NT-01이 notification BC를 부트스트랩하고 **정책 데이터 + 평가 엔진**까지 완성했다(`NotificationPolicy`, `NotificationPolicyEvaluator`, `Channel` enum, `NotificationEventType` enum). 그러나 평가 엔진의 출력을 받아 **실제로 알림을 만들고 전달하는 부분(pgmq consumer + 채널 fanout/발송 + 실시간 푸시)이 없다.** FR-NT-01 ADR 결정 2가 이를 명시적으로 FR-NT-02 경계로 넘겼다.

SDD §9.1.3 발송 흐름.
```
이벤트 발행 → pgmq → NotificationWorker.read()
  1. fanOut: 수신자 결정 (Reporter/Assignee/Watcher/Subscription)
  2. 수신자별: 선호 조회 → 채널 결정 → 렌더링 → 발송
  3. notifications 테이블 기록 (인앱 Inbox)
  4. WebSocket 인앱 실시간 푸시
```

FR-NT-02 전체(채널 5종 + STOMP 서버/클라 + Spring Mail + Webhook + fanout + 재시도)는 한 PR로 비현실적이다. 신규 인프라가 5종 동시 도입된다.

## 결정 1 — FR-NT-02를 "인앱(WebSocket) 발송 코어" first slice로 분할

이번 PR = **발송 코어 + 인앱(WebSocket) 채널** vertical slice. 이메일·Webhook은 동일 Channel 발송 추상 위에 얹는 **후속 PR**로 분리한다.

포함.
- `Notification` aggregate + `notifications` 테이블 (payload, channel, status, read 여부).
- `NotificationWorker` — pgmq consumer (q_issue_events 소비). `BulkOperationWorker` 패턴 답습.
- `NotificationChannelSender` 발송 추상 + **InApp(WebSocket) 구현체만** 이번 PR.
- STOMP WebSocket 서버(Spring) + 클라이언트(`@stomp/stompjs` + `reconnecting-websocket`) + 실시간 토스트(`sonner` 기존).
- 선행 product §1 **STOMP 재연결 PoC를 이 PR에 흡수**(지수 백오프 5s→60s, 재연결 통합 테스트, 알림 지연 p95 < 1s 측정).

**근거**. 인앱은 가장 가시적(화면에 알림이 뜸)이고 FR-UX-03 Inbox(선행=§2.2)의 자연 선행이다. 발송 코어(consumer + Notification + Channel 추상)를 1채널로 완결하면 이메일/Webhook은 추상 구현체 추가로 축소된다. Maxi 게이트 확정(2026-06-12).

## 결정 2 — FR-NT-02 채널 집합 = 인앱 + 이메일 + Webhook (3종)

FR 제목은 "이메일/인앱/Slack/Teams/Webhook"(5종)이나 notification BC가 직접 담당하는 채널을 **3종(인앱·이메일·Webhook)**으로 확정한다.

- **Slack** → slack-integration BC에 위임(별도 BC, 기존 _index/ADR 경계).
- **Teams** → 범위 제외(향후 필요 시 Webhook 채널로 대체 가능).

이는 FR 제목/SDD 명세와의 drift이므로 **fr-index·SDD §9·product `notification-dashboard.md` §2.2·README·Obsidian을 이번 PR에서 전수 동기화**한다(`bash scripts/verify-master-plan.sh` 통과 필수). product D4 구현 계획("이메일+WebSocket+Slack은 타 BC+Webhook")과 일치시키고 제목에서 Teams를 제거한다.

**근거**. Slack은 unfurl/slash/인터랙티브 등 고유 책임이 커 독립 BC가 정당하다(_index). Teams는 사내 사용 신호가 없고 Webhook으로 일반화 가능하다. Maxi 게이트 확정(2026-06-12).

## 결정 3 — 이번 PR 수신자 범위 = payload 명시 수신자(멘션 + 담당자/리포터). Watcher/role 해석은 FR-NT-03

SDD fanout 1단계의 role 기반 수신자 해석(Reporter/Assignee/Watcher/Lead → 실제 사용자)은 **FR-NT-03 RecipientResolver** 경계다. 이번 PR은 **최소 수신자 해석만** 도입한다.

| 수신자 종류 | 출처 | 이번 PR |
|---|---|---|
| 멘션 대상 | `IssueMentioned.mentionedUserIds` (payload 완비, dedup·자기제외 완료) | ✅ |
| 리포터(Reporter) | `IssueCreated.reporterId` (payload) + 이슈 조회 | ✅ |
| 담당자(Assignee) | **이벤트 payload에 없음** → cross-BC 조회 필요(결정 4) | ✅ |
| 워처(Watcher) | 워처 목록 조회 | ❌ FR-NT-03 |
| role/Lead 스키마 해석 | 프로젝트 역할 매핑 | ❌ FR-NT-03 |

**근거**. 멘션은 producer(FR-MN-01 #114)가 이미 발행 중이고 수신자가 payload에 명시돼 end-to-end가 가장 깔끔하다. 담당자/리포터는 사용자가 직접 체감하는 핵심 수신자라 Maxi가 포함을 선택했다(게이트 2026-06-12). 워처/role은 별도 스키마·조회가 필요해 FR-NT-03으로 미룬다.

## 결정 4 — 담당자/리포터 해석을 위한 cross-BC 조회 포트 (이벤트 payload 갭)

`IssueUpdated`/`IssueTransitioned` payload에는 assignee/reporter가 없다(`IssueDomainEvent.kt` 실측). 결정 3의 담당자/리포터 수신자를 얻으려면 notification 워커가 **이슈의 현재 담당자/리포터를 cross-BC로 조회**해야 한다.

방향(스펙에서 확정).
- ArchUnit BC 격리상 notification은 issue-tracking 내부 패키지를 직접 import 금지. **shared-kernel 포트**(예: `IssueRecipientLookupPort`)를 정의하고 issue-tracking이 adapter를 구현한다(UserLookupPort 선례 동일).
- 포트 nullable/fail-open 함정 주의(learnings: crossbc-resolver-nullable-fail-open) — 기본값은 non-null + 빈 수신자(알림 미발송)로 fail-safe.

**리스크/대안**. 이벤트 payload에 assignee/reporter를 추가하는 방향(issue-tracking 이벤트 스키마 확장)도 가능하나 cross-BC 발행 측 변경 + 기존 이벤트 호환이 얽힌다. 포트 조회가 BC 격리에 더 부합한다. **스펙 단계에서 포트 vs 이벤트 확장을 확정**한다.

## 결정 5 — NotificationWorker = pgmq consumer (BulkOperationWorker 패턴 답습)

`backend/modules/issue-tracking/.../bulk/worker/BulkOperationWorker.kt`가 BTS 최초 consumer로 견고하게 정립돼 있다. NotificationWorker는 이를 답습한다.

- `@Scheduled` 폴링 → `pgmq.read(queue, vt, qty)` (raw SQL, 바인드 파라미터, DATA.md §5 예외).
- **at-least-once + 멱등** — 처리 성공 시 `pgmq.delete`, 예외 시 미삭제(vt 만료 후 재전달). 멱등 키로 (event 식별 + 수신자 + channel) 중복 알림 방지(notifications UNIQUE).
- **poison/dead-letter** — `read_ct > MAX_RECEIVE_COUNT`면 `pgmq.archive`.
- `@Transactional` 없음(REQUIRES_NEW 전파 함정, learnings: transaction-self-invocation).
- 소비 큐 = `q_issue_events`(issue-tracking 발행 5종). **리스크**: 향후 automation/slack도 같은 이벤트가 필요하면 큐 fanout/분리가 필요 — 현재 notification이 유일 consumer라 단일 큐 소비로 시작, 미래 fanout은 후속 결정.

## 결정 6 — notifications 테이블 기록 + 실시간 푸시 (Inbox 조회 UI는 FR-UX-03)

SDD §9.1.3의 3·4단계(notifications 기록 + WebSocket 푸시)를 이번 PR이 담당하되, **Inbox 페이지/카운트 뱃지 UI는 FR-UX-03**이다. 이번 PR의 프론트는 STOMP 클라이언트 구독 + 실시간 토스트(sonner)까지. notifications 테이블은 FR-UX-03 Inbox의 데이터 기반이 된다(읽음/보관 컬럼은 FR-UX-03에서 확장 가능하도록 status/read_at 최소 설계).

## 결과 / 영향

- 후속: 이메일 채널(Spring Mail + MailHog) + Webhook 채널 = 동일 `NotificationChannelSender` 추상 위 후속 PR.
- 후속: RecipientResolver(워처/role) = FR-NT-03. UserSubscription override = FR-NT-04. Inbox 조회 = FR-UX-03.
- glossary 추가 후보: NotificationWorker(알림 워커), Fanout(수신자 복제), Channel 발송 추상, 인앱 채널(In-App). Maxi 승인 후 반영.
- WebSocket 인증 방식(세션 쿠키 vs STOMP CONNECT JWT)은 security-engineer 검토로 스펙에서 확정.
- FR drift 전수 동기화(결정 2) 필수.

## Amendment (2026-06-14) — 이메일 채널 구현 + Webhook을 전용 후속 FR로 분리

이메일 채널을 동일 `NotificationChannelSender` 추상 위 `EmailChannelSender`로 구현했다(Spring Mail/`MimeMessageHelper` UTF-8, `UserLookupPort.findEmailById` cross-BC 조회, Testcontainers MailHog 검증, 실패=`deliver()` best-effort PENDING). 워커/resolver/스키마 무변경.

**결정 2(채널 집합) 보완 — Webhook을 FR-NT-02에서 분리해 전용 후속 FR로**. 코드 실측 결과 두 가지가 드러났다(Maxi 확정 2026-06-14).
1. 알림 모델은 per-recipient-user(`notifications.recipientUserId`)인데 Webhook은 외부 URL 대상이라 **per-user 모델과 맞지 않고**, 현재 스키마에 webhook URL 저장 위치가 없다.
2. Webhook은 `NotificationChannelSender`가 아니라 워크플로우 전이 post-action(`CallWebhookPostAction`)이 발행하는 **`WebhookRequested` 이벤트 디스패처**로 구현하는 것이 선례에 부합하나, 그 이벤트는 **현재 어느 pgmq 큐에도 발행되지 않는다**(전이 API 응답 `TransitionResponseDto.events`에만 존재). 제대로 하려면 ① project-workflow BC에 전이 emitEvents 발행 파이프라인(4종 post-action 공통), ② notification BC 소비+HTTP 디스패처가 필요 → cross-BC, FR-NT-02 한 PR 범위 초과.

따라서 Webhook 채널은 **전이-이벤트 발행 파이프라인 설계를 포함한 전용 후속 FR**로 분리한다(FR ID는 해당 FR spec 시점에 mint). FR-NT-02 채널 집합 = 인앱 + 이메일(notification BC 직접 담당), Webhook은 후속 FR, Slack=slack-integration BC, Teams=범위 밖. FR-NT-02 전체는 Webhook 후속이라 부분완료(`[~]`) 유지, FR 총수 불변(122).

> **정정(2026-06-14, FR-NT-05 도메인 분석)**: 위 ①의 "**project-workflow BC**에 전이 emitEvents 발행 파이프라인" 표현은 부정확하다. 코드 실측상 post-action은 계산만(GAP-2), 실행(이벤트 발행)은 **호출자 BC = issue-tracking** 책임이며, 발행은 상태 변경(`repo.applyTransition`)을 소유한 `IssueApplicationService.transitionIssue()` 트랜잭션 안에서 이뤄져야 outbox 정합이 유지된다(project-workflow `plan()`은 dry-run 겸용이라 오발사 위험). 새 FR는 **FR-NT-05**로 mint(122→123). 상세 근거는 [2026-06-14-fr-nt-05-transition-event-outbox.md](2026-06-14-fr-nt-05-transition-event-outbox.md).
