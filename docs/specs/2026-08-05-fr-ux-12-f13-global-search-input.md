# FR-UX-12 F13 — 상단바 전역 검색 입력창 + 자연어 폴백 스펙

> FR: **FR-UX-12** (D6/D7 을 닫는다) · type `ui` · agent `frontend-engineer` · PR #341
> 정본. `docs/plan/product/personalization.md` §4.10 · `docs/design/jira-parity-roadmap.md:64`
> 선행. F4 (#340) 가 D1~D5 를 닫았고 `전역 검색` 이름표를 F13 으로 이관했다(`personalization.md:420`).

## 배경 — 지금 무엇이 문제인가

상단바의 전역 검색이 **입력창이 아니라 아이콘 버튼**이다(`TopBar.tsx:67-77`). 누르면
`/search` 페이지로 이동만 하고, 검색어를 상단바에서 바로 칠 수 없다. 그래서 「검색 진입」이
Cmd+K 팔레트 하나뿐이고, 마우스 사용자는 항상 페이지를 한 번 갈아탄 뒤에야 타이핑을 시작한다.

## 확정 결정 (Maxi, 2026-08-05)

| # | 결정 | 근거 |
|---|---|---|
| **M1** | **`/` 단축키는 현행 유지** — `/search` 페이지 이동. 상단바 입력창은 마우스와 Cmd+K 로 접근 | 「Jira 도 `/` 로 검색창을 포커스한다」는 **초기 근거가 실측으로 거짓 판정**. Jira Cloud 의 검색 진입은 Cmd+K 팔레트이고 `/` 는 그 팔레트 **안에서** 명령↔항목 모드를 바꾸는 키다. F4 가 이미 Cmd+K 로 키보드 진입을 닫았으므로 `/` 까지 입력창으로 돌리면 같은 일을 하는 키보드 경로가 셋이 되고, 대가로 동결된 `SHORTCUTS` 구조체를 건드린다 |
| **M2** | **자연어 폴백 = 3갈래 판별만** — 이슈키 / AQL 문법 / 자유 텍스트 | 「폴백」이라는 말이 약속하는 것과 동작을 정확히 일치시킨다. 키워드 매핑(「내 이슈」·「지난주」)은 목록에 없는 말이 나오는 순간 신뢰가 깎이고, 목록을 늘려달라는 압력을 상시로 받는다 |
| **M3** | 「전역」 = **어디서나 접근**이지 프로젝트 무관이 아니다 | cross-project 는 로드맵 B3 후속, **보안 fail-open 위험**으로 제외 (`glossary.md:90`, FR-UX-07) |

## Jira 대조 (계약 §1)

**1. 대응 화면.** Jira Cloud 상단 네비게이션의 전역 검색 + Cmd/Ctrl+K 검색 팔레트.

**2. 조작감 갭 + 3. BTS 제약 교차.**

| # | Jira Cloud | BTS 현재 | F13 처리 |
|---|---|---|---|
| J1 | 상단바에 **검색 입력창**이 상시 노출 | 아이콘 버튼만 | **닫는다** — 입력창으로 교체 |
| J2 | 결과가 **유형별로 묶여** 표시(work item · project · board · filter), 행에 유형 아이콘·키·요약·**프로젝트명** | 상단바 결과 없음 | **부분 채택** — 이슈만, **프로젝트명 제외**(아래 편차 2) |
| J3 | 「**View all results**」 → 전체 결과 페이지 | 없음 | **닫는다** — F4 팔레트의 「모든 결과 보기 (N건)」과 **동일 문안·동일 목적지** |
| J4 | 검색 진입 = **Cmd/Ctrl+K** | Cmd+K 팔레트 (F4 #340) | **이미 패리티** |
| J5 | 팔레트 안 `/` = 명령→**항목 검색** 모드 전환 | 팔레트 안 `/` = **명령** | **의도적 편차 1** (F4 ADR 승계, 아래) |
| J6 | 전역 `/` 포커스 단축키 | `/` = `/search` 페이지 이동 | **Jira 대응 없음** — Jira 에 그런 단축키가 없다. M1 로 현행 유지 |

**의도적 편차 3건.**

1. **`/` 의 의미가 Jira 와 정반대** — Jira `/`=항목검색(팔레트 내부), BTS `/`=명령. F4 ADR 이
   판정 완료한 편차를 그대로 승계한다. 뒤집으면 FR-UX-04 ADR·유닛 2파일·E2E S1~S5 가 동시에 깨지고
   사용자 체감 결과는 같다.
2. **결과 행에서 프로젝트명 제외** — Jira 는 표시하지만 BTS 는 **활성 프로젝트 단일 스코프**(M3)라
   모든 행이 같은 값이 되어 소음이다. F4 가 팔레트에서 내린 판단을 승계한다.
3. **`/` 를 전역 포커스로 쓰지 않음** — Jira 대응이 없고(J6), 웹 일반 관습(GitHub·Slack·Linear)과는
   어긋난다. M1 의 근거대로 경로 중복을 피하는 선택이며, 후속 FR 에서 재검토 가능하다.

**출처.** [Search work items, projects, and more with your keyboard (Jira Cloud)](https://support.atlassian.com/jira-software-cloud/docs/search-issues-projects-and-more-with-the-your-keyboard/) ·
[Navigate Jira with your keyboard](https://support.atlassian.com/jira-software-cloud/docs/navigate-jira-with-your-keyboard/)

## 사용자 시나리오 (Given-When-Then)

- **S1 자유 텍스트 검색.** Given 활성 프로젝트가 ATLAS 이고 상단바가 보인다 / When 입력창에
  `로그인 버그` 를 치고 Enter / Then `/search?q=text ~ "로그인 버그"` 로 이동해 결과가 나온다.
- **S2 이슈키 즉시 이동.** Given 상단바 / When `atlas-42` 를 치고 Enter / Then 대소문자 무관하게
  `ATLAS-42` 로 정규화돼 `/issues/ATLAS-42` 로 이동한다. 검색 페이지를 경유하지 않는다.
- **S3 AQL 통과.** Given 상단바 / When `status = "열림"` 을 치고 Enter / Then 원문 그대로
  `/search?q=status = "열림"` 로 이동한다. `text ~` 로 감싸지 않는다.
- **S4 빈 입력.** Given 상단바 / When 공백만 치고 Enter / Then 아무 일도 일어나지 않는다(이동 없음).
- **S5 특수문자 보존.** Given 상단바 / When `C:\Users` 를 치고 Enter / Then 역슬래시가
  `escapeAqlString` 으로 보존돼 `text ~ "C:\\Users"` 가 되고 400 이 나지 않는다.
- **S6 한글 IME 조합.** Given 상단바 / When 한글을 조합 중(`compositionstart`~`compositionend`)에
  Enter 를 누른다 / Then **제출되지 않는다**. 조합이 끝난 뒤의 Enter 에만 제출한다.
- **S7 활성 프로젝트 미해소.** Given 접근 가능한 프로젝트가 0건 / When 자유 텍스트를 치고 Enter /
  Then `/search?q=…` 로 이동하고 **검색 페이지가 이미 소유한 `ActiveProjectGate` 가 안내한다**.
  상단바는 안내를 복제하지 않는다. **이슈키 입력은 그대로 동작한다** — 이슈 조회는 프로젝트 무관이다.
- **S8 이름표 분리.** Given 어느 화면이든 / When 접근성 이름으로 컨트롤을 찾는다 / Then 상단바
  입력창은 `전역 검색`, AQL 페이지 제출 버튼은 `검색` 으로 **서로 다른 이름**을 가진다.

## 기능 요구사항 (FR)

**입력창.**

- **FR1.** 상단바의 검색 아이콘 버튼을 **입력창으로 교체**한다. `<input type="search">` 를 써
  접근성 role 이 `searchbox` 가 되게 한다. **`components/ui/input.tsx` 프리미티브를 쓴다**
  (계약 §4 재사용 자산) — raw `<input>` 에 클래스를 직접 쓰면 라운드·포커스 링·다크 배경이
  프리미티브와 어긋난다.
- **FR2.** 입력창의 접근성 이름은 **`전역 검색`** 이다. `i18n/nav-labels.ts` 에
  `globalSearch: '전역 검색'` 을 신설하고 하드코딩하지 않는다.
- **FR3.** 기존 `navLabels.search`(`'검색'`)는 **삭제하지 않는다** — AQL 페이지 제출 버튼이
  계속 쓴다. 다만 「상단바 검색 버튼 aria-label」이라는 주석은 사실과 달라지므로 정정한다.
- **★ FR4 — 폭만 반응형이고 컨트롤 종류는 어떤 폭에서도 바뀌지 않는다.**
  `flex-1` 로 남는 공간을 먹되 `max-w-md`(448px) 상한과 `min-w-32`(128px) 하한을 둔다.
  **좁다고 아이콘 버튼으로 되돌리지 않는다** — 되돌리면 ① 좁은 뷰포트에서 `searchbox` 가 0개가 돼
  완료 기준 1 과 유닛·E2E 단언이 뷰포트에 따라 깨지고 ② 그 버튼의 접근성 이름을 무엇으로 할지
  계약 §2 「`검색` 이름 분리」 문제가 되살아난다.
- **FR4-a.** hover 상태를 **추가하지 않는다.** 프리미티브에 hover 배경이 없는 것이 의도다 —
  입력창의 어포던스는 I-beam 커서가 주고, 상단바 형제 버튼의 `hover:bg-accent` 를 붙이면
  다른 폼 입력 전부와 어긋난다.

**판별 (`lib/aql-natural.ts` 신설).**

- **FR5.** 입력 문자열을 **3갈래**로 판별하는 순수 함수를 제공한다.
  1. `issue-key` — 트림·대문자 정규화 후 `ISSUE_KEY_PATTERN` 에 일치 → `/issues/$key`
  2. `aql` — **알려진 필드명으로 시작하고 그 다음 토큰이 연산자**일 때 → `/search?q=<원문>`
  3. `text` — 그 외 → `buildTextQuery` 로 감싸 `/search?q=text ~ "…"`
- **FR6.** AQL 판별은 `lib/aql-tokenizer.ts` 의 **`AQL_FIELDS` · `AQL_OPERATORS` 를 재사용**한다.
  필드·연산자 목록을 새로 선언하지 않는다(백엔드 `AqlFields.MVP_FIELDS` 가 정본).
- **FR7.** 자유 텍스트 → AQL 변환은 **`lib/aql-text-query.ts` 의 `buildTextQuery` 를 재사용**한다.
  이스케이프를 새로 구현하지 않는다(계약 §4).
- **FR8.** 빈 문자열·공백만인 입력은 판별 결과가 `none` 이고 아무 이동도 하지 않는다.

**★ FR9 — 선재 결함 봉합. 이슈키 정규식 3중 복제 차단.**

`ISSUE_KEY_PATTERN` 이 이미 **2곳에 복제**돼 있고(`command-palette/commands.ts:6` ·
`command-palette/palette-input.ts:9`), 후자의 주석이 *"commands.ts 의 ISSUE_KEY_PATTERN 과 같은
규칙"* 이라며 **스스로 복제임을 자백**한다. F13 이 세 번째를 만들면 3중 복제다.
**정규식을 중립 위치 한 곳으로 올리고 기존 2곳이 그것을 import 하게 바꾼다.**
F4 가 `/search <질의>` 선재 결함을 같은 PR 에서 봉합한 선례를 따른다.

**제출.**

- **FR10.** Enter 로 제출한다. IME 조합 중(`isComposing`)에는 제출하지 않는다.
- **FR11.** 제출 후 입력값은 **유지**한다(지우지 않는다) — 사용자가 방금 친 것을 보며 다듬을 수 있게.
- **★ FR12 — 상단바는 활성 프로젝트를 조회하지 않는다.** `/search` 로 보낼 때 `projectKey` 를
  싣지 않는다. 목적지가 이미 **4단 해소**(`search.tsx:480` `useResolvedActiveProject`)와
  **미해소 안내**(`search.tsx:565-566` `<ActiveProjectGate>`)를 소유하고 있다.
  상단바가 게이트를 복제하면 F4 가 남긴 관찰(*「`ActiveProjectGate` 문구가 팔레트에 복제됨 —
  drift 위험」*)을 그대로 반복하게 된다. 상시 마운트 컴포넌트에서 훅 하나를 덜 쓰는 이득도 있다(NFR2).

## 비기능 요구사항 (NFR)

- **NFR1.** 백엔드 0줄 · 마이그레이션 0 · 신규 API 0 · 신규 의존성 0.
- **NFR2.** 상단바는 모든 화면에 상시 마운트되므로 **입력 자체가 네트워크를 호출하지 않는다.**
  제출(Enter) 시에만 이동한다. 라이브 드롭다운은 이 PR 범위 밖이다(F4 팔레트가 담당).
- **NFR3.** 판별 함수는 **순수 함수**다 — DOM·라우터·네트워크 비의존. 단위 테스트로 전수 고정 가능.
- **NFR4.** 접근성 — `role="searchbox"` + `aria-label` 제공. 키보드만으로 포커스·입력·제출 가능.
- **NFR5.** WCAG AA 대비. 라이트/다크 양쪽에서 placeholder 와 테두리가 기준을 만족한다.

## API 인터페이스 (REST)

**없음.** 기존 `/issues/$key` 라우트와 `/search?q=` 라우트만 사용한다.

## 데이터 모델 변경

**없음.**

## 엣지 케이스

| # | 상황 | 기대 동작 |
|---|---|---|
| E1 | `atlas-42` (소문자) | `ATLAS-42` 로 정규화 후 이동 |
| E2 | `ATLAS-42 로그인` (키 + 텍스트) | 이슈키 패턴 **불일치**(정규식이 `^…$`) → 자유 텍스트로 처리 |
| E3 | `C:\Users\temp` | `escapeAqlString` 이 역슬래시 보존. **400 도, 조용한 0건도 아니어야 한다** |
| E4 | `로그인"버그` (따옴표) | 이스케이프돼 문법 오류가 나지 않는다 |
| E5 | 공백만 / 빈 문자열 | 제출 무시. 이동 없음 |
| E6 | 한글 조합 중 Enter | 제출 안 함 (S6) |
| E7 | 존재하지 않는 이슈키 `ZZZZ-9999` | 패턴은 맞으므로 이동하고, 이슈 상세가 404 를 처리 |
| E8 | 활성 프로젝트 0건 + 자유 텍스트 | `/search?q=…` 로 이동. 안내는 **검색 페이지의 `ActiveProjectGate`** 가 한다 (FR12) |
| E9 | 활성 프로젝트 0건 + 이슈키 | 정상 이동 — 이슈 조회는 프로젝트 무관 |
| E10 | `status = "열림"` | AQL 로 판별해 원문 통과 (S3) |
| E11 | `status` (필드명만, 연산자 없음) | AQL **아님** → 자유 텍스트로 감싼다 |
| E12 | 매우 긴 입력 (수천 자) | 잘리지 않고 그대로 전달. URL 길이 제한은 브라우저/서버 책임 |
| E13 | 로그인 전 화면 | 상단바가 마운트되지 않으므로 해당 없음 |

## 제약 조건 — 깨면 즉사하는 것 (계약 §2)

1. **`검색` 이름 분리.** 상단바=`전역 검색`, `검색`=AQL 페이지 제출 버튼 전용.
   (Maxi 확정 2026-07-28 결정 4)
2. **`aria-label` 4종 보존.** `메인 메뉴`·`관리 메뉴`·`프로젝트 뷰 전환` 은 손대지 않는다.
3. **`SHORTCUTS` 5종 동결.** M1 에 따라 이 PR 은 `shortcuts.ts` 를 **건드리지 않는다.**
4. **팔레트 QUICK_LINKS 순서 보존** — 상단바 변경이 팔레트에 파급되면 안 된다.
5. **`검색` option 보존** — `command-palette.spec.ts:292` 의 팔레트 내 `검색` 옵션은 그대로다.

## 함께 고쳐야 성립하는 것 (같은 PR 범위)

이름이 `검색` → `전역 검색` 으로 바뀌고 role 이 `button` → `searchbox` 로 바뀌므로,
아래를 **같은 PR 에서** 고치지 않으면 머지 시점에 즉시 빨간불이다.

**유닛 4곳.**

| 파일 | 줄 | 현재 단언 | 변경 |
|---|---|---|---|
| `layout/__tests__/navigation-contract.test.tsx` | 115 | `'검색'` 버튼 1개 | `전역 검색` searchbox 1개 |
| `layout/__tests__/navigation-contract.test.tsx` | 165 | `'검색'` 버튼 1개 | 〃 |
| `layout/__tests__/ShellLayout.test.tsx` | 149 | `navLabels.search` 버튼 1개 | `navLabels.globalSearch` searchbox 1개 |
| `layout/__tests__/TopBar.test.tsx` | 116 | `'검색'` 버튼 1개 **+ 클릭 시 `/search` 이동** | 개수 + **제출 동작**으로 교체 |

**E2E 5파일.** 전부 `const HEADER_SEARCH_ARIA_LABEL = '검색'` 을 **하드코딩**해 두고
「화면이 준비됐다」는 신호로 쓴다. i18n 정본을 import 하지 않아 2026-05-26 교훈을 어긴 상태다.

| 파일 | 줄 | 용도 |
|---|---|---|
| `context-shortcuts.spec.ts` | 29 · 73 | 마운트 sentinel |
| `keyboard-shortcuts.spec.ts` | 43 · 70 · 204 | 마운트 sentinel + **클릭** |
| `command-palette.spec.ts` | 61 · 79 | 마운트 sentinel |
| `search.spec.ts` | 53 · 81 | 마운트 sentinel + **클릭** |
| `detail-action-shortcuts.spec.ts` | 51 · 78 | 마운트 sentinel |

★ **role 과 name 이 둘 다 바뀌는 것이 안전장치다.** `button`+`검색` → `searchbox`+`전역 검색` 이라
잔존 참조가 조용히 통과할 수 없다. 하나라도 빠뜨리면 그 spec 이 즉시 실패한다.

**유지 대상 (건드리지 말 것).** `search.spec.ts:107,109` · `saved-filters.spec.ts:206,238,240`
(AQL 페이지 제출 버튼 `검색`) · `command-palette.spec.ts:292` (팔레트 option `검색`).

## 측정 가능한 완료 기준

1. 상단바에 `role="searchbox"` + 접근성 이름 `전역 검색` 인 입력창이 **정확히 1개** 있다.
2. 접근성 이름이 `검색` 인 **버튼**은 상단바에 **0개**, AQL 페이지에 **1개**다.
3. 판별 함수 단위 테스트가 S1·S2·S3·S4 + E1~E12 를 전수 고정한다.
4. `apps/web` 유닛 전량 통과 (현재 기준선 **8,795** / 555파일 — 증가만 허용, 감소 금지).
5. 영향 E2E 5파일 + `search` + `saved-filters` 가 **2회 연속** 통과한다.
6. `node_modules/.bin/tsc -p tsconfig.app.json --noEmit` 통과 · `eslint src` 통과.
7. `bash scripts/verify-master-plan.sh` EXIT 0.
8. `ISSUE_KEY_PATTERN` 선언이 저장소 전체에서 **1개**다 (`grep -rn "ISSUE_KEY_PATTERN\s*=" apps/web/src` → 1건).

## 시각 검증 기준 (계약 §6 — 생략 금지)

**함께 돌릴 E2E.** `context-shortcuts` · `keyboard-shortcuts` · `command-palette` · `search` ·
`detail-action-shortcuts` · `saved-filters` (파이프 금지 — `> /tmp/x 2>&1; echo "EXIT=$?"`).

**브라우저 눈확인 항목 (라이트/다크 양쪽).**

1. 상단바 입력창의 폭·정렬이 `ProjectSwitcher` 와 우측 액션 사이에서 무너지지 않는다.
2. placeholder 대비가 양쪽 테마에서 읽힌다.
3. 포커스 링이 보이고 `ring-1 ring-foreground/10` elevation 관례와 충돌하지 않는다.
4. 좁은 폭(sm 미만)에서 축약 형태가 겹치거나 잘리지 않는다.
5. 이슈키를 쳐서 Enter → 이슈 상세로 **페이지 이동이 실제로 일어난다**.
6. 활성 프로젝트 0건 상태에서 제출 → 검색 페이지의 `ActiveProjectGate` 안내가 뜬다
   (상단바가 자체 안내를 그리지 **않는** 것까지 확인).

## Brainstorming Check

**ui 경량 경로 — Phase B 스킵.** `/bts-spec` §ui 경량 경로에 따라 brainstorming 대신
`## Jira 대조`(계약 §1) + `## 제약 조건`(즉사 계약 §2) + `## 시각 검증 기준`(§6)이
sanity check 를 대신한다. 신규 화면·신규 도메인 개념이 없어 경량 경로 대상이다.
