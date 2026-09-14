package com.mawai.wiibquant.mapper;

import com.mawai.wiibquant.LocalEnv;
import com.mawai.wiibquant.mapper.WhaleSnapshotMapper.Row;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.math.BigDecimal;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * selectLatest 的 19 列别名逐个对上 Row 字段、取的是最近一槽。用一个假币两槽进出，不碰真行。需要本地 PG，默认跳过：
 * <pre>
 * WIIB_REAL_RUN=1 mvn -o test -pl wiib-quant -am -DskipTests=false \
 *   -Dtest=WhaleSnapshotMapperRealRunTest -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 */
@EnabledIfEnvironmentVariable(named = "WIIB_REAL_RUN", matches = "1")
class WhaleSnapshotMapperRealRunTest {

    private static final String COIN = "TESTCOIN";

    @Test
    void selectLatest_取最近一槽_全列对上() throws Exception {
        PooledDataSource ds = new PooledDataSource("org.postgresql.Driver",
                System.getProperty("whale.dbUrl", "jdbc:postgresql://localhost:5432/wiib"),
                System.getProperty("whale.dbUser", "mawai"), LocalEnv.dbPassword());
        Configuration cfg = new Configuration(new Environment("local", new JdbcTransactionFactory(), ds));
        cfg.addMapper(WhaleSnapshotMapper.class);
        SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(cfg);
        try (SqlSession session = factory.openSession(true)) {
            WhaleSnapshotMapper mapper = session.getMapper(WhaleSnapshotMapper.class);
            clean(session);
            assertThat(mapper.selectLatest(COIN)).isNull();

            mapper.insert(row(1_000L, "1"));
            mapper.insert(row(2_000L, "2"));
            Row r = mapper.selectLatest(COIN);

            assertThat(r.getObservedAt()).isEqualTo(2_000L);
            assertThat(r.getCoin()).isEqualTo(COIN);
            assertThat(r.getPrice()).isEqualByComparingTo("80100.5");
            assertThat(r.getHlOpenInterest()).isEqualByComparingTo("1000000000.25");
            assertThat(r.getPoolSize()).isEqualTo(44);
            assertThat(r.getLongCount()).isEqualTo(2);
            assertThat(r.getLongNotional()).isEqualByComparingTo("29000000.2");
            assertThat(r.getLongWavgEntry()).isEqualByComparingTo("75620.2");
            assertThat(r.getLongMedianEntry()).isEqualByComparingTo("77390.2");
            assertThat(r.getLongTop1Share()).isEqualByComparingTo("0.312");
            assertThat(r.getLongUpnl()).isEqualByComparingTo("1470000.2");
            assertThat(r.getShortCount()).isEqualTo(0);
            assertThat(r.getShortNotional()).isEqualByComparingTo("0");
            assertThat(r.getShortWavgEntry()).isNull();
            assertThat(r.getShortMedianEntry()).isNull();
            assertThat(r.getShortTop1Share()).isNull();
            assertThat(r.getShortUpnl()).isNull();
            assertThat(r.getEntryBucketsJson()).isEqualTo("{\"width\":200,\"buckets\":[[79800,100000,0]]}");
            assertThat(r.getLiqBucketsJson()).isEqualTo("{\"width\":400,\"buckets\":[[76000,200000,0]]}");

            clean(session);
        } finally {
            ds.forceCloseAll();
        }
    }

    /** 第 tag 槽：数值末位带 tag，保证取到的是第二槽 */
    private static Row row(long observedAt, String tag) {
        Row r = new Row();
        r.setObservedAt(observedAt);
        r.setCoin(COIN);
        r.setPrice(new BigDecimal("80100.5"));
        r.setHlOpenInterest(new BigDecimal("1000000000.25"));
        r.setPoolSize(44);
        r.setLongCount(2);
        r.setLongNotional(new BigDecimal("29000000." + tag));
        r.setLongWavgEntry(new BigDecimal("75620." + tag));
        r.setLongMedianEntry(new BigDecimal("77390." + tag));
        r.setLongTop1Share(new BigDecimal("0.31" + tag));
        r.setLongUpnl(new BigDecimal("1470000." + tag));
        r.setShortCount(0);
        r.setShortNotional(BigDecimal.ZERO);
        r.setEntryBucketsJson("{\"width\":200,\"buckets\":[[79800,100000,0]]}");
        r.setLiqBucketsJson("{\"width\":400,\"buckets\":[[76000,200000,0]]}");
        return r;
    }

    private static void clean(SqlSession session) throws Exception {
        try (Statement st = session.getConnection().createStatement()) {
            st.execute("DELETE FROM whale_snapshot WHERE coin = '" + COIN + "'");
        }
    }
}
