# ADR: 인증 감사 로그 영속화 — 단순 테이블 + 전수 emit 배선 (FR-AU-10)

> 날짜: 2026-06-10
> 상태: 채택
> 범위: identity-access BC, FR-AU-10 백엔드 1차 (D1~D5)
> 관련 SDD: §19.9 (감사 로그), §5.14 (파티셔닝/인덱싱), §2.3.3 (보존 정책)

## 맥락

FR-AU-10은 그린필드가 아니다. identity-access 모듈에는 이미 감사 시스템 골격이 존재한다.

- `audit/AuthEventType.kt` — 이벤트 12종 enum
- `audit/AuthAuditLog.kt` — 이벤트 데이터 클래스 (`userId: UUID`, eventType, providerId, ip/userAgent/deviceFingerprint, metadata, createdAt)
- `audit/AuthAuditLogService.kt` — `record()` + `findRecent()` 인터페이스
- `audit/InMemoryAuthAuditLogService.kt` — `@Service` 인메모리 임시 구현 (프로세스 재시작 시 소실). KDoc에 "후속 PR(SDD 19.9): `JdbcAuthAuditLogService` + 테이블 구현" 명시.

선행 FR(FR-AU-05/08/09)들이 "감사 로그 emit은 FR-AU-10 위임"으로 미뤄둔 그 후속이 본 작업이다.

두 가지 결정이 필요했다.

## 결정 1 — 파티셔닝 없는 단순 테이블 (SDD §19.9 일탈)

SDD 내부 모순. §19.9는 "월 단위 파티션, 1년 보존"이라 명시하나, §5.14는 "100만 건 규모에서는 파티셔닝 불필요. 인덱스만으로 충분"이라 정반대로 말한다.

**채택**. 파티셔닝 없는 단순 테이블 + 인덱스 3종.

- BTS는 사내 1,000명 규모. 1년 보존 시에도 감사 로그가 수백만 건을 넘기 어렵다 (§5.14의 "인덱스만으로 충분" 영역).
- "Kafka/OpenSearch 도입 금지 (오버엔지니어링 회피)" 기조와 일관 ([[learnings]] 사전 등록 함정).
- 월 단위 파티셔닝은 1K 규모에서 운영 복잡도(파티션 생성/DROP 스케줄)만 늘리고 이득이 없다.

**일탈 근거 기록**. §19.9의 "월 단위 파티션"을 따르지 않는다. 향후 로그량이 수백만 건을 넘으면 파티셔닝을 재도입할 수 있다 (인덱스/스키마 호환 유지).

**보존 1년 enforcement (§2.3.3)**. 파티션 DROP 대신, `created_at`이 1년 지난 행을 삭제하는 `@Scheduled` 정리 작업으로 구현한다.

## 결정 2 — enum 12종 전수 emit 배선 (이벤트 누락 0)

현재 `record()` 호출은 4종만 존재한다.

- `PAT_USED` — WhoamiController
- `PROJECT_MEMBER_ADDED` / `PROJECT_MEMBER_REMOVED` / `PROJECT_ROLE_CHANGED` — ProjectMembershipService

나머지 8종은 enum에 선언만 되고 emit되지 않는 갭이다.

- `LOGIN_SUCCESS`, `LOGIN_FAILURE` — 로그인 흐름 (Local/LDAP/SSO Provider)
- `LOGOUT`, `LOGOUT_ALL_DEVICES` — 세션 종료 (FR-AU-09)
- `TOKEN_REFRESHED`, `SUSPICIOUS_REFRESH_REPLAY` — Refresh Token rotation/리플레이 탐지
- `USER_PROVISIONED` — 신규 사용자 프로비저닝 (AutoProvisionService)
- `LDAP_UNAVAILABLE` — LDAP 연결 불가

**채택**. enum이 선언한 12종 전부를 emit하도록 배선한다 (D5 "이벤트 누락 0"). 영속 백엔드 교체(`InMemory` → DB)와 함께 8개 갭을 모두 채운다.

**범위 밖 (후속)**. FR-AU-05(로컬 계정 생성/강제 비번변경)·FR-AU-08(계정 연결/해제/재인증)이 위임한 이벤트는 현재 enum에 **타입 자체가 없다**. D1 명세("성공/실패/세션종료/권한변경")의 범위도 벗어난다. enum 확장 + 해당 FR 코드 경로 수술이 필요하므로 별도 후속 PR로 분리한다.

## 결정 3 — 동기 record() 패턴 유지 (기존 패턴 답습)

기존 emit은 인증 흐름 안에서 직접 `service.record()`를 동기 호출한다. 감사 신뢰성("누락 0")을 위해 이 동기 패턴을 유지하고, 영속 구현은 트랜잭션 안에서 INSERT한다. 투기적 async/이벤트 리스너 아키텍처는 도입하지 않는다 (DEVELOPMENT.md §단순성).

## 결과

- 새 용어/엔티티 발명 없음 (도메인 모델은 이미 정립).
- SDD §19.9 일탈(파티셔닝)은 본 ADR로 근거 기록.
- D6 관리자 조회 UI + D7 E2E는 후속 PR.
