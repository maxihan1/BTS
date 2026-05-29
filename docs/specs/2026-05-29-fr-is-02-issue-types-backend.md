<!-- FR-IS-02 이슈 타입 백엔드(D1~D5) 스펙 — 커스텀 IssueType CRUD + hierarchy_level + Issue.type 연결 -->

# FR-IS-02 — 이슈 타입 (Epic/Story/Task/Subtask/Bug + 커스텀) 백엔드 (D1~D5, 1-PR)

> BC. issue-tracking | slug. fr-is-02-issue-types-backend | agent. backend-engineer (마이그레이션 task만 db-engineer)
> 범위. 백엔드만 (D1~D5). 프론트(D6)·E2E(D7) 제외 — transition-e2e(PR #34) 정리 후 별 PR.
> 사전 도입분 재활용(FR-WF-02 PR #31). `issue_types` V003 + IssueType Aggregate(factory + 표준 5종) + IssueTypeRepository + read-only GET API.
> 관련 ADR. [issue-type-cross-bc-introduction](../adr/2026-05-29-issue-type-cross-bc-introduction.md)

## 1. 사용자 시나리오 (Given-When-Then)

### S1. 프로젝트 admin 이 커스텀 이슈 타입 생성
- **Given** admin 이 인증됨, `key="spike"`, `name="Spike"`, `hierarchyLevel=0`
- **When** `POST /api/v1/issue-types`
- **Then** 201 + 생성된 IssueType(`isStandard=false`). `key` 는 활성 타입 중 유일.

### S2. admin 이 커스텀 타입 수정
- **Given** 커스텀 타입(`isStandard=false`) 존재
- **When** `PATCH /api/v1/issue-types/{id}` 로 name/description/iconName/hierarchyLevel 부분 변경
- **Then** 200 + 변경 반영. `key` 와 `isStandard` 는 변경 불가(요청에 와도 무시 또는 거부).

### S3. 표준 타입 수정/삭제 차단
- **Given** 표준 타입(`isStandard=true`, 예 `task`)
- **When** `PATCH` 또는 `DELETE /api/v1/issue-types/{id}`
- **Then** 409 `ISSUE_TYPE_STANDARD_IMMUTABLE`. 변경 없음.

### S4. 미사용 커스텀 타입 삭제
- **Given** 커스텀 타입을 가진 이슈가 0건
- **When** `DELETE /api/v1/issue-types/{id}` (reassignTo 없음)
- **Then** 204. 타입 소프트 삭제(`deleted_at` 설정).

### S5. 사용 중 커스텀 타입 삭제 — 재할당 필요
- **Given** 커스텀 타입을 가진 활성 이슈가 N(>0)건
- **When** `DELETE /api/v1/issue-types/{id}` (reassignTo 없음)
- **Then** 409 `ISSUE_TYPE_IN_USE` + 본문에 사용 건수(N).

### S6. 사용 중 커스텀 타입 삭제 — 재할당 후 삭제 (Jira 방식)
- **Given** 커스텀 타입 A 를 가진 이슈 N건, 대상 타입 B(활성)
- **When** `DELETE /api/v1/issue-types/{A}?reassignTo={B}`
- **Then** 204. **한 트랜잭션**에 (a) A 타입 이슈 N건의 `type_id`→B, (b) A 소프트 삭제. 부분 실패 없음(전부 롤백).

### S7. 이슈 생성 시 타입 지정 / 미지정
- **Given** 인증된 사용자
- **When** 이슈 생성 시 `typeId` 지정 → 그 타입. 미지정 → 표준 `task`.
- **Then** 모든 이슈는 유효한 활성 타입을 가리킴(NOT NULL 보장).

### S8. 5 표준 + 커스텀 타입 조회 (read-only, 기존 API)
- **Given** 표준 5종 + 커스텀 타입
- **When** `GET /api/v1/issue-types`
- **Then** 200 + 활성(`deleted_at IS NULL`) 타입 목록. 소프트 삭제된 타입 제외.

## 2. 기능 요구사항 (FR)

| ID | 요구사항 |
|---|---|
| FR-1 | 커스텀 IssueType 생성 — `POST /api/v1/issue-types`. `key`/`name` 필수, `hierarchyLevel`/`description`/`iconName` 선택. 생성분은 항상 `isStandard=false` |
| FR-2 | 커스텀 IssueType 수정 — `PATCH`. `name`/`description`/`iconName`/`hierarchyLevel` 만 변경 가능. `key`/`isStandard` 불변 |
| FR-3 | 커스텀 IssueType 삭제 — `DELETE`. 소프트 삭제. 사용 중이면 `reassignTo` 필수 |
| FR-4 | 표준 타입(`isStandard=true`) PATCH/DELETE 완전 차단(409) |
| FR-5 | `reassignTo` 지정 시 해당 타입 이슈 일괄 `type_id` 변경 + 원 타입 소프트 삭제를 단일 트랜잭션 처리 |
| FR-6 | `issues.type_id` NOT NULL FK 신규. 기존/미지정 이슈 default = `task` 타입 id |
| FR-7 | `Issue.create()` 에 `typeId` 필수 파라미터 추가. 기존 호출자(IssueApplicationService) 반영 |
| FR-8 | IssueType 에 `hierarchyLevel`(Int) 속성. `issue_types.hierarchy_level` 컬럼 ADD + 5표준 backfill(Epic=1, Story/Task/Bug=0, Subtask=-1) |
| FR-9 | (G1) 삭제 "사용 중" 판정 = `issues.type_id` 참조 **OR** 워크플로우 스킴 매핑 참조. 후자는 **SPI 포트(`IssueTypeUsagePort`)** 경유 — issue-tracking 이 outbound 포트 정의, project-workflow 가 inbound adapter 구현(스킴 매핑 카운트 질의). `ON DELETE RESTRICT` FK 는 물리 삭제 전용이라 소프트 삭제엔 무력 → 애플리케이션 레벨 가드 필수 |
| FR-10 | (G3) read-only `IssueTypeResponse` DTO + GET 응답에 `hierarchyLevel` 노출 |
| FR-11 | (G4) `IssueResponse`(이슈 조회 응답)에 type 요약 노출 — `typeId`(Long) + `typeKey`(String) + `typeName`(String) |
| FR-12 | (G2) `reassignTo` 일괄 변경 시 각 이슈 `version`+1(낙관적 잠금 정합). pgmq 이벤트는 발행 생략(소비자 FR-HS 미구현 — 후속 도입 시 재검토) |

## 3. 비기능 요구사항 (NFR)

| ID | 요구사항 |
|---|---|
| NFR-1 | TDD red→green→refactor. MockK 단위 + Testcontainers 통합. 사용 중 삭제(S5)·재할당(S6)·표준 차단(S3) 통합 테스트 필수 |
| NFR-2 | 절대 규칙(DEVELOPMENT.md §1) — DELETE는 소프트 삭제, `@Transactional` 클래스는 `@Service`(ArchUnit), 권한 가드 |
| NFR-3 | 재할당 이슈 일괄 변경은 단일 SQL UPDATE(이슈별 루프 금지). 사용 건수 카운트는 스칼라 서브쿼리(cartesian product 회피) |
| NFR-4 | RFC 7807 ProblemDetail `errorCode` 일관 (`ISSUE_TYPE_*`) |

## 4. API 인터페이스 (REST)

### 4.1 커스텀 CRUD (신규)
```
POST   /api/v1/issue-types                      201 | 400 | 409(ISSUE_TYPE_KEY_DUPLICATE)
PATCH  /api/v1/issue-types/{id}                 200 | 404 | 409(ISSUE_TYPE_STANDARD_IMMUTABLE)
DELETE /api/v1/issue-types/{id}                 204 | 404 | 409(ISSUE_TYPE_STANDARD_IMMUTABLE)
DELETE /api/v1/issue-types/{id}?reassignTo={x}  204 | 404 | 409(ISSUE_TYPE_IN_USE | reassignTo 검증 실패)
```
### 4.2 read-only (기존, IssueTypeController)
```
GET    /api/v1/issue-types                      200  (활성 타입 목록)
```
### 4.3 errorCode (RFC 7807)
| errorCode | HTTP | 상황 |
|---|---|---|
| `ISSUE_TYPE_KEY_DUPLICATE` | 409 | 활성 타입 중 key 중복 |
| `ISSUE_TYPE_KEY_INVALID` | 400 | key 형식 위반 (`^[a-z][a-z0-9-]{1,29}$`) |
| `ISSUE_TYPE_STANDARD_IMMUTABLE` | 409 | 표준 타입 PATCH/DELETE 시도 |
| `ISSUE_TYPE_IN_USE` | 409 | 사용 중인데 reassignTo 없음 (본문에 usageCount = 이슈 참조 + 스킴 매핑 참조 합산). 스킴 매핑 참조는 reassignTo 로 해소 불가하므로 별도 안내 필드(`schemeMappingCount`) 포함 |
| `ISSUE_TYPE_REASSIGN_TARGET_INVALID` | 409 | reassignTo 가 미존재/삭제됨/자기자신 |
| `ISSUE_TYPE_NOT_FOUND` | 404 | id 미존재 또는 이미 삭제됨 |

## 5. 데이터 모델 (DB schema)

### 5.1 신규 마이그레이션 (issue-tracking 모듈 namespace, V005 예정) — 책임. db-engineer
```sql
-- (a) issue_types 에 hierarchy_level 추가 + 5 표준 backfill
ALTER TABLE issue_types ADD COLUMN hierarchy_level INT NOT NULL DEFAULT 0;
UPDATE issue_types SET hierarchy_level = 1  WHERE key = 'epic';
UPDATE issue_types SET hierarchy_level = -1 WHERE key = 'subtask';
-- story/task/bug 은 default 0 유지

-- (a') (B1) key 부분 unique 교체 — soft-delete 된 key 재사용 허용 (Jira UX). 리뷰 BLOCKER B1.
-- V003 의 테이블 레벨 `key ... UNIQUE` 는 soft-delete row 까지 포함 → 재사용 차단됨.
ALTER TABLE issue_types DROP CONSTRAINT issue_types_key_key;   -- 실제 제약명은 \d 로 확인 후 적용
DROP INDEX IF EXISTS ix_issue_types_key_active;                -- 기존 non-unique 부분 인덱스 정리
CREATE UNIQUE INDEX ux_issue_types_key_active ON issue_types (key) WHERE deleted_at IS NULL;

-- (b) issues.type_id FK (NOT NULL, default = task)
ALTER TABLE issues ADD COLUMN type_id BIGINT;
UPDATE issues SET type_id = (SELECT id FROM issue_types WHERE key = 'task' AND deleted_at IS NULL) WHERE type_id IS NULL;  -- (C4) 활성 task 단건 방어
ALTER TABLE issues ALTER COLUMN type_id SET NOT NULL;
ALTER TABLE issues ADD CONSTRAINT fk_issues_type_id FOREIGN KEY (type_id) REFERENCES issue_types(id);
CREATE INDEX ix_issues_type_id ON issues (type_id);
```
> 마이그레이션 (a)(a')(b) 를 한 파일로 묶을지 분리할지는 plan 단계에서 db-engineer 결정. backfill 은 `task` 타입 id 가 V003 seed 로 활성 존재함을 전제. 제약명(`issue_types_key_key`)은 PostgreSQL 기본 네이밍 추정 — db-engineer 가 실제 확인 후 적용.

### 5.2 도메인 변경
- `IssueType` — `hierarchyLevel: Int` 필드 추가. factory `create()` 에 `hierarchyLevel` 파라미터(default 0). **낙관적 잠금 version 없음**(G5) — 타입 변경은 드물고 관리 작업이라 last-write-wins 허용. 명시적 결정.
- `Issue` — `typeId: IssueTypeId` 필드 추가. `Issue.create()` 시그니처에 `typeId` 필수.
- `IssueTypeResponse`(DTO) — `hierarchyLevel: Int` 추가(G3).
- `IssueResponse`(DTO) — `typeId: Long` + `typeKey: String` + `typeName: String` 추가(G4).

### 5.3 SPI 포트 (cross-BC, G1)
- `IssueTypeUsagePort` (issue-tracking outbound 포트 인터페이스) — `fun countSchemeMappings(issueTypeId: Long): Long`.
- project-workflow inbound adapter 구현 — `workflow_scheme_issue_type_mappings` 에서 해당 type 참조 카운트.
- 패턴 참조. ADR `workflow-bc-cross-bc-port`(PR #10), `workflow-transition-port-result-sealed`(WorkflowResolver SPI 선례).
- **BC 격리 예외**. 본 PR 이 project-workflow 모듈에 adapter 1개 추가. plan-review 에서 정당화 검토 대상.

## 6. 엣지 케이스 (EC)

| ID | 케이스 | 처리 |
|---|---|---|
| EC-1 | key 중복(활성 타입) | 409 `ISSUE_TYPE_KEY_DUPLICATE`. 소프트 삭제된 타입의 key 는 재사용 가능(부분 unique index `WHERE deleted_at IS NULL`) |
| EC-2 | key 형식 위반 | 400 `ISSUE_TYPE_KEY_INVALID`. IssueTypeKey VO 규칙 `^[a-z][a-z0-9-]{1,29}$` |
| EC-3 | reassignTo = 자기 자신 | 409 `ISSUE_TYPE_REASSIGN_TARGET_INVALID` (자기 타입으로 재할당 무의미) |
| EC-4 | reassignTo = 미존재/소프트삭제된 타입 | 409 `ISSUE_TYPE_REASSIGN_TARGET_INVALID` |
| EC-5 | reassignTo = 표준 타입 | 허용 (표준은 유효 대상). 표준은 불변이지만 재할당 *대상* 으로는 가능 |
| EC-6 | hierarchyLevel 범위 밖 | 허용 범위 {-1, 0, 1} 고정. 그 외 400 (Subtask보다 아래/Epic보다 위 위계는 본 FR scope 외) |
| EC-7 | 표준 타입 삭제 + reassignTo 동시 | 409 `ISSUE_TYPE_STANDARD_IMMUTABLE` (표준 차단이 우선) |
| EC-8 | 동시 삭제/재할당 경합 | 이슈 type_id 변경은 단일 트랜잭션 UPDATE. 타입 소프트삭제는 멱등(이미 삭제면 404) |
| EC-9 | PATCH 가 key/isStandard 변경 시도 | 해당 필드 무시(부분 업데이트 화이트리스트). 다른 변경분은 정상 반영 |
| EC-10 | (G1) 타입이 워크플로우 스킴에 매핑됨 + reassignTo 지정 | reassignTo 는 이슈만 재할당. 스킴 매핑은 못 풂 → 409 `ISSUE_TYPE_IN_USE`(schemeMappingCount>0). 사용자가 먼저 스킴에서 해당 타입 매핑 제거해야 삭제 가능 |
| EC-11 | (G1) SPI 포트(project-workflow) 응답 불가/지연 | SPI 질의 실패 시 안전하게 삭제 거부(fail-closed). 정합성 우선 |
| EC-12 | (G2) 재할당 중 일부 이슈가 동시 편집돼 version 충돌 | 일괄 UPDATE 는 `WHERE type_id = A` 로 현재 상태 기준 원자 실행. 개별 version 충돌 예외 대신 전체 트랜잭션 일관(재할당은 관리 작업, 개별 낙관락 충돌 비대상) |

## 7. 제약 조건

- BC 격리. 주 BC = issue-tracking. **예외(G1)** — `IssueTypeUsagePort` SPI 구현을 위해 project-workflow 모듈에 inbound adapter 1개 추가. 직접 import 금지 원칙은 유지(포트 인터페이스 경유). plan-review 에서 예외 정당화.
- `Issue.create()` 시그니처 변경 → 기존 호출자(IssueApplicationService, 테스트 fixture) 모두 갱신. 누락 시 컴파일 실패로 드러남(Kotlin non-null).
- 권한. 본 PR 은 dev/test `AlwaysAllowIssuePermissionResolver` 기반(실 RBAC 가드는 별 FR). 단 엔드포인트에 가드 훅 위치는 security-engineer 검토.
- 프론트/E2E 제외. 본 PR 산출물은 백엔드 + 단위/통합 테스트만.

## 8. 측정 가능한 완료 기준

- [ ] `POST/PATCH/DELETE /api/v1/issue-types` 3 엔드포인트 동작 + RFC 7807 errorCode 6종
- [ ] 표준 타입 PATCH/DELETE 409 (S3) 통합 테스트 통과
- [ ] 미사용 삭제(S4) + 사용중 거부(S5) + 재할당후삭제(S6) 단일 트랜잭션 통합 테스트 통과
- [ ] `issues.type_id` NOT NULL FK 마이그레이션 + 기존 이슈 task backfill 검증(Testcontainers)
- [ ] `issue_types.hierarchy_level` 컬럼 + 5표준 backfill(Epic=1/Subtask=-1) 검증
- [ ] `Issue.create()` typeId 필수 + 미지정 시 task fallback 단위 테스트
- [ ] EC-1~EC-9 엣지 케이스 테스트 커버
- [ ] (G1) `IssueTypeUsagePort` SPI — 스킴 매핑 참조 시 삭제 거부(EC-10) + SPI 실패 시 fail-closed(EC-11) 통합 테스트
- [ ] (G3) GET 응답에 hierarchyLevel 노출 검증
- [ ] (G4) IssueResponse 에 typeId/typeKey/typeName 노출 검증
- [ ] (G2) 재할당 시 이슈 version+1 검증 + 이벤트 미발행 검증
- [ ] `./gradlew :backend:modules:issue-tracking:test` + `ktlintCheck detekt` BUILD SUCCESSFUL
- [ ] ArchUnit `@Transactional`→`@Service` 룰 통과

## 9. Brainstorming Check

✅ 통과 (1회 iteration). 발견 gap 5건 — G1(cross-BC 소프트삭제 정합, SPI 포트로 해소) / G2(재할당 version++/이벤트생략) / G3(hierarchyLevel 응답노출) / G4(IssueResponse type 노출) / G5(IssueType 낙관락 부재 명시). G1/G2/G4 Maxi 결정, G3/G5 자동 보강. 모두 스펙 반영 완료.
