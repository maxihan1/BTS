# 프로젝트 내비게이션 서브앱 재편 (PR1 of 2) — 아이콘은 PR2

> 티어: T2
> slug: project-nav-subapp-icon
> type: ui
> agent: frontend-engineer
> 생성: 2026-09-08

> 🛑 **착수 선언은 T3 이었고 PR 분할로 T2 가 됐다.** 티어를 내린 근거는 아이콘(마이그레이션)을
> PR2 로 뺀 것 하나뿐이다 — 이 PR 에 `MIGRATION` 표면이 남아 있지 않다. **절차는 T3 것을 그대로
> 둔다**(spec 분리 · ADR 대조 · plan). 실측 티어는 머지 전 `detect-tier` 로 다시 재어 게이트 2
> 요약에 선언과 나란히 싣는다. 자세한 분할표는 §PR 분할.

## Brief

Maxi 원문 4건 + 추가 1건.

1. 사이드바 프로젝트 하위에서 `> 리포트`, `프로젝트 설정` 메뉴 제거
2. 사이드바 프로젝트 `⋯` → 프로젝트 설정 페이지로 들어가면 「프로젝트 이름 변경」 페이지만 보인다.
   사이드바 영역에 설정 메뉴를 리스트업하고 설정 내 하위 메뉴를 노출
3. 설정 페이지에도 요약·타임라인·보드·백로그 등 탭 영역이 노출된다. 설정 페이지에서는 제거
4. 프로젝트 아이콘 메뉴 신설, 사이드바에도 노출
5. (추가) 리포트 페이지에서도 요약·타임라인·보드·백로그 탭 제거

### Maxi 결정 (착수 전 · AskUserQuestion 4문)

| 질문 | 결정 |
|---|---|
| ①로 리포트 그룹을 지우면 리포트 4화면 진입로가 0이 된다 | **프로젝트 탭바에 「리포트」 탭 신설** |
| 프로젝트 아이콘 저장 방식 (백엔드 지원 0) | **풀스택 — 마이그레이션 + API + UI** |
| 설정 페이지 진입 시 사이드바 | **사이드바 전체 교체** + 「← 프로젝트로 돌아가기」 |
| 잔재 worktree 3개 | 유지 + 새 작업 추가 |
| (조회 후) 리포트 화면의 탭바 | **유지** + 리포트 내부 서브내비 — 탭이므로 자기를 지우면 안 된다 |
| (조회 후) 아이콘 배치 | **별도 메뉴 없음.** 「일반」 탭에 추가 + **생성 폼에도 선택 UI** + **기본 아이콘 나열** |

⚠️ 아래 두 행은 **Jira 실물 재조회가 착수 가정을 뒤집은 결과**다. 경위는 `## Jira 대조` §「조회가
착수 판단 2건을 뒤집었다」에 있다 — ①은 컨트롤러가 구 내비게이션 문서를 인용한 오류였고
Maxi 의 되물음이 그것을 잡았다.

### 컨트롤러 결정 (Maxi 에게 고지 후 진행)

`컴포넌트`·`버전`은 정본 9탭인데 경로가 `/projects/$key/settings/...` 라 ③과 정면 충돌한다.
선을 여기서 긋는다 — **두 화면은 정본 탭으로만 유지**(탭바 그대로 · 일반 사이드바)하고
**설정 메뉴 목록에서는 제거**한다. 그러면 「설정 경로 = 탭바 없음」이 예외 있는 규칙이 되지 않고,
「설정 서브앱에 속한 경로 = 탭바 없음」이 규칙이 된다. 정본 목록은 한 곳이어야 한다.

### classify 결과

```
slug=project-nav-subapp-icon · type=migration · agent=db-engineer · tier=T3(선언 · --tier 지정)
primary_bc=null · task_count=0
```

⚠️ classify 는 제목 문자열만 본다. 실측 티어는 머지 전 `detect-tier` 로 다시 잰다.
T3 선언 근거는 ④가 `projects` 테이블 마이그레이션을 요구한다는 것(`MIGRATION` 표면).

### 착수 시점 실측 (원문 판단의 근거)

- 프로젝트 아이콘은 **백엔드에 전혀 없다** — `apps/web/src/api/projects.ts` 의 `projectSchema` 는
  `id/key/name/archived` 4필드이고 `backend/modules/project-workflow/src/main` 에
  `icon`·`avatar` 키워드 실측 0건. 컬럼·API·마이그레이션이 전부 신설이다.
- `ProjectTree.tsx` 의 `REPORT_LINKS`(4) · `SETTINGS_LINKS`(12) 가 그 16화면의 **유일한 진입로**다
  (파일 :460 주석이 2026-09-04 전수 grep 결과로 그렇게 못박아 뒀다). ①을 그냥 지우면 16화면이 고아가 된다.
- 탭바는 `ProjectViewChrome` 이 `useParams.projectKey` 존재만으로 마운트한다 — 경로 종류를 안 본다.
  그래서 설정·리포트에서도 뜬다(③·⑤의 원인).

### 관련 FR (착수 시점 추정 — /bts-spec 이 확정)

- FR-UX-06 · FR-UX-08 — 사이드바/프로젝트 내비게이션 정본
- FR-PJ-02 · FR-PJ-03 — 프로젝트 CRUD (아이콘 필드가 여기 붙는지 신규 FR 인지 spec 이 판정)
- 신규 FR 필요 여부는 `docs/rules/fr-sync-checklist.md` 전수 동기화 대상

## Jira 대조

계약 §1 절차. 조회일 **2026-09-08** · 전부 Cloud · 도메인 `support.atlassian.com`.

### §1-0 승계 — 재조회하지 않은 것

`docs/plans/2026-09-07-jira-parity-project-header-and-scoped-tabs.md` 가 남긴 탭 «집합» 근거를
**URL·조회일 그대로** 승계한다. 이 PR 은 탭 집합에 「리포트」 하나를 더할 뿐 규칙을 바꾸지 않는다.

| # | 항목 | 출처 | 조회일 |
|---|---|---|---|
| J5-1 | 탭은 수평 나열이고 집합은 스페이스 유형·활성 기능에 달렸다 | [space navigation](https://support.atlassian.com/jira-software-cloud/docs/manage-and-customize-the-project-navigation/) | 2026-09-03 |
| J5-12 | 목록·앱 탭은 제거할 수 없다 — 뷰가 스페이스 안에 산다 | 〃 | 2026-09-07 |
| J5-13 | 착지 탭을 스페이스 단위로 정한다 | 〃 | 2026-09-07 |

### 신규 조회 — 조작감 갭 표

| # | 항목 | 원문 인용 | 출처 (Cloud · 2026-09-08) |
|---|---|---|---|
| JR-1 | **리포트는 스페이스 «내비게이션»(수평 탭)에서 연다.** 사이드바가 아니다 | "Navigate to the space you want to report on." / "Select **Reports** from the space navigation." | [generate a report](https://support.atlassian.com/jira-software-cloud/docs/generate-a-report/) |
| JR-2 | 리포트 탭의 착지는 **개별 차트가 아니라 인사이트 대시보드**이고, 전체 목록은 그 안의 버튼으로 연다 | 착지 후 "space insights dashboard" 를 보고 "**More reports** button to open the classic Jira report catalog" | 〃 |
| JR-3 | 신 내비게이션 **사이드바 항목에 Reports 가 없다** — For you · Recent · Starred · Spaces · Dashboards · Assets · Goals · Apps · More/Customize sidebar 가 전부 | 사이드바 항목 열거에 Reports 부재 · 탭 쪽은 "Horizontal space navigation … Space admins can reorder and remove space tabs" | [what is the new navigation in Jira](https://support.atlassian.com/jira-software-cloud/docs/what-is-the-new-navigation-in-jira/) |
| JS-1 | 설정 진입은 **사이드바 스페이스 이름 옆 `⋯`** 이다 | "Next to the name of your space in the sidebar, select **More actions** (•••), then **Space settings**." | [navigate to your work](https://support.atlassian.com/jira-software-cloud/docs/navigate-to-your-work/) |
| JS-2 | 설정 안에서는 **설정 자체의 사이드바**로 페이지를 고른다 | "…then selecting **Space settings** and choosing **Permissions from the sidebar**." | [change which permission scheme a space uses](https://support.atlassian.com/jira-cloud-administration/docs/change-which-permission-scheme-a-space-uses/) |
| JI-1 | 아이콘은 **별도 메뉴가 아니라 「Details」 페이지** 안에 있다 | "Select **Details**." / "Under your space's current avatar, select **Change icon**." | [edit a space's details](https://support.atlassian.com/jira-work-management/docs/edit-a-projects-details/) |
| JI-2 | 아이콘은 **기본 아이콘 목록 중 선택**하거나 업로드한다 | "Choose from a **default icon** or upload your own, then use **Select** to save your choice." | 〃 |
| JI-3 | Details 페이지는 아바타를 **다른 기본 필드와 한 화면에** 둔다 | 필드 = Name · Space key · URL · Description · Category · **Avatar** · Space owner · Default assignee · Space background · Card covers | 〃 |

### ★ 조회가 착수 판단 2건을 뒤집었다

**① 리포트 위치 — 컨트롤러가 틀렸고 Maxi 가 맞았다.**
1차 조회에서 [velocity chart](https://support.atlassian.com/jira-software-cloud/docs/view-and-understand-the-velocity-chart/)
의 "From your space's **sidebar**, select **Reports** then **Velocity Chart**" 를 근거로
「Jira 는 리포트를 사이드바에 둔다」고 보고했다. Maxi 가 「지라 클라우드에서는 리포트가
사이드바에 없는데?」로 되물어 재조회했고, **그 문서 쪽이 구 내비게이션 문구**임이 확인됐다 —
정본 진입 문서(`generate-a-report`)는 space navigation 이라 적고, 신 내비게이션 문서의
사이드바 항목 열거에 Reports 가 아예 없다(JR-1·JR-3).
**한 문서의 한 문장을 근거로 삼은 것이 원인이다.** 같은 개념을 두 문서가 다르게 적을 때
어느 쪽이 현행인지는 「그 개념의 정본 진입 문서」가 정한다.

**② 아이콘 배치 — Jira 는 별도 메뉴를 두지 않는다.**
Maxi 원문 ④는 「프로젝트 아이콘 메뉴 신설」이었으나 Jira 는 Details 안이다(JI-1·JI-3).
Maxi 결정으로 **Jira 배치를 채택**하고 여기에 **생성 폼 아이콘 선택 UI**를 더한다.

### 채택 (이번 변경이 따르는 Jira 동작)

| # | 채택 | 물리는 요구 |
|---|---|---|
| A-1 | 리포트를 **정본 탭으로 승격**하고 사이드바 트리에서 뺀다 (JR-1·JR-3) | Maxi ① + ⑤ |
| A-2 | 리포트 탭 착지는 **개별 차트가 아니라 리포트 목록 화면** (JR-2) | Maxi ⑤ 후속 결정 |
| A-3 | 리포트 화면에서도 **탭바를 유지**한다 — 탭이므로 (J5-1·JR-1) | Maxi 결정(재확인) |
| A-4 | 설정은 `⋯` 로 들어가고 **자체 사이드바**를 가진다 (JS-1·JS-2) | Maxi ② |
| A-5 | 아이콘은 **「일반(Details)」 안**에 두고 **기본 아이콘 목록**에서 고른다 (JI-1·JI-2·JI-3) | Maxi ④ |

### 의도적 편차

- **X-N1 — 설정 화면에서 탭바를 감춘다.** Jira 문서는 설정 진입 시 스페이스 탭이 남는지
  **한 줄도 적지 않는다**(JS-1·JS-2 어느 쪽도 서술 없음 · 실물 스크린샷도 없음).
  근거가 없으므로 「Jira 가 그렇게 한다」고 주장하지 않는다. Maxi 지적 ③을 근거로 감추고,
  **이것이 문서 근거가 아니라 제품 결정임을 여기 명시**한다.
- **X-N2 — 컴포넌트·버전은 탭으로만 남기고 설정 메뉴에서 뺀다.** 두 화면의 라우트가
  `/settings/...` 라 X-N1 과 충돌한다. Jira 는 릴리스·컴포넌트를 스페이스 탭으로 두므로
  «탭이라는 성격»은 Jira 정합이지만, **경로를 안 옮기고 목록에서만 빼는 것**은 이 저장소 사정이다.
  경로 이동은 라우트 id 변경이라 별건으로 남긴다.
- **X-N3 — 아이콘 업로드는 하지 않는다.** JI-2 는 「기본 아이콘 선택 **또는** 업로드」인데
  업로드는 MinIO 연동·썸네일·용량 정책이 붙는다. **기본 아이콘 목록만** 채택하고 업로드는
  이연한다. Maxi 결정 「지라처럼 템플릿이 나열 되어 있어야 함」이 정확히 앞 절반이다.
- **X-N4 — 생성 폼 아이콘 선택은 Jira 대응이 없다.** Jira 스페이스 생성 플로우가 아이콘을
  묻는다는 근거를 찾지 못했다(Details 는 «생성 후» 편집 화면이다). Maxi 요구
  「프로젝트 생성 시 아이콘 추가하는 UI가 필요」를 그대로 채택하되, **대응 없음 — 설정 화면과
  같은 아이콘 그리드 컴포넌트를 재사용**하는 것으로 ADS 준용 대신 자체 자산 재사용을 근거로 삼는다.

### Maxi 최종 결정 (조회 결과 반영 후)

| 항목 | 결정 |
|---|---|
| 리포트 | **탭바에 리포트 탭** + 리포트 화면에서 **탭바 유지** + 리포트 내부 서브내비 |
| 아이콘 | **생성 폼에 아이콘 선택 UI** + **Jira 처럼 기본 아이콘 나열** + **「일반」 탭에 추가** (별도 메뉴 X) |
| 설정 | 사이드바 전체 교체 |

## 도메인 정리

| 항목 | 값 |
|---|---|
| BC | **`issue-tracking` 단일** — `projects` 테이블이 `issue-tracking/V001__issues_initial.sql` 소유 · `BC_KEYWORDS` 의 '프로젝트' 도 같은 BC (정본 `scripts/workflow/classify-task.ts`) |
| 영향 엔티티 | `Project` (Aggregate Root) — `id·key·name·archivedAt` 에 **`iconKey` 1개 추가** |
| 새 용어 | **프로젝트 아이콘** — Maxi 승인(2026-09-08)으로 `glossary.md` 등재. 「사용자 아바타(MinIO 업로드)」와 **구분해서** 적는다 |
| 관련 ADR | **3건 대조 · 충돌 0.** `fr-ux-06-jira-redesign` **D2**(「프로젝트 선택 시 사이드바가 프로젝트 메뉴로 확장」)는 이 PR 의 서브앱 사이드바를 **지지**한다 — 무효화 아님. 같은 ADR 의 `aria-label` 4종 · `<h1>` 34건은 즉사 계약으로 승계(C-1·C-3). `fr-pr-01-user-profile-placement` **D4**(아바타 = MinIO 배선)는 편차 X-N3(업로드 이연)의 근거로 인용 |
| 신규 ADR | **1건 필요** — 아이콘 카탈로그를 프론트가 소유하고 서버는 형식만 검증한다는 결정(스펙 D-2) |
| FR | **신규 0 · 145 불변.** FR-PJ-01·FR-PJ-03 **문구 확장** (Maxi 결정) |

## 스펙

**본체** → [`docs/specs/2026-09-08-project-nav-subapp-icon.md`](../specs/2026-09-08-project-nav-subapp-icon.md) (9섹션 + 설계결정 D-1~D-4 + 엣지 E-1~E-10 + 제약 C-1~C-7 + 완료기준 A-1~A-15 + A-13′·A-13″)

핵심 3줄.

1. **판정을 한 곳에 모은다.** 순수 함수 `resolveProjectShellMode(pathname, projectKey)` 가
   `'tree' | 'settings'` 를 내고 **사이드바와 탭바가 그 함수 하나를 공유**한다. 판정은
   `PROJECT_SETTINGS_NAV` 목록에서 **유도**되므로 컴포넌트·버전 예외를 따로 쓰지 않는다.
2. **리포트는 정본 탭 한 행이 전부다.** `PROJECT_VIEW_TABS` 9→10 · `exact:false` 라
   하위 리포트 화면에서도 탭이 활성이고 탭바가 남는다(A-3).
3. **아이콘 카탈로그는 프론트 단일 소유.** 서버는 `^[a-z][a-z0-9-]{1,31}$` 형식만 보고,
   미지의 키는 **첫 글자 아바타로 폴백**한다. 마이그레이션 1건(`V040`) + `init_codegen.sql` 미러 동반.

## Sanity Check

**❓ 발견 5건** — 전부 스펙 본문에 반영했다(재작성 아님 · 보강 1회).

| # | 발견 | 반영 |
|---|---|---|
| 1 | Kotlin `data class` 의 nullable 필드는 「JSON 부재」와 「명시적 null」이 **둘 다 null** → 이름만 바꾸는 PATCH 가 아이콘을 지운다 | D-4 · E-1 · A-10 |
| 2 | 「사이드바에서 뺐다」와 「다른 데서 닿는다」가 서로를 검사하지 않는다 (`two-lists-never-check-each-other`) | **A-2 차집합 판별식 + 비-공허 짝** 신설 |
| 3 | 아이콘 카탈로그가 DB CHECK·Kotlin enum·TS 3벌이 될 뻔했다 | D-2 로 프론트 단일 소유 · 대가는 E-3 폴백 + A-11 |
| 4 | 탭 9→10 이 오버플로 e2e 의 개수 단언을 깰 수 있다 | A-6 + plan 이 `project-tabs-overflow.spec.ts` 실측 |
| 5 | 「컴포넌트·버전을 설정에서 뺀다」가 도달성 감소로 오해될 수 있다 — 실측상 둘은 **이미 정본 탭** | A-2 가 기계적으로 증명 |

**★ Maxi 결정이 검증 공백을 하나 만들었고 그것도 메웠다.**
「기존 FR 확장 · 145 유지」를 고르면 카운트가 안 바뀌어 `verify-master-plan.sh` 룰 E 가
FR 문구 drift 를 **못 본다**(룰 E 는 개수만 센다). FR 을 신설하는 쪽이 오히려 기계 검증을
받았을 것이다. 결정은 그대로 받되 **A-13′ 문자열 판별식**(sdd ↔ product 두 파일의 FR-PJ-01·03
행에 `icon` 이 둘 다 있는가 · 비-공허 짝)을 완료기준에 신설했다.

**✅ 통과** — gap 잔여 0. 2회 보강 없이 1회로 닫혔다.

## PR 분할 (Maxi 결정 2026-09-08)

분해가 **13 task**(내비 7 + 아이콘 6)로 나와 한 PR 상한(10)을 넘었다. 두 덩어리가 서로 독립이라 갈랐다.
(착수 시점 어림은 12 였고, 분할하며 세어 보니 13 이었다.)

| PR | 범위 | task | 티어 | 마이그레이션 |
|---|---|---|---|---|
| **PR1 = 이 PR (#476)** | 내비게이션 재편 — Maxi ①②③⑤ | **7** | T2(선언) | **0** |
| PR2 (별건) | 프로젝트 아이콘 풀스택 — Maxi ④ | 6 | T3 | 1 (`V040`) |

**PR1 은 `apps/web` + 판별식 + 문서만 건드린다.** 백엔드 0줄 · 마이그레이션 0.
그래서 **선언 티어를 T3 → T2 로 내린다** — 근거는 `MIGRATION` 표면이 이 PR 에 없다는 것이다.
🛑 **절차는 되돌리지 않는다.** spec·plan 은 이미 T3 서식으로 썼고 그대로 둔다(더 강한 쪽은 해가
없다). 실측 티어는 머지 전 `detect-tier` 로 다시 재어 게이트 2 요약에 **선언과 나란히** 싣는다.

**PR2 로 넘어가는 것** — `## Jira 대조` 의 채택 **A-5**(JI-1·JI-2·JI-3) 전량 ·
스펙 §FR-IC-1~8 · §데이터 모델 변경 · §API 인터페이스 · 완료기준 A-8~A-13″.
스펙 파일은 **하나로 둔다** — 두 PR 이 같은 스펙을 나눠 구현하고, 각 PR 이 자기 완료기준만 문다.

## Plan

### Task 1. 셸 모드 정본 — `PROJECT_SETTINGS_NAV` + `resolveProjectShellMode`

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/project/project-shell-mode.ts`, `apps/web/src/components/project/__tests__/project-shell-mode.test.ts`]
- depends-on: []
- jira: [JS-2]

**RED**:
- 파일: `apps/web/src/components/project/__tests__/project-shell-mode.test.ts`
- 테스트 (A-3):
  ```ts
  it('PROJECT_SETTINGS_NAV 전 경로에서 settings 다', () => { /* 10경로 전수 순회 */ })
  it('컴포넌트·버전·보드설정·요약·보드·백로그·타임라인·리포트4 에서 tree 다', () => { ... })
  it('그룹은 4개이고 항목 합이 10이다 (비-공허 하한)', () => { ... })
  it('projectKey 가 undefined 면 tree 다 (★리뷰 E-4)', () => { ... })
  ```
- 실패 메시지 (예상): `project-shell-mode` 모듈 없음

**★리뷰 반영 (E-4 · 9/10)** — `Sidebar` 는 `/issues`·`/dashboards`·`/calendar` 에서도 렌더되고
그때 `projectKey` 는 `undefined` 다. 그 케이스가 A-3 에 없었다. 빠지면 `undefined` 를 치환해
`/projects/undefined/settings/details` 와 비교하고, **우연히 false 라 조용히 동작하다** 나중에
깨진다. 위 4번째 단언이 그것을 못박는다.

**★리뷰 반영 (D-5 · 7/10)** — `PROJECT_SETTINGS_NAV` 소비자는 **항목 0개인 그룹의 헤딩을
그리지 않는다.** 지금은 게이팅이 없어 항상 10개지만, 설정은 권한 게이팅이 붙는 표면이다
(스펙 GAP-1). 코드 한 줄을 지금 넣어 두면 그날 빈 헤딩이 남지 않는다.

**GREEN**:
- 파일: `apps/web/src/components/project/project-shell-mode.ts`
- `PROJECT_SETTINGS_NAV` — 4그룹 10항목 (스펙 §FR-N2 표 그대로 · `컴포넌트`·`버전` 제외)
- `resolveProjectShellMode(pathname, projectKey): 'tree' | 'settings'` — **목록에서 유도**한다.
  🛑 `pathname.includes('/settings/')` 를 쓰지 않는다. 그러면 `컴포넌트`·`버전`을 위해
  예외 분기를 따로 써야 하고, 그 분기가 목록과 갈리는 **두 번째 목록**이 된다(스펙 D-1).

**REFACTOR**:
- KDoc — 「왜 목록에서 유도하는가」 + `two-lists-never-check-each-other` 참조

**검증**: `pnpm --filter web test -- project-shell-mode`

---

### Task 2. 리포트 착지 화면 + 리포트 서브내비 + 라우트

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/routes/projects.$projectKey.reports.index.tsx`, `apps/web/src/components/project/ProjectReportsNav.tsx`, `apps/web/src/components/project/project-report-links.ts`, `apps/web/src/components/project/__tests__/ProjectReportsNav.test.tsx`, `apps/web/src/routes/__tests__/projects.$projectKey.reports.index.test.tsx`, `apps/web/src/router.ts`, `apps/web/src/routes/projects.$projectKey.reports.velocity.tsx`, `apps/web/src/routes/projects.$projectKey.reports.cfd.tsx`, `apps/web/src/routes/projects.$projectKey.reports.cycle-time.tsx`, `apps/web/src/routes/projects.$projectKey.reports.worklog.tsx`]
- depends-on: []
- jira: [JR-2]

**RED** (동반 테스트):
- 착지 화면이 리포트 **4종 링크를 전부** 그린다 (A-7 의 절반)
- `ProjectReportsNav` 가 **자기 자신을 포함한 4개**를 그리고 현재 것에 `aria-current="page"` 를 준다
- 4개 리포트 화면 각각에서 나머지 3개로 가는 링크가 있다 (A-7)

**GREEN**:
- `project-report-links.ts` — 리포트 4종 **단일 정본**(`ProjectTree.REPORT_LINKS` 를 여기로 이관).
  🛑 목록을 두 벌 두지 않는다 — Task 4 판별식이 이 상수를 직접 읽는다.
- `ProjectReportsNav.tsx` — `nav` + `Link`. **Radix Tabs 금지**(C-5)
- `projects.$projectKey.reports.index.tsx` — 4종 카드 목록 (JR-2 「목록/인사이트 화면」)
- `router.ts` — `projectReportsIndexRoute` 등록 + 헤더 주석의 라우트 수 갱신
- 기존 리포트 4화면에 `<ProjectReportsNav />` 삽입

**★리뷰 반영 (D-4 · 8/10)** — 카드에 **제목만 두지 않는다.** 제목만이면 「벨로시티」와
「사이클/리드 타임」 중 무엇을 누를지 모른다. 카드마다 **답하는 질문 한 줄**을 붙인다.

| 카드 | 한 줄 |
|---|---|
| 벨로시티 | 스프린트마다 얼마나 끝냈나 |
| 누적 흐름도(CFD) | 어느 단계에 일이 쌓이나 |
| 사이클/리드 타임 | 하나 끝내는 데 얼마나 걸리나 |
| 작업 로그 | 누가 어디에 시간을 썼나 |

**REFACTOR**:
- 카드 스타일을 기존 `EmptyState`/카드 프리미티브로 정렬 (§4 재사용 자산)

**검증**:
- 기존 E2E: `apps/web/e2e/project-tabs-overflow.spec.ts` (탭 목록 상수) · 리포트 관련 spec 사전 grep
- 눈확인: 리포트 착지 카드 4장 · 리포트 서브내비 활성 표시 — **라이트/다크 양쪽**

---

### Task 3. 탭바 — 「리포트」 탭 신설(9→10) + 설정 서브앱에서 탭바 숨김

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/project/project-view-tabs.ts`, `apps/web/src/i18n/project-view-labels.ts`, `apps/web/src/components/project/ProjectViewChrome.tsx`, `apps/web/src/components/project/__tests__/project-view-tabs.test.ts`, `apps/web/src/components/project/__tests__/ProjectViewChrome.test.tsx`, `apps/web/src/i18n/__tests__/project-view-labels.test.ts`, `apps/web/e2e/project-tabs-overflow.spec.ts`]
- depends-on: [1, 2]
- jira: [JR-1, JR-3, J5-1]

**RED**:
- `PROJECT_VIEW_TABS` 가 **10개**이고 `reports` 키가 있다 (A-6)
- 실 라우트 전수에서 **활성 탭이 2개 이상이 되지 않는다** (기존 단언 확장)
- 설정 라우트 렌더 시 `role="navigation"` `name="프로젝트 뷰 전환"` **부재**, 비설정에서 **존재** (A-5)
- 리포트 하위 화면(`/reports/velocity`)에서 **리포트 탭이 활성이고 탭바가 있다** (A-3 · JR-1)

**GREEN**:
- 탭 행 추가 — `{ key:'reports', to:'/projects/$projectKey/reports', label:'리포트',
  usesProjectParam:true, exact:false }`.
  🛑 `summary` 의 `exact:true` 를 **건드리지 않는다** — 빠지면 전 화면에서 요약이 함께 강조된다.
- `ProjectViewChrome` — `resolveProjectShellMode(...) === 'settings'` 이면 `<ProjectNavTabs>` 를
  렌더하지 않는다. **`ProjectViewHeader` 는 남긴다**(제목까지 사라지면 설정 화면에 정체성이 없다).

**★리뷰 반영 (E-8 · 7/10) — 탭을 «맨 끝»(버전 뒤)에 넣는다.**
plan 초안은 위치를 안 정했는데, 위치가 오버플로에서 무엇이 접히는지를 정한다. Jira 는 Reports
위치를 문서로 못박지 않으므로 **선택**이다. 맨 끝이면 기존 9탭의 **인덱스가 안 바뀌어**
`resolveActiveTabIndex`·핀 고정 회귀 표면이 최소다.

**★리뷰 반영 (D-3 · 8/10) — 탭바가 사라지는 것을 본문이 설명하게 한다.**
탭 10개를 보다가 설정을 누르면 통째로 사라진다. 첫 반응은 「내가 프로젝트를 나갔나?」다.
`ProjectViewHeader` 가 설정 모드에서 프로젝트명 아래 **「프로젝트 설정」 부제 한 줄**을 낸다.
🛑 `h1` 을 하나 더 만들지 않는다 — 부제는 `p` 다(e2e `<h1>` 단독 34건 · C-3).

**REFACTOR**:
- 「9탭」이라 적힌 주석·KDoc 전수를 **10탭**으로 교정.

**★리뷰 반영 (E-7 · 7/10) — 실측 끝났다. 「구현자가 실측」으로 미루지 않는다. 6곳이다.**

| 파일:행 | 무엇 |
|---|---|
| `project-view-tabs.test.ts:46` | `expect(PROJECT_VIEW_TABS).toHaveLength(9)` |
| `project-view-tabs.test.ts:108` | 〃 (두 번째 단언) |
| `e2e/project-tabs-overflow.spec.ts:21` | 탭 상수 — 「정본 9탭, 소스와 같은 순서」 |
| 〃 `:69` | 불변식 ① 「접힌 게 없으면 9개가 다 보인다」 |
| 〃 `:77` | 불변식 ② 「접힌 것을 펼치면 정확히 9개」 |
| 〃 `:93` | 「마지막 탭(**버전**)은 이 폭에서 확실히 접힌다」 → E-8 로 **`리포트`** 가 된다 |

주석만 고치면 red 다 — **숫자가 코드에 있다.**
그 밖 주석 전용 — `project-view-tabs.ts` · `ProjectNavTabs.tsx` · `ProjectViewHeader.tsx` ·
`router.ts` · `router.admin-guards.test.tsx`.

**★★ 구현 실측 정정 (2026-09-08) — 위 표는 6곳인데 실제로는 8곳이었다.**
E-7 을 「실측 완료」라 적어 구현자에게 「이것만 보면 된다」로 읽히게 만든 것이 이 표의 잘못이다.
구현이 표 밖 2곳을 더 찾았다.

| 누락분 | 무엇 | 왜 표가 놓쳤나 |
|---|---|---|
| `ProjectNavTabs.test.tsx:119` | `expect(links.length).toBe(9)` | **주석이 아니라 코드 숫자**인데 `9탭` 문자열이 없어 grep 에 안 걸렸다 |
| `e2e/project-tabs-overflow.spec.ts:126·130·135` | 뷰포트 폭 `880/860/880` → `960/900/960` | 숫자가 **탭 개수가 아니라 픽셀**이라 「9」를 찾는 눈에 안 보인다. 탭 10종에서 880px 는 7개만 남아 실측 red(`expected 10, received 7`) |

★그 e2e 폭은 **원래 설계대로 스스로 알렸다** — 종전 주석이 「탭이 10종이 되면 첫 단언이 먼저
red 가 되어 폭을 다시 고르라고 알린다. 조용히 통과하지 않는다」라고 예고해 뒀고 실제로 그렇게 됐다.
새 값의 임계는 900↔940(900 → 8+트리거 · 940 → 10)이고 960 은 그 위 여유값이다. 왕복 불변식과
데스크톱 구간(>768px) 제약은 그대로다.

★`router.ts:1` 의 「9탭 전량 프로젝트 스코프」는 **2026-09-07 J5-12 시점의 changelog** 라
숫자를 바꾸면 거짓이 된다 — 의도적으로 보존했다.

**검증**:
- 기존 E2E: `apps/web/e2e/project-tabs-overflow.spec.ts` S2·S3·S4 (개수 불변식 3곳)
- 눈확인: 탭 10개 배치 · 좁은 폭 「더 보기」 · 설정 화면에 탭바 없음 + **부제 「프로젝트 설정」** — **라이트/다크**

---

### Task 4. A-2 도달성 차집합 판별식 (비-공허 짝)

**메타**.
- agent: `frontend-engineer`
- files: [`scripts/workflow/project-nav-reachability.test.ts`]
- depends-on: [1, 3]
- jira: []

**RED → GREEN 순서가 이 task 의 요점이다.**
판별식을 **먼저** 세우고 Task 5 가 사이드바에서 링크를 지운다. 순서를 바꾸면 지운 뒤에
「없어진 게 없다」를 확인하는 꼴이라 **잃어버린 링크를 못 잡는다.**

**RED**:
- 파일: `scripts/workflow/project-nav-reachability.test.ts`
- 단언 (A-2):
  ```ts
  // LEGACY_16 = 이 PR 이전 ProjectTree 의 REPORT_LINKS 4 + SETTINGS_LINKS 12.
  // 🛑 소스에서 읽지 않는다 — 그 상수가 이 PR 로 사라진다. 고정 픽스처로 박는다.
  assert.deepEqual(LEGACY_16.filter(p => !reachable.has(p)), [])
  ```

**★리뷰 반영 (E-3 · 9/10) — `LEGACY_16` 의 출처는 «소스 상수»다. e2e 가 아니다.**
실측으로 **두 목록이 이미 갈려 있다** — `e2e/project-tree.spec.ts` 의 `SETTINGS_LINK_CONTRACT`
는 **11**개인데 `ProjectTree.SETTINGS_LINKS` 는 **12**개다(「일반」이 e2e 에 없다 · FR-PJ PR-5
FE-4 가 11→12 로 늘릴 때 e2e 를 안 고쳤다). 제목의 「직접링크3」도 실제 `DIRECT_LINK_CONTRACT`
**2** 와 다르다. **e2e 를 출처로 잡았다면 「일반」 1건을 처음부터 놓쳤다.**
→ 이 PR 직전 커밋의 `ProjectTree.tsx` 에서 뽑고 **그 SHA 를 판별식 헤더에 적는다.**
이 드리프트 자체가 A-2 가 필요한 이유의 **실물 증거**이므로 헤더에 함께 인용한다.

**★리뷰 반영 (E-5 · 8/10) — 이 판별식은 «회귀 방지»지 «불변식»이 아니다.**
`LEGACY_16` 은 이 PR 이후 아무도 갱신하지 않는다 — 새 설정 화면이 생겨도 16 그대로다.
그 성격을 헤더에 못박는다. 진짜 불변식(「모든 설정 라우트가 어딘가에서 닿는다」)은 `router.ts`
를 읽어야 하고 **별건이다. 지금 범위를 넓히지 않는다.**
  `reachable` = `PROJECT_SETTINGS_NAV` 경로 ∪ `PROJECT_VIEW_TABS` 경로 ∪ `project-report-links` 경로
- **비-공허 짝** — ① 파싱 결과가 0건이면 **실패**로 떨어뜨린다(두 빈 집합은 같다 ·
  `partial-column-parser-lets-unread-column-rot`) ② 픽스처로 판정 함수를 직접 흔들어
  `PROJECT_SETTINGS_NAV` 에서 1건을 빼면 **반드시 red** 임을 같은 파일에서 단언한다

**GREEN**:
- 현 상태에서 green 이어야 한다(아직 아무것도 안 지웠으므로). **뮤테이션으로 red 를 세운다** —
  green 으로 시작하는 판별식은 공허 통과와 구분되지 않는다.

**REFACTOR**:
- 헤더 주석에 「왜 LEGACY_16 을 하드코딩하는가」 + 이 판별식이 지키는 사고(C-2)

**검증**: `node --experimental-strip-types --test scripts/workflow/project-nav-reachability.test.ts`
+ 뮤테이션 2종 red 실측 (**`grep -c` 로 뮤테이션이 실제로 적용됐는지 되잰다** — BSD `sed` 가
대괄호·캐럿을 미매치해 뮤테이션 미적용인데 초록이던 실측 사고가 있다)

---

### Task 5. 설정 서브앱 사이드바 — `ProjectSettingsNav` + `Sidebar` 모드 분기

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/project/ProjectSettingsNav.tsx`, `apps/web/src/components/project/__tests__/ProjectSettingsNav.test.tsx`, `apps/web/src/components/layout/Sidebar.tsx`, `apps/web/src/components/layout/__tests__/Sidebar.test.tsx`, `apps/web/src/i18n/nav-labels.ts`, `apps/web/src/i18n/__tests__/nav-labels.test.ts`]
- depends-on: [1]
- jira: [JS-1, JS-2]

**RED**:
- 설정 경로에서 `Sidebar` 가 **프로젝트 트리 대신** 설정 메뉴를 그린다 (S2-①)
- 설정 메뉴에 **4그룹 10항목**이 전부 있다
- 최상단에 `← {프로젝트명}` 복귀 링크가 있고 목적지가 `/projects/$projectKey` 다 (S3)
- **`<h1>` 이 없다** (C-3)
- 새 nav `aria-label` 이 `navLabels.projectNav`('프로젝트')를 **substring 으로 품지 않는다** (C-1)

**★리뷰 반영 (D-1 · 9/10) — 「`ProjectTree` 관례대로 `sr-only`」가 그 관례의 «전제»를 안 봤다.**
트리의 `sr-only` 는 **첫 글자 아바타라는 시각 앵커가 있어서** 성립한다. 설정 항목 10개는
아이콘이 없어 64px 레일에서 라벨을 숨기면 **구분 불가능한 빈 행 10개**가 된다.
→ **항목마다 lucide 아이콘을 준다.** `lib/settings-hub-links.ts` 가 개인 설정 11개에 이미
그 패턴을 쓴다(**재사용 자산 · 새로 만들지 않는다**). 펼침 상태에서도 스캔이 빨라진다.

**★리뷰 반영 (D-2 · 9/10) — 돌아오는 길이 스크롤하면 사라진다.**
10항목+그룹 헤딩 4개면 짧은 뷰포트에서 `← ATLAS` 가 화면 밖이다. 「내가 어디 있나」는
**항상** 보여야 한다(Krug wayfinding). → 복귀 링크를 **sticky top** 으로 둔다.
(대안이던 「`ProjectViewHeader` 프로젝트명을 링크로」는 D-3 부제와 자리가 겹쳐 접었다.)

**GREEN**:
- `ProjectSettingsNav.tsx` — 그룹 헤딩(`h2`/`h3`) + **아이콘 + 라벨** `Link` 목록.
  복귀 링크는 sticky. 접힘 레일에서는 아이콘만 남고 라벨은 `sr-only` (E-8 동형)
- **항목 0개인 그룹은 헤딩도 그리지 않는다** (★D-5)
- `Sidebar.tsx` — `useParams({strict:false})` 로 `projectKey` 를 읽고
  `resolveProjectShellMode` 로 분기. **판정식을 여기 다시 쓰지 않는다**(A-4)
- `nav-labels.ts` — 새 라벨 1개.
  🛑 값 선정 주의 — `nav-labels.test.ts` FR15 가 **모든 값 쌍의 substring 관계 0**과
  **값 중복 0** 을 단언한다. `'프로젝트 설정'` 은 `'프로젝트'` 를 품어 **red 다.**
  이 제약을 만족하는 값을 고르거나(예: `'스페이스 설정 메뉴'`) 값을
  `project-view-labels` 처럼 **별도 레지스트리**로 가른다 — 어느 쪽인지는 구현자가 실측으로 정한다.

**REFACTOR**:
- 그룹 편성이 Jira 근거가 아님(X-N5)을 KDoc 에 명시

**검증**:
- 기존 E2E: **사전 grep 실측 완료 — 8 spec 이 설정 경로를 연다.**
  `project-member-management`·`automation-rules`·`slack-channel-mapping`·`workflow-scheme-assignment`·
  `workflow-scheme-in-use-modal`·`admin-scheme-index-nav`·`project-crud`·`project-tree`.
  🛑 **앞 7개는 전부 `page.goto(SETTINGS_URL)` 직접 진입**이라 사이드바 변경에 영향이 없다(실측).
  영향받는 것은 `project-tree` 하나이고 그것은 Task 6 이 맡는다.
- 눈확인 (★D-6): 설정 사이드바 그룹 4개 · **sticky 복귀 링크가 스크롤해도 남는지** ·
  64px 레일에서 **아이콘만으로 구분되는지** · **그룹 헤딩이 다크에서 읽히는지**
  (`text-sidebar-foreground/60` 관례 — 새 토큰 만들지 않는다. #472 「다크 인라인코드가 안 보임」이
  같은 형태의 사고다) — **라이트/다크 양쪽**

---

### Task 6. `ProjectTree` — 「리포트」·「프로젝트 설정」 중첩그룹 제거

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/layout/ProjectTree.tsx`, `apps/web/src/components/layout/__tests__/ProjectTree.test.tsx`, `apps/web/src/components/layout/__tests__/navigation-contract.test.tsx`, `apps/web/e2e/project-tree.spec.ts`, `apps/web/e2e/project-velocity.spec.ts`, `apps/web/e2e/project-cfd.spec.ts`, `apps/web/e2e/project-cycle-time.spec.ts`]
- depends-on: [4, 5]
- jira: [JR-1, JR-3]

**★★ 실측 정정 (2026-09-08 · Task 3 구현이 보고) — `project-cycle-time.spec.ts` 가 빠져 있었다.**
리포트 spec 3개가 전부 「**리포트는 탭이 아니다**」라는 주석을 달고 사이드바 그룹으로 진입하는데,
plan 초안은 `velocity`·`cfd` 둘만 `files` 에 넣었다. 세 번째가 같은 상태인 것을 **분해 시점에
전수로 확인하지 않은 것**이 원인이다(2개를 보고 「둘이구나」로 닫았다).
이 PR 로 리포트가 탭이 되므로 그 주석은 **셋 다 거짓이 된다.**

| spec | stale 주석 위치 |
|---|---|
| `project-velocity.spec.ts` | `:77`·`:80` |
| `project-cfd.spec.ts` | `:87`·`:90` |
| **`project-cycle-time.spec.ts`** | `:113`·`:116` ← **초안 누락분** |

**그 밖 stale 「9탭」·「리포트는 탭이 아니다」 잔여** (다른 표면 · 이 PR 범위 밖이면 그대로 둔다) —
`scrum-board.spec.ts:113` · `use-tab-overflow.test.ts:15` · `board-labels.ts:92` ·
`backlog-labels.ts:68` · `backlog.test.tsx:251` · `projects.board.test.tsx:1155` ·
`reports.cycle-time.test.tsx:132`. **손댈지 말지를 구현자가 판단하고 보고한다** —
「리포트는 탭이 아니다」류는 거짓이 됐으므로 고치고, 단순 「9탭」 표기는 그 문장이 **현재 사실을
서술하는가 과거 시점 기록인가**로 가른다(`router.ts:1` 이 후자의 선례다).

**의존이 4·5 인 이유** — 판별식(4)과 대체 진입로(5)가 **먼저 초록**이어야 이 삭제가 안전하다.
순서를 바꾸면 16화면이 고아인 구간이 커밋 히스토리에 남는다.

**RED**:
- `ProjectTree` 렌더 결과에 `리포트`·`프로젝트 설정` 디스클로저 버튼이 **0개** (A-1)
- 펼친 프로젝트 하위에 보드 목록 + `백로그` + `타임라인` **만** 있다 (S1)
- `aria-label="프로젝트"` nav 는 **그대로 존재**한다 (C-1)

**GREEN**:
- `ProjectSubLinkGroup` 호출 2건 제거 · `REPORT_LINKS`·`SETTINGS_LINKS`·`REPORTS_GROUP_LABEL`
  삭제 · `reportsExpanded`/`settingsExpanded` 상태와 그 props 제거
- **`SpaceActionsMenu` 의 `프로젝트 설정` 항목은 남긴다** — JS-1 의 진입로이고 Maxi ②가
  「`⋯` 를 눌러 들어가면」을 전제한다. `PROJECT_SETTINGS_PATH` 상수도 유지
- `ProjectSubLinkGroup` 컴포넌트 자체는 소비자가 0이 되므로 **함께 삭제**(내 변경이 만든 고아)

**REFACTOR**:
- `:460` 주석(「16화면의 유일한 진입로」)을 **새 사실로 교체** — 이제 탭바와 설정 사이드바가
  진입로이고 그 보증은 Task 4 판별식이다

**★리뷰 반영 (E-1 · 10/10) — 리포트 E2E 2건은 `page.goto` 가 «금지»돼 있다.**
`project-velocity.spec.ts:83` · `project-cfd.spec.ts:93` 주석 원문 —
「사이드바 리포트 그룹에서 링크 클릭 (**SPA 내부 이동 — goto 금지, MSW store 리셋**)」.
이 task 가 그 그룹을 지우면 **두 spec 의 유일한 진입 동작이 사라진다.**
🛑 `goto` 로 대체하면 MSW store 가 리셋돼 시드가 날아가고 **무의미한 초록**이 된다.
→ 대체 경로는 **탭바 `리포트` → 착지 카드 클릭** (2클릭 · 전부 SPA 내부). 두 spec 을
`files` 에 명시적으로 넣었다. 두 spec 머리의 「리포트는 탭이 아니다」 주석도 **거짓이 되므로**
같은 커밋에서 교체한다.

**★리뷰 반영 (E-2 · 10/10) — `project-tree.spec.ts` S3 는 「고친다」가 아니라 「재작성」이다.**
그 테스트(`:103`)의 존재 이유가 **리포트4+설정11 그룹 전수 href 확인**이다. 지우는 대상이
곧 테스트 대상이라 부분 수정이 성립하지 않는다.
→ ①S3 를 「하위에 보드목록+백로그+타임라인만 · 리포트·설정 토글 **0개**」로 재작성
②원래 지키던 **「죽은 링크 0」 계약은 Task 4 판별식이 승계**한다는 것을 그 spec 주석에 남긴다.
계약을 옮겼다는 기록이 없으면 다음 사람이 「보증이 사라졌다」고 읽는다.

**검증**:
- 기존 E2E (**사전 grep 실측 완료**): `project-tree.spec.ts` S3 재작성 ·
  `project-velocity.spec.ts`·`project-cfd.spec.ts` 진입 경로 교체.
  나머지 8 spec 은 `page.goto` 직접 진입이라 무영향
- 눈확인: 사이드바가 짧아짐 · `⋯` → 프로젝트 설정 진입 — **라이트/다크**

---

### Task 7. E2E + 문서 동기화

**메타**.
- agent: `qa-engineer`
- files: [`apps/web/e2e/project-settings-nav.spec.ts`, `apps/web/e2e/project-member-management.spec.ts`, `apps/web/src/routes/projects.$projectKey.settings.details.tsx`, `docs/plans/2026-09-08-project-nav-subapp-icon.md`, `docs/specs/2026-09-08-project-nav-subapp-icon.md`]
- depends-on: [3, 5, 6]
- jira: [JR-1, JR-2, JS-1, JS-2]

**★★ 구현이 넘긴 후속 3건 (2026-09-08 실측) — 이 task 가 받는다.**

**후속 1 — `project-member-management.spec.ts:68` 의 단언이 «약해졌다».**
그 줄은 `page.getByText('멤버').first()` 인데, 설정 사이드바의 `멤버` 링크가 **DOM 상 먼저**
오므로 `.first()` 가 이제 본문이 아니라 **사이드바 쪽**을 집는다.
🛑 **red 가 아니다**(`toBeVisible()` 이라 사이드바 링크로도 통과한다 · Task 5 가 실행으로 확인).
그래서 **아무도 알려 주지 않는다** — 계약이 조용히 「본문에 멤버 화면이 떴다」에서
「어딘가에 '멤버'라는 글자가 있다」로 내려앉았다. 스코프를 본문으로 좁혀 원래 의도로 되돌린다.

**후속 2 — 설정 부제 조회는 role 스코프 또는 settled 로케이터를 쓴다.**
SPA 전환 «중» `getByText('프로젝트 설정', { exact: true })` 가 순간 **2건**이 된다 —
두 번째는 `ProjectTree` 의 설정 그룹 토글 `button` 이 전환 프레임에 남은 것이다.
Task 6 이 그 토글을 지우면 사라지지만, **지금 그 사실에 기대어 조회를 느슨하게 쓰면**
나중에 비슷한 문구가 생겼을 때 다시 깨진다.

**후속 3 — `/settings/details` 에 프로젝트명이 «두 번» 뜬다.**
셸 헤더 `<h1>Atlas 프로젝트</h1>` 바로 아래 본문 `<h2>Atlas 프로젝트</h2>` 다.
**이 PR 이전부터 있던 것**(헤더가 셸로 올라간 J5-8 의 잔재)이지만, ★D-3 부제가 그 둘 사이에
끼면서 **눈에 띄게 나빠졌다** — 「이름 / 프로젝트 설정 / 이름」으로 읽힌다.
정본 계약이 이미 있다 — **J5-11 「뷰는 자기 제목을 다시 쓰지 않는다」**. `ProjectSummaryPage` 는
2026-09-07 에 같은 이유로 자기 `h1` 을 지웠고 그 KDoc 이 「되살리면 같은 이름이 두 줄로 겹친다」
라고 적어 뒀다. 이 화면만 그 처방을 못 받았다.
→ 본문 `h2` 를 **프로젝트명이 아니라 화면 이름(`상세정보`)** 으로 바꾼다. 한 줄이고
이미 결정된 계약을 이 화면에 적용하는 것이라 새 판단이 아니다.
🛑 그 파일의 `h1` 문구 보존 주석(F21)은 **`PageHeader` 시절 것**이라 지금 `h2` 와 무관하다 —
헷갈리지 말 것. 바꾼 뒤 그 주석도 현재 사실로 갱신한다.

**RED** (신규 E2E · A-14 의 PR1 몫):
- 사이드바 `⋯` → 프로젝트 설정 → **탭바 부재** + 설정 사이드바 존재
- 설정 사이드바 항목을 눌러 다른 설정으로 이동해도 **탭바가 계속 없다**
- `← 프로젝트` 로 나오면 탭바·프로젝트 트리 복귀
- 탭바 `리포트` → 착지 화면 4카드 → 하나 눌러 이동 → **탭바 유지**

**GREEN**:
- 셀렉터는 `nav aria-label` 스코프 + `exact: true` 규약을 지킨다
  (`보드`·`백로그` 부분일치 함정 · `project-view-labels` 조회 규약 ①②③)

**REFACTOR**:
- plan `## 리뷰 결과` 아래에 실측 기록 · 스펙 완료기준 중 **PR1 몫에 체크**

**검증**:
- `pnpm --filter web test:e2e -- project-settings-nav project-tabs-overflow` **3회 연속 통과**
- 눈확인 총괄 — 라이트/다크

## Plan 메타

- **task 수**: 7 · **예상 wave**: 5
  (w1 = T1·T2 · w2 = T3·T5 · w3 = T4 · w4 = T6 · w5 = T7)

**★리뷰 반영 (E-6 · 8/10) — wave 1·2 는 같은 worktree 에 2 task 병렬이다. 경로 한정 커밋을 쓴다.**
2026-09-07 실측 — 파일단위 `git add` 로는 못 막는다. **git 인덱스가 프로세스 간 공유**라
한쪽의 GREEN 커밋이 다른 쪽의 RED 산출물을 흡수한다(lint-staged 자동 stage 가 이것을 키운다).
`files` 교집합은 0이지만 **인덱스는 공유**다.
→ 각 task 는 `git add <자기 files 경로만>` 직후 **즉시** 커밋한다. `git add -A`·`git add .` 금지.
그리고 worktree 함정 — `lint-staged` 가 옆 작업의 미커밋 파일을 훔치므로 `--only` 로 좁힌다.
- **구현 규율**: TDD red-first + **ui 시각 검증 트랙**(전 task 에 기존 E2E 목록 + 라이트/다크 눈확인)
- **추가 검증**: `pnpm verify`(lint+typecheck+test+build) · `pnpm test:workflow` · Playwright ·
  `node scripts/build-doc-index.mjs --check` · `bash scripts/verify-master-plan.sh`
- **백엔드**: 0줄 · **마이그레이션**: 0 · **신규 엔드포인트**: 0 · **FR 수**: 145 불변
- **신규 판별식**: 1종 (`project-nav-reachability`) — 비-공허 뮤테이션 짝 필수

**Jira 매핑** (§1-7 차집합) —
`JR-1→T3,T6` · `JR-2→T2,T7` · `JR-3→T3,T6` · `JS-1→T5,T7` · `JS-2→T1,T5,T7` ·
`J5-1→T3` · **`JI-1`·`JI-2`·`JI-3`→ PR2 (아이콘 · 범위 밖 · Maxi PR 분할 결정)** ·
`J5-12`·`J5-13` → 승계만 하고 이 PR 이 바꾸지 않음.
**채택 A-1~A-4 는 전부 물렸고 A-5(아이콘)만 PR2 로 이연 — 차집합 0.**

## 리뷰 결과

렌즈 **2종**(`type == "ui"` 행) — `plan-eng-review` + `plan-design-review`. 한 응답에 병렬 발행.
findings **14건 · BLOCKER 0.** 전부 plan 보강으로 해소하고 각 Task 에 `★리뷰 반영` 으로 인라인했다.

> **목업 생성은 건너뛰었다.** `design` 바이너리는 있으나(실측 `DESIGN_READY`) 이 PR 은 **기존
> 컴포넌트의 재배치**이고 시각 기준은 루트 `DESIGN.md` + `jira-parity-contract` 가 이미 소유한다.
> 새 시각 언어를 만드는 작업이 아니다. `codex` 는 미설치(실측 `CODEX_NOT_AVAILABLE`)라
> 외부 렌즈는 단일 모델이다 — 그 사실을 여기 남긴다.

### 🔧 plan-eng-review

| # | 심각도 | finding |
|---|---|---|
| **E-1** | **10/10** | **리포트 E2E 2건이 `page.goto` 를 명시적으로 금지한다.** `project-velocity.spec.ts:83` · `project-cfd.spec.ts:93` 주석 — 「사이드바 리포트 그룹에서 링크 클릭 (**SPA 내부 이동 — goto 금지, MSW store 리셋**)」. Task 6 이 그 그룹을 지우면 두 spec 의 **유일한 진입 동작**이 사라지는데, plan 의 처방(「새 경로로 고친다」)은 `goto` 대체를 암시한다. `goto` 하면 MSW store 가 리셋돼 시드가 날아가고 테스트가 **무의미한 초록**이 된다. → 처방. 탭바 `리포트` → 착지 카드 클릭(2클릭 · 전부 SPA 내부). **Task 6 `files` 에 두 spec 을 명시** |
| **E-2** | **10/10** | **`project-tree.spec.ts` S3 는 「고친다」가 아니라 「재작성」이다.** 그 테스트의 존재 이유가 리포트4+설정11 그룹 전수 href 확인이다(`:103`). plan 은 삭제/재작성 판단을 구현자에게 미뤘다. → 처방. S3 를 「하위에 보드목록+백로그+타임라인만 · 리포트·설정 토글 0개」로 재작성하고, 원래 지키던 **「죽은 링크 0」 계약을 Task 4 판별식이 승계**한다는 것을 그 spec 주석에 남긴다 |
| **E-3** | **9/10** | **두 목록이 이미 갈려 있다 — A-2 가 필요한 이유의 실물 증거.** `SETTINGS_LINK_CONTRACT` **11** vs `ProjectTree.SETTINGS_LINKS` **12**(「일반」이 e2e 에 없다 · FE-4 가 11→12 로 늘릴 때 e2e 를 안 고쳤다). 테스트 제목의 「직접링크3」도 실제 `DIRECT_LINK_CONTRACT` **2** 와 다르다. → 처방. `LEGACY_16` 을 **e2e 가 아니라 `ProjectTree.tsx` 소스 상수**에서 뽑고, 뽑은 커밋 SHA 를 판별식 헤더에 적는다 |
| **E-4** | **9/10** | **`resolveProjectShellMode` 가 프로젝트 밖에서 불린다.** `Sidebar` 는 `/issues`·`/dashboards`·`/calendar` 에서도 렌더되고 그때 `projectKey` 는 `undefined` 다. A-3 단언 목록에 그 케이스가 **없다**. 빠지면 `undefined` 를 치환해 `/projects/undefined/settings/details` 와 비교하고, 우연히 false 라 조용히 동작하다 나중에 깨진다. → **A-3 에 「`projectKey` 부재 → `'tree'`」 행 추가** |
| **E-5** | **8/10** | **`LEGACY_16` 고정 픽스처는 옳지만 반감기가 있다.** 이 PR 이후 아무도 그 배열을 갱신하지 않는다 — 새 설정 화면이 생겨도 16 그대로다. 즉 **회귀 방지지 불변식이 아니다.** → 처방. 헤더에 그 성격을 못박는다. 진짜 불변식(「모든 설정 라우트가 어딘가에서 닿는다」)은 `router.ts` 를 읽어야 하고 **별건이다. 지금 범위를 넓히지 않는다** |
| **E-6** | **8/10** | **wave 2 의 T3·T5 가 같은 worktree 병렬이다.** 2026-09-07 실측(리뷰 F-2) — 파일단위 `git add` 로는 못 막는다. **git 인덱스가 프로세스 간 공유**라 한쪽 GREEN 커밋이 다른 쪽 RED 산출물을 흡수한다. `files` 교집합은 0이지만 인덱스는 공유다. → **경로 한정 커밋**(`git add <경로들>` 직후 즉시 커밋)을 wave 2 에 명시 |
| **E-7** | **7/10** | **탭 9→10 의 파장은 이미 실측됐다 — 「구현자가 실측」으로 미룰 필요가 없다.** 6곳. 유닛 `project-view-tabs.test.ts:46`·`:108` `toHaveLength(9)` · e2e `project-tabs-overflow.spec.ts:21` `VISIBLE_TABS` 상수 · `:69`·`:77` 불변식 2개 · `:93` 「마지막 탭(**버전**)은 이 폭에서 확실히 접힌다」. → **Task 3 REFACTOR 에 6곳 열거** |
| **E-8** | **7/10** | **탭 «순서»를 정하지 않았다.** plan 은 「행 하나를 더한다」고만 적는데, 위치가 오버플로에서 무엇이 접히는지를 정한다. Jira 는 Reports 위치를 문서로 못박지 않으므로 **선택**이다. → **맨 끝(버전 뒤)을 권한다.** 기존 9탭의 인덱스가 안 바뀌어 `resolveActiveTabIndex`·핀고정 회귀 표면이 최소다. 대신 E-7 의 `:93` 주석 대상이 `버전`→`리포트` 로 바뀌므로 같이 고친다 |

### 🎨 plan-design-review

초기 평점 **6/10** — 「무엇이 어디로 가는가」는 정확한데 **문맥 전환의 감각**(전환 순간·돌아오는 길·
좁은 폭)이 비어 있다. 아래 6건 반영 후 **9/10**.

| # | 평점 | finding |
|---|---|---|
| **D-1** | **9/10** | **접힘 레일(64px)에서 설정 사이드바가 「빈 행 10개」가 된다.** plan 은 「`ProjectTree` 관례대로 `sr-only`」라 적는데, 트리는 **첫 글자 아바타라는 시각 앵커가 있고** 설정 항목 10개는 **아이콘이 없다**. 라벨을 숨기면 구분 불가능하다. → 처방 ②를 권한다. ①설정 모드에서 접힘 무시(항상 펼침) ②**항목마다 lucide 아이콘** — `lib/settings-hub-links.ts` 가 개인 설정 11개에 이미 그 패턴을 쓴다(**재사용 자산**). 펼침 상태에서도 스캔이 빨라진다 |
| **D-2** | **9/10** | **돌아오는 길이 하나뿐이고 스크롤하면 사라진다.** 10항목+그룹 헤딩 4개면 짧은 뷰포트에서 `← ATLAS` 가 화면 밖이다. Krug 의 wayfinding — 「내가 어디 있나」는 **항상** 보여야 한다. → 처방. 복귀 링크를 **sticky top** 으로 두거나 `ProjectViewHeader` 의 프로젝트명을 링크로 만든다. **후자가 자산 재사용**이다 |
| **D-3** | **8/10** | **탭바가 사라지는 순간에 설명이 없다.** 방금까지 탭 10개를 보다가 설정을 누르면 통째로 사라진다 — 첫 반응은 「내가 프로젝트를 나갔나?」다. plan 은 **사이드바 교체만** 적고 **본문 쪽 신호가 0** 이다. → 처방. `ProjectViewHeader` 가 설정 모드에서 프로젝트명 아래 「프로젝트 설정」 부제를 낸다. **한 줄이고 방향 감각을 준다** |
| **D-4** | **8/10** | **리포트 착지가 「카드 4장」으로만 적혀 있다.** 제목만 있으면 「벨로시티」와 「사이클/리드 타임」 중 무엇을 누를지 모른다. → 처방. 카드마다 **답하는 질문 한 줄**. 벨로시티 「스프린트마다 얼마나 끝냈나」 · CFD 「어느 단계에 일이 쌓이나」 · 사이클/리드 「하나 끝내는 데 얼마나 걸리나」 · 작업 로그 「누가 어디에 시간을 썼나」. **문구 4줄이고 카드가 실제로 유용해진다** |
| **D-5** | **7/10** | **권한 게이팅이 붙는 날 빈 그룹 헤딩이 남는다.** 스펙은 지금 게이팅 없음(GAP-1)이라 적었지만 설정은 게이팅이 붙는 표면이다. → 처방. **항목 0개면 그룹 헤딩도 안 그린다**를 지금 못박는다. 코드 한 줄이고, 나중에 조용히 깨지지 않는다 |
| **D-6** | **7/10** | **눈확인에 「무엇을 볼지」가 없다.** 설정 사이드바 그룹 헤딩은 `--sidebar-*` 팔레트에 **대응이 없다**(기존 사이드바에 그룹 헤딩이 없었다). 새 토큰을 만들지 말고 `text-sidebar-foreground/60` 관례를 쓰되 **다크에서 60% 가 읽히는지**를 눈확인 항목으로 명시. #472 의 「다크 인라인코드가 안 보임」이 같은 형태다 |

### ★ 리뷰가 바꾼 것

- **E-1·E-2 는 「E2E 를 고친다」를 「E2E 를 재설계한다」로 바꿨다.** 두 리포트 spec 은 `goto` 가
  금지돼 있어 대체 경로가 **2클릭 SPA 내부 이동**이어야 하고, `project-tree` S3 는 지키던 계약을
  Task 4 판별식에 **넘기고** 재작성해야 한다. plan 의 한 줄짜리 「있으면 고친다」로는 이 둘이 안 나온다.
- **E-3 은 A-2 판별식의 정당성을 실물로 증명했다.** `SETTINGS_LINK_CONTRACT` 11 vs 소스 12 는
  이 저장소가 이름 붙인 지배 결함 양식이 **이미 실현된 상태**다. 그리고 `LEGACY_16` 의 출처를
  e2e 로 잡았다면 「일반」 1건을 처음부터 놓쳤을 것이다.
- **D-1 은 「관례를 따른다」가 관례의 «전제»를 안 본 사례다.** 트리의 `sr-only` 는 아이콘이
  있어서 성립한다. 아이콘 없는 목록에 같은 규칙을 적용하면 빈 행이 된다.

**BLOCKER 0** — 14건 전부 plan 보강으로 닫혔다.

## 리뷰 결과 (PR 단위 · 체인 [6])

렌즈 **2종** — `code-reviewer`(절대 규칙 정합) + `/review`(구조·안전성).
**BLOCKER 0 · CONCERNS 4 + 낮은 심각도 3.** 7건 전부 머지 전에 닫았다.

### ★ 두 렌즈가 «독립적으로» 같은 것을 찾았다 — 지적 ①

`ProjectSettingsNav.tsx:82` 의 시각 강조 3줄이 **TanStack 기본값에 우연히 얹혀 있었다.**
그 `Link` 에 `activeProps` 가 없어서 기본값 `{className:'active'}` 가 살아남은 것이고,
**누가 `aria-current` 하나를 추가하는 순간 시각 강조가 통째로 죽는다** — 이 PR 이 탭바에서
방금 고친 결함과 정확히 같은 것이다. 형제 nav 둘은 그 모양으로 고쳐졌는데
(`ProjectNavTabs.tsx:94`·`ProjectReportsNav.tsx:87`) 셋 중 이 파일만 `aria-current` 가 0건이었다.

**리뷰어가 실측으로 방아쇠를 당겨 봤다** — `activeProps={{ 'aria-current': 'page' }}` 를 추가하니
유닛 **41/41 통과**, e2e 도 그 문자열이 0건이라 **아무도 못 잡았다.** 16개 test 중 활성 상태를
보는 것이 하나도 없었던 것이 원인이다. 짝 단언 + 뮤테이션으로 닫았다.

### 지적 전수와 처리

| # | 심각도 | 지적 | 처리 |
|---|---|---|---|
| ① | CONCERNS | 위 — 설정 nav 활성 강조 무방비 | **코드 수정 + 짝 단언 + 뮤테이션** |
| ② | CONCERNS | 스펙 FR-N2 표가 `일반` 인데 코드는 `상세정보` — 스펙이 「정본」이라 부르는 표가 코드와 갈렸다(형태상 `two-lists-never-check-each-other`) | 스펙 `:175`·`:75` 동기화 |
| ③ | CONCERNS | plan 이 red 2건을 「미수정 · 머지 전에 닫아야」로 남겼는데 **이미 `0dc687f55` 로 닫혔다.** 머지 리뷰어가 없는 red 를 보고 차단할 수 있다 | plan 2곳 갱신 |
| ④ | CONCERNS | 리포트 착지 눈확인 미완 — **ui 면제의 대가**라 유일하게 건너뛸 수 없는 칸이다 | 실측 후 기록 |
| ⑤ | 정보 | 판별식이 「두 홉 중 두 번째」만 잰다 — 첫 홉(`⋯` 드롭다운)이 깨지면 **실패 메시지가 엉뚱한 곳을 가리킨다** | 판별식 헤더에 경고 절 추가 |
| ⑥ | 낮음 | plan 산술 2건(12→13 task · 완료기준 개수) | 교정 |
| ⑦ | 낮음 | PR 제목에 「프로젝트 아이콘 신설」이 남음 — 머지 커밋 문구로 **영구히** 남는다 | 제목 교체 |

### 자가보고 6건 대조 — 전부 참으로 확인됐다

리뷰어가 **말만 믿지 않고 직접 뮤테이션을 걸었다.** `PROJECT_SETTINGS_NAV` 의 `members` 한 줄을
바꾸자 8/8 → **2 fail** 로 즉시 red, 복원 후 워킹트리 clean.
`resolveProjectShellMode` 공유는 두 파일에 **경로 문자열 판정 0건**으로 확인(파생식까지 동일).
`SEC_FE` 4-가드는 형제 리포트 라우트와 **동일 상수**이고 신규 라우트에 search/loader/권한 분기가
0이라 보안 표면 증가분이 없다 — **충분** 판정.
e2e `goto` 도피 0 · `skip`/`only` 0 · 선재 e2e 실패 1건은 `TODOS.md` 등재 확인.

### ①④ 처리 결과 (커밋 `8e881ff10`)

**① 은 지적대로였고, 고치면서 «같은 종류의 잠복 결함 1건»을 더 찾았다.**

복귀 링크 `← {프로젝트명}` 의 목적지 `/projects/$projectKey` 는 설정 **10경로 전부의 접두사**다.
`activeOptions` 가 기본(fuzzy)이면 **어느 설정 화면에서나 「현재 페이지」로 켜진다.**
지금은 그 링크에 `[&.active]` 규칙이 없어 눈에 안 보일 뿐이고, **강조를 더하는 날에야 거짓말이
드러나는** ①과 정확히 같은 형태다. `PROJECT_VIEW_TABS` 의 `summary` 탭이 `exact:true` 인 것과
같은 규칙이라 `activeOptions={{ exact: true }}` 를 붙였다. 시각 변화 0.

**뮤테이션 2종 red 실측** (python `assert` 후 치환 · `grep -c` 되재기).

| | 뮤테이션 | 되재기 | 결과 |
|---|---|---|---|
| M1 | 항목 링크에서 `className: ACTIVE_CLASS` 제거 | 2 → 1 | **1 failed** — `aria-current` 단언은 **초록인 채** 시각 강조만 죽는다. 리뷰어가 지목한 시나리오 그대로 |
| M2 | 복귀 링크 `activeOptions={{ exact: true }}` 제거 | 1 → 0 | **2 failed** — 복귀 링크가 모든 설정 화면에서 함께 켜진다 |

짝 단언 3건 추가(16 → 19). 「10항목 전수에서 지금 경로 **하나만**」 형태라 「전부에 붙는」 회귀와
「복귀 링크가 함께 켜지는」 회귀를 같은 자리에서 잡는다. 복귀 링크 비활성 단언에는 **그 짝**
(복귀 목적지에 서면 활성 1건)을 붙였다 — 짝이 없으면 「영원히 비활성」인 구현도 통과한다.

**④ 리포트 착지 눈확인 — 텍스트·포커스 전부 AA 통과. 카드 «경계»만 미달이고 그것은 전역 문제다.**

측정은 `/70` 때와 같은 캔버스 합성 픽셀 방식. 포커스 링은 `:focus-visible` 이 실제로 발화하도록
**Tab 키 13회**로 도달해 쟀다(프로그램적 `.focus()` 로는 못 잰다).

| 항목 | 라이트 | 다크 |
|---|---|---|
| `CardTitle` | **14.10:1** ✅ | **10.47:1** ✅ |
| `CardDescription` | **5.08:1** ✅ | **5.35:1** ✅ |
| 포커스 링 vs 배경 | **3.33:1** ✅ | **8.58:1** ✅ |
| 🟡 카드 ring 경계 | **1.22:1** ❌ | **1.24:1** ❌ |

🟡 **카드 경계는 고치지 않고 숫자만 올린다.** `Card` 프리미티브의 `ring-1 ring-foreground/10`
(DESIGN.md §8 elevation 관례)이라 **앱 전역 카드 전부에 해당**한다 — 대시보드 가젯·이슈 상세가
같은 값이다. 이 화면만 고치면 시각 언어가 갈린다. 라이트는 카드 «면»과 페이지 배경이
**완전히 동일**(1.00:1)이라 경계가 오직 그 ring 하나에 걸려 있다.
WCAG 1.4.11 은 「인접 색으로 구분되어야 **정보가 전달되는**」 컴포넌트에 3:1 을 요구하는데,
카드는 제목·본문이 정보를 다 담고 클릭 대상도 카드 전체라 그 종류는 아니다 —
**위반 단정이 아니라 톤 판단**이고 Maxi 몫이다.

### 수용 — 가짜 그린이 아니라고 판정된 것

`ProjectSettingsNavGroupSection` 의 `items:[]` 테스트는 오늘 부모 경유로 도달 불가지만,
컴포넌트가 실제로 그 분기를 갖고(`:122`) ★D-5 가 명시 요구한 계약이며 KDoc 이
`unreachable-state-fixture-is-fake-green` 을 **스스로 인용해** 판단 근거를 남겼다.

## 구현 중 실측 — 계획을 뒤집거나 보탠 것

wave 1·2 (task 1·2·3·5) 완료 시점 기록. verifier 4건 전부 PASS.

### 🔴 프로덕션 결함 1건을 눈확인이 잡았다 — 계획에 없던 것

**정본 탭바에서 「지금 어느 탭에 있는가」가 시각적으로 표시되지 않고 있었다.**
TanStack `Link` 의 `activeProps` 기본값은 `{ className: 'active' }` 인데 `activeProps` 를 주면
그 기본값이 **통째로 대체된다**(`link.js` 의 `functionalUpdate(activeProps, {}) ?? STATIC_ACTIVE_OBJECT`).
`ProjectNavTabs` 는 `activeProps={{ 'aria-current': 'page' }}` 만 넘겼고, `TAB_LINK_CLASS` 의
`[&.active]:font-semibold` · `[&.active]:text-foreground` 가 **한 줄도 발화하지 않았다.**

| `/projects/ATLAS/backlog` | `.active` | fontWeight | color(라이트) |
|---|---|---|---|
| **수정 전** 활성 탭 `백로그` | **false** | **400** | **rgb(98,111,134)** |
| 수정 전 비활성 탭 9개 | false | 400 | rgb(98,111,134) |
| **수정 후** 활성 탭 | **true** | **600** | **rgb(23,43,77)** |

**활성과 비활성이 바이트 단위로 같았다.** `aria-current` 는 붙어 있었으므로
**스크린리더는 알고 눈으로 보는 사람만 몰랐다.** 유닛이 `aria-current` 만 봐서 초록이었다 —
그래서 짝 단언(`classList.contains('active')` 헬퍼)을 함께 넣어 재발을 막았다.
`ProjectTree` 는 `activeProps` 를 아예 안 줘서 기본값이 살아 정상이었다(대조군).

### 계획이 틀렸던 것 3건

| # | 계획 | 실측 |
|---|---|---|
| 1 | E-7 「9탭 숫자 6곳」 | **8곳.** 「N건」을 쓴 것이 눈가리개였다 — §Task 3 정정표 |
| 2 | Task 6 files 에 리포트 spec **2개** | **3개.** `project-cycle-time.spec.ts` 누락 — §Task 6 정정 |
| 3 | 「TopBar 에 이슈·대시보드·캘린더 대체 경로가 없다」(Task 5 근거) | **부분 오류.** `TopBar.tsx:134` 에 `to="/dashboards"` 가 있다. 다만 그것은 **Atlas 워드마크 홈 링크**이고 접근가능 이름이 `Atlas` 라 「대시보드로 가는 길」로 읽히지 않는다. 이슈·캘린더는 정말 없다 → **결론(메인 nav 유지)은 유지** |

### 계획에 없었지만 실측으로 정한 것 2건

- **`settings/details` 라벨 `'일반'` → `'상세정보'`.** 정본의 `general` 그룹 라벨 `'일반'` 과
  그 첫 항목 라벨이 **같은 글자**라 화면에 「일반 / 일반」이 붙어 보였다. 그 라벨의 e2e 의존이
  **0건**임을 실측하고(`SETTINGS_LINK_CONTRACT` 11개에 「일반」이 애초에 없다 — E-3 기전),
  Jira 원문 JI-1 「"Select **Details**."」에 맞췄다. **중복 회피가 아니라 Jira 정합 회복**이다.
- **그룹 헤딩 대비 `/60` → `/70`.** `/60` 이 라이트 **3.91:1** 로 AA(4.5) 미달이었다.
  `/70` 상향 후 **라이트 5.30:1 · 다크 6.14:1** 로 둘 다 통과.
  측정은 캔버스 합성 픽셀 읽기와 수동 oklab 변환 **두 방법이 교차 검증**됐다.
  (「새 토큰 금지」가 막은 것은 새 CSS 변수 신설이지 같은 토큰의 알파 조정이 아니다.)

### verifier 가 controller 를 교정한 것 2건

이 체인에서 **verifier 가 실제로 값을 했다.** 둘 다 controller 의 첨부 축약을 잡았다.

- Task 1 verifier — 「구현 파일 KDoc 이 첨부에서 생략됐다고 스스로 밝히므로 ⑤는 **미검증으로
  분리**한다. PASS 에 넣으면 확인 안 한 것을 확인했다고 말하는 셈이다」. `diff --stat` 143줄 vs
  첨부 57줄이라는 **줄 수 불일치**를 근거로 댔다. controller 가 grep 으로 닫았다.
- Task 3 verifier — 표 밖 2곳 추가 수정이 범위 이탈인지 물었을 때, 이 저장소 메모리
  (`지시문에 개수를 쓰지 마라 · 「N건」은 눈가리개`)를 인용해 **「6곳은 실측 결과지 상한
  선언이 아니다」**로 판정했다. controller 가 만든 함정을 정확히 지목한 것이다.

### Task 7 실측 — E2E + 고아·stale 정리 (2026-09-08)

신규 `e2e/project-settings-nav.spec.ts` 4시나리오. 지정 명령
(`project-settings-nav project-tabs-overflow project-tree project-velocity project-cfd
project-cycle-time project-member-management`)이 **3회 연속 29 passed · EXIT 0**
(37.2s / 37.5s / 37.1s). 판별식 `# pass 633 / # fail 0 · EXIT=0`.

#### 계획이 틀렸던 것 2건 — 둘 다 **처방을 그대로 쓰면 red** 였다

| # | 계획·지시 | 실측 |
|---|---|---|
| 1 | 「셀렉터는 `exact: true` 규약을 지킨다」 | 리포트 착지 **카드 링크에는 그대로 못 쓴다.** `<Link>` 가 `CardTitle` 과 `CardDescription` 을 함께 감싸 접근가능 이름이 **`라벨 + 질문 한 줄`** 이다(실측 `벨로시티 스프린트마다 얼마나 끝냈나`). 라벨만으로 exact 를 걸면 **element not found**. 둘을 이어 붙여 exact 로 재도록 고쳤고, 그래서 같은 단언이 「질문 줄이 실제로 함께 나온다」(★리뷰 D-4)까지 함께 진다 |
| 2 | 후속 1 처방 「스코프를 본문으로 좁힌다」 | **행까지만 좁히면 여전히 red.** 밥의 행 «안»에 역할 배지 span 과 역할 Select 의 값 span 이 둘 다 `멤버` 라 strict mode violation 이 난다. 같은 파일 S3 가 이미 `span.rounded-full` 로 그 둘을 가르고 있었다 — 처방은 「행」이 아니라 **「행 + 배지 span」** 이다 |

#### B-4 가 끌고 온 기존 조회 — grep 전수 2곳

`settings/details` 를 프로젝트명 heading 으로 찾던 자리는 **유닛 2건 + e2e 3줄**이었다.
e2e 쪽(`project-crud.spec.ts` S4·S5)은 Task 7 의 files 목록에 **없던 파일**이다 —
「그 화면의 기존 테스트를 먼저 grep 하라」가 없었으면 `level: 2` 조회 3줄이 머지 시점에
red 로 터졌을 것이다. 이름은 이제 셸 헤더 `<h1>` 이므로 `level: 1` 로 옮겼고, S4 의
「재조회로 새 이름 반영」 계약은 같은 `useProject` 캐시를 셸 헤더가 읽으므로 그대로 산다.

유닛 T6-0(「RouteAdapter 가 projectKey 를 전달한다」)은 종전에 **헤딩 문구**로 전달을
재고 있었는데, `useProject` 목이 인자와 무관하게 같은 값을 돌려주므로 그 단언은 애초에
전달을 관측하지 못했다. 헤딩이 화면 이름으로 바뀐 김에 조회 훅의 **인자**를 직접 보게 했다.

#### ✅ 계획 밖 발견 1건 — Task 7 은 미수정 보고했고 **controller 가 닫았다** (커밋 `0dc687f55`)

`apps/web/src/components/__tests__/button-primitive-usage.test.ts` 가 red 다
(전체 유닛 `684 파일 중 1 failed` · 그 1건이 이것).

```
expected [ …(18) ] to deeply equal [ …(19) ]
-   "components/layout/ProjectTree.tsx::P6",
```

Task 6 이 `ProjectTree` 에서 중첩그룹 디스클로저 `<button>` 을 지우면서 그 파일의 원시
`<button>` 발생이 **0개**가 됐는데(`grep -n "<button" ProjectTree.tsx` → 0), 판별식의
`EXPECTED_OUT`(`:164`)에 그 키가 남아 「초과」로 red 다. 그 판별식 자신의 주석이 예고한
**유령 키**(`옛 경로를 남기면 유령 키가 되어 「초과」로 red 가 난다`) 그대로다.
처방은 `EXPECTED_OUT` 에서 그 한 줄 삭제(`:68` 의 `BATCH_FILES` 항목은 `ProjectTree` 가
여전히 `Button` 프리미티브를 쓰므로 **그대로 둔다**). Task 7 의 허용 파일 목록 밖이라
손대지 않았다.

**→ 처방 그대로 커밋 `0dc687f55` 로 닫혔다.** `EXPECTED_OUT` 의 그 한 줄만 지웠고 `:68`
목록은 유지했다. 검증 — 그 판별식 포함 60건 통과 · 프론트 related 50파일 1030건 EXIT=0.

#### 과거 시점 기록으로 판단해 **남긴 것**

가른 기준은 「현재 사실을 주장하는가, 과거를 기록하는가」 하나다.

- `i18n/backlog-labels.ts` — 「뷰 전환 링크 5종이 여기 있었다 … J5 로 … 사라졌고 라벨도 함께
  나갔다」는 **그때의 사실**이라 그대로. 뒤이은 「리포트 3종은 탭이 아니므로 사이드바 트리의
  라벨이 정본이다」만 현재 사실 주장이라 갱신했다.
- `reports.cycle-time.test.tsx` — 「탭바가 정본 **9탭**을 소유하면서 그 nav 가 사라졌다」의
  숫자는 J5 시점 기록이라 **10으로 고치지 않았다**(고치면 오히려 거짓이 된다). 괄호 안
  「(리포트는 탭이 아니다)」만 그 시점 서술로 명시하고, UI 경로 문장은 현재 사실로 갱신했다.
- Task 6 이 남긴 4곳(`scrum-board.spec.ts:113` · `hooks/__tests__/use-tab-overflow.test.ts:15` ·
  `i18n/board-labels.ts:92` · `routes/__tests__/projects.board.test.tsx:1155`)은
  **뒤집지 않았다.** 앞 셋은 각각 「접힘 동작 설명 시점의 판」·「테스트 자신의 9칸 픽스처」·
  「J5 때 여기 있던 것이 나갔다」라 과거 기록이 맞다.
  ⚠️ 다만 넷째(`projects.board.test.tsx:1155` 「**지금은** `ShellLayout` 안의
  `ProjectViewChrome` 이 정본 **9탭**을 소유한다」)는 **현재형 주장 + stale 한 숫자**라
  경계선이다. 판단 주체가 Task 6 이고 Task 7 의 지시가 명시적으로 보존이라 손대지 않았다 —
  기록만 남긴다(한 낱말 `9탭` → `10탭` 이면 닫힌다).
  **→ controller 가 커밋 `0dc687f55` 로 `10탭` 으로 교정했다.** 판정 근거는 Task 7 이 세운
  그 기준 그대로다 — 「**지금은**」으로 시작하는 문장은 현재 사실 주장이고, 과거 기록 보존
  대상이 아니다. 경계선을 그은 것이 옳았고 선을 넘은 쪽이었다.
