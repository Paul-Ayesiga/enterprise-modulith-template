package ug.co.smsone.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The platform's reactive edge (ADR 0007). A separate, stateless deployable — it holds no business
 * logic and no tenant state; see docs/GATEWAY_ARCHITECTURE.md.
 */
@SpringBootApplication
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
