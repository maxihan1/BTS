# 보드 설정 화면 — 뼈대 + Columns 탭 (부채 177 · PR 1)

> plan: [`docs/plans/2026-09-04-board-settings-screen.md`](../plans/2026-09-04-board-settings-screen.md)
> 티어: T3 (선언 · Maxi 지정) · slug `board-settings-screen`
> FR — **FR-BD-01** · **FR-BD-03** · **FR-BD-04**. **신규 FR 없음 · FR 총수 144 불변.**

## §1. 이 스펙이 서 있는 자리

부채 177 이 「지라 Board settings 7탭 중 도달 가능한 것이 0개」라고 적었다. **착수 실측이 그 표를
3곳 뒤집었다**(plan `## ★ 착수 실측`). 정정된 그림은 이렇다.

| 지라 탭 | BTS 백엔드 | BTS 화면 | 이 PR |
|---|---|---|---|
| **Columns** | **6종 중 4종 완비** — WIP 편집 · 컬럼 생성 · 삭제 · 상태 집합 교체 | **0개** | **담당** — UI 전량 + 백엔드 2종 보충 |
| Swimlanes | `PATCH /boards/{id}` `swimlaneField` | **있다** (`SwimlaneSelector` · 보드 화면 인라인) | 편차 `X3` 로 등재 · 안 옮긴다 |
| Quick filters | `BoardQuickFilterService` · `V504` | **있다** (`QuickFilterChips`) | 편차 `X3` · 안 옮긴다 |
| Card layout | 없음 | 없음 | **범위 밖** — 후속 PR (갭 B) |
| Estimation and tracking | 없음 | 없음 | 범위 밖 (갭 C) |
| Working days | 없음 | 없음 | 범위 밖 (갭 D) |
| Issue detail view | 없음 | 없음 | 범위 밖 (갭 E) |

**★이 PR 이 여는 문.** 부채 **178**(`board_columns.state_key` DROP)이 착수 조건을
「#444 머지 + **드롭존 UI PR 까지 안정화된 뒤**」로 못박았다. Columns 탭의 상태 매핑 편집 UI 가
그 「드롭존 UI」이고, **이 PR 없이는 178 이 영구히 착수 불가**다.

**★이 PR 이 닫는 것.** WIP 제한 **편집** UI 부재 — `PATCH /boards/{id}/columns/{columnId}` 가
2026-06-22(FR-BD-03 D4)부터 있는데 부르는 화면이 없다. FR-BD-03 **D6 deviation ③**
「WIP 제한 '편집' UI는 후속 이연」이 담당자 없이 살아 있었다. learning
**2026-07-17「도메인·서비스·repo 가 다 있어도 REST 노출이 없으면 기능이 없는 것이다」**의 UI 판본.

## Jira 대조

### 승계 (재조회 안 함 · 계약 §1-0)

부채 177 등재 세션이 2026-09-03 에 조회해 `TODOS.md:1459~` 에 J7~J21 로 남겼고, 그 절이
스스로 「착수 시 재사용 대상」이라 적었다.

| # | 원문 인용 | 출처 · 조회일 |
|---|---|---|
| **J7** | *"From your board, select **more** () then **Configure board**."* | [Configure a company-managed board](https://support.atlassian.com/jira-software-cloud/docs/configure-a-company-managed-board/) · Cloud · 2026-09-03 |
| **J8** | *"you must be either: a **space administrator** for the location of the board [or] a **board administrator** for the board itself"* | 동일 · Cloud · 2026-09-03 |
| **J10** | `Swimlanes` — *"Configure swimlanes on a board to help you distinguish tasks of different categories"* | 동일 · Cloud · 2026-09-03 |
| **J11** | `Quick filters` — *"Configure quick filters on a board to help you switch between work types"* | 동일 · Cloud · 2026-09-03 |

### 이번에 새로 조회 (2026-09-04 · Columns 탭이 이 PR 의 신규 표면이라 승계로 부족했다)

J9 의 승계 인용은 *"edit the mapping of workflow statuses to columns of a board"* 한 줄뿐이라
**조작 6종의 형태를 알 수 없었다.** 재조회로 전부 확보했다.

| # | 원문 인용 | 출처 · 조회일 |
|---|---|---|
| **J22** | 진입 — *"On the **Board settings** screen, select the desired tab (**Columns**, **Swimlanes**, etc)."* | [Configure a company-managed board](https://support.atlassian.com/jira-software-cloud/docs/configure-a-company-managed-board/) · Cloud · 2026-09-04 |
| **J23** | **컬럼 추가** — *"To the right of the columns, select **Add** (**+)**. Enter a name for the new column and select its category, then select **Add column**."* | [Configure columns](https://support.atlassian.com/jira-software-cloud/docs/configure-columns/) · Cloud · 2026-09-04 |
| **J24** | **이름 변경** — *"Select a column's name to edit, modify the existing name, then press **Enter**."* | 동일 · Cloud · 2026-09-04 |
| **J25** | **순서 변경** — *"Hover over the top of a column, then drag the column left or right to its new position. Drop the column to place it in its new position."* | 동일 · Cloud · 2026-09-04 |
| **J26** | **삭제** — *"Select **Delete** () at the top of the column."* | 동일 · Cloud · 2026-09-04 |
| **J27** | **상태 매핑** — *"Drag and drop a status from the **Unmapped statuses** panel to the appropriate column."* | 동일 · Cloud · 2026-09-04 |
| **J28** | **삭제 시 상태 회수** — *"Any Jira workflow statuses mapped to the deleted column are moved back to the **Unmapped statuses** panel."* | 동일 · Cloud · 2026-09-04 |
| **J29** | **컬럼 제약** — *"Under a column's name, you can enter a **minimum** or **maximum** value. To remove a constraint, clear the existing value."* | 동일 · Cloud · 2026-09-04 |

### 채택 / 편차

**채택 8건** — J7(진입) · J8(권한) · J22(탭 화면) · J23(추가) · J24(이름) · J25(순서) · J26·J28(삭제와 상태 회수) · J27(매핑 드래그).

| 편차 | 내용 | 근거 |
|---|---|---|
| **X1** | **컬럼 `category` 를 사용자가 고르지 않는다.** J23 은 생성 시 category 를 선택하게 하나, BTS 는 담은 상태들의 category 최댓값으로 **파생**한다(`BoardApplicationService.createColumn:634` → `BoardCardPlacement.resolveCategory`). | #444 ADR **D5·D7** 이 「`category` 는 표시 전용 스냅샷」으로 확정했고 `BoardColumn.kt:29-30` KDoc 이 「어떤 로직 분기도 하지 않는다」를 못박았다. 사용자가 고르게 하면 **파생값과 선택값이 갈리는 두 번째 진실**이 생긴다 — 이 저장소의 지배 결함 양식([[two-lists-never-check-each-other]]) |
| **X2** | **컬럼 제약은 최대치(`wipLimit`)만 지원한다. 최소치 미지원.** | J29 의 minimum 은 BTS 스키마에 대응 칸이 없다. 넣으면 마이그레이션이 필요해 이 PR 이 T3 실측이 되고 범위가 부푼다. `WipCountBadge` 도 초과 경고 한 방향만 그린다. **후속 등재 대상**이고 이 PR 은 최대치만 낸다 |
| **X3** | **스윔레인·퀵필터를 설정 탭으로 수렴시키지 않는다.** J10·J11 은 설정 탭에 두나 BTS 는 보드 화면 인라인 유지. | **Maxi 확정(2026-09-04).** 오늘 한 번에 되는 조작을 설정 화면 안으로 숨기면 UX 가 나빠진다. 게다가 기존 E2E(`board-swimlane-field-change.spec.ts` · `board-filter.spec.ts`)가 그 셀렉터를 잡고 있어 회귀 반경이 넓다 |
| **X4** | **진입점이 사이드바가 아니라 보드 화면의 더보기 메뉴다.** J7·J22 는 사이드바 보드 이름 옆 `•••`. | BTS 사이드바(`ProjectTree.tsx`)는 보드를 개별 노드로 갖지 않는다 — 보드는 `?board=` 로 전환된다. 보드 화면 헤더의 **기존** `MoreHorizontal` 드롭다운(`board.tsx:396-407` · 이름 변경·삭제 보유)에 항목 1개를 더한다. **새 진입점을 만들지 않는다**(계약 §4 재사용) |

## §3. 사용자 시나리오 (Given-When-Then)

**S1 — 진입.**
Given 프로젝트 `ATLAS` 의 보드 화면을 CREATE 권한으로 보고 있다
When 헤더의 더보기(`•••`) → **보드 설정** 을 누른다
Then `/projects/ATLAS/board/settings?board=<보드 UUID>` 로 이동하고 **Columns** 탭이 열린다.

**S2 — 상태를 컬럼에 매핑한다 (J27 · 178 해금 지점).**
Given 워크플로우에 `대기` 상태가 있고 어느 컬럼에도 매핑돼 있지 않다 (미매핑 패널에 보인다)
When `대기` 를 「진행 중」 컬럼으로 끌어다 놓는다
Then `PUT /boards/{id}/columns/{columnId}/states` 가 그 컬럼의 **집합 전체**로 호출되고,
성공 시 미매핑 패널에서 `대기` 가 사라진다.

**S3 — 매핑을 떼면 미매핑으로 돌아온다 (J28 역방향).**
Given 「완료」 컬럼이 `완료` · `배포됨` 두 상태를 담는다
When `배포됨` 을 미매핑 패널로 끌어다 놓는다
Then 그 컬럼의 상태 집합이 `완료` 하나로 교체되고 `배포됨` 이 미매핑 패널에 나타난다.

**S4 — WIP 제한을 편집한다 (D6 deviation ③ 상환).**
Given 「진행 중」 컬럼에 WIP 제한이 없다
When 컬럼 이름 아래 제약 입력에 `3` 을 넣고 확정한다
Then `PATCH /boards/{id}/columns/{columnId}` 가 `wipLimit=3` 으로 호출되고,
보드 화면으로 돌아가면 `WipCountBadge` 가 `{count}/3` 을 그린다.
**빈 값으로 지우면 무제한(`null`)이 된다**(J29 후단).

**S5 — 컬럼을 추가한다 (J23 · X1).**
Given 컬럼이 3개다
When 오른쪽 끝 `+` 로 이름 `검수` 를 넣고 추가한다
Then **상태 0개 컬럼**이 만들어지고(#444 E1 이 허용), category 는 파생되며(X1),
사용자는 이어서 S2 로 상태를 끌어다 놓는다 — 지라의 「컬럼 먼저, 상태는 드래그로」 흐름 그대로다.

**S6 — 컬럼을 지우면 상태가 미매핑으로 돌아온다 (J26·J28).**
Given 「검수」 컬럼이 `검수중` 상태를 담고 카드 2장이 있다
When 컬럼 삭제를 확정한다
Then `DELETE /boards/{id}/columns/{columnId}` 가 호출되고 `검수중` 이 미매핑 패널로 돌아오며,
**삭제 다이얼로그가 폭발 반경(영향 카드 수)을 먼저 보여준다.**

> ★**eng 리뷰 BLOCKER-1 이 이 줄을 고쳤다.** 초안은 「#444 가 그 응답 계약을 이미 만들어 뒀다」고
> 적었는데 **거짓이다.** `BoardApplicationService.deleteColumn`(`:786-795`)은 `removedCardCount` 를
> **삭제한 뒤 반환값으로** 돌려준다 — 다이얼로그가 **삭제 전에** 보여줄 소스가 아니다.
> 사전 표시는 클라이언트가 `column.cards.length` 로 세는 수밖에 없고, 그 목록은
> `BOARD_CARD_FETCH_LIMIT`(1000)에 **잘린다**(`IssueRepository.kt:754·767`).
> **처방 — `truncated=true` 면 정확한 수를 주장하지 않는다.** 「1000+ 건」으로 표기하고 그 사실을
> 문구로 밝힌다. 서버의 `removedCardCount` 도 같은 잘린 목록에서 세므로(`:787` 이 `getBoard` 를
> 재호출한다) **삭제 후 토스트도 같은 한계를 갖는다** — 두 곳이 같은 규칙을 쓴다.

**S7 — 권한이 없다 (J8).**
Given CREATE 권한이 없는 사용자다
When 설정 URL 로 직접 들어온다
Then 편집 컨트롤이 **전부 비활성**이고 사유가 보인다. 더보기 메뉴에 「보드 설정」 항목이 **안 뜬다**.

**S8 — 보드를 지목하지 않았다 (부채 785 를 안 늘린다).**
Given `?board=` 없이 `/projects/ATLAS/board/settings` 로 들어왔다
When 화면이 열린다
Then **기본 보드를 스스로 고르지 않고** 보드 화면으로 되돌린다. 「기본 보드」 규칙을 **4번째로 늘리지 않는다**.

## §4. 기능 요구사항 (FR)

- **R1** 라우트 `/projects/$projectKey/board/settings` 신설. 보드는 **`?board=<uuid>` 로만** 지목한다(S8).
- **R2** 보드 화면 헤더 더보기 메뉴에 「보드 설정」 항목 추가(X4). CREATE 권한자에게만 노출(S7).
- **R3** 설정 화면은 **Columns 탭 하나만** 그린다. 나머지 6탭은 **만들지 않는다** — 후속 PR 이 자기 탭을 추가한다(Maxi 확정 2026-09-04 · G3). 탭 이름은 지라(J22)의 `Columns` 에 대응하는 「컬럼」.
  ★ **골격 6개를 미리 그리지 않는 이유.** 그것은 「누를 수 있는데 아무 일도 안 일어나는」 화면을 6개 배포하는 것이고, 장부가 경계한 「도달할 UI 가 없는 기능」의 **거울상**이다. 탭이 하나면 탭바를 아예 안 그려도 되므로 화면도 단순해진다 — 두 번째 탭이 생기는 PR 이 탭바를 도입한다.
- **R4** Columns 탭은 컬럼을 `displayOrder` 순 가로 배치하고, 오른쪽에 **미매핑 상태 패널**을 둔다. 데이터는 `GET /boards/{id}` 의 `columns[].states` · `unmappedStates` 를 그대로 쓴다(#444 가 이미 낸다 — **신규 조회 API 없음**).
- **R5** 상태 드래그로 매핑을 바꾼다(J27·S2·S3). 서버 호출은 `PUT …/states` 이고 **집합 전체**를 보낸다.
- **R6** 컬럼 추가(J23·S5) — `POST /boards/{id}/columns`. 이름만 받고 `stateKeys` 는 빈 배열, `category` 는 보내지 않는다(X1).
- **R7** 컬럼 삭제(J26·S6) — `DELETE …/{columnId}`. 확인 다이얼로그가 **영향 카드 수**를 먼저 보인다.
  **컬럼이 1개면 삭제를 비활성**하고 사유를 보인다(❓G1) — 컬럼 0개 보드는 부채 179 로 조회가 500 이 되므로, 이 PR 이 그 상태로 가는 **클릭 한 번짜리 경로를 만들지 않는다.** 지라에 대응 제약이 없으나 편차가 아니라 **결함 회피**다.
- **R8** WIP 제한 편집(J29·S4) — `PATCH …/{columnId}`. 빈 값 = `null`(무제한). **최소치는 안 낸다**(X2).
- **R9** 컬럼 이름 변경(J24) — **신규 API**. `PATCH /boards/{id}/columns/{columnId}` 를 확장해 `name` 을 받는다.
- **R10** 컬럼 순서 변경(J25) — **신규 API**. `PUT /boards/{id}/columns/order` 가 `columnIds` 전체 순서를 받는다. 부분 이동이 아니라 **전체 순서 교체**다 — R5 와 같은 이유(집합/순서를 통째로 받아야 서버·클라이언트가 안 갈린다).
- **R11** 변경 후 보드 화면 캐시를 무효화해, 설정에서 돌아가면 즉시 반영된다.

## §5. 비기능 요구사항 (NFR)

- **N1 마이그레이션 0.** `board_columns.display_order` · `name` · `wip_limit` 이 이미 있다. **DDL 을 만들지 않는다.**
- **N2** `BoardRepository.kt` 줄수 상한 **443 유지**(부채 157). 이 PR 은 리포지터리를 늘리지 않는 방향을 우선한다.
- **N3** 신규 API 2종은 기존 `loadBoardWithCreate` 권한 게이트를 **승계**한다 — 새 권한 축을 만들지 않는다.
- **N4** `apps/web` 줄수 래칫을 지킨다. 컬럼 카드·미매핑 패널은 별 파일로 쪼갠다.
- **N5** 드래그는 기존 `@dnd-kit` 자산을 쓴다 — `KanbanBoard.tsx` 의 `buildDragAnnouncements`(한국어 조사 처리 완비)를 재사용하고 새 공지 구현을 만들지 않는다(계약 §4).
  ★**design 리뷰 BLOCKER-1 이 이 항목을 확장했다.** 초안은 **공지만** 재사용하라고 적었다. 그런데
  키보드 접근을 실제로 여는 것은 공지가 아니라 **센서 구성**이다 —
  `KanbanBoard.tsx:536-538` 이 `useSensor(PointerSensor, { activationConstraint: { distance: 5 } })`
  **+ `useSensor(KeyboardSensor)`** 를 함께 건다. 재사용 목록에 센서가 없으면 구현자가
  `PointerSensor` 만 걸기 쉽고, 그러면 **키보드·보조기술 사용자에게 매핑 기능이 통째로 사라진다.**
  **유닛 테스트는 이것을 못 잡는다** — 드롭 판정 순수 함수는 센서를 모른다
  (memory `mock-swallowed-prop-is-invisible-to-unit-tests` 양식).
  **처방** ①센서 2종 구성을 N5 재사용 대상에 명시 ②키보드만으로 매핑을 바꾸는 판정을 테스트로 박는다.
- **N7 반응형** 컬럼 배치는 보드 화면의 검증된 패턴을 승계한다 — `KanbanBoard.tsx:638` 의
  `flex gap-4 overflow-x-auto pb-4`. **모바일 분기를 새로 만들지 않는다**(보드 화면도 안 만든다).
  터치 타깃은 44px 이상.
- **N8 조회 비용** 설정 화면은 카드 **목록**이 필요 없고 **수**만 필요한데, 유일한 조회
  `GET /boards/{id}` 가 카드 최대 1000장을 실어 온다. 이 PR 은 **그대로 쓴다**(신규 조회 API 를
  만드는 것이 범위를 넘긴다). **부채로 등재**하고 게이트 2 요약에 싣는다 — 매 드롭마다 이 조회를
  무효화하면 카드 1000장을 다시 끌어오므로 드래그 체감이 나빠진다.
- **N6 즉사 계약 준수** — 새 다이얼로그마다 고유 `aria-label` · `<h1>` 은 화면당 하나 · `프로젝트 뷰 전환` 을 Radix Tabs 로 바꾸지 않는다. **이 PR 은 탭이 하나라 탭바를 만들지 않는다**(R3) — 두 번째 탭을 만드는 PR 이 「패널 전환 = Radix Tabs」 ADR 을 적용한다.

## §6. API 인터페이스 (REST)

**소비만 하는 기존 4종 (신규 0줄).**

| 메서드 | 경로 | 쓰임 |
|---|---|---|
| `GET` | `/api/v1/boards/{id}` | 컬럼·상태·`unmappedStates`·`canDelete` (R4) |
| `POST` | `/api/v1/boards/{id}/columns` | 컬럼 추가 (R6) |
| `PUT` | `/api/v1/boards/{id}/columns/{columnId}/states` | 상태 집합 교체 (R5) |
| `DELETE` | `/api/v1/boards/{id}/columns/{columnId}` | 컬럼 삭제 (R7) |

**신규 2종.**

| 메서드 | 경로 | 본문 | 응답 |
|---|---|---|---|
| `PATCH` | `/api/v1/boards/{id}/columns/{columnId}` | **기존 `UpdateColumnWipLimitRequest` 확장** — `wipLimit` 에 더해 `name` 선택 수용. 둘 다 없으면 400 | 200 `ColumnMetaResponse` |
| `PUT` | `/api/v1/boards/{id}/columns/order` | `{ "columnIds": [uuid, …] }` — **보드의 전 컬럼을 빠짐없이** 담아야 한다 | 200 `BoardMetaResponse` |

★ `PATCH` 를 확장하는 이유. 새 경로 `…/{columnId}/name` 을 파면 **컬럼 1건을 고치는 경로가 둘**이 되고
권한 게이트가 두 벌 된다. 기존 요청 DTO 에 `name` 을 더하는 쪽이 게이트 승계(N3)에 맞다.
다만 **요청 이름이 `UpdateColumnWipLimitRequest` 인 채로 `name` 을 받으면 이름이 거짓말**이 되므로
`UpdateColumnRequest` 로 개명한다 — 개명은 같은 PR 안에서 끝낸다.

## §7. 데이터 모델 변경

**없다.** `board_columns` 는 `name` · `category` · `display_order` · `wip_limit` 을 모두 갖고
`board_column_states`(#444 V508)가 매핑을 든다. **이 PR 의 마이그레이션은 0건이다.**

★ 그래서 **실측 티어가 선언(T3)보다 낮게 나올 가능성이 높다** — 마이그레이션도 shared-kernel 도
토폴로지도 안 건드리고 `BE_MAIN`+`API`+`FE_SRC` 이므로 실측은 **T2** 다. 판정 5문 ⑤ 대로
**자동 강등하지 않고** 게이트 2 요약에 선언·실측을 나란히 적어 Maxi 가 결정한다.

## §8. 엣지 케이스

- **E1 상태 0개 컬럼.** R6 이 실제로 만든다(#444 E1 이 허용). 보드 화면에서 그 컬럼은 **항상 비어 있다** — 설정 화면이 「상태 없음」을 명시적으로 그려야 사용자가 미완성임을 안다.
- **E2 컬럼 0개 보드.** 마지막 컬럼을 지우면 도달한다. **부채 179**(컬럼 0개 동시 조회 → 자가 치유 UNIQUE 경합 500)가 여기서 재현 가능해진다. 이 PR 은 179 를 **고치지 않되**, 마지막 컬럼 삭제를 막을지 스스로 판정한다 → **§9 Sanity Check ❓G1**.
- **E3 한 상태를 두 컬럼에 넣으려 한다.** #444 `X1` 이 DB 복합 FK 로 막고 서버가 **409** 로 답한다. UI 는 드롭을 낙관적으로 반영한 뒤 409 면 되돌리고 사유를 보인다.
- **E4 순서 배열이 불완전하다.** R10 이 전 컬럼을 요구하므로 누락·중복·타 보드 컬럼 id 는 **400**.
- **E5 이름 중복.** 지라는 같은 이름 컬럼을 막지 않는다(인용 없음 — 확인 못 함). BTS 도 막지 않되 **공백만 있는 이름은 400** (`BoardColumn.kt:66` 이 이미 `require(name.isNotBlank())`).
- **E6 동시 편집.** 두 사람이 같은 보드 설정을 열고 각각 컬럼을 지우면 늦은 쪽이 404 를 받는다. 재조회 안내로 끝낸다 — 낙관적 락은 이 PR 범위 밖이다.
- **E8 한 사람의 연속 드롭 (eng 리뷰 CONCERN-3).** E6 은 **두 사람**만 봤다. 실제로 더 자주 밟는 것은
  **한 사람이 빠르게 두 번 드래그**하는 경우다. R5 가 **집합 전체**를 보내므로 두 요청이 같은
  「이전 집합」에서 파생되면 나중 응답이 앞 변경을 **덮는다**(lost update). 서버는 둘 다 200 이고
  아무도 오류를 못 본다.
  **처방 — 컬럼당 뮤테이션을 직렬화한다.** in-flight 가 있으면 다음 드롭은 그 응답을 기다린 뒤
  **서버가 돌려준 집합**에서 파생한다. 클라이언트가 들고 있던 집합에서 파생하지 않는다.
- **E7 워크플로우가 상태를 추가했다.** 그 상태는 `unmappedStates` 로 나타난다 — **이 화면이 곧 그 해결 창구**다. `V500__boards.sql:28` 이 「워크플로우 상태 변경 시 컬럼 재동기화는 후속 FR」이라 적은 그 미완 1건이, 자동 동기화 없이 **수동 경로로** 닫힌다.

## §8b. 상호작용 상태 표 (design 리뷰 CONCERN-2)

초안은 상태를 `E1`(상태 0개 컬럼) 하나만 정했다. **빈 상태는 기능이다** — 명세가 없으면
구현자가 「항목이 없습니다.」를 낸다. 사용자가 **보는 것**으로 적는다(백엔드 동작이 아니라).

| 기능 | 로딩 | 빈 | 에러 | 성공 | 부분 |
|---|---|---|---|---|---|
| **설정 화면 전체** | 컬럼 골격 3개 + 패널 골격(스켈레톤) | — | 조회 실패 시 사유 + 「다시 시도」 + 「보드로 돌아가기」 | — | `truncated=true` 면 상단에 「카드가 많아 일부만 셉니다」 고지 |
| **컬럼 목록** | 위와 동일 | **컬럼 0개** — 「이 보드에 컬럼이 없습니다. 컬럼을 만들면 상태를 끌어다 놓을 수 있습니다.」 + `＋ 컬럼 추가` 기본 액션 | — | — | 상태 0개 컬럼은 「상태 없음 — 끌어다 놓으세요」 (E1) |
| **미매핑 패널** | 스켈레톤 2줄 | **미매핑 0건** — 「모든 상태가 컬럼에 배정됐습니다.」 (경고 아님. **이것이 정상 상태다**) | — | — | — |
| **상태 드래그** | 드롭 직후 낙관 반영 + 해당 칩 흐림 | — | 되돌림 + 409 는 「그 상태는 이미 다른 컬럼에 있습니다」 · 그 외 공통 실패 문구 (G2) | 칩이 목적지에 자리 잡고 흐림 해제 | 연속 드롭은 직렬화 대기 (E8) |
| **WIP 제한 편집** | 입력 비활성 + 스피너 | 빈 값 = 무제한(정상) | 값 복원 + 사유 | 저장 표시 후 사라짐 | — |
| **컬럼 추가** | 버튼 비활성 + 스피너 | — | 다이얼로그 유지 + 사유(입력 보존) | 다이얼로그 닫힘 + 새 컬럼이 끝에 | — |
| **컬럼 삭제** | 확인 버튼 비활성 | — | 다이얼로그 유지 + 사유 | 다이얼로그 닫힘 + 상태가 미매핑으로 | `truncated` 면 「1000+ 건」 (BLOCKER-1) |
| **컬럼 순서** | 드래그 중 자리표시자 | — | 순서 되돌림 + 사유 | 새 순서 고정 | — |
| **권한 없음** | — | — | — | — | 편집 컨트롤 전부 비활성 + 「보드를 설정할 권한이 없습니다」 (S7) |

**빈 상태 3종의 온도.** 컬럼 0개는 **행동 유도**(추가 버튼), 미매핑 0건은 **안심**(경고 색 금지),
상태 0개 컬럼은 **미완 표시**(다음 행동이 드래그임을 말한다). 셋을 같은 회색 「없음」으로 그리지 않는다.

## §9. 제약 조건

- **한 PR = 한 BC.** `agile-planning` 만 건드린다. `project-workflow` 의 상태 목록은 기존 `service.listWorkflowStates` 경유로만 읽는다 — 직접 import 금지.
- **병행 세션 충돌.** 다른 세션이 `.worktrees/board-summary-can-delete` 에서 `BoardResponses.kt` · `BoardController.kt` 를 건드린다. **컨트롤러를 만지기 전에 그 PR 머지 여부를 확인**한다.
- **Card layout·Estimation·Working days·Issue detail view 는 범위 밖.** 탭 골격만 그리고 비활성으로 둔다.
- **부채 179 는 이 PR 이 안 고친다** — E2 가 재현 경로를 넓히지만 처방은 별건이다.

## §10. 측정 가능한 완료 기준

1. `/projects/{key}/board/settings?board={id}` 가 Columns 탭을 그린다.
2. `?board=` 없이 들어오면 보드 화면으로 되돌아간다 (S8 · 기본 보드 규칙 4번째 미생성).
3. CREATE 권한자에게만 더보기 메뉴에 「보드 설정」이 뜬다 (S7).
4. 미매핑 상태를 컬럼으로 드래그하면 `PUT …/states` 가 **집합 전체**로 호출된다 (S2).
5. 컬럼에서 상태를 떼면 미매핑 패널에 나타난다 (S3).
6. WIP 제한을 넣고 지울 수 있고, 보드 화면 `WipCountBadge` 가 그것을 반영한다 (S4).
7. 컬럼 추가가 **상태 0개 컬럼**을 만들고 화면이 그것을 「상태 없음」으로 표시한다 (S5·E1).
8. 컬럼 삭제 다이얼로그가 **영향 카드 수**를 보이고, 삭제 후 상태가 미매핑으로 돌아온다 (S6).
9. 컬럼 이름 변경이 `PATCH` 로 저장된다 (R9).
10. 컬럼 순서 변경이 `PUT …/columns/order` 로 저장되고 보드 화면 순서가 바뀐다 (R10).
11. 한 상태를 두 컬럼에 넣으려 하면 **409** 를 받고 UI 가 드롭을 되돌린다 (E3).
12. 순서 배열이 불완전하면 **400** (E4).
12b. 컬럼이 1개인 보드에서 **삭제 버튼이 비활성**이고 사유가 보인다 (R7 · G1).
12c. 낙관적 드롭이 **409 뿐 아니라 모든 실패**에서 되돌아간다 (E3 · G2).
13. `agile-planning` 모듈 전량 + `apps/web` vitest + 표적 E2E 가 EXIT=0.
14. 마이그레이션 **0건** · `BoardRepository.kt` **443줄 이하**.

## Sanity Check

**gap 3건 발견 · 2건 스스로 보강 · 1건 Maxi 판단 필요.**

- ❓**G1 🔴 마지막 컬럼 삭제를 막을지가 스펙에 없다.** E2 가 부채 179(컬럼 0개 → 500)의 재현
  경로를 **이 PR 이 넓힌다**고 적어 놓고 처방을 안 정했다. 오늘 컬럼 0개 보드는 자가 치유가
  만들어 내는 드문 상태인데, 이 PR 이 **사용자가 한 번의 클릭으로 도달할 수 있게** 만든다.
  → **보강.** R7 에 조건을 더한다 — **컬럼이 1개면 삭제 버튼을 비활성**하고 사유를 보인다.
  서버는 그대로 두고(별건) UI 가 도달을 막는다. 지라는 이 제약을 명시하지 않았으나
  **BTS 는 컬럼 0개에서 500 이 나므로 준용할 수 없다** — 편차가 아니라 **결함 회피**다.
- ❓**G2 🟡 낙관적 반영의 되돌리기 기준이 모호하다.** E3 이 「409 면 되돌린다」만 적었다.
  다른 실패(네트워크·500)는? → **보강.** **모든 실패에서 되돌린다**로 고정하고, 409 만
  전용 문구(「그 상태는 이미 다른 컬럼에 있습니다」), 나머지는 공통 실패 문구를 쓴다.
- ❓**G3 🔴 탭 골격 7종을 지금 그리는 것이 옳은가 → Maxi 확정으로 해소.**
  초안 R3 은 「6개를 비활성으로 그린다」였는데, 그것은 **「누를 곳은 있는데 아무 일도 안 일어나는」
  화면**을 6개 배포하는 것이고 장부가 경계한 「도달할 UI 가 없는 기능」의 **거울상**이다.
  → **Maxi 확정(2026-09-04) — Columns 탭 하나만 그린다.** R3 을 그대로 고쳤다.
  부수 효과로 탭바 자체가 불필요해져 §5 N6 의 Radix Tabs 고려도 이 PR 에서는 소멸한다 —
  **두 번째 탭을 만드는 PR 이 탭바를 도입**하고 그때 ADR 의 「패널 전환 = Radix Tabs」를 적용한다.

**결론 — ✅ 통과.** gap 3건 전부 처리(G1·G2 자체 보강 · G3 Maxi 확정). 미해결 결정 없음.
