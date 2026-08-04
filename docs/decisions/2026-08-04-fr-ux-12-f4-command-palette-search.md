<!-- FR-UX-12 F4 커맨드 팔레트 실체 검색의 입력 판별 경계 + 자유텍스트 AQL 래핑 소유권 결정 ADR -->

# ADR — FR-UX-12 F4 (Cmd+K 팔레트 실체 검색): 입력 판별 경계 + 자유텍스트 AQL 래핑 소유권

- 날짜: 2026-08-04
- 상태: 채택 (Accepted)
- 관련 FR: FR-UX-12 (경계 상대 FR-UX-04 · 소비 FR-UX-07 · 계약 상속 FR-SR-02/04)
- 관련 PR: #340

## 맥락 (Context)

`docs/plan/product/personalization.md §4.10` 의 D1 은 **"팔레트 입력의 3계층(슬래시 명령 · 이슈키 ·
자유 텍스트) 판별 규칙 정립. §4.2 FR-UX-04 명령 레지스트리와의 경계"** 를 요구한다.

### 착수 전 실측

정본 §4.10 의 주장 4건을 전수 대조한 결과 **전량 사실**이었다 (최근 5개 FR 연속으로 실측이 정본을
뒤집었던 것과 대비된다).

1. `CommandPalette.tsx:131` 의 `showQuickLinks = parsed.kind === 'not-command' && inputValue === ''`
   때문에 자유 텍스트는 목록이 비고, `:165` 의 `if (parsed.kind === 'not-command') return` 이
   Enter 를 삼키며, `:180` `shouldFilter={false}` 라 cmdk 기본 목록도 비어 위임처가 없다.
2. `components/ui/command.tsx` 소비처 **0** (`grep -rn "ui/command" src/ e2e/` 0건).
3. 상단바 전역 검색은 입력창이 아니라 아이콘 버튼 (`TopBar.tsx:67-77`).
4. 진짜 전역 검색(프로젝트 무관)은 **3층 차단** — `AqlSearchRequest.kt:36` `projectKey @NotBlank` ·
   `SearchController.kt:167` blank→400 · `AqlFields.kt:73` `PLANNED_FIELDS` 에 `project`·`assignee`.

### ★ 실측이 추가로 밝힌 선재 결함 — `/search <질의>` 는 실서버에서 깨진다

`runCommand:88` 이 `navigate({to:'/search', search:{q}})` 로 보내고 `search.tsx:311` 이 그 `q` 를
**가공 없이** `searchAql({query: q})` 에 넘긴다. 프론트 전수 grep 결과 `text ~` **래핑 코드 0건**.

백엔드 `AqlParser.parseComparison:166` 은 `expectIdent("필드명")` 다음에 연산자를 요구하므로
`로그인 버그` 는 `AqlSyntaxException` 이다. 설령 통과해도 `로그인` 이 `MVP_FIELDS`
(`status·label·summary·priority·text`)에 없어 `SEARCH_UNKNOWN_FIELD` 다.

**왜 아무도 몰랐나.** `mocks/search-handlers.ts:48` 의 MSW 핸들러가 **쿼리를 읽지 않고**
localStorage 시나리오 플래그로만 분기해 기본 3건을 반환한다(주석에도 *"쿼리 문자열 무관 고정
3건"*). 그래서 `command-palette.spec.ts:162` S4 가 결과 3건을 단언하며 통과한다. 유닛
(`CommandPalette.test.tsx:68`)은 `navigate` 호출 인자만 검증해 더더욱 못 잡는다.
계열 — [[mock-swallowed-prop-is-invisible-to-unit-tests]] · [[shared-dev-db-preexisting-rows-fake-green]].

사용법 힌트(`CommandPalette.tsx:18`)는 **`예: /search 로그인 버그`** 라며 깨지는 입력을 광고 중이다.

### 기존 경계 제약

`docs/decisions/2026-07-04-fr-ux-04-slash-cmd.md` 가 명령 레지스트리를 못박았다.

- **D1.** 프론트 전용 — 백엔드 command executor 미도입.
- **D3.** 명령 범위는 `/goto`·`/search`·`/issue` **3종, 전부 네비게이션**, 즉석 mutation 0.

### 선례가 서로 다른 방향을 가리킨다

- `2026-08-03-fr-ux-10-f10-context-shortcuts.md` **D-1** — 전역 키맵과 컨텍스트 키를 파일 분리가
  아니라 **도메인 지위 차이**로 갈랐다 (영속 O/X, 소속 BC 상이). → **분리** 쪽 근거.
- 같은 ADR **D-2** — **단일 판별 파이프라인**을 채택했다. → **통합** 쪽 근거.

## 결정 (Decision)

### D-1. 판별은 별도 레이어에 둔다 — `commands.ts` 는 슬래시 전용으로 유지 (Maxi 확정)

`parseCommand` 의 `ParsedCommand` 판별 유니온 **6갈래를 그대로 둔다**. 이슈키·자유 텍스트 판별은
신규 모듈이 맡고, **`parseCommand` 가 `not-command` 를 반환했을 때만** 2차로 호출한다.

근거. FR-UX-04 ADR **D3 이 레지스트리 범위를 "명령 3종"으로 못박았고**, 이슈키와 자유 텍스트는
슬래시 명령이 아니다 — F10 ADR D-1 의 "도메인 지위 차이로 가른다" 와 동형이다. F10 D-2 의 "단일
판별 파이프라인" 은 **한 FR 이 소유한 키 판별** 이야기라 두 FR 이 한 타입을 공유하는 이 상황과
지위가 다르다.

**대가로 생기는 계약** — 호출 순서(슬래시 먼저, 그다음 2차)가 계약이 된다. 순서가 뒤집히면
`/goto ATLAS-1` 이 이슈키 매칭으로 새어 들어간다. §D-5 의 경계 가드가 이것을 강제한다.

### D-2. 자유텍스트 → AQL 래핑은 **제3의 중립 모듈**이 소유한다 (합산 되짚기 산물)

`text ~ "<이스케이프된 질의>"` 로 감싸는 함수를 **`commands.ts` 도 신규 판별 레이어도 아닌**
`lib/aql-text-query.ts` 에 둔다. 양쪽이 그것을 소비한다.

**이 결정은 개별 질문 어디에서도 안 나왔다.** D-1(별도 레이어) 과 D-3(선재 결함 동시 수정) 을
**합치면** 래핑 함수를 두 경로가 동시에 필요로 한다 — 슬래시 `/search`(네비게이션)와 자유
텍스트(라이브 검색). 중립 위치가 아니면 어느 쪽에 둬도 의존이 꼬인다.
계열 — [[split-questions-hide-their-combination]] (각 답은 합리적인데 합치면 가드가 0이 되는 양식).

**★ 근거 정정 (plan 단계 실측).** 이 결정을 처음 적을 때는 `/search` 실행부가 `commands.ts`(FR-UX-04
소유)에 있다고 보고 **"`commands.ts → 신규 레이어` 역방향 의존"** 을 근거로 들었다. **실측 결과
틀렸다** — `runCommand` 는 `CommandPalette.tsx:84` 에 있고 `commands.ts` 는 순수 파서/레지스트리만
갖는다. 따라서 이 PR 은 `commands.ts` 를 **아예 수정하지 않는다**.

결정은 그대로 유지되지만 **이유가 바뀐다**. 두 소비처는 ①`runCommand`(컴포넌트 파일) ②라이브
검색 훅(`use-palette-search.ts`)이고, 훅을 `CommandPalette.tsx` 가 import 하므로 래퍼를 컴포넌트
파일에 두면 **훅 → 컴포넌트 순환 import** 가 된다. 중립 `lib/` 위치가 그래서 필요하다.
D-5 경계 가드 1(“`commands.ts` 가 신규 레이어를 import 하지 않는다”)은 **여전히 유효**하다 —
지금은 참이고, 그것이 깨지는 순간이 곧 경계가 무너지는 순간이다.

**이스케이프 필수.** 질의에 `"` 나 `\` 가 들어오면 AQL 문자열 리터럴을 탈출해 문법 오류 또는 의도치
않은 쿼리가 된다. 래퍼가 이스케이프까지 책임진다 — 두 소비처가 각자 처리하면 drift 가 확정된다.

### D-3. 선재 결함 `/search <질의>` 를 같은 PR 에서 봉합한다 (Maxi 확정)

`/search 로그인 버그` 가 `text ~ "로그인 버그"` 로 래핑되어 나가도록 고친다. 안 고치면 **같은
팔레트 안에서 `로그인 버그` 는 되는데 `/search 로그인 버그` 는 깨지는 모순**이 남고, 사용법 힌트가
계속 깨지는 입력을 광고한다.

**MSW 핸들러도 함께 고친다.** 쿼리를 읽지 않는 현재 핸들러는 이 결함을 만든 **가짜 그린의 원인**
이므로, 최소한 "필드/연산자 형태가 아닌 bare 텍스트면 `SEARCH_SYNTAX_ERROR`" 를 재현하게 해
**S4 테스트가 진짜 증인**이 되게 한다. 핸들러를 그대로 두면 봉합했다는 기록만 남고 증인은 없다
(계열 — [[discriminant-erases-its-own-evidence]]).

### D-4. 활성 프로젝트 미해소 시 — 이슈키는 살리고 자유텍스트만 안내 (Maxi 확정)

`useResolvedActiveProject` 가 `ready` 가 아닐 때(`loading`/`error`/`empty`).

- **이슈키 즉시매칭은 그대로 동작한다** — `fetchIssue(key)` 는 키만으로 조회하므로 프로젝트에
  의존하지 않는다. 프로젝트가 0개인 신규 사용자도 키를 알면 갈 수 있다.
- **자유텍스트 검색만** "프로젝트를 먼저 고르세요" 안내로 대체한다. AQL 은 `projectKey` 가
  필수라 여기서 검색을 시도하면 백엔드가 400 을 준다(§맥락 3층 차단).

### D-5. 순수 판별과 조회를 분리한다 + 경계를 가드로 강제한다 (합산 되짚기 산물)

D-1 은 "판별 레이어" 를, D-4 는 "활성 프로젝트 의존" 을 요구하는데 활성 프로젝트는 **훅**이고
`parseCommand` 는 **순수 함수**다. 둘을 한 모듈에 넣으면 판별을 훅 없이 단위 테스트할 수 없다
(저장소 관례 C3 위반).

- **판별은 순수 함수로** — 입력 문자열 → 판별 결과. 프로젝트·네트워크 무의존.
- **조회/실행은 컴포넌트·훅 층에서** — `useResolvedActiveProject` · `useDebounce` · `searchAql`.

**경계 가드.** D-1 의 경계는 코드에 흔적을 남기지 않으므로 다음 편집자가 자유텍스트 판별을
`commands.ts` 로 되돌려도 아무도 못 막는다. 따라서 다음을 테스트로 강제한다.

1. `commands.ts` 가 신규 판별 레이어를 **import 하지 않는다** (역방향 의존 차단).
2. `ParsedCommand` 유니온이 **정확히 6갈래**로 유지된다 (레지스트리 범위 동결).
3. 호출 순서 계약 — `/goto ATLAS-1` 같은 슬래시 입력이 **이슈키 경로로 새지 않는다**.

## 결과 (Consequences)

- **백엔드 0.** FR-UX-04 ADR D1(프론트 전용)과 충돌 없음. `personalization.md §4.10` 의
  D4/D5("없음 예상")를 그대로 확정한다. 마이그레이션 0 · 신규 의존성 0(`cmdk`·`useDebounce` 재사용).
- **FR 수 불변 139.** 신규 FR 없음. 선재 결함 봉합은 FR-UX-04 의 결손 보정이라 FR 신설 아님.
- **D6/D7 은 `[ ]` 유지.** §4.10 D6 본문이 「F4 + F13」이므로 F13 미착수 동안 닫히지 않는다
  (FR-UX-10 #336 · FR-UX-11 #337 선례와 동형). D1~D5 만 `[x]`.
- **기존 테스트 재작성 범위.** `command-palette.spec.ts` S4 · `CommandPalette.test.tsx:68` ·
  `mocks/search-handlers.ts` 3곳. **회귀 가드 필수** — `command-palette.spec.ts:259` 의
  "빈 입력 시 `QUICK_LINKS` 4개와 순서 보존"(팔레트 option `'검색'` `exact:true`)이 죽으면 안 된다.
- **정본 drift 1건 정정 대상.** FR-UX-04 ADR **D3** 이 `/search <질의>` → **`/issues?q=`** 라고 적었으나
  실제는 `/search?q=` 다(`router.ts:534,544`). ADR 이 구현 확정 전 시점의 문구다. 본 PR 에서
  해당 ADR 에 정정 각주를 단다.
- **`navLabels.search` 봉인은 이 PR 범위 밖.** `nav-labels.ts:6` 이 `'검색'` 을 e2e 계약 문자열로
  봉인했고 `navigation-contract`·`ShellLayout` 이 「버튼 정확히 1개」를 단언한다 — 그것을 깨는 것은
  상단바를 입력창으로 바꾸는 **F13** 이다. 본 PR 은 팔레트 안에 `'검색'` 이라는 **새 접근성 이름을
  만들지 않는다**(만들면 `command-palette.spec.ts:259` 가 strict mode 로 즉사한다).
