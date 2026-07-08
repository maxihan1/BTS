# FR-PF-03 개인화 단축키 커스터마이즈

> slug: fr-pf-03-keymap-customize
> type: feature
> agent: backend-engineer (주) · db-engineer · frontend-engineer · qa-engineer
> primary_bc: personalization (물리 모듈 identity-access)
> 생성: 2026-07-08

## Brief

FR-PF-03 — 단축키 커스터마이즈. 사용자가 FR-UX-05에서 하드코딩된 전역 단축키(`?`·`c`·`/`·`g i`·`g d`)를 자기 취향대로 재배치(reassign)하고 백엔드(`user_keymap`)에 저장. 충돌 검출 포함.

**classify**. type=qa 오판(E2E 키워드) → product 문서 D1~D7 근거로 feature/backend-engineer 정정. 선례 FR-UX-04·FR-TL-03.

**product 문서 §3.3 D1~D7**.
- D1. 도메인 (backend-engineer)
- D2. 명세 — 충돌 검출 (backend-engineer)
- D3. 데이터 모델 — `user_keymap(action, key_combo)` (db-engineer)
- D4. 백엔드 — `GET/PATCH /api/v1/users/me/keymap` (backend-engineer)
- D5. 백엔드 테스트 (backend-engineer)
- D6. 프론트 UI — 단축키 설정 + 실시간 reassign (designer → frontend-engineer)
- D7. E2E (qa-engineer)

**선행 완료**. FR-UX-05(전역 5종 커스텀 훅 `useKeyboardShortcuts` + `shortcuts.ts` SHORTCUTS 상수), FR-PF-01(user_preferences), FR-PR-01(user_profiles, personalization 첫 백엔드 → identity-access 물리 모듈).

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
