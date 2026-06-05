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

**REFACTOR**. 파일 첫 줄 §6 한국어 헤더(`// 인증 주체를 ActorId로 변환하는 ...`) + KDoc(프레임워크 중립 사유 + ADR 링크) + 401 메시지 상수.

**검증**. `./gradlew :modules:project-workflow:test --tests "*CurrentActorTest"`

### Task 2. WorkflowSchemeController actor 결선 (5곳)

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/scheme/web/WorkflowSchemeController.kt`, `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/scheme/web/WorkflowSchemeControllerTest.kt`]
- depends-on: [1]

**RED**. `WorkflowSchemeControllerTest`
- 기존 mutating 테스트(create/update/delete/addMapping/deleteMapping)에 UUID principal 인증 추가.
  스킴 컨트롤러는 `@PreAuthorize`/method-security가 없어 `@WithMockUser(username=UUID)`만으로
  `SecurityContextHolder`가 채워진다(리뷰 CONCERN — `springSecurity()` configurer는 불필요).
  단위/슬라이스에서 `@WithMockUser` 우선, 필요 시 `with(user(UUID))` 보조.
- 신규: 무인증으로 `POST /api/v1/workflow-schemes` → 401 단언.
- 신규: 인증된 mutating → 권한 판정기에 전달된 actor UUID가 principal UUID와 일치(스파이/캡처).
- 실패 예상: 컨트롤러가 아직 systemActor() 사용 → actor 불일치 / 무인증도 통과.

**GREEN**. `WorkflowSchemeController`
- `systemActor()` 호출 5곳 → `CurrentActor.current()`. **각 메서드 진입 직후(권한 판정기 호출 전)**
  추출(현 위치가 이미 메서드 선두라 순서 OK).
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
- **⚠️ 보안 순서(리뷰 CONCERN-B)**: 현재 `projectLookupPort.findIdByKey`(404)가 actor 추출 **앞**에 있음.
  in-place 치환 금지 — `CurrentActor.current()`를 **메서드 맨 앞(projectLookup 전)**으로 끌어올려
  인증 먼저 강제. 안 그러면 무인증자가 존재하지 않는 프로젝트엔 404, 존재 프로젝트엔 401을 받아
  프로젝트 존재 여부 probe 가능(정보 노출). 테스트: 무인증 + 없는 프로젝트 → 401(404 아님) 단언.
- companion `SYSTEM_ACTOR_UUID` 상수 + "actor 임시 처리" KDoc 단락 + 미사용 import 정리.

**REFACTOR**. KDoc 권한 단락 갱신.

**검증**. `./gradlew :modules:project-workflow:test --tests "*ProjectWorkflowSchemeControllerTest"`

### Task 4. 최종 전수 검증 (비-TDD, 검증 전용)

**메타**.
- agent: `security-engineer`
- files: []  (코드 변경 없음 — 검증 전용)
- depends-on: [2, 3]

**참고(리뷰 CONCERN-A)**. `WorkflowSchemeControllerIntegrationTest`는 컨트롤러/MockMvc를 **경유하지 않고**
`WorkflowSchemeApplicationService`를 직접 호출하는 read-only 테스트라 SecurityContext를 안 탄다.
→ T2/T3 후에도 401 RED 없이 그대로 통과. **인증 보강 대상 아님**(plan 초안의 "통합테스트 RED" 전제 철회).
따라서 Task 4는 TDD 사이클이 아니라 **전수 검증 task**다.

**검증(전수)**.
- 잔존 sentinel grep = 0 (오탐 방지로 **상수명+경로 한정**, 리뷰 CONCERN-C):
  `grep -rn "SYSTEM_ACTOR_UUID_STRING\|SYSTEM_ACTOR_UUID" backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/scheme/web/`
  → 0건. **앱서비스 `WorkflowSchemeApplicationService.SYSTEM_ACTOR`(`...0000`)는 보존 대상이라 별도**.
- `./gradlew :modules:project-workflow:test`(모듈 전체 회귀 0).
- `./gradlew :modules:project-workflow:ktlintMainSourceSetCheck :modules:project-workflow:ktlintTestSourceSetCheck :modules:project-workflow:detekt`.

## Plan 메타

- task 수: 4
- wave 예상: 같은 Gradle 모듈(project-workflow) test 컴파일 단위 공유 → 사실상 직렬. T1 → (T2, T3) → T4(검증 전용). 파일 겹침 0이나 모듈 컴파일이 직렬화 요인(메모리 `bts-plan-wave-gradle-module-compile`). T1~T3만 TDD 사이클, T4는 전수 검증.
- TDD 강제: yes (각 task test→feat 순서)
- 신규 의존성: 0
- 추가 검증: ktlint(Main+Test SourceSetCheck) + detekt. E2E/프론트 없음(UI 부재).

## 리뷰 결과

### code-reviewer ground-truth 리뷰 (2026-06-05)

메모리 `bts-review-plan-autoplan-overkill`대로 autoplan 4종 대신 code-reviewer를 실제 코드와 대조 dispatch.

**결론: BLOCKER 0.** 핵심 설계(프레임워크 중립 `Authentication` 추출, 단일 `CurrentActor`, fail-closed 3종, 생성자 불변, test-assembled 검증)가 코드와 정합. 검증 포인트 1~5 모두 PASS.

- ✅ **1. fail-closed/하네스**: `authentication.name` = JWT subject = 사용자 UUID 확인(`JwtIssuer.kt:80` `.subject(userId)`, PAT도 principal=userId). 스킴 컨트롤러는 `@PreAuthorize` 없어 `@WithMockUser`만으로 `SecurityContextHolder` 채워짐 → `springSecurity()` 불필요(plan 정정 반영).
- ✅ **2. SYSTEM_ACTOR 보존**: `WorkflowSchemeApplicationService.SYSTEM_ACTOR`(`...0000`)는 EC-1 자동배정(`:445`)+`WorkflowResolverImpl:94` 시스템 작업 전용. C2 범위 밖 확인.
- ✅ **3. 영향 테스트**: 무인증 mutating 호출은 `WorkflowSchemeControllerTest`/`ProjectWorkflowSchemeControllerTest` 2개뿐(grep 전수). 누락 없음.
- ✅ **4. 생성자 불변**: actor 출처가 메서드 내부라 생성자/mock arity 변경 불요(`plan-files-constructor-injection` 함정 비해당).
- ✅ **5. test-assembled/예외**: "prod 활성화" 과장 없음. `ResponseStatusException(401)`은 `WorkflowSchemeExceptionHandler`(scheme 패키지 한정, 도메인 예외만)와 무충돌, 기본 resolver가 처리.
- ✅ **ArchUnit/절대규칙**: `com.bts.workflow.web` 배치 안전(기존 `WorkflowController` 거주), `@Transactional`/cross-BC import 룰 비해당. 추측성 코드 없음(PAT 미분기가 오히려 규칙 준수).

**반영한 CONCERN 3건(plan 정정 완료)**.
- **CONCERN-B(보안)** → Task 3 GREEN: actor 추출을 메서드 맨 앞(projectLookup 404 전)으로. 무인증+없는프로젝트 → 401(404 아님) 테스트 추가.
- **CONCERN-A** → Task 4 재정의: 통합테스트는 컨트롤러 미경유(service 직접 호출)라 401 RED 없음 → "인증 보강" 철회, 전수 검증 전용 task로.
- **CONCERN-C** → Task 4 grep을 상수명(`SYSTEM_ACTOR_UUID_STRING`/`SYSTEM_ACTOR_UUID`)+경로(scheme/web) 한정해 앱서비스 보존분 오탐 차단.

BLOCKER 없음 → 게이트 1 진입.
