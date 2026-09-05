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

계약 §1 절차. **B2 로 재작성했다** — 초판은 §1-0 재사용을 놓쳐 4탭을 통째로 재조회했다.

### ★재사용 실패와 그 수복 (2026-09-05)

초판의 §1-0 grep 은 계약이 적어 둔 `docs/specs/ docs/plans/` 범위였고, **조회 결과의 실제
보관처인 `TODOS.md:1499~1516`** 을 못 봤다. 그 블록은 `## Jira 대조` 가 아니라
`### 지라 근거` 헤딩이라 **범위를 넓혀도 헤딩 하나만 보면 여전히 못 찾는다.**

**대가.** J18 이 「백로그와 활성 스프린트가 서로 다른 설정을 갖는다」를 2026-09-03 에 이미
기록해 뒀는데 못 보고 초안 R3 을 「구성 공유」로 잘못 썼다가 재조회로 뒤집었다(G6).

**근본 수복.** 계약 §1-0 의 명령을 두 헤딩 + `TODOS.md` 를 함께 보도록 고쳤고,
`bts-spec/SKILL.md` 가 들고 있던 **낡은 사본을 없애고 정본 포인터로 바꿨다**
(사본이 범위 확장을 따라오지 않는 것이 이 저장소가 이름 붙인 지배 결함 양식이다).

### 승계 — 재조회 안 함 (계약 §1-0)

`TODOS.md:1499~1516` (부채 177 등재 세션 · 2026-09-03 · Cloud) + `#452` 스펙.

| # | 내용 | 원문 인용 | 출처 |
|---|---|---|---|
| **J8** | 권한 | *"you must be either: a **space administrator** for the location of the board [or] a **board administrator** for the board itself"* | [Configure a company-managed board](https://support.atlassian.com/jira-software-cloud/docs/configure-a-company-managed-board/) |
| **J12** | Card layout 탭 | *"Customize the layout, colors, and fields on the cards on your board"* | 동일 |
| **J13** | Estimation 탭 | *"Configure how you estimate work and track time"* | 동일 |
| **J14** | Working days 탭 | *"Configure the timezone, and your team's standard working and non-working days"* | 동일 |
| **J15** | Issue detail view 탭 | *"Customize the work item to show more fields, hide fields, and rearrange"* | 동일 |
| **J16** | 카드 레이아웃 경로 | *"…select **Board settings**. Expand **Layout** in the sidebar, then select **Card layout**."* | [Customize cards](https://support.atlassian.com/jira-software-cloud/docs/customize-cards/) |
| **J17** | **추가 필드 상한 3개** | *"You can configure cards on a board to display up to three additional fields."* | 동일 |
| **J18** | **뷰별 구성** | *"The fields can be different for the backlog and Active sprints, if you are using a Scrum board."* | 동일 |
| **J19** | 카드 3층 구조 | *"The work item summary is always at the top on the board and backlog. Any custom fields added to the card are next. Then details about the work item, including work type, priority, assignee, and estimate."* | 동일 |
| **J20** | Days in column | *"You can also enable **Days in column** to visually indicate how long a work item's in a column."* | 동일 |
| **J21** | 카드 색 | *"You can base your card colors on work types, priorities, assignees, or JQL."* | 동일 |
| **J22** | 탭 진입 | *"On the **Board settings** screen, select the desired tab (**Columns**, **Swimlanes**, etc)."* | 동일 · `#452` |

### 이번에 새로 조회 (2026-09-05 · 승계로 부족한 것만)

승계된 J12~J15 는 **탭 한 줄 설명**뿐이라 조작의 형태를 알 수 없었다. 그 부분만 조회했다.

| # | 갭 | 원문 인용 | 출처 · 조회일 |
|---|---|---|---|
| **J35** | C | 진입 — *"Navigate to your board, select **More actions** (•••) next to the board name, then choose **Board settings** and select **Estimation**."* | [Configure estimation and tracking](https://support.atlassian.com/jira-software-cloud/docs/configure-estimation-and-tracking/) · Cloud · 2026-09-05 |
| **J36** | C | **시간 추적 2종** — `None` 은 추정 방식으로 진행을 재고, `Remaining estimate and time spent` 는 *"tracks progress by subtracting the value from the **Time spent** field from the original estimate"* | 동일 · Cloud · 2026-09-05 |
| **J37** | C | **적용 범위 제약** — *"This setting can only be changed for company-managed scrum teams."* | 동일 · Cloud · 2026-09-05 |
| **J38** | D | **표준 근무일** — *"Select the days your team usually work under **Standard working days**."* | [Configure working days](https://support.atlassian.com/jira-software-cloud/docs/configure-working-days/) · Cloud · 2026-09-05 |
| **J39** | D | **비근무일** — *"Specify holidays or one-off dates your team won't be working, select a date using the date picker under **Non-working days**, then select **Add date**."* | 동일 · Cloud · 2026-09-05 |
| **J40** | D | **타임존** — *"Change your board's timezone, select a **Region**, then **Timezone** from the dropdowns."* | 동일 · Cloud · 2026-09-05 |
| **J41** | D | **영향 범위** — *"Working days are reflected in these reports and gadgets: Burndown Chart, Sprint Report, Epic Report, Version Report, Control Chart"* | 동일 · Cloud · 2026-09-05 |
| **J43** | E | **구성 가능 필드**(문서의 표 나열 — 인용 아님) — Summary · Estimate · Status · Priority · Component · Labels · Affected versions · Fix versions · Parent · Reporter · Assignee · Date created · Date updated · Work item links · Description · Comments · Attachments · Subtasks | [Configure the work item details](https://support.atlassian.com/jira-software-cloud/docs/configure-the-issue-detail-view/) · Cloud · 2026-09-05 |
| **J44** | E | **필드 노출 전제** — *"Fields will only appear on a work item if they have been associated with the relevant work type, and are not _hidden_."* | 동일 · Cloud · 2026-09-05 |
| **J46** | E | **진입** — *"Go to the desired board and select **Board** > **Configure**. In the left-side menu, select **Issue Detail View**."* | [Configuring the issue view](https://confluence.atlassian.com/jirasoftwareserver/configuring-the-issue-view-938845334.html) · **DC (Cloud 아님)** · 2026-09-05 |
| **J47** | E | **필드 그룹 4종** — *"different groups of fields: General fields, Date fields, People, and Links."* | 동일 · **DC (Cloud 아님)** · 2026-09-05 |
| **J48** | E | **조작** — *"To add a new field, select the field from one of the dropdown menus, and then select **Add.**"* · *"If you want to hide a field from the issue details view, select **Delete**."* · 순서는 *"drag and drop the field up or down in the list."* | 동일 · **DC (Cloud 아님)** · 2026-09-05 |
| **J49** | E | **권한** — *"as a user with the **Jira admin** or a **board admin** permissions."* | 동일 · **DC (Cloud 아님)** · 2026-09-05 |

**폐기된 번호** — 초판의 `J30`·`J31`·`J32`·`J33`·`J34`·`J42`·`J45` 는 각각
`J16`·`J17`·`J19`·`J20`·`J21`·`J15`·`J18` 의 중복이라 **지웠다.**
번호를 재사용하지 않는다 — 커밋 이력에 남은 옛 번호가 다른 것을 가리키면 안 된다.

### 착수 시점 관찰 (편차 확정은 §제약)

- **J37 이 갭 C 의 범위를 정한다.** 지라는 이 설정을 스크럼 보드에만 연다.
  BTS 는 `board_type` 이 이미 있으므로(#421) 같은 제약을 그대로 태운다.
- **J41 이 갭 D 의 소비처다.** 실측 — `FR-RP-01` 은 D 완료이고 `BurndownCalculator.calculate` 가
  `day.plusDays(1)` 로 달력일 전부를 돈다. 갭 D 는 죽은 설정이 아니라 그 차트의 정확도 결함을 닫는다.
- **J43·J44 는 보드가 아니라 스페이스/스킴 설정을 가리킨다.** 보드 단위로 내는 것이 편차 X10.
- **J17 상한 3개는 커스텀 필드 전제와 맞물린다** — `FR-IS-10` 이 원천이고
  `BoardIssueView`(shared-kernel)가 그 값을 아직 안 나른다.

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
  **뷰마다 따로**다(J18 원문). 데이터 모델이 배열 한 칸 → `board_card_layout_fields(view_scope)` 로 바뀌었다.

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
- files: [`backend/modules/agile-planning/src/main/resources/db/migration/agile-planning/V509__board_settings_tabs.sql`, `backend/modules/agile-planning/src/test/kotlin/com/bts/agileplanning/migration/BoardSettingsMigrationTest.kt`]
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
- files: [`backend/modules/agile-planning/src/main/resources/db/migration/agile-planning/V509__board_settings_tabs.sql`, `backend/modules/agile-planning/src/test/kotlin/com/bts/agileplanning/migration/BoardCardLayoutSchemaTest.kt`]
- depends-on: [1]
- jira: [J17, J18]

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
- files: [`backend/modules/agile-planning/src/main/resources/db/migration/agile-planning/V509__board_settings_tabs.sql`, `backend/modules/agile-planning/src/test/kotlin/com/bts/agileplanning/migration/BoardDetailViewSchemaTest.kt`]
- depends-on: [1]
- jira: [J47]

**RED**. `field_group = 'BOGUS'` INSERT 가 통과한다.

**GREEN**. `board_detail_view_fields` + `CHECK (field_group IN ('GENERAL','DATE','PEOPLE','LINKS'))`.

**REFACTOR**. 그룹 4종의 출처(J47 · DC 문서)를 주석에 남긴다 — 편차 X10 과 짝.

**검증**. `(cd backend && ./gradlew :modules:agile-planning:test --tests '*BoardDetailViewSchemaTest')`

### Task 4. 마이그레이션 멱등 + jOOQ 코드젠 반영

**메타**.
- agent: `db-engineer`
- files: [`backend/modules/agile-planning/src/main/resources/db/migration/agile-planning/V509__board_settings_tabs.sql`, `backend/modules/agile-planning/src/main/resources/db/codegen/init_codegen.sql`, `backend/modules/agile-planning/src/test/kotlin/com/bts/agileplanning/migration/BoardSettingsIdempotencyTest.kt`]
- depends-on: [1, 2, 3, 27]
- jira: []

**RED**. 같은 SQL 을 JDBC 로 재실행하면 죽는다.

**GREEN**. `ADD COLUMN IF NOT EXISTS` · `CREATE TABLE IF NOT EXISTS` · `ADD CONSTRAINT` 는
`DO $$ ... pg_constraint ... $$` 로 감싼다(★`ADD CONSTRAINT` 에는 `IF NOT EXISTS` 가 없다 — 부채 161 · #444 가 같은 자리에서 밟았다).
`init_codegen.sql` 도 함께 고친다 — **jOOQ 코드젠은 마이그레이션이 아니라 이 파일을 읽는다**(부채 54).

**REFACTOR**. 되돌리기도 함께 잰다(리뷰 CONCERN C3) — `DROP COLUMN` 3개 + `DROP TABLE` **3개**를
JDBC 로 실행한 뒤 스키마가 **적용 전과 같음**을 단언한다.
★**2개가 아니라 3개다** — Task 3 이 `board_detail_view_fields` 를 더했다(2026-09-05 갱신).
대상은 `board_card_layout_fields` · `board_non_working_dates` · `board_detail_view_fields`.
V509 머리말의 되돌리기 목록은 아직 ①②③ 만 세므로 **머리말을 믿지 말고 파일 본문의 절을 세라** —
Task 3 이 ④ 절 안에 `DROP TABLE board_detail_view_fields;` 를 명시해 뒀다.
머리말 갱신도 Task 4 가 맡는다(V509 가 Task 4 의 files 에 있다). T1 의 되돌리기 주석은 산문이라
기계가 안 읽는다. `#444` 가 `V506` 되돌리기에서 밟은 자리다(부채 161).

멱등 판정은 **「1차 재실행 후 ↔ 2차 재실행 후」** 로 잰다.
「적용 전 ↔ 1차 후」로 재면 앞선 테스트가 만든 행을 1차가 **정당하게** 백필하는 것을 결함으로 오판한다(#444 실측).

**검증**. `(cd backend && ./gradlew :modules:agile-planning:test --tests '*BoardSettingsIdempotencyTest')`

### Task 5. shared-kernel 포트에 커스텀 필드를 싣는다

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/shared-kernel/src/main/kotlin/com/bts/shared/board/BoardIssueLookupPort.kt`, `backend/modules/shared-kernel/src/test/kotlin/com/bts/shared/board/BoardIssueViewContractTest.kt`]
- depends-on: []
- jira: [J19]

**RED**. `BoardIssueView` 에 `customFields` 가 없어 컴파일이 안 된다.

**GREEN**. `val customFields: Map<String, Any?> = emptyMap()` **기본값과 함께** 추가한다 —
기존 소비자 시그니처를 깨지 않는 것이 스펙 C-5 ②다.

**REFACTOR**. KDoc — 「소유는 issue-tracking BC. agile-planning 은 미러 노출만 한다」
(`rank` 필드가 이미 쓴 문구를 그대로 따른다).

**검증**. `(cd backend && ./gradlew :modules:shared-kernel:test --tests '*BoardIssueViewContractTest')`

### Task 6. issue-tracking 이 커스텀 필드 값을 채운다

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/repository/IssueRepository.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/adapter/outbound/board/BoardIssueLookupAdapter.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/repository/BoardIssueLookupCustomFieldsTest.kt`]
- depends-on: [5]

★**`BoardIssueLookupAdapter.kt` 를 files 에 더했다(2026-09-05 · Task 5 구현자가 넘긴 사실).**
`BoardIssueView` 를 실제로 만드는 곳은 `IssueRepository` 가 아니라 어댑터의
`BoardIssueEntry.toBoardIssueView()`(`:123-124`)다. 초판 files 에 어댑터가 없어 이 task 가
「선언 외 파일 수정」으로 BLOCKED 됐을 자리다 — 값을 읽어도 옮길 곳이 없다.
- jira: [J19]

**RED**. 커스텀 필드가 있는 이슈를 보드 조회로 읽으면 `customFields` 가 비어 있다.

**GREEN**. `FR-IS-10` 의 `customFields` 원천을 `BoardIssueView` 에 매핑한다.

**REFACTOR**. N+1 확인 — 카드 N건에 대해 커스텀 필드 조회가 **1회**임을 쿼리 카운트로 잰다.

**검증**. `(cd backend && ./gradlew :modules:issue-tracking:test --tests '*BoardIssueLookupCustomFieldsTest')`

### Task 7. 보드 설정 리포지터리 분리 — `BoardRepository.kt` 를 키우지 않는다

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/agile-planning/src/main/kotlin/com/bts/agileplanning/repository/BoardSettingsRepository.kt`, `backend/modules/agile-planning/src/test/kotlin/com/bts/agileplanning/repository/BoardSettingsRepositoryTest.kt`]
- depends-on: [1, 2, 3, 4]

★**`depends-on` 에 4 를 더했다(2026-09-05 · Task 1 구현자가 잡은 계획 결함).**
`init_codegen.sql` 미러가 **Task 4 소유**인데 jOOQ 상수는 그 파일에서 생성된다 — 미러 없이
이 task 가 먼저 돌면 **컴파일조차 안 된다**(V005 선례). 초판은 둘 다 `[1,2,3]` 이라 같은 wave 에
병렬로 놓였고, 그대로 dispatch 했으면 이 task 가 깨졌다.
- jira: []

**RED**. 설정 4축을 읽고 쓰는 리포지터리가 없다.

**GREEN**. 카드 레이아웃(뷰별) · 시간 추적 · 근무일 · 상세 필드의 CRUD.

**REFACTOR**. `BoardRepository.kt` **줄수 무증가**를 확인한다(부채 157 · 스펙 C-1).
`#444` 가 `BoardColumnStateRepository` 로 뺀 선례와 같은 이유.

**검증**. `(cd backend && ./gradlew :modules:agile-planning:test --tests '*BoardSettingsRepositoryTest')`

### Task 8. 카드 레이아웃 PATCH — 뷰별 저장 + 400

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/agile-planning/src/main/kotlin/com/bts/agileplanning/application/CardLayoutSettingsService.kt`, `backend/modules/agile-planning/src/main/kotlin/com/bts/agileplanning/web/BoardController.kt`, `backend/modules/agile-planning/src/test/kotlin/com/bts/agileplanning/web/BoardCardLayoutApiTest.kt`]
- depends-on: [7]
- jira: [J17, J18]

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
- files: [`backend/modules/agile-planning/src/main/kotlin/com/bts/agileplanning/application/EstimationSettingsService.kt`, `backend/modules/agile-planning/src/test/kotlin/com/bts/agileplanning/web/BoardEstimationApiTest.kt`]
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
- files: [`backend/modules/agile-planning/src/main/kotlin/com/bts/agileplanning/application/WorkingDaysSettingsService.kt`, `backend/modules/agile-planning/src/test/kotlin/com/bts/agileplanning/web/BoardWorkingDaysApiTest.kt`]
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
- files: [`backend/modules/agile-planning/src/main/kotlin/com/bts/agileplanning/application/DetailViewSettingsService.kt`, `backend/modules/agile-planning/src/test/kotlin/com/bts/agileplanning/web/BoardDetailViewApiTest.kt`]
- depends-on: [7]
- jira: [J46, J47, J48, J49]

**RED**. 그룹 4종에 필드를 넣고 순서를 바꿔도 응답이 순서를 안 지킨다.

**GREEN**. 그룹별 저장 + `position` 순 반환 + 미지원 그룹 400.

**REFACTOR**. 권한은 Task 8 과 같은 게이트. 편차 **X8**(보드 단위 권한 부재 → 프로젝트 권한 갈음)을 KDoc 에.

**검증**. `(cd backend && ./gradlew :modules:agile-planning:test --tests '*BoardDetailViewApiTest')`

### Task 14. BC 경계 판별식 — 포트 계약 고정 + 직접 import 0건

**메타**.
- agent: `backend-engineer`
- files: [`scripts/workflow/bc-isolation-agile-planning.test.ts`, `backend/modules/shared-kernel/src/test/kotlin/com/bts/shared/board/BoardIssueViewFieldSetTest.kt`]
- depends-on: [5, 6]
- jira: []

★**리뷰 BLOCKER B1 로 재작성했다.** 초판은 `com.bts.issue` **직접 import 0건**만 쟀는데,
이 PR 이 만드는 결합은 직접 import 가 **아니라 shared-kernel 포트 경유**(T5·T6)다.
즉 초판 판별식은 **초록인 채로 X9 의 위험을 통과시킨다** — 「가드를 지웠는데 통과했다」의 사전 판본.

**RED**. ① 포트 계약 테스트가 없다 ② `agile-planning` 에 `import com.bts.issue.…` 한 줄을
일부러 넣어도 아무도 안 잡는다. **둘 다 red 를 눈으로 본다**(가드는 끊어 봐야 산다).

**GREEN**. 두 축을 함께 세운다.
- **주 방어선** — `BoardIssueView` 의 **필드 이름 집합**을 shared-kernel 테스트가 고정한다.
  필드가 늘면 그 테스트가 red 가 되어 **cross-BC 계약 변경이 반드시 눈에 띈다.**
  포트를 넓히는 것 자체는 허용된 경로이므로 금지가 아니라 **가시화**가 처방이다.
- **보조선** — `agile-planning/src/main` 에 `com.bts.issue` import 0건.

**REFACTOR**. 판별식이 **못 보는 축**을 적는다 — 리플렉션·문자열 경유 의존, 그리고
포트 필드의 **의미** 변경(이름은 같은데 뜻이 달라지는 경우)은 둘 다 못 잡는다.

★★**보조선의 실제 위상**(2026-09-05 실측 정정). 초판 RED 문구 「import 한 줄을 넣어도 **아무도
안 잡는다**」는 사실이 아니다. ①`agile-planning/build.gradle.kts` 의 project 의존은
`shared-kernel` 하나뿐이라 `import com.bts.issue.…` 는 **컴파일이 죽는다**(미해결 참조).
②`AgilePlanningBcArchTest.kt:74` `mustNotImportIssueTracking` 이 같은 축을 바이트코드에서 이미 막고,
**그쪽은 backend-ci 에서 실제로 돈다**. 정확히는 「**소스 텍스트 층에는** 판별식이 없었다」다.

★★★**그리고 이 TS 판별식은 어떤 PR CI 에서도 돌지 않는다.** `pnpm test:workflow` 를 부르는 유일한
잡이 `.github/workflows/workflow-scripts-ci.yml:114` 인데 그 파일의 `on:` 은 `workflow_dispatch:` 뿐이다.
대체 수단으로 적힌 `.husky/pre-commit` 도 `lint-staged` + `build-doc-index --check` 만 돌린다.
**저장소 선재 상태이고 이 PR 범위 밖이다.** 그러므로 보조선의 실질 가치는 두 가지뿐이다 —
①스펙 C-5 ④ 가 명시 요구한 이행 ②컴파일러·ArchUnit 이 사라지는 날을 위한 보험.
**이 사실을 모르는 다음 사람이 이것을 살아 있는 방어선으로 믿는 것이 진짜 위험**이라 파일 머리에도 적는다.

**검증**. `node --experimental-strip-types --test scripts/workflow/bc-isolation-agile-planning.test.ts` ·
`(cd backend && ./gradlew :modules:shared-kernel:test --tests '*BoardIssueViewFieldSetTest')`

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
- files: [`apps/web/src/components/board/settings/CardLayoutPanel.tsx`, `apps/web/src/components/board/settings/SettingsTabs.tsx`, `apps/web/src/api/board-settings.ts`, `apps/web/src/components/board/settings/CardLayoutPanel.test.tsx`]
- depends-on: [8, 15]
- jira: [J17, J18]

★**T15 가 남긴 계약(구현자 CONCERN C2).** `SettingsTabs.tsx` 안에 `PendingPanel`(아무것도
렌더하지 않는 컴포넌트)이 있고 4개 `TabsContent` 가 그것을 부른다. 이 task 는 자기 탭의
`<PendingPanel />` 을 자기 패널 호출로 **한 줄 교체**한다. 넷이 다 교체되면 `PendingPanel` 은
사라진다 — **남아 있으면 그 자체가 미완의 표시**다.

**RED**(동반 테스트). 4번째 필드를 고를 수 있다. 그리고 스크럼에서 뷰 전환이 없다.

**GREEN**. 뷰 토글(`보드`/`백로그` · 스크럼만) + 3개 상한 + 커스텀 필드 후보 표시.

**REFACTOR**. 칸반은 뷰 토글을 **그리지 않는다**(백로그 스코프 자체가 없다).

**검증**.
- 기존 E2E: `apps/web/e2e/board-settings.spec.ts`
- 눈확인: 3개 채운 뒤 4번째가 비활성 — 라이트/다크

### Task 17. 추정 탭 UI — 칸반 잠금

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/board/settings/EstimationPanel.tsx`, `apps/web/src/components/board/settings/SettingsTabs.tsx`, `apps/web/src/components/board/settings/EstimationPanel.test.tsx`]
- depends-on: [9, 15]
- jira: [J36, J37]

**RED**(동반 테스트). 칸반 보드에서 시간 추적을 바꿀 수 있다.

**GREEN**. 스크럼만 편집 가능 · 칸반은 사유를 보이며 잠근다.

**REFACTOR**. 잠금 사유 문구를 `board-labels.ts` 로 — 화면에 문자열을 박지 않는다.

**검증**. 눈확인: 칸반 보드에서 잠금 표시 — 라이트/다크

### Task 18. 작업일 탭 UI

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/board/settings/WorkingDaysPanel.tsx`, `apps/web/src/components/board/settings/SettingsTabs.tsx`, `apps/web/src/components/board/settings/WorkingDaysPanel.test.tsx`]
- depends-on: [10, 15]
- jira: [J38, J39, J40]

**RED**(동반 테스트). 근무일을 0개로 만들어 저장할 수 있다.

**GREEN**. 요일 토글 + 비근무일 날짜 추가/삭제 + 타임존 선택. 0개면 저장 비활성.

**REFACTOR**. 「미설정 = 현행 유지」를 화면에서 읽히게 한다 — 빈 상태가 「전부 근무일」로 오해되면 안 된다.

**검증**. 눈확인: 비근무일 추가 후 목록 — 라이트/다크

### Task 19. 상세 보기 탭 UI — 그룹 4종 + 드래그

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/board/settings/DetailViewPanel.tsx`, `apps/web/src/components/board/settings/SettingsTabs.tsx`, `apps/web/src/components/board/settings/DetailViewPanel.test.tsx`]
- depends-on: [13, 15]
- jira: [J46, J47, J48]

**RED**(동반 테스트). 드래그로 순서를 바꿔도 저장되지 않는다.

**GREEN**. 그룹 4종 · 드롭다운 추가 · 삭제 · 드래그 정렬.
★`#452` 의 `use-column-settings-drag.ts` **잠금 패턴을 재사용**한다 — 연속 드롭 lost update 방어
(그 PR 이 BLOCKER B2 로 닫은 자리다).

**REFACTOR**. 새 드래그 훅을 만들지 않는다(계약 §4 재사용 자산).
★**T15 가 넘긴 C3 을 여기서 갚는다.** `settings.pageDescription` 이 「컬럼 구성과 워크플로우 상태
매핑을 바꿉니다」로 남아 있어 탭 5개가 된 지금 낡았다. T15 가 **일부러** 안 고쳤다 — 문구에
`작업일`·`상세 보기` 같은 탭 라벨을 넣으면 Playwright `getByText` 부분 일치가 탭과 설명문을
동시에 잡아 T22~T24 셀렉터를 흔든다. **4탭이 다 붙는 이 시점**에 라벨과 겹치지 않는 문구로 고친다.

**검증**. 눈확인: 드래그 중 순서 표시 — 라이트/다크

### Task 20. 카드가 구성을 읽는다 — 보드 + 백로그

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/board/BoardCard.tsx`, `apps/web/src/components/backlog/BacklogRow.tsx`, `apps/web/src/components/board/BoardCard.test.tsx`]
- depends-on: [16, 26]

★**26 을 더했다** — 커스텀 필드가 `BoardCardResponse` 에 실려야 화면이 그릴 것이 생긴다.
- jira: [J17, J19, J18]

**RED**(동반 테스트). 구성을 바꿔도 카드가 그대로다. 그리고 **보드와 백로그에 다른 구성**을 주면 같아진다.

**GREEN**. 뷰 스코프에 맞는 구성을 읽어 3층 순서로 그린다(J19) — 요약 → 추가 필드 → 상세.
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
- jira: [J15, J46]

**RED**(동반 테스트). 구성이 모달에만 반영되고 사이드패널은 그대로다.

**GREEN**. 두 표현이 **같은 구성**을 읽는다. `#455`(모달↔사이드패널 토글)가 방금 머지됐으므로
한쪽만 반영하면 「같은 이슈가 열기 방식에 따라 다르게 보인다」가 된다(스펙 R7c).

**REFACTOR**. 구성 읽기를 훅 하나로 모아 두 표현이 갈리지 않게 한다.

**검증**. 눈확인: 모달과 사이드패널에서 같은 필드 집합 — 라이트/다크

### Task 22. E2E — 탭바 + 카드 레이아웃 (선행 짧게)

**메타**.
- agent: `qa-engineer`
- files: [`apps/web/e2e/board-settings.spec.ts`]
- depends-on: [16]
- jira: [J22, J17, J18]

★**리뷰 CONCERN C4 로 쪼갰다.** 초판은 `depends-on: [16..21]` 이라 프론트 6개가 전부 끝나야
E2E 가 시작하는 직렬 꼬리였다. 탭별로 나누면 T16 완료 시점에 첫 E2E 가 돈다.

★★**선행 수리 — 이 spec 은 지금 red 다(T15 구현자가 실측·증명).**
`board-settings.spec.ts:38` 이 `fixtures/board-helpers.ts` 의 `boardActionsTrigger` 를
**import 하지 않고 같은 이름으로 로컬 재정의**해 뒀고 스코프가 없다. `#454`(사이드바 보드 `⋯`)가
들어오면서 헤더 `⋯` 와 접근성 이름이 바이트 단위로 같아져 S1 이 strict mode violation 으로 즉사한다 —
**캠페인 위험 R10 이 실현된 것**이고 이 브랜치 변경과 무관하다(T15 가 `4fa2cfa84^` 로 되돌려 재현 확인).
처방은 로컬 헬퍼 삭제 + `import { boardActionsTrigger } from './fixtures/board-helpers'` **한 줄**.
이 수리를 GREEN 앞에 둔다.

**RED**. 탭 전환 시나리오가 없다. 그리고 위 S1 이 red 다.

**GREEN**. ①S1 수리(위 한 줄) ②탭바 5탭 전환 + 카드 레이아웃 **뷰별 저장**(보드↔백로그에 서로 다른 구성).
★보드 헤더 `⋯` 와 사이드바 보드 `⋯` 는 접근성 이름이 같다(캠페인 R10) —
`apps/web/e2e/fixtures/board-helpers.ts` 의 **컨테이너 스코프 헬퍼를 그대로 쓴다**(`#450`).

**REFACTOR**. 기존 3건(`#452`)이 탭바 도입으로 깨지지 않았는지 함께 확인한다.

**검증**. `(cd apps/web && node_modules/.bin/playwright test e2e/board-settings.spec.ts)`

### Task 23. E2E — 추정 · 작업일

**메타**.
- agent: `qa-engineer`
- files: [`apps/web/e2e/board-settings-estimation.spec.ts`]
- depends-on: [17, 18]
- jira: [J36, J37, J38, J39]

**RED**. 칸반에서 추정 탭이 잠기는지, 근무일 등록이 번다운을 좁히는지 아무도 안 잰다.

**GREEN**. 칸반 잠금 표시 + 근무일·비근무일 등록 후 번다운 x축 축소.
★**타임존만 바꾸는 경로도 함께 잰다** — 리뷰가 critical gap 으로 지목한 침묵 실패다
(설정은 되는데 차트가 안 바뀌면 아무도 모른다).

**REFACTOR**. 근무일 미설정 보드가 **현행 그대로**인 대조군을 같은 spec 에 둔다.

**검증**. `(cd apps/web && node_modules/.bin/playwright test e2e/board-settings-estimation.spec.ts)`

### Task 24. E2E — 상세 보기 (모달 + 사이드패널 양쪽)

**메타**.
- agent: `qa-engineer`
- files: [`apps/web/e2e/board-settings-detail-view.spec.ts`]
- depends-on: [19, 21]
- jira: [J46, J47, J48]

★**리뷰 CONCERN C2 로 신설했다.** 완료 기준 9c 와 T21 에는 「모달·사이드패널 양쪽」 축이 있는데
**E2E 에는 없었다.** `#455` 가 방금 그 토글을 냈으므로 한쪽만 반영되는 회귀가 지금 가장 나기 쉽다.

**RED**. 상세 구성을 바꾼 뒤 모달로 열면 반영되는데 **사이드패널로 열면 그대로다** —
이 단언이 없으면 그 회귀가 침묵한다.

**GREEN**. 그룹 4종 편집 + 드래그 정렬 → **두 표현 모두** 같은 필드 집합을 그린다.

**REFACTOR**. 두 표현을 여는 헬퍼를 spec 안에서 한 곳으로 모은다.

**검증**. `(cd apps/web && node_modules/.bin/playwright test e2e/board-settings-detail-view.spec.ts)`

### Task 25. 어댑터가 커스텀 필드를 열람 권한으로 마스킹한다

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/adapter/outbound/board/BoardIssueLookupAdapter.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/adapter/outbound/board/BoardIssueLookupMaskingTest.kt`]
- depends-on: [6]
- jira: []

★**Task 6 구현자가 올린 보안 결함 후보를 닫는다. Maxi 확정 2026-09-05 — 「어댑터가 마스킹한다」.**

**무엇이 문제였나.** issue-tracking 자기 REST 경로는 커스텀 필드를 마스킹한다 —
`IssueResponse.maskFields()` 가 열람 권한 없는 키를 제거하고
`IssueApplicationService.maskFieldsForPage()`(`:1869`)가 페이지당 1회
`FieldPermissionResolver.visibleFields` 로 적용한다(FR-PM-07).
**cross-BC 포트 경로에는 그 게이트가 없다.** Task 6 이 `BoardIssueView.customFields` 를
원시 값으로 채웠으므로, Task 26 이 이 값을 보드 응답에 미러하는 순간 **열람 권한 없는
커스텀 필드가 카드에 실려 나간다.** 지금은 아직 아무도 직렬화하지 않아 잠재 결함이다.

**왜 어댑터인가.** 권한 판정은 issue-tracking 이 소유한 지식이고 FR-PM-07 구현이 거기 있다.
agile-planning 이 마스킹하려면 권한 모델을 복사해야 하고 그 순간 「두 목록이 서로를 검사하지
않는다」가 된다. **포트 밖으로는 이미 안전한 값만 나간다** — 미러하는 쪽은 받은 것을 그대로 쓴다.

**RED**. 열람 권한이 없는 뷰어로 보드 카드를 조회해도 `customFields` 에 그 키가 **그대로 들어 있다**.
★**대조군을 한 쌍으로 둔다** — 권한이 **있는** 뷰어는 같은 키를 **본다**는 단언을 함께 둔다.
뒤엣것이 없으면 「전부 지우는」 구현도 통과한다.

**GREEN**. 어댑터가 페이지당 **1회** `FieldPermissionResolver.visibleFields` 를 호출해 필터한다.
★카드마다 부르면 N+1 이다.

★★**초판의 「Task 6 의 쿼리 카운트 가드(C4·C5)가 그것을 잡는다」는 틀렸다**(2026-09-05 실측 정정).
C4·C5 는 jOOQ `ExecuteListener` 로 `countingDsl` 을 지나는 **SQL 문 수**만 센다. 테스트 어댑터는
2-인자 생성이라 resolver 가 기본값 `AlwaysAllowFieldPermissionResolver` 이고 그 구현은
**SQL 을 한 문장도 실행하지 않는다.** 따라서 카드마다 resolver 를 불러도 카운터는 1 그대로다 —
쿼리 카운트로 이 N+1 을 잡는 것은 **구조적으로 불가능**하다.
그래서 **판정 호출 수**를 세는 축(M4·M5)을 따로 세운다. 스파이가 실제 비용을 대표하는 근거는
prod 판정기 `IdentityAccessFieldPermissionResolver.visibleFields` 가 호출 1회마다
`FieldPermissionRepository.findByProject` + `UserGroupRepository.findGroupIdsByUser` 로
**DB 를 2회 친다**는 것이다 — 호출 수 고정이 곧 왕복 수 고정이다.

**REFACTOR**. 포트 KDoc 에 「이 값은 **이미 마스킹된 것**이다 — 소비자는 다시 거르지 않는다」를 못박는다.
마스킹 주체가 두 곳이 되면 그것이 곧 두 번째 진실이다.

**검증**. `(cd backend && ./gradlew :modules:issue-tracking:test --tests '*BoardIssueLookupMaskingTest' --tests '*BoardIssueLookupCustomFieldsTest')`

### Task 26. 보드 응답이 커스텀 필드를 미러한다

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/agile-planning/src/main/kotlin/com/bts/agileplanning/web/dto/BoardResponses.kt`, `backend/modules/agile-planning/src/test/kotlin/com/bts/agileplanning/web/BoardCardCustomFieldsApiTest.kt`]
- depends-on: [25]
- jira: [J17, J19]

★**계획에 없던 task 다(2026-09-05 신설).** Task 5 구현자가 「Task 6 이 값을 채우고 나면
`BoardCardResponse` 에도 미러해야 화면까지 닿는다」를 넘겼는데 그 미러를 소유한 task 가 없었다 —
T8 은 카드 레이아웃 **설정**이고 T20 은 프론트다. 백엔드 응답에 필드가 없으면 화면이 그릴 것이 없다.

**RED**. 커스텀 필드가 있는 이슈를 보드 조회로 읽어도 응답 카드에 `customFields` 가 없다.

**GREEN**. `BoardCardResponse` 에 `customFields: Map<String, Any?> = emptyMap()` 를 더하고
`from(card: BoardIssueView)` 가 그대로 옮긴다. **다시 거르지 않는다** — Task 25 가 이미 걸렀다.

**REFACTOR**. `rank` 의 「소유는 issue-tracking BC · 미러 노출만 한다」 문구를 따라 KDoc 을 단다.
`agile-planning` 이 `com.bts.issue` 를 import 하지 않는지 확인한다(Task 14 판별식과 같은 축).

**검증**. `(cd backend && ./gradlew :modules:agile-planning:test --tests '*BoardCardCustomFieldsApiTest')`

### Task 27. time_tracking 에 CHECK 를 건다 — 형제 board_type 과 같은 관용구

**메타**.
- agent: `db-engineer`
- files: [`backend/modules/agile-planning/src/main/resources/db/migration/agile-planning/V509__board_settings_tabs.sql`, `backend/modules/agile-planning/src/test/kotlin/com/bts/agileplanning/migration/BoardSettingsMigrationTest.kt`]
- depends-on: [1, 3]
- jira: [J36]

★**계획에 없던 task 다(2026-09-05 신설). 게이트 2 관찰에서 올라왔다.**
★**T4 보다 먼저 실행한다** — 번호는 뒤지만 의존은 앞이다(아래 「T4 와의 관계」).

**무엇이 문제였나.** `time_tracking` 은 `NONE` / `REMAINING_AND_SPENT` 2종으로 닫힌
열거형인데 CHECK 가 없다. 형제 `board_type`(`V505:11`)은 같은 모양의 닫힌 열거형을
`CONSTRAINT boards_board_type_allowed CHECK (board_type IN ('SCRUM', 'KANBAN'))` 로 지킨다.
**같은 테이블의 같은 종류 컬럼 두 개가 서로 다른 규율을 받는다.**

**T1 의 반론과 그 한계.** `V509:48-50` 이 「빠뜨린 것이 아니라 스펙 §데이터 모델이 CHECK 없이
선언했기 때문」이라고 적었다. 사실 관계는 맞다. 그러나 그것은 **스펙이 옳다는 근거가 아니라
스펙이 형제와 어긋난다는 증거**다. 스펙의 같은 절이 `board_card_layout_fields` ·
`board_detail_view_fields` 에는 CHECK 를 명시했다는 사실은 「구분」이 아니라
**「셋 중 하나만 빠졌다」**로도 똑같이 읽힌다. 판정 근거는 스펙이 아니라 **형제 컬럼**이다.

**T4 와의 관계 — 서로를 강화한다.** 지금 V509 의 CHECK 는 전부 `CREATE TABLE` 인라인
정의라 `ADD CONSTRAINT` 가 **한 줄도 없다.** 그래서 T4 의 GREEN 요구인
「`ADD CONSTRAINT` 에는 `IF NOT EXISTS` 가 없으므로 `DO $$ ... pg_constraint ... $$` 로 감싼다」
(부채 161 · #444 가 밟은 자리)는 **현재 공허하다 — 감쌀 대상이 없다.**
T27 이 `boards_time_tracking_allowed` 를 추가하면 T4 가 실제 대상을 얻는다.
따라서 **T27 → T4** 순서다. T4 의 `depends-on` 에 27 을 더한다.

**RED**. `time_tracking = 'BOGUS'` UPDATE 가 통과한다.

**GREEN**. `ALTER TABLE boards ADD CONSTRAINT boards_time_tracking_allowed
CHECK (time_tracking IN ('NONE', 'REMAINING_AND_SPENT'));`
제약 이름은 형제 `boards_board_type_allowed` 의 관용구를 따른다.

**REFACTOR**. `V509:48-50` 의 「CHECK 를 걸지 않았다」 주석을 **판정 근거와 함께 뒤집는다** —
왜 스펙을 따르지 않고 형제를 따랐는지 남긴다. 주석이 코드와 반대로 남으면 그것이
다음 사람을 속인다.

**공허 방지**. 대조군을 한 쌍으로 둔다 — 「BOGUS 는 죽는다」만 있으면 「전부 죽이는」 제약도
통과한다. 「`NONE` 과 `REMAINING_AND_SPENT` 는 각각 들어간다」를 함께 둔다.
부정 단언은 SQLSTATE `23514` 값 동등으로 고정한다(T2 가 같은 자리에서 42P01 을 삼켰다).

**검증**. `(cd backend && ./gradlew :modules:agile-planning:test --tests '*BoardSettingsMigrationTest')`

### Task 28. V509 가 깬 boards 컬럼 카운트 가드를 갱신한다

**메타**.
- agent: `db-engineer`
- files: [`backend/modules/agile-planning/src/test/kotlin/com/bts/agileplanning/migration/BoardSchemaMigrationTest.kt`]
- depends-on: [1]
- jira: []

★**계획에 없던 task 다(2026-09-05 신설). Task 27 이 실측으로 찾았다. 머지 차단 결함이다.**

**무엇이 문제인가.** `BoardSchemaMigrationTest > V501 boards 7개 컬럼 존재` 가 **red 다.**
그 테스트는 `boards` 컬럼 목록을 `containsExactlyInAnyOrder` 로 **완전 일치** 검사하는데,
Task 1 이 V509 로 `time_tracking` · `working_days` · `board_timezone` 3칸을 더했다.
패키지 전체를 돌리면 `139 tests completed, 1 failed` 다.

**가드는 의도대로 작동했다.** 「누가 `boards` 에 컬럼을 더하면 반드시 눈에 띈다」가 이 완전 일치
검사의 목적이고, 정확히 그 일이 일어났다. 결함은 가드가 아니라 **갱신하지 않은 것**이다.

**왜 T1 이 못 봤나 — 좁은 테스트 필터가 회귀를 숨겼다.** T1 도 T2 도 T27 도
`--tests '*BoardSettingsMigrationTest'` 처럼 **자기 클래스만** 지목해 돌렸다. 같은 패키지의
다른 테스트는 한 번도 실행되지 않았고, 독립 검증도 「선언 외 파일 없음」만 봤지
**「같은 모듈의 다른 테스트가 깨졌는가」는 계약에 없었다.** 이 PR 의 검증 계약 결함이다.

**★같은 자리에 결함이 하나 더 있다.** `BOARDS_COLUMNS_V501` 은 주석이 「7개 컬럼」인데
실제 리스트는 **8개**다 — V505 가 `board_type` 을 더하며 주석을 안 고쳤다.
**같은 양식이 이미 한 번 반복됐다는 증거**이므로 이번에 함께 닫는다.

**RED**. `(cd backend && ./gradlew :modules:agile-planning:test --tests '*BoardSchemaMigrationTest')`
가 red 다. 실패 원문을 눈으로 본다 — 「could not find」가 아니라 「unexpected」 방향이어야 한다
(테스트가 모르는 컬럼이 DB 에 있다).

**GREEN**. `BOARDS_COLUMNS_V501` 에 3칸을 더하고 **어느 V번호가 더했는지 주석으로 표시**한다
(기존 `// V505 — 보드 종류(SCRUM/KANBAN)` 관용구를 따른다). 주석의 「7개」도 실수와 함께 정정한다.

**REFACTOR**. 이 가드가 **왜 완전 일치인지**와 **갱신을 잊으면 어떻게 드러나는지**를 적는다.
그리고 「자기 클래스만 지목해 돌리면 이 red 를 못 본다」를 남긴다 — 다음 마이그레이션 task 가
같은 자리를 밟는다.

**검증**. 자기 클래스만 돌리지 말고 **패키지 전체**를 돌린다.
`(cd backend && ./gradlew :modules:agile-planning:test --tests 'com.bts.agileplanning.migration.*')`

## Plan 메타

- **task 수**: 28 · **실질 단계**: 4 (초판의 「wave 5」는 직렬 5단계라는 오해를 줬다)
  - **A 마이그레이션 1~4** 와 **B 포트 5~6** 은 **완전 독립이라 동시 시작**한다(리뷰 지적)
  - C 백엔드 7~14 (14 는 5·6 이후) · D 프론트 15~21 · E E2E 22~24 (탭별로 쪼개 꼬리를 줄였다)
- **구현 규율**: 백엔드는 정식 TDD red-first. 프론트는 ui 시각 검증 트랙(RED = 동반 테스트).
- **추가 검증**: `tsc --noEmit -p tsconfig.app.json` · `eslint src` · `vitest run` ·
  `playwright test e2e/board-settings*.spec.ts` · `ktlint` · `detekt` ·
  `node --experimental-strip-types --test 'scripts/**/*.test.ts'` · `build-doc-index.mjs --check`
- ★**병렬 wave 주의** — 같은 worktree 의 병렬 dispatch 는 git 인덱스를 공유한다.
  `git add` 로 좁혀도 커밋 시점 인덱스에 남의 파일이 있으면 함께 커밋된다.
  **경로 한정 커밋**(`git commit -- <경로>`)을 쓰고 로그 파일명에 PID 를 붙인다
  ([[shared-worktree-git-index-defeats-narrow-git-add]] · 2026-09-04 실측).

### Jira 매핑 — 채택 J 번호 ↔ task 차집합 0

| J | task | J | task |
|---|---|---|---|
| J8 | T8 · T13 | J35 | T9 · T17 |
| J12 | T16 | J36 | T1 · T9 · T17 · T23 · T27 |
| J13 | T17 | J37 | T9 · T17 · T23 |
| J14 | T18 | J38 | T1 · T10 · T18 · T23 |
| J15 | T19 · T21 | J39 | T1 · T10 · T18 · T23 |
| J16 | T15 · T16 | J40 | T1 · T10 · T12 · T18 · T23 |
| J17 | T2 · T8 · T16 · T20 | J41 | T11 |
| J18 | T2 · T8 · T16 · T20 · T22 | J43 | T13 · T19 |
| J19 | T5 · T6 · T20 | J46 | T13 · T19 · T21 · T24 |
| J22 | T15 · T22 | J47 | T3 · T13 · T19 · T24 |
| | | J48 | T13 · T19 · T24 |
| | | J49 | T13 |

**범위 밖 3건 — 전부 사유를 적는다.**

- **J20 `Days in column`** — 편차 **X6**. 컬럼 진입 시각을 아무도 기록하지 않는다.
  별도 이력 테이블 + issue-tracking 전환 이력 포트가 필요해 마이그레이션이 두 종류가 된다.
- **J21 카드 색** — 편차 **X11** (리뷰 BLOCKER B3 로 신설). 세 가지 이유다.
  ① 지라에서 카드 색은 **Card layout 탭이 아니라 별도 설정**이고 부채 177 의 7탭 목록에 없다 —
  이 PR 의 대상이 아니다. ② 색 기준 4종 중 **JQL 은 `search-export-import` BC 소관**이라
  이 PR 이 건드리면 BC 를 하나 더 늘린다(X9 가 이미 4개다).
  ③ `TODOS.md:1518` 이 「카드 색의 JQL 기준은 search BC 소관이라 배제 대상인지」를 착수 시
  풀 질문으로 남겨 뒀고, **이 편차가 그 질문에 대한 답이다** — 배제하고 후속으로 등재한다.
  ★초판은 J21(당시 J34)을 조회해 표에 싣고 FR·편차·task 어디에도 안 올렸다.
  분해가 조용히 삼킨 조작은 구현·리뷰·머지 어디서도 안 드러난다.
- **J44 필드 노출 전제** — work type 연관·hidden 판정은 issue-tracking 의 **필드 스킴** 영역이고
  이 PR 은 보드 단위 구성만 낸다. 편차 X10 과 같은 경계.

## 리뷰 결과

렌즈 2종(`type == migration` → `/plan-eng-review` + `/plan-ceo-review`). 한 응답에 병렬 발행했다.
**Outside voice 는 생략** — `gstack-config get codex_reviews` 가 `disabled` 다.
스킬 규정상 이때는 Claude 서브에이전트 폴백도 하지 않는다(「disabled 는 추가 리뷰 단계 없음」).

### Step 0 — 스코프 도전 (eng)

**복잡도 체크가 트리거됐다**(8+ 파일 · 2+ 신규 서비스). 그러나 **재질의하지 않는다** —
「4탭 한 PR」과 「지라 패리티」는 비용(리뷰 표면 4배 · 리베이스 반복 · 롤백 단위 상실 ·
BC 격리 위반)을 제시한 뒤 Maxi 가 2026-09-05 에 **두 번 확정**했다. 이미 정해진 결정을
리뷰 프레임이 다르다는 이유로 다시 묻는 것은 재론이다. 기록만 남긴다.

### 🛑 BLOCKER 3건

**B1 — X9 의 유일한 기계 방어선이 실제 위험을 안 지킨다.** `[P1] (confidence 9/10)`
T14 는 `agile-planning/src/main` 에 `com.bts.issue` **직접 import 가 0건**임을 잰다.
그런데 이 PR 이 만드는 결합은 직접 import 가 **아니라** shared-kernel
`BoardIssueLookupPort` 경유다(T5·T6). 즉 판별식이 초록인 채로 X9 의 위험이 그대로 통과한다.
스펙 C-5 ④가 「유일한 기계 방어선」이라 적었는데 그 방어선이 헛돈다 —
**「가드를 지웠는데 통과했다」의 사전 판본**이다([[invariant-satisfied-by-helptext-not-logic]] 와 같은 양식).
처방 후보 — 포트의 **필드 개수·이름 집합**을 고정하는 계약 테스트를 shared-kernel 에 두고,
`agile-planning` 이 그 집합 밖을 읽지 않음을 잰다. 「import 0건」은 그 위에 얹는 보조선으로 남긴다.

**B2 — J 번호가 두 벌이 됐다.** `[P1] (confidence 10/10)`
`TODOS.md:1499~1516` 이 **J12~J21** 로 4탭을 이미 조회해 뒀다(2026-09-03 · 「착수 시 계약 §1-0
재사용 대상」이라 스스로 명시). 그런데 이 세션은 그것을 못 보고 **J30~J34 · J45 로 다시 조회**했다.

| 이미 있던 것 | 내가 새로 붙인 번호 | 같은 내용인가 |
|---|---|---|
| J16 카드 레이아웃 경로 | J30 | **동일** |
| J17 추가 필드 상한 3개 | J31 | **동일** |
| J18 백로그↔활성 스프린트 별도 설정 | J45 | **동일** |
| J19 카드 3층 구조 | J32 | **동일** |
| J20 Days in column | J33 | **동일** |
| J21 카드 색 | J34 | **동일** |

**원인.** 계약 §1-0 의 재사용 명령이 `grep -rln "## Jira 대조" docs/specs/ docs/plans/` 라
`TODOS.md` 를 훑지 않는다. 그런데 `#452` 스펙은 승계 출처를 「`TODOS.md:1459~` 에 J7~J21 로
남겼다」라고 적어 뒀다 — **명령과 실제 저장 위치가 어긋나 있다.**

**대가가 이미 발생했다.** J18 이 「백로그와 활성 스프린트가 서로 다른 설정을 갖는다」를
2026-09-03 에 이미 기록했는데, 이 세션은 그것을 모른 채 초안 R3 을 「구성 공유」로 잘못 쓰고
재조회로 뒤집었다(G6). **재사용 단계를 제대로 밟았으면 처음부터 옳았다.**

**B3 — 조회한 J 번호 하나가 요구사항에도 편차에도 없다.** `[P1] (confidence 9/10)`
**카드 색**(J21 = J34 · *"You can base your card colors on work types, priorities, assignees, or JQL."*)을
조회해 표에 실어 놓고 FR 에도 편차에도 task 에도 올리지 않았다. plan §1-7 이 요구하는
「채택 J 번호 ↔ task 차집합 0」이 **깨진다** — Jira 매핑 줄이 J34 를 그냥 빠뜨렸다.
`TODOS.md:1518` 이 「착수 시 먼저 풀 것」 ②로 **「카드 색의 JQL 기준은 search BC 소관이라
배제 대상인지」**를 이미 질문으로 남겨 뒀는데 그 질문에 답하지 않았다.
분해가 조용히 삼킨 조작은 구현·리뷰·머지 어디서도 드러나지 않는다.

### ⚠️ CONCERNS 4건

**C1 — `BoardSettingsService` 가 부채 157 을 서비스 층에 복제한다.** `[P2] (8/10)`
T8·T9·T10·T13 이 전부 같은 파일을 `files` 로 잡는다. `BoardRepository.kt` 를 안 키우려고
리포지터리를 뺐는데(C-1) 서비스에 같은 덩어리를 만든다. 탭별로 쪼개는 편이 wave 병렬성도 산다.

**C2 — 상세 보기의 「모달 + 사이드패널 양쪽」 판정이 E2E 에 없다.** `[P1→P2] (9/10)`
완료 기준 9c 와 T21 에는 있는데 **T22 E2E 시나리오에 그 축이 없다.** `#455` 가 방금 토글을
냈으므로 한쪽만 반영되는 회귀가 정확히 지금 생기기 쉽다.

**C3 — 마이그레이션 되돌리기 판정이 없다.** `[P2] (7/10)`
T4 는 **멱등**만 잰다. 스펙 데이터 모델은 되돌리기 주석을 요구하는데(T1 REFACTOR) 그것을
검증하는 task 가 없다. `#444` 가 `V506` 되돌리기에서 겪은 자리다(부채 161).

**C4 — wave E 가 직렬 꼬리다.** `[P2] (7/10)`
T22 가 `depends-on: [16,17,18,19,20,21]` 이라 프론트 6개가 전부 끝나야 시작한다.
탭별 E2E 로 쪼개면 T16 완료 시점에 카드 레이아웃 E2E 를 시작할 수 있다.

### CEO 렌즈

- **폭발 반경 — 통과.** `working_days` NULL 허용이 「미설정 = 현행 유지」를 지키고,
  완료 기준 5가 **대조군 한 쌍**으로 그것을 기계로 잰다. 배포 순간 기존 차트가 바뀌지 않는다.
  ★단 `board_timezone` 도 같은 원칙인데 T12 의 `aggregateByUtcDate → aggregateByBoardDate`
  **이름 변경이 미설정 경로까지 건드린다**. 미설정이면 UTC 라는 것을 이름이 더 이상 안 말한다 —
  KDoc 으로 갚거나 분기를 명시적으로 남길 것. (CONCERN 승격 아님 · 관찰)
- **X10(DC 근거) — 통과.** 「Cloud 아님」을 행마다 표기했고 편차 사유도 적었다.
  계약 §1-4 「주장하지 않는 것까지 적는다」를 지켰다.
- **X9(BC 격리 위반) — 게이트 1 요약에 실렸다.** 다만 **B1 때문에 완화책 ④가 무효**라
  실질 기계 방어선이 **0** 이다. 요약에 그 사실까지 실어야 정직하다.
- **4탭 한 PR 의 대가 — 적혔다.** `## 알고 감수하는 것` 절에 3건이 명시돼 있다.

### NOT in scope

| 항목 | 사유 |
|---|---|
| `Days in column`(J20/J33) | 컬럼 진입 시각을 아무도 기록하지 않는다. 별도 이력 테이블 + issue-tracking 전환 이력 포트가 필요해 마이그레이션이 두 종류가 된다. 편차 X6 |
| 카드 색(J21/J34) | **B3 으로 미결.** 배제하려면 편차 번호를 주고 사유를 적어야 한다 |
| Swimlanes · Quick filters 설정 탭 이관 | `#452` 편차 X3(Maxi 확정) |
| 보드 단위 권한(J8 의 board administrator) | 편차 X8. `#450` 이 T3 이연으로 이미 등재 |
| J44(work type 연관·hidden) | issue-tracking 필드 스킴 영역 |

### What already exists — 재사용 대상

| 자산 | 이 계획이 쓰나 |
|---|---|
| `use-column-settings-drag.ts`(`#452`) | **쓴다** — T19 가 명시. 연속 드롭 lost update 잠금 포함 |
| `board-helpers.ts` 컨테이너 스코프(`#450`) | **쓴다** — T22 가 명시 |
| `BurndownCalculator`(FR-RP-01) | **쓴다** — T11 이 확장 |
| `WorklogService`·`remainingEstimateSeconds` | **쓴다** — 갭 C 의 실데이터 원천 |
| `permissionResolver` 게이트(`BoardController`) | **쓴다** — T8·T13 이 승계. 새 권한 안 만듦 |
| `board_column_states` 분리 선례(`#444`) | **쓴다** — T7 이 같은 방식으로 리포지터리 분리 |
| `TODOS.md` J12~J21 조회 결과 | **못 썼다 → B2** |

### Failure modes — 침묵 실패 후보

| 코드경로 | 실패 양식 | 테스트 | 에러 처리 | 사용자에게 보이나 |
|---|---|---|---|---|
| 카드 레이아웃 동시 저장 | 각자 3개 통과 → 6개 | T2 CHECK | DB 제약 | 보임(400/409) |
| 근무일 전 기간 비근무 | 0 나눗셈 → 500 | 스펙 E2 | `totalDays==0` 합류 | 보임 |
| 타임존만 설정 | 번다운 무변화 | **없음** | 없음 | **침묵** ← C2 인접 |
| 커스텀 필드 미공급 | 카드에 빈 칸 | T20(E4) | 생략 처리 | 보임 |
| 상세 구성이 한쪽만 | 열기 방식에 따라 다름 | **E2E 없음** | 없음 | **침묵** ← C2 |

**critical gap 2건** — 「타임존만 설정」과 「상세 구성 한쪽만」. 둘 다 테스트도 에러 처리도 없고 침묵한다.

### 병렬화 — wave 배치 검증

| wave | task | 모듈 | 병렬 가능 |
|---|---|---|---|
| A | 1~4 | agile-planning 마이그레이션 | 1 → {2,3} → 4 |
| B | 5~6 | shared-kernel → issue-tracking | 5 → 6 (직렬) |
| C | 7~14 | agile-planning main | 7 → {8,9,10,13} → {11,12}; 14 는 6 이후 |
| D | 15~21 | apps/web | 15 → {16,17,18,19} → {20,21} |
| E | 22 | e2e | 16~21 전부 이후 ← **C4** |

**A 와 B 는 완전 독립이라 동시 시작 가능하다** — plan 의 「예상 wave 5」는 직렬 5단계가 아니라
A·B 동시 → C·D → E 로 실질 3~4단계다. 그 사실이 plan 메타에 안 적혀 있다.

★**주의** — 같은 worktree 병렬 dispatch 는 git 인덱스를 공유한다. 경로 한정 커밋
(`git commit -- <경로>`)을 쓰고 `git add` 로 좁히는 것에 의존하지 말 것
([[shared-worktree-git-index-defeats-narrow-git-add]] · 2026-09-04 실측).

### 판정

**초판 판정 — BLOCKER 3 · CONCERNS 4 · critical gap 2.**
`type == migration` 이라 BLOCKER 무시 옵션이 없다(절대 규칙).

### ✅ 처리 결과 (Maxi 확정 2026-09-05 — 「3건 고치고 계약 근본원인까지 닫는다」)

| 항목 | 처리 |
|---|---|
| **B1** 판별식이 실제 위험을 안 지킨다 | T14 재작성. **주 방어선을 `BoardIssueView` 필드 이름 집합 고정**(shared-kernel 계약 테스트)으로 바꿨다 — 포트를 넓히는 것 자체는 허용 경로이므로 **금지가 아니라 가시화**가 처방이다. `com.bts.issue` import 0건은 보조선으로 남겼다 |
| **B2** J 번호 두 벌 | 장부 축(J12~J21)으로 통합. 초판의 J30·J31·J32·J33·J34·J42·J45 를 폐기하고 **번호를 재사용하지 않는다**. plan·스펙 전수 치환 |
| **B2 근본원인** | 계약 §1-0 의 재사용 명령을 **두 헤딩(`## Jira 대조` \| `### 지라 근거`) + `TODOS.md`** 로 넓혔다. `bts-spec/SKILL.md` 가 들고 있던 **낡은 사본을 제거**하고 정본 포인터로 바꿨다 — 사본이 범위 확장을 따라오지 않는 것이 이 저장소의 지배 결함 양식이다. **비-공허 확인** — 새 명령이 `TODOS.md` 를 실제로 잡는다 |
| **B3** 카드 색 누락 | 편차 **X11** 신설. `TODOS.md:1518` 이 남긴 질문(「JQL 기준은 search BC 소관이라 배제 대상인지」)에 **답으로** 배제하고 사유 3건을 적었다 |
| **C1** 서비스 층에 부채 157 복제 | 탭별 서비스 4개로 분리(`CardLayoutSettingsService` 등). wave 병렬성도 함께 산다 |
| **C2** 상세 보기 양쪽 판정이 E2E 에 없음 | **T24 신설** — 모달·사이드패널 양쪽을 재는 spec. critical gap 1건 해소 |
| **C3** 되돌리기 판정 부재 | T4 REFACTOR 에 되돌리기 검증 추가(`DROP` 후 스키마가 적용 전과 같음) |
| **C4** wave E 직렬 꼬리 | E2E 를 T22·T23·T24 로 쪼갰다. T22 는 `depends-on: [16]` 이라 T16 완료 즉시 시작 |
| **critical gap** 「타임존만 설정」 | T23 이 그 경로를 잰다 |
| **critical gap** 「상세 구성 한쪽만」 | T24 가 그 경로를 잰다 |

**재판정 — BLOCKER 0 · CONCERNS 0 · critical gap 0.** task 22 → **24**.
검증 — 가드 7/7 · 판별식 557/557 · doc-index drift 0.


