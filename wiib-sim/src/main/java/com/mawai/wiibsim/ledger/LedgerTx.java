package com.mawai.wiibsim.ledger;

import com.mawai.wiibcommon.enums.LedgerBizType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Supplier;

/**
 * 类内的事务段：开一个事务，期间的资金流水默认记成 type。
 * 等于 @Transactional + @Ledger，但不靠代理，类内自己调也生效。
 */
@Component
@RequiredArgsConstructor
public class LedgerTx {

    private final TransactionTemplate transactionTemplate;

    public <T> T call(LedgerBizType type, Supplier<T> body) {
        LedgerCtx.push(type);
        try {
            return transactionTemplate.execute(_ -> body.get());
        } finally {
            LedgerCtx.pop();
        }
    }

    public void run(LedgerBizType type, Runnable body) {
        call(type, () -> {
            body.run();
            return null;
        });
    }
}