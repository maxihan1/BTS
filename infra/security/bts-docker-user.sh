#!/bin/bash
# Docker publish 가 호스트 방화벽을 우회하는 것을 DOCKER-USER 체인에서 막는 심층방어
#
# ## 왜 필요한가
#
# Docker 는 `-p` 로 publish 한 포트를 `nat/PREROUTING` 과 `FORWARD` 에 직접 넣는다.
# 그래서 firewalld 나 `iptables INPUT` 정책을 **건너뛴다** — 「방화벽에서 8080 을 막았다」가
# `docker run -p 8080:8080` 에 대해 참이 아니다. 이것이 널리 알려진 함정이다.
#
# 2026-09-09 실측 시점 이 호스트(101.79.19.193)의 방어는 **NCP 보안그룹 한 겹뿐**이었다 —
# firewalld 미가동 · `iptables -P INPUT ACCEPT`. 외부 스캔에서 22/80/443 만 열려 있어
# 안전해 *보였지만*, 그것은 ACG 가 막아 준 것이지 호스트가 막은 것이 아니다.
# ACG 가 열리거나 실수로 `-p 8080:8080` 을 쓰면 그 순간 인터넷에 노출된다.
#
# ## 왜 이 방식인가 — firewalld 를 켜지 않는다
#
# Docker 가 실행 중인 호스트에서 firewalld 를 시작하면 iptables 체인이 재구성돼
# Docker 네트워킹이 깨지고, 복구에 `systemctl restart docker` 가 필요하다 —
# 운영 컨테이너 6개 재시작 = 다운타임이다. `DOCKER-USER` 는 그 대가 없이 같은 층을 만든다.
#
# ## ★SSH 락아웃이 발생할 수 없는 이유
#
# `DOCKER-USER` 는 **FORWARD** 체인에서만 참조된다. 호스트 자신에게 오는 트래픽(sshd)은
# `INPUT` 이라 이 스크립트의 영향을 받지 않는다. 규칙을 아무리 잘못 넣어도 SSH 는 살아 있고,
# 그래서 언제든 되돌릴 수 있다.
#
# ## 되돌리기
#
#   systemctl disable --now bts-docker-user.service
#   iptables -F DOCKER-USER && iptables -A DOCKER-USER -j RETURN
#
# 설치 위치 /usr/local/sbin/bts-docker-user.sh · 유닛 bts-docker-user.service (같은 디렉터리)
# 운영 절차 docs/runbooks/vm-hardening.md
set -euo pipefail

# ★인터페이스 이름을 상수로 적지 않는다. 적으면 이 파일과 호스트 실물이 서로를 검사하지
#   않는 두 목록이 된다 — 이름이 바뀌면 규칙이 **아무것도 안 걸린 채 조용히 통과**한다.
EXT="$(ip route get 8.8.8.8 | awk '{for (i = 1; i <= NF; i++) if ($i == "dev") print $(i + 1); exit}')"
if [ -z "$EXT" ]; then
  echo "외부 인터페이스 판정 실패 — 규칙을 넣지 않는다" >&2
  exit 1
fi

# 멱등. 우리 규칙만 지우고 다시 넣는다. Docker 가 만든 마지막 `-j RETURN` 은 건드리지 않는다.
# `while` 인 이유는 중복 적용된 과거 실행 잔재까지 전부 걷어내기 위해서다.
while iptables -D DOCKER-USER -i "$EXT" -m conntrack --ctstate RELATED,ESTABLISHED -j RETURN 2>/dev/null; do :; done
while iptables -D DOCKER-USER -i "$EXT" -p tcp --dport 80 -j RETURN 2>/dev/null; do :; done
while iptables -D DOCKER-USER -i "$EXT" -p tcp --dport 443 -j RETURN 2>/dev/null; do :; done
while iptables -D DOCKER-USER -i "$EXT" -j DROP 2>/dev/null; do :; done

# 순서가 규칙이다 — 허용 3개가 DROP 보다 앞에 와야 한다.
iptables -I DOCKER-USER 1 -i "$EXT" -m conntrack --ctstate ESTABLISHED,RELATED -j RETURN
iptables -I DOCKER-USER 2 -i "$EXT" -p tcp --dport 80 -j RETURN
iptables -I DOCKER-USER 3 -i "$EXT" -p tcp --dport 443 -j RETURN
iptables -I DOCKER-USER 4 -i "$EXT" -j DROP

echo "DOCKER-USER 규칙 적용 완료 (EXT=$EXT · 공개 80/443 · 그 밖의 publish 는 DROP)"
