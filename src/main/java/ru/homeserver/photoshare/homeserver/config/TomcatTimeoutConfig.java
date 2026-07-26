package ru.homeserver.photoshare.homeserver.config;

import org.apache.coyote.AbstractProtocol;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.stereotype.Component;

@Component
public class TomcatTimeoutConfig
        implements WebServerFactoryCustomizer<TomcatServletWebServerFactory> {

    private static final int DOWNLOAD_TIMEOUT_MS =
            30 * 60 * 1000;

    @Override
    public void customize(
            TomcatServletWebServerFactory factory
    ) {
        factory.addConnectorCustomizers(connector -> {
            connector.setProperty(
                    "connectionTimeout",
                    String.valueOf(DOWNLOAD_TIMEOUT_MS)
            );

            connector.setProperty(
                    "socket.soTimeout",
                    String.valueOf(DOWNLOAD_TIMEOUT_MS)
            );

            if (connector.getProtocolHandler()
                    instanceof AbstractProtocol<?> protocol) {

                protocol.setConnectionTimeout(
                        DOWNLOAD_TIMEOUT_MS
                );

                protocol.setKeepAliveTimeout(
                        DOWNLOAD_TIMEOUT_MS
                );
            }
        });
    }
}