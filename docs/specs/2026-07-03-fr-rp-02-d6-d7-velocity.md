# FR-RP-02 D6/D7 — 벨로시티 차트 (Velocity Chart) 프론트엔드 — 스펙

> slug: fr-rp-02-d6-d7-velocity · type: ui · agent: frontend-engineer · 2026-07-03
> 백엔드: #222 머지 완료 (agile-planning BC). 프론트 전용 작업.
> 선례: FR-RP-01 D6/D7 번다운 차트 프론트(#220) 미러.

## 배경

벨로시티(Velocity) 차트는 여러 완료(COMPLETED) 스프린트에 걸쳐 **계획량(Commitment)** 과 **완료량(Completed)** 을
추정 시간(초) 기준으로 스프린트별 집계해 팀의 처리 역량 추세를 보여주는 애자일 리포트다. 백엔드(#222)가
`GET /api/v1/projects/{projectKey}/velocity` 를 제공하며, 이번 작업은 그 응답을 소비하는 프론트 차트 + E2E다.

## 사용자 시나리오 (Given-When-Then)

- **S1 — 벨로시티 조회**. Given 프로젝트 BROWSE 권한이 있는 사용자가 백로그 페이지에 있을 때, When '프로젝트 뷰 전환'
  nav에서 '벨로시티' 링크를 클릭하면, Then `/projects/$projectKey/reports/velocity` 로 이동해 스프린트별
  계획/완료 2막대 바 차트와 평균 참조선을 본다.
- **S2 — 스프린트 없음(빈 상태)**. Given 완료 스프린트가 0개인 프로젝트에서, When 벨로시티 페이지에 진입하면,
  Then 차트 대신 "아직 표시할 데이터가 없습니다." 안내를 본다(평균은 0).
- **S3 — 권한 없음**. Given 프로젝트 BROWSE 권한이 없는 사용자가, When 벨로시티 페이지에 진입하면,
  Then 403 안내("접근 권한이 없습니다.")를 본다(차트·데이터 노출 0).
- **S4 — 로딩/실패**. Given 조회 진행 중이면 로딩 문구를, 네트워크·5xx 실패면 일반 실패 안내를 본다.

## 기능 요구사항 (FR)

- **FR1**. `api/velocity.ts` — `GET /api/v1/projects/{projectKey}/velocity` 를 호출하는 `fetchProjectVelocity(projectKey)`
  + 백엔드 DTO와 1:1 대응하는 Zod 스키마. `DataResponse<T>` 외피(`{ data: {...} }`) 파싱. 상태코드별 `ApiError`.
- **FR2**. `components/velocity/VelocityChart.tsx` — recharts **BarChart**. 스프린트별 `commitment`/`completed`
  2막대(grouped) + `averageCommitmentSeconds`/`averageCompletedSeconds` 수평 `ReferenceLine` 2개. 순수변환
  `toVelocitySeries(response)` 를 named export(단위 테스트 대상). `role="img"` + aria-label. Y축 초→시간(`formatSeconds`),
  X축 스프린트명. `<Legend>` + 색+패턴 이중 구분(WCAG 색-단독 금지).
- **FR3**. `components/velocity/VelocityReport.tsx` — `useQuery(fetchProjectVelocity)` 로 로딩/에러/403/빈 상태(스프린트 0)
  분기 후 `VelocityChart` 렌더. (worklog `WorklogAggregateReport` 선례.)
- **FR4**. `routes/projects.$projectKey.reports.velocity.tsx` — `RouteAdapter`(useParams) + `Page`(props 기반, 라우터 비의존
  단위 테스트 가능). 헤더 h1 + 설명 + `VelocityReport`.
- **FR5**. `router.ts` 에 `projectVelocityRoute` (`/projects/$projectKey/reports/velocity`, `requireAuthAndPasswordChanged`)
  등록 + 라우트 카운트 주석 갱신 + `router.test.tsx` 등록 검증.
- **FR6**. 진입점 — `routes/projects.$projectKey.backlog.tsx` 의 `프로젝트 뷰 전환` nav에 '벨로시티' `Link` 추가
  (board/timeline 형제). 라벨은 `i18n` 상수.
- **FR7**. `i18n/velocity-labels.ts` — page/series/status/chart 라벨(한국어, 콜론 종결 금지).
- **FR8**. `mocks/velocity-handlers.ts` — MSW 핸들러(정상 응답 + 빈 상태 + 403 시나리오). `handlers.ts` 에 등록.
- **FR9**. `e2e/project-velocity.spec.ts` — 실 브라우저 E2E(차트 SVG 컨테이너 가시성, 빈 상태, nav 링크 이동).

## 비기능 요구사항 (NFR)

- **NFR1 — recharts jsdom width0**. jsdom은 SVG 레이아웃(width=0)이라 recharts가 실렌더 안 됨 → **순수변환 함수는 단위 테스트**,
  **시각 렌더는 E2E 위임**(FR-RP-01/FR-TT-02 선례).
- **NFR2 — 계약 정합**. Zod 스키마는 백엔드 DTO를 그대로 반영. `startDate`/`endDate` nullable → `.nullish()`.
  `commitmentSeconds`/`completedSeconds`/`average*Seconds` 는 non-null `z.number()`. MSW fixture는 스키마와 정확히 일치.
- **NFR3 — 접근성**. 차트 `role="img"` + 서술형 aria-label. 색+패턴 이중 구분.
- **NFR4 — 보안**. 403은 컴포넌트에서 데이터 노출 없이 안내만. 미인증(401)은 apiFetch가 세션 리다이렉트 처리(기존 관례).
- **NFR5 — TypeScript strict**. `as`/`any` 금지. recharts `data` 제네릭 추론 이슈는 구체 타입 배열로 해결.

## API 인터페이스 (소비, 신규 백엔드 없음)

```
GET /api/v1/projects/{projectKey}/velocity?limit=10   (limit 기본 10, 프론트는 기본값 사용)
→ 200 { data: VelocityResponse }
  VelocityResponse {
    projectKey: string,
    averageCommitmentSeconds: number,   // 비-null, 스프린트 0개면 0
    averageCompletedSeconds: number,    // 비-null
    sprints: VelocityPointResponse[]     // 시간순 오름차순
  }
  VelocityPointResponse {
    sprintId: string(UUID), name: string,
    startDate: string|null, endDate: string|null,   // LocalDate? → .nullish()
    commitmentSeconds: number, completedSeconds: number
  }
→ 401 미인증 / 403 BROWSE 권한 미충족
```

## 데이터 모델 변경

없음. 프론트 전용. 신규 백엔드/DB/도메인 0.

## 엣지 케이스

- 스프린트 0개 → `sprints: []`, `average*=0` → 빈 상태 안내(차트 미렌더).
- `startDate`/`endDate` null(미설정 스프린트) → 차트는 `name` 기준 X축이라 무영향. 툴팁 날짜는 null 방어.
- 스프린트 1개 → 바 1쌍 + 평균선(= 그 값). 정상 렌더.
- 403/네트워크 오류 → 각 상태 안내 문구.
- 매우 긴 스프린트명 → X축 tick 말줄임/회전은 recharts 기본 처리 위임(과도한 커스텀 금지).

## 제약 조건

- 한 PR = 한 BC(apps/web 프론트). 백엔드 무변경(#222 계약 그대로 소비).
- `limit` URL search param 미노출(단순화). 백엔드 기본값 10 사용. 토글 없음(번다운의 `?view=` 불필요).
- 기존 파일 수정 최소 — 진입 링크(backlog route + labels)와 router 등록만 기존 파일 편집.

## 측정 가능한 완료 기준

- `pnpm typecheck` / `pnpm lint` / `pnpm test`(단위: `toVelocitySeries`, Zod 파싱, Report 상태 분기, router 등록) 통과.
- `pnpm test:e2e`(project-velocity: 차트 렌더 + 빈 상태 + nav 진입) 통과 + 기존 E2E 회귀 0.
- notification-dashboard.md §4.2 D6/D7 체크박스 `[x]` 마킹 + 문서 동기화.

## Brainstorming Check

✅ 통과 (포커스 갭 점검, 완료 FR 미러). 차단 갭 0건. 의도적 범위 결정 2건 — (1) 차트 전용(데이터 테이블 미포함,
번다운 선례), (2) `limit` 컨트롤 미노출(백엔드 기본 10). 경미 해소 — 평균선 `ReferenceLine` label 식별, nav 링크
권한 미게이팅(board/timeline 동일·대상 403 처리), WCAG 색+Legend+툴팁 이중 식별.
