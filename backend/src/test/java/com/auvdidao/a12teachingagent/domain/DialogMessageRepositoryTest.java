package com.auvdidao.a12teachingagent.domain;

import com.auvdidao.a12teachingagent.domain.common.DialogRole;
import com.auvdidao.a12teachingagent.domain.dialog.DialogMessage;
import com.auvdidao.a12teachingagent.domain.dialog.repository.DialogMessageRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class DialogMessageRepositoryTest {

    @Autowired
    private DialogMessageRepository dialogMessageRepository;

    @Test
    void findsDialogMessagesByProjectIdInCreatedOrder() {
        Long projectId = 1001L;

        DialogMessage second = new DialogMessage();
        second.setProjectId(projectId);
        second.setSessionId("project-1001-clarification");
        second.setRole(DialogRole.ASSISTANT);
        second.setContent("请补充课时长度和课堂互动偏好。");
        second.setRoundNo(1);
        second.setCreatedAt(LocalDateTime.of(2026, 7, 8, 10, 5));

        DialogMessage first = new DialogMessage();
        first.setProjectId(projectId);
        first.setSessionId("project-1001-clarification");
        first.setRole(DialogRole.TEACHER);
        first.setContent("我要生成一节水循环主题的科学课。");
        first.setRoundNo(1);
        first.setCreatedAt(LocalDateTime.of(2026, 7, 8, 10, 0));

        DialogMessage otherProject = new DialogMessage();
        otherProject.setProjectId(2002L);
        otherProject.setSessionId("project-2002-clarification");
        otherProject.setRole(DialogRole.TEACHER);
        otherProject.setContent("其他项目消息。");
        otherProject.setRoundNo(1);
        otherProject.setCreatedAt(LocalDateTime.of(2026, 7, 8, 9, 0));

        dialogMessageRepository.saveAll(List.of(second, first, otherProject));

        List<DialogMessage> messages = dialogMessageRepository.findByProjectIdOrderByCreatedAtAscIdAsc(projectId);

        assertThat(messages).hasSize(2);
        assertThat(messages).extracting(DialogMessage::getContent)
                .containsExactly(
                        "我要生成一节水循环主题的科学课。",
                        "请补充课时长度和课堂互动偏好。"
                );
    }
}
