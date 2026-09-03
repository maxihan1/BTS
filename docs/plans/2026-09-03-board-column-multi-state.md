# 보드 컬럼이 워크플로우 상태를 여러 개 매핑할 수 있게 한다 (컬럼:상태 1:1 → 1:N)

> 티어: T3
> slug: board-column-multi-state
> type: migration
> agent: db-engineer
> 생성: 2026-09-03

## Brief

**Maxi 지시(2026-09-03).** 「1:n으로 맵핑 하도록 수정 해야 할거 같은데」 · 「머지 완료되면 바로 pr 생성해서 진행하자」.

발단은 「이슈 상태가 바뀌면 보드에 자동 반영되나」라는 질문이었다. 답은 **반영된다 —
동기화가 아니라 저장 자체를 안 한다**. `BoardCardPlacement.placeCards` 가 `issues.current_state_key`
를 `board_columns.state_key` 로 `groupBy` 해 **조회 시점에 배치**하고, 카드 위치는 어디에도
저장되지 않는다. 그 과정에서 드러난 것이 이 작업의 대상이다.

**오늘 BTS 는 컬럼 1개에 상태 1개만 매핑한다.** `board_columns` 각 행이 `state_key` 하나를
갖고 `UNIQUE (board_id, state_key)` 가 그것을 강제한다(`V500__boards.sql:26,29-40` — 주석이
「상태 1:1 매핑」을 명시). 지라의 흔한 구성인 「진행 중 컬럼에 `in_progress` + `in_review` 를
함께」를 만들 수 없고, 컬럼이 상태 수만큼 늘어난다.

### classify 자동 판정을 3곳 교정했다

| 필드 | 자동 | 교정 | 사유 |
|---|---|---|---|
| `slug` | `1-1-1-n` | `board-column-multi-state` | 한글 제목이라 숫자만 남았다 |
| `tier` | `T1` | **T3** | 제목에 마이그레이션 신호가 없어 기본값이 나왔다. `board_columns` 스키마 변경이 필수라 MIGRATION 표면이고, 「섞이면 최고 티어」 |
| `primary_bc` | `project-workflow` | **agile-planning** | 「워크플로우」 키워드에 걸렸다. `board_columns` 는 `agile-planning` 소유다(`modules/agile-planning/.../V500__boards.sql`) |

`type` 도 `backend` → `migration` 으로 바꿨다. 리뷰 렌즈 라우팅(`bts-review-plan`)이 이 값을 읽고,
`migration` 이어야 ceo 렌즈가 붙는다.

## 착수 시점에 이미 아는 것 (재조사 불필요)

### 핵심 난점은 스키마가 아니라 `moveCard` 다

`BoardApplicationService.kt:413`

```kotlin
toStateKey = targetColumn.stateKey   // 컬럼에서 상태가 유일하게 결정된다
```

컬럼에 상태가 여럿이면 **「이 컬럼으로 드래그」가 어느 상태로 가라는 건지 결정 불가**가 된다.
후보가 여럿이고 각각 사용자에게 다르게 보인다 — ①컬럼의 첫 상태 고정 ②현재 상태에서 **허용된
전환**이 있는 상태를 고름(여러 개면?) ③사용자에게 고르게 함. **스펙 단계에서 지라 실물 조회로
답한다. 기억으로 쓰지 않는다.**

### 영향 범위 실측

| 지점 | 규모 |
|---|---|
| `board_columns` 스키마 — `state_key` 를 별도 테이블로 분리 | 마이그레이션 1개 + jOOQ 재생성 |
| `BoardRepository.kt` | **36곳**. 이미 443줄로 `DEVELOPMENT.md §2.1` 상한 초과(부채 157) — **더 늘리지 않는 선에서 고쳐야 한다** |
| `BoardApplicationService.kt` | 3곳 |
| `BoardCardPlacement` | 순수 함수라 수정 자체는 쉽다 |
| API 응답 `stateKey: string` → 배열 | 백엔드 DTO + 프론트 Zod + 컴포넌트 |
| 프론트 `stateKey` 사용 | **17파일** |
| `BoardController` | 7 엔드포인트 중 `GET /{id}` · `PATCH /{id}/columns/{columnId}` 직접 영향 |

`board_columns` 를 건드리는 선행 마이그레이션 — `V500`(생성) `V501`(wip/swimlane) `V504`(quick
filters) `V506` `V507`. `agile-planning` BC 범위는 V500–V599 이고 **V507 이 최신**이다.

### ★★ 1:1 은 지라와 대조된 적이 없다

원 스펙 `docs/specs/2026-06-20-fr-bd-01-kanban-board.md:10` 이 「컬럼 = 상태별 명시 매핑
(`board_columns` 각 행이 워크플로우 상태 1개에 1:1 매핑)」을 설계로 못박았는데, **그 문서에는
`## Jira 대조` 절이 아예 없다.** 작성일이 `jira-parity-contract` 컷오프(2026-08-27)보다 빨라
강제 대상이 아니었다.

즉 1:1 은 지라를 보고 내린 결정도, 의도적 편차(`X*`)로 등재된 것도 아니다. **스펙 단계의 실물
조회가 이 작업에서 선택이 아니라 전제다.**

### 별건으로 붙어 있는 미완 1건

`V500__boards.sql:28` 이 스스로 적었다 — 「워크플로우 상태 변경 시 컬럼 재동기화는 이번 범위
제외 — 후속 FR」. 워크플로우에 상태를 추가하면 기존 보드에 그 컬럼이 안 생기고, 그 상태로 넘어간
이슈는 `unplacedCount` 로 **조용히 빠진다**. 이번 범위에 넣을지는 스펙에서 판단한다.

## 승계한 learnings (컨트롤러 발췌)

- ★**2026-05-30 「Zod 응답 스키마 강화가 산재한 인라인 mock 을 깬다」(PR #46)** — 이번 작업에
  직결한다. `stateKey: string` → `string[]` 은 정확히 그 양식이다. 예방 4항목: ①해당 DTO 식별
  필드로 인라인 mock **전수 grep** → plan task `files` 에 포함 ②공용 fixture builder 검토
  ③**vitest 는 타입체크를 안 하므로 `tsc --noEmit` 필수 동반** ④스키마 강화 task 의 RED 에 기존
  mock 회귀 검증 포함
- 2026-05-28 「`gh pr merge --delete-branch` main worktree 충돌」 — #431·#432·#440 **3연속** 재현.
  머지는 성공하고 원격 브랜치만 수동 삭제하면 된다
- 2026-07-17 「FR ID 는 `fr-index` 가 아니라 `docs/specs` 에서 선점된다」 — 동시 PR 충돌을
  `fr-index` grep 으로는 못 본다

## Jira 대조 (착수 시점 조회 · 2026-09-03)

**§1-0 재사용 grep 결과 — 승계 0건.** `grep -rln "## Jira 대조" docs/specs/ docs/plans/` 로 나온
10개 문서 중 **컬럼:상태 매핑을 다룬 행이 하나도 없다.** 보드 관련 선행 조사(J1~J13 · 2026-09-01)는
보드 종류·활성 스프린트·보드 CRUD 를 다뤘고 컬럼 내부 구조는 건드리지 않았다. 이번 조작
(한 컬럼에 상태 여러 개 · 그 컬럼으로 드래그했을 때의 결과)은 **전량 신규 조회**다.

### 근거 표

| # | 원문 인용 | 출처 · 조회일 · 구분 |
|---|---|---|
| **J1** | *"you can assign multiple statuses to a single column, reducing the number of columns your team uses when moving cards across your board."* | [Configure columns](https://support.atlassian.com/jira-software-cloud/docs/configure-columns/) · 2026-09-03 · **Cloud** |
| **J2** | *"Drag and drop a status from the **Unmapped statuses** panel to the appropriate column."* | 동일 · 2026-09-03 · **Cloud** |
| **J3** 🔴 | *"each status will be represented as a drop zone on your board's column"* | [New Features: Map Multiple Statuses per Column](https://community.atlassian.com/forums/Jira-articles/New-Features-Additional-Done-Statuses-Map-Multiple-Statuses-per/ba-p/1638591) · 2026-09-03 · **Cloud** · 작성자 Bryan Lim = **Atlassian Team 배지 · Jira Software PM**(계약 §1 「공식 답변만」 충족) |
| **J4** 🔴 | *"notice that the 'Done' and 'Won't Fix' statuses are now represented as drop zones in the last column when you drag and drop issues on the board"* | 동일 · 2026-09-03 · **Cloud** |
| **J5** | *"Any Jira workflow statuses mapped to the deleted column are moved back to the **Unmapped statuses** panel."* | [Configure columns](https://support.atlassian.com/jira-software-cloud/docs/configure-columns/) · 2026-09-03 · **Cloud** |
| **J6** | *"Jira only considers work items in the right-most column of your board as complete."* | 동일 · 2026-09-03 · **Cloud** |

### ★ J3·J4 가 이 작업의 핵심 난점을 해소한다

착수 시점에 「`moveCard` 가 어느 상태로 갈지 결정 불가가 된다」를 최대 난점으로 적고 후보 3개를
세웠다 — ①컬럼의 첫 상태 고정 ②허용된 전환이 있는 상태 ③사용자 선택.

**지라는 셋 중 어느 것도 아니다. 시스템이 상태를 추론하지 않는다** — 컬럼을 상태별 **드롭존**으로
쪼개고 사용자가 드롭 지점으로 직접 고른다. 즉 「컬럼으로 드롭」이라는 조작 자체가 없고
「**컬럼 안의 특정 상태로 드롭**」만 있다. 결정 불가 문제가 **API 계약 층에서 사라진다** —
`toStateKey` 를 클라이언트가 보내면 되고, 서버는 그 상태가 그 컬럼에 속하는지만 검증한다.

### 확보하지 못한 것 (조회했으나 원문 없음)

- **같은 상태를 두 컬럼에 동시 매핑할 수 있는가.** `Configure columns` 는 이 경우를 다루지 않는다.
  매핑 절차가 「Unmapped 패널에서 컬럼으로 이동」·「컬럼 삭제 시 Unmapped 로 복귀」(J5)로만
  서술돼 **단일 배정을 함의하나 원문 확증은 없다.** BTS 는 오늘 `UNIQUE(board_id, state_key)` 로
  이미 그것을 강제하며, 이 PR 은 그 제약을 **유지할지 스펙에서 판단**한다.
- **컬럼당 상태 수 상한.** 어느 문서도 수치를 적지 않는다.
- **같은 컬럼 안 상태 간 이동.** 검색 요약은 「같은 컬럼 안에서는 드래그로 상태를 못 바꾸고 상세
  화면을 써야 한다」고 하나, **그 문장의 출처가 사용자 답변**이라 계약 §1 의 근거 도메인 조건을
  만족하지 못한다. 채택하지 않는다.

**채택 판정과 편차 번호(`X*`)는 `/bts-spec` 이 확정한다.** 이 절은 착수 시점 근거 확보 기록이다.

## 도메인 정리

**BC — `agile-planning`.** BC 노트가 「보드 (칸반/스크럼) **컬럼 매핑**」을 책임으로, `BoardColumn` 을
핵심 엔티티로 명시한다. classify 자동 판정의 `project-workflow` 는 「워크플로우」 키워드 오탐이다.

**영향 엔티티** — `Board` · `BoardColumn`(수정) · **`BoardColumnState`**(신규 개념).

**새 용어 2개.** Maxi 승인 전까지 `glossary.md` 에 반영하지 않는다.
- **미매핑 상태 (Unmapped status)** — 워크플로우에는 있으나 그 보드의 어느 컬럼에도 매핑되지 않은 상태.
  그 상태의 이슈는 보드에 안 보이고 `unplacedCount` 에 잡힌다. 지라 `Unmapped statuses` 패널(J2·J5) 대응.
- **드롭존 (Drop zone)** — 컬럼 안에서 상태 하나가 차지하는 드롭 영역(J3·J4). 화면은 후속 PR(X3)이나
  **API 계약이 이 개념 위에 선다** — `toStateKey` 가 드롭존을 지목한다.

### 기존 결정과의 관계

| ADR | 관계 |
|---|---|
| [2026-08-18-workflow-global-status-catalog](../adr/2026-08-18-workflow-global-status-catalog.md) | **충돌 없음. 오히려 전제를 깔아 줬다.** 이 ADR 이 상태 키를 전역화하며 `board_columns.state_key` 를 근거로 들었고(§맥락 :18), 「키는 불변」(D3)을 못박았다. 1:N 매핑은 **그 전역 키를 여러 개 묶는 것**이라 키 불변 계약을 건드리지 않는다. 다만 이 ADR 의 기각 사유가 「보드 컬럼은 키로 매핑하므로 **두 상태가 한 컬럼에 섞여 들어간다**」를 **문제**로 규정했음에 주의 — 그것은 「같아 보이는 것이 같지 않은」 중복 상태가 **의도치 않게** 섞이는 경우였고, 이 PR 의 **의도적** 묶음과는 다르다. |
| [2026-09-01-board-type-and-active-sprint](../adr/2026-09-01-board-type-and-active-sprint.md) | 무관. 보드 **종류**(칸반/스크럼)를 다루고 컬럼 내부 구조를 건드리지 않는다. |

**선례 — 같은 저장소에 이미 있다.** `V203__add_global_status_catalog.sql:36-60` 의 `workflow_statuses`
가 정확히 같은 형태의 N:M 연결 테이블이다(부모 FK CASCADE · 대상 키 · `display_order` ·
`UNIQUE(부모, 대상)` · FK 인덱스 명시). `board_column_states` 는 이 서식을 복제한다.

**BC 격리** — `WorkflowStateCatalog.listStates` 포트는 **안 바뀐다**(N5). 상태 목록을 받아
「어떻게 컬럼으로 묶느냐」만 바뀐다.

## 스펙

정본 **[`docs/specs/2026-09-03-board-column-multi-state.md`](../specs/2026-09-03-board-column-multi-state.md)**
— R1~R12 · N1~N6 · E1~E11 · 완료 기준 10개 · 편차 X1~X3.

핵심 3줄.
1. `board_column_states` 연결 테이블로 컬럼:상태를 1:N 화하고, 기존 데이터는 컬럼당 상태 1개로 백필해 **화면을 무회귀**로 둔다.
2. **`moveCard` 가 `toStateKey` 를 받는다** — 지라가 컬럼을 상태별 드롭존으로 쪼개 사용자가 지목하게 하므로(J3·J4) **서버는 상태를 추론하지 않는다.** 착수 시점 최대 난점이 여기서 소멸했다.
3. 컬럼 관리 API 3종(생성·삭제·상태 집합 교체)과 **미매핑 상태 목록**을 함께 낸다. 이것이 없으면 1:N 을 쓸 방법이 없다.

**형제 작업 — 부채 177**(`TODOS.md` §기능 동작). Maxi 가 지목한 「보드 설정이 통째로 없다」의
갭 실측과 지라 근거 J7~J21 을 그 항목 본문에 남겼다. **별도 PR** 이고 착수 시점에 `/bts` 체인이
자기 스펙을 만든다 — 설계를 미리 적지 않는다. 이 PR 은 지라 Board settings 7개 탭 중
`Columns` 하나를 담당한다.

## Sanity Check

**gap 4건 발견 · 4건 모두 스스로 보강(1회).** 전문은 스펙의 `## Sanity Check` 절.

- ❓**G1 🔴 스펙 자체의 모순** — E1 이 「상태 0개 컬럼」을 허용하는데 `board_columns.state_key` 가
  `NOT NULL` 이라 이중 기록에 쓸 값이 없었다. V508 이 `DROP NOT NULL` 을 함께 한다.
- ❓**G4 🔴 누락 요구사항** — 매핑 변경 API 는 주면서 UI 는 후속이라, 사용자가 상태 2개를 묶는 순간
  기존 화면이 400 을 받는다. **R12**(프론트가 `toStateKey` 를 보내게 최소 수정)를 추가했다.
- ❓G2 `display_order` 의 의미 명시 · ❓G3 미매핑 목록의 기준을 `listStates(projectKey, null)` 로 고정.

**흔들었으나 문제없던 것** — R5(컬럼 category)는 완료 판정을 안 흔든다. `category` 는 표시용
스냅샷이고 `agile-planning` 의 참조 8곳이 전부 DTO 변환이다(`V500:27` 이 그렇게 적었고 실측 일치).

## Plan

**분해 원칙.** Task 4·5·6 이 `BoardResponses.kt`·`BoardApplicationService.kt` 를 공유해 **파일 겹침으로
자동 직렬화**된다(§2 메타 계약). 억지로 병렬화하면 wave 안에서 서로의 산출물을 덮으므로 그대로 둔다.

### Task 1. V508 — `board_column_states` 신설 + 백필 + `state_key` NOT NULL 완화

**메타**.
- agent: `db-engineer`
- files: [`backend/modules/agile-planning/src/main/resources/db/migration/agile-planning/V508__board_column_states.sql`, `backend/modules/agile-planning/src/test/kotlin/com/bts/agileplanning/migration/BoardColumnStatesMigrationTest.kt`]
- depends-on: []
- jira: [J1]

**RED**:
- 파일: `.../migration/BoardColumnStatesMigrationTest.kt` (신규)
- 선례 `KanbanSprintMoveMigrationTest.kt`(#440) — **전용 컨테이너** + Flyway `target` 고정 다단계 + JDBC 직접 재실행으로 멱등 측정
- 테스트:
  ```kotlin
  @Test fun `백필 후 board_column_states 행 수가 board_columns 행 수와 같다`()      // R2·N1
  @Test fun `백필된 state_key 값 집합이 board_columns 의 것과 완전히 같다`()          // R2·N1
  @Test fun `board_id state_key 유일 제약이 두 컬럼에 같은 상태를 막는다`()            // X1·D3
  @Test fun `column_id 전용 인덱스가 존재한다`()                                      // N3
  @Test fun `board_columns.state_key 가 NULL 을 허용한다`()                          // ★G1
  @Test fun `V508 SQL 을 JDBC 로 재실행해도 행이 늘지 않는다`()                        // E6
  @Test fun `컬럼을 지우면 board_column_states 행이 CASCADE 로 함께 지워진다`()        // R10 의 DB 층
  ```
- 실패 메시지 (예상): `relation "board_column_states" does not exist`

**GREEN**:
- 파일: `V508__board_column_states.sql`
- **서식 정본은 `V203__add_global_status_catalog.sql:36-60` 의 `workflow_statuses`** — 발명하지 않는다
- `UNIQUE (board_id, state_key)` + `UNIQUE (column_id, state_key)` · FK 인덱스는 `column_id` 만
  따로(`board_id` 는 UNIQUE 의 leftmost prefix 가 덮는다 — `V500:36-38` 이 같은 판단을 적었다)
- 백필 `INSERT … SELECT id, board_id, state_key, 0 FROM board_columns` + `ON CONFLICT DO NOTHING`(E6)
- `ALTER TABLE board_columns ALTER COLUMN state_key DROP NOT NULL` — **제약 완화라 무손실**
- ⚠️ `board_columns.state_key` 를 **DROP 하지 않는다**(`DATA.md §4` 3단 분할)

**REFACTOR**:
- 주석 밀도를 `V507` 에 맞춘다. 되돌리기 절을 반드시 적는다 — 이 마이그레이션은 **되돌릴 수 있다**
  (신설 테이블 DROP + `SET NOT NULL` 복원). `V507` 과 달리 파괴적 변경이 0 이라는 사실을 명시한다

**검증**: `./gradlew :modules:agile-planning:test --tests '*BoardColumnStatesMigrationTest'`
(파이프 금지 — 종료 코드가 `tail` 것이 된다. 로그는 파일로 받고 `EXIT=$?` 로 읽는다)

---

### Task 2. 도메인 — 컬럼이 상태 **집합**을 갖는다 (`placeCards` · `seedColumns` · category 규칙)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/agile-planning/src/main/kotlin/com/bts/agileplanning/domain/BoardColumn.kt`, `backend/modules/agile-planning/src/main/kotlin/com/bts/agileplanning/domain/BoardCardPlacement.kt`, `backend/modules/agile-planning/src/test/kotlin/com/bts/agileplanning/domain/BoardCardPlacementTest.kt`]
- depends-on: []
- jira: [J1, J3]

**RED**:
- 파일: `BoardCardPlacementTest.kt` (기존 확장)
- 순수 함수라 컨테이너가 필요 없다 — red 를 가장 싸게 본다
- 테스트:
  ```kotlin
  @Test fun `컬럼이 상태 둘을 담으면 두 상태의 이슈가 모두 그 컬럼에 배치된다`()        // R3
  @Test fun `어느 컬럼의 어느 상태에도 없는 이슈는 제외되고 unplacedCount 에 잡힌다`()   // R3·E2
  @Test fun `상태 0개 컬럼은 카드 0장으로 배치된다`()                                  // E1·N4
  @Test fun `컬럼 category 는 담은 상태들의 최댓값이다 DONE 이 IN_PROGRESS 를 이긴다`() // R5
  @Test fun `seedColumns 는 여전히 상태 1개당 컬럼 1개를 만든다`()                     // R4
  @Test fun `컬럼 내 카드 정렬은 rank NULLS LAST priority key 순서를 유지한다`()        // 무회귀
  ```
- 실패 메시지 (예상): `BoardColumn` 에 `stateKeys` 프로퍼티 없음

**GREEN**:
- `BoardColumn.stateKey: String` → **`stateKeys: List<String>`**
- `placeCards` — `issues.groupBy { currentStateKey }` 는 유지하고, 컬럼별로 **자기 `stateKeys` 의
  카드를 모아 합친 뒤** `CARD_COMPARATOR` 로 정렬. `knownStateKeys` 는 전 컬럼의 `stateKeys` 합집합
- category 우선순위 `DONE > IN_PROGRESS > TODO` 를 상수 맵으로

**REFACTOR**:
- category 규칙을 `BoardColumn` 의 함수로 응집 + KDoc 에 R5·X2 근거(「`category` 는 표시용
  스냅샷이고 로직 판정에 안 쓰인다 — `V500:27`」)를 적는다

**뮤테이션 짝** (GREEN 선커밋 뒤): `placeCards` 가 `stateKeys.first()` 만 보게 되돌리면
「상태 둘」 테스트 **1건만** red. ← 이 뮤테이션이 결함 지점(집합 매칭)을 실제로 지난다

**검증**: `./gradlew :modules:agile-planning:test --tests '*BoardCardPlacementTest'`

---

### Task 3. 리포지터리 — 컬럼–상태 조인 읽기 + 이중 기록 쓰기

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/agile-planning/src/main/kotlin/com/bts/agileplanning/repository/BoardColumnStateRepository.kt`, `backend/modules/agile-planning/src/main/kotlin/com/bts/agileplanning/repository/BoardRepository.kt`, `backend/modules/agile-planning/src/test/kotlin/com/bts/agileplanning/repository/BoardColumnStateRepositoryTest.kt`]
- depends-on: [1, 2]
- jira: [J1]

**RED**:
- 파일: `BoardColumnStateRepositoryTest.kt` (신규)
- 테스트:
  ```kotlin
  @Test fun `보드 조회가 컬럼별 상태 집합을 한 번의 조인으로 읽는다`()                  // N3
  @Test fun `컬럼에 상태 둘을 쓰면 두 행이 생기고 display_order 가 보존된다`()          // R1
  @Test fun `쓰기가 board_columns.state_key 에 첫 상태를 함께 남긴다`()                // E5 이중 기록
  @Test fun `상태 0개 컬럼을 쓰면 board_columns.state_key 가 NULL 이 된다`()           // E1·G1
  ```
- 실패 메시지 (예상): `BoardColumnStateRepository` 클래스 없음

**GREEN**:
- **신규 파일** `BoardColumnStateRepository.kt` — ★**N2 를 지킨다.** `BoardRepository.kt` 는 443줄로
  `DEVELOPMENT.md §2.1` 상한을 이미 넘었다(부채 157). 새 쿼리를 거기 넣지 않는다
- `BoardRepository` 는 **호출 위임만** 추가한다. 줄수 증가를 0 에 가깝게 유지하고, 늘어난 줄수를
  검증에서 실측한다
- 이중 기록(E5)은 **같은 트랜잭션**에서. 컬럼 상태가 2개 이상이면 레거시 컬럼에는 첫 상태를 쓴다

**REFACTOR**:
- 조인 쿼리에 KDoc — 「읽기는 신규 테이블만 본다. 레거시 `state_key` 는 롤백 대비 사본일 뿐」

**검증**:
- `./gradlew :modules:agile-planning:test --tests '*BoardColumnStateRepositoryTest'`
- `wc -l BoardRepository.kt` ≤ **443** (N2 실측)

---

### Task 4. 응답 계약 — `stateKeys` 배열 + 미매핑 상태 목록

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/agile-planning/src/main/kotlin/com/bts/agileplanning/web/dto/BoardResponses.kt`, `backend/modules/agile-planning/src/main/kotlin/com/bts/agileplanning/application/BoardApplicationService.kt`, `backend/modules/agile-planning/src/test/kotlin/com/bts/agileplanning/web/dto/BoardResponsesTest.kt`, `backend/modules/agile-planning/src/test/kotlin/com/bts/agileplanning/application/BoardApplicationServiceTest.kt`]
- depends-on: [2, 3]
- jira: [J1, J2]

**RED**:
- 테스트:
  ```kotlin
  @Test fun `BoardColumnResponse 가 stateKeys 배열을 낸다`()                          // R11
  @Test fun `BoardColumnWithCardsResponse 와 ColumnMetaResponse 도 배열을 낸다`()      // R11
  @Test fun `보드 조회가 어느 컬럼에도 없는 상태를 unmappedStates 로 낸다`()            // R8·J2
  @Test fun `unmappedStates 기준이 listStates projectKey null 이다`()                 // R8·G3
  @Test fun `모든 상태가 매핑됐으면 unmappedStates 가 빈 배열이다`()                    // R8
  ```
- 실패 메시지 (예상): `stateKeys` 프로퍼티 없음 / `unmappedStates` 없음

**GREEN**:
- DTO 3종 `stateKey: String` → `stateKeys: List<String>`
- `getBoard` 가 `listStates(projectKey, null)` 결과에서 매핑된 키를 빼 `unmappedStates` 를 만든다
  — **`createBoard:124` 와 같은 호출**이어야 한다(G3)

**REFACTOR**:
- `unmappedStates` 계산을 `BoardCardPlacement` 의 순수 함수로 뺀다(테스트 가능성 · N5 유지)

**검증**: `./gradlew :modules:agile-planning:test --tests '*BoardResponsesTest' --tests '*BoardApplicationServiceTest'`

---

### Task 5. `moveCard` — `toStateKey` 수용 + `toColumnId` 하위 호환

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/agile-planning/src/main/kotlin/com/bts/agileplanning/application/BoardApplicationService.kt`, `backend/modules/agile-planning/src/main/kotlin/com/bts/agileplanning/web/dto/BoardResponses.kt`, `backend/modules/agile-planning/src/main/kotlin/com/bts/agileplanning/web/BoardController.kt`, `backend/modules/agile-planning/src/test/kotlin/com/bts/agileplanning/application/BoardApplicationServiceTest.kt`, `backend/modules/agile-planning/src/test/kotlin/com/bts/agileplanning/web/BoardControllerIntegrationTest.kt`]
- depends-on: [4]
- jira: [J3, J4]

**RED**:
- 테스트:
  ```kotlin
  @Test fun `toStateKey 로 옮기면 그 상태로 전환된다`()                                // R6
  @Test fun `toStateKey 가 그 보드의 어느 컬럼에도 없으면 404`()                        // E4
  @Test fun `같은 컬럼 안 다른 상태로도 옮길 수 있다`()                                 // E3
  @Test fun `toColumnId 만 보내고 그 컬럼의 상태가 1개면 성공한다`()                     // R7
  @Test fun `toColumnId 만 보내고 그 컬럼의 상태가 2개 이상이면 400`()                   // R7
  @Test fun `둘 다 없으면 400 · 둘 다 있으면 400`()                                    // R7
  ```
- 실패 메시지 (예상): `MoveCardRequest` 에 `toStateKey` 없음

**GREEN**:
- `MoveCardRequest` — `toColumnId: UUID?` + `toStateKey: String?` 둘 다 nullable, **둘 중 정확히
  하나**를 요구하는 검증을 서비스가 진다(`@NotNull` 로는 「둘 중 하나」를 표현 못 한다)
- 신규 코드 상수 `AGILE_COLUMN_STATE_AMBIGUOUS`(400) · `AGILE_BOARD_STATE_NOT_MAPPED`(404)
- 예외 핸들러는 `assignableTypes` 로 **스코프를 좁힌다** — 과거 「401→500 변질」 사고의 처방

**REFACTOR**:
- 요청 해석(`toStateKey` 도출)을 private 헬퍼로. `moveCard` 본문은 전환 위임에 집중

**뮤테이션 짝**: 「상태가 그 보드에 매핑됐는지」 검증을 지우면 **404 테스트 1건만** red

**검증**: `./gradlew :modules:agile-planning:test --tests '*BoardApplicationServiceTest' --tests '*BoardControllerIntegrationTest'`

---

### Task 6. 컬럼 관리 API 3종 — 생성 · 삭제 · 상태 집합 교체

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/agile-planning/src/main/kotlin/com/bts/agileplanning/web/BoardController.kt`, `backend/modules/agile-planning/src/main/kotlin/com/bts/agileplanning/application/BoardApplicationService.kt`, `backend/modules/agile-planning/src/main/kotlin/com/bts/agileplanning/web/dto/BoardResponses.kt`, `backend/modules/agile-planning/src/test/kotlin/com/bts/agileplanning/web/BoardControllerIntegrationTest.kt`]
- depends-on: [5]
- jira: [J1, J2, J5]

**RED**:
- 테스트:
  ```kotlin
  @Test fun `POST columns 로 상태 0개 컬럼을 만들 수 있다`()                           // R9·E1
  @Test fun `PUT states 로 컬럼의 상태 집합을 통째로 교체한다`()                        // R9
  @Test fun `다른 컬럼이 쓰는 상태를 PUT 하면 409 이고 어느 컬럼인지 알려준다`()          // E7·X1
  @Test fun `stateKeys 에 중복이 있으면 400`()                                        // E9
  @Test fun `DELETE 하면 그 컬럼의 상태가 미매핑으로 돌아가고 이슈는 그대로다`()          // R10·J5
  @Test fun `마지막 컬럼도 지울 수 있다`()                                            // E8
  @Test fun `권한 없는 actor 는 기존 컬럼 API 와 같은 코드로 거부된다`()                 // 권한 승계
  ```
- 실패 메시지 (예상): 404 (엔드포인트 없음)

**GREEN**:
- 3 엔드포인트. 권한 게이트는 `PATCH /{id}/columns/{columnId}` 의 `loadBoardWithCreate`(`:344`)를 **승계**
- `PUT states` 를 고른 이유는 스펙에 있다 — 집합 전체를 받아야 X1 위반을 **한 요청 안에서** 판정한다

**REFACTOR**:
- `BoardController` 줄수 확인. 늘어나면 요청 검증을 DTO `init` 블록으로 옮긴다

**검증**: `./gradlew :modules:agile-planning:test --tests '*BoardControllerIntegrationTest'`

---

### Task 7. 프론트 최소 수정 — `stateKeys` 정합 + `toStateKey` 전송

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/api/boards.ts`, `apps/web/src/api/boards.test.ts`, `apps/web/src/mocks/board-handlers.ts`, `apps/web/src/mocks/board-fixtures.ts`, `apps/web/src/mocks/board-handlers.test.ts`, `apps/web/src/mocks/workflow-draft-handlers.ts`, `apps/web/src/components/board/BoardColumn.tsx`, `apps/web/src/components/board/BoardColumn.test.tsx`, `apps/web/src/components/board/KanbanBoard.test.tsx`, `apps/web/src/components/board/ScrumSprintEmptyState.test.tsx`, `apps/web/src/components/board/board-drop.ts`, `apps/web/src/components/board/board-drop.test.ts`, `apps/web/src/hooks/use-move-card.test.tsx`, `apps/web/src/hooks/use-reorder-card.test.tsx`, `apps/web/src/hooks/use-change-card-field.test.tsx`, `apps/web/src/lib/backlog-completion.test.ts`, `apps/web/src/i18n/board-labels.ts`, `apps/web/src/routes/projects.$projectKey.board.tsx`, `apps/web/src/routes/__tests__/projects.board.test.tsx`]
- depends-on: [4, 5]
- jira: [J3]

**RED**:
- ★**learning 2026-05-30 「Zod 응답 스키마 강화가 산재한 인라인 mock 을 깬다」(PR #46)의 양식이다.**
  `stateKey: string` → `stateKeys: string[]` 이 정확히 그것이고, 예방 4항목을 그대로 적용한다 —
  ①`stateKey` 사용 **17파일 전수**를 위 `files` 에 넣었다 ②산재한 인라인 리터럴은 공용 fixture 로
  모을 수 있는지 본다 ③**`tsc --noEmit` 동반 필수**(vitest 는 타입체크를 안 해 `z.parse` 런타임에서만
  터진다) ④RED 에 **기존 mock 회귀 검증**을 포함
- 테스트:
  ```ts
  it('boardColumnSchema 가 stateKeys 배열을 파싱한다')                    // R11
  it('카드 이동 요청이 toStateKey 를 보낸다')                              // R12
  it('상태가 여럿인 컬럼에 떨구면 첫 상태를 보낸다')                        // R12 과도기 동작
  it('기존 보드 픽스처가 새 스키마로도 파싱된다')                           // ④ 회귀
  ```

**GREEN**:
- Zod `stateKey: z.string()` → `stateKeys: z.array(z.string())` (2곳)
- `board-drop.ts` 가 드롭 대상 컬럼의 `stateKeys[0]` 을 `toStateKey` 로 보낸다
- **드롭존 UI 는 만들지 않는다**(X3). 화면 구조 무변경

**REFACTOR**:
- 인라인 mock 이 3곳 이상 같은 모양이면 `makeBoardColumn(overrides)` fixture 로 모은다(learning ②)

**검증**:
- `apps/web/node_modules/.bin/vitest run` (worktree 에서 `pnpm` 래퍼는 죽는다 — 바이너리 직접 호출)
- **`node_modules/.bin/tsc -p apps/web/tsconfig.app.json --noEmit`** ← ③. 루트 `tsconfig.json` 은
  `files: []` 라 **0개 파일을 검사하고 종료 0** 이다(#431 실측 — 이전 세션이 한 번 속았다)
- 기존 E2E: `scrum-board` · `board-manage` · `quick-filter` (E11 · 표적 실행)

---

### Task 8. 무회귀 실측 — 선행 마이그레이션 테스트와 E2E

**메타**.
- agent: `qa-engineer`
- files: [`backend/modules/agile-planning/src/test/kotlin/com/bts/agileplanning/migration/SprintBoardIdBackfillMigrationTest.kt`, `backend/modules/agile-planning/src/test/kotlin/com/bts/agileplanning/migration/KanbanSprintMoveMigrationTest.kt`]
- depends-on: [1, 6, 7]
- jira: []

**RED**: 이 task 는 **판정만** 한다 — 새 테스트를 쓰지 않고 기존 것이 여전히 초록인지 실측한다.
초록이면 그대로, red 면 그 자리가 회귀다.

**측정 대상**:
1. `SprintBoardIdBackfillMigrationTest` · `KanbanSprintMoveMigrationTest` — ★**`target` 을 안 고정한
   테스트는 V508 까지 돈다.** #440 이 같은 함정(E10)을 실측했다. 초록이 아니면 `target` 고정이 필요
2. `agile-planning` 모듈 **전량** — 공유 컨테이너 1개·DB 1개(`AgilePlanningTestcontainersConfig.kt:73,78`)
   라 다른 클래스가 V508 의 영향을 받을 수 있다
3. E2E 표적 — `scrum-board` · `board-manage` · `quick-filter` · `backlog`
4. 판별식 전량 — `debt-ledger-mapping` 포함(장부를 안 건드렸으므로 계속 초록이어야 한다)

**GREEN**: 회귀가 나오면 원인을 지목해 해당 task 로 되돌린다. **이 task 에서 소스를 고치지 않는다.**

**검증**:
- `./gradlew :modules:agile-planning:test --rerun-tasks` (로그는 파일로 · `EXIT=$?` 로 판정)
- `node --experimental-strip-types --test 'scripts/**/*.test.ts' 'scripts/**/*.test.mjs'`
- `./gradlew ktlintCheck detekt --rerun-tasks`

## Plan 메타

- **task 수** 8 (각 TDD 사이클 1개) · **예상 wave** 6
- **wave 배치** — ① T1·T2(파일 무교집합, 병렬) → ② T3 → ③ T4 → ④ T5 → ⑤ T6 → ⑥ T7·T8
  Task 4·5·6 은 `BoardResponses.kt`·`BoardApplicationService.kt` 를 공유해 **파일 겹침 자동 직렬화**
  된다(§2). 억지 병렬은 서로의 산출물을 덮으므로 그대로 둔다
- **구현 규율** TDD red→green→refactor. `test:` 커밋이 `feat:` 보다 먼저 — CI 판별식 ①d 가 대조한다
- **추가 검증** ktlintCheck · detekt(둘 다 `--rerun-tasks`) · `tsc -p tsconfig.app.json --noEmit` ·
  vitest · Playwright 표적 4 spec · 판별식 전량 · `node scripts/build-doc-index.mjs --check`
- **Jira 매핑** — J1→T1·T2·T3·T4·T6 · J2→T4·T6 · J3→T2·T5·T7 · J4→T5 · J5→T6 · **J6 기각(X2 — 완료
  판정을 위치 기반으로 옮기지 않는다)**. 채택 5건 전부 최소 1개 task 에 물렸다 — **차집합 0**
- **뮤테이션 짝 2건 예고** — T2(집합 매칭을 `first()` 로 되돌리면 그 1건만 red) ·
  T5(보드 매핑 검증을 지우면 404 1건만 red). 둘 다 **결함이 사는 자리를 실제로 지난다**
- **장부** 이 PR 은 부채를 닫지 않는다. 157 은 N2 로 「더 늘리지 않는다」만 지키고 본문 갱신 대상이
  아니다. 177 은 등재 완료이고 범위 밖이다 — **장부 갱신 task 없음**


## 리뷰 결과 (← /bts-review-plan 채움)
