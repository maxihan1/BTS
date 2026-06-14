# FR-NT-05 PR2 — Webhook 디스패처 (q_transition_events 소비 + HTTP POST)

> slug: fr-nt-05-webhook-dispatcher
> type: backend
> agent: backend-engineer
> primary_bc: notification (classify는 project-workflow 오판 — /bts-domain서 확정)
> 생성: 2026-06-14

## Brief

FR-NT-05 PR2 — notification BC가 pgmq 큐 `q_transition_events`의 `WebhookRequested` 메시지를 소비해 외부 URL로 HTTP POST를 보내는 Webhook 디스패처를 구현한다. pgmq consumer 메시지 생명주기(delete/archive/stale 재청) 준수. 완료 시 FR-NT-05를 부분완료[~]에서 완료로 전환하고 product notification-dashboard.md §2.5 D단계를 마킹한다.

직전 PR1(#140, squash 01eec8b8)에서 **발행 파이프라인**은 이미 배선됨 — issue-tracking의 `transitionIssue()`가 `plan.emitEvents`(WebhookRequested 등 4종)를 전용 큐 `q_transition_events`에 outbox 발행. 이번 PR2는 그 큐의 **소비 + 외부 전송** 쪽.

classify: type=backend, agent=backend-engineer, slug=fr-nt-05-webhook-dispatcher

## 도메인 정리

- **BC: notification** (코드 실측 확정). classify는 "transition" 키워드로 project-workflow 오판. 실제는 notification BC가 `q_transition_events`를 소비. PR1 ADR(`2026-06-14-fr-nt-05-transition-event-outbox.md`)이 "PR2 = notification 소비/디스패처"를 명시적으로 예고 — 기존 결정과 충돌 0.
- **유일 소비자 확정**. `q_transition_events`는 PR1이 만든 전용 큐(V022). 현재 소비자 0. PR2 워커가 단독 소비자라 `NotificationWorker`의 q_issue_events 경쟁 소비 경고(여러 consumer가 메시지 선점) 해당 없음.
- **소비할 메시지 형식** (V022 주석 + 발행측 실측). `{"type":"WebhookRequested","payload":{"issueKey":"...","url":"...","method":"..."}}`. `CallWebhookPostAction.evaluate()`가 만들고 `TransitionEventPublisher.publish()`가 `pgmq.send(q_transition_events, json::jsonb)`로 발행.
- **정석 템플릿 = `NotificationWorker`** (`backend/modules/notification/.../worker/NotificationWorker.kt`). pgmq 생명주기 패턴 그대로 차용: `@Scheduled` 폴링 → `pgmq.read(q, vt, batch)` → 성공시 `pgmq.delete` → 예외시 delete 안 함(vt 만료 재전달, at-least-once) → `read_ct>MAX_RECEIVE_COUNT`시 `pgmq.archive`(dead-letter). `@Transactional` 없음(의도적, learnings transaction-self-invocation). 미지원 type은 delete(ack)로 무한 재전달 회피.
- **신규 용어**: 없음 (Webhook·디스패처는 표준어, glossary 추가 불요). glossary grep 0건 — 기존 미등록이나 일반 용어라 신설 안 함.
- **신규 ADR 후보**: **첫 백엔드 아웃바운드 HTTP**(외부 URL POST)라 SSRF 방어 수위·HTTP 클라이언트·재시도 의미를 `/bts-spec`/`/bts-review-plan`에서 결정 후 ADR 작성 여부 판단.
- **관련 ADR**: [2026-06-14-fr-nt-05-transition-event-outbox.md](../decisions/2026-06-14-fr-nt-05-transition-event-outbox.md) (PR1, 본 PR이 그 큐·envelope 소비), [2026-06-12-notification-inapp-channel-delivery.md](../decisions/2026-06-12-notification-inapp-channel-delivery.md) (인앱 채널 선례).

## 스펙

전체 스펙. [docs/specs/2026-06-14-fr-nt-05-webhook-dispatcher.md](../specs/2026-06-14-fr-nt-05-webhook-dispatcher.md)

핵심 3줄 요약.
- notification BC `WebhookDispatchWorker`가 `q_transition_events`를 폴링 → `WebhookRequested`만 외부 URL로 RestClient 전송, 생명주기는 NotificationWorker와 동일(read→delete/재전달/archive).
- **D1 SSRF 가드** — DNS 해석 후 내부망 IP(loopback/link-local/private/metadata) + 비-http 스킴 차단. **D2 body** — `{"event":"WebhookRequested","issueKey":"<key>"}` + application/json.
- 신규 마이그레이션 0(큐는 PR1 V022가 생성). RestClient는 spring-web 내장(신규 의존성 0).

Maxi 게이트 결정. D1=내부망 차단 리스트, D2=JSON 엔벨로프.

## Brainstorming Check

✅ 통과 (직접 sanity check, 보안 갭 2건 발견·반영).
- G1 → FR8 추가. RestClient 리다이렉트 추적 차단(3xx 경유 SSRF 우회 방지).
- G2 → 한계 명시. TOCTOU/DNS rebinding은 resolve-then-connect 구조 한계, admin URL+PR2 범위 수용·문서화.

## Plan

> 패키지 루트: `com.bts.notification`. 모든 신규 코드는 notification 모듈 내부(BC 격리).
> 계약. `WebhookDispatcher.dispatch(url, method, issueKey): WebhookDispatchResult`
> `WebhookDispatchResult` = `Sent`(2xx) | `Rejected`(영구: SSRF차단/malformed/비-http → 워커 delete) | `Failed`(일시: non-2xx/타임아웃/연결오류/DNS실패 → 워커 keep 재전달).
> `WebhookUrlValidator.check(url): UrlCheck` = `Allowed` | `Blocked(reason)` | `Malformed(reason)`.

### Task 1. WebhookUrlValidator — SSRF 내부망 차단 (D1)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/notification/src/main/kotlin/com/bts/notification/webhook/WebhookUrlValidator.kt`, `backend/modules/notification/src/test/kotlin/com/bts/notification/webhook/WebhookUrlValidatorTest.kt`]
- depends-on: []

**RED** (`WebhookUrlValidatorTest`, 네트워크 불요 — 리터럴 IP는 DNS 미조회).
- `http://127.0.0.1/x` → Blocked(loopback)
- `http://169.254.169.254/latest/meta-data` → Blocked(link-local/metadata)
- `http://10.0.0.5/`, `http://192.168.1.1/`, `http://172.16.0.1/` → Blocked(private)
- `http://93.184.216.34/` (리터럴 공인 IP) → Allowed
- `ftp://host/`, `file:///etc/passwd` → Malformed/Blocked(비-http 스킴)
- `""`(blank), `"not a url"` → Malformed
- 실패 메시지(예상): `WebhookUrlValidator` 클래스 없음

**GREEN**.
- `WebhookUrlValidator`(@Component): URL 파싱 → 스킴 http/https 검사 → `InetAddress.getAllByName(host)`로 해석 → 각 IP가 `isLoopbackAddress || isLinkLocalAddress || isSiteLocalAddress || isAnyLocalAddress || isMulticastAddress` 또는 private 대역이면 Blocked. (Kotlin `java.net.InetAddress` 사용.)

**REFACTOR**. 차단 판정을 `private fun isInternal(addr): Boolean`로 추출 + KDoc에 G2(TOCTOU/DNS rebinding 한계) 명시.

**검증**: `./gradlew :backend:modules:notification:test --tests "*WebhookUrlValidatorTest*"`

### Task 2. WebhookDispatcher + RestClient 빈 (전송·엔벨로프·리다이렉트 차단)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/notification/src/main/kotlin/com/bts/notification/webhook/WebhookDispatcher.kt`, `backend/modules/notification/src/main/kotlin/com/bts/notification/webhook/WebhookDispatchResult.kt`, `backend/modules/notification/src/main/kotlin/com/bts/notification/config/WebhookHttpClientConfig.kt`, `backend/modules/notification/src/test/kotlin/com/bts/notification/webhook/WebhookDispatcherTest.kt`]
- depends-on: [1]

**RED** (`WebhookDispatcherTest`, JDK 내장 `com.sun.net.httpserver.HttpServer` 로컬 stub — 신규 의존성 0).
- 2xx 응답 stub → `dispatch()` = `Sent`, stub이 받은 요청 검증: method=POST, `Content-Type: application/json`, body=`{"event":"WebhookRequested","issueKey":"PROJ-1"}`.
- 5xx 응답 stub → `Failed`.
- 3xx(302 → 내부 IP) stub → 리다이렉트 미추적, `Failed`, 리다이렉트 타깃 미요청(stub2 hit=0) 검증 (FR8/G1).
- SSRF 차단 URL(127.0.0.1) → `Rejected`, stub hit=0 (전송 안 함).
- url blank/비-http → `Rejected`.
- `method`="PUT" → 실제 PUT 전송 검증. 알 수 없는 method → POST fallback.
- 실패 메시지(예상): `WebhookDispatcher` 없음

**GREEN**.
- `WebhookHttpClientConfig`(@Configuration): `@Bean webhookRestClient`(RestClient) — JDK `HttpClient.newBuilder().followRedirects(NEVER).connectTimeout(...)` + `JdkClientHttpRequestFactory`(read timeout) → `RestClient.builder().requestFactory(...).build()`. 타임아웃은 `@Value` (connect 3000 / read 5000ms 기본).
- `WebhookDispatchResult`(sealed: Sent/Rejected/Failed).
- `WebhookDispatcher`(@Component, validator + RestClient 주입): validator.check → Blocked/Malformed면 Rejected(전송 X, 보안 로그). Allowed면 엔벨로프 body 구성 → RestClient로 method 전송 → 2xx면 Sent, 그 외면 Failed. 예외(타임아웃/연결/DNS)는 catch → Failed.

**REFACTOR**. body 직렬화는 ObjectMapper 주입 재사용. 보안 차단 로그 키 `webhook_url_blocked`(주체/사유 only, URL은 host만).

**검증**: `./gradlew :backend:modules:notification:test --tests "*WebhookDispatcherTest*"`

### Task 3. WebhookDispatchWorker — pgmq consumer 생명주기

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/notification/src/main/kotlin/com/bts/notification/worker/WebhookDispatchWorker.kt`, `backend/modules/notification/src/test/kotlin/com/bts/notification/worker/WebhookDispatchWorkerTest.kt`]
- depends-on: [2]

**RED** (`WebhookDispatchWorkerTest`, MockK로 `DSLContext`+`WebhookDispatcher` mock — NotificationWorkerTest 패턴 미러, Testcontainers 불요).
- W-1. 빈 큐 → 아무 처리 없음.
- W-2. WebhookRequested + dispatcher=Sent → `pgmq.delete` 호출.
- W-3. dispatcher=Rejected(SSRF) → delete 호출(영구), dispatcher 1회만.
- W-4. dispatcher=Failed → delete 미호출(재전달), read_ct≤MAX면 archive 미호출.
- W-5. read_ct>MAX_RECEIVE_COUNT + Failed → `pgmq.archive`.
- W-6. 범위 외 type(`WatcherAdded`) → dispatch 미호출 + delete(ack).
- W-7. JSON 파싱 실패(poison) → read_ct>MAX시 archive, 미만시 재전달 대기.

**GREEN**.
- `WebhookDispatchWorker`(@Component, dsl + dispatcher + objectMapper): `@Scheduled(fixedDelayString="\${bts.notification.webhook.poll-interval-ms:500}")` → `pgmq.read(q_transition_events, VT, BATCH)` → 각 메시지 type 분기. `@Transactional` 없음(NotificationWorker 선례). 상수 QUEUE_NAME/VT/BATCH/MAX는 NotificationWorker 값 차용.

**REFACTOR**. delete/archive 헬퍼 + KDoc(생명주기·BC격리·@Transactional 부재 사유).

**검증**: `./gradlew :backend:modules:notification:test --tests "*WebhookDispatchWorkerTest*"`

### Task 4. End-to-end 통합 (실 pgmq + 실 HTTP) + 부팅 안전

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/notification/src/test/kotlin/com/bts/notification/webhook/WebhookDispatchEndToEndIntegrationTest.kt`]
- depends-on: [3]

**RED→GREEN** (Testcontainers PostgreSQL + pgmq + JDK HttpServer stub; `q_transition_events` 큐를 테스트 config에서 생성 — `NotificationDeliveryTestcontainersConfig` 선례).
- `pgmq.send(q_transition_events, WebhookRequested JSON)` → 워커 `pollAndProcess()` 직접 호출 → stub 서버가 POST+엔벨로프 수신 + 메시지 delete 검증.
- 부팅 안전 확인: 신규 @Component(worker/dispatcher/validator/RestClient 빈)가 전체-컨텍스트 부팅을 깨지 않음(의존성 전부 notification-내부, @Scheduled는 test 프로파일 비활성). 필요 시에만 TestcontainersConfig 보강.

**검증**: `./gradlew :backend:modules:notification:test --tests "*WebhookDispatchEndToEndIntegrationTest*"` + 모듈 전체 `:backend:modules:notification:test`(회귀 0).

### Task 5. FR-NT-05 완료 전수 동기화 (문서)

**메타**.
- agent: `backend-engineer`
- files: [`docs/plan/fr-index.md`, `docs/plan/product/notification-dashboard.md`, `docs/plan/README.md`, `CLAUDE.md`, `docs/sdd/09-notifications-slack.md`, `docs/sdd/02-requirements.md`]
- depends-on: []

**작업** (TDD 비대상 — 문서 동기화. CLAUDE.md §명세/범위 변경 전수 동기화).
- FR-NT-05 `[~]`(부분완료) → 완료로 전환. product `notification-dashboard.md` §2.5 D단계 체크박스 마킹.
- fr-index/README/CLAUDE/SDD의 FR-NT-05 상태·카운트 정합(122/123 불변, 상태만 변경).
- ADR — PR2 신규 결정(SSRF 가드·RestClient·리다이렉트 차단)을 PR1 ADR에 Amendment로 추가하거나 신규 ADR 작성(review-plan서 판단).

**검증**: `bash scripts/verify-master-plan.sh` exit 0.

## Plan 메타

- task 수: 5 (T1~T4 TDD 사이클 + T5 문서)
- 의존 그래프: T1→T2→T3→T4 직렬(레이어 빌드업, 동일 모듈/테스트 컴파일 단위라 어차피 직렬화 — 메모리 bts-plan-wave-gradle-module-compile). T5 독립(docs).
- 예상 wave: 사실상 직렬(단일 backend-engineer, 단일 모듈). T5만 병렬 가능.
- TDD 강제: T1~T4 yes (test 커밋이 feat 커밋보다 먼저).
- 추가 검증: ktlint/detekt(--rerun-tasks 직접 재검증 — 메모리 subagent-ktlint-false-green), verify-master-plan.

## 리뷰 결과 (← /bts-review-plan 채움)
