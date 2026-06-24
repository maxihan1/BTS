# FR-BL-01+FR-BL-02 D6/D7 — 백로그↔스프린트 프론트 UI + 백로그 조회 API + E2E

> slug: fr-bl-01-02-d6-d7-backlog-sprint-ui-e2e
> type: ui
> agent: frontend-engineer (백엔드 조회 API task=backend-engineer, E2E task=qa-engineer plan 메타)
> primary_bc: agile-planning
> 생성: 2026-06-24

## Brief

FR-BL-01(백로그 우선순위 LexoRank, 백엔드 #179)·FR-BL-02(백로그→스프린트 이동, 백엔드 #182)의
D6(프론트 UI)/D7(E2E)를 통합 구현. 양쪽 PR Deviation이 D6/D7과 "백로그 조회 API"를 본 작업으로 이연.

선행 백엔드 완료 상태.
- FR-BL-01 D5(#179): `PATCH /api/v1/issues/{key}/rank` (LexoRank rank 변경, no-bump, on-demand rebalance)
- FR-BL-02 D5(#182): `SprintController` — 스프린트 CRUD + start/complete + 이슈 할당(POST /sprints/{id}/issues)/해제(DELETE /sprints/{id}/issues/{issueKey})

미구현(본 작업 포함).
- 백로그 조회 API — rank 정렬된 스프린트 미할당 이슈 목록 GET (양쪽 Deviation 이연 항목)
- D6 프론트: @dnd-kit 백로그 ↔ 스프린트 드래그앤드롭 보드
- D7 E2E: 드래그 재정렬·스프린트 할당/해제 시나리오

classify override: type=qa→ui (E2E 키워드 오판 선례 교정).

## 도메인 정리

- **BC**: agile-planning (주, 백로그/스프린트 화면) + issue-tracking (포트 rank read 확장만) + shared-kernel (BoardIssueView)
- **신규 엔티티**: 0 (Sprint·sprint_issues·issues.rank·Board 전부 존재). 백로그는 별도 테이블 없는 **조회 전용 view**
- **신규 용어**: 0 (백로그·스프린트·LexoRank 모두 glossary 존재)
- **마이그레이션**: 0 (스키마 전부 존재)
- **핵심 결정** (ADR `2026-06-24-fr-bl-d6-d7-backlog-query-port-rank.md` 신설):
  1. 백로그 조회 API = **agile-planning 소유** (미할당 판정=sprint_issues 기준=agile 데이터. issue-tracking 주도는 역방향 의존이라 기각)
  2. rank 정렬 = `BoardIssueLookupPort.BoardIssueView`에 `rank: String?` 추가(default null, fail-safe) + issue-tracking adapter가 `issues.rank` SELECT. FR-BL-02 ADR "rank=D6 이연(포트 미노출)"의 실현
  3. 정렬 규칙 = `rank ASC NULLS LAST, created_at` (신규 이슈 rank=NULL lazy)
  4. 드래그 동작 = 기존 API 재사용 — 재정렬 `PATCH /issues/{key}/rank`(#179), 할당 `POST /sprints/{id}/issues`(#182), 해제 `DELETE /sprints/{id}/issues/{issueKey}`(#182). 신규 백엔드는 **조회만**
- **기존 결정 충돌**: 없음 (FR-BL-01/02 ADR의 명시적 연장)
- **관련 ADR**: [2026-06-24-fr-bl-d6-d7-backlog-query-port-rank.md](../decisions/2026-06-24-fr-bl-d6-d7-backlog-query-port-rank.md) (신설) · [FR-BL-01 lexorank](../decisions/2026-06-23-fr-bl-01-lexorank-backlog-ordering.md) · [FR-BL-02 sprint-issue](../decisions/2026-06-24-fr-bl-02-sprint-issue-association.md)
- **spec 미결 사항**: 스프린트별 이슈 순서 rank 정렬 포함 여부 · 페이징(1K 가상 스크롤) 방식 · `@dnd-kit/sortable` 추가 여부(board 선례는 core만 사용)
- **프론트 선례**: `apps/web/src/{api/boards.ts, hooks/use-boards.ts, components/board/*, mocks/board-handlers.ts, routes/projects.$projectKey.board.tsx}`. @dnd-kit/core 6.3.1·utilities 3.2.2 설치됨

## 스펙

전체 스펙. [docs/specs/2026-06-24-fr-bl-01-02-d6-d7-backlog-sprint-ui-e2e.md](../specs/2026-06-24-fr-bl-01-02-d6-d7-backlog-sprint-ui-e2e.md)

핵심 5줄 요약.
- 백로그 보드 한 페이지 = 백로그 칸(미할당) + 스프린트별 칸, 모두 rank 순 표시
- 신규 백엔드는 **조회만** — `GET /projects/{key}/backlog`(백로그+스프린트별 이슈, rank 정렬, truncated). 포트에 `BoardIssueView.rank` 확장
- 드래그 5종(S1~S5)은 기존 API 위임 — 재정렬 `PATCH /rank`(이웃 prev/next), 할당/해제 `POST·DELETE /sprints/{id}/issues`. 이동+위치는 2 API 순차
- 라이프사이클 전체 — 스프린트 생성/시작/완료 버튼(#182 API)
- @dnd-kit/core만(sortable 미추가)·가상스크롤 미도입(truncated)·invalidate-only·권한 게이팅+서버 재검증

범위 확정(Maxi). 스프린트 내 재정렬 **포함**, 라이프사이클 UI **전체 포함**.

## Brainstorming Check

✅ 통과 (self-check, gap 0). 완료 FR 후속이라 full brainstorming 대신 스펙 직접 점검 — 잠재 포인트(할당시 rank 부여/null 이웃·드롭 이웃 계산·플리커·COMPLETED read-only)는 모두 스펙 E1~E7/NFR에 반영 또는 plan 구현 디테일로 해소. Maxi 결정 필요 신규 gap 없음.

## Plan

> 헤더 기본 agent=frontend-engineer. 백엔드 task는 메타에 backend-engineer, E2E는 qa-engineer 명시.

### Task 1. BoardIssueView.rank 포트 확장 (shared-kernel)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/shared-kernel/src/main/kotlin/com/bts/shared/board/BoardIssueLookupPort.kt`, `backend/modules/shared-kernel/src/test/kotlin/com/bts/shared/board/BoardIssueViewTest.kt`]
- depends-on: []

**RED**: `BoardIssueView`가 `rank: String?`를 보유하고 미지정 시 default `null`인지 단위 테스트(인라인 fake/미override adapter 안전 — interface-extension-default-method).
**GREEN**: `BoardIssueView`에 `val rank: String? = null` 추가(마지막 필드, default).
**REFACTOR**: KDoc에 rank 의미(LexoRank 정렬 키, null=미부여) + 정렬은 소비측 책임 명시.
**검증**: `./gradlew :backend:shared-kernel:test`

### Task 2. BoardIssueLookupAdapter rank SELECT (issue-tracking)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/adapter/outbound/board/BoardIssueLookupAdapter.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/repository/IssueRepository.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/adapter/outbound/board/BoardIssueLookupAdapterIntegrationTest.kt`]
- depends-on: [1]

**RED**: 통합 테스트 — rank 부여 이슈를 `listVisibleIssuesByProject`로 조회 시 `BoardIssueView.rank`가 채워지고, 미부여 이슈는 null. 기존 board 테스트 회귀 0.
**GREEN**: `IssueRepository`의 board 카드 조회 SQL/`BoardIssueEntry`에 `ISSUES.RANK` 추가, `toBoardIssueView()`에 `rank = ...` 매핑.
**REFACTOR**: 정렬 미적용 유지(adapter는 노출만, 정렬은 agile 소비측). cartesian 무위험 확인.
**검증**: `./gradlew :backend:issue-tracking:test`

### Task 3. 백로그 조회 API — service + controller (agile-planning)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/agile-planning/src/main/kotlin/com/bts/agileplanning/application/BacklogApplicationService.kt`, `backend/modules/agile-planning/src/main/kotlin/com/bts/agileplanning/web/BacklogController.kt`, `backend/modules/agile-planning/src/main/kotlin/com/bts/agileplanning/web/dto/BacklogResponses.kt`, `backend/modules/agile-planning/src/main/kotlin/com/bts/agileplanning/repository/SprintRepository.kt`, `backend/modules/agile-planning/src/test/kotlin/com/bts/agileplanning/application/BacklogApplicationServiceTest.kt`, `backend/modules/agile-planning/src/test/kotlin/com/bts/agileplanning/web/BacklogControllerIntegrationTest.kt`]
- depends-on: [1, 2]

**RED**: ① service 단위 — `listVisibleIssuesByProject` 결과를 미할당(백로그)·스프린트별로 그룹핑 + `rank ASC NULLS LAST, created_at` 정렬 + 스프린트 status 정렬. ② controller 통합 — `GET /api/v1/projects/{key}/backlog` 200(백로그+스프린트별), BROWSE 미충족 403, truncated 전파, 미인증 401. ③ 형제 `@RestControllerAdvice` basePackages가 새 컨트롤러 예외 가로채는지 확인(메모리 fr-bl-02-sprint-backend-done — assignableTypes 한정/직접 핸들러).
**GREEN**: `BacklogApplicationService`(포트 호출 + `SprintRepository`로 프로젝트 스프린트별 issue_key 그룹핑, 미할당=전체−할당), `BacklogController`(BROWSE 권한), `BacklogResponses` DTO. `SprintRepository`에 프로젝트 단위 할당 키 조회 헬퍼 필요 시 추가.
**REFACTOR**: 정렬 헬퍼 추출(rank null-last 비교자), KDoc, BC 격리(shared-kernel 포트만).
**검증**: `./gradlew :backend:agile-planning:test`

### Task 4. 백로그 API 클라이언트 + Zod 스키마 (api/backlog.ts)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/api/backlog.ts`, `apps/web/src/api/backlog.test.ts`]
- depends-on: []

**RED**: `fetchBacklog(projectKey)` Zod parse(백로그+스프린트별+truncated) + mutation api(`rerankIssue`, `assignToSprint`, `unassignFromSprint`, `createSprint`, `startSprint`, `completeSprint`) 계약 테스트. 스키마는 spec API §와 정합(메모리 frontend-zod-backend-dto-contract-gap — invent 금지, spec 기준).
**GREEN**: api 함수(board 선례 `apiGet/apiPost/apiFetch` + local `dataResponseSchema`) + Zod 스키마(`backlogIssueSchema`, `sprintMetaSchema`, `backlogBoardSchema`).
**REFACTOR**: 타입 export, rank `.nullish()`, 매직 경로 상수.
**검증**: `pnpm --filter web test backlog.test`

### Task 5. MSW 핸들러 — stateful (mocks/backlog-handlers.ts)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/mocks/backlog-handlers.ts`, `apps/web/src/mocks/backlog-fixtures.ts`, `apps/web/src/mocks/backlog-handlers.test.ts`, `apps/web/src/mocks/handlers.ts`]
- depends-on: [4]

**RED**: 핸들러 stateful 검증 — 할당/해제/재정렬/스프린트 생성 후 GET backlog 재조회에 반영(메모리 msw-mutation-stateful-refetch, msw-derived-behavior-shared-store-e2e — 브라우저 시드가능 공유 store).
**GREEN**: `GET /projects/:key/backlog` + mutation 핸들러(공유 store에서 파생), `handlers.ts` 등록.
**REFACTOR**: fixture helper, store 모듈로드 자동 시드(메모리 fr-bd-01 — 신규 store 자동 시드).
**검증**: `pnpm --filter web test backlog-handlers.test`

### Task 6. TanStack Query hooks (hooks/use-backlog.ts)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/hooks/use-backlog.ts`, `apps/web/src/hooks/use-backlog.test.tsx`]
- depends-on: [4, 5]

**RED**: `useBacklog(projectKey)` 조회 + 각 mutation(`useRerankIssue`/`useAssignToSprint`/`useUnassignFromSprint`/`useCreateSprint`/`useStartSprint`/`useCompleteSprint`) onSuccess invalidate(invalidate-only, setQueryData 금지 — mutation-setquerydata-partial-response-flicker).
**GREEN**: useQuery/useMutation, `backlogKeys` 팩토리, invalidate.
**REFACTOR**: queryKey 정규화(use-boards 선례).
**검증**: `pnpm --filter web test use-backlog.test`

### Task 7. 카드/칸 컴포넌트 (components/backlog/)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/backlog/BacklogCard.tsx`, `apps/web/src/components/backlog/BacklogColumn.tsx`, `apps/web/src/components/backlog/SprintColumn.tsx`, `apps/web/src/components/backlog/BacklogCard.test.tsx`, `apps/web/src/components/backlog/BacklogColumn.test.tsx`, `apps/web/src/components/backlog/SprintColumn.test.tsx`]
- depends-on: [4]

**RED**: `BacklogCard`(useDraggable, distance 5px로 Link 클릭 보존 — board D-2 선례) 렌더, `BacklogColumn`/`SprintColumn`(useDroppable), COMPLETED 스프린트 칸 droppable 비활성(E2), 스프린트 헤더 status·시작/완료 버튼 슬롯.
**GREEN**: 3 컴포넌트(@dnd-kit/core).
**REFACTOR**: 공통 카드/칸 추출, i18n(콜론 종결 회피 — ko.test 검증).
**검증**: `pnpm --filter web test components/backlog`

### Task 8. BacklogBoard 루트 + 드래그 오케스트레이션 + 라이프사이클

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/backlog/BacklogBoard.tsx`, `apps/web/src/components/backlog/CreateSprintForm.tsx`, `apps/web/src/lib/backlog-drag.ts`, `apps/web/src/components/backlog/BacklogBoard.test.tsx`, `apps/web/src/lib/backlog-drag.test.ts`]
- depends-on: [6, 7]

**RED**: ① `backlog-drag.ts` 순수 함수 — 드롭 위치에서 같은 칸 prev/next 이웃 키 계산 + 시나리오 판정(S1~S5: 재정렬 only / 할당+rank / 해제+rank / 재할당+rank / 스프린트내 재정렬), E1 빈칸=이동만(rank 생략). ② `BacklogBoard` onDragEnd가 시나리오별 hook 호출(이동 API→rank API 순차). ③ `CreateSprintForm` + 시작/완료 버튼 + 권한 게이팅(무권한 비활성, 서버 403 토스트+invalidate 롤백 — E6).
**GREEN**: DndContext + PointerSensor(distance 5) + onDragEnd, 생성 폼, 라이프사이클 버튼.
**REFACTOR**: 시나리오 분기를 `backlog-drag.ts` 순수 함수로 분리(단위 테스트 용이), truncated 배너(F9).
**검증**: `pnpm --filter web test BacklogBoard backlog-drag`

### Task 9. 라우트 + 네비게이션 진입점 + 권한 요약 확인

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/routes/projects.$projectKey.backlog.tsx`, `apps/web/src/router.ts`, `apps/web/src/routes/projects.$projectKey.backlog.test.tsx`]
- depends-on: [8]

**RED**: 라우트(page + adapter 분리, .ts router의 JSX 제약 — TanStack code-based 선례) + 백로그 페이지 데이터 로드 + 보드/프로젝트 네비에서 백로그 링크. 권한 게이팅이 `MyProjectPermission` 요약에 필요한 권한코드(이슈 UPDATE·스프린트 CREATE/UPDATE) 노출 의존(메모리 ui-permission-gating-needs-summary-api-exposure) — 기존 노출분 재사용 확인, 누락 시 리스크 기록.
**GREEN**: route page+adapter, `router.ts` 등록, 네비 링크.
**REFACTOR**: 로딩/에러 상태 i18n.
**검증**: `pnpm --filter web test backlog && pnpm --filter web typecheck`

### Task 10. E2E (qa-engineer)

**메타**.
- agent: `qa-engineer`
- files: [`apps/web/e2e/backlog.spec.ts`, `apps/web/e2e/fixtures/*` (백로그 MSW 시드)]
- depends-on: [9]

**RED→GREEN**: 5 시나리오 — 백로그 재정렬(S1) / 백로그→스프린트(S2) / 스프린트→백로그(S3) / 스프린트 내 재정렬(S5) / 스프린트 생성·시작(S6/S7). 실드래그 PointerSensor(메모리 fr-bd-01 board-ui), MSW 영속 시드는 SPA 링크 이동(reload 금지 — 가짜그린), 분별 시드(메모리 fr-sr-01). MSW opaque 한계 시나리오는 의도적 SKIP + 사유.
**검증**: `pnpm --filter web test:e2e backlog.spec`

## Plan 메타

- task 수: 10 (백엔드 3 · 프론트 6 · E2E 1)
- 예상 wave: ~6 (백엔드 T1→T2→T3 모듈 컴파일 직렬 / 프론트 T4→{5,7}→6→8→9 / E2E T10. 백엔드·프론트 병렬 진행). 메모리 bts-plan-wave-gradle-module-compile — 모듈 경계가 직렬 요인.
- TDD 강제: yes (test 커밋이 feat 커밋보다 먼저)
- 병렬 dispatch: bts-impl이 depends-on + files 교집합으로 wave 계산
- 추가 검증: 백엔드 ktlint/detekt, 프론트 typecheck/lint/vitest, E2E playwright
- 리스크: ① 권한 요약 API에 이슈 UPDATE·스프린트 CREATE/UPDATE 권한코드 노출 부족 시 backend task 추가(T9에서 확인) ② rerank의 null rank 이웃 처리(E3) — T8 이웃 계산이 서버 검증과 정합한지 통합 확인 ③ 스프린트 다수 시 백로그 화면 길이(COMPLETED read-only 표시)

## 리뷰 결과 (← /bts-review-plan 채움)
