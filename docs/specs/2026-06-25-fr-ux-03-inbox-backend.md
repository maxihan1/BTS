# FR-UX-03 개인 알림 보관함 (Inbox) — 백엔드 스펙

> 날짜: 2026-06-25
> FR: FR-UX-03 (notification-dashboard BC §5.2)
> 범위: 백엔드 D1~D5 (프론트 D6/D7 후속 PR)
> ADR: docs/decisions/2026-06-25-fr-ux-03-inbox-data-model.md
> Plan: docs/plans/2026-06-25-fr-ux-03-inbox-backend.md

## 배경 / 데이터 모델 (확정)

`notifications` 테이블(FR-NT-02 V402)은 이미 **수신자별 fanout 단건 알림**이며 `read_at`을 보유한다.
FR-UX-03은 별도 `inbox_items` 테이블을 만들지 않고(ADR D1) `notifications`를 확장한다.

**V407 변경**.
- `archived_at TIMESTAMPTZ NULL` 추가 — 보관 시각(NULL=미보관).
- `actor_user_id UUID NULL` 추가 — 알림 발신자(이벤트를 일으킨 actor) userId. 발신자 검색용.
- `NotificationWorker.buildNotification`이 이미 파싱한 `event.actorId`를 `actor_user_id`에 저장(FR-NT-02
  코드 수정, 같은 BC). 기존 행은 NULL(소급 갱신 없음, graceful).
- `init_codegen.sql` 미러 필수(jOOQ codegen).

상태 2축(독립).
- **read**: `read_at` NULL(안읽음) ↔ 비-NULL(읽음).
- **archive**: `archived_at` NULL(미보관) ↔ 비-NULL(보관).

탭 정의.
- **all**: `archived_at IS NULL` (보관 안 된 전체, 읽음 무관).
- **unread**: `read_at IS NULL AND archived_at IS NULL`.
- **archived**: `archived_at IS NOT NULL` (읽음 무관).

조회 채널: **IN_APP 한정**(이메일/Webhook 제외 — 외부 전송 기록은 Inbox에 표시 안 함).

## 사용자 시나리오 (Given-When-Then)

### S1. 받은 알림 목록 조회
- **Given** 사용자 alice에게 IN_APP 알림 30건이 쌓여 있다
- **When** `GET /api/v1/users/me/inbox?tab=all&page=0&size=20`
- **Then** 최신순(created_at DESC) 20건 + 페이지 메타(totalElements=30) 반환. 각 항목에 read/archive 상태 포함

### S2. 안읽음만 보기 + 미읽음 카운트 뱃지
- **Given** alice의 알림 30건 중 7건 미읽음
- **When** `GET .../inbox/unread-count`
- **Then** `{ count: 7 }`. (`tab=unread` 목록은 7건)

### S3. 읽음 처리
- **Given** alice의 미읽음 알림 N(id=x)
- **When** `PATCH .../inbox/x/read` body `{ "read": true }`
- **Then** `read_at`=now 갱신, 미읽음 카운트 6으로 감소. 멱등(이미 읽음이면 재호출에도 204, 시각 보존)

### S4. 보관 처리
- **Given** alice의 알림(id=x)
- **When** `PATCH .../inbox/x/archive` body `{ "archived": true }`
- **Then** `archived_at`=now. all/unread 탭에서 사라지고 archived 탭에 표시

### S5. 일괄 읽음
- **When** `POST .../inbox/read-all` body `{ "ids": null }` (또는 빈 배열)
- **Then** 현재 미읽음 전체를 읽음 처리, `{ updated: 7 }`. body에 `ids` 지정 시 해당 항목만

### S6. 검색 (텍스트 / 발신자 / 기간)
- **When** `GET .../inbox?q=ATLAS-12&senderId=<uuid>&from=2026-06-01T00:00:00Z&to=2026-06-25T23:59:59Z`
- **Then** title ILIKE `%ATLAS-12%` AND actor_user_id=senderId AND created_at BETWEEN from/to AND 본인 AND IN_APP. AND 결합

### S7. 그룹화 (같은 이슈)
- **Given** 이슈 ATLAS-12에 대한 알림 5건(생성/전환/댓글…)
- **When** `GET .../inbox?issueKey=ATLAS-12`
- **Then** 해당 issue_key 알림만 최신순. (시각적 묶음은 프론트 D6 책임 — 서버는 평면 목록 + issueKey 노출)

### S8. 권한 격리
- **Given** bob의 알림(id=y)
- **When** alice가 `PATCH .../inbox/y/read`
- **Then** 404 (본인 알림 아님 — 존재 probe 방지). 미인증은 401

## 기능 요구사항 (FR)

- **FR1**. 본인(recipient_user_id=actor) + IN_APP 알림만 조회/변경. 미인증 401, 타인 리소스 404
- **FR2**. 탭 필터(all/unread/archived) + Spring Data 페이지네이션(page/size, 기본 size=20, 최신순)
- **FR3**. 미읽음 카운트 단독 API(`read_at IS NULL AND archived_at IS NULL`)
- **FR4**. 읽음 토글(read true/false) — 단건. 멱등(true 재호출 시 시각 보존)
- **FR5**. 보관 토글(archived true/false) — 단건. 멱등
- **FR6**. 일괄 읽음 — ids 지정(선택) 또는 null/빈(현재 미읽음 전체). 변경 건수 반환
- **FR7**. 검색 — 텍스트(title ILIKE, 부분일치) + 발신자(actor_user_id 일치) + 기간(created_at BETWEEN). 모두 optional, AND 결합, 탭과 공존. **q의 LIKE 와일드카드(`%`/`_`)는 이스케이프하지 않는다**(UserRepository 사용자검색 선례 동일, 1K·title 단일컬럼이라 영향 미미. injection은 jOOQ 바인드 파라미터로 차단됨 — 와일드카드는 검색 정확도 이슈일 뿐. 엄격 리터럴 검색 필요 시 후속 FR에서 `DSL.escape` 도입). C2 명시 결정 2026-06-25.
- **FR8**. 그룹화 — issueKey 쿼리 파라미터로 특정 이슈 필터. 서버는 평면 목록 + issueKey 노출(묶음 렌더는 프론트)
- **FR9**. 상태 변경은 부분 UPDATE(no-bump) — read_at/archived_at만 갱신, 다른 컬럼 불변
- **FR10**. NotificationWorker가 발신자(event.actorId)를 actor_user_id에 저장(신규 알림부터). 기존 행 NULL graceful

## 비기능 요구사항 (NFR)

- **NFR1**. Inbox 100건 페이지네이션 p95 < 300ms(product 측정표). `ix_notifications_recipient` 활용
- **NFR2**. 미읽음 카운트 빠른 응답 — partial index `(recipient_user_id) WHERE read_at IS NULL AND archived_at IS NULL` 후보(빈번 호출, 뱃지)
- **NFR3**. 보안 — 타인 알림 접근 0(목록 필터 + 단건 본인 검증). actor 추출은 리소스 조회보다 선행(probe 방지)
- **NFR4**. BC 격리 — 발신자/이슈는 식별자(UUID/issue_key 문자열)만, cross-BC FK·import 없음. 발신자 이름 해석 안 함(프론트가 사용자 선택)
- **NFR5**. jOOQ DSL만(SQL 문자열 결합 금지), 모든 repository public 메서드 @Transactional

## API 인터페이스 (REST)

기준 경로 `/api/v1/users/me/inbox`. 인증 `currentActorId()`(UUID, 미인증/비-UUID 401).

### 1) 목록 조회
```
GET /api/v1/users/me/inbox
  ?tab=all|unread|archived        (기본 all)
  &q=<텍스트>                       (optional, title 부분일치)
  &senderId=<uuid>                 (optional, actor_user_id)
  &issueKey=<문자열>                (optional)
  &from=<ISO Instant>              (optional)
  &to=<ISO Instant>                (optional)
  &page=0&size=20                  (@PageableDefault size=20)
→ 200 Page<InboxItemResponse>
```
`InboxItemResponse` = { id(UUID), eventType(wireValue String), issueKey(String?), title(String),
body(String?), actorUserId(UUID?), readAt(Instant?), archivedAt(Instant?), createdAt(Instant) }

### 2) 미읽음 카운트
```
GET /api/v1/users/me/inbox/unread-count
→ 200 DataResponse<{ count: Long }>
```

### 3) 읽음 토글 (단건)
```
PATCH /api/v1/users/me/inbox/{id}/read   body { "read": true|false }
→ 204 No Content   (타인/부재 404)
```
> 구현 확정: service.markRead가 Unit 반환이라 변경 항목을 응답에 싣지 않고 **204 No Content**.
> 토글 PATCH의 흔한 패턴이며, 프론트(D6/D7)는 mutation 후 목록/카운트 invalidate로 재조회한다.

### 4) 보관 토글 (단건)
```
PATCH /api/v1/users/me/inbox/{id}/archive   body { "archived": true|false }
→ 204 No Content   (타인/부재 404)
```

### 5) 일괄 읽음
```
POST /api/v1/users/me/inbox/read-all   body { "ids": [<uuid>...] | null }
→ 200 DataResponse<{ updated: Int }>
```
ids null/빈 → 현재 미읽음 전체. ids 지정 → 본인 소유 + 해당 id만(타인/부재 id는 무시, updated에서 제외)

## 데이터 모델 변경

V407 (`notifications` ALTER):
```sql
ALTER TABLE notifications ADD COLUMN archived_at   TIMESTAMPTZ;
ALTER TABLE notifications ADD COLUMN actor_user_id UUID;
COMMENT ON COLUMN notifications.archived_at   IS '보관 시각 (NULL=미보관). FR-UX-03 Inbox 보관함';
COMMENT ON COLUMN notifications.actor_user_id IS '알림 발신자 userId (NULL=시스템/없음). FR-UX-03 발신자 검색';
CREATE INDEX ix_notifications_recipient_unread
    ON notifications (recipient_user_id)
    WHERE read_at IS NULL AND archived_at IS NULL AND channel = 'IN_APP';
```
+ `init_codegen.sql` 미러. `Notification` Aggregate에 `archivedAt: Instant?` + `actorUserId: UUID?` 필드 추가.

**기존 테스트 파급 (리뷰 발견)**.
- `NotificationRepositoryIntegrationTest.kt`의 `containsExactlyInAnyOrder` 컬럼 단언(12개)에 `archived_at`,
  `actor_user_id`를 추가해야 한다(V407 적용 시 즉시 RED). → Task 1에서 함께 갱신.
- `InAppChannelSenderTest`/`EmailChannelSenderTest`/`EmailChannelSenderIntegrationTest`가 `Notification(...)`을
  positional로 호출 → trailing nullable default라 무변경이나 인지 대상.

## 엣지 케이스

- **EC1**. 부재 id PATCH → 404. 타인 id PATCH → 404(존재 노출 안 함)
- **EC2**. read:true 멱등 — 이미 읽음이면 read_at 시각 보존(덮어쓰지 않음), 204
- **EC3**. 보관된 항목도 읽음 토글 허용(archived 탭에서 읽음 처리 가능). read와 archive 독립
- **EC4**. unarchive(archived:false) → archived_at NULL, all 탭 복귀
- **EC5**. senderId 비-UUID → 400. from/to 비-ISO → 400
- **EC6**. from > to → 빈 결과(400 아님, 빈 페이지 반환)
- **EC7**. q 공백/빈 → 텍스트 필터 미적용. 모든 검색 파라미터 부재 → 탭 기본 동작
- **EC8**. read-all ids=[] 또는 null → 미읽음 전체. 미읽음 0 → updated=0
- **EC9**. size 상한 — Spring 기본 max 또는 명시 cap(100). 과대 size 방어
- **EC10**. EMAIL/WEBHOOK 채널 행은 어떤 경로에서도 조회/카운트/변경 안 됨(IN_APP 필터)
- **EC11**. archived 항목은 unread 탭/미읽음 카운트에서 제외(읽지 않아도)

## 제약 조건

- DATA.md §5 jOOQ DSL만, §6 @Transactional 명시
- BC 격리 — 발신자/이슈 cross-BC FK·import 금지. 식별자 문자열/UUID만
- ArchUnit 룰4(jOOQ=repository), 룰5(@Transactional=Bean) 준수
- 절대 규칙(DEVELOPMENT.md §1) — 완제품 품질, PoC 금지
- 멱등/no-bump UPDATE — 상태 변경 시 read_at/archived_at만, OCC version 등 부수 컬럼 없음

## 측정 가능한 완료 기준

- **도메인 단위 테스트** — Notification.markRead/markUnread/archive/unarchive 전환(copy 불변, 멱등)
- **Repository 통합 테스트(Testcontainers)** — 탭 필터/페이지네이션/검색(텍스트·발신자·기간) AND 결합/
  미읽음 카운트/no-bump UPDATE(본인만)/타인 격리/IN_APP 한정/멱등
- **Controller 슬라이스 테스트** — 401/404/200, 요청 검증(400), 응답 스키마, read-all 건수
- **Worker 회귀 테스트** — buildNotification이 event.actorId를 actor_user_id로 저장
- **스키마 마이그레이션 테스트** — V407 적용 후 컬럼/인덱스 존재(기존 NotificationSchema 패턴 있으면 확장)
- `./gradlew :backend:modules:notification:test` 그린 + ktlint + detekt 클린
