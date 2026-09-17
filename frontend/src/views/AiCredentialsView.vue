<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref } from 'vue';
import { useRouter } from 'vue-router';
import {
  clearModelConnectionBinding,
  createModelConnection, deleteModelConnection, getModelConnections, setModelConnectionEnabled,
  getModelConnectionBindings, setModelConnectionBinding, updateModelConnection, verifyModelConnection,
  type ModelCapabilities, type ModelConnection, type ModelConnectionPayload, type ModelRole,
} from '@/api/aiCredentials';
import A12AssetIcon from '@/components/ui/A12AssetIcon.vue';
import { defaultModelCapabilities } from '@/utils/conversationWorkspaceConnection';

const router = useRouter();
const connections = ref<ModelConnection[]>([]);
const bindings = reactive<Record<ModelRole, number | null>>({ PLANNING: null, TEMPLATE_VISION: null, EMBEDDING: null });
const loading = ref(false);
const saving = ref(false);
const error = ref('');
const success = ref('');
const editingId = ref<number | null>(null);
const formOpen = ref(false);
const formPanel = ref<HTMLElement | null>(null);
const form = reactive<ModelConnectionPayload & { capabilities: ModelCapabilities }>({ name: '', protocol: 'OPENAI_COMPATIBLE', baseUrl: '', apiKey: '', modelId: '', capabilities: defaultModelCapabilities() });

const roles: Array<{ role: ModelRole; title: string }> = [
  { role: 'PLANNING', title: '课件规划模型' },
  { role: 'TEMPLATE_VISION', title: '模板视觉模型' },
  { role: 'EMBEDDING', title: '文本嵌入模型' },
];

function resetForm() {
  editingId.value = null;
  Object.assign(form, { name: '', protocol: 'OPENAI_COMPATIBLE', baseUrl: '', apiKey: '', modelId: '', capabilities: defaultModelCapabilities() });
}

function openAddConnection() {
  resetForm();
  error.value = '';
  success.value = '';
  formOpen.value = true;
  nextTick(() => formPanel.value?.scrollIntoView({ behavior: 'smooth', block: 'center' }));
}

function closeForm() {
  formOpen.value = false;
  resetForm();
}

async function load() {
  loading.value = true;
  error.value = '';
  try {
    const response = await getModelConnections();
    if (response.code !== 0) throw new Error(response.message);
    connections.value = response.data ?? [];
    try {
      const savedBindings = await getModelConnectionBindings();
      for (const role of roles) bindings[role.role] = savedBindings.find((item) => item.role === role.role)?.modelConnectionId ?? null;
    } catch {
      // Older running backends do not have the optional role-binding endpoint.
      // Keep the existing model connections visible and let the user select or
      // upgrade the backend before configuring the new role bindings.
      for (const role of roles) bindings[role.role] = null;
    }
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : '无法读取模型连接';
  } finally {
    loading.value = false;
  }
}

function edit(connection: ModelConnection) {
  editingId.value = connection.id;
  Object.assign(form, {
    name: connection.name,
    protocol: connection.protocol,
    baseUrl: connection.baseUrl,
    apiKey: '',
    modelId: connection.modelId,
    capabilities: { ...defaultModelCapabilities(), ...(connection.capabilities || {}) },
  });
  error.value = '';
  success.value = '';
  formOpen.value = true;
  nextTick(() => formPanel.value?.scrollIntoView({ behavior: 'smooth', block: 'center' }));
}

async function save() {
  saving.value = true;
  error.value = '';
  success.value = '';
  try {
    syncCapabilityDependencies();
    if (!form.capabilities?.supportsChat && !form.capabilities?.supportsEmbeddings) throw new Error('至少选择一种模型用途');
    const response = editingId.value === null
      ? await createModelConnection({ ...form, apiKey: form.apiKey ?? '' })
      : await updateModelConnection(editingId.value, form);
    if (response.code !== 0) throw new Error(response.message);
    success.value = editingId.value === null ? '模型连接已新增，请测试连接' : '模型连接已更新，请重新测试';
    formOpen.value = false;
    resetForm();
    await load();
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : '保存失败';
  } finally {
    saving.value = false;
  }
}

async function remove(connection: ModelConnection) {
  if (!window.confirm(`确定删除“${connection.name}”吗？`)) return;
  const response = await deleteModelConnection(connection.id);
  if (response.code !== 0) {
    error.value = response.message;
    return;
  }
  success.value = '模型连接已删除';
  await load();
}

function supportsRole(connection: ModelConnection, role: ModelRole) {
  if (!connection.enabled || connection.verificationStatus !== 'VERIFIED') return false;
  const capabilities = connection.capabilities || defaultModelCapabilities();
  const verification = connection.capabilityVerification;
  // A legacy connection may already be overall VERIFIED while its newer
  // capability fields are still DECLARED. It remains a valid planning model
  // when chat/tools/JSON are declared and not explicitly unsupported.
  if (role === 'PLANNING') return capabilities.supportsChat
    && capabilities.supportsTools
    && capabilities.supportsJSONMode
    && (!verification || [verification.supportsChat, verification.supportsTools, verification.supportsJSONMode].every((status) => status !== 'UNSUPPORTED'));
  if (role === 'TEMPLATE_VISION') return capabilities.supportsVision && verification?.supportsVision === 'VERIFIED';
  return capabilities.supportsEmbeddings && Boolean(capabilities.embeddingDimension) && verification?.supportsEmbeddings === 'VERIFIED';
}

function roleConnections(role: ModelRole) {
  return connections.value.filter((connection) => supportsRole(connection, role));
}

async function updateBinding(role: ModelRole, value: string) {
  error.value = '';
  success.value = '';
  try {
    if (!value) {
      await clearModelConnectionBinding(role);
      bindings[role] = null;
    } else {
      const binding = await setModelConnectionBinding(role, Number(value));
      bindings[role] = binding.modelConnectionId;
    }
    success.value = '模型分工已更新';
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : '模型分工更新失败';
    await load();
  }
}

function syncCapabilityDependencies() {
  const capabilities = form.capabilities;
  if (capabilities.supportsVision) capabilities.supportsChat = true;
  if (!capabilities.supportsChat) {
    capabilities.supportsTools = false;
    capabilities.supportsJSONMode = false;
    capabilities.supportsVision = false;
    capabilities.supportsStreaming = false;
  }
}

function capabilityLabels(connection: ModelConnection) {
  const capabilities = connection.capabilities || defaultModelCapabilities();
  const labels: string[] = [];
  if (capabilities.supportsChat) labels.push('课件规划');
  if (capabilities.supportsVision) labels.push('模板视觉');
  if (capabilities.supportsEmbeddings) labels.push(capabilities.embeddingDimension ? `文本嵌入 ${capabilities.embeddingDimension}维` : '文本嵌入');
  return labels;
}

async function toggle(connection: ModelConnection) {
  const response = await setModelConnectionEnabled(connection.id, !connection.enabled);
  if (response.code !== 0) {
    error.value = response.message;
    return;
  }
  await load();
}

async function verify(connection: ModelConnection) {
  const response = await verifyModelConnection(connection.id);
  if (response.code !== 0) {
    error.value = response.message;
    return;
  }
  success.value = response.data?.status === 'VERIFIED'
    ? `连接验证成功（${response.data.baseUrlHost} / ${response.data.modelId}）`
    : `连接验证失败：${response.data?.safeCode}`;
  await load();
}

function statusLabel(connection: ModelConnection) {
  if (connection.verificationStatus === 'VERIFIED' && connection.enabled) return '可用';
  if (connection.verificationStatus === 'VERIFIED') return '已验证，未启用';
  if (connection.verificationStatus === 'INVALID') return '验证失败';
  return '待验证';
}

function statusClass(connection: ModelConnection) {
  if (connection.verificationStatus === 'VERIFIED' && connection.enabled) return 'is-ready';
  if (connection.verificationStatus === 'INVALID') return 'is-invalid';
  return 'is-muted';
}

function returnToApp() {
  router.push({ name: 'lessonforge-missions' });
}

onMounted(load);
</script>

<template>
  <main class="model-settings-screen">
    <aside class="model-settings-sidebar" aria-label="模型设置导航">
      <button class="settings-back" type="button" @click="returnToApp">
        <span class="settings-back__arrow" aria-hidden="true">‹</span>
        <span>返回应用</span>
      </button>
      <nav class="settings-nav" aria-label="模型设置">
        <button class="settings-nav__item is-active" type="button" aria-current="page">
          <span class="settings-nav__icon"><A12AssetIcon name="atom" :size="21" /></span>
          <span>模型</span>
        </button>
      </nav>
    </aside>

    <section class="model-settings-content" aria-labelledby="model-settings-title">
      <div class="model-settings-inner">
        <header class="settings-page-heading">
          <h1 id="model-settings-title">模型配置</h1>
        </header>

        <div v-if="error" class="settings-notice settings-notice--error" role="alert">{{ error }}</div>
        <div v-if="success" class="settings-notice settings-notice--success" role="status">{{ success }}</div>

        <section class="settings-section" aria-labelledby="model-selection-title">
          <div class="settings-section-heading">
            <h2 id="model-selection-title">模型分工</h2>
          </div>
          <div v-for="item in roles" :key="item.role" class="model-selection-card">
            <div>
              <strong>{{ item.title }}</strong>
            </div>
            <select :value="bindings[item.role] || ''" :aria-label="item.title" :disabled="loading" @change="updateBinding(item.role, ($event.target as HTMLSelectElement).value)">
              <option value="">尚未指定</option>
              <option v-for="connection in roleConnections(item.role)" :key="connection.id" :value="connection.id">
                {{ connection.name }} · {{ connection.modelId }}
              </option>
            </select>
          </div>
        </section>

        <section class="settings-section" aria-labelledby="connections-title">
          <div class="settings-section-heading">
            <h2 id="connections-title">模型连接</h2>
            <button class="primary-button" type="button" @click="openAddConnection">＋ 添加连接</button>
          </div>

          <div class="connections-card">
            <div v-if="loading" class="connections-empty">正在读取模型连接…</div>
            <div v-else-if="connections.length === 0" class="connections-empty">
              <strong>还没有模型连接</strong>
              <span>添加一个 OpenAI Compatible 连接后即可选择模型。</span>
              <button class="secondary-button" type="button" @click="openAddConnection">添加连接</button>
            </div>
            <article v-for="connection in connections" v-else :key="connection.id" class="connection-row">
              <div class="connection-row__identity">
                <div class="connection-row__title">
                  <strong>{{ connection.name }}</strong>
                  <span class="connection-status" :class="statusClass(connection)"><span class="status-dot" aria-hidden="true"></span>{{ statusLabel(connection) }}</span>
                </div>
                <span>{{ connection.modelId }}</span>
                <div class="connection-capabilities"><span v-for="label in capabilityLabels(connection)" :key="label">{{ label }}</span></div>
                <small>{{ connection.baseUrl }} · {{ connection.keyHint }}</small>
              </div>
              <div class="connection-row__actions">
                <button type="button" @click="verify(connection)">验证</button>
                <button type="button" @click="toggle(connection)">{{ connection.enabled ? '停用' : '启用' }}</button>
                <button type="button" @click="edit(connection)">编辑</button>
                <button class="is-danger" type="button" @click="remove(connection)">删除</button>
              </div>
            </article>
          </div>

          <article v-if="formOpen" ref="formPanel" class="connection-editor">
            <div class="editor-heading">
              <div>
                <p class="settings-kicker">{{ editingId === null ? '新增连接' : '编辑连接' }}</p>
                <h3>{{ editingId === null ? '添加模型连接' : '编辑模型连接' }}</h3>
              </div>
              <button class="close-editor" type="button" aria-label="关闭连接配置" @click="closeForm">×</button>
            </div>
            <form @submit.prevent="save">
              <div class="editor-grid">
                <label>连接名称<input v-model="form.name" required maxlength="120" placeholder="例如：学校 DeepSeek" /></label>
                <label>协议<select v-model="form.protocol"><option value="OPENAI_COMPATIBLE">OpenAI Compatible</option></select></label>
                <label class="editor-grid__wide">API Base URL<input v-model="form.baseUrl" required maxlength="2048" placeholder="https://api.example.com/v1" /></label>
                <label>API Key<input v-model="form.apiKey" :required="editingId === null" type="password" autocomplete="new-password" :placeholder="editingId === null ? '输入 API Key' : '留空以保留原 Key'" /></label>
                <label>Model ID<input v-model="form.modelId" required maxlength="128" placeholder="例如：deepseek-chat" /></label>
                <fieldset class="editor-grid__wide capability-fieldset">
                  <legend>模型用途</legend>
                  <label><input v-model="form.capabilities.supportsChat" type="checkbox" @change="syncCapabilityDependencies" /> 对话与课件规划</label>
                  <label><input v-model="form.capabilities.supportsVision" type="checkbox" @change="syncCapabilityDependencies" /> 模板视觉理解</label>
                  <label><input v-model="form.capabilities.supportsEmbeddings" type="checkbox" @change="syncCapabilityDependencies" /> 文本嵌入与语义检索</label>
                  <div v-if="form.capabilities.supportsChat" class="capability-advanced">
                    <label><input v-model="form.capabilities.supportsTools" type="checkbox" /> 工具调用</label>
                    <label><input v-model="form.capabilities.supportsJSONMode" type="checkbox" /> 结构化输出</label>
                    <label><input v-model="form.capabilities.supportsStreaming" type="checkbox" /> 流式输出</label>
                  </div>
                </fieldset>
              </div>
              <div class="editor-actions">
                <button class="secondary-button" type="button" @click="closeForm">取消</button>
                <button class="primary-button" :disabled="saving">{{ saving ? '保存中…' : '保存连接' }}</button>
              </div>
            </form>
          </article>
        </section>
      </div>
    </section>
  </main>
</template>

<style scoped>
:global(html), :global(body), :global(#app) { width: 100%; height: 100%; min-width: 320px; min-height: 100%; }
:global(body) { background: #f4f7fb; }

.model-settings-screen { display: flex; width: 100%; height: 100vh; min-height: 100vh; overflow: hidden; background: #f4f7fb; color: #17213a; font-family: Inter, "PingFang SC", "Microsoft YaHei", system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif; }
.model-settings-sidebar { width: 236px; flex: 0 0 236px; padding: 28px 15px; overflow-y: auto; border-right: 1px solid #e1e6f0; background: #fff; }
.settings-back { display: flex; align-items: center; gap: 7px; width: 100%; margin: 0 0 24px; padding: 9px 12px; border: 0; border-radius: 7px; background: transparent; color: #53617d; font-size: 15px; text-align: left; cursor: pointer; }
.settings-back:hover { background: #f1efff; color: #4d3cdb; }
.settings-back__arrow { font-size: 26px; font-weight: 300; line-height: 18px; }
.settings-nav { display: grid; gap: 6px; }
.settings-nav__item { display: flex; align-items: center; gap: 12px; width: 100%; min-height: 44px; padding: 0 14px; border: 0; border-radius: 8px; background: #f0edff; color: #4d3cdb; font-size: 15px; font-weight: 600; text-align: left; }
.settings-nav__icon { display: inline-grid; width: 21px; height: 21px; flex: 0 0 21px; place-items: center; color: #6755ed; }

.model-settings-content { min-width: 0; flex: 1; overflow-y: auto; background: #f4f7fb; }
.model-settings-inner { width: 100%; margin: 0 auto; padding: 46px clamp(24px, 4vw, 58px) 64px; }
.settings-page-heading { margin: 0 0 30px; }
.settings-page-heading h1 { margin: 0; color: #17213a; font-size: clamp(30px, 3vw, 42px); line-height: 1.15; letter-spacing: -.025em; }
.settings-notice { margin: -14px 0 24px; padding: 12px 16px; border: 1px solid; border-radius: 8px; font-size: 14px; }
.settings-notice--error { border-color: #efb4b4; background: #fff7f7; color: #b42318; }
.settings-notice--success { border-color: #a7dfc0; background: #f2fbf5; color: #18794e; }
.settings-section { margin: 0 0 36px; }
.settings-section-heading { display: flex; align-items: center; justify-content: space-between; gap: 18px; margin: 0 0 14px; }
.settings-section-heading h2 { margin: 0; color: #263454; font-size: 21px; }
.model-selection-card, .connections-card, .connection-editor { border: 1px solid #dfe5ef; border-radius: 10px; background: #fff; box-shadow: 0 7px 20px rgb(30 45 90 / 4%); }
.model-selection-card { display: flex; align-items: center; justify-content: space-between; gap: 22px; margin-bottom: 10px; padding: 22px 24px; }
.model-selection-card strong { display: block; color: #263454; font-size: 16px; }
.model-selection-card select { min-width: min(420px, 48%); height: 42px; padding: 0 12px; border: 1px solid #cfd7e6; border-radius: 7px; outline: none; background: #fff; color: #263454; font-size: 14px; }
.model-selection-card select:focus { border-color: #6755ed; box-shadow: 0 0 0 2px rgb(103 85 237 / 13%); }
.model-selection-card select:disabled { cursor: not-allowed; color: #9aa4b6; background: #f8f9fc; }
.selection-hint { margin: 9px 0 0; color: #18794e; font-size: 13px; }
.primary-button, .secondary-button { min-height: 38px; padding: 0 15px; border-radius: 7px; font-size: 14px; cursor: pointer; }
.primary-button { border: 1px solid #6755ed; background: #6755ed; color: #fff; }
.primary-button:hover { background: #5744dc; }
.primary-button:disabled { cursor: not-allowed; opacity: .55; }
.secondary-button { border: 1px solid #cfd7e6; background: #fff; color: #46536e; }
.secondary-button:hover { border-color: #9f96ee; color: #4d3cdb; }
.connections-card { overflow: hidden; }
.connections-empty { display: flex; min-height: 145px; flex-direction: column; align-items: center; justify-content: center; gap: 8px; padding: 24px; color: #7a849b; text-align: center; }
.connections-empty strong { color: #263454; font-size: 16px; }
.connections-empty span { font-size: 13px; }
.connections-empty .secondary-button { margin-top: 7px; }
.connection-row { display: flex; align-items: center; justify-content: space-between; gap: 20px; padding: 20px 23px; border-bottom: 1px solid #edf0f6; }
.connection-row:last-child { border-bottom: 0; }
.connection-row__identity { min-width: 0; display: grid; gap: 6px; }
.connection-row__title { display: flex; align-items: center; flex-wrap: wrap; gap: 11px; }
.connection-row__title strong { color: #263454; font-size: 16px; }
.connection-row__identity > span { color: #53617d; font-size: 14px; }
.connection-capabilities { display: flex; flex-wrap: wrap; gap: 5px; }
.connection-capabilities span { padding: 2px 8px; border-radius: 999px; background: #eaf3ff; color: #2563eb; font-size: 11px; }
.connection-row__identity small { overflow: hidden; color: #8b98aa; font-size: 12px; text-overflow: ellipsis; white-space: nowrap; }
.connection-status { display: inline-flex; align-items: center; gap: 6px; color: #7a849b; font-size: 13px; }
.connection-status.is-ready { color: #198754; }
.connection-status.is-invalid { color: #b42318; }
.status-dot { width: 8px; height: 8px; border-radius: 50%; background: #9aa4b6; }
.connection-status.is-ready .status-dot { background: #38a169; }
.connection-status.is-invalid .status-dot { background: #d35c5c; }
.connection-row__actions { display: flex; flex: 0 0 auto; flex-wrap: wrap; justify-content: flex-end; gap: 8px; }
.connection-row__actions button { min-height: 34px; padding: 0 11px; border: 1px solid #cfd7e6; border-radius: 7px; background: #fff; color: #53617d; cursor: pointer; font-size: 13px; }
.connection-row__actions button:hover { border-color: #9f96ee; color: #4d3cdb; }
.connection-row__actions button.is-danger { color: #b42318; }
.connection-row__actions button.is-danger:hover { border-color: #efb4b4; background: #fff7f7; }
.connection-editor { margin-top: 16px; padding: 24px; }
.editor-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 18px; margin-bottom: 20px; }
.editor-heading h3 { margin: 0; color: #263454; font-size: 20px; }
.close-editor { border: 0; background: transparent; color: #7a849b; cursor: pointer; font-size: 25px; line-height: 1; }
.close-editor:hover { color: #263454; }
.editor-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; }
.editor-grid label { display: grid; gap: 7px; color: #53617d; font-size: 13px; }
.editor-grid__wide { grid-column: 1 / -1; }
.editor-grid input, .editor-grid select { width: 100%; min-height: 40px; padding: 0 11px; border: 1px solid #cfd7e6; border-radius: 7px; outline: none; background: #fff; color: #263454; font-size: 14px; }
.editor-grid input:focus, .editor-grid select:focus { border-color: #6755ed; box-shadow: 0 0 0 2px rgb(103 85 237 / 13%); }
.editor-grid input::placeholder { color: #9aa4b6; }
.capability-fieldset { display: flex; flex-wrap: wrap; gap: 10px 22px; padding: 14px 16px; border: 1px solid #cfd7e6; border-radius: 8px; }
.capability-fieldset legend { padding: 0 6px; color: #53617d; font-size: 13px; }
.capability-fieldset label { display: flex; grid-template-columns: none; align-items: center; gap: 7px; }
.capability-fieldset input { width: 16px; min-height: 16px; margin: 0; box-shadow: none; }
.capability-advanced { display: flex; width: 100%; flex-wrap: wrap; gap: 10px 22px; padding-top: 10px; border-top: 1px solid #e5eaf2; }
.editor-actions { display: flex; justify-content: flex-end; gap: 9px; margin-top: 22px; }

@media (max-width: 760px) {
  .model-settings-sidebar { width: 68px; flex-basis: 68px; padding: 22px 8px; }
  .settings-back { justify-content: center; padding: 8px 0; }
  .settings-back span:last-child, .settings-nav__item > span:last-child { display: none; }
  .settings-nav__item { justify-content: center; padding: 0; }
  .model-settings-inner { padding: 32px 18px 48px; }
  .model-selection-card { align-items: stretch; flex-direction: column; }
  .model-selection-card select { min-width: 0; width: 100%; }
  .connection-row { align-items: flex-start; flex-direction: column; }
  .connection-row__actions { justify-content: flex-start; }
  .editor-grid { grid-template-columns: 1fr; }
  .editor-grid__wide { grid-column: auto; }
}
</style>
