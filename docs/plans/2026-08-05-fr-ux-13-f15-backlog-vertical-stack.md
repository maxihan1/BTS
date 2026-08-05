# FR-UX-13 F15 — 백로그 세로 스택 + 스프린트 다이얼로그 + 키보드 DnD

> slug: fr-ux-13-f15-backlog-vertical-stack
> type: ui (classify override — 아래 §분류 override 참조)
> agent: frontend-engineer
> 생성: 2026-08-05

## Brief

**사용자 원문**. `/bts fr-ux-13 f15, f16 진행해줘`

**Maxi 결정 2건 (착수 전 게이트)**.

1. **PR 분할** — F15 → F16 **2개 PR 순차**. F15 의 4요소(세로 스택 · 시작 다이얼로그 ·
   완료 다이얼로그 · 키보드 DnD)는 전부 `BacklogBoard.tsx` + `backlog.spec.ts` 라는
   같은 두 파일을 건드리는 하나의 관심사라 쪼개면 같은 테스트를 두 번 재작성하게 된다.
   F16(필터바 + 에픽 패널)은 다른 파일이고 F15 결과 위에 얹히므로 **이 PR 머지 후** 별도 PR.
2. **스프린트 완료 시 미완료 이슈** — **프론트가 기존 이슈 해제 API 를 반복 호출**.
   백엔드 변경 0줄 유지 (정본 방침 승계 · `SprintController.kt:221` `complete(id)` 에
   이관 파라미터 부재).

## 분류 override

`classify-task.ts` 는 `type=backend` / `agent=backend-engineer` / `primary_bc=agile-planning`
을 반환했다. **override 한다 — `type=ui` / `agent=frontend-engineer`** (primary_bc 는 유지).

**근거 3중**.
- 정본 `docs/plan/product/personalization.md §4.11` — "아키텍처. **프론트 전용 예상**"
- 실측 — 백엔드 변경 0줄 (D3/D4/D5 가 정본에서 "없음 예상")
- Maxi 결정 2 (위) 가 백엔드 변경 0줄을 명시 확정

classify 가 "스프린트" 키워드를 agile-planning BC 신호로 읽어 backend 로 단정한 것으로 보인다
(`task_count: 0` = 신호 없음). 선례. `docs/plans/2026-05-20-pr4-cleanup-and-obsidian-sync.md:25`.

**게이트 정책**. `type=ui` 지만 §ui 소규모 게이트(task ≤3 + 신규 도메인 개념 없음)에
해당하지 않는다 — 신규 다이얼로그 2종 + 레이아웃 전면 개편 + 센서 추가로 task 4+ 가 확실하다.
**2게이트 유지**.

## 착수 전 실측 (정본 대조)

정본 서술을 그대로 믿지 않고 실측했다. **F5 에서 정본 처방이 두 겹 틀렸던 전례**
(`fr-ux-13-f5-backlog-card-assignee-done`) 때문이다.

### 정본이 틀린 것 — 3건

| 정본 서술 | 실측 | 영향 |
|---|---|---|
| `BacklogBoard.test.tsx` **759행** | **1187행** | 재작성 범위 산정 +56% |
| `backlog.spec.ts` **536행** | **742행** | 재작성 범위 산정 +38% |
| `BacklogBoard.tsx:246,248` (가로 칸반) | **`:178`** | 파일 전체가 219행 |

정본이 "착수 전 재작성 범위를 먼저 산정한다"고 요구한 그 숫자가 틀렸다. 실제 영향 테스트는
**1,929행**이다.

### 정본이 맞은 것

- 백로그는 **가로 칸반** — `BacklogBoard.tsx:178` `<div className="flex gap-4 overflow-x-auto pb-4">`
- `StartSprintDialog.tsx` · `CompleteSprintDialog.tsx` **부재** — `SprintColumn` 의 버튼이
  확인 없이 곧장 `startSprint.mutate` / `completeSprint.mutate` 호출 (`BacklogBoard.tsx:194-199`)
- **키보드 DnD 부재** — `useSensors(useSensor(PointerSensor, …))` 만 (`:103-105`) ·
  `accessibility={undefined}` 로 공지가 **명시적으로 꺼져 있다** (`:176`)
- 재사용 자산 실재 — `KanbanBoard.tsx:526` `useSensor(KeyboardSensor)` ·
  `:285` `buildDragAnnouncements`(한국어) · `:623` `accessibility={{ announcements }}`
- `FilterBar.tsx`(343행) 슬롯 4종 실재 — **F16 소관**

### 기존 백로그 자산 (재작성 대상 판단용)

```
components/backlog/  BacklogBoard.tsx 219 · BacklogColumn.tsx 140 · SprintColumn.tsx 146
                     SprintColumnHeader.tsx 131 · CreateSprintForm.tsx 79 · BacklogCard.tsx 236
                     use-backlog-drag.ts 136 · backlog-collision.ts 35
lib/                 backlog-drag.ts 246 (순수 함수 — 드롭 판정)
hooks/               use-backlog.ts 224
i18n/                backlog-labels.ts 121
e2e/                 backlog.spec.ts 742
```

## 도메인 정리

- **BC**. agile-planning (프론트 소비 · **백엔드 변경 0줄**)
- **영향 엔티티**. Sprint (기존) · BacklogIssue (기존). **신규 엔티티 0**
- **신규 용어**. **없음** — `스프린트`·`백로그`·`에픽` 은 `glossary.md:19-21,117` 에 이미 있고
  「세로 스택」·「시작 다이얼로그」는 UI 배치 용어라 유비쿼터스 언어 대상이 아니다
- **기존 결정 충돌**. 없음
- **관련 ADR**. [`docs/decisions/2026-08-05-fr-ux-13-f15-backlog-vertical-stack.md`](../decisions/2026-08-05-fr-ux-13-f15-backlog-vertical-stack.md) (생성됨)
- **`domain/agile-planning.md` 갱신**. 불필요 — "스프린트 라이프사이클 (계획/시작/종료/회고)"
  서술이 그대로 유효

### 스프린트 상태 전이 (백엔드 확정 · 변경 불가)

`PLANNED → ACTIVE → COMPLETED` **단방향 FSM**. 위반 시 409
(`Sprint.kt` `InvalidSprintTransitionException`).

| 엔드포인트 | 실측 시그니처 | 함의 |
|---|---|---|
| `POST /sprints/{id}/start` | **파라미터 0** (`SprintController.kt:200`) | 시작 시 날짜·목표를 못 넘긴다 |
| `POST /sprints/{id}/complete` | **파라미터 0** (`:221`) | 이관 파라미터 부재 — 정본 서술 확인 |
| `PATCH /sprints/{id}` | `name`·`goal`·`startDate`·`endDate` 3-state + **`version` 필수** (`:149`) | 날짜·목표는 이 경로로만 |
| `DELETE /sprints/{id}/issues/{key}` | **멱등 204** | 재시도 안전 |
| `POST /sprints/{id}/issues` | COMPLETED 대상이면 **409** (`:244`) | **이관은 완료 전에** — 순서가 계약 |

`version` 은 별도 조회 없이 확보된다 — 백로그 응답의 스프린트 메타가 이미
`version`·`goal`·`startDate`·`endDate` 를 싣는다 (`api/backlog.ts:52-68` `sprintMetaSchema`).

### 세로 스택 섹션 모델

백로그 응답은 `{ backlog: Issue[], sprints: [{ sprint, issues }], truncated }` 이고
**섹션은 이 응답이 이미 가진 구조 그대로**다 — 새 모델을 만들 필요가 없다.
현재 가로 칸반은 `BacklogColumn`(백로그) 이 **먼저**, 그 뒤에 스프린트들이 온다
(`BacklogBoard.tsx:179-203`). 지라 백로그는 **스프린트 섹션이 위, 백로그가 맨 아래**라
세로 전환 시 순서가 뒤집힌다 — 스펙에서 확정한다.

### Maxi 도메인 결정 2건 (2026-08-05)

1. **시작 다이얼로그는 기간·목표를 입력받는다** → `PATCH`(변경분만) → `start` **2단계**.
   비용은 「수정은 됐는데 시작은 실패」한 중간 상태이며, 스펙이 문구·재시도 경로를 정한다.
2. **완료 시 이관 대상은 백로그 또는 완료되지 않은 다른 스프린트**.
   백로그행은 이슈당 요청 1회, 다른 스프린트행은 2회(`DELETE` + `POST`).
   COMPLETED 스프린트는 **대상 목록에서 제외**한다 — 넣으면 반드시 409 로 끝나는 선택지가 된다.

## 스펙

전체 스펙(807행). [`docs/specs/2026-08-05-fr-ux-13-f15-backlog-vertical-stack.md`](../specs/2026-08-05-fr-ux-13-f15-backlog-vertical-stack.md)
— FR 14 · NFR · 엣지 15 · S1~S8 · 시각 사양(상태 매트릭스 7종 · 반응형 4종) · Jira 대조 4단계.

**핵심 3줄.**
- 백로그가 가로 칸반에서 **세로 스택**이 된다. 스프린트가 위, 백로그가 맨 아래 — 이 순서는
  발명이 아니라 백엔드가 이미 그렇게 정렬해 내려준다(`sprintComparator` ACTIVE→PLANNED→COMPLETED)
- 스프린트 시작은 **기간·목표를 받는 다이얼로그**를 거치고(변경분만 `PATCH` → `start`),
  완료는 **미완료 이슈를 먼저 옮긴 뒤**에만 진행된다
- 드래그가 **키보드로도** 되고, 진행을 **한국어로** 읽어준다

### ★ 이 스펙의 지배 제약 — 완료는 되돌릴 수 없다

착수 전 실측으로 확인한 백엔드 동작이다. 스펙의 안전장치 대부분이 여기서 나온다.

- `SprintRepository.unassignIssue` 는 `WHERE EXISTS (… AND SPRINTS.STATUS <> 'COMPLETED')`
  조건부 DELETE 다 — **COMPLETED 스프린트에서는 조용히 아무것도 하지 않고 204** 를 준다
- `V503__sprints.sql` `CONSTRAINT sprint_issues_issue_key_unique UNIQUE (issue_key)` —
  한 이슈는 전역적으로 한 스프린트에만 속한다. 주석이 "다른 스프린트로 재할당하려면 먼저
  제거해야 한다"고 못박는데, **그 제거가 위에서 막힌다**

**즉 완료된 스프린트에 남은 이슈는 꺼낼 수도 옮길 수도 없다.** 「이관을 먼저」는 취향이 아니라
안전 요구이고, 「부분 실패 시 완료 중단」·「truncated 면 완료 차단」도 전부 이 한 줄에서 파생된다.

### 실측이 뒤집은 서술 2건

| 어디 | 서술 | 실측 |
|---|---|---|
| 이 plan §도메인 정리 (내가 쓴 것) | `accessibility={undefined}` 라 "공지가 꺼져 있다" | **거짓.** `@dnd-kit/core@6.3.1` 은 undefined 면 **영어 기본 공지**를 쓴다. 지금 스크린리더는 `Picked up draggable item backlog:ATLAS-2.` 를 읽는다 |
| `hooks/use-backlog.ts:210-211` 주석 | "완료 후 미완성 이슈는 백엔드에서 backlog로 이동시키므로" | **거짓.** `SprintApplicationService.kt:282-290` 은 상태만 뒤집는다. 이슈는 그대로 남는다 |

### 지시서에 없던 즉사 계약 2건 (designer 발견 · 내가 실측 확인)

- `DELETE /issues/{key}` 의 COMPLETED no-op + `UNIQUE(issue_key)` → **영구 동결** (위 지배 제약)
- `button-primitive-usage.test.ts:206-208` 이 `SCANNED_FILES` 전원의 **파일 실재**를 단언한다.
  목록에 `SprintColumn.tsx`·`CreateSprintForm.tsx` 가 있어 **파일명을 바꾸면 즉사**한다

### Maxi 결정 (스펙 단계)

**U1 — truncated 상태에서 스프린트 완료를 막는다** (2026-08-05 확정). 상한은
`IssueRepository.kt:1204` `BOARD_CARD_FETCH_LIMIT = 1000`. 대가는 1,000건 초과 프로젝트가
F16(필터바) 전까지 스프린트를 완료할 수 없다는 것이며, 뒤집기 쉽도록 상수 1개
(`BLOCK_COMPLETE_WHEN_TRUNCATED`)로 분기하고 두 동작 각각에 테스트를 붙인다.

### 미해소 1건 — 착수 시 가장 먼저

**U2. 같은 `stateKey` 가 워크플로우마다 다른 카테고리를 갖는 실데이터가 있는가.**
`backlogIssueSchema`(`api/backlog.ts:25-42`)에 카테고리 필드가 **없다**는 것은 실측 확인됐고,
`GET /api/v1/workflows`(`WorkflowController.kt:52` — 파라미터 0 · 전역 목록)로 사상을 만든다.
미상·충돌 키는 **전부 미완료로 기울인다** — 과다 표시는 이관 대상이 늘 뿐이고 `DELETE` 가
멱등이라 무해하지만, 과소 표시는 이슈를 영구 동결시킨다. 안전측이라 기능은 어느 쪽이든
안전하고, 충돌이 실재하면 프로젝트 워크플로우 스킴 경유로 승격할지만 판단하면 된다.

## Brainstorming Check

**ui 경량 경로로 Phase B 스킵** (`/bts-spec` §ui 경량 경로 — Maxi 확정 2026-08-03).
신규 라우트·엔티티·도메인 개념이 0이라 조건을 충족한다. brainstorming 대신 아래가
sanity check 를 대신했다.

- `## Jira 대조` 4단계 — 대응 화면 식별 → 조작감 갭 → BTS 제약 교차 → 대응 없는 것 ADS 준용
- **즉사 계약 실측** — 계약 §5 사전 grep 을 착수 전에 돌려 `'스프린트 시작'`·`'스프린트 완료'`
  버튼 이름 고정, e2e 12파일 영향, `role="dialog"` 고유 label 요구를 스펙에 반영
- **시각 검증 기준 섹션** — 영향 E2E 전수 + 브라우저 눈확인 항목 (계약 §6)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
