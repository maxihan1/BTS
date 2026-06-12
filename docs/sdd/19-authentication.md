# 19. 인증 시스템

## 19.1 설계 목표

엔터프라이즈 환경의 실제 요구를 반영:
- LDAP/AD (대기업 표준)
- SAML SSO (자회사 통합)
- OIDC (Google/Microsoft Workspace)
- 로컬 계정 (외부 협력사)
- OAuth 소셜 로그인 (옵션)
- **여러 Provider를 동시 운영**

## 19.2 플러그형 AuthenticationProvider

```kotlin
interface AuthenticationProvider {
    val id: String                  // "ldap-corp", "saml-acme"
    val displayName: String         // "회사 계정"
    val type: ProviderType          // LDAP/SAML/OIDC/LOCAL/OAUTH
    val priority: Int               // 라우팅 우선순위

    fun authenticate(credentials: Credentials): AuthResult
    fun resolveUser(externalId: String): UserIdentity?
    fun supportsAutoProvisioning(): Boolean
    fun supports2FA(): Boolean
    fun supportedScopes(): Set<String>
}
```

## 19.3 도메인 기반 라우팅

```kotlin
@Service
class AuthRouter {
    fun pickProvider(emailOrUsername: String): AuthenticationProvider {
        val domain = emailOrUsername.substringAfter('@', "")

        // 1. 도메인 매핑 우선
        domainProviderMap[domain]?.let { return it }

        // 2. 사용자명 패턴 (예: "ext-" prefix → Local)
        if (emailOrUsername.startsWith("ext-")) {
            return localProvider
        }

        // 3. 기본 Provider
        return defaultProvider
    }
}
```

예시 설정:
```yaml
auth:
  domain-routing:
    "corp.com": ldap-corp
    "subsidiary.com": saml-subsidiary
    "external.com": oidc-google
  default-provider: ldap-corp
```

## 19.4 계정 통합 (Account Linking)

한 사용자가 여러 Provider로 로그인 가능. UserIdentity 1:N으로 매핑.

```kotlin
data class UserIdentity(
    val id: Long,
    val userId: Long,           // Atlas User
    val providerId: String,     // "ldap-corp"
    val externalId: String,     // LDAP DN, SAML NameID 등
    val email: String?,
    val verifiedAt: Instant,
)
```

같은 이메일이 여러 Provider에 있으면 자동 link 또는 수동 link 옵션.

## 19.5 세션 관리

| 토큰 | 만료 | 저장 |
|---|---|---|
| Access JWT | 15분 | sessionStorage (XSS 대비) |
| Refresh Token | 14일 | HttpOnly Cookie |
| Session (DB) | 14일 | session 테이블 |
| Personal Access Token | 사용자 지정 (최대 1년) | DB SHA-256 해시 |

JWT 클레임:
- `sub` - userId
- `email` - 이메일
- `roles` - 역할
- `sid` - session ID (revoke용)
- `kid` - 키 ID (rotation 대비)
- `mfa_verified` - 2FA 통과 여부

## 19.6 패스워드 정책 (Local만)

- 최소 12자
- 영문 대소문자 + 숫자 + 특수문자 중 3종
- Argon2id 해시 (memory=64MB, iterations=3, parallelism=4)
- 마지막 5개 재사용 금지
- 90일 만료 (선택)
- 연속 5회 실패 시 5분 잠금

## 19.7 2FA (Multi-Factor Authentication)

### 19.7.1 지원 방식

| 방식 | 우선순위 | 비고 |
|---|---|---|
| TOTP (Authenticator 앱) | 필수 | RFC 6238, Google Authenticator 호환 |
| 백업 코드 | 필수 | 10개, 1회용, 해시 저장 |
| WebAuthn (Passkey) | 선택 | Phase 4 |

### 19.7.2 강제 정책

- 모든 관리자: 강제
- 민감 프로젝트 멤버: 강제 (`project.require_2fa = true`)
- 일반 사용자: 권장
- 외부 협력사: 강제 검토

**구현 (FR-MF-04, PR #123)**. `require_2fa` 컬럼은 issue-tracking `projects`(V020). 평가는 shared-kernel `SensitiveProjectResolver` 포트 경유(BC 격리). whoami `mfaEnrollmentRequired` 필드 + 백엔드 게이트(미등록 강제 대상 차단). ADR [2026-06-12-mfa-enforcement-policy](../decisions/2026-06-12-mfa-enforcement-policy.md).

### 19.7.3 신뢰 디바이스 (FR-MF-05)

- 2FA 통과 후 "이 기기 30일 면제" 옵션
- 디바이스 핑거프린트 + JWT 클레임
- 분실/해킹 의심 시 모든 신뢰 디바이스 즉시 만료

### 19.7.4 설정 흐름

```
사용자 → 보안 설정 → 2FA 활성화
  → QR 코드 표시 (otpauth://)
  → 사용자 Authenticator 앱으로 스캔
  → 6자리 코드 입력 검증
  → 백업 코드 10개 표시 + 다운로드
  → 활성화 완료
```

## 19.8 LDAP 통합

### 19.8.1 사용자 동기화

- 매 4시간 백그라운드 동기화
- 사용자 추가/제거/필드 변경 반영
- LDAP에서 삭제된 사용자 → Atlas SUSPENDED
- 그룹 멤버십 변경 → 역할 자동 조정

### 19.8.2 동기화 필드

| LDAP | Atlas |
|---|---|
| mail | email |
| cn | profile.display_name |
| sAMAccountName | username |
| department | profile.department |
| manager | profile.manager_id |
| memberOf | groups |

사용자가 편집 가능한 필드 (avatar, status, timezone)는 동기화 제외.

## 19.9 감사 로그

모든 인증/권한 이벤트 기록:

```kotlin
data class AuthAuditLog(
    val id: Long,
    val userId: Long?,
    val eventType: String,         // LOGIN_SUCCESS, LOGIN_FAILURE, MFA_SETUP, etc.
    val providerId: String?,
    val ipAddress: String?,
    val userAgent: String?,
    val deviceFingerprint: String?,
    val metadata: Map<String, Any>,
    val createdAt: Instant,
)
```

단순 테이블 + 인덱스 3종((user_id, created_at DESC) · (event_type) · (created_at)), 파티셔닝 없음. **append-only 영구 보존**(DATA.md §3 — 감사 로그 절대 삭제 금지). "1년 보존"은 최소 보존 floor로 해석하며 영구 보존이 충족한다. 파티셔닝 일탈 근거 — ADR [2026-06-10-auth-audit-log-persistence](../decisions/2026-06-10-auth-audit-log-persistence.md).

> 위 `data class AuthAuditLog` 표기의 `id: Long`/`userId: Long?` 은 원안 스케치다. 실제 구현은 PK `id: Long`(BIGINT IDENTITY)이되 `userId: UUID?`(users.id 가 UUID), `metadata: Map<String, String>`(JSONB), `createdAt: Instant` 이다(FR-AU-10).

> **관리자 조회 (FR-AU-10 D6/D7, PR #112).** `GET /api/v1/admin/auth-audit-logs` — SYSTEM_ADMIN 전용(`@PreAuthorize hasRole('SYSTEM_ADMIN')` + filter authenticated 이중가드, 비관리자/PAT 403). 필터 `eventType`·`userId`·`from`(>=)·`to`(<=) + offset 페이지네이션(`page`/`size`, 기본 50·1..100), 잘못된 파라미터 400. `users` LEFT JOIN 으로 주체(username/displayName) 표시(삭제/미상 사용자는 null — append-only 보존). count 는 JOIN 없이 단독 산출(cartesian 회피), 동적 WHERE 는 전부 named parameter(SQL 인젝션 방어). 프론트 `/admin/audit-logs`(SYSTEM_ADMIN 게이팅, Header 관리 메뉴 `isSystemAdmin` 노출).

## 19.10 보안 이벤트 알림

- 새 디바이스 로그인 → 사용자 이메일 알림
- 5회 실패 → 사용자 + 관리자 알림
- 비밀번호 변경 → 사용자 이메일 알림
- 2FA 비활성화 → 사용자 + 관리자 알림
- 의심 활동 (해외 IP) → 관리자 알림

## 19.11 다음 챕터

- 개인화 → [20. 개인화](20-personalization.md)
- 권한 모델 → [12. 권한 모델](12-permissions.md)
