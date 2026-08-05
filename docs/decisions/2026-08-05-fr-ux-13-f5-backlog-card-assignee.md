# FR-UX-13 F5 — 백로그 카드 담당자·에러 상태 봉합 (ADR)

> PR #342 · 브랜치 `ui/fr-ux-13-f5-backlog-card-assignee` · 2026-08-05
> BC. agile-planning (프론트 전용) · 백엔드 0줄 · 마이그레이션 0 · 신규 API 0 · 신규 의존성 0
> 정본. `docs/plan/product/personalization.md` §4.11 · 스펙. `docs/specs/2026-08-05-fr-ux-13-f5-backlog-card-assignee.md`

## 무엇을 고쳤나

1. 백로그·스프린트 카드의 담당자가 **전원 `?`(이름 미확인)** 로 렌더되던 것을 실제 이니셜 아바타로 채웠다.
2. 백로그 조회 실패 시 **빈 `<div/>`** 가 반환돼 화면이 통째로 비던 것을 `role="alert"` 안내 + `다시 시도` 버튼으로 바꿨다.

## D-1. 담당자 조회 = 필요한 사람만 + 50개씩 나눠 전량 (Maxi 확정 M1)

**정본의 처방을 따르지 않았다.** §4.11 은 *"`board.tsx:333` 의 `useUsersByIds` 조립 패턴을 복제"* 라고
적었으나 실측 결과 **두 겹으로 틀렸다**.

- 보드 라우트는 `useUsersByIds` 가 아니라 `useQuery(['users'], fetchUsers)` **전체 목록**을 쓴다
  (`projects.$projectKey.board.tsx:335`).
- 그 방식은 `UsersController.kt:53` 의 `MAX_RESULTS = 50` 때문에 **1,000명 조직에서 임의의 50명만**
  돌려준다. 담당자가 그 50명 안에 들 확률은 낮다 — **보드 화면이 이미 그 상태**다(개발 시드가
  5명이라 안 드러날 뿐).

기각한 대안 2종.

| 대안 | 기각 사유 |
|---|---|
| 전체 목록 복제 (정본 문자) | 위 — 알면서 결함을 복제하게 된다 |
| `useUsersByIds` 로 앞 50명만 | 백로그 상한이 `BOARD_CARD_FETCH_LIMIT = 1000`(`IssueRepository.kt:1204`)이라 담당자 50명 초과가 가능하고, 그러면 **고치려던 `?` 가 그대로 남아 반쪽 봉합**이 된다 |

채택. `useUsersByIdsChunked(ids)` 신설 — 중복 제거 후 **`USERS_BY_IDS_CHUNK_SIZE = 50`** 이하 묶음으로
잘라 `useQueries` 로 병렬 조회. 상수값은 백엔드 `MAX_RESULTS` 와 **같아야 하는 값**이라 JSDoc 에
근거를 남겼다(`UsersController.kt:71` — 초과 시 400).

**기존 `useUsersByIds` 는 건드리지 않았다.** 소비처가 5곳이고 queryKey 구조를 바꾸면 그 5곳의
캐시·테스트가 함께 흔들린다. 청크가 필요한 것은 백로그뿐이다.

## D-2. 보드의 동일 결함은 범위 밖 (Maxi 확정 M2)

한 PR = 한 관심사 원칙 + 보드 E2E 동반 검증 비용. **기록만** 하고 이번 PR 은 백로그만 고쳤다.

**★ 두 결정의 조합이 남기는 상태.** 이 PR 이후 **백로그는 정확 · 보드는 낡음**으로 두 화면 동작이
갈린다. **회귀가 아니라 선행**이다 — 백로그가 옳은 쪽이고 보드가 따라올 차례다. 후속 트랙이
"왜 보드만 다르냐" 를 버그로 재보고하지 않도록 이 비대칭을 여기 명시한다.

## D-3. prop 타입은 그대로 둔다 — Map 을 채우는 것이 최소 완전 해법

보드는 `Map<issueKey, CardAssigneeDisplay>` **3-상태 타입**을 쓰고 백로그는 `Map<string, string>`
**문자열**이라 "보드 패턴 복제" 를 문자 그대로 하면 백로그 3파일의 prop 타입과 테스트 4종이 함께 바뀐다.

하지만 `BacklogCard.tsx:83` `AssigneeSlot` 이 **이미 3상태를 판정한다** —
`assigneeName !== undefined → 이니셜` / `assigneeId !== null → ?` / `그 외 → 미배정`.
**Map 을 채우는 것만으로 결함이 완전히 사라진다.** 사용자에게 보이는 변화는 0인데 변경 표면만
커지는 이식은 하지 않았다.

## D-4. 재시도 중에는 버튼이 스스로 상태를 말한다 (Maxi 확정 D3, design review 산출)

`isFetching` 동안 버튼을 비활성화하고 라벨을 `다시 시도 중…` 으로 바꾼다.
**`isLoading` 이 아니라 `isFetching` 이어야 한다** — `isLoading` 은 최초 1회만 true 라 재조회를 못 잡고,
그대로 두면 느린 네트워크에서 버튼이 고장 난 것처럼 보여 연타를 부른다.

분기 순서는 **`isLoading` → `isError` → `undefined`** 로 고정한다. `isError` 를 앞에 두면 재조회 중에도
에러 화면이 깜빡인다. 회귀 가드가 이 순서를 지킨다(뮤테이션 시 T4-3 만 red 로 실증).

## D-5. `combine` 으로 반환 참조를 고정한다 (구현 중 발견)

`useQueries` 결과를 `flatMap` 으로 펴면 **매 렌더 새 배열**이 나온다. `BacklogColumn` 은
`memo(BacklogColumnInner)` 이고 주석이 「`assigneeNames` 가 변하지 않으면 재렌더 스킵」이라 적고 있어,
그대로 두면 **기존 최적화가 통째로 죽는다** — 드래그 중에는 `overDroppableId` 변경으로 상시 재렌더된다.

처방은 `useQueries` 의 **`combine` 옵션**. 라이브러리가
`replaceEqualDeep(this.#combinedResult, combine(input))` 로 깊은 값이 같으면 이전 참조를 돌려준다
(`query-core@5.100.11` `queriesObserver.js:144-162` 실측). 직접 `useMemo` 로 흉내내지 않았다.
회귀 가드 **T2-8**(재렌더돼도 `data` 참조 유지)이 이걸 지키고, 비-공허 확인(combine 제거 → red)을 거쳤다.

## D-6. 코드리뷰가 적발한 것 — 같은 결함으로 가는 경로가 **셋** 더 있었다

리뷰어 3종(절대 규칙 · 테스트 · 유지보수성)이 **독립적으로** 같은 결함군을 지목했다.
「담당자 이름이 전원 `?` 로 깜빡인다」— 이 PR 이 고치려던 바로 그 증상이 **다른 트리거로 재현**된다.

| 경로 | 트리거 | 원인 | 처방 |
|---|---|---|---|
| ① id **순서** 변경 | **드래그 재정렬** (이 화면의 주 조작) | `chunkUserIds` 가 `Set` 삽입 순서를 그대로 두는데 TanStack 배열 queryKey 는 **순서 민감** → 집합이 같아도 캐시 미스 | `[...new Set(ids)].sort()` + 가드 **T2-9** |
| ② id **집합** 변경 | 이슈 생성(담당자 지정) 후 재조회 | 청크 queryKey 변경 → `data` 가 `[]` → 이미 알던 이름까지 소실 (**리뷰 C3 재도입**) | 캐시 조회 placeholder + 가드 **T2-10** |
| ③ **부분** 실패 | 담당자 50명 초과 시 묶음 2개 중 1개 실패 | 갈래 자체가 미검증이었다 | 가드 **T3-4** |

**★ ②의 처방은 controller 가 지시한 것이 틀렸고 implementer 가 실측으로 뒤집었다.**
controller 는 형제 훅의 선례대로 `placeholderData: keepPreviousData` 를 지시했으나, 그걸 넣고도
**T2-10 이 red** 였다. 원인은 `useQueries` 의 구조다 — `queriesObserver.js:171` 이 옵저버를
**`queryHash` 로만** 매칭하므로 청크 키가 바뀌면 **새 옵저버**가 생기고,
`keepPreviousData` 가 읽는 `#lastQueryWithDefinedData`(`queryObserver.js:272`)는 그 새 인스턴스에
비어 있다. 즉 **`keepPreviousData` 는 `useQueries` 에서 키가 바뀌는 순간에 대해 구조적으로 무력**하다
(단일 `useQuery` 인 형제 `useUsersByIds` 에서는 정상 동작 — 그래서 T-UU-6a 가 초록이다).
대체안은 `['users','byIds', …]` 접두 캐시를 훑어 해당 id 를 건져 placeholder 로 넣는 방식이다.

### 함께 봉합한 것 4건

- **죽은 표면 제거.** `useUsersByIdsChunked` 의 `isError` 는 프로덕션 소비처가 **0**인데 JSDoc 과
  T2-7 이 "소비처가 fail-soft 판정에 쓴다"고 선언하고 있었다 — **존재하지 않는 계약을 근거로
  자기를 정당화하는 가드**. 표면과 테스트를 함께 걷어냈다(fail-soft 는 `isError` 를 **안 읽는
  것으로** 이미 충족된다).
- **거짓 인과 주석 교체.** 「`isError` 를 `isLoading` 앞에 두면 재조회 중 에러 화면이 깜빡인다」는
  거짓이다 — `isLoading = isPending && isFetching` 이고 `isPending`/`isError` 는 같은 `status`
  열거의 **배타 값**이라 동시에 참일 수 없다(`queryObserver.js:308-310`). 같은 PR 의 테스트 주석이
  이미 반대 사실을 적어 **문서끼리 모순**하고 있었다.
- **도달 불가 픽스처 정정 (2번째).** T4-3 이 `isLoading: true && isError: true` 를 주입하고 있었다 —
  T4-2b 에서 한 번 잡은 **같은 양식이 다른 테스트에 남아 있었다**. 게다가 그 테스트가 **로딩
  상태(FR-8·E8)의 유일한 커버리지**였다.
- **mock 이 백엔드보다 관대했다.** MSW `user-handlers.ts` 가 `?ids=` 개수 상한을 강제하지 않아
  **프론트가 51개를 보내도 어떤 테스트도 못 잡았다.** 백엔드와 같은 400 을 돌려주게 맞췄다.

### 계측 공백 1건 — 안전망이 한 겹뿐이었다

`BacklogBoard.test.tsx` 의 `useUsersByIdsChunked` mock 이 **인자를 무시**해서,
「모은 담당자 id 를 훅에 실제로 넘기는가」(FR-1·FR-2)를 **어떤 유닛도 재지 않았다**.
리뷰어가 `useUsersByIdsChunked(assigneeIds)` → `([])` 로 뮤테이션하자 **유닛 118/118 전부 초록**,
E2E S9 만 red 였다. mock 을 `vi.fn` 으로 바꾸고 호출 인자 단언을 넣어 유닛 층에서도 잡히게 했다.

### 재시도 경로 커버리지 0 → E2E 2종 신설

스펙 S7(재시도 성공)과 엣지 E9(재시도도 실패)를 덮는 테스트가 없었다. E2E S10 은 버튼의 **존재만**
보고 한 번도 클릭하지 않았고, 유닛 T4-2 는 `refetch` **호출 횟수만** 셌다 — 「클릭 후 정상 복귀」가
**사람 눈확인에만** 남아 있었다. MSW 에 **1회성 실패 토글**(`'once'`)을 더해 S11(복귀)·S12(재실패
유지)를 만들었다.

## ★ 이번 작업이 남기는 교훈 4건

### 1. 정본의 처방이 「어느 파일의 무슨 패턴」이라고 지목하면 그 파일을 열어 봐야 한다

정본은 `board.tsx:333` 의 `useUsersByIds` 를 지목했는데 그 줄에 그 훅이 없었다. 줄번호 2건도
실제와 달랐다(`:217`→`:102`, `:214`→`:99`). **문서가 지목한 좌표는 유통기한이 있다.**
[[comment-backend-is-import-byproduct-read-only]] 의 「파일 존재 ≠ 기능 존재」와 같은 결이다.

### 2. 반쪽 봉합의 판정 기준은 「고치려던 증상이 어떤 조건에서 살아남는가」다

「앞 50명만」은 코드가 가장 짧지만 담당자 51명째부터 **고치려던 `?` 가 그대로 남는다**.
무엇을 고쳤나가 아니라 **무엇이 안 고쳐진 채 남는가**를 물어야 갈린다
([[measured-the-wrong-thing-twice]] 의 후속 적용).

### 3. ★★ 도달 불가능한 상태를 지키는 테스트는 가짜 그린이다

plan 이 지시한 T4-2b 픽스처는 `isError && isFetching && data === undefined` 였는데,
TanStack Query 는 `data === undefined` 인 쿼리를 재조회하면 `status` 를 `'pending'` 으로 되돌리며
에러를 지운다. 즉 **그 조합은 실제로 만들어질 수 없다.** implementer 가 유닛 테스트만 봤으면
초록으로 통과했을 것이고, **실브라우저에서 DOM 을 80ms 간격으로 샘플링해** 「`다시 시도 중…` 이
한 번도 안 뜬다」를 관측하고서야 드러났다. 픽스처를 도달 가능한 경로(캐시에 data 가 남은 채
재조회 실패)로 교체했다.
**교훈. 상태 조합을 단언하기 전에 「그 조합이 실제로 도달 가능한가」를 먼저 물어라.**

### 4. 두 라이브러리의 기본값이 반대다 — `exact` 는 Playwright 에만 있다

plan 이 유닛 테스트에 `getByRole('button', { name: '다시 시도', exact: true })` 를 적었으나
`@testing-library/dom@10.4.1` 의 `ByRoleOptions` 에는 **`exact` 키 자체가 없다**(tsc TS2769 로 실증).
Testing Library 의 ByRole `name` 은 **원래 정확 일치**이고, **Playwright 는 부분 일치가 기본**이라
거기서는 `exact: true` 가 필요하고 유효하다. 같은 이름의 조회가 두 도구에서 반대로 동작한다.
유닛에서는 대신 「`다시 시도` 로 조회했을 때 `다시 시도 중…` 이 안 잡힌다」를 단언으로 못 박았다.

## 부수적으로 고친 것 2건

- **i18n 판별식 등록.** `create-entry-point-names.test.ts` 의 백로그 화면 집합에 `retry` 를 등록했다.
  **`retrying`('다시 시도 중…')은 등록하지 않았다** — `retry` 를 부분 문자열로 포함하지만 §제외 3종 ②
  「같은 버튼의 다른 상태」(`submitButton`/`submitButtonPending` 선례)에 해당해 공존이 불가능하다.
  넣었으면 판별식이 **구조적으로** red 였다.
- **판별식 it 제목의 개수 리터럴 제거.** 「신규 진입점 **3종**…」이 단언 4건과 어긋나 있었다.
  개수 리터럴은 stale 해지는 순간 거짓이 된다([[orchestrator-instruction-counts-are-blindfolds]]).

## 남은 관찰 4건

1. **보드 화면의 동일 결함** (D-2). 전체 목록 50 상한 → 대규모 조직에서 담당자 대부분 `?`. 후속 트랙.
2. **로딩 ↔ 에러 정렬 불일치.** 로딩은 `py-16` 중앙, 에러는 `p-8` 좌측. 눈확인 결과 **세로 위치는
   동일**(둘 다 `y=120`)이고 가로 정렬만 달라 거슬리는 수준은 아니라고 판정. 통일하려면
   `ActiveProjectGate` 를 포함한 전역 관례를 함께 봐야 한다.
3. **재시도 버튼 터치 타깃 32px** (`size="sm"` = `h-8`). 모바일 권장 44px 미만이나
   `ActiveProjectGate` 도 동일한 **전역 관례**라 단독 PR 감. design review F4.
4. **★`if (isError)` 가 캐시된 데이터를 덮는다.** `BacklogBoard.tsx:117` 은 `backlogView` 유무를
   보지 않아, **정상 렌더 중이던 보드가 배경 재조회 실패 하나로 에러 패널로 교체**된다
   (도달 경로. 드래그 성공 직후 invalidate 재조회 실패 · 탭 복귀 시 `refetchOnWindowFocus` 실패).
   main 기준 동작은 "에러를 무시하고 기존 데이터를 계속 렌더" 였으므로 **이 경로는 동작이 바뀌었다**.
   그럼에도 이번 PR 에서 고치지 않는다 — `if (isError)` 단독 조기 반환은 이 저장소의 **지배적
   관례**(`SessionList`·`MemberList`·`ComponentList`·`VersionList`·`CycleTimeReport` 등 약 20곳)라
   신규 일탈이 아니고, `isError && backlogView === undefined` 로 좁히면 `다시 시도 중…` 라벨의
   유일한 도달 경로가 사라져 설계 재검토가 필요하다. **후속 트랙 대상.**
5. **`USERS_BY_IDS_CHUNK_SIZE` ↔ 백엔드 `MAX_RESULTS` 가 서로를 검사하지 않는다.** 이번 PR 은
   프론트 쪽만 닫았다(MSW 가 이제 상한을 강제한다). 남은 위험 2건. ① 백엔드 `MAX_RESULTS` 가
   **과부하**돼 있다 — typeahead `findAll(limit=)`과 ids 개수 상한을 **같은 상수**가 겸해서,
   typeahead 성능을 위해 20 으로 **낮추는** 정당한 변경이 백로그 담당자 조회를 400 으로 조용히
   죽인다. ② 역참조가 없다. **처방은 계약 스냅샷** — 이 repo 에 이미 선례가 있다
   (`apps/web/src/api/__tests__/*.contract.test.ts` + `docs/contracts/*.snapshot.json`).
   백엔드 상수 분리 + 스냅샷 등재는 **백엔드 변경이라 이 PR 범위 밖**.
6. **`apps/web/e2e/` 가 어느 `tsconfig` 의 `include` 에도 없다** — `pnpm typecheck` 가 E2E 를
   타입체크하지 않는다(eslint 만 훑는다). **PRE_EXISTING**, 별도 chore 감.
7. **`BacklogCard.tsx:93` 의 `담당자: ${name}` 이 i18n 정본이 아니라 하드코딩**이고 E2E 가 그 값을
   미러 복제한다. **PRE_EXISTING** 이나 「E2E 셀렉터는 i18n 정본 참조」 과거 사고와 결이 같아
   후속 정리 대상.
8. **`board-reorder.spec.ts` S6 flaky.** 2-worker 실행 1회차 red → 단독 green → 같은 명령 2·3회차
   green. 이번 변경의 MSW 플래그는 BrowserContext 단위라 샐 수 없다. **PRE_EXISTING** 으로 판정
   (판정 근거를 단일 대조가 아니라 **반복 3회**로 세웠다 —
   [[flaky-determination-needs-repeat-not-single-contrast]]).
