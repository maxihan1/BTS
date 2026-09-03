# 보드 컬럼이 워크플로우 상태를 여러 개 매핑한다 (컬럼:상태 1:1 → 1:N)

> 티어 T3 · type migration · BC agile-planning · PR #444
> 관련 FR — FR-BD-01(칸반 보드 · 원 스펙 `2026-06-20`)의 컬럼 모델 확장
> plan `docs/plans/2026-09-03-board-column-multi-state.md` · ADR `docs/adr/2026-09-03-board-column-multi-state.md`
>
> **형제 작업 — 부채 177**(`TODOS.md`). 보드 설정 표면 **전체**의 갭 실측과 지라 근거 J7~J21 이
> 그 항목 본문에 있다. 이 PR 은 지라 Board settings 7개 탭 중 **`Columns` 하나**를 담당하고,
> 나머지 6개(스윔레인 UI · 카드 레이아웃 · 추정 · 근무일 · 이슈 상세 뷰 · 설정 화면 뼈대)는
> **별도 PR** 이다(Maxi 확정 2026-09-03).

## 사용자 시나리오 (Given-When-Then)

**S1. 두 상태를 한 컬럼에 묶는다.**
Given 프로젝트 워크플로우에 `in_progress` 와 `in_review` 가 따로 있고 보드에 컬럼이 둘로 나뉘어 있다
When 보드 관리자가 `in_review` 를 「진행 중」 컬럼으로 옮긴다
Then 컬럼이 하나로 합쳐지고, 두 상태의 이슈가 같은 컬럼에 나타난다. 어느 이슈도 사라지지 않는다.

**S2. 합쳐진 컬럼 안에서 상태를 구분해 옮긴다.**
Given 「진행 중」 컬럼이 `in_progress` · `in_review` 둘을 담는다
When 사용자가 카드를 그 컬럼의 `in_review` 자리로 드롭한다
Then 이슈 상태가 `in_review` 가 된다. **서버가 둘 중 하나를 고르지 않는다** — 요청이 상태를 지목한다.

**S3. 상태를 컬럼에서 뺀다.**
Given 「완료」 컬럼이 `done` · `wont_fix` 를 담는다
When 관리자가 `wont_fix` 를 컬럼에서 뺀다
Then `wont_fix` 는 **미매핑 상태**가 되고, 그 상태의 이슈는 보드에 안 보이며 `unplacedCount` 에 잡힌다.

**S4. 워크플로우에 상태가 추가돼도 조용히 사라지지 않는다.**
Given 보드가 만들어진 뒤 워크플로우에 `blocked` 가 추가됐다
When 이슈가 `blocked` 로 전환된다
Then 그 이슈는 보드에서 빠지지만 **미매핑 목록에 `blocked` 가 이름과 함께 드러난다**. 관리자가 어느 컬럼에 넣을지 고를 수 있다.

**S5. 기존 보드는 그대로 보인다.**
Given 마이그레이션 이전에 만들어진 보드가 있다
When 마이그레이션을 적용한다
Then 컬럼 구성·카드 배치가 **이전과 완전히 같다**. 컬럼당 상태가 1개인 1:N 이 된다.

## Jira 대조 (전 타입 필수)

**§1-0 재사용 grep — 승계 0건.** 기존 10개 문서 중 컬럼:상태 매핑을 다룬 행이 없다. 보드 선행
조사(J1~J13 · 2026-09-01)는 보드 종류·활성 스프린트·CRUD 만 다뤘다. 아래는 **전량 신규 조회**
(2026-09-03)이며 근거 표 원문은 [plan `## Jira 대조`](../plans/2026-09-03-board-column-multi-state.md)
에 있다. 이 절은 **채택 판정과 편차**를 확정한다.

| # | 요지 | 출처 · 구분 |
|---|---|---|
| **J1** | *"you can assign multiple statuses to a single column…"* | [Configure columns](https://support.atlassian.com/jira-software-cloud/docs/configure-columns/) · Cloud |
| **J2** | *"Drag and drop a status from the **Unmapped statuses** panel to the appropriate column."* | 동일 · Cloud |
| **J3** 🔴 | *"each status will be represented as a drop zone on your board's column"* | [Map Multiple Statuses per Column](https://community.atlassian.com/forums/Jira-articles/New-Features-Additional-Done-Statuses-Map-Multiple-Statuses-per/ba-p/1638591) · Bryan Lim(**Atlassian Team · Jira Software PM**) · Cloud |
| **J4** 🔴 | *"notice that the 'Done' and 'Won't Fix' statuses are now represented as drop zones in the last column when you drag and drop issues on the board"* | 동일 · Cloud |
| **J5** | *"Any Jira workflow statuses mapped to the deleted column are moved back to the **Unmapped statuses** panel."* | [Configure columns](https://support.atlassian.com/jira-software-cloud/docs/configure-columns/) · Cloud |
| **J6** | *"Jira only considers work items in the right-most column of your board as complete."* | 동일 · Cloud |
| **J7** 🔴 | resolution 화면은 **전환**에 붙는다 — *"Create a **screen** with the resolution field on it, then map this screen to **the transition**"* | [Best practices on using the Resolution field](https://support.atlassian.com/jira/kb/best-practices-on-using-the-resolution-field-in-jira-cloud/) · 2026-09-04 · Cloud |
| **J8** 🔴 | *"the workflow **transition**...manages the Resolution field"* | 동일 · Cloud |
| **J9** | 재오픈 전환은 post-function 으로 resolution 을 비운다 — *"If you happen to have a **reopening transition**...you'll need to create a **post-function** to clear the value of the 'Resolution' field."* | 동일 · Cloud |

### 채택 판정

- **J1 채택.** 이 PR 의 존재 이유다. 컬럼이 상태 **1개 이상**을 담는다.
- **J2 채택.** 미매핑 상태를 1급 개념으로 들인다(Maxi 확정). 오늘 BTS 에는 이 개념이 아예 없다 —
  `seedColumns` 가 워크플로우 상태 **전량**을 컬럼으로 만들어서 미매핑이 생길 수 없었다.
- **J3·J4 채택 — 이 PR 의 API 계약을 정하는 행이다.** 지라는 컬럼을 상태별 **드롭존**으로 쪼개고
  사용자가 드롭 지점으로 상태를 지목한다. **서버는 상태를 추론하지 않는다.** 따라서
  `MoveCardRequest` 가 `toColumnId` 대신 **`toStateKey`** 를 받는다(R6). 착수 시점에 「어느 상태로
  갈지 결정 불가」를 최대 난점으로 적었으나, 지라의 답은 「그 결정을 서버가 하지 않는다」였다.
- **J5 채택.** 컬럼을 지우면 그 컬럼의 상태들은 **미매핑으로 돌아간다.** 이슈는 손대지 않는다.
- **J6 기각** → **X2**.
- **J7·J8·J9 채택 — 리뷰 BLOCKER-1 의 처방이 여기서 나왔다.** 지라는 resolution 을 **전환**에
  붙인다. 컬럼도 상태도 아니다. **BTS 백엔드는 이미 그렇게 한다** — `IssueRepository.kt:373` 이
  「워크플로우 validator 가 DONE 진입 시 resolution 필수 불변식을 강제한다(B7)」이고 `:1917` 은
  `targetStateIsDone` 으로 판단한다. **어긋난 것은 프론트뿐**이다(`board-drop.ts:322` 가 컬럼
  `category` 를 본다). 오늘은 컬럼:상태가 1:1 이라 두 기준의 결과가 항상 같아 안 드러난다 —
  **1:N 이 그 불일치를 드러낸다.** 이 PR 이 프론트를 백엔드·지라 쪽으로 맞춘다(R13).

### 의도적 편차

| # | 편차 | 근거 |
|---|---|---|
| **X1** | **한 상태는 한 컬럼에만 매핑된다** — `UNIQUE (board_id, state_key)` 를 유지한다 | 지라 문서가 「한 상태를 두 컬럼에」를 **다루지 않는다.** 매핑 절차가 「Unmapped 패널 ↔ 컬럼 이동」(J2)과 「컬럼 삭제 시 Unmapped 복귀」(J5)로만 서술돼 **단일 배정을 함의하나 원문 확증이 없다.** 계약 §5 는 근거 없는 자체 발명을 금지하므로 **현행 제약을 유지**한다. 푸는 쪽이 발명이다. 실질 근거도 있다 — 풀면 `placeCards` 가 같은 이슈를 두 컬럼에 넣어 **사용자가 카드를 두 번 본다.** |
| **X2** | **완료 판정을 「최우측 컬럼」으로 옮기지 않는다** — `category` 스냅샷을 유지한다 | J6 은 지라가 컬럼 **위치**로 완료를 판정한다고 말한다. BTS 는 `board_columns.category`(TODO/IN_PROGRESS/DONE 스냅샷)로 판정하고, 그 값은 `statuses.category` 에서 온다. 위치 기반으로 바꾸면 스프린트 완료·번다운·백로그 완료 판정이 전부 흔들리는데 이 PR 범위 밖이다. **1:N 이 되면 한 컬럼에 서로 다른 category 의 상태가 섞일 수 있으므로** 그때의 컬럼 category 규칙을 R5 가 정한다. ★**초안의 근거가 부분적으로 거짓이었다(리뷰 BLOCKER-1).** 「`category` 는 표시용이라 파급이 표시에 한정된다」는 `agile-planning` **백엔드 8곳만** 실측한 결과였고, 프론트 `board-drop.ts:322` 는 그 값으로 **해결 방안 모달을 분기**하고 있었다. R13 이 그 분기를 대상 상태 기준으로 옮긴 **뒤에야** 「표시 전용」이 참이 된다. |
| **X3** | **UI 는 이 PR 에 없다** — 드롭존·미매핑 패널 화면은 후속 PR | Maxi 확정. FR-BD-04 가 #421(스키마·백엔드) → #422·#424(UI) 로 쪼갠 선례와 같다. 백엔드 계약이 먼저 서야 UI PR 이 그것을 보고 만든다. **대신 하위 호환(R7)이 필수다** — 안 그러면 이 PR 이 머지되는 순간 기존 보드 화면이 깨진다. |

### 조회했으나 원문을 확보하지 못한 것

- **같은 상태를 두 컬럼에 동시 매핑할 수 있는가.** 문서가 이 경우를 다루지 않는다 → X1 의 근거.
- **컬럼당 상태 수 상한.** 어느 문서도 수치를 적지 않는다 → BTS 도 상한을 두지 않는다(N4).
- **같은 컬럼 안 상태 간 이동.** 검색 요약에 「드래그로는 안 되고 상세 화면을 써야 한다」가 있으나
  **출처가 사용자 답변**이라 계약 §1 의 근거 도메인 조건을 만족하지 못한다. **채택하지 않는다** —
  BTS 는 같은 컬럼 안 상태 간 드롭도 R6 계약으로 자연히 지원된다.

## 기능 요구사항 (FR)

| # | 요구사항 |
|---|---|
| **R1** | `board_columns` 에서 `state_key` 를 분리해 **`board_column_states`** 연결 테이블을 만든다. 컬럼 1개가 상태 **0개 이상**을 담는다. |
| **R2** | 기존 데이터는 **컬럼당 상태 1개**로 백필된다. 마이그레이션 전후 컬럼 구성·카드 배치가 동일하다(S5). |
| **R3** | `BoardCardPlacement.placeCards` 가 이슈를 **컬럼의 상태 집합** 중 하나에 매칭해 배치한다. 어느 컬럼의 어느 상태에도 없는 이슈는 종전대로 제외되고 `unplacedCount` 에 잡힌다. |
| **R4** | `seedColumns` 는 종전대로 **상태 1개당 컬럼 1개**를 만든다. 신규 보드의 초기 모습은 안 바뀐다. |
| **R5** | 컬럼의 `category` 는 **그 컬럼이 담은 상태들의 category** 로 정한다. 규칙 — 상태가 1개면 그것, 여럿이면 **`DONE` > `IN_PROGRESS` > `TODO` 우선순위의 최댓값**. ★**이 값은 표시 전용이다. 어떤 로직 분기도 이 값을 읽지 않는다** — R13 이 프론트의 마지막 분기를 걷어낸 뒤 그 사실이 실제가 된다(그 전까지는 거짓이었다 · 리뷰 BLOCKER-1). |
| **R6** | `MoveCardRequest` 가 **`toStateKey`** 를 받는다(J3·J4). 서버는 그 상태가 **그 보드의 어느 컬럼엔가 매핑돼 있는지**만 검증하고 `IssueTransitionPort.transition` 에 그대로 넘긴다. |
| **R7** | **하위 호환** — `toColumnId` 도 계속 받는다. 그 컬럼의 상태가 **정확히 1개**면 그 상태로 해석하고, **2개 이상이면 400** 으로 거부하며 「`toStateKey` 를 쓰라」고 알린다. X3 이 UI 를 후속으로 미루므로 이것이 없으면 머지 즉시 화면이 깨진다. ★**목적을 명시한다(Maxi 확정)** — 이것은 **외부 클라이언트 안전망**이고, R12 이후 **저장소 안에서 이 경로를 쓰는 코드는 0**이다(`toColumnId` 소비자 프론트 5파일을 R12 가 전부 바꾼다). 즉 테스트만 이 경로를 지킨다. 그 사실을 숨기지 않고 적는다 — 드롭존 UI PR 이 오기 전까지의 과도기 계약이다. |
| **R8** | **미매핑 상태 목록**을 보드 조회 응답에 싣는다(J2). 워크플로우가 주는 상태 중 그 보드의 어느 컬럼에도 없는 것들을 `key`·`name`·`category` 와 함께 낸다. 기준 목록은 **`listStates(projectKey, null)`** — `createBoard:124` 가 시드에 쓰는 것과 **같은 호출**이다(이슈 타입별로 가르지 않는다). ❓G3 |
| **R9** | **컬럼 관리 API** — 컬럼 생성 · 삭제 · 상태 매핑 변경(컬럼에 상태 추가/제거)을 제공한다. 이것이 없으면 1:N 을 쓸 방법이 없다. |
| **R10** | 컬럼 삭제 시 그 컬럼의 상태들은 **미매핑으로 돌아간다**(J5). 이슈는 손대지 않는다. |
| **R11** | 응답 DTO 3종(`BoardColumnResponse` · `BoardColumnWithCardsResponse` · `ColumnMetaResponse`)의 `stateKey: String` 이 **`states: List<ColumnStateResponse>`**(`key`·`name`·`category`)가 된다. ★키 배열만으로는 부족하다 — **R13 이 대상 상태의 `category` 를 읽어야** resolution 을 판정한다. 키만 주면 프론트가 별도 조회를 해야 하고 그것이 N+1 이다. |
| **R12** ❓G4 | **프론트가 신규 계약(`toStateKey`)을 쓰도록 최소 수정한다.** 드롭존 UI 없이 「컬럼에 떨구면 그 컬럼의 **첫 상태**로」 보낸다 — 상태가 1개인 오늘의 보드에서는 **동작이 완전히 같고**, 2개 이상인 컬럼에서는 UI PR(X3)이 드롭존을 만들 때까지의 과도기 동작이다. **이것이 없으면 사용자가 상태 2개를 묶는 순간 기존 화면이 R7 의 400 을 받는다** — 「기능은 있는데 쓰면 화면이 깨진다」가 된다. |
| **R13** 🔴 | **해결 방안(resolution) 판정을 컬럼이 아니라 「카드가 가는 상태」로 옮긴다.** `board-drop.ts:322` 가 오늘 `toColumn.category === 'DONE'` 으로 분기하는데, 지라는 **전환**에 붙이고(J7·J8) BTS 백엔드도 이미 **대상 상태**로 판단한다(`IssueRepository.kt:373` · `:1917` `targetStateIsDone`). **어긋난 것은 프론트뿐이고, 1:N 이 그 불일치를 드러낸다.** 판정을 `toStateKey` 가 가리키는 상태의 `category` 로 바꾼다. |

## 비기능 요구사항 (NFR)

| # | 요구사항 |
|---|---|
| **N1** | 마이그레이션은 **데이터 무손실**이다. `board_columns` 행 수·`state_key` 값 집합이 백필 전후로 같다. |
| **N2** | `BoardRepository.kt` 를 **더 늘리지 않는다.** 오늘 443줄로 `DEVELOPMENT.md §2.1` 상한을 이미 넘었다(부채 157). 새 쿼리는 별도 파일(예: `BoardColumnStateRepository.kt`)로 뺀다. |
| **N3** | 보드 조회 쿼리가 **N+1 로 늘어나지 않는다.** 컬럼–상태는 조인 1회로 읽는다. ★**이 항목에는 자동 판정이 없다**(리뷰 CONCERN-5). 저장소 선례도 마찬가지다 — `BoardIssueLookupAdapterTest.kt:46` 이 「N+1 없이 단일 쿼리」를 **주석에만** 적고 쿼리 수를 세지 않는다. 따라서 N3 은 **코드 리뷰가 지는 항목**이고, Task 3 의 테스트는 「조인 결과가 옳은가」만 잰다. 지켜지는 척하지 않는다. |
| **N4** | 컬럼당 상태 수에 **상한을 두지 않는다**(지라도 두지 않는다). 컬럼 0개 상태는 허용한다 — 미매핑 이동의 중간 상태다. |
| **N5** | `WorkflowStateCatalog.listStates` 포트 시그니처는 **바뀌지 않는다.** 시드 방식만 바뀐다. BC 격리 유지 — `agile-planning` 은 `project-workflow` 를 포트로만 닿는다. |
| **N6** | jOOQ 생성물은 **커밋하지 않는다.** `.gitignore:21` 이 `**/src/generated/jooq/` 를 제외하고 `compileKotlin` 이 `generateJooq` 에 `dependsOn` 이다(ADR 2026-08-18 실측). |

## API 인터페이스 (REST)

### 변경

**`POST /api/v1/boards/{id}/cards/{issueKey}/move`** — 요청 바디

```jsonc
// 신규 (권장)
{ "toStateKey": "in_review", "expectedVersion": 3, "resolutionId": null }
// 하위 호환 (R7) — 컬럼의 상태가 1개일 때만
{ "toColumnId": "…uuid…", "expectedVersion": 3, "resolutionId": null }
```

- 둘 다 없으면 **400**. 둘 다 있으면 **400**(모호).
- `toStateKey` 가 그 보드의 어느 컬럼에도 없으면 **404**(`AGILE_BOARD_STATE_NOT_MAPPED`).
- `toColumnId` 가 가리키는 컬럼의 상태가 2개 이상이면 **400**(`AGILE_COLUMN_STATE_AMBIGUOUS`).

**`GET /api/v1/boards/{id}`** — 응답

```jsonc
{
  "columns": [ { "columnId": "…", "stateKeys": ["in_progress", "in_review"], "name": "진행 중", … } ],
  "unmappedStates": [ { "key": "blocked", "name": "차단됨", "category": "TODO" } ],  // R8
  "unplacedCount": 2
}
```

### 신규 (R9)

| 메서드 · 경로 | 하는 일 |
|---|---|
| `POST /api/v1/boards/{id}/columns` | 컬럼 생성. 바디 `{ name, stateKeys[], displayOrder? }` |
| `DELETE /api/v1/boards/{id}/columns/{columnId}` | 컬럼 삭제. 담긴 상태는 미매핑으로 복귀(R10) |
| `PUT /api/v1/boards/{id}/columns/{columnId}/states` | 그 컬럼의 상태 집합을 통째로 교체. 바디 `{ stateKeys[] }` |

`PUT` 을 고른 이유 — 상태 추가·제거를 각각의 엔드포인트로 두면 「지금 이 컬럼의 상태 집합」이
클라이언트와 서버 사이에서 갈릴 수 있다. 집합 전체를 보내면 X1(한 상태는 한 컬럼에만)의 위반을
**한 요청 안에서** 판정할 수 있다.

권한은 기존 컬럼 API(`PATCH /{id}/columns/{columnId}`)의 `loadBoardWithCreate` 게이트를 승계한다.

## 데이터 모델 변경

**신규** `V508__board_column_states.sql` (agile-planning BC 대역 V500–V599 · V507 이 최신이라 V508 이 빈자리)

서식·형태의 **정본은 `V203__add_global_status_catalog.sql:36-60` 의 `workflow_statuses`** 다 —
같은 저장소 안에 이미 「부모 ↔ 키 N:M 연결 테이블」 선례가 있다.

```sql
CREATE TABLE board_column_states (
    id            UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    column_id     UUID        NOT NULL REFERENCES board_columns (id) ON DELETE CASCADE,
    board_id      UUID        NOT NULL REFERENCES boards (id) ON DELETE CASCADE,  -- X1 강제용 비정규화
    state_key     VARCHAR(50) NOT NULL,
    display_order INTEGER     NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (board_id, state_key),    -- X1: 한 상태는 한 보드에서 한 컬럼에만
    UNIQUE (column_id, state_key)
);
```

- **`board_id` 비정규화가 X1 의 실질이다.** `column_id` 만 두면 「한 상태가 같은 보드의 두 컬럼에」를
  DB 가 못 막는다. `V500` 의 `UNIQUE (board_id, state_key)` 가 지키던 불변식을 **그대로 옮기는**
  것이고, 새 제약을 발명하는 것이 아니다.
- **FK 인덱스 2개**를 명시 생성한다 — `DATA.md §7`(PostgreSQL 은 FK 인덱스를 자동 생성하지 않는다).
  단 `UNIQUE (board_id, state_key)` 의 leftmost prefix 가 `board_id` 를 덮으므로 `column_id` 만
  따로 만든다(`V500:36-38` 이 같은 판단을 적어 뒀다).
- **`display_order` 는 컬럼 **안**의 드롭존 순서다**(J3 — 상태 하나가 드롭존 하나). 컬럼끼리의 순서는
  종전대로 `board_columns.display_order` 가 진다. 두 값의 의미가 다르므로 이름이 같아도 섞지 않는다. ❓G2
- **백필** — `INSERT … SELECT id, board_id, state_key, 0 FROM board_columns` (R2).
- **`board_columns.state_key` 는 이 PR 에서 DROP 하지 않는다.** `DATA.md §4` 3단 분할 —
  ①신설+백필(V508) ②코드 전환 ③DROP(후속). 한 PR 에서 신설과 DROP 을 함께 하면 롤백 창이 사라진다.
  대신 **쓰기 경로가 두 곳을 모두 채우는** 이중 기록을 이 PR 이 진다(E5).
- ❓**G1 — 그 이중 기록이 E1(상태 0개 컬럼)과 충돌한다.** `board_columns.state_key` 는
  `NOT NULL`(`V500:32`)이라 **상태 0개 컬럼에 쓸 값이 없다.** V508 이
  `ALTER TABLE board_columns ALTER COLUMN state_key DROP NOT NULL` 을 함께 한다 —
  제약 **완화**라 기존 행은 무영향이고 데이터 파괴가 없다. 이 한 줄이 없으면 R9 의 컬럼 생성이
  상태를 반드시 요구하게 되어 「빈 컬럼을 만들고 상태를 옮겨 넣는」 지라의 조작 순서(J2)를 못 따른다.

## 엣지 케이스

| # | 케이스 | 처리 |
|---|---|---|
| **E1** | 컬럼에 상태가 0개 | 허용(N4). 카드 0장으로 렌더된다. 미매핑 이동의 중간 상태다 |
| **E2** | 이슈의 상태가 어느 컬럼에도 없음 | 종전대로 제외 + `unplacedCount`. 이제 `unmappedStates`(R8)가 **왜 빠졌는지**도 알려준다 |
| **E3** | 같은 컬럼 안 상태 간 이동 | R6 계약으로 자연히 지원. `toStateKey` 가 지목하므로 컬럼이 같아도 무방하다 |
| **E4** | `toStateKey` 가 워크플로우엔 있으나 이 보드에 미매핑 | **404** `AGILE_BOARD_STATE_NOT_MAPPED`. 400 이 아닌 이유 — 요청 형식은 옳고 대상이 없다 |
| **E5** | 이중 기록 창에서 두 곳이 갈림 | `board_columns.state_key` 와 `board_column_states` 를 **같은 트랜잭션**에서 쓴다. 컬럼의 상태가 2개 이상이면 레거시 컬럼에는 **첫 상태**를 쓴다(읽기는 이미 신규 테이블만 본다 — 레거시는 롤백 대비 사본일 뿐). ★**「첫」의 정의는 `(display_order, state_key)` 오름차순 최소다**(리뷰 CONCERN-2). 정의를 안 적으면 이중 기록과 R12 의 프론트 전송이 **서로 다른 「첫」을 골라** 레거시 컬럼과 화면이 갈린다 — #440 이 `(created_at, id)` tie-break 를 같은 이유로 명시했다. |
| **E6** | 마이그레이션 재적용(멱등) | `INSERT … ON CONFLICT (board_id, state_key) DO NOTHING`. 부채 161 이 지적한 V506 비멱등을 V508 이 반복하지 않는다 |
| **E7** | X1 위반 요청 (`PUT states` 에 다른 컬럼이 쓰는 상태) | **409** `AGILE_STATE_ALREADY_MAPPED` + 어느 컬럼이 쓰는지 알린다 |
| **E8** | 마지막 컬럼 삭제 | 허용. 보드에 컬럼 0개는 이미 가능하다(`ensureScrumBoard` 가 컬럼 0개 보드를 만든다 — `BoardApplicationService.kt:183`) |
| **E9** | `stateKeys` 배열에 중복 | 400. `PUT` 바디 검증에서 잡는다 |
| **E10** | 프론트 Zod 가 `stateKey: string` 을 기대 | ★**learning 2026-05-30 의 양식이다.** 산재한 인라인 mock 이 `z.parse` 런타임에서만 터진다. `stateKey` 사용 **17파일 전수**를 task `files` 에 넣고 `tsc --noEmit` 을 동반한다 |
| **E11** | 기존 E2E 가 1:1 을 전제 | `scrum-board` · `board-manage` · `quick-filter` spec 을 동반 실행해 무회귀를 실측한다 |

## 제약 조건

- **한 PR = 한 BC.** `board_columns` 는 `agile-planning` 소유다. `issue-tracking`·`project-workflow`
  는 shared-kernel 포트로만 닿고 포트 시그니처는 안 바뀐다(N5).
- **`BoardRepository.kt` 를 늘리지 않는다**(N2 · 부채 157).
- **`DATA.md §4` 3단 분할** — 이 PR 은 ①신설+백필 과 ②코드 전환 까지. `state_key` DROP 은 후속.
- **UI 없음**(X3). `apps/web` 변경은 **Zod 스키마와 mock 정합 유지에 필요한 최소**로 한정한다 —
  화면 컴포넌트(드롭존·미매핑 패널)는 후속 PR 이다.
- **jOOQ 생성물 미커밋**(N6).

## 측정 가능한 완료 기준

1. `V508` 적용 후 `board_column_states` 행 수 = 적용 전 `board_columns` 행 수. 값 집합 동일(R2·N1).
2. 마이그레이션 전후 **같은 보드의 `GET /boards/{id}` 응답에서 컬럼 구성과 카드 배치가 동일**하다(S5).
3. 한 컬럼에 상태 2개를 매핑한 뒤 `GET` 하면 `stateKeys` 에 둘 다 있고, **두 상태의 이슈가 모두 그 컬럼 카드에 있다**(R1·R3).
4. `toStateKey` 로 카드를 옮기면 이슈가 **정확히 그 상태**가 된다(R6).
5. 상태 2개짜리 컬럼에 `toColumnId` 를 보내면 **400 `AGILE_COLUMN_STATE_AMBIGUOUS`**, 1개짜리면 **성공**한다(R7).
6. 컬럼을 삭제하면 그 상태들이 `unmappedStates` 에 나타나고 **이슈는 그대로다**(R10).
7. 다른 컬럼이 쓰는 상태를 `PUT states` 로 넣으면 **409**(E7 · X1).
8. `V508` 을 JDBC 로 재실행해도 행이 늘지 않는다(E6).
9. `agile-planning` 모듈 테스트 전량 초록 · `BoardRepository.kt` 줄수 **443 이하**(N2).
10. `tsc -p tsconfig.app.json` **0** · vitest 전량 초록 — `stateKey` 17파일 정합(E10).

## Sanity Check — gap 4건 발견, 4건 모두 스스로 보강 (1회)

스펙을 쓴 뒤 스스로 흔들었다. 점검 4항목(누락 요구사항 · 모호한 표현 · 가정 누락 · 엣지 미커버).

| # | gap | 종류 | 처리 |
|---|---|---|---|
| **G1** 🔴 | **스펙 자체의 모순.** E1 이 「상태 0개 컬럼」을 허용하는데 `board_columns.state_key` 는 `NOT NULL` 이다 — 이중 기록(E5)에서 쓸 값이 없다. 이대로면 R9 의 컬럼 생성이 상태를 반드시 요구하게 되어 **지라의 조작 순서(J2 — 빈 컬럼에 상태를 옮겨 넣는다)를 못 따른다** | 엣지 미커버 | V508 이 `DROP NOT NULL` 을 함께 한다(§데이터 모델). 제약 완화라 무손실 |
| **G2** | `board_column_states.display_order` 의 의미를 안 적었다. `board_columns.display_order`(컬럼 순서)와 이름이 같아 섞일 수 있다 | 모호한 표현 | 「컬럼 **안**의 드롭존 순서」로 명시 |
| **G3** | R8 의 미매핑 목록이 **어느 이슈 타입 기준**인지 안 적었다. `listStates` 가 `issueTypeKey` 를 받으므로 실제로 갈릴 수 있다 | 가정 누락 | `createBoard:124` 와 **같은 `listStates(projectKey, null)`** 로 고정 |
| **G4** 🔴 | **누락 요구사항.** 이 PR 이 매핑 변경 API(R9)를 주지만 UI 는 후속(X3)이다. 사용자가 상태 2개를 묶는 순간 **기존 화면이 R7 의 400 을 받는다** — 「기능은 있는데 쓰면 화면이 깨진다」 | 누락 요구사항 | **R12** 추가. 프론트가 `toStateKey`(컬럼의 첫 상태)를 보내게 최소 수정한다. 상태 1개인 오늘의 보드에서는 동작이 완전히 같다 |

### 흔들었으나 문제없던 것

- **R5(컬럼 category 규칙)가 완료 판정을 흔드나** — 아니다. `category` 는 **표시용 스냅샷**이고
  로직 판정에 쓰이지 않는다. `V500:27` 이 그렇게 적었고, 실측도 일치한다 — `agile-planning` 의
  `.category` 참조 8곳이 전부 DTO 변환·매핑이며 완료·번다운 판정에 쓰는 곳이 없다.
  X2(위치 기반 판정 미채택)의 근거이기도 하다.
- **`WorkflowStateCatalog` 포트가 바뀌나** — 아니다(N5). `listStates` 가 주는 상태 목록을
  **어떻게 컬럼으로 묶느냐**만 바뀐다. BC 격리 무영향.
- **`board_id` 비정규화가 새 제약인가** — 아니다. `V500:39` 의 `UNIQUE (board_id, state_key)` 가
  지키던 불변식을 **그대로 옮기는** 것이다(X1).
