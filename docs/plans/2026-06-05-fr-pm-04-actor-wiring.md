# FR-PM-04 후속 — 워크플로우 스킴 컨트롤러 actor 결선

> slug: fr-pm-04-actor-wiring
> type: auth
> agent: security-engineer
> primary_bc: project-workflow
> 생성: 2026-06-05

## Brief

FR-PM-04(워크플로우 스킴 권한 prod resolver, PR #73)의 선행 부채 C2 해소.
현재 워크플로우 스킴 컨트롤러 2개가 인증 주체 대신 하드코딩 system actor를 넘겨,
prod 프로파일에서 전 스킴 API가 fail-closed(403)된다.

- 대상 파일(2개, actor 호출 8곳):
  - `ProjectWorkflowSchemeController.kt` — `ActorId(SYSTEM_ACTOR_UUID)` 2곳
  - `WorkflowSchemeController.kt` — `systemActor()` 6곳
- 해소: SecurityContext의 `Authentication`에서 인증 사용자 UUID 추출 → actor 자리에 결선 (도메인 단계에서 결정 A 확정, 아래 참조).
- 참조 패턴(identity-access): AuthController/PasswordController/WhoamiController/ProjectMemberController.
- 결정 필요(→ spec): PAT 경로 정책 — 스킴 관리 API를 PAT로 허용할지(세션 관리 API는 Jira식 PAT 403).
- 범위: 순수 백엔드, UI 없음.

분류: classifier가 type=ui/frontend로 오판 → Maxi 확정으로 type=auth/security-engineer 정정.

## 도메인 정리

- **BC**: project-workflow (컨트롤러 소재) ↔ identity-access (인증 주체 추출 패턴 참조). SecurityContext 읽기는 Spring Security 프레임워크 인프라(횡단 관심사)라 cross-BC import 아님 → BC 격리 위반 없음.
- **영향 파일**: `ProjectWorkflowSchemeController.kt`(actor 2곳), `WorkflowSchemeController.kt`(actor 6곳).
- **새 용어**: 없음. `actor`/`ActorId`는 glossary 유비쿼터스 용어가 아닌 기술 포트 개념.
- **기존 결정 충돌**: 없음 — 오히려 **완성**. FR-PM-04 ADR(`2026-06-04-workflow-scheme-permission-prod-resolver`)이 "호출자가 UUID를 추출해 전달"을 명시했고, C2가 그 호출자 측 추출을 구현.
- **아키텍처 제약(도메인 단계 발견, Maxi 확정 방향 A)**:
  1. project-workflow build.gradle에 `spring-security-oauth2-resource-server` 부재 → identity-access의 `@AuthenticationPrincipal Jwt` 타입 import 불가.
  2. project-workflow에 production `@SpringBootApplication` 부재(테스트용만). JWT 필터/SecurityConfig는 identity-access에만 존재.
  3. 여러 BC를 한 prod 앱에 조립하는 배포 앱 부재(IdentityAccessApplication=`com.atlas.bts.identity`, IssueTrackingApplication=`com.bts.issue`, 서로 분리 스캔). 메모리 `no-cross-bc-deployment-assembly`. FR-IS-07(#62)·FR-WF-03(#66)은 완료됐으나 BC 배포 조립은 미착수·미등재(무기한).
  4. 기존 스킴/워크플로우 컨트롤러 테스트는 `Jwt`가 아니라 `Authentication`+`@WithMockUser`로 조립(`WorkflowControllerMvcTest`).
- **결정 A(Maxi 확정)**: 프레임워크 중립 추출 — `Authentication`에서 사용자 UUID(`authentication.name` = identity-access JWT subject 규약)를 꺼내 actor 자리에 결선. 신규 의존성 0, 기존 `@WithMockUser` 테스트와 정합, 미래 JWT 기반 BC 조립과도 호환.
- **framing**: "prod 활성화"가 아니라 **하드코딩 actor 코드 부채 해소 + test-assembled 검증**(BTS 현 표준). 띄울 prod 앱이 아직 없으므로 prod 동작 주장 금지.
- **관련 ADR**: [docs/decisions/2026-06-05-workflow-scheme-controller-actor-wiring.md](../decisions/2026-06-05-workflow-scheme-controller-actor-wiring.md) (생성됨)

## 스펙

전체 스펙. [docs/specs/2026-06-05-fr-pm-04-actor-wiring.md](../specs/2026-06-05-fr-pm-04-actor-wiring.md)

핵심 요약.
- actor 생성 7곳(ProjectWorkflowSchemeController 2 + WorkflowSchemeController 5)의 하드코딩 sentinel을 SecurityContext 인증 주체 UUID로 교체.
- 단일 추출 지점(헬퍼/argument resolver) — `Authentication.name`(=JWT subject 규약 UUID)을 ActorId로. drift 차단.
- fail-closed 3종: 익명/null/비-UUID → 401. sentinel fallback 금지.
- PAT 분기 없음(Maxi 확정, 필터 체인 위임). 신규 의존성 0. 검증 test-assembled(@WithMockUser).

## Brainstorming Check

✅ 통과 (직접 기술 스펙 — 메모리 `bts-spec-office-hours-mismatch`. 자체 sanity-check로 fail-closed 3종·PAT 추측성 배제·assignedBy 의미변화 검토 완료)

## Plan

### 설계 요약

- **CurrentActor 헬퍼**(신설, `com.bts.workflow.web`) — `SecurityContextHolder`의 `Authentication`을
  읽어 `ActorId` 반환. 단일 추출 지점. fail-closed: `null`/미인증/`AnonymousAuthenticationToken`/
  `name`이 UUID 아님 → `ResponseStatusException(401)`. 프레임워크 중립(`Jwt` 미사용).
- **호출부 교체 7곳** — `systemActor()`/`ActorId(SYSTEM_ACTOR_UUID)` → `CurrentActor.current()`.
  컨트롤러 **생성자 시그니처 불변**(내부 actor 출처만 변경) → mock arity 변경 없음.
- **보존(범위 밖, 건드리지 말 것)**: `WorkflowSchemeApplicationService.SYSTEM_ACTOR`는 신규 프로젝트
  기본 스킴 자동 배정(EC-1 D10) + `WorkflowResolverImpl`의 **진짜 시스템 작업**용. C2 아님.
- **영향 없는 테스트**: `WorkflowSchemeExceptionHandlerTest`(별도 `/test` 픽스처 컨트롤러 GET만).
- **검증**: test-assembled MockMvc + UUID principal(`springSecurity()` + `with(user(uuid))` 또는
  `.principal(...)`). 기존 무인증 테스트는 인증 추가 필요.

### Task 1. CurrentActor 추출 헬퍼 신설

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/web/CurrentActor.kt`, `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/web/CurrentActorTest.kt`]
- depends-on: []

**RED**. `CurrentActorTest`
- happy: `Authentication`의 name=유효 UUID → `ActorId` 반환(UUID 일치).
- null: `SecurityContextHolder` 비어 있음 → `ResponseStatusException` status=401.
- anonymous: `AnonymousAuthenticationToken` → 401.
- non-UUID: name="alice" → 401.
- 실패 예상: `CurrentActor` 클래스 없음.

**GREEN**. `CurrentActor.current(): ActorId`
- `SecurityContextHolder.getContext().authentication` 읽기.
- null || !isAuthenticated || `AnonymousAuthenticationToken` → `ResponseStatusException(HttpStatus.UNAUTHORIZED)`.
- `UUID.fromString(authentication.name)` 시도, `IllegalArgumentException` → 401.
- `ActorId(authentication.name)` 반환.

**REFACTOR**. KDoc(프레임워크 중립 사유 + ADR 링크) + 401 메시지 상수.

**검증**. `./gradlew :modules:project-workflow:test --tests "*CurrentActorTest"`

### Task 2. WorkflowSchemeController actor 결선 (5곳)

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/scheme/web/WorkflowSchemeController.kt`, `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/scheme/web/WorkflowSchemeControllerTest.kt`]
- depends-on: [1]

**RED**. `WorkflowSchemeControllerTest`
- 기존 mutating 테스트(create/update/delete/addMapping/deleteMapping)에 UUID principal 인증 추가
  (`MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build()` + 요청에 `with(user(UUID))`).
- 신규: 무인증으로 `POST /api/v1/workflow-schemes` → 401 단언.
- 신규: 인증된 mutating → 권한 판정기에 전달된 actor UUID가 principal UUID와 일치(스파이/캡처).
- 실패 예상: 컨트롤러가 아직 systemActor() 사용 → actor 불일치 / 무인증도 통과.

**GREEN**. `WorkflowSchemeController`
- `systemActor()` 호출 5곳 → `CurrentActor.current()`.
- `systemActor()` private 함수 + `SYSTEM_ACTOR_UUID_STRING` 상수 + "ActorId 임시 처리" KDoc 단락 제거.

**REFACTOR**. import 정리, KDoc 권한 단락을 "인증 주체 actor 결선"으로 갱신.

**검증**. `./gradlew :modules:project-workflow:test --tests "*WorkflowSchemeControllerTest"`

### Task 3. ProjectWorkflowSchemeController actor 결선 (2곳)

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/scheme/web/ProjectWorkflowSchemeController.kt`, `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/scheme/web/ProjectWorkflowSchemeControllerTest.kt`]
- depends-on: [1]

**RED**. `ProjectWorkflowSchemeControllerTest`
- 기존 assignScheme(PUT)/getAssignedScheme(GET) 테스트에 UUID principal 인증 추가.
- 신규: 무인증 `PUT /api/v1/projects/ATLAS/workflow-scheme` → 401.
- 신규: 인증된 PUT → actor UUID = principal UUID 단언.
- 실패 예상: ActorId(SYSTEM_ACTOR_UUID) 사용 → 불일치 / 무인증 통과.

**GREEN**. `ProjectWorkflowSchemeController`
- `ActorId(SYSTEM_ACTOR_UUID)` 2곳 → `CurrentActor.current()`.
- companion `SYSTEM_ACTOR_UUID` 상수 + "actor 임시 처리" KDoc 단락 + 미사용 import(`ActorId` 직접 생성 제거 시) 정리.

**REFACTOR**. KDoc 권한 단락 갱신.

**검증**. `./gradlew :modules:project-workflow:test --tests "*ProjectWorkflowSchemeControllerTest"`

### Task 4. 통합테스트 인증 보강 + 최종 검증

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/scheme/web/WorkflowSchemeControllerIntegrationTest.kt`]
- depends-on: [2, 3]

**RED**. T2/T3 후 통합테스트 mutating 호출이 401로 자연 실패(무인증 + AlwaysAllow resolver는 actor 추출 이전에 401).

**GREEN**. mutating 요청에 UUID principal 인증 추가(`springSecurity()` + `with(user(UUID))`). 기존 happy-path 복구.

**검증(전수)**.
- 컨트롤러 `SYSTEM_ACTOR_UUID` grep = 0 (앱서비스 `SYSTEM_ACTOR` 보존 확인 — grep로 잔존 확인).
- `./gradlew :modules:project-workflow:test`(모듈 전체 회귀 0).
- `./gradlew :modules:project-workflow:ktlintMainSourceSetCheck :modules:project-workflow:ktlintTestSourceSetCheck :modules:project-workflow:detekt`.

## Plan 메타

- task 수: 4
- wave 예상: 같은 Gradle 모듈(project-workflow) test 컴파일 단위 공유 → 사실상 직렬. T1 → (T2, T3) → T4. 파일 겹침 0이나 모듈 컴파일이 직렬화 요인(메모리 `bts-plan-wave-gradle-module-compile`).
- TDD 강제: yes (각 task test→feat 순서)
- 신규 의존성: 0
- 추가 검증: ktlint(Main+Test SourceSetCheck) + detekt. E2E/프론트 없음(UI 부재).

## 리뷰 결과 (← /bts-review-plan 채움)
