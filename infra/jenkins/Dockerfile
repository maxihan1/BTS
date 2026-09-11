# 젠킨스 컨트롤러 이미지 — 플러그인 고정 + docker CLI + `.nvmrc` 가 정한 node
#
# 데몬은 넣지 않는다(DinD 아님). 호스트 `/var/run/docker.sock` 을 마운트해 **호스트 데몬**을
# 쓴다 — Testcontainers 가 띄우는 컨테이너도 호스트에 뜨므로 컨테이너 안에 데몬을 또 두면
# 이미지 캐시가 두 벌이 되고 2코어 머신에서 그 비용이 크다.
#
# 빌드는 `bootstrap.sh` 로 한다. 손으로 `docker build` 하면 NODE_VERSION 이 비어 죽는다 —
# 일부러 그렇게 막았다. 아래 주석 참조.
ARG JENKINS_TAG=lts-jdk21
FROM jenkins/jenkins:${JENKINS_TAG}

USER root

# ── docker CLI ──────────────────────────────────────────────────────────────
# 정적 바이너리를 쓴다. apt 저장소를 붙이면 데몬 패키지까지 끌고 올 수 있다.
ARG DOCKER_CLI_VERSION=27.3.1
RUN curl -fsSL "https://download.docker.com/linux/static/stable/x86_64/docker-${DOCKER_CLI_VERSION}.tgz" -o /tmp/docker.tgz \
 && tar -xzf /tmp/docker.tgz -C /tmp \
 && install -m 0755 /tmp/docker/docker /usr/local/bin/docker \
 && rm -rf /tmp/docker /tmp/docker.tgz \
 && docker --version

# ── docker compose plugin ───────────────────────────────────────────────────
# ★compose v2 는 `docker` 바이너리의 일부가 **아니다.** 별도 cli-plugin 바이너리라
#   위 정적 docker CLI 만 넣으면 `docker: 'compose' is not a docker command` 다.
#   배포 스크립트 4단계가 전부 compose 이므로 이것이 없으면 배포가 성립하지 않는다
#   (2026-09-11 사전 점검에서 적발 — 배포 0회라 드러난 적이 없었다).
ARG DOCKER_COMPOSE_VERSION=2.29.7
RUN mkdir -p /usr/local/lib/docker/cli-plugins \
 && curl -fsSL "https://github.com/docker/compose/releases/download/v${DOCKER_COMPOSE_VERSION}/docker-compose-linux-x86_64" \
      -o /usr/local/lib/docker/cli-plugins/docker-compose \
 && chmod 0755 /usr/local/lib/docker/cli-plugins/docker-compose \
 && docker compose version

# ── rsync ───────────────────────────────────────────────────────────────────
# ★배포 스크립트 3단계가 `rsync -avz --delete` 다. 베이스 이미지에 없다(실측).
#   apt 를 쓰지만 데몬 패키지를 끌고 오지 않는 단일 패키지라 위 docker CLI 의
#   「정적 바이너리를 쓴다」 근거가 여기엔 적용되지 않는다.
RUN apt-get update \
 && apt-get install -y --no-install-recommends rsync \
 && rm -rf /var/lib/apt/lists/* \
 && rsync --version | head -1

# ── node ────────────────────────────────────────────────────────────────────
# ★버전 리터럴을 이 파일에 적지 않는다. 정본은 저장소 `.nvmrc` 하나이고 로컬(nvm/mise)도
#   같은 파일을 읽는다. 여기 숫자를 적으면 그 순간 두 목록이 되고, 서로를 검사하지 않는다 —
#   `node-ts-invocation.test.ts` 가 기록한 결함(로컬 22.14 · CI 22.23 에서 타입 스트리핑
#   기본 활성 여부가 갈려 판별식 20건이 조용히 0줄 실행)의 재발 경로다.
#
# ★그래도 이미지는 한번 구우면 굳는다. `.nvmrc` 가 나중에 바뀌면 이 이미지는 따라오지 않는다.
#   그 drift 는 Jenkinsfile 의 「node -v == .nvmrc」 단언이 잡는다(P2). 여기서 막을 수 없는
#   것을 막는 척하지 않는다.
#
# ★`.tar.gz` 를 쓴다. `.tar.xz` 가 더 작지만 젠킨스 베이스 이미지에 `xz` 가 없어
#   `tar (child): xz: Cannot exec` 로 죽는다(2026-09-09 실측). `xz-utils` 를 apt 로 넣는
#   대신 압축 형식을 바꾼다 — 이미지에 패키지를 하나 덜 얹는 쪽이 낫다.
ARG NODE_VERSION
RUN test -n "$NODE_VERSION" || { echo "NODE_VERSION 미지정 — infra/jenkins/bootstrap.sh 로 빌드하라" >&2; exit 1; } \
 && curl -fsSL "https://nodejs.org/dist/v${NODE_VERSION}/node-v${NODE_VERSION}-linux-x64.tar.gz" -o /tmp/node.tar.gz \
 && tar -xzf /tmp/node.tar.gz -C /usr/local --strip-components=1 \
 && rm /tmp/node.tar.gz \
 && node -v && npm -v

# pnpm 버전은 `package.json` 의 `packageManager` 가 정한다. corepack 이 실행 시점에 그것을
# 읽으므로 여기에 버전을 고정하지 않는다 — 고정하면 그것도 두 번째 목록이다.
RUN corepack enable

# ── 플러그인 ────────────────────────────────────────────────────────────────
#
# ★`--latest false` 를 쓰지 않는다. 그 플래그는 버전을 **고정**하는 것이 아니라 의존성이
#   요구하는 **최소 버전**을 깐다 — 2026-09-09 첫 빌드에서 `script-security`
#   (SECURITY-3986) 등 알려진 취약점 버전 5종이 그렇게 설치됐다. 이름이 주는 인상과
#   동작이 반대인 플래그다.
#
#   재현성은 버전을 여기 적어서가 아니라 `bootstrap.sh lock` 이 만드는 `plugins.lock.txt`
#   로 얻는다 — 「무엇이 필요한가」(plugins.txt)와 「무엇이 깔렸나」(lock)를 분리한다.
COPY plugins.txt /usr/share/jenkins/ref/plugins.txt
RUN jenkins-plugin-cli --plugin-file /usr/share/jenkins/ref/plugins.txt \
 && echo "설치 완료 — 실제 버전 고정은 bootstrap.sh lock"

USER jenkins

# JCasC 설정 위치. 컨테이너 기동 시 이 경로를 읽는다.
ENV CASC_JENKINS_CONFIG=/var/jenkins_conf/casc.yaml
# 최초 실행 마법사를 끈다 — 설정 정본은 casc.yaml 이고, 마법사를 거치면 UI 에서만 존재하는
# 설정이 생겨 저장소와 갈린다.
ENV JAVA_OPTS="-Djenkins.install.runSetupWizard=false -Dhudson.model.DownloadService.noSignatureCheck=true"
