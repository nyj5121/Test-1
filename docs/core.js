/* 단단 친구 승인 - 서명 핵심 로직 (브라우저와 Node 양쪽에서 동작) */
(function (root) {
  var cr = (typeof crypto !== 'undefined' && crypto.subtle) ? crypto : require('crypto').webcrypto;
  var subtle = cr.subtle;
  var enc = new TextEncoder();
  var dec = new TextDecoder();
  var EC = { name: 'ECDSA', namedCurve: 'P-256' };

  var b64u = {
    enc: function (buf) {
      var a = new Uint8Array(buf), s = '';
      for (var i = 0; i < a.length; i++) s += String.fromCharCode(a[i]);
      return btoa(s).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
    },
    dec: function (str) {
      str = String(str).replace(/\s+/g, '').replace(/-/g, '+').replace(/_/g, '/');
      while (str.length % 4) str += '=';
      var bin = atob(str), a = new Uint8Array(bin.length);
      for (var i = 0; i < bin.length; i++) a[i] = bin.charCodeAt(i);
      return a;
    }
  };

  async function generate() {
    var kp = await subtle.generateKey(EC, true, ['sign', 'verify']);
    return {
      priv: await subtle.exportKey('jwk', kp.privateKey),
      pub: await subtle.exportKey('jwk', kp.publicKey)
    };
  }

  async function spki(pubJwk) {
    var k = await subtle.importKey('jwk', pubJwk, EC, true, ['verify']);
    return b64u.enc(await subtle.exportKey('spki', k));
  }

  async function sign(privJwk, message) {
    var k = await subtle.importKey('jwk', privJwk, EC, false, ['sign']);
    var sig = await subtle.sign({ name: 'ECDSA', hash: 'SHA-256' }, k, enc.encode(message));
    return b64u.enc(sig);
  }

  /* 요청 문자열: d1|종류|이름|값|nonce|만료(초)|보낸 사람 */
  function parseReq(s) {
    var p = String(s).split('|');
    if (p.length < 7 || p[0] !== 'd1') return null;
    return { kind: p[1], label: p[2], param: p[3], nonce: p[4], exp: parseInt(p[5], 10), from: p[6], reason: p[7] || '', need: Math.max(1, parseInt(p[8], 10) || 1), fps: (p[9] || '').split('.').filter(Boolean) };
  }

  function describe(q) {
    var who = q.from || '친구';
    switch (q.kind) {
      case 'promise_extra': return who + '이(가) "' + q.label + '" 앱을 ' + q.param + '분 더 쓰게 해 달래요.';
      case 'app_unlock': return who + '이(가) "' + q.label + '" 앱의 잠금을 ' + q.param + '분 동안 풀어 달래요.';
      case 'phone_unlock': return who + '이(가) 폰 잠금을 지금 풀어 달래요.';
      case 'sched_edit': return who + '이(가) 잠금 예약(' + q.label + ')을 고치거나 지우게 해 달래요.';
      case 'relax': return who + '이(가) 철벽 모드 같은 잠금 강도 설정을 끄게 해 달래요.';
      case 'cfg_change': return who + '이(가) 설정을 바꾸려고 해요: ' + q.label;
      case 'dumb_free': return who + '이(가) 바보폰에서 ' + q.param + '분 동안 벗어나게 해 달래요.';
      default: return who + '의 요청: ' + q.label;
    }
  }

  /* 자유 시간 정책 요청: q1|기기번호|보낸 사람|현재 시간대|현재 만료(초) */
  function parseQ(s) {
    var p = String(s).split('|');
    if (p.length < 5 || p[0] !== 'q1') return null;
    return { dev: p[1], from: p[2], wins: p[3], exp: parseInt(p[4], 10) || 0, reason: p[5] || '', fps: (p[6] || '').split('.').filter(Boolean) };
  }

  /* 시간대 문자열 "HHMM-HHMM:요일마스크,..." (마스크: 월=1, 화=2, ... 일=64) */
  function parseWins(str) {
    var out = [];
    if (!str) return out;
    str.split(',').forEach(function (it) {
      var m = /^(\d{2})(\d{2})-(\d{2})(\d{2}):(\d+)$/.exec(it);
      if (m) out.push({ s: m[1] + ':' + m[2], e: m[3] + ':' + m[4], d: parseInt(m[5], 10) & 127 });
    });
    return out;
  }
  function encodeWins(rows) {
    return rows.map(function (r) { return r.s.replace(':', '') + '-' + r.e.replace(':', '') + ':' + (r.d & 127); }).join(',');
  }

  /* 정책 문자열 p1|기기번호|발급(초)|만료(초)|시간대|보낸 사람 을 서명해서 "정책.서명" 코드로 만든다 */
  async function signPolicy(privJwk, policy) {
    var sig = await sign(privJwk, policy);
    return b64u.enc(enc.encode(policy)) + '.' + sig;
  }

  var api = {
    b64u: b64u, enc: enc, dec: dec, generate: generate, spki: spki, sign: sign,
    parseReq: parseReq, describe: describe,
    parseQ: parseQ, parseWins: parseWins, encodeWins: encodeWins, signPolicy: signPolicy
  };
  if (typeof module !== 'undefined' && module.exports) module.exports = api; else root.DandanCore = api;
})(typeof self !== 'undefined' ? self : globalThis);
