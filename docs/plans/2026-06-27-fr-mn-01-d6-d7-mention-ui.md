# FR-MN-01 D6/D7 — 본문 @멘션 시각 강조 렌더링 + 멘션→Inbox 도착 E2E

> slug: fr-mn-01-d6-d7-mention-ui
> type: ui
> agent: frontend-engineer (D6) + qa-engineer (D7 E2E)
> 생성: 2026-06-27

## Brief

**원문**. "fr-mn-01 d6 d7 진행하자"

FR-MN-01(본문/댓글 @멘션 + 즉시 알림)의 D1~D5(백엔드 발행)는 PR #114로 완료. D6(멘션 렌더링)·D7(Inbox 도착 E2E)은 당시 "알림 전달(FR-NT)·Inbox(FR-UX-03) 인프라 부재"로 deferred됐다. 그 선행 FR이 모두 완료되어 이제 진행 가능.

**핵심 발견 (코드 확인, 2026-06-27)**. 백엔드 멘션→알림→Inbox 파이프라인은 **이미 완성**.
- `NotificationWorker.kt:357` — `ISSUE_MENTIONED` → "…에서 멘션되었습니다" 알림 생성
- `EventRecipientResolver.kt:147` — `RecipientRole.MENTIONED` → `mentionedUserIds`를 수신자로 해석
- `V403__seed_mention_policy.sql` — `issue.mentioned` MENTIONED×IN_APP 정책 시드 (FR-NT-02에서 추가)
- Inbox는 notifications 테이블 확장 (FR-UX-03, #186/#187)

→ **순수 프론트엔드 작업**. FR-MN-02 자동완성(#158)과 동일하게 백엔드 신규 0.

**범위**.
- D6. 본문(description) 렌더링 시 `@username` 시각 강조 (frontend-engineer)
- D7. "본문에 나를 @멘션 → Inbox에 알림 도착" E2E (qa-engineer)

**classify**. 원래 type=qa·agent=qa-engineer로 오판(E2E/Inbox 키워드) → FR-MN-02 선례대로 type=ui·agent=frontend-engineer 수동 조정.

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
