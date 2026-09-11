package com.auvdidao.a12teachingagent.domain.specification;

import jakarta.persistence.*;

@Entity
@Table(name = "ppt_specification_provenance")
public class PptSpecificationProvenance {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "slide_id", nullable = false) private PptSpecificationSlide slide;
    @Column(nullable = false) private Integer position;
    @Column(nullable = false, length = 16) private String sourceType;
    @Column(nullable = false, length = 500) private String sourceReference;
    public void setSlide(PptSpecificationSlide slide) { this.slide = slide; }
    public Integer getPosition() { return position; } public void setPosition(Integer v) { position = v; }
    public String getSourceType() { return sourceType; } public void setSourceType(String v) { sourceType = v; }
    public String getSourceReference() { return sourceReference; } public void setSourceReference(String v) { sourceReference = v; }
}


