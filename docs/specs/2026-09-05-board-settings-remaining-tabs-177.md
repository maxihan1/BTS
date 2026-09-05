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
Then 보드와 백로그의 카드가 그 구성으로 그려진다. 상한은 **3개**(J17).

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
★**2026-09-05 재작성.** 초판은 §1-0 재사용을 놓쳐 `TODOS.md:1499~1516` 의 J12~J21 을 못 보고
4탭을 재조회했다(리뷰 BLOCKER B2). 번호를 장부 축으로 통합했고 계약 §1-0 의 명령도 함께 고쳤다.
행 번호·출처 URL·조회일은 plan 을 본다. `docs/plans/2026-09-05-board-settings-remaining-tabs-177.md` §Jira 대조.

### 승계 (재조회 안 함 · 계약 §1-0)

| # | 원문 인용 | 출처 · 조회일 |
|---|---|---|
| **J8** | *"you must be either: a **space administrator** for the location of the board [or] a **board administrator** for the board itself"* | [Configure a company-managed board](https://support.atlassian.com/jira-software-cloud/docs/configure-a-company-managed-board/) · Cloud · 2026-09-03 |
| **J22** | 진입 — *"On the **Board settings** screen, select the desired tab (**Columns**, **Swimlanes**, etc)."* | 동일 · Cloud · 2026-09-04 |

### 이번에 새로 조회 (2026-09-05)

| # | 갭 | 원문 인용 | 출처 · 조회일 |
|---|---|---|---|
| **J17** | B | *"You can configure cards on a board to display up to three additional fields."* | [Customize cards](https://support.atlassian.com/jira-software-cloud/docs/customize-cards/) · Cloud · 2026-09-05 |
| **J19** | B | *"Work item cards have three layers of information ... 1. The work item summary is always at the top on the board and backlog. 2. Any custom fields added to the card are next. 3. Then details about the work item, including work type, priority, assignee, and estimate."* | 동일 · Cloud · 2026-09-05 |
| **J20** | B | *"You can also enable **Days in column** to visually indicate how long a work item's in a column."* | 동일 · Cloud · 2026-09-05 |
| **J36** | C | `Remaining estimate and time spent` 는 *"tracks progress by subtracting the value from the **Time spent** field from the original estimate"* | [Configure estimation and tracking](https://support.atlassian.com/jira-software-cloud/docs/configure-estimation-and-tracking/) · Cloud · 2026-09-05 |
| **J37** | C | *"This setting can only be changed for company-managed scrum teams."* | 동일 · Cloud · 2026-09-05 |
| **J38** | D | *"Select the days your team usually work under **Standard working days**."* | [Configure working days](https://support.atlassian.com/jira-software-cloud/docs/configure-working-days/) · Cloud · 2026-09-05 |
| **J39** | D | *"Specify holidays or one-off dates your team won't be working, select a date using the date picker under **Non-working days**, then select **Add date**."* | 동일 · Cloud · 2026-09-05 |
| **J41** | D | *"Working days are reflected in these reports and gadgets: Burndown Chart, Sprint Report, Epic Report, Version Report, Control Chart"* | 동일 · Cloud · 2026-09-05 |
| **J15** | E | *"customize the work item to show more fields, hide fields, and rearrange the field layout."* | [Configure the work item details](https://support.atlassian.com/jira-software-cloud/docs/configure-the-issue-detail-view/) · Cloud · 2026-09-05 |
| **J44** | E | *"Fields will only appear on a work item if they have been associated with the relevant work type, and are not _hidden_."* | 동일 · Cloud · 2026-09-05 |
| **J18** | B | **뷰별 구성** — *"The fields can be different for the backlog and Active sprints, if you are using a Scrum board."* | [Customize cards](https://support.atlassian.com/jira-software-cloud/docs/customize-cards/) · Cloud · 2026-09-05 |
| **J46** | E | **진입** — *"Go to the desired board and select **Board** > **Configure**. In the left-side menu, select **Issue Detail View**."* | [Configuring the issue view](https://confluence.atlassian.com/jirasoftwareserver/configuring-the-issue-view-938845334.html) · **DC (Cloud 아님)** · 2026-09-05 |
| **J47** | E | **필드 그룹 4종** — *"different groups of fields: General fields, Date fields, People, and Links."* | 동일 · **DC (Cloud 아님)** · 2026-09-05 |
| **J48** | E | **조작** — *"To add a new field, select the field from one of the dropdown menus, and then select **Add.**"* · *"If you want to hide a field from the issue details view, select **Delete**."* · 순서는 *"drag and drop the field up or down in the list."* | 동일 · **DC (Cloud 아님)** · 2026-09-05 |
| **J49** | E | **권한** — *"as a user with the **Jira admin** or a **board admin** permissions."* | 동일 · **DC (Cloud 아님)** · 2026-09-05 |

### 의도적 편차 (번호는 `#452` 의 X1~X4 를 이어 X5 부터)

| 편차 | 내용 | 근거 |
|---|---|---|
| ~~X5~~ | **폐기.** 「기존 필드 토글」안이었다. **Maxi 확정 2026-09-05 — 지라 패리티로 간다**(커스텀 필드). 지운 것이 아니라 뒤집힌 것이라 행을 남긴다. | — |
| **X9** | **이 PR 은 여러 BC 의 main 을 건드린다.** `shared-kernel`(`BoardIssueLookupPort` 확장) · `issue-tracking`(커스텀 필드 값 공급 · 상세 레이아웃) · `agile-planning` · `apps/web`. | **Maxi 확정 2026-09-05.** CLAUDE.md 「BC 격리 — 한 PR = 한 BC」와 정면 충돌하며 그 사실을 제시한 뒤 「지라 클라우드와 동일한 스펙으로」가 확정됐다. **게이트 1 요약에 그대로 싣는다.** 완화책은 §제약 C-5. |
| **X11** | **카드 색(J21)은 범위 밖.** J21 은 색 기준 4종(work type · priority · assignee · JQL)을 말한다. | 리뷰 BLOCKER **B3** 로 신설. ① 지라에서 카드 색은 Card layout 탭이 **아니라 별도 설정**이고 부채 177 의 7탭 목록에 없다 ② **JQL 기준은 `search-export-import` BC 소관**이라 건드리면 BC 를 하나 더 늘린다(X9 가 이미 4개) ③ `TODOS.md:1518` 이 「JQL 기준은 search BC 소관이라 배제 대상인지」를 착수 시 풀 질문으로 남겼고 **이 편차가 그 답이다**. 후속 등재. |
| **X10** | **갭 E 의 근거가 DC 문서다.** J46~J49 는 `confluence.atlassian.com`(Data Center)이고 Cloud 문서는 이 탭을 work type 레이아웃(`Settings > Screens`)으로 옮겼다. | 계약 §1 표가 DC 를 「Cloud 가 아님을 행에 표기해야 인정」으로 허용한다. **보드 단위 Issue Detail View 는 Cloud 에 대응 화면이 없다** — 그럼에도 보드 설정으로 내는 것이 Maxi 확정이므로, 근거가 DC 뿐이라는 사실을 여기 명시한다(계약 §1-4 「주장하지 않는 것까지 적는다」). |
| **X6** | **`Days in column`(J20)은 이 PR 범위 밖.** | 컬럼 진입 시각을 아무도 기록하지 않는다 — `board_columns`·카드 어디에도 대응 칸이 없어 **별도 이력 테이블**이 필요하다. 4탭에 그것까지 얹으면 마이그레이션이 두 종류가 된다. 후속 등재 대상. |
| **X7** | **작업일 설정을 보드 단위로 둔다.** 지라도 보드 설정이지만 BTS 는 스프린트가 보드에 속하므로 의미가 같은지 확인이 필요했다 — `V506` 이 `sprints.board_id` 를 넣어 **같다**. | 프로젝트 단위로 두면 한 프로젝트의 스크럼/칸반 보드가 서로 다른 달력을 못 갖는다. |

## 기능 요구사항 (FR)

- **R1** 보드 설정 화면에 **탭바**를 도입한다. 탭은 `컬럼`(기존) + 이 PR 의 4탭.
  `#452` 가 「누를 수 있는데 아무 일도 안 일어나는」 탭을 금지했으므로 **5탭 전부 동작해야** 한다.
- **R2 (갭 B)** 카드에 표시할 **추가 필드를 최대 3개** 고른다(J17). 후보는 **커스텀 필드**(J19 의 2층)와
  `BoardCardResponse` 가 이미 나르는 표준 필드 둘 다이다. 요약(`summary`)은 J19 의 1층이라
  **항상 최상단이고 토글 대상이 아니다.**
- **R2b (갭 B · cross-BC)** 커스텀 필드 값은 **shared-kernel `BoardIssueLookupPort.BoardIssueView`**
  를 넓혀 나른다(`:182~`). `issue-tracking` 이 그 필드를 채운다 — `FR-IS-10` 의 `customFields`
  (`Record<String, Any?>`)가 원천이다. **직접 import 가 아니라 기존 포트 확장**이라는 점이 중요하다.
- **R3 (갭 B)** ★**구성은 뷰마다 따로다.** 스크럼 보드는 **백로그**와 **활성 스프린트**가 서로 다른
  필드 집합을 가질 수 있다(J18 원문). 칸반은 보드 뷰 하나뿐이다.
  **초안의 「보드와 백로그에 함께 적용」은 오독이었다** — J19 의 *"on the board and backlog"* 는
  요약이 **항상 맨 위**라는 서술이지 구성 공유가 아니다.
- **R4 (갭 C)** 시간 추적을 `NONE` / `REMAINING_AND_SPENT` 중 고른다(J36).
  **스크럼 보드에서만 변경 가능**하고 칸반에서는 잠긴다(J37).
- **R5 (갭 D)** 표준 근무일(요일 집합) · 비근무일(날짜 목록) · 타임존을 보드 단위로 저장한다(J38·J39·J40).
- **R6 (갭 D)** `BurndownCalculator` 가 **근무일만** x축으로 쓰고 ideal 선도 근무일 수로 나눈다(J41).
  설정이 없으면 **현재 동작(달력일 전부)을 그대로 유지**한다 — 기존 스프린트의 차트가 조용히 바뀌면 안 된다.
- **R7 (갭 E)** 상세 보기 구성을 **보드 단위**로 둔다(J46). 필드를 **4개 그룹**으로 나눠 보여준다 —
  `General fields` · `Date fields` · `People` · `Links`(J47).
- **R7b (갭 E)** 조작 3종(J48) — 드롭다운에서 골라 **추가** · **삭제**(= 상세에서 숨김) ·
  **드래그로 순서 변경**. `#452` 의 상태 매핑 드래그와 같은 상호작용이라
  `use-column-settings-drag.ts` 의 잠금 패턴을 재사용한다(연속 드롭 lost update 방어).
- **R7c (갭 E)** 구성은 **이슈 상세 화면**이 소비한다. `#455` 가 모달↔사이드패널 토글을 냈으므로
  **두 표현 모두** 같은 구성을 읽어야 한다 — 한쪽만 반영되면 「같은 이슈가 열기 방식에 따라 다르게 보인다」.

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
  "cardLayout": {                                          // 갭 B · 뷰마다 따로(J18)
    "BOARD":   ["EPIC", "PRIORITY", "cf_story_points"],
    "BACKLOG": ["ESTIMATE"]
  },
  "timeTracking": "REMAINING_AND_SPENT",                   // 갭 C · 스크럼만
  "workingDays": {                                         // 갭 D
    "standardDays": ["MON","TUE","WED","THU","FRI"],
    "nonWorkingDates": ["2026-10-03"],
    "timezone": "Asia/Seoul"
  },
  "detailViewFields": {                                    // 갭 E · 그룹 4종(J47)
    "GENERAL": ["summary", "status", "cf_severity"],
    "DATE":    ["dueDate"],
    "PEOPLE":  ["assignee", "reporter"],
    "LINKS":   ["issueLinks"]
  }
}
```

- 400 — 한 뷰의 `cardLayout` 이 4개 이상(J17) · 미지원 필드 키 · `timezone` 이 IANA 가 아님 ·
  칸반 보드에 `BACKLOG` 스코프를 보냈다(칸반은 보드 뷰 하나뿐)
- 409 — `timeTracking` 을 칸반 보드에 보냈다(J37)
- 응답은 `GET /boards/{id}` 와 같은 모양으로 설정을 되돌려준다(N1)

## 데이터 모델 변경

**마이그레이션 1개**(V-번호는 착수 시점에 `git log origin/main` 으로 최대값 +1 — 동시 충돌 함정).

```sql
ALTER TABLE boards
  ADD COLUMN time_tracking  VARCHAR(24) NOT NULL DEFAULT 'NONE',
  ADD COLUMN working_days   VARCHAR(3)[] NULL,   -- NULL = 미설정(현행 유지, R6)
  ADD COLUMN board_timezone VARCHAR(64) NULL;

CREATE TABLE board_non_working_dates (
  board_id UUID NOT NULL REFERENCES boards (id) ON DELETE CASCADE,
  date     DATE NOT NULL,
  PRIMARY KEY (board_id, date)
);

-- 갭 B. ★뷰마다 따로다(J18) — 배열 한 칸으로는 못 담는다.
CREATE TABLE board_card_layout_fields (
  board_id   UUID    NOT NULL REFERENCES boards (id) ON DELETE CASCADE,
  view_scope VARCHAR(16) NOT NULL,   -- 'BOARD' | 'BACKLOG'
  position   SMALLINT NOT NULL,      -- 0..2 — 상한 3(J17)을 DB 가 지킨다
  field_key  VARCHAR(128) NOT NULL,  -- 표준 필드 키 또는 커스텀 필드 키
  PRIMARY KEY (board_id, view_scope, position),
  CHECK (position BETWEEN 0 AND 2),
  CHECK (view_scope IN ('BOARD','BACKLOG'))
);

-- 갭 E. 그룹 4종(J47) · 순서(J48).
CREATE TABLE board_detail_view_fields (
  board_id    UUID    NOT NULL REFERENCES boards (id) ON DELETE CASCADE,
  field_group VARCHAR(16) NOT NULL,  -- 'GENERAL' | 'DATE' | 'PEOPLE' | 'LINKS'
  position    SMALLINT NOT NULL,
  field_key   VARCHAR(128) NOT NULL,
  PRIMARY KEY (board_id, field_group, position),
  CHECK (field_group IN ('GENERAL','DATE','PEOPLE','LINKS'))
);
```

★**상한 3개를 `CHECK (position BETWEEN 0 AND 2)` 로 DB 에 박는다.** 서비스 검증만 두면
두 관리자가 동시에 저장할 때 각자 3개를 통과시켜 6개가 들어간다 — `#444` 가 `X1` 경합에서
「사전 검사는 DB 제약을 대신하지 않는다」로 이미 이름 붙인 양식이다.

★`board_card_layout_fields` 가 **비어 있으면 현행 카드**를 그린다. `working_days` 의 NULL 과 같은
원칙 — 배포 순간 모든 보드의 카드가 바뀌면 안 된다.

★`working_days` 를 `NOT NULL DEFAULT` 로 두지 않는 이유가 **R6 그 자체**다. 기본값을 월~금으로
채우면 **기존 모든 스프린트의 번다운이 배포 순간 바뀐다.** NULL 이 「미설정 = 달력일 전부」다.

## 엣지 케이스

- **E1** 근무일을 **0개**로 저장한다 → 400. 허용하면 ideal 선이 0 나눗셈이다
  (`computeIdealSeconds` 가 `totalDays == 0` 을 이미 특수 처리하지만 그것은 1일 스프린트용 경로다).
- **E2** 스프린트 기간이 **전부 비근무일**이다(전원 휴가 2주) → E1 과 다르다. 저장은 되고 계산에서
  0 나눗셈이 난다. `totalDays == 0` 경로로 합류시킨다.
- **E3** 카드 레이아웃에 4개를 보낸다 → 400(J17).
- **E4** 카드 레이아웃에 고른 필드가 그 이슈에 **없다**(에픽 미소속) → 그 카드에서만 생략한다.
  빈 칸을 그리지 않는다 — J19 의 층 구조가 무너진다.
- **E5** 칸반 보드에 `timeTracking` 을 보낸다 → 409(J37). **404 가 아니다** — 보드는 있고 조작이 막힌 것이다.
- **E6** 보드 종류를 스크럼 → 칸반으로 바꾸면 `time_tracking` 은 어떻게 되나 → **값을 지우지 않는다.**
  되돌리면 살아나야 한다. 읽는 쪽이 `board_type` 을 보고 무시한다.
- **E7** 타임존만 바꾸고 근무일은 미설정이다 → 타임존은 저장되지만 번다운은 현행 유지(R6).
- **E8** 비근무일을 스프린트 **기간 밖**에 등록한다 → 저장은 된다. 계산에서 자연히 무시된다.

## 제약 조건

- **C-1** `BoardRepository.kt` 를 키우지 않는다(N3 · 부채 157).
- **C-2 ✅ 확정(2026-09-05).** 갭 B 는 **지라 패리티** — 커스텀 필드를 얹는다(R2·R2b).
  shared-kernel `BoardIssueLookupPort` 확장 + issue-tracking 값 공급을 포함한다.
- **C-3 ✅ 확정(2026-09-05).** 갭 E 는 **지라와 동일한 스펙** — 보드 단위 Issue Detail View 로 낸다(R7~R7c).
  근거가 DC 문서뿐이라는 사실은 편차 **X10** 에 명시했다.
- **C-5 (X9 완화책).** 여러 BC 를 건드리는 만큼 **BC 경계를 코드로 지킨다.**
  ① `agile-planning` 은 `issue-tracking` 을 **직접 import 하지 않는다** — shared-kernel 포트만 쓴다.
  ② 포트 확장은 **필드 추가만**이고 기존 시그니처를 바꾸지 않는다(다른 소비자 회귀 0).
  ③ BC 별 커밋을 **분리**해 되돌리기 단위를 남긴다.
  ④ 판별식으로 ①을 강제한다 — `agile-planning` 소스에 `com.bts.issue` import 가 0건임을 잰다.
- **C-4** `Days in column`(J20)은 X6 으로 범위 밖.

## 측정 가능한 완료 기준

1. 보드 설정에 **탭바**가 있고 5탭 전부 동작한다. 비활성 골격 0개.
2. 카드 레이아웃 3개 초과 저장이 **400** 이고, **DB CHECK 도 독립으로** 막는다 —
   서비스 검증을 지운 대조군이 제약 위반으로 죽는 것을 확인한다(공허 방지).
3. ★**뷰별 분리 판정.** 스크럼 보드에서 `BOARD` 구성과 `BACKLOG` 구성을 **다르게** 저장하면
   보드 카드와 백로그 카드가 서로 다른 필드를 그린다(J18).
   같은 값을 넣고 재면 「구성을 공유하는」 잘못된 구현도 통과한다 — **일부러 다르게** 넣는다.
3b. **커스텀 필드가 카드에 뜬다**(R2b). shared-kernel 포트 확장이 실제로 값을 나르는지를
   커스텀 필드 하나로 잰다 — 표준 필드만으로는 포트를 안 넓혀도 통과한다.
4. 칸반 보드에 `timeTracking` 을 보내면 **409** 다(E5). 404 가 아님을 단언한다.
5. **번다운 red-first.** 근무일 미설정 스프린트의 point 수가 달력일 수와 같고(현행 유지),
   월~금 설정 후 같은 스프린트의 point 수가 **근무일 수와 같다**(R6).
   ★이 두 단언이 **한 쌍**이어야 한다 — 뒤엣것만 두면 「전부 근무일로 치는」 구현도 통과한다.
6. 비근무일 등록이 5번의 계산에서 **한 칸을 더 뺀다**.
7. 스프린트 전 기간이 비근무일이어도 500 이 아니다(E2).
8. 보드 종류를 왕복시켜도 `time_tracking` 값이 살아 있다(E6).
9. `BoardRepository.kt` 줄수가 이 PR 로 **늘지 않는다**(C-1 · 부채 157).
9b. **BC 경계 판별식**(C-5 ④) — `agile-planning` 의 `src/main` 에 `com.bts.issue` import 가 **0건**이다.
   포트 확장이 직접 의존으로 새지 않았음을 기계가 지킨다.
9c. **상세 보기 구성이 모달과 사이드패널 양쪽에** 반영된다(R7c · `#455`).
   한쪽만 재면 다른 쪽이 조용히 갈린다.
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

### ✅ Maxi 결정 2건 — 확정 (2026-09-05)

**둘 다 「지라 클라우드와 동일한 스펙」으로 확정됐다.**

- **C-2 → 지라 패리티.** 커스텀 필드를 카드에 얹는다. shared-kernel 포트 확장 + issue-tracking 값 공급.
- **C-3 → 지라와 동일.** 보드 단위 Issue Detail View 로 낸다(J46~J49 · DC 근거는 X10 에 명시).

**대가를 그대로 적는다.** 이 PR 은 `shared-kernel` · `issue-tracking` · `agile-planning` · `apps/web`
넷을 건드려 CLAUDE.md 「BC 격리 — 한 PR = 한 BC」를 깬다(**X9**). 그 사실을 제시한 뒤 확정된
결정이므로 되묻지 않고, **게이트 1 요약에 그대로 싣는다.** 완화책은 C-5 네 항목이고 그중
④(판별식으로 직접 import 0건 강제)가 유일한 **기계** 방어선이다.

### ❓ 발견 — G6. 재조회가 R3 을 뒤집었다 (교정함)

C-2·C-3 확정 후 지라를 다시 조회하다 **초안의 R3 이 틀렸음**을 찾았다.
초안은 「구성이 보드와 백로그에 함께 적용된다」였는데 원문은
*"The fields can be different for the backlog and Active sprints, if you are using a Scrum board."*(J18) 다.
J19 의 *"on the board and backlog"* 를 「구성 공유」로 읽은 오독이었다 — 그 문장은 **요약이 항상 맨 위**라는 서술이다.
데이터 모델이 배열 한 칸에서 `board_card_layout_fields(view_scope)` 로 바뀌었고,
완료 기준 3 이 「일부러 다르게 넣어 재는」 판정으로 바뀌었다.
