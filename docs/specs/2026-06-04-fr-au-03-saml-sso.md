<!-- FR-AU-03 SAML 2.0 SSO 기술 스펙 -->

# FR-AU-03 — SAML 2.0 SSO 스펙

> slug: fr-au-03-saml-sso | type: auth | BC: identity-access | 작성: 2026-06-04
> 선행: FR-AU-01(SPI, 완료) / 도메인 정리: plan `## 도메인 정리`
> ADR(예정): `docs/decisions/2026-06-04-saml-sso-provider.md`

## 0. 배경 / 범위

SAML 2.0 기반 SSO(Single Sign-On)를 identity-access BC에 추가한다. 사내 IdP(Okta/Azure AD/Keycloak 등)가
서명한 SAML Assertion을 검증해 BTS 세션을 발급한다. FR-AU-01에서 확립된 SPI(`AuthenticationProvider` + Spring 어댑터)
패턴 위에 SAML 구현체를 끼우는 작업이다. `ProviderType.SAML(40)`은 이미 enum에 실재하나, **`Credential.SamlAssertion`은 ADR 설계 예시일 뿐 실제 `Credential.kt`에 없음 → Task 4에서 신규 추가**(plan-review C3 교정, 2026-06-04).

**범위 포함**: SP-initiated + IdP-initiated 흐름, `saml_idp_configs` 데이터 모델, `/sso/saml2/**` 엔드포인트,
JIT 자동 프로비저닝(LDAP 패턴 재사용), IdP 선택 프론트 UI, Keycloak SAML Testcontainers 통합 테스트.

**범위 제외**: 다중 IdP 우선순위/도메인 라우팅(FR-AU-06/07), SAML SLO(Single Logout) — 후속 검토, MFA 연동(FR-MF).

## 1. 사용자 시나리오 (Given-When-Then)

### S1. SP-initiated 로그인 (정상)
- **Given** 관리자가 `saml_idp_configs`에 사내 IdP를 enabled로 등록했고, 사용자는 로그인 화면에 있다
- **When** 사용자가 IdP 선택 화면에서 "사내 SSO" 버튼을 클릭한다
- **Then** BTS가 SAML AuthnRequest를 만들어 IdP로 리다이렉트하고, IdP 인증 성공 후 BTS `/sso/saml2/acs`로
  Assertion이 POST되며, 서명 검증 통과 시 BTS 세션(JWT)이 발급되고 RelayState에 담긴 복귀 경로로 이동한다

### S2. JIT 자동 프로비저닝 (첫 SSO 로그인)
- **Given** IdP는 사용자를 알지만 BTS `user_external_accounts`에는 매핑이 없다
- **When** S1 흐름으로 Assertion이 검증 통과한다
- **Then** `AutoProvisionService`가 `users` + `user_external_accounts`(provider_id, external_subject=NameID)를
  단일 트랜잭션으로 UPSERT하고 세션을 발급한다 (LDAP과 동일 멱등 UPSERT)

### S3. IdP-initiated 로그인
- **Given** 사용자가 IdP 포털에서 BTS 앱 타일을 클릭한다 (BTS를 거치지 않고 IdP가 먼저 Assertion 발행)
- **When** IdP가 `/sso/saml2/acs`로 Unsolicited Assertion을 POST한다
- **Then** RelayState가 없거나 신뢰 목록 밖이면 기본 랜딩(`/dashboard`)으로만 보내고(open-redirect 차단),
  Assertion replay(중복 ID/만료 시각)를 검증해 거부한다

### S4. 서명 검증 실패 / 만료 Assertion
- **Given** Assertion 서명이 등록된 IdP 인증서와 불일치하거나 `NotOnOrAfter`가 지났다
- **When** `/sso/saml2/acs`가 Assertion을 받는다
- **Then** 401로 거부하고 세션을 발급하지 않으며, 인증 실패 audit 이벤트를 emit한다(FR-AU-10 연동 지점, PII 미로깅)

### S5. IdP 미설정 / 비활성
- **Given** `saml_idp_configs`에 enabled 레코드가 없다
- **When** 사용자가 IdP 선택 화면을 연다
- **Then** SAML 버튼이 노출되지 않는다 (활성 Provider만 렌더 — LDAP/Local 선례)

## 2. 기능 요구사항 (FR)

| ID | 요구사항 |
|---|---|
| F1 | `SamlProvider`(`provider/saml/`)는 `ProviderType.SAML` 등록/메타 노출 목적. **인증 검증은 Spring SAML2 필터+성공 핸들러가 수행**(C4 — SPI `authenticate()` dead-path 방지). `Credential.SamlAssertion`은 Task 4 신규 추가(C3) |
| F2 | Spring Security `spring-security-saml2-service-provider`로 SP-initiated AuthnRequest 생성 + ACS(Assertion Consumer Service) 처리 |
| F3 | IdP-initiated(Unsolicited) Assertion 수용. replay/만료/audience 검증 |
| F4 | `saml_idp_configs` CRUD는 **본 FR 범위에서 read + seed만**(관리 UI는 FR-AU-06 다중 Provider 관리로 이연). enabled IdP 목록 조회 API |
| F5 | JIT 자동 프로비저닝 — 인증 성공 시 `AutoProvisionService` 재사용(users + user_external_accounts UPSERT) |
| F6 | NameID → `user_external_accounts.external_subject` 매핑. SAML 속성(email/displayName)으로 users 갱신 |
| F7 | 프론트 — IdP 선택 화면에 활성 SAML IdP 버튼 동적 렌더 + SP-initiated 진입점(`/sso/saml2/authenticate/{registrationId}`) |
| F8 | RelayState로 로그인 후 복귀 경로 보존. 신뢰 목록(같은 origin 상대 경로) 밖이면 무시하고 기본 랜딩 |

## 3. 비기능 요구사항 (NFR)

| ID | 요구사항 |
|---|---|
| N1 | **서명 검증 필수** — 서명 없는/불일치 Assertion 거부. IdP 인증서는 `saml_idp_configs`에 저장(DEVELOPMENT.md §1 보안) |
| N2 | **open-redirect 차단** — RelayState는 상대 경로 화이트리스트만 허용 |
| N3 | **replay 방지** — Assertion ID 중복 + `NotOnOrAfter`/`NotBefore` 시각 검증(Clock 주입, 메모리 `authcontroller-revokesession-timebomb`) |
| N4 | **PII 미로깅** — NameID/email 등 로그 직접 출력 금지(providerId/registrationId만). LDAP 선례 |
| N5 | 로그인 응답 p95 800ms 이내(외부 IdP 라운드트립 포함, identity-access NFR 표) |
| N6 | XML 처리 — XXE(외부 엔티티 주입) 차단. spring-security-saml2의 안전한 파서 사용, 자체 XML 파싱 금지 |

## 4. API 인터페이스 (REST)

Spring Security SAML2 필터가 표준 경로를 제공한다. BTS 추가분만 명시.

| 메서드 | 경로 | 설명 |
|---|---|---|
| GET | `/sso/saml2/authenticate/{registrationId}` | SP-initiated 시작 (Spring Security 제공). IdP로 리다이렉트 |
| POST | `/login/saml2/sso/{registrationId}` | ACS — Assertion 수신 (Spring Security 기본 경로. BTS 성공 핸들러로 JWT 발급) |
| GET | `/api/v1/auth/saml/idps` | 활성 SAML IdP 목록(registrationId, displayName). 프론트 버튼 렌더용 |

> 경로 접두사(`/sso/saml2` vs Spring 기본 `/login/saml2`)는 plan 단계에서 SecurityFilterChain 설정과 함께 확정.
> 핵심은 ACS 성공 후 BTS JWT 발급 핸들러로 연결되는 것(기존 세션 발급 로직 재사용).

## 5. 데이터 모델 변경

### 신규 테이블 `saml_idp_configs`
```
saml_idp_configs(
  id              uuid PK,
  registration_id text UNIQUE NOT NULL,   -- Spring Security RelyingPartyRegistration 식별자
  display_name    text NOT NULL,          -- 프론트 버튼 라벨 ("사내 SSO")
  idp_entity_id   text NOT NULL,          -- IdP EntityID
  idp_sso_url     text NOT NULL,          -- IdP SSO 엔드포인트
  idp_x509_cert   text NOT NULL,          -- IdP 서명 검증 인증서 (PEM)
  enabled         boolean NOT NULL DEFAULT true,
  created_at      timestamptz NOT NULL DEFAULT now(),
  updated_at      timestamptz NOT NULL DEFAULT now()
)
```
- **마이그레이션**: identity-access 모듈 다음 V번호(현재 최신 확인 후 부여). `init_codegen.sql` 미러 필요 여부는
  jOOQ 상수 생성 대상일 때만(메모리 `jooq-init-codegen-mirror`) — 단 이 BC repository는 plain JDBC라 jOOQ 코드젠 비대상일 가능성. plan에서 확정
- **권한 시드 영향**: 없음(권한 코드 추가 아님) → `PermissionSchemaMigrationTest` 카운트 영향 없음(메모리 `fr-pm-permission-seed-migration-test-coupling` 회피)
- **재사용**: `users`, `user_external_accounts`(provider_id, external_subject) 스키마 변경 0

## 6. 공유 자산 위치 결정 (★ 반드시 결정)

`AutoProvisionService` / `ExternalAccountRepository`가 현재 `provider/ldap/` 패키지에 있음. SAML도 동일 로직 필요.

| 옵션 | 내용 | trade-off |
|---|---|---|
| **A. 공용 패키지 이동(권장)** | `provider/shared/` 또는 `provisioning/`로 이동 후 LDAP+SAML 공유 | DRY. 단 import 참조처(테스트 @Bean 포함) 전수 grep 필수(메모리 `archunit-shared-class-move-repository-package`). ExternalAccountRepository는 plain JDBC라 jOOQ ArchUnit 룰 직접 대상 아님(@Repository는 유지) |
| B. SAML 전용 복제 | `provider/saml/`에 별도 구현 | 중복. user_external_accounts UPSERT 로직 두 벌 → drift 위험(메모리 `external-account-repository 책임 침범` 재현 우려) |

→ **A 권장**. 단 이동은 별도 선행 커밋(refactor: 공용 이동)으로 분리하고 LDAP 회귀 테스트 통과 확인 후 SAML 추가.

## 6b. SPI ↔ Spring Security SAML2 필터 역할 경계 (★ Brainstorming 발견)

LDAP은 도메인 SPI(`LdapProvider.authenticate`)가 인증을 주도했지만, SAML은 Spring Security SAML2 필터가
Assertion 수신·서명검증·파싱을 **프레임워크가 주도**한다. 따라서 둘의 경계를 명확히 한다.

- **Spring SAML2 필터 책임**: AuthnRequest 생성, ACS 수신, 서명/replay/audience 검증, `Saml2Authentication` 생성
- **BTS 성공 핸들러(`Saml2AuthenticationSuccessHandler` 어댑터) 책임**: `Saml2Authentication` → 도메인 `Principal` 변환
  → `AutoProvisionService` 호출(JIT) → 기존 BTS 세션/JWT 발급 로직 재사용 → RelayState 복귀
- **`SamlProvider`(SPI) 위치**: `RelyingPartyRegistrationRepository`를 `saml_idp_configs` 기반으로 구현하는 어댑터 +
  성공 핸들러가 도메인 진입점. `Credential.SamlAssertion`은 SPI 일관성 유지를 위한 표현이며, 실제 검증은 Spring 필터가 수행
  (도메인이 raw XML을 직접 파싱하지 않음 — NFR N6 XXE 차단 일관)

→ 핵심 설계는 **plan 단계에서 security-engineer가 RelyingPartyRegistrationRepository 어댑터 + 성공 핸들러 구조로 확정**.

## 6c. 첫 IdP 등록 경로 (★ Brainstorming 발견)

F4가 관리 UI를 FR-AU-06으로 이연하므로, 본 FR에서 IdP가 시스템에 들어오는 경로를 명시한다.

- **dev/test**: Flyway seed 마이그레이션 또는 통합 테스트 setup에서 Keycloak SAML 클라이언트 메타데이터를 `saml_idp_configs`에 INSERT
- **prod**: 본 FR은 **DB 직접 INSERT(운영 절차)** + enabled 토글까지만. 셀프서비스 관리 UI는 FR-AU-06.
  운영 절차는 `docs/` 운영 노트에 기록(별도 관리 콘솔 코드 없음)
- 이 경계를 plan §리스크에 명시해 reviewer가 "관리 UI 누락"을 BLOCKER로 오인하지 않게 함

## 7. 엣지 케이스

- EC1. RelayState 없는 IdP-initiated → 기본 랜딩(`/dashboard`), open-redirect 차단
- EC2. 같은 NameID 동시 첫 로그인 race → `ON CONFLICT (provider_id, external_subject)` 멱등(LDAP 선례 EC-11)
- EC3. IdP 인증서 롤오버(만료 직전 교체) → `saml_idp_configs.idp_x509_cert` 갱신으로 대응(본 FR은 단일 인증서, 다중 인증서는 후속)
- EC4. Assertion에 email 속성 없음 → displayName/NameID로 fallback, users.email nullable 정책 확인
- EC5. 비활성(enabled=false) IdP의 registrationId로 직접 접근 → 404/거부

## 8. 제약 조건

- C1. **외부 의존성 신규** — `spring-security-saml2-service-provider`(+ 전이 OpenSAML). DEVELOPMENT.md §외부 의존성 → **Maxi 확인 대상**(게이트 1에서 승인)
- C2. **BC 격리** — 한 PR = identity-access only. 다른 BC 호출 없음
- C3. **병행 충돌 선점**(grep 완료):
  - `apps/web/src/router.ts`(단일 라우트 등록) + `apps/web/src/mocks/handlers.ts`(단일 핸들러 인덱스)는 #74(FR-IS-08 프론트)와 공용. SAML은 `/login`·`/sso` 영역, FR-IS-08은 `/issues/$key` 버튼 → 라인 분리로 실제 충돌 위험 낮음. 머지 시 add/add 충돌 가능성만 인지, rebase로 해소
  - #75(system-admin-role)는 SecurityFilterChain 건드릴 수 있음 → SAML 필터 체인 추가 시 머지 순서 확인
- C4. Keycloak SAML Testcontainers 인프라 신규. **Keycloak은 OIDC 용도로 이미 존재**(`infra/docker-compose.dev.yml`, `realm-bts.json`)하나 **Testcontainers/SAML 모드 선례는 없음**(컨테이너 통합테스트는 OpenLDAP/Postgres뿐). `realm-bts.json`에 SAML 클라이언트 추가 또는 별도 realm + 메타데이터 동적 주입(plan-review C5 교정)
- C5. **세션 정책 BLOCKER** — 현재 SecurityConfig 단일 체인 STATELESS(`SecurityConfig.kt:91`)는 saml2Login SP-initiated 상관관계/replay(N3)와 충돌. 게이트1 D1 결정 필요(별도 @Order 체인 IF_REQUIRED vs Saml2AuthenticationRequestRepository 쿠키/DB)
- C6. 외부 의존성은 `gradle/libs.versions.toml`이 아니라 `identity-access/build.gradle.kts` 인라인 추가(프로젝트에 libs.versions.toml 없음, plan-review C8 교정)

## 9. 측정 가능한 완료 기준

- [ ] SP-initiated E2E: 버튼 클릭 → IdP(Keycloak) → ACS → BTS 세션 발급 통과
- [ ] IdP-initiated 통합 테스트: Unsolicited Assertion 수용 + replay 거부
- [ ] 서명 검증 실패 Assertion 401 거부 (Testcontainers Keycloak)
- [ ] JIT 프로비저닝: 첫 SSO 로그인 시 users + user_external_accounts 생성, 2회차 멱등
- [ ] 활성 IdP 목록 API + 프론트 동적 버튼 렌더 + E2E
- [ ] open-redirect/RelayState 화이트리스트 단위 테스트
- [ ] detekt/ktlint/ArchUnit(공용 이동 시 룰) 그린 + identity-access 모듈 전체 test 그린

## Brainstorming Check

✅ 통과 (1회 iteration). 발견 gap 2건 보강 — §6b(SPI↔Spring SAML2 필터 역할 경계), §6c(첫 IdP 등록 경로). 둘 다 Maxi 결정 불요, 스펙 직접 반영.
