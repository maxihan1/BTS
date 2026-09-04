# 에디터 마감 — 체크리스트

정본 계획 `../2026-09-04-editor-polish.md` · 결정 근거 `context-notes.md`

---

## 0. 착수

- [x] 워크트리 `.claude/worktrees/editor-polish` (브랜치 `worktree-editor-polish`) · base `959126eea`
- [x] 4개 항목 현재 코드 상태 실측 (계획 §범위 표)
- [x] FR ID 5건 `docs/plan/fr-index.md` 대조 — FR-IS-04 · FR-MN-02 · FR-UX-11 · FR-AC-01 · FR-AC-02 전부 실재
- [x] 계획 문서 3종 작성
- [x] 첫 커밋 (`docs:`) + draft PR 생성 — #453
- [ ] 눈확인 선행 — 지난 캠페인 미실행분(툴바 15종 · 라이트/다크 · 스크린샷 붙여넣기)

## 1. A — 회귀 가드 2건 (`test:`)

### A1. 에디터 포커스 중 `i` 가 담당자 변경을 발화하지 않는다

- [x] ~~`shortcuts.test.ts` 에 contenteditable 타깃 케이스 추가~~ — **자리를 바꿨다.**
      술어 테스트(`shortcuts.test.ts:106`)는 **이미 있었다**. 없던 것은 `useKeyboardShortcuts.ts:156`
      의 **가드 호출 배선**을 보는 판별식이다. 기존 배선 테스트(E4·E10)는 `<input>` 만 썼다.
      그래서 `useKeyboardShortcuts.test.tsx` 에 contenteditable describe 를 세웠다
- [x] **red 1회 확인** — 뮤테이션 2종. 가드 호출 제거 → 5건 red · contenteditable 속성 분기
      제거 → 4건 red
- [x] 원복 후 green
- [x] issue-detail 컨텍스트 키 전종(`i a m e l`) + 전역 키(`c`)를 함께 잰다 — 가드 한 줄이
      둘을 함께 막는 구조라 한쪽만 재면 나머지가 조용히 썩는다

### A2. 멘션 `@` 후보 표시 단위 판별식

- [x] 좌표 비의존 층 특정 — `MentionList` 키 위임 11건 (렌더 · 순환 · Enter/Tab 확정 ·
      0건 미가로챔 · displayName 결손 fallback · 마우스 선택)
- [x] `Escape` 인계 3건. 뮤테이션 — 인계 분기 제거 → red · `MentionList` 0건 가드 제거 → red ·
      Tab 확정 제거 → red
- [x] **우회 못 했다.** 좌표 API(`getClientRects`·`getBoundingClientRect`)를 Text/Element/Range 에
      채워 넣고도 팝업이 뜨지 않는 것을 재확인했다(2회 시도). 그래서 팝업 렌더가 아니라
      **결함이 났던 분기**를 겨눈다 — 신호원(`isMentionSuggestionActive`)만 모킹하고
      `handleKeyDown` 의 Escape 분기를 잰다. 팝업 층 증인은 e2e 4건이 계속 맡는다

## 2. C — 붙여넣기 경로 판별식 2건 (`test:`)

### C1. 업로드 → `attachment:` 삽입 → 첨부 목록 갱신 순서

- [x] 업로드를 promise 로 붙잡아 두고 그 사이 `setImage` 미호출을 잰다. 뮤테이션 `await` 제거 → 4건 red
- [x] 실패 시 무삽입 + 토스트. 한 건 실패해도 나머지 진행
- [x] `invalidateQueries` 호출. 뮤테이션 제거 → red

### C2. `attachment:uuid` → blob 치환 렌더

- [x] `AttachmentHtml.test.tsx` 9건
- [x] `a[href]` 미치환을 함께 잰다. 뮤테이션 M9(셀렉터를 a 까지 넓힘) → red.
      ★M9 는 1차 시도에서 sed 가 미매치해 **뮤테이션이 안 걸린 채 초록**이 났다.
      grep 으로 적용 여부를 되재 잡았다
- [x] 비-UUID 미치환 + 평문 `attachment:uuid` 미치환(문자열 치환이 아니라 DOM 순회임을 증명)

## 3. B — 본문·댓글 길이 카운터 (`feat:`)

- [x] **Jira 문서에 표시 규칙 서술이 없다**(조회 2026-09-04). 편차 X6 으로 적고 임계(90%)
      노출을 택했다 — 32,767 상한에 늘 숫자를 붙이면 신호가 소음이 된다
- [x] `lib/issue-text-constraints.ts` 신설(세 값 함께). `issue-create-schema.ts` 는 안 건드렸다 —
      기존 임포트 2곳을 흔들지 않는 쪽이 surgical 하다
- [x] `scripts/workflow/issue-text-constraints-alignment.test.ts` 6건. Kotlin 을 텍스트로 읽는다 —
      `scripts/**` 는 조건 없이 전량 실행되므로 **백엔드만 바뀐 커밋**에서도 잡힌다.
      KDoc 의 옛 값(200)이 파서를 오염시키지 않도록 주석 제거 + 그 자체의 회귀 픽스처
- [x] **도입 안 했다.** 서버가 재는 것은 HTML/평문 문자열 길이지 TipTap 의 문자 수가 아니다.
      의존성을 늘리고도 틀린 값을 세게 된다
- [x] 본문 카운터 배선 (HTML 길이)
- [x] 댓글 카운터 배선 — 작성·수정 2곳 (평문 길이)
- [x] **막는다**(편차 X7). 버튼과 ⌘Enter 양쪽. 안 막으면 32,767자를 다 쓴 뒤 400 으로 처음
      알게 되고 그 시점엔 손실을 인지할 방법이 없다
- [ ] 눈확인 — 라이트/다크 양쪽

## 4. D — 첨부 갤러리 좌우 이동 (`feat:`)

- [x] `attachments` + `startIndex`
- [x] 이전/다음 버튼 + 위치 표시 「2 / 3」
- [x] `←`/`→` — `DialogContent` 에 건다(Radix 가 포커스를 모달로 가져오므로 전역 리스너 불필요)
- [x] `DialogTitle` 이 현재 첨부 파일명을 싣는다 — 이동하면 접근성 이름도 따라 바뀐다
- [x] 경계를 감싸지 않는다. 첫 장 ← · 마지막 장 → 는 무동작 + 버튼 비활성
- [x] **분기를 없앴다.** 호출부가 미리보기 가능한 것만 걸러 넘긴다 — 건너뛸 것이 목록에 없다
- [x] 21건 (기존 9 + 갤러리 12)
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
