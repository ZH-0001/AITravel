<script setup>
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Compass, Sparkles, MapPin, ArrowUpRight, ArrowRight, CalendarDays, Wallet, Users, Leaf, Landmark, Utensils, Coffee, Plus, Route, History, Check, X, LoaderCircle, ExternalLink, LogOut, ShieldCheck, Trash2, ChevronLeft, ChevronRight, BookOpen, Clock3, Layers, Menu, RefreshCw, Tent, TrainFront, CircleHelp, PencilLine } from 'lucide-vue-next'
import { api, refreshCsrf } from './api'

const meta = ref({ mode: 'demo', model: 'deepseek-v4-pro' })
const user = ref(null)
const view = ref('planner')
const connected = ref(false)
const booting = ref(true)
const authDialog = ref(false)
const authMode = ref('login')
const authBusy = ref(false)
const credentials = reactive({ username: '', password: '' })
const plans = ref([])
const tasks = ref([])
const selected = ref(null)
const versions = ref([])
const activeTask = ref(null)
const submitting = ref(false)
const pollError = ref('')
const formError = ref('')
const pendingSubmission = ref(null)
const revision = ref(null)
const feedback = ref('')
const planPage = ref(1)
const planMore = ref(false)
const taskPage = ref(1)
const taskMore = ref(false)
const detailBusy = ref(false)
const nicknameDialog = ref(false)
const newNickname = ref('')
const adminTab = ref('tasks')
const adminData = ref([])
const adminPage = ref(1)
const adminMore = ref(false)
const adminFilter = ref('')
let timer
const nowDate = () => { const d = new Date(); const p = new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit' }).formatToParts(d); return `${p.find(x => x.type === 'year').value}-${p.find(x => x.type === 'month').value}-${p.find(x => x.type === 'day').value}` }
const tomorrow = () => { const d = new Date(`${nowDate()}T12:00:00+08:00`); d.setUTCDate(d.getUTCDate() + 1); return d.toISOString().slice(0, 10) }
const form = reactive({ startCity: '上海', destinationCity: '', startDate: tomorrow(), travelDays: 3, travelerCount: 2, budget: 2500, preferences: ['自然风光', '人文古迹'], travelerType: '情侣', mustVisitText: '', remark: '' })
const preferenceOptions = [ { label: '自然风光', icon: Leaf }, { label: '人文古迹', icon: Landmark }, { label: '美食探店', icon: Utensils }, { label: '城市休闲', icon: Coffee } ]
const currentContent = computed(() => selected.value?.version?.content)
const active = computed(() => activeTask.value && ['WAITING', 'RUNNING'].includes(activeTask.value.status))
const phaseIndex = computed(() => ({ RETRIEVAL: 0, PLANNING: 1, VALIDATING: 2 }[activeTask.value?.phase] ?? -1))
const money = value => new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 2 }).format(Number(value || 0))
const dateLabel = value => value ? new Date(value).toLocaleString('zh-CN', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' }) : '—'
const taskName = status => ({ WAITING: '等待中', RUNNING: '进行中', SUCCEEDED: '已完成', FAILED: '未完成' }[status] || status)
const isSafeUrl = value => { try { return ['http:', 'https:'].includes(new URL(value).protocol) } catch { return false } }

onMounted(async () => {
  try {
    meta.value = await api('/meta'); connected.value = true
    await refreshCsrf()
    try { user.value = await api('/users/me') } catch (error) { if (error.status !== 401) throw error }
    if (user.value) await loadWorkspace()
  } catch (error) { ElMessage.error(error.message) } finally { booting.value = false }
})
onUnmounted(() => clearTimeout(timer))

function handle(error) {
  if (error.status === 401) { user.value = null; clearTimeout(timer); authDialog.value = true }
  ElMessage.error(error.message)
}
async function authenticate() {
  authBusy.value = true
  try {
    await refreshCsrf()
    if (authMode.value === 'register') await api('/auth/register', { method: 'POST', body: credentials })
    user.value = await api('/auth/login', { method: 'POST', body: credentials })
    credentials.password = ''; authDialog.value = false; await refreshCsrf(); await loadWorkspace()
    ElMessage.success('欢迎回来，旅程从这里开始')
  } catch (error) { handle(error) } finally { authBusy.value = false }
}
async function logout() {
  try {
    await api('/auth/logout', { method: 'POST' }); user.value = null; plans.value = []; tasks.value = []; selected.value = null; activeTask.value = null; revision.value = null; pendingSubmission.value = null; view.value = 'planner'; clearTimeout(timer); await refreshCsrf()
  } catch (error) { handle(error) }
}
async function loadWorkspace() {
  const [p, t] = await Promise.all([api(`/plans?page=${planPage.value}`), api(`/tasks?page=${taskPage.value}`)])
  plans.value = p.items; planMore.value = p.hasMore; tasks.value = t.items; taskMore.value = t.hasMore
  const running = t.items.find(x => ['WAITING', 'RUNNING'].includes(x.status))
  if (running) { activeTask.value = running; poll() }
}
async function changePlanPage(delta) { planPage.value = Math.max(1, planPage.value + delta); try { await loadWorkspace() } catch (e) { handle(e) } }
async function changeTaskPage(delta) { taskPage.value = Math.max(1, taskPage.value + delta); try { await loadWorkspace() } catch (e) { handle(e) } }
function togglePreference(p) { const i = form.preferences.indexOf(p); if (i < 0) form.preferences.push(p); else form.preferences.splice(i, 1) }
function setDestination(city) { form.destinationCity = city; form.mustVisitText = ''; document.getElementById('trip-form')?.scrollIntoView({ behavior: 'smooth', block: 'center' }) }
function changePeopleType(value) { if (value === '单人') form.travelerCount = 1; else if (value === '情侣') form.travelerCount = 2; else form.travelerCount = Math.max(2, form.travelerCount) }
function conditions() { const { mustVisitText, ...rest } = form; return { ...rest, mustVisitNames: mustVisitText.split(/[,，、\n]/).map(x => x.trim()).filter(Boolean) } }
async function submit() {
  formError.value = ''
  if (!user.value) { authDialog.value = true; return }
  if (!form.destinationCity.trim() || !form.startCity.trim() || !form.startDate || !form.budget || !form.preferences.length) { formError.value = '请填写城市、日期和预算，并至少选择一种偏好。'; return }
  if (revision.value && !feedback.value.trim()) { formError.value = '请填写本次修改意见。'; return }
  submitting.value = true
  const path = revision.value ? `/plans/${revision.value.plan_id}/revisions` : '/plans/generate'
  const body = { conditions: conditions(), feedback: revision.value ? feedback.value : '', baseVersionId: revision.value?.current_version_id || null }
  const fingerprint = JSON.stringify({ path, body })
  // Preserve the id across an ambiguous network failure; only explicit retries use a new id.
  if (pendingSubmission.value?.fingerprint !== fingerprint) pendingSubmission.value = { fingerprint, requestId: crypto.randomUUID() }
  try {
    activeTask.value = await api(path, { method: 'POST', body: { ...body, requestId: pendingSubmission.value.requestId } })
    pendingSubmission.value = null; view.value = 'planner'; selected.value = null; pollError.value = ''; poll()
    ElMessage.success('已提交，正在为你准备旅程')
  } catch (error) { formError.value = error.message; if (error.status) pendingSubmission.value = null; handle(error) } finally { submitting.value = false }
}
function poll() { clearTimeout(timer); timer = setTimeout(pollOnce, 1000) }
async function pollOnce() {
  if (!activeTask.value || !user.value) return
  try {
    const t = await api(`/tasks/${activeTask.value.task_id}`); activeTask.value = t; pollError.value = ''
    if (['WAITING', 'RUNNING'].includes(t.status)) timer = setTimeout(pollOnce, 2000)
    else {
      await loadWorkspace()
      if (t.status === 'SUCCEEDED') { revision.value = null; feedback.value = ''; ElMessage.success('行程已准备好'); await openPlan(t.plan_id) }
    }
  } catch (error) { pollError.value = error.message; if (error.status === 401) handle(error) }
}
async function openPlan(id, versionId = null) {
  detailBusy.value = true
  try {
    selected.value = await api(`/plans/${id}${versionId ? `/versions/${versionId}` : ''}`)
    versions.value = await api(`/plans/${id}/versions`); view.value = 'detail'
    window.scrollTo({ top: 0, behavior: 'smooth' })
  } catch (error) { handle(error) } finally { detailBusy.value = false }
}
function revise() {
  if (!selected.value) return
  const c = selected.value.version.conditions
  Object.assign(form, c, { startDate: c.startDate < nowDate() ? tomorrow() : c.startDate, mustVisitText: c.mustVisitNames.join('，') })
  revision.value = selected.value.plan; feedback.value = ''; formError.value = ''; view.value = 'planner'; window.scrollTo({ top: 0, behavior: 'smooth' })
}
function newTrip() { revision.value = null; feedback.value = ''; formError.value = ''; view.value = 'planner' }
function reloadPage() { window.location.reload() }
async function deletePlan(plan) {
  try {
    await ElMessageBox.confirm('删除后，该方案及所有历史版本将从列表中移除。', '删除这段行程？', { confirmButtonText: '删除方案', cancelButtonText: '保留', type: 'warning' })
    await api(`/plans/${plan.plan_id}`, { method: 'DELETE' }); if (selected.value?.plan.plan_id === plan.plan_id) { selected.value = null; view.value = 'plans' }; await loadWorkspace(); ElMessage.success('方案已删除')
  } catch (error) { if (error instanceof Error) handle(error) }
}
async function showTasks() { view.value = 'tasks'; if (user.value) try { await loadWorkspace() } catch (error) { handle(error) } }
async function showPlans() { view.value = 'plans'; if (user.value) try { await loadWorkspace() } catch (error) { handle(error) } }
async function loadAdmin() {
  try { const suffix = adminTab.value === 'tasks' ? (adminFilter.value ? `&status=${adminFilter.value}` : '') : adminTab.value === 'api-logs' ? (adminFilter.value ? `&stage=${adminFilter.value}` : '') : ''; const r = await api(`/admin/${adminTab.value}?page=${adminPage.value}${suffix}`); adminData.value = r.items; adminMore.value = r.hasMore; view.value = 'admin' } catch (e) { handle(e) }
}
async function changeAdmin(tab) { adminTab.value = tab; adminPage.value = 1; adminFilter.value = ''; await loadAdmin() }
async function saveNickname() { try { user.value = await api('/users/me', { method: 'PATCH', body: { nickname: newNickname.value } }); nicknameDialog.value = false; ElMessage.success('昵称已更新') } catch (error) { handle(error) } }
</script>

<template>
  <div class="app-shell">
    <aside class="sidebar">
      <a class="brand" href="#" @click.prevent="newTrip"><span class="brand-mark"><Compass :size="25" :stroke-width="1.6" /></span><span>拾途<span class="brand-en">TRAVEL WITH INTENT</span></span></a>
      <div class="sidebar-label">你的旅行空间</div>
      <nav class="main-nav" aria-label="主导航">
        <button :class="{ selected: view === 'planner' }" @click="newTrip"><Sparkles :size="18" /> 灵感规划 <span class="nav-new">AI</span></button>
        <button :class="{ selected: view === 'plans' || view === 'detail' }" @click="showPlans"><BookOpen :size="18" /> 我的行程 <span v-if="plans.length" class="nav-count">{{ plans.length }}{{ planMore ? '+' : '' }}</span></button>
        <button :class="{ selected: view === 'tasks' }" @click="showTasks"><History :size="18" /> 生成记录 <i v-if="active" class="live-dot" /></button>
        <button v-if="user?.role === 'ADMIN'" :class="{ selected: view === 'admin' }" @click="loadAdmin"><ShieldCheck :size="18" /> 运行管理</button>
      </nav>
      <div class="sidebar-note"><div class="note-illustration"><Route :size="30" :stroke-width="1.2" /><span>去看没见过的风景</span></div><p>把时间留给风景，<br/>把规划交给拾途。</p><button @click="newTrip">开启一段旅程 <ArrowUpRight :size="15" /></button></div>
      <div class="sidebar-bottom"><span class="model-indicator" /> DeepSeek V4 Pro <span class="model-mini">驱动</span></div>
    </aside>

    <div class="workspace">
      <header class="topbar">
        <div class="breadcrumb"><span class="mobile-brand"><Compass :size="22" /> 拾途</span><span class="desktop-breadcrumb">我的旅行空间 <span>/</span> <strong>{{ { planner: '灵感规划', plans: '我的行程', tasks: '生成记录', detail: '行程详情', admin: '运行管理' }[view] }}</strong></span></div>
        <div class="topbar-right"><span class="mode-badge" :class="{ live: meta.mode === 'live' }"><i /> {{ meta.mode === 'demo' ? '演示模式 · 无真实调用' : '联网模式' }}</span><template v-if="user"><button class="user-button" @click="newNickname = user.nickname; nicknameDialog = true"><span class="avatar">{{ user.nickname?.slice(0, 1) }}</span><span>{{ user.nickname }}</span></button><button class="icon-button" title="退出登录" @click="logout"><LogOut :size="17" /></button></template><button v-else class="login-button" @click="authDialog = true">登录 / 注册 <ArrowUpRight :size="15" /></button></div>
      </header>

      <main v-if="view === 'planner'" class="main-content">
        <div class="page-heading"><div><div class="eyebrow">A LITTLE PLAN, A BIG ADVENTURE</div><h1>{{ revision ? '让旅程，更合你的心意。' : '下一站，去哪里？' }}</h1><p>{{ revision ? '确认出行条件，告诉我们这次想改变什么。' : '说说你的期待，我们一起把远方变成计划。' }}</p></div><div class="today-mark"><Compass :size="32" :stroke-width="1" /><span>慢一点，也很远</span></div></div>

        <div v-if="!connected && !booting" class="connection-alert"><CircleHelp :size="18" /> 后端尚未连接，请启动服务后刷新页面。<button @click="reloadPage">刷新</button></div>
        <section class="hero-banner">
          <div class="hero-copy"><span class="hero-tag"><Sparkles :size="13" /> 有依据的旅行灵感</span><h2>不赶路，<br/>去感受路。</h2><p>从一座城、一种心情开始。<br/>让每一天的安排，都刚刚好。</p><div class="hero-caption"><span class="tiny-line"/> YOUR JOURNEY, YOUR PACE</div></div>
          <svg class="landscape" viewBox="0 0 780 340" aria-hidden="true"><defs><linearGradient id="hill" x1="0" y1="0" x2="0" y2="1"><stop stop-color="#adc5ac"/><stop offset="1" stop-color="#7d9e8d"/></linearGradient><linearGradient id="water" x1="0" y1="0" x2="1" y2="1"><stop stop-color="#c8dad2"/><stop offset="1" stop-color="#a6c5bd"/></linearGradient></defs><circle cx="568" cy="74" r="37" fill="#f5d9a3"/><path d="M90 255 229 88 355 219 466 98 627 235 715 162 800 240V340H90Z" fill="#c7d3bb"/><path d="m184 268 163-159 109 115 105-74 200 166v24H184Z" fill="url(#hill)"/><path d="M0 275c170-90 256 65 417-15s218-54 363-1v81H0Z" fill="#658d7a"/><path d="M263 340c72-77 273-10 297-80 11-36-16-65-2-91 3 36 48 54 34 89-30 70-169 42-190 82Z" fill="url(#water)"/><path d="M468 278v-18m-4 10 4-10 4 10m-64 24v-24m-6 12 6-15 6 15m230-22v-22m-6 13 6-15 6 15" stroke="#355e50" stroke-width="4" stroke-linecap="round"/><path d="m578 288 31-27 32 27Z" fill="#e8d5b5"/><path d="M584 288h50v24h-50z" fill="#f5e6cb"/><path d="M605 312v-17h10v17" fill="#88795f"/><path d="m617 261 24 27h-8l-19-23" fill="#beae93"/><path d="M654 320c21-18 46-19 71-10" fill="none" stroke="#d1d8b5" stroke-width="3" stroke-dasharray="4 7"/><g fill="#345b4c"><path d="m700 290 12-35 12 35Z"/><path d="m736 308 17-48 17 48Z"/></g><path d="M0 322c145-72 252 5 335 18H0Z" fill="#47725e"/><path d="M500 77q10-9 20 0m12-8q8-7 16 0" fill="none" stroke="#738b76" stroke-width="1.5"/></svg>
          <div class="hero-location"><MapPin :size="13" /> 山水之间，自在一点</div>
        </section>

        <section v-if="activeTask" class="task-progress" :class="{ failed: activeTask.status === 'FAILED' }" aria-live="polite">
          <div class="task-progress-title"><span class="progress-icon"><LoaderCircle v-if="active" class="spin" :size="21"/><Check v-else-if="activeTask.status === 'SUCCEEDED'" :size="21"/><CircleHelp v-else :size="21"/></span><div><strong>{{ active ? '正在为你准备旅程' : activeTask.status === 'SUCCEEDED' ? '你的行程已准备好' : '这次规划没有完成' }}</strong><p>{{ activeTask.error_summary || (meta.mode === 'demo' ? '当前使用模拟数据，不会发起联网或模型调用。' : '先查找资料，再规划行程，请稍候。') }}</p></div><span class="task-id">#{{ activeTask.task_id }}</span></div>
          <div v-if="active" class="stage-track"><span v-for="(label, i) in ['检索旅游信息', '规划每日行程', '校验并保存']" :key="label" :class="{ current: phaseIndex === i, complete: phaseIndex > i }"><Check v-if="phaseIndex > i" :size="13"/><span v-else class="step-number">{{ i + 1 }}</span>{{ label }}</span></div>
          <div v-if="pollError" class="poll-error">{{ pollError }} <button @click="pollOnce">恢复查询</button></div>
          <button v-if="activeTask.status === 'SUCCEEDED'" class="text-link" @click="openPlan(activeTask.plan_id)">查看行程 <ArrowRight :size="14"/></button>
          <button v-if="activeTask.status === 'FAILED'" class="text-link" @click="activeTask = null">调整条件后重新提交 <ArrowRight :size="14"/></button>
        </section>

        <div class="planner-grid">
          <section id="trip-form" class="card planning-card">
            <div class="section-title"><span class="section-icon"><Route :size="19" /></span><div><h2>{{ revision ? '调整这段旅程' : '定制你的旅程' }}</h2><p>一点偏好，让计划更懂你</p></div><span class="step-label">{{ revision ? '新增版本' : '01 — 开始探索' }}</span></div>
            <div v-if="revision" class="revision-note"><Layers :size="16"/> 修改方案 #{{ revision.plan_id }} · 原版本会完整保留<button @click="revision = null; feedback = ''">取消修改</button></div>
            <el-form :model="form" label-position="top" novalidate @submit.prevent="submit">
              <div class="field-grid"><el-form-item label="从哪里出发"><el-input v-model="form.startCity" :disabled="!!revision" placeholder="例如：上海" maxlength="100"><template #prefix><MapPin :size="16"/></template></el-input></el-form-item><el-form-item label="想去的城市"><el-input v-model="form.destinationCity" :disabled="!!revision" placeholder="例如：杭州" maxlength="100"><template #prefix><Compass :size="16"/></template></el-input></el-form-item></div>
              <div class="field-grid"><el-form-item label="出发日期"><el-date-picker v-model="form.startDate" type="date" value-format="YYYY-MM-DD" placeholder="选择日期" :disabled-date="date => date < new Date(`${nowDate()}T00:00:00+08:00`)" :clearable="false" /></el-form-item><el-form-item label="游玩天数"><el-input-number v-model="form.travelDays" :min="1" :max="7"/><span class="input-unit">天，按自己的节奏</span></el-form-item></div>
              <div class="field-grid"><el-form-item label="和谁一起"><el-select v-model="form.travelerType" @change="changePeopleType"><el-option v-for="type in ['单人', '情侣', '家庭', '朋友']" :key="type" :label="type" :value="type"/></el-select></el-form-item><el-form-item label="出行人数"><el-input-number v-model="form.travelerCount" :min="1" :max="10" :disabled="['单人', '情侣'].includes(form.travelerType)"/><span class="input-unit">人</span></el-form-item></div>
              <el-form-item label="目的地总预算"><el-input-number v-model="form.budget" :min="0.01" :max="99999999.99" :precision="2" :step="500" class="budget-input"/><span class="input-unit">元 / 全部人员</span><div class="field-hint">含当地交通、餐饮、门票和住宿；不含往返城际交通与购物。</div></el-form-item>
              <el-form-item label="你喜欢怎样的旅行"><div class="preference-options"><button v-for="p in preferenceOptions" :key="p.label" type="button" :aria-pressed="form.preferences.includes(p.label)" :class="{ chosen: form.preferences.includes(p.label) }" @click="togglePreference(p.label)"><component :is="p.icon" :size="16"/>{{ p.label }}<Check v-if="form.preferences.includes(p.label)" :size="13"/></button></div></el-form-item>
              <el-form-item><template #label>不想错过的景点 <span class="optional">选填</span></template><el-input v-model="form.mustVisitText" placeholder="例如：西湖，灵隐寺；多个名称用逗号分隔" maxlength="1000"/></el-form-item>
              <el-form-item><template #label>还有什么期待？ <span class="optional">选填</span></template><el-input v-model="form.remark" type="textarea" :rows="3" maxlength="500" show-word-limit placeholder="比如：想睡个懒觉、少走一点路，或想找一处安静的地方……"/></el-form-item>
              <el-form-item v-if="revision" label="本次修改意见"><el-input v-model="feedback" type="textarea" :rows="3" maxlength="500" show-word-limit placeholder="例如：减少步行，增加美食；预算调整请同步修改上方预算字段。"/></el-form-item>
              <p v-if="formError" class="form-error" role="alert">{{ formError }}</p>
              <button class="generate-button" type="submit" :disabled="submitting || active || !connected"><LoaderCircle v-if="submitting" class="spin" :size="18"/><Sparkles v-else :size="18"/>{{ submitting ? '正在提交…' : active ? '旅程准备中，请稍候' : revision ? '生成新的行程版本' : '生成我的专属行程' }}<ArrowRight v-if="!submitting && !active" :size="18"/></button>
              <p class="submit-note"><ShieldCheck :size="13"/>{{ meta.mode === 'demo' ? '演示模式：使用模拟资料，不搜索、不调用模型、不产生调用费用。' : '提交后必要出行条件将发送给 DeepSeek 和搜索服务，请勿填写敏感信息。' }}</p>
            </el-form>
          </section>

          <aside class="planner-aside">
            <section class="card inspiration-card"><div class="aside-title"><span>还没想好？从这里开始</span><ArrowUpRight :size="17"/></div><button class="destination hangzhou" @click="setDestination('杭州')"><div class="destination-art"><span class="sun"/><span class="mountain m1"/><span class="mountain m2"/><span class="lake"/><span class="pagoda">♜</span></div><div class="destination-bottom"><div><h3>杭州</h3><span>山水入城 · 慢享江南</span></div><span class="destination-arrow"><ArrowUpRight :size="18"/></span></div></button><div class="small-destinations"><button class="small-destination chengdu" @click="setDestination('成都')"><Leaf :size="25" :stroke-width="1.3"/><strong>成都</strong><span>把日子过慢一点</span><ArrowUpRight :size="14"/></button><button class="small-destination beijing" @click="setDestination('北京')"><Landmark :size="25" :stroke-width="1.3"/><strong>北京</strong><span>走进城市的故事</span><ArrowUpRight :size="14"/></button></div><p class="inspiration-footnote">城市卡片仅为灵感入口，不代表已检索。</p></section>
            <section class="card how-card"><div class="aside-title">一份安心的计划 <Leaf :size="17"/></div><div class="how-step"><span>01</span><div><strong>先找到资料</strong><p>联网查找景点，保留来源与查询时间。</p></div></div><div class="how-step"><span>02</span><div><strong>再安排旅程</strong><p>结合你的偏好，组织每日活动与参考预算。</p></div></div><div class="how-step"><span>03</span><div><strong>每次修改，都有记录</strong><p>保留成功版本，随时回看旅程如何成形。</p></div></div></section>
            <div class="gentle-note"><CircleHelp :size="16"/><p>旅行总有惊喜。行程与费用仅作参考，出发前记得核实开放时间、预约和价格。</p></div>
          </aside>
        </div>
        <footer class="page-footer"><span>拾途 SHITU <span>·</span> 让每段旅程，都有自己的节奏</span><span>AI 辅助规划 · 信息有据可查</span></footer>
      </main>

      <main v-else-if="view === 'plans'" class="main-content">
        <div class="page-heading"><div><div class="eyebrow">YOUR COLLECTION OF JOURNEYS</div><h1>把期待，存成行程。</h1><p>每一次出发，都有一份属于你的计划。</p></div><button class="primary-button" @click="newTrip"><Plus :size="17"/> 新建行程</button></div>
        <div v-if="!user || !plans.length" class="card empty-state"><BookOpen :size="45" :stroke-width="1"/><h2>{{ user ? '你的第一段旅程，正在等你' : '登录后，收藏你的远方' }}</h2><p>生成成功的行程会保存在这里，历史版本也会一起留下。</p><button class="primary-button" @click="user ? newTrip() : authDialog = true">{{ user ? '开始规划' : '登录 / 注册' }}<ArrowRight :size="16"/></button></div>
        <div v-else class="plan-cards"><article v-for="p in plans" :key="p.plan_id" class="card plan-card"><div class="plan-card-top"><span><MapPin :size="15"/>{{ p.destination_city }}</span><span class="version-badge">V{{ p.version_no }}</span></div><h2>{{ p.plan_name }}</h2><p>{{ p.start_city }}出发 <span>·</span> {{ dateLabel(p.update_time) }}更新</p><div class="plan-card-budget"><span>参考费用 <strong>¥{{ money(p.total_cost) }}</strong></span><span v-if="p.over_budget" class="over-label">超预算</span></div><div class="plan-card-actions"><button class="text-link" @click="openPlan(p.plan_id)">查看行程 <ArrowUpRight :size="16"/></button><button class="icon-button" title="删除方案" @click="deletePlan(p)"><Trash2 :size="16"/></button></div></article></div>
        <div v-if="plans.length" class="pagination"><button :disabled="planPage === 1" @click="changePlanPage(-1)"><ChevronLeft :size="16"/>上一页</button><span>{{ planPage }}</span><button :disabled="!planMore" @click="changePlanPage(1)">下一页<ChevronRight :size="16"/></button></div>
      </main>

      <main v-else-if="view === 'detail' && selected" class="main-content detail-content" v-loading="detailBusy">
        <button class="back-link" @click="view = 'plans'"><ChevronLeft :size="16"/> 我的行程</button><div class="page-heading"><div><div class="eyebrow">{{ selected.plan.destination_city }} / YOUR PERSONAL ITINERARY</div><h1>{{ currentContent.title }}</h1><p>{{ selected.version.conditions.startDate }} 出发 · {{ selected.version.conditions.travelDays }} 天 · {{ selected.version.conditions.travelerCount }} 人</p></div><button class="primary-button" :disabled="active || selected.version.version_id !== selected.plan.current_version_id" @click="revise"><PencilLine :size="16"/> 调整行程</button></div>
        <div v-if="currentContent.demo" class="demo-notice"><CircleHelp :size="17"/> 这是模拟行程，未执行真实搜索或 DeepSeek 调用，内容不能作为出行依据。</div>
        <section class="card detail-summary"><p>{{ currentContent.summary }}</p><div class="trip-metrics"><div><span>目的地参考费用</span><strong>¥{{ money(selected.version.total_cost) }}</strong></div><div><span>你的总预算</span><strong>¥{{ money(selected.version.conditions.budget) }}</strong></div><div><span>预算状态</span><strong :class="selected.version.over_budget ? 'amount-warning' : 'amount-good'">{{ selected.version.over_budget ? `超出 ¥${money(selected.version.budget_difference)}` : '在预算内' }}</strong></div><div><span>费用范围</span><small>全体人员 · 不含往返城际交通</small></div></div></section>
        <div class="detail-grid"><div><section v-for="day in currentContent.days" :key="day.day_no" class="card day-card"><div class="day-heading"><span class="day-number">DAY {{ String(day.day_no).padStart(2, '0') }}</span><span>{{ day.date }}</span><strong>¥{{ money(day.daily_total) }} <small>/ 当日</small></strong></div><div class="timeline"><article v-for="(a, index) in day.activities" :key="index" class="activity"><span class="activity-dot"><MapPin v-if="a.type === 'scenic'" :size="15"/><Utensils v-else-if="a.type === 'dining'" :size="15"/><Coffee v-else :size="15"/></span><div class="activity-content"><div class="activity-title"><h3>{{ a.scenic?.name || (a.type === 'dining' ? '品尝当地风味' : '自在休闲') }}</h3><span><Clock3 :size="12"/>{{ a.duration_minutes }} 分钟</span></div><p>{{ a.description }}</p><p v-if="a.scenic" class="activity-location"><MapPin :size="12"/>{{ a.scenic.address || a.scenic.district }}</p><p class="transport-note"><TrainFront :size="13"/>{{ a.transport_advice }}</p><div v-if="a.scenic" class="ticket-note">门票 ¥{{ money(a.ticket_used_per_person) }} / 人 <span>{{ currentContent.demo ? '模拟参考价' : a.ticket_basis }}</span><span v-for="source in a.scenic.source_ids" :key="source" class="source-chip">{{ source }}</span></div><p v-if="a.scenic?.opening_hours_note" class="opening-note">开放说明：{{ a.scenic.opening_hours_note }}</p></div></article></div><div class="day-expenses"><span>门票 ¥{{ money(day.expenses.tickets) }}</span><span>餐饮 ¥{{ money(day.expenses.dining) }}</span><span>交通 ¥{{ money(day.expenses.local_transport) }}</span><span>住宿 ¥{{ money(day.expenses.lodging) }}</span></div><p class="lodging-note"><Tent :size="14"/>{{ day.lodging_advice }}</p></section></div>
          <aside><section class="card version-card"><div class="aside-title">旅程的每个版本 <Layers :size="17"/></div><button v-for="v in versions" :key="v.version_id" class="version-row" :class="{ 'is-current': v.version_id === selected.version.version_id }" @click="openPlan(selected.plan.plan_id, v.version_id)"><span class="version-dot"/><div><strong>版本 {{ v.version_no }} <span v-if="v.version_id === selected.plan.current_version_id">最新</span></strong><small>{{ dateLabel(v.create_time) }}</small><p>{{ v.modification_feedback || '第一次规划，故事的开始' }}</p></div><Check v-if="v.version_id === selected.version.version_id" :size="15"/></button></section><section class="card sources-card"><div class="aside-title">这份计划的资料来源 <ExternalLink :size="16"/></div><div v-for="s in currentContent.sources" :key="s.id" class="source-item"><span>{{ s.id }}</span><div><a v-if="isSafeUrl(s.url)" :href="s.url" target="_blank" rel="noopener noreferrer">{{ s.title }}<ArrowUpRight :size="12"/></a><strong v-else>{{ s.title }}</strong><small>查询于 {{ dateLabel(s.retrieved_at) }}</small><small>{{ s.source_type }}{{ s.published_at ? ` · 页面时间 ${s.published_at}` : ' · 页面更新时间未知' }}</small></div></div><p>链接来自本次搜索结果；资料和价格可能变化，请出发前核实。</p></section></aside></div>
      </main>

      <main v-else-if="view === 'tasks'" class="main-content"><div class="page-heading"><div><div class="eyebrow">EVERY STEP COUNTS</div><h1>旅程如何成形。</h1><p>每次生成的进度与结果，都在这里。</p></div><button class="secondary-button" @click="showTasks"><RefreshCw :size="15"/> 刷新</button></div><div v-if="!user || !tasks.length" class="card empty-state"><History :size="42" :stroke-width="1"/><h2>还没有生成记录</h2><p>开始一份计划，留下一段新的期待。</p><button class="primary-button" @click="user ? newTrip() : authDialog = true">{{ user ? '去规划' : '登录 / 注册' }}</button></div><div v-else class="card task-table"><el-table :data="tasks"><el-table-column prop="task_id" label="任务" width="90"/><el-table-column label="类型" width="100"><template #default="{ row }">{{ row.task_type === 'CREATE' ? '新建行程' : '修改行程' }}</template></el-table-column><el-table-column label="状态" width="110"><template #default="{ row }"><span class="status-tag" :class="row.status.toLowerCase()">{{ taskName(row.status) }}</span></template></el-table-column><el-table-column label="时间" min-width="140"><template #default="{ row }">{{ dateLabel(row.create_time) }}</template></el-table-column><el-table-column label="说明" min-width="190"><template #default="{ row }">{{ row.error_summary || (row.mode === 'demo' ? '模拟数据 · 无外部调用' : ({ RETRIEVAL: '检索资料', PLANNING: '规划行程', VALIDATING: '校验保存' }[row.phase] || '等待执行')) }}</template></el-table-column><el-table-column label="操作" width="110"><template #default="{ row }"><button v-if="row.status === 'SUCCEEDED'" class="text-link" @click="openPlan(row.plan_id, row.result_version_id)">查看行程</button><button v-else-if="['WAITING', 'RUNNING'].includes(row.status)" class="text-link" @click="activeTask = row; view = 'planner'; poll()">查看进度</button></template></el-table-column></el-table><div class="pagination"><button :disabled="taskPage === 1" @click="changeTaskPage(-1)">上一页</button><span>{{ taskPage }}</span><button :disabled="!taskMore" @click="changeTaskPage(1)">下一页</button></div></div></main>

      <main v-else-if="view === 'admin'" class="main-content"><div class="page-heading"><div><div class="eyebrow">SYSTEM OBSERVATORY</div><h1>运行管理</h1><p>任务、来源调用与费用诊断，不展示用户完整条件。</p></div></div><div class="card admin-card"><el-tabs :model-value="adminTab" @tab-change="changeAdmin"><el-tab-pane name="tasks" label="任务状态"/><el-tab-pane name="api-logs" label="API 调用"/><el-tab-pane name="operation-logs" label="操作日志"/></el-tabs><div class="admin-filters"><el-select v-if="adminTab !== 'operation-logs'" v-model="adminFilter" clearable placeholder="全部状态 / 阶段" @change="adminPage = 1; loadAdmin()"><el-option v-for="value in (adminTab === 'tasks' ? ['WAITING', 'RUNNING', 'SUCCEEDED', 'FAILED'] : ['RETRIEVAL', 'PLANNING'])" :key="value" :value="value" :label="value"/></el-select><button class="secondary-button" @click="loadAdmin"><RefreshCw :size="14"/> 刷新</button></div><el-table :data="adminData"><el-table-column v-for="column in (adminTab === 'tasks' ? ['task_id', 'user_id', 'status', 'phase', 'mode', 'error_summary'] : adminTab === 'api-logs' ? ['task_id', 'stage', 'call_type', 'provider', 'status', 'cost_time_ms', 'estimated_cost', 'cost_status'] : ['actor_user_id', 'operation_type', 'target_id', 'task_id'])" :key="column" :prop="column" :label="({ task_id: '任务', user_id: '用户', status: '状态', phase: '阶段', mode: '模式', error_summary: '失败摘要', stage: '阶段', call_type: '类型', provider: '服务商', cost_time_ms: '耗时(ms)', estimated_cost: '估算费用', cost_status: '费用状态', actor_user_id: '操作人', operation_type: '操作', target_id: '对象', task_id: '任务' })[column]" min-width="110"/><el-table-column label="时间" min-width="170"><template #default="{ row }">{{ dateLabel(row.create_time) }}</template></el-table-column></el-table><div class="pagination"><button :disabled="adminPage === 1" @click="adminPage--; loadAdmin()">上一页</button><span>{{ adminPage }}</span><button :disabled="!adminMore" @click="adminPage++; loadAdmin()">下一页</button></div></div></main>
    </div>

    <el-dialog v-model="authDialog" width="420px" class="auth-dialog" :close-on-click-modal="false" destroy-on-close><div class="auth-brand"><Compass :size="30"/><span>拾途</span></div><h2>{{ authMode === 'login' ? '欢迎回到你的旅行空间' : '从这里，开启一段旅程' }}</h2><p>存下期待，让每份计划都有迹可循。</p><el-form label-position="top" @submit.prevent="authenticate"><el-form-item label="账号"><el-input v-model="credentials.username" maxlength="30" autocomplete="username" placeholder="3～30 位字母、数字或下划线"/></el-form-item><el-form-item label="密码"><el-input v-model="credentials.password" type="password" show-password :autocomplete="authMode === 'login' ? 'current-password' : 'new-password'" placeholder="至少 8 位密码"/></el-form-item><button class="generate-button" type="submit" :disabled="authBusy"><LoaderCircle v-if="authBusy" class="spin" :size="17"/>{{ authMode === 'login' ? '登录，继续探索' : '注册并开始探索' }}<ArrowRight :size="17"/></button></el-form><button class="auth-switch" @click="authMode = authMode === 'login' ? 'register' : 'login'">{{ authMode === 'login' ? '还没有账号？创建一个' : '已有账号？返回登录' }}</button></el-dialog>
    <el-dialog v-model="nicknameDialog" title="给自己一个旅行昵称" width="380px"><el-input v-model="newNickname" maxlength="50"/><template #footer><el-button @click="nicknameDialog = false">取消</el-button><el-button type="primary" @click="saveNickname">保存</el-button></template></el-dialog>
  </div>
</template>
