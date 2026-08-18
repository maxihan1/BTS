<!-- ADR: 워크플로우 정의 저장 — YAML seed + DB 직렬화 하이브리드 + advisory lock -->

# ADR — 워크플로우 정의 저장: YAML seed vs DB 단독

**일자**. 2026-05-21
**상태**. ⚠️ **대체됨 (Superseded, 2026-08-18)** — `docs/adr/2026-08-18-workflow-db-as-source-of-truth.md`
**관련 FR**. FR-WF-01 (표준 워크플로우 FSM), FR-WF-02 (커스텀 워크플로우 — deferred)

> **읽기 전 주의 1.** 이 ADR 의 결정(YAML = source of truth, DB = 런타임 캐시)은 **더 이상 유효하지
> 않다.** FR-WF-04 에서 정본이 DB 로 옮겨졌고, YAML 은 빈 DB 를 채우는 최초 1회 부트스트랩과
> 「기본값으로 복원」의 기준으로만 남는다. 재기동 시 DB 를 YAML 로 되돌리는 동작은 폐지됐다.
>
> **읽기 전 주의 2 — 이 문서는 구현된 적 없는 설계를 서술한다.** 2026-08-18 대조 실측 결과, 아래
> §Runtime storage 가 규정한 5테이블 중 실제 이름이 맞는 것이 하나도 없다.
>
> | 이 문서가 적은 것 | 실제 (V200) |
> |---|---|
> | `workflow_definitions` (+ `yaml_hash` 컬럼) | `workflows` — 해시 컬럼 **없음** |
> | `workflow_statuses` | `workflow_states` |
> | `workflow_role_constraints` | 없음 — `workflow_validators` 가 그 역할 |
> | `project_workflow_assignments` | 없음 — V201 `project_workflow_scheme_assignments` |
> | 멱등성 = YAML 바이트 SHA-256 비교 | `YamlSeedService.isDirty()` 필드 단위 비교 |
>
> 이 표의 왼쪽 이름으로 코드를 찾지 마라. 존재하지 않는다.
>
> 이 문서는 **당시 판단의 근거를 남기기 위해** 보존한다. 현행 계약은 새 ADR 을 본다.
**관련 PR**. project-workflow-bc-fr-wf-01-fsm-1-pr
**작성자**. Maxi + Claude (backend-engineer)

## 컨텍스트

워크플로우 정의(상태 목록, 전이 규칙, 역할 제약)는 두 가지 방식으로 표현할 수 있다.

**방식 A — YAML/코드 파일.**

- 코드 저장소 안에 버전 관리. PR 리뷰로 변경 추적 가능.
- 1인 운영(Maxi + Claude Code) 환경에서 변경 이력이 git log에 남는다.
- 앱 부팅 시 파일을 읽어 DB에 적재(seed). 런타임 중에는 DB를 읽기 전용으로 사용.
- 단점. 워크플로우 변경 = 코드 변경 = 재배포 필요.

**방식 B — DB 단독.**

- 런타임에 관리 UI 또는 API로 변경 가능. 재배포 불필요.
- 멀티 테넌트·사용자 정의 시나리오에 유리.
- 단점. 변경 이력 추적이 audit log 테이블에 의존. 코드 리뷰 불가.

**FR-WF-01 범위는 표준 4종 워크플로우.**

- `software-default` / `bug-tracking` / `simple` / `kanban-basic`
- 이 4종은 모든 프로젝트가 공유하는 기반이며, 임의 변경 시 데이터 정합성이 깨진다.

**FR-WF-02 (커스텀 워크플로우 CRUD API)는 이 PR 범위 밖이다.**

- 사용자 정의 워크플로우는 FR-WF-02에서 DB CRUD API로 별도 구현한다.

## 후보 비교

| 항목 | YAML seed | DB 단독 |
|---|---|---|
| 변경 이력 추적 | git log + PR 리뷰 | audit log 테이블 |
| 런타임 변경 | 재배포 필요 | API 호출로 즉시 |
| 코드 리뷰 가능 여부 | 가능 | 불가 |
| 표준 워크플로우 안정성 | 높음 (의도치 않은 변경 차단) | 낮음 (실수 노출) |
| 1인 운영 친화성 | 높음 | 보통 |
| 멀티 인스턴스 시드 충돌 위험 | advisory lock으로 해소 | 해당 없음 |

## 결정

**하이브리드 채택 — 표준 4종은 YAML seed, 커스텀은 FR-WF-02로 deferred.**

### Source of truth

YAML 파일 (코드 저장소 내 `backend/modules/project-workflow/src/main/resources/workflows/*.yml`).

### Runtime storage

PostgreSQL 5개 테이블 (Task 3 V001 마이그레이션 결과).

- `workflow_definitions` — 워크플로우 메타 + YAML SHA-256 해시
- `workflow_statuses` — 상태 목록
- `workflow_transitions` — 전이 규칙
- `workflow_role_constraints` — 역할 제약
- `project_workflow_assignments` — 프로젝트↔워크플로우 매핑

### 시드 정책 (멱등)

앱 부팅 시(`ApplicationReadyEvent`) 각 YAML 파일의 SHA-256 해시를 계산한다.

```
hash(YAML bytes) == workflow_definitions.yaml_hash  →  skip (no-op)
hash 불일치 또는 신규                               →  재적재 (upsert)
```

같은 YAML로 재부팅하면 DB 변경이 발생하지 않는다. 멱등성 보장.

### 동시 갱신 보호 (advisory lock)

멀티 인스턴스 배포 시 두 인스턴스가 동시에 시드를 시도할 수 있다.

```sql
-- 시드 트랜잭션 진입 시 워크플로우 키 해시 기반으로 잠금 획득
SELECT pg_advisory_xact_lock(:workflowKeyHash);
-- 잠금 해제는 트랜잭션 종료 시 자동
```

`pg_advisory_xact_lock`은 트랜잭션 범위 잠금이다 — 트랜잭션이 끝나면 자동 해제되어 데드락 위험이 없다. DATA.md §6 (advisory lock 규칙) 준수.

### 부팅 실패 정책 (fail-fast)

YAML 파일이 스키마 검증에 실패하면 `ApplicationContext` 초기화를 중단한다. 잘못된 워크플로우 정의로 앱이 부팅되는 상황을 차단한다. DEVELOPMENT.md §1 #16 (운영 안전 절대 규칙)과 일치.

## 근거

1. **표준 워크플로우의 안정성 최우선** — 표준 4종이 런타임에 임의 변경되면 이미 배정된 프로젝트의 이슈 상태 전이가 깨질 수 있다. YAML + PR 리뷰가 이 위험을 차단한다.
2. **1인 운영 친화** — 재배포 비용보다 변경 추적 가능성이 더 중요하다. 표준 워크플로우는 자주 바뀌지 않는다.
3. **시드 멱등성** — SHA-256 해시 비교로 같은 YAML 재부팅이 DB를 건드리지 않는다. 운영 중 예기치 않은 쓰기 없음.
4. **advisory lock** — Phase 1 이후 다중 인스턴스 운영에 대비한다. 잠금 비용은 부팅 시 1회이며 무시할 수 있다.
5. **커스텀 워크플로우 확장성 유지** — DB 테이블 구조는 사용자 정의 워크플로우도 수용한다. FR-WF-02에서 CRUD API를 추가하면 하이브리드 모델이 자연스럽게 확장된다.

## 영향

### 긍정

- 표준 워크플로우 변경이 코드 변경 + PR 리뷰로 추적된다.
- 같은 YAML로 재부팅하면 DB 쓰기가 발생하지 않는다 (시드 멱등성).
- 멀티 인스턴스에서도 advisory lock으로 시드 충돌이 없다.
- YAML 스키마 오류 시 부팅이 차단되어 잘못된 상태로 운영되지 않는다.

### 부정 / 위험

- 표준 워크플로우 변경 = 재배포 필요. 긴급 수정 시 배포 파이프라인을 거쳐야 한다. 완화책. 긴급 직접 SQL 패치는 DATA.md §6 hotfix 절차 준수 + 사후 YAML/ADR 갱신.
- YAML 파일이 코드 저장소에 있으므로 워크플로우 비전문 기여자가 오해할 수 있다. 완화책. `workflows/README.md`에 편집 금지 범위 명시 (FR-WF-01 구현 시 작성).

## 대안 채택 조건

- 프로젝트가 멀티 테넌트로 확장되어 테넌트별 표준 워크플로우 변형이 필요해지면 → DB 단독 또는 YAML 템플릿 + DB 오버라이드 패턴으로 전환 (별도 ADR).
- 표준 워크플로우 변경 빈도가 주 1회 이상으로 높아지면 → 런타임 관리 API(FR-WF-02 확장) 고려.

## 관련

- `docs/sdd/07-workflow-engine.md` — 워크플로우 엔진 전체 설계
- `DATA.md §6` — 트랜잭션 + advisory lock 규칙
- `DEVELOPMENT.md §1 #16` — 운영 안전 절대 규칙 (fail-fast)
- `docs/adr/2026-05-21-v001-initial-schema-non-concurrent.md` — V001 마이그레이션 결정
- FR-WF-01 (이 PR), FR-WF-02 (커스텀 워크플로우 — deferred)
