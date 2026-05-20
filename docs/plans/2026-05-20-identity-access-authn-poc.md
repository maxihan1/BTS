<!-- identity-access §1 AuthN PoC plan — bts-domain~codereview 단계 산출물 누적 -->

# identity-access §1 AuthN PoC

> slug. `identity-access-authn-poc`
> type. `auth`
> agent. `security-engineer`
> primary_bc. `identity-access`
> 생성. 2026-05-20

## Brief

사용자 원문 입력.

> identity-access §1 AuthN PoC 시작 — Spring Boot + Spring Security + Argon2 + Keycloak 컨테이너 (docs/plan/product/identity-access.md §1 기술검증 6항목)

마스터플랜 docs/plan/product/identity-access.md §1 "기술 검증 (AuthN Provider + Keycloak PoC)"의 6 항목을 단일 PR로 통합 검증. PoC는 통합 검증이 본질이라 분리하지 않음.

### 6 PoC 항목

- [ ] §1.1 `AuthenticationProvider` 인터페이스 + `de.mkammerer:argon2-jvm` 패스워드 해싱 동작 (Argon2id, memory=64MB)
- [ ] §1.2 OIDC Authorization Code + PKCE 동작 (Keycloak 25 컨테이너)
- [ ] §1.3 Keycloak realm import 스크립트 (`infra/keycloak/realm-bts.json`)
- [ ] §1.4 Spring Security 필터 체인 — 1개 보호된 엔드포인트 동작 확인
- [ ] §1.5 CSRF 토큰 검증 동작 (DEVELOPMENT.md §1.5 준수)
- [ ] §1.6 Testcontainers Keycloak 통합 테스트 1개 통과

### 본 PoC에 동반되는 최소 인프라

이 PoC가 처음으로 backend/ 디렉토리에 코드를 도입하므로 다음 호스트 인프라도 같이 들어간다 (마스터플랜 식별. "점진적 도입" 결정 — docs/poc/context-notes.md 2026-05-19).

- `backend/` 디렉토리 초기화 (Gradle Kotlin DSL, JDK 21)
- `backend/build.gradle.kts` (모듈러 모놀리스 root)
- `backend/modules/identity-access/` 모듈
- `infra/docker-compose.dev.yml` (PostgreSQL 16 + Keycloak 25 — 다른 컨테이너 충돌 주의)
- `infra/keycloak/realm-bts.json`

### 절대 규칙 적용 (DEVELOPMENT.md §1.1~§1.6 보안 6종)

이 PoC는 보안 영역. 다음 규칙이 직접 적용된다.

- §1.1 DB 평문 비밀번호/토큰 저장 금지 → Argon2id 해싱
- §1.2 로그에 PII 출력 금지 → Pino logger 설정 시 redact 룰
- §1.4 인증 없는 엔드포인트 추가 금지 → Spring Security 필터 체인 적용
- §1.5 CSRF 검증 비활성화 금지 → CookieCsrfTokenRepository 활성

## 도메인 정리 (← /bts-domain 채움)

### BC + 엔티티

- BC. identity-access
- 활용 엔티티 (기존, 신설 없음). `User`, `UserCredential`, `AuthenticationProvider`, `Session` — `Maxi_wiki/BTS/domain/identity-access.md`에 명시
- 외부 시스템 경계. Keycloak 25 (OIDC IdP)

### 신규 용어 후보 (glossary.md 추가 대기)

PoC 진행에는 영향 없음. 머지 후 Maxi 승인 시 일괄 추가.

| 용어 | 정의 |
|---|---|
| Argon2id | OWASP 권장 비밀번호 해싱 알고리즘 (memory-hard). BTS 표준 `memory=64MB` |
| PKCE | Proof Key for Code Exchange. OIDC Authorization Code 흐름 변조 방지 |
| CSRF Token | Cross-Site Request Forgery 방지. BTS 표준 `CookieCsrfTokenRepository` |

### 기존 결정 충돌

- 없음. SDD 19장 `AuthenticationProvider` 플러그형 구조 + DEVELOPMENT.md §1.1~§1.6 보안 규칙과 정합.

### 관련 ADR

- 없음 (`docs/decisions/` 미존재 — BTS 첫 ADR 후보 영역). PoC 구현 중 발생 결정 후보 3건.
  - Keycloak 이미지 선정 (공식 `quay.io/keycloak/keycloak:25` vs 자체 빌드)
  - Argon2id 파라미터 (memory/iterations/parallelism 값)
  - CSRF 토큰 저장 방식 (Cookie vs Header)

이 ADR들은 `/bts-impl` 단계에서 결정 시점에 `docs/decisions/<date>-<topic>.md`로 생성.

## 스펙 (← /bts-spec Phase A 채움)

전체 스펙. [docs/specs/2026-05-20-identity-access-authn-poc.md](../specs/2026-05-20-identity-access-authn-poc.md)

핵심 3줄.
- 6 PoC 항목 (Argon2id 해싱 / OIDC+PKCE / realm import / 보호 엔드포인트 / CSRF / Testcontainers)
- BTS 첫 백엔드 도입 — Gradle Kotlin DSL + Spring Boot + Keycloak 25 컨테이너
- 통합 흐름 1개 (F2 OIDC → F4 whoami), F1 Argon2는 단위 테스트만

## Brainstorming Check (← /bts-spec Phase B 채움)

Phase B 직접 sanity check 수행 (`superpowers:brainstorming` 대화형은 PoC엔 무거움 — 직접 분석).

✅ 통과 (1회 iteration). gap 6건 식별, 모두 `/bts-impl` 단계 해결 가능 — 스펙 재작성 불필요.

### 발견된 gap (impl 단계 결정 항목으로 위임)

1. **F4 보호 엔드포인트 인증 메커니즘** — OIDC JWT (F2 결과) 활용 명시 필요. spec §2 F4 Given에 "F2의 JWT로" 라고 있긴 함, impl 코드 상수로 검증.
2. **F5 가상 POST 엔드포인트** — 실제 PoC 코드에 minimal `POST /api/v1/users/me/preferences` 1개 포함. body는 빈 JSON `{}` 허용.
3. **포트 충돌** — 호스트에 timescaledb(5433)/mariadb(4306) 떠있음. Keycloak 기본 8080 충돌 가능 → `infra/docker-compose.dev.yml`에서 **8180:8080 매핑** 권장. application.yml의 issuer-uri도 동일.
4. **401 응답 포맷** — spec §2 F4에서 `{"error": "unauthorized"}` 명시했으나 Spring Security 기본 응답으로 충분. 커스텀 ExceptionHandler는 Phase 1 정식 단계로 미룸. spec 수정 안 함, impl에서 기본 응답 그대로.
5. **JWT 검증 설정** — `application.yml`에 `spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost:8180/realms/bts` 항목 필요. impl 단계에서 application.yml에 추가.
6. **Testcontainers 동적 주입** — `@DynamicPropertySource`로 Keycloak Container 동적 URL을 Spring property에 주입. F6 통합 테스트 코드에 적용.

### iteration 결정

추가 brainstorming iteration 불필요 (gap 모두 impl 단계 해결 가능, 스펙 본문 재작성 X). `/bts-plan` 진입.

## Plan (← /bts-plan TDD task 분해 채움)

직접 분해 (`superpowers:writing-plans` 대화형 무거움). spec `docs/specs/2026-05-20-identity-access-authn-poc.md`의 6 F를 7 task로 분해.

### Task 1: backend/ Gradle 부트스트랩 + 의존성 카탈로그 §2 도입 (인프라, TDD 미적용)

이 PoC가 BTS 첫 백엔드 도입. `.gitignore`는 이미 `.gradle/` `build/` 차단.

**파일**:
- `backend/settings.gradle.kts` — `rootProject.name = "bts-backend"`, `include(":modules:identity-access")`
- `backend/build.gradle.kts` — Kotlin 2.0 + Spring Boot 3.3 + JDK 21 + 의존성 catalog §2.1/§2.3/§2.5
- `backend/gradle/wrapper/` — `gradle init`로 생성 (Gradle 8.x)
- `backend/modules/identity-access/build.gradle.kts` — 모듈 빌드 스크립트
- `backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/IdentityAccessApplication.kt` — `@SpringBootApplication` 빈 클래스

**검증**: `./gradlew :backend:build` 통과 (테스트 0개라 trivially)

### Task 2: F1 Argon2id 해싱 (TDD)

**RED**:
- 파일: `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/credential/LocalCredentialServiceTest.kt`
- 테스트:
  ```kotlin
  @Test fun `hash returns argon2id encoded string`() { ... }
  @Test fun `verify returns true for matching plain`() { ... }
  @Test fun `verify returns false for mismatching plain`() { ... }
  ```
- 실패 (예상): `LocalCredentialService` 클래스 없음

**GREEN**:
- 파일: `backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/credential/LocalCredentialService.kt`
- 최소 구현. `de.mkammerer.argon2.Argon2Factory.createAdvanced()` + `hash(memory=65536, iterations=3, parallelism=4, password=plain)` + `verify(hash, plain)`

**REFACTOR**:
- 파라미터 상수 분리 `Argon2Params(MEMORY=65536, ITERATIONS=3, PARALLELISM=4)` + KDoc 출처 (OWASP 2024)
- `Argon2.wipeArray(plain)` 호출 (평문 메모리 폐기)

**검증**: `./gradlew :backend:identity-access:test --tests LocalCredentialServiceTest`

### Task 3: F3 Keycloak realm-bts.json + docker-compose.dev.yml (인프라, TDD 미적용)

**파일**:
- `infra/keycloak/realm-bts.json` — realm `bts` + 클라이언트 `bts-web` (Public, PKCE 강제, redirect-uri `http://localhost:8090/login/oauth2/code/keycloak`) + 사용자 `alice / Test1234!`
- `infra/docker-compose.dev.yml` — Keycloak 25 (포트 **8180:8080**, host 8080 충돌 회피 — Brainstorming Check #3), `--import-realm` 플래그, realm-bts.json 마운트
- `backend/src/main/resources/application.yml` — `spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost:8180/realms/bts` (Brainstorming Check #5)

**검증**: 
```bash
docker compose -f infra/docker-compose.dev.yml up keycloak -d
curl -fsS http://localhost:8180/realms/bts/.well-known/openid-configuration | head -5
```

### Task 4: F2 OIDC + F4 보호 엔드포인트 + whoami (TDD)

**RED**:
- 파일: `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/web/WhoamiControllerTest.kt`
- 테스트: `@WebMvcTest(WhoamiController::class)` + `@AutoConfigureMockMvc(addFilters=false)` 시 200, `addFilters=true` + 무토큰 시 401
- 실패: `WhoamiController` 없음

**GREEN**:
- 파일: `backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/web/WhoamiController.kt` — `@GetMapping("/api/v1/users/me/whoami") fun whoami(jwt: Jwt) = mapOf(...)`
- 파일: `backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/config/SecurityConfig.kt` — `SecurityFilterChain` Bean. `oauth2ResourceServer { jwt {} }`, `/api/v1/users/me/whoami` 인증 필요.

**REFACTOR**:
- WhoamiResponse DTO 분리 + JWT claim 매핑 함수 `fun Jwt.toWhoami(): WhoamiResponse`

**검증**: `./gradlew :backend:identity-access:test --tests WhoamiControllerTest`

### Task 5: F5 CSRF 토큰 검증 + minimal POST 엔드포인트 (TDD)

**RED**:
- 파일: `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/web/PreferencesControllerCsrfTest.kt`
- 테스트: 무 CSRF token POST → 403, 유효 CSRF token POST → 200
- 실패: `PreferencesController` 없음

**GREEN**:
- 파일: `backend/.../web/PreferencesController.kt` — `@PostMapping("/api/v1/users/me/preferences") fun put(@RequestBody body: Map<String, Any>?) = ResponseEntity.ok(mapOf("ok" to true))` (Brainstorming Check #2)
- `SecurityConfig.kt` 갱신 — `csrf { csrfTokenRepository = CookieCsrfTokenRepository.withHttpOnlyFalse(); csrfTokenRequestHandler = CsrfTokenRequestAttributeHandler() }`

**REFACTOR**:
- Preferences DTO 정의 (PoC 최소, Map 그대로 두기로 결정 시 REFACTOR 스킵)

**검증**: `./gradlew :backend:identity-access:test --tests PreferencesControllerCsrfTest`

### Task 6: F6 Testcontainers Keycloak 통합 테스트 (TDD)

**RED**:
- 파일: `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/integration/KeycloakIntegrationTest.kt`
- 테스트:
  ```kotlin
  @Testcontainers @SpringBootTest @AutoConfigureMockMvc
  class KeycloakIntegrationTest {
    @Container companion object {
      val keycloak = GenericContainer("quay.io/keycloak/keycloak:25")
        .withCommand("start-dev --import-realm")
        .withFileSystemBind("infra/keycloak/realm-bts.json", "/opt/keycloak/data/import/realm-bts.json")
        .withExposedPorts(8080)
      @DynamicPropertySource fun props(r: DynamicPropertyRegistry) {
        r.add("spring.security.oauth2.resourceserver.jwt.issuer-uri") {
          "http://${keycloak.host}:${keycloak.firstMappedPort}/realms/bts"
        }
      }
    }
    @Test fun `whoami returns 401 without token`()
    @Test fun `whoami returns 200 with valid JWT from Keycloak`()  // 토큰은 Direct Access Grant로 얻음
  }
  ```
- 실패: 컨테이너 부팅 또는 미구현 시나리오

**GREEN**:
- 위 테스트 통과. JWT 발급은 Keycloak `/realms/bts/protocol/openid-connect/token` 직접 호출 (Direct Access Grants 활성 필요 — realm-bts.json에 옵션)

**REFACTOR**:
- 베이스 클래스 `KeycloakIntegrationBase` 추출 (다음 PR에서 재사용)

**검증**: `./gradlew :backend:identity-access:test --tests KeycloakIntegrationTest` (< 60s)

### Task 7: ADR 3건 작성 (impl 단계 발생 결정 기록, TDD 미적용)

**파일**:
- `docs/decisions/2026-05-20-keycloak-image-selection.md` — `quay.io/keycloak/keycloak:25` 공식 이미지 채택. 대안 (자체 빌드/Bitnami) + 채택 근거 (공식 안정성 + dev 환경 충분).
- `docs/decisions/2026-05-20-argon2id-parameters.md` — `memory=65536, iterations=3, parallelism=4`. OWASP 2024 권장 인용. 측정값 (해싱 ~100ms 단일 코어).
- `docs/decisions/2026-05-20-csrf-cookie-mode.md` — `CookieCsrfTokenRepository.withHttpOnlyFalse()` + `CsrfTokenRequestAttributeHandler` (SPA 호환). HttpOnly=false 보안 정당화 (XSS 방어는 SameSite=Strict + sanitize에 의존).

**검증**: `ls docs/decisions/2026-05-20-*.md | wc -l` → 3

## Plan 메타

- task 수: 7
- TDD 적용: T2/T4/T5/T6 (4 task) — RED→GREEN→REFACTOR
- 인프라/문서: T1/T3/T7 (3 task) — TDD 미적용 (`chore:`/`docs:` 커밋)
- 예상 커밋 수: 15 (T1×1 + T3×1 + T7×1 + T2~T6 각 3) — 사이즈 따라 일부 묶기 가능
- 예상 작업 시간: 3~4시간 (Gradle 부트스트랩 + Testcontainers Keycloak 첫 시도라 시행착오 가능성)
- 추가 검증: ktlint, detekt (의존성 카탈로그에 있음)
- 게이트 2 직전 수동 검증: 브라우저 OIDC 흐름 1회 (alice 로그인 → whoami 200)

## 리뷰 결과 (← /bts-review-plan 채움)

### Direct plan-eng-review (2026-05-20)

`plan-eng-review` 대화형 스킬 대신 직접 체크 (PoC 단순). type=auth라 보안 강화 체크 적용.

| 항목 | 평가 |
|---|---|
| BC 격리 (한 BC 단독) | ✅ |
| DEVELOPMENT.md §1.1 평문 비밀번호 금지 → Argon2id | ✅ T2 |
| §1.2 로그 PII 금지 (구체 redact 룰은 impl) | ✅ spec §7 명시 |
| §1.4 인증 없는 엔드포인트 금지 → Spring Security 필터 | ✅ T4 |
| §1.5 CSRF 비활성화 금지 → CookieCsrfTokenRepository | ✅ T5 |
| §1.6 입력 검증 (PoC라 Map 최소 허용) | ✅ 경계 OK |
| TDD 강제 (test: 커밋 우선) | ✅ T2/T4/T5/T6 |
| 의존성 카탈로그 §1.16 (신규 0) | ✅ §2.1/§2.3/§2.5만 |
| 데이터 무결성 (Flyway only) | ✅ V001 spec 명시 |

### Direct plan-ceo-review (2026-05-20)

- PoC scope 적정 (마스터플랜 §1 그대로, 늘리지 않음)
- BC 의존 그래프 최상단 — identity-access 첫 BC 진입 정당
- 분할 제안 없음 (PoC는 통합 검증이 본질)

### 주의 사항 (impl 단계 보강, BLOCKER 아님)

1. **포트 8180 사전 점검**. impl 진입 시 `lsof -i :8180`로 호스트 충돌 확인. 다른 서비스 있으면 8280 등 대안.
2. **T3 realm-bts.json에 `directAccessGrantsEnabled: true`**. T6 통합 테스트의 JWT 직접 발급에 필요.
3. **T7 ADR 작성 시점 변경**. T7 마지막 일괄 대신, T2/T4/T5 결정 발생 즉시 작성 (컨텍스트 신선). plan 메타의 커밋 수 추정에 영향 없음.
4. **T1 커밋 접두사 `chore(infra):` 강제**. spec-compliance-verifier가 `feat:` 커밋의 test 선행 검사 차단 회피 (T1은 코드지만 비즈니스 로직 0).
5. **Testcontainers Keycloak 부팅 60s 한계 측정**. impl 진입 시 1회 cold start 측정. 60s 초과 시 한계 조정.

### BLOCKER

**0건**. `auth` 타입 절대 규칙 위반 없음. 진행 가능.
