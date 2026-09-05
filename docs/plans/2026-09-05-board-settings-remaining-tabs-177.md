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

## 도메인 정리

**BC — `agile-planning`.** classify 는 `primary_bc: null` 을 냈다(migration 타입은 BC 무관 —
`classify-task.ts:545`). `BC_KEYWORDS` 정본으로 추출하면 「보드」·「추정」·「번다운」이 모두
`agile-planning` 항목이다.

**영향 엔티티** — `boards`(설정 4축 추가) · 신설 `board_non_working_dates` ·
`BurndownCalculator`(도메인 순수 함수 · 근무일 축) · `BoardCardResponse`/`BacklogIssueResponse`(표시 구성).

**★cross-BC 경계가 갭 B·E 의 범위를 정한다.** `BoardIssueView` 는 **shared-kernel**
(`BoardIssueLookupPort.kt:182~`)에 있고 커스텀 필드를 나르지 않는다. 지라 패리티대로 가면
shared-kernel + issue-tracking 을 함께 건드리게 되어 「한 PR = 한 BC」와 충돌한다 — 스펙 `C-2`.

**새 용어** — 없다. 「근무일」·「비근무일」·「카드 레이아웃」은 지라 용어의 직역이고
`glossary.md` 에 새로 넣을 개념이 아니다. Maxi 승인 필요 항목 0건.

**관련 ADR** — `docs/decisions/2026-07-02-fr-rp-01-burndown-burnup.md`.
**무효화하지 않는다.** 갭 D 는 그 ADR 이 정한 계산 모델을 바꾸는 것이 아니라 **축을 근무일로 좁힌다.**
미설정이면 현행 동작을 그대로 둔다(스펙 R6)므로 기존 결정과 충돌이 없다.

**기존 결정 충돌** — 없음. 단 `#452` 의 편차 `X3`(스윔레인·퀵필터를 설정 탭으로 안 옮긴다)는
그대로 승계하며, 이 PR 이 탭바를 도입해도 그 2탭은 만들지 않는다.


## 스펙

정본 — `docs/specs/2026-09-05-board-settings-remaining-tabs-177.md` (T3 이라 분리).

핵심 시나리오 3줄.
1. 보드 관리자가 카드에 표시할 필드를 **최대 3개** 고르면 보드와 백로그 카드가 함께 바뀐다(갭 B).
2. 스크럼 보드에서 시간 추적 방식을 고르면 번다운의 진행 계산이 그것을 따른다. 칸반에서는 잠긴다(갭 C).
3. 표준 근무일·비근무일을 등록하면 **번다운의 x축과 ideal 선이 근무일 기준으로 좁아진다**(갭 D) —
   지금은 `BurndownCalculator` 가 달력일 전부를 돌므로 이 시나리오가 **red 다**.

## Sanity Check

gap **5건**. 3건(권한 · 타임존 소비처 · 시각 검증 기준)은 스펙에서 **1회 보강**했고,
2건은 Maxi 결정이라 `/bts-plan` 을 막고 있다.

- **G1 권한** — 4탭 쓰기는 `BoardController` 의 기존 게이트를 그대로 탄다(R8·R9). 편차 `X8` 등재.
- **G2 타임존** — `board_timezone` 을 만들면서 읽는 곳을 안 썼다. worklog 일 귀속을 그 타임존으로
  바꾼다(R10). 검증은 **UTC 경계를 넘는 시각**의 worklog 로만 성립한다.
- **G5 시각 검증** — E2E 4항목 + 눈확인 4항목을 스펙에 명시.
- **✅ C-2 확정** — 갭 B 는 **지라 패리티(커스텀 필드)**. shared-kernel 포트 확장 + issue-tracking 값 공급.
- **✅ C-3 확정** — 갭 E 는 **지라와 동일**하게 보드 단위 Issue Detail View. 근거가 DC 뿐인 것은 편차 X10.
- **G6 (재조회로 교정)** — 확정 후 재조회에서 **초안 R3 이 틀렸음**을 찾았다. 카드 레이아웃 구성은
  **뷰마다 따로**다(J45 원문). 데이터 모델이 배열 한 칸 → `board_card_layout_fields(view_scope)` 로 바뀌었다.

## 🛑 게이트 1 요약에 실을 이탈

**X9 — 이 PR 은 「한 PR = 한 BC」를 깬다.** `shared-kernel` · `issue-tracking` · `agile-planning` ·
`apps/web` 넷을 건드린다. 그 사실을 제시한 뒤 「지라 클라우드와 동일한 스펙으로」가 Maxi 확정
(2026-09-05)이므로 그대로 간다. 완화책은 스펙 C-5 네 항목이고, 그중 ④ **판별식으로
`agile-planning` 의 `com.bts.issue` 직접 import 0건을 강제**하는 것만이 기계 방어선이다.

**X10 — 갭 E 의 근거가 DC 문서뿐이다.** Cloud 는 이 탭을 work type 레이아웃으로 옮겼다.
계약 §1 이 DC 를 조건부로 허용하므로 「Cloud 아님」을 행마다 표기했다.


## Plan

**공통 규율.** 모든 task 는 RED → GREEN → REFACTOR 한 사이클. 백엔드 검증은
`(cd backend && ./gradlew :modules:<bc>:test --tests '*<TestName>')` 형태로 부른다 —
worktree 루트에서 `backend/gradlew` 를 부르면 「does not contain a Gradle build」로 **시작조차 안 된다**(#450 실측).

### Task 1. 마이그레이션 골격 — `boards` 설정 3칸 + 비근무일 테이블

**메타**.
- agent: `db-engineer`
- files: [`backend/modules/agile-planning/src/main/resources/db/migration/agile-planning/V___board_settings_tabs.sql`, `backend/modules/agile-planning/src/test/kotlin/com/bts/agileplanning/migration/BoardSettingsMigrationTest.kt`]
- depends-on: []
- jira: [J36, J38, J39, J40]

**RED**. `boards` 에 `time_tracking`·`working_days`·`board_timezone` 이 없고 `board_non_working_dates`
테이블이 없음을 단언하는 스키마 테스트가 실패한다.

**GREEN**. `ALTER TABLE boards` 3칸 + `CREATE TABLE board_non_working_dates`.
★**V-번호는 이 task 착수 시점에 `git log origin/main -- '*/db/migration/*'` 로 최대값+1** 을 잡는다.
계획에 숫자를 박으면 `#450` 이 겪은 **동시 브랜치 V-번호 충돌**이 재발한다.
★`working_days` 는 **NULL 허용**이다 — `NOT NULL DEFAULT '{MON..FRI}'` 로 두면 기존 모든
스프린트의 번다운이 배포 순간 바뀐다(스펙 R6).

**REFACTOR**. 되돌리기 주석 — 「`DROP COLUMN` 3개 + `DROP TABLE` 로 완전 원복. 데이터 손실은 설정뿐」.

**검증**. `(cd backend && ./gradlew :modules:agile-planning:test --tests '*BoardSettingsMigrationTest')`

### Task 2. 카드 레이아웃 테이블 — 뷰별 + 상한 3을 DB 가 지킨다

**메타**.
- agent: `db-engineer`
- files: [`backend/modules/agile-planning/src/main/resources/db/migration/agile-planning/V___board_settings_tabs.sql`, `backend/modules/agile-planning/src/test/kotlin/com/bts/agileplanning/migration/BoardCardLayoutSchemaTest.kt`]
- depends-on: [1]
- jira: [J31, J45]

**RED**. `position = 3` 인 행 INSERT 가 **성공해 버린다**(테이블이 없으므로 테스트가 먼저 죽는다 →
테이블 생성 후 CHECK 없으면 통과해 버린다). 두 단언을 **한 쌍**으로 둔다 —
① `view_scope` 를 `BOARD`/`BACKLOG` 로 나눠 각각 3개까지 들어간다 ② 4번째(`position=3`)가 **제약 위반으로 죽는다**.

**GREEN**. `board_card_layout_fields` + `CHECK (position BETWEEN 0 AND 2)` + `CHECK (view_scope IN ('BOARD','BACKLOG'))`.

**REFACTOR**. 「서비스 검증은 사용자에게 이유를 주려고 있는 것이지 이 제약을 대신하지 않는다」를 주석으로.
(`#444` X1 경합이 이름 붙인 양식 — 두 관리자가 동시에 3개씩 저장하면 서비스 검사만으로는 6개가 된다.)

**검증**. `(cd backend && ./gradlew :modules:agile-planning:test --tests '*BoardCardLayoutSchemaTest')`

### Task 3. 상세 보기 필드 테이블 — 그룹 4종

**메타**.
- agent: `db-engineer`
- files: [`backend/modules/agile-planning/src/main/resources/db/migration/agile-planning/V___board_settings_tabs.sql`, `backend/modules/agile-planning/src/test/kotlin/com/bts/agileplanning/migration/BoardDetailViewSchemaTest.kt`]
- depends-on: [1]
- jira: [J47]

**RED**. `field_group = 'BOGUS'` INSERT 가 통과한다.

**GREEN**. `board_detail_view_fields` + `CHECK (field_group IN ('GENERAL','DATE','PEOPLE','LINKS'))`.

**REFACTOR**. 그룹 4종의 출처(J47 · DC 문서)를 주석에 남긴다 — 편차 X10 과 짝.

**검증**. `(cd backend && ./gradlew :modules:agile-planning:test --tests '*BoardDetailViewSchemaTest')`

### Task 4. 마이그레이션 멱등 + jOOQ 코드젠 반영

**메타**.
- agent: `db-engineer`
- files: [`backend/modules/agile-planning/src/main/resources/db/codegen/init_codegen.sql`, `backend/modules/agile-planning/src/test/kotlin/com/bts/agileplanning/migration/BoardSettingsIdempotencyTest.kt`]
- depends-on: [1, 2, 3]
- jira: []

**RED**. 같은 SQL 을 JDBC 로 재실행하면 죽는다.

**GREEN**. `ADD COLUMN IF NOT EXISTS` · `CREATE TABLE IF NOT EXISTS` · `ADD CONSTRAINT` 는
`DO $$ ... pg_constraint ... $$` 로 감싼다(★`ADD CONSTRAINT` 에는 `IF NOT EXISTS` 가 없다 — 부채 161 · #444 가 같은 자리에서 밟았다).
`init_codegen.sql` 도 함께 고친다 — **jOOQ 코드젠은 마이그레이션이 아니라 이 파일을 읽는다**(부채 54).

**REFACTOR**. 멱등 판정은 **「1차 재실행 후 ↔ 2차 재실행 후」** 로 잰다.
「적용 전 ↔ 1차 후」로 재면 앞선 테스트가 만든 행을 1차가 **정당하게** 백필하는 것을 결함으로 오판한다(#444 실측).

**검증**. `(cd backend && ./gradlew :modules:agile-planning:test --tests '*BoardSettingsIdempotencyTest')`

### Task 5. shared-kernel 포트에 커스텀 필드를 싣는다

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/shared-kernel/src/main/kotlin/com/bts/shared/board/BoardIssueLookupPort.kt`, `backend/modules/shared-kernel/src/test/kotlin/com/bts/shared/board/BoardIssueViewContractTest.kt`]
- depends-on: []
- jira: [J32]

**RED**. `BoardIssueView` 에 `customFields` 가 없어 컴파일이 안 된다.

**GREEN**. `val customFields: Map<String, Any?> = emptyMap()` **기본값과 함께** 추가한다 —
기존 소비자 시그니처를 깨지 않는 것이 스펙 C-5 ②다.

**REFACTOR**. KDoc — 「소유는 issue-tracking BC. agile-planning 은 미러 노출만 한다」
(`rank` 필드가 이미 쓴 문구를 그대로 따른다).

**검증**. `(cd backend && ./gradlew :modules:shared-kernel:test --tests '*BoardIssueViewContractTest')`

### Task 6. issue-tracking 이 커스텀 필드 값을 채운다

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/repository/IssueRepository.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/repository/BoardIssueLookupCustomFieldsTest.kt`]
- depends-on: [5]
- jira: [J32]

**RED**. 커스텀 필드가 있는 이슈를 보드 조회로 읽으면 `customFields` 가 비어 있다.

**GREEN**. `FR-IS-10` 의 `customFields` 원천을 `BoardIssueView` 에 매핑한다.

**REFACTOR**. N+1 확인 — 카드 N건에 대해 커스텀 필드 조회가 **1회**임을 쿼리 카운트로 잰다.

**검증**. `(cd backend && ./gradlew :modules:issue-tracking:test --tests '*BoardIssueLookupCustomFieldsTest')`

### Task 7. 보드 설정 리포지터리 분리 — `BoardRepository.kt` 를 키우지 않는다

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/agile-planning/src/main/kotlin/com/bts/agileplanning/repository/BoardSettingsRepository.kt`, `backend/modules/agile-planning/src/test/kotlin/com/bts/agileplanning/repository/BoardSettingsRepositoryTest.kt`]
- depends-on: [1, 2, 3]
- jira: []

**RED**. 설정 4축을 읽고 쓰는 리포지터리가 없다.

**GREEN**. 카드 레이아웃(뷰별) · 시간 추적 · 근무일 · 상세 필드의 CRUD.

**REFACTOR**. `BoardRepository.kt` **줄수 무증가**를 확인한다(부채 157 · 스펙 C-1).
`#444` 가 `BoardColumnStateRepository` 로 뺀 선례와 같은 이유.

**검증**. `(cd backend && ./gradlew :modules:agile-planning:test --tests '*BoardSettingsRepositoryTest')`

### Task 8. 카드 레이아웃 PATCH — 뷰별 저장 + 400

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/agile-planning/src/main/kotlin/com/bts/agileplanning/application/BoardSettingsService.kt`, `backend/modules/agile-planning/src/main/kotlin/com/bts/agileplanning/web/BoardController.kt`, `backend/modules/agile-planning/src/test/kotlin/com/bts/agileplanning/web/BoardCardLayoutApiTest.kt`]
- depends-on: [7]
- jira: [J31, J45]

**RED**. ① `BOARD` 와 `BACKLOG` 에 **다른** 필드 집합을 저장하면 둘이 같아진다(구성 공유 구현이 통과하는 것을 막는다).
② 한 뷰에 4개를 보내면 200 이 온다.

**GREEN**. 뷰별 저장 + 4개 이상 400 + 미지원 필드 키 400 + 칸반에 `BACKLOG` 400.

**REFACTOR**. 권한은 **기존 게이트를 그대로** 탄다 —
`permissionResolver.hasPermission(actor, IssuePermission.SOFT_DELETE, IssueScope.Project(projectKey))`.
존재 확인 ↔ 권한 확인 **순서를 기존 경로와 같게** 유지한다(403/404 의미 뒤집힘 · 로컬은 항상 허용이라 안 보인다).

**검증**. `(cd backend && ./gradlew :modules:agile-planning:test --tests '*BoardCardLayoutApiTest')`

### Task 9. 추정 탭 PATCH — 칸반은 409

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/agile-planning/src/main/kotlin/com/bts/agileplanning/application/BoardSettingsService.kt`, `backend/modules/agile-planning/src/test/kotlin/com/bts/agileplanning/web/BoardEstimationApiTest.kt`]
- depends-on: [7]
- jira: [J36, J37]

**RED**. 칸반 보드에 `timeTracking` 을 보내면 200 이 온다.

**GREEN**. 스크럼만 허용 · 칸반은 **409**(J37). ★**404 가 아니다** — 보드는 있고 조작이 막힌 것이다.

**REFACTOR**. 보드 종류를 스크럼→칸반→스크럼으로 왕복시켜도 값이 **살아 있음**을 단언(스펙 E6).
읽는 쪽이 `board_type` 을 보고 무시하는 구조여야 성립한다.

**검증**. `(cd backend && ./gradlew :modules:agile-planning:test --tests '*BoardEstimationApiTest')`

### Task 10. 작업일 저장 — 근무일 0개는 400

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/agile-planning/src/main/kotlin/com/bts/agileplanning/application/BoardSettingsService.kt`, `backend/modules/agile-planning/src/test/kotlin/com/bts/agileplanning/web/BoardWorkingDaysApiTest.kt`]
- depends-on: [7]
- jira: [J38, J39, J40]

**RED**. `standardDays: []` 가 200 으로 저장된다.

**GREEN**. 0개 → 400(스펙 E1 · ideal 선 0 나눗셈 차단) · IANA 아닌 타임존 → 400 ·
스프린트 기간 밖 비근무일은 **저장 허용**(E8).

**REFACTOR**. 「미설정(NULL) = 달력일 전부」를 KDoc 에 못박는다.

**검증**. `(cd backend && ./gradlew :modules:agile-planning:test --tests '*BoardWorkingDaysApiTest')`

### Task 11. ★번다운이 근무일만 센다 — 이 PR 의 핵심 red

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/agile-planning/src/main/kotlin/com/bts/agileplanning/domain/burndown/BurndownCalculator.kt`, `backend/modules/agile-planning/src/test/kotlin/com/bts/agileplanning/domain/burndown/BurndownWorkingDaysTest.kt`]
- depends-on: [10]
- jira: [J41]

**RED**. 2주 스프린트(달력 14일 · 주말 4일)에 월~금 근무일을 설정해도 point 수가 **14** 다.
★**대조군을 한 쌍으로 둔다** — 미설정 스프린트의 point 수가 **달력일 수와 같다**는 단언을 함께 둔다.
뒤엣것만 두면 「전부 근무일로 치는」 구현도 통과한다(스펙 완료 기준 5).

**GREEN**. `calculate` 가 근무일 집합을 받아 x축을 좁히고 `computeIdealSeconds` 의 분모도 근무일 수로 바꾼다.
근무일 미지정이면 **현행 경로 그대로**.

**REFACTOR**. 스프린트 전 기간이 비근무일이면 `totalDays == 0` 경로로 합류시킨다(E2 · 500 방지).
계산은 **도메인 순수 함수**에 남긴다 — 서비스로 올리면 테스트가 Testcontainers 를 탄다(NFR N2).

**검증**. `(cd backend && ./gradlew :modules:agile-planning:test --tests '*BurndownWorkingDaysTest')`

### Task 12. worklog 일 귀속을 보드 타임존으로

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/agile-planning/src/main/kotlin/com/bts/agileplanning/application/SprintBurndownService.kt`, `backend/modules/agile-planning/src/test/kotlin/com/bts/agileplanning/application/BurndownTimezoneTest.kt`]
- depends-on: [10]
- jira: [J40]

**RED**. `23:30Z` 에 적은 worklog 가 타임존을 `Asia/Seoul` 로 바꿔도 **같은 날짜 칸**에 있다.
★**시각이 UTC 경계를 넘어야 한다.** 낮 시각으로 쓰면 두 타임존에서 같은 날이라
**판정을 지워도 통과하는 공허한 테스트**가 된다(스펙 완료 기준 11).

**GREEN**. `aggregateByUtcDate` 를 보드 타임존 기준으로 바꾼다. 미설정이면 UTC 유지.

**REFACTOR**. 함수 이름이 `...ByUtcDate` 로 남으면 거짓말이 된다 — `aggregateByBoardDate` 로 고친다.

**검증**. `(cd backend && ./gradlew :modules:agile-planning:test --tests '*BurndownTimezoneTest')`

### Task 13. 상세 보기 필드 PATCH + 조회

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/agile-planning/src/main/kotlin/com/bts/agileplanning/application/BoardSettingsService.kt`, `backend/modules/agile-planning/src/test/kotlin/com/bts/agileplanning/web/BoardDetailViewApiTest.kt`]
- depends-on: [7]
- jira: [J46, J47, J48, J49]

**RED**. 그룹 4종에 필드를 넣고 순서를 바꿔도 응답이 순서를 안 지킨다.

**GREEN**. 그룹별 저장 + `position` 순 반환 + 미지원 그룹 400.

**REFACTOR**. 권한은 Task 8 과 같은 게이트. 편차 **X8**(보드 단위 권한 부재 → 프로젝트 권한 갈음)을 KDoc 에.

**검증**. `(cd backend && ./gradlew :modules:agile-planning:test --tests '*BoardDetailViewApiTest')`

### Task 14. BC 경계 판별식 — 직접 import 0건

**메타**.
- agent: `backend-engineer`
- files: [`scripts/workflow/bc-isolation-agile-planning.test.ts`]
- depends-on: [6]
- jira: []

**RED**. 판별식이 없다. 일부러 `agile-planning` 에 `import com.bts.issue.…` 한 줄을 넣어 **red 1회를 눈으로 본다**
(가드는 끊어 봐야 산다 — CLAUDE.md 함정).

**GREEN**. `agile-planning/src/main` 전수에 `com.bts.issue` import 가 0건임을 잰다.
스펙 C-5 ④ — X9(여러 BC 를 건드린다)의 **유일한 기계 방어선**이다.

**REFACTOR**. 판별식이 무엇을 **못 보는지** 적는다 — 리플렉션·문자열 경유 의존은 못 잡는다.

**검증**. `node --experimental-strip-types --test scripts/workflow/bc-isolation-agile-planning.test.ts`

### Task 15. 설정 화면에 탭바를 도입한다

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/routes/projects.$projectKey.board.settings.tsx`, `apps/web/src/components/board/settings/SettingsTabs.tsx`, `apps/web/src/i18n/board-labels.ts`, `apps/web/src/routes/projects.$projectKey.board.settings.test.tsx`]
- depends-on: []
- jira: [J22]

**RED**(동반 테스트). 탭바가 없어 5탭 렌더 단언이 실패한다.

**GREEN**. 탭 5종(`컬럼` + 4탭). ★`#452` 가 「누를 수 있는데 아무 일도 안 일어나는」 탭을 금지했으므로
**비활성 골격을 만들지 않는다** — 이 PR 이 4탭을 전부 동작시킨다.

**REFACTOR**. `settings.tsx:89` 의 「두 번째 탭을 만드는 PR 이 탭바를 도입한다」 주석을 **소진 처리**한다.

**검증**.
- 기존 E2E: `apps/web/e2e/board-settings.spec.ts`(3건 · `#452`) — **탭바 도입으로 깨지지 않는지가 먼저다**
- 눈확인: 탭바 5탭 렌더 — 라이트/다크

### Task 16. 카드 레이아웃 탭 UI — 뷰별 3개

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/board/settings/CardLayoutPanel.tsx`, `apps/web/src/api/board-settings.ts`, `apps/web/src/components/board/settings/CardLayoutPanel.test.tsx`]
- depends-on: [8, 15]
- jira: [J31, J45]

**RED**(동반 테스트). 4번째 필드를 고를 수 있다. 그리고 스크럼에서 뷰 전환이 없다.

**GREEN**. 뷰 토글(`보드`/`백로그` · 스크럼만) + 3개 상한 + 커스텀 필드 후보 표시.

**REFACTOR**. 칸반은 뷰 토글을 **그리지 않는다**(백로그 스코프 자체가 없다).

**검증**.
- 기존 E2E: `apps/web/e2e/board-settings.spec.ts`
- 눈확인: 3개 채운 뒤 4번째가 비활성 — 라이트/다크

### Task 17. 추정 탭 UI — 칸반 잠금

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/board/settings/EstimationPanel.tsx`, `apps/web/src/components/board/settings/EstimationPanel.test.tsx`]
- depends-on: [9, 15]
- jira: [J36, J37]

**RED**(동반 테스트). 칸반 보드에서 시간 추적을 바꿀 수 있다.

**GREEN**. 스크럼만 편집 가능 · 칸반은 사유를 보이며 잠근다.

**REFACTOR**. 잠금 사유 문구를 `board-labels.ts` 로 — 화면에 문자열을 박지 않는다.

**검증**. 눈확인: 칸반 보드에서 잠금 표시 — 라이트/다크

### Task 18. 작업일 탭 UI

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/board/settings/WorkingDaysPanel.tsx`, `apps/web/src/components/board/settings/WorkingDaysPanel.test.tsx`]
- depends-on: [10, 15]
- jira: [J38, J39, J40]

**RED**(동반 테스트). 근무일을 0개로 만들어 저장할 수 있다.

**GREEN**. 요일 토글 + 비근무일 날짜 추가/삭제 + 타임존 선택. 0개면 저장 비활성.

**REFACTOR**. 「미설정 = 현행 유지」를 화면에서 읽히게 한다 — 빈 상태가 「전부 근무일」로 오해되면 안 된다.

**검증**. 눈확인: 비근무일 추가 후 목록 — 라이트/다크

### Task 19. 상세 보기 탭 UI — 그룹 4종 + 드래그

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/board/settings/DetailViewPanel.tsx`, `apps/web/src/components/board/settings/DetailViewPanel.test.tsx`]
- depends-on: [13, 15]
- jira: [J46, J47, J48]

**RED**(동반 테스트). 드래그로 순서를 바꿔도 저장되지 않는다.

**GREEN**. 그룹 4종 · 드롭다운 추가 · 삭제 · 드래그 정렬.
★`#452` 의 `use-column-settings-drag.ts` **잠금 패턴을 재사용**한다 — 연속 드롭 lost update 방어
(그 PR 이 BLOCKER B2 로 닫은 자리다).

**REFACTOR**. 새 드래그 훅을 만들지 않는다(계약 §4 재사용 자산).

**검증**. 눈확인: 드래그 중 순서 표시 — 라이트/다크

### Task 20. 카드가 구성을 읽는다 — 보드 + 백로그

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/board/BoardCard.tsx`, `apps/web/src/components/backlog/BacklogRow.tsx`, `apps/web/src/components/board/BoardCard.test.tsx`]
- depends-on: [16]
- jira: [J31, J32, J45]

**RED**(동반 테스트). 구성을 바꿔도 카드가 그대로다. 그리고 **보드와 백로그에 다른 구성**을 주면 같아진다.

**GREEN**. 뷰 스코프에 맞는 구성을 읽어 3층 순서로 그린다(J32) — 요약 → 추가 필드 → 상세.
필드가 그 이슈에 없으면 **그 카드에서만 생략**한다(E4 · 빈 칸을 그리지 않는다).

**REFACTOR**. 요약은 토글 대상이 아님을 타입으로 막는다.

**검증**.
- 기존 E2E: `apps/web/e2e/board-settings.spec.ts` · 보드/백로그 spec (계약 §5 사전 grep 으로 확정)
- 눈확인: 커스텀 필드가 카드에 뜬다 — 라이트/다크

### Task 21. 이슈 상세가 구성을 읽는다 — 모달 + 사이드패널 양쪽

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/issue/IssueMetaPanel.tsx`, `apps/web/src/routes/issues.$key.tsx`, `apps/web/src/components/issue/IssueMetaPanel.test.tsx`]
- depends-on: [19]
- jira: [J42, J46]

**RED**(동반 테스트). 구성이 모달에만 반영되고 사이드패널은 그대로다.

**GREEN**. 두 표현이 **같은 구성**을 읽는다. `#455`(모달↔사이드패널 토글)가 방금 머지됐으므로
한쪽만 반영하면 「같은 이슈가 열기 방식에 따라 다르게 보인다」가 된다(스펙 R7c).

**REFACTOR**. 구성 읽기를 훅 하나로 모아 두 표현이 갈리지 않게 한다.

**검증**. 눈확인: 모달과 사이드패널에서 같은 필드 집합 — 라이트/다크

### Task 22. E2E — 탭바 + 4탭 한 바퀴

**메타**.
- agent: `qa-engineer`
- files: [`apps/web/e2e/board-settings.spec.ts`]
- depends-on: [16, 17, 18, 19, 20, 21]
- jira: [J22, J31, J36, J38, J46]

**RED**. 탭 전환 시나리오가 없다.

**GREEN**. 탭바 5탭 전환 → 카드 레이아웃 뷰별 저장 → 추정 칸반 잠금 → 작업일 등록 → 상세 필드 드래그.
★보드 헤더 `⋯` 와 사이드바 보드 `⋯` 는 접근성 이름이 같다(캠페인 R10) —
`apps/web/e2e/fixtures/board-helpers.ts` 의 **컨테이너 스코프 헬퍼를 그대로 쓴다**(`#450`).

**REFACTOR**. 기존 3건이 탭바 도입으로 깨지지 않았는지 함께 확인한다.

**검증**. `(cd apps/web && node_modules/.bin/playwright test e2e/board-settings.spec.ts)`

## Plan 메타

- **task 수**: 22 · **예상 wave**: 5
  (A 마이그레이션 1~4 · B 포트 5~6 · C 백엔드 7~14 · D 프론트 15~21 · E E2E 22)
- **구현 규율**: 백엔드는 정식 TDD red-first. 프론트는 ui 시각 검증 트랙(RED = 동반 테스트).
- **추가 검증**: `tsc --noEmit -p tsconfig.app.json` · `eslint src` · `vitest run` ·
  `playwright test e2e/board-settings.spec.ts` · `ktlint` · `detekt` ·
  `node --experimental-strip-types --test 'scripts/**/*.test.ts'` · `build-doc-index.mjs --check`
- **Jira 매핑**:
  J8→T8·T13(권한 게이트 승계) · J22→T15·T22 · J31→T2·T8·T16·T20 · J32→T5·T6·T20 ·
  J36→T1·T9·T17 · J37→T9·T17 · J38→T1·T10·T18 · J39→T1·T10·T18 · J40→T1·T10·T12·T18 ·
  J41→T11 · J42→T21 · J45→T2·T8·T16·T20 · J46→T13·T19·T21 · J47→T3·T13·T19 · J48→T13·T19 ·
  J49→T13 · **J33 범위 밖** (편차 X6 — 컬럼 진입 시각을 아무도 기록하지 않아 별도 이력 테이블이 필요하다) ·
  **J44 범위 밖** (work type 연관·hidden 판정은 issue-tracking 의 필드 스킴 영역이고, 이 PR 은
  보드 단위 구성만 낸다 — 편차 X10 과 같은 경계)



## 리뷰 결과 (← /bts-review-plan 채움)
