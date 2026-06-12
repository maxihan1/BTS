<!-- FR-MF-04 MFA 강제 정책의 기술 스펙 — 시나리오·FR/NFR·API·데이터모델·엣지케이스 -->
# FR-MF-04 — MFA 강제 정책 (관리자 + 민감 프로젝트) — 스펙

> slug: fr-mf-04-mfa-require-2fa · type: auth · BC: identity-access(주) + issue-tracking + shared-kernel
> SDD §19.7.2 · 선행 FR-MF-01(TOTP)/FR-MF-02(백업코드) · ADR `2026-06-12-mfa-enforcement-policy.md`

## 한 줄 요약

관리자(SYSTEM_ADMIN)와 민감 프로젝트(`require_2fa=true`) 멤버에게 MFA를 의무화한다. 강제 대상이 MFA를 아직 설정하지 않았으면 access JWT 클레임 `mfa_enrollment_required=true`가 박히고, 백엔드 게이트가 그 클레임을 읽어 MFA 등록/whoami/logout/refresh 외 모든 API를 차단한다. whoami도 같은 클레임을 노출해 게이트와 항상 일치한다.

## 핵심 결정 반영 (Maxi 2026-06-12)

- **게이트 평가 = JWT 클레임 + 짧은 TTL.** 로그인/refresh 시점에 `mfaEnrollmentRequired`를 계산해 access JWT 클레임 `mfa_enrollment_required`에 박는다. 게이트는 매 요청 이 클레임만 읽는다(DB 조회 0). require_2fa 토글/관리자 지정은 **다음 토큰 refresh(access TTL 범위, ≤ access JWT 만료)까지 반영 지연** — 의도된 trade-off.
- **토글 권한 = SYSTEM_ADMIN 전용.** 프로젝트 리드/PROJECT_ADMIN은 require_2fa를 켤 수 없다.
- **등록 → refresh 흐름.** 강제 대상이 MFA를 활성화하면 현재 토큰의 클레임은 아직 true다. 클라이언트가 `POST /api/v1/auth/refresh`(allow-list)로 새 토큰을 받으면 클레임이 false로 갱신되어 게이트가 풀린다.

## 사용자 시나리오 (Given-When-Then)

### S1. 관리자가 MFA 미설정 상태로 로그인
- **Given** 사용자 A가 SYSTEM_ADMIN이고 MFA(TOTP)를 설정하지 않았다.
- **When** A가 정상 비밀번호로 로그인한다.
- **Then** 로그인은 성공해 세션을 받는다(회귀 0). whoami는 `mfaEnrollmentRequired=true`를 반환한다. A가 `/api/v1/auth/mfa/**`, whoami, logout 외 다른 API를 호출하면 **403**(또는 정의된 게이트 상태)을 받는다.

### S2. 관리자가 MFA를 설정하면 게이트 해제
- **Given** S1 상태의 A.
- **When** A가 `/api/v1/auth/mfa/totp/setup` → `/totp/enable`로 TOTP를 활성화하고, `POST /api/v1/auth/refresh`로 새 access 토큰을 받는다.
- **Then** 새 토큰의 `mfa_enrollment_required` 클레임이 false(이제 `isEnabled=true`)가 되어 게이트가 풀리고 whoami `mfaEnrollmentRequired=false`. 모든 API 정상 접근. (refresh 전까지는 기존 토큰 클레임으로 게이트 유지 — EC6.)

### S3. 민감 프로젝트 멤버 강제
- **Given** 사용자 B가 일반 사용자지만, `require_2fa=true`인 프로젝트 P의 멤버다. B는 MFA 미설정.
- **When** B가 로그인한다.
- **Then** whoami `mfaEnrollmentRequired=true`. B는 MFA 등록 전까지 게이트로 차단된다.

### S4. 일반 사용자(강제 대상 아님)는 영향 없음
- **Given** 사용자 C가 관리자가 아니고, 어떤 민감 프로젝트에도 속하지 않으며, MFA 미설정.
- **When** C가 로그인한다.
- **Then** whoami `mfaEnrollmentRequired=false`. 모든 API 정상 접근(기존 동작 그대로, 회귀 0).

### S5. 이미 MFA를 켠 강제 대상은 무영향
- **Given** 관리자 D가 이미 TOTP를 활성화했다.
- **When** D가 로그인(2단계 포함) 후 API 호출.
- **Then** `mfaEnrollmentRequired=false`. 정상 접근.

### S6. 프로젝트 관리자가 프로젝트를 민감으로 표시
- **Given** 프로젝트 P의 프로젝트 관리자(또는 시스템 관리자) E.
- **When** E가 토글 엔드포인트로 P의 `require_2fa`를 true로 설정한다.
- **Then** P의 모든 멤버는 다음 whoami 평가부터 강제 대상이 된다. 권한 없는 사용자가 토글을 호출하면 403.

### S7. 민감 → 비민감으로 되돌림
- **Given** P가 `require_2fa=true`이고 멤버 B가 이로 인해 강제 대상.
- **When** E가 P의 `require_2fa`를 false로 되돌리고, B가 관리자도 아니고 다른 민감 프로젝트 멤버도 아니다.
- **Then** B의 `mfaEnrollmentRequired=false`로 즉시 복귀(영속 상태 없이 매 요청 재계산).

## 기능 요구사항 (FR)

- **FR1** `MfaEnforcementPolicy`는 사용자가 MFA 강제 대상인지 판정한다: `isSystemAdmin(user) OR (user의 프로젝트 멤버십 중 require_2fa=true 프로젝트 존재)`. 단일 서비스로 일원화(게이트·whoami·토큰 발급이 같은 계산 사용 — drift 차단).
- **FR2** access JWT 발급 시점(login의 `issueTokens`, `mfa/verify` 후, `refresh`)에 `mfaEnrollmentRequired = mfaRequired AND NOT mfaService.isEnabled(user)`를 계산해 클레임 `mfa_enrollment_required`로 박는다.
- **FR3** 백엔드 게이트(Spring Security 필터/인터셉터)는 인증된 요청의 JWT 클레임 `mfa_enrollment_required=true`이면 allow-list(아래 §게이트 allow-list) 외 모든 엔드포인트를 403으로 차단한다. 클레임 부재(기존 토큰·PAT)는 false로 간주(회귀 0).
- **FR4** whoami(JWT 분기)는 `mfaEnrollmentRequired`를 **JWT 클레임에서** 읽어 노출한다(게이트와 동일 출처 → 항상 일치). PAT 분기는 항상 false.
- **FR5** issue-tracking `projects`에 `require_2fa` 컬럼을 추가하고, **SYSTEM_ADMIN 전용** 토글 엔드포인트를 제공한다(SystemPermissionResolver 게이트).
- **FR6** shared-kernel에 `SensitiveProjectResolver` 포트를 정의하고 issue-tracking이 구현(adapter)을 제공한다. identity-access가 소비한다.
- **FR7** 로그인 흐름(`login`, `mfa/verify`) 시그니처는 변경하지 않는다(회귀 0). 강제는 토큰 클레임 + 게이트 레이어에서만 작동한다. 클레임 추가는 응답 바디·시그니처 무변경(JWT 내부).

## 비기능 요구사항 (NFR)

- **NFR1 (회귀 0)** 기존 FR-MF-01/02 로그인·검증·세션 흐름, 기존 통합/E2E 테스트 모두 그린 유지.
- **NFR2 (보안 — fail-safe)** `SensitiveProjectResolver` prod 빈 부재 시 fail-open 금지. 빈 미존재가 곧 '민감 아님'으로 귀결되어 강제가 무력화되면 안 됨 → non-null 빈 + 부팅 가드(또는 안전 기본값). 게이트 allow-list 누락으로 강제 대상이 MFA 설정조차 못 하는 영구 락도 금지(양방향 테스트).
- **NFR3 (성능)** whoami는 매 호출 평가 — 멤버십 조회 1회 + resolver 1회. N+1 금지. 멤버십 project_id 집합을 한 번에 resolver로 질의.
- **NFR4 (BC 격리)** identity-access는 issue-tracking을 직접 import하지 않는다. shared-kernel 포트만 의존.

## API 인터페이스 (REST)

### 변경: `GET /api/v1/users/me/whoami`
응답 DTO `WhoamiResponse`에 `mfaEnrollmentRequired: Boolean` 필드 추가.
```jsonc
{
  "username": "alice", "email": "...", "authMethod": "jwt", "userId": "...",
  "mustChangePassword": false, "isSystemAdmin": true,
  "mfaEnrollmentRequired": true   // 신규
}
```

### 신규: access JWT 클레임 `mfa_enrollment_required`
- 발급처: `JwtIssuer`(login `issueTokens` + `mfa/verify` 후 + `refresh` 재발급). 값은 `MfaEnforcementPolicy` 계산.
- 소비처: 게이트 필터(차단 판정) + whoami(노출).
- 기존 토큰 호환: 클레임 부재 시 false(회귀 0).

### 신규: 프로젝트 민감 토글 (issue-tracking)
`PATCH /api/v1/projects/{key}/require-2fa` (정확한 경로는 plan에서 기존 project web layer 컨벤션에 맞춤)
- body: `{ "requireTwoFactor": true }`
- 권한: **SYSTEM_ADMIN 전용**(SystemPermissionResolver). 그 외 403.
- 응답: 갱신된 프로젝트 설정.

### 게이트 동작 (신규 cross-cutting)
- `mfaEnrollmentRequired=true`인 인증 사용자가 allow-list 외 엔드포인트 호출 시 **403** + 명확한 에러 코드(예: `mfa_enrollment_required`). 응답에 내부 상세 누출 금지(권한 Guard 메시지 누출 선례 회피).

### 게이트 allow-list (차단 예외)
- `POST/GET/DELETE /api/v1/auth/mfa/**` (MFA 등록·상태·백업코드)
- `GET /api/v1/users/me/whoami`
- `POST /api/v1/auth/logout`
- `POST /api/v1/auth/refresh` (등록 중 세션 유지)
- (로그인/검증 `POST /api/v1/auth/login`·`/auth/mfa/verify`는 pre-session이라 게이트 무관이나 allow-list에 포함해도 무해)

## 데이터 모델 변경

- **issue-tracking** — `projects.require_2fa BOOLEAN NOT NULL DEFAULT false` 추가.
  - 마이그레이션: issue-tracking 네임스페이스 다음 V번호(머지 직전 재확인 — V번호 충돌 선례).
  - **jOOQ `init_codegen.sql` 미러 필수**(issue-tracking은 코드 생성 모듈).
- **identity-access** — 새 테이블/컬럼 **없음**. `mfaEnrollmentRequired`는 계산값. access JWT 클레임 `mfa_enrollment_required`로만 운반(영속 0).

## 엣지 케이스

- **EC1 (PAT)** PAT 인증은 `mfaEnrollmentRequired=false` 고정(봇 컨텍스트, mustChangePassword/isSystemAdmin과 동일 정책).
- **EC2 (SSO 사용자)** TOTP는 provider 무관(MFA는 BTS 자체 TOTP). SSO 관리자도 강제 대상이며 BTS TOTP를 설정해야 함.
- **EC3 (멤버십 0)** 프로젝트 멤버십이 없고 관리자도 아니면 강제 아님.
- **EC4 (다중 프로젝트)** 여러 프로젝트 중 하나라도 민감이면 강제(`anyRequiresMfa`).
- **EC5 (관리자이면서 미설정)** 관리자라도 게이트로 차단됨 — 단, MFA 등록 경로는 열려 있어 스스로 해제 가능(영구 락 아님).
- **EC6 (등록 후 게이트 해제)** 강제 대상이 MFA 활성화 직후엔 현재 토큰 클레임이 여전히 true → 게이트 유지. `POST /auth/refresh`(allow-list)로 새 토큰을 받으면 클레임 false → 해제. 클라이언트(D6)는 enrollment 성공 후 refresh를 트리거해야 함.
- **EC7 (평가 단일 출처)** 토큰 발급·게이트·whoami가 모두 `MfaEnforcementPolicy` + 토큰 클레임이라는 단일 출처를 사용(평가 로직 중복 시 drift). whoami는 라이브 재계산하지 않고 클레임을 읽음 → 게이트와 100% 일치.
- **EC8 (소프트 삭제 프로젝트)** `deleted_at IS NOT NULL` 프로젝트는 멤버십·민감 평가에서 제외(resolver/멤버십 조회 시 활성 프로젝트만).
- **EC9 (이미 mfa_verified 세션)** 강제 판정은 'MFA 설정 여부(isEnabled)' 기준이지 '이번 세션 2단계 통과(mfa_verified)' 기준이 아님 — 설정만 되어 있으면 강제 아님(2단계 자체는 로그인 시 FR-MF-01이 처리).
- **EC10 (require_2fa 변경 지연)** 관리자 지정/require_2fa 토글은 다음 refresh까지 반영 지연(JWT 클레임 + 짧은 TTL의 의도된 결과). 즉시 강제가 필요하면 세션 강제 종료(기존 revoke)로 재로그인 유도 — 이 PR 범위 밖.

## 제약 조건

- 로그인/검증 엔드포인트 시그니처·흐름 불변(NFR1).
- cross-BC는 shared-kernel 포트 경유, 직접 import 금지(NFR4).
- 게이트는 Spring Security 필터 체인/인터셉터로 인증 **후** 동작(미인증 요청은 기존대로 401).
- 프론트 게이팅 UI + Playwright E2E는 이 PR 범위 아님(D6/D7 후속).

## 측정 가능한 완료 기준

1. `MfaEnforcementPolicy` 단위 테스트: 관리자/민감멤버/일반/이미설정 4분기 + EC3/EC4 커버.
2. whoami 통합 테스트: 강제 대상 미설정 → `mfaEnrollmentRequired=true`, 설정 후 false, PAT false.
3. 게이트 통합 테스트(양방향): 강제 대상 미설정 사용자가 (a) MFA 등록/whoami/logout = 통과, (b) 임의 보호 API = 403. 비강제/이미설정 사용자는 모든 API 통과(회귀 0).
4. 토글 엔드포인트 통합 테스트: 프로젝트 관리자 = 200, 비권한 = 403, 토글 후 멤버 강제 반영.
5. `SensitiveProjectResolver` resolver 통합 테스트: require_2fa=true 프로젝트 멤버십 → true, false/미존재 → false, fail-safe 빈 부재 가드.
6. 기존 백엔드 전체 테스트 + 기존 E2E 그린(회귀 0).
7. issue-tracking 마이그레이션 + init_codegen 미러 정합(부팅·codegen 통과).

## Brainstorming Check

✅ 통과 (1회 iteration). gap 2건 발견 → Maxi 결정 후 스펙 반영.
- GAP-1 (게이트 매 요청 평가 성능) → JWT 클레임 + 짧은 TTL 채택. 등록→refresh 흐름 명문화(EC6/EC10).
- GAP-2 (토글 권한 모호: ProjectLead vs SYSTEM_ADMIN) → SYSTEM_ADMIN 전용 채택.
- 부수 확인: 게이트는 전 모듈 단일 SecurityFilterChain에 등록(앱 조립 plan에서 확인). whoami 클레임 추가는 프론트에 additive(D6에서 Zod/소비 갱신).
