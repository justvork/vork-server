package sh.vork.filesystem;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SessionUploadStreamingServletConfig {

    @Bean
    public ServletRegistrationBean<SessionUploadStreamingServlet> sessionUploadStreamingServlet(
            SessionFileSystem sessionFileSystem,
            SessionFileAuthorizationService authorizationService,
            ObjectMapper objectMapper) {
        SessionUploadStreamingServlet servlet =
                new SessionUploadStreamingServlet(sessionFileSystem, authorizationService, objectMapper);
        ServletRegistrationBean<SessionUploadStreamingServlet> bean =
                new ServletRegistrationBean<>(servlet, "/api/session-files/upload-stream");
        bean.setName("sessionUploadStreamingServlet");
        bean.setLoadOnStartup(1);
        return bean;
    }
}
