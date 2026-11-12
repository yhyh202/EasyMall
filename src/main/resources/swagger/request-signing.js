// EasyMall request signing for Swagger UI. Prepended to swagger-initializer.js by SwaggerSigning.java.
// Login through POST /api/auth/login on this page: the token is authorized automatically and its
// signKey is kept in sessionStorage. Every request with "Authorization: Bearer ..." is then signed.
(function () {
  'use strict';
  var STORAGE = 'easymall.signKeys';
  var original = window.SwaggerUIBundle;
  if (typeof original !== 'function') return;

  function loadKeys() { try { return JSON.parse(sessionStorage.getItem(STORAGE)) || {}; } catch (e) { return {}; } }
  function saveKeys(keys) { try { sessionStorage.setItem(STORAGE, JSON.stringify(keys)); } catch (e) { /* private mode */ } }
  var keys = loadKeys();

  function header(headers, name) {
    for (var k in headers) if (Object.prototype.hasOwnProperty.call(headers, k) && k.toLowerCase() === name) return headers[k];
    return null;
  }
  function hex(buffer) { return Array.from(new Uint8Array(buffer), function (b) { return b.toString(16).padStart(2, '0'); }).join(''); }
  function base64(buffer) { return btoa(String.fromCharCode.apply(null, new Uint8Array(buffer))); }
  function rfc3986(text) {
    return encodeURIComponent(text).replace(/[!'()*]/g, function (c) { return '%' + c.charCodeAt(0).toString(16).toUpperCase(); });
  }
  function decode(text) { return decodeURIComponent(text.replace(/\+/g, ' ')); }
  // Same rules as RequestSignature.canonicalQuery: decode, re-encode per RFC 3986, sort by key then value.
  function canonicalQuery(search) {
    if (!search) return '';
    return search.split('&').filter(Boolean).map(function (part) {
      var i = part.indexOf('=');
      return [rfc3986(decode(i < 0 ? part : part.slice(0, i))), rfc3986(decode(i < 0 ? '' : part.slice(i + 1)))];
    }).sort(function (a, b) {
      return a[0] < b[0] ? -1 : a[0] > b[0] ? 1 : a[1] < b[1] ? -1 : a[1] > b[1] ? 1 : 0;
    }).map(function (p) { return p[0] + '=' + p[1]; }).join('&');
  }
  function nonce() {
    if (crypto.randomUUID) return crypto.randomUUID();
    var bytes = crypto.getRandomValues(new Uint8Array(16));
    return hex(bytes.buffer);
  }

  async function sign(request) {
    var auth = header(request.headers, 'authorization');
    if (!auth || auth.indexOf('Bearer ') !== 0) return request;
    if (!window.crypto || !crypto.subtle) {
      console.error('EasyMall: Web Crypto is only available on https or localhost; request sent unsigned.');
      return request;
    }
    var token = auth.slice(7);
    var signKey = keys[token];
    if (!signKey) {
      signKey = window.prompt('这个 token 没有对应的 signKey。请粘贴同一次登录响应里的 data.signKey：');
      if (!signKey) return request;
      keys[token] = signKey.trim(); saveKeys(keys); signKey = keys[token];
    }
    var body = request.body;
    if (body != null && typeof body !== 'string') {
      // Only JSON bodies are used by this API; sign exactly the bytes that will be sent.
      body = JSON.stringify(body); request.body = body;
    }
    var url = new URL(request.url, window.location.href);
    var timestamp = String(Date.now()), id = nonce();
    var bodyHash = hex(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(body || '')));
    var canonical = [request.method.toUpperCase(), url.pathname, canonicalQuery(url.search.slice(1)), timestamp, id, bodyHash].join('\n');
    var key = await crypto.subtle.importKey('raw', new TextEncoder().encode(signKey), { name: 'HMAC', hash: 'SHA-256' }, false, ['sign']);
    request.headers['X-Timestamp'] = timestamp;
    request.headers['X-Nonce'] = id;
    request.headers['X-Signature'] = base64(await crypto.subtle.sign('HMAC', key, new TextEncoder().encode(canonical)));
    return request;
  }

  function remember(response) {
    try {
      var path = new URL(response.url, window.location.href).pathname;
      if (!response.ok || !/\/api\/auth\/login$/.test(path)) return response;
      var result = response.body && typeof response.body === 'object' ? response.body : JSON.parse(response.text);
      var data = result && result.data;
      if (data && data.token && data.signKey) {
        keys[data.token] = data.signKey; saveKeys(keys);
        if (window.ui && window.ui.preauthorizeApiKey) window.ui.preauthorizeApiKey('bearerAuth', data.token);
      }
    } catch (e) { console.warn('EasyMall: could not read the login response', e); }
    return response;
  }

  function chain(first, second) {
    if (!first) return second;
    return function (value) { return Promise.resolve(first(value)).then(function (v) { return second(v || value); }); };
  }

  var wrapped = function (config) {
    config = config || {};
    config.requestInterceptor = chain(config.requestInterceptor, sign);
    config.responseInterceptor = chain(config.responseInterceptor, remember);
    return original(config);
  };
  Object.keys(original).forEach(function (k) { wrapped[k] = original[k]; });
  window.SwaggerUIBundle = wrapped;
})();
