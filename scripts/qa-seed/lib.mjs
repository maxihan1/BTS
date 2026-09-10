// QA 시드 공용 라이브러리 — REST 클라이언트·토큰 갱신·동시성 풀·매니페스트 입출력

import { readFile, writeFile } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import path from 'node:path';

/** 시드가 만든 모든 것에 붙는 표식. 롤백·식별의 단일 근거다. */
export const SEED_LABEL = 'qa-seed';

/** 15분 access token 보다 짧게 잡아 만료 직전 재로그인한다. */
const TOKEN_MARGIN_MS = 60_000;

export function parseArgs(argv) {
  const flags = new Set();
  const opts = {};
  const positional = [];
  for (const a of argv.slice(2)) {
    // ★em dash(—)·en dash(–) 를 하이픈으로 정규화한다. macOS 「스마트 대시」가 켜져 있으면
    //   붙여넣은 `--yes` 가 `—yes` 로 바뀌어 **조용히 인자에서 탈락한다**(2026-08-25 실측, 4회 연속).
    //   실패가 "인자를 무시했다" 로만 나타나 원인이 보이지 않으므로 입력 쪽에서 흡수한다.
    const norm = a.replace(/^[–—]+/, '--').replace(/^-{3,}/, '--');
    if (norm.startsWith('--') && norm.includes('=')) {
      const [k, v] = norm.slice(2).split(/=(.*)/s);
      opts[k] = v;
    } else if (norm.startsWith('--')) {
      flags.add(norm.slice(2));
    } else {
      // 대시 없이 적은 것도 받는다 — `yes`, `dry-run`, 그리고 6자리 TOTP.
      positional.push(a);
      flags.add(a);
      if (/^\d{6}$/.test(a)) opts.totp = a;
    }
  }
  return { flags, opts, positional };
}

export function requireEnv(name) {
  const v = process.env[name];
  if (!v) throw new Error(`환경변수 ${name} 가 필요하다. scripts/qa-seed/.env.local 에 적거나 export 해라.`);
  return v;
}

/**
 * `scripts/qa-seed/.env.local` 을 있으면 읽는다 (gitignored).
 *
 * ★자격증명을 추적되는 파일에 박지 않기 위한 자리다. 여기 값은 이미 export 된 환경변수를 덮지 않는다 —
 * CI 나 일회성 `BTS_QA_PAT=... node ...` 가 로컬 파일에 밀리면 디버깅이 불가능해진다.
 */
export function loadLocalEnv(dir) {
  const file = path.join(dir, '.env.local');
  if (!existsSync(file)) return false;
  const before = new Set(Object.keys(process.env));
  process.loadEnvFile(file);
  // loadEnvFile 은 기존 값을 덮으므로, 원래 있던 키는 되돌린다.
  for (const k of before) if (originalEnv.has(k)) process.env[k] = originalEnv.get(k);
  return true;
}

const originalEnv = new Map(Object.entries(process.env));

/**
 * REST 클라이언트. 401 을 만나면 한 번 재로그인하고 그 요청만 재시도한다.
 *
 * 재시도를 401 한정으로 두는 이유 — 5xx 를 자동 재시도하면 POST 가 중복 생성될 수 있다.
 * 이 스크립트의 쓰기는 대부분 비멱등이라 중복이 곧 오염이다.
 */
export class Api {
  constructor({ baseUrl, provider, username, password, pat = null, totp = null }) {
    this.baseUrl = baseUrl.replace(/\/$/, '');
    this.provider = provider;
    this.username = username;
    this.password = password;
    /** PAT 를 주면 비밀번호 로그인 자체를 하지 않는다 — MFA 경로를 통째로 우회한다. */
    this.pat = pat;
    /** 1회용 TOTP. 첫 로그인에서만 쓰고, 그 뒤로는 신뢰 디바이스 쿠키가 대신한다. */
    this.totp = totp;
    this.cookies = new Map();
    this.token = pat;
    this.tokenExpiresAt = pat ? Number.POSITIVE_INFINITY : 0;
    this.stats = { GET: 0, POST: 0, PUT: 0, PATCH: 0, DELETE: 0, retries: 0 };
  }

  /**
   * Set-Cookie 를 쿠키 항아리에 담는다.
   *
   * ★`trusted_device` 를 보존하는 것이 이 메서드의 존재 이유다 — MFA 검증 때 `trust_device:true` 로
   * 받은 그 쿠키가 있으면 이후 재로그인이 `completeLogin → isTrustedDevice` 에서 챌린지를 건너뛴다.
   * 이게 없으면 access token 이 15분마다 만료될 때마다 사람이 TOTP 를 다시 쳐야 해서 긴 적재가 불가능하다.
   */
  #storeCookies(res) {
    const raw = res.headers.getSetCookie?.() ?? [];
    for (const line of raw) {
      const [pair] = line.split(';');
      const idx = pair.indexOf('=');
      if (idx > 0) this.cookies.set(pair.slice(0, idx).trim(), pair.slice(idx + 1).trim());
    }
  }

  #cookieHeader() {
    return [...this.cookies].map(([k, v]) => `${k}=${v}`).join('; ');
  }

  async #postJson(path, body) {
    const headers = { 'content-type': 'application/json' };
    const cookie = this.#cookieHeader();
    if (cookie) headers.cookie = cookie;
    const res = await fetch(`${this.baseUrl}${path}`, { method: 'POST', headers, body: JSON.stringify(body) });
    this.#storeCookies(res);
    const text = await res.text();
    return { res, text, json: text ? JSON.parse(text) : null };
  }

  async login() {
    if (this.pat) { this.token = this.pat; return { access_token: this.pat }; }

    const first = await this.#postJson('/api/v1/auth/login', {
      provider: this.provider, username: this.username, password: this.password,
    });
    if (!first.res.ok) throw new Error(`로그인 실패 ${first.res.status}: ${first.text.slice(0, 300)}`);

    // ★MFA 챌린지는 401 이 아니라 **200 + mfa_required** 로 온다 (AuthController.completeLogin).
    //   ok 만 보고 access_token 을 꺼내면 undefined 가 담겨 이후 전 요청이 401 로 죽는다 — 실측 확인.
    let body = first.json;
    if (body?.mfa_required) {
      if (!this.totp) {
        throw new Error(
          'MFA 필요 — 이 계정은 2단계 인증이 켜져 있다. 아래 중 하나를 써라.\n' +
          '  (a) --totp=123456  인증 앱의 현재 6자리. 신뢰 디바이스로 등록해 이후 재로그인은 코드 없이 간다.\n' +
          '  (b) --pat=<토큰>   설정 > 개인 액세스 토큰에서 발급. MFA 경로를 통째로 건너뛴다.',
        );
      }
      const verified = await this.#postJson('/api/v1/auth/mfa/verify', {
        mfa_challenge_token: body.mfa_challenge_token,
        code: this.totp,
        method: 'totp',
        trust_device: true,
      });
      if (!verified.res.ok) {
        throw new Error(`MFA 검증 실패 ${verified.res.status}: ${verified.text.slice(0, 300)} — 코드가 만료됐을 수 있다. 새 코드로 다시 실행해라.`);
      }
      // 코드는 1회용이다. 재사용하면 replay 로 거부되므로 버리고, 다음부터는 신뢰 쿠키에 의존한다.
      this.totp = null;
      body = verified.json;
    }

    if (!body?.access_token) throw new Error(`로그인 응답에 access_token 이 없다: ${JSON.stringify(body).slice(0, 300)}`);
    this.token = body.access_token;
    this.tokenExpiresAt = Date.now() + (body.expires_in ?? 900) * 1000;
    return body;
  }

  async ensureToken() {
    if (!this.token || Date.now() > this.tokenExpiresAt - TOKEN_MARGIN_MS) await this.login();
  }

  async raw(method, path, { body, formData, expect } = {}) {
    await this.ensureToken();
    const send = async () => {
      const headers = { authorization: `Bearer ${this.token}` };
      let payload;
      if (formData) {
        payload = formData;
      } else if (body !== undefined) {
        headers['content-type'] = 'application/json';
        payload = JSON.stringify(body);
      }
      return fetch(`${this.baseUrl}${path}`, { method, headers, body: payload });
    };

    let res = await send();
    if (res.status === 401) {
      this.stats.retries += 1;
      await this.login();
      res = await send();
    }
    this.stats[method] = (this.stats[method] ?? 0) + 1;

    const text = await res.text();
    const ok = expect ? expect.includes(res.status) : res.ok;
    if (!ok) {
      const err = new Error(`${method} ${path} → ${res.status}: ${text.slice(0, 400)}`);
      err.status = res.status;
      err.bodyText = text;
      throw err;
    }
    return text ? JSON.parse(text) : null;
  }

  get(path, o) { return this.raw('GET', path, o); }
  post(path, body, o) { return this.raw('POST', path, { body, ...o }); }
  put(path, body, o) { return this.raw('PUT', path, { body, ...o }); }
  patch(path, body, o) { return this.raw('PATCH', path, { body, ...o }); }
  del(path, o) { return this.raw('DELETE', path, o); }
}

/** 동시 실행 상한을 지키며 매핑한다. 실패는 결과 배열에 Error 로 남기고 전체를 멈추지 않는다. */
export async function mapPool(items, limit, fn, onProgress) {
  const results = new Array(items.length);
  let cursor = 0;
  let done = 0;
  const worker = async () => {
    for (;;) {
      const i = cursor++;
      if (i >= items.length) return;
      try {
        results[i] = await fn(items[i], i);
      } catch (e) {
        results[i] = e instanceof Error ? e : new Error(String(e));
      }
      done += 1;
      onProgress?.(done, items.length, results[i]);
    }
  };
  await Promise.all(Array.from({ length: Math.min(limit, items.length) }, worker));
  return results;
}

export function splitOk(results) {
  const ok = results.filter((r) => !(r instanceof Error));
  const failed = results.filter((r) => r instanceof Error);
  return { ok, failed };
}

/** 결정적 난수 — 같은 seed 면 같은 데이터가 나와 재실행·재현이 가능하다. */
export function rng(seed) {
  let s = seed >>> 0;
  return () => {
    s = (s * 1664525 + 1013904223) >>> 0;
    return s / 4294967296;
  };
}

export const pick = (r, arr) => arr[Math.floor(r() * arr.length)];

export function pickMany(r, arr, n) {
  const copy = [...arr];
  const out = [];
  for (let i = 0; i < n && copy.length; i += 1) out.push(copy.splice(Math.floor(r() * copy.length), 1)[0]);
  return out;
}

export async function loadManifest(path) {
  if (!existsSync(path)) {
    return { baseUrl: null, createdAt: null, projects: [], issues: [], sprints: [], boards: [], dashboards: [] };
  }
  return JSON.parse(await readFile(path, 'utf8'));
}

export async function saveManifest(path, manifest) {
  await writeFile(path, `${JSON.stringify(manifest, null, 2)}\n`, 'utf8');
}
