# FR-UX-12 F13 — 상단바 전역 검색 입력창 + 자연어 폴백 ADR

> FR: **FR-UX-12** (D6/D7 을 닫아 완주) · PR **#341** · type `ui` · 2026-08-05
> 선행. F4 (#340) 가 D1~D5 를 닫고 `전역 검색` 이름표를 F13 으로 이관했다.
> plan `docs/plans/2026-08-05-fr-ux-12-f13-global-search-input.md` ·
> spec `docs/specs/2026-08-05-fr-ux-12-f13-global-search-input.md`

## 착수 전 실측 — 정본 4건 확인, 표면 1건 확대

정본 §4.10 과 로드맵 `:64` 의 주장은 **전량 사실**이었다(파일 목록·의존·D 마커 상태).
다만 **영향 표면이 정본이 적은 것보다 넓었다.**

| 정본이 말한 것 | 실측 |
|---|---|
| (언급 없음) | `navLabels.search` 봉인이 **유닛 4곳 + E2E 6파일**을 끌고 온다 |
| (언급 없음) | E2E 5파일이 `const HEADER_SEARCH_ARIA_LABEL = '검색'` 을 **하드코딩** — 2026-05-26 교훈(셀렉터는 i18n 정본 import) 위반 상태 |
| `routes/search.tsx` 수정 대상 | **무변경으로 끝났다** (D-5 참조) |

## D-1. `/` 단축키는 바꾸지 않는다 — ★근거가 실측으로 뒤집힌 결정

**결정.** `/` 는 계속 `/search` 페이지로 이동한다. `SHORTCUTS` 레지스트리를 **한 글자도 건드리지 않았다.**

**★ 이 결정은 한 번 뒤집혔다.** 처음에 나는 「Jira 도 `/` 로 검색창을 포커스한다」를 근거로
**바꾸자고 추천**했고 Maxi 가 승인했다. 그 뒤 Atlassian 공식 문서를 실제로 열어 확인한 결과
**그런 전역 단축키가 없었다** — Jira Cloud 의 검색 진입은 **Cmd/Ctrl+K 팔레트**이고, `/` 는 그
**팔레트 안에서** 명령 검색 ↔ 항목 검색 모드를 바꾸는 키다. 이는 F4 ADR 이 이미 기록한
*"`/` 의 의미가 Jira 와 정반대"* 와 같은 사실이다.

근거가 사라졌으므로 추천을 **철회하고 재확인을 요청**했고, Maxi 가 현행 유지를 확정했다.

**최종 근거 (Jira 패리티가 아니다).**
- F4 #340 이 **Cmd+K 팔레트로 키보드 검색 진입을 이미 닫았다.** 여기에 `/` 까지 입력창으로
  돌리면 같은 일을 하는 키보드 경로가 셋이 된다.
- 대가로 `ShortcutAction` 유니온에 `focus` 종류를 신설해야 하는데, 그 파일은
  계약 §2 가 동결한 레지스트리다.

**남는 갭 (의도적 편차 3).** `/` = 검색창 포커스는 **웹 일반 관습**(GitHub·Slack·Linear)이다.
Jira 대응이 없으므로 계약 §1-4 에 따라 「Jira 대응 없음」으로 명시한다. 후속 FR 에서 재검토 가능.

**교훈.** 「지라가 이렇게 한다」를 근거로 쓸 거면 **문서를 열어보고 쓰라.** 이 저장소는 근거를
문서에 남기므로 거짓 근거는 다음 FR 의 판단을 오염시킨다.

## D-2. 자연어 폴백 = 3갈래 판별만

**결정.** 입력을 `empty` → `issue-key` → `aql` → `text` 순으로 가른다. **키워드 매핑을 넣지 않는다.**

- `issue-key` — 트림·대문자 정규화 후 `ISSUE_KEY_PATTERN` 일치 → `/issues/$key`
- `aql` — **알려진 필드로 시작하고 다음 토큰이 연산자**일 때 → `/search?q=<원문>`
- `text` — 그 외 → `buildTextQuery` 로 `text ~ "…"` 래핑

**근거.** 「내 이슈」·「지난주」 같은 키워드 매핑은 목록에 **없는** 말이 나오는 순간 신뢰가 깎이고,
목록을 늘려달라는 압력을 상시로 받는다. F4 의 교훈 *「지키지 못할 약속은 버튼을 옮긴다」* 의
같은 갈래다. 「폴백」이라는 단어가 약속하는 것과 동작을 정확히 일치시켰다.

**판별 순서가 계약이다.** 이슈키를 AQL 보다 먼저 본다 — 지금은 `ATLAS-42` 가 어떤 필드명으로도
시작하지 않아 충돌이 없지만, 순서를 뒤집으면 나중에 필드가 늘었을 때 **조용히 갈래가 바뀐다.**

**목록을 복제하지 않는다.** `AQL_FIELDS`·`AQL_OPERATORS`(`lib/aql-tokenizer.ts`) ·
`buildTextQuery`(`lib/aql-text-query.ts`) · `ISSUE_KEY_PATTERN`(`lib/issue-key.ts`) 전부 import 다.
복제하면 백엔드 `AqlFields.MVP_FIELDS` 와의 drift 를 아무도 못 본다.

## D-3. 「전역」은 어디서나 접근이지 프로젝트 무관이 아니다

**결정.** 활성 프로젝트 스코프를 유지한다. cross-project 검색은 열지 않는다.

**근거.** 이는 **새 결정이 아니라 선행 결정의 승계**다 — `glossary.md:90`(FR-UX-07)이
*"cross-project 는 로드맵 **B3 후속**, **보안 fail-open 위험**으로 제외"* 라고 못박았고,
로드맵 `:64` 의 F13 파일 목록에 백엔드 파일이 **0개**다. F4 ADR `:25` 가 실측한
**3층 차단**(`AqlSearchRequest.kt:36` `projectKey @NotBlank` · `SearchController.kt:167` ·
`AqlFields.kt:73` `PLANNED_FIELDS`)도 그대로 유효하다.

## D-4. 이슈키 정규식을 중립 1곳으로 올린다 (선재 결함 봉합)

**결정.** `lib/issue-key.ts` 를 신설하고 `ISSUE_KEY_PATTERN` 을 그 한 곳에만 둔다.

**근거.** 착수 시점에 이 정규식이 **2곳에 복제**돼 있었고(`command-palette/commands.ts:6` ·
`palette-input.ts:9`), 후자의 주석이 *"commands.ts 의 ISSUE_KEY_PATTERN 과 같은 규칙"* 이라며
**스스로 복제임을 자백**하고 있었다. F13 이 세 번째를 만들면 3중 복제다.

**정규식만 올리고 각 호출부의 `trim()`/`toUpperCase()` 는 그대로 뒀다** — 동작이 한 글자도
바뀌지 않아야 하기 때문이다(§3 Surgical Changes). 완료 기준은
`grep -rn "ISSUE_KEY_PATTERN\s*=" apps/web/src` 가 **1건**.

선례. F4 가 `/search <질의>` 선재 결함을 같은 PR 에서 봉합한 것과 동형.

## D-5. 상단바는 활성 프로젝트를 조회하지 않는다 — 게이트를 복제하지 않는다

**결정.** `/search` 로 보낼 때 `projectKey` 를 싣지 않는다. `routes/search.tsx` **무변경**.

**근거 (실측이 스펙을 단순화했다).** 목적지가 이미 둘 다 소유하고 있다.
- `routes/search.tsx:480` — `useResolvedActiveProject(search.projectKey)` 4단 해소
- `routes/search.tsx:565-566` — 미해소 시 `<ActiveProjectGate state={activeProject} />`

상단바가 게이트를 복제하면 F4 가 남긴 관찰(*「`ActiveProjectGate` 문구가 팔레트에 복제됨 —
drift 위험」*)을 그대로 반복한다. 상시 마운트 컴포넌트에서 훅 하나를 덜 쓰는 이득도 있다.

초안 스펙의 FR12 는 「상단바가 안내한다」였고, 실측 후 **「복제하지 않는다」로 반전**했다.

## D-6. `components/ui/input.tsx` 프리미티브를 쓴다 — plan 리뷰 BLOCKER

**결정.** raw `<input>` + 인라인 className 을 쓰지 않는다. 덮어쓰는 클래스는 `pl-8` 하나.

**근거.** 내 초안 plan 이 raw `<input>` 을 썼고, **디자인 리뷰가 BLOCKER 로 걸렀다.**
인라인 값이 프리미티브 정본과 **충돌**하고 있었다.

| 항목 | 프리미티브 | 내 초안 |
|---|---|---|
| 라운드 | `rounded-lg` | `rounded-md` |
| 포커스 | `focus-visible:border-ring` + `ring-3 ring-ring/50` | `ring-2 ring-ring` |
| 다크 | `dark:bg-input/30` 내장 | `bg-background` (다크에서 안 눌림) |
| 비활성·오류 | `disabled:`·`aria-invalid:` 내장 | 없음 |

**hover 를 추가하지 않는 것도 결정이다.** 프리미티브에 hover 배경이 없는 것이 의도다 —
입력창의 어포던스는 I-beam 커서가 주고, 형제 폼 입력 전부와 어긋나면 안 된다.
브라우저 눈확인에서 hover 전/후 **픽셀 해시 완전 일치**로 검증했다.

## D-7. 폭만 반응형, 컨트롤 종류는 어떤 폭에서도 불변 — plan 리뷰 BLOCKER

**결정.** `flex-1` + `max-w-md`(448px) + `min-w-32`(128px). **좁다고 아이콘 버튼으로 되돌리지 않는다.**

**근거.** 초안 FR4 는 「sm 미만에서 아이콘 버튼으로 축약**될 수 있다**」였다. 디자인 리뷰가
BLOCKER 로 걸렀다 — 축약하면 ① 좁은 뷰포트에서 `searchbox` 가 0개가 돼 유닛 4곳·E2E 6파일
단언이 **뷰포트에 따라** 깨지고 ② 그 버튼의 접근성 이름을 뭘로 할지 계약 §2 문제가 되살아난다.

상·하한 근거. `max-w-md` = 이보다 길면 한 줄 스캔이 어렵고 우측 액션과 균형이 깨진다.
`min-w-32` = 한글 4~5자 + 돋보기가 들어가는 최소치, 이보다 좁으면 placeholder 가 잘린다.
실측(1024→320 6단계)에서 **전 폭 `searchbox` 1개** 유지, 헤더 48px 불변, 우측 액션 압축 0.

## 안전장치 — role 과 name 이 **둘 다** 바뀐다

`button`+`검색` → `searchbox`+`전역 검색`. 잔존 참조가 **조용히 통과할 수 없다.**
빠뜨린 곳은 즉시 빨간불이 된다. 이 성질이 실제로 작동해 `saved-filters.spec.ts:206` 을 잡았다.

## ★ plan 이 틀렸던 지점 3건 (구현이 잡았다)

1. **`saved-filters.spec.ts:206` 오분류.** plan 이 「AQL 제출 버튼, 건드리지 말 것」으로 묶었으나
   실측하면 **상단바 진입 헬퍼**였다(JSDoc `:203` 이 *"Header \"검색\" 아이콘 클릭으로 pushState
   이동한다"* 라고 자백). `:238`·`:240` 만 진짜 제출 버튼이다. **grep 결과를 문맥 없이 분류한 것이
   원인** — 같은 파일에서 bare `page.getByRole` 와 `searchButtonContainer.getByRole` 가 섞여 있었고
   `search.spec.ts` 에서는 같은 형상을 옳게 갈랐으면서 여기서 놓쳤다. 같은 PR 에서 봉합.
2. **`nav-labels.test.ts` FR15 판별식 미인지.** 「라벨 쌍 substring 전수 검사」가 있어
   `'검색'` ⊂ `'전역 검색'` 이 되는 순간 자동으로 red 다. plan 이 이 판별식의 존재를 몰랐다.
   화이트리스트 3쌍을 **실측 근거와 함께** 추가했다 — e2e 6곳 전량이 이미 `exact: true` 이고,
   `search`/`globalSearch` 는 role 이 다르며(`button`/`option` vs `searchbox`), placeholder 2쌍은
   `aria-label` 이 있어 접근성 이름이 되지 않는다. **면제 유효 조건은 `exact: true` 유지**다.
3. **연산자 정렬 주석이 거짓.** plan 이 *"긴 것부터 정렬해야 `!=` 가 `=` 에 먼저 먹히지 않는다"* 라고
   썼으나 실측상 거짓이다 — 정규식 교대는 역추적하므로 `test()` 결과가 순서와 무관하고, 현
   연산자 3종은 서로 접두사 관계가 아니다(`!=` 는 `!` 로 시작). `.sort()` 코드는 향후 접두사 공유
   연산자(`>`/`>=`) 대비로 **유지**하되 주석만 실측 근거로 교체했다.
   메모리 `decorative-annotation-copied-from-sibling` 재발을 막은 판단.

## ★ 코드리뷰가 잡은 BLOCKER 2건 (게이트 2 직전, 봉합 완료)

독립 코드리뷰가 **둘 다 내 plan 이 원인**인 결함을 잡았다. #340 에서 게이트 2 독립 리뷰가
BLOCKER 를 잡은 것과 **같은 자리에서 또 나왔다.**

### B1. `search.spec.ts` S1 이 AQL 제출을 증명하지 못했다 — 「가짜 테스트」 재발

씨앗 진입이 `?q=text ~ "진입용씨앗"` 을 실어 `SearchPage` 가 마운트 즉시 조회하고, MSW 핸들러
`isSyntacticallyValidAql` 이 `text ~` 에 매치돼 기본 3건을 반환한다. 그래서 `fillAndSearch`
**이전에** 결과 카드·리스트 가시·`alert` 부재가 전부 참이었다. **제출 클릭을 지워도 초록.**

그런데 이 ADR 이 *"진짜 증인은 S1"* 이라고 적었다 — **문서가 없는 가드를 있다고 말한 것**이다.

**처방.** S1 만 씨앗 진입을 그만두고 `page.goto(SEARCH_URL)` 로 직접 들어간다(같은 파일 S2·S4 선례).
**비-공허 확인 실측** — 제출 `.click()` 무력화 시 S1 이 `getByRole('list', {name:'검색 결과'})
.getByRole('link', {name:'ATLAS-1'})` 에서 **red**. 원복은 역방향 Edit.

### B2. 상단바 우측 액션이 오른쪽 끝에 붙지 않았다

plan 이 `<div className="flex-1" />` 정렬 스페이서를 **지우라고** 지시했다. 헤더는 `flex … gap-1`
에 `justify-*` 가 없고, grow 를 가진 자식이 검색 컨테이너 하나뿐인데 `max-w-md` 에서 성장이
멈춘다. 나머지는 전부 `shrink-0`/grow 0 이라 **남는 공간이 줄 끝에 그대로 남았다.**

**왜 안 걸렸나.** 브라우저 눈확인 범위가 **1024→320** 이라 이 구간이 통째로 검증 밖이었고,
우측 정렬을 단언하는 E2E 가 없다. **눈확인 범위를 좁게 잡은 것이 결함을 통과시켰다.**

**처방.** 검색 컨테이너 **직후**에 스페이서 복원. grow 요소가 둘 필요한 이유를 주석에 남겼다.
**실측** — 1920px 우측 여백 **1046px → 12px**(= `px-3` 헤더 패딩, 잔여 0). 1440·1280·1024·768
전부 12px, 라이트·다크 동일.

### 함께 반영한 Important 2건 · Suggestion 3건 · 예시 교체 1건

- **IME 이중 방어** — `e.keyCode === 229` 추가. 저장소 선례(`issues.$key.tsx:742` ·
  `IssueDescription.tsx:392`)보다 약했다. `isComposing` 을 안 세우고 229 만 보내는 IME 에서 첫
  Enter 가 제출로 샌다. 유닛은 `isComposing` 을 직접 심으므로 이 경로를 **원리적으로 못 잡는다.**
- **`keyboard-shortcuts.spec.ts` 헤더 정정** — *"page.reload() 미사용"* 이 `page.goto` 도입으로
  거짓이 됐다. S5 부정 단언의 전제(리스너 등록 보장)도 `searchbox` 가시성 1줄로 복원.
- **`as string` 단언 제거** — `buildTextQuery` 에 나중에 길이 상한이 붙으면 `query: null` 이
  `string` 을 달고 흘러 빈 검색이 조용히 나간다. `null` 이면 `empty` 로 떨어뜨린다.
- **`ISSUE_KEY_PATTERN.sticky` 단언 추가** — `y` 플래그도 `test()` 에서 `lastIndex` 를 전진시킨다.
  테스트 이름이 선언한 의도보다 단언이 좁았다.
- **정규식 이스케이프 대칭화** — 연산자만 이스케이프하고 필드는 안 하던 비대칭 해소.
- **테스트 예시 교체** — `priority != HIGH` 는 백엔드에서 **HTTP 500** 이 난다
  (`IssueRepository.kt:2991 asShort()` 의 IAE 를 `SearchExceptionHandler.kt:48` 이 안 다뤄
  catch-all). **PRE_EXISTING 백엔드 결함**이고 이 PR 책임은 아니나, 상시 노출 상단바가 이 입력
  표면을 넓히므로 예시로 못박지 않는다. `label != backend` · `priority = 1` 로 교체.
  **백엔드 500 은 별도 트랙 등재 권장.**

### 리뷰가 확인해 준 것 (문제 없음)

- **ReDoS 없음** — `^` 앵커 + 리터럴 교대 2개 + `\s*` 1개, 중첩 수량자 0, 입력이 상수 배열.
- **화이트리스트 면제 3쌍 비-공허** — 판별식 자체에 짝 검사(허용 쌍이 실제 substring 인지)와
  비-공허 가드가 있고, `exact: true` 가 e2e 로케이터 **5곳 전량**에 유지됨을 전수 확인.
- **`Input` 프리미티브 주장 사실** — 덮어쓴 클래스는 `pl-8` 하나뿐이고 프리미티브를 무력화하지 않음.
  `min-w-32` 도 tailwind v4.3.0 이라 실재하는 클래스(v3.3 이하였다면 공허했을 것).
- **`shortcuts.ts` · `routes/search.tsx` 무변경** 확인 — ADR 주장이 사실.
- **절대 규칙 19개 위반 0** — `any` 0 · `!!` 0 · 빈 catch 0 · `console.log` 0 · 신규 의존성 0.

## 검증 실적

- 프론트 유닛 **8,813** / 557파일 (기준선 8,795 대비 +18, 감소 0 — 리뷰 봉합의 `keyCode 229` 짝 테스트 포함)
- `tsc -p tsconfig.app.json --noEmit` EXIT 0 · `eslint src` EXIT 0
- E2E 6 spec **2회 연속 50/50** (`saved-filters`·`search`·`context-shortcuts`·
  `keyboard-shortcuts`·`command-palette`·`detail-action-shortcuts`)
- **가드 비-공허 확인** — `isComposing` 제거 → S6 만 red · `empty` 가드 무력화 → S4 만 red ·
  `aria-label` 뮤테이션 → 긍정 단언 3건 red · 사이드바 라벨 뮤테이션 → **부정 단언 3건도** red
  (긍정 뮤테이션만으로는 부정 단언이 red 가 되지 않아 별도 뮤테이션이 필요했다)
- `grep -rn "ISSUE_KEY_PATTERN\s*=" apps/web/src` → **1건**
- `grep -rn HEADER_SEARCH_ARIA_LABEL apps/web/` → **0건**
- 브라우저 눈확인 7항목 라이트/다크 양쪽 (실 Chromium, hover 무변화는 픽셀 해시 일치로 증명)

## PRE_EXISTING (이번 PR 책임 아님 — main 트리에서 동일 재현)

- `board-swimlane-field-change.spec.ts:283,325,497,362` — DnD 로케이터 미발견 · 타임아웃
- `workflow-scheme-assignment.spec.ts:21` — `getByText('소프트웨어 개발 기본 스킴')` strict mode violation

## 남은 관찰

- `command-palette.spec.ts:422` S12 로그인 진입 **flaky** (4회 중 1회, `page.goto('/login')` 직후
  heading 미발견). 실패 대상이 회차마다 바뀌는 flaky 서명. 별도 트랙 권장.
- `apps/web/e2e/` 가 어느 `tsconfig` 의 `include` 에도 없어 **타입검사 미적용**. 별건.
- `saved-filters.spec.ts` SF-1 의 「결과 목록」 단언은 **제거하지 않고 보존**하되, 씨앗 진입 때문에
  제출의 증인이 아님을 주석으로 명시했다. URL `q` 로 증인을 세우려던 시도는 실측으로 **기각**됐다 —
  `search.tsx:545 handleQueryChange` 가 **타이핑만으로도** `q` 를 갱신한다. 해소의 증인은
  `projectKey` 다(SF-1·SF-3).
- `search.spec.ts` **B4** 의 말미 결과 카드 단언은 씨앗 조회로 여전히 선참이다(뮤테이션에서 green
  유지 실측). 리뷰 처방이 S1 한정이라 그대로 뒀고 사실만 헤더 주석에 실측값과 함께 기록했다.
  B4 도 직접 진입으로 바꿀지는 후속 판단 대상.
- `keyboard-shortcuts.spec.ts` **S3a**(`g` `i` → `/issues`) 선재 flaky — 브랜치 1/42 · main 1/42
  동일 재현, S3a 단독은 브랜치·main 각 12/12 green. **PRE_EXISTING**, 별도 이슈 후보.
