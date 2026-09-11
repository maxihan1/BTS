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
| 자원 | `mem_limit 10g` · `cpus 2.0` (2026-09-09 상향 · 근거는 §4-6) |

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
./bootstrap.sh job [브랜치]         # 잡 정의 적용 (기본 main) + 재등록 확인까지 대기
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
그 비용이 크다. 대신 **젠킨스가 띄우는 컨테이너는 호스트에 뜨고 `mem_limit` 밖이다** —
Testcontainers 몫은 이 상한이 제한하지 않으므로 따로 계산해야 한다.

★`mem_limit 10g` 는 **예약이 아니라 상한**이다. 산술로만 보면
16GB − 운영 4,992MB − 젠킨스 10,240MB = 약 0.7GB 로 여유가 없어 보이지만,
실측 사용량은 1.5GiB 이고 스왑이 8GB 있다. 이 값을 10g 로 올린 이유는 여유가 아니라
**정본 때문**이다 — `backend/gradle.properties` 가 `-Xmx4096m` + kotlin 데몬 `-Xmx3072m`
= 7GB 를 선언하므로 6g 상한에서는 그 설정이 애초에 성립할 수 없었다.

### 4-6. named volume 은 root 소유로 생긴다 — 기동에서 안 걸린다

Docker 는 **이미지에 없는 경로**에 named volume 을 붙이면 그 디렉터리를 root:root 로 만든다.
`/var/jenkins_home/.gradle` 가 그 경우고, 컨테이너의 jenkins(uid 1000)는 못 쓴다.

실패가 나는 자리가 기동이 아니라는 것이 이 함정의 핵심이다. 컨테이너는 정상으로 뜨고
파이프라인도 돌다가 **첫 `./gradlew` 호출**에서 죽는다 — 빌드 #16 은 프론트 전량 30분을
다 돌고 나서 백엔드 시작 0초 만에 떨어졌다.

    Could not create parent directory for lock file
      /var/jenkins_home/.gradle/wrapper/dists/…/gradle-8.10-bin.zip.lck

`bootstrap.sh` 의 `VOLUME_PATHS` 가 소유권을 고치고,
`scripts/workflow/jenkins-volume-chown.test.ts` 가 compose 의 볼륨 경로와 그 목록의
차집합을 양방향으로 검사한다. **compose 에 볼륨을 추가하면 그 판별식이 먼저 red 가 된다.**

### 4-4. `numExecutors: 1`

2코어다. 늘리면 Testcontainers 컨테이너까지 겹쳐 스왑으로 밀린다. 벽시계 이득은
러너 1대 구조에서 이미 0 이라는 것이 `backend-ci` 에서 실측됐다
(잡 합계 34.8분 · 벽시계 39.8분 — 거의 완전 직렬).

### 4-5. UI 에서 고친 설정은 사라진다

최초 실행 마법사를 껐고(`runSetupWizard=false`) 설정 정본은 `casc.yaml` 이다.
UI 변경은 컨테이너 재생성 시 없어진다 — 저장소를 고쳐라.

### 4-7. `/opt/bts` 마운트는 됐는데 **쓸 수는 없었다**

첫 배포(빌드 #47)가 `rsync ... Permission denied (13)` · `exit 23` 으로 죽었다.

```
젠킨스 컨테이너   uid 1000(jenkins)
/opt/bts          501:20  drwxr-xr-x   ← others 는 읽기만
```

compose 에 `- /opt/bts:/opt/bts` 가 있으면 **읽을 때는** 충분해 보인다. `touch` 를 해봐야 보인다. `bootstrap.sh up` 이 컨테이너 안에서 실제로 써 보고, 못 쓰면 소유권을 교정한 뒤 **다시 써 본다.**

`chmod` 가 아니라 `chown` 인 이유. `rsync -a` 의 `-p` 가 소스 퍼미션을 복사하므로 `chmod -R g+w` 로 풀면 **첫 배포가 그 비트를 지우고 두 번째부터 실패한다.** 소유자는 mode 와 무관하게 쓸 수 있고 `-o`(owner)는 root 만 쓸 수 있어, chown 만이 배포마다 유지된다.

**목록이 둘이다.** 의미가 다르기 때문이다.

| 파일 | 뜻 | `backups` |
|---|---|---|
| `infra/deploy/protected-paths.txt` | 배포가 **지우면** 안 되는 것 (rsync `--delete` 제외) | 포함 |
| `infra/deploy/secret-paths.txt` | 파이프라인이 **읽으면** 안 되는 것 (chown 뒤 root 복구) | **제외** |

`backups/` 는 지워지면 안 되지만 배포 5단계가 **거기에 DB 덤프를 쓴다**. 한 목록으로 다루면 그것까지 root 로 잠겨 배포가 죽는다. 읽기는 `read-protected-paths.sh [protected｜secret]` 한 벌이다.

> ⚠️ 호스트의 uid 1000 은 `nbpmon`(네이버클라우드 모니터링)이고 젠킨스 uid 와 겹친다. 자격증명·JWT 서명키는 root 600/755 로 보호되지만 `/opt/bts` 소스 트리는 그 계정과 공유된다. docker.sock 이 이미 호스트-root 등가를 주므로 공격면이 늘지는 않는다.

### 4-8. 배포 중단 복구 — `abortPrevious` 가 배포도 죽인다

`bts-ci` 는 `disableConcurrentBuilds(abortPrevious: true)` 다. **배포가 도는 중 main 으로 푸시 하나가 들어오면 폴링이 새 빌드를 걸고 이 빌드는 그 자리에서 죽는다.**

실측(빌드 #48). `DEPLOY=true` 로 건 빌드가 폴링 빌드에 abort 됐고 결과가 `Finished: NOT_BUILT` 였다 — 빨간불이 아니라 **침묵**이다.

abort 는 interrupt 라 막지 못한다. 대신 `post { aborted }` 가 배포 진입 여부를 보고 **운영 헬스·컨테이너·되돌릴 수단**을 로그에 찍는다. 계약은 `scripts/workflow/deploy-abort-not-silent.test.ts`.

**배포를 걸 때는 그동안 main 에 푸시하지 않는다.** 겹칠 것 같으면 폴링 빌드가 끝난 뒤에 건다.

중단됐다면 어디서 죽었는지에 따라 다르다.

| 죽은 지점 | 운영 상태 | 처방 |
|---|---|---|
| 전량 검증 중 | 무사 | 다시 걸면 된다 |
| rsync 중 | 무사 (이미지가 서비스한다) | 다시 걸면 rsync 가 맞춘다 |
| `compose build` 중 | 무사 | 다시 걸면 된다 |
| `compose up` 중 | **반쯤 갈렸을 수 있다** | 아래 롤백 |

```bash
# 롤백 — <STAMP> 는 배포 로그의 predeploy 덤프 이름에서
docker exec -i bts-postgres pg_restore -U bts -d bts -c < /opt/bts/backups/predeploy-<STAMP>.dump
docker tag bts-backend:rollback-<STAMP> bts-backend:local
docker tag bts-web:rollback-<STAMP> bts-web:local
docker compose -f /opt/bts/infra/docker-compose.prod.yml --env-file /opt/bts/infra/prod/.env up -d
```

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
| 배포가 `Permission denied (13)` · `exit 23` | `docker exec bts-jenkins touch /opt/bts/.p` | `./bootstrap.sh up` — 소유권을 교정한다 (§4-7) |
| 배포 빌드가 `NOT_BUILT` 로 끝남 | 빌드 로그 맨 끝 | abortPrevious 다 (§4-8). 배포 중이었으면 로그에 운영 상태가 찍혀 있다 |

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

- **시각 회귀 미이전** (P3). 기준 이미지 0장이고 `visual` 프로젝트를 돌리는 파이프라인이
  없다(`chromium` 프로젝트가 `testIgnore: '**/e2e/visual/**'` 이다). 선행 조건 3개는
  폐지된 `frontend-ci.yml` visual 잡 자리
  주석에 있었다 — 그 파일은 지웠으므로 git 이력에서 꺼내 쓴다.
  ① 백엔드 기동 또는 MSW 전량 목킹 ② 그 위에서 기준 이미지 생성 ③ 20회 무변경 flaky 측정.

  ★**「재생성」이 아니라 최초 생성이다** (2026-09-09 정정). 이 저장소에 커밋된 스냅샷 기준
  이미지는 추적 파일 기준 **0장**이다. 종전 계획은 「맥 ARM64 PNG 가 Linux 에서 전량 diff
  나므로 재생성이 필요하다」고 적었는데, diff 날 원본 자체가 없다.
  `snapshot-baseline-guard.test.ts` 가 막는 것은 **갱신**이고, 최초 생성은 그 판별식의
  대상이 아니다 — 첫 커밋에서 `apps/web/src/**` 동반 여부를 사람이 봐야 한다.

  ★그동안 **시각 회귀를 막는 기계는 0개다.** `frontend-ci.yml` 의 `visual` 잡을 지웠고
  젠킨스로는 아직 안 옮겼다. 그 사이 UI 변경의 시각 검증은 브라우저 눈확인 한 겹뿐이다.
- **P0 실측 진행 중** — 프론트 전량은 실측됐다(아래). 백엔드 Gradle 은 빌드 #16 이
  볼륨 권한으로 죽어 아직 한 번도 완주하지 않았다.

  | 프론트 전량 (521파일 · 빌드 #16 실측) | |
  |---|---|
  | 벽시계 | **30.1분** |
  | environment(jsdom) | 690.2초 — 단일 최대 |
  | tests(실제 테스트) | 481.2초 — **전체의 27%뿐** |
  | setup · import · transform | 289.1 · 211.7 · 20.2초 |

  나머지 73%가 준비 비용이다. `pool: 'threads'` 는 그중 fork 비용만 걷어낸다
  (109파일 기준 −18.5% 실측). `isolate: false` 로 `environment` 690초를 없앨 수 있지만
  쓰지 않는다 — 근거는 `apps/web/vitest.config.ts` 주석.
- **잡 파라미터·트리거 재등록** — `./bootstrap.sh job` 이 자동으로 처리한다(§3).

## 관련

- VM 하드닝 [`vm-hardening.md`](vm-hardening.md)
- 러너(**폐지**) [`self-hosted-runner.md`](self-hosted-runner.md) — 과거 기록
- 배포 [`infra/deploy/bts-deploy.sh`](../../infra/deploy/bts-deploy.sh) — P6 에서 젠킨스가 호출한다
