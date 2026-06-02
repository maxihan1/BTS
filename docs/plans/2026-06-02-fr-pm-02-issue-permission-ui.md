# FR-PM-02 D6/D7 — 이슈 권한 기반 액션 버튼 비활성화 UI + E2E

> slug: fr-pm-02-issue-permission-ui
> type: ui
> agent: frontend-engineer (E2E는 qa-engineer 보조)
> 생성: 2026-06-02

## Brief

FR-PM-02 D6/D7 후속 PR. 이슈 권한 스킴(PR #53, 백엔드 D1~D5 머지 완료)에 따라
프론트엔드에서 권한 없는 액션(이슈 등록/수정/삭제) 버튼을 비활성화하는 권한 UI +
Playwright E2E. FR-PM-01 D6/D7(PR #50, `[ui]` 단일 PR, D6 프론트 + D7 E2E) 패턴 답습.

classify 원결과: type=qa(오분류, E2E 키워드) → FR-PM-01 선례로 type=ui 정정.

## 도메인 정리

### BC
- **백엔드 BC: identity-access** (정정 — 1차 도메인 결정 issue-tracking에서 변경, Maxi 2026-06-02) — 권한 조회 엔드포인트(`GET /api/v1/users/me/issue-permissions`). 이유: IssueController가 actor를 SYSTEM 하드코딩(임시)해 실제 사용자를 모름. 인증 사용자 추출(WhoamiController 패턴) + 권한 평가(IdentityAccessIssuePermissionResolver)가 모두 identity-access 핵심 역량.
- **프론트**: apps/web 이슈 상세 화면(D6) + E2E(D7).
- **BC 격리**: resolver가 issueKey prefix로 projectKey 해석 → 이슈 데이터 미접근. identity-access 단독 처리.

### 핵심 도메인 결정 (Maxi 승인 2026-06-02)
**프론트의 권한 인지 방식 = Jira `mypermissions` 방식 (서버가 single source of truth).**

- 백엔드 PR #53은 권한 **강제(enforcement)** 만 구현했다. 권한 없으면 403 + `ACCESS_DENIED`(RFC 7807 ProblemDetail). 그러나 프론트가 미리 물어볼 **권한 조회 API는 없고**, whoami 응답에도 역할(ProjectRole)이 없다(username/email/authMethod/userId만).
- 따라서 "권한 없는 버튼 비활성화"를 하려면, **백엔드에 권한 조회 엔드포인트를 신설**하고 프론트는 그 응답으로만 버튼을 켜고 끈다. 프론트는 권한 매트릭스를 하드코딩하지 않는다(drift 차단).
- 이는 ADR `2026-06-02-issue-permission-scheme-model.md`가 채택한 "풀 Jira식 스킴"과 일관. 권한 조회도 Jira식(`mypermissions`)으로 통일.

### 영향
- **신규**: 권한 조회 REST 엔드포인트(예: `GET /api/v1/issue-permissions/mine?projectKey=&issueKey=`, 형태는 spec 확정). 응답은 권한별 boolean(예: `{ CREATE: true, UPDATE: true, SOFT_DELETE: false }`).
- **신규**: 프론트 권한 조회 훅 + 이슈 등록/수정/삭제 버튼 disabled 분기.
- **신규**: Playwright E2E(권한 있음/없음 시나리오).
- **resolver 포트**: 현재 `hasPermission(actorId, permission, scope): Boolean`만 존재. 조회 API가 권한별 N회 호출하면 포트 변경 불필요(issue-tracking 안에서 해결, BC 격리 유지). 포트에 `getPermissions(...)` 추가 여부 + N회 호출 성능은 plan에서 검토.
- IssuePermission enum: VIEW / CREATE / UPDATE / TRANSITION / SOFT_DELETE / HARD_DELETE(예약). UI 대상은 CREATE / UPDATE / SOFT_DELETE.

### 기존 결정 충돌
- 없음. ADR `2026-06-02-issue-permission-scheme-model.md`가 "권한 조회 API는 후속"으로 미뤘던 것을 이번에 **권한 조회만** 당겨 구현(스킴 CRUD API는 여전히 후속).
- 신규 ADR: `docs/decisions/2026-06-02-issue-permission-query-api.md` (Jira mypermissions 방식 결정 기록).

### 보안
- 권한 조회 API는 JWT 인증 필수, **본인 권한만** 조회(actorId = 인증 사용자). PAT 정책은 spec에서. security-engineer 검토 영역.

### 대안 (기각)
- **프론트 매트릭스 하드코딩(FR-PM-01 답습)** — 백엔드 무변경/scope 작으나 drift 위험. 기각(Jira식 채택).
- **낙관적 UI(버튼 노출+403 토스트)** — "비활성화" 요구 미충족. 기각.

### 관련 ADR
- 기존: [docs/decisions/2026-06-02-issue-permission-scheme-model.md](../decisions/2026-06-02-issue-permission-scheme-model.md)
- 신규: [docs/decisions/2026-06-02-issue-permission-query-api.md](../decisions/2026-06-02-issue-permission-query-api.md)

## 스펙

전체 스펙. [docs/specs/2026-06-02-fr-pm-02-issue-permission-ui.md](../specs/2026-06-02-fr-pm-02-issue-permission-ui.md)

핵심 3줄 요약.
- 백엔드: 이슈 스코프 권한 조회 엔드포인트(`GET /api/v1/issues/{key}/my-permissions` → UPDATE/SOFT_DELETE boolean) 신설. IssuePermissionResolver 포트 재사용(issue-tracking BC).
- 프론트: `useIssuePermissions(issueKey)` 훅으로 받아 상세 화면 수정/삭제 버튼 disabled 분기. fail-closed(로딩/실패 시 비활성). 매트릭스 하드코딩 안 함.
- E2E: S1(ADMIN 수정·삭제 활성) + S2(MEMBER 삭제 비활성). 게이트 범위 = 상세 수정+삭제만(CREATE는 후속).

## Brainstorming Check

✅ 통과 (직접 adversarial sanity check, office-hours 스킵 — 정의된 FR + 도메인 grill 완료).
핵심 발견: 현재 매트릭스 실효는 "MEMBER 삭제 비활성" 하나 → 게이트 범위를 상세 수정+삭제로 한정(Maxi 결정). fail-closed 기본값 + "저장" 4중복 셀렉터/ fixture userId 정합/ MSW stateful 교훈 선반영.

## Plan

### Task 1. 백엔드 — 이슈 권한 조회 엔드포인트 (identity-access)

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/web/MyIssuePermissionController.kt`, `backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/web/dto/IssuePermissionsResponse.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/integration/MyIssuePermissionIntegrationTest.kt`]
- depends-on: []

**RED**:
- 파일: `MyIssuePermissionIntegrationTest.kt` (Testcontainers, @ActiveProfiles("prod") — IdentityAccessIssuePermissionResolverIntegrationTest 부팅 패턴 참조: JWT PEM/BC provider/LDAP @MockBean)
- **(리뷰 C1)** 이 테스트는 resolver 직접 호출이 아니라 **컨트롤러 HTTP 레이어 + JWT 인증 흐름**을 검증한다. 실제 JWT를 발급해 `Authorization: Bearer` 헤더로 넣고 MockMvc로 호출 → @AuthenticationPrincipal 주입까지 태운다. **WhoamiControllerTest의 JWT 발급/주입 패턴을 참조**(매트릭스 정확성은 기존 13케이스 통합테스트가 이미 커버하므로, 여기선 ADMIN/MEMBER/비멤버 HTTP 응답 스모크 + actor 추출 + 401에 집중).
- 테스트:
  ```kotlin
  // 기본 스킴 V008 시드 기준
  @Test fun `ADMIN 사용자는 UPDATE true SOFT_DELETE true`() { /* JWT(admin userId) → GET /api/v1/users/me/issue-permissions?issueKey=ATLAS-1 → 200, UPDATE=true, SOFT_DELETE=true */ }
  @Test fun `MEMBER 사용자는 UPDATE true SOFT_DELETE false`() { /* SOFT_DELETE=false */ }
  @Test fun `비멤버는 모든 권한 false`() { /* UPDATE=false, SOFT_DELETE=false */ }
  @Test fun `미인증 요청은 401`() { /* Authorization 없음 → 401 */ }
  ```
- 실패 메시지(예상): `MyIssuePermissionController` 없음 → 404/빈 컨텍스트

**GREEN**:
- `MyIssuePermissionController`: `@GetMapping("/api/v1/users/me/issue-permissions")`, `@AuthenticationPrincipal jwt: Jwt?` + `@RequestParam issueKey: String`.
  - **(리뷰 C2)** actor 추출 = WhoamiController와 동일하게 **JWT 필수 + PAT 지원**(extractBearerToken→handlePat로 pat.userId). 둘 다 본인 userId만. 미인증 → 401. UI는 JWT를 쓰지만 스펙이 JWT/PAT 둘 다라 whoami 패턴 그대로 재사용해 일관성 유지.
  - userId 추출(jwt.subject UUID / pat.userId) → `IdentityAccessIssuePermissionResolver.hasPermission(userId, IssuePermission.UPDATE/SOFT_DELETE/TRANSITION, IssueScope.Issue(issueKey))` 3회.
  - `IssuePermissionsResponse(issueKey, permissions = mapOf 또는 named fields)`.
- 컨트롤러 @Transactional 0(조회). resolver 직접 주입.

**REFACTOR**:
- 권한 목록을 상수(`UI_PERMISSIONS = listOf(UPDATE, SOFT_DELETE, TRANSITION)`)로 추출. KDoc(엔드포인트 책임 + ADR 링크). 신규 파일 ktlint는 파일 단위 수동(모듈 ktlintFormat 금지).

**검증**: `./gradlew :modules:identity-access:test --tests MyIssuePermissionIntegrationTest` + `ktlintMainSourceSetCheck` (신규 파일만 영향) + detekt.

---

### Task 2. 프론트 — 권한 조회 API client + Zod 스키마

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/api/issue-permissions.ts`, `apps/web/src/api/__tests__/issue-permissions.test.ts`]
- depends-on: []

**RED**:
- `issue-permissions.test.ts`: `fetchIssuePermissions('ATLAS-1')`이 `GET /api/v1/users/me/issue-permissions?issueKey=ATLAS-1` 호출 + Zod parse 결과 `{ issueKey, permissions: { UPDATE, SOFT_DELETE, TRANSITION } }` 반환. 응답 누락 필드 시 ZodError.
- MSW로 모킹(test/handlers 또는 인라인).

**GREEN**:
- `issue-permissions.ts`: `issuePermissionsSchema`(z.object, permissions 3 boolean) + `fetchIssuePermissions(issueKey)` (기존 apiGet/client.ts 패턴 재사용, Zod parse).

**REFACTOR**:
- 타입 export(`IssuePermissions`), 파일 헤더 한국어 주석.

**검증**: `pnpm --filter web test issue-permissions` + `tsc --noEmit`.

---

### Task 3. 프론트 — useIssuePermissions 훅 (TanStack Query)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/hooks/use-issue-permissions.ts`, `apps/web/src/hooks/__tests__/use-issue-permissions.test.tsx`]
- depends-on: [2]

**RED**:
- 훅이 `fetchIssuePermissions(issueKey)`를 queryKey `['issue-permissions', issueKey]`로 호출. 로딩/성공/에러 상태 노출. `enabled: !!issueKey`.
- 테스트: 성공 시 permissions 반환, 로딩 중 isLoading, 에러 시 isError.

**GREEN**:
- `useIssuePermissions(issueKey)`: useQuery 래핑. staleTime 설정(화면 내 중복 방지, 기존 훅 컨벤션).

**REFACTOR**:
- 반환 타입 정리, KDoc.

**검증**: `pnpm --filter web test use-issue-permissions` + `tsc --noEmit`.

---

### Task 4. 프론트 — MSW 권한 핸들러 + fixtures

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/mocks/issue-permission-handlers.ts`, `apps/web/src/mocks/issue-permission-fixtures.ts`, `apps/web/src/mocks/handlers.ts`]
- depends-on: []

**RED/구현**:
- `issue-permission-fixtures.ts`: ADMIN 응답(UPDATE/SOFT_DELETE/TRANSITION true), MEMBER 응답(SOFT_DELETE false). fixture userId는 whoami fixture(alice=`00000000-...-001` 등)와 **정합**(메모리 [[e2e-fixture-whoami-userid-alignment]]).
- `issue-permission-handlers.ts`: `GET /api/v1/users/me/issue-permissions` 핸들러. 역할 전환을 **stateful 오버라이드**로 다룸(메모리 [[msw-mutation-stateful-refetch]] 유형 — 가짜 그린 방지).
- `handlers.ts`에 등록(기존 등록 순서/패턴 따름, 백엔드 분기와 일치).
- 핸들러 자체 테스트(mocks/__tests__ 패턴 있으면 동참).

**검증**: `pnpm --filter web test` (핸들러 영향 범위) + `tsc --noEmit`.

---

### Task 5. 프론트 — 상세 화면 수정/삭제 버튼 disabled 분기

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/routes/issues.$key.tsx`, `apps/web/src/components/issue/IssueMetaPanel.tsx`, `apps/web/src/routes/__tests__/issues.$key.test.tsx`, `apps/web/src/components/issue/__tests__/IssueMetaPanel.test.tsx`]
- depends-on: [3]

**RED**:
- 컴포넌트 테스트: `useIssuePermissions` 모킹.
  - 권한 SOFT_DELETE=false → "이슈 삭제" 버튼 `disabled` + aria/툴팁 "삭제 권한이 없습니다".
  - UPDATE=false → 제목/본문/메타 저장(또는 편집 진입) 버튼 disabled.
  - 로딩 중/에러 → fail-closed(비활성).
  - 권한 true → 활성(기존 동작).

**GREEN**:
- `issues.$key.tsx`에서 `useIssuePermissions(issue.key)` 호출, 권한을 수정/삭제 버튼·`IssueMetaPanel`(onDeleteClick 영역)로 전달.
- `IssueMetaPanel.tsx` 삭제 버튼에 `disabled={!canDelete}` + 사유 라벨. 수정 버튼들에 `disabled={!canEdit}`.
- 안전 기본값: 권한 미확정 시 canEdit/canDelete=false.

**REFACTOR**:
- 권한→불리언 매핑 헬퍼, 중복 disabled 표현 정리. 텍스트 중복 '저장' 버튼은 후속 E2E 위해 `data-testid` 부여 검토(EC-2).

**검증**: `pnpm --filter web test issues.$key IssueMetaPanel` + `tsc --noEmit` + 기존 이슈 컴포넌트 테스트 회귀 0.

---

### Task 6. E2E — Playwright 권한 시나리오 (S1 ADMIN / S2 MEMBER)

**메타**.
- agent: `qa-engineer`
- files: [`apps/web/e2e/issue-permission.spec.ts`]
- depends-on: [4, 5]

**RED→GREEN (E2E TDD 변형)**:
- `issue-permission.spec.ts`:
  - S1: ADMIN 로그인 → 이슈 상세 → 수정·삭제 버튼 enabled.
  - S2: MEMBER 로그인 → 이슈 상세 → 삭제 버튼 disabled(+사유), 수정 enabled.
- MSW serviceWorker 'block' 금지(메모리 [[e2e-msw-serviceworker-block]]) — 권한 핸들러는 MSW 경유.
- 셀렉터: 삭제는 "이슈 삭제" 텍스트. 저장 버튼 4중복은 컨테이너 한정 또는 data-testid(EC-2, 메모리 [[ui-pr-defer-e2e-regression-latent]]).
- **기존 이슈 E2E(issue-crud, issue-edit-conflict 등) 함께 실행**해 회귀 0 확인.

**검증**: `pnpm --filter web exec playwright test issue-permission issue-` (신규 + 기존 이슈 E2E). 5173 orphan 포트 주의(메모리 [[e2e-orphan-vite-after-worktree-remove]]).

## Plan 메타

- task 수: 6
- wave 예상: wave1 [T1, T2, T4] 병렬 / wave2 [T3] / wave3 [T5] / wave4 [T6] — longest path 4
- 모듈: T1 = identity-access(백엔드, 프론트와 완전 독립), T2~T6 = apps/web
- TDD 강제: yes (E2E T6는 E2E TDD 변형)
- 추가 검증: tsc --noEmit, ktlint(신규 파일 수동, 모듈 ktlintFormat 금지), detekt, vitest, playwright(기존 이슈 E2E 동반)
- 백엔드 슬라이스 포함 사유: 권한 조회 API 부재(스펙 FR-1). UI PR이지만 same-FR 보조 백엔드 포함.

## 리뷰 결과

### eng/보안 집중 리뷰 (2026-06-02, code-reviewer dispatch → 세션한도로 직접 검증 완료)

타입 ui지만 권한/보안 백엔드 슬라이스가 핵심이라 design-review 대신 eng/보안 관점 적용(메모리 [[bts-review-plan-autoplan-overkill]]). 실제 코드 grep/read로 plan 가정 검증.

**검증 통과 ✅**
- resolver 시그니처 `hasPermission(actorId: UUID, permission: IssuePermission, scope: IssueScope): Boolean` — plan 호출과 일치.
- enum 값 UPDATE/SOFT_DELETE/TRANSITION 정확(IssuePermission.kt:27/33/30). IssueScope.Issue(key: String) 정확.
- **BC 격리 검증**: `IssueScope.Issue -> resolveKeyToId(key.substringBefore('-'))` — issueKey prefix만으로 projectId 해석, 이슈 데이터 미접근(IdentityAccessIssuePermissionResolver.kt:79). identity-access 단독 처리 정당.
- 통합테스트 부팅: `@SpringBootTest @ActiveProfiles("prod") @Testcontainers` + PEM/BouncyCastle + LDAP @MockBean + V001~V008 시드(기존 13케이스 매트릭스 테스트와 동일) — Task 1 RED 가정 정확.
- 존재하지 않는 issueKey → resolveKeyToId null → 권한 false(fail-safe).
- WhoamiController actor 추출(JWT @AuthenticationPrincipal Jwt → jwt.subject UUID, PAT extractBearerToken→handlePat) 실재 확인.

**BLOCKER: 없음**

**CONCERN (plan 반영 완료)**
- C1: Task 1 통합테스트는 resolver 직접 호출이 아니라 컨트롤러+JWT 인증 흐름 검증 필요 → Task 1 RED에 WhoamiControllerTest JWT 주입 패턴 참조 + HTTP 스모크 집중 명시(매트릭스 정확성은 기존 13케이스가 커버).
- C2: actor 추출 JWT/PAT 범위 → Task 1 GREEN에 JWT 필수 + PAT whoami 패턴 재사용 명시.

**SUGGESTION**
- 프론트 권한→불리언 매핑(canEdit/canDelete)을 단일 헬퍼로 두면 fail-closed 기본값 일관(Task 5 REFACTOR 반영됨).
- "저장" 4중복 버튼은 Task 5에서 미리 data-testid 부여 → Task 6 E2E 셀렉터 견고(EC-2).
