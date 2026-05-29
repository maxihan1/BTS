# FR-IS-01 상태전이 E2E

> slug: fr-is-01-transition-e2e
> type: qa
> agent: qa-engineer
> 생성: 2026-05-29

## Brief

이슈 상태 전이(transition) 플로우의 Playwright E2E 테스트 추가.

- 백엔드 전이 wiring은 PR #27(`WorkflowResolver consumer`)/#28(`transition 매핑 misalign hot-fix`)에서 완료됨. 2026-05-29 코드 검증 완료.
- 기존 E2E는 `issue-crud-happy / issue-not-found / issue-auth-guard / issue-ui-regression` 4개뿐 — transition 시나리오 부재.
- 이번 작업은 메모 `issue-transition-backend-gap`가 말한 "백엔드 wiring 수정 후 후속 slice"에 해당. E2E가 전이의 런타임 end-to-end 동작을 최초로 검증.
- classify: type=qa, agent=qa-engineer.

## 도메인 정리

- **BC**: issue-tracking (주). project-workflow는 `WorkflowKeyResolver` SPI로 소비 (PR #25 공통 SPI 모듈, PR #27 wiring). 새 용어/엔티티 없음 — 기존 모델의 전이 UI + 테스트만 추가.
- **scope 확장** (Maxi 결정 2026-05-29): classify=qa였으나 실제 = **frontend feature(전이 UI) + qa(프론트 E2E + 백엔드 통합 테스트)**. agent: frontend-engineer(UI) + qa-engineer(E2E·통합테스트).
- **전이 실행 계약**: `POST /api/v1/issues/{key}/transition`, body `{ toStatusKey, expectedVersion }` → 200 + 갱신 `IssueResponse`. workflowKey는 서버가 `WorkflowKeyResolver.resolveStart`로 자동 결정. 에러: 404(이슈 없음)·422(워크플로우 미설정)·409(전이 거부/낙관락 충돌).
- **혼동 주의**: `POST /api/v1/workflows/{key}/transitions`(`planTransition`)는 전이 "계획 계산"용 별개 엔드포인트. 이슈 전이 실행이 아님.
- **열린 설계 질문 (→ spec)**: 프론트 전이 UI가 "가용 전이 목록"을 어떻게 얻는가. 현재 `IssueResponse`엔 workflowKey/available transitions 없음. (a) 이슈의 프로젝트 워크플로우 정의를 별도 fetch 후 클라이언트가 currentStateKey 기준 필터 (b) 백엔드가 이슈별 가용 전이를 응답에 포함. spec office-hours에서 결정.
- **현재 UI 상태**: `issues.$key.tsx` + `IssueMetaPanel.tsx`는 상태를 읽기전용 배지(`data-testid="issue-state-badge"`)로만 표시. D6에서 전이 UI 의도적 제외. 이번 작업이 그 후속 slice.
- **새 용어**: 없음. **기존 결정 충돌**: 없음.
- **관련 ADR**: `docs/decisions/2026-05-28-workflow-transition-identity-policy` (transition identity = (fromStateKey, toStateKey), transitionName 불필요).
- **관련 메모**: `issue-transition-backend-gap`(백엔드 갭 PR #27/#28 해결, 본 E2E·통합테스트가 런타임 최종검증), `bts-cross-bc-test-migration`(백엔드 통합테스트는 issue-tracking↔project-workflow 마이그레이션 둘 다 testRuntimeOnly 의존 — 워크플로우 시드 필요), `frontend-zod-backend-dto-contract-gap`(전이 UI Zod 스키마는 실제 backend DTO와 정합 grep 검증 필수).

## 스펙

전체 스펙. [docs/specs/2026-05-29-fr-is-01-transition.md](../specs/2026-05-29-fr-is-01-transition.md)

핵심 결정 (Maxi 2026-05-29).
- 가용전이 = **옵션 A 풀버전** (지라식). 서버가 이슈별로 validator/조건/권한까지 평가해 실행 가능한 전이만 반환.
- scope = shared-kernel SPI 확장 + project-workflow 구현 + issue-tracking 엔드포인트 + 전이 UI(frontend) + 프론트 E2E + 백엔드 Testcontainers 통합 테스트. **cross-BC 3모듈 (Maxi 승인 BC 격리 예외).**
- 전이 실행 엔드포인트(`POST /issues/{key}/transition`)는 기존 — 통합 테스트로 런타임 검증.

핵심 시나리오 3줄.
- 이슈 상세에서 가용 전이를 골라 상태 변경(open→in_progress 등), 배지·가용전이 갱신.
- 가용 전이만 노출 — 서버가 validator 평가 후 권위 있게 큐레이션.
- 잘못된 전이/충돌/미설정은 409·422로 안전 처리.

## Brainstorming Check

✅ 통과. Phase B에서 gap 3건(가용전이 SPI 부재 / 이슈 workflow_key 미영속 / 조건부 전이 미고려) 발견 → Maxi 결정(옵션 A 풀버전)으로 spec 보강. 상세는 spec 파일 ## Brainstorming Check.

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
