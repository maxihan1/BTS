# Auth_platform SSO 연결 — 백엔드 (JIT username · amr MFA 인정 · env 등록)

티어: T2
타입: auth

BTS 를 사내 통합 로그인 Auth_platform(`auth.maxihan.com`, Spring Authorization Server)의 OIDC client 로 붙인다 (FR-AU-04 OIDC SSO 의 운영 연결 — Auth_platform spec §8.3). BTS 의 OIDC upstream 경로는 이미 있다. 막히는 곳 셋을 고친다.
1. JIT username 이 `sub` 다 — Auth_platform 의 `sub` 는 UUID 라 사람이 못 읽는 BTS 사용자가 생기고 `BTS_BOOTSTRAP_ADMIN_USERNAME` 이 안 맞는다 → `preferred_username` (없으면 `sub`).
2. SSO 세션은 `mfa_verified=false` 이고 IdP `amr` 을 안 읽는다 — SSO 관리자가 BTS TOTP 등록 강제(`mfa_enrollment_required`)에 갇힌다 → id token `amr` 에 `mfa` 가 있으면 MFA 를 거친 세션으로 보고 등록 강제를 걸지 않는다.
3. `oidc_provider_configs` 는 SQL 로만 들어가는데 secret 이 암호화라 손 SQL 로는 못 넣는다 → 기동 runner 가 env `BTS_AUTH_PLATFORM_*` 로 `registration_id=auth` 행을 upsert 한다.

## Jira 대조

대응 없음 — 백엔드 계정 매핑 · 세션 속성만 바꾸고 화면은 한 픽셀도 바뀌지 않는다. ADS 준용 대상 화면 없음.

## 할 일
- [ ] JIT username = `preferred_username` (없으면 `sub`) — `provider/oidc/OidcAuthenticationSuccessHandler.kt` `toProvisionAttrs`
**검증**: `cd backend && ./gradlew :modules:identity-access:test --tests '*OidcSuccessHandlerTest'`
- [ ] `amr` 에 `mfa` → `sessionService.create(mfaVerified=true)` · `JwtIssuer` 의 `mfa_enrollment_required = !mfaVerified && policy` — `OidcAuthenticationSuccessHandler.kt` · `jwt/JwtIssuer.kt`
**검증**: `cd backend && ./gradlew :modules:identity-access:test --tests '*JwtIssuerTest' --tests '*Mfa*'`
- [ ] env 등록 runner — `provider/oidc/AuthPlatformRegistrationRunner.kt` · `resources/application.yml`
**검증**: `cd backend && ./gradlew :modules:identity-access:test --tests '*AuthPlatformRegistrationRunnerTest'`
