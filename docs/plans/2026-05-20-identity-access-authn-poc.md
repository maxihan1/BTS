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

(미작성)

## 스펙 (← /bts-spec Phase A 채움)

(미작성)

## Brainstorming Check (← /bts-spec Phase B 채움)

(미작성)

## Plan (← /bts-plan TDD task 분해 채움)

(미작성)

## 리뷰 결과 (← /bts-review-plan 채움)

(미작성)
