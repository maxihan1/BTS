<!-- BTS plan — identity-access FR-AU-01 플러그형 AuthenticationProvider 구조 -->

# FR-AU-01 — 플러그형 AuthenticationProvider 구조

> slug: identity-authn-provider
> type: auth
> agent: security-engineer
> primary_bc: identity-access
> 생성: 2026-05-20
> 마스터플랜: `docs/plan/product/identity-access.md §2.1`

## Brief

identity-access BC 정식 진입의 첫 PR. SDD 04장 (인증/계정) §1 + 마스터플랜 §2.1 기준 — 향후 LDAP/SAML/OIDC/Local 등 모든 Provider가 등록될 **확장 지점**을 먼저 구축.

§1 PoC (PR #2)는 Spring Security + Keycloak 한 묶음으로 통합 검증한 PoC였다. 이 PR은 그 위에 정식 도메인 모델 (`Principal`/`Credential`/`AuthnResult`) + 인터페이스 (`AuthenticationProvider`) + 등록 메커니즘 (`ProviderRegistry`) 을 얹는다. 이후 FR-AU-02~05 (LDAP/SAML/OIDC/Local) 가 모두 이 인터페이스 구현체로 들어온다.

**이 PR 스코프** = §2.1 D1~D5 (백엔드 + 백엔드 테스트) 까지. D6 (UI) + D7 (E2E) 는 별도 PR.

## 도메인 정리

- **BC**. identity-access
- **영향 엔티티**. `AuthenticationProvider` (기존 glossary 등재, 인터페이스 정식화), `ProviderRegistry` (신규), VO 3종 `Principal`/`Credential`/`AuthnResult` (신규)
- **새 용어** (glossary 추가 후보, Maxi 승인 필요).
  - `Principal` — 인증 완료 후의 주체 식별 정보 VO (userId, providerType, displayName, externalSubject)
  - `Credential` — 인증 시도 입력 VO. sealed (UsernamePassword / OidcToken / SamlAssertion / LdapBind / Pat). 메모리에만 존재, DB 저장 안 됨
  - `AuthnResult` — 인증 결과 VO. sealed (Success / Failure / RequiresMfa)
  - `ProviderRegistry` — 도메인 `AuthenticationProvider` Bean 등록소, Spring DI 컨테이너 위 얇은 추상화
- **기존 결정 충돌**.
  - **명명 충돌 위험**. BTS 도메인 `AuthenticationProvider` ≠ Spring Security `org.springframework.security.authentication.AuthenticationProvider`. ADR로 해소 (B안. 도메인 이름 유지 + 패키지 격리 + Spring 어댑터 1개)
  - **`UserCredential` (PoC DB 엔티티)** vs **`Credential` (신규 입력 VO)** — 다른 레이어. 다음 PR로 `UserCredential` 리네임 검토 (본 PR 스코프 외)
- **관련 ADR**. [docs/decisions/2026-05-20-authentication-provider-spi-naming.md](../decisions/2026-05-20-authentication-provider-spi-naming.md) (신규, 본 PR로 생성)
- **선행 PoC ADR** (PR #2, 2026-05-20). argon2id-parameters / csrf-cookie-mode / keycloak-image-selection / testcontainers-docker-desktop-config — 변경 없음, 본 PR 영향 없음
- **grill-with-docs 우회 사유**. FR-AU-01은 인터페이스 + Registry라는 단일 책임. 도메인 모델 면적 작음. 1인 부담 + Auto mode 합리적 판단으로 직접 분석 (PoC 패턴 일관성)

## 스펙

전체 스펙. [docs/specs/2026-05-20-identity-authn-provider.md](../specs/2026-05-20-identity-authn-provider.md)

핵심 3줄.
- **순수 SPI 도입** — `AuthenticationProvider` 인터페이스 + `ProviderRegistry` + VO 3종 (`Principal`/`Credential`/`AuthnResult`) + Spring 어댑터 1개. 외부 REST 변경 0건
- **명명 충돌 해소** — BTS 도메인 SPI vs Spring Security 동명 인터페이스 분리. ArchUnit 룰로 경계 강제
- **CONCERN-NEW-2 처리** — PoC #2의 `issuer-uri` 하드코딩을 `${BTS_KEYCLOAK_ISSUER_URI:...}` 환경변수로 외부화

명시적 비-스코프. LDAP/SAML/OIDC Provider 실제 구현 (FR-AU-02~04 별도 PR), `authn_providers` 테이블 (LDAP PR에서 도입), SecurityFilterChain 통합 (FR-AU-09 PR에서).

## Brainstorming Check

✅ 통과 (1회 iteration, gap 7건 식별 모두 spec 본문에서 닫힘 — iteration 불필요).

### Phase B 직접 수행 — 발견된 gap

1. **`Credential.UsernamePassword.password: CharArray` equals/hashCode 우려** — Argon2.verify로 비교하므로 data class equals 불사용. 정상.
2. **가짜 Provider 등록 프로필** — `@TestConfiguration` + `@Profile("test")` 보다 **`@Profile("test-spi")` 별도 프로필**이 PoC #2 OIDC 통합 테스트와 충돌 방지에 안전. spec FR-1 보강 — plan task에서 명시.
3. **ArchUnit 신규 도입** — `libs.versions.toml` 등록 task 분리. plan T1 (인프라).
4. **`SpringSecurityProviderAdapter` 의 Spring DI 등록 정책** — `@Component` 자동 등록은 SecurityFilterChain 충돌 위험. `@Bean` 명시 등록만 + 본 PR에서는 FilterChain 미등록 (단위 테스트만). spec FR-3 명시.
5. **`MfaChallenge.NOT_IMPLEMENTED_YET`** placeholder enum — 향후 sealed로 변경 시 ABI 깨짐, BTS는 내부 backend라 무관. 정상.
6. **CONCERN-NEW-2 + PoC #2 회귀** — `@DynamicPropertySource` > env > yaml 우선순위로 PoC #2 `KeycloakIntegrationTest` 영향 없음 확인. spec NFR 명시.
7. **`Principal.toString` PII 마스킹** — Kotlin data class toString override. spec §7 §1.2 명시.

### iteration 결정

gap 모두 spec 본문 내에서 닫힘 + plan task 분리로 위임 가능. office-hours/brainstorming 대화형 우회 사유. PoC 패턴 일관성 (1인 부담 + 도메인/스펙 결정이 비교적 명확). `/bts-plan` 진입.

## Plan

직접 분해 (`superpowers:writing-plans` 대화형 우회 — PoC 패턴 일관성 + 1인 부담 + spec 결정 명확). spec `docs/specs/2026-05-20-identity-authn-provider.md` 의 FR-1~FR-5 + §6 엣지케이스 + §8 완료기준을 6 task 로 분해.

### Task 1. 의존성 카탈로그 — ArchUnit 추가 (인프라, TDD 미적용)

`learnings.md` 함정 #3 (Claude 환각) 적용. ArchUnit 1.3.0 정확성 확인 — `com.tngtech.archunit:archunit-junit5:1.3.0` 은 2024-04 릴리스 (Maven Central 확인 가능). 본 task 진입 시 Maxi가 직접 확인 권장.

**파일**.
- `backend/gradle/libs.versions.toml` — `[versions]` `archunit = "1.3.0"`, `[libraries]` `archunit-junit5 = { module = "com.tngtech.archunit:archunit-junit5", version.ref = "archunit" }`
- `backend/modules/identity-access/build.gradle.kts` — `testImplementation(libs.archunit.junit5)` 추가

**검증**.
```bash
./gradlew :modules:identity-access:dependencies --configuration testRuntimeClasspath | grep archunit
```
ArchUnit jar 1개 + transitive (slf4j-api 등) 출력 확인. 커밋 prefix `chore(deps):`.

### Task 2. SPI VO 정의 — Principal / Credential / AuthnResult / ProviderType (TDD)

**RED**.
- 파일. `backend/modules/identity-access/src/test/kotlin/kr/co/bts/identity/spi/CredentialTest.kt`, `AuthnResultTest.kt`, `PrincipalTest.kt`
- 테스트.
  ```kotlin
  // CredentialTest
  @Test fun `sealed Credential covers UsernamePassword and Pat`() { ... when() exhaustive ... }
  // AuthnResultTest
  @Test fun `Success carries Principal`() { ... }
  @Test fun `Failure carries reason enum`() { ... }
  @Test fun `RequiresMfa carries MfaChallenge placeholder`() { ... }
  // PrincipalTest
  @Test fun `toString masks externalSubject`() { ... assert !contains externalSubject ... }
  @Test fun `toString includes displayName`() { ... }
  ```
- 실패 (예상). 클래스/sealed 변종 없음

**GREEN**.
- `kr/co/bts/identity/spi/Principal.kt` — data class with custom `toString` (DEVELOPMENT.md §1.2)
- `kr/co/bts/identity/spi/Credential.kt` — `sealed interface` + `UsernamePassword(username, password: CharArray)`, `Pat(token: String)`
- `kr/co/bts/identity/spi/AuthnResult.kt` — `sealed interface` + 3 branches
- `kr/co/bts/identity/spi/ProviderType.kt` — `enum class { LOCAL, LDAP, SAML, OIDC, PAT }`
- `kr/co/bts/identity/spi/FailureReason.kt`, `MfaChallenge.kt` — placeholder enum (`NOT_IMPLEMENTED_YET`)
- 각 파일 한글 KDoc 1줄 헤더 (CLAUDE.md §6)

**REFACTOR**.
- `Credential.UsernamePassword.password: CharArray` — `equals/hashCode` override (CharArray reference equality 명시). KDoc 에 "Argon2.wipeArray 호출 contract" 명시
- `Principal.toString` — `userId` last-8 + `displayName` + `providerType` + `externalSubject` masked (`<masked>`)

**검증**. `./gradlew :modules:identity-access:test --tests 'kr.co.bts.identity.spi.*Test'`

### Task 3. AuthenticationProvider 인터페이스 + ProviderRegistry (TDD)

**RED**.
- 파일. `src/test/kotlin/kr/co/bts/identity/spi/ProviderRegistryTest.kt`
- 테스트.
  ```kotlin
  @Test fun `registry with no providers returns null for findByType`()
  @Test fun `registry returns provider by type`()
  @Test fun `registry returns null when findFor credential not supported by any`()
  @Test fun `registry returns first matching provider for credential`()
  @Test fun `registry all() returns immutable copy`()
  ```
- 가짜 Provider 2개. `src/test/kotlin/kr/co/bts/identity/spi/fake/FakeLocalProvider.kt`, `FakePatProvider.kt` — `@Profile("test-spi")` (Brainstorming gap #2 적용)
- 실패 (예상). 인터페이스/Registry 미존재

**GREEN**.
- `kr/co/bts/identity/spi/AuthenticationProvider.kt` — `interface AuthenticationProvider { val type: ProviderType; fun supports(credential: Credential): Boolean; fun authenticate(credential: Credential): AuthnResult }`
- `kr/co/bts/identity/spi/ProviderRegistry.kt` — `@Component class ProviderRegistry(private val providers: List<AuthenticationProvider>)` + 3 함수 (`findByType`, `findFor`, `all`)

**REFACTOR**.
- `findFor` 가 `supports` 우선 검사 후 `type` 일치 확인 (두 단계 매칭 명시)
- `all()` 은 `providers.toList()` 로 immutable copy 반환
- KDoc — "Spring 부팅 시 모든 AuthenticationProvider Bean 자동 수집" 명시

**검증**. `./gradlew :modules:identity-access:test --tests 'kr.co.bts.identity.spi.ProviderRegistryTest'`

### Task 4. SpringSecurityProviderAdapter — 단위 테스트만 (TDD)

본 PR 은 FilterChain 미등록 (spec FR-3 명시). 어댑터 클래스 정의 + 단위 테스트만.

**RED**.
- 파일. `src/test/kotlin/kr/co/bts/identity/adapter/spring/SpringSecurityProviderAdapterTest.kt`
- 테스트.
  ```kotlin
  @Test fun `adapter delegates to ProviderRegistry`()
  @Test fun `BTS Success maps to Spring authenticated Authentication`()
  @Test fun `BTS Failure throws Spring BadCredentialsException`()
  @Test fun `BTS RequiresMfa throws Spring AuthenticationException with mfa challenge`()
  @Test fun `adapter is NOT auto-registered with @Component (must be explicit @Bean)`() // ApplicationContextRunner assertion
  ```
- 실패 (예상). 어댑터 미존재

**GREEN**.
- `kr/co/bts/identity/adapter/spring/SpringSecurityProviderAdapter.kt` — Spring `AuthenticationProvider` 구현. `@Component` 없음 (수동 `@Bean` 등록 강제, brainstorming gap #4 적용)
- 변환 로직. `UsernamePasswordAuthenticationToken` → `Credential.UsernamePassword` ; `Credential.UsernamePassword` Argon2.wipeArray 호출은 본 어댑터 책임 외 (Provider 책임)

**REFACTOR**.
- 변환 함수 분리. `private fun Authentication.toBtsCredential(): Credential`, `private fun AuthnResult.toSpringAuthentication(original: Authentication): Authentication`
- KDoc — "본 PR 은 FilterChain 미등록. FR-AU-09 PR 에서 SecurityConfig 에 명시 등록 예정" 명시

**검증**. `./gradlew :modules:identity-access:test --tests 'kr.co.bts.identity.adapter.spring.SpringSecurityProviderAdapterTest'`

### Task 5. ArchUnit SpiBoundary 룰 (TDD)

**RED**.
- 파일. `src/test/kotlin/kr/co/bts/identity/architecture/SpiBoundaryArchTest.kt`
- 테스트 2개.
  ```kotlin
  @AnalyzeClasses(packages = ["kr.co.bts.identity"])
  class SpiBoundaryArchTest {
    @ArchTest
    val `spi package must not import Spring Framework` = noClasses()
      .that().resideInAPackage("..spi..")
      .should().dependOnClassesThat().resideInAPackage("org.springframework..")
      .because("BTS 도메인 SPI 는 프레임워크 비결합")
      // 단, @Component 같은 메타 어노테이션은 허용해야 함 — except("..stereotype..") 등 정밀화

    @ArchTest
    val `Spring AuthenticationProvider import only allowed in adapter spring package` = noClasses()
      .that().resideOutsideOfPackage("..adapter.spring..")
      .should().dependOnClassesThat().haveFullyQualifiedName("org.springframework.security.authentication.AuthenticationProvider")
  }
  ```
- 실패 (예상). T2/T3 결과가 위 룰 위반 0건이라 처음부터 Green 가능. **의도적 위반 케이스를 임시로 만들어 RED 확인 후 제거** (TDD 규율 유지)

**GREEN**.
- 위 ArchUnit 룰 그대로 통과

**REFACTOR**.
- `..spi..` 의 Spring `@Component` allow-list 정확화. `org.springframework.stereotype..`, `org.springframework.context.annotation..` 만 허용

**검증**. `./gradlew :modules:identity-access:test --tests 'kr.co.bts.identity.architecture.SpiBoundaryArchTest'`

### Task 6. CONCERN-NEW-2 + bootJar fake 제외 검증 (TDD)

**RED**.
- 파일 1. `src/test/kotlin/kr/co/bts/identity/config/IssuerUriEnvOverrideTest.kt` — `@SpringBootTest(properties = ["BTS_KEYCLOAK_ISSUER_URI=https://example/realms/test"])` + ApplicationContext 확인. (Spring 의 `${ENV:default}` syntax 우선순위 검증)
- 파일 2. `src/test/kotlin/kr/co/bts/identity/build/BootJarTest.kt` — `./gradlew bootJar` 산출물에 `FakeLocalProvider.class`/`FakePatProvider.class` 미포함 확인. 또는 Gradle test 가 아닌 별도 verify 스크립트.

**파일 2 결정**. Gradle test 통합이 복잡 (bootJar task 의존성 충돌). **별도 verify 스크립트** `scripts/verify/bootjar-no-fakes.sh` 로 분리 + CI 통합은 후속. 본 PR 은 스크립트만 제공 + plan 메타에 명시.

**GREEN**.
- `backend/modules/identity-access/src/main/resources/application.yml` 갱신.
  ```yaml
  spring:
    security:
      oauth2:
        resourceserver:
          jwt:
            issuer-uri: ${BTS_KEYCLOAK_ISSUER_URI:http://localhost:8180/realms/bts}
  ```
- `scripts/verify/bootjar-no-fakes.sh` — `unzip -l backend/modules/identity-access/build/libs/*.jar | grep -v Fake.*Provider`

**REFACTOR**.
- application.yml 상단 한글 주석 1줄 (CLAUDE.md §6)

**검증**.
```bash
./gradlew :modules:identity-access:test --tests IssuerUriEnvOverrideTest
./gradlew :modules:identity-access:bootJar
bash scripts/verify/bootjar-no-fakes.sh
```

### Task 7. PoC #2 회귀 검증 + ADR 갱신 (인프라/문서, TDD 미적용)

**파일**.
- 본 PR 중 ADR (`2026-05-20-authentication-provider-spi-naming.md`) 는 `/bts-domain` 단계에서 작성 완료. 본 task 는 갱신만.
- ADR 에 "구현 후 확인 사항" 섹션 추가. T3~T5 단위 테스트 결과 + ArchUnit 룰 적용 결과.

**검증**.
- `./gradlew :modules:identity-access:test` 전체 통과 (PoC #2 의 KeycloakIntegrationTest 포함, 회귀 없음 확인)
- `./gradlew ktlintCheck detekt` 통과

## Plan 메타

- **task 수**. 7 (T1/T7 인프라·문서, T2~T6 TDD)
- **TDD 적용**. T2/T3/T4/T5/T6 (5 task) — RED→GREEN→REFACTOR
- **인프라/문서**. T1/T7 (2 task) — `chore:` / `docs:` 커밋
- **예상 커밋 수**. 17 (T1×1 + T7×1 + T2~T6 각 3 cycle = 15)
- **예상 작업 시간**. 2~3시간 (인터페이스 단순 + ArchUnit 첫 도입 학습 시간 포함)
- **추가 검증**. ktlintCheck, detekt, ArchUnit, bootJar verify
- **CI 통합 (후속)**. `scripts/verify/bootjar-no-fakes.sh` 는 본 PR에서 작성, GitHub Actions 통합은 별도 PR
- **게이트 2 직전 수동 검증**. `./gradlew :modules:identity-access:test :modules:identity-access:bootJar` 전체 통과 + PoC #2 회귀 확인
- **writing-plans 우회 사유**. PoC 패턴 일관성. spec 결정이 명확해 task 경계가 자명. 1인 부담 + Auto mode 합리적 판단

## 리뷰 결과

### Direct plan-eng-review (2026-05-20)

`plan-eng-review` 대화형 우회 (PoC 패턴 일관성). type=auth 절대 규칙 체크 표.

| 항목 | 평가 | 비고 |
|---|---|---|
| BC 격리 (identity-access 단독) | ✅ | 다른 BC 호출 없음, 이벤트 발행도 없음 |
| DEVELOPMENT.md §1.1 평문 비밀번호 저장 금지 | ✅ | 저장 흐름 없음. `Credential.UsernamePassword.password: CharArray` + wipeArray contract 명시 |
| §1.2 로그 PII 출력 금지 | ✅ T2 | `Principal.toString` 마스킹 테스트 강제 |
| §1.4 인증 없는 엔드포인트 추가 금지 | ✅ | REST 변경 0건 |
| §1.5 CSRF 검증 비활성화 금지 | ✅ | SecurityConfig 변경 없음, PoC #2 설정 유지 |
| §1.6 입력 검증 | ✅ | sealed Credential + supports() 두 단계 매칭으로 타입 안전 |
| §1.16 의존성 카탈로그 | ✅ T1 | ArchUnit 1.3.0 `libs.versions.toml` 등록 명시 |
| TDD 강제 (`test:` 커밋 우선) | ✅ | T2/T3/T4/T5/T6 RED→GREEN→REFACTOR |
| 마스터플랜 §2.1 정합 | ✅ | D1/D2/D4/D5 본 PR 포함, D3 (authn_providers 테이블) LDAP PR 위임 — spec §5 정당화 |
| `learnings.md` 함정 #3 (Claude 환각) | ⚠️ | ArchUnit 1.3.0 — Maxi 가 의존성 추가 시점에 Maven Central 1회 직접 확인 권장 (plan T1 본문 명시) |
| PoC #2 회귀 위험 | ✅ T7 | Spring Bean 등록 정책 (`@Component` 없음, `@Bean` 명시) + ArchUnit 룰로 격리. 단위 테스트 회귀 없음 검증 |

#### 추가 발견 사항 (impl 단계 보강, BLOCKER 아님)

1. **Spring Boot 자동 설정 인터럽트 위험**. Spring 측 `AuthenticationProvider` 빈이 컨텍스트에 1개라도 있으면 Spring Security 가 기본 인증 흐름을 우회/덮어쓰기 가능. T4 의 `@Component` 미부착 + ApplicationContextRunner 테스트로 명시 검증 — 정상.
2. **`CharArray equals/hashCode`**. T2 REFACTOR 에서 명시 override. 빠뜨리지 말 것.
3. **ArchUnit 룰의 `@Component` 허용 패키지 정확화**. T5 REFACTOR 에서 `org.springframework.stereotype..`/`org.springframework.context.annotation..` 만 allow-list. 단순 `org.springframework..` 전체 차단 시 Spring Bean 정의 자체가 깨짐 — 주의.
4. **`scripts/verify/bootjar-no-fakes.sh` 실행 권한**. `chmod +x` 가 settings.json deny 정책. `#!/usr/bin/env bash` 첫 줄 + Gradle task 로 호출하는 형태가 안전. 또는 PR 본문에 "Maxi 가 `! chmod +x` 1회 실행" 안내.
5. **`@Profile("test-spi")`**. Spring Boot 의 기본 `test` 프로파일과 분리. T6 `IssuerUriEnvOverrideTest` 가 `test-spi` 프로필을 활성화하지 않아야 (가짜 Provider 등록은 해당 테스트 무관) — `@ActiveProfiles` 명시.

#### BLOCKER

**0건**. type=auth 절대 규칙 위반 없음. 진행 가능.

### Direct plan-ceo-review (2026-05-20)

`plan-ceo-review` 대화형 우회. PoC 패턴 일관성.

| 항목 | 평가 |
|---|---|
| 스코프 적정 | ✅ 마스터플랜 §2.1 D1/D2/D4/D5 그대로, D3 의도적 위임 (정당화 spec §5) |
| BC 의존 그래프 정합 | ✅ identity-access 첫 BC 정식 진입 — 모든 다른 BC 의 권한 게이트 선행 |
| 분할 권장 여부 | ❌ 단일 PR 유지 (7 task, 17 커밋, 2~3시간 — 적정 사이즈) |
| 10-star 검토 | 본 PR 은 인프라성 인터페이스 도입 — 10-star 적용 대상 아님 (사용자 가치 직접 노출 없음) |

#### BLOCKER

**0건**. 스코프 확장/축소 권장 없음.

### 종합

- **plan-eng-review**. 0 BLOCKER, 5건 impl 단계 주의 사항
- **plan-ceo-review**. 0 BLOCKER
- **`/autoplan` / `/plan-devex-review`**. 적용 대상 아님 (PR 인프라성)

게이트 1 진입 가능.
