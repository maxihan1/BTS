# FR-API-03 PR2 — 구독형 아웃바운드 Webhook 구독 관리 계층

> slug: fr-api-03-pr2-webhook-subscription-crud
> type: feature
> agent: backend-engineer (마이그레이션=db-engineer, secret 암호화=security-engineer는 plan task meta로 지정)
> 생성: 2026-07-01

## Brief

FR-API-03(외부 시스템 통지용 구독형 아웃바운드 Webhook, search-export-import BC)의 **PR2 — 구독 관리 계층**.

범위 (이번 PR):
- `OutboundWebhook` 구독 도메인 애그리거트
- 구독 CRUD REST API (생성/조회/수정/삭제)
- secret 암호화: identity-access `SecretEncryptor`(AES-256-GCM) 재사용
- V603 마이그레이션: `outbound_webhooks(url, secret_encrypted, event_filter)` + `webhook_deliveries(status, response_code)` 테이블

범위 제외 (PR3 이후):
- 실제 이벤트 발송 (issue-tracking dual-send → q_webhook_events, fanout/dispatch 워커, HMAC-SHA256 서명, circuit breaker, 발송 이력 기록)
- 관리 UI + E2E (PR4)

선행:
- PR1(shared-kernel `com.bts.shared.http` 인프라 추출: OutboundUrlValidator + OutboundHttpClientConfig)은 main 머지 완료 (#211, squash f339ff06).
- 관련 ADR: docs/decisions/2026-07-01-fr-api-03-outbound-webhook-bc-and-reuse.md
- 참고: FR-NT-05 전이 webhook(이미 완료) — 상위집합 관계.

## 도메인 정리

- **BC**: search-export-import (패키지 `com.bts.search.webhook` 후보). 논리·물리 소속 일치(PR1 ADR D1).
- **영향/신규 엔티티**:
  - `OutboundWebhook` (신규 애그리거트) — 구독. url + secret(암호화 저장) + event_filter + enabled 상태.
  - `WebhookDelivery` (신규, 이번 PR은 **테이블만** 생성) — 발송 이력. 실제 발송/기록은 PR3.
- **새 용어 (glossary 등록 제안, Maxi 게이트1 확인)**:
  - **아웃바운드 웹훅 구독** (OutboundWebhook) — 외부 시스템이 URL+secret+event_filter를 등록해 두면, 매칭 이벤트 발생 시 BTS가 HMAC 서명과 함께 HTTP로 통지하는 구독 단위. FR-NT-05 전이 webhook(post-action, 흡수 안 함)과 별개 경로로 공존.
  - **이벤트 필터** (event_filter) — 구독이 관심 두는 이벤트 타입 집합. 매칭 판정에 사용(발송 로직은 PR3).
  - **발송 이력** (WebhookDelivery) — 발송 시도/결과(status, response_code) 추적 단위. 관리 UI(PR4)에 노출.
- **기존 결정 충돌**: 없음. FR-NT-05 전이 webhook은 유지·공존(PR1 ADR D3).
- **★도메인 결정 (Maxi 게이트, 2026-07-01) — secret 암호화 재사용 = shared 추출(옵션 A / PR1 대칭)**:
  - `SecretEncryptor` 클래스(현재 identity-access `com.atlas.bts.identity.config`, AES-256-GCM·범용 생성자)를 shared-kernel `com.bts.shared.crypto`로 이동.
  - identity-access `OidcEncryptionConfig`는 shared 클래스 import로 변경, 기존 `BTS_OIDC_ENCRYPTION_*` 키 유지(OIDC 암호화 경로 동작 불변).
  - search BC는 자체 키(`BTS_WEBHOOK_ENCRYPTION_*`)로 별도 빈 생성. **단일 구현 + BC별 키**(webhook·OIDC 키 분리 = 키 위생 향상).
  - **BC 경계 1회 교차(문서화된 예외)**: 이 PR이 identity-access를 건드리는 것은 공유 크립토 추출이라는 본질상 불가피(PR1 http 추출과 동류). plan §리스크 명시.
- **관련 ADR**:
  - [2026-07-01-fr-api-03-outbound-webhook-bc-and-reuse.md](../decisions/2026-07-01-fr-api-03-outbound-webhook-bc-and-reuse.md) (PR1 — BC 배치 + 재사용 방침, 크립토 키관리는 spec 위임)
  - [2026-07-01-fr-api-03-secret-encryptor-shared-extraction.md](../decisions/2026-07-01-fr-api-03-secret-encryptor-shared-extraction.md) (본 PR — SecretEncryptor shared 추출 + BC별 키, 생성됨)

## 스펙

전체 스펙. [docs/specs/2026-07-01-fr-api-03-pr2-webhook-subscription-crud.md](../specs/2026-07-01-fr-api-03-pr2-webhook-subscription-crud.md)

핵심 결정 요약.
- **권한 = SYSTEM_ADMIN 전역** (Maxi). 전 엔드포인트 `SystemPermissionResolver.isSystemAdmin` 게이트, 실패 403. project_key는 선택적 필터(null=전체).
- **secret 암호화 = shared 추출** (Maxi). SecretEncryptor를 shared-kernel `com.bts.shared.crypto`로 이동, search는 자체 키(`BTS_WEBHOOK_ENCRYPTION_*`). 원문 응답/로그 미노출, `hasSecret`만.
- **event_filter = NotificationEventType.wireValue 문자열 계약** 재사용. publishable allowlist 로컬 상수(초기 `issue.created`/`issue.transitioned`), BC 격리(enum import 아님).
- **URL SSRF = OutboundUrlValidator(shared) 재사용**. 목록은 형제 SavedFilter 관례(raw List + offset paging).
- **V603** = outbound_webhooks(소프트삭제·event_filter TEXT[] GIN) + webhook_deliveries(테이블만, PR3 발송). init_codegen 미러.

핵심 시나리오 3줄.
- SYSTEM_ADMIN이 url+eventFilter로 구독 생성 → SSRF 검증·secret 암호화 → 201(secret 미노출).
- 목록/단건/수정(OCC)/소프트삭제 CRUD, 전 경로 admin 게이트(비admin 403, probe 차단).
- 발송·서명·이력기록은 PR3(이번 PR은 dispatch 0, webhook_deliveries는 테이블만).

## 리스크 (impl 주의)

- **★cross-BC 포트 test-boot 부팅**. `SystemPermissionResolver`(impl=identity-access)·`OutboundUrlValidator`(shared.http)가 search 통합테스트 부팅에 필요. BC 격리 test-boot는 타 BC 미포함 → **stub 빈(fail-closed 기본, crossbc-resolver-nullable-fail-open)** + test-boot 스캔에 `com.bts.shared.http` 중앙 추가(shared-kernel-component-extraction-scan-regression). 검증은 타깃 아닌 **search 전체 스위트**.
- **★SecretEncryptor shared 이동 = identity-access BC 1회 교차**(문서화된 예외, ADR D3). OidcEncryptionConfig·DbClientRegistrationRepository 등 참조 import 전수 변경. 검증은 **identity-access 전체 스위트**(OIDC 암호화 회귀 0, ktlint-detekt-linelength import 라인시프트 주의).
- **jOOQ codegen**. V603 신규 테이블은 init_codegen.sql 미러 필수(안 하면 코드생성 누락). event_filter TEXT[] 코드생성 타입 확인.
- **secret 키 미설정 부팅**. search SecretEncryptor 빈은 lazy=부팅 안전. secret 포함 테스트만 `BTS_WEBHOOK_ENCRYPTION_*` test property 필요.

## Brainstorming Check

✅ 통과 (인라인 sanity check, Maxi 결정 필요 gap 0 — 응답봉투·cross-BC부팅·projectKey검증·secret clear·deliveries스키마 6건 모두 선례/표준으로 해소, 상세는 spec §Brainstorming Check).

## Plan

경로 접두사 `bm/` = `backend/modules/`, `s-e-i` = `search-export-import`.

### Task 1. SecretEncryptor를 shared-kernel으로 추출

**메타**.
- agent: `security-engineer`
- files: [`bm/shared-kernel/build.gradle.kts`, `bm/shared-kernel/src/main/kotlin/com/bts/shared/crypto/SecretEncryptor.kt`, `bm/shared-kernel/src/test/kotlin/com/bts/shared/crypto/SecretEncryptorTest.kt`, `bm/identity-access/src/main/kotlin/com/atlas/bts/identity/config/OidcEncryptionConfig.kt`, `bm/identity-access/src/main/kotlin/com/atlas/bts/identity/provider/oidc/DbClientRegistrationRepository.kt`, `bm/identity-access/src/main/kotlin/com/atlas/bts/identity/provider/oidc/OidcProviderConfig.kt`, `bm/identity-access/src/test/kotlin/com/atlas/bts/identity/config/SecretEncryptorTest.kt`, `bm/identity-access/src/test/kotlin/com/atlas/bts/identity/integration/OidcAuthFlowIntegrationTest.kt`, `bm/identity-access/src/test/kotlin/com/atlas/bts/identity/provider/oidc/DbClientRegistrationRepositoryTest.kt`]
- depends-on: []

**★impl BLOCKED 해소(2026-07-01)**. SecretEncryptor는 `org.springframework.security.crypto`(Encryptors.stronger=AES-256-GCM)에 의존. shared-kernel `build.gradle.kts`에 `implementation("org.springframework.security:spring-security-crypto")`(Spring BOM 관리, 버전 무지정) 추가 필요 — PR1이 http 추출 시 shared-kernel에 spring-web 추가한 것과 동형(게이트1 승인 범위, 신규 외부 라이브러리 아님·identity-access 기 사용). files에 build.gradle.kts 추가.

**★리뷰 B1 반영**. SecretEncryptor를 옮기면 아래 참조처가 import로 깨진다 — files에 전수 포함(plan-files-constructor-injection-existing-tests 재발 방지):
  - main: `OidcEncryptionConfig`(빈 생성), `DbClientRegistrationRepository`(생성자 주입), `OidcProviderConfig`(KDoc 링크 `[...config.SecretEncryptor]` — 이동 후 dangling → 갱신)
  - test: `OidcAuthFlowIntegrationTest`(@Autowired), `DbClientRegistrationRepositoryTest`(직접 `SecretEncryptor(...)` 생성), 옛 `SecretEncryptorTest`(이동)

**RED**. shared-kernel에 `SecretEncryptorTest`(round-trip 암복호화 + 키 미설정 시 encrypt 예외 + 같은 평문 다른 ciphertext) 신규 배치 → `com.bts.shared.crypto.SecretEncryptor` 부재로 컴파일 실패.
**GREEN**. `SecretEncryptor.kt`를 shared-kernel `com.bts.shared.crypto`로 이동(암호화 로직 변경 0, 패키지만 변경). **★리뷰 C1 반영**: KDoc의 OIDC 고유 문구를 "외부 비밀값 범용"으로 일반화하고, `requireConfigured()` 예외 메시지도 OIDC 전용(`"...Set BTS_OIDC_ENCRYPTION_KEY/SALT"`)에서 BC 중립(`"encryption key not configured"` substring 유지, env var 이름 문구 제거)으로 일반화(운영 오도 방지, 기존 substring 단언 test-safe).
**REFACTOR**. identity-access 참조처 import를 `com.bts.shared.crypto.SecretEncryptor`로 교체(main 2 + test 2). `OidcProviderConfig` KDoc 링크 갱신. 옛 identity-access `SecretEncryptorTest` 삭제(shared로 이동). grep `SecretEncryptor`로 잔여 참조 0 확인.
**검증**. `./gradlew :backend:modules:shared-kernel:test --rerun-tasks` + **identity-access 전체** `./gradlew :backend:modules:identity-access:test --rerun-tasks`(OIDC 암호화 회귀 0). ktlintCheck/detekt 두 모듈. `grep -rn "config.SecretEncryptor" bm/identity-access` = 0.

### Task 2. V603 마이그레이션 + init_codegen 미러

**메타**.
- agent: `db-engineer`
- files: [`bm/s-e-i/src/main/resources/db/migration/search-export-import/V603__outbound_webhooks.sql`, `bm/s-e-i/src/main/resources/db/codegen/init_codegen.sql`, `bm/s-e-i/src/test/kotlin/com/bts/search/webhook/OutboundWebhookSchemaMigrationTest.kt`]
- depends-on: []

**RED**. `OutboundWebhookSchemaMigrationTest`(Testcontainers) — `outbound_webhooks`·`webhook_deliveries` 테이블/핵심 컬럼(event_filter TEXT[]·secret_encrypted·deleted_at·version / status·response_code)·GIN 인덱스 존재 단언 → 테이블 부재로 실패.
**GREEN**. spec §데이터 모델의 V603 DDL 작성(두 테이블 + 인덱스). 머지 직전 V번호 재확인(migration-vnumber-concurrent-branch-collision).
**REFACTOR**. init_codegen.sql에 동일 DDL 미러(jooq-init-codegen-mirror). 주석 L1(한글).
**검증**. `./gradlew :backend:modules:s-e-i:test --tests *OutboundWebhookSchemaMigrationTest` + jOOQ 코드생성 성공 확인.

### Task 3. OutboundWebhook 도메인 + 발행가능 이벤트 allowlist

**메타**.
- agent: `backend-engineer`
- files: [`bm/s-e-i/src/main/kotlin/com/bts/search/webhook/domain/OutboundWebhook.kt`, `bm/s-e-i/src/main/kotlin/com/bts/search/webhook/domain/WebhookEventCatalog.kt`, `bm/s-e-i/src/test/kotlin/com/bts/search/webhook/domain/OutboundWebhookTest.kt`]
- depends-on: []

**RED**. `OutboundWebhookTest` — 팩토리 불변식(name 공백/≤100·url 비어있지않음·eventFilter 비어있지않음·각 항목이 allowlist 소속·미지 이벤트 reject). `WebhookEventCatalog.PUBLISHABLE`(초기 `issue.created`/`issue.transitioned` wireValue 문자열 상수) → 클래스 부재로 실패.
**GREEN**. `OutboundWebhook` data class(id?·name·url·secret?(원문 아님 표식)·eventFilter·projectKey?·enabled·version) + `create`/`applyUpdate` 팩토리 + `WebhookEventCatalog` 상수.
**REFACTOR**. 상수/에러메시지 정리 + KDoc.
**검증**. `./gradlew :backend:modules:s-e-i:test --tests *OutboundWebhookTest`.

### Task 4. search 전용 SecretEncryptor 빈 config

**메타**.
- agent: `backend-engineer`
- files: [`bm/s-e-i/src/main/kotlin/com/bts/search/webhook/config/WebhookEncryptionConfig.kt`, `bm/s-e-i/src/test/kotlin/com/bts/search/webhook/config/WebhookEncryptionConfigTest.kt`]
- depends-on: [1]

**RED**. `WebhookEncryptionConfigTest` — `BTS_WEBHOOK_ENCRYPTION_KEY/SALT`로 shared `SecretEncryptor` 빈 생성 + round-trip. 빈 부재로 실패.
**GREEN**. `@Configuration`이 `@Value("\${bts.webhook-encryption.key:}")`/`salt`로 shared `SecretEncryptor` 빈 구성(키 부재여도 부팅 안전=lazy). 부팅 안전성 KDoc.
**REFACTOR**. 설정키/문서.
**검증**. `./gradlew :backend:modules:s-e-i:test --tests *WebhookEncryptionConfigTest`.

### Task 5. OutboundWebhookRepository (jOOQ 영속)

**메타**.
- agent: `backend-engineer`
- files: [`bm/s-e-i/src/main/kotlin/com/bts/search/webhook/application/OutboundWebhookRepository.kt`, `bm/s-e-i/src/main/kotlin/com/bts/search/webhook/persistence/JooqOutboundWebhookRepository.kt`, `bm/s-e-i/src/test/kotlin/com/bts/search/webhook/persistence/JooqOutboundWebhookRepositoryTest.kt`]
- depends-on: [2, 3]

**RED**. `JooqOutboundWebhookRepositoryTest`(Testcontainers) — save/findById/listAll(page,size, deleted_at 필터)/update(OCC version 증가·충돌 시 0행)/softDelete/멱등. 미구현으로 실패.
**GREEN**. repository interface(application) + jOOQ impl(persistence). event_filter TEXT[] 매핑·소프트삭제 필터·OCC 조건부 UPDATE(`WHERE id=? AND version=?`, RETURNING).
**REFACTOR**. SQL 상수/매퍼 추출.
**검증**. `./gradlew :backend:modules:s-e-i:test --tests *JooqOutboundWebhookRepositoryTest`.

### Task 6. OutboundWebhookService + 예외 (SYSTEM_ADMIN 게이트·SSRF·암호화)

**메타**.
- agent: `security-engineer`
- files: [`bm/s-e-i/src/main/kotlin/com/bts/search/webhook/application/OutboundWebhookService.kt`, `bm/s-e-i/src/main/kotlin/com/bts/search/webhook/application/OutboundWebhookExceptions.kt`, `bm/s-e-i/src/test/kotlin/com/bts/search/webhook/application/OutboundWebhookServiceTest.kt`]
- depends-on: [3, 4, 5]

**RED**. `OutboundWebhookServiceTest`(mockk: SystemPermissionResolver·OutboundUrlValidator·SecretEncryptor·Repository) — 비admin→ForbiddenException·SSRF Blocked/Malformed→ValidationException·secret 제공 시 encrypt 호출·secret 미노출·update secret 3-state(생략=유지)·OCC 충돌→ConflictException·softDelete. mock default는 fail-closed(isSystemAdmin 미설정=false).
**GREEN**. `OutboundWebhookService` — create/list/get/update/delete. 각 진입에서 admin 검사 우선 → url 검증 → 도메인 팩토리 → secret 암호화 → repo. 예외 계층(NotFound/Forbidden/Validation/Conflict).
**REFACTOR**. 공통 admin 게이트 helper 추출, KDoc.
**검증**. `./gradlew :backend:modules:s-e-i:test --tests *OutboundWebhookServiceTest`.

### Task 7. REST 컨트롤러 + DTO + ActorExtractor + 스코프 ExceptionHandler + 통합 test fixture

**메타**.
- agent: `security-engineer`
- files: [`bm/s-e-i/src/main/kotlin/com/bts/search/webhook/web/OutboundWebhookController.kt`, `bm/s-e-i/src/main/kotlin/com/bts/search/webhook/web/dto/OutboundWebhookDtos.kt`, `bm/s-e-i/src/main/kotlin/com/bts/search/webhook/web/OutboundWebhookActorExtractor.kt`, `bm/s-e-i/src/main/kotlin/com/bts/search/webhook/web/OutboundWebhookExceptionHandler.kt`, `bm/s-e-i/src/test/kotlin/com/bts/search/webhook/web/WebhookIntegrationConfig.kt`, `bm/s-e-i/src/test/kotlin/com/bts/search/webhook/web/OutboundWebhookControllerIntegrationTest.kt`]
- depends-on: [6]

**★리뷰 C2 반영**. search는 통합테스트용 단일 부트 앱(`@SpringBootApplication`)이 **없다** — 형제(SavedFilterIntegrationTest 등)는 각자 `@ContextConfiguration(classes=[...IntegrationConfig])`로 로컬 `@Bean`을 직접 선언한다. 따라서 "중앙 스캔에 shared.http 추가"할 대상이 없다. 대신 이 task가 자체 `WebhookIntegrationConfig`(@TestConfiguration/@Configuration)를 선언:
  - `SystemPermissionResolver` **stub 빈(fail-closed 기본: isSystemAdmin=false)** — 테스트가 admin actor를 명시 override.
  - `OutboundUrlValidator`를 **명시 `@Bean OutboundUrlValidator()`**(무인자 생성자)로 주입 — `com.bts.shared.http` 패키지 스캔은 불필요한 `OutboundHttpClientConfig`의 RestClient 빈(PR2는 dispatch 0)까지 끌어오므로 회피.
  - webhook 암호화 test property(`bts.webhook-encryption.key/salt`) 세팅으로 secret 경로 테스트 지원.

**RED**. `OutboundWebhookControllerIntegrationTest`(Testcontainers, `@ContextConfiguration`으로 위 fixture + 실 서비스/repo 로드) — 비admin 403(전 엔드포인트)·POST 201(secret 미노출·hasSecret=true)·GET 목록/단건·PUT OCC 409·DELETE 204·존재X 404·SSRF url 400·eventFilter 빈/미지 400. actor 추출을 리소스 조회보다 먼저(probe 차단). fixture 부재로 컴파일/부팅 실패가 RED.
**GREEN**. `/api/v1/webhooks` 컨트롤러(형제 SavedFilterController 관례: raw List·offset paging·DEFAULT 20/MAX 100) + 요청/응답 DTO(응답에 secret 원문 필드 없음, `hasSecret`) + ActorExtractor + 스코프 `@RestControllerAdvice(assignableTypes=[OutboundWebhookController])`(구체 핸들러 우선, catch-all 최후) + `WebhookIntegrationConfig`.
**REFACTOR**. DTO 매퍼·검증 helper 정리, fixture admin/non-admin 시드 헬퍼.
**검증**. `./gradlew :backend:modules:s-e-i:test --tests *OutboundWebhookControllerIntegrationTest` + **s-e-i 전체 스위트**(cross-BC 부팅 회귀 0) + ktlint/detekt.

## Plan 메타

- task 수: 7 (리뷰 C2로 옛 Task 7[test-boot 스캔] 제거 → Task 7[컨트롤러]에 통합 fixture 흡수)
- 예상 wave: 4 (W1: T1·T2·T3 병렬[] → W2: T4[dep1]·T5[dep2,3] → W3: T6[dep3,4,5] → W4: T7[dep6]). 단 s-e-i 동일 모듈 test 컴파일은 직렬화 요인(bts-plan-wave-gradle-module-compile).
- 예상 시간: 직렬 약 27분 / wave 병렬 약 14분.
- TDD 강제: yes (test 커밋이 feat 커밋보다 먼저).
- 담당: security-engineer(T1 추출·T6 게이트/암호화·T7 컨트롤러 경계) / db-engineer(T2 마이그레이션) / backend-engineer(T3·T4·T5).
- 추가 검증: identity-access 전체 스위트(T1 OIDC 회귀), s-e-i 전체 스위트(cross-BC 부팅), ktlint/detekt 전 영향 모듈.

## 리뷰 결과

### 독립 eng·security 리뷰 (2026-07-01, autoplan 대신 백엔드 집중 리뷰 — bts-review-plan-autoplan-overkill)

리뷰가 코드베이스 실측으로 결함을 검증. 초기 판정 **BLOCKED**(B1) → plan 수정으로 해소.

- **B1 (BLOCKER, 해소됨)**. Task 1 `files:`에 SecretEncryptor 이동으로 깨지는 참조 누락 — `OidcAuthFlowIntegrationTest`·`DbClientRegistrationRepositoryTest`(+`OidcProviderConfig` KDoc 링크). → Task 1 files에 전수 추가 + REFACTOR에 KDoc 갱신 명시(plan-files-constructor-injection-existing-tests 재발 방지).
- **C1 (반영)**. SecretEncryptor 예외 메시지가 OIDC 전용(`Set BTS_OIDC_ENCRYPTION_*`) → webhook 키 미설정 시 운영 오도. Task 1 GREEN에 메시지 BC 중립화(substring `encryption key not configured` 유지=test-safe).
- **C2 (반영)**. 옛 Task 7 "중앙 스캔 추가" 전제가 코드와 불일치(search는 단일 부트앱 없음, per-test @ContextConfiguration) + vacuous RED. → 옛 Task 7 제거, Task 7(컨트롤러)에 `WebhookIntegrationConfig`(stub SystemPermissionResolver fail-closed + 명시 `@Bean OutboundUrlValidator()`(패키지 스캔 회피, RestClient 빈 안 끌어옴) + 암호화 test property) 흡수.
- **C3 (반영)**. webhook_deliveries는 append-only 발송 로그(audit 동류) → 소프트삭제 미적용을 spec/DDL에 명시(DATA.md §1.2 예외). PR2 테이블 생성 유지.
- **C4 (반영)**. ADR2 "단일 출처" 문구 완화(MfaSecretEncryptor 3번째 사본 잔존 반영).
- **C5 (수용, PR3 재확인)**. event_filter allowlist ↔ NotificationEventType wireValue drift 가드 없음(BC 격리상 불가피). PR3 dispatch 매칭 시 재확인.

**OK 확인**. SYSTEM_ADMIN 게이트(actor 추출→admin 검사→리소스 조회 순, probe 차단)·cross-BC=shared 포트만·SSRF 생성/수정 양쪽 적용·secret 위생(hasSecret만)·스코프 ExceptionHandler(assignableTypes)·V603 번호 정확+init_codegen 미러+전체 스위트 검증.

**최종 판정**. B1/C1/C2/C3/C4 반영 완료 → **PASS**. C5는 PR3 재확인 항목.
