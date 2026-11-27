package com.mawai.wiibcommon.handover;

/**
 * 身份变了，在开口之前同步发。顺带的事（上任时重建索引、重载名单）监听它
 *
 * @param from 原来的身份，启动时是 null
 * @param to   现在的身份
 * @param how  怎么上任的
 */
public record RoleChanged(Role from, Role to, Start how) {
}
