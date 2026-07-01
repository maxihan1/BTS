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

## Plan

> 순수 프론트(apps/web) PR. 단일 SPA라 Gradle 모듈 컴파일 직렬화 무관. wave는 파일 겹침 + depends-on으로 계산.
> 참조 선례: `api/notification-policies.ts`·`api/useNotificationPolicies.ts`(CRUD+훅), `routes/admin.audit-logs.tsx`(Page+RouteAdapter+페이지네이션), `auth/routeGuard.ts`(requireSystemAdmin), `mocks/audit-log-handlers.ts`(MSW), `router.ts`(code-based 등록).

### Task 1. webhooks API 클라이언트 + Zod 스키마 + 이벤트 상수/라벨

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/api/webhooks.ts`, `apps/web/src/api/webhooks.test.ts`, `apps/web/src/i18n/webhook-labels.ts`]
- depends-on: []

**RED**: `webhooks.test.ts`
- `fetchWebhooks(page,size)` → raw List 파싱, `createWebhook/updateWebhook/deleteWebhook/getWebhook/fetchDeliveries` 계약.
- nullish 정합: createdAt/updatedAt/projectKey=null(키 부재)여도 파싱 성공(EC-1). responseCode/errorDetail/deliveredAt=null 이력 파싱.
- CSRF: POST/PUT/DELETE가 `X-XSRF-TOKEN` 헤더 포함(notification-policies 선례).
- secret 3-state: update 바디에서 secret 생략 시 키 미포함 확인(EC-3), version 필수 동봉(EC-4).
- 실패 메시지(예상): `webhooks.ts` 모듈/함수 없음.

**GREEN**: `webhooks.ts`
- `apiGet`(GET) / `apiFetch`+`readXsrfToken()`(변경) 사용. Zod `webhookResponseSchema`(createdAt/updatedAt/projectKey `.nullish()`, version `z.number()`), `webhookDeliveryResponseSchema`(responseCode/errorDetail/deliveredAt/createdAt `.nullish()`, status/eventType `z.string()` 전방호환 EC-5).
- `WEBHOOK_PUBLISHABLE_EVENTS = ['issue.created','issue.transitioned'] as const` + 백엔드 `WebhookEventCatalog.PUBLISHABLE` 동기화 주석(audit-logs AUTH_EVENT_TYPES 선례).
- `webhook-labels.ts`: 이벤트/status 한글 라벨 맵 + `labelForEvent`/`labelForStatus`(미지 값 원문 반환).

**REFACTOR**: 요청/응답 타입 `z.infer` 추출, JSDoc, 경로 상수화.

**검증**: `pnpm --filter web test -- webhooks.test.ts` + `pnpm --filter web typecheck`

### Task 2. useWebhooks React Query 훅 (쿼리 + 뮤테이션)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/api/useWebhooks.ts`, `apps/web/src/api/useWebhooks.test.ts`]
- depends-on: [1]

**RED**: `useWebhooks.test.ts`
- `useWebhooksQuery(page,size)` / `useWebhookDeliveriesQuery(id,page,size)` 캐시 키에 page/size 포함(filter-aware queryKey, EC-2).
- `useCreateWebhook/useUpdateWebhook/useDeleteWebhook` onSuccess가 `invalidateQueries`(setQueryData 금지 — flicker memory).
- 실패 메시지(예상): 훅 없음.

**GREEN**: `useWebhooks.ts`
- `WEBHOOKS_QUERY_KEY=['webhooks']`, 목록/이력 queryKey에 `[...,page,size]`/`[...,id,page,size]`. 모든 mutation onSuccess=invalidate-only.

**REFACTOR**: queryKey 상수 추출, JSDoc(useNotificationPolicies 톤).

**검증**: `pnpm --filter web test -- useWebhooks.test.ts`

### Task 3. MSW stateful 핸들러 6종 + fixtures

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/mocks/webhook-handlers.ts`, `apps/web/src/mocks/webhook-fixtures.ts`, `apps/web/src/mocks/webhook-handlers.test.ts`, `apps/web/src/mocks/handlers.ts`]
- depends-on: [1]

**RED**: `webhook-handlers.test.ts`
- 생성→목록 반영, 삭제→제거, PUT→version+1·secret 3-state(빈=hasSecret 유지), 이력 raw List 반환하는 stateful store(시드 가능, EC-6).
- fixture 필드/형식이 §4 계약 1:1(계약 grep 기반, drift 금지).

**GREEN**: `webhook-handlers.ts` + `webhook-fixtures.ts`
- 모듈 store(seed 함수 export, msw-derived-behavior-shared-store 선례). `handlers.ts`에 `import { webhookHandlers }` + 배열 spread 등록.

**REFACTOR**: store 리셋 헬퍼, fixture 상수화.

**검증**: `pnpm --filter web test -- webhook-handlers.test.ts`

### Task 4. WebhookTable (구독 목록 표)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/admin/WebhookTable.tsx`, `apps/web/src/components/admin/__tests__/WebhookTable.test.tsx`]
- depends-on: [1]

**RED/GREEN**: 순수 HTML+Tailwind 표(NotificationPolicyTable 관례, shadcn table 없음). 컬럼 name·url·eventFilter(라벨)·projectKey('—'|값)·enabled 배지·hasSecret 배지·updatedAt. 행별 `onEdit/onDelete/onViewDeliveries` 콜백 props. 빈 목록 empty state. props 기반이라 MSW 무관.

**REFACTOR**: 배지 서브컴포넌트, JSDoc, DESIGN.md 토큰.

**검증**: `pnpm --filter web test -- WebhookTable.test.tsx`

### Task 5. WebhookForm (생성/수정 겸용, secret 3-state, 이벤트 체크박스, OCC)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/admin/WebhookForm.tsx`, `apps/web/src/components/admin/__tests__/WebhookForm.test.tsx`]
- depends-on: [1]

**RED**: `WebhookForm.test.tsx`
- 생성 모드: name/url 필수 검증, 이벤트 최소 1개 강제(EC-9), 제출 페이로드 형태.
- 수정 모드(initialValue): 필드 프리필 + `version` 보유해 제출에 포함(EC-4), secret 입력란 비움=미포함(3-state, "비워두면 기존 키 유지" 안내 문구 EC-3).
- `submitError` prop 표기(400/409/403 서버 메시지, Dialog submitError 부모 전달 dead-path memory 유의).

**GREEN**: `WebhookForm.tsx`
- Input/Label/Button + 이벤트 체크박스(`WEBHOOK_PUBLISHABLE_EVENTS` 순회). `mode`/`initialValue`/`onSubmit`/`submitError`/`isSubmitting` props. 저장 전 클라 검증(빈 eventFilter/blank name·url).

**REFACTOR**: 폼 상태 타입 추출, JSDoc.

**검증**: `pnpm --filter web test -- WebhookForm.test.tsx`

### Task 6. WebhookDeliveryTable (발송 이력 표)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/admin/WebhookDeliveryTable.tsx`, `apps/web/src/components/admin/__tests__/WebhookDeliveryTable.test.tsx`]
- depends-on: [1]

**RED/GREEN**: 컬럼 eventType(라벨)·status 배지(SUCCEEDED 초록/FAILED 빨강 2종만, 그 외 중립 EC-5)·responseCode(null='—')·attemptCount·errorDetail(null='—')·시각(deliveredAt ?? createdAt). 빈 이력 empty state. props 기반.

**REFACTOR**: status 배지 매핑 상수, JSDoc.

**검증**: `pnpm --filter web test -- WebhookDeliveryTable.test.tsx`

### Task 7. admin.webhooks 목록 라우트 (Page + RouteAdapter 조립)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/routes/admin.webhooks.tsx`, `apps/web/src/routes/admin.webhooks.test.tsx`]
- depends-on: [2, 3, 4, 5]

**RED**: `admin.webhooks.test.tsx` (MSW 사용)
- 목록 렌더(useWebhooksQuery), "새 구독"→WebhookForm 생성, 행 편집→수정(version 동봉), 삭제→확인 후 제거+invalidate, "이력"→네비.
- size 기반 페이지네이션: prev(page>0)/next(받은 len===size) disabled(EC-2, audit-logs total 미의존 변형), "페이지 P" 표기.
- 409/400 → WebhookForm submitError로, 그 외 mutation 에러 → sonner toast.

**GREEN**: `admin.webhooks.tsx`
- `AdminWebhooksPage`(Form+Table 조립) + `AdminWebhooksRouteAdapter` export(router.ts 등록용, JSX 제약 회피). 삭제 확인 UX.

**REFACTOR**: PaginationControls 서브컴포넌트(size 기반), JSDoc + code-based 라우트 등록 예시 주석.

**검증**: `pnpm --filter web test -- admin.webhooks.test.tsx`

### Task 8. admin.webhooks.$id.deliveries 이력 라우트

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/routes/admin.webhooks.$id.deliveries.tsx`, `apps/web/src/routes/admin.webhooks.$id.deliveries.test.tsx`]
- depends-on: [2, 3, 6]

**RED/GREEN**: `Page`+`RouteAdapter`(useParams로 id 추출→page에 props, workflows.$key adapter 선례). useWebhookDeliveriesQuery + WebhookDeliveryTable + size 기반 prev/next + "목록으로" 네비. MSW 이력 렌더 검증.

**REFACTOR**: JSDoc, 라우트 등록 예시 주석.

**검증**: `pnpm --filter web test -- admin.webhooks.$id.deliveries.test.tsx`

### Task 9. router.ts 라우트 등록 + Header admin 메뉴 게이팅

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/router.ts`, `apps/web/src/router.test.tsx`, `apps/web/src/components/Header.tsx`, `apps/web/src/components/Header.test.tsx`]
- depends-on: [7, 8]

**RED**: `router.test.tsx` + `Header.test.tsx`
- `/admin/webhooks`·`/admin/webhooks/$id/deliveries` 등록, `beforeLoad: composeGuards(requireAuth, requireSystemAdmin)`.
- 비-admin 진입→/dashboard 리다이렉트(EC-8). Header에 admin일 때만 "Webhooks" 메뉴 노출, 비-admin 미노출.

**GREEN**: `router.ts`에 `createRoute`×2(RouteAdapter 참조) + `Header.tsx` admin 메뉴 항목(`isSystemAdmin===true`).

**REFACTOR**: 라우트 상수 정리.

**검증**: `pnpm --filter web test -- router.test.tsx Header.test.tsx` + `pnpm --filter web typecheck`

### Task 10. E2E happy path (Playwright)

**메타**.
- agent: `qa-engineer`
- files: [`apps/web/e2e/webhook.spec.ts`]
- depends-on: [9]

**RED/GREEN**: admin fixture로 `/admin/webhooks` 진입→목록·생성·수정·삭제·이력 조회, 비-admin 차단(리다이렉트). MSW 상태 변화는 SPA 내부 라우팅으로 확인(reload 금지 EC-7). getByRole exact/컨테이너 스코프(strict mode), userEvent 긴 입력 delay:null.

**검증**: `pnpm --filter web test:e2e -- webhook.spec.ts` + 기존 e2e 무회귀 스모크.

## Plan 메타

- task 수: 10
- 예상 wave: 5 (W1: T1 / W2: T2·T3·T4·T5·T6 5병렬 / W3: T7·T8 2병렬 / W4: T9 / W5: T10 E2E)
- 예상 시간: 직렬 ~30분, 병렬 wave 적용 시 ~12분
- TDD 강제: yes (모든 task RED→GREEN→REFACTOR, test 커밋이 feat 커밋보다 선행)
- agent: frontend-engineer(T1~T9) + qa-engineer(T10)
- 백엔드 변경: 0 (순수 프론트, 마이그레이션 0, FR 총수 123 불변)
- 추가 검증: `pnpm --filter web verify`(lint+typecheck+test+build) + E2E
- 공유 파일 주의: `mocks/handlers.ts`(T3 단독 등록), `router.ts`/`Header.tsx`(T9 단독) — 파일 겹침으로 자동 직렬화, wave 충돌 없음

## 리뷰 결과

### plan-design-review (2026-07-01) — 텍스트 리뷰(mockup 스킵, Maxi 확정)

초기 7.5/10 → 보강 후 9/10. 기존 admin 화면 4종과 동형이라 새 비주얼 언어 0, AI slop 리스크 없음(App UI). gap은 전부 **기존 관례 명문화**라 진짜 갈림길 없음(미해결 결정 0).

| Pass | 차원 | 전 | 후 | 조치 |
|---|---|---|---|---|
| 1 | 정보계층 | 6 | 9 | 테이블 컬럼 우선순위 명시(D1 보강) |
| 2 | 상태 커버리지 | 7 | 9 | interaction state table 추가(D2 보강) |
| 3 | 사용자 여정 | 8 | 8 | admin 내부 도구, finding 없음 |
| 4 | AI slop | 9 | 9 | App UI·기존 패턴 재사용, finding 없음 |
| 5 | 디자인 시스템 | 9 | 9 | DESIGN.md 정렬+컴포넌트 재사용, 강점 |
| 6 | 반응형/a11y | 6 | 9 | a11y 명시 추가(D3 보강) |
| 7 | 미해결 결정 | — | — | 삭제확인=인라인 관례로 해소, 0건 |

**디자인 보강 (plan 반영 — 각 Task 구현 시 준수)**.
- **D1 정보계층(Task 4/6 테이블)**. App UI 계층 규칙: `name`이 1차 시각 앵커(굵게), `url`은 2차(truncate+title), 이벤트/`enabled`/`hasSecret`/시각은 3차 메타. 좁은 화면은 컨테이너 가로 스크롤(카드 변환 금지 — 데이터 밀도 유지). 이력 테이블은 status 배지가 좌측 앵커(스캔 우선).
- **D2 인터랙션 상태 테이블(Task 4/5/6/7)**.

  | 화면 | 로딩 | 빈 상태 | 에러 | 성공 |
  |---|---|---|---|---|
  | 구독 목록 | 스켈레톤 행(AuditLogTable isLoading 관례) | "등록된 Webhook이 없습니다" + "새 구독" 주요 액션 | 재시도 안내(query error) | 목록 즉시 반영(invalidate) |
  | 생성/수정 폼 | 제출 버튼 spinner+disabled | — | submitError 배너(400/409/403) | 폼 닫힘+목록 갱신 |
  | 발송 이력 | 스켈레톤 행 | "발송 이력이 없습니다"(중립) | 재시도 안내 | 최신순 표시 |
- **D3 접근성(전 Task)**. 삭제/토글 버튼 `aria-label`(NotificationPolicyTable 관례), 키보드 포커스 순서(폼 필드→제출), 터치타겟 44px 이상, 본문 대비 4.5:1 이상, status 배지는 색+텍스트 동시(색맹 대비), 폼 라벨 항상 가시(placeholder-as-label 금지).
- **D4 삭제 확인(Task 4)**. 모달 라이브러리 없이 **인라인 확인**(useState "확인"/"취소" 전환, NotificationPolicyTable #121 패턴 재사용). 실수 삭제 방지 + 기존 관례 일관.

**미해결 결정**: 없음(0건). 삭제 확인 UX가 유일 후보였으나 기존 인라인 확인 관례로 확정.

### plan-eng-review / plan-ceo-review

ui 타입 리뷰 체인은 plan-design-review 단독(bts-review-plan Step 2 분기). 순수 프론트+계약 소비라 eng/ceo 리뷰 미해당.

## GSTACK REVIEW REPORT

| Review | Trigger | Why | Runs | Status | Findings |
|--------|---------|-----|------|--------|----------|
| CEO Review | `/plan-ceo-review` | Scope & strategy | 0 | — | n/a (ui 타입 미해당) |
| Eng Review | `/plan-eng-review` | Architecture & tests | 0 | — | n/a (순수 프론트, 계약 소비) |
| Design Review | `/plan-design-review` | UI/UX gaps | 1 | clean | score 7.5/10 → 9/10, 4 decisions (D1~D4 보강) |

**VERDICT:** DESIGN CLEARED — 9/10, 미해결 0. 순수 프론트 UI(계약 소비)라 eng/ceo 리뷰 미해당. 게이트 1 진입 준비 완료.

NO UNRESOLVED DECISIONS
