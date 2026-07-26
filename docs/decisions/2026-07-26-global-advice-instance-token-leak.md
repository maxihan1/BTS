# ADR: 잠복 전역 advice 의 `instance` 자동채움 봉합 — 고정값 + 열거 봉인

> 날짜: 2026-07-26
> 상태: 채택 (Accepted)
> 관련 FR: 없음 — **FR 신설 0 · 카운트 129 불변** (보안 위생, 기능 변경 0)
> 선행 ADR: `2026-07-25-public-dashboard-error-instance-sanitization.md` (#310) — 기전·원칙 출처
> 형제 ADR: `2026-07-26-nginx-access-log-token-masking.md` (#311, N1) · `2026-07-26-authenticated-error-path-token-leak.md` (#312, N2)
> Plan: `docs/plans/2026-07-26-global-advice-instance-token-leak.md`
> PR: #313

## 맥락 (Context)

경로토큰 유출 표면 4건 중 **N3**. #312 ADR 잔여위험 1 에 등재된 항목이다.

`ProjectArchivedExceptionHandler` 는 **선택자 없는 전역 `@RestControllerAdvice`** 이면서
`ProblemDetail` 을 `instance` 미설정으로 반환한다. Spring MVC 의 `RequestResponseBodyMethodProcessor` 는
`instance == null` 이면 **요청 URI 로 자동으로 채운다**(#310 이 실측 확정한 기전).

### 착수 전 반증 — 기존 기록 3곳을 정정했다

| 기존 기록 | 실측 |
|---|---|
| "선택자 없는 전역 advice 가 위험" | ✅ 맞음. 단 **정밀 grep(`^\s*@(Rest)?ControllerAdvice$`)으로 전 레포 정확히 2개.** 넓은 grep 은 KDoc 본문의 언급까지 잡아 12개로 부풀려 보였다 |
| "#310 **완전 동형**(catch-all)" | ⚠️ **부정확.** catch-all 이 아니라 `@ExceptionHandler(ProjectArchivedException::class)` **단일 타입**만 처리한다. 그 KDoc 이 이유를 명시 — `ProjectArchiveGuard` 가 던지는 cross-cutting 예외라 `assignableTypes` 로 좁힐 대상이 없다. **동형인 것은 `instance` 미설정 하나뿐** |
| "도달 불가" | ✅ 맞음. **단 구조적 불가능이 아니라 "지금 호출 경로가 없음"** |

### 전역 advice 전수 (정밀 grep)

| 파일 | 반환형 | 유출 통로 |
|---|---|---|
| `issue-tracking :: ProjectArchivedExceptionHandler.kt:38` | `ProblemDetail`, `instance` 미설정 | **본 결정의 대상** |
| `project-workflow :: WorkflowExceptionHandler.kt:28` | 손수 만든 `ErrorResponse` | URI 필드 자체가 없어 **통로 아님** |

### 경로토큰 4경로의 방어 상태 (실측)

| 경로 | 컨트롤러-로컬 catch-all | 전역 advice 도달 |
|---|---|---|
| `/api/v1/public/dashboards/{token}` | ✅ 있음 (#310) | 구조적 불가 |
| `/api/v1/webhooks/git/{token}` | ✅ 있음 | 구조적 불가 |
| `/api/v1/automation/webhooks/{token}` | ❌ 없음 (3핸들러, catch-all 0) | **경로 열림** |
| `/ical/feed/{token}.ics` | ❌ 없음 (핸들러 0) | **경로 열림** |

Spring 은 컨트롤러-로컬 `@ExceptionHandler` 를 **항상 먼저** 찾는다. advice 의
`@Order(HIGHEST_PRECEDENCE)` 는 advice 들 사이의 순서일 뿐 컨트롤러-로컬을 이기지 못한다
(ADR #310 §D1 이 의존하는 성질).

### 왜 지금은 안전한가 — 그리고 왜 그래도 고치는가

`ProjectArchivedException` 은 `ProjectArchiveGuard.kt:49·62·78` 에서만 던져지고 호출자는 전부
issue-tracking **쓰기 서비스**다. 위 표의 뒤 2경로는 그 코드를 타지 않는다 —
`AutomationWebhookController.receive` 는 동기 경로가 **조회 + `enqueuer.enqueue` 만**(룰 실행은 pgmq
비동기 워커), `IcalFeedController.feed` 는 **읽기 전용**이다.

**배선은 완료돼 있고 발동조건만 없다.** 이 advice 에 `@ExceptionHandler` 를 하나 더 붙이거나
(cross-cutting 예외가 늘면 자연스러운 변경) 위 두 컨트롤러가 쓰기를 하게 되면 즉시 유출 경로가 된다.
#310 §D2 가 세운 "재발 방지를 테스트가 아니라 구조로" 원칙이 정확히 이런 잠복 배선을 겨냥한다.

### 실측 (추정 아님)

standalone MockMvc 로 경로 세그먼트에 토큰이 실린 요청을 재현했다.

```
"instance":"/archive-test/archived/n3-path-token-abcdef0123456789"
```

## 결정 (Decision)

### D1. `instance` 를 **발생 UUID 로 명시**한다 — 요청 URI 가 아니다

`instance = "urn:uuid:<UUID.randomUUID()>"`, 그리고 **같은 값을 서버 로그에** 싣는다
(`PROJECT_ARCHIVED_409 occurrenceId=<uuid> …`).

> **게이트 2 에서 Maxi 가 선택한 형태다.** 초안은 고정 문자열 `"/problems/project-archived"` 였고
> 유출은 그것으로도 막혔다. 그러나 리뷰(S2)가 지적했듯 고정값은 RFC 9457 §3.1.5 의
> "특정 **발생**을 식별하는 URI" 시맨틱을 잃고 `type` 의 경로 성분과 사실상 중복이었다.
> 발생 UUID 는 **유출 위험 0**(요청 정보와 무관한 난수)을 유지하면서 시맨틱을 충족하고,
> **#310 이 `instance` 를 정화하며 포기했던 진단성을 되찾는다** — 사용자가 붙여넣은 오류 응답
> 하나로 서버 로그를 곧장 특정할 수 있다. 지금까지 그 연결고리는 아예 없었다.
>
> **상관관계 자체가 계약이다.** 로그와 응답 중 한쪽만 바꾸면 둘 다 형식은 맞는 채로 값어치가
> 조용히 사라지므로, `ListAppender` 로 로그를 잡아 응답 UUID 와의 일치를 못 박는다
> (레포 관례 — automation·identity-access 선례 동형).

**왜 요청 경로를 살려 쓰지 않는가.** 이 advice 는 전역이라 고정 엔드포인트가 없다. 요청 URI 를 쓰려면
"비밀 경로인가"를 가리는 판별식이 필요한데, 그 처방은 ADR #310 §D1 이 기각했다 — 목록은 새 비밀 경로가
생길 때마다 갱신돼야 하고 빠뜨리면 조용히 샌다. 실제로 #311(N1)에서 초기 열거가 SPA 라우트
`/dashboards/shared/{token}` 을 누락했다.

**진단성 손실은 없다.** 이 핸들러는 이미 `log.info("PROJECT_ARCHIVED_409 message='{}'", ex.message)` 로
식별자를 서버 로그에 남기고, 요청 경로는 접속 로그(#311 로 토큰만 마스킹된 상태)가 담당한다.
요청자는 자기가 부른 URL 을 안다.

기각한 대안.
- **(a) 컨트롤러-로컬 catch-all 을 4경로에 각각 추가** — `IcalFeedController` 봉인은 별도 BC(identity-access)라
  이 PR 범위 밖이고, 무엇보다 **advice 쪽 구멍은 그대로 남는다**. 컨트롤러가 늘 때마다 반복된다.
- **(b) advice 에 `basePackages` 를 주어 스코프를 좁힌다** — 그 KDoc 이 명시한 설계 의도를 깬다.
  `ProjectArchivedException` 은 Version·Component·CustomField·Issue·Attachment·Worklog·Link·Move 등
  다수 컨트롤러에서 표면화되며, 좁히면 누락된 컨트롤러가 **500(내부 누출)** 로 샌다. 지금이 더 안전하다.

### D2. 재발 방지 — **실효 광역** advice 전수 열거 + `instance` 강제 (3축)

`GlobalControllerAdviceSealTest` — **`:modules:app` 조립 테스트**. `com.bts` 와 `com.atlas.bts` 를
둘 다 스캔해 `@ControllerAdvice`(메타 어노테이션 포함) **46개**를 전수로 본다.

| 축 | 내용 |
|---|---|
| 1 | **실효 광역** advice 전수 열거 → 기대 표본 미등재면 실패 |
| 2 | 광역 advice 의 `@ExceptionHandler` 중 `ProblemDetail` 반환 메서드는 **반드시 `setInstance` 호출** |
| 3 | vacuous 방어 — advice 총 개수 하한(35, 실측 46) |

**★ "선택자 유무"가 아니라 실효 범위로 판정한다 (리뷰 C1 — 뮤테이션으로 실증).**
초안은 네 속성이 **전부 비었는가**로 광역을 판정했다. 그 판정은 Spring 의
`HandlerTypePredicate.hasSelectors()` 와 정확히 일치하지만 **막으려는 위험과는 어긋난다** —
다음 둘은 선택자가 "있어서" 통과하면서 실효는 전역이다.

- `@RestControllerAdvice(annotations = [RestController::class])` — 레포의 **모든** `@RestController` 에 붙는다
- `@RestControllerAdvice(basePackages = ["com.bts"])` — 루트 접두사라 사실상 전체

`isEffectivelyBroad` 는 (1) 선택자 전무 (2) `annotations` 에 컨트롤러 스테레오타입 (3) `basePackages` 에
루트 접두사 — 셋 중 하나면 광역으로 본다.

**★ 축 2 가 없으면 "등재만 하고 처방은 빼먹는" 통로가 남는다 (리뷰 C3).**
축 1 은 등재를 강제할 뿐 처방 적용을 강제하지 않는다. 축 2 가 ArchUnit 메서드 호출 검사로
`ProblemDetail.setInstance` 호출을 강제한다(같은 클래스의 `problem()` 헬퍼 경유도 허용 —
automation 두 웹훅 컨트롤러가 그 형태다).

**★ 스코프는 조립 전역 (리뷰 C2).** 초안은 issue-tracking 만 스캔했다. 그런데 위험 모델은
`BtsApplication` 이 두 루트를 **둘 다** 스캔한다는 것이므로 **조립 전역**이다 — 가드 범위가 위험
모델보다 좁으면 46개 중 23개만 지켜진다. BC 격리는 프로덕션 의존 규칙이고, 조립 모듈은 이미 9 BC 를
전부 물고 있으며 형제 PR #312 도 조립 레벨에 보안 테스트를 두었다. `archunit` 은 다른 BC 가 이미 쓰는
버전의 **조립 레벨 재사용**이다(신규 의존성 아님).

- **개수를 세지 않고 열거한다** — `guard-handler-matrix-blindfold` 의 교훈
- **FQN** 으로 비교한다 — 단순명은 패키지 다른 동명 클래스를 구분 못 한다
- `containsExactlyInAnyOrder` — 순서 불일치로 깨지면 실패 메시지("미등재")와 원인이 어긋난다

## 검증 (Verification)

**뮤테이션 6종.** 전부 커밋된 기준선 위에서 수행하고 원복했다.
클래스명을 명시한다 — `SealTest` 와 `ProjectArchivedExceptionHandlerTest` 는 **서로 다른 것**을 지킨다.

| # | 뮤테이션 | red 가 된 축 | 판정 |
|---|---|---|---|
| 1 | `instance` 고정 제거 | `ProjectArchivedExceptionHandlerTest` N3 축 | 봉합 판별력이 그 한 줄에 붙음 |
| 2 | `instance` 고정 제거 (봉인 재설계 **후**) | `SealTest` 축 2 | 처방 누락을 구조적으로 잡음 (리뷰 C3 해소) |
| 3 | **미등재 무선택자** advice 신설 | `SealTest` 축 1 단독 | 추가 방향 재발 탐지 |
| 4 | **`annotations = [RestController]`** advice 신설 | `SealTest` 축 1 단독 | **초안이 놓쳤던 형태**(리뷰 C1)를 이제 잡음 |
| 5 | **제대로 좁힌** advice 신설 (`basePackages = ["com.bts.issue.nowhere"]`) | **없음 — 전부 green** | **과잉발동 아님** |
| 6 | 스캔 패키지 파괴 | `SealTest` 축 1·3 함께 | vacuous 방어 작동 |
| 7 | 로그의 UUID 를 응답과 **어긋나게** | `ProjectArchivedExceptionHandlerTest` 상관관계 축 | **D1 의 상관관계가 load-bearing** — 둘 다 형식은 맞지만 잡힌다 |

**5 가 판별력의 핵심 대조군이다.** 3·4 와 5 가 모두 red 였다면 룰이 과잉결합된 것이고,
4 가 green 이었다면(= 초안 상태) 룰이 위험을 놓치는 것이다. 둘 다 아니다.

**회귀.** `:modules:app:test` 9클래스 44테스트 · `:modules:issue-tracking:test` 306클래스 3,114테스트
— **전량 통과**(실패 0 · 에러 0 · 스킵 0), XML 집계.

## 결과 (Consequences)

- **기능 변경 0.** 상태코드 409 · `errorCode` · `detail` · `title` · `type` 전부 불변.
  바뀌는 것은 `instance` 필드 값 하나이며, 기존에는 그 필드가 요청 URI 였다.
- **소비처 0건.** `apps/web/src` 에 에러 응답의 `instance` 를 읽는 코드가 없다(#310 이 확인한 사실 그대로).
- **마이그레이션 0 · 신규 의존성 0 · cross-BC 변경 0 · 프론트 0 · FR 카운트 129 불변.**
- **`instance` 를 명시하는 지점이 3 → 4개**가 되어 패턴이 굳는다.
- **새 전역 advice 는 이제 의식적 결정을 요구한다** — 표본 등재 없이는 초록이 되지 않는다.

### 잔여 위험

1. **`IcalFeedController` 4번째 봉인 미완** (identity-access BC — 별도 PR). 그 컨트롤러는
   `@ExceptionHandler` 가 0개라 자기 BC 의 예외도 `/error` 로 넘긴다. #312 가 `include-path: never` 로
   전역 차단했으므로 **실제 유출은 없다**. 구조적 비대칭만 남는다(#312 ADR 잔여위험 5 와 동일 항목).
2. **`instance` 미설정은 이 advice 만의 문제가 아니다.** 레포의 `ProblemDetail` 생성 지점 **51곳(46파일)**
   중 `instance` 를 설정하는 곳은 4개뿐이다. 나머지는 **스코프가 좁아** 비밀 경로에 붙을 수 없어 안전하고
   봉인 축 2 도 광역 advice 만 본다. 구조적으로는 `problem()` 헬퍼가 shared-kernel 공용이 아니라
   곳곳에 복제된 상태다(#310 이 지적한 사실). 공용 헬퍼 추출은 46파일 cross-BC 리팩터링이라 **별도 트랙**.
3. **~~`instance` 값이 RFC 9457 시맨틱과 다르다~~ → 해소.** 게이트 2 에서 `urn:uuid` 상관관계 방식을
   채택해 §D1 에 반영했다. 남는 것은 **선행 3지점과의 비대칭** — `PublicDashboardController` ·
   automation 두 웹훅 컨트롤러는 여전히 고정 엔드포인트 경로를 `instance` 로 쓴다(단일 엔드포인트라
   시맨틱이 살아 있어 틀린 것은 아니지만, 상관관계 이득은 못 누린다). 그 3지점의 UUID 전환은 별도 PR.
4. **다른 BC 의 광역 advice 전환.** 축 2 는 `ProblemDetail` 반환만 본다. `WorkflowExceptionHandler` 가
   손수 만든 `ErrorResponse` 를 `ProblemDetail` 로 전환하면 그때 축 2 가 발동해 `instance` 를 요구한다
   — 즉 이 전환은 봉인이 자동으로 잡는다.
5. **교차모델 검증 부재.** `codex` CLI 미설치. 단 `superpowers:code-reviewer` 독립 리뷰를 적용했고
   **그 리뷰가 C1(봉인의 실효 구멍)을 잡았다** — 구현자 단독이었으면 "봉인을 세웠다"고 믿고 넘어갔을
   결함이다. #312 에 이어 두 번째로 독립 리뷰가 봉인 자체의 결함을 잡았다.

## 함정 기록 (다음 사람을 위해)

**`@(Rest)?ControllerAdvice` 를 넓은 grep 으로 세면 KDoc 언급이 섞인다.** 이 레포는 KDoc 에
"이 클래스는 `@RestControllerAdvice` 가 아니다" 같은 설명 문장을 자주 쓴다. 실제 어노테이션만 세려면
**줄 시작 앵커**(`^[[:space:]]*@`)를 걸어야 한다. 앵커 없이 세면 2개가 12개로 보인다 —
[[orchestrator-instruction-counts-are-blindfolds]] 의 변종으로, **틀린 개수가 조사 방향을 바꾼다.**
