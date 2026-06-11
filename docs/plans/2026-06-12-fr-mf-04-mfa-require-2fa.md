# FR-MF-04 — MFA 강제 정책 (관리자 + 민감 프로젝트)

> slug: fr-mf-04-mfa-require-2fa
> type: auth
> agent: security-engineer
> 생성: 2026-06-12

## Brief

FR-MF-04 — MFA(Multi-Factor Authentication) 강제 정책. SDD §19.7.2 기준.
- 모든 관리자: 강제
- 민감 프로젝트 멤버 (`project.require_2fa = true`): 강제
- 일반 사용자: 권장
- 외부 협력사: 강제 검토

BC: identity-access. 선행 FR-MF-01(TOTP)/FR-MF-02(백업 코드) 완료 위에 enforcement 레이어 추가.

## 도메인 정리

### 결정 사항 (Maxi 확인 — 2026-06-12)

| # | 갈림길 | 선택 | 이유 |
|---|---|---|---|
| D1 | 강제 대상 범위 | **관리자(SYSTEM_ADMIN) + 민감 프로젝트 멤버** | SDD §19.7.2 '필수' 정의. 외부 협력사('강제 검토'=미확정)는 후속 |
| D2 | `require_2fa` 위치 + cross-BC | **issue-tracking `projects` 테이블 + shared-kernel `SensitiveProjectResolver` 포트** | 프로젝트 설정의 자연스러운 자리. 기존 `SystemPermissionResolver` 선례와 동일 |
| D3 | enforcement 차단 방식 | **whoami `mfaEnrollmentRequired` 플래그 + 백엔드 게이트** | FR-AU-05 `mustChangePassword` 선례. 단, FR-AU-05가 미룬 백엔드 전역 차단도 이번엔 포함(보안 정합) |
| D4 | PR 범위 | **백엔드 먼저** | FR-MF-01/02 선례. 프론트 게이팅 UI + E2E는 D6/D7 후속 PR |

### BC 경계 (이 PR이 3개 모듈을 걸침 — Maxi 확인 필요)

CLAUDE.md '한 PR = 한 BC' 원칙의 예외. cross-BC resolver 패턴(FR-PM-06/07 선례)으로 처리.

- **issue-tracking** — `projects.require_2fa` 컬럼 추가(마이그레이션 + jOOQ `init_codegen.sql` 미러 필수) + 프로젝트 관리자용 토글 엔드포인트 + `SensitiveProjectResolver` 구현(adapter).
- **shared-kernel** — `SensitiveProjectResolver` 포트(`com.bts.shared.permission`, UUID 시그니처, identity 도메인 타입 미참조 — 역의존 차단).
- **identity-access** — `MfaEnforcementPolicy` 평가 + whoami `mfaEnrollmentRequired` 노출 + 백엔드 게이트(미등록 강제 대상 차단). **새 마이그레이션 불필요**(상태는 `mfaService.isEnabled` + 정책 계산으로 도출, 영속 상태 0).

### 영향 엔티티 / 포트

- `Project` (issue-tracking) — `require_2fa: Boolean` 신규 필드.
- `ProjectMembership` (identity-access, V007) — 기존. 멤버십 조회로 '민감 프로젝트 소속' 판정.
- `SystemRole`/`SystemPermissionResolver` (identity-access) — 기존. 관리자 판정 재사용.
- `MfaService.isEnabled(userId)` (identity-access, FR-MF-01) — 기존. '등록 여부' 판정.
- `SensitiveProjectResolver` (shared-kernel, **신규 포트**) — issue-tracking이 impl 제공.
- `WhoamiResponse` (identity-access) — `mfaEnrollmentRequired: Boolean` 필드 추가.

### 정책 평가 규칙

```
mfaRequired(user)        = isSystemAdmin(user) OR memberOfSensitiveProject(user)
mfaEnrolled(user)        = mfaService.isEnabled(user)           // FR-MF-01 TOTP 활성
mfaEnrollmentRequired    = mfaRequired(user) AND NOT mfaEnrolled(user)
```

게이트: `mfaEnrollmentRequired=true`인 요청은 enrollment 관련 API + whoami + logout 외 전부 차단.

### 새 용어 (glossary 갱신 대기 — Maxi 승인 필요)

- **MFA 강제 정책 (MFA enforcement policy)** — 특정 사용자군(관리자·민감 프로젝트 멤버)에게 2FA 설정을 의무화하는 규칙.
- **민감 프로젝트 (sensitive project)** — `require_2fa=true`로 표시된 프로젝트. 멤버는 MFA 강제 대상.
- **MFA enrollment 강제 (mfaEnrollmentRequired)** — 강제 대상이지만 아직 MFA 미설정 → 등록 완료 전까지 다른 기능 접근 차단되는 상태.

### 기존 결정 충돌

- 없음. FR-MF-01/02(opt-in MFA) 위에 강제 레이어를 더하는 확장. 기존 로그인 흐름 회귀 0 목표.
- BC 격리 원칙은 resolver 패턴으로 준수(직접 import 0, shared-kernel 포트 경유).

### 관련 ADR

- [docs/decisions/2026-06-12-mfa-enforcement-policy.md](../decisions/2026-06-12-mfa-enforcement-policy.md) (이 PR에서 생성)

## 스펙

전체 스펙. [docs/specs/2026-06-12-fr-mf-04-mfa-require-2fa.md](../specs/2026-06-12-fr-mf-04-mfa-require-2fa.md)

핵심 시나리오 3줄 요약.
- 관리자/민감프로젝트 멤버가 MFA 미설정으로 로그인하면 access JWT 클레임 `mfa_enrollment_required=true` → 게이트가 MFA등록/whoami/logout/refresh 외 전부 403 차단.
- MFA 활성화 후 `/auth/refresh`로 토큰 갱신하면 클레임 false → 게이트 해제 (등록→refresh 흐름).
- require_2fa는 SYSTEM_ADMIN만 토글, issue-tracking projects 컬럼, SensitiveProjectResolver 포트로 cross-BC 평가.

추가 결정 (Maxi 2026-06-12).
- 게이트 평가 = JWT 클레임 + 짧은 TTL (매 요청 DB 0, require_2fa 변경은 다음 refresh까지 지연).
- 토글 권한 = SYSTEM_ADMIN 전용.

## Brainstorming Check

✅ 통과 (1회 iteration, gap 2건 발견 후 Maxi 결정 반영 — 게이트 평가 방식 + 토글 권한).

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
