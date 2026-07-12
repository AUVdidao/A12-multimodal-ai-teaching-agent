<template>
  <article class="material-card">
    <header class="material-card__header">
      <div class="material-card__file"><el-icon><Document /></el-icon><div><h3>{{ material.originalFilename }}</h3><p>{{ material.fileType }} · {{ formatFileSize(material.fileSize) }} · {{ formatDateTime(material.createdAt) }}</p></div></div>
      <span :class="['parse-badge', `parse-badge--${material.parseStatus.toLowerCase()}`]">{{ parseStatusLabels[material.parseStatus] }}</span>
    </header>

    <div class="material-card__body">
      <section class="material-card__section">
        <div class="section-heading"><strong>资料用途</strong><p>用途会参与原型摘要、检索评分和教学意图融合。</p></div>
        <el-checkbox-group :model-value="usageTypes" class="usage-grid" @update:model-value="emit('update:usageTypes', $event as MaterialUsageType[])">
          <el-checkbox v-for="option in usageOptions" :key="option.value" :value="option.value"><span><strong>{{ option.label }}</strong><small>{{ option.description }}</small></span></el-checkbox>
        </el-checkbox-group>
        <el-input :model-value="usageNote" maxlength="500" placeholder="可选：补充这份资料在本课中的使用说明" @update:model-value="emit('update:usageNote', $event)" />
        <div class="material-card__actions">
          <el-button :loading="savingUsage" :disabled="usageTypes.length === 0 || parsing" @click="emit('save-usages')">保存用途</el-button>
          <el-button :icon="Download" @click="emit('download')">下载原文件</el-button>
          <el-button v-if="material.parseStatus !== 'FAILED'" type="primary" :loading="parsing" :disabled="usageTypes.length === 0 || savingUsage || material.parseStatus === 'SUCCEEDED'" @click="emit('parse')">{{ material.parseStatus === 'SUCCEEDED' ? '解析已完成' : '开始原型解析' }}</el-button>
          <el-button v-else type="primary" :icon="Refresh" :loading="parsing" @click="emit('retry')">重新解析</el-button>
        </div>
      </section>

      <section v-if="parseResult?.parseStatus === 'SUCCEEDED'" class="parse-result">
        <div class="prototype-note"><el-icon><InfoFilled /></el-icon><span>演示解析结果基于文件名、资料用途和已确认需求生成，未读取文件全文。</span></div>
        <h4>原型解析摘要</h4><p>{{ parseResult.summary }}</p>
        <div class="result-groups"><div><span>关键词</span><div class="tag-row"><el-tag v-for="keyword in parseResult.keywords" :key="keyword" effect="plain">{{ keyword }}</el-tag></div></div><div><span>适用教学环节</span><div class="tag-row"><el-tag v-for="stage in parseResult.applicableTeachingStages" :key="stage" type="success" effect="plain">{{ stage }}</el-tag></div></div></div>
      </section>
      <StatePanel v-else-if="parseResult?.parseStatus === 'FAILED'" type="error" title="原型解析未完成" :description="parseResult.failureReason || '请重试。'" />
    </div>
  </article>
</template>

<script setup lang="ts">
import type { MaterialParseResult, MaterialRecord, MaterialUsageType } from '@/api/materials';
import { formatDateTime, formatFileSize, parseStatusLabels, usageOptions } from '@/utils/materialLabels';
import { Document, Download, InfoFilled, Refresh } from '@element-plus/icons-vue';
import StatePanel from './StatePanel.vue';

defineProps<{ material: MaterialRecord; parseResult?: MaterialParseResult; usageTypes: MaterialUsageType[]; usageNote: string; savingUsage: boolean; parsing: boolean }>();
const emit = defineEmits<{
  'update:usageTypes': [value: MaterialUsageType[]];
  'update:usageNote': [value: string];
  'save-usages': [];
  parse: [];
  retry: [];
  download: [];
}>();
</script>

<style scoped>
.material-card { border: 1px solid var(--color-border); border-radius: var(--radius-lg); background: var(--color-surface); box-shadow: var(--shadow-card); }
.material-card__header { display: flex; align-items: center; justify-content: space-between; gap: 16px; padding: 17px 19px; border-bottom: 1px solid var(--color-border); }
.material-card__file { display: flex; align-items: center; min-width: 0; gap: 11px; }
.material-card__file > .el-icon { flex: 0 0 auto; color: var(--color-primary); font-size: 24px; }
h3, h4, p { margin: 0; }
h3 { overflow-wrap: anywhere; font-size: 15px; }
.material-card__file p, .section-heading p { margin-top: 3px; color: var(--color-text-muted); font-size: 11px; }
.parse-badge { flex: 0 0 auto; padding: 4px 8px; border-radius: var(--radius-sm); background: var(--color-warning-soft); color: var(--color-warning); font-size: 11px; font-weight: 700; }
.parse-badge--succeeded { background: var(--color-success-soft); color: var(--color-success); }
.parse-badge--failed { background: var(--color-danger-soft); color: var(--color-danger); }
.material-card__body { display: grid; gap: 18px; padding: 19px; }
.material-card__section { display: grid; gap: 13px; }
.section-heading strong { font-size: 13px; }
.usage-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 8px; }
.usage-grid :deep(.el-checkbox) { align-items: flex-start; height: auto; min-height: 54px; margin: 0; padding: 9px 11px; border: 1px solid var(--color-border); border-radius: var(--radius-md); white-space: normal; }
.usage-grid :deep(.el-checkbox.is-checked) { border-color: var(--color-primary-border); background: var(--color-primary-soft); }
.usage-grid strong, .usage-grid small { display: block; }
.usage-grid small { margin-top: 2px; color: var(--color-text-muted); font-size: 10px; }
.material-card__actions { display: flex; flex-wrap: wrap; gap: 9px; }
.material-card__actions .el-button + .el-button { margin-left: 0; }
.parse-result { padding: 16px; border-left: 3px solid var(--color-success); border-radius: var(--radius-md); background: var(--color-success-soft); }
.parse-result h4 { margin-top: 13px; font-size: 13px; }
.parse-result > p { margin-top: 6px; overflow-wrap: anywhere; color: var(--color-text-secondary); font-size: 12px; line-height: 1.7; }
.prototype-note { display: flex; align-items: flex-start; gap: 7px; color: var(--color-success); font-size: 11px; font-weight: 650; }
.result-groups { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 14px; margin-top: 14px; }
.result-groups > div > span { display: block; margin-bottom: 7px; color: var(--color-text-muted); font-size: 10px; font-weight: 700; }
.tag-row { display: flex; flex-wrap: wrap; gap: 6px; }
.tag-row :deep(.el-tag) { max-width: 100%; height: auto; min-height: 24px; white-space: normal; word-break: break-all; }
@media (max-width: 680px) { .material-card__header { align-items: flex-start; } .usage-grid, .result-groups { grid-template-columns: 1fr; } .material-card__actions, .material-card__actions .el-button { width: 100%; } }
</style>
