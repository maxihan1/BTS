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

## 도메인 정리 (← /bts-spec §1 채움)

## 스펙 (← /bts-spec §2 채움)

## Sanity Check (← /bts-spec §3 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
