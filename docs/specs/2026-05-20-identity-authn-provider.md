<!-- identity-access FR-AU-01 spec — Pluggable AuthenticationProvider SPI 도입 -->

# FR-AU-01 — 플러그형 AuthenticationProvider 구조 (스펙)

> 마스터플랜. `docs/plan/product/identity-access.md §2.1`
> SDD. 19장 (인증)
> 도메인 ADR. [2026-05-20-authentication-provider-spi-naming](../decisions/2026-05-20-authentication-provider-spi-naming.md)

## §1 사용자 시나리오 (Given-When-Then)

본 작업은 **개발자용 SPI 도입**. 최종 사용자 시나리오는 없고, **다음 BC/Provider 개발자가 SPI를 쓰는 시나리오**.

### S-01. Provider 개발자가 새 Provider 등록

- **Given**. `com.atlas.bts.identity.spi.AuthenticationProvider` 인터페이스 + Spring DI 컨테이너
- **When**. 개발자가 `class LdapProvider : AuthenticationProvider { ... }` + `@Component` 선언
- **Then**. Spring 부팅 시 `ProviderRegistry`가 자동으로 해당 Bean 수집. `registry.findByType(ProviderType.LDAP)` 로 조회 가능

### S-02. 시스템이 인증 요청을 적합 Provider로 라우팅 (FR-AU-07 선행 인프라)

- **Given**. `ProviderRegistry` 에 가짜 Provider 2개 등록 (`FakeLocal`, `FakeOidc`)
- **When**. `Credential.UsernamePassword(...)` 를 입력으로 `registry.findFor(credential)` 호출
- **Then**. 해당 Credential 을 처리할 Provider 인스턴스 1개 반환 (또는 빈 결과 — 라우팅 미지원 시)

### S-03. Provider 가 인증 실패 시 명확한 사유 반환

- **Given**. `FakeLocal` Provider 가 등록되어 있음
- **When**. `provider.authenticate(Credential.UsernamePassword("alice", "wrong"))` 호출
- **Then**. `AuthnResult.Failure(reason = INVALID_CREDENTIALS)` 반환. 예외 던지지 않음

### S-04. Spring Security 와 BTS SPI 분리 보장

- **Given**. Spring Security 의 `org.springframework.security.authentication.AuthenticationProvider` 가 클래스패스에 있음
- **When**. BTS 코드 어디서든 `import com.atlas.bts.identity.spi.AuthenticationProvider` 만 사용 (Spring 측 인터페이스 직접 import 금지)
- **Then**. ArchUnit 또는 Detekt 룰로 검증. 어댑터 클래스 (`SpringSecurityProviderAdapter`) 만 예외

## §2 기능 요구사항 (FR)

### FR-1. 도메인 SPI 정의

`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/spi/` 에 4개 파일 추가.

- `AuthenticationProvider.kt` — 인터페이스. `authenticate(credential: Credential): AuthnResult` + `supports(credentialType: KClass<out Credential>): Boolean` + `type: ProviderType`
- `Credential.kt` — sealed interface. PoC 단계는 `UsernamePassword` + `Pat` 2개만 정의 (나머지 LDAP/SAML/OIDC 는 각 PR로 추가)
- `AuthnResult.kt` — sealed interface. `Success(principal)` + `Failure(reason)` + `RequiresMfa(challenge)` 3개. `MfaChallenge` 는 후속 PR (`FR-MF-*`) 에서 구체화 — 본 PR은 placeholder enum
- `Principal.kt` — data class. `userId: UUID` + `providerType: ProviderType` + `displayName: String` + `externalSubject: String?`

`ProviderType.kt` enum — `LOCAL`, `LDAP`, `SAML`, `OIDC`, `PAT`. 본 PR은 `LOCAL` 과 `PAT` 만 실제 쓰이고 나머지는 enum 값만 예약.

### FR-2. ProviderRegistry

`com/atlas/bts/identity/spi/ProviderRegistry.kt` 작성. Spring Bean.

- `findByType(type: ProviderType): AuthenticationProvider?` — 해당 타입의 Provider 단일 조회
- `findFor(credential: Credential): AuthenticationProvider?` — credential.type 으로 `supports` 검사 후 첫 매치 반환
- `all(): List<AuthenticationProvider>` — 전체 등록 Provider 목록

생성자 주입으로 `List<AuthenticationProvider>` 수신 → Spring 이 모든 Provider Bean 을 자동 주입. 별도 등록 코드 없음.

### FR-3. Spring Security 어댑터

`com/atlas/bts/identity/adapter/spring/SpringSecurityProviderAdapter.kt`.

- Spring Security 의 `org.springframework.security.authentication.AuthenticationProvider` 구현
- 내부적으로 BTS `ProviderRegistry` 호출 → 결과를 Spring `Authentication` 으로 변환
- 본 PR 단계는 **어댑터만 정의**. 실제 Spring Filter Chain 에 등록은 PoC #2 의 OIDC 흐름과 충돌 가능 — 후속 PR (FR-AU-09 세션/토큰 관리) 에서 통합. 단위 테스트만 작성.

### FR-4. ArchUnit 룰

`backend/modules/identity-access/src/test/kotlin/.../architecture/SpiBoundaryArchTest.kt` 작성.

- "코드베이스에서 `org.springframework.security.authentication.AuthenticationProvider` 직접 import 는 `adapter.spring` 패키지 하위만 허용"
- "도메인 패키지 (`spi/`) 는 Spring Framework 클래스 import 금지" — 단, `@Component` 같은 메타 어노테이션은 허용 (DI 활성화 위해)

### FR-5. CONCERN-NEW-2 처리 (PoC issue-uri 외부화)

PoC #2 의 `application.yml` 의 `spring.security.oauth2.resourceserver.jwt.issuer-uri` 하드코딩 (`http://localhost:8180/realms/bts`) 을 환경변수로 외부화.

```yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: ${BTS_KEYCLOAK_ISSUER_URI:http://localhost:8180/realms/bts}
```

기본값은 dev 환경 동일. 운영/CI 에서 `BTS_KEYCLOAK_ISSUER_URI` 환경변수로 덮어쓰기.

## §3 비기능 요구사항 (NFR)

- **테스트 커버리지**. `spi/` 패키지 100%, `adapter/spring/` 100%. 가짜 Provider 2개 (`FakeLocalProvider`, `FakePatProvider`) 로 등록/조회/라우팅 검증
- **빌드 시간**. 본 PR 추가로 `:identity-access:test` 가 10초 이상 늘지 않음 (PoC #2 베이스라인 측정 후 비교)
- **의존성 추가**. 0건. PoC #2 의존성으로 충분 (Kotlin stdlib + Spring Security 6.3 + JUnit 5 만 사용)
- **ArchUnit 추가** — `com.tngtech.archunit:archunit-junit5` 1.3.0 신규 도입. PoC #2 패턴 일관성 (build.gradle.kts 직접 명시). 의존성 카탈로그 (libs.versions.toml) 도입은 별도 리팩토링 PR로 위임

## §4 API 인터페이스 (REST 변경 0건)

본 PR은 외부 REST 엔드포인트 변경 없음. 순수 SPI 도입. PoC #2 의 `GET /api/v1/users/me/whoami` + `POST /api/v1/users/me/preferences` 유지.

## §5 데이터 모델 변경 (0건 — 의도적 보류)

마스터플랜 D3 = `authn_providers (provider_type, config)` 테이블. **본 PR 보류 사유**.

- 테이블의 실제 INSERT 는 LDAP/OIDC 등 외부 IdP 의 config 가 들어갈 때부터 발생
- 본 PR 의 가짜 Provider 2개는 메모리 + 테스트 픽스처로 등록 → DB 불필요
- 빈 테이블 + 사용 없는 엔티티 = dead weight, 향후 LDAP/OIDC config 모델이 구체화될 때 함께 설계가 정합
- **위임**. FR-AU-02 LDAP PR (다음 다음 PR 예정) 에서 `authn_providers` 테이블 + Flyway 마이그레이션 V002 도입

이 결정은 spec 본문에서 닫음. 마스터플랜 §2.1 D3 체크박스는 LDAP PR 완료 시 같이 체크.

## §6 엣지 케이스

| ID | 케이스 | 처리 |
|---|---|---|
| E1 | Provider 가 0개 등록된 상태 | `registry.all()` 빈 리스트. `findFor` 는 `null`. `findByType` 는 `null`. 예외 없음 |
| E2 | 같은 `ProviderType` 의 Provider 2개 등록 | 본 PR 단계는 단일 활성화 가정. FR-AU-06 "다중 Provider 동시 활성화" 가 다중 등록을 정식 지원 — 그 PR에서 정책 결정. **본 PR은 Spring Bean 충돌 (`@Primary` 없으면 부팅 실패)** 으로 자연스럽게 방어. 테스트로 검증 |
| E3 | `Credential.UsernamePassword(plain="")` 등 빈/null 입력 | Provider 책임. `FakeLocalProvider` 의 `authenticate` 가 `Failure(INVALID_INPUT)` 반환 — 인터페이스 contract |
| E4 | `RequiresMfa` 반환 — MfaChallenge 가 후속 PR | placeholder enum `MfaChallenge.NOT_IMPLEMENTED_YET` 1개. 가짜 Provider 가 이 enum 사용 가능. 후속 FR-MF-01 PR에서 sealed 로 확장 |
| E5 | 가짜 Provider 가 production profile 에 등록 안 됨 | `@Profile("test")` 또는 `@TestConfiguration` 으로 main jar 빌드에 미포함. 검증 — `./gradlew bootJar` 후 jar 내부에 `FakeLocalProvider.class` 없음을 확인하는 테스트 |

## §7 제약 조건

- **DEVELOPMENT.md §1.1~§1.6 보안 규칙 재인용**.
  - §1.1 평문 비밀번호 저장 금지 — 본 PR은 저장 흐름 없음 (인터페이스만). `Credential.UsernamePassword.password` 는 `CharArray` 로 정의 + `Argon2.wipeArray` 호출 contract 명시 (구현자 책임)
  - §1.2 PII 로깅 금지 — `Principal.toString()` 오버라이드. `displayName` 만 표시, `externalSubject` 마스킹
  - §1.4 인증 없는 엔드포인트 추가 금지 — 본 PR 추가 REST 엔드포인트 0건
- **DEVELOPMENT.md §1.16 의존성 카탈로그** — PoC #2 가 카탈로그 미사용 (build.gradle.kts 직접 명시). 본 PR 도 동일 패턴 (드리프트 일관성). 카탈로그 일괄 도입은 별도 리팩토링 PR
- **§ TDD 강제** — FR-1~FR-4 모두 RED→GREEN→REFACTOR 사이클. FR-5 는 단순 yaml 변경 (TDD 미적용 가능)

## §8 측정 가능한 완료 기준

- [ ] `com/atlas/bts/identity/spi/` 4개 파일 (AuthenticationProvider, Credential, AuthnResult, Principal) + ProviderType enum + ProviderRegistry — 모두 작성 + KDoc 1줄 한글 헤더
- [ ] `com/atlas/bts/identity/adapter/spring/SpringSecurityProviderAdapter.kt` — 작성 + 단위 테스트 통과
- [ ] `FakeLocalProvider`, `FakePatProvider` 2개 — `src/test/kotlin/` 위치, `@TestConfiguration` 으로 main jar 미포함
- [ ] ArchUnit 룰 1개 (`SpiBoundaryArchTest`) — Green
- [ ] `application.yml` 의 `issuer-uri` 환경변수 외부화 (`${BTS_KEYCLOAK_ISSUER_URI:...}`)
- [ ] `./gradlew :modules:identity-access:test` 통과 (PoC #2 테스트 + 신규 테스트 모두)
- [ ] `./gradlew ktlintCheck detekt` 통과
- [ ] PoC #2 의 `KeycloakIntegrationTest` 가 본 PR 후에도 그대로 통과 (회귀 없음)

## §9 명시적 비-스코프 (다음 PR 위임)

- FR-AU-02 LDAP Provider 구현 — `authn_providers` 테이블 + 마이그레이션 V002 함께 도입
- FR-AU-09 세션/토큰 관리 — `SpringSecurityProviderAdapter` 의 실제 SecurityFilterChain 통합은 여기서
- `Maxi_wiki/BTS/glossary.md` + `domain/identity-access.md` 갱신 — 본 PR 머지 시 동기화 별도 처리 (수동 영역)
- `UserCredential` (PoC DB 엔티티) → `StoredPasswordCredential` 리네임 — 별도 refactor PR
