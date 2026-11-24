package com.ruoyi.takeout.domain;

import java.util.List;

/**
 * 菜品规格组。
 *
 * required / multi 在库里是 char(1)（'0' 是 / '1' 否），
 * SQL 里已经转成布尔再映射，别在 Java 里再判字符串。
 */
public class TakeoutDishSpec {

    private Long specId;
    private Long dishId;
    private String specName;
    /** true 表示该组必须选，下单时服务端会校验。 */
    private Boolean required;
    /** true 表示可多选。 */
    private Boolean multi;
    private Integer sortNo;
    private List<TakeoutDishSpecOption> options;

    public Long getSpecId() { return specId; }
    public void setSpecId(Long specId) { this.specId = specId; }

    public Long getDishId() { return dishId; }
    public void setDishId(Long dishId) { this.dishId = dishId; }

    public String getSpecName() { return specName; }
    public void setSpecName(String specName) { this.specName = specName; }

    public Boolean getRequired() { return required; }
    public void setRequired(Boolean required) { this.required = required; }

    public Boolean getMulti() { return multi; }
    public void setMulti(Boolean multi) { this.multi = multi; }

    public Integer getSortNo() { return sortNo; }
    public void setSortNo(Integer sortNo) { this.sortNo = sortNo; }

    public List<TakeoutDishSpecOption> getOptions() { return options; }
    public void setOptions(List<TakeoutDishSpecOption> options) { this.options = options; }
}
