# FR-AU-09 마무리 — 내 활성 세션 관리 — 스펙

> slug: fr-au-09-session-management
> type: auth | BC: identity-access | agent: security-engineer
> 생성: 2026-05-29

## 개요

사용자가 **자기 계정에 현재 로그인된 세션 목록**을 조회하고, 의심스러운/오래된 세션을 **강제 종료(로그아웃)** 할 수 있는 self-service 기능. FR-AU-09 의 D4(백엔드 잔여) + D6(프론트 UI) + D7(E2E) 를 한 세로 슬라이스로 완성한다.

기존 자산. `SessionService.revoke(sid, reason)` / `revokeAllOfUser` / `SessionRepository.findActiveByUserId(userId)` / `SidRevokeJwtConverter`(revoke 감지 필터) 모두 구현됨. 본 PR 은 그 위에 **REST 엔드포인트 2개 + Service wrapper 1개 + UI + E2E** 를 얹는다.

## 사용자 시나리오 (Given-When-Then)

### S-1. 활성 세션 목록 조회
- **Given** 사용자가 로그인되어 유효한 access JWT 를 보유
- **When** 세션 관리 화면에 진입 (`GET /api/v1/auth/sessions`)
- **Then** 본인의 활성 세션만 목록으로 반환. 현재 사용 중인 세션은 "현재 세션" 으로 표시되고 강제종료 버튼이 비활성

### S-2. 다른 세션 강제 종료
- **Given** 목록에 현재 세션 외 다른 활성 세션이 1개 이상 존재
- **When** 그 세션의 "강제 로그아웃" 버튼 클릭 (`DELETE /api/v1/auth/sessions/{sid}`)
- **Then** 해당 세션 revoke. 목록에서 사라짐. 그 세션의 access JWT 는 (캐시 TTL 경과 후) 차단됨

### S-3. 남의 세션 종료 시도 (IDOR 방어)
- **Given** 공격자가 다른 사용자의 sid 를 추측/탈취
- **When** `DELETE /api/v1/auth/sessions/{타인 sid}` 호출
- **Then** 404 Not Found. 타인 세션은 폐기되지 않음. 세션 존재 여부도 노출하지 않음

### S-4. 현재 세션 강제 종료 시도 차단
- **Given** 사용자가 자기가 지금 쓰는 세션(요청 JWT 의 sid)을 목록에서 강제종료 시도
- **When** `DELETE /api/v1/auth/sessions/{현재 sid}`
- **Then** 409 Conflict + 에러코드 `cannot_revoke_current_session`. 현재 세션 종료는 로그아웃 기능으로 안내

## 기능 요구사항 (FR)

- **FR-1**. `GET /api/v1/auth/sessions` — 인증된 사용자의 활성 세션(revoked_at IS NULL AND expires_at > now) 목록 반환. 정렬: lastSeenAt DESC (최근 활동 우선).
- **FR-2**. 각 세션 응답 항목: `{ sid, providerId, userAgent, ipAddress, lastSeenAt, createdAt, current }`. `current` = 요청 JWT 의 sid 와 일치 여부 (boolean). `deviceFingerprint` 미노출.
- **FR-3**. `DELETE /api/v1/auth/sessions/{sid}` — 본인 소유 활성 세션 강제 종료. 성공 시 204 No Content + **`SessionService.revoke(sid, "user_revoke")` + `RefreshTokenRepository.revokeChainFromSession(sid)` 둘 다 호출** (logout 선례 `AuthController.kt:169-170` 와 동일 — 세션 + 귀속 refresh chain 즉시 무효화).
- **FR-4**. DELETE 시 대상 세션의 `userId != 인증 사용자 id` 또는 세션 미존재 → **404 Not Found** (IDOR 방어, 존재 여부 비노출).
- **FR-5**. DELETE 대상 sid == 요청 JWT 의 현재 sid → **409 Conflict** + 에러코드 `cannot_revoke_current_session` (자기 세션 종료 차단).
- **FR-6**. 두 엔드포인트는 Spring Security 인증 필터 통과 필수. 미인증 요청 → 401.
- **FR-7**. 프론트 — 세션 관리 화면(`/settings/sessions` 라우트). 활성 세션 목록 카드 + 각 항목 강제 로그아웃 버튼. 현재 세션은 배지 표시 + 버튼 비활성.
- **FR-8**. 프론트 — 강제 종료 성공 시 목록 갱신(TanStack Query invalidate) + toast 피드백. access token 은 sessionStorage 만 사용 (localStorage 금지).
- **FR-9**. E2E — S-1(목록 조회) → S-2(강제 종료) → 종료된 세션의 토큰 차단 확인 시나리오. revoke 캐시 TTL(EC-29 5s) 고려.

## 비기능 요구사항 (NFR)

- **NFR-1 (보안)**. IDOR 방어 — 본인 세션만 조회/종료. DELETE 권한 검증은 Controller 가 아니라 Service/조회 단계에서 userId 일치로 강제.
- **NFR-2 (보안)**. 응답 DTO 에 내부 식별자(deviceFingerprint) 미포함. ipAddress/userAgent 는 본인에게만 노출(본인 세션 목록).
- **NFR-3 (정합)**. 응답 DTO 필드는 frontend Zod 스키마와 1:1 정합 (learnings 2026-05-22 — Zod↔DTO drift 차단). spec 의 FR-2 필드 정의가 단일 진실원천.
- **NFR-4 (성능)**. 한 사용자 활성 세션 수는 소규모(≤ 수십). 페이지네이션 불필요. `idx_sessions_user_active` partial index 활용.
- **NFR-5 (트랜잭션)**. 목록 조회는 readOnly 트랜잭션. revoke 는 기존 `@Transactional` 단일 UPDATE 경계 (DATA.md §6).

## API 인터페이스 (REST)

### GET /api/v1/auth/sessions
- 인증. `Authorization: Bearer <access-jwt>` (필수)
- 응답 200.
```json
{
  "sessions": [
    {
      "sid": "uuid",
      "providerId": "local",
      "userAgent": "Mozilla/5.0 ...",
      "ipAddress": "10.0.0.5",
      "lastSeenAt": "2026-05-29T10:00:00Z",
      "createdAt": "2026-05-20T09:00:00Z",
      "current": true
    }
  ]
}
```
- 401 미인증.

### DELETE /api/v1/auth/sessions/{sid}
- 인증. `Authorization: Bearer <access-jwt>` (필수)
- 204 No Content — 강제 종료 성공.
- 404 Not Found — 본인 소유가 아니거나 미존재/비활성 sid (IDOR 방어).
- 409 Conflict — 현재 세션 sid. body `{ "error": "cannot_revoke_current_session" }`.
- 401 미인증.

## 데이터 모델 변경

**없음.** `sessions` 테이블(V004) + `idx_sessions_user_active` 인덱스 기존 활용. 신규 마이그레이션 없음.

## 엣지 케이스 (EC)

- **EC-1**. 활성 세션 0개(예: 토큰만 있고 세션 만료) → 빈 배열 `{ "sessions": [] }` 반환(에러 아님).
- **EC-2**. 이미 revoke 된 sid 를 DELETE → 활성 목록에 없으므로 FR-4 의 404 (본인이라도 비활성은 not found).
- **EC-3**. 현재 세션을 강제종료 시도 → FR-5 의 409 (S-4).
- **EC-4**. 강제종료 직후 그 토큰으로 API 호출 → SidRevokeJwtConverter EC-29 5s 캐시로 최대 5초간 통과 가능. E2E 는 이 윈도우를 명시적으로 다룸(대기 또는 캐시 미적용 sid 확인).
- **EC-5**. 만료(expires_at 경과)됐지만 revoked_at IS NULL 인 세션 → 활성 목록에서 제외(findActiveByUserId 가 expires_at > now 조건 포함하는지 검증 필요. 미포함 시 Service/Repository 보강).
- **EC-6**. userAgent/ipAddress 가 null 인 세션(헤더 부재 로그인) → 응답에 null 그대로. UI 는 "알 수 없는 기기/위치" fallback 표시.
- **EC-7**. 잘못된 UUID 형식의 sid path → 400 Bad Request (Spring 자동 변환 실패).

## 제약 조건

- 한 PR = 한 BC(identity-access). frontend + same BC view layer(DTO) 한 PR 내 허용.
- 토큰 localStorage 금지 → sessionStorage(access) (DEVELOPMENT.md §1.17).
- 인증 없는 엔드포인트 추가 금지 — 두 엔드포인트 모두 Security 필터 통과.
- @Transactional 메서드 보유 클래스 @Service 필수 (ArchUnit 자동 검증).
- revokeReason 코드는 기존 소문자 snake 관례 따름 → `"user_revoke"`.
- **CSRF** — `DELETE /sessions/{sid}` 는 CSRF ignoring 목록에 추가하지 **않음** (logout 선례 동일 — 상태 변경 메서드는 CSRF 토큰 요구). 프론트가 `XSRF-TOKEN` 쿠키 → `X-XSRF-TOKEN` 헤더로 전송. `GET /sessions` 는 안전 메서드라 CSRF 무관.
- **감사 로그 (범위 외)** — 세션 강제종료의 audit emit 은 본 PR 범위 외. 현재 `AuthController` 는 logout 포함 어떤 audit 도 emit 하지 않음 (`AuthEventType` enum 만 정의, 실제 emit 인프라는 FR-AU-10 미착수). 강제종료도 FR-AU-10 에서 일괄 도입. (enum 에 "타 세션 강제종료" 전용 타입 부재도 FR-AU-10 후속 — LOGOUT/LOGOUT_ALL_DEVICES 와 구분 필요.)

## 측정 가능한 완료 기준

1. `GET /api/v1/auth/sessions` 가 본인 활성 세션만, current 플래그 포함해 반환 (통합 테스트).
2. `DELETE /sessions/{sid}` — 본인 다른 세션 204 + revoke 반영 / 타인 세션 404 / 현재 세션 409 (통합 테스트 3 케이스).
3. 프론트 세션 목록 화면이 current 배지 + 강제종료 버튼(현재 세션 비활성) 렌더 (컴포넌트 테스트).
4. E2E — 목록 조회 → 다른 세션 강제종료 → 그 토큰 차단 확인 (1 happy path + IDOR 404 edge).
5. `./gradlew test` + `pnpm verify` 그린. ktlint/detekt/typecheck 통과.

## Brainstorming Check

✅ 통과 (1회 iteration). gap 4건 발견 → 전부 기존 `logout` 선례로 해소 (Maxi 결정 불필요).
- EC-5 만료 세션 필터 — `findActiveByUserId` 가 `now` 바인딩으로 이미 거름.
- Refresh token 동반 무효화 — logout 선례대로 `revoke` + `revokeChainFromSession` 둘 다 호출 (FR-3 보강).
- CSRF — logout 과 동일하게 CSRF 토큰 요구 (ignoring 추가 안 함). 프론트 XSRF 헤더 전송 (제약 조건 보강).
- 감사 로그 — logout 포함 현재 audit emit 부재. 본 PR 범위 외, FR-AU-10 위임 (제약 조건 명시).
