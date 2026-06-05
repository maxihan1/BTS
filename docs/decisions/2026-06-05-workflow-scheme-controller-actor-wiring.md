<!-- 워크플로우 스킴 컨트롤러의 actor를 SecurityContext 인증 주체로 결선하는 방식 결정 -->
# 워크플로우 스킴 컨트롤러 actor 결선 — 프레임워크 중립 추출

> 상태: 채택(Accepted)
> 날짜: 2026-06-05
> 맥락: FR-PM-04 후속(선행 부채 C2), slug `fr-pm-04-actor-wiring`
> 관련: [2026-06-04-workflow-scheme-permission-prod-resolver](2026-06-04-workflow-scheme-permission-prod-resolver.md)

## 배경

FR-PM-04(PR #73)는 워크플로우 스킴 권한 prod resolver를 결선하며, 포트 시그니처를
`actorId: UUID`로 단순화하고 "호출자가 UUID를 추출해 전달"을 명시했다. 그러나
스킴 컨트롤러 2개(`ProjectWorkflowSchemeController`, `WorkflowSchemeController`)는
인증 주체 대신 하드코딩 sentinel actor(`SYSTEM_ACTOR_UUID`)를 넘긴다(actor 호출 8곳).
이 호출자 측 추출 미완성이 선행 부채 C2다.

## 결정

`Authentication`(Spring Security 인증 주체)에서 사용자 UUID를 추출해 actor 자리에
결선한다(**프레임워크 중립 추출**). `authentication.name`은 identity-access JWT 규약상
subject(= 사용자 UUID)와 일치한다. `@WithMockUser`(username = UUID) 테스트와도 정합한다.

## 고려한 대안

- **A. 프레임워크 중립 `Authentication` 추출 (채택)** — 신규 의존성 0, 기존 테스트 무대와
  정합, 미래 JWT 기반 BC 조립과 호환.
- **B. `spring-security-oauth2-resource-server` 추가 + `@AuthenticationPrincipal Jwt`** —
  identity-access와 동형이나, prod 앱도 없는 BC에 의존성 추가 + 기존 테스트는 Jwt 미사용.
- **C. BC 배포 조립 전까지 보류** — 기다릴 조립 작업이 미등재(무기한). 하드코딩 actor
  footgun 방치.

## 아키텍처 제약(이 결정의 근거)

1. project-workflow에 `spring-security-oauth2-resource-server` 부재 → `Jwt` 타입 import 불가.
2. project-workflow에 production `@SpringBootApplication` 부재(테스트용만). JWT 필터/
   SecurityConfig는 identity-access에만 존재.
3. 여러 BC를 한 prod 앱에 조립하는 배포 앱 부재(각 BC Application이 자기 패키지만 스캔).
   FR-IS-07(#62)·FR-WF-03(#66)은 완료됐으나 BC 배포 조립은 미착수.

## 결과

- 하드코딩 actor 코드 부채 해소. 권한 판정기가 가짜가 아닌 실제 인증 주체를 받는다.
- 검증은 **test-assembled**(MockMvc + `@WithMockUser`)로 수행 — BTS 현 표준. 띄울 prod
  앱이 아직 없으므로 "prod 활성화"가 아닌 코드 부채 해소로 framing.
- PAT 경로 정책은 spec에서 확정(프레임워크 중립 추출은 인증된 주체가 무엇이든 읽고,
  주체 부재 시 권한 판정기가 fail-closed).
