<!-- 보드에 종류(Scrum/Kanban)를 도입하고 스크럼 보드가 활성 스프린트를 보여주도록 하는 결정 -->

# ADR — 보드는 종류를 갖는다 · 스크럼 보드는 활성 스프린트를 보여준다

> 날짜. 2026-09-01
> 상태. **채택 (Active)** — 2026-09-01 Maxi 인가. 티어 **T3**(마이그레이션 + 기존 화면 의미 변경).
> 관련 FR. **FR-BD-04 신설 완료** (보드 종류 + 활성 스프린트 보드 · 총수 143 → 144) · FR-BD-01(보드) · FR-BL-01/02(백로그·스프린트)
> BC. agile-planning 단독
> 근거 규칙. `CLAUDE.md` 작업 티어 T3 · `DATA.md §4` 마이그레이션 · `docs/design/jira-parity-contract.md` §1
> 관련 스키마. `V500__boards.sql` · `V503__sprints.sql`
> 관련 plan. [sprint-manage-ui](../plans/2026-09-01-sprint-manage-ui.md) — **이 ADR 뒤로 보류됨** (PR #418)

## 맥락

### 왜 지금인가

2026-09-01 게이트 1 에서 Maxi 가 물었다.

> *"지라 클라우드에서는 스크럼으로 스프린트를 표현하고 있고 백로그에 있는걸 스프린트로 넘길수
> 있고 스프린트를 시작하면 보드로 표현 되는데 지금 BTS에서는 다르게 동작하는거 같네.
> 그리고 프로젝트에 다수 보드가 존재할 수 있는 구조여야 하는데 그것도 확인해볼래?"*

이어서 범위를 확정했다 — *"지라클라우드와 동일하게 보드 생성 플로우와 스크럼 UI/UX 동일하게
설계해"* · *"프로젝트에 다수 보드가 생성 가능하도록 설계하고"*.

**실측 결과 지적이 정확했다.** 그리고 이 갭은 보드 로드맵 A~D **어디에도 없었다** —
선행 플랜의 조작감 갭 표가 「Scrum/Kanban 타입 부재 → 3단계」로 한 줄 적어 뒀으나 PR C·D 의 실제
범위에 들어가지 않아 **담당자가 없는 상태**였다.

### Jira Cloud 실물 조회 (2026-09-01 · 전 행 Cloud company-managed)

| # | 원문 인용 | 출처 |
|---|---|---|
| **J1** | 보드 생성 모달에서 **"Create a Scrum board"** 또는 **"Create a Kanban board"** 를 고른다 | [create-a-board](https://support.atlassian.com/jira-software-cloud/docs/create-a-board/) |
| **J2** | 사이드바 경로 — *"hover over a company-managed space in the sidebar until a plus icon appears, select **Create board**, choose **Scrum**, enter a board name, select what to include from a dropdown, then select **Create**"* | 〃 |
| **J3** | 전역 경로 — *"select the search field ... **Go to all: Boards**, then select **Create board** in the upper right"* | 〃 |
| **J4** | 소스 선택 — *"select whether to base your board on a new software space or one or more existing spaces"* | 〃 |
| **J5** | *"Your board only displays work items once you've started the sprint, and **the board displays only the work items added to the sprint you started**."* | [plan-a-sprint](https://support.atlassian.com/jira-software-cloud/docs/plan-a-sprint/) |
| **J6** | 카드가 보드에 뜨는 조건 3개 — 상태가 컬럼에 매핑 · **"is in an active sprint (for Scrum boards)"** · 보드 필터에 일치. *"Active sprints are only available on Scrum boards."* | [use-active-sprints](https://support.atlassian.com/jira-software-cloud/docs/use-active-sprints/) |
| **J7** | 백로그 화면 순서 — *"**Scroll down past any currently existing sprints** to the section titled **Backlog**."* → 스프린트가 위, 백로그가 아래 | [create-sprints](https://support.atlassian.com/jira-software-cloud/docs/create-sprints-in-company-managed-projects/) |
| **J8** | **Create sprint** 버튼은 Backlog 섹션 헤더의 *"on the far right"* | 〃 |
| **J9** | *"create a sprint for your current iteration, or **multiple future sprints** if you want to plan several iterations ahead"* | 〃 |
| **J10** | Start sprint 다이얼로그 = 이름 · 시작일 · 종료일 · 목표(선택). 시작 후 **Active sprints 페이지로 이동** | [plan-a-sprint](https://support.atlassian.com/jira-software-cloud/docs/plan-a-sprint/) |
| **J11** | *"If you want to have more than one active sprint at a time, you'll need to **enable parallel sprints**"* → **기본 활성 1개** | 〃 |
| **J12** | 칸반도 백로그를 가질 수 있다(kanplan) — *"Traditionally, teams that work in a kanban style don't use a backlog. If you work in a kanban style, you can still help your team prioritize upcoming work with a backlog."* | [enable-the-backlog](https://support.atlassian.com/jira-software-cloud/docs/enable-the-backlog/) |
| **J13** | 보드는 근본적으로 **저장된 필터의 뷰** — *"a board is available to all users who can view the saved filter on which the board is based"* | [create-a-board-based-on-filters](https://support.atlassian.com/jira-software-cloud/docs/create-a-board-based-on-filters/) |

### BTS 현행 실측 (2026-09-01)

| 축 | 실측 | Jira 대비 |
|---|---|---|
| 보드 종류 | **없다.** `V500__boards.sql` 에 type 컬럼 부재. 테이블 주석이 *"칸반 보드 Aggregate"* | ❌ 갭 |
| 보드 ↔ 스프린트 | **모른다.** `BoardCardPlacement`·`BoardApplicationService`·`BoardRepository`·`BoardController` **4파일 전부 `sprint` 언급 0건** | ❌ 갭 |
| 스프린트 소속 | **프로젝트.** `sprints.project_key VARCHAR(64)` — `board_id` 없음 | ❌ 갭 (Jira 는 보드 소속) |
| 백로그 소속 | **프로젝트.** `fetchBacklog(projectKey)` · 라우트 `/projects/$projectKey/backlog` | ❌ 갭 |
| 활성 스프린트 개수 | **제약 없다.** `SprintApplicationService.start` 는 상태 전환만 검사하고 개수 가드가 없어 **동시 활성 다수가 가능**하다 | ❌ 갭 (Jira 기본 1개) |
| 백로그 화면 순서 | **스프린트 위 · 백로그 아래.** `BacklogBoard.tsx` 가 `SprintColumn`(:697) → `BacklogColumn`(:717) 순으로 세로 스택 | ✅ **일치** |
| Create sprint 위치 | **Backlog 헤더 안** (F16-11) | ✅ **일치** (J8) |
| Start sprint 다이얼로그 | 이름·목표·시작일·종료일 있음 | ✅ **일치** (J10) |
| 다수 보드 | **된다.** `boards.project_key` 에 UNIQUE 없음 · `createBoard(:101)` 에 개수 가드 없음 · E2E `board-manage.spec.ts` S1/S4 가 두 번째 보드 생성·전환·삭제를 검증 | ✅ **일치** |
| 보드 지정 규약 | `?board=<id>` 이미 사용 중 (`projects.$projectKey.board.tsx:872`) | ✅ 재사용 |

**요지.** 백로그 **화면**은 이미 Jira 스크럼 백로그와 같다. 갭은 **보드 쪽**이다 —
보드에 종류가 없고, 스프린트를 모르고, 스프린트·백로그가 보드가 아니라 프로젝트에 매달려 있다.

---

## 결정

### D1. `boards.board_type` 을 도입한다 (`SCRUM` / `KANBAN`)

Jira 가 **생성 시 첫 질문**으로 묻는 축이다(J1). 보드의 의미 자체를 가르므로 설정이 아니라
**정체성**이고, 생성 후 변경은 이번 범위 밖으로 둔다.

```sql
ALTER TABLE boards ADD COLUMN board_type VARCHAR(16) NOT NULL DEFAULT 'KANBAN'
    CHECK (board_type IN ('SCRUM', 'KANBAN'));
```

`DEFAULT 'KANBAN'` 이 **기존 보드 전량을 무변경으로 보존**한다 — 지금 보드는 전부 칸반이고
그 화면 동작이 바뀌면 안 된다.

### D2. 스프린트와 백로그는 **보드 소속**이 된다

Jira 에서 백로그는 보드의 일부다(J7·J8 이 「Backlog 탭」을 보드 화면의 탭으로 서술). 스프린트도
보드에 매달린다. **프로젝트에 보드가 여럿이면 백로그도 여럿**이어야 「다수 보드」가 의미를 갖는다 —
지금처럼 프로젝트 백로그 하나를 N개 보드가 공유하면 보드를 늘려도 계획 단위가 안 늘어난다.

```sql
ALTER TABLE sprints ADD COLUMN board_id UUID REFERENCES boards (id) ON DELETE CASCADE;
-- 백필 후 NOT NULL 승격 (아래 마이그레이션)
```

라우트는 **기존 `?board=<id>` 규약을 그대로** 쓴다. 새 URL 체계를 만들지 않는다.
`/projects/$projectKey/backlog?board=<id>`.

### D3. 스크럼 보드의 보드 화면 = **활성 스프린트만**

J5·J6 그대로. `BoardCardPlacement.placeCards` 가 지금은 `current_state_key` 로만 배치하는데,
보드가 `SCRUM` 이면 **활성 스프린트에 속한 이슈로 먼저 걸러낸 뒤** 배치한다.

```
[칸반 보드]                        [스크럼 보드]
프로젝트 이슈 전량                  그 보드의 활성 스프린트 이슈
      │                                   │
      ▼                                   ▼
 상태→컬럼 매핑                      상태→컬럼 매핑
      │                                   │
      ▼                                   ▼
   보드 카드                           보드 카드
                                  (활성 스프린트 없으면 빈 상태)
```

**활성 스프린트가 없는 스크럼 보드는 빈 상태**를 보여주고 「백로그에서 스프린트를 시작하세요」로
안내한다. Jira 와 같다(J5 — *"only displays work items once you've started the sprint"*).

### D4. 보드 생성 플로우 = **종류를 먼저 묻는다**

J1·J2 의 순서를 그대로 따른다.

```
「새 보드」
   │
   ├─ 1단계. 종류 선택        ← Jira 모달 1단계 (J1)
   │     ○ 스크럼 보드  — 스프린트로 일하는 팀
   │     ○ 칸반 보드    — 흐름으로 일하는 팀
   │
   ├─ 2단계. 이름 입력        ← J2 "enter a board name"
   │
   └─ [보드 만들기] → 생성된 보드로 이동
```

**소스 선택(J4)은 채택하지 않는다** — 아래 「의도적 편차 X1」.

### D5. 활성 스프린트는 **보드당 1개**로 제한한다

J11 이 기본값을 못박는다. BTS 는 지금 **가드가 없어** 동시 활성 다수가 가능하다.
`start` 에서 **같은 보드에 ACTIVE 스프린트가 있으면 409** 로 막는다.
Jira 의 parallel sprints 옵션은 이번 범위 밖이다.

> 🛑 **선행 결정을 뒤집는다.** `docs/plan/product/agile-planning.md` §3.2 의
> **Deviation(PR #182) ⑤ 는 「동시 ACTIVE 다중 허용」을 명시적으로 결정**해 뒀다.
> 그 결정이 내려질 때 Jira 기본값(J11)이 근거로 검토됐다는 기록은 없다.
> 이 ADR 이 그것을 무효화하고, `fr-index.md` 변경 이력과 §2.4 각주에도 같이 남겼다 —
> **조용히 바꾸지 않는다.**

### D6. 다수 보드는 **이미 되므로 유지한다**

새로 만들 것이 없다. 이번 변경이 **깨뜨리지 않는지만** 검증한다 —
`board-manage.spec.ts` S1/S4 를 그대로 통과시키고, 스크럼 보드 2개 시나리오를 E2E 에 더한다.

---

## 마이그레이션

**핵심 제약 — 기존 화면 동작을 바꾸지 않는다.** 지금 보드를 스크럼으로 승격하면 그 보드의 카드가
「활성 스프린트 것만」으로 줄어 **사용자가 보던 것이 사라진다.**

```
프로젝트에 스프린트가 있다?
   │
   ├─ 예 → 그 프로젝트에 **새 스크럼 보드 1개**를 만들고
   │        기존 스프린트 전량을 거기 붙인다.
   │        기존 칸반 보드는 **손대지 않는다**.
   │
   └─ 아니오 → 아무것도 하지 않는다. 기존 보드는 KANBAN 으로 남는다.
```

- 새 스크럼 보드 이름 — `<프로젝트키> 스크럼 보드`. 컬럼은 기존 `seedColumns` 로 시드한다.
- 백필 후 `sprints.board_id` 를 **NOT NULL 로 승격**한다. nullable 로 남기면
  「보드 없는 스프린트」라는 두 번째 상태가 생겨 조회가 조용히 갈린다.
- 되돌리기 — `board_type` 은 DROP COLUMN, `board_id` 도 DROP COLUMN 으로 원복 가능하다.
  **생성된 스크럼 보드는 남는다**(소프트 삭제 대상). 이 비대칭을 마이그레이션 주석에 적는다.

---

## 결과

**얻는 것.** 백로그 → 스프린트 → 시작 → 보드가 **한 흐름**이 된다. 프로젝트에 스크럼 보드와
칸반 보드를 동시에 둘 수 있다. 다수 보드가 「같은 것을 두 번 보는 것」이 아니라
**서로 다른 계획 단위**가 된다.

**치르는 것.**
- 마이그레이션 2개 + 데이터 백필. **T3.**
- `/projects/$projectKey/backlog` 의 의미가 프로젝트 단위 → 보드 단위로 바뀐다.
  `?board=` 없이 들어온 기존 링크는 **기본 보드로 폴백**해야 죽지 않는다.
- `BoardCardPlacement` 가 스프린트를 알게 되어 순수 도메인 함수의 입력이 늘어난다.
- 로드맵 **C(컬럼 M:N)보다 앞선다** — 보드가 무엇을 담는지가 정해져야 컬럼 매핑 설계가 성립한다.

**깨질 수 있는 것 (착수 시 사전 grep 필수 · 계약 §5).**
`board-manage.spec.ts` · `board-kanban.spec.ts` · `backlog.spec.ts` · `sprint-burndown.spec.ts` ·
`board-swimlane-field-change` · `board-wip-swimlane` · `quick-filter` · `board-epic-swimlane`.

---

## 의도적 편차

- **X1 — 보드의 소스.** Jira 보드는 **저장된 필터의 뷰**이고 생성 시 스페이스/필터를 고른다(J4·J13).
  BTS 보드는 `project_key` 문자열에 고정돼 있고, 저장 필터·AQL 은 **search BC 소관**이라
  agile-planning 이 직접 참조할 수 없다(BC 격리). **패리티 포기**로 명시하고 생성 2단계를
  「종류 → 이름」으로 줄인다. 선행 로드맵도 이 항목을 「패리티 포기 후보」로 이미 판정했다.
- **X2 — 보드 생성 진입점.** Jira 는 전역 Boards 디렉터리(J3)와 사이드바 hover `+`(J2) 둘이다.
  BTS 는 **보드 스위처 드롭다운 하나**로 접는다 — 전역 디렉터리 화면이 없다.
  PR #416 이 이미 세운 관례를 그대로 따른다.
- **X3 — 보드 종류 변경.** Jira 도 생성 후 변경 경로가 문서에 없다(조회했으나 원문 미확보).
  BTS 는 **변경 불가**로 두고, 필요하면 새 보드를 만들게 한다. 근거가 없는 채로 기능을 만들지 않는다.
- **X4 — 칸반 백로그(kanplan).** Jira 는 칸반 보드도 백로그를 켤 수 있다(J12).
  이번 범위 밖. 칸반 보드는 백로그 탭 없이 간다.

## 조회했으나 원문을 확보하지 못한 것

- 보드 종류를 **생성 후 바꿀 수 있는지**. `enable-the-backlog` 문서가 다루지 않았다.
  → X3 에서 「변경 불가」로 두는 근거로 삼되, **모른다는 사실을 적는다.**
- 스프린트 편집·삭제에 필요한 **권한 이름**. 두 문서 어느 쪽도 명시하지 않았다.
  검색 요약에 "Manage Sprints space permission" 이 보였으나 원문을 못 잡았다.
  → BTS 는 기존 `IssuePermission.CREATE` 를 유지한다.

## 대안 (기각)

- **A-1. 보드 종류 없이 「스프린트 필터」를 보드 설정으로 둔다.**
  스윔레인처럼 옵션 하나를 더하는 가벼운 길이다. 기각 — Jira 가 이것을 **생성 시 첫 질문**으로
  두는 이유는 보드의 정체를 가르기 때문이고, 설정으로 내리면 「스프린트 필터가 켜진 칸반 보드」라는
  **Jira 에 없는 제3의 것**이 된다. Maxi 지시가 「동일하게」다.
- **A-2. 스프린트·백로그를 프로젝트 소속으로 남긴다.**
  마이그레이션이 가벼워진다. 기각 — 그러면 보드를 N개 만들어도 백로그가 하나라
  **「다수 보드」가 이름만 남는다.** Maxi 의 두 지시가 서로를 요구한다.
- **A-3. 기존 보드를 스크럼으로 승격한다.**
  새 보드를 안 만들어도 된다. 기각 — 승격된 보드의 카드가 활성 스프린트 것만 남아
  **사용자가 보던 것이 사라진다.** 마이그레이션이 화면을 조용히 바꾸면 안 된다.

## 결정된 것 (2026-09-01 Maxi)

1. **ADR 채택.** D1~D6 전량.
2. **FR-BD-04 신설.** 총수 **143 → 144**. `fr-sync-checklist.md` 9종 전수 동기화를 **이 PR 에서** 수행했다.
3. **PR 3분할.**

| PR | 범위 | 티어 | 마이그 | D 단계 |
|---|---|---|---|---|
| **①** | 스키마 + 백엔드 — `board_type` · `sprints.board_id` · 백필 · 생성 API 종류 인자 · 스크럼 카드 배치 · `start` 활성 1개 가드 | **T3** | **2** | D1~D5 |
| **②** | 보드 생성 플로우 UI — 「보드 만들기」 종류 선택 단계 | T2 | 0 | D6 일부 |
| **③** | 스크럼 보드 화면 + 백로그 보드 스코프 (`?board=`) + E2E | T2 | 0 | D6 잔여 · D7 |

**분할 근거.** 마이그레이션이 든 PR 을 작게 유지해 되돌리기 쉽게 한다. ②·③ 은 마이그레이션 0 이라
①이 머지된 뒤 병행 가능하지만, ③ 이 ② 의 종류 선택 없이는 스크럼 보드를 만들 수단이 없으므로
**① → ② → ③ 순서**를 지킨다.

## 다음 한 걸음

**PR ① 착수** — `/bts` 로 스키마·백엔드. 첫 산출물은 `## Jira 대조` 표이고,
J1~J13 은 이 ADR 에서 **출처·조회일 그대로 승계**한다(계약 §1-0 재사용).
