<template>
  <section
    v-if="open"
    id="mes-assistant-panel"
    class="mes-assistant"
    aria-labelledby="mes-assistant-heading"
    @keydown.esc.stop.prevent="$emit('close')"
  >
    <header class="assistant-header">
      <el-icon class="assistant-icon" aria-hidden="true"><ChatDotRound /></el-icon>
      <h2 id="mes-assistant-heading">云枢小助手</h2>
      <div class="assistant-header-actions">
        <el-button v-if="turns.length" text size="small" :disabled="Boolean(busy)" @click="newSession">新会话</el-button>
        <el-button text circle aria-label="关闭云枢小助手" @click="$emit('close')"><el-icon><Close /></el-icon></el-button>
      </div>
    </header>

    <div ref="transcript" class="assistant-transcript">
      <div v-if="!turns.length" class="assistant-welcome">
        <h3>今天要做什么？</h3>
        <button v-for="example in examples" :key="example" type="button" class="assistant-example" @click="useExample(example)">
          {{ example }}
          <el-icon aria-hidden="true"><ArrowRight /></el-icon>
        </button>
      </div>
      <div v-else role="log" aria-label="助手对话">
        <article v-for="(turn, index) in turns" :key="index" class="assistant-turn" :class="'assistant-turn--' + turn.role">
          <div v-if="turn.role === 'user'" class="assistant-question">{{ turn.message }}</div>
          <p v-else class="assistant-message">{{ turn.message }}</p>
        </article>
      </div>
      <p v-if="busy" class="assistant-state" role="status">{{ busy }}</p>
      <p v-if="error" class="assistant-state assistant-error" role="alert">{{ error }}</p>

      <section v-if="state?.result" class="assistant-plan" aria-label="查询结果">
        <div class="plan-heading">
          <h3>{{ state.result.page }}</h3>
          <span class="plan-state">共 {{ state.result.total }} 条</span>
        </div>
        <p v-if="state.result.rows.length && state.result.rows.length < state.result.total" class="plan-description">
          显示前 {{ state.result.rows.length }} 条
        </p>
        <details v-for="(row, index) in state.result.rows" :key="index" class="plan-table" :open="index === 0">
          <summary>{{ Object.values(row)[0] || '记录 ' + (index + 1) }}</summary>
          <dl class="assistant-details">
            <template v-for="(value, label) in row" :key="label">
              <dt>{{ label }}</dt><dd>{{ value }}</dd>
            </template>
          </dl>
        </details>
        <div class="plan-actions">
          <RouterLink :to="state.result.link.path" @click="afterLink">打开页面</RouterLink>
        </div>
      </section>
      <section v-if="state?.operation" class="assistant-plan" aria-label="业务操作方案">
        <div class="plan-heading">
          <h3>{{ state.operation.operation }}</h3>
          <span class="plan-state" :class="{ 'is-published': state.businessApplied }">{{ state.businessApplied ? '已执行' : '待确认' }}</span>
        </div>
        <p class="plan-description">{{ state.operation.page }}</p>
        <p v-if="!state.businessApplied && state.operation.confirmText" class="plan-description">{{ state.operation.confirmText }}</p>
        <dl class="assistant-details">
          <template v-for="(value, label) in state.operation.details" :key="label">
            <dt>{{ label }}</dt><dd>{{ value }}</dd>
          </template>
        </dl>
        <div class="plan-actions">
          <RouterLink :to="state.operation.link.path" @click="afterLink">打开页面</RouterLink>
          <el-button v-if="!state.businessApplied" type="primary" :disabled="Boolean(busy)" @click="confirmBusiness">
            确认{{ state.operation.operation }}
          </el-button>
        </div>
      </section>

      <section v-if="state?.draft" class="assistant-plan" aria-label="应用上线方案">
        <div class="plan-heading">
          <h3>{{ state.draft.name }}</h3>
          <span class="plan-state" :class="{ 'is-published': state.published }">{{ state.published ? '已上线' : '草稿已保存' }}</span>
        </div>
        <p v-if="state.draft.description" class="plan-description">{{ state.draft.description }}</p>
        <template v-if="!state.published">
          <details v-for="table in state.draft.tables" :key="table.key" class="plan-table">
            <summary>{{ table.name }} <span>{{ table.fields.length }} 个字段</span></summary>
            <ul>
              <li v-for="field in table.fields" :key="field.key">
                <strong>{{ field.name }}</strong>
                <span>{{ describeField(field) }}{{ field.required ? ' · 必填' : '' }}</span>
              </li>
            </ul>
          </details>
          <p class="plan-description">每张表上线后会生成录入表单、列表、筛选和菜单。</p>
          <label class="plan-tenant">
            <span>授权租户</span>
            <select :value="state.tenantId || ''" :disabled="Boolean(busy)" @change="selectTenant($event.target.value)">
              <option value="">选择已有租户</option>
              <option v-for="tenant in state.tenants" :key="tenant.id" :value="tenant.id">{{ tenant.name }}（{{ tenant.code }}）</option>
            </select>
          </label>
          <div class="plan-actions">
            <RouterLink to="/studio/apps" @click="afterLink">查看应用草稿</RouterLink>
            <el-button type="primary" :loading="publishing" :disabled="Boolean(busy) || !selectedTenant" @click="publish">
              确认发布并授权
            </el-button>
          </div>
        </template>
        <template v-else>
          <p class="plan-description">已发布 {{ state.draft.tables.length }} 张表，并授权给 {{ state.publishedTenantName }}。</p>
          <div class="assistant-links" aria-label="已上线页面">
            <RouterLink v-for="link in visibleLinks" :key="link.path" :to="link.path" @click="afterLink">
              打开{{ link.label }} <el-icon aria-hidden="true"><ArrowRight /></el-icon>
            </RouterLink>
            <RouterLink to="/studio/apps" @click="afterLink">打开应用管理 <el-icon aria-hidden="true"><ArrowRight /></el-icon></RouterLink>
          </div>
        </template>
      </section>
      <section v-if="state?.iam" class="assistant-plan" aria-label="租户与岗位角色方案">
        <div class="plan-heading">
          <h3>{{ state.iam.action }} · {{ state.iam.name }}</h3>
          <span class="plan-state" :class="{ 'is-published': state.iamApplied }">{{ state.iamApplied ? '已完成' : '待确认' }}</span>
        </div>
        <p class="plan-description">{{ state.iam.code }} · {{ state.iam.dataScope ? '所属租户' : '上级租户' }} {{ state.iam.tenantName }}</p>
        <template v-if="state.iam.dataScope">
          <p class="plan-description">数据范围 {{ state.iam.dataScope }}</p>
          <p class="plan-description">可见菜单 {{ state.iam.menuScope.join('、') }}</p>
          <details class="plan-table" open>
            <summary>保存后的权限 <span>{{ state.iam.services.length }} 项</span></summary>
            <ul><li v-for="service in state.iam.services" :key="service">{{ service }}</li></ul>
          </details>
          <details v-if="state.iam.removedServices.length" class="plan-table" open>
            <summary>将移除的权限</summary>
            <ul><li v-for="service in state.iam.removedServices" :key="service">{{ service }}</li></ul>
          </details>
        </template>
        <div class="plan-actions">
          <RouterLink :to="authStore.isSystemAdmin ? '/studio/iam' : '/tenant-admin'" @click="afterLink">查看租户与角色</RouterLink>
          <el-button v-if="!state.iamApplied" type="primary" :disabled="Boolean(busy)" @click="confirmIam">确认{{ state.iam.action }}</el-button>
        </div>
      </section>
      <section v-if="state?.edit" class="assistant-plan" aria-label="已有应用版本变更">
        <div class="plan-heading">
          <h3>{{ state.edit.target.appName }}</h3>
          <span class="plan-state" :class="{ 'is-published': state.published }">
            {{ state.edit.newTable ? (state.published ? '新表已上线' : '新表草稿已保存') : (state.published ? '新版本已上线' : '新版本草稿已保存') }}
          </span>
        </div>
        <p class="plan-description">{{ state.edit.target.tableName }} · {{ state.edit.newTable ? state.edit.newVersion : state.edit.baseVersion + ' → ' + state.edit.newVersion }}</p>
        <details class="plan-table" open>
          <summary>字段变更 <span>{{ state.edit.changes.length }} 项</span></summary>
          <ul>
            <li v-for="field in state.edit.changes" :key="field.key">
              <strong>{{ field.label }}</strong>
              <span>{{ field.previousLabel ? '由「' + field.previousLabel + '」更名' : '新增 · ' + (fieldNames[field.type] || field.type) + (field.required ? ' · 必填' : '') }}</span>
            </li>
          </ul>
        </details>
        <div v-if="!state.published" class="plan-actions">
          <RouterLink to="/studio/models" @click="afterLink">查看数据表</RouterLink>
          <el-button type="primary" :loading="publishing" :disabled="Boolean(busy)" @click="publish">
            {{ state.edit.newTable ? '确认发布新表' : '确认发布新版本' }}
          </el-button>
        </div>
        <div v-else class="assistant-links" aria-label="已更新页面">
          <RouterLink v-for="link in visibleLinks" :key="link.path" :to="link.path" @click="afterLink">
            打开{{ link.label }} <el-icon aria-hidden="true"><ArrowRight /></el-icon>
          </RouterLink>
          <RouterLink to="/studio/models" @click="afterLink">查看数据表 <el-icon aria-hidden="true"><ArrowRight /></el-icon></RouterLink>
        </div>
      </section>
    </div>

    <form class="assistant-compose" @submit.prevent="submit">
      <div class="assistant-compose-row">
        <textarea
          ref="input"
          v-model="draft"
          rows="2"
          maxlength="1200"
          aria-label="输入操作需求"
          @keydown.enter.exact="handleEnter"
        />
        <el-button type="primary" native-type="submit" :loading="sending" :disabled="Boolean(busy) || !draft.trim()">发送</el-button>
      </div>
    </form>
  </section>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue';
import { requestMesAssistant } from '@/api/assistant';
import { useAppStore } from '@/stores/app';
import { useAuthStore } from '@/stores/auth';

const props = defineProps({ open: Boolean, runtime: Boolean, menu: String });
const emit = defineEmits(['close', 'navigate']);
const appStore = useAppStore();
const authStore = useAuthStore();
const transcript = ref(null);
const input = ref(null);
const draft = ref('');
const state = ref(null);
const pendingMessage = ref('');
const busy = ref('');
const sending = computed(() => Boolean(pendingMessage.value));
const publishing = ref(false);
const error = ref('');
const storageKey = (props.runtime ? 'mes-runtime-session:' : 'mes-release-session:') + authStore.currentUser?.id;
const sessionId = ref(sessionStorage.getItem(storageKey) || makeSessionId());
const turns = computed(() => pendingMessage.value
  ? [...(state.value?.turns || []), { role: 'user', message: pendingMessage.value }]
  : state.value?.turns || []);
const selectedTenant = computed(() => state.value?.tenants?.find(tenant => tenant.id === state.value?.tenantId));
const visibleLinks = computed(() => (state.value?.links || []).filter(link => appStore.findMenuNode('', link.path)));
const examples = computed(() => props.runtime ? [
  ...(props.menu ? ['查询当前页面的前 5 条记录', '统计当前页面有多少条记录'] : ['我可以查询哪些业务页面？'])
] : [
  ...(authStore.canAccessStudio ? ['搭建设备点检应用，记录设备、点检日期和结果', '给已有应用新增一张数据表'] : []),
  ...(authStore.canManageTenants ? ['创建一个名为一车间的租户，编码 workshop_1'] : [])
]);
const fieldNames = {
  String: '文本',
  Text: '多行文本',
  Integer: '整数',
  Decimal: '小数',
  Boolean: '开关',
  Date: '日期',
  DateTime: '日期时间',
  Selection: '单选',
  ManyToOne: '关联表'
};
let request = null;

function makeSessionId() {
  const bytes = crypto.getRandomValues(new Uint8Array(16));
  bytes[6] = (bytes[6] & 15) | 64;
  bytes[8] = (bytes[8] & 63) | 128;
  const hex = Array.from(bytes, byte => byte.toString(16).padStart(2, '0')).join('');
  const id = [hex.slice(0, 8), hex.slice(8, 12), hex.slice(12, 16), hex.slice(16, 20), hex.slice(20)].join('-');
  sessionStorage.setItem(storageKey, id);
  return id;
}

async function scrollToLatest() {
  await nextTick();
  if (transcript.value) transcript.value.scrollTop = transcript.value.scrollHeight;
}

watch(() => props.open, async (isOpen) => {
  if (!isOpen) return;
  if (!state.value && !busy.value) await perform('正在加载会话…');
  await scrollToLatest();
  input.value?.focus({ preventScroll: true });
}, { immediate: true });
onBeforeUnmount(() => request?.abort());

function useExample(question) {
  draft.value = question;
  input.value?.focus();
}

function handleEnter(event) {
  if (event.isComposing) return;
  event.preventDefault();
  submit();
}

function describeField(field) {
  if (field.type === 'Selection') return field.options.map(option => option.label).join(' / ');
  if (field.type === 'ManyToOne') {
    const table = state.value.draft.tables.find(table => table.key === field.relatedTable);
    return '关联 ' + (table?.name || field.relatedTable);
  }
  return fieldNames[field.type] || field.type;
}

function afterLink(event) {
  if (!event.ctrlKey && !event.metaKey && !event.shiftKey && !event.altKey) emit('navigate');
}

async function submit() {
  const question = draft.value.trim();
  if (!question || busy.value) return;
  draft.value = '';
  pendingMessage.value = question;
  scrollToLatest();
  const payload = props.runtime ? { message: question, menu: props.menu || null } : { message: question };
  const saved = await perform(props.runtime ? '正在处理业务需求…' : '正在整理需求…', '/messages', payload);
  pendingMessage.value = '';
  if (!saved && !draft.value) draft.value = question;
  scrollToLatest();
}

function selectTenant(id) {
  return perform('正在更新授权对象…', '/tenant', { tenantId: id || null });
}

async function publish() {
  if (!state.value?.revision || (!state.value.edit && !selectedTenant.value) || busy.value) return;
  publishing.value = true;
  try {
    const label = state.value.edit ? '正在检查并发布新版本…' : '正在检查、发布和授权…';
    if (await perform(label, '/publish', { revision: state.value.revision })) {
      await appStore.refreshMenusAndOverview();
    }
  } finally {
    publishing.value = false;
  }
}

async function confirmIam() {
  if (!state.value?.iamRevision || busy.value) return;
  if (await perform('正在执行已确认的管理操作…', '/iam/confirm', { revision: state.value.iamRevision })) {
    await appStore.loadTenants();
  }
}

async function confirmBusiness() {
  if (!state.value?.businessRevision || busy.value) return;
  if (await perform('正在执行确认的业务操作…', '/confirm', { revision: state.value.businessRevision })) {
    appStore.runtimeRecordChange = { path: state.value.operation.link.path };
  }
}

async function newSession() {
  sessionId.value = makeSessionId();
  state.value = null;
  draft.value = '';
  await perform('正在开启会话…');
  input.value?.focus();
}

async function perform(label, action, payload) {
  if (busy.value) return false;
  busy.value = label;
  error.value = '';
  request = new AbortController();
  try {
    const endpoint = (props.runtime ? '/runtime' : '') + (action || '');
    state.value = await requestMesAssistant(authStore.token, sessionId.value, endpoint, payload, request.signal);
    return true;
  } catch (e) {
    if (e.name !== 'AbortError') error.value = e.message;
    return false;
  } finally {
    busy.value = '';
    request = null;
    scrollToLatest();
  }
}
</script>

<style scoped>
.mes-assistant {
  position: absolute;
  inset: 12px 12px 12px auto;
  z-index: 4;
  width: min(468px, calc(100% - 24px));
  display: flex;
  flex-direction: column;
  border: 1px solid var(--sidebar-rule);
  border-radius: var(--radius-panel);
  background: var(--bg-overlay);
  box-shadow: 0 12px 38px var(--panel-shadow);
}
.assistant-header {
  display: grid;
  grid-template-columns: auto 1fr auto;
  align-items: center;
  gap: 10px;
  padding: 12px 14px;
  border-bottom: 1px solid var(--border-light);
  background: var(--bg-header);
  border-radius: var(--radius-panel) var(--radius-panel) 0 0;
}
.assistant-header-actions { display: flex; align-items: center; }
.assistant-icon {
  width: 36px;
  height: 36px;
  border-radius: var(--radius-control);
  background: var(--primary-soft);
  color: var(--primary);
  font-size: 19px;
}
h2, h3, p { margin: 0; }
h2 { font-size: 16px; }
.assistant-transcript {
  flex: 1;
  min-height: 0;
  overflow: auto;
  padding: 16px;
  scrollbar-width: thin;
  scrollbar-color: var(--sidebar-rule) transparent;
  font-size: 13px;
  line-height: 1.65;
  overflow-wrap: anywhere;
}
.assistant-welcome h3 { margin-bottom: 7px; font-size: 14px; }
.assistant-example {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 10px;
  width: 100%;
  margin: 7px 0;
  padding: 8px 11px;
  border: 1px solid var(--border-light);
  border-radius: var(--radius-control);
  background: var(--bg-card);
  color: var(--text-main);
  font: inherit;
  text-align: start;
  cursor: pointer;
}
.assistant-example:hover, .assistant-links a:hover { background: var(--primary-soft); color: var(--primary); }
.assistant-turn + .assistant-turn { margin-top: 14px; }
.assistant-turn--user { display: flex; justify-content: end; }
.assistant-question {
  max-width: 88%;
  padding: 7px 11px;
  border-radius: var(--radius-control);
  background: var(--primary-soft);
  color: var(--text-main);
  white-space: pre-wrap;
}
.assistant-message { white-space: pre-wrap; }
.assistant-state { margin-top: 8px; color: var(--text-muted); }
.assistant-error { color: var(--el-color-danger); }
.assistant-plan {
  margin-top: 14px;
  padding: 12px;
  border: 1px solid var(--sidebar-rule);
  border-radius: var(--radius-control);
  background: var(--bg-card);
}
.plan-heading, .plan-actions, .plan-table summary, .plan-table li {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 8px;
}
.plan-heading h3 { font-size: 15px; }
.plan-state { flex-shrink: 0; color: var(--primary); font-size: 12px; font-weight: 650; }
.plan-state.is-published { color: var(--el-color-success); }
.plan-description { margin: 6px 0; color: var(--text-muted); }
.plan-table { border-top: 1px solid var(--border-light); }
.plan-table summary { display: list-item; padding: 7px 0; font-weight: 650; cursor: pointer; }
.plan-table summary span { float: inline-end; }
.plan-table ul { margin: 0 0 8px; padding: 0; list-style: none; }
.plan-table li { padding: 3px 0; }
.assistant-details { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1.4fr); gap: 5px 10px; margin: 8px 0; }
.assistant-details dt { color: var(--text-muted); }
.assistant-details dd { margin: 0; overflow-wrap: anywhere; }
.plan-table summary span, .plan-table li span { color: var(--text-muted); font-size: 12px; font-weight: 400; }
.plan-tenant { display: grid; gap: 4px; margin: 10px 0; font-weight: 650; }
.plan-tenant select {
  min-width: 0;
  padding: 6px;
  border: 1px solid var(--control-border);
  border-radius: var(--radius-control);
  background: var(--bg-card);
  color: var(--text-main);
  font: inherit;
  font-weight: 400;
}
.plan-actions { align-items: center; flex-wrap: wrap; }
.plan-actions a { color: var(--primary); text-underline-offset: 3px; }
.assistant-links { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 9px; }
.assistant-links a {
  display: inline-flex;
  gap: 4px;
  align-items: center;
  min-height: 31px;
  padding: 3px 8px;
  border: 1px solid var(--sidebar-rule);
  border-radius: var(--radius-control);
  color: var(--primary);
  background: var(--bg-card);
  text-decoration: none;
}
.assistant-compose {
  border-top: 1px solid var(--border-light);
  padding: 11px 14px;
}
.assistant-compose-row { display: flex; align-items: end; gap: 8px; }
textarea {
  flex: 1;
  min-width: 0;
  resize: none;
  border: 1px solid var(--control-border);
  border-radius: var(--radius-control);
  background: var(--bg-card);
  color: var(--text-main);
  padding: 7px 9px;
  font: inherit;
  font-size: 14px;
}
textarea:focus-visible, .assistant-links a:focus-visible, .assistant-example:focus-visible,
.plan-table summary:focus-visible, .plan-tenant select:focus-visible, .plan-actions a:focus-visible {
  outline: 2px solid var(--primary);
  outline-offset: -2px;
}
@media (max-width: 780px) {
  .mes-assistant { inset: 0; width: auto; border-radius: 0; }
  .assistant-header { border-radius: 0; }
}
</style>
