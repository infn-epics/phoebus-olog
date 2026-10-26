package org.phoebus.olog;

import org.apache.catalina.connector.Connector;
import org.apache.coyote.http11.AbstractHttp11Protocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.embedded.tomcat.TomcatWebServer;
import org.springframework.boot.web.servlet.context.ServletWebServerInitializedEvent;
import org.springframework.boot.web.servlet.server.ServletWebServerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;
import org.springframework.context.event.EventListener;

@Configuration
@PropertySource("classpath:/application.properties")
@SuppressWarnings("unused")
public class HttpConnectorConfig {

    private static final Logger log = LoggerFactory.getLogger(HttpConnectorConfig.class);

    @EventListener
    public void logRequestHeaderLimits(ServletWebServerInitializedEvent event) {
        if (event.getWebServer() instanceof TomcatWebServer) {
            TomcatWebServer server = (TomcatWebServer) event.getWebServer();
            for (Connector connector : server.getTomcat().getService().findConnectors()) {
                if (connector.getProtocolHandler() instanceof AbstractHttp11Protocol) {
                    AbstractHttp11Protocol<?> protocol =
                            (AbstractHttp11Protocol<?>) connector.getProtocolHandler();
                    log.info("Tomcat port {}: maxHttpRequestHeaderSize={} bytes",
                            connector.getLocalPort(), protocol.getMaxHttpRequestHeaderSize());
                }
            }
        }
    }

    @Value("${server.http.enable:true}")
    private boolean httpEnabled;
    @Value("${server.http.port:8080}")
    private int port;

    @Bean
    @ConditionalOnProperty(name="server.http.enable")
    public ServletWebServerFactory servletContainer() {
        TomcatServletWebServerFactory tomcat = new TomcatServletWebServerFactory();
        tomcat.addAdditionalTomcatConnectors(getHttpConnector());
        return tomcat;
    }

    private Connector getHttpConnector() {
        Connector connector = new Connector("org.apache.coyote.http11.Http11NioProtocol");
        connector.setScheme("http");
        connector.setPort(port);
        // This is needed to be able to send a response if client uploads a log entry
        // exceeding configured max sizes. Without this setting Tomcat will simply close the
        // connection before a response can be sent.
        ((AbstractHttp11Protocol <?>)connector.getProtocolHandler()).setMaxSwallowSize(-1);
        return connector;
    }
}
