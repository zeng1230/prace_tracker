package webhookconfigtest;

import com.example.price_tracker.config.JwtProperties;
import com.example.price_tracker.config.NotificationProperties;
import com.example.price_tracker.config.ProductionConfigValidator;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;

@SpringBootConfiguration(proxyBeanMethods = false)
@EnableConfigurationProperties({JwtProperties.class, NotificationProperties.class})
@Import(ProductionConfigValidator.class)
public class WebhookValidationTestApplication {
}
