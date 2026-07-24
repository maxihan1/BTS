# FR-UX-06 PR21 — 보드 카드 순서변경 (@dnd-kit/sortable) — 스펙

> slug: fr-ux-06-pr21-dnd-kit-sortable
> FR: FR-UX-06 (Phase 5 PR21) · FR 총수 129 불변
> BC: agile-planning(보드 조회 view layer) + apps/web · issue-tracking rank API 재사용(변경 0)
> ADR: docs/decisions/2026-07-25-fr-ux-06-pr21-board-in-column-rank.md
> 생성: 2026-07-25

## 배경 / 현 상태 / 스코프 (Maxi 확정 2026-07-25)

칸반 보드는 카드를 다른 컬럼(=다른 워크플로우 상태)으로 옮기는 상태 전이만 지원한다. 같은 컬럼 내 드롭은 `resolveDropAction`에서 noop(EC1, `KanbanBoard.tsx:84`)으로 무시된다.

**최종 목표 = 완전 Jira 스윔레인 보드 드래그(4종 상호작용). 2 PR로 분할.**

| # | 상호작용 | PR21(이번) | PR21b(다음) |
|---|---|---|---|
| ① | 같은 셀(스윔레인 그룹 × 컬럼) 내 위아래 = **순서변경(rank)** | ✅ | |
| ② | 다른 컬럼으로 = **상태 전이** | ✅(기존 유지) | |
| ③ | 담당자 스윔레인 다른 행 = **담당자 재할당** | | ✅(`PATCH /assignee`, UUID 역산 배선) |
| ④ | 우선순위/에픽 스윔레인 다른 행 = **필드 변경** | | ✅(priority/epic-children) |

**이 PR(PR21) 스코프 = ①순서변경 + ②상태전이(기존).** 스윔레인 활성(ASSIGNEE/PRIORITY/EPIC) 시에도 **같은 스윔레인 그룹 안(=셀) 순서변경**은 지원한다. 다른 스윔레인 그룹으로의 드래그(③④ 필드변경)는 **PR21b로 이연**하며, 이번엔 noop 처리한다.

재사용 인프라(실측 확인).
- `issues.rank`(TEXT, LexoRank) + `PATCH /api/v1/issues/{key}/rank` + `BacklogRankService.rerank`(이웃 중간값+**null 이웃 시 rebalance 트리거**+OCC) — issue-tracking, **완비**. lazy 미부여(rank=null) 영역도 서버가 처리(`resolveNeighborRanks`).
- 프론트 `rerankIssue(issueKey, {previousIssueKey?, nextIssueKey?})` — `api/backlog.ts`에 **존재**(400=이웃 누락, 409=OCC). 보드에서 재사용.
- 갭: 보드 조회 `BoardCardResponse`에 rank 없음, 보드 카드가 rank로 정렬되지 않음.
- 스윔레인 4종: NONE/ASSIGNEE/PRIORITY/EPIC. 그룹핑 헬퍼 `groupCardsBySwimlane`. **셀 = 스윔레인 그룹(행) × 컬럼(상태) 교차**. NONE이면 셀 = 컬럼 전체.

## 사용자 시나리오 (Given-When-Then)

- **S1 (같은 컬럼 위→아래 이동)**. Given 보드의 "진행 중" 컬럼에 카드 A,B,C가 이 순서로 있을 때, When 사용자가 A를 C 아래로 드래그하면, Then 순서가 B,C,A로 즉시 바뀌고(낙관적) 서버에 저장되며 새로고침 후에도 유지된다.
- **S2 (같은 컬럼 맨 앞으로 이동)**. Given 카드 A,B,C, When C를 A 위(맨 앞)로 드래그하면, Then 순서가 C,A,B가 되고 `rerankIssue(C, {nextIssueKey: A})`(previous 없음=맨 앞)가 호출된다.
- **S3 (컬럼 간 이동은 기존 동작 보존)**. Given 카드를 다른 컬럼으로 드래그하면, Then **기존 상태 전이(move)** 동작이 그대로 실행된다(DONE 컬럼이면 resolution 모달). 순서변경 로직은 개입하지 않는다.
- **S4 (제자리 드롭)**. Given 카드를 원래 위치에 그대로 놓으면, Then noop(API 호출 없음).
- **S5 (OCC 충돌)**. Given 다른 사용자가 먼저 순서를 바꿔 버전이 어긋나면, When 리랭크가 409를 받으면, Then 낙관적 변경을 롤백하고 목록을 invalidate(재조회)하며 "다른 변경과 충돌" toast를 띄운다(기존 useMoveCard 패턴 재사용).
- **S6 (키보드 접근성)**. Given 키보드 사용자가 카드에 포커스 후 Space로 집고 방향키로 이동하면, Then 컬럼 내 순서가 바뀐다(@dnd-kit KeyboardSensor + sortable).
- **S7 (스윔레인 셀 내 순서변경)**. Given 스윔레인=ASSIGNEE, "진행 중" 컬럼의 "김앨리스" 그룹에 카드 X,Y가 있을 때, When X를 Y 아래로 드래그하면, Then 그 셀 안에서 순서가 Y,X로 바뀌고 이웃(셀 내 카드)만으로 `rerankIssue`를 호출한다.
- **S8 (스윔레인 그룹 넘는 드래그 = 이번 PR noop)**. Given 스윔레인 활성 시 카드를 **다른 스윔레인 그룹**(같은 컬럼)으로 드래그하면, Then 이번 PR에서는 noop(필드변경 없음). PR21b에서 담당자/우선순위/에픽 재할당으로 구현 예정.

## 기능 요구사항 (FR)

- **FR1**. 보드 조회 응답 `BoardCardResponse`에 `rank: String?`(nullable, 미부여 시 null)를 추가한다.
- **FR2**. 보드 카드 조회는 각 컬럼 내 카드를 **rank ASC NULLS LAST → issueKey ASC**로 정렬한다(백로그와 동일 tiebreaker, `BacklogApplicationService` 비교자 패턴).
- **FR3**. 프론트 boards.ts BoardCard Zod 스키마에 `rank`(nullish→null)를 추가한다.
- **FR4**. 각 **셀**(스윔레인 그룹 × 컬럼. NONE이면 컬럼 전체)을 `@dnd-kit/sortable` `SortableContext`로 감싸고, 카드를 `useSortable`로 만든다. 컬럼 간 이동(`useDroppable` 상태 전이)과 한 `DndContext`에서 공존한다.
- **FR5**. 같은 셀 내 드롭 시(구 noop 경로), 드롭 위치의 앞/뒤 이웃 카드 issueKey를 **셀 내 카드 기준**으로 계산해 `rerankIssue(dragged, {previousIssueKey, nextIssueKey})`를 호출한다. 맨 앞=previous 생략, 맨 뒤=next 생략.
- **FR6**. 리랭크는 낙관적 업데이트로 즉시 반영하고, 실패(409/기타) 시 롤백 + queryKey invalidate + toast(기존 useMoveCard 정합). 순서변경 낙관적 업데이트는 셀 내 배열 재정렬(arrayMove) 로직이 필요하므로 전용 mutation 훅(예: `useReorderCard`)을 신설하되 useMoveCard의 filter-aware queryKey·롤백 패턴을 재사용한다.
- **FR7**. `resolveDropAction`을 확장/분기해 (a) 다른 컬럼=move/needs-resolution(기존), (b) **같은 셀 내 위치변경=rerank**, (c) 같은 컬럼·다른 스윔레인 그룹=noop(PR21b 예정), (d) 제자리=noop를 구분한다. 순수 헬퍼로 유지(테스트 가능). 셀 판정에 스윔레인 그룹 식별자(컬럼 + 그룹 key)를 사용한다.

## 비기능 요구사항 (NFR)

- **NFR1 (무회귀)**. 컬럼 간 이동·DONE resolution·필터·WIP 경고·낙관적 업데이트 등 기존 보드 동작이 100% 보존된다. 기존 보드 유닛/e2e green 유지.
- **NFR2 (BC 격리)**. issue-tracking 리랭크 API·서비스는 변경 0(호출만). agile-planning은 조회 view layer만 변경.
- **NFR3 (rank 소유권)**. rank 저장은 오직 issue-tracking 경로. 보드는 미러 노출만.
- **NFR4 (접근성)**. KeyboardSensor 유지, sortable 항목에 적절한 aria(dnd-kit 기본). 카드의 기존 Link 클릭(PointerSensor distance:5) 보존.
- **NFR5 (성능)**. rank 사이 중간값 고갈 시 서버 rebalance는 기존 로직 재사용(프론트 무관).

## API 인터페이스 (REST)

- **재사용**: `PATCH /api/v1/issues/{key}/rank` body `{previousIssueKey?, nextIssueKey?}` → `{data:{key, rank, version}}`. 400=이웃 둘 다 누락, 409=OCC. **신규 엔드포인트 없음**.
- **변경**: `GET /api/v1/boards/{boardId}`(또는 상세 조회) 응답의 각 카드에 `rank` 필드 추가 + 컬럼 내 rank 정렬.

## 데이터 모델 변경

- **없음**. `issues.rank` 컬럼은 이미 존재(FR-BL-01 V025 계열). 마이그레이션 0.

## 엣지 케이스

- **EC1 (제자리 드롭)**. 원래 위치=이웃 불변 → API 호출 안 함(noop).
- **EC2 (rank 전부 null인 컬럼)**. 최초 정렬 안 된 컬럼에서 첫 드래그: 이웃 중 하나가 null rank일 수 있음. 서버 `resolveNeighborRanks`가 null 이웃을 처리(백로그와 동일). 프론트는 issueKey만 전달.
- **EC3 (단일 카드 컬럼)**. 재배치 대상 없음 → 드래그해도 noop.
- **EC4 (컬럼 간 이동 후 위치)**. 이 PR 범위는 **같은 셀 내 순서만**. 컬럼 간 이동 시 목적 컬럼 내 위치 지정(순서까지)은 기존 move 동작 유지(맨 끝 등 서버 기본). 별도 확장은 out-of-scope.
- **EC5 (필터 활성 중 재배치)**. 필터로 일부 카드만 보일 때 이웃 계산은 **화면에 보이는 카드 기준**. rank는 전체 순서에 대해 계산되나 보이는 이웃 사이 배치라 사용자 기대와 일치. filter-aware queryKey 정합(useMoveCard 선례).
- **EC6 (드래그 취소, ESC)**. 드롭 전 취소 → 상태 원복, API 호출 없음.
- **EC7 (스윔레인 그룹 넘는 드래그)**. 같은 컬럼·다른 스윔레인 그룹으로 드롭 → **이번 PR noop**(PR21b에서 필드변경). rank 호출 안 함(그룹 넘는 순서변경은 그룹 필드가 달라 의미 모순).
- **EC8 (단일 카드 셀)**. 스윔레인 그룹에 카드 1개 → 재배치 대상 없음, noop.

## 제약 조건

- **스윔레인 간 필드변경(③④)은 이 PR 제외 → PR21b.** 이번엔 같은 셀 내 순서변경(①)+상태전이(②)만.
- 컬럼 간 이동 시 순서 지정(drop into position across columns)은 이 PR 제외.
- 새 리랭크 백엔드 로직 작성 금지(기존 재사용). 새 마이그레이션 금지.
- rank 계산을 프론트에서 하지 않는다(서버 between 계산). 프론트는 이웃 issueKey만 전달.
- `@dnd-kit/sortable`만 추가. 다른 드래그 라이브러리 도입 금지.

## 측정 가능한 완료 기준

- [ ] `BoardCardResponse.rank` 추가 + 보드 카드 rank ASC NULLS LAST → key ASC 정렬 (백엔드 유닛/통합 테스트).
- [ ] boards.ts BoardCard 스키마 rank 추가 + MSW board fixture/handler rank 반영.
- [ ] 셀 단위 SortableContext + 같은 셀 드롭 시 rerankIssue 호출 (유닛: resolveDropAction 4분기 — move/needs-resolution/rerank/noop(그룹경계·제자리)).
- [ ] 낙관적 업데이트(arrayMove) + 409 롤백 + toast (기존 패턴 정합 테스트).
- [ ] 스윔레인 활성 시 셀 내 순서변경 동작 + 그룹 경계 넘는 드래그 noop 검증.
- [ ] E2E: (NONE)같은 컬럼 순서변경 happy path + 새로고침 후 유지 + 컬럼 간 이동 무회귀.
- [ ] 기존 보드 유닛/e2e 전량 green (무회귀).
- [ ] typecheck 0 / lint 0 / build 0 / verify-master-plan 129/129.
- [ ] product agile-planning §보드 D단계 마킹(신규 FR 없음, 129 불변).

## Brainstorming Check

✅ 통과 (코드 실측 기반 sanity check, 1회 iteration).
- **gap 발견 1 (스윔레인 상호작용)**: 보드 스윔레인 4종(NONE/ASSIGNEE/PRIORITY/EPIC) 실재 → 순서변경과 그룹핑 충돌. Maxi 결정으로 셀 개념 도입 + 필드변경 PR21b 분할.
- **검증됨 (거짓 gap)**: EC2 null 이웃 → 서버 `resolveNeighborRanks`가 lazy 미부여를 rebalance로 처리(백엔드 완비). 리랭크 API(`rerankIssue`)·담당자/우선순위/에픽 변경 API 모두 실재(phantom 아님).
- **구현 주의(plan 위임)**: 기존 보드 카드 정렬 기준 실측(rank 정렬 도입이 기존 e2e 순서 assert에 영향 없는지) · dnd-kit sortable에서 over가 카드일 때 셀 판정 · 새 useReorderCard 훅.
