<!-- ADR — BTS 도메인 AuthenticationProvider SPI 명명 + Spring Security 분리 결정 -->

# ADR: 도메인 AuthenticationProvider SPI 명명 + Spring Security 어댑터 분리

> 결정일. 2026-05-20
> 상태. Accepted
> 컨텍스트. identity-access BC §2.1 FR-AU-01 (PR #3, slug `identity-authn-provider`)
> 선행. ADR 4건 (Phase 0 PoC, 2026-05-20)

## 컨텍스트

마스터플랜 `docs/plan/product/identity-access.md §2.1` 은 FR-AU-01 D1~D5에서 다음 도메인 SPI를 요구한다.

- `AuthenticationProvider` 인터페이스 — 향후 LDAP/SAML/OIDC/Local Provider가 모두 구현할 확장 지점
- `ProviderRegistry` — 등록된 Provider를 조회/순회하는 메커니즘
- VO 3종 — `Principal`, `Credential`, `AuthnResult`

그러나 Phase 0 PoC (PR #2)에서 이미 Spring Security 풀스택을 통합 검증했고, Spring Security에는 동일 명의 인터페이스 `org.springframework.security.authentication.AuthenticationProvider`가 존재한다. 둘은 책임이 다르다.

- **Spring `AuthenticationProvider`** — Spring Security 필터 체인의 인증 위임 후크. `authenticate(Authentication): Authentication` 단일 메서드. 프레임워크 결합.
- **BTS 도메인 `AuthenticationProvider`** — BTS의 비즈니스 도메인 SPI. 향후 IdP 추가 시 외부 통합을 위한 확장 지점. 프레임워크 비결합.

이름이 같으면 import 충돌 + 독자 혼동 + 향후 리팩토링 부담이 누적된다.

## 선택지

### A. 우리 인터페이스 이름을 다르게 (`IdentityProvider` 등)

장점. import 충돌 없음. Spring과 명확히 분리.
단점. 마스터플랜 SDD 19장 + Maxi_wiki glossary 이미 `AuthenticationProvider`로 봉인됨. 용어 변경은 SDD 영향. 비용 큼.

### B. 우리는 도메인 이름 유지 + 패키지 격리 + 어댑터 명시

장점. 마스터플랜/SDD 용어 그대로. 도메인 언어 일관성.
단점. import 충돌 가능 — `import kr.co.bts.identity.AuthenticationProvider` vs `import org.springframework.security.authentication.AuthenticationProvider`. 코드에서 둘 다 쓸 일은 어댑터 클래스 안에만 존재하므로 영향 제한.

## 결정

**B 선택.** 도메인 용어 `AuthenticationProvider` 유지 + 패키지 분리 + Spring 어댑터 1개로 격리.

### 구체 결정

1. **도메인 SPI 위치** = `backend/modules/identity-access/src/main/kotlin/kr/co/bts/identity/spi/AuthenticationProvider.kt`
2. **Spring 어댑터** = `backend/modules/identity-access/src/main/kotlin/kr/co/bts/identity/adapter/spring/SpringSecurityProviderAdapter.kt` — 도메인 `AuthenticationProvider`를 Spring `AuthenticationProvider`로 래핑. 어댑터 클래스 내부에서만 두 이름이 공존하며, Spring 측은 풀-경로 import.
3. **ProviderRegistry 위치** = `kr/co/bts/identity/spi/ProviderRegistry.kt`. Spring Bean이지만 도메인 패키지 소속. Spring DI 컨테이너 생성 시 등록된 모든 도메인 `AuthenticationProvider` Bean을 수집.
4. **VO 3종 위치** = `kr/co/bts/identity/spi/` (Principal, Credential, AuthnResult). sealed 클래스 (Kotlin) 로 정의.

### Credential / AuthnResult sealed 형태

```kotlin
sealed interface Credential {
    data class UsernamePassword(val username: String, val password: CharArray) : Credential
    data class OidcToken(val idToken: String) : Credential
    data class SamlAssertion(val xml: String) : Credential
    data class LdapBind(val username: String, val password: CharArray) : Credential
    data class Pat(val token: String) : Credential
}

sealed interface AuthnResult {
    data class Success(val principal: Principal) : AuthnResult
    data class Failure(val reason: FailureReason) : AuthnResult
    data class RequiresMfa(val challenge: MfaChallenge) : AuthnResult
}
```

`Principal` 은 인증 완료 후 식별 정보 (userId, providerType, displayName, externalSubject).

### UserCredential (PoC 엔티티) 와의 구분

- `UserCredential` = DB 영속 엔티티 (Argon2 해시 + algoVersion + updatedAt). PoC #2에서 도입. 변경 없음.
- `Credential` = 인증 시도 입력 VO. 메모리에만 존재. DB 저장 안 됨.

두 이름이 비슷해 혼동 우려. 향후 `UserCredential` 을 `StoredPasswordCredential` 로 리네임 검토 — **다음 PR로 분리**. 본 PR 스코프 외.

## 결과

- 도메인 용어 1개 (`AuthenticationProvider`) 보존, 새 용어 4개 (`Principal`, `Credential`, `AuthnResult`, `ProviderRegistry`) 추가.
- Spring Security 의존성은 어댑터 1개로 격리. 향후 Spring 버전 업/교체 시 어댑터만 수정.
- `ProviderRegistry` 가 도메인 측 진입점. Spring `AuthenticationManager` 는 내부에서 `SpringSecurityProviderAdapter` 를 통해 도메인 Registry 호출.

## 영향

- glossary.md — `Principal`, `Credential` (Sealed 입력), `AuthnResult`, `ProviderRegistry` 4개 추가 (Maxi 승인 후).
- domain/identity-access.md — 핵심 엔티티 섹션 갱신 + ADR 링크.
- 코드 — `kr/co/bts/identity/spi/` 패키지 신설.

## 관련

- 마스터플랜 §2.1 (`docs/plan/product/identity-access.md`)
- SDD 19장 (인증)
- PoC ADR 4건 (2026-05-20)
