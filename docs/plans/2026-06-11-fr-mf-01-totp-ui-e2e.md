# FR-MF-01 TOTP 2FA 프론트엔드 UI + E2E

> slug: fr-mf-01-totp-ui-e2e
> type: auth (산출물은 frontend UI + E2E)
> agent: security-engineer (가이드) → frontend-engineer / qa-engineer (구현)
> 생성: 2026-06-11

## Brief

FR-MF-01 (TOTP, Authenticator 앱 기반 2FA) 의 **프론트엔드 UI + E2E** 작업.
백엔드는 PR #113 (`5e1c8d03`) 에서 완료된 2-PR 분할의 프론트 PR.

백엔드 API 흐름 (메모리 fr-mf-01-totp-backend-done + history #113):
- `setup` (QR + secret_base32 PENDING)
- `enable` (코드 검증 → ACTIVE)
- 로그인 2단계 — TOTP 활성 시 정식 세션 대신 `200 {mfa_required:true, mfa_challenge_token}` (5분 챌린지 JWT)
- `POST /api/v1/auth/mfa/verify {mfa_challenge_token, code}` → 정식 세션 `mfa_verified=true`
- `disable` (step-up 코드 검증)

산출물:
- `/settings/mfa` 설정 페이지 — QR 코드 표시 · secret 표시 · enable · disable
- 로그인 2단계 — pw 통과 후 `mfa_required` 시 TOTP 코드 입력 화면
- E2E — setup→enable→로그인 2단계 verify→세션 / disable 시나리오

FR-IS-10 선례처럼 이 프론트 PR 머지 시 FR-MF-01 완료 마킹 (현재 122/122 무변경).

## 도메인 정리

- BC: identity-access (프론트 view layer, apps/web)
- 영향 엔티티: 없음 (백엔드 #113에서 도메인 확립. 이 작업은 백엔드 MFA 계약을 프론트에 미러링)
- 새 용어: 없음 (TOTP / 2FA / 챌린지 토큰은 백엔드 #113에서 도입 완료)
- 기존 결정 충돌: 없음
- 관련 ADR: 없음 (계약 미러링 작업 — 신규 결정 없음)

### 백엔드 API 계약 (코드 전수 조사로 확정 — 추측 0)

| 엔드포인트 | 메서드 | 인증 | 요청 | 응답(성공) | 응답(실패) |
|---|---|---|---|---|---|
| `/api/v1/auth/mfa/totp/setup` | POST | JWT | (없음) | 200 `{otpauth_uri, qr_png_data_uri, secret_base32}` | 409 `{error:"already_enabled"}` |
| `/api/v1/auth/mfa/totp/enable` | POST | JWT | `{code}` | 204 | 400 invalid_code / 409 no_pending_setup / 429 too_many_attempts |
| `/api/v1/auth/mfa/totp` | GET | JWT | — | 200 `{enabled}` | — |
| `/api/v1/auth/mfa/totp` | DELETE | JWT | `{code}` | 204 | 400 invalid_code / 404 not_enabled / 429 too_many_attempts |
| `/api/v1/auth/mfa/verify` | POST | permitAll | `{mfa_challenge_token, code}` | 200 `{access_token, token_type, expires_in}` | 401 invalid_code / 429 too_many_attempts |
| `/api/v1/auth/login` (TOTP 활성) | POST | permitAll | 기존 `{provider, username, password}` | 200 `{mfa_required:true, mfa_challenge_token, expires_in:300}` | 기존 동일 |

- **필드명 전부 snake_case** (`@JsonProperty` 명시): otpauth_uri, qr_png_data_uri, secret_base32, mfa_required, mfa_challenge_token, access_token, token_type, expires_in. 단 `code`, `enabled`는 그대로.
- 응답 wrapper 없음 (flat). MFA 엔드포인트는 `{data:...}` 미사용.
- 에러 형식 통일: `{error: "<code>"}`.

### 핵심 설계 결정 (도메인 정리에서 도출)

1. **QR 렌더링 = 백엔드 PNG data URI 직접 표시 (`<img src={qr_png_data_uri}>`)**. 백엔드가 `qr_png_data_uri`를 PNG data URI로 생성·응답하므로 프론트 QR 라이브러리(qrcode.react 등) **의존성 추가 불필요** → 절대 규칙 #17(외부 의존성 Maxi 승인) 회피. `secret_base32`는 QR 스캔 불가 환경용 수동입력 fallback 표시. (게이트1 Maxi 확인 항목)
2. **로그인 2단계 분기 교체**: 현재 `useLoginMutation`이 `mfa_required`를 에러 메시지로 처리 → MfaRequiredResponse 감지 시 챌린지 토큰 보관 + TOTP 코드 입력 화면 전환으로 교체.
3. **챌린지 토큰 보관 위치**: 5분 단명 + 정식 세션 전. authStore(sessionStorage persist)에 영속 금지 — 컴포넌트/메모리 상태로 한정(로그인 흐름 내에서만 유효).

### 따라야 할 프론트 선례

- 설정 페이지: `apps/web/src/routes/settings.password.tsx` (Page + RouteAdapter, `mx-auto max-w-2xl px-4 py-8`)
- API: `apps/web/src/api/client.ts` (apiPost/apiGet + Zod), `apps/web/src/api/schemas.ts` (snake_case Zod)
- 로그인 분기: `apps/web/src/auth/LoginForm.tsx`, `apps/web/src/auth/useLoginMutation.ts`
- 라우터: `apps/web/src/router.ts` (code-based, RouteAdapter), 가드 `apps/web/src/auth/routeGuard.ts` (requireAuth)
- UI: `apps/web/src/components/ui/*` (radix-ui 직접), Dialog는 `Dialog as DialogPrimitive` (선례 `components/auth/ReauthDialog.tsx`)
- i18n: `apps/web/src/i18n/ko.ts` (mfaStrings 신규)
- MSW: `apps/web/src/mocks/auth-handlers.ts` + `auth-fixtures.ts` (localStorage E2E 토글)
- E2E: `apps/web/e2e/login-happy-path.spec.ts` (getByRole/getByLabel, addInitScript)

## 스펙

전체 스펙. [docs/specs/2026-06-11-fr-mf-01-totp-ui-e2e.md](../specs/2026-06-11-fr-mf-01-totp-ui-e2e.md)

핵심 시나리오 요약.
- `/settings/mfa`에서 status 조회 → 활성/비활성 분기. 활성화 = setup(QR PNG `<img>` + secret_base32 표시) → 코드 입력 → enable → 활성 상태.
- 비활성화 = step-up 코드 입력 → disable → 비활성 상태.
- 로그인 2단계 = login 200 응답을 `mfa_required`로 discriminated union 분기(LoginForm 내부 step), MFA 코드 입력 → verify → 기존 성공 핸들러 재사용(세션+whoami+returnTo).
- QR은 백엔드 PNG data URI 직접 표시(의존성 0). 에러 6종 한국어 매핑 + rate-limit/만료 UX.

## Brainstorming Check

✅ 통과 (1회 iteration).
- G1 — MFA 코드 입력 화면 위치 → LoginForm 내부 step + 기존 성공 핸들러 재사용(NFR-1 영속 금지 충족).
- G2 — E2E TOTP 코드 검증 → MSW 고정 유효코드(`123456`) stateful mock(실 알고리즘 불요).
- 게이트1 Maxi 확인 항목: QR 렌더 방식(백엔드 PNG 채택).

## Plan (← /bts-plan 채움)

## Plan

> 모든 경로는 `apps/web/` 기준. 백엔드 변경 없음. TDD red→green→refactor 강제.

### Task 1. MFA API 클라이언트 + Zod 스키마

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/api/schemas.ts`, `apps/web/src/api/mfa.ts`, `apps/web/src/api/mfa.test.ts`]
- depends-on: []

**RED**: `apps/web/src/api/mfa.test.ts`
- `setupMfa()` → `POST /api/v1/auth/mfa/totp/setup`, 응답 `{otpauth_uri, qr_png_data_uri, secret_base32}` Zod parse.
- `getMfaStatus()` → `GET /api/v1/auth/mfa/totp` → `{enabled}`.
- `enableMfa(code)` → `POST .../enable {code}` → 204(본문 없음).
- `disableMfa(code)` → `DELETE .../totp {code}` → 204.
- `verifyMfa(token, code)` → `POST /api/v1/auth/mfa/verify {mfa_challenge_token, code}` → TokenResponse.
- login 응답 union: `mfa_required` 분기 스키마 parse 테스트(TokenResponse vs MfaRequiredResponse).
- **(BLOCKER-CSRF)** setup/enable/disable 요청에 **`X-XSRF-TOKEN` 헤더가 포함**되는지 검증(선례 `users.test.ts` T-US-4b `capturedXsrf` 패턴). verify/status 요청에는 CSRF 헤더 **없음**을 검증(verify=permitAll+ignore, status=읽기).
- MSW로 응답 mock. 실패 메시지(예상): `mfa.ts`/스키마 없음.

**GREEN**:
- `schemas.ts`에 `MfaSetupResponseSchema`, `MfaStatusResponseSchema`, `MfaRequiredResponseSchema`(snake_case 필드 그대로), `LoginOrMfaResponseSchema`(discriminated union, `mfa_required` 유무로 분기).
- `mfa.ts`에 5개 함수. **(BLOCKER-CSRF 해소)** client.ts의 `apiPost`/`apiGet`은 CSRF 토큰을 자동 주입하지 **않으므로**, 상태 변경 호출은 `password.ts` 선례대로 `apiFetch` + 수동 `'X-XSRF-TOKEN': readXsrfToken()`(`sessions.ts` 공유 헬퍼, 중복 구현 금지)으로 작성:
  - `setupMfa()` POST → apiFetch + X-XSRF-TOKEN
  - `enableMfa(code)` POST → apiFetch + X-XSRF-TOKEN + body `{code}`
  - `disableMfa(code)` DELETE → apiFetch + X-XSRF-TOKEN + body `{code}`
  - `getMfaStatus()` GET → `apiGet`(읽기, CSRF 불요)
  - `verifyMfa(token, code)` POST → `apiPost`(permitAll+CSRF-ignore 경로, **X-XSRF-TOKEN 넣지 말 것** — 챌린지 토큰이 인증 증명)
  - enable/disable는 204라 Zod parse 없이 ok 처리.

**REFACTOR**: 에러 코드 문자열 상수화(`invalid_code` 등), 함수 JSDoc.

**검증**: `pnpm test src/api/mfa`, `pnpm typecheck`

---

### Task 2. i18n mfaStrings + 에러 코드 매핑

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/i18n/ko.ts`, `apps/web/src/i18n/ko.test.ts`(있으면 수정, 없으면 미생성)]
- depends-on: []

**RED**: `mfaStrings` 키(설정 제목/설명, 활성화·비활성화 버튼, QR 안내, 코드 라벨, 로그인 2단계 안내 등) 존재 + `mfaErrorMessage(code)`가 6종 코드(`invalid_code`/`too_many_attempts`/`no_pending_setup`/`already_enabled`/`not_enabled` + fallback)를 한국어로 매핑하는 테스트.

**GREEN**: `mfaStrings` 객체 + `mfaErrorMessage` 매핑 함수(원인 과노출 금지, 일반 메시지). `loginStrings`와 동일 패턴.

**REFACTOR**: 메시지 톤 일관화(콜론 종결 금지 — 글로벌 §5).

**검증**: `pnpm test src/i18n`(테스트 있을 때), `pnpm typecheck`

---

### Task 3. MFA 설정 화면(`/settings/mfa`) + 라우터/진입점 등록

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/auth/MfaSettings.tsx`, `apps/web/src/components/auth/MfaSettings.test.tsx`, `apps/web/src/routes/settings.mfa.tsx`, `apps/web/src/router.ts`, (설정 네비/메뉴 진입점 파일 — impl서 grep 확정)]
- depends-on: [1, 2]

**RED**: `MfaSettings.test.tsx`(vitest + testing-library, MSW)
- 미활성(`enabled:false`) → "비활성화됨" + 활성화 버튼.
- 활성화 클릭 → setup 호출 → QR `<img>`(alt 존재) + secret_base32 + 코드 입력 필드 노출.
- 코드 입력 → enable → 성공 시 status refetch로 활성 상태 전환 + QR/secret 화면 정리.
- 활성(`enabled:true`) → 비활성화 버튼 → step-up 코드 → disable → 비활성 전환.
- 에러: enable 400 `invalid_code` → 인라인 에러(필드 유지), 429 → rate-limit 메시지.

**GREEN**: `MfaSettings` 컴포넌트(useQuery status + useMutation setup/enable/disable, mutation 성공 후 invalidate/refetch — 메모리 mutation-setquerydata-partial-response-flicker 따라 invalidate-only). **(CONCERN-state)** enable 204 성공 후 setup 응답(`secret_base32`/`qr_png_data_uri`/`otpauth_uri`)을 담은 컴포넌트 state를 **명시적으로 `null` 리셋**(조건부 렌더로 숨기기만 하면 state·메모리에 secret 잔존). enable/disable 코드 입력 폼도 성공/실패 후 reset. `settings.mfa.tsx`(Page + RouteAdapter, `settings.password.tsx` 레이아웃). `router.ts`에 `settingsMfaRoute`(requireAuth 가드, password/account-links 인근). 설정 진입점에 "2단계 인증" 링크.

**REFACTOR**: QR/secret 표시를 하위 컴포넌트로 분리, 코드 입력 폼 공통화.

**검증**: `pnpm test src/components/auth/MfaSettings`, `pnpm typecheck`

---

### Task 4. 로그인 2단계 분기(useLoginMutation union + LoginForm MFA step + verify)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/auth/useLoginMutation.ts`, `apps/web/src/auth/useLoginMutation.test.ts`, `apps/web/src/auth/LoginForm.tsx`, `apps/web/src/auth/LoginForm.test.tsx`]
- depends-on: [1, 2]

**RED**:
- `useLoginMutation.test.ts` — login 200 `mfa_required` 응답을 **에러가 아니라** 2단계 진입 신호로 분기(parse 전 플래그 검사). 기존 `LOGIN_ERROR_MESSAGES.mfa_required` 에러 경로 제거에 따른 테스트 수정.
- **(CONCERN-union)** 음성 테스트 — `mfa_required:true`이면 응답에 `access_token`이 동봉돼 있어도 **MFA step으로 진입(토큰 무시)**. 분기는 반드시 `mfa_required === true` 우선 검사 → true면 `MfaRequiredResponseSchema.parse`, 아니면 `TokenResponseSchema.parse`. "access_token 존재 여부"로 분기 금지(fail-safe, MFA 우회 회귀 차단).
- `LoginForm.test.tsx` — mfa_required 후 MFA 코드 입력 step 렌더(getByLabel 접근), 코드 입력 → verify 호출 → 성공 시 기존 성공 핸들러(세션 저장+whoami+navigate) 호출. verify 401 `invalid_code` → 인라인 에러(필드 유지). 반복 401/만료 → "다시 로그인" 복귀.
- TOTP 미활성 사용자 기존 로그인 흐름 회귀 없음(기존 happy-path 테스트 유지).

**GREEN**: `useLoginMutation`에 union 분기(`LoginOrMfaResponseSchema`). `LoginForm`에 `step: 'mfa'` 상태 + 챌린지 토큰 컴포넌트 메모리 보관(authStore 영속 금지 — NFR-1) + verify mutation + 성공 시 기존 onSuccess 경로 수렴.

**REFACTOR**: MFA step UI를 작은 컴포넌트로, 에러 매핑은 T2 `mfaErrorMessage` 재사용.

**검증**: `pnpm test src/auth/LoginForm src/auth/useLoginMutation`, `pnpm typecheck`

---

### Task 5. MSW MFA 핸들러 + fixture(stateful + E2E 토글)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/mocks/mfa-handlers.ts`, `apps/web/src/mocks/handlers.ts`, `apps/web/src/mocks/auth-handlers.ts`, `apps/web/src/mocks/auth-fixtures.ts`]
- depends-on: [1]

**RED**: `mfa-handlers` 동작 테스트(또는 MfaSettings/E2E에서 소비). 핸들러가 setup/status/enable/disable/verify 응답 + **stateful**(enable 성공 후 status `enabled:true`, disable 후 `false` — 브라우저 시드 가능 공유 store, 메모리 msw-derived-behavior-shared-store-e2e). 고정 유효코드 `123456` 성공/그 외 실패. login 핸들러가 localStorage 플래그(`__bts_e2e_mfa_enabled`)일 때 `mfa_required` 분기. **(BLOCKER-CSRF 가짜그린 차단)** setup/enable/disable 핸들러는 `X-XSRF-TOKEN` 헤더 존재를 검사(선례 field-permission-handlers)해, 프론트가 CSRF 헤더를 빠뜨리면 MSW에서도 실패하도록 — 실서버 403을 단위/E2E가 못 잡는 가짜 그린 방지.

**GREEN**: `mfa-handlers.ts`(공유 store + 핸들러), `handlers.ts`에 등록, `auth-handlers.ts` login에 mfa 분기 토글, `auth-fixtures.ts`에 플래그 키/시드 helper.

**REFACTOR**: 코드 검증 helper 공통화, store 초기화(beforeEach) 정리.

**검증**: `pnpm test`(MSW 의존 테스트 green), `pnpm typecheck`

---

### Task 6. E2E(Playwright) — 활성화/비활성화/로그인 2단계

**메타**.
- agent: `qa-engineer`
- files: [`apps/web/e2e/mfa-settings.spec.ts`, `apps/web/e2e/mfa-login.spec.ts`]
- depends-on: [3, 4, 5]

**RED**: 시나리오 작성(처음엔 실패/미통과).
- `mfa-settings.spec.ts` — 로그인(loginAsAlice 선례) → `/settings/mfa` → 활성화(QR 표시 확인 → 코드 `123456` 입력 → 활성 상태) → 비활성화(step-up 코드 → 비활성 상태).
- `mfa-login.spec.ts` — `addInitScript`로 `__bts_e2e_mfa_enabled` 세팅 → 로그인 1단계 → MFA 코드 입력 화면 → `123456` → dashboard 도달. 실패 1종(잘못된 코드 → 인라인 에러).
- getByRole/getByLabel 사용, 텍스트 중복 시 컨테이너 한정(메모리 playwright-getbyrole-exact-strict-mode).

**GREEN**: 기존 구현/MSW로 통과. CSRF 쿠키 수동 시드·SPA 내부 이동 등 메모리 worktree-stale-base-rebase-and-e2e-msw-traps 준수.

**REFACTOR**: 공통 step helper 추출(가능 시).

**검증**: `pnpm test:e2e -- mfa-settings mfa-login`, 기존 E2E 회귀 0(`pnpm test:e2e`).

## Plan 메타

- task 수: 6
- 예상 wave: 3 (Wave1 T1·T2 / Wave2 T3·T4·T5 / Wave3 T6)
- TDD 강제: yes (프론트 vitest red→green, E2E 시나리오 red→green)
- agent: frontend-engineer(T1~5) + qa-engineer(T6). security-engineer는 plan/codereview에서 인증 흐름 검토.
- 추가 검증: typecheck, lint, vitest, playwright, `pnpm verify`
- 파일 충돌: 없음(T3=components/routes/router, T4=src/auth, T5=src/mocks 분리). schemas.ts=T1만, ko.ts=T2만, router.ts=T3만, handlers.ts=T5만.

## 리뷰 결과

### security-engineer 독립 리뷰 (2026-06-11)

백엔드 계약(#113)·프론트 선례 실측 대조. 7개 보안 관점 점검.

| # | 항목 | 판정 |
|---|---|---|
| 1 | 챌린지 토큰 비영속(authStore 제외) | ✅ PASS |
| 2 | discriminated union 분기 | ⚠️ CONCERN → T4 음성 테스트 반영 |
| 3 | 계정 열거(일반 메시지) | ✅ PASS |
| 4 | **CSRF 헤더 수동 주입** | 🛑 BLOCKER → T1/T5 반영 |
| 5 | rate-limit UX | ✅ PASS |
| 6 | 민감정보 state 정리 | ⚠️ CONCERN → T3 state reset 반영 |
| 7 | E2E 시뮬레이션 적정성 | ✅ PASS |

**🛑 BLOCKER (CSRF) — 해소됨(plan 반영)**. `client.ts`의 apiPost/apiGet/apiFetch는 CSRF 토큰을 자동 주입하지 않음. setup(POST)/enable(POST)/disable(DELETE)은 백엔드 authenticated+CSRF 적용이라 `X-XSRF-TOKEN: readXsrfToken()`(`sessions.ts` 공유, `password.ts` 선례) 수동 주입 필수. verify는 permitAll+CSRF-ignore라 헤더 불필요. → **T1 GREEN/RED 수정**(수동 주입 + 헤더 단위 테스트), **T5 수정**(MSW CSRF 검사로 가짜그린 차단).

**⚠️ CONCERN 2건 — 같은 PR 반영됨**.
- union 분기: `mfa_required` 우선 신뢰(access_token 존재로 분기 금지). → T4 RED 음성 테스트.
- 민감정보: enable 후 setup 응답 state 명시적 `null` reset(조건부 렌더만으론 잔존). → T3 GREEN.

머지 차단 BLOCKER 1건은 plan 수정으로 해소. auth 작업이라 BLOCKER 무시 옵션 없음 — 구현 시 강제.
