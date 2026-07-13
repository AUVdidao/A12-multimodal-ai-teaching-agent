<template>
  <section class="intent-page" data-testid="teaching-intent-page">
    <IntentWorkflowStepper />

    <div class="intent-page__content">
      <form class="intent-page__form" @submit.prevent="confirmIntent">
        <section class="intent-project-card">
          <span class="intent-project-card__icon">
            <A12AssetIcon name="folder" :size="34" />
          </span>
          <div>
            <small>项目名称</small>
            <div class="intent-project-card__title">
              <h2>人工智能基础概念与应用</h2>
              <button type="button" aria-label="编辑项目名称">
                <A12AssetIcon name="pencil" :size="20" />
              </button>
            </div>
          </div>
          <p>面向大学本科一年级学生，理解人工智能的基本概念、发展历程与典型应用，建立初步的 AI 素养。</p>
        </section>

        <IntentFormSection
          title="生成目标"
          description="本项目意图达成的核心教学目标（可多选）"
          icon="target"
          tone="purple"
        >
          <div class="intent-tag-grid">
            <IntentCheckTag
              v-for="goal in goalOptions"
              :key="goal"
              :label="goal"
              :selected="goals.includes(goal)"
              @toggle="toggleGoal(goal)"
            />
          </div>
        </IntentFormSection>

        <IntentFormSection
          title="内容依据"
          description="教学内容的主要来源与依据"
          icon="document"
          tone="blue"
          align="start"
        >
          <div class="intent-basis">
            <label class="intent-select intent-select--wide">
              <select v-model="basis" aria-label="教学内容的主要来源与依据">
                <option v-for="item in basisOptions" :key="item.value" :value="item.value">
                  {{ item.label }}
                </option>
              </select>
              <span aria-hidden="true" />
            </label>
            <small>补充依据（可选）</small>
            <div class="intent-basis__tags">
              <span v-for="(item, index) in supplementalBasis" :key="item">
                {{ item }}
                <button type="button" :aria-label="`删除${item}`" @click="removeBasis(index)">×</button>
              </span>
              <button class="intent-basis__add" type="button" @click="addBasis">
                <A12AssetIcon name="plus-circle" :size="17" />
                添加依据
              </button>
            </div>
          </div>
        </IntentFormSection>

        <IntentFormSection
          title="教学组织"
          description="面向学生、学时安排与教学形式"
          icon="users"
          tone="green"
        >
          <div class="intent-organization">
            <label>
              <span>面向对象</span>
              <span class="intent-select">
                <select v-model="audience" aria-label="面向对象">
                  <option value="u1">大学本科一年级</option>
                  <option value="u2">大学本科二年级</option>
                </select>
                <i aria-hidden="true" />
              </span>
            </label>
            <label>
              <span>总学时</span>
              <span class="intent-select">
                <select v-model="hours" aria-label="总学时">
                  <option value="16">16 学时</option>
                  <option value="20">20 学时</option>
                </select>
                <i aria-hidden="true" />
              </span>
            </label>
            <label>
              <span>教学形式</span>
              <span class="intent-select">
                <select v-model="format" aria-label="教学形式">
                  <option value="mix">线上线下混合式教学</option>
                  <option value="offline">线下课堂教学</option>
                </select>
                <i aria-hidden="true" />
              </span>
            </label>
          </div>
        </IntentFormSection>

        <IntentFormSection
          title="输出类型"
          description="期望产出的教学方案与资源"
          icon="lightbulb"
          tone="orange"
        >
          <div class="intent-tag-grid">
            <IntentCheckTag
              v-for="output in outputOptions"
              :key="output"
              :label="output"
              :selected="outputs.includes(output)"
              @toggle="toggleOutput(output)"
            />
          </div>
        </IntentFormSection>

        <IntentFormSection
          title="备注说明（可选）"
          description=""
          icon="document"
          tone="gray"
          layout="stacked"
        >
          <div class="intent-notes">
            <textarea
              v-model="notes"
              maxlength="200"
              aria-label="备注说明"
              placeholder="请输入补充说明，如教学重点、使用限制等（200字以内）"
            />
            <span>{{ notes.length }}/200</span>
          </div>
        </IntentFormSection>

        <div class="intent-page__actions">
          <button class="intent-button intent-button--secondary" type="button" @click="saveDraft">
            <A12AssetIcon name="document" :size="20" />
            保存草稿
          </button>
          <button class="intent-button intent-button--primary" type="submit">
            <A12AssetIcon name="check-circle" :size="20" />
            确认教学意图
          </button>
        </div>
      </form>

      <aside class="intent-page__aside">
        <IntentStatusCard />
        <IntentEvidencePanel
          :items="evidence"
          @expand="showFeedback('已展开全部依据证据')"
          @search="router.push(`/projects/${projectId}/knowledge`)"
        />
      </aside>
    </div>

    <transition name="intent-toast">
      <div v-if="feedback" class="intent-feedback" role="status">{{ feedback }}</div>
    </transition>
  </section>
</template>

<script setup lang="ts">
import IntentCheckTag from '@/components/intent/IntentCheckTag.vue';
import IntentEvidencePanel from '@/components/intent/IntentEvidencePanel.vue';
import IntentFormSection from '@/components/intent/IntentFormSection.vue';
import IntentStatusCard from '@/components/intent/IntentStatusCard.vue';
import IntentWorkflowStepper from '@/components/intent/IntentWorkflowStepper.vue';
import A12AssetIcon from '@/components/ui/A12AssetIcon.vue';
import { onBeforeUnmount, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';

const route = useRoute();
const router = useRouter();
const projectId = String(route.params.projectId || '1');

const goalOptions = ['知识理解', '概念掌握', '应用能力', '思维提升', '价值塑造'];
const outputOptions = ['教学大纲', '教学PPT', '课堂活动', '习题与测评', '案例库', '参考资料'];
const goals = ref(['知识理解', '概念掌握', '应用能力']);
const outputs = ref(['教学大纲', '教学PPT', '课堂活动', '习题与测评', '案例库']);
const basis = ref('outline');
const basisOptions = [
  {
    value: 'outline',
    label: '教育部高等学校人工智能专业教学指导分委员会《人工智能导论》课程大纲（2023）',
  },
  { value: 'textbook', label: '《人工智能基础（第3版）》教材解析' },
];
const supplementalBasis = ref([
  '中国新一代人工智能发展规划（2017）',
  '斯坦福大学《AI 100》课程大纲',
]);
const audience = ref('u1');
const hours = ref('16');
const format = ref('mix');
const notes = ref('');
const feedback = ref('');
let feedbackTimer: number | undefined;

const evidence = [
  {
    title: '《人工智能导论》课程大纲（2023）',
    type: '官方文件',
    source: '教育部高等学校人工智能专业教学指导分委员会',
    reason: '明确了人工智能基础概念、发展历程与应用场景为课程核心内容，与项目目标高度一致。',
    fragment: '课程目标：使学生理解人工智能的基本概念、原理与方法，了...',
    tone: 'purple' as const,
  },
  {
    title: '中国新一代人工智能发展规划（2017）',
    type: '政策文件',
    source: '国务院',
    reason: '提供了人工智能发展的国家战略背景与应用方向，支撑课程的价值塑造目标。',
    fragment: '到2030年，我国人工智能理论、技术与应用总体达到世界领先...',
    tone: 'blue' as const,
  },
  {
    title: '斯坦福大学《AI 100》课程大纲',
    type: '课程资料',
    source: 'Stanford University',
    reason: '国际知名高校通识课程，内容体系完整，可作为教学内容组织与案例设计的参考。',
    fragment: 'This course provides a broad introduction to artificial intelligenc...',
    tone: 'green' as const,
  },
];

function toggleGoal(value: string) {
  goals.value = goals.value.includes(value)
    ? goals.value.filter((item) => item !== value)
    : [...goals.value, value];
}

function toggleOutput(value: string) {
  outputs.value = outputs.value.includes(value)
    ? outputs.value.filter((item) => item !== value)
    : [...outputs.value, value];
}

function removeBasis(index: number) {
  supplementalBasis.value.splice(index, 1);
}

function addBasis() {
  const mockBasis = '人工智能通识教育教学指南';
  if (!supplementalBasis.value.includes(mockBasis)) {
    supplementalBasis.value.push(mockBasis);
  }
}

function showFeedback(message: string) {
  feedback.value = message;
  window.clearTimeout(feedbackTimer);
  feedbackTimer = window.setTimeout(() => {
    feedback.value = '';
  }, 1800);
}

function saveDraft() {
  showFeedback('草稿已保存');
}

function confirmIntent() {
  showFeedback('教学意图已确认');
  window.setTimeout(() => {
    router.push(`/projects/${projectId}/plan`);
  }, 300);
}

onBeforeUnmount(() => window.clearTimeout(feedbackTimer));
</script>

<style scoped>
.intent-page {
  position: relative;
  display: flex;
  min-width: 1160px;
  height: 100%;
  flex-direction: column;
  color: #171b2c;
}

.intent-page > :deep(.intent-stepper) {
  flex: 0 0 48px;
  margin-bottom: 9px;
}

.intent-page__content {
  display: grid;
  grid-template-columns: minmax(0, 1fr) clamp(440px, 38%, 510px);
  min-height: 0;
  flex: 1;
  gap: 13px;
}

.intent-page__form {
  display: grid;
  grid-template-rows: 104px 102px 162px 90px 90px 104px 44px;
  min-width: 0;
  min-height: 0;
  gap: 10px;
}

.intent-project-card {
  display: grid;
  grid-template-columns: 52px minmax(0, 1fr);
  align-items: center;
  gap: 10px;
  height: 100%;
  padding: 12px 16px;
  border: 1px solid #e6eaf2;
  border-radius: 12px;
  background: #fff;
}

.intent-project-card__icon {
  display: grid;
  width: 48px;
  height: 48px;
  place-items: center;
  border-radius: 11px;
  background: #f1edff;
}

.intent-project-card small {
  color: #8b95aa;
  font-size: 11px;
}

.intent-project-card__title {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 2px;
}

.intent-project-card h2 {
  margin: 0;
  font-size: 21px;
  font-weight: 700;
}

.intent-project-card__title button {
  padding: 0;
  border: 0;
  background: transparent;
  cursor: pointer;
}

.intent-project-card p {
  grid-column: 1 / -1;
  margin: 0;
  color: #4d5871;
  font-size: 12px;
  line-height: 1.45;
}

.intent-tag-grid {
  display: flex;
  flex-wrap: wrap;
  gap: 7px 8px;
}

.intent-basis {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  gap: 6px;
}

.intent-basis > small {
  color: #808ba3;
  font-size: 10.5px;
}

.intent-basis__tags {
  display: flex;
  min-height: 65px;
  min-width: 0;
  flex-wrap: wrap;
  align-content: flex-start;
  gap: 5px;
  padding: 5px 6px;
  border: 1px solid #e1e5ed;
  border-radius: 7px;
  overflow: hidden;
  margin-top: 5px;
}

.intent-basis__tags > span {
  display: inline-flex;
  align-items: center;
  min-width: 0;
  gap: 5px;
  padding: 3px 7px;
  border-radius: 5px;
  background: #f1edff;
  color: #6554ed;
  font-size: 9.5px;
  white-space: nowrap;
}

.intent-basis__tags button {
  padding: 0;
  border: 0;
  background: transparent;
  color: #9589e8;
  cursor: pointer;
}

.intent-basis__add {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  height: 30px;
  padding: 0 7px;
  border: 1px solid #dfe4ed;
  border-radius: 7px;
  background: #fff;
  color: #6d7890;
  cursor: pointer;
  font-size: 10px;
  white-space: nowrap;
  margin-right: calc(100% - 92px);
}

.intent-select {
  position: relative;
  display: block;
  min-width: 0;
}

.intent-select--wide {
  width: 100%;
}

.intent-select select {
  width: 100%;
  height: 36px;
  padding: 0 34px 0 10px;
  border: 1px solid #dfe4ed;
  border-radius: 7px;
  outline: 0;
  appearance: none;
  background: #fff;
  color: #48536c;
  font-size: 11.5px;
}

.intent-select select:focus {
  border-color: #8f82fb;
  box-shadow: 0 0 0 2px rgba(98, 87, 246, 0.1);
}

.intent-select > span,
.intent-select > i {
  position: absolute;
  top: 50%;
  right: 12px;
  width: 6px;
  height: 6px;
  border-right: 1.5px solid #8791a8;
  border-bottom: 1.5px solid #8791a8;
  pointer-events: none;
  transform: translateY(-70%) rotate(45deg);
}

.intent-organization {
  display: grid;
  grid-template-columns: 1fr 0.78fr 1.22fr;
  gap: 10px;
}

.intent-organization > label {
  display: grid;
  gap: 4px;
  color: #78849d;
  font-size: 10px;
}

.intent-organization .intent-select select {
  height: 36px;
}

.intent-notes {
  position: relative;
}

.intent-notes textarea {
  display: block;
  width: 100%;
  height: 54px;
  padding: 10px 12px 20px;
  border: 1px solid #dfe4ed;
  border-radius: 7px;
  outline: 0;
  resize: none;
  color: #3e485f;
  font-size: 11px;
  line-height: 1.45;
}

.intent-notes textarea::placeholder {
  color: #a0a9ba;
}

.intent-notes textarea:focus {
  border-color: #8f82fb;
}

.intent-notes span {
  position: absolute;
  right: 8px;
  bottom: 6px;
  color: #8c96ab;
  font-size: 10px;
}

.intent-page__actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 12px;
  padding-right: 23px;
  transform: translateY(-1px);
}

.intent-button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 7px;
  height: 42px;
  padding: 0 24px;
  border-radius: 8px;
  cursor: pointer;
  font-size: 13px;
  font-weight: 600;
}

.intent-button--secondary {
  min-width: 150px;
  border: 1px solid #d9dfe9;
  background: #fff;
  color: #58647d;
}

.intent-button--primary {
  min-width: 166px;
  border: 1px solid #5b45f6;
  background: linear-gradient(135deg, #735eff, #5438ef);
  box-shadow: 0 5px 12px rgba(91, 69, 246, 0.2);
  color: #fff;
}

.intent-button--primary :deep(.a12-asset-icon) {
  filter: brightness(0) invert(1);
}

.intent-button--secondary :deep(.a12-asset-icon) {
  filter: grayscale(1);
}

.intent-page__aside {
  display: grid;
  grid-template-rows: 224px minmax(0, 1fr);
  min-height: 0;
  gap: 11px;
}

.intent-feedback {
  position: absolute;
  top: 54px;
  left: 50%;
  z-index: 10;
  padding: 9px 16px;
  border: 1px solid #dcd7ff;
  border-radius: 8px;
  background: #fff;
  box-shadow: 0 8px 24px rgba(32, 39, 66, 0.12);
  color: #5b45f6;
  font-size: 12px;
  transform: translateX(-50%);
}

.intent-toast-enter-active,
.intent-toast-leave-active {
  transition: opacity 140ms ease, transform 140ms ease;
}

.intent-toast-enter-from,
.intent-toast-leave-to {
  opacity: 0;
  transform: translate(-50%, -4px);
}

@media (max-width: 1320px) {
  .intent-page__content {
    grid-template-columns: minmax(0, 1fr) 440px;
  }

  .intent-tag-grid :deep(.intent-check-tag) {
    width: 108px;
  }
}

@media (max-width: 1180px) {
  .intent-page {
    min-width: 0;
    height: auto;
  }

  .intent-page__content {
    grid-template-columns: 1fr;
  }

  .intent-page__form {
    grid-template-rows: auto;
  }

  .intent-page__aside {
    grid-template-rows: 224px 540px;
  }
}
</style>
