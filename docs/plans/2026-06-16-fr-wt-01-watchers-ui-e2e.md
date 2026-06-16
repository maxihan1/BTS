# FR-WT-01 Watcher 프론트 D6/D7 — Watch 버튼 + 카운트 UI + E2E

> slug: fr-wt-01-watchers-ui-e2e
> type: ui
> agent: frontend-engineer
> 생성: 2026-06-16

## Brief

사용자 원문. "fr-wt-01 프론트 watch 버튼+카운트 UI 및 E2E (백엔드 D1~D5는 PR #151로 머지 완료, 후속 PR slug fr-wt-01-watchers-ui-e2e)"

FR-WT-01(이슈 Watcher 추가/제거 + 자동 Watcher)의 백엔드 D1~D5는 PR #151(squash 6e954719)로 머지 완료. 이번 작업은 남은 프론트 D6/D7.

- **D6** — Watch 버튼(토글) + watcher 카운트 UI를 이슈 상세 페이지에 통합. `GET/POST/DELETE /api/v1/issues/{key}/watchers` 호출, `isWatching`으로 버튼 상태.
- **D7** — Playwright E2E(watch/unwatch/카운트/권한 게이팅).

classify 결과. type=qa로 오판정(E2E 키워드) → ui로 교정(본체는 프론트 UI, E2E 따라붙음). 메모리 `classify E2E→qa 오판정 ui 교정` 적용.

## 도메인 정리

- **BC**: issue-tracking (프론트는 BC 경계 무관하나 소비 대상 API의 BC)
- **신규 용어**: 없음. glossary에 "워처(Watcher) — 이슈 변경 알림 수신자" 이미 정의됨.
- **영향 엔티티(읽기 전용 소비)**: Issue(상세), IssueWatcher(목록/카운트). 프론트는 신규 도메인 모델 도입 없음 — 기존 백엔드 API 소비만.
- **기존 결정 충돌**: 없음. 관련 ADR(`2026-06-02-issue-clone-semantics` cloneIssue 자동watch 제외, `2026-06-01-issue-assignee-user-lookup-port`)은 백엔드 영역, 프론트 UI에 영향 없음.

### 소비할 백엔드 API 계약 (정본: backend `com/bts/issue/watcher/web/*`, PR #151 머지됨)

| 메서드 | 경로 | 요청 | 응답 | 권한 / 에러 |
|---|---|---|---|---|
| GET | `/api/v1/issues/{key}/watchers` | — | 200 `DataResponse<WatcherListResponse>` | VIEW / 404·403 |
| POST | `/api/v1/issues/{key}/watchers` | `{ userId?: UUID }` (없으면 self) | 201 (멱등, no body) | self=VIEW·타인=UPDATE / 404·403·422 |
| DELETE | `/api/v1/issues/{key}/watchers/{userId}` | — | 204 (멱등, no body) | self=VIEW·타인=UPDATE / 404·403 |

`WatcherListResponse` = `{ watchers: [{ userId: UUID, displayName: string }], count: number, isWatching: boolean }` (`@JsonInclude(NON_NULL)`).
- GET만 `DataResponse` 래퍼(`{ data: {...} }`). POST/DELETE는 본문 없음.
- POST 멱등(이미 watch여도 201), DELETE 멱등(미존재여도 204).
- D6 범위: **self watch/unwatch + 카운트 + isWatching 버튼 상태**. 타인 추가(UPDATE 권한)는 백엔드 지원하나 UI 1차 범위는 self 토글로 한정(스펙에서 확정).

## 스펙

전체 스펙. [docs/specs/2026-06-16-fr-wt-01-watchers-ui-e2e.md](../specs/2026-06-16-fr-wt-01-watchers-ui-e2e.md)

**D6 범위(Maxi 확정 — Option A)**: self watch/unwatch 토글 + 감시자 카운트 + 감시자 명단(읽기 전용). 타인 수동 추가 UI 제외.

핵심 시나리오 3줄.
- "보기"/"보기 취소" 토글 → POST(self)/DELETE(내 userId) → 카운트·명단 갱신(invalidate-only).
- 메타패널 감시자 섹션에 카운트("N명")+명단(displayName). 자동 추가된 보고자/담당자도 표시.
- 담당자/컴포넌트 변경 시 자동 watcher 라이브 갱신(FR-7, watcher 쿼리 invalidate).

## Brainstorming Check

✅ 통과 (1회). gap 1건(자동 watcher 라이브 갱신 FR-7) 보강 완료.

## Plan

> 선례. API/훅=`api/issue-links.ts`, MSW=`mocks/issue-link-handlers.ts`, E2E=`e2e/issue-links.spec.ts`. invalidate-only(setQueryData 금지·플리커 회피). 현재 userId=`useAuthUser().userId`.

### Task 1. issue-watchers API 모듈 + React Query 훅

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/api/issue-watchers.ts`, `apps/web/src/api/issue-watchers.test.ts`]
- depends-on: []

**RED**: `issue-watchers.test.ts` — (1) `fetchWatchers(key)`가 `DataResponse` 언래핑 후 `{watchers:[{userId,displayName}],count,isWatching}` 반환, (2) `addWatcher(key)` POST 본문없음 → 201 통과, (3) `removeWatcher(key,userId)` DELETE `/{userId}` → 204, (4) `extractWatcherErrorCode`가 ApiError body에서 code 추출. `issue-links.test.ts`의 fetch mock 패턴 그대로.
**GREEN**: `issue-watchers.ts` — `dataResponseSchema(watcherListResponseSchema)` Zod 미러(백엔드 DTO 1:1), `fetchWatchers/addWatcher/removeWatcher`, `issueWatchersKey(key)=['issue-watchers',key,'list']`, 훅 `useWatchers/useAddWatcher/useRemoveWatcher`(mutation onSettled→invalidate `issueWatchersKey`), `ISSUE_WATCHER_ERROR_CODES`+`extractWatcherErrorCode`. CSRF는 issue-links 선례 따름(apiFetch 자동/수동 동일하게).
**REFACTOR**: 경로 상수화 + 함수 상단 메서드/경로/상태코드 주석.
**검증**: `pnpm --dir apps/web exec vitest run src/api/issue-watchers.test.ts`

### Task 2. WatchersSection 컴포넌트 + MSW 핸들러 + i18n

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/issue/WatchersSection.tsx`, `apps/web/src/components/issue/WatchersSection.test.tsx`, `apps/web/src/mocks/issue-watcher-handlers.ts`, `apps/web/src/mocks/handlers.ts`, `apps/web/src/i18n/ko.ts`, `apps/web/src/i18n/ko.test.ts`]
- depends-on: [1]

**RED**: `WatchersSection.test.tsx`(MSW 사용) — (1) 카운트 "N명"+명단(displayName) 렌더, (2) `isWatching=false`면 "보기" 버튼·`true`면 "보기 취소", (3) "보기" 클릭 → POST(self) → MSW store 갱신 → 카운트+1·버튼 토글, (4) "보기 취소" 클릭 → DELETE(`useAuthUser().userId`) → 카운트-1, (5) 0명이면 "감시자가 없습니다." 표시. 컴포넌트 테스트가 MSW 핸들러 부재로 먼저 실패(RED) → 핸들러 추가가 GREEN.
**GREEN**: (a) `issue-watcher-handlers.ts` — stateful `watcherStore: Map<issueKey,Set<userId>>` + `seedIssueWatchers`/`resetIssueWatcherStore`, GET/POST/DELETE 핸들러(현재 userId는 기존 auth 핸들러에서 읽어 isWatching 계산), `handlers.ts` 등록. (b) `WatchersSection.tsx` — `useWatchers`+토글 버튼+카운트+명단. **디자인 FR 반영**: 로딩/에러/빈/진행중 4상태(FR-8), 긴 목록 N명+초과 접기·본인"(나)"표기(FR-9), `aria-pressed`·키보드·44px·outline/secondary 버튼(FR-10). 라벨 "지켜보기/지켜보는 중"(Maxi 확정). (c) `ko.ts` `issueDetailStrings`에 watcher 문자열(콜론 종결 금지) + `ko.test.ts` 검증.
**REFACTOR**: data-testid/aria-label i18n 정본 재노출, 4상태 정리.
**검증**: `pnpm --dir apps/web exec vitest run src/components/issue/WatchersSection.test.tsx src/i18n/ko.test.ts`

### Task 3. IssueMetaPanel 통합 + FR-7 자동 watcher 라이브 갱신

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/issue/IssueMetaPanel.tsx`, `apps/web/src/api/issues.ts`, `apps/web/src/api/issues.test.ts`]
- depends-on: [1, 2]

**RED**: `issues.test.ts` — 담당자 변경 / 컴포넌트 변경 mutation 성공 시 `issueWatchersKey(key)`가 invalidate되는지(spy on `invalidateQueries`) 단언. (자동 watcher 라이브 갱신 FR-7.)
**GREEN**: (a) `IssueMetaPanel.tsx`에 `WatchersSection` 마운트(감시자 섹션 위치=메타패널 상단/담당자 근처). (b) `issues.ts` 담당자·컴포넌트 변경 mutation 훅 onSettled/onSuccess에 `invalidateQueries({queryKey: issueWatchersKey(key)})` 1줄 추가.
**REFACTOR**: 중복 invalidate 정리, KDoc 주석.
**검증**: `pnpm --dir apps/web exec vitest run src/api/issues.test.ts` + IssueMetaPanel 관련 기존 테스트 회귀 확인(`pnpm --dir apps/web typecheck`).

### Task 4. E2E — watch/unwatch + 카운트 + 명단 + 회귀

**메타**.
- agent: `qa-engineer`
- files: [`apps/web/e2e/issue-watchers.spec.ts`, `apps/web/e2e/fixtures/issue-fixtures.ts`]
- depends-on: [3]

**RED**: `issue-watchers.spec.ts` — loginAsAlice → 이슈 상세 진입(SPA 내부 이동) → 감시자 섹션 대기 → (1) "보기" 클릭 → 카운트 증가·명단에 본인 표시·버튼 "보기 취소"로, (2) "보기 취소" 클릭 → 카운트 감소·명단에서 본인 제거. MSW watcher store seed로 초기 상태 구성. 텍스트 중복 버튼은 감시자 섹션 컨테이너 한정(strict mode 회피).
**GREEN**: E2E 통과까지 셀렉터/대기 조정. 필요 시 `issue-fixtures.ts`에 watcher seed 헬퍼 추가.
**REFACTOR**: 상수/헬퍼 정리.
**검증**: `pnpm --dir apps/web exec playwright test issue-watchers` + 기존 `issue-` E2E 회귀 실행.

## Plan 메타

- task 수: 4
- 예상 wave: Wave1=T1 → Wave2=T2 → Wave3=T3 → Wave4=T4 (대부분 직렬 — 코드 의존 + 공유 파일). 병렬 여지 적음.
- TDD 강제: yes (RED→GREEN→REFACTOR, test: 커밋이 feat: 보다 선행).
- 추가 검증: typecheck(tsconfig.app), ktlint/detekt 해당없음(프론트 전용), vitest, playwright(qa).
- 회귀 주의: IssueMetaPanel은 공유 컴포넌트 → 기존 테스트/E2E 동반 실행. issues.ts mock fanout 점검.

## 리뷰 결과

### plan-design-review (2026-06-16)

**초기 평점 6/10 → 반영 후 9/10.** mockup/비교보드 플로우는 스킵 — 기존 메타패널에 표준 컴포넌트(토글 버튼+카운트+읽기전용 명단) 추가라 새 비주얼 디자인 부재, DESIGN.md 기존 규칙 준수. 집중 텍스트 검토로 진행.

- ✅ 통과: API 계약 명확, invalidate-only 플리커 방지, FR-7 자동 watcher 라이브 갱신, 권한 게이팅 단순(VIEW=상세열람으로 충족).
- 🔧 반영(스펙 FR-8~10 추가): (1) 4상태 표현(로딩/에러/빈/진행중), (2) 1,000명 조직 긴 목록 처리(N명+접기·본인 "(나)" 표기), (3) 접근성 WCAG AA(aria-pressed 토글·키보드·44px·outline 버튼).
- ✅ **RESOLVED (게이트1 Maxi 확정)**: 버튼 라벨 = "지켜보기 / 지켜보는 중"(Jira Watch/Watching). 미감시→"지켜보기", 감시중→"지켜보는 중".
- BLOCKER: 없음.

### eng 집중 리뷰 (2026-06-16, self)

작은 프론트 후속이라 autoplan overkill 회피, 핵심 기술 리스크만 점검.
- ⚠️ 주의(반영됨): IssueMetaPanel은 공유 컴포넌트(많은 props·기존 테스트) → WatchersSection은 `issue.key`만 받는 자체-훅 컴포넌트로 prop drilling 회피. 기존 IssueMetaPanel 테스트/E2E 동반 실행(회귀).
- ⚠️ 주의(반영됨): FR-7로 `issues.ts` 담당자/컴포넌트 mutation 수정 시 mock fanout — issues.test.ts 기존 단언 깨지지 않게 invalidate 추가만(메모리 "Zod 스키마 강화 mock 파급" 동형 주의).
- ⚠️ 주의: self-unwatch는 `useAuthUser().userId` 필요 — 미인증 시 버튼 비활성(라우트 가드가 1차 방어).
- ⚠️ 주의: 텍스트 중복 버튼(메타패널에 '저장' 등 다수) → E2E 셀렉터는 감시자 섹션 컨테이너 한정(메모리 "UI PR이 E2E 미루면 회귀 잠복").
- BLOCKER: 없음.
