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
- 중간 실패 문구는 **네 갈래로 분리한다**. 하나로 뭉뚱그리면 거짓말이 된다.
  | 실패 지점 | 문구 (신규 i18n 키) | 재시도 시 보내는 것 |
  |---|---|---|
  | `PATCH` 실패 (비-409) | 「기간·목표를 저장하지 못했습니다. 스프린트는 시작되지 않았습니다.」 | `PATCH` + `start` |
  | `PATCH` 409 | 「다른 사람이 먼저 수정했습니다. **입력하신 값은 그대로 두었으니** 확인 후 다시 시도해 주세요.」 (§I-15 로 정정) | 백로그 invalidate 후 **기준값·`version` 만** 교체하고 **폼 값은 보존** → 사용자가 재확인 |
  | `start` 실패 (비-409) | 「기간·목표는 저장했지만 스프린트를 시작하지 못했습니다.」 | `start` 만 (변경분이 0이므로 자동으로 그렇게 된다) |
  | **`start` 409** | 「이미 시작된 스프린트입니다.」 | **재시도 없음** — 백로그 invalidate + 다이얼로그 닫기 (E10) |

  ★ **네 번째 행은 2026-08-05 리뷰가 적발한 누락이다.** 원래 표가 「세 갈래」를 선언하면서
  `start` 실패를 하나로 묶었는데, **E10 이 정의한 409 처방은 나머지 셋과 정반대**다
  (다이얼로그를 닫고 재시도 버튼을 주지 않는다). 표가 스스로 "하나로 뭉뚱그리면 거짓말이
  된다"고 쓴 그 잘못을 표 자신이 저지르고 있었다.

- **기준값은 다이얼로그 내부 state 에 둔다.** 부모 props 를 그대로 기준값으로 쓰면
  `PATCH` 성공 후에도 props 가 바뀌지 않아(두 요청이 모두 성공해야 invalidate 하므로)
  재시도가 **낡은 `version` 으로 `PATCH` 를 다시 보내 409** 가 된다.
- **다이얼로그를 닫을 때 `PATCH` 만 성공한 상태였다면 백로그를 invalidate 한다.**
  안 하면 다시 열었을 때 기준값이 낡은 `version` 으로 리셋되고, 다음 `PATCH` 가 409 다.
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
  | 워크플로우 조회 자체가 실패했다 (또는 **아직 로딩 중**) | **전부 미완료** + **제출 차단** + 「상태 분류를 불러오지 못해 **안전하게 완료할 수 없습니다.** 모든 이슈를 미완료로 표시하고 있으니 잠시 후 다시 시도해 주세요.」 (§I-15 로 정정 — 원안은 「막지 않는다」였고 그것이 BLOCKER 였다) |
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

**상태 전환 계약 (백엔드 확정, 변경 불가).**
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
| **E7** | 저장된 접힘 목록에 이제 없는 `sprint-{id}` 가 들어 있다 | 무시한다. 정리는 하지 않는다 — 대응 섹션이 없어 무해하다 |
| **E8** | 시작 다이얼로그에서 종료일 < 시작일 | 제출 전에 막고 필드 아래 `text-destructive` 에러를 띄운다. 백엔드도 같은 불변식을 갖지만(`Sprint.kt:28`) 왕복을 만들지 않는다 |
| **E9** | 시작 다이얼로그 열려 있는 사이 다른 사람이 같은 스프린트를 수정 | `PATCH` 409 → FR-4 의 409 분기. 백로그를 invalidate 하고 기준값·`version` 을 새 값으로 교체한 뒤 사용자에게 재확인을 요청한다 |
| **E10** | 시작 다이얼로그 열려 있는 사이 다른 사람이 스프린트를 이미 시작 | `POST /start` 가 409(`InvalidSprintTransitionException`) → 「이미 시작된 스프린트입니다.」 + 백로그 invalidate + 다이얼로그 닫기. 재시도 버튼을 주지 않는다(재시도해도 반드시 409) |
| **E11** | 완료 다이얼로그의 미완료가 0건 | 목록·Select 없이 「옮길 이슈가 없습니다.」. 제출은 `POST /complete` 1회 |
| **E12** | 이관 대상 스프린트가 이관 도중 COMPLETED 로 전환 | `POST /{targetId}/issues` 가 409 → 그 행이 「이관 실패」. 완료 미실행. 재시도 전에 대상을 다시 고를 수 있어야 하므로 Select 를 잠그지 않는다 |
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

---

## 리뷰 반영 (2026-08-05) — 신규 요구와 정정

독립 리뷰 2종(구현 관점 · 설계 완결성)이 **BLOCKER 9 · MAJOR 20** 을 냈고, 그중 겹친 지적
5건과 내가 실측 재확인한 4건을 여기 반영한다. 위 본문과 충돌하는 부분은 **이 절이 이긴다**.

### Maxi 재결재 2건

**R1. `truncated` 완료 차단 — 유지한다. 단 근거를 정정한다.**
U1 결재 당시 근거였던 *"F16 이 오면 풀린다"* 는 **거짓이었다**. 실측 —
`BacklogController.kt:56-59` `getBacklog(@PathVariable projectKey)` 는 **쿼리 파라미터가 0개**이고,
`BacklogApplicationService` 는 `BoardIssueLookupPort.kt:94` 의 `BoardCardFilter` 오버로드를
**쓰지 않는다**. 정본 §4.11 도 F16 을 "프론트 전용 예상"이라 못박는다. 즉 F16 의 `FilterBar` 는
**이미 잘려서 도착한 응답을 클라이언트에서 다시 거를 뿐** `truncated` 플래그를 바꿀 수 없다.

정확한 비용은 「F16 전까지」가 아니라 **「백엔드가 범위 축소 수단을 갖기 전까지 영구히」**다.
Maxi 는 정정된 근거 위에서 **차단 유지**를 재확정했고, 대신 **백엔드 후속 항목을 정본에
등록**하기로 했다 (아래 R3).

**R2. 키보드 드래그 — 좌표 계산기를 직접 만든다.** 아래 FR-15.

**R3. 후속 항목 등록.** `docs/plan/product/personalization.md` §4.11 에
**「B3 — 백로그 조회 범위 축소(백엔드)」** 를 이 PR 에서 신설한다. `truncated` 를 실제로
내릴 수 있는 유일한 경로이고, 그때까지 cap 초과 프로젝트는 스프린트를 완료할 수 없다.

### FR-15. 키보드 좌표 계산기 (신규 · R2)

**문제.** `@dnd-kit/core@6.3.1` 의 `defaultKeyboardCoordinateGetter` 는 방향키 1회에
**25px** 만 움직인다 (`core.esm.js:1111` `x + 25` · `:1121` `y + 25`).
`grep -rn "coordinateGetter\|sortableKeyboardCoordinates" apps/web/src` → **0건**이고,
백로그 카드는 `useSortable` 이 아니라 `useDraggable`+`useDroppable`(`BacklogCard.tsx:152,157`)이라
`sortableKeyboardCoordinates` 도 쓸 수 없다.

**세로 스택 전환이 이 거리를 늘린다.** 섹션이 전폭·전고가 되므로 이슈 12건 섹션 하나가
800px 을 넘고, 옆 섹션까지 30~40회 방향키가 필요하다. 즉 S8·S19 는 **물리적으로 성립하지 않는다**.

**요구.** 새 순수 모듈 `apps/web/src/lib/backlog-keyboard-coordinates.ts` 를 만든다.

- 시그니처. `backlogCoordinateGetter: KeyboardCoordinateGetter` (dnd-kit 타입)
- `↑`/`↓` — **다음/이전 카드의 rect 중심**으로 점프한다. 섹션의 마지막 카드에서 `↓` 를
  누르면 **다음 섹션의 첫 카드**로 넘어간다. 섹션 경계 이동이 1회로 끝나야 한다
- `←`/`→` — 세로 스택에는 가로 이웃이 없으므로 **아무것도 하지 않는다**(현재 좌표 반환)
- 카드가 하나도 없는 섹션은 **섹션 droppable rect 중심**을 후보로 넣는다. 빈 스프린트로
  옮길 수 없으면 기능이 반쪽이다
- 후보 순서는 **화면에 그려진 순서**(스프린트들 → 백로그)와 같아야 한다. `BacklogView` 의
  배열 순서를 그대로 쓴다 — 백엔드가 이미 정렬해 내려주므로 클라이언트 정렬을 넣지 않는다
- **접힌 섹션은 후보에서 제외한다.** 카드 목록이 렌더되지 않으므로 rect 가 없다

**FR-8 의 카드 우선 분기와의 상호작용.** 좌표가 카드 중심으로 점프하므로 카드 droppable 이
정확히 하나 잡힌다. 리뷰가 지적한 *"25px 이동 중 항상 이웃 카드와 겹쳐 같은 섹션
rerank 로만 판정된다"* 는 문제가 이 설계에서는 발생하지 않는다.

**★ 집자마자 놓으면 맨 뒤로 가는 문제 (리뷰 M2).** `BacklogCard.tsx:162` 가
`useDroppable({ disabled: isDragging })` 이라 **자기 자신은 충돌 후보에서 빠지고**,
카드 사이는 `gap-2`(8px) 라 translate 0 에서는 어느 카드와도 겹치지 않는다. 그러면 칸
droppable 로 폴백하고 `extractColumnDropZone` 이 `dropIndex = orderedKeys.length` 를 줘서
(`lib/backlog-drag.ts:51`) **Space→Space 만 눌러도 카드가 맨 뒤로 이동한다**.
마우스는 5px 임계 때문에 이 경로가 없다.

- **요구.** 드래그 시작 좌표를 **집은 카드 자신의 rect 중심**으로 초기화하고, 이동이 0인
  상태의 드롭은 `resolveBacklogDropAction` 이 **noop** 으로 판정하도록 만든다.
- **T-KB-3(신규).** 집고 바로 놓으면 mutation 호출 수가 **0** 이다.

### FR-16. 키보드 활성화가 카드 안 링크를 삼키지 않는다 (신규)

**문제.** `KeyboardSensor.activators` 는 `active.activatorNode.current` 가 있고 이벤트 타깃이
그것과 다를 때만 조기 반환한다 (`core.esm.js:1343-1370`). 그런데 `BacklogCard.tsx:152` 는
`setActivatorNodeRef` 를 **구조 분해하지 않아** `activatorNode.current === null` 이고,
그 결과 **가드가 통과**한다. `{...listeners}` 는 카드 `div` 에 전개돼 있고(`:180-181`)
그 안에 이슈 상세로 가는 `<Link>`(`:193`)가 있어 keydown 이 버블한다.

**결과.** 이슈 링크에 포커스한 채 **Enter 를 누르면 `preventDefault()` 가 걸려 이슈로 가지
않고 드래그가 시작된다.** 키보드 사용자가 백로그에서 이슈를 여는 유일한 경로가 막힌다.

**선재성.** `BoardCard.tsx` + `KanbanBoard.tsx:526` 이 같은 구조라 **보드 화면에는 이미 있는
결함**이다. 그러나 백로그에서는 이 PR 이 여는 **새 충돌면**이고, 이 캠페인은
「선재 결함을 베끼지 않는다」를 원칙으로 삼아 왔다(F5 에서 확립).

**요구.** `keyboardCodes` 의 `start` 에서 **`Enter` 를 뺀다** — `Space` 만 드래그를 시작한다.
`KeyboardSensor` 옵션으로 `{ keyboardCodes: { start: [Space], cancel: [Esc], end: [Space, Esc] } }`
를 준다. **`end` 에서도 `Tab` 을 뺀다** — 기본값은 `end` 에 `Tab` 이 들어 있어
(`core.esm.js:1101`) 드래그 중 Tab 이 「드롭」으로 해석되는데, 이는 사용자가 기대하는
「포커스 이동」과 정반대다.

**T-KB-4(신규).** 카드 안 링크에 포커스한 채 Enter → 드래그가 시작되지 않는다.
**보드 화면은 이 PR 에서 건드리지 않는다** — 선재 결함은 별도 후속으로 남긴다.

### FR-17. 공지가 권한을 반영한다 (신규 · 리뷰 겹침 지적)

`use-backlog-drag.ts:71` 의 `if (!canReorderIssue) return` 이 키보드 경로도 막는 것은 사실이다
(FR-12 실측 정확). 그러나 FR-9 의 `buildBacklogAnnouncements(view)` 는 **권한을 모른다**.

**결과.** UPDATE 권한이 없는 사용자가 Space→방향키→Space 를 하면 화면은 그대로인데
스크린리더가 「스프린트 1 스프린트로 옮겼습니다.」를 읽는다. **mutation 은 0건이다.**
지금까지는 영어 기본 공지가 같은 거짓말을 했지만, 이 PR 이 「사용자 언어로 정확히 말한다」를
목표로 내걸었으므로 여기서 닫는다.

**요구.** 시그니처를 `buildBacklogAnnouncements(view: BacklogView, canReorderIssue: boolean)`
로 바꾸고, 권한이 없으면 `onDragEnd` 가 **「권한이 없어 이동할 수 없습니다.」**를 반환한다.
**T-KB-5(신규).** `canReorderIssue=false` 면 「옮겼습니다」류 문구가 **나오지 않는다**.

### FR-18. 픽스처 보강 — 가짜 그린 차단 (신규 · 리뷰 겹침 지적)

**실측.** `mocks/backlog-fixtures.ts` 에 `COMPLETED` 문자열이 **0건**이고
(`grep -c COMPLETED` → 0), 스프린트는 `PLANNED` 1개뿐이며(`:240`·`:324`),
`truncated` 는 `:255`·`:353` 에서 **`false` 하드코딩**이다.

따라서 아래 두 테스트는 **아무것도 재지 않고 통과한다**.

| 테스트 | 왜 공허한가 |
|---|---|
| T-CP-1 「COMPLETED 가 Select 옵션에 없다」 | COMPLETED 픽스처가 0개라 **자동으로 참** |
| E2E S16 (같은 단언) | 동일 |
| 눈확인 8번 · E15 「truncated 면 완료 차단」 | `truncated=true` 를 만들 수단이 **없다** |

`unreachable-state-fixture-is-fake-green` 양식이고, **PR #342 에서 2회 적발된 유형**이다.

**요구.**
- `DEFAULT_BACKLOG` 에 **COMPLETED 스프린트 1개 + ACTIVE 1개**를 추가한다. PLANNED 는 유지.
  이관 대상 Select 에 「백로그」 말고 실제 선택지가 있어야 S17(다른 스프린트로 이관)이 성립한다
- **`truncated` 시나리오 토글**을 `backlog-handlers.ts` 에 추가한다. 선례를 그대로 따른다 —
  `mocks/timeline-handlers.ts:49,102,133` 이 이미 `'truncated'` 토글을 갖고 있다
- **T-CP-1 을 짝 단언으로 강화한다.** 「COMPLETED 가 없다」 + **「PLANNED·ACTIVE 가 실제로
  들어 있다」** + 옵션 개수. 부정 단언 하나만으로는 공허를 못 막는다

### FR-19. 신규 파일을 판별식에 등록한다 (신규)

**실측.** `components/__tests__/button-primitive-usage.test.ts:80`
`SCANNED_FILES = [...BATCH1_FILES, ...BATCH2_FILES]` 는 `:16-38`·`:46-77` 의 **하드코딩 51파일**이고,
backlog 항목은 `:47-48` 의 `CreateSprintForm.tsx`·`SprintColumn.tsx` 둘뿐이다.

신규 다이얼로그 2종은 목록에 없으므로 **원시 `<button>` 을 100개 넣어도 초록**이다.
§측정 가능한 완료 기준의 「신규 컴포넌트에 원시 `<button>` 0」이 **아무것도 재지 않는다** —
정확히 `two-lists-never-check-each-other` 양식이다.

**요구.** `BATCH2_FILES` 에 `components/backlog/StartSprintDialog.tsx` ·
`components/backlog/CompleteSprintDialog.tsx` 를 **같은 PR 에서 추가한다.**
FR-11 은 「삭제·개명 금지」만 요구했지 **추가를 요구하지 않았다** — 그 누락이 이 결함의 원인이다.

### 정정 목록

| # | 원문 | 정정 |
|---|---|---|
| C-1 | §엣지 「15건」으로 인용된 곳 | **19건**이다 (E1~E19). plan 의 「엣지 15」도 함께 고친다 |
| C-2 | NFR-8 측정 `grep -n "[가-힣]" apps/web/src/components/backlog/*.tsx` | **lib 을 안 본다.** FR-9 는 "컴포넌트·**lib** 안 하드코딩 금지"라 쓴다. 글롭을 `apps/web/src/{components/backlog,lib,hooks}/*backlog*.{ts,tsx}` 로 넓히고 신규 4파일(`backlog-announcements.ts`·`backlog-completion.ts`·`backlog-keyboard-coordinates.ts`·`use-backlog-collapsed.ts`)을 포함시킨다 |
| C-3 | §접근성 「접기 토글에 `aria-controls`(카드 목록 div 의 id)」 + FR-2 「접히면 렌더하지 않는다」 | **서로 부순다** — 접힌 상태에서 dangling IDREF 가 된다. **`aria-expanded` 만 준다.** `aria-controls` 는 넣지 않는다 |
| C-4 | FR-9 `onDragEnd` 문구 **4종** | `resolveBacklogDropAction` 반환 kind 는 **5종**이다 — `noop-move`(`lib/backlog-drag.ts:203`)가 빠졌다. 「이동할 수 없는 위치입니다.」를 쓴다. T-KB-1 이 **5종 전수**를 단언한다 |
| C-5 | FR-9 「`resolveBacklogDropAction` 을 그대로 재사용해 공지와 mutation 이 어긋나지 않는다」 | **판정 함수는 같아도 입력이 다르면 어긋난다.** 실제 mutation 은 `use-backlog-drag.ts:79-93` 의 `extractColumnDropZone`/`extractCardDropZone` + `orderedKeys` 콜백으로 입력을 만든다. **그 입력 구성을 `lib/backlog-drag.ts` 로 올려 공용화**하고 두 경로가 같은 함수를 쓰게 한다 |
| C-6 | E2E S2·S3 처방 「드래그 전 `scrollIntoViewIfNeeded()`」 | **부족하다.** `dragCardToColumn`(`backlog.spec.ts:204-231`)은 출발·도착 boundingBox 를 **먼저 둘 다** 계산한 뒤 마우스를 움직인다. 대상만 스크롤하면 이번엔 출발 카드가 뷰포트를 벗어난다. **① 대상 섹션을 접어 높이를 줄이거나 ② 스크롤 후 좌표를 재계산하고 ③ 출발·도착 동시 가시성을 단언**한다 |
| C-7 | FR-6 이 다이얼로그 스냅샷으로 이관 후 곧바로 `complete` | **비가역 연산 직전 재검증이 0이다.** 그 사이 남이 이슈를 추가하면 목록에 없어 **영구 동결**되고, 남이 원본을 완료하면 `DELETE` 가 조용히 204 를 줘서 프론트가 전 행을 「이관됨」으로 **오판**한다(C1 스스로 경고한 함정). **요구 — `complete` 직전에 백로그를 재조회해 미완료가 0인지 확인하고, 아니면 완료를 중단하고 목록을 갱신한다** |
| C-8 | E11(미완료 0건 → 즉시 완료) vs E15(truncated → 제출 차단) | 우선순위 미정이었다. **E15 가 이긴다** — 목록이 불완전하면 「0건」이라는 관측 자체를 믿을 수 없다 |
| C-9 | FR-10 「접기 토글 이름 **1종** 등록」 | 섹션 A 는 접힘·B 는 펼침이 **동시에** 가능하므로 「같은 버튼의 다른 상태」 면제에 해당하지 않는다. **이름을 상태와 무관하게 고정**하고(`aria-expanded` 가 상태를 말한다) 1종을 등록한다 |
| C-10 | NFR-6(터치 44×44) vs 접기 토글 `size="icon-xs"`(24px) | 어긋난다. 토글은 **`size="icon"`(36px) + 모바일에서 `min-h-11 min-w-11`** 로 준다 |
| C-11 | §실측 방법 기록의 `grep -n "^  it("` → 41 | 그 명령의 실제 값은 **16**이다. 41 은 `grep -cE "^\s+it\("` 의 값이다. 명령을 고친다 |
| C-12 | §갱신이 필요한 기존 유닛 4건 | 실제로는 **깨지는 것 2건**(`:386`·`:398`)이고 `:410`·`:442` 는 `not.toHaveBeenCalled()` 라 그대로 통과한다(갱신이 아니라 **강화**). 그리고 **누락된 치명 항목 1건** — `BacklogBoard.test.tsx:98-201` 의 `vi.mock('@/hooks/use-backlog')` **팩토리에 신규 훅을 추가하지 않으면 41건이 통째로 red** 다 |
| C-13 | §갱신 표에 `BacklogColumn.test.tsx`·`SprintColumn.test.tsx` 없음 | plan 에는 T6 의 `files` 로 들어 있다. **스펙 표에도 추가**해 두 문서를 맞춘다 |
| C-14 | E5(COMPLETED 섹션) | 이관 대상 Select 에서 제외하는 것과 별개로, **완료 다이얼로그를 여는 트리거 자체가 없다**(시작/완료 버튼 미표시). 모순 없음 — 확인만 기록 |
| C-15 | FR-12 권한 게이팅 | 완료 다이얼로그의 **이관은 UPDATE 권한**을 쓴다(`POST /{id}/issues`·`DELETE` 둘 다 UPDATE, `complete` 만 CREATE). CREATE 만 있고 UPDATE 없는 사용자는 전건 403 을 받는데 FR-6 실패 처리에 403 이 없다. **완료 다이얼로그의 이관 UI 를 `canReorderIssue` 로도 게이팅**하고, 403 을 이관 실패 문구에 포함한다 |

### 미배정이었던 엣지 케이스 → 소유 task 지정

| 엣지 | 소유 |
|---|---|
| E7(없어진 sprint id) | T5 RED 에 추가 |
| E8(종료일 < 시작일) | T7 RED 에 추가 — 눈확인만으로는 부족하다 |
| E10(`start` 409) | T7 RED·GREEN (FR-4 네 번째 갈래) |
| E12(대상이 COMPLETED 로 전환) | T8 RED |
| E19(이관 중 닫기 차단) | T8 GREEN |
| E1·E3·E4(스프린트 0 · 전부 접힘 · 접힌 섹션 드롭) | T6 RED |

### 남은 미확인 2건

- **U2** — 같은 `stateKey` 가 워크플로우마다 다른 category 를 갖는 실데이터. 개발 DB 접근이
  이 세션에 없어 재지 못했다. **T2 착수 최우선**으로 남는다.
- **이슈 단위 가시성 제한이 이 배포에서 활성인지.** `BacklogApplicationService` 가
  `keys.mapNotNull { issueByKey[it] }` 로 **비가시 이슈를 조용히 떨어뜨리는데 `truncated` 는
  서지 않는다.** 활성이라면 C1 의 영구 동결이 `truncated` 가드를 우회하는 두 번째 경로가 된다.
  **T8 착수 시 확인하고, 활성이면 완료 다이얼로그에 같은 차단을 건다.**

---

## 구현 중 실측 정정 (wave 1, 2026-08-05)

구현 sub-agent 들이 착수해서 **스펙의 주장이 틀렸음을 실측으로 밝힌 것**을 여기 기록한다.
스펙 본문과 충돌하면 **이 절이 이긴다**.

### I-1. E7 괄호 문구 삭제 (T5 적발)

원문의 「다음 쓰기에서 자연히 걸러진다」는 **성립하지 않는다.** 토글은 목록 전체를 되쓰므로
사라진 `sprint-999` 는 영원히 남는다. 걸러지게 하려면 훅이 「지금 실재하는 스프린트 id 목록」을
인자로 받아야 하고 그건 다른 설계다. 명시 요구인 **「정리하지 않는다」가 정답**이고, 남은 id 는
대응 섹션이 없어 무해하다. 괄호를 삭제했다.

### I-2. T-KB-3 은 스펙이 지정한 기전으로 구현할 수 없다 (T11 적발)

FR-15 는 *"드래그 시작 좌표를 집은 카드 자신의 rect 중심으로 초기화"* 하라고 썼다.
**dnd-kit 실측 결과 좌표 계산기로는 이 경로에 손댈 수 없다.**

- `core.esm.js` `handleStart()` → `onStart(defaultCoordinates)` — 시작 translate 는 **무조건 `{0,0}`**
- `referenceCoordinates` 는 **첫 방향키 keydown 안에서** 설정되고 값은 `collisionRect` 의 **좌상단**이다
- **방향키를 0회 누르면 `coordinateGetter` 가 한 번도 호출되지 않는다**

즉 Space→Space 경로에 좌표 함수가 개입할 지점이 없다. **요구(결과)는 유지하되 기전을 바꾼다** —
`isZeroMoveDrop(delta)` 가드를 `lib/backlog-keyboard-coordinates.ts` 에 두고,
**`use-backlog-drag.ts` 의 `handleDragEnd` 에서 `setOverDroppableId(null)` 직후 호출**한다.

```ts
if (isZeroMoveDrop(event.delta)) return
```

**이 연결이 빠지면 T-KB-3 이 고쳐지지 않는다.** 소유는 `use-backlog-drag.ts` 를 가진 Task 1 이다.

### I-3. 키보드 경로에서 「카드 우선」은 원리적으로 3단계 폴백이다 (T11 실측 · T3 산출물과 정합)

`pointerWithin` 은 포인터 좌표가 없으면 **즉시 `[]`** 를 반환하므로, 키보드 드래그에서는
`cardFirstCollision` 의 1·2단계가 통째로 무의미하고 **항상 `rectIntersection` 폴백**으로 간다.
T3 이 구현한 `pointerCoordinates == null → rectIntersection` 분기가 정확히 이 사실과 일치한다.

**단 「카드 우선 분기가 키보드를 지켜준다」는 전제로 설계하면 안 된다.** 실제로 카드가 이기는
이유는 좌표 계산기가 카드를 대상 rect 에 **정확히 포개서** 교차비가 1.0 이 되기 때문이다
(칸 0.29 대비). 빈 스프린트 칸은 0.57 로 단독 1위다.

### I-4. U2 해소 — 시드 정본 기준 카테고리 충돌 **0건** (T2 실측)

`GET /api/v1/workflows` 실호출은 **미측정**(개발 백엔드 미기동, 서버를 임의로 띄우지 않았다).
대신 **제품에 실려 나가는 시드 정본**을 쟀다.

| 소스 | 결과 |
|---|---|
| `backend/modules/project-workflow/src/main/resources/workflows/*.yaml` 4종 | **충돌 0건.** 중복 키 3개(`in_progress` 3회 · `done` 3회 · `closed` 2회)가 전부 단일 카테고리 |
| `apps/web/src/mocks/workflow-fixtures.ts` | 동일하게 **0건** |

plan 의 「0건이면 현행 사상 유지」 분기 충족. 다만 워크플로우는 런타임 편집이 가능하므로
(`WorkflowController.kt:129` 변경 API 실재) 충돌 분기 자체는 **합성 픽스처**로 테스트를 붙였고,
합성인 이유를 테스트 주석에 남겼다.

### I-5. FR-19 를 「목록 추가」가 아니라 「디렉토리 도출」로 구현 (T1 판단 — 스펙보다 강하다)

FR-19 는 `BATCH2_FILES` 에 신규 다이얼로그 2종의 **경로를 손으로 추가**하라고 썼다.
T1 은 그러지 않고 `components/backlog/` 를 **`readdirSync` 로 도출**하게 만들었다.

**이유가 스펙의 의도보다 정확하다.** 경로를 손으로 적는 방식은 파일명이 한 글자만 달라도
조용히 공허해지는데, FR-19 자체가 바로 그 함정(`two-lists-never-check-each-other`)때문에
생긴 요구다. 이름을 적으면 같은 함정을 되풀이한다. 도출하면 **파일명이 무엇이든 생기는 즉시
스캔 대상**이 되고, T7·T8 이 만들 파일을 기다릴 필요도 없어 **wave 순서 문제까지 사라진다**.
비-공허 가드(`SprintColumn.tsx` 포함 · 길이 > 1 · 테스트 파일 제외)도 함께 넣었다.

### I-6. 다이얼로그 props 계약이 불완전했다 (T7·T8 적발)

plan 이 못박은 props(`open`·`onOpenChange`·`sprint`[+`allSprints`·`truncated`])는 같이 명령한
「훅을 직접 호출한다」와 **양립하지 않는다.**

- `useUpdateSprint(projectKey)`·`useStartSprint(projectKey)`·`useCompleteSprint(projectKey)` 가
  전부 `projectKey` 를 요구하는데 **`SprintMeta` 에 `projectKey` 필드가 없다**(`sprintMetaSchema` 실측)
- 전역 활성 프로젝트 경유는 `BacklogBoard.tsx` 주석이 **명시적으로 금지**한 경로다

→ **`projectKey` 를 두 다이얼로그 모두의 필수 prop 으로 추가**한다. 추가로
`CompleteSprintDialog` 는 **`canReorderIssue` 도 required** 다(C-15 게이팅 입력) — 기본값을 주지
않아 배선이 빠뜨리면 **컴파일 에러로 즉사**하게 했다.

### I-7. ★ 비가시 이슈 경로 — 실재하되 프론트에서 막을 수 없다 (T8 실측)

착수 최우선 확인 항목의 결론이다.

| 물음 | 답 |
|---|---|
| 드롭 경로가 실재하는가 | **그렇다.** `BacklogApplicationService.kt:144-150` `keys.mapNotNull { issueByKey[it] }` 가 `sprint_issues` 에 있으나 가시 목록에 없는 키를 조용히 버리고, `truncated` 는 카드 LIMIT 초과에서만 오므로 **서지 않는다** |
| 언제 활성인가 | `IdentityAccessIssueSecurityDirectory.kt:77-79` — **프로젝트에 이슈 보안 스킴이 배정됐을 때만**. `INSERT INTO project_security_scheme` 이 마이그레이션·시더에 **0건**이라 **신규 배포에서는 휴면** |
| 운영 인스턴스는 | **미확인.** 배정 여부는 런타임 DB 행이고 이 세션에서 관측할 수단이 없다. 추측하지 않는다 |
| 같은 양식이 또 있는가 | **있다 (신규 발견).** `IssueRepository.kt:955` 의 `ISSUES.DELETED_AT.isNull` — soft delete 된 이슈가 `sprint_issues` 에 남아 있으면 똑같이 조용히 빠지고 `truncated` 도 안 선다. 삭제된 이슈라 피해는 작지만 양식은 동일하다 |
| 프론트가 막을 수 있는가 | **없다.** 백로그 응답에 「몇 건이 빠졌는가」 신호가 **0개**다. 관측 불가능한 것에 가드를 달면 **그 가드 자체가 가짜 그린**이 된다 |

**후속 (B3 와 함께 백엔드 몫).** 응답에 `hiddenIssueCount`(또는 `sprint_issues` 키 수 대비 반환 수)를
실어야 프론트가 `truncated` 와 같은 차단을 걸 수 있다.

### I-8. 내가 인용한 jsdom 폴리필 선례가 틀렸다 (T8 적발)

plan·dispatch 가 인용한 `ResolutionPickerModal.test.tsx`·`GitWebhookRegisterDialog.test.tsx` 는
`hasPointerCapture` 를 폴리필하지 **않는다** — 실제로는 **`vi.mock('@/components/ui/select')` 로
네이티브 `<select>` 를 대체**한다. 정본 템플릿은 `PatCreateForm.test.tsx` 다.

부수 실측 — **vitest 의 MSW 기본 핸들러는 `test/handlers.ts` 이고 refresh 하나뿐**이다.
`mocks/handlers.ts`(전량)는 브라우저용이라 vitest 에 붙어 있지 않아, `GET /api/v1/workflows` 가
unhandled 로 전량 red 였다. 필요한 핸들러는 `server.use` 로 직접 깔아야 한다.

### I-9. E2E S1 도 갱신 범위다 (T6 실측 — 정본에 없던 항목)

세로 전환 후 `backlog.spec.ts` 의 **S1·S2·S3 이 red** 다. 브라우저 실측 좌표.

```
viewportH 720 / 스크롤 0
  스프린트 1 섹션.  top=415  bottom=647   → 화면 안
  백로그 섹션.      top=822  bottom=1055  → 화면 밖
```

`dragCardToColumn`(`backlog.spec.ts:204-231`)이 출발·도착 boundingBox 를 **먼저 둘 다 계산**한 뒤
마우스를 움직이는데, 백로그가 맨 아래로 가며 뷰포트를 벗어났다. 백로그가 안 끼는
**S5(스프린트 내부 재정렬)는 통과**한다.

§시각 검증 기준의 `backlog.spec.ts` 갱신 표는 S2·S3 만 「갱신 필요」로 적었다.
**S1 도 같은 원인이므로 T10 범위에 포함한다.**

### I-10. 셸 반응형 선재 결함 (T6 발견 — 이 PR 범위 밖)

375px 에서 본문 폭이 **111px** 로 짜부라진다. 셸 사이드바(264px)가 md 미만에서 접히지 않기
때문이다. **이 PR 이 건드리지 않은 보드·이슈 목록 화면도 `main=111px` 로 동일** —
**선재 결함**이며 원인이 이 변경이 아니다. 다만 세로 스택이 되면서 증상 모양이 바뀐다
(전에는 288px 고정폭이 보드 안쪽에서 가로 스크롤됐고, 지금은 섹션이 111px 로 짜부라진다).
수정 대상이 `components/layout/` 이라 **후속 항목으로 등록**한다.

### I-11. `completeDialog.staleSnapshot` 전용 문구 신설 권고 (T8)

C-7 재검증에서 「목록이 달라졌다」를 알릴 문구가 `completeDialog` 문구군에 없어
`startDialog.patchConflict`(「다른 사람이 먼저 수정했습니다…」)를 재사용했다. 뜻이 정확히
같고 하드코딩을 만들지 않기 위한 선택이다. **후속으로 전용 키를 신설**하는 편이 낫다.

### I-12. ★ 배선 후 실브라우저에서 드러난 결함 — 공지가 거짓말을 했다 (T9 발견 · T12 봉합)

**유닛으로는 잡히지 않고 실브라우저에서만 보인 결함이다.**

```
키보드로 카드를 집고 바로 놓기 (Space → Space)
  ⇒ 요청 0건 · 카드 위치 그대로       ← T-KB-3 가드 정상 작동
  ⇒ 낭독은 "순서를 변경했습니다."      ← 거짓말
```

원인. 이동-0 가드가 `use-backlog-drag.ts` 안에만 있어 `lib/backlog-announcements.ts` 가 그 사실을
모른다. **FR-9 가 못박은 「공지와 mutation 이 어긋나지 않는다」 원칙에서 이동-0 판정만 빠져 있었다.**

**봉합의 핵심 통찰 — 제시된 두 선택지가 배타적이지 않다.**
`Announcements.onDragEnd` 는 `{ active, over }` 만 받는데(`@dnd-kit/core@6.3.1`
`Accessibility/types.d.ts:10`), **그 둘만으로는 원리적으로 구별할 수 없다** — 이동-0 드롭과
「칸 아래 빈 공간에 정상 드롭」이 **같은 `over`(칸 droppable)** 를 낸다. 따라서 전송 경로는 우회가 없다.

→ **판정 위치는 ①**(`resolveOverToDropZone` 이 `isZeroMove` 를 받아 `null` 반환),
**전송은 ②의 최소형**(`useBacklogDrag` 가 안정 참조 getter 를 내보냄). 전송하는 값은 raw delta 가
아니라 **이미 계산된 boolean** 이라 두 소비자가 다른 답을 낼 구조적 여지가 없다.

**`resolveBacklogDropAction` 이 아니라 `resolveOverToDropZone` 을 고른 실측 근거.**
`BacklogDropInput` 에 required 필드를 넣으면 `lib/backlog-keyboard-coordinates.test.ts:308` 이 깨지는데
그 파일은 허용 범위 밖이었다. `resolveOverToDropZone` 은 호출부가 전부 범위 안이라 3번째 인자를
**required** 로 둘 수 있고, **required 여야 tsc 가 두 소비자 모두에게 전달을 강제한다** —
optional 로 두면 가드가 조용히 공허해지는 `two-lists-never-check-each-other` 양식이 된다.

**부수 봉합.** 마우스 zero-move 가드도 함께 열었다. 마우스도 5px 임계를 넘긴 뒤 원위치로 돌아와
놓으면 delta 0 이 되고, 그때 카드 자신의 droppable 이 `disabled: isDragging` 이라 칸으로 폴백해
**카드가 맨 뒤로 날아간다**. T9 이 조건 제거 후 185/185 초록임을 실측했고 T12 가 반영했다.

**증인 — 봉합 전 상태를 일부러 재현했다.** 배선만 끊자 원 결함이 그대로 재현됐다
(`"순서를 변경했습니다."` + 쓰기 요청 0건). 봉합 후 라이브 리전 실측.

```
[light] 집자마자 놓기 → "변경 사항이 없습니다."   · 쓰기 요청 0건 · 순서 유지
[light] 실제 이동    → "순서를 변경했습니다."     · PATCH /issues/ATLAS-5/rank 1건   ← 짝 단언
[dark]  집자마자 놓기 → "변경 사항이 없습니다."
```

### I-13. 인계 과장 1건과 「영구 공허」 단언 발견 (T9 실측)

- **「`vi.mock` 팩토리에 `useUpdateSprint` 를 안 넣으면 41건 통째 red」는 과장이었다.**
  실측은 **2건**이다 — 그 export 는 시작 다이얼로그가 **마운트될 때만** 닿는다.
  나머지 지적(어느 2건이 깨지고 어느 2건이 안 깨지는가)은 정확했다
- **배선 교체가 기존 단언 2건을 「영구 공허」로 만들었다.** 권한 테스트의
  `expect(mockStartSprintMutate).not.toHaveBeenCalled()` 는 이제 **그 `.mutate` 를 부르는 코드가
  존재하지 않아** 무엇을 해도 통과한다. 실질 단언(「다이얼로그가 열리지 않는다」 + `mutateAsync` 스파이)으로 교체했다.
  **봉합이 가드를 눈멀게 하는 양식**(`seal-blinds-existing-guard`)의 재현이다

### I-14. 남은 관찰 3건 (결함 아님 · 판단 필요)

1. **`lib/backlog-announcements.ts` 219줄** — NFR-9 의 「새 lib 200줄 이내」 초과. **선재**다
   (T12 직전 커밋 `aa8a46755` 실측 201줄). REFACTOR 로 223→219 까지 줄였으나 분할은 범위 밖이었다.
   **NFR 측정 시 판단 필요**
2. **dnd-kit 호출 순서 의존** — 「`DndContext` 의 `onDragEnd` prop 이 접근성 모니터보다 먼저」는
   고정 버전 소스(`core.esm.js:3164-3171`)와 브라우저로 확인했지만 `BacklogBoard.test.tsx` 가
   `DndContext` 를 mock 하므로 **유닛으로는 증명되지 않는다.** 가정을 주석으로 명시했다.
   깨져도 mutation 은 정확하고 공지만 한 박자 밀린다(= 봉합 전과 동급, 더 나빠지지 않음)
3. **섹션 경계를 넘는 데 방향키가 실제로는 2회** 필요하다 — 1회째를 컨테이너 자동 스크롤이
   흡수한다. T11 의 순수 함수 단언(「1회로 경계를 넘는다」)은 함수 수준에서 참이지만 화면에서는 다르다.
   기능은 정상이라 결함으로 올리지 않는다

---

## 코드 리뷰 BLOCKER 봉합 (2026-08-05~06) — I-15

게이트 2 의 독립 리뷰가 **BLOCKER 3건**을 냈고 전량 봉합했다 (Maxi 확정). 상세 목록은
plan 의 §코드 리뷰 결과. 여기에는 **스펙 본문을 뒤집는 것**만 남긴다.

### I-15-A. Esc 는 취소다 — `end` 에 넣지 마라 (스펙 §리뷰 반영 FR-16 의 처방 오류)

FR-16 이 처방한 `end: [Space, Esc]` 가 **틀렸다.** dnd-kit 의 `KeyboardSensor.handleKeyDown` 은
`end` 를 **먼저** 검사하고 즉시 `return` 하므로(`core.cjs.development.js:1196-1203`)
**`cancel: [Esc]` 가 도달 불가한 공허 가드**가 되고, Esc 가 취소가 아니라 **드롭(mutation 발사)** 이 된다.

- 정본 값. **`{ start: [Space], cancel: [Esc], end: [Space] }`** — Enter·Tab 제외는 유지(그 판단은 옳았다)
- **`end` 와 `cancel` 의 교집합이 공집합**임을 단언하는 테스트가 이 계약을 지킨다.
  원래 테스트는 「`end` 에 Esc 가 있다」와 「`cancel` 이 `[Esc]` 다」를 **각각** 단언할 뿐
  교집합을 재지 않았다 — `two-lists-never-check-each-other` 가 **테스트 안에서** 재현된 것이다
- E2E **S20** 이 실동작을 잰다 — Esc → 「취소했습니다.」 + 쓰기 요청 0건,
  **짝으로 Space → 요청이 실제로 나간다**(기록기가 살아 있음을 증명)
- `DndContext` 에 **`onDragCancel` 배선**도 함께 넣었다. 없으면 취소 후 섹션 하이라이트가 남는다

### I-15-B. 분류를 못 믿으면 완료를 막는다 (FR-7 의 근거 오류)

FR-7 은 *"과다 포함은 사용자가 목록에서 확인하고 대상을 바꿀 수 있으므로 손실이 없다"* 며
조회 실패 시에도 **「다이얼로그를 막지 않는다」**고 했다. **그 근거가 틀렸다** — 확인 없이 누르면
`unavailable → 전건 미완료 → 전량 DELETE` 로 **완료된 이슈까지 반출**되고, 완료된 스프린트에는
되돌려 넣을 수 없어(`assignIssue` 조건부 INSERT → 409) **성과 기록이 영구히 0** 이 된다.
RED 단계에서 **`DELETE` 4건이 실제로 발생**(완료된 `ATLAS-9` 포함)하는 것을 측정했다.

- **`truncated` 와 동일하게 차단한다** (Maxi 확정). 로딩 중도 포함
- 차단 조건은 **`categoryMap.unavailable` 하나**로 둔다. `isPending ⟹ data === undefined` 이므로
  `workflows.isPending || unavailable` 로 쓰면 앞 항이 뒤 항에 흡수돼 **도달 불가 분기**가 생긴다
- 기존 E14 테스트가 `expect(submitButton()).not.toBeDisabled()` 로 **위험한 동작을 못박고 있었다**.
  「차단된다」로 뒤집고 **「제출을 시도해도 `DELETE` 0건」**을 함께 단언한다

### I-15-C. 409 는 사용자 입력을 파기하지 않는다

`setValues(toFormValues(fresh))` 가 사용자가 친 기간·목표를 서버 값으로 덮어써서, 그 뒤
「다시 시도」가 변경분 0으로 `PATCH` 를 건너뛰고 **남의 값으로 스프린트를 시작**했다.
**기준값과 `version` 만 갱신하고 폼 값은 보존한다.**

★ **이 분기는 테스트에서 실행 자체가 불가능했다** — 하네스가 빈 `QueryClient` 를 써
`getQueryData` 가 항상 `undefined` 였다. `queryClient.setQueryData` 로 백로그를 심어
**처음으로 도달**시켰고, 짝 단언(`version: 9` + `goal: '내 목표'`)으로 「기준값은 갱신됐고
입력은 살아남았다」를 동시에 증명한다. 하나만으로는 증명이 안 된다.

### I-15-D. 문구 3종 정합 (봉합이 문구를 거짓으로 만들었다)

동작이 바뀌면 안내도 바뀌어야 한다 — 이 PR 은 「안내가 사실과 다른 것」을 BLOCKER 로 다뤘고
같은 기준을 자기 수정에도 적용했다.

| 키 | 변경 |
|---|---|
| `completeDialog.workflowLoadFailed` | 차단 사실 + 처방을 함께 말한다. `truncatedBlocked` 의 결만 따르고 **값은 재사용하지 않는다**(「일부 이슈만 표시되어」가 이 상황에선 거짓) |
| `startDialog.patchConflict` | 「최신 값을 불러왔으니」 → **「입력하신 값은 그대로 두었으니」** |
| `completeDialog.staleBlocked` | **신설.** 완료 다이얼로그의 `case 'stale'` 이 `startDialog.patchConflict` 를 **빌려 쓰고 있었는데**, 위 변경으로 **입력창이 없는 화면에서 「입력하신 값」이 새 거짓말**이 될 뻔했다. 원인이 셋(재조회 실패 · 그 사이 미완료 증가 · `complete` 실패)이라 원인을 단정하지 않고 셋이 공유하는 참인 사실만 말한다 |

### 함께 닫은 가짜 합격 2건

- **`collisionDetection` 미측정.** 테스트 mock 이 `sensors`·`accessibility` 만 캡처하고 세 번째
  배선 prop 을 버려서, `backlog-collision.ts` 를 전면 재작성했는데도 **배선 한 줄을 지우면
  1,615줄 파일이 전량 초록**이었다. 같은 파일이 *"mock 이 이 둘을 버리면 배선을 삭제해도
  전량 초록으로 남는다"* 고 **직접 경고해 놓고** 한 칸 옆에서 밟았다. 이제 캡처+단언한다
- **E14 테스트** (위 I-15-B)

### 남은 관찰 1건 (후속 판단)

**로딩 중 요약 건수가 잠깐 사실과 다르게 보인다.** 워크플로우 도착 전 「완료 0건 · 미완료 N건」을
먼저 그리고, 분류가 오면 숫자가 바뀐다. 안전측 판정의 부산물이고 Skeleton + 잠긴 버튼이
로딩임을 알려 **조작 위험은 없다.** 렌더 구조 변경이 필요해 이번 범위 밖으로 뒀다.

### I-16. 봉합이 문제를 「옮겼다」 — 남의 저장분 덮어쓰기 (재검증 발견 · Task 16 봉합)

I-15-C 가 「내 입력이 사라진다」를 고쳤는데, `setBaseline(fresh)` 로 기준값만 갈아끼우면서
**「내가 고친 필드」와 「기준값과 다른 필드」가 더 이상 같은 집합이 아니게 됐다.** 그런데
`buildPatchBody` 는 후자로 계산한다 → **내가 안 건드린 필드에 대해 남의 저장분이 `null` 로 전송된다.**
잃는 것이 미저장 폼 입력에서 **이미 저장된 남의 데이터**로 바뀌어 성격이 더 나쁘다.
`api/backlog.ts` 가 3-state partial 을 도입한 목적(*"바뀌지 않은 필드를 습관적으로 실어 보내면
남의 수정을 덮어쓴다"*)을 **한 층 위에서 재현**한 것이다.

**정본 처방 — 전송 대상은 「기준값과 다른 필드」가 아니라 「사용자가 실제로 편집한 필드」다.**

★ **GREEN 설계가 공허했고 REFACTOR 가 뒤집었다 — 이 PR 에서 가장 값진 자기 적발이다.**
GREEN 은 편집 집합 가드 + `mergeUnedited`(409 뒤 미편집 칸의 **폼 state 를 서버값으로 갱신**)를
함께 넣었는데, 뮤테이션 테스트에서 **가드를 제거해도 새 테스트가 초록**이었다 —
폼 state 를 서버값으로 맞춰 버리니 두 집합이 **다시 우연히 일치**해 가드가 **아무것도 지키지 않는
장식**이 됐다(`unreachable-state-fixture-is-fake-green` 양식).

→ REFACTOR 에서 **사본을 없애고 파생으로** 바꿨다. 미편집 칸의 **표시값을 렌더 때 계산**하고
(`resolveDisplayValues(values, baseline, edited)`) `replaceBaselineFromCache` 는 `setBaseline(fresh)`
한 줄로 되돌렸다. I-15-C 의 「폼을 덮지 않는다」가 문자 그대로 유지되고,
**표시 갱신이 편집으로 오인될 여지가 구조적으로 사라진다**(편집 경로는 `onEdit` 하나뿐).

**미편집 필드의 화면 표시 = 기준값(서버 최신).** 근거 셋.
① 충돌 안내가 "확인 후 다시 시도"라고 말하는데 낡은 빈 칸을 보여주면 확인할 대상이 없다 —
지금 화면은 **재시도 후의 결과와 정확히 같다** ② E8 기간 검증이 서버의 실제 종료일로 판정한다.
갱신하지 않으면 「종료일이 시작일보다 빠른」 스프린트를 경고 없이 만들 수 있다
③ 복사가 아니라 **파생**이라 전송 대상에 섞이지 않는다.

**픽스처를 먼저 갈아야 잡힌다.** 기존 `SERVER_SIDE` 는 서버가 바꾼 필드와 사용자가 친 필드가
`goal` 하나로 **겹쳐** 이 결함을 원리적으로 못 잡았다. 새 픽스처 `OTHERS_SAVED`
(서버는 `goal`+`endDate`, 사용자는 `startDate`)로 조합을 분리한 뒤에야 red 가 났다.

**브라우저 실측 (MSW 목이 낙관적 잠금을 실제 구현하고 있어 목 코드 무변경으로 409 재현).**
```
PATCH #0 (동료)   {"version":0,"goal":"Q3 목표","endDate":"2026-09-30"}  → 200
PATCH #1 (내 제출) {"version":0,"startDate":"2026-09-01"}                 → 409
PATCH #2 (재시도)  {"version":1,"startDate":"2026-09-01"}                 → 200
최종 서버 상태     goal="Q3 목표" · endDate="2026-09-30" · startDate="2026-09-01"
```
`#2` 에 **`endDate`·`goal` 키가 아예 없다.** 동료 저장분이 살아남았고 내 시작일도 반영됐다.
