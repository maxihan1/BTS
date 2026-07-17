<!-- FR-UX-06 UI/UX 전면 개편 — ADS v2 팔레트·2025 신형 사이드바·pathless _shell·nav vs Tabs 규칙·논리≠물리·댓글 분리·chart 토큰 지연 결정 ADR -->

# ADR — FR-UX-06 UI/UX 전면 개편 (Jira Cloud 방식)

> 날짜. 2026-07-17 | PR. (문서 PR) | BC. personalization(논리) · `apps/web`(물리) | 상태. 채택
>
> 관련 FR. **FR-UX-06 (신설 예정 — 카운트 동기화는 #277 머지 후)**
> Plan. [docs/plans/2026-07-17-fr-ux-06-jira-redesign/plan.md](../plans/2026-07-17-fr-ux-06-jira-redesign/plan.md)
> 디자인 스펙. [docs/design/fr-ux-06-jira-redesign.md](../design/fr-ux-06-jira-redesign.md)
> 시안. https://claude.ai/code/artifact/de59cb1e-4fae-4755-b18b-fe888ee96023
> 선례. FR-UX-05 (`2026-07-05-fr-ux-05-keymap.md` **§D4 "논리 ≠ 물리"를 본 ADR D5가 승계**)
> 동시 PR. **#277** (`backend/project-management-crud`) — D8 참조

## 맥락

BTS는 Jira를 대체하려는 사내 이슈 트래커인데 **UI가 Jira를 닮은 구석이 없다.** 123 FR 중 대부분이 구현됐으나 그 기능들이 화면 위에서 서로 연결돼 있지 않다.

### 접지 — 코드가 말하는 사실 (전수 대조, 2026-07-17)

| 사실 | 근거 |
|---|---|
| 앱 셸이 사실상 없다 | `apps/web/src/routes/__root.tsx` **1,776 bytes** = `<Header/> + <main><Outlet/></main>`. 사이드바 컴포넌트가 **파일로 존재하지 않음** |
| 네비가 52 라우트 중 2개만 노출 | `components/Header.tsx:91-104` 메인 nav 링크 = 대시보드·캘린더 **2개** |
| 그 결과가 하드코딩 | `routes/issues.index.tsx:38` · `routes/search.tsx` `const DEFAULT_PROJECT_KEY = 'ATLAS'` |
| 브랜드 색이 없다 | `src/index.css:126` `--primary: oklch(0.205 0 0)` (거의 검정). 팔레트 전체 chroma 0 |
| chart 토큰은 죽어 있다 | `--chart-1`~`--chart-5` 전부 chroma 0 **+ 코드 참조 0건** (`chart-[1-5]` grep) |
| 프리미티브 7종 | `components/ui/` = avatar·button·card·dropdown-menu·form·input·label·select·sonner |
| Dialog 복붙 | `import { Dialog as DialogPrimitive } from 'radix-ui'` **35~37개 파일**, Overlay 클래스 문자열 파일마다 중복 |
| 중첩 레이아웃 0 | `src/router.ts` 52개 `createRoute` 전부 `getParentRoute: () => rootRoute` |
| 규모 | 52 라우트 / 프로덕션 .tsx 239 / 약 52,100 LOC / Playwright spec **122** |

**핵심은 색이 아니라 IA(정보 구조)다.** Maxi 원문 — *"전체 ui/ux와 디자인을 지라 클라우드 방식으로 개편하려고 해"*.

## 결정

### D1. 컬러 = ADS **v2** 팔레트 이식. `#0C66E4`(Blue700). 구세대 `#0052CC` 기각

`--primary`를 Blue700 `#0C66E4`로, 텍스트를 Neutral `#172B4D`로 교체한다. 상태 색(Green/Red/Yellow/Purple)까지 ADS 세트를 통째로 가져온다.

**정당한 근거.** "지라 클라우드 방식" 요청에 가장 충실하고, 대비가 검증된 세트를 통째로 확보한다. 토큰이 `index.css` **한 파일**에 집중돼 있어 교체 자체는 국소적이고 `git revert` 1커밋으로 100% 롤백된다.

**★ `#0052CC`를 기각한 근거.** 널리 알려진 "지라 파랑"이지만 **구세대(ADS v1) B400**이다. 2023 토큰 리프레시에서 램프가 재편돼 현재 brand bold는 Blue700 `#0C66E4`다. D2에서 **2025 신형 네비게이션**을 택했으므로 세대를 맞춘다 — 구형 색을 쓰면 네비만 신형이고 색은 구형인 어정쩡한 조합이 된다.

**★ 팔레트는 정본이 아니다.** `atlassian.design`이 JS 렌더링이라 전수 검증에 실패했다. 4개 지점만 독립 확인했고 전부 일치했다 — `#E9F2FF`(Blue100)·`#082145`(Blue1000)는 Atlassian 개발자 문서에서, `#172B4D`·`#0052CC`는 검색 교차 확인. **램프 양 끝점이 검증됐으니 중간 값 신뢰도는 높지만 정본은 아니다** → 토큰 PR에서 `atlassian.design/components/tokens/all-tokens` **전수 대조를 D단계 작업으로 명시한다.**

**부수 결정 3가지.**
1. **oklch → hex.** 현재 `:root`는 oklch인데 ADS는 hex로 published. 변환하면 **공식 문서와 대조 검증이 불가능**해진다. Tailwind v4는 색 포맷을 가리지 않으므로 hex 그대로 넣어 greppable하게 유지.
2. **`--radius` calc 파생 폐기.** 현재 `--radius-sm: calc(var(--radius) * 0.6)` 구조에 ADS 3px을 넣으면 `1.8px` 쓰레기 파생이 나온다 → 2/3/4/8/12px 명시 나열.
3. **`--syntax-*` 5종은 건드리지 않는다.** 유일하게 대비 계산이 끝난 유채색 토큰이고 `--font-mono`와 함께 AQL overlay↔textarea 정렬 정본이다.

### D2. 네비게이션 = Jira Cloud **2025 신형 통합 사이드바**

좌측 전역 사이드바 하나(내 작업/최근/즐겨찾기/프로젝트 트리/대시보드/캘린더/관리), 상단은 로고+검색+만들기+알림+설정+계정. 프로젝트 선택 시 사이드바가 프로젝트 메뉴로 확장.

**정당한 근거.** ① Atlassian의 현재 방향(2025-03-17 롤아웃) ② BTS의 "프로젝트 진입 경로 부재"를 가장 깔끔하게 해결 ③ `index.css`에 `--sidebar-*` 토큰 8종이 **이미 존재**(shadcn init 산출물, 소비자 0)라 재활용 가능.

**기각.** 구형(탑바 드롭다운 + 프로젝트 진입 시에만 좌측 사이드바) — 오랫동안 익숙한 형태지만 Atlassian이 이미 이탈 중이라 "지라 클라우드 방식"의 현재 기준과 어긋난다.

### D3. 라우터 = pathless `_shell`. **파일 기반 라우팅 전환은 영구 제외**

`createRoute({ getParentRoute: () => rootRoute, id: '_shell', component: ShellLayout })`를 도입하고 `loginRoute`를 **제외한** 51개를 재부모화한다.

**★ 위험 오판 정정 — 라우터는 위험하지 않다.** 당초 최대 위험으로 지목했으나 실측이 뒤집었다.
1. **`router.test.tsx`(14KB)는 트리 모양을 검증하지 않는다** — `createMemoryHistory`로 URL을 넣고 렌더된 텍스트만 확인. `routesById`/`fullPath`/`getParentRoute` assertion **0건**
2. **pathless layout은 자식의 `fullPath`를 바꾸지 않는다** — 바뀌는 건 route **id**뿐
3. **route id 결합이 전체에서 1곳** — `getRouteApi` 0건 · `{strict:false}` 36건(id 비의존) · `settings.account-links.tsx:411` 한 줄뿐이고 `as` 캐스팅이라 타입 이득도 없음

→ **재부모화의 관측 가능한 변화 = wrapper div 하나.**

**🔴 유일한 실패 시나리오 2개.** ① `/login`을 shell에 넣기 (`redirectIfAuth` + `already-authed.spec.ts`) ② 49개 `beforeLoad` 가드를 shell로 hoist (가드 평가 순서가 바뀌어 `routeGuard.test.tsx`가 깨짐). **가드는 라우트에 그대로 둔다.**

**★ `_shell` 도입(PR10)과 사이드바 신설(PR11)을 반드시 분리한다.** PR10은 "렌더 결과 픽셀 동일"이어야 E2E 122 전원 통과가 **무해함의 순수한 증거**가 된다. 합치면 spec이 깨졌을 때 원인 분리가 불가능하다.

**기각 — 파일 기반 라우팅 전환.** `routes/*.tsx`가 파일 기반 네이밍을 쓰고 있어 유혹적이지만, 52 라우트 + 손수 쓴 가드 체인 + `staticData` 증강을 한꺼번에 뒤집는 것은 이 개편이 감당할 이유가 없는 리스크다. **code-based + `_shell`로 목표 100% 달성된다.**

### D4. ★ 라우트 이동 = `nav`+`Link` / 패널 전환 = Radix Tabs

**E2E의 실질 계약은 DOM 구조가 아니라 `aria-label` 4개 문자열이다.** `getByRole('navigation')` **18건**이 전부 `{name}` 스코프 — `메인 메뉴`(`Header.tsx:91`) / `관리 메뉴`(`Header.tsx:107`) / `프로젝트 뷰 전환`(`board.tsx:477`·`backlog.tsx:59`) / workflow-scheme `sidebar.nav`.

**즉 "사이드바 재구축"이 "라벨 4개 보존"으로 환원된다.**

board/backlog/timeline의 뷰 전환이 "탭이 아니라 텍스트 링크 나열"이라 Radix Tabs로 바꾸고 싶어진다. **하면 안 된다.**
- Tabs는 `role="tablist"`를 렌더 → **`role="navigation"`이 사라져** e2e 5 + 유닛 5(`backlog.test.tsx:181/193/205`, `projects.board.test.tsx:861/876`)가 **즉사**
- 게다가 **라우트 이동**이지 패널 전환이 아니라 뒤로가기가 깨지고 시맨틱도 틀림

**정답.** `<nav aria-label="프로젝트 뷰 전환">` + `<Link>`를 **탭처럼 스타일링** (Jira Cloud의 프로젝트 네비게이션도 실제로 링크다).

**대비 — 이슈 상세 활동 탭은 Radix Tabs가 정답이다.** 현재 탭이 **0개**라 깨질 어서션이 없고, 라우팅도 아니다(같은 라우트 내 패널 전환).

> **규칙. 라우트가 바뀌면 nav+Link, 같은 라우트에서 패널만 바뀌면 Radix Tabs.**

**동반 계약 3개.** `role="dialog"` e2e **147건**(Radix `DialogPrimitive.Content`의 role → 래퍼가 같은 primitive를 감싸면 DOM 계약 불변이라 흡수 안전) · `<h1>` 단독 e2e **34건**(`PageHeader`가 단독 소유, 사이드바엔 h1 금지) · `검색` aria-label 단일성(`Header.tsx:124` + 5 spec → 사이드바 중복 금지) · **관리 메뉴 기본 펼침**(접으면 `webhook.spec.ts:73`·`audit-logs.spec.ts:50`·`notification-policies.spec.ts:83`이 not visible → 클릭 실패).

### D5. 논리 ≠ 물리 — FR-UX-06의 논리 소속은 personalization

물리 구현은 `apps/web`(프론트 전용)이고 프로젝트 목록 API는 identity-access지만, **FR의 논리적 소속은 personalization으로 유지**한다 (FR-UX-05 ADR **§D4 승계**).

**후보 3개 중 personalization을 고른 결정적 근거.** **personalization은 이미 논리 BC이고 물리 구현이 identity-access다.** 따라서 프로젝트 목록 API를 identity-access에 물리 구현해도 논리 소속은 personalization으로 **일관**된다. identity-access를 주 BC로 고르면 "UI 개편 FR이 인증 BC에 산다"가 되어 의미적으로 더 나쁘고, project-workflow는 책임이 워크플로우/권한스킴이라 안 맞는다.

보조 근거. ① UX 그룹 관례 — FR-UX-01/04/05가 이미 personalization(UX-02/03은 notification-dashboard) ② FR-UX-05 선례가 동형(프론트 전용).

**감수하는 것.** personalization BC 완료 게이트(12/12)가 **풀린다.** BC 책임 문구도 갱신해야 한다 — "UX 편의 (퀵 필터, Slash, 단축키)"에 전역 네비게이션 추가.

### D6. 댓글은 **별도 FR** (본 FR 범위 밖)

**★ "댓글 백엔드 완비"는 오보였다.** 실측하면 `CommentController`는 **`GET /api/v1/issues/{key}/comments` 하나뿐**이고 POST·PATCH·DELETE가 없다. `CommentApplicationService.create()`는 존재하나 **REST로 미노출**이며 Import가 직접 호출한다.

**원인.** 최초 커밋이 `029dd82fe [feature] FR-IM-01 PR3 — 댓글/Worklog Import (#224)` — **댓글 백엔드는 댓글 기능이 아니라 Import의 부산물**이다. 그래서 `fr-index.md`에 댓글 FR이 없고, 오히려 `FR-MN-01`이 *"댓글 멘션은 댓글 기능 부재로 제외(**댓글 FR 도입 시** sourceField=\"comment\"로 확장)"*, `FR-IS-06`이 *"IssueComment 미구현이라 제외"*라며 **기다리고 있다**.

→ 댓글은 온전한 신규 FR(POST 노출 + 수정/삭제 + 멘션 확장 + 이벤트 발행 + 프론트 전부). **개편 PR에 신규 기능을 섞으면 회귀 원인 분리가 안 된다** → 분리 (Maxi 확정). 시안에는 남겨뒀다.

> ★ **교훈. 도메인·서비스·repo가 다 있어도 REST 노출이 없으면 기능이 없는 것이다.** 파일 존재 ≠ 기능 존재. [[subagent-ktlint-false-green-controller-verify]]의 "에이전트 보고 불신, controller 검증"이 lint뿐 아니라 **기능 유무 판단에도** 적용된다.

### D7. ★ `--chart-1~5`는 **실소비 PR에서** 정의한다 (토큰 PR 범위 밖)

무채색인 데다 **코드 참조 0건**이다. ADS 데이터 시각화 팔레트로 채우고 싶어지지만 **소비자가 없는 토큰을 미리 채우면 그게 PoC다** (CLAUDE.md §작업 기준 — 완제품). Recharts 가젯 6종이 실제로 토큰을 쓰게 되는 PR22에서 함께 정의한다.

같은 논리로 `--sidebar-*` 8종(shadcn init 산출물·소비자 0)도 사이드바 PR에서 덮어쓴다.

### D8. 동시 PR #277과의 경계 — Phase 0 선행, Phase 4 양보

**DRAFT PR #277** (`backend/project-management-crud`, 6 PR 체인 중 PR-1 진행 중)이 **`FR-PJ-01~04` + `FR-PM-10` 5개를 선점(123→128)**하고 `GET`/`POST /api/v1/projects` + 생성·목록·설정·아카이브 화면을 만든다.

| 충돌 | 처리 |
|---|---|
| FR 카운트 123→128 | FR-UX-06은 **128→129**. **본 PR에서 FR 동기화 안 함** — main이 123인데 129 주장 시 verify 즉시 fail |
| `GET /api/v1/projects` | 본 계획의 PR-0' **삭제** |
| #277 PR-5 = "D6 프론트 UI — 생성·목록·설정·아카이브" | Phase 4(PR14·PR15) **양보** |
| identity **V036** | 회피 |

**★ `fr-index.md`엔 FR-PJ가 아직 없다 — `docs/specs/2026-07-17-project-management-crud.md`에만 선점돼 있다.** fr-index만 grep하면 못 본다.

**순서 (Maxi 확정).** Phase 0(문서·ADS 토큰·프리미티브 15종)은 #277과 독립이고 소비자를 안 건드려 위험 ≈ 0 → **먼저 머지**해 #277 PR-5가 새 디자인 시스템 위에 짓게 한다. 반대면 PR-5가 현재 디자인(무채색·프리미티브 7종·셸 없음) 위에 화면 4개를 짓고 개편이 그걸 다시 뜯는다. **머지 후 디자인 스펙을 #277에 공유한다.**

## 결과

**얻는 것.**
- `index.css` 1파일 교체로 256개 `<Button>`이 한 번에 Jira 블루가 된다. 롤백은 `git revert` 1커밋.
- 사이드바가 52 라우트 중 대부분을 노출 → `DEFAULT_PROJECT_KEY` 하드코딩의 **원인**이 사라진다 (제거 자체는 #277).
- Dialog 35곳 흡수로 Overlay 문자열 복붙이 사라지고 순 LOC가 준다. `FilterBar` 통합 −350, `IssueMetaPanel` 분해 −500.
- 시맨틱 토큰 4쌍이 하드코딩 색 141건을 흡수하고, **토큰이 다크를 자동 처리하므로 손수 짠 `dark:` 페어가 삭제**돼 순 LOC가 준다.

**감수하는 것.**
- personalization BC 완료 게이트(12/12)가 풀린다 (D5).
- **D6(프론트 UI) 하나에 15 PR이 몰린다** — Maxi가 "개편 전체를 FR로"를 택한 대가. **D6를 PR 체인으로 쪼개 표기**해 완화한다 (FR-AT-07의 PR-A/B/C 선례).
- **PR3는 자동 검증이 안 되는 유일한 PR이다.** E2E 122개는 색을 검증하지 않는다 → 대비비(WCAG AA 4.5:1) 수동 체크 + 시안 대조가 유일한 게이트.
- `DESIGN.md` 재작성이 필요하다 — **화석이다.** 279 PR 중 커밋 **2건**(#11 최초, #190 AQL). §10~12가 전부 로그인 폼 1개 기준이고 `> 본 PR`이 여전히 #11을 가리킨다.

**후속 (본 ADR 범위 밖).**
- **FR-UX-06 전수 동기화 8종** — #277 머지 후 별도 커밋 (128→129)
- **댓글 FR** (D6)
- **Pretendard 한글 웹폰트** — Atlassian Sans에도 한글 글리프가 없다. **토큰 PR과 엮지 말 것** (섞으면 "팔레트 탓"과 "폰트 탓"을 구분 못 함)
- `--chart-*` 실소비 정의 (D7)

## 대안 (기각)

- **(i) BTS 고유 색조 (ADS 구조 + 다른 hue).** **기각** — "지라 클라우드 방식" 요청에 덜 충실하고, hue를 새로 고르면 WCAG AA 대비를 전부 직접 검증해야 한다. ADS는 그게 끝나 있다.
- **(ii) 무채색 유지 + 포인트만 블루.** **기각** — 변경 폭은 가장 작지만 Jira 특유의 컬러풀한 상태 표현(로젠지·차트)을 못 살린다.
- **(iii) FR 없이 ADR로만 (전역 네비만 FR).** 담당자 권고였으나 **Maxi 기각** — 진척 가시성 우선. 기록해 둔다.
- **(iv) #277 완주 대기.** **기각** — 6 PR 체인이라 오래 걸리고, 그동안 PR-5가 구디자인으로 화면 4개를 지으면 전부 다시 뜯어야 한다 (D8).
- **(v) 파일 기반 라우팅 전환.** **기각** — D3 참조. 영구 제외 권장.

## 관련

- Plan. [docs/plans/2026-07-17-fr-ux-06-jira-redesign/plan.md](../plans/2026-07-17-fr-ux-06-jira-redesign/plan.md) (+ `checklist.md` · `context-notes.md`)
- 디자인 스펙. [docs/design/fr-ux-06-jira-redesign.md](../design/fr-ux-06-jira-redesign.md)
- 선례 — 논리 ≠ 물리. [2026-07-05-fr-ux-05-keymap.md](2026-07-05-fr-ux-05-keymap.md) §D4
- 선례 — PR 체인 분할. [2026-07-16-fr-at-07-pr-b-fix-version-port.md](2026-07-16-fr-at-07-pr-b-fix-version-port.md)
- 동시 PR. `docs/specs/2026-07-17-project-management-crud.md` (#277)
- 상태 모델 정합. `backend/modules/project-workflow/src/main/resources/workflows/software-default.yaml`
- 디자인 시스템 정본(재작성 대상). `DESIGN.md`
