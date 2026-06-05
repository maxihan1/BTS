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

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
