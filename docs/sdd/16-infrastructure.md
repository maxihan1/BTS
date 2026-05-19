# 16. 인프라 / 배포 / 운영

## 16.1 권장 사양 (1,000명 규모)

| 역할 | vCPU/메모리 | 비고 |
|---|---|---|
| App 서버 | 2 vCPU / 8GB | Spring Boot + Worker + Nginx |
| Data 서버 | 2 vCPU / 4GB | PostgreSQL + Redis + Keycloak |
| Block (DB) | 200GB SSD | 3년 누적 |
| Block (App) | 50GB SSD | OS + 앱 + 로그 |
| Object | 500GB | 첨부 + 백업 |

## 16.2 Docker Compose 구성

```yaml
# infra/docker-compose.yml
services:
  nginx:
    image: nginx:1.27-alpine
    ports: ["443:443", "80:80"]
    volumes:
      - ./nginx/conf:/etc/nginx/conf.d:ro
      - ./certs:/etc/ssl/certs:ro

  app:
    image: atlas/backend:${VERSION}
    environment:
      - DB_URL=jdbc:postgresql://postgres:5432/atlas
      - REDIS_URL=redis://redis:6379
    depends_on: [postgres, redis, minio]

  worker:
    image: atlas/backend:${VERSION}
    command: ["--worker"]
    environment: { same as app }

  postgres:
    image: postgres:16
    volumes:
      - postgres-data:/var/lib/postgresql/data
    environment:
      POSTGRES_DB: atlas
      POSTGRES_USER: atlas
      POSTGRES_PASSWORD: ${DB_PASSWORD}

  redis:
    image: redis:7-alpine
    volumes:
      - redis-data:/data

  minio:
    image: minio/minio
    command: server /data --console-address ":9001"
    volumes:
      - minio-data:/data

  keycloak:
    image: quay.io/keycloak/keycloak:25
    environment:
      KC_DB: postgres
      KC_DB_URL: jdbc:postgresql://postgres:5432/keycloak
    depends_on: [postgres]

volumes:
  postgres-data:
  redis-data:
  minio-data:
```

## 16.3 환경 구성

| 환경 | 용도 | 구성 |
|---|---|---|
| Local | 개발 | Docker Compose (단일 호스트) |
| Dev | 개발 통합 | App + Data 합쳐 1대 |
| Staging | 사용자 베타 | Production 동일 구성 |
| Production | 운영 | App + Data 분리 2대 |

## 16.4 배포 흐름

```
Git Push → GitHub Actions:
  1. 빌드 (Gradle + pnpm)
  2. 테스트 (단위 + 통합)
  3. Docker 이미지 빌드
  4. 보안 스캔 (Trivy)
  5. Container Registry 푸시
  6. Staging 자동 배포
  7. E2E 테스트 (Playwright)
  8. Production은 수동 승인 (Maxi)
  9. Blue-green 배포 (Nginx 라우팅 전환)
```

## 16.5 백업

### DB 백업
- 매일 02:00 pg_dump → MinIO
- 보존: 일 7일 + 주 4주 + 월 12개월
- Naver Cloud Object Storage로 오프사이트 복사

### 첨부 백업
- MinIO 자체는 단일 호스트
- 매일 차등 백업 → Object Storage

### 복구 절차
- RPO 24시간 / RTO 1시간
- 백업에서 복원 → 마지막 백업 시점까지 일치
- 운영 가이드: `docs/runbook/disaster-recovery.md`

## 16.6 모니터링

### 메트릭
- Grafana Cloud Free 또는 자체 Grafana + Prometheus
- 주요 지표:
  - API 응답시간 p50/p95/p99
  - 에러율
  - DB 슬로우 쿼리
  - JVM 메모리
  - Worker 큐 깊이
  - Redis hit ratio

### 로그
- Loki 또는 ELK
- 로그 보존: Hot 7일, Cold 90일
- 인증/권한 변경은 1년

### Uptime
- UptimeRobot (5분 간격)
- 다운 시 Slack #ops 알람

## 16.7 비용 (연간)

| 항목 | Naver Cloud |
|---|---|
| App 서버 | 약 60만원 |
| Data 서버 | 약 45만원 |
| Block Storage | 약 30만원 |
| Object Storage | 약 30만원 |
| Network | 약 30만원 |
| **합계** | **약 195만원/년** |

## 16.8 보안

- TLS 1.3 (Let's Encrypt 또는 사내 CA)
- 사내망 우선, 외부는 VPN
- SSH 키 인증만 (패스워드 비활성화)
- iptables / Security Group 최소 포트
- Fail2ban (SSH 차단)
- 주기적 보안 업데이트 (자동: unattended-upgrades)

## 16.9 운영 자동화

- 자동 백업
- 자동 인증서 갱신 (Let's Encrypt)
- 자동 보안 업데이트
- 로그 회전
- 헬스체크 + 재시작 (Docker restart policy)

## 16.10 다음 챕터

- 로드맵 → [17. 로드맵](17-roadmap.md)
- 인증 환경 구성 → [19. 인증 시스템](19-authentication.md)
