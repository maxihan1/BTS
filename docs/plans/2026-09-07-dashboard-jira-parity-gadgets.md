# 대시보드 지라 클라우드 패리티 + 가젯 4종 활성화 (FR-DB-01 · FR-DB-02)

> 티어: T2
> slug: dashboard-jira-parity-gadgets
> type: ui
> agent: frontend-engineer
> 생성: 2026-09-07

## Brief

**Maxi 원문** — 「대시보드 차트랑 엑티비티는 왜 준비중으로 나오는거지?」 →
「6종 추가로 구현하려면 어느정도 소요되지?」 →
「프로젝트 전체로 하고 지라 클라우드와 동일한 스펙으로 진행하자」 →
「대시보드 UI/UX 와 디자인을 지라 클라우드와 동일하게 해야해」 →
「가젯 2종? 6종 아니야?」

**발단** — 대시보드 가젯 카탈로그 12종 중 6종이 `enabled=false` 라 「준비 중」으로 뜬다.
버그가 아니라 FR-DB-02 가 MVP 6종만 켜고 나머지를 후속으로 미룬 결과다
(`docs/plans/2026-06-29-fr-db-02-gadgets.md:33` — 「sprint_burndown(FR-RP-01 의존)·created_vs_resolved·activity/comments는 후속」).

**Maxi 결정 3건**
1. **pie/bar 스코프 축소** — `filterId`/`aql` 이 아니라 `projectKey`(프로젝트 전체). 기존
   `GET /api/v1/projects/{key}/summary` 의 `statusOverview`/`priorityBreakdown`/`typesOfWork`/`teamWorkload`
   를 재사용한다. 신규 집계 API 를 만들지 않는다.
2. **지라 클라우드 패리티** — 대시보드 UI/UX·디자인을 지라와 동일 스펙으로.
   절차는 `docs/design/jira-parity-contract.md`.
3. **3 PR 분할** — 아래 「범위」 참조.

## 범위 — PR1 of 3

미구현 6종 중 **백엔드 신규 작업이 0인 4종**만 이 PR 에서 켠다. 나머지 2종은 issue-tracking BC
엔드포인트 신설이 필요해 `한 PR = 한 BC` 규칙상 분리한다.

| 가젯 | 이 PR | 재사용할 기존 자산 | 백엔드 신규 |
|---|---|---|---|
| `sprint_burndown` | ✅ | `GET /api/v1/sprints/{id}/burndown` · `BurndownChart.tsx` | 0 |
| `activity_stream` | ✅ | `GET /api/v1/projects/{key}/activity` · `ProjectActivityFeed.tsx` | 0 |
| `pie_chart` | ✅ | `GET /api/v1/projects/{key}/summary` 4종 집계 | 0 |
| `bar_chart` | ✅ | 〃 | 0 |
| `comments_recent` | ❌ PR2/PR3 | — | 프로젝트 스코프 엔드포인트 + 인덱스 |
| `created_vs_resolved` | ❌ PR2/PR3 | `CfdCalculator` 선례 | 시계열 API |

**후속** — PR2 = issue-tracking BC 에 위 2종의 백엔드 API 신설(T3, 인덱스 마이그레이션 포함) ·
PR3 = notification+web 에서 나머지 2종 활성화.

## 건드리는 표면 (detect-tier 실측 T2 · UNMAPPED 0)

- `BE_MAIN` — `backend/modules/notification/.../dashboard/domain/GadgetType.kt`
  (SPRINT_BURNDOWN·ACTIVITY_STREAM·PIE_CHART·BAR_CHART `enabled=false→true` + config 필드 조정)
- `FE_SRC` — `apps/web/src/components/dashboard/**`
- `TEST` · `DOC`
- 마이그레이션 **0건 예상** (신규 테이블·컬럼 없음)

## 알려진 함정 — `enabled` 를 켜면 red 되는 기존 테스트

「MVP 6종」을 **개수로 박아둔** 단언들이다. 4종을 켜면 6→10 이 된다.

- `DashboardControllerTest.kt:434` — `enabled==true` 를 `hasSize(6)` 로 단언
- `DashboardGadgetIntegrationTest.kt:232` — 같은 `hasSize(6)`
- `GadgetTypeTest.kt:41` — 「MVP 6종 enabled=true, 나머지 6종 enabled=false」
- `DashboardGadgetIntegrationTest.kt:197` — `pie_chart` POST → 400 단언.
  **이 PR 이 pie_chart 를 켜므로 이 테스트는 의미가 뒤집힌다** — 여전히 꺼져 있는 타입으로 교체해야 한다.
- `AnonymousLayoutSanitizerTest.kt:68` — 공개 대시보드에서 데이터 가젯이 `requiresAuth` 플레이스홀더로
  치환되는지. 신규 4종도 `PublicGadgetRenderer` 화이트리스트(`text_widget`/`link_list`) 밖이어야 한다.

★메모리 `[[two-lists-never-check-each-other]]` 해당 — 「카탈로그 enabled 목록」과 「GadgetRenderer 가
실제로 그리는 목록」이 서로를 안 본다. `enabled=true` 인데 렌더러에 `case` 가 없으면 사용자는
「지원되지 않는 가젯입니다」를 본다. 이 PR 은 차집합 판별식으로 못 박는다.

## Jira 대조

Jira Cloud 실물 조회로 확정한 규칙. 기억으로 다시 정하지 않는다.
승계 — `docs/plans/2026-09-03-project-summary-activity.md` 의 J4-1~J4-4(프로젝트 요약 화면 집계)는
`pie_chart`/`bar_chart` 가 재사용할 `summary` API 의 근거이므로 **출처·조회일 그대로 승계**한다.
아래는 **대시보드·가젯 표면**을 새로 조회한 결과다.

| # | 항목 | 원문·근거 | 출처 | 조회일 | 구분 |
|---|---|---|---|---|---|
| JD-1 | 가젯 추가 동선 | "Select **Edit**" → "Select **Add gadget**" → "Use the gadget wizard to browse the list of gadgets and select **Add**" — **보기/편집이 분리된 모드**다 | [add and customize gadgets](https://support.atlassian.com/jira-software-cloud/docs/add-and-customize-gadgets/) | 2026-09-07 | Cloud |
| JD-2 | 새 가젯의 삽입 위치 | 새로 추가한 가젯은 "at the top of the leftmost column on your dashboard" 에 놓인다 | 같은 문서 | 2026-09-07 | Cloud |
| JD-3 | 가젯 단위 메뉴 | "Select **More actions** (...), then select **Duplicate** for the gadget" — 타일마다 `⋯` 드롭다운이 있다 | 같은 문서 | 2026-09-07 | Cloud |
| JD-4 | 가젯 설정 | "configure each gadget to customize the data displayed and how often it refreshes" — **갱신 주기가 가젯 설정 항목**이다 | 같은 문서 | 2026-09-07 | Cloud |
| JD-5 | 레이아웃은 컬럼 프리셋 | "Select **Change layout**. Choose your preferred layout." — "three columns instead of two, for example". 자유 배치가 아니라 **컬럼 수 프리셋 선택**이다 | [create and edit dashboards](https://support.atlassian.com/jira-software-cloud/docs/create-and-edit-dashboards/) | 2026-09-07 | Cloud |
| JD-6 | `pie_chart` 스코프·그룹 기준 | "Displays work items from a **space or work item filter**, grouped by a statistic type, in pie-chart format" · "grouped by any statistic type (e.g. Status, Priority, Assignee, etc)" | [dashboard gadgets](https://support.atlassian.com/jira-cloud-administration/docs/use-dashboard-gadgets/) | 2026-09-07 | Cloud |
| JD-7 | `activity_stream` 내용 | "Displays a summary of your recent activity." | 같은 문서 | 2026-09-07 | Cloud |
| JD-8 | `sprint_burndown` 내용 | "Sprint burndown chart to track remaining work." | 같은 문서 | 2026-09-07 | Cloud |
| JD-9 | `created_vs_resolved` 내용 | "Displays a difference chart of the work items created vs resolved over a given period." — **차이(difference) 차트**이지 두 선의 단순 병치가 아니다 | 같은 문서 | 2026-09-07 | Cloud |
| JD-10 | `comments_recent` 대응 부재 | 가젯 카탈로그에 "Recent Comments"·"Comments" 라는 이름의 가젯이 **없다**. Jira 는 최근 댓글을 Activity Stream 이 흡수한다 | 같은 문서 | 2026-09-07 | Cloud |

### BTS 현재 상태와의 조작 갭 (실측)

| # | Jira | BTS 현재 | 이 PR |
|---|---|---|---|
| JD-1 | 보기 모드 → `Edit` → 편집 모드 | `canEdit` 이면 **항상** 드래그/리사이즈 활성 (`DashboardGrid.tsx:163-164`) — 모드 토글 없음 | 스펙에서 판단 |
| JD-3 | 타일마다 `⋯` More actions (Duplicate 포함) | 삭제 버튼(`Trash2`) 단독 (`DashboardTile.tsx:4`) | 스펙에서 판단 |
| JD-4 | 가젯별 갱신 주기 설정 | 전 가젯 고정 `staleTime` 30초 (`useGadgetData.ts` `GADGET_STALE_TIME`) | 스펙에서 판단 |
| JD-5 | 컬럼 프리셋 (`Change layout`) | react-grid-layout **12컬럼 자유 배치** (`DashboardGrid.tsx:1`) | 스펙에서 판단 |
| JD-6 | space **또는** filter 스코프 | `pie_chart` config 가 `filterId`/`aql` 만 선언, `projectKey` 없음 (`GadgetType.kt:163`) | **채택 — projectKey 추가** |

### 의도적 편차

- **X-JD-1 — `pie_chart`/`bar_chart` 를 `projectKey` 스코프로만 낸다.** JD-6 의 Jira 실물은
  space(프로젝트) **와** filter 둘 다 받지만, 이 PR 은 프로젝트 스코프만 구현한다.
  Maxi 결정 — 기존 `GET /api/v1/projects/{key}/summary` 집계를 재사용하면 신규 집계 API 가 0이 된다.
  **필터 스코프는 포기가 아니라 이연**이며, Jira 의 절반만 구현했다는 사실을 여기 남긴다.
- **X-JD-2 — `bar_chart` 는 Jira 카탈로그에 독립 항목으로 없다.** JD-6 의 서술은 pie 형식만 말한다.
  BTS 의 `bar_chart` 는 **대응 없음 — ADS 의 데이터 시각화 패턴 준용**으로 간다.
  근거는 같은 통계 그룹핑을 막대로 낸다는 것뿐이고, 「Jira Cloud 가 그렇게 한다」고 주장하지 않는다.
- **X-JD-3 — 제목 인라인 편집을 유지한다.** Jira 에는 타일 제목을 클릭해 고치는 조작이 없다
  (JD-3 의 `⋯` 메뉴에도 rename 이 문서화돼 있지 않다). BTS 선재 기능이므로 제거하지 않는다.

- **X-JD-4 — 레이아웃은 12컬럼 자유 배치를 유지한다.** JD-5 의 Jira 는 컬럼 프리셋
  (`Change layout`, 2/3컬럼)이지만 BTS 는 react-grid-layout 자유 배치를 계승한다.
  Maxi 결정(M-2) — 프리셋으로 바꾸면 기존 대시보드의 `layout` JSON 마이그레이션이 필요해
  T3 으로 승격되고, 자유 배치가 프리셋보다 표현력이 넓다. **Jira 보다 자유롭다**는 방향의 편차다.
  대신 JD-1(보기/편집 모드)과 JD-3(타일 `⋯` 메뉴)은 Jira 를 그대로 따른다.
- **X-JD-5 — `activity_stream` 은 프로젝트 기준이다.** JD-7 의 Jira 원문은 "a summary of
  **your** recent activity" 로 **보는 사람** 기준인데, BTS 가 재사용하는
  `GET /projects/{key}/activity` 는 **프로젝트** 기준이다. 사용자 기준 스트림 API 가 없고,
  만들면 이 PR 의 「백엔드 신규 0」 전제가 깨진다. 「Jira Cloud 가 그렇게 한다」고 주장하지 않는다.
- **X-JD-6 — `sprint_burndown` 은 `boardId` 를 받고 활성 스프린트를 자동 선택한다.**
  Jira 문서는 이 가젯의 설정 필드를 명시하지 않는다(JD-8 은 "track remaining work" 뿐).
  간접 근거만 있다 — 추정 통계가 **보드 설정**에서 온다("Use the **Estimation Statistic** dropdown
  to change how issues are estimated on your board",
  [estimation in sprint burndown gadget](https://support.atlassian.com/jira/kb/how-to-set-estimation-statistics-and-time-tracking-estimation-in-the-sprint-burndown-gadget/), 2026-09-07, Cloud).
  근거가 간접이라는 사실을 여기 남긴다. Maxi 결정(M-3)으로 `boardId` 를 채택했다.

## 도메인 정리

- **BC** — `notification` 단일. 대시보드·가젯은 `backend/modules/notification/.../dashboard/` 에 산다.
  프론트가 부르는 `summary`/`activity`(issue-tracking) · `burndown`(agile-planning) API 는
  **이 PR 이 한 줄도 고치지 않는다** — 읽기만 하므로 BC 침범이 아니다.
- **영향 엔티티** — `GadgetType`(enum · config 디스크립터) · `GadgetCatalogEntry`(카탈로그 응답) ·
  `Dashboard.layout`(JSONB — 가젯이 여기 임베드된다).
- **새 용어** — 없음. 「가젯」은 FR-DB-02 에서 이미 도입됐다.
- **관련 ADR 3건 — 충돌 0건.**
  - `docs/decisions/2026-06-29-fr-db-02-gadget-system.md` **D2** 「데이터 소싱 — 프론트가 기존 BC API
    직접 호출(notification BC = 저장·검증만)」. 이 PR 의 4종이 정확히 그 방식이다. **부합.**
  - 같은 ADR **enabled 단방향** 「enabled 플래그는 활성화(true) 방향으로만 변경」. 이 PR 은
    `false→true` 만 한다. **부합.**
  - `docs/decisions/2026-06-22-fr-db-01-custom-dashboard.md` **D4** layout(JSONB) 임베드. **부합.**
- **FR** — 신규 FR **0건**. FR-DB-01(대시보드) · FR-DB-02(가젯) 의 미완 범위를 채운다. FR 수 145 불변.

## 스펙

### Maxi 결정 3건 (2026-09-07 · sanity check 선행)

| # | 물음 | 결정 | 영향 |
|---|---|---|---|
| M-1 | 설정 폼에 선택기가 없어 UUID·프로젝트키를 손으로 타이핑해야 한다 | **이번 PR 에 선택기 포함** | 선재 결함(`filter_result`·`issue_count` 의 `filterId`)도 함께 해소 |
| M-2 | JD-5 레이아웃 갭 | **자유 배치 유지 + 편집 모드 도입** | 12컬럼 그리드 유지 · JD-1 모드 토글 + JD-3 타일 ⋯ 메뉴만 맞춤 · 자유 배치는 편차 `X-JD-4` |
| M-3 | `sprint_burndown` 이 무엇을 받나 | **`boardId` + 활성 스프린트 자동** | `BoardDetail.activeSprint` 재사용 · 스프린트가 바뀌어도 가젯을 안 고친다 |

### 사용자 시나리오 (Given-When-Then)

- **S1 파이 차트 추가.** Given 대시보드 편집 권한이 있고, When `가젯 추가` → `파이 차트` 를 고르고
  **프로젝트 드롭다운**에서 프로젝트를, **그룹 기준** ENUM 에서 `상태` 를 고르면,
  Then 그 프로젝트의 상태별 이슈 분포가 파이로 그려진다. (JD-6)
- **S2 막대 차트.** S1 과 같되 마크만 막대다. 같은 4종 통계(`status`/`assignee`/`priority`/`issueType`)를 쓴다.
- **S3 번다운.** Given 스크럼 보드가 있고, When `번다운` 가젯에서 **프로젝트 → 보드** 를 고르면,
  Then 그 보드의 **활성 스프린트** 번다운이 그려진다. 스프린트가 다음 것으로 바뀌어도
  가젯 설정을 고치지 않는다. (M-3)
- **S4 활동 스트림.** When 프로젝트를 고르고 표시 개수를 정하면, Then 그 프로젝트의 최근 변경이
  최신순으로 나열된다. (JD-7)
- **S5 편집 모드.** Given 보기 모드에서, When `편집` 을 누르면 Then 드래그·리사이즈 핸들과
  타일 `⋯` 메뉴가 나타나고, `완료` 를 누르면 사라진다. (JD-1)
- **S6 칸반 보드 번다운.** Given 칸반 보드(활성 스프린트 없음)를 고른 번다운 가젯에서,
  When 대시보드를 열면 Then 「활성 스프린트가 없습니다」 빈 상태가 뜬다. 오류가 아니다.

### 기능 요구사항 (FR)

신규 FR 0건. 아래는 기존 FR 의 미완 범위다.

- **R1** `GadgetType` 의 `SPRINT_BURNDOWN`·`ACTIVITY_STREAM`·`PIE_CHART`·`BAR_CHART` 를
  `enabled=true` 로 전환한다. `COMMENTS_RECENT`·`CREATED_VS_RESOLVED` 는 **그대로 false 로 둔다.**
- **R2** `PIE_CHART`·`BAR_CHART` 의 config 를 `projectKey`(STRING · required) + `field`(ENUM · required)
  로 바꾼다. `filterId`·`aql` 은 **제거**한다 — X-JD-1 로 필터 스코프를 이연했으므로 남기면
  아무도 안 쓰는 유연성이 된다. `enabled=false` 였으므로 이 타입이 저장된 대시보드는 존재할 수 없다.
- **R3** `SPRINT_BURNDOWN` 의 config 를 `sprintId`(UUID) → `boardId`(UUID · required) 로 바꾼다. (M-3)
- **R4** `GadgetRenderer` 에 4종 `case` 를 추가한다. 기존 차트/피드 컴포넌트를 재사용하고
  새 차트를 발명하지 않는다.
- **R5** `GadgetConfigForm` 에 **선택기 3종**을 넣는다 — 프로젝트(`listProjects`) ·
  보드(`fetchBoards(projectKey)` · 프로젝트에 계단식 종속) · 저장된 필터(`fetchOwnedFilters`).
  descriptor 의 `key` 로 어떤 선택기를 쓸지 정한다. (M-1)
- **R6** 대시보드에 **보기/편집 모드 토글**을 넣는다. 편집 모드에서만 드래그·리사이즈·타일 메뉴가
  산다. (JD-1 · M-2)
- **R7** 타일에 `⋯` More actions 드롭다운을 넣고 `복제`·`삭제` 를 담는다. (JD-3)
- **R8** ★**차집합 판별식** — 「카탈로그에서 `enabled=true` 인 타입」과 「`GadgetRenderer` 가 `case`
  로 그리는 타입」이 **정확히 같아야** 한다. 어느 쪽이든 한쪽에만 있으면 red.

### 비기능 요구사항 (NFR)

- **N1** 가젯 하나가 죽어도 대시보드 전체가 죽지 않는다 (기존 EC6 계승 — `default` 안내 문구).
- **N2** 차트는 기존 차트 색 토큰만 쓴다. 새 색을 발명하지 않는다
  (`apps/web/src/components/__tests__/chart-color-tokens.test.ts` 가 이미 강제).
- **N3** 라이트·다크 양쪽 눈확인 필수. 한쪽만 보면 안 된다 (#468 에서 다크 인라인 코드가 실제로 샜다).

### API 인터페이스 (REST)

**신규 엔드포인트 0건.** 프론트가 기존 API 를 부른다(ADR D2).

| 가젯 | 부르는 API | 이미 있는 클라이언트 |
|---|---|---|
| `pie_chart`·`bar_chart` | `GET /api/v1/projects/{key}/summary` | `use-project-summary.ts` |
| `activity_stream` | `GET /api/v1/projects/{key}/activity?limit=` | 〃 |
| `sprint_burndown` | `GET /api/v1/boards/{boardId}` → `activeSprint.sprintId` → `GET /api/v1/sprints/{id}/burndown` | `boards.ts` `fetchBoard` · `burndown.ts` `fetchSprintBurndown` |
| 선택기 | `GET /api/v1/projects` · `GET /api/v1/boards?projectKey=` · `GET /api/v1/filters` | `projects.ts` · `boards.ts` · `saved-filters.ts` |

바뀌는 응답은 `GET /dashboards/gadget-catalog` 하나 — `enabled` 4건이 `true` 가 되고
pie/bar/burndown 의 `configFields` 가 바뀐다.

### 데이터 모델 변경

**마이그레이션 0건.** 새 테이블·컬럼 없음. 가젯 config 는 `dashboards.layout`(JSONB) 안에 있고
스키마는 `GadgetType` 이 코드로 검증한다.

### 엣지 케이스

- **E1** 칸반 보드(활성 스프린트 없음) → 번다운 가젯은 빈 상태. 오류 아님. (S6)
- **E2** 보드가 삭제됨 → 404 를 빈 상태로 흡수. 대시보드는 살아 있다.
- **E3** 프로젝트에 이슈가 0건 → 파이/막대는 「데이터 없음」. 빈 파이를 그리지 않는다.
- **E4** `projectKey` 가 존재하지 않는 프로젝트 → 404 흡수. (선택기를 쓰면 잘 안 나지만
  layout JSON 을 손으로 고치거나 프로젝트가 나중에 지워지면 난다)
- **E5** 익명 공유 뷰 → `AnonymousLayoutSanitizer` 가 **카테고리 기반 fail-closed** 라
  CHART/ACTIVITY 4종이 자동 차단된다. **실측 확인** — 코드 변경 불필요, 테스트로만 못 박는다.
- **E6** 그룹 기준 ENUM 에 없는 값이 layout JSON 에 들어옴 → 400 (기존 `validateEnumField`).
- **E7** 편집 모드에서 저장 없이 이탈 → 기존 OCC/저장 흐름을 그대로 쓴다. 새로 만들지 않는다.
- **E8** 보드 선택기에서 프로젝트를 바꾸면 → 이미 고른 보드가 무효가 된다. 초기화해야 한다.

### 제약 조건

- **C-1** `한 PR = 한 BC` — 이 PR 은 notification BC + 프론트만. issue-tracking 은 **읽기만.**
- **C-2** enabled 단방향(ADR) — `false→true` 만. 이 PR 은 어떤 타입도 끄지 않는다.
- **C-3** 새 차트 컴포넌트를 만들지 않는다. recharts 와 기존 6종 차트의 패턴을 따른다.
- **C-4** `COMMENTS_RECENT` 는 **Jira 대응이 없다**(JD-10). 이 PR 에서 건드리지 않고,
  PR3 에서 「대응 없음 — ADS 준용」 판정을 받는다.

### 측정 가능한 완료 기준

- **A1** `GET /gadget-catalog` 의 `enabled==true` 개수가 **10** 이다(6→10). `pie_chart`·`bar_chart`·
  `sprint_burndown`·`activity_stream` 각각 `true` 점단언. `comments_recent`·`created_vs_resolved` 는 `false` 점단언.
- **A2** ★**차집합 판별식이 양방향으로 비어 있다** — 카탈로그 `enabled=true` 집합과
  `GadgetRenderer` 의 `case` 집합이 정확히 일치. **비-공허 짝** — `case` 하나를 지우면 red 가 뜬다.
- **A3** 카탈로그 총수는 여전히 **12**. 이 PR 은 타입을 추가하지 않는다.
- **A4** `enabled=false` 타입 저장 거부(EC10) 단언이 **여전히 꺼진 타입**으로 살아 있다 —
  `DashboardGadgetIntegrationTest.kt:197` 의 `pie_chart` 를 `comments_recent` 로 교체.
- **A5** `AnonymousLayoutSanitizer` 가 신규 4종을 `requiresAuth` 플레이스홀더로 치환한다 (4종 전수).
- **A6** 선택기 3종이 실제로 목록을 부른다 — 프로젝트 선택 → 보드 목록이 그 프로젝트로 좁혀진다(E8 포함).
- **A7** 보기 모드에서 드래그 핸들·타일 `⋯` 가 **없고**, 편집 모드에서 **있다**.
- **A8** E2E — 4종 가젯을 추가하고 각각이 「지원되지 않는 가젯입니다」가 **아닌** 것을 확인.
- **A9** 라이트·다크 눈확인 1회씩.


## Sanity Check

스펙을 스스로 흔들어 gap 6건을 찾았다. **1건은 실측으로 해소**, 4건은 스펙에 보강, 1건은 이미 커버.

- **❓ 발견 1 — R2 의 `filterId`/`aql` 제거가 기존 데이터를 깨뜨리나. → 해소(실측).**
  이력 검색(`-S PIE_CHART`)으로 확인했다. `PIE_CHART` 를 도입한 커밋과 `Dashboard.validateLayout`
  에 `enabled` 저장 가드를 넣은 커밋이 **동일하다**(`d69310340` · FR-DB-02 #205). 즉 `pie_chart`
  는 태어난 순간부터 `enabled=false` 였고 **같은 커밋이 그 타입의 저장을 거부**했다. 저장된
  `pie_chart` 타일은 존재할 수 없으므로 config 필드 제거는 안전하다.
  「없을 것이다」가 아니라 「없다」다.

- **❓ 발견 2 — JD-7 은 「**your** recent activity」인데 BTS 는 프로젝트 기준이다. → 편차 등재.**
  Jira 의 Activity Stream 은 **보는 사람** 기준 활동이고, BTS 가 재사용할
  `GET /projects/{key}/activity` 는 **프로젝트** 기준이다. 다른 것을 같다고 부르면 안 되므로
  `X-JD-5` 로 명시한다. 사용자 기준 스트림은 만들지 않는다 — 그 API 가 없고, 만들면 이 PR 의
  「백엔드 신규 0」 전제가 깨진다.

- **❓ 발견 3 — `canEdit`(권한)과 편집 모드(UI 상태)의 관계가 없었다. → 보강.**
  둘은 다른 축이다. **`canEdit=false` 면 편집 버튼 자체가 없다**(모드로 진입할 수단이 없음).
  `canEdit=true` 여도 기본은 **보기 모드**다. `publicMode=true` 는 `canEdit` 과 무관하게 강제
  읽기 전용이라는 기존 규칙(`DashboardGrid.tsx:117-118`)을 그대로 계승한다.
  → **A7 을 3분기로 확장** — 권한없음 / 권한있음·보기 / 권한있음·편집.

- **❓ 발견 4 — R5 가 descriptor 의 `key` 문자열로 선택기를 고르는 것은 두 목록이다. → 보강.**
  「`projectKey` 면 프로젝트 선택기」라는 매핑이 `GadgetConfigForm` 안에 문자열로 살면,
  나중에 누가 `GadgetType.kt` 에 `projectKey` 를 쓰는 필드를 추가해도 폼은 조용히 텍스트
  입력으로 떨어진다 — 지금 고치는 그 결함의 재발이다.
  → **A10 신설** — 선택기 매핑 키 집합과 `GadgetType.kt` 가 실제로 선언한 필드 키 집합을
  대조하는 판별식. 매핑에 없는 스코프성 키(`projectKey`·`boardId`·`filterId`)가 생기면 red.

- **❓ 발견 5 — 익명 차단이 백엔드·프론트 두 곳인데 서로를 안 본다. → 보강.**
  백엔드 `AnonymousLayoutSanitizer` 는 **카테고리 기반**(STATIC 만 통과)이고 프론트
  `PublicGadgetRenderer` 는 **타입 화이트리스트**(`text_widget`/`link_list`)다. 판정 기준이
  다르므로 한쪽만 고치면 조용히 갈린다.
  → **A5 를 양쪽으로 확장** — 백엔드 4종 치환 단언 + 프론트 화이트리스트가 4종을 거부하는 단언.

- **✅ 이미 커버 — E8(보드 선택기 초기화)** 은 A6 이 명시적으로 재고 있다.

### 보강으로 추가된 완료 기준

- **A7′** 편집 모드는 3분기로 잰다 — ①`canEdit=false`: 편집 버튼 부재 ②`canEdit=true`·보기 모드:
  드래그 핸들·타일 `⋯` 부재 ③편집 모드: 둘 다 존재. `publicMode=true` 는 ①과 같다.
- **A10** 선택기 매핑 키 집합 ⊇ `GadgetType.kt` 의 스코프성 필드 키 집합. 차집합이 비어야 한다.
  **비-공허 짝** — 매핑에서 한 줄을 지우면 red.
- **A5′** 익명 차단을 백엔드(카테고리 치환)·프론트(타입 화이트리스트) **양쪽에서** 4종 전수로 잰다.


## Plan

### Task 1. A2 차집합 판별식 — 카탈로그 `enabled` 집합 ↔ 렌더러 `case` 집합

**메타**.
- agent: `frontend-engineer`
- files: [`scripts/workflow/gadget-catalog-renderer-parity.test.ts`]
- depends-on: []
- jira: []

**RED**: 이 판별식은 **현재 저장소에서 green 이 정상**이다(카탈로그 6종 = 렌더러 6종).
그러므로 red 는 「지금 실패하는 테스트」가 아니라 **비-공허 뮤테이션**으로 세운다.
- 대조군 — 지금 돌려서 green 임을 확인한다. green 이 아니면 파서가 틀린 것이다.
- ★**공허 방지 짝** — 파싱 결과가 0건이면 두 빈 집합이 같아져 조용히 통과한다.
  `enabled=true` 개수 **≥ 6**, `case` 개수 **≥ 6** 하한을 함께 단언한다.
  이 하한이 없으면 정규식이 깨져도 초록이다.

**GREEN**:
- `backend/.../GadgetType.kt` 를 읽어 `key = "<k>"` 와 그 상수의 `enabled = true|false` 를 짝지어
  파싱 → `enabledTypes: Set<string>`.
  ★같은 상수 블록 안에서 짝지어야 한다. 파일 전체에서 `key` 와 `enabled` 를 따로 모으면
  순서가 어긋나도 개수가 같아 통과한다(메모리 `[[partial-column-parser-lets-unread-column-rot]]`).
- `apps/web/.../GadgetRenderer.tsx` 를 읽어 `case '<k>':` 를 파싱 → `renderedTypes: Set<string>`.
- `assert.deepEqual([...enabled].sort(), [...rendered].sort())` — **양방향**.
  한쪽에만 있으면 어느 쪽인지 메시지에 적는다.

**REFACTOR**: 두 파서를 export 해 테스트가 직접 호출할 수 있게 한다.

**검증**: `node --experimental-strip-types --test scripts/workflow/gadget-catalog-renderer-parity.test.ts`
★**뮤테이션 2회** — ①`GadgetRenderer.tsx` 의 `case 'issue_count':` 를 지우면 red
②`GadgetType.kt` 의 `TEXT_WIDGET` 을 `enabled = false` 로 바꾸면 red.
**Python assert-후-replace 로 적용하고 `grep -c` 로 되잰다** — BSD `sed` 가 미매치해도 조용히
0건을 적용하고 초록을 내던 전례가 있다(2026-09-04). GREEN 선커밋 뒤에 뮤테이션한다.

### Task 2. `GadgetType` config 스키마 — pie/bar `projectKey`, burndown `boardId`

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/notification/src/main/kotlin/com/bts/notification/dashboard/domain/GadgetType.kt`, `backend/modules/notification/src/test/kotlin/com/bts/notification/dashboard/domain/GadgetTypeTest.kt`]
- depends-on: []
- jira: [JD-6]

**RED**: `GadgetTypeTest.kt`
- `pie_chart` config 에 `projectKey` 만 주면 통과한다 → 지금은 `field` required 위반으로 실패
- `pie_chart` 에 `filterId` 를 주면 **무시된다**(EC5 미지 키 무시) — 필드가 사라졌음을 확인
- `sprint_burndown` 에 `boardId`(UUID) 를 주면 통과, `sprintId` 는 무시된다
- `bar_chart` 는 `pie_chart` 와 **같은 필드 집합**이다 (한 곳만 고치는 실수 차단)

**GREEN**: `GadgetType.kt`
- `PIE_CHART`·`BAR_CHART` — `projectKey`(STRING, required, maxLength 100) + `field`(ENUM, required,
  `status|assignee|priority|issueType`). `filterId`·`aql` 과 `additionalRules` **제거**.
- `SPRINT_BURNDOWN` — `sprintId`(UUID) → `boardId`(UUID, **required**).
- `ACTIVITY_STREAM` — 변경 없음(`projectKey`·`maxItems` 유지).
- `enabled` 는 **이 task 에서 건드리지 않는다** (Task 3 이 켠다 — 사이클 분리).

**REFACTOR**: pie/bar 의 동일 필드 목록을 `private val` 로 뽑아 두 상수가 공유한다.

**검증**: `(cd backend && ./gradlew :modules:notification:test --tests '*GadgetTypeTest*')`

### Task 3. `enabled` 4종 전환 + 개수 단언 교정 + 익명 차단 양방향 못 박기

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/notification/src/main/kotlin/com/bts/notification/dashboard/domain/GadgetType.kt`, `backend/modules/notification/src/test/kotlin/com/bts/notification/dashboard/domain/GadgetTypeTest.kt`, `backend/modules/notification/src/test/kotlin/com/bts/notification/dashboard/web/DashboardControllerTest.kt`, `backend/modules/notification/src/test/kotlin/com/bts/notification/dashboard/web/DashboardGadgetIntegrationTest.kt`, `backend/modules/notification/src/test/kotlin/com/bts/notification/dashboard/application/AnonymousLayoutSanitizerTest.kt`]
- depends-on: [2]
- jira: []

★**익명 차단을 같은 task 에 넣는 이유** — `enabled` 를 켜는 순간 그 타입이 공개 대시보드에
실릴 수 있다. 켜고 나중에 막으면 그 사이가 구멍이다. 한 사이클에서 켜고 막는다.

**RED**:
- `GadgetTypeTest.kt:41` — 「MVP 6종」을 **10종 enabled=true / 2종 false** 로 고치고 4종 점단언 추가.
  `comments_recent`·`created_vs_resolved` 는 `false` 점단언(**여전히 꺼져 있음**을 지킨다).
- `DashboardControllerTest.kt:434` · `DashboardGadgetIntegrationTest.kt:232` — `hasSize(6)` → `hasSize(10)`.
  총수 `length() == 12` 는 **그대로 둔다**(타입을 추가하지 않았다).
- `DashboardGadgetIntegrationTest.kt:197` — EC10 저장 거부 단언의 대상을 `pie_chart` →
  **`comments_recent`** 로 교체한다. ★삭제하면 EC10 회귀 가드를 통째로 잃는다. 교체다.
- `AnonymousLayoutSanitizerTest.kt` — 신규 4종 **전수**가 `requiresAuth: true` 플레이스홀더로
  치환되고 `config` 가 제거되는지. (A5 백엔드 쪽)

**GREEN**: `GadgetType.kt` 의 `SPRINT_BURNDOWN`·`ACTIVITY_STREAM`·`PIE_CHART`·`BAR_CHART` 를
`enabled = true` 로. ★`AnonymousLayoutSanitizer` 는 **카테고리 기반 fail-closed** 라
코드 변경이 **불필요**하다 — 실측으로 확인했고, 테스트는 그 사실을 못 박을 뿐이다.

**REFACTOR**: 없음(플래그 4개).

**검증**: `(cd backend && ./gradlew :modules:notification:test)`
★이 시점에 **Task 1 판별식이 red 로 뒤집힌다** — 카탈로그는 10종인데 렌더러는 6종이다.
Task 7 이 green 으로 되돌린다. **이 red 가 판별식이 살아 있다는 증거다.**

### Task 4. `pie_chart`·`bar_chart` 가젯 — `summary` API 재사용

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/dashboard/gadgets/DistributionChartGadget.tsx`, `apps/web/src/components/dashboard/gadgets/DistributionChartGadget.test.tsx`, `apps/web/src/components/dashboard/gadgets/gadget-types.ts`]
- depends-on: [2]
- jira: [JD-6]

**RED(동반 테스트)**:
- `field=status` 면 `summary.statusOverview` 를, `priority`/`issueType`/`assignee` 면 각각
  `priorityBreakdown`/`typesOfWork`/`teamWorkload` 를 읽는다 — **4종 전수**.
- `variant='pie'` 와 `'bar'` 가 **같은 데이터**로 다른 마크를 낸다.
- 이슈 0건 → 「데이터 없음」. 빈 파이를 그리지 않는다 (E3).
- 없는 `projectKey` → 404 를 빈 상태로 흡수. 대시보드는 살아 있다 (E4).

**GREEN**: `DistributionChartGadget.tsx` 하나가 `variant` prop 으로 pie/bar 를 겸한다.
`use-project-summary.ts` 재사용. recharts `PieChart`/`BarChart`.
**색은 기존 차트 색 토큰만** 쓴다(N2 — `chart-color-tokens.test.ts` 가 이미 강제).

**REFACTOR**: `field → summary 필드` 매핑을 `Record` 상수로 뽑는다.

**검증**:
- `pnpm --filter web test -- DistributionChartGadget`
- 눈확인: 파이·막대 각각 라이트/다크 (N3)

### Task 5. `sprint_burndown` 가젯 — `boardId` → 활성 스프린트 자동

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/dashboard/gadgets/SprintBurndownGadget.tsx`, `apps/web/src/components/dashboard/gadgets/SprintBurndownGadget.test.tsx`]
- depends-on: [2]
- jira: [JD-8]

**RED(동반 테스트)**:
- `boardId` → `fetchBoard` → `activeSprint.sprintId` → `fetchSprintBurndown` 순으로 부른다.
- ★**칸반 보드**(`activeSprint === null`) → 「활성 스프린트가 없습니다」 빈 상태. **오류가 아니다** (E1/S6).
- 보드 404 → 빈 상태 흡수 (E2).

**GREEN**: `BurndownChart.tsx` 를 **그대로 재사용**한다. 새 차트를 만들지 않는다(C-3).

**REFACTOR**: 2단 조회를 `useSprintBurndownByBoard(boardId)` 훅으로 묶는다.

**검증**:
- `pnpm --filter web test -- SprintBurndownGadget`
- 눈확인: 스크럼(차트) · 칸반(빈 상태) 각각 라이트/다크

### Task 6. `activity_stream` 가젯 — `ProjectActivityFeed` 재사용

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/dashboard/gadgets/ActivityStreamGadget.tsx`, `apps/web/src/components/dashboard/gadgets/ActivityStreamGadget.test.tsx`]
- depends-on: [2]
- jira: [JD-7]

**RED(동반 테스트)**:
- `projectKey` + `maxItems` 로 `GET /projects/{key}/activity?limit=` 를 부른다.
- `maxItems` 미지정 → 기본 10, 범위 1~50 클램프 (기존 `clampMaxItems` 규칙 계승).
- 활동 0건 → 빈 상태.

**GREEN**: `ProjectActivityFeed.tsx` 재사용. 가젯 높이에 맞게 스크롤 컨테이너로 감싼다.

**REFACTOR**: 없음 예상.

**검증**:
- `pnpm --filter web test -- ActivityStreamGadget`
- 눈확인: 활동 있음/없음 각각 라이트/다크

### Task 7. `GadgetRenderer` 4종 등록 — Task 1 판별식 green 복귀

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/dashboard/gadgets/GadgetRenderer.tsx`, `apps/web/src/components/dashboard/gadgets/GadgetRenderer.test.tsx`, `apps/web/src/components/dashboard/gadgets/PublicGadgetRenderer.test.tsx`]
- depends-on: [1, 4, 5, 6]
- jira: []

**RED(동반 테스트)**:
- 4종 각각이 「지원되지 않는 가젯입니다」가 **아닌** 것을 렌더한다.
- `PublicGadgetRenderer` 가 4종 **전수**를 거부한다 (A5′ 프론트 쪽 — 화이트리스트 밖).

**GREEN**: `case 'pie_chart'`·`'bar_chart'`(→ `DistributionChartGadget` variant 분기) ·
`'sprint_burndown'` · `'activity_stream'` 추가.

**REFACTOR**: `default` 안내 문구는 그대로(N1 — 미지 타입은 여전히 안전하게 떨어져야 한다).

**검증**:
- `node --experimental-strip-types --test scripts/workflow/gadget-catalog-renderer-parity.test.ts` — **green 복귀**
- `pnpm --filter web test -- GadgetRenderer PublicGadgetRenderer`

### Task 8. A10 선택기 판별식 + `GadgetConfigForm` 선택기 3종

**메타**.
- agent: `frontend-engineer`
- files: [`scripts/workflow/gadget-config-picker-coverage.test.ts`, `apps/web/src/components/dashboard/GadgetConfigForm.tsx`, `apps/web/src/components/dashboard/GadgetConfigForm.test.tsx`]
- depends-on: [2]
- jira: []

**RED**:
- **판별식** — `GadgetType.kt` 가 선언한 **스코프성 키**(`projectKey`·`boardId`·`filterId`) 전량이
  `GadgetConfigForm` 의 「키→선택기」 매핑에 있다. 차집합이 비어야 한다. **지금은 매핑이 없어 red.**
  ★스코프성 키 목록 자체가 하드코딩이면 그것이 또 하나의 썩는 목록이다 —
  「`UUID` 타입이거나 이름이 `*Key` 로 끝나는 필드」처럼 **파일에서 유도**한다.
- **폼 테스트** — `projectKey` 필드가 `<input>` 이 아니라 프로젝트 `<select>` 로 렌더된다.
  프로젝트를 고르면 보드 목록이 그 프로젝트로 좁혀지고, **프로젝트를 바꾸면 고른 보드가 초기화된다** (E8).

**GREEN**: `GadgetConfigForm` 에 매핑 추가 —
`projectKey`→`listProjects` · `boardId`→`fetchBoards(projectKey)` 계단식 · `filterId`→`fetchOwnedFilters`.
★선재 결함 동반 해소 — 이미 활성인 `filter_result`·`issue_count` 의 `filterId` 도 이 매핑을 타서
자유 입력에서 선택기로 바뀐다 (M-1).

**REFACTOR**: 매핑을 `PICKER_BY_KEY` 상수로 뽑아 판별식이 한 곳만 보게 한다.

**검증**:
- `node --experimental-strip-types --test scripts/workflow/gadget-config-picker-coverage.test.ts`
- ★뮤테이션 — `PICKER_BY_KEY` 에서 `boardId` 줄을 지우면 red (Python assert-후-replace · `grep -c` 되재기)
- 눈확인: 가젯 추가 모달에서 프로젝트→보드 계단식 — 라이트/다크

### Task 9. 보기/편집 모드 토글 + 타일 `⋯` 메뉴 + 새 가젯 삽입 위치

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/routes/dashboards.$dashboardId.tsx`, `apps/web/src/components/dashboard/DashboardGrid.tsx`, `apps/web/src/components/dashboard/DashboardTile.tsx`, `apps/web/src/components/dashboard/DashboardTile.test.tsx`, `apps/web/src/components/dashboard/DashboardGrid.test.tsx`]
- depends-on: []
- jira: [JD-1, JD-2, JD-3]

**RED(동반 테스트)** — **A7′ 3분기를 전수로 잰다.**
- ①`canEdit=false` → **편집 버튼 자체가 없다**(모드 진입 수단 없음)
- ②`canEdit=true` · 보기 모드(기본) → 드래그 핸들·타일 `⋯` **부재**
- ③편집 모드 → 둘 다 **존재**
- `publicMode=true` 는 `canEdit` 과 무관하게 ①과 같다 (기존 규칙 계승)
- 타일 `⋯` 메뉴에 `복제`·`삭제` 가 있다 (JD-3)
- ★새 가젯이 **좌측 컬럼 최상단**에 놓인다 (JD-2) — 현재는 `y: maxBottom`(맨 아래)이라
  Jira 와 반대다. `y: 0` 으로 바꾸고 나머지가 밀려나는지 확인한다.

**GREEN**: 라우트에 `isEditing` 상태 추가. `DashboardGrid` 가 `canEdit` 대신 `canEdit && isEditing`
을 드래그/리사이즈에 넘긴다. `DashboardTile` 의 `Trash2` 단독을 `⋯` `DropdownMenu` 로 바꾸고
`복제`·`삭제` 를 담는다. `handleAddGadgetTile` 의 `y: maxBottom` → `y: 0`.

**REFACTOR**: 「보기/편집」 라벨을 `dashboard-labels.ts` 로 (하드코딩 문구 금지).

**검증**:
- `pnpm --filter web test -- DashboardTile DashboardGrid`
- 눈확인: 보기↔편집 전환 · `⋯` 메뉴 열림 — 라이트/다크

### Task 10. E2E + 최종 눈확인

**메타**.
- agent: `qa-engineer`
- files: [`apps/web/e2e/dashboard-gadgets.spec.ts`]
- depends-on: [7, 8, 9]
- jira: []

**RED(동반 테스트)**:
- 가젯 4종을 **각각** 추가하고 「지원되지 않는 가젯입니다」가 **아닌** 것을 확인 (A8).
  ★타입 목록을 e2e 에 손으로 적지 않는다 — 적으면 그것이 세 번째 목록이 된다.
  카탈로그 응답에서 `enabled=true` 인 신규 4종을 읽어 순회한다.
- 편집 모드에서만 드래그 핸들이 보인다 (A7′).
- 선택기로 프로젝트를 고르면 보드가 좁혀진다 (A6).

**GREEN**: 기존 대시보드 e2e 픽스처·헬퍼 재사용.

**REFACTOR**: 없음.

**검증**:
- `pnpm --filter web test:e2e -- dashboard-gadgets` **3회 연속 green**(플레이크 배제)
- 눈확인 최종 — 4종 가젯이 실린 대시보드를 라이트/다크 각 1회 (A9)

## Plan 메타

- **task 수**: 10 · **예상 wave**: 4
  - wave 1 — T1(판별식) · T2(config 스키마) · T9(편집 모드 · 독립)
  - wave 2 — T3(enabled+익명) · T4(pie/bar) · T5(burndown) · T6(activity) · T8(선택기)
  - wave 3 — T7(렌더러 등록 → 판별식 green 복귀)
  - wave 4 — T10(E2E)
- **구현 규율**: TDD red→green→refactor + **ui 시각 검증 트랙**(전 UI task 에 라이트/다크 눈확인).
  T2/T3 은 백엔드라 정식 red-first.
- **추가 검증**: `tsc` · `eslint` · `ktlint` · `detekt` · `vitest` · `playwright` ·
  판별식 전량(`node --test 'scripts/**/*.test.ts'`) · `build-doc-index --check` · `verify-master-plan`
- **신규 판별식 2종**: `gadget-catalog-renderer-parity`(A2) · `gadget-config-picker-coverage`(A10).
  둘 다 **비-공허 뮤테이션 짝**을 갖는다. 뮤테이션은 **GREEN 선커밋 뒤** Python
  assert-후-replace 로 적용하고 `grep -c` 로 되잰다(BSD `sed` 미매치 함정).
- **Jira 매핑**: `JD-1→T9` · `JD-2→T9` · `JD-3→T9` · `JD-6→T2,T4` · `JD-7→T6` · `JD-8→T5` ·
  `JD-4 범위 밖`(가젯별 갱신 주기 — 현재 전 가젯 고정 30초 `GADGET_STALE_TIME`. 가젯마다 설정
  항목을 여는 것은 config 스키마 확장이라 후속 PR) · `JD-5 편차 X-JD-4`(컬럼 프리셋 미채택 · M-2) ·
  `JD-9·JD-10 → PR2/PR3 범위`. **채택 항목 차집합 0.**
- **마이그레이션 0 · 신규 엔드포인트 0 · 신규 FR 0**(FR 수 145 불변).
- **T3 승격 사유**: 없음. 생기면 즉시 정지·보고.


## 리뷰 결과 (← /bts-review-plan 채움)
