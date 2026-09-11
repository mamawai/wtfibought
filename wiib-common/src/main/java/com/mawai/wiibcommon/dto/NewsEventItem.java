package com.mawai.wiibcommon.dto;

import lombok.Data;

/**
 * 快讯条目（首页快讯卡数据源）：news_event 表的查询投影，MyBatis 按列别名注入。
 */
@Data
public class NewsEventItem {

    private Long id;
    private String title;
    /** 纯文本正文（入库时已脱 HTML） */
    private String content;
    /** 英文译文；null=没译成，英文界面不展示这条 */
    private String titleEn;
    private String contentEn;
    /** 原始消息源链接 */
    private String url;
    /** 发稿时刻 epoch 毫秒 */
    private Long publishedAt;
}
