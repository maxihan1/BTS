<!-- FR-AU-04 OIDC SSO 기술 스펙 -->

# FR-AU-04 — OIDC SSO 스펙

> slug: fr-au-04-oidc-sso | type: auth | BC: identity-access | 작성: 2026-06-04
> 선행: FR-AU-01(SPI, 완료) / FR-AU-03(SAML SSO, PR #76 — SSO 전용 체인·JIT 재사용 선례) / 도메인 정리: plan `## 도메인 정리`
> ADR: `docs/decisions/2026-06-04-oidc-sso-provider.md` (D1~D5 확정)

## 0. 배경 / 범위

OIDC(OpenID Connect) 기반 SSO(Single Sign-On)를 identity-access BC에 추가한다. 외부 IdP(Keycloak/Google/Microsoft Entra 등)의
Authorization Code + PKCE 흐름으로 로그인을 받아, ID Token을 검증하고 BTS 자체 세션/JWT를 발급한다. FR-AU-01에서 확립된
SPI(`AuthenticationProvider` + Spring 어댑터) 패턴 위에 OIDC 구현체를 끼우되, **인증 흐름은 Spring Security OAuth2 Client
(`spring-boot-starter-oauth2-client`)가 프레임워크 차원에서 주도**한다(SAML 동형). `ProviderType.OIDC(50)`은 이미 enum에 실재한다.

**범위 포함**: Authorization Code + PKCE(SP-initiated) 흐름, `oidc_provider_configs` 데이터 모델(client_secret 암호화),
표준 `/oauth2/authorization/{registrationId}`(진입) + `/login/oauth2/code/{registrationId}`(redirect URI) 엔드포인트(Spring 기본 경로),
JIT 자동 프로비저닝(`AutoProvisionService` 재사용), OIDC IdP 선택 프론트 UI, Keycloak OIDC Testcontainers 통합 테스트.

**범위 제외**: `spring-boot-starter-oauth2-resource-server`(ADR D3 — BTS 자체 JWT 발급, 외부 access token 미소비), client_secret
셀프서비스 관리 UI(FR-AU-06 다중 Provider 관리로 이연), 다중 IdP 우선순위/도메인 라우팅(FR-AU-06/07), RP-initiated Logout(후속),
계정 통합(FR-AU-08), 토큰 갱신을 통한 외부 세션 추적(BTS는 로그인 시점 1회 검증 후 자체 세션 발급).

> **OIDC는 SP-initiated가 표준**: SAML의 IdP-initiated(Unsolicited) 같은 별도 흐름 분리 이슈가 없다. Authorization Code 흐름은
> 항상 RP(=BTS, SP)가 시작한다. 따라서 SAML §S3(IdP-initiated 후속 분리) 같은 축소 결정이 불필요하다.

## 1. 사용자 시나리오 (Given-When-Then)

### S1. Authorization Code + PKCE 로그인 (정상)
- **Given** 관리자가 `oidc_provider_configs`에 외부 IdP를 enabled로 등록했고(issuer/client_id/암호화된 client_secret), 사용자는 로그인 화면에 있다
- **When** 사용자가 IdP 선택 화면에서 "OIDC SSO" 버튼을 클릭한다
- **Then** BTS가 `/oauth2/authorization/{registrationId}` 진입 시 `state`+PKCE `code_challenge`+`nonce`를 만들어 IdP authorization
  endpoint로 302 리다이렉트하고, IdP 인증 성공 후 BTS redirect URI `/login/oauth2/code/{registrationId}`로 code가 돌아오며,
  BTS가 token endpoint에 code+`code_verifier`로 토큰 교환 후 ID Token 서명(JWKS)·`nonce` 검증 통과 시 BTS 세션(JWT)이 발급된다
  (통합테스트가 실 Keycloak으로 302+authorization endpoint 리다이렉트 검증)

### S2. JIT 자동 프로비저닝 (첫 SSO 로그인)
- **Given** IdP는 사용자를 알지만 BTS `user_external_accounts`에는 매핑이 없다
- **When** S1 흐름으로 ID Token이 검증 통과한다
- **Then** `AutoProvisionService`가 `users` + `user_external_accounts`(provider_id, external_subject=ID Token `sub`)를
  단일 트랜잭션으로 UPSERT하고 세션을 발급한다 (LDAP/SAML과 동일 멱등 UPSERT)

### S3. ID Token 검증 실패 / 만료
- **Given** ID Token 서명이 IdP JWKS와 불일치하거나 `exp`가 지났거나 `nonce`/`aud`가 불일치한다
- **When** redirect URI 콜백에서 토큰 교환·검증이 일어난다
- **Then** 인증 거부(세션 미발급)하고, 인증 실패 audit 이벤트를 emit한다(FR-AU-10 연동 지점, PII 미로깅)

### S4. state 불일치 (CSRF) / code 가로채기 (PKCE)
- **Given** 콜백의 `state`가 세션 저장값과 다르거나, code가 다른 클라이언트에서 가로채졌다
- **When** redirect URI 콜백을 받는다
- **Then** Spring OAuth2 필터가 `state` 불일치를 거부(CSRF 방어)하고, PKCE `code_verifier` 미보유 클라이언트의 토큰 교환을 IdP가 거부한다

### S5. IdP 미설정 / 비활성
- **Given** `oidc_provider_configs`에 enabled 레코드가 없다
- **When** 사용자가 IdP 선택 화면을 연다
- **Then** OIDC 버튼이 노출되지 않는다 (활성 Provider만 렌더 — LDAP/Local/SAML 선례)

## 2. 기능 요구사항 (FR)

| ID | 요구사항 |
|---|---|
| F1 | `OidcProvider`(`provider/oidc/`)는 `ProviderType.OIDC` 등록/메타 노출 목적. **인증 검증은 Spring OAuth2 필터+성공 핸들러가 수행**(SAML C4 동형 — SPI `authenticate()` dead-path, `supports()` 항상 false). `Credential.OidcToken`은 SPI 일관성용 최소 표현으로 신규 추가 검토 |
| F2 | Spring Security `spring-boot-starter-oauth2-client`로 Authorization Code + PKCE 흐름. token 교환·ID Token 검증은 프레임워크 위임 |
| F3 | DB 기반 `ClientRegistrationRepository` 구현 — `oidc_provider_configs`에서 enabled 행을 읽어 `ClientRegistration` 생성(SAML `RelyingPartyRegistrationRepository` 어댑터 동형). issuer-uri discovery(`.well-known/openid-configuration`)로 엔드포인트 해소 |
| F4 | `oidc_provider_configs`는 **본 FR 범위에서 read + seed만**(관리 UI는 FR-AU-06으로 이연). enabled IdP 목록 조회 API |
| F5 | JIT 자동 프로비저닝 — 인증 성공 시 `AutoProvisionService.provision` 재사용(users + user_external_accounts UPSERT, 이동 없이 import — SAML ADR D2 선례). claims는 `LdapProvisionAttrs` VO로 매핑(SAML 동형, VO 의미오염 수용) |
| F6 | ID Token `sub` → `user_external_accounts.external_subject` 매핑. claims(email/name/preferred_username)로 users 갱신 |
| F7 | 프론트 — IdP 선택 화면에 활성 OIDC IdP 버튼 동적 렌더 + 진입점(`/oauth2/authorization/{registrationId}`). SAML `SamlIdpButtons` 동형 |
| F8 | client_secret 암호화 — 저장 시 app key로 암호화, `ClientRegistration` 생성 시 복호화(ADR D4) |

## 3. 비기능 요구사항 (NFR)

| ID | 요구사항 |
|---|---|
| N1 | **ID Token 서명 검증 필수** — JWKS 기반(프레임워크 위임). 미검증 토큰으로 세션 발급 금지(DEVELOPMENT.md §1 보안) |
| N2 | **PKCE 필수** — Authorization Code 가로채기 방어. Spring oauth2Client `code_challenge_method=S256` |
| N3 | **state/nonce 검증** — `state`로 CSRF 방어, `nonce`로 ID Token replay 방어. HttpSession 저장(IF_REQUIRED 체인) |
| N4 | **client_secret 비밀 보호** — 평문 저장 금지. app key 대칭 암호화(ADR D4), 복호화 키 환경변수 주입. PII/secret 미로깅(providerId/registrationId만) |
| N5 | **open-redirect 차단** — 로그인 후 복귀 경로는 상대 경로 화이트리스트만 허용(SAML RelayState 동형) |
| N6 | 로그인 응답 p95 800ms 이내(외부 IdP 라운드트립 포함, identity-access NFR 표) |

## 4. API 인터페이스 (REST)

Spring Security OAuth2 Client 필터가 표준 경로를 제공한다. BTS 추가분만 명시.

| 메서드 | 경로 | 설명 |
|---|---|---|
| GET | `/oauth2/authorization/{registrationId}` | SP-initiated 시작 (Spring 제공). IdP authorization endpoint로 리다이렉트 |
| GET | `/login/oauth2/code/{registrationId}` | redirect URI — code 수신 (Spring 기본 경로. BTS 성공 핸들러로 JWT 발급) |
| GET | `/api/v1/auth/oidc/providers` | 활성 OIDC IdP 목록(registrationId, displayName). 프론트 버튼 렌더용 |

> 핵심은 콜백 성공 후 BTS JWT 발급 핸들러로 연결되는 것(기존 세션 발급 로직 재사용, SAML 성공 핸들러 동형).

## 5. 데이터 모델 변경

### 신규 테이블 `oidc_provider_configs`
```
oidc_provider_configs(
  id                     uuid PK,
  registration_id        text UNIQUE NOT NULL,   -- Spring Security ClientRegistration 식별자 (URL 경로)
  display_name           text NOT NULL,          -- 프론트 버튼 라벨 ("OIDC SSO")
  issuer_uri             text NOT NULL,          -- IdP issuer (.well-known discovery 기준)
  client_id             text NOT NULL,          -- OAuth2 client_id
  client_secret_encrypted text NOT NULL,         -- app key 암호화된 client_secret (ADR D4, 평문 금지)
  scopes                 text NOT NULL DEFAULT 'openid,profile,email',  -- 요청 scope (쉼표 구분)
  authn_provider_id      uuid NOT NULL REFERENCES authn_providers(id),  -- JIT providerId
  enabled                boolean NOT NULL DEFAULT true,
  created_at             timestamptz NOT NULL DEFAULT now(),
  updated_at             timestamptz NOT NULL DEFAULT now()
)
```
- **OIDC authn_providers seed**: 고정 UUID, type='OIDC', name='OIDC SSO' row를 같은 마이그레이션에서 INSERT(FK 위반 방지, SAML V010 선례)
- **마이그레이션**: identity-access 모듈 다음 V번호(최신 V010 확인 → V011 예상). plain JDBC repository면 jOOQ 코드젠 비대상(`init_codegen.sql` 미러 불요) — plan에서 SAML 선례로 확정
- **권한 시드 영향**: 없음 → `PermissionSchemaMigrationTest` 카운트 영향 없음(메모리 `fr-pm-permission-seed-migration-test-coupling` 회피)
- **재사용**: `users`, `user_external_accounts`(provider_id, external_subject) 스키마 변경 0

## 6. SPI ↔ Spring OAuth2 필터 역할 경계

LDAP은 도메인 SPI가 인증을 주도했지만, OIDC는 Spring OAuth2 Client 필터가 code 교환·ID Token 검증을 **프레임워크가 주도**한다.

- **Spring OAuth2 필터 책임**: authorization 리다이렉트(state/PKCE/nonce 생성), code 콜백 수신, token 교환, ID Token 서명/exp/aud/nonce 검증, `OAuth2AuthenticationToken`(OidcUser) 생성
- **BTS 성공 핸들러(`OidcAuthenticationSuccessHandler` 어댑터) 책임**: `OidcUser` → 도메인 `Principal` 변환 → `AutoProvisionService` 호출(JIT) → 기존 BTS 세션/JWT 발급 로직 재사용 → 복귀 경로. **세션/JWT 발급은 `SessionService`/`RefreshTokenRepository`/`JwtIssuer` 직접 호출 + Clock 주입**(SAML `Saml2AuthenticationSuccessHandler` 동형 — AuthController.login 토큰 발급 로직이 컨트롤러 인라인이라 공용 추출 안 됨, 핸들러가 rawToken/sha256 유틸 자체 보유). refresh raw는 Cookie에만, DB는 SHA-256만(메모리 `authcontroller-revokesession-timebomb` Clock 주입)
- **DB 기반 `ClientRegistrationRepository`**: `oidc_provider_configs`(enabled) → `ClientRegistration`(issuer discovery + 복호화된 client_secret). Spring 기본 `InMemoryClientRegistrationRepository`가 아닌 DB 동적 구현(SAML DB 기반 RelyingPartyRegistrationRepository 동형). discovery 호출 비용 고려 → lazy 생성 + 캐시는 plan에서 확정
- **`OidcProvider`(SPI)**: `ProviderType.OIDC` 등록/메타 노출 thin 구현. 실제 검증은 Spring 필터 — 도메인이 raw 토큰을 직접 검증하지 않음(N1 일관)

## 6b. client_secret 암호화 인프라 (★ 신규 도입)

기존 BC에 암호화 유틸 부재 확인됨(도메인 단계 grep). 본 FR이 처음 도입한다.

- **암호화**: Spring Security Crypto `AesBytesEncryptor`(또는 `Encryptors.stronger`) — AES-256-GCM 계열. app key + salt 환경변수 주입
- **키 부재 시**: 부팅 시 OIDC 협력자 빈 구성 단계에서 fail-fast(키 없으면 명확한 메시지). 단 OIDC 미설정(`oidc_provider_configs` 없음) 환경에서는 암호화 빈이 불필요하므로 `@ConditionalOnProperty`/조건부 구성으로 non-OIDC 부팅 보호(메모리 `profile-scoped-bean-boot-failure`)
- **복호화 시점**: `ClientRegistrationRepository`가 `ClientRegistration` 생성 시 1회. 복호화 결과는 메모리에만, 로깅 금지(N4)

## 6c. 첫 IdP 등록 경로

F4가 관리 UI를 FR-AU-06으로 이연하므로, 본 FR에서 IdP가 시스템에 들어오는 경로를 명시한다(SAML §6c 동형).

- **dev/test**: 통합 테스트 setup에서 Keycloak OIDC 클라이언트(client_id/secret/issuer)를 `oidc_provider_configs`에 INSERT(secret은 암호화)
- **prod**: 본 FR은 **DB 직접 INSERT(운영 절차, secret 암호화 도구 제공)** + enabled 토글까지만. 셀프서비스 관리 UI는 FR-AU-06
- 이 경계를 plan §리스크에 명시해 reviewer가 "관리 UI 누락"을 BLOCKER로 오인하지 않게 함

## 7. 엣지 케이스

- EC1. 복귀 경로 없음/화이트리스트 밖 → 기본 랜딩(`/dashboard`), open-redirect 차단(N5)
- EC2. 같은 `sub` 동시 첫 로그인 race → `ON CONFLICT (provider_id, external_subject)` 멱등(LDAP/SAML 선례)
- EC3. IdP key 롤오버 → JWKS 동적 조회(프레임워크가 kid로 해소). `oidc_provider_configs`는 issuer만 저장, 인증서 직접 저장 안 함(SAML과 차이 — OIDC는 JWKS endpoint 동적)
- EC4. ID Token에 email claim 없음 → preferred_username/sub로 fallback, users.email nullable 정책 확인
- EC5. 비활성(enabled=false) IdP의 registrationId로 직접 접근 → `ClientRegistrationRepository`가 미해소 → 거부
- EC6. issuer discovery(`.well-known`) 호출 실패(IdP 다운) → 명확한 인증 실패, 부팅은 막지 않음(lazy 해소)

## 8. 제약 조건

- C1. **외부 의존성 신규** — `spring-boot-starter-oauth2-client`(전이 nimbus-oauth2-sdk). DEVELOPMENT.md §외부 의존성 → **Maxi 확인 대상**(게이트 1 승인). resource-server는 ADR D3로 제외
- C2. **BC 격리** — 한 PR = identity-access only
- C3. **병행 충돌 선점**(plan 단계 grep 필수):
  - SAML이 추가한 SecurityFilterChain(@Order(1)) 옆에 OIDC 체인 공존 — `securityMatcher` 배타 경로(`/oauth2/**`, `/login/oauth2/**`) 확인
  - `apps/web` router/handlers/IdpButtons 영역은 SAML 산출물 위에 추가(SAML `SamlIdpButtons` 옆 `OidcIdpButtons` 또는 통합)
  - 활성 PR #73/#75/#79와 SecurityFilterChain/router 겹침 머지 순서 확인
- C4. **Keycloak OIDC Testcontainers** — SAML PR #76이 `KeycloakSamlTestcontainersBase` + `keycloak/saml-test-realm.json`(SAML 전용 realm)을 도입함(grep 확인). OIDC는 **OIDC realm/client(`keycloak/oidc-test-realm.json`) 추가**가 필요(Keycloak 컨테이너 이미지는 재사용, base 클래스는 OIDC용 신규 또는 일반화). Testcontainers 라이프사이클은 singleton pattern 준수(메모리 `Testcontainers 클래스 라이프사이클`)
- C5. **세션 정책** — 전역 STATELESS와 oauth2Login(state/nonce/PKCE HttpSession) 충돌 → ADR D1 결정대로 OIDC 전용 @Order 체인 IF_REQUIRED 분리(SAML 동형, 메모리 `saml-spring-security-integration`)
- C6. 외부 의존성은 `identity-access/build.gradle.kts` 인라인 추가(프로젝트에 libs.versions.toml 없음, SAML C6 선례)
- C7. **암호화 키 관리** — app encryption key 환경변수 신규(`infra` dev/prod 설정). 키 없으면 OIDC 구성만 fail, non-OIDC 부팅 보호(§6b)

## 9. 측정 가능한 완료 기준

- [ ] Authorization Code 진입 통합 테스트: `/oauth2/authorization/{registrationId}` → 실 Keycloak authorization endpoint로 302 + state/PKCE
- [ ] 전체 로그인 통합 테스트: code 콜백 → token 교환 → ID Token 검증 → BTS 세션 발급 (Testcontainers Keycloak)
- [ ] ID Token 검증 실패(서명/exp/nonce/aud) 거부, 세션 미발급
- [ ] JIT 프로비저닝: 첫 SSO 로그인 시 users + user_external_accounts 생성, 2회차 멱등
- [ ] client_secret 암호화 저장 + 복호화 round-trip 단위 테스트, 로그에 secret 미노출
- [ ] 활성 IdP 목록 API + 프론트 동적 버튼 렌더 + E2E
- [ ] open-redirect 복귀 경로 화이트리스트 단위 테스트
- [ ] detekt/ktlint/ArchUnit 그린 + identity-access 모듈 전체 test 그린

## Brainstorming Check

✅ 통과 (1회 iteration, SAML PR #76 산출물 ground-truth 점검). 발견 gap 3건 모두 SAML 선례로 자명 → 스펙 직접 반영(Maxi 결정 불요).
- G1. Keycloak Testcontainers는 SAML realm 전용(`KeycloakSamlTestcontainersBase`/`saml-test-realm.json`) → OIDC realm/client 추가 필요(C4 보강)
- G2. JIT는 `LdapProvisionAttrs` VO 재사용, claims → attrs 매핑(F5 보강)
- G3. 성공 핸들러는 세션/JWT 발급 로직 자체 보유(AuthController 미추출, Clock 주입) — SAML 핸들러 동형(§6 보강)
