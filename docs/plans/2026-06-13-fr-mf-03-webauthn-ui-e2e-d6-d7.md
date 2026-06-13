# FR-MF-03 WebAuthn 보안 키 — D6 프론트 UI + D7 E2E

> slug: fr-mf-03-webauthn-ui-e2e-d6-d7
> type: auth (frontend-engineer 주도 + security-engineer 검토 + qa-engineer E2E)
> agent: security-engineer (classify 기본 — task별 plan 메타로 frontend/qa 재지정)
> 생성: 2026-06-13

## Brief

FR-MF-03 WebAuthn(Passkey/하드웨어 키) 2차 인증의 프론트 UI(D6) + E2E(D7). 백엔드 D1~D5는 PR #129로 완료 — 계약 준비됨. 잔여 D6/D7만 본 PR 범위.

**Maxi 확정 결정 3종(게이트0 office-hours 대체).**
1. **설정 레이아웃** — 보안 키를 TOTP와 독립된 섹션으로 **항상 노출**(백엔드 `isAnyMfaEnabled = TOTP OR 백업코드 OR WebAuthn`, 보안 키는 TOTP 없이도 등록 가능). 백업코드는 현행대로 TOTP 활성 시에만.
2. **로그인 2단계** — "보안 키로 인증" 버튼 + 기존 코드 입력 병렬(mfa_required 응답이 보유 요소를 알려주지 않으므로 선택지 모두 노출).
3. **D7 E2E** — `navigator.credentials.create/get`을 addInitScript로 stub(가짜 PublicKeyCredential 반환), MSW가 임의 credential 수락. 기존 MSW 결정적 패턴과 일관(CDP 가상 authenticator 미사용).

## 도메인 정리

- **BC**: identity-access (프론트는 view layer — 계약 소비, 백엔드 변경 0 목표)
- **영향 엔티티**: 없음(신규). 백엔드 `WebAuthnCredential`(VO) + `webauthn_credentials`(V025)는 PR #129에서 확정.
- **새 용어**: 없음. glossary §2FA에 "WebAuthn(선택)" 이미 등록, "Assertion" 용어도 SAML 맥락으로 존재. 프론트는 사용자 노출 명칭으로 **"보안 키"**(한국어) 사용 — 신규 도메인 용어가 아니라 UI 라벨.
- **기존 결정 충돌**: 없음.
- **관련 ADR**: [docs/decisions/2026-06-12-webauthn-second-factor.md](../decisions/2026-06-12-webauthn-second-factor.md) (PR #129, 확정). 핵심 — D1 2차 인증 only(passwordless 제외, 로그인 2단계 재사용 method=webauthn), D2 attestation=none, D3 사용자당 N개 credential(전역 credential_id UNIQUE)+sign_count clone 방어. **프론트는 이 결정을 변경하지 않고 소비만 한다.**
- **신규 ADR**: 없음(view layer 작업, 도메인 결정 없음).

## 스펙

전체 스펙. [docs/specs/2026-06-13-fr-mf-03-webauthn-ui-e2e-d6-d7.md](../specs/2026-06-13-fr-mf-03-webauthn-ui-e2e-d6-d7.md)

핵심 시나리오 3줄.
- 설정 `/settings/mfa` 보안 키 독립 섹션(항상 노출) — start→`@simplewebauthn` create→finish 등록, 목록, 인라인 확인 삭제.
- 로그인 MFA 화면에 "보안 키로 인증" 독립 버튼(코드 입력 병렬) — authenticate/start→get→verify(method=webauthn).
- 백엔드 변경 0(계약 소비). `@simplewebauthn/browser` 신규 의존성(절대규칙 #17 승인)으로 base64url↔ArrayBuffer 변환.

확정 결정 4종(§2) + 위험 R-1(의존성 버전)/R-2(E2E 가짜 credential 형태)/R-3(worktree pnpm install).

## Brainstorming Check

✅ 통과 (1회 self-iteration). gap 5건 발견·보강.
- A(P0) 등록 후 mfaEnrollmentRequired면 refreshSession(클레임-read 게이트, fr-mf-04) → FR-8.
- B 로그인 webauthn=독립 액션 버튼(세 번째 mode 아님) → FR-5 명확화.
- C verify(method=webauthn)는 mfa-handlers verifyHandler 확장 → §7.
- D @simplewebauthn 버전 고정 + E2E 가짜 credential 형태 → R-1/R-2.
- E worktree pnpm install node_modules 함정 → R-3.

## Plan

> 모두 프론트(apps/web). 백엔드 변경 0. vitest 파일 단위 → 파일 겹침이 직렬화 요인(Gradle 모듈 직렬 함정 무관).
> agent 기본값 = frontend-engineer. E2E task만 qa-engineer.

### Task 1. 계약 기반 — Zod 스키마 + 에러코드 + i18n 문자열

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/api/schemas.ts`, `apps/web/src/api/schemas.test.ts`, `apps/web/src/api/mfa.ts`, `apps/web/src/i18n/ko.ts`]
- depends-on: []

**RED**: `schemas.test.ts` — `WebauthnKeysResponseSchema.parse({keys:[{id:<uuid>, name, createdAt:<iso>, lastUsedAt:null}]})` 성공 + `lastUsedAt` nullable + `name` nullable 검증. 빈 keys 배열 허용. (Zod v4 UUID는 RFC4122 — fixture는 v4 형식 uuid 사용, zod-v4-uuid-fixture-strictness.)
**실패(예상)**: `WebauthnKeysResponseSchema` 미존재.
**GREEN**:
- `schemas.ts` — `WebauthnKeySchema`(id `z.string().uuid()`, name `.nullable()`, createdAt `z.string()`, lastUsedAt `z.string().nullable()`), `WebauthnKeysResponseSchema`({keys: array}).
  - **C-2**: timestamp는 `z.string()` 무변환(sessions.ts:21·audit-logs.ts 선례 — Instant→Date 변환 패턴 없음, transform invent 금지).
  - **C-1**: `WebAuthnKeyResponse`는 백엔드 NON_NULL 미적용 → `name`/`lastUsedAt` 키 보존 → `.nullable()` 충분(`.nullish()`/`.optional()` 쓰지 말 것 — 같은 BC NON_NULL DTO와 혼동 금지).
- `mfa.ts` `MfaErrorCode` — `INVALID_REGISTRATION:'invalid_registration'`, `ALREADY_REGISTERED:'already_registered'`, `NOT_FOUND:'not_found'`, `MFA_CHALLENGE_EXPIRED:'mfa_challenge_expired'` 추가.
- `i18n/ko.ts` `mfaStrings` — webauthn 섹션/버튼/등록/삭제/에러 문자열. `mfaErrorMessage` 매핑에 신규 에러코드 케이스.
**REFACTOR**: 문자열 그룹 KDoc. lastUsedAt null 표시용 라벨 포함.
**검증**: `pnpm --filter web test -- schemas.test.ts` + `pnpm --filter web typecheck`.

### Task 2. api/webauthn.ts + @simplewebauthn/browser 설치

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/api/webauthn.ts`, `apps/web/src/api/webauthn.test.ts`, `apps/web/package.json`, `pnpm-lock.yaml`]
- depends-on: [1]

**선행**: `pnpm --filter web add @simplewebauthn/browser`(버전 고정 — R-1, 현행 v13.x). worktree 설치이므로 typecheck로 해석 확인(R-3 worktree-node-modules-partial-install — 깨지면 controller 보고).

**fetch 방식 대비표 (C-4 — 혼동 금지)**.
| 함수 | 엔드포인트 | fetch | CSRF | credentials |
|---|---|---|---|---|
| `webauthnRegisterStart` | POST register/start | **apiFetch** | X-XSRF-TOKEN | (apiFetch 기본) |
| `webauthnRegisterFinish` | POST register/finish | **apiFetch** | X-XSRF-TOKEN | (apiFetch 기본) |
| `listWebauthnKeys` | GET webauthn | **apiGet** | — | (apiGet 기본) |
| `deleteWebauthnKey` | DELETE webauthn/{id} | **apiFetch** | X-XSRF-TOKEN | (apiFetch 기본) |
| `webauthnAuthenticateStart` | POST authenticate/start | **raw fetch** | 불요 | 불요 |
| `verifyWebauthn` | POST mfa/verify | **raw fetch** | 불요 | **`include` 필수** |

**RED**: `webauthn.test.ts` —
- `webauthnRegisterStart()` apiFetch + `X-XSRF-TOKEN`(C-4). **B-2**: 응답을 `await res.json()`으로 **옵션 객체**화해 반환(text 아님 — 백엔드가 JSON 문자열 바디를 application/json으로 내려보냄).
- `webauthnRegisterFinish(credential, name)` apiFetch POST `/finish` body `{credential, name}`. **B-1**: 빈 201(No Content) — `res.ok`만 보고 **void 반환**(parse 금지). **B-4**: credential은 객체 그대로(이중 직렬화 금지 — body 한 번만 JSON.stringify).
- `listWebauthnKeys()` apiGet → `WebauthnKeysResponseSchema`.
- `deleteWebauthnKey(id)` apiFetch DELETE 204.
- `webauthnAuthenticateStart(token)` — **raw fetch**(NFR-2), credentials/CSRF 불요. **B-2**: `await res.json()`로 옵션 객체화. 401 시 `apiFetch` 미사용으로 refresh 미호출(`auth-pre-session-401-raw-fetch`, refreshCallCount=0). 401 `mfa_challenge_expired` 구분.
- `verifyWebauthn(token, credential)` — **raw fetch** POST `/mfa/verify`, **B-3**: `credentials:'include'` + `Content-Type: application/json`(verifyMfa mfa.ts:158-163 동형). **B-4**: body `{mfa_challenge_token, code:'', method:'webauthn', credential}`를 한 번만 `JSON.stringify`(credential 객체 보존). → `TokenResponseSchema`. 401 invalid_code 시 refresh 미호출.
**실패(예상)**: `webauthn.ts` 미존재.
**GREEN**: 6개 함수 구현. `@simplewebauthn/browser`의 `startRegistration({optionsJSON: <res.json 객체>})`/`startAuthentication({optionsJSON: <res.json 객체>})` 래핑은 상위 헬퍼(`registerSecurityKey(name)`: start→`res.json()`→startRegistration→finish, `authenticateWithSecurityKey(token)`: authenticate/start→`res.json()`→startAuthentication→`verifyWebauthn(token, 반환객체)`)로 캡슐화. startAuthentication 반환 객체를 그대로 verifyWebauthn credential 인자에. NotAllowedError 등 의식 실패는 throw 보존(컴포넌트가 매핑).
**REFACTOR**: 헬퍼 KDoc(raw fetch 사유·credentials:include 필수·credential 객체 직렬화·의식 실패 전파).
**검증**: `pnpm --filter web test -- webauthn.test.ts` + typecheck.

### Task 3. MSW webauthn 핸들러 + verify 확장

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/mocks/webauthn-handlers.ts`, `apps/web/src/mocks/webauthn-handlers.test.ts`, `apps/web/src/mocks/mfa-handlers.ts`, `apps/web/src/mocks/handlers.ts`]
- depends-on: []

**핵심 요건 (B-1/B-2/B-4 — MSW는 백엔드 실제 응답 형식과 바이트 단위 일치. 느슨하면 가짜 그린)**.
- `register/start`·`authenticate/start`는 **`HttpResponse.json(<옵션 객체>)`**(text 아님, B-2). 옵션 객체는 @simplewebauthn가 디코드하는 필드 포함 — `challenge`(base64url), `rp`, `user`(id base64url), `pubKeyCredParams`, authenticate는 `allowCredentials`/`rpId`. R-2 stub과 짝.
- `register/finish`는 **빈 201**(`new HttpResponse(null,{status:201})`, B-1 — `{id,name}` 본문 금지).
- verify webauthn 분기는 `body.credential`이 **객체로 존재**하는지만 확인 후 200(B-4).

**RED**: `webauthn-handlers.test.ts` — stateful store 기반.
- `register/start` 200 옵션 **객체**(B-2), `register/finish` **빈 201**(B-1) + store에 키 추가, 중복이면 409.
- `GET /webauthn` 현재 store 목록 반환(WebauthnKeyResponse 형태 — name/lastUsedAt 키 보존, C-1).
- `DELETE /webauthn/{id}` 204 + store 제거, 없으면 404.
- `authenticate/start` 200 옵션 **객체**(B-2), 만료 토큰이면 401 `mfa_challenge_expired`.
- (`mfa-handlers.ts`) `verifyHandler`가 `method:'webauthn'` 분기 추가 — `body.credential` 객체 존재 시 200 토큰(기존 totp/backup 분기 회귀 0).
**실패(예상)**: webauthn-handlers 미존재 / verify가 webauthn을 totp로 처리해 실패.
**GREEN**: 핸들러 + store 구현. `handlers.ts` 배열에 webauthnHandlers 등록(공유 파일 — 본 task 단독 소유, parallel-fr-overlapping-frontend-infra-collision). MSW 변이는 `X-XSRF-TOKEN` 검증(기존 mfa-handlers 패턴). stateful store는 브라우저 시드 가능 구조(E2E 공유, msw-derived-behavior-shared-store-e2e).
**REFACTOR**: store reset export(테스트 격리, msw-mutation-stateful-refetch).
**검증**: `pnpm --filter web test -- webauthn-handlers.test.ts mfa-handlers`.

### Task 4. WebauthnSection.tsx — 설정 보안 키 섹션

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/auth/WebauthnSection.tsx`, `apps/web/src/components/auth/WebauthnSection.test.tsx`]
- depends-on: [2, 3]

**RED**: `WebauthnSection.test.tsx` —
- 목록 표시(이름·등록일·lastUsedAt null="사용 안 함"), 0개 시 안내.
- "보안 키 추가" → 별칭 입력 → `registerSecurityKey` 호출 → 성공 시 목록 invalidate(setQueryData 부분응답 금지, invalidate-only).
- 삭제 인라인 확인(fr-mf-02 패턴, 모달 부재) → `deleteWebauthnKey` → 목록 갱신.
- **FR-8(P0)**: `mfaEnrollmentRequired===true`면 등록 성공 후 `refreshSession` 호출(mock) 검증(클레임-read 게이트, fr-mf-04).
- EC-1 사용자 취소(NotAllowedError) → 인라인 에러 + 화면 유지. EC-2 409 → "이미 등록". EC-3 미지원 → 비활성 안내(jsdom PublicKeyCredential 미정의 자연 커버).
**실패(예상)**: `WebauthnSection` 미존재.
**GREEN**: useQuery(목록) + useMutation(register/delete). 200줄 제약 → 하위 컴포넌트 분리(목록 항목/등록 폼/삭제 확인). 평문 challenge/credential 영속 금지(NFR-1, state만).
**REFACTOR**: 컴포넌트 분리 + i18n 사용 확인.
**검증**: `pnpm --filter web test -- WebauthnSection.test.tsx`.

### Task 5. MfaSettings.tsx — 2-섹션 구조(보안 키 항상 노출)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/auth/MfaSettings.tsx`, `apps/web/src/components/auth/MfaSettings.test.tsx`]
- depends-on: [4]

**RED**: `MfaSettings.test.tsx`(기존 확장) — TOTP 미활성 상태에서도 `WebauthnSection`이 렌더됨(FR-1, D-A). 기존 TOTP/백업코드 동작 회귀 0(백업코드는 TOTP 활성 시에만 유지).
**실패(예상)**: TOTP 미활성 시 보안 키 섹션 미렌더.
**GREEN**: MfaSettings를 "TOTP 섹션 + 보안 키 섹션" 구조로. `<WebauthnSection/>`을 isEnabled 분기 밖(항상)에 배치. 기존 백업코드는 isEnabled 안 유지(surgical).
**REFACTOR**: 섹션 구분 헤더/여백. 기존 enforcement 배너·refreshSession 경로 보존.
**검증**: `pnpm --filter web test -- MfaSettings.test.tsx`.

### Task 6. LoginForm.tsx — "보안 키로 인증" 버튼

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/auth/LoginForm.tsx`, `apps/web/src/auth/LoginForm.test.tsx`]
- depends-on: [2, 3]

**RED**: `LoginForm.test.tsx`(기존 확장) — MFA step에서 "보안 키로 인증" 버튼 표시. 클릭 → `authenticateWithSecurityKey(challengeToken)` → 성공 시 setSession + onSuccess(기존 verify 성공 경로 수렴).
- **EC-4** verify 401 invalid_code → "보안 키 인증 실패" 인라인 + 화면 유지 + **challengeToken 보존**(재클릭 재시도 — B-5, authenticate/start가 토큰 미소비).
- **EC-1** 사용자 취소(NotAllowedError throw) → 인라인 에러 + 화면 유지 + **challengeToken 보존**(재시도).
- **EC-5** authenticate/start 401 `mfa_challenge_expired` → 토큰 실제 만료 → 안내 + 로그인 복귀(handleBackToLogin, 토큰 폐기).
- **C-3** `browserSupportsWebAuthn()` false(미지원) → "보안 키로 인증" 버튼 미표시/비활성.
- 기존 TOTP/백업코드 토글 회귀 0.
**실패(예상)**: 버튼 미존재.
**GREEN**: `LoginMfaStep`에 "보안 키로 인증" **독립 액션 버튼**(FR-5 — MfaCodeInput 세 번째 mode 아님, zodResolver 불요). 클릭 핸들러가 webauthn 오케스트레이션 호출. 챌린지 토큰은 메모리만(NFR-1). **B-5 토큰 생명주기**: 취소/EC-4는 토큰 유지(재시도), EC-5만 폐기·복귀. 성공 시 access token 저장 + whoami + setSession(기존 MfaCodeInput 동형, credentials:'include'로 받은 refresh 쿠키 의존).
**REFACTOR**: webauthn 핸들러 분리, i18n 사용.
**검증**: `pnpm --filter web test -- LoginForm.test.tsx`.

### Task 7. E2E webauthn-settings + navigator.credentials stub fixture

**메타**.
- agent: `qa-engineer`
- files: [`apps/web/e2e/webauthn-settings.spec.ts`, `apps/web/e2e/support/webauthn-stub.ts`]
- depends-on: [3, 4, 5]

**RED→검증(E2E는 행위 검증)**:
- `support/webauthn-stub.ts` — addInitScript로 `navigator.credentials.create/get`을 가짜 `PublicKeyCredential` 반환하도록 stub(R-2: id base64url, rawId/response ArrayBuffer, type, getClientExtensionResults, response.getTransports — @simplewebauthn 직렬화 통과 형태). 빈 ArrayBuffer로도 MSW 수락.
- `webauthn-settings.spec.ts` — 로그인 후 `/settings/mfa` → 보안 키 섹션 노출(TOTP 무관) → "보안 키 추가" → 별칭 입력 → 등록 성공 → 목록 표시. 삭제 → 목록에서 제거. MSW serviceWorkers:'block' 금지, 시나리오 토글은 localStorage(e2e-msw-serviceworker-block, e2e-msw-scenario-toggle). getByRole exact/컨테이너 한정(playwright-getbyrole-exact-strict-mode).
**검증**: `pnpm --filter web test:e2e -- webauthn-settings.spec.ts`.

### Task 8. E2E webauthn-login

**메타**.
- agent: `qa-engineer`
- files: [`apps/web/e2e/webauthn-login.spec.ts`]
- depends-on: [3, 6, 7]

**RED→검증**:
- `webauthn-login.spec.ts` — `support/webauthn-stub.ts`(T7 fixture) 재사용. MFA 챌린지 화면(MFA_E2E_ENABLED 토글) → "보안 키로 인증" 버튼 클릭 → 의식 stub → /dashboard 도달(S3). 사용자 취소(stub이 NotAllowedError throw) → 인라인 에러 + 화면 유지(S4). 기존 mfa-login.spec.ts(TOTP) 회귀 0 동시 실행(ui-pr-defer-e2e-regression-latent).
**검증**: `pnpm --filter web test:e2e -- webauthn-login.spec.ts mfa-login.spec.ts`.

## Plan 메타

- task 수: 8
- 예상 wave: W1[T1,T3] · W2[T2] · W3[T4,T6] · W4[T5] · W5[T7] · W6[T8] (약 6 wave — 파일 겹침/코드 의존 직렬화)
- TDD 강제: yes (test 커밋이 feat 커밋보다 먼저)
- 병렬 dispatch: bts-impl이 depends-on + files로 wave 계산. 프론트라 vitest 파일 단위(Gradle 모듈 직렬 무관)
- 추가 검증: pnpm verify(lint+typecheck+test+build), playwright(qa), verify-master-plan(D6/D7 [x])
- 신규 의존성: `@simplewebauthn/browser`(절대규칙 #17 Maxi 승인 — Task 2)

## 리뷰 결과

### plan-eng-review (독립 security-engineer 적대 dispatch, 2026-06-13)

백엔드 계약(MfaController/AuthController/WebAuthnService/WebAuthnSecurityKeyService/MfaEnrollmentGateFilter) + 프론트 선례 실측 대조. **BLOCKER 5 + CONCERN 4 적발 → 전부 plan 반영(아래 §반영).** auth라 BLOCKER 무시 불가.

- **B-1** `register/finish` 응답은 빈 201(No Content). `MfaController.kt:248` `…build<Unit>()` — KDoc `{id,name}`은 죽은 주석. → finish는 parse 금지·void 반환, MSW도 빈 201, 목록은 invalidate-only.
- **B-2** `register/start`·`authenticate/start`는 webauthn4j 직렬화 **JSON 문자열** 반환(`WebAuthnService.kt:107`, `produces=json`). 프론트는 `res.json()`으로 **객체화**해 `@simplewebauthn` `optionsJSON`에 전달. MSW도 `HttpResponse.json(객체)` + @simplewebauthn 디코드 필드(challenge base64url/rp/user/pubKeyCredParams, auth는 allowCredentials/rpId) 갖춤.
- **B-3** `verify`는 `credentials:'include'` 필수(Set-Cookie refresh_token 수신, `mfa.ts:160` 선례), `authenticate/start`는 credentials·CSRF 불요. 비대칭 명시(verify에 include 누락 시 이후 refresh 전부 실패).
- **B-4** `MfaVerifyRequest.credential`은 `JsonNode?`(`AuthController.kt:871`), `credentialJsonOf`가 `.toString()`(line 302) → 프론트는 credential을 **객체 그대로** 실어 body 한 번만 `JSON.stringify`(이중 직렬화 금지, 문자열화 시 401).
- **B-5** `authenticate/start`는 토큰 validate만·consume 안 함(`MfaController.kt:296` KDoc) → webauthn 취소/EC-4(verify 401)는 challengeToken **보존**(재클릭 재시도), EC-5(authenticate/start 401 mfa_challenge_expired)만 로그인 복귀.
- **C-1** `WebAuthnKeyResponse`는 NON_NULL 미적용 → `name`/`lastUsedAt` 키 보존 → `.nullable()` 충분(`.nullish()`·`.optional()` 불요).
- **C-2** schemas에 Instant→Date 변환 패턴 없음 — timestamp는 `z.string()`(sessions.ts:21·audit-logs.ts 선례). "Instant 컨버전 패턴" 표현 정정.
- **C-3** Task 6에 브라우저 미지원(`browserSupportsWebAuthn()` false) 버튼 미표시/비활성 케이스 추가.
- **C-4** register/start·finish·list·delete는 **apiFetch + X-XSRF-TOKEN**(세션 있음, 401 자동 refresh 정상). raw fetch는 authenticate/start·verify(세션 전)뿐. 대비 명시.

**OK(검증됨)**: FR-8 P0 게이트(`isAnyMfaEnabled`가 WebAuthn OR 포함 `AuthController.kt:207`, GateFilter ALLOW_LIST에 `/mfa/**`·refresh), 독립 액션 버튼(FR-5), 평문 비영속(NFR-1), method=webauthn 백엔드 경로 존재, verifyMfa 시그니처 불변 회귀 0.

### §반영 — BLOCKER/CONCERN 9건 모두 Task 1/2/3/6에 인라인 보강(아래 Task 본문 갱신). 게이트1 진입 가능.
