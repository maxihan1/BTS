# FR-PR-04 — LDAP 동기화 필드 vs 사용자 편집 분리 (source 컬럼)

> slug: fr-pr-04-ldap-vs-source
> type: auth
> agent: security-engineer
> primary_bc: identity-access
> 생성: 2026-07-07

## Brief

사용자 원문: "FR-PR-04 진행 (LDAP 소스 분리)"
classify: type=auth, agent=security-engineer, slug=fr-pr-04-ldap-vs-source, primary_bc=identity-access

FR-PR-04 (personalization §2.4): LDAP 동기화 필드 vs 사용자 편집 필드를 source 컬럼으로 분리.
핵심 충돌: 사용자가 프로필 이름(users.display_name)을 편집해도, 다음 LDAP 재로그인 시
AutoProvisionService가 ON CONFLICT (username) → displayName을 LDAP cn으로 덮어써 원복됨.
FR-PR-04 = "LDAP 동기화 시 USER 편집 필드는 덮어쓰지 않음" + 필드별 출처(source) 표시.

선행 사실:
- LDAP 동기화는 로그인 시점(JIT) 뿐. 별도 주기적 sync 잡 없음.
- LDAP 제공 필드 = username / email / displayName(cn). department는 LDAP 미제공.
- user_profiles(avatar/timezone/department)는 현재 순수 사용자 편집(LDAP 미접촉).
- ADR 2026-07-05: FR-PR-01이 source 컬럼 추가 여지 남김.

## 도메인 정리

- **BC**: identity-access (personalization 논리 BC의 물리 배치 — FR-PR-01 선례)
- **영향 엔티티**: User (`users.display_name_source` 컬럼 신설)
- **새 용어**: "필드 출처(Field Source)" — 프로필 필드 값의 출처(LDAP 동기화 vs 사용자 편집). glossary 추가 후보(Maxi 승인 대기)
- **source 모델** (ADR D1): `users.display_name_source` enum(`'LDAP'`|`'USER'`, DEFAULT `'LDAP'`). 범용 테이블/컬럼별 source 미채택(실 충돌 필드 display_name 하나뿐 → YAGNI)
- **충돌 해소** (ADR D2): 편집 시 source='USER' 전환(단일 tx) → 재로그인 UPSERT는 `CASE WHEN source='USER' THEN 보존 ELSE EXCLUDED` 게이트. email은 편집 대상 아님 → 계속 LDAP 동기화
- **재동기화** (ADR D4): source 'USER'→'LDAP' 되돌림 → 다음 로그인부터 cn 동기화 재개. UI "LDAP 값으로 재설정"
- **기존 결정 충돌**: 없음. FR-PR-01 ADR D3(source 컬럼 여지)를 실현. **product 문서 §2.4 D3 "user_profiles 컬럼별 source" 표기는 부정합**(display_name이 users에 있음) → 본 PR에서 정정
- **관련 ADR**: [docs/decisions/2026-07-07-fr-pr-04-ldap-field-source.md](../decisions/2026-07-07-fr-pr-04-ldap-field-source.md) (생성됨) · 선행 [2026-07-05-fr-pr-01-user-profile-placement.md](../decisions/2026-07-05-fr-pr-01-user-profile-placement.md)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
