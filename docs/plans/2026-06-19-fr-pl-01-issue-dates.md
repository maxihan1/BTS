# FR-PL-01 — 일정 필드 (Start/Due/Target Date)

> slug: fr-pl-01-issue-dates
> type: feature
> agent: backend-engineer (D1/D4/D5) + db-engineer (D3) + frontend-engineer (D6) + qa-engineer (D7)
> primary_bc: issue-tracking
> 생성: 2026-06-19

## Brief

FR-PL-01 (agile-planning §6.1). 이슈에 일정 필드 3종(start_date, due_date, target_date) 추가.
- 데이터: `issues.start_date, due_date, target_date` (DATE)
- 백엔드: 이슈 PATCH 엔드포인트 확장
- 프론트: date-fns + 데이트픽커 UI
- 선행: issue-tracking §2.1.1 (FR-IS-01 이슈 CRUD) — 완료됨
- BC 경계: FR은 agile-planning 분류이나 구현은 issue-tracking BC (issues 테이블 + 이슈 PATCH)

classify-task 오판정(auth/security) → feature/backend-engineer override.

## 도메인 정리

- **BC**: issue-tracking (확정). FR은 agile-planning §6.1 분류이나, 데이터는 `issues` 테이블 + PATCH 엔드포인트 확장 = issue-tracking BC. 모든 선행 `issues.X` 컬럼 추가(priority/assignee/labels/components/versions/parent/securityLevel/customFields)가 issue-tracking BC였던 전례 일치. agile-planning 모듈 신설 불필요.
- **영향 엔티티**: Issue 1개. nullable `LocalDate?` 3필드 추가 — `startDate`(시작일), `dueDate`(마감일/종료일), `targetDate`(목표일).
- **도메인 결정**:
  1. **타입 = DATE(Kotlin `LocalDate?`)**. 시각/타임존 성분 없는 캘린더 날짜 — Jira Start/Due date 정석. SDD 05.1(24~26행) `DATE NULL` 정합. → **D2 타임존 정책의 답**: 날짜 전용이라 타임존 무관, 저장/표시 모두 캘린더 날짜 그대로.
  2. **PATCH 3-state sentinel = `JsonNullable<LocalDate>`** (각 필드). `securityLevelId: JsonNullable<UUID>` 선례 동형 — undefined=무변경 / null=클리어 / 값=설정. nullable 날짜는 "지우기" vs "안 건드림" 구분 필수.
  3. **도메인 mutation 경유 강제**. `assignSecurityLevel(levelId)` 패턴 따라 도메인 메서드(예: `assignSchedule(...)` 또는 개별)로 변경. repository 직접 update 금지(patch-merge-domain-bypass 방지). version +1 책임 위치는 기존 선례(securityLevel은 도메인 메서드서 +1, label/priority는 repository서 +1) 확인해 spec서 확정.
- **교차 필드 검증(start ≤ due 등)**: 도메인 차원은 독립 nullable 3필드로 둠. Jira는 기본 강제 안 함(경고만). 강제 여부 = **spec 결정 사안** → bts-spec에서 정책 확정.
- **새 용어**: "일정 필드"(Schedule Dates) — 평범한 서술어라 glossary 신규 등재 불요(Maxi 확인 생략). 필요 시 spec서 재검토.
- **기존 결정 충돌**: 없음. SDD 05.1이 이미 명세. issue-tracking domain 노트 기존 ADR(이슈키 prefix, IssueType cross-BC) 무관.
- **관련 ADR**: 없음 (SDD 05.1 명세 기준 구현, 신규 ADR 불필요).
- **마이그레이션**: 다음 번호 **V025** (현재 최신 V024 issue_watchers). init_codegen.sql issues 블록에도 미러 필수(jOOQ codegen, memory: jooq-init-codegen-mirror). ⚠️ V번호는 머지 직전 재확인(동시 브랜치 충돌 방지 — 현재 병행 fr-nt-03은 notification 모듈이라 issue-tracking과 무충돌).
- **grill-with-docs**: 생략. SDD 완전 명세 + securityLevelId 동형 선례 + BC 무모호 → 직접 도메인 정리(memory: bts-spec-office-hours-mismatch / bts-review-plan-autoplan-overkill 원칙).

## 스펙

전체 스펙. [docs/specs/2026-06-19-fr-pl-01-issue-dates.md](../specs/2026-06-19-fr-pl-01-issue-dates.md)

핵심 결정 요약.
- 3필드(startDate/dueDate/targetDate) `LocalDate?` DATE NULL. 시각/타임존 없음.
- PATCH /{key} 확장, `JsonNullable<LocalDate>` 3-state(부재=무변경/null=클리어/값=설정) — securityLevelId 동형.
- 교차 검증 없음(Maxi 확정, Jira 정석) — Version `ChangeVersionDatesRequest` 주석 정책과 동일.
- 권한 = 기존 `IssuePermission.UPDATE`(IssueScope.Issue) 재사용. 신규 권한 없음.
- 도메인 mutation 경유 + OCC version bump. 마이그레이션 V025 + init_codegen 미러.

## Brainstorming Check

✅ 통과 (직접 sanity check). Version 날짜 패턴 + securityLevelId 3-state 두 동형 선례로 설계 공간 닫힘. 인터랙티브 brainstorming/office-hours 생략(memory: bts-spec-office-hours-mismatch — 잘 명세된 FR엔 직접 기술 스펙). 유일 미결(교차검증)은 Maxi AskUserQuestion으로 "검증 없음" 확정.

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
