# FR-PM-06 — 이슈 보안 수준 (Issue Security Level)

> slug: fr-pm-06-issue-security-level
> type: auth
> agent: security-engineer
> primary_bc: identity-access
> 생성: 2026-06-05

## Brief

FR-PM-06 이슈 보안 수준. 이슈마다 보안 등급(IssueSecurityLevel: 이름·설명·허용 역할 목록)을
지정하고, VIEW_ISSUE 권한을 통과해도 그 이슈의 보안 수준을 통과하지 못하면 못 보게 하는
추가 차단 계층. FR-PM-05(Browse/View 분리)의 직속 후속.

- SDD 정본: docs/sdd/12-permissions.md §12.4
- plan 추적: docs/plan/product/identity-access.md §4.6
- 단계: D1 도메인 / D2 명세 / D3 데이터모델(security_levels, issues.security_level_id) /
  D4 백엔드 가드 / D5 백엔드 테스트 / D6 프론트 UI / D7 E2E

## 도메인 정리 (← /bts-domain 채움)

### ⏸️ 보류 (2026-06-05) — 그룹 인프라 선행 필요

도메인 grill 중 Maxi 결정으로 **FR-PM-06 보류**. 이유와 결정 내역을 기록(재개 시 컨텍스트 보존).

**Maxi 결정 (2026-06-05 도메인 grill)**
- 허용 명단 모델 + Reporter/Assignee 예외 = **Jira Cloud 방식 그대로**.
- 작업 범위 = 백엔드 먼저 D1~D5 (UI/E2E는 후속).
- 멤버 타입 = **그룹(Group) 기반**을 원함 → 그러나 BTS에 그룹 인프라 전무.
- 최종 결정 = **사용자 그룹 FR을 먼저 등재·구현하고, FR-PM-06은 그 뒤로 보류**.
  PR #86 + worktree는 **보류 상태로 유지**(폐기 안 함).

**Ground-truth (재개 시 출발점)**
- `IssuePermission`(shared-kernel, 7종: BROWSE/VIEW/CREATE/UPDATE/TRANSITION/SOFT_DELETE/HARD_DELETE)
  + `IssueScope`(Global/Project/Issue). 카운트 가드: shared-kernel `IssueScopeTest`(hasSize(7)).
- prod 판정기 `IdentityAccessIssuePermissionResolver`(@Profile prod): 이슈 단건 스코프에서
  **키 접두사로 projectId만 해석**, 이슈 데이터(보안수준 값)는 안 읽음 → per-issue 판정 시
  이슈의 security_level_id를 cross-BC로 읽는 경로 신설 필요.
- non-prod `AlwaysAllowIssuePermissionResolver`(@Profile !prod, 항상 true).
- 권한 검사: `IssueApplicationService.assertPermission` / `assertViewIssueOrNotFound`(404 존재숨김).
  listIssues=BROWSE/Project, findByKey=VIEW/Issue.
- listIssues jOOQ 필터: `IssueRepository.listWithType` (activeInProject 조건) — per-issue 술어 추가 지점.
- 역할 모델: `ProjectRole`(PROJECT_ADMIN/MEMBER 2종)뿐. SDD §12.4 예시("임원만") 표현 불가.
- 권한 시드: V008/V009/V013/V014, `PermissionSchemaMigrationTest` count=12. 다음 V번호=V015(주의: 동시 브랜치).
- issues 테이블: security_level 비슷한 컬럼 없음(신설 필요). init_codegen.sql 미러 필수.
- 그룹: `user_external_accounts.groups`(LDAP 동기화 JSON 문자열 목록)만 존재. 진짜 그룹 엔티티/
  멤버십/CRUD 없음. 로컬 가입 사용자는 그룹 0.

**재개 설계 방향 (그룹 FR 완료 후)**
- 멤버 타입을 `member_type` + `member_value` **다형**으로 설계 → 그룹은 GROUP 타입 한 줄 추가로 수용.
  이번 가능 타입: 특정 사용자 / 프로젝트 역할 / 보고자 / 담당자.
- security_levels(프로젝트별 등급) + issues.security_level_id + 다형 멤버 테이블.
- VIEW_ISSUE 매트릭스 통과 **AND** 보안수준 멤버십 통과 → 미통과 시 404(FR-PM-05 D2 일관).

### 관련

- 선행 ADR: docs/decisions/2026-06-05-issue-browse-view-permission.md (FR-PM-05 §D4가 본 FR로 위임)
- 후속 의존: 신규 "사용자 그룹" FR (먼저 등재·구현)

## 스펙 (← /bts-spec Phase A 채움)

(보류 — 그룹 FR 완료 후 재개)


## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
