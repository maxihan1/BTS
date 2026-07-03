# FR-RP-04 D6/D7 — Cycle Time / Lead Time 분포 리포트 프론트엔드 — 스펙

> slug: fr-rp-04-d6-d7-cycle-time-lead-time
> type: ui (frontend-engineer)
> 백엔드 D1~D5: PR #228 완료 · ADR `2026-07-03-fr-rp-04-cycle-lead-time`
> 미러 템플릿: CFD 프론트(FR-RP-03 D6/D7)

## 배경

FR-RP-04는 프로젝트의 **완료된 이슈**가 얼마나 걸려 끝났는지의 **분포**를 시각화한다.
- **Lead Time** = 이슈 생성 → 마지막 DONE 전이. 모든 완료 이슈 집계.
- **Cycle Time** = 첫 IN_PROGRESS 전이 → 마지막 DONE 전이. IN_PROGRESS 미경유 이슈 제외(Cycle 표본 ≤ Lead 표본).

백엔드가 초 단위 표본 + 요약 통계(nearest-rank 백분위)를 이미 계산해 반환한다. 프론트는 **순수 변환 + 렌더**만 담당(FR-RP-01/02/03 선례 정합, 프론트가 백분위 재계산 금지).

## Maxi 확정 결정 (2026-07-03)

- **D1. 기간 컨트롤 없음** — 날짜 피커 미노출. 백엔드 기본 30일 창 사용(형제 리포트 CFD/velocity/burndown 동형). 응답의 실제 `from`/`to`는 페이지에 텍스트로 표기. 나중에 피커 추가 여지.
- **D2. 세로 스택 배치** — Cycle Time 섹션(위) → Lead Time 섹션(아래). 각 섹션 = 요약 타일 + 히스토그램 + 박스플롯. 두 지표 동시 노출로 Lead≥Cycle 관계를 한눈에 비교.
- **D3. 표준 Tukey 박스플롯** — 상자 = p25~p75, 안쪽 선 = 중앙값(p50), 수염 = min~max(전체 범위). p90은 요약 타일에 별도 수치로 표기(박스플롯엔 미표시).

## 사용자 시나리오 (Given-When-Then)

### S1. 정상 조회
- **Given** 백로그 권한(BROWSE) 있는 사용자가 완료 이슈가 있는 프로젝트를 본다.
- **When** 백로그 페이지의 "Cycle/Lead Time" 링크를 눌러 `/projects/{key}/reports/cycle-time`에 진입한다.
- **Then** 최근 30일 창의 Cycle/Lead Time 요약 타일 + 히스토그램 + 박스플롯이 세로 스택으로 렌더된다. 조회 창(from~to)이 텍스트로 보인다.

### S2. Cycle 표본 없음(Lead만 있음)
- **Given** 완료 이슈는 있으나 모두 IN_PROGRESS를 거치지 않았다(cycleTime.count=0, leadTime.count>0).
- **When** 리포트를 연다.
- **Then** Lead Time 섹션은 정상 렌더, Cycle Time 섹션은 "표본 없음(진행 중 상태를 거친 완료 이슈가 없습니다)" 안내만 표시한다(빈 통계로 차트 그리지 않음).

### S3. 완료 이슈 없음(전체 빈 상태)
- **Given** 창 내 완료 이슈가 하나도 없다(cycleTime.count=0 AND leadTime.count=0).
- **When** 리포트를 연다.
- **Then** 페이지 전체가 단일 빈 상태 안내("아직 표시할 데이터가 없습니다")를 표시한다.

### S4. 권한 없음
- **Given** 해당 프로젝트 BROWSE 권한이 없는 사용자.
- **When** URL로 직접 리포트에 진입한다.
- **Then** 403 → "접근 권한이 없습니다" 안내만 표시하고 응답 데이터(issueKey 등)를 화면에 노출하지 않는다.

### S5. 로딩 / 조회 실패
- **Given** 조회가 진행 중이거나 5xx·네트워크 오류.
- **When** 리포트를 연다.
- **Then** 로딩 중엔 `role="status"` 안내, 실패 시 일반 재시도 안내(응답 미노출).

## 기능 요구사항 (FR)

- **FR1. API 클라이언트** — `fetchProjectCycleTime(projectKey)`가 `GET /api/v1/projects/{projectKey}/cycle-time`(from/to 미전달) 호출, `{ data: CycleTimeResponse }` 언랩. Zod 스키마는 백엔드 DTO(`CycleTimeResponse`/`MetricResponse`/`SampleResponse`)와 필드명·타입 1:1 대조.
- **FR2. 리포트 컨테이너** — `CycleTimeReport`가 useQuery 상태 분기(로딩 → 에러(403/기타) → 전체 빈(isCycleTimeEmpty) → 본문). 본문은 Cycle/Lead 두 섹션 스택.
- **FR3. 지표 섹션** — `CycleTimeMetricSection`이 지표 하나(요약 타일 + 히스토그램 + 박스플롯) 렌더. 해당 지표 count=0이면 섹션 내 "표본 없음" 안내.
- **FR4. 요약 타일** — count·min·max·avg·p25·p50·p75·p90를 사람 친화 단위로 표시. count=0이면 섹션 렌더 안 함(FR3).
- **FR5. 히스토그램** — recharts BarChart. 순수 함수 `toHistogram(samples, binCount)`가 표본을 등간격 구간으로 binning(초 → 구간 라벨). x축=소요 구간, y축=이슈 수.
- **FR6. 박스플롯** — 커스텀 SVG(recharts 미지원). 상자 p25~p75, 중앙선 p50, 수염 min~max. 순수 스케일 함수로 통계값→x좌표 매핑. `role="img"` + aria-label.
- **FR7. 단위 포맷터** — 순수 함수 `formatDuration(seconds)`가 초를 사람 친화 문자열(초/분/시간/일)로 변환.
- **FR8. 라우트** — `/projects/$projectKey/reports/cycle-time`, requireAuth. route adapter + props 기반 Page(라우터 비의존 단위 테스트).
- **FR9. nav 진입** — 백로그 페이지 nav에 "Cycle/Lead Time" 링크 추가(velocity/cfd 링크와 동형).
- **FR10. i18n** — 모든 표시 문자열은 `cycleTimeLabels`(콜론 종결 금지).
- **FR11. MSW 핸들러** — dev/test용 `cycle-time-handlers`. 정상/빈/403 시나리오 시드 가능(공유 store 불필요, 단순 응답).

## 프론트 계약 (Zod — 백엔드 DTO 1:1)

```
SampleResponse   = { issueKey: string, seconds: number }
MetricResponse   = {
  count: number,
  min: number | null, max: number | null, avg: number | null,
  p25: number | null, p50: number | null, p75: number | null, p90: number | null,
  samples: SampleResponse[]        // 초 오름차순
}
CycleTimeResponse = {
  projectKey: string,
  from: string,                    // "YYYY-MM-DD"
  to: string,
  cycleTime: MetricResponse,
  leadTime: MetricResponse
}
```
- count=0이면 min/max/avg/p25/p50/p75/p90 전부 null(표본 없음 ≠ 값 0). Zod에서 `.nullable()`.
- 응답 래퍼 `{ data: T }`.

## 순수 함수 명세 (단위 테스트 핵심)

- `isCycleTimeEmpty(res)` = `res.cycleTime.count === 0 && res.leadTime.count === 0`.
- `toHistogram(samples: {seconds}[], binCount)` → `{ label, rangeStartSeconds, rangeEndSeconds, count }[]`.
  - 등간격: 폭 = `(max−min)/binCount`, `min===max`면 단일 구간, 빈 배열이면 빈 결과. 라벨은 `formatDuration` 범위 표기.
  - 각 표본은 정확히 한 구간에만 귀속(마지막 구간은 우측 경계 포함).
- `boxPlotScale({min,max}, width)` → 통계값을 [0,width] 픽셀로 선형 매핑하는 함수. min===max 방어(0 division).
- `formatDuration(seconds)` → 초<60 "N초", <3600 "N분", <86400 "N시간 M분", 그 외 "N일 M시간"(반올림 규칙 명시, 음수 없음 가정).

## 비기능 요구사항 (NFR)

- **접근성(WCAG 2.1 AA)** — 색-단독 구분 금지(박스플롯/히스토그램에 텍스트·aria 병행), 차트 컨테이너 `role="img"` + aria-label, 요약 타일은 텍스트로 수치 제공.
- **보안** — 403/에러 상태에서 응답 데이터(issueKey 포함) 미노출. 프론트는 백엔드 가시성 필터를 신뢰(재검증 안 함).
- **타입 안전** — TS strict. 렌더 전 Zod parse로 계약 위반 차단. `tsc --noEmit` + vitest 동반(vitest는 타입 무시 — 메모리 zod-schema-strengthen 교훈).
- **성능** — 페이로드는 창 내 완료 이슈 수에 비례(v1 캡 없음, 180일 상한서 유계). 히스토그램 binning은 O(n).

## 엣지 케이스

- cycleTime.count=0 & leadTime.count>0 → Lead만 렌더, Cycle 섹션 "표본 없음"(S2).
- 표본 1건 → 히스토그램 단일 막대, 박스플롯 상자·수염 폭 0(min===max) 방어.
- 모든 표본 동일 seconds → 히스토그램 단일 구간, 박스플롯 상자 폭 0.
- avg 등 통계는 초 단위 정수(백엔드 Long) — 포맷터가 반올림 표기.
- 응답 from/to는 표시용(창 텍스트), 재조회 트리거 아님.
- 날짜 피커 없음 → date-input ISO 변환 함정(FR-UX-03) 해당 없음.
- **박스플롯 독립 스케일(v1 수용 단순화)** — Cycle/Lead 박스플롯은 각 지표 자체 min~max로 스케일. 두 박스플롯의 폭을 직접 시각 비교하진 못하며, 절대 소요는 요약 타일 수치로 확인. 공유 축은 v1 범위 밖(추후 여지).
- **정확값 확인** — 히스토그램은 recharts Tooltip으로 구간·개수 노출, 박스플롯은 min/p25/p50/p75/max 값을 텍스트로 병기(색-단독/도형-단독 금지, WCAG).

## 제약 조건

- 백엔드/도메인 코드 변경 0(순수 프론트 뷰). BC 격리 준수.
- CFD 프론트 자산 재사용 금지(별도 파일) — 단, 상태분기/route adapter/Zod 래퍼 등 **구조 패턴**은 미러. 공유 헬퍼 무리한 추출 금지(YAGNI).
- recharts는 이미 의존성(velocity/cfd). 박스플롯만 커스텀 SVG.

## 측정 가능한 완료 기준

- [ ] `fetchProjectCycleTime` + Zod가 백엔드 DTO와 1:1(필드 grep 대조), 단위 테스트 통과.
- [ ] `CycleTimeReport` 상태 분기 4종(로딩/403/기타에러/빈) 단위 테스트.
- [ ] 순수 함수(`isCycleTimeEmpty`/`toHistogram`/`boxPlotScale`/`formatDuration`) 엣지 케이스 단위 테스트.
- [ ] 라우트 등록 + 백로그 nav 링크 + E2E 진입 라운드트립.
- [ ] E2E(D7): 진입·정상 렌더·빈 상태·(가능 시) Cycle 표본 없음. 실 렌더(SVG/recharts) 시각 검증은 E2E 위임(jsdom width 0 함정 — 메모리 recharts jsdom).
- [ ] `pnpm verify`(lint+typecheck+test+build) 통과.

## Brainstorming Check

✅ 통과 (1회 iteration). gap 2건 발견·반영 — G1 박스플롯 독립 스케일 v1 단순화(절대값=요약 타일) 명시, G2 정확값 확인 수단(히스토그램 Tooltip·박스플롯 값 텍스트 병기). Maxi 결정 필요 사항 없음(구현 세부). 나머지 요구/시나리오/엣지는 백엔드 계약·CFD 선례로 충분히 커버.
