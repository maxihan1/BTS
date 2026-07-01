# FR-API-03 — Webhook (외부 시스템 통지)

> slug: fr-api-03-outbound-webhook
> type: feature
> agent: backend-engineer (D2/D4 HMAC·SSRF security-engineer 공동, D3 db-engineer, D6 frontend-engineer, D7 qa-engineer)
> 생성: 2026-07-01

## Brief

FR-API-03 — 구독형 아웃바운드 Webhook(외부 시스템 통지). search-export-import BC(§5.3).
선행. §5.1(FR-API-01 REST API 표준), identity-access §2.10(감사).

- D1. 도메인 — OutboundWebhook
- D2. 명세 — HMAC-SHA256 서명 + 재시도 + circuit breaker
- D3. 데이터 모델 — outbound_webhooks(url, secret_encrypted, event_filter) + webhook_deliveries(status, response_code)
- D4. 백엔드 — pgmq event → HTTP 발송 + 재시도
- D5. 백엔드 테스트 — 재시도 + circuit breaker
- D6. 프론트 UI — Webhook 관리 페이지 + 발송 이력
- D7. E2E

**핵심 쟁점 — FR-NT-05 중복**. notification BC가 이미 webhook 발송 인프라 보유
(WebhookDispatcher/WebhookUrlValidator/WebhookDispatchWorker, ADR 2026-06-14-fr-nt-05-webhook-dispatch-ssrf).
단 FR-NT-05는 워크플로우 전이 전용(post-action). FR-API-03는 구독형 범용(이벤트필터+HMAC+이력+circuit breaker).
→ (1) 인프라 재사용 범위, (2) BC 경계(search-export-import FR vs notification webhook 인프라)를 bts-domain서 확정.

## 도메인 정리

- **BC**: search-export-import (FR 소속 BC 존중 — Maxi 옵션 A 확정)
- **신규 엔티티/도메인**: `OutboundWebhook`(구독: url + secret_encrypted + event_filter), `WebhookDelivery`(발송 이력: status + response_code), circuit breaker 상태.
- **재사용(shared-kernel 추출)**: notification BC의 `WebhookUrlValidator`(SSRF) + HTTP 클라이언트 설정 → `com.bts.shared.http`로 추출, FR-NT-05와 공유. notification 코드 1회 리팩터링(문서화된 BC 경계 교차).
- **FR-NT-05 관계**: 전이 webhook(post-action)은 그대로 유지, 흡수 안 함. 범용 구독 webhook과 공존.
- **신규 용어 후보(glossary 승인 대기)**: "아웃바운드 Webhook(구독형)", "Webhook Delivery(발송 이력)", "Circuit Breaker(연속 실패 차단)", "HMAC 서명". → Maxi 승인 후 glossary 추가.
- **선행**: FR-API-01/02(REST API 표준 — 페이지네이션/에러봉투) 따름. identity-access §2.10(감사).
- **기존 결정 충돌**: 없음. FR-NT-05 ADR이 예견한 "재사용 결정" 실현.
- **관련 ADR**: [docs/decisions/2026-07-01-fr-api-03-outbound-webhook-bc-and-reuse.md](../decisions/2026-07-01-fr-api-03-outbound-webhook-bc-and-reuse.md) (생성됨), [2026-06-14-fr-nt-05-webhook-dispatch-ssrf.md](../decisions/2026-06-14-fr-nt-05-webhook-dispatch-ssrf.md)
- **마이그레이션 V번호**: search-export-import 최신 V602 → 신규 V603 후보(머지 직전 재확인).

## 스펙

전체 스펙. [docs/specs/2026-07-01-fr-api-03-outbound-webhook.md](../specs/2026-07-01-fr-api-03-outbound-webhook.md)

**전체 FR = 4-PR 분할(Maxi 확정). 본 run = PR1(shared 추출)만.**
- PR1(본 run): SSRF URL 검증기 + HTTP 클라이언트 설정 → shared-kernel `com.bts.shared.http` 추출, FR-NT-05 교체. **순수 리팩터·동작불변**.
- PR2: OutboundWebhook 구독 CRUD + secret 암호화 + V603 테이블.
- PR3: issue-tracking dual-send(q_webhook_events) + fanout/dispatch + HMAC-SHA256 + circuit breaker + 발송이력.
- PR4: 관리 UI + 발송이력 + E2E.

이벤트 소싱(Maxi Q1=A). issue-tracking IssueEventPublisher가 q_webhook_events로도 dual-send → search 워커 소비. (PR3)

핵심 사실(Explore 조사). q_issue_events는 NotificationWorker 독점소비(경합불가). SecretEncryptor(AES-256-GCM, identity-access) 재사용. REST 봉투=AqlSearchPageResponse. validator BC의존 0(추출 안전). shared-kernel은 spring-context 보유하나 **spring-web 없음**(HTTP config 이관 시 추가 필요).

## Brainstorming Check

✅ 통과 (직접 기술 스펙 — 정의된 순수 리팩터). Sanity gap 5건 스펙 §5 선반영. (1)설정키 이관 동작보존 (2)RestClient 빈 주입 (3)spring-web shared 추가 (4)테스트 이전 가짜그린 (5)ArchUnit BC→shared 정방향 확인.

## Plan

> PR1 = 순수 리팩터. TDD 규율은 "이동한 테스트가 새 위치에서 먼저 실패(class 없음=RED) → 클래스 이동(GREEN) → 낡은 참조 정리(REFACTOR)". 기존 회귀 테스트가 안전망.
> 두 task는 `WebhookDispatcherTest`·통합테스트 파일을 공유 → bts-impl이 자동 직렬화(단일 wave 불가). Task 2는 depends-on [1].
> **빈 모호성 주의**. `WebhookDispatcher`가 `RestClient`를 타입 주입 → 같은 타입 빈 2개 공존 불가 → Task 2(HTTP config)는 old 삭제+new 추가를 **원자적**으로.

### Task 1. SSRF URL 검증기를 shared-kernel로 이동 (OutboundUrlValidator)

**메타**.
- agent: `security-engineer` (SSRF 보안 검증기 — 단일 출처화가 추출의 핵심 목적)
- files: [`backend/modules/shared-kernel/src/main/kotlin/com/bts/shared/http/OutboundUrlValidator.kt`, `backend/modules/shared-kernel/src/main/kotlin/com/bts/shared/http/UrlCheck.kt`, `backend/modules/shared-kernel/src/test/kotlin/com/bts/shared/http/OutboundUrlValidatorTest.kt`, `backend/modules/notification/src/main/kotlin/com/bts/notification/webhook/WebhookDispatcher.kt`, `backend/modules/notification/src/test/kotlin/com/bts/notification/webhook/WebhookDispatcherTest.kt`, `backend/modules/notification/src/test/kotlin/com/bts/notification/webhook/WebhookDispatchEndToEndIntegrationTest.kt`, `backend/modules/notification/src/main/kotlin/com/bts/notification/webhook/WebhookUrlValidator.kt`(삭제), `backend/modules/notification/src/main/kotlin/com/bts/notification/webhook/UrlCheck.kt`(삭제), `backend/modules/notification/src/test/kotlin/com/bts/notification/webhook/WebhookUrlValidatorTest.kt`(삭제/이전)]
- depends-on: []

**RED**.
- 파일: `.../shared/http/OutboundUrlValidatorTest.kt` — 기존 `WebhookUrlValidatorTest`의 SSRF 케이스(loopback/private 10·172·192/ULA fc00::/7/IPv4-mapped ::ffff:/malformed/비-http 스킴)를 그대로 이전, 대상만 `OutboundUrlValidator`.
- 실패(예상): `OutboundUrlValidator`/`UrlCheck`가 `com.bts.shared.http`에 없음 → 컴파일 실패.

**GREEN**.
- `com.bts.shared.http.UrlCheck`(sealed: Allowed/Blocked/Malformed) + `OutboundUrlValidator`(@Component, 기존 `check(url): UrlCheck` 로직 그대로 — `isInternal`/`extractMappedIpv4` 포함) 신설. 로직 diff 0.

**REFACTOR**.
- notification `WebhookDispatcher`의 필드 타입 `WebhookUrlValidator`→`OutboundUrlValidator`(import 교체). `WebhookDispatcherTest`의 `mockk<WebhookUrlValidator>()`→`mockk<OutboundUrlValidator>()`, 통합테스트 import 교체.
- notification의 낡은 `WebhookUrlValidator.kt`·`UrlCheck.kt`·`WebhookUrlValidatorTest.kt` 삭제(회귀 테스트는 shared-kernel으로 이전됨 — 정확히 한 곳 실행).
- **[리뷰 CONCERN-1] 빈 등록**. 통합테스트 `NotificationTestBootApplication`은 `scanBasePackages=["com.bts.notification"]`이라 `com.bts.shared.http`를 스캔 안 함 → 이동한 `OutboundUrlValidator`(@Component)를 `WebhookDispatchEndToEndIntegrationTest`의 `@SpringBootTest(classes=[...])` 목록에 명시 추가(또는 `WebhookE2EConfig`에 `@Import`). 누락 시 부팅 NoSuchBeanDefinition.
- 검증: `grep -rn "notification.webhook.WebhookUrlValidator\|notification.webhook.UrlCheck" backend/` 결과 0.

**검증**. `./gradlew :backend:modules:shared-kernel:test --tests '*OutboundUrlValidatorTest*'` + `./gradlew :backend:modules:notification:test --tests '*WebhookDispatcherTest*' --tests '*WebhookDispatchEndToEndIntegrationTest*'` + `:backend:modules:shared-kernel:test --tests '*SharedKernelBoundaryArchTest*'`.

### Task 2. 아웃바운드 HTTP 클라이언트 설정을 shared-kernel로 이동 (OutboundHttpClientConfig)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/shared-kernel/build.gradle.kts`, `backend/modules/shared-kernel/src/main/kotlin/com/bts/shared/http/OutboundHttpClientConfig.kt`, `backend/modules/shared-kernel/src/test/kotlin/com/bts/shared/http/OutboundHttpClientConfigTest.kt`, `backend/modules/notification/src/test/kotlin/com/bts/notification/webhook/WebhookDispatcherTest.kt`, `backend/modules/notification/src/test/kotlin/com/bts/notification/webhook/WebhookDispatchEndToEndIntegrationTest.kt`, `backend/modules/notification/src/main/kotlin/com/bts/notification/config/WebhookHttpClientConfig.kt`(삭제)]
- depends-on: [1]   # WebhookDispatcherTest·통합테스트 파일 공유 → 직렬

**RED**.
- 파일: `.../shared/http/OutboundHttpClientConfigTest.kt` — RestClient 빈이 `HttpClient.Redirect.NEVER` + 기본 타임아웃(connect 3000/read 5000)로 구성되는지 단언(팩토리 `outboundHttpRestClient()` 호출로 non-null RestClient 반환·redirect 정책 검증).
- 실패(예상): `com.bts.shared.http.OutboundHttpClientConfig` 없음 → 컴파일 실패.

**GREEN**.
- shared-kernel `build.gradle.kts` dependencies에 `implementation("org.springframework:spring-web")` 추가(현재 spring-context/spring-tx만). detekt kotlin-version 강등 우회 설정 이미 존재 — 재검증.
- `com.bts.shared.http.OutboundHttpClientConfig`(@Configuration(proxyBeanMethods=false)) 신설. 기존 `WebhookHttpClientConfig` 로직 그대로 이전. 빈 이름 `outboundHttpRestClient`, 프로퍼티 키 `bts.outbound-http.connect-timeout-ms:3000`/`bts.outbound-http.read-timeout-ms:5000`(기본값 동일=동작보존; application.yml 오버라이드 grep 결과 0건 확인됨). `DEFAULT_CONNECT_TIMEOUT_MS`/`DEFAULT_READ_TIMEOUT_MS` 상수 보존.

**REFACTOR**.
- notification `WebhookDispatcherTest`의 `WebhookHttpClientConfig().webhookRestClient()`→`OutboundHttpClientConfig().outboundHttpRestClient()`, 통합테스트 import `com.bts.notification.config.WebhookHttpClientConfig`→`com.bts.shared.http.OutboundHttpClientConfig`.
- notification 낡은 `WebhookHttpClientConfig.kt` 삭제(원자적 — RestClient 빈은 항상 한 개).
- **[리뷰 CONCERN-1] 빈 등록**. `OutboundHttpClientConfig`(@Configuration)도 `com.bts.shared.http`라 notification 스캔 밖 → `WebhookDispatchEndToEndIntegrationTest`의 `classes=[...]`에 `OutboundHttpClientConfig::class` 추가(또는 `@Import`). WebhookE2EConfig가 기존 WebhookHttpClientConfig를 명시 참조했다면 그 참조도 교체.
- `WebhookDispatcher`는 `RestClient`를 타입 주입하므로 코드 변경 불필요(빈 출처만 shared로 이동). 통합테스트 부팅으로 빈 단일성·주입 검증.

**검증**. `./gradlew :backend:modules:shared-kernel:test --tests '*OutboundHttpClientConfigTest*'` + `:backend:modules:notification:test`(webhook·worker 전체) + `:backend:modules:shared-kernel:ktlintCheck detekt`(spring-web 추가 후) + `grep -rn "config.WebhookHttpClientConfig\|bts.notification.webhook" backend/` 0건.

## Plan 메타

- task 수: 2 (각 원자적 이동 리팩터, TDD 이전-먼저 규율)
- 예상 시간: 직렬 약 12분 (파일 공유로 단일 wave 불가, Task 2 depends-on [1])
- TDD 강제: yes (이전한 회귀 테스트가 RED, 클래스 이동이 GREEN)
- 병렬 dispatch: 불가(2 task 직렬) — bts-impl이 files 교집합으로 자동 직렬화
- 추가 검증: 전 모듈 clean 빌드(동작불변 확인), detekt/ktlint(spring-web 추가 영향), SSRF 회귀 정확히 한 곳
- BC 교차: notification 코드 수정 1회(shared 추출 — ADR §D2 문서화된 예외)

## 리뷰 결과

### plan-eng-review (2026-07-01) — feature/task<3 → eng 집중 리뷰

**Step 0 스코프 챌린지**. ✅ 통과. 6 파일 내외·신규 서비스 0(기존 클래스 이동)·8파일 스멜 임계 미달. 병렬 인프라 신설 없음(재사용이 목적). 4-PR 분할의 PR1로 strangler-fig식 점진 접근 — 스코프 크립 없음. 완제품 기준(SSRF 회귀 보존, 동작불변).

**아키텍처**. ✅ shared-kernel `com.bts.shared.http` 추출 타당(spring-context 이미 보유, IssueSecurityDirectory 선례). spring-web 추가는 ArchUnit(BC 역참조만 금지) 위반 아님 — 검증됨. **빈 타입주입 모호성**(RestClient 2개 공존 불가)을 원자적 config 이동으로 회피 — 계획이 최대 리스크를 정확히 짚음.

**테스트**. ✅ `WebhookDispatcherTest`는 순수 단위(mockk+직접 인스턴스화) — 스캔 무관, import만 교체. SSRF 회귀는 shared-kernel 단일 위치로 이전(정확히 한 곳). `WebhookDispatchEndToEndIntegrationTest`가 실 컨텍스트 부팅 = 빈 모호성/등록 실패 안전망.

**CONCERN-1 (등록/스캔, 반영 완료)**. `NotificationTestBootApplication`이 `scanBasePackages=["com.bts.notification"]`로 한정 → @Component/@Configuration을 `com.bts.shared.http`로 옮기면 스캔 밖이라 통합테스트 부팅 시 NoSuchBeanDefinition. **해결책**: 통합테스트 `@SpringBootTest(classes=[...])`에 `OutboundUrlValidator::class`+`OutboundHttpClientConfig::class` 명시 추가(또는 `@Import`). Task 1/2 REFACTOR에 반영함. 안전망(통합테스트 부팅)이 미이행 시 즉시 적발.

**CONCERN-2 (설정키 이관) — 해소**. `bts.notification.webhook.*`→`bts.outbound-http.*` 일반화. 전 저장소 grep(yml/properties/env/docker/infra) 오버라이드 **0건** 확인 → 기본값(3000/5000) 그대로라 동작 중립.

**forward note(PR3 범위)**. 프로덕션 배포 조립 부재(현 표준=test-assembled). 실 assembly 도입 시 `com.bts.shared.http` 스캔 포함 필요. search-export-import(PR2+)도 자체 컨텍스트에 shared 빈 등록 필요.

**BLOCKER: 없음**. type=feature·순수 리팩터. auth/migration 아님. → 게이트 1 진입 가능.
