# FR-SL-05 인터랙티브 메시지 (버튼/메뉴)

> slug: fr-sl-05-interactive
> type: feature
> agent: backend-engineer (+ security-engineer D4)
> primary_bc: slack-integration
> 생성: 2026-07-12

## Brief

**사용자 원문**. "fr-sl-05 진행하자" — FR-SL-05 인터랙티브 (Slack 메시지 버튼/셀렉트 등 인터랙티브 컴포넌트 처리).

**classify 교정** (진실 출처 = 이 plan, cache 아님 — 멀티세션 충돌로 cache 신뢰 불가).
- classify 원 판정. `type=ui / agent=frontend-engineer / primary_bc=issue-tracking` — "컴포넌트" 단어를 React UI로 오판.
- 교정. `type=feature / agent=backend-engineer (+security-engineer D4) / primary_bc=slack-integration`.
- 근거. `docs/plan/product/slack-integration.md §3.3` D1~D7 전부 backend/db/security/qa 책임, **D6 프론트 UI = 해당 없음**. Slack Block Kit 버튼은 Slack이 렌더 → React UI 없음. FR-SL-03 D6에 동일 오분류 교정 선례 기록됨.

**스코프 요지** (SDD 9.3.5 + product §3.3).
- 알림 메시지(Slack DM)에 액션 버튼 포함. 예. `[상세보기] [완료로 표시] [코멘트 추가]`.
- 사용자가 Slack에서 버튼 클릭 → Slack이 Interactivity Request URL로 `block_actions` payload POST.
- 백엔드. 서명검증 → payload 파싱 → Slack 사용자 → BTS 사용자 해석 → 권한 가드 → 액션 수행(상태 전이/담당자 변경/댓글) → Slack 메시지 갱신(response_url / chat.update).
- 선행. FR-SL-04(§3.2). 인바운드 서명검증기·ack200+@Async 패턴 재사용.

**D 단계 (product §3.3)**.
- D1 도메인 — InteractiveAction (backend-engineer)
- D2 명세 — 상태 전이, 담당자 변경 등 (backend-engineer)
- D3 데이터 모델 — (활용) (db-engineer)
- D4 백엔드 — block_actions handler + 권한 가드 + 응답 갱신 (backend-engineer + security-engineer)
- D5 백엔드 테스트 (backend-engineer)
- D6 프론트 UI — (해당 없음)
- D7 E2E (qa-engineer)

**멀티세션 주의**. 동시 세션 = FR-AT-02 D6 (automation 프론트, `.worktrees/fr-at-02-d6-d7-ui`). 모듈·레이어 분리로 충돌 위험 낮으나, 머지 직전 Flyway V번호 + git 브랜치 재확인 필수.

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
