# 보드 생성 종류 선택 UI — 스크럼/칸반 (FR-BD-04 D6 · 로드맵 PR ②)

> 티어: T2
> slug: board-type-create-ui
> type: ui
> agent: frontend-engineer
> 생성: 2026-09-01

## Brief

**사용자 원문.** `fr-bd-04 d6 진행 해주고 pr 418 홀드 되어 있는데 이것도 같이 정리 해줘`
(PR 418 정리는 별건으로 선행 완료 — 커밋 `73753735c`, 홀드 사유를 「보드 모델 설계 대기」에서
「FR-BD-04 D6 선행 대기」로 갱신하고 plan 실측 6건을 정정했다.)

**이 PR 이 하는 것.** ADR 3분할의 **PR ②** — 「새 보드」 흐름에 **1단계 종류 선택**(스크럼/칸반)을
더한다. 설계 정본은 [ADR 2026-09-01 board-type-and-active-sprint](../adr/2026-09-01-board-type-and-active-sprint.md) **D4**.

**왜 지금인가.** PR #421(`cfa7c920c`)이 D1~D5(스키마·백엔드)를 넣었지만 `apps/web` 은 **0파일**이라
사용자에게 보이는 변화가 0이다. `POST /api/v1/boards` 가 `boardType` 을 받고
`GET /api/v1/boards/{id}` 가 `boardType`·`activeSprint` 를 내주는데 **화면이 보내지도 읽지도 않는다.**
②가 오지 않으면 스키마만 남는다(#421 CEO 렌즈 C1, 수용됨).

**classify 결과.** `type=ui` · `agent=frontend-engineer` · `primary_bc=agile-planning` ·
`tier=T1`(판별식 실측) — **선언은 T2**.

**FR.** `FR-BD-04` **D6 일부**. 신규 FR 없음 → **총수 144 불변**.
D6 은 세 조각(종류 선택 · 스크럼 보드 화면 · 백로그 `?board=` 스코프)이고 이 PR 은 **첫 조각만**
닫는다. **D6 체크박스는 `[ ]` 로 남고 진척 표기도 안 바뀐다** — PR ③ 이 닫는다.

**마이그레이션 0 · 신규 의존성 0 · 신규 프리미티브 0.**

### 티어 — 선언 T2 · 실측 T1

ADR 이 PR ② 를 **T2** 로 지정했다(ADR §「결정된 것」 3분할 표). 실측 표면은 `apps/web/src` 뿐이라
`classify-task.ts` 도 **T1** 을 돌려준다 — **백엔드 0줄**이다. `boardType` 을 받고 되싣는 계약이
#421 에 이미 다 있다.

**지정 티어가 우선**이므로(`/bts` 판정 5문 ②) T2 절차로 간다 — 정식 TDD red-first · plan 1파일 ·
리뷰 2종 · 게이트 1+2. **선언 T2 · 실측 T1 을 게이트 2 요약에 나란히 싣는다**(판정 5문 ⑤).

### 범위 밖 — 고려했고 미룬 것

| 항목 | 사유 |
|---|---|
| `BoardSummaryResponse` 에 `boardType` | 목록·스위처에 종류를 실을 **소비처가 아직 없다**. PR ③ 이 스크럼 보드 화면을 만들 때 필요해진다. A1 plan 이 `canDelete` 를 같은 논리로 미룬 선례 |
| `boardDetailSchema` 에 `boardType`·`activeSprint` | 소비처가 PR ③ 의 스크럼 보드 화면이다 |
| 스크럼 보드 화면 · 백로그 `?board=` 스코프 | **D6 잔여 = PR ③** |
| E2E | **D7 = PR ③** |
| 소스 선택(J4) · 생성 후 종류 변경 | ADR 의도적 편차 X1 · X3 |
| 칸반 백로그(kanplan) | ADR 의도적 편차 X4 |
| E-6(두 번째 스크럼 보드 빈 보드 고정) · 스크럼 × `truncated` | 「PR ③ 전에 닫는다」로 기한이 박힌 부채. 백로그·보드 조회를 건드릴 때 닿는다 — 이 PR 은 생성 플로우만 만진다 |

## Jira 대조 (전 타입 필수)

`jira-parity-contract.md` §1 5단계 산출물. **§1-0 재사용** — ADR
`docs/adr/2026-09-01-board-type-and-active-sprint.md` §「Jira Cloud 실물 조회」가 **2026-09-01 에
조회한 J1~J13 을 출처·조회일 그대로 승계**한다(ADR 「다음 한 걸음」이 이 재사용을 명시했다).
전 행 **Cloud company-managed** 기준이다.

| # | 원문 인용 | 출처 | 조회일 |
|---|---|---|---|
| **J1** | 보드 생성 모달에서 **"Create a Scrum board"** 또는 **"Create a Kanban board"** 를 고른다 | [create-a-board](https://support.atlassian.com/jira-software-cloud/docs/create-a-board/) | 2026-09-01 |
| **J2** | 사이드바 경로 — *"hover over a company-managed space in the sidebar until a plus icon appears, select **Create board**, choose **Scrum**, enter a board name, select what to include from a dropdown, then select **Create**"* | 〃 | 2026-09-01 |
| **J3** | 전역 경로 — *"select the search field ... **Go to all: Boards**, then select **Create board** in the upper right"* | 〃 | 2026-09-01 |
| **J4** | 소스 선택 — *"select whether to base your board on a new software space or one or more existing spaces"* | 〃 | 2026-09-01 |
| **J6** | *"Active sprints are only available on Scrum boards."* — 종류가 화면 의미를 가른다 | [use-active-sprints](https://support.atlassian.com/jira-software-cloud/docs/use-active-sprints/) | 2026-09-01 |

### 채택

**J1·J2 의 순서를 그대로 따른다 — 종류를 먼저 묻고, 그 다음 이름.** ADR §D4 가 확정한 흐름이다.

```
「새 보드」
   ├─ 1단계. 종류 선택        ← J1 (모달 1단계)
   │     ○ 스크럼 보드  — 스프린트로 일하는 팀
   │     ○ 칸반 보드    — 흐름으로 일하는 팀
   ├─ 2단계. 이름 입력        ← J2 "enter a board name"
   └─ [보드 만들기] → 생성된 보드로 이동
```

### 편차

| # | 항목 | 결정 | 사유 |
|---|---|---|---|
| **X1** | 소스 선택(J4) — *"base your board on a new space or existing spaces"* | **미채택** | ADR 의도적 편차 X1. BTS 보드는 `project_key` 문자열에 고정돼 있어 다중 스페이스 개념이 없다. 저장 필터 기반 보드(J13)는 패리티 포기 후보로 A1 plan 이 이미 등재했다 |
| **X2** | 진입점(J2 사이드바 · J3 전역) | **보드 스위처 드롭다운 하나** | ADR 의도적 편차 X2. BTS 는 프로젝트 컨텍스트 안에서만 보드를 만든다 — 전역 「Go to all: Boards」에 대응하는 화면이 없다 |
| **X3** | 생성 후 종류 변경 | **없음** | ADR 의도적 편차 X3. 종류가 바뀌면 카드 집합의 의미가 통째로 바뀐다 |
| **X4** | 기본 선택 | **칸반** | 백엔드가 `boardType` 을 **선택 인자**로 두고 생략 시 KANBAN 이다(#421). 기본을 칸반으로 두면 기존 사용자의 「이름 쓰고 만들기」 흐름이 클릭 1회만 늘고 결과가 같다. Jira 는 기본 선택을 명시하지 않는다 |

## 도메인 정리 (← /bts-spec §1 채움)

## 스펙 (← /bts-spec §2 채움)

## Sanity Check (← /bts-spec §3 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
