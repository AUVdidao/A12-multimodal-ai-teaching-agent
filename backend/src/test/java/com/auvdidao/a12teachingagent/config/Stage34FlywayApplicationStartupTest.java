package com.auvdidao.a12teachingagent.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate"
})
@ActiveProfiles("test")
class Stage34FlywayApplicationStartupTest {

    @Test
    void applicationStartsWithVersionedSchemaAndValidationOnly() {
        // Context initialization is the assertion: Flyway creates the schema before Hibernate validates it.
    }
}
