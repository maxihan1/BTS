# FR-SL-01 Slack App + Bot Token 방식

> slug: fr-sl-01-slack-bot-app
> type: auth
> agent: security-engineer
> primary_bc: slack-integration
> 생성: 2026-07-07

## Brief

slack-integration BC의 첫 구현. Slack App 설치 흐름(OAuth 2.0) + Bot Token 안전 보관.
- D1. 도메인 — SlackWorkspace + BotInstall
- D2. 명세 — OAuth 2.0 설치 흐름 + Token 보관
- D3. 데이터 모델 — slack_installs(workspace_id, bot_token_encrypted, ...)
- D4. 백엔드 — Slack 설치 콜백 (/slack/install/callback)
- D5. 백엔드 테스트
- D6. 프론트 UI — 관리자 "Slack 연결" 페이지
- D7. E2E

SDD 09장(알림/Slack). product: docs/plan/product/slack-integration.md §2.1

## 도메인 정리

- **BC**: slack-integration (신규 — 새 Gradle 모듈 `backend/modules/slack-integration`, 패키지 `com.atlas.bts.slack`)
- **영향 엔티티**: `SlackInstall` (신규, `slack_installs` 테이블 매핑 VO — 워크스페이스별 봇 설치 + 암호화 토큰)
- **새 용어**: "Slack 설치(Install)" = 워크스페이스에 BTS App을 OAuth로 연결해 bot token을 발급받은 상태. "봇 토큰(Bot Token)" = Slack이 App에 발급하는 워크스페이스 스코프 액세스 토큰.
- **재사용 자산**:
  - `com.bts.shared.crypto.SecretEncryptor` (shared-kernel, AES 대칭) — bot token 암호화. 빈 이름 `slackSecretEncryptor` + `@Qualifier` (OIDC `oidcSecretEncryptor` 선례)
  - webhook `WebhookHttpClientConfig` RestClient 패턴 — OAuth 토큰 교환 아웃바운드 HTTP
  - OIDC `oidc_provider_configs`/`DbClientRegistrationRepository` — 암호화 토큰 DB 저장 + 부팅 안전성 패턴
- **결정 사항 (게이트 확정)**:
  - SDK = `com.slack.api:slack-api-client` (client 층만, Bolt 프레임워크 미도입 / 신규 외부 의존성)
  - 암호화 = AES(SecretEncryptor), KMS는 v0.4+ (domain/product 문서 "KMS" 표기 정정 대상)
  - OAuth v2 설치 흐름 + `state` CSRF 검증
  - 스코프 = 백엔드 코어 D1~D5, 실 App 없음 → 통합 테스트 stub
- **기존 결정 충돌**: 없음 (slack-integration BC 첫 ADR). domain 노트 "KMS" 표기만 정정 필요 (머지 시 동기화).
- **관련 ADR**: [docs/decisions/2026-07-07-fr-sl-01-slack-bot-app.md](../decisions/2026-07-07-fr-sl-01-slack-bot-app.md) (생성됨)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
