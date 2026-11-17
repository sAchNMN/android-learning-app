package com.ruoyi.takeout.domain;

/**
 * 店铺评价列表项。
 *
 * [nickname] 由 SQL 直接按 is_anonymous 输出「匿名用户」，
 * Java 侧不再判断，避免列表和详情两处逻辑走偏。
 */
public class ReviewRow {

    private Long reviewId;
    private String nickname;
    private Integer rating;
    private String tags;
    private String content;
    private String reply;
    private String createTime;

    public Long getReviewId() { return reviewId; }
    public void setReviewId(Long reviewId) { this.reviewId = reviewId; }

    public String getNickname() { return nickname; }
    public void setNickname(String nickname) { this.nickname = nickname; }

    public Integer getRating() { return rating; }
    public void setRating(Integer rating) { this.rating = rating; }

    public String getTags() { return tags; }
    public void setTags(String tags) { this.tags = tags; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getReply() { return reply; }
    public void setReply(String reply) { this.reply = reply; }

    public String getCreateTime() { return createTime; }
    public void setCreateTime(String createTime) { this.createTime = createTime; }
}
