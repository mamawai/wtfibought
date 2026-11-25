package com.mawai.wiibagent.trader.wakeup;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.LongPredicate;
import java.util.function.Predicate;

/**
 * 每个 trader 的现场（desk）：在跑的几轮、停着的事、占用。所有要拿 desk 锁的小操作都在这里：登记一轮、摘掉一轮、
 * 把到来的事交给能接的轮、占用和放手、交接时的持有名单。去处之外的事（查库、判唤醒时段、起线程、调对端）在调度器里。
 * <p>
 * 交接期间 desk 还有两种状态：新实例上"旧实例持有"（到来的事先转给旧实例，没接就停着，等它放手），
 * 旧实例上"我还持有"（让位时有轮在跑、被占着或有预案在做，最后一轮结束后放手）。
 * "起新轮的口"关没关在 desk 锁里判：一轮要么在出持有名单之前登记上（被数进去），要么被拒（交还新实例）
 */
@Component
public class Desks {

    /** 整盘轮在 {@link Desk#rounds} 里的键；单币轮的键是币码 */
    static final String BOOK = "*";

    /** 一件到来的事的去处 */
    enum Place {
        /** 登记了新的一轮，调用方去起线程 */
        STARTED,
        /** 插进了在跑的轮 */
        TAKEN,
        /** 被重置或删除占着，触发停着，放手后分发 */
        PARKED,
        /** 被重置或删除占着，这根例行不发 */
        DROPPED,
        /** 别的轮已经把这条盯盘命中告诉过模型 */
        SKIPPED,
        /** 起新轮的口关着 */
        CLOSED,
        /** 旧实例还持有，转过去 */
        HELD_BY_OLD,
        /** 手动唤醒被挡 */
        BUSY
    }

    /** 去处；STARTED 时带上登记好的这一轮和它的键 */
    record Placement(Place place, WakeRound round, String key) {
        static Placement of(Place place) {
            return new Placement(place, null, null);
        }
    }

    /** 停着的事：例行边界（只留最近一根）和触发 */
    record Parked(Long routine, List<WakeTrigger> triggers) {
        boolean isEmpty() {
            return routine == null && triggers.isEmpty();
        }
    }

    /** 一个 trader 的现场。读写都锁这个对象 */
    private static final class Desk {
        /** 正在跑的轮，整盘轮至多一轮，每个币的单币轮至多一轮 */
        final Map<String, WakeRound> rounds = new HashMap<>();
        /** 停着的触发：被占着、旧实例持有没接、口关着时交还来的 */
        final List<WakeTrigger> parked = new ArrayList<>();
        /** 停着的例行边界，只留最近一根 */
        Long parkedRoutine;
        /** 重置或删除占着，这期间不起新轮 */
        boolean occupied;
        /** 新实例上：旧实例还持有 */
        boolean heldByOld;
        /** 旧实例上：我还持有，跑完要放手 */
        boolean heldHere;

        Parked takeParked() {
            Parked out = new Parked(parkedRoutine, List.copyOf(parked));
            parked.clear();
            parkedRoutine = null;
            return out;
        }

        void park(Arrival arrival) {
            switch (arrival) {
                case Arrival.Trigger t -> parked.add(t.trigger());
                case Arrival.Routine r -> parkedRoutine = parkedRoutine == null ? r.boundary() : Math.max(parkedRoutine, r.boundary());
            }
        }
    }

    private final Map<Long, Desk> desks = new ConcurrentHashMap<>();

    private Desk desk(long traderId) {
        return desks.computeIfAbsent(traderId, _ -> new Desk());
    }

    /** 例行到点：旧实例持有 → 转；占着 → 不发；有整盘轮在跑 → 插进去；没有就登记一轮整盘的（口关着不登记） */
    Placement routine(long traderId, WakeScope book, long boundary, BooleanSupplier open, long now) {
        Desk d = desk(traderId);
        synchronized (d) {
            if (d.heldByOld) {
                return Placement.of(Place.HELD_BY_OLD);
            }
            if (d.occupied) {
                return Placement.of(Place.DROPPED);
            }
            WakeRound running = d.rounds.get(BOOK);
            if (running != null) {
                running.offerRoutine(boundary);
                return Placement.of(Place.TAKEN);
            }
            return register(d, book, open, now);
        }
    }

    /**
     * 一条触发：旧实例持有 → 转；占着 → 停着；有能接的轮（这个币的单币轮，没有就整盘轮）→ 插进去；
     * told 说别的轮已经告诉过模型 → 跳过；都不是就登记一轮（口关着不登记）
     */
    Placement trigger(long traderId, WakeScope scope, WakeTrigger trigger, BooleanSupplier open,
                      Predicate<WakeTrigger> told, long now) {
        Desk d = desk(traderId);
        synchronized (d) {
            if (d.heldByOld) {
                return Placement.of(Place.HELD_BY_OLD);
            }
            if (d.occupied) {
                d.parked.add(trigger);
                return Placement.of(Place.PARKED);
            }
            WakeRound taker = taker(d, scope);
            if (taker != null) {
                taker.offer(trigger);
                return Placement.of(Place.TAKEN);
            }
            if (told.test(trigger)) {
                return Placement.of(Place.SKIPPED);
            }
            return register(d, scope, open, now);
        }
    }

    /** 手动唤醒：被持有、被占着、已有整盘轮在跑、口关着都挡；不挡就登记一轮整盘的 */
    Placement manual(long traderId, WakeScope book, BooleanSupplier open, long now) {
        Desk d = desk(traderId);
        synchronized (d) {
            if (manualBlocked(d, open)) {
                return Placement.of(Place.BUSY);
            }
            return register(d, book, open, now);
        }
    }

    /** 手动唤醒会不会被挡（只看不登记） */
    boolean manualBlocked(long traderId, BooleanSupplier open) {
        Desk d = desk(traderId);
        synchronized (d) {
            return manualBlocked(d, open);
        }
    }

    private static boolean manualBlocked(Desk d, BooleanSupplier open) {
        return d.heldByOld || d.occupied || d.rounds.containsKey(BOOK) || !open.getAsBoolean();
    }

    /** 登记一轮。调用方已经锁着 desk */
    private Placement register(Desk d, WakeScope scope, BooleanSupplier open, long now) {
        if (!open.getAsBoolean()) {
            return Placement.of(Place.CLOSED);
        }
        String key = scope.single() ? scope.symbol() : BOOK;
        WakeRound round = new WakeRound(scope, () -> singleSymbols(d), now);
        d.rounds.put(key, round);
        return new Placement(Place.STARTED, round, key);
    }

    /** 能接这件事的轮：这个币的单币轮，没有就整盘轮 */
    private static WakeRound taker(Desk d, WakeScope scope) {
        WakeRound own = scope.single() ? d.rounds.get(scope.symbol()) : null;
        return own != null ? own : d.rounds.get(BOOK);
    }

    /** 一轮跑完：摘掉登记，取回没被读到的 */
    WakeRound.Arrivals finish(long traderId, String key, WakeRound round) {
        Desk d = desk(traderId);
        synchronized (d) {
            d.rounds.remove(key);
            return round.take();
        }
    }

    /** 此刻有单币轮在处理的币 */
    private static Set<String> singleSymbols(Desk d) {
        synchronized (d) {
            Set<String> out = new TreeSet<>(d.rounds.keySet());
            out.remove(BOOK);
            return out;
        }
    }

    /** 在跑的轮数 */
    int roundCount() {
        int n = 0;
        for (Desk d : desks.values()) {
            synchronized (d) {
                n += d.rounds.size();
            }
        }
        return n;
    }

    /** atMs 那一刻就在跑、到现在还没跑完、看得到这个币的轮（整盘轮或这个币的单币轮）；旧实例持有的当作有 */
    boolean runningAt(long traderId, String symbol, long atMs) {
        Desk d = desk(traderId);
        synchronized (d) {
            return d.heldByOld || d.rounds.entrySet().stream()
                    .anyMatch(e -> (BOOK.equals(e.getKey()) || e.getKey().equals(symbol)) && e.getValue().startedAt() <= atMs);
        }
    }

    /** 占住（重置、删除）：有轮在跑或被旧实例持有就占不上 */
    boolean tryOccupy(long traderId) {
        Desk d = desk(traderId);
        synchronized (d) {
            if (d.occupied || d.heldByOld || !d.rounds.isEmpty()) {
                return false;
            }
            d.occupied = true;
            return true;
        }
    }

    /** 放开占用，取出占着的时候停着的触发 */
    List<WakeTrigger> releaseOccupied(long traderId) {
        Desk d = desk(traderId);
        synchronized (d) {
            d.occupied = false;
            List<WakeTrigger> out = List.copyOf(d.parked);
            d.parked.clear();
            return out;
        }
    }

    void forget(long traderId) {
        desks.remove(traderId);
    }

    // ---------- 交接：旧实例这一侧 ----------

    /** 交给在跑的轮（对端转来的）：不持有 → NOT_HELD；有能接的轮 → TAKEN；没有 → NOT_TAKEN。例行只给整盘轮 */
    Offer offer(long traderId, WakeScope scope, Arrival arrival) {
        Desk d = desk(traderId);
        synchronized (d) {
            if (!d.heldHere) {
                return Offer.NOT_HELD;
            }
            switch (arrival) {
                case Arrival.Trigger t -> {
                    WakeRound taker = taker(d, scope);
                    if (taker == null) {
                        return Offer.NOT_TAKEN;
                    }
                    taker.offer(t.trigger());
                }
                case Arrival.Routine r -> {
                    WakeRound book = d.rounds.get(BOOK);
                    if (book == null) {
                        return Offer.NOT_TAKEN;
                    }
                    book.offerRoutine(r.boundary());
                }
            }
            return Offer.TAKEN;
        }
    }

    /** 关口时：有轮在跑、被占着、或者在 busy 里（有预案在做）的，标成"我还持有" */
    void markHeldHere(Set<Long> busy) {
        busy.forEach(this::desk);
        desks.forEach((id, d) -> {
            synchronized (d) {
                d.heldHere = !d.rounds.isEmpty() || d.occupied || busy.contains(id);
            }
        });
    }

    /** 恢复在任：不再替对端持有 */
    void clearHeldHere() {
        for (Desk d : desks.values()) {
            synchronized (d) {
                d.heldHere = false;
            }
        }
    }

    /** 此刻还持有的 */
    Set<Long> heldHere() {
        Set<Long> out = new TreeSet<>();
        desks.forEach((id, d) -> {
            synchronized (d) {
                if (d.heldHere) {
                    out.add(id);
                }
            }
        });
        return out;
    }

    /** 还持有就在锁里跑 action（对端转来的预案在这里登记），返回 true；不持有返回 false */
    boolean whileHeldHere(long traderId, Runnable action) {
        Desk d = desk(traderId);
        synchronized (d) {
            if (!d.heldHere) {
                return false;
            }
            action.run();
            return true;
        }
    }

    /** 放手：还持有、没有轮、没被占着、idle 说没别的事在做（预案）才放，放了返回 true */
    boolean releaseHere(long traderId, LongPredicate idle) {
        Desk d = desk(traderId);
        synchronized (d) {
            if (!d.heldHere || !d.rounds.isEmpty() || d.occupied || !idle.test(traderId)) {
                return false;
            }
            d.heldHere = false;
            return true;
        }
    }

    // ---------- 交接：新实例这一侧 ----------

    /** 接手时：这些 trader 标成旧实例持有 */
    void markHeldByOld(Collection<Long> traderIds) {
        for (long id : traderIds) {
            Desk d = desk(id);
            synchronized (d) {
                d.heldByOld = true;
            }
        }
    }

    boolean isHeldByOld(long traderId) {
        Desk d = desks.get(traderId);
        if (d == null) {
            return false;
        }
        synchronized (d) {
            return d.heldByOld;
        }
    }

    /** 被旧实例持有的 */
    Set<Long> heldByOld() {
        Set<Long> out = new TreeSet<>();
        desks.forEach((id, d) -> {
            synchronized (d) {
                if (d.heldByOld) {
                    out.add(id);
                }
            }
        });
        return out;
    }

    /** 旧实例没接（或调不通）：还被持有就停着，返回 true；已经不被持有了返回 false，调用方本地处理 */
    boolean parkWhileHeld(long traderId, Arrival arrival) {
        Desk d = desk(traderId);
        synchronized (d) {
            if (!d.heldByOld) {
                return false;
            }
            d.park(arrival);
            return true;
        }
    }

    /**
     * 旧实例交还来的：还被持有、或者口还关着（接手还没开口）就停着；被占着的触发停着、例行不发。
     * 停着或丢了返回 true；返回 false 调用方按平时分发
     */
    boolean parkHandedBack(long traderId, Arrival arrival, BooleanSupplier open) {
        Desk d = desk(traderId);
        synchronized (d) {
            if (d.occupied) {
                if (arrival instanceof Arrival.Trigger t) {
                    d.parked.add(t.trigger());
                }
                return true;
            }
            if (d.heldByOld || !open.getAsBoolean()) {
                d.park(arrival);
                return true;
            }
            return false;
        }
    }

    /** 旧实例放手了：清掉持有标记，取出停着的；本来就不被持有返回 null */
    Parked releaseFromOld(long traderId) {
        Desk d = desks.get(traderId);
        if (d == null) {
            return null;
        }
        synchronized (d) {
            if (!d.heldByOld) {
                return null;
            }
            d.heldByOld = false;
            return d.takeParked();
        }
    }

    /** 开口时：没被持有也没被占着、却停着东西的（口关着时交还来的），取出来 */
    Map<Long, Parked> drainParked() {
        Map<Long, Parked> out = new HashMap<>();
        desks.forEach((id, d) -> {
            synchronized (d) {
                if (!d.heldByOld && !d.occupied) {
                    Parked p = d.takeParked();
                    if (!p.isEmpty()) {
                        out.put(id, p);
                    }
                }
            }
        });
        return out;
    }
}
