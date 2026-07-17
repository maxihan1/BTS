# FR-UX-06 — UI/UX 전면 개편 (Jira Cloud 방식)

> slug: fr-ux-06-jira-redesign
> type: ui
> agent: frontend-engineer (주) + designer (스펙) + security-engineer (프로젝트 목록 API — #277로 이관) + qa-engineer (E2E)
> 생성: 2026-07-17
> 주 BC: personalization (논리) / 물리 `apps/web` + identity-access — ADR D5 "논리 ≠ 물리"
> ADR: [docs/decisions/2026-07-17-fr-ux-06-jira-redesign.md](../../decisions/2026-07-17-fr-ux-06-jira-redesign.md)
> 디자인 스펙: [docs/design/fr-ux-06-jira-redesign.md](../../design/fr-ux-06-jira-redesign.md)
> 시안(동작 프로토타입 8화면): https://claude.ai/code/artifact/de59cb1e-4fae-4755-b18b-fe888ee96023
> 동시 PR: **#277** (`backend/project-management-crud`) — 충돌 처리는 §충돌 참조

## Brief

**BTS는 Jira를 대체하려는 이슈 트래커인데 UI가 Jira를 닮은 구석이 없다.** 123 FR 중 대부분이 구현됐지만 그 기능들이 화면 위에서 서로 연결돼 있지 않다.

Maxi 원문 — *"전체 ui/ux와 디자인을 지라 클라우드 방식으로 개편하려고 해"*.

**핵심은 색이 아니라 IA(정보 구조)다.**

| 사실 | 실측 (2026-07-17) |
|---|---|
| 앱 셸 | `routes/__root.tsx` **1,776 bytes** = `<Header/> + <main><Outlet/></main>`. **사이드바 컴포넌트가 파일로 없음** |
| 네비게이션 | `Header.tsx` 메인 nav 링크 **2개**(대시보드·캘린더). 52 라우트 중 대부분 도달 불가 |
| 그 결과 | `issues.index.tsx:38` · `search.tsx`에 `const DEFAULT_PROJECT_KEY = 'ATLAS'` 하드코딩 |
| 브랜드 색 | **없음.** `--primary: oklch(0.205 0 0)`(거의 검정), 팔레트 전체 chroma 0. `--chart-1~5`도 무채색 + **참조 0건** |
| 프리미티브 | `components/ui/` **7종뿐**. `dialog` 부재 → **35~37개 파일이 Radix Dialog를 각자 import**, Overlay 클래스 문자열 복붙 |
| 중첩 레이아웃 | **0개.** 52 라우트 전부 `getParentRoute: () => rootRoute` |
| 규모 | 52 라우트 / 프로덕션 .tsx 239 / 약 52,100 LOC / Playwright spec **122** |

### 확정 결정 (Maxi, 2026-07-17)

| # | 결정 | 기각된 대안 |
|---|---|---|
| 1 | **Jira Cloud 2025 신형 통합 사이드바** | 구형(탑바 드롭다운 + 프로젝트 사이드바) — Atlassian이 이미 이탈 |
| 2 | **ADS v2 팔레트 그대로 이식** (`#0C66E4`) | BTS 고유 색조 / 무채색 유지 |
| 3 | **FR-UX-06 신설** | FR 없이 ADR로만 (담당자 권고였으나 Maxi 기각 — 진척 가시성 우선) |
| 4 | **개편 전체를 FR-UX-06 범위로** | 전역 네비만 FR (담당자 권고였으나 Maxi 기각) |
| 5 | **댓글은 별도 FR로 분리** | 개편에 포함 |
| 6 | **Phase 0 먼저 머지 · Phase 4는 #277에 양보** | #277 완주 대기 / 병렬 강행 |

**#4의 대가와 완화.** D1~D7 7단계 모델에 22 PR을 밀어 넣으면 D6 하나에 15 PR이 몰려 진척 표현이 무너진다. → **D6를 PR 체인으로 쪼개 표기**해 완화한다 (FR-AT-07의 PR-A/B/C 선례 원용).

## 충돌 — 동시 PR #277

**DRAFT PR #277 「프로젝트 관리 — 생성/목록/설정/아카이브 + FR 신설」** (`backend/project-management-crud`, 6 PR 체인 중 **PR-1 진행 중**).

| 충돌 | 내용 | 처리 |
|---|---|---|
| **FR 카운트** | #277이 `FR-PJ-01~04`(issue-tracking) + `FR-PM-10`(identity-access) **5개 선점 → 123→128** | FR-UX-06은 **128→129**. **본 커밋에서 FR 동기화 안 함** |
| **프로젝트 목록 API** | #277이 `GET`/`POST /api/v1/projects`를 만듦 | 본 계획의 PR-0' **삭제** |
| **Phase 4** | #277 **PR-5 = "D6 프론트 UI — 생성·목록·설정·아카이브 화면"** | **#277에 양보.** PR14·PR15 삭제 |
| **마이그레이션** | #277이 identity **V036** 선점 | 회피 |

★ **`fr-index.md`에 FR-PJ가 아직 없다 — `docs/specs/2026-07-17-project-management-crud.md`에만 선점돼 있다.** fr-index만 grep했다면 못 봤다.

**순서.** Phase 0(문서·ADS 토큰·프리미티브)은 #277과 독립이고 소비자를 안 건드려 위험 ≈ 0 → **먼저 머지해 #277 PR-5가 새 디자인 시스템 위에 짓게 한다.** 반대면 화면 4개를 두 번 만든다. 머지 후 **디자인 스펙을 #277에 공유**한다.

**설정 nav.** `/settings`·`/admin` 인덱스는 #277 범위 밖이라 양보 대상이 아니다 → 사이드바(PR11)/PageLayout(PR13)에 흡수, 별도 PR 없음.

## 본 커밋 범위 (문서 전용)

| 하는 것 | 안 하는 것 |
|---|---|
| plan 3종 (본 디렉토리) · ADR · 디자인 스펙 | **FR-UX-06 전수 동기화 8종** → #277 머지 후 별도 커밋 |
| — | `fr-index`·SDD·`product/personalization`·README·CLAUDE.md 카운트 |
| — | `build-dashboard.mjs` 재생성 (카운트 불변) |

**근거.** main이 123인데 129를 주장하면 `verify-master-plan.sh`가 즉시 fail한다. #277 범위가 바뀌면 숫자도 틀린다. `docs/plans/`·`docs/decisions/`·`docs/design/`은 verify 스캔 대상이 아니라 **본 커밋만으론 안 깨진다**.

## 도메인 정리

### 상태 모델 — 매핑에 발명이 필요 없다

`backend/modules/project-workflow/src/main/resources/workflows/software-default.yaml`이 **이미 Jira의 status category 모델과 동일**하다.

| 상태 | category | ADS 로젠지 |
|---|---|---|
| Open | `TODO` | neutral |
| In Progress / In Review | `IN_PROGRESS` | blue |
| Done / Closed | `DONE` | green |

이슈 타입도 `V003__issue_types.sql` 시드가 epic/story/task/subtask로 Jira와 1:1. 우선순위는 1~5 (`가장 높음`~`가장 낮음`).

### 팔레트 검증 상태 — 정본 아님

`atlassian.design`이 JS 렌더링이라 **전수 검증 실패**. 4개 지점만 독립 확인했고 전부 일치했다.

| 값 | 역할 | 출처 |
|---|---|---|
| `#E9F2FF` | Blue100 | Atlassian 개발자 문서 (`color.background.information` 라이트) |
| `#082145` | Blue1000 | 동 문서 다크 |
| `#172B4D` | Neutral 텍스트 | 검색 교차 확인 |
| `#0052CC` | **구세대 v1 B400** | 검색 — **쓰지 않는 근거** |

→ **PR3에서 `atlassian.design/components/tokens/all-tokens` 전수 대조를 D단계 작업으로 명시한다.** 시안의 색은 제안이지 정본이 아니다.

## Plan — 20 PR (PR-0'·PR14·PR15 삭제 후)

```
Phase0 기반   PR1(문서·본커밋) ─ PR2(프리미티브15종) ─ PR3(ADS토큰) ─ PR9(1줄prep)   ← 4개 독립·병렬
Phase1 정착                                          └─ PR4(하드코딩색 141건)
Phase2 Dialog        PR5 → (PR6 ∥ PR7) → PR8(+ESLint 락)
Phase3 Shell         PR9 → PR10(_shell) → PR11(사이드바) → PR12 → PR13
Phase4               ── #277 PR-5/PR-6에 양보 ──
Phase5 화면          PR17 … PR22
```
**임계 경로.** PR2 → PR10 → PR11 → PR13 → PR17

| PR | 한 문장 | 규모 | 위험 |
|---|---|---|---|
| **PR1** | 본 커밋 — plan 3종 + ADR + 디자인 스펙 | docs only | 없음 |
| **PR2** | `ui/*` 프리미티브 15종 추가 (**소비자 0**) | +15파일 ≈900 | **없음** |
| **PR3** | `index.css` 팔레트를 ADS로 교체 + 시맨틱 토큰 4쌍 신설 | **1파일** ≈±120 | 🔴 시각 |
| **PR9** | `settings.account-links.tsx:411` route-id 결합 제거 (prep) | **1파일 1줄** | 없음 |
| **PR4** | 하드코딩 색 141건 → 시맨틱 토큰 | 29파일 | 낮음 |
| **PR5** | `issue-tracking` BC Dialog 흡수 (**API 확정 PR**) | ≈10파일 −250 | 낮음 |
| **PR6/7** | `agile-planning` / `identity-access`+`notification` 흡수 | ≈8~9파일 | 낮음 |
| **PR8** | 잔여 BC + **`radix-ui` Dialog 직접 import 금지 ESLint 룰** | ≈6파일 | 낮음 |
| **PR10** | pathless `_shell` + 51개 재부모화 (**시각 변화 0**) | `router.ts` +8/−52 | 낮음 |
| **PR11** | 전역 사이드바 신설 + `Header` 축소 | +4파일 ≈450 | 🔴 **실질 최대** |
| **PR12** | 프로젝트 사이드바 확장 + `ProjectNavTabs` 통합 | +2파일 | 중 |
| **PR13** | `PageLayout`/`PageHeader`/`Breadcrumb` + `/settings`·`/admin` 인덱스 | +3파일 | 중 |
| **PR17** | `IssueFilterBar`+`BoardFilterBar` → 공유 `FilterBar` | **−350 순감** | 중 |
| **PR18** | 이슈 목록 카드 → `ui/table` 네비게이터 | ≈±400 | 중 |
| **PR19** | 이슈 상세 탭화 + `IssueMetaPanel`(1204줄) 분해 | **−500 순감** | 중 |
| **PR21** | `@dnd-kit/sortable` → 보드 컬럼 내 순서변경 | ≈±150 | 낮음 |
| **PR22** | 원시 `<button>` 정리 + `--chart-*` 실소비 정의 | **−200 순감** | 낮음 |
| *별도 FR* | 댓글 UI | — | — |

### ★ 핵심 3원칙

1. **PR10(재부모화)과 PR11(사이드바)을 반드시 분리한다.** PR10은 "렌더 결과 픽셀 동일"이어야 E2E 122 전원 통과가 **무해함의 순수한 증거**가 된다. 합치면 spec이 깨졌을 때 원인이 재부모화인지 사이드바인지 구분 불가.
2. **Dialog는 가장 어려운 `issue-tracking`(PR5)부터.** 위험 오름차순이 아니라 **API 확정 순서** — 거기서 안 정해지면 나머지 3개가 전부 재작업. PR8의 ESLint 락이 없으면 **재발한다** (룰 없는 흡수는 리팩터가 아니라 일시적 청소).
3. **PR3(토큰)와 PR4(하드코딩 색)를 분리한다.** `text-amber-800`은 Tailwind 리터럴 직참조라 `--primary` 교체에 영향받지 않는다 → **PR3는 141건에 무해**. 합치면 "파란 primary 탓"과 "warning 대비 탓"을 구분 못 한다. 분리하면 PR4는 "시각적으로 거의 no-op이어야 한다"는 명확한 기대치를 갖고, 어긋나는 곳이 곧 버그다.

### PR3 설계 결정 3가지

1. **oklch → hex.** 현재 `:root`는 oklch인데 ADS는 hex로 published. 변환하면 **공식 문서와 대조 검증이 불가능**해진다. Tailwind v4는 색 포맷을 가리지 않으므로 ADS 값을 hex 그대로 넣어 greppable하게 유지.
2. **`--radius` calc 파생 폐기.** 현재 `--radius: 0.625rem` + `--radius-sm: calc(var(--radius) * 0.6)`. ADS 기본 3px을 넣으면 `1.8px` 같은 쓰레기 파생이 나온다 → **2/3/4/8/12px 명시 나열**.
3. **`--chart-1~5`는 PR3 범위 밖.** 소비자 0이라 지금 채우면 그게 PoC다. Recharts 가젯이 실제로 토큰을 쓰는 PR22에서 함께 정의.

### PR11 완화책 — 네비게이션 계약 테스트

착수 **전에** `apps/web/src/components/layout/__tests__/navigation-contract.test.tsx`를 추가한다. aria-label 4종 존재 + 관리 nav의 `isSystemAdmin` 게이팅을 어서션. **현재 `Header` 기준으로 쓰면 즉시 green** → 사이드바 이관 중 라벨을 깨면 vitest가 Playwright 122개보다 **훨씬 빨리** 잡는다. TDD red→green에 정확히 부합.

## 🔒 반드시 지켜야 하는 것 (숫자 붙은 계약)

| 대상 | 이유 |
|---|---|
| **aria-label 4종** — `메인 메뉴` / `관리 메뉴` / `프로젝트 뷰 전환` / workflow-scheme `sidebar.nav` | `getByRole('navigation')` **18건**이 전부 `{name}` 스코프. **유일한 계약** |
| **`role="dialog"`** | e2e **147건**이 Radix `DialogPrimitive.Content`의 role에 의존 → 래퍼가 같은 primitive를 감싸면 **DOM 계약 불변** |
| **`<h1>` 단 하나 + name 글자 보존** | e2e **34건**이 `level:1`. `PageHeader`가 h1 **단독 소유**, 채택 시 페이지 기존 h1 삭제. **사이드바엔 h1 금지** |
| **`검색` aria-label 단일성** | `Header.tsx:124`에 이미 있고 **5 spec** 의존. 사이드바에 중복 시 strict mode 위반 |
| **관리 메뉴 기본 펼침** | 접으면 `webhook.spec.ts:73`·`audit-logs.spec.ts:50`·`notification-policies.spec.ts:83`이 **not visible → 클릭 실패** |
| **`프로젝트 뷰 전환`은 nav+Link** | Radix Tabs는 `role="tablist"`라 `role="navigation"` 소멸 → **e2e 5 + 유닛 5 즉사**. 라우트 이동이라 뒤로가기도 깨짐 |
| **`loginRoute` = `rootRoute` 직속** | `redirectIfAuth` + `already-authed.spec.ts` |
| **49개 `beforeLoad` 가드 위치** (hoist 금지) | `routeGuard.test.tsx` |
| **다크모드 전 경로** | `lib/theme.ts` + `PreferencesProvider` + FOUC 스크립트가 완전 동작 중. ADS 다크 토큰 **동시** 이식 필수 |
| **`--syntax-*` 5종 + `--font-mono`** | 유일하게 대비 계산이 끝난 유채색. `--font-mono`는 AQL overlay↔textarea 정렬 정본 — 건드리면 하이라이트가 밀림 |
| **`.mention`** | `--primary`/`--accent` 직참조 → PR3 후 **대비 재확인** (CONCERN-D2로 명시된 의도적 설계) |

> ★ **규칙. 라우트가 바뀌면 nav+Link, 같은 라우트에서 패널만 바뀌면 Radix Tabs.** 이슈 상세 활동 탭(PR19)은 현재 탭 0개라 깨질 어서션이 없고 라우팅도 아니므로 **거기선 Tabs가 정답**.

## 🗑️ 버려도 되는 것

- `apps/web/src/App.tsx` + `App.test.tsx` — 죽은 코드(`main.tsx`가 import 안 함). PR10에서 같이 삭제
- `--chart-1~5` **현재 값** (무채색·참조 0건) · `--sidebar-*` **현재 값** (shadcn init 산출물·소비자 0)
- `IssueFilterBar`/`BoardFilterBar` 중 하나 (거의 클론, i18n 라벨까지 이원화) · `FilteredEmptyState` 중복 2곳 · `Skeleton` 인라인 2곳 · 뷰 전환 하드코딩 3벌 · calc radius 스케일
- `DESIGN.md` 현재 내용 — **화석이다.** 279 PR 중 커밋 **2건**(#11 최초, #190 AQL). §10~12가 전부 로그인 폼 1개 기준이고 `> 본 PR`이 여전히 #11 지시. 재작성 대상
- ⚠️ `routes/dashboard.tsx`(단수 placeholder) — 삭제 가능하나 **`router.test.tsx`가 `/dashboard`에서 `환영합니다`를 어서션**하므로 동시 수정 필수

## 🅿️ 로드맵 밖

- **파일 기반 라우팅 전환 — 영구 제외 권장.** `routes/*.tsx`가 파일 기반 네이밍이라 유혹적이지만, 52 라우트 + 손수 가드 체인 + `staticData`를 한꺼번에 뒤집는 건 이 개편이 감당할 이유가 없다. **code-based + `_shell`로 목표 100% 달성.**
- **Pretendard 한글 웹폰트** — Geist는 라틴 전용, Atlassian Sans에도 한글 글리프가 없다. **토큰 PR과 엮지 말고 별도 PR.**
- **가드 hoist** — 선택적 후속. 영원히 안 해도 무방.

## Verification

```bash
# 본 커밋 (문서 전용)
bash scripts/verify-master-plan.sh          # exit 0 — 카운트 불변이므로 회귀 확인용

# 코드 PR (PR2~)
cd apps/web && pnpm verify                  # lint + typecheck + test + build
pnpm test:e2e                               # Playwright 122 spec

# FR 동기화 커밋 (#277 머지 후)
bash scripts/verify-master-plan.sh          # ★ exit 0 필수 (1=SDD매핑 2=마커 3=파일부재 4=카운트drift)
grep -rn "128 FR\|129 FR" CLAUDE.md docs/plan/
node scripts/build-dashboard.mjs
```

- **CI typecheck는 `tsconfig.app.json` 기준** — 로컬과 다르므로 별도 확인
- frontend-ci는 lint/typecheck/test 3잡 병렬. **체크 3개 모두 green이어야 정상**, cancelled는 조사 대상
- 시각 회귀 자동화는 없다 → 각 PR마다 시안 대조 수동 확인
