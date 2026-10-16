import { randomUUID } from 'node:crypto'
import { mkdir, writeFile } from 'node:fs/promises'

// Explicit, single-task live check. API keys stay in the backend process.
const base = 'http://127.0.0.1:8080/api'
let cookie = ''
let csrf = null
let taskId = null
const report = { testedAt: new Date().toISOString(), status: 'NOT_STARTED' }

async function request(path, method = 'GET', body) {
  const headers = { Cookie: cookie }
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  if (method !== 'GET' && csrf) headers[csrf.headerName] = csrf.token
  const response = await fetch(base + path, {
    method, headers, body: body === undefined ? undefined : JSON.stringify(body),
    redirect: 'error', signal: AbortSignal.timeout(15000),
  })
  const cookies = response.headers.getSetCookie()
  for (const value of cookies) if (value.startsWith('JSESSIONID=')) cookie = value.split(';')[0]
  const result = await response.json()
  if (!response.ok) throw new Error(`HTTP ${response.status}: ${result.code || 'REQUEST_FAILED'}`)
  return result
}

try {
  const meta = await request('/meta')
  if (meta.mode !== 'live' || meta.searchProvider !== 'Bocha') throw new Error('后端尚未以 live / Bocha 启动，请重启最新代码。')
  report.model = meta.model
  report.searchProvider = meta.searchProvider
  csrf = await request('/auth/csrf')
  const account = { username: `smoke_${randomUUID().slice(0, 8)}`, password: randomUUID() }
  await request('/auth/register', 'POST', account)
  await request('/auth/login', 'POST', account)
  csrf = await request('/auth/csrf')
  const date = new Date(Date.now() + 86400000).toLocaleDateString('en-CA', { timeZone: 'Asia/Shanghai' })
  // Only one submission. Never automatically resubmit after an error or timeout.
  const task = await request('/plans/generate', 'POST', {
    requestId: randomUUID(),
    conditions: { startCity: '上海', destinationCity: '杭州', startDate: date,
      travelDays: 1, travelerCount: 1, budget: 500, preferences: ['自然风光'],
      travelerType: '单人', mustVisitNames: [], remark: '首次接入测试，安排轻松的一日游。' },
  })
  taskId = task.task_id
  report.taskId = taskId
  console.log(`已提交一次真实任务 #${taskId}，不自动重试。`)
  const deadline = Date.now() + 300000
  let previous = ''
  while (Date.now() < deadline) {
    const state = await request(`/tasks/${taskId}`)
    const label = `${state.status}/${state.phase || ''}`
    if (label !== previous) { console.log(label); previous = label }
    if (state.status === 'FAILED') { report.errorCode = state.error_code; report.phaseTimings = state.phase_timings; throw new Error(`${state.error_code || 'TASK_FAILED'}: ${state.error_summary || '生成未完成'}`) }
    if (state.status === 'SUCCEEDED') {
      const detail = await request(`/plans/${state.plan_id}`)
      if (detail.version.content.demo || detail.version.research.demo) throw new Error('真实结果被标记为模拟。')
      if (detail.version.content.days.length !== 1 || !detail.version.research.sources.length) throw new Error('结果天数或来源校验失败。')
      report.status = 'SUCCEEDED'
      report.planId = state.plan_id
      report.versionId = state.result_version_id
      report.sourceCount = detail.version.research.sources.length
      report.candidateCount = detail.version.research.candidates.length
      report.totalCost = detail.version.total_cost
      report.phaseTimings = state.phase_timings
      console.log(`真实流程成功：${report.sourceCount} 条来源，${report.candidateCount} 个候选，参考费用 ${report.totalCost} 元。`)
      break
    }
    await new Promise(resolve => setTimeout(resolve, 2000))
  }
  if (report.status !== 'SUCCEEDED') throw new Error('查询等待结束；任务可能仍在运行，请勿立即重复提交。')
} catch (error) {
  report.status = 'FAILED'
  report.error = error.message
  console.error(`验证未完成：${error.message}${taskId ? `（任务 #${taskId}）` : ''}`)
  process.exitCode = 1
} finally {
  const dir = new URL('../backend/.data/', import.meta.url)
  await mkdir(dir, { recursive: true })
  await writeFile(new URL('live-check.json', dir), JSON.stringify(report, null, 2), 'utf8')
  if (csrf) await request('/auth/logout', 'POST').catch(() => {})
}
