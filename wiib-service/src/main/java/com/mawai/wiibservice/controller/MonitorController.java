package com.mawai.wiibservice.controller;

import com.mawai.wiibcommon.util.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.management.*;
import java.util.*;

@RestController
@RequestMapping("/monitor")
@EnableScheduling
@Tag(name = "监控")
public class MonitorController {

    private final SimpMessagingTemplate ws;

    private static final MemoryMXBean MEM_MX = ManagementFactory.getMemoryMXBean();
    private static final ThreadMXBean THREAD_MX = ManagementFactory.getThreadMXBean();
    private static final com.sun.management.OperatingSystemMXBean OS_MX =
            (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
    private static final RuntimeMXBean RUNTIME_MX = ManagementFactory.getRuntimeMXBean();
    private static final ClassLoadingMXBean CLASS_MX = ManagementFactory.getClassLoadingMXBean();

    public MonitorController(SimpMessagingTemplate ws) {
        this.ws = ws;
    }

    @Scheduled(fixedRate = 5000)
    public void pushMonitor() {
        ws.convertAndSend("/topic/monitor", collectLite());
    }

    @GetMapping("/detail")
    @Operation(summary = "详细监控（含classLoading和内存池）")
    public Result<Map<String, Object>> detail() {
        return Result.ok(collect());
    }

    private static Map<String, Object> collectLite() {
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
