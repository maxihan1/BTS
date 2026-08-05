# FR-UX-13 F15 — 백로그 세로 스택 + 스프린트 다이얼로그 + 키보드 DnD 스펙

> FR. FR-UX-13 (F15) · BC. agile-planning (프론트 소비 · **백엔드 변경 0줄**)
> ADR. [`docs/decisions/2026-08-05-fr-ux-13-f15-backlog-vertical-stack.md`](../decisions/2026-08-05-fr-ux-13-f15-backlog-vertical-stack.md)
> plan. [`docs/plans/2026-08-05-fr-ux-13-f15-backlog-vertical-stack.md`](../plans/2026-08-05-fr-ux-13-f15-backlog-vertical-stack.md)
> 계약. [`docs/design/jira-parity-contract.md`](../design/jira-parity-contract.md) · 토큰 정본 [`DESIGN.md`](../../DESIGN.md)
> 작성. 2026-08-05 · 모든 줄번호는 이 날짜의 `main`(b20d393c0) 실측이다.

---

## 실측 방법 기록 (개수 리터럴의 출처)

이 문서가 쓰는 숫자는 전부 아래 명령의 결과다. 근거 없는 개수는 쓰지 않는다.

| 숫자 | 명령 | 결과 |
|---|---|---|
| e2e 다이얼로그 조회 발생 | `grep -rn "getByRole('dialog'" apps/web/e2e/` | **187발생 / 44파일** |
| 백로그 라우트 참조 | `grep -rn "/backlog" apps/web/e2e/` | **30발생 / 10파일** |
| backlog·sprint 언급 e2e | `grep -rli "backlog\|sprint" apps/web/e2e/` | **12파일** |
| `backlog.spec.ts` 실행 테스트 | `grep -n "^  test(" apps/web/e2e/backlog.spec.ts` | **11건** (S4는 주석 SKIP) |
| `BacklogBoard.test.tsx` 테스트 | `grep -n "^  it(" apps/web/src/components/backlog/BacklogBoard.test.tsx` | **41건 / describe 13개** |
| 시작·완료 클릭을 직접 단언하는 유닛 | 위 목록의 386·398·410·442행 | **4건** |

---

## 사용자 시나리오 (Given-When-Then)

### S1. 백로그를 세로로 훑는다

- **Given** ATLAS 프로젝트에 ACTIVE 스프린트 1개, PLANNED 스프린트 1개, 백로그 이슈 12개가 있다.
- **When** `/projects/ATLAS/backlog` 에 진입한다.
- **Then** 화면이 위에서 아래로 `ACTIVE 스프린트 → PLANNED 스프린트 → 백로그` 순서의 **전폭 섹션**으로 쌓인다. 가로 스크롤이 없다.
- **Then** 각 섹션 헤더에 이름·상태 배지·이슈 수·액션이 **한 줄**로 놓인다.

### S2. 섹션을 접어 시야를 좁힌다

- **Given** 스프린트 섹션이 펼쳐져 있다.
- **When** 섹션 헤더 왼쪽의 접기 토글을 누른다.
- **Then** 그 섹션의 카드 목록이 사라지고 헤더만 남는다. 헤더의 이슈 수는 그대로 보인다.
- **When** 페이지를 새로고침한다.
- **Then** 접힌 상태가 그대로 복원된다.

### S3. 스프린트를 기간·목표와 함께 시작한다

- **Given** PLANNED 스프린트 「스프린트 1」이 있고 사용자에게 CREATE 권한이 있다.
- **When** 「스프린트 시작」 버튼을 누른다.
- **Then** 시작 다이얼로그가 열리고 현재 시작일·종료일·목표가 채워져 있다.
- **When** 종료일을 바꾸고 다이얼로그의 「스프린트 시작」을 누른다.
- **Then** `PATCH /sprints/{id}` 로 바뀐 필드만 보내고, 성공하면 이어서 `POST /sprints/{id}/start` 를 보낸다.
- **Then** 다이얼로그가 닫히고 섹션 배지가 ACTIVE 로 바뀐다.

### S4. 값을 안 바꾸고 그냥 시작한다

- **Given** 시작 다이얼로그가 열려 있고 아무 입력도 바꾸지 않았다.
- **When** 「스프린트 시작」을 누른다.
- **Then** `PATCH` 를 **보내지 않고** `POST /start` 만 보낸다.

### S5. 수정은 됐는데 시작이 실패한다

- **Given** 시작 다이얼로그에서 종료일을 바꿔 제출했고 `PATCH` 는 200, `POST /start` 는 500 이 났다.
- **When** 응답이 돌아온다.
- **Then** 다이얼로그가 **닫히지 않는다**.
- **Then** 다이얼로그 안 `role="alert"` 에 「기간·목표는 저장했지만 스프린트를 시작하지 못했습니다.」가 뜬다. 「전부 실패했습니다」라고 말하지 않는다.
- **When** 「다시 시도」를 누른다.
- **Then** `PATCH` 없이 `POST /start` 만 재전송된다 (폼 기준값이 이미 저장된 값으로 갱신됐으므로 변경분이 0).

### S6. 스프린트를 완료하며 남은 일을 옮긴다

- **Given** ACTIVE 스프린트에 이슈 5개가 있고 그중 2개가 DONE 카테고리다.
- **When** 「스프린트 완료」를 누른다.
- **Then** 완료 다이얼로그가 열리고 **미완료 3건**이 목록으로 뜬다. 이관 대상 선택에는 「백로그」와 **완료되지 않은 다른 스프린트**만 있고 COMPLETED 스프린트는 없다.
- **When** 대상으로 「스프린트 2」를 고르고 「스프린트 완료」를 누른다.
- **Then** 이슈마다 `DELETE` → `POST` 를 **순서대로** 보내고, 3건이 모두 끝난 **뒤에** `POST /sprints/{id}/complete` 를 보낸다.

### S7. 이관 중 일부가 실패한다

- **Given** 미완료 3건을 「스프린트 2」로 옮기는 중 2번째 이슈의 `POST` 가 500 이 났다.
- **When** 실패가 발생한다.
- **Then** **완료 요청을 보내지 않는다.** 다이얼로그가 열린 채로 남는다.
- **Then** 성공한 행은 「이관됨」으로 잠기고, 실패한 행에만 「이관 실패」가 붙는다.
- **Then** 요약 `role="alert"` 에 「이슈 3개 중 1개를 옮기지 못했습니다. 스프린트는 완료되지 않았습니다.」가 뜬다.
- **When** 「다시 시도」를 누른다.
- **Then** **실패한 1건만** 재시도한다. 이미 성공한 2건은 다시 보내지 않는다.

### S8. 마우스 없이 카드를 옮긴다

- **Given** 백로그 섹션의 카드 「ATLAS-2」에 키보드 포커스가 있다.
- **When** Space 를 누른다.
- **Then** 스크린리더가 「ATLAS-2 카드를 집었습니다.」를 한국어로 읽는다.
- **When** ↓ 를 눌러 스프린트 섹션까지 이동하고 Space 를 누른다.
- **Then** 「스프린트 1 스프린트로 옮겼습니다.」를 읽고, 마우스 드래그와 **같은 mutation** 이 나간다.
- **When** 대신 Esc 를 누른다.
- **Then** 「취소했습니다.」를 읽고 아무 요청도 나가지 않는다.

---

## Jira 대조

계약 §1 의 4단계를 수행한 결과다.

### 1단계 — 대응 화면 식별

| BTS 요소 | Jira Cloud 2025 대응 |
|---|---|
| 백로그 화면 전체 | **Backlog** (Software 프로젝트 좌측 내비 → Backlog) |
| 스프린트 시작 | **Start sprint** 모달 (Sprint name / Duration / Start date / End date / Sprint goal) |
| 스프린트 완료 | **Complete sprint** 모달 (완료/미완료 건수 요약 + 미완료 이슈 이동 대상 선택) |
| 섹션 접기 | 스프린트/백로그 섹션 헤더의 chevron 토글 |
| 키보드 DnD | Jira 백로그의 카드 키보드 이동 |

### 2단계 — 조작감 갭

| # | Jira | BTS 현재 (실측) | 갭 |
|---|---|---|---|
| G1 | 스프린트·백로그가 **세로 스택**, 각 섹션 전폭 | `BacklogBoard.tsx:178` `<div className="flex gap-4 overflow-x-auto pb-4">` + 칸마다 `min-w-72 w-72` (`BacklogColumn.tsx:68` · `SprintColumn.tsx:83`) | 가로 칸반. 스프린트가 3개만 돼도 가로 스크롤 |
| G2 | **스프린트가 위, 백로그가 맨 아래** | `BacklogBoard.tsx:179-203` — `BacklogColumn` 이 먼저, 그 뒤 `sprints.map` | 순서가 반대 |
| G3 | 섹션 접기/펼치기 | 없음 | 부재 |
| G4 | Start sprint 가 **모달**이고 기간·목표를 받는다 | `BacklogBoard.tsx:194-196` — 확인 없이 곧장 `startSprint.mutate` | 확인 단계·입력 부재 |
| G5 | Complete sprint 가 **모달**이고 미완료 이슈 이동 대상을 받는다 | `BacklogBoard.tsx:197-199` — 곧장 `completeSprint.mutate` | 확인 단계·이관 부재 |
| G6 | 키보드로 카드 이동 가능 | `BacklogBoard.tsx:103-105` `PointerSensor` 단독 | KeyboardSensor 부재 |
| G7 | 드래그 공지가 사용자 언어 | `BacklogBoard.tsx:176` `accessibility={undefined}` | **꺼진 것이 아니라 영어 기본값이다** — 아래 §정정 참조 |
| G8 | 섹션 헤더 액션이 가로 한 줄 | `SprintColumnHeader.tsx:63,92-128` — 288px 폭 때문에 시작/완료·번다운이 세로로 쌓임 | 세로 스택에서는 폭이 남는데도 세로 배치 |

**정정 — G7 「공지가 꺼져 있다」는 거짓이다.**
`@dnd-kit/core@6.3.1` 는 `DndContext` 가 `{...accessibility}` 를 `Accessibility` 에 전개하고
(`core.esm.js:3363`), `Accessibility` 는 `announcements = defaultAnnouncements` ·
`screenReaderInstructions = defaultScreenReaderInstructions` 를 **기본값으로** 받는다
(`core.esm.js:88-91`). 기본값은 영어다 — `"To pick up a draggable item, press the space bar…"`
(`:40-42`), `"Picked up draggable item " + active.id + "."` (`:43-49`).
그리고 백로그 카드의 `active.id` 는 `` `${context}:${issue.key}` `` (`BacklogCard.tsx:153`) 이라
현재 스크린리더는 **「Picked up draggable item backlog:ATLAS-2.」** 를 읽는다.
따라서 이 PR 의 일은 「공지를 켜는 것」이 아니라 **영어 원문 + 내부 id 를 한국어 + 사람이 읽는
이름으로 교체하는 것**이다.

### 3단계 — BTS 제약과 교차

- 계약 §2 「`role="dialog"` 고유 label」 — 실측 **187발생 / 44파일**. 같은 화면에 이미
  `새 이슈 만들기`(`CreateIssueDialog.tsx:94` `DialogTitle`)가 있다 → FR-10 이 이름 규약을 정한다.
- 계약 §2 「`<h1>` 단 하나 + verbatim」 — `routes/projects.$projectKey.backlog.tsx:79` 의 `백로그` h1 은 건드리지 않는다.
- 계약 §4 「키보드 DnD 공지 = `KanbanBoard.tsx` `buildDragAnnouncements`」 — **재사용 불가**로 판정. 근거는 FR-9.
- 계약 §4 「빈 상태 = EmptyState 프리미티브」 — 현재 백로그는 자체 placeholder(`BacklogColumn.tsx:105-111`)를 쓴다. 이번 PR 은 레이아웃 축만 바꾸므로 **교체하지 않는다**(회귀면 확대 방지). 별도 항목으로 로드맵에 남긴다.

### 4단계 — Jira 대응이 없는 것

| 항목 | 판정 |
|---|---|
| 「수정은 됐는데 시작은 실패」한 2단계 중간 상태 | **Jira 대응 없음.** Jira 는 단일 요청이라 이 상태가 생기지 않는다. **ADS v2 준용** — inline `role="alert"` + 모달 유지 + 부분 성공 명시 (ADS Modal dialog 의 "keep the dialog open on error" 패턴) |
| 이관 부분 실패의 행 단위 상태 표시 | **Jira 대응 없음.** ADS v2 준용 — 행 단위 상태 텍스트 + 요약 alert + 실패분만 재시도 |
| COMPLETED 스프린트를 백로그 화면에 계속 노출 | **Jira 와 다르다** (Jira 는 백로그에서 완료 스프린트를 감춘다). **기존 BTS 동작을 유지한다** — 감추면 `SprintColumn.tsx:69,100-107` 의 COMPLETED 비활성 표현과 그 유닛이 전부 죽는다. 변경 없음 |

---

## 기능 요구사항 (FR)

### FR-1. 세로 스택 레이아웃

- `BacklogBoard.tsx:178` 의 `<div className="flex gap-4 overflow-x-auto pb-4">` 를
  **`<div className="flex flex-col gap-3">`** 로 교체한다.
- 렌더 순서를 뒤집는다 — **`sprints.map(...)` 먼저, `BacklogColumn` 을 맨 마지막**.
- 스프린트 사이의 정렬은 **클라이언트에서 하지 않는다.** 백엔드가 이미
  `ACTIVE → PLANNED → COMPLETED, 그다음 startDate` 로 정렬해 내려준다
  (`BacklogApplicationService.kt:37` KDoc · `:209-214` `sprintComparator`).
  응답 배열 순서를 그대로 그린다. 클라이언트 정렬을 넣으면 근거 없는 발명이 되고
  백엔드 정렬과 이중화된다.
- 각 섹션의 고정폭을 없앤다 — `BacklogColumn.tsx:68` · `SprintColumn.tsx:83` 의
  `min-w-72 w-72` → **`w-full`**.
- `role="region"` 과 `backlogLabels.columnAriaLabel(name, count)` 는 **문자열 그대로 보존**한다.
  `backlog.spec.ts:143-151` 이 `getByRole('region').filter({ hasText: /^{name}/ })` 로 조회한다.

### FR-2. 섹션 접기/펼치기

- 각 섹션 헤더 **맨 앞**에 접기 토글을 놓는다. Lucide `ChevronDown`(펼침) / `ChevronRight`(접힘), 24×24 기본.
- 토글의 접근 이름은 **`aria-label` 로만** 준다. `sr-only` 텍스트 노드를 쓰면 안 된다 —
  섹션의 텍스트 콘텐츠 맨 앞에 글자가 끼어 `backlog.spec.ts:149` 의 `^` 앵커 정규식이 깨진다.
  선례는 `CreateIssueEntryButton.tsx:55-67`(variant=`icon`)로 이미 같은 방식이다.
- 접히면 카드 목록(`useDroppable` 이 붙은 div)을 **렌더하지 않는다**. 헤더의 이름·상태·개수·액션은 남는다.
- 상태는 `localStorage` 에 영속한다. 계약 §4 의 `hooks/use-sidebar-collapsed.ts`(zustand + fail-safe try/catch)를 **템플릿으로** 따른다.
  - 키. `bts.backlog.collapsed.{projectKey}`
  - 값. 접힌 섹션 id 배열 (`'backlog'` 또는 `'sprint-{sprintId}'`) — droppable id 와 같은 문자열을 쓴다.
  - 기본값. **전부 펼침.** COMPLETED 스프린트를 기본 접힘으로 두자는 안은 근거가 없어 채택하지 않는다.
  - 파싱 실패·용량 초과는 조용히 무시하고 전부 펼침으로 동작한다.
- 접힌 섹션에는 카드가 없으므로 그 섹션으로의 드롭이 불가능해진다. 이는 의도된 동작이며 E7 이 다룬다.

### FR-3. 시작 다이얼로그

- 트리거는 기존 버튼이다. **이름 `스프린트 시작` 을 바꾸지 않는다** (`backlogLabels.startSprint`, `SprintColumnHeader.tsx:99-101`).
  `backlog.spec.ts:501·525·541` 이 `exact: true` 로 조회한다.
- 다이얼로그는 `components/ui/dialog.tsx` 의 compound 래퍼(`Dialog`/`DialogContent`/`DialogHeader`/`DialogTitle`/`DialogDescription`/`DialogFooter`)만 쓴다.
  **`radix-ui` 의 `Dialog as DialogPrimitive` 직접 import 금지** — 래퍼가 이미 전량 흡수했다.
- 필드 3종. 전부 `components/ui/input.tsx` + `components/ui/label.tsx` + `components/ui/textarea.tsx`.
  | 필드 | 컨트롤 | 초기값 |
  |---|---|---|
  | 시작일 | `Input type="date"` (선례 `AuditLogFilters.tsx:185` · `WorklogAggregateReport.tsx:92`) | `sprint.startDate ?? ''` |
  | 종료일 | `Input type="date"` | `sprint.endDate ?? ''` |
  | 목표 | `Textarea` 3행 | `sprint.goal ?? ''` |
- 값의 출처는 **백로그 응답이다.** 별도 조회를 하지 않는다 —
  `api/backlog.ts:52-67` `sprintMetaSchema` 가 `goal`·`startDate`·`endDate`·`version` 을 이미 싣는다.
- 다이얼로그 안 제출 버튼의 이름도 **`스프린트 시작`** 으로 둔다(Jira 의 Start 와 동형). 취소는 `취소`(`ko.ts:785` 재사용).
- `canManageSprint === false` 이면 트리거는 그대로 렌더하되 다이얼로그가 열리지 않는다 —
  현재 동작(`BacklogBoard.tsx:194` 의 `onStart` 를 `undefined` 로 넘김)을 승계한다.
  `BacklogBoard.test.tsx:442` 가 이 형태를 단언한다.

### FR-4. 시작 = `PATCH`(변경분만) → `start` 2단계

- 제출 시 폼 값과 **기준값**(다이얼로그를 연 시점의 `SprintMeta`)을 필드별로 비교한다.
- 변경분이 **1개 이상**이면 먼저 `PATCH /api/v1/sprints/{id}` 를 보낸다.
  - body 에는 **바뀐 필드만** 담는다 (3-state partial, `SprintController.kt:149`).
  - `version` 은 **항상 담는다** — 필수다.
  - 빈 문자열 → `null` 로 보내 값을 지운다. 값이 없었고 여전히 없으면 변경분이 아니다.
- 변경분이 **0개**이면 `PATCH` 를 **보내지 않는다**. 불필요한 버전 증가와 낙관적 잠금 충돌면을 만들지 않기 위함이다.
- `PATCH` 성공 후 그 응답의 `SprintMeta` 로 **기준값과 `version` 을 갱신**한다. 이것이 S5 재시도의 정확성을 보장한다.
- 이어서 `POST /api/v1/sprints/{id}/start` 를 보낸다 (파라미터 0, `SprintController.kt:200-203`).
- 두 요청이 모두 성공해야 다이얼로그를 닫고 백로그 쿼리를 invalidate 한다.
- 중간 실패 문구는 **세 갈래로 분리한다**. 하나로 뭉뚱그리면 거짓말이 된다.
  | 실패 지점 | 문구 (신규 i18n 키) | 재시도 시 보내는 것 |
  |---|---|---|
  | `PATCH` 실패 (비-409) | 「기간·목표를 저장하지 못했습니다. 스프린트는 시작되지 않았습니다.」 | `PATCH` + `start` |
  | `PATCH` 409 | 「다른 사람이 먼저 수정했습니다. 최신 값을 불러왔으니 확인 후 다시 시도해 주세요.」 | 백로그 invalidate 후 기준값 교체 → 사용자가 재확인 |
  | `start` 실패 | 「기간·목표는 저장했지만 스프린트를 시작하지 못했습니다.」 | `start` 만 (변경분이 0이므로 자동으로 그렇게 된다) |
- 재시도 버튼은 `backlogLabels.retry`(`다시 시도`)를 **재사용**한다. 조회 실패 화면의 같은 이름 버튼과 공존할 수 없다 —
  조회 실패 시 `BacklogBoard.tsx:125-143` 이 조기 반환해 다이얼로그가 통째로 언마운트되기 때문이다. 이 근거를 코드 주석으로 남긴다.

### FR-5. 완료 다이얼로그

- 트리거는 기존 버튼이다. **이름 `스프린트 완료` 를 바꾸지 않는다** (`backlogLabels.completeSprint`, `SprintColumnHeader.tsx:113-117`, `backlog.spec.ts:536`).
- 구성.
  1. 요약 행 — 「완료 N건 · 미완료 M건」.
  2. 미완료 이슈 목록 — 이슈 키 + 제목 + 행 상태 슬롯. `max-h-72 overflow-y-auto`.
  3. 이관 대상 — `components/ui/select.tsx` **단일 Select**. 목록의 모든 미완료 이슈에 같은 대상을 적용한다(Jira 동형).
     - 옵션. 「백로그」 + **PLANNED·ACTIVE 스프린트 전부**(자기 자신 제외).
     - **COMPLETED 스프린트는 옵션에 넣지 않는다.** 넣으면 반드시 409 로 끝나는 선택지가 된다 (`SprintController.kt:244` 계약).
     - 기본 선택. 「백로그」.
  4. 푸터 — `취소` / `스프린트 완료`.
- 미완료가 0건이면 목록과 Select 를 렌더하지 않고 「옮길 이슈가 없습니다.」만 보인다. 이때 완료는 곧바로 `POST /complete` 1회다.

### FR-6. 이관 실행 — 순서·직렬·부분 실패

- **순서가 계약이다. 이관을 전부 끝낸 뒤에 완료한다.** 역순은 구조적으로 불가능하다.
- 이슈 1건당 요청.
  | 대상 | 요청 |
  |---|---|
  | 백로그 | `DELETE /api/v1/sprints/{sourceId}/issues/{key}` **1회** |
  | 다른 스프린트 | `DELETE /api/v1/sprints/{sourceId}/issues/{key}` → `POST /api/v1/sprints/{targetId}/issues` **2회** |
- `DELETE` 를 먼저 보내는 것은 선택이 아니다 — `sprint_issues` 에 `UNIQUE (issue_key)` 가 있어
  (`V503__sprints.sql:45-46`) 한 이슈는 전역에서 한 스프린트에만 속한다. 제거 없이 `POST` 하면 409 다.
- **직렬로 실행한다.** 동시 요청 수 1. 부분 실패 지점을 결정적으로 만들기 위함이며, 진행률을 정직하게 표시하기 위함이다.
- 진행 중에는 푸터 버튼 이름이 「스프린트 완료 중…」으로 바뀌고 비활성이 된다. 같은 버튼의 다른 상태이므로
  `create-entry-point-names.test.ts` §제외 3종 ② 에 해당해 판별식 집합에 **넣지 않는다**.
- **1건이라도 실패하면 `POST /complete` 를 보내지 않는다.**
- 실패 시 화면에 남기는 것.
  - 성공한 행 — 「이관됨」. 회색 텍스트. 재시도 대상에서 제외되고 시각적으로 잠긴다.
  - 실패한 행 — 「이관 실패」. `text-danger-text`.
  - 요약 `role="alert"` — 「이슈 {N}개 중 {M}개를 옮기지 못했습니다. 스프린트는 완료되지 않았습니다.」
  - 푸터에 `다시 시도` 버튼.
- **재시도 단위는 「실패한 이슈」다.** 성공분은 다시 보내지 않는다.
  `DELETE` 가 멱등(`SprintController.kt:256-259` 문서화된 계약)이라 재시도가 안전하다.
- 이관이 전부 성공하면 `POST /api/v1/sprints/{id}/complete` (파라미터 0, `:221-224`)를 1회 보내고,
  성공 시 다이얼로그를 닫고 백로그를 invalidate 한다.

### FR-7. 미완료 판정 기준

**실측 결론 — 백로그 응답만으로는 판정할 수 없다.**
`api/backlog.ts:25-42` 의 `backlogIssueSchema` 필드는
`key` · `summary` · `currentStateKey` · `assigneeId` · `priority` · `rank` · `version` · `epicKey` 뿐이고
**카테고리 필드가 없다**. `currentStateKey` 는 `'open'` 같은 워크플로우 상태 키 문자열이다
(mock 시드도 `backlog-fixtures.ts:300,310,333,343` 에서 `'open'`).

**채택 — 워크플로우 목록으로 상태 키를 카테고리에 사상한다.**

- 출처. `useWorkflows()`(`hooks/use-workflows.ts:26`, `GET /api/v1/workflows`).
  `api/workflows.ts:13,16-21` 이 `category: 'TODO' | 'IN_PROGRESS' | 'DONE'` 를 준다.
- 권한. `WorkflowController.kt:52` 의 `@GetMapping` 에는 `@PreAuthorize` 가 **없다**
  (권한 요구는 `:129` 의 변경 API 뿐). 일반 사용자도 조회 가능하다 — 실측 확인했다.
- 사상. 전 워크플로우의 `states` 를 훑어 `stateKey → Set<category>` 를 만든다.
- **판정은 안전측으로 기운다.**
  | 조건 | 판정 |
  |---|---|
  | 그 키의 카테고리 집합이 정확히 `{DONE}` | **완료** |
  | 집합이 비었다 (어느 워크플로우에도 없는 키) | **미완료** |
  | 집합이 `{DONE, ...다른 것}` (워크플로우마다 다름) | **미완료** |
  | 워크플로우 조회 자체가 실패했다 | **전부 미완료** + 다이얼로그 상단에 「상태 분류를 불러오지 못해 모든 이슈를 미완료로 봅니다.」 안내 |
- 과다 포함은 사용자가 목록에서 확인하고 대상을 바꿀 수 있으므로 손실이 없다.
  과소 포함(완료로 잘못 분류)은 이슈를 영구 동결시키므로 절대 허용하지 않는다 — 아래 §제약 조건 C1 참조.

**미측정으로 남는 것.** 서로 다른 워크플로우가 **같은 `stateKey` 를 다른 카테고리로** 정의하는 실데이터가
존재하는지는 이 스펙에서 재지 않았다. 판정이 안전측이라 기능은 안전하지만, 요약의 「완료 N건」 숫자가
그런 경우 과소 표시될 수 있다. 확인 방법은 §미정 항목에 적었다.

### FR-8. 키보드 DnD 센서

- `BacklogBoard.tsx:103-105` 에 `useSensor(KeyboardSensor)` 를 **추가**한다.
  `PointerSensor` 의 `activationConstraint: { distance: 5 }` 는 그대로 둔다 — 카드 안 `Link` 클릭 보존이 이 값에 걸려 있다.
  선례는 `KanbanBoard.tsx:524-527` 과 동일한 형태다.
- 카드는 이미 키보드 활성화 가능하다 — `BacklogCard.tsx:180-181` 이 `{...listeners} {...attributes}` 를
  카드 `div` 에 전개하고 있어 dnd-kit 이 `role="button"` · `tabIndex=0` · `aria-describedby` 를 부여한다.
  **DOM 구조를 바꾸지 않는다.** `aria-roledescription`(`:184`)과 `aria-label`(`:185`)이 spread 뒤에 오는 현재 순서를 유지한다.
- **충돌 감지가 키보드에서 다르게 동작한다는 것을 명시적으로 다룬다.**
  `backlog-collision.ts:15-32` 의 `cardFirstCollision` 은 `pointerWithin` 을 두 번 시도하는데,
  `pointerWithin` 은 포인터 좌표가 없으면 **무조건 빈 배열을 반환한다**
  (`@dnd-kit/core@6.3.1` `core.esm.js:472-481` — `if (!pointerCoordinates) return []`).
  즉 키보드 드래그에서는 두 단계가 모두 건너뛰어지고 `rectIntersection(args)` 폴백만 남아
  **카드 우선 규칙이 적용되지 않는다.**
  - 요구. `cardFirstCollision` 에 **키보드 경로를 명시적으로 추가한다** — 포인터 좌표가 없으면
    카드 droppable 만으로 `rectIntersection` 을 먼저 시도하고, 결과가 없을 때 칸 droppable 로 넘어간다.
    현재 코드의 우선순위 의도를 포인터/키보드 양쪽에서 동일하게 만든다.
  - 이 요구는 추측이 아니라 위 실측에 근거한다. 검증은 §시각 검증 기준의 T-KB-2 가 맡는다.
- 키보드 드롭 결과는 마우스와 **같은 `resolveBacklogDropAction`** 을 통과해야 한다
  (`lib/backlog-drag.ts:181`). 판정 로직을 두 벌로 나누지 않는다.

### FR-9. 한국어 드래그 공지

- `BacklogBoard.tsx:176` 의 `accessibility={undefined}` 를
  **`accessibility={{ announcements, screenReaderInstructions }}`** 로 교체한다.
- **`KanbanBoard.tsx` 의 `buildDragAnnouncements` 를 재사용하지 않는다.** 실측 근거 2개.
  1. 시그니처가 보드 전용이다 — `buildDragAnnouncements(board: BoardDetail, assigneeNames: Map<string, CardAssigneeDisplay>): Announcements` (`KanbanBoard.tsx:285-288`).
     백로그에는 `BoardDetail` 도 `CardAssigneeDisplay` 도 없다.
  2. **export 되어 있지 않다** — `KanbanBoard.tsx:285` 는 `function buildDragAnnouncements(` 로 시작한다(모듈 내부 함수).
- 대신 새 순수 모듈 **`apps/web/src/lib/backlog-announcements.ts`** 를 만든다.
  - 시그니처. `buildBacklogAnnouncements(view: BacklogView): Announcements`
  - `onDragOver`/`onDragEnd` 는 `resolveBacklogDropAction` 을 **그대로 재사용**해 공지와 실제 mutation 이 어긋나지 않게 한다 (KanbanBoard 가 확립한 원칙 승계).
  - 카드 이름은 내부 id 가 아니라 **이슈 키**를 읽는다. `active.id` 는 `` `${context}:${issue.key}` `` 이므로 `:` 뒤를 취한다.
- **조사(助詞) 계산이 필요 없도록 문구를 설계한다.** 가변 부분 뒤에 항상 고정 명사를 붙인다.
  | 이벤트 | 문구 |
  |---|---|
  | `onDragStart` | `{키} 카드를 집었습니다.` |
  | `onDragOver` (대상 있음) | `{섹션 이름} 스프린트 위에 있습니다.` / `백로그 위에 있습니다.` |
  | `onDragOver` (대상 없음) | `드롭 가능한 영역을 벗어났습니다.` |
  | `onDragOver` (판정 noop) | `이동할 수 없는 위치입니다.` |
  | `onDragEnd` (assign) | `{섹션 이름} 스프린트로 옮겼습니다.` |
  | `onDragEnd` (unassign) | `백로그로 옮겼습니다.` |
  | `onDragEnd` (rerank) | `순서를 변경했습니다.` |
  | `onDragEnd` (noop) | `변경 사항이 없습니다.` |
  | `onDragCancel` | `취소했습니다.` |
  - 「스프린트」는 종성이 없어 항상 `스프린트로`, 「백로그」도 항상 `백로그로` 다. 받침 판별이 원리적으로 불필요하다.
  - 따라서 `KanbanBoard.tsx:126` 의 비-export `josaEuro` 를 공용 모듈로 끌어내는 리팩터를 **하지 않는다**. 이 PR 과 무관한 파일을 건드리지 않기 위함이다.
- `screenReaderInstructions.draggable` 도 한국어로 준다 —
  「스페이스바로 카드를 집습니다. 방향키로 이동하고, 스페이스바로 놓거나 Esc 로 취소합니다.」
  지정하지 않으면 영어 기본값(`core.esm.js:40-42`)이 그대로 남는다.
- 모든 문구는 `i18n/backlog-labels.ts` 에 넣는다. 컴포넌트·lib 안 하드코딩 금지.

### FR-10. 이름 규약 — 즉사 계약 보존

- **버튼 이름 `스프린트 시작`·`스프린트 완료` 를 바꾸지 않는다.** 트리거가 다이얼로그를 여는 것으로 바뀌어도 이름은 그대로다.
- 신규 다이얼로그 2종의 접근 이름은 **`DialogTitle` 로 준다** (`CreateIssueDialog.tsx:94` 선례). 별도 `aria-label` 을 덧붙이지 않는다 — 두 이름이 경쟁한다.
  | 다이얼로그 | `DialogTitle` |
  |---|---|
  | 시작 | `스프린트 시작` |
  | 완료 | `스프린트 완료` |
  - 백로그 화면의 dialog 이름 3종(`새 이슈 만들기` · `스프린트 시작` · `스프린트 완료`)은 서로 substring 관계가 아니다.
  - 다이얼로그 제목과 트리거 버튼이 같은 글자인 것은 **role 이 달라 조회 공간이 겹치지 않는다** (`create-entry-point-names.test.ts:28-31` §제외 3종 ① 과 같은 논리).
- 다이얼로그 안 제출 버튼도 같은 이름(`스프린트 시작`/`스프린트 완료`)을 쓴다.
  Radix Dialog 가 modal 이라 열려 있는 동안 바깥 트리거는 접근성 트리에서 감춰지지만,
  **이 가정을 검증 없이 두지 않는다** — §시각 검증 기준 T-DL-1 이 「다이얼로그가 열린 동안 그 이름의 보이는 버튼이 정확히 1개」임을 단언한다.
- 신규 라벨은 전부 `i18n/backlog-labels.ts` 에 추가한다. 그리고 **버튼 이름인 것만**
  `i18n/__tests__/create-entry-point-names.test.ts` 의 `BACKLOG_SCREEN_BUTTON_NAMES` 에 등록한다.
  등록을 빠뜨리면 판별식이 조용히 공허해진다 (그 파일 :133-146 이 명시한 함정).
  - 등록 대상. 섹션 접기 토글 이름 1종 (예시 인자로 `collapseSection('2026-W31')`).
  - 등록 제외. 다이얼로그 제목(버튼 아님) · 「스프린트 완료 중…」(같은 버튼의 다른 상태) · 필드 라벨 · alert 문구.
- **재사용해야 하는 기존 값** — `취소`(`ko.ts:785`) · `다시 시도`(`backlogLabels.retry`). 새 문자열을 만들면 「이름 중복이 없다」 단언이 깨진다.

### FR-11. 파일 경로 보존

`apps/web/src/components/__tests__/button-primitive-usage.test.ts:206-208` 이
`SCANNED_FILES` 전원의 **파일 실재**를 단언한다. 목록에는
`components/backlog/CreateSprintForm.tsx` 와 `components/backlog/SprintColumn.tsx` 가 들어 있다(`:47-48`).

- 이 두 파일을 **삭제하거나 이름을 바꾸지 않는다.** 세로 스택으로 바꾸더라도 `SprintColumn.tsx` 라는 이름을 유지한다.
- 부득이 옮겨야 하면 **같은 PR 에서** `BATCH2_FILES` 를 함께 고친다. 안 고치면 유닛이 즉사한다.
- 신규 컴포넌트 파일에도 원시 `<button>` 을 쓰지 않는다 — `components/ui/button.tsx` 프리미티브만 쓴다.

### FR-12. 권한 게이팅 승계

- `canManageSprint`(CREATE) → 시작/완료 다이얼로그를 열 수 있는지.
- `canReorderIssue`(UPDATE) → 드래그(마우스·키보드 공통) 성립 여부. `useBacklogDrag` 의 `if (!canReorderIssue) return`(`use-backlog-drag.ts:71`)이 키보드 경로도 함께 막는다 — 같은 `handleDragEnd` 를 지나기 때문이다.
- `canCreateIssue`(CREATE, fail-closed) → 섹션 헤더의 이슈 생성 진입점.
- 접기 토글은 **권한과 무관**하다. 읽기 전용 사용자도 접을 수 있어야 한다.

### FR-13. truncated 경고 배너

- `BacklogBoard.tsx:151-158` 의 배너를 **그대로 둔다.** 문구·`role="alert"`·토큰(`border-warning bg-warning/10`) 변경 없음.
- 위치도 그대로 — 스택 **바깥 최상단**. 섹션 안으로 옮기지 않는다.
  `backlogViewSchema.truncated`(`api/backlog.ts:89-90`)는 **뷰 전체 플래그**라 어느 섹션이 잘렸는지 알 수 없다.
  섹션에 귀속시키면 근거 없는 발명이 된다.
- 배너는 접기 상태와 무관하게 항상 보인다.
- **truncated=true 이면 완료 다이얼로그의 제출을 막는다.** 근거는 §제약 조건 C1(영구 동결). 상세는 §미정 항목 U1.

### FR-14. MSW 핸들러 보강

`apps/web/src/mocks/backlog-handlers.ts` 에 **`PATCH /api/v1/sprints/:id` 핸들러가 없다**
(`grep -n "http.patch" ...` → `:137` 의 rerank 하나뿐).
없으면 시작 다이얼로그의 1단계가 개발·e2e 에서 미처리 요청이 된다.

- `http.patch('/api/v1/sprints/:id')` 를 추가한다. 3-state partial 반영 + `version` 증가 + `{ data: SprintMeta }` 반환.
- `version` 불일치면 **409** 를 반환한다. FR-4 의 409 분기를 검증할 수 있어야 한다.
- 부분 실패(S5·S7)를 e2e 에서 재현할 수 있도록 실패 토글을 둔다. 기존 선례를 그대로 따른다 —
  `backlog.spec.ts` 가 이미 `LS_KEY_BACKLOG_FAIL` 방식의 localStorage 토글을 쓴다.

---

## 비기능 요구사항 (NFR)

| # | 요구 | 측정 방법 |
|---|---|---|
| NFR-1 | 세로 스택 전환으로 **네트워크 요청이 늘지 않는다**. 백로그 초기 렌더의 요청 수는 현행과 동일 | Playwright `page.on('request')` 카운트 비교 |
| NFR-2 | 완료 다이얼로그가 추가하는 조회는 **`GET /api/v1/workflows` 1회뿐**이며, TanStack Query 캐시로 다이얼로그 재개봉 시 재요청하지 않는다 | 같은 방법 |
| NFR-3 | 이관 직렬 실행이 이슈 20건 기준 **5초 이내**에 끝난다 (mock 환경) | e2e 타이머 |
| NFR-4 | 접기 토글 클릭 → 화면 반영 **100ms 이내**. localStorage 쓰기는 렌더를 막지 않는다 | 브라우저 눈확인 + Performance 패널 |
| NFR-5 | 섹션 헤더의 액션(생성 진입점·시작/완료·번다운)은 **드롭 영역 밖**에 있어 드래그를 방해하지 않는다. 현행 구조(`BacklogColumn.tsx:83-84` 주석)를 승계 | 기존 DnD e2e 통과 |
| NFR-6 | 모바일(<640px)에서 헤더 액션의 터치 타깃이 **44×44 이상** | 브라우저 눈확인 (DevTools 디바이스 모드) |
| NFR-7 | 라이트·다크 양쪽에서 본문·상태 텍스트 대비 **WCAG AA 4.5:1 이상** | `DESIGN.md` §10 표에 이미 실측된 토큰만 사용해 충족 |
| NFR-8 | 새 사용자 문구는 **전부 `i18n/backlog-labels.ts`** 에 있다. 컴포넌트에 한국어 리터럴 0 | `grep -n "[가-힣]" apps/web/src/components/backlog/*.tsx` 가 주석 외 0 |
| NFR-9 | 다이얼로그 2종 + 새 lib 각각 **200줄 이내** (`DEVELOPMENT.md §2.2`) | `wc -l` |
| NFR-10 | 드래그 공지가 **한국어**이고 내부 id(`backlog:ATLAS-1` 형태)를 노출하지 않는다 | 유닛에서 `buildBacklogAnnouncements` 반환 문자열 단언 |

---

## API 인터페이스 (REST)

**신규 엔드포인트 0. 기존 엔드포인트 조합만 쓴다.**

| 용도 | 메서드·경로 | 실측 시그니처 | 출처 |
|---|---|---|---|
| 백로그 조회 | `GET /api/v1/projects/{projectKey}/backlog` | `{ data: { backlog, sprints, truncated } }` | `api/backlog.ts:171-177` |
| 기간·목표 수정 | `PATCH /api/v1/sprints/{id}` | `name`/`goal`/`startDate`/`endDate` 3-state partial + **`version` 필수** | `SprintController.kt:149-168` |
| 시작 | `POST /api/v1/sprints/{id}/start` | **파라미터 0** (path 만) | `SprintController.kt:200-209` |
| 완료 | `POST /api/v1/sprints/{id}/complete` | **파라미터 0** (path 만) | `SprintController.kt:221-230` |
| 스프린트에서 제거 | `DELETE /api/v1/sprints/{id}/issues/{issueKey}` | **멱등 204** | `SprintController.kt:268-278` |
| 스프린트에 할당 | `POST /api/v1/sprints/{id}/issues` | `{ issueKey }` → 201. **COMPLETED 대상이면 409** | `SprintController.kt:244-254` |
| 상태 카테고리 사상 | `GET /api/v1/workflows` | `{ data: WorkflowView[] }`, `states[].category` 3종 | `api/workflows.ts:79-85` · `WorkflowController.kt:52` |

**프론트 클라이언트에 없는 것 = 이번에 추가할 것.**
`api/backlog.ts` 에 `PATCH /sprints/{id}` 호출 함수가 **없다** (파일 전수 확인 — `createSprint`/`startSprint`/`completeSprint`/`assignToSprint`/`unassignFromSprint`/`rerankIssue`/`fetchBacklog` 7개뿐).
`updateSprint(sprintId, body)` 를 같은 파일에 추가하고, `hooks/use-backlog.ts` 에 `useUpdateSprint` 를 더한다.
기존 훅의 `invalidate-only` 규약(setQueryData 금지)을 그대로 따른다.

**상태 전이 계약 (백엔드 확정, 변경 불가).**
`PLANNED → ACTIVE → COMPLETED` 단방향 FSM. 위반 시 409 (`Sprint.kt:68-95` `InvalidSprintTransitionException`).

---

## 데이터 모델 변경

**없음.** 확정 사유 3개.

1. **DB 스키마 무변경.** 새 테이블·컬럼·인덱스가 없다. 이번 작업이 쓰는 저장소 구조는
   `V503__sprints.sql` 의 `sprints` · `sprint_issues` 그대로다. Flyway 마이그레이션 0건.
2. **API 스키마 무변경.** 시작 다이얼로그가 필요로 하는 `goal`·`startDate`·`endDate`·`version` 이
   백로그 응답의 `sprintMetaSchema`(`api/backlog.ts:52-67`)에 **이미 실려 있다**.
   `PATCH` 요청 body 타입은 프론트 쪽 신규 인터페이스일 뿐 백엔드 DTO 를 바꾸지 않는다.
3. **신규 엔티티·용어 0.** `스프린트`·`백로그`·`에픽` 은 `Maxi_wiki/BTS/glossary.md:19-21,117` 에 이미 있다.
   「세로 스택」·「시작 다이얼로그」·「이관」은 UI 배치·조작 용어라 유비쿼터스 언어 대상이 아니다.
   `Maxi_wiki/BTS/domain/agile-planning.md` 의 「스프린트 라이프사이클(계획/시작/종료/회고)」 서술이 그대로 유효하다.

**클라이언트 로컬 상태 1건 신설** (DB 아님). `localStorage` 키 `bts.backlog.collapsed.{projectKey}` —
비민감 UI 상태이며 `bts.theme`(`DESIGN.md` §9) 선례와 같은 등급이다.

---

## 엣지 케이스

| # | 상황 | 기대 동작 |
|---|---|---|
| **E1** | 스프린트가 0개 | 백로그 섹션 하나만 세로로 놓인다. 「스프린트 생성」 폼은 현행 위치를 유지한다 |
| **E2** | 백로그 이슈 0건 + 스프린트 이슈 0건 | 각 섹션이 현행 placeholder(`이슈 없음`)를 그대로 그린다. 에러가 아니다 (기존 T4-5 승계) |
| **E3** | 모든 섹션이 접혀 있다 | 헤더만 쌓인 화면이 된다. 드롭 대상이 없으므로 드래그를 시작해도 어디에도 놓을 수 없다. 카드 자체가 렌더되지 않으므로 드래그 시작 지점도 없다 — 교착이 아니다 |
| **E4** | 접힌 스프린트에 카드를 떨어뜨리려 한다 | 접힌 섹션은 `useDroppable` 을 렌더하지 않으므로 드롭 후보에 없다. 공지는 「드롭 가능한 영역을 벗어났습니다.」 |
| **E5** | COMPLETED 스프린트 섹션 | 현행대로 드롭 비활성 + `bg-muted/50 opacity-75`(`SprintColumn.tsx:100-107`). 시작/완료 버튼 없음. 접기는 가능 |
| **E6** | localStorage 가 막혀 있다(사생활 보호 모드·용량 초과) | try/catch 로 삼키고 **전부 펼침**으로 동작한다. 화면에 오류를 띄우지 않는다 |
| **E7** | 저장된 접힘 목록에 이제 없는 `sprint-{id}` 가 들어 있다 | 무시한다. 정리는 하지 않는다(다음 쓰기에서 자연히 걸러진다) |
| **E8** | 시작 다이얼로그에서 종료일 < 시작일 | 제출 전에 막고 필드 아래 `text-destructive` 에러를 띄운다. 백엔드도 같은 불변식을 갖지만(`Sprint.kt:28`) 왕복을 만들지 않는다 |
| **E9** | 시작 다이얼로그 열려 있는 사이 다른 사람이 같은 스프린트를 수정 | `PATCH` 409 → FR-4 의 409 분기. 백로그를 invalidate 하고 기준값·`version` 을 새 값으로 교체한 뒤 사용자에게 재확인을 요청한다 |
| **E10** | 시작 다이얼로그 열려 있는 사이 다른 사람이 스프린트를 이미 시작 | `POST /start` 가 409(`InvalidSprintTransitionException`) → 「이미 시작된 스프린트입니다.」 + 백로그 invalidate + 다이얼로그 닫기. 재시도 버튼을 주지 않는다(재시도해도 반드시 409) |
| **E11** | 완료 다이얼로그의 미완료가 0건 | 목록·Select 없이 「옮길 이슈가 없습니다.」. 제출은 `POST /complete` 1회 |
| **E12** | 이관 대상 스프린트가 이관 도중 COMPLETED 로 전이 | `POST /{targetId}/issues` 가 409 → 그 행이 「이관 실패」. 완료 미실행. 재시도 전에 대상을 다시 고를 수 있어야 하므로 Select 를 잠그지 않는다 |
| **E13** | 이관 중 이슈가 이미 스프린트에서 빠져 있다 | `DELETE` 가 멱등 204 라 성공으로 처리된다. 이어지는 `POST` 도 정상 |
| **E14** | 워크플로우 조회 실패 | FR-7 의 fail-safe — 전부 미완료로 보고 안내를 띄운다. 다이얼로그를 막지 않는다 |
| **E15** | `truncated=true` 인 상태에서 완료를 시도 | 완료 다이얼로그에 「일부 이슈만 표시되어 안전하게 완료할 수 없습니다.」 alert + 제출 버튼 비활성. 근거는 C1 |
| **E16** | 키보드 드래그 도중 Esc | 「취소했습니다.」 공지. mutation 0건 |
| **E17** | 키보드 드래그 도중 화면이 다시 조회돼 목록이 바뀐다 | dnd-kit 이 rect 를 재계산한다. 판정은 드롭 시점의 `backlogView` 로 하므로 마우스 경로와 동일한 위험도다. 별도 처리를 넣지 않는다 |
| **E18** | 스프린트 이름이 서로 같다 | 접기 토글 이름과 이슈 생성 진입점 이름이 충돌한다. **선재 문제**이며(현재도 `createIssueInSprint` 가 이름만으로 구분한다) 이 PR 은 악화시키지도 해결하지도 않는다. 로드맵 항목으로 남긴다 |
| **E19** | 이관 실행 중 사용자가 다이얼로그를 닫으려 한다 | 진행 중에는 오버레이 클릭·Esc 로 닫히지 않게 한다. 중간에 끊기면 어디까지 갔는지 알 수 없다 |

---

## 제약 조건

### C1. 완료는 되돌릴 수 없다 — 이 스펙의 가장 강한 제약

실측으로 확인한 3가지가 겹쳐 **COMPLETED 스프린트에 남은 이슈는 영구 동결된다**.

1. `POST /complete` 는 **상태만 뒤집는다.** 이슈를 백로그로 옮기지 않는다
   (`SprintApplicationService.kt:282-290` — `sprint.complete()` 후 `updateStatus` 뿐).
2. `DELETE /sprints/{id}/issues/{key}` 는 스프린트가 COMPLETED 면 **조용히 아무것도 하지 않고 204 를 반환한다.**
   `SprintRepository.kt:334-345` 의 `WHERE EXISTS (... AND SPRINTS.STATUS.ne(STATUS_COMPLETED))` 조건부 DELETE 다.
   호출자는 성공으로 오해한다.
3. 다른 스프린트로도 못 옮긴다 — `sprint_issues` 의 `UNIQUE (issue_key)`(`V503__sprints.sql:45-46`)에 걸려 409 다.

**따라서.** 이관을 완료보다 먼저 하는 것은 취향이 아니라 안전 요구다. 부분 실패 시 완료를 강행하지 않는 것,
미완료 판정을 안전측으로 기울이는 것, truncated 상태에서 완료를 막는 것이 모두 이 하나의 제약에서 나온다.

**부수 발견 (이 PR 에서 고칠 것).**
`hooks/use-backlog.ts:210-211` 의 주석 「완료 후 미완성 이슈는 백엔드에서 backlog로 이동시키므로」는 **거짓이다.**
위 1번이 반증한다. 같은 PR 에서 주석을 사실로 고친다 (코드 변경 없음).

### C2. 백엔드 변경 0줄

`backend/` 아래 파일을 한 줄도 고치지 않는다. D3(데이터 모델)·D4(백엔드)·D5(백엔드 테스트)는 「해당 없음」이다.

### C3. F16 은 범위 밖

필터바(`components/filters/FilterBar.tsx`)와 에픽 패널은 이 PR 에 넣지 않는다.
`CreateSprintForm` 을 백로그 섹션 헤더로 옮기는 재배치도 **F16 과 함께** 한다 —
지금 옮기면 `backlog.spec.ts:484,583` 의 `getByRole('form', { name: '스프린트 생성 폼' })` 2건과
유닛 2건이 이번 PR 의 다른 변경과 뒤섞여 원인 분리가 어려워진다.

### C4. 카드 내부 레이아웃 무변경

`BacklogCard.tsx` 의 2단 구조(제목 → 키·우선순위·담당자)를 바꾸지 않는다.
FR-UX-13 F5(#342)가 담당자 슬롯을 막 손봤고, 그 위에 바로 레이아웃 변경을 얹으면 회귀 원인이 겹친다.
전폭이 되면서 `line-clamp-2` 가 거의 발동하지 않게 되는 것은 무해하다.

### C5. 프리미티브만 쓴다

`DESIGN.md` §4 의 24종에서만 소비한다 — `Dialog` · `Button` · `Input` · `Textarea` · `Label` · `Select` · `Badge` · `Separator`.
새 프리미티브를 만들지 않는다. `radix-ui` 직접 import 를 새로 추가하지 않는다.

### C6. 토큰 무신설

`DESIGN.md` §2 의 기존 토큰만 쓴다. 신규 색·간격 토큰 0. 상세는 §시각 사양.

---

## 시각 사양 (토큰·타이포·간격)

전부 `DESIGN.md` 기존 토큰의 **재사용**이다. 신설 0 → `DESIGN.md` 패치 없음.

### 레이아웃

| 요소 | 클래스 | 근거 |
|---|---|---|
| 스택 컨테이너 | `flex flex-col gap-3` | §6 `3`=12px. 섹션 간 간격 |
| 섹션 | `w-full flex flex-col gap-2` | 기존 `gap-2` 유지 |
| 섹션 헤더 | `sticky top-0 z-10 flex items-center gap-2 rounded-t-lg bg-(--bg-neutral-solid) px-3 py-2` | 현행 유지(`SprintColumnHeader.tsx:63`). `--bg-neutral-solid` 는 §7 의 **가림용 불투명 뉴트럴**이라 세로 스크롤에서 특히 옳다 |
| 카드 목록 영역 | `flex flex-col gap-2 rounded-b-lg border border-border p-2 transition-colors` | 현행 유지. `flex-1` 은 제거한다(세로 스택에서 높이를 늘릴 이유가 없다) |
| 드롭 하이라이트 | `bg-accent ring-2 ring-primary` | 현행 유지 |
| COMPLETED 섹션 | `bg-muted/50 opacity-75` | 현행 유지 |

### 섹션 헤더 액션 (Jira 대조 G8 해소)

세로 스택에서는 폭이 남으므로 시작/완료·번다운을 **가로 한 줄**로 옮긴다.
`SprintColumnHeader.tsx:12` 의 `ACTION_BASE_CLASS` 에서 `self-start` 를 제거하고 나머지는 유지한다.

배치 순서 (왼→오). `접기 토글` · `이름` · `상태 배지` · `이슈 수` · **`flex-1` 스페이서** · `이슈 추가(아이콘)` · `시작 또는 완료` · `번다운`.

| 요소 | 클래스 | 상태색 |
|---|---|---|
| 접기 토글 | `Button variant="ghost" size="icon-xs"` + `aria-label` | — |
| 이름 | `text-sm font-semibold text-foreground` | 현행 유지 |
| 상태 배지 | `rounded-sm px-1.5 py-0.5 text-xs font-medium` | PLANNED `bg-info/10 text-info-text` · ACTIVE `bg-success/10 text-success-text` · COMPLETED `bg-muted text-muted-foreground` — 현행 유지(§C tint 패턴) |
| 이슈 수 | `rounded-sm bg-background px-1.5 py-0.5 text-xs text-muted-foreground` | 현행 유지 |
| 시작 버튼 | `Button variant="default" size="xs"` | `bg-primary` |
| 완료 버튼 | `Button variant="default" size="xs"` + `bg-success text-success-foreground hover:bg-success/90` | 현행 유지 (§C bold 패턴. `success` 는 Button variant 에 없어 className 이 tailwind-merge 로 덮는 것이 기존 관례) |

### 다이얼로그

| 요소 | 클래스 |
|---|---|
| 시작 다이얼로그 | `DialogContent` 기본 `max-w-lg` 그대로 |
| 완료 다이얼로그 | `DialogContent className="sm:max-w-2xl"` — 이슈 목록 때문 |
| 이슈 목록 | `max-h-72 overflow-y-auto rounded-md border border-border divide-y divide-border` |
| 목록 행 | `flex items-center gap-2 px-3 py-2 text-sm` |
| 행 상태 「이관됨」 | `text-xs text-muted-foreground` |
| 행 상태 「이관 실패」 | `text-xs text-danger-text` (§C tint 위 텍스트 규칙) |
| 인라인 alert | `rounded-md border border-warning bg-warning/10 px-3 py-2 text-sm` — truncated 배너와 같은 패턴 |
| 오류 alert | `rounded-md border border-destructive bg-destructive/10 px-3 py-2 text-sm text-danger-text` |
| 필드 라벨 | `text-sm font-medium text-foreground` (§5 사용 가이드) |
| 필드 에러 | `text-sm text-destructive` (§5 사용 가이드) |
| elevation | `DialogContent` 가 이미 `shadow-lg ring-1 ring-foreground/10` — §8 관례 그대로. 추가 그림자 금지 |

### 아이콘 (Lucide only, 24×24 기본)

| 용도 | 아이콘 |
|---|---|
| 섹션 펼침 | `ChevronDown` |
| 섹션 접힘 | `ChevronRight` |
| 이슈 생성 진입점 | `Plus` (기존 `CreateIssueEntryButton.tsx:3`) |
| 이관 성공 행 | `Check` |
| 이관 실패 행 | `TriangleAlert` |

이모지 금지.

### 상태 매트릭스 7종

**접기 토글**

| 상태 | 표현 |
|---|---|
| default | `ChevronDown`, `text-muted-foreground` |
| hover | `bg-(--bg-neutral-hover)` (ghost variant 기본) |
| active | `bg-(--bg-neutral-press)` |
| focus | `outline-2 outline-(--border-focus) outline-offset-2` |
| disabled | 없음 — 항상 활성 |
| loading | 없음 — 즉시 반영 |
| error | 없음 — localStorage 실패는 조용히 삼킨다 |
| empty | 이슈 0건이어도 토글은 있다 |

**시작/완료 트리거 버튼**

| 상태 | 표현 |
|---|---|
| default | 시작 `bg-primary text-primary-foreground` / 완료 `bg-success text-success-foreground` |
| hover | `hover:bg-primary/90` / `hover:bg-success/90` |
| active | 프리미티브 기본 |
| focus | `ring` 토큰 (프리미티브 기본) |
| disabled | `canManageSprint=false` 여도 **비활성으로 그리지 않는다** — 현행은 `onStart=undefined` 로 무동작이며 `BacklogBoard.test.tsx:442` 가 이 형태를 단언한다. 바꾸면 유닛이 깨진다 |
| loading | 트리거에는 없다. 진행 표시는 다이얼로그 안 제출 버튼이 맡는다 |
| error | 트리거에는 없다 |
| empty | 해당 없음 |

**다이얼로그 제출 버튼**

| 상태 | 표현 |
|---|---|
| default | 시작 `variant="default"` / 완료 `bg-success` |
| hover / active / focus | 프리미티브 기본 |
| disabled | 검증 실패(E8) · truncated(E15) · 진행 중 |
| loading | 이름이 「스프린트 시작 중…」/「스프린트 완료 중…」으로 바뀌고 비활성. 이관 진행 중에는 `{완료}/{전체}` 진행 수를 함께 보인다 |
| error | 버튼은 원래 이름으로 돌아오고, alert 가 뜨며, 옆에 `다시 시도` 가 나타난다 |
| empty | 미완료 0건이면 곧바로 완료만 수행 |

**완료 다이얼로그 이슈 목록**

| 상태 | 표현 |
|---|---|
| default | 행마다 키 + 제목 |
| hover | `hover:bg-(--bg-neutral-hover)` |
| active | 없음 — 행은 클릭 대상이 아니다 |
| focus | 없음 |
| disabled | 이관 진행 중 전체 목록이 `opacity-70` |
| loading | 워크플로우 조회 중에는 `Skeleton` 3행 |
| error | 행 단위 「이관 실패」 + 요약 alert |
| empty | 「옮길 이슈가 없습니다.」 (`text-sm text-muted-foreground`) |

### 반응형 4종

| 브레이크포인트 | 스택 | 섹션 헤더 | 다이얼로그 |
|---|---|---|---|
| **sm (~640px)** | 전폭 1열. 페이지 패딩 `p-6` 유지 | `flex-wrap` 으로 2행 허용. 액션 버튼에 `min-h-11`(44px) 적용 — NFR-6 | `DialogContent` 가 `w-full` 라 화면을 거의 채운다. 필드는 1열. 푸터는 `flex-col-reverse`(프리미티브 기본) |
| **md (~768px)** | 동일 | 1행 복귀. `min-h-11` 해제 | 시작 다이얼로그의 시작일·종료일이 `sm:grid-cols-2` 2열 |
| **lg (~1024px)** | 동일 | 동일 | 완료 다이얼로그 `sm:max-w-2xl` 적용 |
| **xl (~1280px)** | 동일. **최대 폭 제한을 두지 않는다** — Jira 백로그도 전폭이고, 폭 제한은 근거 없는 발명이 된다 | 동일 | 동일 |

### 접근성

- 섹션 `role="region"` + `aria-label` — 현행 문자열 보존 (FR-1).
- 접기 토글에 `aria-expanded` 와 `aria-controls`(카드 목록 div 의 id)를 준다.
- 다이얼로그 이름은 `DialogTitle` 이 `aria-labelledby` 로 연결한다 (FR-10).
- 각 다이얼로그에 `DialogDescription` 을 둬 `aria-describedby` 를 채운다. 시작 = 「기간과 목표를 확인한 뒤 스프린트를 시작합니다.」, 완료 = 「미완료 이슈를 옮긴 뒤 스프린트를 완료합니다.」
- 오류·경고는 `role="alert"`. 진행 상태는 `aria-live="polite"`.
- 드래그 공지·지침은 한국어 (FR-9).
- 대비. 쓰는 토큰이 모두 `DESIGN.md` §10 에서 AA 실측된 것들이다 — `text-danger-text` tint 위 4.9:1 이상, `--border-focus` 포커스 링.
- 키보드 순서. 섹션 헤더(토글 → 진입점 → 시작/완료 → 번다운) → 카드들. 다이얼로그는 Radix 가 포커스를 가둔다.

---

## 시각 검증 기준

계약 §6 에 따라 **브라우저 눈확인을 생략하지 않는다.**

### 영향 E2E — `grep` 결과 전수

`backlog|sprint` 언급 **12파일**, 백로그 라우트 참조 **30발생 / 10파일**.

| 파일 | 백로그 참조 | 이 PR 의 영향 판정 |
|---|---|---|
| `backlog.spec.ts` | 11 | **갱신 필수.** 아래 표 참조 |
| `timeline.spec.ts` | 5 | 라우트 이동만. **무영향 예상 → 동반 실행으로 확인** |
| `timeline-zoom.spec.ts` | 3 | 동일 |
| `sprint-burndown.spec.ts` | 2 | 번다운 링크 진입. 헤더 재배치가 링크를 옮기므로 **확인 필요** |
| `project-velocity.spec.ts` | 2 | 무영향 예상 → 확인 |
| `project-cfd.spec.ts` | 2 | 동일 |
| `project-cycle-time.spec.ts` | 2 | 동일 |
| `project-tree.spec.ts` | 1 | 동일 |
| `board-reorder.spec.ts` | 1 | 동일 |
| `issue-create-entry-points.spec.ts` | 1 | 백로그 섹션의 생성 진입점을 직접 조회. **헤더 재배치 영향 — 확인 필수** |
| `notification-policies.spec.ts` | 0 (문자열만) | 무영향 |
| `custom-fields.spec.ts` | 0 (문자열만) | 무영향 |

### `backlog.spec.ts` 갱신 범위 (11건 중)

| 시나리오 | 판정 | 이유·조치 |
|---|---|---|
| S1 백로그 재정렬 | **확인** | 같은 섹션 안 드래그. 좌표만 바뀜 |
| S2 백로그→스프린트 | **갱신 필요** | `dragCardToColumn`(`:204-231`)이 `boundingBox()` 좌표로 마우스를 움직인다. 세로 스택에서 대상 섹션이 뷰포트 밖이면 좌표가 화면 밖이 된다 → 드래그 전에 `scrollIntoViewIfNeeded()` 를 넣는다 |
| S3 스프린트→백로그 | **갱신 필요** | 같은 이유 (백로그가 맨 아래로 내려간다) |
| S5 스프린트 내 재정렬 | **확인** | 같은 섹션 안 |
| S6 스프린트 생성 | **확인** | `:501` 이 새 섹션의 `스프린트 시작` 버튼 존재를 본다. 트리거 이름을 보존하므로 통과해야 한다 |
| **S7 스프린트 시작** | **갱신 확정** | `:514-543` 이 「클릭 → 곧바로 ACTIVE 배지」를 기대한다. 다이얼로그가 끼면서 **확인 단계가 추가된다.** 갱신 내용 — ① 트리거 클릭 후 `getByRole('dialog', { name: '스프린트 시작' })` 가시성 단언 추가 ② 다이얼로그 안에서 제출 클릭 ③ 기존 Then 3줄(`:531-542` ACTIVE 배지 · 완료 버튼 등장 · 시작 버튼 소멸)은 **그대로 보존** |
| 초기 렌더 | **확인** | `:565` h1 · `:568-586` 칸/카드/배지/droppable. 순서를 안 보므로 통과 예상 |
| S9 담당자 아바타 | **확인** | 카드 내부 무변경 |
| S10 조회 실패 | **확인** | 조기 반환 경로 무변경 |
| S11 재시도 성공 | **확인** | 동일 |
| S12 재시도 실패 | **확인** | 동일 |

### 신규 E2E (`backlog.spec.ts` 에 추가)

| id | 시나리오 |
|---|---|
| S13 | 세로 스택 순서 — 스프린트 섹션이 백로그 섹션보다 **위**에 있다 (`boundingBox().y` 비교) |
| S14 | 섹션 접기 → 카드 사라짐 → 새로고침 후에도 접힌 채 |
| S15 | 시작 다이얼로그에서 종료일 변경 후 제출 → ACTIVE 배지 (S7 의 확장이 아니라 **값 변경 경로**) |
| S16 | 완료 다이얼로그 — 미완료 목록이 뜨고 이관 대상에 COMPLETED 스프린트가 **없다** |
| S17 | 완료 → 이관 후 완료. 요청 순서가 `DELETE`(들) → `POST /complete` 임을 확인 |
| S18 | 이관 부분 실패 → 완료 요청이 나가지 않고 다이얼로그가 남는다 |
| S19 | 키보드 DnD — Tab 으로 카드 포커스 → Space → ↓ → Space 로 이동 성공 |

### 신규 유닛

| id | 대상 | 단언 |
|---|---|---|
| T-DL-1 | 다이얼로그 이름 | 시작 다이얼로그가 열린 동안 **보이는** `스프린트 시작` 버튼이 정확히 1개 (FR-10 의 가정을 측정으로 바꾼다) |
| T-DL-2 | 2단계 요청 | 값 변경 시 `PATCH` → `start` 순서. 값 무변경 시 `PATCH` **미호출** |
| T-DL-3 | 중간 실패 | `PATCH` 200 + `start` 500 → 다이얼로그 유지 + 「기간·목표는 저장했지만」 문구. **「전부 실패」 문구가 화면에 없음**을 함께 단언 |
| T-DL-4 | 재시도 | T-DL-3 상태에서 재시도 시 `PATCH` 호출 수가 **늘지 않는다** |
| T-CP-1 | 대상 목록 | COMPLETED 스프린트가 Select 옵션에 **없다** |
| T-CP-2 | 순서 | 이관 요청이 모두 끝난 **뒤에** `complete` 가 호출된다 (호출 순서 배열 단언) |
| T-CP-3 | 부분 실패 | 3건 중 1건 실패 → `complete` 호출 수 **0** |
| T-CP-4 | 재시도 단위 | 재시도 시 성공했던 2건의 요청 수가 늘지 않는다 |
| T-WF-1 | 미완료 판정 | 카테고리 집합이 `{DONE}` 인 키만 완료. 미상·충돌 키는 미완료 |
| T-WF-2 | fail-safe | 워크플로우 조회 실패 시 전건 미완료 + 안내 문구 |
| T-KB-1 | 공지 | `buildBacklogAnnouncements` 반환 문자열이 한국어이고 `backlog:`/`sprint:` 접두 id 를 **포함하지 않는다** |
| T-KB-2 | 충돌 감지 | `cardFirstCollision` 에 `pointerCoordinates: null` 을 주면 **카드 droppable 이 칸보다 먼저** 반환된다 (FR-8 의 실측 결함을 봉인) |
| T-CL-1 | 접기 영속 | 접기 → 재마운트 후에도 접힘. localStorage 예외를 던져도 전부 펼침으로 렌더 |
| T-CL-2 | 텍스트 앵커 | 섹션의 `textContent` 가 여전히 섹션 이름으로 시작한다 (토글의 `sr-only` 침입 차단) |

### 갱신이 필요한 기존 유닛

| 파일·위치 | 조치 |
|---|---|
| `BacklogBoard.test.tsx:386` 「시작 버튼 클릭 시 useStartSprint.mutate를 호출한다」 | 다이얼로그 제출까지 거치도록 갱신 |
| `BacklogBoard.test.tsx:398` 「완료 버튼 클릭 시…」 | 동일 |
| `BacklogBoard.test.tsx:410` 「canManageSprint=false이면…」 | 다이얼로그가 열리지 않음을 단언하도록 갱신 |
| `BacklogBoard.test.tsx:442` 「onHandler 없이(undefined) 렌더된다」 | 형태 보존 확인 |
| `i18n/__tests__/create-entry-point-names.test.ts:55-73` | 신규 버튼 이름 등록 (FR-10) |
| `components/__tests__/button-primitive-usage.test.ts:46-77` | 파일 경로를 바꿨을 때만 갱신. **바꾸지 않는 것이 기본** (FR-11) |

### 브라우저 눈확인 (계약 §6, 생략 금지)

preview 프록시에서 **라이트·다크 양쪽** 확인하고 관찰 요지를 PR 본문에 남긴다.

1. 세로 스택 기본 화면 — 스프린트가 위, 백로그가 아래. 가로 스크롤 없음.
2. 섹션 헤더 액션이 한 줄로 정렬되고 sticky 가 카드 위를 제대로 가리는지 (`--bg-neutral-solid` 확인).
3. 섹션 접기/펼치기 애니메이션과 새로고침 후 복원.
4. 시작 다이얼로그 — 기본 상태 / 검증 에러 / 중간 실패 alert.
5. 완료 다이얼로그 — 미완료 있음 / 미완료 없음 / 이관 진행 중 / 부분 실패.
6. **모바일 폭(375px)** — 헤더 줄바꿈, 터치 타깃 44px, 다이얼로그 전폭.
7. 키보드만으로 카드 이동 (Tab → Space → ↓ → Space). macOS VoiceOver 로 한국어 공지 확인.
8. truncated 배너가 있는 상태에서 완료 버튼이 막히는지.

---

## 측정 가능한 완료 기준

- [ ] `BacklogBoard.tsx` 에 `overflow-x-auto` 가 **0회** 등장한다. (`grep -c "overflow-x-auto" apps/web/src/components/backlog/BacklogBoard.tsx` → 0)
- [ ] `min-w-72` 가 `components/backlog/` 에 **0회** 등장한다.
- [ ] 렌더 순서가 `sprints` → `backlog` 다. S13 이 `boundingBox().y` 로 증명한다.
- [ ] `backend/` 변경 **0줄**. (`git diff --stat origin/main -- backend/` 가 비어 있다)
- [ ] Flyway 마이그레이션 신규 **0건**.
- [ ] 신규 REST 엔드포인트 **0건**. 프론트가 호출하는 경로가 §API 표의 7개를 벗어나지 않는다.
- [ ] 버튼 이름 `스프린트 시작`·`스프린트 완료` 가 `backlog-labels.ts` 에서 **문자 그대로** 유지된다.
- [ ] `backlog.spec.ts` 11건 + 신규 7건이 전부 통과한다.
- [ ] 백로그 라우트를 참조하는 e2e **10파일 전량**을 동반 실행해 통과한다. 파일 목록을 PR 본문에 적는다.
- [ ] `BacklogBoard.test.tsx` 41건이 (갱신분 포함) 전부 통과한다.
- [ ] `create-entry-point-names.test.ts` 의 substring 판별식이 통과한다. 신규 버튼 이름이 집합에 **등록돼 있다**.
- [ ] `button-primitive-usage.test.ts` 가 통과한다 — 신규 컴포넌트에 원시 `<button>` 0.
- [ ] T-KB-2 가 **뮤테이션 검증을 통과한다** — `cardFirstCollision` 의 키보드 분기를 지우면 red 가 된다.
- [ ] T-DL-3 이 뮤테이션 검증을 통과한다 — 중간 실패 문구를 「전부 실패했습니다」로 바꾸면 red 가 된다.
- [ ] T-CP-3 이 뮤테이션 검증을 통과한다 — 부분 실패에도 `complete` 를 보내게 고치면 red 가 된다.
- [ ] 사용자 문구 하드코딩 0 (NFR-8 명령이 주석 외 0).
- [ ] 신규 파일 각각 200줄 이내.
- [ ] `DESIGN.md` 신규 토큰 **0** — 이 스펙이 쓰는 클래스가 전부 §2·§5·§6·§7·§8 의 기존 항목이다.
- [ ] 라이트·다크 눈확인 8항목을 수행하고 결과를 PR 본문 또는 게이트 2 요약에 남긴다.
- [ ] `hooks/use-backlog.ts:210-211` 의 거짓 주석이 사실로 고쳐져 있다.
- [ ] `bash scripts/verify-master-plan.sh` 통과.

---

## 미정 항목 — 결정이 필요한 것

### U1. truncated 상태에서 완료를 막을 것인가 — ✅ **해소 (Maxi 확정 2026-08-05)**

**막는다.** 스펙 기본값이 그대로 채택됐다. 아래는 결정 근거의 원본 기록이다.

착수 전 실측으로 상한값을 확인했다 — `IssueRepository.kt:1204`
`internal const val BOARD_CARD_FETCH_LIMIT = 1000`. 백로그와 모든 스프린트 이슈를 합쳐
1,000건을 넘을 때 `truncated` 가 선다.

- **스펙이 채택한 기본값. 막는다** (E15·FR-13).
  근거는 C1 — 화면에 안 보이는 이슈가 스프린트에 남은 채 완료되면 그 이슈는 되살릴 방법이 없다.
- **비용.** 백로그가 cap 을 넘은 프로젝트는 스프린트를 완료할 수 없게 된다.
  범위를 줄이는 필터는 **F16 에서 들어온다** — 그전까지는 탈출구가 없다.
- **대안.** 경고만 띄우고 진행을 허용한다. 사용자가 위험을 감수한다.
- **결정해야 할 사람.** Maxi.
- **구현 지침.** 어느 쪽으로 뒤집어도 한 곳만 고치면 되도록 **상수 1개**(`BLOCK_COMPLETE_WHEN_TRUNCATED`)로 분기하고, 두 동작 각각에 테스트를 붙인다.

### U2. 같은 `stateKey` 가 워크플로우마다 다른 카테고리를 갖는가

- FR-7 의 사상이 안전측이라 **기능은 어느 쪽이든 안전하다.** 영향은 완료 다이얼로그 요약의 「완료 N건」이 과소 표시될 수 있다는 것뿐이다.
- **확인 방법.** 개발 DB 에서
  `GET /api/v1/workflows` 응답을 받아 `states[].key` 별 `category` 집합 크기가 2 이상인 키가 있는지 센다.
- **결정 대상.** 충돌이 실재하면 「프로젝트의 워크플로우 스킴을 따라간다」(`GET /api/v1/projects/{key}/workflow-scheme` 경유, 요청 2회 추가)로 승격할지 여부.
  실재하지 않으면 현행 사상을 그대로 둔다.
- 이 확인은 구현 착수 시 **가장 먼저** 한다. 결과에 따라 FR-7 의 구현 난이도가 달라진다.
