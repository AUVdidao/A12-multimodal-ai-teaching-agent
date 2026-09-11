package com.auvdidao.a12teachingagent.domain.specification;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "ppt_specification_slides")
public class PptSpecificationSlide {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "version_id", nullable = false) private PptSpecificationVersion version;
    @Column(nullable = false, length = 128) private String slideId;
    @Column(nullable = false) private Integer position;
    @Column(nullable = false) private Integer pageNumber;
    @Column(nullable = false, length = 500) private String title;
    @Column(nullable = false, length = 2000) private String teachingGoal;
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, columnDefinition = "TEXT") private String semanticLayoutJson;
    @Column(length = 2000) private String notes;
    @OneToMany(mappedBy = "slide", cascade = CascadeType.ALL, orphanRemoval = true) @OrderBy("position asc") private List<PptSpecificationContentBlock> contentBlocks = new ArrayList<>();
    @OneToMany(mappedBy = "slide", cascade = CascadeType.ALL, orphanRemoval = true) @OrderBy("position asc") private List<PptSpecificationAssetRequirement> assetRequirements = new ArrayList<>();
    @OneToMany(mappedBy = "slide", cascade = CascadeType.ALL, orphanRemoval = true) @OrderBy("position asc") private List<PptSpecificationProvenance> provenance = new ArrayList<>();
    public void setVersion(PptSpecificationVersion version) { this.version = version; }
    public void addContentBlock(PptSpecificationContentBlock value) { value.setSlide(this); contentBlocks.add(value); }
    public void addAssetRequirement(PptSpecificationAssetRequirement value) { value.setSlide(this); assetRequirements.add(value); }
    public void addProvenance(PptSpecificationProvenance value) { value.setSlide(this); provenance.add(value); }
    public Long getId() { return id; }
    public String getSlideId() { return slideId; }
    public void setSlideId(String slideId) { this.slideId = slideId; }
    public Integer getPosition() { return position; }
    public void setPosition(Integer position) { this.position = position; }
    public Integer getPageNumber() { return pageNumber; }
    public void setPageNumber(Integer pageNumber) { this.pageNumber = pageNumber; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getTeachingGoal() { return teachingGoal; }
    public void setTeachingGoal(String teachingGoal) { this.teachingGoal = teachingGoal; }
    public String getSemanticLayoutJson() { return semanticLayoutJson; }
    public void setSemanticLayoutJson(String semanticLayoutJson) { this.semanticLayoutJson = semanticLayoutJson; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public List<PptSpecificationContentBlock> getContentBlocks() { return contentBlocks; }
    public List<PptSpecificationAssetRequirement> getAssetRequirements() { return assetRequirements; }
    public List<PptSpecificationProvenance> getProvenance() { return provenance; }
}
