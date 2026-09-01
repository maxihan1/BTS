<!-- 보드 종류 스키마·백엔드 구현 계획 — FR-BD-04 D1~D5 -->

# 보드 종류 + 활성 스프린트 보드 — 스키마·백엔드 (FR-BD-04 D1~D5)

> 티어: T3
> slug: board-scrum-schema
> type: migration
> agent: db-engineer
> BC: agile-planning
> 생성: 2026-09-01

## Brief

ADR `2026-09-01-board-type-and-active-sprint.md`(**채택**)의 3분할 중 **PR ①**.
스키마 2개 + 백엔드. 화면은 PR ②·③.

**FR.** `FR-BD-04` D1~D5. 신규 FR 없음 → **총수 144 불변**.
**스펙.** [docs/specs/2026-09-01-board-scrum-schema.md](../specs/2026-09-01-board-scrum-schema.md) — 9섹션 전문.

**이 PR 이 끝나도 사용자에게 보이는 변화는 없다.** 기존 보드는 전부 `KANBAN` 이고 화면은 아직
종류를 묻지 않는다. 마이그레이션이 든 PR 을 작게 유지하려는 의도다.

## Jira 대조

계약 §1-0 **재사용 승계.** ADR 의 J1~J13 중 이 PR 의 백엔드 계약을 정하는 4행만 가져왔다.
출처·조회일 그대로(2026-09-01 · Cloud). **추가 조회 0건** — 새로 건드리는 조작이 없다.
전문과 채택 판정·편차는 [스펙 `## Jira 대조`](../specs/2026-09-01-board-scrum-schema.md) 에 있다.

| # | 요지 | 출처 |
|---|---|---|
| **J1** | 보드 생성 시 Scrum / Kanban 을 고른다 | https://support.atlassian.com/jira-software-cloud/docs/create-a-board/ |
| **J5** | *"the board displays only the work items added to the sprint you started"* | https://support.atlassian.com/jira-software-cloud/docs/plan-a-sprint/ |
| **J6** | 카드 표시 조건에 *"is in an active sprint (for Scrum boards)"* | https://support.atlassian.com/jira-software-cloud/docs/use-active-sprints/ |
| **J11** | 활성 스프린트 기본 1개 (parallel sprints 는 옵션) | https://support.atlassian.com/jira-software-cloud/docs/plan-a-sprint/ |

## 착수 전 실측 (이미 수행)

| 확인한 것 | 결과 |
|---|---|
| agile-planning 최신 마이그레이션 번호 | **V504** → 신규는 **V505 · V506**. 머지 직전 재확인 필요(DATA.md §4.1) |
| 프론트 Zod 스키마가 `.strict()` 인가 | **아니다.** `api/boards.ts` 는 전부 맨 `z.object(...)` — Zod 기본이 미지 키를 **버리므로** 응답 필드 추가가 프론트를 깨지 않는다 |
| `BoardCardPlacement.placeCards` 시그니처 | `(columns, issues)` 순수 함수. **바꾸지 않는다** — 필터링을 서비스로 올린다(NFR-2) |
| 보드 경로가 스프린트를 아는가 | **모른다.** 4파일 `sprint` 언급 0건 — 이 PR 이 그 연결을 만든다 |
| `SprintRepository` 에 활성 조회가 있는가 | **없다.** `list` 의 status 필터뿐(`:139`) — `findActiveByBoard` 를 신설한다 |

## Plan

### Task 1. V505 — `boards.board_type`

**메타**.
- agent: `db-engineer`
- files: [`backend/modules/agile-planning/src/main/resources/db/migration/agile-planning/V505__board_type.sql`]
- depends-on: []
- jira: [J1]

**RED**: 마이그레이션 적용 후 `boards.board_type` 이 존재하고 기본값이 `KANBAN` 인지 단언하는
Testcontainers 테스트. 컬럼이 없어 실패한다.

**GREEN**:
```sql
ALTER TABLE boards ADD COLUMN board_type VARCHAR(16) NOT NULL DEFAULT 'KANBAN'
    CHECK (board_type IN ('SCRUM', 'KANBAN'));
```
`COMMENT ON COLUMN` 으로 「기존 보드 전량 보존이 `DEFAULT 'KANBAN'` 의 목적」을 남긴다.

**REFACTOR**: 되돌리기 주석 — `DROP COLUMN` 으로 원복 가능.

**검증**: `./gradlew :modules:agile-planning:test --tests '*Migration*'`

---

### Task 2. V506 — `sprints.board_id` + 백필

**메타**.
- agent: `db-engineer`
- files: [`backend/modules/agile-planning/src/main/resources/db/migration/agile-planning/V506__sprint_board_id.sql`]
- depends-on: [1]

**RED**: 마이그레이션 전 스프린트를 가진 프로젝트를 심어 두고, 적용 후
① 그 스프린트가 **스크럼 보드**에 붙고 ② **기존 칸반 보드의 이름·컬럼이 무변경**인지 단언한다.

**GREEN**: 컬럼 추가 → 스프린트 보유 프로젝트마다 스크럼 보드 신설 → 연결 → `NOT NULL` 승격
→ 활성 부분 인덱스.

★ **기존 보드를 승격하지 않는다.** 승격하면 그 보드 카드가 활성 스프린트 것만 남아
**사용자가 보던 것이 사라진다**(ADR 기각 대안 A-3).

★ **다중 ACTIVE 기존 행을 깨지 않는다.** PR #182 가 허용했으므로 실재할 수 있다.
가드는 `start` 시점에만 건다 — 이 사실을 SQL 주석에 적는다(스펙 E-2).

**REFACTOR**: `ON DELETE CASCADE` 를 둔 이유(하드 삭제 경로가 생기면 고아 방지)를 주석에.

**검증**: 위 RED + `verify-master-plan.sh`

---

### Task 3. 도메인 — `BoardType` + `Sprint.boardId`

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/agile-planning/src/main/kotlin/com/bts/agileplanning/domain/BoardType.kt`, `.../domain/Board.kt`, `.../domain/Sprint.kt`, `backend/modules/agile-planning/src/test/kotlin/com/bts/agileplanning/domain/BoardTypeTest.kt`]
- depends-on: []
- jira: [J1]

**RED**: 허용값 밖 문자열로 `BoardType.from` 을 부르면 **named exception**(`BoardTypeInvalidException`)이
난다. 맨 `IllegalArgumentException` 이 아니다 — `Board.kt:18` 의 `BoardNameInvalidException` 이
세운 관례를 따른다(그 KDoc 이 이유를 적는다).

**GREEN**: enum + `Board.boardType`(기본 `KANBAN`) + `Sprint.boardId`.

**검증**: `./gradlew :modules:agile-planning:test --tests '*BoardType*'`

---

### Task 4. 리포지토리 — 컬럼 배선 + `findActiveByBoard`

**메타**.
- agent: `backend-engineer`
- files: [`.../repository/BoardRepository.kt`, `.../repository/SprintRepository.kt`, `.../repository/BoardRepositoryTest.kt`, `.../repository/SprintRepositoryTest.kt`]
- depends-on: [1, 2, 3]

**RED**: `findActiveByBoard(boardId)` 가 없다. ACTIVE 1건 + COMPLETED 2건을 심고 ACTIVE 만 나오는지,
**soft-deleted 는 제외**되는지 단언한다.

**GREEN**: jOOQ 재생성 후 `board_type`·`board_id` 매핑 + `findActiveByBoard`.
★ 부분 인덱스(`WHERE deleted_at IS NULL AND status='ACTIVE'`)를 타도록 조건 순서를 맞춘다.

**검증**: `./gradlew :modules:agile-planning:test --tests '*RepositoryTest*'`

---

### Task 5. 생성 API — `boardType` 선택 인자

**메타**.
- agent: `backend-engineer`
- files: [`.../application/BoardApplicationService.kt`, `.../web/BoardController.kt`, `.../web/dto/BoardResponses.kt`, `.../web/BoardExceptionHandler.kt`, `.../application/BoardApplicationServiceTest.kt`]
- depends-on: [3, 4]
- jira: [J1]

★ **요청 DTO 는 `BoardResponses.kt` 에 있다.** `BoardRequests.kt` 는 **존재하지 않는다** —
PR #416 리뷰가 이 착각을 잡았다.

**RED**:
- `boardType: "SCRUM"` 으로 만들면 응답이 `SCRUM` 이다.
- **`boardType` 을 안 보내면 `KANBAN`** 이다 — 기존 호출자 무변경 보장.
- 허용값 밖이면 **400** `AGILE_BOARD_TYPE_INVALID`.

**GREEN**: DTO 선택 필드 + 서비스 인자 + 핸들러 매핑(named exception 만).

**검증**: `./gradlew :modules:agile-planning:test --tests '*BoardApplicationServiceTest*'`

---

### Task 6. 조회 — 스크럼 분기 + 응답 확장

**메타**.
- agent: `backend-engineer`
- files: [`.../application/BoardApplicationService.kt`, `.../web/dto/BoardResponses.kt`, `.../application/BoardApplicationServiceTest.kt`]
- depends-on: [4, 5]
- jira: [J5, J6]

**RED**:
- 스크럼 보드 + 활성 스프린트(이슈 3건) + 스프린트 밖 이슈 5건 → 카드 총합 **3**.
- 스크럼 보드 + 활성 스프린트 없음 → 컬럼 전부 비고 `activeSprint` 가 **null**.
- **칸반 보드는 지금과 완전히 같다** — 회귀 0 을 단언으로 고정한다.

**GREEN**: `board.boardType == SCRUM` 일 때만 활성 스프린트를 조회해 이슈를 거른 뒤
기존 `placeCards` 에 넘긴다.

★ **`BoardCardPlacement` 를 건드리지 않는다**(NFR-2) — 순수 함수로 남기고 기존 단위 테스트를 산 채로 둔다.
★ **칸반은 쿼리가 늘지 않는다**(NFR-1) — 분기 전에 스프린트를 조회하지 않는다.

**검증**: 위 RED + **비-공허 확인** — GREEN 선커밋 뒤 분기를 끊어 red 1회를 눈으로 본다.

---

### Task 7. `start` 가드 — 보드당 활성 1개

**메타**.
- agent: `backend-engineer`
- files: [`.../application/SprintApplicationService.kt`, `.../application/SprintExceptions.kt`, `.../web/SprintExceptionHandler.kt`, `.../application/SprintApplicationServiceTest.kt`]
- depends-on: [4]
- jira: [J11]

**RED**: 같은 보드에서 두 번째 `start` 가 **409** `AGILE_SPRINT_ALREADY_ACTIVE`.
지금은 가드가 없어 **200 이 난다**(`SprintApplicationService.start` 는 상태 전환만 검사).

**GREEN**: `findActiveByBoard` 로 판정 후 named exception → 409.

> 🛑 **선행 결정을 뒤집는다.** `agile-planning.md §3.2` Deviation(PR #182) ⑤ 「동시 ACTIVE 다중 허용」.
> ADR 이 무효화했고 여기서 실행한다. **기존 다중 활성 행은 깨지 않는다** — 가드는 `start` 시점만.

**검증**: `./gradlew :modules:agile-planning:test --tests '*SprintApplicationServiceTest*'`

---

### Task 8. 백필 검증 — Testcontainers

**메타**.
- agent: `qa-engineer`
- files: [`backend/modules/agile-planning/src/test/kotlin/com/bts/agileplanning/migration/BoardScrumBackfillTest.kt`]
- depends-on: [2]

**RED**: 마이그레이션 전 상태(스프린트 N건 + 칸반 보드 1개)를 심고 적용 후를 단언한다.
① 스프린트 전량이 새 스크럼 보드 소속 ② 기존 칸반 보드 이름·컬럼 무변경 ③ `board_id` NOT NULL.

★ **깨끗한 컨테이너에서 잰다.** 공유 개발 DB 의 선재 행이 가짜 그린을 만든다
(메모리 `shared-dev-db-preexisting-rows-fake-green`).

**GREEN**: Task 2 가 이미 낸다. 이 task 는 **검증만** 추가한다.

**검증**: `./gradlew :modules:agile-planning:test --tests '*BackfillTest*'`

---

### Task 9. 문서 동기화 — D1~D5 마킹

**메타**.
- agent: `backend-engineer`
- files: [`docs/plan/product/agile-planning.md`, `docs/INDEX.md`, `docs/INDEX-fr.md`, `docs/INDEX-recent.md`]
- depends-on: [1, 2, 3, 4, 5, 6, 7, 8]

**GREEN**: §2.4 의 **D1~D5 를 `[x]`** 로. D6·D7 은 `[ ]` 유지(PR ②·③).
FR 총수 **144 불변** — 신규 FR 이 없으므로 fr-index·README·CLAUDE·CHANGELOG 는 **건드리지 않는다**.
`node scripts/build-doc-index.mjs` 재실행.

★ §2.4 의 완료 게이트 줄에 `FR-<접두>` 토큰과 `N개` 를 함께 쓰지 않는다 —
`verify-master-plan.sh` 헤더 카운트 규칙이 산문 줄도 스캔해 선언으로 읽는다(2026-09-01 오탐 1회).

**검증**: `bash scripts/verify-master-plan.sh` EXIT=0 · FR 144 불변

## Plan 메타

- **task 수**: 9 · **예상 wave**: 5 (w1 = T1·T3 / w2 = T2 / w3 = T4·T8 / w4 = T5·T7 / w5 = T6·T9)
- **구현 규율**: TDD red-first (T3) + **마이그레이션 검증**(DATA.md 정본).
  `test:` 커밋 → `feat:` 커밋 순서가 로그에서 대조된다.
- **추가 검증**: ktlint · detekt · Testcontainers · `verify-master-plan.sh` · 판별식
- **Jira 매핑**: J1→T1·T3·T5 · J5→T6 · J6→T6 · J11→T7. **차집합 0.**
- **프론트 변경 0파일** — 응답 필드가 늘지만 Zod 가 미지 키를 버려 무해하다(실측).
