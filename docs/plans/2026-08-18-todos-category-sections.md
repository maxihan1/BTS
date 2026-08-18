# TODOS.md 를 카테고리 절로 물리 재배열한다

> 티어: T2
> slug: todos-category-sections
> type: chore
> agent: (인라인 판정 — 게이트 1에서 확정)
> 생성: 2026-08-18

## Brief

**Maxi 원문.** 「todos.md와 대시보드 개선 작업 진행중이었는데 그거 이어서 하자」

**정확한 범위.** `docs/plans/2026-08-18-debt-dashboard-plain-language.md` 가 PR #389 에서
**후속으로 이월한 W7·W10**, 그리고 그 계획서가 「#389 가 만든다」고 적었으나 **만들어지지 않은
안전망**이다.

| # | 항목 | 출처 |
|---|---|---|
| 1 | 재배열 무손실을 기계로 확인하는 안전망 | 같은 계획서 `:221`(Maxi 결정 ③ 표) · `:245`(근거 ㄱ) · NFR N5 |
| 2 | W7 — `TODOS.md` 를 카테고리 절로 물리 재배열 | 같은 계획서 `:97` (2026-08-18 Maxi 추가 요청) |
| 3 | W10 — 영역 접두 없는 항목 1건에 접두 부여 | 같은 계획서 `:100` |

**★착수 전 실측 — 안전망은 만들어지지 않았다.**
`docs/plans/2026-08-18-debt-dashboard-plain-language.md:221` 은 「후속 PR 이 쓸 안전망(본문 해시
불변 판별식)은 이 PR 이 만든다」고 적고, `:245` 근거 ㄱ 는 「그 판별식이 (나)에서 나온다. 순서가
뒤집히면 맨손으로 5,000줄을 옮기는 것이다」라고 적는다. 실측 결과 —

- `scripts/` 전체에 `해시`/`hash` **0건**
- `todos-plain-language-contract.test.ts` 12종 · `build-dashboard.test.mjs` 36종 어디에도
  **재배열 전후 비교 단언 없음**
- 같은 계획서 `:630` **Implementation Tasks 이행 상태(T1~T5)에 이 항목 자체가 없다**

즉 약속 목록과 이행 목록이 서로를 대조하지 않았다 — 저장소가 이름 붙인
`two-lists-never-check-each-other` 의 재발이다. **이 PR 의 첫 task 가 그 안전망 신설이다.**

**Maxi 결정 2건 (착수 전 선결).**

| # | 결정 | 근거 |
|---|---|---|
| A | **재배열 구조 A안** — 미해결 28건만 카테고리 5절 H1 로. 해소 76건은 맨 뒤 「해소」 절에 현재 순서 그대로 | 화면 렌더(`renderByCategory`)가 이미 미착수·보류만 카테고리로 묶는다(NFR N2). 파일 구조가 같은 규칙 하나를 따르게 해 두 벌이 갈라질 여지를 없앤다. B(✅까지 분류)는 N2 를 뒤집는 재결정 + 이동량 4배, C(재배열 취소)는 Maxi 추가 요청을 무기한 연기 |
| B | **티어 T2 로 승격** | 안전망이 `parseTodos` 에 소속 절 정보를 요구하고, `scripts/build-*.mjs` 는 `GUARD_CI` 표면(T2). 판별식이 자체 fence 스캔을 쓰는 우회는 `every-new-heading-scanner-forgets-code-fences` 가 경고한 **4벌째 펜스 맹목 스캐너**를 만드는 길이라 기각 |

**classify 결과와 오버라이드.**

| 항목 | classify 출력 | 실제 | 근거 |
|---|---|---|---|
| type | `backend` | **`chore`** | Kotlin **0줄** 작업이다. 신호 0 → `backend` 기본값으로 떨어진 것. 부채 `52` 의 5번째 자기 실연 |
| agent | `backend-engineer` | 미정 | 백엔드 무관. 게이트 1에서 확정 |
| slug | `todos-md` | **`todos-category-sections`** | 파일명만 남고 작업 내용이 사라졌다 |
| tier | `T1` | **`T2`** | 표면 실측 — Maxi 결정 B |

**티어 근거 (표면 실측).**

| 경로 | 표면 | 티어 |
|---|---|---|
| `TODOS.md` | `DOC` (`*.md`) | T0 |
| `scripts/workflow/*.test.ts` (신설) | `TEST` (`scripts/**/*.test.{ts,mjs}`) | T1 |
| `scripts/build-dashboard.mjs` | **`GUARD_CI`** (`scripts/build-*.mjs`) | **T2** |

혼합 → 최고 티어 **T2**. `UNMAPPED` 0.

**★게이트 2 요약에 실을 것.** 신설 판별식은 실질 `GUARD_CI` 성격인데 `SURFACE_PRECEDENCE` 의
`TEST` 우선 때문에 단독으로는 T1 로 떨어진다 — 부채 `42`(「티어 표면이 강제 장치를 T1 로
떨어뜨린다」)의 실연이다. 이번엔 `build-dashboard.mjs` 동반 수정이 T2 를 끌어올렸을 뿐,
**판별식만 신설하는 PR 이었다면 T1 로 통과했다.**

## 도메인 정리

**BC — 없음.** `classify.primary_bc` 는 `null` 이고 실제로도 9개 BC 어디에도 속하지 않는다.
변경 표면은 `TODOS.md`(저장소 루트 문서) · `scripts/build-dashboard.mjs`(생성기) ·
`scripts/workflow/`(판별식) 셋뿐이고 **백엔드 0줄 · `apps/web` 0파일**이다.
도메인 엔티티·유비쿼터스 언어에 손대지 않으므로 `glossary.md` 갱신 대상도 없다.

**관련 ADR — 문서 구조를 정한 것은 0건.** `docs/decisions/` 전량에서 `TODOS.md` 를 언급하는
5건(`2026-07-27-workflow-scheme-canonical-vocabulary` · `2026-07-27-fr-co-comment-feature` ·
`2026-07-26-workflow-scheme-read-permission-gate` · `2026-07-27-fr-co-02-comment-moderation` ·
`2026-08-07-fr-ux-14-b2-card-fields`)을 실측했으나 **전부 「이 건은 TODOS.md 에 등재한다」는
개별 항목 언급**이고 파일의 구조·배열을 정한 결정은 없다. 이 PR 이 그 첫 결정이다.

**기존 결정과의 충돌 — 없음. 다만 승계하는 결정이 둘 있다.**

| 승계 | 출처 | 이 PR 에서의 의미 |
|---|---|---|
| N2 — 해소(✅)는 카테고리 분류 대상이 아니다 | 선행 계획서 NFR N2 | A안의 근거. 파일도 화면과 같은 규칙을 따른다 |
| N6 — 이동과 편집을 같은 커밋에 섞지 않는다 | 선행 계획서 NFR N6 | 커밋 분할의 근거 |

**유사 선례 — `glossary.md` 의 「배치 판별식」(2026-07-27 명문화).** 용어를 어느 섹션에 넣을지를
하드코딩 목록이 아니라 **판별식**으로 정하고 기등재 70개에 역적용해 검증한 사례다. 축은 다르지만
(용어사전 섹션 ↔ 부채 카테고리) **처방의 형태가 같다** — 이 PR 의 구조 계약도 「어느 절에 넣나」를
`AREA_CATEGORIES` 라는 기계 판별식에 맡기고 기존 104건 전량에 역적용해 검증한다.

## 스펙

### 사용자 시나리오 (Given-When-Then)

**S1. 파일을 직접 여는 사람.**
Given 개발자가 `TODOS.md` 를 열어 「지금 화면 쪽에 남은 빚이 뭐냐」를 찾는다.
When 파일이 카테고리 절로 묶여 있다.
Then `# 화면에서 보이는 것` 절 하나만 읽으면 되고, 시간순 5,220줄을 훑지 않는다.

**S2. 새 부채를 등재하는 사람.**
Given 새 항목 `## ⬜ apps/web — …` 을 적는다.
When 그것을 `# 개발 안전장치` 절 안에 넣는다(영역은 `apps/web` = 화면 카테고리인데).
Then **CI 가 red** 로 「이 항목의 영역은 화면인데 개발 안전장치 절에 있다」를 열거한다.

**S3. 재배열을 수행하는 사람 (이 PR 자신).**
Given 104개 항목을 절 사이로 옮긴다.
When 옮기는 과정에서 항목 하나의 본문 일부가 조용히 사라진다.
Then **무손실 대조가 그 항목을 제목과 함께 열거**하고, 사람이 diff 를 눈으로 판정하지 않아도 된다.

**S4. 카테고리를 늘리거나 이름을 바꾸는 사람 (미래).**
Given `CATEGORIES` 에 6번째 카테고리를 추가한다.
When `TODOS.md` 에 그 절을 만들지 않는다.
Then CI 가 red — 상수와 파일이 갈라진 채로 머지되지 않는다.

### Jira 대조

**해당 없음.** `type=chore` 이고 `apps/web` 0파일이다. 사용자 대면 화면을 만들지 않는다.
(선행 PR #389 가 만든 `docs/progress.html` 부채 페이지의 렌더는 이 PR 에서 **불변**이다 —
`renderTodos` 는 `parseTodos` 결과의 순서가 아니라 상태·카테고리로 다시 묶으므로,
파일 재배열이 화면을 바꾸지 않는다. 이것이 F4 의 완료 기준이다.)

### 기능 요구사항 (FR)

부채 추적이라 FR ID 를 발급하지 않는다. 이 계획서 자체 번호를 쓰고 출처를 병기한다.

| # | 요구사항 | 출처 |
|---|---|---|
| **F1** | `parseTodos` 가 각 항목의 **소속 H1 절 이름**을 함께 반환한다 | 신규 (F2·F3 의 전제) |
| **F2** | **무손실 대조** — 두 버전의 `TODOS.md` 텍스트를 받아 항목 `(상태, 제목, 본문)` **멀티셋의 양방향 차집합**을 열거한다. 차집합이 비어 있지 않으면 사라진 것·생긴 것을 제목과 함께 출력한다 | 선행 NFR N5 · 계획서 `:221`·`:245` 의 미이행분 |
| **F3** | **구조 계약 판별식** — ① 항목이 속한 절 이름 집합 == `CATEGORIES` 5종 이름 + `해소` (양방향 차집합 0) ② 미해결·보류 항목은 `AREA_CATEGORIES[areaOfTitle(제목)]` 이 가리키는 절 안에 있다 ③ 해소 항목은 전부 `해소` 절 ④ 어느 카테고리 절에도 안 속한 항목 0 ⑤ **모든 항목이 영역 접두를 갖는다(해소 포함)** — 매핑 존재까지는 묻지 않는다 | 신규 (S2·S4) · 축 ⑤ 는 ❓ 발견 6 |
| **F4** | **W7 재배열** — 미해결 28건을 카테고리 5절 H1 아래로, 해소 76건을 맨 뒤 `# 해소` 절로. 각 절 안의 순서는 현재 파일 순서를 보존한다 | 선행 W7 · Maxi 결정 A |
| **F5** | **W10** — 영역 접두가 없는 항목 `CREATE_PROJECT 상수 단일화` 에 접두를 부여한다 | 선행 W10 |
| **F6** | **등재 서식에 절 배치 규칙 명시** — 「영역 접두가 절을 정한다」 한 줄. **카테고리 이름을 서식에 다시 적지 않는다** — 매핑 정본 `AREA_CATEGORIES` 를 가리키기만 한다 | ❓ 발견 1 (선행 W8 의 세 겹 중 「머리 명시」가 이 규칙에는 빠져 있다) |
| **F7** | **선행 계획서에 미이행 이월 기록** — `2026-08-18-debt-dashboard-plain-language.md:221` 의 「안전망은 이 PR 이 만든다」 옆에 **미이행 → PR #390 이월**을 적는다 | ❓ 발견 2 |

### 비기능 요구사항 (NFR)

- **N1. 새 헤딩 스캐너를 만들지 않는다.** `# `·`## ` 를 읽는 코드는 `build-dashboard.mjs` 의
  `parseTodos` 하나뿐이어야 한다. 판별식·도구는 그것을 **import** 한다.
  근거 — `[[every-new-heading-scanner-forgets-code-fences]]`. #389 한 세션에서 같은 펜스 맹목을
  **다섯 번** 밟았고, 다섯 번째는 「①을 고친 바로 그 PR 에서 새로 쓴 코드」였다.
- **N2. 무손실 대조의 기대값을 재배열 후 파일에서 뽑지 않는다.** 기대는 **재배열 전 텍스트**이고,
  두 텍스트는 호출자가 각각 넘긴다. 근거 — `[[expected-value-derived-from-subject-is-a-tautology]]`.
- **N3. 개수 하한을 계약으로 쓰지 않는다.** `length >= N` 은 「비어 있지 않음」만 막고
  **방금 내린 결정 자체는 하나도 안 지킨다**. 집합·멀티셋의 정확 일치를 쓴다.
  근거 — `[[count-floor-lets-the-decision-you-just-made-rot]]`.
- **N4. 두 목록이 서로를 검사한다.** 파일의 절 배치(A)와 `CATEGORIES`/`AREA_CATEGORIES`(B)는
  각자 사람이 유지하므로 **양방향 차집합**을 강제한다. 근거 — `[[two-lists-never-check-each-other]]`.
- **N5. 이동과 편집을 다른 커밋으로 가른다.** 선행 NFR N6 승계. F4(이동)와 F5(편집)는 별도 커밋이고,
  F4 커밋의 diff 는 **순수 이동**이라 무손실 대조가 차집합 0 을 내야 한다.
- **N6. 생성기 입력·출력 계약은 불변.** `renderTodos` 결과 HTML 이 재배열 전후로 같아야 한다 —
  화면이 바뀌면 그것은 재배열이 아니라 편집이다.
  **성립 근거(추론).** `renderByCategory` 는 `items` 를 순회 순서대로 버킷에 `push` 하므로
  **버킷 안 상대 순서 = 파일 순서**다. A안이 「각 절 안의 순서는 현재 파일 순서 보존」이므로
  카테고리별 상대 순서가 유지되고 HTML 이 같아진다. **추론이므로 완료 기준 6이 실측으로 잠근다.**
- **N7. 판별식은 비-공허 짝과 합성 양성 대조군을 함께 갖는다.** 0건이라 통과하는 축은
  합성 입력으로 탐지 로직이 살아 있음을 증명한다. 이 저장소의 기존 처방 그대로.

### API 인터페이스 (REST)

**해당 없음.** 신규 엔드포인트 0 · 기존 엔드포인트 변경 0.

### 데이터 모델 변경

**해당 없음.** 마이그레이션 0 · 스키마 변경 0 · jOOQ 재생성 0.

`parseTodos` 반환 객체에 `section` 필드가 **추가**된다(기존 `status`·`title`·`body` 불변).
기존 소비처 3곳(`renderTodos` · `build-dashboard.test.mjs` · `todos-plain-language-contract.test.ts`)은
필드 추가라 무영향이고, 그 사실을 F1 의 완료 기준이 실측으로 확인한다.

### 엣지 케이스

| # | 입력 | 기대 |
|---|---|---|
| E1 | 코드펜스 안의 `# 주석` (실파일 498·796행에 현존) | 절 경계로 읽지 않는다. `parseTodos` 가 이미 방어하고 F1 이 그것을 승계한다 |
| E2 | 파일 머리 `# TODOS` 아래에 항목이 남아 있음 | F3-④ 가 red — 재배열 누락분이다 |
| E3 | 제목에 영역 접두가 없는 항목 | **실측 1건 — `CREATE_PROJECT 상수 단일화`(✅)**. ✅ 라 F3-② (미해결·보류만 봄)에 **걸리지 않는다**. → 축 ⑤ 가 이것을 잡는다. **F5 전에는 축 ⑤ 가 red 여야 정상**이고 그것이 F5 의 red-first 증거다 |
| E3b | 영역 접두는 있으나 `AREA_CATEGORIES` 에 없는 영역 | **실측 19건 — 전부 ✅**(`identity-access` · `project-workflow` · `apps/web mocks` · `apps/web(테스트 인프라)` · `로딩 프레임 계약` 등). A안에서 ✅ 는 `해소` 절로 뭉치므로 **매핑이 필요 없다**. 축 ⑤ 는 접두 **존재**만 보고 매핑 존재는 묻지 않는다 — 묻는 순간 이 19건이 전부 red 가 되고, 그것은 B안(✅ 까지 분류)을 강제하는 것이라 Maxi 결정 A 를 뒤집는다 |
| E4 | 같은 `(상태, 제목, 본문)` 항목이 2건 | 멀티셋 비교라 개수까지 본다. 집합으로 세면 1건이 사라져도 초록이 되므로 **멀티셋이 필수** |
| E5 | 보류(📌) 항목 — 실데이터 **0건** | F3-② 의 보류 축은 공허하게 통과한다. 합성 입력으로 그 축이 실제로 도는지 증명한다(N7) |
| E6 | 절 사이 산문(등재 서식 머리 등)이 재배열 중 소실 | `parseTodos` 는 항목 밖 줄을 **버리므로** F2 가 못 본다. → **F2 의 CLI 가 「항목에 속하지 않는 줄」의 멀티셋 차이도 함께 출력**하고 사람이 판정한다. 판별식이 아니라 도구의 책임임을 명시한다 |
| E7 | 카테고리에 항목이 0건인 절 | 화면은 빈 묶음을 만들지 않는다(`renderByCategory` 가 `filter`). 파일도 **절을 만들지 않는다** — F3-① 은 「항목이 속한 절 이름 집합」을 보므로 자연히 정합한다 |
| E8 | 세 번째 파서 `debt-ledger-mapping.test.ts` 의 `ledgerKeys` | `l.startsWith('## ⬜ ')` 로 **줄 순서를 보지 않고 펜스도 안 본다**(실측). 재배열은 순서만 바꾸므로 **영향 0**. 단 재배열이 코드펜스 안에 `## ⬜ ` 모양을 만들지 않는다는 것이 **암묵 가정**이며, 이 PR 은 그런 줄을 만들지 않는다 |
| E9 | F5(W10)가 제목을 바꿔 장부 키가 갈림 | `CREATE_PROJECT 상수 단일화` 는 **✅ 항목**이고 마스터 §전수 매핑에 **부재**(실측 — 마스터 전량 grep 0건). `masterDoneKeys()` 대조 대상이 아니므로 마스터 동기화 불필요. `todos-plain-language-contract` 의 영역 열 일치 단언도 계약 대상(⬜·📌)만 보므로 무관 |

### 제약 조건

- **worktree 에서 `pnpm` 금지.** `node --experimental-strip-types --test` 로 돌린다
  (`node_modules` 가 심볼릭이라 pnpm auto install 이 깨진다 — `CLAUDE.md` §함정).
- **뮤테이션 검증은 GREEN 선커밋 뒤.** 미커밋 상태에서 원복하면 소실이다
  (`[[mutation-restore-discards-uncommitted-fix-on-same-path]]` — #388 에서 실제로 밟았다).
- **`git checkout --` 로 뮤테이션을 되돌릴 때 같은 경로의 미커밋 수정을 삼킨다.** 커밋 후에만 쓴다.
- **커밋 메시지에 백틱을 쓸 때 `-m "…"` 금지.** 셸 명령치환으로 먹힌다 — `-F <파일>` 또는
  `<<'EOF'` heredoc 을 쓴다(#389 에서 커밋 2줄이 훼손됐다).
- **리뷰 렌즈를 같은 worktree 에서 동시 실행하지 않는다.** 서로의 뮤테이션이 충돌한다
  (부채 `39` · #389 에서 실연). [6] 은 순차 발행한다.

### 측정 가능한 완료 기준

1. **F1** — `parseTodos` 반환에 `section` 이 있고, 기존 소비처 3곳이 **수정 0줄**로 통과한다.
2. **F2** — 무손실 대조 함수가 합성 입력에서 ① 항목 삭제 ② 본문 1줄 삭제 ③ 제목 1글자 변경
   ④ 중복 항목 1건 제거 **네 가지를 전부 잡는다**(양성 대조군). 순수 이동에는 차집합 0.
3. **F3** — 현재 파일(재배열 전)에 대해 **red 를 먼저 본다**. 재배열 후 green.
   red 메시지가 「어느 항목이 어느 절에 있어야 하는데 어디에 있다」를 **열거**한다(개수 아님).
4. **F4** — 재배열 커밋에서 무손실 대조 CLI 가 **차집합 0** 을 출력하고, 「항목 밖 줄」 차이가
   **의도한 H1 변경 + 빈 줄뿐**임을 열거해 PR 본문에 싣는다.
5. **F5** — W10 적용 후 F3-② 의 `areaOfTitle == null` 축이 green.
6. **N6 실측** — 재배열 전후 `renderTodos` 출력 HTML 이 **바이트 단위로 같다**.
   같지 않으면 이동이 아니라 편집이 섞인 것이다.
7. **전량** — `node --experimental-strip-types --test 'scripts/**/*.test.ts' 'scripts/**/*.test.mjs'`
   전부 green · `build-doc-index --check` EXIT 0 · `verify-master-plan.sh` EXIT 0.
8. **뮤테이션** — 신설 판별식의 각 축을 하나씩 끊어 red 1회씩 실측한다. 목록은 Plan 단계에서 확정.

## Sanity Check

스펙을 스스로 흔들어 **gap 5건**을 찾았다. 전부 스스로 보강 가능해 **1회 보강**했고, Maxi 결정이
필요한 것은 없다. 보강분은 위 표에 `❓ 발견 N` 라벨로 출처를 남겼다.

**❓ 발견 1 — 새 규칙을 만들면서 그 규칙을 문서에 안 적는다.** → **F6 신설**
선행 계획서 W8 이 「앞으로 쓰는 항목이 같은 맥락으로 나오게」를 **세 겹**으로 걸었다
(① `TODOS.md` 머리 명시 ② 판별식 ③ 절차 문서 포인터). 이 PR 은 **절 배치라는 새 규칙**을
만들면서 ②만 걸고 ①을 빠뜨렸다. 그러면 S2 의 사람은 **CI red 를 보고서야** 규칙을 안다.
보강 시 **카테고리 이름을 서식에 다시 적지 않는다** — 사본은 drift 라는 W8-3 의 원칙 그대로,
매핑 정본 `AREA_CATEGORIES` 를 가리키기만 한다.

**❓ 발견 2 — 선행 계획서에 거짓 기록이 남는다.** → **F7 신설**
`2026-08-18-debt-dashboard-plain-language.md:221` 은 「후속 PR 이 쓸 안전망은 이 PR 이 만든다」고
적은 채다. 고치지 않으면 **다음 사람이 또 있다고 믿는다** — 이 PR 이 그것을 믿고 착수했다가
실측으로 반증한 것이 바로 이번 발견이다. 같은 함정을 **세 번째로 파지 않으려면** 그 자리에
미이행 사실을 적어야 한다.

**❓ 발견 3 — 항목 `25` 종결이 범위에서 빠졌다.** → **의도적 범위 밖으로 명시**
2026-08-18 체크포인트의 남은 일 3번은 「W7 · W10 · **항목 `25` 종결**」이었다. `25`
(worktree `node_modules` 링크 절차 부재)는 **「절차가 이미 있다」가 맞는지의 별개 판단**이
필요하고 재배열과 무관한 내용 편집이다. 이 PR 은 그 항목을 `# 빌드·배포 환경` 절로 **옮기기만**
한다. N5(이동/편집 분리)의 정신에도 맞는다.

**❓ 발견 4 — 세 번째 파서의 영향을 확인하지 않았다.** → **실측 후 E8 추가**
`TODOS.md` 를 읽는 파서는 셋이다. `parseTodos`·`todos-resolved-section-purity` 는 확인했으나
`debt-ledger-mapping.test.ts` 를 안 봤다. 실측 결과 `ledgerKeys` 는 `## ⬜ ` 접두 줄만 필터해
**제목 키의 멀티셋**을 만든다 — **줄 순서를 보지 않으므로 재배열 영향 0**이다.
`ledgerOpenKeys().length >= 10` 하한(`:154`)도 파서 생존 확인용이라 재배열이 깨지 않는다
(⬜ 28건 불변).

**❓ 발견 5 — N6(HTML 바이트 동일)이 성립한다는 근거가 없었다.** → **근거 보강 + 실측 잠금**
「재배열해도 화면이 안 바뀐다」는 당연해 보이지만 `renderByCategory` 가 순회 순서로 버킷을
채우므로 **성립 근거가 필요하다**. 근거를 NFR N6 에 적었고, 그것이 추론임을 명시한 뒤
완료 기준 6이 실측으로 잠근다.

**❓ 발견 6 (Plan 단계 실측이 스펙을 반증했다) — E3 가 틀렸다.** → **F3 에 축 ⑤ 신설 · E3 교체 · E3b 추가**
스펙은 「영역 접두 없는 항목 → F3-② 가 red → 그것이 F5 의 red-first 증거」라고 적었다.
Plan 단계에서 실파일을 재어 보니 **접두 없는 항목은 1건이고 그것이 `✅` 상태**다
(`CREATE_PROJECT 상수 단일화`). F3-② 는 미해결·보류만 보므로 **이 항목을 아예 안 본다** —
즉 **F5 에 red-first 축이 없었다.** 축 ⑤ 「모든 항목이 영역 접두를 갖는다」를 신설해 메운다.

같은 측정이 **A안의 정당성도 뒷받침했다.** ✅ 76건 중 **19건**이 `AREA_CATEGORIES` 7키에 없는
영역을 쓴다(`identity-access` · `apps/web mocks` · `apps/web(테스트 인프라)` · `로딩 프레임 계약` …).
B안(✅ 까지 카테고리 분류)을 골랐다면 **매핑 19종을 새로 판정**해야 했다. 결정 당시엔 추정이었고
지금 실측이 확인했다.

**✅ 통과 (gap 6건 전부 보강 · Maxi 결정 필요 0건 · FR 5 → 7개 · F3 축 4 → 5개)**


## Plan

구현은 **인라인** 판정을 게이트 1에서 확정한다. `agent` 키는 승계용으로만 적는다.
새 판별식은 목적별로 **2파일**로 나눈다 — 무손실 대조(F2)는 *도구*라 CLI 진입점을 갖고,
구조 계약(F3)은 *영속 판별식*이라 CI 에서 계속 돈다. 둘 다 `parseTodos` 를 **import** 한다(N1).

### Task 1. `parseTodos` 가 각 항목의 소속 절 이름을 함께 반환한다 (F1)

**메타**.
- agent: (인라인)
- files: [`scripts/build-dashboard.mjs`, `scripts/build-dashboard.test.mjs`]
- depends-on: []

**RED**:
- 파일: `scripts/build-dashboard.test.mjs`
- 테스트 3종:
  ```js
  test('parseTodos — 각 항목이 소속 H1 절 이름을 갖는다', ...)
  test('parseTodos — 펜스 안의 `# ` 는 절 이름을 바꾸지 않는다 (E1 승계)', ...)
  test('parseTodos — H1 앞의 항목은 section 이 null 이다 (음성 대조군)', ...)
  ```
- 실패 메시지 (예상): `undefined` — 반환 객체에 `section` 키 없음

**GREEN**:
- 파일: `scripts/build-dashboard.mjs`
- `parseTodos` 루프에 `section` 변수 추가. **펜스 게이트는 이미 있는 `isFenceLine` 을 그대로
  탄다** — 새 판정을 만들지 않는다(N1).

**REFACTOR**:
- 기존 소비처 3곳(`renderTodos` · `build-dashboard.test.mjs` · `todos-plain-language-contract.test.ts`)이
  **수정 0줄**로 통과함을 실측(완료 기준 1). 필드 추가라 무영향이어야 한다.

**검증**: `node --experimental-strip-types --test scripts/build-dashboard.test.mjs`

### Task 2. 무손실 대조 — 두 텍스트의 항목 멀티셋 양방향 차집합 (F2)

**메타**.
- agent: (인라인)
- files: [`scripts/workflow/todos-reorder-integrity.mjs`, `scripts/workflow/todos-reorder-integrity.test.ts`]
- depends-on: [1]

**RED**:
- 파일: `scripts/workflow/todos-reorder-integrity.test.ts`
- **양성 대조군 4종** (완료 기준 2) — 합성 입력에서 전부 잡혀야 한다:
  ```
  ① 항목 1건 통째 삭제   ② 본문 1줄 삭제
  ③ 제목 1글자 변경      ④ 같은 항목 2건 중 1건 제거 (멀티셋 축)
  ```
- **음성 대조군 1종** — 순수 이동(순서만 바꿈)에 차집합 0.
- **비-공허 짝** — 합성 입력에서 항목을 실제로 수집했는지 하한 단언(파서 생존 확인. 여기는
  하한이 계약인 자리다 — N3 의 예외 조건 그대로).
- 실패 메시지 (예상): `compareTodoIntegrity` 없음

**GREEN**:
- 파일: `scripts/workflow/todos-reorder-integrity.mjs`
- `parseTodos` 를 import 해 `(status, title, body)` fingerprint 를 만들고 **멀티셋** 양방향 차집합.
  **집합이 아니라 멀티셋**이다 — 집합으로 세면 중복 항목 1건이 사라져도 초록이다(E4).
- **기대값은 호출자가 넘긴 `before` 텍스트**다. 대상 파일에서 파생시키지 않는다(N2).

**REFACTOR**:
- CLI 진입점 추가 — `node scripts/workflow/todos-reorder-integrity.mjs <before-file> <after-file>`.
- **「항목에 속하지 않는 줄」의 멀티셋 차이도 함께 출력**한다(E6). `parseTodos` 는 항목 밖 줄을
  버리므로 판별식만으로는 머리 산문 소실이 초록이다 — 그 축은 **도구가 열거하고 사람이 판정**한다.

**검증**: `node --experimental-strip-types --test scripts/workflow/todos-reorder-integrity.test.ts`

### Task 3. 구조 계약 판별식 5축 — 재배열 전에 red 를 본다 (F3)

**메타**.
- agent: (인라인)
- files: [`scripts/workflow/todos-structure-contract.test.ts`]
- depends-on: [1]

**RED**:
- 파일: `scripts/workflow/todos-structure-contract.test.ts`
- **이 task 의 RED 는 현재 실파일에 대한 red 다.** 카테고리 절이 아직 없으므로 축 ①②③④ 가 전부
  실패해야 한다 — 그것이 **이 PR 의 red→green 축**이고, Task 4 가 green 으로 만든다.
- 5축:
  ```
  ① 항목이 속한 절 이름 집합 == CATEGORIES 5종 + '해소'   (양방향 차집합 0)
  ② 미해결·보류는 AREA_CATEGORIES[areaOfTitle(제목)] 절 안 (열거형 메시지)
  ③ 해소는 전부 '해소' 절
  ④ 어느 카테고리 절에도 안 속한 항목 0 (= section 이 'TODOS' 이거나 null 인 항목 0)
  ⑤ 모든 항목이 영역 접두를 갖는다 (해소 포함 · 매핑 존재는 묻지 않는다)
  ```
- **비-공허 짝** — 항목 수집 하한 + 「계약 대상이 실제로 있다」.
- **합성 양성 대조군** — 각 축마다 위반 입력을 만들어 실제로 잡히는지(N7).
- 실패 메시지는 **열거형**이어야 한다 — 「N건 불일치」가 아니라 **어느 항목이 어느 절에 있어야
  하는데 어디에 있다**를 항목별로 적는다(완료 기준 3 · 저장소 함정 「지시문에 개수를 쓰지 마라」).

**GREEN**:
- **이 task 에는 GREEN 이 없다.** Task 4(재배열)가 green 을 만든다. 축 ⑤ 만 Task 5 가 만든다.
  판별식을 먼저 커밋하고 red 를 실측 기록하는 것이 이 task 의 산출물이다.

**REFACTOR**:
- red 메시지를 실제로 읽어 「이걸 보고 재배열할 수 있나」를 확인한다. 못 하면 메시지를 고친다.

**검증**: `node --experimental-strip-types --test scripts/workflow/todos-structure-contract.test.ts` — **red 기대**

### Task 4. W7 재배열 — 이동만, 편집 0 (F4)

**메타**.
- agent: (인라인)
- files: [`TODOS.md`]
- depends-on: [2, 3]

**RED**: Task 3 이 이미 red 다. 새 테스트를 쓰지 않는다.

**GREEN**:
- 파일: `TODOS.md`
- 미착수 28건을 카테고리 5절 H1 아래로. **절 순서는 `CATEGORIES` 선언 순서**
  (화면에서 보이는 것 → 기능 동작 → 개발 안전장치 → 빌드·배포 환경 → 문서·규칙).
- 해소 76건을 맨 뒤 `# 해소` 절로, **현재 파일 순서 그대로**.
- 실측 분포 — 화면 7 · 기능 4 · 안전장치 10 · 빌드배포 4 · 문서 3 = **28**.
- 기존 H1 `# 2026-08-09 전수 실측에서 새로 드러난 항목` 은 **소실**된다(Maxi 결정 A 의 알려진 대가.
  항목별 날짜는 제목에 남는다).
- **각 절 안의 상대 순서는 현재 파일 순서를 보존**한다 — N6(HTML 바이트 동일)의 성립 조건이다.
- **본문은 한 글자도 고치지 않는다**(N5). 고칠 것이 보이면 Task 5 이후로 미룬다.

**REFACTOR**:
- **무손실 대조 CLI 실행** — `git show HEAD:TODOS.md` 를 before 로, 현재를 after 로.
  차집합 **0** 이어야 한다. 「항목 밖 줄」 차이는 **의도한 H1 변경 + 빈 줄뿐**임을 눈으로 확인하고
  그 출력을 PR 본문에 싣는다(완료 기준 4).
- **N6 실측** — 재배열 전후 `renderTodos` 출력 HTML 이 **바이트 단위로 같은지** 확인한다.
  다르면 이동이 아니라 편집이 섞인 것이다(완료 기준 6).

**검증**: Task 3 판별식 green · 무손실 대조 차집합 0 · `renderTodos` HTML 동일 · 전량 테스트

### Task 5. W10 — 영역 접두 부여 (F5 · 별도 커밋)

**메타**.
- agent: (인라인)
- files: [`TODOS.md`]
- depends-on: [3]

**RED**: Task 3 의 **축 ⑤** 가 red 다 — `CREATE_PROJECT 상수 단일화` 1건(실측).
Task 4 를 마쳐도 이 축만 red 로 남는 것이 **F5 의 red-first 증거**다.

**GREEN**:
- 파일: `TODOS.md`
- `## ✅ CREATE_PROJECT 상수 단일화 (해소 2026-07-27)` 에 영역 접두를 붙인다.
  본문 실측 — `GlobalPermissionGrantService.kt` · `shared-kernel GlobalPermissionCodes` ·
  `WhoamiController` 가 대상이므로 영역은 **`identity-access`** 가 맞다(이미 ✅ 19건이 쓰는 접두).
- **N5 — Task 4 와 반드시 다른 커밋.** 이동과 편집이 섞이면 diff 로 판정할 수 없다.

**REFACTOR**:
- 장부 대조 영향 재확인 — 이 항목은 마스터 §전수 매핑에 **부재**(실측)라 `masterDoneKeys()` 대조
  대상이 아니다(E9). `debt-ledger-mapping` 전량 green 을 실측한다.

**검증**: Task 3 축 ⑤ green · `debt-ledger-mapping.test.ts` green

### Task 6. 등재 서식에 절 배치 규칙 명시 (F6)

**메타**.
- agent: (인라인)
- files: [`TODOS.md`, `scripts/workflow/todos-plain-language-contract.test.ts`]
- depends-on: [4]

**RED**:
- 파일: `scripts/workflow/todos-plain-language-contract.test.ts`
- 기존 단언 「`TODOS.md` 머리에 등재 서식이 적혀 있다」(`:312`)를 확장 — **절 배치 규칙 문장**이
  머리에 있는지 단언한다.
- 실패 메시지 (예상): 머리에 절 배치 규칙 문장 없음

**GREEN**:
- 파일: `TODOS.md` 머리 등재 서식
- 한 줄 추가 — 「항목은 **영역 접두가 가리키는 카테고리 절** 안에 둔다」.
  **★카테고리 이름 5종을 여기 다시 적지 않는다** — 매핑 정본은 `AREA_CATEGORIES` 이고 사본은
  drift 다(선행 W8-3 원칙 승계). 파일 경로를 가리키기만 한다.

**REFACTOR**: 문체 「~다」체 유지(선행 Maxi 결정 ②).

**검증**: `node --experimental-strip-types --test scripts/workflow/todos-plain-language-contract.test.ts`

### Task 7. 선행 계획서에 미이행 이월 기록 (F7)

**메타**.
- agent: (인라인)
- files: [`docs/plans/2026-08-18-debt-dashboard-plain-language.md`]
- depends-on: []

**RED**: **없다 — 문서 서술이라 기계 판정 대상이 아니다.**
판별식을 만들려면 「계획서가 약속한 산출물이 실재하는가」를 일반화해야 하는데, 그것은
부채 항목 `4467`(살아 있는 정본이 `docs/plans/` 안에 있어 판별식 사각)의 소관이고
**이 PR 의 범위 확장**이다. 그 사실을 여기 명시하는 것으로 갈음한다.

**GREEN**:
- 파일: `docs/plans/2026-08-18-debt-dashboard-plain-language.md`
- `:221`(Maxi 결정 ③ 표) · `:245`(근거 ㄱ) · **`:630` Implementation Tasks 이행 상태** 세 곳에
  **「미이행 → PR #390 이월」**을 적는다. 세 곳인 이유 — 약속이 두 곳에 적혔고 이행 목록에는
  항목 자체가 없어서, 하나만 고치면 나머지가 여전히 거짓을 말한다.

**REFACTOR**: 없음.

**검증**: `node scripts/build-doc-index.mjs --check` EXIT 0

## Plan 메타

- **task 수 7** · **예상 wave 5** (`files` 겹침으로 4→5→6 이 `TODOS.md` 에서 자동 직렬화)
  - wave 1 — Task 1, Task 7 (독립)
  - wave 2 — Task 2, Task 3
  - wave 3 — Task 4 · wave 4 — Task 5 · wave 5 — Task 6
- **구현 규율** — TDD red-first. **이 PR 의 red→green 축은 Task 3 → Task 4** 다.
  Task 3 을 먼저 커밋해 red 를 실측 기록하고, Task 4 가 green 으로 만든다.
- **커밋 분할 (N5)** — Task 4(이동)와 Task 5(편집)는 **반드시 다른 커밋**.
- **추가 검증** — `node scripts/build-doc-index.mjs --check` · `bash scripts/verify-master-plan.sh` ·
  전량 `node --experimental-strip-types --test 'scripts/**/*.test.ts' 'scripts/**/*.test.mjs'`.
  **worktree 에서 `pnpm` 금지**(node_modules 심볼릭).

### 뮤테이션 목록 확정 (완료 기준 8)

스펙이 Plan 단계로 미뤄 둔 항목이다. **GREEN 선커밋 뒤**에만 돌린다 — 미커밋 상태에서
`git checkout --` 로 원복하면 같은 경로의 수정을 삼킨다(`[[mutation-restore-discards-uncommitted-fix-on-same-path]]`).

| # | 대상 | 끊는 것 | red 가 되어야 하는 단언 |
|---|---|---|---|
| M1 | F1 | `section` 대입 삭제 | Task1 「소속 절 이름을 갖는다」 |
| M2 | F1 | 펜스 게이트 삭제 | Task1 「펜스 안 `# ` 는 절을 안 바꾼다」 |
| M3 | F2 | 멀티셋 → 집합 (중복 접기) | 양성 대조군 ④ 중복 1건 제거 |
| M4 | F2 | 양방향 → 한 방향 | 「생긴 것」 축 |
| M5 | F2 | fingerprint 에서 본문 제외 | 양성 대조군 ② 본문 1줄 삭제 |
| M6 | F3 | 축① 양방향 → 한 방향 | 「CATEGORIES 에 있는데 파일에 절 없음」 |
| M7 | F3 | `AREA_CATEGORIES` 한 값 변경 | 축② 배치 불일치 (★이 축이 동어반복이 아님의 증명) |
| M8 | F3 | 축③ 판정 삭제 | 「해소가 해소 절에」 |
| M9 | F3 | 축④ 판정 삭제 | 「절 밖 항목 0」 |
| M10 | F3 | 축⑤ 판정 삭제 | 「모든 항목이 영역 접두」 |
| M11 | F3 | 항목 수집을 0건으로 | 비-공허 짝 |
| M12 | F6 | 등재 서식의 절 배치 문장 삭제 | Task6 머리 단언 |

**★M7 이 이 목록의 핵심이다.** 축②의 기대값을 `AREA_CATEGORIES` 에서 읽는데, 그것이
**동어반복인지 아닌지**를 가르는 것이 M7 이다. 파일(actual)과 상수(expected)가 서로 다른 출처라
red 가 나야 한다 — green 이면 `[[expected-value-derived-from-subject-is-a-tautology]]` 를
#389 에 이어 두 번째로 밟은 것이다.


## 리뷰 결과 (← /bts-review-plan 채움)
