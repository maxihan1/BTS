# FR-AT-02 — 자동화 액션 (필드 변경/담당자/댓글/API 호출)

> slug: fr-at-02
> type: backend
> agent: backend-engineer (+ security-engineer, db-engineer, designer/frontend-engineer, qa-engineer)
> primary_bc: automation
> 생성: 2026-07-11

## Brief

사용자 원문: "fr-at-02 진행해줘"

FR-AT-02 (automation §2.2) — 자동화 규칙의 **액션(Action)** 실행 엔진.
FR-AT-01(트리거)이 매칭된 규칙을 `q_automation_execution` 큐에 enqueue → FR-AT-02가
consumer로 dequeue 후 4종 액션 실행.

- D1. 도메인 — Action 다형성 (backend-engineer)
- D2. 명세 — 4종 액션(필드 변경/담당자/댓글/API 호출) + 권한 가드 (backend + security-engineer)
- D3. 데이터 모델 — automation_actions(action_type, config) (db-engineer)
- D4. 백엔드 — Action executor + dry-run 모드 (backend-engineer)
- D5. 백엔드 테스트 — 권한 부족 시 reject (backend + security-engineer)
- D6. 프론트 UI — 액션 빌더 (designer → frontend-engineer)
- D7. E2E (qa-engineer)

SDD 참조: 08장 (자동화 엔진). 선행: FR-AT-01(완료, PR #251/#254).

## 도메인 정리

- **BC**: automation (BTS 9번째 모듈, `com.bts.automation`, JdbcTemplate, test-boot)
- **영향 엔티티**:
  - `AutomationRule`(기존, 확장) — 트리거 전용 → **액션 리스트 보유**로 확장
  - `Action`(신규, sealed 4종) — `SetFieldAction`/`AssignAction`/`AddCommentAction`/`CallWebhookAction`
  - `ActionType`(신규 enum 4종)
- **신규 shared-kernel 포트**: `IssueMutationPort`(가칭) — issue-tracking 변경 위임(동기).
  `IssueTransitionPort` 선례 동형. `OutboundUrlValidator`(기존 SSRF 가드) CallWebhook 재사용.
- **새 용어(glossary 후보, Maxi 승인 대기)**:
  - 액션(Action) — 이미 존재, 4종 구체화
  - dry-run 모드 — 실제 커밋 없이 "무엇이 바뀔지 + 권한 통과"만 계산
  - rule actor(룰 액터) — 액션을 실행하는 권한 주체 = 룰 생성자(`created_by`)
  - 실행 체인 깊이(execution depth) — 액션→이벤트→재발화 무한루프 차단(10 제한)
- **Maxi 확정 3결정** (ADR D2/D3/D6):
  1. cross-BC 실행 = **동기 커맨드 포트**(shared-kernel), 비동기 이벤트 큐 기각
  2. 실행 권한 = **룰 생성자(rule actor)**, fail-closed, 트리거유발자/시스템액터 기각
  3. 이번 PR = **백엔드 코어 D1~D5**, UI(D6)/E2E(D7)는 후속 PR
- **기존 결정 충돌**: 없음 (FR-AT-01 ADR D4 이음선을 그대로 소비)
- **관련 ADR**: [docs/decisions/2026-07-11-fr-at-02-automation-actions.md](../decisions/2026-07-11-fr-at-02-automation-actions.md) (생성됨)

## 스펙

전체 스펙. [docs/specs/2026-07-11-fr-at-02-automation-actions.md](../specs/2026-07-11-fr-at-02-automation-actions.md)

핵심 시나리오 3줄 요약.
- executor 워커가 `q_automation_execution`(FR-AT-01 이음선) 첫 소비자로서 룰의 액션 리스트를 순차 실행
- 이슈 변경 3종(SetField/Assign/AddComment)은 shared-kernel `IssueMutationPort`(동기)로 위임,
  외부호출 1종(CallWebhook)은 기존 `OutboundUrlValidator`(SSRF) 재사용
- 권한은 **선택 가능한 rule actor**(기본 created_by)로 fail-closed 강제 + dry-run + 체인 상한

핵심 결정(ADR + Maxi 확정).
- cross-BC 실행 = 동기 커맨드 포트 / rule actor = 선택형(지라 Actor) / 댓글 작성자 = rule actor
- AddComment 템플릿 변수 `{{ var }}` 포함 / 무한루프 = 런타임 상한 + FR-AT-04 위임 / 이번 PR = D1~D5

## Brainstorming Check

✅ 통과 (Phase B adversarial 검토 — gap 4건 발견 후 해소).
- Gap A 댓글 작성자 → 선택 가능한 rule actor(지라 모델), D3 정제
- Gap B 템플릿 변수 → 포함(FR10 신설)
- Gap C 무한루프(왕복 시 깊이 리셋) → 런타임 상한 + FR-AT-04 위임(FR8 정제)
- Gap D at-least-once 중복 → best-effort 수용, dedup 은 FR-AT-05 위임(EC9)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
