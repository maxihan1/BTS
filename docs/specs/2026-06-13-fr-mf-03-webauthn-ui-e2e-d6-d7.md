<!-- FR-MF-03 WebAuthn 보안 키 D6 프론트 UI + D7 E2E 기술 스펙 -->
# FR-MF-03 WebAuthn 보안 키 — D6 프론트 UI + D7 E2E 스펙

> FR: FR-MF-03 (우선순위 선택) · BC: identity-access (view layer)
> 백엔드 D1~D5: PR #129 완료(계약 확정). 본 스펙은 **D6 프론트 + D7 E2E**만 다룬다.
> 작성: 2026-06-13 · office-hours 생략(완료 FR 후속, 직접 기술 스펙 — bts-spec-office-hours-mismatch)

## 1. 배경 / 범위

WebAuthn(FIDO2 — 지문·얼굴·USB 보안키 기반 인증) 2차 인증의 프론트 UI와 E2E. 백엔드는 등록(attestation)/인증(assertion) 챌린지·검증·credential 관리 엔드포인트를 모두 갖췄다(PR #129). 본 작업은 **계약 소비**이며 백엔드 변경 0을 목표로 한다(불가피한 view layer 갭 발견 시 게이트1에서 same-BC 예외 판단 — BC 격리 예외 패턴).

**범위 밖**: passwordless/패스키 1단계 로그인(ADR D1에서 제외), 신뢰 디바이스(FR-MF-05), prod RP fail-fast 전환(백엔드 후속 — 메모리 #6).

## 2. Maxi 확정 결정 (게이트0 — office-hours/design-shotgun 대체)

| # | 결정 | 근거 |
|---|---|---|
| D-A | **설정: 보안 키 독립 섹션 항상 노출** | 백엔드 `isAnyMfaEnabled = TOTP OR 백업코드 OR WebAuthn` — 보안 키는 TOTP 없이도 등록 가능. 백업코드(TOTP 활성 선행)와 다름. |
| D-B | **로그인: "보안 키로 인증" 버튼 + 코드 입력 병렬** | `mfa_required` 응답이 보유 요소를 알려주지 않아 클라이언트가 선택지를 모두 노출해야 함. |
| D-C | **E2E: `navigator.credentials` stub (addInitScript)** | BTS E2E는 MSW로 백엔드 완전 모킹 — 가짜 credential 수락. CDP 가상 authenticator 미사용(기존 결정적 패턴 일관). |
| D-D | **변환: `@simplewebauthn/browser` (신규 의존성, 절대규칙 #17 승인)** | base64url↔ArrayBuffer + 브라우저 quirk를 표준 라이브러리가 처리. webauthn4j `verify*ResponseJSON`과 W3C 표준 형식 호환. 보안 의식 정확성 위험 최소. |

## 3. 사용자 시나리오 (Given-When-Then)

### 설정 — 등록 (S1)
- **Given** 로그인한 사용자가 `/settings/mfa`의 보안 키 섹션을 본다(TOTP 활성 여부 무관).
- **When** "보안 키 추가" 클릭 → 별칭(name) 입력 → 진행.
- **Then** `register/start` 옵션 발급 → `navigator.credentials.create` 프롬프트(지문/PIN/터치) → `register/finish` 검증 → 201 → 목록에 새 키 표시(이름/등록일).

### 설정 — 목록·삭제 (S2)
- **Given** 보안 키가 1개 이상 등록된 사용자.
- **When** 섹션 진입 시 `GET /webauthn`로 키 목록(이름·등록일·마지막 사용)을 표시. 키의 "삭제" 클릭 → 인라인 확인.
- **Then** `DELETE /webauthn/{id}` → 204 → 목록에서 제거. (모달 라이브러리 없으니 fr-mf-02 패턴 — 인라인 확인.)

### 로그인 — 보안 키 2단계 (S3)
- **Given** 1단계(비번) 통과 후 `mfa_required` 챌린지 화면.
- **When** "보안 키로 인증" 버튼 클릭.
- **Then** `webauthn/authenticate/start {mfa_challenge_token}` 옵션 발급 → `navigator.credentials.get` 프롬프트 → `mfa/verify {method:"webauthn", credential}` → 200 토큰 → `/dashboard` 진입.

### 엣지 — 사용자 취소 (S4)
- **When** `navigator.credentials.create/get` 도중 사용자가 취소(NotAllowedError) 또는 타임아웃.
- **Then** 인라인 에러 메시지 + 화면 유지(세션/플로우 보존, 재시도 가능). 챌린지 토큰은 미소비(authenticate/start는 consume 안 함).

## 4. 기능 요구사항 (FR)

- **FR-1** 보안 키 섹션은 `/settings/mfa`에 **독립적으로 항상 렌더**된다(TOTP 상태 무관). MfaSettings를 "TOTP 섹션 + 보안 키 섹션" 2-섹션 구조로 재배치한다.
- **FR-2** 등록: start→`startRegistration`→finish 3-step. 별칭(name)은 사용자 입력(선택, 빈 값 허용 — 백엔드 nullable). 성공 시 목록 invalidate.
- **FR-3** 목록: `GET /webauthn` → 이름(없으면 기본 라벨)·등록일·마지막 사용 시각 표시. 0개면 "등록된 보안 키 없음" 안내.
- **FR-4** 삭제: 인라인 확인 후 `DELETE /webauthn/{id}` → 204 → 목록 갱신.
- **FR-5** 로그인 MFA 화면에 "보안 키로 인증" 버튼 추가. **이 버튼은 `MfaCodeInput`의 세 번째 mode가 아니라 독립 액션 버튼**이다(코드 입력 없음 → zodResolver/useForm 불요, react-usestate-stale-key 함정 회피). TOTP 코드 입력 폼·백업코드 토글과 같은 화면에 병렬 배치. 클릭 시 authenticate/start→`startAuthentication`→verify(method=webauthn).
- **FR-6** 에러 매핑: `invalid_registration`(400)/`already_registered`(409)/`not_found`(404)/`mfa_challenge_expired`(401)/`invalid_code`(401, verify 실패)/`too_many_attempts`(429) 모두 한국어 메시지로. 브라우저 의식 실패(NotAllowedError 등)도 별도 메시지.
- **FR-7** 브라우저 미지원(`browserSupportsWebAuthn()` false) 시 보안 키 섹션/버튼을 비활성 + 안내(unit 환경 jsdom 포함).
- **FR-8 (P0 — 클레임-read 게이트 해제)** 보안 키 등록 성공 후, 사용자가 `mfaEnrollmentRequired === true`(FR-MF-04 강제 대상)였다면 `refreshSession()`으로 access JWT 클레임을 재계산하고 게이트를 해제한다(TOTP enable의 FR-D6-4와 동형). 생략 시 store가 stale(true)로 남아 `requireMfaEnrolled` 가드가 영구 리다이렉트(등록하고도 갇힘 — fr-mf-04-mfa-enforcement-done P0). 백엔드 `isAnyMfaEnabled`가 WebAuthn을 포함하므로 보안 키만 등록해도 게이트 대상에서 벗어난다.

## 5. 비기능 요구사항 (NFR)

- **NFR-1 (영속 금지, 절대규칙 #18)** challenge·옵션·credential·챌린지 토큰은 React state/메모리에만. localStorage/sessionStorage/authStore 영속 금지. 챌린지 토큰은 LoginForm 메모리(기존 패턴 유지).
- **NFR-2 (인증 전 raw fetch)** `webauthn/authenticate/start`·`mfa/verify`는 정식 세션 전 호출 → `apiFetch` 금지(401 자동 refresh가 에러 변질 + clearSession 부수효과). raw `fetch` 사용(auth-pre-session-401-raw-fetch). 회귀 테스트로 refresh 미호출 확인.
- **NFR-3 (CSRF)** 세션 있는 self-service 변이(register/start·finish, delete)는 `X-XSRF-TOKEN` 수동 주입(frontend-api-convention-per-bc, 기존 mfa.ts 패턴).
- **NFR-4 (i18n)** 모든 사용자 노출 문자열은 `i18n/ko.ts`의 `mfaStrings`에 추출(인라인 금지 — fr-mf-02 게이트2 교훈).
- **NFR-5 (Zod 계약 일치)** `WebAuthnKeysResponse`/키 항목 Zod 스키마는 백엔드 DTO와 1:1(id UUID, name nullable, createdAt/lastUsedAt ISO Instant). invent 금지(frontend-zod-backend-dto-contract-gap).

## 6. API 인터페이스 (백엔드 계약 — 소비, 변경 없음)

| 메서드 | 경로 | 인증 | 요청 | 응답 |
|---|---|---|---|---|
| POST | `/api/v1/auth/mfa/webauthn/register/start` | JWT + CSRF | — | 200 옵션 JSON(base64url) / 403 PAT |
| POST | `/api/v1/auth/mfa/webauthn/register/finish` | JWT + CSRF | `{credential, name?}` | 201 / 400 `invalid_registration` / 409 `already_registered` |
| GET | `/api/v1/auth/mfa/webauthn` | JWT | — | 200 `{keys:[{id,name,createdAt,lastUsedAt}]}` |
| DELETE | `/api/v1/auth/mfa/webauthn/{id}` | JWT + CSRF | — | 204 / 404 `not_found` |
| POST | `/api/v1/auth/mfa/webauthn/authenticate/start` | permitAll(챌린지 토큰) | `{mfa_challenge_token}` | 200 옵션 JSON / 401 `mfa_challenge_expired` |
| POST | `/api/v1/auth/mfa/verify` | permitAll(챌린지 토큰) | `{mfa_challenge_token, method:"webauthn", credential}` | 200 TokenResponse / 401 `invalid_code` / 429 |

옵션 JSON은 webauthn4j 직렬화 형식(W3C `PublicKeyCredentialCreationOptions`/`RequestOptions`, 바이트 필드 base64url). `@simplewebauthn/browser`의 `startRegistration({optionsJSON})`/`startAuthentication({optionsJSON})` 입력으로 그대로 사용. 반환 응답 JSON을 finish/verify의 `credential`로 전송.

## 7. 프론트 구조 (신규/변경)

**신규**
- `apps/web/src/api/webauthn.ts` — `webauthnRegisterStart/Finish`, `listWebauthnKeys`, `deleteWebauthnKey`, `webauthnAuthenticateStart`(raw), `verifyWebauthn`(raw, verify method=webauthn). `@simplewebauthn/browser` 래핑은 컴포넌트가 아닌 이 레이어 or 전용 lib에서.
- `apps/web/src/components/auth/WebauthnSection.tsx` — 설정 보안 키 섹션(목록/등록/삭제). BackupCodesSection 동형.
- `apps/web/src/mocks/webauthn-handlers.ts` — MSW 핸들러(stateful store: 키 목록·register/start·register/finish·delete·authenticate/start). **verify(method=webauthn)는 별도 엔드포인트가 아니라 기존 `mfa-handlers.ts`의 `verifyHandler` 확장**(현재 totp/backup만 분기 → webauthn 추가, 가짜 credential 수락 시 성공). 두 파일 협응이라 plan에서 명시.
- `apps/web/e2e/webauthn-settings.spec.ts` + `apps/web/e2e/webauthn-login.spec.ts` — D7 E2E(navigator.credentials addInitScript stub).
- E2E fixture: navigator.credentials stub 헬퍼.

**변경**
- `apps/web/src/components/auth/MfaSettings.tsx` — 보안 키 섹션을 항상 렌더(2-섹션 구조).
- `apps/web/src/auth/LoginForm.tsx` — MFA 화면에 "보안 키로 인증" 버튼 추가(코드 입력 병렬).
- `apps/web/src/api/schemas.ts` — `WebauthnKeySchema`/`WebauthnKeysResponseSchema`.
- `apps/web/src/api/mfa.ts`의 `MfaErrorCode` — webauthn 에러코드(`invalid_registration`, `already_registered`, `not_found`, `mfa_challenge_expired`) 추가.
- `apps/web/src/i18n/ko.ts`의 `mfaStrings` — webauthn 문자열.
- `apps/web/package.json` — `@simplewebauthn/browser` 의존성.

## 8. 엣지 케이스

- **EC-1** 사용자 취소/타임아웃(NotAllowedError) → 인라인 에러 + 화면 유지(세션 보존). 챌린지 토큰 미소비라 재시도 가능.
- **EC-2** 중복 등록(같은 인증기) → 백엔드 409 `already_registered`(start 옵션 excludeCredentials로도 1차 차단) → "이미 등록된 보안 키" 메시지.
- **EC-3** 브라우저 미지원/비-HTTPS → `browserSupportsWebAuthn()` false → 섹션·버튼 비활성 + 안내. (unit 환경 jsdom은 PublicKeyCredential 미정의 → false 경로 자연 커버.)
- **EC-4** verify 실패(서명 불일치/clone/만료) → 백엔드 401 `invalid_code` 일반화 → "보안 키 인증에 실패했습니다" + 화면 유지(/dashboard 미전환).
- **EC-5** authenticate/start 챌린지 만료 → 401 `mfa_challenge_expired` → "세션이 만료되었습니다. 다시 로그인하세요" + 로그인 복귀.
- **EC-6** 마지막 보안 키 삭제 — TOTP/백업코드가 없으면 사용자의 모든 2차 요소가 사라질 수 있음. 백엔드가 막지 않으므로 프론트는 삭제 자체는 허용하되(계약 준수), 강제 정책(FR-MF-04) 대상이면 mfaEnrollmentRequired 게이트가 재등록 유도(별도 FR, 본 범위 밖 — 인지만).

## 9. 제약 조건 / 구현 위험

- 백엔드 변경 0 목표(계약 소비). 불가피 시 same-BC view layer 예외 게이트1 판단.
- 기존 TOTP/백업코드 로그인 회귀 0(verifyMfa 시그니처 미변경 — webauthn은 별도 함수).
- 컴포넌트 200줄 제약(분리). 절대규칙 19개 준수.
- TDD red→green 강제.
- **위험 R-1 (의존성 버전)** `@simplewebauthn/browser`는 webauthn4j 0.28.4의 `verify*ResponseJSON`이 파싱하는 W3C `RegistrationResponseJSON`/`AuthenticationResponseJSON` 형식과 호환되는 버전 고정(현행 v13.x — WebAuthn L3 표준 JSON). 백엔드 webauthn4j 버전 고정(메모리 fr-mf-03 함정 #1)과 대응. 실하드웨어 round-trip은 prod RP 설정 후라 CI 미검증 — 형식 호환은 표준 준수로 확보.
- **위험 R-2 (E2E 가짜 credential 형태)** `@simplewebauthn/browser`가 `navigator.credentials` 결과를 client-side에서 직렬화하므로, addInitScript stub이 반환하는 가짜 `PublicKeyCredential`은 @simplewebauthn가 처리 가능한 형태여야 한다 — `id`(base64url string), `rawId`(ArrayBuffer), `response.{clientDataJSON, attestationObject 또는 authenticatorData/signature}`(ArrayBuffer), `type:'public-key'`, `getClientExtensionResults()`, `response.getTransports?.()`. 빈 ArrayBuffer로도 직렬화는 통과(MSW가 수락). plan task에서 fixture 헬퍼로 캡슐화.
- **위험 R-3 (worktree node_modules)** `@simplewebauthn/browser` 신규 설치는 worktree에서 `pnpm install` 필요. worktree node_modules 부분설치 함정(worktree-node-modules-partial-install) — 설치 후 typecheck/test로 해석 확인, 깨지면 main에서 복구.

## 10. 측정 가능한 완료 기준

- [ ] `/settings/mfa` 보안 키 섹션: 등록/목록/삭제 동작(TOTP 무관 항상 노출).
- [ ] 로그인 MFA 화면 "보안 키로 인증" 버튼 → 2단계 성공 → /dashboard.
- [ ] 단위 테스트(api/webauthn, WebauthnSection, LoginForm webauthn 분기, schemas) green.
- [ ] E2E: webauthn-settings(등록·삭제) + webauthn-login(성공·취소) 통과. 기존 MFA E2E 회귀 0.
- [ ] `pnpm verify`(lint+typecheck+test+build) green. verify-master-plan green(D6/D7 [x] 마킹).
- [ ] product §3.3 D6/D7 [x], 카운트 불변(122/122 — FR 자체는 #129에서 이미 계수).

## 11. Brainstorming Check

✅ 통과 (1회 self-iteration, gap 5건 발견 후 보강).
- Gap A (P0): 보안 키 등록 성공 시 mfaEnrollmentRequired 강제 대상이면 refreshSession 클레임 재계산 → FR-8 추가.
- Gap B: 로그인 webauthn은 MfaCodeInput 세 번째 mode가 아닌 독립 액션 버튼 → FR-5 명확화.
- Gap C: verify(method=webauthn)는 mfa-handlers.ts verifyHandler 확장 → §7 명시.
- Gap D: @simplewebauthn 버전 고정 + E2E 가짜 credential 형태 → R-1/R-2.
- Gap E: worktree pnpm install node_modules 함정 → R-3.
