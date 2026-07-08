<template>
  <section class="page">
    <header class="page__header page__header--with-action">
      <div>
        <h2 class="page__title">项目列表</h2>
        <p class="page__description">
          查看已创建的备课项目，继续选择生成模式或进入教学需求输入。
        </p>
      </div>
      <el-button type="primary" @click="router.push('/projects/new')">
        新建项目
      </el-button>
    </header>

    <StatusCard
      title="主流程起点"
      description="当前页面已接入项目列表接口，支持从首页进入项目管理并继续演示闭环。"
    />

    <el-card class="page-card" shadow="never">
      <el-table
        v-loading="loading"
        :data="projects"
        empty-text="暂无项目，请先新建课件项目"
        @row-click="goToModeSelection"
      >
        <el-table-column prop="projectName" label="项目名称" min-width="180" />
        <el-table-column prop="courseName" label="课程" width="120" />
        <el-table-column prop="chapterTitle" label="章节主题" min-width="160" />
        <el-table-column label="状态" width="120">
          <template #default="{ row }">
            <el-tag size="small" effect="plain">{{ formatStatus(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="更新时间" min-width="180">
          <template #default="{ row }">
            {{ formatDate(row.updatedAt) }}
          </template>
        </el-table-column>
        <el-table-column label="下一步" width="132" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click.stop="goToModeSelection(row)">
              选择模式
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-alert
        v-if="errorMessage"
        class="inline-alert"
        :title="errorMessage"
        type="warning"
        show-icon
        :closable="false"
      />
    </el-card>

    <div class="page__actions">
      <el-button @click="loadProjects">刷新列表</el-button>
      <el-button @click="router.push('/')">返回首页</el-button>
    </div>
  </section>
</template>

<script setup lang="ts">
import StatusCard from '@/components/StatusCard.vue';
import { listProjects, type TeachingProject } from '@/api/projects';
import { onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';

const router = useRouter();
const loading = ref(false);
const projects = ref<TeachingProject[]>([]);
const errorMessage = ref('');

onMounted(loadProjects);

async function loadProjects() {
  loading.value = true;
  errorMessage.value = '';

  try {
    projects.value = await listProjects();
  } catch (error) {
    projects.value = [];
    errorMessage.value = '项目列表读取失败，请确认后端服务已启动。';
  } finally {
    loading.value = false;
  }
}

function goToModeSelection(project: TeachingProject) {
  router.push(`/projects/${project.id}/mode`);
}

function formatStatus(status: string) {
  const statusMap: Record<string, string> = {
    CREATED: '已创建',
    REQUIREMENT_CONFIRMED: '需求已确认',
    MATERIAL_READY: '资料就绪',
    INTENT_CONFIRMED: '意图已确认',
    GENERATED: '已生成',
    FINALIZED: '已定稿',
  };
  return statusMap[status] || status;
}

function formatDate(value: string) {
  if (!value) {
    return '-';
  }
  return new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date(value));
}
</script>
