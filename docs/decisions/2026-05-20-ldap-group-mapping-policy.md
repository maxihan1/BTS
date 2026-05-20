<!-- ADR: LDAP 그룹 매핑 정책 — 그룹 DN 저장만, 권한 매핑 FR-PM-01 위임 -->

# ADR: LDAP 그룹 매핑 정책

> 날짜: 2026-05-20
> 상태: 결정됨
> 연관: FR-AU-02 (본 PR), FR-PM-01 (후속 PR)

## 맥락

LDAP 로그인 시 사용자의 그룹 정보(예: `cn=engineers,ou=groups,dc=example,dc=org`)를 어떻게 처리할지 결정해야 한다. 그룹 → BTS role 매핑까지 본 PR 에서 구현할지, 별도 PR 로 분리할지가 핵심.

## 옵션 비교

### 옵션 A: 그룹 저장 + 즉시 role 매핑 (미채택)

LDAP 로그인 시 그룹 DN을 조회하고, 사전 정의된 매핑 테이블에서 BTS role 을 즉시 할당.

**단점**:
- 그룹 → role 매핑 테이블 스키마가 필요 → 별도 V003 마이그레이션.
- FR-PM-01 (권한 관리) 와 강결합 — 권한 설계 미확정 상태에서 구현 어려움.
- 본 PR 스코프 폭발.

### 옵션 B: 그룹 DN 저장만, role 매핑 FR-PM-01 위임 (채택)

LDAP 로그인 시 사용자의 그룹 DN 목록을 `user_external_accounts.groups` (JSONB array) 에 저장만 한다. BTS role 할당은 FR-PM-01 에서 처리.

**장점**:
- 본 PR 스코프 명확 — LDAP 인증 + 프로비저닝만 담당.
- 그룹 정보 보존 — FR-PM-01 도입 시 기존 로그인 사용자의 그룹 데이터 활용 가능.
- 권한 매핑 로직 변경 시 LDAP Provider 코드 수정 불필요.

## 결정

**옵션 B: 그룹 DN 저장만** 을 채택한다.

## 현재 구현 상태 (본 PR)

- `user_external_accounts.groups` JSONB 컬럼에 그룹 DN 목록 저장.
- 현재 구현에서는 groups 는 빈 목록(`[]`)으로 저장 (LDAP 그룹 검색 로직 미구현).
- 실제 그룹 검색은 `LdapConfig.groupSearchBase` + `groupSearchFilter` 설정이 이미 있으나, 실제 LDAP 쿼리는 FR-PM-01 에서 구현 예정.

## 향후 구현 (FR-PM-01)

1. LDAP 로그인 성공 시 `ldapTemplate.search(groupSearchBase, groupSearchFilter)` 호출.
2. 결과 그룹 DN 목록을 `user_external_accounts.groups` 업데이트.
3. `authn_providers.config` 에 `groupMappings: Map<String, String>` (그룹 DN → BTS role 이름) 추가.
4. `groups` JSONB 에 GIN 인덱스 추가 (그룹 기반 조회 최적화).

## 1-depth 그룹 제한

본 PR 및 FR-PM-01 초기 버전은 1-depth 그룹만 지원한다. nested 그룹(그룹의 그룹)은 별도 FR.
