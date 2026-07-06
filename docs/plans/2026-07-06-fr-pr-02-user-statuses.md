# FR-PR-02 상태 메시지 (이모지 + 텍스트)

> slug: fr-pr-02-user-statuses
> type: feature
> agent: backend-engineer (+ db-engineer, frontend-engineer, qa-engineer)
> BC: personalization (물리 모듈: identity-access)
> 생성: 2026-07-06

## Brief

FR-PR-02 — 사용자 상태 메시지(이모지 + 텍스트). Slack 스타일 개인 상태 표시.

- D1. 도메인 — UserStatus
- D2. 명세 — TTL(만료) 옵션
- D3. 데이터 모델 — `user_statuses(user_id, emoji, text, expires_at)`
- D4. 백엔드 — `PATCH /api/v1/users/me/status`
- D5. 백엔드 테스트
- D6. 프론트 UI — 아바타 옆 상태 + 설정 모달
- D7. E2E

선행: §2.1 FR-PR-01(프로필) 완료. classify E2E 키워드로 qa 오판 → feature 수동 정정.

## 도메인 정리

- **BC**: personalization (논리) / identity-access (물리 모듈) — FR-PR-01 ADR D1 계승
- **영향 엔티티**: `UserStatus` (신규, User와 1:1)
- **새 용어**: "상태 메시지 (Status Message)" — 이모지 + 텍스트 + 만료로 구성된 개인 상태 (Slack 스타일). glossary 추가 후보(머지 시 sync)
- **데이터 모델**: `user_statuses(user_id PK/FK, emoji NULL, text NULL, expires_at NULL, updated_at)` — user_profiles 미러, ON CONFLICT UPSERT
- **핵심 결정 (Maxi 확정)**:
  - TTL = **클라이언트 절대시각**(`expiresAt` ISO Instant/null 그대로 저장, 프론트가 프리셋→시각 변환). 만료는 조회 시 lazy 필터.
  - 검증 = 이모지·텍스트 **최소 하나**. 둘 다 빈값 → 상태 해제(원자적 replace 시맨틱, 3-state 병합 아님).
  - whoami view-layer +상태 노출(아바타 옆 표시, FR-PR-01 선례).
- **기존 결정 충돌**: 없음. FR-PR-01 패턴 계승.
- **관련 ADR**: [docs/decisions/2026-07-06-fr-pr-02-user-status.md](../decisions/2026-07-06-fr-pr-02-user-status.md) (생성됨)
- **회귀 주의**: V028 마이그레이션 번호 머지 직전 재확인(동시 브랜치 충돌). whoami mock fanout(`.nullable().optional()`, FR-PR-01 D6 선례).

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
