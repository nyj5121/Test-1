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
    return { kind: p[1], label: p[2], param: p[3], nonce: p[4], exp: parseInt(p[5], 10), from: p[6] };
  }

  function describe(q) {
    var who = q.from || '친구';
    switch (q.kind) {
      case 'promise_extra': return who + '이(가) "' + q.label + '" 앱을 ' + q.param + '분 더 쓰게 해 달래요.';
      case 'app_unlock': return who + '이(가) "' + q.label + '" 앱의 잠금을 ' + q.param + '분 동안 풀어 달래요.';
      case 'phone_unlock': return who + '이(가) 폰 잠금을 지금 풀어 달래요.';
      case 'sched_edit': return who + '이(가) 잠금 예약(' + q.label + ')을 고치거나 지우게 해 달래요.';
      case 'relax': return who + '이(가) 철벽 모드 같은 잠금 강도 설정을 끄게 해 달래요.';
      case 'cfg_change': return who + '이(가) 친구 해제 설정을 바꾸려고 해요: ' + q.label;
      default: return who + '의 요청: ' + q.label;
    }
  }

  var api = { b64u: b64u, enc: enc, dec: dec, generate: generate, spki: spki, sign: sign, parseReq: parseReq, describe: describe };
  if (typeof module !== 'undefined' && module.exports) module.exports = api; else root.DandanCore = api;
})(typeof self !== 'undefined' ? self : globalThis);
