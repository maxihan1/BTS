# 배포 기반 구축 체크리스트

> 관련: plan.md · context-notes.md

## P0 조사 (완료)
- [x] AIG VM 정보 확보 (61.107.200.30 / testify / ~/.ssh/id_ed25519 / /home/testify/aig, AIG 13579 운영중)
- [x] BTS 배포 인프라 부재 확인 (Dockerfile·prod compose·배포스크립트·prod시크릿 없음)
- [x] 조립 앱 부재 확인 (@SpringBootApplication 2개, 자기 패키지만 스캔)
- [x] 모듈 의존 그래프 (모두 shared-kernel; issue→project-workflow)
- [x] Flyway V번호 충돌 분석 (identity↔issue만 충돌, 나머지 대역 분리)
- [x] prod 프로파일·env 키·redis 미사용 확인

## P1 조립 모듈 신설
- [x] `backend/modules/app` 모듈 + settings.gradle.kts 등록
- [x] `build.gradle.kts` — 8개 모듈 의존 + application 플러그인 + bootJar (+flyway/jdbc)
- [x] `com.bts.app.BtsApplication` (`scanBasePackages = com.bts, com.atlas.bts` + FQN 이름생성기)
- [x] 기존 2개 @SpringBootApplication 스캔 제외 필터 (+ SchedulingConfiguration REGEX 제외)
- [x] @EnableScheduling 단일화
- [x] **컴파일 성공** (8개 모듈 jar + jOOQ codegen 통과)
- [ ] 컨텍스트 로드 성공 → **prod 프로파일 필요**로 판명 (P2로 이관)

## P2 통합 Flyway/설정/보안/cross-BC
- [x] 모듈별 다중 Flyway 빈 (이력 테이블 분리) — FlywayAssemblyConfig + baselineVersion=0
- [x] 통합 application.yml + application-prod.yml
- [x] 로컬 RSA 테스트키 생성 + prod 프로파일 컨텍스트 로드 **성공**
- [x] cross-BC 결선 prod 승격 (prod 프로파일이 실제 resolver 활성화로 해소)
- [x] BouncyCastle "BC" 프로바이더 등록 (identity 잠복버그 보완)
- [x] 마이그레이션 전량 적용 (7개 모듈, 다중 이력 테이블)
- [x] ✅ **workflow PermissionResolver prod 어댑터** — `DelegatingPermissionResolver`(@Profile prod, com.bts.workflow.adapter) 신설. shared-kernel `IssuePermissionResolver`+`SystemPermissionResolver`에 위임(BC 격리 준수, prod는 identity가 채움). 권한문자열→IssuePermission 매핑 7종, 미등록은 fail-closed deny+WARN. TDD(test #313eebcc8→feat #64a7e7b62), 단위 8케이스 green. security-engineer.
- [ ] allow-bean-definition-overriding 오버라이드 로그 감사 (진짜 충돌 없는지)
- [ ] SecurityFilterChain @Order 정렬 확인 (security-engineer)
- [ ] BtsApplicationContextTest Testcontainers 전환 (현재 로컬 postgres 의존)

## P3 컨테이너화 ✅
- [x] 백엔드 Dockerfile (JRE21, 호스트빌드 fat jar) — 이미지 빌드 검증 843MB
- [x] 프론트 Dockerfile + nginx.conf (SPA + 백엔드 프록시, Docker DNS 지연해석) — 빌드+nginx -t 검증
- [x] `infra/docker-compose.prod.yml` (bts-net 격리·18080·digest 고정·mem_limit·minio 버킷 init) — config 검증 6서비스
- [x] `infra/prod/.env.prod.example` (시크릿 키 목록)
- [x] `infra/deploy/bts-deploy.sh` + config.sh.example 스캐폴드 (P5용)
- [x] app bootJar 이름 고정 + mainClass, 시크릿 gitignore

## P4 로컬 전체 스택 검증 ✅ 완료
- [x] ✅ **workflow PermissionResolver prod 어댑터** — `DelegatingPermissionResolver` 신설로 해소. **`BtsApplicationContextTest`(@ActiveProfiles prod)가 스텁 없이 8개 BC 전체를 실제 어댑터로 부팅 성공** — NoSuchBean 갭 실배선 확인.
- [x] ✅ **프론트 fresh 빌드** — node_modules 심볼릭이 삭제된 워크트리 가리켜 깨짐 → `CI=true pnpm install --prefer-offline`(네트워크 0)로 복구. dist 55 assets 재생성.
- [x] ✅ **배포 아티팩트 2종** — `bts-app.jar` 118MB(`:modules:app:bootJar`) + apps/web/dist. 이미지 `bts-backend:local` 843MB·`bts-web:local` 83MB 빌드.
- [x] ✅ **`docker compose up` 전체 6서비스 기동** — postgres·minio·minio-init·clamav·backend·web. **backend `Started BtsApplicationKt in 15s`**(Flyway·MinIO버킷·워크플로우시드·DelegatingPermissionResolver 배선). worktree `.worktrees/deploy-prod-foundation`에서(2차 hijack 격리).
- [x] ✅ **health/actuator UP + 프록시 검증** — backend `/actuator/health` 200 UP(env crutch 없이 application.yml 수정만) · web healthy · nginx SPA 서빙(`<title>BTS — Atlas</title>`) · nginx→백엔드 프록시(`/api/v1/whoami`→401 정상). **health 오탐 2건 소스 수정**(commit bdccebbde). (로그인→CRUD 브라우저 스모크는 시드 사용자 없어 미실시 — 후속.)

## P5 서버 배포 (Maxi 승인 — 준비 착수)
- [x] ✅ `bts-deploy.sh` health 버그 수정 (nginx /actuator 미프록시 → docker exec 백엔드 직접 확인). config.sh.example 완비(REMOTE_DIR=/home/testify/bts, AIG와 분리).
- [ ] 🚧 **VM 점검 (네트워크 블로커)** — 이 Mac에서 `61.107.200.30:22` 라우팅 불가("Network is unreachable", 게이트웨이 10.22.6.60). AIG는 이 Mac에서 로컬 배포하므로 평소엔 접속됨 → **사내망/VPN 미접속 상태로 추정**. 네트워크 회복 후 아래 읽기전용 점검 실행:
  - `ssh -i ~/.ssh/id_ed25519 testify@61.107.200.30 'free -m; df -h /; nproc; docker --version; docker compose version; ps aux --sort=-%mem | head'`
  - 확인 항목: RAM 여유(BTS ~4.5GB + AIG 기존), 디스크, **Docker/Compose 설치 여부(AIG는 PM2라 미설치 가능성)**, AIG 현재 메모리 점유.
- [ ] 포트/도메인/TLS 결정 (현재 18080 평문, P5서 Let's Encrypt/앞단 프록시)
- [ ] 격리 배포 + AIG 무영향 확인 (bts-net·18080·/home/testify/bts 분리 설계 완료)
