package com.virtualpet;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 验证 Spring 上下文可以正常启动（使用 H2 的 MySQL 兼容模式）。
 */
@SpringBootTest
@ActiveProfiles("test")
class VirtualPetApplicationTests {

    @Test
    void contextLoads() {
        // 上下文启动成功即通过
    }
}
