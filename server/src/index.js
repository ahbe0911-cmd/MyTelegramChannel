// Cloudflare Worker: shared-password gateway for a PUBLIC Telegram channel.
// This is an app access gate, NOT a way to make a public Telegram channel private.
const encoder = new TextEncoder();
const json = (data, status = 200) => new Response(JSON.stringify(data), {
  status, headers: { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store', 'x-content-type-options': 'nosniff' }
});
function b64u(bytes) { return btoa(String.fromCharCode(...bytes)).replace(/=/g, '').replace(/\+/g, '-').replace(/\//g, '_'); }
function fromB64u(value) {
  if (!/^[A-Za-z0-9_-]+$/.test(value)) throw Error('bad base64');
  return Uint8Array.from(atob(value.replace(/-/g, '+').replace(/_/g, '/') + '='.repeat((4 - value.length % 4) % 4)), c => c.charCodeAt(0));
}
function hex(bytes) { return [...bytes].map(x => x.toString(16).padStart(2, '0')).join(''); }
async function digest(value) { return hex(new Uint8Array(await crypto.subtle.digest('SHA-256', encoder.encode(value)))); }
function safeEqual(a, b) {
  const x = encoder.encode(String(a)), y = encoder.encode(String(b));
  let diff = x.length ^ y.length;
  for (let i = 0; i < Math.max(x.length, y.length); i++) diff |= (x[i] || 0) ^ (y[i] || 0);
  return diff === 0;
}
function isStrong(value) { return typeof value === 'string' && value.length >= 12 && value.length <= 128 && value.trim() === value; }
function newSalt() { return hex(crypto.getRandomValues(new Uint8Array(16))); }
async function passwordHash(password, salt, pepper) { return digest(`${salt}:${password}:${pepper}`); }
async function getConfig(env) {
  let config = await env.DB.prepare('SELECT salt, pass_hash, version, channel FROM config WHERE id = 1').first();
  if (config) return config;
  if (!isStrong(env.INITIAL_PASSWORD)) throw Error('INITIAL_PASSWORD must be 12-128 chars without surrounding spaces');
  const salt = newSalt(), passHash = await passwordHash(env.INITIAL_PASSWORD, salt, env.PASSWORD_PEPPER);
  await env.DB.prepare('INSERT OR IGNORE INTO config (id, salt, pass_hash, version, channel) VALUES (1, ?, ?, 1, ?)').bind(salt, passHash, '').run();
  return env.DB.prepare('SELECT salt, pass_hash, version, channel FROM config WHERE id = 1').first();
}
async function hmacKey(secret) {
  return crypto.subtle.importKey('raw', encoder.encode(secret), { name: 'HMAC', hash: 'SHA-256' }, false, ['sign', 'verify']);
}
async function issueToken(env, version) {
  const payload = b64u(encoder.encode(JSON.stringify({ v: version, exp: Date.now() + 30 * 60 * 1000, id: crypto.randomUUID() })));
  const signature = b64u(new Uint8Array(await crypto.subtle.sign('HMAC', await hmacKey(env.SESSION_SECRET), encoder.encode(payload))));
  return payload + '.' + signature;
}
async function verifyToken(env, config, token) {
  if (typeof token !== 'string' || token.length > 1000) return false;
  const parts = token.split('.');
  if (parts.length !== 2) return false;
  try {
    const valid = await crypto.subtle.verify('HMAC', await hmacKey(env.SESSION_SECRET), fromB64u(parts[1]), encoder.encode(parts[0]));
    if (!valid) return false;
    const claim = JSON.parse(new TextDecoder().decode(fromB64u(parts[0])));
    return claim.v === config.version && Number.isFinite(claim.exp) && claim.exp > Date.now();
  } catch { return false; }
}
async function bodyOf(request) {
  const raw = await request.text();
  if (raw.length > 2048) throw Error('request too large');
  const body = JSON.parse(raw);
  if (!body || typeof body !== 'object' || Array.isArray(body)) throw Error('bad json');
  return body;
}
async function attemptKey(env, request, tag) {
  const ip = request.headers.get('CF-Connecting-IP') || 'unknown';
  return digest(`${tag}:${ip}:${env.PASSWORD_PEPPER}`);
}
async function isBlocked(env, key) {
  const item = await env.DB.prepare('SELECT attempts, until_ms FROM throttle WHERE bucket = ?').bind(key).first();
  return Boolean(item && item.until_ms > Date.now() && item.attempts >= 5);
}
async function registerFailure(env, key) {
  const now = Date.now();
  const item = await env.DB.prepare('SELECT attempts, until_ms FROM throttle WHERE bucket = ?').bind(key).first();
  const attempts = (item && item.until_ms > now ? item.attempts : 0) + 1;
  await env.DB.prepare('INSERT INTO throttle (bucket, attempts, until_ms) VALUES (?, ?, ?) ON CONFLICT(bucket) DO UPDATE SET attempts = excluded.attempts, until_ms = excluded.until_ms')
    .bind(key, attempts, now + 15 * 60 * 1000).run();
}
async function clearFailures(env, key) {
  await env.DB.prepare('DELETE FROM throttle WHERE bucket = ?').bind(key).run();
}
function channelName(input) {
  if (typeof input !== 'string') return null;
  const match = input.trim().match(/^(?:https:\/\/t\.me\/|@)?([A-Za-z0-9_]{5,32})\/?$/i);
  return match ? match[1] : null;
}
export default {
  async fetch(request, env) {
    try {
      if (!env.DB || !env.ADMIN_KEY || env.ADMIN_KEY.length < 24 || !env.PASSWORD_PEPPER || env.PASSWORD_PEPPER.length < 24 || !env.SESSION_SECRET || env.SESSION_SECRET.length < 24) {
        return json({ error: 'Server is not configured' }, 503);
      }
      const route = new URL(request.url).pathname;
      if (request.method !== 'POST' || !['/login', '/session', '/admin/change', '/admin/channel'].includes(route)) return json({ error: 'Not found' }, 404);
      const body = await bodyOf(request);
      const config = await getConfig(env);
      if (route === '/login') {
        const key = await attemptKey(env, request, 'login');
        if (await isBlocked(env, key)) return json({ error: 'Too many attempts. Try again in 15 minutes.' }, 429);
        if (typeof body.password !== 'string' || !safeEqual(await passwordHash(body.password, config.salt, env.PASSWORD_PEPPER), config.pass_hash)) {
          await registerFailure(env, key);
          return json({ error: 'Incorrect password' }, 401);
        }
        await clearFailures(env, key);
        return json({ token: await issueToken(env, config.version), channel: config.channel });
      }
      if (route === '/session') {
        if (!await verifyToken(env, config, body.token)) return json({ error: 'Session expired or password changed' }, 401);
        return json({ ok: true, channel: config.channel });
      }
      const adminAttempt = await attemptKey(env, request, 'admin');
      if (await isBlocked(env, adminAttempt)) return json({ error: 'Too many attempts. Try again in 15 minutes.' }, 429);
      if (typeof body.adminKey !== 'string' || !safeEqual(body.adminKey, env.ADMIN_KEY)) {
        await registerFailure(env, adminAttempt);
        return json({ error: 'Invalid admin key' }, 403);
      }
      await clearFailures(env, adminAttempt);
      if (route === '/admin/change') {
        if (!isStrong(body.newPassword)) return json({ error: 'Use a password of 12-128 characters with no surrounding spaces' }, 422);
        const salt = newSalt(), hash = await passwordHash(body.newPassword, salt, env.PASSWORD_PEPPER);
        await env.DB.prepare('UPDATE config SET salt = ?, pass_hash = ?, version = version + 1 WHERE id = 1').bind(salt, hash).run();
        return json({ ok: true, message: 'Password updated; previous sessions invalidated' });
      }
      const channel = channelName(body.channel);
      if (!channel) return json({ error: 'Enter a public channel username such as mychannel or https://t.me/mychannel' }, 422);
      await env.DB.prepare('UPDATE config SET channel = ? WHERE id = 1').bind(channel).run();
      return json({ ok: true, channel });
    } catch (error) {
      if (error instanceof SyntaxError || ['request too large', 'bad json'].includes(error.message)) return json({ error: 'Invalid request' }, 400);
      console.error('Worker error:', error.message);
      return json({ error: 'Server error' }, 500);
    }
  }
};
