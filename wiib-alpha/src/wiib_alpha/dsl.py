"""因子表达式 DSL。

写法就是 Python 表达式：$列 引用 panel 的列，函数名查 ops.OPERATORS，只放行 + - * / 与负号，其它一律拒。
解析出的树能求值、算复杂度、抹掉窗口和常量得规范形（查换皮用）、做前视自检。
求值一币一算；截面算子（CsRank 等）要同一时刻全部在池币的值，由调用方先跨币算好按节点传进来（见 data/pool.py）。
"""

from __future__ import annotations

import ast
import re
from dataclasses import dataclass
from typing import Iterable, Mapping, Sequence

import numpy as np
import pandas as pd

from . import ops

MAX_WINDOW = 2016  # 三周
MAX_TEXT = 500
# 复杂度上限在解析时就卡住，评估之前拦下堆参数的公式；G7 只做报告。
# 都按不同的数：不同的子式、不同的窗口值、不同的常量值、不同的截面算子（每个都要全池多读一遍），同一个东西重复写不多算
COMPLEXITY = {"nodes": 40, "windows": 6, "constants": 6, "cross": 2}
_COL = re.compile(r"\$([A-Za-z_][A-Za-z0-9_]*)")
_MARK = "__col__"
_BIN = {ast.Add: "+", ast.Sub: "-", ast.Mult: "*", ast.Div: "/"}


class DslError(ValueError):
    """表达式不合法；一句话说清哪里错，喂回给模型"""


@dataclass(frozen=True)
class Col:
    name: str


@dataclass(frozen=True)
class Const:
    value: float


@dataclass(frozen=True)
class Neg:
    x: "Node"


@dataclass(frozen=True)
class Bin:
    op: str
    left: "Node"
    right: "Node"


@dataclass(frozen=True)
class Call:
    name: str
    args: tuple["Node", ...]
    # rolling (w,)  quantile (w, q)  pair (w,)  其它 ()
    params: tuple[float, ...]


Node = Col | Const | Neg | Bin | Call
# 截面节点 → 这个币在池时刻的值（index 是收盘时刻）
Cross = Mapping[Call, pd.Series]


class Expr:
    def __init__(self, root: Node):
        self.root = root

    def __str__(self) -> str:
        return _render(self.root, masked=False)

    def canonical(self) -> str:
        """窗口抹成 w、常量抹成 c 之后的形状；两条表达式规范形相同就是换个窗口的翻版"""
        return _render(self.root, masked=True)

    def columns(self) -> set[str]:
        return {n.name for n in _walk(self.root) if isinstance(n, Col)}

    def complexity(self) -> dict[str, int]:
        """按不同的数：同一个子式、窗口值、常量值、截面算子重复写只算一个"""
        nodes = set(_walk(self.root))
        calls = [n for n in nodes if isinstance(n, Call)]
        windows = {n.params[0] for n in calls if n.params}
        constants = {n.value for n in nodes if isinstance(n, Const)} | {n.params[1] for n in calls if len(n.params) > 1}
        cross = sum(1 for n in calls if _is_cross(n))
        return {"nodes": len(nodes), "windows": len(windows), "constants": len(constants), "cross": cross}

    def cross_nodes(self) -> list[Call]:
        """截面节点，里层的在前、相同的只留一个；求值前要按这个顺序跨币算好"""
        out: list[Call] = []
        for n in _post_order(self.root):
            if _is_cross(n) and n not in out:
                out.append(n)
        return out

    def evaluate(self, df: pd.DataFrame, cross: Cross | None = None) -> pd.Series:
        """一币一算；截面节点的值从 cross 里取，不在池的时刻是 NaN。除零之类的 inf 在每个节点都变 NaN"""
        return _eval(self.root, df, cross or {})


def parse(text: str, columns: Iterable[str]) -> Expr:
    allowed = set(columns)
    if _MARK in text:
        raise DslError("列要写成 $名字")
    source = _COL.sub(lambda m: _MARK + m.group(1), text.strip())
    try:
        tree = ast.parse(source, mode="eval")
    except (SyntaxError, ValueError) as exc:
        raise DslError(f"语法错误：{exc.msg if isinstance(exc, SyntaxError) else exc}") from None
    root = _convert(tree.body, allowed)
    if not any(isinstance(n, Col) for n in _walk(root)):
        raise DslError("表达式得至少引用一个列")
    expr = Expr(root)
    # 字数按重写后的全括号写法量：存下来再读回来过不过一样
    if len(str(expr)) > MAX_TEXT:
        raise DslError(f"表达式超过 {MAX_TEXT} 字")
    cx = expr.complexity()
    for key, label in (("nodes", "节点"), ("windows", "窗口"), ("constants", "常量"), ("cross", "截面算子")):
        if cx[key] > COMPLEXITY[key]:
            raise DslError(f"表达式太复杂：{label} {cx[key]} 个，上限 {COMPLEXITY[key]}")
    return expr


def lookahead_free(expr: Expr, df: pd.DataFrame, cuts: Sequence[int], cross: Cross | None = None) -> bool:
    """截到第 c 行重算，前 c 行必须与全量算出来的一样；不一样就是偷看了未来。
    截面算子本身只看同一时刻，它的输入各自也截断查一遍：输入不看未来，整条就不看未来"""
    for sub in [expr, *(Expr(n.args[0]) for n in expr.cross_nodes())]:
        full = sub.evaluate(df, cross).to_numpy(dtype="float64")
        for c in cuts:
            part = sub.evaluate(df.iloc[:c], cross).to_numpy(dtype="float64")
            if not np.allclose(part, full[:c], rtol=1e-9, atol=1e-12, equal_nan=True):
                return False
    return True


# ========== 语法树 → 节点 ==========


def _convert(node: ast.AST, columns: set[str]) -> Node:
    match node:
        case ast.Constant(value=v) if isinstance(v, (int, float)) and not isinstance(v, bool):
            return Const(float(v))
        case ast.Name(id=name):
            if not name.startswith(_MARK):
                raise DslError(f"列要写成 $名字，比如 ${name}")
            col = name[len(_MARK):]
            if col not in columns:
                raise DslError(f"没有这个列：${col}")
            return Col(col)
        case ast.UnaryOp(op=ast.USub(), operand=operand):
            return Neg(_convert(operand, columns))
        case ast.BinOp(left=left, op=op, right=right):
            symbol = _BIN.get(type(op))
            if symbol is None:
                raise DslError("只支持 + - * /，幂用 Pow(x, y)")
            return Bin(symbol, _convert(left, columns), _convert(right, columns))
        case ast.Call(func=ast.Name(id=name), args=args, keywords=keywords):
            if keywords:
                raise DslError(f"{name} 不支持关键字参数")
            op = ops.OPERATORS.get(name)
            if op is None:
                raise DslError(f"没有这个算子：{name}")
            return _call(name, op, args, columns)
        case _:
            raise DslError(f"不支持的写法：{type(node).__name__}")


def _call(name: str, op: ops.Op, args: list[ast.expr], columns: set[str]) -> Call:
    arity = {"unary": 1, "binary": 2, "ternary": 3, "rolling": 1, "quantile": 1, "pair": 2, "cross": 1}[op.kind]
    params = {"unary": 0, "binary": 0, "ternary": 0, "rolling": 1, "quantile": 2, "pair": 1, "cross": 0}[op.kind]
    if len(args) != arity + params:
        raise DslError(f"{name} 要 {arity + params} 个参数，给了 {len(args)} 个")
    series = tuple(_convert(a, columns) for a in args[:arity])
    if op.kind == "quantile":
        return Call(name, series, (_window(name, args[1]), _fraction(name, args[2])))
    if params:
        return Call(name, series, (_window(name, args[arity]),))
    return Call(name, series, ())


def _window(name: str, node: ast.expr) -> int:
    if not (isinstance(node, ast.Constant) and isinstance(node.value, int) and not isinstance(node.value, bool)):
        raise DslError(f"{name} 的窗口得是 1~{MAX_WINDOW} 的整数字面量")
    if not 1 <= node.value <= MAX_WINDOW:
        raise DslError(f"{name} 的窗口得在 1~{MAX_WINDOW} 之间")
    return node.value


def _fraction(name: str, node: ast.expr) -> float:
    if not (isinstance(node, ast.Constant) and isinstance(node.value, (int, float))
            and not isinstance(node.value, bool) and 0 < node.value < 1):
        raise DslError(f"{name} 的分位得是 0~1 之间的小数字面量")
    return float(node.value)


# ========== 遍历、渲染、求值 ==========


def _walk(node: Node):
    yield node
    match node:
        case Neg(x=x):
            yield from _walk(x)
        case Bin(left=left, right=right):
            yield from _walk(left)
            yield from _walk(right)
        case Call(args=args):
            for a in args:
                yield from _walk(a)


def _post_order(node: Node):
    """子节点先于父节点"""
    match node:
        case Neg(x=x):
            yield from _post_order(x)
        case Bin(left=left, right=right):
            yield from _post_order(left)
            yield from _post_order(right)
        case Call(args=args):
            for a in args:
                yield from _post_order(a)
    yield node


def _is_cross(node: Node) -> bool:
    return isinstance(node, Call) and ops.OPERATORS[node.name].kind == "cross"


def _render(node: Node, masked: bool) -> str:
    match node:
        case Col(name=name):
            return f"${name}"
        case Const(value=v):
            return "c" if masked else f"{v:g}"
        case Neg(x=x):
            return f"-{_render(x, masked)}"
        case Bin(op=op, left=left, right=right):
            parts = [_render(left, masked), _render(right, masked)]
            # 规范形不分左右：a + b 和 b + a、a * b 和 b * a 是同一个形状
            if masked and op in "+*":
                parts.sort()
            return f"({parts[0]} {op} {parts[1]})"
        case Call(name=name, args=args, params=params):
            parts = [_render(a, masked) for a in args]
            if params:
                parts.append("w" if masked else f"{int(params[0])}")
            if len(params) > 1:
                parts.append("c" if masked else f"{params[1]:g}")
            return f"{name}({', '.join(parts)})"
    raise TypeError(node)


def _eval(node: Node, df: pd.DataFrame, cross: Cross) -> pd.Series:
    match node:
        case Col(name=name):
            return df[name].astype("float64")
        case Const(value=v):
            return pd.Series(v, index=df.index, dtype="float64")
        case Neg(x=x):
            return -_eval(x, df, cross)
        case Bin(op=op, left=left, right=right):
            a, b = _eval(left, df, cross), _eval(right, df, cross)
            with np.errstate(all="ignore"):
                if op == "+":
                    return _finite(a + b)
                if op == "-":
                    return _finite(a - b)
                if op == "*":
                    return _finite(a * b)
                return _finite(a / b)
        case Call() if _is_cross(node):
            return _finite(cross[node].reindex(df.index).astype("float64"))
        case Call(name=name, args=args, params=params):
            series = [_eval(a, df, cross) for a in args]
            params = tuple(int(p) if i == 0 else p for i, p in enumerate(params))
            return _finite(ops.OPERATORS[name].fn(*series, *params))
    raise TypeError(node)


def _finite(s: pd.Series) -> pd.Series:
    """inf 换成 NaN，上层的 Gt / If / Sign 拿到的就不会是 inf"""
    return s.replace([np.inf, -np.inf], np.nan)
