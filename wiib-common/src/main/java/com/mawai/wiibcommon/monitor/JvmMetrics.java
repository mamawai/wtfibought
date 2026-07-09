package com.mawai.wiibcommon.monitor;

import java.lang.management.ClassLoadingMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.RuntimeMXBean;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JVM 运行指标采集（共享层）：读**本进程**的 MXBean，采集逻辑与进程无关，sim/feed/quant 三家共用。
 * <p>{@link #collectLite()} 供实时推送（堆/堆外/线程/GC/CPU/运行时长）；{@link #collect()} 加内存池+类加载供详情。
 * 全部 static——无状态、无需注入。
 */
public final class JvmMetrics {

    private static final MemoryMXBean MEM_MX = ManagementFactory.getMemoryMXBean();
    private static final ThreadMXBean THREAD_MX = ManagementFactory.getThreadMXBean();
    private static final com.sun.management.OperatingSystemMXBean OS_MX =
            (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
    private static final RuntimeMXBean RUNTIME_MX = ManagementFactory.getRuntimeMXBean();
    private static final ClassLoadingMXBean CLASS_MX = ManagementFactory.getClassLoadingMXBean();

    private JvmMetrics() {}

    /** 轻量快照：实时推送用。 */
    public static Map<String, Object> collectLite() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("heap", usageMap(MEM_MX.getHeapMemoryUsage()));
        data.put("nonHeap", usageMap(MEM_MX.getNonHeapMemoryUsage()));

        Map<String, Object> thread = new LinkedHashMap<>();
        thread.put("current", THREAD_MX.getThreadCount());
        thread.put("peak", THREAD_MX.getPeakThreadCount());
        thread.put("daemon", THREAD_MX.getDaemonThreadCount());
        data.put("thread", thread);

        List<Map<String, Object>> gcList = new ArrayList<>();
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", gc.getName());
            item.put("count", gc.getCollectionCount());
            item.put("timeMs", gc.getCollectionTime());
            gcList.add(item);
        }
        data.put("gc", gcList);

        double cpuLoad = OS_MX.getProcessCpuLoad();
        data.put("cpuPct", cpuLoad >= 0 ? Math.round(cpuLoad * 100) : -1);
        data.put("uptimeSec", RUNTIME_MX.getUptime() / 1000);

        return data;
    }

    /** 详细快照：collectLite + 内存池 + 类加载。 */
    public static Map<String, Object> collect() {
        Map<String, Object> data = collectLite();
        List<Map<String, Object>> pools = new ArrayList<>();
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", pool.getName());
            item.put("type", pool.getType().name());
            item.put("usage", usageMap(pool.getUsage()));
            pools.add(item);
        }
        data.put("pools", pools);

        Map<String, Object> classLoading = new LinkedHashMap<>();
        classLoading.put("loaded", CLASS_MX.getLoadedClassCount());
        classLoading.put("totalLoaded", CLASS_MX.getTotalLoadedClassCount());
        classLoading.put("unloaded", CLASS_MX.getUnloadedClassCount());
        data.put("classLoading", classLoading);

        return data;
    }

    private static Map<String, Object> usageMap(MemoryUsage usage) {
        if (usage == null) return Collections.emptyMap();
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("used", usage.getUsed() / 1024 / 1024);
        map.put("committed", usage.getCommitted() / 1024 / 1024);
        long max = usage.getMax();
        map.put("max", max < 0 ? -1 : max / 1024 / 1024);
        return map;
    }
}
