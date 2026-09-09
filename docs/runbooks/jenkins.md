<!-- 젠킨스 컨트롤러 운영 절차 — 기동·접근·자원·복구. GitHub Actions 승계 대상 -->

# 젠킨스 운영 (`bts-jenkins`)

> 도입 2026-09-09 (P1). 계획 정본은 `~/.claude/plans/functional-kindling-map.md` P0~P8.
> **CI 정의는 이제 이것 하나다** — GitHub Actions 4종은 P4b 에서 철거했고,
> self-hosted 러너 2대도 등록 0건이다(2026-09-09 실측).
> [`self-hosted-runner.md`](self-hosted-runner.md) 는 폐지됐고 과거 기록으로만 남는다.

## 1. 어디에 있나

| 항목 | 값 |
|---|---|
| 호스트 | `101.79.19.193` — **운영 스택과 같은 머신** (Rocky 8.8 · 2코어 16GB) |
| 컨테이너 | `bts-jenkins` · 이미지 `bts-jenkins:local` |
| 설정 정본 | `infra/jenkins/casc.yaml` (JCasC) |
| 접근 | `http://127.0.0.1:18081/` — **루프백 전용. 외부에서 열리지 않는다** |
| 자원 | `mem_limit 6g` · `cpus 1.5` |

## 2. 접근 — SSH 터널

젠킨스는 `127.0.0.1` 에만 붙는다. 브라우저로 보려면 터널을 판다.

```bash
ssh -i ~/.ssh/ncp-bts.pem -N -L 18081:127.0.0.1:18081 root@101.79.19.193
# 그다음 로컬 브라우저에서 http://127.0.0.1:18081/
```

## 3. 기동 · 정지

**`bootstrap.sh` 를 거친다. `docker compose` 를 직접 부르지 않는다.**

```bash
cd /opt/bts/infra/jenkins
cp .env.example .env && vi .env     # JENKINS_ADMIN_PASSWORD 를 사람이 채운다
./bootstrap.sh up                   # 빌드 + 기동 + 루프백 검증
./bootstrap.sh logs
./bootstrap.sh down
./bootstrap.sh lock                 # 설치된 플러그인 실제 버전 고정
```

두 값이 저장소 정본에서 나와야 해서 래퍼가 있다 — `NODE_VERSION` ← `.nvmrc`,
`DOCKER_GID` ← `getent group docker`. `.env` 에 옮겨 적으면 정본이 바뀌어도 따라오지 않는다.
compose 에 `:?` 를 걸어 **비면 죽게** 해 두었으므로 래퍼를 건너뛰면 기동이 실패한다.

## 4. ★ 설계상 알아 둘 것

### 4-1. `network_mode: host` 다 — 포트 매핑이 아니다

Testcontainers 는 호스트 데몬에 컨테이너를 띄우고 **`localhost:<임의포트>`** 로 붙는다.
젠킨스를 브리지 네트워크에 두면 그 `localhost` 가 젠킨스 컨테이너 자신이라 못 붙는다.
`TESTCONTAINERS_HOST_OVERRIDE` 로 우회할 수 있지만 그 값이 또 하나의 목록이 된다.
호스트 네트워크는 그 문제를 통째로 없앤다.

### 4-2. 그래서 루프백 바인딩이 **유일한** 방어선이다

`network_mode: host` 에서는 compose 의 `ports:` 가 무시되고,
`infra/security/bts-docker-user.sh` 의 DROP 규칙도 **도움이 되지 않는다** —
그 규칙은 `FORWARD` 체인이고 host 네트워크 트래픽은 그 체인을 타지 않는다.

막는 것은 `JENKINS_OPTS=--httpListenAddress=127.0.0.1` 한 줄뿐이다.
그래서 `bootstrap.sh up` 이 기동 직후 `ss -tlnp` 로 **`0.0.0.0:18081` 이 아닌지 직접 확인**하고,
그렇다면 종료 코드 2 로 죽는다. 이 검사를 지우지 말 것.

### 4-3. DooD — 데몬은 호스트 것을 쓴다

`/var/run/docker.sock` 마운트. DinD 를 쓰면 이미지 캐시가 두 벌이 되고 2코어 머신에서
그 비용이 크다. 대신 **젠킨스가 띄우는 컨테이너는 호스트에 뜨고 `mem_limit 6g` 밖이다** —
Testcontainers 몫을 따로 계산해야 한다. 여유는 약 4.5GB
(16GB − 운영 4,992MB − 젠킨스 6,144MB).

### 4-4. `numExecutors: 1`

2코어다. 늘리면 Testcontainers 컨테이너까지 겹쳐 스왑으로 밀린다. 벽시계 이득은
러너 1대 구조에서 이미 0 이라는 것이 `backend-ci` 에서 실측됐다
(잡 합계 34.8분 · 벽시계 39.8분 — 거의 완전 직렬).

### 4-5. UI 에서 고친 설정은 사라진다

최초 실행 마법사를 껐고(`runSetupWizard=false`) 설정 정본은 `casc.yaml` 이다.
UI 변경은 컨테이너 재생성 시 없어진다 — 저장소를 고쳐라.

## 5. 플러그인 버전

`plugins.txt` 는 **무엇이 필요한가**만 갖고 버전을 적지 않는다. 지금 적는 숫자는
「오늘의 최신」일 뿐 검증된 조합이 아니라 가짜 정밀도가 된다.
재현의 정본은 `./bootstrap.sh lock` 이 만드는 `plugins.lock.txt` 다 — 기동에 성공한 뒤
실제 설치 버전을 뽑아 커밋한다.

## 6. 복구

| 증상 | 확인 | 처방 |
|---|---|---|
| 기동 안 됨 | `./bootstrap.sh logs` | `.env` 의 `JENKINS_ADMIN_PASSWORD` 가 비었는지부터 |
| `docker: permission denied` | `getent group docker` | `DOCKER_GID` 불일치 — `bootstrap.sh up` 재실행 |
| `18081` 이 `0.0.0.0` | `ss -tlnp \| grep 18081` | **즉시 down.** `JENKINS_OPTS` 확인 |
| Testcontainers 가 DB 에 못 붙음 | `docker ps` | host 네트워크가 풀렸는지 — compose 의 `network_mode` |
| 운영 응답 지연 | `uptime` · `docker stats` | `cpus` 를 낮춘다. 전량 빌드는 야간으로 |

## 7. 끝난 것 · 남은 것

**끝난 것** (2026-09-09)

| | |
|---|---|
| P1 컨트롤러 | JCasC · DooD · 루프백 바인딩 |
| P2 파이프라인 | 2단(빠른 게이트 · 전량) · `Jenkinsfile` |
| P5 훅 이전 | `.husky/pre-push` 에서 백엔드·프론트 테스트 제거. 푸시 14.5초 |
| P6 일부 | 배포 승인 + 배포 stage. **배포는 아직 한 번도 실행하지 않았다** |
| P7 하네스 | `jenkins-build-status.ts` 3분기 판정 · 게이트 2 배선 |
| P4 Actions 철거 | 판별식 7종 재조준 후 워크플로우 4종 삭제 |

**남은 것**

- **E2E·시각 회귀 미이전** (P3). 선행 조건 3개는 폐지된 `frontend-ci.yml` visual 잡 자리
  주석에 있었다 — 그 파일은 지웠으므로 git 이력에서 꺼내 쓴다
  (`git log --all --diff-filter=D -- .github/workflows/frontend-ci.yml`).
  ① 백엔드 기동 또는 MSW 전량 목킹 ② 그 위에서 기준 이미지 생성 ③ 20회 무변경 flaky 측정.
  기준 이미지는 맥 ARM64 에서 만들어져 Linux x86_64 에서 전량 diff 난다 — **재생성이 필요**하고
  `snapshot-baseline-guard.test.ts` 가 무단 갱신을 막는다.
- **P0 실측 미완** — 2코어 대비 배율이 아직 추정치다.
- **잡 파라미터·트리거 재등록** — `./bootstrap.sh job` 이 자동으로 처리한다(§3).

## 관련

- VM 하드닝 [`vm-hardening.md`](vm-hardening.md)
- 러너(**폐지**) [`self-hosted-runner.md`](self-hosted-runner.md) — 과거 기록
- 배포 [`infra/deploy/bts-deploy.sh`](../../infra/deploy/bts-deploy.sh) — P6 에서 젠킨스가 호출한다
