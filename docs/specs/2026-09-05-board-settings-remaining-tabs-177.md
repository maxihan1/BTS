# 보드 설정 잔여 4탭 — 카드 레이아웃 · 추정 · 작업일 · 상세 보기 (부채 177 · PR 2) — 스펙

> 티어: T3
> slug: board-settings-remaining-tabs-177
> plan: `docs/plans/2026-09-05-board-settings-remaining-tabs-177.md`
> FR: FR-BD-01 · FR-BD-03 · FR-BD-04 · FR-RP-01(갭 D 가 소비처를 바꾼다)

## §0. 이 스펙이 서 있는 자리

`#452`(PR 1)가 Columns 탭 하나를 냈고 부채 **177 은 아직 열려 있다**. 이 스펙은 잔여 4탭을 낸다.
Swimlanes·Quick filters 2탭은 `#452` 가 편차 `X3` 로 「보드 화면 인라인 유지」를 Maxi 확정했으므로
범위가 아니다. **탭바는 이 PR 이 도입한다** — `settings.tsx:89` 가 「두 번째 탭을 만드는 PR 이
탭바를 도입한다」고 적어 뒀다.

**착수 실측이 계획의 전제 하나를 뒤집었다.** 계획 단계에서 「Working days 는 번다운이 없어 값이
안 난다」고 적었는데 **틀렸다.** `FR-RP-01` 은 D 완료이고 `SprintBurndownController` ·
`SprintBurndownService` · `BurndownCalculator` 가 실재한다. 갭 D 는 죽은 설정이 아니라
**이미 있는 차트의 정확도 결함**을 닫는다(§1 S3).

## 사용자 시나리오 (Given-When-Then)

**S1 — 카드에 보고 싶은 필드를 고른다 (갭 B).**
Given 보드 관리자가 보드 설정에 들어와 있다.
When 「카드 레이아웃」 탭에서 표시할 필드를 고르고 저장한다.
Then 보드와 백로그의 카드가 그 구성으로 그려진다. 상한은 **3개**(J31).

**S2 — 추정 방식과 시간 추적을 고른다 (갭 C).**
Given 스크럼 보드의 관리자다.
When 「추정」 탭에서 시간 추적을 `없음` ↔ `잔여 추정 + 소요 시간` 으로 바꾼다.
Then 번다운의 진행 계산이 그 방식을 따른다. **칸반 보드에서는 이 탭이 잠긴다**(J37).

**S3 — 주말·휴일을 번다운에서 뺀다 (갭 D).**
Given 스프린트가 2주(달력 14일)이고 그중 주말이 4일이다.
When 「작업일」 탭에서 표준 근무일을 월~금으로 두고 공휴일 1일을 등록한다.
Then 번다운의 x축과 ideal 선이 **9일**을 기준으로 그려진다.
★현재는 `BurndownCalculator.calculate` 가 `day.plusDays(1)` 로 달력일 전부를 돌고
`computeIdealSeconds` 가 `ChronoUnit.DAYS.between` 으로 ideal 을 내므로 **14일 기준**이다.
즉 이 시나리오가 **지금 red 다** — 갭 D 의 red-first 테스트는 이 한 줄로 서면 된다.

**S4 — 상세 보기 구성을 바꾼다 (갭 E).**
Given 관리자다.
When 상세 화면에 보일 필드를 고른다.
Then 이슈 상세가 그 구성으로 그려진다.
★**이 시나리오는 §제약 C-3 의 결정 전까지 확정되지 않는다.**

## Jira 대조

계약 §1 5단계 산출물. **plan 의 같은 절이 정본이고 여기 표는 그 사본이 아니라 요약이다** —
행 번호·출처 URL·조회일은 plan 을 본다. `docs/plans/2026-09-05-board-settings-remaining-tabs-177.md` §Jira 대조.

### 승계 (재조회 안 함 · 계약 §1-0)

| # | 원문 인용 | 출처 · 조회일 |
|---|---|---|
| **J8** | *"you must be either: a **space administrator** for the location of the board [or] a **board administrator** for the board itself"* | [Configure a company-managed board](https://support.atlassian.com/jira-software-cloud/docs/configure-a-company-managed-board/) · Cloud · 2026-09-03 |
| **J22** | 진입 — *"On the **Board settings** screen, select the desired tab (**Columns**, **Swimlanes**, etc)."* | 동일 · Cloud · 2026-09-04 |

### 이번에 새로 조회 (2026-09-05)

| # | 갭 | 원문 인용 | 출처 · 조회일 |
|---|---|---|---|
| **J31** | B | *"You can configure cards on a board to display up to three additional fields."* | [Customize cards](https://support.atlassian.com/jira-software-cloud/docs/customize-cards/) · Cloud · 2026-09-05 |
| **J32** | B | *"Work item cards have three layers of information ... 1. The work item summary is always at the top on the board and backlog. 2. Any custom fields added to the card are next. 3. Then details about the work item, including work type, priority, assignee, and estimate."* | 동일 · Cloud · 2026-09-05 |
| **J33** | B | *"You can also enable **Days in column** to visually indicate how long a work item's in a column."* | 동일 · Cloud · 2026-09-05 |
| **J36** | C | `Remaining estimate and time spent` 는 *"tracks progress by subtracting the value from the **Time spent** field from the original estimate"* | [Configure estimation and tracking](https://support.atlassian.com/jira-software-cloud/docs/configure-estimation-and-tracking/) · Cloud · 2026-09-05 |
| **J37** | C | *"This setting can only be changed for company-managed scrum teams."* | 동일 · Cloud · 2026-09-05 |
| **J38** | D | *"Select the days your team usually work under **Standard working days**."* | [Configure working days](https://support.atlassian.com/jira-software-cloud/docs/configure-working-days/) · Cloud · 2026-09-05 |
| **J39** | D | *"Specify holidays or one-off dates your team won't be working, select a date using the date picker under **Non-working days**, then select **Add date**."* | 동일 · Cloud · 2026-09-05 |
| **J41** | D | *"Working days are reflected in these reports and gadgets: Burndown Chart, Sprint Report, Epic Report, Version Report, Control Chart"* | 동일 · Cloud · 2026-09-05 |
| **J42** | E | *"customize the work item to show more fields, hide fields, and rearrange the field layout."* | [Configure the work item details](https://support.atlassian.com/jira-software-cloud/docs/configure-the-issue-detail-view/) · Cloud · 2026-09-05 |
| **J44** | E | *"Fields will only appear on a work item if they have been associated with the relevant work type, and are not _hidden_."* | 동일 · Cloud · 2026-09-05 |

### 의도적 편차 (번호는 `#452` 의 X1~X4 를 이어 X5 부터)

| 편차 | 내용 | 근거 |
|---|---|---|
| **X5** | **카드 레이아웃의 「추가 필드」를 커스텀 필드가 아니라 기존 카드 필드의 표시 토글로 낸다.** J31·J32 는 커스텀 필드를 얹는다. | `BoardIssueView`(**shared-kernel** `BoardIssueLookupPort.kt:182~`)가 커스텀 필드를 **나르지 않는다.** 지라대로 하려면 shared-kernel 포트를 넓히고 issue-tracking 이 값을 채워야 해 **한 PR 이 2개 BC 의 main 을 건드린다** — CLAUDE.md 「BC 격리 · 한 PR = 한 BC」와 정면 충돌. 상한 3개(J31)와 3층 구조(J32)는 그대로 지킨다. **C-2 로 Maxi 확인 대상.** |
| **X6** | **`Days in column`(J33)은 이 PR 범위 밖.** | 컬럼 진입 시각을 아무도 기록하지 않는다 — `board_columns`·카드 어디에도 대응 칸이 없어 **별도 이력 테이블**이 필요하다. 4탭에 그것까지 얹으면 마이그레이션이 두 종류가 된다. 후속 등재 대상. |
| **X7** | **작업일 설정을 보드 단위로 둔다.** 지라도 보드 설정이지만 BTS 는 스프린트가 보드에 속하므로 의미가 같은지 확인이 필요했다 — `V506` 이 `sprints.board_id` 를 넣어 **같다**. | 프로젝트 단위로 두면 한 프로젝트의 스크럼/칸반 보드가 서로 다른 달력을 못 갖는다. |

## 기능 요구사항 (FR)

- **R1** 보드 설정 화면에 **탭바**를 도입한다. 탭은 `컬럼`(기존) + 이 PR 의 4탭.
  `#452` 가 「누를 수 있는데 아무 일도 안 일어나는」 탭을 금지했으므로 **5탭 전부 동작해야** 한다.
- **R2 (갭 B)** 카드에 표시할 **추가 필드를 최대 3개** 고른다(J31). 후보는 `BoardCardResponse` 가
  이미 나르는 것 — `epicKey` · `priority` · `labels` · `originalEstimateSeconds` · `assigneeId` · `typeKey`.
  요약(`summary`)은 J32 의 1층이라 **항상 최상단이고 토글 대상이 아니다.**
- **R3 (갭 B)** 구성은 **보드와 백로그에 함께** 적용된다(J32 의 *"on the board and backlog"*).
- **R4 (갭 C)** 시간 추적을 `NONE` / `REMAINING_AND_SPENT` 중 고른다(J36).
  **스크럼 보드에서만 변경 가능**하고 칸반에서는 잠긴다(J37).
- **R5 (갭 D)** 표준 근무일(요일 집합) · 비근무일(날짜 목록) · 타임존을 보드 단위로 저장한다(J38·J39·J40).
- **R6 (갭 D)** `BurndownCalculator` 가 **근무일만** x축으로 쓰고 ideal 선도 근무일 수로 나눈다(J41).
  설정이 없으면 **현재 동작(달력일 전부)을 그대로 유지**한다 — 기존 스프린트의 차트가 조용히 바뀌면 안 된다.
- **R7 (갭 E)** §제약 C-3 결정에 종속. 확정 전까지 요구사항을 쓰지 않는다.

## 비기능 요구사항 (NFR)

- **N1** 보드 조회 응답에 설정을 실어 **추가 왕복을 만들지 않는다.** 카드 레이아웃은 보드·백로그
  모든 렌더에 필요하므로 별도 GET 이면 화면마다 N+1 이 된다.
- **N2** 근무일 계산은 **도메인 순수 함수**로 둔다(`BurndownCalculator` 와 같은 자리).
  서비스에 넣으면 테스트가 Testcontainers 를 타야 한다.
- **N3** `BoardRepository.kt` 는 이미 부채 **157**(줄수 상한 초과)이다. 이 PR 이 그 파일을 더 키우면
  안 된다 — 새 설정은 별도 리포지터리로 뺀다(`#444` 가 `BoardColumnStateRepository` 로 뺀 선례).

## API 인터페이스 (REST)

기존 `PATCH /api/v1/projects/{projectKey}/boards/{boardId}` 를 넓힌다 —
`swimlaneField` 가 이미 이 경로로 바뀌므로 설정 축을 늘리는 것이 같은 모양이다.

```
PATCH /api/v1/projects/{projectKey}/boards/{boardId}
{
  "cardLayoutFields": ["EPIC", "PRIORITY", "ESTIMATE"],   // 갭 B · 최대 3
  "timeTracking": "REMAINING_AND_SPENT",                   // 갭 C · 스크럼만
  "workingDays": {                                         // 갭 D
    "standardDays": ["MON","TUE","WED","THU","FRI"],
    "nonWorkingDates": ["2026-10-03"],
    "timezone": "Asia/Seoul"
  }
}
```

- 400 — `cardLayoutFields` 가 4개 이상(J31) · 미지원 필드 키 · `timezone` 이 IANA 가 아님
- 409 — `timeTracking` 을 칸반 보드에 보냈다(J37)
- 응답은 `GET /boards/{id}` 와 같은 모양으로 설정을 되돌려준다(N1)

## 데이터 모델 변경

**마이그레이션 1개**(V-번호는 착수 시점에 `git log origin/main` 으로 최대값 +1 — 동시 충돌 함정).

```sql
ALTER TABLE boards
  ADD COLUMN card_layout_fields TEXT[] NOT NULL DEFAULT '{}',
  ADD COLUMN time_tracking      VARCHAR(24) NOT NULL DEFAULT 'NONE',
  ADD COLUMN working_days       VARCHAR(3)[] NULL,      -- NULL = 미설정(현행 유지, R6)
  ADD COLUMN board_timezone     VARCHAR(64) NULL;

CREATE TABLE board_non_working_dates (
  board_id UUID NOT NULL REFERENCES boards (id) ON DELETE CASCADE,
  date     DATE NOT NULL,
  PRIMARY KEY (board_id, date)
);
```

★`working_days` 를 `NOT NULL DEFAULT` 로 두지 않는 이유가 **R6 그 자체**다. 기본값을 월~금으로
채우면 **기존 모든 스프린트의 번다운이 배포 순간 바뀐다.** NULL 이 「미설정 = 달력일 전부」다.

## 엣지 케이스

- **E1** 근무일을 **0개**로 저장한다 → 400. 허용하면 ideal 선이 0 나눗셈이다
  (`computeIdealSeconds` 가 `totalDays == 0` 을 이미 특수 처리하지만 그것은 1일 스프린트용 경로다).
- **E2** 스프린트 기간이 **전부 비근무일**이다(전원 휴가 2주) → E1 과 다르다. 저장은 되고 계산에서
  0 나눗셈이 난다. `totalDays == 0` 경로로 합류시킨다.
- **E3** 카드 레이아웃에 4개를 보낸다 → 400(J31).
- **E4** 카드 레이아웃에 고른 필드가 그 이슈에 **없다**(에픽 미소속) → 그 카드에서만 생략한다.
  빈 칸을 그리지 않는다 — J32 의 층 구조가 무너진다.
- **E5** 칸반 보드에 `timeTracking` 을 보낸다 → 409(J37). **404 가 아니다** — 보드는 있고 조작이 막힌 것이다.
- **E6** 보드 종류를 스크럼 → 칸반으로 바꾸면 `time_tracking` 은 어떻게 되나 → **값을 지우지 않는다.**
  되돌리면 살아나야 한다. 읽는 쪽이 `board_type` 을 보고 무시한다.
- **E7** 타임존만 바꾸고 근무일은 미설정이다 → 타임존은 저장되지만 번다운은 현행 유지(R6).
- **E8** 비근무일을 스프린트 **기간 밖**에 등록한다 → 저장은 된다. 계산에서 자연히 무시된다.

## 제약 조건

- **C-1** `BoardRepository.kt` 를 키우지 않는다(N3 · 부채 157).
- **C-2 🛑 Maxi 결정 필요.** 갭 B 를 **X5(기존 필드 토글)** 로 갈지, **지라 패리티(커스텀 필드)** 로
  가서 shared-kernel `BoardIssueLookupPort` 를 넓히고 issue-tracking 을 함께 건드릴지.
  후자는 「한 PR = 한 BC」를 깬다.
- **C-3 🛑 Maxi 결정 필요.** 갭 E(상세 보기)는 지라에서 **보드 설정이 아니다.** J42·J44 와 조회 결과가
  가리킨 경로는 `Settings > Screens` 의 **work type 레이아웃**이고, 그것은 issue-tracking 영역이다.
  선택지 — ① 이 PR 에서 뺀다(3탭) ② 보드 단위 편차로 축소해 낸다 ③ issue-tracking 까지 포함한다.
- **C-4** `Days in column`(J33)은 X6 으로 범위 밖.

## 측정 가능한 완료 기준

1. 보드 설정에 **탭바**가 있고 5탭 전부 동작한다. 비활성 골격 0개.
2. 카드 레이아웃 3개 초과 저장이 **400** 이다(E3).
3. 카드 레이아웃 변경이 **보드와 백로그 양쪽**에 반영된다(R3).
4. 칸반 보드에 `timeTracking` 을 보내면 **409** 다(E5). 404 가 아님을 단언한다.
5. **번다운 red-first.** 근무일 미설정 스프린트의 point 수가 달력일 수와 같고(현행 유지),
   월~금 설정 후 같은 스프린트의 point 수가 **근무일 수와 같다**(R6).
   ★이 두 단언이 **한 쌍**이어야 한다 — 뒤엣것만 두면 「전부 근무일로 치는」 구현도 통과한다.
6. 비근무일 등록이 5번의 계산에서 **한 칸을 더 뺀다**.
7. 스프린트 전 기간이 비근무일이어도 500 이 아니다(E2).
8. 보드 종류를 왕복시켜도 `time_tracking` 값이 살아 있다(E6).
9. `BoardRepository.kt` 줄수가 이 PR 로 **늘지 않는다**(C-1 · 부채 157).
10. 마이그레이션 재실행이 멱등이다 — 「1차 재실행 후 ↔ 2차 재실행 후」로 잰다
    (`#444` 가 「적용 전 ↔ 1차 후」로 재다 옳은 마이그레이션을 red 로 만들 뻔한 자리).

## Sanity Check

스펙을 스스로 흔들었다. **gap 5건** — 3건은 1회 보강했고 2건은 Maxi 결정이다.

### ❓ 발견 — G1. 권한을 한 줄도 안 적었다 (보강함)

J8 이 「space administrator 또는 board administrator」를 요구하는데 스펙에 권한 문장이 없었다.
실측 — 보드 설정 계열은 `BoardApplicationService` 가 아니라 **`BoardController` 가** 막는다
(`:171` · `:207` · `:626`) — `permissionResolver.hasPermission(actor, IssuePermission.SOFT_DELETE,
IssueScope.Project(projectKey))`. `updateBoard` 서비스 메서드는 `actorId` 를 **받지도 않는다.**

- **R8** 4탭의 모든 쓰기는 위 게이트를 **그대로** 탄다. 새 권한을 만들지 않는다.
- **R9** 존재 확인 ↔ 권한 확인 **순서를 기존 경로와 같게** 유지한다. 뒤집으면 403 과 404 의 의미가
  갈리고 로컬은 항상 허용이라 눈에 안 띈다([[permission-assert-before-existence-makes-403-lie]]).
- **편차 X8** — BTS 에 J8 의 「board administrator」에 **대응하는 보드 단위 권한이 없다.**
  프로젝트 단위 권한으로 갈음한다. `#450` 이 `canDelete` 를 프로젝트 판정 1회로 낸 것과 같은 이유이고,
  보드 단위 권한 도입은 그 PR 이 이미 T3 이연으로 적어 둔 별건이다.

### ❓ 발견 — G2. 타임존이 아무 데도 안 쓰인다 (보강함)

`board_timezone` 칸을 만들면서 **그 값을 읽는 곳을 쓰지 않았다.** 그대로 두면
「설정은 되는데 번다운이 안 바뀐다」가 된다 — 갭 D 가 피하려던 바로 그 형태다.

실측 — `SprintBurndownService.aggregateByUtcDate(:139)` 가 worklog 를 `startedOnUtcDate` 로 묶는다.
즉 **일 귀속이 UTC 고정**이라, 한국 팀이 오전 9시에 적은 worklog 가 전날로 붙는 경우가 있다.

- **R10** 보드 타임존이 설정되면 worklog 일 귀속을 **그 타임존 기준**으로 바꾼다.
  미설정이면 현행(UTC) 유지 — R6 과 같은 「미설정 = 현행」 원칙이다.
- **완료 기준 11** 추가. 같은 worklog 가 타임존 설정 전후로 **다른 날짜 칸**에 붙는다.
  ★이 단언은 UTC 경계를 넘는 시각(예: `23:30Z`)의 worklog 로만 성립한다 — 낮 시각으로 쓰면
  두 타임존에서 같은 날이라 **판정을 지워도 통과하는 공허한 테스트**가 된다.

### ❓ 발견 — G5. 시각 검증 기준이 없다 (보강함)

T3 이고 화면이 5탭으로 늘어나는데 눈확인 항목을 안 적었다.

- **E2E** — `board-settings.spec.ts`(기존 3건, `#452`)에 탭바 전환 시나리오를 더한다.
  기존 3건이 **탭바 도입으로 깨지지 않는지**가 먼저다(Columns 탭이 이제 탭 안에 있다).
- **눈확인 4항목** — ①탭바 5탭 렌더 ②카드 레이아웃 변경이 보드·백로그 양쪽에 반영 ③칸반에서 추정 탭 잠금
  ④근무일 변경 후 번다운 x축 축소.
- **E2E 셀렉터** — 보드 헤더 `⋯` 와 사이드바 보드 `⋯` 가 접근성 이름이 같다(캠페인 R10).
  `#450` 이 만든 컨테이너 스코프 헬퍼 `apps/web/e2e/fixtures/board-helpers.ts` 를 **그대로 쓴다.**

### 🛑 Maxi 결정 대기 2건

**C-2 (갭 B 범위).** 카드 「추가 필드」를 X5(기존 필드 토글) 로 갈지, 지라 패리티(커스텀 필드)로 가서
**shared-kernel `BoardIssueLookupPort` 를 넓히고 issue-tracking 을 함께 건드릴지.**
후자는 「한 PR = 한 BC」를 깬다.

**C-3 (갭 E 범위).** 상세 보기는 지라에서 보드 설정이 아니다(J42·J44 · `Settings > Screens`).
① 이 PR 에서 뺀다(3탭) ② 보드 단위 편차로 축소 ③ issue-tracking 까지 포함.

**이 2건이 정해지기 전에는 `/bts-plan` 으로 넘어가지 않는다** — task 분해가 범위에 종속된다.
