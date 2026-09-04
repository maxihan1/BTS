# 이슈 상세 Jira 패리티 — 모달 · 레이아웃 · TipTap 에디터 · 첨부 썸네일 (4-PR 캠페인)

> 티어: T3 (PR① 마이그레이션) · T1 (PR②③④)
> slug: issue-detail-jira-parity
> type: ui
> agent: db-engineer(PR①) · frontend-engineer(PR②③④)
> 생성: 2026-09-04

## Brief

**사용자 원문.** `이슈 본문 ui가 지라 클라우드와 다른데 지라 클라우드와 동일한 ui 스펙으로 수정하고
버그도 수정하자 1, 이슈를 클릭하면 모달 형태로 노출 2. 오른쪽 사이드바 아래로 댓글및 작업로그가
나오는데 본문 아래에 붙도록 수정 3. 입력 필드에 최대 글자수 적용 제목 서머리 등(지라 클라우드 스펙
확인) 4. 첨부파일 썸네일 기능 추가하고 본문에도 이미지 첨부 되도록 수정, 클립보드에 복사된 이미지를
본문에 붙여넣기 가능하도록 수정 5. 기타 지라 클라우드 스펙 리서치 해서 수정 사항에 반영해줘`

추가 지시 (대화 중). `추가로 폰트 크기나 굵게 등 에디터 기능이 BTS에는 없는데 지라 클라우드 스펙과
동일하게 들어가야 겠다` · `지라 클라우드는 tiptap 방식 아니야?? BTS는 에디터 ui도 없어`

**이 캠페인이 하는 것.** 다섯 갈래다.

1. 이슈 상세를 **모달**로 연다. 기존 split view 는 유지하고 Jira 처럼 토글한다
2. 활동(댓글·작업로그·이력·연결)을 **본문 아래**로 옮기고 **기본 탭을 댓글**로 바꾼다
3. 입력 길이 제약을 Jira 값으로 정렬하고 프론트에 노출한다
4. 첨부를 **썸네일 그리드**로, 본문에 **이미지 붙여넣기**를 넣는다
5. 본문·댓글 에디터를 **TipTap WYSIWYG** 로 바꾸고 저장 포맷을 HTML 로 전환한다

**왜 PR① 이 T3 인가.** `issues.description` 을 markdown → HTML 로 in-place 변환하는 Flyway
마이그레이션을 돈다. 검색 인덱스(`search_vector` · `idx_issues_description_trgm`)도 함께 재작성한다.
`CLAUDE.md` §작업 티어의 「마이그레이션」 행이 적용된다. sanitize allowlist 변경은 보안 표면이라
security-engineer 공동 검토가 붙는다.

## 계획 정본

전문은 `~/.claude/plans/ui-agile-hejlsberg.md`. 이 문서는 저장소에 남기는 요약이고, 결정 근거는
`2026-09-04-issue-detail-jira-parity/context-notes.md`, 진행은 같은 디렉터리 `checklist.md` 다.

## Jira 대조

조회일 **2026-09-04**. 전부 Cloud 문서다.

| # | 항목 | Jira Cloud 실물 | 출처 |
|---|---|---|---|
| J1 | 상세 열기 | 모달이 기본. "Your choice to view your work in the modal or the Preview Panel will persist across Jira views within the same session" · "revert to sidebar by opening an issue in the modal, clicking the '…' and selecting 'Open issues in sidebar'" · "you can continue to use Cmd or Ctrl + click, or right-click to open it in a new tab" | [Preview Panels 공지](https://community.atlassian.com/forums/Jira-articles/Preview-Panels-will-soon-replace-the-detail-view-in-Jira-s/ba-p/3196999) (Atlassian 공식 글) |
| J2 | 활동 위치 | "just scroll down the work item and find the **Activity** section" | [활동 유형](https://support.atlassian.com/jira-software-cloud/docs/what-are-the-different-types-of-activity-on-an-issue/) (Cloud) |
| J3 | 활동 필터·기본 | Comments · History · Work log · All. "By default the activity feed shows comments" | 같은 문서 |
| J4 | 제목 길이 | "The 255 character limit is for single-line text fields, like Summary. You can not change it." | [커뮤니티 공식 답변](https://community.atlassian.com/forums/Jira-questions/Summary-must-be-less-than-255-characters/qaq-p/989632) |
| J5 | 본문 길이 | "Description is a long text that defaults to max 32767 characters" | [커뮤니티 공식 답변](https://community.atlassian.com/forums/Jira-questions/Character-limit-of-Description-field-in-Jira-Software-cloud/qaq-p/2655913) |
| J6 | 첨부 썸네일 | "Click an image thumbnail to open a preview… If your Jira admin has disabled thumbnails in Jira's attachment settings, the image files will appear as a list" — 썸네일이 기본, 리스트가 예외 | [첨부·스크린샷](https://support.atlassian.com/jira-service-management-cloud/docs/attach-files-and-screenshots-to-issues/) (Cloud · JSM) |
| J7 | 본문 이미지 | 본문 이미지도 첨부다. "Files, Confluence pages and live docs, and Loom videos all appear together in a single **Attachments** section" | [본문에 파일·이미지 넣기](https://support.atlassian.com/jira-software-cloud/docs/add-files-images-and-other-content-to-describe-an-issue/) (Cloud) |
| J8 | 에디터 서식 | Bold `**` · Italic `*` · Strikethrough `~~` · Monospace `` ` `` · Heading 1~6 · 목록 3종(bullet · numbered · action item `[]`) · 인용 `>` · 구분선 `---` · 코드블록 ` ``` ` · 링크 · 이미지 `![]()`. 단축키 ⌘B ⌘I ⌘U ⌘⇧S ⌘⇧M ⌘⇧7 ⌘⇧8 ⌘⇧9 ⌘⇧- ⌘⇧K · `/` 퀵인서트 · `@` 멘션 · `:` 이모지 | [마크다운·단축키](https://support.atlassian.com/jira-software-cloud/docs/markdown-and-keyboard-shortcuts/) (Cloud) |

### 의도적 편차

| # | 편차 | 근거 |
|---|---|---|
| **X1** | 저장 포맷이 ADF(JSON) 가 아니라 **정화된 HTML** | Maxi 결정. ADF 는 FTS · PDF 렌더러(`IssuePdfTemplate.kt`) · import 를 전부 재설계해야 해 비용이 맞지 않는다 |
| **X2** | 밑줄은 마크다운 문법 없이 TipTap `Underline` 확장으로만 | Jira 도 ⌘U 단축키로만 넣는다(J8). sanitize 에 `u` 를 추가한다 |
| **X3** | 이모지 `:` 자동완성 미구현 | 이번 범위 밖. `@` 멘션은 기존 자산을 이식한다 |
| **X4** | 첨부 파일당 상한 100MB 유지 | Jira 는 1GB(문서에 따라 10MB). BTS 기존 `MAX_ATTACHMENT_BYTES` 를 그대로 둔다 |
| **X5** | 서버 썸네일 리사이즈 없음 — 5MB 이하 이미지만 원본을 받아 CSS 로 축소 | 리사이즈 엔드포인트는 별건. 초과분은 아이콘 타일 + 미리보기 버튼 |

## PR 분할

| PR | BC | 티어 | 내용 |
|---|---|---|---|
| ① | `issue-tracking` | **T3** | HTML 저장 마이그레이션 + `description_plain` 생성 컬럼 + 검색 인덱스 재작성 + sanitize allowlist 확장 + flexmark 확장 등록 + 필드 길이 정렬 + `descriptionHtml` 양방향 호환 |
| ② | `apps/web` | T1 | 상세 모달 + 활동을 본문 아래로 + 댓글 기본탭 + 길이 제약 프론트 노출 |
| ③ | `apps/web` | T1 | TipTap WYSIWYG (본문 · 댓글 공통 `RichTextEditor`) |
| ④ | `apps/web` | T1 | 첨부 썸네일 그리드 + 본문 이미지 삽입·붙여넣기 |

PR① 은 프론트가 아직 markdown 을 보내는 동안에도 깨지지 않도록 **`description`(markdown) 과
`descriptionHtml`(HTML) 둘 중 하나**를 받는다. 둘 다 오면 400. import(CSV 평문) 경로가 markdown 을
계속 쓰므로 flexmark 는 제거하지 않는다.

## 선행 실측 (계약 §5 · 2026-09-04)

| 무엇 | 실측값 |
|---|---|
| 상세 진입 의존 e2e | **37 spec** |
| `goto('/issues/KEY')` | **46건 / 21파일** — 전체 페이지 라우트가 살아 있어 무영향 |
| `getByRole('dialog')` | **224건** — 계약 §2 고유 `aria-label` 조항 적용 |
| 본문·댓글 유닛 테스트 | **8파일** (`IssueDescription.test.tsx` 1,005줄 포함) |
| `isEditableTarget` contenteditable 판정 | **이미 있음** (`shortcuts.ts:218`) — PR③ 리스크 사전 해소, 회귀 가드만 세운다 |

## FR 매핑

`jira-parity-roadmap.md:43` 의 `F7 댓글 기본탭(CO 결손)` 을 PR② 가 닫는다. 나머지 갈래의 FR 귀속은
착수 직전 `docs/plan/README.md` 와 대조해 확정한다 — 신규 FR 이면 `docs/rules/fr-sync-checklist.md`
전수 동기화를 같은 PR 에서 돈다.
