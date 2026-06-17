# FR-MN-02 — 멘션 자동완성

> slug: fr-mn-02-mentions-autocomplete
> type: api
> agent: backend-engineer
> primary_bc: identity-access
> 생성: 2026-06-17

## Brief

FR-MN-02 멘션 자동완성 (issue-tracking §4.1.2, 선행 FR-MN-01 완료).
- D4 백엔드. `GET /api/v1/users/autocomplete?q=` — prefix 매칭 사용자 목록 반환
- D6 프론트. `@` 트리거 popover (명세 "TipTap 확장" — 실제 에디터 확인 필요)
- D7 E2E

classify: type=api, agent=backend-engineer, primary_bc=identity-access.

선행 쟁점.
1. cross-BC 경계 — `users` 자동완성은 identity-access 소관(데이터 소유), FR은 issue-tracking. 엔드포인트 위치 결정 필요.
2. 에디터 실체 — "TipTap 확장"이 현재 프론트(plain textarea + MentionParser)와 정합한지 확인 필요.

## 도메인 정리

- **BC**: issue-tracking (기능 소속). 단 자동완성 데이터 경로는 **순수 identity-access** — 프론트가 `GET /api/v1/users`를 직접 호출. **cross-BC 포트 불필요, BC 위반 없음**.
- **영향 엔티티**: 신규 0. 기존 `User`(identity-access) 읽기만.
- **새 용어**: 0 (멘션·typeahead 기존 컨텍스트).
- **기존 결정/코드 (재사용 진실 출처)**:
  - `UsersController.GET /api/v1/users?query=` (FR-IS-03 Task 4, ADR `2026-06-01-issue-assignee-user-lookup-port` §2) — PII-게이트 typeahead 사용자 검색. `UserSummaryResponse(id, username, displayName, email)`, MAX_RESULTS=50, `query`는 username/display_name **substring(ILIKE `%q%`)** 매칭.
  - `apps/web/src/api/users.ts` `fetchUsers(query?)` + `userSummarySchema` Zod — 그대로 재사용.
  - `apps/web/src/components/issue/IssueDescription.tsx` — 마크다운 **textarea**(Write/Preview 탭). `@` popover가 붙는 호스트. (FR-MN-01 백엔드 `MentionParser`가 마크다운 `@username` 파싱하는 것과 정합.)
- **관련 ADR**: `docs/adr/2026-06-01-issue-assignee-user-lookup-port.md` (엔드포인트 결정 기 수립). 신규 ADR 불요 — FR-MN-02는 inline deviation 기록.

### 명세 deviation (Maxi 확정 2026-06-17)

기존 재사용 + substring 유지 선택. **백엔드 신규 0, 프론트 전용 기능**(+ E2E).

1. **D4 엔드포인트**: 명세 `GET /api/v1/users/autocomplete?q=` → **기존 `GET /api/v1/users?query=` 재사용**(신규 0).
2. **매칭**: 명세 "prefix" → **substring(`%q%`) 유지**(기존 엔드포인트, Slack/GitHub식 관대 매칭).
3. **D6 에디터**: 명세 "TipTap 확장" → **마크다운 textarea 기반 `@` typeahead**(TipTap 미도입, 의존성 0).
4. **classify 조정**: type=ui, agent=frontend-engineer (백엔드 0이므로 api→ui).

> 머지 시 §명세/범위 변경 전수 동기화 대상 — `docs/plan/product/issue-tracking.md §4.1.2`에 deviation 블록 추가 + D4/D5 "신규 0" 표기 + D6 "textarea @ typeahead".

## 스펙

전체 스펙. [docs/specs/2026-06-17-fr-mn-02-mentions-autocomplete.md](../specs/2026-06-17-fr-mn-02-mentions-autocomplete.md)

핵심 시나리오 3줄.
- 본문 편집 textarea에서 `@jo` 입력 → 250ms debounce 후 `fetchUsers("jo")`(기존 substring) → 일치 사용자 popover(textarea 아래 docked).
- 클릭/키보드(Enter·Tab) 선택 → 활성 멘션 구간(`@`~caret)을 `@username `로 splice, caret 복원(rAF+isConnected, IME 보류).
- Escape/공백/blur/0건으로 닫힘. 저장은 기존 onSave(markdown) 불변 → 백엔드 FR-MN-01이 멘션 추출.

재사용 진실출처. `fetchUsers`/`userSummarySchema`(api/users.ts) · `use-debounce` · LabelAutocompleteInput(listbox/blur/onMouseDown) · TemplateContentField(spliceToken/restoreCaretAfterFrame/IME).

## Brainstorming Check

✅ 통과 (1회). gap 5건 발견·해소(popover 위치 FR11·활성멘션 FR9·쿼리 경계 FR10·코드블록 비억제 E9·MSW E10). 의도적 후속: 생성폼/댓글 멘션, caret 픽셀 위치.

## Plan

> 프론트 전용·단일 BC(issue-tracking). 데이터 레이어 100% 재사용(`useUsers`/`fetchUsers`/`user-handlers` MSW 기존). 의존 체인 T1→T2→T3→T4 직렬(파일 의존).

### Task 1. 멘션 트리거 감지 + 토큰 splice 순수 함수

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/issue/mention/mention-detect.ts`, `apps/web/src/components/issue/mention/__tests__/mention-detect.test.ts`]
- depends-on: []

**RED**: `mention-detect.test.ts`
- `detectActiveMention(text, caret)` — caret 직전 역방향 첫 `@` 탐색.
  - `'@jo'`, caret=3 → `{ active: true, query: 'jo', start: 0, end: 3 }` (FR1/FR9)
  - `'hi @jo'`, caret=6 → `{ active: true, query: 'jo', start: 3 }` (앞 공백 경계)
  - `'mail a@b'`, caret=8 → `{ active: false }` (앞 문자가 비공백 → 비트리거, FR1/S5)
  - `'@jo bar'`, caret=7 → `{ active: false }` (쿼리 뒤 공백, FR2/FR7d)
  - `'@@'`, caret=2 → `{ active: false }` (앞 문자 `@`, E2)
  - `'@'`, caret=1 → `{ active: true, query: '' }` (쿼리 0 — 표시 게이팅은 호출측, FR3)
  - 멀티라인 `'a\n@jo'`, caret=4 → active(개행 경계, E3)
- `spliceMention(text, start, end, username)` — `start..end`를 `@username ` 로 치환, caret 위치 반환.
  - `spliceMention('hi @jo', 3, 6, 'jdoe')` → `{ next: 'hi @jdoe ', caret: 9 }` (FR5)
- 실패(예상): `mention-detect` 모듈 없음.

**GREEN**: `mention-detect.ts`
- `detectActiveMention`: caret에서 역방향 스캔, 공백/개행 만나면 중단, `@` 발견 시 앞 문자(시작/공백/개행) 경계 검사. 순수 함수.
- `spliceMention`: `text.slice(0,start) + '@'+username+' ' + text.slice(end)`, caret=start+username.length+2.

**REFACTOR**: 경계 판정 헬퍼(`isBoundaryChar`) 추출 + L1 한글 주석 + 반환 타입 인터페이스 export.

**검증**: `pnpm --filter web test mention-detect`

### Task 2. useMentionAutocomplete 훅 + MentionDropdown 표시 컴포넌트

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/issue/mention/use-mention-autocomplete.ts`, `apps/web/src/components/issue/mention/MentionDropdown.tsx`, `apps/web/src/components/issue/mention/__tests__/use-mention-autocomplete.test.tsx`]
- depends-on: [1]

**RED**: `use-mention-autocomplete.test.tsx` (jsdom, MSW user-handlers + QueryClient)
- 훅 시그니처 `useMentionAutocomplete({ value, onChange, textareaRef })` → `{ open, candidates, activeIndex, onKeyDown, onCompositionStart, onCompositionEnd, getDropdownProps, getOptionProps, onSelect }`.
- value=`'@jo'` + caret(textareaRef.selectionStart `?? 0`, E1) → debounce(250) 후 `useUsers('jo')` 후보 노출(open=true). (S1/FR3)
- value=`'@'`(쿼리 0) → fetch/open 안 함(FR3).
- `onKeyDown` ArrowDown/Up activeIndex 순환, Enter/Tab → `onSelect(active)` 호출 + `onChange(spliceMention(...))` 적용 + preventDefault(FR6/S3).
- Escape → open=false, preventDefault, value 불변(S4).
- IME: compositionStart 후 트리거 보류, compositionEnd 후 재개(FR8).
- 응답 경합: 닫힌 뒤 도착한 응답 미반영(E4) — open/query 가드.
- `MentionDropdown` 렌더: `role=listbox`/`role=option`, `aria-activedescendant`, onMouseDown preventDefault→onSelect(LabelAutocompleteInput 선례), 후보 `displayName (@username)`.
- 실패(예상): 훅/컴포넌트 없음.

**GREEN**:
- `use-mention-autocomplete.ts`: detectActiveMention로 활성쿼리 도출 → `useDebounce` → `useUsers(debounced)`(쿼리≥1 enabled) → open/activeIndex state, 키보드 핸들러, onSelect=spliceMention+onChange+rAF caret 복원(`restoreCaretAfterFrame` 패턴, isConnected 가드).
- `MentionDropdown.tsx`: docked `absolute mt-1 w-full` listbox(FR11), 직접 DOM ul/li(jsdom 호환, LabelAutocompleteInput 선례).

**REFACTOR**: 상수(DEBOUNCE 250·LISTBOX_ID) 추출, caret 복원 헬퍼 공유 검토(TemplateContentField와 중복 시 lib로 추출은 보류—단순성), L1 한글 주석.

**검증**: `pnpm --filter web test use-mention-autocomplete`

### Task 3. IssueDescription EditMode 배선

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/issue/IssueDescription.tsx`, `apps/web/src/components/issue/__tests__/IssueDescription.test.tsx`]
- depends-on: [2]

**RED**: `IssueDescription.test.tsx` (기존 파일 확장)
- 편집모드 진입 → Write 탭 textarea에 `@jo` 입력 → MentionDropdown 후보 노출(MSW).
- 후보 클릭 → draftMarkdown이 `@jdoe `로 갱신(FR5/S2).
- Escape → dropdown 닫힘, textarea 값/포커스 유지(S4).
- 회귀: 기존 Write/Preview 탭·저장/취소·권한 게이팅(restricted/noneditable) 테스트 그대로 통과(NFR/E7).
- 실패(예상): textarea에 멘션 배선 없음.

**GREEN**: `IssueDescription.tsx`
- `EditMode`에 `textareaRef`(mergedRef 불필요—단일 ref) 추가, `useMentionAutocomplete({ value: draftMarkdown, onChange: onDraftChange, textareaRef })` 연결.
- Write 탭 `<textarea>`에 `ref`, `onKeyDown`, `onCompositionStart/End` 부착 + textarea 아래 `<MentionDropdown>` 렌더(FR11). 기존 value/onChange/disabled/aria 불변.

**REFACTOR**: EditMode 내 멘션 배선을 가독성 유지하게 정리, L1 주석 보강.

**검증**: `pnpm --filter web test IssueDescription`

### Task 4. E2E — 멘션 자동완성 → 저장

**메타**.
- agent: `qa-engineer`
- files: [`apps/web/e2e/issue-mention-autocomplete.spec.ts`, `apps/web/src/mocks/user-fixtures.ts`]
- depends-on: [3]

**RED**: `issue-mention-autocomplete.spec.ts` (Playwright)
- 시나리오: 이슈 상세 진입 → 본문 편집 → Write 탭 textarea에 `@al` 타이핑 → 후보 popover에서 "Alice" 선택 → textarea에 `@alice ` 삽입 확인 → 저장 → 본문 반영(또는 onSave 호출) 확인. (S2/S8)
- 키보드 경로 1건(↓+Enter), Escape 닫힘 1건.
- 셀렉터 컨테이너 한정(strict-mode, memory `playwright-getbyrole-exact-strict-mode`).
- user-fixtures에 멘션용 사용자(alice 등) 시드 보장(없으면 추가, E10). substring 필터는 기존 user-handlers 재사용.

**GREEN**: 필요한 fixture만 보강(`user-fixtures.ts`). 구현 코드(src) 수정 금지(qa-engineer 규칙).

**REFACTOR**: 시나리오 헬퍼 정리.

**검증**: `pnpm --filter web test:e2e issue-mention-autocomplete` + 기존 issue E2E 회귀 0.

## Plan 메타

- task 수: 4 (각 TDD 사이클)
- 의존: T1→T2→T3→T4 (파일/API 의존 직렬). 예상 wave 4 (병렬 여지 적음 — 단일 기능 직선).
- 예상 시간: 약 16분(직렬 기준).
- TDD 강제: yes (test 커밋이 feat보다 선행).
- 백엔드/마이그레이션: 0. 신규 npm 의존: 0(NFR1).
- 추가 검증: typecheck, lint(biome), vitest, playwright(qa-engineer), `pnpm verify`.

## 리뷰 결과

### eng + design 인라인 리뷰 (2026-06-17)

> type=ui이나 새 화면이 아닌 동작 중심(기존 LabelAutocompleteInput 패턴 재사용). 인터랙티브 plan-design-review 대신 eng+design 집중 인라인 리뷰로 대체(메모리 `bts-review-plan-autoplan-overkill` 리뷰어 미스매치 취지).

**eng 관점**
- ✅ TDD RED→GREEN→REFACTOR + 메타블록(agent/files/depends-on) 4 task 모두 충족. 의존 직렬 무순환.
- ✅ jsdom selectionStart 함정(E1) 명시 + 실 caret은 E2E 위임(memory `jsdom-browser-textarea-selectionstart`).
- ✅ caret 복원 rAF+isConnected·IME 보류(TemplateContentField 선례) 반영.
- ✅ 회귀 가드: T3가 기존 IssueDescription(Write/Preview·저장·권한 게이팅) 테스트 보존.
- ⚠️ **주의1 (T2 구현)**. 활성 멘션 감지는 **render 중 textareaRef.selectionStart 읽기보다 textarea onChange/onSelect 이벤트의 `event.target.selectionStart`에서 계산**할 것 — value 갱신과 caret 읽기의 1-tick lag로 인한 오탐 방지. 감지 결과는 state로 보관.
- ⚠️ **주의2 (T2 구현)**. 키보드 `onKeyDown`의 Enter/Tab preventDefault는 **popover open일 때만** 적용. 닫힘 상태에선 textarea 기본(줄바꿈) 동작 보존(FR6).
- ⚠️ **주의3 (T2/T3 구현)**. `useUsers`는 쿼리 길이 ≥1에서만 `enabled`(50명 덤프 방지, FR3). debounce 후 닫힌 popover 미반영 가드(E4).

**design 관점**
- ✅ DESIGN.md 토큰 준수(LabelAutocompleteInput의 `bg-popover/border/shadow-md` 재사용). WCAG AA ARIA listbox/option·키보드 단독·활성 강조(NFR4).
- ✅ docked 위치(FR11) 일관 — 별도 시안 불요(기존 패턴).
- ⚠️ **주의4 (T2/T3 구현)**. docked dropdown은 `z-50` + textarea 바로 아래. 저장/취소 버튼·Preview 영역과 겹침 시 z-index/위치 확인(시각 회귀는 E2E 스냅 불요, 수동 확인).

**BLOCKER: 없음.**

### PR 단위 코드 리뷰 (2026-06-17, 게이트 2)

**superpowers:code-reviewer**: ✅ PASS (CONCERNS 2, BLOCKER 0). 절대규칙 클린(any/!!/console/localStorage/빈catch 0), PII 안전(email 미참조), noUncheckedIndexedAccess 가드, 주의1·2 반영, race/blur cleanup 정상, 테스트 비-vacuous, IssueDescription 회귀 0.
- CONCERN1 (빈쿼리 fetch): 기존 assignee 셀렉터(issues.$key.tsx:148)와 동일 production 패턴이라 수용. **거짓 주석 1줄 정정 완료**(커밋 38b955c1).
- CONCERN2 (훅이 ReactNode 반환): 의도적 단순화, 수용.

**독립 adversarial 리뷰**: BLOCKER 0. 발견 triage(controller 검증).
- **P1 stale value splice — 기각**. controlled textarea라 DOM===value, 클릭은 키입력 커밋 후 실행, useCallback 동일-render 클로저. 버그 아님(리뷰어 자체 메모도 "consistent per-render").
- **수용된 저심각 엣지(후속 후보)**: (a) caret를 기존 `@user` 중간에 두고 선택 시 `@user ` + 잔여(`ice`) 오염 — spec FR9(end=caret) 일치, 저빈도. (b) activeIndex 범위초과 dead-key — keystroke 없는 candidates 축소(백그라운드 refetch)만 트리거, 극희소. (c) Preview 탭 전환 후 드롭다운 잔존 — 경미 UX. (d) E2E `/@alice\s/` 느슨(기존 description 내용으로 부분일치 일부 정당).
- **기존 앱 전역 패턴(신규 아님)**: blur 타이머 vs 터치(LabelAutocompleteInput), email 네트워크 노출(assignee 셀렉터 동일 DTO).

**verify-master-plan**: ✅ exit 0 (FR 123/123 정합, drift 0).
**검증**: typecheck ✅ · lint ✅ · 단위 3051/3051 ✅ · E2E 4 시나리오 + issue-body-meta 회귀 0 ✅.
