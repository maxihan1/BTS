# checklist — 이슈 상세 Jira 패리티 4-PR 캠페인

모달 · 활동 레이아웃 · 길이 제약 · TipTap 에디터 · 첨부 썸네일.
계획 정본 `../2026-09-04-issue-detail-jira-parity.md` · 결정 근거 `context-notes.md`.

## 0. 착수

- [x] worktree `issue-detail-jira-parity` 생성
- [x] 선행 실측 4종 (계약 §5) — e2e 37 · goto 46/21 · dialog 224 · 본문 테스트 8파일
- [x] plan · checklist · context-notes 생성
- [ ] `pnpm install` 후 `node_modules/.bin` 존재 확인 (worktree 심볼릭 함정)
- [ ] husky 훅이 worktree 에서 실제로 도는지 확인

---

## PR ① 백엔드 — HTML 저장 · sanitize · 필드 길이 (T3)

### 1.1 RED — `test:` 커밋

- [ ] `HtmlSanitizerTest` — `attachment:<uuid>` src 통과
- [ ] `HtmlSanitizerTest` — `http://evil/x.png` · `javascript:` · `data:` src 제거
- [ ] `HtmlSanitizerTest` — `<img onerror=...>` 속성 제거
- [ ] `HtmlSanitizerTest` — `<table><tr><td>` · `<del>` · `<hr>` · `<u>` 통과
- [ ] `HtmlSanitizerTest` — `<script>` · `<iframe>` · `<svg>` 여전히 제거 (회귀 가드)
- [ ] `MarkdownRendererTest` — `~~취소선~~` → `<del>` (flexmark Strikethrough 확장)
- [ ] `MarkdownRendererTest` — 표 문법 → `<table>` (Tables 확장)
- [ ] `MarkdownRendererTest` — `- [ ]` → 체크박스 (TaskList 확장)
- [ ] 길이 경계 — summary 255 통과 / 256 은 400
- [ ] 길이 경계 — description 32767 통과 / 32768 은 400
- [ ] 길이 경계 — comment 32767 통과 / 32768 은 400
- [ ] `UpdateIssueRequest` — `description` 과 `descriptionHtml` 동시 전달 시 400
- [ ] 마이그레이션 통합 — 백필 전 markdown 행이 백필 후 기대 HTML
- [ ] `description_plain` 이 태그를 제거한다
- [ ] AQL `text ~` 가 `description_plain` 기준으로 여전히 맞는다
- [ ] **`EXPLAIN` 단언** — `idx_issues_description_trgm` 이 실제로 선택된다 (죽은 인덱스 가드)
- [ ] **red 확인 (비-공허)** — 전부 실제로 빨간지 눈으로 본다

### 1.2 GREEN — 마이그레이션

- [ ] `V0xx__description_html.sql` — `description_md_backup` 컬럼 신설 + 원문 복사
- [ ] `V0xx` — `description_plain` GENERATED ALWAYS AS STORED
- [ ] Flyway Java migration — `issues.description` markdown → HTML 백필
- [ ] Flyway Java migration — `comments.body` markdown → HTML 백필
- [ ] `search_vector` 생성식을 `summary || description_plain` 으로 교체
- [ ] `idx_issues_description_trgm` 을 `lower(description_plain)` 으로 재생성
- [ ] 컬럼 주석 갱신 (마크다운 → HTML)

### 1.3 GREEN — sanitize · flexmark

- [ ] `MarkdownRenderer.kt` → `HtmlSanitizer` 로 개명, `renderSafe` + `sanitize` 두 진입점
- [ ] allowlist 확장 — `u` `del` `s` `hr` `table` `thead` `tbody` `tr` `th` `td` `img` `input[checkbox]`
- [ ] `img[src]` 를 `attachment:<uuid>` 정규식으로만 허용
- [ ] flexmark `StrikethroughExtension` · `TablesExtension` · `TaskListExtension` 등록
- [ ] `RawInlineHtmlRenderer` 정책이 새 태그와 충돌하지 않는지 확인

### 1.4 GREEN — DTO · 서비스

- [ ] `CreateIssueRequest.summary` `@Size(max)` 200 → 255
- [ ] `UpdateIssueRequest.summary` `@Size(max)` 200 → 255
- [ ] `UpdateIssueRequest.description` `@Size(max)` 65535 → 32767
- [ ] `UpdateIssueRequest.descriptionHtml` 신규 필드 + 상호배타 검증
- [ ] `CommentApplicationService.MAX_BODY_LENGTH` 32000 → 32767
- [ ] jOOQ `DESCRIPTION.likeIgnoreCase` → `DESCRIPTION_PLAIN`

### 1.5 게이트

- [ ] `./gradlew :modules:issue-tracking:test ktlintCheck detekt`
- [ ] `bash scripts/verify-master-plan.sh` EXIT 0
- [ ] security-engineer 검토 (sanitize 변경)

---

## PR ② 레이아웃 — 모달 · 활동 위치 · 기본탭 (T1)

### 2.1 RED

- [ ] 진입점 클릭 시 모달이 열리고 URL 이 바뀌지 않는다
- [ ] ⌘/Ctrl+클릭은 모달을 열지 않는다 (새 탭 기본 동작 보존)
- [ ] `/issues/KEY` 직접 진입은 전체 페이지다
- [ ] 활동 탭이 본문 `<section>` **안**에 있다 (DOM 포함 관계 단언)
- [ ] 기본 활성 탭이 댓글이다
- [ ] 제목 입력 `maxLength` 가 255다
- [ ] 모달 `aria-label` 이 이슈 키를 포함해 고유하다
- [ ] `issue-detail-modal-gate.test.ts` 가 여전히 초록 (모달 보고 신호)
- [ ] **red 확인 (비-공허)**

### 2.2 GREEN

- [ ] `IssueDetailPage` `variant` 에 `'modal'` 추가
- [ ] `IssueDetailModal.tsx` 신규 — Dialog 껍데기만
- [ ] 모달 열림 전역 스토어 (zustand, 비영속)
- [ ] `useReportModalOpen` 배선
- [ ] 진입점 13곳 전환 — `BoardCard` · `BacklogCard` · `InboxListItem` · `ProjectActivityFeed` · `IssueListGadget` · `RecentIssuesMenu` · `CommandPalette`×2 · `CalendarView` · `TopBar`×2 · `EpicChildrenSection` · `LinkGraph`×2 · `issue-columns`
- [ ] modifier key 검사로 새 탭 보존
- [ ] `…` 메뉴 「사이드 패널로 열기」 + `sessionStorage` 선호 유지
- [ ] `IssueActivityTabs` 를 본문 section 안 `AttachmentSection` 아래로 이동
- [ ] `defaultValue` HISTORY → COMMENT · 탭 순서 Jira 순
- [ ] 기존 D1 결정 번복 사실을 주석에 날짜와 함께 남긴다
- [ ] `SUMMARY_MAX_LENGTH` 200 → 255
- [ ] 상세 제목 `Input` 에 `maxLength`
- [ ] 본문·댓글 길이 상수 + 임계 이하일 때만 보이는 카운터

### 2.3 게이트

- [ ] `pnpm typecheck && pnpm lint && pnpm test`
- [ ] 상세 진입 e2e 37 spec 전량
- [ ] 눈확인 — 라이트/다크 · 클릭 모달 · ⌘클릭 새 탭 · 댓글 기본탭 · 활동 위치

---

## PR ③ 에디터 — TipTap WYSIWYG (T1)

### 3.1 RED

- [ ] 툴바 15종이 각각 기대 태그를 만든다
- [ ] 단축키 10종이 각각 동작한다 (⌘⇧S · ⌘⇧M · ⌘⇧7/8/9 는 직접 배선분)
- [ ] 저장 시 `descriptionHtml` 로 PATCH 한다
- [ ] 멘션 `@` 가 후보를 띄우고 `span.mention` 을 만든다
- [ ] 에디터 포커스 중 `i` 가 담당자 변경을 발화하지 않는다 (회귀 가드)
- [ ] `⌘Enter` 저장 · `Esc` 취소가 살아 있다
- [ ] 미저장 변경 확인 패널이 HTML 비교로 동작한다
- [ ] 댓글도 같은 컴포넌트를 쓴다
- [ ] **red 확인 (비-공허)**

### 3.2 GREEN

- [ ] TipTap 의존성 추가 (pnpm)
- [ ] `components/editor/RichTextEditor.tsx` 신규
- [ ] 툴바 15종
- [ ] 단축키 배선
- [ ] `Mention` 확장 — 후보 조회 로직 재사용 · `span.mention` 마크업 호환
- [ ] `IssueDescription` 전환 · Write/Preview 탭 제거
- [ ] `CommentSection` 전환
- [ ] 미저장 확인 패널 판정식 교체

### 3.3 게이트

- [ ] `pnpm typecheck && pnpm lint && pnpm test`
- [ ] 멘션 e2e 2종 (`issue-mention-autocomplete` · `issue-mention-render`)
- [ ] `IssueDescription.test.tsx` 삭제 전 살릴 단언 이관 확인
- [ ] 눈확인 — 툴바 15종 각각

---

## PR ④ 첨부 — 썸네일 · 본문 이미지 (T1)

### 4.1 RED

- [ ] 이미지 첨부가 썸네일로, 비이미지가 아이콘 타일로 뜬다
- [ ] 5MB 초과 이미지는 썸네일을 그리지 않는다
- [ ] blob URL 이 언마운트 시 revoke 된다
- [ ] 붙여넣기가 업로드 → `attachment:` 삽입 → 첨부 목록 갱신 순으로 간다
- [ ] 업로드 실패 시 자리표시자가 사라진다
- [ ] `attachment:uuid` 가 blob 으로 치환돼 렌더된다
- [ ] 갤러리 좌우 이동이 동작한다
- [ ] **red 확인 (비-공허)**

### 4.2 GREEN

- [ ] `AttachmentSection` 썸네일 그리드
- [ ] `use-attachment-image-src` 훅 — blob 치환 (문자열 치환 금지, 노드 교체)
- [ ] 에디터 `handlePaste` · `handleDrop` · 툴바 이미지 버튼
- [ ] 업로드 자리표시자 · 실패 롤백
- [ ] 첨부 쿼리 invalidate
- [ ] `AttachmentPreviewModal` 갤러리 좌우 이동
- [ ] 서버 리사이즈 엔드포인트를 `TODOS.md` 에 별건 등록

### 4.3 게이트

- [ ] `pnpm typecheck && pnpm lint && pnpm test`
- [ ] 첨부 e2e 2종 (`issue-attachments` · `issue-attachment-preview`)
- [ ] 눈확인 — 스크린샷 붙여넣기 → 본문 + 첨부 목록 동시 반영

---

## 마감

- [ ] 시각 회귀 기준선(`e2e/visual/__screenshots__/`) 갱신 필요 여부 판정
- [ ] `pnpm --filter web test:e2e` 전량
- [ ] `node scripts/build-doc-index.mjs --check`
- [ ] `jira-parity-roadmap.md` F7 완료 마킹
- [ ] FR 매핑 확정 · 필요 시 `fr-sync-checklist.md` 전수 동기화
