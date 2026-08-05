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
4. **`board-reorder.spec.ts` S6 flaky.** 2-worker 실행 1회차 red → 단독 green → 같은 명령 2·3회차
   green. 이번 변경의 MSW 플래그는 BrowserContext 단위라 샐 수 없다. **PRE_EXISTING** 으로 판정
   (판정 근거를 단일 대조가 아니라 **반복 3회**로 세웠다 —
   [[flaky-determination-needs-repeat-not-single-contrast]]).
