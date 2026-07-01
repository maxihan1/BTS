<!-- ADR — FR-API-04 PAT 발급/관리 셀프서비스 정책(scope 범위·회전·TTL) 결정 -->

# ADR — FR-API-04 Personal Access Token 관리 정책

**일자.** 2026-07-02
**상태.** Accepted
**관련 PR.** #215 (`auth/fr-api-04-pat`)
**작성자.** security-engineer (주도) + Maxi 결정
**선행.** [session-pat-schema](2026-05-20-session-pat-schema.md)(V006 스키마·scope JSONB·EC-27 무기한 허용)

---

## 컨텍스트

PAT 검증 인프라(V006 `personal_access_tokens`·`PatAuthenticationFilter`·`PersonalAccessTokenService.verify/markLastUsed/hasScope`·CSRF 스킵)는 FR-AU-09(#37, 2026-05-29)에서 완성됐다. 그러나 **발급·목록·취소 로직과 REST 엔드포인트·프론트 UI가 전무**하다(`issue()`/`listByUser()`/`revoke()` 미구현, 주석 "후속 PR"). FR-API-04는 이 셀프서비스 관리 계층을 완성한다. product §5.4 D2가 "scope + TTL + 회전 + 취소"를 명시하나 각 항목의 구체 정책이 미확정이라 본 ADR로 결정한다.

---

## 결정

### 1. scope — 발급/관리 + 저장·표시만 (전면 강제는 후속)

PAT 발급 시 지원 scope 목록에서 선택·저장·UI 표시한다. **실제 권한 검사(전 API에 필요 scope 매핑 + Bearer 필터 교집합 강제)는 본 PR 범위 밖**이며, 기존 `hasScope()`(와일드카드 `*` 지원) 인프라를 개별 API가 필요 시 호출하는 형태로 남긴다.

**근거.** 전면 scope 강제는 수십 엔드포인트에 필요 scope를 매핑해야 하는 큰 작업이고 폭발 반경이 크다. 발급/관리 UI를 먼저 완성하고 scope 저장 기반을 확보한 뒤, 강제는 별도 트랙으로 분리한다(Maxi 결정).

**scope 카탈로그.** 명시 상수/enum이 없었으므로(테스트 관례만) 지원 scope를 서버 상수로 정의하고 발급 시 화이트리스트 검증한다. 미지 scope는 400. 카탈로그 구체 목록은 spec에서 확정.

### 2. 회전(rotate) — 취소+재발급으로 충분 (별도 API 없음)

PAT는 stateless 자격증명이라 회전 = 기존 토큰 취소 후 새 토큰 발급이 표준(GitHub PAT 방식). 별도 `rotate` 엔드포인트를 두지 않고, 클라이언트가 `DELETE` + `POST`를 조합한다.

**근거.** 별도 rotate API는 원자성/edge-case(같은 name 중복 등) 추가 구현 비용 대비 이득이 적다. product D2의 "회전" 표기는 취소+재발급 흐름으로 충족(Maxi 결정).

### 3. TTL — 사용자 선택, 최대 1년 (무기한 금지)

발급 시 만료 기간을 사용자가 선택하되(예: 30일/90일/1년), **무기한(`expires_at = NULL`)은 금지**하고 상한 1년(`now + 365일` 이내)을 강제한다. 위반 시 400.

**근거.** SDD 19(PAT "최대 1년") 준수 + 무기한 PAT의 장기 방치 보안 리스크 제거. session-pat-schema ADR의 EC-27("무기한 허용, 후속 PR에서 1년 default 검토")이 예고한 후속 PR이 본 PR이다.

**DB 무변경.** `expires_at`은 nullable 유지(기존 무기한 PAT 데이터 호환·감사 보존). 신규 발급만 애플리케이션 레벨에서 필수+상한 검증한다. 마이그레이션 0.

---

## 범위 (D1~D7)

| 단계 | 신규 내용 |
|---|---|
| D1 도메인 | `PersonalAccessToken` 발급 팩토리(raw `pat_` 생성·SHA-256) |
| D2 명세 | scope 카탈로그·TTL 상한·발급 응답 1회 노출 |
| D3 데이터 모델 | V006 재사용(마이그레이션 0) |
| D4 백엔드 | `PersonalAccessTokenService.issue/listByUser/revoke` + `PersonalAccessTokenController`(POST/GET/DELETE `/api/v1/users/me/pats`) |
| D5 백엔드 테스트 | 발급·scope 화이트리스트·TTL 상한·무기한 거부·목록 마스킹·취소 멱등 |
| D6 프론트 UI | `/settings/pats` + `pats.ts` — 발급(name/scope/만료 선택)·목록·취소, **토큰 1회 표시**(DEVELOPMENT §1.17, localStorage 금지) |
| D7 E2E | 발급→1회 표시→목록→취소 |

---

## 대안 검토

- **scope 전면 강제(불채택)** — 보안 완성도는 높으나 수십 엔드포인트 수정 = 범위 폭발. 발급 기반 우선 확보 후 후속.
- **별도 rotate API(불채택)** — 취소+재발급으로 동일 효과, 구현 단순.
- **무기한 허용 유지(불채택)** — 장기 방치 토큰 보안 리스크. SDD 19 "최대 1년"과 상충.

---

## 참조

- [session-pat-schema](2026-05-20-session-pat-schema.md) — V006 스키마·scope JSONB·EC-27
- SDD 19-authentication.md §19.2(supportedScopes)·토큰 표(PAT 최대 1년)
- DEVELOPMENT.md §1.1(토큰 SHA-256 해시 저장)·§1.17(토큰 화면 1회·localStorage 금지)
- product §5.4 FR-API-04
