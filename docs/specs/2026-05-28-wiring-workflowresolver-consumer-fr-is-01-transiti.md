<!-- 이슈 상태 전이 백엔드 wiring slice 상세 명세 — D1 신규 SPI WorkflowKeyResolver 채택, BC 격리 유지하며 issue-tracking → project-workflow consumer 연결 -->

# 이슈 상태 전이 백엔드 wiring — Spec

> slug: wiring-workflowresolver-consumer-fr-is-01-transiti
> 작성: 2026-05-28
> 관련 plan: ../plans/2026-05-28-wiring-workflowresolver-consumer-fr-is-01-transiti.md
> 관련 ADR: ../decisions/2026-05-27-shared-kernel-extraction.md
> office-hours 스킵 사유. 본 작업은 product 검증이 아닌 기존 SPI consumer 연결(구현 결정). 도메인 결정 D1~D4 명확, 직접 spec 작성이 효율적.

## 1. 작업 요약

PR #18에서 project-workflow BC에 도입된 워크플로우 결정 로직(`WorkflowResolver`/`Scheme`/`Mapping`)을, issue-tracking BC가 PR #25에서 분리한 shared-kernel SPI 경계를 통해 호출하도록 연결한다. 이슈 상태 전이가 런타임에 실제 동작하게 만들어 후속 FR-IS-01 D7 E2E(생성→조회→수정→전이→삭제→키 영속성)를 unblock 한다.

## 2. 도메인 결정 (D1~D4, 게이트1 검토)

### D1. WorkflowResolver 통합 패턴 — (b) 신규 SPI `WorkflowKeyResolver` 정의 (Maxi 결정 2026-05-28)

- shared-kernel(`com.bts.shared.workflow`)에 신규 인터페이스 `WorkflowKeyResolver` 정의.
- 시그니처. `resolveStart(projectKey: ProjectKey, issueTypeKey: IssueTypeKey?): WorkflowStartState` (D2 보조 결정 반영 — workflowKey + startStateKey 묶음 반환). `@Transactional(propagation = MANDATORY)`.
- project-workflow가 `WorkflowKeyResolverImpl` 제공 — 내부적으로 기존 `WorkflowResolverImpl` 결과의 `Workflow.key` + `states.first { displayOrder=1 }.key` 추출.
- issue-tracking은 `WorkflowKeyResolver`만 호출. `WorkflowResolver` / `Workflow` 객체 직접 사용 0.

**근거**. shared-kernel published language 정신(consumer가 알아야 할 최소만 노출). issue-tracking은 workflowKey + startStateKey 문자열만 필요. ADR `2026-05-27-shared-kernel-extraction`의 자연스러운 후속 + 첫 consumer 적용 사례.

### D2. 워크플로우 결정 시점 — 추천 (a) 이슈 생성 + 전이 둘 다 (게이트1 검토)

생성 시점에도 `WorkflowKeyResolver.resolveStart()` 호출 → workflowKey + 시작 상태 키(정본 컨벤션 소문자) → currentStateKey 결정.

**근거**.
- 생성 시점에서도 워크플로우 정본 기반으로 시작 상태 결정 → currentStateKey 케이스 자동 정렬 (소문자).
- 생성/전이가 같은 결정 흐름을 거치므로 데이터 정합 위험 0.
- 대안 (b) "전이 시점만"은 생성 시 여전히 `"open"` 하드코딩 필요 → 워크플로우 변경/확장 시 또 wiring 갈라짐.

**영향**. `IssueApplicationService.create`가 `WorkflowKeyResolver` 호출 추가.

#### D2 보조 결정. 시작 상태 조회 방식 — (b1) SPI 시그니처 통합

`resolveStart()` 단일 메서드로 `WorkflowStartState(workflowKey, startStateKey)` 묶음 반환. consumer 1회 호출. 대안 (b2) "별 SPI `WorkflowStartStateResolver`"는 SPI 늘림 비용.

### D3. currentStateKey 케이스 마이그레이션 — 추천 (a) Flyway 마이그레이션 (게이트1 검토)

- `V00X__lowercase_current_state_key.sql` 추가 (issue-tracking 모듈 db/migration).
- 본문. `UPDATE issues SET current_state_key = LOWER(current_state_key) WHERE current_state_key <> LOWER(current_state_key);`
- 적용 후 새 생성 코드는 항상 소문자(D2(a) 결과).

**근거**. 기존 DB의 `"OPEN"` 데이터를 한 번에 정합. 대안 (b) "새 데이터부터"는 기존/신규 데이터 케이스 혼재로 검색·비교 함정.

### D4. issueTypeKey 전달 — `null` (default mapping)

본 PR scope. `Issue` 도메인에 `IssueType` 없음 (FR-IS-02 미구현). `WorkflowKeyResolver.resolveStart(projectKey, null)` 호출 → resolver가 default mapping(`issue_type_id IS NULL`) 조회. FR-IS-02 도입 시 명시 전달로 확장.

## 3. 사용자 시나리오 (Given-When-Then)

### S1. 이슈 생성 시 워크플로우 자동 결정 + 시작 상태 정본 적용

- **Given**. 프로젝트 ATLAS가 software-scheme에 자동 배정됨(`WorkflowResolverImpl` EC-1 동작). software-default 워크플로우의 시작 상태는 `open`.
- **When**. `POST /api/v1/issues { projectKey: "ATLAS", summary: "..." }`
- **Then**. 새 이슈가 `currentStateKey="open"`(소문자 정본)으로 생성. 응답 200 + `IssueResponse` 반환. `WorkflowKeyResolver.resolveStart("ATLAS", null)` 1회 호출.

### S2. 이슈 상태 전이

- **Given**. ATLAS-1 이슈가 `currentStateKey="open"` 상태.
- **When**. `POST /api/v1/issues/ATLAS-1/transition { toStateKey: "in_progress" }`
- **Then**. 200 + `currentStateKey="in_progress"` 업데이트. 흐름.
  1. `WorkflowKeyResolver.resolveStart("ATLAS", null)` → `WorkflowStartState("software-default", "open")` 반환
  2. `AppTransitionIssueRequest(workflowKey="software-default", fromStateKey="open", toStateKey="in_progress")` 구성
  3. `WorkflowTransitionPort.applyTransition` 호출 → `TransitionResult.Success(toStateKey="in_progress")`
  4. 이슈 UPDATE + 이벤트 발행 (같은 트랜잭션)

### S3. 새 프로젝트의 자동 scheme 배정 (EC-1)

- **Given**. 새 프로젝트 NEW (`project_workflow_scheme_assignments` 미존재).
- **When**. NEW 프로젝트에서 이슈 생성/전이.
- **Then**. `WorkflowResolverImpl`의 자동 배정 로직(D10) 동작 → software-scheme 자동 배정 → software-default 반환 → 이슈 생성/전이 정상.

### S4. 기존 데이터 마이그레이션 후 호환

- **Given**. 마이그레이션 전 DB에 `currentStateKey="OPEN"` 이슈 다수 존재.
- **When**. Flyway V00X 적용.
- **Then**. 모든 이슈가 `currentStateKey="open"`으로 일괄 변경. 후속 전이 호출 시 워크플로우 상태 키(소문자)와 정합.

## 4. 기능 요구사항 (FR)

- **FR-1**. shared-kernel `com.bts.shared.workflow.WorkflowKeyResolver` 신규 인터페이스 + `WorkflowStartState` VO. `@Transactional(propagation = MANDATORY)`.
- **FR-2**. project-workflow `WorkflowKeyResolverImpl` 신규 구현 (Spring `@Component`). 기존 `WorkflowResolverImpl`/`Workflow` 활용해 workflowKey + 시작 상태 키 추출.
- **FR-3**. `IssueApplicationService.create`가 `WorkflowKeyResolver.resolveStart(projectKey, null)` 호출 → `currentStateKey` = 반환된 startStateKey 사용. `"OPEN"` 하드코딩 제거.
- **FR-4**. `IssueApplicationService.transition`(또는 controller 위임)이 `WorkflowKeyResolver.resolveStart(projectKey, null)` 호출 → workflowKey 사용 → `AppTransitionIssueRequest(workflowKey = result.workflowKey, ...)`. `IssueController.kt:179`의 `"DEFAULT"` 하드코딩 제거.
- **FR-5**. Flyway 마이그레이션 V00X 추가 (issue-tracking) — `UPDATE issues SET current_state_key = LOWER(current_state_key) WHERE current_state_key <> LOWER(current_state_key);`
- **FR-6**. `WorkflowKeyResolver`의 `Propagation.MANDATORY` 정책 — 호출자 트랜잭션 안에서만. 위반 시 `IllegalTransactionStateException`.
- **FR-7**. issue-tracking 모듈의 `build.gradle.kts`가 `:modules:project-workflow` 의존을 가지지 **않는다** (PR #25 정리 상태 유지). shared-kernel만 의존.
- **FR-8**. ArchUnit 룰 신규 — `com.bts.shared..` 패키지가 `com.bts.issue..` / `com.bts.workflow..`를 import하지 않음을 빌드 시점 강제.

## 5. 비기능 요구사항 (NFR)

- **NFR-1. 트랜잭션 일관성**. 이슈 생성. `WorkflowKeyResolver` lookup + 이슈 INSERT + 이벤트 enqueue = 한 트랜잭션. 전이. `WorkflowKeyResolver` lookup + `WorkflowTransitionPort.applyTransition` + 이슈 UPDATE + 이벤트 enqueue = 한 트랜잭션. 부분 실패 시 모두 롤백.
- **NFR-2. 성능**. `WorkflowKeyResolver` lookup은 추가 쿼리 1~2건 (`project_workflow_scheme_assignments` → `workflow_scheme_issue_type_mappings` → `workflows`). 이슈 생성 p95 영향 < 50ms (기존 임계 300ms 여유 충분). 캐시는 본 PR scope 외(WorkflowResolverImpl 내부 캐시 도입은 후속).
- **NFR-3. 회귀**. 기존 `IssueRepositoryTest` / `IssueApplicationServiceTest` / `IssueControllerTest`가 새 케이스(`"open"` 소문자) + `WorkflowKeyResolver` mock 반영하여 모두 green.
- **NFR-4. BC 격리**. issue-tracking 모듈에서 `com.bts.workflow.*` import 0건 (ArchUnit + grep 검증).

## 6. API 인터페이스

**계약 변경 없음**(외부 endpoint/구조 보존). 단 응답 값에 미세 변화.

- `POST /api/v1/issues` — `IssueResponse.currentStateKey` 값이 대문자 `"OPEN"` → 소문자 `"open"`으로 변경 (D6 UI는 읽기전용 표시 — 케이스 비교 의존 없음을 spec 작성 시점에 확인 필요, C-4 참조).
- `POST /api/v1/issues/{key}/transition` — 응답 형식 동일, `currentStateKey` 소문자.
- `GET /api/v1/issues/{key}` — `currentStateKey` 소문자.

## 7. 데이터 모델 변경

- **마이그레이션 V00X__lowercase_current_state_key.sql** (issue-tracking 모듈 db/migration).
- **신규 SPI 인터페이스** (shared-kernel) — `WorkflowKeyResolver`, `WorkflowStartState`. `WorkflowKey` VO는 기존 project-workflow `Workflow.key`(String) 이미 사용 — 본 PR에서는 shared-kernel에 가벼운 `WorkflowKey` type alias 또는 String 직접 사용(spec 단계 미확정, plan에서 결정).
- **신규 구현** (project-workflow) — `WorkflowKeyResolverImpl` (Spring `@Component`).
- **build.gradle 변경** — issue-tracking의 `:modules:project-workflow` 의존 확인(없으면 OK, 있으면 제거). `:modules:shared-kernel`만 잔류.

## 8. 엣지 케이스

- **EC-1. project-workflow scheme 미배정 → 자동 배정**. `WorkflowResolverImpl`가 software-scheme 자동 배정 (PR #18 EC-1, D10 구현). 본 PR은 그 동작 위에서 wiring — 동작 변경 없음.
- **EC-2. issueTypeKey=null + default mapping 부재**. `WorkflowSchemeNoDefaultException` 발생 → `IssueApplicationService`가 도메인 예외(`IssueWorkflowNotConfiguredException` 신규 또는 기존 변환) → `IssueExceptionHandler`가 HTTP 422 (Unprocessable Entity) 응답.
- **EC-3. Propagation.MANDATORY 위반**. 호출자가 `@Transactional` 없이 `WorkflowKeyResolver.resolveStart()` 호출 시 Spring이 `IllegalTransactionStateException`. 단위 테스트로 강제 검증.
- **EC-4. 전이 실패 시 트랜잭션 롤백**. `WorkflowTransitionPort.applyTransition`가 `TransitionResult.Failure` 반환 → 이슈 상태 변경 안 됨 + 이벤트 발행 안 됨. 기존 동작 보존, 회귀 테스트로 검증.
- **EC-5. 케이스 불일치 데이터 (마이그레이션 적용 전)**. 마이그레이션 자체로 해소. 마이그레이션 누락 시 기존 데이터의 `"OPEN"`으로 `applyTransition(fromStateKey="OPEN", ...)` 호출 → 워크플로우의 `open`과 불일치 → `IllegalTransitionException`. 회귀 테스트로 마이그레이션 강제.
- **EC-6. shared-kernel SPI 경계 위반**. ArchUnit 룰로 빌드 시점 차단 (FR-8).
- **EC-7. WorkflowKey 형식 검증**. project-workflow의 `Workflow.key`는 이미 정규식 검증(PR #18). shared-kernel에서 잘못된 키 전파 시 IllegalArgumentException 또는 NoSuchElementException — `WorkflowKeyResolverImpl`이 결과만 전달하므로 새 검증 불필요.
- **EC-8. 동시 이슈 생성 + scheme 자동 배정 race**. 두 요청이 동시에 새 프로젝트의 scheme 자동 배정 트리거 → ON CONFLICT 처리 필요. PR #18 `WorkflowResolverImpl`이 이미 idempotent인지 확인 필요(spec 작성 시점 미검증) — plan 단계에서 검증 task 추가.

## 9. 제약 조건

- **C-1. BC 격리**. issue-tracking은 `com.bts.shared.*`만 import. `com.bts.workflow.*` 직접 import 금지 (ArchUnit + build.gradle 의존 검증).
- **C-2. TDD red→green→refactor**. 단위 + 통합 테스트 (Testcontainers). 마이그레이션은 별도 Flyway 마이그레이션 통합 테스트.
- **C-3. ADR 후속**. `2026-05-27-shared-kernel-extraction`의 published language 패턴 첫 consumer 적용. 결과를 ADR 미해결 항목 (`@Transactional(MANDATORY)` 위치)에 피드백 — ADR 추가 단락 또는 신규 ADR 후보.
- **C-4. 외부 API 호환**. `currentStateKey` 응답 케이스 변경 (대→소)은 D6 UI가 케이스 비교에 의존하지 않음을 plan 단계에서 grep 검증 후 진행. 의존 발견 시 plan 분할 또는 별 slice.

## 10. 측정 가능한 완료 기준

- [ ] shared-kernel에 `WorkflowKeyResolver` SPI 정의 (`com.bts.shared.workflow`)
- [ ] project-workflow가 `WorkflowKeyResolverImpl` 제공 (Spring `@Component`)
- [ ] `IssueApplicationService.create` / `.transition`이 `WorkflowKeyResolver` 호출 — `workflowKey="DEFAULT"` / `currentStateKey="OPEN"` 하드코딩 grep 결과 0건
- [ ] Flyway 마이그레이션 V00X 적용 후 기존 DB의 `OPEN` → `open` (Testcontainers 마이그레이션 테스트 PASS)
- [ ] issue-tracking 모듈 `:modules:project-workflow` 의존 0건 (`build.gradle.kts` grep)
- [ ] ArchUnit 룰 추가 — `com.bts.shared..` → `com.bts.issue..` / `com.bts.workflow..` 미참조
- [ ] 단위 + 통합 테스트 TDD red→green→refactor (각 task `test:` commit이 `feat:` 보다 먼저, controller 직접 git log 검증 — learnings 2026-05-27 C1)
- [ ] backend `./gradlew test` 전체 green (issue-tracking + project-workflow + shared-kernel 모두)
- [ ] 회귀 — 기존 `IssueRepositoryTest` / `IssueApplicationServiceTest` / `IssueControllerTest` 모두 green (케이스 소문자 반영)
- [ ] glossary.md / domain/project-workflow.md stale 정리 task는 본 PR scope 외 별 cleanup slice로 이슈 등록

## Brainstorming Check ✅ 통과 (1회, 자체 외부 시선 검토)

`superpowers:brainstorming` 외부 호출 대신 spec 작성자가 외부 시선으로 자체 sanity check 수행 (스킵 사유는 `/bts-spec` Phase B 우회 사유와 동일 — 명확한 wiring slice + 새 도메인 0). 발견 항목.

- ✅ **EC-5 (마이그레이션 누락 시 케이스 불일치)** — 회귀 테스트로 검증 추가.
- ✅ **EC-8 (scheme 자동 배정 race)** — PR #18 idempotent 확인 task로 plan에 추가 권장.
- ✅ **D2 보조 결정 (시작 상태 조회 방식)** — SPI 시그니처를 `resolveStart` 1개로 통합.
- ✅ **C-4 (응답 케이스 변경의 D6 UI 영향)** — 외부 API 영향으로 명시, plan 단계에서 grep 검증.
- ⚠️ **ADR 미해결 항목 (`@Transactional(MANDATORY)` 위치)** — 본 PR이 첫 consumer라 결정 적용 + ADR 추가 단락 권장 (별 chore 또는 본 PR docs 변경).
- ⚠️ **신규 도메인 예외 `IssueWorkflowNotConfiguredException`** — EC-2 처리를 위해 신규 도입할지, 기존 `IssueDomainException` 하위로 통합할지 plan에서 결정.

게이트1 추가 검토 후보. D2 채택 (a), D3 채택 (a), C-4 (응답 케이스 변경 외부 영향).
