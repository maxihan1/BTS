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

## 도메인 정리

- **BC**. 논리 personalization / 물리 identity-access (FR-PR-01·PF-01·PF-02 선례 유지)
- **영향 엔티티**. UserKeymap (신규) — 사용자별 action→key_combo 오버라이드 집합
- **새 용어** (glossary 추가 후보, Maxi 승인 대기).
  - `action` — 단축키가 실행하는 논리적 동작의 안정 식별자. 5종 화이트리스트(`help`·`create-issue`·`search`·`goto-my-issues`·`goto-dashboard`). 프론트 SHORTCUTS ↔ 백엔드 화이트리스트 SSOT.
  - `key_combo` — action에 배정된 키 시퀀스 정규화 문자열. single(`c`) 또는 leader(`g i`).
  - `keymap 충돌` — 두 action이 같은 key_combo(완전 중복) 또는 single↔leader 접두로 겹침.
- **스코프** (Maxi 결정 2026-07-08). 전역 5종 전부 커스터마이즈(도움말 `?` 포함) + single↔leader 자유 변환.
- **데이터 모델**. `user_keymap(user_id, action, key_combo)` V033, UNIQUE(user_id, action), action CHECK 화이트리스트, override 패턴(행 없으면 프론트 기본값). identity-access V032 최신 → V033(머지 직전 재확인).
- **충돌 검출**. (1) 완전 중복 (2) leader 접두 충돌 + 빈 key_combo 금지. 백엔드 PATCH가 SSOT, 프론트 실시간 복제.
- **정규화 전제**. FR-UX-05 `shortcuts.ts` SHORTCUTS에 안정 action ID 부여 + `resolveKeydown`이 "기본값+override 병합" 키맵 참조하도록 확장(same-BC view-layer).
- **기존 결정 충돌**. 없음. FR-UX-05 ADR이 커스텀 키맵을 명시적으로 FR-PF-03에 위임.
- **관련 ADR**. [docs/decisions/2026-07-08-fr-pf-03-keymap-customize.md](../decisions/2026-07-08-fr-pf-03-keymap-customize.md) (생성됨)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
