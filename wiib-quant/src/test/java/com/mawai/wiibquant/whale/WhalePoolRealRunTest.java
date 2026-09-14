package com.mawai.wiibquant.whale;

import com.mawai.wiibquant.LocalEnv;
import com.mawai.wiibquant.external.hyperliquid.HyperliquidClient;
import com.mawai.wiibquant.mapper.WhaleAddressMapper;
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

import java.math.BigDecimal;

/**
 * 真跑一轮地址池认证落本地库（docs/hyperliquid-whale.md §4）：默认按生产口径，排行榜候选 ≥30 万约 6400 个实体；
 * 认证桶临时给 1000/分钟（本地 agent 轮询另占十来点），约三小时；想快就抬候选门槛。跑完用 psql 人工看池子成分。
 * <p>
 * 不起 Spring：MyBatis 手工建 session 挂 WhaleAddressMapper，表要先按 init.sql 第 34 章建好。默认跳过。跑法（项目根）：
 * <pre>
 * WIIB_REAL_RUN=1 mvn -o test -pl wiib-quant -am -DskipTests=false \
 *   -Dtest=WhalePoolRealRunTest -Dsurefire.failIfNoSpecifiedTests=false \
 *   [-Dwhale.minLeaderboardValue=300000] [-Dwhale.poolWeight=1000]
 * </pre>
 */
@EnabledIfEnvironmentVariable(named = "WIIB_REAL_RUN", matches = "1")
class WhalePoolRealRunTest {

    private static final Logger log = LoggerFactory.getLogger(WhalePoolRealRunTest.class);

    @Test
    void 真跑一轮_落本地库() {
        WhaleProperties props = new WhaleProperties();
        props.getPool().setMinLeaderboardValue(new BigDecimal(System.getProperty("whale.minLeaderboardValue", "300000")));
        props.getPool().setWeightPerMinute(Integer.getInteger("whale.poolWeight", 1000));

        PooledDataSource ds = new PooledDataSource("org.postgresql.Driver",
                System.getProperty("whale.dbUrl", "jdbc:postgresql://localhost:5432/wiib"),
                System.getProperty("whale.dbUser", "mawai"), LocalEnv.dbPassword());
        Configuration cfg = new Configuration(new Environment("local", new JdbcTransactionFactory(), ds));
        cfg.addMapper(WhaleAddressMapper.class);
        SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(cfg);

        try (SqlSession session = factory.openSession(true)) {
            WhaleAddressMapper mapper = session.getMapper(WhaleAddressMapper.class);
            log.info("[RealRun] 跑前 whale_address {} 行，候选门槛 {}，认证桶 {}/分钟", mapper.count(),
                    props.getPool().getMinLeaderboardValue(), props.getPool().getWeightPerMinute());

            new WhalePoolTask(new HyperliquidClient(props), mapper, props).refresh();

            log.info("[RealRun] 跑后 whale_address {} 行", mapper.count());
        } finally {
            ds.forceCloseAll();
        }
    }
}
