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

## 도메인 정리 (← /bts-spec §1 채움)

## 스펙 (← /bts-spec §2 채움)

## Sanity Check (← /bts-spec §3 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
