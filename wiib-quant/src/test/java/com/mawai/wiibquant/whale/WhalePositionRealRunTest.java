package com.mawai.wiibquant.whale;

import com.mawai.wiibquant.LocalEnv;
import com.mawai.wiibquant.external.hyperliquid.HyperliquidClient;
import com.mawai.wiibquant.mapper.WhaleAddressMapper;
import com.mawai.wiibquant.mapper.WhalePositionMapper;
import com.mawai.wiibquant.mapper.WhaleSnapshotMapper;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 真跑一轮持仓轮询落本地库（docs/hyperliquid-whale.md §12 第 3 步）：池子来自 whale_address 里 in_pool 的行
 * （第 2 步真跑留下的），跑完用 psql 看每币快照与覆盖率。默认跳过。跑法（项目根）：
 * <pre>
 * WIIB_REAL_RUN=1 mvn -o test -pl wiib-quant -am -DskipTests=false \
 *   -Dtest=WhalePositionRealRunTest -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 */
@EnabledIfEnvironmentVariable(named = "WIIB_REAL_RUN", matches = "1")
class WhalePositionRealRunTest {

    private static final Logger log = LoggerFactory.getLogger(WhalePositionRealRunTest.class);

    @Test
    void 真跑一轮_落本地库() {
        WhaleProperties props = new WhaleProperties();
        PooledDataSource ds = new PooledDataSource("org.postgresql.Driver",
                System.getProperty("whale.dbUrl", "jdbc:postgresql://localhost:5432/wiib"),
                System.getProperty("whale.dbUser", "mawai"), LocalEnv.dbPassword());
        Configuration cfg = new Configuration(new Environment("local", new JdbcTransactionFactory(), ds));
        cfg.addMapper(WhaleAddressMapper.class);
        cfg.addMapper(WhaleSnapshotMapper.class);
        cfg.addMapper(WhalePositionMapper.class);
        SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(cfg);

        try (SqlSession session = factory.openSession(true)) {
            WhaleAddressMapper addresses = session.getMapper(WhaleAddressMapper.class);
            log.info("[RealRun] 池内地址 {} 个", addresses.selectPoolAddresses().size());

            new WhalePositionTask(new HyperliquidClient(props), addresses,
                    session.getMapper(WhaleSnapshotMapper.class), session.getMapper(WhalePositionMapper.class), props).run();
        } finally {
            ds.forceCloseAll();
        }
    }
}
