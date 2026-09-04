# 에디터 마감 — 체크리스트

정본 계획 `../2026-09-04-editor-polish.md` · 결정 근거 `context-notes.md`

---

## 0. 착수

- [x] 워크트리 `.claude/worktrees/editor-polish` (브랜치 `worktree-editor-polish`) · base `959126eea`
- [x] 4개 항목 현재 코드 상태 실측 (계획 §범위 표)
- [x] FR ID 5건 `docs/plan/fr-index.md` 대조 — FR-IS-04 · FR-MN-02 · FR-UX-11 · FR-AC-01 · FR-AC-02 전부 실재
- [x] 계획 문서 3종 작성
- [ ] 첫 커밋 (`docs:`) + draft PR 생성
- [ ] 눈확인 선행 — 지난 캠페인 미실행분(툴바 15종 · 라이트/다크 · 스크린샷 붙여넣기)

## 1. A — 회귀 가드 2건 (`test:`)

### A1. 에디터 포커스 중 `i` 가 담당자 변경을 발화하지 않는다

- [ ] `shortcuts.test.ts` 에 contenteditable 타깃 케이스 추가
- [ ] **red 1회 확인** — `shortcuts.ts:212` 의 `isContentEditable` 분기를 일부러 끊어 red 를 본다.
      안 보면 「가드가 있는데 판별자가 없는」 상태를 그대로 물려받는다
- [ ] 원복 후 green
- [ ] 단축키 `i` 외에 같은 가드에 기대는 것이 더 있는지 전수 — `shortcuts.ts:239` 사용처

### A2. 멘션 `@` 후보 표시 단위 판별식

- [ ] 좌표 비의존 층을 특정 — `mention-extension.tsx` `items` · `MentionList` 렌더 · `onKeyDown` 반환값
- [ ] `Escape` 가 후보만 닫고 **본문을 버리지 않는다** 를 직접 잡는다 (#448 결함 재발 방지)
- [ ] jsdom 에서 suggestion 팝업 좌표 의존을 우회했는지 확인 — 우회 못 하면 그 사실을 여기 적고
      e2e 만 증인으로 남긴 이유를 `context-notes.md` 에 남긴다

## 2. C — 붙여넣기 경로 판별식 2건 (`test:`)

### C1. 업로드 → `attachment:` 삽입 → 첨부 목록 갱신 순서

- [ ] `use-editor-image-upload.ts` 대상 — 업로드 **완료 후에만** 노드 삽입인지
- [ ] 업로드 실패 시 본문에 노드가 안 들어가는지 (자리표시자 없음 = 상태 둘뿐, #449 결정)
- [ ] 첨부 목록 갱신이 삽입보다 늦지 않은지

### C2. `attachment:uuid` → blob 치환 렌더

- [ ] `AttachmentHtml.tsx` 치환 경로 판별식
- [ ] **`a[href]` 의 `attachment:` 는 막혀 있는지 함께 본다** — #446 결함(정책 전역
      `allowUrlProtocols` 로 `img` 용 스킴이 `a` 에도 열렸고 UUID 술어는 `img[src]` 에만 있었다)
- [ ] uuid 형태가 아닌 `attachment:` 값이 렌더되지 않는지

## 3. B — 본문·댓글 길이 카운터 (`feat:`)

- [ ] Jira 실물 대조 — 카운터 상시 노출인지, 임계 근접 시에만인지 (조회일 기록)
- [ ] 프론트 상수 정본 위치 결정 — 제목 255(`issue-create-schema.ts:20`)와 같은 자리
- [ ] **서버 상수와의 짝 판별식** — `IssueTextConstraints.DESCRIPTION_MAX`/`COMMENT_BODY_MAX`
      (둘 다 32767) 와 프론트 값이 어긋나면 red.
      짝 없이 두면 `[[two-lists-never-check-each-other]]` 그대로다
- [ ] TipTap `CharacterCount` 확장 도입 여부 결정 (현재 미설치) — 도입하면 `package.json` 변경
- [ ] 본문 카운터 배선
- [ ] 댓글 카운터 배선
- [ ] 상한 초과 시 저장 버튼 상태 — 막을지, 서버 400 에 맡길지 결정 후 기록
- [ ] 눈확인 — 라이트/다크 양쪽

## 4. D — 첨부 갤러리 좌우 이동 (`feat:`)

- [ ] `AttachmentPreviewModal` props 를 단수 `attachment` → 목록 + 현재 index 로
- [ ] 이전/다음 버튼
- [ ] 키보드 `←`/`→` 배선
- [ ] `role="dialog"` 고유 `aria-label` — 계약 §2 (실측 224건이라 이름 없이는 못 잡는다)
- [ ] 경계 — 목록 처음/끝
- [ ] 미리보기 불가 타입(`previewCategory` 분류)을 건너뛸지 포함할지 결정 후 기록
- [ ] `AttachmentPreviewModal.test.tsx` 갱신
- [ ] 눈확인

## 5. 게이트

- [ ] `apps/web/node_modules/.bin/tsc --noEmit`
- [ ] `apps/web/node_modules/.bin/eslint`
- [ ] `vitest related` (전량 금지 — 이 기계가 642파일 병렬을 못 버틴다)
- [ ] e2e 변경 표면 직격 그룹 **`--workers=1`**
      · 실행 전 `lsof -ti:5173 | xargs -r kill -9`
      · 5173 점유 프로세스의 cwd 가 **이 워크트리**인지 증명
- [ ] `grep -c 'outside of Vite serving allow list' <log>` — **0 아니면 수치 전량 버린다**
- [ ] 눈확인(계약 §6) 전량
- [ ] `node scripts/build-doc-index.mjs --check` — drift 0
- [ ] `bash scripts/verify-master-plan.sh`

## 6. 마감

- [ ] 커밋 5개가 성격대로 갈렸는지 (`docs:` / `test:` ×2 / `feat:` ×2)
- [ ] **경로 한정 커밋** — `git commit -- <경로>`. 인덱스가 워크트리 간 공유라
      `git add` 를 좁혀도 옆 세션 파일이 딸려 들어간다
      (`[[shared-worktree-git-index-defeats-narrow-git-add]]`)
- [ ] 뮤테이션 검증은 **GREEN 선커밋 뒤에만**
- [ ] PR body 에 미실행 항목과 사유 명시
- [ ] PR B(모달↔사이드패널 토글) 착수 조건 기록
