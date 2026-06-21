# FR-TT-02 D6/D7 — 워크로그 집계 프론트 UI + E2E

> slug: fr-tt-02-d6-d7-ui-recharts-e2e
> type: ui
> agent: frontend-engineer
> 생성: 2026-06-21

## Brief

사용자 원문: "fr-tt-02 d6, d7 진행하자" + "다른 섹션 병행 작업 중이니 워크트리는 새로 만들어서 진행"

FR-TT-02 — 이슈/사용자/기간별 시간 집계 (agile-planning §5.2, BC=issue-tracking).
백엔드 D1~D5 완료(#167): `GET /api/v1/worklogs/aggregate?project=&by=&granularity=&from=&to=`.
이번 작업 = D6(프론트 UI 표 + recharts 차트) + D7(E2E).

classify: 원판정 type=qa(E2E 키워드 오판) → type=ui / agent=frontend-engineer 교정.

### Maxi 확정 결정 (2종, spec 선행)
1. **차트 = recharts 설치** (product 기획대로). 절대규칙 #17 — 버전 고정 필수.
2. **배치 = 프로젝트별 보고 페이지** `/projects/$projectKey/reports/worklog`.

### 백엔드 응답 계약 (실측, Zod 1:1 미러 대상)
`{ data: { by, granularity?, from?, to?, buckets:[{key,label,timeSpentSeconds,worklogCount}], totalTimeSpentSeconds } }`
- `@JsonInclude(NON_NULL)`: granularity(by≠period 시 키 제거), from/to(미전달 시 키 제거)
- 주의: spec 예시의 `project` 필드는 실제 DTO에 없음 (drift — Zod는 실 DTO 기준)

## 도메인 정리

- **BC**: issue-tracking (백엔드 ADR `2026-06-20-worklog-aggregate-model.md` D1 계승)
- **신규 도메인 용어**: 없음 — 프론트는 백엔드 집계 계약(`/api/v1/worklogs/aggregate`)을 **소비만** 한다. 새 도메인 개념 0.
- **신규 ADR**: 없음 — 백엔드 ADR가 모델/권한(BROWSE+Project scope)/차원(issue·user·period)/granularity/period UTC 버킷을 모두 확정. ADR §미해결 5항목도 spec(`2026-06-20-fr-tt-02-worklog-aggregate.md`)에서 전부 채워짐.
- **기존 결정 충돌**: 없음 (백엔드 ADR/spec 계승).
- **프론트 고유 결정** (UI 스펙 — spec 단계에서 확정):
  - period sparse→dense 채움 = **프론트 D6 책임** (spec E1 명시). 백엔드는 데이터 있는 버킷만 반환.
  - recharts 차트 종류 (by=issue/user 막대, by=period 시계열).
  - 시간 포맷 (초 → "2h 30m" 등) — FR-TT-01 WorklogSection 선례 재사용 후보.
  - 403 권한 게이팅 UI, worklog 0건 빈 상태.
- **glossary 메모**: glossary.md에 worklog/집계 용어 미등록(백엔드 FR-TT-01/02 시 누락). 수동 영역이라 본 PR 범위 밖 — Maxi 영역으로 남김.

## 스펙

전체 스펙. [docs/specs/2026-06-21-fr-tt-02-d6-d7-worklog-aggregate-ui.md](../specs/2026-06-21-fr-tt-02-d6-d7-worklog-aggregate-ui.md)

핵심 요약.
- 라우트 `/projects/$projectKey/reports/worklog` 신설(RouteAdapter + props Page). 차원 셀렉터(issue/user/period) + period 시 granularity + from/to 네이티브 date.
- API 클라이언트 `worklog-aggregate.ts` 신규(이슈 단위 `worklogs.ts`와 분리). Zod는 실 DTO 1:1 — granularity/from/to **optional**(@JsonInclude NON_NULL), `project` 필드 미포함(drift 차단).
- recharts `BarChart` + 표(formatSeconds 재사용). 빈 상태/403 권한 안내/로딩 처리.
- period dense 채움은 범위 외(sparse 시간순 표시 + 명시). 차트 막대 과다 시 상위 N + 표 전체.

## Brainstorming Check

✅ self-review 1-pass 통과 (정의된 FR + Maxi 결정 2종으로 핵심 갈림길 사전 확정).
보강 항목. Zod optional/nullable 구분 · project drift 차단 · displayName 빈 문자열 placeholder · 차원전환 필터 보존 · router.ts 병행 worktree 충돌 명시 · recharts 버전 고정 · 403 probe 방지.

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
