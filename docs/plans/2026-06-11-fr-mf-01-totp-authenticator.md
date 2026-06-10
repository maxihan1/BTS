# FR-MF-01 — TOTP (Authenticator 앱 기반 다중 요소 인증)

> slug: fr-mf-01-totp-authenticator
> type: auth
> agent: security-engineer
> BC: identity-access
> 생성: 2026-06-11

## Brief

FR-MF-01 — TOTP (Time-based One-Time Password) 기반 다중 요소 인증(MFA). Google Authenticator
등 Authenticator 앱으로 30초마다 갱신되는 6자리 코드를 검증해 1차 비밀번호 인증 이후 2차 요소로
사용한다. SDD §3.1. BC: identity-access. 우선순위: 필수.

classify 결과 — type=auth, agent=security-engineer, primary_bc=identity-access.

## 도메인 정리

- **BC**: identity-access (담당 security-engineer)
- **관련 SDD**: §19.7 (2FA), §19.7.1 지원 방식(TOTP 필수 RFC 6238), §19.7.4 설정 흐름, §19.5 `mfa_verified` JWT 클레임
- **영향 엔티티**: `TotpSecret` (신규 — glossary/domain 노트에 "미래 PR"로 사전 등록됨), `User`(기존 FK), `AuthAuditLog`(MFA_SETUP/MFA_VERIFY 이벤트, FR-AU-10 기존 인프라)
- **새 용어**: 없음 — TOTP/2FA/BackupCode 모두 glossary §2FA에 사전 등록(SDD 19.7). 신규 용어 추가 불필요
- **기존 비계(이번 PR이 실체화)**:
  - `spi/MfaChallenge.kt` — `enum { NOT_IMPLEMENTED_YET }` placeholder. 주석에 "FR-MF PR에서 확장" 명시 → TOTP 챌린지 타입으로 확장
  - `spi/AuthnResult.kt` — `RequiresMfa(challenge: MfaChallenge)` 이미 존재. login이 `mfa_required` 401 반환 자리 보유(`AuthController.kt:147`)
  - `jwt/JwtIssuer.kt:87` — `mfa_verified=false` 더미("FR-09-15 — MFA Task 완료 후 실제 값으로 교체")
  - `adapter/spring/SpringSecurityProviderAdapter.kt:39` — RequiresMfa → InsufficientAuthenticationException 처리 존재
- **마이그레이션**: 다음 V번호 = **V022** (V021=auth_audit_logs까지 존재). identity-access는 jdbc-only 모듈 → `init_codegen.sql` 미러 불요(jOOQ codegen 미사용, 메모리 fr-au-07 확인)
- **기존 결정 충돌**: 없음
- **관련 ADR**: TOTP 라이브러리 선정 + secret 암호화 방식은 본 PR에서 신규 ADR 후보 (Maxi 결정 후 생성)
- **BC 격리**: 단일 BC(identity-access) 작업. 프론트 보안 설정 UI는 same-BC view layer로 같은 PR 내 처리 가능(선례 — learnings 2026-05-22 옵션 C)

### Maxi 확정 결정 (2026-06-11)

- **D1 — TOTP 라이브러리**: `dev.samstevens.totp:totp:1.7.x` 채택(절대 규칙 #17 Maxi 승인). secret 생성 + otpauth:// URI + QR(ZXing) + 시간윈도우 검증 올인원. FR-MF-02(백업코드)도 같은 lib 재사용 가능. 신규 maven 의존성 — `gradle/libs.versions.toml` + identity-access build.gradle.kts에 추가.
- **D2 — 범위**: **FR-MF-01 단독**. TOTP secret 생성/QR provisioning/verify/enable/disable + 로그인 2단계 챌린지 + `mfa_verified` 실체화. 백업코드(FR-MF-02)·강제정책(FR-MF-04)·신뢰디바이스(FR-MF-05)는 후속 PR. **caveat**: 폰 분실 시 복구수단 부재 → FR-MF-02 즉시 후속 권장(스펙 NFR/후속작업에 명시).
- **D3 — 챌린지 메커니즘**: **단기 챌린지 토큰**(stateless). 1단계(pw) 통과 시 단명(~5분) 서명 JWT(`mfa_pending=true` + userId) 발급 → 2단계 `POST /auth/mfa/verify {challenge_token, code}` → 검증 성공 시 정식 세션+`mfa_verified=true` 발급. 기존 nimbus `JwtIssuer` 인프라 재사용. 로그인은 SSO와 달리 브라우저 리다이렉트 없어 HttpSession 불요.
- **D4 — secret 암호화(기본값)**: 기존 `SecretEncryptor`(AES-256-GCM, random IV) 패턴 재사용. 전용 키 `BTS_MFA_ENCRYPTION_KEY/SALT` 환경변수 분리(OIDC 키와 격리). 평문 저장 금지(DEVELOPMENT.md §1.1.1). 검증 시 원본 필요 → 양방향 암호화(StoredPasswordCredential의 Argon2 단방향과 구분).

## 스펙

전체 스펙. [docs/specs/2026-06-11-fr-mf-01-totp-authenticator.md](../specs/2026-06-11-fr-mf-01-totp-authenticator.md)

핵심 시나리오 요약.
- 설정 화면에서 "2FA 활성화" → 서버가 secret(PENDING)+QR 생성 → 앱 스캔 후 코드 입력 검증 → ACTIVE
- TOTP 활성 사용자 로그인 = 2단계. pw 통과 시 정식세션 대신 단기 챌린지 토큰 발급 → `POST /auth/mfa/verify {token, code}` → 정식세션(`mfa_verified=true`)
- TOTP 미활성 사용자는 기존 로그인 흐름 무변경(회귀 0)
- 비활성화는 현재 코드 검증(step-up) 필요

엔드포인트 6종(setup/enable/status/disable/verify + login 변경), 테이블 1+1(totp_secrets 신규 + sessions.mfa_verified 컬럼, V022), 신규 dep `dev.samstevens.totp:1.7.1`.

## Brainstorming Check

✅ 통과 (1회 iteration). 발견 4건 해소.
- GAP-1 refresh 회전 mfa_verified 소실 → sessions.mfa_verified 컬럼 전파 (correctness)
- GAP-2 brute-force rate-limit → Caffeine MfaAttemptLimiter 본 PR 포함 (Maxi 결정)
- GAP-3 login 200 discriminated union → 프론트 분기 (clarify)
- GAP-4 otpauth issuer/label → BTS / email (clarify)

## Plan

> 모든 백엔드 task는 단일 Gradle 모듈 `identity-access` → test 컴파일 공유로 사실상 직렬 wave(메모리 bts-plan-wave-gradle-module-compile). depends-on/files는 코드 의존성 정확성용. agent 기본값 = `security-engineer`(생략 시).

### Task 1. samstevens 의존성 + V022 마이그레이션 (totp_secrets + sessions.mfa_verified)

**메타**.
- agent: `db-engineer`
- files: [`backend/modules/identity-access/build.gradle.kts`, `backend/modules/identity-access/src/main/resources/db/migration/V022__mfa_totp.sql`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/db/V022MigrationTest.kt`]
- depends-on: []

**RED**: `V022MigrationTest` (Testcontainers, V00x 선례) — Flyway migrate 후 `totp_secrets` 테이블 5컬럼(user_id PK, secret_cipher, status CHECK, last_verified_step, confirmed_at) + `sessions.mfa_verified BOOLEAN NOT NULL DEFAULT false` 존재 단언. 실패: 테이블/컬럼 없음.

**GREEN**: `build.gradle.kts`에 `implementation("dev.samstevens.totp:totp:1.7.1")` 추가. `V022__mfa_totp.sql` 작성(스펙 §데이터 모델 — CREATE totp_secrets + ALTER sessions). 동시 브랜치 V번호 충돌 머지 직전 재확인.

**REFACTOR**: 컬럼 COMMENT 추가(스펙 명시 문구). CHECK 제약·인덱스(없음 — PK로 충분) 점검.

**검증**: `./gradlew :backend:modules:identity-access:test --tests "*V022MigrationTest"`

---

### Task 2. MfaSecretEncryptor + MfaEncryptionConfig (AES-256-GCM, 전용 키)

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/mfa/MfaSecretEncryptor.kt`, `backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/mfa/MfaEncryptionConfig.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/mfa/MfaSecretEncryptorTest.kt`]
- depends-on: []

**RED**: `MfaSecretEncryptorTest` — round-trip(encrypt→decrypt=원본), 같은 평문 2회 암호화 시 ciphertext 상이(random IV), 키 미설정 시 encrypt/decrypt 호출에서 `IllegalStateException`(생성자는 성공). 실패: 클래스 없음.

**GREEN**: `SecretEncryptor` 패턴 복제 — `MfaSecretEncryptor(password, hexSalt)` + lazy `Encryptors.stronger`. `MfaEncryptionConfig`가 `BTS_MFA_ENCRYPTION_KEY/SALT` 주입(빈 항상 등록 — 부팅 안전성, profile-scoped-bean-boot-failure). 평문/키 미로깅.

**REFACTOR**: KDoc(OIDC SecretEncryptor와 키 격리 사유 명시). 공통 추출은 하지 않음(키 격리 우선, 중복 최소).

**검증**: `./gradlew :backend:modules:identity-access:test --tests "*MfaSecretEncryptorTest"`

---

### Task 3. TotpService (samstevens 래핑 — secret 생성/otpauth URI/QR/코드 검증)

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/mfa/TotpService.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/mfa/TotpServiceTest.kt`]
- depends-on: [1]

**RED**: `TotpServiceTest` — (a) `generateSecret()` base32 비공백, (b) `otpauthUri(secret,label)` 형식(`otpauth://totp/BTS:<label>?secret=...&issuer=BTS&algorithm=SHA1&digits=6&period=30`, label URL 인코딩), (c) `qrPngDataUri(uri)` `data:image/png;base64,` 접두, (d) `verify(secret, code, step)` — 주입 `Clock`로 알려진 secret의 정답 코드 통과 / 오답 거부 / ±1 window 통과. 실패: 클래스 없음.

**GREEN**: samstevens `DefaultSecretGenerator`/`QrGenerator(ZxingPngQrGenerator)`/`DefaultCodeVerifier` 래핑. `verify`는 현재 time-step 계산에 주입 `Clock` 사용(time-bomb 회피). ±1 window는 `DefaultCodeVerifier`의 allowedTimePeriodDiscrepancy=1.

**REFACTOR**: issuer/period/digits 상수 companion 추출. KDoc에 RFC 6238 파라미터 명시.

**검증**: `./gradlew :backend:modules:identity-access:test --tests "*TotpServiceTest"`

---

### Task 4. TotpSecret 엔티티 + TotpSecretRepository (upsert/조회/last_verified_step/삭제)

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/mfa/TotpSecret.kt`, `backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/mfa/TotpSecretRepository.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/mfa/TotpSecretRepositoryTest.kt`]
- depends-on: [1]

**RED**: `TotpSecretRepositoryTest` (Testcontainers, 실 repo+시드) — upsertPending(기존 PENDING 덮어쓰기), findByUser, activate(status→ACTIVE+confirmed_at), advanceVerifiedStep(조건부 UPDATE `WHERE last_verified_step IS NULL OR last_verified_step < :step` — replay/TOCTOU), deleteByUser. 실패: repo 없음.

**GREEN**: jdbc raw SQL(UserGroup repo 선례). NULLS 처리 점검(pg-null-distinct 불요 — PK). status enum 매핑.

**REFACTOR**: SQL 상수 추출. RowMapper KDoc.

**검증**: `./gradlew :backend:modules:identity-access:test --tests "*TotpSecretRepositoryTest"`

---

### Task 5. JwtIssuer mfa_verified 파라미터 + MFA 챌린지 토큰 발급/검증 + MfaChallenge 확장

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/jwt/JwtIssuer.kt`, `backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/spi/MfaChallenge.kt`, `backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/mfa/MfaChallengeTokenService.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/mfa/MfaChallengeTokenServiceTest.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/jwt/JwtIssuerTest.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/jwt/JwtIssuerSignatureRegressionTest.kt`]
- depends-on: []

**RED**: (a) `JwtIssuerTest` — `issue(..., mfaVerified=true)` 시 `mfa_verified` 클레임 true, 기본 false. (b) `MfaChallengeTokenServiceTest` — `issueChallenge(userId, providerId)` 단명(5분) JWT 발급(고유 jti), `validate(token)` 성공 시 userId/providerId 반환, 만료/위조/purpose 불일치 시 실패, **(C1) 일회용** — 같은 토큰 `consume` 2회 시 2번째 거부(Caffeine consumed-jti). 실패: API 없음.

**GREEN**: `JwtIssuer.issue`에 `mfaVerified: Boolean = false` 파라미터 추가(기존 `false` 더미 제거, line 87). `MfaChallengeTokenService`가 nimbus로 purpose=`mfa_challenge` 단명 JWT(jti 포함) 발급/검증(기존 keyProvider 재사용) + **(C1)** verify 성공 시 jti를 Caffeine(5분 TTL)에 기록해 재사용 차단. `MfaChallenge` enum에 `TOTP` 추가(placeholder 교체, dead path adapter 영향 확인).
- **(C6) JwtIssuer.issue 시그니처 파급** — line 87 하드코딩 `false`를 파라미터화하면 호출처 4곳(AuthController:248, RefreshTokenService:143, OidcSuccessHandler, SamlSuccessHandler) 영향. **기본값 `false`로 컴파일 무회귀**이며, SSO 로그인의 `mfa_verified`는 본 PR 범위 외로 **false 유지**(의도). `JwtIssuerSignatureRegressionTest`로 SSO 핸들러 발급 토큰의 mfa_verified=false 회귀 가드(plan-files-constructor-injection 메모리 — 호출처 테스트 포함).

**REFACTOR**: 챌린지 TTL 상수. KDoc.

**검증**: `./gradlew :backend:modules:identity-access:test --tests "*JwtIssuerTest" --tests "*MfaChallengeTokenServiceTest"`

---

### Task 6. sessions.mfa_verified 전파 (Session 엔티티 + SessionService.create + RefreshTokenService.rotate)

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/session/Session.kt`, `backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/session/SessionService.kt`, `backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/session/SessionRepository.kt`, `backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/session/RefreshTokenService.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/session/SessionServiceTest.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/session/RefreshTokenServiceTest.kt`]
- depends-on: [1, 5]

**RED**: (a) `SessionServiceTest` — `create(..., mfaVerified=true)` 시 저장된 세션 `mfaVerified=true`, 기본 false. (b) `RefreshTokenServiceTest` — mfaVerified=true 세션의 rotate가 `jwtIssuer.issue(mfaVerified=true)` 호출(mock verify). 실패: 파라미터/컬럼 없음.

**GREEN**: `Session`에 `mfaVerified: Boolean` 필드. `SessionService.create` 시그니처 + INSERT에 컬럼. `SessionRepository` 매핑(SELECT 포함). `RefreshTokenService.rotate:143`에서 `mfaVerified = session.mfaVerified` 전달. 기존 호출처(AuthController.issueTokens) 컴파일 영향 → 기본값 false로 무회귀(plan-files-constructor-injection 선례 — 기존 테스트도 files 포함).

**REFACTOR**: SessionResponse엔 미노출(NFR-2 유지) 확인.

**검증**: `./gradlew :backend:modules:identity-access:test --tests "*SessionServiceTest" --tests "*RefreshTokenServiceTest"`

---

### Task 7. MfaAttemptLimiter (Caffeine rate-limit — 5회/5분)

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/mfa/MfaAttemptLimiter.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/mfa/MfaAttemptLimiterTest.kt`]
- depends-on: []

**RED**: `MfaAttemptLimiterTest` — 5회 실패 누적 후 `isBlocked(userId)` true, 성공 시 reset, window(5분) 만료 후 해제(주입 ticker/Clock). 실패: 클래스 없음.

**GREEN**: Caffeine(3.1.8 기존 dep) `expireAfterWrite(5분)` 카운터 캐시. `recordFailure`/`reset`/`isBlocked`. 테스트 가능성 위해 `Ticker` 주입.

**REFACTOR**: 임계값/window 상수. KDoc(메모리 only, FR-MF-04 영구 lockout 이연 명시).

**검증**: `./gradlew :backend:modules:identity-access:test --tests "*MfaAttemptLimiterTest"`

---

### Task 8. AuthEventType MFA 이벤트 4종 + MfaService 오케스트레이션 + emit coverage 갱신

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/audit/AuthEventType.kt`, `backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/mfa/MfaService.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/mfa/MfaServiceTest.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/audit/AuthEventEmitCoverageTest.kt`]
- depends-on: [2, 3, 4, 7]

**RED**: `MfaServiceTest` — setup(secret 암호화 저장 PENDING + otpauth/QR 반환, 이미 ACTIVE면 AlreadyEnabled), enable(코드 검증→ACTIVE, 오답→`limiter.recordFailure`+InvalidCode, **(C2) 5회 오답→TooManyAttempts**, PENDING 없으면 NoPending), verifyLogin(limiter 차단 시 TooManyAttempts, 코드+replay step 검증→성공, 오답→limiter.recordFailure + InvalidCode), disable(코드 검증→삭제, 오답→InvalidCode), isEnabled. 각 성공/실패 분기 + 감사 이벤트 emit 단언. `AuthEventEmitCoverageTest`는 새 enum 4종 emit 배선 자동 검증(현재 12→16). 실패: 서비스/이벤트 없음.

**GREEN**: `AuthEventType`에 `MFA_ENABLED`, `MFA_CHALLENGE_SUCCESS`, `MFA_CHALLENGE_FAILURE`, `MFA_DISABLED` 추가(12→16). `MfaService`가 TotpService+TotpSecretRepository+MfaSecretEncryptor+MfaAttemptLimiter+AuthAuditLogService 조립. `@Service`+`@Transactional`(클래스 부착 — TransactionalServiceArchTest). secret 평문 미로깅. emit 배선으로 coverage 통과.

**REFACTOR**: 결과 sealed result 타입. KDoc.

**검증**: `./gradlew :backend:modules:identity-access:test --tests "*MfaServiceTest" --tests "*AuthEventEmitCoverageTest"`

---

### Task 9. MfaController (setup/enable/status/disable) + SecurityConfig 라우팅

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/web/MfaController.kt`, `backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/config/SecurityConfig.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/web/MfaControllerTest.kt`]
- depends-on: [8]

**RED**: `MfaControllerTest` (@WebMvcTest 또는 통합) — POST setup(JWT→200 otpauth/qr, 이미 활성→409), POST enable(코드→204, 오답→400 invalid_code, no pending→409), GET status(→200 {enabled}), DELETE disable(코드→204, 오답→400, 미활성→404), PAT→403(세션관리 선례), 미인증→401. catch-all 핸들러가 4xx 삼키지 않음(domain-exception HTTP 핸들러 스코프 선례). 실패: 컨트롤러 없음.

**GREEN**: `MfaController` 엔드포인트 + DTO. JWT 전용(`@AuthenticationPrincipal Jwt?` null→403). `SecurityConfig`에 `/api/v1/auth/mfa/**` authenticated(verify 제외). 도메인 예외→HTTP 상태 매핑(message 일반화 — guard-exception-message-leak 선례).

**REFACTOR**: 에러응답 일원화(login errorResponse 선례). KDoc.

**검증**: `./gradlew :backend:modules:identity-access:test --tests "*MfaControllerTest"`

---

### Task 10. AuthController 로그인 2단계 게이트 + /auth/mfa/verify 엔드포인트

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/web/AuthController.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/web/AuthControllerTest.kt`]
- depends-on: [5, 6, 8]

**RED**: `AuthControllerTest` — (a) TOTP 미활성 사용자 login → 기존 토큰응답 그대로(회귀 0). (b) TOTP 활성 사용자 login → 200 `{mfa_required:true, mfa_challenge_token, expires_in}`(정식세션 미발급). (c) `/auth/mfa/verify` 정답 코드 → 200 access_token + refresh cookie + 세션 mfa_verified=true. (d) 오답→401 invalid_code, 만료 토큰→401 mfa_challenge_expired, limiter 차단→429. 실패: 분기/엔드포인트 없음.

**GREEN**: `login()` Success 분기 후 `mfaService.isEnabled(userId)` 체크 → true면 `mfaChallengeTokenService.issueChallenge` 발급(issueTokens 대신). `verifyMfa()` 신규 — 챌린지 토큰 validate(+일회용 consume) + `mfaService.verifyLogin(code)` → `issueTokens(mfaVerified=true)`. `AuthnResult.RequiresMfa` dead path 무변경.
- **(BLOCKER-1) SecurityConfig `/api/v1/auth/mfa/verify`를 양쪽 리스트에 등록** — `csrf.ignoringRequestMatchers`(line 122 블록) **AND** `authorizeHttpRequests permitAll`(line 131 블록). login 선례가 두 곳 동시 등록(line 123+132)임을 확인. permitAll만 추가하면 CSRF 필터가 POST를 403으로 막는다(아직 JWT 없어 oauth2 자동 skip 미적용). RED에 "verify POST가 403 아님(CSRF 통과)" 단언 추가.
- **(C7) 타이밍 누출 점검** — `mfaService.isEnabled` 조회는 1단계 Success 분기 *후*에만 수행(비밀번호 오답 경로는 isEnabled 미조회 → MFA 보유 여부 비노출). RED에 명시.

**REFACTOR**: issueTokens에 mfaVerified 파라미터. 응답 DTO(MfaRequiredResponse). KDoc 갱신.

**검증**: `./gradlew :backend:modules:identity-access:test --tests "*AuthControllerTest"`

---

### Task 11. 통합 테스트 (Testcontainers end-to-end: setup→enable→login→verify→refresh)

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/integration/MfaTotpIntegrationTest.kt`]
- depends-on: [9, 10]

**RED**: `MfaTotpIntegrationTest` (prod+RANDOM_PORT 부팅 레시피 — identity-access-prod-randomport-boot-recipe) — 실 사용자 시드 → setup → 실제 TOTP 코드 계산(주입 Clock) → enable → login(2단계 진입 확인) → verify(정식세션) → whoami/refresh로 mfa_verified=true 유지 확인 → disable. secret DB 평문 미저장(암호문 컬럼 검증). 실패: 흐름 미완.

**GREEN**: 통합 배선 확인. **(C4) 진짜 RED 보장** — Task 1~10이 끝났어도 통합테스트를 *먼저 작성*해 배선 결함을 잡는다. RED 확인 절차로 verify→issueTokens의 `mfaVerified=true` 전달을 의도적으로 1곳 비워 통합테스트가 실패(mfa_verified=false)함을 확인 후 되돌린다(가짜 그린 차단). 동시 Testcontainers flaky 시 단독 재실행 확정(concurrent-testcontainers-suite-flaky).

**REFACTOR**: 헬퍼(코드 계산) 추출. RED 확인 절차를 KDoc에 기록.

**검증**: `./gradlew :backend:modules:identity-access:test --tests "*MfaTotpIntegrationTest"`

---

### Task 12. 프론트엔드 — Zod 스키마 + useMfa 훅 + /settings/mfa 페이지 + 로그인 2단계 + MSW

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/api/schemas.ts`, `apps/web/src/auth/useMfaQuery.ts`, `apps/web/src/auth/useMfaMutations.ts`, `apps/web/src/routes/settings.mfa.tsx`, `apps/web/src/components/auth/MfaSetupDialog.tsx`, `apps/web/src/auth/useLoginMutation.ts`, `apps/web/src/auth/LoginForm.tsx`, `apps/web/src/mocks/mfa-handlers.ts`, `apps/web/src/mocks/mfa-fixtures.ts`, + 대응 `*.test.ts(x)`]
- depends-on: [9, 10]

**RED**: vitest — (a) MfaSetupDialog: QR 표시+코드 입력→enable mutation, (b) settings.mfa 활성/비활성 분기(status query), (c) useLoginMutation: 200 `mfa_required` 응답 시 챌린지 토큰 보관+MFA 코드 단계 전환(TokenResponseSchema parse 전 분기 — GAP-3), (d) MFA 코드 제출→verify→세션. MSW stateful handler(msw-mutation-stateful-refetch). 실패: 컴포넌트/훅 없음.

**GREEN**: Zod 스키마(backend DTO와 정합 — frontend-zod-backend-dto-contract-gap, spec §API에서 grep 검증). 훅+페이지+로그인 분기. CSRF 수동(frontend-api-convention-per-bc). 라이브러리 QR 불필요(서버 PNG data URI 렌더).
- **(C3) login 200 응답 형태 변경은 계약 변경** — 기존 login 200 parse 지점 전수 grep(`grep -rn "auth/login" apps/web/src`)으로 `useLoginMutation` 외 소비자 확인 + `loginAsAlice`/whoami fixture 회귀 점검(e2e-loginasalice-fixture, frontend-zod-backend-dto-contract-gap 메모리). 백엔드 `AuthControllerTest` 기존 200 단언도 Task 10 RED에서 무회귀 확인.

**REFACTOR**: 에러 메시지 i18n 임시 const. 컴포넌트 분리.

**검증**: `pnpm typecheck && pnpm test --filter mfa && pnpm lint`

---

### Task 13. E2E (Playwright) — MFA 활성화/비활성화 + 로그인 2단계

**메타**.
- agent: `qa-engineer`
- files: [`apps/web/e2e/mfa.spec.ts`, `apps/web/src/mocks/mfa-handlers.ts`(시나리오 토글 보강)]
- depends-on: [12]

**RED**: Playwright happy path — (1) 로그인→/settings/mfa→활성화(QR 표시→코드 입력→완료), (2) 로그아웃→재로그인 시 MFA 코드 단계→통과→대시보드, (3) 비활성화. MSW 시나리오 토글(localStorage 플래그+addInitScript — e2e-msw-scenario-toggle), getByRole exact(playwright-getbyrole-exact). 실패: E2E 미존재.

**GREEN**: MSW 파생동작 공유 store(msw-derived-behavior-shared-store-e2e). CSRF 쿠키 수동 시드. 드롭다운/다이얼로그 로딩 대기.

**REFACTOR**: fixture 정리. 기존 로그인 E2E 회귀 동반 실행(ui-pr-defer-e2e-regression).

**검증**: `pnpm test:e2e --grep mfa && pnpm verify`

---

## Plan 메타

- task 수: 13 (백엔드 11 + 프론트 1 + E2E 1)
- 예상 wave: 백엔드는 단일 Gradle 모듈 공유로 대부분 직렬(T1·T2·T5·T7 의존성 0이나 모듈 컴파일 공유). 프론트(T12)/E2E(T13)는 독립.
- TDD 강제: yes (test: 커밋 먼저)
- 추가 검증: ktlint, detekt, typecheck, vitest, playwright
- 신규 의존성: `dev.samstevens.totp:1.7.1` (Maxi 승인 완료, 절대규칙 #17). Caffeine은 기존 dep 재사용.
- 범위 주의: task 13개 = 10 초과(bts-plan 휴리스틱). 단일 원자 FR(TOTP는 setup/login/verify가 분리 출시 불가)이라 PR 분할 vs 단일 PR을 게이트1에서 Maxi 확인.

## 리뷰 결과

### plan-eng-review (security-engineer 독립 적대적 리뷰, 2026-06-11)

종합 판정: **GO with fixes**. 회귀 방지 커버리지·BC 격리·인프라 실측 정합성 견고. BLOCKER 1건은 plan 텍스트 보강으로 해결.

- 보안 정확성: CONCERN → C1(챌린지 토큰 일회용)·C2(enable limiter) 반영
- 절대 규칙: **BLOCKER-1** (CSRF ignore 누락) → Task 10 반영 완료
- 로그인 회귀: CONCERN → C3(계약 변경 grep)·C6(SSO 핸들러) 반영
- BC 격리: ✅ PASS
- TDD 건전성: CONCERN → C4(통합 진짜 RED) 반영
- 회귀 함정: ✅ PASS (가장 강한 영역)
- task 분해: CONCERN → C5(2-PR 분할) Maxi 게이트1 결정

**반영 완료 (plan 수정됨)**:
- BLOCKER-1 — Task 10 GREEN: `/auth/mfa/verify`를 CSRF `ignoringRequestMatchers` + `permitAll` 양쪽 등록 명시 + RED 403 가드
- C1 — Task 5: 챌린지 토큰 jti 일회용(Caffeine consumed-jti)
- C2 — Task 8: enable 경로 limiter 적용 + RED TooManyAttempts
- C3 — Task 12: login 200 계약 변경 소비자 전수 grep
- C4 — Task 11: 통합테스트 진짜 RED 확인 절차
- C6 — Task 5: JwtIssuer 시그니처 파급(SSO 핸들러 false 유지) 회귀 가드
- C7 — Task 10: isEnabled 조회를 Success 분기 후로 한정(타이밍 누출 방지)

**Maxi 결정 (게이트1, 2026-06-11)**:
- 게이트1 **승인** → /bts-impl 진행.
- C5 — **2-PR 분할 확정**. **PR #113 = 백엔드 T1~T11**(API·마이그레이션·암호화·rate-limit·통합테스트). **후속 PR = 프론트 T12 + E2E T13**(백엔드 API 계약 확정 후, Zod invent 없이 grep 검증). 본 PR 구현 범위는 T1~T11만.
