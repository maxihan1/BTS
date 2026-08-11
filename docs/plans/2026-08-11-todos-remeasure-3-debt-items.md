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
  그 관용구를 쓰는 테스트 파일 **22개 중 20개가 34-set 밖**이고(정의 = executor 인자 0개 `new Promise(() => …)`), 교과서적 실례
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

## Plan

> **구현 규율.** 문서만 고치므로 red-first TDD 가 적용되지 않는다. 각 task 는
> `**대상**` / `**변경**` / `**검증**` 3단이고, `**검증**` 은 **반드시 셸 명령으로 실측 대조**한다.
> 「고쳤다」가 아니라 **「사본이 하나도 안 남았다」**를 증명해야 한다 —
> 이 저장소의 지배 결함이 `two-lists-never-check-each-other` 다.
>
> **`TODOS.md` 는 126KB 다. 통째 Read 금지** — `grep -n` 으로 줄번호를 얻고 부분 Read 한다.
> 모든 줄번호는 **편집 전 HEAD(`7760ec5ce`) 기준**이며, 앞 task 가 줄을 밀면 뒤 task 는
> 반드시 `grep -n` 으로 **다시 찾는다**.

### Task 1. `IssueCreateForm` 줄수 항목 — 표 수치 2셀 + 파일 상한 서술 정정

**메타**.
- agent: `backend-engineer`
- files: [`TODOS.md`]
- depends-on: []

**대상**. `TODOS.md:2368~2388` (`## ⬜ apps/web — \`IssueCreateForm\` 컴포넌트가 줄수 상한을 넘는다`)

**변경**. 4곳.

(1) `:2370-2371` 「무엇」 문단 — 파일 상한 서술이 **거짓**이다.

```
< **무엇.** `DEVELOPMENT.md §2.2` 는 「컴포넌트 200줄 이내 · 파일 300줄 이내」다.
< 2026-08-09 CREATE 게이트 작업 후 실측 — **컴포넌트 227줄 · 파일 321줄**.
---
> **무엇.** `DEVELOPMENT.md §2.2`(`:72`)는 「함수 30줄 이내 · **컴포넌트 200줄 이내**」다 —
> **파일 상한 조항은 없다.** 「파일 300줄」은 `DEVELOPMENT.md:55` §2.1 **Kotlin** 규칙이다.
> 다만 `docs/sdd/22-claude-code-env.md:209` 는 **언어 태그 없이** 「함수 30줄 이내, 파일 300줄 이내」를
> 규정해 두 정본이 갈린다 → 아래 신규 항목 「파일 300줄 상한 정본 충돌」로 넘긴다.
> 2026-08-09 CREATE 게이트 작업 후 실측 — **컴포넌트 227줄 · 파일 320줄**.
```

(2) `:2376` 표 「파일」 행 — main·헬퍼 분리 후 두 셀이 틀렸다. 「컴포넌트」 행(`:2375`)은 **무변경**.

```
< | 파일 | 349 | 426 | **321** | 300 |
---
> | 파일 | 348 | 426† | **320** | 300 |
```

(3) 표 바로 아래에 각주를 신설한다.

```
> † `426` 은 게이트 작업 직후 값으로 **재측정하지 못했다**(그 시점 커밋이 남아 있지 않다).
> `348`·`320` 은 2026-08-11 실측 — `git show 5b55318a6:apps/web/src/components/issue/IssueCreateForm.tsx | wc -l`
> → 348, `git show d3cd9df20:...` → 320. **현재 HEAD 는 318** 이다(이 항목이 열린 뒤 더 줄었다).
>
> **계수 기준 = raw(빈 줄·주석 포함). Maxi 확정 2026-08-11.** 근거 —
> `docs/plans/2026-08-05-fr-ux-13-f15-backlog-vertical-stack.md:703-708` 선례가 시작행~종료행 span 으로
> 재 사실상 raw 관행이고, 빈 줄·주석을 빼는 계수를 쓰면 이 컴포넌트가 **코드 변화 0 으로 185/28 이 돼
> 상환 없이 이 항목이 사라진다.**
```

(4) `:2378` — 괄호 안 두 수치.

```
< **★파일은 main 보다 작아졌다**(349 → 321). 남은 것은 컴포넌트 27줄 초과다.
---
> **★파일은 main 보다 작아졌다**(348 → 320). 남은 것은 컴포넌트 27줄 초과다.
```

**검증**.

```bash
# a) 이 항목 범위(2368~2400)에 옛 수치 349·321 이 0건
S=$(grep -n '^## ⬜ apps/web — `IssueCreateForm` 컴포넌트가 줄수' TODOS.md | cut -d: -f1)
sed -n "${S},$((S+40))p" TODOS.md | grep -cE '349|321'      # 기대: 0

# b) 새 수치가 표에 실제로 있다 (비-공허 짝 — a) 만으로는 「행을 통째로 지워도」 통과한다)
sed -n "${S},$((S+40))p" TODOS.md | grep -c '| 파일 | 348 | 426† | \*\*320\*\* | 300 |'   # 기대: 1

# c) 컴포넌트 행은 손대지 않았다
sed -n "${S},$((S+40))p" TODOS.md | grep -c '| 컴포넌트 | 197 | 243 | \*\*227\*\* | 200 |'  # 기대: 1

# d) 「파일 300줄 이내」를 §2.2 의 규칙이라 말하는 서술이 이 항목에 0건
sed -n "${S},$((S+40))p" TODOS.md | grep -c '§2.2 는 「컴포넌트 200줄 이내 · 파일 300줄 이내」'  # 기대: 0

# e) raw 확정이 기록됐다
sed -n "${S},$((S+40))p" TODOS.md | grep -c 'raw(빈 줄·주석 포함). Maxi 확정 2026-08-11'  # 기대: 1
```

---

### Task 2. 한글 placeholder 수치 — 사본 6곳 전수 정정 (28→27 · 29→28)

**메타**.
- agent: `backend-engineer`
- files: [`TODOS.md`]
- depends-on: [1]   # 같은 파일. 줄번호가 밀리므로 반드시 grep 으로 재탐색

**대상**. `TODOS.md` 의 6곳 — `:1802` · `:1824`(×2, 「28곳」과 「29곳」) · `:1826` · `:2092` · `:2094` · `:2096`
(편집 전 기준. Task 1 이 줄을 밀었으므로 `grep -n '28곳\|29곳' TODOS.md` 로 다시 찾을 것)

**변경**. 모든 「**28곳**」(리터럴 수) → 「**27곳**」, 모든 「29곳」(템플릿 포함 총합) → 「**28곳**」.
치환이 아니라 **한 곳씩 문맥을 보고** 바꾼다 — 두 수가 같은 문장에 함께 나오는 자리가 있다(`:1824`·`:2096`).

곳별 지시.

| 위치(편집 전) | 현재 | 정정 |
|---|---|---|
| `:1802` | `★**나머지 28곳과 ESLint 래칫은 별도 항목**이다.` | `28곳` → `27곳` |
| `:1824` | `한글 리터럴 placeholder 가 **28곳** 있다` | `**27곳**` |
| `:1824` | `반쪽 i18n 까지 더하면 29곳` | `28곳` |
| `:1826` | `나머지 28곳은 신규 등재.` | `27곳` |
| `:2092` (H2 제목) | `한글 리터럴 placeholder 28곳이 i18n 밖에 있다` | `27곳` |
| `:2094` | `한글 리터럴 \`placeholder\` 가 **28곳**` | `**27곳**` |
| `:2096` | `반쪽 i18n 까지 29곳.` | `28곳.` |

`:2094` 문단 끝에 감소 근거와 사각지대 정정을 덧붙인다.

```
> **★2026-08-11 재실측 — 27곳이다.** PR #352(`d3cd9df20`)가 `IssueCreateBasicFields.tsx:110` 1건을
> 해소해 리터럴 28 → **27** · 템플릿 포함 총합 29 → **28** 로 실제 감소했다.
> **★변수형은 사각지대가 2곳이 아니라 10곳 / 9파일이다.** `LabelAutocompleteInput.tsx:79` 는
> 함수 기본 파라미터라 리터럴·템플릿과 다른 **제3 유형**이고, 어떤 JSX 선택자로도 잡히지 않는다.
```

처방 문단(`:2098` 「`eslint.config.js:69·116` 에 이미 있는 … 두 배열」)은 **정본이 이미 옳다 → 무변경**.

**검증**.

**정정 전 실측 (2026-08-11 · HEAD `7760ec5ce`).** `28곳` **5건**(`:1802`·`:1824`·`:1826`·`:2092`·`:2094`) ·
`29곳` **2건**(`:1824`·`:2096`) · `27곳` **0건**. 합 7 occurrence 이고 위 표의 7행과 정확히 대응한다.
**`:1824` 한 줄에 두 수가 모두 있다** — 그 줄은 `28곳`→`27곳` 과 `29곳`→`28곳` 을 **둘 다** 고쳐야 한다.

```bash
# a) 옛 수치 전수 잔여 0 — 파일 전체다(사본이 흩어져 있어 국소 검사로는 못 잡는다)
grep -c '29곳' TODOS.md    # 기대: 0   (정정 전 2)

# b) 자릿수 이동이 정확히 일어났다 — 비-공허 짝. a) 만으로는 「줄을 통째로 지워도」 통과한다
grep -c '27곳' TODOS.md    # 기대: 5   (정정 전 0 — 옛 「28곳」 5건이 전부 내려왔다)
grep -c '28곳' TODOS.md    # 기대: 2   (정정 전 5 — 옛 「29곳」 2건만 남는다)

# c) 남은 「28곳」 2건이 전부 '템플릿 포함 총합' 문맥인지 눈으로 확인 (:1824 · :2096 이어야 한다)
grep -n '28곳' TODOS.md

# d) 재실측 근거가 기록됐다
grep -c 'PR #352(`d3cd9df20`)가 `IssueCreateBasicFields.tsx:110` 1건을' TODOS.md   # 기대: 1
grep -c '변수형은 사각지대가 2곳이 아니라 10곳 / 9파일' TODOS.md                    # 기대: 1
```

> ★기대값 `27곳=5` · `28곳=2` · `29곳=0` 은 **합이 7 로 보존**된다. 합이 7 이 아니면
> 문구를 지웠거나 새로 만든 것이다 — 정정이 아니라 개작이 됐다는 신호다.

---

### Task 3. 지연 MSW 항목 — 「34」는 유지하고 세는 정의 명시 + 누락 관용구 등재 + 처방 교체

**메타**.
- agent: `backend-engineer`
- files: [`TODOS.md`]
- depends-on: [2]   # 같은 파일

**대상**. `TODOS.md:2227~2240` (`## ⬜ apps/web(테스트 인프라) — 지연 MSW 핸들러 34개 파일의 …`).
`:1755` · `:2695` 의 「34개」 언급은 **숫자 무변경**이고 손대지 않는다.

**변경**. `:2230` 「무엇」 문단 뒤에 정의를 명시하고, `:2237~2240` 처방을 교체한다.

(1) `:2230` 뒤에 삽입.

```
> **★「34개」는 틀린 수가 아니다 — 세는 정의가 안 적혀 있었을 뿐이다(2026-08-11 실측).**
> 정의 = `(setTimeout ∨ msw delay()) ∧ (server.use( ∨ setupServer()`. 이 정의에서 HEAD 기준 **정확히 34** 다.
> `setTimeout` 만으로 세면 33, 주석 1건(`routes/projects.$projectKey.settings.import.test.tsx`)을 빼면 32 다.
> **정의를 안 적으면 다음 사람이 세는 법을 바꿔 「숫자가 틀렸다」고 결론낸다** — 실제로 그렇게 됐다.
>
> **★★그런데 이 34-set 자체가 대상을 놓치고 있다.** 최고위험 관용구인 **미해결 Promise**
> (`new Promise(() => {})` 계열 — 지연이 **무한**이라 `setTimeout(N)` 보다 누수 위험이 크다)가
> 정의에서 통째로 빠졌다. 그 관용구를 쓰는 테스트 파일 **22개 중 20개가 34-set 밖**이다
> (정의 = executor 인자 0개 — `new Promise(() => …)`. `() => {` 만 세면 16/15 로 줄어든다).
> 교과서적 실례 — `FavoriteButton.test.tsx:262` 가
> `http.post('/api/v1/favorites', () => new Promise<never>(() => {}))` 로 뮤테이션을 영원히 pending 시키고
> `:275` 에서 클릭한 뒤 정착을 기다리지 않고 끝난다(`:122` `afterEach` 는 `vi.clearAllMocks()` 뿐).
> 이 파일은 **34-set·33-set·32-set 어디에도 없다.**
```

(2) `:2237~2240` 처방 문단을 교체한다.

```
< **처방.** 공용 `createTestQueryClient` 헬퍼 + 전역 `afterEach` 로
< `queryClient.getMutationCache().getAll().filter(m => m.state.status === 'pending')` 가 빈 배열임을 단언.
< 디렉토리 단위로 나눠 넣을 것.
---
> **처방 — ★정적 목록보다 「1회 실측」이 먼저다 (2026-08-11 교체).**
> 원 처방(공용 `createTestQueryClient` 헬퍼 + 전역 `afterEach` 로
> `queryClient.getMutationCache().getAll().filter(m => m.state.status === 'pending')` 가 빈 배열임을 단언,
> 디렉토리 단위 분할)은 **구조는 유효하나 대상 집합을 grep 으로 만든다는 전제가 틀렸다.**
> 관용구를 손으로 열거하는 한 누락이 재발한다 — 이번에 실제로 재발했다(위 미해결 Promise 13파일).
>
> **바뀐 순서.**
> 1. **측정 PR 먼저.** 판정용 `afterEach` 를 `src/test/setup.ts` 에 **임시로** 전역 주입하고
>    유닛 전량을 **1회** 돌려 「실제로 pending 을 남기는 파일」의 실측 목록을 얻는다.
>    이 한 번의 실행이 관용구 열거 누락을 **구조적으로 불가능**하게 만든다. 기존 테스트 수정 0줄.
> 2. 그 실측 목록을 기준으로 디렉토리 단위 이주 PR 을 나눈다.
>
> **★착수 전 확인 2건.**
> ① `src/test/setup.ts:19-45` 가 `process.on('unhandledRejection')` 추가를 **절대 금지**로 못박았다
>    (리스너가 1개를 넘으면 vitest 가 물러나 종료 코드가 1 → 0 으로 뒤집힌다). 그 경로는 배제한다.
> ② 선례 `AutomationYamlImportDialog.test.tsx:107` 의 `?? []` 를 그대로 전역화하면
>    **미이주 파일 전량에서 항진명제**가 된다(레지스트리가 비면 무조건 통과).
>    합성 위반 양성 대조군이 없으면 가드 자체가 장식이다.
```

**검증**.

```bash
# a) ★숫자 34 를 바꾸지 않았다 — 정정 전 실측 5건(:1755 · :2227 · :2230 · :2232 · :2695)이 그대로여야 한다
grep -c '34개' TODOS.md    # 기대: 5   (정정 전 5 — 이 task 는 34 를 손대지 않는다)
grep -n '34개' TODOS.md | cut -d: -f1 | tr '\n' ' '   # 5개 줄번호가 나오는지 눈으로 확인
# 34 를 다른 수로 바꾼 diff 가 없다
git diff TODOS.md | grep -E '^-.*34개' | wc -l    # 기대: 0

# b) 세는 정의가 실제로 적혔다
grep -c '(setTimeout ∨ msw delay()) ∧ (server.use( ∨ setupServer()' TODOS.md   # 기대: 1

# c) 누락 관용구와 실례가 등재됐다
grep -c 'FavoriteButton.test.tsx:262' TODOS.md      # 기대: 1
grep -c '22개 파일 중 20개가 34-set 밖' TODOS.md     # 기대: 1

# d) 옛 처방 문단이 그대로 남아 있지 않다 (교체 확인 — 인용 안에는 남는다)
grep -c '^\*\*처방\.\*\* 공용 `createTestQueryClient`' TODOS.md   # 기대: 0
grep -c '정적 목록보다 「1회 실측」이 먼저다' TODOS.md              # 기대: 1
```

---

### Task 4. 신규 부채 3건 등재 (㉮ 정본 충돌 · ㉯ 강제 수단 0 · ㉰ Obsidian 동기화 부재)

**메타**.
- agent: `backend-engineer`
- files: [`TODOS.md`]
- depends-on: [3]   # 같은 파일

**대상**. `TODOS.md` **파일 끝**(현재 2784행, 마지막 `⬜` 섹션은 `:2764`). 기존 섹션 사이에 끼워 넣지 않는다.

**변경**. `---` 구분자 뒤에 아래 3개 H2 섹션을 **그대로** 추가한다.

````markdown
---

## ⬜ 문서 — `DEVELOPMENT.md §2.2` 와 SDD §22.7.1 이 파일 300줄 상한을 놓고 갈린다 (신규 · **Maxi 판단 대기**)

**무엇.** TypeScript 에 파일 300줄 상한이 있는가에 대해 두 정본이 다르게 말한다.

| 문서 | 좌표 | 서술 | 언어 태그 |
|---|---|---|---|
| `DEVELOPMENT.md` §2.1 | `:55` | 함수 30줄 이내, **파일 300줄 이내** | **Kotlin(백엔드)** |
| `DEVELOPMENT.md` §2.2 | `:72` | 함수 30줄 이내, 컴포넌트 200줄 이내 | TypeScript(프론트) — **파일 상한 없음** |
| `docs/sdd/22-claude-code-env.md` | `:209` | 함수 30줄 이내, **파일 300줄 이내** | **없음** |

**왜 문제인가.** SDD 그 표의 **이웃 행은 언어 특정일 때 「Kotlin: …」·「TypeScript strict…」처럼
명시적으로 태그를 붙인다.** 따라서 태그 없는 행을 Kotlin 전용으로 읽을 근거가 없다.
어느 쪽으로 정리하든 **반대편에 drift 를 만든다** — `CLAUDE.md §명세/범위 변경 시 전수 동기화` 대상이다.

**착수 전 Maxi 확정 필요.** ① TypeScript 에 파일 300줄 상한이 유효한가
② 유효하면 `DEVELOPMENT.md §2.2` 에 명문화할 것인가, 무효면 SDD `:209` 에 언어 태그를 붙일 것인가.
**추측 구현 금지.** 확정 전에는 어느 문서도 고치지 않는다.

**발견 경위.** 2026-08-11 재측정. `TODOS.md` 의 `IssueCreateForm` 줄수 항목이
「`§2.2` 는 컴포넌트 200줄 · 파일 300줄」이라 적고 있었는데 §2.2 에 파일 상한 조항이 없어 정정하다가 드러났다.

---

## ⬜ apps/web — 200줄 초과 컴포넌트가 18건인데 장부엔 1건 · 줄수 규칙에 강제 수단이 0 이다 (신규 · 미착수)

**무엇.** `DEVELOPMENT.md:72` 의 「컴포넌트 200줄 이내」에 **강제 수단이 하나도 없다.**

| 강제 수단 | 현황 |
|---|---|
| ESLint `max-lines` / `max-lines-per-function` / `complexity` | `apps/web/eslint.config.js` 에 **0건** |
| `scripts/` 판별식 | **0건** (`verify-master-plan.sh` 의 `wc -l` 3곳은 FR/SDD 개수 대조라 소스 줄수와 무관) |
| CI 검사 | **0건** |

**★장부는 실제 부채의 표본이다.** 2026-08-11 ESLint 실측 — 비-테스트 200줄 초과 **18건 / 18파일**.
그중 `TODOS.md` 에 등재된 것은 **1건**(`IssueCreateForm` 227줄)이고 그마저 **컴포넌트 중 14위**다.
1위는 `routes/issues.$key.tsx` 의 `IssueDetailPage` **1,041줄**로 4.6배다.
그 1건이 잡힌 것도 「가장 나빠서」가 아니라 PR #352 중 **우연히 눈에 띄어서**다.
⇒ **강제 수단을 넣지 않는 한 장부 숫자는 들여다볼 때마다 계속 오른다.**

**계수 기준 = raw(빈 줄·주석 포함). Maxi 확정 2026-08-11.**

**★★설계 제약 — ESLint 단독으로는 못 한다.**
`max-lines-per-function` 은 **규칙 하나에 임계값 하나**뿐이고, 겹치는 config 블록에서
뒤 블록이 앞 블록을 **병합이 아니라 대체**한다(같은 파일의 `no-restricted-syntax` 로 `--print-config` 실증됨).
그런데 「컴포넌트 200줄」과 「함수 30줄」은 **같은 규칙 id 를 공유**하고 **컴포넌트도 함수**다 —
`200` 을 걸면 199줄짜리 일반 함수가 통과하고, `30` 을 걸면 모든 컴포넌트가 걸린다. **동시 강제 불가.**
파일 단위 `override` 로 예외를 주면 그 파일에서 **두 트랙이 함께 죽는다.**

⇒ 컴포넌트(대문자 시작 + JSX 반환)와 일반 함수를 구분하려면 **소스 훑기 계약 테스트**가 필요하다.
선례 = `apps/web/src/components/__tests__/button-primitive-usage.test.ts`(327줄) —
①목록을 손으로 적지 않고 디렉토리에서 **도출**(`:96 readdirSync`) ②도출이 실제로 배선됐는지를
**비-공허 짝**으로 확인(`:305-307`) ③개수가 아니라 **목록 전수 비교**로 단언(`:319 toEqual`).

**★착수 전 알아야 할 수치.** raw 로 `max-lines-per-function: 200` 을 켜면 **CI 스코프에서 87건 / 78파일**이 red 다
(비-테스트 18건/18파일 + **테스트 69건/60파일**). `apps/web/package.json` 의 `lint` 가 `eslint src` 이고
`eslint.config.js` 의 `ignores` 가 `['dist']` 뿐이라 **테스트 파일도 린트 대상**이기 때문이다.
「비-테스트 18건」만 보고 예외 목록을 짜면 CI 에서 87건으로 터진다.

**★가드가 공허해질 수 있는 경로 3종.**
① `apps/web/src` 에 `eslint-disable` 주석이 57건 있고 `linterOptions.reportUnusedDisableDirectives` 설정이 없다
   — 계약 테스트가 「마커 전수」와 「히트 전수」를 **양방향** 비교하지 않으면 주석 한 줄로 목록에도 없고 히트도 아닌 파일이 생긴다.
② 실행처가 CI 하나뿐이다. **worktree 에서 husky 훅은 구조적으로 부재**하므로
   (`[[worktree-silently-disables-husky-hooks]]`) 훅을 실행처로 계산에 넣으면 안 된다.
③ 소스를 훑는 계약 테스트는 **자기 파일을 스캔에서 빼거나** NEEDLE 을 런타임 조립해야 자기탐지에 안 걸린다
   (`msw-single-setupserver.test.ts:123` 선례).

**Maxi 확정 방향 (2026-08-11).** 개별 상환보다 **강제 수단(래칫)이 먼저**다.

---

## ⬜ 인프라 — Obsidian 동기화가 「자동」이라 적혀 있으나 그 스크립트가 존재하지 않는다 (신규 · 미착수)

**무엇.** `Maxi_wiki/BTS/_index.md:59` 는 「**Repo → Obsidian** (단방향, 자동) — 머지 시 post-merge hook 이
`scripts/workflow/sync-obsidian.ts` 실행」이라 적는다. **그 파일은 저장소에 없다**(`scripts/` 전수 grep 0건).
실제 `.husky/post-merge` 가 하는 일은 `node scripts/build-dashboard.mjs` 재생성·푸시 **하나뿐**이다.
훅 배선 자체는 정상이다(`core.hooksPath=.husky/_` · shim `-rwxr-xr-x`).

**실제 피해.** `/bts-merge` Step 7 은 4항목(①`history.md` 등재 ②`docs/decisions/` 복사
③`docs/plans/` 복사 ④`learning:` 라벨 시 `learnings.md`)을 **수동**이라 명시하는데,
`_index.md` 는 자동이라 말한다. 두 문서가 갈린 결과 **PR #350~#359 10건이 `history.md` 에 통째로 누락**됐고
`docs/decisions/` 미러 1건이 stale 로 남았다(2026-08-11 세션이 수동 백필로 해소).

**★이 항목이 위 「강제 수단 0」과 같은 양식이다.** 규칙(Step 7)은 있는데 강제가 없고,
문서가 「자동」이라 말하니 사람도 에이전트도 손으로 하지 않는다. `CLAUDE.md:63` 은
「Phase 0 은 수동, **Phase 1 에 자동화**」인데 **지금이 Phase 1** 이다 — 예정된 자동화가 안 만들어진 채
`_index.md` 만 완료형으로 서술됐다.

**미러 전체 drift (2026-08-11 실측).** `docs/decisions` 총 134 — 미러 부재 3 · 내용 다름 2.
`docs/plans` 총 319 — 미러 부재 15 · 내용 다름 8. **합 28건.**

**착수 전 Maxi 확정 필요.** ① `sync-obsidian.ts` 를 실제로 만들 것인가, 아니면
`_index.md:59` 를 「수동」으로 정정하고 Step 7 체크리스트를 강제할 것인가
② 미러 drift 28건을 일괄 동기화할 것인가. **`Maxi_wiki/` 는 저장소 밖이라 CI 가 볼 수 없다** —
어떤 강제 수단이든 커밋 시점(`build-doc-index.mjs --check` 와 같은 자리) 또는 `/bts-merge` 스킬 안에 두어야 한다.
````

**검증**.

```bash
# a) ⬜ 섹션이 20 → 23 으로 정확히 3 늘었다 (✅ 는 48 불변)
grep -c '^## ⬜' TODOS.md    # 기대: 23
grep -c '^## ✅' TODOS.md    # 기대: 48

# b) 3건이 각각 실재한다 (개수가 아니라 목록으로 확인 — 「3 늘었다」는 엉뚱한 3건으로도 통과한다)
grep -c '^## ⬜ 문서 — `DEVELOPMENT.md §2.2` 와 SDD §22.7.1' TODOS.md              # 기대: 1
grep -c '^## ⬜ apps/web — 200줄 초과 컴포넌트가 18건인데 장부엔 1건' TODOS.md      # 기대: 1
grep -c '^## ⬜ 인프라 — Obsidian 동기화가 「자동」이라 적혀 있으나' TODOS.md        # 기대: 1

# c) 설계 제약(동시 강제 불가)이 실제로 기록됐다 — 이게 빠지면 다음 세션이 ESLint 로 시도한다
grep -c '규칙 하나에 임계값 하나' TODOS.md      # 기대: 1
grep -c '87건 / 78파일' TODOS.md                # 기대: 1
```

---

### Task 5. `personalization.md` ⑤ — 「3종은 이관됐다」가 거짓이므로 정정

**메타**.
- agent: `backend-engineer`
- files: [`docs/plan/product/personalization.md`]
- depends-on: []   # 다른 파일 — Task 1~4 와 병렬 가능

**대상**. `docs/plan/product/personalization.md:479-483` (`**구조 (코드리뷰 적발)**` 아래 ⑤ 항목)

**변경**. 2026-08-11 실측 — **5종 중 0종이 이관됐다.** `fcf93d9c6` 이 옮긴 것은
`BacklogFilterBar.tsx` 의 **다른 3종**(`BACKLOG_SEARCH_LABEL`·`BACKLOG_SEARCH_PLACEHOLDER`·`NO_EPIC_LABEL`)이다.

```
< `EPIC_SCOPE_NOTICE`·`BACKLOG_FILTERED_EMPTY_TITLE`·`BACKLOG_FILTER_RESET_LABEL`. 3종은
< `fcf93d9c6` 로 `i18n/backlog-labels.ts` 에 옮겼으나 나머지는 남았다. 실제 비용 —
---
> `EPIC_SCOPE_NOTICE`·`BACKLOG_FILTERED_EMPTY_TITLE`·`BACKLOG_FILTER_RESET_LABEL`.
> **★2026-08-11 실측 정정 — 5종 중 이관된 것은 0종이다.** `fcf93d9c6` 이 옮긴 것은
> `BacklogFilterBar.tsx` 의 **다른 3종**(`BACKLOG_SEARCH_LABEL`·`BACKLOG_SEARCH_PLACEHOLDER`·`NO_EPIC_LABEL`)이고,
> 위 5종은 지금도 `BacklogEpicPanel.tsx:27·35·44` · `BacklogBoard.tsx:48·57` 에 원시 한글 리터럴로 있다.
> 실제 비용 —
```

**검증**.

```bash
# a) 거짓 서술이 사라졌다
grep -c '3종은$' docs/plan/product/personalization.md                    # 기대: 0
grep -c '5종 중 이관된 것은 0종이다' docs/plan/product/personalization.md  # 기대: 1

# b) 정정 내용이 코드와 실제로 일치한다 (문서만 고치고 끝내면 또 거짓이 된다)
grep -c 'export const EPIC_PANEL_TITLE'   apps/web/src/components/backlog/BacklogEpicPanel.tsx  # 기대: 1
grep -c 'export const EPIC_LIST_ARIA_LABEL' apps/web/src/components/backlog/BacklogEpicPanel.tsx # 기대: 1
grep -c 'export const EPIC_SCOPE_NOTICE'  apps/web/src/components/backlog/BacklogEpicPanel.tsx  # 기대: 1
grep -c 'export const BACKLOG_FILTERED_EMPTY_TITLE' apps/web/src/components/backlog/BacklogBoard.tsx # 기대: 1
grep -c 'export const BACKLOG_FILTER_RESET_LABEL'   apps/web/src/components/backlog/BacklogBoard.tsx # 기대: 1
# 5종이 i18n 정본에 없다 (있으면 「0종 이관」이 거짓)
grep -cE 'EPIC_PANEL_TITLE|EPIC_LIST_ARIA_LABEL|EPIC_SCOPE_NOTICE|BACKLOG_FILTERED_EMPTY_TITLE|BACKLOG_FILTER_RESET_LABEL' \
  apps/web/src/i18n/backlog-labels.ts   # 기대: 0
```

---

### Task 6. 문서 인덱스 재생성

**메타**.
- agent: `backend-engineer`
- files: [`docs/INDEX.md`, `docs/INDEX-recent.md`, `docs/INDEX-fr.md`, `MEMORY.md`, `memory/index/*.md`]
- depends-on: [1, 2, 3, 4, 5]   # 모든 문서 편집 후

**대상**. 자동 생성 인덱스 4종.

**변경**. **생성 파일을 직접 수정하지 않는다**(`CLAUDE.md §문서 인덱싱 규칙`). 생성기를 재실행한다.

```bash
node scripts/build-doc-index.mjs
```

이 PR 이 새 plan 문서 1개(`docs/plans/2026-08-11-todos-remeasure-3-debt-items.md`)를 추가했으므로
`--check` 가 이미 **drift 2건**(`docs/INDEX.md` · `docs/INDEX-recent.md`)을 보고하고 있다(2026-08-11 실측).

> ★`build-doc-index.mjs` 는 `.mjs` 라 `--experimental-strip-types` 가 **불필요**하고,
> worktree 에 `node_modules` 가 없어도 돈다(실측 확인).

**검증**.

```bash
node scripts/build-doc-index.mjs --check    # 기대: FAIL 줄 0건 (drift 0)
git status --porcelain docs/INDEX*.md MEMORY.md memory/  # 변경된 파일이 커밋에 포함되는지 확인
```

---

### Task 7. 최종 게이트 — 마스터플랜 검증 + 전수 잔여 확인

**메타**.
- agent: `backend-engineer`
- files: []   # 검증 전용. 파일 수정 없음
- depends-on: [6]

**대상**. PR 전체.

**변경**. 없음. 검증만 한다.

**검증**.

```bash
# a) FR 카운트 drift 자동 차단 (종료 4 면 머지 차단)
bash scripts/verify-master-plan.sh; echo "exit=$?"    # 기대: exit 0 · FR 139/139

# b) ★프로덕션 코드 0줄 — 이 PR 의 불변량
git diff --stat origin/main...HEAD -- \
  'apps/web/src' 'backend' ':!*.md'                   # 기대: 빈 출력

# c) 변경 파일이 문서/인덱스뿐이다
git diff --name-only origin/main...HEAD               # 기대: TODOS.md · docs/**.md · MEMORY.md · memory/** 만

# d) 문서 인덱스 최신
node scripts/build-doc-index.mjs --check              # 기대: FAIL 0건

# e) 마이그레이션·신규 의존성 0
git diff --name-only origin/main...HEAD | grep -cE 'migration|\.sql$|build\.gradle|package\.json|pnpm-lock'  # 기대: 0
```

---

## Plan 메타

- task 수: **7**
- 예상 wave: **2** (Task 5 만 병렬 — 나머지는 `TODOS.md` 공유로 자동 직렬화)
- 구현 규율: **문서 정정 트랙** (red-first TDD 면제 — 프로덕션 코드 0줄).
  각 task 의 `**검증**` 은 셸 명령 실측 대조이며, **비-공허 짝**(「사본이 0」 + 「새 값이 실재」)을 반드시 함께 둔다
- 병렬 dispatch: bts-impl 이 `depends-on` + `files` 로 wave 계산. Task 1→2→3→4→6→7 직렬, Task 5 는 wave 1 에 함께
- 추가 검증: `bash scripts/verify-master-plan.sh` · `node scripts/build-doc-index.mjs --check`
- 커밋 규율: worktree 라 `--no-verify` + 손으로 `node scripts/build-doc-index.mjs --check`
  (husky lint-staged 가 메인 트리 미추적 파일을 끌어간 선례 — `[[worktree-lint-staged-steals-peer-untracked]]`)
- ★이 PR 이 **닫지 않는 것** — ㉮ 정본 충돌과 ㉰ Obsidian 자동화는 **Maxi 판단 대기**로 등재만 한다.
  추측 구현 금지(`CLAUDE.md §컨텍스트 효율`)

## 리뷰 결과 (← /bts-review-plan 채움)

_fast-track(chore) — 스킵. 게이트 1 도 함께 생략되고 게이트 2 만 정지한다._
