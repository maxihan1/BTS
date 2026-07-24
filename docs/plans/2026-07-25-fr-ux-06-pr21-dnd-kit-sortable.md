# FR-UX-06 PR21 — @dnd-kit/sortable 보드 컬럼 내 카드 순서변경

> slug: fr-ux-06-pr21-dnd-kit-sortable
> type: ui
> agent: frontend-engineer
> primary_bc: agile-planning
> 생성: 2026-07-25

## Brief

사용자 원문: FR-UX-06 PR21 — @dnd-kit/sortable 보드 컬럼 내 카드 순서변경

classify 결과: type=ui, agent=frontend-engineer, primary_bc=agile-planning, slug=fr-ux-06-pr21-dnd-kit-sortable

FR-UX-06(BTS UI/UX Jira Cloud 방식 개편) Phase 5(화면)의 다섯 번째 PR. 칸반 보드에서 같은 컬럼 안 카드들의 순서를 드래그로 바꾸는 기능. agile-planning BC는 이미 @dnd-kit·LexoRank 인프라 보유.

## 도메인 정리 (← /bts-domain 채움)

- **BC**: 주 = agile-planning(보드 소유), 종속 = issue-tracking(rank 컬럼·리랭크 API 소유, **재사용만·변경 0**), 프론트 apps/web.
- **grill-with-docs 생략**: 도메인 모델(LexoRank·rank 소유권)이 ADR로 이미 확립, 새 엔티티/용어 0(PR20 선례와 동일 근거).

### 현 상태 실측 (코드 대조)

| 항목 | 현재 상태 | 근거 |
|---|---|---|
| 보드 컬럼 간 이동(상태 전이) | ✅ 구현됨 | `KanbanBoard.tsx` `resolveDropAction`→`useMoveCard`(`POST /boards/{id}/cards/{key}/move`) |
| **보드 컬럼 내 순서변경** | ❌ **미구현 — 같은 컬럼 드롭 = noop(EC1)** | `KanbanBoard.tsx:84` `fromColumnId === toColumnId → noop` |
| 보드 카드 응답 rank 노출 | ❌ **없음** | `BoardResponses.kt` `BoardCardResponse`(issueKey/summary/assigneeId/priority/version/epicKey), rank 필드 부재 |
| 보드 카드 컬럼 내 정렬 기준 | ❌ rank 정렬 아님 | `BoardRepository.kt` 컬럼만 DISPLAY_ORDER 정렬, 카드 정렬 기준 없음 |
| `issues.rank` 컬럼 + 리랭크 API | ✅ **완비(재사용 가능)** | `PATCH /api/v1/issues/{key}/rank`(FR-BL-01), `BacklogRankService.rerank`(이웃 중간값+rebalance), `issues.rank` TEXT |
| 프론트 리랭크 API 클라이언트 | ⚠️ backlog.ts에만 존재 | `api/backlog.ts`(백로그용), `api/boards.ts`엔 rank 함수 없음 |
| `@dnd-kit/sortable` 설치 | ❌ **미설치** | package.json에 `@dnd-kit/core`·`@dnd-kit/utilities`만, sortable 없음 |

### 기존 결정과의 정합

- **LexoRank ADR(2026-06-23 FR-BL-01) 결정2·결정4가 "미래 보드 컬럼 내 정렬(agile-planning)에서도 재사용 가능"을 이미 명시** → PR21이 정확히 그 예견된 케이스. 결정 충돌 없음.
- rank 소유권(issue-tracking) 불변. 보드는 조회 시 rank를 미러 노출만(백로그 `BacklogResponses.kt` 선례와 동일 패턴).

### ADR 후보

- 보드 조회 rank 노출/정렬 + 컬럼 내 리랭크가 기존 리랭크 API 재사용임을 기록 (신규 리랭크 경로 없음). BC 경계: agile-planning 조회 view layer가 issue-tracking rank를 미러(백로그 선례).

### ★ 스코프·의존성·FR 귀속 — Maxi 확정 (2026-07-25)

1. **스코프 = 풀스택 완제품** ✅ — agile-planning 보드 조회에 rank 노출/정렬 추가 + 프론트 `@dnd-kit/sortable` + 기존 리랭크 API(`PATCH /api/v1/issues/{key}/rank`) 재사용. issue-tracking 변경 0(조회만).
2. **FR 귀속 = FR-UX-06 Phase 5** ✅ — FR 총수 **129 불변**. product 문서 D단계 마킹만(신규 FR 없음). 22 PR 체인 PR21에 편입.
3. **`@dnd-kit/sortable` 승인** ✅ — @dnd-kit/core(6.3.1)·utilities 공식 패밀리 확장, core 버전과 호환. SortableContext로 컬럼 내 정렬.

## 스펙 (← /bts-spec Phase A 채움)

전체 스펙. [docs/specs/2026-07-25-fr-ux-06-pr21-dnd-kit-sortable.md](../specs/2026-07-25-fr-ux-06-pr21-dnd-kit-sortable.md)

**스코프 확정(Maxi 2026-07-25) — 완전 Jira 목표를 2 PR로 분할.**
- **PR21(이번)** = ①같은 셀(스윔레인 그룹×컬럼) 내 순서변경(rank) + ②컬럼 간 상태전이(기존 유지). 스윔레인 활성 시에도 셀 내 순서변경 지원, 그룹 경계 넘는 드래그는 noop.
- **PR21b(다음)** = ③담당자 재할당 + ④우선순위/에픽 변경(스윔레인 간 드래그). API 모두 실재(`PATCH /assignee`·`updateIssue priority`·`epic-children`), ASSIGNEE는 UUID 역산 배선 필요.

핵심 3줄.
- 보드 조회(agile-planning)에 rank 노출 + rank ASC NULLS LAST → key ASC 정렬 추가(백로그 선례).
- 프론트 `@dnd-kit/sortable` 도입, 셀 단위 SortableContext, 같은 셀 드롭 시 기존 `rerankIssue`(재사용) 호출.
- issue-tracking 리랭크 API·서비스 변경 0(호출만). 낙관적 업데이트(arrayMove)+409 롤백+toast는 useMoveCard 패턴 재사용.

## Brainstorming Check (← /bts-spec Phase B 채움)

✅ 통과 (코드 실측 sanity check 1회). gap 1건(스윔레인 4종 상호작용)→셀 개념+PR21b 분할로 해소. 거짓 gap 검증: EC2 null 이웃은 서버 rebalance로 처리(백엔드 완비), 필드변경 API 4종 모두 실재(phantom 아님).

## Plan (← /bts-plan 채움)

> 스펙 §완료기준 기반 8 TDD 태스크. issue-tracking 리랭크 API·서비스 변경 0. 마이그레이션 0. FR 129 불변.
> KanbanBoard.tsx를 여러 프론트 태스크가 공유 → files 겹침으로 자동 직렬(PR19 wave1 lint-staged 레이스 교훈 → 직렬 dispatch 권장).

### Task 1. 보드 카드 응답에 rank 노출

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/agile-planning/src/main/kotlin/com/bts/agileplanning/web/dto/BoardResponses.kt`, `backend/modules/agile-planning/src/main/kotlin/com/bts/agileplanning/domain/BoardCardPlacement.kt`, `backend/modules/agile-planning/src/main/kotlin/com/bts/agileplanning/application/BoardApplicationService.kt`, `backend/modules/agile-planning/src/test/kotlin/com/bts/agileplanning/web/BoardControllerTest.kt`, `backend/modules/agile-planning/src/test/kotlin/com/bts/agileplanning/application/BoardApplicationServiceTest.kt`]
- depends-on: []

**RED**: 보드 조회 응답의 각 `BoardCardResponse`에 `rank`(nullable) 필드가 있고, 이슈의 rank 값이 실려 오는지 검증하는 테스트. 현재 필드 부재로 실패.
**GREEN**: `BoardCardResponse`에 `rank: String? = null` 추가. `page.issues`(issue-tracking 조회 결과)에 이미 존재하는 rank를 `BoardCardPlacement.placeCards`가 카드로 전달하도록 매핑. 조회 이슈 뷰에 rank가 없으면 issue 조회 포트/뷰에 rank 포함(읽기 전용).
**REFACTOR**: KDoc에 rank 미러 노출(issue-tracking 소유) 명시.
**검증**: `./gradlew :backend:modules:agile-planning:test --tests '*BoardControllerTest' --tests '*BoardApplicationServiceTest'`

### Task 2. 보드 카드 컬럼 내 rank 정렬

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/agile-planning/src/main/kotlin/com/bts/agileplanning/domain/BoardCardPlacement.kt`, `backend/modules/agile-planning/src/test/kotlin/com/bts/agileplanning/domain/BoardCardPlacementTest.kt`]
- depends-on: [1]

**RED**: 한 컬럼에 배치된 카드가 **rank ASC NULLS LAST → issueKey ASC**로 정렬되는지 검증(BacklogApplicationService 비교자 선례). 뒤섞인 입력 → 기대 순서 실패.
**GREEN**: `placeCards`가 각 컬럼 카드를 rank ASC NULLS LAST → key ASC로 정렬. null rank는 뒤로, 동값/양쪽 null은 key 사전순.
**REFACTOR**: 비교자를 BacklogApplicationService와 동일 규칙으로 공유/주석 참조.
**검증**: `./gradlew :backend:modules:agile-planning:test --tests '*BoardCardPlacementTest'`

### Task 3. @dnd-kit/sortable 설치 + boards.ts rank 스키마 + MSW fixture

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/package.json`, `apps/web/src/api/boards.ts`, `apps/web/src/api/boards.test.ts`, `apps/web/src/mocks/board-fixtures.ts`, `apps/web/src/mocks/board-handlers.ts`, `apps/web/src/mocks/board-handlers.test.ts`]
- depends-on: []

**RED**: boards.ts BoardCard Zod 스키마 파싱 테스트가 `rank`(nullish→null) 필드를 기대 → 현재 스키마에 없어 실패. MSW board fixture 카드에 rank 미포함.
**GREEN**: `@dnd-kit/sortable`(core 6.3.1 호환 버전) 설치. BoardCard 스키마에 `rank: z.string().nullish().transform(v => v ?? null)` 추가(backlog.ts 선례). board-fixtures/handlers 카드에 rank 부여(정렬 검증 가능하도록).
**REFACTOR**: 스키마 주석에 rank 미러(issue-tracking 소유) 명시.
**검증**: `pnpm test -- boards.test board-handlers.test` (바이너리 직접 호출로 필터 — [[e2e-playwright-filter-arg-drop]])

### Task 4. resolveDropAction 셀 판정 확장 (순수 헬퍼)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/board/board-drop.ts`, `apps/web/src/components/board/board-drop.test.ts`, `apps/web/src/components/board/KanbanBoard.tsx`]
- depends-on: []

**RED**: `resolveDropAction`이 (a)다른 컬럼=move/needs-resolution, (b)같은 셀 내=rerank(이웃 issueKey 계산), (c)같은 컬럼·다른 스윔레인 그룹=noop, (d)제자리=noop 4분기를 판정하는 유닛 테스트. rerank 분기·그룹경계 noop 부재로 실패.
**GREEN**: `resolveDropAction`을 KanbanBoard.tsx에서 `board-drop.ts`로 추출하면서 셀(컬럼+스윔레인 그룹 key) 판정 + rerank 분기(previous/next 이웃 issueKey, 맨앞/맨뒤 경계) 추가. DropAction union에 `reorder` 타입 추가. KanbanBoard.tsx는 import로 전환.
**REFACTOR**: 셀 식별·이웃 계산 헬퍼 분리 + KDoc.
**검증**: `pnpm test -- board-drop.test`

### Task 5. useReorderCard 훅 — 낙관적 순서변경 + 롤백

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/hooks/use-reorder-card.ts`, `apps/web/src/hooks/use-reorder-card.test.tsx`]
- depends-on: [3]

**RED**: 셀 내 카드 배열을 arrayMove로 재정렬하는 순수 헬퍼(`reorderCardInCell`) + `rerankIssue` 호출 + 낙관적 업데이트 + 409/에러 롤백 + invalidate 테스트. 훅 부재로 실패.
**GREEN**: `useReorderCard(boardId, filter)` 신설. `rerankIssue`(api/backlog.ts 재사용) 호출, onMutate 낙관적 arrayMove(filter-aware boardKeys queryKey, useMoveCard 패턴), onError 롤백+toast, onSettled invalidate.
**REFACTOR**: 순수 헬퍼(reorderCardInCell)를 export해 테스트 격리.
**검증**: `pnpm test -- use-reorder-card.test`

### Task 6. BoardCard useSortable 전환 + 셀 단위 SortableContext

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/board/BoardCard.tsx`, `apps/web/src/components/board/BoardColumn.tsx`, `apps/web/src/components/board/BoardCard.test.tsx`, `apps/web/src/components/board/BoardColumn.test.tsx`]
- depends-on: [3, 4]

**RED**: BoardCard가 `useSortable`(id=issueKey, data에 fromColumnId+swimlaneGroupKey)로 렌더되고, BoardColumn이 각 셀(스윔레인 그룹 또는 컬럼 전체)을 `SortableContext`(items=셀 내 issueKey 배열)로 감싸는지 검증. 현재 useDraggable/컨텍스트 없음으로 실패.
**GREEN**: BoardCard useDraggable→useSortable(기존 fromColumnId data 보존 + swimlaneGroupKey 추가). BoardColumn의 각 SwimlaneSection·NONE 목록을 SortableContext로 래핑(verticalListSortingStrategy). Link 클릭 보존(PointerSensor distance:5).
**REFACTOR**: 셀 key 계산 헬퍼 공유(board-drop.ts와 일관).
**검증**: `pnpm test -- BoardCard.test BoardColumn.test`

### Task 7. KanbanBoard onDragEnd 통합 (rerank/move 분기, 기존 보존)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/board/KanbanBoard.tsx`, `apps/web/src/components/board/KanbanBoard.test.tsx`]
- depends-on: [4, 5, 6]

**RED**: onDragEnd가 resolveDropAction 결과로 reorder→useReorderCard, move/needs-resolution→기존 useMoveCard/resolution 모달로 분기하는 테스트. 기존 컬럼 간 이동·DONE resolution 무회귀 검증. reorder 분기 부재로 실패.
**GREEN**: KanbanBoard onDragEnd에 reorder 분기 추가(useReorderCard.mutate). 기존 move/needs-resolution 경로 100% 보존. DragOverlay·센서 유지.
**REFACTOR**: 분기 핸들러 함수 분리.
**검증**: `pnpm test -- KanbanBoard.test`

### Task 8. E2E — 셀 내 순서변경 + 무회귀

**메타**.
- agent: `qa-engineer`
- files: [`apps/web/e2e/board-reorder.spec.ts`]
- depends-on: [2, 7]

**RED**: (NONE)같은 컬럼 순서변경 happy path + 새로고침 후 순서 유지 + 컬럼 간 이동(상태전이) 무회귀 + 스윔레인 활성 셀 내 순서변경 + 그룹 경계 넘는 드래그 noop E2E. 기능 부재로 실패.
**GREEN**: 기능 통합 후 통과. MSW rank stateful 반영. aria/getByRole 계약 준수([[frontend-nav-aria-label-e2e-contract]]·[[playwright-getbyrole-exact-strict-mode]]).
**REFACTOR**: 드래그 헬퍼 공유.
**검증**: playwright 바이너리 직접 호출(파일 필터 — [[e2e-playwright-filter-arg-drop]]). **CI에 e2e 잡 없음 → 로컬 필수**([[frontend-ci-10min-timeout-nonrequired]]).

## Plan 메타

- task 수: 8
- 예상 wave: 백엔드(T1→T2) ∥ 프론트 계약(T3·T4 독립) → T5(dep 3)·T6(dep 3,4) → T7(dep 4,5,6) → T8(dep 2,7). KanbanBoard.tsx 공유(T4·T7)·BoardColumn(T6) 겹침 → **직렬 dispatch 권장**(PR19/PR20 선례).
- TDD 강제: yes (test 커밋 선행 자동 검증)
- 추가 검증: typecheck·eslint·vitest 전수·playwright(qa)·백엔드 :agile-planning:test·조립 부팅 불요(읽기 전용 rank 노출, cross-BC @Component 신설 없음)·verify-master-plan 129/129

## 리뷰 결과 (← /bts-review-plan 채움)
