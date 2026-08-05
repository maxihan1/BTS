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

## Plan

**분해 원칙 2가지.**

1. **공유 파일을 Task 1 에 몰았다.** `i18n/backlog-labels.ts` 는 거의 모든 FR 이 문구를 추가하는
   파일이라 병렬 wave 에 흩으면 `lint-staged` 가 남의 미완성 산출물을 stage 하는 race 가 재현된다
   (2026-05-26·05-27 교훈 2건). 라벨·API·훅·MSW 를 Task 1 이 전부 깔고 나머지는 **소비만** 한다.
2. **순수 함수를 먼저 뽑았다.** 판정(T2) · 충돌 감지(T3) · 공지(T4) · 접기 상태(T5) 는
   DOM 없이 단언되고, 이들이 red 를 못 내면 UI task 는 시작할 가치가 없다.

`type == ui` 시각 검증 트랙이므로 **RED 는 red-first 순서 강제가 아니라 「동반 테스트 명세」**로
읽는다. 단 T2·T3·T4·T5 는 순수 로직이라 **red-first 를 지킨다**.

---

### Task 1. 문구·API·훅·MSW 기반 깔기

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/i18n/backlog-labels.ts`, `apps/web/src/i18n/backlog-labels.test.ts`, `apps/web/src/i18n/__tests__/create-entry-point-names.test.ts`, `apps/web/src/api/backlog.ts`, `apps/web/src/api/backlog.test.ts`, `apps/web/src/hooks/use-backlog.ts`, `apps/web/src/hooks/use-backlog.test.tsx`, `apps/web/src/mocks/backlog-handlers.ts`, `apps/web/src/mocks/backlog-handlers.test.ts`]
- depends-on: []

**RED**.
- `backlog-handlers.test.ts` — `PATCH /api/v1/sprints/:id` 가 3-state partial 을 반영하고 `version` 을 올린다 · **`version` 불일치면 409**
- `api/backlog.test.ts` — `patchSprint()` 가 바뀐 필드 + `version` 만 담아 보낸다
- `create-entry-point-names.test.ts` — 신규 버튼 이름(섹션 접기 토글)이 `BACKLOG_SCREEN_BUTTON_NAMES` 에 **등록돼 있다**
- 실패 예상. `http.patch` 핸들러 부재 · `patchSprint` 미존재 · 집합에 신규 이름 없음

**GREEN**.
- `backlog-labels.ts` — FR-4 실패 3갈래 · FR-6 이관 상태/요약 · FR-9 공지 9종 + `screenReaderInstructions` · FR-2 접기 토글 이름. **`취소`(`ko.ts:785`)와 `retry`(`다시 시도`)는 재사용, 새로 만들지 않는다** (FR-10)
- `api/backlog.ts` — `patchSprint` + `sprintMetaSchema` 재사용 응답 파싱
- `use-backlog.ts` — `usePatchSprint` 추가 + **`:210-211` 거짓 주석 정정**(백엔드는 상태만 뒤집는다)
- `backlog-handlers.ts` — `http.patch` + 409 분기 + 실패 토글(기존 `LS_KEY_BACKLOG_FAIL` 선례)

**REFACTOR**. 문구 키를 FR 별로 묶고 각 그룹에 근거 주석(어느 FR·왜 이 문구인지).

**검증**.
- `pnpm vitest run src/i18n src/api/backlog.test.ts src/hooks/use-backlog.test.tsx src/mocks/backlog-handlers.test.ts`
- 동반 E2E. **없음** (UI 무변경 task)
- 눈확인. **없음**

---

### Task 2. 미완료 판정 순수 모듈 (FR-7)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/lib/backlog-completion.ts`, `apps/web/src/lib/backlog-completion.test.ts`]
- depends-on: []

**RED**. T-WF-1 · T-WF-2.
- 카테고리 집합이 정확히 `{DONE}` 인 키만 **완료**
- 빈 집합(어느 워크플로우에도 없는 키) → **미완료**
- `{DONE, IN_PROGRESS}` 처럼 워크플로우마다 다른 키 → **미완료**
- 워크플로우 목록이 `undefined`(조회 실패) → **전건 미완료** + 안내 플래그 true

**GREEN**. `buildStateCategoryMap(workflows)` + `isIssueIncomplete(issue, map)` 2개 순수 함수.

**REFACTOR**. 안전측 판정 이유를 함수 KDoc 에 남긴다 — 과소 분류가 이슈를 **영구 동결**시킨다는 것(ADR C1).

**검증**.
- `pnpm vitest run src/lib/backlog-completion.test.ts`
- **뮤테이션 검증**. 충돌 키 판정을 「완료」로 뒤집으면 red 가 되는지 확인 후 `git checkout --` 로 원복.
  ★원복 전에 GREEN 이 **커밋돼 있어야** 한다 (`mutation-test-requires-committed-baseline` 교훈)
- 동반 E2E · 눈확인. 없음

**★ 착수 전 최우선 확인 (U2)**. 개발 DB 에서 `GET /api/v1/workflows` 를 받아
`states[].key` 별 `category` 집합 크기가 2 이상인 키를 센다. 결과를 plan 에 **수치로** 적는다.
0건이면 현행 사상 유지, 1건 이상이면 그 키 목록을 테스트 픽스처에 넣는다.

---

### Task 3. 키보드 충돌 감지 분기 (FR-8)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/backlog/backlog-collision.ts`, `apps/web/src/components/backlog/backlog-collision.test.ts`]
- depends-on: []

**RED**. T-KB-2 — `cardFirstCollision` 에 `pointerCoordinates: null` 을 주면 **카드 droppable 이 칸보다 먼저** 반환된다.
- 현재 코드는 `pointerWithin` 2회 시도가 **빈 배열로 즉시 빠지고**(`core.esm.js:472-481` `if (!pointerCoordinates) return []`) `rectIntersection` 폴백만 남아 카드 우선이 소멸한다. 이 테스트가 red 여야 한다

**GREEN**. 포인터 좌표 부재 시 카드 droppable 만으로 `rectIntersection` 을 먼저 시도하고, 결과가 없을 때 칸으로 넘어가는 분기 추가.

**REFACTOR**. 포인터/키보드 두 경로가 **같은 우선순위 의도**를 표현하도록 공통 헬퍼로 정리.

**검증**.
- `pnpm vitest run src/components/backlog/backlog-collision.test.ts`
- **뮤테이션 검증**. 키보드 분기를 지우면 red (완료 기준 명시 항목)
- 동반 E2E · 눈확인. 없음 (S19 가 Task 10 에서 실동작을 잰다)

---

### Task 4. 한국어 드래그 공지 모듈 (FR-9)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/lib/backlog-announcements.ts`, `apps/web/src/lib/backlog-announcements.test.ts`]
- depends-on: [1]

**RED**. T-KB-1.
- 반환 문자열이 한국어이고 `backlog:`/`sprint:` **접두 id 를 포함하지 않는다**(이슈 키만 노출)
- 9종 이벤트(start/over 3갈래/end 4갈래/cancel) 각각의 문구

**GREEN**. `buildBacklogAnnouncements(view: BacklogView): Announcements`.
- `onDragOver`/`onDragEnd` 는 **`resolveBacklogDropAction` 을 그대로 재사용**해 공지와 실제 mutation 이 어긋나지 않게 한다
- 문구는 전부 `backlogLabels` 에서 읽는다 (하드코딩 0)

**REFACTOR**. 「스프린트」·「백로그」가 받침이 없어 조사 판별이 원리적으로 불필요하다는 근거를 주석으로.
`KanbanBoard.tsx:126` 의 비-export `josaEuro` 를 **끌어내지 않는다** — 무관한 파일 무변경.

**검증**.
- `pnpm vitest run src/lib/backlog-announcements.test.ts`
- 동반 E2E. 없음 · 눈확인. 없음 (VoiceOver 확인은 Task 9)

---

### Task 5. 섹션 접기 영속 훅 (FR-2 상태)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/hooks/use-backlog-collapsed.ts`, `apps/web/src/hooks/use-backlog-collapsed.test.tsx`]
- depends-on: []

**RED**. T-CL-1.
- 접기 → 재마운트 후에도 접힘 (`bts.backlog.collapsed.{projectKey}`)
- `localStorage` 가 **예외를 던져도** 전부 펼침으로 렌더 (fail-safe)
- 파싱 실패(깨진 JSON)도 전부 펼침

**GREEN**. `hooks/use-sidebar-collapsed.ts` 의 zustand + try/catch 템플릿을 따른다 (계약 §4 재사용).

**REFACTOR**. 프로젝트별 키 분리 이유 주석.

**검증**.
- `pnpm vitest run src/hooks/use-backlog-collapsed.test.tsx`
- 동반 E2E · 눈확인. 없음 (S14 가 Task 10)

---

### Task 6. 세로 스택 레이아웃 + 섹션 접기 UI (FR-1 · FR-2 UI · FR-13)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/backlog/BacklogBoard.tsx`, `apps/web/src/components/backlog/BacklogColumn.tsx`, `apps/web/src/components/backlog/SprintColumn.tsx`, `apps/web/src/components/backlog/SprintColumnHeader.tsx`, `apps/web/src/components/backlog/BacklogColumn.test.tsx`, `apps/web/src/components/backlog/SprintColumn.test.tsx`]
- depends-on: [1, 5]

**RED**. T-CL-2 + 레이아웃 단언.
- 섹션의 `textContent` 가 **여전히 섹션 이름으로 시작한다** — 접기 토글이 `sr-only` 텍스트를 넣으면 `backlog.spec.ts:149` 의 `^` 앵커가 깨지므로, 이름은 **`aria-label` 로만** 준다
- 접히면 카드 목록 div 가 **렌더되지 않는다**. 헤더의 이름·상태·개수·액션은 남는다

**GREEN**.
- `BacklogBoard.tsx:178` `flex gap-4 overflow-x-auto pb-4` → `flex flex-col gap-3`
- 렌더 순서 뒤집기 — **`sprints.map` 먼저, `BacklogColumn` 맨 마지막**. 스프린트 간 정렬은 **하지 않는다**(백엔드 `sprintComparator` 가 이미 정렬)
- `BacklogColumn.tsx:68` · `SprintColumn.tsx:83` 의 `min-w-72 w-72` → `w-full`
- 헤더 맨 앞에 `ChevronDown`/`ChevronRight` 토글 (`CreateIssueEntryButton.tsx:55-67` variant=icon 선례)
- `role="region"` + `columnAriaLabel(name, count)` **문자열 그대로 보존**
- truncated 배너는 **무변경** — 위치·문구·`role="alert"`·토큰 전부 그대로 (FR-13)

**REFACTOR**. 파일명 `SprintColumn.tsx`·`CreateSprintForm.tsx` **유지**
(`button-primitive-usage.test.ts:206-208` 이 파일 실재를 단언 — 이름을 바꾸면 즉사).

**검증**.
- `pnpm vitest run src/components/backlog`
- **동반 E2E (계약 §5)**. `backlog.spec.ts` · `issue-create-entry-points.spec.ts` · `sprint-burndown.spec.ts` — 헤더 재배치가 진입점·링크를 옮기므로 이 3종은 **필수 확인**
- **눈확인**. ① 세로 스택 기본(스프린트 위·백로그 아래·가로 스크롤 0) ② 헤더 액션 한 줄 정렬 + sticky 가 카드를 가리는지 ③ 접기/펼치기 + 새로고침 복원 ④ 모바일 375px 줄바꿈·터치 44px. **라이트·다크 양쪽**

---

### Task 7. 시작 다이얼로그 (FR-3 · FR-4)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/backlog/StartSprintDialog.tsx`, `apps/web/src/components/backlog/StartSprintDialog.test.tsx`]
- depends-on: [1]

**RED**. T-DL-2 · T-DL-3 · T-DL-4.
- 값 변경 시 `PATCH` → `start` **순서**. 값 무변경 시 `PATCH` **미호출**
- `PATCH` 200 + `start` 500 → 다이얼로그 유지 + 「기간·목표는 저장했지만」 문구 · **「전부 실패」 문구가 화면에 없음**을 함께 단언
- 재시도 시 `PATCH` 호출 수가 **늘지 않는다** (성공 응답으로 기준값을 갱신했으므로 변경분 0)

**GREEN**.
- `components/ui/dialog.tsx` compound 래퍼만 사용. **radix `Dialog as DialogPrimitive` 직접 import 금지**
- 필드 3종 — `Input type="date"` ×2 + `Textarea` 3행. 초기값은 **백로그 응답의 `sprintMeta`**(별도 조회 0)
- `DialogTitle` = `스프린트 시작`, 제출 버튼도 같은 이름, 취소는 `취소` 재사용
- 실패 문구 3갈래 분기 (비-409 / 409 / start 실패)

**REFACTOR**. `retry` 라벨 재사용이 조회 실패 화면과 **공존할 수 없는 이유**를 주석으로
(`BacklogBoard.tsx:125-143` 이 조기 반환해 다이얼로그가 통째로 언마운트된다).

**검증**.
- `pnpm vitest run src/components/backlog/StartSprintDialog.test.tsx`
- **뮤테이션 검증**. 중간 실패 문구를 「전부 실패했습니다」로 바꾸면 T-DL-3 이 red
- 동반 E2E. Task 9 배선 후 `backlog.spec.ts`
- **눈확인**. 시작 다이얼로그 기본 / 검증 에러 / 중간 실패 alert. 라이트·다크

---

### Task 8. 완료 다이얼로그 (FR-5 · FR-6)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/backlog/CompleteSprintDialog.tsx`, `apps/web/src/components/backlog/CompleteSprintDialog.test.tsx`]
- depends-on: [1, 2]

**RED**. T-CP-1 ~ T-CP-4.
- COMPLETED 스프린트가 Select 옵션에 **없다**
- 이관 요청이 **모두 끝난 뒤에** `complete` 가 호출된다 (호출 순서 배열 단언)
- 3건 중 1건 실패 → `complete` 호출 수 **0**
- 재시도 시 성공했던 2건의 요청 수가 **늘지 않는다**

**GREEN**.
- 요약 「완료 N건 · 미완료 M건」 + 미완료 목록(`max-h-72 overflow-y-auto`) + 단일 `Select` 이관 대상
- 대상 옵션 = 「백로그」 + **PLANNED·ACTIVE 스프린트**(자기 제외). 기본 「백로그」
- 이관 **직렬 실행**(동시 1). 백로그행 `DELETE` 1회 / 다른 스프린트행 `DELETE` → `POST` 2회
- 1건이라도 실패하면 `complete` **미발사**. 성공 행 「이관됨」 잠금 · 실패 행 「이관 실패」 · `role="alert"` 요약 · `다시 시도`
- 미완료 0건이면 목록·Select 없이 「옮길 이슈가 없습니다.」 + `complete` 1회
- **`truncated === true` 면 제출 차단** (Maxi 확정 U1). 상수 `BLOCK_COMPLETE_WHEN_TRUNCATED` 1개로 분기하고 **두 동작 각각에 테스트**를 붙인다

**REFACTOR**. 「이관 먼저」가 취향이 아니라 **영구 동결 방지**라는 근거를 파일 헤더 주석에.

**검증**.
- `pnpm vitest run src/components/backlog/CompleteSprintDialog.test.tsx`
- **뮤테이션 검증**. 부분 실패에도 `complete` 를 보내게 고치면 T-CP-3 이 red
- 동반 E2E. Task 9 배선 후
- **눈확인**. 미완료 있음 / 없음 / 이관 진행 중 / 부분 실패 / truncated 차단. 라이트·다크

---

### Task 9. 배선 — 다이얼로그·센서·공지 연결 (FR-8 센서 · FR-9 연결 · FR-10 · FR-12)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/backlog/BacklogBoard.tsx`, `apps/web/src/components/backlog/BacklogBoard.test.tsx`]
- depends-on: [3, 4, 6, 7, 8]

**RED**. T-DL-1 + 기존 유닛 갱신 4건.
- **시작 다이얼로그가 열린 동안 「보이는」 `스프린트 시작` 버튼이 정확히 1개** — FR-10 의 「Radix modal 이 바깥 트리거를 감춘다」는 **가정을 측정으로 바꾼다**
- `BacklogBoard.test.tsx:386` 「시작 버튼 클릭 시 mutate 호출」 → 다이얼로그 제출까지 거치도록 갱신
- `:398` 완료 버튼 동일 · `:410` `canManageSprint=false` 면 **다이얼로그가 열리지 않음** · `:442` 형태 보존

**GREEN**.
- `useSensors` 에 `useSensor(KeyboardSensor)` **추가**. `PointerSensor` 의 `distance: 5` 는 **그대로**(카드 안 `Link` 클릭 보존이 걸려 있다)
- `accessibility={undefined}` → `accessibility={{ announcements, screenReaderInstructions }}`
- 시작/완료 버튼이 곧장 `mutate` 하던 것을 다이얼로그 open 으로 교체. **버튼 이름은 불변**
- `BacklogCard.tsx` **DOM 무변경** — `{...listeners} {...attributes}` 뒤에 `aria-roledescription`·`aria-label` 이 오는 현재 순서 유지

**REFACTOR**. 다이얼로그는 **화면당 1개씩**만 마운트 (`CreateIssueDialog` 가 확립한 FR-15 원칙 승계 — 칸마다 두면 `role="dialog"` 가 N개가 되어 strict mode 로 깨진다).

**검증**.
- `pnpm vitest run src/components/backlog src/routes/projects.\$projectKey.backlog.test.tsx`
- `pnpm typecheck && pnpm lint`
- **동반 E2E**. `backlog.spec.ts` 전량
- **눈확인**. 키보드만으로 카드 이동(Tab→Space→↓→Space) + **macOS VoiceOver 한국어 공지 확인**

---

### Task 10. E2E 갱신 + 신규 7종

**메타**.
- agent: `qa-engineer`
- files: [`apps/web/e2e/backlog.spec.ts`]
- depends-on: [9]

**RED**. 신규 S13~S19 가 먼저 red 여야 한다.
| id | 시나리오 |
|---|---|
| S13 | 세로 스택 순서 — 스프린트가 백로그보다 **위** (`boundingBox().y` 비교) |
| S14 | 섹션 접기 → 카드 사라짐 → 새로고침 후에도 접힌 채 |
| S15 | 시작 다이얼로그에서 **종료일 변경 후** 제출 → ACTIVE 배지 |
| S16 | 완료 다이얼로그 이관 대상에 COMPLETED 스프린트가 **없다** |
| S17 | 요청 순서가 `DELETE`(들) → `POST /complete` |
| S18 | 이관 부분 실패 → `complete` 가 **나가지 않고** 다이얼로그가 남는다 |
| S19 | 키보드 DnD — Tab → Space → ↓ → Space 로 이동 성공 |

**GREEN**. 기존 3건 갱신.
- **S7 (갱신 확정)** — `:514-543`. ① 트리거 클릭 후 `getByRole('dialog', { name: '스프린트 시작' })` 가시성 단언 추가 ② 다이얼로그 안 제출 클릭 ③ **기존 Then 3줄(`:531-542`)은 그대로 보존**
- **S2·S3** — `dragCardToColumn`(`:204-231`)이 `boundingBox()` 좌표를 쓰는데 세로 스택에서 대상이 뷰포트 밖이면 좌표가 화면 밖이 된다 → 드래그 전 `scrollIntoViewIfNeeded()` 추가
- 나머지 8건(S1·S5·S6·초기렌더·S9~S12)은 **통과해야 한다** — 깨지면 그 자체가 회귀 신호

**REFACTOR**. 신규 시나리오의 셀렉터를 `backlogLabels` **import 로** 참조 (하드코딩 금지 — 2026-05-26 i18n 드리프트 교훈).

**검증**.
- `pnpm playwright test e2e/backlog.spec.ts`
- **백로그 라우트 참조 e2e 10파일 전량 동반 실행** — `backlog` · `timeline` · `timeline-zoom` · `sprint-burndown` · `project-velocity` · `project-cfd` · `project-cycle-time` · `project-tree` · `board-reorder` · `issue-create-entry-points`. **파일 목록과 결과를 PR 본문에 적는다**
- 눈확인. 위 Task 6~9 의 8항목을 최종 통합 확인

---

## Plan 메타

- **task 수**. 10
- **wave 예상**. 4 — `W1{T1,T2,T3,T5}` → `W2{T4,T6,T7,T8}` → `W3{T9}` → `W4{T10}`
- **파일 충돌**. 없음. `BacklogBoard.tsx` 를 만지는 T6·T9 는 `depends-on` 으로 직렬화됨
- **구현 규율**. ui 시각 검증 트랙 (red-first 면제) + **T2·T3·T4·T5 는 순수 로직이라 red-first 준수**
- **뮤테이션 검증 3건 필수**. T-KB-2(키보드 분기 삭제) · T-DL-3(문구 뒤집기) · T-CP-3(부분 실패에도 complete)
  — **GREEN 커밋 후에** 뮤테이션하고 `git checkout --` 로 원복 (미커밋 상태면 작업이 날아간다)
- **추가 검증**. `pnpm typecheck` · `pnpm lint` · `pnpm vitest run` 전량 · `pnpm playwright test`(10파일) · `bash scripts/verify-master-plan.sh`
- **착수 최우선**. Task 2 의 U2 확인(`GET /api/v1/workflows` 카테고리 충돌 실측)

## 리뷰 결과 (← /bts-review-plan 채움)
