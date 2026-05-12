package ru.homeserver.photoshare.homeserver.config;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class CsrfCookieFilter implements Filter {

    @Override
    public void doFilter(ServletRequest request,
                         ServletResponse response,
                         FilterChain chain)
            throws IOException, ServletException {

        CsrfToken csrfToken =
                (CsrfToken) ((HttpServletRequest) request)
                        .getAttribute(CsrfToken.class.getName());

        if (csrfToken != null) {
            csrfToken.getToken(); // заставляет Spring создать cookie XSRF-TOKEN
        }

        chain.doFilter(request, response);
    }
}
