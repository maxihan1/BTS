# FR-EP-02 — Epic 진행률 자동 집계

> slug: fr-ep-02-epic-progress
> type: feature
> agent: backend-engineer
> 생성: 2026-06-23

## Brief

사용자 원문: "fr-ep-02 진행하자"

FR-EP-02 — Epic 진행률 자동 집계 (agile-planning BC §7.2).
- 선행: FR-EP-01 (Epic 이슈 타입 + 자식 연결, PR #175 완료) — `issues.epic_id` 자기참조 FK 기반
- 핵심: 에픽에 연결된 자식 이슈들의 상태(WorkflowState) 비율을 집계해 진행률 자동 계산
- 엔드포인트: GET /api/v1/epics/{key}/progress
- 데이터 모델: 신규 테이블 없이 활용 (epic_id 기반 집계 쿼리)
- 프론트: 진행률 막대 UI

classify 보정: project-workflow/api → agile-planning/feature (수동)

## 도메인 정리

- **BC: issue-tracking** (명세 product/agile-planning.md §7.2 분류이나 실제 코드 BC는 issue-tracking. FR-EP-01 ADR 2026-06-22 확정 + 전체 코드가 `com.bts.issue.epic` 패키지). FR-EP-02도 동일 패키지.
- **영향 코드 (모두 issue-tracking 모듈)**.
  - `epic/web/IssueEpicController.kt` — GET `/api/v1/epics/{key}/progress` 메서드 추가 (현재 `/issues/{key}/epic-children` 3종 보유)
  - `epic/application/IssueEpicService.kt` — 진행률 집계 메서드 추가
  - `epic/web/dto/EpicProgressResponse.kt` (신규 DTO)
  - `epic/web/EpicChildExceptionHandler.kt` — `assignableTypes=[IssueEpicController]`라 새 엔드포인트 자동 커버 (입력예외/404/403 재사용)
- **재사용 자산**.
  - `IssueRepository.findEpicChildren(epicId, actor, access, projectKey)` (IssueRepository.kt:1495) — epic_id FK 필터 + buildActiveSecureWhere(deleted_at·accessibleLevels·동일프로젝트 푸시다운). 진행률 집계의 자식 모수.
  - 권한 게이트 패턴 — BROWSE(Project scope) + accessibleLevels visibility 필터 (listChildren 동형, IssueEpicService.kt:194)
- **cross-BC 경계 (핵심)**. 자식 이슈는 `Issue.currentStateKey`(String)만 보유. category/isDone 없음. "완료" 판정은 shared-kernel SPI `WorkflowStateCatalog.listStates(projectKey, issueTypeKey)` → `WorkflowStateView.isDone`(=category==DONE). 이미 board adapter가 사용 중 → issue-tracking → shared-kernel 의존 정상.
- **새 용어 후보**. "Epic 진행률 (Epic Progress)" — glossary 추가 검토 (Maxi 승인 대기).
- **기존 결정 충돌**. 없음. ADR 2026-06-22 §영향이 `GET /api/v1/epics/{key}/progress`를 "epic_id 직속 자식 기준"으로 이미 예약.
- **관련 ADR**. [docs/decisions/2026-06-22-fr-ep-01-epic-child-link.md](../decisions/2026-06-22-fr-ep-01-epic-child-link.md) (기존, FR-EP-02 미리 커버). 진행률 정의 방식 확정 시 작은 ADR 추가 검토.
- **확정 결정 (Maxi 2026-06-23)**.
  1. **진행률 정의** = done/total 백분율 + 3카테고리(TODO/IN_PROGRESS/DONE) 카운트 분해. 응답 `{total, done, donePercentage, byCategory:{TODO, IN_PROGRESS, DONE}}`. Jira 에픽 진행률 바 parity, 막대 3색 구간.
  2. **상태 판정** = 자식 **타입별** `listStates(projectKey, issueTypeKey)` 정확 판정. 타입별 1회 캐싱(타입 2~3종 → 조회 2~3회, N+1 아님). currentStateKey → 해당 타입 WorkflowStateView.category 매핑.
  3. **빈 에픽(자식 0)** = total=0, donePercentage=0, byCategory 전부 0 (에러 아님).
  4. **함정** — listStates N+1 회피(타입별 캐싱 맵), 미할당 워크플로우/매핑 안 되는 상태키 → 비-DONE(category 미상)으로 처리(spec에서 분류 규칙 확정).

## 스펙

전체 스펙. [docs/specs/2026-06-23-fr-ep-02-epic-progress.md](../specs/2026-06-23-fr-ep-02-epic-progress.md)

핵심 시나리오 3줄 요약.
- `GET /api/v1/epics/{key}/progress` → 직속 자식(epic_id)들을 카테고리(TODO/IN_PROGRESS/DONE)로 집계해 `{total, done, donePercentage, byCategory}` 반환
- 카테고리 판정은 자식 **타입별** `WorkflowStateCatalog.listStates(projectKey, typeKey)` 기준 (타입별 1회 캐싱, N+1 차단)
- BROWSE(Project) 게이트 + accessibleLevels 푸시다운 → **보이는 자식만** 모수(누출 0). 빈 에픽 0%, 매핑 실패 상태키는 TODO 분류

## Brainstorming Check

✅ 통과 (직접 self-review, 1회). visibility 모수 기준·손자 포함 여부·매핑 실패 처리·반올림 규칙을 스펙에 명문화. Maxi 결정 필요 gap 없음(진행률 정의는 도메인 단계 확정).

## Plan

> 전체 스펙: [docs/specs/2026-06-23-fr-ep-02-epic-progress.md](../specs/2026-06-23-fr-ep-02-epic-progress.md)
> 계약 고정 (drift 방지): 응답 `{ total:Int, done:Int, donePercentage:Int, byCategory:{ todo:Int, inProgress:Int, done:Int } }`. byCategory JSON 키는 camelCase(todo/inProgress/done) — 구현 시 기존 enum 노출 관례 grep 후 Zod와 일치 확정. 최상위 `done` == byCategory.done(편의 중복 허용).

### Task 1. EpicProgress 도메인 VO + 진행률 계산 순수 로직

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/epic/domain/EpicProgress.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/epic/domain/EpicProgressTest.kt`]
- depends-on: []

**RED**.
- 파일: `EpicProgressTest.kt`
- 테스트:
  ```kotlin
  // categories: 각 직속 자식의 워크플로우 카테고리 문자열 리스트 (이미 해석됨)
  @Test fun `aggregates categories into byCategory counts`()        // [DONE,DONE,IN_PROGRESS,TODO] → todo1,inProgress1,done2,total4
  @Test fun `donePercentage rounds half`()                          // done1/total3 → 33, done2/total3 → 67
  @Test fun `empty epic yields zero progress`()                     // [] → total0, done0, donePercentage0, all category 0 (EC1)
  @Test fun `unmapped or unknown category falls back to TODO`()     // "FOO"/null → todo 카운트 (EC2/EC3). EC3는 도달불가 방어(StateCategory enum 3값뿐)임을 주석 명시
  @Test fun `all done yields 100 percent`()                         // [DONE,DONE] → 100 (EC5)
  @Test fun `top-level done equals byCategory done`()               // 편의 중복 필드 정합 단언 (리뷰 NIT — 한쪽만 변경 시 회귀 차단)
  ```
- 실패 메시지(예상): `EpicProgress` 클래스 없음.

**GREEN**.
- 파일: `EpicProgress.kt`
- `data class EpicProgress(total, done, donePercentage, todo, inProgress, doneCount)` 또는 `byCategory` 중첩 VO.
- `companion fun of(categories: List<String?>): EpicProgress` — 정규화(TODO/IN_PROGRESS/DONE만 인정, 그 외/null→TODO) + 집계 + `donePercentage = if (total>0) Math.round(done*100.0/total).toInt() else 0`.

**REFACTOR**.
- 카테고리 정규화를 `StateCategory` 표준 3값과 1:1 매핑하는 private when으로. KDoc에 EC2/EC3 폴백 규칙 명시.

**검증**. `./gradlew :backend:issue-tracking:test --tests "*EpicProgressTest"`

### Task 2. IssueEpicService.progress() — WorkflowStateCatalog 집계

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/epic/application/IssueEpicService.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/epic/application/IssueEpicServiceTest.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/epic/application/IssueEpicServiceProgressTest.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/epic/web/IssueEpicControllerIntegrationTest.kt`]
- depends-on: [1]

**주의 (learnings) — ★ plan-review BLOCKER 반영**.
- **★ 생성자 주입 파급 = 생성 사이트 2곳** ([[plan-files-constructor-injection-existing-tests]]). `IssueEpicService(` 생성은 **두 곳**.
  1. `IssueEpicServiceTest.kt:46` (mockk) — WorkflowStateCatalog mockk 인자 추가
  2. `IssueEpicControllerIntegrationTest.kt:197` TestConfig (5-arg 빈) — **반드시 같은 task에서 수정**. 이 기존 테스트는 progress를 안 쓰므로 **relaxed mockk `WorkflowStateCatalog` 빈 1줄**만 추가하면 충분(실 스킴 시드 불요). 누락 시 Task 2 완료 시점에 issue-tracking test 컴파일 깨진 채 Task 3 진입.
- **★ listStates throw 경로 폴백** (운영 500 차단). `WorkflowResolverImpl.resolveExistingFor`는 스킴 미할당(null→emptyList, OK)과 달리 **매핑 없음/프로젝트 없음은 throw**(`WorkflowSchemeNoDefaultException`/`ProjectNotFoundException`, RuntimeException). 자식 타입 중 하나라도 스킴 매핑 없으면 progress 전체가 500. 선례 `IssueMoveService.kt:193-196`처럼 **simple-name 문자열 catch**(BC 내부 예외라 import 불가) → 해당 타입 상태맵을 빈 맵으로 폴백(그 타입 자식 전부 TODO).
- **★ 타입→typeKey 해석 N+1 차단** (CONCERN 4). `issueTypeRepository`에 `findByIds` 배치 메서드 **없음**(`findById`/`findByKey`/`findAll`만). → **`issueTypeRepository.findAll()` 1쿼리**로 typeId→IssueTypeKey 맵을 만들어 in-memory 해석(DB 1쿼리 고정, 타입 수 적어 무해, distinct 망각 N+1 차단).
- **타입별 listStates 캐싱** — distinct typeKey별 `listStates(projectKey, typeKey)` **1회씩**(FR4). typeKey별 `Map<stateKey, category>` 캐시 → 각 자식 `currentStateKey` → category 해석 → `List<String?>` → `EpicProgress.of(...)`.

**RED**.
- 파일: `IssueEpicServiceProgressTest.kt` (단위, mockk)
- 테스트:
  ```kotlin
  @Test fun `progress denies without BROWSE`()                      // 403 (S5)
  @Test fun `progress 404 when epic missing`()                      // (S6)
  @Test fun `progress aggregates visible children by type workflow`()  // 타입별 listStates 판정 (S3)
  @Test fun `progress calls listStates once per child type`()       // N+1 부재 — verify(exactly = typeCount) (FR4)
  @Test fun `progress empty epic returns zero`()                    // (S2/EC1)
  @Test fun `progress tolerates a child type with no workflow scheme`()  // ★ listStates가 NoDefault throw → 그 타입 자식 TODO, 나머지 정상, 200 (BLOCKER2)
  ```
- 실패 메시지(예상): `IssueEpicService.progress` 메서드 없음.

**GREEN**.
- `@Transactional(readOnly = true) fun progress(epicKey: IssueKey, actor: ActorId): EpicProgress`
- listChildren 동형 — BROWSE(Project) 게이트 → epic 조회(404) → accessibleLevels → findEpicChildren → `issueTypeRepository.findAll()` typeId→key 맵 → distinct typeKey별 listStates(NoDefault catch 폴백) 캐싱 → category 해석 → `EpicProgress.of`.

**REFACTOR**.
- typeKey/category 캐싱 + NoDefault catch를 private 헬퍼로 추출(LongMethod 억제). 단, listStates 호출 헬퍼는 **같은 트랜잭션 프록시 경계 안**에서만 불리게 유지(self-invocation으로 readOnly 트랜잭션 우회 방지, [[transaction-self-invocation-requires-new]] 정신 — private 추출은 프록시 무관하나 별 Bean 분리 금지). KDoc에 visibility 모수(보이는 자식만)·N+1 캐싱·throw 폴백 명시.

**검증**. `./gradlew :backend:issue-tracking:test --tests "*IssueEpicServiceProgressTest" --tests "*IssueEpicServiceTest" --tests "*IssueEpicControllerIntegrationTest"`

### Task 3. IssueEpicController GET /api/v1/epics/{key}/progress + DTO (통합)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/epic/web/IssueEpicController.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/epic/web/dto/EpicProgressResponse.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/epic/web/IssueEpicProgressControllerIntegrationTest.kt`]
- depends-on: [2]

**주의 (learnings) — ★ plan-review BLOCKER 반영**.
- **★ 실 WorkflowStateCatalogImpl 조립 + 스킴/상태 DB 시드 (가짜그린 차단)**. S3(타입별 다른 DONE 키 판정)는 실 빈 + 시드 없이는 검증 불가 — 시드 없으면 전부 TODO로 떨어져 그린인데 집계 안 됨. **선례 = `IssueMoveIntegrationTest.IssueMoveConfig`** (`workflowStateCatalogImpl(workflowResolver)` :232, 주입 :244/:269). 다음을 **명시 sub-step**으로 조립.
  1. `WorkflowResolverImpl` + 의존 repo(assignment/mapping/workflow + `ProjectLookupPort`) 빈 등록
  2. `WorkflowStateCatalogImpl(WorkflowResolverImpl)` 빈 등록
  3. **DB 시드** — 워크플로우 + 스킴 + type-mapping + 상태. **Story/Bug 타입별 서로 다른 DONE 상태 키**를 시드해야 S3가 vacuous green이 아님.
  4. **TestConfig 선택 (택1 확정)** — 기존 `IssueEpicControllerIntegrationTest`의 TestConfig를 **확장**(같은 컨트롤러라 자연스러움)하되, Task 2가 그 파일에 추가한 relaxed mockk 빈은 progress 미사용 테스트용이고, **신규 progress 테스트는 실 빈 + 시드**를 쓴다(별 TestConfig 또는 별 시드 메서드). move 통합테스트의 실 조립 패턴을 복사.
- **JSON naming** — `EpicProgressResponse` 필드/byCategory 키를 기존 응답 관례(camelCase) + Zod 계약과 일치. 새 예외 불필요(`EpicChildExceptionHandler` assignableTypes로 401/403/404 자동 커버).
- **경로** — `@GetMapping`에 클래스 `@RequestMapping` 외 별도 `/api/v1/epics/{key}/progress` 추가(기존은 `/api/v1/issues`). SecurityConfig는 `/api/**` authenticated catch-all로 자동 보호(permitAll 화이트리스트 미포함 — 누출 없음, 리뷰 item7 확인). actor는 이슈 조회 **전** 추출(존재 probe 방지).

**RED**.
- 파일: `IssueEpicProgressControllerIntegrationTest.kt` (Testcontainers)
- 테스트: 200 정상 집계(S1) / 401(S7) / 403(S5) / 404(S6) / visibility 제외(S4) / 빈 에픽(S2) / **타입별 다른 DONE 키 판정(S3 — 실 시드)**.
- 실패 메시지(예상): 404 라우트 없음 또는 컴파일 실패.

**GREEN**.
- 컨트롤러 `progress(@PathVariable key)` → `service.progress(...)` → `EpicProgressResponse.from(vo)` → `DataResponse`.
- `EpicProgressResponse` DTO + `from(EpicProgress)`.

**REFACTOR**.
- DTO from 매핑 KDoc + 응답 예시.

**검증**. `./gradlew :backend:issue-tracking:test --tests "*IssueEpicProgressControllerIntegrationTest"` + 모듈 ktlint/detekt.

### Task 4. 프론트 API 클라이언트 + Zod 스키마 + MSW

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/api/epic-children.ts`, `apps/web/src/api/epic-progress.test.ts`, `apps/web/src/mocks/issue-handlers.ts`, `apps/web/src/mocks/issue-fixtures.ts`]
- depends-on: [3]

**주의 (learnings)**.
- **Zod 계약** — `EpicProgressSchema`를 백엔드 `EpicProgressResponse`와 **정확히 일치**(필드/키/타입). spec의 응답 형태 grep 검증([[frontend-zod-backend-dto-contract-gap]]). 백엔드 BC(issue-tracking) api 관례 따름([[frontend-api-convention-per-bc]]) — `epic-children.ts`와 같은 파일/같은 fetch 유틸(apiFetch) 재사용.
- **MSW** — progress 핸들러 + 픽스처. 자식 연결/해제 mutation 후 progress가 갱신되도록 stateful하게([[msw-mutation-stateful-refetch]] / [[msw-derived-behavior-shared-store-e2e]]) — 가능하면 자식 store에서 파생 집계.

**RED**.
- 파일: `epic-progress.test.ts`
- 테스트: `fetchEpicProgress(key)` 정상 파싱 / Zod 거부(필드 누락) / 0% 빈 에픽.

**GREEN**.
- `epic-children.ts`에 `fetchEpicProgress` + `EpicProgressSchema` 추가. MSW 핸들러/픽스처.

**REFACTOR**.
- 스키마/타입 export 정리.

**검증**. `pnpm test -- epic-progress` + `pnpm typecheck`.

### Task 5. EpicProgressBar 컴포넌트 + 이슈 상세 통합

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/issue/EpicProgressBar.tsx`, `apps/web/src/components/issue/EpicProgressBar.test.tsx`, `apps/web/src/components/issue/EpicChildrenSection.tsx`, `apps/web/src/i18n/ko.ts`]
- depends-on: [4]

**주의 (learnings)**.
- **통합 지점** — `EpicChildrenSection`이 렌더되는 Epic 상세(이슈 상세 재사용) 위치에 진행률 막대 인접 배치. EpicChildrenSection의 부모 추적해 통합.
- **invalidate** — 자식 연결/해제(FR-EP-01 mutation) 후 progress queryKey invalidate(cross-mutation 공유, [[ui-permission-gating-needs-summary-api-exposure]] 스타일 cross-invalidate).
- **렌더** — 3색 가로 바(done/inProgress/todo 비율) + `donePercentage%` + `done/total 완료`. 자식 0 → "자식 이슈 없음" 0% 빈 바. 테스트 텍스트 중복 시 컨테이너 한정([[playwright-getbyrole-exact-strict-mode]] 정신).
- **★ i18n 한글 라벨** (CONCERN 5). 신규 문자열("자식 이슈 없음"/"완료"/"진행률" 등)은 인라인 하드코딩 금지 — `apps/web/src/i18n/ko.ts`에 추가하고 `ko.test.ts` **콜론 종결 검증** 통과([[fr-mf-05-trusted-devices-done]] i18n 콜론 규칙).
- **★ visibility 주석** (NIT) — `total`/카운트는 보는 사람 권한(accessibleLevels)에 따라 다를 수 있음(사용자 A는 10, B는 7). 버그 아님을 컴포넌트 KDoc에 1줄 남겨 QA 오인 리포트 예방.

**RED**.
- 파일: `EpicProgressBar.test.tsx`
- 테스트: 비율 막대 렌더(40% done) / 빈 에픽 표시 / 카운트 텍스트.

**GREEN**.
- `EpicProgressBar.tsx` + EpicChildrenSection 통합.

**REFACTOR**.
- 색상/비율 계산 순수 함수 분리.

**검증**. `pnpm test -- EpicProgressBar` + `pnpm typecheck` + `pnpm lint`.

### Task 6. E2E — Epic 진행률 표시

**메타**.
- agent: `qa-engineer`
- files: [`apps/web/e2e/epic-progress.spec.ts`, `apps/web/src/mocks/issue-handlers.ts`]
- depends-on: [5]

**주의 (learnings)**.
- **MSW store 시드** — E2E는 SPA 내부 이동으로 상태 유지(reload 금지=가짜그린, [[worktree-stale-base-rebase-and-e2e-msw-traps]] / [[fr-nt-03-d6-d7-done]]). 신규 store 모듈 로드 자동 시드([[fr-bd-01-d6-d7-board-ui-done]]).
- **orphan vite** — worktree E2E 후 5173 orphan kill([[e2e-orphan-vite-after-worktree-remove]]).

**RED/GREEN**.
- 시나리오: Epic 상세 진입 → 진행률 막대 + 퍼센트 표시 / 자식 상태 변화 후 진행률 갱신(있으면).

**검증**. `pnpm test:e2e -- epic-progress`.

## Plan 메타

- task 수: 6 (백엔드 3 · 프론트 2 · E2E 1)
- 의존 그래프: 1 → 2 → 3 → 4 → 5 → 6 (레이어 + 계약 의존으로 대체로 직렬). 예상 wave 약 6.
  - 백엔드 3 task는 도메인VO→서비스→컨트롤러 레이어 직렬. 프론트는 백엔드 계약(Task 3) 확정 후. 파일 겹침 없음.
- 예상 시간: 약 30~40분 (직렬 기준 — Task 3 실 워크플로우 스킴/상태 DB 시드 부담 반영해 상향, plan-review 지적)
- TDD 강제: yes (test→feat 커밋 순서 자동 검증)
- 데이터 모델: 변경 없음 (마이그레이션 task 없음 → db-engineer 미관여)
- 추가 검증: ktlint/detekt(backend), typecheck/vitest/lint(front), playwright(qa)
- cross-BC: issue-tracking → shared-kernel WorkflowStateCatalog SPI (신규 의존 없음, 기존 board/move 선례)
- Task 3 리스크: cross-BC 실 빈 조립 + 스킴 시드 = 최대 난이도 task. move 통합테스트 선례 복사가 핵심.

## 리뷰 결과

### plan-eng-review (적대 리뷰, code-reviewer 에이전트, 2026-06-23)

autoplan(4종) 대신 eng 집중 독립 적대 리뷰([[bts-review-plan-autoplan-overkill]]). 8개 항목 검증, 근거 파일:라인 인용.

**BLOCKER 3건 (모두 plan에 반영 완료)**.
1. **생성자 주입 파급 파일 누락** — `IssueEpicService(` 생성 사이트 2곳 중 `IssueEpicControllerIntegrationTest.kt:197`이 plan files에 없어 Task 2 완료 시 컴파일 깸 → Task 2 files에 추가 + relaxed mockk 빈 1줄. ✅ 반영
2. **listStates throw 미처리 운영 500** — `WorkflowResolverImpl`이 스킴 매핑 없으면 `WorkflowSchemeNoDefaultException` throw. 자식 타입 하나 미할당이면 progress 전체 500 → simple-name catch 폴백(`IssueMoveService.kt:193-196` 선례) + RED 케이스. ✅ 반영
3. **Task 3 cross-BC 실 빈 + 시드 미분해 = 가짜그린** — S3(타입별 DONE) 검증은 실 WorkflowStateCatalogImpl+WorkflowResolverImpl+스킴/상태 시드 필요 → 명시 sub-step 4단계 + move 통합테스트 선례 복사 + 예상시간 상향. ✅ 반영

**CONCERN 2건 (반영 완료)**.
4. **typeId 해석 N+1** — `findByIds` 부재 → `issueTypeRepository.findAll()` 1쿼리 in-memory 맵. ✅ 반영
5. **i18n 라벨 task 부재** — 신규 한글 문자열 ko.ts + ko.test 콜론 규칙. ✅ Task 5 반영

**NIT (반영)**. done==byCategory.done 정합 단언 테스트 / EC3 vacuous 주석 / visibility 사람별 차이 컴포넌트 KDoc / REFACTOR 트랜잭션 프록시 경계 유지.

**이상 없음 확인**. listStates MANDATORY 충족(progress readOnly 트랜잭션, MovePreviewService 선례) / 경로 보안(`/api/**` authenticated catch-all, permitAll 미포함, 누출 0) / EpicProgress 0~100 범위 보장 / visibility 모수(보이는 자식만 = spec S4 의도 정합).

**종합 판정**. 수정 후 진행(Revise-then-proceed). BLOCKER 1~3 + CONCERN 4~5 plan 반영 완료 → **승인 가능 상태**.
