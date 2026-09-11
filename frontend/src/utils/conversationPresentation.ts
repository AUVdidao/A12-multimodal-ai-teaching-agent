import type { GoPlanningDraft, GoQuestion } from '@/api/go';

export type QuestionOptionPresentation = {
  code: string;
  label: string;
  detail: string;
  selected: boolean;
};

export type QuestionGroupPresentation = {
  key: string;
  index: number;
  title: string;
  prompt: string;
  type: GoQuestion['type'];
  options: QuestionOptionPresentation[];
  answer: string;
  answered: boolean;
};

export type PlanSlidePresentation = {
  key: string;
  index: string;
  title: string;
  purpose: string;
  points: string[];
  layout: string;
  visual: string;
  classroomAction: string;
  sources: string[];
};

function text(value: unknown): string {
  if (typeof value === 'string') return value.trim();
  if (typeof value === 'number') return String(value);
  return '';
}

function textList(value: unknown): string[] {
  if (typeof value === 'string') return value.split(/\r?\n|[；;]/).map((item) => item.trim()).filter(Boolean);
  if (!Array.isArray(value)) return [];
  return value.flatMap((item) => {
    if (typeof item === 'string' || typeof item === 'number') return [text(item)];
    if (item && typeof item === 'object') {
      const record = item as Record<string, unknown>;
      return [text(record.text) || text(record.label) || text(record.title) || text(record.content)].filter(Boolean);
    }
    return [];
  });
}

function answerText(question: GoQuestion): string {
  const answer = question.latestAnswer;
  if (!answer) return '';
  return [answer.textAnswer, ...answer.selectedValues].map((item) => item.trim()).filter(Boolean).join('；');
}

function selectedByAnswer(optionLabel: string, optionDetail: string, question: GoQuestion): boolean {
  const answer = question.latestAnswer;
  if (!answer) return false;
  if (answer.selectedValues.some((value) => value === optionLabel || value.startsWith(optionLabel))) return true;
  const answerValue = answerText(question);
  if (answerValue.includes(optionLabel)) return true;
  return (optionDetail.match(/[\u4e00-\u9fa5]{2,}/g) || []).some((fragment) => fragment.length >= 2 && answerValue.includes(fragment));
}

function parseOptions(value: string, question: GoQuestion): QuestionOptionPresentation[] {
  const options: QuestionOptionPresentation[] = [];
  const matcher = /\(([A-Z])\)\s*([\s\S]*?)(?=\s*[；;]\s*\([A-Z]\)|$)/g;
  let match: RegExpExecArray | null;
  while ((match = matcher.exec(value)) !== null) {
    const raw = match[2].trim().replace(/[；;]+$/, '');
    const [label, ...detail] = raw.split(/——|--/);
    options.push({ code: match[1], label: label.trim(), detail: detail.join('——').trim(), selected: selectedByAnswer(label.trim(), detail.join('——').trim(), question) });
  }
  return options;
}

function answerForGroup(title: string, fullAnswer: string, groupCount: number): string {
  if (!fullAnswer || groupCount <= 1) return fullAnswer;
  if (/目标|对象/.test(title)) return fullAnswer.split(/安排\s*\d+\s*个课时/)[0].trim() || fullAnswer;
  if (/课时|环节|组织/.test(title)) {
    const match = fullAnswer.match(/安排\s*\d+\s*个课时[\s\S]*$/);
    return match?.[0]?.trim() || fullAnswer;
  }
  return fullAnswer;
}

export function presentQuestionGroups(question: GoQuestion): QuestionGroupPresentation[] {
  const raw = question.text || '';
  const sections = [...raw.matchAll(/【问题\s*(\d+)\s*[｜|]\s*([^】]+)】([\s\S]*?)(?=【问题\s*\d+\s*[｜|]|$)/g)];
  const fullAnswer = answerText(question);
  if (!sections.length) {
    return [{
      key: question.id,
      index: 1,
      title: '需求确认',
      prompt: raw,
      type: question.type,
      options: (question.options || []).map((option, index) => ({ code: String.fromCharCode(65 + index), label: option, detail: '', selected: selectedByAnswer(option, '', question) })),
      answer: fullAnswer,
      answered: Boolean(question.latestAnswer),
    }];
  }
  return sections.map((section, position) => {
    const body = section[3].trim();
    const firstOption = body.search(/\([A-Z]\)\s*/);
    const prompt = (firstOption >= 0 ? body.slice(0, firstOption) : body).replace(/[：:]\s*$/, '').trim();
    return {
      key: `${question.id}-${section[1]}`,
      index: Number(section[1]) || position + 1,
      title: section[2].trim(),
      prompt,
      type: parseOptions(body, question).length ? 'MULTI_CHOICE' : question.type,
      options: parseOptions(body, question),
      answer: answerForGroup(section[2], fullAnswer, sections.length),
      answered: Boolean(question.latestAnswer),
    };
  });
}

function fallbackForTitle(title: string, index: number): Omit<PlanSlidePresentation, 'key' | 'index' | 'title'> {
  const rules: Array<[RegExp, Omit<PlanSlidePresentation, 'key' | 'index' | 'title'>]> = [
    [/封面/, { purpose: '建立主题与学习预期', points: ['课题标题与核心问题', '本节课的学习任务', '教师与学生的课堂入口'], layout: '标题区 + 主视觉 · 留出讲解空间', visual: 'TCP 连接与数据流的抽象网络图', classroomAction: '教师用一个问题引入：连接建立前，双方为什么不能直接发送数据？', sources: [] }],
    [/课程目标|课时安排/, { purpose: '让学生明确本节课结束时要做到什么', points: ['理解 SYN、SYN-ACK、ACK 的作用', '能够在抓包中定位完整握手', '能够解释常见握手故障'], layout: '三项目标卡片 + 课时路线', visual: '目标图标与两课时路线条', classroomAction: '教师带学生快速浏览目标，并说明评价方式', sources: [] }],
    [/预备知识/, { purpose: '激活先备知识，降低进入新概念的门槛', points: ['端口与客户端/服务器角色', 'TCP 报文的基本字段', '连接状态的直觉理解'], layout: '左侧概念清单 + 右侧快速检查', visual: '字段标注图或三道快问快答', classroomAction: '先让学生口头回答，再进入新内容', sources: [] }],
    [/导入/, { purpose: '用真实问题制造认知冲突', points: ['没有握手会发生什么', '双方如何确认对方已准备好', '问题引出三步交互'], layout: '大问题居中 + 底部线索卡', visual: '断开连接与建立连接的对比', classroomAction: '停顿让学生先猜测，再揭示本节主题', sources: [] }],
    [/概念卡片/, { purpose: '建立术语、符号与动作之间的共同语言', points: ['SYN：发起连接', 'SYN-ACK：确认并同步', 'ACK：确认收到'], layout: '三张横向概念卡 + 字段提示', visual: 'SYN / SYN-ACK / ACK 颜色编码', classroomAction: '教师逐张讲解，要求学生复述每个动作', sources: [] }],
    [/总览/, { purpose: '先给全局，再逐步放大每一步细节', points: ['客户端发送 SYN', '服务器返回 SYN-ACK', '客户端发送 ACK 完成建立'], layout: '横向三段流程图 · 箭头串联', visual: '客户端 ↔ 服务器时序箭头', classroomAction: '先整体播放一次，再回到每一步拆解', sources: [] }],
    [/第\s*[123]\s*次握手/, { purpose: '拆解单个报文动作与状态变化', points: ['发送方与接收方', '标志位、序列号与确认号', '发送后双方状态如何变化'], layout: '左侧步骤说明 + 右侧报文卡片', visual: '带字段标注的时序箭头', classroomAction: '逐步动画后追问：这一帧证明了什么？', sources: [] }],
    [/序列号|确认号/, { purpose: '把字段关系转化为可观察的计算规则', points: ['初始序列号的变化', '确认号如何回应上一步', '字段之间的对应关系'], layout: '公式/字段卡 + 逐步标注', visual: '序列号与确认号对照箭头', classroomAction: '请学生根据示例预测下一帧确认号', sources: [] }],
    [/状态转换/, { purpose: '把报文动作和连接状态放在同一张图上', points: ['SYN-SENT', 'SYN-RECEIVED', 'ESTABLISHED'], layout: '状态节点 + 触发事件连线', visual: '状态机节点图', classroomAction: '让学生指出每条边对应的报文', sources: [] }],
    [/为什么是三次/, { purpose: '比较方案并形成可解释的结论', points: ['两次握手缺少哪一次确认', '四次握手增加了什么成本', '三次如何平衡可靠性与效率'], layout: '左右对比 + 中央结论', visual: '两次/三次/四次握手对照', classroomAction: '小组先讨论，再用一句话解释结论', sources: [] }],
    [/Wireshark|pcap|抓包/, { purpose: '把协议原理过渡到真实工具与数据', points: ['过滤表达式', '定位 SYN、SYN-ACK、ACK', '按时间顺序还原连接'], layout: '工具截图占主位 + 右侧观察清单', visual: 'Wireshark 列表与高亮帧', classroomAction: '教师演示一次，学生跟随完成定位', sources: [] }],
    [/案例/, { purpose: '用一组真实帧验证前面建立的判断规则', points: ['识别关键帧', '还原握手顺序', '指出异常或缺失环节'], layout: '左侧案例上下文 + 右侧逐帧解读', visual: 'pcap 帧截图与标注', classroomAction: '逐帧提问，不直接给出答案', sources: [] }],
    [/练习/, { purpose: '让学生独立完成从观察到判断的闭环', points: ['分组领取 pcap 任务', '填写握手过程表', '准备小组结论'], layout: '任务说明卡 + 提交检查表', visual: '任务编号、步骤清单、计时提示', classroomAction: '小组操作，教师巡视并记录共性问题', sources: [] }],
    [/汇报|误区/, { purpose: '通过解释与纠错巩固概念', points: ['展示小组判断', '比较不同分析路径', '集中澄清常见误区'], layout: '汇报区 + 误区对照卡', visual: '正确路径与错误路径对比', classroomAction: '先让学生互评，再由教师收束', sources: [] }],
    [/作业|参考答案|解析/, { purpose: '把课堂知识迁移到故障排查情境', points: ['给出异常现象', '要求定位失败原因', '提供答案与推理链'], layout: '问题卡 + 分步解析折叠区', visual: '故障现象与诊断路径', classroomAction: '布置作业，提醒学生按证据写出判断', sources: [] }],
    [/总结|知识框架|结束/, { purpose: '回收本节课的核心知识与下一步行动', points: ['三次握手的完整流程', '报文与状态的对应关系', '抓包分析的固定步骤'], layout: '知识框架 + 退出问题', visual: '全课知识网络图', classroomAction: '让学生用自己的话完成一分钟复述', sources: [] }],
  ];
  const matched = rules.find(([pattern]) => pattern.test(title));
  if (matched) return matched[1];
  return { purpose: `围绕“${title}”建立一个可讲解、可练习的课堂节点`, points: ['核心概念或事实', '课堂中需要观察的证据', '学生需要完成的一个动作'], layout: index % 2 ? '左文右图 · 内容与证据并列' : '标题 + 要点 · 底部课堂提示', visual: '与本页主题对应的示意图或课堂截图', classroomAction: '教师讲解后邀请学生用一句话复述本页结论', sources: [] };
}

export function presentPlanSlides(draft: GoPlanningDraft | null): PlanSlidePresentation[] {
  const raw = draft?.structuredPlan;
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return [];
  const record = raw as Record<string, unknown>;
  const source = Array.isArray(record.slides) ? record.slides : Array.isArray(record.sections) ? record.sections : Array.isArray(record.pages) ? record.pages : [];
  return source.map((item, index) => {
    const value = typeof item === 'string' ? { title: item } : item && typeof item === 'object' ? item as Record<string, unknown> : {};
    const title = text(value.title) || text(value.name) || `第 ${index + 1} 页`;
    const fallback = fallbackForTitle(title, index);
    return {
      key: `${index}-${title}`,
      index: String(index + 1).padStart(2, '0'),
      title,
      purpose: text(value.purpose) || text(value.objective) || text(value.learningGoal) || fallback.purpose,
      points: textList(value.keyPoints || value.points || value.bullets || value.content).slice(0, 5).length ? textList(value.keyPoints || value.points || value.bullets || value.content).slice(0, 5) : fallback.points,
      layout: text(value.layout) || text(value.layoutType) || text(value.semanticLayout) || fallback.layout,
      visual: text(value.visual) || text(value.visuals) || text(value.visualElements) || text(value.media) || fallback.visual,
      classroomAction: text(value.classroomAction) || text(value.teacherAction) || text(value.activity) || fallback.classroomAction,
      sources: textList(value.sources || value.sourceRefs || value.provenance).slice(0, 3) || fallback.sources,
    };
  });
}
