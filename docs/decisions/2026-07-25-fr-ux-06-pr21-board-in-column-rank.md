# ADR: 보드 컬럼 내 카드 순서변경 — 보드 조회 rank 미러 노출 + 기존 리랭크 API 재사용 (FR-UX-06 PR21)

> 날짜: 2026-07-25
> 상태: 채택
> 범위: agile-planning BC(보드 조회 view layer) + apps/web(@dnd-kit/sortable). issue-tracking rank 컬럼·리랭크 API는 재사용(변경 0).
> 관련 ADR: [FR-BL-01 LexoRank 정렬](2026-06-23-fr-bl-01-lexorank-backlog-ordering.md)(결정2·4에서 "미래 보드 컬럼 내 정렬 재사용" 예견)
> 관련 FR: FR-UX-06 (Phase 5 PR21)

## 맥락

칸반 보드(`KanbanBoard.tsx`)는 현재 카드를 다른 컬럼(=다른 워크플로우 상태)으로 옮기는 상태 전이만 지원한다. `resolveDropAction`은 같은 컬럼 내 드롭을 noop(EC1, `KanbanBoard.tsx:84`)으로 무시한다. FR-UX-06 Phase 5 PR21은 같은 컬럼 안에서 카드의 상하 순서를 드래그로 재배치하는 기능을 신설한다.

실측 결과 백엔드 정합에 갭이 있다.

- `issues.rank`(TEXT) 컬럼과 리랭크 API(`PATCH /api/v1/issues/{key}/rank`, `BacklogRankService.rerank` — 이웃 중간값 계산 + rebalance)는 FR-BL-01에서 이미 완비됐다(issue-tracking BC 소유).
- 그러나 보드 조회 응답 `BoardCardResponse`(`BoardResponses.kt`)에는 rank가 없고, `BoardRepository`는 카드를 rank로 정렬하지 않는다. 따라서 프론트가 현재 순서를 알 수도, 재배치를 저장/복원할 수도 없다.

## 결정 1 — 보드 조회 view layer가 issue-tracking rank를 미러 노출

`BoardCardResponse`에 `rank: String?`를 추가하고, 보드 카드 조회를 `rank ASC NULLS LAST → priority ASC → key ASC`로 정렬한다(백로그 `BacklogResponses.kt`·`BacklogApplicationService`의 rank 규칙에 보드 기존 priority tiebreaker를 유지 결합 — rank 미부여 시 기존 priority 정렬 무회귀). 구현은 `BoardCardPlacement.CARD_COMPARATOR`.

**근거**.
- rank 소유권은 issue-tracking(`issues.rank`)에 그대로 둔다. 보드는 조회 시 rank를 **미러 노출**만 한다(백로그 view가 이미 하는 것과 동일). 새 rank 저장 경로를 만들지 않는다.
- FR-BL-01 ADR 결정4가 "미래 보드 컬럼 내 정렬(agile-planning)에서도 재사용 가능"을 명시했다. 이 결정이 그 예견을 실현한다.
- `PatchMerge`/워크플로우 불변식 우회 위험(FR-BD-01 ADR 결정3)은 없다. 리랭크는 rank 스칼라만 바꾸고 상태 전이와 직교한다.

## 결정 2 — 리랭크 저장은 기존 `PATCH /api/v1/issues/{key}/rank` 재사용 (issue-tracking 변경 0)

컬럼 내 드롭 시 프론트가 기존 리랭크 API를 호출한다. issue-tracking에 신규 엔드포인트/서비스를 만들지 않는다. 프론트 API 클라이언트(`api/boards.ts` 또는 `api/issues.ts`)만 신설한다(현재 리랭크 클라이언트는 `api/backlog.ts`에만 존재).

**근거**.
- 리랭크 도메인 로직(이웃 rank 사이 중간값, 고갈 시 rebalance, OCC)은 백로그와 동일하다. 재구현은 중복·drift 위험.
- BC 경계 준수. 실제 코드 변경은 agile-planning(조회) + 프론트뿐. issue-tracking은 호출만 받는다.

## 결정 3 — `@dnd-kit/sortable` 도입, 상태 전이 드래그와 공존

컬럼 내 정렬은 `@dnd-kit/sortable`의 `SortableContext` + `useSortable`로 구현한다. 기존 컬럼 간 이동(`useDraggable`/`useDroppable` 기반 상태 전이)과 한 `DndContext` 안에서 공존한다.

**근거**.
- `@dnd-kit/core`(6.3.1)·`@dnd-kit/utilities`가 이미 설치돼 있고 sortable은 같은 패밀리 공식 확장(버전 호환). Maxi 승인(2026-07-25).
- core만으로 인덱스 계산을 수작업하면 재발명·회귀 위험. 공식 패턴 사용.

## 대안

- **보드 전용 rank 테이블(agile-planning 소유)** — issues 테이블을 안 건드리나, 백로그와 rank 이원화 → 같은 이슈가 백로그/보드에서 다른 순서. FR-BL-01 ADR이 이미 "issues.rank 단일 소유"로 기각한 패턴. 기각.
- **프론트 로컬 순서(서버 미저장)** — 새로고침/타 사용자 간 순서 소실. 완제품 기준 위배. 기각.
- **sortable 없이 core 직접 구현** — 새 의존성 0이나 재발명·버그 위험. 기각(Maxi).

## 결정 4 — 완전 Jira 스윔레인 드래그(4종)를 2 PR로 분할

최종 목표는 Jira Cloud 스윔레인 보드 드래그 4종(①셀 내 순서변경 ②컬럼 간 상태전이 ③담당자 재할당 ④우선순위/에픽 변경)이다. 실측 결과 ③④는 스윔레인 타입별 별도 mutation(담당자 UUID 역산 배선·priority·epic-children)과 낙관적 업데이트 3종을 요구해 단일 PR로는 과대하다. **PR21 = ①+②(스윔레인 활성 시 셀 내 순서변경 포함, 그룹 경계 넘는 드래그는 noop), PR21b = ③+④**로 분할한다.

**근거**. 22 PR 체인 철학(큰 기능 잘게)과 일치. 각 PR 독립 검증 가능. ③④의 API(`PATCH /assignee`, `updateIssue priority`, `POST epic-children`)는 실재하나 배선 복잡도가 ①②와 분리된다. rank 순서변경만도 백엔드 rank 노출+sortable+낙관적 업데이트로 태스크 6~8개.

## 결과

- agile-planning `BoardCardResponse`에 `rank` 필드 추가 + 보드 카드 조회 rank 정렬.
- 프론트 `@dnd-kit/sortable` 추가 + `KanbanBoard` 컬럼 내 SortableContext + 같은 컬럼 드롭 시 리랭크 호출(noop→rerank).
- issue-tracking 리랭크 API 재사용(변경 0).
- FR-UX-06 귀속, FR 총수 129 불변, product D단계 마킹만.
- rank 정렬 tiebreaker(rank 동값/null → key ASC), OCC 충돌 처리, 낙관적 업데이트 정합은 spec에서 확정한다.
