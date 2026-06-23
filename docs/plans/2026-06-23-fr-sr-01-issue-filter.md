# FR-SR-01 — 이슈 필터 (다중 필드 조합)

> slug: fr-sr-01-issue-filter
> type: api
> agent: backend-engineer
> primary_bc: issue-tracking (domain 단계에서 BC 경계 확정)
> 생성: 2026-06-23

## Brief

FR-SR-01 이슈 필터 (다중 필드 조합) — search-export-import BC의 첫 FR.
`GET /api/v1/issues?filter=...` 다중 필드 조합(status, assignee_id, project_id, label) AND/OR jOOQ 동적 쿼리.
백엔드 D1~D5 우선. 새 BC(search-export-import) 부트스트랩 여부 vs issue-tracking 확장은 domain 단계에서 결정.

- classify: type=api, agent=backend-engineer, primary_bc=issue-tracking
- product 문서: docs/plan/product/search-export-import.md §2.1
- §0 진입 조건: FR-AU-09(PAT) 완료 ✓ / issue-tracking 이슈 데이터 안정 ✓ / §1 AQL 파서 PoC는 FR-SR-02용(본 FR 무관)

## 도메인 정리

- **BC(구현)**: issue-tracking (새 BC 신설 안 함 — Maxi 확정)
- **BC(논리 소속)**: search-export-import 유지 (fr-index 매핑 무변경, 논리≠물리)
- **필터 의미론**: 필드 내 OR + 필드 간 AND (BoardCardFilter 동형, Maxi 확정). 임의 AND/OR 트리는 FR-SR-02(AQL) 영역.
- **영향 엔티티**: Issue (기존). 신규 엔티티 0.
- **재사용 자산**:
  - `BoardCardFilter` (shared-kernel/.../board/BoardCardFilter.kt) — assignee/label/component 필터 VO
  - `IssueRepository.buildFilterCondition` / `buildSecurityCondition` / `listWithType` / `listVisibleForBoard` (issue-tracking)
  - `IssueSecurityDirectory.accessibleLevels` (visibility 필터)
  - `IssueController.GET /api/v1/issues` (현재 projectKey+pageable → 필터 파라미터 추가)
- **신규 필요**: status(`current_state_key`) 필터 (기존 BoardCardFilter에 없음). project는 기존 projectKey로 충족.
- **새 용어**: "이슈 필터(Issue Filter)" — 다중 필드 조합 조건 VO. BoardCardFilter 확장/유사. glossary 추가는 Maxi 승인 대기.
- **기존 결정 충돌**: 없음. 관련 ADR `2026-06-02-issue-permission-query-api`(권한 쿼리) / `2026-06-03-version-component-permission-query-and-gating`와 정합(visibility 필터 재사용).
- **관련 ADR**: [docs/decisions/2026-06-23-fr-sr-01-issue-filter-bc.md](../decisions/2026-06-23-fr-sr-01-issue-filter-bc.md) (생성됨)

## 스펙

전체 스펙. [docs/specs/2026-06-23-fr-sr-01-issue-filter.md](../specs/2026-06-23-fr-sr-01-issue-filter.md)

핵심 시나리오 3줄 요약.
- 기존 `GET /api/v1/issues`에 status/assignee/label/component 필터 파라미터 추가 (필드 내 OR, 필드 간 AND)
- count·content 양쪽에 필터 적용 + visibility(보안 수준) 필터 우선(권한 없는 이슈는 필터로도 노출 0)
- 백엔드 D1~D5만 (BoardCardFilter에 statusKeys 확장 / IssueFilterQueryParser 신규 / 보드 회귀 0). D6 프론트·D7 E2E는 후속 PR

## Brainstorming Check

✅ 통과 (1회, 직접 인라인 적대 검토). 발견·반영 — count 필터 누락(FR-7/EC6) · catch-all 400→500 변질(EC3) · 보드 회귀(§8) · label 정확매칭(FR-5) · visibility 우회(FR-8/S7).

## Plan

레이어 의존(VO → Repository → Service → Controller)으로 Task 1→2→3→4는 직렬. Task 5(db 인덱스)는 독립.
시그니처에 default 파라미터를 추가하므로 prod 호출처는 안 깨지나, **mockk stub 테스트는 새 시그니처로 갱신** 필요 → 해당 테스트 파일을 files에 포함.

### Task 1. BoardCardFilter에 statusKeys 필드 추가 (shared-kernel)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/shared-kernel/src/main/kotlin/com/bts/shared/board/BoardCardFilter.kt`, `backend/modules/shared-kernel/src/test/kotlin/com/bts/shared/board/BoardCardFilterTest.kt`]
- depends-on: []

**RED**:
- 파일: `BoardCardFilterTest.kt`
- 테스트:
  - `statusKeys 가 비어있지 않으면 isEmpty 는 false`
  - `statusKeys 만 있어도 isEmpty 는 false (다른 필드 빈 상태)`
  - `EMPTY 는 statusKeys 도 빈 목록`
- 실패: `statusKeys` 프로퍼티 없음 → 컴파일/테스트 실패

**GREEN**:
- `BoardCardFilter`에 `val statusKeys: List<String> = emptyList()` 추가 — **data class 마지막 필드로 추가**(positional 호출 방어; 현재 positional 호출 0건이나 중간 삽입 시 보드 호출 깨짐, C2).
- `isEmpty()`에 `&& statusKeys.isEmpty()` 추가.

**REFACTOR**:
- KDoc "필드 조합 규칙"에 statusKeys(워크플로우 상태 키, 같은 필드 내 OR) 항목 추가. `@property statusKeys` 명시.

**검증**: `./gradlew :modules:shared-kernel:test --tests "*BoardCardFilterTest"`

### Task 2. buildStatusCondition + listWithType filter 파라미터 (IssueRepository)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/repository/IssueRepository.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/repository/IssueRepositoryFilterTest.kt`]
- depends-on: [1]

**RED**:
- 파일: `IssueRepositoryFilterTest.kt` (신규 통합 테스트, `IssueTestcontainersBase` 상속)
- 테스트(시드 후 `listWithType(projectKey, pageable, actor, UNRESTRICTED_ACCESS, filter)`):
  - `status 단일 필터 — 해당 상태 이슈만` (S2)
  - `status 다중값 — 필드 내 OR` (S3)
  - `status + assignee — 필드 간 AND` (S4)
  - `label overlap 필터` (S6)
  - `필터가 count(totalElements)에 반영` (EC6) — 필터 적용 시 total 이 필터 결과 수와 일치
  - `visibility 제한 access + 필터 — 권한 없는 이슈는 결과 0` (S7/EC7)
  - `EMPTY 필터 — 전체 목록(기존 동작)` (EC1)
- 실패: `listWithType` 5번째 파라미터 없음 → 컴파일 실패

**GREEN**:
- `listWithType`에 `filter: BoardCardFilter = BoardCardFilter.EMPTY` 파라미터 추가.
- `buildFilterCondition(filter)` 를 **한 번만 호출**해 그 결과 객체를 count 쿼리와 content 쿼리 **양쪽** where 에 `baseWhere.and(filterCondition)` 로 재사용(null 이면 baseWhere 그대로). 쿼리별 재조립 금지 — count/content drift 차단(C1, EC6).
- `buildFilterCondition`에 `buildStatusCondition(filter)` 추가 — `ISSUES.CURRENT_STATE_KEY.`in`(filter.statusKeys)` (statusKeys 비면 null).

**REFACTOR**:
- `buildStatusCondition` KDoc — current_state_key 정확 매칭(소문자 컨벤션 V004), 같은 필드 OR(IN).

**검증**: `./gradlew :modules:issue-tracking:test --tests "*IssueRepositoryFilterTest"`

### Task 3. IssueApplicationService.listIssues filter 파라미터

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/application/IssueApplicationService.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/application/IssueApplicationServiceListTest.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/application/IssueFieldVisibilityTest.kt`]
- depends-on: [2]

**RED**:
- 파일: `IssueApplicationServiceListTest.kt`
- 테스트: `listIssues 가 filter 를 repo.listWithType 에 그대로 전달` — `verify { repo.listWithType(projectKey, pageable, any(), any(), filter) }` (5-arg).
- 실패: `listIssues` 4번째 파라미터 없음 → 컴파일 실패. **주의(B1)** — 기존 `repo.listWithType(p, pg, a, any())` mockk `every` stub(`IssueApplicationServiceListTest:104,151` · `IssueFieldVisibilityTest:323,469`)은 default 인자로 **컴파일은 통과**하나, `any()` 매처와 자동충전 literal default 혼합으로 **런타임 매처 mismatch(가짜 그린/실패)** 발생. 4개 stub 라인 모두 `(p, pg, a, any(), any())` 5-arg 로 갱신 필수.

**GREEN**:
- `listIssues`에 `filter: BoardCardFilter = BoardCardFilter.EMPTY` 파라미터 추가 → `repo.listWithType(projectKey, pageable, actor.value, access, filter)` 전달.
- 기존 mockk stub 갱신(`IssueApplicationServiceListTest`, `IssueFieldVisibilityTest`의 `repo.listWithType(p, pg, a, any())` → `(p, pg, a, any(), any())`).

**REFACTOR**:
- KDoc `@param filter` 추가.

**검증**: `./gradlew :modules:issue-tracking:test --tests "*IssueApplicationServiceListTest" --tests "*IssueFieldVisibilityTest"`

### Task 4. IssueFilterQueryParser 신규 + IssueController.list 확장

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/adapter/inbound/rest/IssueFilterQueryParser.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/adapter/inbound/rest/IssueFilterQueryParserTest.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/adapter/inbound/rest/IssueController.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/adapter/inbound/rest/IssueControllerReadTest.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/adapter/inbound/rest/IssueExceptionHandler.kt`]
- depends-on: [3]

**RED**:
- 파일: `IssueFilterQueryParserTest.kt` (신규 단위)
  - `status/assignee/label/component 파싱 → BoardCardFilter`
  - `assignee=unassigned 센티널 → includeUnassigned`
  - `blank 값 무시 / 모두 비면 EMPTY` (EC2)
  - `잘못된 UUID(assignee/component) → ResponseStatusException 400` (EC3)
- 파일: `IssueControllerReadTest.kt`
  - `필터 파라미터 → service.listIssues(filter) 전달` (mock verify, list mock 을 4-arg 로 갱신)
  - `잘못된 UUID 요청 → 400 AND errorCode == VALIDATION_FAILED` — **status 400뿐 아니라 errorCode 까지 단언**(B2 가짜그린 차단)
- 실패: `IssueFilterQueryParser` 없음 / `IssueController.list` 필터 파라미터 없음 / 400 errorCode 가 INTERNAL_ERROR

**GREEN**:
- `IssueFilterQueryParser` 구현 — `BoardFilterQueryParser` 동형(같은 trim/센티널/UUID 400 규칙) + status 파싱. **agile-planning import 금지**(자체 구현).
- `IssueController.list`에 `@RequestParam(required=false) status/assignee/label/component: List<String> = emptyList()` 추가 → 파서 → `service.listIssues(actor, projectKey ?: "", pageable, filter)`.
- **B2 errorCode 교정** — `IssueExceptionHandler.handleResponseStatus`(이미 존재, IssueExceptionHandler.kt:659~)의 when 절은 현재 `else -> INTERNAL_ERROR`라 400이 `INTERNAL_ERROR`로 오매핑됨. `HttpStatus.BAD_REQUEST -> IssueErrorCodes.VALIDATION_FAILED to "요청 파라미터가 올바르지 않습니다."` 분기 추가. (핸들러 신규 추가 아님 — 기존 핸들러 보강.)

**REFACTOR**:
- 파서 KDoc + 센티널 상수. 파일 L1 한국어 헤더 주석.

**검증**: `./gradlew :modules:issue-tracking:test --tests "*IssueFilterQueryParserTest" --tests "*IssueControllerReadTest"`

### Task 5. db 인덱스 검토 (D3)

**메타**.
- agent: `db-engineer`
- files: [`backend/modules/issue-tracking/src/main/resources/db/migration/issue-tracking/V0NN__issue_filter_indexes.sql`, `backend/db/init_codegen.sql`]
- depends-on: []

**RED/GREEN** (마이그레이션은 TDD 변형 — 적용 검증):
- 필요성 판단 — 이슈 목록은 항상 `project_id` 단위(`idx_issues_project_id_deleted_at` 존재). status/assignee 필터 빈도·프로젝트당 이슈 규모 기준으로 `(project_id, current_state_key)` / `(project_id, assignee_id)` 부분/복합 인덱스 추가 가치 판단.
- 추가 시: 신규 `V029__issue_filter_indexes.sql`(현재 최신 = V028__issue_epic_link.sql 실측, 신규 = **V029** — 단 **FR-BL-01 worktree 마이그레이션과 V번호 충돌 머지 직전 재확인**, 메모리 migration-vnumber-concurrent-branch-collision) + `init_codegen.sql` 미러(메모리 jooq-init-codegen-mirror, 인덱스는 코드젠 무관이나 일관성 유지).
- 불필요 판단 시: **이 Task 생략 + plan §리뷰에 "인덱스 미추가 사유" 명시**(product D3 deviation 기록).

**검증**: `./gradlew :modules:issue-tracking:flywayMigrate` 또는 통합 테스트 부팅 시 마이그레이션 적용 확인.

## Plan 메타

- task 수: 5
- 예상 wave: 4 (Wave1: T1+T5 병렬 / Wave2: T2 / Wave3: T3 / Wave4: T4) — 레이어 의존으로 T1→T2→T3→T4 직렬, T5 독립
- 예상 시간: 직렬 약 20분, 병렬 wave 적용 약 15분
- TDD 강제: yes (각 task RED→GREEN→REFACTOR)
- 추가 검증: ktlint, detekt, `:modules:issue-tracking:test` 전체, `:modules:shared-kernel:test`
- 회귀 가드: 보드(FR-BD-02) 테스트 그대로 통과(BoardCardFilter statusKeys 기본값으로 보드 동작 불변)

## 리뷰 결과

### plan-eng-review + plan-devex-review (2026-06-23, 독립 에이전트 적대 리뷰 + 직접 실측)

종합 판정: **조건부 yes** → 발견 2 BLOCKER + 4 CONCERN 모두 plan/spec에 반영 완료. 구현 진행 가능.

**BLOCKER (반영 완료)**.
- **B1** mockk stub 깨짐 메커니즘 정정 — "컴파일 실패"가 아니라 default+`any()` 혼합 **런타임 매처 mismatch(가짜 그린 위험)**. Task 3 RED에 4개 stub 라인(ListTest:104,151 · VisibilityTest:323,469) 5-arg 갱신 명시.
- **B2** EC3 핸들러 결함 — `IssueExceptionHandler.handleResponseStatus`(이미 존재, :659~)의 `else -> INTERNAL_ERROR`가 400을 오매핑. 실측 확인(`IssueErrorCodes.VALIDATION_FAILED` 존재 :741). Task 4에 `BAD_REQUEST -> VALIDATION_FAILED` 분기 추가 + IssueExceptionHandler.kt를 files에 추가 + 테스트 errorCode 단언.

**CONCERN (반영 완료)**.
- **C1** count/content에 동일 `filterCondition` 객체 재사용(쿼리별 재조립 금지) — Task 2 GREEN 명시.
- **C2** statusKeys data class 마지막 필드 추가(positional 방어) — Task 1 GREEN 명시.
- **C3** V번호 V028 실측 → 신규 V029 — Task 5 반영(머지 직전 FR-BL-01 충돌 재확인).
- **C4** DevEx — status 키 discoverability + assignee UUID-only 트레이드오프 — spec §8 기록.

**칭찬(설계 견고성 실측 확인)**.
- 레이어 의존 직렬화 정확(VO→Repo→Service→Controller, Gradle 모듈 경계 일치).
- 보안 AND 결합 구조적 안전 — `buildActiveSecureWhere` 위에 filter를 다시 AND, 필터로 visibility 우회 불가(S7/EC7).
- BC 격리 명확(BoardFilterQueryParser cross-BC import 금지, IssueFilterQueryParser 자체 신설).
- 하위호환 보장(신규 파라미터 모두 optional + EMPTY default), 파라미터 명명 보드와 일관.

BLOCKER: 없음(2건 모두 반영). 머지 차단 사유 0.
