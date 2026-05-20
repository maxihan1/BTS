# BTS 개발 기여 가이드

BTS (Project Atlas) 프로젝트의 로컬 개발 환경 설정 안내.

---

## 개발 환경 요구사항

| 도구 | 최소 버전 | 비고 |
|---|---|---|
| Node.js | 22+ | `scripts/workflow/` 실행 |
| JDK | 21 | Kotlin/Spring 백엔드 빌드 |
| Docker Desktop | 최신 안정 버전 | Testcontainers 필수 |

버전 확인.

```bash
node --version   # v22.x.x 이상
java --version   # 21.x.x 이상
docker --version # Docker version 26.x 이상
```

---

## Docker Desktop 설정 (macOS 기준)

설치 페이지에서 다운로드.

```
https://www.docker.com/products/docker-desktop
```

설치 후 **메모리 4GB 이상** 권장 (OpenLDAP + PostgreSQL 컨테이너 동시 기동 기준).
Settings → Resources → Memory 슬라이더 조정 후 Apply & Restart.

실행 확인.

```bash
docker info
```

`Cannot connect to the Docker daemon` 메시지가 나오면 Docker Desktop 미실행 상태.
메뉴바 고래 아이콘이 초록색인지 확인 후 앱 실행.

---

## Testcontainers 동작 확인

Testcontainers 는 테스트 실행 시 Docker 컨테이너를 자동으로 띄우고 테스트 종료 후 정리하는 도구.
별도 서버 설치 없이 테스트마다 깨끗한 환경을 보장.

### identity-access 통합 테스트 실행

```bash
./gradlew :backend:identity-access:test
```

**첫 실행 주의.** OpenLDAP 이미지(`osixia/openldap`)를 Docker Hub 에서 내려받으므로
**최초 1회는 5분 내외** 소요. 이후 실행은 로컬 캐시로 30초 내외.

```
# 첫 실행 시 보이는 로그
Pulling image: osixia/openldap:1.5.0
...
Container started in 8234ms
```

단위 테스트만 (컨테이너 없이, 빠름).

```bash
./gradlew :backend:identity-access:test --tests "*LdapProviderUnitTest*"
```

---

## 흔한 문제 + 해결

### `Cannot connect to the Docker daemon`

원인. Docker Desktop 미실행.
해결. Docker Desktop 실행 후 고래 아이콘이 초록색이 될 때까지 대기, 재시도.

### `OutOfMemoryError` / 컨테이너 시작 실패

원인. Docker Desktop 메모리 부족.
해결. Settings → Resources → Memory 를 4GB 이상으로 늘리고 Apply & Restart.

### 컨테이너 누적으로 디스크 부족

테스트 비정상 종료 시 컨테이너가 남을 수 있음. 이미지도 함께 삭제되니 주의.

```bash
docker system prune -a --volumes
```

이미지 재다운로드가 부담스러우면 컨테이너만 정리.

```bash
docker system prune
```

---

## 참고 문서

| 문서 | 경로 |
|---|---|
| SDD (설계 문서 26개 챕터) | `docs/sdd/README.md` |
| 헌법 진입점 | `CLAUDE.md` |
| 절대 규칙 18개 | `DEVELOPMENT.md` |
| 데이터 룰 | `DATA.md` |
