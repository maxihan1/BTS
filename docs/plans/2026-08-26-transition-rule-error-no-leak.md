# 전환 규칙 에러 응답에 내부 정보가 새지 않음을 기계로 잠근다 (부채 136)

> 티어: T2
> slug: transition-rule-error-no-leak
> type: chore
> agent: backend-engineer
> 생성: 2026-08-26

## Brief

**FR 없음 — FR수 불변 143.** 프로덕션 Kotlin **0줄** · `apps/web` 0파일 · 마이그레이션 0 ·
신규 의존성 0 · 신규 API 0. 테스트 2파일만 강화한다.

**대상.** PR #407 이 등재한 부채 136. `TransitionRuleFrameworkErrors` 가 응답 메시지를 상수로
고정하고 상세는 로그로만 보내도록 해 뒀는데, **그것을 재는 테스트가 `jsonPath("$.error.message").isString`
뿐**이라 누가 `ex.message` 를 그 자리에 꽂아도 통과한다. 계약이 주석과 리뷰로만 지켜지고 있었다.

**왜 지금 하는가.** 현재 구현은 정확하고 새는 것이 없다 — 사용자 영향 0 이다. 다만 이 표면은
앞으로 `ProblemDetail` 이식 유혹(형제 BC 선례)과 「디버깅하기 편하게 예외 메시지를 실어 달라」는
요구를 계속 받는 자리다. 그 요구가 왔을 때 기계가 막지 않으면 사람이 리뷰에서 잡는 수밖에 없다.

이 저장소에 그 사고 이력이 있다 — 메모리 `fr-pm-04-guard-exception-message-http-leak` 에서 가드
예외 메시지의 `actorId`·`permission`·`scope` 가 HTTP 로 샜다.

## 설계 — 장부의 처방을 그대로 쓰지 않는다

장부(`TODOS.md`)는 「상수 목록을 판별식에 베끼지 말고 `TransitionRuleFrameworkErrors` 에서
**런타임에 읽어** 대조하라」를 처방했다. **그 처방은 그 자리의 기존 계약과 정면으로 충돌한다.**

`ValidatorControllerTest` 의 프레임워크 예외 블록 주석이 반대 방향을 못박아 뒀다.

> 코드 문자열은 리터럴로 적는다. 형제 `PostActionControllerTest` 가 **같은 리터럴**을 적고 있고,
> 「두 표면이 같은 코드를 쓴다」는 계약을 지키는 것은 그 대칭뿐이다 — 양쪽이 구현 상수를 import
> 하면 상수 한 벌이 갈려도 둘 다 초록이 된다.

그래서 **장부가 같은 값을 한다고 인정해 둔 두 번째 갈래**를 쓴다 — 요청에 고유 토큰을 심고 그
토큰이 응답에 **없음**을 재는 방식이다. 이 방식은 허용 메시지 집합을 참조도 복사도 하지 않으므로
`two-lists-never-check-each-other` 를 구조적으로 회피하면서 기존 리터럴 대칭 계약도 안 건드린다.

### 두 판정의 역할이 다르다

| 판정 | 무엇을 잡나 | 지금의 위치 |
|---|---|---|
| `error.message` **정확 일치** | `message` 칸의 값이 바뀌는 것 전부 | **실질 방어선** — 봉투가 `{code,message}` 뿐이라 새는 통로가 하나다 |
| **누수 카나리** (`doesNotContain`) | 응답 본문 **전체**에 요청 값이 실리는 것 | 통로가 늘어나는 날을 위한 것 (`ProblemDetail` 이식·`detail` 필드) |

정확 일치만 두면 누가 `error.detail` 같은 칸을 새로 만들어 `ex.message` 를 실었을 때 통과한다.
카나리만 두면 메시지를 「오류가 발생했습니다」로 바꿔도 통과한다. 둘 다 둔다.

### ★카나리는 값이 실제로 새는 경로에만 둔다

착수 시 「깨진 JSON 본문」에 카나리를 심었는데, **뮤테이션으로 공허함이 드러났다.**

| 경로 | `ex.message` 실측 | 카나리 |
|---|---|---|
| 비-UUID `{id}` | `… for value 'canary9f3a-not-a-uuid'` | **심는다** — 값이 실린다 |
| 본문 타입 불일치 (`displayOrder` 에 문자열) | `Cannot deserialize value of type 'int' from String "canary9f3a"` | **심는다** — 값이 실린다 |
| 깨진 JSON (닫히지 않은 문자열) | `JSON parse error: Unexpected end-of-input in VALUE_STRING` | **심지 않는다** — 본문 조각이 없다 |
| 미인증 401 | 우리 코드가 던진다 | **심을 자리가 없다** |

Spring Boot 가 Jackson 의 `INCLUDE_SOURCE_IN_LOCATION` 을 꺼 두기 때문에 깨진 JSON 경로에는 본문이
실리지 않는다. 거기 카나리를 두면 **어떤 뮤테이션으로도 red 를 못 내는 판정**이 된다 —
`unreachable-state-fixture-is-fake-green` 그대로다.

그래서 같은 `HttpMessageNotReadableException` 핸들러를 타면서 값이 실제로 실리는 **본문 타입 불일치**
경로를 테스트로 추가하고 카나리를 그리로 옮겼다. 깨진 JSON 은 정확 일치만 남기고, **왜 여기만
카나리가 없는지**를 KDoc 에 적었다 — 안 적으면 다음 사람이 같은 조사를 다시 한다. 미인증 401 도 같다.

### 카나리 상수는 양쪽에 리터럴로 적는다

한 곳에 두고 형제가 import 하면 그 한 벌이 갈려도 둘 다 초록이 된다. 위 인용한 계약과 같은 이유이고,
같은 파일들이 `WORKFLOW_INVALID_REQUEST` 를 이미 그렇게 다루고 있다.

## 검증

```bash
backend/gradlew -p backend :modules:project-workflow:test \
  --tests '*ValidatorControllerTest*' --tests '*PostActionControllerTest*'
backend/gradlew -p backend :modules:project-workflow:ktlintCheck
```

★ `| tail` 로 파이프하지 않는다 — 종료 코드가 `tail` 의 것이 된다
(`pipe-eats-exit-code-in-verification`). ★ 결과 XML 의 **신선도와 건수**를 함께 본다
(`lint-fails-first-leaves-stale-test-xml`).

**뮤테이션 (GREEN 선커밋 뒤).** `TransitionRuleFrameworkErrors` 의 메시지 자리에 `ex.message` 를
꽂아 **두 표면 6건이 함께 red** 인지 본다. 미커밋 원복은 소실이므로 반드시 커밋 뒤에 한다.

## 범위 밖

- `TODOS.md:1171` 「전환 규칙 표면에 남은 사본 5종」 — 별건 부채
- 부채 137·138 (판별식 표면) — PR #408 에서 닫았다
- 부채 139 (`ConfirmDialog`) — `apps/web` 표면이라 별건 PR
