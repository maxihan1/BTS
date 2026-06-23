<!-- FR-BL-01 백로그 우선순위 정렬(LexoRank) 구현 계획 -->
# FR-BL-01 — 백로그 우선순위 정렬 (LexoRank)

> slug: backlog-lexorank
> type: api
> agent: backend-engineer
> primary_bc: issue-tracking (도메인 확정 — rank는 issue 스칼라 속성)
> 생성: 2026-06-23
> ⚠️ 진실출처: 이 plan 파일 (.bts-cache/classify.json은 멀티세션 충돌로 FR-SR-01에 덮어써짐)

## Brief

FR-BL-01 백로그 우선순위 정렬 (LexoRank) — agile-planning BC 백엔드.
- issues.rank 컬럼 (SDD §13.2.1 VARCHAR(50) 알파벳 키)
- LexoRank 알고리즘 (backend/shared/lexorank.kt 자체 구현, §1.1 선행)
- PATCH /api/v1/issues/{key}/rank 리랭크 API (위/아래 이웃 중간값)
- 자동 rebalance (주 1회 백그라운드 + 중간값 고갈 시 트리거)
- 1K 부하 테스트 (insert/move 평균 < 5ms)

프론트 UI(D6)는 FR-BL-02와 통합 예정 — 이번 PR 제외.

classify: { type: api, agent: backend-engineer, primary_bc: issue-tracking(검증필요) }
SDD §13.2.1 / product agile-planning.md §3.1 / fr-index §3.1

## 도메인 정리

- **구현 BC**: issue-tracking (product 분류는 agile-planning §3.1이나, rank는 issue 스칼라 속성 → issue-tracking 소유. FR-PL-01 일정 필드 선례 동일).
- **영향 엔티티**: Issue (issues.rank 컬럼 신규). shared-kernel에 LexoRank Rank VO 신규.
- **새 용어**:
  - LexoRank — 이미 glossary 존재 ("순서 보존 알고리즘, 보드/백로그 정렬"). 변경 없음.
  - Rank VO — LexoRank 키의 값 객체 (VARCHAR(50) 알파벳 문자열). glossary 추가 후보.
  - rebalance(재균형) — 중간값 고갈/누적 시 rank 키를 재배포. glossary 추가 후보.
- **BC 경계 결정** (Maxi 확정):
  1. rank 소유 = issue-tracking (issues.rank 컬럼 + IssueController `PATCH /api/v1/issues/{key}/rank`).
  2. LexoRank 알고리즘 = shared-kernel `com.bts.shared.lexorank` 순수 VO.
- **기존 결정 충돌**: 없음. FR-BD-01 ADR(결정 3)이 LexoRank를 FR-BL-01로 명시 이연 → 본 작업이 그 이연을 정식 도입.
- **product/SDD drift**: product §1.1 `backend/shared/lexorank.kt` 경로는 실제와 불일치 → shared-kernel로 정정 (전수 동기화 대상). product D3 `issues.rank`(TEXT) vs SDD §13.2.1 `VARCHAR(50)` → spec에서 컬럼 타입 확정.
- **관련 ADR**: [docs/decisions/2026-06-23-fr-bl-01-lexorank-backlog-ordering.md](../decisions/2026-06-23-fr-bl-01-lexorank-backlog-ordering.md) (생성됨)

## 스펙

전체 스펙. [docs/specs/2026-06-23-backlog-lexorank.md](../specs/2026-06-23-backlog-lexorank.md)

핵심 결정 (Maxi 확정).
- 리랭크 계약: `PATCH /api/v1/issues/{key}/rank {previousIssueKey?, nextIssueKey?}` — 서버가 이웃 rank 조회 후 between 계산.
- rebalance: on-demand만 (중간값 고갈 시 즉시, advisory lock 직렬화).
- 초기 rank: 생성 시 자동부여(맨 끝) + V029 백필, NOT NULL, VARCHAR(50).
- rank=프로젝트 전역 키 / no-bump last-write-wins / tie-break ORDER BY rank,id / 권한 UPDATE 재사용 / history 미기록.

## Brainstorming Check

✅ 통과 (adversarial self-review 1회, gap 5건 발견·반영: 정렬 스코프·rank 중복 tie-break·OCC→no-bump·락 범위·소프트삭제 404).

## Plan

> Gradle 모듈: `:modules:shared-kernel`, `:modules:issue-tracking`. 검증 명령은 `backend/`에서 실행.
> 모듈 단위 컴파일 직렬화(메모리 bts-plan-wave-gradle-module-compile): T3~T7은 같은 issue-tracking 모듈 → 파일 안 겹쳐도 컴파일은 모듈 일괄.

### Task 1. shared-kernel Rank VO — between / initial / 고갈 예외

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/shared-kernel/src/main/kotlin/com/bts/shared/lexorank/Rank.kt`, `backend/modules/shared-kernel/src/test/kotlin/com/bts/shared/lexorank/RankTest.kt`]
- depends-on: []

**RED**: `RankTest` —
- `initial()`이 중간값 키 반환(끝문자≠'a'), 1~50자 a–z 검증.
- `assertEquals(Rank.initial(), Rank.between(null, null))` — initial==between(null,null) 동치 (N3).
- `between("b","z")` 결과가 "b" < r < "z" 사전순.
- `between("b","c")` 인접 → 길이 증가, prev < r < next 유지, **끝문자≠'a'**.
- **`between("b","bc")` 결과가 'a'로 끝나지 않음** (B2 — prefix 케이스 trailing-a 회피).
- `between(null, X)` < X, `between(X, null)` > X, 모두 끝문자≠'a'.
- Comparable 정렬 일관(문자열 사전순 == Rank 순서).
- 50자 내 키 생성 불가 시 `RankSpaceExhaustedException`.
- 잘못된 값("", 대문자, 51자, **끝문자 'a'**) → `require` 실패.

**GREEN**: `Rank` `@JvmInline value class`(`init { require(...) }`) + `companion object { between/initial/of }`. base-26 a–z, 경계 하한/상한 처리. **between은 trailing-a를 절대 반환 안 함**(중간값이 'a'로 끝나면 한 자리 연장, B2). 50자 초과 시 `RankSpaceExhaustedException`.

**REFACTOR**: 알파벳/경계 상수 추출, KDoc(중괄호·백틱 금지 — 메모리 ktlint-kdoc-brace).

**검증**: `cd backend && ./gradlew :modules:shared-kernel:test --tests "*RankTest"`

### Task 2. V029 마이그레이션 — rank 컬럼 + 백필 + NOT NULL + 인덱스 + init_codegen 미러

**메타**.
- agent: `db-engineer`
- files: [`backend/modules/issue-tracking/src/main/resources/db/migration/issue-tracking/V029__issue_rank.sql`, `backend/modules/issue-tracking/src/main/resources/db/codegen/init_codegen.sql`, `backend/modules/issue-tracking/src/test/kotlin/.../migration/IssueRankMigrationTest.kt`]
- depends-on: []

**RED**: 마이그레이션 테스트 — V029 적용 후 `issues.rank` NOT NULL, 기존 행 백필됨, 프로젝트별 created_at 순 == rank 순, 인덱스 `idx_issues_project_rank` 존재.

**GREEN**: `ADD COLUMN rank VARCHAR(50)` → 프로젝트별 `row_number() OVER (PARTITION BY project_id ORDER BY created_at, id)` 기반 균등 고정폭 base-26 키 백필 → `SET NOT NULL` → `CREATE INDEX (project_id, rank)`. init_codegen.sql의 issues 정의에 `rank VARCHAR(50)` 인라인 미러(메모리 jooq-init-codegen-mirror).
- **백필 구현 방법** (eng 리뷰 C1): base-26 다자리 인코딩(N>26)은 순수 `chr()` UPDATE로 불가 → 마이그레이션 내 PL/pgSQL 인라인 함수(`CREATE FUNCTION ... DO $$ ... $$` 후 DROP) 또는 Kotlin Flyway Java migration 택1. 고정폭(예 3자리)이면 충분 여유(26^3=17,576 > 1K). 끝문자 'a' 회피하도록 인코딩(예 base-26을 'b'~'z' 25개 + 마지막 자리 non-a).
- 대용량 마이그레이션 락: 백필 UPDATE가 issues 전체 락 → 운영 영향 주석. (현재 규모 1K라 무해)
- ⚠️ V029 번호는 머지 직전 재확인(메모리 migration-vnumber, 동시 FR-SR-01).

**REFACTOR**: 백필 SQL 주석(균등 분포 의도), 한 줄 헤더 주석.

**검증**: `cd backend && ./gradlew :modules:issue-tracking:flywayMigrate :modules:issue-tracking:generateJooq` + 마이그레이션 테스트.

### Task 3. IssueRepository — rank no-bump UPDATE + 이웃/정렬 조회

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/repository/IssueRepository.kt`, `backend/modules/issue-tracking/src/test/kotlin/.../repository/IssueRankRepositoryTest.kt`]
- depends-on: [2]

**RED**: `IssueRankRepositoryTest`(Testcontainers) —
- `updateRank(key, rank)` 가 rank만 변경하고 `version` 불변(no-bump 검증) + **`updated_at` 불변**(C4, assignee no-bump 선례).
- `findRankByKey(key)` 반환.
- `findRanksForRebalance(projectId)` 가 `ORDER BY rank, id` (tie-break)로 (key, rank) 목록 반환, 소프트삭제 제외.
- `findMaxRank(projectId)` (생성 시 맨 끝 부여용).

**GREEN**: jOOQ 구현(V029 codegen 의존). no-bump = `UPDATE issues SET rank=? WHERE key=?` (version·updated_at 미증가).

**REFACTOR**: SQL 상수, KDoc(no-bump 사유 = 메모리 no-bump-sidecar-version, updated_at 미갱신 사유 = 드래그 noise).

**검증**: `cd backend && ./gradlew :modules:issue-tracking:integrationTest --tests "*IssueRankRepositoryTest"`

### Task 4. BacklogRankService — 리랭크 + 생성 시 자동부여 + on-demand rebalance(advisory lock)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/application/BacklogRankService.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/application/IssueApplicationService.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/application/InvalidRankNeighborException.kt`, `backend/modules/issue-tracking/src/test/kotlin/.../application/BacklogRankServiceTest.kt`]
- depends-on: [1, 3]

**RED**: `BacklogRankServiceTest`(mockk repo + 분기 단위) —
- `rerank(actor, key, prev?, next?)`: UPDATE 권한 검증(`IssuePermission.UPDATE`), 대상/이웃 조회, 이웃 rank로 `between`, no-bump update.
- 이웃 검증: 둘 다 null/대상==이웃/타 프로젝트/순서 역전 → **`InvalidRankNeighborException`**(B1 — `IllegalArgumentException` raw throw 금지, catch-all 500 방지); 이웃/대상 미존재·소프트삭제 → `IssueNotFoundException`(404).
- 고갈(`RankSpaceExhaustedException`) → `rebalance(projectId)` → **rebalance 후 prev/next rank를 `findRankByKey`로 재조회**(C3, 키가 재배포됐으므로) → between 재계산. advisory lock = **`pg_advisory_xact_lock(hashtextextended(projectId::text, 0))`** (C2, identity-access ExternalAccountRepository 선례, UUID→bigint) + lock 후 재조회(TOCTOU, 메모리 advisory-lock-bigint).
- `createIssue` 흐름에서 신규 이슈에 `between(findMaxRank, null)` 자동부여.
- rank 변경은 `IssueChangeDetector`에 **등록하지 않음**(history noise 회피) — 단위 테스트로 history 미생성 확인.

**GREEN**: `BacklogRankService` 신규(`@Service @Transactional`, ArchUnit 통과). `InvalidRankNeighborException` 신규(도메인 예외). `IssueApplicationService.createIssue`에서 rank 자동부여 호출.

**REFACTOR**: 검증 헬퍼 추출, KDoc.

**검증**: `cd backend && ./gradlew :modules:issue-tracking:test --tests "*BacklogRankServiceTest"`

### Task 5. PATCH /{key}/rank 컨트롤러 + DTO + 에러 매핑

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/adapter/inbound/rest/IssueController.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/adapter/inbound/rest/dto/RerankIssueRequest.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/adapter/inbound/rest/IssueExceptionHandler.kt`, `backend/modules/issue-tracking/src/test/kotlin/.../rest/IssueRankControllerTest.kt`]
- depends-on: [4]

**RED**: `IssueRankControllerTest`(MockMvc, service mock) — `PATCH /api/v1/issues/{key}/rank` 200(rank/version 응답), **400(`InvalidRankNeighborException`)**, 403(권한), 404(이슈 없음). 도메인예외 → HTTP 상태 매핑 검증: `InvalidRankNeighborException`이 **실제로 400**으로 나오는지(catch-all `Exception`→500이 삼키지 않는지) MockMvc로 단언(B1 + 메모리 domain-exception-http-handler-basepackage-scope / catch-all-swallows).

**GREEN**: `@PatchMapping("/{key}/rank")` + `RerankIssueRequest(previousIssueKey?, nextIssueKey?)` + `BacklogRankService` 위임 + `DataResponse`. **`IssueExceptionHandler`에 `@ExceptionHandler(InvalidRankNeighborException::class)` → 400 추가**(B1). 응답 version은 service가 반환한 issue.version(no-bump 불변, N1).

**REFACTOR**: DTO @Valid, KDoc.

**검증**: `cd backend && ./gradlew :modules:issue-tracking:test --tests "*IssueRankControllerTest"`

### Task 6. HTTP 통합 테스트 — S1~S5 / E1~E12

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/test/kotlin/.../integration/IssueRankIntegrationTest.kt`]
- depends-on: [5]

**RED→GREEN**: 실 repo + 시드(메모리 issue-tracking-transition-test-mocks) end-to-end — S1(사이 이동) S2(맨앞) S3(맨뒤) S4(생성 자동부여) S5(고갈→rebalance 투명) + E2/E3/E4/E6/E7(400) E5/E9(404) E10(동시 rebalance 직렬) E11(tie-break) + 권한 403. (E7=둘다 null→400 HTTP 레벨 추가, N2.) 1개 이상 일부러 위반 넣어 vacuous 아님 확인(메모리 archunit-vacuous).

**검증**: `cd backend && ./gradlew :modules:issue-tracking:integrationTest --tests "*IssueRankIntegrationTest"`

### Task 7. 1K 부하 테스트 (NFR2 / NFR3)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/test/kotlin/.../performance/BacklogRankLoadTest.kt`]
- depends-on: [4]

**RED→GREEN**: 1,000개 이슈 백로그 — 반복 삽입 시 키 길이 증가 제한 검증, 평균 리랭크 < 5ms, 1K rebalance < 500ms 측정·assert. 동시실행 flaky 주의(메모리 concurrent-testcontainers-suite-flaky) → 단독 실행 가이드 주석.

**검증**: `cd backend && ./gradlew :modules:issue-tracking:integrationTest --tests "*BacklogRankLoadTest"`

## Plan 메타

- task 수: 7
- 예상 wave: 5 (W1: T1‖T2 / W2: T3 / W3: T4 / W4: T5‖T7 / W5: T6). issue-tracking 모듈 컴파일 직렬 요인 존재.
- TDD 강제: yes (RED→GREEN→REFACTOR, test 커밋 선행)
- 병렬 dispatch: bts-impl이 depends-on + files로 wave 계산
- 추가 검증: ktlint, detekt(baseline), ArchUnit(@Transactional @Service), generateJooq

## 리뷰 결과

### plan-eng-review (2026-06-23, 독립 backend-engineer dispatch — autoplan overkill 회피)

**BLOCKER 3건 — 모두 plan/spec 수정으로 해소 완료.**
- B1. `IllegalArgumentException` → catch-all이 500으로 삼킴(400 매핑 없음). → 커스텀 `InvalidRankNeighborException` + `IssueExceptionHandler` 명시 핸들러(T4·T5, spec #12).
- B2. `between("a","ac")="aa"` trailing-a 생성 가능 → FR1 위반. → between이 trailing-a 미반환 보장(자리 연장), T1 RED에 `between("b","bc")` 끝문자≠a 테스트 추가(spec FR2 불변식).
- B3. spec S1의 `rank a`가 FR1(끝문자≠a)과 모순. → S 시나리오 예시 rank를 FR1 준수값으로 교체 + "예시일 뿐" 명시.

**CONCERN 4건 — 모두 반영.**
- C1. 백필 SQL 방법(base-26 다자리) → PL/pgSQL 인라인 또는 Flyway Java 명시(T2).
- C2. advisory lock 키 변환 → `hashtextextended(projectId::text, 0)` (UUID→bigint, identity-access 선례, T4).
- C3. rebalance 후 이웃 rank 재조회 명시(T4).
- C4. `updated_at` 갱신 안 함(assignee no-bump 선례, T3, spec #13).

**NIT 3건 — 반영.** N1(version=조회한 issue.version, T5/spec #14), N2(E7 HTTP 통합 추가, T6), N3(initial==between(null,null) assertion, T1).

**종합 판정**: 수정 후 진행 → **수정 완료, 진행 가능.**

### plan-devex-review
- API 계약(`PATCH /{key}/rank`, 이웃 키 기반)은 기존 `/api/v1/issues` 네임스페이스와 일관. 신규 엔드포인트라 하위호환 깨짐 없음. 별도 BLOCKER 없음(eng 리뷰가 계약·에러 매핑까지 커버).
