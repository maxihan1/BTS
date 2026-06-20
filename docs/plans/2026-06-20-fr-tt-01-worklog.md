# FR-TT-01 — Worklog (추정/실제/잔여 시간)

> slug: fr-tt-01-worklog
> type: backend
> agent: backend-engineer
> primary_bc: issue-tracking
> 생성: 2026-06-20

## Brief

FR-TT-01 — Worklog (추정/실제/잔여 시간). 이슈별 작업 시간 기록.

- 원문: "fr-tt-01 진행하자 다른 섹션에서 병행해서 작업하고 있으니 워크트리 새로 만들어서 진행해"
- classify: type=backend, agent=backend-engineer, primary_bc=issue-tracking, slug=fr-tt-01-worklog
- SDD 참조: §5.9 Worklog 데이터 모델, 02-requirements FR-TT-01
- product: docs/plan/product/agile-planning.md §5.1
- **BC 배치 권장**: agile-planning BC 소속 FR이나 issue-tracking BC에 구현 (Worklog 등록 시 Issue.time_spent/remaining_estimate 갱신 = Issue 애그리거트 결합, FR-PL-01/02 선례 동일). bts-domain에서 정식 확정 → 게이트 1 Maxi 확인.

## 도메인 정리

- **BC**: issue-tracking (논리 BC 라벨은 agile-planning 유지 — FR-PL 선례, FR 카운트 변동 없음)
- **신규 엔티티**: Worklog (Issue 애그리거트의 자식)
- **신규 issue 컬럼**: original_estimate_seconds, time_spent_seconds, remaining_estimate_seconds (SDD §5.9/§5.1 명시하나 미마이그레이션)
- **새 용어 (glossary 추가 후보, Maxi 승인 대기)**: Worklog(작업 로그), Original Estimate(원 추정), Time Spent(실제 소요), Remaining Estimate(잔여 추정), Log Work(작업 기록)
- **기존 결정 충돌**: 없음 (worklog 관련 ADR/glossary 항목 0건, 신규 도메인 영역)
- **관련 ADR**: [docs/adr/2026-06-20-worklog-time-tracking-model.md](../adr/2026-06-20-worklog-time-tracking-model.md) (생성됨)

### Maxi 결정 (도메인 게이트)

1. **BC 배치** → issue-tracking (Issue.time_spent/remaining_estimate 갱신 결합 + FR-PL 선례)
2. **Worklog 가시성** → 이슈 권한만 따름 (항목별 PUBLIC/TEAM_ONLY/PRIVATE 제외, 후속 FR 위임)
3. **잔여 추정 동작** → 자동 차감 + 수동 override (기본 max(0, remaining − time_spent), 명시 new_remaining 시 설정)

### 마이그레이션

- 다음 V번호: **V027** (issue-tracking 하위 폴더, 자체 V-series V001~V026) — 머지 직전 재확인 (동시 브랜치 충돌 방지)
- init_codegen.sql 미러 필수 (jOOQ 코드젠)

### PR 분할 (기본 — 게이트1 확인)

- 백엔드 D1~D5 우선 1 PR (#163), 프론트 D6/D7 후속 PR — FR-WT-01/FR-MV-01 선례

## 스펙

전체 스펙. [docs/specs/2026-06-20-fr-tt-01-worklog.md](../specs/2026-06-20-fr-tt-01-worklog.md)

핵심 시나리오 요약.
- `POST /issues/{key}/worklogs`로 작업시간 기록 → issue.time_spent = SUM 재집계, remaining 자동 차감(또는 newRemaining override)
- `PATCH /issues/{key}`로 originalEstimate/remainingEstimate 수동 설정(JsonNullable 3-state, FR-PL-01 패턴), time_spent는 파생·읽기전용
- GET/PATCH/DELETE worklog — VIEW/UPDATE+본인 권한, 단일 트랜잭션, no-bump(OCC 회피), 하드삭제
- V027 신규 worklogs 테이블 + issues 3컬럼, init_codegen 미러

## Brainstorming Check

✅ 통과 (자체 적대적 갭 분석). 주요.
- **G1 반영**: IssueResponse 3필드 추가 팬아웃 → plan에 전 생성지점/테스트 task 필수
- **G2 게이트1 위임**: 추정 changelog 통합 기본값(FR9, 일관성) vs 미통합 대안
- G3~G6 반영: 롤업 범위외·페이지네이션 없음·Instant 재사용·comment 상한 plan확정

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
