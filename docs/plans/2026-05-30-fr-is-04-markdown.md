# FR-IS-04 — 이슈 본문(Markdown) + 우선순위/라벨/환경/영향도

> slug: fr-is-04-markdown
> type: feature
> agent: backend-engineer (+ security-engineer 공동, frontend-engineer, db-engineer, qa-engineer)
> 생성: 2026-05-30

## Brief

FR-IS-04 — 이슈에 본문(Markdown)과 메타 필드(우선순위/라벨/환경/영향도)를 추가한다. BC=issue-tracking. 선행 FR-IS-01(이슈 CRUD) 완료.

분류 결과 — type=feature, agent=backend-engineer, primary_bc=issue-tracking.

문서상 단계(docs/plan/product/issue-tracking.md §2.1.4).
- D1. 도메인 (backend-engineer)
- D2. 명세 — Markdown XSS sanitization (backend-engineer + security-engineer)
- D3. 데이터 모델 — `issues.body` (TEXT) + priority/environment/impact (db-engineer)
- D4. 백엔드 — flexmark 렌더링 + sanitize (backend-engineer + security-engineer)
- D5. 백엔드 테스트 — XSS 페이로드 10종 차단 (backend-engineer + security-engineer)
- D6. 프론트 UI — TipTap Atlas Editor (`issue-body` variant) (designer → frontend-engineer)
- D7. E2E (qa-engineer)

보안 핵심 — 사용자가 입력한 Markdown을 렌더할 때 XSS(악성 스크립트 주입)를 막아야 함. flexmark(Markdown→HTML 렌더 라이브러리) + sanitize 단계 필수. security-engineer 공동 책임.

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
