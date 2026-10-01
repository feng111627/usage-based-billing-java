package com.github.feng111627.usagebasedbillingjava.model;

/**
 * 三方账户：订阅方现金、平台收入、支付通道手续费。任意时刻三者余额之和必须为 0。
 */
public enum Account {
    SUBSCRIBER,
    PLATFORM,
    CHANNEL
}
