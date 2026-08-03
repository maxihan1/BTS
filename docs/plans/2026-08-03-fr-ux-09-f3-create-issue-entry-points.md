# FR-UX-09 F3 — 보드/백로그/스프린트 컬럼 이슈 생성 진입점

> slug: fr-ux-09-f3-create-issue-entry-points
> type: ui
> agent: frontend-engineer
> primary_bc: agile-planning (프론트 전용 — `apps/web`)
> 생성: 2026-08-03

## Brief

**사용자 원문.** FR-UX-09 F3 — 보드/백로그/스프린트 컬럼 3곳(`BoardColumn` · `BacklogColumn` · `SprintColumn`)에
이슈 생성 모달(`CreateIssueDialog`) 진입점 연결. FR-UX-09 의 D6/D7 을 닫는 마지막 조각.

**classify 결과.** `type=ui` · `agent=frontend-engineer` · `primary_bc=agile-planning`.
자동 slug `fr-ux-09-f3-3` 은 「3곳」의 숫자가 잘려 붙은 무의미 토큰이라
형제 PR 명명(`ui/fr-ux-09-f2-create-issue-dialog`)에 맞춰 교체함.

**선행 컨텍스트 (PR #331, F2).**

- `CreateIssueDialog` 는 완성돼 있고 **라우터 훅 import 0** 이다 — F3 이 URL 변경 없이 열 수 있는 **설계 조건**이며 #331 에서 검증됨.
- 폼은 `components/issue/create/` 로 분해돼 있다 — `IssueCreateForm`(196) ·
  `IssueCreateBasicFields`(113) · `IssueCreateAssignmentFields`(46) · `IssueCreateExtraFields`(66) +
  `issue-create-schema.ts` · `use-assignee-picker.ts` · `use-issue-create-defaults.ts`.
- D-8 **성공 후 이동은 진입 경로가 정한다** — 딥링크(`/issues/new`)는 상세로 이동, 상단바는 제자리 + 토스트.
  **F3 의 3개 진입점이 어느 쪽인지는 스펙 단계에서 확정한다.**

**착수 전 필독 (되돌리지 말 것 4종 — 메모리 `fr-ux-09-f2-create-issue-dialog-done`).**

1. `assigneeIntent` 초기값 **`undefined`** — `null` 로 바꾸면 모든 생성이 서버 자동 배정을 조용히 끈다
2. 폼에 `IssueLabelsEdit` 넣기 — 내장 「저장」이 「이슈 생성」과 겹쳐 E2E strict mode 파손
3. `CreateIssueDialog` 에 **라우터 훅 도입 금지** — import 0 이 F3 의 설계 조건
4. 표시용 담당자를 검색 결과 `find()` 로 되돌리기 — B-2 재발

**판별식 2개 (F2 게이트 2가 남긴 것).**

- **B-1.** 기존 컴포넌트를 `<form>` 안으로 처음 옮길 때, 안의 텍스트 input 전수에 Enter 의미를 물어라.
- **B-2.** 기존 화면의 컴포넌트를 새 화면에 재사용할 때, 감싸는 쪽 코드의 `버그 수정`·`회귀 방지` 주석을 먼저 읽어라.

**인접 TODO 1건.** `apps/web — 이슈 상세 담당자 셀렉터가 검색 전 사용자 전량 노출` (`TODOS.md` L1611~).
F3 과 같은 영역이라 **묶어서 처리하면 중복이 적다** — 스펙 단계에서 포함 여부 결정.

## 도메인 정리

- **BC**. 논리 = `agile-planning`(보드·백로그·스프린트 화면) / 물리 = `apps/web` **프론트 전용**.
  FR-UX-05 D4 · FR-UX-06 D5 · FR-UX-07 ADR §D2 의 「논리 ≠ 물리」 선례 승계.
- **영향 엔티티**. 신규 0. 기존 `Issue`(issue-tracking 소유) 생성 + `Sprint` 배정(agile-planning) 소비만.
- **새 용어**. **0건** — 보드·백로그·스프린트·이슈 전부 `glossary.md` 에 이미 있다.
  glossary 갱신 0 · `domain/agile-planning.md` 갱신 0.
- **관련 ADR**. [2026-08-03-fr-ux-09-f3-create-issue-entry-points](../decisions/2026-08-03-fr-ux-09-f3-create-issue-entry-points.md) (신규, D-1~D-5) ·
  [2026-08-01 F2](../decisions/2026-08-01-fr-ux-09-f2-create-issue-dialog.md) ·
  [2026-07-31 B1](../decisions/2026-07-31-fr-ux-09-b1-create-issue-fields.md)

### 착수 전 실측이 뒤집은 것 2건

1. **생성 계약에 `sprintId` 도 상태(`stateKey`)도 없다.** `CreateIssueRequest.kt` 가 받는 건
   `projectKey`·`typeId`·`summary`·`description`·`componentIds`·`securityLevelId`·`customFields`·
   `assigneeId`(3-state)·`priority`·`labels` 뿐이다. **「칸에서 만들면 그 칸에 생긴다」는 1회 제출로 불가능**이다.
2. **정본 §4.7 F3 의 산문과 파일 목록이 서로 다르다.** 산문은 「보드 컬럼 · 백로그 섹션 · **목록 헤더**」,
   파일 목록은 `BoardColumn`·`BacklogColumn`·**`SprintColumn`**. F2 ADR D-1 이 후자로 확정했으므로 **산문이 stale**.

### 확정 결정 (ADR D-1~D-5)

| # | 결정 | 요지 |
|---|---|---|
| D-1 | **컨텍스트 반영은 스프린트만** (2026-08-03 Maxi 확정, 3안 중 B) | 백로그는 기본 동작이 곧 정답 · 보드는 계약상 불가 |
| D-2 | 스프린트는 **생성 후 `assignToSprint` 2회 호출** | 2차 실패 시 **이슈는 온전**하므로 되돌리지 않고 부분 성공을 명시 노출. 🛑 2차 실패를 1차 실패처럼 다루면 중복 이슈가 생긴다 |
| D-3 | **보드 진입점은 컬럼별이 아니라 보드 헤더 1곳** | 지키지 못할 약속은 하지 않는다. 정본 §4.7 원문을 **정정 노트가 아니라 원문 자체로** 고친다 |
| D-4 | 프로젝트 프리필은 **기존 경로에 위임**(신규 배선 0) | `useTrackActiveProject`(ShellLayout) → 활성 프로젝트 → `useDefaultProjectSelection`. **명시 전달(`initialProjectKey`) 전환 여부는 스펙 단계 결정** |
| D-5 | 이 PR 이 **FR-UX-09 완주** | D1·D3·D6·D7 → `[x]`. FR 카운트 139 불변, 진척 열은 머지 직전 재실측 |

### 기존 결정 충돌

**1건.** F2 ADR D-1 의 「F3 = 진입점 3곳 **배선만**」을 D-2·D-3 이 넘어선다.
조용한 확장이 아니라 **명시적 승계·정정** — 근거는 위 실측 1번, 2026-08-03 Maxi 확정으로 채택.
그 외 충돌 없음 (B1 ADR 의 3-state·알림 REST 한정은 그대로 유지).

## 스펙

전체 스펙. [docs/specs/2026-08-03-fr-ux-09-f3-create-issue-entry-points.md](../specs/2026-08-03-fr-ux-09-f3-create-issue-entry-points.md)
— 시나리오 S1~S7 · FR1~FR15 · NFR1~NFR6 · 엣지 E1~E11 · 설계결정 D-A~D-E · 완료기준 C1~C17 · 한계 L1~L4.

**핵심 3줄.**

- 백로그 칸·스프린트 칸·**보드 헤더**(컬럼별 아님) 3곳에서 이슈 생성 모달을 제자리에서 연다.
- 스프린트 칸에서 만든 이슈만 생성 직후 그 스프린트에 배정하고, **배정만 실패하면 「이슈는 만들어졌다」를 먼저 알린다**.
- 세 진입점 모두 CREATE 권한 **fail-closed** 게이트 — 권한 조회 중·실패에도 비활성.

**이 단계가 확정한 것 2건** (ADR 이 열어 둔 것 + 새로 정한 것).

- **D-A. 프로젝트 프리필을 명시 전달로 바꾼다** (`initialProjectKey` prop 신설). 활성 프로젝트 경유는
  `isKnownProject` 가드가 아직 통과 못한 순간 **다른 프로젝트가 채워진 채로 열린다**.
- **D-B. 칸은 아이콘 버튼 + `sr-only` 이름, 보드는 아이콘 + 텍스트.** 칸 폭이 288px 로 이미 꽉 차 있다.

## Brainstorming Check

✅ 통과 (1회 iteration). **gap 3건 발견 → 전부 스펙 흡수.** 셋 다 코드를 열어 확인했다(추측 0).

- **❓G1 (BLOCKER 급).** MSW 목이 만든 이슈를 `backlogStore` 에 넣지 않고, 배정 목은 모르는 키를
  **201 로 멱등 처리**한다 → 「호출됐다」만 보는 테스트는 **항상 통과**(가짜 그린)하고
  「나타난다」를 보는 테스트는 **구현이 옳아도 실패**(거짓 실패)한다. → FR-13 · D-D · C15
- **❓G2 (BLOCKER 급).** 새 버튼 이름이 상단바 `만들기` · 모달 제출 `이슈 생성` · `스프린트 생성` 과
  **부분 문자열로 충돌**하면 기존 e2e·단위 테스트가 깨진다. Playwright·Testing Library **둘 다 기본이
  부분 일치**다. → FR-14 · D-E · E-10 · C16 (전수 판별식 + 비-공허 증명)
- **❓G3.** 모달 소유 위치 미정 → 칸마다 두면 `role="dialog"` 가 N개 (F2 164발생 함정 재발). → FR-15 · NFR-6 · C17
- **정정 1건.** FR-6 이 권한 게이트를 신규 계산처럼 읽혀 중복 구현을 부를 수 있었다 —
  두 라우트가 이미 계산해 둔 값(`board.tsx:287` · `backlog.tsx:64`) 재사용으로 못 박았다.

## Plan

> **명세 우선 원칙.** 아래 task 본문의 표기가 스펙과 어긋나면 **스펙이 이긴다**
> (learnings 2026-05-22 plan/spec drift). 타입·필드명은 스펙 §4 FR 번호를 참조한다.
> **에이전트는 전 task `frontend-engineer`** (plan 헤더 기본값).

### Task 1. 접근 가능 이름 전수 판별식 + 진입점 i18n 문자열

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/i18n/backlog-labels.ts`, `apps/web/src/i18n/board-labels.ts`, `apps/web/src/i18n/ko.ts`, `apps/web/src/i18n/__tests__/create-entry-point-names.test.ts`]
- depends-on: []

**RED**. `i18n/__tests__/create-entry-point-names.test.ts` 신설.
- **같은 화면에 공존 가능한 버튼 이름 집합**을 상수로 모은다 — 백로그 화면(백로그 칸·스프린트 칸 N개·`스프린트 생성`·`스프린트 시작`·`스프린트 완료`·`번다운`·상단바 `만들기`·모달 `이슈 생성`·`취소`) / 보드 화면(보드 헤더·상단바 `만들기`·모달 `이슈 생성`).
- 단언 = **집합 안 임의의 두 이름 `a≠b` 에 대해 `a.includes(b)` 가 참인 쌍이 0**.
- 신규 문자열이 아직 없으므로 **import 실패로 red**.
- 🛑 손으로 3~5개만 나열하지 말 것 — FR-UX-08 PR-B 의 `admin` ⊂ `adminNav` 가 정확히 손나열이 놓친 결함이다.

**GREEN**. 문자열 신설. 기존 3개(`만들기`·`이슈 생성`·`스프린트 생성`)를 **부분 문자열로 포함하지 않는** 이름을 고른다.
- `backlogLabels.createIssueInBacklog` — 백로그 칸
- `backlogLabels.createIssueInSprint(sprintName)` — 스프린트 칸. **스프린트 이름을 포함해 서로 구분**(FR-10)
- `boardLabels.page.createIssue` — 보드 헤더
- `issueCreateStrings.sprintAssignFailed(key)` — 부분 성공 안내 (D-C, 경고 톤)

**REFACTOR**. 판별식의 화면별 집합을 이름 있는 상수로 분리 + 「왜 부분 일치가 위험한가」 주석
(Playwright `getByRole(name)`·Testing Library 정규식 **둘 다 기본이 부분 일치**).

**검증**.
```bash
pnpm --filter @bts/web test -- src/i18n/__tests__/create-entry-point-names.test.ts
```
**비-공허 확인 (C16)** — 신규 이름 하나를 일부러 `이슈 생성` 으로 바꿔 **red 를 눈으로 본 뒤** 되돌린다.

---

### Task 2. MSW 목 — 생성한 이슈가 백로그 조회에 나타난다

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/mocks/issue-handlers.ts`, `apps/web/src/mocks/backlog-fixtures.ts`, `apps/web/src/mocks/issue-handlers.test.ts`]
- depends-on: []

**RED**. `mocks/issue-handlers.test.ts` 에 목 계약 테스트 2건 추가.
1. `POST /api/v1/issues` 로 만든 키가 **직후 `GET /api/v1/projects/{key}/backlog` 의 `backlog` 배열에 있다** → 현재 **실패**(생성 핸들러가 `backlogStore` 를 안 건드림).
2. 그 키로 `POST /api/v1/sprints/{id}/issues` 후 **그 스프린트 `issues` 에 있고 `backlog` 에는 없다** → 현재 **실패**(모르는 키라 `backlog-handlers.ts:230` 이 201 멱등 no-op).

**GREEN**. `createIssueHandler` 가 생성 이슈를 해당 프로젝트 `backlogStore` 의 백로그 칸에 추가.
- 프로젝트 항목이 없으면 만들지 않고 **조용히 건너뛴다** — 백로그를 안 쓰는 기존 테스트에 영향 0.
- 추가 위치는 **맨 끝**(rank 순서 관례).

**REFACTOR**. 추가 로직을 `backlog-fixtures.ts` 의 이름 있는 헬퍼로 옮겨 두 핸들러가 같은 출처를 쓰게 한다
(learnings 2026-05-23 「fixture 가 helper 를 호출해 drift 를 본질 차단」).

**검증**.
```bash
pnpm --filter @bts/web test -- src/mocks/issue-handlers.test.ts src/mocks/backlog-handlers.test.ts
```
🛑 **이 task 의 red 를 건너뛰면 안 된다** — C15 가 요구하는 「확장 전 red」가 바로 이것이고,
이게 없으면 뒤 task 의 「나타난다」 단언이 **가짜 그린인지 진짜인지 구분할 수 없다**.

---

### Task 3. `CreateIssueEntryButton` — 진입점 3곳이 공유하는 버튼

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/issue/CreateIssueEntryButton.tsx`, `apps/web/src/components/issue/__tests__/CreateIssueEntryButton.test.tsx`]
- depends-on: [1]

**RED**. 단위 테스트 4건.
- `canCreate=true` → 활성 버튼, 접근 가능 이름 = 전달한 `label`
- `canCreate=false` → **`disabled`**, 클릭해도 `onClick` 미발화 (fail-closed, FR-6/E-6)
- `variant='icon'` → 아이콘 + `sr-only` 이름 (칸용, D-B)
- `variant='text'` → 아이콘 + 보이는 텍스트 (보드 헤더용, D-B)

**GREEN**. `components/issue/CreateIssueEntryButton.tsx` 신설.
props = `label` · `variant: 'icon' | 'text'` · `canCreate` · `onClick`. `Plus` 아이콘 + `Button` 프리미티브.

**시각 규격 — 디자인 리뷰 확정 (DR-1·DR-3, 임의 값 금지).**

| variant | Button variant | size | 근거 |
|---|---|---|---|
| `icon` (칸 헤더) | `ghost` | **`icon-xs`**(size-6) | 제목 행의 기존 배지가 `text-xs`/`py-0.5` 다. `icon`(size-8)은 **행 높이를 키운다** |
| `text` (보드 헤더) | **`outline`** | `default` | 🛑 `default`(primary)를 쓰면 **상단바 「만들기」와 같은 화면에 primary CTA 2개**가 된다. 상단바 버튼은 모든 페이지에 항상 있다 |

**REFACTOR**. 「왜 fail-closed 인가」 주석 — 로딩·에러도 **비활성**이다.
선례 `routes/issues.index.tsx:379-390 NewIssueButton` 을 참조로 명시.

**검증**.
```bash
pnpm --filter @bts/web test -- src/components/issue/__tests__/CreateIssueEntryButton.test.tsx
```

---

### Task 4. `initialProjectKey` — 모달이 프로젝트를 받아서 연다

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/issue/CreateIssueDialog.tsx`, `apps/web/src/components/issue/IssueCreateForm.tsx`, `apps/web/src/components/issue/create/use-issue-create-defaults.ts`, `apps/web/src/components/issue/__tests__/CreateIssueDialog.test.tsx`]
- depends-on: []

**RED**. `CreateIssueDialog.test.tsx` 에 2건 추가.
- `initialProjectKey='INFRA'` 로 열면 프로젝트 칸이 **INFRA** → 현재 실패(prop 없음)
- **미전달이면 기존 활성 프로젝트 기본값이 그대로** → 기존 동작 무회귀 (C12)

**GREEN**. `CreateIssueDialog` → `IssueCreateForm` → `useDefaultProjectSelection` 으로 옵셔널 값 전달.
`useDefaultProjectSelection` 의 기본값 계산에서 **명시값이 있으면 그것을 우선**하되,
**「아직 사용자가 안 골랐을 때만 채운다」는 기존 가드는 유지**한다.

**REFACTOR**. KDoc 에 D-A 근거 기록 — 활성 프로젝트 경유는 `isKnownProject` 가드가 아직
통과 못한 순간 **다른 프로젝트가 채워진 채로 열린다**.

**검증**.
```bash
pnpm --filter @bts/web test -- src/components/issue/__tests__/CreateIssueDialog.test.tsx src/routes/issues.new.test.tsx
grep -c "@tanstack/react-router" apps/web/src/components/issue/CreateIssueDialog.tsx   # 반드시 0 (NFR-2/C9)
```

---

### Task 5. 백로그 칸 진입점 + 모달 소유권

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/backlog/BacklogColumn.tsx`, `apps/web/src/components/backlog/BacklogBoard.tsx`, `apps/web/src/routes/projects.$projectKey.backlog.tsx`, `apps/web/src/components/backlog/BacklogColumn.test.tsx`, `apps/web/src/components/backlog/BacklogBoard.test.tsx`]
- depends-on: [2, 3, 4]

**RED**. 4건.
- 백로그 칸 헤더에 진입점이 있고 누르면 **`role="dialog"` 가 1개** 열린다 (FR-1/FR-15)
- 권한 없음 → 비활성 (FR-6)
- 생성 완료 → **백로그 칸에 새 이슈가 나타난다** (C1 — Task 2 없이는 이 단언이 성립하지 않는다)
- 스프린트가 2개여도 **`role="dialog"` 는 1개** (C17)

**GREEN**.
- `BacklogColumn` 에 `onCreateIssue?` · `canCreateIssue?` prop 추가 → 헤더에 `CreateIssueEntryButton`
- **`BacklogBoard` 가 모달 1개를 소유**하고 「어느 칸이 눌렀는가」를 상태로 갖는다 (FR-15)
- 백로그 라우트가 `canCreateIssue` 를 내린다 — **`canManageSprint` 를 재사용하되 이름은 분리**(FR-6)

**REFACTOR**. 콜백을 `useCallback` 으로 안정화 — 컬럼이 `memo` 라 매 렌더 새 함수를 주면
재렌더 스킵이 무력화된다 (NFR-4). 드롭 영역 밖 헤더임을 주석으로 못 박는다 (NFR-5).

**검증**.
```bash
pnpm --filter @bts/web test -- src/components/backlog/
```

---

### Task 6. 스프린트 칸 진입점 + 배정 + 부분 성공

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/backlog/SprintColumn.tsx`, `apps/web/src/components/backlog/BacklogBoard.tsx`, `apps/web/src/components/backlog/SprintColumn.test.tsx`, `apps/web/src/components/backlog/BacklogBoard.test.tsx`]
- depends-on: [5]

**RED**. 4건.
- 스프린트 칸에서 만들면 `POST /issues` **1회** + `POST /sprints/{id}/issues` **1회** 이고
  **그 스프린트 칸에 나타난다** (C2)
- 배정 실패 주입 → **이슈 키를 담은 부분 성공 안내**가 뜨고 생성이 되돌려지지 않는다 (C3/FR-5)
- `COMPLETED` 스프린트 → **진입점 미렌더** (C7/E-3)
- 배정은 **1회만** 발화한다 (E-8)

**GREEN**. `SprintColumn` 에 같은 두 prop 추가. `BacklogBoard` 의 생성 성공 콜백이
「어느 칸이 눌렀는가」로 분기해 스프린트면 기존 `useAssignToSprint` 를 부른다.
목록 갱신은 **배정 성공 후 1회**(E-7) — 훅이 이미 invalidate-only 다.

**REFACTOR**. 🛑 **2차 실패를 1차 실패처럼 다루지 않는다**를 주석으로 고정 (ADR D-2).
`error` 토스트가 아니라 경고 톤 — 빨간 실패는 「안 만들어졌다」로 읽혀 **중복 이슈**를 부른다.

**검증**.
```bash
pnpm --filter @bts/web test -- src/components/backlog/
```

---

### Task 7. 보드 헤더 진입점

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/routes/projects.$projectKey.board.tsx`, `apps/web/src/routes/projects.$projectKey.board.test.tsx`]
- depends-on: [3, 4]

**RED**. `routes/projects.$projectKey.board.test.tsx` **신설**(이 라우트는 현재 테스트 파일이 없다).

🛑 **범위를 배선 3점으로 좁힌다 (디자인 리뷰 DR-7).** 이 라우트는 600줄 + `useBoards`·`useBoard`·
`useProjectPermissions`·라우터 의존이 많아 렌더 셋업이 비싸다. **fail-closed 판정은 T3 이 이미
단독으로 검증**하므로 여기서 다시 전개하지 않는다.

- 보드 헤더에 진입점이 **있다** (FR-3)
- 진입점이 `canCreate` 를 **그대로 내려준다** — 권한 판정 자체가 아니라 **배선**을 본다 (FR-6, `board.tsx:287` 재사용)
- 생성 성공 → **URL 불변** + 토스트 + 「보기」 액션 (FR-12/C4)

**GREEN**. 보드 헤더 행(`FavoriteButton`·`ProjectNavTabs` 인접)에 `CreateIssueEntryButton variant='text'`
+ `CreateIssueDialog` 1개. `initialProjectKey={projectKey}`.

**REFACTOR**. 🛑 **`BoardColumn.tsx` 는 건드리지 않는다** (C8). 왜 컬럼별이 아닌지를
ADR D-3 링크와 함께 주석으로 남긴다 — 다음 사람이 「컬럼에 붙이는 걸 빠뜨렸다」로 오해하지 않게.

**검증**.
```bash
pnpm --filter @bts/web test -- src/routes/projects.\$projectKey.board.test.tsx
git diff --stat apps/web/src/components/board/BoardColumn.tsx   # 반드시 출력 0줄 (C8)
```

---

### Task 8. E2E — 진입점 3곳 시나리오

**메타**.
- agent: `qa-engineer`
- files: [`apps/web/e2e/issue-create-entry-points.spec.ts`]
- depends-on: [6, 7]

**RED→GREEN**. 신규 스펙 파일. S1·S2·S3·S4·S5·S7 을 각 1건.
- 🛑 **셀렉터는 `exact: true` 를 기본으로** 쓴다. 부분 일치가 이 PR 의 G2 결함 양식이다.
- 🛑 **기존 `issue-create-dialog.spec.ts` 를 수정하지 않는다** — 수정이 필요해졌다면
  그건 이름 충돌이 실재한다는 신호이고, 답은 e2e 수정이 아니라 **이름 변경**이다.

**검증**.
```bash
pnpm --filter @bts/web test:e2e -- issue-create-entry-points.spec.ts
pnpm --filter @bts/web test:e2e -- backlog.spec.ts board-kanban.spec.ts issue-create-dialog.spec.ts   # 인접 무회귀 (C11)
```

---

### Task 9. 문서 전수 동기화 + 정본 정정

**메타**.
- agent: `frontend-engineer`
- files: [`docs/plan/product/personalization.md`, `TODOS.md`, `CHANGELOG.md`, `docs/plans/2026-08-03-fr-ux-09-f3-create-issue-entry-points.md`]
- depends-on: [8]

**작업** (TDD 대상 아님 — 문서. 검증은 판별식 스크립트).
- §4.7 F3 산문 정정 — 「목록 헤더」 → 보드 헤더, 「`BoardColumn.tsx` 배선」 → 보드 라우트 헤더.
  **정정 노트가 아니라 원문 자체를 고친다** (ADR D-3).
- §4.7 **D1·D3·D6·D7 → `[x]`** (ADR D-5). FR 카운트 139 불변.
- 진척 열은 **머지 직전 재실측** — 계획 시점 합계는 유통기한이 있다.
- `TODOS.md` 등재 1건 — 상단바 「만들기」 CREATE 권한 게이트 부재 (스펙 L3, 선재).
- `CHANGELOG.md` 항목 추가.

**검증**.
```bash
bash scripts/verify-master-plan.sh; echo "EXIT=$?"     # 0 필수
node scripts/build-doc-index.mjs                        # 고아·깨진 링크 0
```
🛑 verify 스캐너는 §헤더에 `FR-UX-09` 토큰이 들어가면 **메시지 없이 EXIT 1** 을 낸다
(FR-UX-08 PR-B 실측). 절 번호로 지칭한다.

## Plan 메타

- **task 수**. 9 (T1~T8 은 TDD 사이클, T9 는 문서 + 판별식)
- **wave 예상**. 6 — W1 `[1,2,4]` · W2 `[3]` · W3 `[5,7]` · W4 `[6]` · W5 `[8]` · W6 `[9]`
  (T5·T7 은 파일 교집합 0 이라 동시. T6 은 `BacklogBoard.tsx` 가 겹쳐 T5 뒤로 직렬)
- **TDD 강제**. yes (T1~T8). `test:` 커밋이 `feat:` 보다 먼저인지 기계 검증
- **추가 검증**. `typecheck` · `eslint`(신규 경고 0) · `vitest` 전체 · `playwright` 인접 무회귀 · `verify-master-plan.sh` EXIT 0
- **★순서가 의미를 갖는 지점 2곳**.
  1. **T2 의 red 를 먼저 본다** — 없으면 T5·T6 의 「나타난다」가 가짜 그린인지 알 수 없다 (C15)
  2. **T1 이 T3 보다 먼저다** — 이름을 정하기 전에 판별식이 있어야 충돌을 이름 단계에서 잡는다 (C16)

## 리뷰 결과

### plan-design-review (2026-08-03) — `type=ui` 체인

**판정. BLOCKER 0 · 반영 4건 · 후속 후보 1건.** 지적은 전부 **코드베이스 선례로 근거를 댔고**,
반영분은 plan task 본문에 **이미 접었다**(리뷰 결과에만 적고 task 는 그대로 두면 구현자가 못 본다).

| # | 지적 | 근거 | 반영 |
|---|---|---|---|
| **DR-1** | 보드 헤더 진입점을 primary 로 만들면 **상단바 「만들기」와 같은 화면에 primary CTA 가 2개**가 된다. 상단바 버튼은 **모든 페이지에 항상** 있다 | `TopBar.tsx:80-89` 가 `variant="default"`. `SprintColumn` 도 「주 액션 1개」 규율을 이미 지킨다 — 시작/완료만 `default`, 번다운은 `bg-secondary` | T3 표에 **`outline` 고정** |
| **DR-2** | 스프린트 칸 헤더는 이미 액션이 세로로 쌓인다(시작·완료 중 1 + 번다운). 3번째를 스택에 얹으면 **헤더가 계속 길어진다** | `SprintColumn.tsx:96-154` 가 `flex-col` | 진입점은 **제목 행**(이름·상태배지·개수)에 넣어 **세로 증가 0** — T5·T6 GREEN 의 「헤더」는 제목 행을 뜻한다 |
| **DR-3** | 아이콘 버튼 크기 미지정 → 임의 값이 들어간다 | 제목 행 배지가 `text-xs`·`py-0.5`. `size="icon"`(size-8)은 행 높이를 키운다 | T3 표에 **`icon-xs`(size-6) 고정** |
| **DR-7** | T7 이 **테스트가 하나도 없는 600줄 라우트**에 첫 테스트를 신설한다. 여기서 권한 판정까지 전개하면 T7 혼자 부푼다 | `routes/projects.$projectKey.board.tsx` 에 `.test.tsx` 부재(실측) | T7 RED 를 **배선 3점**으로 좁힘. fail-closed 판정은 T3 단독 시험대가 갖는다 |

**후속 후보 1건 (이 PR 범위 밖).** 빈 칸(`이슈 없음` placeholder)이 CTA 를 겸하면 더 좋다.
지금도 헤더 진입점으로 접근 가능하므로 **결손이 아니다**. 스코프 확대를 피해 기록만 남긴다.

**확인만 하고 지나간 것 2건.**
- 부분 성공 토스트 톤 — `toast.warning` 이 코드베이스에 **실재**한다(2건 사용). ADR D-C 의
  「경고 톤」은 새 변형을 만들지 않고 이것을 쓴다.
- `COMPLETED` 스프린트에서 진입점 미렌더 → 헤더가 짧아지는 것 외 레이아웃 영향 없음.

### ★ 이 리뷰의 한계 (숨기지 않음)

**독립·교차모델 리뷰가 아니다.** `codex` 미설치 + 세션 지시로 에이전트 호출이 막혀 있어
**컨트롤러가 plan 리뷰를 자기수행**했다. #327·#328·#331 과 같은 조건이다.
자기 리뷰가 위 4건을 실제로 잡았지만, **그것이 독립 리뷰의 대체재라는 근거는 없다.**
