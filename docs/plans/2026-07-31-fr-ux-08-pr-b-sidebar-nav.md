<!-- FR-UX-08 PR-B (F17) 사이드바 "내 작업"·"최근 항목" 워크플로우 진행 기록 -->
# FR-UX-08 PR-B — 사이드바 "내 작업"·"최근 항목" + nav 라벨 전수 판별식 (F17)

> slug: fr-ux-08-pr-b-sidebar-nav
> type: ui
> agent: frontend-engineer
> primary_bc: issue-tracking (프론트 `apps/web/**` 전용)
> 생성: 2026-07-31

## Brief

FR-UX-08 을 완주시키는 후속 PR. PR-A(#326, F12 프로젝트 스위처 + 트리 펼침 영속)가 머지돼
코드 파일 교집합 0 이 확보됐으므로 착수 가능해졌다.

**범위 정본** — `docs/specs/2026-07-30-fr-ux-08-project-switcher.md` §11 PR-B 행.

| 축 | 값 |
|---|---|
| 로드맵 | F17 |
| 담당 FR | FR2 · FR4 · FR12 · FR13 · FR14 · FR15 · **FR16(정본 전수 동기화)** |
| 시나리오 | S7 · S8 · S9 |
| 엣지 케이스 | E2 · E3 · E4 · E8 · E9 |
| 제약 | §8 `MAIN_NAV_LINKS` 항목이 이 PR 부터 적용 |
| plan 원안 | T2 · T4 · T6 · T9 (PR-A plan 에서 이미 분해) |

**착수 전 확정 전제 3건 (PR-A 에서 실측으로 뒤집힌 것).**

1. ★ `?assignee=me` 는 **실재하지 않는다**. `IssueFilterQueryParser` 센티널은 `unassigned` 뿐이고
   그 외 값은 UUID 파싱 실패 시 **400**. → `?assignee=<whoami.userId>`.
2. ★ "최근 항목" = 최근 본 **이슈** (프로젝트 아님). 사이드바에 `ProjectTree` 전체 목록이 이미 있어 중복.
3. ★ FR16 이 PR-B 소관 → D 마커 `[x]` + 진척 132→133 + `verify-master-plan.sh` EXIT0 이 머지 조건.

classify 결과. `type=ui` / `agent=frontend-engineer` / `slug=fr-ux-08-pr-b-nav-f17-ui`
(브랜치 접두사가 이미 `ui/` 라 슬러그 말미 `-ui` 중복을 제거해 `fr-ux-08-pr-b-sidebar-nav` 로 사용).

## 도메인 정리

- **논리 BC. personalization / 물리. `apps/web`** — ADR §D6 승계 (FR-UX-05 D4 · FR-UX-06 D5 · FR-UX-07 D2).
  `classify-task.ts` 는 `primary_bc=issue-tracking` 으로 분류했으나 변경 파일이 전량 `apps/web`,
  `backend/**` 0건이라 PR-A 와 동일하게 정정한다.
- **영향 엔티티.** 신규 0. 프론트 전용 개념 2종(아래 용어)만 추가.
- **기존 결정 충돌.** 없음. 이 PR 이 다루는 D1·D3·D4 는 ADR `2026-07-30-fr-ux-08-project-switcher.md`
  에서 이미 확정됐고 PR-B 는 그 **하위집합**이다.
- **관련 ADR.** [2026-07-30-fr-ux-08-project-switcher](../decisions/2026-07-30-fr-ux-08-project-switcher.md)
  (§D1 최근 항목=이슈 · §D3 상한5·MRU·방문 시 자동기록 · §D4 키만 저장 · §D6 BC) ·
  [2026-07-28-fr-ux-07-active-project-context](../decisions/2026-07-28-fr-ux-07-active-project-context.md) ·
  [2026-07-17-fr-ux-06-jira-redesign](../decisions/2026-07-17-fr-ux-06-jira-redesign.md) (nav 라벨·S3 도입)
- **신규 ADR.** 없음. PR-A 의 ADR 이 PR-B 결정을 이미 담고 있어 새로 만들면 정본이 둘이 된다.

### grill-with-docs 미호출 (사유 명시)

이 단계의 정규 절차는 `grill-with-docs` 호출이나 **호출하지 않았다.** ADR 이 착수 조사 4개 질문
(최근 항목의 대상 · 트리 펼침 충돌 · 상한/정렬/기록시점 · 저장 범위)을 D1~D5 로 전부 닫았고,
PR-B 에서 **새로 열리는 도메인 질문이 0건**이다. 이미 확정된 결정을 재심하는 것은
「이전 세션의 정착된 결정을 조용히 재litigate 하지 않는다」에 어긋난다.
대신 아래 **실측 3건**으로 도메인 전제가 코드와 여전히 일치하는지 확인했다.

### ★실측 3건 (PR-B 착수 전제 검증)

**M1. `navLabels` S3 회귀 가드가 이 PR 로 반쯤 뒤집힌다 — 절반만 지우면 봉인이 깨진다.**

`apps/web/src/i18n/__tests__/nav-labels.test.ts:55-71` 의 `S3 — 백킹 없는 항목 제외 회귀 가드` 가
**4개 키의 부재**를 단언한다 — `myWork` · `recent` · `filters` · `projects`.
PR-B 는 이 중 **`myWork`·`recent` 두 개를 추가**한다 (ADR §충돌표 4행이 *"충돌 아님 — 이 FR 이 그 추가 시점"* 으로 이미 승인).
남은 **`filters`·`projects` 두 개는 계속 부재여야 한다** (백킹 라우트 없음).

⇒ **describe 블록째 삭제는 오답.** 그러면 `filters`·`projects` 가 가드를 잃는다 —
메모리 「봉인은 절반만 닫힌다」의 정확한 재현. **부분 반전**(2개는 존재 단언으로 전환, 2개는 부재 유지)이 맞고,
그 위에 이번 작업의 목표인 **목록 제거형 전수 판별식**을 얹는다.

**M2. `nav-labels` 테스트 파일이 두 벌이다 (선재).**

| 파일 | 줄수 | 범위 | 출처 |
|---|---|---|---|
| `apps/web/src/i18n/__tests__/nav-labels.test.ts` | 72 | e2e 계약 4종 고정 + 라벨 존재 7종 + **S3 제외 가드 4종** | FR-UX-06 PR11 |
| `apps/web/src/i18n/nav-labels.test.ts` | 27 | `breadcrumb` 신규 키 + substring 비충돌 | FR-UX-06 PR13 |

같은 대상을 두 파일이 나눠 보고 있어 **"어느 쪽에 전수 판별식을 두는가"** 가 plan 단계 결정 사항이다.
한쪽에만 두면 다른 쪽을 고치는 사람이 판별식을 못 본다(「두 목록이 서로를 안 본다」 양식).
**이 PR 이 만든 상황이 아니라 선재 상태**임을 명시한다.

**M3. glossary 신규 용어 2종이 아직 미등재다.**

ADR §신규 용어 표가 **최근 프로젝트**(Recent Projects) · **최근 본 이슈**(Recent Issues) 를
glossary 등재 대상으로 지정했으나, `Maxi_wiki/BTS/glossary.md` 실측 결과 **둘 다 없다**
(PR-A 가 「최근 프로젝트」를 스위처 정렬 축으로 실제 구현했는데도 미등재).
glossary 는 `_index.md` §동기화 규칙상 **수동 영역**(자동 갱신 안 함)이라
**Maxi 승인이 필요** → 게이트 1 안건으로 올린다.



## 스펙

전체 스펙. [docs/specs/2026-07-30-fr-ux-08-project-switcher.md](../specs/2026-07-30-fr-ux-08-project-switcher.md)
— **신규 작성이 아니라 기존 정본에 PR-B 공백을 보강**했다 (아래 G1~G4).

핵심 시나리오 3줄 요약.
- **S7** 사이드바 "내 작업" → 활성 프로젝트에서 내가 담당인 이슈 목록
- **S8** 사이드바 "최근 항목" → 최근 본 이슈 5건이 MRU 순으로 제목과 함께
- **S9** 삭제·권한회수된 이슈는 조용히 목록에서 탈락하고 사이드바는 살아 있다

### Phase A — office-hours / design-* 미호출 (사유 명시)

- **design-consultation 스킵.** `DESIGN.md` 실재(프로젝트 첫 UI 작업 아님).
- **design-shotgun 스킵.** 새 화면이 아니라 **확립된 디자인 시스템 위의 링크 2종 추가**.
  스펙 §Brainstorming Check 이 PR-A 에서 같은 사유로 이미 스킵을 기록했고 PR-B 도 동일 조건.
- **office-hours 스킵.** 스펙 정본이 이미 존재하고 FR 이 확정돼 있다. YC 아이디어 검증 프레임이
  맞지 않는다(2026-05-29 Maxi 확정 · FR-UX-07 선례 승계, 스펙 §Brainstorming Check 에 명문화).
- 대신 이 단계가 한 일은 **PR-B 범위 추출 + 실측 기반 공백 보강**이다.

### ❓ Brainstorming 발견 — 스펙 공백 4건 (전량 보강 완료)

**G1 (BLOCKER 급). S3 제외 회귀 가드가 스펙 어디에도 없었다.**
FR14 는 `nav-labels.ts` **주석**만 고치라 했고, FR15 는 `i18n/nav-labels.test.ts` 만 교체 대상으로 지목했다.
그런데 `myWork`·`recent` 추가로 **실제 red 가 되는 파일은 `i18n/__tests__/nav-labels.test.ts:55-71`** 이고
스펙에 단 한 번도 등장하지 않는다. 구현자가 "테스트가 깨졌으니 지운다" 로 가면
**`filters`·`projects` 가드가 함께 소실**된다.
⇒ **FR14-b 신설** — 부분 반전(2건 존재 단언 전환 / 2건 부재 유지) + 근거 주석 + 양방향 실증.

**G2. FR15 판별식의 거처가 미지정 + nav 라벨 테스트가 3벌인 사실이 스펙에 없었다.**
① `i18n/nav-labels.test.ts` 27줄 ② `i18n/__tests__/nav-labels.test.ts` 72줄 ③ `layout/__tests__/navigation-contract.test.tsx` 167줄.
①②는 **같은 상수를 대상으로 하면서 서로를 참조하지 않는다**.
⇒ **FR15-b 신설** — ①을 ②로 흡수해 상수 테스트를 한 파일로 통일, 판별식을 거기 둔다. ③은 렌더 계약이라 유지.

**G3. 두 신규 항목의 「배치」가 미지정이었다.**
FR13 이 "섹션" 이라 부르는데, 새 `<nav>` 를 만들면 ADR §D5 · NFR3 · `navigation-contract.test.tsx:60`
aria-label 4종 가드가 **동시에** 깨진다.
⇒ **FR13-b 신설** — 기존 `메인 메뉴` `<nav>` 안에 렌더. 근거는 실측 선례
(`Sidebar.tsx:93-101` 이 이미 `MAIN_NAV_LINKS` 3링크 + `<FavoritesMenu/>` 를 같은 nav 안에 담고 있다).

**G4. §9 완료 기준이 PR-A/PR-B 혼재라 이 PR 의 게이트로 쓸 수 없었다.**
E7 회귀가드 · `ProjectTree` 덮어쓰기 0건 · `useResolvedActiveProject` 참조는 전부 PR-A 소관(완료).
⇒ **§11-B PR-B 전용 완료 기준 신설** (14항목).

**G5(부수). glossary 등재 2종 미이행** → **FR16-b 신설**. 수동 영역이라 **Maxi 승인 필요 → 게이트 1 안건**.

## Brainstorming Check

✅ 통과 (1회 iteration). 발견 gap 5건 전량을 **스펙 정본 보강**으로 해소했고
(FR13-b · FR14-b · FR15-b · FR16-b · §11-B), Maxi 결정이 필요한 1건(G5 glossary 등재)만
게이트 1 안건으로 남겼다. Phase A↔B 루프 재진입 없음.

## Plan

### 실측으로 확정한 관례 (task 가 이 경로를 그대로 쓴다)

| 축 | 실측값 |
|---|---|
| 훅 테스트 | `src/hooks/__tests__/<name>.test.ts` |
| 라우트 테스트 | `src/routes/__tests__/<name>.test.tsx` |
| 컴포넌트 테스트 | `src/components/<dir>/__tests__/<Name>.test.tsx` |
| e2e | `apps/web/e2e/<name>.spec.ts` |
| 별도 항목을 nav 안에 렌더한 선례 | `Sidebar.tsx:18` import + `:100` `<FavoritesMenu />` (`components/favorite/`) |
| MRU 훅 템플릿 | `hooks/use-recent-projects.ts` (PR-A 산출물, 145줄 — fail-safe 3중 + 복원 중복제거 CR2 반영본) |

---

### Task 1. `use-recent-issues` MRU 스토어 신설

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/hooks/use-recent-issues.ts`, `apps/web/src/hooks/__tests__/use-recent-issues.test.ts`]
- depends-on: []

**RED**. `use-recent-issues.test.ts` 신설.
- `pushRecentIssue('ATLAS-12')` → 목록 맨 앞
- **중복 push 시 맨 앞으로 이동하고 길이는 그대로** (E5 — 이미 맨 앞이면 write 생략)
- **상한 5 초과 시 가장 오래된 항목 축출, 정확히 5 유지** (E6)
- **변조 저장값 폴백 3종** (E4/NFR2) — ① 배열 아님 `{"a":1}` ② 원소가 객체 `[{}]` ③ `getItem` throw 주입
- **복원 경로 중복 제거** — `["A-1","A-1","B-2"]` 저장 후 초기화 시 `["A-1","B-2"]` (PR-A CR2 동형 결함 선차단)
- ⚠️ **`beforeEach` 스토어 리셋 필수** — zustand 스토어는 모듈 전역 싱글턴이라 리셋이 없으면
  **거짓통과·거짓실패가 둘 다** 가능하다 (PR-A plan 리뷰 BLOCKER B2 의 정확한 재발 지점)

**GREEN**. `use-recent-issues.ts` — `use-recent-projects.ts` 를 템플릿으로 복제.
`RECENT_ISSUES_STORAGE_KEY = 'bts.recent-issues'` · `MAX_RECENT_ISSUES = 5`.

**REFACTOR**. KDoc 에 **NFR1 근거**를 명시 — *"이슈 **키 문자열만** 저장한다. 제목·본문·담당자 등
업무 내용을 저장하지 않는다"* + 근거(로그아웃이 `localStorage` 를 지우지 않음, `authStore.ts:34-37`).

**검증**. `pnpm vitest run src/hooks/__tests__/use-recent-issues.test.ts`

---

### Task 2. 이슈 방문 기록 배선 (조회 성공 후에만)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/routes/issues.$key.tsx`, `apps/web/src/routes/__tests__/issues.$key.recent.test.tsx`]
- depends-on: [1]

**RED**. 라우트 테스트 신설.
- 이슈 조회 **성공** 시 `bts.recent-issues` 에 해당 키가 기록된다
- ★ **404/403 응답 시 기록되지 않는다** (FR4) — 조회 전에 기록하면 죽은 키가 목록을 오염시키고
  그 키는 다음 마운트에서 또 실패한다
- ★ **키 리다이렉트**(`IssueRedirectError`, `:195`) 시 **새 키**가 기록되고 옛 키는 기록되지 않는다

**GREEN**. `issues.$key.tsx` 의 `useQuery`(`:182-186`) 결과에 `useEffect` 를 걸어
`data` 가 truthy 일 때만 `pushRecentIssue(issue.key)`.

**REFACTOR**. 기록 지점이 **이 한 곳뿐**임을 주석으로 못박는다 (FR3 의 동형 원칙 — 생산 지점이
둘이 되면 가드가 한쪽에만 붙는 FR-UX-07 CR3 결함이 재발한다).

**검증**. `pnpm vitest run src/routes/__tests__/issues.$key.recent.test.tsx`

---

### Task 3. `navLabels` 2키 추가 + S3 가드 **부분 반전**

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/i18n/nav-labels.ts`, `apps/web/src/i18n/__tests__/nav-labels.test.ts`]
- depends-on: []

**RED**. `__tests__/nav-labels.test.ts:55-71` 의 `S3 — 백킹 없는 항목 제외 회귀 가드` 를 **부분 반전**.
- `myWork` 키가 **존재하고** 값이 `'내 작업'` 이다 ← 반전
- `recent` 키가 **존재하고** 값이 `'최근 항목'` 이다 ← 반전
- `filters` 키가 **없다** ← **유지**
- `projects` 키가 **없다** ← **유지**
- describe 제목을 `S3 — 백킹 유무에 따른 항목 게이팅 (FR-UX-08 에서 2건 반전)` 으로 갱신

**GREEN**. `nav-labels.ts` 에 `myWork: '내 작업'` · `recent: '최근 항목'` 추가.

**REFACTOR**. `nav-labels.ts:9` S3 주석에 **왜 2건만 뒤집었는지**를 남긴다 —
*"`myWork`·`recent` 는 FR-UX-08 이 실 라우트를 부여해 추가됨. `filters`·`projects` 는 백킹 없음 —
가드 유지"*. 새 라벨 2종은 e2e 계약 문자열이 아니므로 🔒 표시를 붙이지 않는다.

**★ 이 task 의 실패 양식.** describe 블록을 통째로 지우는 것. 그러면 `filters`·`projects` 가
가드를 잃는다 — 「봉인은 절반만 닫힌다」. **양방향 실증**이 완료 조건이다
(2건 지우면 red / 2건 추가하면 red, **둘 다** 확인).

**검증**. `pnpm vitest run src/i18n/__tests__/nav-labels.test.ts`

---

### Task 4. nav 라벨 **전수 판별식** + 테스트 파일 통합

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/i18n/__tests__/nav-labels.test.ts`, `apps/web/src/i18n/nav-labels.test.ts`(삭제)]
- depends-on: [3]

**RED**. `__tests__/nav-labels.test.ts` 에 **목록 제거형** 판별식 추가.
- `Object.entries(navLabels)` 를 **런타임으로 훑어** 모든 쌍의 양방향 substring 을 검사
- 실재 예외 **1쌍만** 화이트리스트 — `['projectNav','projectViewNav']`
- **비-공허 짝** — 수집된 쌍이 0건이면 실패시킨다 (라벨이 사라져도 조용히 통과하지 않게)
- **화이트리스트 짝 검사** — 화이트리스트에 적힌 쌍이 **실제로 substring 관계가 아니면** 실패
  (짝을 잘못 적으면 파생이 틀린 쌍을 면제하며 통과한다 — #323 M9 선례)
- 흡수 대상 — 삭제될 `i18n/nav-labels.test.ts` 의 `breadcrumb` 비충돌 단언이
  전수 판별식에 **포함되는지** 확인하는 단언 1건

**GREEN**. 판별식 구현 + `apps/web/src/i18n/nav-labels.test.ts` **삭제**.

**REFACTOR**. 파일 L1 주석에 *"nav 라벨 상수 단위 테스트의 단일 거처. 렌더 계약은
`layout/__tests__/navigation-contract.test.tsx` 가 따로 본다"* 를 명시해 재분열을 막는다.

**★ 비-공허 실증(완료 조건).** `navLabels` 에 일부러 `'프로젝트 뷰'` 를 넣으면 **실제 red**.

**검증**. `pnpm vitest run src/i18n/`

---

### Task 5. 사이드바 "내 작업" 링크

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/layout/Sidebar.tsx`, `apps/web/src/components/layout/__tests__/Sidebar.test.tsx`]
- depends-on: [3]

**RED**. `Sidebar.test.tsx` 에 추가.
- `userId` 존재 시 `내 작업` 링크가 렌더되고 `href` 가 `/issues?assignee=<userId>` 다 (FR12)
- ★ **`projectKey` 를 싣지 않는다** (§1-A) — `href` 에 `projectKey` 부재 단언
- ★ **`userId` 부재 시 미렌더** (E9) — 죽은 링크를 만들지 않는다
- ★ **"이슈" 와 "내 작업" 이 동시에 활성 표시되지 않는다** (E8) — `activeOptions={{ includeSearch: true }}`
- ★ **접힘(64px) 시 텍스트가 `sr-only` 로 DOM 에 남는다** (E10) — `getByRole('link',{name})` 계약 보존
- ★ **`getByRole('navigation')` 개수 불변** (FR13-b/NFR3)

- ★ **위치가 `MAIN_NAV_LINKS` 3링크보다 앞이다** (§8-A D-A) — 렌더 순서 단언

**GREEN**. `MAIN_NAV_LINKS` 배열 **밖**, 기존 `메인 메뉴` `<nav>` **안 최상단**에 조건부 렌더
(§8 제약 — 배열을 nullable/optional 로 넓혀 기존 3항목까지 복잡해지게 하지 않는다).
아이콘 **lucide `UserCheck`** + `NAV_ICON_CLASS`, 클래스 `NAV_LINK_CLASS` (§8-A D-C).

**REFACTOR**. 왜 배열 밖인지 + 왜 최상단인지 1줄씩 주석 (§8-A D-A 링크).

**검증**. `pnpm vitest run src/components/layout/__tests__/Sidebar.test.tsx src/components/layout/__tests__/navigation-contract.test.tsx`

---

### Task 6. 사이드바 "최근 항목" 섹션

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/issue/RecentIssuesMenu.tsx`, `apps/web/src/components/issue/__tests__/RecentIssuesMenu.test.tsx`, `apps/web/src/components/layout/Sidebar.tsx`]
- depends-on: [1, 3, 5]

**RED**. `RecentIssuesMenu.test.tsx` 신설.
- 최근 키 5건의 제목을 조회해 `KEY 제목` 으로 MRU 순 렌더 (FR13/S8)
- ★ **403/404 항목은 조용히 숨기고 나머지는 렌더** (E2/S9/NFR5) — 5건 중 2건 403 주입 시 3건 렌더 + 사이드바 생존
- ★ **목록이 비면 섹션 자체 미렌더** (E3) — 빈 헤더만 남기지 않는다
- ★ **조회는 최대 5건** (NFR5)
- ★ **새 `<nav>` 를 만들지 않는다** (FR13-b) — `<ul aria-label={navLabels.recent}>`, `navigation` 개수 불변
- ★ **접힘(`collapsed=true`) 시 섹션 전체 미렌더** (§8-A D-B) — 헤더도 링크도 DOM 에 없다
- ★ **전체 settle 전에는 아무것도 렌더하지 않는다** (§8-A D-D) — 헤더 플리커 방지.
  `useQueries` 중 하나라도 `pending` 이면 `null`. 조회는 `retry: false`
- ★ **하위 링크에 아이콘이 없다** (§8-A D-C) — 아이콘 요소 부재 단언

**GREEN**. 별도 컴포넌트 `RecentIssuesMenu` 를 만들고 `Sidebar.tsx` 의 `메인 메뉴` nav
**최하단**(`<FavoritesMenu />` **다음**)에 배치 (§8-A D-A).
그룹 헤더는 관리 메뉴 헤더와 동일 타이포, 하위 링크는 `TREE_SUB_LINK_CLASS` 관례 (§8-A D-C).

**REFACTOR**. 제목 조회는 `issues.$key.tsx` 와 **같은 `queryKey`**(`issueQueryKey`)를 써
세션 중 캐시에 적중하게 한다 (L2 완화). 실패는 `null` 반환 (`ProjectTree` fail-safe 관례).
`FavoritesMenu.FilterFavoritesGroup:120-169` 이 **같은 문제(비동기 이름 조회 + 404 숨김 +
헤더 플리커)를 이미 푼 구현**이므로 구조를 그대로 참조한다 — 단, 그쪽은 드롭다운이고
이쪽은 인라인 목록이라 **컨테이너·타이포는 사이드바 관례**를 쓴다.

**검증**. `pnpm vitest run src/components/issue/__tests__/RecentIssuesMenu.test.tsx src/components/layout/`

---

### Task 7. e2e — S7·S8·S9

**메타**.
- agent: `qa-engineer`
- files: [`apps/web/e2e/sidebar-my-work-recent.spec.ts`]
- depends-on: [5, 6]

**RED→GREEN**. 3 시나리오.
- **S7** "내 작업" 클릭 → 담당 이슈 목록 도착
- **S8** 이슈 2건을 순서대로 열람 → 사이드바 "최근 항목"이 MRU 순
- **S9** 최근 목록에 죽은 키를 심고 → 그 항목만 빠지고 사이드바 생존

**★ e2e 함정 2건 (PR-A 실측, 그대로 적용).**
- `page.addInitScript` 는 이후 **모든 `goto` 에 재적용**된다. 1회성 초기화에 쓰면 왕복마다
  값을 지워 **"영속 안 됨" 거짓 실패**가 난다 → 1회성은 `page.evaluate`.
  (단 **시작 상태 심기**는 `active-project.spec.ts:46,65` 선례대로 `addInitScript` 가 맞다)
- 실패 시 **2단계 분류** — 전체 1차 → 재실행 2차. 대상이 바뀌면 flaky, 고정이면
  **내 변경 한 줄을 제거해 재실행**해 PRE_EXISTING 여부를 인과로 가른다.

**검증**. `apps/web/node_modules/.bin/playwright test e2e/sidebar-my-work-recent.spec.ts`
(필터 인자 삼킴 회피 — 바이너리 직접 호출)

---

### Task 8. FR16 정본 전수 동기화 + D 마커 + 진척 132→133

**메타**.
- agent: `frontend-engineer`
- files: [`docs/plan/product/personalization.md`, `docs/plan/README.md`, `docs/progress.html`, `CHANGELOG.md`, `docs/specs/2026-07-30-fr-ux-08-project-switcher.md`]
- depends-on: [1, 2, 3, 4, 5, 6, 7]

**작업** (TDD 대상 아님 — 문서. 검증은 `verify-master-plan.sh` 가 대신한다).
- `personalization.md` §4.6 **D1~D7 `[x]`** (F12+F17 완주 — ADR §D6 판별식)
- §4.6 본문 `?assignee=me` → `?assignee=<whoami.userId>` 표기 정정
- §4.6 D1 "최근 프로젝트" 문구를 ADR §D1 재배치에 맞게 정정
- `docs/plan/README.md` §1 진척 열 **132 → 133**
- `node scripts/build-dashboard.mjs` 로 `docs/progress.html` 재생성
- `CHANGELOG.md` `[Unreleased]` **범위/상태/BC 요약 표**
- **FR 총수 139 불변** (신설 아님)

**★FR16-b — glossary 등재 2종 (2026-07-31 Maxi 게이트 1 승인).**
`Maxi_wiki/BTS/glossary.md` 는 `_index.md` §동기화 규칙상 **수동 영역**이라 자동 미러 대상이 아니다.
승인됐으므로 **`/bts-merge` 의 Obsidian 동기화 단계에서 직접 추가**한다 — 정의 원문은
ADR `2026-07-30-fr-ux-08-project-switcher.md` §신규 용어 표 그대로.
- **최근 프로젝트**(Recent Projects) — 스위처 **정렬 축 전용**, 사이드바 미노출
- **최근 본 이슈**(Recent Issues) — 사이드바 "최근 항목"의 데이터 소스, **키만 영속**

**검증**. `bash scripts/verify-master-plan.sh` EXIT **0** ·
`grep -rn "assignee=me" apps/web/src docs/plan` **0건** ·
머지 후 `grep -c "최근 프로젝트\|최근 본 이슈" Maxi_wiki/BTS/glossary.md` **≥2**

---

## Plan 메타

- **task 수**. 8
- **wave 예상**. 3 — W1{T1, T3} · W2{T2, T4, T5} · W3{T6} → T7 → T8
  (`Sidebar.tsx` 를 T5·T6 이 공유하므로 자동 직렬화. `i18n/__tests__/nav-labels.test.ts` 를
  T3·T4 가 공유하므로 직렬)
- **TDD 강제**. yes (T8 문서 제외 — `verify-master-plan.sh` 가 대체 검증)
- **추가 검증**. typecheck · eslint · vitest(**XML 실측 + 이번 실행 산출물 확인**) · playwright
- **뮤테이션 필수 3종** (커밋 후 기준선에서만 — 미커밋 상태에서 돌리면 원복이 수정을 지운다).
  M1 T3 부분 반전 **양방향** · M2 T4 판별식 비-공허 · M3 T6 403 숨김
- **백엔드 0줄 · 마이그레이션 0 · 신규 의존성 0**

## 리뷰 결과

### plan-design-review (2026-07-31)

`classify.type == "ui"` → 리뷰 체인은 `/plan-design-review` 단독 (bts-review-plan 분기표).

**초기 6/10 → 확정 9/10.** 확정 결정 3건은 스펙 §8-A 에 정본으로 기록했다.

| 패스 | 전 | 후 | 내용 |
|---|---|---|---|
| 1 정보 위계 | 5 | **10** | **D-A** 내 작업 = 메인 메뉴 최상단 / 최근 항목 = 최하단 |
| 2 상태 커버리지 | 6 | **9** | 로딩 미지정 → 선례 강제(전체 settle 전 미렌더, `retry:false`) |
| 3 사용자 여정 | 8 | **8** | **지적 철회** — ADR §D3 자동 기록이 이미 해결 |
| 4 AI 슬롭 위험 | 9 | **9** | 앱 UI · 하드리젝션 7종 해당 없음 · 지적 0 |
| 5 디자인 시스템 정합 | 5 | **9** | **D-C** 사이드바 선례 채택. `UserCheck` · 하위링크 무아이콘 |
| 6 반응형·접근성 | 6 | **9** | **D-B** 접힘 시 최근 항목 섹션 전체 미렌더 |
| 7 미해결 결정 | — | — | **3건 해결 · 0건 잔류** |

**BLOCKER. 없음.**

**★리뷰가 실제로 바꾼 것.** 6개 공백 중 **4개는 선례 실측으로 자동 소멸**했고(잘림·로딩·아이콘 규격·헤더 타이포),
진짜 열린 결정은 3개뿐이었다. 초기 평점 6/10 의 절반은 **"정본이 없다"가 아니라 "정본을 안 찾아봤다"** 였다.
`FavoritesMenu.FilterFavoritesGroup` 이 **비동기 이름 조회 + 404 숨김 + 헤더 플리커 방지**라는
똑같은 세 문제를 이미 풀어 둔 것을 못 보고 plan 을 썼다.

**★리뷰가 스스로 철회한 것 1건.** Pass 3 에서 *"첫 사용자가 기능 존재를 모른다"* 를 제기했으나
ADR §D3 이 **자동 기록**으로 이미 닫아 둔 문제였다. 지적을 살려 뒀으면 없는 문제에
빈 상태 UI 를 만들 뻔했다 — **리뷰의 지적도 검증 대상**이다.

**외부 목소리 부재 (한계, PR-A 와 동일 조건).** `codex` 미설치 + 에이전트 호출 금지 지시로
교차 모델 리뷰·독립 서브에이전트 리뷰 둘 다 없다. 위 판정은 전부 자기 검증이다.
대가는 게이트 2 의 **브라우저 눈확인**으로 상쇄한다 (§11-B 완료 기준에 포함).

### NOT in scope (검토 후 명시적 이연)

| 항목 | 이연 사유 |
|---|---|
| 접힘 레일에서 최근 항목 접근 경로 | **D-B 의 의도된 대가.** TODOS.md 등록 완료. 실사용 신호 확보 전에는 추측 구현 |
| 최근 항목에 이슈 외 타입(보드·필터·대시보드) | 스펙 L5 — v1 범위. 저장 형식만 바뀌면 확장 가능 |
| 최근 이슈 제목 배치 조회 API | 스펙 L2 — 백엔드 변경이라 범위 밖. 세션 중에는 캐시 적중 |
| 사용자 스코프 최근 목록 | 스펙 L1 — 키만 저장 + 403/404 자동 탈락으로 유출 없음 |
| `nav-labels` 렌더 계약 테스트 통합 | `navigation-contract.test.tsx` 는 **렌더** 계약이라 대상이 다름. 통합은 FR15-b 범위 밖 |

### What already exists (재사용 대상 — 새로 만들지 말 것)

| 자산 | 위치 | 이 PR 에서의 쓰임 |
|---|---|---|
| `NAV_LINK_CLASS` · `NAV_ICON_CLASS` | `Sidebar.tsx:38-44` | "내 작업" 링크 스타일 |
| `TREE_SUB_LINK_CLASS` | `ProjectTree.tsx:54` | "최근 항목" 하위 링크 스타일 |
| 관리 메뉴 그룹 헤더 | `Sidebar.tsx:106-108` | "최근 항목" 그룹 헤더 타이포 |
| `FilterFavoritesGroup` | `FavoritesMenu.tsx:120-169` | 비동기 이름 조회 + 404 숨김 + 플리커 방지 **구조 참조** |
| `use-recent-projects.ts` | PR-A 산출물 145줄 | `use-recent-issues` 템플릿 |
| `issueQueryKey` · `fetchIssue` | `api/issues.ts` · `issues.$key.tsx:182-186` | 제목 조회 (캐시 공유) |
| `useAuthUser` | `authStore.ts:50` | `userId` 동기 조회 |
| `--sidebar-*` 토큰 8종 | `index.css` | 신규 색값 도입 금지 |

### Implementation Tasks (리뷰 발견 → 작업 반영)

발견 전량이 **기존 T5·T6 에 흡수**됐다. 신규 task 0건 — 없는 작업을 지어내지 않는다.

- [x] **T5 흡수** — 내 작업 최상단 배치 + `UserCheck` + 렌더 순서 단언 (D-A · D-C)
- [x] **T6 흡수** — 접힘 시 섹션 미렌더 · settle 전 미렌더 · 무아이콘 하위링크 단언 (D-B · D-C · D-D)
- [x] **스펙 §8-A** — 확정 3건 + 선례 강제 4건 + 철회 1건 정본화
- [x] **TODOS.md** — 접힘 접근 경로 1건 등록 (의도된 대가 명시)

## GSTACK REVIEW REPORT

| Review | Trigger | Why | Runs | Status | Findings |
|--------|---------|-----|------|--------|----------|
| CEO Review | `/plan-ceo-review` | Scope & strategy | 0 | — | — |
| Codex Review | `/codex review` | Independent 2nd opinion | 0 | — | codex 미설치 |
| Eng Review | `/plan-eng-review` | Architecture & tests (required) | 0 | — | BTS 분기표상 `type=ui` 는 design 단독 |
| Design Review | `/plan-design-review` | UI/UX gaps | 1 | CLEAR (FULL) | score: 6/10 → 9/10, 3 decisions |
| DX Review | `/plan-devex-review` | Developer experience gaps | 0 | — | — |

**VERDICT:** DESIGN CLEARED (9/10, 0 unresolved). eng review not run — BTS `bts-review-plan`
분기표가 `TYPE == "ui"` 에 `/plan-design-review` **단독**을 지정하므로 이 워크플로우에서는
누락이 아니다. gstack 기본 게이트 기준으로는 미충족이며, 그 차이를 여기 명시해 둔다.

NO UNRESOLVED DECISIONS


