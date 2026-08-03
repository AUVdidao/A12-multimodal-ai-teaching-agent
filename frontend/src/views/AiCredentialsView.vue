<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue';
import { getAiCredentials, saveAiCredentials, type AiCredentialView } from '@/api/aiCredentials';

type CredentialForm = {
  slot: number;
  key: string;
  meta: AiCredentialView | null;
};

const slots = reactive<CredentialForm[]>([
  { slot: 1, key: '', meta: null },
  { slot: 2, key: '', meta: null },
  { slot: 3, key: '', meta: null },
]);
const activeSlot = ref(1);
const loading = ref(false);
const saving = ref(false);
const error = ref('');
const success = ref('');

function applyView(items: AiCredentialView[] = []) {
  for (const entry of slots) {
    entry.meta = items.find((item) => item.slot === entry.slot) ?? null;
    if (entry.meta?.active) activeSlot.value = entry.slot;
  }
}

async function load() {
  loading.value = true;
  error.value = '';
  try {
    const result = await getAiCredentials();
    if (result.code !== 0) throw new Error(result.message);
    applyView(result.data?.credentials ?? []);
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '无法读取密钥状态';
  } finally {
    loading.value = false;
  }
}

async function submit() {
  saving.value = true;
  error.value = '';
  success.value = '';
  try {
    const result = await saveAiCredentials({
      keys: slots.map((entry) => entry.key),
      activeSlot: activeSlot.value,
    });
    if (result.code !== 0) throw new Error(result.message);
    applyView(result.data?.credentials ?? []);
    slots.forEach((entry) => {
      entry.key = '';
    });
    success.value = '已保存并启用';
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '保存失败，请稍后重试';
  } finally {
    saving.value = false;
  }
}

function clearForm() {
  slots.forEach((entry) => {
    entry.key = '';
  });
  error.value = '';
  success.value = '';
}

onMounted(load);
</script>

<template>
  <section class="credentials-page">
    <header class="page-heading">
      <div>
        <p class="eyebrow">系统设置</p>
        <h1>API 密钥</h1>
        <p class="page-description">配置 AI 服务使用的密钥。密钥只在服务端使用，页面不会展示完整内容。</p>
      </div>
      <button class="secondary-button" type="button" :disabled="loading" @click="load">刷新状态</button>
    </header>

    <div v-if="error" class="notice notice--error" role="alert">{{ error }}</div>
    <div v-if="success" class="notice notice--success" role="status">{{ success }}</div>

    <div class="credentials-layout">
      <form class="credentials-card" @submit.prevent="submit">
        <div class="card-heading">
          <div>
            <h2>Kimi AI 密钥</h2>
            <p>可以保存三个密钥，并手动选择当前启用的密钥。</p>
          </div>
          <span class="provider-badge">{{ slots.some((entry) => entry.meta?.active) ? '已启用' : '待配置' }}</span>
        </div>

        <div class="credential-list" :aria-busy="loading || saving">
          <label v-for="entry in slots" :key="entry.slot" class="credential-row">
            <span class="credential-select">
              <input v-model="activeSlot" type="radio" name="active-key" :value="entry.slot" />
              <strong>密钥 {{ entry.slot }}</strong>
              <span v-if="entry.meta?.active" class="active-label">当前启用</span>
            </span>
            <span class="credential-input-wrap">
              <input
                v-model="entry.key"
                class="credential-input"
                type="password"
                autocomplete="new-password"
                :placeholder="entry.meta?.configured ? `已配置 ${entry.meta.maskedKey ?? '••••'}` : '输入 API Key'"
              />
              <small v-if="entry.meta?.updatedAt">上次更新：{{ entry.meta.updatedAt }}</small>
            </span>
          </label>
        </div>

        <div class="security-note">
          <strong>安全提示</strong>
          <span>服务端只保存加密后的密钥及末四位提示，浏览器不会回显完整密钥。</span>
        </div>

        <div class="form-actions">
          <button class="secondary-button" type="button" :disabled="saving" @click="clearForm">清空输入</button>
          <button class="primary-button" type="submit" :disabled="loading || saving">
            {{ saving ? '保存中…' : '保存并启用' }}
          </button>
        </div>
      </form>

      <aside class="status-card">
        <h2>使用状态</h2>
        <dl>
          <div>
            <dt>服务提供方</dt>
            <dd>Kimi</dd>
          </div>
          <div>
            <dt>已配置密钥</dt>
            <dd>{{ slots.filter((entry) => entry.meta?.configured).length }} / {{ slots.length }}</dd>
          </div>
          <div>
            <dt>当前密钥</dt>
            <dd>密钥 {{ activeSlot }}</dd>
          </div>
        </dl>
      </aside>
    </div>
  </section>
</template>

<style scoped>
.credentials-page {
  max-width: 1120px;
  margin: 0 auto;
  padding: 32px 36px 48px;
  color: #17213a;
}

.page-heading,
.card-heading,
.form-actions,
.credential-select,
.credentials-layout {
  display: flex;
}

.page-heading,
.card-heading,
.form-actions {
  align-items: center;
  justify-content: space-between;
  gap: 24px;
}

.eyebrow {
  margin: 0 0 8px;
  color: #6755ed;
  font-size: 13px;
  font-weight: 700;
}

h1,
h2,
p {
  margin-top: 0;
}

h1 {
  margin-bottom: 10px;
  font-size: 30px;
  line-height: 1.2;
}

h2 {
  margin-bottom: 8px;
  font-size: 18px;
}

.page-description,
.card-heading p {
  margin-bottom: 0;
  color: #6d7892;
  line-height: 1.6;
}

.notice {
  margin-top: 22px;
  padding: 12px 16px;
  border: 1px solid;
  border-radius: 8px;
}

.notice--error {
  border-color: #f0b4b4;
  background: #fff7f7;
  color: #b42318;
}

.notice--success {
  border-color: #a7dfc0;
  background: #f2fbf5;
  color: #18794e;
}

.credentials-layout {
  align-items: flex-start;
  gap: 24px;
  margin-top: 24px;
}

.credentials-card,
.status-card {
  border: 1px solid #e1e6f0;
  border-radius: 10px;
  background: #fff;
  box-shadow: 0 8px 24px rgb(30 45 90 / 5%);
}

.credentials-card {
  flex: 1 1 720px;
  padding: 26px;
}

.status-card {
  flex: 0 0 280px;
  padding: 26px;
}

.provider-badge,
.active-label {
  color: #6755ed;
  font-size: 13px;
  font-weight: 700;
}

.credential-list {
  margin-top: 24px;
}

.credential-row {
  display: grid;
  grid-template-columns: minmax(150px, 0.8fr) minmax(260px, 1.6fr);
  align-items: center;
  gap: 20px;
  padding: 18px 0;
  border-top: 1px solid #edf0f6;
}

.credential-select {
  align-items: center;
  gap: 10px;
}

.credential-select input {
  width: 16px;
  height: 16px;
  accent-color: #6755ed;
}

.credential-input-wrap {
  display: flex;
  flex-direction: column;
  gap: 5px;
}

.credential-input {
  width: 100%;
  box-sizing: border-box;
  padding: 11px 13px;
  border: 1px solid #cfd7e6;
  border-radius: 7px;
  color: #17213a;
  font: inherit;
}

.credential-input:focus {
  border-color: #6755ed;
  outline: 3px solid rgb(103 85 237 / 14%);
}

small {
  color: #8993aa;
  font-size: 12px;
}

.security-note {
  display: flex;
  flex-direction: column;
  gap: 5px;
  margin-top: 18px;
  padding: 14px 16px;
  border-left: 3px solid #6755ed;
  background: #f7f6ff;
  color: #64708a;
  line-height: 1.5;
}

.security-note strong {
  color: #3f3696;
}

.form-actions {
  justify-content: flex-end;
  margin-top: 24px;
}

.primary-button,
.secondary-button {
  min-height: 38px;
  padding: 0 16px;
  border-radius: 7px;
  font: inherit;
  cursor: pointer;
}

.primary-button {
  border: 1px solid #6755ed;
  background: #6755ed;
  color: #fff;
}

.secondary-button {
  border: 1px solid #cfd7e6;
  background: #fff;
  color: #46536e;
}

button:disabled {
  cursor: not-allowed;
  opacity: 0.55;
}

.status-card dl {
  margin: 20px 0 0;
}

.status-card dl div {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  padding: 14px 0;
  border-top: 1px solid #edf0f6;
}

dt {
  color: #7a849b;
}

dd {
  margin: 0;
  color: #263454;
  font-weight: 700;
  text-align: right;
}

@media (max-width: 760px) {
  .credentials-page {
    padding: 24px 18px 36px;
  }

  .page-heading,
  .credentials-layout {
    align-items: stretch;
    flex-direction: column;
  }

  .credential-row {
    grid-template-columns: 1fr;
    gap: 10px;
  }

  .status-card {
    flex-basis: auto;
  }
}
</style>
