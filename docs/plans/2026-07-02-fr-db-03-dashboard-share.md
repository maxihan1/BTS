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

## 도메인 정리

- **BC**: notification-dashboard (`backend/modules/notification`, `com.bts.notification.dashboard`)
- **영향 엔티티**:
  - Dashboard (Aggregate Root, 기존) — 공유 토큰 발급/취소 진입점 (aggregate 통해서만 변경)
  - DashboardShareToken (**신규** 자식 엔티티) — (id, dashboardId, tokenHash SHA-256, createdBy, expiresAt?, revokedAt?)
  - DashboardVisibility (기존 enum) — **불변** (PRIVATE/TEAM/ORG 유지, PUBLIC 추가 안 함)
- **새 용어**: "공유 토큰"(Dashboard Share Token) — 대시보드를 비로그인 URL로 공유하는 불투명 토큰. Maxi 승인 후 머지 시 glossary 동기화.
- **핵심 도메인 결정 (Maxi 확정 2026-07-02)**:
  - D1. 직교 토큰 모델 (visibility enum 미확장) — 갈림길 1
  - D2. 익명/임베드 뷰 = 정적 가젯(text_widget/link_list)만, 데이터 가젯은 "로그인 필요" 플레이스홀더 — 갈림길 2
- **기존 결정 충돌**: 없음. FR-DB-01 ADR의 "PUBLIC(URL 토큰)은 FR-DB-03 범위"를 본 작업이 구체화.
- **관련 ADR**: [docs/decisions/2026-07-02-fr-db-03-dashboard-share.md](../decisions/2026-07-02-fr-db-03-dashboard-share.md) (생성됨)
- **보안 핵심**: BTS 첫 **비인증(anonymous) 읽기 경로** 도입 — SecurityFilterChain 화이트리스트 + 토큰 검증 + iframe 임베드 응답 한정 X-Frame-Options/CSP 완화. D2·D4는 security-engineer 공동검토.

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
