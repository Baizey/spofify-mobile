const SAFE = new Set(['GET', 'HEAD']);
export class StaleOperation extends Error { constructor() { super('Session changed'); this.name = 'StaleOperation'; } }
export function localPath(path, origin) {
  if (typeof path !== 'string' || !path.startsWith('/') || path.startsWith('//') || /[\\\s\u0000-\u001f\u007f]/.test(path)) throw new TypeError('Same-origin path required');
  const url = new URL(path, origin);
  if (url.origin !== origin || url.hash || url.username || url.password) throw new TypeError('Same-origin path required');
  return url.pathname + url.search;
}
export function createTransport({origin, fetch = globalThis.fetch.bind(globalThis), onInvalid = () => {}}) {
  if (new URL(origin).origin !== origin) throw new TypeError('Canonical origin required');
  let epoch = 0, offline = true, identity = null, csrf = '', disposed = false;
  const pending = new Set();
  const fence = () => { epoch++; for (const controller of pending) controller.abort(); pending.clear(); };
  const token = () => ({epoch, accountId: identity?.accountId});
  const current = t => !disposed && t.epoch === epoch && t.accountId === identity?.accountId;
  async function request(path, options = {}) {
    if (disposed || offline) throw new Error('Offline mode');
    const target = localPath(path, origin), method = String(options.method || 'GET').toUpperCase();
    if (!SAFE.has(method) && target !== '/api/auth/login' && (!identity || !csrf)) throw new Error('Sign in required');
    const t = token(), abort = new AbortController();
    const relay = () => abort.abort(); options.signal?.addEventListener('abort', relay, {once: true});
    if (options.signal?.aborted) abort.abort();
    const headers = new Headers(options.headers);
    if (!SAFE.has(method) && csrf) headers.set('X-CSRF-Token', csrf);
    pending.add(abort);
    const timer = setTimeout(() => abort.abort(), options.timeoutMs ?? 30000);
    try {
      const response = await fetch(target, {...options, method, headers, signal: abort.signal, credentials: 'same-origin', cache: 'no-store', redirect: 'error',
        ...(!SAFE.has(method) ? {referrerPolicy: 'same-origin', referrer: origin + '/'} : {})});
      if (!current(t)) throw new StaleOperation();
      if (response.status === 401) { fence(); identity = null; csrf = ''; offline = true; onInvalid(); throw Object.assign(new Error('Sign in again'), {status:401}); }
      if (response.status === 403) { fence(); csrf = ''; onInvalid({verification:true}); throw Object.assign(new Error('Check your session'), {status:403}); }
      // Fence BODY completions, not just headers. The abort remains owned until
      // consumption, and no stale JSON/bytes can escape into the new namespace.
      const consume = async fn => {
        try { const value = await fn(); if (!current(t) || offline) throw new StaleOperation(); return value; }
        finally { cleanup(); }
      };
      let guardedBody;
      const wrapped = new Proxy(response, {get(obj, key) {
        if (['json','text','arrayBuffer','blob'].includes(key)) return () => consume(() => obj[key]());
        if (key === 'clone') return () => { throw new TypeError('Scoped responses cannot be cloned'); };
        if (key === 'body') {
          if (!obj.body) return null;
          if (!guardedBody) {
            const reader=obj.body.getReader();
            guardedBody=new ReadableStream({
              async pull(controller) {
                try {
                  const result=await reader.read();
                  if (!current(t) || offline) { await reader.cancel(); throw new StaleOperation(); }
                  if (result.done) { cleanup(); controller.close(); } else controller.enqueue(result.value);
                } catch(error) { cleanup(); controller.error(error); }
              },
              async cancel(reason) { cleanup(); await reader.cancel(reason); }
            });
          }
          return guardedBody;
        }
        const value = Reflect.get(obj,key,obj); return typeof value === 'function' ? value.bind(obj) : value;
      }});
      // Empty responses have no consumer. Other bodies remain bounded by timer.
      if ([204,205,304].includes(response.status) || method === 'HEAD') cleanup();
      return wrapped;
    } catch (error) { cleanup(); throw error; }
    function cleanup() { clearTimeout(timer); pending.delete(abort); options.signal?.removeEventListener('abort',relay); }
  }
  async function json(path, options) {
    const response = await request(path,options);
    if (!response.ok) { await response.text(); throw Object.assign(new Error('Request unavailable'), {status:response.status}); }
    return response.json();
  }
  function bind(session) {
    if (!session || !['accountId','username','role','csrfToken'].every(k => typeof session[k] === 'string' && session[k])) throw new Error('Invalid session');
    fence(); identity = Object.freeze({accountId:session.accountId, username:session.username, role:session.role}); csrf = session.csrfToken;
    return identity;
  }
  return {request,json,bind,token,current, accountId:()=>identity?.accountId || '', identity:()=>identity,
    online() { if (disposed) throw new Error('Stopped'); fence(); offline=false; },
    offline() { fence(); offline=true; csrf=''; },
    stop() { fence(); disposed=true; offline=true; csrf=''; identity=null; }
  };
}
