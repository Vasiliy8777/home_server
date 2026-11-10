package ru.homeserver.photoshare.homeserver.music;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.*;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import ru.homeserver.photoshare.homeserver.config.SecurityConfig;
import ru.homeserver.photoshare.homeserver.security.LoginFailureHandler;
import ru.homeserver.photoshare.homeserver.security.LoginSuccessHandler;

import java.nio.file.*;
import java.util.*;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = MusicController.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.REGEX, pattern = ".*WebConfig.*"),
        properties = {"app.security.username=test", "app.security.password=test", "app.log-dir=${java.io.tmpdir}/homeserver-music-test-logs"})
@Import(SecurityConfig.class)
class MusicControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean MusicService service;
    @MockitoBean MusicSourceService sources;
    @MockitoBean LoginFailureHandler failureHandler;
    @MockitoBean LoginSuccessHandler successHandler;
    @TempDir Path root;
    private MockHttpSession session() {
        var session = new MockHttpSession();
        session.setAttribute("SPRING_SECURITY_CONTEXT", new SecurityContextImpl(
                UsernamePasswordAuthenticationToken.authenticated("test", "", AuthorityUtils.createAuthorityList("ROLE_USER"))));
        return session;
    }
    @Test void privatePromptsAndAudioRequireLogin() throws Exception {
        for (String path : List.of("/api/music/status", "/api/music/jobs", "/api/music/jobs/test/audio", "/api/music/jobs/test/download", "/api/music/sources/test", "/api/music/sources/test/audio"))
            mvc.perform(get(path)).andExpect(status().is3xxRedirection());
        verifyNoInteractions(service);
    }
    @Test void savingAndDeletingRequireCsrf() throws Exception {
        var session = session();
        mvc.perform(post("/api/music/jobs/test/save").session(session)).andExpect(status().isForbidden());
        mvc.perform(delete("/api/music/jobs/test").session(session)).andExpect(status().isForbidden());
        when(service.status()).thenReturn(Map.of("available", true));
        var token = mvc.perform(get("/api/music/status").session(session)).andReturn().getResponse().getCookie("XSRF-TOKEN");
        mvc.perform(post("/api/music/jobs/test/save").session(session).cookie(token).header("X-XSRF-TOKEN", token.getValue()))
                .andExpect(status().isOk());
        verify(service).save("test");
    }
    @Test void audioSupportsByteRangesForSeeking() throws Exception {
        Path audio = root.resolve("test.wav");
        Files.write(audio, new byte[100]);
        when(service.audio("test")).thenReturn(audio);
        mvc.perform(get("/api/music/jobs/test/audio").session(session()).header("Range", "bytes=10-19"))
                .andExpect(status().isPartialContent()).andExpect(header().string("Content-Range", "bytes 10-19/100"))
                .andExpect(content().bytes(new byte[10]));
    }
    @Test void downloadsSelectedAudioAsAttachmentWithoutChangingItsStorageState() throws Exception {
        Path audio = root.resolve("result.wav");
        byte[] bytes = new byte[100];
        Files.write(audio, bytes);
        when(service.audio("selected")).thenReturn(audio);
        mvc.perform(get("/api/music/jobs/selected/download").session(session()))
                .andExpect(status().isOk()).andExpect(content().contentType("audio/wav"))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"song-selected.wav\""))
                .andExpect(content().bytes(bytes));
        verify(service).audio("selected");
        verifyNoMoreInteractions(service);
    }
    @Test void deletedAudioCannotBeDownloaded() throws Exception {
        when(service.audio("deleted")).thenThrow(new NoSuchFileException("deleted"));
        mvc.perform(get("/api/music/jobs/deleted/download").session(session())).andExpect(status().isNotFound());
    }
    @Test void sourceUploadRequiresCsrfAndStreamsBinaryData() throws Exception {
        var session = session();
        byte[] audio = new byte[]{1, 2, 3, 4};
        mvc.perform(post("/api/music/sources").param("name", "sample.wav").session(session)
                .contentType("application/octet-stream").content(audio)).andExpect(status().isForbidden());
        verifyNoInteractions(sources);
        when(service.status()).thenReturn(Map.of("available", true));
        var token = mvc.perform(get("/api/music/status").session(session)).andReturn().getResponse().getCookie("XSRF-TOKEN");
        when(sources.upload(eq("sample.wav"), eq(4L), any(), eq("music"))).thenAnswer(call -> {
            org.junit.jupiter.api.Assertions.assertArrayEquals(audio, call.getArgument(2, java.io.InputStream.class).readAllBytes());
            return new MusicSourceService.Source("source", "sample.wav", 30, "/audio");
        });
        mvc.perform(post("/api/music/sources").param("name", "sample.wav").session(session).cookie(token)
                        .header("X-XSRF-TOKEN", token.getValue()).contentType("application/octet-stream").content(audio))
                .andExpect(status().isCreated()).andExpect(jsonPath("id").value("source"));
        when(sources.upload(eq("voice.wav"), eq(4L), any(), eq("voice")))
                .thenReturn(new MusicSourceService.Source("voice", "voice.wav", 5, "/audio", "voice"));
        mvc.perform(post("/api/music/sources").param("name", "voice.wav").param("purpose", "voice").session(session).cookie(token)
                        .header("X-XSRF-TOKEN", token.getValue()).contentType("application/octet-stream").content(audio))
                .andExpect(status().isCreated()).andExpect(jsonPath("purpose").value("voice"));
    }
}
