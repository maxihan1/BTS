# FR-API-03 PR4 — 아웃바운드 Webhook 관리 UI + 발송 이력 화면 + E2E

> slug: fr-api-03-pr4-webhook-ui
> type: ui
> agent: frontend-engineer
> 생성: 2026-07-01

## Brief

FR-API-03(구독형 아웃바운드 Webhook)의 마지막 PR(PR4). PR1(shared 추출)·PR2(구독 CRUD 백엔드)·PR3(발송 실행 계층)이 모두 머지됨. 이 PR은 프론트엔드 관리 UI + 발송 이력 화면 + E2E를 구현하고, 완료 시 FR-API-03 전체가 종료된다.

- 대상: `apps/web` SPA (React 19 / TS strict)
- 담당: frontend-engineer (E2E는 impl 말미 qa-engineer 부수 추가)
- 접근 제어: 전 엔드포인트 SYSTEM_ADMIN 전용 → whoami `isSystemAdmin` 게이팅으로 비-admin에게 화면 미노출 (FR-AU-05 선례 재사용)

### 프론트 계약 (백엔드 PR2/PR3 확정)
- `GET /api/v1/webhooks` — 구독 목록 (raw List + offset 페이지네이션 DEFAULT20/MAX100)
- `POST /api/v1/webhooks` — 구독 생성
- `GET /api/v1/webhooks/{id}` — 단건 조회
- `PUT /api/v1/webhooks/{id}` — 수정 (secret 3-state: 생략/blank유지/값재암호화)
- `DELETE /api/v1/webhooks/{id}` — 소프트 삭제
- `GET /api/v1/webhooks/{id}/deliveries` — 발송 이력 (offset page/size)

### 응답 DTO
- `WebhookResponse` — hasSecret boolean만 (secret 원문 미노출)
- `WebhookDeliveryResponse` — status enum(SUCCEEDED/FAILED), responseCode nullable, errorDetail(정규화 문구), deliveredAt

### spec 근거
- 전체 스펙: `docs/specs/2026-07-01-fr-api-03-outbound-webhook.md` §0 PR 분할 표 (PR4 = 관리 UI + 이력 화면 + E2E)

### 함정 후보 (과거 learnings/memory)
- `@JsonInclude`↔Zod `.nullish` 정합 (nullable 필드)
- MSW 계약 drift (적대 리뷰에서 표면화)
- MSW 영속 E2E는 SPA 내부이동 (reload=가짜그린)
- filter-aware queryKey (이력 페이지네이션 파라미터)
- admin 게이팅 UI: whoami isSystemAdmin 재사용

## 도메인 정리 (/bts-domain 완료)

- **BC**: search-export-import (`com.bts.search.webhook`). PR4는 프론트(`apps/web`)가 이 BC의 확정된 REST 계약을 소비하는 UI 작업. 신규 도메인 모델 0, 새 용어 0.
- **소비 대상 도메인(백엔드 확정, PR2/PR3)**: `OutboundWebhook`(구독 애그리거트), `WebhookDelivery`(append-only 발송 이력), `WebhookEventCatalog`(발행가능 이벤트 allowlist).
- **기존 결정 충돌**: 없음.
- **관련 ADR**:
  - `docs/decisions/2026-07-01-fr-api-03-outbound-webhook-bc-and-reuse.md` (BC 배치 + 재사용)
  - `docs/decisions/2026-07-01-fr-api-03-secret-encryptor-shared-extraction.md` (secret 암호화 shared 추출)
  - `docs/decisions/2026-06-14-fr-nt-05-webhook-dispatch-ssrf.md` (SSRF 정책)

### 계약 실물 검증 결과 (frontend-Zod-backend-DTO-contract-gap 회귀 방지)

백엔드 코드 grep으로 확정 — 체크포인트 요약보다 필드가 많으므로 Zod는 아래를 정본으로 한다.

**`WebhookResponse`** (목록/단건/생성/수정 응답):
`id: UUID`, `name: String`, `url: String`, `eventFilter: List<String>`, `projectKey: String?`(nullable), `enabled: Boolean`, `hasSecret: Boolean`, `createdAt: Instant?`(**nullable**), `updatedAt: Instant?`(**nullable**), `version: Long`.

**`WebhookDeliveryResponse`** (이력):
`id: UUID`, `eventType: String`, `status: String`(SUCCEEDED/FAILED), `responseCode: Int?`(nullable), `attemptCount: Int`, `errorDetail: String?`(nullable), `createdAt: Instant?`(nullable), `deliveredAt: Instant?`(nullable).

**요청 바디**:
- `CreateWebhookRequest`: name, url, eventFilter(List<String>), secret?, projectKey?, enabled?(기본 true)
- `UpdateWebhookRequest`: name, url, eventFilter, **version(OCC 키, 필수)**, secret?(**3-state**: 생략/null/blank=기존 암호문 유지, 값=재암호화), projectKey?, enabled?

**엔드포인트/응답 형식**:
- `GET /api/v1/webhooks?page=0&size=20` → 200 + **raw `List<WebhookResponse>`** (envelope 아님, page/size DEFAULT20 MIN1 MAX100)
- `POST` → 201 + WebhookResponse / `GET /{id}` → 200 / `PUT /{id}` → 200(version 필수) / `DELETE /{id}` → 204
- `GET /api/v1/webhooks/{id}/deliveries?page=0&size=20` → 200 + **raw `List<WebhookDeliveryResponse>`** (최신순)
- 전 엔드포인트 SYSTEM_ADMIN 전용.

### 게이트 1 전 결정 필요 — 도메인 갭 2건 (→ /bts-spec에서 옵션 제시)

- **갭 D1 — 발행가능 이벤트 목록 노출 경로 부재**: 구독 생성/수정의 `eventFilter` 선택 UI에는 `WebhookEventCatalog.PUBLISHABLE`(`issue.created`, `issue.transitioned`) 목록이 필요하나, 이를 노출하는 REST 엔드포인트가 없다(도메인 내부 상수). → (A) 프론트 상수 하드코딩(drift 위험, 현재 2종·변경 드묾) vs (B) `GET /api/v1/webhooks/events` 카탈로그 엔드포인트 추가(same BC view layer, learnings 옵션 C 선례).
- **갭 D2 — 목록/이력 raw List(총 개수 없음)**: FR-API-01(cursor+envelope)·FR-API-02(offset envelope)와 달리 webhook은 raw List. 프론트 페이지네이션은 "받은 개수 == size → 다음 페이지 있음" 추정으로 설계(별도 total API 없음).

## 스펙 (/bts-spec 완료)

전체 스펙. [docs/specs/2026-07-01-fr-api-03-pr4-webhook-ui.md](../specs/2026-07-01-fr-api-03-pr4-webhook-ui.md)

**Maxi 결정 2건**.
- 갭 D1(이벤트 목록) → **프론트 상수 미러**(`WEBHOOK_PUBLISHABLE_EVENTS`, audit-logs `AUTH_EVENT_TYPES` 선례). 백엔드 카탈로그 엔드포인트 미신설 = 순수 프론트 PR.
- 디자인 → **기존 admin 컨벤션 재사용**(audit-logs/notification-policies 패턴 + DESIGN.md). design-shotgun 스킵.

핵심 시나리오 3줄 요약.
- SYSTEM_ADMIN이 `/admin/webhooks`에서 구독 CRUD(생성/목록/수정/삭제) — 비-admin은 라우트 가드 + 메뉴 미노출로 이중 차단.
- 수정은 OCC(version 동봉) + secret 3-state(빈=기존 유지), 서버 400/409/403을 안전 메시지로 표기.
- 발송 이력(`/deliveries`)은 최신순 표 — status 2종 배지·nullable 컬럼 "—"·raw List라 size 기반 prev/next.

## Brainstorming Check (/bts-spec 완료)

✅ 통과 (직접 기술 스펙, office-hours/제품발상 부적합 — 확정 계약 소비 UI, PR1 선례).
sanity gap 9건(EC-1~9)을 스펙 §6에 선반영. 이 PR 고유 리스크는 EC-2(raw List 총개수 부재→size 기반 hasNext), EC-3(secret 3-state UX), EC-4(OCC 409), EC-8(admin 게이팅 이중화).

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
