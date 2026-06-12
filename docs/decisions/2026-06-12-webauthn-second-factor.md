<!-- FR-MF-03 WebAuthn(Passkey/하드웨어 키)의 역할 정립·attestation 정책·도메인 모델 결정을 기록하는 ADR -->
# ADR — WebAuthn 2차 인증 (FR-MF-03)

> 날짜: 2026-06-12
> 상태: 채택
> 관련 FR: FR-MF-03
> 관련 PR: #129
> BC: identity-access

## 맥락

FR-MF-01(TOTP)·FR-MF-02(백업 코드)로 BTS는 MFA(다중 요소 인증) 인프라를 갖췄다. FR-MF-03은 여기에 **WebAuthn**(FIDO2 표준 — 지문·얼굴·USB 보안키 같은 기기 기반 인증)을 추가한다. 우선순위는 '선택'(SDD §2.2.13), 로드맵상 Phase 4 작업을 당겨서 진행한다.

WebAuthn은 기존 TOTP/백업 코드와 **인증 패러다임이 다르다**.

- TOTP는 stateless 코드 검증이다 — 서버가 secret을 알고, 클라이언트가 보낸 6자리를 시간창 안에서 대조한다.
- WebAuthn은 **challenge-response**다 — 서버가 랜덤 nonce(challenge)를 발급하고, 기기(authenticator)가 비공개키로 서명해 돌려보내면 서버가 등록된 공개키로 검증한다. 서버는 비밀을 보관하지 않고 **공개키만** 저장한다.

이 차이로 인해 (1) WebAuthn의 역할(2차 인증 vs passwordless), (2) 등록 시 기기 진위 검증 수준(attestation), (3) 사용자당 credential 개수 같은 도메인 결정이 필요하다.

## 결정

### D1. WebAuthn = 2차 인증 수단(second factor) only — passwordless/passkey 제외

WebAuthn은 비밀번호 통과 후 거치는 **추가 인증 단계**로만 도입한다. 기존 TOTP·백업 코드와 같은 자리다. 비밀번호 없이 보안키만으로 1단계 로그인하는 **패스키(passwordless)는 이번 범위에서 제외**한다.

근거.
- glossary §인증 정의가 'WebAuthn = 2FA의 한 수단'으로 분류한다("2FA = TOTP + 백업 코드(필수), WebAuthn(선택)").
- FR-MF 시리즈 전체가 MFA(2단계) 맥락이고, §3.3 D단계도 attestation(등록)+assertion(2차 검증) 흐름이다.
- passwordless는 로그인 1단계 흐름 재설계 + 계정 열거 방어 + resident key(discoverable credential) 처리 + 사용자 핸들 관리가 필요해 FR-MF-03 원안(2FA) 범위를 크게 넘는다.

이에 따라 로그인 흐름은 기존 2단계를 **그대로 재사용**한다 — 1단계 비번 통과 → `mfa_required` + 5분 challenge JWT → `/api/v1/auth/mfa/verify`에서 `method=webauthn` 분기. `MfaChallenge` enum(`spi/MfaChallenge.kt`)에 `WEBAUTHN`을 추가한다.

**대안(기각).** passwordless까지 한 번에 — 사용자 가치는 크지만 범위·위험이 2FA의 수 배. '선택' 우선순위 FR에 과투자. 추후 별도 FR로 분리 가능.

### D2. attestation = none

등록 시 기기의 제조사 증명서 체인을 검증하지 않고 **공개키만 저장**한다. 어떤 표준 WebAuthn authenticator든 허용한다.

근거.
- 사내 1,000명 규모 + '선택' 우선순위에서 full attestation(metadata service·루트 CA 관리·허용 기기 화이트리스트)의 운영 부담이 효익을 크게 초과한다.
- Google·GitHub 등 대부분의 서비스 기본값이 attestation=none이다.
- 특정 제조사 보안키 강제(예. YubiKey only)는 엔터프라이즈 규제 환경 요구사항으로, 필요해지면 후속 FR에서 full로 강화 가능하다(공개키 저장 구조는 그대로 유지).

### D3. credential은 사용자당 N개 (다중 등록)

TOTP는 사용자당 1개(`totp_secrets` PK=user_id)지만, WebAuthn은 **사용자당 여러 개**를 등록할 수 있다(집 노트북 지문 + 회사 데스크톱 + USB 보안키…). 따라서 `webauthn_credentials`는 user당 N행, PK=id(surrogate), `UNIQUE(user_id, credential_id)`로 중복 등록을 차단한다.

clone 방어. authenticator가 매 인증마다 증가시키는 `sign_count`를 저장하고, 검증 시 저장값보다 큰지 확인해 복제된 기기를 탐지한다(TOTP `last_verified_step` 단조 증가 방어와 같은 정신).

### D4. webauthn4j 라이브러리 + 기존 MFA 공유 컴포넌트 재사용

FIDO2의 attestation·assertion·CBOR 파싱을 직접 구현하는 것은 보안상 위험하다. 검증된 `com.webauthn4j:webauthn4j-core`를 도입한다(Maxi 승인 완료, DEVELOPMENT.md §외부 의존성 / 절대 규칙 #17 — 직접 의존 명시 선언).

**버전 = 0.28.4.RELEASE 고정(Jackson 2 호환).** webauthn4j는 **0.31.0.RELEASE(2026-02-01)부터 Jackson 3**(`tools.jackson`)에 의존한다. BTS는 **Spring Boot 3.3.5 = Jackson 2.x**(`com.fasterxml.jackson`)이므로 0.31+는 클래스패스 충돌/이중 Jackson을 유발한다. 따라서 0.31 미만(Maven 공개 최신 안정판 0.28.4)을 채택한다. Spring Boot가 Jackson 3(SB 4+)로 상향되는 시점에 webauthn4j 0.31+로 동반 상향. 진입점은 `WebAuthnManager.createNonStrictWebAuthnManager()`(attestation 미검증=none 일치). 테스트는 `webauthn4j-test`(EmulatorAuthenticator)로 가상 ceremony.

기존 MFA 인프라를 재사용한다.
- 시도 제한. `MfaAttemptLimiter` 패턴.
- 감사. `AuthAuditLogService`(method="webauthn").
- 로그인 2단계 진입권. `MfaChallengeTokenService` 재사용(method로 분기).

**공개키 = 평문 저장(확정).** WebAuthn 공개키는 비밀이 아니므로 암호화/해시하지 않는다(표준 관행). `attestedCredentialData`를 `AttestedCredentialDataConverter`로 byte[] 직렬화 후 base64 TEXT로 보존한다(TOTP secret 암호화·백업코드 해시와 다름). `MfaSecretEncryptor`는 WebAuthn에 사용하지 않는다.

### D5. WebAuthn challenge(nonce) 저장 = Caffeine 캐시(확정)

WebAuthn의 등록/로그인 challenge nonce는 기존 challenge JWT(`MfaChallengeTokenService` — '2단계 진입권')와 **다른** 개념이다. FIDO2 서명 대상 nonce로, 발급(start)→기기 서명→대조(finish)까지 짧게 보관한다. 저장은 **Caffeine 캐시**(TTL 5분, key=userId, 1회용 소비)로 한다 — 기존 `MfaChallengeTokenService`/`MfaAttemptLimiter`의 in-memory Caffeine 선례와 일관되고, BTS의 stateless JWT 인증에 부합한다(단일 호스트 — Naver Cloud Docker Compose). HttpSession(FR-AU-08b LinkingIntent 선례)은 SameSite/JSESSIONID 복잡도를 동반해 단순 nonce 왕복에는 과하므로 기각.

### D5b. status 컬럼 없음 — 검증 후 INSERT = 즉시 활성

TOTP는 setup(PENDING secret 저장 → QR 표시)→enable(코드 검증 → ACTIVE) 2-step이 필요하다. WebAuthn은 register/start가 challenge만 임시 저장(Caffeine)하고, register/finish의 **attestation 검증 성공이 곧 기기 소유 증명**이라 그 시점에 credential을 INSERT한다 — PENDING 상태가 의미 없다. 따라서 `webauthn_credentials`에 status 컬럼을 두지 않고 **row 존재 = 활성**으로 한다(도메인 정리 단계의 PENDING/ACTIVE 표기를 단순화).

### D6. PR 범위 = 백엔드 D1~D5 먼저

도메인·FIDO2 명세·`webauthn_credentials` 스키마·webauthn4j 백엔드·가상 authenticator 테스트까지 이 PR. 프론트(D6 `navigator.credentials`)·E2E(D7)는 후속 PR. FR-MF-01/02/04와 동일한 분할.

## 결과

- identity-access: `WebAuthnCredential` VO + `WebAuthnService`(webauthn4j 래핑) + 오케스트레이션 서비스 + JDBC 리포지토리 + `MfaController` 엔드포인트 확장 + `MfaChallenge.WEBAUTHN` + `AuthController` verify 분기 + 통합 테스트.
- DB: V025 마이그레이션(`webauthn_credentials`). identity-access는 jOOQ 코드 생성 비대상(jdbc-only)이라 init_codegen 미러 불요(FR-AU-07 선례).
- 의존성: `webauthn4j-core` 신규 선언.
- 새 용어(glossary 추가 후보, Maxi 승인 후): WebAuthn, FIDO2, attestation, assertion, RP ID(Relying Party), credential ID, sign count.

## 위험 / 후속

- **RP ID/origin 환경별 설정** — WebAuthn은 Relying Party ID(도메인)와 origin 검증이 필수다. `@ConfigurationProperties("bts.webauthn")`(rpId·rpName·origin) + 환경별 application yml/환경변수. 잘못/누락 설정 시 모든 검증 실패하므로 빈은 항상 등록·값은 사용 시점 검증(부팅 안전성). spec NFR-4/FR-10/EC-7.
- **webauthn4j 객체 직렬화 함정** — `PublicKeyCredentialCreationOptions`/`RequestOptions`를 Spring 기본 ObjectMapper로 직렬화하면 표준 JSON이 깨진다. webauthn4j `ObjectConverter`로 직렬화해 String 반환(전역 컨버터 교체 금지). spec NFR-9.
- **가상 authenticator 테스트** — `webauthn4j-test`(EmulatorAuthenticator/ClientPlatform)로 실기기 없이 ceremony 생성. 통합 테스트 부팅 레시피(identity-access prod+RANDOM_PORT)는 기존 MFA 통합테스트 재사용.
- D6/D7 프론트(`navigator.credentials`) + E2E(가상 authenticator) 후속.
