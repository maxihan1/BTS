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
- files: [`backend/modules/agile-planning/src/main/resources/db/migration/agile-planning/V505__board_type.sql`, `backend/modules/agile-planning/src/main/resources/db/codegen/init_codegen.sql`]
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

🔴 **`init_codegen.sql` 을 같은 커밋에서 고친다.** jOOQ 코드 생성은 **Flyway 마이그레이션을 읽지 않는다** —
`build.gradle.kts:146-149` 가 `TC_INITSCRIPT=file:src/main/resources/db/codegen/init_codegen.sql` 로
**손관리 미러**를 적용한다. 그 파일 헤더가 *"미러 누락 시 jOOQ 상수 미생성"* 이라 스스로 경고한다.
빠뜨리면 `BOARDS.BOARD_TYPE` 상수가 안 생겨 Task 4 가 **컴파일되지 않는다**(리뷰 E2).

**검증**: `./gradlew :modules:agile-planning:test --tests '*Migration*'`

---

### Task 2. V506 — `sprints.board_id` + 백필

**메타**.
- agent: `db-engineer`
- files: [`backend/modules/agile-planning/src/main/resources/db/migration/agile-planning/V506__sprint_board_id.sql`, `backend/modules/agile-planning/src/main/resources/db/codegen/init_codegen.sql`]
- depends-on: [1]

**RED**: 마이그레이션 전 스프린트를 가진 프로젝트를 심어 두고, 적용 후
① 그 스프린트가 **스크럼 보드**에 붙고 ② **기존 칸반 보드의 이름·컬럼이 무변경**인지 단언한다.

**GREEN**: 컬럼 추가 → 스프린트 보유 프로젝트마다 스크럼 보드 신설 → **그 프로젝트 기존 보드의
`board_columns` 를 복제** → 연결 → `NOT NULL` 승격 → 활성 부분 인덱스.

🔴 **「첫 조회에서 시드된다」는 거짓이었다.** `BoardApplicationService` KDoc(`:58-61`)이 조회 경로를
*"boards/board_columns 로드 → listVisibleIssuesByProject → placeCards"* 로 못박는다 — **시드가 없다.**
복제할 보드가 없는 프로젝트(스프린트는 있는데 보드가 0개)는 **영원히 빈 보드**가 된다.
→ Task 6 에 **자가 치유**를 넣는다(리뷰 E1).

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

★ **컬럼 0개 보드 자가 치유(리뷰 E1).** 조회 시 컬럼이 비어 있으면 `createBoard` 와 같은 경로로
워크플로우 카탈로그에서 시드해 영속한다. 백필이 복제할 보드를 못 찾은 프로젝트를 구제한다.
상태 목록도 비면 기존 「스킴 미할당」 처리를 그대로 따른다 — **새 오류 경로를 만들지 않는다.**

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


## 리뷰 결과

렌즈 2종(`type=migration` → eng + ceo). **지적 4건 · 2건 plan 반영 · 1건 게이트 1 · 1건 수용.**
모든 지적이 pre-emit 검증 게이트를 통과했다 — 근거 줄을 인용하지 못한 지적은 올리지 않았다.

### E1 [P1] (confidence 10/10) · `BoardApplicationService.kt:58-61` — ✅ 반영

> *"## 보드 조회 — boards/board_columns 로드 → [BoardIssueLookupPort.listVisibleIssuesByProject] 로
> 카드 조회 → [BoardCardPlacement.placeCards] 로 배치."*

조회 경로에 **시드가 없다.** plan Task 2 가 「보드가 없으면 컬럼 없이 만들고 **첫 조회에서 시드**」라
적었는데 그런 경로는 존재하지 않는다. 백필이 만든 컬럼 0개 보드는 **영원히 빈 보드**로 남는다.
→ Task 2 는 기존 보드의 컬럼을 **복제**하고, Task 6 에 **자가 치유**를 넣었다.

### E2 [P1] (confidence 10/10) · `build.gradle.kts:146-149` + `init_codegen.sql:1-4` — ✅ 반영

```
url = "jdbc:tc:postgresql:16-alpine:///bts_codegen" +
    "?TC_INITSCRIPT=file:src/main/resources/db/codegen/init_codegen.sql"
```
> *"-- jOOQ 코드 생성용 초기화 SQL (agile-planning BC) — V500~V504 테이블 구조 미러"*
> *"... 과 동일하게 유지한다(미러 누락 시 jOOQ 상수 미생성)."*

**jOOQ 는 Flyway 마이그레이션을 읽지 않는다.** V505·V506 만 추가하면 `BOARDS.BOARD_TYPE` 상수가
생성되지 않아 Task 4 가 **컴파일되지 않는다**. plan 의 `files` 에 `init_codegen.sql` 이 빠져 있었다.
→ Task 1·2 의 `files` 에 추가했다.

### E3 [P2] (confidence 9/10) · 미러와 마이그레이션이 서로를 검사하지 않는다 — 🛑 게이트 1

`init_codegen.sql` 이 마이그레이션과 같은지 확인하는 장치가 **자연어 주석뿐**이다.
`docs/rules/behavior-rules.md §3` 이 *"자연어 지시는 강제가 아니다"* 를 이미 판정했고,
이것이 이 저장소의 **지배 결함 양식**(`two-lists-never-check-each-other`)이다.
E2 가 그 양식에 실제로 걸린 첫 사례다 — 내가 놓칠 뻔했다.

→ **차집합 판별식을 이 PR 에 넣을지 결정이 필요하다.** 아래 게이트 1.

### C1 [P2] (CEO 렌즈) — ⚠️ 수용

이 PR 은 **사용자에게 보이는 변화가 0**이다. 의도된 분할이지만 PR ②③ 이 오지 않으면 스키마만 남는다.
FR-BD-04 의 D6·D7 이 정본에서 추적하므로(진척 열 `☐`) **조용히 사라지지 않는다.** 수용.

### 렌즈별 판정

| 렌즈 | 결과 | BLOCKER |
|---|---|---|
| plan-eng-review | ⚠️ 주의 — E1·E2 반영 · E3 미결(게이트 1) | **0** |
| plan-ceo-review | ✅ 통과 — C1 수용 | **0** |

**BLOCKER 0건.** E3 는 선택이며 어느 쪽이든 이 PR 이 진행된다.

## GSTACK REVIEW REPORT

| Review | Trigger | Why | Runs | Status | Findings |
|--------|---------|-----|------|--------|----------|
| CEO Review | `/plan-ceo-review` | Scope & strategy | 1 | ✅ CLEAR | 1 issue (C1 수용) |
| Codex Review | `/codex review` | Independent 2nd opinion | 0 | skipped | 중첩 codex 토큰 비용으로 미실행 — 생략 사실을 남긴다 |
| Eng Review | `/plan-eng-review` | Architecture & tests (required) | 1 | ⚠️ CONCERNS | 3 issues, 0 critical gaps (E3 미결) |
| Design Review | `/plan-design-review` | UI/UX gaps | 0 | not run | 프론트 0파일 — 라우팅 대상 아님 |
| DX Review | `/plan-devex-review` | Developer experience gaps | 0 | not run | 라우팅 대상 아님 |

- **VERDICT:** CEO CLEARED · ENG CONCERNS (E3 결정 대기) — 게이트 1 에서 E3 을 정하면 구현 착수 가능. BLOCKER 0.

**UNRESOLVED DECISIONS:**
- E3 — `init_codegen.sql` ↔ 마이그레이션 차집합 판별식을 이 PR 에 넣을 것인가

---

## 리뷰 결과 (PR 단위 · 체인 [6] `/bts-codereview`)

렌즈 3종 — `code-reviewer` · data-migration+testing 스페셜리스트 · 독립 리뷰(sonnet).
**두 렌즈가 독립적으로 같은 결론에 도달한 지점이 셋**(TDD 커밋 순서 · 스크럼 보드 유일성 ·
`ensureScrumBoard` 커버리지)이라 신뢰도가 높다.

| 렌즈 | 결과 | BLOCKER |
|---|---|---|
| code-reviewer | ⚠️ CONCERNS → 수정 완료 | 1 (해소) |
| data-migration + testing | ⚠️ CRITICAL 6 → 4건 수정 · 2건 등재 | 0 |
| 독립 리뷰 (sonnet) | ⚠️ CONCERNS → 수정 완료 | 1 (해소) |

### 수정한 것

- **BLOCKER.** `BoardRepository.findScrumBoardIdByProject` 에 `@Transactional` 누락
  (절대 규칙 §1.2-9 · DATA.md §1-4). `4353fc0ed`.
  ★ 반쪽 수정이었다 — 앞선 커밋이 리포지토리 메서드를 둘 추가했는데 T7 에서 하나만 고쳤다.
  리포지토리 3파일 public 메서드 전수 훑어 남은 누락 0 확인.
- **C1.** 모든 실제 스프린트 생성이 타는 `ensureScrumBoard` 경로가 테스트 0건.
  뮤테이션 2종(`?: error("x")` · 재사용 조기반환 삭제)이 전부 초록이었다. `ead2e24ef` + `06e3b24d5`.
- **C2.** `ensureScrumBoard` read-then-insert 경쟁 → advisory lock. `06e3b24d5`.
  UNIQUE 인덱스를 쓰지 않은 이유는 ADR D6(다수 보드 지원)과 충돌하기 때문 — 막아야 할 것은
  사용자의 두 번째 보드가 아니라 암묵 생성의 경쟁이다.
- **커버리지.** 신규 응답 필드(`boardType`·`activeSprint`) 0건 · 백필 픽스처가 V506 ③ 의
  두 판정(가장 오래된 · 활성)을 구별 못 함. 뮤테이션으로 각각 red 확인. `8e0c4150e`.

### 🛑 Deviation — TDD 커밋 순서 위반 4건 (Maxi 승인 필요)

이 계획 §Plan 메타가 *"`test:` 커밋 → `feat:` 커밋 순서가 로그에서 대조된다"* 를 T3 요건으로
선언했으나 아래 4개 커밋이 어겼다.

| 커밋 | 문제 |
|---|---|
| `60ef92af6` `feat: V505` | 선행 `test:` 없음 — 스키마 단언은 `ff1c3430b` 로 사후 유입 |
| `b4230c996` `feat: V506` | 선행 `test:` 없음 — 백필 검증은 11 커밋 뒤 `45e119096` |
| `85c8d47c8` `feat: 도메인·리포지토리` | 신규 테스트를 같은 커밋에 묶음 |
| `ff1c3430b` `fix: … 스키마 테스트 갱신` | 스키마 테스트를 `fix:` 로 사후 갱신 |

선행 `183f8010c test:` 는 판별식 1파일이라 V505/V506 **동작**의 red 가 아니다.
정상 red-first 3쌍 — `cb57e892f`→`69b52940f` · `2c6680a6b`→`e36e1a611` · `2b3197d58`→`84bf87747`.

**되돌리지 않는 이유.** 푸시된 커밋이라 force-push 가 필요하고, 되돌린다는 것은 **실제로 일어나지
않은 red-first 이력을 지어내는 것**이라 정직한 기록보다 나쁘다.

**재발 방지.** 이 계획의 Task 8(백필 검증) `depends-on: [2]` 배선 자체가 「마이그레이션 먼저,
검증 나중」을 구조적으로 강제했다. 다음 마이그레이션 작업에서는 **스키마 단언 task 를
마이그레이션 task 의 선행으로** 뒤집는다.

### 등재만 하고 고치지 않은 것

- **E-6** 사용자가 만든 두 번째 스크럼 보드에 스프린트가 안 붙는다 → 스펙 엣지 케이스에 등재.
  PR ③ 이 `boardId` 를 명시로 넘기면 사라진다.
- **스크럼 보드 × `truncated`** — 프로젝트 이슈 1,000건 상한 뒤 스프린트로 거르므로 오래된
  스프린트 이슈가 빠질 수 있다. `BacklogApplicationService:120` 이 이미 같은 패턴이라 이 PR 이
  만든 결함 클래스가 아니고, 화면이 0파일이라 지금 사용자에게 닿지 않는다. **PR ③ 전에 닫는다.**
- **V506 재적용 비멱등성** — 되돌린 뒤 재적용하면 ② 가 보드를 중복 생성한다. 운영자가 의도적으로
  Flyway 이력을 지워야 도달하는 경로라 등재만 한다.
- **보드 이름 문자열 이중화** (V506 SQL ↔ `ensureScrumBoard`). 조회가 `board_type` 으로 찾아
  중복 보드는 안 생기고 표시 이름만 갈린다.

### 절차 위반 (자진 보고)

체크리스트 Pass 0-2 가 금지한 모듈 전체 `ktlintFormat` 을 실행했다. 피해 0 확인 —
건드린 7파일이 전부 이 PR 이 이미 수정한 파일이라 옆 작업 오염은 없다.

### CI

**실행 0건.** self-hosted 러너 `bts-local` 미가동 · GitHub Actions 결제 차단으로 클라우드 러너
배정 불가. 저장소 마지막 run 이 2026-08-20 이다. 이 PR 의 검증 근거는 **전량 로컬 실행**이다.
