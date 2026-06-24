# ADR: FR-UX-03 개인 알림 보관함 (Inbox) — 데이터 모델

> 날짜: 2026-06-25
> 상태: 채택 (Accepted)
> 관련 FR: FR-UX-03 (notification-dashboard BC §5.2)
> Plan: docs/plans/2026-06-25-fr-ux-03-inbox-backend.md

## 맥락 (Context)

FR-UX-03은 사용자가 받은 인앱 알림을 모아 보는 "받은 편지함(Inbox)"이다. 수신함/안읽음/
보관함 탭, 읽음·보관 상태 변경, 미읽음 카운트 뱃지, 그리고 SDD 9.2가 명세한 그룹화(같은
이슈의 여러 이벤트 묶음)·검색(텍스트/발신자/기간)을 제공한다.

본 PR은 백엔드 D1~D5(도메인·명세·데이터 모델·API·테스트)만 담당하고, 프론트 D6/D7(Inbox
페이지·카운트 뱃지·E2E)은 후속 PR로 분리한다(Maxi 확정 2026-06-25, FR-UX-02 선례).

기존 인프라. FR-NT-02(인앱 채널, V402)가 `notifications` 테이블을 이미 생성했고, FR-UX-03을
염두에 두고 `read_at`(읽은 시각), `payload`(Inbox 딥링크용 JSONB), 인덱스
`ix_notifications_recipient (recipient_user_id, created_at DESC)`까지 미리 마련해 두었다.
`Notification` Aggregate도 `readAt` 필드를 이미 보유하며, `NotificationRepository`에는
`findByRecipient(recipientUserId)` 조회가 존재한다.

product 명세(D3)는 별도 `inbox_items(user_id, notification_id, read_at, archived_at)` 조인
테이블을 제안했다. 이 설계는 "notification = 이벤트 단위 1행, inbox_items = 수신자별 읽음
상태"라는 가정에서 나온 것이다.

## 결정 (Decision)

### D1. 데이터 모델 — `notifications` 테이블 확장 (별도 inbox_items 폐기, Maxi 확정 2026-06-25)

`notifications`는 이미 **수신자별로 fanout된 단건 알림**이다(한 이벤트 → N 수신자 → N행, 각 행이
`recipient_user_id` + 자기 `read_at` 보유). 따라서 `inbox_items` 조인 테이블을 만들면
`user_id`+`notification_id`가 1:1로 묶이는 순수 중복 구조가 된다.

→ 별도 테이블을 만들지 않고 `notifications`에 **`archived_at TIMESTAMPTZ NULL` 컬럼만 추가**한다
(V407). 읽음 상태는 기존 `read_at`, 보관 상태는 신규 `archived_at`으로 표현한다. 두 컬럼 모두
NULL=미적용, 비-NULL=적용 시각.

product D3의 "별도 inbox_items 테이블" 표기는 본 결정으로 **무효화**되며, 같은 PR에서
notification-dashboard.md §5.2 D3 본문을 "notifications 확장"으로 전수 동기화한다.

### D2. 상태 모델 — read / archive 2축 독립

- **read**: `read_at` NULL(안읽음) ↔ 비-NULL(읽음). 읽음 토글은 단건 + 일괄(전체 읽음).
- **archive**: `archived_at` NULL(보관 안 됨) ↔ 비-NULL(보관됨). 보관은 단건.
- 탭 = 두 축의 조합. **전체**(archived_at IS NULL) / **안읽음**(read_at IS NULL AND archived_at
  IS NULL) / **보관함**(archived_at IS NOT NULL). 보관함은 읽음 여부와 무관하게 표시.
- 도메인 전이는 `Notification` Aggregate에 `markRead(at)` / `markUnread()` / `archive(at)` /
  `unarchive()` 같은 copy 기반 메서드로 추가한다(기존 불변 data class 패턴 유지). 단, 실제
  상태 변경 영속은 repository의 부분 UPDATE(no-bump, read_at/archived_at만)로 처리한다.

### D3. 기능 범위 — SDD 9.2 고급 포함 (Maxi 확정 2026-06-25)

코어(탭 조회·페이지네이션·미읽음 카운트·읽음/보관 변경)에 더해 SDD 9.2의 다음을 포함한다.
- **그룹화**: 같은 `issue_key`의 여러 알림을 묶어 표시(서버는 issue_key 기준 그룹 메타 제공,
  최종 묶음 렌더는 프론트). 그룹화 키·응답 형태는 spec 단계에서 확정.
- **검색**: 텍스트(title/body) · 발신자(이벤트 주체) · 기간(created_at 범위) 필터. 발신자의
  진실 출처(payload 내 actor vs event_type)와 텍스트 검색 방식(LIKE vs FTS)은 spec 단계에서
  확정. 1K 사용자 규모라 PostgreSQL 기본 수단 우선(Kafka/OpenSearch 금지, SDD 03 결정).
- **일괄 읽음**: 다중 선택 또는 "전체 읽음" — 둘 중 형태는 spec에서 확정.

### D4. 조회 경로 — recipient_user_id + 인앱 한정

Inbox는 본인(`recipient_user_id = 현재 actor`) 알림만 조회한다(401/권한). 채널은 **IN_APP만**
대상으로 한다(이메일/Webhook은 외부 전송 기록이라 Inbox에 표시하지 않음). 기존 인덱스
`ix_notifications_recipient`가 최신순 조회를 커버하며, 탭/검색 푸시다운에 필요한 추가 인덱스는
spec/plan에서 확정.

## 결과 (Consequences)

- 신규 마이그레이션 **V407** (`notifications` ALTER ADD `archived_at`) — notification 모듈
  `db/migration/notification/`, + `init_codegen.sql` 미러 필수(jOOQ 코드 생성). 머지 직전 V번호
  재확인(동시 브랜치 충돌 주의).
- `Notification` Aggregate에 `archivedAt` 필드 추가 → `NotificationRepository.toDomain`/insert,
  `markSent` 등 기존 매핑·테스트 파급. 기존 `findByRecipient`도 신규 컬럼 매핑.
- product **notification-dashboard.md §5.2 D3** 표기를 "별도 inbox_items 테이블" →
  "notifications 확장"으로 동기화(본 ADR 인용). 같은 PR.
- API 형태(`GET /api/v1/users/me/inbox` 탭·검색·페이지네이션 쿼리, 미읽음 카운트, 읽음/보관
  변경 PATCH)의 요청/응답 스키마·일괄 처리·검색 파라미터는 spec 단계에서 확정.
- glossary 신규 용어 후보: 보관함(Inbox), 읽음/안읽음(read/unread), 보관(archive). Maxi 승인 후
  머지 단계에서 glossary/domain 노트 동기화.
- 기존 결정 충돌: 없음. FR-NT-02 V402의 `read_at` 설계 의도와 일관(오히려 그 토대를 완성).
