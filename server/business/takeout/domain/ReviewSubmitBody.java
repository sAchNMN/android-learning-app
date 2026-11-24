package com.ruoyi.takeout.domain;

import java.util.List;

/**
 * 提交评价。
 *
 * 只有 [rating] 必填，其余维度、标签、文字都可选。
 * 不接收 userId / shopId —— 两者都从订单里取，避免给别人的店刷评。
 */
public class ReviewSubmitBody {

    /** 总评 1-5，必填。 */
    private Integer rating;
    private Integer tasteRating;
    private Integer packRating;
    private Integer deliveryRating;
    private List<String> tags;
    private String content;
    private Boolean anonymous;

    public Integer getRating() { return rating; }
    public void setRating(Integer rating) { this.rating = rating; }

    public Integer getTasteRating() { return tasteRating; }
    public void setTasteRating(Integer tasteRating) { this.tasteRating = tasteRating; }

    public Integer getPackRating() { return packRating; }
    public void setPackRating(Integer packRating) { this.packRating = packRating; }

    public Integer getDeliveryRating() { return deliveryRating; }
    public void setDeliveryRating(Integer deliveryRating) { this.deliveryRating = deliveryRating; }

    public List<String> getTags() { return tags; }
    public void setTags(List<String> tags) { this.tags = tags; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public Boolean getAnonymous() { return anonymous; }
    public void setAnonymous(Boolean anonymous) { this.anonymous = anonymous; }
}
