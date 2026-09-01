# 스크럼 보드 화면 + 백로그 보드 스코프 (FR-BD-04 D6 잔여 · D7 · 로드맵 PR ③)

> 티어: T2
> slug: scrum-board-screen
> type: feature
> agent: backend-engineer
> 생성: 2026-09-02

## Brief

**사용자 원문.** `FR-BD-04 PR ③ — 스크럼 보드 화면 + 백로그 ?board= 스코프 + D7 E2E`

**이 PR 이 하는 것.** ADR 3분할의 **PR ③** — 스크럼 보드가 **활성 스프린트의 이슈만** 보여주고,
백로그가 **보드 단위**로 스코프된다. 이것으로 **FR-BD-04 가 완주**한다(D6 잔여 + D7).
설계 정본 [ADR 2026-09-01 board-type-and-active-sprint](../adr/2026-09-01-board-type-and-active-sprint.md)
**§D2**(스프린트·백로그는 보드 소속) · **§D3**(스크럼 보드 화면 = 활성 스프린트만) · **§D6**(다수 보드 유지).

**왜 지금인가.** ①(#421 스키마·백엔드)과 ②(#422 종류 선택 UI)가 머지됐지만 **화면 의미가 아직 안 갈렸다** —
스크럼으로 보드를 만들어도 칸반과 똑같이 보인다. `GET /boards/{id}` 가 `boardType`·`activeSprint` 를
내주는데 화면이 안 읽는다. ADR §D3 이 약속한 「스프린트를 시작하면 보드가 바뀐다」가 이 PR 로 성립한다.

**classify 결과.** `type=backend`(판별식) · `agent=backend-engineer` · `primary_bc=agile-planning` ·
`tier=T1`(판별식 실측 — 제목에 백엔드 신호가 없어 낮게 나왔다) — **선언은 T2**.

**FR.** `FR-BD-04` **D6 잔여 + D7**. 신규 FR 없음 → **총수 144 불변**.
**이 PR 로 D6·D7 체크박스가 `[x]` 가 되고 FR-BD-04 가 완주한다** — 진척 표기가 바뀌는 첫 PR 이다
(②는 D6 「일부」라 표기 불변이었다). `agile-planning` 진척 열 `☐ D단계 (BD-04)` → `☑`.

**마이그레이션 0**(스키마는 #421 이 완료) · **신규 의존성 0**.

### 티어 — 선언 T2 · 실측 T2

ADR 3분할 표가 ③ 을 **T2** 로 지정했고, ②와 달리 **백엔드 `main` 이 실제로 붙어** 실측도 T2 다.
`classify-task.ts` 가 T1 을 돌려준 것은 제목 문자열에 백엔드 신호가 없기 때문이고, 표면이 기준이다.

## Jira 대조 (전 타입 필수)

`jira-parity-contract.md` §1 5단계 산출물. **§1-0 재사용** — ADR §「Jira Cloud 실물 조회」가
**2026-09-01 에 조회한 J1~J13 을 출처·조회일 그대로 승계**한다. 전 행 **Cloud company-managed** 기준.

| # | 원문 인용 | 출처 | 조회일 |
|---|---|---|---|
| **J5** | *"Your board only displays work items once you've started the sprint, and **the board displays only the work items added to the sprint you started**."* | [plan-a-sprint](https://support.atlassian.com/jira-software-cloud/docs/plan-a-sprint/) | 2026-09-01 |
| **J6** | 카드가 보드에 뜨는 조건 3개 — 상태가 컬럼에 매핑 · **"is in an active sprint (for Scrum boards)"** · 보드 필터에 일치. *"Active sprints are only available on Scrum boards."* | [use-active-sprints](https://support.atlassian.com/jira-software-cloud/docs/use-active-sprints/) | 2026-09-01 |
| **J7** | 백로그 화면 순서 — *"**Scroll down past any currently existing sprints** to the section titled **Backlog**."* → 스프린트가 위, 백로그가 아래 | [create-sprints](https://support.atlassian.com/jira-software-cloud/docs/create-sprints-in-company-managed-projects/) | 2026-09-01 |
| **J10** | Start sprint 다이얼로그 = 이름·시작일·종료일·목표(선택). 시작 후 **Active sprints 페이지로 이동** | [plan-a-sprint](https://support.atlassian.com/jira-software-cloud/docs/plan-a-sprint/) | 2026-09-01 |
| **J12** | 칸반도 백로그를 가질 수 있다(kanplan) — *"Traditionally, teams that work in a kanban style don't use a backlog…"* | [enable-the-backlog](https://support.atlassian.com/jira-software-cloud/docs/enable-the-backlog/) | 2026-09-01 |

### 채택

- **J5·J6 — 스크럼 보드는 활성 스프린트의 이슈만 보여준다.** 백엔드는 #421 이 이미 그렇게 배치한다
  (`placeCards` 호출 **전** 필터). 이 PR 은 **화면이 그 의미를 드러내게** 한다 —
  활성 스프린트가 없으면 빈 상태이고, 그것이 「아직 시작 안 함」임을 사용자가 알아야 한다.
- **J7 — 백로그는 스프린트가 위, 백로그가 아래.** 기존 화면이 이미 그 순서다(무변경).
  이 PR 이 더하는 것은 **어느 보드의 스프린트인가**라는 축이다.

### 편차

| # | 항목 | 결정 | 사유 |
|---|---|---|---|
| **X4** | 칸반 백로그(kanplan · J12) | **미채택** | ADR 의도적 편차 X4. **칸반 보드는 백로그 탭 없이 간다** |
| **X5** | 백로그 진입 시 보드 미지정 | **기본 보드로 폴백** | ADR 「결과」 절. Jira 는 보드 컨텍스트가 URL 에 항상 있지만 BTS 는 기존 링크(`?board=` 없음)가 살아 있어야 한다 — **깨진 링크를 만들지 않는 것이 패리티보다 앞선다** |

## 도메인 정리 (← /bts-spec §1 채움)

## 스펙 (← /bts-spec §2 채움)

## Sanity Check (← /bts-spec §3 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
