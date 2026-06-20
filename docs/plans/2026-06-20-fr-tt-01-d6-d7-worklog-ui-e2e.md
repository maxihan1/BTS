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

### Task 1. [x] duration 유틸 — parseHm / formatSeconds 순수 함수

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/lib/duration.ts`, `apps/web/src/lib/duration.test.ts`]
- depends-on: []

**RED**: `duration.test.ts` — `parseHm({hours:2,minutes:30})===9000`, `parseHm({hours:0,minutes:0})===0`, `formatSeconds(9000)==='2h 30m'`, `formatSeconds(2700)==='45m'`, `formatSeconds(0)==='0m'`, `formatSeconds(3600)==='1h 0m'`, `formatSeconds(360000)==='100h 0m'`(일/주 단위 변환 없음, E8). 실패: 모듈 없음.

**GREEN**: `parseHm = ({hours,minutes}) => hours*3600 + minutes*60`. `formatSeconds`: `h=floor(s/3600)`, `m=floor((s%3600)/60)`, h>0이면 `"{h}h {m}m"` 아니면 `"{m}m"`. 음수 입력 가드(0 floor).

**REFACTOR**: JSDoc + 타입 export(`HourMinute`).

**검증**: `pnpm --filter web test duration`.

### Task 2. [x] api/issues.ts — 추정 Zod 3필드 + UpdateIssueInput 추정 필드

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

### Task 3. [x] api/worklogs.ts (신규) — Zod 스키마 + CRUD 함수

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/api/worklogs.ts`, `apps/web/src/api/worklogs.test.ts`]
- depends-on: []

**RED**: `worklogs.test.ts` — `worklogResponseSchema`/`worklogListResponseSchema`(summary 포함) 파싱, `fetchWorklogs(key)`가 `GET /api/v1/issues/{key}/worklogs`→`data.{worklogs,summary}` 언랩, `addWorklog(key,{timeSpentSeconds,startedAt,comment,newRemainingEstimateSeconds})` POST body, `updateWorklog(key,id,{...})` PATCH, `deleteWorklog(key,id)` DELETE 204. 실패: 모듈 없음.

**GREEN**: attachments.ts 패턴 — `dataResponseSchema(inner)` wrapper 재사용/로컬정의. 스키마(실측): `worklogResponseSchema={id:uuid, issueKey:string, authorId:uuid, timeSpentSeconds:number, startedAt:string, comment:string.nullable(), createdAt:string, updatedAt:string}`, `worklogSummarySchema={originalEstimateSeconds:number.nullable(), timeSpentSeconds:number, remainingEstimateSeconds:number.nullable()}`. CRUD는 `apiFetch`/`apiGet`(기존 client 패턴 — attachments/watchers 동일, **JWT Bearer→CSRF skip이라 X-XSRF 헤더 불요**, custom-fields.ts 수동 XSRF 미추종 [[frontend-api-convention-per-bc]] same-BC grep). 빈 필드 미전송(undefined).
- **eng-C2 (summary는 `.optional()` 금지)**: `WorklogSummary`는 NON_NULL 미적용=키 항상 존재 → 추정 필드 `z.number().nullable()`만(`.optional()` 붙이면 회귀감지 약화). IssueResponse 3필드(fanout 회피용 `.optional()`)와 **다름**.
- **eng-C1 (comment 클리어 불가)**: `UpdateWorklogRequest.comment`는 3-state 아님(백엔드 KDoc: null=기존 유지). `updateWorklog`에서 `comment:null`은 "무변경"이지 "클리어"가 아님 — 클리어 sentinel 없음(빈문자열 전송 시 그 값으로 설정). 미수정 필드는 전송 생략(undefined).

**REFACTOR**: 타입 export(`WorklogResponse`/`WorklogSummary`/`WorklogListResponse`), KDoc.

**검증**: `pnpm --filter web test worklogs` + `typecheck`.

### Task 4. [x] i18n/ko.ts — Worklog/추정 라벨·토스트

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/i18n/ko.ts`, `apps/web/src/i18n/ko.test.ts`]
- depends-on: []

**RED**: 기존 `ko.test.ts`(콜론 문장종결 금지 자동검증) green 유지 + 신규 키 존재 단언(필요 시). 키: 추정(원추정/기록시간/잔여추정 라벨, 추정저장 버튼/aria, 추정저장 에러), Worklog(섹션 제목, 추가 폼 라벨 시간h·분m·시작시각·코멘트·자동조정토글·직접지정·미리보기 "기록 후 잔여", 추가/수정/삭제 버튼·aria, 빈상태, 작성자, 로딩/에러 토스트). 콜론 종결 금지.

**GREEN**: `issueDetailStrings`(또는 신규 `worklogStrings`) 객체에 라벨 추가. 문장 종결 `.`/`!`/`?`만.

**REFACTOR**: 그룹핑 주석.

**검증**: `pnpm --filter web test ko` + `typecheck`.

### Task 5. [x] IssueEstimatePanel — 추정 카드(표시 + PATCH 편집)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/issue/IssueEstimatePanel.tsx`, `apps/web/src/components/issue/IssueEstimatePanel.test.tsx`]
- depends-on: [1, 2, 4]

**RED**: `IssueEstimatePanel.test.tsx` — (a) summary 표시(formatSeconds로 원추정/기록/잔여, timeSpent 읽기전용), (b) 추정 미설정 시 안내(E1), (c) 원추정·잔여 h+m 편집 후 저장→`updateIssue(key,{originalEstimateSeconds, remainingEstimateSeconds, expectedVersion})` 호출(빈 입력→null 클리어, 3-state), (d) 409→충돌 toast+invalidate(E6), (e) `disabled`(canEdit=false)→입력·저장 disabled(fail-closed, S8). 실패: 컴포넌트 없음.

**GREEN**: IssueScheduleFields 패턴 복제 — `issue: IssueResponse` props, 로컬 draft(h/m state), useEffect로 issue 변경 시 동기화(stale 방지), `updateIssue` 호출, 409 처리, `invalidateQueries(issueQueryKey)`. 시간 입력=시간/분 number input 2개, `parseHm`/`formatSeconds` 사용. timeSpent는 표시만.
- **design-C1 (카드 wrapper는 route가)**: IssueEstimatePanel 내부는 **입력 행만** 렌더(카드 테두리/제목 X). 카드 wrapper(`border border-border rounded-xl px-3.5 py-3` + `text-xs text-muted-foreground` 라벨)는 Task 7의 route가 IssueScheduleFields와 동일하게 그림.

**REFACTOR**: 입력 행 추출, JSDoc.

**검증**: `pnpm --filter web test IssueEstimatePanel` + `typecheck`.

### Task 6. [x] WorklogSection — 목록 + 추가/수정/삭제 + 자동계산 미리보기

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/issue/WorklogSection.tsx`, `apps/web/src/components/issue/WorklogSection.test.tsx`]
- depends-on: [1, 3, 4]

**RED**: `WorklogSection.test.tsx` — (a) 목록 표시(formatSeconds 시간, `useUsersByIds`로 authorId→displayName, startedAt `toLocaleString` 로컬, comment), 빈상태(E7), (b) 추가 폼(시간 h+m 필수>0·0h0m이면 추가 disabled E3, datetime-local 기본 현재, 코멘트, 자동조정/직접지정 토글), (c) 자동차감 미리보기("기록 후 잔여 = max(0, remaining−입력)", remaining null이면 숨김 E2/FR6), (d) 추가 성공→`addWorklog` 호출 + **issueQueryKey & worklog query 둘 다 invalidate(★FR9 cross-invalidate)**, (e) 본인 worklog만 수정/삭제 버튼 노출(authorId===whoami userId, S7/E5), (f) 수정/삭제→PATCH/DELETE+invalidate, (g) `disabled`(canEdit=false)→추가/수정/삭제 disabled(S8). 실패: 컴포넌트 없음.

**GREEN**: AttachmentSection 패턴 — `{ issueKey, canUpdate }` props, `useQuery(fetchWorklogs)`, `useMutation`(add/update/delete) onSuccess에서 `invalidateQueries(worklogQueryKey)` **+ `invalidateQueries(issueQueryKey)`**(cross-invalidate). 현재 사용자=whoami(WatchersSection의 `useAuthUser()` 재사용), `useUsersByIds(authorIds)` displayName.
- **eng-C4 (remaining 단일출처)**: 자동차감 미리보기 = `max(0, summary.remainingEstimateSeconds − 입력초)`. remaining 출처는 **`summary.remainingEstimateSeconds`(fetchWorklogs 응답) 단일 출처**(IssueResponse 경유 금지 — cross-invalidate 타이밍에 두 값 어긋남). summary null이면 미리보기 숨김(E2).
- **design-C2/C4**: 섹션 제목 `text-sm font-semibold text-foreground`(AttachmentSection 일치). 1차 액션 `min-h-[44px]`, 행 인라인 수정/삭제 `min-h-[32px]`. 빈상태 `text-sm text-muted-foreground`.
- **design-C3**: "잔여 직접 지정" 단일 체크박스 — 체크 시에만 직접지정 h+m 입력 **조건부 렌더**(미체크=자동, `newRemainingEstimateSeconds` 미전송). datetime-local↔ISO 변환.

**REFACTOR**: 추가 폼·목록 아이템 하위 컴포넌트 추출, JSDoc.

**검증**: `pnpm --filter web test WorklogSection` + `typecheck`.

### Task 7. [x] routes/issues.$key.tsx — 추정 카드 + WorklogSection 배치

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/routes/issues.$key.tsx`]
- depends-on: [5, 6]

**RED**: 기존 issue 상세 테스트(있으면) green 유지 + 추정 카드(메타 인근, IssueScheduleFields 카드 하단)·WorklogSection(하단 전체폭, AttachmentSection 근처) 렌더 + `canEdit`/`issue` props 전달. WorklogSection은 현재 remaining 전달 위해 `issue` 또는 summary 경유. 실패: 미배치.

**GREEN**: `<IssueEstimatePanel issue={issue} disabled={!canEdit} />`(스케줄 카드 인근), `<WorklogSection issueKey={issue.key} canUpdate={canEdit} />`(하단). import 추가.

**REFACTOR**: -

**검증**: `pnpm --filter web test issues.$key` + `typecheck` + `lint`.

### Task 8. [x] E2E (D7) — Worklog happy path + 권한/타인 차단

**메타**.
- agent: `qa-engineer`
- files: [`apps/web/e2e/worklog.spec.ts`, `apps/web/src/mocks/handlers/*`(worklog MSW stateful), `apps/web/e2e/fixtures/*`(필요 시)]
- depends-on: [7]

**RED/GREEN**: MSW **stateful 핸들러**(worklog POST/GET/PATCH/DELETE + 자동차감 재현 — [[msw-derived-behavior-shared-store-e2e]] 브라우저 시드가능 공유 store). **백엔드 시맨틱 정확 재현(eng-C5, 가짜그린 방지)**:
- POST: timeSpent SUM 재집계 + `newRemainingEstimateSeconds` 미전송이면 `remaining=max(0, r−t)`, 전송이면 그 값 override.
- **PATCH/DELETE: timeSpent만 재합산, remaining 불변**(자동차감 안 함).
`worklog.spec.ts` 시나리오: ① 추정 설정→summary 갱신 ② 30m 기록(자동차감)→기록/잔여 갱신+목록 1건 ③ 본인 worklog 수정→timeSpent 재집계(remaining 불변) ④ 삭제→timeSpent 재집계(remaining 미복원) ⑤ 권한 없음(canEdit=false) disabled ⑥ 타인 worklog 수정/삭제 버튼 미노출. **reload/goto 금지**(SPA 내부 이동, store 영속), CSRF 쿠키 수동 시드, 텍스트 중복 버튼 컨테이너 한정([[playwright-getbyrole-exact-strict-mode]]). whoami userId↔worklog authorId fixture 정합([[e2e-fixture-whoami-userid-alignment]]).

**검증**: `pnpm --filter web test:e2e worklog` + 기존 issue E2E 무회귀(`pnpm --filter web test:e2e`).

## Plan 메타

- task 수: 8
- 예상 wave: 4 (W1: T1·T2·T3·T4 / W2: T5·T6 / W3: T7 / W4: T8) — **단, 프론트 단일 패키지 lint-staged race + vitest 공유로 직렬 dispatch 권장**([[parallel-dispatch-precommit-hook-race]], 선례 #160/#162 직렬). bts-impl이 wave 계산하되 자기 파일만 명시 stage.
- TDD 강제: yes (각 task RED→GREEN→REFACTOR)
- 병렬 dispatch: depends-on + files 교집합으로 wave 계산. T1~T4 files 무교집합, T5↔T6 다른 컴포넌트 파일 무충돌.
- 추가 검증: typecheck, lint(eslint), vitest, playwright(qa). CI typecheck는 tsconfig.app.json([[ci-typecheck-tsconfig-app-vs-local]]) → `pnpm --filter web typecheck`로 동일검증.
- 핵심 회귀 가드: ★FR9 cross-invalidate(T6), Zod 백엔드 1:1 실측(T2/T3), 본인 worklog 게이팅(T6), MSW stateful 자동차감(T8).

## 리뷰 결과

type=ui → design 관점 + eng 정합성 관점 **독립 병렬 리뷰**(선례 #160/#164 두 리뷰 병행 정석). 둘 다 **BLOCKER 0**.

### plan-design-review (디자인/UX, 2026-06-20)

DESIGN.md + 선례 3컴포넌트(IssueScheduleFields/AttachmentSection/WatchersSection) 실측 대조. BLOCKER 없음.
- 🟡 **C1 (반영 → Task 5/7)**: 추정 카드 wrapper는 IssueScheduleFields처럼 **route(issues.$key.tsx)가** `border border-border rounded-xl px-3.5 py-3` + `text-xs text-muted-foreground` 라벨로 감싸고, IssueEstimatePanel 내부는 입력 행만 렌더(self-wrap 시 위계 drift).
- 🟡 **C2 (반영 → Task 6)**: WorklogSection 섹션 제목 토큰 = AttachmentSection의 `text-sm font-semibold text-foreground`(인접 배치 일관).
- 🟡 **C3 (반영 → Task 6)**: 직접지정 h+m 입력은 토글=직접일 때만 **조건부 렌더**(기본 밀도 절제), 토글은 단일 체크박스 "잔여 직접 지정" 권장.
- 🟡 **C4 (반영 → Task 6)**: 1차 액션(추가/저장) `min-h-[44px]`, 목록 행 인라인 수정/삭제는 AttachmentSection 선례대로 `min-h-[32px]`.
- 🟡 **C5 (반영 → Task 4/5/6)**: 빈상태(E7)/추정 미설정(E1) 안내 = `text-sm text-muted-foreground`(선례 일치).
- 🟢 배치·상태표현·WCAG·의존성0·시간표시 일관성·i18n 콜론종결 게이트 모두 통과.

### plan-eng-review (프론트 정합성, 2026-06-20)

백엔드 계약 전부 실측 대조(WorklogController/DTO/IssueResponse/UpdateIssueRequest). BLOCKER 없음. Zod 1:1·FR9 cross-invalidate·OCC·게이팅·시간변환 모두 정합 확인.
- 🟡 **C1 (반영 → Task 3)**: `UpdateWorklogRequest.comment`는 **3-state 아님**(null=무변경, 클리어 불가). 구현자가 `comment:null`을 클리어로 오해하면 가짜그린 → "클리어 sentinel 없음, 빈문자열 전송 시 그 값으로 설정" 명시.
- 🟡 **C2 (반영 → Task 3)**: `WorklogSummary`는 NON_NULL 미적용=키 항상 존재라 **mock fanout 없음** → summary 추정 필드는 `.nullable()`만(`.optional()` 금지, 회귀감지 약화 방지). IssueResponse 3필드와 구분.
- 🟡 **C3 (확인 — Task 2 이미 반영)**: IssueResponse 3필드 `.nullable().optional()`은 **fixture fanout 회피용**(startDate 선례 동형, 6개 fixture estimate 0개). 본질은 `.nullable()` — KDoc에 "백엔드 항상 직렬화, optional은 fixture 보호용" 명시(Task 2 REFACTOR 기지시).
- 🟡 **C4 (반영 → Task 6)**: 자동차감 미리보기 remaining 출처는 **summary.remainingEstimateSeconds 단일 출처**(IssueResponse 경유 시 cross-invalidate 타이밍에 두 값 어긋남). 백엔드 `timeSpent`=이번 추가분(누적 아님).
- 🟡 **C5 (반영 → Task 8)**: MSW stateful은 자동차감뿐 아니라 **PATCH/DELETE는 timeSpent만 재합산·remaining 불변**(백엔드 시맨틱)을 재현해야 가짜그린 회피.
- 🟢 부가 검증: CSRF는 attachments/watchers 패턴(JWT Bearer→CSRF skip, X-XSRF 헤더 불요)이 맞음. custom-fields.ts 수동 XSRF는 따라가지 말 것.

### 구현 검증 (D6/D7, 2026-06-20)

- 8 task 전부 TDD(test→feat 순서 git log 확인) + controller 직접 검증 PASS.
- worklog E2E 5 passed(S1 추정설정·S2 자동차감·S3 수정 remaining불변·S4 삭제 remaining미복원·S6 본인 버튼노출). S5(권한없음)·타인(isOwner=false)은 단위(WorklogSection.test b2/e2)로 커버, E2E SKIP 사유 spec 명시.
- 전체 unit 3240 passed / typecheck / lint 클린.
- **auth-fixtures userId v4 형식 변경**(00000000-0000-4000-8000-00...x): worklog authorId가 첫 `z.string().uuid()` 소비처라 기존 all-zeros fixture가 Zod v4 통과 못 함([[zod-v4-uuid-fixture-strictness]] 권고). 파급 5파일(project-member-fixtures/handlers·issue-move/watcher-handlers·테스트) 정합. **직접 영향권 회귀 세트 16 passed**(issue-watchers/project-member-management/issue-schedule/issue-crud-happy/issue-move).
- **workflow-scheme-* E2E 실패는 PRE_EXISTING**: main(c9ac548d) baseline에서 동일 fill-timeout 재현 → 우리 변경 무관. 원인=workflow-scheme-fixtures.loginAsAlice FR-AU-07 1단계 미해결([[e2e-loginasalice-fixture-fr-au-07-regression]]). 본 PR 범위 밖(FR-AU-07 후속).

### 반영 요약

위 10개 CONCERN을 아래 Task 본문에 보강 반영(전부 plan 명시로 해소, BLOCKER 0이라 게이트1 진입). 핵심: eng-C1(comment 클리어불가)·eng-C2(summary .optional 금지)·eng-C4(remaining 단일출처)·eng-C5(MSW PATCH/DELETE 불변)·design-C1(카드 wrapper route)·design-C3(직접지정 조건부).
