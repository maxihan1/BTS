# ADR — 버전/컴포넌트 권한 질의 API 확장 + 프론트 게이팅 (FR-PM-03 D6/D7)

> 날짜: 2026-06-03
> 상태: 채택
> BC: identity-access (권한 질의 API) + apps/web (게이팅 UI)
> 관련 FR: FR-PM-03 D6/D7
> 선행: FR-PM-03 D1~D5(PR #70, prod 리졸버 + MANAGE_* 매트릭스) · FR-PM-02 D6(PR #55, 권한 질의 API + 게이팅)
> 선례 ADR: docs/decisions/2026-06-02-issue-permission-query-api.md (ProjectPermissionsResponse 도입)

## 맥락

FR-PM-03 D1~D5가 prod 권한 리졸버 2개 + `MANAGE_COMPONENTS`/`MANAGE_VERSIONS` 매트릭스(PROJECT_ADMIN 전용)를
채웠다. D6/D7은 그 권한을 프론트에서 게이팅한다(권한 없는 사용자에게 관리 버튼 비활성화).

문제. 기존 `MyProjectPermissionController`(`GET /api/v1/users/me/project-permissions?projectKey=`)는
`UI_PROJECT_PERMISSIONS = listOf(IssuePermission.CREATE)`만 조회하고 `IssuePermissionResolver`만 주입한다.
즉 MANAGE_* 권한을 프론트가 알 방법이 없다. 응답 DTO `ProjectPermissionsResponse(projectKey, permissions: Map<String, Boolean>)`는
제네릭 맵이라 키 추가에 열려 있다.

## 결정

### D1 — 기존 엔드포인트 확장(새 엔드포인트 신설 안 함)
`MyProjectPermissionController`가 `ComponentPermissionResolver`/`VersionPermissionResolver`도 주입받아
응답 `permissions` 맵에 `MANAGE_COMPONENTS`/`MANAGE_VERSIONS` 키를 추가한다. 한 번의 round-trip으로
CREATE + MANAGE_* 전부 반환. 응답은 **additive**(기존 CREATE 소비자 무영향).

근거. DTO가 이미 제네릭 맵이고, 한 프로젝트 단위 권한을 한 호출로 받는 게 자연스럽다(프론트 staleTime 30s 캐시 1건).

### D2 — projectKey→projectId 해석 후 두 리졸버 호출
IssuePermissionResolver는 `IssueScope.Project(key)`(키)를 받지만 Component/VersionPermissionResolver는
`projectId: UUID`를 직접 받는다. 컨트롤러가 `ProjectDirectory.resolveKeyToId(projectKey)`로 해석한다.
- 해석 결과 null(미존재/소프트삭제) → MANAGE_* 둘 다 `false`(기존 "미존재 projectKey → 200 + false" 정책 일관).
- 멤버/매트릭스 판정은 리졸버 내부(D1~D5)가 수행.

### D3 — identity-access non-prod fallback 빈 2개 추가 (부팅 가드)
확장된 컨트롤러가 두 `@Profile("prod")` 포트를 주입받으면 identity-access가 그 포트의 **소비자**가 된다.
non-prod identity-access 컨텍스트엔 그 포트를 채우는 빈이 없어(issue-tracking의 AlwaysAllow*는 다른
컨텍스트) 부팅이 `UnsatisfiedDependency`로 깨진다(메모리 profile-scoped-bean-boot-failure, PR #55 66-test 연쇄).
→ `DevAllowComponentPermissionResolver`/`DevAllowVersionPermissionResolver`(`@Profile("!prod")`, 항상 true)를
identity-access에 추가한다. `DevAllowIssuePermissionResolver` 1:1 선례.

### D4 — 프론트 게이팅(fail-closed, 모든 mutation 액션)
`useProjectPermissions(projectKey)`가 반환하는 `permissions.MANAGE_COMPONENTS`/`MANAGE_VERSIONS`로 게이팅.
- 게이트 대상. ComponentList "컴포넌트 추가" + ComponentRow 편집/삭제, VersionList "버전 추가" + VersionRow 편집/삭제.
- fail-closed. 로딩/에러/false → `disabled`(권한 미확정 시 차단). FR-PM-02 IssueMetaPanel 선례 동형.
- 응답 Zod 스키마(ProjectPermissions)에 두 키 추가 시 strict 스키마 깨짐 주의(메모리
  zod-schema-strengthen-inline-mock-fanout, frontend-zod-backend-dto-contract-gap) — 인라인 mock 전수 grep.

### D5 — E2E (D7)
PROJECT_ADMIN 관리 가능 / MANAGE_* 없는 사용자 버튼 disabled 시나리오. MSW project-permission-handlers에
MANAGE_* 응답 추가(같은 사용자 토글은 localStorage 플래그 — 메모리 e2e-msw-scenario-toggle-localstorage-flag).

## 결과

- 산출물. 컨트롤러 확장 + DTO 맵 키 추가 + DevAllow fallback 2 + 백엔드 테스트 / 프론트 게이팅(api Zod + 훅 사용 +
  List/Row disabled) + 단위테스트 / E2E.
- 보안 포스처. prod에서 비PROJECT_ADMIN은 관리 버튼 비활성(UI) + 백엔드 403(D1~D5)으로 2중 방어.
- 미해소. 없음. 스킴 편집 UI는 별도 FR.
