# FR-RP-02 D6/D7 — 벨로시티 차트 (Velocity Chart) 프론트엔드

> slug: fr-rp-02-d6-d7-velocity
> type: ui
> agent: frontend-engineer
> 생성: 2026-07-03

## Brief

FR-RP-02 D6/D7 — 벨로시티 차트 프론트엔드(recharts 바 차트) + E2E.
백엔드는 #222로 머지 완료: `GET /api/v1/projects/{projectKey}/velocity`, 신규 포트 `SprintVelocityLookupPort`.
막대=계획(Commitment, 스프린트 현재 가시이슈 추정합) vs 완료(Completed, DONE 카테고리 추정합) 2막대, 지표=추정시간(초).
notification-dashboard.md §4.2 D6/D7 미완료 상태.

classify: type=qa 오분류(E2E 제목) → ui/frontend-engineer 정정 (FR-RP-01 D6/D7 선례).
선례: FR-RP-01 D6/D7 번다운 차트 프론트(#220) — 코드기반 라우팅·recharts jsdom width0·계약 정합.

## 도메인 정리

- **BC**: notification-dashboard(논리) / 프론트는 `apps/web`. 백엔드는 agile-planning 구현(#222, 머지 완료).
- **영향 범위**: 프론트엔드 전용(apps/web). 신규 백엔드/도메인 엔티티 0 — 기존 계약 소비.
- **용어**: `벨로시티(Velocity)` / `계획(Commitment)` / `완료(Completed)` — **모두 glossary 등재 완료**(#222). 신규 용어 0.
- **기존 결정 충돌**: 없음. 프론트는 FR-RP-01 D6/D7 라우팅·recharts 패턴 재사용.
- **관련 ADR**: [docs/decisions/2026-07-02-fr-rp-02-velocity.md](../decisions/2026-07-02-fr-rp-02-velocity.md) (백엔드, 기존). **프론트 신규 ADR 불필요**.

### 백엔드 계약 (확보 완료, #222)
- `GET /api/v1/projects/{projectKey}/velocity?limit=10` → `DataResponse<VelocityResponse>` (외피 `{ data: {...} }`)
- `VelocityResponse`: `projectKey: string`, `averageCommitmentSeconds: number`(비-null, 0 fallback), `averageCompletedSeconds: number`, `sprints: VelocityPointResponse[]`
- `VelocityPointResponse`: `sprintId: UUID`, `name: string`, `startDate: LocalDate?`(**nullable→`.nullish()`**), `endDate: LocalDate?`, `commitmentSeconds: number`, `completedSeconds: number`
- 상태: 200 / 401(미인증) / 403(BROWSE). limit 클램프 [1,50]은 서비스 책임.

### FR-RP-01 D6/D7 미러 청사진 (프론트 파일 구조)
| FR-RP-01 (번다운, 스프린트 단위) | FR-RP-02 (벨로시티, 프로젝트 단위) |
|---|---|
| `api/burndown.ts` (Zod+순수변환+client) | `api/velocity.ts` |
| `components/burndown/BurndownChart.tsx` (recharts 라인) | `components/velocity/VelocityChart.tsx` (recharts **바**) |
| `routes/projects.$projectKey.sprints.$sprintId.burndown.tsx` | `routes/projects.$projectKey.velocity.tsx` |
| `mocks/burndown-handlers.ts` | `mocks/velocity-handlers.ts` |
| `i18n/burndown-labels.ts` | `i18n/velocity-labels.ts` |
| 진입: `SprintColumn.tsx` "번다운" 버튼 (스프린트 단위) | 진입: **프로젝트 단위 위치** — spec에서 확정 |
| `e2e/sprint-burndown.spec.ts` | `e2e/project-velocity.spec.ts` |

**★spec 결정거리**: 벨로시티는 프로젝트 전체(여러 완료 스프린트) 집계라 진입점이 SprintColumn(스프린트 단위)이 아님. 프로젝트 단위 진입 위치(백로그 보드 헤더 / 프로젝트 보드 등)를 spec에서 확정.

## 스펙

전체 스펙. [docs/specs/2026-07-03-fr-rp-02-d6-d7-velocity.md](../specs/2026-07-03-fr-rp-02-d6-d7-velocity.md)

핵심 시나리오 3줄 요약.
- 백로그 '프로젝트 뷰 전환' nav의 '벨로시티' 링크 → `/projects/$projectKey/reports/velocity` 이동.
- 스프린트별 계획(commitment)/완료(completed) 2막대 바 차트 + 평균 참조선 2개(recharts BarChart).
- 스프린트 0개면 빈 상태, BROWSE 권한 없으면 403 안내(데이터 노출 0).

의도적 범위 결정 (게이트1 확인 대상).
- 차트 전용(데이터 테이블 미포함, 번다운 선례). `limit` 컨트롤 미노출(백엔드 기본 10). 토글 없음.

## Brainstorming Check

✅ 통과 (포커스 갭 점검). 차단 갭 0건, 의도적 범위 결정 2건 노트.

## Plan

> 모든 task agent: `frontend-engineer` (E2E task만 `qa-engineer`). 경로는 `apps/web/` 기준.

### Task 1. api/velocity.ts — Zod 스키마 + fetchProjectVelocity

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/api/velocity.ts`, `apps/web/src/api/velocity.test.ts`]
- depends-on: []

**RED** (`api/velocity.test.ts`):
- `velocityResponseSchema.parse` 가 정상 `DataResponse` 외피(`{ data: {...} }`)를 파싱한다.
- `startDate`/`endDate` 가 `null` 이어도 파싱된다(`.nullish()`).
- `fetchProjectVelocity` 가 200 이면 `data` 를 반환, 401/403 이면 `ApiError(status)` 를 throw(전역 fetch mock).
- 실패 메시지(예상): `api/velocity.ts` 모듈 없음.

**GREEN**:
- `burndownPointSchema`/`dataResponseSchema` 패턴 미러. `velocityPointResponseSchema`(sprintId/name string,
  startDate/endDate `.nullish()`, commitmentSeconds/completedSeconds `z.number()`) + `velocityResponseSchema`
  (projectKey, averageCommitmentSeconds/averageCompletedSeconds `z.number()`, sprints 배열).
- `fetchProjectVelocity(projectKey: string)` → `apiFetch` GET, `!res.ok` → `ApiError`, `.parse(raw).data`.

**REFACTOR**: KDoc(백엔드 DTO 1:1 대응 명시), 타입 `z.infer` export.

**검증**: `pnpm --filter web test -- velocity` (api 단위) + `pnpm --filter web typecheck`.

### Task 2. VelocityChart.tsx (recharts BarChart + toVelocitySeries) + velocity-labels.ts

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/velocity/VelocityChart.tsx`, `apps/web/src/components/velocity/VelocityChart.test.tsx`, `apps/web/src/i18n/velocity-labels.ts`]
- depends-on: [1]

**RED** (`VelocityChart.test.tsx` — 순수변환만, recharts 실렌더는 jsdom width0라 E2E 위임):
- `toVelocitySeries(response)` 가 `sprints` 를 `{ name, commitment, completed }[]` 로 매핑한다.
- 빈 `sprints` → `[]`.
- `startDate`/`endDate` null 이어도 name 기준이라 무영향(방어 확인).
- 실패 메시지(예상): `toVelocitySeries` export 없음.

**GREEN**:
- `velocity-labels.ts`: page.title/description, series.commitment/completed, series.avgCommitment/avgCompleted,
  status.loading/forbidden/empty/loadFailed, chart.ariaLabel/yAxisTitle (콜론 종결 금지).
- `toVelocitySeries` named export(순수). `VelocityChart`: `ResponsiveContainer > BarChart`, `<Bar dataKey="commitment">`
  + `<Bar dataKey="completed">`(grouped), `<ReferenceLine y={averageCommitmentSeconds}>` + `<ReferenceLine y={averageCompletedSeconds}>`(label),
  XAxis dataKey="name", YAxis `tickFormatter=formatSeconds`, `<Legend>`, `role="img"` + aria-label.

**REFACTOR**: 색상 상수 추출(번다운 COLOR_* 톤 계열, 두 막대 대비색), `formatSecondsValue` 헬퍼 재사용.

**검증**: `pnpm --filter web test -- VelocityChart` + `typecheck`.

### Task 3. VelocityReport.tsx — useQuery 상태 분기

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/velocity/VelocityReport.tsx`, `apps/web/src/components/velocity/VelocityReport.test.tsx`]
- depends-on: [1, 2]

**RED** (`VelocityReport.test.tsx`, `vi.mock('@/api/velocity')` + QueryClient wrapper):
- 로딩 중 → `status.loading` 문구.
- 성공(sprints≥1) → 차트 컨테이너(`role="img"`) 렌더.
- 빈 상태(sprints=[]) → `status.empty` 문구(차트 미렌더).
- `ApiError(403)` → `status.forbidden` 문구(데이터 노출 0).
- 실패 메시지(예상): `VelocityReport` 없음.

**GREEN**: `useQuery({ queryKey: ['velocity', projectKey], queryFn: () => fetchProjectVelocity(projectKey) })`,
isLoading/isError(ApiError 403 분기)/빈 분기 → 각 문구, 정상 → `<VelocityChart>`.

**REFACTOR**: 상태 분기 헬퍼 정리, KDoc.

**검증**: `pnpm --filter web test -- VelocityReport` + `typecheck`.

### Task 4. route 페이지 + router 등록 + router.test

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/routes/projects.$projectKey.reports.velocity.tsx`, `apps/web/src/routes/projects.$projectKey.reports.velocity.test.tsx`, `apps/web/src/router.ts`, `apps/web/src/router.test.tsx`]
- depends-on: [3]

**RED**:
- `projects.$projectKey.reports.velocity.test.tsx`: `VelocityReportPage({projectKey})` 가 헤더 h1(`page.title`) + `VelocityReport` 렌더(라우터 비의존, `vi.mock` VelocityReport).
- `router.test.tsx`: `/projects/$projectKey/reports/velocity` 라우트가 트리에 등록됨.
- 실패 메시지(예상): 라우트 미등록.

**GREEN**:
- route 파일: `ProjectVelocityReportRouteAdapter`(useParams) + `VelocityReportPage`(props). worklog 리포트 라우트 미러.
- `router.ts`: `projectVelocityRoute = createRoute({ getParentRoute, path:'/projects/$projectKey/reports/velocity',
  component: ProjectVelocityReportRouteAdapter, beforeLoad: requireAuthAndPasswordChanged })`, `addChildren` 배열 추가,
  **라우트 카운트 주석 38→39** + 신규 항목 명시.

**REFACTOR**: import 정렬, 주석 갱신.

**검증**: `pnpm --filter web test -- router velocity` + `typecheck`.

### Task 5. backlog nav '벨로시티' Link + backlog-labels

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/routes/projects.$projectKey.backlog.tsx`, `apps/web/src/i18n/backlog-labels.ts`, `apps/web/src/routes/projects.$projectKey.backlog.test.tsx`]
- depends-on: [4]   # TanStack 타입드 Link `to` 가 등록된 라우트 필요

**RED** (backlog 페이지 테스트 — 기존 파일 있으면 케이스 추가, 없으면 신규):
- `프로젝트 뷰 전환` nav 에 '벨로시티' 링크가 있고 `to="/projects/$projectKey/reports/velocity"` params projectKey.
- 실패 메시지(예상): 링크 부재.

**GREEN**: `backlog-labels.ts` 에 `page.velocityLink` 추가, backlog route nav 에 board/timeline 형제로 `<Link>` 추가.

**REFACTOR**: 링크 스타일 형제와 통일.

**검증**: `pnpm --filter web test -- backlog` + `typecheck`.

### Task 6. mocks/velocity-handlers.ts + handlers 등록 (계약 정합 가드)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/mocks/velocity-handlers.ts`, `apps/web/src/mocks/handlers.ts`, `apps/web/src/mocks/velocity-handlers.test.ts`]
- depends-on: [1]   # velocity 스키마로 fixture 계약 검증

**RED** (`velocity-handlers.test.ts` — MSW↔계약 drift 가드):
- 핸들러 정상 응답이 `velocityResponseSchema` 로 파싱된다(외피 포함).
- 빈 상태/403 시나리오 토글이 각 응답을 낸다(localStorage 플래그, MSW 시나리오 토글 선례).
- 실패 메시지(예상): 핸들러 없음.

**GREEN**: `velocity-handlers.ts`(정상 + 빈 + 403, `addInitScript`/localStorage 토글), `handlers.ts` 에 spread 등록.

**REFACTOR**: fixture 시드 상수화.

**검증**: `pnpm --filter web test -- velocity-handlers`.

### Task 7. e2e/project-velocity.spec.ts (실 브라우저)

**메타**.
- agent: `qa-engineer`
- files: [`apps/web/e2e/project-velocity.spec.ts`]
- depends-on: [4, 5, 6]

**시나리오** (testDir=`apps/web/e2e/`, SPA는 `toHaveURL(regex)` polling):
- S1: 로그인 → 백로그 → '벨로시티' nav 클릭 → URL `/reports/velocity` + 차트 SVG 컨테이너(`role="img"`) 가시.
- S2: 빈 상태 시나리오(localStorage 토글) → `status.empty` 문구 가시.
- (선택) S3: 막대/평균선 존재(SVG rect/line 셀렉터, bbox 폭>0 확인 — SVG E2E 함정 대비).
- 기존 E2E 회귀 0 동반 실행(UI PR은 기존 E2E 함께).

**검증**: `pnpm --filter web test:e2e -- project-velocity` + 인접 스펙 회귀 확인.

## Plan 메타

- task 수: 7 (각 TDD 사이클; T7만 E2E)
- 의존성 그래프: T1→T2→T3→T4→T5, T1→T6, {T4,T5,T6}→T7
- 예상 wave: 6 (W1:T1 / W2:T2,T6 / W3:T3 / W4:T4 / W5:T5 / W6:T7) — import 체인상 대체로 직렬
- TDD 강제: yes (test: 커밋이 feat: 커밋보다 먼저 — bts-impl 자동 검증)
- 추가 검증: typecheck, lint, vitest, playwright(qa)
- ★핵심 함정(선례): recharts jsdom width0(순수변환 단위+실렌더 E2E) · TanStack 타입드 Link는 라우트 등록 후 · 라우트 카운트 주석 갱신 · MSW↔계약 drift 가드 · E2E testDir=apps/web/e2e/

## 리뷰 결과

### plan-review (eng 집중, 2026-07-03) — 완료 FR 미러라 design-shotgun/autoplan machinery 생략
- ✅ 의존성 그래프 정합 — T5(nav Link)가 T4(라우트 등록) 의존 올바름(TanStack 타입드 `to`는 등록 라우트 대조).
- ✅ 파일 겹침 0 — 동일 wave 내 두 task가 같은 파일 안 건드림(router.ts=T4·handlers.ts=T6·backlog.tsx=T5 단독).
- ✅ recharts jsdom width0 — T2 순수변환 단위 + 실렌더 T7 E2E 위임.
- ✅ MSW↔계약 drift 가드 — T6 스키마 적합성 테스트.
- ⚠️ 라우트 카운트 — "38→39"는 가정값. **impl 시점 실제 카운트 재확인** 후 증가.
- ⚠️ 평균 `ReferenceLine` — 수평선에 값 label 필수(평균이 핵심 지표).
- ⚠️ 에러 폴백 — 비-403 에러는 `loadFailed` 일반 폴백. 401은 apiFetch 세션 리다이렉트(기존 관례).
- BLOCKER: 없음.

### 디자인 관점 (경량)
- 번다운 시각 언어 미러(높이 360·color 톤·Legend·`role="img"`) → 일관성 유지.
- 두 막대(commitment/completed) 대비색 + Legend + 툴팁으로 색-단독 회피(WCAG). 접근성 색 대비 확보.
- 로딩/빈/403 상태 문구는 번다운 status 패턴 미러.
