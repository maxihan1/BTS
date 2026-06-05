# FR-PM-05 — 이슈 접근 권한 (Browse, View)

> slug: fr-pm-05-browse-view
> type: auth
> agent: security-engineer
> 생성: 2026-06-05

## Brief

FR-PM-05 — 이슈 접근 권한(Browse/View 분리). 권한 없는 이슈를 조회 결과에서
자동 필터링하고 단건 조회 시 차단. identity-access BC 중심 + 이슈 쿼리 필터
자동 첨부(jOOQ Condition 빌더) + 프론트 권한 없는 이슈 404 처리.

- 선행: §4.2 FR-PM-02 권한 스킴 모델 활용
- 병렬 작업: FR-CM-03(issue-tracking, draft PR #84) 존재 — 공유 자원 충돌 주의
- classify: type=auth, agent=security-engineer, primary_bc=identity-access

plan 문서(docs/plan/product/identity-access.md §4.5) D1~D7:
- D1. 도메인 — BrowsePermission vs ViewPermission 분리 (security-engineer)
- D2. 명세 (security-engineer)
- D3. 데이터 모델 — (FR-PM-02 활용) (db-engineer)
- D4. 백엔드 — 이슈 쿼리에 필터 자동 첨부 (jOOQ Condition 빌더) (security + backend)
- D5. 백엔드 테스트 — 비공개 이슈 조회 차단 (security-engineer)
- D6. 프론트 UI — 권한 없는 이슈 404 처리 (frontend-engineer)
- D7. E2E (qa-engineer)

## 도메인 정리

- BC: identity-access (소유, 판정기) + issue-tracking (포트 호출/스코프)
- 영향 타입: `IssuePermission`(enum, BROWSE 추가), `IdentityAccessIssuePermissionResolver`(toCodeOrNull 매핑), `IssueApplicationService.listIssues/findByKey`, `role_permissions` 시드(신규 마이그레이션)
- 핵심 도메인 결정 (Maxi 확정 2026-06-05):
  - **D1. BROWSE/VIEW 분리** (SDD 12.3 충실) — `IssuePermission.BROWSE` 신규.
    목록(listIssues)=`BROWSE_PROJECT`, 단건(findByKey)=`VIEW_ISSUE`. prod resolver "멤버면 통과" 임시 정책 → 매트릭스 이관.
  - **D2. 미인가 단건 = 404** (존재 숨김, Jira 방식) — 미인증→401, 비멤버/미인가→404, 인가→200.
  - **D3. 시드** — 기본 스킴에 BROWSE_PROJECT + VIEW_ISSUE(역할 2종 모두). `PermissionSchemaMigrationTest` 카운트 갱신 동반.
  - **D4. per-issue 보안 수준(비공개 이슈)은 FR-PM-06으로 분리** — 본 FR은 프로젝트 단위 매트릭스까지.
- 기존 결정 충돌: 없음 (FR-PM-02 resolver KDoc이 VIEW 이관을 FR-PM-05로 예약 → 그 예약 실행)
- 관련 ADR: [docs/decisions/2026-06-05-issue-browse-view-permission.md](../decisions/2026-06-05-issue-browse-view-permission.md) (생성됨)
- 글로서리: "이슈 데이터 접근 권한"에 Browse(목록 가시성)/View(단건 상세) 구분 추가 후보 — Maxi 승인 대기

## 스펙

전체 스펙. [docs/specs/2026-06-05-fr-pm-05-browse-view.md](../specs/2026-06-05-fr-pm-05-browse-view.md)

핵심 시나리오 요약.
- 멤버는 BROWSE_PROJECT로 목록(200), VIEW_ISSUE로 단건(200). 비멤버 목록 403.
- 비멤버/미인가 단건 조회 → **404**(존재 숨김, Jira 방식). findByKey·availableTransitions·clone소스 전 경로 일관.
- VIEW→BROWSE/VIEW 분리(SDD 12.3), prod resolver 매트릭스 이관("멤버면 통과" 임시정책 종료), V014 시드(+4행, 카운트 8→12).
- 컨트롤러 actor 결선·per-issue 보안수준은 범위 밖(후속/FR-PM-06).

## Brainstorming Check

✅ 통과 (자체 적대적 sanity check 1회). 갭3건 반영 — 갭1 단건 VIEW 전경로 404 일관(probe 차단), 갭2 jOOQ 투기 인프라 배제(프로젝트 게이트 한정), 갭3 mutation 403 잔여 문서화. 스펙 §Brainstorming Check 참조.

## Plan

> 검증 표준 = prod 프로파일 Testcontainers 통합테스트(거부 경로). non-prod AlwaysAllow stub은 전 권한 통과로 마스킹.
> 단건 미인가 404 매핑은 mockk resolver(false) 단위테스트로 검증, 매트릭스 판정 정확성은 prod 통합테스트로 검증(2계층).

### Task 1. IssuePermission.BROWSE 추가 + prod resolver 매트릭스 이관

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/shared-kernel/src/main/kotlin/com/bts/shared/permission/IssuePermission.kt`, `backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/permission/IdentityAccessIssuePermissionResolver.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/permission/IdentityAccessIssuePermissionResolverTest.kt`]
- depends-on: []

**RED**:
- resolver 단위테스트(mockk `schemeRepo`/`membershipRepo`/`projectDirectory`):
  - `BROWSE` → `roleHasPermission(projectId, role, "BROWSE_PROJECT")` 위임 검증(멤버여도 매트릭스 false면 false).
  - `VIEW` → `roleHasPermission(..., "VIEW_ISSUE")` 위임 검증(기존 "멤버면 통과" 종료).
- **기존 단언 갱신(필수)** — `IdentityAccessIssuePermissionResolverTest.kt:76/83/88`의 "멤버는 범위 밖 VIEW 허용(schemeRepo 미호출)" 단언은 VIEW가 매트릭스 위임으로 바뀌므로 제거/갱신. 안 하면 mock 미stub로 단언 붕괴(코드리뷰 B2).
- 실패 메시지(예상): `IssuePermission.BROWSE` 미존재(컴파일) → 추가 후 toCodeOrNull이 VIEW=null 반환해 단언 실패(RED).

**GREEN**:
- `IssuePermission`에 `BROWSE` 추가 + KDoc 표에 `BROWSE`(GET /issues 목록) 행 추가, VIEW는 단건으로 한정.
- `toCodeOrNull()` when에 `BROWSE -> "BROWSE_PROJECT"`, `VIEW -> "VIEW_ISSUE"` 추가. null 분기는 TRANSITION/HARD_DELETE만 잔존(VIEW 제거).
- resolver KDoc "범위 밖 임시 정책"에서 VIEW 항목 제거(BROWSE/VIEW 매트릭스 이관 명시).

**REFACTOR**:
- toCodeOrNull 표 KDoc 갱신(VIEW_ISSUE/BROWSE_PROJECT 행).

**검증**: `./gradlew :modules:identity-access:test --tests "*IdentityAccessIssuePermissionResolverTest"`

> 영향 확인 완료 — IssuePermission exhaustive when은 toCodeOrNull 1곳뿐(Component/Version/WorkflowScheme은 별도 enum). `MyIssuePermissionController.UI_ISSUE_PERMISSIONS`는 큐레이트 리스트라 BROWSE 추가 무영향.

### Task 2. V014 시드 마이그레이션 + PermissionSchemaMigrationTest 카운트 갱신

**메타**.
- agent: `db-engineer`
- files: [`backend/modules/identity-access/src/main/resources/db/migration/V014__browse_view_issue_permissions.sql`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/permission/PermissionSchemaMigrationTest.kt`]
- depends-on: []

**RED**:
- `PermissionSchemaMigrationTest`: 카운트 단언 8 → **12** 갱신 + 주석 갱신(PROJECT_ADMIN 8 / MEMBER 4). 신규 테스트 `기본 스킴이 BROWSE_PROJECT VIEW_ISSUE를 양 역할에 보유한다`(PROJECT_ADMIN·MEMBER 각각 2코드 = 4행) 추가.
- 실패(예상): V014 부재로 8행 → 12 단언 실패.

**GREEN**:
- `V014__browse_view_issue_permissions.sql`: 기본 스킴 PROJECT_ADMIN·MEMBER에 `BROWSE_PROJECT`,`VIEW_ISSUE` INSERT(+4행). **V013/V008 실제 패턴 복제(코드리뷰 C1 정정) — 하드코딩 리터럴 scheme_id `'00000000-0000-0000-0000-000000000001'` + 평이한 `INSERT ... VALUES`. 서브쿼리·ON CONFLICT 없음**(`role_permissions`의 `UNIQUE(scheme_id, role, permission_code)` 제약이 중복 방지).

**REFACTOR**:
- 마이그레이션 헤더 주석(한국어 1줄 역할).

**검증**: `./gradlew :modules:identity-access:test --tests "*PermissionSchemaMigrationTest"`

> init_codegen 미러 불요(행 추가만, 컬럼 불변 — 메모리 `jooq-init-codegen-mirror`는 컬럼 추가시만 해당). V014 머지 직전 `git fetch + ls`로 V번호 재확인(동시 브랜치 선점 함정).

### Task 3. 단건 VIEW 404 가드 헬퍼 + listIssues BROWSE 전환 (issue-tracking 서비스)

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/application/IssueApplicationService.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/application/IssueApplicationServicePermissionTest.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/application/IssueApplicationServiceFindTest.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/application/IssueApplicationServiceListTest.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/application/IssueApplicationServiceAvailableTransitionsTest.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/application/IssueApplicationServiceCloneTest.kt`]
- depends-on: [1]

**RED**:
- `IssueApplicationServicePermissionTest.kt`(신규) 단위테스트(mockk `IssuePermissionResolver`):
  - `findByKey` — VIEW false → `IssueNotFoundException`(404), `IssueAccessDeniedException` 아님.
  - `availableTransitions` — VIEW false → `IssueNotFoundException`.
  - `cloneIssue` 소스 — 소스 VIEW false → `IssueNotFoundException`.
  - `listIssues` — `hasPermission(actor, BROWSE, IssueScope.Project)` 호출 검증, false → `IssueAccessDeniedException`(403 유지).
- **기존 테스트 갱신(필수, 코드리뷰 B1)** — 변경 동작을 직접 단언하는 기존 4종을 같이 수정:
  - `IssueApplicationServiceFindTest.kt:122-123` — findByKey VIEW false `IssueAccessDeniedException` 단언 → `IssueNotFoundException`(404).
  - `IssueApplicationServiceListTest.kt:88-147` — 전 mock의 `hasPermission(..., VIEW, Project)` stub 키 → `BROWSE`로 갱신(미갱신 시 mockk 기본값으로 전 list 테스트 붕괴).
  - `IssueApplicationServiceAvailableTransitionsTest.kt:128/172/197` — VIEW false 경로 단언 → 404.
  - `IssueApplicationServiceCloneTest.kt` — 소스 VIEW false `IssueAccessDeniedException` → `IssueNotFoundException`(import 포함).
- 실패(예상): 현재 VIEW 미인가가 AccessDenied → NotFound 단언 실패(RED).

**GREEN**:
- private 헬퍼 `assertViewIssueOrNotFound(actor, key)`: `!hasPermission(actor, VIEW, IssueScope.Issue(key)) → throw IssueNotFoundException(key)`.
- `findByKey`(266)/`availableTransitions`(383)/`cloneIssue` 소스(198)의 `assertPermission(... VIEW ...)` → 헬퍼 치환.
- `listIssues`(637): `assertPermission(actor, VIEW, Project)` → `assertPermission(actor, BROWSE, Project)`.

**REFACTOR**:
- 헬퍼 KDoc(존재 숨김 정책 + ADR 링크). mutation 검증(UPDATE/SOFT_DELETE/TRANSITION)은 assertPermission(403) 그대로.

**검증**: `./gradlew :modules:issue-tracking:test --tests "*IssueApplicationServicePermissionTest"`

> ⚠️ **FR-CM-03(draft PR #84) 공유 충돌** — 같은 `IssueApplicationService.kt`를 수정 중(import L6, createIssue L129-156, 클래스 말미 helper `resolveDefaultAssignee` L879). 본 task는 198/266/383/637 메서드 본문 + 신규 helper. **메서드 본문은 겹치지 않으나, 양쪽 다 import 상단 + 클래스 말미 helper 추가 → git 3-way 텍스트 충돌 가능 지대**(코드리뷰 C3, auto-merge 단정 금지). 머지 직전 충돌 수동 확인 필수, 먼저 머지된 쪽 기준 rebase.

### Task 4. prod 통합테스트 — resolver 매트릭스 허용/거부 (identity-access)

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/permission/IdentityAccessIssuePermissionResolverIntegrationTest.kt`]
- depends-on: [1, 2]

**RED** (기존 FR-PM-02 통합테스트에 BROWSE/VIEW 케이스 추가 — 코드리뷰 N2/B2):
- `@ActiveProfiles("prod")` Testcontainers(기존 파일에 케이스 추가, 신규 파일 생성 대신 중복 fixture 회피):
  - 멤버(기본 스킴) + BROWSE_PROJECT 시드 → `hasPermission(actor, BROWSE, Project)` = true (S1).
  - 비멤버 → BROWSE false (S2), VIEW false (S4).
  - 멤버 + VIEW_ISSUE → `hasPermission(actor, VIEW, Issue)` = true (S3).
- **기존 단언 갱신(필수, B2)** — `IdentityAccessIssuePermissionResolverIntegrationTest.kt:277`의 "MEMBER + VIEW(범위 밖) → 멤버 통과" 라벨/주석을 "VIEW_ISSUE 매트릭스 판정"으로 갱신(stale 라벨이 신규 동작과 모순된 채 동결되는 것 차단).
- 실패(예상): BROWSE 케이스 신규 + VIEW 시맨틱 변경 → 단언 실패.

**GREEN**:
- 시드/멤버십 fixture + prod resolver(`IdentityAccessIssuePermissionResolver`). Task 1·2 산출물로 그린.

**REFACTOR**:
- fixture 헬퍼 추출.

**검증**: `./gradlew :modules:identity-access:test --tests "*IdentityAccessIssuePermissionResolverIntegrationTest"`

### Task 5. 프론트 — 권한 없는 이슈 상세 404 not-found UI

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/routes/issues.$key.tsx`, `apps/web/src/routes/issues.$key.test.tsx`, `apps/web/src/mocks/issue-handlers.ts`]
- depends-on: []

> **코드리뷰 C2 정정** — `issues.$key.tsx:301-306`은 이미 `error !== null || issue === undefined` catch-all로 모든 에러를 `issueDetailStrings.notFound` 렌더. **S6는 현재 코드로 이미 충족**. Task 5는 **회귀 가드 테스트 + MSW 404 핸들러 추가만**(분기 신설/catch-all 축소 금지 — 비-404를 not-found에서 떼어내면 회귀 위험).

**RED**:
- route 테스트(신규): 단건 GET 404 응답 시 `issueDetailStrings.notFound` 화면 렌더 단언(권한 없는 이슈 = 미존재 동일 UX 계약 고정).
- MSW 핸들러: 특정 issueKey에 404 응답 시나리오(localStorage 플래그 토글, 메모리 `e2e-msw-scenario-toggle-localstorage-flag` 패턴).
- 실패(예상): 신규 테스트가 아직 MSW 404 시나리오 미배선.

**GREEN**:
- MSW 404 핸들러 추가. **프로덕션 코드(issues.$key.tsx) 로직 변경 없음** — 기존 catch-all이 계약 충족.

**REFACTOR**:
- MSW 핸들러 시나리오 주석.

**검증**: `pnpm --filter @bts/web test --run issues.\$key && pnpm --filter @bts/web typecheck`

### Task 6. E2E — 권한 없는 이슈 진입 not-found (qa)

**메타**.
- agent: `qa-engineer`
- files: [`apps/web/e2e/issue-access-permission.spec.ts`, `apps/web/src/mocks/issue-handlers.ts`]
- depends-on: [5]

**RED**:
- Playwright: 권한 없는 사용자가 `/issues/ATLAS-1` 직접 진입 → not-found 화면(에러 토스트/스택 노출 0). MSW 404 시나리오.
- 기존 이슈 E2E 회귀 0 동반 실행(메모리 `ui-pr-defer-e2e-regression-latent` — 새 화면이 기존 셀렉터 strict mode 안 깨는지).

**GREEN**:
- spec + MSW 시나리오. serviceWorkers 'block' 금지(MSW 부팅, 메모리 `e2e-msw-serviceworker-block`).

**REFACTOR**:
- 셀렉터 컨테이너 한정(텍스트 중복 strict mode 회피).

**검증**: `pnpm --filter @bts/web exec playwright test issue-access-permission`

## Plan 메타

- task 수: 6
- 예상 wave: 3 — Wave1 [T1, T2, T5](depends-on []), Wave2 [T3(dep 1), T4(dep 1,2)], Wave3 [T6(dep 5)]
- TDD 강제: yes (test→feat 커밋 순서 controller 검증)
- 병렬 dispatch: bts-impl이 depends-on + files 교집합으로 wave 계산
- 추가 검증: ktlintMainSourceSetCheck + ktlintTestSourceSetCheck + detekt(4모듈), pnpm typecheck, vitest, playwright
- 공유 충돌 주의: T3 ↔ FR-CM-03 PR #84 (IssueApplicationService.kt import/helper 텍스트 충돌 지대) / V014 ↔ 동시 브랜치 V번호
- wave 직렬화 보강(N1): T1·T2·T4가 동일 `identity-access` test 컴파일 단위 공유(메모리 `bts-plan-wave-gradle-module-compile`) → 병렬 dispatch여도 같은 모듈 test 컴파일 직렬화. wave 산정엔 영향 없음.

## 리뷰 결과

### code-reviewer ground-truth 독립 리뷰 (2026-06-05)

백엔드 권한 FR이라 autoplan 4종 대신 code-reviewer가 plan/spec/ADR 주장을 main 트리 코드와 직접 대조(메모리 `bts-review-plan-autoplan-overkill`).

**BLOCKER 2건 — 모두 해소**:
- **B1** — Task 3 변경으로 깨지는 기존 테스트 4종(Find/List/AvailableTransitions/Clone)이 files 누락(메모리 `plan-files-constructor-injection-existing-tests` 재현). → Task 3 files에 4종 추가 + RED에 "기존 403/VIEW-mock 단언 → 404/BROWSE 갱신" 명시. ✅
- **B2** — Task 1 VIEW 매트릭스 이관으로 깨지는 resolver 테스트 2종. → Task 1 RED에 기존 멤버-통과 단언 갱신 명시 + Task 4를 신규 파일 대신 기존 `IdentityAccessIssuePermissionResolverIntegrationTest.kt` 갱신(stale 라벨 L277 정정 포함, N2 동시 해소). ✅

**CONCERN 3건 — 모두 정정**:
- **C1** — V008/V013 시드 패턴 오기("scheme_id 서브쿼리 + ON CONFLICT" 부재). → Task 2 GREEN을 "리터럴 UUID + 평이 INSERT VALUES, UNIQUE 제약이 중복방지"로 정정. ✅
- **C2** — 프론트는 이미 catch-all로 모든 에러를 not-found 렌더(S6 충족). 분기 신설은 회귀 위험. → Task 5를 "회귀 가드 테스트 + MSW 404 핸들러만, 프로덕션 로직 무변경"으로 축소. ✅
- **C3** — FR-CM-03 충돌 "auto-merge 높음" 과소평가(import/helper 텍스트 충돌 지대). → Task 3 노트 정정. ✅

**검증된 정확 항목(환각 아님)**: SDD 12.3 정합, 404 전수 3곳(누락 read 경로 없음→probe 위험 부재), exhaustive when 1곳, V014 카운트 8→12, prod 통합 ground-truth, 스코프 경계 정당, 404 정보누출 0.

**종합 판정**: B1/B2/C1/C2/C3 해소 완료 → 구현 착수 가능.
