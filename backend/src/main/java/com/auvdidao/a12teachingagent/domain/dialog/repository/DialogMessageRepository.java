package com.auvdidao.a12teachingagent.domain.dialog.repository;

import com.auvdidao.a12teachingagent.domain.dialog.DialogMessage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DialogMessageRepository extends JpaRepository<DialogMessage, Long> {

    List<DialogMessage> findByProjectIdOrderByCreatedAtAsc(Long projectId);
}
