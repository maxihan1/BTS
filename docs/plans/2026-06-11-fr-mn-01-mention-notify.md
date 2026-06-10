# FR-MN-01 — 본문/댓글 @멘션 + 즉시 알림

> slug: fr-mn-01-mention-notify
> type: backend
> agent: backend-engineer
> BC: issue-tracking (fr-index 정본 §4.1.1) — classify의 primary_bc=notification은 미존재 모듈, 무시
> 생성: 2026-06-11

## Brief

사용자 원문: "fr-mn-01 진행해줘"

FR-MN-01 (fr-index §4.1.1, 필수): 이슈 본문/댓글에서 `@사용자`로 멘션하면 해당 사용자에게 즉시 알림.

classify 결과: type=backend, agent=backend-engineer, primary_bc=notification(미존재 → issue-tracking로 정정).
미해결 핵심 쟁점: "즉시 알림"의 전달 메커니즘 + BC 경계 (notification 모듈 부재 → issue-tracking 내 처리 vs 신규 모듈/이벤트). domain 단계에서 결정.

## 도메인 정리 (← /bts-domain 채움)

- **BC**: issue-tracking (정본 fr-index §4.1.1). classify의 `primary_bc=notification`은 미존재 모듈 → 무시.
- **범위 결정 (Maxi 확정, 옵션 A)**: 본문 멘션 추출 + pgmq 이벤트 발행까지. 댓글 멘션·Inbox 도착(D7)·렌더링(D6)은 후속 FR로 deferred.
  - 근거: ① 댓글(comment) FR·엔티티 부재(본문만 가능) ② 알림 전달/Inbox(FR-UX-03)·notification 모듈 부재 ③ plan 정본 D3/D4가 이미 "notification 이벤트 발행만"으로 좁혀 명시 ④ learnings "병렬 FR 인프라 충돌" — notification 인프라 선점 시 FR-NT와 충돌.
- **선행 상태**: §2.1.4 FR-IS-04(본문 Markdown) = ✅완료(PR #43~47). §4.3.1 FR-WT-01(Watcher) = ❌미완료지만, 옵션 A 범위(멘션→이벤트 발행)는 멘션 대상을 직접 해석하므로 Watcher 불요.
- **영향 엔티티**: Issue(기존, `description` 필드 활용), `IssueMentioned`(신규 도메인 이벤트, `issue.mentioned`).
- **신규 용어**: "멘션 (Mention)" — 글(본문/댓글)에서 `@username`으로 다른 사용자를 호출하는 행위. glossary에 정식 등재 후보(현재 "그룹 멘션" 만 간접 언급). Maxi 승인 대기.
- **기존 이벤트 인프라 활용**: `IssueDomainEvent` sealed interface(`issue.created/updated/transitioned/soft_deleted`) + `IssueEventPublisher`(pgmq `q_issue_events`, `@Transactional(MANDATORY)` outbox) + 발행 호출처 `IssueApplicationService`(create L213 / update L285 등). → `IssueMentioned` 추가 후 본문 저장 시점에서 발행.
- **미해결(→ spec에서 확정)**: ① `@username` 파싱 규칙(정규식, 코드블록/이메일 회피) ② username→userId cross-BC 해석 메커니즘(identity-access resolver 존재 여부 확인 필요) ③ `IssueMentioned` payload 형태 ④ 추출 시점(생성 + description PATCH) ⑤ 자기 멘션/중복/미존재 username 처리.
- **관련 ADR**: 없음 (plan 정본 D3/D4 의도 준수, 신규 아키텍처 결정 없음).

## 스펙

전체 스펙. [docs/specs/2026-06-11-fr-mn-01-mention-notify.md](../specs/2026-06-11-fr-mn-01-mention-notify.md)

핵심 3줄 요약.
- 이슈 본문(description) PATCH 시 `@username` 추출 → `UserLookupPort.findIdsByUsernames`로 해석 → `IssueMentioned`(issue.mentioned) pgmq 발행 (같은 트랜잭션 outbox).
- diff 기반(신규 추가 멘션만) + 자기멘션 제외 + 미존재/이메일/코드스팬 무시. 신규 멘션 0건이면 미발행.
- 신규 REST 엔드포인트·Flyway 마이그레이션 없음. cross-BC는 UserLookupPort 확장만. 댓글·Inbox·D6/D7 deferred.

## Brainstorming Check

✅ 통과 (1회 iteration). sealed subtype 추가 안전성·q_issue_events 무소비자 패턴 코드 검증 완료. Maxi 결정 gap 없음.

## Plan

> 파급 분석 결과 반영.
> - **UserLookupPort 확장은 interface default 메서드**로 — `object : UserLookupPort` 인라인 구현 ~35개 테스트 fake가 깨지지 않도록(`enum-add-breaks` 교훈의 인터페이스판). 실제 어댑터(`UserLookupAdapter`)와 멘션 테스트만 override. production 구현체는 `UserLookupAdapter` 단 1개.
> - **MentionParser는 무상태 `object` 유틸** — `IssueApplicationService` 생성자 미변경(주입 빈 아님). `userLookupPort`는 이미 주입됨 → Task 4는 생성자 파급 0 → 기존 IssueApplicationService 테스트 fake 영향 없음.

### Task 1. IssueMentioned 도메인 이벤트 추가

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/event/IssueDomainEvent.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/event/IssueDomainEventTest.kt`]
- depends-on: []

**RED**:
- 파일: `IssueDomainEventTest.kt`
- 테스트: `issue.mentioned` JSON 직렬화→역직렬화 라운드트립 — `IssueMentioned(issueKey, projectKey, mentionedUserIds, actorId, sourceField, occurredAt)`가 `"type":"issue.mentioned"`로 직렬화되고 다시 같은 인스턴스로 역직렬화. `mentionedUserIds` 순서 보존.
- 실패: `IssueMentioned` 클래스 없음 (컴파일 실패).

**GREEN**:
- 파일: `IssueDomainEvent.kt`
- `@JsonSubTypes`에 `JsonSubTypes.Type(value = IssueMentioned::class, name = "issue.mentioned")` 추가.
- `data class IssueMentioned(val issueKey: IssueKey, val projectKey: String, val mentionedUserIds: List<UUID>, val actorId: ActorId, val sourceField: String, val occurredAt: Instant) : IssueDomainEvent` + `@JsonTypeName("issue.mentioned")`. (import `java.util.UUID`)

**REFACTOR**:
- KDoc(각 프로퍼티 의미 + sourceField 향후 "comment" 메모). 기존 이벤트 KDoc 스타일 일치.

**검증**: `./gradlew :backend:modules:issue-tracking:test --tests "*IssueDomainEventTest*"`

---

### Task 2. MentionParser 무상태 유틸 (정규식 + 코드 스팬 제거)

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/mention/MentionParser.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/mention/MentionParserTest.kt`]
- depends-on: []

**RED**:
- 파일: `MentionParserTest.kt`
- 테스트(스펙 S6/EC-6/EC-8/EC-10 + EC-2):
  - `"@bob 검토"` → `{"bob"}`
  - `"contact alice@corp.com or @bob"` → `{"bob"}` (이메일 회피)
  - 인라인 코드 `` "`@bob`" `` → `{}` (코드 스팬 제거)
  - 펜스 코드 블록 ```` "```\n@bob\n```" ```` → `{}`
  - `"@bob @bob"` → `{"bob"}` (dedup)
  - `"@alice."`(문장부호) → `{"alice"}` (영숫자 경계)
  - `"@alice-bob @x.y_z"` → `{"alice-bob","x.y_z"}`
- 실패: `MentionParser` 없음.

**GREEN**:
- 파일: `MentionParser.kt`
- `object MentionParser { fun extract(text: String?): Set<String> }`.
  - text null/blank → emptySet.
  - 선처리: 펜스 코드 블록 제거(```` ```...``` ````, DOTALL non-greedy) → 인라인 코드 제거(`` `...` ``).
  - 멘션 정규식 `(?<![A-Za-z0-9._-])@([A-Za-z0-9](?:[A-Za-z0-9._-]*[A-Za-z0-9])?)`. 캡처 그룹 수집(LinkedHashSet 순서 보존).
- 정규식·코드제거 패턴은 `private val` 상수.

**REFACTOR**:
- KDoc(L1 한글 주석 + 각 패턴 의미). detekt MaxLineLength/NestedBlockDepth 0 보장.

**검증**: `./gradlew :backend:modules:issue-tracking:test --tests "*MentionParserTest*"`

---

### Task 3. UserLookupPort.findIdsByUsernames 확장 (default 메서드 + 어댑터 구현)

**메타**.
- agent: `backend-engineer` (shared-kernel + identity-access 단순 조회 — 사용자/세션 스키마 변경 아님, security 공동검토 불요. 단 리뷰에서 cross-BC 계약 확인)
- files: [`backend/modules/shared-kernel/src/main/kotlin/com/bts/shared/user/UserLookupPort.kt`, `backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/user/UserLookupAdapter.kt`, `backend/modules/identity-access/src/test/kotlin/com/atlas/bts/identity/user/UserLookupAdapterIntegrationTest.kt`]
- depends-on: []

**RED**:
- 파일: `UserLookupAdapterIntegrationTest.kt` (Testcontainers, 기존 클래스 확장)
- 테스트: `users`에 alice/bob 시드 → `findIdsByUsernames(setOf("alice","bob","ghost"))` → `{"alice"->id, "bob"->id}` (ghost 드롭). 빈 입력 → 빈 맵(쿼리 생략).
- 실패: `findIdsByUsernames` 미정의.

**GREEN**:
- `UserLookupPort.kt`: `fun findIdsByUsernames(usernames: Set<String>): Map<String, UUID> = emptyMap()` **default 메서드**(인라인 fake 보호). KDoc로 "미존재 username은 결과 제외, 실제 구현은 UserLookupAdapter" 명시.
- `UserLookupAdapter.kt`: override. 빈 입력 → `emptyMap()` 즉시 반환. 아니면 `SELECT id, username FROM users WHERE username = ANY(:names)` (named param, `Array` 바인딩) → `RowMapper`로 `username->id` 수집. `@Transactional(readOnly = true)`.

**REFACTOR**:
- SQL 상수 `SQL_FIND_IDS_BY_USERNAMES` companion. KDoc.

**검증**: `./gradlew :backend:modules:identity-access:test --tests "*UserLookupAdapterIntegrationTest*"`

---

### Task 4. updateIssue 멘션 추출 → IssueMentioned 발행 결선

**메타**.
- agent: `backend-engineer`
- files: [`backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/application/IssueApplicationService.kt`, `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/application/IssueApplicationServiceMentionTest.kt`]
- depends-on: [1, 2, 3]

**RED**:
- 파일: `IssueApplicationServiceMentionTest.kt` (신규, MockK 단위 — 기존 `object : UserLookupPort` fake 패턴에 `findIdsByUsernames` override 추가)
- 테스트(스펙 S1~S5):
  - S1: 본문 `null→"@bob"` 변경 → `eventPublisher.publish(match { it is IssueMentioned && it.mentionedUserIds == listOf(bobId) && it.sourceField=="description" && it.actorId==alice })` 1회.
  - S2: `"@bob"→"@bob 추가"`(멘션 동일) → `IssueMentioned` 미발행(`verify(exactly=0)`).
  - S3: `"@bob"→"@bob @carol"` → `mentionedUserIds == listOf(carolId)` (추가분만).
  - S4: actor=alice가 `"@alice"` 추가 → `IssueMentioned` 미발행(자기 제외 후 빈집합).
  - S5: `"@ghost"`(해석 빈맵) → 미발행.
  - 기존 `IssueUpdated`(description) 발행은 모든 케이스 유지(회귀).
- 실패: 멘션 발행 로직 없음.

**GREEN**:
- 파일: `IssueApplicationService.kt`, `updateIssue` 내 `IssueUpdated` 발행(L438) **직후**:
  - `if ("description" in changedFields)` 일 때만:
    - `val newM = MentionParser.extract(request.description)`; `val oldM = MentionParser.extract(existing.description)`
    - `val added = newM - oldM`; if `added.isEmpty()` → skip.
    - `val resolved = userLookupPort.findIdsByUsernames(added)` (`Map<String,UUID>`)
    - `val targets = resolved.values.toSet() - actor.value` (자기 제외, dedup)
    - if `targets.isNotEmpty()` → `eventPublisher.publish(IssueMentioned(key, key.projectPrefix, targets.sorted(), actor, "description", Instant.now(clock)))`.
  - 생성자/주입 변경 없음(`MentionParser` object 호출, `userLookupPort`/`eventPublisher`/`clock` 기존 주입).
- `changedFields`의 description 토큰 문자열은 GREEN 직전 `buildChangedFields` 실측 확인("description").

**REFACTOR**:
- 멘션 블록을 `private fun publishMentions(key, existing, request, actor)`로 추출 + KDoc. detekt 복잡도 0. ktlint 라인길이.

**검증**: `./gradlew :backend:modules:issue-tracking:test --tests "*IssueApplicationServiceMentionTest*"` + 회귀 `--tests "*IssueApplicationServiceUpdateTest*"`

---

## Plan 메타

- task 수: 4
- 예상 wave: 2 (Wave 1 = T1·T2·T3 병렬 독립 / Wave 2 = T4 ← [1,2,3])
  - T1·T2(issue-tracking) · T3(shared-kernel+identity-access)는 파일·모듈 비겹침 → 병렬. T4는 세 산출물 모두 호출 → 직렬.
- 예상 시간: 직렬 ≈ 16분, wave 병렬 ≈ 9분
- TDD 강제: yes (각 task RED→GREEN→REFACTOR)
- 마이그레이션: 없음 (q_issue_events 재사용, 스키마 무변경)
- 추가 검증: 3모듈(issue-tracking·identity-access·shared-kernel) detekt/ktlint 그린, 전체 회귀 통과
- 범위 deferred 마킹: D6 렌더링·D7 Inbox E2E·댓글 멘션·그룹 멘션 (product 파일에 후속 FR 위임 명시 — 머지 단계 동기화)

## 리뷰 결과

### eng 집중 독립 리뷰 (2026-06-11, backend type — autoplan 대신 eng 집중)

**실측 검증 (가정 확정)**.
- ✅ 트랜잭션 경계(F6): `IssueApplicationService` 클래스 레벨 `@Transactional`(L92) → `updateIssue`의 `IssueMentioned` 발행이 본문 저장과 동일 트랜잭션. `IssueEventPublisher`(MANDATORY) 정합.
- ✅ description 게이트(F1/F4): `buildChangedFields`가 `"description"` 토큰 추가(L1032, `isTextFieldChanged`). clear("") → added 빈집합 → 미발행(EC-4) 정확.
- ✅ cross-BC 격리(C1): identity-access 직접 import 없음, shared-kernel `UserLookupPort` 경유만.
- ✅ fail-open 금지(C2): default `emptyMap()`은 테스트 fake 보호용 fail-safe(멘션 0=미발행), per-mention 드롭 의미. allow-all 아님.
- ✅ 권한 분리(C3): 추출/발행은 수신자 권한 미필터(전달단 FR-NT 책임). 이벤트=큐 내부 데이터, 외부 미노출.
- ✅ 클론 일관성(EC-9): cloneIssue는 updateIssue 미경유 → 멘션 자동 미발행, 추가 코드 불요.
- ✅ wave 파일 비겹침: T1·T2(issue-tracking 신규 파일)·T3(shared-kernel+identity-access) 교집합 ∅ → 병렬 안전.

**⚠️ 주의 (구현 시 반영, BLOCKER 아님)**.
- A1. `UserLookupAdapter.override` 필수 — default가 production에 새면 멘션 영구 침묵. 완화: Task 3 통합테스트가 실제 어댑터 검증 + production 유일 `@Component` 구현체. 가능하면 boot 컨텍스트(예: identity-access boot test)에서 `UserLookupAdapter` 빈 주입 확인 1줄 추가.
- A2. `MentionParser` 코드스팬 제거는 불균형 백틱 등 엣지에서 best-effort — v1 수용, KDoc에 한계 명시. 정밀화는 렌더링(D6)/FR-MN-02에서.
- A3. 신규 파일 2종(`MentionParser.kt`, `IssueApplicationServiceMentionTest.kt`)은 **L1 한글 헤더 주석** 필수(글로벌 CLAUDE.md §6).
- A4. `mentionedUserIds`는 `UUID.sorted()`(UUID Comparable) 결정적 직렬화 — N3.

**관찰**.
- O1. `q_issue_events` 무소비자 — 기존 모든 이슈 이벤트와 동일 패턴, 회귀 아님. 큐 retention/소비는 FR-NT 책임.

**BLOCKER: 없음.**
