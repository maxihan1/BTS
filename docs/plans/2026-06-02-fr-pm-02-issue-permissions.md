# FR-PM-02 이슈 등록/수정/삭제 권한 분리 (백엔드 슬라이스 D1~D5)

> slug: fr-pm-02-issue-permissions
> plan slug (FR 추적): identity/issue-permissions
> type: auth · agent: security-engineer
> primary BC: identity-access (소유) + issue-tracking (계약 타입 이동 + stub 배선)
> 생성: 2026-06-02 · 머지: PR #53

## Brief

역할(role) × 권한(permission)을 매트릭스로 정의해 이슈 등록/수정/삭제를 역할별로 분리 통제(Jira permission scheme 방식). FR-PM-01(ProjectMembership: PROJECT_ADMIN/MEMBER)이 선행 토대. 범위 — 백엔드 D1~D5만, 프론트 UI(D6)/E2E(D7)는 후속 PR.

## 도메인 정리

- BC: identity-access (소유) + issue-tracking (계약 타입 이동 + stub/profile 배선)
- 권한 저장/평가 모델: 풀 Jira식 permission_schemes + role_permissions + project_permission_scheme(연결표)
- 신규 엔티티: PermissionScheme, RolePermission. 재사용: ProjectRole, ProjectMembership(멤버 게이트), ProjectDirectory(scope 해석), IssuePermissionResolver/IssuePermission/IssueScope(공용 모듈 이전)
- 신규 어댑터: IdentityAccessIssuePermissionResolver(@Profile prod) — stub 교체
- 관련 ADR: [issue-permission-scheme-model](../decisions/2026-06-02-issue-permission-scheme-model.md) + [issue-permission-resolver-port 정정](../adr/2026-05-22-issue-permission-resolver-port.md)

### 결정 요약 (grill + Maxi 7결정)

- **G1** stub 교체까지 본 PR 흡수 (FR-AU-12 ≡ FR-PM-02). 표 + 리더기 연결 한 묶음. BC 격리 의식적 예외.
- **G2** 권한 어휘 2레이어 + adapter 통역. 포트 `CREATE` 유지, 명부 `CREATE_ISSUE` 정본, `when` exhaustive 컴파일 가드.
- **G3** 매트릭스 역할 축 = 현존 ProjectRole 2종(PROJECT_ADMIN/MEMBER). 동적 역할은 후속.
- **G4** 가드 = 포트 명시 호출 유지(@PreAuthorize 미도입).
- **G5** 범위 밖 권한(VIEW/TRANSITION/HARD_DELETE) = 프로젝트 멤버 게이트(비멤버 차단).
- **G6** 스킴 공유 = project_permission_scheme 연결표 포함. 미매핑은 기본 스킴 fallback.
- **G7** 배선 후보 B 확정(A 기각) — 계약 타입 shared-kernel `com.bts.shared.permission` 추출, 시그니처 ActorId→UUID(ActorId는 issue VO 잔류, 호출부 actor.value).

## 스펙

전체 스펙. [docs/specs/2026-06-02-fr-pm-02-issue-permissions.md](../specs/2026-06-02-fr-pm-02-issue-permissions.md)

- permission_schemes + role_permissions + project_permission_scheme(V008), 기본 스킴 시드(ADMIN={C,E,D}, MEMBER={C,E}).
- IdentityAccessIssuePermissionResolver(@Profile prod): 멤버 게이트(비멤버 false) → 범위 내 매트릭스 / 범위 밖 멤버통과.
- scope=Issue는 issueKey prefix→ProjectDirectory.resolveKeyToId(이슈 데이터 미접근). Global→false.
- 권한거부 403, advisory lock 불필요.

## Brainstorming Check

✅ 통과 (self sanity check). gap-1(NFR-4 회귀 0 모호) 해소 — 평가 로직(test=!prod 자동 0) vs 컴파일 레벨(B 이동) 분리. ActorId 처리는 옵션 b(UUID 수용)로 확정.

## Plan

> 배선 B: 계약을 shared-kernel로, prod adapter가 role_permissions 매트릭스 + 멤버 게이트 판정. issue-tracking은 import만 갱신(동작 무변경).

### Task 1. 권한 계약 타입을 shared-kernel로 추출 (배선 B)
- agent: backend-engineer. depends-on: []. 리팩토링 변형(회귀 0이 GREEN).
- IssuePermissionResolver/IssuePermission/IssueScope를 com.bts.shared.permission으로 이동, 시그니처 ActorId→UUID. ActorId는 issue 도메인 VO 잔류. AlwaysAllow stub @Profile(!prod) 유지. service 테스트 8개 mock 콜사이트 actor→actor.value 일괄 치환(리뷰 BLOCKER 반영). 검증 `:issue-tracking:test :shared-kernel:test` 회귀 0 + ArchUnit.

### Task 2. V008 마이그레이션 — 권한 스킴 스키마 + 기본 스킴 시드
- agent: db-engineer. depends-on: []. permission_schemes/role_permissions/project_permission_scheme + 기본 스킴 시드. per-class @Container 패턴. RED(스킴 조회 통합테스트)→GREEN(V008).

### Task 3. PermissionScheme repository — 유효 스킴 해석 + 권한 판정
- agent: security-engineer. depends-on: [2]. roleHasPermission(projectId, role, permissionCode): 유효 스킴(연결표 매핑→기본 fallback) EXISTS 스칼라 서브쿼리(cartesian 회피). NamedParameterJdbcTemplate, @Transactional(readOnly).

### Task 4. IdentityAccessIssuePermissionResolver adapter (@Profile prod)
- agent: security-engineer. depends-on: [1,3]. 멤버 게이트→범위 내 매트릭스/범위 밖 멤버통과. scope=Issue prefix 해석. when exhaustive 매핑. 단위테스트 MockK(EC-2 프로젝트없음 포함).

### Task 5. 권한 매트릭스 전수 통합테스트 (@ActiveProfiles prod)
- agent: security-engineer. depends-on: [4]. 13케이스 + 스킴 공유 + Bean 배타. Testcontainers singleton.

## Plan 메타
- task 수: 5. wave: w0={T1,T2} → w1={T3} → w2={T4} → w3={T5}.
- TDD 강제(T1은 리팩토링 변형). 추가 검증: detekt/ktlint, ArchUnit.

## 리뷰 결과

### eng 독립 리뷰 (code-reviewer dispatch, plan 단계) — autoplan 대신
- BLOCKER 1건(해소): Task 1 시그니처 변경 시 service 테스트 8개(44 콜사이트) + IssueScopeTest/IssueExceptionsTest/IssueExceptionHandlerTest 컴파일 에러 → file-list 누락. Task 1에 11개 테스트 파일 + actor→actor.value 일괄 치환 명시로 수정.
- CONCERN(반영): EC-2 음성 케이스 → Task 4 단위테스트 추가. CONCERN(수용): VIEW 멤버 통과 FR-PM-05 이연.

### PR 단위 코드리뷰 (PR #53)
- code-reviewer agent: ✅ PASS (절대규칙/배선B/매트릭스/SQL parameter binding/when exhaustive 검증). CONCERNS 2건(차단 아님): ① ArchUnit permission 패키지 미차단(방어 깊이) ② doc drift(로그/KDoc) + Global 테스트 부재.
- 구조/안전성 직접: ✅ PASS (배선 누락 호출부 0, 구현체 정상).
- CONCERNS 수정(Maxi 결정): ArchUnit permission 패키지 추가 + Global 테스트 케이스 + shared KDoc 패키지 정정. (로그 메시지는 테스트 결합+MaxLineLength로 원복, doc drift 사소 후속.)

### 특이사항 — PRE_EXISTING ktlint debt
identity-access 모듈에 main(ce44a1a)부터 존재하던 ktlint debt(audit/config/session 등 65파일)가 --rerun-tasks로 표면화. config/ktlint/baseline.xml로 동결(detekt-baseline 선례 동일, 우리 신규 코드 미포함). 우리 무관, 별도 점진 축소 후속.
