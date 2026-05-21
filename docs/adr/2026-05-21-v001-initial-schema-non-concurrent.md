<!-- ADR: V001 초기 스키마 CREATE INDEX CONCURRENTLY 미적용 — 빈 테이블 락 영향 0 -->

# ADR — V001 초기 스키마 인덱스 CONCURRENTLY 예외

**일자**. 2026-05-21
**상태**. Accepted
**관련 PR**. project-workflow-bc-fr-wf-01-fsm-1-pr
**작성자**. Maxi + Claude (backend-engineer)

## 컨텍스트

DATA.md §4.3 마이그레이션 작성 규칙.

> **인덱스는 `CONCURRENTLY`** (PostgreSQL) — 대형 테이블 락 회피

이 규칙은 운영 중인 테이블에 인덱스를 추가할 때 전체 테이블 락(ACCESS SHARE LOCK)으로 인한 쿼리 차단을 방지하기 위해 존재한다. `CREATE INDEX CONCURRENTLY`는 테이블 락 없이 백그라운드에서 인덱스를 구축한다.

그러나 Flyway는 각 마이그레이션 파일을 **단일 데이터베이스 트랜잭션** 안에서 실행한다. PostgreSQL은 트랜잭션 블록 내부에서 `CREATE INDEX CONCURRENTLY`를 허용하지 않는다.

```
ERROR: CREATE INDEX CONCURRENTLY cannot run inside a transaction block
```

V001 마이그레이션(`V001__init_workflow.sql`)은 project-workflow BC의 초기 스키마를 구성한다.

- 5개 테이블. `workflows`, `workflow_states`, `workflow_transitions`, `workflow_validators`, `workflow_post_actions`
- 6개 FK 인덱스. `idx_workflow_states_workflow`, `idx_workflow_transitions_workflow`, `idx_workflow_transitions_from`, `idx_workflow_transitions_to`, `idx_workflow_validators_transition`, `idx_workflow_post_actions_transition`

V001은 **최초 배포 시점에 한 번만 실행**된다. 이 시점에 5개 테이블은 모두 빈 테이블이다.

## 결정

**V001 초기 스키마 한정으로 `CREATE INDEX CONCURRENTLY`를 미적용한다.**

6개 인덱스 모두 Flyway 기본 트랜잭션 안에서 `CREATE INDEX`(비동시)로 생성한다.

근거.

1. **락 영향 0** — V001 실행 시점에 5개 테이블에는 row가 존재하지 않는다. 빈 테이블에 인덱스를 생성하면 테이블 스캔이 없으므로 락 유지 시간이 사실상 0이다. 운영 트래픽 차단 위험이 없다.
2. **Flyway 트랜잭션 일관성 유지** — V001 전체(5 CREATE TABLE + 6 CREATE INDEX)를 단일 트랜잭션으로 처리하면 부분 실패 시 완전 롤백이 보장된다. 트랜잭션을 분리하면 중간 실패 복구가 복잡해진다.
3. **DATA.md §4 룰의 목적 일치** — 해당 룰의 목적은 "대형 테이블 락 회피"이다. 빈 테이블에는 회피할 락 영향이 없으므로 규칙의 목적이 충족된다.

## 영향

### 긍정

- V001 마이그레이션이 단일 트랜잭션으로 원자적으로 실행된다.
- 초기 배포 시 부분 실패 복구가 명확하다.
- Flyway 기본 설정 변경 없이 동작한다.

### 준수 사항 — 후속 마이그레이션

**본 ADR은 V001만의 예외이다.** V002 이후 모든 인덱스 추가는 DATA.md §4.3 원칙을 적용한다.

구체적 방법.

1. `CREATE INDEX CONCURRENTLY`는 트랜잭션 블록 밖에서 실행해야 한다. Flyway에서는 해당 파일 상단에 `-- ;flyway: transactional=false` 마커를 추가하거나, 별도 SQL 파일로 분리해 트랜잭션 없이 실행한다.
2. 예시.
   ```sql
   -- ;flyway: transactional=false
   CREATE INDEX CONCURRENTLY idx_issues_assignee ON issues(assignee_id);
   ```
3. `idx_workflow_transitions_to`처럼 FK 컬럼에는 반드시 인덱스를 동반한다(DATA.md §7 FK 인덱스 룰).

### 관련 ADR

- `2026-05-21-workflow-yaml-vs-db-storage.md` — YAML 시드 전략 및 advisory lock
- `2026-05-21-workflow-validator-terminology.md` — Validator 명명 결정

## 관련 파일

- `DATA.md §4` — 마이그레이션 작성 규칙 (인덱스 CONCURRENTLY 원칙 위치)
- `backend/modules/project-workflow/src/main/resources/db/migration/V001__init_workflow.sql` — 본 ADR 예외가 적용되는 마이그레이션 파일
- `docs/plans/2026-05-21-project-workflow-bc-fr-wf-01-fsm-1-pr.md` Task 3 — V001 6 인덱스 명세 (CONCERN-6, CONCERN-9 해소)
