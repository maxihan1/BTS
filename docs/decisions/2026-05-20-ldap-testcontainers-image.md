<!-- ADR: OpenLDAP Testcontainers 이미지 선정 — osixia/openldap:1.5.0 -->

# ADR: OpenLDAP Testcontainers 이미지 선정

> 날짜: 2026-05-20
> 상태: 결정됨
> 연관: FR-AU-02 (LdapProviderIntegrationTest)

## 맥락

LdapProvider 통합 테스트(T6)에서 실제 LDAP 서버가 필요하다. Testcontainers로 실행 가능한 OpenLDAP 이미지를 선정해야 한다.

## 후보 비교

| 이미지 | 버전 | 가용성 | 비고 |
|---|---|---|---|
| `bitnami/openldap` | 2.6 | 미존재 (2026-05-20 pull 실패) | Docker Hub에서 해당 태그 없음 |
| `bitnami/openldap` | latest | 미존재 (2026-05-20 pull 실패) | Docker Hub에서 해당 이미지 없음 |
| `osixia/openldap` | 1.5.0 | 존재 | pull 성공, Testcontainers 안정 동작 |
| `openldap` (공식) | - | 없음 | Docker Hub 공식 이미지 없음 |

## 결정

`osixia/openldap:1.5.0` 을 선택한다.

## 이유

1. **bitnami/openldap 미가용**: 2026-05-20 기준 Docker Hub에서 bitnami/openldap 이미지가 존재하지 않음 (pull 실패).
2. **osixia/openldap 안정성**: 1.5.0 버전이 Testcontainers 환경에서 정상 동작 확인됨 (cold start ~2초).
3. **seed.ldif 지원**: `--copy-service` 플래그로 `/container/service/slapd/assets/config/bootstrap/ldif/50-bootstrap.ldif` 경로에 ldif 파일 복사 가능.
4. **환경변수 기반 설정**: `LDAP_DOMAIN`, `LDAP_ADMIN_PASSWORD` 등 표준 환경변수로 설정 가능.

## 제약사항

- osixia/openldap 은 유지보수가 활발하지 않을 수 있음. 향후 bitnami 이미지가 가용해지면 마이그레이션 검토.
- cold start 목표 30초 이내 — 실측 ~2초로 목표 달성.

## 대안 (미채택)

- 로컬 `slapd` 프로세스 기반 테스트: 환경 의존성 높고 CI 이식성 낮음.
- Embedded LDAP (UnboundID): 실제 OpenLDAP 동작과 차이 가능, 별도 라이브러리 의존.
