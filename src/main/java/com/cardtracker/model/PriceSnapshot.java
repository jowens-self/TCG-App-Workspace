package com.cardtracker.model;

import java.math.BigDecimal;
import java.time.LocalDate;

public record PriceSnapshot(
        int productId,
        String subTypeName,
        LocalDate snapshotDate,
        BigDecimal marketPrice,
        BigDecimal lowPrice,
        BigDecimal midPrice,
        BigDecimal highPrice,
        BigDecimal directLowPrice) {
}
