# FR-HS-01 보강 — cross-BC 라벨 박제 (완전 Jira식)

> slug: fr-hs-01-crossbc-label
> type: feature (FR-HS-01 deviation 수정)
> primary_bc: issue-tracking (주) + shared-kernel + identity-access (다중 — Maxi 단계분할 승인)
> agent: backend-engineer (주) + security-engineer (identity-access port 검토)
> 생성: 2026-06-11
> 후속: FR-HS-02 조회 API+UI+E2E는 PR2로 분리 (이 PR 머지 후)

## Brief

FR-HS-02(히스토리 조회 UI) 진행 중, "조회 시 cross-BC 필드(assignee·securityLevel)를 어떻게 표시할까" 결정에서 Maxi가 **완전 Jira식**(기록 시점 표시명 박제)을 선택. Jira는 cross-BC 구분 없이 변경 당시 표시명(`fromString`/`toString`)을 박제해 "당시값 보존"의 감사 무결성을 확보한다.

BTS FR-HS-01(PR #115)은 BC 격리 때문에 cross-BC 필드(assignee·securityLevel)는 기록 시점 표시명 박제를 일부러 비워뒀다(`label=null`). 이 PR은 그 빈칸을 **기록 시점 박제**로 채운다. 그러면 FR-HS-02 조회는 박제된 label만 쓰면 되어 단순해진다.

**범위 결정 (Maxi 게이트)**
- 완전 Jira식 채택 (cross-BC 박제). [AskUserQuestion 2026-06-11]
- 단계 분할: PR1=FR-HS-01 보강(이 문서), PR2=FR-HS-02 조회/UI/E2E. [AskUserQuestion 2026-06-11]
- 한 PR로 전부는 다중-BC 거대 PR이 되어 기각.

## 도메인 정리

- **BC (다중, BC 격리 예외)**: 박제 호출 = issue-tracking, cross-BC 조회 port = shared-kernel 인터페이스 + identity-access 구현.
- **새 엔티티/용어**: 없음. 기존 모델/포트 확장.
- **활용/확장 엔티티 (git grep 실재 확인)**:
  - `IssueChangeLabelResolver`(issue-tracking, `history/`) — 현재 type·resolution·components·versions만 라벨링, assignee·securityLevel·status는 "cross-BC 조회 금지"로 label=null 유지(KDoc line 28-30). **이 PR이 assignee·securityLevel 라벨링 추가**.
  - `UserLookupPort`(shared-kernel, `com.bts.shared.user`) — 현재 `exists(userId)` + `findIdsByUsernames`(username→id). **역방향(userId→표시명) 추가 필요**. 구현체 `UserLookupAdapter`(identity-access).
  - `IssueSecurityDirectory`(shared-kernel, `com.bts.shared.permission`) — 보안등급 cross-BC 창구. **레벨명 조회 추가 필요**. 구현체 `IdentityAccessIssueSecurityDirectory`(identity-access). `IssueSecurityLevel`(identity-access)에 name 보유.
  - `IssueHistoryRecorder`(issue-tracking) — 기록 facade. LabelResolver 호출부.
  - `IssueApplicationService.changeAssignee/assignSecurityLevel`(issue-tracking) — 변경 진입점. `AppChangeAssigneeRequest(assigneeId: UUID?)`는 표시명 미보유 → 기록 시점 cross-BC 조회 불가피.
- **status 필드**: project-workflow BC, stateKey passthrough. Maxi는 assignee·securityLevel만 명시 → **status는 이 PR 범위 밖**(stateKey가 어느 정도 식별 가능, 추후 별도 판단).
- **actor 표시명**: actorId(UUID) group 레벨 저장. 변경 author 식별이라 "당시명 박제"보다 조회 시점 resolve가 일반적(Jira author도 계정 링크). **이 PR 범위 밖** — FR-HS-02 조회에서 처리.

### 기존 결정 뒤집기 (deviation — ADR 갱신 필수)
- ADR `docs/adr/2026-06-11-issue-change-history-model.md` §결정 1 "기록 시 cross-BC 호출 회피" + LabelResolver KDoc "cross-BC 조회 금지"를 **부분 뒤집음**: assignee·securityLevel은 기록 시점 cross-BC **읽기**(표시명 조회)를 허용. 단 graceful degrade(조회 실패 시 label=null 유지, 기록은 진행) — 기존 LabelResolver 정책과 동일.
- ADR에 보강 단락 추가 + 전수 동기화(아래).

### 함정 메모 (learnings/memory)
- cross-BC 조회는 read-only 표시명 한정. 권한/멤버십 직접 조회 금지(메모리 crossbc-permission-resolver-not-role-lookup).
- cross-BC resolver nullable fail-open 주의(메모리 crossbc-resolver-nullable-fail-open) — 표시명 못 가져와도 기록 차단 금지, label=null fallback.
- best-effort catch에 권한 예외 포함 금지(메모리 best-effort-loop-permission-exception) — 단 여기선 표시명 조회라 권한 예외 무관, graceful degrade 적정.
- 기록 트랜잭션 안 cross-BC 호출 추가 → self-invocation/트랜잭션 경계 점검(메모리 transaction-self-invocation-requires-new).

## 스펙

전체 스펙. [docs/specs/2026-06-11-fr-hs-01-crossbc-label.md](../specs/2026-06-11-fr-hs-01-crossbc-label.md)

핵심 요약.
- `IssueChangeLabelResolver`가 assignee(→display_name?:username)·securityLevel(→IssueSecurityLevel.name)을 기록 시점에 표시명 박제. detector가 채운 from/to 값(UUID)을 cross-BC port로 resolve.
- cross-BC port는 shared-kernel 인터페이스에 **default 메서드**로 역방향 추가(UserLookupPort.findDisplayNamesByIds, IssueSecurityDirectory.findLevelNames) → 기존 fake 다수 보호 + fail-safe(빈 Map→label=null). 구현은 identity-access.
- 조회 실패 graceful degrade(label=null, 기록 진행). 기록 트랜잭션 참여(readOnly port). BC 격리 유지(직접 import 0).
- FR-HS-01 기존 테스트(label=null 가정) → 박제 검증으로 갱신. ADR 보강. 테이블 변경 없음(마이그레이션 불요).

## Brainstorming Check

✅ 통과 (직접 sanity check — 완료 FR 보강이라 office-hours/brainstorming 대화형 생략, 메모리 bts-spec-office-hours-mismatch 학습).
- 검토 gap: actor 표시명 박제(→PR2 조회에서 처리), status 박제(Maxi 범위 제외, project-workflow BC), prod 구현 오버라이드 누락 시 fail-safe(label=null 보안 무영향), 통합테스트는 실 repo로 가짜그린 회피.
- 모두 의도적 범위 분리이거나 완료기준으로 커버됨. 신규 BLOCKER 없음.

## Plan

> 선례: `UserLookupPort.findIdsByUsernames`가 이미 `= emptyMap()` default 패턴(FR-MN-01). 같은 패턴 답습.
> `IssueSecurityDirectory`는 prod(`IdentityAccessIssueSecurityDirectory`)/non-prod(`AlwaysAllowIssueSecurityDirectory`) 프로파일 분리. securityLevel 레벨명은 prod 구현에서만 실 조회, non-prod stub은 default(빈 맵) 상속 → non-prod securityLevel label=null.

### Task 1. shared-kernel 역방향 표시명 조회 default 메서드 2개

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/shared-kernel/src/main/kotlin/com/bts/shared/user/UserLookupPort.kt`, `backend/modules/shared-kernel/src/main/kotlin/com/bts/shared/permission/IssueSecurityDirectory.kt`, `backend/modules/shared-kernel/src/test/kotlin/com/bts/shared/user/UserLookupPortDefaultTest.kt`, `backend/modules/shared-kernel/src/test/kotlin/com/bts/shared/permission/IssueSecurityDirectoryDefaultTest.kt`]
- depends-on: []

**RED**:
- 파일: 위 두 `*DefaultTest.kt`
- 테스트: 기존 메서드만 구현한 anonymous fake(`object : UserLookupPort { override fun exists(...)=... }`)로 새 default 호출 → 빈 맵 반환(fail-safe) 검증.
  ```kotlin
  @Test fun `findDisplayNamesByIds default 는 빈 맵을 반환한다`() {
      val port = object : UserLookupPort { override fun exists(id: UUID) = false }
      assertThat(port.findDisplayNamesByIds(setOf(UUID.randomUUID()))).isEmpty()
  }
  ```
- 실패 메시지(예상): `findDisplayNamesByIds` / `findLevelNames` 미존재 (컴파일 에러).

**GREEN**:
- `UserLookupPort`: `fun findDisplayNamesByIds(ids: Set<UUID>): Map<UUID, String> = emptyMap()`
- `IssueSecurityDirectory`: `fun findLevelNames(levelIds: Set<UUID>): Map<UUID, String> = emptyMap()`

**REFACTOR**: KDoc — 용도(역방향 표시명 박제), fail-safe(빈 맵=label 미박제, 보안 무영향), prod override 필수 명시. `findIdsByUsernames` KDoc 톤 답습.

**검증**: `./gradlew :backend:shared-kernel:test`

### Task 2. identity-access UserLookupAdapter.findDisplayNamesByIds 구현

**메타**.
- agent: `security-engineer` (identity-access/** 주영역)
- files: [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/user/UserLookupAdapter.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/user/UserLookupAdapterIntegrationTest.kt`]
- depends-on: [1]

**RED**:
- 파일: `UserLookupAdapterIntegrationTest.kt` (Testcontainers, 기존 있으면 확장)
- 테스트: users 2명 시드 — A(display_name="홍길동", username="hong"), B(display_name=null, username="bob"). `findDisplayNamesByIds(setOf(A, B, 미존재))` → `{A:"홍길동", B:"bob"}` (display_name 우선, null이면 username), 미존재 키 제외. 빈 입력 → emptyMap(쿼리 0).
- 실패 메시지(예상): override 미구현(default 빈 맵 반환) → 시드한 표시명 불일치.

**GREEN**:
- `SELECT id, COALESCE(display_name, username) AS dn FROM users WHERE id IN (:ids)` named parameter 바인딩. 빈 입력 단락(emptyMap). RowMapper로 `UUID -> dn`.

**REFACTOR**: SQL 상수 + KDoc. `findIdsByUsernames` 구현 톤 답습(SQL 인젝션 방어 명시).

**검증**: `./gradlew :backend:identity-access:test --tests '*UserLookupAdapterIntegrationTest'`

### Task 3. identity-access IdentityAccessIssueSecurityDirectory.findLevelNames 구현 (prod)

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/issuesecurity/IdentityAccessIssueSecurityDirectory.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/issuesecurity/IdentityAccessIssueSecurityDirectoryIntegrationTest.kt`]
- depends-on: [1]

**RED**:
- 파일: `IdentityAccessIssueSecurityDirectoryIntegrationTest.kt` (Testcontainers, 기존 있으면 확장)
- 테스트: security level 시드 2건 → `findLevelNames(setOf(lvl1, lvl2, 미존재))` → `{lvl1:name1, lvl2:name2}`, 미존재 제외. 빈 입력 → emptyMap.
- 실패 메시지(예상): override 미구현(default 빈 맵).

**GREEN**: `IssueSecurityLevel` 조회 경로(`IssueSecuritySchemeRepository` 또는 직접 SELECT name FROM issue_security_levels WHERE id IN). non-prod `AlwaysAllowIssueSecurityDirectory`는 default(빈 맵) 상속 — 수정 불요(plan §선례 메모).

**REFACTOR**: SQL/조회 상수 + KDoc(레벨명 read 용도, prod 한정).

**검증**: `./gradlew :backend:identity-access:test --tests '*IdentityAccessIssueSecurityDirectoryIntegrationTest'`

### Task 4. issue-tracking IssueChangeLabelResolver assignee/securityLevel 박제 + 파급 테스트 갱신

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/history/IssueChangeLabelResolver.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/history/IssueChangeLabelResolverTest.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/history/IssueHistoryRecorderTest.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/history/IssueHistoryRecorder.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/architecture/IssueBcArchTest.kt`]
- depends-on: [1]

**RED**:
- 파일: `IssueChangeLabelResolverTest.kt`
- 테스트: fake `UserLookupPort`(findDisplayNamesByIds 맵 반환) + fake `IssueSecurityDirectory`(findLevelNames 맵) 주입.
  - assignee item(fromValue=uuidA, toValue=uuidB) → `fromLabel="홍길동"`, `toLabel="김철수"`
  - securityLevel item(from=lvl1, to=lvl2) → 레벨명 박제
  - 빈 맵(fake 미스) → label=null (graceful)
  - unassign(toValue=null) → toLabel=null, fromLabel 박제
- **기존 null 단언 케이스 갱신(BLOCKER 해소 — eng-review 항목7)**: `IssueChangeLabelResolverTest`의 "label=null 유지 필드" 섹션(현 line 276-296)의 assignee/securityLevel 케이스는 T4 후 RED가 된다 → **삭제하고 "표시명 박제" 케이스로 대체**. status/스칼라 null 유지 케이스는 보존.
- 실패 메시지(예상): `resolveLabels`가 assignee/securityLevel을 그대로 통과(label=null).

**GREEN**:
- 생성자에 `UserLookupPort`, `IssueSecurityDirectory` 주입.
- `resolveLabels(items, projectId)`: 전 items에서 assignee UUID 집합 + securityLevel UUID 집합 수집 → 각 port 1회 batch 조회 → when 분기에서 `FIELD_ASSIGNEE`, `FIELD_SECURITY_LEVEL` 매핑. projectId 무관(port는 글로벌).

**REFACTOR**:
- `resolveAssignee`/`resolveSecurityLevel` 헬퍼 추출(detekt 복잡도 회피, 메모리 fr-hs-01-handoff detekt 헬퍼추출).
- **ArchUnit 회귀 가드 추가(CONCERN 해소 — eng-review 항목2)**: `IssueBcArchTest` 룰 1b 금지 패키지 목록에 `com.atlas.bts.identity.issuesecurity..` 추가(현재 누락 — issue-tracking이 prod 보안등급 구현을 직접 import하면 잡히도록). 일부러 위반 import 넣어 fail 확인 후 제거(메모리 archunit-vacuous-rule-silent-pass).
- 생성자 변경으로 깨진 `IssueHistoryRecorderTest`는 LabelResolver를 `mockk`로 다뤄 영향 적으나 확인. `IssueHistoryRecorder`(main)는 @Service 자동주입이라 무변경 예상(확인).

**검증**: `./gradlew :backend:issue-tracking:test --tests '*IssueChangeLabelResolverTest' --tests '*IssueHistoryRecorderTest'`

### Task 5. assignee 박제 + securityLevel graceful 통합테스트 (issue-tracking 경계)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/history/IssueChangeHistoryE2EIntegrationTest.kt`]
- depends-on: [4]

> **BC 격리 결정(CONCERN 해소 — eng-review 항목6)**: T5는 issue-tracking 경계 안에서 검증한다. 실 `UserLookupAdapter`/`IdentityAccessIssueSecurityDirectory`(identity-access)를 직접 쓰지 않는다 → issue-tracking에 identity-access testImplementation 의존 추가 불필요, BC 격리 유지. 실 adapter SQL은 T2(`UserLookupAdapterIntegrationTest`)·T3(`IdentityAccessIssueSecurityDirectoryIntegrationTest`)가 각 BC 경계에서 검증한다(no-cross-bc-deployment-assembly 정합). T5는 fake `UserLookupPort`에 표시명 stub을 주입해 "변경 → 이력 기록 → label 박제"의 issue-tracking 내부 흐름을 검증한다(가짜그린 아님 — LabelResolver/Recorder 통합 + 박제 사슬 실측).

**부팅 실패 해소(BLOCKER — eng-review 항목4)**: 이 테스트는 `@ContextConfiguration` 명시 빈 등록(component scan 없음) + `@ActiveProfiles("test")`다. T4에서 `IssueChangeLabelResolver` 생성자에 `IssueSecurityDirectory`가 추가되면 `HistoryE2EConfig`의 LabelResolver 빈이 의존성 주입 실패 → `UnsatisfiedDependencyException`. **`HistoryE2EConfig`에 `IssueSecurityDirectory` 빈(`AlwaysAllowIssueSecurityDirectory()`) + `UserLookupPort` fake 빈을 명시 등록**해 부팅 실패를 막는다.

**RED**:
- 파일: `IssueChangeHistoryE2EIntegrationTest.kt` (확장)
- 기존 케이스 갱신(BLOCKER — eng-review 항목7): 현 line 378 "(d) assignee 변경 이력 아이템은 label 이 null 이다"(fromLabel/toLabel null 단언, line 405-406) + line 60 KDoc "assignee 는 label=null"을 **박제 단언으로 변경**. fake `UserLookupPort`에 시드 user UUID→표시명 맵 stub → assignee 변경 시 `to_label`=표시명 단언. line 537 자동배정 케이스도 동일 갱신.
- 신규: user 미존재(stub 맵에 없음) → label=null + row 정상(S5 graceful 회귀).
- securityLevel: `AlwaysAllowIssueSecurityDirectory`(findLevelNames default 빈 맵) → label=null graceful 확인. **박제 검증은 T3 담당**(이 테스트 범위 아님 명시).

**GREEN**: `HistoryE2EConfig` 빈 등록(IssueSecurityDirectory=AlwaysAllow, UserLookupPort=표시명 stub fake). 시드/단언 갱신.

**REFACTOR**: 시드 헬퍼 정리, fake stub 헬퍼.

**검증**: `./gradlew :backend:issue-tracking:test --tests '*IssueChangeHistoryE2EIntegrationTest'`

### Task 6. ADR 갱신 + 전수 동기화 (문서)

**메타**.
- agent: `backend-engineer`
- files: [`docs/adr/2026-06-11-issue-change-history-model.md`, `docs/plan/product/issue-tracking.md`]
- depends-on: []

**RED**: (문서 — 테스트 없음. verify-master-plan.sh가 게이트)

**GREEN**:
- ADR에 "보강(2026-06-11): cross-BC 라벨 박제" 단락 — §결정1 "cross-BC 호출 회피"를 assignee/securityLevel 표시명 read에 한해 deviation, graceful degrade 유지 명시.
- `docs/plan/product/issue-tracking.md` §5.1.1 D2 노트 "assignee/securityLevel은 cross-BC라 label=null, 조회는 FR-HS-02" → "표시명 기록 시점 박제(PR #120 보강)"로 수정.

**REFACTOR**: Obsidian/메모리 미러는 bts-merge에서.

**검증**: `bash scripts/verify-master-plan.sh`

## Plan 메타

- task 수: 6
- 예상 wave: 3 (wave1: T1·T6 / wave2: T2·T3·T4 / wave3: T5[depends 4]). shared-kernel(T1) 변경이 전 모듈 재컴파일 유발 → 자연 직렬(메모리 bts-plan-wave-gradle-module-compile). T5는 BC 격리 위해 실 adapter 대신 fake port → depends [4]만.
- 예상 시간: 직렬 약 18분, wave 적용 약 9분.
- TDD 강제: yes (T1~T5). T6은 문서.
- 다중 BC: shared-kernel(T1) → identity-access(T2·T3) + issue-tracking(T4·T5). BC 격리 유지(issue-tracking은 shared-kernel 포트만 의존, ArchUnit 검증).
- 추가 검증: ktlint, detekt(헬퍼 추출 전제), ArchUnit BC 격리, 풀 테스트 0 fail.

## 리뷰 결과

### 독립 eng-review (backend-engineer agent, 2026-06-11, 실측 기반)

autoplan(4종) 대신 eng 집중 독립 리뷰(메모리 bts-review-plan-autoplan-overkill). 실제 코드 grep/read로 가정 검증.

- ✅ 항목1 cross-BC 기록경로 도입: OK. ADR 회피는 "수단 부재"였지 원자성 강제 아님. readOnly 포트 REQUIRED 참여로 경계 오염 없음. batch로 N+1 방지.
- ⚠️→✅ 항목2 BC 격리 ArchUnit: CONCERN. `IssueBcArchTest` 룰 1b에 `identity.issuesecurity..` 누락 → **T4 REFACTOR에 추가 반영**.
- ✅ 항목3 default 메서드 fail-open: OK. label은 부가정보, 접근제어는 별도 메서드. 빈 맵=보안 무영향 기능 degrade.
- 🛑→✅ 항목4 securityLevel 프로파일/E2E 부팅: **BLOCKER**. T5가 @ContextConfiguration 명시 빈 등록이라 T4 생성자 변경 시 IssueSecurityDirectory 빈 미등록→부팅 실패 → **T5에 HistoryE2EConfig 빈(AlwaysAllow + UserLookupPort fake) 명시 등록 반영**.
- ✅ 항목5 graceful degrade: OK. from/to_value(UUID) 항상 기록, label=null은 "당시명 미확보" 명시.
- ⚠️→✅ 항목6 wave/의존성: CONCERN. T5 실 adapter는 identity-access testImplementation 필요(BC 회색지대) → **T5를 fake port stub로 전환, depends [4], BC 격리 유지 반영**.
- 🛑→✅ 항목7 기존 null 단언 테스트: **BLOCKER**. E2E (d) line 378·LabelResolverTest null 케이스가 T4 후 RED → **T4/T5 RED에 구체적 갱신(삭제→박제 단언) 반영**.

**종합**: BLOCKER 2 + CONCERN 2 모두 plan 보강으로 해소. 구현 진행 가능.

### 보강 반영 요약
- T4: files에 `IssueBcArchTest.kt` 추가, REFACTOR에 ArchUnit `issuesecurity..` 금지 추가(vacuous 방지 위반 확인), RED에 LabelResolverTest null 케이스 삭제→박제 대체.
- T5: depends [2,3,4]→[4], 실 adapter→fake port stub(BC 격리), HistoryE2EConfig 빈 명시 등록(부팅 해소), E2E (d) null 케이스→박제 단언 갱신, securityLevel은 graceful만(박제는 T3).

## 전수 동기화 대상 (머지 전 verify-master-plan.sh)
- ADR `docs/adr/2026-06-11-issue-change-history-model.md` — cross-BC 박제 보강 단락
- `docs/plan/product/issue-tracking.md` §5.1.1 D2 노트 — "assignee/securityLevel은 cross-BC라 label=null" → 박제로 수정
- (FR 카운트 변동 없음 — FR-HS-01 보강이지 신규 FR 아님)
- Obsidian history/learnings 미러, 자동 메모리
