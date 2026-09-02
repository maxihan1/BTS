<!-- 스크럼 보드 조회가 스프린트 술어를 LIMIT 앞으로 미는 계약 — FR-BD-04 PR ④ 스펙 -->

# 스크럼 보드 조회 — 스프린트 술어를 LIMIT 앞으로 (FR-BD-04 PR ④)

> 티어: T3
> slug: board-sprint-scoped-lookup
> type: backend
> agent: backend-engineer
> BC: shared-kernel + issue-tracking + agile-planning (「한 PR = 한 BC」 명시적 예외)
> 생성: 2026-09-02
> 설계 정본: [ADR 2026-09-01 board-type-and-active-sprint](../adr/2026-09-01-board-type-and-active-sprint.md) §후속 3분할
> plan: [2026-09-02-board-sprint-scoped-lookup](../plans/2026-09-02-board-sprint-scoped-lookup.md)

## Context

`FR-BD-04` 는 D1~D7 이 전부 `[x]` 로 완주 표기돼 있지만, PR ③(#424)이 **자기 범위 밖으로 밀어낸
부채 하나**가 남았다. 원래 PR ①의 plan(`docs/plans/2026-09-01-board-scrum-schema.md:384`)이
「**PR ③ 전에 닫는다**」로 기한을 박았고, ③ 은 닫는 대신 PR ④ 로 분리하며 인계 지침 4건을 남겼다
(`docs/plans/2026-09-02-scrum-board-screen.md:300-325`).

### 결함 — 자르기와 거르기의 순서가 뒤집혀 있다

- `IssueRepository.listVisibleForBoard` 가 `created_at DESC` 로 `BOARD_CARD_FETCH_LIMIT + 1`(1,001)건을
  **먼저 자른다**.
- `BoardApplicationService.getBoard` 의 스프린트 필터가 **그 뒤**에 온다.

프로젝트 이슈가 1,000건을 넘고 활성 스프린트 이슈의 `created_at` 이 상위 1,001건 밖이면 그 이슈는
포트를 빠져나오지 못한다. 사용자에게는 **정상 시작한 스프린트가 빈 보드**로 보이고, 스크럼 보드는
활성 스프린트 이슈만 그리는 화면이라 다른 확인 경로가 없다.

`truncated` 플래그도 도움이 안 된다 — 사후 필터가 건수를 줄인 뒤라 화면이 「일부 누락」을 알리지 못하거나,
반대로 빈 보드에 「일부 누락」이 뜨는 두 방향으로 다 틀린다.

## 계약

### C1. `BoardCardFilter.issueKeys` — 서버 내부 전용 이슈 키 화이트리스트

`shared-kernel` 의 `BoardCardFilter` 에 6번째 필드를 더한다. `statusKeys` 와 **같은 취급**이다.

| 항목 | 규약 |
|---|---|
| 결합 | 다른 필드와 **AND**. 목록 내부는 OR(`key IN (...)`) |
| 빈 목록 | **필터 미적용**(다른 필드와 동일). 「해당 이슈 없음」이 아니다 |
| `isEmpty()` | `issueKeys` 도 함께 본다 |
| 쿼리 파라미터 | **없다.** `BoardFilterQueryParser` 가 파싱하지도 직렬화하지도 않는다 |
| 이슈 목록 API | `IssueFilterQueryParser` 가 세팅하지 않아 항상 미적용(inert) |

**포트 시그니처는 바뀌지 않는다.** `BoardIssueLookupPort` 의 3-인자 메서드가 이미 `BoardCardFilter`
전량을 통과시키므로 새 메서드가 필요 없다.

### C2. SQL 술어는 LIMIT 앞에 온다

`IssueRepository.buildFilterCondition` 의 `listOfNotNull` 에 `key IN (...)` 을 편입한다.
`issues.key` 는 `V001__issues_initial.sql:37` UNIQUE 라 IN 이 인덱스 경로다.

### C3. `getBoard` 는 스프린트를 **포트 호출 앞에서** 조회한다

```
현재: 포트 호출 → (SCRUM) 스프린트 조회 → Kotlin 필터
계약: (SCRUM) 스프린트 조회 → 포트 호출(issueKeys 실은 filter) → Kotlin 필터(유지)
```

- `filter.copy(issueKeys = …)` 다. 새 VO 를 만들면 호출자가 건 담당자·라벨·퀵필터가 사라진다.
- **칸반은 이 경로를 타지 않는다** — 스프린트 쿼리 0, filter 변화 0 (NFR-1 회귀 0).

### C4. 🛑 스프린트 이슈 키가 0건이면 포트를 호출하지 않는다

`issueKeys = emptyList()` 는 C1 규약상 **무필터**다. 그대로 넘기면 프로젝트 이슈 전량 조회 +
허위 `truncated` 가 되어 **빈 스크럼 보드에 「일부가 누락됐다」**가 뜬다.
「활성 스프린트 없음」과 「활성 스프린트가 비었음」은 화면상 둘 다 빈 보드이므로 같은 분기로 단락시킨다.
빈 페이지는 지역값으로 만들어 `placeCards` · `quickFilters` 경로를 그대로 태운다 — 응답 **형태** 불변.

### C5. 🛑 Kotlin 사후 필터는 유지한다

`BoardIssueLookupPort` 3-인자 KDoc(CONCERN-1)이 「default 구현은 filter 를 **무시하고** 2-인자로
위임한다」를 **계약으로 허용**한다. 따라서 filter 는 「이 조건에 맞는 것만 온다」는 보장이 아니라
**`truncated` 정확성을 위한 최적화 요청**이고, 카드 정확성은 소비측 책임으로 남는다.

사후 필터를 지우면 filter 를 드롭하는 구현(포트 default · override 를 빠뜨린 미래 adapter)에서
스크럼 보드가 **다른 스프린트·백로그 이슈까지 그린다** — 이 PR 이 고치는 결함보다 나쁜 회귀다.

## 범위 밖

| 항목 | 이유 |
|---|---|
| `BacklogApplicationService` | 🛑 백로그는 **차집합**(`visibleIssues.filter { it.key !in assignedKeys }`)이라 같은 술어를 넣으면 백로그 칸이 전멸한다. 부채 해소 범위를 `getBoard` 하나로 못박는다 |
| 포트에 새 메서드 추가 | C1 로 불필요. 인계 지침 3 의 전제가 실측에서 뒤집혔다 — plan §정정 참조 |
| `BOARD_CARD_FETCH_LIMIT` 가시성 변경 | 인계 지침 4 의 전제가 실측에서 뒤집혔다 — 1,001행 배치 헬퍼가 이미 있다 |
| 프론트 | `truncated` 는 이미 `BoardDetailResponse` 까지 결선돼 있다. FE 변경 0 |

## 엣지 케이스

| # | 상황 | 기대 |
|---|---|---|
| E1 | 스크럼 · 활성 스프린트 없음 | 포트 미호출 · 빈 보드 · `truncated=false` · `activeSprint=null` |
| E2 | 스크럼 · 활성 스프린트에 이슈 0건 | 포트 미호출 · 빈 보드 · `truncated=false` · **`activeSprint` 는 응답에 남는다**(빈 상태 문구가 E1/E2 를 구분하는 근거) |
| E3 | 스크럼 · 대상이 가장 오래됐고 비대상이 LIMIT+1건 | 대상 전량 반환 · `truncated=false` |
| E4 | 칸반 · 같은 보드에 활성 스프린트 존재 | 프로젝트 이슈 전량 배치 · `issueKeys` 빈 목록 |
| E5 | 스크럼 · 호출자가 담당자·라벨 필터를 함께 걸었다 | 두 술어가 AND 로 결합 |
| E6 | 비가시 보안 등급 이슈를 `issueKeys` 로 명시 지정 | 제외된다 — visibility 가 필터보다 앞선다 |
