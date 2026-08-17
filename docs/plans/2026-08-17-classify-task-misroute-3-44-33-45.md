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
| **R3** | `qa` **경로** 신호는 `ui` 보다 **앞**서고, `qa` **키워드**만 뒤로 간다 | `detectType` 판정 순서 |
| **R3-b** | `apps/web/e2e` 도 뒤 슬래시 없이 인식한다 (R1 과 같은 결함) | `QA_PATH_PATTERNS` |
| **R4** | `BOUNDARY_ONLY` 에 **이름으로 적힌** 키워드만 양쪽 단어 경계를 요구한다 | `includesWithBoundary` |
| **R5** | 그 밖의 ASCII 키워드는 종전 부분일치를 유지한다 — **길이로 자르지 않는다** | 〃 |
| **R5-b** | 영어 복수형 접미 `s` 1개는 경계로 친다 (`labels`·`boards`·`actions`) | 〃 |
| **R6** | 위 전부가 **회귀하면 판별식이 red** 가 된다 | `classify-task.test.ts` |
| **R7** | 장부 2파일이 `33`·`44`·**`45` 를 전부 ✅ `#387`** 로 반영한다 | 장부 2파일 |
| **R8** | 장부의 티어 표기 `33`·`44` 를 `T1` → `T2` 로 정정한다 | `TODOS.md` |
| **R9** | §PR 별 집계에서 `33` 의 **중복 소유**를 없앤다 (`33~40` 범위 조정) | debt24-master |
| **R10** | 부채 `34`(`bts-review-plan` 분기 표)에 **`chore`@T2 미정의**를 흡수하고 순서 제약을 적는다 | `TODOS.md` · master |

> **R7 초판 정정 (리뷰 F10).** 초판은 `45` 를 `⬜ 미배정` 으로 등재한다고 적었는데, **같은 PR 이
> `45` 를 고친다.** 장부 판별식은 「장부·마스터가 둘 다 ⬜ 로 일치」하면 조용히 통과하므로
> (`debt-ledger-mapping.test.ts:255` 가 그 한계를 자기 주석에 적어 두었다) 이 모순은 CI 가 못 잡는다.
> `45` 는 이 PR 이 닫으므로 **✅ `#387`** 이다.

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

**② `33` — 판정 순서를 「경로 먼저, 키워드 나중」으로 쪼갠다.** ⚠️ **초판을 폐기했다**(리뷰 F3).

초판은 qa 판정을 **통째로** `api`·`ui` 뒤로 내렸다. 그러면 `apps/web` 이 `apps/web/e2e/` 의
**접두사**라 처방 ① 의 경로 확장과 겹쳐 **Playwright 표면 전체가 `qa-engineer` 에 도달 불가**가 된다.

| 입력 | 현행 | 초판(qa 통째 뒤) | 확정안(경로 분리) |
|---|---|---|---|
| `apps/web/e2e/issue-detail.spec.ts 회귀 보강` | `qa` | **`ui`** ❌ | `qa` ✅ |
| `로딩 프레임 계약 E2E 신설 + apps/web/ 4파일` | `qa` ❌ | `ui` ✅ | `ui` ✅ |
| `E2E 시나리오만 추가` | `qa` | `qa` ✅ | `qa` ✅ |

**확정안** — `QA_PATH_PATTERNS`(경로)는 `ui` **앞**에, `QA_KEYWORDS`(키워드)만 **뒤**로 내린다.
이것이 장부가 원래 적어 둔 처방 ①(「제목이 아니라 **변경 경로**를 보게 한다」)의 취지다.
경로는 정밀 신호이고 키워드는 퍼지 신호이므로 **정밀한 쪽이 이긴다.**

**②-b `QA_PATH_PATTERNS` 도 같은 뒤 슬래시 결함을 갖고 있다** — `/apps\/web\/e2e\//` 라
`apps/web/e2e`(슬래시 없음)를 놓친다. 결함 `44` 와 **같은 결함**이므로 같은 처방을 적용한다.

> **왜 애매하면 ui 쪽인가 — 오류 비용이 비대칭이다.**
> `qa-engineer` 는 구현 코드 수정이 **금지**돼 있어 잘못 가면 **복구 불가**다.
> `frontend-engineer` 가 E2E 를 쓰는 것은 금지돼 있지 않아 **복구 가능**하다.
> 단 이 비대칭은 **경로 신호가 없을 때만** 적용한다 — 경로는 추측이 아니라 사실이다.

**③ `45` — 큐레이션 경계 목록.** ⚠️ **초판(길이 임계)을 폐기했다**(리뷰 F1·F2).

초판은 「길이 ≤ N 인 ASCII 키워드에 경계를 요구」했다. **N 을 4 로 두든 6 으로 올리든
진짜 신호를 함께 죽인다.**

| 검산 항목 | 현행 | 길이 임계 6 | **확정안(큐레이션+복수형s)** |
|---|---|---|---|
| `Argon2id 파라미터 튜닝` | `auth`/identity-access | **`backend`/-** ❌ | `auth`/identity-access ✅ |
| `LDAPS 연결 설정` | `auth`/identity-access | **`backend`/-** ❌ | `auth`/identity-access ✅ |
| 복수형 `issues`·`users`·`labels`·`boards`·`actions` | BC 유지 | **0/5** ❌ | **5/5** ✅ |
| 오배정 교정 12건 | — | 12/12 ✅ | **12/12** ✅ |
| FR 동결값 (≤57) | 57 | 57 | **57** ✅ |

**★★「동결값 57 이 유지된다」는 안전 근거가 아니었다.** `bc-keyword-coverage` 의 오라클은
**한국어 FR 제목 139건**이라 영어 어형 변화가 **원리적으로 나타날 수 없다.** 그래서 임계
2·3·4·5·6 **전부**에서 57 이 유지된다 — 계획 자신이 2·3 을 「부족」이라 적은 구간에서도 그렇다.
**계측기가 눈이 먼 것을 안전으로 읽었다.** 이 계획이 §Brief 에서 진단한
`[[safety-claim-asserted-but-never-measured]]` 의 **7번째 인스턴스를 계획 자신이 저질렀고**,
독립 리뷰가 머지 전에 잡았다.

**확정안.**

```ts
// 실제로 부딪힌 키워드만 이름으로 적는다. 길이는 충돌 성향과 상관이 없다 —
// csrf(4)·oidc(4)·aql(3) 은 15개월간 한 번도 안 부딪혔고, board(5)·action(6) 은 부딪혔다.
const BOUNDARY_ONLY = new Set(['pat', 'ui', 'api', 'rest', 'board', 'action', 'label']);
```

- 이 집합의 키워드만 **양쪽 단어 경계**를 요구한다. 나머지는 종전 부분일치 그대로다.
- 경계 문자류는 **`[a-z]` 만**(숫자 제외) — `[a-z0-9]` 로 잡으면 `saml2`·`oauth2` 가 깨진다(실측).
- **영어 복수형 접미 `s` 1개는 경계로 친다** — 없으면 `labels`·`boards`·`actions` 가 BC 를 잃는다.
  `relabel`·`keyboard`·`transaction` 은 **앞** 경계에서 걸리므로 이 허용에 영향받지 않는다(실측).
- **뮤테이션이 항목별로 비-공허하다** — 집합에서 한 줄을 지우면 대응 단언 하나가 red 다.
  단일 상수는 이 성질을 가질 수 없다.

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
| E8 | `플러그형 AuthenticationProvider 구조` | BC `identity-access` 유지 | R5 — `authentication` 은 목록 밖 |
| E9 | `담당자 (Reporter / Assignee / Watchers)` | BC `issue-tracking` 유지 | R5 — `watcher` 는 목록 밖 |
| E10 | 한글 전용 제목(`빌드 스크립트 정리`) | 종전과 동일 | 한글 경계 규칙 무변경 |
| **E11** | `Argon2id 파라미터 튜닝` | **`auth`/identity-access 유지** | ★리뷰 F1 — 길이 임계였다면 유실 |
| **E12** | `LDAPS 연결 설정` | **`auth`/identity-access 유지** | ★리뷰 F1 — 〃 |
| **E13** | `labels 상한 조정` · `boards 순서` · `actions 실행기` | BC 유지 | ★R5-b 복수형 |
| **E14** | `apps/web/e2e/issue-detail.spec.ts 회귀 보강` | **`qa` 유지** | ★리뷰 F3 — 초판이 `ui` 로 유실시켰다 |
| **E15** | `apps/web/e2e 시나리오 추가` | **`qa`** | ★R3-b — QA 경로의 뒤 슬래시 결함 |
| **E16** | `keyboard 단축키` · `transaction 격리` · `interaction 로그` · `relabel 스크립트` | BC `null` | 신규 오배정 4건 |
| **E17** | `labeling 규칙 정리` · `labeled 항목 필터` | BC `issue-tracking` 유지 | ★독립 검증이 잡은 **이 PR 이 만든** 오배정 |

> **E17 은 이 PR 이 스스로 만든 결함을 닫는 단언이다 (구현 후 추가 · D9).** `label` 을 경계
> 대상으로 만들자 `labeling`·`labeled` 같은 **진짜 라벨 작업**까지 신호를 잃었고, 빈자리를
> `규칙`(automation)·`필터`(search-export-import)가 차지했다. **신호 유실이 아니라 다른 BC 로의
> 신규 조용한 오라우팅**이라 부채 `45` 와 같은 양식이다 — 오배정을 없애는 PR 이 새 오배정을
> 남기면 안 된다. 처방은 뒤 경계로 인정하는 어형 접미를 `s` 에서 `ing`·`es`·`ed`·`s` 로 넓히는 것.
> **이 확장은 고친 12건과 직교한다** — 그것들은 전부 **앞** 경계에서 걸린다(실측).

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

1. `classify-task.test.ts` 에 S1~S5 · **E1~E16** 대응 단언이 있고 **각각 red 를 먼저 봤다.**
2. 워크플로우 판별식 **308 → (신규분 포함) 전량 pass · EXIT=0**. 착수 baseline 은 308/308.
3. `bc-keyword-coverage.test.ts` 가 **상한·하한 둘 다 통과**(불일치 57 유지 · `MAX_MISMATCHES` 무변경).
   ⚠️ **이 항목은 안전 근거가 아니다** — 오라클이 한국어라 영어 어형 변화를 못 본다(리뷰 F2).
   영어 쪽 안전은 E11~E13 단언이 진다. **이 문장을 지우지 말 것.**
4. **뮤테이션 6종이 설계대로 RED**
   ① `UI_PATH_PATTERNS` 의 `(?![\w-])` 제거 → E1·E2 RED
   ② `QA_PATH_PATTERNS` 판정을 다시 `ui` 뒤로 → **E14 RED**
   ③ `QA_PATH_PATTERNS` 의 경계 제거 → E15 RED
   ④ `BOUNDARY_ONLY` 에서 `'action'` 1줄 삭제 → E16 의 `transaction` RED (**항목별 비-공허**)
   ⑤ 복수형 `s` 허용 제거 → E13 RED
   ⑥ 마스터 §전수 매핑에서 `45` 행 삭제 → `debt-ledger-mapping` RED
5. `TODOS.md` 와 debt24-master 가 `33`·`44`·**`45` 전부 ✅ `#387`** 로 **양쪽 일치**하고,
   §PR 별 집계에 `33` 이 **한 행에만** 나온다.
6. `node scripts/build-doc-index.mjs --check` · `bash scripts/verify-master-plan.sh` EXIT=0.
7. 이 PR 의 최종 제목을 분류기에 태웠을 때 **더 이상 `api` 로 떨어지지 않는다**(자기 실연의 종료).
8. **`Argon2id`·`LDAPS` 가 `security-engineer` 로 간다** — 이 PR 이 보안 라우팅을 **악화시키지 않았다**는
   직접 증거. 길이 임계안이 여기서 죽었다.

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
  // ★리뷰 F3 — Task 1 과의 상호작용. 이 단언이 없으면 Playwright 표면이 통째로 샌다
  test('E2E 경로는 ui 경로보다 앞선다 (E14·E15)', () => {
    assert.equal(classify({ title: 'apps/web/e2e/issue-detail.spec.ts 회귀 보강' }).type, 'qa');
    assert.equal(classify({ title: 'apps/web/e2e 시나리오 추가' }).type, 'qa');
  });
  ```
- 실패 메시지 (예상): 첫 테스트가 `'qa' !== 'ui'` 로 red. **E15 는 현행에서도 red**
  (`QA_PATH_PATTERNS` 가 뒤 슬래시를 요구해 `apps/web/e2e` 를 못 잡는다 — 선재 결함).

**GREEN**:
- 파일: `scripts/workflow/classify-task.ts`
- `detectType` 의 qa 판정을 **둘로 쪼갠다** — `hasPathPattern(raw, QA_PATH_PATTERNS)` 는
  `api`·`ui` **앞**(5번), `hasAny(stripped, QA_KEYWORDS)` 는 **뒤**(8번). 번호 주석 갱신.
- `QA_PATH_PATTERNS` 의 `/apps\/web\/e2e\//` → `/apps\/web\/e2e(?![\w-])/` (R3-b).

**REFACTOR**:
- **`detectType` 위에 ASCII 결정 트리 도식을 박는다** (게이트 1 결정 D5). 순서가 계약인데
  지금은 번호 주석 10개로만 표현돼 있어, 한 줄을 옮기면 같은 사고가 다시 난다.
  ```
  ① auth(strong)     보안은 fast-track 무시 (위험 반경 ↑)
  ② migration
  ③ chore/fix 접두사
  ④ design
  ⑤ qa **경로**      ← 경로는 사실. 퍼지 신호보다 앞선다 (F3)
  ⑥ api
  ⑦ ui
  ⑧ qa **키워드**    ← 「E2E」가 제목에 섞였을 뿐일 수 있다 (부채 33)
  ⑨ auth(weak) → ⑩ feature → ⑪ backend(기본값)
  ```
- 비대칭 근거를 KDoc 으로 — `qa-engineer` 오배정은 복구 불가 · `frontend-engineer` 는 복구 가능.
  **단 이 비대칭은 ⑧ 에만 적용된다** — ⑤ 는 추측이 아니라 사실이므로 양보하지 않는다.

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
  test('목록 밖 키워드는 종전 부분일치를 유지한다 (E8·E9)', () => {
    assert.equal(classify({ title: '플러그형 AuthenticationProvider 구조' }).primary_bc,
      'identity-access');
    assert.equal(classify({ title: '담당자 (Reporter 1 / Assignee 1 / Watchers)' }).primary_bc,
      'issue-tracking');
  });
  // ★★리뷰 F1 — 길이 임계안이 여기서 죽었다. 이 단언이 그 설계를 영구히 배제한다
  test('진짜 보안 키워드는 어형이 붙어도 살아 있다 (E11·E12)', () => {
    for (const t of ['Argon2id 파라미터 튜닝', 'LDAPS 연결 설정']) {
      assert.equal(classify({ title: t }).type, 'auth', t);
      assert.equal(classify({ title: t }).agent, 'security-engineer', t);
    }
  });
  test('영어 복수형은 BC 를 잃지 않는다 (E13)', () => {
    assert.equal(classify({ title: 'labels 상한 조정' }).primary_bc, 'issue-tracking');
    assert.equal(classify({ title: 'boards 순서 조정' }).primary_bc, 'agile-planning');
    assert.equal(classify({ title: 'actions 실행기 리팩터' }).primary_bc, 'automation');
  });
  test('충돌 키워드는 다른 단어 안에 묻히면 신호가 아니다 (E16)', () => {
    for (const t of ['keyboard 단축키 정리', 'transaction 격리 수준 조정',
                     'interaction 로그 수집', 'relabel 스크립트']) {
      assert.equal(classify({ title: t }).primary_bc, null, t);
    }
  });
  ```
- 실패 메시지 (예상): S4·E16 이 red(12건). E8·E9·E11·E12·E13 은 현행에서도 pass —
  **회귀 방지 단언**이고 뮤테이션(Task 5 ④⑤)이 항목별 비-공허를 증명한다.

**GREEN**:
- 파일: `scripts/workflow/classify-task.ts`
- `includesWithBoundary` 에 ASCII 분기 추가 + **`BOUNDARY_ONLY` 집합 신설**
  (`pat` `ui` `api` `rest` `board` `action` `label`). 경계 문자류는 `[a-z]` 만(숫자 제외),
  **영어 복수형 접미 `s` 1개는 경계로 친다.**
- ⚠️ **분기 순서** — `isHangulSyllable(kw[0])` 검사가 **먼저**다. ASCII 분기를 앞에 두면
  한글 키워드(`액션`·`이슈`)가 앞 경계 보호를 잃어 `리액션` 오라우팅이 되살아난다(리뷰 F9-a).
- ⚠️ **양쪽 경계**여야 한다. 앞만 보면 `patch`·`path` 는 키워드가 index 0 이라 통과한다(F9-b).

**REFACTOR**:
- 기존 KDoc(`:239-250`)의 **거짓 문장을 정정**한다 — 「ASCII 키워드는 이 규칙과 무관」은 틀렸다.
  실제 위험은 한글 접두사가 아니라 **다른 ASCII 단어**였음을 실측 표와 함께 남긴다.
- `BOUNDARY_ONLY` 의 **각 줄에 부딪힌 단어를 주석으로** 적는다 (`'pat', // dispatch · patch · path`).
  「왜 이 키워드냐」에 줄 단위로 답하게 하는 것이 단일 상수 대비 이 설계의 핵심 이점이다.
- **길이 임계를 기각한 이유를 KDoc 에 남긴다** — `argon2`(6자)·`ldap`(4자)가 함께 죽는다.
  안 적으면 다음 사람이 「이거 그냥 길이로 자르면 되잖아」로 되돌린다.

**검증**: `node --experimental-strip-types --test scripts/workflow/classify-task.test.ts scripts/workflow/bc-keyword-coverage.test.ts`

### Task 4. 장부 2파일을 동시에 갱신한다 — `33`·`44` 해소 · `45` 등재 · 티어 표기 정정

**메타**.
- agent: `backend-engineer`
- files: [`TODOS.md`, `docs/plans/2026-08-12-debt24-master.md`]
- depends-on: []

**RED**: ⚠️ **초판 지시를 폐기했다 — 관측 불가능한 RED 였다.**

초판은 「`45` 를 `## ⬜` 로 먼저 넣어 red 를 본다」였는데, 리뷰 F10 정정으로 **`45` 는 `✅`** 다.
그리고 판별식의 양방향 차집합은 **`⬜` 키만** 본다 — `✅` 행은 `마스터 ✅ ⊆ 장부 ✅` 단방향이라
어느 쪽에 먼저 넣든 **red 가 구조적으로 안 난다.** 뮤테이션 ⑥ 이 그것을 실측으로 증명했다.

**대체 RED** — 이 PR 이 **새로 넣은 ⬜ 행**(`46`)을 마스터에서만 지운다(Task 5 ⑥′).
`「장부에만 있고 마스터 계획에 없는 항목이 0 이다」` 가 즉시 red 다.
**기존 행이 아니라 이 PR 이 넣은 행에 거는 것이 요점**이다 — 기존 행은 증거력이 약하다.

**GREEN**:
- 파일: `docs/plans/2026-08-12-debt24-master.md`
- §전수 매핑에 `| 45 | ✅ | … | #387 | 워크플로우 |` 행 추가 — **⬜ 가 아니다**(리뷰 F10).
  이 PR 이 `45` 를 닫으므로 ⬜ 로 적으면 장부가 거짓이 되고, 판별식은 그 거짓을 못 본다.
- `33`·`44` 행도 `✅` + `#387` 로 전환하고 `TODOS.md` 의 세 제목 마커를 `## ✅ … (해소 2026-08-17)` 로.
  본문은 저장소 선례대로 **`**해소.**` 문단 + `<details>` 원 기록 보존** 형태.
- `TODOS.md` 의 `33`·`44` 제목 꼬리 `· T1` → `· T2` 정정(표면 정본 `GUARD_CI` 근거).
  키 정규화(`normalizeKey`)가 **끝 괄호 그룹만** 떼므로 이 편집은 장부 대조 키를 안 흔든다(리뷰 F14).

**REFACTOR**:
- 마스터 §PR 별 집계에 `#387` 행 추가(항목 `33`·`44`·`45`)하고, **기존 `33`~`40` 범위 행에서
  `33` 을 빼고 건수를 `8`→`7` 로 내린다** (리뷰 F12 — 안 하면 `33` 이 두 행의 소유가 되는데
  이 표는 `masterMappingRows()` 스캔 대상 밖이라 **CI 가 영원히 못 본다**).
- **부채 `34` 서술에 `chore`@T2 를 흡수한다.** 착수 중 `bts-review-plan` 분기 표가 `chore`@T2 를
  정의하지 않은 것을 실제로 밟았다(이 PR 이 그 조합이다). `ui`@T2 와 **같은 표·같은 결함**이라
  새 항목을 만들지 않는다. 마스터 §순서 제약에 **`34` 는 `33` 다음**을 추가한다 —
  `33` 이 혼합 작업을 `qa`(표 미진입)에서 `ui`(정의 안 된 조합) 쪽으로 옮겨 노출을 키우기 때문이다.

**검증**: `node --experimental-strip-types --test scripts/workflow/debt-ledger-mapping.test.ts scripts/workflow/todos-resolved-section-purity.test.ts`

### Task 5. 뮤테이션 6종으로 판별식이 비어 있지 않음을 증명한다

**메타**.
- agent: `backend-engineer`
- files: [`scripts/workflow/classify-task.ts`, `docs/plans/2026-08-12-debt24-master.md`]
- depends-on: [1, 2, 3, 4]

**RED**:
- 이 task 의 RED 는 **뮤테이션이 만든 red 그 자체**다. GREEN 을 **선커밋한 뒤** 하나씩 끊는다.

| # | 끊는 것 | 기대 | **실측 결과** |
|---|---|---|---|
| ① | `UI_PATH_PATTERNS` 의 `(?![\w-])` 제거 | E1·E2 RED | **RED ✅** 1건 |
| ② | `QA_PATH_PATTERNS` 판정을 다시 `ui` 뒤로 | E14 RED (리뷰 F3 이 연 구멍) | **RED ✅** 2건 (E14·E15) |
| ③ | `QA_PATH_PATTERNS` 의 경계 제거 | E15 RED | **RED ✅** 1건 |
| ④ | `BOUNDARY_ONLY` 에서 `'action'` **1줄만** 삭제 | E16 RED — 항목별 비-공허 | **RED ✅** 1건 |
| ⑤ | `ASCII_INFLECTIONS` 허용 전체 제거 | E13·E17 RED | **RED ✅** 2건 |
| ⑤′ | `ASCII_INFLECTIONS` 에서 `'ing'` **1개만** 제거 | E17 RED — 항목별 비-공허 | **RED ✅** 1건 |
| ⑥ | 마스터 §전수 매핑에서 `45`(✅) 행 삭제 | `debt-ledger-mapping` RED | **GREEN ❌ 설계와 다름** |
| ⑥′ | 마스터 §전수 매핑에서 `46`(⬜) 행 삭제 | 〃 | **RED ✅** 1건 |
| ⑦ | `BOUNDARY_ONLY` 분기를 길이 임계(`kw.length > 6`)로 되돌림 | **E11 RED** (E12 는 생존) | **RED ✅** 1건 |

> **★⑦ 기대란을 정정했다 (독립 검증 지적).** 초판은 「`Argon2id`·`LDAPS` **둘 다** red」라 적었으나
> 실측은 **`Argon2id` 하나만** 죽는다 — `LDAPS` 는 `ldap`+`s` 로 어형 접미 분기를 타고 살아남는다.
> 단언은 한 `test` 안에 있어 red 1건으로 같아 보이지만 **기대란이 사실을 앞섰다.**
> 위험은 실질적이다 — 나중에 누가 E11 의 `Argon2id` 케이스만 지우면 길이 임계 가드가 조용히
> 사라지고 남은 `LDAPS` 는 아무것도 못 막는다.

> **④ 가 이 설계의 핵심 증거다.** 단일 상수(`ASCII_BOUNDARY_MAXLEN`)였다면 「한 줄을 지우면
> 대응 단언 하나가 red」라는 성질을 가질 수 없다 — 상수 하나를 흔들면 전부가 같이 흔들린다.
>
> **⑦ 은 게이트 1 에서 기각된 설계가 되살아나는 것을 기계로 막는다.** 길이 임계를 다시 넣는
> 순간 `Argon2id`·`LDAPS` 단언이 red 다. 문장으로만 「기각했다」고 적으면 다음 사람이 되돌린다.

> **★★⑥ 이 설계와 다르게 GREEN 이었고, 그것을 그대로 적는다.** 원인은 판별식의 구조다 —
> 양방향 차집합 2종은 **`⬜` 키만** 보고(`ledgerOpenKeys()` ↔ `masterOpenKeys()`),
> `✅` 는 `마스터 ✅ ⊆ 장부 ✅` **단방향**이다. 마스터에서 ✅ 행을 지우면 부분집합이 더 작아질
> 뿐이라 통과한다. **`45` 는 ✅ 라 구조적으로 red 가 날 수 없는 행이었다** — 내 뮤테이션 설계가
> 틀린 것이지 판별식이 퇴화한 것이 아니다. ⑥′ 로 같은 자리를 ⬜ 행에 걸어 비-공허를 확인했다.
> 이 사각 자체는 **부채 `47` 로 등재**했다.

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
  `bash scripts/verify-master-plan.sh` · **뮤테이션 6종**.
- **typecheck·lint**: `scripts/**` 는 `apps/web` tsconfig 밖이라 프론트 typecheck 대상이 아니다 —
  판별식 실행 자체가 타입 스트리핑을 거치므로 문법 오류는 즉시 드러난다. `pnpm verify` 는
  **worktree 에서 금지**(C4)이며 CI 가 담당한다.

## 리뷰 결과

**렌즈. `plan-eng-review` 1종** (`chore`@T2 는 분기 표 미정의 조합 — 게이트 1 결정 D2 로 확정).
`plan-ceo-review` 는 제외(사업·범위 관점이 하네스 도구 정비에 공전). 아웃사이드 보이스는
`codex` 부재로 Claude 서브에이전트 대체(D3 승인 범위로 처리).

### 결정 (게이트 1 이전 · Maxi 확정)

| # | 결정 | 근거 |
|---|---|---|
| D1 | 신규 `45` 를 이 PR 에 포함 | 같은 파일·같은 뿌리 · 파급 최대 |
| D2 | plan 리뷰 = `plan-eng-review` 1종 | 표 미정의 조합. 강제 장치 변경에 리뷰 0종은 불가 |
| D3 | 독립 코드리뷰에 서브에이전트 사용 승인 | 자기 구현을 자기가 리뷰하면 독립이 아니다 |
| D4 | ~~`ASCII_BOUNDARY_MAXLEN` 4 → 6~~ **폐기** | 아웃사이드 보이스 F1 이 반증. 아래 D6 이 대체 |
| D5 | `detectType` ASCII 결정 트리 도식 | 순서가 계약인데 번호 주석으로만 표현돼 있었다 |
| D6 | **길이 임계 전면 기각 → `BOUNDARY_ONLY` 큐레이션 + 복수형 s** | F1·F2 |

### 인라인 리뷰 (4섹션)

| 섹션 | 결과 |
|---|---|
| 1. 아키텍처 | 발견 1 — 임계 4 가 길이 5~6 을 열어둠 (→ D4, 이후 D6 이 대체) |
| 2. 코드 품질 | 발견 1 — 결정 트리 도식 부재 (→ D5) |
| 3. 테스트 | REGRESSION RULE 발동 → 신규 4건 자동 추가 · GAP 1(임계 경계값 단언, D6 으로 소멸) |
| 4. 성능 | 문제 없음 — 인접 문자 2개 추가 조회, 정규식 백트래킹 무증가 |

### 아웃사이드 보이스 — 인라인 리뷰를 뚫었다 (P0 2건)

**★★독립 리뷰가 내 리뷰의 승인 결과(D4)를 반증했다.** 6연속에 이은 6번째가 아니라,
**같은 세션 안에서 인라인 리뷰가 통과시킨 것을 아웃사이드가 잡은 첫 사례**다.

| # | 심각도 | 내용 | 내 검산 |
|---|---|---|---|
| F1 | **P0** | 길이 임계는 `argon2`(6)·`ldap`(4) 등 **진짜 보안 키워드**와 영어 복수형 전량을 함께 죽인다 | **확진** — `Argon2id`·`LDAPS` 가 `auth/identity-access` → `backend/-` |
| F2 | P1 | 「동결값 57 유지」는 안전 근거가 아니다 — 오라클이 한국어라 영어 어형을 못 본다 | **확진** — 임계 2·3·4·5·6 전부 57 |
| F3 | **P0** | `apps/web` 이 `apps/web/e2e/` 의 접두사 → Playwright 표면이 `qa-engineer` 도달 불가 | **확진** — `…spec.ts 회귀 보강` 이 `qa`→`ui` |
| F5 | P1 | 큐레이션 7개가 같은 결과를 50개 적은 키워드로 낸다 | **확진 + 개선** — 복수형 s 를 더해 5/5 |
| F9 | P2 | 분기 순서(한글 먼저)와 양쪽 경계가 스펙에 안 적혀 있다 | 채택 — Task 3 GREEN 에 명시 |
| F10 | P1 | `45` 를 고치면서 `⬜` 로 등재하는 모순. 장부 판별식이 못 잡는 형태 | 채택 — R7 정정 |
| F12 | P2 | §PR 별 집계에서 `33` 이 두 행 소유. 그 표는 스캔 대상 밖 | 채택 — R9 |
| F13 | P1 | `33` 을 고치면 부채 `34` 노출이 커진다. 순서 제약 부재 | 채택 — R10 |
| F14 | P2 | 티어 표기 편집이 장부 키를 안 흔든다 | 확인 후 근거를 Task 4 에 명시 |
| F16 | P2 | `UI_PATH_PATTERNS` 의 raw 매칭은 무관 | 일치 — 내 인라인 판단과 같음 |
| **F11** | — | 「`#387` 은 추측된 PR 번호」 | **기각** — `#387` 은 이미 열린 실제 PR |
| **F15** | — | 「44 만 단독 배포하라」 | **기각** — 44 만 넣으면 F3 의 절반이 열린다. Maxi 확정(D6 묶음 유지) |

**미채택 사유 기록.** F7·F8 은 「현재 무해하나 문서화 안 됨」이라 **범위 밖**으로 둔다 —
공백 ASCII 키워드는 전부 ≥10자라 큐레이션 집합에 없고, `/api/` 의 사실상 사문화는 선재 상태다.

### BLOCKER 처리

**P0 2건 → 중단 후 Maxi 개입 → D6 으로 처방 교체 승인.** 계획은 이 결과를 반영해 갱신됐다.
초판 서술(길이 임계·qa 통째 이동·`45` ⬜)은 **지우지 않고 정정 표시로 남겼다** — 왜 그 설계가
기각됐는지가 없으면 다음 사람이 되돌린다.

### 독립 검증 (구현 후 · `/bts-impl` Step 2-C 대체)

**판정 `DRIFT`.** Task 1~3 은 PASS(세 RED 커밋을 **그 시점 트리에서 실제 실행해** 확인),
요구사항 미구현 0건, 조작된 증거 0건. 지적 4건 중 **3건 채택 · 1건 기각.**

| # | 지적 | 처리 |
|---|---|---|
| V1 | Task 4 의 RED 산출물이 없다 (`test:` 커밋 부재) | **채택 · waive** — 처방된 RED 가 F10 정정 이후 **관측 불가능**임이 ⑥ 으로 실증됐다. 계획 RED 절을 ⑥′ 기준으로 다시 썼다. 재dispatch 는 기존 증거보다 **적게** 증명하므로 안 한다 |
| V2 | 뮤테이션 ⑦ 기대란 과장 (`LDAPS` 는 생존) | **채택** — 실측 재현 후 기대란을 `E11 RED (E12 는 생존)` 로 정정 |
| V3 | `labeling`·`labeled` 가 **신규 오배정**됐다 | **채택 (D9)** — E17 단언 + 어형 접미 확장으로 봉합. 뮤테이션 ⑤·⑤′ 로 비-공허 확인 |
| V4 | 부채 `47` 의 티어가 `T1` 이 아니라 `T2` 다 | **기각** — 실측 `TIER: T1 · SURFACES: TEST`. `47` 의 처방 2종은 `debt-ledger-mapping.test.ts` 만 만지고, `SURFACE_PRECEDENCE` 가 `TEST` 를 **맨 앞**에 둔다(판정 규칙 ④ 를 지키려는 배치). 리뷰어가 `GUARD_CI` 만 보고 우선순위를 안 봤다 |

**미선언 파일 3건**(이 plan 파일 · `docs/INDEX*.md` 2종) — 전부 정당하나 task `files` 에 없었다.
plan 파일은 Task 5 REFACTOR 가 「PR 본문에」라 적어 대상이 갈렸고, INDEX 2종은 자동 생성물이다.

**계획 예측 오류 1건 (구현 무관).** Task 2 RED 절이 「E15 는 현행에서도 red」라 적었으나 실제로는
`e2e` 가 `QA_KEYWORDS` substring 이라 통과했다. 구현에는 영향 없고 뮤테이션 ③ 이 대신 증명한다.

**완료 기준 1 의 문언 결함.** 「E1~E16 **각각** red 를 먼저 봤다」는 문언상 거짓이다 —
회귀 가드(E1·E2·E5~E9·E11~E15)는 설계상 fix 이전에도 통과한다. 그 사실은 각 RED 절에 적어 두었고,
비-공허는 뮤테이션 8종이 진다. **다음 계획에서는 이 문장을 「신규 동작 단언은 red 를 먼저 보고,
회귀 가드는 뮤테이션으로 비-공허를 증명한다」로 쓴다.**

## GSTACK REVIEW REPORT

| Review | Trigger | Why | Runs | Status | Findings |
|--------|---------|-----|------|--------|----------|
| CEO Review | `/plan-ceo-review` | Scope & strategy | 0 | SKIPPED | 하네스 도구 정비 — 사업·범위 렌즈 공전 |
| Codex Review | `/codex review` | Independent 2nd opinion | 0 | UNAVAILABLE | codex CLI 미설치 → Claude 서브에이전트 대체 |
| Eng Review | `/plan-eng-review` | Architecture & tests (required) | 1 | CLEAR | 2 issues, 0 critical gaps (인라인) |
| Design Review | `/plan-design-review` | UI/UX gaps | 0 | N/A | 사용자 화면 0 |
| DX Review | `/plan-devex-review` | Developer experience gaps | 0 | NOT RUN | — |

**CROSS-MODEL:** 아웃사이드 보이스가 인라인 리뷰와 **겹치지 않는 P0 2건**을 냈다(F1·F3).
겹친 것은 F16 1건뿐이다. 인라인 리뷰가 승인한 D4 를 아웃사이드가 반증했고, 전량 자체 검산으로
확인한 뒤 D6 으로 교체했다. 아웃사이드 주장 12건 중 **2건(F11·F15)은 검산에서 기각**했다.

**VERDICT:** ENG CLEARED (D6 반영 후) — 게이트 1 로 넘어간다. CEO 리뷰는 이 티어·타입에서 미적용.

NO UNRESOLVED DECISIONS
