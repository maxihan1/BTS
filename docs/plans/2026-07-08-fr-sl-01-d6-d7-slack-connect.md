# FR-SL-01 D6 프론트 + D7 E2E — 관리자 Slack 연결 페이지

> slug: fr-sl-01-d6-d7-slack-connect
> type: feature (classify=qa 오판 교정)
> agents: frontend-engineer(UI) · security-engineer(admin-gated backend view-layer) · qa-engineer(E2E)
> 생성: 2026-07-08

## Brief

FR-SL-01 백엔드 코어(D1~D5, PR #244)의 후속. D6 프론트 + D7 E2E.

- **D6 프론트**. `/settings/slack` 시스템 관리자 전용 페이지 — 현재 연결 상태 표시(연결됨/미연결) + 연결/다시연결 버튼 + 콜백 결과 배너(`?installed=` 성공 / `?error=<code>` 실패).
- **D6 백엔드(같은 BC view-layer)**. Bearer 인증 제약(전체 페이지 이동은 Authorization 헤더 미전송)으로 관리자 가드 JSON 엔드포인트 2종 필요.
  - `GET /api/v1/slack/installation` — 연결 상태 `{ connected, teamName?, teamId?, botUserId?, installedAt? }` (users 조인 없음, 설치자 이름 제외).
  - `GET /api/v1/slack/install-url` — 신선한 서명 state 실은 authorize URL `{ url }`.
  - 둘 다 SystemPermissionResolver 관리자 가드.
- **D7 E2E**. MSW 기반 — 관리자 접근/비관리자 게이팅, 연결됨/미연결 상태, `?installed`/`?error` 배너.

**Maxi 확정**. A1(상태 조회 + 연결). 제외(후속): disconnect/revoke(하드삭제 ADR 필요), 설치자 이름 표시(cross-BC users 조인).

**제약/근거**.
- Access token = Bearer 헤더(메모리 store), refresh = HttpOnly 쿠키. 전체 페이지 nav는 Bearer 미전송 → `/slack/install`(SecurityContext 관리자 판정) 직접 nav 시 401. 그래서 authorize URL을 apiFetch로 받아 `window.location.href` 이동.
- 기존 `SlackInstallController`(`/slack/install` 302, `/slack/install/callback` 302)는 그대로 유지(배포 조립/쿠키 경로용). D6은 `/api/v1/slack/*` JSON 경로 신설.
- BC 격리 — identity-access import 0, SystemPermissionResolver 포트만 소비.

## 도메인 정리

- **BC**: slack-integration (기존, FR-SL-01 백엔드 코어로 확립).
- **영향 엔티티**: `SlackInstall`(기존). 신규 엔티티/마이그레이션 **없음** — view-layer 조회 read + UI만.
- **새 용어**: 없음. "연결 상태"·"다시 연결"은 UI 라벨, 도메인 용어 아님. glossary 변경 불필요.
- **기존 결정 충돌**: 없음. 기존 302 `/slack/install`·`/slack/install/callback` 유지 + SPA용 JSON 경로 신설(병존).
- **핵심 결정**: Bearer 인증 제약(전체 페이지 nav는 Authorization 헤더 미전송) → 관리자 가드 JSON 엔드포인트 `GET /api/v1/slack/{installation,install-url}` 신설. authorize URL을 apiFetch로 받아 nav.
- **관련 ADR**: [docs/decisions/2026-07-08-fr-sl-01-d6-slack-connect-page.md](../decisions/2026-07-08-fr-sl-01-d6-slack-connect-page.md) (생성됨) · 선행 [2026-07-07-fr-sl-01-slack-bot-app.md](../decisions/2026-07-07-fr-sl-01-slack-bot-app.md).
- **도메인 리뷰 방식**: 전면 grill-with-docs 스킵. 이미 확립된 BC의 view-layer 후속이라 신규 용어/엔티티 0 — focused 점검(ADR·glossary·domain 노트 대조)으로 대체.

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
