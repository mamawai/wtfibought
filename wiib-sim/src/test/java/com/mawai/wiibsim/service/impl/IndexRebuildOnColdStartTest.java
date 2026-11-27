package com.mawai.wiibsim.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.mawai.wiibcommon.cache.CacheService;
import com.mawai.wiibcommon.entity.FuturesPosition;
import com.mawai.wiibcommon.handover.RoleChanged;
import com.mawai.wiibcommon.handover.Start;
import com.mawai.wiibsim.config.FuturesLeverageBracketRegistry;
import com.mawai.wiibsim.mapper.FuturesPositionMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** 重建索引只在冷启动上任时做：交接上任时旧实例一直维护着索引，待命的实例更不能在它干活时去重建 */
class IndexRebuildOnColdStartTest {

    @BeforeAll
    static void initTableInfoCache() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), FuturesPosition.class);
    }

    private final FuturesPositionMapper positionMapper = mock(FuturesPositionMapper.class);
    private final FuturesPositionIndexServiceImpl index =
            new FuturesPositionIndexServiceImpl(positionMapper, mock(CacheService.class), new FuturesLeverageBracketRegistry());

    @Test
    void 交接上任不重建() {
        index.rebuildOnColdStart(new RoleChanged(null, null, Start.HANDOVER));

        verify(positionMapper, never()).selectList(any());
    }

    @Test
    void 冷启动上任重建() {
        index.rebuildOnColdStart(new RoleChanged(null, null, Start.COLD));

        verify(positionMapper).selectList(any());
    }
}
