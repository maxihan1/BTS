# FR-PR-02 상태 메시지 (이모지 + 텍스트) — 스펙

> slug: fr-pr-02-user-statuses
> 모듈: identity-access (논리 BC: personalization)
> 관련 ADR: [2026-07-06-fr-pr-02-user-status](../decisions/2026-07-06-fr-pr-02-user-status.md)
> **스코프**: 풀스택 D1~D7 (도메인·마이그레이션·API·테스트·프론트 모달/Header·E2E). FR-PR-02는 MinIO 없이 작아 단일 PR(동일 BC). FR-PR-01 선례(백엔드/프론트 분리)와 달리 통합.

## 스코프 경계

| 포함 (본 PR) | 제외 (후속/무관) |
|---|---|
| `UserStatus` 도메인 + `user_statuses` 마이그레이션(V028) | 부재중(FR-PR-03)·환경설정(FR-PF) |
| `GET/PATCH /api/v1/users/me/status` | 상태 프리셋 서버 소유(프론트가 소유) |
| whoami view-layer +상태 노출 | 동료 상태 목록/디렉터리 조회 API(범위 밖 — 본 PR은 본인 설정 + 아바타 옆 자기 표시) |
| 백엔드 단위/통합 테스트(D5) | 상태 변경 알림/이벤트 발행(notification BC) |
| 프론트 상태 설정 모달 + Header 상태 배지(D6) | 이모지 피커 라이브러리 도입(네이티브 입력 + 최근/추천 이모지 상수) |
| Playwright E2E(D7) | |

## 사용자 시나리오 (Given-When-Then)

### S1. 상태 조회 (상태 없음 — 신규 사용자)
- **Given** 인증된 사용자 bob가 `user_statuses` row 없음
- **When** `GET /api/v1/users/me/status`
- **Then** 200 + `{ emoji: null, text: null, expiresAt: null }` (row 없어도 defaults, 강제 생성 안 함)

### S2. 상태 설정 (이모지 + 텍스트 + 만료)
- **Given** alice
- **When** `PATCH /api/v1/users/me/status` body `{ "emoji": "🌴", "text": "휴가 중", "expiresAt": "2026-07-12T15:00:00Z" }`
- **Then** 200 + 저장값 반환. `user_statuses` row lazy upsert. expiresAt 그대로 저장(서버는 프리셋 해석 안 함)

### S3. 이모지만 설정 (텍스트 없음)
- **When** `PATCH` body `{ "emoji": "🌴" }`
- **Then** 200 + `{ emoji: "🌴", text: null, expiresAt: null }`. 이모지 하나만으로 상태 성립(검증 통과)

### S4. 텍스트만 설정 (이모지 없음)
- **When** `PATCH` body `{ "text": "회의 중" }`
- **Then** 200 + `{ emoji: null, text: "회의 중", expiresAt: null }`. 텍스트 하나만으로 상태 성립

### S5. 상태 해제 (둘 다 빈값)
- **Given** alice가 상태 보유
- **When** `PATCH` body `{ "emoji": "", "text": "" }` (또는 `{}`, 또는 `{ "expiresAt": "..." }` 만)
- **Then** 200 + `{ emoji: null, text: null, expiresAt: null }`. row 삭제(clear). expiresAt만 있고 emoji·text 빈값이면 상태 성립 안 함 → 해제

### S6. 만료된 상태 조회 (lazy 필터)
- **Given** alice의 `expires_at`가 과거(`2026-07-01T00:00:00Z`, 현재 2026-07-06)
- **When** `GET /api/v1/users/me/status`
- **Then** 200 + `{ emoji: null, text: null, expiresAt: null }`. 만료 상태는 응답에서 제외(스케줄러 없이 조회 시 필터). 행 물리 삭제는 best-effort(선택)

### S7. whoami에 활성 상태 노출
- **Given** alice가 활성 상태(`🌴`, "휴가 중", 미만료) 보유
- **When** `GET /api/v1/users/me/whoami`
- **Then** 200 응답에 `statusEmoji: "🌴"`, `statusText: "휴가 중"` 포함. 만료/미설정이면 둘 다 null. PAT 분기는 항상 null

### S8. Header 상태 배지 (프론트)
- **Given** 로그인 사용자가 활성 상태 보유
- **When** 앱 헤더 렌더
- **Then** 계정 아바타에 상태 이모지 배지 표시(우하단 오버레이). 텍스트는 title/tooltip. 상태 없으면 배지 없음

### S9. 상태 설정 모달 (프론트)
- **Given** 로그인 사용자가 계정 드롭다운의 "상태 설정" 클릭
- **When** 모달에서 이모지(추천/입력) + 텍스트 + 만료 프리셋(안 지움/30분/1시간/4시간/오늘/이번 주) 선택 후 저장
- **Then** 프론트가 프리셋→`expiresAt`(로컬 기준 절대시각) 변환해 PATCH. 성공 시 whoami 재조회 + store 갱신 → 헤더 배지 즉시 반영(FR-PR-01 D6 Header 연동 선례)

## 기능 요구사항 (FR)

- **FR1** `GET /api/v1/users/me/status` — 현재 사용자의 활성 상태 반환. 미설정/만료면 all-null. row 강제 생성 안 함.
- **FR2** `PATCH /api/v1/users/me/status` — 상태를 **원자적 교체(replace)**. `{ emoji?, text?, expiresAt? }`. emoji·text 정규화(blank→null) 후 둘 다 null이면 **해제(row 삭제)**, 아니면 upsert. 3-state 병합 아님(상태는 통짜 값).
- **FR3** `UserStatus` 도메인 + `UserStatusRepository`(**Spring `NamedParameterJdbcTemplate`** — identity-access 관례, FR-PR-01 선례. jOOQ 아님) — user_statuses upsert/delete/find.
- **FR4** whoami 응답에 `statusEmoji`/`statusText` view-layer 추가(만료 필터 적용). identity-access same-BC view-layer 확장(FR-PR-01 displayName/avatarUrl 선례).
- **FR5** (프론트) 상태 설정 모달 + Header 아바타 상태 배지 + api/mocks/i18n. 프리셋→expiresAt 변환은 프론트 순수함수.
- **FR6** (프론트) 저장 성공 시 whoami 재조회 + authStore 갱신으로 헤더 배지 즉시 반영.

## 비기능 요구사항 (NFR)

- **NFR1** 상태 조회 p95 < 100ms(product §NFR 프로필 조회와 동급, 단일 PK 조회).
- **NFR2** 모든 엔드포인트 인증 필수(Spring Security 필터, `/api` 하위 authenticated). me-scope는 JWT subject 식별(FR-PR-01 `currentUserId` 선례, PAT는 401).
- **NFR3** text 길이 상한 100자(Slack 관례), emoji 길이 상한 32자(단일 이모지+변형선택자 수용, 남용 방지). 초과 400.
- **NFR4** WCAG 2.1 AA(모달 포커스 트랩·라벨, 상태 배지 aria). axe 0 violations.
- **NFR5** expiresAt는 유효 ISO-8601 Instant. 과거 시각이면 400(즉시 만료될 상태 설정은 무의미). 파싱 실패 400.

## API 인터페이스 (REST)

### GET /api/v1/users/me/status → 200
```json
{ "emoji": "🌴", "text": "휴가 중", "expiresAt": "2026-07-12T15:00:00Z" }
```
- 미설정/만료 시 `{ "emoji": null, "text": null, "expiresAt": null }`.

### PATCH /api/v1/users/me/status → 200 (설정 후 활성 상태, GET과 동일 형태)
```json
{ "emoji": "🌴", "text": "휴가 중", "expiresAt": "2026-07-12T15:00:00Z" }
```
- `emoji`: nullable. blank→null 정규화. ≤32자.
- `text`: nullable. blank→null 정규화. ≤100자.
- `expiresAt`: nullable ISO Instant. 과거면 400.
- emoji·text 정규화 결과 둘 다 null → **해제**(200 + all-null). expiresAt 유무 무관.
- absent 필드 = null 취급(replace 시맨틱, 부재≠보존).

### whoami (GET /api/v1/users/me/whoami) 응답 확장
```json
{ "...기존 필드...", "statusEmoji": "🌴", "statusText": "휴가 중" }
```
- 만료/미설정/PAT → 둘 다 null.
- WhoamiController는 이미 `userProfileRepository`를 주입해 avatarUrl을 파생한다(선례). 동일하게 `UserStatusRepository.findActiveByUserId`(만료 필터 SQL)를 주입해 statusEmoji/statusText를 채운다.

## 데이터 모델 변경

신규 마이그레이션 `V028__user_statuses.sql` (identity-access):
```sql
CREATE TABLE user_statuses (
    user_id    UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    emoji      VARCHAR(32),                        -- 이모지 문자열(없으면 NULL)
    text       VARCHAR(100),                       -- 상태 텍스트(없으면 NULL)
    expires_at TIMESTAMPTZ,                         -- 만료 시각(절대, NULL=만료 없음)
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
```
- user_profiles(V027)와 동일한 1:1 확장 테이블 + `ON DELETE CASCADE`.
- `CHECK (emoji IS NOT NULL OR text IS NOT NULL)` — 둘 다 NULL인 무의미 행 방지(해제는 DELETE로 처리하므로 행이 존재하면 최소 하나는 non-null).
- identity-access는 **jOOQ codegen 미사용** → init_codegen.sql 미러 대상 아님. Flyway V028만 추가.
- `updated_at`은 repository가 write마다 `NOW()` 세팅(identity-access 관례, FR-PR-01 선례).
- **V028 번호는 머지 직전 재확인**(동시 브랜치 충돌 회귀 방지, memory).

## 엣지 케이스

- **EC1** row 없는 사용자 GET → all-null(강제 생성 안 함). PATCH(설정)에서만 lazy upsert.
- **EC2** PATCH 빈 body `{}` → 200 all-null(멱등 해제). 이미 없으면 무변경.
- **EC3** emoji/text 공백만(`"   "`) → 정규화 blank→null → 해제.
- **EC4** expiresAt 과거 시각 → 400. 부분 적용 없음(검증 먼저, upsert 안 함).
- **EC5** text 101자 → 400.
- **EC6** 재설정(이미 상태 있음) → upsert로 통짜 교체(이전 emoji/text/expiresAt 잔존 없음 — replace 시맨틱).
- **EC7** 해제 후 재조회 → all-null(멱등). 해제는 DELETE, 없어도 0행 영향으로 정상.
- **EC8** users 하드 삭제 → ON DELETE CASCADE로 user_statuses 자동 정리.
- **EC9** whoami가 만료 상태를 노출하지 않음(활성만) — Header가 만료 상태를 표시하지 않도록 백엔드에서 필터.
- **EC10** PAT 인증으로 me/status 접근 → 401(JWT subject 전용, FR-PR-01 선례). whoami PAT 분기는 statusEmoji/statusText null.

## 제약 조건

- BC 격리: identity-access 내부에서만. 타 모듈 import 금지.
- @Transactional 메서드는 `@Service` 필수(ArchUnit `TransactionalServiceArchTest`).
- DB 접근 = Spring `NamedParameterJdbcTemplate`(identity-access 관례, jOOQ/init_codegen 아님).
- identity-access는 **전역 마이그레이션 카운트 가드가 아니라 테이블별 스키마 테스트** 패턴(`UserProfilesSchemaTest`·`AuthAuditLogsSchemaTest` 등). V028은 전역 카운트를 깨지 않으므로 `UserStatusesSchemaTest`를 `UserProfilesSchemaTest` 미러로 신설(컬럼/제약/CASCADE 검증). 신설 전 전역 카운트 가드 부재 재확인.
- whoami DTO 확장 시 프론트 Zod mock fanout — 신규 필드 `.nullable().optional()` 방어(FR-PR-01 D6 whoami +2 선례, memory).
- MSW mutation은 stateful 오버라이드 영속(memory: msw-mutation-stateful-refetch). 상태 파생동작은 시드 가능 공유 store.
- 프론트 시각 계산: 프리셋→expiresAt는 순수함수 + 사용자 로컬 기준. jsdom/실브라우저 무관하게 테스트.
- 완제품 품질(PoC/임시 코드 금지).

## 측정 가능한 완료 기준

- [ ] `GET/PATCH /api/v1/users/me/status` 구현 + 통합 테스트(설정/해제/만료필터/검증)
- [ ] V028 마이그레이션 + SchemaMigrationTest 카운트 갱신(있으면)
- [ ] whoami view-layer +statusEmoji/statusText + 만료 필터 테스트
- [ ] emoji·text 최소 하나 검증 + 길이/과거 expiresAt 400 테스트
- [ ] 프론트 상태 모달 + Header 배지 + 프리셋→expiresAt 순수함수 단위 테스트
- [ ] Playwright E2E(상태 설정→헤더 배지, 해제, 검증 에러, whoami mock fanout 회귀)
- [ ] ktlint/detekt/ArchUnit + pnpm lint/typecheck/test 통과

## Brainstorming Check

✅ 통과 (1회 iteration, 코드 대조로 gap 3건 발견·보강).

- **F1 (경로 정정)**: whoami 실제 경로는 `/api/v1/users/me/whoami`(스펙 초안의 `/me` 오기). WhoamiController가 이미 `userProfileRepository` 주입 + `avatarUrlFor` 파생 → 상태도 `UserStatusRepository.findActiveByUserId`(만료 필터 SQL) 주입 동일 패턴.
- **F2 (마이그레이션 가드)**: identity-access는 전역 카운트 가드 없음 — 테이블별 스키마 테스트(`UserProfilesSchemaTest` 등). V028은 전역 카운트 무영향 → `UserStatusesSchemaTest` 미러 신설. FR-PR-01의 F6 우려(전역 카운트)는 이 모듈엔 해당 없음.
- **F3 (Avatar 배지)**: `Avatar`에 `AvatarProps` 존재. 상태 배지는 Avatar 확장 대신 Header에서 상대 위치 컨테이너로 오버레이(우하단). Avatar 시그니처 불변 → 폭발 반경 최소.
- **replace vs 3-state 재확인**: 상태는 통짜 값이라 FR-PR-01의 JsonNode 3-state 불필요. StatusPatchRequest는 평이한 nullable 필드(Jackson 부재→null). "부재=보존"이 아니라 "부재=미설정"(replace). 단순화 정당.
