<template>
  <section class="page">
    <header class="page__header">
      <h2 class="page__title">首页</h2>
      <p class="page__description">
        从这里进入教师备课演示闭环：项目列表、新建课件项目、选择生成模式，然后继续填写教学需求。
      </p>
    </header>

    <StatusCard
      title="教师端主流程起点"
      description="TA-006 已接入项目管理和生成模式接口；真实 Dify、资料上传与 RAG 仍由后续任务完成。"
    />

    <el-card class="page-card" shadow="never">
      <p>后端接口地址：{{ apiBaseURL }}</p>
      <div class="page__actions">
        <el-button type="primary" @click="router.push('/projects')">
          进入项目列表
        </el-button>
        <el-button @click="router.push('/projects/new')">
          新建课件项目
        </el-button>
        <el-button :loading="checking" @click="handleCheckHealth">
          检查后端状态
        </el-button>
      </div>
      <el-alert
        v-if="healthMessage"
        class="health-result"
        :title="healthMessage"
        :type="healthStatus === 'UP' ? 'success' : 'warning'"
        show-icon
        :closable="false"
      />
    </el-card>
  </section>
</template>

<script setup lang="ts">
import StatusCard from '@/components/StatusCard.vue';
import { apiBaseURL } from '@/api/http';
import { checkBackendHealth } from '@/api/health';
import { ref } from 'vue';
import { useRouter } from 'vue-router';

const router = useRouter();
const checking = ref(false);
const healthStatus = ref('');
const healthMessage = ref('');

async function handleCheckHealth() {
  checking.value = true;
  healthMessage.value = '';

  try {
    const result = await checkBackendHealth();
    healthStatus.value = result.data.status;
    healthMessage.value = `后端状态：${result.data.status} / 服务：${result.data.service} / 版本：${result.data.version}`;
  } catch (error) {
    healthStatus.value = 'DOWN';
    healthMessage.value = '后端健康检查失败，请确认 backend 服务已在 8080 端口启动。';
  } finally {
    checking.value = false;
  }
}
</script>
