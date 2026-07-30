<!-- FR-UX-08 스펙 — 프로젝트 스위처(F12) + 트리 펼침 영속 + 사이드바 "내 작업"·"최근 항목"(F17). 논리 BC personalization / 물리 apps/web -->

# FR-UX-08 — 프로젝트 스위처 · 최근 항목 · 내 작업 — 스펙

> slug: `fr-ux-08-project-switcher` · type `ui` · agent `frontend-engineer`
> 논리 BC `personalization` / 물리 `apps/web` · PR #326
> ADR: [2026-07-30-fr-ux-08-project-switcher.md](../decisions/2026-07-30-fr-ux-08-project-switcher.md) (D1~D6)
> 선행: [FR-UX-07 활성 프로젝트 컨텍스트](2026-07-28-fr-ux-07-active-project-key.md) (PR #320)
> 로드맵: `~/.claude/plans/ui-ux-sorted-kay.md` §F12 · §F17

## 0. 문제

FR-UX-07 이 **활성 프로젝트**를 세웠지만 **그것을 바꿀 UI 가 없다.** 사용자가 다른 프로젝트로 옮기려면 URL 을 손으로 고치거나, 사이드바 트리에서 그 프로젝트의 보드로 들어가는 우회로뿐이다.

동시에 사이드바에는 **백킹 없는 항목이 빠진 자리**가 남아 있다. `nav-labels.ts:9` 의 S3 규칙이 *"백킹 라우트·기능이 없는 항목(내 작업·최근·필터)은 포함하지 않는다. **각 항목은 해당 기능 FR 에서 추가한다**"* 로 명시 이연했고, 이 FR 이 그 중 둘("내 작업"·"최근")의 추가 시점이다.

그리고 트리에는 두 개의 결함이 있다.
- **접은 것이 다시 열린다.** `ProjectTree.tsx` 의 `useEffect` 가 라우트 이동마다 `setExpandedKeys(new Set([activeProjectKey]))` 로 펼침 집합을 **갈아엎는다**.
- **활성 프로젝트를 보지 않는다.** `ProjectTree.tsx:369` 가 `useParams({ strict: false }).projectKey`(**경로** 파라미터)만 읽어, `/issues?projectKey=MIDDLE`(**검색** 파라미터)에서는 트리가 전부 접힌 채 아무것도 활성으로 표시하지 않는다.

## 1. ★ 핵심 설계 두 가지

### 1-A. "내 작업" 링크는 `projectKey` 를 싣지 않는다 (FR-UX-07 §1 설계 B 승계)

| 대안 | 내용 | 판정 |
|---|---|---|
| A. 링크가 `search={{ projectKey, assignee }}` 를 둘 다 싣는다 | 사이드바가 활성 프로젝트를 계산해 링크에 붙인다 | **기각** |
| **B. `assignee` 만 싣고 `projectKey` 는 라우트가 해소한다** | `to='/issues' search={{ assignee: userId }}`. 프로젝트는 `useResolvedActiveProject` 가 이미 해소 | **채택** |

**A 를 기각하는 이유.** FR-UX-07 §1 이 이미 같은 이유로 같은 패턴을 기각했다 — **활성 프로젝트 해소는 `useProjects()` 응답에 의존하는데 사이드바는 그보다 먼저 렌더된다.** 링크가 한 박자 늦게 바뀌고, 그 사이 클릭하면 빈 projectKey 가 실린다.

**B 가 성립하는 근거 — 두 값의 성질이 다르다.**
- `userId` 는 **동기**다. `useAuthUser()` 가 zustand persist 스토어에서 즉시 읽는다(`authStore.ts:50`). 비동기 순서 문제가 없다.
- `projectKey` 는 **비동기**다. 그래서 링크가 아니라 라우트가 해소한다 — `/issues` 는 이미 그렇게 동작한다.

**검증 완료.** `routes/issues.index.tsx:711,717` 이 `search.assignee` → `searchToIssueFilter` → `IssueListPage.filter` 로 이미 소비한다. **`/issues` 라우트 무변경으로 동작한다.**

### 1-B. 스위처 선택의 착지점은 **현재 라우트의 성격**이 정한다

| 현재 위치 | 선택 시 동작 | 이유 |
|---|---|---|
| URL 에 `$projectKey` **경로 파라미터**가 있다 (`/projects/ATLAS/board` 등) | 같은 하위 경로로 **치환 이동** (`/projects/INFRA/board`) | 활성 프로젝트 해소 ①(URL)이 최상위라, 활성값만 바꾸면 URL 이 이기고 `useTrackActiveProject` 가 원래 키를 **되기록**해 선택이 즉시 되돌려진다 |
| 경로 파라미터가 없다 (`/issues` · `/search` · `/dashboards` 등) | **활성값만 갱신**, 라우트 유지 | 라우트가 해소 ②(저장값)로 스스로 갱신한다. 원치 않는 화면 이동을 만들지 않는다 |

**기각한 단순안 — "항상 `/projects/<key>/board` 로 이동".** 구현은 더 쉽지만, `/issues` 에서 프로젝트만 바꾸려던 사용자를 보드로 튕긴다. 위 분기는 `useParams({ strict: false }).projectKey` 유무 하나로 판정되므로 복잡도 증가가 사실상 없다.

> **★ 이 결함은 설계 없이 구현했다면 반드시 났다.** "선택했는데 아무 일도 안 일어나고 목록이 원래대로 돌아온다" 는 재현은 쉽지만 원인 추적은 어렵다(`useTrackActiveProject` 의 되기록이 범인). E7 에 회귀 가드를 둔다.

## 2. 사용자 시나리오 (Given-When-Then)

**S1 — 스위처로 프로젝트 전환 (경로 파라미터 없음)**
- Given 사용자가 `/issues` 에서 ATLAS 이슈를 보고 있다
- When 상단바 스위처를 열어 INFRA 를 선택한다
- Then 같은 `/issues` 에 머문 채 INFRA 이슈 목록으로 바뀌고, 활성 프로젝트가 INFRA 로 저장된다

**S2 — 스위처로 프로젝트 전환 (경로 파라미터 있음)**
- Given 사용자가 `/projects/ATLAS/board` 를 보고 있다
- When 스위처에서 INFRA 를 선택한다
- Then `/projects/INFRA/board` 로 이동한다 (같은 하위 경로 유지)

**S3 — 스위처 목록 정렬**
- Given 사용자가 최근 INFRA → ZETA 순으로 방문했고, 접근 가능 프로젝트는 ATLAS·INFRA·MIDDLE·ZETA 다
- When 스위처를 연다
- Then 상단에 최근 방문 그룹(ZETA·INFRA)이 MRU 순으로, 그 아래 나머지가 백엔드 순서(이름 오름차순)로 보인다

**S4 — 펼친 트리가 유지된다**
- Given 사용자가 ATLAS·INFRA 두 프로젝트를 펼쳐 뒀다
- When `/dashboards` 로 갔다가 돌아온다 (또는 새로고침한다)
- Then ATLAS·INFRA 가 **둘 다 펼쳐진 채** 그대로다 (기존에는 라우트 이동 시 전부 접혔다)

> **★ 영속 범위는 프로젝트 레벨이다.** `expandedKeys`(`ProjectTree.tsx:362`)는 **프로젝트 키만** 담는다. 중첩그룹("리포트"·"프로젝트 설정")의 펼침은 `ProjectTreeRow` 의 로컬 `useState`(`:304-305`)라 이 FR 의 영속 대상이 아니다 (L6).

**S5 — 자동펼침은 더하기만 한다**
- Given 사용자가 INFRA 를 펼쳐 둔 상태다
- When `/projects/ATLAS/board` 로 이동한다
- Then ATLAS 가 **추가로** 펼쳐지고 **INFRA 는 펼쳐진 채 유지된다** (기존 동작은 INFRA 를 접었다)

**S6 — 검색 파라미터 경로에서도 트리가 활성 프로젝트를 표시한다 (선재 갭 해소)**
- Given 사용자의 활성 프로젝트가 MIDDLE 이다
- When `/issues?projectKey=MIDDLE` 로 진입한다
- Then 트리에서 MIDDLE 이 자동 펼침 + `aria-current="page"` 로 표시된다 (기존에는 전부 접힘)

**S7 — 사이드바 "내 작업"**
- Given 사용자의 활성 프로젝트가 INFRA 이고 로그인 사용자는 alice 다
- When 사이드바 "내 작업" 을 누른다
- Then INFRA 프로젝트에서 alice 가 담당자인 이슈 목록이 보인다

**S8 — 사이드바 "최근 항목"**
- Given 사용자가 `ATLAS-12` → `INFRA-3` 순으로 이슈를 열어봤다
- When 사이드바를 본다
- Then "최근 항목" 에 `INFRA-3`·`ATLAS-12` 가 MRU 순으로 제목과 함께 보인다

**S9 — 최근 항목의 자가 치유**
- Given 최근 목록에 `OLD-1` 이 있는데 그 이슈가 삭제됐거나 권한이 회수됐다
- When 사이드바를 연다
- Then `OLD-1` 은 조용히 목록에서 빠지고 나머지는 정상 표시된다 (사이드바 전체가 깨지지 않는다)

**S10 — 프로젝트 0개**
- Given 접근 가능한 프로젝트가 하나도 없다
- When 상단바를 본다
- Then 스위처는 비활성(또는 미렌더)이고 `/projects` 안내가 유지된다 (`ActiveProjectGate` 기존 동작)

## 3. 기능 요구사항 (FR)

| ID | 내용 |
|---|---|
| **FR1** | `hooks/use-recent-projects.ts` 신설 — 프로젝트 키 MRU 목록(상한 **5**, LRU 축출). localStorage `bts.recent-projects`. zustand + fail-safe 3중 폴백(SSR·스토리지 차단·파싱 실패)은 `use-active-project.ts:39-69` 템플릿 복제. **배열이 아니거나 원소가 문자열이 아니면 빈 목록으로 폴백** |
| **FR2** | `hooks/use-recent-issues.ts` 신설 — 이슈 키 MRU 목록(상한 **5**, LRU 축출). localStorage `bts.recent-issues`. **키 문자열만 저장한다 — 제목·본문·담당자 등 이슈 내용을 저장하지 않는다** (ADR §D4) |
| **FR3** | **기록 지점을 늘리지 않는다.** 프로젝트 기록은 기존 `hooks/use-track-active-project.ts` 를 확장한다 — `isKnownProject` 가드(접근 가능 목록 대조)를 **통과한 뒤에만** 최근 목록에 push. 새 기록 훅을 만들면 저장값 생산 지점이 둘이 되어 FR-UX-07 코드리뷰 CR3 가 걸었던 결함(가드가 한쪽에만 있음)이 재발한다 |
| **FR4** | 이슈 방문 기록 — `routes/issues.$key.tsx` 에서 **이슈 조회가 성공한 뒤에만** push. 조회 전에 기록하면 404·403 키가 목록을 오염시키고, 그 키는 다음 마운트에서 또 실패한다 |
| **FR5** | `hooks/use-project-tree-expanded.ts` 신설 — 펼침 집합 영속. 저장 형식은 `string[]`(JSON), 메모리 표현은 `Set<string>`. localStorage `bts.project-tree.expanded` |
| **FR6** | `ProjectTree` 자동펼침을 **더하기(`add`)로 변경**하고 **덮어쓰기(`new Set([key])`)를 폐지**한다. FR-UX-06 PR12 FR5 의 정정 (ADR §D2). JSDoc 의 *"이전 수동 펼침을 덮어쓴다"* · *"수동 펼침은 ephemeral 이며 영속하지 않는다"* 두 문장도 함께 정정 |
| **FR7** | `ProjectTree` 의 활성 프로젝트 소스를 `useParams({strict:false}).projectKey` → **`useResolvedActiveProject`** 로 교체한다. 선재 갭(S6) 동시 해소 |
| **FR8** | `components/project/ProjectSwitcher.tsx` 신설 — `components/ui/popover.tsx`(현재 소비처 0) + `role="listbox"` / `role="option"`. **`<nav>` 로 만들지 않는다** (ADR §D5). 기존 `role="listbox"` 소비처 5곳(`SenderAutocomplete.tsx:237` · `LabelAutocompleteInput.tsx:167` · `DashboardForm.tsx:167` · `MentionDropdown.tsx:55` · `UserMappingStep.tsx:82`)의 ARIA 관례를 따른다 |
| **FR9** | 스위처 배치 — `TopBar.tsx`, 로고와 검색 버튼 사이. 트리거는 현재 활성 프로젝트명을 표시한다 |
| **FR10** | 스위처 목록 = **최근 방문 그룹(MRU) + 나머지**. 나머지는 **백엔드 순서 그대로**(`ProjectQueryRepository.kt:62` `ORDER BY name ASC`). **프론트 재정렬 금지** 원칙(FR-UX-07 FR3 · `use-projects.ts:10`)은 "나머지" 구간에 그대로 적용되고, MRU 그룹은 재정렬이 아니라 **별도 구간 분리**다 |
| **FR11** | 스위처 선택 동작 — §1-B 분기. 경로 파라미터 있으면 같은 하위 경로로 치환 이동, 없으면 `setActiveProject` 만 |
| **FR12** | 사이드바 "내 작업" — `to='/issues' search={{ assignee: userId }}`. **`projectKey` 를 싣지 않는다**(§1-A). `useAuthUser()?.userId` 부재 시 항목을 렌더하지 않는다 |
| **FR13** | 사이드바 "최근 항목" — 최근 이슈 키 5건, 각 키의 제목을 마운트 시 조회해 `KEY 제목` 형태로 렌더. 조회 실패(403/404) 항목은 **조용히 숨긴다**. 목록이 비면 섹션 자체를 렌더하지 않는다 |
| **FR14** | `navLabels` 에 `myWork`(`'내 작업'`) · `recent`(`'최근 항목'`) 추가. `nav-labels.ts` 의 S3 주석에 "FR-UX-08 에서 추가됨" 근거를 남긴다 |
| **FR15** | **★ `nav-labels.test.ts` 를 전수 판별식으로 교체한다.** 현재 테스트는 계약 문자열 5개를 손으로 나열하고 대상은 `breadcrumb` 하나로 하드코딩돼 있어, **새 라벨을 추가해도 아무것도 검사하지 않는다**(`breadcrumb` 자신도 목록에 없다). `Object.entries(navLabels)` 를 런타임으로 훑어 **모든 쌍**에 대해 양방향 substring 을 검사하고, 실재하는 예외 1쌍(`projectNav '프로젝트'` ⊂ `projectViewNav '프로젝트 뷰 전환'`)만 명시 화이트리스트로 둔다. 목록을 없애면 다음 라벨 추가가 자동으로 검사 대상이 된다 |
| **FR16** | **정본 전수 동기화** — `docs/plan/product/personalization.md` §4.6 D1~D7 `[x]` · §4.6 본문의 `?assignee=me` → `?assignee=<whoami.userId>` 표기 정정 · D1 "최근 프로젝트" 문구를 ADR §D1 재배치에 맞게 정정 · `docs/plan/README.md` §1 진척 열 · `docs/progress.html` 재생성 · `CHANGELOG.md`. **FR 총수 139 불변**(신설 아님). `verify-master-plan.sh` EXIT 0 |

**FR 아님 (명시적 비-요구).**
- `routes/issues.index.tsx` **무변경** — `search.assignee` 소비가 이미 있다(`:711,717`).
- `shortcuts.ts` · `commands.ts` **무변경** — 단축키·팔레트 계약을 건드리지 않는다.
- cross-project "내 작업" — 로드맵 B3, 범위 밖 (ADR §D5 / FR-UX-07 §D5).

## 4. 비기능 요구사항 (NFR)

| ID | 내용 | 측정 |
|---|---|---|
| **NFR1** | localStorage 3키는 **UI 선호값 또는 키 참조**만 담는다. **이슈 제목·본문 등 업무 내용을 저장하지 않는다.** 근거 KDoc 필수 (기존 6곳 전부 화면 설정값이라는 선례) | 코드 리뷰 + `bts.recent-issues` 값에 이슈 키 형태(`^[A-Z][A-Z0-9]*-\d+$`)만 있는지 단언 |
| **NFR2** | localStorage 접근 불가(시크릿 모드·차단)·변조 값에서도 앱이 정상 동작한다 | 유닛 — `getItem` throw 주입 · 배열 아닌 값 · 원소가 객체인 배열 |
| **NFR3** | 기존 e2e 계약 무위반 — nav `aria-label` 계약 문자열 · `getByRole('navigation')` 개수 불변 · `<h1>` 단일 · `QUICK_LINKS` 순서 · `SHORTCUTS` 5종 | `shortcuts.test.ts` **무수정** green + `project-tree.spec.ts`·`command-palette.spec.ts` green |
| **NFR4** | 백엔드 변경 0 · Flyway 마이그레이션 0 · 신규 npm 의존성 0 | `git diff --stat` 에 `backend/` 0건 · `package.json` diff 0 |
| **NFR5** | 사이드바 마운트 시 최근 이슈 조회는 **최대 5건**이고, 실패는 사이드바 전체를 차단하지 않는다 | 유닛 — 5건 중 2건 403 주입 시 나머지 3건 렌더 + 사이드바 생존 |
| **NFR6** | 스위처는 키보드만으로 완전 조작 가능하다 (열기·↑↓ 이동·Enter 선택·Esc 닫기) | 유닛 — `userEvent.keyboard` |
| **NFR7** | 트리 펼침 영속은 **프로젝트 수에 무관하게** 상한을 두지 않는다 (사용자가 접을 수 있고 그 접기가 유지되므로) | 명시적 비-요구 |

## 5. API 인터페이스

**신규·변경 없음.** 소비만 한다.

| API | 용도 | 기존 소비처 |
|---|---|---|
| `GET /api/v1/projects` | 스위처 목록 · 최근 프로젝트 유효성 대조 | `hooks/use-projects.ts` (staleTime 30s) — `ProjectTree`·`useTrackActiveProject` 가 이미 소비. **queryKey 공유라 요청이 늘지 않는다** |
| `GET /api/v1/issues/{key}` | 최근 항목 제목 조회 | `routes/issues.$key.tsx:186` `useQuery` + `fetchIssue` — 세션 중에는 캐시 적중 |
| `GET /api/v1/issues?projectKey=&assignee=` | "내 작업" 목록 | `routes/issues.index.tsx` 기존 경로. **변경 0** |

> **★ `?assignee=me` 는 실재하지 않는다.** `IssueFilterQueryParser.kt:42` 의 센티널은 `unassigned` 하나뿐이고 그 외 값은 `parseUuid` → 실패 시 **400**. `WhoamiResponse.userId`(`api/schemas.ts:22`)를 쓴다. 선례 `useGadgetData.ts:92-107` `assigneeIds: [userId]`.

## 6. 데이터 모델 변경

**없음.** 테이블 0 · Flyway 마이그레이션 0 · jOOQ 재생성 0.

클라이언트 저장소만 — localStorage 키 **3종 신설**.

| 키 | 값 | 상한 |
|---|---|---|
| `bts.recent-projects` | 프로젝트 키 문자열 배열 (MRU 순) | 5 |
| `bts.recent-issues` | 이슈 키 문자열 배열 (MRU 순) | 5 |
| `bts.project-tree.expanded` | 펼침 노드 키 문자열 배열 | 없음 |

## 7. 엣지 케이스

| # | 상황 | 처리 |
|---|---|---|
| E1 | 최근 프로젝트에 접근 불가·삭제된 키가 남음 | 스위처 렌더 시 `useProjects()` 목록과 대조해 탈락. 저장값도 교정 |
| E2 | 최근 이슈 조회 403/404 | 조용히 숨기고 저장값에서 제거. **사이드바 전체를 막지 않는다** (`ProjectTree` 의 에러 시 `null` 반환 관례) |
| E3 | 최근 목록이 비어 있음 | "최근 항목" 섹션 자체를 렌더하지 않는다 (빈 섹션 헤더만 남기지 않음) |
| E4 | localStorage 에 배열 아닌 값·원소가 문자열 아님 (수동 변조) | 빈 목록으로 폴백 |
| E5 | 같은 프로젝트/이슈를 연속 방문 | 이미 맨 앞이면 write 생략 (`use-active-project.ts:105` 의 no-op 가드와 같은 근거) |
| E6 | 상한 초과 | 가장 오래된 항목 축출. **정확히 5를 유지한다** |
| E7 | **★ 스위처 선택이 되돌려짐** — 경로 파라미터가 있는 라우트에서 활성값만 바꾸면 URL①이 이기고 `useTrackActiveProject` 가 원래 키를 되기록한다 | §1-B 분기로 차단. **회귀 가드 필수** — `/projects/ATLAS/board` 에서 INFRA 선택 후 활성값이 INFRA 로 **유지**되는지 단언 |
| E8 | 사이드바 "이슈"와 "내 작업" 이 같은 `/issues` 라 둘 다 활성 표시 | `activeOptions={{ includeSearch: true }}` 로 search 까지 비교. 없으면 두 링크가 동시에 `.active` 가 된다 |
| E9 | `userId` 부재 (PAT 인증 등) | "내 작업" 항목 미렌더. 죽은 링크를 만들지 않는다 |
| E10 | 사이드바 접힘(64px 레일) | 새 항목도 기존 관례를 따른다 — 아이콘만 노출, 텍스트는 `sr-only`(DOM 유지, `getByRole('link',{name})` 계약 보존) |
| E11 | 스위처 목록에서 최근 그룹과 나머지가 중복 | 최근 그룹에 나온 키는 나머지 구간에서 제외 |
| E12 | 트리 펼침 키의 네임스페이스 | **충돌 없음 (실측 확정).** `expandedKeys`(`ProjectTree.tsx:362,404`)는 **프로젝트 키만** 담는다. 중첩그룹 펼침은 `ProjectTreeRow` 로컬 `useState`(`:304-305`)로 분리돼 있다. 저장 형식은 프로젝트 키 배열 그대로 |
| E13 | 영속된 펼침 키 중 접근 불가·삭제된 프로젝트가 있음 | 렌더 시 `useProjects()` 목록과 대조해 무시. 저장값은 다음 write 시 자연 정리 (별도 청소 로직 불필요) |

## 8. 제약 조건

- **`<nav>` 추가 금지** (ADR §D5). `navLabels.projectNav = '프로젝트'` 가 `projectViewNav = '프로젝트 뷰 전환'` 의 substring 이고 Playwright `getByRole` 은 기본 substring 매칭이다(`nav-labels.ts:23-26` 이 이미 경고). 스위처는 `role="listbox"`
- **신규 프리미티브 금지** — `components/ui/popover.tsx` 재사용. `dropdown-menu.tsx`(AccountMenu 소비)도 후보였으나 목록 선택 시맨틱에는 `listbox` 가 맞다
- **`hooks/use-projects.ts` 재사용** — 새 프로젝트 조회 훅 신설 금지
- **`shortcuts.ts` 무변경** — 건드리면 프론트 2단언 + 백엔드 `KeymapAction` enum + `user_keymap` CHECK 가 동시에 깨진다
- **`routes/issues.index.tsx` 무변경** — `search.assignee` 소비가 이미 있다
- **백엔드 무변경** · 마이그레이션 0 · 신규 의존성 0
- **`MAIN_NAV_LINKS` 는 `{ to, label, Icon }` 정적 배열이다.** "내 작업" 은 `search` 와 조건부 렌더(userId 유무)가 필요하므로 이 배열에 넣지 않고 별도 항목으로 렌더한다 — 배열 타입을 nullable·optional 로 넓혀 기존 3항목까지 복잡해지게 하지 않는다
- **e2e 는 localStorage 를 `page.addInitScript` 로 심는 관례를 따른다** (`active-project.spec.ts:46,65` 선례). `playwright.config.ts` 에 `storageState` 가 없어 테스트마다 새 컨텍스트 = localStorage 빈 상태로 시작한다 (기존 `project-tree.spec.ts` S4/S5 의 "나머지 전부 접힘" 단언이 D2 정정 후에도 통과하는 근거)

## 9. 측정 가능한 완료 기준

- [ ] S1~S10 시나리오가 유닛/e2e 로 커버된다
- [ ] **E7 회귀 가드** — `/projects/ATLAS/board` 에서 스위처로 INFRA 선택 후 활성값이 INFRA 로 유지된다 (되기록 차단 실증)
- [ ] **FR15 판별식이 비-공허하다** — `navLabels` 에 일부러 충돌 라벨(예 `'프로젝트 뷰'`)을 넣으면 새 테스트가 **실제로 red** 가 된다
- [ ] `grep -rn "assignee=me" apps/web/src docs/plan` → **0건**
- [ ] `ProjectTree.tsx` 에 `new Set([activeProjectKey])` 형태의 **덮어쓰기 0건**
- [ ] `ProjectTree.tsx` 가 `useResolvedActiveProject` 를 참조한다 (선재 갭 해소 실증)
- [ ] `bts.recent-issues` 저장값이 이슈 키 형태만 담는다 (제목 미저장 실증)
- [ ] `shortcuts.test.ts` **무수정** green
- [ ] `getByRole('navigation')` 개수가 기존과 같다 (스위처가 nav 를 늘리지 않음)
- [ ] `apps/web` typecheck 0 · eslint 0 error · 유닛 green (기준선 + 신규분, **XML 실측**)
- [ ] `bash scripts/verify-master-plan.sh` EXIT **0** (139/139)
- [ ] `git diff --stat` 에 `backend/` 파일 **0건** · `package.json` diff 0
- [ ] **브라우저 눈확인** — 스위처 전환 · 트리 펼침 유지 · "내 작업" · "최근 항목" (라이트/다크)

## 10. 알려진 한계 (이 PR 범위 밖, 명시적으로 남김)

| # | 한계 | 근거 / 후속 |
|---|---|---|
| L1 | **최근 목록에 사용자 스코프가 없다.** 로그아웃해도 남는다 (`authStore.ts:34-37` 이 `sessionStorage` 만 정리) | 이슈 **키만** 저장하므로 내용 유출은 없고, 다음 사용자에게는 조회가 403/404 로 떨어져 자동 탈락한다(E2). `bts.active-project` L4 와 동급 판정 |
| L2 | **최근 이슈 제목은 매 하드 리로드마다 최대 5건 조회한다.** 배치 조회 API 가 없다(`fetchIssue` 는 단건) | 세션 중에는 `issues.$key` 의 `useQuery` 캐시에 적중. 배치 API 신설은 백엔드 변경이라 범위 밖 |
| L3 | **cross-project "내 작업" 불가.** 활성 프로젝트 스코프만 | 로드맵 B3 — `PROJECTS.KEY.eq(projectKey)` 단일 축 하드코딩, 합집합 조립은 fail-open 위험 (FR-UX-07 ADR §D5) |
| L4 | **활성 프로젝트·최근 목록이 기기 간에 안 넘어간다** | 서버 `user_preferences` 확장은 FR-UX-07 D4 가 기각하고 후속 FR 후보로 남긴 것을 승계 |
| L5 | **"최근 항목" 이 이슈만 담는다.** 지라는 보드·필터·대시보드도 담는다 | v1 범위. 확장 시 `use-recent-issues` 를 타입 태그가 붙은 일반 목록으로 넓히면 되고 저장 형식만 바뀐다 |
| L6 | **중첩그룹("리포트"·"프로젝트 설정") 펼침은 영속되지 않는다.** 프로젝트 행이 접히면 `ProjectTreeRow` 가 언마운트돼 로컬 상태가 사라진다 | 정본이 지목한 F12 대상 라인(`ProjectTree.tsx:368-370`)이 **프로젝트 레벨 `expandedKeys`** 블록이다. 중첩그룹까지 넓히려면 로컬 상태를 상위로 리프팅 + 키 네임스페이스(`<projectKey>/<group>`) 도입이 필요해 범위가 커진다. 확장 시 저장 형식만 바뀌고 훅 인터페이스는 불변 |

## 11. PR 분할 (2026-07-30 Maxi 확정)

정본 §4.6 · ADR §D6 분할 매핑표가 적은 **승계 PR 2건**을 그대로 따른다. 두 그룹은 **변경 파일이 하나도 겹치지 않는다.**

| PR | 로드맵 | 범위 | 담당 FR |
|---|---|---|---|
| **PR-A** (#326, 본 작업) | **F12** | 프로젝트 스위처 + 트리 펼침 영속 + 선재 갭(트리가 활성 프로젝트 미참조) 해소 | FR1 · FR3 · FR5 · FR6 · FR7 · FR8 · FR9 · FR10 · FR11 |
| **PR-B** (후속) | **F17** | 사이드바 "내 작업" · "최근 항목" + nav 라벨 전수 판별식 | FR2 · FR4 · FR12 · FR13 · FR14 · FR15 |

**FR16(정본 전수 동기화)은 PR-B 소관이다.** FR-UX-08 의 D 마커는 **완주 단위**라 F12·F17 이 둘 다 끝나야 `[x]` 가 된다(ADR §D6 판별식). PR-A 는 **FR 카운트·D 마커·진척 열을 건드리지 않고**(139/139 불변, 진척 132 불변) `personalization.md` §4.6 에 PR 분할 계획과 ADR 링크만 추가한다.

**시나리오·엣지 케이스 귀속.**
- PR-A — S1·S2·S3·S4·S5·S6·S10 / E1·E5·E6·E7·E10·E11·E12·E13
- PR-B — S7·S8·S9 / E2·E3·E4·E8·E9

**PR-A 에서 적용되지 않는 제약.** §8 의 `MAIN_NAV_LINKS` 항목은 F17 소관이다.

## Brainstorming Check

✅ 통과 (1회 iteration). **`office-hours` 는 호출하지 않았다** — YC 아이디어 검증 도구라 FR 이 이미 정의되고 도메인이 확정된 작업엔 프레임이 안 맞는다(2026-05-29 Maxi 확정, FR-UX-07 스펙 §Brainstorming Check 선례 승계). `design-consultation`(DESIGN.md 517줄 실재)·`design-shotgun`(새 화면 아님 — 확립된 디자인 시스템 위의 컴포넌트 추가) 도 스킵.

Phase B 는 추상 브레인스톰 대신 **스펙의 사실 주장을 코드로 되짚는 방식**으로 수행했다. 결과 **5건, 그중 갭 3건**.

| # | 항목 | 결과 |
|---|---|---|
| **G1** | 정본의 `?assignee=me` 가 실재하는가 | **갭 → 해소.** `IssueFilterQueryParser.kt:42` 실측 — 센티널은 `unassigned` 뿐, 그 외는 `parseUuid` 실패 시 **400**. 정본대로 구현했으면 사이드바에 400 나는 링크를 박았다. `?assignee=<whoami.userId>` 로 교체(§5), 정본 문구도 FR16 에서 정정 |
| **G2** | **★ `nav-labels.test.ts` 가 새 라벨을 검사하는가** | **갭 → 해소.** 검사 **안 한다**. `contractLabels` 5개가 손으로 나열돼 있고 대상은 `navLabels.breadcrumb` 하나로 하드코딩 — `breadcrumb` 자신도 목록에 없다. 「두 목록이 서로를 안 본다」 양식. FR15 에서 **목록을 없애는** 전수 판별식으로 교체 |
| **G3** | **★ 스위처 선택이 되돌려지는가** | **갭 → 해소.** 경로 파라미터가 있는 라우트에서 활성값만 바꾸면 해소 ①(URL)이 이기고 `useTrackActiveProject:50` 이 원래 키를 **되기록**한다. §1-B 분기로 차단, E7 에 회귀 가드 |
| **G4** | D2 정정이 기존 테스트를 깨는가 | **깨지지 않는다.** `ProjectTree.test.tsx:186,199,211` 과 e2e `project-tree.spec.ts:128,148`(S4/S5)은 전부 **초기 상태**(펼침 영속 없음) 기준이라 통과한다. `playwright.config.ts` 에 `storageState` 가 없어 테스트마다 localStorage 가 비어 있다. **"라우트 이동이 이전 펼침을 리셋한다" 를 직접 단언하는 테스트는 0건** — FR5 의 덮어쓰기는 JSDoc 에만 있고 테스트로 봉인돼 있지 않다 |
| **G5** | `/issues` 가 `search.assignee` 를 실제로 소비하는가 | **확증.** `routes/issues.index.tsx:711,717` → `searchToIssueFilter` → `IssueListPage.filter`. §1-A 설계 B 가 **라우트 무변경**으로 성립한다 |

**한계 — 독립 리뷰는 미실시.** 위 5건은 내가 쓴 스펙을 내가 되짚은 것이다. 이 저장소의 최근 이력에서 **독립 리뷰가 컨트롤러가 만든 BLOCKER 를 반복 적발**했으므로(#317 4연속 · #314 2회 · #322 plan-eng-review 가 계획의 자기모순 적발), `bts-review-plan` 과 게이트 2 의 `bts-codereview` 가 실질 안전망이다.
