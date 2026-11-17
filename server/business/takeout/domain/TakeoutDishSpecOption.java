package com.ruoyi.takeout.domain;

import java.math.BigDecimal;

/** 菜品规格选项。priceDelta 是加价，不是最终价。 */
public class TakeoutDishSpecOption {

    private Long optionId;
    private Long specId;
    private String optionName;
    private BigDecimal priceDelta;
    private Boolean isDefault;

    public Long getOptionId() { return optionId; }
    public void setOptionId(Long optionId) { this.optionId = optionId; }

    public Long getSpecId() { return specId; }
    public void setSpecId(Long specId) { this.specId = specId; }

    public String getOptionName() { return optionName; }
    public void setOptionName(String optionName) { this.optionName = optionName; }

    public BigDecimal getPriceDelta() { return priceDelta; }
    public void setPriceDelta(BigDecimal priceDelta) { this.priceDelta = priceDelta; }

    public Boolean getIsDefault() { return isDefault; }
    public void setIsDefault(Boolean isDefault) { this.isDefault = isDefault; }
}
