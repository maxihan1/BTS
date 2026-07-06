# ADR — FR-PR-02 상태 메시지: UserStatus 도메인 + user_statuses 테이블 + TTL 표현

> 날짜: 2026-07-06
> 상태: 결정됨 (Maxi 확정)
> 관련 FR: FR-PR-02 (상태 메시지 — 이모지 + 텍스트)
> 관련 slug: fr-pr-02-user-statuses
> 선행 ADR: [2026-07-05-fr-pr-01-user-profile-placement.md](2026-07-05-fr-pr-01-user-profile-placement.md)

## 맥락

personalization BC(`docs/plan/product/personalization.md §2.2`)의 두 번째 백엔드 FR. Slack 스타일 개인 상태 메시지(이모지 + 텍스트 + 만료). FR-PR-01(프로필)이 확립한 "논리 BC personalization ≠ 물리 모듈 identity-access" + `user_profiles` 미러 패턴을 그대로 계승하되, 다음 두 가지가 이 FR 고유의 미결 결정이었다.

1. **TTL(만료) 표현** — 상태에 유효기간을 주는 방식. 서버 프리셋 enum vs 클라이언트 절대시각.
2. **이모지/텍스트 필수 조건** — 둘 중 무엇이 필수인가.

## 결정

### D1. 모듈 = identity-access (FR-PR-01 계승)

FR-PR-01 ADR D1과 동일 근거. `users`/`/api/v1/users/me/*`가 identity-access에 있고 UserStatus는 User와 1:1이라 같은 모듈이면 cross-BC 우회 없이 JOIN·whoami view-layer 확장이 가능하다. UserProfile과 나란히 `identity/status` 패키지에 둔다.

### D2. 테이블 = user_statuses 신설 (user_profiles 미러)

```
user_statuses (신규, V028)
  user_id     PK/FK → users.id (ON DELETE CASCADE)
  emoji       text NULL      -- 이모지 문자열(예: "🌴"), nullable
  text        text NULL      -- 상태 텍스트, nullable
  expires_at  timestamptz NULL  -- 만료 시각(절대). NULL = 만료 없음
  updated_at  timestamptz NOT NULL DEFAULT NOW()
```

**근거.**
- FR-PR-01 `user_profiles`와 동일한 1:1 확장 테이블 패턴. `JdbcTemplate` + `INSERT ... ON CONFLICT (user_id) DO UPDATE` UPSERT로 lazy 생성 + 멱등성 보장.
- 상태는 통째로 교체/해제되는 원자적 값이라 FR-PR-01의 필드별 3-state 병합(avatar 보존 등)이 불필요하다 — 단일 UPSERT 또는 DELETE로 충분.

### D3. TTL 표현 = 클라이언트 절대시각 (Option A, Maxi 확정)

클라이언트가 `expiresAt`(ISO Instant) 또는 null을 계산해 전송하고, 서버는 받은 값을 그대로 저장한다. "30분/1시간/오늘/이번 주" 같은 프리셋→절대시각 변환은 **프론트 책임**.

```
PATCH /api/v1/users/me/status
{ "emoji": "🌴", "text": "휴가 중", "expiresAt": "2026-07-12T15:00:00Z" }
```

**만료 처리 = 조회 시 lazy 필터.** GET(status/whoami) 시 `expires_at IS NOT NULL AND expires_at < now()`이면 상태 없음으로 취급해 응답에서 제외한다. 스케줄러/배치 삭제를 도입하지 않는다(1K 규모, 만료 상태는 다음 조회에서 자연 소멸). 만료 행의 물리 삭제 여부는 spec에서 확정(현재 결정: 조회 시 best-effort lazy-clear 허용, 필수 아님).

**기각.** 서버 프리셋 enum(clearAfter): 서버가 프리셋 목록·기간을 소유하면 프리셋 추가 시 백엔드 변경이 필요하고, "이번 주 끝" 같은 계산이 사용자 타임존에 의존해 서버가 타임존을 알아야 한다. 절대시각은 프론트가 사용자 로컬 기준으로 계산해 넘기므로 서버가 타임존 무관하게 단순 저장만 한다. (트레이드오프: 클라이언트 시계 오차 가능 — 1K 사내 규모에서 수용.)

### D4. 검증 = 이모지·텍스트 최소 하나 (Maxi 확정)

상태 설정 시 emoji·text 중 **최소 하나가 비어있지 않아야** 한다. 둘 다 빈값/부재면 상태 해제(행 삭제)로 취급한다.

- `🌴`(텍스트 없음) → OK
- `휴가 중`(이모지 없음) → OK
- 둘 다 빈값 → 상태 해제(clear)
- `expiresAt`만 있고 emoji·text 둘 다 빈값 → 상태 해제(만료만으로는 상태 성립 안 함)

emoji/text 길이 상한·이모지 형식 검증 상세는 spec에서 확정(현재 방침: text 길이 상한 두되 이모지는 임의 유니코드 문자열 허용, 서버는 형식을 강제하지 않음).

### D5. whoami view-layer 확장 (FR-PR-01 선례)

D6 product 요구("아바타 옆 상태")를 위해 whoami 응답에 상태를 노출한다. FR-PR-01이 `displayName`/`avatarUrl`을 whoami에 view-layer로 추가한 것과 동일 원칙(same-BC view-layer). 노출 필드(statusEmoji/statusText)와 만료 필터 적용은 spec에서 확정. PAT 분기는 항상 null(봇 컨텍스트).

## 영향

- 신규 마이그레이션: `V028__user_statuses.sql` (identity-access) — **머지 직전 V번호 재확인**(동시 브랜치 충돌 회귀 방지).
- 신규 엔드포인트: `GET/PATCH /api/v1/users/me/status` (+ 필요 시 clear는 PATCH 빈값으로 흡수).
- 신규 도메인: `UserStatus`, `UserStatusService`, `UserStatusRepository`/`JdbcUserStatusRepository`.
- whoami 응답 +상태 필드(view-layer).
- 신규 용어: "상태 메시지 (Status Message)" — glossary 추가 후보(머지 시 Obsidian sync).
