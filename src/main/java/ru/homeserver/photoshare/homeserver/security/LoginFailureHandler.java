package ru.homeserver.photoshare.homeserver.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class LoginFailureHandler implements AuthenticationFailureHandler {

    private final LoginAttemptService loginAttemptService;

    public LoginFailureHandler(LoginAttemptService loginAttemptService) {
        this.loginAttemptService = loginAttemptService;
    }

    @Override
    public void onAuthenticationFailure(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException exception
    ) throws IOException {

        String key = request.getRemoteAddr();

        if (loginAttemptService.isBlocked(key)) {
            response.sendRedirect("/login?blocked");
            return;
        }

        loginAttemptService.loginFailed(key);

        if (loginAttemptService.isBlocked(key)) {
            response.sendRedirect("/login?blocked");
            return;
        }

        response.sendRedirect("/login?error");
    }
}
