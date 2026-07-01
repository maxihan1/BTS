# FR-DB-03 — 대시보드 공유 (URL, 임베드)

> slug: fr-db-03-dashboard-share
> type: feature
> agent: backend-engineer (D2 공개토큰/권한 = security-engineer 공동검토)
> BC: notification-dashboard (`backend/modules/notification`, `com.bts.notification.dashboard`)
> 생성: 2026-07-02

## Brief

FR-DB-03 대시보드 공유 (공유 URL 토큰 + iframe 임베드 + 권한). product 문서 §3.3.

- D1. 도메인 (backend-engineer)
- D2. 명세 — 공유 URL + iframe 임베드 + 권한 (backend-engineer + security-engineer 공동)
- D3. 데이터 모델 — `dashboard_share_tokens` (db-engineer)
- D4. 백엔드 — `POST /api/v1/dashboards/{id}/share` (backend-engineer)
- D5. 백엔드 테스트 (backend-engineer)
- D6. 프론트 UI — 공유 모달 + 임베드 코드 복사 (designer → frontend-engineer)
- D7. E2E (qa-engineer)

**분류 정정 (Maxi 확정 2026-07-02)**. classifier가 "권한/토큰" 키워드로 auth/identity-access 오분류
→ product §3.3 기준 notification-dashboard BC, backend-engineer 주도로 정정.
공개 토큰·권한(D2)만 security-engineer 공동검토.

**선행**. FR-DB-01(대시보드 CRUD·visibility PRIVATE/TEAM·PUBLIC은 FR-DB-03로 미룸) / FR-DB-02(가젯) 완료.

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
