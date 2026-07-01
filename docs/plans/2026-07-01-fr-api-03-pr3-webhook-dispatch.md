# FR-API-03 PR3 — Webhook 발송 실행 계층 (fanout/dispatch)

> slug: fr-api-03-pr3-webhook-dispatch
> type: api
> agent: backend-engineer
> 생성: 2026-07-01

## Brief

FR-API-03(구독형 아웃바운드 Webhook)의 4-PR 분할 중 **PR3 — 발송 실행 계층**.
PR1(shared-kernel 추출, #211)·PR2(구독 CRUD + 테이블, #212) 완료 후속.

범위:
- issue-tracking `IssueEventPublisher` dual-send — 전용 큐 `q_webhook_events`로도 이벤트 발행
  (q_issue_events는 NotificationWorker 독점소비라 경합불가 — ADR 확정)
- search-export-import fanout/dispatch 워커 — 큐 소비 → 구독 매칭(event_filter) → 발송
- HMAC-SHA256 서명 (요청 헤더)
- circuit breaker (연속 실패 시 구독 일시 차단)
- 발송이력 기록 (`webhook_deliveries` append-only 테이블 — PR2 V603에서 생성됨)

관련: ADR docs/decisions/2026-07-01-fr-api-03-outbound-webhook-bc-and-reuse.md
FR-NT-05 webhook dispatcher(notification 전이 webhook)의 상위집합.

## 도메인 정리 (← /bts-domain 채움)

- **BC**: search-export-import(webhook 발송 주체) + issue-tracking(dual-send = 이벤트 발행만, BC 격리 준수).
  두 BC 걸침이나 issue-tracking 쪽은 pgmq 이벤트 발행 추가뿐이라 격리 예외 아님(직접 import 0).
- **재사용 (grill-with-docs 대신 ADR/코드 실증)**:
  - ADR `2026-07-01-fr-api-03-outbound-webhook-bc-and-reuse.md`가 BC 배치·재사용·이벤트소싱을 이미 확정 → grill 인터뷰 가치 낮음.
  - PR2가 도메인(`OutboundWebhook`, `WebhookEventCatalog.PUBLISHABLE={issue.created, issue.transitioned}`)·테이블(V603)·구독 CRUD 완료·머지.
  - FR-NT-05 `WebhookDispatcher`(notification): SSRF(`OutboundUrlValidator` shared)+RestClient(`Redirect.NEVER`)+pgmq 워커 생명주기(Sent/Rejected→delete, Failed→VT 재전달, read_ct>MAX→archive). **PR3는 이 위에 HMAC·구독매칭·발송이력·circuit breaker를 신규 추가**(상위집합).
- **dual-send 접점**: `IssueEventPublisher.publish()`(issue-tracking, `@Transactional MANDATORY` outbox). 현재 `q_issue_events`만 → PUBLISHABLE 이벤트를 `q_webhook_events`로도 발행.
- **이벤트 payload**: `IssueDomainEvent` 7종 중 `IssueCreated`(projectKey 有)·`IssueTransitioned`(**projectKey 無** — issueKey에서 파싱 필요). 구독 projectKey 필터 매칭 시 고려.
- **새 도메인 요소**: `WebhookDelivery`(발송 이력 레코드, `webhook_deliveries` 매핑), circuit breaker(연속 실패 차단), HMAC-SHA256 서명. 모두 ADR/product §5.3에 이미 정의됨(신규 용어 아님).
- **기존 결정 충돌**: 없음. ADR이 재사용을 예견.
- **관련 ADR**: [2026-07-01-fr-api-03-outbound-webhook-bc-and-reuse.md](../decisions/2026-07-01-fr-api-03-outbound-webhook-bc-and-reuse.md), [2026-06-14-fr-nt-05-webhook-dispatch-ssrf.md](../decisions/2026-06-14-fr-nt-05-webhook-dispatch-ssrf.md)
- **스키마 공백(PR3 열린 결정)**: `outbound_webhooks`/`webhook_deliveries`에 circuit breaker 상태 필드 없음 → 저장 위치 결정 필요(spec Phase에서 Maxi 확정).

## 스펙

전체 스펙. [docs/specs/2026-07-01-fr-api-03-pr3-webhook-dispatch.md](../specs/2026-07-01-fr-api-03-pr3-webhook-dispatch.md)

핵심 3줄.
- issue-tracking `IssueEventPublisher`가 PUBLISHABLE 2종(issue.created·issue.transitioned)을 `q_webhook_events`(신규 V034)로도 dual-send.
- search 워커가 큐 소비 → event_filter+projectKey 매칭 구독 fanout → HMAC-SHA256 서명 발송 → `webhook_deliveries` 이력 → in-memory circuit breaker(5회/60초/half-open).
- 발송 이력 조회 API `GET /webhooks/{id}/deliveries`(SYSTEM_ADMIN) PR3 포함. 부분 실패는 delete+이력/circuit 관찰(개별 재시도 큐 없음).

**Maxi 확정 (2회 AskUserQuestion)**.
- circuit breaker = in-memory + 이번 PR 포함 (5회/60초/half-open).
- 발송 이력 조회 API = PR3 포함.
- fanout 부분 실패 = delete + 이력/circuit 관찰 (pgmq VT 재전달만).

## Brainstorming Check

✅ 통과 (gap 3건 식별·해소 — 카탈로그 2종 drift 정본화 / 이력 조회 API PR3 포함 / fanout 재시도 정책). 상세는 spec §Brainstorming Check.

## Plan

> 10 task, 2 모듈(issue-tracking dual-send / search-export-import 발송). 각 task TDD 사이클.
> 신규 패키지 `com.bts.search.webhook.dispatch`. PR2 `com.bts.search.webhook.{domain,application,persistence,web}` 확장.

### Task 1. q_webhook_events 큐 마이그레이션 (issue-tracking V034)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/resources/db/migration/issue-tracking/V034__pgmq_queue_webhook_events.sql`, `backend/modules/issue-tracking/src/main/resources/db/codegen/init_codegen.sql`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/event/WebhookEventsQueueMigrationTest.kt`]
- depends-on: []

**RED**: Testcontainers 통합 테스트 — `q_webhook_events` 큐가 존재하고 `pgmq.send/read` 가능한지 검증. 큐 부재로 실패.
**GREEN**: V034 = `SELECT pgmq.create('q_webhook_events');` (V022 q_transition_events 패턴 그대로). init_codegen.sql에 동일 큐 미러(메모리 jooq-init-codegen-mirror).
**REFACTOR**: SQL L1 주석(Korean) — 큐 목적(FR-API-03 webhook dual-send), 소비자=search 워커 명시.
**검증**: `./gradlew :backend:modules:issue-tracking:test --tests '*WebhookEventsQueueMigrationTest'`

### Task 2. IssueEventPublisher dual-send

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/event/IssueEventPublisher.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/event/IssueEventPublisherTest.kt`]
- depends-on: [1]

**RED**: `IssueEventPublisher.publish()` 호출 시 (a) PUBLISHABLE 이벤트(issue.created/issue.transitioned)는 `q_issue_events`·`q_webhook_events` **두 큐 모두** send, (b) non-PUBLISHABLE(issue.updated 등)은 `q_issue_events`만 send. mock `DSLContext`로 send 호출 인자 검증. 현재는 q_issue_events만이라 (a) 실패.
**GREEN**: `publish()`에 dual-send 추가. **BC 격리** — search `WebhookEventCatalog` import 불가 → issue-tracking 자체 판정. **stringly-typed set 대신 `IssueDomainEvent` sealed 타입 exhaustive `when`**(else 없이 7종 전부 분류: created/transitioned=q_webhook_events 발행, 나머지 5종=미발행) → 새 이벤트 추가 시 컴파일 실패로 강제 분류(C4, silent under-send 차단). 같은 트랜잭션(`@Transactional MANDATORY` 유지) → outbox 보장.
**REFACTOR**: 판정 `when`을 private 함수로 + KDoc(search `WebhookEventCatalog.PUBLISHABLE`과 값 정합, BC 격리상 코드 공유 불가한 정당한 분리 — exhaustive when + T10 단언이 이중 가드).
**검증**: `./gradlew :backend:modules:issue-tracking:test --tests '*IssueEventPublisherTest'`
**리스크 (C4/C6)**: (1) issue-tracking 판정 ↔ search `PUBLISHABLE` drift — exhaustive when으로 under-send는 컴파일 차단, over-send는 search `findMatching`이 무해화. 값 정합은 T10 "PUBLISHABLE 각 이벤트→발송 도달" 단언으로 이중 가드. (2) q_webhook_events는 이제 모든 PUBLISHABLE 발행 경로(create/transition MANDATORY 트랜잭션)의 **하드 의존** → T1 선행 필수, 두 하네스 경로(Flyway migrate / init_codegen) 모두 큐 포함 확인(누락 시 이슈 생성/전이 트랜잭션 롤백).

### Task 3. WebhookDelivery 도메인 + Repository (기록 + 조회)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/search-export-import/src/main/kotlin/com/bts/search/webhook/domain/WebhookDelivery.kt`, `backend/modules/search-export-import/src/main/kotlin/com/bts/search/webhook/application/WebhookDeliveryRepository.kt`, `backend/modules/search-export-import/src/main/kotlin/com/bts/search/webhook/persistence/JooqWebhookDeliveryRepository.kt`, `backend/modules/search-export-import/src/test/kotlin/com/bts/search/webhook/persistence/JooqWebhookDeliveryRepositoryTest.kt`]
- depends-on: []

**RED**: `JooqWebhookDeliveryRepository` Testcontainers 테스트 — record(webhookId, eventType, status, responseCode?, attemptCount, errorDetail?) INSERT 후 `listByWebhook(webhookId, page, size)` 조회 시 최신순(created_at DESC) 반환. 클래스 부재로 실패.
**GREEN**: `WebhookDelivery` 도메인(status enum SUCCEEDED/FAILED, id/deliveredAt nullable). `WebhookDeliveryRepository` 포트(`record(...): WebhookDelivery`, `listByWebhook(...)`). Jooq 구현 — INSERT `webhook_deliveries`(RETURNING id/created_at), append-only(soft-delete 없음). deliveredAt/created_at은 DB now() 또는 Clock 주입. `webhook_deliveries.id`는 **per-attempt 내부 감사 PK** — 외부 발송 멱등키(`X-BTS-Delivery`)와 분리(B1, T7/T8 소관).
**REFACTOR**: DeliveryStatus enum + KDoc(append-only 이력, PR2 V603 테이블 매핑, id=감사 PK≠외부 멱등키).
**검증**: `./gradlew :backend:modules:search-export-import:test --tests '*JooqWebhookDeliveryRepositoryTest'`

### Task 4. WebhookSigner (HMAC-SHA256)

**메타**.
- agent: `backend-engineer` (보안 서명 — security-engineer 게이트2 검토)
- files: [`backend/modules/search-export-import/src/main/kotlin/com/bts/search/webhook/dispatch/WebhookSigner.kt`, `backend/modules/search-export-import/src/test/kotlin/com/bts/search/webhook/dispatch/WebhookSignerTest.kt`]
- depends-on: []

**RED**: `WebhookSigner.sign(secret, body)` = 알려진 벡터(고정 secret+body)에 대해 `sha256=<hex>` 반환 검증(RFC4231 HMAC-SHA256 테스트 벡터 또는 GitHub 문서 예시). 클래스 부재로 실패.
**GREEN**: `javax.crypto.Mac`(HmacSHA256) — `sign(secret: String, body: ByteArray): String` → `"sha256=" + hex(mac)`. 순수 함수.
**REFACTOR**: hex 인코딩 헬퍼 + KDoc(서명 대상=raw body bytes, secret=평문, 헤더 형식 GitHub 관례).
**검증**: `./gradlew :backend:modules:search-export-import:test --tests '*WebhookSignerTest'`

### Task 5. WebhookCircuitBreaker (in-memory + Clock)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/search-export-import/src/main/kotlin/com/bts/search/webhook/dispatch/WebhookCircuitBreaker.kt`, `backend/modules/search-export-import/src/test/kotlin/com/bts/search/webhook/dispatch/WebhookCircuitBreakerTest.kt`]
- depends-on: []

**RED**: 상태 전이 단위 테스트(Clock 주입) — (a) 연속 5회 실패 → `isOpen(id)=true`, (b) OPEN 후 60초 이내 → open 유지, (c) 60초 경과 → half-open(1회 탐침 허용), (d) 탐침 성공 → CLOSED(카운터 리셋), (e) 탐침 실패 → 재OPEN. 클래스 부재로 실패.
**GREEN**: `Map<UUID, State>`(ConcurrentHashMap). `recordSuccess(id)`/`recordFailure(id)`/`isOpen(id): Boolean`. `Clock` 주입(메모리 authcontroller-revokesession-timebomb — 시각 의존 로직 Clock). 상수 THRESHOLD=5, OPEN_DURATION=60s.
**REFACTOR**: State data class + KDoc(in-memory **단일 인스턴스 전제** — CLAUDE.md 단일 호스트 토폴로지; 다중 인스턴스 스케일 시 카운터가 인스턴스별 독립 → 실질 임계 5×N 명시, 재시작 리셋 수용 — Maxi 확정. C8).
**검증**: `./gradlew :backend:modules:search-export-import:test --tests '*WebhookCircuitBreakerTest'`

### Task 6. 구독 매칭 조회 (OutboundWebhookRepository.findMatching)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/search-export-import/src/main/kotlin/com/bts/search/webhook/application/OutboundWebhookRepository.kt`, `backend/modules/search-export-import/src/main/kotlin/com/bts/search/webhook/persistence/JooqOutboundWebhookRepository.kt`, `backend/modules/search-export-import/src/test/kotlin/com/bts/search/webhook/persistence/JooqOutboundWebhookRepositoryTest.kt`]
- depends-on: []

**RED**: Testcontainers 테스트 — `findMatching(eventType, projectKey)` = (a) eventFilter에 eventType 포함(GIN overlap) + (b) projectKey null(전체) 또는 일치 + (c) enabled=true + (d) deleted_at IS NULL 구독만 반환. positive+negative control(비매칭 이벤트·타프로젝트·비활성·삭제됨 부재 단언 — vacuous 방지, 메모리 fr-tl-02). 메서드 부재로 실패.
**GREEN**: 포트에 `findMatching(eventType: String, projectKey: String): List<OutboundWebhook>` 추가(default 아님 — 필수 구현). Jooq — `event_filter && ARRAY[eventType]` + `(project_key IS NULL OR project_key = ?)` + `enabled AND deleted_at IS NULL`.
**REFACTOR**: KDoc(GIN 인덱스 활용, projectKey null=전체 스코프 의미).
**검증**: `./gradlew :backend:modules:search-export-import:test --tests '*JooqOutboundWebhookRepositoryTest'`
**리스크**: 기존 `JooqOutboundWebhookRepositoryTest` 갱신(메서드 추가). PR2 테스트 회귀 확인.

### Task 7. SearchWebhookDispatcher (SSRF + payload + HMAC + 발송)

**메타**.
- agent: `backend-engineer` (security-engineer 게이트2 검토 — SSRF·HMAC)
- files: [`backend/modules/search-export-import/src/main/kotlin/com/bts/search/webhook/dispatch/SearchWebhookDispatcher.kt`, `backend/modules/search-export-import/src/main/kotlin/com/bts/search/webhook/dispatch/WebhookDispatchResult.kt`, `backend/modules/search-export-import/src/test/kotlin/com/bts/search/webhook/dispatch/SearchWebhookDispatcherTest.kt`]
- depends-on: [4]

**RED**: 단위 테스트(mock RestClient/validator) — (a) SSRF Blocked → Rejected(발송 X), (b) secret 있으면 X-BTS-Signature 헤더 부착, (c) secret 없으면 서명 헤더 부재, (d) 2xx → Sent(code), (e) non-2xx/예외 → Failed. 클래스 부재로 실패.
**GREEN**: `dispatch(url, secret?, eventType, deliveryId, payloadBody): WebhookDispatchResult`. **payloadBody·deliveryId는 호출자(T8)가 §6 화이트리스트 data + 안정 멱등키로 구성해 전달** — T7은 서명·발송만 책임. shared `OutboundUrlValidator`(재검증) + shared `RestClient`(Redirect.NEVER) 재사용. 헤더 X-BTS-Event/X-BTS-Delivery(안정키) + secret 있으면 `WebhookSigner`로 X-BTS-Signature(raw body). `WebhookDispatchResult` sealed(Sent(code)/Rejected(reason)/Failed(reason, code?)). host-only 로그(FR-NT-05 패턴).
**REFACTOR**: 헤더 구성 헬퍼 + KDoc(FR-NT-05 WebhookDispatcher와 차이=HMAC·구독 payload).
**검증**: `./gradlew :backend:modules:search-export-import:test --tests '*SearchWebhookDispatcherTest'`

### Task 8. WebhookDispatchWorker (@Scheduled fanout + 이력 + circuit + pgmq 생명주기)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/search-export-import/src/main/kotlin/com/bts/search/webhook/dispatch/WebhookDispatchWorker.kt`, `backend/modules/search-export-import/src/test/kotlin/com/bts/search/webhook/dispatch/WebhookDispatchWorkerTest.kt`, `backend/modules/search-export-import/src/test/kotlin/com/bts/search/webhook/dispatch/WebhookDispatchWorkerIntegrationTest.kt`]
- depends-on: [3, 5, 6, 7]

**RED**: 통합 테스트 — q_webhook_events에 issue.created 메시지 넣고 워커 폴링 → 매칭 구독 fanout 발송(mock 외부 HTTP) + `webhook_deliveries` 기록 + 메시지 delete 검증. circuit OPEN 구독 스킵. poison → archive. 클래스 부재로 실패.
**GREEN**: `@Component` `@Scheduled`(SchedulingConfiguration 재사용). 폴링(**BATCH_SIZE=1** — fanout VT 초과 방지, C1) → wire JSON 파싱(BC 격리, 손수 파싱) → eventType/projectKey 추출(transitioned는 issueKey에서 파싱, EC1) → **§6 화이트리스트 data + envelope payload 구성** + **안정 deliveryId=UUIDv5(ns, "webhookId:msgId") (B1)** → `findMatching` → 각 구독: circuit isOpen 스킵, 아니면 secret 복호화(`@Qualifier("webhookSecretEncryptor")` decrypt, **복호화 IllegalStateException은 해당 구독만 FAILED 이력·circuit 반영 후 fanout 계속** — 배치 전체 죽이지 않음, 평문/예외 미로그, C7) → dispatch → delivery record + circuit record. 처리 완료 시 delete(부분 실패해도, EC4). read_ct>MAX archive.
**REFACTOR**: 헬퍼 분리(parse/payload-build/fanout/lifecycle) + `@Suppress` 최소화(detektMain type-resolved 엄격, 메모리 module-first-scheduled-worker). KDoc(pgmq 생명주기 P0, at-least-once, 안정 멱등키).
**검증**: `./gradlew :backend:modules:search-export-import:test --tests '*WebhookDispatchWorker*'`
**리스크**: pgmq 생명주기 누락=무한 재전달(메모리 pgmq-consumer-message-lifecycle-p0). fanout VT(BATCH=1, C1). detektMain 엄격. **q_webhook_events는 issue-tracking V034 소유 → search Testcontainers 셋업에서 `SELECT pgmq.create('q_webhook_events')` 수동 생성**(search Flyway는 V600~만 적용, 메모리 bts-cross-bc-test-migration, C5).

### Task 9. 발송 이력 조회 API (GET /webhooks/{id}/deliveries)

**메타**.
- agent: `backend-engineer` (권한 게이트 — security-engineer 검토)
- files: [`backend/modules/search-export-import/src/main/kotlin/com/bts/search/webhook/web/OutboundWebhookController.kt`, `backend/modules/search-export-import/src/main/kotlin/com/bts/search/webhook/web/dto/OutboundWebhookDtos.kt`, `backend/modules/search-export-import/src/main/kotlin/com/bts/search/webhook/application/OutboundWebhookService.kt`, `backend/modules/search-export-import/src/test/kotlin/com/bts/search/webhook/web/OutboundWebhookControllerIntegrationTest.kt`]
- depends-on: [3]

**RED**: 통합 테스트 — `GET /api/v1/webhooks/{id}/deliveries` (a) SYSTEM_ADMIN 200 + 이력 목록(최신순, offset 페이지네이션 DEFAULT20/MAX100), (b) 비-admin 403, (c) 미존재 404(비-admin도 404로 probe 차단 — PR2 관례), (d) admin 게이트를 리소스 조회보다 먼저(메모리 auth-extraction-before-resource-lookup). 엔드포인트 부재로 실패.
**GREEN**: 컨트롤러에 GET 추가(PR2 admin 게이트 재사용) + service `listDeliveries(webhookId, page, size)` + `WebhookDeliveryResponse` DTO. 기존 스코프 `OutboundWebhookExceptionHandler`(assignableTypes) 커버.
**REFACTOR**: DTO 매핑 헬퍼 + KDoc.
**검증**: `./gradlew :backend:modules:search-export-import:test --tests '*OutboundWebhookControllerIntegrationTest'`
**리스크**: 기존 컨트롤러/DTO/서비스 확장 → PR2 통합테스트 회귀 확인.

### Task 10. End-to-end 통합 테스트 (dual-send → 워커 → 발송 → 이력)

**메타**.
- agent: `backend-engineer` (qa-engineer 검토 가능)
- files: [`backend/modules/search-export-import/src/test/kotlin/com/bts/search/webhook/WebhookDispatchEndToEndIntegrationTest.kt`]
- depends-on: [2, 8, 9]

**RED**: end-to-end — 구독 등록 → q_webhook_events에 이벤트 → 워커 발송(mock HTTP 수신 서명 검증) → webhook_deliveries SUCCEEDED → 이력 조회 API로 확인. **★wire-contract(C2)**: 큐 fixture를 손수 JSON 대신 **실제 `IssueEventPublisher`와 동일한 ObjectMapper 설정**(registerKotlinModule+JavaTimeModule)으로 `IssueCreated`/`IssueTransitioned` 직렬화해 주입(발행↔소비 shape drift 방지). **★under-send 가드(C4)**: search `WebhookEventCatalog.PUBLISHABLE` 각 이벤트마다 구독 등록 → 발송 도달 단언(issue-tracking allowlist 값 정합). **★큐 수동생성(C5)**: Testcontainers 셋업 `pgmq.create('q_webhook_events')`. **★멱등키(B1)**: 같은 메시지 재전달(수동 재삽입 아닌 VT) 시 동일 X-BTS-Delivery 검증(가능 범위).
**GREEN**: 통합 테스트 작성(신규 코드 없음, 기존 컴포넌트 조립 검증).
**REFACTOR**: 시나리오 KDoc.
**검증**: `./gradlew :backend:modules:search-export-import:test --tests '*WebhookDispatchEndToEndIntegrationTest'`
**구현 노트 (C2 deviation)**. BC 격리로 search 는 issue-tracking `IssueDomainEvent` 를 import 할 수 없어(모듈 의존성 없음), 발행측(Spring Boot 기본 ObjectMapper)과 **동일한 설정**(registerKotlinModule + JavaTimeModule + 타임스탬프 ISO)으로 **동일 wire shape**(내부 VO `reporterId`/`actorId`={value} 포함)를 직렬화하는 방식으로 C2 를 실현한다(occurredAt ISO 계약·소비측 파싱 호환·C3 내부 VO 누출 차단을 실제 직렬화 경로로 검증). issue-tracking 클래스 직접 직렬화가 아니라는 점만 원안과 다르다(테스트 KDoc 상세).

## Plan 메타

- task 수: 10 (각 TDD 사이클)
- 모듈: issue-tracking(T1-T2) / search-export-import(T3-T10)
- 예상 wave: 4 (W1: T1·T3·T4·T5·T6 / W2: T2·T7 / W3: T8·T9 / W4: T10). 같은 Gradle 모듈은 컴파일 직렬화(메모리 bts-plan-wave-gradle-module-compile).
- TDD 강제: yes (test 커밋이 feat 커밋보다 먼저)
- 추가 검증: ktlint, detekt(--rerun-tasks, search detektMain 엄격), ArchUnit BC 격리, 전 모듈 test
- 보안 게이트: T2(BC격리 allowlist)·T4(HMAC)·T7(SSRF+HMAC)·T9(admin 게이트) security-engineer 게이트2 검토

## 리뷰 결과

### plan-eng-review (독립 eng 리뷰, 2026-07-01)

실제 코드 대조 독립 리뷰(general-purpose agent, 편향 배제). 재사용·scope·TDD 분해·보안 패턴 견고 판정. **BLOCKER 1 + CONCERN 9 발견 → 전건 plan/spec 반영**.

**BLOCKER (해소)**.
- **B1 멱등키 계약 자기모순** — spec §6 "deliveryId=webhook_deliveries.id, 수신자 멱등키"인데 VT 재전달 시 새 delivery 행→새 id→수신자 dedup 불가(외부 공개 API 계약). → **해소**: deliveryId를 pgmq `msg_id` 기반 결정적 안정키(UUIDv5)로, `webhook_deliveries.id`(내부 per-attempt PK)와 분리(spec §6, T3/T7/T8).

**CONCERN (반영)**.
- C1 fanout VT 사이징(단건 전제 부족) → BATCH_SIZE=1 + per-call timeout (spec §8, T8).
- C2 wire-contract seam 미검증 → T10 fixture를 실 `IssueEventPublisher` ObjectMapper 직렬화(손수 JSON 금지).
- C3 payload data 내부 VO(`ActorId`={value}) 누출 → spec §6 이벤트별 화이트리스트 스칼라, actor/UUID 미노출.
- C4 dual-send allowlist silent under-send → T2 `IssueDomainEvent` exhaustive `when`(컴파일 강제) + T10 under-send 단언.
- C5 큐 소유 BC(issue-tracking) → T8/T10 Testcontainers 수동 `pgmq.create`.
- C6 트랜잭션 하드 결합 → T2 리스크에 하네스 2경로(Flyway/init_codegen) 큐 포함 명시.
- C7 secret 복호화 위치/실패 → T8 per-구독 catch·fanout 계속·미로그.
- C8 circuit 단일 인스턴스 전제 → T5 KDoc 명시(다중 시 실질 5×N).
- C9 status enum PENDING 문서 drift(경미) → 발송 후 동기 기록이라 SUCCEEDED/FAILED만, V603 주석은 초기 표기(무해).

**scope 평가**: 적절(과/과소설계 없음). 재사용 주장 전건 실증(shared validator/RestClient, webhookSecretEncryptor, SchedulingConfiguration, pgmq 패턴, PR2 도메인). 8 신규 클래스 단일책임. HMAC=JDK Mac·circuit=자체 구현(resilience4j 과설계) 판단 옳음. 두 dispatcher SSRF 골격 중복은 ADR 수용 범위(3번째 등장 시 shared 추출).

**BLOCKER 없음** (B1 해소). 게이트1 진입 가능.

### plan-devex-review (2026-07-01, 경량)

외부 개발자(webhook 수신자) 경험 관점. eng 리뷰 B1(안정 멱등키)·C3(payload data 계약 명확화)가 devex 핵심을 이미 커버. 추가 확인.
- 이력 조회 API 페이지네이션 = offset raw List(PR2 webhook CRUD 관례와 일관, FR-API cursor 봉투 아님 — 같은 BC 내 일관성 우선, FR-API-02 메모리 선례).
- 서명 검증 = `X-BTS-Signature: sha256=<hex>`(GitHub 관례, 수신자 친숙), HMAC 대상=raw body 명시(spec §6).
- 에러 = RFC7807 스코프 예외핸들러(PR2 `OutboundWebhookExceptionHandler` 재사용).
- devex 추가 BLOCKER/CONCERN 없음.
