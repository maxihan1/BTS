// QA 계정에 CREATE_PROJECT 전역 권한을 1회 부여한다 — SYSTEM_ADMIN 계정으로만 호출된다

import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createInterface } from 'node:readline/promises';
import { Api, parseArgs, requireEnv, loadLocalEnv } from './lib.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
loadLocalEnv(HERE);
const { opts } = parseArgs(process.argv);

const BASE_URL = opts['base-url'] ?? process.env.BTS_BASE_URL ?? 'https://bts.maxihan.com';
const log = (...a) => console.log(...a);

/**
 * TOTP 를 인자로 받지 못하면 직접 묻는다.
 *
 * ★플래그로만 받지 않는 이유 — 스마트 대시가 켜진 환경에서 `--totp=` 가 `—totp=` 로 바뀌어
 * 조용히 인자에서 탈락한다(2026-08-25 실측). 코드는 30초마다 바뀌므로 .env.local 에도 못 둔다.
 * 물어보면 두 실패 모드가 함께 사라진다.
 */
async function askTotp() {
  // 파이프/비대화 셸에서는 물어봐야 답이 오지 않는다 — 매달리는 대신 사용법을 알리고 끝낸다.
  if (!process.stdin.isTTY) {
    throw new Error(
      'TOTP 가 없다. 대시 없이 6자리를 그대로 인자로 넘겨라.\n' +
      '  node scripts/qa-seed/grant.mjs 123456',
    );
  }
  const rl = createInterface({ input: process.stdin, output: process.stdout });
  try {
    const answer = (await rl.question('인증 앱의 TOTP 6자리 (MFA 없으면 그냥 Enter): ')).trim();
    return answer || null;
  } finally {
    rl.close();
  }
}

/**
 * 부여 주체는 SYSTEM_ADMIN 이어야 한다 (`requireSystemAdmin`). 시드 계정과 다르므로
 * `--admin-username` / `--admin-password` 로 **따로** 받는다 — .env.local 의 QA 계정을 덮어쓰지 않는다.
 */
async function main() {
  const target = opts.target ?? process.env.BTS_QA_USERNAME;
  if (!target) throw new Error('--target=<권한을 줄 계정 username> 이 필요하다.');

  const api = new Api({
    baseUrl: BASE_URL,
    provider: opts.provider ?? process.env.BTS_PROVIDER ?? 'local',
    username: opts['admin-username'] ?? requireEnv('BTS_ADMIN_USERNAME'),
    password: opts['admin-password'] ?? requireEnv('BTS_ADMIN_PASSWORD'),
    totp: opts.totp ?? process.env.BTS_ADMIN_TOTP ?? (await askTotp()),
  });

  log(`대상 서버: ${BASE_URL}`);
  await api.login();

  // username 으로 사용자를 찾는다. /users 는 봉투 없는 맨 배열을 준다.
  const users = await api.get(`/api/v1/users?query=${encodeURIComponent(target)}`);
  const user = users.find((u) => u.username === target || u.email === target);
  if (!user) throw new Error(`사용자 '${target}' 를 찾지 못했다. 조회된 후보: ${users.map((u) => u.username).join(', ') || '없음'}`);
  log(`대상 사용자: ${user.username} (${user.id})`);

  const existing = await api.get('/api/v1/admin/global-permissions');
  const already = (Array.isArray(existing) ? existing : existing?.data ?? [])
    .find((g) => g.granteeId === user.id && g.permission === 'CREATE_PROJECT');
  if (already) { log('이미 CREATE_PROJECT 를 보유하고 있다 — 아무것도 하지 않는다.'); return; }

  const created = await api.post('/api/v1/admin/global-permissions', {
    permission: 'CREATE_PROJECT',
    granteeType: 'USER',
    granteeId: user.id,
  });
  log(`부여 완료: ${JSON.stringify(created?.data ?? created).slice(0, 200)}`);
  log('\n이제 시더를 QA 계정으로 돌릴 수 있다: node scripts/qa-seed/seed.mjs --yes');
}

main().catch((e) => { console.error(`\n실패: ${e.message}`); process.exit(1); });
