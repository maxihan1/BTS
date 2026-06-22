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
  @Test fun `unmapped or unknown category falls back to TODO`()     // "FOO"/null → todo 카운트 (EC2/EC3)
  @Test fun `all done yields 100 percent`()                         // [DONE,DONE] → 100 (EC5)
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
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/epic/application/IssueEpicService.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/epic/application/IssueEpicServiceTest.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/epic/application/IssueEpicServiceProgressTest.kt`]
- depends-on: [1]

**주의 (learnings)**.
- **생성자 주입 변경** — `WorkflowStateCatalog` 의존 추가. 기존 `IssueEpicServiceTest`의 생성자 호출/mockk 셋업도 같은 task에서 수정([[plan-files-constructor-injection-existing-tests]]). fail-closed 원칙대로 default 없는 non-null 주입.
- **타입→typeKey 해석** — `findEpicChildren`는 `List<Issue>`(typeId 보유). 자식 distinct `typeId` → `issueTypeRepository.findById` → `IssueTypeKey`. distinct typeKey별 `listStates(projectKey, typeKey)` **1회씩 캐싱**(자식 수만큼 호출 금지 = N+1 차단, FR4).
- **카테고리 맵** — typeKey별 `Map<stateKey, category>` 캐시. 각 자식 `currentStateKey` → category 해석 → `List<String?>` → `EpicProgress.of(...)`.

**RED**.
- 파일: `IssueEpicServiceProgressTest.kt` (단위, mockk)
- 테스트:
  ```kotlin
  @Test fun `progress denies without BROWSE`()                      // 403 (S5)
  @Test fun `progress 404 when epic missing`()                      // (S6)
  @Test fun `progress aggregates visible children by type workflow`()  // 타입별 listStates 판정 (S3)
  @Test fun `progress calls listStates once per child type`()       // N+1 부재 — verify(exactly = typeCount) (FR4)
  @Test fun `progress empty epic returns zero`()                    // (S2/EC1)
  ```
- 실패 메시지(예상): `IssueEpicService.progress` 메서드 없음.

**GREEN**.
- `@Transactional(readOnly = true) fun progress(epicKey: IssueKey, actor: ActorId): EpicProgress`
- listChildren 동형 — BROWSE(Project) 게이트 → epic 조회(404) → accessibleLevels → findEpicChildren → typeKey 캐싱 → listStates 캐싱 → category 해석 → `EpicProgress.of`.

**REFACTOR**.
- typeKey/category 캐싱을 private 헬퍼로 추출(LongMethod 억제). KDoc에 visibility 모수(보이는 자식만)·N+1 캐싱 명시.

**검증**. `./gradlew :backend:issue-tracking:test --tests "*IssueEpicServiceProgressTest" --tests "*IssueEpicServiceTest"`

### Task 3. IssueEpicController GET /api/v1/epics/{key}/progress + DTO (통합)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/epic/web/IssueEpicController.kt`, `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/epic/web/dto/EpicProgressResponse.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/epic/web/IssueEpicProgressControllerIntegrationTest.kt`]
- depends-on: [2]

**주의 (learnings)**.
- **cross-BC SPI 부팅** — 통합테스트 컨텍스트에 project-workflow `WorkflowStateCatalogImpl` 빈 필요. 기존 board/move 통합테스트가 `WorkflowStateCatalog`를 어떻게 로드/stub하는지 grep 후 동일 패턴([[no-cross-bc-deployment-assembly]] / cross-BC SPI @MockBean 부팅함정). 실 빈 로드 가능하면 시드 기반 end-to-end 검증 우선.
- **JSON naming** — `EpicProgressResponse` 필드/byCategory 키를 기존 응답 관례(camelCase) + Zod 계약과 일치. 새 예외 불필요(`EpicChildExceptionHandler` assignableTypes로 401/403/404 자동 커버).
- **경로** — `@GetMapping`에 클래스 `@RequestMapping` 외 별도 `/api/v1/epics/{key}/progress` 추가(기존은 `/api/v1/issues`). actor는 이슈 조회 **전** 추출(존재 probe 방지).

**RED**.
- 파일: `IssueEpicProgressControllerIntegrationTest.kt` (Testcontainers)
- 테스트: 200 정상 집계(S1) / 401(S7) / 403(S5) / 404(S6) / visibility 제외(S4) / 빈 에픽(S2).
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
- files: [`apps/web/src/components/issue/EpicProgressBar.tsx`, `apps/web/src/components/issue/EpicProgressBar.test.tsx`, `apps/web/src/components/issue/EpicChildrenSection.tsx`]
- depends-on: [4]

**주의 (learnings)**.
- **통합 지점** — `EpicChildrenSection`이 렌더되는 Epic 상세(이슈 상세 재사용) 위치에 진행률 막대 인접 배치. EpicChildrenSection의 부모 추적해 통합.
- **invalidate** — 자식 연결/해제(FR-EP-01 mutation) 후 progress queryKey invalidate(cross-mutation 공유, [[ui-permission-gating-needs-summary-api-exposure]] 스타일 cross-invalidate).
- **렌더** — 3색 가로 바(done/inProgress/todo 비율) + `donePercentage%` + `done/total 완료`. 자식 0 → "자식 이슈 없음" 0% 빈 바. 테스트 텍스트 중복 시 컨테이너 한정([[playwright-getbyrole-exact-strict-mode]] 정신).

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
- 예상 시간: 약 18~24분 (직렬 기준)
- TDD 강제: yes (test→feat 커밋 순서 자동 검증)
- 데이터 모델: 변경 없음 (마이그레이션 task 없음 → db-engineer 미관여)
- 추가 검증: ktlint/detekt(backend), typecheck/vitest/lint(front), playwright(qa)
- cross-BC: issue-tracking → shared-kernel WorkflowStateCatalog SPI (신규 의존 없음, 기존 board/move 선례)

## 리뷰 결과 (← /bts-review-plan 채움)
