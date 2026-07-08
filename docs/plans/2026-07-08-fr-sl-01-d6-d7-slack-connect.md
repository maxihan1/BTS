# FR-SL-01 D6 프론트 + D7 E2E — 관리자 Slack 연결 페이지

> slug: fr-sl-01-d6-d7-slack-connect
> type: feature (classify=qa 오판 교정)
> agents: frontend-engineer(UI) · security-engineer(admin-gated backend view-layer) · qa-engineer(E2E)
> 생성: 2026-07-08

## Brief

FR-SL-01 백엔드 코어(D1~D5, PR #244)의 후속. D6 프론트 + D7 E2E.

- **D6 프론트**. `/admin/slack` 시스템 관리자 전용 페이지 — 현재 연결 상태 표시(연결됨/미연결) + 연결/다시연결 버튼 + 콜백 결과 배너(`?installed=` 성공 / `?error=<code>` 실패).
- **D6 백엔드(같은 BC view-layer)**. Bearer 인증 제약(전체 페이지 이동은 Authorization 헤더 미전송)으로 관리자 가드 JSON 엔드포인트 2종 필요.
  - `GET /api/v1/slack/installation` — 연결 상태 `{ connected, teamId?, teamName?, installedAt? }` (users 조인 없음·설치자 이름 제외·botUserId 미표시라 제외).
  - `GET /api/v1/slack/install-url` — 신선한 서명 state 실은 authorize URL `{ url }`.
  - 둘 다 SystemPermissionResolver 관리자 가드.
- **D7 E2E**. MSW 기반 — 관리자 접근/비관리자 게이팅, 연결됨/미연결 상태, `?installed`/`?error` 배너.

**Maxi 확정**. A1(상태 조회 + 연결). 제외(후속): disconnect/revoke(하드삭제 ADR 필요), 설치자 이름 표시(cross-BC users 조인).

**제약/근거**.
- Access token = Bearer 헤더(메모리 store), refresh = HttpOnly 쿠키. 전체 페이지 nav는 Bearer 미전송 → `/slack/install`(SecurityContext 관리자 판정) 직접 nav 시 401. 그래서 authorize URL을 apiFetch로 받아 `window.location.href` 이동.
- 기존 `SlackInstallController`(`/slack/install` 302, `/slack/install/callback` 302)는 그대로 유지(배포 조립/쿠키 경로용). D6은 `/api/v1/slack/*` JSON 경로 신설.
- BC 격리 — identity-access import 0, SystemPermissionResolver 포트만 소비.

## 도메인 정리

- **BC**: slack-integration (기존, FR-SL-01 백엔드 코어로 확립).
- **영향 엔티티**: `SlackInstall`(기존). 신규 엔티티/마이그레이션 **없음** — view-layer 조회 read + UI만.
- **새 용어**: 없음. "연결 상태"·"다시 연결"은 UI 라벨, 도메인 용어 아님. glossary 변경 불필요.
- **기존 결정 충돌**: 없음. 기존 302 `/slack/install`·`/slack/install/callback` 유지 + SPA용 JSON 경로 신설(병존).
- **핵심 결정**: Bearer 인증 제약(전체 페이지 nav는 Authorization 헤더 미전송) → 관리자 가드 JSON 엔드포인트 `GET /api/v1/slack/{installation,install-url}` 신설. authorize URL을 apiFetch로 받아 nav.
- **관련 ADR**: [docs/decisions/2026-07-08-fr-sl-01-d6-slack-connect-page.md](../decisions/2026-07-08-fr-sl-01-d6-slack-connect-page.md) (생성됨) · 선행 [2026-07-07-fr-sl-01-slack-bot-app.md](../decisions/2026-07-07-fr-sl-01-slack-bot-app.md).
- **도메인 리뷰 방식**: 전면 grill-with-docs 스킵. 이미 확립된 BC의 view-layer 후속이라 신규 용어/엔티티 0 — focused 점검(ADR·glossary·domain 노트 대조)으로 대체.

## 스펙

전체 스펙. [docs/specs/2026-07-08-fr-sl-01-d6-d7-slack-connect.md](../specs/2026-07-08-fr-sl-01-d6-d7-slack-connect.md)

핵심 시나리오 3줄 요약.
- 관리자가 `/admin/slack` 진입 → 상태 조회(`GET /api/v1/slack/installation`)로 연결됨/미연결 표시.
- "연결/다시 연결" 클릭 → `GET /api/v1/slack/install-url`(Bearer)로 authorize URL 받아 `window.location.href` 이동(Bearer 제약 회피).
- 콜백 복귀 시 `?installed=`/`?error=` 파싱 → 결과 배너(에러 코드 7종 매핑 + fallback), 쿼리 정리.

API 2종(관리자 가드) + 신규 read projection `SlackInstallationView`(토큰 미로드) + repo `findCurrentInstallation`. 마이그레이션 0. Header adminLinks에 nav 링크(FR7).

## Brainstorming Check

✅ 통과 (1회 iteration). gap 1건 = 발견성(nav 링크) → Header `adminLinks` 패턴으로 해소(FR7). Maxi 결정 gap 없음.

## Plan

> 8 task · 4 wave. 백엔드(T1→T2→T3, security-engineer) + 프론트(T4→T5→T7 / T6, frontend-engineer) + E2E(T8, qa-engineer).
> TDD red→green→refactor 강제. 파일 겹침 없음(같은 wave 병렬 안전).

### Task 1. Repository 현재 설치 조회 + 토큰 미로드 projection

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/slack-integration/src/main/kotlin/com/bts/slack/application/SlackInstallRepository.kt`, `backend/modules/slack-integration/src/main/kotlin/com/bts/slack/application/SlackInstallationView.kt`, `backend/modules/slack-integration/src/main/kotlin/com/bts/slack/persistence/JdbcSlackInstallRepository.kt`, `backend/modules/slack-integration/src/test/kotlin/com/bts/slack/persistence/JdbcSlackInstallRepositoryTest.kt`]
- depends-on: []

**RED**. `JdbcSlackInstallRepositoryTest`(Testcontainers)에 추가.
- `findCurrentInstallation` 이 설치 없을 때 null 반환.
- 두 워크스페이스 upsert 후 `installed_at` 최신 1건(`team_id`·`team_name`·`installedAt`) 반환.
- 반환 projection 에 **봇 토큰 컬럼이 없음**(타입 상 `SlackInstallationView` 는 token 필드 부재로 컴파일 보장).
- 실패(예상): `SlackInstallationView`/`findCurrentInstallation` 미존재.

**GREEN**.
- `SlackInstallationView(teamId: String, teamName: String, installedAt: Instant)` data class 신설(L1 한국어 주석).
- `SlackInstallRepository.findCurrentInstallation(): SlackInstallationView?` 인터페이스 추가.
- Jdbc 구현. `SELECT team_id, team_name, installed_at FROM slack_installs ORDER BY installed_at DESC LIMIT 1` (DATA.md §5 `?` 무파라미터, RowMapper 로 3필드만). `installed_at` → `Instant`.

**REFACTOR**. SQL 상수화 + KDoc(왜 projection 인지 = 토큰 미로드 방어). 
**검증**. `./gradlew :backend:modules:slack-integration:test --tests '*JdbcSlackInstallRepositoryTest*'` (모듈 경로는 settings.gradle 확인 후 확정).

**주의**. `slack_installs` 에 `installed_at` 컬럼 실재 확인(V700). 부재 시 db-engineer 협의(스펙 §데이터모델은 존재 가정).

### Task 2. Service getInstallation — 관리자 가드 + 조회

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/slack-integration/src/main/kotlin/com/bts/slack/application/SlackInstallService.kt`, `backend/modules/slack-integration/src/test/kotlin/com/bts/slack/application/SlackInstallServiceTest.kt`]
- depends-on: [1]

**RED**. `SlackInstallServiceTest`(mock `SystemPermissionResolver` + `SlackInstallRepository`)에 추가.
- 관리자 + 설치 존재 → `SlackInstallationStatus(connected=true, teamId, teamName, installedAt)`.
- 관리자 + 미설치 → `connected=false`, 나머지 null.
- 비관리자 → `SlackForbiddenException`(조회 이전 가드, repo 미호출 verify).
- 가드가 repo 조회보다 **먼저**(auth-extraction-before-lookup) — 비관리자 시 `repository.findCurrentInstallation` 미호출 검증.

**GREEN**.
- `SlackInstallationStatus(connected, teamId, teamName, installedAt)` 결과 타입(nullable 필드).
- `fun getInstallation(actorId: UUID): SlackInstallationStatus` — `isSystemAdmin` false → `SlackForbiddenException()`; true → `findCurrentInstallation()?.let{연결됨} ?: 미연결`.
- `startInstall(actorId)` 은 install-url 용으로 그대로 재사용(변경 없음).

**REFACTOR**. KDoc(가드 순서·비-비밀 필드만). 
**검증**. `--tests '*SlackInstallServiceTest*'`.

### Task 3. Query 컨트롤러 2 엔드포인트 + 예외핸들러 스코프 + test-boot SecurityConfig

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/slack-integration/src/main/kotlin/com/bts/slack/web/SlackInstallQueryController.kt`, `backend/modules/slack-integration/src/main/kotlin/com/bts/slack/web/SlackInstallQueryResponses.kt`, `backend/modules/slack-integration/src/main/kotlin/com/bts/slack/web/SlackInstallExceptionHandler.kt`, `backend/modules/slack-integration/src/main/kotlin/com/bts/slack/web/SlackInstallController.kt`, `backend/modules/slack-integration/src/main/kotlin/com/bts/slack/application/SlackInstallExceptions.kt`, `backend/modules/slack-integration/src/main/kotlin/com/bts/slack/application/SlackInstallService.kt`, `backend/modules/slack-integration/src/test/kotlin/com/bts/slack/SlackTestSecurityConfig.kt`, `backend/modules/slack-integration/src/test/kotlin/com/bts/slack/web/SlackInstallQueryControllerIntegrationTest.kt`, `backend/modules/slack-integration/src/test/kotlin/com/bts/slack/web/SlackInstallIntegrationTest.kt`]
- depends-on: [2]

**RED**. `SlackInstallQueryControllerIntegrationTest`(test-boot + Testcontainers, `SlackInstallIntegrationTest` 미러).
- `GET /api/v1/slack/installation` — 관리자 미설치 200 `{connected:false,...null}`; 설치 후 200 `{connected:true, teamName, teamId, installedAt(ISO)}`; **응답 본문에 `xoxb`/암호문/installedBy 부재 실증**.
- 관리자 아님 → 403; 미인증 → 401.
- `GET /api/v1/slack/install-url` — 관리자 200 `{url}`(`https://slack.com/oauth/v2/authorize` 접두 + `state=` 포함); 비관리자 403.
- 실패(예상): 컨트롤러 미존재 → 404, 또는 존재해도 `SlackForbiddenException` 이 500(핸들러 스코프 밖)로 새어 RED 확인.

**GREEN**.
- `SlackInstallQueryController`(`com.bts.slack.web`) — `GET /api/v1/slack/installation` → `SlackActorExtractor.extract()`(401) → `service.getInstallation(actorId)`(403) → `SlackInstallationResponse`. `GET /api/v1/slack/install-url` → extract → `service.startInstall(actorId)`(403) → `SlackInstallUrlResponse(url)`.
- 응답 DTO(`SlackInstallationResponse(connected, teamId, teamName, installedAt)`, `SlackInstallUrlResponse(url)`). `installedAt: Instant?` → Jackson ISO(배열 아님, full-boot JavaTimeModule) 확인.
- **★예외핸들러 확장**. `SlackInstallExceptionHandler` `assignableTypes = [SlackInstallController::class, SlackInstallQueryController::class]` (교훈 domain-exception-http-handler-basepackage-scope — 스코프 밖이면 403→500 변질). 401(ResponseStatusException)·403(SlackForbiddenException) 둘 다 두 컨트롤러 커버 확인.
- **test-boot SecurityConfig**. `SlackTestSecurityConfig` 에 `/api/v1/slack/**` authenticated 추가(콜백 permitAll 와 별개, 관리자 가드 실행되도록).
- **★라우트 상수 변경(게이트 1 결정)**. `SlackInstallController.FRONT_RESULT_PATH = "/admin/slack"`(기존 `/settings/slack`). RED = `SlackInstallIntegrationTest` 5개 assertion(`Location` 헤더 `/settings/slack…` → `/admin/slack…`) 먼저 갱신해 실패 확인 → GREEN = 상수 변경. `SlackInstallController`/`SlackInstallExceptions`/`SlackInstallService` KDoc 내 `/settings/slack` 문자열 3곳도 `/admin/slack`으로 동기화. 콜백 302 로직 자체는 불변.

**REFACTOR**. KDoc(Bearer 제약·302 흐름과의 관계) + ktlint/detekt(신규 파일 MaxLineLength·detektMain type-resolved) 확인.
**검증**. `--tests '*SlackInstallQueryController*'` + `ktlintCheck detekt --rerun-tasks`(캐시 false-green 방지).

### Task 4. 프론트 Zod 스키마 + API 함수

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/api/slack.ts`, `apps/web/src/api/slack.test.ts`]
- depends-on: []

**RED**. `slack.test.ts`.
- `SlackInstallationSchema.parse` — 연결됨/미연결 두 형태 통과, 필드 누락 시 throw.
- `SlackInstallUrlSchema.parse` — `{url}` 통과.
- `getSlackInstallation()`/`getSlackInstallUrl()` 가 올바른 경로(`/api/v1/slack/installation`·`/install-url`)로 `apiGet` 호출(mock).

**GREEN**.
- `apps/web/src/api/slack.ts`(L1 한국어 주석) — Zod 스키마 2종 + `getSlackInstallation`/`getSlackInstallUrl`(`apiGet` 사용). backend DTO 계약 그대로(invent 금지, 스펙 §API 기준). `installedAt` 은 `.string().nullable()`(ISO), connected `boolean`.

**REFACTOR**. 타입 export(`SlackInstallation`, `SlackInstallUrl`).
**검증**. `pnpm --filter web test slack.test.ts` + `pnpm --filter web typecheck`.

### Task 5. SlackConnectionCard — 상태 표시 + 연결 버튼

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/settings/SlackConnectionCard.tsx`, `apps/web/src/components/settings/SlackConnectionCard.test.tsx`]
- depends-on: [4]

**RED**. 컴포넌트 테스트(`getSlackInstallation`/`getSlackInstallUrl` mock).
- 연결됨 → teamName + installedAt(로캘 포맷) + `[다시 연결]`.
- 미연결 → 안내문 + `[Slack에 연결]`.
- 로딩 상태 → 스켈레톤/플레이스홀더.
- 버튼 클릭 → `getSlackInstallUrl` 호출 후 `window.location.assign(url)` 실행(assign mock 로 인자 검증).
- install-url 조회 실패 → 인라인 오류 + 페이지 유지.

**GREEN**. `SlackConnectionCard`(useQuery 로 installation 로드, card.tsx/button.tsx 재사용). 버튼 핸들러=`getSlackInstallUrl()` await → `window.location.assign(url)`.

**REFACTOR**. installedAt 포맷은 기존 date 표시 유틸 재사용. 
**검증**. `pnpm --filter web test SlackConnectionCard`.

### Task 6. SlackResultBanner — 콜백 결과 배너 + 에러 코드 매핑

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/settings/SlackResultBanner.tsx`, `apps/web/src/components/settings/SlackResultBanner.test.tsx`]
- depends-on: []

**RED**. 컴포넌트 테스트.
- `?installed=Acme%20Corp` → `role="alert"` 성공 "Acme Corp 워크스페이스에 연결되었습니다".
- `?error=access_denied|invalid_state|missing_params|exchange_failed|unsupported_install_type|missing_access_token|invalid_code` → 각 한국어 메시지(스펙 §에러 매핑).
- 미지원 코드 `?error=weird_xyz` → 일반 fallback(코드 원문 미노출).
- `installed`+`error` 동시 → error 우선.
- 파라미터 없음 → 배너 미렌더(null).
- 배너 표시 후 쿼리 정리(navigate replace, mock 으로 호출 검증).

**GREEN**. `SlackResultBanner` + `slackErrorMessages` 맵(스펙 표). TanStack Router `useSearch` 로 `installed`/`error` 읽고, `useEffect` 로 표시 후 `navigate({ search:{}, replace:true })` 정리.

**REFACTOR**. 메시지 맵 상수 분리 + 기본 메시지 fallback 명시.
**검증**. `pnpm --filter web test SlackResultBanner`.

### Task 7. 페이지 + 라우트 + 라우터 등록 + Header nav 링크

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/routes/admin.slack.tsx`, `apps/web/src/router.ts`, `apps/web/src/components/Header.tsx`, `apps/web/src/components/Header.test.tsx`, `apps/web/src/routes/__tests__/admin.slack.test.tsx`]
- depends-on: [5, 6]

**RED**.
- `admin.slack.test.tsx` — 페이지가 `SlackConnectionCard` + `SlackResultBanner` 렌더.
- `Header.test.tsx` — isSystemAdmin=true 시 관리 메뉴에 "Slack 연결"(`/admin/slack`) 링크; 비관리자 시 부재.

**GREEN**.
- `routes/admin.slack.tsx`(L1 주석) — `SlackConnectionSettingsPage`(배너+카드, `mx-auto max-w-2xl` 관례) + `SlackConnectionSettingsRouteAdapter`. `validateSearch`로 `installed?`/`error?` 노출(T6 배너가 `useSearch`로 읽고 정리).
- `router.ts` — `adminSlackRoute`(`path:'/admin/slack'`, `beforeLoad: composeGuards(requireAuth, requireSystemAdmin)`) 등록(상단 카운트 주석 갱신, admin.webhooks 선례).
- `Header.tsx` — `adminLinks` 배열에 `{ to:'/admin/slack', label:'Slack 연결' }` 추가.

**REFACTOR**. 페이지 제목/설명 카피 정리.
**검증**. `pnpm --filter web test settings.slack Header` + `typecheck`.

**주의**. `router.ts`·`Header.tsx` 는 병렬 worktree `fr-pf-02-start-page` 와 잠재 충돌(둘 다 route 추가). 본 PR 내부엔 T7 만 수정 → intra-PR 충돌 없음. 머지 시 rebase 확인.

### Task 8. MSW 핸들러 + D7 E2E

**메타**.
- agent: `qa-engineer`
- files: [`apps/web/src/mocks/slack-handlers.ts`, `apps/web/src/mocks/handlers.ts`, `apps/web/e2e/slack-connect.spec.ts`]
- depends-on: [7]

**RED/GREEN(E2E는 시나리오 기반)**.
- `slack-handlers.ts` — `GET /api/v1/slack/installation`(연결됨/미연결 **localStorage 플래그 토글**, 교훈 e2e-msw-scenario-toggle-localstorage-flag) + `GET /api/v1/slack/install-url`(`{url}` stub). `handlers.ts` 에 등록.
- `slack-connect.spec.ts` — (1) 관리자 미연결 진입(`/admin/slack`) → `[Slack에 연결]` 노출. (2) 연결됨 토글 → teamName 표시 + `[다시 연결]`. (3) `/admin/slack?installed=Acme` → 성공 배너. (4) `/admin/slack?error=invalid_state` → 오류 배너. (5) 비관리자(whoami isSystemAdmin=false 토글) → `/dashboard` 리다이렉트.

**검증**. `pnpm --filter web test:e2e slack-connect`. E2E 후 orphan vite(5173) 정리(교훈 e2e-orphan-vite).

## Plan 메타

- task 수: 8
- wave: 4 (W1=T1·T4·T6 / W2=T2·T5 / W3=T3·T7 / W4=T8). longest path=4(프론트 T4→T5→T7→T8).
- 예상 시간: 직렬 ~24분, wave 병렬 ~12분.
- TDD 강제: yes (test 커밋 선행 자동 검증).
- 추가 검증: ktlintCheck·detekt(--rerun-tasks)·ArchUnit(BC 격리) / vitest·typecheck / playwright.
- 파일 겹침: 없음(같은 wave 내). router.ts·Header.tsx 는 T7 단독.

## 리뷰 결과

> 저위험 plan(마이그레이션 0·기존 302 흐름 불변·같은 BC view-layer) → autoplan 4-phase 대체, **eng+design 집중 리뷰**(교훈 bts-review-plan-autoplan-overkill).

### eng-review (2026-07-08)

- ✅ **예외핸들러 스코프**. T3의 `assignableTypes` 확장이 정확한 수정. 미포함 시 403→500 변질(교훈 반영).
- ✅ **가드 순서**. actor 추출(401) → 관리자(403) → 조회. auth-extraction-before-lookup 준수.
- ✅ **토큰 미노출**. projection이 `botTokenEncrypted`를 아예 로드 안 함. 통합테스트 부재 실증. 방어적 우수.
- ✅ **install-url 재사용**. `startInstall` 그대로 재사용(신규 가드 로직 0). 매 클릭 신선 state(10분 exp).
- ✅ **V700 `installed_at` 실재 확인** — 마이그레이션 불필요 확정.
- ✅ **wave/의존성**. longest path=4, 같은 wave 파일 겹침 0. 정확.
- ⚠️ **주의(Instant ISO)**. T3는 full test-boot 통합(슬라이스 아님)이라 Jackson JavaTimeModule ISO 직렬화가 기본. 그래도 응답 assertion에서 ISO 문자열 형식 명시 확인(교훈 enablewebmvc-slice-localdate-array-serialization는 @EnableWebMvc 슬라이스 한정 — 본 케이스 무관하나 방어).
- ⚠️ **주의(TanStack search)**. 쿼리 정리(T6 banner `navigate({search:{}, replace:true})`)를 위해 T7 라우트가 `validateSearch`로 `installed?`/`error?`를 노출해야 함. T6/T7 계약 명시(구현 시 coordinate).
- BLOCKER: 없음.

### design-review (2026-07-08)

- ✅ **페이지 일관성**. `mx-auto max-w-2xl` settings 레이아웃 + card/button 재사용. settings.preferences/profile 관례 일치.
- ✅ **상태 카드**. 연결됨(teamName·installedAt·다시 연결) / 미연결(안내·Slack에 연결) 분기 명확.
- ✅ **결과 배너**. `role="alert"` 상단, 성공/실패, 쿼리 정리로 새로고침 재표시 차단. admin.webhooks 배너 관례.
- ❓ **라우트 위치(taste — Maxi 결정)**. 계획은 `/settings/slack`(백엔드 `FRONT_RESULT_PATH` 상수 존중). 그러나 **기존 관리자 페이지는 전부 `/admin/*`**(webhooks·notification-policies·audit-logs·workflow-schemes), `/settings/*`는 전부 사용자 개인 설정. Slack 워크스페이스 연결은 조직-레벨 관리자 설정이라 `/admin/slack`이 관례상 더 일관. 변경 비용=백엔드 상수 1 + KDoc 3 + 통합테스트 assertion 5(전부 slack 모듈, 본 PR이 이미 T3에서 수정). **지금이 URL 변경 최저비용 시점**(페이지 아직 어디에도 없음). → 게이트 1에서 Maxi 결정.
- BLOCKER: 없음.
