# FR-TT-02 D6/D7 — 워크로그 집계 프론트 UI + E2E — 스펙

> slug: fr-tt-02-d6-d7-ui-recharts-e2e · BC: issue-tracking · 선행: FR-TT-02 백엔드(#167)
> 백엔드 ADR: [docs/adr/2026-06-20-worklog-aggregate-model.md](../adr/2026-06-20-worklog-aggregate-model.md)
> 백엔드 spec: [docs/specs/2026-06-20-fr-tt-02-worklog-aggregate.md](2026-06-20-fr-tt-02-worklog-aggregate.md)

## 개요

FR-TT-02 백엔드(`GET /api/v1/worklogs/aggregate`)가 제공하는 프로젝트 워크로그 집계를
**프로젝트별 보고 페이지**(`/projects/$projectKey/reports/worklog`)에서 표 + recharts 차트로 시각화한다.

- **D6** — 프론트 UI (frontend-engineer)
- **D7** — E2E (qa-engineer)

### Maxi 확정 결정 (2종)
1. **차트 = recharts** 신규 설치 (`recharts@3.8.1`, React 19 peer 지원 확인). 절대규칙 #17 — 버전 고정.
2. **배치 = 프로젝트별 보고 페이지** `/projects/$projectKey/reports/worklog`.

## 백엔드 응답 계약 (실측 — Zod 1:1 미러 대상)

`GET /api/v1/worklogs/aggregate?project=&by=&granularity=&from=&to=` → 200
```jsonc
{
  "data": {
    "by": "issue",                  // "issue" | "user" | "period"
    "granularity": "day",           // @JsonInclude(NON_NULL): by=period 일 때만 존재 (그 외 키 자체 없음)
    "from": "2026-01-01",           // 미전달 시 키 없음
    "to": "2026-06-30",             // 미전달 시 키 없음
    "buckets": [
      { "key": "BTS-12", "label": "BTS-12", "timeSpentSeconds": 50400, "worklogCount": 7 }
    ],
    "totalTimeSpentSeconds": 86400
  }
}
```
- **주의**: 백엔드 spec 예시에 있던 `project` 필드는 **실제 DTO에 없음**(drift). Zod 스키마는 실 DTO 기준 — `project` 미포함.
- `granularity`/`from`/`to`는 `@JsonInclude(NON_NULL)`이라 키가 아예 없을 수 있음 → Zod `.optional()` (not `.nullable()`).
- 에러: 400(파라미터 무효), 401(미인증), 403(BROWSE 권한 없음).

## 사용자 시나리오 (Given-When-Then)

### S1. 이슈별 집계 보기 (기본)
- **Given** actor가 프로젝트 `BTS`에 BROWSE 권한 보유, 보고 페이지 진입
- **When** `/projects/BTS/reports/worklog` 진입 (기본 by=issue)
- **Then** 이슈별 소요 시간 막대 차트 + 표(label=issueKey, 시간, 건수) 표시. 합계는 상단 요약에 표시

### S2. 차원 전환 (issue → user → period)
- **When** 차원 셀렉터에서 `사용자별` 선택
- **Then** by=user로 재조회 → displayName 라벨 막대/표. `기간별` 선택 시 granularity 셀렉터(day/week/month) 노출

### S3. 기간 필터
- **When** from/to 날짜 입력(네이티브 date) + 적용
- **Then** 해당 기간으로 재조회. by=period면 granularity 버킷으로 표시

### S4. worklog 0건 (빈 상태)
- **Given** 권한 있으나 집계 결과 buckets=[]
- **When** 보고 페이지 진입
- **Then** "기록된 워크로그가 없습니다" 빈 상태 + 합계 0. 차트/표 미표시(또는 빈 표)

### S5. 권한 없는 프로젝트
- **Given** actor가 해당 프로젝트 BROWSE 권한 없음 → API 403
- **When** 보고 페이지 진입
- **Then** "이 프로젝트의 워크로그를 볼 권한이 없습니다" 안내 화면(기존 ProjectNotFound 류 재사용/유사)

### S6. 잘못된 입력 방지
- **When** from > to 입력
- **Then** 클라이언트 단에서 적용 차단 + 안내(서버 400 방어). 차원/granularity는 셀렉터라 무효값 발생 불가

## 기능 요구사항 (FR)

- **FR1**. 라우트 `/projects/$projectKey/reports/worklog` 신설. RouteAdapter(useParams) + props 기반 Page 분리(기존 패턴).
- **FR2**. API 클라이언트 `fetchWorklogAggregate(projectKey, { by, granularity?, from?, to? })` + Zod 스키마(백엔드 DTO 1:1). `worklog-aggregate.ts` 신규(기존 `worklogs.ts`는 이슈 단위라 분리).
- **FR3**. TanStack Query 훅 `useWorklogAggregate(projectKey, params)`. params 변경 시 자동 재조회. queryKey에 모든 파라미터 포함.
- **FR4**. 필터 바 — 차원 셀렉터(issue/user/period), by=period 시 granularity 셀렉터(day/week/month), from/to 네이티브 `input[type=date]`(FR-PL-01 선례, 의존성 0).
- **FR5**. 차트 — recharts `BarChart`. X축=bucket.label, Y축=소요 시간(시간 단위 tick formatter), 막대 hover 툴팁=`formatSeconds`. by=issue/user는 값 DESC 정렬(백엔드 순서 유지), by=period는 시간 ASC(백엔드 순서 유지).
- **FR6**. 표 — 버킷 전체를 `label / 소요시간(formatSeconds) / worklogCount` 컬럼으로. 합계 행 또는 상단 요약에 `totalTimeSpentSeconds`.
- **FR7**. 시간 표시 — `@/lib/duration`의 `formatSeconds`(초→"Xh Ym") 재사용. 신규 포맷 함수 금지.
- **FR8**. 403 → 페이지 레벨 권한 안내 화면. 그 외 에러 → 에러 메시지. 로딩 → 스켈레톤/로딩 표시.
- **FR9**. i18n — 라벨은 `@/i18n/worklog-aggregate-labels.ts`로 분리(기존 *-labels 패턴). 콜론 종결 금지(ko.test 자동 검증 대상).
- **FR10**. 네비게이션 진입점 — 프로젝트 컨텍스트에서 보고 페이지로 가는 링크 1개(최소). 위치는 기존 프로젝트 네비 패턴 따름(plan에서 확정).

## 비기능 요구사항 (NFR)

- recharts 번들 추가 — lazy import 또는 일반 import는 plan에서 결정(보고 페이지 전용이라 코드 스플릿 후보).
- 접근성 — 차트는 시각 보조이므로 표가 데이터의 1차 출처(스크린리더 접근). 차트에 `aria-label`/대체 텍스트.
- 차트 막대 과다(버킷 수 큰 프로젝트) — 차트는 상위 N개만, 표는 전체. N은 plan에서 확정(기본 후보 20). silent 절단 금지 — "상위 N개 표시" 명시.

## 엣지 케이스

- **E1. period dense 채움**: 백엔드는 데이터 있는 버킷만 반환(sparse). 이번 범위는 **sparse 그대로 시간순 표시**(빈 기간 0 막대 채움 제외 — granularity별 버킷 생성 복잡도 + 차트 가독성에 필수 아님). dense 채움은 후속 개선으로 명시(spec E1 "프론트 책임"은 "가능"이지 "필수" 아님).
- **E2. granularity는 by=period 전용**: by=issue/user면 granularity 셀렉터 숨김. 요청에 granularity 미포함(또는 무시). 응답 granularity 키 부재 정상.
- **E3. by=user displayName 빈 문자열**: 백엔드가 조회 실패 시 label="" 반환. 프론트는 빈 라벨을 "(알 수 없음)" 같은 placeholder로 표시(key=UUID는 노출하지 않음 — 가독성).
- **E4. from/to 부분 입력**: from만 또는 to만 입력 허용(백엔드가 한쪽 필터 지원). 둘 다 미입력=전체 기간.
- **E5. 차원 전환 시 granularity/from/to 보존**: by를 바꿔도 기간 필터는 유지. by=period→issue 전환 시 granularity는 UI에서 숨기되 상태 보존(다시 period로 오면 복원).
- **E6. 잘못된 projectKey(미존재/비멤버)**: 백엔드 403(권한 판정) → 권한 안내 화면(존재 probe 방지, 404 아님 — 백엔드 E5 계승).
- **E7. URL 직접 진입 + 쿼리 동기화**: 필터 상태를 URL 쿼리에 반영할지는 plan 결정(MVP는 컴포넌트 state로 충분, URL 동기화는 nice-to-have).

## 제약 조건

- **읽기 전용** — 이 페이지는 조회만. mutation 없음.
- **API 클라이언트 분리** — `worklog-aggregate.ts` 신규(이슈 단위 `worklogs.ts`와 책임 분리). Zod는 실 DTO 1:1, `project` 필드 inventing 금지(메모리 frontend-zod-backend-dto-contract-gap).
- **CSRF** — GET이라 CSRF 토큰 불요(apiFetch 기본 동작).
- **router.ts 공유 충돌 주의** — 병행 worktree(fr-bd-01-d6-d7)도 router.ts 수정. 머지 시점 rebase로 양쪽 라우트 보존(메모리 parallel-fr-overlapping-frontend-infra-collision). 먼저 머지된 쪽에 통합.
- **recharts 버전 고정** — `recharts@3.8.1` 정확 버전(절대규칙 #17). `^`/`~` 금지.

## 측정 가능한 완료 기준

- [ ] `/projects/$projectKey/reports/worklog` 라우트 진입 + 3개 by 차원 전환 동작
- [ ] Zod 스키마가 백엔드 실 DTO와 1:1(granularity/from/to optional, project 미포함) — vitest + tsc 통과
- [ ] 차트(recharts BarChart) + 표 렌더, 시간은 formatSeconds 표시
- [ ] 빈 상태(buckets=[]) / 403 권한 안내 / 로딩 상태 각각 렌더
- [ ] from/to 기간 필터 + by=period granularity 동작
- [ ] i18n 라벨 콜론 종결 0 (ko.test)
- [ ] D7 E2E — happy path(차원 전환 + 차트/표 표시) + 빈 상태 + 권한(403) MSW 시나리오
- [ ] pnpm verify (lint + typecheck + test + build) 그린

## Brainstorming Check (self-review)

직접 sanity check로 도출·반영한 gap.
- Zod optional vs nullable 구분(@JsonInclude NON_NULL → optional) · `project` 필드 drift 차단(실 DTO 기준)
- period dense 채움 범위 결정(sparse 표시로 한정 + 명시) · displayName 빈 문자열 placeholder(E3)
- 차원 전환 시 필터 상태 보존(E5) · 차트 막대 과다 시 상위 N + 표 전체(silent 절단 금지)
- router.ts 병행 worktree 충돌 사전 명시 · recharts 버전 고정 · 권한 안내(403, probe 방지)
✅ self-review 1-pass 통과 (정의된 FR + Maxi 결정 2종으로 핵심 갈림길 사전 확정. office-hours 대화형 부적합 — 메모리 bts-spec-office-hours-mismatch)
