# 보드 종류 불변식 결선 — 쓰기 가드 · start TOCTOU · 응답 계약 (FR-BD-04 PR ⑤)

> 티어: T2
> slug: board-type-invariants
> type: backend
> agent: backend-engineer
> 생성: 2026-09-02
> 설계 정본: [ADR 2026-09-01 board-type-and-active-sprint](../adr/2026-09-01-board-type-and-active-sprint.md) §후속 3분할

## Brief

**이 PR 이 하는 것.** ADR 후속 3분할의 **PR ⑤** — `board_type` 이 스키마에만 있고 **쓰기 경로와
응답 계약에는 결선되지 않은** 구멍 3건을 닫는다.

**FR.** `FR-BD-04` D4·D5 잔여. 신규 FR 없음 → 총수 **144 불변**. **마이그레이션 0 · 신규 의존성 0.**
**BC.** `agile-planning` 단독(+ 그 응답을 읽는 `apps/web` 스키마 동기화).

## Jira 대조

계약 §1-0 **재사용 승계.** 이 PR 은 새 화면 조작을 만들지 않는다 — 근거는
[ADR 2026-09-01](../adr/2026-09-01-board-type-and-active-sprint.md) 이 실물 조회로 확정한 표에서
**출처·조회일을 그대로 승계**한다(조회일 2026-09-01 · 전 행 Jira Cloud company-managed).
**추가 조회 0건.**

| # | 원문 인용 | 출처 | 이 PR 의 어디 |
|---|---|---|---|
| **J6** | *"**Active sprints are only available on Scrum boards.**"* | [use-active-sprints](https://support.atlassian.com/jira-software-cloud/docs/use-active-sprints/) | ⑤-1 — 스프린트는 스크럼 보드에만 붙는다 |
| **J11** | *"If you want to have more than one active sprint at a time, you'll need to **enable parallel sprints**"* → 기본 활성 **1개** | [plan-a-sprint](https://support.atlassian.com/jira-software-cloud/docs/plan-a-sprint/) | ⑤-2 — 그 1개를 동시 요청에서도 지킨다 |

**⑤-3(응답 계약)은 Jira 대응 없음 — ADS 준용 대상도 아니다.** 화면이 아니라 **서버 응답 DTO 필드**
이고, Jira 는 자기 REST 응답 형태를 사용자 문서로 약속하지 않는다. 준용할 패턴이 없으므로
근거는 **BTS 자체 일관성**이다 — 같은 개념(보드 종류·소속 보드)을 노출하는 형제 DTO 가 이미 있고
그중 일부만 빠져 있었다.

**조회했으나 원문을 확보하지 못한 것.** 칸반 보드에 스프린트를 **만들려고 시도**했을 때 Jira 가
무엇을 돌려주는지(거부인지, 애초에 UI 가 없어 도달 불가인지). `use-active-sprints` 는 「활성
스프린트는 스크럼 전용」까지만 적고 거부 동작을 다루지 않았다. → BTS 는 **404** 로 두되
그 선택이 원문 근거가 아니라 형제 경로(`resolveBoardScope` 편차 E8)와의 일관성임을 적는다.

## 무엇을 고치나

### ⑤-1. 칸반 보드에 스프린트가 매달린다

`SprintApplicationService.resolveTargetBoard` 가 `projectKey` 일치 + `deleted_at IS NULL` 만 봤다.
그래서 `POST /api/v1/sprints {boardId: <칸반>}` 이 201 이고 `start` 도 200 인데, `getBoard` 는
`boardType == SCRUM` 일 때만 활성 스프린트를 조회하므로 **그 스프린트는 어느 화면에도 영원히
안 나타난다** — 사용자에게는 「시작했는데 아무 일도 안 일어남」이다.

오늘 이것을 가리는 것은 백로그 스위처의 스크럼 필터 **하나뿐**이고
(`projects.$projectKey.backlog.tsx:153-161` KDoc 이 이 실패 양식을 그대로 적는다),
즉 **프론트 필터가 유일한 방어선**이었다.

**🛑 쓰기 경로만 막는다 (Maxi 확정 2026-09-02).** 읽기 경로(`BacklogApplicationService.resolveBoardScope`)는
그대로 둔다 — 기존 시드 전량이 칸반 보드 소속 스프린트를 쓰고
(`apps/web/src/mocks/board-handlers.test.ts` 의 회귀 가드가 「보드 E2E 전량이 그 시드를 쓴다」를 명시),
읽기까지 막으면 그 데이터가 통째로 404 가 된다. `V506` 이 선재 다중 ACTIVE 행을 보존한 것과 같은
판단이다 — **새로 만드는 것만 막고 있는 것은 둔다.**

그 비대칭을 `resolveTargetBoard` KDoc 의 「읽기 경로와 같은 규약」 절에 명시했다.
**안 적으면 그 문단 자체가 거짓이 된다.**

상태 코드는 **404** 를 유지한다 — 403 이면 「그 UUID 는 존재한다」가 샌다
(memory `permission-assert-before-existence-makes-403-lie`).

### ⑤-2. `start` 활성 1개 가드에 TOCTOU

`findActiveByBoard` → `updateStatus` 사이에 잠금이 없었다. DB 도 못 막는다 —
`V506__sprint_board_id.sql` 의 `idx_sprints_board_active` 는 선재 다중 ACTIVE 행 보존을 위해
**일부러 UNIQUE 가 아니다.**

**처방.** 형제 락 `BoardRepository.acquireProjectScrumBoardLock` 을 그대로 따른다 —
`acquireSprintStartLock(boardId)` = `pg_advisory_xact_lock(hashtextextended('sprint-start:<id>', 0))`,
전파 **MANDATORY**. `REQUIRED` 로 두면 트랜잭션 없이 불렸을 때 자기 트랜잭션을 열고 즉시 커밋해
**락이 그 자리에서 풀리는데 예외 없이 조용히 성공한다.**

`start` 는 락 → **재조회** → 쓰기 순서다. 락 밖에서 읽은 값으로 판단하면 락이 무력화된다
(memory `advisory-lock-bigint-toctou`). 전환 무효 판정은 락 **앞**에 둔다 — 잘못된 요청이 남의
시작을 막아 세우지 않는다.

### ⑤-3. 응답 계약 구멍 2건

- `BoardMetaResponse` — 종류를 노출하는 DTO 5개 중 **PATCH 응답만** 빠져 있었다. 종류가 불변
  (편차 X3)이라 오늘 버그는 아니지만, 한 개념을 5곳 중 4곳만 싣는 계약은 소비자가 예외를
  학습하게 만들고 그 예외가 다음 결함이 된다.
- `SprintResponse` · `SprintMetaResponse` — `Sprint.boardId` 가 도메인에 있는데 어느 응답에도 없었다.
  그래서 E2E 가 **요청 바디**를 유일한 관측점으로 삼을 수밖에 없었다 — 응답을 못 보므로 서버가
  boardId 를 흘려도 화면이 멀쩡했다. 이제 응답 축이 「보냈다」가 아니라 **「붙었다」**를 잰다.

프론트 스키마는 **optional 로 두지 않는다.** optional 이면 백엔드가 필드를 흘려도 조용히 통과해
계약이 다시 갈린다.

## TDD (T2 — `test:` 가 `feat:` 앞)

| # | RED 근거 |
|---|---|
| ⑤-1 | 새 케이스만 빨갛다(55 tests, 1 failed) — `activeBoard` fixture 기본값을 SCRUM 으로 돌려 나머지를 지켰다 |
| ⑤-2 | 「동시 start 2건 중 성공이 **2 건**이다」 — 레이스가 정확히 재현됐다 |
| ⑤-3 | compileTestKotlin `Unresolved reference 'boardType'` · vitest 4 failed |

### 🛑 fixture 기본값을 도메인 기본값과 다르게 뒀다

`SprintApplicationServiceTest.activeBoard` 의 `boardType` 기본값은 **SCRUM** 이다 —
도메인 기본값(`KANBAN`)과 일부러 다르다. 스프린트가 붙을 수 있는 보드는 스크럼뿐이므로(편차 X4),
도메인 기본값을 그대로 쓰면 이 파일의 create·start 테스트 전량이 **도달 불가 조합**을 고정한다
(memory `unreachable-state-fixture-is-fake-green`).

### 🛑 락 순서를 직접 재는 테스트를 넣었다

락을 조회 뒤로 옮기면 락이 무력화되는데, 그래도 다른 단위 테스트는 전부 초록이고 통합 테스트도
두 스레드가 우연히 안 겹치면 통과할 수 있다. `verifyOrder` 로 순서 자체를 고정한다.

## ⚠️ 이 PR 이 발견한 것 — 루트 tsconfig 는 아무것도 검사하지 않는다

`apps/web/tsconfig.json` 은 `"files": []` + `references` 뿐이라 **0개 파일을 검사하고 종료 0** 이다.
실 검사는 `tsc -p tsconfig.app.json`(package.json 의 `typecheck`)이다.
루트 설정으로 타입체크를 돌리면 **공허하게 통과**한다 — 이 PR 도 한 번 그것에 속았다.

그 사실이 드러난 계기가 ⑤-3 이다. 스키마에 필수 필드를 더하자 `tsc -p tsconfig.app.json` 이
`SprintMeta`·`BoardMeta` 리터럴 **22곳**을 한 번에 청구했다(#422 plan 의 「미룬 청구서」).

## 검증

```bash
cd backend && ./gradlew :modules:agile-planning:test ktlintCheck detekt
cd apps/web && node_modules/.bin/tsc -p tsconfig.app.json --noEmit   # ★루트 tsconfig 아님
pnpm --filter web test
pnpm --filter web test:e2e
```

**실측.** agile-planning 모듈 전량 · tsc 0 · vitest 614 files / 10277 tests ·
E2E scrum-board·backlog·board-manage·board-kanban·quick-filter **49 passed**.
