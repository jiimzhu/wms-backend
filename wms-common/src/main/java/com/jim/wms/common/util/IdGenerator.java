package com.jim.wms.common.util;

import cn.hutool.core.util.IdUtil;

/**
 * 业务单号 / 事件ID 生成（本地雪花算法，不依赖 Redis）
 */
public final class IdGenerator {

    private static final cn.hutool.core.lang.Snowflake SNOWFLAKE = IdUtil.getSnowflake(1, 1);

    private IdGenerator() {
    }

    public static long nextId() {
        return SNOWFLAKE.nextId();
    }

    public static String nextIdStr() {
        return String.valueOf(nextId());
    }

    /**
     * 带前缀的单号，如 PO20261117000001
     */
    public static String nextNo(String prefix) {
        return prefix + System.currentTimeMillis();
    }
}
