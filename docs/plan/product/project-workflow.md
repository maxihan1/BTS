<!-- project-workflow BC — 워크플로우 FSM 2 FR + 워크플로우 FSM PoC + pgmq 트랜잭션 PoC -->

# project-workflow BC

**소속 FR**. 2개 (WF 2).
**책임**. 상태 기계(FSM) 기반 워크플로우, YAML 정의, 전이 검증, 후처리.
**SDD 참조**. 07장 (워크플로우 엔진).
**다른 BC와의 경계**. issue-tracking BC의 상태 전이 호출을 받아 검증. pgmq 이벤트 발행.

## §0 진입 조건

- [ ] identity-access §2.1, §4.4 (워크플로우 관리 권한) 완료
- [ ] DATA.md §트랜잭션 + §pgmq 규칙 숙지
- [ ] §1 기술 검증 통과 (아래)

## §1 기술 검증

### §1.1 워크플로우 FSM PoC (3일)

**SDD**. 07장. **checklist.md 위임**. §1.1. **ADR 후보**. 없음.

- [x] PostgreSQL 테이블 스키마 1차 안정 (Flyway V001) (PR #10, 2026-05-22)
- [x] Spring Boot 진입점 (`./gradlew :backend:bootRun` 성공) (PR #10, 2026-05-22)
- [x] TDD 사이클 1회 완료 (`test:` → `feat:` → `refactor:`) (PR #10, 2026-05-22)
- [x] Testcontainers 통합 테스트 1개 통과 (상태 전이 invariant 검증) (PR #10, 2026-05-22)
- [x] Maxi 검토 통과 (PR #10, 2026-05-22)

### §1.2 pgmq 트랜잭션 일관성 PoC (1일)

**SDD**. 03.6. **checklist.md 위임**. §1.5. **ADR 후보**. **pgmq 이미지 선정** (fr-index.md §A.3 #1).

- [x] pgmq 이미지 선정 ADR 작성 (`docs/adr/<date>-pgmq-image.md`) (PR #10 backend + PR #14 ADR pgmq-postgres-image, 2026-05-22)
- [x] PostgreSQL 16 + pgmq 컨테이너 기동 (PR #10 backend + PR #14 ADR pgmq-postgres-image, 2026-05-22)
- [x] `pg_trgm` 확장 설치 (PR #10 backend + PR #14 ADR pgmq-postgres-image, 2026-05-22)
- [x] jOOQ routine 래퍼 (pgmq 함수 호출) (PR #10 backend + PR #14 ADR pgmq-postgres-image, 2026-05-22)
- [x] 트랜잭션 일관성 통합 테스트 — "이슈 생성 ↔ 알림 큐 발행" 동일 트랜잭션 (롤백 시 큐도 롤백) (PR #10 backend + PR #14 ADR pgmq-postgres-image, 2026-05-22)

## §2 워크플로우 (FR-WF, 2개)

### §2.1 FR-WF-01 — FSM 워크플로우 (상태/전이/조건/검증/후처리)

**우선순위**. 필수 | **선행**. §1 | **Plan slug**. `workflow/fsm`

- [x] D1. 도메인 — Workflow Aggregate, State, Transition, Guard, PostAction (책임. backend-engineer + Maxi) (PR #10, 2026-05-22)
- [x] D2. 명세 — Given/When/Then. 표준 4종 워크플로우 (Software Dev, Bug Tracking, Service Desk, Task) (책임. backend-engineer) (PR #10, 2026-05-22)
- [x] D3. 데이터 모델 — `workflows`, `workflow_states`, `workflow_transitions`, `workflow_guards` (책임. db-engineer) (PR #10, 2026-05-22)
- [x] D4. 백엔드 — `WorkflowEngine` + 상태 전이 API + invariant 검증 (책임. backend-engineer) (PR #10, 2026-05-22)
- [x] D5. 백엔드 테스트 — TDD + property-based test (전이 무결성) (책임. backend-engineer) (PR #10, 2026-05-22)
- [x] D6. 프론트 UI — 워크플로우 다이어그램 (mermaid 또는 SVG) (책임. designer → frontend-engineer) (PR #13, 2026-05-22; 후속 cleanup PR #16/#20/#21)
- [x] D7. E2E + NFR — 상태 전이 6단계 시나리오 (책임. qa-engineer) (PR #13 + PR #19, 2026-05-22 ~ 2026-05-23; 후속 cleanup PR #16/#20/#21)

| 항목 | 임계 | 실측 (p95) |
|---|---|---|
| 상태 전이 처리 | 100ms | ___ |

### §2.2 FR-WF-02 — 프로젝트별 워크플로우 스킴 + 타입별 매핑

**우선순위**. 필수 | **선행**. §2.1 | **Plan slug**. `workflow/scheme`

- [x] D1. 도메인 — WorkflowScheme (책임. backend-engineer) — PR #18
- [x] D2. 명세 (책임. backend-engineer) — PR #18
- [x] D3. 데이터 모델 — `workflow_schemes`, `project_workflow_scheme_map`, `scheme_issue_type_workflow` (책임. db-engineer) — PR #18
- [x] D4. 백엔드 — Scheme 관리 API — REST 10 endpoint (Scheme CRUD 5 + Mapping 2 + Project assignment 2 + IssueType read 1) + 예외 핸들러 (책임. backend-engineer + security-engineer) — PR #18
- [x] D5. 백엔드 테스트 — 단위·Controller·Repository 통합 완료 (책임. backend-engineer) — PR #18. *S1~S8 시나리오 통합테스트 + ADR·SDD 정정은 Wave 6 후속*
- [ ] D6. 프론트 UI — 프로젝트 설정 → 워크플로우 (책임. designer → frontend-engineer)
- [ ] D7. E2E (책임. qa-engineer)

## §NFR project-workflow BC 완료 게이트

### 측정값 기록표

| 항목 | 임계 | 실측 (p95) | 비고 |
|---|---|---|---|
| 상태 전이 처리 (FSM) | 100ms | ___ | k6 (단일 트랜잭션 + pgmq 이벤트) — k6 + axe 도입 후속 |
| 스킴 매핑 조회 | 50ms | ___ | k6 — k6 + axe 도입 후속 |
| 워크플로우 다이어그램 렌더 | 1s | ___ | Playwright — k6 + axe 도입 후속 |
| pgmq 트랜잭션 롤백 정합성 | 100% | ___ | 통합 테스트 — k6 + axe 도입 후속 |
| WCAG 2.1 AA | 0 violations | ___ | axe-core — k6 + axe 도입 후속 |

> **Deferred trigger**. §NFR 5건 미실측은 silent 영구 보류 아님. trigger — (a) FR-WF-02 (스킴 매핑) 머지 후 + (b) `docs/adr/*-k6-load-testing.md` + `docs/adr/*-axe-accessibility.md` 2건 ADR 발행 시점에 측정 일괄 진행. Maxi 1인 선언으로 trigger 조정 가능.

### BC 완료 조건

> **§2 진척**. FR-WF-01 ✅ / FR-WF-02 backend(D1~D5) ✅ 머지 #18 (main 663f1b4) — 후속. D6 프론트 · D7 E2E · Wave 6(S1~S8 통합테스트 + ADR/SDD) · C1 detekt 정합

- [ ] §2 (FR-WF 2개) 모두 `[x]` 마킹
- [ ] §NFR 측정표 모든 항목 임계 통과 (위 deferred trigger 충족 후)
- [ ] pgmq ADR (§A.3 #1) 발행 완료
- [ ] CHANGELOG.md 정리
- [ ] README.md §7 변경 이력에 "project-workflow BC 완료 — YYYY-MM-DD" 추가
- [ ] Maxi 1인 선언 — "project-workflow BC 완료"
