<template>
  <el-drawer
    :model-value="modelValue"
    title="Model Connection"
    size="min(560px, 100%)"
    direction="rtl"
    @update:model-value="$emit('update:modelValue', $event)"
    @open="loadConnections"
  >
    <section class="connection-drawer">
      <div class="connection-drawer__notice" role="status">
        <strong>会话级模型选择</strong>
        <span>连接只显示脱敏摘要；需要时可在这里管理或测试。</span>
      </div>

      <section class="connection-drawer__section">
        <div class="connection-drawer__section-head">
          <div>
            <h3>当前连接</h3>
            <p>选择后仅绑定当前前端会话，不改变服务器默认 Provider。</p>
          </div>
          <el-button text type="primary" @click="toggleForm">{{ editingId ? '取消编辑' : showForm ? '收起添加' : '添加连接' }}</el-button>
        </div>

        <el-select
          :model-value="selectedConnectionId"
          class="connection-drawer__select"
          clearable
          :loading="loading"
          placeholder="请选择已保存的连接"
          @change="selectConnection"
        >
          <el-option v-for="connection in selectableConnections" :key="connection.id" :label="connectionLabel(connection)" :value="connection.id" />
        </el-select>
        <p v-if="!selectableConnections.length && !loading" class="connection-drawer__empty">还没有已启用且已验证的连接，请添加或测试一个 OpenAI Compatible 连接。</p>
      </section>

      <section v-if="connections.length" class="connection-drawer__cards">
        <article v-for="connection in connections" :key="connection.id" class="connection-card">
          <div>
            <strong>{{ connection.name }}</strong>
            <span>{{ connection.modelId }} · {{ connection.baseUrl }}</span>
            <small>{{ verificationLabel(connection) }} · Key {{ connection.keyHint || '未配置' }}</small>
          </div>
          <div class="connection-card__actions">
            <el-button text size="small" :disabled="Boolean(operationLock)" @click="startEdit(connection)">编辑</el-button>
            <el-button text size="small" type="primary" :loading="operationLock?.operation === 'verify' && operationLock.connectionId === connection.id" :disabled="Boolean(operationLock)" @click="testConnection(connection)">测试连接</el-button>
            <el-button text size="small" type="danger" :loading="operationLock?.operation === 'delete' && operationLock.connectionId === connection.id" :disabled="Boolean(operationLock)" @click="removeConnection(connection)">删除</el-button>
            <el-switch
              :model-value="connection.enabled"
              :loading="operationLock?.operation === 'toggle' && operationLock.connectionId === connection.id"
              :disabled="Boolean(operationLock)"
              active-text="启用"
              inactive-text="停用"
              @change="toggleConnection(connection, Boolean($event))"
            />
          </div>
        </article>
      </section>

      <section v-if="lastVerification" class="verification-result" role="status">
        <strong>最近一次测试：{{ lastVerification.status }}</strong>
        <span>诊断码：{{ lastVerification.safeCode || '未提供' }} · HTTP：{{ lastVerification.httpStatus || '未提供' }}</span>
        <span>服务主机：{{ lastVerification.baseUrlHost || '未提供' }} · Model ID：{{ lastVerification.modelId || '未提供' }}</span>
        <span>时间：{{ lastVerification.verifiedAt || '未提供' }}</span>
      </section>

      <form v-if="showForm" class="connection-form" @submit.prevent="saveConnection">
        <h3>{{ editingId ? '编辑 OpenAI Compatible 连接' : '新增 OpenAI Compatible 连接' }}</h3>
        <el-form-item label="连接名称" required>
          <el-input v-model="form.name" autocomplete="off" placeholder="例如：学校模型服务" />
        </el-form-item>
        <el-form-item label="API Base URL" required>
          <el-input v-model="form.baseUrl" autocomplete="url" placeholder="https://api.example.com/v1" />
        </el-form-item>
        <el-form-item label="Model ID" required>
          <el-input v-model="form.modelId" autocomplete="off" placeholder="模型 ID" />
        </el-form-item>
        <el-form-item label="API Key" :required="!editingId">
          <el-input v-model="form.apiKey" type="password" show-password autocomplete="new-password" :placeholder="editingId ? '留空保持已有 Key；输入新 Key 才会替换' : '仅提交到现有连接 API，不在页面回显'" />
        </el-form-item>
        <p class="connection-form__boundary">{{ editingId ? '编辑时不会回显已有 Key；留空提交将保留服务端密文。' : '新增连接必须填写 Key。' }} 保存不会自动执行连接测试。</p>
        <div class="connection-form__actions">
          <el-button @click="resetForm">取消</el-button>
          <el-button type="primary" :loading="saving" :disabled="Boolean(operationLock)" native-type="submit">保存连接</el-button>
        </div>
      </form>

      <StatePanel v-if="error" type="error" title="连接读取失败" :description="error">
        <template #action><el-button @click="loadConnections">重新加载</el-button></template>
      </StatePanel>
    </section>
  </el-drawer>
</template>

<script setup lang="ts">
import {
  createModelConnection,
  deleteModelConnection,
  getModelConnections,
  setModelConnectionEnabled,
  updateModelConnection,
  verifyModelConnection,
  type ConnectionVerification,
  type ModelConnection,
} from '@/api/aiCredentials';
import StatePanel from '@/components/StatePanel.vue';
import {
  acquireConnectionOperation,
  buildModelConnectionPayload,
  connectionVerificationOutcome,
  findSelectableConnection,
  isSelectableConnection,
  modelConnectionVerificationLabel,
  releaseConnectionOperation,
  safeConnectionErrorMessage,
  shouldSyncConnectionSelection,
  type ConnectionListState,
  type ConnectionOperation,
  type ConnectionOperationLock,
} from '@/utils/conversationWorkspaceConnection';
import { ElMessage, ElMessageBox } from 'element-plus';
import { computed, reactive, ref, watch } from 'vue';

const props = defineProps<{ modelValue: boolean; selectedConnection?: ModelConnection | null; selectedConnectionId?: number | null }>();
const emit = defineEmits<{
  'update:modelValue': [value: boolean];
  'update:connection': [value: ModelConnection | null];
  'connections-loaded': [value: ModelConnection[]];
}>();

const connections = ref<ModelConnection[]>([]);
const loading = ref(false);
const saving = ref(false);
const connectionListState = ref<ConnectionListState>('idle');
const operationLock = ref<ConnectionOperationLock | null>(null);
const error = ref('');
const showForm = ref(false);
const editingId = ref<number | null>(null);
const lastVerification = ref<ConnectionVerification | null>(null);
const form = reactive({ name: '', baseUrl: '', modelId: '', apiKey: '' });
const selectableConnections = computed(() => connections.value.filter(isSelectableConnection));

async function loadConnections() {
  loading.value = true;
  connectionListState.value = 'loading';
  error.value = '';
  try {
    const response = await getModelConnections();
    if (response.code !== 0) throw new Error(response.message);
    connections.value = response.data || [];
    connectionListState.value = 'loaded';
    emit('connections-loaded', connections.value);
    syncSelection();
  } catch (reason) {
    connectionListState.value = 'error';
    error.value = safeConnectionErrorMessage(reason, '暂时无法读取已保存连接。');
  } finally {
    loading.value = false;
  }
}

function selectConnection(id: number | undefined) {
  emit('update:connection', findSelectableConnection(connections.value, id));
}

function syncSelection() {
  if (!shouldSyncConnectionSelection(connectionListState.value)) return;
  emit('update:connection', findSelectableConnection(connections.value, props.selectedConnectionId));
}

watch(() => props.selectedConnectionId, syncSelection);

function toggleForm() {
  if (editingId.value !== null) {
    resetForm();
    return;
  }
  showForm.value = !showForm.value;
}

function startEdit(connection: ModelConnection) {
  editingId.value = connection.id;
  form.name = connection.name;
  form.baseUrl = connection.baseUrl;
  form.modelId = connection.modelId;
  form.apiKey = '';
  showForm.value = true;
  lastVerification.value = null;
}

async function saveConnection() {
  const mode = editingId.value === null ? 'create' as const : 'edit' as const;
  const payload = buildModelConnectionPayload(form, mode);
  if (!payload) {
    ElMessage.warning(editingId.value === null ? '请填写连接名称、Base URL、Model ID 和 API Key。' : '请填写连接名称、Base URL 和 Model ID；API Key 可留空以保留已有密文。');
    return;
  }
  if (!beginOperation('save', null)) return;
  saving.value = true;
  try {
    const editingConnectionId = editingId.value;
    const response = editingConnectionId === null
      ? await createModelConnection({ ...payload, apiKey: payload.apiKey as string })
      : await updateModelConnection(editingConnectionId, payload);
    if (response.code !== 0) throw new Error(response.message);
    if (!response.data) throw new Error('连接保存未返回安全摘要。');
    const connection = response.data;
    connections.value = [connection, ...connections.value.filter((item) => item.id !== connection.id)];
    emit('connections-loaded', connections.value);
    emit('update:connection', findSelectableConnection(connections.value, connection.id));
    resetForm();
    ElMessage.success(`${editingConnectionId === null ? '连接已创建' : '连接已更新'}；真实验证仍待 Provider 调用。`);
  } catch (reason) {
    ElMessage.error(safeConnectionErrorMessage(reason, '连接保存失败，请稍后重试。'));
  } finally {
    saving.value = false;
    endOperation('save', null);
  }
}

async function toggleConnection(connection: ModelConnection, enabled: boolean) {
  if (!beginOperation('toggle', connection.id)) return;
  try {
    const response = await setModelConnectionEnabled(connection.id, enabled);
    if (response.code !== 0) throw new Error(response.message);
    const updated = response.data;
    connections.value = connections.value.map((item) => item.id === updated.id ? updated : item);
    emit('connections-loaded', connections.value);
    emit('update:connection', findSelectableConnection(connections.value, props.selectedConnectionId));
  } catch (reason) {
    ElMessage.error(safeConnectionErrorMessage(reason, '连接状态更新失败。'));
  } finally {
    endOperation('toggle', connection.id);
  }
}

async function removeConnection(connection: ModelConnection) {
  if (!beginOperation('delete', connection.id)) return;
  try {
    await ElMessageBox.confirm(`确定删除连接“${connection.name}”吗？删除后当前会话不会继续使用它。`, '删除 Model Connection', { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' });
  } catch {
    endOperation('delete', connection.id);
    return;
  }
  try {
    const response = await deleteModelConnection(connection.id);
    if (response.code !== 0) throw new Error(response.message);
    connections.value = connections.value.filter((item) => item.id !== connection.id);
    emit('connections-loaded', connections.value);
    emit('update:connection', findSelectableConnection(connections.value, props.selectedConnectionId));
    if (editingId.value === connection.id) resetForm();
    ElMessage.success('连接已删除。');
  } catch (reason) {
    ElMessage.error(safeConnectionErrorMessage(reason, '连接删除失败，请稍后重试。'));
  } finally {
    endOperation('delete', connection.id);
  }
}

async function testConnection(connection: ModelConnection) {
  if (!beginOperation('verify', connection.id)) return;
  lastVerification.value = null;
  try {
    const response = await verifyModelConnection(connection.id);
    if (response.code !== 0 || !response.data) throw new Error(response.message || '测试连接未返回安全诊断。');
    lastVerification.value = response.data;
    await loadConnections();
    const outcome = connectionVerificationOutcome(response.data);
    if (outcome.kind === 'verified') ElMessage.success(outcome.message);
    else ElMessage.warning(outcome.message);
  } catch (reason) {
    ElMessage.error(safeConnectionErrorMessage(reason, '测试连接失败；未将其标记为成功。'));
  } finally {
    endOperation('verify', connection.id);
  }
}

function beginOperation(operation: ConnectionOperation, connectionId: number | null) {
  const next = acquireConnectionOperation(operationLock.value, operation, connectionId);
  if (!next) return false;
  operationLock.value = next;
  return true;
}

function endOperation(operation: ConnectionOperation, connectionId: number | null) {
  operationLock.value = releaseConnectionOperation(operationLock.value, operation, connectionId);
}

function resetForm() {
  editingId.value = null;
  form.name = '';
  form.baseUrl = '';
  form.modelId = '';
  form.apiKey = '';
  showForm.value = false;
}

function connectionLabel(connection: ModelConnection) {
  return `${connection.name} · ${connection.modelId}${connection.enabled ? '' : '（已停用）'}`;
}

function verificationLabel(connection: ModelConnection) {
  const label = modelConnectionVerificationLabel(connection.verificationStatus);
  if (label === 'LIVE_VERIFICATION_PENDING') return '待验证';
  return label;
}

</script>

<style scoped>
.connection-drawer { display: grid; gap: 18px; }
.connection-drawer__notice { display: grid; gap: 5px; padding: 13px 14px; border: 1px solid #d7d0ff; border-radius: 10px; background: #f7f5ff; color: var(--ui-muted); font-size: 12px; line-height: 1.55; }
.connection-drawer__notice strong { color: var(--ui-primary); font-size: 14px; }
.connection-drawer__section, .connection-form { display: grid; gap: 12px; }
.connection-drawer__section-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; }
.connection-drawer h3, .connection-form h3 { margin: 0; color: var(--ui-text); font-size: 16px; }
.connection-drawer p { margin: 0; color: var(--ui-muted); font-size: 12px; line-height: 1.5; }
.connection-drawer__select { width: 100%; }
.connection-drawer__empty { padding: 12px; border: 1px dashed var(--ui-border-strong); border-radius: 8px; text-align: center; }
.connection-drawer__cards { display: grid; gap: 9px; }
.connection-card { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; padding: 13px; border: 1px solid var(--ui-border); border-radius: 10px; background: #fff; }
.connection-card > div:first-child { display: grid; min-width: 0; gap: 5px; }
.connection-card strong { color: var(--ui-text); font-size: 13px; overflow-wrap: anywhere; }
.connection-card span, .connection-card small { color: var(--ui-muted); font-size: 11px; overflow-wrap: anywhere; }
.connection-card small { color: var(--ui-primary); }
.connection-card__actions { display: flex; align-items: center; flex-wrap: wrap; justify-content: flex-end; gap: 3px; }
.verification-result { display: grid; gap: 4px; padding: 11px 12px; border: 1px solid var(--ui-border); border-radius: 8px; background: #f8fbff; color: var(--ui-muted); font-size: 12px; line-height: 1.45; }
.verification-result strong { color: var(--ui-text); }
.connection-form { padding-top: 16px; border-top: 1px solid var(--ui-border); }
.connection-form :deep(.el-form-item) { margin: 0; }
.connection-form__boundary { padding: 10px 12px; border-radius: 8px; background: #fff8ec; color: #9b6818 !important; }
.connection-form__actions { display: flex; justify-content: flex-end; gap: 9px; }
</style>
