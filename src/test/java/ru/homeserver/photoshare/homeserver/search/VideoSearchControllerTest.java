package ru.homeserver.photoshare.homeserver.search;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import ru.homeserver.photoshare.homeserver.share.ShareService;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class VideoSearchControllerTest {
    @Test void videoRoutesUseOnlyVideoIndexAndValidateFilters() throws Exception {
        var video = mock(VideoSearchService.class);
        var index = mock(PhotoSearchService.class);
        when(video.index()).thenReturn(index);
        when(index.search(eq("море"), eq(0), eq(60), any(), isNull()))
                .thenReturn(new PhotoSearchService.SearchPage(List.of(), 3, null));
        var mvc = MockMvcBuilders.standaloneSetup(new VideoSearchController(video, mock(ShareService.class))).build();
        mvc.perform(get("/api/video-search").param("query", "море"))
                .andExpect(status().isOk()).andExpect(jsonPath("total").value(3));
        mvc.perform(post("/api/video-search/index")).andExpect(status().isOk());
        mvc.perform(post("/api/video-search/stop")).andExpect(status().isOk());
        mvc.perform(get("/api/video-search/status")).andExpect(status().isOk());
        verify(index).start();
        verify(index).stop();
        verify(index).status();
        mvc.perform(get("/api/video-search").param("query", "море").param("sortField", "invalid"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/video-search").param("query", "море").param("periodEnabled", "true")
                        .param("periodFrom", "invalid"))
                .andExpect(status().isBadRequest());
    }
}
