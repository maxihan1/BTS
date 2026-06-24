# FR-UX-03 개인 알림 보관함 (Inbox) — 백엔드 D1~D5

> slug: fr-ux-03-inbox-backend
> type: feature
> agent: backend-engineer
> primary_bc: notification
> 생성: 2026-06-25

## Brief

**원문**. FR-UX-03 개인 알림 보관함 (Inbox) — notification 모듈에 inbox 읽음/보관 상태 관리 + 조회 화면.

**범위 (Maxi 확정 2026-06-25)**. 백엔드 D1~D5만 본 PR. 프론트 D6/D7(Inbox 페이지 + 카운트 뱃지 + E2E)은 후속 PR. FR-UX-02(즐겨찾기) 선례와 동일 패턴(#184 백엔드 / #185 프론트 분리).

**classify 정정**. classify-task가 'Inbox/조회 화면' 키워드로 `type=ui`로 오판 → 풀스택 feature로 정정, agent=backend-engineer.

**기존 인프라 (선행 발견)**.
- FR-NT-02(인앱 채널, V402)가 `notifications` 테이블을 이미 생성. `read_at` 컬럼 + `payload`(Inbox 딥링크용) + 인덱스 `ix_notifications_recipient (recipient_user_id, created_at DESC)`까지 FR-UX-03을 염두에 두고 미리 마련됨.
- 단, "읽는 조회 API"·"읽음/보관 상태 변경 API"·보관(`archived_at`) 컬럼은 아직 없음.
- 다음 마이그레이션 V번호 = **V407** (머지 직전 재확인 필요 — 동시 브랜치 충돌 주의).

**선행 도메인 쟁점 (→ /bts-domain에서 결정)**.
- product D3 계획은 별도 `inbox_items(user_id, notification_id, read_at, archived_at)` 조인 테이블을 제안. 그러나 `notifications`가 이미 수신자별(recipient_user_id) row로 fanout 저장되어 `read_at`도 보유 → 별도 조인 테이블이 불필요할 수 있음. `notifications`에 `archived_at`만 추가하는 방향 vs 별도 테이블 방향을 도메인 단계에서 확정.

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
