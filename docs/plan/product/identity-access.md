<!-- identity-access BC — 인증/2FA/권한 25 FR + AuthN Provider PoC -->

# identity-access BC

**소속 FR**. 25개 (AU 10 + MF 5 + PM 10).
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
- [x] D4. 백엔드 — 가입/비밀번호 변경/리셋 (책임. security-engineer) — **변경(PR #44) + 가입(PR #99) 완료. 리셋은 이메일 인프라(notification BC) 도입 후속 FR**
- [x] D5. 백엔드 테스트 — 비밀번호 정책 위반 케이스 (책임. security-engineer) (PR #44)
- [x] D6. 프론트 UI — 가입/비밀번호 변경 폼 (책임. designer → frontend-engineer) — **변경 폼(PR #45) + 가입 폼(PR #99) 완료**
- [x] D7. E2E (책임. qa-engineer) — **변경 E2E(PR #45) + 가입·강제변경 E2E(PR #99) 완료**

> **FR-AU-05 회원가입 완료 (2026-06-08, PR #99)**. 관리자(SYSTEM_ADMIN) 로컬 계정 생성 슬라이스 완결 — `POST /api/v1/users`(`@PreAuthorize hasRole SYSTEM_ADMIN`, FR-PM-08 인프라 소비) + 서버 SecureRandom 임시비번(정책 충족, 응답 1회·평문 미저장/wipe) + INSERT 전용 생성(중복 409 USERNAME_TAKEN, UPSERT 덮어쓰기 금지) + `local_credentials.must_change_password`(V019, 머지 후 FR-PM-07 #97 V018 선점으로 V018→V019 재번호) + 첫 로그인 강제 변경(whoami `mustChangePassword`/`isSystemAdmin` 노출 → 프론트 `requirePasswordChanged`/`requireSystemAdmin` 가드 → 변경 시 rotate→store(false)→UPSERT SET 자동 해제) + 가입 폼(`/admin/users/new`) + Playwright E2E. **deviation** — whoami가 `isSystemAdmin`도 노출(프론트 admin 게이팅), 감사로그는 FR-AU-10 위임, 강제변경 enforcement는 프론트 가드(백엔드 전역 차단은 후속 하드닝). **리셋만 미구현**(이메일 인프라 부재, 후속 FR). 덤으로 FR-IS-10 #98의 `CustomFieldPermissionResolver` non-prod fallback 누락(통합테스트 84건 부팅 회귀)을 `DevAllowCustomFieldPermissionResolver`로 hot-fix. 검증 — 백엔드 1257+ 테스트/ktlint/detekt 그린, 프론트 typecheck/lint/vitest1510/build 그린, E2E 131 그린. ADR [2026-06-08-local-account-signup](../../decisions/2026-06-08-local-account-signup.md).

> **FR-AU-05 비밀번호 변경 프론트 완료 (2026-05-31, PR #45)**. D6/D7 중 **비밀번호 변경 폼** 슬라이스 완료 — `/settings/password`(requireAuth) 라우트 + `ChangePasswordForm`(현재/새/확인 3입력) + `changePassword` API(POST + X-XSRF-TOKEN) + `useChangePassword` 훅 + MSW 핸들러 + Playwright E2E 4시나리오(정상/현재불일치/정책위반/확인불일치). 클라이언트 검증은 required+새≠확인만, 정책(12자/3종)은 정적 안내+서버 권위(D1 결정, drift 차단). 에러코드 대문자 정합(frontend-zod-backend-dto-contract-gap PR #41 선례 회피), POLICY_VIOLATION 문구는 violations(MIN_LENGTH/COMPLEXITY)로 프론트 생성. **가입 폼/가입 E2E는 후속** — 가입 API(전역 admin 권한 FR-PM-01 선행)와 함께. 검증 — lint/typecheck/단위443/E2E49 그린. 백엔드 무변경, 마이그레이션 0건.

> **FR-AU-05 비밀번호 변경 완료 (2026-05-30, PR #44)**. D4 중 **비밀번호 변경** API(`POST /api/v1/users/me/password` + `PasswordPolicy` 12자/3종 복잡도 + 변경 성공 시 현재 세션 제외 다른 세션 무효화 — `revoke`+`revokeChainFromSession` 쌍) + D5(정책 위반 테스트) 완료. 기존 `LocalCredentialService.rotate`/`SessionService`/`RefreshTokenRepository` 재사용, 마이그레이션 0건. **가입(관리자+임시비번)·리셋은 후속** — 가입은 전역 admin 권한 체계(FR-PM-01) 선행 필요, 리셋은 이메일 발송 인프라 도입 후 (현재 `JavaMailSender`/notification BC 부재). D6(프론트 폼)·D7(E2E)은 PR-2. PRE_EXISTING ktlint debt(identity-access 모듈 285건)는 별도 cleanup PR 위임.

### §2.6 FR-AU-06 — 다중 Provider 동시 활성화

**우선순위**. 필수 | **선행**. §2.1~§2.5 | **Plan slug**. `identity/multi-provider`

- [x] D1. 도메인 (책임. security-engineer)
- [x] D2. 명세 — Provider **명시 선택** 규칙 (자동 fallback 폐기 — 보안) (책임. security-engineer)
- [x] D3. 데이터 모델 — `authn_providers.enabled, sort_order` (기존 컬럼 활용, 마이그레이션 0) (책임. db-engineer)
- [x] D4. 백엔드 — `CompositeAuthenticationManager` (명시 선택 디스패처) (책임. security-engineer)
- [x] D5. 백엔드 테스트 — 다중 Provider 시나리오 (책임. security-engineer)
- [x] D6. 프론트 UI — 다중 Provider 선택 화면 (책임. designer → frontend-engineer)
- [x] D7. E2E (책임. qa-engineer)

> **FR-AU-06 완료 (2026-06-09, PR #101)**. 여러 인증 방식(LOCAL·LDAP·SAML·OIDC) 동시 활성화 + 로그인 화면 동적 목록. **D2 deviation** — 원안의 "우선순위 + fallback 규칙"을 brainstorming 보안 검토 후 **명시 선택만**으로 변경(자동 fallback 폐기). 자동 순차 시도의 위험 3종(동명이인 타계정 로그인 · 비밀번호 오전달 · lockout 2배)을 회피하며, "똑똑한 자동 선택"은 FR-AU-07(도메인 기반 라우팅)이 담당. **핵심 — G1 버그 해소**. 변경 전 `AuthController`가 항상 `Credential.UsernamePassword`만 생성해 `LdapProvider`(LdapBind만 supports) 진입 불가 → username/password LDAP 로그인이 사실상 불가했음. `CompositeAuthenticationManager`(명시 선택 디스패처)가 provider별 Credential을 생성하도록 수정. providers 목록은 username/password 계열(LOCAL/LDAP)만 코드 Bean ∩ DB(`enabled`/`sort_order`) 오버레이로 반환(SAML/OIDC는 별도 엔드포인트). **마이그레이션 0**(컬럼 기존재, LOCAL/LDAP seed 안 함 — LDAP config는 환경 의존). `isEnabled` fail-safe(비활성 row 하나라도 있으면 false). 같은 type 다중 인스턴스(LdapTemplate 동적 wiring)는 **제외 → 후속 FR**. ADR `docs/decisions/2026-06-09-multi-provider-explicit-selection.md`.

### §2.7 FR-AU-07 — 도메인 기반 자동 라우팅

**우선순위**. 높음 | **선행**. §2.6 | **Plan slug**. `identity/domain-routing` (실제 작업 slug `fr-au-07-provider`)

- [x] D1. 도메인 (책임. security-engineer)
- [x] D2. 명세 — 이메일 도메인 → Provider 매핑 (책임. security-engineer)
- [x] D3. 데이터 모델 — `domain_provider_routes(domain, provider_id)` (책임. db-engineer)
- [x] D4. 백엔드 — 이메일 입력 → Provider 자동 선택 (책임. security-engineer)
- [x] D5. 백엔드 테스트 (책임. security-engineer)
- [x] D6. 프론트 UI — 이메일 입력 후 Provider 자동 진입 (책임. designer → frontend-engineer)
- [x] D7. E2E (책임. qa-engineer)

> **FR-AU-07 완료 (2026-06-09, PR #102)**. 이메일 도메인 기반 SSO 자동 라우팅(Home Realm Discovery). **라우팅 대상 SSO 전용(SAML/OIDC)** — LOCAL/LDAP는 authn_providers 시드 부재 + 비밀번호 오전달 위험으로 제외(FR-AU-06이 위임한 "똑똑한 자동 선택"을 안전하게 담당). 신규 테이블 `domain_provider_routes(domain UNIQUE, provider_id → authn_providers ON DELETE CASCADE)` V020(시드 없음, 환경 의존). 조회 `GET /api/v1/auth/route?domain=`(permitAll) — 매칭 판정은 **type 분기 2-step**(라우트→authn_providers.type→enabled SAML/OIDC config 단일조회, cartesian 회피). 매칭/미매칭 둘 다 200(계정 열거 0, 도메인만 판단·자격증명 미취급). fail-safe — 끊긴 라우트·비활성·LOCAL/LDAP 지시는 null→2단계 폼 fallback. 프론트 **identifier-first 2단계**(이메일 먼저→매칭 시 `ssoEntryUrl`로 SSO 자동 리다이렉트(encodeURIComponent), 미매칭 시 기존 폼+이메일 프리필). 도메인 **exact + lowercase 정규화**(Controller), 서브도메인 매칭·라우트 CRUD 관리 UI는 후속 FR. identity-access는 jdbc-only라 init_codegen 미러 불요. ADR `docs/decisions/2026-06-09-domain-based-provider-routing.md`.

### §2.8 FR-AU-08 — 계정 통합 (Account Linking)

**우선순위**. 높음 | **선행**. §2.1~§2.5 | **Plan slug**. `identity/account-linking`

- [x] D1. 도메인 (책임. security-engineer) (PR #103)
- [x] D2. 명세 — 동일 사용자 다중 외부 계정 통합 워크플로우 (책임. security-engineer) (PR #103)
- [x] D3. 데이터 모델 — `user_external_accounts` 다대일 (책임. db-engineer) — **기존 V002 재사용, 마이그레이션 0건** (PR #103)
- [x] D4. 백엔드 — Linking API + 재인증 강제 (책임. security-engineer) — **LDAP 연결+해제+목록+재인증(step-up) 완료(PR #103). SSO(SAML/OIDC) 연결 + SSO 재인증 완료(FR-AU-08b, PR #104)** (PR #103, #104)
- [x] D5. 백엔드 테스트 — 충돌 케이스 (책임. security-engineer) (PR #103)
- [x] D6. 프론트 UI — "계정 연결" 설정 페이지 (책임. frontend-engineer) — **`/settings/account-links` + linkable-providers 엔드포인트 (PR #106)**
- [x] D7. E2E (책임. qa-engineer) — **account-links.spec 8시나리오 (PR #106)**

> **FR-AU-08 완료 — D6 UI·D7 E2E (2026-06-10, PR #106)**. 계정 연결 셀프서비스 설정 페이지 `/settings/account-links`(`requireAuthAndPasswordChanged`) — 연결 목록(provider 타입 배지·마스킹 식별자·연결/마지막로그인 시각·비활성 배지) + 계정 추가(LDAP 인라인 username/password 폼 · SSO 리다이렉트 2단계) + 해제(확인 다이얼로그) + step-up 재인증 모달(LOCAL/LDAP 동기 · SSO 리다이렉트) + SSO 콜백 쿼리(`?link=`/`?reauth=`) 토스트+제거. **백엔드 view-layer 1종 추가** — `GET /api/v1/auth/account/linkable-providers`(JWT 전용·PAT 403·enabled LDAP UUID+SAML/OIDC registrationId 통합·`@JsonInclude(NON_NULL)`로 Zod discriminatedUnion 정합), `AuthnProviderConfigRepository.findEnabledByType` 신설(same-BC view layer 예외). **보안** — step-up 서버 403 `step_up_required` 진실출처(클라 `stepUpExpiresAt`는 round-trip 절감 보조)·sid 프론트 미취급·SSO open-redirect 0(백엔드 구성 URL만)·전 mutation X-XSRF-TOKEN·비번 메모리만·PII 마스킹·계정열거 0. **검증** — 백엔드 test+ktlint+detekt green(--rerun-tasks), 프론트 verify(lint/typecheck/test 1824/build), E2E 14/14(신규 8 + 회귀 6). 덤으로 FR-AU-07 이후 깨져 있던 `loginAsAlice` E2E 선재 회귀(identifier-first 2단계) 해소. 독립 security+프론트 eng ground-truth plan리뷰(BLOCKER 3 반영) + PR 2관점 코드리뷰(code-reviewer BLOCKER 1 — ReauthDialog가 백엔드 `error` 키 미참조+가짜그린 테스트 → 공유 util 추출 수정). **FR-AU-08 전체 완료**(1차 #103 LDAP + 08b #104 SSO + D6/D7 #106 UI/E2E).

> **FR-AU-08 1차 백엔드 완료 (2026-06-09, PR #103)**. 로그인 사용자의 외부 신원 **명시적 수동 연결**(이메일 자동 연결 미도입 — 계정 탈취 차단, FR-AU-06/07 보안 기조 일관). API 4종 — `GET /api/v1/auth/account/links`(목록+`hasLocalPassword`, 마스킹) · `POST /reauth`(재인증 챌린지→Caffeine `sid→expiry` 5분 step-up 윈도우, **sid는 JWT 클레임만**) · `POST /links`(LDAP 동기 연결, step-up 필요, 신규 201/멱등 200) · `DELETE /links/{id}`(step-up 필요, 204). **충돌 규칙** — 타계정 선점 거부(409, 계정 열거 0)·동일계정 멱등·**마지막 수단 해제 거부**(409, enabled provider 링크+LOCAL만 카운트, userId `pg_advisory_xact_lock`(hashtextextended) TOCTOU 직렬화). LDAP 다운→503, bind 실패→401, PAT→403 전 엔드포인트. `LdapProvider.bindForLinking`(bind만, provision 분리 — 일반 로그인 경로 무변경 회귀가드 green). **2단계 분리(Maxi)** — SSO(SAML/OIDC) 리다이렉트 연결은 보안 핵심 성공 핸들러 수술이 필요해 **FR-AU-08b 후속**. **deviation/한계** — SSO 전용 사용자는 1차에서 동기 재인증 불가(연결/해제 불가, 08b 위임), LOCAL lockout 인프라 부재로 reauth 적극 rate-limit 후속, 감사로그 FR-AU-10 위임, **D6 UI·D7 E2E 후속(백엔드 전용 1차)**. 마이그레이션 0건. 검증 — identity-access test+ktlint+detekt green(--rerun-tasks), 통합테스트 S1~S8+EC10(동시 해제 TOCTOU) 실증, 기존 LDAP/일반 로그인 회귀 0. 독립 보안 plan리뷰(BLOCKER 2+CONCERN 6) + PR 2관점 리뷰(BLOCKER 0, EC3 503 가짜그린·reauth 500 CONCERN 2 수정) 반영. ADR [2026-06-09-account-linking-policy](../../decisions/2026-06-09-account-linking-policy.md).

> **FR-AU-08b SSO 연결+재인증 백엔드 완료 (2026-06-09, PR #104)**. SSO(SAML/OIDC) 리다이렉트 방식 계정 연결 + SSO 전용 사용자 재인증(step-up)으로 1차 한계 2종 해소(SSO 신원 명시 연결 부재 · SSO 전용 사용자 연결/해제 불가). **신규 엔드포인트 2종** — `POST /links/sso/start`(JWT+step-up) · `POST /reauth/sso/start`(JWT). 둘 다 XHR 로 HttpSession 에 `LinkingIntent` 저장 + JSESSIONID 세팅 후 `{authorizeUrl}` 반환 → SPA 가 SSO 로 네비게이트(2단계 흐름). **보안 핵심 — 연결 모드 성공 핸들러 분기(fail-closed)** — SAML/OIDC 성공 핸들러가 콜백서 인텐트-first 로 분기(intent 있으면 일반 로그인 발급 경로 물리적 진입 불가·early return). LINK=현재 userId attach(**새 세션/JWT/provision 미발생** — confused-deputy 차단), REAUTH=본인 링크 일치(EC9 동형) 시만 `StepUpService.grant(sid)`. 콜백 providerId 해소는 **enabled provider 로만**(OIDC `findEnabledByRegistrationId` 추가로 SAML 과 대칭, start↔콜백 TOCTOU 차단, B1/EC16). **충돌/마지막수단 1차 계승** — 타계정 선점 거부(`?link=conflict`, 순수 INSERT)·동일계정 멱등(`?link=already_linked`)·동시 콜백 `(provider,subject)` advisory lock 직렬화(EC18, 전폭 해시)·마지막수단 enabled 링크+LOCAL 카운트(비활성 SSO 링크 제외, 영구 락 방지, C4). **인텐트 보존=HttpSession 피기백**(URL 미노출·1회용·단명 ≤5분, EC10 session-fixation `changeSessionId` 이관)·**JSESSIONID SameSite=None base + secure prod 전용**(SAML ACS cross-site POST 왕복 생존, EC17, base/test http 부팅 보호). sid 는 JWT 클레임 출처만(FR8)·PAT→403. 공통 추출 `AccountLinkJwtSupport`(JWT subject+sid·step-up 게이트·errorResponse 단일화, drift 차단). **C3 분해** — SAML ACS cross-site POST 브라우저 왕복은 JVM 통합테스트 불가(기존 SamlAuthFlowIntegrationTest 도 ACS POST 제외)이라 통합테스트(실 Postgres + 서비스/processor 직접 구동)는 S1(LINK attach+세션/refresh 미발급)·S4(선점 conflict)·EC2(멱등)·S2/EC9(REAUTH grant)·EC8(타신원 미grant)·C3(registrationId 불일치)·S3/C4(SSO-only 해제 204→마지막 409)·EC16(비활성 링크 카운트 제외)·EC1(intent 없으면 process=false 위임)·EC18(동시 직렬화)로 분해, session-fixation/SameSite 는 Task 8 단위/설정 검증, 진짜 브라우저 왕복은 E2E(D7) 위임. 마이그레이션 0·신규 권한코드 0·cross-BC 0. **D6 UI·D7 E2E 후속(백엔드 전용)**. 검증 — identity-access test+ktlint+detekt green(--rerun-tasks), 통합 11시나리오 실증, 기존 SAML/OIDC/LDAP/일반 로그인 회귀 0. ADR [2026-06-09-sso-account-linking](../../decisions/2026-06-09-sso-account-linking.md).

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

- [x] D1. 도메인 — AuthEvent (성공/실패/세션종료/권한변경) (책임. security-engineer)
- [x] D2. 명세 — 보존 1년 (SDD §2.3.3) (책임. security-engineer)
- [x] D3. 데이터 모델 — `auth_audit_logs(event_type, ip, user_agent, ...)` (책임. db-engineer)
- [x] D4. 백엔드 — 모든 인증/권한 변경 이벤트 emit (책임. security-engineer)
- [x] D5. 백엔드 테스트 — 이벤트 누락 0 (책임. security-engineer)
- [x] D6. 프론트 UI — 관리자 감사 로그 조회 (책임. designer → frontend-engineer)
- [x] D7. E2E (책임. qa-engineer)

> **FR-AU-10 백엔드 1차 완료 (2026-06-10, PR #108)**. 기존 `audit/` 골격(AuthEventType 12종·AuthAuditLog·AuthAuditLogService·InMemory)을 DB 영속화 — `JdbcAuthAuditLogService`(@Service @Transactional, NamedParameterJdbcTemplate, JSONB metadata는 PAT scopes 선례 재사용) + V021 `auth_audit_logs`(파티셔닝 없는 append-only 단순 테이블 + 인덱스 3종, FK 없음). **emit 12종 전수 배선** — 기존 4종(PAT/PROJECT_*)에 8종 갭(LOGIN_SUCCESS는 AuthController+OIDC+SAML 3곳, LOGIN_FAILURE/LOGOUT, LOGOUT_ALL_DEVICES, TOKEN_REFRESHED, SUSPICIOUS_REFRESH_REPLAY, USER_PROVISIONED, LDAP_UNAVAILABLE) 추가. `AuthEventEmitCoverageTest`가 누락 0 회귀 가드. **결정** — B-1: web 레이어 emit best-effort(try-catch+에러로그, 로그인 가용성 우선) / service 레이어 트랜잭션 동기. C-5: refresh replay+race-loser 둘 다 SUSPICIOUS_REFRESH_REPLAY. **DATA.md §3 충돌 해소** — 감사 로그 append-only 영구 보존("보존 1년"은 최소 floor), @Scheduled 삭제 작업 드롭. `AuthAuditLog.userId` nullable화(LOGIN_FAILURE/LDAP_UNAVAILABLE 사용자 미상), USER_PROVISIONED는 `xmax=0` 신규 판정(기존 재로그인 비-emit), LOGIN_FAILURE 계정열거 차단(userId=null, username 역조회 금지). 검증 — identity-access 1540 테스트 0실패, ktlint/detekt green, 리뷰 2종(code-reviewer PASS + 보안 적대적 BLOCKER0/CONCERN0). ADR [2026-06-10-auth-audit-log-persistence](../../decisions/2026-06-10-auth-audit-log-persistence.md). **D6 관리자 조회 UI + D7 E2E는 후속 PR**(조회 API 포함) → PR #112 완료.

> **FR-AU-10 완료 — D6/D7 + 관리자 조회 API (2026-06-11, PR #112)**. 백엔드 1차(#108)에서 미룬 **관리자 전역 조회 API** + 프론트 조회 UI + E2E로 FR-AU-10 전체 종료. **조회 API** — `GET /api/v1/admin/auth-audit-logs`(`@PreAuthorize hasRole('SYSTEM_ADMIN')` + SecurityFilterChain authenticated 이중가드, 비관리자/PAT 403). 필터 eventType·userId·from(>=)·to(<=) + offset 페이지네이션(page/size, 기본 50·1..100), 잘못된 파라미터 400(`invalid_query_parameter` 일반 메시지). `AuthAuditLogAdminQueryRepository`+Jdbc — **users LEFT JOIN**으로 주체(username/displayName) 표시, 삭제/미상 사용자는 null(append-only 감사 보존), count는 JOIN 없이 단독(cartesian 회피), 동적 WHERE 전부 named parameter(SQL 인젝션 방어). read-only(@Transactional readOnly), 마이그레이션 0(V021 인덱스 3종 재사용). **결정** — 주체=LEFT JOIN / 필터=eventType+기간+userId / 진입=Header 관리 메뉴를 `isSystemAdmin===true`일 때만 노출(기존 전체노출→admin 전용, 워크플로우 스킴 링크 포함). **프론트** — `/admin/audit-logs`(composeGuards requireAuth+requireSystemAdmin+requirePasswordChanged), Zod `.nullish()`로 `@JsonInclude(NON_NULL)` 키 생략 대응, 이벤트 12종 한국어 라벨(전방호환 z.string()), 사용자 typeahead 필터, keepPreviousData 페이지네이션. **deviation** — workflow-schemes 라우트 가드(requireAuth)는 미변경(nav 가시성만 admin-only, surgical). deviceFingerprint 응답 제외(미사용). 신규 마이그레이션·권한코드·ADR 0(영속화 ADR 계승). 검증 — 백엔드 1564+신규 24 테스트·ktlint/detekt(--rerun-tasks) green, 프론트 typecheck/lint·1982 테스트 green, E2E 4/4 green, code-reviewer PASS(BLOCKER0/CONCERN0, S1/S2 반영). 선재 회귀 — workflow-scheme E2E 5건은 workflow-scheme-fixtures 1단계 loginAsAlice(FR-AU-07 미반영, 본 PR 무관).

## §3 다중 요소 인증 (FR-MF, 5개)

### §3.1 FR-MF-01 — TOTP (Authenticator 앱)

**우선순위**. 필수 | **선행**. §2.5, §2.9 | **Plan slug**. `identity/mfa-totp`

- [x] D1. 도메인 — TotpSecret VO (책임. security-engineer)
- [x] D2. 명세 — QR 코드 등록 + 6자리 코드 검증 (책임. security-engineer)
- [x] D3. 데이터 모델 — `totp_secrets(secret_cipher)` (V022, 책임. db-engineer)
- [x] D4. 백엔드 — `dev.samstevens.totp:1.7.1` (RFC 6238, 책임. security-engineer)
- [x] D5. 백엔드 테스트 — clock drift ±1 step 허용 (책임. security-engineer)
- [x] D6. 프론트 UI — QR 표시 + 6자리 입력 (책임. designer → frontend-engineer)
- [x] D7. E2E (책임. qa-engineer)

### §3.2 FR-MF-02 — 백업 코드 (Recovery Codes)

**우선순위**. 필수 | **선행**. §3.1 | **Plan slug**. `identity/mfa-backup`

- [x] D1. 도메인 (책임. security-engineer) (PR #117)
- [x] D2. 명세 — 10개 1회용 코드 생성 + 해시 저장 (책임. security-engineer) (PR #117)
- [x] D3. 데이터 모델 — `user_mfa_backup_codes(code_hash, used_at)` (책임. db-engineer) (V023, PR #117)
- [x] D4. 백엔드 — 코드 생성/검증/소진 (책임. security-engineer) (PR #117)
- [x] D5. 백엔드 테스트 (책임. security-engineer) (PR #117)
- [x] D6. 프론트 UI — 코드 복사/다운로드(.txt) + 1회용 안내 (책임. frontend-engineer) (PR #121)
- [x] D7. E2E (책임. qa-engineer) (PR #121)

### §3.3 FR-MF-03 — WebAuthn (Passkey/하드웨어 키)

**우선순위**. 선택 | **선행**. §3.1 | **Plan slug**. `identity/mfa-webauthn`

- [x] D1. 도메인 (책임. security-engineer) (PR #129)
- [x] D2. 명세 — FIDO2 attestation + assertion (책임. security-engineer) (PR #129)
- [x] D3. 데이터 모델 — `user_webauthn_credentials(credential_id, public_key)` (책임. db-engineer) (PR #129)
- [x] D4. 백엔드 — `webauthn4j` 라이브러리 (책임. security-engineer) (PR #129)
- [x] D5. 백엔드 테스트 — 가상 Authenticator (책임. security-engineer) (PR #129)
- [x] D6. 프론트 UI — `navigator.credentials` API (책임. frontend-engineer) (PR #130)
- [x] D7. E2E (책임. qa-engineer) (PR #130)

> **FR-MF-03 백엔드 D1~D5 완료 (2026-06-12, PR #129)**. WebAuthn(Passkey/하드웨어 키) 2차 인증 백엔드 — 등록(attestation)/인증(assertion) 챌린지·검증·자격증명 관리. **핵심 결정**(ADR `docs/decisions/2026-06-12-webauthn-second-factor.md`) — `webauthn4j` 0.28.4(Jackson2 ObjectMapper 버전 고정으로 직렬화 호환성 확보), attestation `none`(서버는 제조사 검증 없이 등록 수락 — 2차 인증 용도라 신뢰성 충분), credential은 사용자당 N개 등록 가능(전역 `credential_id` UNIQUE로 중복/이관 방지), `sign_count` 단조 증가 검증으로 자격증명 복제(clone) 공격 방어, 등록/인증 challenge는 Caffeine 인메모리 캐시(단기 TTL), 공개키는 평문 저장(비밀값 아님 — `attested_credential_data`에 base64 직렬화로 포함). DB는 `webauthn_credentials`(V025, SDD 원안 명세 표기 `user_webauthn_credentials`에서 실제 테이블명은 `webauthn_credentials`). `MfaChallenge.WEBAUTHN` enum 추가 + `isAnyMfaEnabled` 합성(TOTP/백업코드/WebAuthn 중 하나라도 등록 시 true). 감사 이벤트 `MFA_WEBAUTHN_REGISTERED`/`MFA_WEBAUTHN_REMOVED`. **deviation** — (1) `WebAuthnProperties`(rpId/rpName/origin)는 빈 문자열 기본값으로 두어 미설정 시에도 부팅 안전성 확보(prod는 실제 값 주입 필요), (2) credential에 별도 `status` 컬럼 없음 — 검증 통과 후 INSERT 자체가 활성화를 의미(soft-disable 불필요). **후속** — D6 프론트 UI(`navigator.credentials`)/D7 E2E는 후속 PR.

> **FR-MF-03 D6/D7 완료 (2026-06-13, PR #130)**. 프론트 UI + E2E. **백엔드 변경 0(계약 소비)**. **Maxi 결정** — (1) 설정 `/settings/mfa`에 보안 키를 TOTP와 독립된 섹션으로 **항상 노출**(백엔드 `isAnyMfaEnabled`가 WebAuthn을 OR로 포함 → TOTP 없이도 등록 가능, 백업코드와 다름), (2) 로그인 2단계에 "보안 키로 인증" **독립 액션 버튼**(코드 입력 폼과 병렬 — mfa_required가 보유 요소 미노출이라 선택지 모두 제시, MfaCodeInput 세 번째 mode 아님으로 zodResolver 함정 회피), (3) base64url↔ArrayBuffer 변환은 `@simplewebauthn/browser`(신규 의존성, 절대규칙 #17 승인) 사용 — webauthn4j `verify*ResponseJSON`과 W3C 표준 형식 호환, (4) E2E는 `navigator.credentials` addInitScript stub(MSW 결정적 패턴, CDP 가상 authenticator 미사용). **구조** — `api/webauthn.ts`(register/list/delete + authenticate/verify 오케스트레이션) · `WebauthnSection.tsx`(설정 독립 섹션) · `LoginForm` 보안 키 버튼 · MSW `webauthn-handlers.ts`. **클레임-read 게이트(FR-MF-04 연동)** — 보안 키 등록 성공 후 `mfaEnrollmentRequired` 강제 대상이면 `refreshSession`으로 클레임 재계산(미적용 시 등록하고도 영구 락). **eng-review 적발 BLOCKER 5** — finish 빈 201(parse 금지)·start JSON 문자열→res.json()·verify `credentials:'include'` 필수·credential 객체 단일 직렬화·취소 시 챌린지 토큰 보존(authenticate/start 미소비). PRE_EXISTING typecheck 부채(#128이 누락한 whoami 인라인 픽스처 2건 `mfaEnrollmentRequired`) 본 PR에서 동반 수정.

### §3.4 FR-MF-04 — 강제 정책 (관리자 + 민감 프로젝트)

**우선순위**. 필수 | **선행**. §3.1, §3.2 | **Plan slug**. `identity/mfa-enforce`

- [x] D1. 도메인 — MfaEnforcementPolicy 평가 서비스 (책임. security-engineer) (PR #123)
- [x] D2. 명세 — 관리자 + 민감 프로젝트 멤버 강제, whoami `mfaEnrollmentRequired` 노출 (책임. security-engineer) (PR #123)
- [x] D3. 데이터 모델 — `projects.require_2fa BOOLEAN DEFAULT false` (issue-tracking V020) + `SensitiveProjectResolver` shared-kernel 포트 (책임. db-engineer) (PR #123)
- [x] D4. 백엔드 — whoami 필드 + 백엔드 게이트(미등록 강제 대상 차단, enrollment/whoami/logout allow-list) (책임. security-engineer) (PR #123)
- [x] D5. 백엔드 테스트 — `MfaEnforcementEndToEndTest` 통합 + 단위 (책임. security-engineer) (PR #123)
- [x] D6. 프론트 UI — MFA 미등록 강제 게이트(기존 `/settings/mfa` 리다이렉트 + 안내 배너, Option A2) (책임. frontend-engineer) (PR #128)
- [x] D7. E2E (책임. qa-engineer) (PR #128)

> **FR-MF-04 백엔드 D1~D5 완료 (2026-06-12, PR #123)**. MFA 강제 정책 평가 + JWT 클레임 + 게이트 + whoami + issue-tracking `require_2fa` 컬럼/토글/resolver. **핵심 결정**(ADR `docs/decisions/2026-06-12-mfa-enforcement-policy.md`) — `require_2fa`는 issue-tracking `projects`에, shared-kernel `SensitiveProjectResolver` 포트로 cross-BC 평가(BC 격리 유지). `MfaEnforcementPolicy`(관리자 역할 OR 민감 프로젝트 멤버) + whoami `mfaEnrollmentRequired` 필드 + 백엔드 게이트(미등록 강제 대상은 enrollment/whoami/logout 외 차단). `@ConditionalOnMissingBean` fallback(`NonProdSensitiveProjectResolver`, `anyRequiresMfa`=false) — identity-access 단독 부팅 가용성 확보, prod fallback 제거 후 WARN 로그로 misassembled 관측. **deviation** — 원안 `mfa_policies(scope, required)` 별도 테이블 대신 `projects.require_2fa` 컬럼(자연스러운 프로젝트 설정 위치, ADR D2).

> **FR-MF-04 D6/D7 완료 (2026-06-12, PR #128)**. 프론트 게이팅 UI + E2E. whoami `mfaEnrollmentRequired` 소비 — `requireMfaEnrolled` 라우트 가드(`requirePasswordChanged` 동형)가 강제대상 미등록을 `/settings/mfa`로 리다이렉트(공유 가드 const + admin 3라우트 합성). **deviation(원안 "step-up 페이지" → Option A2)** — 별도 페이지 신설 대신 기존 MFA 설정 페이지 재사용 + 강제 안내 배너(신규 라우트 0, Maxi 확정). **정합성 핵심** — whoami/게이트가 JWT 클레임을 읽기만 하므로 등록 후 토큰 refresh + whoami 재조회(`refreshSession`)로 클레임 재계산해야 게이트가 풀림(미적용 시 store stale로 영구 락). 백엔드 변경 0(계약 소비). E2E 4 시나리오(강제 리다이렉트/게이트 고정/등록 후 해제/비강제 회귀) + MSW refresh 핸들러 신설. 검증 — typecheck/lint/단위 2399/E2E 4신규+기존 회귀 0.

### §3.5 FR-MF-05 — 신뢰 디바이스 (30일 면제)

**우선순위**. 높음 | **선행**. §3.1 | **Plan slug**. `identity/trusted-devices`

- [x] D1. 도메인 — TrustedDevice (책임. security-engineer) — PR #131
- [x] D2. 명세 — 사용자 동의 + 30일 TTL + 취소 (책임. security-engineer) — PR #131
- [x] D3. 데이터 모델 — `trusted_devices(token_hash, expires_at)` (책임. db-engineer) — PR #131 (V026. 컬럼명 일탈 `device_fingerprint`→`token_hash`: 기존 `Session.deviceFingerprint`(약한 UA+IP 핑거프린트)와 혼동 회피. init_codegen 미러 불요 — jdbc-only)
- [x] D4. 백엔드 — fingerprint 발급 + MFA 우회 검증 (책임. security-engineer) — PR #131
- [x] D5. 백엔드 테스트 — TTL 만료 + 명시적 취소 (책임. security-engineer) — PR #131
- [x] D6. 프론트 UI — "이 디바이스 신뢰" 체크박스 + 디바이스 관리 페이지 (책임. designer → frontend-engineer) — PR #134
- [x] D7. E2E (책임. qa-engineer) — PR #134

> **FR-MF-05 백엔드 D1~D5 완료 (2026-06-13, PR #131)**. 신뢰 디바이스 30일 MFA 면제. **식별 = 서버 불투명 토큰**(MFA verify 성공 + `trust_device=true` 동의 시 32바이트 난수 hex → `Set-Cookie trusted_device` HttpOnly·Secure·SameSite=Strict·Path=/api/v1/auth·30일, DB `trusted_devices.token_hash`=SHA-256만 저장·평문 비영속). 다음 로그인 시 `completeLogin`이 쿠키를 `verifyAndTouch`(user-bound + 미만료 + 갱신 1행)로 검증해 통과하면 챌린지 생략·`issueTokens(mfaVerified=true)`, 아니면 기존 `mfa_required` 폴백(fail-safe). **클라이언트 핑거프린트 기각**(위조/충돌/프라이버시). **취소** = 수동(`GET`/`DELETE {id}`(IDOR 404)/`DELETE` 전체, JWT 전용·PAT 403) + 자동(비밀번호 변경·TOTP 비활성 시 `revokeAll`). 고정 30일(sliding 아님). 감사 `TRUSTED_DEVICE_ADDED`/`REVOKED`(AuthEventType 20→22, 카운트가드 전수 갱신). **우회 경계 = password(local/LDAP) 로그인 전용**(SSO success handler 비대상 — IdP MFA 관할), 단건 세션 종료(`revokeSession`)도 비트리거(세션≠신뢰, SDD '전체 만료'는 명시 전체취소+비번변경 자동폐기로 충족). JWT 클레임 미도입(`mfa_verified` 재사용). **검증** — identity-access 모듈 test+ktlint+detekt+detektTest 전부 `--rerun-tasks` 그린, prod Testcontainers 통합 9 시나리오(S1~S7·EC2·PAT) ground-truth. code-review BLOCKER 0, 적대적 패스 P2(verifyAndTouch TOCTOU→updateLastUsedAt 0행 false)·P3(User-Agent 라벨 256자 cap) 동반 수정. P0 부팅결함(TrustedDeviceService Clock 기본값 누락→full-context 부팅 차단) 통합테스트가 적발·수정. ADR [2026-06-13-trusted-device-mfa-exemption](../../decisions/2026-06-13-trusted-device-mfa-exemption.md). **D6 프론트/D7 E2E는 후속 PR**(FR-MF-01~04 동일 분할).

> **FR-MF-05 D6 프론트/D7 E2E 완료 (2026-06-13, PR #134)**. 신뢰 디바이스 프론트 UI + E2E. 설정 `/settings/mfa`에 신뢰 디바이스 섹션(목록·단건/전체 취소·빈 상태, `WebauthnSection` 동형 **항상 노출**) + 로그인 MFA verify "이 디바이스 신뢰(30일)" **공통 체크박스**(TOTP·백업코드·보안키 **모든 수단**, 부모 `LoginMfaStep` 보관으로 mode 토글 시 보존). `verifyMfa`/`verifyWebauthn` `trustDevice` 인자(하위호환·기본 false), `api/trusted-devices.ts`(sessions.ts 동형 목록/취소·X-XSRF-TOKEN·JWT 전용), MSW cross-handler(verify 신뢰→다음 login `mfa_required` 생략 재현). **eng-review BLOCKER 2 사전적발** — 보안키 verify body는 오케스트레이터(`authenticateWithSecurityKey`)가 아니라 `verifyWebauthn`에 있어 거기 trustDevice 미배선 시 미전송 가짜그린(실경로 body capture 테스트로 차단)·verify raw fetch refreshCallCount=0(auth-pre-session-401-raw-fetch). **E2E가 MSW fixture drift 적발**(expiresAt 누락→Zod parse 실패)→옵션 B(api 타입 import로 drift 원천 차단)+cross-handler 단위 보강. code-review CONCERN 2(중복 테스트 파일 제거·i18n `issueDetailStrings`→`mfaStrings` 통일) 머지 전 정리. 검증 typecheck 0·lint clean·vitest 2613·E2E S1~S6 6/6+회귀 27. **→ FR-MF-05 전체 종료, identity-access(계정 권한) BC 완료.** FR 122/122 무변경(D6/D7 완료가 카운트 불변).

## §4 권한 관리 (FR-PM, 10개)

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

**우선순위**. 필수 | **선행**. §4.2 + §4.8(FR-PM-08 전역 admin 인프라) | **Plan slug**. `fr-pm-04-workflow-automation` | **PR**. #73

- [x] D1. 도메인 (책임. security-engineer)
- [x] D2. 명세 (책임. security-engineer)
- [x] D3. 데이터 모델 — V013 `MANAGE_WORKFLOW` 시드(PROJECT_ADMIN) (책임. security-engineer)
- [x] D4. 백엔드 — 포트 shared-kernel 이동(actor→UUID) + `IdentityAccessWorkflowSchemePermissionResolver`(@Profile prod) (책임. security-engineer)
- [x] D5. 백엔드 테스트 — prod 프로파일 Testcontainers S1~S6(허용/거부) + 예외 403 + 부팅 빈 해소 (책임. security-engineer)
> **D6·D7 (프론트 UI / E2E) — 범위 외**. 스킴 관리 UI 부재(FR-PM-08과 동일), UI 없어 백엔드 prod 통합테스트가 ground-truth. 후속 스킴관리 UI FR 소관. (체크박스 D-task 아님 — 의도적 미수행이라 완료 카운트에서 제외)

> **FR-PM-04 완료 (2026-06-05, PR #73)**. 워크플로우 스킴 권한 prod 결선. **범위**: `MANAGE_SCHEME`/Global=`SystemPermissionResolver.isSystemAdmin`(FR-PM-08 소비), `ASSIGN_SCHEME`/Project=멤버십+`role_permissions` `MANAGE_WORKFLOW` 매트릭스(FR-PM-03 동형). 권한 계약(포트+enum+scope+예외) project-workflow→shared-kernel 이동, `actor: ActorId`→`actorId: UUID`. Guard 예외(`WorkflowSchemeAccessDeniedException`)가 BC 가로질러 project-workflow 핸들러에서 403 매핑. **자동화 권한(`MANAGE_AUTOMATION`)은 automation BC 부재로 범위 제외**(ADR D1, dead 시드 회피). **선행 부채(C2) — ✅ 해소(PR #82, 2026-06-05)**: 스킴 컨트롤러 7곳의 하드코딩 system actor를 `CurrentActor`(SecurityContext 인증 주체→`ActorId`, 미인증/익명/비-UUID→401)로 결선. 결정 A(프레임워크 중립, `Jwt` 미사용, 신규 의존성 0). 단 project-workflow에 prod 앱/BC 배포 조립 부재라 **코드 부채 해소 + test-assembled 검증**까지(prod 활성화는 BC 조립 후). ADR `2026-06-05-workflow-scheme-controller-actor-wiring`. 검증 — 4모듈 test+ktlint+detekt 그린, 회귀 0. ADR `2026-06-04-workflow-scheme-permission-prod-resolver`(spec 단계 D3/D4 확정 닫음).

### §4.5 FR-PM-05 — 이슈 접근 (Browse, View)

**우선순위**. 필수 | **선행**. §4.2 | **Plan slug**. `fr-pm-05-browse-view` | **PR**. #85

- [x] D1. 도메인 — BROWSE/VIEW 분리 (SDD 12.3 BROWSE_PROJECT/VIEW_ISSUE) (책임. security-engineer) (PR #85)
- [x] D2. 명세 (책임. security-engineer) (PR #85)
- [x] D3. 데이터 모델 — V014 BROWSE_PROJECT/VIEW_ISSUE 시드(기본 스킴 PROJECT_ADMIN·MEMBER) (책임. db-engineer) (PR #85)
- [x] D4. 백엔드 — `IssuePermission.BROWSE` + prod resolver 매트릭스 이관(toCodeOrNull) + 단건 VIEW 404 가드(`assertViewIssueOrNotFound`) + listIssues BROWSE (책임. security-engineer) (PR #85)
- [x] D5. 백엔드 테스트 — prod 통합테스트 허용/거부(S1~S4) + 서비스 단위 404 매핑 (책임. security-engineer) (PR #85)
- [x] D6. 프론트 UI — 권한 없는 이슈 404 not-found(기존 catch-all 충족, 회귀 가드 + MSW 404 시나리오) (책임. frontend-engineer) (PR #85)
- [x] D7. E2E — 권한 없는 이슈 진입 not-found(2 시나리오, 기존 issue E2E 회귀 0) (책임. qa-engineer) (PR #85)

> **FR-PM-05 완료 (2026-06-05, PR #85)**. 이슈 접근 권한 BROWSE/VIEW 분리. **범위**: `IssuePermission.VIEW` 단일 → `BROWSE`(목록=`BROWSE_PROJECT`) + `VIEW`(단건=`VIEW_ISSUE`) 분리(SDD 12.3 정본). prod `IdentityAccessIssuePermissionResolver`의 "프로젝트 멤버면 통과" 임시 정책 → `role_permissions` 매트릭스 판정으로 이관(`toCodeOrNull`에 BROWSE_PROJECT/VIEW_ISSUE 매핑, KDoc 임시 정책 메모 제거). 단건 VIEW 미인가 시 **404 존재 숨김**(`assertViewIssueOrNotFound` 헬퍼 — findByKey/availableTransitions/cloneIssue 소스 3경로 일관, exportPdf 전파). 목록은 BROWSE 미인가 403 유지. V014 시드(+4행, `PermissionSchemaMigrationTest` 8→12). **범위 밖**: per-issue 보안 수준(비공개 이슈 차등)은 FR-PM-06 위임(SDD 12.4), 컨트롤러 actor 결선(`SYSTEM_ACTOR_UUID` 하드코딩)은 issue-tracking BC 전체 후속(FR-PM-04 C2 동형). 검증 — 백엔드 3모듈 test+ktlint+detekt 그린, 프론트 1216 test/typecheck/lint clean, prod Testcontainers 통합이 ground-truth(non-prod AlwaysAllow 마스킹). ADR [2026-06-05-issue-browse-view-permission](../../decisions/2026-06-05-issue-browse-view-permission.md).

### §4.6 FR-PM-06 — 이슈 보안 수준

**우선순위**. 필수 | **선행**. §4.5 · §4.8(SYSTEM_ADMIN) · §4.9(사용자 그룹) | **Plan slug**. `identity/issue-security-level`

**Jira식 스킴 구조 채택**(ADR [2026-06-06-issue-security-level-scheme-model](../../decisions/2026-06-06-issue-security-level-scheme-model.md)). 스킴→등급→멤버(5타입) + 프로젝트 적용 + SET_ISSUE_SECURITY. 관리자 우회 없음. **2 PR 분할** — PR-A(identity-access 관리 인프라) / PR-B(issue-tracking 컬럼·지정·판정 결선).

- [x] D1. 도메인 — IssueSecurityScheme/Level/SecurityLevelMember(다형 5타입) (책임. security-engineer) (PR-A #86)
- [x] D2. 명세 — 스킴→등급→멤버, 멤버 5타입, 관리자 우회 없음 (책임. security-engineer) (PR-A #86)
- [x] D3. 데이터 모델 — V016 `issue_security_schemes`/`issue_security_levels`/`issue_security_level_members`/`project_issue_security_schemes` + SET_ISSUE_SECURITY 시드 (책임. db-engineer) (PR-A #86). `issues.security_level_id`는 PR-B.
- [x] D4. 백엔드 — 관리 인프라(스킴/등급/멤버 CRUD + 프로젝트 적용 + SYSTEM_ADMIN/PROJECT_ADMIN 가드) PR-A. 판정 결선(resolver VIEW 게이트 + IssueSecurityLookup + IssueSecurityDirectory + 지정 API + SET_ISSUE_SECURITY 가드 + 422 + 목록 필터) PR-B #90 (책임. security-engineer)
- [x] D5. 백엔드 테스트 — 관리 prod 통합 PR-A. 판정 prod Testcontainers(S2 비멤버 404·S7 관리자 우회없음 ground-truth) PR-B #90 (책임. security-engineer)
- [x] D6. 프론트 UI — 이슈 생성/편집 시 등급 선택(IssueSecurityLevelSelect + 등급 목록 조회 API) PR-B #90 (책임. frontend-engineer)
- [x] D7. E2E — 생성/편집/해제 시나리오 PR-B #90 (책임. qa-engineer)

> **FR-PM-06 완료 (2026-06-06, PR #90, PR-B)**. 이슈 보안 수준 판정 결선. **판정**: `VIEW_ISSUE` 매트릭스 통과 AND (등급 NULL OR 등급 멤버 5타입 충족), 미통과 단건 404·목록 SQL 제외. **관리자 우회 없음**(resolver isSystemAdmin 미호출, S7 prod 통합 isFalse 실측). cross-BC 포트 2종 — `IssueSecurityLookup`(identity-access 내부 raw SQL read of issues, ProjectDirectory 동형) + `IssueSecurityDirectory`(shared-kernel 포트, prod 구현+non-prod stub, 목록 필터·422 검증). `issues.security_level_id`(V014, init_codegen 미러). 등급 지정 = SET_ISSUE_SECURITY 가드 + 적용 스킴 미소속 422 + Jira식 JsonNullable 3-state(부재 무변경/명시null 해제/값 지정). 목록 = SQL 술어 푸시다운(count·content 동일 WHERE, 페이지네이션 정합, N+1 0). `IssueController` actor 결선(고정 SYSTEM_ACTOR→인증주체, FR-PM-04 C2 해소, 미인증 401). 사용자용 등급 목록 조회 `GET /api/v1/projects/{key}/issue-security-scheme/levels`. 검증 — 백엔드 3모듈 test+ktlint+detekt 그린, 프론트 1291 test/typecheck/lint/build + E2E 3 시나리오 그린, prod Testcontainers 거부 ground-truth(non-prod AlwaysAllow 마스킹). code-reviewer BLOCKER 1(미인증 401→500 변질, @ExceptionHandler(ResponseStatusException) 추가로 해소)+CONCERN 4 처리. FR 카운트 불변(121). ADR [2026-06-06-issue-security-level-scheme-model](../../decisions/2026-06-06-issue-security-level-scheme-model.md).

### §4.7 FR-PM-07 — 필드 수준 권한

**우선순위**. 높음 | **선행**. §4.5 · §4.9(사용자 그룹) · FR-IS-10(커스텀 필드) | **Plan slug**. `identity/field-permissions`

**그룹 기반 프로젝트별 규칙 채택**(ADR [2026-06-08-field-level-permissions](../../decisions/2026-06-08-field-level-permissions.md)). 필드 × 사용자 그룹 × 접근수준(VIEW/EDIT) 규칙 + opt-in 제한 + 관리자 우회 없음. **2 PR 분할** — PR-A(백엔드 전체 D1~D5) / PR-B(프론트·E2E D6~D7).

- [x] D1. 도메인 — `FieldPermission`(프로젝트×필드×그룹×VIEW/EDIT) + `FieldAccessLevel` + shared-kernel `FieldKind`/`FieldRef` (책임. security-engineer) (PR-A #97)
- [x] D2. 명세 — 필드 × 사용자 그룹 매트릭스(역할 축=그룹, FR-PM-09), 열람/편집, 관리자 우회 없음 (책임. security-engineer) (PR-A #97)
- [x] D3. 데이터 모델 — `field_permissions(project_id, field_kind, field_key, group_id, access_level)` V018 + `MANAGE_FIELD_PERMISSIONS` 시드 (책임. db-engineer) (PR-A #97)
- [x] D4. 백엔드 — shared-kernel `FieldPermissionResolver` 포트 + prod resolver(관리자 우회 없음) + 규칙 CRUD API + 응답 열람 마스킹(`restrictedFields`) + 편집 EDIT 게이트 (책임. security-engineer + backend-engineer) (PR-A #97)
- [x] D5. 백엔드 테스트 — prod 통합(그룹 멤버십 유일 통과·관리자 비멤버 isEmpty ground-truth) + 마스킹/편집 게이트 (책임. security-engineer) (PR-A #97)
- [x] D6. 프론트 UI — 숨김 필드 렌더 차단 + 규칙 관리 화면 (책임. frontend-engineer) — PR-B #100
- [x] D7. E2E (책임. qa-engineer) — PR-B #100

> **FR-PM-07 PR-B 완료 (2026-06-09, PR #100)**. 필드 수준 권한 프론트 + E2E. **이슈 화면** — `restrictedFields`(열람 불가) 필드 렌더 차단·`noneditableFields`(편집 불가) 입력 컨트롤 비활성(IssueMetaPanel 코어/커스텀 + IssueDescription 본문, 기존 useIssuePermissions UPDATE와 AND). **규칙 관리 화면** — `/projects/{key}/settings/field-permissions`(커스텀 필드 #98 패턴 복제, FieldPermissionList/FormDialog/Row), 규칙=필드(CORE 화이트리스트/CUSTOM)×그룹×VIEW/EDIT, 그룹 드롭다운(useGroups), MANAGE_FIELD_PERMISSIONS 게이팅. **백엔드 결선** — IssueResponse `noneditableFields`(visible−editable−restricted) + MyProjectPermissionController MANAGE_FIELD_PERMISSIONS 노출 + UserGroupController `listGroups` 인증 사용자 읽기 완화(write·listMembers는 SYSTEM_ADMIN 유지). **편집 불가 미리 비활성**(Maxi 결정, §S8 충족). 검증 — 백엔드 2모듈 test+ktlint+detekt 그린, 프론트 typecheck/lint/test 1599/build + E2E 9 시나리오 그린, 기존 E2E 회귀 0. stale base(FR-AU-05 미포함)는 rebase로 해소. **FR-PM-07 완전 종료**(PR-A 백엔드 #97 + PR-B 프론트 #100, D1~D7).

> **FR-PM-07 PR-A 완료 (2026-06-08, PR #97)**. 필드 수준 권한 백엔드. **그룹 기반 프로젝트별 규칙**(Maxi 도메인 결정) — `field_permissions(project_id, field_kind CORE/CUSTOM, field_key, group_id→user_groups CASCADE, access_level VIEW/EDIT)` V018 + `MANAGE_FIELD_PERMISSIONS`(PROJECT_ADMIN 시드, PermissionSchemaMigrationTest 14→15). shared-kernel `FieldPermissionResolver` 포트(visibleFields/editableFields) + prod `IdentityAccessFieldPermissionResolver`(actor 그룹 멤버십 ∩ 규칙, **관리자 우회 없음**=isSystemAdmin/role 미참조, prod 통합테스트 isEmpty 실증) + non-prod AlwaysAllow stub. **opt-in 제한**(규칙 0건 필드는 자유, 1건+면 지정 그룹만). **EDIT⊃VIEW**. 규칙 CRUD API 3종(MANAGE_FIELD_PERMISSIONS 이중가드, CORE 화이트리스트 422, securityLevelId 등 제외). 시행=issue-tracking — 이슈 응답 열람 마스킹(커스텀 키 제거·nullable 코어 null·non-null summary/priority는 편집만·`restrictedFields` 응답, 단건/목록 배치 N+1 회피) + 편집 EDIT 게이트(no-op 통과·변경 거부 403). **fail-open 수정**(code-review): IssueApplicationService resolver를 nullable `?: return`→securityDirectory 선례대로 non-null allow-all 기본값, 보안 코드 항상 실행. 검증 — 3모듈 test+ktlint+detekt 그린(--rerun-tasks), 회귀 0. ADR [2026-06-08-field-level-permissions](../../decisions/2026-06-08-field-level-permissions.md). **범위 밖**: 프론트 UI/E2E(D6/D7)는 PR-B.

### §4.8 FR-PM-08 — 전역 시스템 관리자 역할/권한 인프라

**우선순위**. 필수 | **선행**. §4.1 | **후행 해소**. §4.4 FR-PM-04 · FR-AU-05 회원가입 | **Plan slug**. `identity/system-admin-role` | **PR**. #75

전역(시스템) 역할이 데이터·JWT·판정 어디에도 없어 FR-PM-04(전역 워크플로우/자동화 관리)와 FR-AU-05(회원가입)가 막혀 있다. 이 인프라가 공통 선행을 해소한다. **인프라만**(Maxi 2026-06-04) — 실제 관리 엔드포인트/UI는 후행 FR 소관이라 D6/D7(프론트/E2E) 없음. ADR [2026-06-04-system-admin-role](../../decisions/2026-06-04-system-admin-role.md).

- [x] D1. 도메인 — `SystemRole`(SYSTEM_ADMIN 단일), `ProjectRole`과 분리, 전역 판정기 포트 (책임. security-engineer) (PR #75)
- [x] D2. 명세 (책임. security-engineer) (PR #75)
- [x] D3. 데이터 모델 — `system_role_assignments` 테이블 (V012, project_id 없음) (책임. db-engineer) (PR #75)
- [x] D4. 백엔드 — Repository + 전역 판정기(shared-kernel 포트 + identity 구현) + `JwtIssuer` 전역 역할 클레임 + 설정값 기반 멱등 부트스트랩 `ApplicationRunner` (책임. security-engineer) (PR #75)
- [x] D5. 백엔드 테스트 — 통합테스트(@ActiveProfiles prod): SYSTEM_ADMIN→전역 판정 true, 일반 사용자→false, 부트스트랩 멱등성 (책임. security-engineer) (PR #75)

> **FR-PM-08 완료 (2026-06-04, PR #75)**. 전역 시스템 관리자 인프라 — `system_role_assignments`(V012, 머지 시 main FR-AU-03/04 V010/V011과 충돌해 V010→V012 재배정) + `SystemRole`(SYSTEM_ADMIN 단일, ProjectRole과 별개 축) + Repository(멱등 ON CONFLICT) + `SystemPermissionResolver`(shared-kernel 포트, FR-PM-04 소비) + `JwtIssuer` roles 클레임 + `ROLE_SYSTEM_ADMIN` authority(converter) + 설정값 기반 멱등 부트스트랩 `ApplicationRunner`. 전역 판정기는 DB 조회·프로파일 무관 단일 빈(ADR D4 정정, AlwaysAllow stub 없음). PAT 전역역할 제외(EC7), IssueScope.Global 실결선은 FR-PM-04 후속. 검증 — 전체 691 test + prod 통합테스트 S3/S4 그린, 회귀 0.

### §4.9 FR-PM-09 — 사용자 그룹 (전역 그룹 인프라)

**우선순위**. 필수 | **선행**. §4.8 FR-PM-08(SYSTEM_ADMIN) | **Plan slug**. `identity/user-groups`
**범위**. 백엔드 인프라만(D1~D5). 관리 UI/E2E(D6/D7)·LDAP 그룹 동기화는 후속 FR.

전역 사용자 그룹 + 멤버십 인프라. 보안 수준(FR-PM-06)·권한 스킴·멘션이 소비할 "그룹" 단위를 제공한다. BTS에 그룹 개념이 전무(`user_external_accounts.groups` LDAP 문자열 목록만 존재, 로컬 가입 사용자는 그룹 0)해 FR-PM-06(이슈 보안 수준, 그룹 기반 멤버)이 막혀 있으며, 이 인프라가 그 선행을 해소한다. SDD [12.6.1 사용자 그룹](../../sdd/12-permissions.md).

- [x] D1. 도메인 — `UserGroup`(전역, name 유니크) + `GroupMembership`(group×user N:M) (책임. security-engineer) (PR #88)
- [x] D2. 명세 — 그룹 CRUD + 멤버 추가/제거, `SYSTEM_ADMIN` 관리, 전역 name 유니크, 중복 멤버 멱등 (책임. security-engineer) (PR #88)
- [x] D3. 데이터 모델 — `user_groups`, `group_memberships` (V015) (책임. db-engineer) (PR #88)
- [x] D4. 백엔드 — Repository + 그룹/멤버십 관리 API + `SYSTEM_ADMIN` 가드 (책임. security-engineer) (PR #88)
- [x] D5. 백엔드 테스트 — 통합테스트(@ActiveProfiles prod): SYSTEM_ADMIN 그룹 CRUD 허용·일반 사용자 거부, 멤버십 멱등 (책임. security-engineer) (PR #88)

> **FR-PM-09 완료 (2026-06-05, PR #88)**. 전역 사용자 그룹 인프라 — `user_groups`(V015, name 전역 UNIQUE) + `group_memberships`(복합 PK, 두 FK ON DELETE CASCADE) + 불변 도메인 `UserGroup.create`(identity-access 첫 도메인 팩토리, trim/길이 require) + `UserGroupRepository`(raw SQL NamedParameterJdbcTemplate, memberCount 스칼라 서브쿼리, addMember/removeMember 멱등=ON CONFLICT DO NOTHING·RETURNING 회피) + `UserGroupService`(@Transactional, 도메인 우회 금지) + 관리 API 8종(`/api/v1/groups`). **권한 = 기존 `SystemPermissionResolver.isSystemAdmin` 재사용(신규 포트/권한코드 0, role_permissions 시드 무변경 → PermissionSchemaMigrationTest 카운트 비영향)**. 이중 가드(@PreAuthorize isAuthenticated + 핸들러 내 DB isSystemAdmin, @PreAuthorize hasRole 비사용=claim stale/PAT 일관). 미인가 403/미인증 401, prod 통합테스트가 거부 ground-truth(non-prod 마스킹 없음). 검증 — 모듈 전체 test(83 신규)+ktlint+detekt --rerun-tasks 그린, 회귀 0. ADR [2026-06-05-user-groups](../../decisions/2026-06-05-user-groups.md). **범위 밖**: 관리 UI/E2E(D6/D7) 후속, LDAP 그룹 동기화(SDD 19.8.1) 별도 FR, 소비처 결선(FR-PM-06 보안수준 멤버·권한스킴 grants·멘션)은 각 소비 FR. FR-PM-06(이슈 보안 수준)은 이 인프라 위에서 재개 가능.

### §4.10 FR-PM-10 — 전역 권한 부여 (`global_permission_grants`)

**우선순위**. 필수 | **선행**. §4.8 FR-PM-08(SYSTEM_ADMIN) · §4.9 FR-PM-09(사용자 그룹) | **Plan slug**. `identity/global-permission-grants` | **PR**. #277 (PR-1, D1~D5) · #284 (D6/D7)
**범위**. 백엔드 인프라(D1~D5, #277) + 관리 화면·E2E(D6/D7, #284). **전 단계 완료.**

전역 권한 부여 인프라 — `global_permission_grants`가 SYSTEM_ADMIN 이 아닌 사용자/그룹에 개별 전역 권한(`CREATE_PROJECT`)을 부여한다. issue-tracking BC 의 `POST /projects`(FR-PJ-01)가 이 인프라를 소비한다. FR-PM-08 의 SYSTEM_ADMIN 단일 전역 축을 grant 테이블로 확장. ADR [2026-07-17-global-permission-grants](../../decisions/2026-07-17-global-permission-grants.md).

- [x] D1. 도메인 — `GlobalPermissionGrant`(권한코드 × grantee 다형: GROUP/USER), `SystemPermissionResolver.hasGlobalPermission` default 메서드(= `isSystemAdmin`, 확장 지점 — shared-kernel, ADR D-3) (책임. security-engineer)
- [x] D2. 명세 — grant/revoke/list API 계약, 판정식(grant OR isSystemAdmin, ADR D-2), `CREATE_PROJECT` 권한코드 DB CHECK + 서비스 400 이중 검증(D17) (책임. security-engineer)
- [x] D3. 데이터 모델 — `global_permission_grants`(V036, `permission` CHECK IN ('CREATE_PROJECT') · `grantee_type` CHECK GROUP/USER · UNIQUE(permission, grantee_type, grantee_id) · FK 없음(ADR D-4, 다형 참조) · `granted_by` NOT NULL(ADR D-5, 감사 흔적)) (책임. db-engineer)
- [x] D4. 백엔드 — `GlobalPermissionGrantRepository`(concrete `@Repository`, GROUP 전파 조회) + `IdentityAccessSystemPermissionResolver.hasGlobalPermission` override(grant OR isSystemAdmin) + `GlobalPermissionGrantController`/`Service`(grant/revoke/list, PAT 지원 — D18, sealed 예외 4종) + `ProjectMembershipWritePort`(shared-kernel 포트, FR-PJ-01 소비 예정) (책임. security-engineer)
- [x] D5. 백엔드 테스트 — 스키마 가드 3건 · Repository 8건(GROUP 전파/그룹탈퇴·중복부여 409) · resolver override 4건(mutation 실증) · 컨트롤러 13건+서비스 10건(PAT 양성 포함) · 조립 가드 2건(:modules:app) — 신규 34건 전량 PASS (책임. security-engineer)
- [x] D6. 프론트 UI — 전역 권한 부여/회수 관리 화면 (`/admin/global-permissions`, SYSTEM_ADMIN 가드, 부여 폼·목록·인라인 회수·orphan 표시) (책임. frontend-engineer) — #284
- [x] D7. E2E — Playwright S1~S5(목록·그룹부여·사용자검색부여·회수·중복409) (책임. qa-engineer) — #284

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
- [ ] §4 (FR-PM 10개) 모두 `[x]` 마킹
- [ ] §NFR 측정표 모든 항목 임계 통과
- [ ] OWASP Top 10 자가 점검 (`docs/adr/<date>-identity-owasp-audit.md`)
- [ ] CHANGELOG.md 정리 (BC 단위 변경 요약)
- [ ] README.md §7 변경 이력에 "identity-access BC 완료 — YYYY-MM-DD" 추가
- [ ] Maxi 1인 선언 — "identity-access BC 완료"
