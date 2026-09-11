package com.auvdidao.a12teachingagent.domain.specification;

import jakarta.persistence.*;

@Entity
@Table(name = "ppt_specification_asset_requirements")
public class PptSpecificationAssetRequirement {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "slide_id", nullable = false) private PptSpecificationSlide slide;
    @Column(nullable = false, length = 128) private String assetId;
    @Column(nullable = false) private Integer position;
    @Column(nullable = false, length = 16) private String assetType;
    @Column(nullable = false, length = 500) private String source;
    @Column(nullable = false, length = 16) private String approvalStatus;
    @Column(nullable = false) private Boolean required;
    @Column(nullable = false, length = 16) private String placementIntent;
    public void setSlide(PptSpecificationSlide slide) { this.slide = slide; }
    public String getAssetId() { return assetId; } public void setAssetId(String v) { assetId = v; }
    public Integer getPosition() { return position; } public void setPosition(Integer v) { position = v; }
    public String getAssetType() { return assetType; } public void setAssetType(String v) { assetType = v; }
    public String getSource() { return source; } public void setSource(String v) { source = v; }
    public String getApprovalStatus() { return approvalStatus; } public void setApprovalStatus(String v) { approvalStatus = v; }
    public Boolean getRequired() { return required; } public void setRequired(Boolean v) { required = v; }
    public String getPlacementIntent() { return placementIntent; } public void setPlacementIntent(String v) { placementIntent = v; }
}


