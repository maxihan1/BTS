# FR-VR-04 — 버전 릴리즈 노트 자동 생성

> slug: fr-vr-04-versions-release-notes
> type: api (풀스택 — 백엔드 GET API + 프론트 미리보기/복사 UI)
> agent: backend-engineer (+ frontend-engineer / qa-engineer)
> 생성: 2026-06-10
> FR: FR-VR-04 (issue-tracking BC, 중요도 중간)
> 선행: FR-VR-01(버전 생성) · FR-VR-03(Affects/Fix Version 연결) — 둘 다 완료

## Brief

특정 버전을 Fix Version으로 연결한 이슈들을 모아 Markdown 형식 릴리즈 노트를 자동 생성한다.

- D1. 도메인 (backend-engineer)
- D2. 명세 — Markdown 템플릿 (backend-engineer)
- D3. 데이터 모델 — (활용, 새 테이블 없음) (db-engineer)
- D4. 백엔드 — `GET /api/v1/versions/{id}/release-notes` (backend-engineer)
- D5. 백엔드 테스트 (backend-engineer)
- D6. 프론트 UI — 미리보기 + 복사 (designer → frontend-engineer)
- D7. E2E (qa-engineer)

classify: { type: api, agent: backend-engineer, primary_bc: issue-tracking }
product 정본: docs/plan/product/issue-tracking.md §3.2.4
SDD 참조: §3.2.4

## 도메인 정리

- **BC**: issue-tracking
- **영향 엔티티** (모두 기존, 읽기 전용으로 사용):
  - `Version` (`com.bts.issue.version.domain.Version`) — 이름·상태·releaseDate 등 릴리즈 노트 헤더 정보
  - `Issue` (`com.bts.issue.domain.Issue`) — `fixVersionIds`, `key`, `summary`, `typeId`, `resolutionId`
  - `IssueType` (`com.bts.issue.type`) — `typeId → 타입명` (그룹핑 라벨)
- **새 엔티티/테이블**: **없음** (D3 = 활용). 릴리즈 노트는 영속화하지 않고 요청 시 생성(조회 전용).
- **새 도메인 개념**: **릴리즈 노트(Release Notes)** — 한 버전을 Fix Version으로 가진 활성 이슈들을 모아 만든 Markdown 문서. glossary 추가 후보(게이트 1에서 Maxi 확인).
- **신규 repository 메서드 필요**: 버전 ID → 그 버전을 fix version으로 가진 활성 이슈 역방향 조회 (`IssueRepository`에 추가). 현재는 issue→version 방향(`findFixVersionIdsByIssue`)만 존재.
- **Issue에 워크플로우 상태 없음**: 상태 전환은 project-workflow BC 위임. issue-tracking에서는 `resolutionId`로 해결 여부 표현 → 릴리즈 노트 필터/그룹핑은 타입·resolution 축만 사용(cross-BC 회피).
- **권한**: 기존 `VersionPermissionResolver` READ 패턴 재사용. actorId는 `SYSTEM_ACTOR_UUID` placeholder(FR-PM-03 이연), Security 필터가 401 보장.
- **기존 결정 충돌**: 없음.
- **관련 ADR** (참조, 충돌 없음): docs/adr/2026-06-03-version-model-and-permission-deferral.md, 2026-06-10-version-status-and-transitions.md

### ⚠️ spec 단계로 넘길 핵심 결정 (옵션 논의 필요)

1. **엔드포인트 경로** — VersionController가 `/api/v1/projects/{projectIdOrKey}/versions` 하위이므로, 릴리즈 노트도 `GET /api/v1/projects/{projectIdOrKey}/versions/{id}/release-notes`로 정렬해야 함. product 문서의 `GET /api/v1/versions/{id}/release-notes`는 약식 표기.
2. **응답 형식** — JSON `{ data: { markdown, ... } }` vs `text/markdown` raw.
3. **Markdown 그룹핑 기준** — 이슈 타입별 섹션(Bug/Story/Task…).
4. **포함 이슈 범위** — fix version 연결 활성 이슈 전부 vs resolution 보유분만.
5. **정렬 순서** — 이슈 키 / 생성순 등.

## 스펙

전체 스펙: [docs/specs/2026-06-10-fr-vr-04-versions-release-notes.md](../specs/2026-06-10-fr-vr-04-versions-release-notes.md)

확정된 핵심 결정 5건.
1. 엔드포인트: `GET /api/v1/projects/{projectIdOrKey}/versions/{id}/release-notes` (VersionController 매핑 일관)
2. 응답: JSON `DataResponse<ReleaseNotesResponse>` — `{ projectKey, versionName, versionStatus, releaseDate, issueCount, generatedAt, markdown }` (Maxi 결정)
3. 그룹핑: 이슈 타입별 섹션 (hierarchy_level 순, 그룹 내 키 순)
4. 포함 범위: Fix Version 연결 활성 이슈 **전부** (상태 무관, resolution은 표시만) (Maxi 결정)
5. 데이터: 신규 테이블 없음. IssueRepository에 버전→이슈 역방향 조회 메서드 추가.

3줄 요약.
- 버전의 fix version 연결 활성 이슈를 타입별로 묶어 Markdown 릴리즈 노트를 요청 시 생성(영속 안 함)
- 응답은 메타데이터 + markdown 본문을 JSON으로 래핑, 권한/Clock/에러는 기존 Version 패턴 재사용
- 프론트는 버전 행에 "릴리즈 노트" 액션 → 미리보기 다이얼로그 + 클립보드 복사

## Brainstorming Check

✅ 통과 (1회 자가 점검). gap 1건(헤더 프로젝트 식별 누락) 발견 후 spec에 projectKey 보강. 구현 주의점 4건(resolution 다건 주입 / IssueType 표준 순서 / projectKey ProjectLookup / 클립보드 secure-context) plan에 인계.

## Plan

> 패키지 기준: `com.bts.issue` (issue-tracking 모듈). 모든 백엔드 task는 `:backend:modules:issue-tracking` 컴파일 단위.
> 검증 게이트(공통): `./gradlew :backend:modules:issue-tracking:test ktlintCheck detekt` (백엔드), `pnpm typecheck lint test` (프론트), `pnpm test:e2e` (E2E).

### Task 1. IssueRepository 역방향 조회 2종 (버전→이슈, projectId→projectKey)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/repository/IssueRepository.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/project/ProjectLookup.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/repository/IssueRepositoryReleaseNotesTest.kt`]
- depends-on: []

**RED**:
- Testcontainers 통합테스트 `IssueRepositoryReleaseNotesTest`:
  - `findFixVersionIssuesForReleaseNotes(versionId)` 가 해당 버전을 fix version 으로 가진 활성 이슈만(`deleted_at IS NULL`) 반환, soft-delete 이슈 제외, 타입 정보(typeId/typeKey/typeName) 동봉.
  - `ProjectIdRepository.findProjectKeyById(projectId)` 가 활성 프로젝트 key 반환, 미존재 null.
- 실패: 두 메서드 미존재.

**GREEN**:
- `IssueRepository.findFixVersionIssuesForReleaseNotes(versionId: UUID): List<ReleaseNoteIssueRow>` — `ISSUE_FIX_VERSIONS` JOIN `ISSUES`(deleted_at IS NULL) JOIN `ISSUE_TYPES`, 단일 쿼리(cartesian product 회피 — 메모리 jooq-leftjoin-count). 반환 row 에 `key/summary/typeId/typeKey/typeName/resolutionId` 포함.
- `ProjectIdRepository`(ProjectLookup.kt 내)에 `findProjectKeyById(id: UUID): String?` 추가 — 기존 `findActiveProjectIdByKey` 역방향.
- `ReleaseNoteIssueRow` data class 는 IssueRepository.kt 내부 또는 인접 정의.

**REFACTOR**: 컬럼 매핑 KDoc, 정렬은 Service/Generator 책임이므로 repository 는 raw 반환.

**검증**: `./gradlew :backend:modules:issue-tracking:test --tests '*IssueRepositoryReleaseNotesTest'`

### Task 2. ReleaseNotesGenerator — 순수 Markdown 조립 도메인

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/version/releasenotes/ReleaseNotesGenerator.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/version/releasenotes/ReleaseNotesGeneratorTest.kt`]
- depends-on: []

**RED**:
- 단위테스트 `ReleaseNotesGeneratorTest`:
  - 입력(projectKey, versionName, versionStatus, releaseDate, List<ReleaseNoteIssue>) → spec §5 템플릿대로 markdown 생성.
  - 타입별 그룹핑 + 그룹 hierarchy_level 순 + 그룹 내 이슈 키 순.
  - resolution 있으면 ` (Fixed)` 접미, 없으면 미표기.
  - 이슈 0건 → `포함된 이슈가 없습니다.` 한 줄.
  - summary 내 줄바꿈 → 공백 치환.
  - releaseDate null → `미지정`.
- 실패: `ReleaseNotesGenerator` 미존재.

**GREEN**:
- `ReleaseNotesGenerator.generate(input): String` 순수 함수. `ReleaseNoteIssue`(key, summary, typeName, hierarchyLevel, typeKey, resolutionName?) 입력 모델 정의.
- 그룹핑: `groupBy(typeKey)` 후 `(hierarchyLevel, typeName)` 정렬, 그룹 내 `sortedBy(key)`.

**REFACTOR**: 템플릿 상수 추출, 이스케이프 헬퍼(`sanitizeSummary`) 분리.

**검증**: `./gradlew :backend:modules:issue-tracking:test --tests '*ReleaseNotesGeneratorTest'`

### Task 3. ReleaseNotesService — 조회 조합 + Clock 주입

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/version/releasenotes/ReleaseNotesService.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/version/releasenotes/ReleaseNotes.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/version/releasenotes/ReleaseNotesServiceTest.kt`]
- depends-on: [1, 2]

**RED**:
- 단위테스트(mockk repository): `generate(actorId, projectIdOrKey, versionId)` 가
  - resolveProject 미존재 → `VersionProjectNotFoundException`(404).
  - findActiveVersion 미존재 → `VersionNotFoundException`(404).
  - 이슈 역방향 조회 + `ResolutionRepository.findAllActive()` 맵으로 resolutionId→name 주입.
  - projectKey 조회 + Generator 호출 → `ReleaseNotes`(projectKey, versionName, versionStatus, releaseDate, issueCount, generatedAt=Instant.now(clock), markdown).
  - Clock 고정 시 generatedAt 결정적.
- 실패: `ReleaseNotesService` 미존재.

**GREEN**:
- `@Service @Transactional(readOnly = true) ReleaseNotesService(projectLookup, versionRepo, issueRepo, resolutionRepo, clock=Clock.systemUTC())`.
- READ 게이트 없음(VersionApplicationService.getById 정책 동일). resolveProject(404) + findActiveVersion(404)만.
- `ReleaseNotes` 출력 data class.

**REFACTOR**: resolution 맵 빌드 헬퍼, row→ReleaseNoteIssue 매핑 헬퍼 분리.

**검증**: `./gradlew :backend:modules:issue-tracking:test --tests '*ReleaseNotesServiceTest'`

### Task 4. GET 엔드포인트 (별도 ReleaseNotesController) + ReleaseNotesResponse DTO + 통합테스트

> eng-review 결정: VersionController에 끼워넣지 않고 **별도 컨트롤러**로 분리. 이유 — (1) VersionController는 이미 7 엔드포인트+`TooManyFunctions` Suppress 상태, (2) 생성자에 ReleaseNotesService 주입 시 기존 VersionControllerIntegrationTest 파급(메모리 plan-files-constructor-injection). 별도 컨트롤러는 단일 책임 + 기존 테스트 완전 무영향.

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/version/web/ReleaseNotesController.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/version/web/dto/ReleaseNotesResponse.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/version/web/ReleaseNotesIntegrationTest.kt`]
- depends-on: [3]

**RED**:
- Testcontainers 통합테스트 `ReleaseNotesIntegrationTest` (spec §2 시나리오):
  - S1 200 + ReleaseNotesResponse(메타+markdown), 타입별 그룹.
  - S2 0건 → 200, issueCount=0, 안내 markdown.
  - S3 resolution 표시.
  - S4 버전 미존재 404 `VERSION_NOT_FOUND`(errorCode 바디 단언 — 메모리 패턴).
  - S5 프로젝트 미존재 404 `VERSION_PROJECT_NOT_FOUND`.
  - S7 ARCHIVED 200 / soft-delete 404.
- 실패: 엔드포인트 미존재 404 라우팅.

**GREEN**:
- `@RestController @RequestMapping("/api/v1/projects/{projectIdOrKey}/versions/{versionId}/release-notes") class ReleaseNotesController(service: ReleaseNotesService)` + `@GetMapping fun get(...)` — `ReleaseNotesService.generate` 위임, `DataResponse(ReleaseNotesResponse.from(releaseNotes))`. actorId는 `SYSTEM_ACTOR_UUID` placeholder(VersionController 패턴 동일).
- `ReleaseNotesResponse`(versionId, projectKey, versionName, versionStatus, releaseDate, issueCount, generatedAt, markdown) + `from(ReleaseNotes)`.

**REFACTOR**: KDoc(@throws 404 종류), 컨트롤러는 트랜잭션 경계 없음 확인(서비스 `@Transactional(readOnly=true)`가 담당).

**검증**: `./gradlew :backend:modules:issue-tracking:test --tests '*ReleaseNotesIntegrationTest'` + 전체 모듈 그린.

### Task 5. 프론트 API + Zod 스키마 + 훅 + MSW 핸들러

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/api/versions.ts`, `apps/web/src/api/versions.types.ts`, `apps/web/src/hooks/use-versions.ts`, `apps/web/src/mocks/version-handlers.ts`, `apps/web/src/api/versions.test.ts`, `apps/web/src/mocks/version-handlers.test.ts`]
- depends-on: [4]

**RED**:
- `versions.test.ts`: `getReleaseNotes(projectKey, versionId)` 가 `GET .../release-notes` 호출 후 Zod 파싱한 ReleaseNotes 반환. MSW fixture 로 응답.
- 실패: 함수/스키마 미존재.

**GREEN**:
- `versions.types.ts`: `ReleaseNotesResponseSchema` Zod — **spec §4 응답 계약 정확히 일치**(메모리 frontend-zod-backend-dto-contract-gap: backend DTO 그대로, invent 금지). 필드 grep 대조.
- `versions.ts`: `getReleaseNotes`.
- `use-versions.ts`: `useReleaseNotes(projectKey, versionId, enabled)` React Query 훅(요청 시점 lazy — dialog open 시 enabled).
- `version-handlers.ts`: release-notes MSW 핸들러 + fixture(markdown 포함).

**REFACTOR**: 기존 versions API 관례(BC별 convention 메모리) 준수, CSRF 불필요(GET).

**검증**: `pnpm --filter web test -- versions version-handlers` + `pnpm typecheck`

### Task 6. ReleaseNotesDialog 컴포넌트 + VersionRow 액션 버튼

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/version/ReleaseNotesDialog.tsx`, `apps/web/src/components/version/VersionRow.tsx`, `apps/web/src/components/version/ReleaseNotesDialog.test.tsx`, `apps/web/src/components/version/VersionRow.test.tsx`]
- depends-on: [5]

**RED**:
- `ReleaseNotesDialog.test.tsx`: open 시 `useReleaseNotes` 데이터 렌더(markdown 미리보기 `<pre>`/렌더), 복사 버튼 클릭 → `navigator.clipboard.writeText(markdown)` 호출(mock). 로딩/에러 상태.
- `VersionRow.test.tsx`: "릴리즈 노트" 버튼 존재 + 클릭 시 dialog open. (행 컨테이너 한정 셀렉터 — 메모리 playwright/ui 중복).
- 실패: 컴포넌트/버튼 미존재.

**GREEN**:
- `ReleaseNotesDialog`(radix Dialog 직접 import — 메모리 shadcn Dialog 래퍼 부재) — markdown `<pre>` 미리보기 + 복사 버튼(clipboard) + 복사 완료 토스트/표시.
- `VersionRow` 에 "릴리즈 노트" 버튼(ARCHIVED 무관 활성 — 읽기 동작) + dialog state.

**REFACTOR**: clipboard 실패(secure-context 아님) graceful 처리, i18n 라벨 version-labels.ts 합류.

**검증**: `pnpm --filter web test -- ReleaseNotesDialog VersionRow` + `pnpm typecheck lint`

### Task 7. E2E — 릴리즈 노트 미리보기 + 복사 happy path

**메타**.
- agent: `qa-engineer`
- files: [`apps/web/e2e/version-release-notes.spec.ts`, `apps/web/src/mocks/version-handlers.ts`]
- depends-on: [6]

**RED**:
- `version-release-notes.spec.ts`: 버전 관리 화면 진입(session-fixtures `loginAsAlice` 2단계 — 메모리 FR-AU-07 회귀) → 버전 행 "릴리즈 노트" 클릭 → dialog 에 markdown 미리보기 노출 → 복사 버튼 클릭 → 복사 완료 표시. clipboard 권한 grant(`context.grantPermissions(['clipboard-read','clipboard-write'])`).
- MSW 시나리오: release-notes 응답(기존 stateful store 재사용 — 메모리 msw-derived-behavior-shared-store).

**GREEN**: E2E 통과까지 셀렉터/대기 조정(드롭다운/dialog 로딩 대기 — 메모리 worktree-stale-base-rebase-and-e2e-msw-traps).

**REFACTOR**: 기존 version-management E2E 회귀 동시 실행 확인(메모리 ui-pr-defer-e2e).

**검증**: `pnpm --filter web test:e2e -- version-release-notes` + 기존 version E2E 회귀.

## Plan 메타

- task 수: 7
- 예상 wave: 6 (wave1: T1∥T2, wave2: T3, wave3: T4, wave4: T5, wave5: T6, wave6: T7). 백엔드→프론트→E2E 자연 직렬 + 계약 일치 위해 프론트는 백엔드 DTO(T4) 확정 후.
- TDD 강제: yes (test 커밋이 feat 커밋보다 먼저)
- 추가 검증: ktlint/detekt(백), typecheck/lint/vitest(프론트), playwright(E2E)
- BC 격리: issue-tracking 단일 BC. cross-BC 호출 없음(워크플로우 상태 미조회).
- 신규 테이블/마이그레이션: 없음.

## 리뷰 결과

### plan-eng-review (2026-06-10, 집중 독립 리뷰 — autoplan overkill 회피)

- ✅ TDD 구조: 7 task 모두 RED/GREEN/REFACTOR + 검증 명시.
- ✅ 메타 블록: agent/files/depends-on 완비, 순환 없음 (T1∥T2 → T3 → T4 → T5 → T6 → T7).
- ✅ 파일 겹침 자동 직렬화: T5·T7이 version-handlers.ts 공유하나 depends-on 직렬이라 동일 wave 회피.
- ✅ cartesian product 회피: versionId 필터 시 issue당 fix_versions 최대 1행(복합 PK), ISSUE_TYPES는 1:1 — 안전. plan에 명시.
- ✅ Clock 주입(T3 generatedAt 결정적), READ 게이트 없음(getById 정책 일치, spec 정정 반영), 도메인 mutation 없음(읽기 전용).
- ✅ N+1 회피: resolution은 findAllActive() 맵 1회.
- **🔧 개선 반영 (BLOCKER 아님)**: T4를 VersionController 끼워넣기 → **별도 ReleaseNotesController** 분리. VersionController TooManyFunctions Suppress + 생성자 주입 기존 테스트 파급(메모리 plan-files-constructor-injection) 회피.
- ⚠️ 주의(impl 인계): (a) T1 ProjectIdRepository 메서드 추가는 ProjectLookupTest 무영향(생성자 불변)이나 새 메서드 테스트 추가. (b) ReleaseNotesGenerator 그룹핑 로직 detekt Complexity/MaxLineLength 가능 — RED 단계에서 의식. (c) ReleaseNoteIssueRow(repository row)와 ReleaseNoteIssue(generator 입력)는 layer 분리상 의도적 별도 모델.
- BLOCKER: 없음.

### plan-devex-review (2026-06-10)

- ✅ 엔드포인트 경로 `/api/v1/projects/{projectIdOrKey}/versions/{id}/release-notes` — VersionController 매핑 일관.
- ✅ 응답 DataResponse 래핑, 에러코드 기존 VersionErrorCodes 재사용(신규 0) — BC 일관.
- ✅ 신규 엔드포인트라 API 호환성 breaking 없음.
- ✅ Zod 계약(T5)은 spec §4 정확 일치 강제(메모리 frontend-zod-backend-dto-contract-gap) + 필드 grep 대조.
- ⚠️ 주의: 클립보드 복사는 secure-context 필요 — localhost는 Playwright에서 secure 처리, E2E grantPermissions 명시(T7).
- BLOCKER: 없음.

**종합: BLOCKER 0건. 개선 1건(별도 컨트롤러) plan 반영 완료. 게이트 1 진입 가능.**
