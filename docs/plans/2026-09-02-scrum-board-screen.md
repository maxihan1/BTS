# 스크럼 보드 화면 + 백로그 보드 스코프 (FR-BD-04 D6 잔여 · D7 · 로드맵 PR ③)

> 티어: T2
> slug: scrum-board-screen
> type: feature
> agent: backend-engineer
> 생성: 2026-09-02

## Brief

**사용자 원문.** `FR-BD-04 PR ③ — 스크럼 보드 화면 + 백로그 ?board= 스코프 + D7 E2E`

**이 PR 이 하는 것.** ADR 3분할의 **PR ③** — 스크럼 보드가 **활성 스프린트의 이슈만** 보여주고,
백로그가 **보드 단위**로 스코프된다. 이것으로 **FR-BD-04 가 완주**한다(D6 잔여 + D7).
설계 정본 [ADR 2026-09-01 board-type-and-active-sprint](../adr/2026-09-01-board-type-and-active-sprint.md)
**§D2**(스프린트·백로그는 보드 소속) · **§D3**(스크럼 보드 화면 = 활성 스프린트만) · **§D6**(다수 보드 유지).

**왜 지금인가.** ①(#421 스키마·백엔드)과 ②(#422 종류 선택 UI)가 머지됐지만 **화면 의미가 아직 안 갈렸다** —
스크럼으로 보드를 만들어도 칸반과 똑같이 보인다. `GET /boards/{id}` 가 `boardType`·`activeSprint` 를
내주는데 화면이 안 읽는다. ADR §D3 이 약속한 「스프린트를 시작하면 보드가 바뀐다」가 이 PR 로 성립한다.

**classify 결과.** `type=backend`(판별식) · `agent=backend-engineer` · `primary_bc=agile-planning` ·
`tier=T1`(판별식 실측 — 제목에 백엔드 신호가 없어 낮게 나왔다) — **선언은 T2**.

**FR.** `FR-BD-04` **D6 잔여 + D7**. 신규 FR 없음 → **총수 144 불변**.
**이 PR 로 D6·D7 체크박스가 `[x]` 가 되고 FR-BD-04 가 완주한다** — 진척 표기가 바뀌는 첫 PR 이다
(②는 D6 「일부」라 표기 불변이었다). `agile-planning` 진척 열 `☐ D단계 (BD-04)` → `☑`.

**마이그레이션 0**(스키마는 #421 이 완료) · **신규 의존성 0**.

### 티어 — 선언 T2 · 실측 T2

ADR 3분할 표가 ③ 을 **T2** 로 지정했고, ②와 달리 **백엔드 `main` 이 실제로 붙어** 실측도 T2 다.
`classify-task.ts` 가 T1 을 돌려준 것은 제목 문자열에 백엔드 신호가 없기 때문이고, 표면이 기준이다.

## Jira 대조 (전 타입 필수)

`jira-parity-contract.md` §1 5단계 산출물. **§1-0 재사용** — ADR §「Jira Cloud 실물 조회」가
**2026-09-01 에 조회한 J1~J13 을 출처·조회일 그대로 승계**한다. 전 행 **Cloud company-managed** 기준.

| # | 원문 인용 | 출처 | 조회일 |
|---|---|---|---|
| **J5** | *"Your board only displays work items once you've started the sprint, and **the board displays only the work items added to the sprint you started**."* | [plan-a-sprint](https://support.atlassian.com/jira-software-cloud/docs/plan-a-sprint/) | 2026-09-01 |
| **J6** | 카드가 보드에 뜨는 조건 3개 — 상태가 컬럼에 매핑 · **"is in an active sprint (for Scrum boards)"** · 보드 필터에 일치. *"Active sprints are only available on Scrum boards."* | [use-active-sprints](https://support.atlassian.com/jira-software-cloud/docs/use-active-sprints/) | 2026-09-01 |
| **J7** | 백로그 화면 순서 — *"**Scroll down past any currently existing sprints** to the section titled **Backlog**."* → 스프린트가 위, 백로그가 아래 | [create-sprints](https://support.atlassian.com/jira-software-cloud/docs/create-sprints-in-company-managed-projects/) | 2026-09-01 |
| **J10** | Start sprint 다이얼로그 = 이름·시작일·종료일·목표(선택). 시작 후 **Active sprints 페이지로 이동** | [plan-a-sprint](https://support.atlassian.com/jira-software-cloud/docs/plan-a-sprint/) | 2026-09-01 |
| **J12** | 칸반도 백로그를 가질 수 있다(kanplan) — *"Traditionally, teams that work in a kanban style don't use a backlog…"* | [enable-the-backlog](https://support.atlassian.com/jira-software-cloud/docs/enable-the-backlog/) | 2026-09-01 |
| **J14** | *"Company-managed projects are less tightly tied to boards than team-managed projects, and **backlogs are part of the board, not the project**."* | [enable-the-backlog](https://support.atlassian.com/jira-software-cloud/docs/enable-the-backlog/) (community 답변 경유 확인) | 2026-09-02 |
| **J15** | *"A scrum board is always made up of **two parts** - the backlog and the active-sprint board."* | 〃 | 2026-09-02 |
| **J16** | REST 경로가 boardId 를 담는다 — `POST /rest/agile/1.0/backlog/{boardId}/issue` · `/rest/software/1.0/board/{boardId}/backlog` | [rest api-group-backlog](https://developer.atlassian.com/cloud/jira/software/rest/api-group-backlog/) | 2026-09-02 |
| **J17** | 화면 진입은 **탭 하나** — *"Select the **Backlog** tab from your space navigation of your scrum space."* | [use-your-scrum-backlog](https://support.atlassian.com/jira-software-cloud/docs/use-your-scrum-backlog/) | 2026-09-02 |
| **J18** | *"The **backlog** of a Scrum board shows the work items for your space grouped into a backlog and sprints."* · *"When you're happy with the work items for the sprint, select **Start sprint**, and the stories will move into the **Active sprints** view."* | 〃 | 2026-09-02 |

> **★ J14~J18 은 이 PR 에서 새로 조회했다** (2026-09-02 · Maxi 지시).
> 백로그를 보드 단위로 스코프하는 것이 Jira 모델과 맞는지가 설계를 갈랐기 때문이다.

### 채택

- **J14·J15·J16 — 백로그는 보드에 속한다.** BTS 는 지금 `GET /projects/{key}/backlog` 로
  **프로젝트 소속**이고, 그것이 ADR §D2 가 지목한 갭이다. 이 PR 이 그 모델을 옮긴다.
- **J17 — 진입은 탭 하나다.** 사용자가 URL 에 boardId 를 타이핑하지 않는다.
  **그래서 nav 「백로그」 링크를 바꾸지 않는다** — 보드 컨텍스트는 폴백과 스위처가 정한다.
- **J18 — 백로그 화면에서 스프린트를 시작하면 활성 스프린트 뷰로 넘어간다.** D7 E2E 의 시나리오다.
- **J5·J6 — 스크럼 보드는 활성 스프린트의 이슈만 보여준다.** 백엔드는 #421 이 이미 그렇게 배치한다
  (`placeCards` 호출 **전** 필터). 이 PR 은 **화면이 그 의미를 드러내게** 한다 —
  활성 스프린트가 없으면 빈 상태이고, 그것이 「아직 시작 안 함」임을 사용자가 알아야 한다.
- **J7 — 백로그는 스프린트가 위, 백로그가 아래.** 기존 화면이 이미 그 순서다(무변경).
  이 PR 이 더하는 것은 **어느 보드의 스프린트인가**라는 축이다.

### 편차

| # | 항목 | 결정 | 사유 |
|---|---|---|---|
| **X4** | 칸반 백로그(kanplan · J12) | **미채택** | ADR 의도적 편차 X4. **칸반 보드는 백로그 탭 없이 간다** |
| **X5** | 백로그 진입 시 보드 미지정 | **기본 보드로 폴백** | ADR 「결과」 절. **깨진 링크를 만들지 않는 것이 패리티보다 앞선다.** J17 이 「진입은 탭 하나」라 Jira 도 사용자에게 boardId 를 요구하지 않는다 — 폴백은 그 사용자 경험의 BTS 판이다 |
| **X6** | URL 경로를 `/boards/{id}/backlog` 로 (J16 의 REST 계층을 화면 URL 에도) | **미채택 — 기존 `?board=` 규약 유지** | ADR 이 **「새 URL 체계를 만들지 않는다」**를 명시했다. 경로를 바꾸면 nav 링크·기존 공유 링크·`project-tree.spec.ts:39`(href 완전 일치) 가 전부 깨지고 ADR 수정이 선행돼야 한다. **모델은 옮기되 URL 체계는 안 건드린다** |
| **X7** | 보드 탭과 백로그 탭의 board 공유 | **미채택 — 각 탭이 자기 `?board=` 를 든다** | Jira 는 보드를 고르면 백로그·활성 스프린트가 한 쌍으로 따라온다(J15). BTS 도 그렇게 하려면 공유 상태(활성 프로젝트 컨텍스트 FR-UX-07 과의 관계 정리)가 필요해 범위가 넘친다. **두 탭이 어긋날 수 있다는 것을 알려진 한계로 남긴다**(Maxi 확정 2026-09-02) |

## 도메인 정리

**BC.** `agile-planning` **주** + `issue-tracking` **종**(아래 「BC 격리 예외」).

**영향 엔티티.** `Board`(기존 · `boardType` 은 #421 이 넣음) · `Sprint`(기존 · `boardId` 는 #421) —
**새 엔티티 0.** 이 PR 은 **이미 있는 모델을 화면과 API 에 드러내는 일**이다.

**새 용어 없음.** 「스크럼 보드」·「활성 스프린트」는 ADR 이 도입했고 `glossary.md` 에 보드/스프린트
헤딩이 없다 — 용어 도입 PR 이 아니므로 갱신하지 않는다.

**관련 ADR** — `docs/adr/2026-09-01-board-type-and-active-sprint.md` **§D2·§D3·§D6 이 정본**.
백로그 계열 선행 결정 3건도 확인했다.
- `2026-08-06-fr-ux-13-f16-backlog-filter-epic.md` — ★ **이 PR 과 직접 맞물린다**(아래 E9)
- `2026-06-23-fr-bl-01-lexorank-backlog-ordering.md` · `2026-08-05-fr-ux-13-f5-backlog-card-assignee.md` — URL 계약과 무관

**기존 결정 충돌 — 1건 있고, 뒤집지 않고 확장한다.**
`fr-ux-13-f16` 이 *"`getBacklog(@PathVariable projectKey)` 는 쿼리 파라미터 **0개**"* 라고 적고
백로그 필터를 **클라이언트 필터**로 확정했다(보드는 서버 필터). 이 PR 의 `?board=` 는 **백로그에
생기는 첫 서버 쿼리 파라미터**다. **필터 3축(`q`·`assignee`·`epic`)은 클라이언트 그대로 두고
`board` 만 서버로 보낸다** — 그 결정을 뒤집는 것이 아니라 축을 하나 더하는 것이다.

### 🛑 BC 격리 예외 — Maxi 확정 (2026-09-02)

`truncated` 부채의 근본 해결이 **issue-tracking 의 `IssueRepository`** 를 건드린다
(`BOARD_CARD_FETCH_LIMIT` · `:813-825`). 「한 PR = 한 BC」의 **명시적 예외**로 Maxi 가 확정했다.
BC 간 호출이 아니라 **포트 시그니처 확장 + 그 구현**이라 pgmq 이벤트로 대체할 수 없다.
게이트 2 요약에 이 예외를 그대로 싣는다.

## 스펙

### 사전 grep 실측 (계약 §5 · 2026-09-02) — 전수

★★ **ADR 이 지목한 「깨질 수 있는 것」 8종이 불완전했다.** 8종 **밖에서 11개**가 더 걸린다.
`pre-grep-must-be-read-not-just-run` 이 말한 그대로다 — 목록을 물려받지 말고 다시 재라.

**A. 단일 지점이 보드 E2E 전량을 죽인다**

`boardDetailSchema.boardType` 을 필수로 올리면(Maxi 확정) 아래가 **한꺼번에** red 다.
- MSW 응답 조립부 `mocks/board-handlers.ts:162` `toResponseDetail()` 에 **`boardType` 기본값이 없다**
- 시드 픽스처 **7개 전부 `boardType` 미설정** — `DEFAULT_BOARD`(`board-fixtures.ts:310`) ·
  `FILTER_BOARD`(`:443`) · `WIP_BOARD`(`:526`) · `SWIMLANE_BOARD`(`:631`) ·
  `EPIC_SWIMLANE_BOARD`(`:707`) · `REORDER_SWIMLANE_BOARD`(`:808`) ·
  ★**`QUICK_FILTER_PERM_SEED` 는 `board-handlers.ts:873`** — **픽스처 파일 밖에 있다.**
  `board-fixtures.ts` 만 고치면 `quick-filter.spec.ts` S7 이 남아서 죽는다
- `DEFAULT_BOARD` 는 `: BoardDetail` 타입 선언(`:310`)이라 **타입 에러**도 난다.
  같은 형태의 인라인 `BoardDetail` 리터럴이 단위 테스트 다수에 있다 —
  `board-drop.test.ts:63,238` · `KanbanBoard.test.tsx:118,420,570,595,612` ·
  `use-boards.test.tsx:46,272` · `use-change-card-field.test.tsx:39,115` ·
  `use-reorder-card.test.tsx:35` · `use-move-card.test.tsx:25` ·
  `routes/__tests__/projects.board.test.tsx:249,266,272,277,296,789,835`

**B. E2E 영향 — 8종 안**

| 파일 | 걸리는 줄 | 사유 |
|---|---|---|
| `board-manage.spec.ts` | `:42` `:100` `:116` `:126-131` `:162-171` | ★**두 겹** — A 스키마 + **S1 이 만드는 보드가 실제 SCRUM** 이라 활성 스프린트 분기를 탄다. `:124` 주석이 「스크럼 보드 화면은 PR ③ 소관」으로 이 PR 을 가리킨다 |
| `board-kanban.spec.ts` | `:50` `:161/192/221/276` `:303/312` | A 스키마. `:312` NEWPROJ 는 보드 0개라 무관 |
| `backlog.spec.ts` | `:70` `:717` `:952/1213/1257/1281/1321/1369/1410` `:2307-2321` `:2355/2382` | ★**26개 goto 전부가 폴백 경로**를 밟는다. 폴백이 없으면 전멸 |
| `sprint-burndown.spec.ts` | `:24` `:88` | `:88` 백로그 진입. `:131` 직접 진입은 무관 |
| `board-swimlane-field-change.spec.ts` | `:76/85` `:289` `:350~606`(7 goto) | A 스키마. `:173/178` 은 주석이라 무관 |
| `board-wip-swimlane.spec.ts` | `:39/48` `:93/125/175/220` | A 스키마 |
| `quick-filter.spec.ts` | `:44/48/51/55/58/62` `:170~367` `:306/310` | A 스키마 + `:58` QF_PERM 시드가 픽스처 밖 |
| `board-epic-swimlane.spec.ts` | `:35` `:91/144/196` | A 스키마 |

**C. ★ 8종 밖에서 걸리는 11개 — ADR 이 놓친 것**

| 파일 | 걸리는 줄 | 사유 |
|---|---|---|
| `card-density.spec.ts` | `:47`(보드) `:50`(백로그) | **두 변경 축에 동시에** 걸리는 유일한 파일 |
| `issue-create-entry-points.spec.ts` | `:17`(백로그) `:18`(보드 · **`?board=` 없음**) `:85` | 스프린트 칸 진입점 |
| `board-reorder.spec.ts` | `:53/62` 6 goto | `board-*` 접두라 grep 에서 걸러졌다 |
| `board-filter.spec.ts` | `:46` 7 goto · `:428` `&assignee=` | 동상 |
| `project-tree.spec.ts` | **`:38` `:39` href 완전 일치** · `:86/93/128/132` | ★ **nav 링크에 `?board=` 를 붙이면 즉사** |
| `project-switcher.spec.ts` | **`:93` `:97` 정규식 `$` 앵커** | ★ **URL 뒤에 `?board=` 가 붙으면 실패** |
| `project-crud.spec.ts` | **`:142` pathname 완전 일치** | ★ redirect 로 경로를 바꾸면 즉사 |
| `active-project.spec.ts` | `:159` `:305` | 보드 직접 진입 |
| `timeline.spec.ts` · `timeline-zoom.spec.ts` | `:35` · `:38` | 백로그를 진입 발판으로 사용 |
| `project-velocity` · `project-cfd` · `project-cycle-time` | `:24` · `:25` · `:34` | 동일 패턴 |

★ **이 3줄(`project-tree:38,39` · `project-switcher:93,97` · `project-crud:142`)이 편차 X6 의 근거다** —
URL 자동 주입을 금지하는 것은 취향이 아니라 **실측된 제약**이다.

**D. MSW 는 백로그 쿼리 파라미터를 아예 안 읽는다 — lexical 비교보다 나쁘다**

`mocks/backlog-handlers.ts` 의 **8개 핸들러 전량**(`:123,195,283,360,416,505,569,617`)이
`searchParams` 를 **0회** 쓴다. `getBacklogHandler`(`:123`)는 **`request` 조차 안 받는다**.
→ `?board=` 를 **안 보내도, 틀린 형식이어도, 남의 보드 UUID 여도 전부 초록**이 된다.
`?from=2026-06-01` 사고(learnings 2026-06-25)와 **같은 자리이고 더 나쁜 형태**다.
`createSprintHandler`(`:416`)도 body 5개만 읽어 **`boardId` 를 조용히 버린다**.
★ **`backlogStore` 는 `projectKey` 키 Map** 이라 **board 축이 자료구조에 없다** — 진짜 재현하려면
store 구조가 바뀐다. 이것이 이 PR 의 **MSW 작업 실체**다.

참고 — `board-handlers.ts` 는 비교가 전부 `===`/`includes` 라 lexical 문제 없음.
`inbox-handlers.ts:144-159` · `audit-log-handlers.ts:26-38` 은 그 사고 뒤 **이미 고쳐져 있다**.

### 기능 요구사항 (FR)

**FR-1. 스크럼 보드 화면.** `boardType === 'SCRUM'` 이고 `activeSprint === null` 이면
빈 상태 + 「백로그에서 스프린트를 시작하세요」 + 백로그 링크. 칸반은 무변경.
★ **early-return 이 아니라 인라인 대체**다 — 선례는 `projects.$projectKey.board.tsx:961`
`isFilteredEmpty` 분기이고, 헤더·`⋯` 메뉴·필터바를 **유지한 채** KanbanBoard 자리만 바꾼다.
early-return 으로 만들면 `board-manage.spec.ts:162-171`(SCRUM 보드로 전환 → `⋯` 삭제)이 죽는다.

**FR-2. 활성 스프린트 표시.** 스크럼 보드 헤더에 활성 스프린트 이름(+ 기간)을 보인다.
`ActiveSprintResponse` = `{sprintId, name, startDate, endDate}` **4필드**(`goal`·`status` 없음).

**FR-3. 백로그 보드 스코프.** `GET /projects/{key}/backlog?board={uuid}`.
`board` 미지정이면 **기본 보드**(그 프로젝트의 스크럼 보드 · 없으면 첫 보드)로 폴백.

**FR-4. 스프린트 생성에 boardId.** `CreateSprintRequest.boardId` + 컨트롤러 결선.
서비스는 **이미 준비돼 있다** — `SprintApplicationService.create(…, boardId: UUID? = null)` 이
존재하고 주석이 「PR ③ 에서 명시 지정이 붙는다」고 적어 뒀다. **DTO·컨트롤러 2곳만 결선.**

**FR-5. 보드 목록에 종류.** `BoardSummaryResponse.boardType` — 스위처가 종류를 표시할 수 있게.
★ **백엔드 확장은 이것 하나만 남았다** — `BoardDetailResponse` 는 `boardType`·`activeSprint` 가
**이미 완비**돼 있다(`BoardResponses.kt:274-275`, PR ① 산출물).

**FR-6. `truncated` 부채 해소** (BC 격리 예외 · Maxi 확정).
현행은 `IssueRepository:813-825` 가 **`created_at DESC` 로 1,000건을 먼저 자르고**
스프린트 필터가 **그 뒤**에 온다(`BoardApplicationService.getBoard` · `BacklogApplicationService:113-120`).
→ 활성 스프린트 이슈가 오래됐으면 **경고 없이 증발**한다. 보드 스코프를 **SQL 술어로** 밀어넣는다.

### API 인터페이스 (REST)

| 엔드포인트 | 변경 |
|---|---|
| `GET /api/v1/projects/{projectKey}/backlog` | **`?board={uuid}` 선택 파라미터 추가.** 미지정=기본 보드 폴백. 잘못된 UUID 형식 → 400 · 존재하지 않는 보드 → 404 · 다른 프로젝트 보드 → 404(존재 probe 차단) |
| `POST /api/v1/sprints` | `CreateSprintRequest.boardId: UUID?` **선택** 추가. 미지정 시 현행 `ensureScrumBoard` 폴백 유지(하위 호환) |
| `GET /api/v1/boards?projectKey=` | `BoardSummaryResponse.boardType` 추가 |
| `GET /api/v1/boards/{id}` | **무변경** — `boardType`·`activeSprint` 가 이미 있다 |

### 데이터 모델 변경

**없음. 마이그레이션 0.** `boards.board_type` · `sprints.board_id` 는 #421 의 V505·V506 이 완료했다.

### 엣지 케이스

| # | 상황 | 기대 |
|---|---|---|
| **E1** | 스크럼 보드 · 활성 스프린트 없음 | 빈 상태 + 「백로그에서 스프린트를 시작하세요」. **헤더·`⋯` 메뉴 유지**(FR-1) |
| **E2** | 스크럼 보드 · 활성 스프린트 있는데 이슈 0건 | 「이 스프린트에 이슈가 없습니다」 — E1 과 **다른 문장**이어야 한다. 원인이 다르다 |
| **E3** | 칸반 보드 | **완전 무변경.** `activeSprint` 는 항상 null 이고 화면이 그 축을 안 본다 |
| **E4** | 백로그 `?board=` 없이 진입 (기존 링크·nav 탭) | 기본 보드 폴백. **URL 은 안 바꾼다**(X6 · `project-tree:39` href 일치) |
| **E5** | 백로그에서 필터를 바꿈 | ★ **`?board=` 가 증발하면 안 된다.** `handleFilterChange` 가 `filterToSearch(next)` 로 **통째 교체**한다 — 보드 라우트의 `buildBoardSearch(currentBoardId, …)`(`board.tsx:709`) 선례를 따른다 |
| **E6** | 백로그에서 보드를 바꿈 | 필터 3축을 **유지**할지 초기화할지 — **유지**한다. 담당자·에픽 필터는 보드와 독립 축이다 |
| **E7** | 존재하지 않는 `?board=` UUID | 404. **기본 보드로 조용히 폴백하지 않는다** — 잘못된 링크를 정상처럼 보이면 사용자가 다른 보드를 보고 있는 줄 모른다 |
| **E8** | 다른 프로젝트의 보드 UUID | 404(403 아님). 존재 probe 차단 — `permission-assert-before-existence-makes-403-lie` |
| **E9** | ★ 보드를 바꾼 뒤 필터바 | `BacklogBoard.tsx:601` 의 `filterBarKey` 재마운트 열쇠가 URL 변경과 맞물린다. `fr-ux-13-f16` 이 **「유닛 전부 초록인 채 살아 있던」** 결함으로 기록했고 **브라우저 눈확인이 처음 잡았다** — 이 자리는 눈확인이 필수다 |
| **E10** | 스크럼 보드 2개 (E-6 부채) | 두 번째 보드에 스프린트를 만들면 그 보드에 붙는다. FR-4 의 `boardId` 명시 전달이 이것을 닫는다 |
| **E11** | `truncated` + 활성 스프린트 | FR-6 이후 스프린트 이슈가 상한에 안 걸린다. 그래도 `truncated` 면 배너를 **유지**한다(백로그 전체는 여전히 잘릴 수 있다) |

### 제약 조건

- **URL 체계를 새로 만들지 않는다**(ADR · 편차 X6). `?board=` 규약 그대로.
- **nav 링크·redirect 로 `?board=` 를 자동 주입하지 않는다** — 실측 3줄이 금지한다.
- **필터 3축은 클라이언트 필터를 유지**한다(`fr-ux-13-f16` 결정 존중). `board` 만 서버로.
- **`⋯` 관리 메뉴가 사라지면 안 된다** — 빈 상태를 early-return 으로 만들지 않는 이유.
- **BC 격리 예외는 FR-6 한 곳뿐**이다. 그 밖에 issue-tracking 을 건드리지 않는다.

### 측정 가능한 완료 기준

1. 스크럼 보드에 활성 스프린트가 없으면 빈 상태가 뜨고, 백로그에서 스프린트를 시작하면 **그 스프린트의 이슈만** 보인다.
2. `GET /backlog?board={uuid}` 가 그 보드의 스프린트만 돌려준다. 미지정이면 기본 보드로 폴백한다.
3. 스프린트 생성 시 `boardId` 가 요청 바디에 실리고, **두 번째 스크럼 보드에서도 스프린트가 그 보드에 붙는다**(E-6 해소).
4. `BoardSummaryResponse.boardType` 이 목록 응답에 실린다.
5. **활성 스프린트 이슈가 `created_at` 기준 1,000건 밖이어도 스크럼 보드에서 안 사라진다**(FR-6).
6. `boardDetailSchema.boardType` 필수 · `activeSprint` nullable 로 올리고 **MSW 시드 7개 + 인라인 리터럴 전수**가 갱신돼 유닛·E2E 전량 초록.
7. **D6·D7 체크박스가 `[x]` 가 되고 `agile-planning` 진척 열이 `☑` 로 바뀐다** — FR-BD-04 완주.
8. `pnpm verify` · 판별식 478/478 · `verify-master-plan` EXIT=0(FR 144/144).

### 시각 검증 기준

**관련 E2E** — 위 B·C 표 **19개 파일 전량**(8종 + 8종 밖 11개).

**눈확인 항목** (계약 §6 · E9 때문에 **생략 불가**)
1. 스크럼 보드 빈 상태 — 헤더·`⋯` 메뉴·필터바가 **남아 있는지** (라이트/다크)
2. 백로그에서 보드를 바꿨을 때 **필터바가 튕기지 않는지**(E9 · `filterBarKey`)
3. 필터를 바꿔도 **`?board=` 가 URL 에 남는지**(E5)
4. 스프린트 시작 → 보드로 이동 → **그 스프린트 이슈만** 보이는지(J18)

## Sanity Check

**❓ 발견 3건 — 스스로 보강했다.**

1. **「기본 보드」의 정의가 없었다.** 프로젝트에 스크럼 보드가 여럿이면 어느 것인가.
   → **그 프로젝트의 스크럼 보드 중 가장 오래된 것**(`findScrumBoardIdByProject` 가 이미
   `created_at ASC LIMIT 1` 로 그렇게 고른다 · 스크럼이 없으면 첫 보드). 기존 코드의 판단을 재사용한다.
2. **E1 과 E2 를 한 문장으로 뭉갤 뻔했다.** 「활성 스프린트 없음」과 「활성 스프린트에 이슈 없음」은
   사용자가 할 일이 다르다 — 전자는 백로그로 가야 하고 후자는 이슈를 넣어야 한다. 별개 문구로 갈랐다.
3. **E7 을 「폴백」으로 쓸 뻔했다.** 존재하지 않는 보드 UUID 를 기본 보드로 조용히 대체하면
   **사용자가 다른 보드를 보면서 그 사실을 모른다.** 404 로 확정했다 — 폴백은 **`board` 파라미터가
   아예 없을 때만**이다.

**Maxi 결정 필요 — 없음.** 세 결정(board 스코프 방식 · 스키마 강도 · truncated 범위)은
2026-09-02 에 이미 받았다.


## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
