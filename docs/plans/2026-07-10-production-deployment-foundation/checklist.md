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
- [ ] 🚧 **BLOCKER FR-WF-03**: workflow PermissionResolver 운영 어댑터 미구현 (현재 테스트 스텁) — 배포 전 필수, security-engineer
- [ ] allow-bean-definition-overriding 오버라이드 로그 감사 (진짜 충돌 없는지)
- [ ] SecurityFilterChain @Order 정렬 확인 (security-engineer)
- [ ] BtsApplicationContextTest Testcontainers 전환 (현재 로컬 postgres 의존)

## P3 컨테이너화
- [ ] 백엔드 Dockerfile (multi-stage, JRE21, fat jar)
- [ ] 프론트 Dockerfile (nginx dist 서빙 + /api 프록시)
- [ ] `infra/docker-compose.prod.yml` (bts-net 격리·포트 18xxx·버전 고정·mem_limit)
- [ ] `infra/prod/.env.prod.example` (시크릿 키 목록)
- [ ] `infra/deploy/bts-deploy.sh` 스캐폴드 (Docker 기반, P5용)

## P4 로컬 전체 스택 검증
- [ ] `docker compose -f infra/docker-compose.prod.yml up` 전체 기동
- [ ] health/actuator UP + 마이그레이션 적용 확인
- [ ] 프론트 로그인 → 이슈 CRUD 스모크
- [ ] 기존 백엔드 테스트 green (조립 모듈이 기존 테스트 안 깨뜨림)

## P5 서버 배포 (별도 승인 — 이번 범위 밖)
- [ ] VM RAM/디스크 여유 확인 (free -m, df -h)
- [ ] VM Docker/Compose 설치 여부
- [ ] 포트/도메인/TLS 결정
- [ ] 격리 배포 + AIG 무영향 확인
