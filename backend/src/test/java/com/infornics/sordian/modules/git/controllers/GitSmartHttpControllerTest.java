package com.infornics.sordian.modules.git.controllers;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.infornics.sordian.modules.git.services.GitProcessRunner;
import com.infornics.sordian.modules.git.services.GitQuarantineService;
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

    @MockBean
    private com.infornics.sordian.modules.git.services.GitPktLineParser pktLineParser;

    @MockBean
    private com.infornics.sordian.modules.git.services.GitInspectionService inspectionService;

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

    @Test
    @DisplayName("Should return Git receive-pack rejection when inspection service rejects proposed update")
    void shouldReturnGitRejectionWhenInspectionFails() throws Exception {
        File dummyRepo = File.createTempFile("sordian-test-reject", ".git");
        dummyRepo.delete();
        dummyRepo.mkdirs();
        dummyRepo.deleteOnExit();

        Mockito.when(quarantineService.getOrCreateQuarantineRepo("acme", "core-api"))
                .thenReturn(dummyRepo);

        com.infornics.sordian.modules.git.models.RefCommand cmd =
                com.infornics.sordian.modules.git.models.RefCommand.builder()
                        .oldOid("1111111111111111111111111111111111111111")
                        .newOid(com.infornics.sordian.modules.git.models.RefCommand.ZERO_OID)
                        .refName("refs/heads/main")
                        .build();

        Mockito.when(pktLineParser.parsePushCommands(Mockito.any()))
                .thenReturn(new com.infornics.sordian.modules.git.services.GitPktLineParser.ParseResult(
                        java.util.List.of(cmd),
                        new byte[0],
                        java.util.List.of()
                ));

        Mockito.when(inspectionService.validatePushCommands(Mockito.any(), Mockito.any()))
                .thenReturn(com.infornics.sordian.modules.git.models.PushValidationResult.reject(
                        "Branch deletion is denied by default.",
                        cmd
                ));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/acme/core-api.git/git-receive-pack")
                        .contentType("application/x-git-receive-pack-request")
                        .content("0000"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("unpack ok")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("ng refs/heads/main [SORDIAN REJECTED] Branch deletion is denied by default.")));
    }
}
