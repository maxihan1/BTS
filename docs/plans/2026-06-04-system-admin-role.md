# 전역 시스템 관리자 역할 + 전역 권한 인프라

> slug: system-admin-role
> type: auth
> agent: security-engineer
> 생성: 2026-06-04

## Brief

SDD 12.6 OrgAdmin/시스템 권한(12.3 ADMIN_SYSTEM 등)의 실제 구현. 현재 BTS는 프로젝트 단위 권한
(PROJECT_ADMIN/MEMBER)만 있고 전역(시스템/조직) 관리자 역할이 데이터·JWT·판정 어디에도 부재.

**범위 (Maxi 2026-06-04 — 인프라만)**:
1. 사용자 전역 역할 저장 (users 전역역할 컬럼 or 시스템역할 테이블)
2. JWT 토큰에 전역 역할/권한 클레임 추가
3. 시스템 권한코드(ADMIN_SYSTEM 등) 전역 판정 인프라
4. 최초 시스템 관리자 부트스트랩

**범위 제외**: 회원가입(FR-AU-05 소관, 후속) · 전역 워크플로우 스킴 관리(FR-PM-04, 후속).
이 인프라가 두 작업의 공통 선행을 해소한다.

**선행 해소 대상**: FR-PM-04(전역 MANAGE_WORKFLOW 판정) · FR-AU-05 회원가입(관리자 계정 생성).
**Jira Cloud 모델**: 워크플로우 스킴 등 전역 자원은 사이트/전역 관리자가 관리.

## 도메인 정리 (← /bts-domain 채움)

- **BC**: identity-access (전역 역할/권한은 인증 BC 소유, 프로젝트 권한과 동일)
- **새 엔티티**: `SystemRoleAssignment` (사용자↔전역 역할, project_id 없음 — 프로젝트 멤버십과의 핵심 차이)
- **새 enum**: `SystemRole`(현재 `SYSTEM_ADMIN` 1종). `ProjectRole`과 **분리** — 프로젝트 역할과 전역 역할은 다른 축.
- **새 포트**: 전역 권한 판정기 (shared-kernel `com.bts.shared.permission`). 여러 BC가 의존할 공용 기반.
- **영향 기존 코드**: `JwtIssuer`(전역 역할 클레임 추가) · JWT converter(authority 변환) · `users` 테이블(V001, FK 대상).
- **새 용어** (glossary 추가 대기, Maxi 승인 필요):
  - **전역 역할 / 시스템 역할** (System Role) — 프로젝트와 무관하게 시스템 전체에 적용되는 역할. 현재 `SYSTEM_ADMIN` 1종.
  - **시스템 관리자** (System Admin) — `SYSTEM_ADMIN` 전역 역할 보유자. SDD 12.6 OrgAdmin의 단일 역할 구현.
- **기존 결정 관계**:
  - FR-PM-01 ADR이 stale 폐기한 SDD 12.6 8종 역할 중 OrgAdmin을 `SYSTEM_ADMIN`으로 부분 복원.
  - 메모리 `issue-scope-global-prod-hard-deny`(IssueScope.Global prod 무조건 거부)의 정공 해소 토대 — 단 실결선은 FR-PM-04.
- **관련 ADR**: [docs/decisions/2026-06-04-system-admin-role.md](../decisions/2026-06-04-system-admin-role.md) (생성됨, D1~D6)
- **Maxi 결정 (2026-06-04, AskUserQuestion)**:
  - D1 저장 = 별도 테이블 `system_role_assignments` (users 컬럼 기각)
  - D5 부트스트랩 = 설정값(`bts.bootstrap.admin-username`) 기반 멱등 승격 (마이그레이션 고정 INSERT 기각)
  - D6 범위 = 순수 토대 + 통합테스트 검증 (IssueScope.Global 실결선은 FR-PM-04)

## 스펙

전체 스펙. [docs/specs/2026-06-04-system-admin-role.md](../specs/2026-06-04-system-admin-role.md)

핵심 시나리오 4줄 요약.
- 앱 기동 시 `bts.bootstrap.admin-username` 설정값의 사용자를 SYSTEM_ADMIN으로 멱등 승격 (이미 있으면 skip)
- SYSTEM_ADMIN 보유자 로그인 시 JWT에 `roles=["SYSTEM_ADMIN"]` 클레임 + `ROLE_SYSTEM_ADMIN` authority
- 전역 판정기(shared-kernel 포트)가 시스템 관리자 여부 판정 — FR-PM-04 등 후행이 소비
- 신규 REST 엔드포인트 없음(토대만), 실 동작은 FR-PM-04·FR-AU-05

산출물. V010 마이그레이션 · `SystemRole`/`SystemRoleAssignment` · Repository · `SystemPermissionResolver`(포트+구현) · `JwtIssuer` 클레임 확장 · 부트스트랩 `ApplicationRunner` · 통합테스트.

## Brainstorming Check

✅ 통과 (적대적 self-review, office-hours 스킵 — 정의된 FR 작업이라 부적합, 메모리 `bts-spec-office-hours-mismatch`).
- 보강: 감사 로그(audit FR-AU-10 후속, 현재 로그만) · PAT 전역역할 제외(EC7) · 부여 경로 부트스트랩 한정.
- plan-review 위임 갈림길: 전역 판정기 시그니처(`isSystemAdmin` vs 권한코드 기반 `hasSystemPermission`) — FR-PM-04 사용성과 직결.

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
