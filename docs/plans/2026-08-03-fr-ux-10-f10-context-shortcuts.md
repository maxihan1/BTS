# FR-UX-10 F10 — 컨텍스트 단축키 아키텍처 + 목록 항법

> slug: fr-ux-10-f10-context-shortcuts
> type: ui
> agent: frontend-engineer
> 생성: 2026-08-03
> FR: FR-UX-10 (`docs/plan/product/personalization.md §4.8`)

## Brief

**사용자 원문**. "fr-ux-10 진행해줘"

### 범위 확정 (Maxi 확정 2026-08-03)

FR-UX-10 은 승계 PR 2건(F10 · F11)인데 로드맵 정본
[`docs/design/jira-parity-roadmap.md:63`](../design/jira-parity-roadmap.md) 이
**F11 의존 = F10 + F8** 로 명시한다. F8(이슈 상세 인라인 편집)은 §4.9 FR-UX-11
소속이고 **미착수**라 F11 의 `e`(편집) 키가 갈 곳이 없다.

따라서 **이번 PR 은 F10 만** 담고 FR-UX-10 의 D6/D7 은 `[ ]` 로 유지한다.
완주 순서는 `FR-UX-11(F8 → F9) → FR-UX-10 F11`. FR-UX-09 가 B1 #328 · F2 #331 ·
F3 #333 3 PR 로 완주한 것과 같은 패턴이다.

### F10 산출물

로드맵 정본 `jira-parity-roadmap.md:62` 기준.

- 신규 `context-shortcuts.ts` — `CONTEXT_SHORTCUTS` **별도** 레지스트리
- 신규 `useContextShortcuts.ts` 훅
- 목록 항법 `j` / `k` / `o` / `t` / `[`
- `ShortcutsHelpDialog.tsx` 확장

> 이관 전 사본(`~/.claude/plans/ui-ux-sorted-kay.md:90`)은 대상에 `Sidebar.tsx` 를
> 포함했으나 레포 정본에는 없다. #335 가 이관하며 stale 을 정정했으므로
> **레포 정본을 따른다** — 사이드바 변경 필요는 스펙 단계에서 별도 판정한다.

### 🛑 깨면 즉사하는 계약

기존 `shortcuts.ts` 의 `SHORTCUTS` 를 **건드리지 않는다**. 여기에 키를 추가하면

1. `shortcuts.test.ts:121` `expect(SHORTCUTS).toHaveLength(5)`
2. `shortcuts.test.ts:147` `DEFAULT_KEYMAP` 완전일치
3. 백엔드 `KeymapAction.kt` 5종 화이트리스트
4. `user_keymap.action` CHECK 제약

이 **동시에** 깨진다. 4중 계약의 소유자는 §3.3 FR-PF-03 이다.
**성공 판정식 = `shortcuts.test.ts` 의 `toHaveLength(5)` 가 무수정 green 유지.**

### ★ 착수 중 발견 — 계약 문서의 실측 명령이 고장나 있다

`docs/design/jira-parity-contract.md:39` "단축키 레지스트리 동결" 행의 실측 명령이
**존재하지 않는 경로**를 가리킨다.

```
문서:  grep -n "toHaveLength" apps/web/src/lib/shortcuts.test.ts
실행:  ugrep: warning: ... No such file or directory   ← 종료코드 2
실제:  apps/web/src/components/keyboard-shortcuts/shortcuts.test.ts
```

계약 §2 는 *"개수 리터럴은 stale 해지는 순간 거짓이 된다 — 각 행의 명령으로 착수 시점에
실측하라"* 로 실측을 강제하는데, **그 실측 명령 자체가 실측을 못 한다.** 지시대로
실행한 사람은 0건을 보고 "계약 없음" 으로 오판한다. 이번 PR 이 지켜야 할 바로 그
계약이므로 **같은 PR 에서 경로 1줄을 정정**한다.
(계열 교훈 — `discriminant-erases-its-own-evidence` · `two-lists-never-check-each-other`)

### 범위 밖

사용자 키 재배치(로드맵 B4 — `KeymapAction` enum + `user_keymap` CHECK 신규
마이그레이션 필요)는 v1 제외. **고정 키로 출시**한다.

### 불변량

백엔드 변경 0 · 마이그레이션 0 · FR 수 불변 139 · 신규 API 0 · `SHORTCUTS` 무변경.

**classify 결과**. type=ui · agent=frontend-engineer · primary_bc=null — 논리 소속은
personalization, 물리 구현은 `apps/web` (ADR §D2, FR-UX-05 D4 · FR-UX-06 D5 선례 승계).

## 도메인 정리

- **BC**. 논리 = personalization / 물리 = `apps/web` 프론트 전용. **백엔드 변경 0**이라
  BC 침범 없음. 단 `SHORTCUTS` 는 identity-access 의 `KeymapAction` 과 1:1 계약이 걸려 있어
  **건드리면 BC 격리 위반**이 된다 — 이것이 즉사 계약의 정체다.
- **영향 개념**. `SHORTCUTS`(전역 5종, 무변경) · `CONTEXT_SHORTCUTS`(신규) ·
  `selectedKey`(split view 선택 → **항법 커서 역할 겸함**)
- **새 용어**. 없음 (glossary 미등재 — D-1 참조). "컨텍스트 단축키" 는 도메인 개념이 아니라
  UI 구현 세부로 판정. 다만 **전역 단축키/`KeymapAction` 이 glossary 에 0건**인 것은
  별건 등재 후보로 남긴다.
- **기존 결정 충돌**. 없음. FR-UX-05(전역 5종) · FR-PF-03(키맵 커스터마이즈) ·
  FR-UX-06 PR20(split view) 셋 다 **확장 방향으로 승계**한다.
- **관련 ADR**. [2026-08-03-fr-ux-10-f10-context-shortcuts](../decisions/2026-08-03-fr-ux-10-f10-context-shortcuts.md) (생성됨)

### 확정 3건 (ADR 본문 참조)

| | 결정 | 한 줄 근거 |
|---|---|---|
| **D-1** | `CONTEXT_SHORTCUTS` 별도 레지스트리 — 경계는 **도메인 지위 차이** | 전역=영속(`user_keymap`)·identity-access 소속 / 컨텍스트=영속 0·화면 지역 규약. B4 승격 경로가 열림 |
| **D-2** | **단일 리스너·단일 판별 파이프라인** (전역 우선 → 컨텍스트 폴백) | 리스너 2개면 ① `g`+`j` 가 `reset`(preventDefault 없음)을 타고 새 커서까지 발화 ② `helpOpen` 이 훅 내부 state 라 모달 열림 중 배후 목록이 움직임 |
| **D-3** | **항법 커서 = 기존 `selectedKey` 재사용** (Maxi 확정) | 지라 이슈 네비게이터 동형. 선택 개념 증식 0 · 강조 표기(`aria-current`) 자산 그대로 · 연타는 상태 분리가 아니라 **요청 지연**으로 해소 |

## 스펙

전체 스펙. [docs/specs/2026-08-03-fr-ux-10-f10-context-shortcuts.md](../specs/2026-08-03-fr-ux-10-f10-context-shortcuts.md)

**키 5종 확정** (Jira 공식 문서 대조 완료 — 근거가 레포에 0건이던 것을 이번에 처음 기록).

| 키 | 동작 | 컨텍스트 |
|---|---|---|
| `j` / `k` | 목록 커서 아래 / 위 | `issue-list` |
| `o` | 커서 이슈를 전체화면 상세로 | `issue-list` |
| `t` | 상세 페인(split view) 토글 | `issue-list` |
| `[` | 사이드바 접기/펼치기 | `app-shell` |

**핵심 3줄.**
- 컨텍스트는 **레이어**다 — `app-shell` ⊃ `issue-list`. 판별 순서는 전역 → 좁은 → 넓은
- 커서는 기존 `selectedKey`(URL `?selected`) 재사용. `replace: true` 로 히스토리 보호
- 요청 지연 **v1 미도입** — 지연이 오히려 E2E 를 타이밍 의존으로 만든다(ADR D-3 이 상태
  분리를 기각한 논리와 동일). 후속 트리거만 명시

**착수 중 정본 수정 2건** (같은 PR 동기화).
1. `personalization.md §4.8` — 이연 목록 `s` 누락 복원. `j/k/e/m/s`(Gmail 기준) → F10/F11
   (Jira 기준)의 **기준 교체**였음을 대사표로 명시. `e`·`m` 은 글자만 같고 동작이 다르며
   `s`(별표)는 대응이 없어 빠져 있었다 → **즐겨찾기 토글로 F11 배치**(8종). 로드맵 정본
   `jira-parity-roadmap.md:63` 동기화 완료
2. `jira-parity-contract.md:39` — 실측 명령이 **존재하지 않는 경로**를 가리켜 종료코드 2 로
   죽던 것을 정정 (`apps/web/src/lib/` → `apps/web/src/components/keyboard-shortcuts/`)

## Brainstorming Check

✅ 통과 (ui 경량 경로 — brainstorming 스킵, `## Jira 대조` + 즉사 계약 §2 교차가 대체).
office-hours 스킵 근거는 FR-UX-05 선례와 동형. Maxi 결정 필요 gap 0.

## 사전 grep 결과 (계약 §5 — 착수 전 실측, 2026-08-03)

| 항목 | 실측 | 판정 |
|---|---|---|
| `/issues` 접촉 e2e | **33 파일** | 전량은 과도 — 계약 접촉분만 동반 실행 (아래) |
| **`issue-split-view.spec.ts`** | *"?selected=<KEY> + 우측 상세 페인(h2) + 선택 행 aria-current"* | ★**최우선 회귀 가드** — 이번 커서 계약과 정확히 동일 |
| **`issue-table.spec.ts`** | *"행 클릭은 `/issues?selected=<KEY>`"* | ★커서 진입 경로 계약 |
| **`project-switcher.spec.ts:119`** | *"`?projectKey=&status=&selected=` 전환 시 **다른 검색 파라미터가 살아남는다**"* | ★★**회귀 위험 최고** — `j`/`k` 가 URL 갱신 시 기존 파라미터를 날리면 즉사 |
| `keyboard-shortcuts.spec.ts` · `keymap.spec.ts` | 기존 전역 단축키 e2e | 새 키가 기존 발화를 방해하지 않는지 |
| **즉사 계약** | `shortcuts.test.ts:121` `expect(SHORTCUTS).toHaveLength(5)` | **무수정 green 이 성공 판정식** |

> `project-switcher.spec.ts:119` 는 이번 grep 이 아니었으면 놓쳤을 항목이다 — URL 을
> 통째로 교체하지 말고 **`selected` 키만 갈아끼워야** 한다.

## Plan

### Task 1. `context-shortcuts.ts` — 레지스트리 + 순수 판별·커서 계산

**메타**.
- agent: `frontend-engineer`
- 트랙: **red-first TDD** (순수 로직 — 시각 변화 0)
- files: [`apps/web/src/components/keyboard-shortcuts/context-shortcuts.ts`, `apps/web/src/components/keyboard-shortcuts/context-shortcuts.test.ts`]
- depends-on: []

**RED**. `context-shortcuts.test.ts` 신규.
- `CONTEXT_SHORTCUTS` 가 5종이고 각 항목이 `{ key, description, context, action }` 을 갖는다
- `resolveContextKeydown('j', 'issue-list')` → `{ kind: 'cursor-move', delta: 1 }`
- `resolveContextKeydown('j', 'app-shell')` → `{ kind: 'none' }` (컨텍스트 불일치, E12)
- `resolveContextKeydown('[', 'issue-list')` → `{ kind: 'toggle-sidebar' }` (**넓은 컨텍스트 폴백** — FR2)
- 커서 경계 — `nextCursorKey(keys, current, +1)`. E1(빈 목록 `null`) · E2(current 없음 → 첫 키) ·
  E3(첫에서 -1 → 무동작) · E4(마지막에서 +1 → 무동작, wrap 없음) · E5(current 미포함 → 첫 키)
- 실패 예상. `context-shortcuts` 모듈 없음

**GREEN**. `context-shortcuts.ts` 신규. 상수 + 순수 함수 2개. **`shortcuts.ts` import 0**
(단방향 — 계약 오염 차단).

**REFACTOR**. 판별 유니온 타입에 KDoc. 컨텍스트 레이어 우선순위를 배열 순서로 명문화.

**검증**. `pnpm test context-shortcuts` + **`pnpm test shortcuts.test` 무수정 green (C1)**

---

### Task 2. `useContextShortcuts` — 컨텍스트 등록 훅 + 스토어

**메타**.
- agent: `frontend-engineer`
- 트랙: **red-first TDD**
- files: [`apps/web/src/components/keyboard-shortcuts/useContextShortcuts.ts`, `apps/web/src/components/keyboard-shortcuts/useContextShortcuts.test.tsx`]
- depends-on: [1]

**RED**. 라우트가 자신의 컨텍스트와 핸들러를 등록/해제한다.
- 마운트 시 컨텍스트가 활성, 언마운트 시 해제 (누수 0 — NFR5)
- 두 라우트가 연속 마운트되면 나중 것이 활성 (스택 아님, 최신 1개)
- 핸들러 미제공 액션은 무동작

**GREEN**. zustand 스토어(활성 컨텍스트 + 핸들러 맵) + `useEffect` 등록/해제.
**`document` 리스너 추가 금지 (C2)** — 이 훅은 등록만 하고 발화는 Task 3 이 한다.

**REFACTOR**. `use-sidebar-collapsed.ts` 의 zustand 패턴에 맞춰 정리.

**검증**. `pnpm test useContextShortcuts`

---

### Task 3. `useKeyboardShortcuts` 판별 파이프라인 확장

**메타**.
- agent: `frontend-engineer`
- 트랙: **red-first TDD**
- files: [`apps/web/src/components/keyboard-shortcuts/useKeyboardShortcuts.ts`, `apps/web/src/components/keyboard-shortcuts/useKeyboardShortcuts.test.tsx`]
- depends-on: [2]

**RED**. 기존 테스트 무회귀 + 신규 단언.
- 전역 미매칭 키(`j`)가 활성 컨텍스트로 폴백해 발화한다 (FR2)
- **leader 대기 중(`g` 직후) `j` → 무동작 + 시퀀스 리셋** (E6, ADR D-2)
- **도움말 열림 중 `j`/`[` → 무동작** (E7)
- IME 조합 중 · 입력 포커스 중 무동작 (E9/E10 — 기존 `shouldIgnoreEvent` 선행)
- 비로그인 시 리스너 미등록 (E11)
- **컨텍스트 키 발화 시 `preventDefault` 호출** (ADR D-2 파생 — 후행 bubble 리스너가
  `defaultPrevented` 로 걸러내는 기존 관례가 성립해야 한다)
- **판별식** `keyboard-shortcuts/` 안의 `document.addEventListener('keydown'` 이 **정확히 1회**
  (소스 전수 grep — 리뷰 F2 반영)

**GREEN**. `resolveKeydown` 결과가 `none` 일 때만 컨텍스트 판별로 폴백. `reset`(leader 리셋)은
폴백하지 않는다 — 이것이 E6 을 닫는 지점.

**REFACTOR**. 판별 순서를 주석으로 명문화 (전역 → 좁은 컨텍스트 → 넓은 컨텍스트).

**검증**. `pnpm test useKeyboardShortcuts` + **`shortcuts.test` 무수정 green (C1)** +
`grep -c "document.addEventListener('keydown'" apps/web/src/components/keyboard-shortcuts/*.ts` = 1

---

### Task 4. `ShortcutsHelpDialog` — 컨텍스트 섹션

**메타**.
- agent: `frontend-engineer`
- 트랙: **ui 시각 검증 트랙** (동반 테스트 — red-first 순서 면제)
- files: [`apps/web/src/components/keyboard-shortcuts/ShortcutsHelpDialog.tsx`, `apps/web/src/components/keyboard-shortcuts/ShortcutsHelpDialog.test.tsx`]
- depends-on: [1]

**동반 테스트**.
- 그룹 헤딩 2개(**「어디서나」**·**「이슈 목록에서」**)로 렌더된다 (design 리뷰 D4)
- 컨텍스트 5종이 올바른 그룹에 배치된다 — `[` 는 「어디서나」, `j`/`k`/`o`/`t` 는
  「이슈 목록에서」 (`CONTEXT_SHORTCUTS` 단일 구동 — C4)
- **F11 미구현 키(`a`/`i`/`m`/`e`/`l`/`s`/`w`/`.`)가 렌더되지 않는다** (C5, FR-UX-05 FR8 승계)
- ★**기존 5종 + `Cmd/Ctrl K` 의 설명 문구·키 표기 verbatim 무변경** — e2e 가 텍스트로
  찾는다 (계약 §2 h1 verbatim 과 같은 부류의 위험)

**구현**. 현재는 **그룹 헤딩 없는 평평한 `<dl>`**(`SHORTCUTS.map` + `PALETTE_HELP_ITEM`)이라
**그룹 구조를 도입**한다. 기존 항목도 그룹에 넣되 문구는 건드리지 않는다.
하드코딩 금지 — 상수를 map.

**검증**.
- `pnpm test ShortcutsHelpDialog`
- 기존 e2e — `keyboard-shortcuts.spec.ts` · `keymap.spec.ts`
- **눈확인**. `?` 로 열어 컨텍스트 섹션 렌더 · F11 키 미노출 · **라이트/다크 양쪽** 대비

---

### Task 5. `issues.index.tsx` 배선 — 커서·스크롤·공지

**메타**.
- agent: `frontend-engineer`
- 트랙: **ui 시각 검증 트랙**
- files: [`apps/web/src/routes/issues.index.tsx`, `apps/web/src/routes/issues.index.test.tsx`]
- depends-on: [3]

**동반 테스트**.
- `j` → `selected` 가 다음 이슈 키로. **`replace: true`** (C3)
- ★**다른 검색 파라미터 보존** — `?projectKey=INFRA&status=open` 위에서 `j` 를 눌러도
  두 파라미터가 살아남는다 (`project-switcher.spec.ts:119` 회귀 가드의 유닛 짝)
- `o` → `/issues/<KEY>` 이동 · `t` → `selected` 토글 (E13 좁은폭 포함)
- 커서 이동 시 `scrollIntoView({ block: 'nearest' })` 호출 (FR10) +
  **sticky 헤더에 첫 행이 가리지 않도록 `scroll-margin` 확인** (design 리뷰 D5)
- `aria-live="polite"` 공지 문구가 **`"{총}개 중 {n}번째, {이슈키}, {제목}"`** 형식
  (design 리뷰 D6 확정 — 예 *"25개 중 3번째, ATLAS-13, 로그인 실패 처리"*)

**구현**. `useContextShortcuts('issue-list', handlers)` 호출 + 핸들러가 기존
`selectedKey` 갱신 경로를 재사용 (**새 상태 신설 0** — C3).

**검증**.
- `pnpm test issues.index`
- ★**기존 e2e 동반 실행** — `issue-split-view.spec.ts` · `issue-table.spec.ts` ·
  `project-switcher.spec.ts` (learnings #47 — 미루면 머지 시점까지 잠복)
- **눈확인**. `o` 후 뒤로가기로 커서 보존 · `t` 와이드/좁은폭 ·
  **라이트/다크 커서 강조 대비** · 체크박스 체크 + 커서가 겹친 행의 표기(D2 — 다른
  채널이라 충돌 없어야 정상)
- ★**연타 판정선** (design 리뷰 D3). `j` 10회 연타 후 3항 관측 —
  ① 우측 페인이 중간 상태(스켈레톤/빈 화면)를 거치는가
  ② **커서 이동이 페인 로딩을 기다려 끊기는가**
  ③ 네트워크 요청이 누적되는가
  → **① 또는 ② 관측 시 결함으로 보고 즉시 대응. ③만이면 후속으로 기록**

---

### Task 6. E2E 신규 + 회귀 동반 실행

**메타**.
- agent: `qa-engineer`
- 트랙: **ui 시각 검증 트랙**
- files: [`apps/web/e2e/context-shortcuts.spec.ts`]
- depends-on: [4, 5]

**시나리오**. S1 커서 이동 + URL 반영 · S3 `o` 전체화면 · S4 `t` 토글 ·
S5 `[` 사이드바(새로고침 후 영속) · S6 도움말 컨텍스트 노출 · **S7 `g`+`j` 무동작(E6)** ·
E12 `/issues` 밖에서 `j` 무동작 · `[` 는 살아있음

**회귀 동반 실행 (필수)**. `issue-split-view` · `issue-table` · `project-switcher` ·
`keyboard-shortcuts` · `keymap`

**검증**. 위 신규 + 회귀 5종 green · `pnpm typecheck && pnpm lint && pnpm test` ·
`bash scripts/verify-master-plan.sh` EXIT 0

---

## Plan 메타

- **task 수**. 6
- **wave**. 4 — `[T1] → [T2, T4] → [T3] → [T5] → [T6]` (T4 는 T1 만 의존해 조기 병렬)
- **구현 규율**. T1~T3 = red-first TDD (순수 로직·훅) / T4~T6 = ui 시각 검증 트랙
  (기존 E2E 동반 실행 + 브라우저 눈확인 — `/bts-impl` §타입별 규율)
- **파일 겹침**. 0 — 각 task 가 서로 다른 파일을 소유해 wave 계산이 단순하다
- **추가 검증**. typecheck · lint · vitest 전량 · playwright(신규 1 + 회귀 5) ·
  verify-master-plan · **`shortcuts.test.ts` 무수정 확인(git diff 0)**
- **작성 방식**. `writing-plans` 미호출 — bts-plan §Step 2 가 BTS 고유 형식을 명시하고
  설계(ADR 3건 + 스펙 FR11종 + 사전 grep)가 선확정돼 분해 입력이 이미 완결이다.
  형식 준수 항목(메타/RED/GREEN/REFACTOR/검증·depends-on·files)은 전 task 충족.

## 리뷰 결과

### 구조 리뷰 (실측 기반, 2026-08-03) — 결함 2건 발견·반영

**F1 (MAJOR·반영완료).** ADR D-2 의 *"리스너는 하나"* 명제가 **사실과 다르다**.
`document`/`window` keydown 리스너가 이미 **5개** 공존한다 — `useKeyboardShortcuts:155` ·
`useCommandPalette:52` · `issues.$key.tsx:89` · `ShareDashboardModal:181` ·
`use-timeline-zoom:139`. 여러 리스너 공존은 BTS 의 **기존 설계**이고 이중 발화는
`e.defaultPrevented` 로 조정하는 관례가 이미 있다(`usePaneEscapeClose` 주석).
→ ADR 에 명제 정정 + **컨텍스트 키 발화 시 `preventDefault` 필수** 파생 규칙 추가.

**F2 (MAJOR·반영완료).** T3 의 *"addEventListener 스파이로 리스너 1개 단언"* 이
**작동하지 않는다**. 테스트 환경에서 다른 훅이 함께 마운트되면 카운트가 1이 아니고,
다른 파일에 리스너가 추가돼도 못 잡는다(가드가 자기 파일만 본다).
→ **소스 전수 판별식**으로 교체 — `keyboard-shortcuts/` 안의
`document.addEventListener('keydown'` 이 정확히 1회.

**R1·R3·R5·R6·R7 판정** — R1 기존 `shortcuts.test.ts:121` 이 이미 가드이고
Plan 메타의 `git diff 0` 확인이 그 가드의 무력화를 막으므로 추가 장치 불요 /
R3 F9(목록 셀 편집)는 오히려 커서를 필요로 해 재사용이 이득, F11 은 상세 화면이라 무관 /
R5 레이어 구조는 `issue-detail`·`board` 추가로 그대로 확장 /
R6 T1→T2→T3 직렬은 층 구조상 불가피, T4 를 T1 의존으로 떼어 조기 병렬 /
R7 `aria-live`·`scrollIntoView` 는 같은 파일이라 분리해도 직렬화만 늘어남.

### plan-design-review (경량, Maxi 확정 2026-08-03)

시각 변화가 도움말 모달 섹션 1개뿐이고 커서 강조는 FR-UX-06 PR20 에서 이미 토큰화된
자산 재사용이라 mockup 생성·외부 모델 호출은 생략. D1~D6 을 실측으로 판정했다.

| | 판정 | 근거 |
|---|---|---|
| **D1** 커서 = split 강조 동일 표기 | ✅ **옳다. 유지** | ADR D-3 이 둘을 같은 개념으로 정의했으므로 같은 표기가 일관적이다. 다르게 그리면 *"같은 것인데 달라 보이는"* 더 나쁜 상태가 된다 |
| **D2** 체크박스 + 커서 겹침 | ✅ **충돌 구조적으로 불가** (실측) | bulk 선택은 **행 스타일을 안 바꾼다** — `selection.isSelected` 는 체크박스 `checked` 만 제어(`IssueTable.tsx:220`). 행 배경 `data-[state=selected]:bg-(--bg-selected)`(`ui/table.tsx:49`)는 `selectedKey` 전용. **다른 채널**이라 겹쳐도 안전 |
| **D3** 연타 깜빡임 | ⚠️ **눈확인 판정선 구체화** | 관측 3항 — ① 우측 페인이 중간 상태(스켈레톤/빈 화면)를 거치는가 ② **커서 이동이 페인 로딩을 기다려 끊기는가** ③ 네트워크 요청이 누적되는가. **판정선 — ① 또는 ②면 결함(즉시 대응), ③만이면 후속** |
| **D4** 도움말 모달 | 🔧 **구조 변경 필요 — T4 갱신** | 실측상 현재 모달은 **그룹 헤딩 없는 평평한 `<dl>`**(`SHORTCUTS.map` + `PALETTE_HELP_ITEM`). 섹션을 넣으려면 그룹 헤딩을 **도입**해야 하고, 그러면 기존 5종도 그룹에 들어가야 일관적이다 |
| **D5** `scrollIntoView` `block:'nearest'` | ✅ **적절** | 이미 보이면 안 움직이고 벗어나야 최소로 이동 — 항법에 정확히 맞다. `center` 는 매 키마다 화면이 튄다. **단 sticky 헤더에 첫 행이 가리지 않도록 `scroll-margin` 확인** |
| **D6** `aria-live` 공지 문구 | 🔧 **문구 확정** | **`"{총}개 중 {n}번째, {이슈키}, {제목}"`** (예 — *"25개 중 3번째, ATLAS-13, 로그인 실패 처리"*). 위치 → 식별자 → 내용 순. `polite` 는 연타 중 큐가 쌓이지 않고 멈춘 지점만 읽으므로 총 개수를 매번 실어도 피로하지 않고, 멈췄을 때 위치 감각이 완결된다 |

**D4 파생 — 그룹 라벨 문구 확정 (한국어, 사용자 대상이라 "전역" 같은 개발 용어 배제).**

| 그룹 | 포함 |
|---|---|
| **「어디서나」** | 기존 전역 5종 + `Cmd/Ctrl K` + **`[`**(app-shell 컨텍스트) |
| **「이슈 목록에서」** | `j` · `k` · `o` · `t` |

**T4 갱신 지시.** 평평한 `<dl>` → 그룹 헤딩 2개 구조로 변경하되 **기존 항목의 설명
문구·키 표기는 verbatim 보존**한다(`keyboard-shortcuts.spec.ts`·`keymap.spec.ts` 가
텍스트로 찾는다 — 계약 §2 h1 verbatim 규칙과 같은 부류의 위험). 동반 테스트에
**"기존 5종 문구 무변경"** 단언을 추가한다.
