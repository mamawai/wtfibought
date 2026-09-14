package com.mawai.wiibquant.mapper;

import com.mawai.wiibquant.LocalEnv;
import com.mawai.wiibquant.mapper.WhaleAddressMapper.Known;
import com.mawai.wiibquant.mapper.WhaleAddressMapper.Row;
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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * upsert 的 ON CONFLICT 语义只有 PG 能测：first_seen_at 首见才写、role/parent 只在有值时覆盖、
 * qualified_at 不合格时留旧值、其余字段每天覆盖。用一个假地址进出，不碰真行。需要本地 PG，默认跳过：
 * <pre>
 * WIIB_REAL_RUN=1 mvn -o test -pl wiib-quant -am -DskipTests=false \
 *   -Dtest=WhaleAddressMapperRealRunTest -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 */
@EnabledIfEnvironmentVariable(named = "WIIB_REAL_RUN", matches = "1")
class WhaleAddressMapperRealRunTest {

    private static final String ADDR = "0x000000000000000000000000000000000000test";

    @Test
    void upsert_首见写first_seen_at_role与qualified_at不被null覆盖() throws Exception {
        PooledDataSource ds = new PooledDataSource("org.postgresql.Driver",
                System.getProperty("whale.dbUrl", "jdbc:postgresql://localhost:5432/wiib"),
                System.getProperty("whale.dbUser", "mawai"), LocalEnv.dbPassword());
        Configuration cfg = new Configuration(new Environment("local", new JdbcTransactionFactory(), ds));
        cfg.addMapper(WhaleAddressMapper.class);
        SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(cfg);
        try (SqlSession session = factory.openSession(true)) {
            WhaleAddressMapper mapper = session.getMapper(WhaleAddressMapper.class);
            try (Statement st = session.getConnection().createStatement()) {
                st.execute("DELETE FROM whale_address WHERE address = '" + ADDR + "'");
            }

            // 第一天：合格进池
            Row day1 = row(true, "user", "2000000", null, 1_000L, 1_000L);
            mapper.upsert(day1);
            assertThat(read(session, "first_seen_at")).isEqualTo(1_000L);
            assertThat(read(session, "qualified_at")).isEqualTo(1_000L);
            assertThat(read(session, "role")).isEqualTo("user");

            // 第二天：缩水被拦，role 没再查（null）、qualified_at 给 null、first_seen_at 给新值
            mapper.upsert(row(false, null, "500000", "SMALL", 2_000L, null));
            assertThat(read(session, "first_seen_at")).as("首见才写").isEqualTo(1_000L);
            assertThat(read(session, "qualified_at")).as("不合格留旧值").isEqualTo(1_000L);
            assertThat(read(session, "role")).as("null 不覆盖").isEqualTo("user");
            assertThat(read(session, "in_pool")).isEqualTo(false);
            assertThat(read(session, "reject_reason")).isEqualTo("SMALL");
            assertThat(((BigDecimal) read(session, "account_value"))).isEqualByComparingTo("500000");

            Known k = mapper.selectKnown().stream().filter(x -> ADDR.equals(x.getAddress())).findFirst().orElseThrow();
            assertThat(k.getRole()).isEqualTo("user");
            assertThat(k.getParentAddress()).isNull();

            try (Statement st = session.getConnection().createStatement()) {
                st.execute("DELETE FROM whale_address WHERE address = '" + ADDR + "'");
            }
        } finally {
            ds.forceCloseAll();
        }
    }

    private static Row row(boolean inPool, String role, String accountValue, String reject, long firstSeen, Long qualified) {
        Row r = new Row();
        r.setAddress(ADDR);
        r.setRole(role);
        r.setInPool(inPool);
        r.setAccountValue(new BigDecimal(accountValue));
        r.setPositionCount(1);
        r.setTrackedMaxPosition(BigDecimal.ZERO);
        r.setRejectReason(reject);
        r.setFirstSeenAt(firstSeen);
        r.setQualifiedAt(qualified);
        return r;
    }

    private static Object read(SqlSession session, String column) throws Exception {
        try (PreparedStatement ps = session.getConnection().prepareStatement(
                "SELECT " + column + " FROM whale_address WHERE address = ?")) {
            ps.setString(1, ADDR);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                return rs.getObject(1);
            }
        }
    }
}
