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

## 리뷰 결과 (← /bts-review-plan 채움)
