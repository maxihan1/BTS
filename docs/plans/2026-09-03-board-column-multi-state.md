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

## 도메인 정리 (← /bts-spec §1 채움)

## 스펙 (← /bts-spec §2 채움)

## Sanity Check (← /bts-spec §3 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
