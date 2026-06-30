# FR-EX-02 D6/D7 — 대용량 비동기 Export 프론트 UI + E2E

> slug: fr-ex-02-d6-d7-export-ui
> type: ui
> agent: frontend-engineer (D6 주력) + qa-engineer (D7 E2E 보조)
> 생성: 2026-06-30

## Brief

FR-EX-02(대용량 >1만건 비동기 Export)의 백엔드 D1~D5는 PR #204로 완료.
이번 작업은 D6(프론트 UI — 진행률 + 알림 + 다운로드) + D7(E2E).

백엔드 계약(PR #204):
- POST /api/v1/search/export-jobs (202 + jobId)
- GET /api/v1/search/export-jobs/{id} (폴링 — status/progress)
- GET /api/v1/search/export-jobs/{id}/download (완료 시 프록시 스트리밍)
- 24h TTL 후 하드삭제

classify 원분류 qa(E2E 키워드 오판) → ui로 정정. FR-EX-01(동기 Export 다이얼로그, PR #203) 위에 비동기 경로를 얹는 작업.

## 도메인 정리

- **BC**: search-export-import (FR-EX-01·FR-EX-02 동일 본거지, BC 변경 0). 프론트는 `apps/web` SPA.
- **영향 범위**: 프론트 view-layer 소비만. 백엔드 도메인/계약은 PR #204에서 확정 — 이 PR은 0 백엔드 변경 목표.
  - 새 API 클라이언트 (`@/api/search`에 비동기 export-jobs 3종 함수 추가)
  - 새 UI (잡 생성 → 진행률 폴링 → 완료 다운로드)
- **백엔드 계약 (PR #204 확정, 직접 코드 검증)**:
  - `POST /api/v1/search/export-jobs` — body `ExportRequest`(projectKey, query=AQL, format?, columns?), 202 `{ jobId, status:"PENDING" }`, AQL/검증 오류 400
  - `GET /api/v1/search/export-jobs/{id}` — 200 `ExportJobResponse{ jobId, status, progress(0~100), rowCount?, format, errorCode?, downloadReady }`, 타인/없음 404 (@JsonInclude NON_NULL)
  - `GET /api/v1/search/export-jobs/{id}/download` — 200 octet-stream + Content-Disposition / 미완료 409(SEARCH_EXPORT_NOT_READY) / 타인·없음 404
  - status enum 4종: PENDING→RUNNING→COMPLETED / (PENDING|RUNNING)→FAILED (종단 COMPLETED·FAILED)
- **★핵심 제약 — 완료 이벤트 발행 없음**: 백엔드 plan §187 "ExportJob은 완료 이벤트 발행이 없다". 백엔드는 in-app/이메일 알림을 보내지 않음. **프론트가 폴링으로 완료 감지 + 자체 진행률/완료 표시**. product 문서 D6의 "알림"은 프론트 자체 통지(진행률 바·완료 표시)로 해석.
- **재사용 자산 (FR-EX-01 동기 Export, PR #203)**:
  - `ExportDialog.tsx`/`ExportForm` — 형식(CSV/XLSX) + 9컬럼 선택 UI, `/search` 페이지(search.tsx:604)에서 "내보내기" 버튼으로 열림
  - `exportIssues()` (`@/api/search`), `triggerBlobDownload` (`@/lib/download`), `resolveExportError` 에러 추출 패턴
  - `EXPORT_COLUMNS` 9컬럼 정의(백엔드 ExportColumn enum 1:1)
- **새 용어**: 없음. ExportJob은 백엔드 도메인 용어(glossary 미등재 — PR #204에서 Maxi 승인 보류). 프론트는 view 소비라 용어 신설 불요.
- **기존 결정 충돌**: 없음 (FR-EX-01 ADR §D5가 `/search/export-jobs`를 명시 예고).
- **관련 ADR**: FR-EX-01 `docs/decisions/2026-06-29-fr-ex-01-csv-xlsx-export.md` §D5 + FR-EX-02 백엔드 `docs/decisions/2026-06-29-fr-ex-02-async-export-jobs.md`. 프론트 UX 결정은 본 PR plan/spec에 기록(별도 ADR 후보 — 게이트1 판단).
- **grill-with-docs 생략 근거**: 백엔드 계약 100% 확정된 view-layer 소비 작업. 새 도메인 결정 0. 대화형 도메인 challenge 과함 (메모리: bts-spec office-hours mismatch / bts-review-plan autoplan overkill). 직접 계약 검증으로 대체.
- **★spec 단계 핵심 미결정 (Maxi taste)**: 동기(FR-EX-01) ↔ 비동기(FR-EX-02) UX 진입점 통합 방식 + 진행률 표시 위치. SDD 미명시 → spec 전 Maxi 결정.

## 스펙

전체 스펙. [docs/specs/2026-06-30-fr-ex-02-d6-d7-export-ui.md](../specs/2026-06-30-fr-ex-02-d6-d7-export-ui.md)

핵심 시나리오 3줄 요약.
- 소량(≤1만) → 기존 동기 Export 그대로(회귀 0). 대용량(>1만) → 동기 거부 LIMIT_EXCEEDED 감지 → 비동기 제안.
- "백그라운드 내보내기" → POST export-jobs(jobId) → 같은 다이얼로그가 진행률 폴링(1500ms, GET /{id})로 전환.
- COMPLETED → "다운로드" 버튼 → GET /{id}/download → blob. FAILED → errorCode 한국어 사유 표시.

핵심 설계 = ExportDialog **4단계 상태 머신** (form → confirmAsync → tracking → done). 폴링=TanStack Query refetchInterval(종단 중단·unmount cleanup).
신규 API 클라이언트 3종(submitExportJob/fetchExportJobStatus/downloadExportJobResult) + Zod 스키마. 백엔드 변경 0.

## Brainstorming Check

✅ 통과 (직접 adversarial sanity check — 완료된 FR 프론트 단계라 office-hours/design-shotgun 부적합, 메모리 bts-spec-office-hours-mismatch). gap 8건 전부 spec 내 해소(Maxi 결정 추가 불요).

## Plan

> 베이스: `apps/web/src/`. 전부 프론트 + E2E. 백엔드 변경 0.
> 모듈 검증: `pnpm --filter web lint typecheck test` + `pnpm --filter web test:e2e`(qa).

### Task 1. `@/api/search` 비동기 export-jobs 클라이언트 3종 + Zod 스키마

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/api/search.ts`, `apps/web/src/api/search.test.ts`]
- depends-on: []

**RED**: `search.test.ts`에 추가 — (a) `submitExportJob({projectKey,query,format,columns})` → `POST /api/v1/search/export-jobs` → `{jobId, status:"PENDING"}` 반환(MSW 202), (b) `fetchExportJobStatus(jobId)` → `GET .../{id}` → `exportJobStatusSchema` 파싱 결과 반환(progress/status/downloadReady, errorCode·rowCount nullable 검증), (c) `downloadExportJobResult(jobId)` → `GET .../{id}/download` → `{blob, filename}`(Content-Disposition 파싱, exportIssues 패턴 미러), (d) 404/409 시 ApiError throw. → 함수/스키마 없음 fail.

**GREEN**: `exportJobStatusSchema`(z.object: jobId, status(z.enum 4종 PENDING/RUNNING/COMPLETED/FAILED), progress(int 0~100), **rowCount `.nullish()` · errorCode `.nullish()`**, format, downloadReady boolean) + 3 함수. `bulk-operations.ts` 스키마 패턴 참조. 전부 `apiFetch` 경유(raw fetch 금지). submit은 apiPost 또는 apiFetch+수동 파싱(202라 Spring DataResponse 아님 — exportIssues처럼 직접). download는 `exportIssues`(search.ts:139-143)의 Content-Disposition 파싱 미러.

> ★**BLOCKER-2 (리뷰)**: 백엔드 `ExportJobResponse`는 `@JsonInclude(NON_NULL)`(ExportJobResponse.kt:23) — rowCount/errorCode가 null이면 **응답 JSON에서 키 자체가 누락**된다(PENDING 응답엔 두 키 없음). `.nullable()`만 쓰면 `undefined` 입력에 `ZodError: Expected ..., received undefined`. **`.nullish()`(=nullable+optional) 필수.**

**REFACTOR**: `SEARCH_ERROR_CODES`에 `EXPORT_LIMIT_EXCEEDED`/`EXPORT_NOT_READY`/`EXPORT_STORAGE_ERROR` 추가(백엔드 SearchErrorCodes 정본 1:1) + JSDoc(폴링 계약 — 종단 상태 COMPLETED/FAILED).

**검증**: `pnpm --filter web test -- src/api/search.test.ts`

### Task 2. ExportDialog 4단계 상태 머신 확장 (자동분기 + 폴링 + 다운로드)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/search/ExportDialog.tsx`, `apps/web/src/components/search/ExportDialog.test.tsx`, `apps/web/src/hooks/use-export-job-polling.ts`]
- depends-on: [1]

> ★**선례 (구현 템플릿)**: `apps/web/src/hooks/use-bulk-operation.ts`가 동일한 잡 폴링 패턴(POLL_INTERVAL_MS=1500·종단 중단·queryKey id분리)을 이미 구현. `BulkEditDialog.tsx`/`BulkOperationResultDialog.tsx`가 "다이얼로그 내 잡 접수→폴링→결과" UX 선례. **이 두 패턴을 답습**(바퀴 재발명 금지).

**RED**: `ExportDialog.test.tsx`에 추가(기존 vi.mock('@/api/search') 패턴 — `submitExportJob`/`fetchExportJobStatus`/`downloadExportJobResult` mock 추가) —
- (a) **S1 회귀**: 동기 성공 → triggerBlobDownload + onClose (기존 동기 성공/컬럼/형식 테스트 무변경 통과)
- (b) **S2 자동분기**: `exportIssues` reject `ApiError(400,{errorCode:"SEARCH_EXPORT_LIMIT_EXCEEDED",resultCount:15000})` → 확인 단계("검색 결과 15,000건은 대용량"·"백그라운드") 표시 → "백그라운드 내보내기" 클릭 → submitExportJob 호출 → 폴링 진행률 표시 → fetchExportJobStatus가 COMPLETED+downloadReady 반환 → "다운로드" 버튼 → 클릭 시 downloadExportJobResult+triggerBlobDownload. ★resultCount는 **정확한 정수값 단언**(`getByText`로 "15,000건" — NaN/undefined 가짜그린 차단, CONCERN-B)
- (c) **S3 FAILED**: 폴링 FAILED(errorCode LIMIT_EXCEEDED) → "10만건 초과" 사유 표시 → "다시 시도" 클릭 → form 복귀 + **jobId null 초기화 확인**(다음 제출 stale 폴링 차단, CONCERN-D)
- (d) **FR-7 cleanup**: 다이얼로그 닫으면 추가 폴링 호출 없음(타이머 advance 후 fetchExportJobStatus 호출 횟수 불변)
- (e) 기타 동기 에러(LIMIT_EXCEEDED 아님) → 폼 유지 인라인 표시(기존 resolveExportError 경로)
- (f) **S4 취소**: confirmAsync에서 "취소" → form 복귀 + 형식/컬럼 상태 유지(CONCERN-C)
- ★**BLOCKER-3 (리뷰)**: 기존 `'상한초과 ... detail 메시지가 표시된다'` 단위 테스트(ExportDialog.test.tsx:225-243)는 새 자동분기 동작과 양립 불가(LIMIT_EXCEEDED가 alert 대신 confirmAsync 전환). **이 테스트를 (b) S2로 교체**(삭제+대체). NFR-1의 "회귀 0"은 동기 성공/컬럼/형식 경로 한정.
→ phase 전환 미구현 fail.

**GREEN**: `ExportForm`에 `phase: 'form'|'confirmAsync'|'tracking'|'done'` 상태 + `jobId`/`asyncResult`/`resultCount` 상태. 동기 mutation onError에서 `isLimitExceeded(error)` 판별 → confirmAsync 전환.
- ★**isLimitExceeded narrowing (CONCERN-A)**: `error instanceof ApiError` 가드 후 `body as Record<string, unknown>` → `errorCode`/`resultCount` 키 타입 가드. 기존 `resolveExportError`(ExportDialog.tsx:42-47) 패턴 답습. `as any` 금지.
- confirmAsync "백그라운드" → submitExportJob → jobId 설정 → tracking.
- ★**폴링 (BLOCKER-1 — use-bulk-operation.ts:76-94 미러)**: `useQuery({queryKey:['export-job',jobId], queryFn:()=>fetchExportJobStatus(jobId!), enabled:jobId!==null, refetchInterval:(query)=>{ if(query.state.status==='error') return false; const s=query.state.data?.status; return (s && isTerminal(s)) ? false : 1500 }})`. **★react-query v5는 refetchInterval 콜백 인자가 `data`가 아니라 `query` 객체**(v4→v5 파괴적 변경). `data=>data?.status`로 쓰면 종단 판별 실패 → 폴링 무한 실행. `query.state.data?.status`로 접근.
- status 종단 시 done 전환. done에서 downloadReady면 "다운로드" 버튼.

**REFACTOR**: 폴링을 `useExportJobPolling(jobId, enabled)` hook으로 분리(use-bulk-operation 일관) + `EXPORT_FAILURE_MESSAGES` errorCode→한국어 매핑(정적 상수) + 진행률 바 `role="progressbar"` `aria-valuenow`/`aria-valuemin=0`/`aria-valuemax=100` + 상태전환 `role="status"`/`role="alert"`(a11y NFR-2) + KDoc(상태 머신 다이어그램·폴링 cleanup 근거).

**검증**: `pnpm --filter web test -- src/components/search/ExportDialog.test.tsx`

### Task 3. E2E 대용량 자동분기 happy path + MSW stateful export-jobs 핸들러

**메타**.
- agent: `qa-engineer`
- files: [`apps/web/src/mocks/search-handlers.ts`, `apps/web/src/mocks/search-fixtures.ts`, `apps/web/e2e/export.spec.ts`]
- depends-on: [1, 2]

**RED/시나리오**: `export.spec.ts`에 추가 — E2E-1: `/search` 대용량 쿼리 → "내보내기" → (MSW 동기 400 LIMIT_EXCEEDED) → 비동기 제안 표시 → "백그라운드 내보내기" → 진행률 표시 → 폴링이 COMPLETED 도달 → "완료"+"다운로드" 표시 → "다운로드" 클릭 시 download 요청 발생. MSW: `POST /search/export-jobs`(202+jobId), `GET /search/export-jobs/:id`(**stateful — 폴링 진행**), `GET /search/export-jobs/:id/download`(blob). 다운로드 실제 저장은 opaque일 수 있어 요청 발생 검증으로 한정(메모리 fr-mv-01 opaque 한계).
- ★**BLOCKER-4 (리뷰)**: 기존 S4(export.spec.ts:239-264 — LIMIT_EXCEEDED→`role=alert` 에러 표시)는 새 자동분기 동작과 양립 불가. **S4를 confirmAsync UI 노출 검증으로 교체**. 기존 S1/S2/S3 무변경 통과. Task 3 REFACTOR의 "무회귀 보장"은 S1~S3 한정(S4는 의도된 교체).
- ★**CONCERN-E (MSW 리셋)**: stateful store는 **jobId를 키로 하는 Map + per-key 폴링 카운터**로 구현(POST마다 새 jobId 발급 → 테스트 간 자동 격리, 카운터 leak 0). 추가로 브라우저 접근 reset 헬퍼 노출(메모리 msw-derived-behavior-shared-store-e2e — 시드가능 공유 store).

**GREEN**: MSW stateful 핸들러(jobId-키 Map 폴링 카운터 — 첫 GET PENDING, 다음 RUNNING(progress), 그 다음 COMPLETED) + export.spec.ts 시나리오.

**REFACTOR**: 시드 헬퍼 + 시나리오 토글(localStorage 플래그, 메모리 e2e-msw-scenario-toggle) — 기존 동기 export.spec S1~S3 무회귀 보장(S4는 교체).

**검증**: `pnpm --filter web test:e2e -- export.spec.ts` + 모듈 전체 `pnpm --filter web lint typecheck test`

## Plan 메타

- task 수: 3 (각 TDD 사이클)
- wave 예상 (depends-on + files 교집합 기반):
  - W1: T1(api 클라이언트)
  - W2: T2(ExportDialog: 1)
  - W3: T3(E2E+MSW: 1,2)
  - 직렬 사슬 — 프론트 단일 SPA 의존 + 다른 파일이라 파일충돌은 없으나 코드 의존으로 순차
- 예상 시간: 직렬 ~15분
- TDD 강제: yes (test 커밋이 feat 커밋보다 먼저 — 단 ExportDialog는 기존 테스트 확장)
- 추가 검증: lint + typecheck + vitest + build(T1·T2) / playwright E2E(T3, qa-engineer)
- 백엔드 변경 0 — 순수 프론트 + E2E. BC 격리(search 프론트 관례 `@/api/search` 확장)
- 신규 의존성: 0 (TanStack Query·Radix·Zod 기존)

## 리뷰 결과

### 독립 frontend-engineer plan 리뷰 (subagent, 2026-06-30)

실제 코드(package.json·ApiError·ExportJobResponse.kt·ExportDialog.test.tsx·export.spec.ts·search.ts) 인용 기반 adversarial 리뷰. **BLOCKER 4건 + CONCERN 5건 모두 plan 반영 완료** (taste decision 0건 — 전부 기술 수정). design-review 대화형 스킬은 비주얼 변형 부재로 생략(메모리 bts-review-plan-autoplan-overkill).

**BLOCKER (해소 완료)**.
- **B1. refetchInterval v5 시그니처** — react-query v5는 콜백 인자가 `data`가 아니라 `query` 객체(v4→v5 파괴적 변경). `data=>data?.status`면 종단 판별 실패 → 폴링 무한. → Task 2 GREEN: `(query)=>query.state.data?.status` 명시 + **use-bulk-operation.ts:76-94 선례 미러**.
- **B2. Zod NON_NULL** — `ExportJobResponse @JsonInclude(NON_NULL)`이라 null 필드는 키 누락. `.nullable()`은 undefined 거부. → Task 1 GREEN: `.nullish()` 명시.
- **B3. 기존 단위 테스트 파괴** — ExportDialog.test.tsx:225-243 LIMIT_EXCEEDED→alert가 자동분기와 양립 불가. → Task 2 RED: S2로 교체 명시 + NFR-1 정정.
- **B4. 기존 E2E S4 파괴** — export.spec.ts:239-264 S4가 alert 가정. → Task 3 RED: confirmAsync 검증으로 교체 명시.

**CONCERN (해소 완료)**.
- A. isLimitExceeded narrowing 패턴(resolveExportError 답습, as any 금지) → Task 2 GREEN.
- B. resultCount 정수값 정확 단언(vacuous green 차단) → Task 2 RED (b).
- C. confirmAsync→form 취소 전이 테스트 → Task 2 RED (f).
- D. done(FAILED)→form 다시시도 + jobId null 초기화 → Task 2 RED (c).
- E. MSW stateful 카운터 리셋(jobId-키 Map per-key) → Task 3 RED/GREEN.

**PASS 확인**: ApiError body narrowing 패턴 존재 · download Content-Disposition 미러(search.ts:139-143) · FR-7 unmount cleanup(v5 inactive 자동 중단) · apiPost 202 정상 · SEARCH_ERROR_CODES 확장 패턴 일치.

**선례 추가 발견 (controller)**: `use-bulk-operation.ts`(폴링 hook) + `BulkEditDialog.tsx`/`BulkOperationResultDialog.tsx`(다이얼로그 내 잡 폴링 UX) = ExportDialog 4단계 상태 머신의 직접 템플릿. Task 2가 답습(바퀴 재발명 금지).

**BLOCKER: 모두 해소됨 (게이트 1 진입 가능).**
