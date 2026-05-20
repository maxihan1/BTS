# ADR: CSRF 토큰 전달 방식 — Cookie 모드 채택

- **날짜.** 2026-05-20
- **상태.** 채택 (Accepted)
- **작성자.** security-engineer (Claude Sonnet 4.6)
- **관련 Task.** identity-access-authn-poc T5

---

## 컨텍스트

BTS SPA(React 19)는 Spring Boot 백엔드에 상태 변경 요청(POST/PUT/DELETE)을 보낸다.
CSRF(Cross-Site Request Forgery) 공격을 방어하면서 SPA와 통합하는 CSRF 토큰 전달 방식을 결정해야 한다.

Spring Security 6.x에서 지원하는 주요 방식은 세 가지다.

---

## 결정

`CookieCsrfTokenRepository.withHttpOnlyFalse()` + `CsrfTokenRequestAttributeHandler`를 채택한다.

```kotlin
.csrf { csrf ->
    csrf.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
    csrf.csrfTokenRequestHandler(CsrfTokenRequestAttributeHandler())
}
```

SPA는 다음 흐름으로 동작한다.

1. 첫 GET 요청 시 서버가 `XSRF-TOKEN` 쿠키를 발급한다.
2. SPA가 JavaScript로 쿠키를 읽어 `X-XSRF-TOKEN` 요청 헤더에 담아 POST/PUT/DELETE를 보낸다.
3. 서버의 `CsrfFilter`가 쿠키 값과 헤더 값을 비교한다. 불일치 시 403.

---

## 대안 검토

### 대안 1. HttpSessionCsrfTokenRepository (Spring Security 기본값, 불채택)

서버 세션에 토큰을 저장한다. BTS는 JWT Bearer Token 기반 stateless 아키텍처이므로 세션을 유지하지 않는다. SPA + JWT 환경에서 구조적으로 부적합하다.

### 대안 2. Header-based Double Submit (커스텀 구현, 불채택)

쿠키 없이 요청 헤더와 응답 헤더로만 토큰을 교환하는 방식. Spring Security 표준에서 벗어나고, 구현 복잡도가 높다. 기존 `spring-security-test`의 `csrf()` 포스트 프로세서와 통합이 어렵다.

### 대안 3. CSRF 완전 비활성화 (절대 금지)

DEVELOPMENT.md §1.5에서 명시적으로 금지. T4에서 임시로 `.csrf { it.disable() }`를 사용했으나 T5에서 즉시 제거했다.

---

## 결정 근거

- **SPA 호환.** `HttpOnly=false`로 JavaScript가 쿠키를 읽을 수 있어야 SPA가 헤더에 토큰을 담아 보낼 수 있다.
- **Spring Security 표준.** 별도 라이브러리 없이 Spring Security 내장 구현을 사용한다.
- **BREACH 공격.** `CsrfTokenRequestAttributeHandler`(XOR 없음)를 채택했다. XOR 마스킹이 필요하면 `XorCsrfTokenRequestAttributeHandler`로 교체 가능하나, PoC 단계에서는 단순성 우선.

---

## 보안 트레이드오프 및 보완 조치

### XSS에 의한 CSRF 토큰 탈취 위험

`HttpOnly=false`이므로 XSS 취약점이 있으면 공격자가 JavaScript로 `XSRF-TOKEN` 쿠키를 읽을 수 있다.

**보완 조치 (의무).**

| 항목 | 방법 |
|---|---|
| 쿠키 SameSite | `SameSite=Strict` 설정 (같은 Origin만 쿠키 전송) |
| XSS 방어 | 서버 측 입력 sanitization + React의 기본 XSS 방어(JSX escaping) |
| CSP | Content-Security-Policy 헤더로 외부 스크립트 주입 차단 |

`SameSite=Strict`만으로도 대부분의 CSRF 공격을 방어할 수 있으므로, `CookieCsrfTokenRepository`는 이중 방어 레이어 역할을 한다.

### JWT Bearer Token 경로의 CSRF

`SecurityMockMvcRequestPostProcessors.jwt()`가 내부적으로 `CsrfFilter.skipRequest()`를 호출한다 (Spring Security 6.3.x 바이트코드 확인). Bearer Token은 stateless이므로 CSRF 공격 면역 — Spring Security 설계 의도와 일치한다.

---

## 영향 범위

- **SecurityConfig.kt.** `csrf { it.disable() }` → `CookieCsrfTokenRepository` 교체.
- **SPA(apps/web).** 모든 POST/PUT/DELETE 요청에서 `X-XSRF-TOKEN` 헤더를 추가해야 한다 (axios interceptor 또는 fetch wrapper에서 처리 예정).
- **테스트.** `@WebMvcTest` 슬라이스 테스트에서 jwt() 경로는 CSRF를 skip하므로, 세션 기반 CSRF 검증은 T6 통합 테스트(Testcontainers)에서 별도 검증.
