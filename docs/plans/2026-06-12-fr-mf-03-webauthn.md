# FR-MF-03 백엔드 (D1~D5) — WebAuthn(Passkey/하드웨어 키) FIDO2 서버

> slug: fr-mf-03-webauthn
> type: auth
> agent: security-engineer
> primary_bc: identity-access
> 생성: 2026-06-12

## Brief

**원문 요청**. "fr-mf-03 진행하자"

**확정 범위 (Maxi 결정)**.
- 이번 PR = 백엔드 slice **D1~D5만** (도메인·FIDO2 명세·credentials 스키마·webauthn4j 백엔드·가상 Authenticator 테스트). 프론트(D6 `navigator.credentials`)·E2E(D7)는 **후속 PR** — FR-MF-01/02/04 동일 분할 패턴.
- 외부 라이브러리 = **webauthn4j 도입 확정** (product 문서 D4 명시, FIDO2 표준, DEVELOPMENT.md §외부 의존성 Maxi 승인 완료).

**컨텍스트 메모**.
- FR-MF-03 우선순위 = **선택** (FR-MF-01/02/04 는 필수), SDD 로드맵상 **Phase 4(Polish)** 작업을 당겨서 진행.
- 선행 §3.1 TOTP 완료(#113/#116), §3.2 백업코드 완료(#117/#121), §3.4 강제정책 완료(#123/#128).
- product 정본. `docs/plan/product/identity-access.md §3.3` (L208~218).
- SDD. `19-authentication.md:113`, `02-requirements.md:232`.

## 도메인 정리

- **BC**. identity-access (단일)
- **영향 엔티티**.
  - `WebAuthnCredential` (신규 VO — 도메인 노트 identity-access.md:23에 "미래 PR"로 예약된 이름). 사용자당 **N개**(다중 등록), status PENDING/ACTIVE, sign_count(clone 방어).
  - `MfaChallenge` enum 확장 — `WEBAUTHN` 추가 (`spi/MfaChallenge.kt`, 현재 TOTP/NOT_IMPLEMENTED_YET).
  - `sessions.mfa_verified` 재사용(기존 V022 컬럼, 신규 불요).
- **핵심 정책 결정 (Maxi 확정)**.
  1. WebAuthn = **2차 인증 수단(second factor) only** — 패스키/passwordless 제외. 기존 2단계 로그인 흐름 재사용.
  2. **attestation = none** — 공개키만 저장, 표준 보안키 모두 허용.
  3. credential 사용자당 N개 / sign_count clone 방어.
  4. **webauthn4j 도입** + 기존 MFA 공유 컴포넌트(`MfaSecretEncryptor`·`MfaAttemptLimiter`·`AuthAuditLogService`·`MfaChallengeTokenService`) 재사용.
- **새 용어 (glossary 추가 후보, Maxi 승인 필요)**. WebAuthn · FIDO2 · attestation(등록 증명) · assertion(로그인 검증) · RP ID(Relying Party) · credential ID · sign count.
- **기존 결정 충돌**. 없음. FR-MF-01/02/04 인프라 위에 자연스럽게 확장.
- **spec에서 확정할 열린 결정**. (a) WebAuthn challenge nonce 저장 위치(HttpSession vs Caffeine), (b) 공개키 암호화 저장 여부, (c) RP ID/origin 환경별 설정.
- **관련 ADR**. [docs/decisions/2026-06-12-webauthn-second-factor.md](../decisions/2026-06-12-webauthn-second-factor.md) (생성됨)
- **다음 마이그레이션**. V025 (`webauthn_credentials`). identity-access는 jdbc-only → init_codegen 미러 불요.

## 스펙

전체 스펙. [docs/specs/2026-06-12-fr-mf-03-webauthn.md](../specs/2026-06-12-fr-mf-03-webauthn.md)

핵심 시나리오 3줄 요약.
- 등록(JWT): register/start(challenge 발급+Caffeine 저장) → 기기 서명 → register/finish(attestation 검증 후 credential INSERT, 즉시 활성)
- 로그인 2단계: 비번 통과 → mfa_challenge_token → authenticate/start(challenge) → 기기 서명 → /mfa/verify(method=webauthn) → signCount 갱신 → mfa_verified 세션
- 관리(JWT): 보안 키 목록 조회 / 삭제(본인 키만)

핵심 기술 확정.
- **webauthn4j 0.28.4.RELEASE**(Jackson 2, SB 3.3.5 호환). **0.31+ 금지**(Jackson 3 충돌). `createNonStrictWebAuthnManager()`(attestation none).
- challenge=Caffeine(TTL 5분, 1회용) / 공개키=평문 저장(base64) / status 컬럼 없음(검증 후 INSERT=활성) / 사용자당 N개 + signCount clone 방어.
- RP 설정=`@ConfigurationProperties("bts.webauthn")`. webauthn4j 객체는 자체 ObjectConverter로 직렬화(String 반환).
- V025 마이그레이션(`webauthn_credentials`, jdbc-only → init_codegen 미러 불요).

## Brainstorming Check

✅ 통과 (1회 iteration, adversarial self-review). gap 3건 spec 내 보강(Maxi 결정 불요).
- GAP-1(correctness): webauthn4j 객체 Spring 직렬화 시 표준 JSON 깨짐 → ObjectConverter 직렬화(NFR-9)
- GAP-2(clarify): userVerification=preferred / residentKey=discouraged(2차 인증 맥락, FR-1)
- GAP-3(clarify): user handle = userId 16바이트(FR-1)

## Plan

> 경로 약칭. `«main»` = `backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity`, `«test»` = `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity`, `«res»` = `backend/modules/identity-access/src/main/resources`.
> 모든 task TDD 강제(test 커밋이 feat 커밋보다 먼저). spec=docs/specs/2026-06-12-fr-mf-03-webauthn.md, ADR=docs/decisions/2026-06-12-webauthn-second-factor.md 참조.

### Task 1. webauthn4j 의존성 + WebAuthn 설정/빈

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/identity-access/build.gradle.kts`, `«main»/mfa/WebAuthnProperties.kt`, `«main»/mfa/WebAuthnConfig.kt`, `«res»/application.yml`, `«test»/mfa/WebAuthnConfigTest.kt`]
- depends-on: []

**RED**. `WebAuthnConfigTest` — `@ConfigurationProperties("bts.webauthn")` 바인딩(rpId/rpName/origin)과 `WebAuthnManager` 빈 생성 검증. 빈/클래스 부재로 실패.

**GREEN**.
- `build.gradle.kts`. `implementation("com.webauthn4j:webauthn4j-core:0.28.4.RELEASE")` + `testImplementation("com.webauthn4j:webauthn4j-test:0.28.4.RELEASE")`. **0.31+ 금지(Jackson 3 충돌, spec 라이브러리 결정)**. 버전 직접 명시(절대 규칙 #17).
- `WebAuthnProperties`. `@ConfigurationProperties("bts.webauthn")` data class(rpId, rpName, origin). 빈 항상 등록.
- `WebAuthnConfig`. `@Bean WebAuthnManager` = `WebAuthnManager.createNonStrictWebAuthnManager()`(attestation none) + `@Bean ObjectConverter`(webauthn4j JSON 직렬화용, MfaEncryptionConfig 선례 위치).
- `application.yml`. `bts.webauthn.{rp-id: localhost, rp-name: BTS, origin: http://localhost:5173}` 기본값(환경별 override).

**REFACTOR**. KDoc(L1 한국어 헤더 주석). 값은 사용 시점 검증(부팅 안전성, 메모리 profile-scoped-bean-boot-failure).

**검증**. `./gradlew :backend:modules:identity-access:test --tests "*WebAuthnConfigTest"`

---

### Task 2. V025 마이그레이션 + 스키마 테스트

**메타**.
- agent: `db-engineer`
- files: [`«res»/db/migration/V025__mfa_webauthn.sql`, `«test»/mfa/WebauthnCredentialsSchemaTest.kt`]
- depends-on: []

**RED**. `WebauthnCredentialsSchemaTest`(Testcontainers) — `webauthn_credentials` 테이블/컬럼/`uq_webauthn_credential_id` UNIQUE/`idx_webauthn_credentials_user` 존재 검증. 테이블 부재로 실패.

**GREEN**. `V025__mfa_webauthn.sql`(spec §데이터 모델 그대로). id(UUID PK)·user_id(FK CASCADE)·credential_id(TEXT)·attested_credential_data(TEXT base64)·sign_count(BIGINT)·name·aaguid·last_used_at·created/updated_at. `UNIQUE(credential_id)` + `INDEX(user_id)`. **status 컬럼 없음**(ADR D5b). identity-access는 jdbc-only → init_codegen 미러 불요.

**REFACTOR**. 컬럼 COMMENT(clone 방어 sign_count 등).

**검증**. `./gradlew :backend:modules:identity-access:test --tests "*WebauthnCredentialsSchemaTest"`. 동시 브랜치 V번호 충돌은 머지 직전 재확인(메모리 migration-vnumber-concurrent-branch-collision).

---

### Task 3. WebAuthnCredential VO + 리포지토리

**메타**.
- agent: `security-engineer`
- files: [`«main»/mfa/WebAuthnCredential.kt`, `«main»/mfa/WebAuthnCredentialRepository.kt`, `«main»/mfa/JdbcWebAuthnCredentialRepository.kt`, `«test»/mfa/JdbcWebAuthnCredentialRepositoryTest.kt`]
- depends-on: [2]

**RED**. `JdbcWebAuthnCredentialRepositoryTest`(Testcontainers) — insert/findByUser(N개)/findByCredentialId/deleteByIdAndUser(소유검증)/advanceSignCount(조건부) CRUD. 클래스 부재로 실패.

**GREEN**.
- `WebAuthnCredential` VO(id, userId, credentialId, attestedCredentialData[base64], signCount, name?, aaguid?, lastUsedAt?, createdAt, updatedAt).
- `WebAuthnCredentialRepository` interface + `JdbcWebAuthnCredentialRepository`(@Transactional, @Repository). `insert`, `findByUser(userId): List`, `findByCredentialId(credentialId)`, `deleteByIdAndUser(id, userId): Boolean`(소유검증), `advanceSignCount(id, newCount): Boolean`(조건부 UPDATE `WHERE sign_count < :new OR (sign_count=0 AND :new=0)`, TOCTOU 차단), `touchLastUsed(id, at)`. RowMapper. `:param` 바인딩.

**REFACTOR**. SQL 상수 추출, KDoc.

**검증**. `./gradlew :backend:modules:identity-access:test --tests "*JdbcWebAuthnCredentialRepositoryTest"`

---

### Task 4. WebAuthnChallengeStore (Caffeine)

**메타**.
- agent: `security-engineer`
- files: [`«main»/mfa/WebAuthnChallengeStore.kt`, `«test»/mfa/WebAuthnChallengeStoreTest.kt`]
- depends-on: []

**RED**. `WebAuthnChallengeStoreTest` — issue→consume 성공, 재소비 실패(1회용), 만료 후 부재, key=userId 격리. 클래스 부재로 실패.

**GREEN**. `WebAuthnChallengeStore`(Caffeine, TTL 5분, key=userId, value=`Challenge`). `issue(userId): Challenge`(생성+저장), `consume(userId): Challenge?`(조회+atomic invalidate). 단일 호스트 in-memory(MfaChallengeTokenService 선례).

**REFACTOR**. TTL 상수, KDoc(기존 challenge JWT와 다른 개념임 명시).

**검증**. `./gradlew :backend:modules:identity-access:test --tests "*WebAuthnChallengeStoreTest"`

---

### Task 5. WebAuthnService (webauthn4j 래핑 + 직렬화)

**메타**.
- agent: `security-engineer`
- files: [`«main»/mfa/WebAuthnService.kt`, `«test»/mfa/WebAuthnServiceTest.kt`]
- depends-on: [1]

**RED**. `WebAuthnServiceTest` — `webauthn4j-test` `EmulatorAuthenticator`/`ClientPlatform`로 가상 ceremony 생성 후 (a) createRegistrationOptions→verifyRegistration round-trip, (b) createAuthenticationOptions→verifyAuthentication round-trip, (c) 잘못된 challenge/origin 검증 실패. 클래스 부재로 실패.

**GREEN**. `WebAuthnService`(WebAuthnManager + WebAuthnProperties + ObjectConverter 주입).
- `registrationOptionsJson(challenge, user, excludeCredentialIds): String` — `PublicKeyCredentialCreationOptions`(rpId/rpName, user handle=userId 16바이트, pubKeyCredParams=[ES256(-7),RS256(-257)], attestation=none, userVerification=preferred, residentKey=discouraged, excludeCredentials) 생성 후 **ObjectConverter로 JSON 직렬화**(NFR-9).
- `verifyRegistration(responseJson, challenge): RegistrationData` — `ServerProperty(origin, rpId, challenge)` + `RegistrationParameters` → `verifyRegistrationResponseJSON`.
- `authenticationOptionsJson(challenge, allowCredentialIds): String` — `PublicKeyCredentialRequestOptions` 직렬화.
- `verifyAuthentication(responseJson, credentialRecord, challenge): AuthenticationData` — `AuthenticationParameters`(serverProperty, credentialRecord, allowCredentials, userVerificationRequired, userPresenceRequired) → `verifyAuthenticationResponseJSON`.
- credential 직렬화 헬퍼. `AttestedCredentialDataConverter(objectConverter)` ↔ base64.

**REFACTOR**. pubKeyCredParams 상수, COSEAlgorithmIdentifier 상수, KDoc. webauthn4j 예외는 호출자(T6)가 sealed result로 변환하도록 그대로 throw.

**검증**. `./gradlew :backend:modules:identity-access:test --tests "*WebAuthnServiceTest"`

---

### Task 6. WebAuthnSecurityKeyService 오케스트레이션 + 감사 이벤트

**메타**.
- agent: `security-engineer`
- files: [`«main»/mfa/WebAuthnSecurityKeyService.kt`, `«main»/audit/AuthEventType.kt`, `«test»/mfa/WebAuthnSecurityKeyServiceTest.kt`, `«test»/audit/AuthEventEmitCoverageTest.kt`(있으면 갱신)]
- depends-on: [3, 4, 5]

**RED**. `WebAuthnSecurityKeyServiceTest` — registerStart/registerFinish(성공·challenge만료·검증실패)·authenticateStart·verifyLogin(성공·signCount clone거부·실패)·listKeys·deleteKey(소유검증) sealed result. 가상 authenticator(webauthn4j-test) + mock repo/challenge store.

**GREEN**.
- `AuthEventType`에 `MFA_WEBAUTHN_REGISTERED`, `MFA_WEBAUTHN_REMOVED` 추가(emit coverage 가드 동반 갱신 — 메모리 enum-add-breaks-crossmodule-count-guard).
- `WebAuthnSecurityKeyService`(@Service @Transactional). 의존: WebAuthnService·WebAuthnCredentialRepository·WebAuthnChallengeStore·AuthAuditLogService·MfaAttemptLimiter.
  - `registerStart(userId): String`(challenge issue + options json, excludeCredentials=기존 키)
  - `registerFinish(userId, responseJson, name): RegisterResult`(challenge consume → verifyRegistration → credentialId UNIQUE 충돌=409 → insert → MFA_WEBAUTHN_REGISTERED). sealed.
  - `authenticateStart(userId): String`(challenge issue + options json, allowCredentials=등록 키)
  - `verifyLogin(userId, responseJson): VerifyResult`(challenge consume → credential 복원 → verifyAuthentication → advanceSignCount(clone 거부) → touchLastUsed). VerifyResult.Success/InvalidAssertion/TooManyAttempts(MfaAttemptLimiter).
  - `listKeys(userId)`, `deleteKey(userId, id): Boolean`(소유검증 + MFA_WEBAUTHN_REMOVED), `hasActiveKey(userId): Boolean`.
- webauthn4j 검증 예외 → sealed result 변환(catch-all 500 변질 방지, NFR-7).

**REFACTOR**. result 매핑 헬퍼, KDoc, best-effort 감사(권한예외 포함 금지 — 메모리 best-effort-loop-permission-exception-nonprod-mask).

**검증**. `./gradlew :backend:modules:identity-access:test --tests "*WebAuthnSecurityKeyServiceTest" --tests "*AuthEventEmitCoverageTest"`

---

### Task 7. 컨트롤러 통합 — 엔드포인트 + verify 분기 + MFA 판정 합성

**메타**.
- agent: `security-engineer`
- files: [`«main»/web/MfaController.kt`, `«main»/web/AuthController.kt`, `«main»/spi/MfaChallenge.kt`, `«test»/web/MfaWebAuthnControllerTest.kt`]
- depends-on: [6]

**RED**. `MfaWebAuthnControllerTest`(@WebMvcTest 또는 slice) — register/start·finish·GET·DELETE·authenticate/start·verify(method=webauthn) 상태코드/PAT 403/소유검증 404. 엔드포인트 부재로 실패.

**GREEN**.
- `MfaChallenge`에 `WEBAUTHN` 추가(NOT_IMPLEMENTED_YET 정리).
- `MfaController`. `POST /webauthn/register/start`(200 options json), `POST /webauthn/register/finish`(201 {id,name}/400/409), `GET /webauthn`(키 목록), `DELETE /webauthn/{id}`(204/404), `POST /webauthn/authenticate/start`(200 options/401). JWT 전용(PAT 403). options는 `String` produces=application/json(NFR-9).
- `AuthController`. (1) line 181 `mfaService.isEnabled` → `isAnyMfaEnabled`(TOTP `isEnabled` OR `webauthnSecurityKeyService.hasActiveKey`)로 합성. (2) `/mfa/verify`의 method 분기에 `webauthn` 추가 → `mapWebauthnResult`(Success→issueTokens mfaVerified=true / InvalidAssertion→401 invalid_code / TooManyAttempts→429). 도메인 결과 직접 ResponseEntity 매핑(catch-all 회귀 방지).

**REFACTOR**. DTO(WebAuthnRegisterFinishRequest{credential,name}, WebAuthnVerify는 기존 MfaVerifyRequest 확장), KDoc.

**검증**. `./gradlew :backend:modules:identity-access:test --tests "*MfaWebAuthnControllerTest" --tests "*AuthControllerTest"`(기존 회귀 0)

---

### Task 8. 통합 테스트 (end-to-end, 가상 authenticator)

**메타**.
- agent: `security-engineer` (E2E 인프라 보강은 qa-engineer 검토 가능)
- files: [`«test»/integration/MfaWebauthnIntegrationTest.kt`]
- depends-on: [7]

**RED→GREEN**. `MfaWebauthnIntegrationTest`(@SpringBootTest RANDOM_PORT, Testcontainers, prod 부팅 레시피 — 메모리 identity-access-prod-randomport-boot-recipe). `webauthn4j-test` EmulatorAuthenticator/ClientPlatform로 실기기 없이:
1. register start→(가상 서명)→finish→DB INSERT 확인
2. login 1단계(비번)→mfa_required+challenge_token
3. authenticate start→(가상 서명)→`/mfa/verify` method=webauthn→정식세션(`mfa_verified=true`)
4. signCount clone 거부(저장값 이하 재제출)
5. 다중 키 등록 + 개별 삭제 + 타인 키 404
6. MFA 미활성 사용자 로그인 회귀 0

**검증**. `./gradlew :backend:modules:identity-access:test --tests "*MfaWebauthnIntegrationTest"` + 모듈 전체 `:test ktlintCheck detekt`(--rerun-tasks, 메모리 backend-detekt-lint-debt-unmasked).

---

### Task 9. FR 마킹 + 문서 전수 동기화

**메타**.
- agent: `security-engineer`
- files: [`docs/plan/fr-index.md`, `docs/plan/product/identity-access.md`, `docs/sdd/02-requirements.md`, `docs/sdd/19-authentication.md`, `docs/plan/README.md`, `CLAUDE.md`, `docs/plan/progress.html`]
- depends-on: [8]

**작업**(TDD 비대상, chore). FR-MF-03 **D1~D5만** 체크(D6/D7 미체크 유지). CLAUDE.md §명세 동기화 체크리스트 전수.
- `product/identity-access.md §3.3` D1~D5 `[x]` + (PR #129) 표기 + 완료 노트 추가.
- `fr-index.md` FR-MF-03 상태(D1~D5 부분완료 표기 — FR 단위 카운트는 D7까지라 "완료"는 아님, 진행 표기).
- SDD 19/02-requirements WebAuthn 상태 갱신(있으면).
- `node scripts/build-dashboard.mjs` 재생성(메모리 dashboard-regen-after-fr-marking).

**검증**. `bash scripts/verify-master-plan.sh` 통과(카운트 drift 차단).

## Plan 메타

- task 수: 9
- TDD 강제: yes (T9 문서만 예외)
- 예상 wave (depends-on 기준): W1[T1,T2,T4] → W2[T3,T5] → W3[T6] → W4[T7] → W5[T8] → W6[T9]. 단 identity-access 단일 모듈이라 test 컴파일 직렬화로 실제 병렬성 제한(메모리 bts-plan-wave-gradle-module-compile).
- 추가 검증: ktlint/detekt(--rerun-tasks), 통합테스트 prod 부팅 레시피, verify-master-plan.sh
- 프론트(D6)·E2E(D7)는 후속 PR (본 PR 범위 외)

## 리뷰 결과 (← /bts-review-plan 채움)
