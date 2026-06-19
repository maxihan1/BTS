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

### Task 1. shared-kernel 포트 3종 정의 + IssueRecipients 확장

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/shared-kernel/src/main/kotlin/com/bts/shared/issue/IssueRecipientLookupPort.kt`, `backend/modules/shared-kernel/src/main/kotlin/com/bts/shared/issue/ProjectRecipientLookupPort.kt`, `backend/modules/shared-kernel/src/main/kotlin/com/bts/shared/issue/IssueVisibilityPort.kt`, `backend/modules/shared-kernel/src/test/kotlin/com/bts/shared/issue/RecipientPortsDefaultTest.kt`]
- depends-on: []

**RED**: `RecipientPortsDefaultTest` — (a) `IssueRecipients`가 watcherIds/componentLeadIds/previousAssigneeId 필드 보유 + `empty()`가 빈/ null, (b) `ProjectRecipientLookupPort` default가 `ProjectRecipients.empty()`, (c) `IssueVisibilityPort` default `filterVisible`가 입력 집합 그대로 반환(allow-all). 실패: 클래스/필드 없음.

**GREEN**:
- `IssueRecipients`에 `watcherIds: List<UUID> = emptyList()`, `componentLeadIds: List<UUID> = emptyList()`, `previousAssigneeId: UUID? = null` 추가 + `empty()` 갱신. `findRecipients` default 유지(기존 fake 보호, [[interface-extension-default-method]]).
- `ProjectRecipientLookupPort { fun findProjectRecipients(projectKey: String): ProjectRecipients = ProjectRecipients.empty() }` + `data class ProjectRecipients(memberIds, adminIds)` + `empty()`.
- `IssueVisibilityPort { fun filterVisible(issueKey: String, candidateUserIds: Set<UUID>): Set<UUID> = candidateUserIds }`.

**REFACTOR**: KDoc(fail-safe 방향 명시 — recipient 조회 빈=누락 안전 / visibility default allow-all=비-prod 한정, prod adapter 필수). SharedKernelBoundaryArchTest 통과(원시 타입만).

**검증**: `cd backend && ./gradlew :shared-kernel:test --tests "*RecipientPortsDefaultTest*"`

### Task 2. EventRecipientResolver — 5개 역할 해석 분기

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/notification/src/main/kotlin/com/bts/notification/recipient/EventRecipientResolver.kt`, `backend/modules/notification/src/test/kotlin/com/bts/notification/recipient/EventRecipientResolverTest.kt`]
- depends-on: [1]

**RED**: fake 포트로 5개 역할 단위 테스트 — WATCHER(다중), COMPONENT_LEAD(다중·lead 없음 제외), PREVIOUS_ASSIGNEE(직전 1명·이력 없음 null), PROJECT_MEMBER(전체), PROJECT_ADMIN(admin만), RULE_OWNER skip(로그). 실패: else→skip이라 빈 목록.

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

**RED**: visibility fake 포트로 — (a) 권한 없는 user 제외(보안수준 제한 이슈), (b) **기존 MENTIONED/REPORTER/ASSIGNEE도 필터 통과**(FR7 동작 강화), (c) issueKey=null 이벤트는 필터 비대상, (d) dedup/actor 제외 후 visibility 순서. 실패: 필터 미적용.

**GREEN**:
- 생성자에 `IssueVisibilityPort` 주입.
- `resolve()` 말미: actor 제외 + dedup **이후** distinct userId 추출 → `issueKey != null`이면 `filterVisible(issueKey, userIds)` 1회 호출 → 통과 userId의 (userId, channel)만 유지.

**REFACTOR**: 필터 단계 private 메서드 + KDoc(G3 — 런타임 장애 시 포트가 예외를 던져 이벤트 보류, allow-all 삼킴 금지).

**검증**: `cd backend && ./gradlew :notification:test --tests "*EventRecipientResolverTest*"`

### Task 4. issue-tracking adapter — IssueRecipientLookupAdapter 확장 + ComponentRepository 메서드

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/notification/IssueRecipientLookupAdapter.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/component/repository/ComponentRepository.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/notification/IssueRecipientLookupAdapterIntegrationTest.kt`]
- depends-on: [1]

**RED**: Testcontainers 통합 — 시드 이슈(워처 N, 컴포넌트 2개 중 lead 1개, assignee 변경 이력)에 `findRecipients(issueKey)` 호출 시 watcherIds/componentLeadIds/previousAssigneeId 채워짐. 미할당→할당 from_value, 이력 0 → null. 실패: 필드 빈.

**GREEN**:
- `ComponentRepository`에 `findLeadUserIdsByIssue(issueId): List<UUID>` 추가(issue_components ⋈ components, lead_user_id NOT NULL, deleted_at IS NULL, DISTINCT).
- `IssueRecipientLookupAdapter`에 `IssueWatcherRepository`·`ComponentRepository`·`IssueChangeHistoryRepository` 주입. 동일 issueId로 watcher 목록·component lead·직전 assignee(최근 field='assignee' 변경 from_value 파싱) 채움. 이슈 미존재 → empty.

**REFACTOR**: from_value 파싱 헬퍼(UUID 텍스트/'NONE' 리터럴 방어), N+1 없는 단일 조회 묶음.

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

### Task 6. IssueVisibilityPort adapter — 보안수준 가시성 필터 (보안)

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/notification/IssueVisibilityAdapter.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/notification/IssueVisibilityAdapterIntegrationTest.kt`]
- depends-on: [1]

**RED**: Testcontainers — (a) 보안수준 미설정 이슈 → 전체 통과(unrestricted), (b) 보안수준 설정 이슈 → 접근 가능 등급 멤버만 통과·나머지 제외, (c) reporter/assignee 조건 등급은 해당 역할자만, (d) 이슈 미존재 → 빈. 실패: adapter 없음.

**GREEN**:
- `IssueVisibilityAdapter(@Component)` — `IssueRepository`(이슈의 security_level_id·reporter·assignee 조회) + 기존 `IssueSecurityDirectory`(accessibleLevels) 재사용. 이슈 level_id null → 전체 통과. level 있으면 각 candidate userId의 `accessibleLevels(userId, projectKey)`로 staticLevelIds∪(reporter면 reporterLevelIds)∪(assignee면 assigneeLevelIds)에 이슈 level_id 포함 여부 판정.
- prod 부팅 가드 검토(IssueSecurityDirectory는 !prod AlwaysAllow stub 존재 — 일관 동작).

**REFACTOR**: 판정 로직 캡슐화 + 빠른 경로(level_id null/ unrestricted) 우선. **security-engineer는 IssuePermissionResolver/IssueSecurityDirectory 중 정확한 재사용 대상을 impl에서 확정**(가시성=VIEW 권한과 보안등급 둘 다 관여하는지 검토).

**검증**: `cd backend && ./gradlew :issue-tracking:test --tests "*IssueVisibilityAdapterIntegrationTest*"`

### Task 7. end-to-end 통합 + ArchUnit BC 격리 확인

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/notification/src/test/kotlin/com/bts/notification/worker/RecipientResolutionIntegrationTest.kt`]
- depends-on: [2, 3, 4, 5, 6]

**RED**: Testcontainers full-context — 이벤트(transition, 워처+컴포넌트 lead+멤버 시드, 보안수준 일부 제한) 발행 → NotificationWorker 소비 → notifications 테이블에 visibility 통과 수신자만 기록. actor 제외·dedup 확인. 실패: 미해석/누출.

**GREEN**: 실 adapter 빈 등록(또는 TestcontainersConfig stub 보강). 배선만, 로직 변경 없음.

**REFACTOR**: ArchUnit BC 격리(notification → shared-kernel만) 룰 재확인, detekt/ktlint baseline 동결.

**검증**: `cd backend && ./gradlew :notification:test --tests "*RecipientResolutionIntegrationTest*"` + `:notification:test --tests "*ArchTest*"`

## Plan 메타

- task 수: 7
- 모듈 분포: SK(T1) · NT(T2,T3,T7) · IT(T4,T6) · IA(T5)
- 예상 wave: wave1=T1 / wave2=T2,T4,T5(+T6는 IT 모듈이라 T4와 직렬화 가능) / wave3=T3,T6 / wave4=T7. 약 4 wave.
- TDD 강제: yes (test 커밋 선행 자동 검증)
- 보안: T3·T6는 security-engineer (visibility 필터 = 정보 누출 차단). visibility 판정은 기존 IssueSecurityDirectory 재사용.
- 마이그레이션: 0 (전부 기존 테이블 읽기). enum/시드/스키마 변경 0 → FR 카운트·정본 동기화 불요.
- D6/D7(프론트/E2E): 백엔드(D1~D5) 완결 우선, 별도 판단(스펙 §D6/D7 분리 참조).

## 리뷰 결과 (← /bts-review-plan 채움)
