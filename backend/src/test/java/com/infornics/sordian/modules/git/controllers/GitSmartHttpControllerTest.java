package com.infornics.sordian.modules.git.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.infornics.sordian.modules.git.service.GitProcessRunner;
import com.infornics.sordian.modules.git.service.GitQuarantineService;
import java.io.File;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(GitSmartHttpController.class)
class GitSmartHttpControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private GitQuarantineService quarantineService;

    @MockBean
    private GitProcessRunner gitRunner;

    @Test
    @DisplayName("Should return 403 Forbidden when service is not git-receive-pack (e.g. clone/fetch attempts)")
    void shouldRejectNonReceivePackServices() throws Exception {
        mockMvc.perform(get("/acme/core-api.git/info/refs?service=git-upload-pack"))
                .andExpect(status().isForbidden())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Sordian only proxies Git pushes")));
    }

    @Test
    @DisplayName("Should return 200 with Git pkt-line advertisement for git-receive-pack")
    void shouldAdvertiseRefsForReceivePack() throws Exception {
        File dummyRepo = File.createTempFile("sordian-test", ".git");
        dummyRepo.delete();
        dummyRepo.mkdirs();
        dummyRepo.deleteOnExit();

        Mockito.when(quarantineService.getOrCreateQuarantineRepo("acme", "core-api"))
                .thenReturn(dummyRepo);

        mockMvc.perform(get("/acme/core-api.git/info/refs?service=git-receive-pack"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/x-git-receive-pack-advertisement"))
                .andExpect(header().string("Cache-Control", "no-cache, max-age=0, must-revalidate"));
    }
}
