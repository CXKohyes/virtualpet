package com.virtualpet;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 像素宠物屋后端启动类。
 *
 * <p>批次 0 只提供最小可启动应用和健康检查，不包含宠物业务、数据库表、对战和 WebSocket。</p>
 */
@SpringBootApplication
public class VirtualPetApplication {

    public static void main(String[] args) {
        SpringApplication.run(VirtualPetApplication.class, args);
    }
}
