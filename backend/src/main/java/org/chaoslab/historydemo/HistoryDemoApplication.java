package org.chaoslab.historydemo;

import com.chaoslab.console.ConsoleReadOnlyFilter;
import com.chaoslab.console.history.HistoryEvidenceController;
import com.chaoslab.console.history.HistoryEvidenceReader;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** Isolated launcher: no scanning of production services, DB, engine, wrapper or scheduler. */
@Configuration(proxyBeanMethods = false)
@EnableAutoConfiguration(
    exclude = {
        DataSourceAutoConfiguration.class,
        HibernateJpaAutoConfiguration.class,
        DataJpaRepositoriesAutoConfiguration.class,
        FlywayAutoConfiguration.class,
    }
)
@Import({
    HistoryEvidenceController.class,
    HistoryEvidenceReader.class,
    ConsoleReadOnlyFilter.class,
})
public class HistoryDemoApplication {

    public static void main(String[] args) {
        var app = new SpringApplication(HistoryDemoApplication.class);
        app.setAdditionalProfiles("history-demo");
        app.run(args);
    }
}
