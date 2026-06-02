# FR-PM-02 CREATE 게이트 — 목록 새 이슈 버튼 권한 비활성화 — 스펙

> slug: fr-pm-02-create-gate
> BC: identity-access
> 관련 ADR: [issue-permission-query-api](../decisions/2026-06-02-issue-permission-query-api.md)(확장), [issue-permission-scheme-model](../decisions/2026-06-02-issue-permission-scheme-model.md)
> 선행 PR: #55(D6/D7 상세 수정·삭제 게이트), #53(권한 enforcement)

## 배경 한 줄

이슈 목록의 "새 이슈" 버튼을, 현재 사용자가 그 프로젝트에서 이슈를 만들 수 없으면 비활성화한다. 권한 근거는 서버가 유일한 정답지(Jira mypermissions 방식)이며, 프로젝트 스코프 권한 조회 엔드포인트를 신설해 답한다.

## 핵심 설계 결정

- **엔드포인트 = 신규 분리**(Maxi 결정 2026-06-02). `GET /api/v1/users/me/project-permissions?projectKey={key}`. 기존 `issue-permissions`(이슈 스코프) 계약·Zod 스키마·테스트를 건드리지 않아 회귀 위험 0(메모 `zod-schema-strengthen-inline-mock-fanout` 회피).
- **백엔드 도메인 무변경**. `IdentityAccessIssuePermissionResolver`는 이미 `IssueScope.Project` + `IssuePermission.CREATE → CREATE_ISSUE`를 판정한다. 신규 컨트롤러가 `IssueScope.Project(projectKey)` + `IssuePermission.CREATE`로 resolver를 호출할 뿐이다.
- **현 매트릭스 실효 = 비멤버 / fail-closed**. `PROJECT_ADMIN`·`MEMBER` 둘 다 `CREATE_ISSUE` 보유(V008 시드). 따라서 멤버는 항상 CREATE=true(버튼 활성), 실질 비활성 경로는 (a) 비멤버, (b) 권한 미확정(로딩/에러)이다. D6/D7의 "상세 도달자는 멤버라 항상 통과"와 동일한 구조. 인프라는 미래 스킴 확장(역할에서 CREATE 회수) 대비.

## 사용자 시나리오 (Given-When-Then)

- **S1 — CREATE 권한 있음**: Given 멤버/관리자(alice)로 로그인 / When 이슈 목록 진입 / Then "새 이슈" 버튼이 활성, 클릭 시 `/issues/new`로 이동.
- **S2 — CREATE 권한 없음**: Given CREATE 권한 없는 사용자(비멤버 또는 회수된 역할) / When 이슈 목록 진입 / Then "새 이슈" 버튼이 비활성(disabled), 클릭해도 이동 안 함.
- **S3 — fail-closed**: Given 권한 조회가 로딩 중이거나 에러 / When 이슈 목록 렌더 / Then "새 이슈" 버튼은 비활성(권한 확정 전 노출 안 함). 권한은 UX 힌트, 실제 보안 경계는 `POST /api/v1/issues`의 403.

## 기능 요구사항 (FR)

- **FR1 (백엔드)** `GET /api/v1/users/me/project-permissions?projectKey={key}`를 신설한다. 인증된 사용자의 actorId를 토큰(JWT/PAT)에서 추출하고, `IssuePermissionResolver.hasPermission(actorId, CREATE, IssueScope.Project(projectKey))`를 호출해 결과를 boolean 맵으로 반환한다.
- **FR2 (응답 DTO)** `ProjectPermissionsResponse(projectKey: String, permissions: Map<String, Boolean>)`. 본 FR 범위 권한 = `{ "CREATE": <boolean> }`. 이슈 스코프와 동형(同型) 구조라 프론트 훅 패턴 재사용.
- **FR3 (인증)** JWT/PAT 인증 필수. 미인증 401. actor는 토큰에서만 추출(바디/파라미터로 actor 안 받음). `MyIssuePermissionController`의 `resolveActorId`/`extractBearerToken`과 동일 패턴.
- **FR4 (파라미터 검증)** `projectKey` 누락/공백이면 400. 존재하지 않는 projectKey는 resolver가 false 반환 → 200 + `{ "CREATE": false }`(이슈 스코프 엔드포인트의 존재하지 않는 issueKey 동작과 일관).
- **FR5 (프론트 API client + 훅)** `fetchProjectPermissions(projectKey)` + Zod 스키마 `projectPermissionsSchema`(`{ projectKey, permissions: { CREATE: boolean } }`) + `useProjectPermissions(projectKey)` 훅. 기존 `issue-permissions.ts`/`use-issue-permissions.ts` 패턴 미러.
- **FR6 (버튼 게이트)** `IssueListPage`의 "새 이슈" 진입점이 `canCreate`(CREATE && !isLoading && !isError)일 때만 활성. fail-closed. 현재 `<a>` 링크라 disabled를 못 거니, 비활성 상태를 표현할 수 있는 요소로 전환(예: `Button asChild` 또는 disabled 분기). `data-testid="new-issue-button"` 부여(E2E 셀렉터, 텍스트 중복 회피 학습).
- **FR7 (MSW 핸들러)** 토큰(username) 기반 `project-permissions` 핸들러 추가. alice→admin(CREATE true), bob→member(CREATE true). E2E S2용 비활성 케이스는 stateful 오버라이드 또는 CREATE=false fixture로 구성.

## 비기능 요구사항 (NFR)

- **NFR1 보안**. 프론트 게이트는 UX 힌트일 뿐, 실제 차단은 백엔드 `POST /issues` 403. 버튼 비활성이 보안 경계 아님(명시).
- **NFR2 회귀 격리**. 기존 `issue-permissions` 엔드포인트/스키마/E2E 무변경. 신규 파일·신규 핸들러만 추가.
- **NFR3 BC 격리 유지**. resolver가 projectKey→projectId를 `ProjectDirectory.resolveKeyToId`로 해석, 이슈 데이터 미접근. identity-access 단일 BC.
- **NFR4 캐시**. 훅 staleTime 30초(기존 issue-permissions 훅과 동일), 화면 내 중복 호출 방지.

## API 인터페이스 (REST)

```
GET /api/v1/users/me/project-permissions?projectKey=ATLAS
Authorization: Bearer <JWT|pat_...>

200 OK
{ "projectKey": "ATLAS", "permissions": { "CREATE": true } }

400 — projectKey 누락/공백
401 — 미인증
```

## 데이터 모델 변경

없음. 신규 테이블/컬럼/마이그레이션 없음. 기존 `role_permissions`(V008) + `IssueScope.Project` + `IssuePermission.CREATE` 재사용.

## 엣지 케이스

- 존재하지 않는 projectKey → 200 + `{CREATE:false}`(404 아님, resolver false 일관).
- 비멤버 → resolver 멤버 게이트에서 false → 버튼 비활성.
- 권한 조회 로딩/에러 → fail-closed 비활성(S3).
- PAT 인증 → JWT와 동일 분기 지원. PAT 감사로그(whoami는 남기나 권한 조회는 미기록)는 기존 `issue-permissions`와 동일하게 이번 범위 밖(C3 후속 일관화 대상).
- 목록 projectKey가 현재 `DEFAULT_PROJECT_KEY='ATLAS'` 하드코딩 → 멀티 프로젝트 목록은 본 FR 범위 밖, projectKey를 훅에 그대로 전달.

## 제약 조건

- 한 PR = identity-access BC(+ same-BC view layer인 프론트). 다른 BC import 금지.
- TDD red→green→refactor 강제(`test:` 커밋이 `feat:` 앞).
- 기존 `issue-permissions` 계약 불변(신규 분리 결정의 핵심 가치).

## 측정 가능한 완료 기준

- [ ] `GET .../project-permissions?projectKey=ATLAS`가 멤버 200+CREATE:true, 비멤버 200+CREATE:false, 미인증 401, projectKey 누락 400 반환(통합테스트).
- [ ] 프론트 단위: `canCreate`=true → 버튼 활성, false/로딩/에러 → 비활성.
- [ ] E2E: S1(alice 버튼 활성·이동) + S2(CREATE 없음 버튼 비활성).
- [ ] 기존 `issue-permissions` 단위/E2E green 유지(회귀 0).
- [ ] ktlint/detekt green, `pnpm --filter @bts/web typecheck` green(메모 `ci-typecheck-tsconfig-app-vs-local`).
