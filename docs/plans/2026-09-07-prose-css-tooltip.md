# 에디터 전수 결함 수정 — prose CSS 부재·이슈 생성폼 리치에디터·툴바 단축키 실배선·Tooltip 교체

> 티어: T2
> slug: prose-css-tooltip
> type: backend
> agent: backend-engineer
> 생성: 2026-09-07

## Brief

Maxi 원문 (2026-09-07):

1. 이슈 생성할때는 에디터 서식이 안나옴
2. 에디터 서식중 동작하지 않는 기능과 지라클라우드와 방식이 다른 것들이 있음 — 말머리 숫자서식 동작 안함, 코드내용 등이 지라 클라우드와 작동 방식이 다름
3. 에디터 마우스 후버 시 설명이 있어야 함
4. 에디터 전수 조사해서 이슈사항 수정해

### 착수 전 실측 (2026-09-07)

| # | 결함 | 실측 근거 |
|---|---|---|
| ① | `prose` 클래스가 통째로 무효 | `@tailwindcss/typography` 가 `apps/web/package.json` 에 없고 `index.css` 에 `@plugin` 도 없다. 빌드 산출 `apps/web/dist/assets/index-*.css` 에 `.prose` **0회** · `.ProseMirror` **0회** (`grep -c` 실측). Tailwind v4 `preflight.css:197-200` 이 `ol,ul{list-style:none}` 이라 **번호·불릿 마커가 안 보인다** = Maxi 지적 2번 「말머리 숫자서식 동작 안함」의 실체. h1~h6 은 `font-size:inherit` 로 리셋, `pre`·`code`·`blockquote`·`table` 도 무스타일 = 「코드내용이 지라와 다름」. 적용처 3곳: `RichTextEditor.tsx:98`(입력창) · `IssueDescription.tsx:181`(본문 읽기) · `CommentSection.tsx:452`(댓글 읽기) |
| ② | 이슈 생성 폼에 리치 에디터가 없다 | `IssueCreateBasicFields.tsx:128` 이 plain `<Textarea rows={4}>`. 근본 원인은 백엔드 — `CreateIssueRequest.kt` 에 `descriptionHtml` 필드가 **없다**(PATCH `UpdateIssueRequest.kt:89` 에만 존재). 생성 API 가 HTML 을 못 받으므로 프론트 단독 수정 불가 |
| ③ | 툴바 툴팁이 거짓 단축키를 표기 | `node_modules` 실측 — `@tiptap/extension-horizontal-rule@3.31.0` · `@tiptap/extension-link@3.31.0` 에 `addKeyboardShortcuts` 가 **없다**. 그런데 `RichTextToolbar.tsx` 는 구분선 `⌘⇧-` · 링크 `⌘⇧K` 로 표기한다. 인라인 코드는 Tiptap 기본 `Mod-e` 를 그대로 `⌘E` 로 적었으나 Jira J8 명세는 `⌘⇧M`. 표·이미지는 미구현 `/` 퀵인서트를 표기 |
| ④ | 호버 설명이 native `title` 뿐 | `apps/web/src/components/ui/tooltip.tsx` 프리미티브가 존재하는데 앱 전체 사용 **0회**, `TooltipProvider` 미마운트 (grep 실측) |

### 범위 결정 (Maxi · AskUserQuestion 2026-09-07)

- ①②③④ **전부** 수정 · 백엔드 API 확장 포함이라 **T2**
- Jira J8 미구현 3종(`/` 퀵인서트 · `:` 이모지 · 코드블록 언어선택)은 **이번 범위 제외**

### classify

`{ slug: prose-css-tooltip, type: backend, agent: backend-engineer, primary_bc: issue-tracking, tier: T2 }`

★classify 의 제목 기반 판정은 `T1` 이었다 — 제목에 백엔드 신호가 없기 때문이다. 실제 변경 경로가
`backend/modules/issue-tracking/src/main/**`(=`BE_MAIN`·`API`)을 포함하므로 `--tier T2` 로 덮었다
(판정 5문 ② Maxi 지정 우선).

## Jira 대조

계약 §1-0 **재사용 먼저**. `grep -rlE "## Jira 대조|### 지라 근거" docs/specs/ docs/plans/ TODOS.md` 로
에디터 표면을 이미 조회한 문서를 찾아 **J8 행을 출처·조회일 그대로 승계**했다
(`docs/plans/2026-09-04-issue-detail-jira-parity.md:51` · 조회일 2026-09-04).
**이번 변경이 새로 건드리는 조작 3개만 추가 조회**했다 — 조회일 **2026-09-07**.

| # | 항목 | Jira Cloud 실물 | 출처 |
|---|---|---|---|
| J8 | 에디터 서식 *(승계 · 2026-09-04)* | Bold `**` · Italic `*` · Strikethrough `~~` · Monospace `` ` `` · Heading 1~6 · 목록 3종(bullet · numbered · action item `[]`) · 인용 `>` · 구분선 `---` · 코드블록 ` ``` ` · 링크 · 이미지 `![]()` | [마크다운·단축키](https://support.atlassian.com/jira-software-cloud/docs/markdown-and-keyboard-shortcuts/) (Cloud) |
| J22 | **단축키 실물 재조회** *(신규 · 2026-09-07)* | 인라인 코드 `⌘⇧M` · 구분선 `⌘⇧-` · 링크 `⌘⇧K` · 글머리 목록 `⌘⇧8` · 번호 목록 `⌘⇧7` · **인용 `⌘⇧9`**. **액션 아이템(체크박스)은 단축키가 없다** — 마크다운 `[]` + Space 만("press Space after, use Tab to indent"). **코드블록·제목도 단축키가 없다** — 각각 ` ``` ` 과 `#`~`######` 마크다운뿐 | [마크다운·단축키](https://support.atlassian.com/jira-software-cloud/docs/markdown-and-keyboard-shortcuts/) (Cloud) |
| J23 | **생성 다이얼로그 본문** *(신규 · 2026-09-07)* | "**Description** — Add details about the work item. The description field expands vertically to accommodate **long descriptions, code blocks, table edits, and pasted images**." → 생성 화면도 상세 화면과 **같은 리치 에디터**다 | [작업 항목·하위 작업 만들기](https://support.atlassian.com/jira-software-cloud/docs/create-a-work-item-and-a-subtask/) (Cloud) |
| J24 | **호버 설명** *(신규 · 2026-09-07)* | Jira 문서에 툴바 툴팁을 규정한 절이 없다 → 계약 §1-5 대로 **대응 없음 — ADS v2 `Tooltip` 준용**. "A tooltip briefly describes an interactive element on **mouse hover or keyboard focus**." — 마우스 호버**와 키보드 포커스** 둘 다가 계약이다 | [ADS Tooltip](https://atlassian.design/components/tooltip/usage) (ADS v2) |

### J22 가 뒤집은 것 — 기억으로 썼으면 틀렸다

착수 시 나는 「구분선 `⌘⇧-` · 링크 `⌘⇧K` 는 툴바가 지어낸 표기」로 판단했다. **틀렸다.**
그 둘은 **Jira 실물 그대로**이고, 틀린 것은 「Tiptap 이 그 키맵을 안 준다」는 쪽이다 —
표기는 옳고 **배선이 없다**. 처방이 「표기를 지운다」에서 「키맵을 만든다」로 뒤집힌다.

그리고 재조회가 **착수 시 못 봤던 결함 1건을 새로 드러냈다**.

| | Jira Cloud (J22) | BTS 현재 (`RichTextToolbar.tsx`) | 판정 |
|---|---|---|---|
| `⌘⇧9` | **인용(blockquote)** | **체크박스 목록**(TaskList) | ★**충돌** — 같은 키가 다른 것을 한다 |
| 인용 | `⌘⇧9` | `⌘⇧B` (Tiptap Blockquote 기본) | Jira 에 없는 키를 표기 |
| 체크박스 목록 | 단축키 **없음** (`[]` 마크다운만) | `⌘⇧9` (Tiptap TaskList 기본) | Jira 에 없는 키를 표기 |

Tiptap `@tiptap/extension-list@3.31.0` 의 TaskList 가 `Mod-Shift-9` 를 선점해
**Jira 사용자가 인용을 누르면 체크박스가 나온다**. 이것이 Maxi 지적 2번
「지라 클라우드와 방식이 다른 것들이 있음」의 두 번째 실체다.

### 의도적 편차

| # | 편차 | 근거 |
|---|---|---|
| **X-E1** | `/` 퀵인서트 · `:` 이모지 · 코드블록 언어 선택 **미구현 유지** | Maxi 결정(2026-09-07 AskUserQuestion) — 이번 범위 밖. 기존 `X3`(이모지 미구현)의 연장이다. 툴바가 표·이미지 툴팁에 적어 둔 거짓 `/` 표기는 **제거**한다 |
| **X-E2** | 인용에 `⌘⇧B` 를 **추가 키로 남긴다** (정식 표기는 Jira 와 같은 `⌘⇧9`) | Tiptap Blockquote 확장이 기본 제공하는 키다. 끄면 확장 기본값을 거스르는 코드가 늘고 얻는 것이 없다. **툴팁에는 `⌘⇧9` 만 적어** 사용자가 배우는 키를 Jira 와 일치시킨다 |
| **X-E3** | 체크박스 목록 툴바 버튼은 **유지**하되 툴팁에서 단축키 표기를 **뺀다** | J22 상 Jira 에 단축키가 없다. 버튼까지 없애면 마우스 사용자가 `[]` 마크다운을 외워야 한다 — Jira 도 툴바 버튼은 준다(J8 「목록 3종」) |
| **X-E4** | 저장 포맷은 정화된 HTML (ADF 아님) | 기존 `X1` 승계 — `docs/plans/2026-09-04-issue-detail-jira-parity.md:57` |

## 도메인 정리

- **BC**: `issue-tracking` 단일. 프론트 `apps/web` 은 BC 격리 대상이 아니다(옵션 C 패턴 — 같은 BC 의 view layer).
- **영향 엔티티**: `Issue` — 다만 **도메인 클래스는 건드리지 않는다**(아래 D-2 참조). DB 는 `issues.description` · `issues.description_html` 두 컬럼(V039)으로 **이미 존재**한다 → **마이그레이션 없음 = T3 아님**.
- **새 용어**: 없음. `descriptionHtml` · `sanitizeHtml` · `BodyPatch` 는 V039 가 이미 도입한 기존 용어다.
- **관련 ADR**: **없음**. `docs/adr/` · `docs/decisions/` 를 `에디터|editor|tiptap|서식|typography|tooltip` 로 grep 해 에디터 표면을 다룬 결정 문서가 0건임을 확인했다. 가장 가까운 선행 결정은 ADR 이 아니라 plan 인 `docs/plans/2026-09-04-issue-detail-jira-parity.md`(편차 X1 — 저장 포맷을 ADF 가 아닌 정화 HTML 로) 이고, 이번 변경은 그 결정을 **따른다**.
- **기존 결정 충돌**: 없음.

## 스펙

### 사용자 시나리오 (Given-When-Then)

| # | Given | When | Then |
|---|---|---|---|
| S1 | 이슈 상세에서 본문을 편집 중이다 | 툴바의 「번호 목록」을 누르고 세 줄을 쓴다 | 화면에 **1. 2. 3.** 이 보인다 (현재는 마커가 없어 들여쓰기만 된 세 줄로 보인다) |
| S2 | 본문에 코드 블록과 인용과 표가 있다 | 저장 후 읽기 모드로 돌아온다 | 코드 블록은 모노 폰트 + 배경, 인용은 좌측 선 + 들여쓰기, 표는 셀 테두리를 갖는다 |
| S3 | 새 이슈를 만든다 | 「만들기」 다이얼로그의 본문에 굵게·목록·코드 블록을 넣는다 | 상세 화면과 **같은 리치 에디터**가 뜨고, 저장 후 상세에서 서식이 그대로 보인다 (J23) |
| S4 | 새 이슈를 만든다 | 본문을 **비운 채** 제출한다 | 서버가 프로젝트 템플릿으로 채운다 — FR-TM-01 이 그대로 산다 (**E4** 참조) |
| S5 | Jira 를 쓰던 사용자가 본문을 편집한다 | `⌘⇧9` 를 누른다 | **인용**이 된다 (현재는 체크박스 목록이 나온다 — J22 충돌) |
| S6 | 본문을 편집한다 | `⌘⇧-` · `⌘⇧K` · `⌘⇧M` 을 누른다 | 각각 구분선 · 링크 입력 · 인라인 코드가 된다 (현재는 **아무 일도 안 일어난다**) |
| S7 | 툴바 아이콘에 마우스를 올린다 / 키보드로 포커스한다 | — | 이름 + 실제 동작하는 단축키가 담긴 툴팁이 뜬다 (J24 · 현재는 native `title` 이라 호버만·1초 지연·포커스 시 안 뜸) |
| S8 | 댓글을 읽는다 | 목록·코드가 든 댓글을 본다 | 본문과 **같은 서식**으로 보인다 (같은 CSS 를 공유) |

### 기능 요구사항 (FR)

기존 FR 에 붙는 결함 수정이다. **새 FR 을 만들지 않는다.**

| FR | 무엇이 붙나 |
|---|---|
| **FR-IS-01** (이슈 생성) | 생성 요청이 `descriptionHtml` 을 받는다. 리치 에디터로 만든 본문이 생성 시점에 저장된다 |
| **FR-IS-04** (이슈 수정 · 본문) | 저장된 HTML 이 **화면에 서식으로 보인다** — 지금까지 저장은 됐지만 CSS 가 없어 안 보였다 |
| **FR-TM-01** (프로젝트 템플릿) | 빈 본문 판정이 HTML 입구에서도 성립한다 (**E4**) |
| **FR-CO-01** (댓글) | 댓글 읽기가 본문과 같은 서식을 받는다 |

### 비기능 요구사항 (NFR)

| # | 요구 | 판정 |
|---|---|---|
| N1 | **에디터가 만들 수 있는 태그 = 서버가 허용하는 태그 = CSS 가 스타일을 주는 태그**. 셋이 갈리면 「저장했는데 안 보인다」가 재발한다 | 판별식 `rich-text-style-coverage.test.ts` 가 서버 allowlist ↔ CSS 셀렉터 **차집합 0** 을 pre-push 에서 강제 |
| N2 | 툴팁에 적힌 단축키는 **전부 실제로 동작**한다 | 판별식이 툴바 표기 ↔ 키맵 등록을 차집합 0 으로 대조 |
| N3 | 라이트·다크 두 테마에서 대비가 유지된다 | 색은 기존 ADS 토큰만 쓴다. 임의 hex·알파 금지 |
| N4 | 생성 경로와 수정 경로의 본문 처리 규약이 **하나** | 두 경로가 같은 `BodyPatch` 해석 함수를 통과한다 |

### API 인터페이스 (REST)

`POST /api/v1/issues` — `CreateIssueRequest` 에 필드 1개 추가.

```kotlin
@field:Size(max = IssueTextConstraints.DESCRIPTION_MAX, message = "descriptionHtml은 32767자 이하여야 합니다.")
val descriptionHtml: String? = null,

// UpdateIssueRequest.isBodyExclusive 와 **문자 단위로 같은 술어**여야 한다.
// 갈리는 순간 「생성은 통과, 수정은 400」 비대칭이 생긴다 — 라벨 검증에서 이미 겪은 양식이다.
@get:AssertTrue(message = "description 과 descriptionHtml 은 동시에 보낼 수 없습니다.")
@get:JsonIgnore
val isBodyExclusive: Boolean get() = description == null || descriptionHtml == null
```

**본문 해석표** — 수정 경로 `resolveBodyPatch` 의 표(`IssueApplicationService.kt:2160` KDoc)를 생성으로 확장한다.

| 입구 | `description` 컬럼 | `description_html` 컬럼 | 템플릿 fallback |
|---|---|---|---|
| `descriptionHtml` 이 **내용 있음** | `NULL` | `sanitizeHtml(입력)` | **안 탄다** |
| `descriptionHtml` 이 **빈 문서**(`<p></p>` 등) | 템플릿 결과 | `NULL` | **탄다** (E4) |
| `description` 이 non-blank | 원문 | `NULL`(읽기 fallback 이 렌더) | 안 탄다 |
| `description` 이 blank / 둘 다 미전달 | 템플릿 결과 | `NULL` | 탄다 |
| 둘 다 non-null | — | — | **400** `isBodyExclusive` |

### 데이터 모델 변경

**없다.** `issues.description_html` 은 V039 가 이미 만들었고 `IssueRepository` 의 수정 경로가 이미 쓴다.
생성 경로만 그 컬럼에 값을 넣는 길이 없었을 뿐이다. **Flyway 마이그레이션 0건 → T3 승격 사유 없음.**

### 설계 결정

| # | 결정 | 근거 |
|---|---|---|
| **D-1** | CSS 는 `@tailwindcss/typography` 를 설치하지 않고 **`index.css` 에 `.rich-text` 블록을 직접 쓴다** | ①플러그인의 스타일 대상 목록은 **서버 allowlist 와 무관하게 정해진다** — 두 목록이 서로를 검사하지 않는 그 양식이고, 플러그인 내부를 파싱하는 판별식은 버전업마다 깨진다. ②`input[type=checkbox]`(체크박스 목록) · `span.mention` 은 `prose` 가 다루지 않아 어차피 보완 CSS 가 필요하다. ③`package.json` 을 안 건드려 `DEPS` 표면이 빠진다. ④allowlist ↔ CSS 차집합 판별식(N1)이 **성립한다** |
| **D-2** | 생성 경로의 HTML 은 **도메인 `Issue` 에 필드를 더하지 않고** `IssueRepository.insert(issue, descriptionHtml)` 인자로 흘린다 | 수정 경로가 이미 `IssueFieldPatch` 로 도메인 **밖에서** 이 컬럼을 다룬다. 도메인에 필드를 더하면 `Issue.create`·`clone`·`toIssue`·`toInsertRecord` 와 그 테스트가 연쇄로 바뀌는데, 얻는 것은 없다 — 이 값은 표현 계층 산출물이지 불변식을 갖는 도메인 속성이 아니다 |
| **D-3** | 빈 HTML 문서 판정은 **서버가 정본**이고 프론트는 보조다 | 프론트만 고치면 다른 클라이언트(모바일·API 사용자)가 같은 함정에 빠져 FR-TM-01 이 조용히 죽는다. 서버가 `<p></p>`·`<p><br></p>`·공백을 blank 로 접는다 |
| **D-4** | 인용 `⌘⇧9` 를 Jira 와 맞추기 위해 **TaskList 의 기본 키맵을 끈다** (`TaskList.extend({ addKeyboardShortcuts: () => ({}) })`) | J22 — Jira 는 액션 아이템에 단축키가 없고 `⌘⇧9` 는 인용이다. 두 확장이 같은 키를 다투게 두면 등록 순서라는 **보이지 않는 규칙**이 동작을 정한다 |

### 엣지 케이스

| # | 케이스 | 처리 |
|---|---|---|
| **E1** | 저장은 되는데 화면에 안 보이는 태그가 남는다 | N1 판별식이 차집합으로 잡는다. 서버 allowlist 30태그 전량이 대상 — `h1`~`h6` `strong` `em` `b` `i` `u` `del` `s` `ul` `ol` `li` `p` `br` `hr` `pre` `blockquote` `code` `table` `thead` `tbody` `tfoot` `tr` `th` `td` `input[type=checkbox]` `img` `a` `span.mention` |
| **E2** | 툴팁이 없는 단축키를 적는다 / 있는 키를 안 적는다 | N2 판별식 |
| **E3** | 생성 폼에 `imageIssueKey` 가 없다 | 아직 이슈가 없어 첨부를 매달 곳이 없다. `RichTextEditor` 는 미전달 시 이미지 경로를 통째로 비활성하도록 **이미 설계돼 있다**(`imageIssueKey === undefined` 분기). 툴바 이미지 버튼도 안 그려진다 — 새 코드 불필요 |
| **E4** | **빈 에디터가 `<p></p>` 를 보내 FR-TM-01 템플릿이 죽는다** | ★가장 위험한 회귀. TipTap 은 빈 문서를 빈 문자열이 아니라 `<p></p>` 로 직렬화한다. 지금 서버는 `description.isNotBlank()` 로 템플릿 여부를 정하므로 HTML 입구에는 그 판정이 없다. **D-3** 대로 서버가 빈 HTML 을 접는다 |
| **E5** | 생성 시 `description` 과 `descriptionHtml` 을 둘 다 보낸다 | 400. 수정 경로와 **같은 술어** |
| **E6** | 단위 테스트가 `TooltipProvider` 없이 툴바를 렌더한다 | Radix Tooltip 은 Provider 없이도 동작하지만 delay 기본값이 갈린다. 툴바 테스트가 red 로 확인한다 |
| **E7** | `prose` 를 지우면 다른 화면이 깨진다 | 사용처는 3곳뿐(`RichTextEditor` · `IssueDescription` · `CommentSection`)이고 전부 이번 대상이다 — grep 으로 전수 확인했다 |
| **E8** | 표에 `colspan`/`rowspan` 이 없다 | 서버가 그 속성을 허용하지 않는다(의도). CSS 도 열지 않는다 |

### 제약 조건

- **BC 격리** — `issue-tracking` 1개 BC. 다른 BC 를 import 하지 않는다.
- **서버 sanitize 를 우회하지 않는다** — 생성 경로도 `MarkdownRenderer.sanitizeHtml` 을 반드시 통과한다. 이것이 XSS 단일 방어선이다.
- **`Issue` 도메인 불변** (D-2).
- **Flyway 마이그레이션 0건.**
- **ADS 토큰만** — `index.css` 에 이미 있는 CSS 변수만 쓴다. 새 색을 만들지 않는다.
- 새 UI 프리미티브를 만들지 않는다 — `ui/tooltip.tsx` 를 쓴다 (계약 §4).

### 측정 가능한 완료 기준

| # | 기준 | 검증 |
|---|---|---|
| C1 | 서버 allowlist 30태그 전량이 CSS 스타일을 받는다 | `rich-text-style-coverage.test.ts` 차집합 0 + 비-공허 짝(양쪽 하한) + 뮤테이션 red 1회 |
| C2 | 툴바 표기 단축키 전량이 실제 키맵에 등록돼 있다 | `toolbar-shortcut-coverage.test.ts` 차집합 0 |
| C3 | 생성 API 가 `descriptionHtml` 을 저장한다 | Testcontainers 통합 테스트 — 생성 후 단건 조회에 서식이 실린다 |
| C4 | 빈 HTML 본문이 템플릿 fallback 을 탄다 | 백엔드 테스트 — `<p></p>` 제출 시 템플릿 내용이 저장된다 (E4) |
| C5 | `description` + `descriptionHtml` 동시 전달이 400 | 백엔드 테스트 |
| C6 | 생성 폼에 리치 에디터가 뜨고 서식이 왕복한다 | E2E — 생성 다이얼로그에서 목록 입력 → 상세에서 `<ol><li>` 확인 |
| C7 | `⌘⇧9` 가 인용이다 | 프론트 단위 테스트 |
| C8 | 툴팁이 호버·**키보드 포커스** 둘 다에서 뜬다 | 프론트 단위 테스트 (J24 계약) |
| C9 | 번호 목록 마커가 실제로 보인다 | 눈확인 1회 + E2E 시각 |

## Sanity Check

스펙을 4항목(①누락 요구사항 ②모호한 표현 ③가정 누락 ④엣지 케이스 미커버)으로 스스로 흔들었다.
**gap 5건.** 그중 **1건은 이 PR 이 만든 것이 아닌 선재 결함**이라 Maxi 결정 대상이다.

### ✅ 흔들었으나 문제 없음 — 검색 인덱스

「HTML 로 저장하면 검색이 옛 본문을 긁지 않나」를 의심해 마이그레이션을 실측했다.
`V039__issue_description_html.sql:44-50` 이 `description_plain` 을 **STORED generated column**
(`GENERATED ALWAYS AS (issue_body_plain(description_html, description)) STORED`)으로 만들어 두었고,
같은 파일이 `search_vector` 도 **같은 함수**를 호출한다. 즉 HTML 을 넣는 순간 DB 가 평문을
파생하고 FTS·trigram 이 함께 따라온다 — **앱이 할 일이 없다.** 스펙 「데이터 모델 변경: 없다」가 참이다.

### ❓ 발견 1 (스스로 보강) — 「빈 문서」 판정식이 모호했다

E4 에 「`<p></p>` 등」이라고만 적었다. "등"이 판정식이 아니다. **판정식을 못박는다.**

> 태그를 모두 제거하고 `&nbsp;`(U+00A0)를 공백으로 접은 뒤 `trim()` 했을 때 빈 문자열이고,
> **동시에** `<img` 를 포함하지 않으면 빈 문서다.

`<img>` 예외가 필요한 이유 — 이미지 한 장만 붙인 본문은 텍스트가 0자이지만 **빈 본문이 아니다.**
이 조건을 빠뜨리면 「이미지만 넣고 저장했더니 프로젝트 템플릿이 덮어썼다」가 된다.

### ❓ 발견 2 (스스로 보강) — 생성 폼의 길이 카운터가 스펙에 없었다

상세 편집에는 `TextLengthCounter` 가 붙어 있는데(`DESCRIPTION_MAX` 32,767자) 생성 폼에는
없었다. Textarea 시절에는 서버 `@Size` 가 400 을 내면 그만이었지만, **HTML 은 보이는 글자보다
길다** — 서식이 많으면 화면상 8,000자에서도 상한을 넘길 수 있다(`TextLengthCounter` KDoc 이
이미 그 사실을 적어 두었다). 생성 폼에도 같은 카운터를 붙인다. 재는 문자열은 상세와 같은 **HTML**.

### ❓ 발견 3 (스스로 보강) — 시각 회귀 스냅샷

`apps/web/e2e/visual/visual-regression.spec.ts` 가 `issue-detail-{light,dark}.png` ·
`issue-list-{light,dark}.png` 4장을 비교한다. 본문 CSS 를 바꾸면 `issue-detail` 2장이 **반드시**
바뀐다 — 이것은 회귀가 아니라 **의도한 변경**이다. 스냅샷을 갱신하고, 갱신 전후를 눈으로 대조해
「번호가 보이게 됐다」가 실제로 찍혔는지 확인한다. 무비판 갱신은 이 검사를 죽인다.

### 🛑 발견 4 (Maxi 결정 필요) — **이슈 복제가 리치 에디터 본문을 통째로 잃는다** (선재 결함)

실측 사슬.

1. `resolveBodyPatch`(`IssueApplicationService.kt:2185`) — HTML 입구는 `markdown = ""` 를 낸다.
2. `updateFields`(`IssueRepository.kt:319`) — `set(ISSUES.DESCRIPTION, patch.description.ifBlank { null })` → **`description` 컬럼이 NULL 이 된다.**
3. 도메인 `Issue` 에는 `descriptionHtml` 필드가 **없다**(`Issue.kt:106` 은 `description` 뿐).
4. `cloneIssue`(`IssueApplicationService.kt:394`) — `Issue.create(description = source.description)` → **NULL 을 복사한다.**

⇒ **리치 에디터로 저장한 이슈를 복제하면 복제본의 본문이 빈다.** 마크다운 시절 이슈는 멀쩡하고
에디터로 편집한 이슈만 그렇다 — 그래서 지금까지 안 드러났다.

이 PR 이 만든 결함이 **아니다**. V039 가 본문을 두 컬럼으로 쪼개면서 복제 경로를 함께 옮기지
않은 것이고, 이 저장소가 이름 붙인 **「두 목록이 서로를 검사하지 않는다」** 의 또 다른 판본이다
(본문을 쓰는 경로 목록 ↔ 본문을 읽는 경로 목록).

**설계 결정 D-2 는 이것을 고치지 않는다.** 다만 D-2 를 유지한 채로도 고칠 수 있다 —
`cloneIssue` 가 `repo` 에서 원본 HTML 을 읽어 `insert(clone, descriptionHtml)` 로 넘기면 된다
(생성 경로에 어차피 만드는 그 인자다). 추가 비용은 작고, **판별식으로 재발을 막을 수 있다** —
「`insert` 를 부르는 모든 경로가 본문 두 컬럼을 함께 다루는가」.

### 🛑 Maxi 결정 (2026-09-07 · AskUserQuestion)

| 질문 | 결정 |
|---|---|
| 복제 본문 소실(발견 4)을 이번 PR 에 포함? | **포함 + 재발 판별식** |
| 서식 CSS 방식 (설계 결정 D-1) | **자체 CSS + 판별식** — `@tailwindcss/typography` 설치 안 함 |

이 결정으로 스펙에 다음이 **추가**된다.

- **D-5** — `cloneIssue` 가 원본 `description_html` 을 복제본에 옮긴다. 리포지토리에 본문 HTML
  단건 조회를 더하거나 기존 `findByKeyWithType` 결과를 재사용한다(구현 시 더 좁은 쪽을 고른다).
- **C10** — 리치 에디터로 저장한 이슈를 복제하면 복제본 본문이 원본과 같다 (Testcontainers).
- **C11** — **판별식**: `IssueRepository.insert` 를 부르는 모든 생산 경로가 본문 HTML 인자를
  함께 넘긴다. 차집합 0 + 비-공허 짝. 새 경로가 생겨 인자를 빠뜨리면 red 가 된다.
  ★이 판별식이 없으면 오늘 고쳐도 다음 `insert` 호출자가 같은 자리에서 다시 잃는다 —
  발견 4 자체가 그 증거다(V039 가 수정 경로만 옮기고 복제 경로를 두고 갔다).

**gap 5건 처리 완료** — 검색 인덱스 ✅무문제 · 발견 1~3 스스로 보강 · 발견 4 Maxi 결정.

## Plan

### 판별식 설계 — 이 PR 의 핵심 산출물

세 결함이 전부 같은 양식이다. **두 목록이 서로를 검사하지 않는다.**

| 결함 | 목록 A | 목록 B | 지금 |
|---|---|---|---|
| ① | 서버 sanitize allowlist 30태그 | CSS 가 스타일을 주는 태그 | 서로 모른다 |
| ③ | 툴바가 표기하는 단축키 | 실제 등록된 키맵 | 서로 모른다 |
| ⑤ | 본문을 쓰는 경로 | 본문 두 컬럼을 함께 다루는 경로 | 서로 모른다 |

처방은 저장소 정본대로 **차집합 판별식 + 비-공허 짝 + 훅**이다. 다만 ⑤ 는 더 강한 처방이 있다.

> **C11 은 판별식이 아니라 컴파일러로 강제한다.** `IssueRepository.insert(issue, descriptionHtml)`
> 의 두 번째 인자에 **기본값을 주지 않는다.** 그러면 인자를 빠뜨린 호출은 **컴파일 에러**이고,
> 새 호출자가 생겨도 자동으로 걸린다 — 목록을 만들지 않으므로 썩을 목록이 없다.
> 판별식은 그 대신 **「강제 장치가 살아 있는가」**를 지킨다: `insert` 시그니처에 기본값이
> 생기면 red. 기본값 한 글자가 컴파일러 강제를 통째로 무력화하기 때문이다.

★`IssueRepository.insert(Issue)` 호출자는 실측 **정확히 2곳**(`IssueApplicationService.kt:296` createIssue ·
`:401` cloneIssue). import 어댑터도 `createIssue` 를 통과하므로 직접 호출자는 이 둘뿐이다 — 비-공허 하한 2.

**C2 의 함정과 처방.** 툴바 표기 ↔ 키맵 대조에서 Tiptap **기본** 키맵(⌘B·⌘I·⌘U 등)은
`node_modules` 안에 있어 파싱이 버전업마다 깨진다. 그래서 **툴바가 표기하는 단축키를 전부
우리 커스텀 키맵 확장에 명시 등록**한다 — Tiptap 기본과 중복 등록되지만 같은 명령이라 무해하고
(먼저 매치한 쪽이 실행하고 결과가 같다), 판별식은 **저장소 안의 파일 두 개**만 보면 된다.

---

### Task 1. 리치 텍스트 CSS 블록 + allowlist 커버리지 판별식 (C1)

**메타**.
- agent: `frontend-engineer`
- files: [`scripts/workflow/rich-text-style-coverage.test.ts`, `apps/web/src/index.css`]
- depends-on: []
- jira: [J8]

**★리뷰 반영 — 판별식 파서 명세를 못박는다(E-1 · E-2 · E-3).** 파서가 헐거우면 이 판별식은
가짜 red 나 가짜 green 을 낸다. 아래가 계약이다.

| 항목 | 규칙 |
|---|---|
| allowlist 파서 | `MarkdownRenderer.kt` 의 `.allowElements("a", "b", …)` 를 **여러 줄에 걸쳐** 전부 읽는다. 호출이 체인으로 흩어져 있으므로 한 줄만 보면 안 된다 |
| CSS 파서 | 그룹 셀렉터(`.rich-text ul, .rich-text ol`)와 결합자(`.rich-text li > p`)를 **둘 다** 인식한다. 셀렉터를 콤마로 쪼갠 뒤 각 조각에서 `.rich-text` 다음의 **첫 태그 토큰**을 뽑는다 |
| `span` 예외 | 서버는 `span[class=mention]` 만 허용하고, 그 스타일은 **`index.css:487` 의 전역 `.mention` 이 이미 갖고 있다**(FR-MN-01). 판별식은 `span` 에 대해 `.rich-text span` 이 아니라 **`.mention` 규칙의 존재**를 본다 — 이 예외를 안 두면 **거짓 red** 가 난다 |
| `b`·`i`·`strong`·`em` | 브라우저·preflight 기본(`b, strong { font-weight: bolder }` — preflight.css:97)으로 **이미 보이지만 CSS 를 명시한다.** 기본값에 기대는 것이 바로 이번 결함의 원인이다(preflight 가 `ol` 마커를 지웠다). 예외 목록을 만드는 순간 그 목록이 두 번째 썩는 목록이 된다 |
| `tfoot` 등 | Tiptap 이 만들지 못해도 서버가 허용하면 CSS 를 준다 — CSV/import 경로로 들어올 수 있다 |

**RED**: `scripts/workflow/rich-text-style-coverage.test.ts`

```ts
// ① MarkdownRenderer.kt 에서 .allowElements("h1","h2",…) 를 파싱해 허용 태그 집합을 만든다
// ② index.css 의 `/* rich-text:start */` ~ `/* rich-text:end */` 사이에서
//    `.rich-text <tag>` 형태 셀렉터를 파싱해 스타일 대상 집합을 만든다
// ③ 차집합 0 을 단언한다
test('서버 allowlist 전량이 리치 텍스트 CSS 스타일을 받는다', …)
// 비-공허 짝 2개 — 파서가 눈멀면 「0건 대 0건」으로 조용히 통과한다
test('allowlist 파서가 25개 이상을 읽는다 (비-공허)', …)
test('CSS 파서가 25개 이상을 읽는다 (비-공허)', …)
// 뮤테이션 짝 — 판정 함수를 직접 불러 「태그 하나 빠진 CSS」가 red 를 내는지 본다
test('판정 함수가 누락을 실제로 잡는다', …)
```

- 실패 메시지 (예상): `CSS 가 스타일을 주지 않는 허용 태그: h1, h2, …, span.mention` (30건)

**GREEN**: `apps/web/src/index.css` 에 마커로 감싼 `.rich-text` 블록.

- 대상 30종 — `h1`~`h6` `p` `br` `strong` `em` `b` `i` `u` `del` `s` `ul` `ol` `li` `hr` `pre` `blockquote` `code` `table` `thead` `tbody` `tfoot` `tr` `th` `td` `input[type=checkbox]` `img` `a` `span.mention`
- `ol`/`ul` 은 preflight 가 지운 `list-style` 을 **되돌린다** — 이 한 줄이 Maxi 지적 2번의 직접 처방이다
- 중첩 목록 마커 순환(`decimal → lower-alpha → lower-roman`)까지 준다 — Jira 와 같다
- 색·간격은 `index.css` 에 이미 있는 ADS 토큰 변수만 쓴다. **새 색을 만들지 않는다**(NFR N3)

**REFACTOR**: 블록 상단에 「이 목록의 짝은 `MarkdownRenderer.SANITIZE_POLICY` 이고 판별식이 지킨다」 주석.

**검증**: `node --experimental-strip-types --test scripts/workflow/rich-text-style-coverage.test.ts`

★**뮤테이션 검증의 함정(E-5) — 이 저장소가 이미 밟았다.**
2026-09-04 실측 기록: *BSD `sed` 가 대괄호·캐럿이 섞인 CSS 셀렉터(`img[src^="attachment:"]`)를
미매치해 뮤테이션이 파일에 들어가지 않았는데 테스트는 초록이었고, 그대로 「판별식이 잡는다」로
오독할 뻔했다.* 이번 판별식의 대상이 **정확히 그 모양**이다(`input[type="checkbox"]` ·
`img[src^=…]` · `span.mention`). 그래서 뮤테이션 짝은 두 규율을 지킨다.

1. 뮤테이션 직후 `grep -c MUTATION` 으로 **적용 건수를 되잰다.** 0건이면 그 검증은 무효다.
2. 대괄호·캐럿이 든 패턴은 `sed` 대신 **Python 으로 앵커 문자열을 assert 한 뒤 replace** 한다.

「걸었다」와 「걸렸다」는 다르다.

---

### Task 2. `prose` 사용처 3곳을 `rich-text` 로 교체

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/editor/RichTextEditor.tsx`, `apps/web/src/components/issue/IssueDescription.tsx`, `apps/web/src/components/issue/CommentSection.tsx`, `apps/web/src/components/editor/__tests__/RichTextEditor.test.tsx`]
- depends-on: [1]
- jira: [J8]

**RED**: `RichTextEditor.test.tsx` — 에디터 본문 컨테이너가 `rich-text` 클래스를 갖는다. 현재는 `prose` 라 red.
★함께 단언한다: **`prose` 문자열이 `apps/web/src` 어디에도 남지 않는다.** 하나라도 남으면
그 화면만 조용히 무스타일로 남고, 그것이 바로 지금 상태다.

**GREEN**: 세 곳의 `prose prose-sm max-w-none` → `rich-text`. `max-w-none` 은 `prose` 의 폭 제한을 푸는 클래스였으므로 함께 제거한다(우리 블록은 폭을 제한하지 않는다).

**REFACTOR**: 세 곳이 같은 문자열을 반복하므로 `editor` 모듈에 `RICH_TEXT_CLASS` 상수로 뽑는다.

**검증**:
- `pnpm --filter web test -- RichTextEditor`
- 기존 E2E: `apps/web/e2e/issue-detail.spec.ts` · `apps/web/e2e/issue-comments.spec.ts`
- 눈확인: 이슈 상세 본문의 번호 목록·코드 블록·인용 — **라이트/다크 양쪽**

---

### Task 3. 툴바 단축키 키맵 확장 + 커버리지 판별식 (C2)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/editor/rich-text-extensions.ts`, `apps/web/src/components/editor/__tests__/rich-text-shortcuts.test.ts`, `scripts/workflow/toolbar-shortcut-coverage.test.ts`]
- depends-on: []
- jira: [J22]

**RED**: `rich-text-shortcuts.test.ts` — 실제 에디터 인스턴스에 키를 쏘고 결과 노드를 본다.

```ts
test('⌘⇧9 는 인용이다 (Jira J22)', …)        // 현재: 체크박스 목록 → red
test('⌘⇧- 가 구분선을 넣는다', …)             // 현재: 아무 일도 없음 → red
test('⌘⇧K 가 링크 입력을 연다', …)            // 현재: 아무 일도 없음 → red
test('⌘⇧M 이 인라인 코드를 토글한다', …)      // 현재: ⌘E 만 있음 → red
test('⌘⇧9 가 체크박스 목록을 만들지 않는다', …) // TaskList 키맵 회수 확인
```

**GREEN**: `rich-text-extensions.ts`

- `TaskList.extend({ addKeyboardShortcuts: () => ({}) })` — `Mod-Shift-9` 회수 (**D-4**)
- 커스텀 `Extension.create({ name: 'jiraShortcuts', addKeyboardShortcuts })` 하나에 툴바가
  표기하는 키 **전량**을 명시 등록: `Mod-b` `Mod-i` `Mod-u` `Mod-Shift-s` `Mod-Shift-m`
  `Mod-Shift-7` `Mod-Shift-8` `Mod-Shift-9` `Mod-Shift--` `Mod-Shift-k` `Mod-Alt-c`
- 링크 키는 툴바와 **같은 입구**를 불러야 한다 — 두 입구가 갈리면 「버튼과 키가 다르게 동작」이 된다. 링크 프롬프트 열기를 콜백으로 주입한다

**REFACTOR**: 키 → 명령 매핑을 배열 1개로 만들어 툴바 표기와 판별식이 **같은 출처**를 보게 한다.

**검증**:
- `pnpm --filter web test -- rich-text-shortcuts`
- `node --experimental-strip-types --test scripts/workflow/toolbar-shortcut-coverage.test.ts`

---

### Task 4. 툴바 표기 교정 + Tooltip 교체 + Provider 마운트

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/editor/RichTextToolbar.tsx`, `apps/web/src/i18n/editor-labels.ts`, `apps/web/src/main.tsx`, `apps/web/src/components/editor/__tests__/RichTextToolbar.test.tsx`]
- depends-on: [3]
- jira: [J22, J24]

**RED**: `RichTextToolbar.test.tsx`

```ts
test('호버하면 툴팁이 뜬다', …)               // 현재: native title → role="tooltip" 없음 → red
test('키보드 포커스에서도 툴팁이 뜬다', …)     // ADS 계약 J24 → red
test('인용 툴팁이 ⌘⇧9 를 적는다', …)          // 현재 ⌘⇧B → red
test('체크박스 목록 툴팁에 단축키가 없다', …)   // 현재 ⌘⇧9 → red (X-E3)
test('표·이미지 툴팁에 / 표기가 없다', …)      // 현재 "/" → red (X-E1)
```

**GREEN**:
- `ToggleButton` 의 `title` 속성을 `Tooltip`/`TooltipTrigger`/`TooltipContent` 로 교체.
  `aria-label` 은 **남긴다** — 툴팁은 보조 설명이고 접근성 이름은 버튼이 스스로 가져야 한다
- 표기 교정: 인용 `⌘⇧9` · 인라인 코드 `⌘⇧M` · 체크박스 목록 단축키 표기 제거 · 표·이미지 `/` 제거
- `shortcut` 을 optional 로 바꾼다 (단축키 없는 버튼이 실재하므로)
- `main.tsx` 의 `QueryClientProvider` 바깥에 `TooltipProvider` 마운트

**REFACTOR**: 툴팁 본문 서식(`이름 (키)` / 키 없으면 `이름`)을 헬퍼 1개로.

**검증**:
- `pnpm --filter web test -- RichTextToolbar`
- 눈확인: 툴바 아이콘 호버 + Tab 포커스 — 라이트/다크 양쪽

---

### Task 5. `CreateIssueRequest.descriptionHtml` + 상호배타 검증

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/adapter/inbound/rest/CreateIssueRequest.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/adapter/inbound/rest/CreateIssueRequestValidationTest.kt`]
- depends-on: []
- jira: [J23]

**RED**: 검증 테스트

```kotlin
@Test fun `description 과 descriptionHtml 을 동시에 보내면 위반이다`()   // 필드 없음 → 컴파일 red
@Test fun `descriptionHtml 32768자는 위반이다`()
@Test fun `descriptionHtml 만 보내면 통과한다`()
```

**GREEN**: `@field:Size` + `@get:AssertTrue val isBodyExclusive`.
★`UpdateIssueRequest.isBodyExclusive` 와 **문자 단위로 같은 술어**여야 한다 —
갈리면 「생성은 통과, 수정은 400」 비대칭이 되살아난다(라벨 검증에서 이미 겪은 양식).

**REFACTOR**: 두 DTO 의 술어가 같음을 단언하는 정렬 테스트 — `IssueLabelConstraintsAlignmentTest` 와 같은 결.

**검증**: `(cd backend && ./gradlew :modules:issue-tracking:test --tests '*CreateIssueRequest*')`

---

### Task 6. 생성 경로 본문 해석 + `insert` 배선 + 빈 문서 판정 (E4)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/application/IssueApplicationService.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/application/IssueApplicationRequests.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/adapter/inbound/rest/IssueController.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/repository/IssueRepository.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/application/IssueCreateBodyTest.kt`]
- depends-on: [5]
- jira: [J23]

**RED**: `IssueCreateBodyTest` (Testcontainers)

```kotlin
@Test fun `descriptionHtml 로 생성하면 단건 조회에 서식이 실린다`()          // C3
@Test fun `descriptionHtml 로 생성하면 description 컬럼이 NULL 이다`()      // 수정 경로와 같은 규약
@Test fun `빈 HTML 문서는 프로젝트 템플릿으로 대체된다`()                    // C4 · E4 — 가장 위험한 회귀
@Test fun `이미지만 든 HTML 은 빈 문서가 아니다`()                           // 발견 1 — img 예외
@Test fun `descriptionHtml 로 생성한 이슈가 검색에 잡힌다`()                 // description_plain generated 확인
```

**GREEN**:
- `IssueRepository.insert(issue: Issue, descriptionHtml: String?)` — ★**기본값을 주지 않는다.**
  인자 누락이 컴파일 에러가 되는 것이 C11 의 강제 장치다
- 빈 문서 판정 함수 — 태그 제거 + `&nbsp;`(U+00A0) 공백 접기 + `trim()` 이 빈 문자열 **AND** `<img` 미포함
- `createIssue` 의 본문 해석을 수정 경로 `resolveBodyPatch` 와 **같은 표**로 (스펙 「본문 해석표」)
- `AppCreateIssueRequest` · `IssueController.create` 에 필드 전달

**REFACTOR**: 생성·수정이 같은 본문 해석 함수를 통과하게 정리 (NFR N4).

**검증**: `(cd backend && ./gradlew :modules:issue-tracking:test --tests '*IssueCreateBody*')`

---

### Task 7. 복제 본문 소실 수정 (D-5) + 강제 장치 판별식 (C11)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/application/IssueApplicationService.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/application/IssueCloneBodyTest.kt`, `scripts/workflow/issue-insert-body-guard.test.ts`]
- depends-on: [6]

**RED**:

```kotlin
@Test fun `리치 에디터로 저장한 이슈를 복제하면 본문이 원본과 같다`()  // C10 — 현재 빈다 → red
```

```ts
// C11 — 강제 장치가 살아 있는가
test('IssueRepository.insert 의 descriptionHtml 인자에 기본값이 없다', …)
// 기본값이 생기면 컴파일러 강제가 사라지고, 새 insert 호출자가 본문을 조용히 잃는다.
// 실제로 V039 가 수정 경로만 옮기고 복제 경로를 두고 가서 이 결함이 났다.
test('insert 호출자가 2곳 이상이다 (비-공허)', …)
```

**GREEN**: `cloneIssue` 가 원본 `description_html` 을 읽어 `insert(clone, html)` 로 넘긴다.

★**리뷰가 이 자리의 서술을 뒤집었다(E-4).** 초안은 「원본 조회는 이미 하고 있으므로 **추가 쿼리 없이**
꺼낸다」였는데 **틀렸다.** 실측 — `cloneIssue` 는 `repo.findByKey(sourceKey)`(`IssueApplicationService.kt:378`)
로 **도메인 `Issue`** 를 읽고, 그 클래스에는 `descriptionHtml` 이 **없다**(`Issue.kt:106` 은 `description` 뿐).
바로 그 부재가 이 결함의 원인이므로, 같은 객체에서 값을 꺼내는 길은 **원리적으로 없다.**

⇒ `IssueRepository` 에 **단일 컬럼 조회 1개**를 더한다 — `fun findDescriptionHtml(key: IssueKey): String?`.
복제는 단건 조작이라 쿼리 1회 추가는 무해하고, `findByKeyWithType`(응답 DTO 용 JOIN 조회)을
끌어오는 것보다 훨씬 좁다.

**REFACTOR**: `cloneIssue` KDoc 에 「본문은 두 컬럼이다」 명시.

**검증**:
- `(cd backend && ./gradlew :modules:issue-tracking:test --tests '*IssueCloneBody*')`
- `node --experimental-strip-types --test scripts/workflow/issue-insert-body-guard.test.ts`

---

### Task 8. 이슈 생성 폼을 리치 에디터로 전환

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/issue/create/IssueCreateBasicFields.tsx`, `apps/web/src/components/issue/create/issue-create-schema.ts`, `apps/web/src/components/issue/create/use-issue-create-form-state.ts`, `apps/web/src/api/issues.ts`, `apps/web/src/components/issue/create/__tests__/IssueCreateBasicFields.test.tsx`]
- depends-on: [2, 5]
- jira: [J23]

**RED**: `IssueCreateBasicFields.test.tsx`

```tsx
test('본문 입력이 리치 에디터다 (툴바가 있다)', …)      // 현재 textarea → red
test('빈 에디터는 descriptionHtml 키를 보내지 않는다', …) // E4 프론트 보조 (D-3)
test('길이 카운터가 임계에서 나타난다', …)                // 발견 2
```

**GREEN**:
- `Textarea` → `RichTextEditor` (`imageIssueKey` 미전달 — E3 대로 이미지 경로 자동 비활성)
- `TextLengthCounter` 추가 (재는 문자열은 상세와 같은 **HTML**)
- ★**리뷰 반영 — 다이얼로그 공간(D-1 · D-2).** `RichTextEditor` 의 본문 최소 높이는
  `min-h-[8rem]`(`RichTextEditor.tsx:98`) 이고 툴바 버튼은 16개 + 제목 select 다. 상세 화면보다
  좁은 생성 다이얼로그에 그대로 넣으면 ①본문이 `rows={4}` 시절보다 커져 아래 필드를 밀어내고
  ②툴바가 `flex-wrap` 으로 **2~3단**이 되어 폼 상단을 잡아먹는다. 처방 — 최소 높이를
  **prop 으로 받아** 생성 폼에서 낮추고, 다이얼로그 폭에서 툴바가 몇 단이 되는지 **눈으로 센다.**
  단수가 2단을 넘으면 그 사실을 게이트 2 요약에 적는다(버튼 정리는 별건이다)
- `issue-create-schema.ts` 의 `description` → `descriptionHtml`, 빈 문서면 키 자체를 뺀다
- `CreateIssueInput` · `createIssue` 에 `descriptionHtml` 추가 (`description` 은 레거시로 **남긴다**)

**REFACTOR**: 빈 문서 판정을 `editor` 모듈 헬퍼로 뽑아 다른 소비처가 재사용하게.

**검증**:
- `pnpm --filter web test -- IssueCreateBasicFields`
- 눈확인: 생성 다이얼로그 본문 — 라이트/다크

---

### Task 9. E2E 왕복 + 시각 스냅샷 갱신

**메타**.
- agent: `qa-engineer`
- files: [`apps/web/e2e/issue-rich-text.spec.ts`, `apps/web/e2e/visual/__screenshots__/issue-detail-light.png`, `apps/web/e2e/visual/__screenshots__/issue-detail-dark.png`]
- depends-on: [2, 4, 6, 8]
- jira: [J8, J22, J23, J24]

**RED**: `issue-rich-text.spec.ts`

```ts
test('생성 폼에서 번호 목록을 넣으면 상세에 1. 2. 3. 이 보인다', …)  // C6 + C9
test('빈 본문으로 생성하면 템플릿이 채워진다', …)                     // C4 e2e 확인
test('툴바 아이콘 호버에 툴팁이 뜬다', …)                            // C8
```

**GREEN**: 스펙 작성. 시각 스냅샷 2장 갱신.

★**무비판 갱신 금지**(발견 3). 갱신 전후 이미지를 **눈으로 대조**해
「번호가 보이게 됐다」가 실제로 찍혔는지 확인하고, 그 사실을 게이트 2 요약에 적는다.
스냅샷을 그냥 덮으면 이 검사는 그 순간 죽는다.

**검증**:
- `pnpm --filter web test:e2e -- issue-rich-text`
- `pnpm --filter web test:e2e -- visual`
- 눈확인: 갱신 전/후 스냅샷 대조

---

## Plan 메타

- **task 수**: 9 · **예상 wave**: 4
  - w1 — T1(CSS+판별식) · T3(키맵+판별식) · T5(REST DTO)
  - w2 — T2(prose 교체) · T4(툴팁) · T6(생성 배선)
  - w3 — T7(clone+강제장치) · T8(생성폼)
  - w4 — T9(E2E+시각)
- **구현 규율**: TDD red-first (T2 필수). 프론트 UI task 는 **시각 검증 트랙 병행** — `**검증**` 의 E2E 목록 + 눈확인 항목이 계약이다.
- **추가 검증**: `tsc --noEmit` · `eslint` · `ktlintCheck` · `detekt` · `vitest` · `playwright` · `node --test 'scripts/**'`(판별식 전량) · `build-doc-index --check` · `verify-master-plan.sh`
- **Jira 매핑**: `J8→T1,T2` · `J22→T3,T4` · `J23→T5,T6,T8` · `J24→T4` · 전 항목이 T9 E2E 로 다시 덮인다. **채택 4건 전량이 task 에 물렸다 — 차집합 0.** 편차 `X-E1`(퀵인서트·이모지·언어선택 미구현)은 T4 가 거짓 표기를 제거하는 형태로 반영한다.
- **BC**: `issue-tracking` 1개 · **마이그레이션 0건** · **신규 의존성 0건**(D-1 로 `@tailwindcss/typography` 를 설치하지 않으므로 `DEPS` 표면이 빠진다)

## 리뷰 결과

렌즈 **2종** — `plan-eng-review` + `plan-design-review`.
`type == "backend"` 행은 「UI 포함 시 `plan-design-review` 추가」이고, 이번 변경은 CSS·툴바·툴팁·
생성 폼까지 **UI 가 지배적**이라 2종을 발행했다.

**BLOCKER 0.** findings 10건 전부 plan 보강으로 해소했고, 보강 내용은 각 Task 본문에
`★리뷰 반영` 으로 인라인했다 — 별지에 적으면 구현자가 안 본다.

### plan-eng-review (6건)

| # | 심각도 | 신뢰도 | 발견 | 처리 |
|---|---|---|---|---|
| **E-1** | P1 | 9/10 | CSS 판별식의 **파서 명세가 없었다.** 그룹 셀렉터(`.rich-text ul, .rich-text ol`)나 결합자(`.rich-text li > p`)를 못 읽으면 가짜 red, 헐거우면 가짜 green | Task 1 에 파서 계약표 추가 — 콤마 분해 후 `.rich-text` 다음 첫 태그 토큰 |
| **E-2** | P1 | 9/10 | **`span` 이 거짓 red 를 낸다.** 서버는 `span[class=mention]` 만 허용하고 그 스타일은 `index.css:487` 의 전역 `.mention` 이 **이미** 갖고 있다(FR-MN-01). `.rich-text span` 을 요구하면 없는 결함을 만든다 | 판별식이 `span` 에 대해서만 `.mention` 규칙의 존재를 보게 예외 명시 |
| **E-3** | P2 | 9/10 | `b`·`i`·`strong`·`em` 은 preflight 기본(`preflight.css:97` `b, strong { font-weight: bolder }`)으로 이미 보인다 — CSS 를 생략할 유혹이 있다 | **생략하지 않는다.** 기본값 의존이 이번 결함의 원인이고(preflight 가 `ol` 마커를 지웠다), 예외 목록을 만들면 그것이 두 번째 썩는 목록이 된다 |
| **E-4** | P1 | **10/10** | ★**plan 의 서술이 틀렸다.** Task 7 초안은 복제 본문을 「추가 쿼리 없이」 꺼낸다고 적었는데, `cloneIssue` 는 `repo.findByKey`(`:378`)로 **도메인 `Issue`** 를 읽고 그 클래스에 `descriptionHtml` 이 **없다**(`Issue.kt:106`) — 그 부재가 결함의 원인이므로 같은 객체에서 꺼내는 길이 **원리적으로 없다** | Task 7 을 정정. `findDescriptionHtml(key)` 단일 컬럼 조회 1개 추가 |
| **E-5** | P1 | **10/10** | 뮤테이션 검증이 이 저장소가 이미 밟은 함정에 정면으로 걸린다 — 2026-09-04 실측: BSD `sed` 가 `img[src^="attachment:"]` 를 미매치해 **뮤테이션이 파일에 안 들어갔는데 초록**이었다. 이번 판별식 대상이 정확히 그 모양이다 | Task 1 에 규율 2개 명시 — `grep -c MUTATION` 으로 적용 건수 되재기 · 대괄호 패턴은 Python assert-후-replace |
| **E-6** | P2 | 8/10 | `tfoot` 처럼 Tiptap 이 만들지 못하는 태그에도 CSS 를 준다 = 죽은 코드 아닌가 | **정당하다.** 서버가 허용하므로 CSV·import 경로로 들어올 수 있다. 커버리지 예외를 두는 쪽이 더 위험 |

### plan-design-review (4건)

7패스 중 Pass 4(AI slop)는 **해당 없음** — 새 화면을 만드는 것이 아니라 이미 있는 화면의
서식을 복구하는 작업이라 생성형 레이아웃 리스크가 없다. Pass 5 는 ADS 토큰 강제(NFR N3)로 충족.

| # | 패스 | 심각도 | 신뢰도 | 발견 | 처리 |
|---|---|---|---|---|---|
| **D-1** | 2 상태 커버리지 | P1 | 9/10 | 생성 다이얼로그 에디터의 **높이**가 미명세. `min-h-[8rem]`(`RichTextEditor.tsx:98`)은 기존 `rows={4}` Textarea 보다 커서 아래 필드를 밀어낸다 | Task 8 — 최소 높이를 prop 으로 받아 생성 폼에서 낮춘다 |
| **D-2** | 6 반응형 | P1 | 8/10 | 툴바 버튼 **16개 + select** 가 좁은 다이얼로그에서 `flex-wrap` 으로 2~3단이 되어 폼 상단을 잡아먹는다 | Task 8 — 다이얼로그 폭에서 단수를 **눈으로 센다.** 2단 초과면 게이트 2 요약에 적는다(버튼 정리는 별건) |
| **D-3** | 6 접근성 | P2 | 7/10 | Radix Tooltip 은 **터치에서 뜨지 않는다.** 모바일 사용자는 아이콘 의미를 배울 길이 없다 | `aria-label` 을 **남기므로** 스크린리더는 무사하다(Task 4 GREEN 에 이미 명시). 터치 어포던스는 별건 — X-E1 과 같은 결로 범위 밖 |
| **D-4** | 2 상태 커버리지 | P2 | 8/10 | 긴 코드 블록의 **가로 스크롤**이 미명세. `pre` 가 넘치면 본문 레이아웃이 깨진다 | Task 1 CSS 에 `pre { overflow-x: auto }` 포함 — 표(`table`)도 같다 |

### 렌즈가 실제로 바꾼 것

리뷰가 **plan 의 사실 주장 하나를 거짓으로 판정했다**(E-4). 「추가 쿼리 없이 꺼낸다」는
그럴듯했지만 도메인 클래스를 실제로 읽으면 성립하지 않는다 — 구현 단계에서야 걸렸다면
Task 7 이 막히고 그 자리에서 설계를 다시 했을 것이다.

그리고 E-5 는 **이 저장소의 과거 실측**이 이번 작업에 그대로 적용된다는 발견이다.
판별식을 세우는 PR 이 정작 그 판별식의 검증에서 가짜 통과를 내는 것이 가장 나쁜 결말이다.
