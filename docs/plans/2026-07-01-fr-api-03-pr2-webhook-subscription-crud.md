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
- files: [`bm/shared-kernel/src/main/kotlin/com/bts/shared/crypto/SecretEncryptor.kt`, `bm/shared-kernel/src/test/kotlin/com/bts/shared/crypto/SecretEncryptorTest.kt`, `bm/identity-access/src/main/kotlin/com/atlas/bts/identity/config/OidcEncryptionConfig.kt`, `bm/identity-access/src/main/kotlin/com/atlas/bts/identity/provider/oidc/DbClientRegistrationRepository.kt`, `bm/identity-access/src/test/kotlin/com/atlas/bts/identity/config/SecretEncryptorTest.kt`]
- depends-on: []

**RED**. shared-kernel에 `SecretEncryptorTest`(round-trip 암복호화 + 키 미설정 시 encrypt 예외 + 같은 평문 다른 ciphertext) 신규 배치 → `com.bts.shared.crypto.SecretEncryptor` 부재로 컴파일 실패.
**GREEN**. `SecretEncryptor.kt`를 shared-kernel `com.bts.shared.crypto`로 이동(로직 변경 0, 패키지만 변경). KDoc의 OIDC 고유 문구는 "외부 비밀값 범용"으로 일반화.
**REFACTOR**. identity-access 참조처(`OidcEncryptionConfig`, `DbClientRegistrationRepository`, 그 외 grep으로 발견되는 전부) import를 `com.bts.shared.crypto.SecretEncryptor`로 교체. identity-access의 옛 `SecretEncryptorTest` 삭제(shared로 이동).
**검증**. `./gradlew :backend:modules:shared-kernel:test --rerun-tasks` + **identity-access 전체** `./gradlew :backend:modules:identity-access:test --rerun-tasks`(OIDC 암호화 회귀 0). ktlintCheck/detekt 두 모듈.

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

### Task 7. search 통합테스트 cross-BC stub 빈 + shared.http 스캔

**메타**.
- agent: `backend-engineer`
- files: [`bm/s-e-i/src/test/kotlin/com/bts/search/webhook/WebhookTestBootConfig.kt`, `bm/s-e-i/src/test/resources/application-test.yml`]
- depends-on: []

**RED**. search 통합 test-boot가 `SystemPermissionResolver`·`OutboundUrlValidator` 빈 부재로 `NoSuchBeanDefinitionException` 부팅 실패(스모크 부팅 테스트).
**GREEN**. 테스트 전용 `@TestConfiguration` — `SystemPermissionResolver` stub(**fail-closed 기본**: isSystemAdmin=false, 테스트가 명시 override) + 기존 search 통합 부트 앱 스캔에 `com.bts.shared.http` 중앙 추가(OutboundUrlValidator @Component 확보). test property에 `bts.webhook-encryption.*` 세팅.
**REFACTOR**. 공유 헬퍼로 admin/non-admin 시드.
**검증**. search 통합테스트 부팅(스모크) 통과.

### Task 8. REST 컨트롤러 + DTO + ActorExtractor + 스코프 ExceptionHandler

**메타**.
- agent: `security-engineer`
- files: [`bm/s-e-i/src/main/kotlin/com/bts/search/webhook/web/OutboundWebhookController.kt`, `bm/s-e-i/src/main/kotlin/com/bts/search/webhook/web/dto/OutboundWebhookDtos.kt`, `bm/s-e-i/src/main/kotlin/com/bts/search/webhook/web/OutboundWebhookActorExtractor.kt`, `bm/s-e-i/src/main/kotlin/com/bts/search/webhook/web/OutboundWebhookExceptionHandler.kt`, `bm/s-e-i/src/test/kotlin/com/bts/search/webhook/web/OutboundWebhookControllerIntegrationTest.kt`]
- depends-on: [6, 7]

**RED**. `OutboundWebhookControllerIntegrationTest`(Testcontainers, 실 부트) — 비admin 403(전 엔드포인트)·POST 201(secret 미노출·hasSecret=true)·GET 목록/단건·PUT OCC 409·DELETE 204·존재X 404·SSRF url 400·eventFilter 빈/미지 400. actor 추출을 리소스 조회보다 먼저(probe 차단).
**GREEN**. `/api/v1/webhooks` 컨트롤러(형제 SavedFilterController 관례: raw List·offset paging·DEFAULT 20/MAX 100) + 요청/응답 DTO(응답에 secret 원문 필드 없음, `hasSecret`) + ActorExtractor + 스코프 `@RestControllerAdvice(assignableTypes=[OutboundWebhookController])`(구체 핸들러 우선, catch-all 최후).
**REFACTOR**. DTO 매퍼·검증 helper 정리.
**검증**. `./gradlew :backend:modules:s-e-i:test --tests *OutboundWebhookControllerIntegrationTest` + s-e-i 전체 스위트 + ktlint/detekt.

## Plan 메타

- task 수: 8
- 예상 wave: 4 (W1: T1·T2·T3·T7 병렬[]→ W2: T4[dep1]·T5[dep2,3] → W3: T6[dep3,4,5] → W4: T8[dep6,7]). 단 s-e-i 동일 모듈 test 컴파일은 직렬화 요인(bts-plan-wave-gradle-module-compile).
- 예상 시간: 직렬 약 30분 / wave 병렬 약 15분.
- TDD 강제: yes (test 커밋이 feat 커밋보다 먼저).
- 담당: security-engineer(T1 추출·T6 게이트/암호화·T8 컨트롤러 경계) / db-engineer(T2 마이그레이션) / backend-engineer(T3·T4·T5·T7).
- 추가 검증: identity-access 전체 스위트(T1 OIDC 회귀), s-e-i 전체 스위트(cross-BC 부팅), ktlint/detekt 전 영향 모듈.

## 리뷰 결과 (← /bts-review-plan 채움)
