package com.ruoyi.music.domain;

import java.util.ArrayList;
import java.util.List;

/** 一次喜欢操作的结果：本地是否落库 + 各平台的同步回执。 */
public class LikeResult {

    private boolean liked;

    /** 后端库是否写入成功。false 时客户端应提示「本次只在本地生效」。 */
    private boolean saved;

    private List<SyncResult> syncs = new ArrayList<>();

    public boolean isLiked() {
        return liked;
    }

    public void setLiked(boolean liked) {
        this.liked = liked;
    }

    public boolean isSaved() {
        return saved;
    }

    public void setSaved(boolean saved) {
        this.saved = saved;
    }

    public List<SyncResult> getSyncs() {
        return syncs;
    }

    public void setSyncs(List<SyncResult> syncs) {
        this.syncs = syncs;
    }
}
