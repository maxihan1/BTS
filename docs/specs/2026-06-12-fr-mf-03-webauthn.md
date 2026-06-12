# FR-MF-03 — WebAuthn (Passkey/하드웨어 보안 키) 2차 인증 백엔드 — 스펙

> BC: identity-access · type: auth · agent: security-engineer
> SDD: §19.7(2FA), §2.2.13(FR-MF-03 선택), §19.5(`mfa_verified` 클레임)
> ADR: [docs/decisions/2026-06-12-webauthn-second-factor.md](../decisions/2026-06-12-webauthn-second-factor.md)
> 범위(Maxi 확정): **FR-MF-03 백엔드 D1~D5만** — WebAuthn을 **2차 인증 수단**으로만(패스키/passwordless 제외), **attestation=none**. 프론트(D6 `navigator.credentials`)·E2E(D7)는 후속 PR.

## 용어

- **WebAuthn** (Web Authentication, W3C) — 브라우저 표준 API로 기기(지문·얼굴·USB 보안키)를 공개키 암호로 인증하는 방식. 비밀번호 대신/추가로 본인을 증명.
- **FIDO2** — WebAuthn(브라우저) + CTAP(기기 통신) 표준 묶음. WebAuthn의 상위 우산 용어.
- **authenticator** — 실제 인증을 수행하는 기기(폰 지문센서, YubiKey 등). 비공개키를 안전하게 보관하고 challenge에 서명.
- **RP** (Relying Party) — 인증을 의뢰하는 서비스. 여기서는 BTS. **RP ID**는 도메인(예. `localhost` / 실 도메인), **origin**은 전체 출처(예. `http://localhost:5173`).
- **attestation** (등록 증명) — 등록 시 authenticator의 진위를 제조사 증명서로 확인하는 절차. **본 PR은 attestation=none**(증명서 검증 생략, 공개키만 저장).
- **assertion** (로그인 검증) — 로그인 시 authenticator가 서버 challenge에 서명해 보낸 응답. 서버가 등록된 공개키로 서명을 검증.
- **challenge** — 서버가 매 ceremony마다 생성하는 랜덤 nonce. 재전송(replay) 공격 방어. authenticator가 이 값에 서명.
- **credential** — 등록된 공개키 자격증명 1건. `credentialId`(식별자) + 공개키 + `signCount`로 구성. **사용자당 여러 개** 가능.
- **sign count** — authenticator가 인증할 때마다 증가시키는 카운터. 저장값보다 작거나 같으면 복제된 기기로 의심(clone 방어).
- **ceremony** — 등록(registration) 또는 로그인(authentication)의 1회 왕복 절차. WebAuthn은 challenge-response라 **start(challenge 발급) → finish(검증)** 2단계.
- **MFA 챌린지 토큰** — 1단계(비밀번호) 통과 후 2단계 대기 상태를 들고 가는 단명(5분) 서명 JWT(기존 FR-MF-01). WebAuthn challenge(nonce)와는 **다른 개념** — 전자는 "2단계 진입권", 후자는 "FIDO2 서명 대상".

## 라이브러리 결정 (핵심 — 호환성 함정)

- **`com.webauthn4j:webauthn4j-core:0.28.4.RELEASE`** 도입(Maxi 승인, 절대 규칙 #17 — 직접 의존 명시 선언).
- **버전 고정 이유**. webauthn4j는 **0.31.0.RELEASE(2026-02-01)부터 Jackson 3**(`tools.jackson`)에 의존한다. BTS는 **Spring Boot 3.3.5 = Jackson 2.x**(`com.fasterxml.jackson`)이므로 0.31+는 클래스패스 충돌/이중 Jackson을 유발한다. **0.31 미만(0.28.4가 Maven 공개 최신 안정판)이 Jackson 2 호환**이라 이를 채택한다. Spring Boot가 Jackson 3(SB 4+)로 올라가면 그때 webauthn4j 0.31+로 동반 상향.
- **테스트 의존성**. `com.webauthn4j:webauthn4j-test:0.28.4.RELEASE`(testImplementation) — `EmulatorAuthenticator`/`ClientPlatform`로 실기기 없이 가상 authenticator ceremony 생성(D5 "가상 Authenticator 테스트").
- **진입점**. `WebAuthnManager.createNonStrictWebAuthnManager()` — attestation statement 미검증 모드(=attestation none 결정과 정확히 일치). 0.28.4 검증 API(GitHub 0.28.4.RELEASE 태그 소스 실측):
  - 등록. `parseRegistrationResponseJSON(String): RegistrationData`, `verifyRegistrationResponseJSON(String, RegistrationParameters): RegistrationData`
  - 인증. `parseAuthenticationResponseJSON(String): AuthenticationData`, `verifyAuthenticationResponseJSON(String, AuthenticationParameters): AuthenticationData`
  - `ServerProperty.builder().origin(Origin).rpId(String).challenge(Challenge).build()`
  - `RegistrationParameters(serverProperty, pubKeyCredParams, userVerificationRequired, userPresenceRequired)`
  - `AuthenticationParameters(serverProperty, credentialRecord, allowCredentials, userVerificationRequired, userPresenceRequired)`
  - 저장 직렬화. `AttestedCredentialDataConverter(objectConverter).convert(attestedCredentialData): ByteArray` ↔ `convert(ByteArray): AttestedCredentialData`

## 사용자 시나리오 (Given-When-Then)

### S1. 보안 키 등록 (로그인 사용자, JWT)
- **Given** 로그인한 사용자(이미 TOTP 활성일 수도, 아닐 수도)
- **When** 보안 키 등록을 시작(`POST .../webauthn/register/start`)하면
- **Then** 서버가 challenge(nonce)를 생성·임시 저장(Caffeine, key=userId)하고 `PublicKeyCredentialCreationOptions`(rpId·user·challenge·pubKeyCredParams[ES256/RS256]·excludeCredentials[기존 키]·attestation=none)를 JSON으로 반환한다.
- **When** 사용자가 기기로 서명한 결과(credential JSON)와 키 별칭(name)을 `POST .../webauthn/register/finish`로 보내면
- **Then** 서버가 저장된 challenge로 `verifyRegistrationResponseJSON`을 수행하고, 성공 시 credential(credentialId·공개키·signCount=0·name)을 DB에 INSERT한다(`MFA_WEBAUTHN_REGISTERED` 감사). challenge는 소비(삭제).
- **When** challenge가 만료/부재거나 검증 실패면
- **Then** 400 `invalid_registration`(원인 비노출). credential 미저장.

### S2. 보안 키 사용 로그인 (2단계)
- **Given** 보안 키가 1개 이상 등록된 사용자
- **When** `POST /login`으로 올바른 비밀번호를 제출하면
- **Then** 서버는 정식 세션 대신 200 `{ mfa_required: true, mfa_challenge_token, expires_in }`를 응답한다(기존 FR-MF-01 흐름 무변경 — TOTP/WebAuthn 어느 쪽이든 `isAnyMfaEnabled`면 챌린지 발급).
- **When** 챌린지 토큰으로 assertion 시작(`POST .../webauthn/authenticate/start { mfa_challenge_token }`)하면
- **Then** 서버가 challenge 생성·임시 저장하고 `PublicKeyCredentialRequestOptions`(challenge·allowCredentials=사용자 등록 키 목록·rpId)를 반환한다.
- **When** 사용자가 기기 서명 결과를 `POST /auth/mfa/verify { mfa_challenge_token, method: "webauthn", credential }`로 보내면
- **Then** 서버가 챌린지 토큰(미만료·purpose·1회용) + challenge로 `verifyAuthenticationResponseJSON`을 검증하고, signCount를 갱신한 뒤 정식 세션(`mfa_verified=true`)을 발급한다(`MFA_CHALLENGE_SUCCESS` 감사).
- **When** 서명 검증 실패/challenge 만료면
- **Then** 401 `invalid_code`(`MFA_CHALLENGE_FAILURE` 감사).

### S3. MFA 미사용 로그인 (회귀 없음)
- **Given** TOTP·WebAuthn 모두 미등록 사용자
- **When** 올바른 비밀번호로 로그인하면
- **Then** 기존과 동일하게 즉시 정식 세션 발급(`mfa_verified=false`). **기존 로그인 흐름 무변경**.

### S4. 보안 키 목록 조회 / 삭제 (로그인 사용자, JWT)
- **Given** 보안 키가 등록된 사용자
- **When** `GET .../webauthn`을 호출하면
- **Then** 등록된 키 목록(`id`, `name`, `created_at`, `last_used_at`)을 반환한다(공개키·credentialId raw는 미노출).
- **When** `DELETE .../webauthn/{id}`를 호출하면
- **Then** 본인 소유 키면 삭제하고 204(`MFA_WEBAUTHN_REMOVED` 감사). 마지막 MFA 수단이 사라져 강제 대상이 미등록이 되면, FR-MF-04 게이트가 다음 요청에서 재등록을 유도(별도 차단 로직 불요).
- **When** 타인 키/존재하지 않는 id면
- **Then** 404 `not_found`(소유 검증 — 본인 키만).

## 기능 요구사항 (FR)

- **FR-1** 등록 start — `WebAuthnManager` + 서버 설정(rpId·rpName·origin)으로 `PublicKeyCredentialCreationOptions`를 만들고, challenge를 Caffeine에 단기 저장(key=userId). pubKeyCredParams=[ES256(-7), RS256(-257)], attestation=`none`, excludeCredentials=기존 등록 credentialId(중복 등록 방지). **userVerification=`preferred`**(2차 인증 맥락 — 보안키 소유가 이미 2번째 factor라 생체/PIN 강제 안 함), **residentKey=`discouraged`**(passwordless 미지원이므로 discoverable credential 불요). user handle(`PublicKeyCredentialUserEntity.id`)=userId의 안정적 byte 표현(UUID 16바이트), name=username/email.
- **FR-2** 등록 finish — 저장된 challenge로 `verifyRegistrationResponseJSON` 수행. 성공 시 `attestedCredentialData`를 `AttestedCredentialDataConverter`로 byte[] 직렬화 후 base64로 저장, credentialId(base64url)·signCount(초기값=RegistrationData의 signCount)·name·aaguid 저장. challenge 소비.
- **FR-3** 로그인 assertion start — 챌린지 토큰 검증 후, 사용자의 등록 키로 `allowCredentials`를 채운 `PublicKeyCredentialRequestOptions` 반환 + challenge 저장(key=userId).
- **FR-4** 로그인 assertion verify — `/auth/mfa/verify`의 `method` 분기에 `webauthn` 추가. 저장 credential을 `CredentialRecord`로 복원해 `AuthenticationParameters`에 싣고 `verifyAuthenticationResponseJSON` 검증. 성공 시 `mfa_verified=true` 세션 발급.
- **FR-5** sign count 갱신(clone 방어) — 검증 성공 후 `AuthenticationData`의 새 signCount로 조건부 UPDATE(`WHERE sign_count < :new OR (저장=0 AND 신규=0)`). 새 값이 저장값 이하면 의심 — 검증 실패 처리(authenticator 자체가 0 고정인 경우는 예외 허용).
- **FR-6** `MfaChallenge` enum에 `WEBAUTHN` 추가(`spi/MfaChallenge.kt`, 현재 TOTP/NOT_IMPLEMENTED_YET).
- **FR-7** 키 목록/삭제 — 본인 키 목록 조회, 본인 키 삭제(소유 검증).
- **FR-8** MFA 활성 판정 통합 — 로그인 1단계의 "MFA 필요" 판정을 `mfaService.isEnabled(TOTP) OR webauthnService.hasActiveKey`로 확장(`isAnyMfaEnabled`). 기존 TOTP 단독 판정 자리를 합성.
- **FR-9** 감사 — `MFA_WEBAUTHN_REGISTERED` / `MFA_WEBAUTHN_REMOVED` 신규 + 기존 `MFA_CHALLENGE_SUCCESS` / `MFA_CHALLENGE_FAILURE` 재사용. best-effort(FR-AU-10 인프라).
- **FR-10** RP 설정 — `@ConfigurationProperties("bts.webauthn")`(rpId·rpName·origin) + 환경별 application yml/환경변수. 빈은 항상 등록, 값은 사용 시점 검증(부팅 안전성).

## 비기능 요구사항 (NFR)

- **NFR-1 (공개키 평문 저장)** WebAuthn 공개키는 비밀이 아니므로 평문 저장(표준 관행). TOTP secret(암호화)·백업코드(해시)와 달리 암호화/해시 불요. 단 무결성을 위해 직렬화 byte[]를 그대로 보존(변형 금지).
- **NFR-2 (challenge 1회용·단기)** challenge는 Caffeine에 TTL 5분·key=userId로 저장하고 finish/verify 성공 시 즉시 소비(삭제). 재사용·만료 challenge는 거부. 단일 호스트 in-memory(기존 `MfaChallengeTokenService`/`MfaAttemptLimiter` 선례).
- **NFR-3 (replay/clone 방어)** challenge nonce(매 ceremony 신규) + signCount 단조 증가 검증(조건부 UPDATE, TOCTOU 차단 — 메모리 advisory-lock-bigint-toctou 정신).
- **NFR-4 (origin/rpId 엄격 검증)** `ServerProperty`의 origin·rpId가 어긋나면 webauthn4j가 검증 실패. 환경별 설정 누락 시 모든 검증이 실패하므로 설정값 부팅 로깅(secret 아님).
- **NFR-5 (rate-limit)** assertion verify 실패를 기존 `MfaAttemptLimiter` 패턴으로 집계(5회/5분 → 429 `too_many_attempts`). TOTP 카운터와 통합 또는 병렬(구현 시 결정, 사용자별).
- **NFR-6 (계정 열거 방지)** register/list/delete는 JWT 인증 필수(본인만, PAT 403). 로그인 assertion start/verify는 챌린지 토큰 기반, 실패는 원인 무관 일반 메시지.
- **NFR-7 (가용성)** 감사 INSERT 실패가 흐름을 막지 않음(best-effort). webauthn4j 검증 예외는 도메인 결과(sealed)로 변환해 catch-all 500 변질 방지(메모리 catch-all-exceptionhandler-swallows-responsestatusexception).
- **NFR-8 (시각 의존)** last_used_at 등 시각 기록은 주입된 `Clock` 사용(메모리 authcontroller-revokesession-timebomb).
- **NFR-9 (webauthn4j 객체 직렬화 — 핵심 함정)** `PublicKeyCredentialCreationOptions`/`RequestOptions`를 **Spring 기본 ObjectMapper로 직렬화하면 WebAuthn 표준 JSON 형식이 깨진다**(challenge/byte[] base64url·enum 표기 등 커스텀 직렬화 필요). webauthn4j의 `ObjectConverter`(JSON converter)로 직렬화한 JSON 문자열을 반환하거나, webauthn4j Jackson 모듈을 별도 ObjectMapper에 등록해 사용한다. 컨트롤러는 options를 `String`(`produces=application/json`)으로 반환하는 방식을 기본으로 한다(Spring ↔ webauthn4j 직렬화 책임 분리, 메모리 fr-vr-04 WebMvcConfigurer 교훈 — 전역 컨버터 교체 금지).

## API 인터페이스 (REST)

| 메서드 | 경로 | 인증 | 요청 | 응답 |
|---|---|---|---|---|
| POST | `/api/v1/auth/mfa/webauthn/register/start` | JWT | — | 200 `PublicKeyCredentialCreationOptions`(JSON) |
| POST | `/api/v1/auth/mfa/webauthn/register/finish` | JWT | `{ credential, name }` | 201 `{ id, name }` / 400 `invalid_registration` |
| GET | `/api/v1/auth/mfa/webauthn` | JWT | — | 200 `{ keys: [{ id, name, created_at, last_used_at }] }` |
| DELETE | `/api/v1/auth/mfa/webauthn/{id}` | JWT | — | 204 / 404 `not_found` |
| POST | `/api/v1/auth/mfa/webauthn/authenticate/start` | 챌린지 토큰(body) | `{ mfa_challenge_token }` | 200 `PublicKeyCredentialRequestOptions`(JSON) / 401 `mfa_challenge_expired` |
| POST | `/api/v1/auth/mfa/verify` (확장) | 챌린지 토큰(body) | `{ mfa_challenge_token, method: "webauthn", credential }` | 200 `{ access_token }` + Set-Cookie / 401 `invalid_code` |
| POST | `/api/v1/auth/login` (무변경) | — | 기존 | MFA(any) 활성 사용자: `{ mfa_required, mfa_challenge_token, expires_in }` |

- register/list/delete는 JWT 전용(PAT 403 — 세션 관리 선례).
- authenticate/start·verify는 챌린지 토큰 기반(아직 정식 세션 아님). `SecurityConfig` permitAll + CSRF 처리(login/verify 선례 동일).
- credential 필드는 브라우저 `navigator.credentials` 응답을 그대로 직렬화한 JSON 문자열(webauthn4j `verify...ResponseJSON`가 직접 파싱).
- `PublicKeyCredentialCreationOptions`/`RequestOptions`는 webauthn4j 객체를 JSON 직렬화해 반환(프론트가 D6에서 그대로 `navigator.credentials.create/get`에 사용).

## 데이터 모델 변경

`V025__mfa_webauthn.sql` (identity-access, jdbc-only → init_codegen 미러 불요 — 메모리 fr-au-07-domain-routing-done)

```sql
CREATE TABLE webauthn_credentials (
    id                      UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id                 UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    credential_id           TEXT        NOT NULL,             -- base64url(rawId)
    attested_credential_data TEXT       NOT NULL,             -- base64(AttestedCredentialDataConverter 직렬화), 공개키+aaguid
    sign_count              BIGINT      NOT NULL DEFAULT 0,   -- clone 방어
    name                    VARCHAR(100),                     -- 사용자 지정 별칭
    aaguid                  TEXT,                             -- authenticator 모델 식별(분석용, nullable)
    last_used_at            TIMESTAMPTZ,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_webauthn_credential_id ON webauthn_credentials(credential_id);
CREATE INDEX idx_webauthn_credentials_user ON webauthn_credentials(user_id);
```

- **사용자당 N행**(TOTP의 1행과 다름). `credential_id`는 전역 UNIQUE(WebAuthn credentialId는 전역 유일).
- **status 컬럼 없음** — TOTP는 setup(PENDING)→enable(ACTIVE) 2-step이 필요하지만, WebAuthn은 register/finish의 attestation 검증이 곧 소유 증명이라 **검증 후 INSERT = 즉시 활성**. PENDING 상태 불요(ADR D3 단순화 반영).
- 사용자 삭제 시 CASCADE(메모리 join-table-fk-cascade 관례).
- `sessions.mfa_verified`(V022)는 그대로 재사용. 신규 컬럼 없음.

## 엣지 케이스

- **EC-1** register/start 후 challenge 만료(5분 초과) → finish 시 400 `invalid_registration`.
- **EC-2** 동일 기기 중복 등록 시도 → `excludeCredentials`로 브라우저가 차단(서버도 credential_id UNIQUE로 2차 차단, 충돌 시 409 `already_registered`).
- **EC-3** assertion verify에서 signCount가 저장값 이하 → clone 의심, 401 `invalid_code`(authenticator가 signCount=0 고정인 정상 케이스는 0==0 허용).
- **EC-4** 챌린지 토큰 만료/위조 → 401(원인 비노출, FR-MF-01 EC-3/4 동일).
- **EC-5** allowCredentials 빈 목록(키 0개)인데 authenticate/start 호출 → 사용자에게 등록 키 없음(이론상 1단계 판정에서 걸러짐, 방어적 400).
- **EC-6** 타인 credentialId로 verify 시도 → credential 소유자≠챌린지 토큰 userId면 검증 실패(401).
- **EC-7** RP 설정(rpId/origin) 미설정 환경 → 빈은 등록되되 사용 시점 검증 실패로 명확한 에러(부팅은 성공 — 메모리 profile-scoped-bean-boot-failure).
- **EC-8** PAT로 register/list/delete → 403(세션 관리 선례).
- **EC-9** 동시 verify(두 탭) → challenge 1회용 소비로 한쪽만 성공(Caffeine atomic invalidate).
- **EC-10** 마지막 보안 키 삭제(TOTP도 없음) → MFA 완전 해제(비번 로그인 복귀). 강제 대상이면 FR-MF-04 게이트가 재등록 유도(본 PR은 삭제 자체를 막지 않음).

## 제약 조건

- 신규 maven 의존성 `com.webauthn4j:webauthn4j-core:0.28.4.RELEASE`(+ test `webauthn4j-test`). **0.31+ 금지(Jackson 3 충돌)**. 모듈 `build.gradle.kts`에 직접 버전 명시(BTS는 버전 카탈로그 미사용 — Explore 실측, 절대 규칙 #17).
- 단일 BC(identity-access). 프론트(`navigator.credentials`)는 D6 후속 PR(백엔드 slice라 본 PR은 same-BC 백엔드만).
- 기존 로그인 흐름(MFA 미활성)·기존 TOTP/백업코드 흐름 무변경(회귀 0). 1단계 MFA 판정만 `isAnyMfaEnabled`로 합성.
- challenge·credential·서명 raw는 로그/예외 메시지에 노출 금지(공개키는 비밀 아니나 일관성).

## 측정 가능한 완료 기준

1. MFA 미활성 사용자 로그인 회귀 0(기존 `AuthControllerTest` green).
2. register start→finish→DB INSERT→login(2단계 webauthn)→verify→정식세션(`mfa_verified=true`) end-to-end 통합 테스트 통과(Testcontainers + `webauthn4j-test` EmulatorAuthenticator로 가상 ceremony).
3. signCount clone 방어: 저장값 이하 signCount 재제출 시 거부 테스트.
4. challenge 1회용/만료 거부 테스트.
5. 사용자당 다중 키 등록 + 개별 삭제 + 소유 검증(타인 키 404) 테스트.
6. RP 설정 미설정 환경 부팅 성공 + 사용 시점 검증 실패 테스트.
7. `./gradlew :backend:modules:identity-access:test ktlintCheck detekt`(--rerun-tasks) green.
8. SDD §19.7 / fr-index FR-MF-03 D1~D5 마킹 + product/identity-access 체크박스 동기화(verify-master-plan.sh 통과). D6/D7은 미체크 유지.

## Brainstorming Check

✅ 통과 (1회 iteration, adversarial self-review). 발견 3건 모두 spec 내 보강(Maxi 결정 불요).
- GAP-1 (correctness): webauthn4j 객체를 Spring 기본 ObjectMapper로 직렬화 시 WebAuthn 표준 JSON 깨짐 → webauthn4j `ObjectConverter` 직렬화 + 컨트롤러 String 반환 (NFR-9).
- GAP-2 (clarify): userVerification/residentKey 정책 미명시 → preferred/discouraged (2차 인증 맥락, FR-1).
- GAP-3 (clarify): user handle 구성 미명시 → `PublicKeyCredentialUserEntity.id`=userId 16바이트 (FR-1).

## 알려진 함정 (구현 시 회귀 가드)
- **webauthn4j 0.31+ 금지** — Jackson 3 의존, SB 3.3.5(Jackson 2)와 충돌. 0.28.4.RELEASE 고정.
- **webauthn4j 객체 직렬화** — 전역 ObjectMapper 교체 금지(ProblemDetail/기존 직렬화 보존). options는 webauthn4j ObjectConverter로 String 반환(메모리 fr-vr-04-release-notes-done).
- `AuthEventType` enum에 MFA WebAuthn 이벤트 2종 추가 → emit 커버리지 가드 갱신(메모리 enum-add-breaks-crossmodule-count-guard, identity-access 단일 모듈).
- catch-all `@ExceptionHandler`가 webauthn4j 검증 예외를 500으로 변질시키지 않도록 도메인 sealed 결과로 변환(메모리 catch-all-exceptionhandler-swallows-responsestatusexception).
- identity-access prod+RANDOM_PORT 통합테스트 부팅 레시피 준수 + MFA 암호화 키 주입(메모리 identity-access-prod-randomport-boot-recipe). WebAuthn은 공개키 평문이라 암호화 키 불요하나 기존 MFA 빈 부팅 위해 레시피 유지.
- 1단계 MFA 판정 `isAnyMfaEnabled` 합성 시 기존 TOTP 단독 판정 테스트 회귀 확인.
- detekt baseline은 `--rerun-tasks` 실검증 + 모듈 baseline 동결만(메모리 backend-detekt-lint-debt-unmasked, detekt-baseline-module-pattern).
