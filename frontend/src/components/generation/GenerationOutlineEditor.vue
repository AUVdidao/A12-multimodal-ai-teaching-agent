<template>
  <section class="outline-editor">
    <header class="outline-editor__header">
      <div class="outline-editor__title">
        <span :class="['outline-editor__icon', `is-${tone}`]">
          <el-icon><component :is="sectionIcon" /></el-icon>
        </span>
        <div>
          <h3>{{ title }}</h3>
          <p>{{ description }}</p>
        </div>
      </div>
      <UiStatusPill :label="`${modelValue.length} 节`" tone="gray" />
    </header>

    <div v-if="modelValue.length" class="outline-editor__list">
      <div v-for="(item, index) in modelValue" :key="`${item.order}-${index}`" class="outline-editor__row">
        <span class="outline-editor__order">{{ index + 1 }}</span>
        <div class="outline-editor__fields">
          <el-input
            :model-value="item.title"
            :disabled="disabled"
            :aria-label="`${title}第 ${index + 1} 节标题`"
            maxlength="200"
            placeholder="章节标题"
            @update:model-value="updateField(index, 'title', $event)"
          />
          <el-input
            :model-value="item.description"
            :disabled="disabled"
            :aria-label="`${title}第 ${index + 1} 节描述`"
            type="textarea"
            :autosize="{ minRows: 2, maxRows: 4 }"
            maxlength="2000"
            show-word-limit
            placeholder="说明本节内容与教学安排"
            @update:model-value="updateField(index, 'description', $event)"
          />
        </div>
        <div v-if="!disabled" class="outline-editor__tools">
          <el-tooltip content="上移" placement="top">
            <el-button text circle :icon="ArrowUpBold" :disabled="index === 0" :aria-label="`上移第 ${index + 1} 节`" @click="move(index, -1)" />
          </el-tooltip>
          <el-tooltip content="下移" placement="top">
            <el-button text circle :icon="ArrowDownBold" :disabled="index === modelValue.length - 1" :aria-label="`下移第 ${index + 1} 节`" @click="move(index, 1)" />
          </el-tooltip>
          <el-tooltip content="删除" placement="top">
            <el-button text circle type="danger" :icon="Delete" :aria-label="`删除第 ${index + 1} 节`" @click="remove(index)" />
          </el-tooltip>
        </div>
      </div>
    </div>

    <StatePanel v-else type="empty" title="当前大纲没有章节" description="可添加章节后再保存方案。" />

    <el-button v-if="!disabled" class="outline-editor__add" plain :icon="Plus" @click="add">
      添加章节
    </el-button>
  </section>
</template>

<script setup lang="ts">
import type { PlanOutlineItem } from '@/api/generation';
import StatePanel from '@/components/StatePanel.vue';
import UiStatusPill from '@/components/ui/UiStatusPill.vue';
import { ArrowDownBold, ArrowUpBold, DataBoard, Delete, Document, Plus } from '@element-plus/icons-vue';
import { computed } from 'vue';

const props = withDefaults(defineProps<{
  modelValue: PlanOutlineItem[];
  title: string;
  description: string;
  kind: 'ppt' | 'docx';
  tone?: 'purple' | 'blue';
  disabled?: boolean;
}>(), {
  tone: 'purple',
  disabled: false,
});

const emit = defineEmits<{
  'update:modelValue': [value: PlanOutlineItem[]];
}>();

const sectionIcon = computed(() => (props.kind === 'ppt' ? DataBoard : Document));

function normalize(items: PlanOutlineItem[]) {
  return items.map((item, index) => ({ ...item, order: index + 1 }));
}

function updateField(index: number, field: 'title' | 'description', value: string) {
  emit('update:modelValue', normalize(props.modelValue.map((item, itemIndex) => (
    itemIndex === index ? { ...item, [field]: value } : { ...item }
  ))));
}

function move(index: number, offset: number) {
  const target = index + offset;
  if (target < 0 || target >= props.modelValue.length) return;
  const next = props.modelValue.map((item) => ({ ...item }));
  [next[index], next[target]] = [next[target], next[index]];
  emit('update:modelValue', normalize(next));
}

function remove(index: number) {
  emit('update:modelValue', normalize(props.modelValue.filter((_, itemIndex) => itemIndex !== index)));
}

function add() {
  emit('update:modelValue', normalize([
    ...props.modelValue.map((item) => ({ ...item })),
    { order: props.modelValue.length + 1, title: '', description: '' },
  ]));
}
</script>

<style scoped>
.outline-editor {
  min-width: 0;
  padding: 18px;
  border: 1px solid var(--ui-border);
  border-radius: 8px;
  background: var(--ui-panel);
  box-shadow: var(--shadow-panel);
}

.outline-editor__header,
.outline-editor__title {
  display: flex;
  align-items: center;
}

.outline-editor__header {
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 8px;
}

.outline-editor__title {
  min-width: 0;
  gap: 12px;
}

.outline-editor__title > div {
  min-width: 0;
}

.outline-editor__icon {
  display: grid;
  width: 40px;
  height: 40px;
  flex: 0 0 40px;
  place-items: center;
  border-radius: 8px;
  font-size: 20px;
}

.outline-editor__icon.is-purple {
  background: var(--ui-primary-soft);
  color: var(--ui-primary);
}

.outline-editor__icon.is-blue {
  background: #edf4ff;
  color: var(--ui-info);
}

.outline-editor h3,
.outline-editor p {
  margin: 0;
}

.outline-editor h3 {
  font-size: 16px;
}

.outline-editor p {
  margin-top: 2px;
  color: var(--ui-muted);
  font-size: 12px;
  line-height: 1.5;
}

.outline-editor__list {
  border-top: 1px solid var(--ui-border);
}

.outline-editor__row {
  display: grid;
  grid-template-columns: 28px minmax(0, 1fr) 36px;
  align-items: start;
  gap: 12px;
  padding: 14px 0;
  border-bottom: 1px solid var(--ui-border);
}

.outline-editor__order {
  display: grid;
  width: 28px;
  height: 28px;
  place-items: center;
  border-radius: 50%;
  background: var(--ui-panel-soft);
  color: var(--ui-muted);
  font-size: 12px;
  font-weight: 700;
}

.outline-editor__fields {
  display: grid;
  min-width: 0;
  gap: 8px;
}

.outline-editor__tools {
  display: grid;
  grid-template-rows: repeat(3, 32px);
  align-items: center;
  justify-items: center;
}

.outline-editor__tools :deep(.el-button) {
  width: 32px;
  min-height: 32px;
  height: 32px;
  margin: 0;
}

.outline-editor__add {
  width: 100%;
  margin-top: 14px;
}

@media (max-width: 640px) {
  .outline-editor {
    padding: 14px;
  }

  .outline-editor__header {
    align-items: flex-start;
  }

  .outline-editor__row {
    grid-template-columns: 24px minmax(0, 1fr);
    gap: 8px;
  }

  .outline-editor__order {
    width: 24px;
    height: 24px;
  }

  .outline-editor__tools {
    grid-column: 2;
    grid-template-columns: repeat(3, 32px);
    grid-template-rows: 32px;
    justify-content: end;
  }
}
</style>
