# FR-TT-02 — 이슈/사용자/기간별 시간 집계

> slug: fr-tt-02-worklog-aggregate
> type: backend
> agent: backend-engineer
> 생성: 2026-06-20

## Brief

사용자 원문: "FR-TT-02 진행. 다른 섹션에서 병행 작업 중이니 워크트리는 새로 만들어서 진행"

FR-TT-02 — 이슈/사용자/기간별 시간 집계 (agile-planning BC, Plan slug `agile/worklog-aggregate`).
선행 FR-TT-01(Worklog, #163/#166) 완료. `worklogs` 테이블 위에 집계(aggregate) 기능을 얹는다.

product/agile-planning.md §5.2 D단계:
- D1. 도메인 (backend-engineer)
- D2. 명세 — 집계 차원 (이슈/사용자/기간/프로젝트) (backend-engineer)
- D3. 데이터 모델 — 머티리얼라이즈드 뷰 검토 (db-engineer)
- D4. 백엔드 — `GET /api/v1/worklogs/aggregate?by=...` (backend-engineer)
- D5. 백엔드 테스트 (backend-engineer)
- D6. 프론트 UI — 표 + recharts 차트 (designer → frontend-engineer)
- D7. E2E + NFR (qa-engineer)

classify 결과: slug=fr-tt-02-worklog-aggregate, type=backend, agent=backend-engineer.
주의: classify의 primary_bc=issue-tracking은 키워드 추정이며, 실제 BC는 worklog 코드 위치로 확정한다.

## 도메인 정리

- **BC**: issue-tracking (FR-TT-01 ADR D1 계승 — worklog는 Issue 애그리거트 강결합. product의 agile-planning §5.2는 논리 라벨)
- **영향 엔티티**: `Worklog`(읽기 전용 집계 소스, 기존), `Issue`(project 필터 JOIN용)
- **신규 도메인 타입**: `WorklogAggregateDimension` enum(ISSUE/USER/PERIOD), 집계 결과 VO(`WorklogAggregateBucket`)
- **새 용어**: 워크로그 집계(Worklog Aggregate), 집계 차원(dimension), 기간 버킷(period bucket)
- **신규 스키마**: 없음(읽기 전용). 집계 전용 인덱스는 D3 성능 검토 후 조건부 추가
- **기존 결정 충돌**: 없음. FR-TT-01 ADR D4 가시성 규칙을 cross-issue 집계로 확장(누출 차단)

### Maxi 확정 결정 (3종)

1. **권한 범위** = 프로젝트 단위 + Project scope VIEW 권한. `?project=KEY` 필수, 미보유 403. 개별 이슈 보안수준은 미반영(FR-TT-01 D4 단순성 계승).
2. **계산 방식** = 실시간 SQL 집계(jOOQ GROUP BY). 머티리얼라이즈드 뷰 회피(권한 동적필터 불가 + REFRESH 관리 부담 + 현 규모 과함).
3. **집계 차원** = `by ∈ {issue, user, period}`. period는 `granularity ∈ {day, week, month}`. project는 그룹축 아닌 필수 필터.

- **엔드포인트**: `GET /api/v1/worklogs/aggregate?project=&by=&from=&to=&granularity=` (신규 cross-issue 컨트롤러)
- **관련 ADR**: [docs/adr/2026-06-20-worklog-aggregate-model.md](../adr/2026-06-20-worklog-aggregate-model.md) (생성됨)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
