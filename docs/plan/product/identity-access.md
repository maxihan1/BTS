<!-- identity-access BC — 인증/2FA/권한 22 FR + AuthN Provider PoC -->

# identity-access BC

**소속 FR**. 22개 (AU 10 + MF 5 + PM 7).
**책임**. 사용자/조직/그룹/역할/권한/인증/세션 전체.
**SDD 참조**. 19장 (인증), 12장 (권한).
**다른 BC와의 경계**. 모든 BC의 권한 게이트. 사용자 식별·인증·인가의 단일 진실 원천. 다른 BC는 `SecurityContext` 인터페이스로만 접근.

## §0 진입 조건

- [ ] DEVELOPMENT.md §1.1~§1.6 (보안 6개 절대 규칙) 숙지
- [ ] DATA.md §사용자/세션 테이블 규칙 확인
- [ ] `docs/poc/dependencies.md §2.3` (인증/보안 라이브러리) 확인
- [ ] §1 기술 검증 통과 (아래)

## §1 기술 검증 (AuthN Provider + Keycloak PoC)

**SDD**. 19장. **checklist.md 위임**. §1.4. **ADR 후보**. 없음 (LDAP/SAML/OIDC는 표준 라이브러리).

- [ ] `AuthenticationProvider` 인터페이스 + `de.mkammerer:argon2-jvm` 패스워드 해싱 동작 (Argon2id, memory=64MB)
- [ ] OIDC Authorization Code + PKCE 동작 (Keycloak 25 컨테이너)
- [ ] Keycloak realm import 스크립트 (`infra/keycloak/realm-bts.json`)
- [ ] Spring Security 필터 체인 — 1개 보호된 엔드포인트 동작 확인
- [ ] CSRF 토큰 검증 동작 (DEVELOPMENT.md §1.5 준수)
- [ ] Testcontainers Keycloak 통합 테스트 1개 통과

## §2 인증 (FR-AU, 10개)

### §2.1 FR-AU-01 — 플러그형 AuthenticationProvider 구조

**우선순위**. 필수 | **선행**. §1 기술 검증 | **Plan slug**. `identity/authn-provider`

- [x] D1. 도메인 정의 — `Maxi_wiki/BTS/domain/identity-access.md`. Principal/Credential/AuthnResult VO (책임. security-engineer + Maxi)
- [x] D2. 명세 — `AuthenticationProvider` 인터페이스 + 등록 메커니즘 (책임. security-engineer)
- [x] D3. 데이터 모델 — `authn_providers` (provider_type, config) (책임. db-engineer)
- [x] D4. 백엔드 — `ProviderRegistry` + `AuthenticationManager` Spring Bean (책임. security-engineer)
- [x] D5. 백엔드 테스트 — 가짜 Provider 2개로 등록/조회 (책임. security-engineer)
- [x] D6. 프론트 UI — Provider 선택 화면 (책임. designer → frontend-engineer) (FR-AU-02 PR #11 흡수)
- [x] D7. E2E (책임. qa-engineer) (FR-AU-02 PR #22 흡수)

> **FR-AU-01 D6/D7 완료 (2026-05-30, 마커 정정)**. D6(Provider 선택 화면)·D7(E2E)는 plan(`docs/plans/2026-05-20-identity-authn-provider.md` §스코프)이 "별도 PR" 로 위임했으나, 실제로는 별도 PR 대신 후속 FR-AU-02(LDAP) 작업에서 흡수 구현됨. D6 = `apps/web/src/auth/LoginForm.tsx` 의 provider 드롭다운(local/ldap-corp, PR #11), D7 = `apps/web/e2e/login-ldap.spec.ts` 의 provider 선택 E2E(S1/S2-ldap, PR #22). 코드 변경 없이 stale 마커만 정정. 활성 Provider 목록을 백엔드에서 받아 동적 렌더(하드코딩 enum 제거)하는 작업은 FR-AU-06(다중 Provider 선택 화면) 범위로 분리.

### §2.2 FR-AU-02 — LDAP/AD 연동

**우선순위**. 필수 | **선행**. §2.1 | **Plan slug**. `identity/ldap`

- [x] D1. 도메인 (책임. security-engineer) (PR #8, 2026-05-21)
- [x] D2. 명세 — baseDN, 사용자/그룹 매핑, lockout 정책 (책임. security-engineer) (PR #8, 2026-05-21)
- [x] D3. 데이터 모델 — `users`, `user_external_accounts(provider, externalId)` (책임. db-engineer) (PR #8, 2026-05-21)
- [x] D4. 백엔드 — Spring Security LDAP authenticator + `UserDetailsService` (책임. security-engineer) (PR #8, 2026-05-21)
- [x] D5. 백엔드 테스트 — Testcontainers OpenLDAP (책임. security-engineer) (PR #8, 2026-05-21)
- [x] D6. 프론트 UI — 로그인 폼 (책임. designer → frontend-engineer) (PR #11, 2026-05-22)
- [x] D7. E2E — Playwright (책임. qa-engineer) (PR #22, 2026-05-26)

| 항목 | 임계 | 실측 (p95) |
|---|---|---|
| 로그인 응답 | 500ms | ___ |

### §2.3 FR-AU-03 — SAML 2.0 SSO

**우선순위**. 필수 | **선행**. §2.1 | **Plan slug**. `identity/saml`

- [x] D1. 도메인 (책임. security-engineer) — PR #76 (SamlIdpConfig + Credential.SamlAssertion + SPI thin SamlProvider, ADR docs/decisions/2026-06-04-saml-sso-provider.md)
- [x] D2. 명세 — **SP-initiated 흐름** (책임. security-engineer) — PR #76 (IdP-initiated는 후속 FR로 축소, 게이트2 결정)
- [x] D3. 데이터 모델 — `saml_idp_configs`(authn_provider_id FK + SAML authn_providers seed) (책임. db-engineer) — PR #76 (V010)
- [x] D4. 백엔드 — `spring-security-saml2-service-provider`. 표준 `/saml2/authenticate/{registrationId}` + ACS `/login/saml2/sso/{registrationId}`, SAML 전용 @Order(1) SecurityFilterChain(IF_REQUIRED, STATELESS 분리), JIT 프로비저닝(AutoProvisionService 재사용), wantAuthnRequestsSigned(false) (책임. security-engineer) — PR #76
- [x] D5. 백엔드 테스트 — Testcontainers Keycloak SAML 모드 (책임. security-engineer) — PR #76 (SpInitiatedEntryTest 실 Keycloak 302+SAMLRequest, JIT 멱등, 서명검증)
- [x] D6. 프론트 UI — IdP 선택 + SP-initiated 진입점 (책임. frontend-engineer) — PR #76 (활성 IdP 동적 버튼 SamlIdpButtons, GET /api/v1/auth/saml/idps)
- [x] D7. E2E (책임. qa-engineer) — PR #76 (login-saml.spec S1/S5, 기존 login E2E 회귀 0)

> **FR-AU-03 SP-initiated 완료 (2026-06-04, PR #76)**. SP-initiated SAML SSO end-to-end 완성(실 Keycloak Testcontainers 302 검증). **IdP-initiated(Unsolicited Assertion + replay 방어)는 후속 FR로 분리**(게이트2 Maxi 결정 — 복잡·보안위험). SP가 IdP 서명 요구 시 SP 키 구성도 후속(현재 wantAuthnRequestsSigned=false). code-reviewer ground-truth가 plan 환각 3건(Credential.SamlAssertion 부재 등) + PR BLOCKER 2건(경로 불일치/IdP-initiated 갭) 적발·해소.

### §2.4 FR-AU-04 — OIDC SSO

**우선순위**. 필수 | **선행**. §2.1 | **Plan slug**. `identity/oidc`

- [x] D1. 도메인 (책임. security-engineer) — PR #80 (OidcProvider thin SPI + OidcProviderConfig, ADR docs/decisions/2026-06-04-oidc-sso-provider.md)
- [x] D2. 명세 — Authorization Code + PKCE. ID Token 검증 (책임. security-engineer) — PR #80 (SP-initiated 표준, PKCE S256 강제)
- [x] D3. 데이터 모델 — `oidc_provider_configs`(authn_provider_id FK + client_secret 암호화) (책임. db-engineer) — PR #80 (V011)
- [x] D4. 백엔드 — `oauth2-client`(기존재 재사용, 의존성 신규 0). OIDC 전용 @Order(2) SecurityFilterChain(IF_REQUIRED), DB기반 ClientRegistrationRepository(issuer discovery), JIT(AutoProvisionService 재사용) (책임. security-engineer) — PR #80
- [x] D5. 백엔드 테스트 — Testcontainers Keycloak OIDC (책임. security-engineer) — PR #80 (실 Keycloak 302 진입 + code_challenge/S256 + secret 암호화 round-trip)
- [x] D6. 프론트 UI — OIDC 진입 버튼 + 리다이렉트 (책임. frontend-engineer) — PR #80 (활성 IdP 동적 OidcIdpButtons, GET /api/v1/auth/oidc/providers)
- [x] D7. E2E (책임. qa-engineer) — PR #80 (login-oidc.spec S1/S5, SAML E2E 회귀 0)

> **FR-AU-04 완료 (2026-06-04, PR #80)**. OIDC SSO end-to-end 완성(실 Keycloak Testcontainers 302 진입 + PKCE S256 검증). SAML(FR-AU-03 PR #76) 동형 구조 재사용 — SSO 전용 @Order 체인 분리, JIT 프로비저닝(AutoProvisionService) 재사용. **의존성 신규 0**(oauth2-client/resource-server 기존재, FR-AU-09 맥락). **client_secret 암호화 저장**(AES-256-GCM, app key 환경변수, DATA.md §8 준수). 구현 중 부팅 결함(`@ConditionalOnProperty` 가드가 항상 스캔 의존성 깸 → SecretEncryptor 항상등록+사용시점 검증으로 정정, 메모리 `profile-scoped-bean-boot-failure` 재현·해소) + PKCE 보강(confidential client에도 S256 강제, spec N2). code-reviewer 게이트2 PASS(BLOCKER 0/CONCERN 2). **후속** — username=preferred_username 승격(현재 sub, FR-AU-06/08), full callback 왕복 E2E(현재 302 진입까지, SAML 동형). IdP 셀프서비스 관리 UI는 FR-AU-06.

### §2.5 FR-AU-05 — 로컬 계정 (외부 협력사용)

**우선순위**. 필수 | **선행**. §2.1 | **Plan slug**. `identity/local-account`

- [x] D1. 도메인 — LocalCredential VO (책임. security-engineer)
- [x] D2. 명세 — 비밀번호 정책 (길이/복잡도/이력) + Argon2id (책임. security-engineer)
- [x] D3. 데이터 모델 — `local_credentials(password_hash, last_changed_at)` (책임. db-engineer)
- [~] D4. 백엔드 — 가입/비밀번호 변경/리셋 (책임. security-engineer) — **변경 완료(PR #44), 가입·리셋 후속**
- [x] D5. 백엔드 테스트 — 비밀번호 정책 위반 케이스 (책임. security-engineer) (PR #44)
- [~] D6. 프론트 UI — 가입/비밀번호 변경 폼 (책임. designer → frontend-engineer) — **비밀번호 변경 폼 완료(PR #45), 가입 폼 후속**
- [~] D7. E2E (책임. qa-engineer) — **비밀번호 변경 E2E 완료(PR #45), 가입 E2E 후속**

> **FR-AU-05 비밀번호 변경 프론트 완료 (2026-05-31, PR #45)**. D6/D7 중 **비밀번호 변경 폼** 슬라이스 완료 — `/settings/password`(requireAuth) 라우트 + `ChangePasswordForm`(현재/새/확인 3입력) + `changePassword` API(POST + X-XSRF-TOKEN) + `useChangePassword` 훅 + MSW 핸들러 + Playwright E2E 4시나리오(정상/현재불일치/정책위반/확인불일치). 클라이언트 검증은 required+새≠확인만, 정책(12자/3종)은 정적 안내+서버 권위(D1 결정, drift 차단). 에러코드 대문자 정합(frontend-zod-backend-dto-contract-gap PR #41 선례 회피), POLICY_VIOLATION 문구는 violations(MIN_LENGTH/COMPLEXITY)로 프론트 생성. **가입 폼/가입 E2E는 후속** — 가입 API(전역 admin 권한 FR-PM-01 선행)와 함께. 검증 — lint/typecheck/단위443/E2E49 그린. 백엔드 무변경, 마이그레이션 0건.

> **FR-AU-05 비밀번호 변경 완료 (2026-05-30, PR #44)**. D4 중 **비밀번호 변경** API(`POST /api/v1/users/me/password` + `PasswordPolicy` 12자/3종 복잡도 + 변경 성공 시 현재 세션 제외 다른 세션 무효화 — `revoke`+`revokeChainFromSession` 쌍) + D5(정책 위반 테스트) 완료. 기존 `LocalCredentialService.rotate`/`SessionService`/`RefreshTokenRepository` 재사용, 마이그레이션 0건. **가입(관리자+임시비번)·리셋은 후속** — 가입은 전역 admin 권한 체계(FR-PM-01) 선행 필요, 리셋은 이메일 발송 인프라 도입 후 (현재 `JavaMailSender`/notification BC 부재). D6(프론트 폼)·D7(E2E)은 PR-2. PRE_EXISTING ktlint debt(identity-access 모듈 285건)는 별도 cleanup PR 위임.

### §2.6 FR-AU-06 — 다중 Provider 동시 활성화

**우선순위**. 필수 | **선행**. §2.1~§2.5 | **Plan slug**. `identity/multi-provider`

- [ ] D1. 도메인 (책임. security-engineer)
- [ ] D2. 명세 — Provider 우선순위 + fallback 규칙 (책임. security-engineer)
- [ ] D3. 데이터 모델 — `authn_providers.priority, enabled` (책임. db-engineer)
- [ ] D4. 백엔드 — `CompositeAuthenticationManager` (책임. security-engineer)
- [ ] D5. 백엔드 테스트 — 다중 Provider 시나리오 (책임. security-engineer)
- [ ] D6. 프론트 UI — 다중 Provider 선택 화면 (책임. designer → frontend-engineer)
- [ ] D7. E2E (책임. qa-engineer)

### §2.7 FR-AU-07 — 도메인 기반 자동 라우팅

**우선순위**. 높음 | **선행**. §2.6 | **Plan slug**. `identity/domain-routing`

- [ ] D1. 도메인 (책임. security-engineer)
- [ ] D2. 명세 — 이메일 도메인 → Provider 매핑 (책임. security-engineer)
- [ ] D3. 데이터 모델 — `domain_provider_routes(domain, provider_id)` (책임. db-engineer)
- [ ] D4. 백엔드 — 이메일 입력 → Provider 자동 선택 (책임. security-engineer)
- [ ] D5. 백엔드 테스트 (책임. security-engineer)
- [ ] D6. 프론트 UI — 이메일 입력 후 Provider 자동 진입 (책임. designer → frontend-engineer)
- [ ] D7. E2E (책임. qa-engineer)

### §2.8 FR-AU-08 — 계정 통합 (Account Linking)

**우선순위**. 높음 | **선행**. §2.1~§2.5 | **Plan slug**. `identity/account-linking`

- [ ] D1. 도메인 (책임. security-engineer)
- [ ] D2. 명세 — 동일 사용자 다중 외부 계정 통합 워크플로우 (책임. security-engineer)
- [ ] D3. 데이터 모델 — `user_external_accounts` 다대일 (책임. db-engineer)
- [ ] D4. 백엔드 — Linking API + 재인증 강제 (책임. security-engineer)
- [ ] D5. 백엔드 테스트 — 충돌 케이스 (책임. security-engineer)
- [ ] D6. 프론트 UI — "계정 연결" 설정 페이지 (책임. designer → frontend-engineer)
- [ ] D7. E2E (책임. qa-engineer)

### §2.9 FR-AU-09 — 세션/토큰 관리 (JWT + Refresh + PAT)

**우선순위**. 필수 | **선행**. §2.1 | **Plan slug**. `identity/sessions`

- [x] D1. 도메인 — Session/AccessToken/RefreshToken/PAT 분리 (책임. security-engineer)
- [x] D2. 명세 — TTL, 회전 정책, 세션 강제 종료 (책임. security-engineer)
- [x] D3. 데이터 모델 — `sessions`, `refresh_tokens`, `pats(scope, revoked_at)` (책임. db-engineer)
- [x] D4. 백엔드 — JWT 발급/검증 + 세션 + PAT API + 세션 목록 조회/강제 종료 API (책임. security-engineer)
- [x] D5. 백엔드 테스트 — 토큰 만료/회전/취소 (책임. security-engineer)
- [x] D6. 프론트 UI — 활성 세션 목록 + 강제 로그아웃 (책임. frontend-engineer). **DEVELOPMENT.md §1.17 — 토큰 localStorage 금지 (sessionStorage 강제)**
- [x] D7. E2E (책임. qa-engineer)

> **FR-AU-09 완료 (2026-05-29, PR #37)**. self-service 세션 관리 (목록 조회 + 강제 종료) 추가로 D2/D4/D6/D7 마무리. PAT 는 세션 API 에서 403 (Jira 방식 — 세션/API토큰 분리). admin 세션 관리는 별도 후속. audit emit 은 FR-AU-10 위임.

### §2.10 FR-AU-10 — 인증 감사 로그

**우선순위**. 필수 | **선행**. §2.1 | **Plan slug**. `identity/audit-log`

- [ ] D1. 도메인 — AuthEvent (성공/실패/세션종료/권한변경) (책임. security-engineer)
- [ ] D2. 명세 — 보존 1년 (SDD §2.3.3) (책임. security-engineer)
- [ ] D3. 데이터 모델 — `auth_audit_logs(event_type, ip, user_agent, ...)` (책임. db-engineer)
- [ ] D4. 백엔드 — 모든 인증/권한 변경 이벤트 emit (책임. security-engineer)
- [ ] D5. 백엔드 테스트 — 이벤트 누락 0 (책임. security-engineer)
- [ ] D6. 프론트 UI — 관리자 감사 로그 조회 (책임. designer → frontend-engineer)
- [ ] D7. E2E (책임. qa-engineer)

## §3 다중 요소 인증 (FR-MF, 5개)

### §3.1 FR-MF-01 — TOTP (Authenticator 앱)

**우선순위**. 필수 | **선행**. §2.5, §2.9 | **Plan slug**. `identity/mfa-totp`

- [ ] D1. 도메인 — TotpSecret VO (책임. security-engineer)
- [ ] D2. 명세 — QR 코드 등록 + 6자리 코드 검증 (책임. security-engineer)
- [ ] D3. 데이터 모델 — `user_mfa_totp(secret_encrypted)` (책임. db-engineer)
- [ ] D4. 백엔드 — `aerogear-otp-java` 또는 자체 RFC 6238 (책임. security-engineer)
- [ ] D5. 백엔드 테스트 — clock drift ±1 step 허용 (책임. security-engineer)
- [ ] D6. 프론트 UI — QR 표시 + 6자리 입력 (책임. designer → frontend-engineer)
- [ ] D7. E2E (책임. qa-engineer)

### §3.2 FR-MF-02 — 백업 코드 (Recovery Codes)

**우선순위**. 필수 | **선행**. §3.1 | **Plan slug**. `identity/mfa-backup`

- [ ] D1. 도메인 (책임. security-engineer)
- [ ] D2. 명세 — 10개 1회용 코드 생성 + 해시 저장 (책임. security-engineer)
- [ ] D3. 데이터 모델 — `user_mfa_backup_codes(code_hash, used_at)` (책임. db-engineer)
- [ ] D4. 백엔드 — 코드 생성/검증/소진 (책임. security-engineer)
- [ ] D5. 백엔드 테스트 (책임. security-engineer)
- [ ] D6. 프론트 UI — 코드 다운로드/인쇄 + 1회용 안내 (책임. designer → frontend-engineer)
- [ ] D7. E2E (책임. qa-engineer)

### §3.3 FR-MF-03 — WebAuthn (Passkey/하드웨어 키)

**우선순위**. 선택 | **선행**. §3.1 | **Plan slug**. `identity/mfa-webauthn`

- [ ] D1. 도메인 (책임. security-engineer)
- [ ] D2. 명세 — FIDO2 attestation + assertion (책임. security-engineer)
- [ ] D3. 데이터 모델 — `user_webauthn_credentials(credential_id, public_key)` (책임. db-engineer)
- [ ] D4. 백엔드 — `webauthn4j` 라이브러리 (책임. security-engineer)
- [ ] D5. 백엔드 테스트 — 가상 Authenticator (책임. security-engineer)
- [ ] D6. 프론트 UI — `navigator.credentials` API (책임. designer → frontend-engineer)
- [ ] D7. E2E (책임. qa-engineer)

### §3.4 FR-MF-04 — 강제 정책 (관리자 + 민감 프로젝트)

**우선순위**. 필수 | **선행**. §3.1, §3.2 | **Plan slug**. `identity/mfa-enforce`

- [ ] D1. 도메인 — MfaPolicy (책임. security-engineer)
- [ ] D2. 명세 — 역할/프로젝트 단위 강제 (책임. security-engineer)
- [ ] D3. 데이터 모델 — `mfa_policies(scope, required)` (책임. db-engineer)
- [ ] D4. 백엔드 — 인증 중간 단계에서 MFA 등록 강제 (책임. security-engineer)
- [ ] D5. 백엔드 테스트 (책임. security-engineer)
- [ ] D6. 프론트 UI — MFA 미등록 시 step-up 페이지 (책임. designer → frontend-engineer)
- [ ] D7. E2E (책임. qa-engineer)

### §3.5 FR-MF-05 — 신뢰 디바이스 (30일 면제)

**우선순위**. 높음 | **선행**. §3.1 | **Plan slug**. `identity/trusted-devices`

- [ ] D1. 도메인 — TrustedDevice (책임. security-engineer)
- [ ] D2. 명세 — 사용자 동의 + 30일 TTL + 취소 (책임. security-engineer)
- [ ] D3. 데이터 모델 — `trusted_devices(device_fingerprint, expires_at)` (책임. db-engineer)
- [ ] D4. 백엔드 — fingerprint 발급 + MFA 우회 검증 (책임. security-engineer)
- [ ] D5. 백엔드 테스트 — TTL 만료 + 명시적 취소 (책임. security-engineer)
- [ ] D6. 프론트 UI — "이 디바이스 신뢰" 체크박스 + 디바이스 관리 페이지 (책임. designer → frontend-engineer)
- [ ] D7. E2E (책임. qa-engineer)

## §4 권한 관리 (FR-PM, 7개)

### §4.1 FR-PM-01 — 프로젝트 행정 (관리자/멤버 관리)

**우선순위**. 필수 | **선행**. §2.1 | **Plan slug**. `identity/project-admin`

> **FR-PM-01 백엔드 D1~D5 완료 (2026-06-01, PR #48)**. ProjectMembership/ProjectRole(PROJECT_ADMIN/MEMBER 2종) identity-access 모듈 도입 — V007 `project_memberships`(project_id는 issue-tracking projects를 FK 없이 cross-BC 참조, user_id FK, UNIQUE+admin 부분 인덱스), `JdbcProjectMembershipRepository`, `ProjectDirectory` read-only 포트(projects 존재검증), `ProjectMembershipService`(부트스트랩 + 멤버십 자기참조 가드 + 마지막 admin 보호 + audit), `ProjectMemberController`(`/api/v1/projects/{projectId}/members` CRUD). **핵심 결정**(ADR `2026-06-01-project-membership-model`) — 부트스트랩은 멤버 0명 프로젝트에 JWT+자기자신만 자동 ADMIN(타인/PAT 거부, B2), CRUD는 PAT 허용(B1), 비멤버 404 존재숨김(B3, 세션 IDOR 선례), 부트스트랩·마지막admin은 `pg_advisory_xact_lock` 하 count 재조회로 TOCTOU 차단(C1/C2), 권한변경 audit emit(AuthEventType 3종). 검증 — detekt/단위/Testcontainers 통합(EC-1/EC-2b 동시성 포함) 그린. **D6 프론트/D7 E2E는 후속 PR** — 이 백엔드가 미뤄둔 회원가입(전역 admin 권한 FR-PM-01 선행)의 토대. PRE_EXISTING(우리 무관) — AuthControllerTest 2건(main 재현 확정)·identity-access ktlint debt는 별도 cleanup.

- [x] D1. 도메인 — ProjectRole (책임. security-engineer)
- [x] D2. 명세 — 관리자 멤버 초대/제거 (책임. security-engineer)
- [x] D3. 데이터 모델 — `project_memberships(project_id, user_id, role)` (책임. db-engineer)
- [x] D4. 백엔드 — CRUD API + 가드 (책임. security-engineer)
- [x] D5. 백엔드 테스트 (책임. security-engineer)
- [x] D6. 프론트 UI — 프로젝트 설정 → 멤버 (책임. designer → frontend-engineer)
- [x] D7. E2E (책임. qa-engineer)

> **FR-PM-01 D6 프론트/D7 E2E 완료 (2026-06-01, PR #50)**. `/projects/$projectKey/settings/members` 멤버 관리 화면 — 목록(displayName 표시 + 역할 배지) + 추가(typeahead 검색→선택→역할) + 역할변경(낙관적) + 제거 + 비멤버 404 "접근 권한 없음", 에러 8종→한국어 토스트, MSW stateful refetch + Playwright S1~S6. **백엔드 슬라이스 동반** — 멤버 API path를 Jira식 `{projectIdOrKey}`로 확장(`ProjectDirectory.resolveKeyToId`, 비UUID·비key 입력도 404 단일봉투 존재숨김), 멤버 응답에 `displayName`/`username` 동봉(users LEFT JOIN, 같은 BC). **핵심 결정**(ADR `2026-06-01-project-member-projectidorkey`) — projectIdOrKey 수용(workflow-scheme projectKey 관례 정합), 이름은 멤버 응답 동봉(decision C, 50건 검색 상한 회피), 부트스트랩/생성자-자동admin UI는 프로젝트 생성 FR으로 이연. 머지 중 PR #51(FR-IS-03)의 사용자 디렉토리 인프라(`api/users.ts`·`use-users`·`user-handlers`)와 통합(중복 제거). 검증 — 백엔드 identity-access+detekt 그린, 프론트 단위 691+typecheck+build 그린, E2E 멤버6+기존 회귀0.

### §4.2 FR-PM-02 — 이슈 등록/수정/삭제 권한 분리

**우선순위**. 필수 | **선행**. §4.1 | **Plan slug**. `identity/issue-permissions`

- [x] D1. 도메인 — Permission (CREATE_ISSUE/EDIT_ISSUE/DELETE_ISSUE) (책임. security-engineer) — PR #53
- [x] D2. 명세 — 역할 × 권한 매트릭스 (책임. security-engineer) — PR #53
- [x] D3. 데이터 모델 — `permission_schemes` + `role_permissions` (+ `project_permission_scheme`) (책임. db-engineer) — V008, PR #53
- [x] D4. 백엔드 — 권한 가드 (명시 호출 `hasPermission`, `@PreAuthorize` 미도입 G4) (책임. security-engineer) — PR #53
- [x] D5. 백엔드 테스트 — 권한 매트릭스 전수 13케이스 (책임. security-engineer) — PR #53
- [x] D6. 프론트 UI — 권한 없는 액션 버튼 비활성화 (책임. designer → frontend-engineer) — PR #55
- [x] D7. E2E (책임. qa-engineer) — PR #55

### §4.3 FR-PM-03 — 버전/컴포넌트 등록 권한

**우선순위**. 필수 | **선행**. §4.2(권한 매트릭스) + **기능 선행 FR-CM-01(§3.1.1, 컴포넌트 CRUD) / FR-VR-01(§3.2.1, 버전 CRUD)** | **Plan slug**. `identity/version-component-permissions`

> 기능 선행 메모(2026-06-02, 갱신 2026-06-03). FR-PM-03은 "버전/컴포넌트 엔드포인트에 @PreAuthorize 추가"라 대상 기능이 먼저 있어야 한다. FR-CM-01(컴포넌트)은 PR #59로 구현 완료(권한은 ComponentPermissionResolver 포트로 추상화, prod 실판정을 FR-PM-03이 채움 — ADR docs/adr/2026-06-02-component-model-and-permission-deferral.md). **FR-VR-01(버전) 백엔드 D1~D5도 PR #67로 완료**(VersionPermissionResolver 포트 추상화, prod 실판정 FR-PM-03 이연 — ADR docs/adr/2026-06-03-version-model-and-permission-deferral.md). **→ FR-PM-03 기능 선행(컴포넌트·버전 CRUD) 모두 충족, 착수 가능.** FR-PM-03은 두 리졸버(ComponentPermissionResolver, VersionPermissionResolver)의 prod 구현 + permission_schemes 매트릭스를 채운다.

- [x] D1. 도메인 (책임. security-engineer) — PR #70 (ADR docs/decisions/2026-06-03-version-component-permission-prod-resolver.md)
- [x] D2. 명세 (책임. security-engineer) — PR #70
- [x] D3. 데이터 모델 — (FR-PM-02 활용) (책임. db-engineer) — PR #70 (V009: 기본 스킴에 MANAGE_COMPONENTS/MANAGE_VERSIONS PROJECT_ADMIN 시드. 신규 테이블 없음)
- [x] D4. 백엔드 — 권한 가드 prod 구현 (책임. security-engineer) — PR #70 (IdentityAccessComponent/VersionPermissionResolver @Profile prod. @PreAuthorize 대신 명시 호출 — IssuePermissionResolver 동형)
- [x] D5. 백엔드 테스트 (책임. security-engineer) — PR #70 (단위 MockK 2 + prod 프로파일 통합 매트릭스 각 9케이스. MEMBER/비멤버 거부 ground-truth)
- [x] D6. 프론트 UI (책임. frontend-engineer) — PR #72 (MyProjectPermissionController 확장으로 MANAGE_* 노출 + DevAllow fallback 2 + ComponentList/Row·VersionList/Row fail-closed 게이팅, 리드변경 포함)
- [x] D7. E2E (책임. qa-engineer) — PR #72 (권한 게이팅 E2E 4 + 기존 component/version-management 회귀 0)

### §4.4 FR-PM-04 — 워크플로우/자동화 관리 권한

**우선순위**. 필수 | **선행**. §4.2 | **Plan slug**. `identity/workflow-automation-permissions`

- [ ] D1. 도메인 (책임. security-engineer)
- [ ] D2. 명세 (책임. security-engineer)
- [ ] D3. 데이터 모델 — (FR-PM-02 활용) (책임. db-engineer)
- [ ] D4. 백엔드 — `@PreAuthorize` 추가 (책임. security-engineer)
- [ ] D5. 백엔드 테스트 (책임. security-engineer)
- [ ] D6. 프론트 UI (책임. frontend-engineer)
- [ ] D7. E2E (책임. qa-engineer)

### §4.5 FR-PM-05 — 이슈 접근 (Browse, View)

**우선순위**. 필수 | **선행**. §4.2 | **Plan slug**. `identity/issue-access`

- [ ] D1. 도메인 — BrowsePermission vs ViewPermission 분리 (책임. security-engineer)
- [ ] D2. 명세 (책임. security-engineer)
- [ ] D3. 데이터 모델 — (FR-PM-02 활용) (책임. db-engineer)
- [ ] D4. 백엔드 — 이슈 쿼리에 필터 자동 첨부 (jOOQ Condition 빌더) (책임. security-engineer + backend-engineer)
- [ ] D5. 백엔드 테스트 — 비공개 이슈 조회 차단 (책임. security-engineer)
- [ ] D6. 프론트 UI — 권한 없는 이슈 404 처리 (책임. frontend-engineer)
- [ ] D7. E2E (책임. qa-engineer)

### §4.6 FR-PM-06 — 이슈 보안 수준

**우선순위**. 필수 | **선행**. §4.5 | **Plan slug**. `identity/issue-security-level`

- [ ] D1. 도메인 — SecurityLevel 등급 (책임. security-engineer)
- [ ] D2. 명세 — 이슈마다 등급 + 등급별 접근자 (책임. security-engineer)
- [ ] D3. 데이터 모델 — `security_levels`, `issues.security_level_id` (책임. db-engineer)
- [ ] D4. 백엔드 — 등급 검증 가드 (책임. security-engineer)
- [ ] D5. 백엔드 테스트 (책임. security-engineer)
- [ ] D6. 프론트 UI — 이슈 생성/편집 시 등급 선택 (책임. designer → frontend-engineer)
- [ ] D7. E2E (책임. qa-engineer)

### §4.7 FR-PM-07 — 필드 수준 권한

**우선순위**. 높음 | **선행**. §4.5 | **Plan slug**. `identity/field-permissions`

- [ ] D1. 도메인 — FieldVisibility (책임. security-engineer)
- [ ] D2. 명세 — 필드 × 역할 매트릭스 (책임. security-engineer)
- [ ] D3. 데이터 모델 — `field_permissions(field_name, role, visibility)` (책임. db-engineer)
- [ ] D4. 백엔드 — 응답 직렬화 시 필드 필터 (책임. security-engineer + backend-engineer)
- [ ] D5. 백엔드 테스트 (책임. security-engineer)
- [ ] D6. 프론트 UI — 숨김 필드 렌더 차단 (책임. frontend-engineer)
- [ ] D7. E2E (책임. qa-engineer)

## §NFR identity-access BC 완료 게이트

### 측정값 기록표

| 항목 | 임계 | 실측 (p95) | 비고 |
|---|---|---|---|
| 로그인 응답 (LDAP) | 500ms | ___ | k6 |
| 로그인 응답 (OIDC) | 800ms | ___ | k6 (외부 라운드트립 포함) |
| 토큰 검증 (JWT) | 50ms | ___ | k6 단순 검증 |
| 권한 가드 오버헤드 | 10ms | ___ | k6 (이슈 GET 권한 검증) |
| 감사 로그 보존 | 1년 | ___ | DB 정책 확인 |
| WCAG 2.1 AA (로그인 페이지) | 0 violations | ___ | axe-core |
| 비밀번호 해싱 (Argon2id) | memory=64MB | ___ | DEVELOPMENT.md §1.1 |
| Trivy + Dependabot | 0 high/critical | ___ | 의존성 스캔 |

### BC 완료 조건

- [ ] §2 (FR-AU 10개) 모두 `[x]` 마킹
- [ ] §3 (FR-MF 5개) 모두 `[x]` 마킹
- [ ] §4 (FR-PM 7개) 모두 `[x]` 마킹
- [ ] §NFR 측정표 모든 항목 임계 통과
- [ ] OWASP Top 10 자가 점검 (`docs/adr/<date>-identity-owasp-audit.md`)
- [ ] CHANGELOG.md 정리 (BC 단위 변경 요약)
- [ ] README.md §7 변경 이력에 "identity-access BC 완료 — YYYY-MM-DD" 추가
- [ ] Maxi 1인 선언 — "identity-access BC 완료"
