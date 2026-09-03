# 스크럼 보드 조회 — 스프린트 술어를 LIMIT 앞으로 (FR-BD-04 PR ④)

> 티어: T3
> slug: board-sprint-scoped-lookup
> type: backend
> agent: backend-engineer
> 생성: 2026-09-02
> spec: [2026-09-02-board-sprint-scoped-lookup](../specs/2026-09-02-board-sprint-scoped-lookup.md)

## Brief

**이 PR 이 하는 것.** ADR 후속 3분할의 **PR ④** — 스크럼 보드가 활성 스프린트 이슈를 조용히 잃는
결함을 없앤다. 술어를 `BOARD_CARD_FETCH_LIMIT` 자르기 **앞**으로 민다.

**왜 지금인가.** PR ①의 plan(`docs/plans/2026-09-01-board-scrum-schema.md:384`)이 「**PR ③ 전에
닫는다**」로 기한을 박았고 그 기한을 넘겼다. PR ③(#424)은 닫는 대신 PR ④ 로 분리하며 인계 지침
4건을 남겼으나(`docs/plans/2026-09-02-scrum-board-screen.md:300-325`) **그 분리를 장부에 등재하는
task 가 실행되지 않아** 2026-09-02 까지 어느 목록에도 없었다(PR ⓪ 이 복구).

**FR.** `FR-BD-04` D4 잔여. 신규 FR 없음 → 총수 **144 불변**. **마이그레이션 0 · 신규 의존성 0 · FE 변경 0.**

### 티어 — T3

`shared-kernel` 을 만진다. 작업 티어표가 shared-kernel 을 T3 로 지정한다.

### ⚠️ 「한 PR = 한 BC」 명시적 예외

`shared-kernel` + `issue-tracking` + `agile-planning` 3모듈을 동시에 바꾼다. **회피 불가다.**

| 대안 | 왜 안 되나 |
|---|---|
| agile-planning 이 issue-tracking 을 직접 import | `AgilePlanningBcArchTest.mustNotImportIssueTracking` 이 막는다 |
| pgmq 이벤트로 우회 | 보드 GET 은 **동기 읽기**라 큐로 대체 불가 |
| agile-planning 안에서만 해결 | 자르기가 issue-tracking SQL 안에서 일어난다 |

의존 방향은 불변이다 — `agile-planning ──(port)─▶ shared-kernel ◀──(impl)── issue-tracking`.
같은 성격의 예외를 `docs/plans/2026-09-02-scrum-board-screen.md:112` 가 이미 기록했다.

## Jira 대조

계약 §1-0 **재사용 승계.** 이 PR 은 새 조작을 만들지 않는다 — 스크럼 보드가 「활성 스프린트의
이슈만」 보여준다는 계약은 [ADR 2026-09-01](../adr/2026-09-01-board-type-and-active-sprint.md)
이 이미 실물 조회로 확정했다. **출처·조회일을 그대로 승계**한다(조회일 2026-09-01 ·
전 행 Jira Cloud company-managed). **추가 조회 0건.**

| # | 원문 인용 | 출처 |
|---|---|---|
| **J5** | *"Your board only displays work items once you've started the sprint, and **the board displays only the work items added to the sprint you started**."* | [plan-a-sprint](https://support.atlassian.com/jira-software-cloud/docs/plan-a-sprint/) |
| **J6** | 카드가 보드에 뜨는 조건 3개 — 상태가 컬럼에 매핑 · **"is in an active sprint (for Scrum boards)"** · 보드 필터에 일치 | [use-active-sprints](https://support.atlassian.com/jira-software-cloud/docs/use-active-sprints/) |

**대조 결과 — 이 PR 은 패리티를 새로 만들지 않고 「이미 약속한 패리티가 조용히 깨지던 것」을 고친다.**
J5 는 *the work items added to the sprint* 를 **전부** 보여준다고 적는다. 그런데 BTS 는 프로젝트
이슈를 1,001건 먼저 자른 뒤 스프린트로 걸러, 활성 스프린트 이슈가 오래됐으면 **말없이 빠진다.**
Jira 문서 어디에도 「오래된 이슈는 보드에서 빠진다」는 서술이 없다 — 즉 **의도적 편차가 아니라 결함**이다.

**조회했으나 원문을 확보하지 못한 것.** Jira 가 보드 카드 조회에 **상한을 두는지**, 둔다면 상한 초과를
사용자에게 어떻게 알리는지. `use-active-sprints` · `plan-a-sprint` 어느 쪽도 다루지 않았다.
→ BTS 의 `truncated` 플래그는 그 공백에서 **BTS 고유 장치**로 남는다(모른다는 사실을 적는다).

## 🛑 인계 지침 정정 — 4건 중 2건이 실측에서 뒤집혔다

`docs/plans/2026-09-02-scrum-board-screen.md:309-322` 를 그대로 따르지 않았다. **왜 안 따랐는지를
여기 적는다** — 다음 사람이 같은 문서를 읽고 같은 결론에 다시 도달하지 않게 하기 위해서다.

| 지침 | 원문 요지 | 실측 |
|---|---|---|
| **1** | 포트 경유가 회피 불가다 | ✅ **참.** 위 표로 재확인 |
| **2** | `BacklogApplicationService` 에 같은 술어를 넣으면 백로그 칸이 전멸한다 | ✅ **참.** 그 파일을 만지지 않았다 |
| **3** | 새 포트 메서드는 `abstract` 로 — 구현자가 컴파일 에러로 전수 드러난다 | 🛑 **부분 거짓** (아래) |
| **4** | `BOARD_CARD_FETCH_LIMIT` 이 `const val` 이라 RED 를 못 만든다 | 🛑 **불필요** (아래) |

### 지침 3 — 「전수 드러남」은 성립하지 않는다

abstract 새 메서드를 넣으면 구현체 15곳이 컴파일 에러로 드러나지만 **`mockk(relaxed = true)` 3곳은
컴파일도 런타임도 조용히 통과한다** — `BoardApplicationServiceTest.kt:116` ·
`BacklogApplicationServiceTest.kt:161` · `SprintApplicationServiceTest.kt:105`.

더 나쁘게, abstract 화는 `BoardPortContractTest.kt:71,80` 의 `object : BoardIssueLookupPort {}` 를
컴파일 불가로 만든다. 그 테스트는 **fail-safe default 의 존재 자체를 검증하는 테스트**라, 고치는
순간 그 의도가 파괴된다.

**결론.** 새 메서드를 만들지 않는다 → 함정이 소멸한다. `BoardCardFilter` 확장이 대안이고,
`statusKeys` 가 이미 같은 모양의 선례다(파서가 만들지도 직렬화하지도 않는 서버 내부 전용 필드).

**단, 지침 3 의 진짜 위험은 남는다** — 포트 계약이 filter 드롭을 허용하므로 소비측 사후 필터를
지우면 안 된다. spec C5 와 코드 KDoc 양쪽에 못박았다.

### 지침 4 — 1,001행 배치 헬퍼가 이미 있다

`BoardIssueLookupAdapterTest.kt:346 insertNonMatchingIssuesBatch`(JDBC addBatch, 100건마다 flush) +
`IssueTestcontainersBase.cleanIssues()` 의 `@BeforeEach` 정리. `BOARD_CARD_FETCH_LIMIT` 은
`internal const val`(`IssueRepository.kt:1221`)이고 **가시성을 바꿔 얻는 것이 0** 이다. 건드리지 않았다.

## Task (TDD red→green · `test:` 가 `feat:` 앞)

| # | 커밋 | 내용 | RED 근거 |
|---|---|---|---|
| R1 | `test:` | shared-kernel `BoardCardFilterTest` 3케이스 | `No parameter with name 'issueKeys' found` (컴파일) |
| R1 | `feat:` | `BoardCardFilter.issueKeys` + `isEmpty()` 편입 | — |
| R2 | `test:` | issue-tracking `S13`·`S14` + 배치 헬퍼 `startSeq` | S13 이 대상 3건 대신 배치 1,001건을 돌려준다 |
| R2 | `feat:` | `buildIssueKeyCondition` 편입 | — |
| R3 | `test:` | agile-planning `RecordingLookup` 5케이스 | 4 failed / 45 |
| R3 | `feat:` | `getBoard` 순서 재배치 + `fetchBoardIssues` + 포트 KDoc | — |

### 🛑 R2 의 가짜 GREEN 함정 — 삽입 **순서가 판정 그 자체**다

기존 `S9`(EC7)는 대상 이슈를 **나중에**(=최신) 넣는다. `created_at DESC` LIMIT 창 안에 이미
들어오므로 **「LIMIT 뒤 필터」 구현으로도 통과한다.** 그 모양을 베끼면 RED 가 서지 않는다.

`S13` 은 대상 3건을 **먼저**(=가장 오래된) 넣고 비대상 LIMIT+1 건을 뒤에 넣는다. 실측 RED —
반환이 `TPRJ-1`~`3` 이 아니라 `TPRJ-4`~`TPRJ-1004` 였다.

부수로 배치 헬퍼의 flush 주기를 `seq % 100` 에서 **삽입 순번** 기준으로 바꿨다. `seq` 절대값으로
재면 `startSeq` 가 100 의 배수가 아닐 때 주기가 어긋난다. S9 는 기본값 `startSeq = 1` 로 무영향이다.

## 검증

```bash
cd backend
./gradlew :modules:shared-kernel:test :modules:issue-tracking:test :modules:agile-planning:test
./gradlew ktlintCheck detekt
```

### 회귀 방어 — 무엇이 이것을 지키나

| 위험 | 방어 |
|---|---|
| 🛑 백로그 차집합 전멸 | `BacklogApplicationService` 무변경 · `BacklogApplicationServiceTest` + `BacklogControllerIntegrationTest` 전량 |
| 칸반 배치 회귀 | `칸반 보드는 같은 보드에 활성 스프린트가 있어도 이슈 전량을 배치한다` + 신규 `칸반 보드는 issueKeys 를 비운 채 포트를 호출한다` |
| `isEmpty()` 확장 누락 → **필터 전체 드롭** | `issueKeys 만 있어도(다른 필드 빈 상태) isEmpty 는 false`. `buildFilterCondition` 이 `if (filter.isEmpty()) return null` 로 시작하므로 **이 케이스가 유일한 방어**다 |
| 빈 스프린트에 허위 truncated | `활성 스프린트에 이슈가 0건이면 포트를 아예 호출하지 않는다` |
| 이슈 목록 API 오염 | `listWithType`·`listWithTypeByCursor` 가 같은 `buildFilterCondition` 을 쓰지만 `IssueFilterQueryParser` 가 `issueKeys` 를 세팅하지 않아 inert — `IssueRepositoryFilterTest` · `IssueSecurityListFilterTest` 전량 |
| 퀵필터 문자열 유출 | `BoardFilterQueryParser` 무변경 → `BoardFilterQueryParserTest` 왕복 전량 |
| visibility 우회 | 신규 `S14` — 키로 명시 지정해도 비가시 등급은 못 끌어온다 |
| BC 격리 | `AgilePlanningBcArchTest` — `com.bts.issue..` 신규 import 0 |

### 이 PR 이 재지 **않는** 것 (알고 남긴다)

- **IN 목록 폭주.** 스프린트 규모(수백)를 전제한다. `issues.key` 인덱스가 있어 무해하지만
  바인드 파라미터 상한(65,535)은 재지 않는다 — 실무 범위 밖이다.
- **`truncated` 의 의미 변화.** 이제 스크럼 보드의 `truncated` 는 「활성 스프린트 이슈가 1,000건을
  넘었다」를 뜻한다. 프로젝트 전체 이슈 수와 무관해진다. 화면 문구는 그대로 두었다 —
  스프린트가 1,000건을 넘는 것은 그 자체로 이상 상황이고, 문구를 바꾸면 칸반과 갈린다.
