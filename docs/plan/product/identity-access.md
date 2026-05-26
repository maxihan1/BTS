<!-- identity-access BC — 인증/2FA/권한 22 FR + AuthN Provider PoC -->

# identity-access BC

**소속 FR**. 22개 (AU 10 + MF 5 + PM 7).
**책임**. 사용자/조직/그룹/역할/권한/인증/세션 전체.
**SDD 참조**. 19장 (인증), 12장 (권한).
**다른 BC와의 경계**. 모든 BC의 권한 게이트. 사용자 식별·인증·인가의 단일 진실 원천. 다른 BC는 `SecurityContext` 인터페이스로만 접근.

## §0 진입 조건

- [ ] DEVELOPMENT.md §1.1~§1.6 (보안 6개 절대 규칙) 숙지
- [ ] DATA.md §사용자/세션 테이블 규칙 확인
- [ ] `docs/poc/dependencies.md §2.3` (인증/보안 라이브러리) 확인
- [ ] §1 기술 검증 통과 (아래)

## §1 기술 검증 (AuthN Provider + Keycloak PoC)

**SDD**. 19장. **checklist.md 위임**. §1.4. **ADR 후보**. 없음 (LDAP/SAML/OIDC는 표준 라이브러리).

- [ ] `AuthenticationProvider` 인터페이스 + `de.mkammerer:argon2-jvm` 패스워드 해싱 동작 (Argon2id, memory=64MB)
- [ ] OIDC Authorization Code + PKCE 동작 (Keycloak 25 컨테이너)
- [ ] Keycloak realm import 스크립트 (`infra/keycloak/realm-bts.json`)
- [ ] Spring Security 필터 체인 — 1개 보호된 엔드포인트 동작 확인
- [ ] CSRF 토큰 검증 동작 (DEVELOPMENT.md §1.5 준수)
- [ ] Testcontainers Keycloak 통합 테스트 1개 통과

## §2 인증 (FR-AU, 10개)

### §2.1 FR-AU-01 — 플러그형 AuthenticationProvider 구조

**우선순위**. 필수 | **선행**. §1 기술 검증 | **Plan slug**. `identity/authn-provider`

- [x] D1. 도메인 정의 — `Maxi_wiki/BTS/domain/identity-access.md`. Principal/Credential/AuthnResult VO (책임. security-engineer + Maxi)
- [x] D2. 명세 — `AuthenticationProvider` 인터페이스 + 등록 메커니즘 (책임. security-engineer)
- [x] D3. 데이터 모델 — `authn_providers` (provider_type, config) (책임. db-engineer)
- [x] D4. 백엔드 — `ProviderRegistry` + `AuthenticationManager` Spring Bean (책임. security-engineer)
- [x] D5. 백엔드 테스트 — 가짜 Provider 2개로 등록/조회 (책임. security-engineer)
- [~] D6. 프론트 UI — Provider 선택 화면 (책임. designer → frontend-engineer)
- [~] D7. E2E (책임. qa-engineer)

### §2.2 FR-AU-02 — LDAP/AD 연동

**우선순위**. 필수 | **선행**. §2.1 | **Plan slug**. `identity/ldap`

- [x] D1. 도메인 (책임. security-engineer) (PR #8, 2026-05-21)
- [x] D2. 명세 — baseDN, 사용자/그룹 매핑, lockout 정책 (책임. security-engineer) (PR #8, 2026-05-21)
- [x] D3. 데이터 모델 — `users`, `user_external_accounts(provider, externalId)` (책임. db-engineer) (PR #8, 2026-05-21)
- [x] D4. 백엔드 — Spring Security LDAP authenticator + `UserDetailsService` (책임. security-engineer) (PR #8, 2026-05-21)
- [x] D5. 백엔드 테스트 — Testcontainers OpenLDAP (책임. security-engineer) (PR #8, 2026-05-21)
- [x] D6. 프론트 UI — 로그인 폼 (책임. designer → frontend-engineer) (PR #11, 2026-05-22)
- [x] D7. E2E — Playwright (책임. qa-engineer) (PR #22, 2026-05-26)

| 항목 | 임계 | 실측 (p95) |
|---|---|---|
| 로그인 응답 | 500ms | ___ |

### §2.3 FR-AU-03 — SAML 2.0 SSO

**우선순위**. 필수 | **선행**. §2.1 | **Plan slug**. `identity/saml`

- [ ] D1. 도메인 (책임. security-engineer)
- [ ] D2. 명세 — IdP-initiated + SP-initiated 흐름 (책임. security-engineer)
- [ ] D3. 데이터 모델 — `saml_idp_configs` (책임. db-engineer)
- [ ] D4. 백엔드 — `spring-security-saml2-service-provider`. `/sso/saml2/...` (책임. security-engineer)
- [ ] D5. 백엔드 테스트 — Testcontainers Keycloak SAML 모드 (책임. security-engineer)
- [ ] D6. 프론트 UI — IdP 선택 + SP-initiated 진입점 (책임. designer → frontend-engineer)
- [ ] D7. E2E (책임. qa-engineer)

### §2.4 FR-AU-04 — OIDC SSO

**우선순위**. 필수 | **선행**. §2.1 | **Plan slug**. `identity/oidc`

- [ ] D1. 도메인 (책임. security-engineer)
- [ ] D2. 명세 — Authorization Code + PKCE. JWT 검증 (책임. security-engineer)
- [ ] D3. 데이터 모델 — `oidc_provider_configs` (책임. db-engineer)
- [ ] D4. 백엔드 — `spring-boot-starter-oauth2-client` + `-resource-server` (책임. security-engineer)
- [ ] D5. 백엔드 테스트 — Testcontainers Keycloak OIDC (책임. security-engineer)
- [ ] D6. 프론트 UI — OIDC 진입 버튼 + 리다이렉트 처리 (책임. designer → frontend-engineer)
- [ ] D7. E2E (책임. qa-engineer)

### §2.5 FR-AU-05 — 로컬 계정 (외부 협력사용)

**우선순위**. 필수 | **선행**. §2.1 | **Plan slug**. `identity/local-account`

- [x] D1. 도메인 — LocalCredential VO (책임. security-engineer)
- [x] D2. 명세 — 비밀번호 정책 (길이/복잡도/이력) + Argon2id (책임. security-engineer)
- [x] D3. 데이터 모델 — `local_credentials(password_hash, last_changed_at)` (책임. db-engineer)
- [~] D4. 백엔드 — 가입/비밀번호 변경/리셋 (책임. security-engineer)
- [~] D5. 백엔드 테스트 — 비밀번호 정책 위반 케이스 (책임. security-engineer)
- [ ] D6. 프론트 UI — 가입/비밀번호 변경 폼 (책임. designer → frontend-engineer)
- [ ] D7. E2E (책임. qa-engineer)

### §2.6 FR-AU-06 — 다중 Provider 동시 활성화

**우선순위**. 필수 | **선행**. §2.1~§2.5 | **Plan slug**. `identity/multi-provider`

- [ ] D1. 도메인 (책임. security-engineer)
- [ ] D2. 명세 — Provider 우선순위 + fallback 규칙 (책임. security-engineer)
- [ ] D3. 데이터 모델 — `authn_providers.priority, enabled` (책임. db-engineer)
- [ ] D4. 백엔드 — `CompositeAuthenticationManager` (책임. security-engineer)
- [ ] D5. 백엔드 테스트 — 다중 Provider 시나리오 (책임. security-engineer)
- [ ] D6. 프론트 UI — 다중 Provider 선택 화면 (책임. designer → frontend-engineer)
- [ ] D7. E2E (책임. qa-engineer)

### §2.7 FR-AU-07 — 도메인 기반 자동 라우팅

**우선순위**. 높음 | **선행**. §2.6 | **Plan slug**. `identity/domain-routing`

- [ ] D1. 도메인 (책임. security-engineer)
- [ ] D2. 명세 — 이메일 도메인 → Provider 매핑 (책임. security-engineer)
- [ ] D3. 데이터 모델 — `domain_provider_routes(domain, provider_id)` (책임. db-engineer)
- [ ] D4. 백엔드 — 이메일 입력 → Provider 자동 선택 (책임. security-engineer)
- [ ] D5. 백엔드 테스트 (책임. security-engineer)
- [ ] D6. 프론트 UI — 이메일 입력 후 Provider 자동 진입 (책임. designer → frontend-engineer)
- [ ] D7. E2E (책임. qa-engineer)

### §2.8 FR-AU-08 — 계정 통합 (Account Linking)

**우선순위**. 높음 | **선행**. §2.1~§2.5 | **Plan slug**. `identity/account-linking`

- [ ] D1. 도메인 (책임. security-engineer)
- [ ] D2. 명세 — 동일 사용자 다중 외부 계정 통합 워크플로우 (책임. security-engineer)
- [ ] D3. 데이터 모델 — `user_external_accounts` 다대일 (책임. db-engineer)
- [ ] D4. 백엔드 — Linking API + 재인증 강제 (책임. security-engineer)
- [ ] D5. 백엔드 테스트 — 충돌 케이스 (책임. security-engineer)
- [ ] D6. 프론트 UI — "계정 연결" 설정 페이지 (책임. designer → frontend-engineer)
- [ ] D7. E2E (책임. qa-engineer)

### §2.9 FR-AU-09 — 세션/토큰 관리 (JWT + Refresh + PAT)

**우선순위**. 필수 | **선행**. §2.1 | **Plan slug**. `identity/sessions`

- [x] D1. 도메인 — Session/AccessToken/RefreshToken/PAT 분리 (책임. security-engineer)
- [~] D2. 명세 — TTL, 회전 정책, 세션 강제 종료 (책임. security-engineer)
- [x] D3. 데이터 모델 — `sessions`, `refresh_tokens`, `pats(scope, revoked_at)` (책임. db-engineer)
- [~] D4. 백엔드 — JWT 발급/검증 + Redis 세션 + PAT 발급 API (책임. security-engineer)
- [x] D5. 백엔드 테스트 — 토큰 만료/회전/취소 (책임. security-engineer)
- [~] D6. 프론트 UI — 활성 세션 목록 + 강제 로그아웃 (책임. designer → frontend-engineer). **DEVELOPMENT.md §1.17 — 토큰 localStorage 금지 (sessionStorage 강제)**
- [x] D7. E2E (책임. qa-engineer)

### §2.10 FR-AU-10 — 인증 감사 로그

**우선순위**. 필수 | **선행**. §2.1 | **Plan slug**. `identity/audit-log`

- [ ] D1. 도메인 — AuthEvent (성공/실패/세션종료/권한변경) (책임. security-engineer)
- [ ] D2. 명세 — 보존 1년 (SDD §2.3.3) (책임. security-engineer)
- [ ] D3. 데이터 모델 — `auth_audit_logs(event_type, ip, user_agent, ...)` (책임. db-engineer)
- [ ] D4. 백엔드 — 모든 인증/권한 변경 이벤트 emit (책임. security-engineer)
- [ ] D5. 백엔드 테스트 — 이벤트 누락 0 (책임. security-engineer)
- [ ] D6. 프론트 UI — 관리자 감사 로그 조회 (책임. designer → frontend-engineer)
- [ ] D7. E2E (책임. qa-engineer)

## §3 다중 요소 인증 (FR-MF, 5개)

### §3.1 FR-MF-01 — TOTP (Authenticator 앱)

**우선순위**. 필수 | **선행**. §2.5, §2.9 | **Plan slug**. `identity/mfa-totp`

- [ ] D1. 도메인 — TotpSecret VO (책임. security-engineer)
- [ ] D2. 명세 — QR 코드 등록 + 6자리 코드 검증 (책임. security-engineer)
- [ ] D3. 데이터 모델 — `user_mfa_totp(secret_encrypted)` (책임. db-engineer)
- [ ] D4. 백엔드 — `aerogear-otp-java` 또는 자체 RFC 6238 (책임. security-engineer)
- [ ] D5. 백엔드 테스트 — clock drift ±1 step 허용 (책임. security-engineer)
- [ ] D6. 프론트 UI — QR 표시 + 6자리 입력 (책임. designer → frontend-engineer)
- [ ] D7. E2E (책임. qa-engineer)

### §3.2 FR-MF-02 — 백업 코드 (Recovery Codes)

**우선순위**. 필수 | **선행**. §3.1 | **Plan slug**. `identity/mfa-backup`

- [ ] D1. 도메인 (책임. security-engineer)
- [ ] D2. 명세 — 10개 1회용 코드 생성 + 해시 저장 (책임. security-engineer)
- [ ] D3. 데이터 모델 — `user_mfa_backup_codes(code_hash, used_at)` (책임. db-engineer)
- [ ] D4. 백엔드 — 코드 생성/검증/소진 (책임. security-engineer)
- [ ] D5. 백엔드 테스트 (책임. security-engineer)
- [ ] D6. 프론트 UI — 코드 다운로드/인쇄 + 1회용 안내 (책임. designer → frontend-engineer)
- [ ] D7. E2E (책임. qa-engineer)

### §3.3 FR-MF-03 — WebAuthn (Passkey/하드웨어 키)

**우선순위**. 선택 | **선행**. §3.1 | **Plan slug**. `identity/mfa-webauthn`

- [ ] D1. 도메인 (책임. security-engineer)
- [ ] D2. 명세 — FIDO2 attestation + assertion (책임. security-engineer)
- [ ] D3. 데이터 모델 — `user_webauthn_credentials(credential_id, public_key)` (책임. db-engineer)
- [ ] D4. 백엔드 — `webauthn4j` 라이브러리 (책임. security-engineer)
- [ ] D5. 백엔드 테스트 — 가상 Authenticator (책임. security-engineer)
- [ ] D6. 프론트 UI — `navigator.credentials` API (책임. designer → frontend-engineer)
- [ ] D7. E2E (책임. qa-engineer)

### §3.4 FR-MF-04 — 강제 정책 (관리자 + 민감 프로젝트)

**우선순위**. 필수 | **선행**. §3.1, §3.2 | **Plan slug**. `identity/mfa-enforce`

- [ ] D1. 도메인 — MfaPolicy (책임. security-engineer)
- [ ] D2. 명세 — 역할/프로젝트 단위 강제 (책임. security-engineer)
- [ ] D3. 데이터 모델 — `mfa_policies(scope, required)` (책임. db-engineer)
- [ ] D4. 백엔드 — 인증 중간 단계에서 MFA 등록 강제 (책임. security-engineer)
- [ ] D5. 백엔드 테스트 (책임. security-engineer)
- [ ] D6. 프론트 UI — MFA 미등록 시 step-up 페이지 (책임. designer → frontend-engineer)
- [ ] D7. E2E (책임. qa-engineer)

### §3.5 FR-MF-05 — 신뢰 디바이스 (30일 면제)

**우선순위**. 높음 | **선행**. §3.1 | **Plan slug**. `identity/trusted-devices`

- [ ] D1. 도메인 — TrustedDevice (책임. security-engineer)
- [ ] D2. 명세 — 사용자 동의 + 30일 TTL + 취소 (책임. security-engineer)
- [ ] D3. 데이터 모델 — `trusted_devices(device_fingerprint, expires_at)` (책임. db-engineer)
- [ ] D4. 백엔드 — fingerprint 발급 + MFA 우회 검증 (책임. security-engineer)
- [ ] D5. 백엔드 테스트 — TTL 만료 + 명시적 취소 (책임. security-engineer)
- [ ] D6. 프론트 UI — "이 디바이스 신뢰" 체크박스 + 디바이스 관리 페이지 (책임. designer → frontend-engineer)
- [ ] D7. E2E (책임. qa-engineer)

## §4 권한 관리 (FR-PM, 7개)

### §4.1 FR-PM-01 — 프로젝트 행정 (관리자/멤버 관리)

**우선순위**. 필수 | **선행**. §2.1 | **Plan slug**. `identity/project-admin`

- [ ] D1. 도메인 — ProjectRole (책임. security-engineer)
- [ ] D2. 명세 — 관리자 멤버 초대/제거 (책임. security-engineer)
- [ ] D3. 데이터 모델 — `project_memberships(project_id, user_id, role)` (책임. db-engineer)
- [ ] D4. 백엔드 — CRUD API + 가드 (책임. security-engineer)
- [ ] D5. 백엔드 테스트 (책임. security-engineer)
- [ ] D6. 프론트 UI — 프로젝트 설정 → 멤버 (책임. designer → frontend-engineer)
- [ ] D7. E2E (책임. qa-engineer)

### §4.2 FR-PM-02 — 이슈 등록/수정/삭제 권한 분리

**우선순위**. 필수 | **선행**. §4.1 | **Plan slug**. `identity/issue-permissions`

- [ ] D1. 도메인 — Permission (CREATE_ISSUE/EDIT_ISSUE/DELETE_ISSUE) (책임. security-engineer)
- [ ] D2. 명세 — 역할 × 권한 매트릭스 (책임. security-engineer)
- [ ] D3. 데이터 모델 — `permission_schemes` + `role_permissions` (책임. db-engineer)
- [ ] D4. 백엔드 — `@PreAuthorize("hasPermission(...)")` (책임. security-engineer)
- [ ] D5. 백엔드 테스트 — 권한 매트릭스 전수 (책임. security-engineer)
- [ ] D6. 프론트 UI — 권한 없는 액션 버튼 비활성화 (책임. designer → frontend-engineer)
- [ ] D7. E2E (책임. qa-engineer)

### §4.3 FR-PM-03 — 버전/컴포넌트 등록 권한

**우선순위**. 필수 | **선행**. §4.2 | **Plan slug**. `identity/version-component-permissions`

- [ ] D1. 도메인 (책임. security-engineer)
- [ ] D2. 명세 (책임. security-engineer)
- [ ] D3. 데이터 모델 — (FR-PM-02 활용) (책임. db-engineer)
- [ ] D4. 백엔드 — `@PreAuthorize` 추가 (책임. security-engineer)
- [ ] D5. 백엔드 테스트 (책임. security-engineer)
- [ ] D6. 프론트 UI (책임. frontend-engineer)
- [ ] D7. E2E (책임. qa-engineer)

### §4.4 FR-PM-04 — 워크플로우/자동화 관리 권한

**우선순위**. 필수 | **선행**. §4.2 | **Plan slug**. `identity/workflow-automation-permissions`

- [ ] D1. 도메인 (책임. security-engineer)
- [ ] D2. 명세 (책임. security-engineer)
- [ ] D3. 데이터 모델 — (FR-PM-02 활용) (책임. db-engineer)
- [ ] D4. 백엔드 — `@PreAuthorize` 추가 (책임. security-engineer)
- [ ] D5. 백엔드 테스트 (책임. security-engineer)
- [ ] D6. 프론트 UI (책임. frontend-engineer)
- [ ] D7. E2E (책임. qa-engineer)

### §4.5 FR-PM-05 — 이슈 접근 (Browse, View)

**우선순위**. 필수 | **선행**. §4.2 | **Plan slug**. `identity/issue-access`

- [ ] D1. 도메인 — BrowsePermission vs ViewPermission 분리 (책임. security-engineer)
- [ ] D2. 명세 (책임. security-engineer)
- [ ] D3. 데이터 모델 — (FR-PM-02 활용) (책임. db-engineer)
- [ ] D4. 백엔드 — 이슈 쿼리에 필터 자동 첨부 (jOOQ Condition 빌더) (책임. security-engineer + backend-engineer)
- [ ] D5. 백엔드 테스트 — 비공개 이슈 조회 차단 (책임. security-engineer)
- [ ] D6. 프론트 UI — 권한 없는 이슈 404 처리 (책임. frontend-engineer)
- [ ] D7. E2E (책임. qa-engineer)

### §4.6 FR-PM-06 — 이슈 보안 수준

**우선순위**. 필수 | **선행**. §4.5 | **Plan slug**. `identity/issue-security-level`

- [ ] D1. 도메인 — SecurityLevel 등급 (책임. security-engineer)
- [ ] D2. 명세 — 이슈마다 등급 + 등급별 접근자 (책임. security-engineer)
- [ ] D3. 데이터 모델 — `security_levels`, `issues.security_level_id` (책임. db-engineer)
- [ ] D4. 백엔드 — 등급 검증 가드 (책임. security-engineer)
- [ ] D5. 백엔드 테스트 (책임. security-engineer)
- [ ] D6. 프론트 UI — 이슈 생성/편집 시 등급 선택 (책임. designer → frontend-engineer)
- [ ] D7. E2E (책임. qa-engineer)

### §4.7 FR-PM-07 — 필드 수준 권한

**우선순위**. 높음 | **선행**. §4.5 | **Plan slug**. `identity/field-permissions`

- [ ] D1. 도메인 — FieldVisibility (책임. security-engineer)
- [ ] D2. 명세 — 필드 × 역할 매트릭스 (책임. security-engineer)
- [ ] D3. 데이터 모델 — `field_permissions(field_name, role, visibility)` (책임. db-engineer)
- [ ] D4. 백엔드 — 응답 직렬화 시 필드 필터 (책임. security-engineer + backend-engineer)
- [ ] D5. 백엔드 테스트 (책임. security-engineer)
- [ ] D6. 프론트 UI — 숨김 필드 렌더 차단 (책임. frontend-engineer)
- [ ] D7. E2E (책임. qa-engineer)

## §NFR identity-access BC 완료 게이트

### 측정값 기록표

| 항목 | 임계 | 실측 (p95) | 비고 |
|---|---|---|---|
| 로그인 응답 (LDAP) | 500ms | ___ | k6 |
| 로그인 응답 (OIDC) | 800ms | ___ | k6 (외부 라운드트립 포함) |
| 토큰 검증 (JWT) | 50ms | ___ | k6 단순 검증 |
| 권한 가드 오버헤드 | 10ms | ___ | k6 (이슈 GET 권한 검증) |
| 감사 로그 보존 | 1년 | ___ | DB 정책 확인 |
| WCAG 2.1 AA (로그인 페이지) | 0 violations | ___ | axe-core |
| 비밀번호 해싱 (Argon2id) | memory=64MB | ___ | DEVELOPMENT.md §1.1 |
| Trivy + Dependabot | 0 high/critical | ___ | 의존성 스캔 |

### BC 완료 조건

- [ ] §2 (FR-AU 10개) 모두 `[x]` 마킹
- [ ] §3 (FR-MF 5개) 모두 `[x]` 마킹
- [ ] §4 (FR-PM 7개) 모두 `[x]` 마킹
- [ ] §NFR 측정표 모든 항목 임계 통과
- [ ] OWASP Top 10 자가 점검 (`docs/adr/<date>-identity-owasp-audit.md`)
- [ ] CHANGELOG.md 정리 (BC 단위 변경 요약)
- [ ] README.md §7 변경 이력에 "identity-access BC 완료 — YYYY-MM-DD" 추가
- [ ] Maxi 1인 선언 — "identity-access BC 완료"
