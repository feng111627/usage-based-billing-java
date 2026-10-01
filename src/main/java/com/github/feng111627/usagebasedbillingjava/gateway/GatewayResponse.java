package com.github.feng111627.usagebasedbillingjava.gateway;

/**
 * 模拟通道配置脚本中的一条规则：按引用前缀/序号决定成功或失败。
 */
public record GatewayResponse(String matchReference, boolean success, String failureReason) {
}
