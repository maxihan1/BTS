# 보드 설정 잔여 4탭 — 카드 레이아웃 · 추정 · 작업일 · 상세 보기 (부채 177)

> 티어: T3
> slug: board-settings-remaining-tabs-177
> type: migration
> agent: db-engineer
> 생성: 2026-09-05

## Brief

**사용자 원문.** 「보드 관련 다음 남은 작업」에서 부채 177 잔여 4탭을 골랐고,
「하나의 PR 로 할 수 없어?」 → 「4탭 모두 적용이 필요한 것들이잖아?」로 **4탭 한 PR** 을 확정했다.

**classify 결과.** `slug=board-settings-remaining-tabs-177` · `type=migration` ·
`agent=db-engineer` · `tier=T3` · `primary_bc=null`(migration 은 BC 무관 —
`classify-task.ts:545`).

**티어 근거.** `--tier T3` 를 명시해 넘겼다. `classify-task.ts:582` 가
`input.tier ?? DEFAULT_TIER` 라 티어를 type 에서 유도하지 않으므로 호출자가 선언한다.
네 탭 모두 `boards` 에 대응 칸이 없어 **신규 스키마가 확정**이고, 티어 표에서 MIGRATION 은 T3 다.

## 무엇을 만드는가

지라 Board settings 7탭 중 BTS 에 **아직 없는 4탭**을 만든다.

| 탭 | 갭 | 백엔드 현황(2026-09-05 실측) |
|---|---|---|
| Card layout | B | 없음. `BoardCard.tsx` 가 고정 필드를 그린다 |
| Estimation and tracking | C | **이슈 층에 실데이터 있음** — `WorklogService` · `remainingEstimateSeconds` · 백로그/보드 응답의 추정 필드. 보드 층 설정만 신규 |
| Working days | D | 없음 |
| Issue detail view | E | 없음 |

`boards` 현재 컬럼 — `id · project_key · name · created_at · updated_at · deleted_at`
(`V500__boards.sql`) + `swimlane_field` + `board_type`. **네 탭 어디에도 대응 칸이 없다.**

**Swimlanes · Quick filters 2탭은 범위가 아니다.** #452 가 편차 `X3` 로
「보드 화면 인라인 유지 · 설정 탭으로 수렴시키지 않는다」를 Maxi 확정으로 등재했다.

## FR

**기존** — `FR-BD-01` · `FR-BD-03` · `FR-BD-04`.

**신규 FR 필요 여부는 스펙에서 판단한다.** #452 계획이 남긴 쟁점을 그대로 잇는다 —
「카드 레이아웃 갭 B 가 유일한 후보이고, `FR-BD-03` 범위 확장으로 흡수 가능한지가 쟁점」.
갭 C·D·E 는 그 판단을 한 번 더 해야 한다(넷을 한 PR 로 묶었으므로 네 번이 아니라 한 자리에서).

## 착수 시점에 이미 아는 것

- **탭바가 아직 없다.** `settings.tsx:89` — 「탭이 하나라 탭바 자체를 안 만든다 —
  **두 번째 탭을 만드는 PR 이 탭바를 도입한다**」. 그 PR 이 이것이다.
- **비활성 골격을 미리 그리지 않는다는 결정이 있다**(`board-labels.ts:303`, Maxi 확정 2026-09-04) —
  「누를 수 있는데 아무 일도 안 일어나는」 화면은 장부가 경계한 「도달할 UI 가 없는 기능」의 거울상.
  이 PR 은 네 탭을 **전부 동작하게** 만들므로 그 결정과 충돌하지 않는다.
- **J 번호가 하나도 없다.** 네 탭 모두 `docs/design/jira-parity-contract.md` 에 항목이 없어
  **지라 실물 조회부터** 필요하다. 이것이 스펙 작업의 대부분이다.
- **마이그레이션은 1개로 묶는다.** 네 탭 설정 칸을 한 V-번호에 몰아 V-번호 동시 충돌
  함정([[migration-vnumber-concurrent-branch-collision]])을 네 번이 아니라 한 번만 상대한다.
  이것이 4탭을 한 PR 로 묶는 유일한 기술적 이득이다.

## 알고 감수하는 것 (Maxi 확정 2026-09-05)

4탭 한 PR 의 비용을 제시했고 그대로 진행하기로 했다.

1. **게이트 2 리뷰 표면이 4배.** #452 는 **1탭**인데도 CONCERNS 9건이 나왔다.
2. **main 이 빠르다.** 최근 하루에 `#449`~`#456` 이 들어왔다. 며칠짜리 PR 은 리베이스를 반복한다.
3. **되돌리기 단위가 사라진다.** 한 탭이 잘못되면 4탭이 통째로 롤백된다.

## Jira 대조

계약 §1 절차. **Step 0(재사용)을 먼저 돌렸다** —
`grep -rln "## Jira 대조" docs/specs/ docs/plans/ | xargs grep -ln "<표면>"` 로 62개 문서를 훑었다.
`fr-ux-14-b2-card-fields` 스펙은 **`## Jira 대조` 를 생략**했다고 스스로 적어 승계할 행이 없었고,
`#452` 스펙에서 화면 진입·권한 2행만 승계된다. 4탭의 **조작**은 전부 신규 조회다.

### 승계 (재조회 안 함 · 계약 §1-0)

| # | 원문 인용 | 출처 · 조회일 |
|---|---|---|
| **J8** | *"you must be either: a **space administrator** for the location of the board [or] a **board administrator** for the board itself"* | [Configure a company-managed board](https://support.atlassian.com/jira-software-cloud/docs/configure-a-company-managed-board/) · Cloud · 2026-09-03 |
| **J22** | 진입 — *"On the **Board settings** screen, select the desired tab (**Columns**, **Swimlanes**, etc)."* | 동일 · Cloud · 2026-09-04 |

### 이번에 새로 조회 (2026-09-05 · 4탭이 전부 신규 표면이다)

**Card layout (갭 B)**

| # | 원문 인용 | 출처 · 조회일 |
|---|---|---|
| **J30** | 진입 — *"Next to your board's name in the sidebar, select **More actions** (•••), then **Board settings**. Expand **Layout** in the sidebar, then select **Card layout**."* | [Customize cards](https://support.atlassian.com/jira-software-cloud/docs/customize-cards/) · Cloud · 2026-09-05 |
| **J31** | **필드 상한 3개** — *"You can configure cards on a board to display up to three additional fields."* | 동일 · Cloud · 2026-09-05 |
| **J32** | **카드 3층 구조** — *"Work item cards have three layers of information that are stacked on top of each other following this pattern: 1. The work item summary is always at the top on the board and backlog. 2. Any custom fields added to the card are next. 3. Then details about the work item, including work type, priority, assignee, and estimate."* | 동일 · Cloud · 2026-09-05 |
| **J33** | **Days in column** — *"You can also enable **Days in column** to visually indicate how long a work item's in a column. This helps identify slow moving work."* | 동일 · Cloud · 2026-09-05 |
| **J34** | **카드 색** — *"You can base your card colors on work types, priorities, assignees, or JQL."* | 동일 · Cloud · 2026-09-05 |

**Estimation and tracking (갭 C)**

| # | 원문 인용 | 출처 · 조회일 |
|---|---|---|
| **J35** | 진입 — *"Navigate to your board, select **More actions** (•••) next to the board name, then choose **Board settings** and select **Estimation**."* | [Configure estimation and tracking](https://support.atlassian.com/jira-software-cloud/docs/configure-estimation-and-tracking/) · Cloud · 2026-09-05 |
| **J36** | **시간 추적 2종** — `None` 은 추정 방식으로 진행을 재고, `Remaining estimate and time spent` 는 *"tracks progress by subtracting the value from the **Time spent** field from the original estimate"* | 동일 · Cloud · 2026-09-05 |
| **J37** | **적용 범위 제약** — *"This setting can only be changed for company-managed scrum teams."* | 동일 · Cloud · 2026-09-05 |

**Working days (갭 D)**

| # | 원문 인용 | 출처 · 조회일 |
|---|---|---|
| **J38** | **표준 근무일** — *"Select the days your team usually work under **Standard working days**."* | [Configure working days](https://support.atlassian.com/jira-software-cloud/docs/configure-working-days/) · Cloud · 2026-09-05 |
| **J39** | **비근무일** — *"Specify holidays or one-off dates your team won't be working, select a date using the date picker under **Non-working days**, then select **Add date**."* | 동일 · Cloud · 2026-09-05 |
| **J40** | **타임존** — *"Change your board's timezone, select a **Region**, then **Timezone** from the dropdowns."* | 동일 · Cloud · 2026-09-05 |
| **J41** | **영향 범위** — *"Working days are reflected in these reports and gadgets: Burndown Chart, Sprint Report, Epic Report, Version Report, Control Chart"* | 동일 · Cloud · 2026-09-05 |

**Issue detail view (갭 E)**

| # | 원문 인용 | 출처 · 조회일 |
|---|---|---|
| **J42** | 목적 — *"customize the work item to show more fields, hide fields, and rearrange the field layout."* | [Configure the work item details](https://support.atlassian.com/jira-software-cloud/docs/configure-the-issue-detail-view/) · Cloud · 2026-09-05 |
| **J43** | **구성 가능 필드**(문서의 표 나열 — 인용 아님) — Summary · Estimate · Status · Priority · Component · Labels · Affected versions · Fix versions · Parent · Reporter · Assignee · Date created · Date updated · Work item links · Description · Comments · Attachments · Subtasks | 동일 · Cloud · 2026-09-05 |
| **J44** | **필드 노출 전제** — *"Fields will only appear on a work item if they have been associated with the relevant work type, and are not _hidden_."* | 동일 · Cloud · 2026-09-05 |

### 착수 시점 관찰 (편차 확정은 /bts-spec 에서)

- **J37 이 갭 C 의 범위를 정할 수 있다.** 지라는 이 설정을 **스크럼 보드에만** 연다.
  BTS 는 `board_type` 이 이미 있으므로(#421) 같은 제약을 그대로 태울지, 칸반에도 열지가 쟁점이다.
- **J41 이 갭 D 의 가치를 정한다.** 근무일 설정이 값을 내는 곳이 전부 번다운·스프린트 리포트류인데
  BTS 에 그 리포트가 없다. 「설정은 되는데 아무 데도 안 쓰이는 칸」이 되면
  `board-labels.ts:303` 이 경계한 「누를 수 있는데 아무 일도 안 일어나는」 화면의 재판이다.
- **J42·J44 는 보드 설정이 아니라 스페이스/스킴 설정을 가리킨다.** 지라의 이 탭은 보드가 아니라
  work type 레이아웃을 건드리고, 조회 결과도 `Settings > Screens` 경로를 지목했다.
  갭 E 를 보드 단위 설정으로 만들면 **지라와 다른 모델**이 되므로 편차 등재 대상이다.
- **J31 상한 3개는 BTS 에 커스텀 필드가 이미 있다는 전제와 맞물린다**(`FR-IS-10` 키 단위 병합 패치).

## 도메인 정리 (← /bts-spec §1 채움)

## 스펙 (← /bts-spec §2 채움)

정본은 `docs/specs/2026-09-05-board-settings-remaining-tabs-177.md` (T3 은 분리).

## Sanity Check (← /bts-spec §3 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
