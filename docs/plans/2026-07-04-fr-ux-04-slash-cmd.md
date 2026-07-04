# FR-UX-04 Slash 명령어 (Cmd+K 명령 팔레트)

> slug: fr-ux-04-slash-cmd
> type: feature
> agent: backend-engineer (UI task는 frontend-engineer, E2E는 qa-engineer 지정)
> primary_bc: personalization
> 생성: 2026-07-04

## Brief

FR-UX-04 — Slash 명령어. `Cmd+K`로 여는 명령 팔레트(command palette).
`/issue`, `/search`, `/goto` 등 슬래시 명령을 한 곳에서 실행하는 UX 편의 기능.

product 문서(docs/plan/product/personalization.md §4.2) D 단계.
- D1. 도메인 — Command (backend-engineer)
- D2. 명세 — `/issue`, `/search`, `/goto` 등 (backend-engineer)
- D3. 데이터 모델 — 활용(명령어 정의는 코드 상수), 신규 테이블 없음
- D4. 백엔드 — `POST /api/v1/commands/execute` (backend-engineer)
- D5. 백엔드 테스트 (backend-engineer)
- D6. 프론트 UI — cmdk 명령 팔레트 (`Cmd+K`) (designer → frontend-engineer)
- D7. E2E (qa-engineer)

**분류 메모**. classify 자동판정 qa(입력 "E2E" 키워드 오판) → feature 수동정정.
learnings.md 반복 함정(FR-SR-02·FR-RP-01 동일). agent는 plan task별 지정.

**선결 이슈** (domain/spec에서 확정).
- personalization BC의 첫 backend 부트스트랩 여부 (기존 모듈 없음)
- cmdk 신규 외부 의존성 도입 여부 (DEVELOPMENT.md §외부 의존성 → Maxi 확인)
- `/issue`, `/search`, `/goto` 명령 범위 확정

## 도메인 정리

- **BC**: personalization (논리) — 물리 구현은 `apps/web` 프론트 전용. 백엔드 모듈 없음.
- **아키텍처 결정** (Maxi 확정, AskUserQuestion): **프론트 전용**. product D4 `POST /api/v1/commands/execute` 미도입.
  명령 레지스트리=프론트 코드 상수, cmdk 팔레트가 기존 라우터/검색·이슈 라우트로 직접 dispatch.
- **명령 범위** (Maxi 확정): `/goto` · `/search` · `/issue` 3종, 전부 네비게이션(즉석 mutation 없음).
  `/issue`는 새 이슈 폼으로 이동(제목 프리필) — "빠른 이동만(액션 명령 제외)"과 양립.
- **영향 파일(신규, 프론트)**: `CommandPalette.tsx`(cmdk 팔레트) + `commands.ts`(명령 레지스트리/파서) + 전역 `Cmd+K` 훅.
- **cmdk**: 이미 설치·실사용 중(`cmdk ^1.1.1`, FR-IS-09 `LabelAutocompleteInput.tsx`). 신규 의존성 0.
- **새 용어**(glossary 추가 대기 — Maxi 승인 필요):
  - 명령 팔레트 (Command Palette) — `Cmd+K`로 여는 슬래시 명령 실행 UI.
  - 슬래시 명령 (Slash Command) — `/goto` `/search` `/issue` 형식의 네비게이션 명령. Slack의 서버측 slash 명령과 다름(클라이언트 전용).
- **기존 결정 충돌**: 없음. 단 **product 문서 deviation 발생** — `personalization.md §4.2` D3~D5(데이터모델/백엔드/백엔드테스트) → "프론트 전용" 조정. 구현 PR 내 전수 동기화(fr-index 카운트/BC 불변).
- **관련 ADR**: [docs/decisions/2026-07-04-fr-ux-04-slash-cmd.md](../decisions/2026-07-04-fr-ux-04-slash-cmd.md) (생성됨)
- **agent 배정**: frontend-engineer(UI) + qa-engineer(E2E). backend-engineer 태스크 0.

## 스펙 (← /bts-spec Phase A 채움)

## 스펙

전체 스펙. [docs/specs/2026-07-04-fr-ux-04-slash-cmd.md](../specs/2026-07-04-fr-ux-04-slash-cmd.md)

핵심 시나리오 요약.
- `Cmd+K`/`Ctrl+K`로 전역 명령 팔레트 토글(인증 시만, `__root` RootLayout에 마운트).
- 빈 입력 시 빠른 이동 목록(내 이슈/검색/대시보드/받은편지함) + 명령 힌트.
- `/goto <이슈키>` → `/issues/$key`, `/search <질의>` → `/search?q=`, `/issue <제목>` → `/issues/new?summary=`(프리필).
- 전부 네비게이션(mutation 없음). 백엔드 0(product D4 deviation).

핵심 뷰레이어 변경.
- `issues.new` 라우트에 `summary`(선택) search param 추가 → 폼 기본값 프리필(FR7).

## Brainstorming Check

✅ 통과 (sanity-check 1회 보강, gap 4건 수정 반영).
- SearchRouteAdapter projectKey optional 검증 → `/search?q=` 라우팅 성립 확인.
- 명령 힌트 prefill / projectKey 위임 / 포커스 복원 / 모달 중첩 정책 명시.

## Plan

### Task 1. 명령 파서/레지스트리 (순수 함수)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/command-palette/commands.ts`, `apps/web/src/components/command-palette/commands.test.ts`]
- depends-on: []

**RED**: `commands.test.ts`
- `parseCommand('/goto PROJ-12')` → `{ kind: 'goto', issueKey: 'PROJ-12' }`
- `parseCommand('/goto proj-12')` → 대문자 정규화 `PROJ-12` (E4)
- `parseCommand('/search 로그인 버그')` → `{ kind: 'search', query: '로그인 버그' }`
- `parseCommand('/issue 결제 실패')` → `{ kind: 'issue', summary: '결제 실패' }`
- `parseCommand('/foo x')` → `{ kind: 'unknown', name: 'foo' }` (E1)
- `parseCommand('/goto')` → `{ kind: 'incomplete', name: 'goto' }` (E2)
- `parseCommand('/goto 안녕')` → `{ kind: 'incomplete', name: 'goto', reason: 'invalid-key' }` (E3)
- `parseCommand('hello')` → `{ kind: 'not-command' }`
- 실패 메시지(예상): `parseCommand`/`commands.ts` 없음
- + 정적 목적지 레지스트리 `QUICK_LINKS`(내 이슈/검색/대시보드/받은편지함) export 테스트

**GREEN**: `commands.ts`
- `ParsedCommand` 판별 유니온 + `parseCommand(input)` 순수 함수(정규식 이슈키 `^[A-Z][A-Z0-9]*-\d+$`, 대문자 정규화).
- `COMMANDS`(goto/search/issue 힌트 메타) + `QUICK_LINKS` 상수.

**REFACTOR**: 정규식/명령명 상수 추출 + JSDoc. 파일 L1 한국어 헤더 주석.

**검증**: `pnpm --filter web test -- commands.test.ts` + `pnpm --filter web typecheck`

### Task 2. CommandPalette 컴포넌트 (cmdk UI)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/command-palette/CommandPalette.tsx`, `apps/web/src/components/command-palette/CommandPalette.test.tsx`]
- depends-on: [1]

**RED**: `CommandPalette.test.tsx` (navigate mock 주입)
- 빈 입력 → QUICK_LINKS 4개 + 명령 힌트 3개 렌더 (S2).
- 명령 힌트 `/goto` 선택 → 입력창에 `/goto ` prefill, 라우팅 안 함 (S2 보강).
- `/goto PROJ-12` + Enter → `navigate({ to:'/issues/$key', params:{key:'PROJ-12'} })` 호출 (S3).
- `/search 버그` + Enter → `navigate({ to:'/search', search:{ q:'버그' } })` (S4).
- `/issue 제목` + Enter → `navigate({ to:'/issues/new', search:{ summary:'제목' } })` (S5).
- 알 수 없는/불완전 명령 → 안내 문구, navigate 미호출 (E1~E3, FR5).
- IME 조합 중 Enter(isComposing) → 실행 안 함 (NFR4).
- 실패 메시지(예상): `CommandPalette` 없음

**GREEN**: `CommandPalette.tsx`
- cmdk `Command`/`Command.Input`/`Command.List`/`Command.Item` 기반. props: `open`, `onOpenChange`.
- `useNavigate()`로 dispatch. `parseCommand` 결과 kind별 라우팅. 선택/실행 후 `onOpenChange(false)` + 입력 초기화(FR6).
- DESIGN.md 토큰(overlay/card/muted/border/ring). `*Strings` 객체로 문구.

**REFACTOR**: dispatch 로직을 `runCommand(parsed, navigate)` 헬퍼로 분리(부수효과 격리, C3).

**검증**: `pnpm --filter web test -- CommandPalette.test.tsx` + typecheck

### Task 3. 전역 Cmd+K 훅 + RootLayout 마운트

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/command-palette/useCommandPalette.ts`, `apps/web/src/components/command-palette/useCommandPalette.test.tsx`, `apps/web/src/routes/__root.tsx`]
- depends-on: [2]

**RED**: `useCommandPalette.test.tsx`
- `Cmd+K`(metaKey) keydown → open=true, `preventDefault` 호출 (FR1, E6).
- `Ctrl+K`(ctrlKey) keydown → open=true (win/linux).
- 열린 상태에서 다시 `Cmd+K` → 토글 close (E7).
- 실패 메시지(예상): `useCommandPalette` 없음

**GREEN**:
- `useCommandPalette()`: open state + document keydown 리스너(metaKey||ctrlKey && key==='k' → preventDefault + toggle). cleanup 등록.
- `__root.tsx` RootLayout: `isAuthenticated`일 때만 `useCommandPalette` + `<CommandPalette open onOpenChange />` 마운트(FR2, C1).

**REFACTOR**: 키 매칭 상수화. `__root.tsx` L1 주석 보존.

**검증**: `pnpm --filter web test -- useCommandPalette.test.tsx __root` + typecheck

### Task 4. issues.new summary URL 프리필 (뷰레이어)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/routes/issues.new.tsx`, `apps/web/src/routes/issues.new.test.tsx`]
- depends-on: []

**RED**: `issues.new.test.tsx`
- URL `?summary=결제 실패`로 마운트 → 제목 input 기본값이 `결제 실패` (FR7, S5).
- summary 없을 때 → 기존 동작(빈 제목) 유지(회귀 방지).

**GREEN**:
- 라우트 `validateSearch`에 `summary?: string` 추가(router.ts의 issues.new 등록부 또는 route 파일 스키마).
- `useSearch`로 summary 추출 → `defaultValues.summary`에 반영.

**REFACTOR**: summary trim/최대길이(500, 기존 zod max) 방어.

**검증**: `pnpm --filter web test -- issues.new.test.tsx` + typecheck

### Task 5. E2E — 명령 팔레트 시나리오

**메타**.
- agent: `qa-engineer`
- files: [`apps/web/e2e/command-palette.spec.ts`]
- depends-on: [2, 3, 4]

**시나리오** (기존 E2E fixture/로그인 헬퍼 재사용):
- `Cmd+K` 토글 → 팔레트 열림/`Esc` 닫힘 (S1).
- `/goto PROJ-…` → 이슈 상세 URL 이동 (S3).
- `/search <질의>` → `/search?q=` 이동 + 검색 실행 (S4).
- `/issue <제목>` → `/issues/new` 제목 프리필 (S5).
- 비로그인 시 `Cmd+K` 무반응 (E5) — 로그인 전 페이지.
- axe-core 0 violations (NFR2).

**검증**: `pnpm --filter web test:e2e -- command-palette.spec.ts`

### Task 6. product 문서 deviation 동기화 (D3~D5 조정)

**메타**.
- agent: `frontend-engineer`
- files: [`docs/plan/product/personalization.md`]
- depends-on: []

**내용**: §4.2 FR-UX-04의 D3~D5(데이터모델/백엔드/백엔드테스트)를 "프론트 전용, 백엔드 없음(ADR 2026-07-04)"로 조정. D6/D7 유지. ADR 링크 추가. fr-index BC 매핑·카운트 불변(변경 없음).
- **주의**: FR 완료 마킹(`[x]`)은 bts-merge 게이트에서 일괄. 이 task는 D단계 텍스트 조정만.

**검증**: `bash scripts/verify-master-plan.sh` (카운트 drift 0 확인)

## Plan 메타

- task 수: 6
- 예상 wave: 4 (W1: T1·T4·T6 / W2: T2 / W3: T3 / W4: T5)
- TDD 강제: yes (T1~T4 프론트 vitest red→green, T5 E2E)
- 병렬 dispatch: files 교집합 0 → 독립 task 동시 실행
- 추가 검증: typecheck, lint(eslint), vitest, playwright(qa), verify-master-plan(T6)
- backend/security/db agent: 미사용 (프론트 전용)

## 리뷰 결과

### 집중 리뷰 (eng + design, 2026-07-04)

autoplan(codex 4-phase 듀얼보이스) 대신 규모 맞춤 집중 리뷰 — 프론트 전용·저위험(mutation 0·보안표면 0·신규의존성 0·관용 UI 패턴)이라 [[bts-review-plan-autoplan-overkill]] 정신 적용.

**Eng**.
- ✅ T3 전역 keydown: `__root` 단일 마운트 + 인증 가드 + cleanup 명시. Cmd+K modifier라 타이핑 충돌 없음.
- ✅ 파서 정규식 `^[A-Z][A-Z0-9]*-\d+$` 프로젝트 키 규칙(대문자 시작) 부합.
- ✅ T2 dispatch `runCommand(parsed, navigate)` 순수 분리 → 테스트 용이.
- ⚠️ **CONCERN-1 (T4)**: react-hook-form `defaultValues`는 mount 1회 적용. 이미 `/issues/new`에 있는 상태에서 `/issue 다른제목` 재실행 시 같은 라우트라 프리필 미갱신 가능. → T4 GREEN에서 `useSearch(summary)`를 `defaultValues` 뿐 아니라 마운트 후 반영 확인, E2E는 "다른 페이지→/issue" 경로로 검증(같은 페이지 재실행은 엣지, 필요 시 key 재마운트).

**Design**.
- ✅ cmdk unstyled → 기존 모달/오버레이 DESIGN.md 토큰 재사용(C4). 새 토큰 신설 없음.
- ✅ 빈/알수없음 상태(E1~E3), 키보드 조작·focus trap·포커스 복원(NFR2) 명세됨.

**BLOCKER: 없음.** CONCERN 1건은 T4 구현 시 해소(implementer 인계).
