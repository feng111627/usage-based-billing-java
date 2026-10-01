package com.github.feng111627.usagebasedbillingjava.config;

import com.github.feng111627.usagebasedbillingjava.error.ErrorCode;
import com.github.feng111627.usagebasedbillingjava.error.BillingException;
import com.github.feng111627.usagebasedbillingjava.service.PlanService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * 预置两个套餐，启动后即可直接走通完整流程：
 * BASIC（30 元/周期，1000 次配额，超出 0.05 元/次）、
 * PRO（80 元/周期，5000 次配额，超出 0.02 元/次）。
 */
@Component
public class DataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    private final PlanService planService;

    public DataInitializer(PlanService planService) {
        this.planService = planService;
    }

    @Override
    public void run(ApplicationArguments args) {
        seed("BASIC", "基础版", new BigDecimal("30.00"), 1000, new BigDecimal("0.05"));
        seed("PRO", "专业版", new BigDecimal("80.00"), 5000, new BigDecimal("0.02"));
        log.info("[init] 示例套餐就绪: BASIC, PRO");
    }

    private void seed(String id, String name, BigDecimal price, long quota, BigDecimal overage) {
        try {
            planService.createPlan(id, name, price, quota, overage);
        } catch (BillingException ex) {
            if (ex.getCode() == ErrorCode.DUPLICATE_REQUEST) {
                // 重启场景：内存数据其实会清空，这里仅保持幂等语义
                log.info("[init] 套餐 {} 已存在，跳过", id);
            } else {
                throw ex;
            }
        }
    }
}
