# 프로덕션 배포 기반 구축 (Production Deployment Foundation)

> slug: prod-deployment-foundation
> type: infrastructure
> agent: backend-engineer (조립/설정) + security-engineer (보안 체인 검토) + db-engineer (Flyway 다중 이력) + frontend-engineer (nginx/프론트 빌드)
> 생성: 2026-07-10
> 대상 서버: 61.107.200.30 (AIG와 같은 VM, Docker 격리 배포) — 실제 배포는 별도 승인 단계

## Brief

BTS를 VM에 **배포 가능한 완성체**로 만드는 기반 구축. FR 기능은 ~90% 완성이나, 8개 BC(Bounded Context — 책임 범위로 나눈 도메인 단위)를 하나로 조립해 시동 거는 배포 앱이 없고, 컨테이너화·프로덕션 compose·시크릿·배포 스크립트가 전무하다.

**이번 작업 범위**. 조립 모듈 신설 + 컨테이너화 + 로컬 `docker compose up` 전체 부팅 검증까지. **실제 서버 배포는 이 문서 범위 밖**(별도 승인 단계 P5).

## 현황 (P0 조사 결과, 2026-07-10)

- 부팅 가능한 `@SpringBootApplication` 2개뿐 — `IdentityAccessApplication`(`com.atlas.bts.identity`), `IssueTrackingApplication`(`com.bts.issue`). 각자 자기 패키지만 컴포넌트 스캔.
- 나머지 6개(project-workflow·shared-kernel·notification·agile-planning·search-export-import·slack-integration)는 **라이브러리 모듈** — 단독 부팅 불가.
- **전체를 스캔·결선하는 bootstrap/app 모듈 없음.** cross-BC 런타임 결선은 각 통합테스트 TestConfig의 수동 조립에만 존재 (메모리 `no-cross-bc-deployment-assembly`).
- 모듈 의존 그래프: 모두 `shared-kernel`에 의존. issue-tracking → project-workflow. 나머지는 서로 독립.
- Flyway V번호 대역: identity(V001–033) · issue(V001–035) · project-workflow(V200–202) · notification(V400–408) · agile-planning(V500–504) · search-export-import(V600–608) · slack(V700). **충돌은 identity ↔ issue 뿐** (둘 다 V001부터). 나머지는 대역 분리로 안전. `baseline-on-migrate: true` 주석에 "모놀리스 조립 환경 대비" 이미 명시.
- 각 모듈이 자기 `datasource`·`flyway`·`server.port`를 보유 → 통합 시 중복 정의 충돌.
- prod 프로파일(`application-prod.yml`)은 identity-access만 존재. 설정 대부분이 `${BTS_*}` 환경변수 기반 → prod 주입 수월.
- 프론트(apps/web)는 Vite → `dist/` 정적 빌드 OK. `frontend-ci.yml`만 있고 백엔드 CI·배포 파이프라인 없음.
- 인프라 의존: postgres(pgmq) · MinIO(첨부) · ClamAV(바이러스 스캔). **redis는 prod 코드에서 미참조**(제외).
- VM 현황: AIG(Next.js/PM2)가 포트 13579 **실서비스 운영 중**. Docker 설치 여부·RAM 여유 미확인.

## 설계 결정

### D1. 조립 모듈 `backend/modules/app` 신설
- 패키지 `com.bts.app`, `@SpringBootApplication(scanBasePackages = ["com.bts", "com.atlas.bts"])`로 전체 스캔.
- 8개 모듈 전부 `implementation(project(...))` 의존. `application` 플러그인 + `bootJar` 활성(실행 fat jar 산출).
- **기존 2개 `@SpringBootApplication`을 컴포넌트 스캔에서 제외** — `@ComponentScan.Filter`(또는 `TypeExcludeFilter`)로 `IdentityAccessApplication`·`IssueTrackingApplication` 배제. 미배제 시 "multiple `@SpringBootConfiguration`" 부팅 실패.
- `@EnableScheduling`은 조립 모듈에서 한 번만(각 BC의 `SchedulingConfiguration` 중복 활성 주의).

### D2. Flyway = 모듈별 다중 Flyway 빈 (이력 테이블 분리)
- BC별로 `Flyway` 빈을 따로 정의, 각자 `flyway_schema_history_<bc>` 이력 테이블 + 자기 location. → identity(V001) ↔ issue(V001) 충돌 무해화.
- 실행 순서: shared→identity→issue→project-workflow→나머지 (FK 의존 고려: project-workflow가 issue의 projects 참조).
- **대안(비채택)**: 단일 이력 + issue 재번호(V100+). 기존 dev/prod DB 이력·픽스처 전면 파손 → blast radius 과대.

### D3. 통합 설정 병합
- 조립 모듈에 단일 `application.yml`(base) + `application-prod.yml`. 단일 `datasource`·`server.port`. 각 BC의 설정 키(minio·clamav·ldap·webauthn·auth 등)를 병합.
- prod는 전부 `${BTS_*}` 환경변수 fail-fast. 시크릿은 compose `.env`로 주입.

### D4. cross-BC 런타임 결선 승격
- 테스트 TestConfig에만 있던 결선(WorkflowEngine·WorkflowTransitionAdapter·WorkflowValidatorFactory·WorkflowDefinitionRepository 등)을 prod 빈으로 승격 결선. 조립 부팅 시 NoSuchBean 나오는 포트를 순차 해소.

### D5. 보안 체인 정렬
- BC별 `SecurityFilterChain`이 여럿이면 `@Order` + path 스코프로 정렬(충돌·미스매치 방지). security-engineer 검토.

### D6. 컨테이너화
- **백엔드 Dockerfile** — multi-stage(Gradle 빌드 → JRE 21 런타임), 조립 fat jar 실행, actuator health.
- **프론트 Dockerfile** — nginx로 `dist/` 정적 서빙 + `/api` → 백엔드 프록시.

### D7. compose 격리 (AIG와 공존)
- 별도 docker network `bts-net`, 컨테이너명 `bts-*`, 포트 대역 예약(AIG 13579 회피 — 백엔드/게이트웨이 18xxx 대역).
- 이미지 버전 **고정**(dev의 `:latest` 금지): postgres pgmq · minio · clamav 태그 pin.
- `mem_limit`/리소스 제한으로 AIG 보호. postgres/minio 볼륨 분리.

### D8. 시크릿 & 배포 스크립트
- `infra/prod/.env.prod.example` — BTS_DB_* · JWT key path · LDAP · WEBAUTHN · MINIO · ISSUER 등 키만(값은 서버 관리).
- `infra/deploy/bts-deploy.sh` — Docker 기반(AIG 스크립트 참고하되 rsync/PM2 → git pull + compose build + up + health). **P5에서 사용, 이번엔 스캐폴드만.**

## 리스크 / 오픈 이슈

- **조립 부팅은 "boot until green" 반복 작업** — 빈 이름 충돌·중복 설정·보안 체인 순서·스케줄러 중복 등 다수 표면화 예상(메모리의 documented traps 다수). 1회성 아님, 반복 검증.
- VM RAM 여유 미확인 — JVM+PG+MinIO+ClamAV+nginx를 실서비스 AIG 옆에. P5 시작 시 `free -m` 확인 필수. 부족하면 별도 VM 재검토.
- VM Docker/Compose 설치 여부 미확인 (P5).
- TLS·접속 도메인 미정 — BTS 도메인? Let's Encrypt vs 자체 인증서? (P5 결정).
- 프론트 API base URL/CORS 정합.

## 단계 (Phase)

| Phase | 내용 | 게이트 |
|---|---|---|
| P0 | 조사 | ✅ 완료 |
| P1 | 조립 모듈 신설 + 로컬 dev 인프라 대상 부팅 green | 앱 컨텍스트 로드 성공 |
| P2 | 통합 Flyway/설정/보안/cross-BC 결선 | 마이그레이션 전량 적용 + health UP |
| P3 | Dockerfile ×2 + prod compose + .env 템플릿 | 이미지 빌드 성공 |
| P4 | 로컬 `docker compose up` 전체 스택 스모크 | 로그인~이슈 CRUD 스모크 통과 |
| P5 | (별도 승인) 서버 프로비저닝 + 실제 배포 | Maxi 승인 게이트 |

## 성공 기준

1. 조립 앱이 `--spring.profiles.active=prod`(로컬 시크릿)로 부팅되고 actuator `/health` UP.
2. `docker compose up`으로 백엔드+프론트+인프라 전체 기동, 프론트에서 로그인→이슈 CRUD 스모크 통과.
3. main 미오염(브랜치/worktree 작업), 기존 테스트 green.
