package com.omni.user.service;

import com.omni.ai.prompt.PromptTemplate;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SupportPromptCompatibilityTest {
    private static final String CURRENT_SHA256 = "5abbedc3d107cd02bcc3dbbcd25536b347e9ed2ce3f000c29b916118f03ed871";

    @Test
    void keepsCurrentSystemPromptFingerprintIncludingWhitespace() throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(SupportKnowledgeBase.projectKnowledge().getBytes(StandardCharsets.UTF_8));
        StringBuilder hash = new StringBuilder();
        for (byte value : digest) {
            hash.append(String.format("%02x", value & 0xff));
        }
        assertEquals(CURRENT_SHA256, hash.toString());
    }

    @Test
    void usesVersionedResourceWithTheCurrentFingerprint() {
        PromptTemplate template = PromptTemplate.fromResource(SupportKnowledgeBase.class,
                "/prompts/support-local-v2.txt", "support-local", "v2", Set.of());
        assertEquals(CURRENT_SHA256, template.getVersion().getSha256());
        assertEquals(template.render(Map.of()), SupportKnowledgeBase.projectKnowledge());
    }
}
