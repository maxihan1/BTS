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

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
