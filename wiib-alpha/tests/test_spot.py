from wiib_alpha.data.spot import spot_symbol

# 现货归档里有的对
SPOT = {"BTCUSDT", "PEPEUSDT", "BONKUSDT", "MOGUSDT", "1000SATSUSDT", "1MBABYDOGEUSDT", "1INCHUSDT", "0GUSDT",
        "LUNAUSDT", "DODOUSDT"}


def test_same_name_first_then_strip_the_multiplier_prefix():
    assert spot_symbol("BTCUSDT", SPOT) == "BTCUSDT"
    # 改过名的同一个币按别名表
    assert spot_symbol("LUNA2USDT", SPOT) == "LUNAUSDT" and spot_symbol("DODOXUSDT", SPOT) == "DODOUSDT"
    assert spot_symbol("1000PEPEUSDT", SPOT) == "PEPEUSDT"
    assert spot_symbol("1000BONKUSDT", SPOT) == "BONKUSDT"
    assert spot_symbol("1000000MOGUSDT", SPOT) == "MOGUSDT"
    # 现货就叫这个名的，同名先命中，不去掉前缀
    assert spot_symbol("1000SATSUSDT", SPOT) == "1000SATSUSDT"
    assert spot_symbol("1MBABYDOGEUSDT", SPOT) == "1MBABYDOGEUSDT"
    # 数字开头的真名
    assert spot_symbol("1INCHUSDT", SPOT) == "1INCHUSDT" and spot_symbol("0GUSDT", SPOT) == "0GUSDT"
    # 没有现货对
    assert spot_symbol("1000XUSDT", SPOT) is None and spot_symbol("ZROUSDT", SPOT) is None
