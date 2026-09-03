# 칸반 소속 스프린트 이관과 스프린트 시작 락 예산 (부채 165 · 166)

> 티어: T3
> slug: kanban-sprint-move-and-lock-budget
> type: migration
> agent: db-engineer
> 생성: 2026-09-03

FR-BD-04 후속. 스펙 정본은 [docs/specs/2026-09-03-kanban-sprint-move-and-lock-budget.md](../specs/2026-09-03-kanban-sprint-move-and-lock-budget.md),
설계 결정은 [docs/adr/2026-09-03-kanban-sprint-move-and-lock-budget.md](../adr/2026-09-03-kanban-sprint-move-and-lock-budget.md).

## Brief

장부 부채 **165**·**166** 을 한 PR 로 닫는다. 둘 다 `agile-planning` 이고 같은 함수
`SprintApplicationService.start` 를 가리킨다. 마이그레이션이 끼므로 티어는 T3(섞이면 최고 티어).

- **165** — #431 이 `resolveTargetBoard` 에 `boardType == SCRUM` 을 넣어 **생성**만 막았다.
  `start`(`SprintApplicationService.kt:286-303`)는 보드를 다시 읽지 않고 종류도 안 본다. 선재 칸반
  소속 스프린트는 `start` 200 을 받고 ACTIVE 가 되는데 `BoardApplicationService.kt:256` 이
  `boardType == SCRUM` 일 때만 `findActiveByBoard` 를 부르므로 어느 화면에도 나타나지 않는다.
- **166** — `start` 가 락 뒤에서 `findActiveByBoard` 만 재조회하고 스프린트 자체(`status`·`version`)는
  락 앞 스냅샷을 쓴다. READ COMMITTED 에서만 안전한데 `agile-planning` 은 격리 수준을 어디에도
  안 박는다(실측 0건 · identity-access 는 37곳 명시). `pg_advisory_xact_lock` 은 무한 대기이고
  `lock_timeout`·`statement_timeout` 이 저장소 전역 0건이다. 형제 락
  `acquireProjectScrumBoardLock` 도 같은 상태다.

classify 결과 — `type=migration · agent=db-engineer · tier=T1(착수 시점 diff 없음)`.
**선언 티어는 Maxi 지정 T3** 가 우선한다(`/bts` 판정 5문 ②).

## 확정된 결정 (Maxi · 2026-09-03)

| # | 결정 | 근거 |
|---|---|---|
| D1 | 165 처방 = **이관 마이그레이션 + `start` 가드** | 데이터를 살리면서 불변식을 잠그는 유일한 조합 |
| D2 | ACTIVE 충돌 시 **이관 대상을 PLANNED 로 내린다** | Jira 근거 없음(J17). `findActiveByBoard` 가 `limit(1)` 이라 ACTIVE 유지 시 이관 뒤에도 안 보이고 409 로 손도 못 대며 선행 스프린트 완료일에 예고 없이 나타난다 |
| D3 | 166 범위 = 격리 명시 + 락 뒤 재조회 + 락 예산 · **형제 락까지** | 두 락이 같은 결함을 공유한다 |
| D4 | **한 PR · T3** | 둘 다 `start` 를 고친다. 스택 분할은 #429→#430 교착 사고의 재발 자리 |

## Jira 대조

계약 §1-0 **재사용 승계.** J1·J5·J6·J11 은 [ADR 2026-09-01](../adr/2026-09-01-board-type-and-active-sprint.md)
이 실물 조회로 확정한 표에서 **출처·조회일을 그대로 승계**한다(조회일 2026-09-01 · 전 행 Jira Cloud
company-managed). 이번 PR 이 **새로 건드리는 조작**(스프린트를 다른 보드로 옮긴다 · 한 보드에 활성
스프린트가 둘일 때)만 추가 조회했다 — 조회일 **2026-09-03**.

| # | 원문 인용 | 출처 | Cloud/DC |
|---|---|---|---|
| **J14** | *"Sprints aren't dependent on a specific board or project. It's therefore possible for a sprint to be displayed in more than one board."* | https://support.atlassian.com/jira/kb/sprints-appearing-on-multiple-boards-in-jira-data-center-server-or-cloud/ | Cloud+DC |
| **J15** | `originBoardId` = *"the ID of the board where the sprint was originally created."* | https://support.atlassian.com/jira/kb/sprints-appearing-on-multiple-boards-in-jira-data-center-server-or-cloud/ | Cloud+DC |
| **J16** | *"The sprint was created while viewing that board... A board referencing the sprint was copied... Issues included in the board's JQL filter are associated with the sprint."* | https://support.atlassian.com/jira/kb/sprints-appearing-on-multiple-boards-in-jira-data-center-server-or-cloud/ | Cloud+DC |
| **J17** | origin board 를 **바꾸는 조작을 문서가 다루지 않는다.** 공식 답변은 「새 스프린트를 만들어 이슈를 옮겨라」 | https://support.atlassian.com/jira/kb/sprints-appearing-on-multiple-boards-in-jira-data-center-server-or-cloud/ | Cloud+DC |
| **J18** | *"If it's not intended for issue SSPA-9 to be in sprint SSPB 3, then it's suggested to disassociate sprint SSPB 3 from issue SSPA-9"* — 처방이 **스프린트 상태를 건드리지 않는다** | https://support.atlassian.com/jira/kb/multiple-active-sprints-in-agile-scrum-board-when-the-parallel-sprint-option-is-disabled/ | **DC 전용 · Cloud 아님** |

### 채택 판정

- **J14·J15·J16 — 기등재 편차 X5 를 재확인한다(패리티 포기).** Jira 는 보드-스프린트를 **필터 기반
  표시**로 풀고 소속은 origin(생성 이력)일 뿐이다. BTS 는 `sprints.board_id NOT NULL` **직접 소속**이다.
  이 PR 은 X5 를 바꾸지 않는다 — 저장 필터·AQL 은 search BC 소관이라 BC 격리상 참조 불가라는
  X1 승계 사유가 그대로 유효하다.
- **J17 — 대응 없음.** Jira 에 「스프린트를 다른 보드로 옮긴다」는 **사용자 조작이 없다.** 그러나 이 PR 의
  이관은 사용자 조작이 아니라 **데이터 정정**이고, `V506__sprint_board_id.sql:49-53` 이 이미 같은
  일(선재 스프린트 전 행 `board_id` 백필)을 했다. 선례의 확장이지 새 조작이 아니다.
- **J18 — 준용하지 않는다 (의도적 편차 X9 · 신규).** Jira 는 ACTIVE 다중을 데이터로 막지 않고 표시로
  흡수한다. BTS 는 `SprintRepository.findActiveByBoard` 가 `orderBy(created_at asc).limit(1)` 이라
  흡수가 **불가**하다. 그대로 두면 이관 대상이 화면에서 계속 안 보이고, `start` 는 409 로 막히며,
  선행 스프린트 완료일에 예고 없이 나타난다. 근거는 Jira 원문이 아니라 **BTS 자체 일관성**이며,
  D2 가 그 근거로 서 있다는 사실을 여기 적는다.

### 조회했으나 원문을 확보하지 못한 것

**칸반 보드에서 스프린트 생성을 시도**했을 때 Jira 가 무엇을 돌려주는지. J6
(*"Active sprints are only available on Scrum boards."*)가 「스크럼 전용」까지만 적고 거부 동작을
다루지 않는다 — Jira 는 UI 자체가 없어 **도달 불가**로 보이나 원문 확증은 없다. #431 이 남긴 같은
공백을 이번에도 메우지 못했다.

## 도메인 정리 (← /bts-spec §1 채움)

## 스펙 (← /bts-spec §2 채움 · 정본은 docs/specs 쪽)

## Sanity Check (← /bts-spec §3 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
