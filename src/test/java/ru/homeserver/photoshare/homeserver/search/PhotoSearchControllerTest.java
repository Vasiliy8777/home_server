package ru.homeserver.photoshare.homeserver.search;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import ru.homeserver.photoshare.homeserver.config.SecurityConfig;
import ru.homeserver.photoshare.homeserver.security.LoginFailureHandler;
import ru.homeserver.photoshare.homeserver.security.LoginSuccessHandler;

import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = PhotoSearchController.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.REGEX, pattern = ".*WebConfig.*"), properties = {
        "app.security.username=test", "app.security.password=test",
        "app.log-dir=${java.io.tmpdir}/homeserver-photo-search-test-logs"})
@Import(SecurityConfig.class)
class PhotoSearchControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean PhotoSearchService service;
    @MockitoBean LoginFailureHandler failureHandler;
    @MockitoBean LoginSuccessHandler successHandler;

    private MockHttpSession authenticatedSession() {
        var session = new MockHttpSession();
        var authentication = UsernamePasswordAuthenticationToken.authenticated("test", "",
                AuthorityUtils.createAuthorityList("ROLE_USER"));
        session.setAttribute("SPRING_SECURITY_CONTEXT", new SecurityContextImpl(authentication));
        return session;
    }

    @Test
    void requiresAuthenticationForSearchStatusAndImages() throws Exception {
        mvc.perform(get("/api/photo-search").param("query", "пляж")).andExpect(status().is3xxRedirection());
        mvc.perform(get("/api/photo-search/status")).andExpect(status().is3xxRedirection());
        mvc.perform(get("/api/photo-search/image").param("path", "photo.jpg").param("version", "1"))
                .andExpect(status().is3xxRedirection());
        verifyNoInteractions(service);
    }

    @Test
    void requiresCsrfForIndexingAndAllowsAuthenticatedRequestWithToken() throws Exception {
        var session = authenticatedSession();
        mvc.perform(post("/api/photo-search/index").session(session)).andExpect(status().isForbidden());
        var token = mvc.perform(get("/api/photo-search/status").session(session)).andReturn()
                .getResponse().getCookie("XSRF-TOKEN");
        mvc.perform(post("/api/photo-search/index").session(session).cookie(token)
                        .header("X-XSRF-TOKEN", token.getValue()))
                .andExpect(status().isOk());
        verify(service, times(1)).start();
    }

    @Test
    void returnsSearchPageAndValidationErrors() throws Exception {
        when(service.search("пляж", 0, 60)).thenReturn(new PhotoSearchService.SearchPage(List.of(), 0, null));
        mvc.perform(get("/api/photo-search").session(authenticatedSession()).param("query", "пляж"))
                .andExpect(status().isOk()).andExpect(jsonPath("total").value(0));
        when(service.search("", 0, 60)).thenThrow(new IllegalArgumentException("Введите запрос"));
        mvc.perform(get("/api/photo-search").session(authenticatedSession()).param("query", ""))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("error").value("Введите запрос"));
    }
}
