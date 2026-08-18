# FR-BD-01 D6/D7 — 칸반 보드 프론트엔드 UI + E2E

> slug: fr-bd-01-d6-d7-ui-dnd-kit-e2e
> type: ui
> agent: frontend-engineer
> BC: agile-planning
> 생성: 2026-06-21

## Brief

FR-BD-01 D6(프론트 UI)/D7(E2E)를 구현한다. 백엔드는 #165(보드 CRUD/조회/이동
API)·#168(필터 API)로 완료. 이번 작업은 그 API를 호출해 보여줄 **칸반 보드 화면**
신규 구축.

- **D6**. @dnd-kit 기반 컬럼/카드 드래그앤드롭 보드 페이지. 카드 이동 = 대상 컬럼
  state_key로 전환 위임(POST .../move). resolution 필요 전환(E4)·전환 불가(E4)·
  버전 충돌(E5) 처리.
- **D7**. Playwright E2E + NFR(보드 200건 렌더 p95 < 1.5s) 검증.
- **범위 외**. FR-BD-02 필터 칩 UI(보드 안정화 후 별도 결정 — Maxi 2026-06-21).
- **신규 의존성**. @dnd-kit (product §2.1 D6 명시 선택). DEVELOPMENT.md §외부 의존성
  — spec 단계 Maxi 확인.

원문(classify): type=qa로 오판정(E2E 키워드) → ui/frontend-engineer 교정.

## 도메인 정리

- **BC**: agile-planning (보드 소유). 카드 이동은 cross-BC 전환 위임(issue-tracking)을
  이미 백엔드(#165)가 처리 — 프론트는 백엔드 API 호출만.
- **영향 엔티티**: 신규 0. Board / BoardColumn / Card(=이슈 뷰) 모두 백엔드에 존재.
  이번 작업은 **프레젠테이션 레이어**(D6/D7)만 — 도메인 모델 무변경.
- **새 용어**: 없음. board/칸반/컬럼/카드는 SDD §13.1·glossary 기존 용어. LexoRank는
  glossary 등록됨(이번 범위 밖).
- **기존 결정 충돌**: 없음. FR-BD-01 ADR
  (`2026-06-20-fr-bd-01-agile-planning-bootstrap`)의 결정을 그대로 시각화.

### ADR가 프론트에 강제하는 도메인 제약 (구현 시 필수 준수)

- **결정 2 — 컬럼 = 상태 매핑**. 카드는 `currentStateKey`가 매핑된 컬럼에 배치. 어떤
  컬럼에도 매핑 안 되는 상태의 이슈는 보드에서 제외(spec E2 = 백엔드가 이미 제외).
- **결정 3 — 카드 드래그 = 컬럼 간 이동 = 워크플로우 전환**. 카드를 A→B 컬럼으로
  드래그하면 B 컬럼의 `stateKey`로 **전환 위임**(`POST /api/v1/boards/{id}/cards/{issueKey}/move`).
  **직접 상태 UPDATE 금지**(불변식 우회 — patch-merge-domain-bypass 반례). 프론트는
  move API만 호출, 응답으로 카드 갱신.
- **컬럼 내 재정렬(LexoRank) 범위 제외**(ADR 결정 3 / FR-BL-01). → @dnd-kit은 **컬럼 간
  이동에만** 사용. 같은 컬럼 내 카드 순서 변경 드롭은 no-op(서버 정렬 priority ASC 유지).
- **전환 결과 분기**(백엔드 spec E3~E5): 같은 컬럼=no-op(200), 전환 불가=409,
  resolution 필요=422, 버전 충돌=409. 프론트가 각각 처리(드래그 원복 + 모달/토스트).

- 관련 ADR: [docs/decisions/2026-06-20-fr-bd-01-agile-planning-bootstrap.md](../decisions/2026-06-20-fr-bd-01-agile-planning-bootstrap.md) (기존, 무변경)

## 스펙

전체 스펙. [docs/specs/2026-06-21-fr-bd-01-d6-d7-ui-dnd-kit-e2e.md](../specs/2026-06-21-fr-bd-01-d6-d7-ui-dnd-kit-e2e.md)

핵심 (Maxi 확정 2026-06-21).
- @dnd-kit/core@6.3.1 + @dnd-kit/utilities@3.2.2 도입(버전 고정, 컬럼 간 이동 전용·LexoRank 범위 외).
- 범위 = 보드 목록 + 생성 + 칸반 뷰 전체. 라우트 `/projects/$projectKey/board?board=<id>`.
- 카드 드래그=컬럼 간 이동=move 위임(낙관적+롤백). DONE 컬럼 드롭 → resolution 모달 사전 요구
  (category 사전 감지, 422 사후 의존 제거 — 백엔드 errorCode 일반화 한계 우회).
- boards.ts Zod 1:1 미러. 담당자 best-effort(fetchUsers Map + 이니셜 fallback, 1,000명 한계 명시).
- 에러: 409 AGILE_CONFLICT(전환불가+버전충돌 동일)·422 AGILE_UNPROCESSABLE → 토스트+원위치(+409 refetch).

## Brainstorming Check

✅ 통과 (1회 iteration, 직접 adversarial 갭 스캔). G1 담당자 이름 규모 한계 → best-effort+백엔드 후속 명시.
G2 보드 선택 → URL `?board=`. G3 빈 resolutions → 안내+비활성. 422 사전감지 우회·단일카드 패치 플리커 회피
·CSRF impl 확인 확정.

## Plan

> 전략. 아래→위 빌드(계약→훅→컴포넌트→페이지→MSW→E2E). 각 task TDD red→green→refactor.
> 검증은 worktree에서 `pnpm`(node_modules는 Task 1이 설치). 컴포넌트/훅 테스트는 api/훅을 vi.mock으로
> 격리(MSW는 dev+E2E용). Zod는 백엔드 DTO 1:1(spec API §) — drift 차단.

### Task 1. @dnd-kit 의존성 추가 + worktree 셋업

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/package.json`, `apps/web/pnpm-lock.yaml`]
- depends-on: []

**작업**(setup — TDD 아님, 검증 기준 명시).
- `apps/web/package.json` dependencies에 `@dnd-kit/core@6.3.1`, `@dnd-kit/utilities@3.2.2` 추가(캐럿 금지,
  정확 버전 고정 — 메모리 신규 의존성 버전 고정).
- worktree에서 `pnpm install` (worktree node_modules 미존재 → 전체 설치). **주의**: 부분설치 깨지면
  메모리 worktree-node-modules-partial-install(main서 dist cp 복구) 적용.
- **검증**: `pnpm --filter web typecheck` 베이스라인 그린 + `import { DndContext } from '@dnd-kit/core'`
  타입 해석. (한 줄 임시 import로 확인 후 제거.)

### Task 2. boards.ts API 클라이언트 + Zod 스키마 (1:1 미러)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/api/boards.ts`, `apps/web/src/api/boards.test.ts`]
- depends-on: []

**RED** (`boards.test.ts`).
- `boardDetailSchema` 파싱: spec API §의 GET /boards/{id} 응답 픽스처(columns→cards 중첩, category enum,
  assigneeId uuid|null, truncated, unplacedCount) 통과. category 비허용값/누락 필드 reject.
- `boardSummarySchema`/`boardCreatedSchema`/`moveCardResultSchema` 각 1:1 파싱.
- fetch 함수(`fetchBoards`/`fetchBoard`/`createBoard`/`moveCard`)는 `vi.spyOn(global,'fetch')` mock으로
  경로·메서드·body·`{data:}` 언래핑 단언.
- 예상 실패: `boards.ts` 미존재.

**GREEN** (`boards.ts`).
- 첫 줄 Korean 헤더 주석. Zod 스키마(UUID Zod v4 형식, `category: z.enum(['TODO','IN_PROGRESS','DONE'])`).
- `apiGet`/`apiPost`(client.ts) 사용. `moveCard`는 POST body `{toColumnId, expectedVersion, resolutionId?}`.
  타입은 `z.infer`로 export(인라인 mock drift 차단).

**REFACTOR**. 스키마/타입 정리, `dataResponseSchema` 로컬 헬퍼(resolutions.ts 선례).

**검증**: `pnpm --filter web test boards` + `pnpm --filter web typecheck`.

### Task 3. 보드 조회/생성 훅 (useBoards / useBoard / useCreateBoard)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/hooks/use-boards.ts`, `apps/web/src/hooks/use-boards.test.tsx`]
- depends-on: [2]

**RED**.
- QueryClient wrapper + `vi.mock('@/api/boards')`. `useBoards(projectKey)`/`useBoard(boardId)`가 데이터 반환.
- `useCreateBoard(projectKey)` mutation 성공 시 boards 목록 invalidate(refetch). boardId 미지정 시 useBoard
  비활성(enabled=false) 단언.

**GREEN**. `useQuery`/`useMutation`. queryKey 상수(`['boards',projectKey]`, `['board',boardId]`).

**REFACTOR**. queryKey 팩토리 함수 추출.

**검증**: `pnpm --filter web test use-boards`.

### Task 4. 카드 이동 훅 useMoveCard (낙관적 이동 + 롤백 + 409 refetch)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/hooks/use-move-card.ts`, `apps/web/src/hooks/use-move-card.test.tsx`]
- depends-on: [2]

**RED**.
- `onMutate`: `['board',boardId]` 캐시 스냅샷 보관 + 카드를 현재 컬럼→대상 컬럼으로 **단일 카드 이동**
  (전체 setQueryData 교체 아님 — 플리커 회피).
- `onSuccess`: 응답 `version`/`currentStateKey`로 이동한 카드 패치.
- `onError`: 스냅샷 복원 + `['board',boardId]` invalidate(409 충돌 시 서버 진실 회복).
- `resolutionId` 인자가 moveCard로 전달됨.
- 테스트: 낙관적 이동 즉시 반영 / 성공 version 갱신 / 409 throw 시 원위치+invalidate 호출.

**GREEN**. `useMutation` + onMutate/onError/onSuccess/onSettled. cancelQueries로 드래그 중 refetch 억제(EC8).

**REFACTOR**. 캐시 이동 헬퍼(컬럼 간 카드 이동) 순수 함수 추출 + 단위 테스트.

**검증**: `pnpm --filter web test use-move-card`.

### Task 5. BoardCard + BoardColumn 컴포넌트 (draggable/droppable + 담당자)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/board/BoardCard.tsx`, `apps/web/src/components/board/BoardCard.test.tsx`,
  `apps/web/src/components/board/BoardColumn.tsx`, `apps/web/src/components/board/BoardColumn.test.tsx`]
- depends-on: [1, 2]

**RED**.
- `BoardCard`: issueKey + summary 렌더, 담당자 displayName/미배정/이니셜 fallback(props로 name 주입 —
  해석은 페이지가 Map 제공), `/issues/$key` Link. `useDraggable`(id=issueKey, data={fromColumnId}).
- `BoardColumn`: 헤더(name·category·카드수), `useDroppable`(id=columnId, data={category}), 카드 목록 렌더,
  빈 컬럼도 드롭 영역(EC4).

**GREEN**. 각 컴포넌트 + 첫 줄 Korean 헤더. @dnd-kit `useDraggable`/`useDroppable` + `@dnd-kit/utilities` CSS
transform. DESIGN.md 토큰(Tailwind) 사용.

**REFACTOR**. 카드/컬럼 `memo`(NFR-1 리렌더 억제), aria 라벨.

**검증**: `pnpm --filter web test BoardCard BoardColumn`.

### Task 6. KanbanBoard + DndContext + 드래그 이동 wiring + ResolutionPickerModal

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/board/KanbanBoard.tsx`, `apps/web/src/components/board/KanbanBoard.test.tsx`,
  `apps/web/src/components/board/ResolutionPickerModal.tsx`, `apps/web/src/components/board/ResolutionPickerModal.test.tsx`]
- depends-on: [4, 5]

**RED**.
- `KanbanBoard`: `DndContext`(PointerSensor+KeyboardSensor) + `DragOverlay`. `onDragEnd` 로직:
  - over=null 또는 대상 컬럼==출발 컬럼 → **no-op**(move 미호출, EC1).
  - 대상 컬럼 `category==='DONE'` → `ResolutionPickerModal` 오픈(이동 보류). 확인 시 `useMoveCard`에
    resolutionId 포함 호출. 취소 시 미호출(EC2).
  - 그 외 → `useMoveCard({toColumnId, expectedVersion=카드.version})` 즉시 호출.
  - 컬럼은 displayOrder asc 배치.
- `ResolutionPickerModal`: `useResolutions()`(기존 훅) 목록, 미선택 시 확인 비활성(BulkTransitionDialog 선례),
  빈 목록 → "설정된 해결 방안이 없습니다"(G3). radix Dialog 래퍼(직접 import — 메모리 shadcn Dialog 부재).
- 테스트: cross-column→move 호출 / same-column→no-op / DONE→모달→resolutionId 포함 호출 / 취소→미호출.

**GREEN**. DndContext wiring + 모달. onDragEnd에서 over.data.category 판정.

**REFACTOR**. onDragEnd 분기 헬퍼 추출(테스트 가능). collisionDetection 설정.

**검증**: `pnpm --filter web test KanbanBoard ResolutionPickerModal`.

### Task 7. BoardPage + RouteAdapter + 라우트 등록 + 생성 폼 + 보드 선택 + 경고/상태

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/routes/projects.$projectKey.board.tsx`, `apps/web/src/router.ts`,
  `apps/web/src/components/board/CreateBoardForm.tsx`, `apps/web/src/components/board/CreateBoardForm.test.tsx`,
  `apps/web/src/routes/__tests__/projects.board.test.tsx`]
- depends-on: [3, 6]

**RED**.
- `BoardRouteAdapter`(useParams projectKey + useSearch board) → `BoardPage({projectKey, boardId})`.
- 보드 1+: KanbanBoard 렌더. 보드 0: CreateBoardForm. 다중: 선택 드롭다운 → `?board=<id>` navigate.
- 403(AGILE_ACCESS_DENIED) → 접근 불가 안내(members S6 선례). truncated/unplacedCount>0 경고 배너.
- `CreateBoardForm`: 이름 입력→useCreateBoard. 422(워크플로우 미할당) → 안내 메시지.
- router.ts에 `/projects/$projectKey/board` 라우트 등록(validateSearch `{board?:string}`,
  RouteAdapter/Page 분리, requireAuthAndPasswordChanged 가드). 라우트 수 주석 26→27 갱신.

**GREEN**. 페이지 + 폼 + 라우트 등록.

**REFACTOR**. 상태 분기 정리, 빈/로딩/에러 컴포넌트.

**검증**: `pnpm --filter web test projects.board CreateBoardForm` + `pnpm --filter web typecheck`.

### Task 8. MSW 보드 핸들러 + 픽스처 (dev + E2E, stateful)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/mocks/board-handlers.ts`, `apps/web/src/mocks/board-fixtures.ts`,
  `apps/web/src/mocks/handlers.ts`]
- depends-on: [2]

**RED/GREEN** (MSW는 통합/E2E 검증이라 핸들러+stateful 동작 단언).
- GET 목록/단건, POST 생성, POST move 핸들러. **stateful 공유 store**: move가 카드를 컬럼 간 이동시켜
  이후 refetch에 반영(메모리 msw-mutation-stateful-refetch / msw-derived-behavior-shared-store-e2e).
- 브라우저에서 시드 가능한 store(E2E addInitScript). 충돌 시나리오 토글(409) 지원.
- `handlers.ts`에 `boardHandlers` spread 등록. 픽스처 타입은 `z.infer`(api/boards.ts)로 drift 차단.

**검증**: `pnpm --filter web test board-handlers` (있으면) + dev 수동 렌더 확인.

### Task 9. (D7) Playwright E2E + NFR + 기존 E2E 회귀

**메타**.
- agent: `qa-engineer`
- files: [`apps/web/e2e/board-kanban.spec.ts`, (필요 시) `apps/web/e2e/fixtures/*`]
- depends-on: [7, 8]

**작업**.
- 시나리오: 보드 생성 → 조회(컬럼/카드) → 카드 이동(일반 전환, 컬럼 변경 확인) → DONE 이동(resolution
  모달 선택) → 409 충돌(원위치+토스트). MSW stateful store 시드(SPA 내부 이동, reload 금지 — store 리셋
  가짜그린 회피).
- NFR-1: 200건 보드 렌더 시간 측정(trace) — 임계 1.5s 참고 기록.
- **기존 E2E 회귀**: 새 화면이 전역 셀렉터 안 깨는지 `pnpm --filter web test:e2e` 영향 범위 확인
  (ui-pr-defer-e2e-regression-latent). 셀렉터는 컨테이너 한정/getByRole exact.

**검증**: `pnpm --filter web exec playwright test board-kanban` + 기존 스펙 회귀.

## Plan 메타

- task 수: 9 (Task 1~8 frontend-engineer, Task 9 qa-engineer E2E)
- wave 예상: 약 5 — W1={T1, T2}, W2={T3, T4, T5, T8}(T5만 deps[1,2], 나머지 deps[2], 파일 겹침 0),
  W3={T6}(deps[4,5]), W4={T7}(deps[3,6], router.ts 단독), W5={T9}(deps[7,8]).
- 모듈 컴파일 직렬화 요인: 없음(프론트 단일 패키지, 파일 단위 격리). router.ts는 T7 단독 수정.
- TDD 강제: yes (T1 setup 제외, T2~T7 red→green→refactor, T8 stateful 동작 단언, T9 E2E).
- 추가 검증: `pnpm --filter web typecheck`(tsc, vitest와 별도 — 메모리 vitest≠tsc), eslint(no-console),
  vitest, playwright(T9). 신규 의존성 @dnd-kit 2종(버전 고정).
- 신규 백엔드/마이그레이션/스키마: 0.
- 잠재 함정(impl 인계): worktree node_modules 설치(T1)·CSRF per-BC 확인(move/create POST)·
  MSW stateful store E2E 시드·radix Dialog 직접 import·담당자 best-effort fallback.

## 리뷰 결과

### plan-design-review (2026-06-21, 집중 디자인+엔지니어링 렌즈)

> 칸반은 표준 레이아웃(시각 신규성 낮음)이라 mockup 생성 대신 인터랙션 상태 완성도에 집중.
> DESIGN.md(shadcn/Radix + Tailwind) 토큰 기준. **BLOCKER: 없음.** 디자인 완성도 갭은 impl 가이드로 인계.

**디자인 완성도 갭 (impl 반영 — "나중에 폴리시" 금지, 작성 시점 완제품).**
- **D-1 빈/로딩/에러 상태 구체화 (FR-9 보강)**. "빈 보드"는 단순 안내가 아니라 **온기 + 주 액션**:
  보드 0개 → 일러스트/문구 + CreateBoardForm 강조(주 CTA). 빈 컬럼 → 흐린 "카드 없음" + 드롭 영역 유지.
  로딩 → 컬럼/카드 스켈레톤(레이아웃 시프트 0). → Task 5/7 RED에 빈/로딩 케이스 단언 추가.
- **D-2 드래그 affordance + 드롭 피드백 (Task 5/6 보강)**. (1) 카드 cursor grab→grabbing. (2) 드래그 중
  대상 컬럼 **하이라이트(ring/bg)** — 어디 떨어질지 보여야 함(Krug "생각하게 하지 마라"). (3) `DragOverlay`로
  드래그 카드 미리보기. (4) 키보드 드래그 시 라이브 안내(aria-live). → Task 6 GREEN에 명시.
- **D-3 다중 컬럼 반응형 (신규, 미명시 갭)**. 컬럼 5+개일 때 **가로 스크롤 컨테이너**(min-w 컬럼, wrap 금지) +
  **sticky 컬럼 헤더**. 모바일 터치 스크롤 vs 드래그 충돌 주의(@dnd-kit TouchSensor delay). → Task 6에 추가.
- **D-4 카드 정보 위계 (Task 5 보강)**. summary=주(truncate 2줄), issueKey=보조(muted 소형), 담당자=아바타
  우하단. 제네릭 카드 그리드 슬롭 회피 — DESIGN.md 카드 토큰 사용. 카드 클릭=상세 이동, 드래그=이동(제스처 구분).
- **D-5 충돌 회복 UX (Task 4/6 보강)**. 409 시 카드 스냅백 + 토스트(왜 되돌아갔는지 — "다른 사용자가 먼저
  변경했습니다") + refetch. 스냅백이 갑작스럽지 않게 transition. resolution 모달은 BulkTransitionDialog 일관.

**엔지니어링 렌즈 (확인된 안전성).**
- ✅ 낙관적 이동: `['board',boardId]` 캐시에서 source→target 컬럼 카드 이동(immutable 순수 helper, Task 4).
  단일 카드 패치(전체 교체 아님) → 플리커 회피. expectedVersion=직전 성공 응답 version 반영.
- ✅ 드래그 id 충돌 없음: 카드 draggable=issueKey(고유), 컬럼 droppable=columnId(UUID).
- ✅ DONE resolution 사전 감지(category) — 백엔드 errorCode 일반화(409/422) 우회. BulkTransitionDialog 정석.
- ✅ CSRF per-BC 확인(Task 7/8 move/create POST) · worktree node_modules(Task 1) · MSW stateful E2E(Task 8) ·
  기존 E2E 회귀(Task 9) 모두 plan에 명시.
- ⚠️ **CONCERN-1 (impl)**. @dnd-kit + React 19 StrictMode 이중 마운트 — 센서/DndContext가 effect 의존이면
  cleanup 안전 확인(메모리 fr-nt-02 StrictMode useRef 가드 동형). 저위험.
- ⚠️ **CONCERN-2 (verify)**. 낙관적 이동 중 `useBoard` refetch가 캐시 덮어쓰면 카드 튐 → Task 4 `cancelQueries`
  (EC8) 필수. RED에 "드래그 중 refetch 억제" 단언.

**판정**: 진행 가(BLOCKER 0). 디자인 갭 D-1~D-5는 impl 가이드 인계, CONCERN-1/2는 verify.
