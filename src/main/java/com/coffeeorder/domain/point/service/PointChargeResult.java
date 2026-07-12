package com.coffeeorder.domain.point.service;

import java.time.Instant;

public record PointChargeResult(long pointTransactionId, long balance, Instant chargedAt) {}
