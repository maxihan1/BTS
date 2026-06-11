# FR-MF-01 — TOTP 2FA 프론트 UI + E2E — 스펙

> slug: fr-mf-01-totp-ui-e2e
> 백엔드 선행: PR #113 (`5e1c8d03`), 백엔드 스펙 `docs/specs/2026-06-11-fr-mf-01-totp-authenticator.md`
> 작성: 2026-06-11

## 배경 / 범위

FR-MF-01(TOTP, Authenticator 앱 2FA)의 2-PR 분할 중 **프론트 PR**. 백엔드(setup/enable/status/disable/verify + 로그인 2단계 게이트)는 #113에서 완료. 본 PR은 백엔드 계약을 미러링하는 **프론트 UI(`/settings/mfa` + 로그인 2단계) + E2E**만 다룬다. 머지 시 FR-MF-01 완료 마킹(FR-IS-10 선례).

**범위 안.** 보안 설정 화면(활성화 QR+코드, 비활성화 step-up), 로그인 2단계 코드 입력, 에러/rate-limit UX, 컴포넌트 단위 테스트, E2E(MSW).
**범위 밖.** 백엔드 변경(이미 완료), 백업코드(FR-MF-02), 강제 정책(FR-MF-04), 신뢰 디바이스(FR-MF-05), 비활성화 시 알림 emit(notification BC 후속).

## 사용자 시나리오 (Given-When-Then)

### S1 — 설정 화면 진입 (미활성)
- **Given** 로그인한 사용자가 TOTP 미활성 상태
- **When** `/settings/mfa` 진입 → `GET /auth/mfa/totp`
- **Then** `{ enabled: false }` → "2단계 인증 비활성화됨" + "활성화" 버튼 표시

### S2 — 활성화 시작 (QR 표시)
- **Given** S1 상태에서 "활성화" 클릭
- **When** `POST /auth/mfa/totp/setup`
- **Then** 응답의 `qr_png_data_uri`를 `<img>`로 표시 + `secret_base32`를 수동입력 fallback으로 표시 + 6자리 코드 입력 필드 노출

### S3 — 활성화 확정
- **Given** S2에서 Authenticator 앱의 6자리 코드 입력
- **When** `POST /auth/mfa/totp/enable { code }`
- **Then** 204 → "2단계 인증 활성화됨" 상태로 전환(status refetch). 400 `invalid_code` → "코드가 올바르지 않습니다" 인라인 에러(필드 유지). 429 `too_many_attempts` → "시도가 너무 많습니다. 잠시 후 다시 시도하세요".

### S4 — 비활성화 (step-up)
- **Given** TOTP 활성 사용자가 `/settings/mfa`에서 `{ enabled: true }`
- **When** "비활성화" 클릭 → step-up 코드 입력 → `DELETE /auth/mfa/totp { code }`
- **Then** 204 → "비활성화됨" 상태로 전환. 400 `invalid_code` → 인라인 에러. 429 → rate-limit 메시지.

### S5 — 로그인 2단계 (TOTP 활성 사용자)
- **Given** TOTP 활성 사용자가 로그인 폼에서 username/password 입력
- **When** `POST /auth/login` → 200 `{ mfa_required: true, mfa_challenge_token, expires_in: 300 }`
- **Then** dashboard로 이동하지 않고 **MFA 코드 입력 화면**으로 전환(챌린지 토큰 메모리 보관). 사용자가 6자리 코드 입력 → `POST /auth/mfa/verify { mfa_challenge_token, code }` → 200 TokenResponse → 정식 세션 저장 → whoami → dashboard 이동.

### S6 — 로그인 2단계 실패/만료
- **Given** S5의 MFA 코드 입력 화면
- **When** 잘못된 코드 → `verify` 401 `invalid_code` / 만료된 챌린지 → 401 / rate-limit → 429
- **Then** 401 → "코드가 올바르지 않습니다"(필드 유지). 챌린지 5분 만료 의심 시(반복 401) "인증 시간이 만료되었습니다. 다시 로그인하세요" + 로그인 1단계로 복귀 링크. 429 → rate-limit 메시지.

## 기능 요구사항 (FR)

- **FR-F1** `/settings/mfa` 라우트 추가 — `requireAuth`(+ 강제 비밀번호 변경 가드) 적용, RouteAdapter 패턴(`settings.password.tsx` 선례).
- **FR-F2** 진입 시 `GET /auth/mfa/totp`로 상태 조회 → `enabled`로 활성/비활성 UI 분기(로딩/에러 상태 포함).
- **FR-F3** 활성화 흐름 — setup → QR PNG(`<img>`) + secret_base32 표시 → 코드 입력 → enable → status refetch.
- **FR-F4** 비활성화 흐름 — step-up 코드 입력 → disable → status refetch.
- **FR-F5** 로그인 2단계 — `useLoginMutation`이 login 200 응답을 `mfa_required`로 **discriminated union 분기**(parse 전), MFA 코드 입력 단계 렌더, verify로 정식 세션 획득. **MFA 코드 입력은 `LoginForm` 내부 step 상태로 처리**(identifier-first가 이미 사용하는 step 패턴 연장 — 별도 라우트 금지, 챌린지 토큰을 컴포넌트 메모리에만 보관해 NFR-1 충족). **verify 200 후에는 기존 로그인 성공 핸들러를 재사용**(access_token 저장 + whoami 조회 + returnTo 네비게이션) — 1단계 성공과 동일 경로로 수렴.
- **FR-F6** 에러 코드 매핑 — `invalid_code` / `too_many_attempts` / `no_pending_setup` / `already_enabled` / `not_enabled`를 한국어 메시지로 매핑(원인 과노출 금지, 일반 메시지).

## 비기능 요구사항 (NFR)

- **NFR-1 (민감정보 비영속)** `mfa_challenge_token`·`secret_base32`·`qr_png_data_uri`는 authStore(sessionStorage persist)에 저장 금지 — 로그인/설정 흐름 내 컴포넌트/메모리 상태로만 보관. 챌린지 토큰은 정식 세션 획득 즉시 폐기.
- **NFR-2 (계약 정합)** Zod 스키마는 백엔드 snake_case 필드명을 그대로 사용(`otpauth_uri`, `qr_png_data_uri`, `secret_base32`, `mfa_required`, `mfa_challenge_token`). 백엔드 DTO와 분리해 invent 금지(메모리 frontend-zod-backend-dto-contract-gap).
- **NFR-3 (접근성/i18n)** 모든 텍스트는 `i18n/ko.ts`의 `mfaStrings`로 분리. 폼 라벨은 `getByLabel`/`getByRole`로 접근 가능(E2E 호환). 코드 입력은 `inputMode="numeric"` + 6자리.
- **NFR-4 (의존성 0)** QR 라이브러리 추가 금지 — 백엔드 PNG data URI 직접 표시(절대 규칙 #17 회피).
- **NFR-5 (회귀 0)** TOTP 미활성 사용자의 기존 로그인 흐름 무변경. discriminated union 분기는 `mfa_required` 미존재 시 기존 TokenResponse 경로 그대로.

## API 인터페이스 (프론트가 호출하는 백엔드 — #113 확정)

| 메서드 | 경로 | 인증 | 요청 | 응답(성공) | 응답(실패) |
|---|---|---|---|---|---|
| POST | `/api/v1/auth/mfa/totp/setup` | JWT | — | 200 `{otpauth_uri, qr_png_data_uri, secret_base32}` | 409 `already_enabled` |
| POST | `/api/v1/auth/mfa/totp/enable` | JWT | `{code}` | 204 | 400 `invalid_code` / 409 `no_pending_setup` / 429 `too_many_attempts` |
| GET | `/api/v1/auth/mfa/totp` | JWT | — | 200 `{enabled}` | — |
| DELETE | `/api/v1/auth/mfa/totp` | JWT | `{code}` | 204 | 400 `invalid_code` / 404 `not_enabled` / 429 `too_many_attempts` |
| POST | `/api/v1/auth/mfa/verify` | permitAll | `{mfa_challenge_token, code}` | 200 `{access_token, token_type, expires_in}` | 401 `invalid_code` / 429 `too_many_attempts` |
| POST | `/api/v1/auth/login` | permitAll | `{provider, username, password}` | 200 TokenResponse **또는** `{mfa_required:true, mfa_challenge_token, expires_in}` | 기존 동일 |

## 엣지 케이스

- **EC-1 (챌린지 만료)** 챌린지 토큰 5분 만료 후 verify → 401. 반복 401 시 "다시 로그인" 안내 + 로그인 1단계 복귀.
- **EC-2 (setup 재진입)** 활성화 흐름 중 이탈 후 재진입 → "활성화" 재클릭 시 setup 재호출(새 PENDING/새 QR). ACTIVE 상태에서 setup 호출 시 409 `already_enabled` → 상태 refetch로 활성 UI 표시.
- **EC-3 (코드 형식)** 클라이언트 검증 — 6자리 숫자만 허용(공백 trim), 미달 시 submit 비활성/인라인 안내. 서버 검증이 정본이므로 클라 검증은 UX 보조.
- **EC-4 (동시 상태)** 다른 탭/디바이스에서 상태 변경 → 진입 시 status 조회가 정본. 낙관적 업데이트 대신 mutation 성공 후 refetch.
- **EC-5 (네트워크/5xx)** setup/enable/disable/verify 실패(네트워크·5xx) → 일반 에러 토스트/메시지 + 재시도 가능(필드 유지).
- **EC-6 (PAT 세션)** 설정 화면은 JWT 세션 전제. PAT로는 403(프론트는 JWT 세션만 사용하므로 정상 흐름에선 미발생).

## 제약 조건

- 단일 BC(identity-access) 프론트 view layer. 백엔드 변경 없음(이미 완료) → BC 경계 명확.
- 기존 로그인 E2E(`login-happy-path` 등) 회귀 0 — TOTP 미활성 사용자 흐름 무변경.
- radix-ui 직접 사용(shadcn 래퍼 부재), `components/ui/*` 재사용. Dialog 필요 시 `Dialog as DialogPrimitive`(ReauthDialog 선례).
- MSW 핸들러는 stateful(메모리 msw-mutation-stateful-refetch) — enable 후 status가 enabled:true를 반환하도록 상태 반영.

## 테스트 전략 (E2E TOTP 코드 처리)

- E2E는 MSW로 백엔드를 목킹하므로 **실제 TOTP 알고리즘 불요**. MSW MFA 핸들러는 **고정 유효 코드(`123456`)를 성공, 그 외는 실패**로 처리하는 stateful mock으로 구성(메모리 msw-mutation-stateful-refetch). enable 성공 → status가 `enabled:true` 반환, disable 성공 → `enabled:false` 반환(브라우저 시드 가능한 공유 store, 메모리 msw-derived-behavior-shared-store-e2e).
- TOTP 활성 사용자 로그인 2단계 E2E는 localStorage 플래그(예 `__bts_e2e_mfa_enabled`)로 login 응답을 `mfa_required` 분기시킴(메모리 e2e-msw-scenario-toggle-localstorage-flag, addInitScript).
- 추가 UX 디테일 — QR `<img>`에 의미 있는 `alt` 텍스트, 활성화 완료(enable 204) 후 QR/secret 화면 정리(민감정보 화면 잔존 최소화).

## 측정 가능한 완료 기준

1. `/settings/mfa`에서 활성화(QR 표시→코드→enable→활성 상태) 동작.
2. `/settings/mfa`에서 비활성화(step-up 코드→disable→비활성 상태) 동작.
3. TOTP 활성 사용자 로그인 → MFA 코드 입력 → verify → dashboard 도달(discriminated union 분기).
4. 에러 코드 6종 한국어 매핑 + rate-limit/만료 UX.
5. 컴포넌트 단위 테스트(설정 화면 상태 분기 + useLoginMutation MFA 분기 + verify 성공/실패) green.
6. E2E(Playwright + MSW) — 활성화/비활성화/로그인 2단계 happy path + 실패 1종 통과.
7. `pnpm verify`(lint + typecheck + test + build) green, 기존 E2E 회귀 0.

## Brainstorming Check

✅ 통과 (1회 iteration). 발견·보강한 gap.
- **G1** MFA 코드 입력 화면 위치 미명확 → `LoginForm` 내부 step 상태 + 기존 성공 핸들러 재사용으로 확정(NFR-1 영속 금지 충족). FR-F5 보강.
- **G2** E2E TOTP 코드 검증 방식 미명세 → MSW 고정 유효코드(`123456`) stateful mock으로 확정. §테스트 전략 신설.
- 경미 보강: QR `alt` 텍스트, enable 후 QR/secret 화면 정리.
- Maxi 결정 필요 항목: QR 렌더 방식(백엔드 PNG vs 프론트 라이브러리) — 백엔드 PNG 채택을 게이트1에서 확인.
