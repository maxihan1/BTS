# FR-UX-12 F4 — Cmd+K 커맨드 팔레트 실체 검색 — 스펙

> FR: **FR-UX-12** (정본 `docs/plan/product/personalization.md §4.10`) · 승계 PR **F4** ·
> ADR [`2026-08-04-fr-ux-12-f4-command-palette-search.md`](../decisions/2026-08-04-fr-ux-12-f4-command-palette-search.md) ·
> PR #340 · type `ui` · agent `frontend-engineer` · 백엔드 0 · 마이그레이션 0 · 신규 의존성 0

## 요약

Cmd+K 팔레트에서 **슬래시 없는 입력이 실제로 무언가를 한다**. 지금은 자유 텍스트를 치면 목록이
비고 Enter 도 무반응이다. 이슈키를 치면 그 이슈로, 자유 텍스트를 치면 활성 프로젝트 안에서
전문 검색한 결과를 팔레트 안에 보여주고, Enter 로 하이라이트된 결과를 연다.

같은 PR 에서 **선재 결함 1건**을 봉합한다 — `/search <질의>` 가 실서버에서 문법 오류를 내고
있었고, MSW 목이 쿼리를 읽지 않아 E2E 가 그것을 가려 왔다.

## Jira 대조 (계약 §1 산출물)

**대응 화면.** Jira Cloud 의 커맨드 팔레트(`Cmd+K` / `Ctrl+K`) + Quick search.

| # | Jira Cloud 동작 | 근거 | BTS 현재 | 본 PR |
|---|---|---|---|---|
| J1 | **이슈키를 치면 그 이슈로 바로 간다.** *"If you type in the key of an issue, you will jump straight to that issue. For example, if you type in 'ABC-107' (or 'abc-107'), and press the Enter button, you will be redirected"* | **DC 공식** ([Quick searching](https://confluence.atlassian.com/spaces/JIRASOFTWARESERVER/pages/939938728/Quick+searching)). Cloud 팔레트 문서엔 **미기재** | 없음 (`/goto` 를 쳐야 함) | **채택** — 대소문자 무관 정규화까지(기존 `/goto` 가 `toUpperCase()` 하는 것과 동일 규칙) |
| J2 | 자유 텍스트는 **instant results** 로 즉시 뜨고, 더 보려면 **View all results** 로 전체 페이지 이동. *"you can select View all results. It will take you to another page with all the relevant search results"* | **Cloud 공식** ([Search with your keyboard](https://support.atlassian.com/jira-software-cloud/docs/search-issues-projects-and-more-with-the-your-keyboard/)) | 없음 (목록이 빔) | **채택** — 디바운스 결과 + 「모든 결과 보기」 |
| J3 | 결과 행은 **키 · 요약 · 프로젝트** 3요소 | Cloud 공식 (동일 문서) | — | **부분 채택** — v1 은 활성 프로젝트 스코프라 프로젝트명이 전 행 동일. **키 + 요약**만 (§의도적 편차 X2) |
| J4 | 검색 대상은 *"Summary, Description or any text field"* | Cloud 공식 ([Quick search](https://support.atlassian.com/jira-software-cloud/docs/perform-a-quick-search/)) | AQL `text` 가 대응 (가상 FTS 필드, `~` 만 허용 — FR-SR-04 ADR D4) | **채택** — `text ~ "…"` |
| J5 | **입력 중에는 팔레트가 안 열린다.** *"if you're editing something in a free text field (like description, comment, etc.), the command palette will not open"* | Cloud 공식 | F11 이 `.` 에 대해 이미 구현 (S10 `E1 입력 중 무발화`) | 범위 밖 (기존 동작 무변경) |
| J6 | **`/` 는 「명령이 아니라 항목을 검색한다」는 표시.** *"The forward slash in the search bar indicates you are **not** searching for commands, you're instead searching for work items…"* | Cloud 공식 | **정반대** — BTS 는 `/` = 슬래시 명령 | **의도적 편차 X1** (아래) |

### 의도적 편차

- **X1. `/` 의 의미를 Jira 와 반대로 유지한다.** Jira 는 `/` = 검색 모드, BTS 는 `/` = 명령
  (`/goto`·`/search`·`/issue`). 뒤집으면 FR-UX-04 ADR D3 · `commands.ts` · 유닛
  (`commands.test.ts`·`CommandPalette.test.tsx`) · E2E(`command-palette.spec.ts` S1~S5)가 동시에
  깨진다. **사용자가 체감하는 결과는 같다** — 텍스트를 치면 이슈가 나온다. 다른 것은 명령을
  부르는 문법뿐이고, VS Code·Linear·GitHub 도 `/`·`>` 를 명령 접두사로 쓴다.
- **X2. 결과 행에서 프로젝트명을 뺀다.** v1 이 활성 프로젝트 스코프라 모든 행이 같은 값을 갖는다.
  전 행 동일한 열은 정보가 아니라 소음이다. cross-project 는 3층 차단으로 범위 밖(§제약).
- **X3. 이슈키 즉시매칭 근거는 Jira Cloud 가 아니다.** J1 은 **DC 문서에만** 있고 Cloud 팔레트
  문서에는 없다. 그래도 채택하는 근거는 **정본 §4.10 이 명시**한 것 + 조작 효율(키를 아는
  사용자가 `/goto` 5글자를 더 칠 이유가 없다)이다. "Jira Cloud 가 그렇게 한다"고 주장하지 않는다.

## 사용자 시나리오 (Given-When-Then)

### S1. 이슈키 즉시매칭

```
Given  팔레트가 열려 있다
When   `ATLAS-12` 를 입력한다
Then   결과 영역 최상단에 그 이슈 1건(키 + 요약)이 뜬다
And    Enter 를 누르면 `/issues/ATLAS-12` 로 이동하고 팔레트가 닫힌다
```

### S2. 이슈키 대소문자 무관

```
Given  팔레트가 열려 있다
When   `atlas-12` 를 입력한다
Then   S1 과 동일하게 동작한다 (대문자로 정규화 — 기존 `/goto` 규칙과 동일)
```

### S3. 존재하지 않는 이슈키

```
Given  팔레트가 열려 있다
When   `ATLAS-99999` (없는 키)를 입력한다
Then   이슈 항목은 뜨지 않고, 같은 문자열로 자유 텍스트 검색한 결과가 뜬다
And    검색 결과도 0건이면 "결과가 없습니다" 안내가 뜬다
```

### S4. 자유 텍스트 검색

```
Given  활성 프로젝트가 ATLAS 로 해소돼 있고 팔레트가 열려 있다
When   `로그인` 을 입력한다
Then   입력이 멎고 250ms 후 `text ~ "로그인"` AQL 이 활성 프로젝트 스코프로 1회 호출된다
And    결과가 키 + 요약 목록으로 뜬다 (상한 있음 — §NFR3)
And    Enter 를 누르면 하이라이트된 결과의 이슈로 이동한다
```

### S5. 모든 결과 보기 (탈출구)

```
Given  S4 상태에서 결과가 떠 있다
When   목록 끝의 「모든 결과 보기」 항목을 선택한다
Then   `/search?q=text ~ "로그인"&projectKey=ATLAS` 로 이동한다
And    AQL 검색 페이지가 그 질의로 즉시 검색을 실행한다
And    팔레트가 닫힌다
```

### S6. 빈 입력 — 기존 동작 무회귀 (즉사 계약)

```
Given  팔레트가 열려 있다
When   아무 것도 입력하지 않는다
Then   바로가기 4개가 `내 이슈`(0)/`검색`(1)/`대시보드`(2)/`받은 편지함`(3) 순서로 뜬다
And    명령 힌트 3개(`/goto`·`/search`·`/issue`)가 뜬다
And    방향키+Enter 만으로 바로가기를 선택할 수 있다
```

### S7. 슬래시 명령 — 기존 동작 무회귀 + 선재 결함 봉합

```
Given  팔레트가 열려 있다
When   `/search 로그인 버그` 를 입력하고 Enter 를 누른다
Then   `/search?q=text ~ "로그인 버그"` 로 이동한다   ← 봉합. 이전엔 q=로그인 버그 (문법오류)
And    AQL 검색 페이지가 결과를 렌더한다 (실서버에서도 200)
```

```
Given  팔레트가 열려 있다
When   `/goto ATLAS-12` 를 입력하고 Enter 를 누른다
Then   `/issues/ATLAS-12` 로 이동한다 (이슈키 경로로 새지 않는다 — 판별 순서 계약)
```

### S8. 활성 프로젝트 미해소

```
Given  접근 가능한 프로젝트가 0개다 (useResolvedActiveProject → 'empty')
When   `로그인` (자유 텍스트)을 입력한다
Then   검색을 호출하지 않고 "프로젝트를 먼저 선택하세요" 안내가 뜬다
When   `ATLAS-12` (이슈키)를 입력한다
Then   S1 과 동일하게 동작한다 (이슈키는 프로젝트에 의존하지 않는다)
```

## 기능 요구사항 (FR)

| ID | 요구사항 |
|---|---|
| **FR1** | 팔레트 입력을 3계층으로 판별한다 — ①`/` 로 시작하면 슬래시 명령(기존 `parseCommand`) ②이슈키 정규식(`^[A-Z][A-Z0-9]*-\d+$`, 대문자 정규화 후) 일치 ③그 외 자유 텍스트 |
| **FR2** | 판별 순서는 ①→②→③ 로 고정한다. `/goto ATLAS-1` 이 이슈키 경로로 새면 안 된다 |
| **FR3** | 이슈키 판별 시 해당 이슈를 조회해 결과 최상단에 키 + 요약으로 노출한다. 조회 실패(404/403)면 항목을 노출하지 않고 자유 텍스트 경로로 넘어간다 |
| **FR4** | 자유 텍스트는 250ms 디바운스 후 `text ~ "<이스케이프된 질의>"` 를 활성 프로젝트 스코프로 검색한다 |
| **FR5** | AQL 문자열 리터럴 이스케이프를 래핑 함수가 책임진다 — `"` 와 `\` 를 이스케이프한다 |
| **FR6** | 결과 목록 끝에 「모든 결과 보기」 항목을 두고, 선택 시 `/search` 로 같은 질의를 넘긴다 |
| **FR7** | 활성 프로젝트가 `ready` 가 아니면 자유 텍스트 검색을 호출하지 않고 안내를 표시한다. 이슈키 경로는 계속 동작한다 |
| **FR8** | 빈 입력 시 기존 바로가기 4개 + 명령 힌트 3개를 순서 그대로 노출한다 (무회귀) |
| **FR9** | `/search <질의>` 슬래시 명령이 FR5 의 같은 래핑 함수를 거쳐 유효한 AQL 로 나간다 (선재 결함 봉합) |
| **FR10** | 결과 렌더는 `components/ui/command.tsx` 래퍼를 소비한다 (`CommandInput`/`CommandList`/`CommandGroup`/`CommandItem`/`CommandEmpty`). 다이얼로그 셸은 래퍼에 없으므로 `CommandPrimitive.Dialog` 를 유지한다 |
| **FR11** | MSW `search/aql` 핸들러가 쿼리를 실제로 검사해, 필드/연산자 형태가 아닌 bare 텍스트에 `SEARCH_SYNTAX_ERROR` 를 반환한다 (가짜 그린 제거) |
| **FR12** | 결과 0건이면 "결과가 없습니다" 를 표시한다 (`CommandEmpty`) |
| **FR13** | 검색 실패(400/403/500)는 팔레트 안에서 안내로 표시하고, 팔레트를 닫거나 라우팅하지 않는다 |

## 비기능 요구사항 (NFR)

| ID | 요구사항 | 측정 |
|---|---|---|
| **NFR1** | 디바운스 250ms — `LabelAutocompleteInput` 선례와 동일 상수 관례 | 타이머 테스트로 1회 호출 확인 |
| **NFR2** | 키보드만으로 완결 — 방향키 이동 + Enter 실행, 마우스 불필요 | E2E 에서 `page.keyboard` 만으로 S1·S4·S5 완주 |
| **NFR3** | 팔레트 결과 상한 = **7건**. 초과분은 「모든 결과 보기」로 유도 | `searchAql({size: 7})` |
| **NFR4** | IME 조합 중 Enter 는 실행하지 않는다 (기존 가드 유지) | `isComposing` 단언 |
| **NFR5** | 접근성 — 결과는 `role="option"`, 목록은 cmdk 가 부여하는 `role="listbox"`. 하이라이트는 `data-selected` | 유닛에서 role 조회 |
| **NFR6** | 라이트/다크 양쪽 눈확인 (계약 §6) | 브라우저 확인 결과를 게이트 2 요약에 기록 |

## API 인터페이스 (REST)

**신규 0.** 기존 2종만 소비한다.

| 용도 | 엔드포인트 | 클라이언트 |
|---|---|---|
| 이슈키 즉시매칭 | `GET /api/v1/issues/{key}` | `fetchIssue(key)` (`api/issues.ts:478`) |
| 자유 텍스트 검색 | `POST /api/v1/search/aql` | `searchAql({projectKey, query, page, size})` (`api/search.ts:320`) |

## 데이터 모델 변경

**없음.** 마이그레이션 0. 영속 상태 0 (팔레트 입력은 컴포넌트 지역 상태).

## 엣지 케이스

| ID | 상황 | 처리 |
|---|---|---|
| **E1** | `/goto ATLAS-1` — 슬래시 + 이슈키 | 슬래시 경로가 이긴다 (FR2). 이슈키 경로로 새지 않는다 |
| **E2** | `ATLAS-` (프로젝트 키만) | 이슈키 정규식 불일치 → 자유 텍스트 검색 |
| **E3** | `12` (숫자만) | 이슈키 정규식 불일치 → 자유 텍스트 검색. (Jira DC 의 "현재 프로젝트 번호 점프" 는 미채택 — 정본 범위 밖) |
| **E4** | 질의에 `"` 포함 (`로그인"버그`) | FR5 이스케이프. AQL 문자열 리터럴을 탈출하지 않는다 |
| **E5** | 질의에 `\` 포함 | FR5 이스케이프 |
| **E6** | 이슈키가 존재하나 **권한 없음**(403) | 항목 미노출 + 자유 텍스트 경로 (S3 와 동일 — 존재 여부를 노출하지 않는다) |
| **E7** | 검색 in-flight 중 입력 변경 | 디바운스가 이전 타이머를 취소. 응답 경합은 TanStack Query 의 queryKey 로 격리 |
| **E8** | 활성 프로젝트 `loading` | 자유 텍스트 검색 보류(호출 0), 안내 표시. 해소되면 재개 |
| **E9** | 활성 프로젝트 `error` | 안내 + 재시도 경로(`useResolvedActiveProject.retry`) |
| **E10** | 팔레트를 닫았다 다시 열기 | 입력 초기화(기존 `useEffect(!open)` 유지) → 빈 입력 화면 |
| **E11** | 결과 0건 | `CommandEmpty` 안내. 「모든 결과 보기」는 계속 노출(전체 페이지에선 더 나올 수 있다) |
| **E12** | 공백만 입력 (`"   "`) | trim 후 빈 문자열 → 빈 입력과 동일 취급(검색 호출 0) |

## 제약 조건

- **백엔드 3층 차단** — `AqlSearchRequest.kt:36` `projectKey @NotBlank` · `SearchController.kt:167`
  blank→400 · `AqlFields.kt:73` `PLANNED_FIELDS` 에 `project`·`assignee`. 따라서 v1 은 **활성
  프로젝트 스코프**다. 프로젝트 무관 검색은 범위 밖.
- **AQL `text` 연산자 제약** — `text` 는 가상 FTS 필드로 `~` 만 허용(`=`·`!=`·`IN`·`NOT IN` 불가,
  FR-SR-04 ADR D4). 정렬 불가(`SORTABLE_FIELDS` 미포함).
- **FR-UX-04 ADR 경계** — D1 프론트 전용(백엔드 executor 미도입) · D3 명령 3종 네비게이션.
  본 PR 은 명령을 추가하지 않는다.
- **즉사 계약 (패리티 계약 §2)**
  - 팔레트 `QUICK_LINKS` 순서 — 빈 입력 시 바로가기 4개와 순서 보존.
  - `검색` 이름 분리 — **팔레트 안에 `검색` 이라는 새 접근성 이름을 만들지 않는다.**
    `command-palette.spec.ts:259` 가 팔레트 option `'검색'` 을 `exact:true` 로 잡고 있어
    같은 이름이 하나 더 생기면 strict mode 로 즉사한다.
  - 상단바 `navLabels.search` 봉인(유닛 6 + e2e 3파일, 「버튼 정확히 1개」 단언 3건)은 **F13 소관**.

## 시각 검증 기준 (ui 경량 경로 필수)

**동반 실행할 기존 E2E.**
`command-palette.spec.ts`(272행) · `keyboard-shortcuts.spec.ts` · `detail-action-shortcuts.spec.ts`
(3파일 모두 팔레트 표면을 건드린다 — 계약 §5 사전 grep 결과) · `search.spec.ts` ·
`saved-filters.spec.ts`(`검색` 제출 버튼 계약).

**눈확인 항목 (라이트/다크 양쪽).**
1. 빈 입력 — 바로가기 4개 + 명령 힌트 3개, 순서와 간격.
2. 자유 텍스트 입력 중 — 결과 목록, 하이라이트 색이 `--bg-selected`(라이트 `#E9F2FF` / 다크
   `#082145`)로 렌더되는지. **래퍼 채택으로 회색 `--accent` 에서 바뀌는 지점이라 회귀 위험 최대.**
3. 래퍼 `CommandInput` 이 추가하는 돋보기 아이콘이 입력 문구를 밀지 않는지.
4. 목록 최대 높이 320→300px 변경이 스크롤을 어색하게 만들지 않는지.
5. 결과 0건 · 검색 실패 · 프로젝트 미해소 3가지 안내 상태.

## 측정 가능한 완료 기준

- [ ] S1~S8 전 시나리오가 E2E 로 검증된다 (신규 스펙 + 기존 `command-palette.spec.ts` 갱신)
- [ ] FR2 판별 순서가 테스트로 강제된다 — `/goto ATLAS-1` 이 이슈키 경로로 새지 않음
- [ ] ADR D-5 경계 가드 3종이 존재한다 — ①`commands.ts` 가 신규 판별 레이어를 import 하지 않음
      ②`ParsedCommand` 유니온 6갈래 동결 ③호출 순서 계약
- [ ] FR11 — MSW 핸들러가 bare 텍스트에 `SEARCH_SYNTAX_ERROR` 를 반환하고, **그 핸들러 아래에서**
      S7 이 통과한다 (봉합 전 코드로는 red 여야 한다 — 비-공허 확인)
- [ ] 즉사 계약 무회귀 — `command-palette.spec.ts:259` 의 바로가기 4개/순서 단언 통과
- [ ] 기존 E2E 5파일 동반 실행 green
- [ ] 유닛 전량 green · `tsc --noEmit` · `eslint src` 통과
- [ ] 라이트/다크 눈확인 5항목 완료, 결과를 게이트 2 요약에 기록
- [ ] `bash scripts/verify-master-plan.sh` EXIT 0 · FR 수 불변 139
- [ ] `personalization.md §4.10` D1~D5 `[x]`, D6/D7 `[ ]` 유지

## Brainstorming Check

**ui 경량 경로 적용 (Maxi 확정 2026-08-03)** — `type == ui` + 기존 화면 수정(신규 라우트 0 ·
신규 엔티티 0 · glossary 신규 용어 0)이라 Phase B brainstorming 을 스킵하고, `## Jira 대조`
(계약 §1) + 즉사 계약(§2) 교차 + `## 시각 검증 기준` 이 sanity check 를 대신한다.

office-hours 는 **"fully formed plan" 경로**로 실행했다(Phase 2 수요 검증 6대 질문 스킵 —
이미 승인된 유지보수 FR 이라 무의미). Phase 3 전제 검증 5건 + Phase 4 대안 3종은 수행했고,
대안 C(부분 래퍼 채택)는 **토큰 실측으로 탈락**했다 — `--accent`(#F1F2F4 회색)와
`--bg-selected`(#E9F2FF 파란 tint)가 다른 색이라 한 팔레트 안에서 하이라이트가 항목마다 달라진다.
