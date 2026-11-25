package com.vyom.service;

import com.vyom.entity.MerchantPayable;
import com.vyom.entity.Wallet;
import com.vyom.entity.enums.AccountType;
import com.vyom.exception.WalletNotFoundException;
import com.vyom.repository.MerchantPayableRepository;
import com.vyom.repository.WalletRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

@Service
public class MerchantPayableService {

    private static final Logger log = LoggerFactory.getLogger(MerchantPayableService.class);

    private final MerchantPayableRepository payableRepository;
    private final WalletRepository walletRepository;

    public MerchantPayableService(MerchantPayableRepository payableRepository,
                                  WalletRepository walletRepository) {
        this.payableRepository = payableRepository;
        this.walletRepository = walletRepository;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public MerchantPayable getOrCreatePayable(UUID merchantId, String currency) {
        String curr = currency.toUpperCase().trim();
        return payableRepository.findByMerchantIdAndCurrency(merchantId, curr)
                .orElseGet(() -> {
                    MerchantPayable newPayable = new MerchantPayable(merchantId, curr);
                    return payableRepository.saveAndFlush(newPayable);
                });
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Wallet getCommercialPayableWallet(String currency) {
        String curr = currency.toUpperCase().trim();
        return walletRepository.findByAccountTypeAndCurrency(AccountType.MERCHANT_PAYABLE, curr)
                .orElseThrow(() -> new WalletNotFoundException(
                        "Commercial payable settlement wallet not found for currency: " + curr));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public MerchantPayable creditPayable(UUID merchantId, String currency, BigDecimal amount) {
        String curr = currency.toUpperCase().trim();
        MerchantPayable payable = payableRepository.findByMerchantIdAndCurrencyForUpdate(merchantId, curr)
                .orElseGet(() -> {
                    MerchantPayable p = new MerchantPayable(merchantId, curr);
                    return payableRepository.saveAndFlush(p);
                });

        payable.credit(amount);
        payable = payableRepository.save(payable);
        log.info("Credited merchant payable: merchant={}, currency={}, amount={}, newBalance={}",
                merchantId, curr, amount, payable.getCurrentBalance());
        return payable;
    }
}
