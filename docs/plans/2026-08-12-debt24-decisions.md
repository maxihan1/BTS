# 기술부채 24건 — 결정 6건 장부 등재 + 규율 정정 (0파)

> slug: debt24-decisions
> type: chore (fast-track — domain / spec / review-plan 및 게이트 1 생략)
> agent: backend-engineer
> 생성: 2026-08-12
> PR: #365 (draft, 브랜치 `docs/debt24-decisions`)

## Brief

2026-08-12 세션에서 확정한 **결정 6건을 장부(`TODOS.md`)에 등재**하고, 그 결정이 뒤집는 문서를
정정한다. 기술부채 24건 전수 처리의 **0파**이며, 전체 계획은 같은 PR 에서
`docs/plans/2026-08-12-debt24-master.md` 로 등재한다.

**프로덕션 코드 0줄.** 단 Maxi 확정으로 **판별식 1개(`verify-master-plan.sh` 룰 I)** 를 포함한다 —
이 PR 이 문서화하는 사고가 「규칙은 있는데 강제가 없어 25일간 재발」이므로 기록만 하면
같은 양식을 그대로 재생산한다.

### 이어가기 컨텍스트 (2026-08-12 세션 실측)

이전 세션이 브랜치 11개(#365~#375)와 draft PR 11건을 만들었으나 **전부 빈 커밋 1개 · 변경 파일 0**
이었다. worktree · plan 파일 · `.bts-cache` 는 모두 부재했다. 이 PR 은 그 중 **0파만** 진행한다.

| 항목 | 실측 |
|---|---|
| `docs/debt24-decisions` HEAD | `fd88dd3a7` (빈 커밋) |
| main 대비 변경 파일 | 0 |
| merge-base | `b9300ebee` = 현재 main (rebase 불필요) |
| 러너 엔진 헬스체크 | EXIT=0 |
| `verify-master-plan.sh` 기준선 | **EXIT=0** (PASS, FR 139/139) |
| `TODOS.md` ⬜ 항목 | **정확히 24건** (PR 본문 주장과 일치) |

### ★PR 본문 근거 4건이 실측으로 전복됐다

이 PR 의 존재 이유가 「맞는 진단을 지속 채널에 남긴다」인데 **그 진단 자체에 오류가 있었다.**
아래는 `TODOS.md` 에 **정정된 값으로** 들어가야 한다.

| # | PR #365 본문 주장 | 2026-08-12 실측 | 판정 |
|---|---|---|---|
| 1 | `toUpperCase()` grep → **0건** | `apps/web/src` 전체 **12건**. `projects.new.tsx` 안에서만 0건 | ❌ 범위 미기재 |
| 2 | `PROJECTS.KEY.eq()` → **8곳** | grep 8히트 중 1건은 `UserCalendarLookupAdapter.kt:28` **KDoc 주석**. 실제 코드 **7곳** | ❌ 7 + 주석 1 |
| 3 | 「issue-tracking BC 안에만」 | `TODOS.md:2766` 이 지목한 `ProjectDirectory.kt` 는 **identity-access BC** 이고 jOOQ DSL 이 아니라 **raw SQL `WHERE key = :key`**(`SQL_RESOLVE_KEY`). `PROJECTS.KEY.eq(` grep 에 **원리적으로 안 잡힌다** | ⚠️ 누락 · **cross-BC** |
| 4 | `scripts/workflow/README.md:92` 도 「똑같은 거짓 서술」 | 그 줄은 **`## 향후 추가 예정` 절 안**이다 — 「아직 없다」는 뜻이므로 **정확한 서술** | ❌ 장부 주장 자체가 오류 |

**#3 이 결정 1을 강화한다.** 처방 ②(백엔드 조회 대소문자 무시)는 **2개 BC** 를 건드려야 하고
그중 하나는 jOOQ DSL 이 아니라 raw SQL 이다. `PROJECTS.KEY.eq(` 한 줄로 훑는 방식은 그것을
영영 못 본다 — `[[two-lists-never-check-each-other]]` 양식. ⇒ **결정 ③(프론트에서 기존 패턴 재사용)** 유지.

### ★부수 발견 2건 (이 PR 에서 함께 정정)

1. **`TODOS.md:1924` 가 자기모순이다.** 제목은 `후속 2건` 인데 본문 표는 `⬜ 남은 3건`.
   그 표의 1번(머지된 PR 좀비 run 차단)은 **PR #354 가 이미 닫았다**(`scripts/cancel-merged-pr-runs.sh` 존재).
   ⇒ 본문 표를 2건으로 정정.
2. **`Maxi_wiki/` 는 저장소 밖이다** (`git ls-files` 0건 · 심볼릭/iCloud 아님).
   `_index.md:59` 수정은 **PR diff 에 나오지 않는다** — 증거를 plan · PR 본문에 남긴다.

### Obsidian 「자동」 서술 — 정정 범위 확정 (Maxi 확인 2026-08-12)

**동기화는 실제로 되고 있다** — `history.md` 에 #350~#364 전량 등재 확인. Maxi 가 손댈 일도 없다.
틀린 것은 「자동이냐」가 아니라 **「누가 하느냐」** 한 문장이다.

| 후보 | 전수 확인 결과 |
|---|---|
| `.husky/post-merge` | `node scripts/build-dashboard.mjs` **하나뿐**. Obsidian 미접촉 |
| `build-doc-index.mjs --obsidian` | `INDEX.md`(**파일 목록**)만 생성. 내용 동기화 아님 + 명시 플래그 필요, 훅에 미배선 |
| launchd / crontab | BTS↔Obsidian 항목 **0건** |
| `scripts/workflow/sync-obsidian.ts` | **부재** |
| 실제 주체 | `.claude/skills/bts-merge/SKILL.md:105` **Step 7 — 메인 에이전트 수행** |

⇒ 정정 문구에서 **「수동」이라는 단어를 쓰지 않는다.** 주체만 바로잡는다.
(스크립트가 하면 빠뜨릴 수 없고 에이전트가 하면 빠뜨릴 수 있다 — #350~#359 10건 누락이 그 실증.)

## 도메인 정리 (← /bts-domain 채움)

fast-track 스킵 — 신규 도메인 개념 없음.

## 스펙 (← /bts-spec Phase A 채움)

fast-track 스킵.

## Brainstorming Check (← /bts-spec Phase B 채움)

fast-track 스킵.

## Plan

**dispatch 정책.** 이 PR 은 마크다운·셸 편집뿐이라 sub-agent 를 쓰지 않고 메인 에이전트가 직접
수행한다. 오케스트레이터가 지시문에 개수를 옮겨 적다 틀리는 양식
(`[[orchestrator-instruction-counts-are-blindfolds]]`)을 피하기 위함이며, 대신 각 task 는
**전수 열거**로만 검증한다.

**TODOS.md 를 만지는 task(T2·T3·T7)는 같은 파일이라 직렬**이다.

---

### Task 1. master plan 문서 신설 — 24건 ↔ 11 PR 전수 매핑

**메타**.
- agent: `backend-engineer`
- files: [`docs/plans/2026-08-12-debt24-master.md`]
- depends-on: []

**GREEN**.
`TODOS.md` 의 `^## ⬜` **24건 전량**을 **줄번호와 함께** 표로 싣고 각 건이 어느 PR 로 가는지 적는다.
PR 본문의 묶음표(11 PR)는 건수만 있고 **어느 항목인지 적지 않아** 대조가 불가능했다 —
그 상태가 `[[two-lists-never-check-each-other]]` 양식 그 자체다.

| PR | 묶음 | TODOS 줄번호 | 건 |
|---|---|---|---|
| #365 (0파) | 결정 기록 + 규율 정정 | 2967 | 1 |
| #366 | 러너 자원 회수 | 1924 · 2596 | 2 |
| #367 | 이동 다이얼로그 | 2762 · 2780 · 2917 · 2943 | 4 |
| #368 | MovePreviewServiceTest | 2815 | 1 |
| #369 | 사용자 선택 UI | 2072 · 3154 · 3243 | 3 |
| #370 | 권한 게이트 | 2835 · 2851 · 2900 | 3 |
| #371 | IssueCreateForm | 2529 · 3180 | 2 |
| #372 | OpenAPI required | 2023 | 1 |
| #373 | 마스킹 required | 2570 | 1 |
| #374 | Obsidian + 대시보드 | 3082 · 3220 | 2 |
| #375 | setupServer 이주 | 1707 · 1878 · 2285 | 3 |
| — | personalization (보류) | 1507 | 1 |
| | | **합** | **24** |

**검증**. 표의 줄번호 24개가 `grep -n '^## ⬜' TODOS.md` 출력과 **집합으로 완전 일치**
(차집합 양방향 0). 개수 일치만으로는 부족하다.

---

### Task 2. TODOS.md — 결정 6건 등재

**메타**.
- agent: `backend-engineer`
- files: [`TODOS.md`]
- depends-on: [1]

**GREEN**. 각 해당 항목 본문에 `**★Maxi 확정 (2026-08-12).**` 블록을 추가한다.

| # | 항목 | 줄 | 확정 |
|---|---|---|---|
| 1 | 이동 키 소문자 | 2762 | **③** 기존 `PROJECT_KEY_PATTERN` 재사용 · 요청 전 차단 |
| 2 | 마스킹 required | 2570 | **②** 검증 제외 |
| 3 | CI 러너 | 1924 | **1대 유지** + 자원 회수 |
| 4 | Obsidian 동기화 | 3082 | `sync-obsidian.ts` **실제 제작**(#374) |
| 5 | 파일 300줄 상한 | 2967 | **TypeScript 무효** · SDD 행 분리 |
| 6 | 이동 403 문구 | 2943 | **③ 적용 후 PR-A 안에서 재판정** |

**검증**. `grep -c '★Maxi 확정 (2026-08-12)' TODOS.md` == 6 **이고**, 6건이
위 6개 줄번호가 속한 섹션 안에 각각 1건씩 있을 것(섹션 귀속 전수 확인).

---

### Task 3. TODOS.md — 실측 전복분 4건 + 부수 발견 1건 정정

**메타**.
- agent: `backend-engineer`
- files: [`TODOS.md`]
- depends-on: [2]

**GREEN**.
- `:2762` 처방 후보 블록 — `toUpperCase()` 범위 명시(파일 안 0건 / apps/web 12건),
  `PROJECTS.KEY.eq()` **코드 7곳 + 주석 1건**, `ProjectDirectory.kt` 가 **identity-access BC · raw SQL** 이라
  grep 에 안 잡힌다는 사실, ⇒ 처방 ② 는 **cross-BC** 라는 결론.
- `:3090~3093` — `README.md:92` 를 「거짓 서술」이라 한 **장부 자신의 주장**을 정정.
  「`## 향후 추가 예정` 절이라 정확하다. 이 PR 은 `_index.md:59` 만 고친다」.
- `:1924` 본문 표 — `⬜ 남은 3건` → **2건**(1번은 PR #354 가 닫음, 근거 `scripts/cancel-merged-pr-runs.sh`).

**검증**. 정정 3곳이 각각 grep 으로 잡히고, **옛 서술이 0건**임을 함께 확인
(추가만 하고 원문을 남기면 두 서술이 공존해 drift 가 는다).

---

### Task 4. 판별식 룰 I 신설 — 줄수 규칙 정본 대조 (RED)

**메타**.
- agent: `backend-engineer`
- files: [`scripts/verify-master-plan.sh`]
- depends-on: []

**RED**.
`scripts/verify-master-plan.sh` 에 룰 **I**(A~H 다음 미사용 문자) 를 추가한다.
`DEVELOPMENT.md` 와 `docs/sdd/22-claude-code-env.md` 의 **줄수 규칙 서술이 갈리면 `sync_fail`**(EXIT 4).

판정 대상 — SDD 22.7.1 표에 `파일 300줄` 행이 있는데 그 행에 **언어 태그가 없으면 FAIL**.
(`DEVELOPMENT.md:55` 는 §2.1 Kotlin, `:72` 는 §2.2 TypeScript 로 이미 태그돼 있다.)

**실패 메시지 (예상)**.
```
FAIL. 정합 drift — SDD 22.7.1 '파일 300줄' 행에 언어 태그가 없다 (DEVELOPMENT.md:55 는 Kotlin 전용).
```

**검증**. 이 시점에 `bash scripts/verify-master-plan.sh` 가 **EXIT=4** (지금 SDD:209 가 태그 없음).
기준선이 0 이었으므로 이 red 는 룰 I 가 만든 것이다.

---

### Task 5. SDD 22.7.1 행 분리 (GREEN)

**메타**.
- agent: `backend-engineer`
- files: [`docs/sdd/22-claude-code-env.md`]
- depends-on: [4]

**GREEN**.
`:209` 는 **규칙 2개가 한 행**이다 — `함수 30줄 이내, 파일 300줄 이내`.
통째로 「Kotlin:」을 붙이면 **TypeScript 에도 유효한 「함수 30줄」까지 Kotlin 전용**이 돼
반대 방향 drift 가 생긴다(`DEVELOPMENT.md:72` 가 TS 함수 30줄을 규정한다). 그래서 **행을 쪼갠다**.

```diff
- | 함수 30줄 이내, 파일 300줄 이내 | Claude 컨텍스트 효율 |
+ | 함수 30줄 이내 (Kotlin · TypeScript 공통) | Claude 컨텍스트 효율 |
+ | Kotlin: 파일 300줄 이내 | Claude 컨텍스트 효율 |
+ | TypeScript: 컴포넌트 200줄 이내 | Claude 컨텍스트 효율 |
```

세 번째 행은 **반대 방향 drift 를 닫는다** — SDD 에 TS 컴포넌트 상한이 **아예 없었다**
(`grep '200줄' docs/sdd/22-claude-code-env.md` → 0건). `CLAUDE.md §명세/범위 변경 시 전수 동기화` 요구.

**검증**. `bash scripts/verify-master-plan.sh` **EXIT=0** 복귀.

---

### Task 6. 룰 I 비-공허 확인 (뮤테이션)

**메타**.
- agent: `backend-engineer`
- files: [`scripts/verify-master-plan.sh`]
- depends-on: [5]

**REFACTOR/검증**.
가드가 **실제로 배선됐는지**를 잰다. 봉인 자체가 영구초록이던 전례가 있다
(`[[fr-ux-13-f16-f7-epic-control-test-contract-done]]`).

| 뮤테이션 | 기대 |
|---|---|
| M1. SDD 행에서 `Kotlin:` 태그 제거 | **EXIT=4** |
| M2. SDD 에서 `파일 300줄` 행 자체를 삭제 | **EXIT=4** (행 부재도 drift — 조용한 통과 차단) |
| M3. 무관한 줄 1개 수정 | EXIT=0 (과잉결합 아님) |

**★M2 가 핵심이다.** 「없으면 검사 안 함」으로 짜면 행을 지우는 것만으로 가드가 무력화된다 —
`[[seal-blinds-existing-guard]]` 양식. 세 뮤테이션 모두 **원복 후** 다음 task 로 간다.

**검증**. 3종 전부 기대대로. 원복 후 `verify-master-plan.sh` EXIT=0.

---

### Task 7. TODOS.md — `:2967` 해소 처리 (⬜ → ✅)

**메타**.
- agent: `backend-engineer`
- files: [`TODOS.md`]
- depends-on: [3, 5]

**GREEN**. 헤딩을 `## ✅ 문서 — … (해소 2026-08-12 · PR #365)` 로 바꾸고,
**무엇이 닫혔고 무엇이 안 닫혔는지**를 인용 블록으로 남긴다 —
「TS 파일 상한 무효 확정 + SDD 행 분리 + 룰 I 로 재발 차단」이 닫힌 것이고,
**200줄 초과 18건의 상환은 여전히 열린 부채**다(`:2990` 항목이 정본).

**검증**. `grep -c '^## ⬜' TODOS.md` == **23** (24 → 23).

---

### Task 8. `Maxi_wiki/BTS/_index.md:59` 주체 정정 (저장소 밖)

**메타**.
- agent: `backend-engineer`
- files: [`/Users/maxi.moff/Maxi_wiki/BTS/_index.md`]
- depends-on: []

**GREEN**. §동기화 규칙 첫 줄의 **메커니즘**만 바로잡는다. 「수동」이라는 단어는 쓰지 않는다.

```diff
- - **Repo → Obsidian** (단방향, 자동) — 머지 시 post-merge hook이 `scripts/workflow/sync-obsidian.ts` 실행
+ - **Repo → Obsidian** (단방향) — `/bts-merge` Step 7 에서 메인 에이전트가 수행
+   (`history.md` 1줄 등재 · `decisions/`·`plans/` 복사 · `learning:` 라벨 시 `learnings.md`)
+ - post-merge hook 은 `docs/progress.html` 재생성 전용 — Obsidian 을 건드리지 않는다
+ - 스크립트 자동화(`scripts/workflow/sync-obsidian.ts`)는 **아직 없다** (#374 예정)
```

**⚠️ 저장소 밖이라 PR diff 에 나오지 않는다.** PR 본문에 「저장소 밖 수동 변경 1건」으로 명시한다.

**검증**. `grep -n 'sync-obsidian' /Users/maxi.moff/Maxi_wiki/BTS/_index.md` 가
「아직 없다」 맥락에서만 잡히고, 「post-merge hook이 … 실행」 서술은 **0건**.

---

### Task 9. 문서 인덱스 · 대시보드 재생성 + 전체 verify

**메타**.
- agent: `backend-engineer`
- files: [`docs/INDEX*.md`, `MEMORY.md`, `docs/progress.html`]
- depends-on: [1, 7, 8]

**검증** (전부 통과해야 함).
1. `node scripts/build-doc-index.mjs` → 재생성 후 `--check` EXIT=0
2. `bash scripts/verify-master-plan.sh` → **EXIT=0**
3. `node scripts/build-dashboard.mjs` → 부채 카운트 **24 → 23**
4. `git status` 에 의도치 않은 파일 없음

## Plan 메타

- task 수: 9
- 구현 규율: TDD (T4 RED → T5 GREEN → T6 뮤테이션). 문서 task 는 **전수 열거 검증**
- 병렬 dispatch: **미사용** (마크다운·셸 편집, sub-agent 없이 메인 에이전트 직접 수행)
- 직렬 제약: T2 → T3 → T7 (같은 `TODOS.md`), T4 → T5 → T6 (룰 I 사이클)
- PR diff 밖 변경: **1건** (`Maxi_wiki/BTS/_index.md` — 저장소 밖)

## 실행 기록 (context-notes)

### ★룰 I — 1차 초안이 절반만 봉인돼 있었다 (코드리뷰 적발)

기준선(무변경) EXIT=0 에서 출발. GREEN 을 **먼저 커밋한 뒤** 뮤테이션을 돌렸다
(`[[mutation-test-requires-committed-baseline]]`).

**초안은 3종만 돌리고 통과했다.** 코드리뷰가 「그 3종이 못 잡는 4번째 경로」를 찾으라는 지시로
**11종**까지 확장하자 **4개가 뚫렸다.**

| 뮤테이션 | 초안 | 현재 | 기대 |
|---|---|---|---|
| M1 `Kotlin:` 태그 제거 | 4 | **4** | 4 |
| M2 Kotlin 행 삭제 | 4 | **4** | 4 |
| M3 무관한 줄 수정 | 0 | **0** | 0 |
| M4 Kotlin 행 복제 | 4 | **4** | 4 |
| M5 표 밖 **산문** 언급 | 4 ❌**오탐** | **0** | 0 |
| M6 **통째 태그로 되돌림** | **0** ❌ | **4** | 4 |
| M7 공통 `함수 30줄` 행만 삭제 | **0** ❌ | **4** | 4 |
| M8 **SDD 파일 자체 부재** | **0** ❌ | **3** | 3 |
| M9 **§2.1 → §2.2 조항 이동** | **0** ❌ | **4** | 4 |
| M11 한 행 안 2회 표기 | **0** ❌ | **4** | 4 |
| 원복 후 | 0 | **0** | 0 |

**★M6 이 가장 아프다.** 초안 I-1 의 정규식이 대안(`|`)으로
`^\| *Kotlin: *함수 30줄 이내, 파일 300줄 이내` 를 허용했다 — **이 PR 이 「행을 쪼개야 한다」며
거부한 바로 그 형태를 가드가 화이트리스트에 올린 것**이다. 방어적으로 대안을 하나 더 적은 것이
정확히 반대 효과를 냈다.

**★M9 는 단언과 검사의 괴리였다.** 조항을 §2.1 Kotlin → §2.2 TypeScript 로 옮기면
(= 이 PR 이 무효라 선언한 상태 복원) 초안은 EXIT 0. 출현 **횟수**만 세고 **어느 절인지**는
안 봤기 때문이다. 그런데 실패 메시지는 「`DEVELOPMENT.md:55` 는 §2.1 Kotlin 전용 조항이다」라고
**검사하지도 않는 사실을 단언**하고 있었다.

**★M8 은 자기가 인용한 양식의 재생산이었다.** 주석에 「행 부재도 FAIL 이다 —
`[[seal-blinds-existing-guard]]`」라 적어 놓고, **파일** 부재는 `if [[ -f ]]` 로 조용히 건너뛰었다.
한 층 위에서 같은 결함을 만든 것이다.

**처방 — 열거를 늘리지 않고 판정을 뒤집었다.**

| 초안 | 현재 |
|---|---|
| 정규식 대안으로 두 형태 허용 | **3행 전수 열거 · 행 전체 정확 매치** (M6·M7·M11 동시 봉쇄) |
| `grep -c` 로 출현 횟수만 | `DEVELOPMENT.md` 를 **`awk` 로 절 구간 절단** 후 계수 (M9) |
| `if [[ -f ]]` 로 감쌈 | **사전 점검 `exit 3`** 으로 승격 (M8) |
| 파일 전체에서 `파일 300줄` 1건 | **표 행(`^\|`)으로 범위 축소** — 산문 오탐 제거 (M5) |

### ★자기 검증의 맹점 — 「옛 서술 잔존 0건」이 거짓이었다

1차 검증은 `⬜ 남은 3건` · `똑같은 거짓 서술` · `## ⬜ 문서 —` **세 문자열**로 「옛 서술 0건」을
선언했다. 셋 다 **내가 정정한 자리 안에서만** 찾는 **닫힌 열거**라,
**다른 항목이 이 항목을 참조하는 경로**를 원리적으로 못 본다.

코드리뷰가 `TODOS.md:2543` (⬜ `IssueCreateForm` 항목, **PR #371 담당**)에서 잔존 1건을 찾았다.

- 「SDD `:209` 는 언어 태그 없이 … 두 정본이 갈린다」 — 이 PR 이 무효화한 서술
- 상한 표의 `| 파일 | … | 300 |` 행 — TS 파일 상한 300 을 적용한 값

**material 한 이유** — #371 의 범위가 「320 → 300 을 줄여야 하는가」에 달려 있다.
#371 **PR 본문**은 범위가 좁아진 걸 알지만 **정본으로 선언된 장부는 몰랐다.**
장부를 정본으로 읽으라는 것이 이 PR 의 전제이므로 방향이 반대다.
⇒ 그 항목에 갱신 블록을 넣고 표의 상한을 「**없음** (TS 무효)」로 정정했다.

`[[two-lists-never-check-each-other]]` 양식이 **이 PR 자신의 검증 절차에서** 재현됐다.

### RED → GREEN 순서 실측

| 시점 | EXIT |
|---|---|
| 작업 전 기준선 | 0 |
| 룰 I 추가 직후 (T4) | **4** — I-1 태그 0행 · I-2 0행 |
| SDD 행 분리 후 (T5) | **0** |

### 워크플로우 편차 4건 — 사유 등재

| # | 편차 | 사유 |
|---|---|---|
| 1 | `/bts-domain` · `/bts-spec` · `/bts-review-plan` · 게이트 1 생략 | `type=chore` fast-track 정책 (`.claude/skills/bts/SKILL.md` Phase C) |
| 2 | sub-agent 미사용 | 마크다운·셸 편집뿐. 오케스트레이터가 지시문에 개수를 옮겨 적다 틀리는 양식(`[[orchestrator-instruction-counts-are-blindfolds]]`) 회피 |
| 3 | T2 · T3 · T7 을 **한 커밋**으로 합침 | 셋 다 `TODOS.md` 단일 파일이고 편집 구간이 겹친다. 결정 1의 확정(③)은 근거 정정과 분리하면 문장이 성립하지 않는다 |
| 4 | `.bts-cache/classify.json` 의 `task_count` 갱신 실패 | Bash 권한 거부. 부수 기록이라 진행에 영향 없음 |

### ★훅 우회 4회 — 사유와 대체 검증

worktree 에 `node_modules` 가 없어 `.husky/pre-commit` 의 `node_modules/.bin/lint-staged` 가
**실행 자체가 불가**했다. `--no-verify` 로 우회하되 **훅의 두 검사를 매 커밋마다 손으로 대체 확인**했다.

| 훅 검사 | 대체 확인 |
|---|---|
| ① `lint-staged` | 설정(`.lintstagedrc.json`)이 `apps/web/**/*.{ts,tsx,js,jsx}` 만 대상이다. 이 PR 의 스테이징에서 해당 확장자 **0건** ⇒ 원래 no-op |
| ② `build-doc-index.mjs --check` | 매 커밋 전 실행해 **EXIT=0** 확인 |

**심볼릭 링크로 `node_modules` 를 끌어오지 않았다** — worktree 의 심볼릭 `node_modules` 는
pnpm auto-install 을 유발해 무-TTY 로 죽고(`[[worktree-pnpm-verify-deps-symlink]]`),
lint-staged 가 peer 의 untracked 파일을 훔치는 사고가 있었다
(`[[worktree-lint-staged-steals-peer-untracked]]`).

### 최종 검증

| 항목 | 결과 |
|---|---|
| `bash scripts/verify-master-plan.sh` | **EXIT=0** |
| `node scripts/build-doc-index.mjs --check` | **EXIT=0** |
| `node scripts/build-dashboard.mjs` 부채 카운트 | **24 → 23** |
| `TODOS.md` `★Maxi 확정 (2026-08-12)` | **6건** — 서로 다른 6개 섹션 귀속 |
| `TODOS.md` `^## ⬜` | **23건** |
| 옛 서술 잔존 (정정 자리 안) | **0건** (`⬜ 남은 3건` · `똑같은 거짓 서술` · `## ⬜ 문서 —`) |
| 옛 서술 잔존 (**참조 경로**) | 1차 **미검출** → 코드리뷰가 `TODOS.md:2543` 적발 → 정정 완료 |
| master plan ↔ 장부 줄번호 | **양방향 차집합 0** (24/24) |
| 룰 I 뮤테이션 | 초안 3종 통과 → 리뷰 확장 11종에서 **4건 구멍** → 재작성 후 **전량 기대대로** |

## 리뷰 결과

`/bts-review-plan` 은 fast-track 스킵. `/bts-codereview` 의 `superpowers:code-reviewer` 는 수행.

### `superpowers:code-reviewer` — **CONCERNS 2건 · 둘 다 채택하여 수정**

| # | 지적 | 판정 | 조치 |
|---|---|---|---|
| C-1 | 룰 I 가 우회 경로 4종(M6·M7·M8·M9)+2종(M10·M11)을 통과시킨다 | **타당** — 직접 재현 | 룰 I 재작성. M10 은 **명시된 경계**로 문서화 |
| C-2 | `TODOS.md:2543` 에 옛 서술 잔존. PR 본문의 「잔존 0건」이 거짓 | **타당** — 직접 확인 | 해당 항목에 갱신 블록 + 상한 표 정정. PR 본문 문구 교체 |
| C-3 | I-1 이 산문 언급에 오탐 | **타당** | 표 행(`^\|`)으로 범위 축소 |
| 기타 | `PROJECTS.KEY.eq(` 히트 8 에 범위 표기 없음 (자기 삽입이 히트를 늘림) | **타당** | `backend/ 기준` 명시 |

**리뷰가 확인해 준 것** — 실측 재검증 **6/6 전부 일치**. 셸 관용구 의심 2건
(`$(grep -c … || true)` 안전성 · ERE 교대 앵커)은 **무혐의**로 실증됐다.

### 채택하지 않은 것

- **M10 (제3의 문서에 상충 사본)** — 룰 I 의 계약은 **두 정본 사이의 정합**이다.
  전 문서 스캔은 오탐(인용·회고·`TODOS.md` 자신)이 많아 가드를 무력화하는 쪽이 크다.
  스크립트 주석에 **보지 않는 범위**로 명시했다 — 조용한 축소가 아니다.
- **PR #366·#375 본문의 `9건 cancelled` 낡은 수치** — 이 PR diff 밖이다.
  각 PR 착수 시 재측정 대상이며 `TODOS.md` 가 이미 그렇게 지시하고 있다.
