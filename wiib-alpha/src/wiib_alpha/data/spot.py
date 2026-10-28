"""永续符号对现货符号：改过名的按别名表，其余同名优先，没有就去掉 1000 / 1M 这类合约倍数前缀再找。

三列都是 USDT 额和笔数，不受倍数影响。1000SATSUSDT、1MBABYDOGEUSDT 现货就叫这个名，同名先命中；
1INCHUSDT、0GUSDT 是真名，也走同名。对不上的是没有现货对，面板上现货三列留空。
"""

from __future__ import annotations

from collections.abc import Container

# 永续和现货不同名的同一个币（10-28 拿两边价格核过）
_ALIASES = {"LUNA2USDT": "LUNAUSDT", "DODOXUSDT": "DODOUSDT"}
# 合约倍数前缀，长的先试
_PREFIXES = ("1000000", "1000", "1M")


def spot_symbol(perp: str, spot: Container[str]) -> str | None:
    name = _ALIASES.get(perp, perp)
    if name in spot:
        return name
    for prefix in _PREFIXES:
        if perp.startswith(prefix) and perp[len(prefix):] in spot:
            return perp[len(prefix):]
    return None
