# BC 별 Flyway 마이그레이션 번호 prefix 정책

- **상태**. accepted
- **결정자**. Maxi (PR #18 진행 중, 2026-05-26)
- **컨텍스트**. FR-WF-02 (project-workflow BC WorkflowScheme + Jira align)

## 결정

BTS 의 모든 모듈은 단일 PostgreSQL schema (`public`) 를 공유하므로 Flyway 가
모듈별 마이그레이션 파일을 합쳐서 적용한다. cross-BC 의존이 도입된 모듈에서
같은 버전 번호 (`V001`) 가 두 모듈 모두에 존재하면 Flyway 는
`Found more than one migration with version 001` 으로 실패한다.

이를 막기 위해 **BC 별 100단위 번호 범위** 를 사전 할당한다.

| BC                 | 범위        |
|--------------------|------------|
| identity-access    | V001~V099  |
| issue-tracking     | V001~V099  |
| project-workflow   | V200~V299  |
| automation (예정)  | V300~V399  |
| 새 BC              | V400+      |

`identity-access` 와 `issue-tracking` 은 cross-BC 의존이 도입되기 전부터
V001 부터 시작했으므로 grand-fathered (그대로 유지). project-workflow 가
issue-tracking 을 cross-BC import 하면서 처음 충돌이 노출되었고, 본 결정에
따라 V200~ 으로 이동되었다.

## 배경 (왜 문제가 발생했는가)

### cross-BC 의존 도입

본 PR (#18, FR-WF-02) 에서 project-workflow 가 issue-tracking 의
`IssueTypeId`, `IssueTypeKey` 를 직접 import 하기 위해
`implementation(project(":modules:issue-tracking"))` 를 추가했다 (ADR
`issue-type-cross-bc-introduction` 결정).

이 결과 project-workflow 의 testRuntimeClasspath 에 issue-tracking 의
`db/migration/V001__issues_initial.sql` 가 포함되었고, project-workflow
자신의 `V001__init_workflow.sql` 와 동일 버전 번호 충돌이 발생했다.

### 운영 영향

본 PR 진행 시점 (2026-05-26) 에 운영 DB 가 미배포 상태이므로 (Phase 0
진입 직전 ~ 진입 중), V001 → V200 rename 으로 인한
`flyway_schema_history` 충돌은 발생하지 않는다.

## 대안 검토

### 대안 A — 임시 fix (project-workflow V001만 V010 등으로 단조 증가)

- 장점. 가장 작은 변경.
- 단점. 다음 PR 에서 또 다른 BC 가 같은 영역에 V010 을 쓰면 재충돌.
  컨벤션 불명확.

### 대안 B — BC prefix 컨벤션 도입 (본 결정)

- 장점. 미래 BC 추가 시 자동으로 안전. 컨벤션 명확.
- 단점. 본 PR 의 변경 범위 약간 확장 (DATA.md + ADR + V001 rename + jOOQ
  codegen URL + IT 주석 정리).

### 대안 C — BC 별 PostgreSQL schema 격리

- 장점. 가장 깔끔. Flyway history 도 schema 별 분리.
- 단점. 운영 schema 분리 + Spring DataSource per BC + jOOQ codegen per
  schema 등 대규모 변경. 본 PR scope 초과.

→ **B 선택**. 미래 안전 + 본 PR 안에서 완결 가능.

## 영향

- **DATA.md §4.1** 에 BC 별 번호 범위 표 추가.
- **project-workflow**.
  - `V001__init_workflow.sql` → `V200__init_workflow.sql` rename.
  - `V004__workflow_schemes.sql` 는 단조 증가 컨벤션 유지 (issue-tracking
    V003 직후) 라 그대로.
  - `build.gradle.kts:140` jOOQ codegen TC_INITSCRIPT URL 의 V001 참조 →
    V200 으로 업데이트.
  - `V001MigrationTest.kt` → `V200MigrationTest.kt` 파일/클래스 rename.
  - 기타 IT 주석에 V001 언급 정리.
- **identity-access / issue-tracking**. 변경 없음 (grand-father).
- **운영 DB**. 미배포 상태이므로 영향 없음.

## 검증

- `./gradlew :modules:project-workflow:test` → 0 fail (V001 충돌로 실패하던 11건 회복).
- `./gradlew :modules:issue-tracking:test` → 회귀 없음 (issue-tracking 변경 없음).
