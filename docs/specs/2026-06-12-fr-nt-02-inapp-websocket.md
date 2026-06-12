# FR-NT-02 인앱(WebSocket) 알림 채널 발송 코어 — 스펙

> slug: fr-nt-02-inapp-websocket / BC: notification / 생성: 2026-06-12
> 기반 ADR: docs/decisions/2026-06-12-notification-inapp-channel-delivery.md (결정 1~6)
> 선행: FR-NT-01(#118/#124 정책+평가엔진), FR-MN-01(#114 IssueMentioned 발행), BulkOperationWorker(consumer 선례)

## 범위 (이번 PR)

발송 코어(`notifications` 테이블 + `Notification` 도메인 + `NotificationWorker` pgmq consumer + `NotificationChannelSender` 추상) + **인앱(WebSocket) 채널 구현체** + STOMP 서버/클라이언트 + 실시간 토스트. 선행 §1 STOMP 재연결 PoC 흡수.

**범위 밖**(후속). 이메일/Webhook 채널 구현체, 워처/role 기반 RecipientResolver(FR-NT-03), UserSubscription override(FR-NT-04), Inbox 조회 페이지/카운트 뱃지 UI(FR-UX-03).

## 사용자 시나리오 (Given-When-Then)

**S1. 멘션 실시간 알림**
- Given. Alice가 로그인해 SPA에서 STOMP WebSocket으로 `/user/queue/notifications` 구독 중.
- When. Bob이 이슈 본문에 `@alice`를 추가 → `IssueMentioned` 이벤트가 `q_issue_events`에 발행됨.
- Then. NotificationWorker가 소비 → 정책 평가(채널=인앱 활성) → Alice용 `Notification`(channel=IN_APP, status=SENT) 기록 → STOMP로 Alice에게 푸시 → 화면에 sonner 토스트 표시. 지연 p95 < 1s.

**S2. 담당자/리포터 알림**
- Given. 이슈 ATLAS-42의 담당자=Alice, 리포터=Carol. 둘 다 구독 중.
- When. 이슈가 전이됨 → `IssueTransitioned` 발행.
- Then. Worker가 `IssueRecipientLookupPort`로 ATLAS-42의 담당자/리포터 조회 → 정책상 인앱 활성 수신자(Alice, Carol)에게 각각 Notification 기록 + 실시간 푸시. 행위자(자기 변경) 본인은 제외.

**S3. 정책 비활성 시 미발송**
- Given. 전역/프로젝트 정책에서 `issue.transitioned × Assignee × IN_APP = disabled`.
- When. 전이 발생.
- Then. 평가 엔진이 해당 (역할×채널) 조합 미반환 → 그 수신자에게 알림 생성 안 됨.

**S4. 재전달 멱등 (at-least-once)**
- Given. Worker가 한 메시지를 처리하다 push 후 delete 직전 크래시.
- When. vt 만료 후 같은 메시지 재전달.
- Then. 동일 (event+recipient+channel) `dedup_key` UNIQUE 충돌 → 중복 Notification 미생성. 사용자는 토스트 1회만 받음(이미 SENT 기록 존재 시 재푸시 안 함).

**S5. 연결 끊김/재연결**
- Given. Alice 클라이언트가 STOMP 연결 중 네트워크 단절.
- When. 네트워크 복귀.
- Then. `reconnecting-websocket` 지수 백오프(5s→최대 60s)로 자동 재연결 + 구독 복원. (오프라인 중 알림은 `notifications` 테이블에 기록돼 FR-UX-03 Inbox에서 조회 — 이번 PR은 실시간 푸시만, 미수신 재전송은 범위 밖.)

**S6. 미인증 연결 거부**
- Given. JWT 없이/만료 JWT로 STOMP CONNECT 시도.
- When. handshake/CONNECT.
- Then. `ChannelInterceptor`가 JWT 검증 실패 → CONNECT 거부(ERROR frame), 구독 불가.

## 기능 요구사항 (FR)

- FR1. `NotificationWorker`가 `q_issue_events`를 `@Scheduled` 폴링 소비(`pgmq.read`). BulkOperationWorker 패턴(CAS 불요 — 메시지=처리단위, 아래 FR8 멱등으로 대체).
- FR2. 소비한 이벤트 → `NotificationPolicyEvaluator`로 (recipient_role × channel) 적용 정책 목록 산출.
- FR3. 수신자 해석(이번 PR 범위). `issue.mentioned`→`mentionedUserIds`. Reporter/Assignee 역할→`IssueRecipientLookupPort`로 이슈 조회. Watcher/Lead 역할→이번 PR은 무시(빈 수신자, FR-NT-03).
- FR4. 채널=IN_APP인 (수신자×정책)마다 `Notification` 생성·기록 후 STOMP로 해당 사용자에게 실시간 푸시.
- FR5. STOMP WebSocket 서버 — Spring `@EnableWebSocketMessageBroker`, endpoint `/ws`(SockJS fallback 불요, 순수 WS), user destination `/user/queue/notifications`.
- FR6. WebSocket 인증 — STOMP CONNECT frame `Authorization: Bearer <accessToken>` native header를 `ChannelInterceptor.preSend`(CONNECT command)에서 기존 JWT 디코더로 검증 → `Principal`(userId) 설정. 미검증 시 CONNECT 거부. (BTS STATELESS 아키텍처상 세션쿠키 불가 — ADR 결정, **security-engineer 검토 필수**.)
- FR7. 프론트 — `@stomp/stompjs`(v7) 클라이언트, 로그인 사용자 한정 연결, `/user/queue/notifications` 구독, 수신 시 `sonner` 토스트. 비로그인/로그아웃 시 연결 해제. 재연결은 stompjs 내장 `reconnectDelay` + 지수 백오프 wrapper(아래 NFR2). (Maxi 승인 2026-06-12: 의존성 `@stomp/stompjs` 1종, `reconnecting-websocket` 미도입.)
- FR10. 렌더링(이번 PR 간단) — `title`/`body`는 issueKey + event_type 기반 결정적 문자열(예: "ATLAS-42에서 회원님을 멘션했습니다"). **actor/수신자 표시 이름은 조회하지 않음**(추가 cross-BC UserLookupPort 호출 회피). 풍부한 표시는 FR-UX-03 Inbox에서.
- FR8. 멱등 — `notifications.dedup_key` UNIQUE. INSERT ON CONFLICT DO NOTHING. 재전달 시 중복 생성·재푸시 방지.
- FR9. 메시지 생명주기 — 처리 성공 시 `pgmq.delete`. 예외 시 미삭제(vt 만료 재전달). `read_ct > MAX_RECEIVE_COUNT`(poison) 시 `pgmq.archive`(dead-letter).

## 비기능 요구사항 (NFR)

- NFR1. WebSocket 알림 지연 p95 < 1s (이벤트 발행 → 클라이언트 토스트). product §NFR 측정표 기록.
- NFR2. STOMP 재연결 지수 백오프 5s→최대 60s. stompjs `reconnectDelay`를 재연결 시도마다 동적으로 증가(5→10→20→40→60s cap)시키는 wrapper로 구현. 네트워크 분리/복귀 단위/통합 테스트로 백오프 수열 검증(§1 PoC 흡수).
- NFR3. ArchUnit BC 격리 — notification은 issue-tracking/project-workflow 내부 패키지 직접 import 0건. cross-BC는 shared-kernel 포트만.
- NFR4. Worker는 `@Transactional` 미부착(REQUIRES_NEW 전파 함정). raw SQL은 바인드 파라미터(DATA.md §5).
- NFR5. 보안 — WebSocket은 인증된 사용자만. 사용자는 자기 destination(`/user/queue/...`)만 수신(타인 알림 수신 불가). 토스트 payload에 권한 외 민감정보 미포함.

## API / 인터페이스

**WebSocket (STOMP)**
- 연결. `ws(s)://<host>/ws` — CONNECT frame에 `Authorization: Bearer <jwt>`.
- 구독. `SUBSCRIBE /user/queue/notifications`.
- 서버 푸시 메시지 payload(JSON). `{ id, eventType, issueKey, title, body, occurredAt }` (Inbox 상세 필드는 FR-UX-03에서 확장).

**REST** — 이번 PR은 신규 REST 없음(Inbox 조회 API는 FR-UX-03). 정책 CRUD는 FR-NT-01 기존.

**cross-BC 포트(shared-kernel)**
- `IssueRecipientLookupPort.findRecipients(issueKey): IssueRecipients?` → `{ reporterId: UUID, assigneeId: UUID? }`. issue-tracking이 adapter 구현. 미발견/조회불가 시 빈 수신자(fail-safe, non-null 기본).

## 데이터 모델 변경

**신규 `notifications` 테이블** (notification BC, V402+).
- `id` UUID PK
- `recipient_user_id` UUID NOT NULL — 수신자
- `event_type` TEXT NOT NULL — NotificationEventType
- `channel` TEXT NOT NULL — Channel(이번 PR IN_APP만 기록되나 컬럼은 일반)
- `issue_key` TEXT NULL — 소스 이슈(있으면)
- `title` TEXT NOT NULL / `body` TEXT NULL — 렌더링 결과(이번 PR 간단 템플릿)
- `payload` JSONB NULL — 원본 컨텍스트
- `status` TEXT NOT NULL — PENDING/SENT/FAILED
- `dedup_key` TEXT NOT NULL — `hash(event_type + issue_key + occurredAt + recipient_user_id + channel)`. **UNIQUE(dedup_key)** (PG NULLS NOT DISTINCT 불요 — 모두 NOT NULL).
- `read_at` TIMESTAMPTZ NULL — FR-UX-03 Inbox 대비(이번 PR은 항상 NULL로 생성)
- `created_at` TIMESTAMPTZ NOT NULL DEFAULT now()
- 인덱스. `ix_notifications_recipient (recipient_user_id, created_at DESC)` (FR-UX-03 조회 대비).
- init_codegen.sql 미러 필수(learnings: jooq-init-codegen-mirror).

## 엣지 케이스

- E1. 멘션 + 담당자가 동일인 → 같은 이벤트로 중복 알림? dedup_key가 (event+user+channel)이라 1건. (단 event_type이 다르면 별건 — 멘션은 issue.mentioned, 전이는 issue.transitioned로 자연 분리.)
- E2. 행위자 본인이 수신자 후보 → 자기 행동 알림 제외(IssueMentioned는 이미 자기제외; 담당/리포터는 actorId와 비교 제외).
- E3. 이슈 조회 실패(삭제됨/권한) → 빈 수신자, 알림 미발송(fail-safe). 멘션은 payload 수신자라 영향 없음.
- E4. 정책 0건 매칭 → 알림 미생성(정상).
- E5. 동시 다중 Worker 인스턴스 → pgmq vt로 메시지 단일 점유 + dedup_key UNIQUE 이중 안전망.
- E6. 미발행 event_type(due_soon 등) → 발행원 없어 도달 안 함(정책만 존재 가능, FR-NT-01 ADR 결정 3).
- E7. STOMP 구독자 없음(오프라인) → Notification 기록은 됨, 실시간 푸시는 유실(FR-UX-03 Inbox에서 조회). 이번 PR은 재전송 안 함.
- E8. WebSocket 연결은 됐으나 JWT 만료(장기 연결) → 재연결 시 재검증. 연결 중 만료 처리는 토큰 수명 내 best-effort(범위 밖 상세).

## 제약 조건

- C1. BC 격리(ArchUnit). cross-BC는 shared-kernel 포트만.
- C2. WebSocket 인증=기존 JWT 재사용(STOMP CONNECT). 세션쿠키 금지(STATELESS). security-engineer 검토.
- C3. pgmq raw SQL 바인드 파라미터. Worker @Transactional 미부착.
- C4. 신규 라이브러리 = 프론트 `@stomp/stompjs`(Maxi 승인 2026-06-12, 1종) + 백엔드 `spring-boot-starter-websocket`(Spring 공식). `reconnecting-websocket`은 미도입(stompjs 내장 재연결 사용).
- C5. FR drift 전수 동기화(채널 3종, Teams 제거) — verify-master-plan.sh 통과.

## 측정 가능한 완료 기준

1. S1~S6 시나리오를 통합/E2E 테스트로 검증(멘션→토스트 end-to-end 1건 이상 E2E).
2. WebSocket 알림 지연 p95 < 1s 측정·기록(product §NFR).
3. 재연결 지수 백오프 통합 테스트 통과(§1 PoC 흡수).
4. dedup_key 멱등 — 재전달 시 중복 0 단위 테스트.
5. ArchUnit BC 격리 통과 + detekt/ktlint 그린 + 전 모듈 테스트 그린.
6. notification BC 백엔드 + 프론트 + E2E 전부 그린. verify-master-plan.sh 통과.

## Brainstorming Check (자체 sanity, BTS 백엔드 인프라 패턴)

✅ 통과 (1회 iteration). 발견·해소.
- **[Maxi 결정]** 신규 의존성 — `reconnecting-websocket` 불필요(stompjs v7 내장 재연결). Maxi가 `@stomp/stompjs` 1종 승인 → FR7/NFR2/C4 반영.
- **[보강]** 렌더링 — actor/수신자 표시 이름 조회는 추가 cross-BC 비용 → 이번 PR은 issueKey+event 기반 결정적 문자열(FR10). 풍부화는 FR-UX-03.
- **[보강]** status 의미 — notifications 기록 성공=SENT(Inbox 데이터 존재), 실시간 푸시는 best-effort(구독자 없으면 유실, E7). 푸시 실패가 곧 FAILED는 아님(기록은 SENT).
- **[보강]** Worker CAS 불요 — 메시지 1개=이벤트 1개=처리 1회. BulkOperationWorker의 작업레벨 CAS 대신 `dedup_key` UNIQUE 멱등으로 대체(FR8).
- **[plan 위임]** E2E WebSocket 전략 — 백엔드 STOMP 통합 테스트(실제 WS 클라이언트로 연결→이벤트 발행→수신)가 1차. 프론트 단위는 stompjs mock, Playwright E2E의 WS 검증 범위/방식은 qa-engineer가 plan에서 확정.
- **[확인 필요·plan]** Channel enum 무변경 — IN_APP은 기존 enum. 신규 enum 추가 없음(enum 카운트 가드 무영향, learnings: enum-add-breaks-count-guard). plan에서 Channel enum 실재 확인.
