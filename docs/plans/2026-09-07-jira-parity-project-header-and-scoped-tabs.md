# 프로젝트 헤더를 셸로 · 9탭 전량 프로젝트 스코프 — Jira 패리티 J5 후속

> 티어: T2 · type: ui · BC = 없음(`apps/web` 전용) · 마이그레이션 0건 · 신규 의존성 0
> FR — FR-UX-06(페이지 레이아웃 공통 헤더) · FR-BD-01(보드) · FR-IS-01(이슈 목록) · FR-CA-01(캘린더) · FR-DB-01(대시보드)

## 왜 T2 인가

`apps/web/src/router.ts` 가 `surfaces.ts` 의 `SEC_FE` 다. 이 PR 은 라우트를 **3개 신설**하므로
가드 행렬에 행이 3줄 늘어난다. 등록만 하고 `beforeLoad: requireAuthAndPasswordChanged` 를
빼먹으면 그 경로가 무가드로 열린다 — 선례 `2026-09-03-project-summary-route-j4.md` 와 같은 이유다.

## Maxi 지시 (2026-09-07)

> 「요약 타임라인 보드 백로그 캘린더 대시보드 컴포넌트 이슈 버전 이 탭이 지라클라우드와 다른
> 방식으로 구현이 되었었고 이슈 캘린더 에서는 해당 버튼이 노출 되지 않아 지라 클라우드와
> 동일한 UI 배치로 수정 해줄래?」

첨부 스크린샷 2장 = ① Jira Cloud 실물(`Deeps Kanban` 스페이스) ② BTS 현재 보드 화면.

`AskUserQuestion` 2문항 확답.
1. **헤더 구조** → 「지라 그대로 — 뷰 h1 제거」
2. **편차 X9** → 「지라클라우드와 동일하게 해줘」 = 세 탭도 프로젝트 스코프 라우트를 갖는다

## Jira 대조 (조회일 2026-09-07 · 전부 Cloud · Maxi 첨부 실물 스크린샷 대조)

| # | 항목 | 근거 | 구분 |
|---|---|---|---|
| J5-8 | 스페이스 이름이 **탭바 위**에 온다 | 스크린샷 ① — `스페이스 / BORADEEPS` 브레드크럼 → `Deeps Kanban` 제목행 → 탭바 순서 | 실물 |
| J5-9 | 제목행 우측이 **액션 영역**이다 | 스크린샷 ① — 공유·자동화·가져오기·전체화면 아이콘이 제목과 같은 줄 | 실물 |
| J5-10 | 탭 전환에도 제목행이 **그대로 남는다** | 스크린샷 ① 의 `목록` 탭 활성 상태에서도 `Deeps Kanban` 이 위에 있다 | 실물 |
| J5-11 | 뷰는 자기 제목을 **다시 쓰지 않는다** | 스크린샷 ① — `목록` 탭 본문에 「목록」이라는 h1 이 없다. 곧바로 뷰 툴바 | 실물 |
| J5-12 | 캘린더·목록도 **스페이스 안의 탭**이다 | 스크린샷 ① — `캘린더`·`목록` 이 같은 탭바에 있고 눌러도 탭바가 유지된다 | 실물 |

### 의도적 편차 — 신규·수정

- **X9 폐기.** 「캘린더·대시보드·이슈는 프로젝트 스코프 라우트가 없다」는 2026-09-03 확정을
  Maxi 가 2026-09-07 뒤집었다. `project-view-tabs.ts` 의 X9 문단과 그 짝 판별식 3건을 함께 지운다.
  **문단만 지우고 판별식을 남기면 red 가 나고, 판별식만 지우면 문서가 거짓말한다 — 같은 커밋이다.**
- **X-J5-13 (신설) — 프로젝트 캘린더·대시보드는 아직 내용을 좁히지 않는다.**
  `GET /api/v1/users/me/calendar` 는 **개인** 캘린더고 프로젝트 파라미터가 없다(identity-access BC).
  대시보드 목록도 프로젝트 연관이 없다. 그래서 `/projects/$key/calendar`·`/dashboards` 는
  **탐색 패리티만** 준다 — 탭바가 유지되고 왕복이 가능해진다. 내용 필터는 백엔드 신규 계약이라
  이 PR 밖이다(별건 부채로 등재). 이슈 탭은 `projectKey` 필터가 이미 있어 **실제로 좁힌다.**
- **X-J5-14 (신설) — 프로젝트 하위 화면(설정·보고서·번다운)의 `h1` 을 `h2` 로 내린다.**
  Jira 의 그 화면들도 스페이스 이름 아래 붙는 하위 제목이다. 셸 헤더가 `h1` 을 단독 소유해야
  「문서당 h1 1개」(`PageHeader` KDoc 계약)가 깨지지 않는다.

## 설계

### 1. 셸 크롬이 2층이 된다

```
ShellLayout <main>
  └ ProjectViewChrome            ← projectKey 있을 때만
      ├ ProjectViewHeader        ← 신규. 브레드크럼 + 프로젝트명 h1 + ☆ + 액션 슬롯
      └ ProjectNavTabs           ← 기존 9탭
  └ <Outlet/>
```

### 2. 액션 슬롯 — 포털 1개 + 컨텍스트 1개

제목행 우측(J5-9)에 뷰별 버튼(보드의 「이슈 추가」, 이슈 목록의 「새 이슈」)을 올려야 하는데,
**그 버튼들은 뷰의 상태(권한·다이얼로그·쿼리 무효화)를 안다.** 셸로 올려 다시 구현하면 보드의
`invalidateQueries(boardKeys.detail)` 같은 지식이 두 벌이 된다.

그래서 **소유는 뷰에 두고 자리만 셸이 준다**.

- `ProjectChromeContext` — `{ present: boolean; actionHost: HTMLElement | null }`
- `ProjectHeaderActions` — `present` 면 `actionHost` 로 `createPortal`, 아니면 **그 자리에 그대로 렌더**

폴백 인라인이 핵심이다. `issues.index.tsx` 와 `dashboards.tsx` 는 전역(`/issues`)과
프로젝트 스코프(`/projects/$key/issues`) **양쪽**에서 쓰이므로, 크롬이 없을 때 버튼이 사라지면
전역 화면에서 진입점이 증발한다.

🛑 `actionHost` 는 `useState` **콜백 ref** 로 잡는다 — `useRef` 는 첫 렌더에 `null` 이고 채워져도
재렌더가 없어 포털이 영영 안 붙는다(`ProjectNavTabs` 의 `portalHost` 선례).

### 3. 같은 컨텍스트가 h1 중복도 막는다

`present === true` 면 셸이 `h1` 을 갖고 있다. 그래서 전역/스코프 양용 화면(`issues.index`·
`dashboards`)은 `present` 일 때 자기 제목행을 렌더하지 않는다. 조건 하나가 두 문제를 함께 푼다.

### 4. 편차 X9 폐기 — 라우트 3개 신설

| 신규 경로 | 컴포넌트 | 가드 |
|---|---|---|
| `/projects/$projectKey/calendar` | `CalendarRouteAdapter` (재사용) | `requireAuthAndPasswordChanged` |
| `/projects/$projectKey/dashboards` | `DashboardsRouteAdapter` (재사용) | 〃 |
| `/projects/$projectKey/issues` | `IssuesIndexRouteAdapter` (params 에서 projectKey) | 〃 |

탭 정의 3건이 `usesProjectParam: true` 가 되고 `projectKeySearchParam` 축이 **사라진다**
(마지막 사용자가 이슈 탭이었다).

## 체크리스트

- [x] 1. `ProjectChromeContext` + `ProjectHeaderActions` — 폴백 인라인 red 먼저 (7건)
- [x] 2. `ProjectViewHeader` — 브레드크럼·프로젝트명 h1·☆·액션 호스트 (7건)
- [x] 3. `ProjectViewChrome` 이 헤더+탭바 2층을 렌더 (14건 · 순서 단언 포함)
- [x] 4. 라우트 3종 신설 + 가드 (`router.ts`) — `router.guard-coverage` 가 자동 강제
- [x] 5. `project-view-tabs.ts` X9 폐기 + 판별식 갱신 (전역 3탭 단언 → 9탭 전수 스코프 단언)
- [x] 6. 9탭 뷰에서 제목행 제거 (요약·보드·백로그·이슈·대시보드) — 컴포넌트·버전은 §8 로 흡수
- [x] 7. 보드·이슈목록·대시보드 액션을 `ProjectHeaderActions` 로 감싼다
- [x] 8. 프로젝트 하위 화면 18종 `h1` → `h2` (X-J5-14)
- [x] 9. 영향 테스트 전량 갱신 — 유닛 11,128건 green · typecheck · lint 0 error · 200줄 래칫 green
- [x] 10. 부채 등재 — 부채 **180** (TODOS.md + 마스터 §전수 매핑, 차집합 판별식 green)
- [ ] 11. E2E 전량 — 프로젝트 하위 화면 heading 계약 갱신

### §6 이 §8 로 흡수된 이유

컴포넌트·버전 탭은 `/projects/$key/settings/…` 아래의 설정 화면이라 제목이 「무엇을 관리하는
화면인가」를 말한다(형제 설정 10종과 같은 모양). 탭 라벨을 그대로 되뇌는 보드·백로그와 다르므로
**지우지 않고 h2 로 내렸다** — 문서당 h1 1개는 그대로 지켜진다.

## 컨텍스트 노트

작업 중 결정을 여기 append 한다.

### D1 — 액션을 셸이 다시 구현하지 않는다 (2026-09-07)
보드의 「이슈 추가」는 `boardKeys.detail(currentBoardId)` 무효화를 안다. 셸로 올리면 셸이
보드 쿼리 키를 알아야 하고, 백로그·이슈목록이 각자 다른 무효화를 요구하는 순간 셸이
전 화면의 쿼리 지도를 갖게 된다. 포털은 **자리만** 빌려주므로 그 지식이 이동하지 않는다.

### D2 — `present` 를 `actionHost !== null` 로 대신하지 않는다 (2026-09-07)
호스트는 콜백 ref 라 첫 렌더에 `null` 이다. `present` 를 그것으로 유도하면 첫 렌더에서
`issues.index` 가 자기 h1 을 그렸다가 다음 렌더에 지우는 깜빡임이 생기고, 유닛 테스트는
첫 렌더만 보므로 **초록인 채로** 그 깜빡임을 통과시킨다. 두 값을 분리한다.

### D3 — 캘린더·대시보드는 탐색 패리티까지만 (2026-09-07)
클라이언트에서 이슈 키 접두(`ATLAS-`)로 거를 수도 있으나, 응답에 `truncated` 가 있어
**잘린 뒤 거르면 조용히 빈 화면**이 된다. 「좁힌 척」이 「안 좁힘」보다 나쁘다.
→ **부채 180** 등재(TODOS.md §화면에서 보이는 것 · 마스터 §전수 매핑 180행). BC 가 둘이라
(identity-access 캘린더 · notification 대시보드) 상환은 최소 2 PR 이다.

### D4 — 200줄 래칫이 이 PR 의 설계를 한 번 고쳤다 (2026-09-07)
`chromeOwnsTitle` 을 `IssueListPage` 안의 변수로 두자 그 함수가 동결값 331 을 3줄 넘겼고,
`listTarget` 을 어댑터 본문에 두자 `IssueListRouteAdapter` 가 **새로** 200줄을 넘었다.
주석을 줄이는 대신 **훅·컴포넌트로 뺐다** — `useIssueListRouteScope()` 와
`<IssueListPageTitle/>`. 래칫이 「주석을 지워라」가 아니라 「쪼개라」를 요구한 것이 맞았다:
두 조각 모두 이름이 붙으면서 무엇을 하는지가 오히려 또렷해졌다.

### D5 — 판별식을 「존재」가 아니라 «목적지»로 적었다 (2026-09-07)
탭 3종을 스코프로 옮기면서 「링크가 있다」만 단언하면 전역 경로로 되돌린 회귀가 초록으로
통과한다. 그래서 ① 세 탭의 href 를 문자열로 못박고 ② 9탭 전수가 `/projects/ATLAS` 로 시작함을
따로 단언했다. ②가 없으면 **새로 추가되는 탭**이 같은 함정에 다시 빠진다.
