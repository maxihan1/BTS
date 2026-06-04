<!-- ADR — FR-AU-03 SAML 2.0 SSO Provider 도입 + SAML 전용 SecurityFilterChain 분리 결정 -->

# ADR: SAML 2.0 SSO Provider 도입 + SAML 전용 SecurityFilterChain 분리

> 결정일. 2026-06-04
> 상태. Accepted
> 컨텍스트. identity-access BC §2.3 FR-AU-03 (slug `fr-au-03-saml-sso`, PR #76)
> 선행. ADR `2026-05-20-authentication-provider-spi-naming`(SPI 어댑터 패턴)

## 컨텍스트

FR-AU-03은 SAML 2.0 SSO를 identity-access BC에 추가한다. FR-AU-01의 SPI 패턴 위에 SAML을 얹되,
Spring Security SAML2(`spring-security-saml2-service-provider`)가 인증 흐름을 프레임워크 차원에서 주도한다.
plan-review(code-reviewer ground-truth)에서 다음 사실이 확인됐다.

1. 현재 `SecurityConfig`는 **단일 `SecurityFilterChain` + `SessionCreationPolicy.STATELESS`**(`SecurityConfig.kt:91`),
   주석이 "단일 chain 통합, Order 분리 폐기"를 명시(`:32-33`).
2. `Credential.SamlAssertion`은 SPI ADR의 *설계 예시*였을 뿐 실제 `Credential.kt`에 미존재(`ProviderType.SAML`만 실재).
3. 프로젝트에 `gradle/libs.versions.toml` 없음(버전은 build.gradle.kts 인라인).
4. `saml_idp_configs`와 `authn_providers` 연결 컬럼 부재 → JIT 프로비저닝 FK 위반 잠재.

## 결정

### D1. SAML 전용 SecurityFilterChain @Order 분리 (단일 체인 정책 폐기)

Spring Security SAML2의 SP-initiated 흐름은 AuthnRequest 상관관계(inResponseTo/replay 방어)를 HttpSession에 저장한다.
전역 STATELESS와 충돌하므로, **SAML 경로(`/sso/saml2/**`, `/login/saml2/**`)에 한정한 별도 `SecurityFilterChain`을
`@Order`로 분리**하고 `SessionCreationPolicy.IF_REQUIRED`를 허용한다. 나머지 모든 경로(`/api/**` 등)는 기존 STATELESS 유지.

→ ADR `2026-05-20-...`(또는 SecurityConfig 주석)의 "단일 체인 통합" 정책을 **이 ADR이 갱신**한다. 단일 체인은
JWT 전용이던 시점의 결정이었고, SAML 도입으로 세션 기반 외부 인증 왕복이 필요해졌다.

### D2. 공유 프로비저닝 자산 — 이동 없이 재사용 (옵션 C)

`AutoProvisionService`/`ExternalAccountRepository`(현 `provider/ldap/`)를 SAML이 그대로 import해 재사용한다.
참조처가 main 5 + test 17개로 이동 회귀 위험이 크고(ArchUnit jOOQ 룰은 이 BC에 없어 위치 제약 없음), 로직은 외부 IdP
프로비저닝 공통이다. KDoc에 "LDAP 전용 아님, 외부 IdP 프로비저닝 공통"을 명시한다. `LdapProvisionAttrs` VO 의존은
의미 오염이나 비용 대비 수용(완전 중립화는 후속).

### D3. saml_idp_configs ↔ authn_providers 연결

JIT는 `AutoProvisionService.provision(providerId: authn_providers.id, ...)`를 호출한다. 따라서
`saml_idp_configs.authn_provider_id uuid NOT NULL REFERENCES authn_providers(id)`를 두고, V010 마이그레이션이
SAML `authn_providers` seed row를 함께 INSERT한다. 성공 핸들러는 `Saml2Authentication.registrationId` →
`saml_idp_configs` → `authn_provider_id`로 해소한다.

### D4. SamlProvider 역할 (dead-path 방지)

`SamlProvider`는 `ProviderType.SAML` 등록/메타 노출 목적의 thin 구현으로 두고, **인증 검증은 Spring SAML2 필터 +
`Saml2AuthenticationSuccessHandler`(자작)가 수행**한다. 도메인 SPI `authenticate()`를 SAML에 강제하지 않는다
(호출 진입점이 없어 dead-path가 되므로). `Credential.SamlAssertion`은 SPI 표현 일관성을 위한 최소 형태로 Task 4에서 신규 추가.

## 결과

- 외부 의존성 1개 추가(`spring-security-saml2-service-provider`, Spring Boot 3.3.5 BOM 관리).
- SecurityFilterChain 2개(SAML IF_REQUIRED @Order 우선 + 기존 STATELESS).
- 신규 테이블 `saml_idp_configs`(authn_provider_id FK 포함). users/user_external_accounts 재사용.
- JIT 자동 프로비저닝 LDAP 패턴 재사용.

## 보안 (DEVELOPMENT.md §1)

- 서명 검증 필수(N1), open-redirect 차단(RelayState 화이트리스트 N2), replay 방지(Clock 주입 N3), XXE 차단(N6, 자체 XML 파싱 금지), PII 미로깅(N4).
- ACS는 IdP가 POST → CSRF skip 목록에 `/login/saml2/sso/**` 추가.

## 관련

- 마스터플랜 §2.3 (`docs/plan/product/identity-access.md`)
- spec `docs/specs/2026-06-04-fr-au-03-saml-sso.md`
- plan `docs/plans/2026-06-04-fr-au-03-saml-sso.md`
- SDD 19장(인증)
