import test from 'node:test';
import assert from 'node:assert/strict';
import worker from '../src/index.js';

class FakeD1 {
  config = null;
  throttles = new Map();
  prepare(sql) {
    const db = this;
    return {
      bind(...args) { this.args = args; return this; },
      async first() {
        const a = this.args || [];
        if (sql.includes('FROM config')) return db.config ? { ...db.config } : null;
        if (sql.includes('FROM throttle')) return db.throttles.get(a[0]) || null;
        throw Error(`Unsupported first: ${sql}`);
      },
      async run() {
        const a = this.args || [];
        if (sql.startsWith('INSERT OR IGNORE INTO config')) {
          db.config ||= { salt:a[0], pass_hash:a[1], version:1, channel:a[2] };
        } else if (sql.startsWith('UPDATE config SET salt')) {
          Object.assign(db.config, { salt:a[0], pass_hash:a[1], version:db.config.version + 1 });
        } else if (sql.startsWith('UPDATE config SET channel')) db.config.channel = a[0];
        else if (sql.startsWith('INSERT INTO throttle')) db.throttles.set(a[0], {attempts:a[1], until_ms:a[2]});
        else if (sql.startsWith('DELETE FROM throttle')) db.throttles.delete(a[0]);
        else throw Error(`Unsupported run: ${sql}`);
        return {success:true};
      }
    };
  }
}
const env = {
  DB: new FakeD1(), INITIAL_PASSWORD:'initial-password-123',
  ADMIN_KEY:'admin-secret-1234567890-long-enough',
  PASSWORD_PEPPER:'pepper-secret-1234567890-long-enough',
  SESSION_SECRET:'session-secret-1234567890-long-enough'
};
async function post(route, body, ip='203.0.113.1') {
  const res = await worker.fetch(new Request('https://example.workers.dev'+route, {
    method:'POST', headers:{'CF-Connecting-IP':ip,'Content-Type':'application/json'}, body: JSON.stringify(body)
  }), env);
  return [res.status, await res.json()];
}
test('login, admin channel, password rotation and old session revocation', async () => {
  let [code, data] = await post('/login', {password:'wrong'});
  assert.equal(code,401);
  [code, data] = await post('/login', {password:'initial-password-123'});
  assert.equal(code,200);
  const oldToken = data.token;
  assert.equal(data.channel,'');
  [code, data] = await post('/session', {token:oldToken});
  assert.equal(code,200);
  [code, data] = await post('/admin/channel', {adminKey:env.ADMIN_KEY, channel:'https://t.me/Channel_123'});
  assert.equal(code,200);
  assert.equal(data.channel,'Channel_123');
  [code, data] = await post('/admin/change', {adminKey:'wrong-admin', newPassword:'new-password-123'});
  assert.equal(code,403);
  [code, data] = await post('/admin/change', {adminKey:env.ADMIN_KEY, newPassword:'new-password-123'});
  assert.equal(code,200);
  [code, data] = await post('/session', {token:oldToken});
  assert.equal(code,401);
  [code, data] = await post('/login', {password:'initial-password-123'});
  assert.equal(code,401);
  [code, data] = await post('/login', {password:'new-password-123'});
  assert.equal(code,200);
  assert.equal(data.channel,'Channel_123');
  [code, data] = await post('/session', {token:data.token});
  assert.equal(code,200);
  console.log('PASS: login, admin authentication, channel update, password rotation, revoked old token');
});
test('incorrect credentials are throttled, good credentials do not consume failure limit', async () => {
  const ip='203.0.113.90';
  for (let n=0;n<5;n++) {
    const [code] = await post('/login',{password:'bad'},ip);
    assert.equal(code,401);
  }
  let [code] = await post('/login',{password:'new-password-123'},ip);
  assert.equal(code,429);
  const goodIp='203.0.113.91';
  for (let n=0;n<6;n++) {
    [code] = await post('/login',{password:'new-password-123'},goodIp);
    assert.equal(code,200);
  }
  console.log('PASS: bad login throttled, successful logins allowed');
});
test('reject invalid public channel and short passwords', async () => {
  let [code] = await post('/admin/channel',{adminKey:env.ADMIN_KEY, channel:'https://t.me/+privateInvite'});
  assert.equal(code,422);
  [code] = await post('/admin/change',{adminKey:env.ADMIN_KEY, newPassword:'1234'});
  assert.equal(code,422);
  console.log('PASS: private links and weak passwords rejected');
});
