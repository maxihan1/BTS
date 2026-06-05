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

**근거 메모(C5)**: 컴포넌트명은 프로젝트 내 활성 유니크(`components` 부분 유니크 인덱스 `ux_components_project_id_name_active`). 따라서 name 동률은 활성 컴포넌트 간 발생 불가 → id tiebreak는 방어적(dead-path). 정렬 단일 진실은 이 resolver의 JVM `compareBy`(DB `findByProject`의 name asc와 별개이나 resolver가 재정렬하므로 최종 결정성 보장). KDoc에 명시.

**검증**: `./gradlew :modules:issue-tracking:test --tests '*DefaultAssigneeResolverTest'`

### Task 2. 도메인 — Issue.create가 componentIds 수용

**메타**.
- agent: `backend-engineer`
- files: [`BE/main/kotlin/com/bts/issue/domain/Issue.kt`, `BE/test/kotlin/com/bts/issue/domain/IssueTest.kt`]
- depends-on: []

**RED**: `IssueTest.kt`. `Issue.create(..., componentIds = listOf(a, a, b))` 호출 시 componentIds가 distinct 정규화돼 저장되는지(중복 제거). 기본값 호출 시 emptyList.

**GREEN**: `Issue.create` 팩토리에 `componentIds: List<UUID> = emptyList()` 파라미터 추가 → 반환
Issue 객체에 `componentIds = componentIds.filterNotNull().distinct()` **반드시 설정**(현재 create는
componentIds를 반환 객체에 누락 — 리뷰 확인. assignComponents 정규화와 동일 규칙).

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
- files: [`BE/main/kotlin/com/bts/issue/application/IssueApplicationService.kt`, `BE/main/kotlin/com/bts/issue/repository/IssueRepository.kt`, `BE/test/kotlin/com/bts/issue/application/IssueCreateComponentAutoAssignIntegrationTest.kt`]
- depends-on: [1, 2, 3]

**RED**: Testcontainers 통합 `IssueCreateComponentAutoAssignIntegrationTest`.
- S1: 리드 Alice인 컴포넌트로 생성 → assignee=Alice + componentIds 영속 + **version=1**(B1: bump 없음 검증)
- S5: 리드 null 컴포넌트로 생성 → assignee null
- S4: 리드 다른 두 컴포넌트 → 이름 사전순 첫 리드
- 컴포넌트 없이 생성 → assignee null, componentIds 빈
- 검증실패: 다른 프로젝트/비활성 컴포넌트 → 422 COMPONENT_NOT_FOUND

**GREEN**: `createIssue` 흐름 확장.
1. request.componentIds 정규화(distinct) + `validateComponents(componentIds, projectId)` 재사용(FR-CM-02).
2. 후보 조회: `componentRepository.findByProject(projectId)`에서 componentIds∩ + leadUserId!=null → `ComponentLead` 목록.
3. `DefaultAssigneeResolver.resolve(current=null, candidates)` → resolvedAssignee.
4. `Issue.create(..., componentIds=정규화, assigneeId=resolvedAssignee)` → `repo.insert`(assignee_id+componentIds 외 기본필드 영속, version=1).
5. **컴포넌트 링크 영속(B1 정정)**: `replaceComponents`(OCC+version bump) 재사용 금지 — 신규 이슈는 동시편집/기존링크 없음. `IssueRepository`에 **생성 전용 `insertComponents(issueId: UUID, componentIds: List<UUID>)` 신규 추가**(version bump·DELETE·OCC 없는 순수 INSERT, FR-CM-02 replaceComponents의 INSERT 부분 재활용). createIssue가 insert 후 이 메서드 호출. 빈 목록이면 skip.
- 모두 같은 트랜잭션(NFR1, 클래스 레벨 @Transactional). 자동 배정은 silent(이벤트/히스토리 없음, NFR4).

**REFACTOR**: 후보 추출 private helper(`resolveDefaultAssignee(projectId, componentIds, current)`)로 추출(Task 5와 공유). insertComponents KDoc(생성 전용·no-bump 명시).

**검증**: `./gradlew :modules:issue-tracking:test --tests '*IssueCreateComponentAutoAssign*'`

### Task 5. 서비스 — changeComponents 자동배정

**메타**.
- agent: `backend-engineer`
- files: [`BE/main/kotlin/com/bts/issue/application/IssueApplicationService.kt`, `BE/main/kotlin/com/bts/issue/repository/IssueRepository.kt`, `BE/test/kotlin/com/bts/issue/application/IssueChangeComponentsAutoAssignIntegrationTest.kt`]
- depends-on: [1, 4]

**RED**: Testcontainers 통합 `IssueChangeComponentsAutoAssignIntegrationTest`.
- S2: 미할당 이슈에 리드 보유 컴포넌트 지정 → assignee 자동 설정 + **version 정확히 +1**(C4: 이중 bump 금지 검증)
- S3: 명시 담당자 있는 이슈에 컴포넌트 지정 → 담당자 보존(덮어쓰기 안 함)
- S6: 컴포넌트 전부 해제(componentIds=[]) → 기존 담당자 유지
- 낙관락 충돌 409(회귀)

**GREEN**: `changeComponents`에서 `replaceComponents`(version+1) 후, 현재 assignee가 null이면 Task4의
`resolveDefaultAssignee` 호출 → resolved 있으면 `existing.assignTo(resolved)` 경유로 담당자 영속.
- **C4 정정 — 이중 version bump 금지**: `replaceComponents`가 이미 version을 +1 했으므로, 자동 배정
  영속에 기존 `updateAssignee`(OCC+version bump) 재사용 금지(한 PATCH에 version +2 → 기존 FR-CM-02
  version 테스트 회귀). `IssueRepository`에 **no-bump `setAssignee(issueId: UUID, assigneeId: UUID?)`
  신규 추가**(version 미변경·OCC 없는 순수 assignee_id UPDATE, 같은 트랜잭션 side-effect 전용). assignee
  있으면 skip(FR4). componentIds 빈 → 후보 없음 → 변경 없음.

**REFACTOR**: 중복 제거(Task4 helper 재사용 확인) + setAssignee KDoc(no-bump·자동배정 전용, 사용자
직접 담당자 변경은 기존 changeAssignee/updateAssignee 유지) + 로그.

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
- files: [`FE/routes/issues.new.tsx`, `FE/routes/issues.new.test.tsx`, `FE/hooks/use-components.ts`, `FE/hooks/__tests__/use-components.test.tsx`, `FE/mocks/issue-handlers.ts`]
- depends-on: [6]

**RED**: `use-components.test.ts` + `issues.new.test.tsx`.
- `useComponents(key, { enabled: false })` → fetch 미발생(쿼리 idle), `{ enabled: true }`/옵션 생략 → fetch
- projectKey 빈 상태 → ComponentMultiSelect disabled(B3)
- 유효 projectKey 입력 → `useComponents(projectKey)` 활성 → options 채워짐 → 선택 후 제출 시 createIssue가 componentIds 받음
- MSW createIssue 핸들러가 componentIds 반영 + **백엔드 resolve 규칙(미할당+리드 보유 사전순 첫)과 동일 분기로** assigneeId 에코(C1: 가짜그린 방지, stateful, MEMORY msw-mutation-stateful-refetch)

**GREEN**: `issues.new.tsx`에 ComponentMultiSelect 추가.
- **B3 정정 — projectKey↔컴포넌트 연결**: 현재 `useComponents(projectKey)`는 옵션 인자가 없으므로(리뷰
  ground-truth 확인), **먼저 `useComponents`를 `useComponents(projectKey, options?: { enabled?: boolean })`로
  확장**하고 `useQuery`에 `enabled: options?.enabled ?? true` 전달(use-components.test.ts에 enabled=false 시
  fetch 안 함 검증 추가). 그 위에서 생성 폼은 `useComponents(projectKey, { enabled: projectKey.trim() !== '' })`로
  lazy 로드. projectKey 빈/로딩 동안 셀렉터 `disabled`, `options={data ?? []}`(ComponentMultiSelect는
  options-driven 순수 컴포넌트). projectKey 변경 시 선택 componentIds 보존, 제출 시 백엔드 422가 최종 방어.
- value/onChange 로컬 상태, 제출 시 componentIds 전달.
- **C1 정정 — MSW 미러**: createIssue 핸들러의 자동배정 모사를 백엔드 규칙과 동일하게(미할당 한정+리드 보유 컴포넌트 이름 사전순 첫). E2E(T8)는 백엔드 통합테스트(T4·T5)가 ground-truth임을 주석 명시.

**REFACTOR**: i18n 키 + 접근성 라벨.

**검증**: `pnpm --filter @bts/web test issues.new` + `pnpm --filter @bts/web typecheck`

### Task 8. E2E — 컴포넌트 지정 후 담당자 자동 표시

**메타**.
- agent: `qa-engineer`
- files: [`apps/web/e2e/issue-component-default-assignee.spec.ts`]
- depends-on: [4, 5, 7]

**RED/시나리오**: 리드 보유 컴포넌트를 이슈에 지정(생성 또는 상세 PATCH) → 담당자 필드에 리드가 자동 표시. 상세 자동 갱신(invalidate-only refetch) 확인. 행/컨테이너 한정 셀렉터(strict mode 회피, MEMORY playwright-getbyrole-exact-strict-mode).
- 주석 명시(C1): 이 E2E는 MSW 미러 위에서 동작하며 자동배정의 ground-truth는 백엔드 통합테스트(T4·T5)다.

**검증**: `pnpm --filter @bts/web exec playwright test issue-component-default-assignee`

## Plan 메타

- task 수: 8 (각 TDD 사이클)
- 예상 wave: 4 — W1[T1,T2,T3,T6] → W2[T4,T7] → W3[T5] → W4[T8]
- 직렬화 요인: T4·T5 동일 파일(IssueApplicationService.kt + IssueRepository.kt) → wave 분리. T8 E2E는 백엔드+프론트 완료 후
- TDD 강제: yes. 추가 검증: ktlint(ktlintMainSourceSetCheck+TestSourceSetCheck)·detekt·typecheck·vitest·playwright
- 마이그레이션: 없음(leadUserId 재사용)

## 리뷰 결과

### code-reviewer ground-truth 대조 (2026-06-05) — eng 집중 독립 리뷰

초기 결과: BLOCKER 3 / CONCERN 5. 모두 plan 정정으로 해소.

- **B1 (해소)**. 생성 경로 `replaceComponents` 재사용은 OCC version bump(1→2)로 신규 이슈 version=1 계약
  위반 → T4를 no-bump 전용 `insertComponents` 신규 메서드로 변경. version=1 검증 추가.
- **B2 (해소)**. T4 files에 `IssueRepository.kt` 누락 → 추가(insertComponents 신규).
- **B3 (해소)**. 생성 폼 projectKey 자유 텍스트 → 컴포넌트 셀렉터를 `useComponents(projectKey, {enabled})`
  lazy 로드 + projectKey 빈 동안 disabled로 T7 명시.
- **C1 (해소)**. MSW 에코 가짜그린 → createIssue 핸들러를 백엔드 resolve 규칙과 동일 분기로 미러,
  E2E는 백엔드 통합이 ground-truth임을 주석.
- **C2 (해소)**. NFR1 "이벤트 한 트랜잭션" 문구가 silent(NFR4)와 모순 → spec 문구 정정.
- **C3 (확인됨)**. private helper self-invocation은 진입 시 트랜잭션 열려 문제 없음.
- **C4 (해소)**. changeComponents에서 replaceComponents(+1) 후 updateAssignee(+1) = version +2 회귀 →
  no-bump `setAssignee` 신규로 한 PATCH version +1 유지. version +1 검증 추가.
- **B3 (2차 정정 후 해소)**. 1차 정정이 존재하지 않는 `useComponents(projectKey, {enabled})` 시그니처를
  참조 → 재리뷰서 신규 BLOCKER 적발. T7에 `hooks/use-components.ts` 확장(enabled 옵션 추가) 명시 + files
  추가로 해소. 부수: setAssignee는 주변 OCC 메서드가 IssueKey 키라 일관 위해 IssueKey 키 권장(no-OCC라 무방).
- **C5 (해소)**. 컴포넌트명 활성 유니크 → name 동률 불가(id tiebreak 방어적), resolver JVM 정렬 단일 진실 명시.

ground-truth 확인됨: Issue.create의 assigneeId 파라미터 존재(componentIds는 반환 객체 누락 — T2가 보강),
toInsertRecord의 assignee_id 영속, findByProject 활성 필터, changeComponents/changeAssignee silent,
validateComponents 재사용, ComponentMultiSelect 순수 컴포넌트 재사용, issueResponseSchema.componentIds .default([]).

3차 재검증: B3 enabled 옵션 확장 ground-truth 동작 확인 + 기존 호출처(ComponentList, settings.components)
단일 인자라 회귀 없음. 테스트 파일 경로 오기(`use-components.test.ts` → `hooks/__tests__/use-components.test.tsx`)
1건 정정.

**최종 BLOCKER: 0 / CONCERN: 0 (전부 정정 완료, 3라운드 리뷰).**

### PR 단위 코드리뷰 (2026-06-05, 게이트 2 직전)

- **code-reviewer agent (ground-truth)**: PASS. BLOCKER 0 / CONCERN 2(둘 다 nit).
  - 절대 규칙 19개 위반 0, 신규 의존성 0, TDD 규율 모범, learnings 회귀 없음.
  - C1(nit): `assignTo`가 단순 copy라 현재 도메인 검증 효과 형식적(향후 불변식 추가 대비 유지 권장 — 수정 불요).
  - C2(nit): MSW 정렬 tiebreak 누락(mock 한정, dead-path — 수정 불요).
- **구조 검증(/review 핵심)**: PASS. setAssignee/insertComponents jOOQ DSL+id한정 WHERE+null/empty 가드,
  changeComponents 부수효과 게이트(assignee null일 때만)+no-bump version+1, enum 신규 없음. 구조 이슈 0.
- plan-ceo-review: skip(type=feature, auth/migration 아님).
- 검증 합계: backend ktlint+detekt+989 테스트 / frontend typecheck clean+1222 테스트 / E2E 4/4.
- 알려진 한계: E2E가 MSW componentLeadStore 브라우저 시드 부재로 자동배정 "담당자 이름" 표시는 직접 검증 못
  함 → 자동배정 ground-truth는 백엔드 Testcontainers 통합(IssueCreate/ChangeComponentsAutoAssignIntegrationTest).
  계층화 타당(리뷰 합의).

### 게이트 2 후 수정 + 재리뷰 (2026-06-05)

Maxi 요청으로 C2 + E2E 한계 수정(프로젝트 리드 폴백은 별도 후속 FR로 분리 — admin≠lead, cross-BC).
- **C2 해소**: MSW createIssue 자동배정을 component-handlers의 componentStore(셀렉터·브라우저 시드와 동일
  출처)에서 읽도록 통합(`getStoredComponentsByIds`), 정렬 `name → id` tiebreak으로 백엔드 DefaultAssigneeResolver와
  일치. 별도 componentLeadStore/seedComponentLeads 제거.
- **E2E 한계 해소**: X-MSW-Seed-Components로 리드(alice) 보유 컴포넌트 시드 → 생성 후 담당자 영역에 자동배정
  리드 **이름(김앨리스)** 표시 직접 검증 + 다중 컴포넌트 사전순 첫 리드 tiebreak 시나리오(선택순서 무관).
- 수정 범위 = MSW mock + test + E2E만(프로덕션 런타임 코드 0). 재리뷰 PASS.
- 검증: 프론트 vitest 1227 그린 + typecheck TS에러 0, E2E 4 + 회귀 6 그린. 백엔드 무변경(989 유효).
- 잔여 minor: MSW changeComponentsHandler는 자동배정 미러 안 함(상세 PATCH 경로 E2E는 생성 경로로 검증,
  ground-truth는 백엔드 T5). 후속 정리 후보.
- **후속 FR 예정**: 컴포넌트 리드 없을 때 프로젝트 리드 폴백(2순위). admin(다수·권한)≠lead(단일·담당)이라
  project_memberships에 단일 PROJECT_LEAD 지정 + cross-BC 포트로 설계 예정(Maxi와 방향 논의 중).
