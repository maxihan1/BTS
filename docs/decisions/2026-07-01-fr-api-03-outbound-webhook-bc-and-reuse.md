# ADR: FR-API-03 — 구독형 아웃바운드 Webhook의 BC 배치 + FR-NT-05 인프라 재사용

> 날짜: 2026-07-01
> 상태: Accepted (Maxi 게이트 확정 — 옵션 A)
> 관련 FR: FR-API-03 (Webhook 외부 시스템 통지), search-export-import BC §5.3
> 관련 ADR: [2026-06-14-fr-nt-05-webhook-dispatch-ssrf.md](2026-06-14-fr-nt-05-webhook-dispatch-ssrf.md) (FR-NT-05 전이 webhook + SSRF), [2026-06-30-fr-api-01-cursor-pagination-envelope.md](2026-06-30-fr-api-01-cursor-pagination-envelope.md) (REST API 표준 — 선행)
> 관련 PR: (FR-API-03 백엔드 PR)

## 맥락

FR-API-03은 **구독형 범용 아웃바운드 Webhook**이다. 외부 시스템이 webhook을 등록(URL + secret + event_filter)하면, BTS가 매칭되는 이벤트를 HMAC-SHA256 서명과 함께 HTTP로 전송하고, 발송 결과를 이력으로 추적하며, 실패 시 재시도 + circuit breaker로 보호한다.

이미 완료된 **FR-NT-05**(notification BC)가 아웃바운드 webhook 발송 인프라를 보유한다 — 단 **의도적으로 최소**였다.

| 항목 | FR-NT-05 (기존) | FR-API-03 (신규) |
|---|---|---|
| 트리거 | 워크플로우 전이 전용(post-action에 URL 박음) | 이벤트 구독(event_filter) |
| URL 등록 | 없음(YAML/post-action config) | 구독 CRUD + 관리 UI |
| 서명 | 없음 | HMAC-SHA256 |
| 발송 이력 | 없음(pgmq 생명주기만) | `webhook_deliveries` 테이블 |
| 실패 보호 | pgmq 재전달 | 재시도 + **circuit breaker** |
| SSRF 가드 | `WebhookUrlValidator` | (재사용) |
| HTTP 클라이언트 | RestClient + `Redirect.NEVER` | (재사용) |

FR-NT-05 ADR은 SSRF 가드(`WebhookUrlValidator`)와 HTTP 클라이언트 패턴을 명시적으로 **"향후 다른 BC의 아웃바운드 HTTP가 참조할 재사용 결정"**이라 선언했고, allowlist/관리 UI는 "PR2 범위 초과(현재 webhook 사용처 0건이라 과설계)"로 기각했다 — **그 인프라가 곧 FR-API-03**이다.

BTS의 BC 격리 원칙(한 PR = 한 BC, 다른 BC는 이벤트 발행만, 직접 import 금지)상 다른 BC의 코드를 직접 import할 수 없으므로, "재사용"은 곧 "코드를 어디에 둘지"의 결정과 묶인다.

## 결정

### D1. BC 배치 = search-export-import (FR 소속 BC 존중)

FR-API-03의 도메인(OutboundWebhook 구독모델, HMAC 서명, 발송 이력, circuit breaker, 디스패치 워커)은 **search-export-import BC**(`com.bts.search.webhook` 패키지 후보)에 구현한다. FR의 논리·물리 소속이 일치한다.

대안 기각.
- **옵션 B(notification BC, 논리≠물리 — FR-SR-01 선례)**: 인프라 직접 재사용은 편하나 webhook 전체가 notification BC 책임이 되고, FR-NT-05 흡수 시 범위가 확대된다. search BC가 webhook을 소유하지 않게 됨.
- **옵션 C(인프라 복제)**: SSRF 가드 같은 보안 코드를 두 곳에 복제하면 한쪽만 패치될 때 회귀 위험. 기각.

### D2. 재사용 = SSRF 가드 + HTTP 클라이언트를 shared-kernel로 추출

`WebhookUrlValidator`(SSRF)와 아웃바운드 HTTP 클라이언트 설정(`WebhookHttpClientConfig` 상당)을 notification BC에서 **shared-kernel `com.bts.shared.http`**로 추출한다.
- 추출 대상은 cross-BC 의존이 없는 순수 유틸(`InetAddress`/`URI`/`RestClient`)이라 안전하게 이동 가능.
- notification BC의 FR-NT-05 코드는 추출된 shared 버전을 import하도록 리팩터링한다.
- **BC 경계 1회 교차(문서화된 예외)**: 이 PR이 notification BC 코드를 건드리는 것은, 공유 인프라 추출이라는 본질상 불가피하다(FR-MV-01 D6/D7이 view-layer를 cross-BC 패치한 것과 동류). plan §리스크에 명시하고 reviewer가 사유를 즉시 파악하도록 한다.
- 추출 후 보안 로직(SSRF 차단 리스트)은 **단일 출처** — 한 번 패치하면 전이 webhook + 범용 webhook 양쪽에 적용.

### D3. FR-NT-05 전이 webhook은 그대로 유지 (흡수하지 않음)

FR-NT-05의 전이 전용 webhook(post-action)은 이미 완성·머지되어 운영 중이다. FR-API-03의 구독모델로 흡수/리팩터링하지 **않는다**(범위 확대·회귀 위험 회피). 두 경로가 공존한다.
- notification BC: "이 전이가 일어나면 이 URL 호출"(post-action)
- search-export-import BC: "이벤트 타입 구독 → HMAC 서명 발송 → 이력/재시도/circuit breaker"

향후 통합(전이 webhook을 구독모델 위에 재구축)은 별도 결정 사안.

### D4. FR-API-03가 추가하는 신규 역량

- **구독 모델**: `outbound_webhooks(url, secret_encrypted, event_filter, ...)` — event_filter로 관심 이벤트 선택.
- **HMAC-SHA256 서명**: 수신자가 페이로드 위변조를 검증하도록 서명 헤더 부착. secret은 암호화 저장(`secret_encrypted`).
- **발송 이력**: `webhook_deliveries(status, response_code, ...)` — 발송 시도/결과 추적, 관리 UI에 노출.
- **circuit breaker**: 특정 webhook이 연속 실패하면 일시적으로 발송 차단(외부 장애가 워커를 잠식하지 않도록).
- **관리 REST API**: FR-API-01/02가 확립한 페이지네이션·에러 봉투 표준을 따름.

## 알려진 한계 / 경계 (수용)

- **TOCTOU / DNS rebinding**: 추출된 `WebhookUrlValidator`가 FR-NT-05와 동일한 한계를 승계(검증 시점≠연결 시점). admin/등록 URL 기준 수용, 후속 강화 대상.
- **at-least-once**: webhook 중복 전송 가능. 수신자 멱등 책임 + HMAC 서명으로 진위 검증.
- **secret 암호화 키 관리**: `secret_encrypted` 복호화 키 관리 정책은 spec에서 기존 암호화 인프라(있으면) 재사용 여부 확정.

## 영향 / 후속

- shared-kernel `com.bts.shared.http` 추출은 향후 Slack/외부 통합 등 다른 BC의 아웃바운드 HTTP가 공유할 기반이 된다.
- FR-API-03는 규모가 커서 백엔드(D1~D5)와 프론트(D6/D7)를 별도 PR로 분리할 가능성이 높다(spec/plan에서 확정).
