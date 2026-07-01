# FR-API-03 PR2 — 구독형 아웃바운드 Webhook 구독 관리 계층 — 스펙

> slug: fr-api-03-pr2-webhook-subscription-crud
> BC: search-export-import (`com.bts.search.webhook`)
> 작성: 2026-07-01
> 관련 ADR: [BC 배치+재사용](../decisions/2026-07-01-fr-api-03-outbound-webhook-bc-and-reuse.md) · [SecretEncryptor shared 추출](../decisions/2026-07-01-fr-api-03-secret-encryptor-shared-extraction.md)
> 범위 제외(PR3+): 실제 발송(fanout/dispatch/HMAC/circuit breaker), 관리 UI+E2E(PR4)

## 개요

외부 시스템이 이벤트 통지를 받도록 아웃바운드 webhook을 **구독 등록**하는 관리 계층. 이번 PR은 구독의 도메인 모델 + CRUD REST API + secret 암호화 저장 + 스키마(V603)까지. 실제 이벤트 발송은 PR3.

## 사용자 시나리오 (Given-When-Then)

- **S1 (구독 생성)**. Given SYSTEM_ADMIN이 로그인, When `POST /api/v1/webhooks`로 name·url·eventFilter(+선택 secret·projectKey)를 보내면, Then URL이 SSRF 검증을 통과하고 secret은 암호화 저장되며 201 + 구독 정보(secret 원문 미포함)를 받는다.
- **S2 (목록/단건 조회)**. Given 구독이 등록됨, When SYSTEM_ADMIN이 `GET /api/v1/webhooks` 또는 `GET /{id}`, Then 구독 목록/단건을 받는다(secret 원문은 절대 미노출, `hasSecret` boolean만).
- **S3 (수정)**. Given 구독 존재, When SYSTEM_ADMIN이 `PUT /{id}`로 version과 함께 필드 변경, Then OCC 검증 후 갱신(secret 필드 생략 시 기존 유지, 새 값 주면 재암호화).
- **S4 (삭제)**. When SYSTEM_ADMIN이 `DELETE /{id}`, Then 소프트 삭제(deleted_at) 후 204. 이후 조회/발송 대상에서 제외.
- **S5 (권한 거부)**. Given 비-SYSTEM_ADMIN actor, When 어떤 webhook 엔드포인트든 호출, Then 403 (리소스 존재 여부 노출 안 함 — actor/admin 검사를 리소스 조회보다 먼저).
- **S6 (SSRF 차단)**. When url이 내부망/malformed, Then 400 거부(host만 보안 로그, 전체 URL 비기록).

## 기능 요구사항 (FR)

- **FR-1**. `OutboundWebhook` 도메인 애그리거트 — name·url·eventFilter(비어있지 않음)·secret(선택, 암호화 전 원문은 도메인 경계 밖)·projectKey(선택)·enabled·version. 생성/수정은 팩토리/불변식 경유(name ≤100, url 비어있지 않음, eventFilter 비어있지 않고 각 항목이 발행가능 이벤트 allowlist에 속함).
- **FR-2**. CRUD REST API (`/api/v1/webhooks`) — POST 생성 / GET 목록(offset 페이지네이션) / GET {id} 단건 / PUT {id} 수정(OCC) / DELETE {id} 소프트 삭제.
- **FR-3**. **모든 엔드포인트는 SYSTEM_ADMIN 게이트**. shared-kernel `SystemPermissionResolver.isSystemAdmin(actorId)`로 판정, 실패 시 403. actor 추출·admin 검사를 리소스 조회보다 **먼저**(존재 probe 차단, auth-extraction-before-resource-lookup).
- **FR-4**. **URL SSRF 검증** — 생성/수정 시 shared-kernel `OutboundUrlValidator.check(url)`로 검증. Blocked/Malformed → 400. FR-NT-05와 동일 방어 재사용.
- **FR-5**. **secret 암호화 저장** — shared-kernel `com.bts.shared.crypto.SecretEncryptor`(SecretEncryptor를 identity-access에서 추출)로 AES-256-GCM 암호화. search BC는 자체 키(`BTS_WEBHOOK_ENCRYPTION_KEY/SALT`)로 빈 구성. secret 원문은 **로깅·응답에 절대 미포함**(DEVELOPMENT §1.1.2). 응답은 `hasSecret: Boolean`만.
- **FR-6**. **event_filter 어휘** — 값은 `NotificationEventType.wireValue`와 동일한 문자열 계약(예: `issue.created`, `issue.transitioned`). search BC는 발행가능(publishable=true) wireValue를 로컬 allowlist(문자열 상수)로 정의해 검증(BC 격리 — enum import 아님). 초기 허용: `issue.created`, `issue.transitioned`(향후 확장). 미지값 → 400.
- **FR-7**. **V603 마이그레이션** — `outbound_webhooks` + `webhook_deliveries` 테이블. init_codegen.sql 미러 필수(jooq-init-codegen-mirror).
- **FR-8**. `webhook_deliveries` 테이블은 **이번 PR은 생성만**(발송 이력 기록/조회는 PR3). CRUD/발송 로직 없음.

## 비기능 요구사항 (NFR)

- **NFR-1 (보안)**. secret 원문은 어떤 경로로도 노출 금지(로그·응답·예외 메시지). 암호화 실패/키 미설정은 500(비밀값 미포함 메시지).
- **NFR-2 (동시성)**. 수정은 OCC(version). 충돌 → 409. no-bump 불필요(구독 편집은 단일 경로).
- **NFR-3 (에러 표준)**. RFC 7807 `ProblemDetail` + `errorCode`. 스코프 한정 `@RestControllerAdvice(assignableTypes=[OutboundWebhookController])`. 구체 핸들러(입력/타입/OCC/권한/도메인) 우선, catch-all은 최후 fallback(catch-all-swallows 안티패턴 회피).
- **NFR-4 (BC 격리)**. cross-BC는 shared-kernel 포트만(`SystemPermissionResolver`, `OutboundUrlValidator`, `SecretEncryptor`). 타 BC 직접 import 0. projectKey는 문자열·FK 미적용.

## API 인터페이스 (REST)

베이스: `/api/v1/webhooks` (전 엔드포인트 SYSTEM_ADMIN).

| 메서드 | 경로 | 요청 | 성공 | 실패 |
|---|---|---|---|---|
| POST | `/api/v1/webhooks` | `{name, url, eventFilter[], secret?, projectKey?, enabled?}` | 201 + WebhookResponse | 400(검증/SSRF), 403 |
| GET | `/api/v1/webhooks?page&size` | — | 200 + List<WebhookResponse> | 403 |
| GET | `/api/v1/webhooks/{id}` | — | 200 + WebhookResponse | 403, 404 |
| PUT | `/api/v1/webhooks/{id}` | `{name, url, eventFilter[], version, secret?, projectKey?, enabled?}` | 200 + WebhookResponse | 400, 403, 404, 409 |
| DELETE | `/api/v1/webhooks/{id}` | — | 204 | 403, 404 |

**WebhookResponse**: `{id, name, url, eventFilter[], projectKey?, enabled, hasSecret, createdAt, updatedAt, version}`. **secret 원문 필드 없음**.

**secret 3-state (PUT)**. 필드 생략/null = 기존 유지. 비어있지 않은 값 = 재암호화 교체. (clear는 PR2 미지원 — 필요 시 후속.)

## 데이터 모델 변경 (V603)

```sql
-- outbound_webhooks: 아웃바운드 webhook 구독
CREATE TABLE outbound_webhooks (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name             VARCHAR(100) NOT NULL,
    url              TEXT NOT NULL,
    secret_encrypted TEXT,                       -- nullable, AES-256-GCM hex (원문 비저장)
    event_filter     TEXT[] NOT NULL,            -- wireValue 집합, 비어있지 않음
    project_key      VARCHAR(100),               -- nullable, null=전체 프로젝트
    enabled          BOOLEAN NOT NULL DEFAULT TRUE,
    created_by       UUID NOT NULL,              -- SYSTEM_ADMIN actor (BC 격리, FK 미적용)
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at       TIMESTAMPTZ,                -- 소프트 삭제(DATA.md 신규테이블 기본)
    version          BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX idx_outbound_webhooks_enabled_notdeleted
    ON outbound_webhooks (enabled) WHERE deleted_at IS NULL;
CREATE INDEX idx_outbound_webhooks_event_filter_gin
    ON outbound_webhooks USING GIN (event_filter);  -- PR3 매칭(&& overlap) 대비

-- webhook_deliveries: 발송 이력 (이번 PR은 테이블만, 기록/조회는 PR3)
CREATE TABLE webhook_deliveries (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    webhook_id    UUID NOT NULL REFERENCES outbound_webhooks(id),
    event_type    VARCHAR(100) NOT NULL,
    status        VARCHAR(20) NOT NULL,          -- PENDING/SUCCEEDED/FAILED (PR3)
    response_code INT,                           -- nullable HTTP status
    attempt_count INT NOT NULL DEFAULT 0,
    error_detail  TEXT,                          -- nullable
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    delivered_at  TIMESTAMPTZ                    -- nullable
);
CREATE INDEX idx_webhook_deliveries_webhook_id ON webhook_deliveries (webhook_id);
```

init_codegen.sql에 동일 DDL 미러(jOOQ 코드생성 대상).

## 엣지 케이스

- eventFilter 빈 배열 → 400.
- eventFilter에 미지 wireValue → 400.
- url이 내부망/스킴 위반/malformed → 400(host만 보안 로그).
- 비-SYSTEM_ADMIN → 403 (리소스 존재 여부 무관, admin 검사 우선).
- PUT stale version → 409.
- GET/PUT/DELETE 존재하지 않는 id → 404.
- secret 필드 생략 PUT → 기존 secret 유지(재암호화 안 함).
- name/url 공백만 → 400.
- 키 미설정 환경(BTS_WEBHOOK_ENCRYPTION_* 부재) + secret 제공 → 500(암호화 호출 시점 검증, 비밀값 미포함 메시지). secret 없는 요청은 정상 처리(부팅/조회 무영향).

## 제약 조건

- secret 원문은 도메인/응답 경계 밖(암호화 후에만 영속). DEVELOPMENT §1.1.2 준수.
- SecretEncryptor shared 추출은 identity-access BC 1회 교차(문서화된 예외, ADR D3). 검증은 identity-access 전체 스위트로.
- 발송·서명·circuit breaker·이력 기록은 PR3 범위. 본 PR에 dispatch 코드 0.

## 측정 가능한 완료 기준

- [ ] V603 적용 + init_codegen 미러 + jOOQ 코드생성 성공.
- [ ] SecretEncryptor shared-kernel 이동 + identity-access 전체 테스트 그린(OIDC 암호화 회귀 0).
- [ ] `/api/v1/webhooks` 5개 엔드포인트 CRUD 동작 + SYSTEM_ADMIN 403 게이트 통합테스트.
- [ ] secret 암호화 round-trip 테스트 + 응답/로그 secret 미노출 단언.
- [ ] SSRF url 거부(400) + event_filter 검증(400) + OCC 충돌(409) 테스트.
- [ ] webhook_deliveries 테이블 생성 확인(발송 로직 없음).
- [ ] ktlint/detekt 0, 전 모듈 컴파일 성공.

## Brainstorming Check

✅ 통과 (인라인 sanity check — 잘 정의된 기술 스펙, 코드베이스 접지 기반 gap 점검). 발견 gap + 해소:

1. **응답 봉투(raw vs envelope)**. FR-API-03 ADR은 "FR-API-01/02 표준 준수"라 했으나, FR-API-01 D1/D2는 offset 목록을 raw `Page`/`List`로 유지하고 cursor 경로만 envelope로 확정했다. 형제 `SavedFilterController`(같은 BC·최근)도 raw `List<Response>` 반환. → **webhook 목록은 형제 관례(raw List + offset paging)** 채택. admin 소형 목록이라 cursor envelope 불필요. (frontend-api-convention-per-bc — 같은 BC 선례 우선.)
2. **cross-BC 포트 test-boot 부팅**. search BC 통합테스트에 `SystemPermissionResolver`(impl=identity-access)·`OutboundUrlValidator`(shared-kernel `com.bts.shared.http`)가 필요. BC 격리 test-boot는 타 BC 미포함 → **stub 빈**(fail-closed 기본) + shared.http 스캔 중앙 추가 필요. → plan §리스크로 이관(fr-nt-02 신규 sender 부팅함정·shared-kernel-component-extraction-scan-regression 교훈).
3. **SecretEncryptor 빈 키 미설정**. search 자체 빈은 lazy라 키 부재여도 부팅 성공, encrypt() 호출 시점에만 검증. secret 포함 테스트는 test properties에 `BTS_WEBHOOK_ENCRYPTION_*` 설정 필요. → plan/impl 세부.
4. **projectKey 존재검증 없음**. BC 격리상 문자열·FK·존재검증 미적용(favorites 선례와 동형). 의도된 deviation, 스펙 명시.
5. **secret clear(제거)**. PR2는 3-state 중 keep/replace만. clear는 후속(YAGNI). 명시됨.
6. **webhook_deliveries 스키마 speculation**. PR3 발송 로직이 쓸 최소 컬럼(status/response_code/attempt/error/타임스탬프)만. 추가 필요 시 PR3가 V604로 확장. 과설계 회피.

Maxi 결정 필요 gap 0 (모두 선례·표준으로 해소).
