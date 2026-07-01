# FR-API-03 PR4 — 아웃바운드 Webhook 관리 UI + 발송 이력 화면 + E2E — 스펙

> slug: fr-api-03-pr4-webhook-ui · BC: search-export-import(소비) / apps/web(구현) · type: ui
> 상위 스펙: [2026-07-01-fr-api-03-outbound-webhook](./2026-07-01-fr-api-03-outbound-webhook.md) §0 PR 분할 표 (PR4 = 관리 UI + 이력 화면 + E2E)
> 관련 ADR: [outbound-webhook-bc-and-reuse](../decisions/2026-07-01-fr-api-03-outbound-webhook-bc-and-reuse.md)
> **PR4 완료 시 FR-API-03 전체 종료. FR 총수 123 불변(새 FR 없음), 마이그레이션 0, 백엔드 변경 0(순수 프론트).**

## 0. 범위

FR-API-03의 마지막 PR. PR1(shared 추출)·PR2(구독 CRUD 백엔드)·PR3(발송 실행 계층)이 머지된 상태에서, 이미 확정된 `/api/v1/webhooks` REST 계약을 소비하는 **순수 프론트엔드 관리 UI**를 구현한다.

- 구현 위치: `apps/web` (React 19 / TS strict)
- 담당: frontend-engineer (E2E는 impl 말미 qa-engineer 부수 추가)
- 디자인: **기존 admin 컨벤션 재사용**(Maxi 확정) — `admin.audit-logs`/`admin.notification-policies` 화면 패턴 + DESIGN.md 토큰. design-shotgun 스킵.
- 이벤트 목록: **프론트 상수 미러**(Maxi 확정) — `admin/audit-logs`의 `AUTH_EVENT_TYPES` 선례. 백엔드 카탈로그 엔드포인트 미신설.

## 1. 목표 · 사용자 시나리오

**목표**. SYSTEM_ADMIN이 아웃바운드 Webhook 구독을 브라우저에서 등록·조회·수정·삭제하고, 각 구독의 발송 성공/실패 이력을 확인할 수 있게 한다. 비-admin에게는 진입 경로 자체를 숨긴다.

**Given-When-Then**.
- **S1 (목록·게이팅)**. Given 로그인한 SYSTEM_ADMIN, When `/admin/webhooks` 진입, Then 구독 목록(name/url/이벤트/활성/secret 설정 여부)이 표로 표시되고 "새 구독" 버튼이 보인다. Given 비-admin(또는 미인증), When 같은 경로 진입, Then `/dashboard`(또는 `/login`)로 리다이렉트되고 헤더 메뉴에 항목이 없다.
- **S2 (생성)**. Given admin이 "새 구독"을 열고, When name·url·구독 이벤트(체크박스, 발행가능 2종)·secret(선택)·projectKey(선택)·활성 여부를 입력하고 저장, Then `POST /api/v1/webhooks`가 201로 성공하고 목록에 새 행이 나타난다. url이 SSRF 차단 대상이면 서버 400을 일반 오류 메시지로 표시한다(내부 host 미노출).
- **S3 (수정·OCC·secret 3-state)**. Given admin이 기존 구독을 편집, When 필드를 바꿔 저장, Then `PUT /api/v1/webhooks/{id}`가 현재 `version`을 함께 보내 성공한다. secret 입력란을 **비워두면 기존 secret 유지**, 값을 넣으면 교체된다(응답은 `hasSecret`만). 저장 중 다른 곳에서 먼저 바뀌어 version이 어긋나면 409를 "다시 불러오세요" 안내로 표시한다.
- **S4 (삭제)**. Given admin이 구독 행의 삭제를 누르고 확인, When `DELETE /api/v1/webhooks/{id}`가 204, Then 목록에서 사라진다(소프트 삭제).
- **S5 (발송 이력)**. Given admin이 특정 구독의 "이력"을 열고, When `GET /api/v1/webhooks/{id}/deliveries`를 최신순으로 받아, Then 이벤트·상태(성공/실패)·HTTP 응답코드·시도 순번·실패 사유·시각이 표로 표시된다. 페이지 크기만큼 받으면 "다음" 버튼이 활성화된다.

## 2. 기능 요구사항 (FR)

> FR-API-03의 하위 작업이라 새 FR ID를 만들지 않는다. 아래는 PR4 로컬 항목(PR1 스펙의 `PR1-FR-N`과 동형).

- **PR4-FR-1 라우트·게이팅**. `/admin/webhooks`(목록) 라우트를 code-based로 등록하고 `composeGuards(requireAuth, requireSystemAdmin)` 적용. 헤더/네비의 admin 메뉴 노출도 `isSystemAdmin === true`로 게이팅(기존 Header 패턴 재사용). 발송 이력은 목록 화면 내 상세/모달 또는 `/admin/webhooks/$id/deliveries` 하위 경로 중 plan에서 확정(기존 admin 화면 라우팅 관례 따름).
- **PR4-FR-2 API 클라이언트 + Zod**. `apps/web/src/api/webhooks.ts` 신설. `apiGet`(GET, CSRF 불요) + `apiFetch`+`X-XSRF-TOKEN`(POST/PUT/DELETE, CSRF) 관례(notification-policies 선례). Zod 스키마는 §4 계약 1:1 정합. nullable 필드는 `@JsonInclude(NON_NULL)` 가정 하에 반드시 `.nullish()`(createdAt/updatedAt/projectKey/responseCode/errorDetail/deliveredAt). `eventType`/`status`는 전방호환 위해 `z.string()`으로 수신하고 라벨 매핑에서 미지 값은 원문 표시.
- **PR4-FR-3 구독 목록 화면**. 표(name·url·구독 이벤트·projectKey·활성·hasSecret·수정시각) + "새 구독" 버튼 + 행별 편집/삭제/이력. offset 페이지네이션(§엣지 EC-2 방식). AuditLogTable/PaginationControls 시각 컨벤션 재사용하되 total 미의존 형태로 조정.
- **PR4-FR-4 구독 생성/수정 폼**. name·url(필수), 구독 이벤트(발행가능 2종 체크박스, 최소 1개), secret(선택, 수정 시 3-state 안내 문구), projectKey(선택), 활성 여부. 수정 폼은 `version`을 폼 상태로 보유해 PUT에 실어 보낸다. 서버 400/409/403을 필드/폼 오류로 표기(내부 식별자·비밀값 미노출 메시지 그대로 사용).
- **PR4-FR-5 발송 이력 화면**. 특정 구독의 발송 시도를 최신순 표로. 컬럼: 이벤트·상태 배지(성공=초록/실패=빨강, 2종만)·응답코드(null이면 "—")·시도 순번·실패 사유(성공 시 "—")·발송시각(실패면 createdAt 기준 시도시각 표시). offset prev/next.
- **PR4-FR-6 발행가능 이벤트 상수 미러**. `WEBHOOK_PUBLISHABLE_EVENTS = ['issue.created','issue.transitioned'] as const` + 백엔드 `WebhookEventCatalog.PUBLISHABLE` 동기화 주석(audit-logs `AUTH_EVENT_TYPES` 선례). 한글 라벨 맵 별도(`i18n/`), 미지 wireValue는 원문 표시.
- **PR4-FR-7 MSW 핸들러 + 테스트**. `mocks/`에 6개 엔드포인트 stateful 핸들러(생성→목록 반영, 삭제→제거, PUT→version 증가·secret 3-state) + 단위/컴포넌트 테스트(vitest) + E2E happy path(Playwright): 목록·생성·수정·삭제·이력·비-admin 차단.

## 3. 비기능 요구사항 (NFR)

- **보안 게이팅 이중화**. 라우트 가드(`requireSystemAdmin`)는 UX 편의 계층이고 권위 출처는 백엔드(403). 비-admin에게 화면/메뉴/버튼 모두 미노출. secret 평문은 응답·로그·화면 어디에도 표시하지 않는다(`hasSecret` boolean만).
- **CSRF**. 상태 변경(POST/PUT/DELETE)은 `X-XSRF-TOKEN` double-submit 헤더 포함(sessions.readXsrfToken 재사용).
- **계약 정합(가짜그린 차단)**. MSW fixture/핸들러 응답이 백엔드 DTO와 필드·형식 1:1 정합. Zod 스키마가 §4 계약과 어긋나지 않게 계약 grep 기반 작성(프론트가 백엔드 DTO를 상상 금지).
- **접근성/일관성**. DESIGN.md 토큰·기존 admin 화면 레이아웃(max-w, heading/description 헤더, muted 설명)과 일관.
- **무회귀**. 기존 admin 라우트·헤더·라우터 등록에 회귀 0. 기존 E2E 함께 통과.

## 4. API 인터페이스 (백엔드 확정 계약 — 프론트는 소비만, 상상 금지)

**엔드포인트(전 SYSTEM_ADMIN 전용)**.
| 메서드 | 경로 | 성공 | 비고 |
|---|---|---|---|
| GET | `/api/v1/webhooks?page=0&size=20` | 200 + `WebhookResponse[]` | **raw List**(envelope 아님). size DEFAULT20 MIN1 MAX100 |
| POST | `/api/v1/webhooks` | 201 + `WebhookResponse` | CreateWebhookRequest |
| GET | `/api/v1/webhooks/{id}` | 200 + `WebhookResponse` | 단건 |
| PUT | `/api/v1/webhooks/{id}` | 200 + `WebhookResponse` | UpdateWebhookRequest(**version 필수**) |
| DELETE | `/api/v1/webhooks/{id}` | 204 | 소프트 삭제 |
| GET | `/api/v1/webhooks/{id}/deliveries?page=0&size=20` | 200 + `WebhookDeliveryResponse[]` | **raw List**, 최신순 |

**WebhookResponse**. `id: string(uuid)`, `name: string`, `url: string`, `eventFilter: string[]`, `projectKey: string | null`(nullish), `enabled: boolean`, `hasSecret: boolean`, `createdAt: string | null`(nullish, ISO), `updatedAt: string | null`(nullish, ISO), `version: number`.

**WebhookDeliveryResponse**. `id: string(uuid)`, `eventType: string`, `status: string`(SUCCEEDED|FAILED), `responseCode: number | null`(nullish), `attemptCount: number`, `errorDetail: string | null`(nullish), `createdAt: string | null`(nullish, ISO), `deliveredAt: string | null`(nullish, ISO).

**CreateWebhookRequest**. `name`, `url`, `eventFilter: string[]`, `secret?`, `projectKey?`, `enabled?`(기본 true).
**UpdateWebhookRequest**. `name`, `url`, `eventFilter`, `version`(필수, OCC), `secret?`(**3-state**: 생략/null/blank=기존 유지, 값=교체), `projectKey?`, `enabled?`.

## 5. 데이터 모델 변경

없음. 순수 프론트엔드. 신규 마이그레이션 0, 백엔드 변경 0, 새 pgmq 큐 0.

## 6. 엣지 케이스 · 리스크 (sanity gap 선반영 — brainstorming 대체)

- **EC-1 @JsonInclude(NON_NULL) ↔ Zod**. nullable 필드가 null이면 JSON에 키 자체가 없다 → `.nullable()`만 쓰면 파싱 실패. createdAt/updatedAt/projectKey/responseCode/errorDetail/deliveredAt 전부 `.nullish()`. (audit-logs/notification-policies가 겪은 함정, FR-AU-08 D6.)
- **EC-2 raw List 페이지네이션(총 개수 없음)**. 목록/이력은 envelope가 아니라 raw List라 totalElements/totalPages가 없다. audit-logs의 PaginationControls(total 의존)를 그대로 못 쓴다. → "받은 개수 == size면 다음 페이지 있음" 추정. prev(page>0)/next(len===size) disabled 규칙. "N개 중 X–Y" 대신 "페이지 P"로 표기.
- **EC-3 secret 3-state UX**. 수정 폼 secret 입력란을 비워두면 기존 secret 유지(교체 아님). 사용자가 "비우면 secret이 지워진다"고 오해하지 않도록 명시 문구("비워두면 기존 서명 키 유지"). 생성 폼엔 3-state 없음(빈=미설정).
- **EC-4 OCC 409**. PUT은 version이 필수이며 서버가 stale version이면 409. 폼은 편집 진입 시점의 version을 보유하고, 409 시 "다른 곳에서 변경됨 — 다시 불러오기" 안내 + 목록 invalidate.
- **EC-5 eventType/status 전방호환**. 응답 eventType/status는 z.string() 수신. 미지 값(백엔드가 이벤트/상태 추가)이 와도 파싱 무파손, 라벨 맵 미스는 원문 표시. status 배지는 SUCCEEDED/FAILED 2종만 색 매핑, 그 외 중립.
- **EC-6 MSW stateful & 계약 drift**. 핸들러는 생성/수정/삭제가 목록·이력에 반영되는 stateful store(공유 시드 가능) — 파생 동작 시드. fixture 필드/형식이 §4와 어긋나면 적대 리뷰에서 표면화되므로 계약 grep 기반. (msw-derived-behavior-shared-store, ui-permission-gating memory.)
- **EC-7 E2E 영속은 SPA 내부이동**. MSW 상태 변화 검증은 브라우저 reload가 아니라 SPA 내부 라우팅으로 확인(reload=MSW 초기화=가짜그린).
- **EC-8 admin 진입점**. 라우트 가드만으로 부족 — 헤더/네비 메뉴·목록 진입 버튼도 `isSystemAdmin` 게이팅(비-admin은 딥링크로도 리다이렉트). 목록 진입 버튼 노출과 라우트 가드 둘 다.
- **EC-9 빈 eventFilter 거부**. 생성/수정 시 이벤트 최소 1개 선택 강제(도메인 팩토리가 400, 프론트도 저장 전 검증). 발행 불가 이벤트는 애초에 상수 미러에 없어 선택 불가.

## 7. 측정 가능한 완료 기준

- [ ] `/admin/webhooks` SYSTEM_ADMIN 진입 시 목록 표시, 비-admin/미인증은 리다이렉트 + 메뉴 미노출.
- [ ] 생성/수정/삭제가 서버 계약대로 동작(201/200/204), 목록에 즉시 반영.
- [ ] 수정 시 version 동봉(OCC), secret 3-state 동작(빈=유지), 409/400/403 오류 표기.
- [ ] 발송 이력 최신순 표시, status 2종 배지, nullable 컬럼 "—" 처리, offset prev/next.
- [ ] Zod 스키마 §4 계약 1:1(nullish 정합), `pnpm typecheck` 0, `pnpm test`(webhook 단위/컴포넌트) 그린.
- [ ] E2E happy path(목록·생성·수정·삭제·이력·비-admin 차단) 그린, 기존 E2E 무회귀.
- [ ] 백엔드 변경 0, 마이그레이션 0, FR 총수 123 불변.

## Brainstorming Check

✅ 통과 (직접 기술 스펙 — 확정 계약 소비 UI, office-hours/제품발상 부적합[bts-spec-office-hours-mismatch], PR1 선례).
근거 조사로 사실 확정: (1)백엔드 계약 실물 grep(WebhookResponse 10필드·DeliveryResponse 8필드·raw List·OCC·secret 3-state), (2)admin 컨벤션(requireSystemAdmin 가드·audit-logs 페이지네이션·notification-policies CRUD/CSRF/Zod nullish·enum 미러), (3)Maxi 결정 2건(이벤트=프론트 미러·디자인=기존 admin 재사용). Sanity gap 9건(EC-1~9)을 §6에 선반영 — 특히 EC-2(raw List 총개수 부재) EC-3(secret 3-state UX) EC-4(OCC 409) EC-8(게이팅 이중화)이 이 PR 고유 리스크.
