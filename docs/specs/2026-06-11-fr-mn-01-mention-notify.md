<!-- FR-MN-01 본문 @멘션 → pgmq 이벤트 발행 기술 스펙 (issue-tracking BC) -->
# FR-MN-01 — 본문 @멘션 + 즉시 알림 (이벤트 발행) — 스펙

> slug: fr-mn-01-mention-notify · BC: issue-tracking · type: backend · 작성: 2026-06-11
> 정본: fr-index §4.1.1 / product issue-tracking §4.1.1 / SDD 09 §9.4
> **범위 (Maxi 확정, 옵션 A)**: 본문(description) 저장 시 `@username` 추출 → userId 해석 → `IssueMentioned`(issue.mentioned) pgmq 발행까지.
> **제외 (deferred)**: 댓글 멘션(댓글 미존재), 그룹 멘션(@team), 알림 전달/Inbox(FR-NT/FR-UX-03), 멘션 렌더링 UI(D6), Inbox-도착 E2E(D7), 멘션 자동완성(FR-MN-02).

---

## 배경 — 현재 코드 실태 (조사 결과)

- 본문(description): `Issue.description` 존재(FR-IS-04 완료, PR #43~47). **생성 시엔 미설정** — `CreateIssueRequest`에 description 필드 없음. 본문은 **오직 PATCH(`updateIssue`)로만** 변경된다.
- 이벤트 인프라: `IssueDomainEvent` sealed interface(`issue.created/updated/transitioned/soft_deleted`) + `IssueEventPublisher.publish()` (pgmq `q_issue_events`, `@Transactional(MANDATORY)` outbox). → 새 이벤트 타입 추가가 정석 확장 경로.
- cross-BC 사용자 조회: `com.bts.shared.user.UserLookupPort`(shared-kernel, 현재 `exists(UUID)`만). 구현체 = identity-access `UserLookupAdapter`(NamedParameterJdbcTemplate). `users.username VARCHAR(255) NOT NULL UNIQUE`.
- username 형식: `^[A-Za-z0-9._-]+$`, 3~255자 (`CreateUserRequest.USERNAME_PATTERN`).

---

## 사용자 시나리오 (Given-When-Then)

**S1 — 본문에 멘션 추가 → 이벤트 발행**
- Given: 이슈 `ATLAS-42`의 본문이 `"초안"`이고, 사용자 `bob`(username)이 실재한다.
- When: 작성자 `alice`가 본문을 `"@bob 검토 부탁"`으로 PATCH 한다.
- Then: `IssueMentioned(issueKey=ATLAS-42, mentionedUserIds=[bob.id], actorId=alice, sourceField="description")` 이벤트가 `q_issue_events` 큐에 발행된다. 이슈 본문 저장과 같은 트랜잭션.

**S2 — 재편집, 멘션 변화 없음 → 발행 안 함**
- Given: 본문이 이미 `"@bob 검토 부탁"`.
- When: `alice`가 본문을 `"@bob 검토 부탁드립니다"`로 PATCH (멘션 집합 동일).
- Then: `IssueMentioned` **미발행** (신규 멘션 0건). `IssueUpdated`(description)는 기존대로 발행.

**S3 — 멘션 추가분만 발행**
- Given: 본문 `"@bob"`.
- When: `"@bob @carol"`로 PATCH.
- Then: `IssueMentioned(mentionedUserIds=[carol.id])` — **신규 추가된 carol만**. bob은 재알림 안 됨.

**S4 — 자기 자신 멘션 → 제외**
- When: `alice`가 본문에 `"@alice 메모"` 추가.
- Then: 자기 멘션은 제외 → 신규 멘션 0건이면 `IssueMentioned` 미발행.

**S5 — 미존재 username → 무시**
- When: 본문에 `"@ghost"`(미존재 username) 추가.
- Then: 해석 실패로 드롭. 다른 유효 멘션이 없으면 `IssueMentioned` 미발행. 에러 없음(이슈 저장은 정상).

**S6 — 코드 블록/이메일 → 멘션 아님**
- When: 본문에 ``` `@bob` ```(인라인 코드), 펜스 코드 블록 내 `@bob`, 또는 `user@example.com` 포함.
- Then: 셋 다 멘션으로 추출되지 않음.

---

## 기능 요구사항 (FR)

- **F1 — 멘션 추출 지점**: `IssueApplicationService.updateIssue`에서 `changedFields`에 `"description"`이 포함될 때만 멘션 추출을 수행한다. (생성 경로는 description 부재로 해당 없음, 클론은 §제외)
- **F2 — 파싱 규칙**: 새 본문(`request.description`)에서 다음 정규식으로 `@username` 후보를 추출한다.
  - 정규식: `(?<![A-Za-z0-9._-])@([A-Za-z0-9](?:[A-Za-z0-9._-]*[A-Za-z0-9])?)`
    - 앞 negative lookbehind `(?<![A-Za-z0-9._-])` → 이메일(`user@x.com`)의 `@` 비매칭.
    - 캡처는 영숫자로 시작·끝 → 문장부호(`@alice.`의 `.`) 제외.
  - **코드 제거 선처리**: 펜스 코드 블록(```` ``` ... ``` ````)과 인라인 코드(`` `...` ``)를 정규식으로 먼저 제거한 뒤 멘션을 추출한다 (코드 내 `@`는 멘션 아님).
- **F3 — 해석**: 추출된 username 집합(대소문자 원문 보존, dedup)을 `UserLookupPort.findIdsByUsernames(Set<String>): Map<String, UUID>`로 일괄 해석한다. 미존재 username은 결과 맵에 없음 → 자동 드롭. **DB가 멘션 유효성의 단일 진실원천**(과대 캡처는 해석 실패로 안전하게 무시).
- **F4 — diff 발행**: `신규멘션 = 해석(새 본문 멘션) − 해석(옛 본문 멘션) − {actor 본인 id}`. `신규멘션`이 비어있지 않을 때만 `IssueMentioned` 1건 발행. (옛 본문 = `existing.description`, updateIssue에서 이미 로드됨)
- **F5 — 이벤트 형태**:
  ```kotlin
  @JsonTypeName("issue.mentioned")
  data class IssueMentioned(
      val issueKey: IssueKey,
      val projectKey: String,
      val mentionedUserIds: List<UUID>,   // 해석·dedup·자기제외 후, 정렬 고정(결정적)
      val actorId: ActorId,               // 멘션을 작성한 행위자
      val sourceField: String,            // "description" (향후 "comment")
      val occurredAt: Instant,
  ) : IssueDomainEvent
  ```
  `@JsonSubTypes`에 `issue.mentioned` 등록. 직렬화 다형성은 기존 패턴 동일.
- **F6 — 트랜잭션 정합**: `IssueMentioned` 발행은 본문 저장과 **같은 트랜잭션**(outbox 패턴, `IssueEventPublisher.publish` MANDATORY). 본문 저장 실패 시 이벤트도 롤백.
- **F7 — 발행 순서**: 기존 `IssueUpdated`(description 변경 시) 발행 직후 `IssueMentioned`를 발행. 둘은 독립 이벤트.

## 비기능 요구사항 (NFR)

- **N1 — 보안**: username 해석은 named parameter 바인딩(SQL 인젝션 금지, 기존 `UserLookupAdapter` 패턴). 멘션이 권한을 우회해 비공개 이슈 존재를 노출하지 않음 — 이벤트는 큐 내부 데이터이며 전달 단계(FR-NT)에서 수신자 권한 필터링 책임(이 FR 범위 아님, 단 §제약에 명시).
- **N2 — 성능**: 멘션 해석은 본문당 1회 `WHERE username IN (:names)` 단일 쿼리(N+1 금지). distinct 멘션은 본문당 `MAX_MENTIONS_PER_EVENT`(50)으로 cap — 초과분은 결정적(정렬 후 take)으로 드롭 + `log.warn`. IN 파라미터 수와 `mentionedUserIds` payload 크기를 둘 다 bound (H1 방어).
- **N3 — 결정성**: `mentionedUserIds`는 정렬 고정(예: UUID 오름차순)으로 직렬화 — 테스트 안정성.
- **N4 — 회귀 0**: 기존 `updateIssue` 동작(필드 병합/OCC/IssueUpdated 발행) 불변. 멘션 로직은 부가 side-effect로만 추가.

## API 인터페이스 (REST)

- **신규 엔드포인트 없음.** 멘션 추출은 기존 `PATCH /api/v1/issues/{key}` (본문 변경)의 내부 side-effect.
- (FR-MN-02에서 `GET /api/v1/users/autocomplete` 추가 예정 — 본 FR 범위 아님)

## 데이터 모델 변경

- **마이그레이션 없음.** 기존 `q_issue_events` pgmq 큐 재사용. 새 테이블·컬럼 없음 (plan D3 "이벤트 발행만" 충실).
- `UserLookupPort` 확장은 코드 변경(shared-kernel 인터페이스 + identity-access 어댑터), 스키마 무관 — 기존 `users.username` 컬럼 조회.

## cross-BC 계약 변경 (UserLookupPort 확장)

- shared-kernel `UserLookupPort`에 메서드 추가:
  ```kotlin
  /** 주어진 username 집합을 실재 사용자 id 로 일괄 해석. 미존재 username 은 결과에서 제외. */
  fun findIdsByUsernames(usernames: Set<String>): Map<String, UUID>
  ```
  - 빈 입력 → 빈 맵 (쿼리 생략).
  - username 대소문자: `users.username`은 대소문자 구분 UNIQUE → 정확 매칭(원문 보존). (대소문자 무시 매칭은 비범위)
- identity-access `UserLookupAdapter` 구현: `SELECT id, username FROM users WHERE username IN (:names)` (named param 컬렉션 바인딩, 프로젝트 `findByIds` 선례 일치, 읽기 전용). 결과를 `username -> id` 맵으로 수집. (`= ANY(:array)`는 드라이버 배열 바인딩 의존이라 미채택 — distinct 멘션은 cap(50)으로 bound되어 IN 파라미터 수는 안전.)
- ADR 참조: 기존 `2026-06-01-issue-assignee-user-lookup-port` 포트의 메서드 추가 — 신규 ADR 불요(동일 포트 확장), plan에 기록.

## 엣지 케이스

- **EC-1 자기 멘션**: actor 본인 id는 신규멘션에서 제외(F4). 자기만 멘션 시 미발행.
- **EC-2 중복 멘션**: `@bob @bob` → username dedup → 1회.
- **EC-3 미존재 username**: 해석 맵에 없음 → 드롭, 에러 없음.
- **EC-4 description 클리어**: `""` 또는 변경으로 본문이 비워짐 → 새 멘션 0 → (옛 멘션이 있었어도 "추가"가 아니므로) 미발행.
- **EC-5 description 무변경**: `changedFields`에 description 없음 → 추출 자체를 건너뜀.
- **EC-6 이메일/멘션 혼재**: `"contact alice@corp.com or @bob"` → `alice@...`는 lookbehind로 비매칭, `@bob`만 추출.
- **EC-7 대량 멘션**: 한 본문에 distinct username 다수 → 단일 ANY 쿼리로 처리. (스팸성 대량 멘션 throttle은 전달단(FR-NT) 책임, 본 FR은 추출만)
- **EC-8 코드 스팬 내 멘션**: 인라인/펜스 코드 내 `@x`는 선처리 제거 후 비추출(S6/F2).
- **EC-9 클론(cloneIssue)**: 원본 본문의 멘션은 **재발행하지 않는다**(의도적 제외). 근거: 클론은 본문의 구조적 복사이며 작성자가 그 멘션을 의도적으로 작성한 것이 아니다. FR-IS-06은 "core 필드만" 복제 기조. → cloneIssue 경로에 멘션 추출 미적용.
- **EC-10 username 경계**: `@alice-bob`(하이픈 포함 유효 username), `@alice.` (문장부호) → 캡처가 영숫자로 끝나 `alice`만, DB 해석으로 최종 검증.

## 제약 조건

- **C1 — BC 격리**: issue-tracking은 identity-access를 직접 gradle 의존하지 않는다. 사용자 해석은 shared-kernel `UserLookupPort` 경유만(직접 import 금지). [learnings: cross-BC resolver 창구]
- **C2 — fail-open 금지**: `findIdsByUsernames` 미해석은 "드롭"(해당 멘션만 누락)이지, "전체 통과/전체 거부"가 아니다. nullable 기본값으로 인한 fail-open 금지. [learnings: cross-BC resolver nullable fail-open]
- **C3 — 권한 책임 분리**: 멘션된 사용자가 이슈 VIEW 권한이 없을 수 있다. 본 FR(추출/발행)은 권한 필터링을 하지 않는다 — 수신자 권한 검증은 전달단(FR-NT) 책임이며 SDD 09 §9.1.3 fanOut 단계에 위임. 이벤트 payload는 큐 내부 데이터로 외부 노출되지 않음. (이 분리를 plan·리뷰에 명시)
- **C4 — 완제품 품질**: 임시/PoC 금지. TDD red→green, detekt/ktlint 그린, 테스트 커버.

## 측정 가능한 완료 기준

1. `IssueMentioned`가 `IssueDomainEvent` sealed + `@JsonSubTypes`에 등록되고 JSON 직렬화/역직렬화 라운드트립 통과.
2. `UserLookupPort.findIdsByUsernames` + `UserLookupAdapter` 구현 — Testcontainers로 `users` 시드 후 일괄 해석/미존재 드롭 검증.
3. `MentionParser`(또는 동등 유틸) 단위 테스트: 이메일 회피·코드 스팬 제거·문장부호 경계·dedup 케이스(S6/EC-6/EC-8/EC-10) 통과.
4. `updateIssue` 통합 테스트(Testcontainers): S1(발행)·S2(무변화 미발행)·S3(추가분만)·S4(자기제외)·S5(미존재 드롭) — pgmq `q_issue_events`에서 `IssueMentioned` 유무·payload 검증.
5. 회귀: 기존 issue-tracking 테스트 전부 통과(N4). 3모듈 detekt/ktlint 그린.
6. (deferred 명시) D6 렌더링·D7 Inbox E2E는 본 PR 완료 판정 제외 — product 파일에 deferred 마킹.

---

## Brainstorming Check

✅ 통과 (1회 iteration, Maxi 결정 gap 없음). 코드 검증으로 확인한 항목.
- **sealed subtype 추가 안전성**: production에 `IssueDomainEvent` 망라 `when` 소비자 없음(main 소스는 publisher/정의만) → `IssueMentioned` 추가가 컴파일/소비 회귀 없음. [회귀 의심 → 검증 완료]
- **q_issue_events 무소비자**: 기존 모든 이슈 이벤트가 소비자 없이 발행되는 정착 패턴 → FR-MN-01이 신규 위험 도입 안 함. 소비자는 FR-NT.
- **직렬화 가드**: `IssueDomainEventTest`에 `issue.mentioned` 라운드트립 추가(완료 기준 1).
- **그룹 멘션(@team) 제외 정당성**: SDD 9.4의 그룹 멘션은 FR-PM-09 user_groups를 소비하는 별도 단위 → 본 FR 제외 확정.
