# FR-PM-01 프로젝트 행정 (멤버 관리) — D6 프론트 UI + D7 E2E

> slug: fr-pm-01-members-ui
> type: ui (classifier qa 오분류 교정)
> agent: frontend-engineer (+ qa-engineer for E2E)
> primary BC: identity-access (프론트는 apps/web)
> 생성: 2026-06-01

## Brief

FR-PM-01 남은 작업. 백엔드 D1~D5는 PR #48 완료(ProjectMembership/ProjectRole + CRUD API).
이번 PR 범위는 **D6(프론트 UI: 프로젝트 설정 → 멤버 관리 화면) + D7(E2E)**.

백엔드 API. `/api/v1/projects/{projectId}/members` — POST(추가)/GET(목록)/PATCH(역할)/DELETE(제거).
규칙. 부트스트랩(멤버0명 첫멤버 자동 ADMIN, JWT+자기자신), CRUD PAT 허용, 비멤버 404 존재숨김, 마지막 admin 보호.

**범위 변경(2026-06-01 Maxi 결정)**. 이번 PR은 D6/D7(프론트)에 더해, Jira식 `projectIdOrKey` 정합을 위한 **작은 백엔드 슬라이스**(멤버 API가 projectKey도 받게 확장)를 포함한다.

## 도메인 정리

- **BC**: identity-access (멤버 백엔드) + apps/web 프론트. cross-BC read는 issue-tracking `projects`(읽기 전용).
- **이번 PR 범위 (Maxi 결정 3건)**:
  1. **식별자 = Jira식 projectIdOrKey** — 멤버 백엔드 API path `{projectId}` → `{projectIdOrKey}`로 확장. UUID 형식이면 id로, 아니면 projects.key로 해석. `ProjectDirectory`에 `resolveKeyToId(key)` 추가(cross-BC read, FK 없음). 프론트 라우트는 `/projects/$projectKey/settings/members`로 workflow-scheme 관례와 일관. → 백엔드 슬라이스 + 프론트.
  2. **이름 표시 + 검색 추가** — 멤버 응답은 userId(UUID)만 → `GET /api/v1/users?query=`(FR-IS-03 디렉토리, `[{id,username,displayName?,email?}]`, 최대 50건)로 목록의 userId→이름 변환 + 추가 시 typeahead 검색.
  3. **부트스트랩 제외** — 이번엔 PROJECT_ADMIN의 멤버 CRUD만. 비멤버는 404→"접근 권한 없음" 화면. 생성자-자동admin은 프로젝트 생성 FR으로 이연.
- **검증된 백엔드 계약(PR #48, 실코드 인용)**:
  - GET `{members:[ProjectMemberResponse]}` 200 / POST 201 단건 / PATCH `/{userId}` 200 단건 / DELETE `/{userId}` 204
  - `ProjectMemberResponse{ projectId:UUID, userId:UUID, role:string("PROJECT_ADMIN"|"MEMBER"), createdAt:ISO, updatedAt:ISO }` (camelCase, displayName/email 없음 — Zod 1:1)
  - 에러 `{error:snake_case}` — `project_not_found`(404 존재숨김), `user_not_found`(404), `member_not_found`(404), `membership_already_exists`(409), `not_project_admin`(403), `last_admin_protected`(409), `bootstrap_requires_jwt`(403), `invalid_role`(422), `unauthorized`(401)
- **실재 검증(phantom 방지)**:
  - `projects` 테이블 `id` UUID + `key` VARCHAR UNIQUE 둘 다 존재 ✅ (issue-tracking V001) → key→id 변환 가능
  - `GET /api/v1/users` 디렉토리 ✅ (UsersController, FR-IS-03) / 프론트 users 디렉토리 클라이언트 미존재 ❌ (이번 신규)
  - 프론트 `/projects/$projectKey/settings/workflow-scheme` 라우트 패턴 실재 ✅ → 멤버 라우트가 따를 선례
  - `ProjectMemberController`가 projectKey 수용 ❌ (이번 백엔드 슬라이스로 확장)
- **새 용어**: 없음 (프로젝트 멤버십/역할은 백엔드 plan에서 이미 도입). glossary 변경 없음.
- **기존 결정 충돌**: 백엔드 ADR `2026-06-01-project-membership-model`이 "projectId UUID path"로 명시 → 이번 projectKey 확장으로 보강. 신규 ADR로 결정 기록.
- **관련 ADR**: [docs/decisions/2026-06-01-project-member-projectidorkey.md](../decisions/2026-06-01-project-member-projectidorkey.md) (생성됨)

## 스펙 (← /bts-spec Phase A 채움)

## 스펙

전체 스펙. [docs/specs/2026-06-01-fr-pm-01-members-ui.md](../specs/2026-06-01-fr-pm-01-members-ui.md)

핵심 요약.
- **백엔드 슬라이스(security-engineer)** — ① 멤버 API path `{projectIdOrKey}` 수용(`ProjectDirectory.resolveKeyToId`, key 해석 실패=404 존재숨김) ② `ProjectMemberResponse`에 `displayName?`+`username` 동봉(users 같은-BC 조인). 마이그레이션 0건, UUID 경로 회귀 0.
- **프론트(frontend-engineer)** — `/projects/$projectKey/settings/members`(requireAuth) 라우트. 목록(displayName 표시 + 역할 배지) + 추가(typeahead `/users?query=` 검색→선택→역할) + 역할변경(낙관적) + 제거(확인). 에러코드 8종→한국어 토스트, 비멤버 404→접근권한없음 화면. 라벨은 i18n 파일(E2E 셀렉터 정본). Zod 1:1.
- **E2E(qa-engineer)** — S1~S6 Playwright, MSW stateful refetch(가짜 그린 방지).
- 범위 밖: 부트스트랩/생성자-자동admin UI(생성 FR), 전체 페이지네이션.

## Brainstorming Check

✅ 통과 (adversarial self-review 1회). gap2건 해소 — currentUserId는 WhoamiResponse로 확보(검증), 멤버 이름변환은 결정 C(응답 displayName 동봉, Jira식)로 해소. BLOCKER 0.

## Plan

패키지/경로. 백엔드 `com.atlas.bts.identity.project`·`.web`(identity-access 모듈). 프론트 `apps/web/src/{api,hooks,components/admin,routes,mocks,i18n}`.
공통 검증. 백엔드 task 끝 `./gradlew :modules:identity-access:test --tests <…>` + detekt. 프론트 task 끝 `pnpm --filter web typecheck && pnpm --filter web test -- <file>`. E2E는 `pnpm --filter web test:e2e`.

> **TDD 강제**. 모든 task는 `test:` 커밋이 `feat:` 커밋보다 먼저. 백엔드 DTO(B3) 확정 후 프론트 Zod(F1)가 그 실코드를 1:1 미러 — `frontend-zod-backend-dto-contract-gap` 회귀 차단 위해 F1 depends-on [B3].

### Task B1. ProjectDirectory.resolveKeyToId — projectKey→UUID 변환

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/project/ProjectDirectory.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/project/JdbcProjectDirectoryIntegrationTest.kt`]
- depends-on: []

**RED**. `JdbcProjectDirectoryIntegrationTest`에 추가 — `resolveKeyToId("ATLAS")`: projects 행 존재+`deleted_at IS NULL` → 해당 id, soft-deleted/미존재 → null. (테스트가 projects 최소 테이블 생성하는 기존 setUp 재사용, key 컬럼 포함.)
**GREEN**. `ProjectDirectory` 인터페이스에 `resolveKeyToId(key: String): UUID?` 추가 + `JdbcProjectDirectory` 구현 `SELECT id FROM projects WHERE key = :key AND deleted_at IS NULL`.
**REFACTOR**. KDoc(cross-BC read-only, key 정규식은 issue-tracking 소유), SQL 상수화.
**검증**. `--tests JdbcProjectDirectoryIntegrationTest`

### Task B2. 멤버 목록 view 조회 — users 조인(displayName/username)

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/project/ProjectMembershipRepository.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/project/ProjectMembershipRepositoryIntegrationTest.kt`]
- depends-on: []

**RED**. 통합테스트 추가 — `listMemberViewsByProject(projectId)`가 멤버십 + `users.display_name`/`users.username`을 조인해 반환(`ProjectMemberView` 읽기 모델: 멤버십 필드 + displayName? + username). FK CASCADE라 orphan 없음 가정 검증. 단건 resolve(`findMemberView(projectId,userId)`)도 추가 — POST/PATCH 응답용.
**GREEN**. 새 read 모델 `ProjectMemberView` + 리포지토리 메서드 2개(`project_memberships m JOIN users u ON m.user_id = u.id`). 도메인 `ProjectMembership`은 불변·표시필드 미오염(읽기 전용 view 분리).
**REFACTOR**. RowMapper object 분리, SQL 상수.
**검증**. `--tests ProjectMembershipRepositoryIntegrationTest`

### Task B3. Controller projectIdOrKey 수용 + 응답 displayName 동봉

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/web/ProjectMemberController.kt`, `backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/web/dto/ProjectMemberResponse.kt`, `backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/project/ProjectMembershipService.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/web/ProjectMemberControllerTest.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/integration/ProjectMemberFlowIntegrationTest.kt`]
- depends-on: [B1, B2]

**RED**.
- ControllerTest — path가 projectKey('ATLAS')일 때 정상 동작(resolveKeyToId 경유), 잘못된 key → 404 `project_not_found`(존재숨김), 기존 UUID 경로 회귀 없음. 응답 JSON에 `displayName`/`username` 포함(GET 목록·POST·PATCH).
- FlowIntegrationTest — projectKey 경로 end-to-end 1건 + 응답 displayName 검증 추가.
**GREEN**. 컨트롤러 `@RequestMapping(".../{projectIdOrKey}/members")`, `resolveProjectId(raw)` 헬퍼(UUID 파싱 시도→실패 시 `resolveKeyToId`, 둘 다 실패=ProjectNotFound). `ProjectMemberResponse`에 `displayName:String?`+`username:String` 추가, `from(view)` 오버로드. 서비스 `listMembers`/add/changeRole 반환을 view 기반으로(또는 컨트롤러가 단건 resolve). 에러 평가순서 유지(C6).
**REFACTOR**. resolveProjectId 헬퍼 KDoc(key 정규식·UUID 형식 비교집합), 응답 매핑 정리.
**검증**. `--tests ProjectMemberControllerTest --tests ProjectMemberFlowIntegrationTest` + `./gradlew :modules:identity-access:detekt`

### Task F1. 프론트 API 레이어 + Zod (members + users 디렉토리)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/api/project-members.ts`, `apps/web/src/api/project-members.types.ts`, `apps/web/src/api/users.ts`, `apps/web/src/api/__tests__/project-members.test.ts`, `apps/web/src/api/__tests__/users.test.ts`]
- depends-on: [B3]

**RED**. vitest(MSW inline) — `fetchProjectMembers(projectKey)`가 `{members:[...]}`를 파싱해 `displayName`/`username`/`role` 보유, 에러코드(`not_project_admin` 등) 보존 throw. `addMember`/`changeRole`/`removeMember`가 X-XSRF-TOKEN 포함 + 상태/응답 검증. `searchUsers(query)`가 `[{id,username,displayName?,email?}]` 배열 파싱. **Zod 스키마는 B3 실DTO 1:1**(grep 대조).
**GREEN**. `project-members.types.ts`(Zod: ProjectMemberResponse+role enum, 에러), `project-members.ts`(CRUD, ProjectMemberApiError), `users.ts`(검색). 기존 `client.ts`(apiGet/apiFetch/readXsrfToken) 재사용.
**REFACTOR**. 에러코드→class 매핑 정리, 타입 z.infer.
**검증**. `pnpm --filter web typecheck && pnpm --filter web test -- project-members users`

### Task F2. MSW 핸들러 + fixtures (stateful CRUD + 디렉토리)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/mocks/project-member-handlers.ts`, `apps/web/src/mocks/project-member-fixtures.ts`, `apps/web/src/mocks/users-handlers.ts`, `apps/web/src/mocks/handlers.ts`]
- depends-on: [F1]

**RED**. 핸들러 단위 검증(또는 F3에서 소비) — GET 멤버 목록, POST 추가→목록 반영, PATCH 역할변경→반영, DELETE→제거. **stateful 오버라이드 영속**(invalidate refetch 후 화면 반영, `msw-mutation-stateful-refetch` 교훈). 에러 분기는 백엔드 순서와 동일(비멤버 404 먼저, 마지막admin 409). `X-MSW-Reset-*` 헤더로 상태 초기화. users-handlers는 query substring 검색.
**GREEN**. stateful Map/Set 기반 핸들러 + fixtures(프로젝트 'ATLAS' + 멤버 2~3 + 디렉토리 사용자), `handlers.ts`에 등록.
**REFACTOR**. fixtures 상수화, reset 헬퍼.
**검증**. `pnpm --filter web test -- mocks` (또는 F3 통과로 간접)

### Task F3. TanStack Query 훅 (목록/추가/변경/제거 + typeahead)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/hooks/use-project-members.ts`, `apps/web/src/hooks/use-user-directory.ts`, `apps/web/src/hooks/project-member-error.ts`, `apps/web/src/hooks/__tests__/use-project-members.test.ts`]
- depends-on: [F1, F2]

**RED**. vitest — `useProjectMembers(projectKey)` 목록 쿼리, `useAddMember`/`useChangeRole`(낙관적+onError 롤백+onSettled invalidate)/`useRemoveMember`, `useUserSearch(query)` typeahead(디바운스/enabled). 에러코드→토스트 메시지 매핑(`project-member-error.ts`). 키 상수 `PROJECT_MEMBER_KEYS`.
**GREEN**. 훅 구현(use-workflow-schemes 패턴), `setQueryData` 부분응답 주의(`mutation-setquerydata-partial-response-flicker` 교훈 — invalidate 우선).
**REFACTOR**. 키/에러 매핑 정리.
**검증**. `pnpm --filter web test -- use-project-members`

### Task F4. 컴포넌트 + i18n 라벨 (목록/행/역할Select/추가다이얼로그)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/admin/MemberList.tsx`, `apps/web/src/components/admin/MemberRow.tsx`, `apps/web/src/components/admin/RoleSelect.tsx`, `apps/web/src/components/admin/AddMemberDialog.tsx`, `apps/web/src/i18n/project-member-labels.ts`, `apps/web/src/components/admin/MemberList.test.tsx`, `apps/web/src/components/admin/AddMemberDialog.test.tsx`]
- depends-on: [F3]

**RED**. vitest+RTL — MemberList 로딩/에러/빈/목록 4분기, displayName 표시(null→username 폴백, EC-1), 역할 배지. RoleSelect 변경→mutation. AddMemberDialog typeahead 검색→선택→역할→추가. 액션 컨트롤은 PROJECT_ADMIN(현재 사용자 whoami userId가 목록에서 ADMIN)일 때만 노출(FR-F9). 텍스트 중복 버튼은 컨테이너 한정(`playwright-getbyrole-exact-strict-mode` 교훈 — 단위에서도 셀렉터 위생).
**GREEN**. shadcn Card/Select/Dialog/Button(기존 settings 페이지 패턴), 라벨 i18n 파일(E2E 셀렉터 정본).
**REFACTOR**. 컴포넌트 분리 정리, aria-label.
**검증**. `pnpm --filter web typecheck && pnpm --filter web test -- MemberList AddMemberDialog`

### Task F5. 라우트 페이지 + 라우터 등록

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/routes/projects.$projectKey.settings.members.tsx`, `apps/web/src/router.ts`, `apps/web/src/routes/__tests__/projects.$projectKey.settings.members.test.tsx`]
- depends-on: [F4]

**RED**. vitest — RouteAdapter가 `$projectKey` 추출→Page에 props. Page가 목록 렌더 + 404→"접근 권한이 없습니다" 화면(S6, project_not_found). 라우터 비의존 단위 테스트(props 주입).
**GREEN**. `ProjectMembersSettingsPage`(workflow-scheme 페이지 패턴) + `router.ts`에 `/projects/$projectKey/settings/members`(requireAuth) 등록.
**REFACTOR**. 페이지 헤더/레이아웃 정리(max-w 컨테이너).
**검증**. `pnpm --filter web typecheck && pnpm --filter web test -- members`

### Task E1. E2E — 멤버 관리 시나리오 S1~S6

**메타**.
- agent: `qa-engineer`
- files: [`apps/web/e2e/project-member-management.spec.ts`]
- depends-on: [F2, F5]

**RED**(실패하는 새 E2E). Playwright — S1 목록조회(displayName/역할배지), S2 추가(검색→선택→역할→추가, 목록반영), S3 역할변경(배지갱신), S4 제거(사라짐), S5 마지막admin 보호(에러토스트+롤백), S6 비멤버 404→접근권한없음. `beforeEach` MSW 상태 리셋(`X-MSW-Reset-*`). 텍스트 중복 버튼은 컨테이너/exact 한정(strict mode 교훈). 기존 E2E 동반 실행(UI 추가가 전역 셀렉터 안 깨는지, `ui-pr-defer-e2e-regression-latent` 교훈).
**GREEN**. (단위는 F1~F5 test-first 완료. E1은 통합 시나리오 + MSW stateful 검증이 처음 드러내는 새 검증.)
**REFACTOR**. 시나리오 헬퍼(loginAsAlice 재사용).
**검증**. `pnpm --filter web test:e2e -- project-member-management` + 기존 E2E 회귀 그린

## Plan 메타

- task 수: 9 (백엔드 슬라이스 3 / 프론트 5 / E2E 1)
- 의존 그래프 / wave(이론):
  - wave 1. B1 ∥ B2 (파일 겹침 없음, depends-on 없음)
  - wave 2. B3 [B1,B2]
  - wave 3. F1 [B3] — 백엔드 DTO 확정 후 Zod 1:1 (contract gap 차단)
  - wave 4. F2 [F1]
  - wave 5. F3 [F1,F2]
  - wave 6. F4 [F3]
  - wave 7. F5 [F4]
  - wave 8. E1 [F2,F5]
  - 백엔드(identity-access 단일 모듈)는 같은 test 컴파일 단위 공유로 B1~B3 실질 직렬(`bts-plan-wave-gradle-module-compile`). 프론트 체인은 의도적 직렬(api→mocks→hooks→components→route, 각 layer 선행). F1을 B3에 묶어 contract gap 차단(병렬 이득보다 정합 우선).
- TDD 강제: yes (test-first, `test:` 커밋이 `feat:` 앞)
- 추가 검증: typecheck/ktlint/detekt/vitest/playwright
- 리스크:
  1. **contract drift** — 프론트 Zod ↔ B3 DTO. F1 depends-on [B3] + grep 대조로 차단.
  2. **MSW 가짜 그린** — stateful 오버라이드 영속 안 하면 invalidate refetch 후 롤백 안 됨(F2 RED에 명시).
  3. **strict mode 셀렉터** — 텍스트 중복 버튼(역할변경/제거 행마다) 컨테이너 한정(F4/E1).
  4. **기존 E2E 회귀** — 새 라우트/요소가 전역 셀렉터 깨는지 E1에서 기존 E2E 동반 실행.
  5. **백엔드 merged 코드 수정** — B3가 PR #48 DTO/서비스 확장. 같은 BC·순수 추가(기존 필드 유지)로 UUID 경로 회귀 0 보장(FlowIntegrationTest).

## 리뷰 결과 (← /bts-review-plan 채움)
