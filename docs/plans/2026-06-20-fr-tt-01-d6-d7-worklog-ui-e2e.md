# FR-TT-01 D6/D7 — Worklog 프론트 UI + E2E

> slug: fr-tt-01-d6-d7-worklog-ui-e2e
> type: ui
> agent: frontend-engineer
> primary_bc: issue-tracking (백엔드), 프론트 apps/web
> 생성: 2026-06-20

## Brief

FR-TT-01 — Worklog (추정/실제/잔여 시간)의 **D6(프론트 UI) + D7(E2E)**. 백엔드 D1~D5는 #163으로 머지 완료.

- 원문: "fr-tt-01 d6, d7 진행해줘"
- classify: type=qa 오판정 → **ui 교정**(D6 프론트가 주작업, D7 E2E는 qa-engineer 단일 task). 선례 #155/#158/#164.
- product: docs/plan/product/agile-planning.md §5.1
  - [ ] D6. 프론트 UI — Worklog 입력 폼 + 잔여 시간 자동 계산 (designer → frontend-engineer)
  - [ ] D7. E2E (qa-engineer)
- 백엔드 계약(#163 산출): `POST/GET/PATCH/DELETE /api/v1/issues/{key}/worklogs`, 추정 PATCH(`PATCH /issues/{key}` — originalEstimateSeconds/remainingEstimateSeconds), IssueResponse 3필드(originalEstimateSeconds/timeSpentSeconds/remainingEstimateSeconds, `@JsonInclude(NON_NULL)`), 잔여 자동차감 max(0, remaining−timeSpent) 또는 newRemaining override

## 도메인 정리

- **BC**: issue-tracking (백엔드), 프론트는 apps/web
- **신규 도메인 변경**: 없음 — D6/D7은 #163이 확정한 백엔드 계약(엔드포인트·DTO 필드)을 프론트에서 소비하는 view layer + E2E. 새 엔티티/관계/불변식 0.
- **상속 ADR**: [docs/adr/2026-06-20-worklog-time-tracking-model.md](../adr/2026-06-20-worklog-time-tracking-model.md) (#163 생성, 변경 없음)
- **용어**: Worklog / Original Estimate / Time Spent / Remaining Estimate / Log Work — #163에서 도입. glossary는 수동 영역으로 미반영 상태(머지 게이트에서 Maxi 승인 시 일괄 추가 후보). D6/D7에서 신규 도메인 용어 추가 없음(UI 라벨은 도메인 용어 아님).
- **기존 결정 충돌**: 없음
- **grill-with-docs**: 스킵 — 도메인 모델 변경 0인 view layer 작업에 대화형 도메인 검증은 비용>효익(선례 #155/#164/#158 D6/D7 동일).

## 스펙

전체 스펙. [docs/specs/2026-06-20-fr-tt-01-d6-d7-worklog-ui-e2e.md](../specs/2026-06-20-fr-tt-01-d6-d7-worklog-ui-e2e.md)

**Maxi 확정(스펙 게이트)**: ① 시간=시간(h)+분(m) 분리 입력, 표시 "2h 30m" ② 풀 범위(추정 편집 + worklog 풀 CRUD + 잔여 자동계산).

핵심 시나리오 요약.
- 추정 카드(원추정/기록/잔여 "2h 30m" 표시 + original/remaining PATCH 3-state·OCC), timeSpent 읽기전용
- Worklog 섹션(목록 + 추가/수정/삭제, 본인만 수정/삭제) — AttachmentSection 패턴, 자체 query/mutation
- 잔여 자동차감 미리보기(자동=newRemaining undefined / 직접지정=값), startedAt datetime-local↔ISO
- ★ worklog mutation 후 issue query cross-invalidate(추정 카드 갱신, FR9)

## Brainstorming Check

✅ 통과 (자체 적대적 갭 분석, 8 갭 보강). 핵심.
- G8 cross-invalidate(worklog→issue query, 추정 카드 stale 방지) → FR9
- G7 Zod `.nullable().optional()`(mock fanout 회피, startDate 선례)
- G2/G3 authorId→useUsersByIds / 현재사용자=whoami userId(WatchersSection 선례)
- G9 D7 MSW stateful store(자동차감 재현, reload 금지)

## Plan

> 범위 D6(프론트 UI) + D7(E2E). 경로 prefix: `apps/web/src/`. 백엔드 변경 0.
> 모든 Zod/DTO는 #163 백엔드 계약 실측 미러(invent 금지). 추정 3필드는 `@JsonInclude(NON_NULL)` 미적용(키 항상 존재) — 그래도 mock fanout 회피 위해 `.nullable().optional()`.

### Task 1. duration 유틸 — parseHm / formatSeconds 순수 함수

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/lib/duration.ts`, `apps/web/src/lib/duration.test.ts`]
- depends-on: []

**RED**: `duration.test.ts` — `parseHm({hours:2,minutes:30})===9000`, `parseHm({hours:0,minutes:0})===0`, `formatSeconds(9000)==='2h 30m'`, `formatSeconds(2700)==='45m'`, `formatSeconds(0)==='0m'`, `formatSeconds(3600)==='1h 0m'`, `formatSeconds(360000)==='100h 0m'`(일/주 단위 변환 없음, E8). 실패: 모듈 없음.

**GREEN**: `parseHm = ({hours,minutes}) => hours*3600 + minutes*60`. `formatSeconds`: `h=floor(s/3600)`, `m=floor((s%3600)/60)`, h>0이면 `"{h}h {m}m"` 아니면 `"{m}m"`. 음수 입력 가드(0 floor).

**REFACTOR**: JSDoc + 타입 export(`HourMinute`).

**검증**: `pnpm --filter web test duration`.

### Task 2. api/issues.ts — 추정 Zod 3필드 + UpdateIssueInput 추정 필드

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/api/issues.ts`, `apps/web/src/api/issues.test.ts`]
- depends-on: []

**RED**: `issues.test.ts`(또는 신규) — (a) `issueResponseSchema.parse`가 `originalEstimateSeconds:number|null`, `timeSpentSeconds:number`, `remainingEstimateSeconds:number|null` 파싱(null·값·키부재 모두 OK), (b) `updateIssue(key,{originalEstimateSeconds:3600, remainingEstimateSeconds:null, expectedVersion})` 호출 시 PATCH body에 두 필드 포함(null=클리어, undefined=미포함). 실패: 필드 없음.

**GREEN**:
- `issueResponseSchema`에 추가: `originalEstimateSeconds: z.number().nullable().optional()`, `timeSpentSeconds: z.number().optional()`(기본 0 보장 위해 `.optional()` 후 소비측 `?? 0`), `remainingEstimateSeconds: z.number().nullable().optional()` — startDate 선례(`.nullable().optional()`)로 **기존 mock fanout 회피**.
- `UpdateIssueInput`에 `originalEstimateSeconds?: number|null`, `remainingEstimateSeconds?: number|null` 추가(startDate 3-state 패턴 그대로). `updateIssue` body 직렬화는 기존 JsonNullable 매핑 흐름(undefined=미포함) 재사용.
- **mock fanout 점검(G7)**: `grep -rn "issueResponseSchema\|IssueResponse" apps/web/src --include=*.ts --include=*.tsx | grep -i mock/fixture` 전수 → `.optional()`이라 추가 불요 확인. tsc 통과 단언.

**REFACTOR**: KDoc(백엔드 NON_NULL 미적용·nullable 본질 명시).

**검증**: `pnpm --filter web test issues` + `pnpm --filter web typecheck`.

### Task 3. api/worklogs.ts (신규) — Zod 스키마 + CRUD 함수

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/api/worklogs.ts`, `apps/web/src/api/worklogs.test.ts`]
- depends-on: []

**RED**: `worklogs.test.ts` — `worklogResponseSchema`/`worklogListResponseSchema`(summary 포함) 파싱, `fetchWorklogs(key)`가 `GET /api/v1/issues/{key}/worklogs`→`data.{worklogs,summary}` 언랩, `addWorklog(key,{timeSpentSeconds,startedAt,comment,newRemainingEstimateSeconds})` POST body, `updateWorklog(key,id,{...})` PATCH, `deleteWorklog(key,id)` DELETE 204. 실패: 모듈 없음.

**GREEN**: attachments.ts 패턴 — `dataResponseSchema(inner)` wrapper 재사용/로컬정의. 스키마(실측): `worklogResponseSchema={id:uuid, issueKey:string, authorId:uuid, timeSpentSeconds:number, startedAt:string, comment:string.nullable(), createdAt:string, updatedAt:string}`, `worklogSummarySchema={originalEstimateSeconds:number.nullable(), timeSpentSeconds:number, remainingEstimateSeconds:number.nullable()}`. CRUD는 `apiFetch`/`apiGet`(기존 client 패턴, CSRF 자동 — [[frontend-api-convention-per-bc]] same-BC grep). 빈 필드 미전송(undefined).

**REFACTOR**: 타입 export(`WorklogResponse`/`WorklogSummary`/`WorklogListResponse`), KDoc.

**검증**: `pnpm --filter web test worklogs` + `typecheck`.

### Task 4. i18n/ko.ts — Worklog/추정 라벨·토스트

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/i18n/ko.ts`, `apps/web/src/i18n/ko.test.ts`]
- depends-on: []

**RED**: 기존 `ko.test.ts`(콜론 문장종결 금지 자동검증) green 유지 + 신규 키 존재 단언(필요 시). 키: 추정(원추정/기록시간/잔여추정 라벨, 추정저장 버튼/aria, 추정저장 에러), Worklog(섹션 제목, 추가 폼 라벨 시간h·분m·시작시각·코멘트·자동조정토글·직접지정·미리보기 "기록 후 잔여", 추가/수정/삭제 버튼·aria, 빈상태, 작성자, 로딩/에러 토스트). 콜론 종결 금지.

**GREEN**: `issueDetailStrings`(또는 신규 `worklogStrings`) 객체에 라벨 추가. 문장 종결 `.`/`!`/`?`만.

**REFACTOR**: 그룹핑 주석.

**검증**: `pnpm --filter web test ko` + `typecheck`.

### Task 5. IssueEstimatePanel — 추정 카드(표시 + PATCH 편집)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/issue/IssueEstimatePanel.tsx`, `apps/web/src/components/issue/IssueEstimatePanel.test.tsx`]
- depends-on: [1, 2, 4]

**RED**: `IssueEstimatePanel.test.tsx` — (a) summary 표시(formatSeconds로 원추정/기록/잔여, timeSpent 읽기전용), (b) 추정 미설정 시 안내(E1), (c) 원추정·잔여 h+m 편집 후 저장→`updateIssue(key,{originalEstimateSeconds, remainingEstimateSeconds, expectedVersion})` 호출(빈 입력→null 클리어, 3-state), (d) 409→충돌 toast+invalidate(E6), (e) `disabled`(canEdit=false)→입력·저장 disabled(fail-closed, S8). 실패: 컴포넌트 없음.

**GREEN**: IssueScheduleFields 패턴 복제 — `issue: IssueResponse` props, 로컬 draft(h/m state), useEffect로 issue 변경 시 동기화(stale 방지), `updateIssue` 호출, 409 처리, `invalidateQueries(issueQueryKey)`. 시간 입력=시간/분 number input 2개, `parseHm`/`formatSeconds` 사용. timeSpent는 표시만.

**REFACTOR**: 입력 행 추출, JSDoc.

**검증**: `pnpm --filter web test IssueEstimatePanel` + `typecheck`.

### Task 6. WorklogSection — 목록 + 추가/수정/삭제 + 자동계산 미리보기

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/issue/WorklogSection.tsx`, `apps/web/src/components/issue/WorklogSection.test.tsx`]
- depends-on: [1, 3, 4]

**RED**: `WorklogSection.test.tsx` — (a) 목록 표시(formatSeconds 시간, `useUsersByIds`로 authorId→displayName, startedAt `toLocaleString` 로컬, comment), 빈상태(E7), (b) 추가 폼(시간 h+m 필수>0·0h0m이면 추가 disabled E3, datetime-local 기본 현재, 코멘트, 자동조정/직접지정 토글), (c) 자동차감 미리보기("기록 후 잔여 = max(0, remaining−입력)", remaining null이면 숨김 E2/FR6), (d) 추가 성공→`addWorklog` 호출 + **issueQueryKey & worklog query 둘 다 invalidate(★FR9 cross-invalidate)**, (e) 본인 worklog만 수정/삭제 버튼 노출(authorId===whoami userId, S7/E5), (f) 수정/삭제→PATCH/DELETE+invalidate, (g) `disabled`(canEdit=false)→추가/수정/삭제 disabled(S8). 실패: 컴포넌트 없음.

**GREEN**: AttachmentSection 패턴 — `{ issueKey, canUpdate }` props, `useQuery(fetchWorklogs)`, `useMutation`(add/update/delete) onSuccess에서 `invalidateQueries(worklogQueryKey)` **+ `invalidateQueries(issueQueryKey)`**(cross-invalidate). 현재 사용자=whoami(WatchersSection의 user 훅 재사용), `useUsersByIds(authorIds)` displayName. 미리보기는 폼 입력값 기준 로컬 계산(현재 remaining=props 또는 summary). datetime-local↔ISO 변환.

**REFACTOR**: 추가 폼·목록 아이템 하위 컴포넌트 추출, JSDoc.

**검증**: `pnpm --filter web test WorklogSection` + `typecheck`.

### Task 7. routes/issues.$key.tsx — 추정 카드 + WorklogSection 배치

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/routes/issues.$key.tsx`]
- depends-on: [5, 6]

**RED**: 기존 issue 상세 테스트(있으면) green 유지 + 추정 카드(메타 인근, IssueScheduleFields 카드 하단)·WorklogSection(하단 전체폭, AttachmentSection 근처) 렌더 + `canEdit`/`issue` props 전달. WorklogSection은 현재 remaining 전달 위해 `issue` 또는 summary 경유. 실패: 미배치.

**GREEN**: `<IssueEstimatePanel issue={issue} disabled={!canEdit} />`(스케줄 카드 인근), `<WorklogSection issueKey={issue.key} canUpdate={canEdit} />`(하단). import 추가.

**REFACTOR**: -

**검증**: `pnpm --filter web test issues.$key` + `typecheck` + `lint`.

### Task 8. E2E (D7) — Worklog happy path + 권한/타인 차단

**메타**.
- agent: `qa-engineer`
- files: [`apps/web/e2e/worklog.spec.ts`, `apps/web/src/mocks/handlers/*`(worklog MSW stateful), `apps/web/e2e/fixtures/*`(필요 시)]
- depends-on: [7]

**RED/GREEN**: MSW **stateful 핸들러**(worklog POST/GET/PATCH/DELETE + 자동차감 재현: remaining=max(0,r−t), 직접지정 override — [[msw-derived-behavior-shared-store-e2e]] 브라우저 시드가능 공유 store). `worklog.spec.ts` 시나리오: ① 추정 설정→summary 갱신 ② 30m 기록(자동차감)→기록/잔여 갱신+목록 1건 ③ 본인 worklog 수정→재집계 ④ 삭제→재집계(잔여 미복원) ⑤ 권한 없음(canEdit=false) disabled ⑥ 타인 worklog 수정/삭제 버튼 미노출. **reload/goto 금지**(SPA 내부 이동, store 영속), CSRF 쿠키 수동 시드, 텍스트 중복 버튼 컨테이너 한정([[playwright-getbyrole-exact-strict-mode]]). whoami userId↔worklog authorId fixture 정합([[e2e-fixture-whoami-userid-alignment]]).

**검증**: `pnpm --filter web test:e2e worklog` + 기존 issue E2E 무회귀(`pnpm --filter web test:e2e`).

## Plan 메타

- task 수: 8
- 예상 wave: 4 (W1: T1·T2·T3·T4 / W2: T5·T6 / W3: T7 / W4: T8) — **단, 프론트 단일 패키지 lint-staged race + vitest 공유로 직렬 dispatch 권장**([[parallel-dispatch-precommit-hook-race]], 선례 #160/#162 직렬). bts-impl이 wave 계산하되 자기 파일만 명시 stage.
- TDD 강제: yes (각 task RED→GREEN→REFACTOR)
- 병렬 dispatch: depends-on + files 교집합으로 wave 계산. T1~T4 files 무교집합, T5↔T6 다른 컴포넌트 파일 무충돌.
- 추가 검증: typecheck, lint(eslint), vitest, playwright(qa). CI typecheck는 tsconfig.app.json([[ci-typecheck-tsconfig-app-vs-local]]) → `pnpm --filter web typecheck`로 동일검증.
- 핵심 회귀 가드: ★FR9 cross-invalidate(T6), Zod 백엔드 1:1 실측(T2/T3), 본인 worklog 게이팅(T6), MSW stateful 자동차감(T8).

## 리뷰 결과 (← /bts-review-plan 채움)
