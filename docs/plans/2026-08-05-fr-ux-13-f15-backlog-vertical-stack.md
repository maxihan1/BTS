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

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
