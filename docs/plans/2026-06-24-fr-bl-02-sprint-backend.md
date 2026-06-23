<!-- FR-BL-02 백로그→스프린트 이동 백엔드(D1~D5) 구현 계획 -->
# FR-BL-02 — 백로그 → 스프린트 드래그 이동 (백엔드 D1~D5)

> slug: fr-bl-02-sprint-backend
> type: api
> agent: backend-engineer
> primary_bc: agile-planning 단독 (Sprint + sprint_issues 조인 — ADR 2026-06-24, issues 무변경)
> 생성: 2026-06-24
> ⚠️ 진실출처: 이 plan 파일 (.bts-cache/classify.json은 멀티세션 충돌 가능)

## Brief

FR-BL-02 백로그→스프린트 이동의 **백엔드 D1~D5만** 이번 PR 범위.
- Sprint 도메인 신설 (agile-planning BC 단독)
- `sprints` + `sprint_issues` 조인 테이블 (agile-planning, V503+ / issues 무변경)
- 스프린트 CRUD API + 이슈→스프린트 할당/해제 API
- 권한(security-engineer 공동 검토), 백엔드 테스트

프론트 D6/D7(@dnd-kit 백로그↔스프린트 드래그)은 **이번 PR 제외** — 후속에서 FR-BL-01 D6/D7(백로그 정렬 UI)과 통합.

classify: { type: api, agent: backend-engineer, primary_bc: agile-planning }
product agile-planning.md §3.2 / SDD §13 / fr-index §3.2
배경: docs/plan/README.md §2(stash 보관) — FR-BL-02가 cross-BC 병목(리포트 4종 FR-RP-01~04 선행)으로 최우선 지목됨.

## 도메인 정리

- **BC**: agile-planning **단독**. issue-tracking 무변경(board 선례 — cross-BC는 shared-kernel 포트로만 통신).
- **신규 엔티티**:
  - `Sprint` (agile-planning) — 프로젝트 단위 작업 기간. 상태(라이프사이클)·기간·목표 보유.
  - `SprintIssue` 연관 — `sprint_issues(sprint_id, issue_id)` 조인. `issue_id`는 UUID **느슨 참조**(cross-BC FK 없음).
- **신규 테이블**: `sprints`, `sprint_issues` (agile-planning 마이그레이션 V503+). issues 테이블 변경 없음.
- **cross-BC 이슈 조회**: 기존 `BoardIssueLookupPort` 재사용/확장(board가 이슈를 읽는 패턴 동일). issue-tracking 직접 import 금지.
- **용어**: 스프린트(Sprint)·백로그(Backlog)는 glossary에 이미 정의됨 → 신규 용어 0. (sprint 상태 enum 명칭은 spec에서 확정.)
- **관계 모델 결정 (Maxi 확정)**: `sprint_issues` 조인 테이블 (모델 B). product §3.2 D3의 `issues.sprint_id`(모델 A)는 BC 격리·회귀위험(FR-BL-01 rank 254 파급류) 사유로 기각.
- **product/SDD drift 정정 대상**: agile-planning.md §3.2 D3 `issues.sprint_id` → `sprint_issues 조인`. fr-index/SDD 동기화는 머지 PR에서 전수 반영.
- **관련 ADR**: [docs/decisions/2026-06-24-fr-bl-02-sprint-issue-association.md](../decisions/2026-06-24-fr-bl-02-sprint-issue-association.md) (생성됨)
- **기존 결정 충돌**: 없음. FR-BD board 패턴과 일관. FR-BL-01 rank(issue-tracking)와는 별개 영역(rank=정렬, sprint=그루핑).

## 스펙

전체 스펙. [docs/specs/2026-06-24-fr-bl-02-sprint-backend.md](../specs/2026-06-24-fr-bl-02-sprint-backend.md)

핵심 결정 (Maxi 확정).
- 범위: 스프린트 CRUD + 이슈 할당/해제 + 상태전이(PLANNED→ACTIVE→COMPLETED). 스프린트 내 순서(rank)는 이연. 동시 ACTIVE 다중 허용.
- 관계: `sprint_issues(sprint_id, issue_id)` 조인, `UNIQUE(issue_id)`로 1:N 강제(다른 스프린트 할당 시 원자적 이동). issues 무변경.
- 9개 엔드포인트(`/api/v1/sprints` CRUD 5 + start/complete 2 + 이슈 할당/해제 2) + 백로그 조회 1. 권한 `IssuePermission`(CRUD/관리=CREATE, 조회=BROWSE) + `IssuePermissionResolver` 재사용(board 선례).
- 백로그(미할당) 조회는 `GET /api/v1/sprints/backlog` — BoardIssueLookupPort 가시 이슈 − sprint_issues.
- version: 할당/해제=no-bump, 상태전이/메타수정=bump. 소프트삭제 시 연관 제거→백로그 복귀.
- 데이터: V503 `sprints` + `sprint_issues`(agile-planning), init_codegen 미러.

## Brainstorming Check

✅ 통과 (adversarial self-review 1회, gap 3건 발견·반영: 백로그 조회 API 누락·소프트삭제 연관 처리·version 동시성 정책). Maxi 추가 결정 불필요.

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
