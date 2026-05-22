<!-- ADR: pgmq 포함 postgres 이미지 결정 — dev/test/prod 일관 -->

# ADR — pgmq-postgres-image

**일자**. 2026-05-22
**상태**. Accepted
**관련 PR**. `issue-tracking-bc-fr-is-01-crud`
**작성자**. Maxi + Claude (db-engineer)

## 컨텍스트

PR #10 (project-workflow) 의 plan 에 pgmq (`CREATE EXTENSION pgmq`) 가 명시되어 있으나 실제 docker-compose dev 의 postgres 이미지는 `postgres:16-alpine` (pgmq 미포함) 상태. issue-tracking BC 진입 시 (본 PR) `V002__pgmq_queue_issue_events.sql` 적용에서 표면화.

## 고려한 옵션

- 옵션 A. `quay.io/tembo/pg16-pgmq:latest` — tembo.io 공식 이미지 (postgres 16 + pgmq 확장 사전 설치)
- 옵션 B. 자체 Dockerfile — `FROM postgres:16-alpine; pgmq 설치` — 운영 정합성 우선
- 옵션 C. pgmq 사용 중단 + InMemoryEventPublisher — scope 후퇴 (Maxi 거부)

## 결정

옵션 A — `quay.io/tembo/pg16-pgmq:latest` 채택.

## 결과

긍정.
- pgmq 확장 사전 설치로 `CREATE EXTENSION pgmq CASCADE` 즉시 동작.
- Tembo 가 정기 업데이트 + 보안 패치 제공.
- Naver Cloud 등 prod 환경에서도 동일 이미지 사용 가능해 dev/test/prod 환경 일관성 확보.
- 자체 Dockerfile 유지 비용 없음.

부정 / 위험.
- 외부 registry (`quay.io/tembo`) 의존 — Tembo 의 지속 운영 위험 존재.
- `postgres:16-alpine` 대비 이미지 크기 증가 (pgmq 및 관련 의존 패키지 포함).
- `:latest` 태그 사용으로 재현성 위험 — 향후 prod 고정 버전 태그 검토 필요.

## 향후 고려 — Prod 배포 시 이미지 전략

Naver Cloud 배포 시 두 가지 선택지가 있다.

**선택 1. `quay.io/tembo/pg16-pgmq` 고정 버전 태그 사용 (권장).**
현재 `:latest` 태그는 재현성을 보장하지 않으므로, prod 배포 전
`quay.io/tembo/pg16-pgmq:<구체적 버전>` 으로 고정한다.
Tembo 릴리즈 채널을 주기적으로 모니터링해 패치 버전을 올린다.

**선택 2. Naver Cloud 관리형 PostgreSQL (Cloud DB for PostgreSQL) 사용.**
Naver Cloud 가 PostgreSQL 16 을 지원하면 확장 설치 가능 여부를 확인해야 한다.
관리형 서비스는 pgmq 같은 서드파티 확장 설치를 제한할 수 있다 — 사전 검증 필수.
관리형 서비스 사용 시 이미지 의존성 자체가 사라지므로 이 ADR 은 dev/test 전용으로 축소된다.

현재 Phase 0 기준으로는 단일 호스트 Docker Compose 이므로 선택 1 을 따른다.
Prod 배포 전 별도 ADR 로 관리형 서비스 여부를 재결정한다.

## 관련

- `infra/docker-compose.dev.yml`
- `docs/plans/2026-05-22-issue-tracking-bc-fr-is-01-crud.md` §Wave 1c
- `backend/modules/issue-tracking/src/main/resources/db/migration/V002__pgmq_queue_issue_events.sql`
