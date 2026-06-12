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

## Plan

> 범위: backend D1~D5 (발송 코어 + STOMP 서버 + JWT 인증 + 통합테스트). frontend(@stomp/stompjs+토스트)+E2E는 후속 PR(D6/D7).
> 이번 PR 실제 동작 이벤트: `issue.mentioned`(MENTIONED), `issue.created`(REPORTER), `issue.transitioned`(REPORTER/ASSIGNEE). WATCHER/기타 role은 FR-NT-03.
> 발견: `NotificationEventType`에 `issue.mentioned` 부재 → Task 4에서 추가(없으면 멘션 정책 평가 미매칭, S1 미동작).

### Task 1. notifications 테이블 마이그레이션 (V402) + init_codegen 미러

**메타**.
- agent: `db-engineer`
- files: [`backend/modules/notification/src/main/resources/db/migration/notification/V402__notifications.sql`, `backend/modules/notification/src/main/resources/db/codegen/init_codegen.sql`, `backend/modules/notification/src/test/kotlin/com/bts/notification/repository/NotificationRepositoryIntegrationTest.kt`]
- depends-on: []

**RED**. `NotificationRepositoryIntegrationTest`에 "notifications 테이블 존재 + 컬럼/UNIQUE(dedup_key)/인덱스" 검증 → 테이블 없어 실패.
**GREEN**. V402 작성 — 스펙 §데이터 모델 컬럼(id/recipient_user_id/event_type/channel/issue_key/title/body/payload jsonb/status/dedup_key/read_at/created_at), `UNIQUE(dedup_key)`, `ix_notifications_recipient(recipient_user_id, created_at DESC)`. init_codegen.sql에 동일 DDL 미러(learnings: jooq-init-codegen-mirror).
**REFACTOR**. 컬럼 주석(SQL `--` 한국어), V번호 머지 직전 재확인(learnings: migration-vnumber-concurrent-branch-collision — 현재 notification 대역 V401 다음 V402).
**검증**. `./gradlew :notification:flywayMigrate` + codegen 빌드.

### Task 2. Notification 도메인 + dedup_key 결정적 계산

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/notification/src/main/kotlin/com/bts/notification/domain/Notification.kt`, `backend/modules/notification/src/main/kotlin/com/bts/notification/domain/NotificationStatus.kt`, `backend/modules/notification/src/test/kotlin/com/bts/notification/domain/NotificationTest.kt`]
- depends-on: []

**RED**. dedup_key가 (event_type+issue_key+occurredAt+recipient+channel)로 결정적(동일 입력→동일 키, 다른 입력→다른 키) 테스트. status enum(PENDING/SENT/FAILED).
**GREEN**. `Notification` data class + `dedupKey()` 순수 함수(sha-256 hex 등) + `NotificationStatus` enum.
**REFACTOR**. KDoc(중괄호/백틱 금지 — learnings: ktlint-kdoc-brace-parse-failure), 불변 보장.
**검증**. `./gradlew :notification:test --tests "*NotificationTest"`.

### Task 3. NotificationRepository (jOOQ INSERT ON CONFLICT DO NOTHING + 조회)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/notification/src/main/kotlin/com/bts/notification/repository/NotificationRepository.kt`, `backend/modules/notification/src/test/kotlin/com/bts/notification/repository/NotificationRepositoryIntegrationTest.kt`]
- depends-on: [1, 2]

**RED**. 동일 dedup_key 2회 INSERT → 1행만 존재(멱등) 통합테스트. recipient별 조회.
**GREEN**. jOOQ repository. `insertOnConflictDoNothing` → 삽입 여부(boolean) 반환(재푸시 판단용). raw SQL은 바인드 파라미터(DATA.md §5).
**REFACTOR**. 쿼리 상수화.
**검증**. `./gradlew :notification:test --tests "*NotificationRepositoryIntegrationTest"`.

### Task 4. NotificationEventType에 ISSUE_MENTIONED 추가 + 카운트 갱신 + V403 멘션 정책 시드

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/notification/src/main/kotlin/com/bts/notification/domain/NotificationEventType.kt`, `backend/modules/notification/src/test/kotlin/com/bts/notification/domain/NotificationEventTypeTest.kt`, `backend/modules/notification/src/main/resources/db/migration/notification/V403__seed_mention_policy.sql`, `backend/modules/notification/src/test/kotlin/com/bts/notification/repository/NotificationPolicyRepositoryIntegrationTest.kt`, `backend/modules/notification/src/test/kotlin/com/bts/notification/NotificationPolicyEndToEndIntegrationTest.kt`]
- depends-on: []

**RED**. `fromWire("issue.mentioned")` → `ISSUE_MENTIONED` 테스트. enum size 9→10 갱신. V403 시드(issue.mentioned×MENTIONED×IN_APP) 존재 통합테스트.
**GREEN**. enum 상수 `ISSUE_MENTIONED("issue.mentioned", false)` 추가. 주석 "9종→10종". `entries.size shouldBe 10`. V403 시드(ON CONFLICT DO NOTHING). 시드 카운트 검증 테스트 갱신(learnings: enum-add-breaks-count-guard / fr-pm-permission-seed-migration-test-coupling — 같은 PR서 카운트 전수 갱신).
**REFACTOR**. cross-module 가드 없음 확인(NotificationEventType은 notification 모듈 전용 — 실측 완료).
**검증**. `./gradlew :notification:test`.

### Task 5. IssueRecipientLookupPort (shared-kernel) + fail-safe default

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/shared-kernel/src/main/kotlin/com/bts/shared/issue/IssueRecipientLookupPort.kt`, `backend/modules/shared-kernel/src/test/kotlin/com/bts/shared/issue/IssueRecipientLookupPortDefaultTest.kt`]
- depends-on: []

**RED**. default 구현이 빈 수신자(`IssueRecipients(reporterId=null, assigneeId=null)` 또는 빈 결과) 반환 — prod 빈 부재 시 fail-safe(알림 미발송, learnings: crossbc-resolver-nullable-fail-open — non-null allow-empty 기본).
**GREEN**. `interface IssueRecipientLookupPort { fun findRecipients(issueKey: String): IssueRecipients }` + `IssueRecipients` data class + default 구현(빈). (learnings: interface-extension-default-method — default로 fail-safe.)
**REFACTOR**. KDoc 포트 계약 명시.
**검증**. `./gradlew :shared-kernel:test`.

### Task 6. issue-tracking IssueRecipientLookupAdapter (담당/리포터 조회)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/notification/IssueRecipientLookupAdapter.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/notification/IssueRecipientLookupAdapterIntegrationTest.kt`]
- depends-on: [5]

**RED**. 이슈 시드(reporter/assignee 지정) 후 `findRecipients(issueKey)` → 정확한 reporter/assignee UUID. soft-deleted 이슈 → 빈(fail-safe). 통합테스트.
**GREEN**. `@Component` jOOQ adapter, `issues` 조회(deleted_at IS NULL 필터). UserLookupAdapter 패턴 답습.
**REFACTOR**. 쿼리 상수화.
**검증**. `./gradlew :issue-tracking:test --tests "*IssueRecipientLookupAdapterIntegrationTest"`.

### Task 7. EventRecipientResolver (이벤트 → 수신자 결정: 멘션/리포터/담당)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/notification/src/main/kotlin/com/bts/notification/recipient/EventRecipientResolver.kt`, `backend/modules/notification/src/test/kotlin/com/bts/notification/recipient/EventRecipientResolverTest.kt`]
- depends-on: [5]

**RED**(단위, 포트 mock). PolicyMatch 목록 + 이벤트 → (역할별) 수신자 UUID 산출. `MENTIONED`→payload mentionedUserIds. `REPORTER`/`ASSIGNEE`→`IssueRecipientLookupPort` 조회(또는 issue.created reporterId payload). `WATCHER`/기타 role→빈(FR-NT-03). actor 본인 제외. 정책 0건→빈.
**GREEN**. resolver. **이름 주의** — FR-NT-03 정식 RecipientResolver와 구분(learnings: duplicate-exception-name-cross-package). 이번 PR은 `EventRecipientResolver`(payload+포트 최소판).
**REFACTOR**. role→해석 전략 분기 정리.
**검증**. `./gradlew :notification:test --tests "*EventRecipientResolverTest"`.

### Task 8. NotificationChannelSender 추상 + InAppChannelSender (STOMP 푸시)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/notification/src/main/kotlin/com/bts/notification/channel/NotificationChannelSender.kt`, `backend/modules/notification/src/main/kotlin/com/bts/notification/channel/InAppChannelSender.kt`, `backend/modules/notification/src/test/kotlin/com/bts/notification/channel/InAppChannelSenderTest.kt`]
- depends-on: [2]

**RED**(단위, `SimpMessagingTemplate` mock). InAppChannelSender가 `convertAndSendToUser(recipientId, "/queue/notifications", payload)` 호출. `supports(Channel.IN_APP)` 매칭.
**GREEN**. `interface NotificationChannelSender { fun supports(c): Boolean; fun send(n: Notification) }` + `InAppChannelSender`(@Component, SimpMessagingTemplate 주입).
**REFACTOR**. payload DTO(id/eventType/issueKey/title/body/occurredAt) 분리.
**검증**. `./gradlew :notification:test --tests "*InAppChannelSenderTest"`.

### Task 9. STOMP WebSocket 서버 config + JWT ChannelInterceptor (보안)

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/notification/src/main/kotlin/com/bts/notification/config/WebSocketConfig.kt`, `backend/modules/notification/src/main/kotlin/com/bts/notification/config/StompAuthChannelInterceptor.kt`, `backend/modules/notification/src/test/kotlin/com/bts/notification/config/StompAuthChannelInterceptorTest.kt`, `backend/modules/notification/build.gradle.kts`]
- depends-on: []

**RED**(단위). CONNECT frame `Authorization: Bearer <jwt>` → 기존 JWT 디코더 검증 → `Principal`(userId) 설정. 미검증/만료 → CONNECT 거부(예외). PAT(pat_ prefix) 거부.
**GREEN**. `@EnableWebSocketMessageBroker` config(endpoint `/ws`, broker `/queue`, user prefix `/user`). `StompAuthChannelInterceptor.preSend`(CONNECT command 시 인증). 의존성 추가: `spring-boot-starter-websocket` + **`spring-security-oauth2-jose`**(eng-review C1 — JwtDecoder 타입 제공 패키지가 현 build.gradle에 없음). JWT 검증 = Spring 표준 `org.springframework.security.oauth2.jwt.JwtDecoder` 빈 주입(identity-access JwtConfig가 제공, 런타임 동일 ApplicationContext) — **Spring 타입이라 BTS 패키지 import 0, BC 격리 위반 아님**(다른 모듈이 SecurityContextHolder 읽는 선례와 동형).
**REFACTOR**. 보안 KDoc(STATELESS 근거, 세션쿠키 불가).
**검증**. `./gradlew :notification:test --tests "*StompAuthChannelInterceptorTest"`.

### Task 10. NotificationWorker (pgmq q_issue_events consumer 조립)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/notification/src/main/kotlin/com/bts/notification/worker/NotificationWorker.kt`, `backend/modules/notification/src/test/kotlin/com/bts/notification/worker/NotificationWorkerTest.kt`]
- depends-on: [3, 4, 7, 8]

**RED**(단위, 협력자 mock). 이벤트 소비→`NotificationPolicyEvaluator.evaluate`→`EventRecipientResolver`→`NotificationRepository.insert`(멱등)→삽입된 건만 `NotificationChannelSender.send`. 성공 시 `pgmq.delete`, 예외 시 미삭제, `read_ct>MAX`→`pgmq.archive`. `@Transactional` 미부착.
**GREEN**. `@Component` + `@Scheduled(fixedDelay)` `pgmq.read` 폴링. **폴링 주기 ≤ 500ms**(eng-review C2 — 알림 지연 p95<1s NFR1 충족 위해 BulkOperationWorker 1000ms보다 짧게, 설정 프로퍼티 + 근거 KDoc). `BulkOperationWorker` 패턴 답습. 이벤트 역직렬화(Jackson 다형성, `IssueDomainEvent`는 shared 계약 — JSON 파싱).
**REFACTOR**. KDoc 처리 흐름(BulkOperationWorker 스타일), vt/MAX 상수 + 근거.
**검증**. `./gradlew :notification:test --tests "*NotificationWorkerTest"`.

### Task 11. STOMP end-to-end 통합테스트 (연결→이벤트 발행→수신)

**메타**.
- agent: `qa-engineer`
- files: [`backend/modules/notification/src/test/kotlin/com/bts/notification/NotificationDeliveryEndToEndIntegrationTest.kt`]
- depends-on: [9, 10]

**RED→GREEN**. Testcontainers(pgmq postgres) + 실제 STOMP 클라이언트로 인증 연결 + 구독 → `q_issue_events`에 `IssueMentioned` 발행 → 토스트 payload 수신 검증. 미인증 연결 거부(S6). 재전달 멱등(S4 — 중복 미수신). 지연 측정(NFR1 기록). 동시 suite flaky 주의(learnings: concurrent-testcontainers-suite-flaky).
**검증**. `./gradlew :notification:test --tests "*NotificationDeliveryEndToEndIntegrationTest"`.

### Task 12. FR drift 전수 동기화 (채널 3종 확정, Teams 제거) + D 단계 마킹

**메타**.
- agent: `backend-engineer`
- files: [`docs/plan/fr-index.md`, `docs/sdd/09-notifications-slack.md`, `docs/sdd/02-requirements.md`, `docs/plan/product/notification-dashboard.md`, `docs/plan/README.md`]
- depends-on: []

**작업**. FR-NT-02 제목 "이메일/인앱/Slack/Teams/Webhook" → "이메일/인앱/Webhook (Slack=slack-integration BC)"로 정정(Teams 제거). product §2.2 D1~D5 `[x]` 마킹(이번 PR backend 분), D6/D7은 후속 PR. SDD §9 채널 표 정합. fr-index 카운트 무변동(FR 개수 동일, 채널 명세만). ADR 결정 2 근거 인용.
**검증**. `bash scripts/verify-master-plan.sh` 통과(종료 0). dashboard 재생성은 머지 단계(bts-merge).

## Plan 메타

- task 수: 12 (각 TDD 사이클). 코드 11 + 문서동기화 1.
- 모듈 분포: notification(7: T1·T2·T3·T4·T7·T8·T9·T10·T11 다수) / shared-kernel(T5) / issue-tracking(T6) / docs(T12).
- depends-on 그래프 → 예상 wave.
  - Wave 1 (deps []): T1, T2, T4, T5, T9, T12
  - Wave 2: T3[1,2], T6[5], T7[5], T8[2]
  - Wave 3: T10[3,4,7,8]
  - Wave 4: T11[9,10]
- 같은 notification 모듈 다수 task → test 컴파일 단위 직렬화 요인(learnings: bts-plan-wave-gradle-module-compile). files 겹침 자동 직렬화 + bts-impl wave 계산.
- TDD 강제: yes. agent 분담: db(T1), security(T9), qa(T11), 나머지 backend.
- 추가 검증: ktlint/detekt(--rerun-tasks, baseline 동결), ArchUnit BC 격리, verify-master-plan.sh.

## 리뷰 결과

### plan-eng-review (2026-06-12) — 백엔드 집중 독립 리뷰

**Scope Challenge**. ✅ 적절. 신규 클래스 다수(8종)이나 발송 인프라 그린필드라 불가피. 이미 "인앱 slice"로 축소 + PR 분할 확정(backend/frontend). over-engineering 없음.

**BLOCKER: 없음.**

**CONCERN (plan 반영 완료)**.
- C1 [Architecture, 8/10] cross-BC JWT 검증. notification build.gradle에 `spring-security-oauth2-jose`(JwtDecoder 타입) 미포함 → **T9 GREEN에 의존성 추가 명시**. 검증은 Spring 표준 `JwtDecoder` 빈 주입(identity-access 제공) — BTS 패키지 import 0이라 BC 격리 OK(타 모듈 SecurityContextHolder 선례와 동형). 실측 확인.
- C2 [Performance, 7/10] pgmq 폴링 주기가 알림 지연 p95<1s(NFR1) 좌우 → **T10 GREEN에 폴링 주기 ≤500ms 명시**.
- C3 [Architecture, 6/10] `issue.transitioned` 담당자=포트 조회 시점의 현재 담당자(이벤트 발생시점과 race 가능). 영향 작음(알림은 best-effort), 수용. 통합테스트에서 시드 고정으로 결정적 검증.
- C4 [Test, 7/10] T11 STOMP 통합테스트 flaky 위험(Testcontainers+실 WS). 수용 — 단독 재실행으로 확정(learnings: concurrent-testcontainers-suite-flaky).
- C5 [Scope/일관성, 6/10] `Channel` enum은 5종(IN_APP/EMAIL/SLACK/TEAMS/WEBHOOK) 유지 — 카탈로그라 미구현 채널 표현 무방. ADR 결정2의 "직접 담당 3종"은 발송 구현체/문서 레벨. T12에서 문서만 정정, enum 코드 변경 없음. 일관성 확인 완료.

**실패 모드(critical gap 점검)**. (1) JWT 검증 실패→CONNECT 거부(테스트 O, 에러 O, 사용자에 ERROR frame). (2) 이슈 조회 실패→빈 수신자 fail-safe(테스트 O, 에러 처리 O, silent 미발송 — 알림이라 허용). (3) push 실패→기록은 SENT, 실시간 유실(FR-UX-03 Inbox 보완, E7). **critical gap(테스트X+에러처리X+silent) 0건.**

**병렬화**. Lane A=notification 모듈(T1·2·3·4·7·8·10·11 — 같은 test 컴파일 단위, 일부 직렬) / Lane B=shared-kernel(T5) / Lane C=issue-tracking(T6) / Lane D=security(T9, notification). B·C·D는 A와 독립 병렬. bts-impl이 wave+files 겹침으로 계산.

**NOT in scope(명시 deferral)**. 이메일/Webhook 채널(동일 추상 후속 PR), RecipientResolver 워처/role 해석(FR-NT-03), UserSubscription override(FR-NT-04), Inbox 조회 UI/카운트(FR-UX-03), frontend STOMP 클라+토스트+E2E(D6/D7 후속 PR), Teams 채널(범위 제외).

**What already exists(재사용)**. `NotificationPolicyEvaluator`(평가 엔진, T10 재사용), `Channel`/`NotificationEventType`/`RecipientRole` enum(T4서 issue.mentioned만 보강), `sonner`(후속 PR), `BulkOperationWorker`(consumer 패턴 템플릿), `UserLookupPort`(포트 선례), `SecurityContextHolder`/`CurrentActor`(인증 읽기 선례).

**verdict**. CLEARED — 아키텍처/테스트/성능 검토 통과, BLOCKER 0, CONCERN 5건 전부 plan 반영. 구현 진입 가능.
