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

### Task 1. 문구·API·훅·MSW·픽스처·판별식 기반 깔기 (리뷰 반영으로 확장)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/i18n/backlog-labels.ts`, `apps/web/src/i18n/backlog-labels.test.ts`, `apps/web/src/i18n/__tests__/create-entry-point-names.test.ts`, `apps/web/src/api/backlog.ts`, `apps/web/src/api/backlog.test.ts`, `apps/web/src/hooks/use-backlog.ts`, `apps/web/src/hooks/use-backlog.test.tsx`, `apps/web/src/mocks/backlog-handlers.ts`, `apps/web/src/mocks/backlog-handlers.test.ts`, `apps/web/src/mocks/backlog-fixtures.ts`, `apps/web/src/components/__tests__/button-primitive-usage.test.ts`, `apps/web/src/lib/backlog-drag.ts`, `apps/web/src/lib/backlog-drag.test.ts`, `apps/web/src/components/backlog/use-backlog-drag.ts`]
- depends-on: []

**★ 이 task 가 커진 이유.** 독립 리뷰 2종이 겹쳐 지적했다 — 원안의 T1 은 라벨을 **최소 12개
빠뜨렸고**, 그러면 T7·T8 이 **같은 wave 에서 둘 다** `backlog-labels.ts` 를 열어야 해서
plan 이 막겠다던 lint-staged race 가 그대로 재현된다. 공유 자원은 **전부** 여기서 닫는다.

**RED**.
- `backlog-handlers.test.ts` — `PATCH /api/v1/sprints/:id` 가 3-state partial 을 반영하고 `version` 을 올린다 · **`version` 불일치면 409**
- `api/backlog.test.ts` — `updateSprint()` 가 바뀐 필드 + `version` 만 담아 보낸다
- `create-entry-point-names.test.ts` — 신규 버튼 이름(섹션 접기 토글)이 `BACKLOG_SCREEN_BUTTON_NAMES` 에 **등록돼 있다**
- **`backlog-drag.test.ts` — `resolveOverToDropZone(view, over)` 가 칸/카드 both 를 판정한다** (신규 공용 함수. 아래 GREEN 참조)
- 실패 예상. `http.patch` 핸들러 부재 · `updateSprint` 미존재 · 집합에 신규 이름 없음 · 공용 함수 미존재

**GREEN**.
- **`backlog-labels.ts` — 스펙 문구 전수.** 원안 4묶음 + **리뷰가 적발한 누락 전량**.
  - FR-4 실패 **4갈래** (`start` 409 「이미 시작된 스프린트입니다.」 포함 — 스펙 §리뷰 반영 FR-4 정정)
  - FR-3 다이얼로그 제목 2 · `DialogDescription` 2 · 필드 라벨 3(시작일·종료일·목표)
  - FR-5 「완료 N건 · 미완료 M건」 · 「옮길 이슈가 없습니다.」 · 이관 대상 Select 라벨 · 「백로그」 옵션명
  - FR-6 「스프린트 시작 중…」 · 「스프린트 완료 중…」 · 「이관됨」 · 「이관 실패」 · 요약 alert · **403 문구**(C-15)
  - FR-7 워크플로우 조회 실패 안내 (E14)
  - FR-9 공지 **5종**(`noop-move` 포함 — C-4) + `screenReaderInstructions` + **권한 없음 문구**(FR-17)
  - FR-2 접기 토글 이름 (**상태 무관 고정** — C-9)
  - E8 종료일 < 시작일 · E15 truncated 차단 문구
  - **`취소`(`ko.ts:785`)와 `retry`(`다시 시도`)는 재사용**, 새 문자열을 만들지 않는다 (FR-10)
- `api/backlog.ts` — **`updateSprint`**(이름 통일 — 스펙 §API 표기를 따른다. 원안의 `patchSprint` 는 폐기) + `sprintMetaSchema` 재사용 응답 파싱
- `use-backlog.ts` — **`useUpdateSprint`** 추가 + **`:210-211` 거짓 주석 정정**(백엔드는 상태만 뒤집는다)
- `backlog-handlers.ts` — `http.patch` + 409 분기 + 실패 토글 + **`truncated` 시나리오 토글**(선례 `timeline-handlers.ts:49,102,133`)
- **`backlog-fixtures.ts` — COMPLETED 1개 + ACTIVE 1개 추가** (FR-18). 지금은 `PLANNED` 1개뿐이라 T-CP-1·S16·S17 이 전부 공허하다
- **`button-primitive-usage.test.ts` — `BATCH2_FILES` 에 신규 다이얼로그 2종 등록** (FR-19). 안 하면 「원시 `<button>` 0」 완료 기준이 아무것도 재지 않는다
- **`lib/backlog-drag.ts` — `resolveOverToDropZone(view, over)` 신설 + `use-backlog-drag.ts` 가 그것을 쓰도록 교체** (C-5). 지금은 드롭존 추출이 훅 안에 갇혀 있어 T4 공지 모듈이 **복제**할 수밖에 없고, 그러면 FR-8 의 「판정 로직을 두 벌로 나누지 않는다」가 첫날부터 깨진다

**REFACTOR**. 문구 키를 FR 별로 묶고 각 그룹에 근거 주석. `use-backlog-drag.ts` 는 **행동 무변경**이어야 한다 — 추출만 옮긴다.

**검증**.
- `pnpm vitest run src/i18n src/api/backlog.test.ts src/hooks/use-backlog.test.tsx src/mocks src/lib/backlog-drag.test.ts src/components/__tests__/button-primitive-usage.test.ts`
- **회귀 확인**. `pnpm vitest run src/components/backlog` — `use-backlog-drag` 추출 교체가 기존 드래그 단언을 깨지 않아야 한다
- 동반 E2E. `backlog.spec.ts` (픽스처가 바뀌므로 **필수** — 스프린트 2개 추가가 기존 11건을 깨지 않는지)
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

**RED**. T-KB-1 · **T-KB-5(신규)**.
- 반환 문자열이 한국어이고 `backlog:`/`sprint:` **접두 id 를 포함하지 않는다**(이슈 키만 노출)
- **10종** 이벤트 — start / over 3갈래 / **end 5갈래** / cancel. ★`onDragEnd` 는 원안이 4종이었는데
  `resolveBacklogDropAction` 의 반환 kind 는 **5종**이다(`noop-move` 누락 — `lib/backlog-drag.ts:203`).
  빠뜨리면 그 순간 스크린리더가 **침묵**하고 테스트는 초록이다 (C-4)
- **T-KB-5.** `canReorderIssue=false` 면 「옮겼습니다」류 문구가 **나오지 않는다** (FR-17)

**GREEN**. **`buildBacklogAnnouncements(view: BacklogView, canReorderIssue: boolean): Announcements`**.
- **권한 인자를 받는다** — 없으면 UPDATE 권한이 없는 사용자에게 「스프린트 1 스프린트로
  옮겼습니다.」를 읽어주면서 **mutation 은 0건**이다. 두 리뷰가 겹쳐 지적했다 (FR-17)
- **T1 이 공용화한 `resolveOverToDropZone` 을 쓴다** — 판정 함수만 같고 **입력 구성을 복제하면
  어긋난다**. 어긋남은 판정이 아니라 입력에서 난다 (C-5)
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

**RED**. T-CL-1 · **E7(신규 배정)**.
- 접기 → 재마운트 후에도 접힘 (`bts.backlog.collapsed.{projectKey}`)
- `localStorage` 가 **예외를 던져도** 전부 펼침으로 렌더 (fail-safe)
- 파싱 실패(깨진 JSON)도 전부 펼침
- **E7.** 저장된 목록에 **이제 없는 `sprint-{id}`** 가 들어 있어도 무시하고 정상 동작한다 (정리는 하지 않는다)

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

**RED**. T-CL-2 + 레이아웃 단언 · **E1 · E3 · E4(신규 배정)**.
- 섹션의 `textContent` 가 **여전히 섹션 이름으로 시작한다** — 접기 토글이 `sr-only` 텍스트를 넣으면 `backlog.spec.ts:149` 의 `^` 앵커가 깨지므로, 이름은 **`aria-label` 로만** 준다
- 접히면 카드 목록 div 가 **렌더되지 않는다**. 헤더의 이름·상태·개수·액션은 남는다
- **E1.** 스프린트 0개 → 백로그 섹션 하나만. 「스프린트 생성」 폼은 현행 위치 유지
- **E3.** 전부 접힘 → 헤더만 쌓인다. 카드가 없으므로 드래그 시작 지점도 없다 (교착 아님)
- **E4.** 접힌 섹션은 드롭 후보에 없다. ★훅 규칙 위반을 피해 **`useDroppable({ disabled: collapsed })` + div 미렌더**로 구현한다 — 훅 자체를 조건부 호출하면 안 된다. rect 가 없으면 `pointerWithin`·`rectIntersection` 둘 다 건너뛰므로 결과는 같다
- **`aria-controls` 는 넣지 않는다** (C-3). 접힌 상태에서 대상 id 가 DOM 에 없어 dangling IDREF 가 된다. **`aria-expanded` 만** 준다

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

**★ 컴포넌트 데이터 계약 (리뷰 BLOCKER — 원안에 없어서 추가).**
이 한 줄이 T-DL-2/3/4 의 성립 여부를 통째로 결정한다. 콜백 props 로 받으면 「요청 순서·호출 수」
단언 자체가 불가능해져 전부 T9 로 밀린다.

- 다이얼로그가 **`useUpdateSprint`·`useStartSprint` 를 직접 호출한다.**
- props 는 **`open` · `onOpenChange` · `sprint: SprintMeta` 만** 받는다.
- **`key={sprint.sprintId}` 로 마운트한다** — 폼 기준값 state 를 내부에 두므로, 대상이 바뀌어도
  재마운트되지 않으면 낡은 값이 남는다.
- 기준값은 **내부 state**다. 부모 props 를 기준값으로 쓰면 `PATCH` 성공 후에도 props 가 안 바뀌어
  (두 요청 모두 성공해야 invalidate) 재시도가 **낡은 `version` 으로 409** 를 받는다.
- MSW 가 vitest 전역에 붙어 있어(`test/setup.ts:19`) 훅 직접 호출이면 단위 단독 관측이 된다.

**RED**. T-DL-2 · T-DL-3 · T-DL-4 · **E8 · E10(신규 배정)**.
- 값 변경 시 `PATCH` → `start` **순서**. 값 무변경 시 `PATCH` **미호출**
- `PATCH` 200 + `start` 500 → 다이얼로그 유지 + 「기간·목표는 저장했지만」 문구 · **「전부 실패」 문구가 화면에 없음**을 함께 단언
- 재시도 시 `PATCH` 호출 수가 **늘지 않는다** (성공 응답으로 기준값을 갱신했으므로 변경분 0)
- **E8.** 종료일 < 시작일이면 제출을 막고 필드 에러를 띄운다. **왕복을 만들지 않는다** — 원안은 눈확인에만 맡겨 테스트가 없었다
- **E10.** `start` 가 **409** → 「이미 시작된 스프린트입니다.」 + **다이얼로그 닫기** + invalidate. **재시도 버튼이 없음**을 단언한다 — 나머지 세 갈래와 정반대 처방이라 뭉뚱그리면 거짓말이 된다

**GREEN**.
- `components/ui/dialog.tsx` compound 래퍼만 사용. **radix `Dialog as DialogPrimitive` 직접 import 금지**
- 필드 3종 — `Input type="date"` ×2 + `Textarea` 3행. 초기값은 **백로그 응답의 `sprintMeta`**(별도 조회 0)
- `DialogTitle` = `스프린트 시작`, 제출 버튼도 같은 이름, 취소는 `취소` 재사용
- 실패 문구 **4갈래** 분기 (`PATCH` 비-409 / `PATCH` 409 / `start` 비-409 / **`start` 409**)
- **닫을 때 `PATCH` 만 성공한 상태였으면 백로그를 invalidate 한다** — 안 하면 재개봉 시 기준값이 낡은 `version` 으로 리셋돼 다음 `PATCH` 가 409 다

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

**★ 컴포넌트 데이터 계약.** T7 과 동일 — 훅(`useCompleteSprint`·이관용 mutation)을 **직접
호출**하고 props 는 `open`·`onOpenChange`·`sprint`·`allSprints`·`truncated` 만. `key={sprint.sprintId}`.

**RED**. T-CP-1 ~ T-CP-4 · **E12 · truncated 양방향(신규 배정)**.
- **T-CP-1 은 짝 단언이어야 한다** (FR-18). COMPLETED 가 **없다** + **PLANNED·ACTIVE 가 실제로 들어 있다** + 옵션 개수.
  ★부정 단언 하나만 두면 **픽스처에 COMPLETED 가 0개라 자동으로 통과**한다 — 두 리뷰가 겹쳐 지적한 가짜 그린이고 PR #342 에서 2회 적발된 양식이다. T1 이 픽스처를 보강한 뒤에야 이 테스트가 의미를 갖는다
- 이관 요청이 **모두 끝난 뒤에** `complete` 가 호출된다 (호출 순서 배열 단언)
- 3건 중 1건 실패 → `complete` 호출 수 **0**
- 재시도 시 성공했던 2건의 요청 수가 **늘지 않는다**
- **E12.** 이관 도중 대상이 COMPLETED 로 전이 → `POST` 409 → 그 행만 실패 · **Select 를 잠그지 않는다**(대상을 다시 골라야 하므로)
- **`truncated` 양방향.** `BLOCK_COMPLETE_WHEN_TRUNCATED` 의 **두 값 각각**에 테스트를 붙인다.
  ★상수를 리터럴로 두면 TypeScript 가 좁혀서 반대 분기가 **도달 불가**가 된다 — 그러면 그 테스트가
  또 하나의 가짜 그린이다. **모듈에서 export 하고 `vi.spyOn`/`vi.mock` 으로 뒤집어** 두 경로를 실제로 지난다

**GREEN**.
- 요약 「완료 N건 · 미완료 M건」 + 미완료 목록(`max-h-72 overflow-y-auto`) + 단일 `Select` 이관 대상
- 대상 옵션 = 「백로그」 + **PLANNED·ACTIVE 스프린트**(자기 제외). 기본 「백로그」
- 이관 **직렬 실행**(동시 1). 백로그행 `DELETE` 1회 / 다른 스프린트행 `DELETE` → `POST` 2회
- 1건이라도 실패하면 `complete` **미발사**. 성공 행 「이관됨」 잠금 · 실패 행 「이관 실패」 · `role="alert"` 요약 · `다시 시도`
- 미완료 0건이면 목록·Select 없이 「옮길 이슈가 없습니다.」 + `complete` 1회. **단 `truncated` 면 E15 가 이긴다** — 목록이 불완전하면 「0건」이라는 관측 자체를 믿을 수 없다 (C-8)
- **`truncated === true` 면 제출 차단** (Maxi 재확정 2026-08-05). 상수 `BLOCK_COMPLETE_WHEN_TRUNCATED` 로 분기
- **이관 진행 중에는 Esc·오버레이로 닫히지 않는다** (E19). 중간에 끊기면 어디까지 갔는지 알 수 없다
- **이관 UI 를 `canReorderIssue` 로도 게이팅한다** (C-15). 이관은 `POST /{id}/issues`·`DELETE` 둘 다 **UPDATE 권한**인데 `complete` 만 CREATE 다. CREATE 만 있는 사용자는 전건 403 을 받으므로 403 문구를 실패 처리에 포함한다
- **`complete` 직전에 백로그를 재조회해 미완료가 0인지 확인한다** (C-7). 다이얼로그를 연 뒤 남이 이슈를 추가하면 목록에 없어 **영구 동결**되고, 남이 원본을 완료하면 `DELETE` 가 조용히 204 를 줘서 프론트가 전 행을 「이관됨」으로 **오판**한다 — C1 이 스스로 경고한 함정을 원안이 그대로 밟고 있었다

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
- depends-on: [3, 4, 6, 7, 8, 11]

**RED**. T-DL-1 + 기존 유닛 조치 (원안 「4건 갱신」을 실측으로 정정 — C-12).
- **시작 다이얼로그가 열린 동안 「보이는」 `스프린트 시작` 버튼이 정확히 1개** — FR-10 의 「Radix modal 이 바깥 트리거를 감춘다」는 **가정을 측정으로 바꾼다**
- **실제로 깨지는 것은 2건이다.** `:386`(시작 → `mockStartSprintMutate`) · `:398`(완료 동일) — 다이얼로그가 끼면 트리거 클릭만으로 mutate 가 안 나간다
- **`:410`·`:442` 는 안 깨진다.** 둘 다 `not.toHaveBeenCalled()` 라 그대로 통과한다. 갱신이 아니라 **강화**다 — 「다이얼로그가 열리지 않음」 단언을 **추가**한다
- **★ 원안이 안 센 치명 항목.** `BacklogBoard.test.tsx:98-201` 의 `vi.mock('@/hooks/use-backlog', () => ({...}))` **팩토리에 `useUpdateSprint` 를 추가하지 않으면 이 파일 41건이 통째로 red 다** (팩토리가 반환하지 않는 export 는 `undefined`)

**GREEN**.
- `useSensors` 에 `useSensor(KeyboardSensor, { coordinateGetter, keyboardCodes })` 추가 — **T11 이 만든 좌표 계산기와 키 코드 제한을 연결한다**. `PointerSensor` 의 `distance: 5` 는 **그대로**(카드 안 `Link` 클릭 보존이 걸려 있다)
- `accessibility={undefined}` → `accessibility={{ announcements, screenReaderInstructions }}`. **`buildBacklogAnnouncements(view, canReorderIssue)` 로 권한을 넘긴다** (FR-17)
- 시작/완료 버튼이 곧장 `mutate` 하던 것을 다이얼로그 open 으로 교체. **버튼 이름은 불변**
- 다이얼로그 2종을 **`key={대상 sprintId}` 로 화면당 1개씩** 마운트
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
- **★ S1 도 갱신 대상이다 (T6 실측 · 정본에 없던 항목 — 스펙 I-9).** 세로 전환 후 실제로 red 인 것은
  **S1·S2·S3 셋**이다. 브라우저 좌표로 원인을 못박았다 — `viewportH 720` 에서 스프린트 1 은
  `top=415`(화면 안)인데 **백로그는 `top=822`(화면 밖)** 이다. 백로그가 안 끼는 S5 는 통과한다
- **S2·S3 (처방 보강 — C-6).** `dragCardToColumn`(`:204-231`)은 출발·도착 `boundingBox()` 를 **먼저 둘 다 계산한 뒤** 마우스를 움직인다. 원안의 「드래그 전 `scrollIntoViewIfNeeded()`」 **한 줄로는 안 풀린다** — 대상만 스크롤하면 이번엔 출발 카드가 뷰포트를 벗어나 `mouse.down()` 이 안 닿는다. 세로 스택 + `ShellLayout` 의 단일 스크롤 컨테이너에서는 **출발·도착이 동시에 안 보이는 것이 기본값**이다. 처방 — ① 대상 섹션 외를 **접어 높이를 줄이거나** ② 스크롤 후 **좌표를 재계산**하고 ③ **출발·도착 동시 가시성을 단언**한다
- 나머지 8건(S1·S5·S6·초기렌더·S9~S12)은 **통과해야 한다** — 깨지면 그 자체가 회귀 신호. ★T1 이 픽스처에 스프린트 2개를 추가하므로 **개수를 세는 단언이 있으면 함께 갱신**한다

**REFACTOR**. 신규 시나리오의 셀렉터를 `backlogLabels` **import 로** 참조 (하드코딩 금지 — 2026-05-26 i18n 드리프트 교훈).

**검증**.
- `pnpm playwright test e2e/backlog.spec.ts`
- **백로그 라우트 참조 e2e 10파일 전량 동반 실행** — `backlog` · `timeline` · `timeline-zoom` · `sprint-burndown` · `project-velocity` · `project-cfd` · `project-cycle-time` · `project-tree` · `board-reorder` · `issue-create-entry-points`. **파일 목록과 결과를 PR 본문에 적는다**
- 눈확인. 위 Task 6~9 의 8항목을 최종 통합 확인

---

---

### Task 11. 키보드 좌표 계산기 + 활성화 키 제한 (리뷰 BLOCKER — 신규)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/lib/backlog-keyboard-coordinates.ts`, `apps/web/src/lib/backlog-keyboard-coordinates.test.ts`]
- depends-on: []

**왜 생겼나.** 두 리뷰가 겹쳐 지적했고 내가 실측 확인했다 —
`@dnd-kit/core@6.3.1` 의 기본 좌표 계산기는 방향키 1회에 **25px** 만 움직이고
(`core.esm.js:1111` `x + 25` · `:1121` `y + 25`), `coordinateGetter` 사용은 코드베이스 전체에
**0건**이다. 세로 스택에서 옆 섹션까지는 800px 이 넘으므로 **S8·S19 가 물리적으로 성립하지
않는다.** Maxi 가 「좌표 계산기를 직접 만든다」로 확정했다 (2026-08-05).

**RED**. T-KB-3 · T-KB-4 + 좌표 계산 단언.
- `↓` 1회 → **다음 카드 rect 중심**. 섹션 마지막 카드에서 `↓` → **다음 섹션 첫 카드** (1회로 경계를 넘는다)
- `←`/`→` → 현재 좌표 그대로 (세로 스택엔 가로 이웃이 없다)
- **카드가 0건인 섹션**도 후보에 들어온다 — 빈 스프린트로 못 옮기면 기능이 반쪽이다
- **접힌 섹션은 후보에서 제외**된다 (rect 가 없다)
- 후보 순서 = **화면에 그려진 순서**(스프린트들 → 백로그). `BacklogView` 배열 그대로, 클라이언트 정렬 없음
- **T-KB-3.** 집자마자 놓으면(이동 0) mutation 호출 수가 **0** 이다.
  ★근거. `BacklogCard.tsx:162` 가 `useDroppable({ disabled: isDragging })` 라 **자기 자신이 후보에서 빠지고**, 카드 간격이 `gap-2`(8px)라 translate 0 에서는 어느 카드와도 안 겹친다 → 칸 droppable 폴백 → `extractColumnDropZone` 이 `dropIndex = orderedKeys.length` 를 줘서(`lib/backlog-drag.ts:51`) **카드가 맨 뒤로 날아간다**. 마우스는 5px 임계 때문에 이 경로가 없다
- **T-KB-4.** 카드 안 링크에 포커스한 채 **Enter** → 드래그가 **시작되지 않는다**

**GREEN**.
- `backlogCoordinateGetter: KeyboardCoordinateGetter` — 카드/섹션 rect 기반 점프
- 드래그 시작 좌표를 **집은 카드 자신의 rect 중심**으로 초기화해 이동 0 이 noop 으로 판정되게 한다
- `keyboardCodes` — **`start: [Space]`**(Enter 제외) · `cancel: [Esc]` · **`end: [Space, Esc]`**(Tab 제외)
  ★근거. 기본값은 `start` 에 Enter, `end` 에 Tab 이 들어 있다(`core.esm.js:1099,1101`). 그런데
  `BacklogCard.tsx:152` 가 `setActivatorNodeRef` 를 **구조 분해하지 않아** `activatorNode.current === null`
  이고, 그러면 dnd-kit 의 조기 반환 가드(`:1343-1370`)가 **통과**한다. 결과로 카드 안
  `<Link>`(`:193`)에 포커스한 채 Enter 를 누르면 `preventDefault()` 가 걸려 **이슈로 못 간다** —
  키보드 사용자가 백로그에서 이슈를 여는 유일한 경로가 막힌다

**REFACTOR**. 보드 화면(`KanbanBoard.tsx:526`)도 **같은 선재 결함**을 갖지만
**이 PR 에서 건드리지 않는다** — 한 PR = 한 관심사. 별도 후속으로 남긴다는 주석을 남긴다.

**검증**.
- `pnpm vitest run src/lib/backlog-keyboard-coordinates.test.ts`
- **뮤테이션 검증**. 좌표 계산기를 기본값으로 되돌리면 「1회로 섹션 경계를 넘는다」가 red
- 동반 E2E. T10 의 S19 가 실동작을 잰다
- 눈확인. T9 에서 통합 (Tab → Space → ↓ → Space + VoiceOver)

---

## Plan 메타

- **task 수**. **11** (원안 10 + 리뷰가 적발한 키보드 좌표 계산기)
- **wave 예상**. 4 — `W1{T1,T2,T3,T5,T11}` → `W2{T4,T6,T7,T8}` → `W3{T9}` → `W4{T10}`
- **파일 충돌**. 없음. 검증 방법 — 각 task `files` 의 교집합이 공집합이고, `BacklogBoard.tsx`(T6·T9)는 `depends-on` 으로 직렬화된다.
  ★원안은 **공유 파일 3개가 미배정**이었다(`backlog-labels.ts`·`use-backlog-drag.ts`·`backlog-fixtures.ts`) — 전부 **T1 이 소유**하도록 고쳤다. **T7·T8 은 `backlog-labels.ts` 를 열지 않는다.**
- **구현 규율**. ui 시각 검증 트랙 (red-first 면제) + **T2·T3·T4·T5·T11 은 순수 로직이라 red-first 준수**
- **뮤테이션 검증 5건 필수**. T-KB-2(키보드 충돌 분기 삭제) · **T-KB-3(시작 좌표 초기화 삭제)** · T-DL-3(문구 뒤집기) · T-CP-3(부분 실패에도 complete) · **T-CP-1(픽스처에서 COMPLETED 제거 시 red 여야 한다 — 짝 단언 검증)**
  — **GREEN 커밋 후에** 뮤테이션하고 `git checkout --` 로 원복 (미커밋 상태면 작업이 날아간다)
- **추가 검증**. `pnpm typecheck` · `pnpm lint` · `pnpm vitest run` 전량 · `pnpm playwright test`(10파일) · `bash scripts/verify-master-plan.sh`
- **착수 최우선 2건**. ① T2 의 U2(`GET /api/v1/workflows` 카테고리 충돌 실측)
  ② T8 의 비가시 이슈 경로 — `BacklogApplicationService` 가 `keys.mapNotNull { issueByKey[it] }` 로
  비가시 이슈를 조용히 떨어뜨리는데 `truncated` 는 서지 않는다. 활성이면 `truncated` 가드를
  우회하는 **두 번째 영구 동결 경로**다
- **정본 동기화 (이 PR 범위)**. `docs/plan/product/personalization.md` §4.11 에
  **「B3 — 백로그 조회 범위 축소(백엔드)」** 후속 항목 신설 (Maxi 확정). `truncated` 를 실제로
  내릴 수 있는 유일한 경로이고, 그전까지 cap 초과 프로젝트는 스프린트를 완료할 수 없다

## 리뷰 결과

### plan-design-review (2026-08-05) — 독립 리뷰 2종 병렬

`type=ui` 체인. gstack 범용 스킬의 목업 생성·Codex 경로는 **의도적으로 생략**했다 —
기존 화면 수정이고 스펙이 이미 시각 사양(상태 매트릭스 7종 · 반응형 4종 · 토큰 **0 신설**)을
확정했다. 대신 이 스킬의 실질 가치인 **독립 리뷰**를 두 시각으로 병렬 실행했다.
「겹친 지적이 진짜 결함」 원칙으로 판정했다.

**결과. BLOCKER 9 · MAJOR 20 · MINOR 14.** 전량 반영했다.

#### 두 리뷰가 겹친 지적 (= 확정 결함, 5건)

| # | 결함 | 반영 |
|---|---|---|
| 1 | `backlog-labels.ts` 가 T7·T8 **같은 wave 공유 자원** — T1 라벨 목록이 최소 12개 부족 | T1 을 **전수로 확장** + 「T7·T8 은 이 파일을 열지 않는다」 명시 |
| 2 | 키보드 DnD **물리적 불가** (방향키 25px · `coordinateGetter` 0건) | **Task 11 신설** (Maxi 확정) + 스펙 FR-15 |
| 3 | `COMPLETED` 픽스처 **0개** → T-CP-1·S16 가짜 그린 | T1 이 픽스처 보강 + T-CP-1 을 **짝 단언**으로 (스펙 FR-18) |
| 4 | 권한 없는 사용자에게 **거짓 공지** | `buildBacklogAnnouncements(view, canReorderIssue)` (스펙 FR-17) |
| 5 | 엣지 **19건인데 15로 셈** · E10·E19 무배정 · FR-4 는 실제 **4갈래** | 엣지 전수 배정 + FR-4 표 정정 |

#### 단독 지적이지만 내가 실측 확인한 것 (4건)

| # | 결함 | 실측 |
|---|---|---|
| 6 | `truncated` 탈출구 **영구 부재** — F16 은 플래그를 못 내린다 | `getBacklog(@PathVariable projectKey)` **파라미터 0** · F16 정본 "프론트 전용" |
| 7 | `SCANNED_FILES` 하드코딩 → 신규 다이얼로그 **미스캔** | `button-primitive-usage.test.ts:80` · backlog 항목 `:47-48` 2개뿐 |
| 8 | `KeyboardSensor` 가 카드 안 링크의 **Enter 를 삼킨다** | `setActivatorNodeRef` 미구조분해(`BacklogCard.tsx:152`) → 가드 통과 · `<Link>` `:193` |
| 9 | `vi.mock` 팩토리 미확장 시 **41건 통째 red** | `BacklogBoard.test.tsx:98-201` |

#### 🛑 Maxi 재결재 2건 (2026-08-05)

- **U1 재결재.** 내가 앞서 「F16 전까지 완료 불가」라고 설명했으나 **근거가 거짓**이었다 —
  실측상 **영구 차단**이다. 정정된 근거 위에서 **차단 유지**를 재확정했고, 대신
  **백엔드 후속 항목(B3)을 정본에 등록**한다.
- **키보드 DnD.** **좌표 계산기를 직접 만든다** → Task 11.

#### 리뷰가 「plan 이 맞다」고 확인해준 것

`PATCH` 응답이 `version+1` 포함 최신 메타를 준다(T-DL-4 성립) · `canReorderIssue` 가드가
키보드 경로도 막는다 · `pointerCoordinates` 게이팅이면 마우스 충돌 감지 **바이트 단위 무변경** ·
개수 리터럴(`backlog.spec.ts` 11 test / `BacklogBoard.test.tsx` 41 it) 일치 ·
G7 정정(「공지가 꺼진 게 아니라 영어 기본값」)이 사실 · `size="icon-xs"` 실재 ·
`CreateIssueEntryButton` variant=icon 의 `aria-label` 전략이 `^` 앵커 보존에 유효.

#### 남은 미확인 2건 (착수 최우선)

1. **U2** — 같은 `stateKey` 가 워크플로우마다 다른 category 를 갖는 실데이터 (T2)
2. **비가시 이슈 경로** — `truncated` 가드를 우회하는 두 번째 영구 동결 경로일 수 있다 (T8)

**BLOCKER 잔여. 없음** (9건 전량 스펙·plan 에 반영 완료).
