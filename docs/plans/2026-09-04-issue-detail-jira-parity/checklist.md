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

> 파일명은 `HtmlSanitizeTest` 로 굳었다(계획의 `HtmlSanitizerTest` 아님).

- [x] `HtmlSanitizeTest` — `attachment:<uuid>` src 통과
- [x] `HtmlSanitizeTest` — `http://evil/x.png` · `javascript:` · `data:` src 제거
- [x] `HtmlSanitizeTest` — `<img onerror=...>` 속성 제거
- [x] `HtmlSanitizeTest` — `<table><tr><td>` · `<del>` · `<hr>` · `<u>` 통과
- [x] `HtmlSanitizeTest` — `<script>` · `<iframe>` · `<svg>` 여전히 제거 (회귀 가드)
- [x] `MarkdownRendererTest` — `~~취소선~~` → `<del>` (flexmark Strikethrough 확장)
- [x] `MarkdownRendererTest` — 표 문법 → `<table>` (Tables 확장)
- [x] `MarkdownRendererTest` — `- [ ]` → 체크박스 (TaskList 확장)
- [x] 길이 정합 — `IssueTextConstraintsAlignmentTest` 가 층간 정합을 강제한다.
      HTTP 400 경계를 따로 두는 대신 **도메인 경계(상한 통과 / +1 거부) + 각 DTO `@Size(max)` 가
      상수와 같다**를 단언한다 — 숫자를 다시 적지 않으므로 상수만 고치면 전 층이 따라온다
- [x] `UpdateIssueRequest` — `description` 과 `descriptionHtml` 동시 전달 시 400 (`isBodyExclusive`)
- [x] `description_plain` 이 태그를 제거한다
- [x] AQL `text ~` 가 `description_plain` 기준으로 여전히 맞는다
- [x] **`EXPLAIN` 단언** — `idx_issues_description_trgm` 이 실제로 선택된다 (죽은 인덱스 가드)
      + 비-공허 짝(다른 표현식은 이 인덱스를 못 쓴다)
- [x] **red 확인 (비-공허)** — `HtmlSanitizeTest` 착수 시 10건 red 확인 후 green
- [x] **[리뷰 추가]** `a[href]` 가 `attachment:` 스킴을 통과시키지 않는다 + 비-공허 짝
      (http·https·mailto·상대 경로는 통과) — §1.6 참조
- ~~마이그레이션 통합 — 백필 전 markdown 행이 백필 후 기대 HTML~~
  → **폐기**. 백필 자체를 버렸다(§1.2). 옛 행은 읽기 지점의 `html ?: renderSafe(markdown)`
  fallback 이 흡수하고, 그 이슈가 편집되는 순간 이행이 끝난다

### 1.2 GREEN — 마이그레이션 (`V039__description_html.sql`)

> **백필을 버렸다.** Flyway Java migration 은 **등록 지점이 둘**이다 — 조립 앱은
> `FlywayAssemblyConfig` 가, 단독 테스트는 `application-test.yml` 의 Spring auto-config 가 돈다.
> 한쪽만 등록하면 조립 앱에서 백필이 조용히 건너뛰어진다(두 목록이 서로를 검사하지 않는 지배 결함).
> 대신 기존 컬럼을 **그대로 두고** HTML 컬럼을 새로 뒀다. 덕분에 마이그레이션이 순수 SQL 이고
> 마크다운 원문도 영구 보존된다. 백업 컬럼도 그래서 필요 없어졌다.

- [x] `issues.description_html` 신설 (NULL = 아직 HTML 로 저장된 적 없음)
- [x] `issue_body_plain(html, md)` — 평문 추출의 단일 출처.
      PostgreSQL 이 generated → generated 참조를 금지해(실측) `description_plain` 과
      `search_vector` 가 식을 복사하는 대신 **같은 함수**를 각각 호출한다
- [x] `description_plain` GENERATED ALWAYS AS STORED
- [x] `search_vector` 생성식을 `summary || issue_body_plain(...)` 으로 교체 (DROP 후 재생성)
- [x] `idx_issues_description_trgm` 을 `lower(description_plain)` 으로 재생성
- [x] `comments.body_html` 신설 (읽기 지점 `CommentView.of()`)
- [x] 컬럼 주석 갱신 (마크다운 → HTML · fallback 경로 명시)
- [x] `init_codegen.sql` 미러 + jOOQ `excludes` 에 `description_plain` 추가
      (STORED generated 라 포함시키면 record INSERT 가 500 이 된다 — `search_vector` 와 동형)
- ~~`description_md_backup` 컬럼 신설 + 원문 복사~~ → **폐기**. `description` 이 원문 그대로 남는다
- ~~Flyway Java migration 백필 2건~~ → **폐기**. 위 사유

### 1.3 GREEN — sanitize · flexmark

- [x] `renderSafe`(마크다운) + `sanitizeHtml`(에디터 HTML) 두 진입점.
      **같은 `SANITIZE_POLICY` 를 쓴다** — 정책이 갈라지면 한 입구로 들어온 페이로드가
      다른 입구의 테스트를 통과한 채 살아남는다
- [x] allowlist 확장 — `u` `del` `s` `hr` `table` `thead` `tbody` `tfoot` `tr` `th` `td`
      `img` `input[type=checkbox]`
- [x] `img[src]` 를 `attachment:<uuid>` 정규식으로만 허용.
      ★스킴 등록(`allowUrlProtocols`)과 UUID 술어(`matching`)가 **둘 다** 필요하다 —
      OWASP 가 `src` 를 URL 속성으로 특별 취급해 스킴을 술어보다 **먼저** 잘라낸다(실측)
- [x] flexmark `StrikethroughExtension` · `TablesExtension` · `TaskListExtension` 등록
      (파서와 렌더러가 `EXTENSIONS` 상수 **하나**를 본다)
- [x] `RawInlineHtmlRenderer` 정책이 새 태그와 충돌하지 않는지 확인
- ~~`MarkdownRenderer.kt` → `HtmlSanitizer` 로 개명~~ → **보류**. 개명은 호출 지점 전수 변경이라
      이 PR 의 표면을 넓힌다. 두 진입점만 추가하고 파일명은 그대로 뒀다

### 1.4 GREEN — DTO · 서비스

> 값의 단일 출처는 `IssueTextConstraints` 다. 아래는 전부 그 상수를 참조한다.

- [x] `CreateIssueRequest.summary` `@Size(max)` 200 → `SUMMARY_MAX`(255)
- [x] `UpdateIssueRequest.summary` `@Size(max)` 200 → `SUMMARY_MAX`(255)
- [x] `UpdateIssueRequest.description` `@Size(max)` 65535 → `DESCRIPTION_MAX`(32767)
- [x] `CreateIssueRequest.description` — 착수 시점에 `@Size` 가 **아예 없었다**(선재 결함).
      상한 없이 만든 본문이 나중에 편집 불가가 되는 비대칭이었다
- [x] `UpdateIssueRequest.descriptionHtml` 신규 필드 + 상호배타 검증(`isBodyExclusive`)
- [x] `CommentApplicationService.MAX_BODY_LENGTH` 32000 → `COMMENT_BODY_MAX`(32767)
- [x] jOOQ `DESCRIPTION.likeIgnoreCase` → raw DSL `lower(issues.description_plain) like ...`
      (codegen 제외 컬럼이라 `search_vector` 와 동형으로 raw 조건을 쓴다 · B1 표현식 일치)

### 1.5 게이트

- [x] `./gradlew :modules:issue-tracking:test ktlintCheck detekt` — 3487건 + 린트 초록
- [x] main 리베이스 후 **캐시 없이** 재검증 — `--no-build-cache --rerun-tasks` 로
      issue-tracking 3487 + 조립 앱(`:modules:app:test`) 79 전부 초록.
      ★첫 재실행은 전부 UP-TO-DATE 였다(「BUILD SUCCESSFUL in 1s」) — 초록으로 보이지만
      **테스트가 돌지 않은 상태**다. 실행 여부는 XML 타임스탬프로 확인한다
- [x] `bash scripts/verify-master-plan.sh` EXIT 0 — FR 144/144 매핑·카운트 정합
- [x] 보안 렌즈 검토 (sanitize 변경) — 결함 1건 적발·수정, §1.6

### 1.6 리뷰 적발 — `a[href]` 가 `attachment:` 스킴을 통과시켰다

`allowUrlProtocols` 는 **요소별이 아니라 정책 전역**이다. `img` 를 위해 연 `attachment` 스킴이
`a[href]` 에도 그대로 열렸고, UUID 술어는 `img[src]` 에만 걸려 있어 `a` 로 들어온 임의 경로를
아무도 막지 않았다 — `<a href="attachment:../../etc/passwd">` 가 그대로 통과했다(실측).

`javascript:` 는 여전히 차단되니 XSS 는 아니지만, PR④ 가 프론트에서 `attachment:` 참조를
API 경로로 치환하므로 그대로 두면 경로 조작 표면이 된다.

- [x] red 확인 — 금지 단언 1건이 실제로 빨갛고, 비-공허 짝(http·https·mailto)은 초록
- [x] `href` 에 스킴 술어 추가 — 상대 경로·앵커·`http(s)`·`mailto` 는 종전대로 통과(실측 대조)

---

## PR ② 레이아웃 — 모달 · 활동 위치 · 기본탭 (T1)

### 2.1 RED

- [x] 진입점 클릭 시 모달이 열리고 URL 이 바뀌지 않는다
- [x] ⌘/Ctrl+클릭은 모달을 열지 않는다 (새 탭 기본 동작 보존)
- [x] `/issues/KEY` 직접 진입은 전체 페이지다
- [x] 활동 탭이 본문 `<section>` **안**에 있다 (DOM 포함 관계 단언)
- [x] 기본 활성 탭이 댓글이다
- [x] 제목 입력 `maxLength` 가 255다
- [x] 모달 `aria-label` 이 이슈 키를 포함해 고유하다
- [x] `issue-detail-modal-gate.test.ts` 가 여전히 초록 (모달 보고 신호)
- [x] **red 확인 (비-공허)**

### 2.2 GREEN

- [x] `IssueDetailModal.tsx` 신규 — Dialog 껍데기만
- [x] 모달 열림 전역 스토어 (zustand, 비영속) + `__root.tsx` 단일 마운트
- [x] `useReportModalOpen` 배선 (`IssueDetailPage` 가 이미 보고)
- [x] modifier key 검사로 새 탭 보존 — `useOpenIssueDetail` 훅 하나로 모음
- [x] `IssueActivityTabs` 를 본문 section 안 `AttachmentSection` 아래로 이동
- [x] `defaultValue` HISTORY → COMMENT · 탭 순서 Jira 순
- [x] 기존 D1 결정 번복 사실을 주석에 날짜와 함께 남긴다
- [x] `SUMMARY_MAX_LENGTH` 200 → 255 · i18n 문구도 함께
- [x] 상세 제목 `Input` 에 `maxLength`

**계획에서 바뀐 것 / 안 한 것**

- [x] ~~`variant` 에 `'modal'` 추가~~ — **하지 않았다**. 기존 `variant='pane'` 이 닫기·Escape·
      `<h2>` 강등·콜백 위임을 전부 갖고 있어 새 분기는 같은 코드를 두 벌로 만든다
- [x] 진입점 **11곳** 전환 (계획은 13곳) — `issue-columns.ts` 는 목록 split view 에 위임하므로
      제외했고(유지 결정), `TopBar` 생성 토스트는 방금 만든 이슈로 가는 것이라 전체 페이지가 맞다
- [ ] `…` 메뉴 「사이드 패널로 열기」 + `sessionStorage` 선호 — **미구현**. 스토어에 `presentation`
      필드는 두었으나 토글 UI 와 목록 split view 연동은 후속
- [ ] 본문·댓글 길이 카운터 — **미구현**. `maxLength` 만 걸었다

### 2.3 게이트

- [x] `pnpm typecheck && pnpm lint && pnpm test` — 10,574건 초록
- [x] 상세 진입 e2e — **돌렸고 8건이 red 였다**. 전부 같은 원인 — 모달이라 URL 이 안 바뀌는데
      spec 이 `waitForURL('**/issues/KEY')` 로 도착지를 재고 있었다(유닛은 스토어 단언으로
      갱신했지만 e2e 는 빠졌다). 도착지를 `getByRole('dialog', { name: '이슈 상세 <KEY>' })` 로
      옮기고 `heading level 1` → level 2 로 함께 옮겼다. `calendar` E2E-4 ·
      `command-palette` S3·S9·S12·S13 · `inbox` E2E-8 · `issue-link-graph` G5·G6
- [x] `goto('/issues/KEY')` 46건은 무영향 확인 — 전체 페이지 경로가 그대로 살아 있다
- [ ] **병렬 flaky (선재 · 이 PR 소관 아님)** — 병렬로 돌리면 매번 다른 조합이 6~9건 실패하고
      워커 1개로는 전부 통과한다. 두 실패 집합이 겹치지 않는 것을 실측했다.
      vite dev 서버·MSW store 공유 문제. 고아 vite 가 5173 을 점유하면 실행 자체가 죽는다
- [ ] 눈확인 — 라이트/다크 · 클릭 모달 · ⌘클릭 새 탭 · 댓글 기본탭 · 활동 위치 — **미실행**

---

## PR ③ 에디터 — TipTap WYSIWYG (T1)

### 3.1 RED

- [x] 툴바 서식 11종이 각각 기대 태그를 만든다 (마크 5 + 블록 6)
- [x] 제목 드롭다운이 h1~h6 전부 제공 + H2 선택이 `<h2>` 를 만든다
- [x] 서식 버튼이 `aria-pressed` 로 활성 상태를 노출한다
- [x] `⌘Enter`·`Ctrl+Enter` 저장 / 맨 Enter 는 저장 안 함
- [x] `Esc` 취소 / **IME 조합 중 Esc 는 취소하지 않는다**
- [x] `onChange` 가 마크다운이 아니라 HTML 을 넘긴다
- [x] `editable=false` 잠금 + 비-공허 짝
- [x] 저장 시 `descriptionHtml` 로 PATCH 한다 (라우트 통합 테스트)
- [x] 미저장 변경 확인 패널이 HTML 비교로 동작한다 (`IssueDescription` 8건)
- [x] 댓글도 같은 컴포넌트를 쓴다
- [x] **red 확인 (비-공허)** — 굵게 명령을 끊어 정확히 1건 red 확인
- [ ] 멘션 `@` 가 후보를 띄우고 `span.mention` 을 만든다 — **단위 판별식 미작성**.
      jsdom 에서 suggestion 팝업이 좌표에 기대 재현되지 않는다. e2e 몫으로 남긴다
- [ ] 에디터 포커스 중 `i` 가 담당자 변경을 발화하지 않는다 — **회귀 가드 미작성**.
      `isEditableTarget` 이 `isContentEditable` 을 이미 보므로 동작은 하지만 가드가 없다

### 3.2 GREEN

- [x] TipTap 의존성 추가 (pnpm) — starter-kit 이 Underline·Link 를 이미 포함해 중복 5개 제거
- [x] `components/editor/RichTextEditor.tsx` 신규
- [x] `rich-text-extensions.ts` — 서버 allowlist 와 짝을 이루는 확장 구성
- [x] 툴바 15종 (`RichTextToolbar.tsx` + `i18n/editor-labels.ts`)
- [x] 단축키 — 서식은 TipTap 확장 소유, 폼 키 2개만 직접 배선
- [x] `Mention` 확장 — 기존 `MentionDropdown` UI 재사용 · `span.mention` 마크업 호환
- [x] `IssueDescription` 전환 · Write/Preview 탭 제거 (i18n 키 2개도 삭제)
- [x] `CommentSection` 전환 (작성·편집 양쪽) + `html-text.ts` 평문 추출
- [x] 미저장 확인 패널 판정식 교체 (마크다운 비교 → HTML 비교)
- [x] 공유 테스트 대역 `test/rich-text-editor-mock.tsx` — prop 을 전부 실제로 쓴다

### 3.3 게이트

- [x] `pnpm typecheck && pnpm lint && pnpm test`
- [x] `IssueDescription.test.tsx` 재작성 — 살릴 계약(폐기 확인·권한·클릭 진입) 이관 완료
- [x] 부채 감소 2건 — `EditMode` 가 200줄 밑으로(래칫 항목 삭제) · 원시 `<button>` 2건 소멸
- [ ] 멘션 e2e 2종 (`issue-mention-autocomplete` · `issue-mention-render`) — **미갱신**.
      Write 탭 셀렉터에 의존하므로 TipTap 기준으로 다시 써야 한다
- [ ] 눈확인 — 툴바 15종 각각 — **미실행**

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
