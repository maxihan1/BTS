# 보드 생성 종류 선택 UI — 스크럼/칸반 (FR-BD-04 D6 · 로드맵 PR ②)

> 티어: T2
> slug: board-type-create-ui
> type: ui
> agent: frontend-engineer
> 생성: 2026-09-01

## Brief

**사용자 원문.** `fr-bd-04 d6 진행 해주고 pr 418 홀드 되어 있는데 이것도 같이 정리 해줘`
(PR 418 정리는 별건으로 선행 완료 — 커밋 `73753735c`, 홀드 사유를 「보드 모델 설계 대기」에서
「FR-BD-04 D6 선행 대기」로 갱신하고 plan 실측 6건을 정정했다.)

**이 PR 이 하는 것.** ADR 3분할의 **PR ②** — 「새 보드」 흐름에 **1단계 종류 선택**(스크럼/칸반)을
더한다. 설계 정본은 [ADR 2026-09-01 board-type-and-active-sprint](../adr/2026-09-01-board-type-and-active-sprint.md) **D4**.

**왜 지금인가.** PR #421(`cfa7c920c`)이 D1~D5(스키마·백엔드)를 넣었지만 `apps/web` 은 **0파일**이라
사용자에게 보이는 변화가 0이다. `POST /api/v1/boards` 가 `boardType` 을 받고
`GET /api/v1/boards/{id}` 가 `boardType`·`activeSprint` 를 내주는데 **화면이 보내지도 읽지도 않는다.**
②가 오지 않으면 스키마만 남는다(#421 CEO 렌즈 C1, 수용됨).

**classify 결과.** `type=ui` · `agent=frontend-engineer` · `primary_bc=agile-planning` ·
`tier=T1`(판별식 실측) — **선언은 T2**.

**FR.** `FR-BD-04` **D6 일부**. 신규 FR 없음 → **총수 144 불변**.
D6 은 세 조각(종류 선택 · 스크럼 보드 화면 · 백로그 `?board=` 스코프)이고 이 PR 은 **첫 조각만**
닫는다. **D6 체크박스는 `[ ]` 로 남고 진척 표기도 안 바뀐다** — PR ③ 이 닫는다.

**마이그레이션 0 · 신규 의존성 0 · 신규 프리미티브 0.**

### 티어 — 선언 T2 · 실측 T1

ADR 이 PR ② 를 **T2** 로 지정했다(ADR §「결정된 것」 3분할 표). 실측 표면은 `apps/web/src` 뿐이라
`classify-task.ts` 도 **T1** 을 돌려준다 — **백엔드 0줄**이다. `boardType` 을 받고 되싣는 계약이
#421 에 이미 다 있다.

**지정 티어가 우선**이므로(`/bts` 판정 5문 ②) T2 절차로 간다 — 정식 TDD red-first · plan 1파일 ·
리뷰 2종 · 게이트 1+2. **선언 T2 · 실측 T1 을 게이트 2 요약에 나란히 싣는다**(판정 5문 ⑤).

### 범위 밖 — 고려했고 미룬 것

| 항목 | 사유 |
|---|---|
| `BoardSummaryResponse` 에 `boardType` | 목록·스위처에 종류를 실을 **소비처가 아직 없다**. PR ③ 이 스크럼 보드 화면을 만들 때 필요해진다. A1 plan 이 `canDelete` 를 같은 논리로 미룬 선례 |
| `boardDetailSchema` 에 `boardType`·`activeSprint` | 소비처가 PR ③ 의 스크럼 보드 화면이다 |
| 스크럼 보드 화면 · 백로그 `?board=` 스코프 | **D6 잔여 = PR ③** |
| E2E | **D7 = PR ③** |
| 소스 선택(J4) · 생성 후 종류 변경 | ADR 의도적 편차 X1 · X3 |
| 칸반 백로그(kanplan) | ADR 의도적 편차 X4 |
| E-6(두 번째 스크럼 보드 빈 보드 고정) · 스크럼 × `truncated` | 「PR ③ 전에 닫는다」로 기한이 박힌 부채. 백로그·보드 조회를 건드릴 때 닿는다 — 이 PR 은 생성 플로우만 만진다 |

## Jira 대조 (전 타입 필수)

`jira-parity-contract.md` §1 5단계 산출물. **§1-0 재사용** — ADR
`docs/adr/2026-09-01-board-type-and-active-sprint.md` §「Jira Cloud 실물 조회」가 **2026-09-01 에
조회한 J1~J13 을 출처·조회일 그대로 승계**한다(ADR 「다음 한 걸음」이 이 재사용을 명시했다).
전 행 **Cloud company-managed** 기준이다.

| # | 원문 인용 | 출처 | 조회일 |
|---|---|---|---|
| **J1** | 보드 생성 모달에서 **"Create a Scrum board"** 또는 **"Create a Kanban board"** 를 고른다 | [create-a-board](https://support.atlassian.com/jira-software-cloud/docs/create-a-board/) | 2026-09-01 |
| **J2** | 사이드바 경로 — *"hover over a company-managed space in the sidebar until a plus icon appears, select **Create board**, choose **Scrum**, enter a board name, select what to include from a dropdown, then select **Create**"* | 〃 | 2026-09-01 |
| **J3** | 전역 경로 — *"select the search field ... **Go to all: Boards**, then select **Create board** in the upper right"* | 〃 | 2026-09-01 |
| **J4** | 소스 선택 — *"select whether to base your board on a new software space or one or more existing spaces"* | 〃 | 2026-09-01 |
| **J6** | *"Active sprints are only available on Scrum boards."* — 종류가 화면 의미를 가른다 | [use-active-sprints](https://support.atlassian.com/jira-software-cloud/docs/use-active-sprints/) | 2026-09-01 |

### 채택

**J1·J2 의 순서를 그대로 따른다 — 종류를 먼저 묻고, 그 다음 이름.** ADR §D4 가 확정한 흐름이다.

```
「새 보드」
   ├─ 1단계. 종류 선택        ← J1 (모달 1단계)
   │     ○ 스크럼 보드  — 스프린트로 일하는 팀
   │     ○ 칸반 보드    — 흐름으로 일하는 팀
   ├─ 2단계. 이름 입력        ← J2 "enter a board name"
   └─ [보드 만들기] → 생성된 보드로 이동
```

### 편차

| # | 항목 | 결정 | 사유 |
|---|---|---|---|
| **X1** | 소스 선택(J4) — *"base your board on a new space or existing spaces"* | **미채택** | ADR 의도적 편차 X1. BTS 보드는 `project_key` 문자열에 고정돼 있어 다중 스페이스 개념이 없다. 저장 필터 기반 보드(J13)는 패리티 포기 후보로 A1 plan 이 이미 등재했다 |
| **X2** | 진입점(J2 사이드바 · J3 전역) | **보드 스위처 드롭다운 하나** | ADR 의도적 편차 X2. BTS 는 프로젝트 컨텍스트 안에서만 보드를 만든다 — 전역 「Go to all: Boards」에 대응하는 화면이 없다 |
| **X3** | 생성 후 종류 변경 | **없음** | ADR 의도적 편차 X3. 종류가 바뀌면 카드 집합의 의미가 통째로 바뀐다 |
| **X4** | 기본 선택 | **칸반** | 백엔드가 `boardType` 을 **선택 인자**로 두고 생략 시 KANBAN 이다(#421). 기본을 칸반으로 두면 기존 사용자의 「이름 쓰고 만들기」 흐름이 클릭 1회만 늘고 결과가 같다. Jira 는 기본 선택을 명시하지 않는다 |

## 도메인 정리

**BC.** `agile-planning` 단독. 다른 BC 를 부르지 않는다 — 프론트만 바뀌고 호출하는 API 도
agile-planning 소유 `POST /api/v1/boards` 하나다.

**영향 엔티티.** `Board`(기존) — `boardType: BoardType` 필드는 **#421 이 이미 넣었다**.
이 PR 은 엔티티를 건드리지 않는다.

**새 용어 없음.** 「스크럼 보드」·「칸반 보드」는 ADR 이 이미 도입했고 `glossary.md` 는
보드/스프린트 헤딩을 갖고 있지 않다 — **이 PR 에서 glossary 를 갱신하지 않는다**(용어 도입 PR 이
아니다. 필요하면 PR ③ 이 화면 의미와 함께 등재하는 것이 맞다).

**관련 ADR.** `docs/adr/2026-09-01-board-type-and-active-sprint.md` (채택 · #419) — **§D4 가 이 PR 의 정본**.
`docs/decisions/` 에는 보드 종류 관련 ADR 이 없다(grep 0건).

**기존 결정 충돌 없음.** ADR 이 무효화한 것은 `FR-BL-02` Deviation(PR #182) ⑤ 「동시 ACTIVE 다중 허용」
하나이고 그것은 **#421 이 이미 처리**했다(가드는 `start` 시점 · 기존 다중 활성 행은 안 깬다).
이 PR 은 생성 플로우만 만져 그 결정과 접점이 없다.

## 스펙

> **§5 경량 경로.** `type=ui` 가 T2 로 승격된 작업이라 `bts-spec` §5 에 따라
> `## Jira 대조`(위) + `## 엣지 케이스` + `## 측정 가능한 완료 기준` 3섹션으로 줄인다.
> **시각 검증 기준은 필수 기재**이므로 아래 별도 절에 둔다.

### 사전 grep 실측 (계약 §5 · 착수 시점 2026-09-01)

**「유닛 전부 초록인데 e2e 만 빨강」을 이번에 실제로 만들 수 있는 자리를 먼저 쟀다.**

| 대상 | 명령 | 결과 |
|---|---|---|
| E2E 계약 문자열 | `grep -rn "보드 만들기\|새 보드\|보드 이름" apps/web/e2e/` | **`board-manage.spec.ts` 3곳** — `:113` `:114` `:161` |
| 유닛 어서션 | `grep -rln "createBoard\|CreateBoardForm" apps/web/src --include="*.test.*"` | **4파일** — `CreateBoardForm.test.tsx` · `use-boards.test.tsx` · `api/boards.test.ts` · `routes/__tests__/projects.board.test.tsx` |
| 소비처 | `grep -rn "CreateBoardForm" apps/web/src` | **2곳** — `projects.$projectKey.board.tsx:305`(다이얼로그) · `:844`(빈 상태) |

### 🛑 즉사 계약 — 이 PR 이 반드시 함께 고치는 것

**`board-manage.spec.ts` 는 종류 선택 단계를 모른다. 넣는 순간 red 다.**

```ts
// :113-114  S1. 두 번째 보드 생성 — 다이얼로그를 열자마자 이름을 채우고 제출한다
await createDialog.getByLabel('보드 이름').fill(SECOND_BOARD_NAME)
await createDialog.getByRole('button', { name: '보드 만들기', exact: true }).click()

// :161      S5. 마지막 보드 삭제 → 빈 상태 — 「보드 만들기」 버튼 가시성을 단언한다
await expect(page.getByRole('button', { name: '보드 만들기', exact: true })).toBeVisible()
```

1단계에는 **이름 필드도 「보드 만들기」 버튼도 없다**. 두 시나리오 모두 종류 선택을 거치도록
같은 PR 에서 갱신한다. **후속 PR 로 미루지 않는다** — 「UI PR 이 E2E 를 후속으로 미루면 기존 E2E
회귀가 머지 시점에 잠복」이 이 저장소에서 이미 일어난 양식이다(learnings 2026-05-31).

**D7(신규 E2E)은 여전히 PR ③ 소관이다.** 이 PR 이 지는 것은 **기존 스펙 회귀 수정**뿐이다.

### ⚠️ mock 이 삼킬 자리

`routes/__tests__/projects.board.test.tsx:104` 가 `CreateBoardForm` 을 **mock 한다.**
종류 선택을 넣어도 그 파일은 **초록 그대로**다 — 이 mock 은 `showEmptyStateIntro`·`onCreated` 만
DOM 에 노출한다. 판정의 실체는 `CreateBoardForm.test.tsx`(실물 렌더)와 E2E 두 곳이고,
**라우트 테스트의 초록을 근거로 삼지 않는다**(memory `mock-swallowed-prop-is-invisible-to-unit-tests`).

### 엣지 케이스

| # | 상황 | 기대 |
|---|---|---|
| **E1** | 1단계에서 아무것도 안 고르고 「다음」 | 기본 선택이 **칸반**이라 도달 불가. 라디오는 항상 하나가 선택돼 있다(X4) |
| **E2** | 2단계에서 이름이 공백 | 기존 동작 유지 — 제출이 no-op(`trimmed === ''` early return). **새 에러 문구를 만들지 않는다** |
| **E3** | 2단계에서 「뒤로」 → 종류를 바꿈 → 다시 「다음」 | **입력한 이름이 보존**된다. 종류만 갈아끼운다 — 이름을 지우면 사용자가 두 번 친다 |
| **E4** | 422 `AGILE_UNPROCESSABLE`(워크플로우 스킴 미할당) | 기존 안내 문구·자리 그대로. **2단계에 표시**한다(제출한 화면에 붙어 있어야 한다) |
| **E5** | 제출 중(`isPending`) 「뒤로」 | 단계 이동을 막는다 — 진행 중 요청의 종류와 화면의 종류가 갈리면 안 된다 |
| **E6** | 다이얼로그를 닫았다가 다시 연다 | **1단계부터 시작**한다. 이전 선택이 남으면 「종류를 먼저 묻는다」는 계약이 화면에서 사라진다 |
| **E7** | 빈 상태(보드 0개) 진입 | 다이얼로그와 **같은 2단계**를 탄다. 소비처가 같은 컴포넌트라 분기하지 않는다 |
| **E8** | 스크럼으로 만든 직후 이동한 보드 | 카드가 **0건**일 수 있다 — 활성 스프린트가 없으면 그렇다(#421 계약). 이 PR 은 **빈 화면을 설명하지 않는다**(PR ③ 의 빈 상태 소관). 게이트 2 에 남길 알려진 한계 |

### 측정 가능한 완료 기준

1. 「새 보드」 다이얼로그와 빈 상태 **양쪽**에서 종류 선택 → 이름 → 생성이 된다.
2. 스크럼을 고르면 `POST /api/v1/boards` 요청 바디에 `boardType: "SCRUM"` 이 실린다.
   칸반이면 `"KANBAN"` 이다 — **생략하지 않는다**(백엔드 기본값에 기대면 화면의 선택이 무의미해진다).
3. `boardCreatedSchema` 가 응답의 `boardType` 을 **필수**로 파싱한다.
4. `board-manage.spec.ts` S1·S5 가 갱신된 흐름으로 **초록**이다.
5. `pnpm --filter web test` · `pnpm verify` 초록. `pnpm test:workflow` 478/478 유지.
6. **신규 프리미티브 0 · 신규 의존성 0 · 백엔드 0줄 · 마이그레이션 0.**

### 시각 검증 기준 (§5 필수)

**관련 E2E** — `board-manage.spec.ts`(S1·S5 갱신 대상) · `board-kanban.spec.ts`(회귀 확인).

**눈확인 항목** (계약 §6 · 생략 불가)

1. 다이얼로그 1단계 — 라디오 2개가 **설명 문구와 함께** 보이고 칸반이 기본 선택.
2. 「다음」 → 2단계에 이름 입력. **「뒤로」로 돌아와도 이름이 남는다**(E3).
3. 빈 상태에서도 같은 2단계 — 「보드가 없습니다」 인트로는 **빈 상태에만** 뜬다(C1 계약 유지).
4. 스크럼으로 생성 → 네트워크 탭에서 요청 `boardType: "SCRUM"` · 응답 `boardType: "SCRUM"`.

## Sanity Check

**❓ 발견 2건 — 스스로 보강했다.**

1. **「1단계/2단계」를 wizard 로 볼지 한 폼의 순서로 볼지가 ADR 만으로는 안 갈렸다.**
   ADR §D4 다이어그램은 단계로 그렸고 J1·J2 도 Jira 모달의 두 화면이다 → **wizard(step state)로 확정**.
   대안이던 「한 화면에 라디오 + 이름」은 기존 E2E 를 안 깨는 이점이 있으나 **J1·J2 의 순서를
   버린다** — 패리티 계약이 요구하는 것은 편의가 아니라 Jira 실물의 순서다.
   그 대가로 E2E 2곳을 이 PR 이 갚는다(위 즉사 계약 절).
   **이 트레이드오프는 게이트 1 보고 항목이다.**
2. **「보드 만들기」 문자열의 소유자가 둘이 됐다.** `board-labels.ts:68` 이 이미
   *"🛑 「보드 만들기」로 쓰지 마라 — `CreateBoardForm` 제출 버튼이 그 문자열"* 이라고 경고한다.
   2단계 제출 버튼이 그 문자열을 **계속 가져간다** — 1단계의 진행 버튼은 「다음」이고
   「보드 만들기」를 재사용하지 않는다. E2E `:114` `:161` 의 `exact: true` 단언이 그대로 산다.

**Maxi 결정 필요 — 없음.** 위 1번은 ADR 이 이미 정한 방향이라 보고 항목이지 질문이 아니다.

## Plan

### Task 1. API 계약 — `boardType` 을 보내고 되읽는다

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/api/boards.ts`, `apps/web/src/api/boards.test.ts`]
- depends-on: []
- jira: [J1]

**RED**:
- 파일: `apps/web/src/api/boards.test.ts`
- 테스트 2개.
  ① `createBoard(projectKey, name, 'SCRUM')` 이 요청 바디에 `boardType: 'SCRUM'` 을 싣는다.
  ② `boardCreatedSchema` 가 `boardType` 없는 응답을 **거부**한다.
- 실패 메시지(예상). ① `boardType` 이 바디에 없음 ② 스키마가 미지 키를 버리고 통과

**GREEN**:
- `boardTypeSchema = z.enum(['SCRUM', 'KANBAN'])` + `export type BoardType = z.infer<...>`
- `boardCreatedSchema` 에 `boardType: boardTypeSchema` **필수** 추가
- `createBoard(projectKey, name, boardType)` — 바디에 항상 싣는다.
  ★ **선택 인자로 두지 않는다.** 백엔드가 생략 시 KANBAN 으로 채우므로(`BoardResponses.kt:46`)
  옵셔널로 두면 화면의 선택이 조용히 무시돼도 아무도 모른다(완료 기준 2).

**REFACTOR**: `boardTypeSchema` 에 「백엔드 `BoardType` enum 대응」 주석 1줄.

**검증**: `pnpm --filter web test -- api/boards.test.ts`
- ★ **필수 필드 추가는 산재한 인라인 mock 을 깬다**(learnings 2026-05-30). 이 task 의 red 는
  자기 파일뿐 아니라 **다른 파일에서도** 난다 — 전수로 받아 Task 5 가 고친다.

---

### Task 2. 훅 입력에 종류를 싣는다

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/hooks/use-boards.ts`, `apps/web/src/hooks/use-boards.test.tsx`]
- depends-on: [1]
- jira: [J1]

**RED**:
- 파일: `apps/web/src/hooks/use-boards.test.tsx`
- 테스트. `useCreateBoard` 를 `{ name, boardType: 'SCRUM' }` 으로 부르면 `createBoard` 가
  **세 번째 인자로 `'SCRUM'`** 을 받는다.
- 실패 메시지(예상). `CreateBoardInput` 에 `boardType` 없음 (타입 에러)

**GREEN**:
- `CreateBoardInput { name: string; boardType: BoardType }` — **선택 필드가 아니다**(Task 1 과 같은 이유)
- `mutationFn: ({ name, boardType }) => createBoard(projectKey, name, boardType)`

**REFACTOR**: `CreateBoardInput.boardType` TSDoc — 「생략 불가. 화면의 선택이 곧 계약이다」

**검증**: `pnpm --filter web test -- use-boards.test.tsx`

---

### Task 3. 종류 선택 문구를 i18n 에 둔다

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/i18n/board-labels.ts`]
- depends-on: []
- jira: [J1]

**RED**(동반 테스트): 이 task 는 상수만 추가한다. 판정은 Task 4 의 렌더 테스트가 진다 —
문구를 하드코딩하면 Task 4 가 `boardLabels` 를 참조하는 단언에서 red 다.

**GREEN**:
- `boardLabels.createForm` 하위에 종류 선택 문구를 둔다.
  ADR §D4 원문을 그대로 쓴다 — 「스크럼 보드 — 스프린트로 일하는 팀」·「칸반 보드 — 흐름으로 일하는 팀」
- 1단계 진행 버튼 「다음」 · 2단계 복귀 버튼 「뒤로」 · 종류 선택 그룹 레이블 「보드 종류」
- 🛑 **「보드 만들기」를 재사용하지 않는다** — `board-labels.ts:68` 이 이미 경고한 자리다.
  그 문자열은 **2단계 제출 버튼 전용**으로 남는다(E2E `:114` `:161` 의 `exact: true` 단언이 산다).

**REFACTOR**: 각 키에 「ADR §D4 · J1 원문」 출처 주석.

**검증**: `pnpm --filter web typecheck` + Task 4 의 렌더 단언

---

### Task 4. `CreateBoardForm` 2단계 wizard

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/board/CreateBoardForm.tsx`, `apps/web/src/components/board/CreateBoardForm.test.tsx`]
- depends-on: [2, 3]
- jira: [J1, J2]

**RED**(동반 테스트): `CreateBoardForm.test.tsx` 에 시나리오 6개.
- ① 최초 렌더는 **1단계** — 라디오 2개가 보이고 이름 입력이 **안 보인다**
- ② 기본 선택이 **칸반**(X4)
- ③ 스크럼 선택 → 「다음」 → 이름 입력 → 제출 시 mutate 가 `{ name, boardType: 'SCRUM' }` 을 받는다
- ④ **E3** — 이름을 치고 「뒤로」 → 종류를 바꾸고 「다음」 → **이름이 남아 있다**
- ⑤ **E5** — `isPending` 중에는 「뒤로」가 비활성
- ⑥ **E4** — 422 `AGILE_UNPROCESSABLE` 안내가 **2단계에** 뜬다
- 실패 메시지(예상). 라디오가 없어 ①②③ 전부 red

**GREEN**:
- `useState<'type' | 'name'>('type')` 한 축으로 단계를 가른다. **새 컴포넌트를 만들지 않는다** —
  이 폼은 빈 상태(`:844`)와 다이얼로그(`:305`) 둘이 공유하므로 분기하면 소비처가 갈린다(E7).
- 종류 선택은 `components/ui/radio-group.tsx` **재사용**(선례 `AddAccountDialog.tsx` ·
  `GlobalPermissionFormDialog.tsx`). **신규 프리미티브 0.**
- `showEmptyStateIntro` 인트로는 **1단계에만** 붙인다 — 「보드가 없습니다」가 이름 입력 단계까지
  따라다니면 C1 계약의 의미가 흐려진다.
- **E6** — 소비처가 언마운트/재마운트하므로 `useState` 초기값이 곧 1단계 복귀다. 별도 리셋 불필요.
  ★ 다이얼로그가 **언마운트되지 않는 구조면** 이것이 성립하지 않는다 —
  `projects.$projectKey.board.tsx:294-310` 을 읽어 확인하고, 유지형이면 `key` 를 물려 강제한다.

**REFACTOR**: 단계 전환 핸들러 2개를 컴포넌트 상단으로 모으고 TSDoc 에 J1·J2 인용.

**검증**:
- `pnpm --filter web test -- CreateBoardForm.test.tsx`
- 기존 E2E: `apps/web/e2e/board-manage.spec.ts` · `board-kanban.spec.ts` (계약 §5 사전 grep 결과)
- 눈확인: 1단계 라디오 2개 + 설명 문구 · 2단계 이름 입력 · 「뒤로」 이름 보존 — 라이트/다크 양쪽

---

### Task 5. MSW 핸들러·픽스처가 종류를 안다

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/mocks/board-handlers.ts`, `apps/web/src/mocks/board-fixtures.ts`, `apps/web/src/mocks/board-handlers.test.ts`]
- depends-on: [1]
- jira: []

**RED**:
- 파일: `apps/web/src/mocks/board-handlers.test.ts`
- 테스트. `POST /api/v1/boards` 에 `boardType: 'SCRUM'` 을 보내면 **응답이 그 값을 되싣는다**
  (지금은 body 를 `{projectKey?, name?}` 로만 읽어 종류가 증발한다 — `board-handlers.ts:330`)
- 실패 메시지(예상). 응답에 `boardType` 없음 → `boardCreatedSchema` 파싱 실패

**GREEN**:
- `createBoardHandler` 가 `boardType` 을 읽어 저장·반환. **미전송이면 `'KANBAN'`** (백엔드와 같은 기본값)
- `board-fixtures.ts` 의 `BoardCreated` 조립부(`:251`)와 `StoredBoardDetail` 에 `boardType` 추가
- ★ **Task 1 이 만든 전수 red 를 여기서 받는다** — 다른 파일의 인라인 mock 이 `boardType` 을
  빠뜨려 깨진 것을 전부 고친다. **한 곳이라도 남기면 그 파일이 이 PR 의 계약 밖으로 샌다.**

**REFACTOR**: 기본값 `'KANBAN'` 옆에 「백엔드 `BoardCreateRequest.boardType` 이 선택 인자라
같은 기본값을 둔다」 주석.

**검증**: `pnpm --filter web test` (전체 — 전수 red 소진 확인)

---

### Task 6. 즉사 계약 — `board-manage.spec.ts` S1·S5 갱신

**메타**.
- agent: `qa-engineer`
- files: [`apps/web/e2e/board-manage.spec.ts`]
- depends-on: [4, 5]
- jira: [J1, J2]

**RED**(동반 테스트): 이 task 는 **기존 스펙이 red 인 것을 green 으로 되돌린다**.
Task 4 가 머지되는 순간 `:113` `:114` `:161` 이 실패한다 — 1단계엔 이름 필드도
「보드 만들기」 버튼도 없다.

**GREEN**:
- **S1** — 다이얼로그를 연 뒤 **종류 선택 → 「다음」** 을 거쳐 이름을 채운다.
  ★ 이 기회에 **스크럼을 고른다** — S1 이 칸반만 만들면 종류 선택이 실화면에서 한 번도
  검증되지 않는다(#421 이 「모든 실제 생성 경로가 테스트 0건」으로 잡힌 것과 같은 양식).
- **S5** — 빈 상태 복귀 단언을 **1단계 기준**으로 바꾼다. 「보드가 없습니다」 인트로 단언은
  그대로 두고, 「보드 만들기」 버튼 가시성 단언은 **「다음」 버튼**으로 옮긴다.
- 🛑 **S2·S3·S4 를 건드리지 않는다.** S1~S5 는 한 test 연쇄라 앞 단계가 만든 보드를 뒤가 쓴다 —
  S1 이 만드는 보드 이름·개수를 바꾸면 그 뒤 전부가 흔들린다.

**REFACTOR**: 종류 선택 두 줄을 `selectBoardType(page, kind)` 헬퍼로 빼 S1 과 후속 PR ③ 이 공유.

**검증**:
- `pnpm --filter web test:e2e -- board-manage.spec.ts board-kanban.spec.ts`
- 눈확인: S1 흐름을 브라우저에서 1회 — 스크럼 생성 후 네트워크 탭 `boardType: "SCRUM"` 확인

---

## Plan 메타

- **task 수**: 6 · **예상 wave**: 4
  (w1 = T1·T3 병렬 / w2 = T2·T5 / w3 = T4 / w4 = T6)
- **구현 규율**: TDD red-first (T2) + **ui 시각 검증 트랙** — T4·T6 은 눈확인 필수
- **Jira 매핑**: `J1→T1·T2·T3·T4·T6` · `J2→T4·T6` · `J6→` **범위 밖**(화면 의미 분기는 PR ③ 소관 —
  이 PR 은 생성 플로우만 만진다) · `J3` **범위 밖**(전역 「Go to all: Boards」 대응 화면 부재 — 편차 X2) ·
  `J4` **범위 밖**(소스 선택 미채택 — 편차 X1). **채택 J1·J2 는 차집합 0.**
- **추가 검증**: `pnpm --filter web typecheck` · `pnpm verify` · `pnpm test:workflow`(478 유지) ·
  `node scripts/build-doc-index.mjs --check`
- **백엔드 0줄 · 마이그레이션 0 · 신규 의존성 0 · 신규 프리미티브 0**

## 리뷰 결과 (← /bts-review-plan 채움)
