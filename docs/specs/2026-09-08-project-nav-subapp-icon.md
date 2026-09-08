# 프로젝트 내비게이션 서브앱 재편 + 프로젝트 아이콘

> slug: `project-nav-subapp-icon` · 티어 T3 · BC `issue-tracking`
> plan: [`docs/plans/2026-09-08-project-nav-subapp-icon.md`](../plans/2026-09-08-project-nav-subapp-icon.md)
> 관련 FR — **FR-PJ-01 · FR-PJ-03 확장**(아이콘 · Maxi 결정으로 신규 FR 없음 · **145 불변**) ·
> FR-PJ-02 · FR-PJ-04 · FR-UX-06 · FR-UX-08 · FR-RP-02 · FR-RP-03 · FR-RP-04 · FR-TT-02

## 도메인 정리

| 항목 | 값 |
|---|---|
| BC | **`issue-tracking` 단일** |
| 근거 | `projects` 테이블이 `issue-tracking/V001__issues_initial.sql` 소유. `BC_KEYWORDS` 도 '프로젝트' → `issue-tracking` (정본 `scripts/workflow/classify-task.ts`) |
| 영향 엔티티 | `Project`(Aggregate Root · `com.bts.issue.project.domain.Project`) — 필드 `id·key·name·archivedAt` 에 **`iconKey` 1개 추가** |
| 새 용어 | **프로젝트 아이콘 (project icon)** — 아래 §용어 |
| 프론트 | `apps/web` — BC 아님. 사이드바·탭바·설정/리포트 내비 재편 |

### 새 용어 — Maxi 승인 대상

**프로젝트 아이콘 (project icon)**. 프로젝트를 목록·사이드바·탭에서 식별하는 시각 표식.
값은 **미리 정의된 아이콘 카탈로그의 키** 하나이며, 없으면 이름 첫 글자 아바타로 폴백한다.
Jira 의 `avatar`(JI-3)와 같은 자리지만 **업로드를 포함하지 않으므로**(편차 X-N3) 「아바타」가
아니라 「아이콘」으로 부른다 — `user_profiles.avatar_url`(identity-access · 업로드 있음)과
같은 단어를 쓰면 두 개념의 저장 방식 차이가 이름에서 지워진다.

### 관련 ADR — 충돌 여부

| ADR | 관련 내용 | 판정 |
|---|---|---|
| [`2026-07-17-fr-ux-06-jira-redesign.md`](../decisions/2026-07-17-fr-ux-06-jira-redesign.md) **D2** | 「좌측 전역 사이드바 하나 … **프로젝트 선택 시 사이드바가 프로젝트 메뉴로 확장**」 | **부합.** 이 스펙의 설정/리포트 서브앱은 그 D2 가 이미 선언한 「사이드바가 문맥에 따라 바뀐다」의 연장이다. 무효화하지 않는다 |
| 〃 §E2E 계약 | `role="navigation"` **`aria-label` 4종**(`메인 메뉴`·`관리 메뉴`·`프로젝트 뷰 전환`·workflow-scheme) · `<h1>` 단독 34건 · 사이드바 h1 금지 | **즉사 계약.** §제약 조건 C-1~C-3 으로 승계 |
| [`2026-07-28-fr-ux-07-active-project-context.md`](../decisions/2026-07-28-fr-ux-07-active-project-context.md) | 활성 프로젝트 문맥 | 무충돌 (읽기만) |
| [`2026-07-05-fr-pr-01-user-profile-placement.md`](../decisions/2026-07-05-fr-pr-01-user-profile-placement.md) **D4** | 사용자 아바타 = identity-access 자체 MinIO 배선 | **무충돌이되 선례로 인용한다** — 업로드를 하면 그 배선이 필요하다는 것이 X-N3(업로드 이연)의 근거다 |

**신규 ADR 1건 필요** — `docs/decisions/2026-09-08-project-icon-catalog-ownership.md`
(아이콘 카탈로그를 프론트가 소유하고 서버는 형식만 검증한다는 결정 · 아래 D-2).

## 사용자 시나리오 (Given-When-Then)

**S1. 사이드바가 짧아진다.**
Given 사이드바에서 프로젝트 `ATLAS` 를 펼쳤다
When 하위를 본다
Then 보드 목록 + `백로그` + `타임라인` 만 보인다 — `리포트`·`프로젝트 설정` 중첩그룹은 **없다**.

**S2. 설정에 들어가면 사이드바가 설정 메뉴가 된다.**
Given 사이드바 `ATLAS` 행의 `⋯` 를 눌러 `프로젝트 설정` 을 골랐다 (JS-1)
When `/projects/ATLAS/settings/details` 에 착지한다
Then ① 사이드바가 **프로젝트 트리 대신 설정 메뉴**로 바뀌고 그룹별 하위 항목이 보인다 (JS-2)
② 맨 위에 `← ATLAS` 로 프로젝트로 돌아가는 링크가 있다
③ **요약·타임라인·보드·백로그 탭바가 없다** (Maxi ③ · 편차 X-N1).

**S3. 설정에서 나오면 원래 사이드바로 돌아온다.**
Given 설정 사이드바를 보고 있다
When `← ATLAS` 를 누른다
Then `/projects/ATLAS` 로 이동하고 사이드바가 프로젝트 트리로, 탭바가 다시 보인다.

**S4. 리포트가 탭이 된다.**
Given 프로젝트 화면 어디에 있다
When 탭바의 `리포트` 를 누른다
Then `/projects/ATLAS/reports` 로 이동해 **리포트 4종 목록**이 카드로 보이고 (JR-2)
**탭바는 그대로 남는다** (JR-1 · A-3).

**S5. 리포트끼리는 리포트 안에서 오간다.**
Given `/projects/ATLAS/reports/velocity` 를 보고 있다
When 화면 안의 리포트 서브내비에서 `누적 흐름도(CFD)` 를 누른다
Then `/projects/ATLAS/reports/cfd` 로 이동하고 **탭바·사이드바 둘 다 그대로**다.

**S6. 프로젝트를 만들 때 아이콘을 고른다.**
Given `/projects/new` 생성 폼에 있다
When 아이콘 그리드에서 하나를 고르고 키·이름을 넣어 생성한다
Then 생성된 프로젝트가 **그 아이콘으로** 사이드바에 나타난다 (편차 X-N4).

**S7. 만든 뒤에도 「일반」에서 바꾼다.**
Given `/projects/ATLAS/settings/details` 에 있다
When 현재 아이콘 아래 `아이콘 변경` 을 눌러 그리드에서 다른 것을 고르고 저장한다 (JI-1·JI-2)
Then 사이드바·프로젝트 목록의 아이콘이 즉시 바뀐다.

**S8. 아이콘을 안 고르면 지금과 같다.**
Given 아이콘을 한 번도 고르지 않은 기존 프로젝트다
When 사이드바를 본다
Then **이름 첫 글자 아바타**가 나온다 — 현행 접힘 레일 동작(`ProjectTreeCollapsedRow`)과 같다.

## Jira 대조

**정본은 plan 의 같은 절**이다 — [`docs/plans/2026-09-08-project-nav-subapp-icon.md#jira-대조`](../plans/2026-09-08-project-nav-subapp-icon.md).
사본을 두지 않기 위해 여기에는 이 스펙이 직접 무는 행만 옮긴다. 전부 **Cloud · 조회 2026-09-08**.

| # | 항목 | 원문 인용 | 출처 |
|---|---|---|---|
| JR-1 | 리포트는 스페이스 **내비게이션(수평 탭)** 에서 연다 | "Select **Reports** from the space navigation." | [generate a report](https://support.atlassian.com/jira-software-cloud/docs/generate-a-report/) |
| JR-2 | 리포트 착지는 개별 차트가 아니라 **목록/인사이트 화면** | "space insights dashboard" · "**More reports** button to open the classic Jira report catalog" | 〃 |
| JR-3 | 신 내비 **사이드바에 Reports 가 없다** | 사이드바 항목 = For you · Recent · Starred · Spaces · Dashboards · Assets · Goals · Apps | [what is the new navigation in Jira](https://support.atlassian.com/jira-software-cloud/docs/what-is-the-new-navigation-in-jira/) |
| JS-1 | 설정 진입 = 사이드바 스페이스 이름 옆 `⋯` | "Next to the name of your space in the sidebar, select **More actions** (•••), then **Space settings**." | [navigate to your work](https://support.atlassian.com/jira-software-cloud/docs/navigate-to-your-work/) |
| JS-2 | 설정 안에서는 **설정 자체 사이드바**로 페이지를 고른다 | "…selecting **Space settings** and choosing **Permissions from the sidebar**." | [change which permission scheme a space uses](https://support.atlassian.com/jira-cloud-administration/docs/change-which-permission-scheme-a-space-uses/) |
| JI-1 | 아이콘은 별도 메뉴가 아니라 **Details 페이지 안** | "Select **Details**." / "Under your space's current avatar, select **Change icon**." | [edit a space's details](https://support.atlassian.com/jira-work-management/docs/edit-a-projects-details/) |
| JI-2 | **기본 아이콘 목록**에서 고르거나 업로드한다 | "Choose from a **default icon** or upload your own, then use **Select** to save your choice." | 〃 |
| JI-3 | Details 는 아바타를 다른 기본 필드와 **한 화면에** 둔다 | Name · Space key · URL · Description · Category · **Avatar** · Space owner · … | 〃 |

**의도적 편차** — X-N1(설정 탭바 숨김은 **문서 근거 없음**, Maxi 제품 결정) · X-N2(컴포넌트·버전
경로 미이동) · X-N3(업로드 이연) · X-N4(생성 폼 아이콘은 Jira 대응 없음 — 설정과 **같은 그리드
컴포넌트 재사용**). 근거 전문은 plan.

**추가 편차 X-N5 (신설) — 설정 메뉴의 그룹 편성은 Jira 근거가 없다.**
`support.atlassian.com` 에서 company-managed 스페이스 설정 사이드바의 **항목 전량과 그 그룹
이름**을 나열한 문서를 찾지 못했다(JS-2 는 「사이드바에서 고른다」까지만 말한다).
그래서 그룹 편성은 **대응 없음 — ADS `Side navigation` 의 `NestingItem` 패턴 준용**으로 하고,
묶는 축은 **BTS 의 기존 라우트 의미**로 정한다(§FR-N2 표). 「Jira 가 이렇게 묶는다」고 주장하지 않는다.

## 설계 결정

### D-1. 「지금 설정 서브앱인가」의 판정을 **한 곳**에서만 한다

순수 함수 `resolveProjectShellMode(pathname, projectKey)` 를 신설하고
**사이드바와 탭바가 같은 함수를 부른다.** 반환은 `'tree' | 'settings'` 2값.

🛑 두 컴포넌트가 각자 `pathname.includes('/settings/')` 를 쓰면 **갈린다** — 한쪽만 고쳐졌을 때
탭바는 사라졌는데 사이드바는 트리인(또는 그 반대) 상태가 조용히 생긴다. 이 저장소가 이름 붙인
`two-lists-never-check-each-other` 의 판본이다.

**판정은 목록에서 유도한다.** `PROJECT_SETTINGS_NAV`(§FR-N2 정본)에 실린 경로에 있을 때만
`'settings'` 다. 그래서 **컴포넌트·버전은 목록에 없으므로 자동으로 `'tree'`** 가 된다 —
X-N2 를 위해 예외 분기를 따로 쓰지 않는다. 예외 분기는 목록과 갈리는 두 번째 목록이 된다.

### D-2. 아이콘 카탈로그는 **프론트가 소유**하고 서버는 형식만 검증한다

| 축 | 결정 |
|---|---|
| 저장 | `projects.icon_key VARCHAR(32) NULL` — NULL = 미지정(첫 글자 아바타 폴백) |
| 서버 검증 | **형식만** — `^[a-z][a-z0-9-]{1,31}$` (DB CHECK + Bean Validation `@Pattern`) |
| 카탈로그 | `apps/web/src/lib/project-icon-catalog.ts` **단일 정본** (lucide 아이콘 + 배경 토큰) |
| 미지의 키 | **폴백 렌더**. 500 도 빈칸도 아니다 |

**왜 서버가 카탈로그를 모르는가.** 아이콘 집합은 순수 표현 관심사다. 서버에 enum 을 두면
같은 목록이 **DB CHECK · Kotlin enum · TS 카탈로그 3곳**에 생기고, 아이콘 하나를 추가할 때마다
마이그레이션이 필요해진다. 그 대가로 「카탈로그에 없는 키가 저장될 수 있다」를 받되,
**폴백이 그것을 무해하게 만든다**(§엣지 E-3). 이 결정은 ADR 로 남긴다.

### D-3. 리포트 탭은 정본 목록에 **행 하나를 더하는 것**이 전부다

`PROJECT_VIEW_TABS` 9 → 10. 새 행 `{ key:'reports', to:'/projects/$projectKey/reports',
label:'리포트', usesProjectParam:true, exact:false }`.
`exact:false` 라 `/reports/velocity` 에서도 리포트 탭이 활성이다 — 이것이 A-3(탭바 유지)의 실체다.

🛑 **`summary` 의 `exact:true` 를 건드리지 않는다.** `/projects/ATLAS` 는 모든 하위 경로의
접두사라 그 플래그가 빠지면 전 화면에서 요약이 함께 강조된다(`project-view-tabs.ts` KDoc).

### D-4. 아이콘 갱신은 **기존 PATCH 를 확장**한다 — 새 엔드포인트를 만들지 않는다

`PATCH /api/v1/projects/{projectIdOrKey}` 가 이미 있고 `UpdateProjectRequest.name` 을 받는다.
`iconKey` 를 **선택 필드**로 더한다. 새 엔드포인트를 만들면 권한 가드(`PROJECT_ADMIN`)와
404/403 순서 계약을 한 벌 더 써야 하고, 그 둘이 갈리면 `permission-assert-before-existence-makes-403-lie`
양식이 재현된다.

**부분 갱신 의미론** — 필드 부재(`undefined`)와 명시적 `null` 을 가른다.
부재 = 안 바꿈 · `null` = 아이콘 해제(첫 글자 아바타로). 이 구분이 없으면 이름만 바꾸는 요청이
아이콘을 지운다(§엣지 E-1).

## 기능 요구사항 (FR)

### 기존 FR 의 UI 재편 (신규 FR 아님)

| # | 요구 | 무는 FR |
|---|---|---|
| FR-N1 | `ProjectTree` 의 프로젝트 하위에서 `리포트`·`프로젝트 설정` 중첩그룹을 제거한다. 남는 것은 보드 목록 + `백로그` + `타임라인` | FR-UX-06 · FR-UX-08 |
| FR-N2 | 설정 서브앱 사이드바를 신설한다. 정본 목록 `PROJECT_SETTINGS_NAV` **10항목 4그룹** (아래 표) + 최상단 `← {프로젝트명}` 복귀 링크 | FR-UX-06 · FR-PJ-02 |
| FR-N3 | `resolveProjectShellMode` 가 `'settings'` 인 경로에서 **프로젝트 뷰 탭바를 렌더하지 않는다** | FR-UX-06 |
| FR-N4 | `PROJECT_VIEW_TABS` 에 `리포트` 탭을 더한다(9→10). 목적지는 신규 라우트 `/projects/$projectKey/reports` | FR-RP-02~04 · FR-TT-02 |
| FR-N5 | 리포트 착지 화면이 리포트 4종을 카드로 나열한다. 각 리포트 화면 안에는 4종을 오가는 서브내비가 있다 | 〃 |
| FR-N6 | `컴포넌트`·`버전`은 정본 탭으로 유지하고 `PROJECT_SETTINGS_NAV` 에서 **제외**한다 | FR-IS-* |

**FR-N2 정본 목록** (X-N5 — 그룹 편성은 BTS 자체 · Jira 근거 없음)

| 그룹 | 항목 | 경로 |
|---|---|---|
| 일반 | 일반 | `/projects/$projectKey/settings/details` |
| 일반 | 프로젝트 리드 | `/projects/$projectKey/settings/project-lead` |
| 이슈 | 워크플로우 스킴 | `/projects/$projectKey/settings/workflow-scheme` |
| 이슈 | 이슈 템플릿 | `/projects/$projectKey/settings/issue-templates` |
| 이슈 | 커스텀 필드 | `/projects/$projectKey/settings/custom-fields` |
| 이슈 | 필드 권한 | `/projects/$projectKey/settings/field-permissions` |
| 액세스 | 멤버 | `/projects/$projectKey/settings/members` |
| 연동 | 자동화 | `/projects/$projectKey/settings/automation` |
| 연동 | Slack 채널 | `/projects/$projectKey/settings/slack-channels` |
| 연동 | 가져오기 | `/projects/$projectKey/settings/import` |

기존 `SETTINGS_LINKS` 12 − `컴포넌트`·`버전` 2 = **10**. 링크가 사라지지 않았음이 §완료기준 C-2.

### 프로젝트 아이콘 — **기존 FR 확장** (Maxi 결정 2026-09-08 · FR 수 145 불변)

신규 FR 을 세우지 않는다. 아이콘은 새 기능이 아니라 **기존 설정의 필드 추가**로 읽는다.

| FR | 현재 문구 | 이 PR 이후 |
|---|---|---|
| FR-PJ-01 | 프로젝트 생성 (키 검증, 생성자 자동 PROJECT_ADMIN 멤버십) | 프로젝트 생성 (키 검증, **아이콘 선택**, 생성자 자동 PROJECT_ADMIN 멤버십) |
| FR-PJ-03 | 프로젝트 설정 변경 (`name`) | 프로젝트 설정 변경 (`name`, **`iconKey`**) |

🛑 **문구를 고치는 곳이 한 곳이 아니다.** `docs/sdd/02-requirements.md` 의 FR 표가 정본이고
`docs/plan/product/issue-tracking.md` 가 같은 문장을 다시 들고 있다. **카운트는 안 바뀌므로
`verify-master-plan.sh` 가 이 drift 를 못 잡는다** — 룰 E 는 개수만 본다. 문구 동기화는
`fr-sync-checklist` 2·3번 항목을 **사람이** 이행한다(§완료기준 A-13′).

프로젝트는 **미리 정의된 아이콘 카탈로그**에서 고른 아이콘을 가질 수 있다.
아이콘은 프로젝트 생성 시와 「일반」 설정에서 지정·변경·해제할 수 있고,
사이드바 프로젝트 트리·프로젝트 목록에 표시된다. 미지정이면 이름 첫 글자 아바타로 폴백한다.

| # | 요구 | 무는 FR |
|---|---|---|
| FR-IC-1 | `projects` 가 `icon_key` 를 갖는다. NULL 허용. 형식 CHECK `^[a-z][a-z0-9-]{1,31}$` | FR-PJ-01·03 |
| FR-IC-2 | `ProjectResponse` 가 `iconKey: String?` 를 노출한다 | FR-PJ-02 |
| FR-IC-3 | `POST /api/v1/projects` 가 선택 필드 `iconKey` 를 받는다 | FR-PJ-01 |
| FR-IC-4 | `PATCH /api/v1/projects/{idOrKey}` 가 선택 필드 `iconKey` 를 받는다. 부재=유지 · null=해제 | FR-PJ-03 |
| FR-IC-5 | 생성 폼(`/projects/new`)에 아이콘 그리드가 있다 | FR-PJ-01 |
| FR-IC-6 | 「일반」 설정(`/settings/details`)에 현재 아이콘 + `아이콘 변경` 그리드가 있다 | FR-PJ-03 |
| FR-IC-7 | 사이드바 프로젝트 트리(펼침·접힘 레일 둘 다)와 프로젝트 목록이 아이콘을 그린다 | FR-PJ-02 |
| FR-IC-8 | 아이콘 변경 권한은 이름 변경과 **같다**(`PROJECT_ADMIN` — 컴포넌트 UPDATE 재사용) | FR-PJ-03 |

`FR-IC-*` 는 **이 스펙 안의 지역 번호**다 — FR ID 가 아니므로 `fr-index` 에 등재하지 않는다.
plan 의 task 가 이 번호로 요구를 문다.

## 비기능 요구사항 (NFR)

| # | 요구 | 판정 |
|---|---|---|
| NFR-1 | 사이드바 모드 전환에 **추가 네트워크 요청이 없다** — 판정 입력이 `pathname` 뿐이다 | `resolveProjectShellMode` 순수 함수 단위 테스트 |
| NFR-2 | 아이콘 표시가 **프로젝트 목록 응답 1건**만으로 된다 — 아이콘별 추가 요청 0 | `useProjects()` 응답에 `iconKey` 포함 |
| NFR-3 | 아이콘은 **SVG 컴포넌트**(lucide)라 네트워크 자산이 아니다 — 이미지 요청 0 | 정적 import |
| NFR-4 | 마이그레이션이 **기존 행을 잠그지 않는다** — `ADD COLUMN ... NULL` 은 기본값이 없어 rewrite 가 없다 | `DATA.md` 마이그레이션 검증 |

## API 인터페이스 (REST)

신규 엔드포인트 **0건**. 기존 3개를 확장한다.

```
GET  /api/v1/projects            → data[].iconKey: string|null   (신규 필드)
GET  /api/v1/projects/{idOrKey}  → data.iconKey:  string|null   (신규 필드)
POST /api/v1/projects            ← { key, name, iconKey?: string|null }
PATCH /api/v1/projects/{idOrKey} ← { name?: string, iconKey?: string|null }
```

- `PATCH` 는 **204 No Content** 를 유지한다(기존 `ProjectSettingsController` 계약).
- `iconKey` 검증 실패 → **400**. 권한 없음 → **403**. 프로젝트 없음 → **404**.
  🛑 **존재 확인이 권한 판정보다 먼저**다 — 반대로 하면 403/404 의미가 뒤집힌다
  (`permission-assert-before-existence-makes-403-lie`). 기존 `ProjectSettingsService.changeName`
  의 「존재 → 권한 → 영속」 순서를 그대로 탄다.

## 데이터 모델 변경

**마이그레이션 1건** — `backend/modules/issue-tracking/src/main/resources/db/migration/issue-tracking/V040__projects_icon_key.sql`
(현재 최신 = `V039__description_html.sql`).

```sql
ALTER TABLE projects ADD COLUMN icon_key VARCHAR(32) NULL;
ALTER TABLE projects ADD CONSTRAINT projects_icon_key_check
  CHECK (icon_key IS NULL OR icon_key ~ '^[a-z][a-z0-9-]{1,31}$');
```

🛑 **`init_codegen.sql` 미러를 같은 커밋에서 고친다.** jOOQ 코드 생성은 Flyway 를 읽지 않고
`issue-tracking/src/main/resources/db/codegen/init_codegen.sql` 를 introspection 한다 —
미러가 낡으면 `PROJECTS.ICON_KEY` 상수가 **생성되지 않아 컴파일이 깨진다.**
짝 판별식 `scripts/workflow/codegen-mirror-parity.test.ts` 가 양방향 차집합으로 강제한다.

## 엣지 케이스

| # | 상황 | 처리 |
|---|---|---|
| E-1 | 이름만 바꾸는 PATCH 가 아이콘을 지운다 | `iconKey` **부재 = 유지**. 명시적 `null` 만 해제. Kotlin 은 `JsonNullable` 계열이 없으므로 **부재/null 구분이 되는 표현**을 쓴다(구현 수단은 plan) |
| E-2 | 생성 폼에서 아이콘을 안 고른다 | `iconKey` 를 안 싣는다. 서버는 NULL 저장. S8 폴백 |
| E-3 | 카탈로그에 없는 `icon_key` 가 DB 에 있다 (수동 INSERT · 카탈로그 축소) | **첫 글자 아바타로 폴백**. throw 도 빈칸도 아니다. 짝 테스트 필수 |
| E-4 | 형식은 맞지만 33자 이상 | DB `VARCHAR(32)` 와 Bean Validation `@Size(max=32)` 가 **둘 다** 막는다. 한쪽만 두면 DB 예외가 500 으로 샌다 |
| E-5 | 설정 사이드바에서 프로젝트가 아카이브된 상태 | 설정 진입 자체는 막지 않는다(현행 `/settings/details` 와 동일). 이 스펙은 아카이브 규칙을 바꾸지 않는다 |
| E-6 | `/projects/$key/board/settings`(보드 설정) 경로 | `PROJECT_SETTINGS_NAV` 에 없다 → `'tree'` → **탭바 유지**. 의도된 결과다(보드 설정은 프로젝트 설정이 아니다) |
| E-7 | 설정 경로인데 `projectKey` 가 빈 문자열 | 기존 `ProjectViewChrome` 의 `present` 판정(`!== '' && !== undefined`)을 그대로 탄다 → 크롬 없음. `/projects//versions` 401 사고(PR #471)의 재발 방지선을 건드리지 않는다 |
| E-8 | 접힘 레일(64px)에서 아이콘 | 아이콘이 첫 글자 자리를 **대체**한다. 라벨은 `sr-only` 로 DOM 에 남아 `getByRole('link',{name})` 계약(FR5)이 유지된다 |
| E-9 | 리포트 탭 추가로 탭이 10개 → 오버플로 | `useTabOverflow` 가 기존대로 처리. **활성 탭은 핀 고정**되므로 리포트에 있으면 리포트 탭이 보인다 |
| E-10 | 모바일 드로어에서 설정 사이드바 | 같은 `aside` 안이라 드로어 동작 불변. `aria-label` 만 모드에 따라 바뀐다 → C-1 |

## 제약 조건

| # | 제약 | 근거 |
|---|---|---|
| C-1 | **`aria-label` 계약 문자열을 바꾸지 않는다** — `메인 메뉴`·`프로젝트`·`프로젝트 뷰 전환`. 설정 사이드바 nav 는 **새 이름**을 쓰되 `프로젝트`(`navLabels.projectNav`)를 substring 으로 품지 않아야 한다 | ADR `fr-ux-06-jira-redesign` §E2E 계약 · `nav-labels.test.ts` FR15 |
| C-2 | **설정 10링크 + 리포트 4링크의 도달성이 0이 되면 안 된다.** 사이드바에서 뺀 만큼 다른 진입로가 생겨야 한다 | `ProjectTree.tsx:460` 주석(2026-09-04 전수 grep — 대체 진입로 0건) |
| C-3 | **사이드바에 `<h1>` 을 두지 않는다.** 설정 사이드바의 프로젝트명도 `h1` 이 아니다 | e2e `<h1>` 단독 34건 |
| C-4 | **한 PR = 한 BC.** `issue-tracking` 만 건드린다. 다른 BC 직접 import 금지 | `CLAUDE.md` §핵심 패턴 |
| C-5 | **Radix Tabs 금지.** 리포트 서브내비도 `nav`+`Link` 다 | `ProjectNavTabs` KDoc · e2e 5 + 유닛 5 |
| C-6 | **FR 수는 145 그대로.** 대신 FR-PJ-01·03 의 **문구**를 같은 PR 에서 전수 동기화한다 — `docs/sdd/02-requirements.md`(정본) · `docs/plan/product/issue-tracking.md`(사본) · `glossary.md` 신규 용어. 🛑 카운트가 안 바뀌므로 `verify-master-plan.sh` 룰 E 가 **이 drift 를 못 잡는다** | `docs/rules/fr-sync-checklist.md` 2·3번 |
| C-7 | 아이콘 **업로드를 구현하지 않는다** | 편차 X-N3 |

## 측정 가능한 완료 기준

| # | 기준 | 검증 |
|---|---|---|
| A-1 | `ProjectTree` 렌더 결과에 `리포트`·`프로젝트 설정` 디스클로저 버튼이 **0개** | 유닛 (RTL) |
| A-2 | **차집합 판별식** — `PROJECT_SETTINGS_NAV` 경로 집합 ∪ `PROJECT_VIEW_TABS` 경로 집합 ⊇ 「기존 `SETTINGS_LINKS` 12 + `REPORT_LINKS` 4」. 즉 **도달성을 잃은 화면이 0개**. 비-공허 짝 — 목록에서 1건을 빼면 red | 판별식 (`scripts/workflow/`) |
| A-3 | `resolveProjectShellMode` 가 `PROJECT_SETTINGS_NAV` **전 경로**에서 `'settings'`, `컴포넌트`·`버전`·보드설정·요약·보드·백로그·타임라인·리포트 4종에서 `'tree'` | 순수 함수 단위 |
| A-4 | **사이드바와 탭바가 같은 함수를 쓴다** — 두 컴포넌트 소스에 각자의 경로 판정식이 없다. 뮤테이션: 함수 본문을 뒤집으면 두 컴포넌트 테스트가 **함께** red | 판별식 + 뮤테이션 짝 |
| A-5 | 설정 라우트 렌더 시 `role="navigation"` `name="프로젝트 뷰 전환"` 이 **부재**, 비설정 라우트에서는 **존재** | 유닛 (RTL) |
| A-6 | `PROJECT_VIEW_TABS.length === 10` 이고 실 라우트 전수에서 **활성 탭이 2개 이상이 되지 않는다** | 기존 `project-view-tabs.test.ts` 확장 |
| A-7 | 리포트 4화면 각각에서 나머지 3개로 가는 링크가 있다 | 유닛 |
| A-8 | `V040` 적용 후 `projects.icon_key` 가 존재하고, 잘못된 형식 INSERT 가 **CHECK 위반**으로 거부된다 | Testcontainers 통합 |
| A-9 | `init_codegen.sql` ↔ 마이그레이션 **양방향 차집합 0** | `codegen-mirror-parity.test.ts` |
| A-10 | `PATCH {name}` 만 보내면 `icon_key` 가 **변하지 않는다**. `PATCH {iconKey:null}` 은 NULL 로 만든다 | 통합 (E-1) |
| A-11 | 카탈로그에 없는 `iconKey` 로 렌더하면 **첫 글자 아바타**가 나오고 throw 하지 않는다 | 유닛 (E-3) |
| A-12 | 아이콘 변경이 **비-PROJECT_ADMIN 에게 403**, 없는 프로젝트에 **404** (권한보다 존재가 먼저) | 통합 |
| A-13 | `verify-master-plan.sh` EXIT 0 · **FR 145/145 불변** | 스크립트 |
| A-13′ | **FR-PJ-01·03 문구가 두 파일에서 같다** — `docs/sdd/02-requirements.md` 와 `docs/plan/product/issue-tracking.md` 의 해당 행에 `icon` 이 **둘 다** 있다. 카운트 룰이 못 잡는 자리라 **문자열 판별식**을 신설한다. 비-공허 짝 — 한쪽에서 `icon` 을 지우면 red | 판별식 (`scripts/workflow/`) |
| A-13″ | `glossary.md` 에 「프로젝트 아이콘」 항목이 있고 **「아바타」와 구분**을 적는다 (Maxi 승인 2026-09-08) | 수동 + doc-index |
| A-14 | E2E — 설정 진입 시 탭바 부재 · 리포트 탭으로 리포트 착지 · 아이콘 지정 후 사이드바 반영 | Playwright |
| A-15 | 눈확인 — **라이트/다크 둘 다**. 설정 사이드바 · 리포트 착지 · 아이콘 그리드 | 수동 |

## Sanity Check

**❓ 발견 1 — `iconKey` 부재/null 구분을 Kotlin 이 그냥은 못 한다.**
`data class`의 nullable 필드는 「JSON 에 없음」과 「`null` 로 옴」이 **둘 다 `null`** 이다.
그래서 E-1(이름만 바꿔도 아이콘이 지워짐)이 **기본 구현에서 실제로 발생한다.**
스펙에 요구(FR-PJ-05-4)만 적고 수단을 안 적으면 구현이 조용히 틀린다 → §D-4·E-1 에
「부재/null 구분이 되는 표현을 쓴다」를 명시하고 A-10 을 완료기준으로 못박았다.

**❓ 발견 2 — 「사이드바에서 뺐다」와 「다른 데서 닿는다」가 서로를 검사하지 않는다.**
FR-N1(제거)과 FR-N2·N4(대체 진입로)를 각자 테스트하면, 나중에 누가 목록에서 한 줄을 지워도
양쪽 테스트가 **각자 초록**이다. 지배 결함 양식 `two-lists-never-check-each-other` 그대로다.
→ **A-2 차집합 판별식 + 비-공허 짝**을 완료기준에 신설했다. 「기존 16경로」를 **하드코딩 목록이
아니라 이 PR 의 diff 이전 상수에서 뽑아** 고정 픽스처로 박는다(그 상수가 사라지므로).

**❓ 발견 3 — 아이콘 카탈로그가 세 번째 목록이 될 뻔했다.**
초안은 서버 enum + DB CHECK + TS 카탈로그였다. D-2 로 **프론트 단일 소유**로 접고 서버는
형식만 본다. 대가(미지의 키 저장 가능)는 E-3 폴백 + A-11 로 무해화한다.

**❓ 발견 4 — 리포트 탭 신설이 오버플로 계산을 건드린다.**
탭 9→10 이면 좁은 폭에서 접히는 탭이 하나 늘어난다. `useTabOverflow` 는 활성 탭을 핀 고정하므로
기능은 안 깨지지만, **기존 오버플로 e2e 가 「9탭 전량 복귀」를 단언하고 있으면 red** 다.
→ A-6 에 개수 단언을 명시하고, `e2e/project-tabs-overflow.spec.ts` 의 개수 의존을 plan 이
실측하도록 남긴다.

**❓ 발견 5 — 「컴포넌트·버전을 설정 메뉴에서 뺀다」가 도달성을 줄인다고 오해될 수 있다.**
실측 — 두 화면은 **정본 탭에 이미 있다**(`PROJECT_VIEW_TABS` 의 `components`·`versions`).
그래서 설정 목록에서 빼도 도달성은 그대로다. A-2 차집합이 이것을 기계적으로 증명한다.

**✅ Maxi 결정 2건 (2026-09-08 · `AskUserQuestion`)**

| 질문 | 결정 |
|---|---|
| 아이콘의 FR 축 처리 | **기존 FR 확장 · 145 유지** — FR-PJ-01·03 문구만 고친다. FR-PJ-05 신설 기각 |
| 새 용어 `glossary.md` 등재 | **등재** — 「프로젝트 아이콘(카탈로그 키·업로드 없음)」 vs 「사용자 아바타(MinIO 업로드)」 구분 명시 |

★그 첫 결정이 **새 위험을 만들었다.** 카운트가 안 바뀌므로 `verify-master-plan.sh` 룰 E 는
문구 drift 를 못 본다 — FR 수를 바꾸는 쪽이 오히려 기계 검증을 받았을 것이다.
그래서 A-13′ **문자열 판별식**을 완료기준에 신설했다. 결정을 그대로 받되 그 대가를 덮는다.

**Sanity Check ✅ 통과** — 발견 5건 전부 스펙 본문에 반영(D-2·D-4·E-1·E-3·A-2·A-6·A-11),
Maxi 결정 2건 반영, 그 결정이 파생시킨 검증 공백 1건(A-13′)까지 메웠다.
