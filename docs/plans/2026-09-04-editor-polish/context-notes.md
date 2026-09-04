# 에디터 마감 — 컨텍스트 노트

결정과 그 근거를 시간 순으로 덧붙인다. 계획 `../2026-09-04-editor-polish.md` · 진행 `checklist.md`

---

## 2026-09-04 — PR 을 둘로 가른다

**결정.** 남은 에디터 작업 5건을 PR A(이 문서, 항목 A~D)와 PR B(모달↔사이드패널 토글)로 가른다.

**근거.** A~D 는 에디터·첨부 컴포넌트 안에서 끝난다. 토글은 `routes/issues.index.tsx`
(목록 split view)를 건드리고 e2e 의존이 많다. 섞으면 토글이 막힐 때 나머지 4건이 함께 발이
묶이고, e2e 가 깨졌을 때 원인 분리에 시간이 든다. **직전 4-PR 캠페인에서 가장 비쌌던 것이
e2e 디버깅이었다** — 이건 추측이 아니라 그 캠페인의 실측이다.

## 2026-09-04 — 계획 문서를 첫 커밋으로 둔다

**결정.** 구현 전에 계획 3종을 먼저 커밋하고 draft PR 을 연다.

**근거 둘.** ① 빈 브랜치로는 PR 을 만들 수 없다. ② `CLAUDE.md` §7 이 plan + checklist +
context-notes 3종을 비-사소 작업의 선행 조건으로 요구한다.

**문서 규약** — `docs/plans/YYYY-MM-DD-slug.md` · `# H1` 필수 · 본문에 FR ID ·
spec 과 plan 은 같은 slug · 커밋 후 `node scripts/build-doc-index.mjs` 재실행.
형식은 직전 캠페인 `2026-09-04-issue-detail-jira-parity.md` 를 따랐다(frontmatter 없이
`# H1` + 인용 블록 메타).

## 2026-09-04 — 체크포인트의 항목 (3) 기술이 틀렸다. 실측이 더 나쁘다

**기록된 것.** 「본문·댓글 길이 카운터 미구현 — 지금은 `maxLength` 만 걸려 있다」

**실측.** 프론트에 본문·댓글 상한이 **아예 없다**.

| 층 | 제목 | 본문 | 댓글 |
|---|---|---|---|
| 서버 정본 | 255 | 32767 (`IssueTextConstraints.kt:45`) | 32767 (`:48`) |
| 프론트 상수 | `SUMMARY_MAX_LENGTH = 255` | **없음** | **없음** |
| 프론트 노출 | `maxLength` 속성 | **없음** | **없음** |

32767 은 `api/issues.ts:423`·`:430` **주석에만** 있다. TipTap `CharacterCount` 확장도
미설치다(`package.json` 조회 0건). 본문이 contenteditable 이라 `maxLength` 속성 자체를
걸 수 없으므로, 「`maxLength` 만 걸려 있다」는 상태는 애초에 성립할 수 없다.

**함의.** 사용자는 상한을 넘길 때까지 신호를 못 받고 **저장 시 서버 400 으로 처음 안다**.
32767자를 날리는 경로다. 항목 B 의 우선순위가 「있으면 좋은 카운터」가 아니라 **데이터 손실
경로 차단**으로 올라간다.

**따라오는 위험.** 프론트에 상수를 두는 순간 서버 상수와 **이중 목록**이 생긴다.
`[[two-lists-never-check-each-other]]` 의 지배 결함 양식 그대로다. 처방도 같다 —
차집합 판별식 + 비-공허 짝. 값만 복사하고 짝을 안 두면 다음 조정 때 조용히 어긋난다.

## 2026-09-04 — A1 은 「동작하지만 판별자가 없는」 형태다

`isEditableTarget`(`shortcuts.ts:212`)이 `isContentEditable` 을 이미 본다. `RichTextEditor.tsx:63`
주석이 그 사실에 명시적으로 기대고 있다. 그래서 **지금 동작은 옳다.**

문제는 그 분기를 지워도 red 가 되는 테스트가 없다는 것. TipTap 전환(#448) 이전에는 본문이
`textarea` 라 `tagName` 분기로 막혔고 `contentEditable` 분기는 관측면이 없었다. 전환이
그 분기를 **활성 경로로 바꿨는데 판별자는 따라오지 않았다.**

→ 가드를 쓸 때 `CLAUDE.md` §함정을 그대로 적용한다. **일부러 끊어 red 1회를 본다.**
안 보면 판별식이 헬프텍스트에 만족하는 형태(`[[invariant-satisfied-by-helptext-not-logic]]`)로
들어갈 수 있다.

## 2026-09-04 — C2 는 #446 결함의 짝까지 봐야 한다

`attachment:` 스킴 판별식을 `img[src]` 쪽만 쓰면 #446 에서 실제로 난 결함을 못 잡는다.
그 결함은 `allowUrlProtocols` 가 **정책 전역**이라 `img` 용으로 연 스킴이 `a[href]` 에도
열렸는데 UUID 술어는 `img[src]` 에만 있었던 것이다. 판별식이 한쪽만 보면 나머지 한쪽은
조용히 썩는다 — `[[partial-column-parser-lets-unread-column-rot]]` 와 같은 양식이다.

→ C2 는 `img` 통과와 `a` 차단을 **함께** 본다.

## 2026-09-04 — 워크트리 락이 살아 있다

`.claude/worktrees/editor-polish` 는 pid 10079(`claude bg-spare`, 2026-09-04 01:19 시작)가
잡고 있다. 커밋 0건 · `git status` clean 이라 편집 중인 파일은 없다.

**대응.** 인덱스가 워크트리 간 공유이므로 `git add` 를 자기 파일로 좁혀도 커밋 시점에 옆
세션이 스테이지해 둔 파일이 딸려 들어간다(`[[shared-worktree-git-index-defeats-narrow-git-add]]`
— 실측 사고 있음). **경로 한정 커밋 `git commit -- <경로>` 를 쓴다.**

## 미해결 · 착수 시 결정할 것

- **B 표시 규칙** — 카운터 상시 노출인지, 임계 근접 시에만인지. Jira 실물 대조 후 확정
- **B 저장 차단 여부** — 상한 초과 시 프론트가 저장을 막을지, 서버 400 에 맡길지
- **D 경계 처리** — 미리보기 불가 타입(`previewCategory` 분류)을 갤러리 이동에서 건너뛸지
- **A2 재현 가능 여부** — jsdom 에서 suggestion 좌표 의존을 우회 못 하면, e2e 만 증인으로
  남긴 이유를 여기 적는다
