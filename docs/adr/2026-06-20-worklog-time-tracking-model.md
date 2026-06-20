# ADR — Worklog 시간 추적 모델 (FR-TT-01)

> 날짜: 2026-06-20
> 상태: 채택
> 관련 FR: FR-TT-01 (Worklog — 추정/실제/잔여 시간)
> 관련 SDD: §5.9 (Worklog 데이터 모델), 02-requirements §2.2.6
> 관련 PR: #163

## 맥락

FR-TT-01은 이슈별 작업 시간 로그(Worklog)와 추정/실제/잔여 시간을 다룬다. fr-index는 이 FR을 `agile-planning` BC로 표기하지만, 해당 백엔드 모듈은 아직 부트스트랩되지 않았다(현재 5개 모듈: identity-access, issue-tracking, notification, project-workflow, shared-kernel).

SDD §5.9는 Worklog 등록 시 `Issue.time_spent`, `Issue.remaining_estimate`를 자동 갱신한다고 명시한다. 그러나 실제 `issues` 테이블에는 시간 관련 컬럼(`time_spent`, `remaining_estimate`, `original_estimate`)이 마이그레이션된 적이 없다.

## 결정

### D1. BC 배치 — issue-tracking

Worklog는 `issue-tracking` BC에 구현한다(신규 agile-planning 모듈 부트스트랩 안 함).

**근거**.
- Worklog 등록/수정/삭제는 `Issue.time_spent` / `Issue.remaining_estimate`를 갱신한다 → Issue 애그리거트와 강하게 결합. 별도 BC로 분리하면 매 worklog CRUD가 cross-BC 이벤트가 되어 복잡도가 급증한다.
- 같은 agile-planning BC로 표기된 FR-PL-01(일정 필드)·FR-PL-02(지연 알림)도 동일하게 issue-tracking에 구현된 선례가 있다.
- classify-task의 `primary_bc` 판정도 issue-tracking.
- fr-index의 논리적 BC 표기(agile-planning)는 유지한다(FR-PL과 동일 — 물리 모듈 ≠ 논리 BC 라벨). FR 카운트 변동 없음.

### D2. Worklog 엔티티 모델

`worklogs` 테이블 — Issue 애그리거트의 자식.

| 필드 | 타입 | 설명 |
|---|---|---|
| id | UUID (PK) | Worklog ID |
| issue_id | UUID (FK → issues, ON DELETE CASCADE) | 대상 이슈 |
| author_id | UUID | 작업자 (BC 격리 — users FK 미적용) |
| time_spent_seconds | INT NOT NULL (CHECK > 0) | 소요 시간 (초) |
| started_at | TIMESTAMPTZ NOT NULL | 작업 시작 시각 |
| comment | TEXT NULL | 작업 설명 |
| created_at / updated_at | TIMESTAMPTZ NOT NULL DEFAULT NOW() | 감사 |

**id 타입 deviation**. SDD §5.9는 BIGINT를 명시하나 실제 issues 테이블은 UUID 채택(구 SDD 설계 ↔ 실제 구현 분기). 일관성을 위해 UUID 사용.

**SDD §5.9 대비 deviation**. `visibility` 컬럼(PUBLIC/TEAM_ONLY/PRIVATE)은 FR-TT-01 범위에서 제외한다(D4 참조).

**삭제 정책**. worklog는 외부 참조(이슈 키 같은)가 없는 자식 엔티티 → watcher/attachment 선례처럼 WHERE 절 명시 하드 삭제. 삭제 후 time_spent 재집계.

### D3. issues 테이블 시간 컬럼 신설

FR-TT-01이 다음 컬럼을 `issues`에 추가한다(SDD §5.9 + §5.1 설계가 명시하나 미마이그레이션 상태).

| 컬럼 | 타입 | 의미 | 출처 |
|---|---|---|---|
| original_estimate_seconds | INT NULL | 원 추정 (사용자 설정) | 사용자 직접 입력 |
| time_spent_seconds | INT NOT NULL DEFAULT 0 | 실제 누적 소요 | worklog 합으로 갱신 |
| remaining_estimate_seconds | INT NULL | 잔여 추정 | 자동 차감 + 수동 override |

마이그레이션 번호. issue-tracking 자체 V-series → **V027** (issue-tracking 하위 폴더). `init_codegen.sql` 미러 필수(jOOQ 코드젠).

### D4. 가시성 — 이슈 권한만 따름

Worklog는 해당 이슈를 VIEW할 수 있는 사용자에게 모두 노출된다. 항목별 visibility 컬럼/권한 필터는 두지 않는다.

**근거**. 단순성 우선(CLAUDE.md §2). 항목별 가시성은 권한 매트릭스·조회 필터 복잡도를 크게 늘리는데 1K 사내 협업 워크스페이스에서 즉시 필요성이 낮다. 필요 시 후속 FR로 `visibility` 컬럼 + 필터를 추가한다.

### D5. 추정 의미론 — 자동 차감 + 수동 override

- **time_spent**. 항상 worklog `time_spent_seconds`의 합 = 신뢰 출처(SoT). worklog CRUD 시 재계산.
- **remaining_estimate**. Log Work 시 기본은 `remaining = max(0, remaining − time_spent)` 자동 차감. 요청에 명시적 새 잔여값(`new_remaining_estimate`)이 오면 그 값으로 설정(수동 override).
- **original_estimate**. 사용자가 직접 설정. worklog와 무관(자동 갱신 안 함).
- 수정/삭제 시 time_spent 재집계. remaining 자동 차감은 **신규 등록(POST) 시점에만** 적용(수정/삭제는 SoT 재계산 충돌 회피 — 상세는 spec).

## 결과

- 신규 엔티티 Worklog + 신규 issue 시간 컬럼 3종.
- API. `POST/PATCH/DELETE /api/v1/issues/{key}/worklogs` (product §5.1). 추정 필드는 이슈 PATCH 확장 또는 worklog 응답에 포함(상세는 spec).
- 트랜잭션 경계. worklog INSERT/UPDATE/DELETE + issue 시간 컬럼 갱신은 한 트랜잭션(issue-tracking 절대 규칙 — 이슈+히스토리+이벤트 단일 트랜잭션).
- 권한. worklog 작성은 이슈 코멘트/업데이트 권한 관례 재사용(상세는 spec + security-engineer 검토).

## 미해결 (spec 위임)

- 추정 필드 갱신 API 형태(이슈 PATCH 확장 vs worklog 응답 포함).
- worklog 수정/삭제 시 remaining 재계산 정책 세부.
- 변경 이력(IssueHistory) 기록 여부 — worklog 추가가 changelog에 남는가.
