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

## 도메인 정리 (← /bts-spec §1 채움)

## 스펙 (← /bts-spec §2 채움)

## Sanity Check (← /bts-spec §3 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
