# 로컬 setupServer 전면 이주 — MSW 단일 서버로 수렴 (3파)

> slug: debt24-msw-migration
> type: test (fast-track — domain / spec / review-plan 및 게이트 1 생략)
> 생성: 2026-08-12
> PR: #375 (draft, 브랜치 `test/debt24-msw-migration`)
> 마스터 계획 [`2026-08-12-debt24-master.md`](2026-08-12-debt24-master.md)

## Brief

기술부채 24건 전수 처리의 **3파**. 이 PR 이 담당하는 항목은 마스터 계획 매핑 **`2` · `3` · `7`**.

| 매핑 # | 항목 | 상태 |
|---|---|---|
| `2` | 인프라 — 전체 스위트에서 `pnpm test` 가 간헐적으로 exit≠0 | **✅ 해소** (아래 §완료분) |
| `3` | apps/web — MSW 리졸버가 요청 1회에 **2번 실행**된다 | ⬜ — 이주가 닫는다 |
| `7` | apps/web — 로컬 `setupServer` **59개** 전면 이주 | ⬜ — 본체 |

`3` 과 `7` 은 **같은 작업**이다. 이중 디스패치의 원인이 「로컬 `setupServer` 인스턴스가
전역과 동시에 listen」이므로, 이주가 끝나면 원인이 사라진다.

---

## 완료분 (이 PR 에서 이미 닫은 것)

### ① 매핑 `2` — `pnpm test` 간헐 exit≠0 → ✅

**판정 질문을 바꿨다.** 「재현되나?」는 원리적으로 부재 증명이 안 된다 — 장부 자신이
2026-08-09 에 5회 미재현에도 열어 둔 이유가 그것이다. 대신 **누출 경로가 존재하는가**를 물었다.

| 경로 | 범위 | 결과 |
|---|---|---|
| `.catch` 없는 `mutateAsync` | 호출 18건 전수 | **전부 `try` 안** |
| mutation 콜백의 `throw` | 전수 grep | **0건** (히트 1건은 오탐) |

재현 측정도 3회 `EXIT=0` (215/213/216s · 기준선 340s 대비 **−37%**).
처방은 **읽는 쪽 봉합** — `/bts-impl` 에 §판정은 종료 코드로 한다 추가,
`worktree-hook-wiring.test.ts` 층 2 가 강제(뮤테이션 3종 red).

### ② 장부↔마스터계획 차집합 판별식 (범위 외 발견분)

마스터 계획이 `TODOS.md` **줄번호**를 키로 실었는데 **작성 시점부터 18/22 가 어긋나** 있었다.
줄번호는 휘발성이라 매 PR 마다 깨진다. 키를 **제목**으로 바꾸고 대조를
`scripts/workflow/debt-ledger-mapping.test.ts` 로 승격했다(뮤테이션 5종 red).
**첫 실전에서 실제로 미갱신을 잡았다.**

---

## 실측 — 이주 대상의 실제 모습 (2026-08-12)

### 규모

| 항목 | 값 |
|---|---|
| 로컬 `setupServer` 파일 | **59개** (`src/test/server.ts` 제외. 60번째 히트는 주석) |
| `const server = setupServer(...)` 형태 | **59 / 59** — 변수명까지 동일 |
| `beforeAll(() => server.listen({ onUnhandledRequest: 'error' }))` | **54** |
| 그 외 `listen` 형태 | 4 (+ `'bypass'` 1) |

**★변수명이 전역과 같은 `server` 다.** 그래서 `const server = setupServer(...)` 를
`import { server } from '@/test/server'` 로 바꾸면 **본문의 `server.use(...)` 는 그대로 산다.**

### ★★장부의 「최대 함정」은 이 저장소에 해당하지 않는다 (반증)

장부는 이렇게 경고했다.

> **★★이주 시 최대 함정 (반증이 적발).** 이주하면 그 59개 파일에서 **`/auth/refresh` 가
> 처음으로 살아나** `apiFetch` 의 401 자동 재시도가 지금은 실패하던 자리에서 성공한다 —
> **401/403 을 단언하는 테스트의 결과가 뒤집힌다.** 「기계적 치환」이 아니다.

**전수 조사 결과 해당 파일이 0개다.** 근거 4단계.

| 단계 | 실측 |
|---|---|
| 1. `fetch` 를 **직접** 부르는 파일 | **43 / 59** — `apiFetch` 를 거치지 않으므로 재시도 로직 자체가 없다 |
| 2. 나머지 16개 중 `401`·`403` 을 단언 | **6개** |
| 3. 그 6개의 상태 코드 | **전부 `403`** — `401` 이 하나도 없다 |
| 4. `apiFetch` 의 재시도 진입 조건 | `client.ts:114` — `if (res.status !== 401) return res` |

**403 은 재시도 대상이 아니다.** 그러므로 이 경고가 겨냥한 뒤집힘은 발생하지 않는다.

> **★경고를 「무효」로 넘겨 읽지 말 것.** 무효인 것은 **현재 코드에서의 401 경로**다.
> 앞으로 `apiFetch` 경유로 401 을 단언하는 테스트가 추가되면 전제가 다시 선다.

### 여전히 유효한 함정

| # | 함정 | 대응 |
|---|---|---|
| 2 | 전역 `setup.ts:61` 이 매 테스트 뒤 `server.resetHandlers()` 를 부른다 | 등록은 **반드시 `beforeEach`**. `beforeAll` 이면 첫 테스트 뒤 조용히 사라진다 |
| 3 | 단일 서버가 되면 `onUnhandledRequest: 'error'` 에 그대로 걸린다 | 전역도 `'error'` 이나 **전역은 `handlers.ts` 전량을 갖고 있어 더 관대**하다 — 오히려 완화 |
| **신규** | 전역에 이미 같은 핸들러가 있으면 **로컬 등록이 중복**된다 | MSW 는 나중 등록이 우선이라 무해. 다만 `server.use()` 는 **유지**한다 — 전역 구성이 바뀌어도 이 파일이 독립적으로 서게 |

---

## 파일럿 3종 — 패턴 확립 (전부 통과)

| # | 파일 | 형태 | before | after |
|---|---|---|---|---|
| A | `src/api/oidc.test.ts` | `setupServer()` **빈 인자** · 각 테스트가 `server.use` | 3 passed | **3 passed** |
| B | `src/mocks/saved-filter-handlers.test.ts` | `setupServer(...handlers)` · 전역에 **이미 등록됨** | 35 passed | **35 passed** |
| C | `src/components/automation/AutomationRuleList.test.tsx` | 컴포넌트 · `403` 단언 · `document.cookie` 조작 | 14 passed | **14 passed** |

### 확립된 치환 패턴

```diff
-import { setupServer } from 'msw/node'
+import { server } from '@/test/server'

-const server = setupServer(...someHandlers)
-
-beforeAll(() => server.listen({ onUnhandledRequest: 'error' }))
-afterEach(() => {
-  server.resetHandlers()
-  resetSomeStore()
-})
-afterAll(() => server.close())
+beforeEach(() => {
+  server.use(...someHandlers)
+})
+
+afterEach(() => {
+  resetSomeStore()
+})
```

**vitest import 도 함께 정리한다** — `beforeAll` · `afterAll` 이 다른 데서 안 쓰이면 제거,
`beforeEach` 가 없으면 추가. 남겨 두면 `noUnusedLocals` 로 타입 검사가 깨진다.

### ★파일럿 C 가 드러낸 것 — 주석이 거짓이었다

`AutomationRuleList.test.tsx:28` 이 「전역 handlers.ts **미등록**, 로컬 서버로 격리」라고
적었는데 **`handlers.ts:71·153` 이 이미 import·spread 하고 있었다.**
즉 이 파일은 **이중 디스패치의 실제 사례**였고, 주석이 그것을 가리고 있었다.
이주하면서 주석도 정정했다. **같은 형태의 거짓 주석이 다른 파일에도 있을 수 있다** —
「전역 미등록」이라 적힌 곳은 옮기기 전에 `handlers.ts` 를 grep 할 것.

---

## Plan — 남은 56개 이주

**dispatch 정책.** sub-agent 를 쓰지 않고 메인 에이전트가 직접 수행한다.

**검증 규율.** 파일마다 **before/after 테스트 수가 같아야** 한다. 줄어들면 조용한 축소다.
배치마다 `EXIT` 을 **파이프 없이** 직접 확인한다(이 PR 이 세운 규율).

### Task 1. ✅ 파일럿 3종 — 완료

### Task 2. `src/mocks/` 배치 (28개 중 1개 완료 → 27개)

**메타**. files: [`apps/web/src/mocks/*.test.ts`] · depends-on: [1]

`fetch` 직접 호출이 대부분이라 함정 1 무관. 기계적 치환.

### Task 3. `src/mocks/__tests__/` 배치 (15개)

**메타**. files: [`apps/web/src/mocks/__tests__/*.test.ts`] · depends-on: [1]

### Task 4. `src/api/` 배치 (9개 중 1개 완료 → 8개)

**메타**. files: [`apps/web/src/api/*.test.ts`, `apps/web/src/api/*.test.tsx`] · depends-on: [1]

★`useXxx.test.tsx` 는 React Query 훅 테스트라 `renderHook` 을 쓴다 — 생명주기 훅 정리에 주의.

### Task 5. 컴포넌트·라우트 배치 (7개 중 1개 완료 → 6개)

**메타**. files: [`apps/web/src/components/**`, `apps/web/src/routes/__tests__/**`, `apps/web/src/hooks/*.test.tsx`] · depends-on: [1]

★함정 1 위험군 6개가 여기 몰려 있다(전부 `403` 이라 실제 위험은 없으나 **개별 확인**한다).

### Task 6. 판별식 baseline 비우기 + 전량 검증

**메타**. files: [`apps/web/src/test/msw-single-setupserver.test.ts`] · depends-on: [2,3,4,5]

`MIGRATION_BASELINE` 을 **빈 배열로** 만든다. 그 순간부터 로컬 `setupServer` 는 **신규든
잔존이든 전부 red** 다. 비-공허 짝(훑은 파일 수 하한)은 그대로 둔다 —
baseline 이 비면 「0건 발견」과 「스캐너 고장」이 구분되지 않기 때문이다.

전 스위트 1회 실행. **파일 수·테스트 수 증감 0** 확인, `EXIT` 직접 확인.

### Task 7. 장부 갱신

**메타**. files: [`TODOS.md`, `docs/plans/2026-08-12-debt24-master.md`, `docs/progress.html`] · depends-on: [6]

매핑 `3` · `7` → ✅. ⬜ 21 → 19. 판별식이 마스터 계획 동기화를 강제한다.

**걷어낼 것 / 남길 것** (장부 지시).
- 제거 후보. `automation-execution-handlers.ts:117-119, 228-229, 260-261` 의 requestId 단발 캐시
  — 이중 디스패치 **보상 가드**라 원인이 사라지면 불필요
- **남긴다.** `backlog-fixtures.ts:165` 의 키 중복 금지 — 그건 우회가 아니라 **실제 백로그의
  도메인 불변식**이고, 제거하면 판별자만 사라진다(`[[seal-blinds-existing-guard]]`)

## Plan 메타

- task 수: 7 (1 완료)
- 구현 규율: 파일별 before/after 테스트 수 동일 · 배치마다 `EXIT` 직접 확인
- 병렬 dispatch: **미사용**
- 직렬 제약: T1 → (T2·T3·T4·T5) → T6 → T7

## 리뷰 결과 (← /bts-review-plan 채움)

fast-track 스킵 — 게이트 2(`/bts-codereview`)에서만 정지.
