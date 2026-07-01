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

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
