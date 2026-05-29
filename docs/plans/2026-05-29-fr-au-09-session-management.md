# FR-AU-09 마무리 — 내 활성 세션 관리 (목록 조회 + 강제 종료)

> slug: fr-au-09-session-management
> type: auth
> agent: security-engineer
> primary_bc: identity-access
> 생성: 2026-05-29

## Brief

**사용자 원문**.
> FR-AU-09 작업 마무리. 진척 확인 결과 D4(백엔드)·D6(프론트 UI)·D7(E2E)가 "내 활성 세션 관리" 기능으로 통째로 비어 있음.

**요구사항**.
- 백엔드. 활성 세션 목록 조회 API (`GET /api/v1/auth/sessions`) + 특정 세션 강제 종료 API (`DELETE /api/v1/auth/sessions/{sid}`).
- 프론트. 활성 세션 목록 화면 + 각 세션 강제 로그아웃 버튼.
- E2E. 목록 조회 → 강제 종료 → 해당 토큰 차단 확인 시나리오.

**선행 컨텍스트 (진척 확인, 2026-05-29)**.
- `SessionService.revoke(sid)` / `revokeAllOfUser(userId)` / `lookup(sid)` / `markLastSeen(sid)` 이미 구현됨 (`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/session/SessionService.kt`).
- `AuthController` (`/api/v1/auth`) 에 login / logout / refresh 만 존재. 세션 목록·강제종료 엔드포인트 부재.
- `SidRevokeJwtConverter` + `SessionService.lookup` 기반 revoke 검증 필터 이미 동작 (revoke 시 해당 sid JWT 차단). EC-29 Caffeine 5s TTL 캐시.
- 프론트 `apps/web/src/auth/` 에 login/logout mutation + authStore 만 존재. 세션 목록 UI 부재.
- `apps/web/e2e/` 에 login 계열 E2E 만 존재. 세션관리 E2E 부재.

**classify 보정**. classify-task 가 'E2E' 키워드로 qa 오분류 → Maxi 확인 후 auth / security-engineer / identity-access 로 확정 (2026-05-29).

**제약**.
- DEVELOPMENT.md §1.17 — 토큰 localStorage 금지 (sessionStorage 강제).
- 한 PR = 한 BC (identity-access). 단, frontend + same BC view layer 변경은 한 PR 내 허용 (learnings 2026-05-22 옵션 C 패턴).

## 도메인 정리

- **BC**. identity-access (단일 BC, 격리 유지). 단 frontend UI + same BC view layer 변경은 한 PR 내 허용 (learnings 2026-05-22 옵션 C).
- **영향 엔티티**. `Session` (기존, V004) — **신규 엔티티 없음**. 목록 표시에 필요한 필드 (createdAt / lastSeenAt / userAgent / ipAddress / providerId) 이미 보유.
- **신규 서비스 메서드 (백엔드 추가)**. `SessionService.findActiveByUser(userId)` — 사용자의 활성(revoked_at IS NULL AND expires_at > now) 세션 목록 조회. partial index `idx_sessions_user_active` 활용. 강제 종료는 기존 `revoke(sid, reason)` 활용.
- **개념 구분 (중요)**. 본 작업은 **self-service 세션 관리** (사용자 본인이 자기 세션 조회/종료). domain 노트 §미래 작업의 "관리자 화면 — 세션 강제 종료"(admin이 남의 세션 종료)와는 **별개**. 본 PR은 self-service 범위만.
- **권한 경계 (보안 핵심 — IDOR 방지)**. `GET /sessions`는 인증 사용자 본인 세션만 반환. `DELETE /sessions/{sid}`는 해당 sid의 `session.userId == 인증 사용자 id` 검증 후에만 revoke. 남의 sid 종료 시도는 거부 (404 권장 — 존재 여부 노출 방지).
- **결정 1 — 현재 세션 처리**. 목록에서 본인 JWT의 `sid`와 일치하는 세션을 "현재 세션"으로 표시 + 그 세션의 강제종료는 차단 (자기 발 그루기 방지). 현재 세션 종료는 기존 로그아웃 버튼으로. (GitHub/Google UX 패턴, Maxi 확정 2026-05-29)
- **결정 2 — 노출 정보**. Provider / 기기(User-Agent) / IP 주소 / 마지막 활동시각(lastSeenAt) / 로그인 시각(createdAt). `deviceFingerprint`(내부 신뢰 디바이스 판별용)는 응답 DTO에서 제외. (Maxi 확정 2026-05-29)
- **도메인 사실 — revoke 반영 지연**. `SidRevokeJwtConverter`의 EC-29 Caffeine 5s TTL 캐시로 인해, 강제 종료 후 해당 access token이 최대 5초간 유효할 수 있음. E2E 시나리오 (강제종료 → 토큰 차단 확인)는 이 지연을 고려해야 함.
- **기존 결정 충돌**. 없음. `session-pat-schema` ADR의 연장. SidRevokeJwtConverter revoke 흐름과 정합.
- **신규 ADR**. 불필요. 새 아키텍처 결정이 아니라 기존 스키마 위 기능 명세 — 결정 1/2는 spec의 FR/EC로 기록.
- **glossary 추가 후보 (Maxi 승인 영역)**. "활성 세션", "세션 강제 종료" — glossary.md에 세션 용어 부재. 머지 시점에 추가 제안 예정.
- **관련 ADR**. [session-pat-schema](../decisions/2026-05-20-session-pat-schema.md), [jwt-key-rotation-policy](../decisions/2026-05-20-jwt-key-rotation-policy.md)

## 스펙

전체 스펙. [docs/specs/2026-05-29-fr-au-09-session-management.md](../specs/2026-05-29-fr-au-09-session-management.md)

핵심 시나리오 3줄 요약.
- `GET /api/v1/auth/sessions` — 본인 활성 세션 목록 (current 플래그 + 기기/IP/시각). `SessionService.findActiveByUser` wrapper 신규 (repo `findActiveByUserId` 기존).
- `DELETE /api/v1/auth/sessions/{sid}` — 본인 세션 강제 종료 (revoke + refresh chain 무효화). 타인 sid 404(IDOR), 현재 sid 409.
- 프론트 `/settings/sessions` 목록 UI + 강제 로그아웃 버튼(현재 세션 비활성) + E2E (목록→종료→토큰 차단).

API 변경 없음 (sessions V004 + idx_sessions_user_active 기존 활용). 신규 마이그레이션 0.

## Brainstorming Check

✅ 통과 (1회 iteration). gap 4건 (만료필터/refresh무효화/CSRF/감사로그) 전부 기존 logout 선례로 해소 — Maxi 결정 불필요.

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
