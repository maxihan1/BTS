# FR-UX-01 — 퀵 필터 (보드 상단 즉시 필터)

> slug: fr-ux-01-quick-filters
> type: feature (백엔드 D1~D5 + 프론트 D6 + E2E D7 풀스택)
> agent: backend-engineer (진입점) → frontend-engineer (D6) → qa-engineer (D7)
> primary_bc: agile-planning (잠정 — board_quick_filters가 board_id 종속, bts-domain에서 확정)
> 생성: 2026-07-04

## Brief

사용자 원문: "fr-ux-01 진행" (FR-UX-01 퀵 필터).

product 문서 근거 (`docs/plan/product/personalization.md §4.1`):
- **우선순위**: 필수 | **선행**: agile-planning §2.1 (칸반보드) | **Plan slug(문서)**: `personal/quick-filters`
- D1. 도메인 — QuickFilter (backend-engineer)
- D2. 명세 — 보드별 사전 정의 필터 (backend-engineer)
- D3. 데이터 모델 — `board_quick_filters(board_id, name, query)` (db-engineer)
- D4. 백엔드 — CRUD API + 보드 응답에 포함 (backend-engineer)
- D5. 백엔드 테스트 (backend-engineer)
- D6. 프론트 UI — 보드 상단 필터 칩 (designer → frontend-engineer)
- D7. E2E (qa-engineer)

classify 정정 메모:
- classify.ts는 type=backend, primary_bc=agile-planning 판정. "보드" 키워드로 agile-planning 잡음 → 데이터 모델(board_id 종속) 근거상 타당.
- 실제로는 풀스택 feature이므로 type=feature로 정정. UI task는 plan에서 frontend-engineer dispatch.
- personalization은 product 문서상 논리 그룹일 뿐 실제 백엔드 모듈 아님 (FR-UX-02=favorites, FR-UX-03=notification-dashboard).

병렬 작업 주의: FR-IM-02 (Import 매핑 UI) worktree 동시 진행 중. 프론트 필터 인프라(BoardFilterBar.tsx 등) 충돌 여부 spec/plan에서 선점 확인.

## 도메인 정리

- **BC (물리 구현)**: agile-planning 모듈 (`backend/modules/agile-planning/`). 데이터가 `board_id` 종속 → 보드 소유 BC에 둠.
- **BC (논리 소속)**: personalization (product 문서 §4.1 그대로 유지). 논리≠물리 — FR-SR-01 ADR(`2026-06-23-fr-sr-01-issue-filter-bc.md`) 선례 계승. fr-index BC 카운트 변경 없음.
- **신규 엔티티**: `QuickFilter` (도메인) / `board_quick_filters(board_id, name, query)` (테이블). 보드에 1:N 종속.
- **새 용어**: "퀵 필터 (Quick Filter)" — 보드별로 저장된 이름 붙은 필터 조합. 보드 상단 칩 클릭 한 번으로 즉시 적용. (glossary 추가 후보 — Maxi 승인 대기)
- **재사용 대상** (신규 로직 최소화):
  - `BoardCardFilter` VO (shared-kernel `com.bts.shared.board`) — assignee/label/component. 같은 필드 OR, 다른 필드 AND.
  - `BoardFilterQueryParser.parse()` — query 파라미터 → `BoardCardFilter` 파싱 (400 처리 포함).
  - `IssueRepository.buildFilterCondition` / `listVisibleForBoard` — 동적 WHERE + 보안필터 (변경 없음).
  - `BoardController` 권한 게이트 패턴 (actor 추출 → 보드 메타 조회(404) → 권한(403) 순서).
- **Maxi 게이트 결정 (D-domain)**:
  - **query 형식 = BoardCardFilter 재사용**. 퀵필터 `query`는 `assignee/label/component` 파라미터 문자열. 칩 클릭 → 저장된 query를 `GET /boards/{id}` 파라미터로 그대로 적용. AQL(FR-SR-02) 미채택 — 보드 조회 경로가 AQL 미지원이라 범위 폭발 회피.
  - **소유 범위 = 보드 공유**. `board_quick_filters`에 user_id 없음. 보드를 보는 모두가 공유하는 팀 차원 사전정의 필터. CRUD 권한은 spec에서 확정.
- **기존 결정 충돌**: 없음. board filter(FR-BD-02) 위에 저장 레이어 추가. board 조회 동작 보존.
- **관련 ADR**: [docs/decisions/2026-07-04-fr-ux-01-quick-filter-bc.md](../decisions/2026-07-04-fr-ux-01-quick-filter-bc.md) (생성됨)
- **병렬 작업 주의**: FR-IM-02 worktree 동시 진행 중. FR-UX-01은 agile-planning 백엔드 + 보드 상단 프론트(BoardFilterBar 인접). FR-IM-02는 Import 매핑 UI — 프론트 겹침 영역 다름(board vs import). 충돌 위험 낮음. spec에서 재확인.

## 스펙

전체 스펙: [docs/specs/2026-07-04-fr-ux-01-quick-filters.md](../specs/2026-07-04-fr-ux-01-quick-filters.md)

핵심 시나리오 3줄 요약.
- 보드 담당자가 현재 필터(담당자/라벨/컴포넌트)를 이름 붙여 저장 → 보드 상단에 공유 칩 생성 (CREATE 권한).
- 누구나(BROWSE) 칩 클릭 한 번으로 즉시 필터 적용, 재클릭/수동변경 시 해제 (activeQuickFilterId 추적).
- CRUD API는 `/api/v1/boards/{boardId}/quick-filters` nested 리소스, 조회는 BoardDetailResponse에 포함.

핵심 설계 결정.
- query 저장 = 쿼리스트링(BoardFilterQueryParser 검증 + 정규화 재직렬화). AQL/JSON 미채택.
- 재사용: BoardCardFilter VO · BoardFilterQueryParser · buildBoardFilterQuery(프론트) · BoardController 권한 게이트.
- 신규: QuickFilter 도메인 · V504__board_quick_filters.sql · CRUD 서비스/컨트롤러 · serialize 함수 · 칩 UI.
- 권한: 조회 BROWSE / 생성·수정·삭제 CREATE (보드 스윔레인·WIP 변경과 동급).

## Brainstorming Check

✅ 통과 (1회 iteration). gap 5건(query 형식·활성판정·OCC·status범위·정렬) 발견 후 spec 직접 보강.
Maxi 결정 불필요 — 모두 기존 인프라 재사용 극대화 방향으로 확정.

## Plan

모든 백엔드 경로는 `backend/modules/agile-planning/src/{main,test}/kotlin/com/bts/agileplanning/` 기준.
모든 프론트 경로는 `apps/web/src/` 기준.

### Task 1. BoardFilterQueryParser 양방향 확장 (serialize + deserialize + 인코딩 계약)

> 리뷰 BLOCKER-A 반영. 저장/검증/적용에 문자열↔VO 양방향이 필요한데 기존 `parse`는 이미 쪼개진 3개 리스트만 받는다(정방향 절반). deserialize·serialize 신규 + 인코딩 왕복 계약을 이 task에서 확립.

**메타**.
- agent: `backend-engineer`
- files: [`web/BoardFilterQueryParser.kt`, `test/web/BoardFilterQueryParserTest.kt`]
- depends-on: []

**RED**:
- `serialize(filter: BoardCardFilter): String` — VO → 정규 쿼리스트링(`assignee=<uuid>&label=<name>&component=<uuid>`, `?` 없음). 필드 정렬(assignee→unassigned→label→component) + trim + 중복 제거. 빈 필터는 빈 문자열. `URLEncoder`(UTF-8, `application/x-www-form-urlencoded`: 공백→`+`).
- `deserialize(query: String): BoardCardFilter` — 쿼리스트링 split(`&`,`=`) + `URLDecoder`(UTF-8, `+`→공백)로 assignee/label/component 리스트 추출 → 기존 `parse(리스트)` 위임.
- **인코딩 왕복 테스트**: 공백 포함 라벨(`"my bug"`) serialize→deserialize→동등, `+`가 공백으로 복원되는지(리뷰 B1 함정), 센티널 `unassigned` 보존, UUID/특수문자 왕복.
- statusKeys 미직렬화 확인 테스트(board GET 범위 밖).

**GREEN**: `object BoardFilterQueryParser`에 `serialize`/`deserialize` 추가. serialize는 4종만(statusKeys 제외). deserialize는 `parse` 재사용.

**REFACTOR**: 인코딩 계약(x-www-form-urlencoded)·정렬 규칙·statusKeys 제외 사유를 KDoc 명시. serialize↔deserialize↔parse 대칭 구조.

**검증**: `./gradlew :backend:modules:agile-planning:test --tests '*BoardFilterQueryParserTest'`

### Task 2. QuickFilter 도메인

**메타**.
- agent: `backend-engineer`
- files: [`domain/QuickFilter.kt`, `test/domain/QuickFilterTest.kt`]
- depends-on: []

**RED**: `QuickFilter(id, boardId, name, query)` 데이터 클래스 + name 검증(blank 거부, 50자 초과 거부 → `require`). 팩토리 `create` 시 검증.

**GREEN**: 도메인 클래스 + 검증. (query 파싱검증은 서비스 계층 T5, 도메인은 형식만.)

**REFACTOR**: 상수(MAX_NAME_LENGTH=50) 추출 + KDoc.

**검증**: `./gradlew :backend:modules:agile-planning:test --tests '*QuickFilterTest'`

### Task 3. V504 마이그레이션 + init_codegen 미러 + 기존 스키마 테스트 확장

> 리뷰 C6 반영. 신규 QuickFilterSchemaMigrationTest 대신 **기존 BoardSchemaMigrationTest에 검증 추가** — 3번째 Testcontainers 스위트 신설 시 병렬 크래시 위험(memory: concurrent-testcontainers-suite-flaky). 기존 테스트는 컬럼 집합을 containsExactlyInAnyOrder로 단언하므로 신규 테이블이 카운트를 깨지 않음(리뷰 C6 확인).

**메타**.
- agent: `db-engineer`
- files: [`main/resources/db/migration/agile-planning/V504__board_quick_filters.sql`, `main/resources/db/codegen/init_codegen.sql`, `test/migration/BoardSchemaMigrationTest.kt`]
- depends-on: []

**RED**: 기존 `BoardSchemaMigrationTest`에 `board_quick_filters` 검증 추가 — 테이블 존재 + 컬럼(id/board_id/name/query/created_at/updated_at) + FK `board_id→boards(id) ON DELETE CASCADE`(memory: join-table-fk-cascade, 선례 board_columns 동일 패턴) + `UNIQUE(board_id,name)` + index 검증(Testcontainers).

**GREEN**: `V504__board_quick_filters.sql` 작성(spec §데이터 모델). **`init_codegen.sql` 미러 동반**(memory: jooq-init-codegen-mirror — 미러 누락 시 jOOQ codegen 깨짐). V번호는 머지 직전 재확인(memory: migration-vnumber-concurrent-branch-collision — 최신 V503, 다음 V504).

**REFACTOR**: 주석 정리. soft-delete 시 orphan 행은 BoardController 404로 접근 차단(선례 board_columns 일치, 리뷰 C7 — 무해 확인).

**검증**: `./gradlew :backend:modules:agile-planning:test --tests '*BoardSchemaMigrationTest'`

### Task 4. BoardQuickFilterRepository (jOOQ CRUD)

**메타**.
- agent: `backend-engineer`
- files: [`repository/BoardQuickFilterRepository.kt`, `test/repository/BoardQuickFilterRepositoryTest.kt`]
- depends-on: [2, 3]

**RED**: Testcontainers 리포지토리 테스트 — `insert`(UNIQUE 위반 시 예외), `findByBoardId`(created_at ASC), `findByIdAndBoardId`(타보드 null), `update`, `delete`, `countByBoardId`. 시드 보드 사전 INSERT(FK 충족).

**GREEN**: `com.bts.agileplanning.repository` 패키지(memory: archunit-shared-class-move-repository-package)에 jOOQ 리포지토리. UNIQUE 위반은 서비스가 409로 변환하도록 native 예외 노출(memory: jooq-exception-translator-409-dependency — 두 경로 대비).

**REFACTOR**: SQL 상수 추출 + KDoc.

**검증**: `./gradlew :backend:modules:agile-planning:test --tests '*BoardQuickFilterRepositoryTest'`

### Task 5. BoardQuickFilterService (검증 + CRUD 유스케이스)

**메타**.
- agent: `backend-engineer`
- files: [`application/BoardQuickFilterService.kt`, `application/BoardQuickFilterExceptions.kt`, `test/application/BoardQuickFilterServiceTest.kt`]
- depends-on: [1, 4]

**RED**: 서비스 단위 테스트(mockk repository) — 생성/수정 시 (EC1) 빈 query 400(`deserialize`→`isEmpty`), (EC4) query 파싱 실패 400(`BoardFilterQueryParser.deserialize` — T1), (EC2) 중복 409, (EC3) 20건 초과 409(`countByBoardId`, soft cap — 리뷰 C8 TOCTOU 수용), (EC5) 타보드 filterId 404. serialize(T1)로 정규화 저장. **409 dual-path**: mockk가 `DataIntegrityViolationException`(Spring)과 jOOQ-native `IntegrityConstraintViolationException` **두 분기 모두** 던지는 테스트(memory: jooq-exception-translator-409-dependency, 선례 `SprintApplicationService.tryAssignIssue` — 리뷰 C5). mockk `any()` 남발 금지(memory: mockk default+any 가짜그린 — 분기별 명시 인자).

**GREEN**: `@Transactional @Service`(memory: @Service 누락→@Transactional 무력화) CRUD 유스케이스 + 검증. query = deserialize→isEmpty 검증→serialize 정규화 저장. 이름 중복은 두 예외 경로 모두 catch → `QuickFilterNameConflictException`(409). 예외는 `BoardQuickFilterExceptions.kt` 도메인 예외.

**REFACTOR**: 검증 로직 응집 + KDoc. 20건 상한은 soft cap임을 KDoc 명시(원자성 없음).

**검증**: `./gradlew :backend:modules:agile-planning:test --tests '*BoardQuickFilterServiceTest'`

### Task 6. BoardQuickFilterController (POST/PATCH/DELETE + 권한 게이트 + 예외핸들러 스코프)

> 리뷰 BLOCKER-B 반영. 신규 컨트롤러가 기존 권한 게이트 예외(`BoardAccessDeniedException` 403 / `BoardNotFoundException` 404 / `ResponseStatusException` 401)를 재사용하는데, `BoardExceptionHandler`는 `assignableTypes=[BoardController]`로만 스코프 → 신규 컨트롤러에선 401/403/404가 catch-all로 500 변질. **기존 핸들러의 assignableTypes에 신규 컨트롤러를 추가**(별도 전역 advice 신설 금지 — 이중 advice로 BoardController 매핑 오염 위험).

**메타**.
- agent: `backend-engineer` (권한 게이트는 이미 완료된 plan-review에서 security 관점 확인됨)
- files: [`web/BoardQuickFilterController.kt`, `web/BoardExceptionHandler.kt`, `web/dto/QuickFilterDto.kt`, `test/web/BoardQuickFilterControllerIntegrationTest.kt`, `test/web/BoardControllerIntegrationTest.kt`]
- depends-on: [5]

**RED**: HTTP 통합 테스트 — POST/PATCH/DELETE 권한 게이트를 **정확한 status로 단언**(401 미인증 / 404 보드미존재 / 403 CREATE 미충족 / 201·200·204 성공 / 409 이름중복 / 400 빈query). vacuous 금지(실제 status 단언). 권한 fixture userId를 whoami와 정합(memory: e2e-fixture-whoami-userid-alignment). 교차 advice 회귀 차단 위해 **기존 `BoardControllerIntegrationTest` 재실행**(BoardController 에러 매핑 무오염 확인 — memory: domain-exception-http-handler-basepackage-scope).

**GREEN**: 기존 `BoardController` 권한 패턴 재사용 — actor 추출 먼저(memory: auth-extraction-before-resource-lookup) → 보드 메타 조회(404) → `IssuePermissionResolver` CREATE(403) → 동작. `QuickFilterDto.kt`에 request/response DTO(T7과 공유). **`BoardExceptionHandler`의 `assignableTypes`에 `BoardQuickFilterController::class` 추가** → 401/403/404/409/400 매핑 재사용(memory: catch-all-exceptionhandler / domain-exception-http-handler-basepackage-scope). 이름중복 409는 OCC 메시지("다른 변경과 충돌")와 구분되게 `QuickFilterNameConflictException` 전용 `@ExceptionHandler` 추가("같은 이름의 퀵필터가 이미 있습니다" — 리뷰 C4). Guard 예외 message 일반화(memory: fr-pm-04-guard-exception-message-http-leak).

**REFACTOR**: 권한 헬퍼 공유 + KDoc.

**검증**: `./gradlew :backend:modules:agile-planning:test --tests '*BoardQuickFilterControllerIntegrationTest' --tests '*BoardControllerIntegrationTest'`

### Task 7. BoardDetailResponse.quickFilters 확장 (GET /boards/{id} 포함)

**메타**.
- agent: `backend-engineer`
- files: [`web/dto/BoardResponses.kt`, `application/BoardApplicationService.kt`, `web/BoardController.kt`, `test/web/dto/BoardResponsesTest.kt`, `test/application/BoardApplicationServiceTest.kt`, `test/web/BoardControllerIntegrationTest.kt`]
- depends-on: [4, 6]

**RED**: `BoardDetailResponse.quickFilters: List<QuickFilterResponse>` 필드 추가 → `GET /boards/{id}`(BROWSE) 응답에 보드 퀵필터 목록(created_at ASC) 포함. 기존 BoardController/ApplicationService 테스트 갱신.

**GREEN**: `BoardApplicationService.getBoard`가 `BoardQuickFilterRepository.findByBoardId`(created_at ASC) 조회 후 결과에 포함. 생성자에 리포지토리 주입 → 기존 테스트 mock 갱신(memory: plan-files-constructor-injection-existing-tests). **`BoardControllerIntegrationTest`의 @SpringBootTest 설정에 `BoardQuickFilterRepository` mock @Bean 추가**(리뷰 C9 — 부팅 실패 방지). `BoardDetailResponse.of` 시그니처 변경 → 모든 호출자 갱신. `QuickFilterResponse`는 T6의 `QuickFilterDto.kt` 재사용.

**REFACTOR**: DTO 변환 함수 응집.

**검증**: `./gradlew :backend:modules:agile-planning:test --tests '*BoardControllerIntegrationTest' --tests '*BoardApplicationServiceTest'`

### Task 8. 프론트 API + boardDetailSchema 확장 + queryStringToSearch + MSW 핸들러

> 리뷰 C1/C2 반영. 칩 적용은 저장 query 문자열 → URL search 변환(역방향)이 필요 — 신규 `queryStringToSearch`. MSW는 백엔드 정규화를 재현하거나 raw 동등 단언 금지(정규화 drift 가짜그린 차단).

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/api/board-quick-filters.ts`, `apps/web/src/api/boards.ts`, `apps/web/src/lib/board-filter.ts`, `apps/web/src/mocks/board-handlers.ts`, `apps/web/src/mocks/board-fixtures.ts`, `apps/web/src/api/board-quick-filters.test.ts`, `apps/web/src/lib/board-filter.test.ts`, `apps/web/src/mocks/board-handlers.test.ts`]
- depends-on: []

**RED**: Zod 스키마(`quickFilterSchema` — filterId uuid(memory: zod-v4-uuid), name, query) + CRUD fetch(create/update/delete) + `boardDetailSchema`에 `quickFilters: z.array(...).default([])`(memory: @JsonInclude↔Zod, 인라인 mock 파급 grep). `@/lib/board-filter`에 **`queryStringToSearch(query: string): BoardFilterSearch`** — `URLSearchParams`로 파싱(`+`→공백 자동, 인코딩 계약 일치) → assignee/label/component 배열. 왕복 테스트(`buildBoardFilterQuery`→`?`제거→`queryStringToSearch`→`searchToFilter` 동등). MSW **stateful store**(memory: msw-derived-behavior-shared-store — 시드 가능 공유 store)에서 저장 시 백엔드 serialize와 동일 정규화 재현(또는 raw 동등 단언 회피 — 리뷰 C2). 계약 정합(memory: msw↔api 계약 drift 적대리뷰).

**GREEN**: api 파일 + MSW 핸들러 + `queryStringToSearch`.

**REFACTOR**: 스키마/fetch 응집.

**검증**: `pnpm test -- board-quick-filters board-filter board-handlers`

### Task 9. 퀵필터 칩 UI + 저장 다이얼로그 + activeQuickFilterId + 라우트(BoardPage) 통합

> 리뷰 BLOCKER-B 반영. 보드 필터 상태는 **URL search 기반이고 라우트 `projects.$projectKey.board.tsx`(BoardPage)가 소유**한다(KanbanBoard는 prop만 받음). 칩 적용=navigate·activeQuickFilterId 추적은 navigate와 같은 레벨(라우트)에 co-locate해야 FR6이 성립. 칩 렌더 위치도 BoardFilterBar 인접(라우트). FR-IM-02 병렬 worktree는 이 라우트 미수정(충돌 위험 낮음, 확인 완료).

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/board/QuickFilterChips.tsx`, `apps/web/src/components/board/SaveQuickFilterDialog.tsx`, `apps/web/src/hooks/use-board-quick-filters.ts`, `apps/web/src/routes/projects.$projectKey.board.tsx`, `apps/web/src/i18n/quick-filter-labels.ts`, `apps/web/src/components/board/QuickFilterChips.test.tsx`, `apps/web/src/components/board/SaveQuickFilterDialog.test.tsx`]
- depends-on: [8]

**RED**: 칩 렌더(FR6 활성 상태 시각 구분 — 활성 강조 vs 비활성 muted, 기존 `Chip` 스타일 재사용) + "필터 저장" 다이얼로그(현재 필터를 이름 붙여 저장, 빈 필터 시 저장 비활성=EC1) + 편집/삭제 권한 게이팅(FR5 `canManage` = 기존 `useProjectPermissions(projectKey).permissions.CREATE` 재사용 — memory: ui-permission-gating, BoardDetailResponse 권한필드 신설 금지, 리뷰 C4). 저장 다이얼로그 폼은 filterId key 재마운트(memory: react-usestate-stale-key-prop). i18n 라벨은 콜론으로 문장 종결 금지(memory: i18n 콜론).

**GREEN**: 컴포넌트 + `use-board-quick-filters` 훅(CRUD mutation, cross-mutation invalidate — memory: msw-mutation-stateful-refetch·mutation-setquerydata-partial-response-flicker → invalidate). **BoardPage(라우트)에 칩 행 통합** — 칩 클릭 → `queryStringToSearch(query)` → `navigate({search})`(URL 필터 적용) + `activeQuickFilterId` 세팅. **activeQuickFilterId 생명주기(리뷰 C3)**: 재클릭 해제 / 수동 필터변경(BoardFilterBar onChange) 해제 / 활성 퀵필터 삭제 시 초기화 / boardId 전환 시 초기화. KanbanBoard는 미수정(prop 소비만 유지).

**REFACTOR**: 컴포넌트 분리 + 훅 응집.

**검증**: `pnpm test -- QuickFilterChips SaveQuickFilterDialog`

### Task 10. E2E — 퀵필터 저장→적용→삭제

**메타**.
- agent: `qa-engineer`
- files: [`apps/web/e2e/quick-filter.spec.ts`, `apps/web/src/mocks/board-handlers.ts`]
- depends-on: [9]

**RED/시나리오**: 보드 진입 → 필터 적용 → "내 버그" 저장 → 칩 표시 → 새로고침 없이 칩 클릭 시 카드 필터링(memory: MSW 영속 E2E는 SPA 내부이동, reload=가짜그린) → 재클릭 해제 → 삭제로 칩 사라짐. getByRole exact/컨테이너 스코프(memory: playwright-getbyrole-exact-strict-mode). worktree remove 후 5173 kill(memory: e2e-orphan-vite).

**GREEN**: MSW 시나리오 시드(공유 store) + spec happy path.

**검증**: `pnpm test:e2e -- quick-filter`

## Plan 메타

- task 수: 10 (백엔드 7 T1~T7 · 프론트 2 T8~T9 · E2E 1 T10)
- 예상 wave (bts-impl이 depends-on+files로 계산):
  - wave1: T1[], T2[], T3[], T8[] (프론트 T8 백엔드와 병렬)
  - wave2: T4[2,3], T9[8]
  - wave3: T5[1,4], T10[9]
  - wave4: T6[5]
  - wave5: T7[4,6]
  - 백엔드 T1~T7은 같은 agile-planning 모듈 → Gradle 컴파일 직렬화(memory: bts-plan-wave-gradle-module-compile)로 실질 직렬. 프론트/E2E는 독립 병렬.
- TDD 강제: yes (test 커밋이 feat 커밋보다 선행)
- 추가 검증: ktlint, detekt(--rerun-tasks, memory: backend-detekt-lint-debt-unmasked), typecheck(tsconfig.app), vitest, playwright
- 병렬 dispatch: worktree 하나(FR-IM-02와 별도). pre-commit lint-staged race 주의(memory: parallel-dispatch-precommit-hook-race — 자기 파일만 stage)

## 리뷰 결과

독립 에이전트 2종 병렬 리뷰(autoplan 대신 eng 집중 — memory: bts-review-plan-autoplan-overkill). 상보적으로 BLOCKER 3건.

### 설계 리뷰 (Plan 에이전트, 2026-07-04)
- **BLOCKER-A**: query 양방향 (de)serialize 누락 — `parse`는 이미 쪼갠 리스트만 받음. 문자열→VO deserialize·인코딩 왕복(`+`→공백)이 백엔드·프론트 양쪽 미배정 → **T1 양방향 확장 + 인코딩 계약 고정으로 반영**.
- CONCERN: status serialize 누락(→T1 statusKeys 제외 명시), 20건 TOCTOU(→T5 soft cap 명시), 정규화 문구 과대(→spec 정정), 409 메시지 OCC 뉘앙스(→T6 전용 메시지). wave/DTO공유/BC격리/권한게이트 = 건전 확인.

### 회귀/함정 리뷰 (code-reviewer 에이전트, 2026-07-04)
- **BLOCKER-B**: 프론트 통합 오조준 — 보드 필터는 URL search 기반, 라우트 BoardPage 소유. 칩을 KanbanBoard에 넣으면 navigate/FR6 깨짐 → **T9를 라우트(projects.$projectKey.board.tsx) 통합으로 변경, activeQuickFilterId co-locate**.
- **BLOCKER-C**: 신규 컨트롤러 공유 예외 500 변질 — `BoardExceptionHandler` assignableTypes가 BoardController 한정 → **T6에서 assignableTypes에 신규 컨트롤러 추가 + 기존 통합테스트 재실행으로 반영**.
- CONCERN: 칩 적용 역파싱(→T8 queryStringToSearch), MSW 정규화 drift(→T8), activeId 생명주기 4종(→T9 C3), canManage=useProjectPermissions 재사용(→T9 C4), 409 dual-path(→T5 C5), 스키마테스트 컨테이너 절약(→T3 기존 확장), 생성자주입 통합테스트 mock @Bean(→T7 C9). FK CASCADE+soft-delete = 선례 일치 무해 확인.

### design 경량 점검 (메인, 2026-07-04)
- 기존 `Chip` 컴포넌트(rounded-full·44px 터치타겟) 재사용 + 활성 상태 시각 구분만 추가. design BLOCKER 없음.

### 종합
- BLOCKER 3건 모두 plan에 반영 완료(T1/T3/T5/T6/T7/T8/T9 + spec 인코딩 계약).
- 남은 결정 사항: 없음(모든 CONCERN 설계로 해소). glossary "퀵 필터" 등재는 Maxi 승인 대기(머지 시 처리).

## glossary 갱신 대기
- "퀵 필터 (Quick Filter)" — 보드별 저장된 이름 붙은 필터 조합, 칩 클릭 즉시 적용, 보드 공유. (Maxi 승인 후 `Maxi_wiki/BTS/glossary.md` 추가)
