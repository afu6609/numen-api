package com.dwinovo.numen.mcp.server;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class McpServerChatPolicyTest {

    @Test
    void rejectsOnlyCommandShapedChatAtTheTransportBoundary() {
        assertTrue(McpServer.isSlashCommandChat("/time set day"));
        assertTrue(McpServer.isSlashCommandChat("   /weather clear"));
        assertFalse(McpServer.isSlashCommandChat("好，我去看看。"));
        assertFalse(McpServer.isSlashCommandChat("这是文本 /time set day"));
        assertFalse(McpServer.isSlashCommandChat(null));
    }
}
