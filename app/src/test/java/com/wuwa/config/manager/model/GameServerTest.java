package com.wuwa.config.manager.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public final class GameServerTest {
    @Test
    public void packageLookupAndPathsAreExact() {
        GameServer server = GameServer.fromPackageName("com.kurogame.mingchao.bilibili");
        assertEquals(GameServer.CHINA_BILIBILI, server);
        assertEquals(
                "/storage/emulated/0/Android/data/com.kurogame.mingchao.bilibili/files/UE4Game/Client/Client/Saved/Config/Android",
                server.getConfigDirectory());
        assertEquals(
                "/storage/emulated/0/Download/Wuwa CFBP/Backup/com.kurogame.mingchao.bilibili",
                server.getBackupDirectory());
        assertEquals(
                "/storage/emulated/0/Download/Wuwa CFBP/ManualBackup/com.kurogame.mingchao.bilibili",
                server.getManualBackupDirectory());
        assertEquals(
                "/storage/emulated/0/Download/鸣潮画质助手/Backup/com.kurogame.mingchao.bilibili",
                server.getLegacyBackupDirectory());
        assertNull(GameServer.fromPackageName("not.a.game"));
    }
}
