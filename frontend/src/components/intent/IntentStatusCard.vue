<template>
  <section class="intent-status-card">
    <h2>教学意图状态</h2>
    <div class="intent-status-card__notice">
      <span class="intent-status-card__hourglass" aria-hidden="true" />
      <div>
        <strong>{{ statusLabel }}</strong>
        <p>{{ description }}</p>
      </div>
    </div>
    <dl>
      <div>
        <dt>创建时间</dt>
        <dd>{{ formatFullDateTime(createdAt) }}</dd>
      </div>
      <div>
        <dt>最后更新</dt>
        <dd>{{ formatFullDateTime(updatedAt) }}</dd>
      </div>
    </dl>
  </section>
</template>

<script setup lang="ts">
import { formatFullDateTime } from '@/utils/presentation';

withDefaults(
  defineProps<{
    statusLabel?: string;
    description?: string;
    createdAt?: string;
    updatedAt?: string;
  }>(),
  {
    statusLabel: '待生成',
    description: '完成前置步骤后即可生成并确认教学意图',
    createdAt: '',
    updatedAt: '',
  },
);
</script>

<style scoped>
.intent-status-card {
  height: 100%;
  padding: 18px 16px 16px;
  border: 1px solid #e5e9f1;
  border-radius: 12px;
  background: #fff;
}

.intent-status-card h2 {
  margin: 0 0 12px;
  color: #171b2c;
  font-size: 16px;
}

.intent-status-card__notice {
  display: flex;
  align-items: center;
  gap: 12px;
  height: 68px;
  padding: 0 14px;
  border: 1px solid #f6d9ad;
  border-radius: 9px;
  background: #fff9ef;
}

.intent-status-card__hourglass {
  position: relative;
  width: 34px;
  height: 38px;
  flex: 0 0 34px;
  border-radius: 8px;
  background: #fff2df;
}

.intent-status-card__hourglass::before,
.intent-status-card__hourglass::after {
  position: absolute;
  left: 9px;
  width: 16px;
  height: 13px;
  background: #f28b17;
  content: "";
}

.intent-status-card__hourglass::before {
  top: 6px;
  clip-path: polygon(0 0, 100% 0, 50% 100%);
}

.intent-status-card__hourglass::after {
  bottom: 6px;
  clip-path: polygon(50% 0, 100% 100%, 0 100%);
}

.intent-status-card__notice strong {
  color: #df7915;
  font-size: 14px;
}

.intent-status-card__notice p {
  margin: 4px 0 0;
  color: #c67d2f;
  font-size: 11px;
}

.intent-status-card dl {
  display: grid;
  gap: 12px;
  margin: 16px 0 0;
  color: #78839b;
  font-size: 12px;
}

.intent-status-card dl div {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.intent-status-card dt,
.intent-status-card dd {
  margin: 0;
}

.intent-status-card dd {
  color: #53617c;
}
</style>
