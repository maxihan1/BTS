# FR-CM-03 — 컴포넌트별 기본 담당자 자동 할당

> slug: fr-cm-03-default-assignee
> plan_slug: issue/components-default-assignee
> type: feature
> agent: backend-engineer
> 생성: 2026-06-05

## Brief

이슈 생성 또는 컴포넌트 변경 시, 할당된 컴포넌트의 리드(`leadUserId`)를 이슈의 기본
담당자로 자동 할당한다. 담당자가 미할당(null)일 때만 채우며(명시 할당 보존), 이슈가
여러 컴포넌트에 속한 경우 컴포넌트 이름 사전순 첫 번째 컴포넌트의 리드를 채택한다.

- BC: issue-tracking
- 데이터 신설 없음 — 기존 `components.lead_user_id`(FR-CM-01) + `issue_components`(FR-CM-02) 활용
- 선행 완료: FR-CM-01(PR #59/#64), FR-CM-02(PR #81)

### 확정된 도메인 결정 (2026-06-05, Maxi)

1. 이슈↔컴포넌트는 **다중 유지**(FR-CM-02 그대로). 단일 전환은 검토했으나 취소.
2. 기본 담당자 = 컴포넌트의 기존 **`leadUserId`**(리드)를 그대로 사용. 별도 필드 신설 안 함.
3. **Trigger**: 이슈 생성 시 + 컴포넌트 변경 시 둘 다 자동 배정 평가.
4. **덮어쓰기 정책**: 담당자가 미할당(null)일 때만 자동으로 채움. 명시 할당은 보존.
5. **다중 컴포넌트 우선순위**: 리드가 지정된 컴포넌트 중 **이름 사전순 첫 번째**의 리드 채택.
6. 리드가 없는(null) 컴포넌트뿐이면 자동 배정 없음(미할당 유지).

분류: classify 오판(ui/frontend-engineer) → Maxi 확정 feature/backend-engineer.

## 도메인 정리

- BC: issue-tracking (단일 BC)
- 영향 엔티티: Issue(assigneeId, componentIds — 기존), Component(leadUserId — 기존, 재사용)
- 새 용어/엔티티: 없음. "컴포넌트 리드 = 그 컴포넌트 이슈의 기본 담당자" 의미만 명확화
- 데이터 신설: 없음(기존 components.lead_user_id + issue_components 활용)
- ground-truth 검증 완료:
  - `Issue.assigneeId: ActorId?`, `assignTo()/unassign()` 존재
  - `Issue.componentIds: List<UUID>`, `assignComponents()/clearComponents()` 존재
  - `Component.leadUserId: UUID?` 존재, 리드 실재는 컴포넌트 set 시 UserLookupPort 검증됨
  - **`createIssue`는 현재 컴포넌트/담당자 미수용** → 생성 시 자동배정 위해 componentIds 입력 추가 필요(D2)
  - FR-CM-02 `validateComponents`(같은 프로젝트+활성) 재사용 가능
- 확정 결정(상세는 ADR):
  1. 기본 담당자 = 기존 `leadUserId`(필드 신설 없음)
  2. trigger = 이슈 생성 + 컴포넌트 변경 둘 다 (생성 API에 componentIds 추가)
  3. 담당자 미할당(null)일 때만 채움
  4. 다중이면 리드 보유 컴포넌트 중 이름 사전순 첫 번째
  5. 로직은 IssueApplicationService, 담당자 설정은 도메인 assignTo() 경유
- 기존 결정 충돌: 없음(FR-CM-02 다대다 유지). 단일 전환 검토했으나 기각
- 관련 ADR: [docs/adr/2026-06-05-component-default-assignee-auto-assignment.md](../adr/2026-06-05-component-default-assignee-auto-assignment.md) (생성됨)

## 스펙

전체 스펙. [docs/specs/2026-06-05-fr-cm-03-default-assignee.md](../specs/2026-06-05-fr-cm-03-default-assignee.md)

핵심 시나리오 3줄 요약.
- 컴포넌트 지정 시(생성 또는 PATCH) 담당자 미할당이면 컴포넌트 리드를 담당자로 자동 배정
- 다중 컴포넌트면 리드 보유분 중 이름 사전순 첫 번째 리드 채택, 명시 담당자는 보존
- createIssue에 componentIds 입력 추가(생성 트랜잭션에서 issue_components 링크 영속), 자동 배정은 silent

## Brainstorming Check

✅ 통과 (1회). 자동 배정 silent 처리(기존 changeAssignee 일관) + createIssue 컴포넌트 영속 주의 보강.

## Plan

> 경로 접두사 `BE/` = `backend/modules/issue-tracking/src/`, `FE/` = `apps/web/src/`.
> 모든 백엔드 task agent=backend-engineer, 프론트=frontend-engineer, E2E=qa-engineer.

### Task 1. 도메인 — 기본 담당자 해소 함수 DefaultAssigneeResolver

**메타**.
- agent: `backend-engineer`
- files: [`BE/main/kotlin/com/bts/issue/domain/DefaultAssigneeResolver.kt`, `BE/test/kotlin/com/bts/issue/domain/DefaultAssigneeResolverTest.kt`]
- depends-on: []

**RED**: `DefaultAssigneeResolverTest.kt`. 입력 = 후보 컴포넌트 목록(VO `ComponentLead(id: UUID, name: String, leadUserId: UUID?)`) + 현재 assigneeId(ActorId?). 케이스.
- 현재 assignee 있음 → 그대로 반환(S3)
- assignee null + 단일 리드 → 그 리드(S1)
- assignee null + 다중 → name 오름차순 첫 번째 리드(S4)
- name 동률 → id 오름차순 tiebreak(FR6)
- 리드 보유 컴포넌트 없음 → null 반환(S5)

**GREEN**: `DefaultAssigneeResolver.kt`. 순수 함수 `resolve(current: ActorId?, candidates: List<ComponentLead>): ActorId?` — current 있으면 반환, 없으면 `leadUserId != null` 필터 후 `sortedWith(compareBy({it.name},{it.id})).firstOrNull()?.leadUserId?.let(::ActorId)`.

**REFACTOR**: ComponentLead VO + KDoc(규칙 근거 ADR 링크).

**검증**: `./gradlew :modules:issue-tracking:test --tests '*DefaultAssigneeResolverTest'`

### Task 2. 도메인 — Issue.create가 componentIds 수용

**메타**.
- agent: `backend-engineer`
- files: [`BE/main/kotlin/com/bts/issue/domain/Issue.kt`, `BE/test/kotlin/com/bts/issue/domain/IssueTest.kt`]
- depends-on: []

**RED**: `IssueTest.kt`. `Issue.create(..., componentIds = listOf(a, a, b))` 호출 시 componentIds가 distinct 정규화돼 저장되는지(중복 제거). 기본값 호출 시 emptyList.

**GREEN**: `Issue.create` 팩토리에 `componentIds: List<UUID> = emptyList()` 파라미터 추가 → `componentIds = componentIds.filterNotNull().distinct()`(assignComponents 정규화와 동일).

**REFACTOR**: KDoc 갱신(componentIds 파라미터 설명).

**검증**: `./gradlew :modules:issue-tracking:test --tests '*IssueTest'`

### Task 3. 웹/앱 DTO — CreateIssueRequest componentIds 입력

**메타**.
- agent: `backend-engineer`
- files: [`BE/main/kotlin/com/bts/issue/adapter/inbound/rest/CreateIssueRequest.kt`, `BE/main/kotlin/com/bts/issue/application/IssueApplicationRequests.kt`, `BE/main/kotlin/com/bts/issue/adapter/inbound/rest/IssueController.kt`, `BE/test/kotlin/com/bts/issue/adapter/inbound/rest/IssueControllerTest.kt`]
- depends-on: []

**RED**: `IssueControllerTest.kt`. POST /issues 요청 본문에 `componentIds: [uuid...]` 포함 시 app command(AppCreateIssueRequest)에 그대로 전달되는지(컨트롤러 매핑). 미포함 시 빈 목록.

**GREEN**: `CreateIssueRequest`에 `componentIds: List<UUID> = emptyList()` 추가, `AppCreateIssueRequest`에 동일 필드 추가, 컨트롤러 매핑에 전달.

**REFACTOR**: KDoc + validation 어노테이션(있으면 일관).

**검증**: `./gradlew :modules:issue-tracking:test --tests '*IssueControllerTest'`

### Task 4. 서비스 — createIssue 컴포넌트 검증+링크 영속+생성시 자동배정

**메타**.
- agent: `backend-engineer`
- files: [`BE/main/kotlin/com/bts/issue/application/IssueApplicationService.kt`, `BE/test/kotlin/com/bts/issue/application/IssueCreateComponentAutoAssignIntegrationTest.kt`]
- depends-on: [1, 2, 3]

**RED**: Testcontainers 통합 `IssueCreateComponentAutoAssignIntegrationTest`.
- S1: 리드 Alice인 컴포넌트로 생성 → assignee=Alice + componentIds 영속
- S5: 리드 null 컴포넌트로 생성 → assignee null
- S4: 리드 다른 두 컴포넌트 → 이름 사전순 첫 리드
- 검증실패: 다른 프로젝트/비활성 컴포넌트 → 422 COMPONENT_NOT_FOUND

**GREEN**: `createIssue` 흐름 확장.
1. request.componentIds 정규화(distinct) + `validateComponents(componentIds, projectId)` 재사용(FR-CM-02).
2. 후보 조회: `componentRepository.findByProject(projectId)`에서 componentIds∩ + leadUserId!=null → `ComponentLead` 목록.
3. `DefaultAssigneeResolver.resolve(current=null, candidates)` → resolvedAssignee.
4. `Issue.create(..., componentIds=정규화, assigneeId=resolvedAssignee)` → `repo.insert`(assignee_id+기본필드 영속).
5. 컴포넌트 링크 영속: 신규 이슈 version으로 `repo.replaceComponents(key, issueId, componentIds, version)` 호출(생성 직후 version 사용). 빈 목록이면 skip.
- 모두 같은 트랜잭션(NFR1). 자동 배정은 silent(이벤트/히스토리 없음, NFR4).

**REFACTOR**: 후보 추출 private helper(`resolveDefaultAssignee(projectId, componentIds, current)`)로 추출(Task 5와 공유).

**검증**: `./gradlew :modules:issue-tracking:test --tests '*IssueCreateComponentAutoAssign*'`

### Task 5. 서비스 — changeComponents 자동배정

**메타**.
- agent: `backend-engineer`
- files: [`BE/main/kotlin/com/bts/issue/application/IssueApplicationService.kt`, `BE/test/kotlin/com/bts/issue/application/IssueChangeComponentsAutoAssignIntegrationTest.kt`]
- depends-on: [1, 4]

**RED**: Testcontainers 통합 `IssueChangeComponentsAutoAssignIntegrationTest`.
- S2: 미할당 이슈에 리드 보유 컴포넌트 지정 → assignee 자동 설정
- S3: 명시 담당자 있는 이슈에 컴포넌트 지정 → 담당자 보존(덮어쓰기 안 함)
- S6: 컴포넌트 전부 해제(componentIds=[]) → 기존 담당자 유지
- 낙관락 충돌 409(회귀)

**GREEN**: `changeComponents`에서 `replaceComponents` 후, 현재 assignee가 null이면 Task4의 `resolveDefaultAssignee` 호출 → resolved 있으면 `existing.assignTo(resolved)` 경유 `repo.updateAssignee`(같은 트랜잭션). assignee 있으면 skip(FR4). componentIds 빈 목록이면 후보 없음 → 변경 없음.

**REFACTOR**: 중복 제거(Task4 helper 재사용 확인) + 로그.

**검증**: `./gradlew :modules:issue-tracking:test --tests '*IssueChangeComponentsAutoAssign*'`

### Task 6. 프론트 api — createIssue componentIds

**메타**.
- agent: `frontend-engineer`
- files: [`FE/api/issues.ts`, `FE/api/issues.test.ts`]
- depends-on: []

**RED**: `issues.test.ts`. `createIssue({..., componentIds: [...]})` 호출 시 POST body에 componentIds 포함. 미지정 시 빈 배열 기본.

**GREEN**: `CreateIssueInput`에 `componentIds?: string[]` 추가, `createIssue`가 body에 `componentIds: input.componentIds ?? []` 전달.

**REFACTOR**: 주석(FR-CM-03) + 타입 정렬.

**검증**: `pnpm --filter @bts/web test issues` + `pnpm --filter @bts/web typecheck`

### Task 7. 프론트 — 이슈 생성 폼 컴포넌트 입력 + MSW

**메타**.
- agent: `frontend-engineer`
- files: [`FE/routes/issues.new.tsx`, `FE/routes/issues.new.test.tsx`, `FE/mocks/issue-handlers.ts`]
- depends-on: [6]

**RED**: `issues.new.test.tsx`. 생성 폼에 컴포넌트 선택(ComponentMultiSelect 재사용) 렌더 + 선택 후 제출 시 createIssue가 componentIds 받음. MSW createIssue 핸들러가 componentIds 반영 + 리드 기반 assigneeId 에코(stateful, MEMORY msw-mutation-stateful-refetch).

**GREEN**: `issues.new.tsx`에 ComponentMultiSelect 추가(프로젝트 컴포넌트 useComponents/options, value/onChange 로컬 상태), 제출 시 componentIds 전달. MSW 핸들러 componentIds + assignee 에코.

**REFACTOR**: i18n 키 + 접근성 라벨.

**검증**: `pnpm --filter @bts/web test issues.new` + `pnpm --filter @bts/web typecheck`

### Task 8. E2E — 컴포넌트 지정 후 담당자 자동 표시

**메타**.
- agent: `qa-engineer`
- files: [`apps/web/e2e/issue-component-default-assignee.spec.ts`]
- depends-on: [4, 5, 7]

**RED/시나리오**: 리드 보유 컴포넌트를 이슈에 지정(생성 또는 상세 PATCH) → 담당자 필드에 리드가 자동 표시. 상세 자동 갱신(invalidate-only refetch) 확인. 행/컨테이너 한정 셀렉터(strict mode 회피, MEMORY playwright-getbyrole-exact-strict-mode).

**검증**: `pnpm --filter @bts/web exec playwright test issue-component-default-assignee`

## Plan 메타

- task 수: 8 (각 TDD 사이클)
- 예상 wave: 4 — W1[T1,T2,T3,T6] → W2[T4,T7] → W3[T5] → W4[T8]
- 직렬화 요인: T4·T5 동일 파일(IssueApplicationService.kt) → wave 분리. T8 E2E는 백엔드+프론트 완료 후
- TDD 강제: yes. 추가 검증: ktlint(ktlintMainSourceSetCheck+TestSourceSetCheck)·detekt·typecheck·vitest·playwright
- 마이그레이션: 없음(leadUserId 재사용)

## 리뷰 결과 (← /bts-review-plan 채움)
