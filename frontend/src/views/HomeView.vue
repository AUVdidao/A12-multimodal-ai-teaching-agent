<template>
  <section class="page">
    <header class="page__header">
      <h2 class="page__title">首页</h2>
      <p class="page__description">
        从这里进入教师备课演示闭环，先检查后端服务，再创建课件项目并逐步完成需求澄清、资料增强、内容生成和导出。
      </p>
    </header>

    <StatusCard title="M0 工程基础已就绪" description="当前页面用于验证前端壳、路由和后端健康检查联通。" />

    <el-card class="page-card" shadow="never">
      <p>后端接口地址：{{ apiBaseURL }}</p>
      <div class="page__actions">
        <el-button type="primary" :loading="checking" @click="handleCheckHealth">
          检查后端状态
        </el-button>
        <el-button @click="router.push('/projects/new')">下一步：新建课件项目</el-button>
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
