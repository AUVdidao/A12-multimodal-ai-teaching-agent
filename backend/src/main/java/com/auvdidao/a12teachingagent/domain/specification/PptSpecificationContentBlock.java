package com.auvdidao.a12teachingagent.domain.specification;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "ppt_specification_content_blocks")
public class PptSpecificationContentBlock {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "slide_id", nullable = false) private PptSpecificationSlide slide;
    @Column(nullable = false, length = 128) private String blockId;
    @Column(nullable = false) private Integer position;
    @Column(nullable = false, length = 16) private String type;
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, columnDefinition = "TEXT") private String content;
    @Column(nullable = false, length = 16) private String sourceType;
    @Column(nullable = false, length = 500) private String sourceReference;
    @Column(nullable = false) private Boolean locked;
    public void setSlide(PptSpecificationSlide slide) { this.slide = slide; }
    public String getBlockId() { return blockId; } public void setBlockId(String v) { blockId = v; }
    public Integer getPosition() { return position; } public void setPosition(Integer v) { position = v; }
    public String getType() { return type; } public void setType(String v) { type = v; }
    public String getContent() { return content; } public void setContent(String v) { content = v; }
    public String getSourceType() { return sourceType; } public void setSourceType(String v) { sourceType = v; }
    public String getSourceReference() { return sourceReference; } public void setSourceReference(String v) { sourceReference = v; }
    public Boolean getLocked() { return locked; } public void setLocked(Boolean v) { locked = v; }
}
