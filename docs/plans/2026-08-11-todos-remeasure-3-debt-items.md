# TODOS 정본 수치 3항목 재측정 정정 + 신규 부채 3건 등재

> slug: todos-remeasure-3-debt-items
> type: chore
> agent: backend-engineer
> 생성: 2026-08-11

## Brief

2026-08-11 재측정(워크플로우 13 에이전트 · 실측 → 적대적 반증 → 종합)에서 `TODOS.md` 기술부채 3항목의
정본 수치가 코드와 어긋난 것이 확인됐다. 프로덕션 코드는 한 줄도 건드리지 않고 **장부를 코드에 맞춘다.**
아울러 재측정 과정에서 드러난 신규 부채 3건을 등재한다.

**Maxi 확정 (2026-08-11).**
- 착수 방향 = **강제 수단 먼저** (개별 부채 상환이 아니라 신규 유입 차단). 이 PR 은 그 첫 단계(R1).
- 줄수 계수 기준 = **raw**(빈 줄·주석 포함).

### classify

- type: `chore` (문서만 · 프로덕션 코드 0줄 · 마이그레이션 0 · 신규 API 0 · 신규 의존성 0)
- agent: `backend-engineer`
- primary_bc: 없음 (BC 무관)
- FR수 불변 **139**

### 정정 대상 (실측 근거 포함)

**① `IssueCreateForm` 줄수 항목** (`TODOS.md:2368~2386`)

| 셀 | 정본 | 실측 | 근거 |
|---|---|---|---|
| 표 「파일」행 main | 349 | **348** | `git show 5b55318a6:<path> \| wc -l` |
| 표 「파일」행 헬퍼 분리 후 | 321 | **320** | `git show d3cd9df20:<path> \| wc -l` |
| (현재 HEAD) | — | **318** | 각주로 명시 |
| 표 「컴포넌트」행 197/243/227 | — | 세 시점 모두 일치 | **무변경** |

- 본문 「2026-08-09 실측 — 컴포넌트 227줄 · 파일 321줄」 → 파일 **320**
- 「★파일은 main 보다 작아졌다(349 → 321)」 → **(348 → 320)**
- 「무엇」 문단의 「`DEVELOPMENT.md §2.2` 는 컴포넌트 200줄 이내 · **파일 300줄 이내**다」는 **거짓**.
  `DEVELOPMENT.md:72`(§2.2 TypeScript)에 파일 상한 조항이 없고, 파일 300줄은 `DEVELOPMENT.md:55`(§2.1 Kotlin)다.
  → 서술을 실측대로 정정하되 **어느 문서가 정본인지는 이 PR 에서 확정하지 않는다**(신규 부채 ㉮로 등재).
- 계수 기준 **raw** Maxi 확정을 항목에 명시. 근거 — `docs/plans/2026-08-05-fr-ux-13-f15-backlog-vertical-stack.md:703-708`
  선례가 시작행~종료행 span 으로 재 사실상 raw 관행이고, skip 계수를 쓰면 이 파일이 **코드 변화 0 으로 185/28 이 돼
  상환 없이 항목이 사라진다.**

**② 한글 placeholder 항목** (`TODOS.md:2092·2094·2096` + 사본 서술 `:1802·:1824·:1826`)

- 「28곳」(리터럴) → **27곳** · 「29곳」(템플릿 포함) → **28곳**.
  `PR #352`(`d3cd9df20`)가 `IssueCreateBasicFields.tsx:110` 1건을 해소해 29 → 28 로 **실제 감소**했다.
- 「변수형 사각지대 **2곳**」 서술 → **10곳 / 9파일**. `LabelAutocompleteInput.tsx:79` 는
  함수 기본 파라미터라 리터럴·템플릿과 다른 **제3 유형**이다.
- 처방 「`eslint.config.js:69·116` 두 배열에 셀렉터 추가」는 **정본이 이미 옳다 — 무변경.**

**③ 지연 MSW 항목** (`TODOS.md:1755·2227·2230·2232·2695` — 5곳)

- **「34개」는 틀린 수가 아니다.** `(setTimeout ∨ msw delay()) ∧ (server.use( ∨ setupServer()` 정의에서
  HEAD 기준 정확히 34다. 숫자를 바꾸지 말고 **세는 정의를 본문에 명시**한다.
- ★신규 사실 등재 — 최고위험 관용구 **`new Promise(() => {})`(지연이 무한)** 가 34-set 에서 통째로 빠졌다.
  그 관용구를 쓰는 테스트 파일 **15개 중 13개가 34-set 밖**이고, 교과서적 실례
  `FavoriteButton.test.tsx:262`(`http.post('/api/v1/favorites', () => new Promise<never>(() => {}))` 뒤
  `:275` 클릭 · 정착 대기 없음 · `:122` afterEach 는 `vi.clearAllMocks()` 뿐)는 **어느 목록에도 없다.**
- 그래서 처방을 「정적 목록 먼저」 → **「판정용 `afterEach` 를 임시로 전역 주입해 유닛 전량 1회 실행 →
  실측 목록 확보」 선행**으로 교체한다. 정적 grep 목록에 의존하는 한 관용구 열거 누락은 재발한다.

### 신규 부채 등재 3건

- **㉮ `DEVELOPMENT.md §2.2` ↔ `docs/sdd/22-claude-code-env.md:209` 파일 300줄 상한 충돌** (Maxi 판단 대기).
  SDD 쪽은 **언어 태그 없이** 「함수 30줄 이내, 파일 300줄 이내」를 규정하고, 같은 표의 이웃 행은
  「Kotlin: …」처럼 태그를 붙이므로 태그 없는 행을 Kotlin 전용으로 읽을 근거가 없다.
- **㉯ 200줄 초과 컴포넌트가 18건인데 장부엔 1건 — 강제 수단 0.**
  ESLint `max-lines`·`max-lines-per-function`·`complexity` 0건 · `scripts` 판별식 0건 · CI 검사 0건.
  1위 `routes/issues.$key.tsx` `IssueDetailPage` **1,041줄**, `IssueCreateForm` 227 은 컴포넌트 중 **14위**.
  ★설계 제약 동봉 — ESLint `max-lines-per-function` 은 **규칙당 임계값 1개**라 「컴포넌트 200」과
  「함수 30」을 동시에 강제할 수 없다(뒤 블록이 앞 블록을 **대체**). 컴포넌트(대문자 시작 + JSX 반환)와
  일반 함수를 구분하려면 **소스 훑기 계약 테스트**가 필요하다.
- **㉰ Obsidian 동기화 자동화 부재.** `Maxi_wiki/BTS/_index.md:59` 가
  「머지 시 post-merge hook 이 `scripts/workflow/sync-obsidian.ts` 실행」이라 적었으나 **그 파일이 저장소에 없다**
  (`scripts/` 전수 grep 0건). 실제 `.husky/post-merge` 는 `build-dashboard.mjs` 재생성·푸시만 한다.
  `CLAUDE.md:63` 은 「Phase 0 은 수동, **Phase 1 에 자동화**」인데 지금이 Phase 1 이다.
  **PR #350~#359 10건 미등재의 근본 원인**(2026-08-11 세션이 수동 백필로 해소).

### 제약

- 문서만. **프로덕션 코드 0줄** · 백엔드 0줄 · 프론트 0줄 · 마이그레이션 0 · 신규 API 0 · 신규 의존성 0
- 머지 전 `bash scripts/verify-master-plan.sh` 통과 필수
- worktree 커밋은 `--no-verify` + 손으로 `node scripts/build-doc-index.mjs --check`
  (husky 가 메인 트리 미추적 파일을 끌어간 선례 — `worktree-lint-staged-steals-peer-untracked`)

## 도메인 정리 (← /bts-domain 채움)

_fast-track(chore) — 스킵._

## 스펙 (← /bts-spec Phase A 채움)

_fast-track(chore) — 스킵._

## Brainstorming Check (← /bts-spec Phase B 채움)

_fast-track(chore) — 스킵._

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)

_fast-track(chore) — 스킵. 게이트 1 도 함께 생략되고 게이트 2 만 정지한다._
