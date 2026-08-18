# FR-NT-02 이메일 채널 (EmailChannelSender) — 스펙

> slug: fr-nt-02-email-webhook-channels
> BC: notification
> 작성: 2026-06-14
> 범위: 이메일 채널만 (Webhook은 전용 후속 FR로 분리 — plan ## 도메인 정리 참조)

## 배경

FR-NT-02 인앱 채널은 머지 완료(#126/#137). 발송 코어(`NotificationWorker` + `Notification` + `NotificationChannelSender` 추상)는 이미 채널-무관하게 동작한다. 이번 PR은 `NotificationChannelSender` 추상 위에 **이메일 채널 구현체 1종**을 얹는다. 워커·resolver·정책 평가기·스키마는 변경하지 않는다.

## 사용자 시나리오 (Given-When-Then)

- **S1 (이메일 발송 성공)**. Given 관리자가 어떤 이벤트에 대해 `channel=EMAIL` 알림 정책을 설정했고 수신자가 이메일을 가진 사용자다. When 그 이벤트가 발생한다. Then 수신자의 이메일 주소로 알림 메일이 발송되고, 해당 notifications 행이 `SENT`로 전환된다.
- **S2 (이메일 주소 부재)**. Given 수신자 사용자가 `users.email`이 비어있거나 사용자 행이 없다. When 이메일 알림을 발송한다. Then 메일을 보내지 않고 경고 로그를 남기며, notifications 행은 `PENDING`으로 남는다(`deliver()` best-effort — markSent 안 함).
- **S3 (SMTP 전송 실패)**. Given SMTP 서버가 일시적으로 응답하지 않는다. When 이메일 알림을 발송한다. Then 예외가 `deliver()`에서 흡수되어 경고 로그 + notifications 행 `PENDING` 유지(능동 재시도 없음 — pgmq dedup이 같은 알림 재발송을 막으므로 PENDING이 "미발송" 사실을 정확히 표현).
- **S4 (인앱 무영향)**. Given 정책이 `channel=IN_APP`이다. When 이벤트가 발생한다. Then `EmailChannelSender.supports(IN_APP)=false`라 이메일 경로를 타지 않고 기존 인앱 푸시만 동작한다(회귀 없음).

## 기능 요구사항 (FR)

- **FR-1**. `EmailChannelSender`는 `NotificationChannelSender`를 구현하고 `supports(channel) == (channel == Channel.EMAIL)`을 반환한다.
- **FR-2**. `send(notification)`은 `notification.recipientUserId`로 수신자 이메일을 조회한다(아래 FR-5 포트). 이메일이 있으면 `notification.title`을 제목, `notification.body ?: notification.title`을 본문으로 하는 메일을 발송한다. 발신자는 설정값 `bts.notification.email.from`을 사용한다(FR-7). 본문은 sender가 `notification.title`(+issueKey 컨텍스트) 기반으로 구성하며 워커의 `buildTitleBody`(body 항상 null)는 변경하지 않는다.
- **FR-7**. 발신 주소는 `bts.notification.email.from` 프로퍼티로 구성한다(기본값 예: `no-reply@bts.local`). 미설정 시 부팅은 깨지지 않고 기본값 사용.
- **FR-3**. 이메일 주소 조회 실패(없음/blank)면 메일 발송을 생략하고 경고 로그를 남긴 뒤 **예외를 던져** `deliver()`가 `PENDING`을 유지하게 한다(거짓 `SENT` 박제 방지). 발송된 적 없음을 상태로 정확히 표현.
- **FR-4**. SMTP 전송 실패 시 예외를 전파한다(`deliver()`가 흡수 → 경고 로그 + `PENDING`). 자체 재시도 루프 없음.
- **FR-5**. `UserLookupPort`(shared-kernel)에 `findEmailById(userId: UUID): String?`를 **default 구현(기본값 `null`)으로 추가**한다. identity-access `UserLookupAdapter`가 `SELECT email FROM users WHERE id = ?`로 override한다. learnings `interface-extension-default-method` 준수 — 추상 메서드 추가 금지(기존 인라인 fake ~35개 컴파일 보호), default는 fail-safe(이메일 미조회 → FR-3 경로).
- **FR-6**. `JavaMailSender`는 부재 가능성을 고려해 안전하게 주입한다(아래 NFR-2). 메일 인프라 미구성이어도 모듈/앱 부팅은 실패하지 않는다.

## 비기능 요구사항 (NFR)

- **NFR-1 (BC 격리)**. notification은 identity-access를 직접 import하지 않는다. 이메일 조회는 shared-kernel `UserLookupPort` 경유. ArchUnit 통과.
- **NFR-2 (부팅 견고성)**. `EmailChannelSender`는 `JavaMailSender` 빈이 없어도 빈 생성·앱 부팅이 깨지지 않아야 한다(learnings `profile-scoped-bean-boot-failure`). → JavaMailSender 빈을 **우리가 `@Configuration`에서 무조건 생성**(host 프로퍼티 기본값 제공)하거나 `ObjectProvider<JavaMailSender>`로 지연 주입하고, 부재 시 FR-3과 동일하게 경고+예외(PENDING).
- **NFR-3 (테스트)**. 단위 테스트(`JavaMailSender`/`UserLookupPort` mock)로 FR-1~4 로직 검증 + Testcontainers MailHog 통합 테스트로 실제 SMTP 발송→수신 검증(product §2.2 D5 정본).
- **NFR-4 (보안)**. 메일 본문/제목은 기존 `buildTitleBody`가 생성한 이슈키+이벤트 기반 문자열만 사용(사용자 입력 직접 삽입 없음). 이메일 주소는 로그에 마스킹 또는 userId만 로깅(PII 누출 방지).
- **NFR-5 (한국어 인코딩)**. 제목/본문이 한국어다(예: "ATLAS-42 에서 멘션되었습니다"). `SimpleMailMessage`는 charset을 지정하지 않아 한국어가 깨질 수 있으므로 **`MimeMessage` + `MimeMessageHelper`(UTF-8)** 로 발송한다. 통합 테스트에서 MailHog 수신 제목의 한국어가 보존되는지 검증.
- **NFR-6 (워커 블로킹 상한)**. 워커는 단일 스레드 `@Scheduled` 폴링(vt 예산 ~50ms/건). 느린 SMTP가 후속 수신자·인앱 푸시를 지연시키지 않도록 `JavaMailSender`에 SMTP connection/read 타임아웃을 짧게 설정(`mail.smtp.connectiontimeout`/`mail.smtp.timeout`, 예: 5s). 타임아웃 초과는 예외→`deliver()` PENDING(FR-4).
- **NFR-7 (상태 일관)**. `NotificationStatus.FAILED`가 enum에 존재하나 현재 `deliver()`는 SENT/PENDING만 사용한다(워커 수정 금지 제약). 이메일도 동일 — 실패는 `PENDING`으로 남기고 `FAILED`를 새로 쓰지 않는다(인앱과 일관, deliver() 변경 회피).

## API 인터페이스 (REST)

- 신규 REST 엔드포인트 **없음**. 발송은 `NotificationWorker`(pgmq consumer) 내부 경로.

## 데이터 모델 변경

- 스키마/마이그레이션 변경 **없음**. `notifications.channel`은 이미 일반 컬럼(IN_APP/EMAIL/WEBHOOK 공통, V402). `users.email`(V001) 재사용. init_codegen 미러 불요(스키마 무변경).

## 의존성 변경

- notification 모듈에 메일 발송 라이브러리 추가. **권장(plan에서 확정)**: 모듈의 "필요한 컴포넌트만 직접 선언" 컨벤션에 맞춰 `org.springframework:spring-context-support`(JavaMailSenderImpl) + Jakarta Mail 구현(`org.eclipse.angus:angus-mail`) + 우리가 정의한 `@Configuration` JavaMailSender 빈(부팅 견고성, NFR-2). 대안: `spring-boot-starter-mail`(간결하나 autoconfig 의존+full starter 회피 컨벤션과 충돌). 신규 의존성이므로 절대규칙 #17 — Maxi 승인 대상(게이트 1에서 확인).
- 테스트: MailHog는 `org.testcontainers:testcontainers`의 `GenericContainer`로 충분(전용 TC 모듈 불요). 이미지 `mailhog/mailhog:v1.0.1`, SMTP 1025/HTTP API 8025. `@DynamicPropertySource`로 `spring.mail.host`/`port` 주입, HTTP API `/api/v2/messages`로 수신 검증.

## 엣지 케이스

- **E1**. 수신자 이메일 없음 → 발송 생략 + PENDING(S2/FR-3).
- **E2**. SMTP 다운 → PENDING(S3/FR-4), 재시도 없음.
- **E3**. JavaMailSender 빈 부재(메일 미구성 환경) → 부팅 정상 + 발송 시 경고+PENDING(NFR-2).
- **E4**. `notification.body == null` → 본문에 `title` 사용(FR-2).
- **E5**. 같은 이벤트 재전달 → `insertIfAbsent`가 false → send 자체 호출 안 됨(기존 멱등, 회귀 없음).
- **E6 (회귀)**. 인앱 채널 알림은 `supports(EMAIL)=false`로 이메일 sender를 타지 않음(S4). 기존 인앱 E2E/통합 그린 유지.

## 제약 조건

- 워커(`NotificationWorker`)·`EventRecipientResolver`·`NotificationPolicyEvaluator`·스키마 **수정 금지**(sender 빈 추가 + 포트 default 메서드 + 메일 설정만).
- `UserLookupPort`는 default 메서드로만 확장(추상 추가 금지).
- Webhook 관련 코드/스키마 추가 금지(별도 FR).

## 측정 가능한 완료 기준

1. `EmailChannelSenderTest`(단위) — FR-1~4 + supports 매트릭스 그린.
2. MailHog Testcontainers 통합 테스트 — `channel=EMAIL` 알림이 실제 SMTP로 발송돼 MailHog HTTP API에서 수신 확인 + notifications 행 `SENT` 전환(S1). 이메일 부재 시 PENDING(S2).
3. `UserLookupAdapter`에 `findEmailById` override + identity-access 통합 테스트(실 users 행 조회) 그린.
4. ArchUnit BC 격리 + ktlint/detekt(신규 파일 baseline 밖) 그린.
5. 기존 notification 인앱 통합/단위 테스트 회귀 없음.
6. product §2.2 D-단계 이메일 부분 + ADR 보완 동기화, `scripts/verify-master-plan.sh` 통과, FR 카운트 불변(122).

## Brainstorming Check

✅ 통과 (1회, 직접 적대적 점검 — office-hours/brainstorming sub-skill은 완료-FR 후속이라 생략, 메모리 `bts-spec-office-hours-mismatch`).

발견·보강한 gap 4건.
- 한국어 제목 인코딩 → `MimeMessageHelper` UTF-8 (NFR-5). SimpleMailMessage 사용 시 한글 깨짐 위험.
- 발신 주소 누락 → `bts.notification.email.from` 설정 (FR-7).
- 느린 SMTP의 워커 블로킹 → SMTP 타임아웃 상한 (NFR-6).
- `FAILED` 상태 미사용 정책 명시 → PENDING 유지(워커 수정 금지, 인앱 일관) (NFR-7).

미해결/Maxi 확인 대상.
- 신규 의존성(mail 라이브러리) — 절대규칙 #17, 게이트 1에서 승인.
- 의존성 선택(spring-context-support+angus-mail vs spring-boot-starter-mail) — plan/eng-review에서 확정.
