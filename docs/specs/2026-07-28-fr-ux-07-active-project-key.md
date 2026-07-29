<!-- FR-UX-07 PR1 스펙 — projectKey URL·활성 컨텍스트 승격 + FR-UX-07 신설. 논리 BC personalization / 물리 apps/web -->

# FR-UX-07 PR1 — 활성 프로젝트 컨텍스트 + projectKey URL 승격 — 스펙

> slug: `fr-ux-07-active-project-key` · type `ui` · agent `frontend-engineer`
> 논리 BC `personalization` / 물리 `apps/web` · PR #320
> ADR: [2026-07-28-fr-ux-07-active-project-context.md](../decisions/2026-07-28-fr-ux-07-active-project-context.md) (D1~D5)
> 로드맵: `~/.claude/plans/ui-ux-sorted-kay.md` §F1

## 0. 문제

`DEFAULT_PROJECT_KEY = 'ATLAS'` 하드코딩(`routes/issues.index.tsx:47,788` · `routes/search.tsx:21,478`)이 이슈 목록 진입로 **4개**(사이드바 "이슈" · 팔레트 "내 이슈" · 로그인 후 시작 페이지 · 단축키 `g i`)를 전부 ATLAS 로 못박는다. `issuesIndexRoute.validateSearch`(`router.ts:126-158`)에 `projectKey` 가 없어 URL 로 바꿀 수단조차 없다.

## 1. ★ 핵심 설계 — 링크가 아니라 라우트가 해소한다

스펙 작성 중 발견한 단순화. **진입로 4곳을 고치지 않는다.**

| 대안 | 내용 | 판정 |
|---|---|---|
| A. 링크가 `search={{projectKey}}` 를 실어 보낸다 | `Sidebar`·`commands.ts`·`shortcuts.ts`·`start-page.ts` 4곳이 활성 프로젝트를 계산해 링크에 붙인다 | **기각** |
| **B. `/issues` 라우트가 스스로 해소한다** | 링크는 `/issues` 그대로. 페이지가 `useActiveProject()` 로 해소 | **채택** |

**A 를 기각하는 이유 — 셋 다 실질적이다.**
1. **비동기 순서 문제.** 활성 프로젝트 해소는 `useProjects()` 응답에 의존한다(폴백 ③). 사이드바는 그보다 먼저 렌더되므로 링크가 한 박자 늦게 바뀐다.
2. **`QUICK_LINKS` 는 정적 배열이어야 한다.** 동적으로 만들면 `command-palette.spec.ts:252-259` 의 **"ArrowDown 1회 → 2번째 = 검색"** 순서 계약이 흔들린다.
3. **`shortcuts.ts` 는 건드리면 4곳이 동시에 깨진다.** `shortcuts.test.ts:121` `toHaveLength(5)` + `:147` `DEFAULT_KEYMAP` 완전일치 + 백엔드 `KeymapAction.kt` 5종 화이트리스트 + `user_keymap.action` CHECK 제약.

**B 의 결과.** 변경 파일이 **7 → 4** 로 줄고, 위 세 계약을 **하나도 건드리지 않는다**. 로드맵 §F1 이 적었던 "진입로 4곳 배선"은 **불필요**한 것으로 판명 — 라우트 한 곳을 고치면 4곳이 동시에 낫는다.

### URL 정규화(canonicalize)는 하지 않는다

`/issues` 진입 시 해소된 projectKey 를 URL 에 `replace` 로 써넣는 방안을 검토했으나 **기각**한다. 이 저장소에는 **transient URL 관측 race 로 e2e 가 90초 hang 한 선례**가 있다(`saved-filters` SF-1/SF-3, `?filterId=`). 정규화가 주는 이득(공유 가능한 URL)은 `?projectKey=` 를 명시로 지원하는 것으로 이미 확보되므로, 마운트 직후 URL 이 바뀌는 패턴을 새로 들이지 않는다.

## 2. 사용자 시나리오 (Given-When-Then)

**S1 — 명시 지정이 최우선**
- Given 사용자가 `INFRA` 프로젝트에 접근 권한이 있고, 마지막 활성 프로젝트는 `ATLAS` 다
- When `/issues?projectKey=INFRA` 로 진입한다
- Then INFRA 이슈 목록이 보이고, 활성 프로젝트가 `INFRA` 로 갱신된다

**S2 — 저장값 폴백**
- Given 사용자가 이전에 `INFRA` 보드를 보다가 브라우저를 닫았다
- When 다시 로그인해 사이드바 "이슈" 를 누른다 (URL 에 projectKey 없음)
- Then INFRA 이슈 목록이 보인다 (ATLAS 아님)

**S3 — 첫 방문 폴백**
- Given 저장값이 없고 URL 에도 projectKey 가 없다
- When `/issues` 로 진입한다
- Then 접근 가능한 **첫 프로젝트**의 이슈 목록이 보이고, 그 값이 저장된다

**S4 — 프로젝트 컨텍스트 추종**
- Given 사용자가 `/projects/INFRA/board` 를 보고 있다
- When 사이드바 "이슈" 를 누른다
- Then INFRA 이슈 목록이 보인다

**S5 — 프로젝트 0개**
- Given 사용자가 접근 가능한 프로젝트가 하나도 없다
- When `/issues` 로 진입한다
- Then 이슈를 조회하지 않고 빈 상태 안내 + `/projects` 링크가 보인다

**S6 — 저장값이 낡음**
- Given 저장값이 `OLDPROJ` 인데 그 프로젝트가 삭제됐거나 권한이 회수됐다
- When `/issues` 로 진입한다
- Then `OLDPROJ` 로 조회하지 않고 폴백 ③(첫 프로젝트)으로 내려가며, 저장값이 교정된다

**S7 — 명시 지정이 실패**
- Given 사용자가 `NOPERM` 에 접근 권한이 없다
- When `/issues?projectKey=NOPERM` 로 진입한다
- Then 조용히 다른 프로젝트로 바꾸지 않고 **에러를 표시**한다 (사용자가 명시로 요청한 것이므로 권한 문제를 숨기지 않는다). 저장값도 갱신하지 않는다

**S8 — 시작 페이지 파생 결함 해소**
- Given 사용자의 시작 페이지가 `my_issues` 이고 ATLAS 에는 담당 이슈가 없다
- When 로그인한다
- Then 활성 프로젝트 기준 담당 이슈가 보인다 (기존에는 항상 ATLAS 라 빈 화면)

## 3. 기능 요구사항 (FR)

| ID | 내용 |
|---|---|
| **FR1** | `router.ts` `issuesIndexRoute.validateSearch` 에 `projectKey?: string` 추가. 기존 7종(page·status·assignee·label·component·sort·selected) 파싱 방식과 동일하게 `typeof === 'string'` 가드 |
| **FR2** | `hooks/use-active-project.ts` 신설 — localStorage 영속 스토어(zustand). 키 `bts.active-project`. `hooks/use-sidebar-collapsed.ts:15-45` 의 fail-safe 3중 폴백(SSR·스토리지 차단·파싱 실패) 패턴 복제 |
| **FR3** | 해소 — 우선순위 ① URL `projectKey`(검색 파라미터 **또는** 경로 파라미터 `/projects/$projectKey/*`) → ② 저장값(접근 가능 목록에 실재할 때만) → ③ 목록의 **첫 원소**(`projects[0]`) → ④ `null`(0개). **프론트 재정렬 금지** — 백엔드가 이미 `ORDER BY name ASC`(`ProjectQueryRepository.kt:62`)이고 저장소 관례가 "백엔드 정렬 신뢰, 프론트 재정렬 없음"(`routes/projects.index.tsx:42`·`hooks/use-projects.ts:10`)이다 (G1·C4) |
| **FR3-b** | 조합 훅 `useResolvedActiveProject()` **1개**가 `useProjects()` + 저장값 + URL 을 묶는다. `/issues`·`/search` 가 각자 조합하면 드리프트가 확정된다 (C2) |
| **FR3-c** | **경로 파라미터 기록기** — `/projects/$projectKey/*` 계열 라우트를 볼 때 그 키를 저장값에 기록한다. 전 인증 라우트 공통 셸(`ShellLayout`)에 훅 1개로 마운트. **이것이 없으면 S4 가 성립하지 않는다** (B1) |
| **FR4** | 해소 출처가 **`url` 또는 `first`** 일 때 저장값에 반영한다. `first` 를 저장하지 않으면 S3("그 값이 저장된다")·S6/E3("저장값이 교정된다")가 미충족이고, `useProjects` 재조회(`refetchOnWindowFocus` 기본 true)로 첫 원소가 바뀌면 **사용자가 아무 조작도 안 했는데 프로젝트가 갈아탄다** (B3). 출처가 `stored` 면 write 생략(E7) |
| **FR4-b** | URL 의 projectKey 를 **다른 navigate 가 지우지 않는다.** `issues.index.tsx` 의 navigate 5곳 중 `handleFilterChange`(`:767-770`)만 `...prev` 를 안 펼쳐 projectKey 가 증발한다. TanStack `search` 는 **객체형이면 병합이 아니라 치환**이고 전 필드가 optional 이라 **타입 체크로도 안 잡힌다** (B2) |
| **FR5** | `routes/issues.index.tsx` — `DEFAULT_PROJECT_KEY` 상수 제거, 해소 결과를 `IssueListPage projectKey` 로 전달 |
| **FR6** | `routes/search.tsx` — `DEFAULT_PROJECT_KEY` 상수 제거, `search.projectKey` 부재 시 해소 결과 사용 (기존 우선순위 구조 유지) |
| **FR7** | 프로젝트 목록 로딩 중에는 이슈를 조회하지 않는다 (빈 projectKey 로 요청 금지) |
| **FR8** | 프로젝트 0개 → `EmptyState`(기존 `components/ui/empty-state.tsx` 소비) + `/projects` 링크. 이슈 조회 0회 |
| **FR9** | **FR-UX-07 등록** — 8종 정본 전수 동기화 131→132. `verify-master-plan.sh` EXIT 0 |

**FR 아님 (명시적 비-요구).** 진입로 4곳(`Sidebar.tsx`·`commands.ts`·`shortcuts.ts`·`lib/start-page.ts`) **무변경**. §1 설계 B 의 결과로 자동 해소된다.

## 4. 비기능 요구사항 (NFR)

| ID | 내용 | 측정 |
|---|---|---|
| **NFR1** | 활성 프로젝트 저장값은 **UI 선호값**이며 토큰·PII 를 담지 않는다 (절대 규칙 §1.18 무관 — 근거 주석 필수) | 코드 리뷰 + KDoc |
| **NFR2** | localStorage 접근 불가(시크릿 모드·차단)에서도 앱이 정상 동작한다 | 유닛 — `localStorage.getItem` throw 주입 |
| **NFR3** | 기존 e2e 계약 무위반 — `QUICK_LINKS` 순서 · `SHORTCUTS` 5종 · nav `aria-label` 4종 · `<h1>` 단일 | `shortcuts.test.ts` 무수정 green + 관련 e2e |
| **NFR4** | 백엔드 변경 0 · 마이그레이션 0 · 신규 npm 의존성 0 | `git diff --stat` |
| **NFR5** | 마운트 직후 URL 이 바뀌지 않는다 (transient URL race 회피) | e2e — 진입 후 URL 불변 어서션 |

## 5. API 인터페이스

**신규·변경 없음.** 소비만 한다.

| API | 용도 | 기존 소비처 |
|---|---|---|
| `GET /api/v1/projects` | 접근 가능한 프로젝트 목록 (폴백 ③·저장값 유효성 검사) | `hooks/use-projects.ts:19` `useProjects()` (staleTime 30s) — `ProjectTree` 가 이미 소비 중 |

## 6. 데이터 모델 변경

**없음.** 테이블 0 · Flyway 마이그레이션 0 · jOOQ 재생성 0.

클라이언트 저장소만 — localStorage 키 `bts.active-project`, 값은 프로젝트 키 문자열(JSON 인코딩).

## 7. 엣지 케이스

| # | 상황 | 처리 |
|---|---|---|
| E1 | 프로젝트 목록 로딩 중 | 이슈 조회 보류 + 로딩 표시. **빈 projectKey 로 요청 금지** |
| E2 | 프로젝트 목록 조회 실패 | 에러 + 재시도. 저장값으로 추측 진행 금지 |
| E3 | 저장값이 접근 가능 목록에 없음 (삭제·아카이브·권한 회수) | 폴백 ③ 으로 내려가고 저장값 교정 (S6) |
| E4 | URL projectKey 가 접근 불가 | 에러 표시. 조용한 대체 금지. 저장값 미갱신 (S7) |
| E5 | localStorage 에 문자열 아닌 값(수동 변조) | 기본값 취급 → 폴백 ③ |
| E6 | 프로젝트 0개 | 빈 상태 (S5) |
| E7 | 같은 프로젝트 키가 URL·저장값에 동일 | 저장 쓰기 생략 (불필요한 write 방지) |
| E8 | `/search` 는 이미 `?projectKey=` 를 읽는다 | 우선순위 구조 유지, 폴백 상수만 해소 결과로 교체 |
| E9 | split view `?selected=` 와 동시 사용 | 직교. `selected` 는 이슈 키, `projectKey` 는 스코프 — 상호 무관 |
| E10 | 프로젝트 키 대소문자 | 백엔드 계약대로 대문자. 저장값을 변환하지 않고 그대로 보존 |

> **★ E8 라벨 정정 (2026-07-29).** e2e 에서 「검색 화면도 같은 활성 프로젝트를 따른다」 테스트가 `S8` 로 **오라벨**돼 있었다. §2 의 S8 은 *"시작 페이지 파생 결함 해소"* 라는 **완전히 다른 시나리오**이고, 그 테스트가 실제로 검증하는 것은 이 표의 **E8**(`/search` 폴백 상수를 해소 결과로 교체)이다. `apps/web/e2e/active-project.spec.ts` 에서는 이미 정정됐다(테스트명 `E8: …` + 정정 사유 블록주석). **이 표가 정본**이다 — S8 은 §2, E8 은 §7 이다.

## 8. 제약 조건

- **백엔드 무변경** (Maxi 결정 2). cross-project 조회(B3)는 범위 밖 — `/issues` 는 v1 에서 프로젝트 스코프 유지 (ADR D5)
- **신규 의존성 0.** zustand·TanStack Query·TanStack Router 모두 기존
- **`shortcuts.ts` 무변경** — 건드리면 프론트 2단언 + 백엔드 enum + DB CHECK 가 동시에 깨진다
- **`components/ui/empty-state.tsx` 재사용** — 새 빈 상태 컴포넌트 신설 금지
- **`hooks/use-projects.ts` 재사용** — 새 프로젝트 조회 훅 신설 금지
- **★ `projectKey: string` non-nullable 계약을 가진 소비처가 4곳이다. 어느 것도 nullable 로 바꾸지 않는다** (G3, B4 로 확장)
  - `IssueListPage`(`issues.index.tsx:337`) — nullable 화 시 `:453` queryKey · `:456` fetch · `:469` 권한 · `:549` invalidate · `:582` 하위전달 5지점 파급
  - `SearchPage`(`search.tsx:242`, 전달 `:593`) · `SaveFilterDialog`(`:609`) · `ExportDialog`(`:616`)
  - 해소 전(로딩)·0개 상태는 **각 라우트 어댑터가 조기 반환으로 흡수**한다. 빈 문자열이 새면 백엔드가 빈 스코프 권한 평가로 조용히 차단한다(`IssueApplicationService.kt:1021`)
- **★ split view 상세 페인은 프로젝트 해소에 끌려가면 안 된다** (C5). `issues.index.tsx:786-822` 에서 `listPage` 는 `IssueListSplitView` 좌측 자식이다. 어댑터 **최상단** 조기 반환으로 구현하면 우측 `IssueDetailPage variant='pane'` 까지 사라져 `/issues?selected=ATLAS-3` 딥링크가 프로젝트 조회 실패에 끌려 죽는다. 조기 반환은 **목록 영역 한정**
- **해소 결과는 `useMemo` 로 안정화**하고 effect 의존성엔 원시값(`key`·`source`)만 넣는다. 매 렌더 새 객체를 의존성 배열에 넣으면 무한 루프 (N3)

## 9. 측정 가능한 완료 기준

- [ ] `grep -rn "DEFAULT_PROJECT_KEY" apps/web/src` → **0건**
- [ ] `router.ts` `issuesIndexRoute.validateSearch` 가 `projectKey` 를 파싱한다
- [ ] S1~S8 시나리오가 유닛/e2e 로 커버된다
- [ ] `shortcuts.test.ts` **무수정** green (`toHaveLength(5)` 유지가 §1 설계 B 의 성공 판정식)
- [ ] `Sidebar.tsx`·`commands.ts`·`shortcuts.ts`·`lib/start-page.ts` **diff 0**
- [ ] `apps/web` typecheck 0 · eslint 0 error · 유닛 green (기준선 7,935 + 신규분)
- [ ] `bash scripts/verify-master-plan.sh` EXIT **0** (132/132)
- [ ] `git diff --stat` 에 `backend/` 파일 **0건**
- [ ] 브라우저 눈확인 — 프로젝트 전환이 이슈 목록에 반영되는지 (라이트/다크)
- [ ] **필터를 바꿔도 URL 의 `?projectKey=` 가 남는다** (B2 회귀 가드)
- [ ] **보드 → 사이드바 "이슈" 가 그 프로젝트를 연다** (S4/B1)
- [ ] 봉인 테스트가 **`const DEFAULT_PROJECT_KEY = 'ATLAS'` 를 실제로 잡는다** (B5 — 판별식 비-공허 증명)

## 10. 알려진 한계 (이 PR 범위 밖, 명시적으로 남김)

| # | 한계 | 근거 / 후속 |
|---|---|---|
| L1 | **로그아웃 상태에서 `?projectKey=` 공유 링크를 열면 착지하지 못한다.** `routeGuard.ts:60-61` 의 `returnTo` 가 쿼리 포함 문자열을 `redirect({ to })` 로 넘기는데 router-core 는 `to` 를 **pathname 으로** 해석해 쿼리가 경로에 박힌다. 기존 결함이나 이 PR 이 `?projectKey=` 를 "공유해도 되는 것"으로 처음 승격시켜 비로소 실사용된다 (C7) | `routeGuard.test.tsx` returnTo 5종이 전부 쿼리 없는 경로만 검증 — 미검증 구간. 별도 수정 PR |
| L2 | **`/issues/new` 가 활성 프로젝트를 못 받는다.** 진입점 2곳(`issues.index.tsx:392-399`·`TopBar.tsx:75`)이 컨텍스트를 안 넘기고 폼은 `defaultValues: { projectKey: '' }`(`issues.new.tsx:153`) (N1) | 로드맵 **F2(이슈 생성 모달)** 가 처리 |
| L3 | **활성 프로젝트가 기기 간에 안 넘어간다.** localStorage 이므로 (ADR D4) | 서버 `user_preferences` 확장은 후속 FR 후보 |
| L4 | **`bts.active-project` 에 사용자 스코프가 없다.** 로그아웃 시 정리되지 않는다 (C8) | 화면 오동작·정보 노출 없음 — FR3 ②가 "접근 가능 목록에 실재할 때만" 이라 다른 사용자에겐 탈락하고 FR4 의 `first` 저장이 첫 방문에 덮어쓴다. `use-column-visibility`·`use-timeline-zoom` 과 동급의 기기별 UI 선호값 |
| L5 | **봉인 판별식 P3 는 시드 4키만 막는다.** `src/__tests__/active-project-contract.test.ts` 의 `P3-시드리터럴` 은 `/['"](?:ATLAS\|MIDDLE\|ZETA\|NOVA)['"]/` 라, **시드가 아닌 실 프로젝트 키**를 임의 이름 상수에 넣는 형태(`const FALLBACK = 'PAYMENTS'`)는 못 잡는다 | **형태 기반 판별식은 채택 불가.** 값 대신 형태(`= '대문자 2~10자'`)로 넓히면 정당한 enum·판별자 리터럴이 대량으로 걸린다 — 봉인 테스트와 **동일한 스캔·주석 제거 로직으로 실측 95히트 / 40파일**(스캔 대상 487파일). 화이트리스트가 룰보다 커져 봉인이 의미를 잃는다. P1(상수 선언형)·P2(대입/프로퍼티형)이 실제 회귀 형태를 덮으므로 **P3 는 별칭 우회의 보조 그물**로만 둔다 |
| L6 | **S8 e2e 는 「빈 화면 → 채워짐」을 재현하지 못한다.** MSW 목이 `GET /api/v1/issues` 를 `projectKey` 로 필터링하지 않아(`issue-fixtures` 가 프로젝트 스코핑을 흉내내지 않는다) 화면 내용의 전후 대비가 관측되지 않는다 | **대체 증명으로 통과.** 결함의 정확한 원인(`DEFAULT_PROJECT_KEY = 'ATLAS'` 하드코딩)이 사라졌다는 유일한 관측 가능 증거 — 재로그인 후 실제 발사되는 요청의 `projectKey` 쿼리가 활성 프로젝트(`MIDDLE`)인지 — 를 `page.waitForRequest` 네트워크 인터셉트로 확인한다. **완전 검증에는 백엔드의 실 project-scoping 이 필요**하고 이는 기존 e2e 스펙 약 120건의 픽스처 전제를 흔들어 이 PR 범위 밖이다 |

## Brainstorming Check

✅ 통과 (1회 iteration). **`office-hours` 는 호출하지 않았다** — YC 아이디어 검증 도구라 FR 이 이미 정의되고 도메인이 확정된 작업엔 프레임이 안 맞는다(2026-05-29 Maxi 확정, [[bts-spec-office-hours-mismatch]]). `design-consultation`(DESIGN.md 실재)·`design-shotgun`(새 화면 아님 — 라우팅·상태 배관) 도 스킵.

Phase B 는 추상 브레인스톰 대신 **스펙의 사실 주장을 코드로 되짚는 방식**으로 수행했다. 결과 3건.

| # | 항목 | 결과 |
|---|---|---|
| **G1** | "접근 가능한 첫 프로젝트" 가 **정의되지 않은 표현**이었다 | **갭 → 해소.** `ProjectQueryRepository.kt:62` `.orderBy(PROJECTS.NAME.asc())` 실측 → **이름 오름차순 첫 번째**로 확정. `use-projects.ts` KDoc 의 "name 오름차순, 백엔드 정렬 신뢰" 주장이 정확함도 확인. FR3 에 반영 |
| **G2** | `QUICK_LINKS` 순서 계약이 실재하는가 | **확증.** `e2e/command-palette.spec.ts` 가 주석으로 *"commands.ts 순서: 내 이슈(0)/검색(1)/대시보드(2)/받은 편지함(3)"* 를 못박고 ArrowDown 1회 → "검색" 을 어서션. §1 설계 B 가 `commands.ts` 를 안 건드리므로 보존됨 |
| **G3** | 해소 전·프로젝트 0개일 때 `IssueListPage` 에 무엇을 넘기는가 | **갭 → 해소.** `issues.index.tsx:337` `projectKey: string` **non-nullable** 실측. nullable 로 바꾸면 5지점(:453 queryKey·:456 fetch·:469 권한·:549 invalidate·:582 하위전달)에 파급 → **어댑터가 흡수**하는 것으로 §8 제약에 명시 |

**한계 — 독립 리뷰는 미실시.** 위 3건은 전부 내가 쓴 스펙을 내가 되짚은 것이다. 이 저장소의 최근 이력에서 **독립 리뷰가 내가 만든 BLOCKER 를 반복 적발**했으므로(#317 4연속·#314 2회), 게이트 2 의 `bts-codereview` 가 실질 안전망이다.
