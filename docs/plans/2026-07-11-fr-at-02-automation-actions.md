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

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
