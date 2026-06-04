# FR-IS-08 D6/D7 — 이슈 PDF 출력 프론트엔드

> slug: fr-is-08-pdf-frontend
> type: ui (frontend-engineer 주도 + qa-engineer E2E)
> primary_bc: apps/web
> 생성: 2026-06-04

## Brief

FR-IS-08 D6/D7 — 이슈 PDF 출력의 프론트엔드 짝. 백엔드 D1-D5(PR #71, `GET /api/v1/issues/{key}/pdf` → application/pdf)는 머지 완료.

- D6. 프론트 UI — 이슈 상세에 인쇄/PDF 다운로드 버튼 (책임. designer → frontend-engineer)
- D7. E2E (책임. qa-engineer)

classify가 'E2E'로 qa 오분류 → UI 기능+E2E 혼합으로 정정(frontend-engineer 주도).

**핵심 기술 포인트**.
- 백엔드는 `application/pdf` 바이너리(`Content-Disposition: attachment; filename="{key}.pdf"`)를 반환.
- 프론트는 fetch → blob → 브라우저 다운로드 트리거(또는 새 탭). MSW로 mock.

## 도메인 정리

- **BC**. issue-tracking (프론트 apps/web). 단일.
- **영향**. 이슈 상세 페이지(`src/routes/issues.$key.tsx`) + 메타 패널(`IssueMetaPanel.tsx`). 신규 도메인 엔티티/용어 0.
- **성격**. 기존 백엔드 엔드포인트(`GET /api/v1/issues/{key}/pdf`, PR #71) 소비. 읽기 전용 다운로드.
- **API 관례**(`src/api/issues.ts` + `client.ts`). Zod + DataResponse 래퍼 + `apiFetch`(Authorization 자동 + 401 refresh-retry + credentials include).
- **신규 패턴 — blob 다운로드**. 기존 API 함수는 전부 JSON 파싱. PDF는 `apiFetch(...).then(res => res.blob())` (코드베이스 **첫 바이너리 다운로드**). Zod 파싱 안 함(바이너리). 에러만 ApiError로.
- **CSRF**. GET 요청이라 X-XSRF-TOKEN 불요([[frontend-api-convention-per-bc]] — mutation만 필요).
- **기존 결정 충돌**. 없음. 다운로드/blob 관련 ADR 부재.
- **관련 ADR**. 없음.

### 미해결 (→ spec에서 결정)
1. 버튼 배치 위치(메타 패널 액션 영역 vs 상세 헤더). 기존 액션 버튼 패턴 확인 필요.
2. 다운로드 방식(앵커 클릭 자동 다운로드 vs 새 탭 열기). 백엔드가 `attachment` Content-Disposition이라 자동 다운로드가 자연스러움.
3. 로딩/에러 UX(다운로드 중 버튼 비활성 + 실패 토스트).

## 스펙

전체 스펙. [docs/specs/2026-06-04-fr-is-08-pdf-frontend.md](../specs/2026-06-04-fr-is-08-pdf-frontend.md)

핵심 3줄 요약.
- `downloadIssuePdf(key): Promise<Blob>` (issues.ts, apiFetch→res.blob, 첫 바이너리 패턴) + breadcrumb 행 우측 PDF 버튼.
- 다운로드 트리거 = createObjectURL→임시 앵커 click(download="{key}.pdf")→revokeObjectURL. 로딩 중 disabled, 실패 시 sonner 토스트.
- 단위(createObjectURL mock) + E2E(Playwright download 이벤트). 기존 E2E 셀렉터 회귀 0.

**미해결(plan 확정)**. blob 다운로드 헬퍼 위치(`src/lib/download.ts` 신규 vs 컴포넌트 인접) — 재사용/테스트 고려.

## Brainstorming Check

✅ 통과 (인라인 새너티, 엣지 8건). office-hours/design-shotgun은 단일 버튼 추가에 부적합하여 직접 작성.

## Plan

> 범위. D6(프론트 UI) Task 1-3 = frontend-engineer, D7(E2E) Task 4 = qa-engineer. agent 기본값 frontend-engineer.
> 헬퍼 위치 확정. `src/lib/download.ts`(신규, 순수 헬퍼 — 재사용+vitest 용이).
> "인쇄" 해석. 백엔드가 서버 PDF만 제공 → PDF 다운로드 버튼 1개(브라우저 print 별도 추가 안 함, 스코프).
> 라우트 테스트 파일. 정본은 `src/routes/issues.$key.test.tsx`(52KB). `__tests__/issues.$key.test.tsx`(5.6KB)는 기존 보조 — 건드리지 않음.

### Task 1. downloadIssuePdf API + MSW 핸들러

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/api/issues.ts`, `apps/web/src/api/issues.test.ts`, `apps/web/src/mocks/issue-handlers.ts`]
- depends-on: []

**RED**.
- 파일. `apps/web/src/api/issues.test.ts`
- 테스트. `downloadIssuePdf(key)` →
  - 200 + application/pdf MSW 응답 시 `Blob` 반환(타입/크기 검증).
  - 5xx MSW 응답 시 `ApiError(status)` throw.
- MSW 핸들러(`src/mocks/issue-handlers.ts`)에 `GET /api/v1/issues/:key/pdf` 추가 — 작은 PDF 바이트(`%PDF-` 시작, `new HttpResponse(uint8, {headers})`) `application/pdf` + `Content-Disposition: attachment; filename="{key}.pdf"` 반환. **⚠️ C2 — 핸들러를 `issueHandlers` export 배열(파일 끝)에 반드시 추가**(빠지면 vitest는 error로 잡지만 E2E는 onUnhandledRequest:'bypass'로 조용히 빠져 download 미발생→타임아웃). `:key`는 단일 세그먼트라 `/:key/pdf`를 안 삼킴(순서 무관).
- 커밋. `test: fr-is-08-pdf-frontend task-1 red — downloadIssuePdf`

**GREEN**.
- `src/api/issues.ts`에 `downloadIssuePdf(key: string): Promise<Blob>` 추가(apiFetch GET → !ok면 ApiError → res.blob()). 스펙 §API 인터페이스 그대로.
- 커밋. `feat: fr-is-08-pdf-frontend task-1 green — downloadIssuePdf + MSW`

**REFACTOR**. JSDoc(바이너리/CSRF 불요 사유) + 핸들러 주석.

**검증**. `pnpm --filter @bts/web test -- issues.test` + `pnpm --filter @bts/web typecheck`

### Task 2. triggerBlobDownload 헬퍼

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/lib/download.ts`, `apps/web/src/lib/download.test.ts`]
- depends-on: []   # 순수 헬퍼, Task 1과 병렬

**RED**.
- 파일. `apps/web/src/lib/download.test.ts`
- 테스트. `triggerBlobDownload(blob, filename)` →
  - `URL.createObjectURL`(mock) 호출 + 반환 URL을 앵커 href에.
  - 임시 `<a download="{filename}">` click 호출(spy).
  - `URL.revokeObjectURL`(mock) 호출(누수 방지).
- 커밋. `test: fr-is-08-pdf-frontend task-2 red — triggerBlobDownload`

**GREEN**.
- `src/lib/download.ts` — `triggerBlobDownload(blob, filename)`: createObjectURL → 앵커 생성/click → revokeObjectURL(finally).
- 커밋. `feat: fr-is-08-pdf-frontend task-2 green — blob 다운로드 헬퍼`

**REFACTOR**. JSDoc(첫 줄 한국어 헤더) + revoke를 finally로 누수 보장.

**검증**. `pnpm --filter @bts/web test -- download.test`

### Task 3. PDF 다운로드 버튼 UI + i18n + 배선

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/routes/issues.$key.tsx`, `apps/web/src/i18n/ko.ts`, `apps/web/src/routes/issues.$key.test.tsx`]
- depends-on: [1, 2]

**RED**.
- 파일. `apps/web/src/routes/issues.$key.test.tsx` (정본)
- **⚠️ BLOCKER B1 (코드리뷰) — 부분 mock 필수**. `@/api/issues`를 통째 mock하면 `fetchIssue/updateIssue/transitionIssue`가 undefined가 되어 페이지 부팅 실패 → 기존 30+ 라우트 테스트 전멸. 반드시 부분 mock:
  ```ts
  vi.mock('@/api/issues', async (importOriginal) => ({
    ...(await importOriginal<typeof import('@/api/issues')>()),
    downloadIssuePdf: vi.fn(),
  }))
  ```
  `@/lib/download`의 `triggerBlobDownload`도 vi.mock(jsdom blob 회피). 선례: 같은 파일 `issues.$key.test.tsx`의 `vi.mock('@tanstack/react-router', async (importOriginal) => ...)`.
- 테스트(route 단위 — `downloadIssuePdf`·`triggerBlobDownload`만 격리, 나머지 api는 원본 유지/MSW).
  - breadcrumb 행 우측에 PDF 버튼 렌더(aria-label).
  - 클릭 → `downloadIssuePdf(key)` 호출 + 반환 Blob으로 `triggerBlobDownload(blob, "{key}.pdf")` 호출.
  - 다운로드 중 버튼 `disabled`.
  - 실패(`downloadIssuePdf` reject) → `toast.error` 호출 + 버튼 재활성.
  - 기존 상세 테스트 회귀 0(셀렉터 strict mode — 버튼 텍스트 "PDF" 중복 없게 컨테이너 한정).
- 커밋. `test: fr-is-08-pdf-frontend task-3 red — PDF 버튼`

**GREEN**.
- `src/i18n/ko.ts` `issueDetailStrings`에 `pdfDownloadButton`/`pdfDownloadAriaLabel`/`pdfDownloadError` 추가.
- `src/routes/issues.$key.tsx` breadcrumb 행을 flex(우측 정렬)로 만들고 secondary `Button`(lucide 아이콘 + 라벨) 추가. `useState` 로딩 + async 핸들러(downloadIssuePdf → triggerBlobDownload → try/catch toast).
- 커밋. `feat: fr-is-08-pdf-frontend task-3 green — PDF 버튼 배선`

**REFACTOR**. 핸들러 추출 + 로딩 상태 정리.

**검증**. `pnpm --filter @bts/web test -- issues.\$key` + `pnpm --filter @bts/web typecheck` + `pnpm --filter @bts/web lint`

### Task 4. E2E (Playwright)

**메타**.
- agent: `qa-engineer`
- files: [`apps/web/e2e/issue-pdf.spec.ts`]
- depends-on: [3]

**RED**.
- 파일. `apps/web/e2e/issue-pdf.spec.ts`
- 시나리오. 이슈 상세 진입 → **`const downloadPromise = page.waitForEvent('download')`를 click 이전에 셋업(C3 레이스 회피)** → PDF 버튼 클릭 → `await downloadPromise` → `suggestedFilename()`이 `{key}.pdf` 검증. 앵커 `download` 속성이 우선하므로 프론트 filename과 MSW Content-Disposition을 일치(`{key}.pdf`). (MSW 핸들러는 Task 1에서 추가됨, 재사용. 첫 download E2E라 선례 없음 — 안정화 주의.)
- 기존 이슈 E2E 회귀 0 확인(전체 e2e 실행).
- 커밋. `test: fr-is-08-pdf-frontend task-4 — PDF 다운로드 E2E`

**GREEN/REFACTOR**. (버튼·핸들러는 Task 3 완성) 셀렉터/대기 안정화.

**검증**. `pnpm --filter @bts/web test:e2e -- issue-pdf`

## Plan 메타

- task 수: 4
- 예상 wave: 3 (Wave1 T1·T2 병렬 → Wave2 T3 → Wave3 T4)
- TDD 강제: yes
- 파일 겹침: 없음 (T1 api/handler, T2 lib, T3 route/i18n, T4 e2e 독립)
- 추가 검증: typecheck(tsconfig.app) + eslint + vitest + playwright
- 주의(learnings): UI PR 기존 E2E 셀렉터 strict mode 회귀([[ui-pr-defer-e2e-regression-latent]], [[playwright-getbyrole-exact-strict-mode]]) / MSW serviceWorker block 함정([[e2e-msw-serviceworker-block]]) / CI typecheck는 tsconfig.app([[ci-typecheck-tsconfig-app-vs-local]])

## 리뷰 결과

### code-reviewer ground-truth plan 리뷰 (2026-06-04)

**결론**. 진행 가능. 환각 API 0. apiFetch raw Response/blob, msw 2.14.6 바이너리, jsdom createObjectURL mock 필요 모두 ground truth 일치.

- ⚠️ **B1 (반영, BLOCKER)** Task 3 라우트 테스트는 `@/api/issues` **부분 mock** 필수(통째 mock 시 fetchIssue 등 undefined→기존 30+ 테스트 전멸). 부분 mock 코드 plan에 명시.
- ⚠️ **C2 (반영)** MSW 핸들러를 `issueHandlers` export 배열에 추가(빠지면 E2E bypass→타임아웃).
- ⚠️ **C3 (반영)** E2E `waitForEvent('download')`를 click 이전 셋업 + filename 일치.
- ℹ️ **C1** triggerBlobDownload mock으로 라우트는 "호출됨"까지만 검증, 실동작은 T2 download.test + T4 E2E가 커버(같은 PR이라 OK).
- ℹ️ **C4/N7** 백엔드 에러 바디 형식 1회 확인 권장. "인쇄"=PDF 다운로드 해석은 타당하나 게이트1에서 Maxi 확인.
- ✅ **N1-N6** 환각 없음 / MSW 바이너리 가능 / jsdom mock 정확 / 기존 "PDF" 텍스트 0건이라 셀렉터 회귀 없음 / i18n 위치 맞음(e2e fixture 직결) / 파일 겹침 없음(단 i18n/ko.ts는 머지 직전 병렬 FR 충돌 확인).
