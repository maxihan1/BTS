# FR-NT-03 — 수신자 정책 (R/A/W/Lead/역할) RecipientResolver

> slug: fr-nt-03-recipient-resolver
> type: backend
> agent: backend-engineer
> primary_bc: notification
> 생성: 2026-06-19

## Brief

FR-NT-03 — 수신자 정책 (R/A/W/Lead/역할) RecipientResolver 구현.
notification-dashboard BC. FR-NT-01 notification_policies.recipient_role을
실제 사용자 집합으로 해석.

- product: docs/plan/product/notification-dashboard.md §2.3
- SDD: docs/sdd/02-requirements.md FR-NT-03 (수신자 정책: R/A/W/Lead/역할)
- 선행: FR-NT-01 (notification_policies, recipient_role 필드 존재, PR #118)
- D1~D7 미완 (전부 [ ])

## 도메인 정리

- **BC**: notification
- **핵심 통찰**: FR-NT-03은 신규 컴포넌트가 아니라 기존 `EventRecipientResolver`의 `else → skip (FR-NT-03 대상)` 분기를 실제 cross-BC 조회로 채우는 작업. enum `RecipientRole`(9개)·정책 평가 엔진·NotificationWorker·dedup 인프라는 FR-NT-01/02에서 완성됨.
- **이미 구현(FR-NT-02)**: MENTIONED, REPORTER, ASSIGNEE
- **이번에 구현(Maxi 확정 2026-06-19, 데이터 있는 5개 전부)**:
  | RecipientRole | 데이터 출처 | 원본 BC | 조회 키 |
  |---|---|---|---|
  | WATCHER | `issue_watchers` (FR-WT-01) | issue-tracking | issueId |
  | COMPONENT_LEAD | `issue_components` 조인 → `components.lead_user_id` | issue-tracking | issueId |
  | PREVIOUS_ASSIGNEE | `issue_change_item` (field='assignee', from_value) (FR-HS) | issue-tracking | issueId |
  | PROJECT_MEMBER | `project_memberships` 전체 | identity-access | projectId/Key |
  | PROJECT_ADMIN | `project_memberships` WHERE role='PROJECT_ADMIN' | identity-access | projectId/Key |
- **skip 유지**: RULE_OWNER — automation BC 미존재(Phase 1 범위 밖). 정책 시드(V401)엔 행이 있으나 동작 안 함(시드 부트스트랩 ADR 결정 3과 일관 — "발행원 없는 정책은 무해하게 존재"). 향후 FR-AT 시리즈에서 결선.
- **PROJECT_LEAD enum 미추가(Maxi 확정)**: product 'Lead'='COMPONENT_LEAD'로 해석(SDD §9.1 issue.created 기본 수신자와 일치). `projects.lead_user_id` 데이터는 있으나 enum/시드/init_codegen 변경 회피 → drift 위험 0.
- **새 용어**: 없음 (모든 역할 개념 이미 glossary/enum에 존재)
- **cross-BC 포트 설계 방향(spec에서 시그니처 확정)**: ArchUnit BC 격리상 notification은 issue-tracking/identity-access 내부 패키지 직접 import 금지. shared-kernel 포트 인터페이스 정의 + 각 원본 BC가 adapter 구현(선례 `IssueRecipientLookupPort` 답습). nullable/fail-open 함정 주의([[crossbc-resolver-nullable-fail-open]]) — 기본값 non-null + 빈 수신자(알림 미발송) fail-safe.
- **기존 결정 충돌**: 없음. FR-NT-01 ADR(결정 2/결과) + FR-NT-02 ADR(결정 3/4)이 RecipientResolver=FR-NT-03 경계를 명시적으로 예약함.
- **관련 ADR**: FR-NT-03 cross-BC 포트 설계 ADR은 spec 단계에서 포트 분해(역할별 vs 통합) 확정 후 작성 검토. 현재 IssueRecipientLookupPort 선례 답습이라 신규 결정 비중 낮음.

## 스펙

전체 스펙. [docs/specs/2026-06-19-fr-nt-03-recipient-resolver.md](../specs/2026-06-19-fr-nt-03-recipient-resolver.md)

핵심 요약.
- `EventRecipientResolver.resolveRole`의 `else→skip`을 5개 역할(WATCHER/COMPONENT_LEAD/PREVIOUS_ASSIGNEE/PROJECT_MEMBER/PROJECT_ADMIN)로 확장, RULE_OWNER만 skip 유지.
- cross-BC 포트 3종: ① IssueRecipientLookupPort 확장(IssueRecipients에 watcherIds/componentLeadIds/previousAssigneeId 추가, issue-tracking adapter) ② ProjectRecipientLookupPort 신규(projectKey→멤버/관리자, identity-access adapter, ProjectDirectory.resolveKeyToId 재사용) ③ IssueVisibilityPort 신규(보안수준 필터, 발송 전 배치).
- 해석 → actor 제외 → dedup → **visibility 필터(기존 MENTIONED/REPORTER/ASSIGNEE 포함, 동작 강화)**. 마이그레이션 0.
- Maxi 확정: 보안수준=발송 전 visibility 필터, PREVIOUS_ASSIGNEE=직전 1명, PROJECT_LEAD enum 미추가.

## Brainstorming Check

✅ 통과 (1회 보강). 5개 gap 발견 후 전부 스펙 반영.
- G1 PREVIOUS_ASSIGNEE 근사(이벤트~처리 시점 재변경) 한계 수용
- G2 visibility 순서/범위(dedup 후, 기존 역할 포함) → FR7
- G3 visibility 런타임 장애 fail-closed(이벤트 보류)
- G4 조회 시점 eventual 일관성 명시
- G5 광역 발송 부하(배치 필수, 그룹화 별도)

## Plan

> 모든 경로는 repo 루트 기준. 검증 명령은 worktree 내부 `cd backend && ./gradlew ...`.
> 모듈 약칭: SK=shared-kernel, NT=notification, IT=issue-tracking, IA=identity-access.

### Task 1. shared-kernel 포트 정의 + IssueRecipients 확장 (v2)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/shared-kernel/src/main/kotlin/com/bts/shared/issue/IssueRecipientLookupPort.kt`, `backend/modules/shared-kernel/src/main/kotlin/com/bts/shared/issue/ProjectRecipientLookupPort.kt`, `backend/modules/shared-kernel/src/main/kotlin/com/bts/shared/permission/IssueVisibilityPort.kt`, `backend/modules/shared-kernel/src/test/kotlin/com/bts/shared/issue/RecipientPortsDefaultTest.kt`]
- depends-on: []

**RED**: `RecipientPortsDefaultTest` — (a) `IssueRecipients`가 watcherIds/componentLeadIds/previousAssigneeId 필드 보유 + `empty()`가 빈/null, (b) `ProjectRecipientLookupPort` default가 `ProjectRecipients.empty()`. 실패: 클래스/필드 없음.

**GREEN**:
- `IssueRecipients`에 `watcherIds: List<UUID> = emptyList()`, `componentLeadIds: List<UUID> = emptyList()`, `previousAssigneeId: UUID? = null` 추가 + `empty()` 갱신. `findRecipients` default 유지(기존 fake 보호, [[interface-extension-default-method]]).
- `ProjectRecipientLookupPort { fun findProjectRecipients(projectKey: String): ProjectRecipients = ProjectRecipients.empty() }` + `data class ProjectRecipients(memberIds, adminIds)` + `empty()`. (수신자 조회 fail-safe=빈=누락 안전)
- **`IssueVisibilityPort`(`com.bts.shared.permission`, IssueSecurityDirectory 옆)**: `fun filterVisibleUserIds(issueKey: String, candidateUserIds: Set<UUID>): Set<UUID>` — **추상 메서드(default 없음)**. allow-all default 금지(B-SEC-3 fail-open 차단). 구현 부재 시 notification 컨텍스트 부팅 실패가 의도된 안전(non-null 필수 주입).

**REFACTOR**: KDoc — IssueVisibilityPort는 "이슈 VIEW 가시성(매트릭스+보안등급) 판정의 단일 source of truth를 재사용한다, 새 보안 경로 금지, default 금지(fail-closed)". SharedKernelBoundaryArchTest 통과(원시 타입만).

**검증**: `cd backend && ./gradlew :shared-kernel:test --tests "*RecipientPortsDefaultTest*"`

### Task 2. EventRecipientResolver — 5개 역할 해석 분기

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/notification/src/main/kotlin/com/bts/notification/recipient/EventRecipientResolver.kt`, `backend/modules/notification/src/test/kotlin/com/bts/notification/recipient/EventRecipientResolverTest.kt`]
- depends-on: [1]

**RED**: fake 포트로 5개 역할 단위 테스트 — WATCHER(다중), COMPONENT_LEAD(다중·lead 없음 제외), PREVIOUS_ASSIGNEE(직전 1명·이력 없음 null), PROJECT_MEMBER(전체), PROJECT_ADMIN(admin만), RULE_OWNER skip(로그). 실패: else→skip이라 빈 목록.
- **C4 — 기존 테스트 갱신 필수**: 현 `EventRecipientResolverTest`(생성자 1-인자 mock, line ~26)는 `ProjectRecipientLookupPort` 추가로 **컴파일 깨짐** → 생성자 갱신. 기존 line 217-234의 "WATCHER/PROJECT_ADMIN은 빈 목록으로 skip" 단언은 **이제 의미 상실** → **WATCHER/PROJECT_ADMIN은 해석 단언으로 교체, RULE_OWNER skip 단언만 유지**. 가짜 그린 방지.

**GREEN**:
- `EventRecipientResolver` 생성자에 `ProjectRecipientLookupPort` 추가 주입.
- `resolveRole` when에 5개 분기 추가(WATCHER/COMPONENT_LEAD/PREVIOUS_ASSIGNEE → `recipients`(IssueRecipients)에서, PROJECT_MEMBER/PROJECT_ADMIN → project recipients에서). RULE_OWNER는 명시 skip+debug 로그 유지.
- `lazyRecipientsLookup` 확장: issue 기반 역할(REPORTER/ASSIGNEE/WATCHER/COMPONENT_LEAD/PREVIOUS_ASSIGNEE) 중 하나라도+issueKey 있으면 IssueRecipients 1회 조회. project 기반 역할(PROJECT_MEMBER/PROJECT_ADMIN) 있으면+projectKey 있으면 ProjectRecipients 1회 조회(별도 lazy).

**REFACTOR**: 역할별 private `resolveXxx` 메서드 분리, N+1 방지 주석.

**검증**: `cd backend && ./gradlew :notification:test --tests "*EventRecipientResolverTest*"`

### Task 3. EventRecipientResolver — visibility 필터 통합 (보안)

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/notification/src/main/kotlin/com/bts/notification/recipient/EventRecipientResolver.kt`, `backend/modules/notification/src/test/kotlin/com/bts/notification/recipient/EventRecipientResolverTest.kt`]
- depends-on: [1, 2]   # 같은 파일, Task 2 다음 직렬

**RED**: visibility fake 포트로 — (a) 권한 없는 user 제외(보안수준 제한 이슈), (b) **기존 MENTIONED/REPORTER/ASSIGNEE도 필터 통과**(FR7 동작 강화), (c) **C-S2 — 보안수준 제한 이슈 + 권한 없는 멘션 대상 → 제외**(멘션도 누출 차단 우선, 명시 케이스), (d) issueKey=null 이벤트는 필터 비대상, (e) dedup/actor 제외 후 visibility 순서, (f) **C3/B-SEC-3 — visibility 포트가 예외를 던지면 `resolve()`가 예외 전파**(빈 목록으로 삼키지 않음 = fail-closed). 실패: 필터 미적용.

**GREEN**:
- 생성자에 `IssueVisibilityPort` **non-null 필수 주입**(빈 부재 = 부팅 실패, allow-all fallback/`?:` 금지 — B-SEC-3).
- `resolve()` 말미: actor 제외 + dedup **이후** distinct userId 추출 → `issueKey != null`이면 `filterVisibleUserIds(issueKey, userIds)` 1회 호출 → 통과 userId의 (userId, channel)만 유지. 포트 예외는 잡지 않고 전파(worker가 메시지 보류·재전달).

**REFACTOR**: 필터 단계 private 메서드 + KDoc(G3 — 런타임 장애 시 포트 예외 전파로 이벤트 보류, allow-all 삼킴 금지).

**검증**: `cd backend && ./gradlew :notification:test --tests "*EventRecipientResolverTest*"`

### Task 4. issue-tracking adapter — IssueRecipientLookupAdapter 확장 + repo 메서드 신설 (v2)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/notification/IssueRecipientLookupAdapter.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/component/repository/ComponentRepository.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/history/IssueChangeHistoryRepository.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/history/JdbcIssueChangeHistoryRepository.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/notification/IssueRecipientLookupAdapterIntegrationTest.kt`]
- depends-on: [1]
- **주의 B-ENG-2**: 아래 JdbcIssueChangeHistoryRepository 실제 구현 클래스명은 impl 시 grep 확인(파일명이 다를 수 있음 — `find ... -name "*IssueChangeHistory*"`).

**RED**: Testcontainers 통합 — 시드 이슈(워처 N, 컴포넌트 2개 중 lead 1개, assignee 변경 이력)에 `findRecipients(issueKey)` 호출 시 watcherIds/componentLeadIds/previousAssigneeId 채워짐. 미할당→할당 from_value, 이력 0 → null. 실패: 필드 빈.
- repo 메서드 신설 RED: `IssueChangeHistoryRepository.findLatestAssigneeChangeFromValue(issueId): String?`가 최근 field='assignee' 변경의 from_value를 반환(없으면 null).

**GREEN**:
- **B-ENG-2 — `IssueChangeHistoryRepository`에 `findLatestAssigneeChangeFromValue(issueId): String?` 신설**(append-only 인터페이스에 읽기 메서드 추가, 불변식 무해) + Jdbc 구현(field='assignee' AND group.issue_id=? ORDER BY id DESC LIMIT 1, from_value). 전 이력 메모리 로드 금지(단일 쿼리).
- `ComponentRepository`에 `findLeadUserIdsByIssue(issueId): List<UUID>` 추가(issue_components ⋈ components, lead_user_id NOT NULL, deleted_at IS NULL, DISTINCT).
- `IssueRecipientLookupAdapter`에 `IssueWatcherRepository`·`ComponentRepository`·`IssueChangeHistoryRepository` 주입. 동일 issueId로 watcher 목록·component lead·직전 assignee 채움. 이슈 미존재 → empty.

**REFACTOR**: from_value 파싱 헬퍼(UUID 텍스트/'NONE'·null 방어, 파싱 실패→null fail-safe), N+1 없는 단일 조회 묶음.

**검증**: `cd backend && ./gradlew :issue-tracking:test --tests "*IssueRecipientLookupAdapterIntegrationTest*"`

### Task 5. identity-access adapter — ProjectRecipientLookupAdapter

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/project/ProjectRecipientLookupAdapter.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/project/ProjectRecipientLookupAdapterIntegrationTest.kt`]
- depends-on: [1]

**RED**: Testcontainers — projectKey로 멤버 N명/관리자 M명 조회. 미해결 projectKey(삭제/없음) → empty. 실패: adapter 없음.

**GREEN**: `ProjectRecipientLookupAdapter(@Component, IssueRecipient... 아님)` — `ProjectDirectory.resolveKeyToId(projectKey)` → null이면 empty, 아니면 `ProjectMembershipRepository.listByProject(projectId)` → memberIds 전체 + adminIds(role==PROJECT_ADMIN).

**REFACTOR**: KDoc + fail-safe(미해결 키 empty).

**검증**: `cd backend && ./gradlew :identity-access:test --tests "*ProjectRecipientLookupAdapterIntegrationTest*"`

### Task 6. IssueVisibilityPort 구현 — identity-access 배치 가시성 필터 (보안, 재설계 v2)

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/notification/IssueVisibilityAdapter.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/notification/IssueVisibilityAdapterIntegrationTest.kt`]
- depends-on: [1]
- **BC = identity-access**(issue-tracking 아님). 단건 이슈 VIEW 가시성의 source of truth가 identity-access(`IdentityAccessIssuePermissionResolver` + `IssueSecurityDecider` + `IssueSecurityLookup`)이기 때문. 패키지 경로는 impl 시 기존 issuesecurity/permission 패키지 관례 확인 후 확정.

**핵심(B-SEC-1·2·3 해소)**: `accessibleLevels` 재조립을 **폐기**. notification에 노출할 `IssueVisibilityPort.filterVisibleUserIds(issueKey, candidateIds)`를 identity-access가 구현하되, **기존 검증된 단건 VIEW 판정을 재사용**한다. 새 보안 판정 경로를 만들지 않는다(drift=누출).

**RED**: Testcontainers — (a) 보안수준 미설정 + VIEW_ISSUE 권한 없는 멤버 → **제외**(B-SEC-1: 매트릭스 게이트 작동), (b) 보안수준 미설정 + VIEW 권한 있는 멤버 → 통과, (c) 보안수준 설정 이슈 → 등급 멤버(static/group/role)만 통과, (d) reporter/assignee 조건 등급은 **이 이슈의** reporter/assignee일 때만(IssueSecurityDecider 정확성), (e) 고아 등급(멤버0) → 보수적 차단, (f) **C-S5 — 소프트삭제 이슈 → 빈**, (g) 이슈 미존재 → 빈. 실패: adapter 없음.

**GREEN**:
- `IssueVisibilityAdapter(@Component)` — issueKey로 이슈 컨텍스트(projectKey, reporterId, assigneeId, securityLevelId) 조회는 **기존 `IssueSecurityLookup`(deleted_at 필터 검증됨) 재사용**. 후보 userId 집합에 대해 **VIEW_ISSUE 매트릭스 권한 + 보안등급 게이트(`IssueSecurityDecider`)를 결합 적용**(단건 VIEW 판정과 동일 규칙). 관리자 우회 없음(ADR §결정5).
- N+1 최소화: 보안등급 미설정이어도 VIEW 매트릭스는 항상 적용. 후보별 판정이 불가피하면 role/group 조회를 배치/캐시로 묶어 메시지당 쿼리 폭증 회피(C2). 가능하면 set-based 1회.

**REFACTOR**: 판정을 기존 resolver/decider 위임으로 캡슐화(복제 금지). **prod 부팅 가드 표현 정정**: BTS는 cross-BC 배포 조립 모듈 부재([[no-cross-bc-deployment-assembly]])라 "prod 부팅 가드"는 실증 불가 → IssueVisibilityPort는 **non-null 필수 주입**으로 빈 부재 시 부팅 실패가 안전망, test-assembled가 현 표준임을 KDoc에 명시.

**검증**: `cd backend && ./gradlew :identity-access:test --tests "*IssueVisibilityAdapterIntegrationTest*"`

### Task 7. end-to-end 통합 + ArchUnit BC 격리 확인

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/notification/src/test/kotlin/com/bts/notification/worker/RecipientResolutionIntegrationTest.kt`]
- depends-on: [2, 3, 4, 5, 6]

**RED**: Testcontainers full-context — 이벤트(transition, 워처+컴포넌트 lead+멤버 시드, **보안수준 제한 이슈 + VIEW 권한 없는 멤버 포함**) 발행 → NotificationWorker 소비 → notifications 테이블에 **visibility 통과 수신자만** 기록(권한 없는 멤버 미기록=누출 차단 단언). actor 제외·dedup 확인. 실패: 미해석/누출.

**GREEN**:
- **C5/B-SEC-3 — 실 adapter 강제**: test-assembled 컨텍스트에 `IssueVisibilityAdapter`·`ProjectRecipientLookupAdapter`·`IssueRecipientLookupAdapter` **실 구현 빈 등록**. AlwaysAllow류 stub 등록 금지(누출 못 잡는 가짜 그린). notification 컨텍스트가 포트 빈을 못 찾으면 부팅 실패하도록 non-null 주입 유지.
- 신규 @Component(T5·T6)가 기존 전체-컨텍스트 통합테스트 부팅을 깨면([[fr-nt-02-email-channel-done]] 선례) TestcontainersConfig에 실 빈 보강(stub 아님).

**REFACTOR**: ArchUnit BC 격리(notification → shared-kernel만) 룰 재확인, detekt/ktlint baseline 동결.

**검증**: `cd backend && ./gradlew :notification:test --tests "*RecipientResolutionIntegrationTest*"` + `:notification:test --tests "*ArchTest*"`

## Plan 메타 (v2 — 리뷰 반영)

- task 수: 7
- 모듈 분포 (v2): SK(T1) · NT(T2,T3,T7) · IT(T4) · **IA(T5,T6)** — visibility(T6)가 issue-tracking→identity-access로 이동.
- 예상 wave (v2): wave1=T1 / wave2=T2(NT),T4(IT),T5(IA) / wave3=T3(NT,T2후),T6(IA,T5와 같은 모듈 직렬) / wave4=T7. 약 4 wave. **T5·T6 둘 다 IA 모듈이라 같은 wave 불가(test 컴파일 공유, [[bts-plan-wave-gradle-module-compile]])** → bts-impl이 직렬화. C6 해소: T6 depends-on [1]이나 IA 모듈 직렬화로 T5와 같은 wave에 안 떨어짐.
- TDD 강제: yes (test 커밋 선행 자동 검증)
- 보안: T3·T6 = security-engineer. **visibility 판정 = 기존 IssuePermissionResolver(VIEW 매트릭스)+IssueSecurityDecider+IssueSecurityLookup 재사용**(accessibleLevels 재조립 폐기, source of truth 단일화). IssueVisibilityPort는 non-null 필수 주입(fail-open 차단).
- 마이그레이션: 0 (전부 기존 테이블 읽기). enum/시드/스키마 변경 0 → FR 카운트·정본 동기화 불요.
- D6/D7(프론트/E2E): 백엔드(D1~D5) 완결 우선, 별도 판단(스펙 §D6/D7 분리 참조).
- **리뷰 BLOCKER 4건 전부 plan 반영**(B-ENG-2 repo메서드 / B-SEC-1·2 visibility 재설계 / B-SEC-3 non-null 주입). 게이트 1에서 Maxi 검토.

## 리뷰 결과

### plan-eng-review (2026-06-19, 독립 에이전트)
- **BLOCKER B-ENG-1**: visibility "배치 1회"는 `IssueSecurityDirectory.accessibleLevels`(actor당 호출)로 불가능 → N+1(1000명×7쿼리). → B-SEC-2 배치 메서드 신설로 통합 해소.
- **BLOCKER B-ENG-2**: `IssueChangeHistoryRepository`에 "직전 assignee 조회" 메서드 부재(record/findByIssue/findByIssuePaged/countByIssue만). → 신설 + plan files 반영 필요.
- CONCERN: C1(visibility BC 위치 오기재), C2(워커 무트랜잭션×per-user), C3(fail-closed 테스트 누락), C4(기존 EventRecipientResolverTest 생성자/skip 단언 깨짐), C5(full-context 부팅 시 신규 @Component 포트 stub 필요), C6(T6 depends-on이 wave 배치와 어긋나 IT 모듈 race).
- NIT: N2(Task5 GREEN 표기 깨짐), N3(from_value 'NONE' 가정), N4(다대다 표현).

### plan-security-review (2026-06-19, 독립 에이전트) — **재설계 필수**
- **BLOCKER B-SEC-1 (누출)**: 단건 이슈 가시성 source of truth = `IssuePermissionResolver.hasPermission(actorId, VIEW, IssueScope.Issue(key))` (VIEW_ISSUE 매트릭스 + 보안등급 게이트 **결합**, `IdentityAccessIssuePermissionResolver.kt:72-120`). plan T6의 `accessibleLevels` 단독 재사용은 VIEW 매트릭스 게이트를 빠뜨려 **VIEW 권한 없는 멤버에게 누출**(특히 보안수준 미설정 이슈 + 광역 역할).
- **BLOCKER B-SEC-2 (drift)**: reporter/assignee 조건 등급 직접 재조립은 `IssueSecurityDecider`(검증된 단건 판정 순수함수)와 별도 보안 경로 생성 → drift = 누출. → **identity-access에 `filterVisibleUserIds(issueKey, candidateIds): Set<UUID>` 배치 메서드 신설**(기존 VIEW 판정 + IssueSecurityDecider 재사용, source of truth 단일화 + N+1 회피). 신규 보안 판정 경로 금지.
- **BLOCKER B-SEC-3 (fail-open)**: notification은 issue-tracking/identity-access 미의존, cross-BC 결선은 test-assembled에서만([[no-cross-bc-deployment-assembly]]). "prod 부팅 가드"는 실재하지 않음. IssueVisibilityPort default=allow-all + 빈 부재 = silent 누출([[crossbc-resolver-nullable-fail-open]]). → **non-null 필수 주입**, allow-all default 제거(또는 fail-closed), T7은 실 adapter 강제(stub=가짜그린).
- CONCERN: C-S2(멘션 전용 누출 테스트 명시), C-S5(소프트삭제 이슈→제외, 기존 `IssueSecurityLookup` deleted_at 필터 재사용).
- 누락 없음 확인: 관리자 우회(ADR §결정5대로 우회 없음이 정답), 이메일 채널(userId 단위 필터로 자동 제외), actor/dedup 순서(안전).

### 해소 — plan 수정 (아래 ## Plan v2 반영)
B-SEC-2 권장(identity-access 배치 visibility 메서드)이 B-ENG-1/B-SEC-1/B-SEC-2/B-SEC-3를 통합 해소. visibility 아키텍처를 **`accessibleLevels` 재조립 → identity-access `IssueVisibilityPort` 구현(IssuePermissionResolver VIEW 판정 + IssueSecurityDecider 배치 재사용)**으로 재설계. BC도 issue-tracking → identity-access로 이동. **이 재설계는 게이트 1에서 Maxi 검토 핵심 항목.**
