<template>
  <section class="page">
    <header class="page-hero">
      <div>
        <h2>新建教学项目</h2>
        <p>先建立项目容器，后续需求、资料、知识和教学意图都会沉淀在同一个项目中。</p>
      </div>
      <el-button @click="router.push('/projects')">返回项目列表</el-button>
    </header>

    <div class="grid cols-2">
      <el-card shadow="never">
        <el-form label-position="top" :model="form">
          <el-form-item label="项目名称">
            <el-input v-model="form.projectName" placeholder="例如：人工智能基础概念与应用" />
          </el-form-item>
          <el-form-item label="课程名称">
            <el-input v-model="form.courseName" placeholder="例如：人工智能基础" />
          </el-form-item>
          <el-form-item label="章节主题">
            <el-input v-model="form.chapterTitle" placeholder="例如：人工智能的基本概念" />
          </el-form-item>
          <el-form-item label="授课对象">
            <el-input v-model="form.targetStudents" placeholder="例如：大学本科一年级" />
          </el-form-item>
          <el-form-item label="课时长度">
            <el-input v-model="form.lessonDuration" placeholder="例如：2 课时（90 分钟）" />
          </el-form-item>
          <div class="page-actions">
            <el-button type="primary" @click="createFrontendProject">创建并进入项目</el-button>
            <el-button @click="router.push('/projects')">取消</el-button>
          </div>
        </el-form>
      </el-card>

      <section class="panel">
        <h3>当前阶段说明</h3>
        <p>这是前端先行版本。创建动作会完成页面跳转和项目上下文演示，后端合并后再替换为真实 `POST /api/projects`。</p>
        <div class="step-strip" style="grid-template-columns: 1fr; margin-top: 18px">
          <span class="is-active">创建项目</span>
          <span>选择模式</span>
          <span>教学需求与澄清</span>
          <span>资料与知识增强</span>
          <span>教学意图确认</span>
        </div>
      </section>
    </div>
  </section>
</template>

<script setup lang="ts">
import { reactive } from 'vue';
import { useRouter } from 'vue-router';

const router = useRouter();
const form = reactive({
  projectName: '人工智能基础概念与应用',
  courseName: '人工智能基础',
  chapterTitle: '人工智能的基本概念与发展历程',
  targetStudents: '大学本科一年级',
  lessonDuration: '2 课时（90 分钟）',
});

function createFrontendProject() {
  router.push('/projects/1/mode');
}
</script>
