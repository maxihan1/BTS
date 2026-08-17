# classify-task 의 type 판정이 실제 신호 대신 우연한 문자열에 반응한다

> 티어: T2
> slug: classify-task-misroute-3-44-33-45
> type: chore
> agent: backend-engineer
> 생성: 2026-08-17
> 부채. TODOS.md 매핑 `44`(경로 뒤 슬래시 누락 → backend 기본값) · `33`(E2E 혼합 PR → qa-engineer) · **신규 `45`**(ASCII 키워드가 다른 영단어 안의 부분문자열에 매치)

## Brief

**사용자 원문.** 「부채 44+33 - classify-task 오배정 2종」 — `/context-restore` 로 복원한 남은 일 1번
(부채 ⬜ 23건)에서 `docs/plans/2026-08-12-debt24-master.md` §PR 별 집계의 다음 묶음 후보 중
**`44`+`33`**(같은 파일 `classify-task.ts` · 충돌면 동일)을 지목.

**착수 중 범위가 1건 늘었다 (Maxi 확정 · D1).** baseline 실측 중 **미등재 결함 1건**을 확인해
신규 `45` 로 등재하고 같은 PR 에 넣기로 했다. 근거는 ① 같은 파일 ② 같은 뿌리 — `44` 는 진짜 신호를
**놓치는** 방향, `45` 는 신호가 아닌 것을 **신호로 읽는** 방향이라 한쪽만 고치면 반대 방향 문이 열린 채
남는다 ③ `45` 의 파급이 셋 중 가장 크다(보안 에이전트로 오배정).

### 선행 실측 (2026-08-16/17 · 착수 시점 baseline)

**결함 `44` — 경로 뒤 슬래시.** `classify-task.ts:68` 의 `UI_PATH_PATTERNS` 가 `/apps\/web\//`.

| 입력 제목 | type | agent |
|---|---|---|
| `apps/web 판별식 정리` | `backend` | `backend-engineer` |
| `apps/web/ 판별식 정리` | **`ui`** | **`frontend-engineer`** |

**★항목 `44` 가 「착수 시 실측하라」고 남긴 오탐 후보를 실측했다.** 현재 상태에서
`apps/web-legacy` → `backend` · `apps/webhook` → `backend`. 처방 후보 ①(`/apps\/web\b/`)을 그대로 쓰면
`apps/web-legacy` 는 **`\b` 가 성립해 `ui` 로 걸린다**(`b` 다음 `-` 는 단어 경계). `apps/webhook` 은
안 걸린다(`b` 다음 `h` 는 경계 아님). 즉 **후보 ① 은 하이픈 형태에 오탐을 만든다** — 스펙에서 확정한다.

**결함 `33` — 판정 순서.** `detectType` 의 qa(`:298`)가 ui(`:304`)보다 앞선다.
이 PR 의 착수 제목 자체가 걸렸다 — 「… E2E 혼합 …」 → `type=qa` · `agent=qa-engineer`.
`.claude/agents/qa-engineer.md` 는 구현 코드 수정을 금지하므로 **손을 못 쓰는 에이전트로 간다.**

**신규 `45` — ASCII 키워드 부분문자열.** `includesWithBoundary`(`:251`)는 **한글 키워드의 앞 경계만**
본다. ASCII 키워드는 맨 substring 매치라 다른 영단어 안에 묻혀도 걸린다.

| 입력 | 걸린 키워드 | type | agent |
|---|---|---|---|
| `dispatch 로직 정리` | `pat` (AUTH) | `auth` | **`security-engineer`** |
| `patch 파일 적용` | `pat` | `auth` | **`security-engineer`** |
| `path 계산 수정` | `pat` | `auth` | **`security-engineer`** |
| `build 스크립트 정리` | `ui` (UI) | `ui` | `frontend-engineer` |
| `guide 문서 갱신` | `ui` | `ui` | `frontend-engineer` |
| `requirement 정리` | `ui` | `ui` | `frontend-engineer` |
| `restore 절차 문서화` | `rest` (API) | `api` | `backend-engineer` |
| `restrict 규칙 추가` | `rest` | `api` | `backend-engineer` |
| `rapid 프로토타입` | `api` | `api` | `backend-engineer` |

**대조군(원인 확정).** `빌드 스크립트 정리` → `backend` · `경로 계산 수정` → `backend`.
한글로 같은 뜻을 쓰면 정상이므로 원인은 **ASCII 부분문자열**이 맞다.

**★이 결함은 「측정하지 않은 안전 속성을 단정한 문장」이 낳았다.** 2026-07-27 해소 기록
(`TODOS.md:1172`)이 경계 규칙을 넣으면서 **「ASCII 키워드는 무영향」**이라 적었고, 소스 주석
(`:248-249`)도 「영문에 한글 접두사가 붙는 형태는 이 도메인에 없다」로 같은 주장을 한다.
그 문장은 **참이지만 무관하다** — ASCII 키워드의 실제 위험은 한글 접두사가 아니라 **다른 ASCII 단어**다.
메모리 `[[safety-claim-asserted-but-never-measured]]` 와 같은 양식이고, 5연속 뒤 6번째다.

**★결함이 이 세션에서 4회 자기 실연했다.** 착수 제목 3형태 + 최종 제목까지 전부 오배정됐다
(`E2E`→qa · `dispatch`→auth · `misroute` 안의 `route`→api). 재현 비용이 0인 결함이다.

### classify 결과와 선언의 불일치 (의도적 오버라이드 · 게이트 2 요약에 재기재)

| | 분류기 출력 | 이 PR 의 선언 | 사유 |
|---|---|---|---|
| type | `api` | **`chore`** | 최종 제목의 `misroute` 안 `route` 가 API 키워드에 걸렸다 — **고치려는 결함 `45` 본인**이다. 실제 성격은 하네스 도구 정비이므로 `chore`(저장소 선례 `chore/node-ts-invocation-flag-guard` 등) |
| agent | `backend-engineer` | **`backend-engineer`**(유지) | 결과값은 같지만 **경로가 우연**이다. `scripts/workflow/` 를 책임지는 에이전트가 정의돼 있지 않아 `chore`→`backend-engineer` 매핑을 쓴다. 이 공백 자체는 이 PR 범위 밖 |
| tier | `T1`(기본값) | **`T2`** | `detect-tier` 실측 = `T2` · `SURFACES: TEST, GUARD_CI`. `surfaces.ts` 의 `GUARD_CI` 가 `scripts/workflow/*.{ts,mjs}` 를 잡는다 |

**★`TODOS.md` 가 항목 `33`·`44` 를 둘 다 `(… · T1)` 로 적어 놓았는데 실측은 T2 다.**
장부 표기가 표면 정본과 어긋난 것이므로 이 PR 에서 함께 정정한다.

## 도메인 정리

**BC. 없음(`primary_bc = null`).** 제품 도메인이 아니라 **개발 하네스**다 — 9 BC 어느 것도 건드리지
않는다. `detectBoundedContext` 도 `chore` 를 BC 무관으로 처리한다. 영향 엔티티 0 · 신규 용어 0 ·
`glossary.md` 갱신 대상 0.

**관련 ADR — 3건.** 새 결정을 만들지 않고 **기존 ADR 이 손으로 덮던 것을 기계로 옮긴다.**

| ADR | 무엇을 기록했나 |
|---|---|
| `2026-07-28-fr-ux-07-active-project-context.md` §D2 | 「`classify-task.ts` 는 이 작업을 `type=backend`/`primary_bc=issue-tracking` 으로 **오분류**했다(**같은 오분류 3회째**). 변경 파일이 전량 `apps/web`, `backend/` 0건임을 실측해 정정했다」 |
| `2026-07-30-fr-ux-08-project-switcher.md` §D6 | 같은 오분류를 다시 기록. 정정 사유를 `.bts-cache/classify.fr-ux-08-project-switcher.json` 에 남겼다 |
| `2026-06-23-fr-bl-01-lexorank-backlog-ordering.md` | classify 의 `primary_bc` 판정이 BC 경계와 어긋나는 사례 |

**★결함 `44` 는 등재(2026-08-16)보다 **최소 7주 앞서** 사람을 괴롭히고 있었다.** ADR 이 「3회째」라고
적은 시점이 2026-07-28 이다. 매번 사람이 알아채고 손으로 덮었고, **그 오버라이드를 강제하는 것이
없었다.** 이 PR 은 새 정책을 세우는 게 아니라 **이미 3~5회 내려진 같은 판단을 코드로 고정**한다.

**기존 결정과의 충돌. 없음.** 위 ADR 들은 전부 「분류기가 틀렸으니 손으로 정정한다」는 방향이고
이 PR 은 그 정정을 불필요하게 만든다 — 뒤집는 것이 아니라 승계다.

## 스펙

### 사용자 시나리오 (Given-When-Then)

**S1 (결함 `44`).**
Given `/bts` 사용자가 `apps/web` 전용 작업을 자연어로 지시하고 경로를 **뒤 슬래시 없이** 적었을 때
When `classify-task` 가 `type`·`agent` 를 정하면
Then `type=ui` · `agent=frontend-engineer` 여야 한다. (현행 — `backend`/`backend-engineer`)

**S2 (결함 `33`).**
Given 제목에 「E2E」와 UI 신호가 **함께** 있는 혼합 작업일 때
When 분류기가 `agent` 를 정하면
Then **구현 코드를 만질 수 있는** 에이전트여야 한다. (현행 — `qa-engineer`, 구현 코드 수정 금지)

**S3 (결함 `33` 역방향 · 회귀 방지).**
Given 제목이 **순수 E2E/테스트 작업**이고 UI·API 신호가 없을 때
When 분류기가 `agent` 를 정하면
Then `qa-engineer` 여야 한다. (좁히다가 qa 자체를 없애면 안 된다)

**S4 (결함 `45`).**
Given 제목에 `dispatch`·`patch`·`path`·`build`·`guide`·`restore` 처럼 **키워드를 부분문자열로 품은
평범한 영단어**가 있고 실제 도메인 신호는 없을 때
When 분류기가 `type` 을 정하면
Then 그 우연 일치는 신호로 치지 않는다. (현행 — `auth`/`ui`/`api` 로 오배정)

**S5 (결함 `45` 역방향 · 회귀 방지).**
Given 제목에 `SAML`·`OAuth2`·`2FA`·`E2E` 처럼 **진짜 키워드가 단독으로** 있을 때
When 분류기가 `type` 을 정하면
Then 종전과 같은 판정을 유지한다.

### Jira 대조

**해당 없음.** 사용자 화면이 없는 개발 하네스다(`type=chore`). `jira-parity-contract.md` 는 진입하지 않는다.

### 기능 요구사항 (FR)

**신규 FR 0건 · FR 총수 139 불변.** 제품 기능이 아니라 부채 상환이므로 `fr-index` 를 건드리지 않는다.
추적 단위는 부채 매핑 `44`·`33`·`45`.

| ID | 요구 | 대상 |
|---|---|---|
| **R1** | 경로 신호 `apps/web` 은 **뒤 슬래시 없이도** 인식한다 | `UI_PATH_PATTERNS` |
| **R2** | 단, `apps/webhook`·`apps/web-legacy` 처럼 **더 긴 이름**은 인식하지 않는다 | 〃 |
| **R3** | `qa` 는 **다른 타입 신호가 전혀 없을 때만** 고른다 | `detectType` 판정 순서 |
| **R4** | 짧은 ASCII 키워드는 **단어 경계**를 지킬 때만 매치한다 | `includesWithBoundary` |
| **R5** | 긴 ASCII 키워드(형태 변화가 잦은 것)는 종전 부분일치를 유지한다 | 〃 |
| **R6** | 위 4종이 **회귀하면 판별식이 red** 가 된다 | `classify-task.test.ts` |
| **R7** | 장부(`TODOS.md` · debt24-master)가 `33`·`44` 를 ✅ 로, `45` 를 ⬜ 로 **양쪽 다** 반영한다 | 장부 2파일 |
| **R8** | 장부의 티어 표기 `33`·`44` 를 `T1` → `T2` 로 정정한다 | `TODOS.md` |

### 비기능 요구사항 (NFR)

- **N1. 기존 동결값을 흔들지 않는다.** `bc-keyword-coverage.test.ts` 의 `MAX_MISMATCHES = 57` 이
  현재 실측과 **정확히 같아** 여유가 0이다. 상한(`≤ 57`)과 하한(`≥ 53`)을 **둘 다** 지켜야 한다.
- **N2. 프로덕션 코드 0줄.** 변경은 `scripts/workflow/**` 와 문서뿐이다. 백엔드·`apps/web` 0파일.
- **N3. 판정 비용은 O(제목 길이).** 경계 검사는 인접 문자 2개만 본다 — 정규식 백트래킹 없음.

### API 인터페이스 (REST)

**변경 없음.** 이 PR 은 HTTP 표면을 갖지 않는다.

### 데이터 모델 변경

**변경 없음.** 마이그레이션 0 · jOOQ 재생성 0 · DB 접근 0.

### 처방 확정 (착수 실측 기반)

**① `44` — 경로 패턴.** `/apps\/web\//` → **`/apps\/web(?![\w-])/`**.
항목 `44` 가 「착수 시 실측하라」고 남긴 후보 ①(`/apps\/web\b/`)은 **기각**한다 — `\b` 는
`apps/web-legacy` 에서 성립해 오탐을 만든다(실측). 부정 전방탐색으로 **단어 문자와 하이픈 둘 다**
막으면 `apps/webhook`·`apps/web-legacy` 는 안 걸리고 `apps/web`·`apps/web/` 만 걸린다.

**② `33` — 판정 순서.** `qa` 를 `api`·`ui` **뒤로** 내린다(5→7번).
후보 ②(`/bts-impl` 이 `files` 로 재판정)는 **기각** — 착수 시점에 변경 경로가 0건이라 분류기
호출 시점 자체를 바꿔야 하고, 그건 이 PR 의 한 문장을 벗어난다.

> **왜 「좁히기」가 아니라 「순서」인가 — 오류의 비용이 비대칭이다.**
> `qa-engineer` 는 구현 코드 수정이 **금지**돼 있어 잘못 가면 **복구 불가**(작업을 못 한다).
> 반대로 `frontend-engineer` 가 E2E 를 쓰는 것은 금지돼 있지 않아 **복구 가능**하다.
> 그래서 애매하면 **복구 가능한 쪽으로 기운다.** S3 이 반대 방향 회귀를 막는다.

**③ `45` — ASCII 단어 경계.** `includesWithBoundary` 가 한글 키워드만 보던 경계 검사를
**짧은 ASCII 키워드까지** 확장한다. 길이 `> ASCII_BOUNDARY_MAXLEN(4)` 인 키워드는 종전 부분일치 유지.

**★상수 4 는 임의값이 아니라 실측으로 고른 구간의 값이다.**

| 임계 | baseline 불일치 | 오배정 표본 교정 | 판정 |
|---|---|---|---|
| 2 | 57 → **57** | 3/9 | 부족 |
| 3 | 57 → **57** | 7/9 | `rest`(4자)를 못 잡음 |
| **4** | 57 → **57** | **8/9** | **채택** |
| 5 | 57 → **57** | 8/9 | 4와 동일 결과 — 경계값이 칼날이 아님 |
| ∞(전량 경계) | 57 → **59** ❌ | 8/9 | `authentication`⊂`AuthenticationProvider` · `watcher`⊂`Watchers` 를 잃어 **동결값 초과** |

교정 안 된 1건은 `restrict 규칙 추가` → `feature` 인데 **오배정이 아니다**(「추가」가 FEATURE 트리거).

경계 문자류는 **`[a-z]` 만**(숫자 제외)이다. `[a-z0-9]` 로 잡으면 `saml2`·`oauth2` 가 깨진다(실측).

### 엣지 케이스

| # | 입력 | 기대 | 근거 |
|---|---|---|---|
| E1 | `apps/webhook 재시도` | `ui` 아님 | R2 |
| E2 | `apps/web-legacy 정리` | `ui` 아님 | R2 · `\b` 기각 사유 |
| E3 | `E2E 시나리오만 추가` | `qa` 유지 | S3 |
| E4 | `Playwright 회귀 보강` | `qa` 유지 | S3 |
| E5 | `saml2 설정` · `oauth2 로그인` | `auth` 유지 | 경계 문자류에서 숫자 제외 |
| E6 | `2FA 백업코드` | `auth` 유지 | 키워드가 숫자로 시작 |
| E7 | `API-03 웹훅` | `api` 유지 | 하이픈은 경계 |
| E8 | `플러그형 AuthenticationProvider 구조` | BC `identity-access` 유지 | R5 — 길이 예외가 없으면 N1 위반 |
| E9 | `담당자 (Reporter / Assignee / Watchers)` | BC `issue-tracking` 유지 | R5 — 복수형 `Watchers` |
| E10 | 한글 전용 제목(`빌드 스크립트 정리`) | 종전과 동일 | 한글 경계 규칙 무변경 |

### 제약 조건

- **C1. 장부 2파일 동시 갱신은 선택이 아니다.** `debt-ledger-mapping.test.ts` 가 `TODOS.md` 의 `## ⬜`
  집합과 `docs/plans/2026-08-12-debt24-master.md` §전수 매핑의 ⬜ 집합을 **양방향 차집합 0** 으로
  강제한다. `45` 를 한쪽에만 적으면 red 다. ✅ 행은 `#NNN` 을, ⬜ 행은 `미배정`·`보류` 만 허용한다.
- **C2. 표면 정본을 복사하지 않는다.** 티어 근거는 `surfaces.ts` 의 `GUARD_CI` 다. 글로브를 문서에
  다시 적으면 `[[two-lists-never-check-each-other]]` 를 새로 만든다 — **표면 이름만** 적는다.
- **C3. 뮤테이션 검증은 GREEN 선커밋 뒤.** 미커밋 상태에서 되돌리면 소실이다.
- **C4. worktree 에서 `pnpm` 금지.** 심볼릭 `node_modules` 때문에 죽는다 —
  `node --experimental-strip-types --test 'scripts/**/*.test.ts' 'scripts/**/*.test.mjs'` 로 직접 부른다.

### 측정 가능한 완료 기준

1. `classify-task.test.ts` 에 S1~S5 · E1~E10 대응 단언이 있고 **각각 red 를 먼저 봤다.**
2. 워크플로우 판별식 **308 → (신규분 포함) 전량 pass · EXIT=0**. 착수 baseline 은 308/308.
3. `bc-keyword-coverage.test.ts` 가 **상한·하한 둘 다 통과**(불일치 57 유지 · `MAX_MISMATCHES` 무변경).
4. **뮤테이션 4종이 설계대로 RED** — ① 경로 부정 전방탐색 제거 ② qa 순서 원복
   ③ `ASCII_BOUNDARY_MAXLEN` 을 99 로 ④ 장부에서 `45` 행 1개 삭제.
5. `TODOS.md` 와 debt24-master 가 `33`·`44` = ✅ `#387` · `45` = ⬜ `미배정` 로 **양쪽 일치**.
6. `node scripts/build-doc-index.mjs --check` · `bash scripts/verify-master-plan.sh` EXIT=0.
7. 이 PR 의 최종 제목을 분류기에 태웠을 때 **더 이상 `api` 로 떨어지지 않는다**(자기 실연의 종료).

## Sanity Check

**❓ 발견 3건 — 전부 스펙에 반영 완료(1회 보강).**

1. **❓ 상수 `4` 의 근거가 「내가 골랐다」로 남을 뻔했다.** 임계값 5종을 전수 실측해 표로 넣었고,
   4와 5가 같은 결과라 **경계값이 칼날이 아님**을 명시했다. 리뷰가 「왜 4냐」를 물으면 표가 답한다.
2. **❓ qa 순서를 내리면 순수 E2E 작업이 유실될 위험**을 처음엔 안 적었다. S3·E3·E4 로 역방향
   회귀 단언을 세웠고, 「오류 비용의 비대칭」으로 방향 선택 근거를 명문화했다.
3. **❓ 장부 갱신이 「문서 작업」으로 취급될 뻔했다.** 실제로는 `debt-ledger-mapping.test.ts` 가
   강제하는 **red/green 대상**이다. C1 · 완료 기준 4④·5 로 올렸다.

**남은 gap 0.** 아래 2건은 **의도적으로 범위 밖**이며 그 사실을 여기 남긴다.

- **`scripts/workflow/` 를 책임지는 sub-agent 가 정의돼 있지 않다.** `chore` → `backend-engineer`
  매핑으로 굴러가지만 그 에이전트의 정본 책임은 Kotlin 백엔드다. **결함이 아니라 공백**이고,
  고치려면 `.claude/agents/` 에 역할을 신설해야 해 이 PR 의 한 문장을 벗어난다. 별도 등재 후보.
- **`bc-keyword-coverage` 의 오라클 한계(불일치 57건)는 이 PR 이 줄이지 않는다.** 그 57 은 계획
  문서 편제와 코드 위치가 다른 데서 오는 구조적 불일치이고, 이 PR 은 **57 을 유지**하는 것이 목표다.

## Plan

### Task 1. 경로 신호 `apps/web` 을 뒤 슬래시 없이 인식하되 더 긴 이름은 배제한다 (결함 `44`)

**메타**.
- agent: `backend-engineer`
- files: [`scripts/workflow/classify-task.ts`, `scripts/workflow/classify-task.test.ts`]
- depends-on: []

**RED**:
- 파일: `scripts/workflow/classify-task.test.ts`
- 테스트:
  ```ts
  test('apps/web 는 뒤 슬래시가 없어도 ui 로 간다 (부채 44)', () => {
    assert.equal(classify({ title: 'apps/web 판별식 정리' }).type, 'ui');
    assert.equal(classify({ title: 'apps/web 판별식 정리' }).agent, 'frontend-engineer');
  });
  test('apps/web 로 시작하는 더 긴 이름은 ui 가 아니다 (E1·E2)', () => {
    assert.notEqual(classify({ title: 'apps/webhook 재시도 정리' }).type, 'ui');
    assert.notEqual(classify({ title: 'apps/web-legacy 정리' }).type, 'ui');
  });
  ```
- 실패 메시지 (예상): 첫 테스트가 `'backend' !== 'ui'` 로 red. 둘째는 현행에서도 pass —
  **오탐 방지 단언이라 red 가 안 나는 것이 정상**이고, 뮤테이션(Task 5 ①)이 비-공허를 증명한다.

**GREEN**:
- 파일: `scripts/workflow/classify-task.ts`
- `UI_PATH_PATTERNS` 의 `/apps\/web\//` → `/apps\/web(?![\w-])/` 1줄 교체

**REFACTOR**:
- 그 줄에 KDoc — 「`\b` 를 쓰지 않는 이유는 `apps/web-legacy` 에서 성립하기 때문(실측)」

**검증**: `node --experimental-strip-types --test scripts/workflow/classify-task.test.ts`

### Task 2. `qa` 는 다른 타입 신호가 없을 때만 고른다 (결함 `33`)

**메타**.
- agent: `backend-engineer`
- files: [`scripts/workflow/classify-task.ts`, `scripts/workflow/classify-task.test.ts`]
- depends-on: [1]

**RED**:
- 파일: `scripts/workflow/classify-task.test.ts`
- 테스트:
  ```ts
  test('E2E 와 UI 신호가 섞이면 구현 가능한 에이전트로 간다 (부채 33)', () => {
    const r = classify({ title: '로딩 프레임 계약 E2E 신설 + apps/web/ 4파일' });
    assert.equal(r.type, 'ui');
    assert.notEqual(r.agent, 'qa-engineer'); // 구현 코드 수정 금지 에이전트
  });
  test('순수 E2E 작업은 여전히 qa 다 (S3 역방향 회귀)', () => {
    assert.equal(classify({ title: 'E2E 시나리오만 추가' }).type, 'qa');
    assert.equal(classify({ title: 'Playwright 회귀 보강' }).type, 'qa');
  });
  ```
- 실패 메시지 (예상): 첫 테스트가 `'qa' !== 'ui'` 로 red.

**GREEN**:
- 파일: `scripts/workflow/classify-task.ts`
- `detectType` 에서 qa 판정 블록을 api·ui **뒤로** 이동(5→7번). 번호 주석도 함께 갱신.

**REFACTOR**:
- 이동한 블록 위에 KDoc — **오류 비용의 비대칭**을 근거로 남긴다
  (`qa-engineer` 오배정은 복구 불가 · `frontend-engineer` 오배정은 복구 가능)

**검증**: `node --experimental-strip-types --test scripts/workflow/classify-task.test.ts`

### Task 3. 짧은 ASCII 키워드는 단어 경계를 지킬 때만 매치한다 (신규 `45`)

**메타**.
- agent: `backend-engineer`
- files: [`scripts/workflow/classify-task.ts`, `scripts/workflow/classify-task.test.ts`]
- depends-on: [2]

**RED**:
- 파일: `scripts/workflow/classify-task.test.ts`
- 테스트:
  ```ts
  test('키워드를 부분문자열로 품은 평범한 영단어는 신호가 아니다 (부채 45)', () => {
    for (const t of ['dispatch 로직 정리', 'patch 파일 적용', 'path 계산 수정']) {
      assert.notEqual(classify({ title: t }).type, 'auth', t);
      assert.notEqual(classify({ title: t }).agent, 'security-engineer', t);
    }
    for (const t of ['build 스크립트 정리', 'guide 문서 갱신', 'requirement 정리']) {
      assert.notEqual(classify({ title: t }).type, 'ui', t);
    }
    for (const t of ['restore 절차 문서화', 'rapid 프로토타입']) {
      assert.notEqual(classify({ title: t }).type, 'api', t);
    }
  });
  test('진짜 키워드가 단독으로 있으면 종전 판정을 유지한다 (S5·E5~E7)', () => {
    assert.equal(classify({ title: 'SAML 연동' }).type, 'auth');
    assert.equal(classify({ title: 'saml2 설정' }).type, 'auth');       // 숫자는 경계로 안 친다
    assert.equal(classify({ title: 'oauth2 로그인 연동' }).type, 'auth');
    assert.equal(classify({ title: '2FA 백업코드' }).type, 'auth');
    assert.equal(classify({ title: 'API-03 웹훅' }).type, 'api');       // 하이픈은 경계
  });
  test('길이 예외가 없으면 동결값을 깬다 (E8·E9)', () => {
    assert.equal(classify({ title: '플러그형 AuthenticationProvider 구조' }).primary_bc,
      'identity-access');
    assert.equal(classify({ title: '담당자 (Reporter 1 / Assignee 1 / Watchers)' }).primary_bc,
      'issue-tracking');
  });
  ```
- 실패 메시지 (예상): 첫 테스트가 `'auth' !== 'auth'` 형태로 red(9건 중 8건).
  둘째·셋째는 현행에서도 pass — **회귀 방지 단언**이고 뮤테이션(Task 5 ③)이 비-공허를 증명한다.

**GREEN**:
- 파일: `scripts/workflow/classify-task.ts`
- `includesWithBoundary` 에 ASCII 분기 추가 + `ASCII_BOUNDARY_MAXLEN = 4` 상수 신설.
  경계 문자류는 `[a-z]` 만(숫자 제외).

**REFACTOR**:
- 기존 KDoc(`:239-250`)의 **거짓 문장을 정정**한다 — 「ASCII 키워드는 이 규칙과 무관」은 틀렸다.
  실제 위험은 한글 접두사가 아니라 **다른 ASCII 단어**였음을 실측 표와 함께 남긴다.
- 상수 `4` 옆에 임계값 스윕 결과(2/3/4/5/∞)를 요약해 「왜 4냐」에 파일이 스스로 답하게 한다.

**검증**: `node --experimental-strip-types --test scripts/workflow/classify-task.test.ts scripts/workflow/bc-keyword-coverage.test.ts`

### Task 4. 장부 2파일을 동시에 갱신한다 — `33`·`44` 해소 · `45` 등재 · 티어 표기 정정

**메타**.
- agent: `backend-engineer`
- files: [`TODOS.md`, `docs/plans/2026-08-12-debt24-master.md`]
- depends-on: []

**RED**:
- 파일: `TODOS.md`
- 조작: 신규 항목 `45` 를 `## ⬜ 워크플로우 — classify-task 가 …` 로 **먼저 추가한다.**
- 실패 메시지 (예상): `debt-ledger-mapping.test.ts` 가
  「`TODOS.md` ⬜ 집합 ⊄ 마스터 ⬜ 집합」으로 red — 기존 판별식이 그대로 RED 역할을 한다.

**GREEN**:
- 파일: `docs/plans/2026-08-12-debt24-master.md`
- §전수 매핑에 `| 45 | ⬜ | … | 미배정 | 워크플로우 |` 행 추가
- `33`·`44` 행을 `✅` + `#387` 로 전환하고 `TODOS.md` 의 두 제목 마커도 `## ✅ … (해소 2026-08-17)` 로.
  본문은 저장소 선례대로 **`**해소.**` 문단 + `<details>` 원 기록 보존** 형태.
- `TODOS.md` 의 `33`·`44` 제목 꼬리 `· T1` → `· T2` 정정(표면 정본 `GUARD_CI` 근거).

**REFACTOR**:
- 마스터 §PR 별 집계에 `#387` 행 추가(항목 `33`·`44`·`45`).

**검증**: `node --experimental-strip-types --test scripts/workflow/debt-ledger-mapping.test.ts scripts/workflow/todos-resolved-section-purity.test.ts`

### Task 5. 뮤테이션 4종으로 판별식이 비어 있지 않음을 증명한다

**메타**.
- agent: `backend-engineer`
- files: [`scripts/workflow/classify-task.ts`]
- depends-on: [1, 2, 3, 4]

**RED**:
- 이 task 의 RED 는 **뮤테이션이 만든 red 그 자체**다. GREEN 을 **선커밋한 뒤** 하나씩 끊는다.

| # | 끊는 것 | 기대 |
|---|---|---|
| ① | `UI_PATH_PATTERNS` 의 `(?![\w-])` 제거 | Task 1 오탐 단언 RED |
| ② | `detectType` 의 qa 블록을 원위치(5번)로 | Task 2 혼합 단언 RED |
| ③ | `ASCII_BOUNDARY_MAXLEN` 을 `99` 로 | Task 3 부분문자열 단언 + `bc-keyword-coverage` 상한 RED |
| ④ | 마스터 §전수 매핑에서 `45` 행 1개 삭제 | `debt-ledger-mapping` RED |

**GREEN**:
- 각 뮤테이션을 `git checkout -- <경로>` 로 원복. **하위 디렉터리에서 부르지 말 것**
  (learnings `bts-git-add-path-base-in-worktree` — 원복 실패가 종료 코드에 안 나타난다).
  원복 뒤 `git status` 를 **눈으로 본다.**

**REFACTOR**:
- 4종 결과를 PR 본문 §검증에 표로 싣는다. **설계와 다른 결과가 하나라도 나오면 그것을 그대로 적는다.**

**검증**: `node --experimental-strip-types --test 'scripts/**/*.test.ts' 'scripts/**/*.test.mjs'` → EXIT=0

## Plan 메타

- **task 수**: 5 · **예상 wave**: 4
  (W1 `{1, 4}` → W2 `{2}` → W3 `{3}` → W4 `{5}`.
  Task 1·2·3 은 `files` 교집합이 `classify-task.ts`·`classify-task.test.ts` 라 **자동 직렬화**되고,
  Task 4 는 장부 2파일만 만져 교집합 0 이므로 Task 1 과 병렬이다.)
- **구현 규율**: TDD red-first (T2 — `test:` → `feat:` 커밋 순서가 대조된다). ui 시각 트랙 **해당 없음**.
- **추가 검증**: 워크플로우 판별식 전량(착수 baseline 308/308) · `node scripts/build-doc-index.mjs --check` ·
  `bash scripts/verify-master-plan.sh` · 뮤테이션 4종.
- **typecheck·lint**: `scripts/**` 는 `apps/web` tsconfig 밖이라 프론트 typecheck 대상이 아니다 —
  판별식 실행 자체가 타입 스트리핑을 거치므로 문법 오류는 즉시 드러난다. `pnpm verify` 는
  **worktree 에서 금지**(C4)이며 CI 가 담당한다.

## 리뷰 결과 (← /bts-review-plan 채움)
