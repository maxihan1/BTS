<!-- ADR: 워크플로우 CustomExpression 파서 — SpEL 채택 (ANTLR 4 거부) -->

# ADR — 워크플로우 CustomExpression 파서 선택

**일자**. 2026-05-21
**상태**. Accepted
**관련 PR**. project-workflow-bc-fr-wf-01-fsm-1-pr
**작성자**. Maxi + Claude (backend-engineer)

## 컨텍스트

FR-WF-01 의 `CustomExpressionValidator` 는 워크플로우 전환 조건을 평가해야 한다. 조건 표현식은 "이슈 필드 + 액터 정보 기반 boolean 표현식" 형태로, 워크플로우 정의 YAML/seed 에 포함된다 (관리자만 편집 가능).

예시.

```yaml
transitions:
  - from: OPEN
    to: IN_PROGRESS
    condition: "issue.priority == 'HIGH' and actor.role == 'DEVELOPER'"
```

평가 엔진 후보 두 가지를 검토했다.

### 후보 A — ANTLR 4

ANTLR 4 (ANother Tool for Language Recognition) 는 자체 문법 파일(`.g4`)을 작성하면 파서 코드를 자동 생성해 주는 도구다. 마치 "내 언어의 문법 규칙서"를 쓰면 해석기를 만들어 주는 식이다.

- 장점. 사용자 표현식 안전성 완전 제어, 풍부한 DSL 설계 가능.
- 단점.
  - `.g4` 문법 파일 + 코드 생성 단계 → Gradle 빌드 복잡도 증가.
  - 문법 유지보수 비용이 1인 운영 1,000명 규모에서 과잉.
  - Visitor / Listener 계층 구현 필요 — 초기 작성 + 장기 유지 공수 큼.

### 후보 B — SpEL (Spring Expression Language)

SpEL 은 Spring 프레임워크에 내장된 표현식 평가 엔진이다. `"issue.priority == 'HIGH'"` 같은 문자열을 받아 실행 중에 평가한다. 별도 라이브러리 없이 Spring Boot 만으로 동작한다.

- 장점. 외부 의존성 0, `SimpleEvaluationContext` 로 메서드 호출 차단 가능, Spring 생태계 숙련도 그대로 적용.
- 단점. Spring에 묶임 (본 프로젝트는 이미 Spring Boot 단일 스택이므로 실질 제약 없음).

## 결정

**SpEL 채택.** 아래 네 가지 조건을 모두 적용한다.

### 조건 1 — SimpleEvaluationContext 로 평가 컨텍스트 제한

```kotlin
val context = SimpleEvaluationContext
    .forReadOnlyDataBinding()
    .build()
```

`SimpleEvaluationContext` 는 SpEL 의 "제한 모드"다. 기본 `StandardEvaluationContext` 와 달리 임의 Java 메서드 호출, 리플렉션(Reflection — 실행 중 클래스 구조를 들여다보는 기능), bean 참조를 모두 차단한다.

### 조건 2 — Root 객체를 sealed interface + getter-only data class 로 제한

```kotlin
// 워크플로우 표현식이 접근할 수 있는 루트 객체 인터페이스
sealed interface ExpressionRoot

data class IssueView(
    val id: Long,
    val key: String,
    val priority: String,
    val status: String,
    val assigneeId: Long?,
) : ExpressionRoot

data class ActorView(
    val id: Long,
    val role: String,
    val email: String,
) : ExpressionRoot
```

Root 객체(표현식에서 `issue.*`, `actor.*` 로 접근하는 대상)는 데이터 읽기만 허용하는 구조로 설계한다. `sealed interface` 는 "이 인터페이스의 구현체는 이 파일 안에만 존재한다"는 Kotlin 제약이다. 덕분에 외부에서 임의 구현체를 주입할 수 없다.

**NOTE.** Kotlin `@JvmInline value class` 는 단일 필드만 가질 수 있는 제약이 있어 다중 필드 Root 표현에 적합하지 않다. `sealed interface + concrete data class` 패턴이 정답이다.

Root 표면에 action 메서드가 0개임은 ArchUnit (아키텍처 제약을 테스트하는 라이브러리) 또는 리플렉션 테스트로 CI 에서 강제한다.

### 조건 3 — 50ms timeout 으로 무한 루프 / DoS 차단

```kotlin
val future = executor.submit(Callable {
    parser.parseExpression(expression).getValue(context, root, Boolean::class.java)
        ?: false
})
try {
    future.get(50, TimeUnit.MILLISECONDS)
} catch (e: TimeoutException) {
    future.cancel(true)
    throw ExpressionTimeoutException("표현식 평가 제한 시간(50ms) 초과: $expression")
}
```

DoS (Denial of Service — 서비스 거부 공격) 형태의 악의적 표현식이 무한 루프를 일으키는 경우를 차단한다. `ExecutorService + Future.get(timeout)` 패턴으로 50ms 초과 시 강제 중단한다.

### 조건 4 — 사용자 입력 표현식 직접 평가 금지

표현식은 반드시 워크플로우 정의 YAML 또는 seed 데이터에서만 로드한다. 이 파일들은 관리자(Admin) 만 편집할 수 있다. 일반 사용자가 API 를 통해 임의 표현식을 실행 엔진에 전달하는 경로를 만들지 않는다.

## 거부 후보 — ANTLR 4

| 거부 이유 | 상세 |
|---|---|
| 유지 비용 과잉 | 1인 운영 + 1,000명 규모에서 자체 DSL 문법 파일 유지는 수익 대비 비용 초과 |
| 빌드 복잡도 증가 | 코드 생성 단계가 Gradle 빌드 파이프라인에 추가됨 — CI 속도 저하 |
| SpEL 대비 이점 부재 | `SimpleEvaluationContext` sandbox 로 동일 수준의 안전성 달성 가능 |

## 영향

### 긍정

- **외부 의존성 0** — `spring-expression` 은 `spring-boot-starter` 에 포함. 추가 의존성 없음.
- **sandbox 보안 모델 명확** — SimpleEvaluationContext + sealed root + 50ms timeout 세 겹 방어.
- **빌드 단순** — 코드 생성 단계 없음. Gradle 설정 변경 없음.
- **Spring Boot 숙련도 재사용** — 별도 파서 학습 곡선 없음.

### 부정 / 위험

- **표현식 함수 호출 차단** — Root 객체 surface 에 action 메서드가 없어야 한다는 규칙을 ArchUnit 또는 리플렉션 기반 테스트로 CI 에서 강제해야 한다. 테스트 없으면 사람 실수로 메서드가 추가될 수 있다.
- **Spring 종속** — SpEL 은 Spring 내장. Spring 에서 다른 프레임워크로 마이그레이션 시 교체 필요. 현재 프로젝트에서는 실질 위험 없음.
- **표현식 언어 표현력 한계** — 복잡한 집계 / DB 조회가 필요한 조건은 SpEL 로 표현 불가. 해당 케이스는 커스텀 `FunctionResolver` 등록 또는 별도 서비스 메서드 호출로 해결한다 (Phase 1+).

## 후속 작업

- `project-workflow.md` (Obsidian 도메인 노트) 의 "ANTLR 4" 표현을 "SpEL (`SimpleEvaluationContext`)" 로 정정 — Obsidian sync 별도 PR.
- `CustomExpressionValidator` 구현 시 ArchUnit 테스트 추가 (Root 객체 메서드 0개 강제).

## 관련

- `docs/sdd/07-workflow-engine.md` §7.3 — SpEL 채택 명세
- `Maxi_wiki/BTS/domain/project-workflow.md` — "ANTLR 4" 정정 대상
- FR-WF-01 `CustomExpressionValidator`
- [Spring Expression Language Reference](https://docs.spring.io/spring-framework/reference/core/expressions.html)
