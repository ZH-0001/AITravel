let csrf = null

export async function refreshCsrf() {
  const r = await fetch('/api/auth/csrf', { credentials: 'same-origin' })
  if (!r.ok) throw new Error('无法连接服务，请确认后端已启动')
  csrf = await r.json()
}

export async function api(path, options = {}) {
  const method = options.method || 'GET'
  const headers = { ...options.headers }
  if (options.body !== undefined) headers['Content-Type'] = 'application/json'
  if (!['GET', 'HEAD'].includes(method)) {
    if (!csrf) await refreshCsrf()
    headers[csrf.headerName] = csrf.token
  }
  let response
  try {
    response = await fetch(`/api${path}`, { credentials: 'same-origin', ...options, headers, body: options.body === undefined ? undefined : JSON.stringify(options.body) })
  } catch { throw new Error('网络连接失败，请检查后端服务') }
  const body = await response.json().catch(() => ({}))
  if (!response.ok) {
    const error = new Error(body.message || `请求失败（${response.status}）`)
    error.status = response.status
    error.code = body.code
    throw error
  }
  return body
}
