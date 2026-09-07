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

## 도메인 정리 (← /bts-spec §1 채움)

## 스펙 (← /bts-spec §2 채움)

## Sanity Check (← /bts-spec §3 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
