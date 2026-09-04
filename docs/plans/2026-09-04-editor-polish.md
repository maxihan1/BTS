# 에디터 마감 — 회귀 가드 · 길이 카운터 · 붙여넣기 판별식 · 첨부 갤러리 이동

> 티어: T1 (`apps/web/src` 단독)
> slug: editor-polish
> type: ui
> agent: frontend-engineer
> 생성: 2026-09-04

## Brief

이슈 상세 Jira 패리티 4-PR 캠페인(#446·#447·#448·#449)이 main 에 전부 들어갔다.
그 캠페인이 **의도적으로 남긴 미완 4건**을 닫는다. 새 기능이 아니라 마감이다.

캠페인 종료 시점의 `checklist.md` 가 `[ ]` 로 남긴 항목과, 리뷰·구현 중 발견됐지만
범위를 넘겨 미룬 판별식들이 대상이다.

**신규 FR 없음.** 기존 FR 의 D 단계 마감이다 —
FR-IS-04(본문 편집) · FR-MN-02(멘션 자동완성) · FR-UX-11(인라인 편집) ·
FR-AC-01(첨부 업로드) · FR-AC-02(첨부 미리보기).

## 범위 — 4건

| # | 항목 | 성격 | 현재 상태 (실측 2026-09-04) |
|---|---|---|---|
| A | 회귀 가드 2건 | `test:` | 미작성 |
| B | 본문·댓글 길이 카운터 | `feat:` | 프론트에 상한 상수 **0건** |
| C | 붙여넣기 경로 판별식 2건 | `test:` | 미작성 |
| D | 첨부 갤러리 좌우 이동 | `feat:` | 미구현 |

### A. 회귀 가드 2건 — `test:`

**A1. 에디터 포커스 중 `i` 가 담당자 변경을 발화하지 않는다.**
`shortcuts.ts:212` 의 `isEditableTarget` 이 `isContentEditable` 을 이미 본다
(`RichTextEditor.tsx:63` 주석이 그 사실에 기대고 있다). **동작은 하지만 가드가 없다** —
누군가 `isEditableTarget` 에서 `isContentEditable` 분기를 지워도 지금은 아무 테스트도 red 가
되지 않는다. TipTap 전환(#448) 이전에는 본문이 `textarea` 라 `tagName` 분기만으로 막혔고,
`contentEditable` 분기는 그때 관측면이 없었다.

- 대상 파일 — `apps/web/src/components/keyboard-shortcuts/shortcuts.test.ts`
- red 확인 방법 — `shortcuts.ts` 의 `isContentEditable` 판정을 일부러 끊고 1회 red 를 본다
  (`CLAUDE.md` §함정 「가드 수정 시 표면을 없애면 판별자도 사라진다」)

**A2. 멘션 `@` 후보 표시 단위 판별식.**
현재 증인이 e2e 4건뿐이다. jsdom 에서 suggestion 팝업이 좌표(`clientRect`)에 기대 재현되지
않는 것이 #448 에서 확인됐다. 좌표에 의존하지 않는 층 — `mention-extension.tsx` 의
`items` 조회 · `MentionList` 렌더 · `onKeyDown` 반환값 — 을 직접 잡는다.

- ★ #448 이 잡은 결함이 정확히 이 층이다 — ProseMirror `someProp` 이 view props
  (`editorProps.handleKeyDown`)를 플러그인보다 **먼저** 훑어 suggestion 의 `onKeyDown` 이
  영영 불리지 않았고, 그 결과 후보가 떠 있을 때 `Escape` 가 **작성 중인 본문을 통째로 버렸다**.
  이 판별식은 그 결함의 재발을 잡아야 한다.

### B. 본문·댓글 길이 카운터 — `feat:`

**체크포인트 기록(「지금은 `maxLength` 만 걸려 있다」)은 부정확하다. 실측은 그보다 나쁘다.**

| 층 | 제목 | 본문 | 댓글 |
|---|---|---|---|
| 서버 정본 | 255 | 32767 | 32767 |
| 프론트 상수 | `SUMMARY_MAX_LENGTH = 255` | **없음** | **없음** |
| 프론트 노출 | `maxLength` 속성 | **없음** | **없음** |

- 서버 정본 — `IssueTextConstraints.kt:45` `DESCRIPTION_MAX = 32767` ·
  `:48` `COMMENT_BODY_MAX = 32767` (V039 에서 기존 32000 을 Jira 값으로 정렬)
- 프론트 — `issue-create-schema.ts:20` 에 제목 255 만 있다. 32767 은
  `api/issues.ts:423`·`:430` **주석에만** 존재한다
- contenteditable 이라 `maxLength` 속성 자체를 걸 수 없다. TipTap `CharacterCount` 확장도
  **미설치**다 (`package.json` 조회 0건)

즉 사용자는 상한을 넘길 때까지 아무 신호를 못 받고 **저장 시 서버 400 으로 처음 안다**.
본문 32767자를 날리는 경로다.

- 프론트 상수 정본을 한 곳에 두고(제목 255 와 같은 자리), 서버 상수와의 **이중 목록**이
  생기는 것을 자각한다 → `[[two-lists-never-check-each-other]]`. 판별식으로 짝을 맞춘다
- 표시 규칙은 착수 시 Jira 실물 대조 후 확정한다 (상시 노출 vs 임계 근접 시 노출)

### C. 붙여넣기 경로 판별식 2건 — `test:`

#449 가 넣은 경로인데 **유닛으로도 e2e 로도 안 덮여 있다**.

**C1. 업로드 → `attachment:` 삽입 → 첨부 목록 갱신 — 순서.**
`use-editor-image-upload.ts:35` 주석이 계약을 적어 뒀다: 업로드가 끝난 뒤에만 노드를 넣는다
(자리표시자 없음 — 상태가 둘뿐이라 롤백 경로가 없다는 것이 #449 의 결정이다).
`:72` 가 `setImage({ src: 'attachment:<uuid>' })` 를 부른다. 이 순서가 뒤집히면
첨부 목록에 없는 uuid 를 본문이 참조한다.

**C2. `attachment:uuid` → blob 치환 렌더.**
`rich-text-extensions.ts:55` 가 「본문 이미지는 첨부 참조(`attachment:<uuid>`)만 유효」로
못박아 뒀다. 읽기 렌더에서 그 스킴을 실제 blob URL 로 바꾸는 층(`AttachmentHtml.tsx`)이
증인 없이 돌고 있다.

★ #446 의 리뷰 결함이 정확히 이 스킴 주변이었다 — `allowUrlProtocols` 가 **정책 전역**이라
`img` 용으로 연 `attachment:` 스킴이 `a[href]` 에도 열렸고, UUID 술어는 `img[src]` 에만 있었다.
판별식은 `a` 쪽 차단까지 함께 본다.

### D. 첨부 갤러리 좌우 이동 — `feat:`

`AttachmentPreviewModal.tsx:23` 의 props 가 `attachment: AttachmentResponse` **단수**다.
미리보기를 열면 그 하나에 갇히고 목록의 다음 이미지로 못 넘어간다.

- 목록 맥락(`AttachmentSection.tsx`)을 모달에 넘겨 이전/다음을 만든다
- 키보드 `←`/`→` 배선 — 모달이 `role="dialog"` 라 계약 §2 의 고유 `aria-label` 조항이 걸린다
  (`getByRole('dialog')` 실측 224건 · 이름으로 구분해야 한다)
- 경계 — 목록의 처음/끝, 미리보기 불가 타입(`previewCategory` 가 분류)을 건너뛸지 포함할지

## Jira 대조

조회일 **2026-09-04**. 이 PR 은 직전 4-PR 캠페인(#446~#449)의 마감이라 근거 표가
`2026-09-04-issue-detail-jira-parity.md` 와 겹친다. 여기서는 **이 PR 이 실제로 건드리는
두 갈래**만 다시 적는다.

| # | 항목 | Jira Cloud 실물 | 출처 |
|---|---|---|---|
| J4 | 제목 길이 | "The 255 character limit is for single-line text fields, like Summary. You can not change it." | [커뮤니티 공식 답변](https://community.atlassian.com/forums/Jira-questions/Summary-must-be-less-than-255-characters/qaq-p/989632) |
| J5 | 본문 길이 | "Description is a long text that defaults to max 32767 characters" | [커뮤니티 공식 답변](https://community.atlassian.com/forums/Jira-questions/Character-limit-of-Description-field-in-Jira-Software-cloud/qaq-p/2655913) |
| J6 | 첨부 미리보기 | "Click an image thumbnail to open a preview… If your Jira admin has disabled thumbnails in Jira's attachment settings, the image files will appear as a list" — 썸네일이 기본, 미리보기는 그 썸네일에서 연다 | [첨부·스크린샷](https://support.atlassian.com/jira-service-management-cloud/docs/attach-files-and-screenshots-to-issues/) (Cloud · JSM) |

### 채택

- **길이 상한 값**(B) — 제목 255 · 본문 32,767 · 댓글 32,767. 백엔드는 #446 에서 이미 이 값으로
  정렬했다. 이 PR 은 그 값을 **프론트에 노출**한다.
- **미리보기가 첨부 목록의 한 장에서 열린다**(D) — 목록이 미리보기의 맥락이라는 것이
  좌우 이동의 근거다.

### 의도적 편차

| # | 편차 | 근거 |
|---|---|---|
| **X6** | 카운터를 **상시 노출하지 않는다** — 임계(90%)에 닿을 때만 나타난다 | Jira Cloud 사용자 문서에 카운터의 표시 규칙에 대한 서술이 없다(조회 2026-09-04). 상한이 32,767자라 일상적 글쓰기가 닿지 않으므로, 늘 띄우면 모든 에디터 아래에 쓸모없는 숫자가 붙고 정작 필요한 순간의 신호가 소음에 묻힌다. 「숫자가 보인다는 것 자체가 경고」로 설계한다 |
| **X7** | 상한 초과 시 **프론트가 저장을 막는다** | Jira 동작 미확인. 막지 않으면 사용자가 32,767자를 다 쓴 뒤 서버 400 으로 처음 알게 된다 — 그 시점에는 이미 손실을 인지할 방법이 없다. 되돌릴 수 없는 쪽을 피한다 |
| **X8** | 재는 문자열이 필드마다 다르다 — 본문은 **HTML**, 댓글은 **평문** | Jira 는 ADF 를 쓰므로 대응 개념이 없다. BTS 서버가 실제로 재는 값을 그대로 따른다 — `@Size` on `descriptionHtml` 대 `CommentApplicationService.validateBody(body)`. 한쪽에 맞추면 다른 쪽에서 카운터가 거짓말을 한다 |
| **X9** | 갤러리 이동은 **키보드 ←/→ 와 버튼**만. 스와이프 제스처 없음 | Jira Cloud 문서에 모바일 제스처 서술이 없다(조회 2026-09-04). 데스크톱 우선인 이 저장소의 관례를 따르고, 새 제스처 자산을 만들지 않는다(계약 §4) |

## 범위 밖 — PR B 로 분리

**모달↔사이드패널 토글** (Jira J1 의 "persist across Jira views within the same session").
스토어에 `presentation` 필드만 있고 UI 가 없다.

**왜 가르는가.** A~D 는 에디터·첨부 컴포넌트 안에서 끝나지만, 토글은
`routes/issues.index.tsx`(목록 split view)를 건드리고 e2e 의존이 많다. 섞으면 토글이 막힐 때
나머지 4건이 함께 발이 묶이고, e2e 가 깨졌을 때 원인 분리에 시간이 든다.
**이번 캠페인에서 e2e 디버깅이 제일 비쌌다** — 그 실측이 이 분할의 근거다.

## 커밋 분할

성격대로 4개로 가른다. T1 이라 `test:` → `feat:` 순서 강제는 아니지만, A·C 가 순수 가드라
자연히 red-first 가 된다.

| 순서 | 커밋 | 내용 |
|---|---|---|
| 1 | `docs:` | 이 계획 3종 (draft PR 을 열기 위한 첫 커밋) |
| 2 | `test:` | A 회귀 가드 2건 |
| 3 | `test:` | C 붙여넣기 경로 판별식 2건 |
| 4 | `feat:` | B 길이 카운터 |
| 5 | `feat:` | D 갤러리 좌우 이동 |

## 게이트 (T1 — 리뷰 1종 · 게이트 2)

- `apps/web/node_modules/.bin/tsc --noEmit` · `eslint` · `vitest related`
- e2e 는 **`--workers=1`** 로, 변경 표면 직격 그룹만
- **눈확인(계약 §6)** — 지난 캠페인이 미실행으로 남겼다. 툴바 15종 · 라이트/다크 ·
  스크린샷 붙여넣기. 이번 PR 의 B·D 는 시각 변화라 눈확인이 필수다
- `node scripts/build-doc-index.mjs --check`

## 선재 결함 (이 PR 이 만든 것 아님 · 판정 시 제외)

- **e2e 병렬 flaky** — 병렬이면 매번 다른 6~9건이 실패하고 워커 1개면 전부 통과.
  두 실패 집합이 겹치지 않는 것을 실측했다
- **`select-test-scope.ts` 의 `@{u}` base 결함** — 리베이스한 뒤 push 를 아직 안 했으면 diff 가
  부풀어 백엔드 전량(약 55분)으로 넓어진다
- main 체크아웃 선재 e2e 실패 3파일 8건 (`fr-au-05-signup` · `workflow.spec` ·
  `workflow-scheme-assignment`) — 전부 에디터 무관
