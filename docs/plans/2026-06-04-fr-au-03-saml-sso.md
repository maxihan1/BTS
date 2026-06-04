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
| **C. 이동 없이 재사용(권장)** | SAML이 `provider.ldap.AutoProvisionService` 그대로 import | 회귀위험 0. 단 SAML→ldap 패키지 의존(의미 약간 부자연). KDoc로 "LDAP 전용 아님, 외부 프로비저닝 공통" 명시 |

**→ 권장 C** (17파일 이동 회귀 > 청결성 이득). Maxi가 A 선택 시 별도 선행 refactor 커밋 + LDAP 회귀 전수 통과 후 진행. **게이트1 결정 반영**.

**RED/GREEN/REFACTOR**: 옵션 C면 코드 변경 0(KDoc만) → 기존 LDAP 테스트가 회귀 가드. 옵션 A면 이동 후 `:modules:identity-access:test` 전체 그린이 검증.

**검증**: `./gradlew :modules:identity-access:test`

### Task 2. SAML 의존성 + saml_idp_configs 마이그레이션

**메타**.
- agent: `db-engineer` (스키마) + `security-engineer` (의존성)
- files: [`backend/modules/identity-access/build.gradle.kts`, `gradle/libs.versions.toml`, `backend/modules/identity-access/src/main/resources/db/migration/V010__saml_idp_configs.sql`, `.../test/.../SamlIdpConfigsSchemaTest.kt`]
- depends-on: []

**RED**: V010 적용 후 `saml_idp_configs` 테이블 존재 + 컬럼/UNIQUE(registration_id) 단언하는 schema 테스트(Testcontainers).
**GREEN**: V010 마이그레이션(spec §5 컬럼) + libs.versions.toml에 spring-security-saml2 버전 + build.gradle.kts 의존성.
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
- files: [`.../provider/saml/SamlProvider.kt`, `.../provider/saml/Saml2AuthenticationSuccessHandler.kt`, `.../test/.../SamlProviderUnitTest.kt`, `.../test/.../Saml2SuccessHandlerTest.kt`]
- depends-on: [1, 3]

**RED**: `Saml2Authentication` → `Principal`(NameID=externalSubject) 변환 + AutoProvisionService 호출 + RelayState 화이트리스트(open-redirect 차단 N2) 단위 테스트(mock).
**GREEN**: 성공 핸들러 — Principal 변환 → AutoProvision(JIT) → 기존 세션/JWT 발급 재사용 → RelayState 복귀. SamlProvider는 SPI 일관성 표현(spec §6b).
**REFACTOR**: RelayState 검증 로직 분리 + Clock 주입(replay 시각, 메모리 `authcontroller-revokesession-timebomb`).
**검증**: `./gradlew :modules:identity-access:test --tests *SamlProvider* --tests *Saml2Success*`

### Task 5. SecurityFilterChain SAML2 wiring + 엔드포인트

**메타**.
- agent: `security-engineer`
- files: [`.../config/SecurityConfig.kt`(또는 SAML 전용 config), `.../test/.../SamlSecurityConfigTest.kt`]
- depends-on: [4]

**RED**: `/login/saml2/sso/{registrationId}`(ACS) + `/sso/saml2/authenticate/{registrationId}` 라우트가 SAML2 필터에 연결되고 성공 핸들러가 wiring됐는지 슬라이스 테스트.
**GREEN**: `saml2Login {}` DSL + DbRelyingPartyRegistrationRepository 빈 + 성공 핸들러 연결. **#75(system-admin-role)와 SecurityFilterChain 충돌 주의** — 머지 순서 확인.
**REFACTOR**: 경로 상수화.
**주의**: `@Profile` 한정 빈 부팅 실패 회피(메모리 `profile-scoped-bean-boot-failure`) — non-prod 통합테스트 컨텍스트 부팅 가드.
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
**주의**: Keycloak SAML 컨테이너 직접 선례 없음 — 인프라 신규. 의존 클래스가 stale RUNNING/orphan 안 남게.
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

## 리뷰 결과 (← /bts-review-plan 채움)
