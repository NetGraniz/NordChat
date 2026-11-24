package com.nordfjell.nordchat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class PreferenceStorageTest {
    private static final UUID A = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID B = UUID.fromString("22222222-2222-4222-8222-222222222222");

    private static PlayerPreferences.Snapshot hidden() {
        return new PlayerPreferences.Snapshot(false, false, false, Map.of(B, Long.MAX_VALUE), Set.of(B), Set.of(B));
    }

    public static void main(String[] args) throws Exception {
        Path database = Files.createTempDirectory("nordchat-sqlite-").resolve("players.db");
        try (PreferenceStorage storage = new PreferenceStorage(database)) {
            assert storage.submit(A, "SyntheticA", hidden());
            for (int i = 0; i < 1000; i++) assert storage.submit(new UUID(0, i + 10), "Test" + i, hidden());
            assert storage.pending() == 1001;
            assert storage.flush(1, true);
            assert storage.pending() == 0;
        }
        try (PreferenceStorage restarted = new PreferenceStorage(database)) {
            assert restarted.name(A).equals("SyntheticA");
            assert restarted.preferences(A).equals(hidden());
            for (int i = 0; i < 1000; i++) assert restarted.preferences(new UUID(0, i + 10)).equals(hidden());
            assert restarted.submit(A, "RenamedA", null);
            assert restarted.flush(2, true);
        }
        try (PreferenceStorage restarted = new PreferenceStorage(database)) {
            assert restarted.name(A).equals("RenamedA");
        }
        System.out.println("ALL_PREFERENCE_TESTS_PASSED");
    }
}
