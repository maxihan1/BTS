# 잠복 전역 advice 의 `instance` 자동채움 봉합 (N3)

> slug: global-advice-instance-token-leak
> type: backend
> agent: backend-engineer
> primary_bc: issue-tracking
> 생성: 2026-07-26

## Brief

경로토큰 유출 표면 4건(N1~N4) 중 **N3**. N1 ✅#311 · N2 ✅#312 완료, N4 는 Maxi 정책 결정 선행.

`ProjectArchivedExceptionHandler` 가 **선택자 없는 전역 `@RestControllerAdvice`** 이면서 `ProblemDetail` 을
`instance` 미설정으로 반환한다. Spring 의 `RequestResponseBodyMethodProcessor` 는 `instance == null` 이면
**요청 URI 로 자동 채우므로**(#310 이 실측 확정한 기전) 경로에 원문 토큰이 있는 요청에서 유출이 된다.

## 도메인 정리

### BC / 영향 범위

- **BC.** `issue-tracking` 단일. `IcalFeedController` 4번째 봉인은 identity-access 라 **별도 PR**(BC 격리)
- **영향 엔티티.** 없음. 마이그레이션 0 · 신규 의존성 0 · 프론트 0 · FR 129 불변
- **영향 파일.** `ProjectArchivedExceptionHandler.kt` 1개 + 테스트

### ★ 착수 전 반증 — 메모리 기록 3곳 정정

| 기존 기록 | 실측 결과 |
|---|---|
| "선택자 없는 전역 advice가 위험" | ✅ 맞음. **정밀 grep(`^\s*@(Rest)?ControllerAdvice$`)으로 전 레포 정확히 2개.** 넓은 grep 은 KDoc 본문의 언급까지 잡아 12개로 부풀려 보였다 |
| "#310 **완전 동형**(catch-all)" | ⚠️ **부정확.** catch-all 이 아니라 `@ExceptionHandler(ProjectArchivedException::class)` **단일 타입**만 처리한다. 그 KDoc 이 이유를 명시 — `ProjectArchiveGuard` 가 던지는 cross-cutting 예외라 `assignableTypes` 로 좁힐 대상이 없다. **동형인 것은 `instance` 미설정 하나뿐** |
| "도달 불가" | ✅ 맞음. **단 구조적 불가능이 아니라 "지금 호출 경로가 없음"** |

### 전역 advice 전수 (정밀 grep)

| 파일 | 반환형 | 유출 통로 |
|---|---|---|
| `issue-tracking :: ProjectArchivedExceptionHandler.kt:38` | `ProblemDetail`, `instance` 미설정 | **이 작업의 대상** |
| `project-workflow :: WorkflowExceptionHandler.kt:28` | 손수 만든 `ErrorResponse` | URI 필드 자체가 없어 **통로 아님** |

### 경로토큰 4경로의 방어 상태 (실측)

| 경로 | 컨트롤러-로컬 catch-all | 전역 advice 도달 |
|---|---|---|
| `/api/v1/public/dashboards/{token}` | ✅ 있음 (#310) | 구조적 불가 |
| `/api/v1/webhooks/git/{token}` | ✅ 있음 | 구조적 불가 |
| `/api/v1/automation/webhooks/{token}` | ❌ 없음 (3핸들러, catch-all 0) | **경로 열림** |
| `/ical/feed/{token}.ics` | ❌ 없음 (핸들러 0) | **경로 열림** |

> Spring 은 컨트롤러-로컬 `@ExceptionHandler` 를 **항상 먼저** 찾는다. advice 의 `@Order(HIGHEST_PRECEDENCE)`
> 는 advice 들 사이의 순서일 뿐 컨트롤러-로컬을 이기지 못한다(ADR #310 §D1 이 의존하는 성질).

### 왜 지금은 안전한가 (발동조건 부재)

`ProjectArchivedException` 은 `ProjectArchiveGuard.kt:49·62·78` 세 곳에서만 던져지고, 호출자는 전부
issue-tracking 의 **쓰기 서비스**다. 위 표의 뒤 2경로는 그 코드를 타지 않는다 —

- `AutomationWebhookController.receive` — 동기 경로가 **조회 + `enqueuer.enqueue` 만**(그 KDoc §동기 경로,
  NFR2 < 200ms). 룰 실행은 pgmq 비동기 워커라 HTTP 응답과 무관
- `IcalFeedController.feed` — `calendarFeedService.generateFeed` **읽기 전용**

**즉 배선은 완료돼 있고 발동조건만 없다.** 이 advice 에 `@ExceptionHandler` 를 하나 더 붙이거나
(자연스러운 변경) 위 두 컨트롤러가 쓰기를 하게 되면 즉시 유출 경로가 된다.

### 새 용어

없음.

### 기존 결정과의 관계

| ADR | 관계 |
|---|---|
| `2026-07-25-…-instance-sanitization` (#310) | **원칙 출처.** §D2 "재발 방지가 테스트가 아니라 구조로" · §잔여위험이 이 클래스를 지목 |
| `2026-07-26-authenticated-error-path-token-leak` (#312) | **직전 형제.** §잔여위험 1 에 N3 로 등재됨 |

**충돌 없음.**

### 워크플로우 편차

`grill-with-docs` 미호출 — 신규/변경 엔티티 0, 도메인 모델 무영향.

## 스펙

축약 체인 적용(게이트 D2 옵션 A 연장). `office-hours`·`plan-*-review` 5종 생략.

### 수용 기준

1. 전역 advice 가 반환하는 `ProblemDetail` 의 `instance` 가 **요청 URI 로 자동 채워지지 않는다**
2. 새 전역(선택자 없는) advice 가 추가되면 **테스트가 실패**한다 — 미분류=실패 (#310 §D4 패턴)
3. 409 매핑·`errorCode`·`detail` 등 기존 응답 계약 무변경

### 엣지 케이스

| # | 케이스 | 처리 |
|---|---|---|
| EC-1 | 이 advice 에 `@ExceptionHandler` 추가 | `instance` 가 이미 고정돼 있으므로 새 핸들러도 헬퍼를 지나면 안전 |
| EC-2 | 새 전역 advice 신설 | 수용기준 2 의 열거 테스트가 미등재로 실패 |
| EC-3 | `WorkflowExceptionHandler`(전역, `ErrorResponse`) | 통로 아님. 열거 테스트의 기대 표본에 등재만 |

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
