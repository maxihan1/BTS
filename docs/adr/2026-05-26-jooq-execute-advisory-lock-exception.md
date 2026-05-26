<!-- ADR: DATA.md §5 dsl.execute(rawSql) 금지의 정식 예외 — parameter binding + jOOQ 미지원 PG 함수 한정 -->

# ADR — jooq-execute-advisory-lock-exception

**일자**. 2026-05-26
**상태**. Accepted
**관련 PR**. `issue-tracking-bc-fr-is-01-cleanup-tests`
**작성자**. Maxi + Claude (backend-engineer)

## 컨텍스트

PR #17 codereview CONCERN-3 에서 다음 두 라인이 DATA.md §5 의 `dsl.execute(rawSql)` 금지를 표면 위반하는 것처럼 보인다는 지적이 있었다.

- `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/repository/IssueRepository.kt:28` — SQL 상수 정의
- `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/repository/IssueRepository.kt:246` — `dsl.execute(SQL_ADVISORY_LOCK, "project:$projectKey")` 호출

실제 코드는 다음과 같다.

```kotlin
// L28
private const val SQL_ADVISORY_LOCK = "SELECT pg_advisory_xact_lock(hashtext(?))"

// L246
dsl.execute(SQL_ADVISORY_LOCK, "project:$projectKey")
```

`?` placeholder 를 사용하는 parameter binding 형태이므로 SQL Injection 영역은 0이다. 그러나 DATA.md §5 에 이 패턴을 허용하는 명시적 단서가 없어 reviewer 가 매 PR 마다 동일한 우려를 반복할 위험이 있다.

### DATA.md §5 금지 규칙의 원래 의도

DATA.md §5 는 SQL Injection 과 정적 검증 우회를 차단하기 위해 다음 세 패턴을 금지한다.

1. `Connection.createStatement` 직접 사용 — JDBC 레벨 raw 접근
2. `String.format("SELECT ... %s ...", userInput)` — 사용자 입력을 SQL 문자열에 직접 결합
3. `dsl.execute(rawSql)` — parameter binding 없이 전달하는 raw SQL

세 규칙의 공통 금지 이유는 **사용자 입력이 SQL 구조로 해석될 수 있는 경로**를 열기 때문이다. `dsl.execute(SQL_ADVISORY_LOCK, "project:$projectKey")` 는 parameter binding (`?` placeholder) 을 사용하므로 이 경로가 차단된 상태다.

### pg_advisory_xact_lock — jOOQ 미지원 이유

`pg_advisory_xact_lock(bigint)` 은 PostgreSQL 고유 함수다. jOOQ DSL 은 표준 SQL 및 지원 방언(dialect) 함수를 type-safe DSL 메서드로 제공하지만, `pg_advisory_xact_lock` 처럼 PostgreSQL 특화 인프라 함수는 별도 DSL 메서드가 없다. 동일 범주의 함수 예시는 아래와 같다.

- `pg_advisory_lock(bigint)` — 세션 범위 권고 락
- `pg_advisory_xact_lock(bigint)` — 트랜잭션 범위 권고 락 (본 사례)
- `pg_advisory_unlock(bigint)` — 세션 락 해제
- `pgmq.send(queue_name, msg)` — pgmq 메시지 발행 (표준 SQL 함수가 아닌 확장 함수)
- `pgmq.read(queue_name, vt, qty)` — pgmq 메시지 소비

이 함수들을 jOOQ DSL 없이 호출하려면 `dsl.execute(sql, bindings...)` 패턴이 불가피하다.

## 대안 검토

**옵션 A — DATA.md 단서만 (ADR 없음).**
헌법(DATA.md) 에 인라인으로 예외 단서를 추가한다. ADR 파일 없이 단서 한 줄로 처리.
단점 — 단서 한 줄로는 결정 근거(parameter binding 원리, jOOQ 미지원 이유, 잘못된 사용 가드)를 담기 어렵다. 향후 implementer 가 단서를 인용해 binding 없는 변형을 작성할 위험.

**옵션 B — ADR만 (DATA.md 표면 유지).**
DATA.md 는 그대로 두고 ADR 파일에 결정을 기록한다.
단점 — DATA.md 를 읽는 reviewer 가 ADR 존재를 모르면 매번 같은 우려가 반복된다. 빠른 참조 불가.

**옵션 C — 둘 다 (본 PR 채택).**
ADR 에 상세 결정 근거를 기록하고, DATA.md §5 에 ADR 링크 포함 단서 한 줄을 추가한다.
장점 — reviewer 가 DATA.md 에서 단서를 즉시 발견 + ADR 로 상세 근거 확인 경로 확보.

## 결정

정식 예외로 등록한다. 허용 조건은 다음 두 가지를 **동시에** 만족하는 경우다.

1. **parameter binding (`?` placeholder) 사용** — 사용자 입력 또는 외부 값이 SQL 구조로 해석되지 않도록 드라이버가 이스케이프 처리
2. **jOOQ 미지원 PostgreSQL 함수 호출** — `pg_advisory_lock`, `pg_advisory_xact_lock`, `pg_advisory_unlock`, `pgmq.send`, `pgmq.read` 등 jOOQ DSL 에 type-safe 메서드가 없는 PG 확장 함수

두 조건 중 하나라도 빠지면 기존 DATA.md §5 금지가 그대로 적용된다.

## 가드 — binding 없이 직접 결합 시 SQL Injection 위험

본 ADR 을 인용해 `dsl.execute(rawSql)` 패턴을 작성할 때 **binding 사용 여부를 반드시 검증**해야 한다.

**금지 예 (SQL Injection 위험).**
```kotlin
// 절대 금지 — userInput 이 SQL 구조로 해석될 수 있음
dsl.execute("SELECT pg_advisory_xact_lock(hashtext('$userInput'))")
dsl.execute("SELECT pgmq.send('q_notifications', '$payload')")
```

**허용 예 (본 ADR 예외 적용 가능).**
```kotlin
// 허용 — ? placeholder 로 바인딩, 드라이버가 이스케이프 처리
dsl.execute("SELECT pg_advisory_xact_lock(hashtext(?))", userInput)
dsl.execute("SELECT pgmq.send(?, ?::jsonb)", queueName, payload)
```

### 잠재 잘못된 사용 — 향후 implementer 검증 책임

본 ADR 예외를 인용해 새 `dsl.execute(sql, ...)` 호출을 추가할 경우, PR 작성자는 다음을 PR description 에 명시해야 한다.

- 사용한 PostgreSQL 함수명 + jOOQ 미지원 근거
- binding 사용 여부 — `?` placeholder 가 몇 개이고 어떤 값이 바인딩되는지
- 사용자 입력이 바인딩 인자에 포함되는 경우, 입력 검증 레이어 (Jakarta Validation 또는 도메인 레이어) 통과 여부

이 세 항목을 명시하지 않은 PR 은 `/bts-codereview` 에서 CONCERN 으로 처리한다.

## 결과

### 긍정

- **reviewer 반복 우려 제거** — DATA.md 단서 + ADR 링크로 매 PR 마다 같은 지적이 반복되지 않는다.
- **허용 조건 명시** — "parameter binding + jOOQ 미지원 함수" 라는 좁은 범위로 예외를 한정해 악용 경로를 최소화한다.
- **잘못된 사용 가드 상설** — 향후 implementer 가 본 ADR 을 참조하더라도 binding 검증 책임이 명시되어 있어 안전한 패턴으로 유도된다.

### 부정

- **DATA.md 에 예외 분기 증가** — 헌법 본문이 단순하지 않게 된다. 단, 단서 1줄 + ADR 링크로 본문 크기 영향 최소화.
- **예외 항목 누적 위험** — 향후 다른 jOOQ 미지원 함수가 추가될 때마다 본 ADR 이 확장될 수 있다. ADR 은 append-only 로 유지하고 새 함수 추가는 본 ADR 본문(허용 함수 목록) 을 갱신하는 방식으로 관리한다.

### 위험

- **binding 없는 변형 오인 적용** — 가드 섹션에서 명시했으나, 코드 리뷰 없이 `dsl.execute("... '$value'")` 형태가 "예외 허용"으로 오용될 수 있다. IssueBcArchTest 또는 Detekt 커스텀 룰로 `dsl.execute` 의 단인자(binding 없음) 호출을 검출하는 정적 분석 추가가 Phase 1 에서 권장된다.
- **jOOQ 버전 업 시 미지원 함수가 DSL 에 추가될 가능성** — 예. jOOQ 가 `pg_advisory_xact_lock` 용 DSL 메서드를 추가하면 본 ADR 의 예외 근거 일부가 사라진다. 분기별 jOOQ 업그레이드 시 예외 항목 유효성 재검토가 권장된다.

## 관련

- `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/repository/IssueRepository.kt:28, 246` — 현재 사용 사례 (본 ADR 의 motivating example)
- `DATA.md §5` — 헌법 본문 단서 위치
- `docs/adr/2026-05-22-pgmq-postgres-image.md` — pgmq 이미지 결정 (pgmq.send 등 pgmq 함수 도입 배경)
- `docs/adr/2026-05-21-workflow-bc-cross-bc-port.md` — port-adapter 패턴 원형 (본 ADR 의 advisory_lock 이 보호하는 `incrementKeySequence` 의 사용 맥락)
