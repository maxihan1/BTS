# FR-AU-03 — SAML 2.0 SSO

> slug: fr-au-03-saml-sso
> type: auth
> agent: security-engineer
> primary_bc: identity-access
> 생성: 2026-06-04

## Brief

FR-AU-03 SAML 2.0 SSO 구현 — identity-access BC.

선행 FR-AU-01(플러그형 AuthenticationProvider 구조, 완료) 위에:
- `spring-security-saml2-service-provider` 기반 SP-initiated + IdP-initiated 흐름
- `saml_idp_configs` 데이터 모델
- `/sso/saml2/...` 엔드포인트
- Keycloak SAML 모드 Testcontainers 통합 테스트
- IdP 선택 프론트 UI (D6) + E2E (D7)

plan 슬롯: `docs/plan/product/identity-access.md §2.3 (D1~D7)`.

**병행 주의** — 같은 identity-access BC인 `system-admin-role`(#75, 전역 권한 인프라)·`fr-pm-04`(#73)와
SAML Provider 등록(`ProviderRegistry`/`authn_providers`)·`SecurityContext`/SecurityFilterChain 충돌 가능성.
또 FR-IS-08 프론트(#74, issue-tracking BC)와 프론트 공유 인프라(라우터/MSW 핸들러 인덱스/api 공통)
충돌 선점 확인 필요. → spec 단계에서 grep 검증.

## 도메인 정리

- **BC**: identity-access
- **영향 엔티티**:
  - 신규 — `SamlIdpConfig` (IdP 메타데이터/EntityID/서명 인증서 영속. `saml_idp_configs` 테이블)
  - 재사용 — `User`, `user_external_accounts`(provider_id, external_subject=NameID), `Principal`, `Credential.SamlAssertion`(이미 SPI에 정의됨), `ProviderType.SAML(40)`(이미 enum에 존재)
- **새 구현체**: `provider/saml/SamlProvider`(도메인 SPI `AuthenticationProvider` 구현) + Spring Security SAML2 어댑터. ADR `2026-05-20-authentication-provider-spi-naming`의 어댑터 격리 패턴 그대로 적용
- **자동 프로비저닝**: **JIT 채택**(Maxi 결정 2026-06-04, LDAP과 동일). SAML 첫 SSO 로그인 시 BTS 계정 자동 생성. 단 `AutoProvisionService`/`ExternalAccountRepository`가 현재 `provider/ldap/` 패키지에 갇혀 있음 → SAML 공유를 위한 **공용 위치 이동 vs SAML 전용 분리는 spec/plan 단계에서 결정**(learnings `archunit-shared-class-move-repository-package` 주의 — jOOQ 접촉 repo는 .repository 패키지 유지)
- **SAML 흐름**: SP-initiated + IdP-initiated 둘 다(Brief). IdP-initiated의 RelayState/replay 보안은 spec에서 명세
- **새 용어**: IdP, SP, SAML Assertion, SAML metadata, RelayState, NameID → glossary.md 등록 완료(Maxi 승인 2026-06-04)
- **기존 결정 충돌**: 없음. SPI ADR이 SAML을 명시적으로 예견(`Credential.SamlAssertion` 선반영)
- **같은 BC 병행 작업 경계**:
  - #75 system-admin-role(전역 권한 인프라) — SecurityContext/SecurityFilterChain 건드릴 가능성 → SAML 엔드포인트(`/sso/saml2`) 필터 체인 추가 시 충돌 주의
  - #73 fr-pm-04(워크플로우/자동화 권한) — 권한 영역, SAML 인증 영역과 분리. 충돌 가능성 낮음
- **관련 ADR**: 선행 `docs/decisions/2026-05-20-authentication-provider-spi-naming.md`(어댑터 패턴), `2026-05-20-ldap-group-mapping-policy.md`, `2026-05-20-ldap-testcontainers-image.md`(Keycloak SAML Testcontainers 선례). SAML 전용 ADR(JIT 정책 + saml_idp_configs 모델 + 공유자산 위치)은 **spec 단계에서 생성 예정**

## 스펙

전체 스펙: [docs/specs/2026-06-04-fr-au-03-saml-sso.md](../specs/2026-06-04-fr-au-03-saml-sso.md)

핵심 요약.
- SP-initiated + IdP-initiated SAML 흐름. Spring Security SAML2 필터가 검증 주도, BTS 성공 핸들러가 Principal 변환 + JIT 프로비저닝 + JWT 발급
- 신규 `saml_idp_configs`(registration_id/entity_id/sso_url/x509_cert/enabled). users/user_external_accounts 재사용
- 공유 자산(AutoProvisionService/ExternalAccountRepository) ldap→공용 패키지 이동 권장(옵션 A), 별도 refactor 커밋 선행
- 외부 의존성 `spring-security-saml2-service-provider`(+OpenSAML) 신규 → 게이트1 Maxi 승인 대상
- 보안: 서명검증/replay/open-redirect(RelayState 화이트리스트)/XXE 차단/PII 미로깅
- 프론트: 활성 IdP 동적 버튼 렌더(`/login`). #74·#75와 공용파일(router.ts/handlers.ts) 충돌 위험 낮음(영역 분리)

## Brainstorming Check

✅ 통과 (1회 iteration). gap 2건 보강 — SPI↔Spring SAML2 필터 역할 경계(§6b), 첫 IdP 등록 경로(§6c). Maxi 결정 불요.

## Plan

> 전체 TDD red→green→refactor. agent 기본값 `security-engineer`(auth BC).
> **단일 Gradle 모듈(identity-access)** — backend test끼리는 컴파일 단위 공유로 wave 병렬 효과 제한(메모리 `bts-plan-wave-gradle-module-compile`). 프론트(apps/web)는 별개 모듈로 백엔드와 병행 가능.

### ★ 게이트1 Maxi 확인 항목 (2건)
1. **외부 의존성** — `spring-security-saml2-service-provider`(+ 전이 OpenSAML) 신규 추가 승인 (DEVELOPMENT.md §외부 의존성)
2. **공유 자산 위치 전략** — 아래 Task 1. 옵션 A(이동, 17참조 수정) vs C(이동 없이 재사용). 권장은 후술

### Task 1. 공유 프로비저닝 자산 위치 전략

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/provider/.../AutoProvisionService.kt`, `.../ExternalAccountRepository.kt`, `.../LdapProvisionAttrs.kt`, + import 참조 17 test 파일(이동 옵션 시)]
- depends-on: []

**배경**. AutoProvisionService/ExternalAccountRepository는 "외부 IdP 자동 프로비저닝" 공통 로직이나 `provider/ldap/` 패키지에 위치. SAML도 동일 로직 필요. 참조처 = main 5 + test 17.

| 옵션 | 내용 | trade-off |
|---|---|---|
| A. `provider/provisioning/` 이동 | 의미 중립 패키지로 이동, LDAP+SAML 공유 | 도메인 청결. import 17참조 수정(기계적, 로직 0). 통합테스트 @Bean 명시 import라 회귀위험 中 |
| **C. 이동 없이 재사용(권장)** | SAML이 `provider.ldap.AutoProvisionService` 그대로 import | 회귀위험 0. 단 SAML→ldap 패키지 의존 + **LDAP 전용 `LdapProvisionAttrs` VO까지 의존(C2 의미 오염)**. KDoc로 "외부 프로비저닝 공통" 명시 |

**→ 권장 C** (17파일 이동 회귀 > 청결성 이득). 단 C2 — 옵션 C도 `provision()`이 받는 `LdapProvisionAttrs`(LDAP 전용 VO)를 SAML이 채워야 함. 완전 청결을 원하면 옵션 A에서 VO명도 중립화(`ExternalProvisionAttrs`). Maxi가 A 선택 시 별도 선행 refactor 커밋 + LDAP 회귀 전수 통과 후 진행. **게이트1 결정 D2 반영** (spec §6 옵션A와의 모순 C1도 여기서 통일).

**RED/GREEN/REFACTOR**: 옵션 C면 코드 변경 0(KDoc만) → 기존 LDAP 테스트가 회귀 가드. 옵션 A면 이동 후 `:modules:identity-access:test` 전체 그린이 검증.

**검증**: `./gradlew :modules:identity-access:test`

### Task 2. SAML 의존성 + saml_idp_configs 마이그레이션

**메타**.
- agent: `db-engineer` (스키마) + `security-engineer` (의존성)
- files: [`backend/modules/identity-access/build.gradle.kts`, `backend/modules/identity-access/src/main/resources/db/migration/V010__saml_idp_configs.sql`, `.../test/.../SamlIdpConfigsSchemaTest.kt`]
- depends-on: []

**RED**: V010 적용 후 `saml_idp_configs` 테이블 존재 + 컬럼/UNIQUE(registration_id) + `authn_provider_id` FK(→authn_providers) 단언하는 schema 테스트(Testcontainers).
**GREEN**: V010 마이그레이션 — spec §5 컬럼 + **`authn_provider_id uuid NOT NULL REFERENCES authn_providers(id)`**(C9 FK 갭 해소) + **SAML `authn_providers` seed row INSERT**(JIT가 providerId 필요). 의존성은 **`identity-access/build.gradle.kts`에 직접 인라인** 추가(C8 — libs.versions.toml 없음): `org.springframework.security:spring-security-saml2-service-provider`(Spring Boot 3.3.5 BOM이 6.3.x 관리, 버전 명시 불요).
**REFACTOR**: 인덱스(enabled 부분) 정리, KDoc.
**주의**: 권한 코드 시드 아님 → `PermissionSchemaMigrationTest` 영향 0. jOOQ 코드젠 비대상이면 `init_codegen.sql` 미러 불요(plain JDBC repo) — 확인 후 결정.
**검증**: `./gradlew :modules:identity-access:test --tests *SamlIdpConfigsSchemaTest`

### Task 3. SamlIdpConfig 도메인 + Repository(read) + RelyingPartyRegistrationRepository 어댑터

**메타**.
- agent: `security-engineer`
- files: [`.../provider/saml/SamlIdpConfig.kt`, `.../provider/saml/SamlIdpConfigRepository.kt`, `.../provider/saml/DbRelyingPartyRegistrationRepository.kt`, `.../test/.../SamlIdpConfigRepositoryTest.kt`, `.../test/.../DbRelyingPartyRegistrationRepositoryTest.kt`]
- depends-on: [2]

**RED**: saml_idp_configs read → `RelyingPartyRegistration` 변환 테스트(enabled만, 비활성 제외 EC5).
**GREEN**: plain JDBC repository(LDAP repo 패턴) + Spring `RelyingPartyRegistrationRepository` 구현 어댑터.
**REFACTOR**: PEM 인증서 파싱 헬퍼 추출.
**검증**: `./gradlew :modules:identity-access:test --tests *SamlIdpConfig* --tests *RelyingParty*`

### Task 4. SamlProvider + Saml2 인증 성공 핸들러 (Principal 변환 + JIT + JWT)

**메타**.
- agent: `security-engineer`
- files: [`.../spi/Credential.kt`(SamlAssertion 추가 — C3), `.../provider/saml/SamlProvider.kt`, `.../provider/saml/Saml2AuthenticationSuccessHandler.kt`, `.../test/.../SamlProviderUnitTest.kt`, `.../test/.../Saml2SuccessHandlerTest.kt`]
- depends-on: [1, 3]

**RED**: `Saml2Authentication` → `Principal`(NameID=externalSubject) 변환 + registrationId→authn_providers.id 매핑(C9) + AutoProvisionService.provision(providerId,...) 호출 + RelayState 화이트리스트(open-redirect 차단 N2) 단위 테스트(mock).
**GREEN**:
- `Credential.kt`에 `data class SamlAssertion(...)` 추가(C3 — 실제 없으므로 신규). 단 **검증은 Spring 필터가 수행**하므로 SamlAssertion은 SPI 표현용 최소 형태
- 성공 핸들러 — Principal 변환 → registrationId로 SAML authn_providers.id 해소 → AutoProvision(JIT) → 기존 세션/JWT 발급 재사용 → RelayState 복귀
- **SamlProvider 역할 재정의(C4)** — `authenticate()` dead-path 방지. SamlProvider는 ProviderType.SAML 등록/메타 노출 목적의 thin 구현(또는 SPI 강제 구현 제거하고 성공 핸들러만). plan-review 반영: SPI `authenticate()` 강제하지 않음
**REFACTOR**: RelayState 검증 로직 분리 + Clock 주입(replay 시각, 메모리 `authcontroller-revokesession-timebomb`).
**검증**: `./gradlew :modules:identity-access:test --tests *SamlProvider* --tests *Saml2Success*`

### Task 5. SecurityFilterChain SAML2 wiring + 엔드포인트

**메타**.
- agent: `security-engineer`
- files: [`.../config/SecurityConfig.kt`(또는 SAML 전용 config), `.../test/.../SamlSecurityConfigTest.kt`]
- depends-on: [4]

**RED**: `/login/saml2/sso/{registrationId}`(ACS) + `/sso/saml2/authenticate/{registrationId}` 라우트가 SAML2 필터에 연결되고 성공 핸들러가 wiring됐는지 슬라이스 테스트 + **SAML 경로 미인증 접근(permitAll) 통과 + 세션 정책 동작** 단언.
**GREEN**:
- **세션 정책(BLOCKER 해소, 게이트1 D1 결정 반영)** — (a) SAML 경로 별도 `@Order` SecurityFilterChain `IF_REQUIRED` 또는 (b) `Saml2AuthenticationRequestRepository` 쿠키/DB 교체
- `saml2Login {}` DSL + DbRelyingPartyRegistrationRepository 빈 + 성공 핸들러 연결
- **permitAll 추가(C6)** — `/sso/saml2/authenticate/**`, `/login/saml2/sso/**`, `/api/v1/auth/saml/idps`(미인증 호출). **ACS는 IdP가 POST → CSRF skip 목록(SecurityConfig.kt:108-116)에 `/login/saml2/sso/**` 추가**
**REFACTOR**: 경로 상수화.
**주의**: `@Profile` 한정 빈 부팅 실패 회피(메모리 `profile-scoped-bean-boot-failure`). #75는 머지 순서만 인지(C7 — 진짜 충돌은 자기 체인 세션 정책).
**검증**: `./gradlew :modules:identity-access:test`

### Task 6. 활성 SAML IdP 목록 API

**메타**.
- agent: `security-engineer`
- files: [`.../web/SamlIdpController.kt`, `.../web/dto/SamlIdpResponse.kt`, `.../test/.../SamlIdpControllerTest.kt`]
- depends-on: [3]

**RED**: `GET /api/v1/auth/saml/idps` → enabled IdP만(registrationId, displayName) 반환, 비활성 제외, 인증서/URL 미노출.
**GREEN**: 컨트롤러 + DTO(민감정보 제외). 미인증 접근 허용(로그인 전 호출).
**REFACTOR**: 응답 DTO ↔ 프론트 Zod 1:1(drift 차단, 메모리 `frontend-zod-backend-dto-contract-gap`).
**검증**: `./gradlew :modules:identity-access:test --tests *SamlIdpController*`

### Task 7. Keycloak SAML Testcontainers 통합 테스트

**메타**.
- agent: `security-engineer` (+ qa-engineer 인프라 검토)
- files: [`.../test/.../integration/SamlAuthFlowIntegrationTest.kt`, `infra/keycloak/realm-bts.json`(SAML 클라이언트 추가) 또는 별도 realm, `.../test/resources/...`]
- depends-on: [5]

**RED→GREEN**: Keycloak 컨테이너 SAML 모드 — SP-initiated(ACS→세션), IdP-initiated(Unsolicited 수용+replay 거부 S3), 서명검증 실패 401(S4), JIT 프로비저닝 멱등(S2/EC2). Testcontainers singleton 패턴(메모리 stale port 회피).
**주의(C5 교정)**: Keycloak은 OIDC 용도로 이미 존재(`infra/docker-compose.dev.yml`, `realm-bts.json`)하나 **Testcontainers/SAML 모드 선례는 없음** — 컨테이너 통합테스트 선례는 OpenLDAP/Postgres뿐. realm에 SAML 클라이언트 추가 + 부팅 후 SAML 메타데이터(EntityID/SSO URL/cert)를 saml_idp_configs에 동적 주입하는 비자명 배선 필요. 단일 모듈 직렬 끝단이라 여기서 막히면 전체 지연 → **qa-engineer 인프라 검토를 게이트1에서 미리 당김**. Testcontainers singleton(stale port 회피) + orphan 방지.
**검증**: `./gradlew :modules:identity-access:test --tests *SamlAuthFlow*`

### Task 8. 프론트 — IdP 선택 동적 버튼 (D6)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/api/saml.ts`, `apps/web/src/auth/LoginForm.tsx`(SAML 버튼 영역), `apps/web/src/mocks/saml-handlers.ts`, `apps/web/src/mocks/handlers.ts`(인덱스 1줄 추가), `apps/web/src/**/*.test.tsx`]
- depends-on: [6]   # API 계약 의존, 단 MSW로 백엔드와 병행 가능

**RED**: `GET /api/v1/auth/saml/idps` mock → 활성 IdP 버튼 동적 렌더(0개면 미노출 S5), 클릭 시 `/sso/saml2/authenticate/{registrationId}` 이동.
**GREEN**: api/saml.ts(같은 BC api 관례 grep — 메모리 `frontend-api-convention-per-bc`) + LoginForm 버튼 + MSW handler(stateful 불요, read-only).
**REFACTOR**: 버튼 컴포넌트 분리.
**주의**: `handlers.ts`/`router.ts`는 #74와 공용 — SAML은 `/login` 영역이라 라인 분리. 머지 시 add/add만 인지.
**검증**: `pnpm --filter @bts/web typecheck && pnpm --filter @bts/web test`

### Task 9. E2E (D7)

**메타**.
- agent: `qa-engineer`
- files: [`apps/web/e2e/login-saml.spec.ts`]
- depends-on: [8]

**RED→GREEN**: MSW 기반 — IdP 버튼 노출/클릭 리다이렉트(S1 진입), IdP 0개 미노출(S5). 실제 IdP 왕복은 백엔드 통합테스트(T7) 위임. 기존 login E2E 회귀 0(컨테이너 한정 셀렉터, 메모리 `playwright-getbyrole-exact-strict-mode`).
**검증**: `pnpm --filter @bts/web test:e2e --grep saml`

## Plan 메타

- task 수: 9
- 예상 wave: backend(T1·T2 → T3 → T4·T6 → T5 → T7)는 단일 모듈이라 대체로 직렬, 프론트(T8·T9)는 T6 계약 후 백엔드와 병행
- TDD 강제: yes
- 병렬 dispatch: bts-impl이 depends-on + files로 wave 계산
- 추가 검증: ktlint/detekt/ArchUnit(이동 옵션 시) + vitest/typecheck + playwright
- 게이트1 Maxi 확인: 외부 의존성 승인 + 공유 자산 위치 전략(권장 C)

## 게이트1 Maxi 결정 (2026-06-04, 승인 → 구현 착수)

- **D1 (BLOCKER 해소)** = **SAML 경로 별도 SecurityFilterChain @Order 분리** + `IF_REQUIRED`. 나머지 API는 STATELESS 유지. → "단일 체인 통합" 정책(`SecurityConfig.kt:32-33`) 폐기 → **ADR 갱신**(`docs/decisions/2026-06-04-saml-sso-provider.md`)
- **D2 (공유자산)** = **옵션 C** — 이동 없이 `provider.ldap.AutoProvisionService`/`ExternalAccountRepository` 재사용. KDoc로 "외부 프로비저닝 공통" 명시. spec §6 옵션A 권장은 C로 통일(C1 해소)
- **D3 (외부 의존성)** = **승인** — `spring-security-saml2-service-provider`(Spring Boot 3.3.5 BOM 관리, 버전 명시 불요)
- **게이트1** = 승인. 9 task TDD 구현 착수

## 리뷰 결과

### 게이트2 PR 코드리뷰 (2026-06-04, code-reviewer) — BLOCKER 2 / CONCERN 3 → 전부 해소

자동 테스트(test/lint/typecheck/E2E)는 그린이었으나 mock 위 false-green. ground-truth 검증으로 진짜 결함 적발.

- **🛑 BLOCKER 1 (경로 불일치)** — 프론트 `/sso/saml2/authenticate/` ↔ Spring 표준 `/saml2/authenticate/`. 미배선 별칭이라 클릭 시 로그인 미시작. → **해소**: 양쪽 표준 통일. fix-gate2/fix-gate2-fe 커밋. `SpInitiatedEntryTest`가 실 Keycloak 302+SAMLRequest 검증.
- **🛑 BLOCKER 2 (IdP-initiated 미구현)** — spec 범위 포함인데 전무. → **해소**: Maxi 결정으로 후속 FR 축소. spec/ADR 반영.
- **⚠️ CONCERN-A (SP signing)** — wantAuthnRequestsSigned 기본 true 충돌. → **해소**: Maxi 결정 `wantAuthnRequestsSigned(false)`.
- **⚠️ CONCERN 2 (RelayState 백슬래시)** — 코드 방어 있으나 테스트 미커버. → **해소**: 테스트 추가.
- **⚠️ CONCERN 3 (ADR XML 문구)** — production 위임/테스트 자체파싱 정합. → **해소**: ADR "production 한정" 명시.
- PASS: SecurityConfig 체인 분리 안전(STATELESS/JWT/CSRF/PAT 무손상), JIT FK 정합, PII 미로깅, open-redirect, XXE, dead-path 방지.

재검증: 백엔드 모듈 전체 test(SAML 통합 포함) + 프론트 1133 + E2E 회귀 0 그린.

### plan-review ground-truth 리뷰 (2026-06-04, 사전) — BLOCKER 1 / CONCERN 9

직접 검증 완료(Credential.kt, SecurityConfig.kt:91, libs.versions.toml find). 모두 사실 확인.

**🛑 BLOCKER 1 — SecurityConfig STATELESS ↔ saml2Login 세션 충돌**
- `SecurityConfig.kt:91`은 단일 `SecurityFilterChain` + `SessionCreationPolicy.STATELESS`. SAML SP-initiated는 AuthnRequest 상관관계(inResponseTo/replay 방어 N3)를 HttpSession에 저장 → STATELESS면 깨지거나 replay 무력화.
- 해소 택1 → **게이트1 Maxi 결정 D1**:
  - (a) SAML 경로(`/sso/saml2/**`,`/login/saml2/**`) 한정 별도 SecurityFilterChain `@Order` 분리 + `IF_REQUIRED`. 단 현재 "단일 체인 통합"(SecurityConfig.kt:32-33) 정책 폐기 → ADR 갱신 필요
  - (b) `Saml2AuthenticationRequestRepository`를 세션 대신 쿠키/DB로 교체, STATELESS 유지

**사실 오류 3건 (교정 완료)**
- C3 — `Credential.SamlAssertion` "이미 정의됨" 오류 → 실제 없음. **Task 4에 `spi/Credential.kt`에 SamlAssertion 추가 포함**으로 교정
- C8 — `gradle/libs.versions.toml` 환각(프로젝트에 없음) → Task 2를 `identity-access/build.gradle.kts` 인라인 의존성 추가로 교정
- C5 — Keycloak "선례 없음" 오류 → OIDC 용도로는 이미 존재(`docker-compose.dev.yml`), 단 Testcontainers/SAML 모드는 신규(리스크 유효)

**모델 갭 (교정 완료)**
- C9 — `saml_idp_configs`↔`authn_providers` 연결 부재 → JIT `AutoProvisionService.provision(providerId=authn_providers.id FK)` FK 위반 잠재. **Task 2에 SAML `authn_providers` seed row + `saml_idp_configs.authn_provider_id` FK 컬럼 추가, Task 4에 registrationId→providerId 매핑 명세** 교정

**방향 교정**
- C4 — SamlProvider SPI `authenticate()` dead-path 위험. F1 수정 → SamlProvider는 ProviderType 등록/메타 노출용, 인증 검증은 Spring 필터+성공 핸들러. SPI 강제 구현 제거
- C7 — "#75 충돌" 경고 방향 오류(진짜는 자기 체인 STATELESS). #75는 머지 순서만 인지

**Maxi 결정 필요 (게이트1)**
- C1 — 공유자산 전략 plan(옵션C)↔spec(옵션A) 모순 → **D2로 통일**
- C2 — 옵션 C 시 SAML이 LDAP 전용 `LdapProvisionAttrs` VO 의존(의미 오염) → D2와 함께 고려

**permitAll/CSRF (Task 5 교정 완료)**
- C6 — SAML ACS/시작/IdP목록 경로 permitAll + ACS는 IdP가 POST하므로 CSRF skip 목록 추가 명시
