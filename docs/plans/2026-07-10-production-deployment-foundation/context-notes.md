# 배포 기반 구축 — 컨텍스트 노트 (결정 + 근거)

> 작업 진행하며 계속 append. 다음 세션(사람/에이전트)이 재유도 없이 이어받도록.

## 2026-07-10 — 착수 배경

- Maxi 요청: "계획된 FR 90%+ 개발됨. VM 서버에 올리고 싶다. AIG에 VM 정보 있으니 확인해서 세팅."
- Maxi 결정(질문 응답):
  - **진행 범위** = "계획과 배포 기반 먼저 구축" (조립+Docker+compose, 로컬 부팅 검증까지. 실제 서버 배포는 별도 단계).
  - **대상 서버** = "같은 VM, 격리 배포" (61.107.200.30, AIG 옆에 Docker 격리).

## 핵심 발견 (왜 단순 복사가 아닌가)

1. **BTS는 지금 배포 불가** — FR 기능은 90%+지만 8개 BC를 하나로 조립하는 배포 앱이 없다. 90% 완성은 test-assembled 컨텍스트로만 검증됨(메모리 `no-cross-bc-deployment-assembly`). production-bootable 아티팩트가 존재하지 않음.
2. **AIG ≠ BTS 스택** — AIG는 Next.js/PM2/rsync. BTS는 Kotlin/Spring + React + Docker Compose. AIG 배포 스크립트 재사용 불가. VM 접속 정보(IP/SSH/유저)만 재사용.
3. **VM은 AIG 실서비스 운영중** (포트 13579) → 격리 배포 필수. 리소스 여유 미확인.

## 설계 결정 근거

- **Flyway 다중 이력 테이블 채택 이유**: identity(V001–033)↔issue(V001–035) 충돌은 오직 이 둘. 나머지는 대역 분리(200/400/500/600/700)라 안전. 재번호(단일 이력)는 기존 DB 이력/픽스처 전면 파손(blast radius 과대)이라 비채택. `baseline-on-migrate: true` 주석이 이미 "모놀리스 조립 대비"라 명시 → 다중 이력이 원 설계 의도와 정합.
  - ⚠️ 미검증 지점: identity+issue를 **한 Flyway 실행에 합친 적이 없음**(project-workflow 테스트는 issue만 testRuntimeOnly, identity 미포함). 따라서 V001 충돌은 실전에서 처음 부딪히는 것. 다중 이력으로 회피.
- **조립 모듈 스캔 제외 필터 필요 이유**: `scanBasePackages`로 전체 스캔 시 기존 2개 `@SpringBootApplication`(=`@SpringBootConfiguration`)이 스캔에 걸려 "multiple SpringBootConfiguration" 부팅 실패. 필터로 배제.
- **redis 제외**: 백엔드 prod 코드에서 redis/lettuce 미참조 확인. dev compose에도 없음. 인프라에서 뺌.

## 파악한 사실 (참조용)

- 모듈 의존: 전부 shared-kernel. issue-tracking → project-workflow(compile). project-workflow는 testRuntimeOnly로 issue-tracking(마이그레이션 classpath 목적).
- 인프라 의존: postgres(pgmq, `quay.io/tembo/pg16-pgmq`) · MinIO · ClamAV.
- 서버 포트(현행 개별): identity 8090(dev 8080), 나머지 라이브러리라 자체 포트 없음. 조립 후 단일 포트.
- prod env 키(identity 기준): BTS_DB_URL/USERNAME/PASSWORD · BTS_AUTH_ISSUER_URI · BTS_JWT_PRIVATE_KEY_PATH · BTS_LDAP_* · BTS_WEBAUTHN_* · BTS_BOOTSTRAP_ADMIN_USERNAME. + minio 키(issue/search 모듈), clamav host/port.
- 프론트: Vite build → apps/web/dist. nginx 서빙 대상.

## 다음 세션 진입점

- Maxi 승인 대기 중(P1 착수 게이트). 승인 시 worktree `.worktrees/prod-foundation`에서 P1(조립 모듈)부터.
- P1은 "boot until green" 반복 — NoSuchBean/빈충돌/보안체인 순서를 로그 보고 순차 해소. 추측 금지, 실제 에러 로그 기반.
