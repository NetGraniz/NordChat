package com.nordfjell.nordchat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class PreferenceStorageTest {
    private static final UUID A=UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID B=UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static PlayerPreferences.Snapshot hidden(){return new PlayerPreferences.Snapshot(false,false,false,Map.of(B,Long.MAX_VALUE),Set.of(B),Set.of(B));}
    public static void main(String[] args)throws Exception{
        String aliasSource="first: &a []\ncopy: *a\nquoted: '*a'\n";
        assert PreferenceStorage.normalizeEmptyAliases(aliasSource).equals("first: &a []\ncopy: []\nquoted: '*a'\n");
        Path directory=Files.createTempDirectory("nordchat-sqlite-");
        Path legacy=directory.resolve("players.yml"),database=directory.resolve("players.db");
        Files.writeString(legacy,"names:\n  "+A+": SyntheticA\nplayers:\n  "+A+":\n    chat-visible: false\n    private-messages-visible: false\n    death-messages-visible: false\n    hard-ignored: ["+B+"]\n    ignored-death-messages: ["+B+"]\n    temporary-ignored:\n      "+B+": 9223372036854775807\n");
        byte[] original=Files.readAllBytes(legacy);
        try(PreferenceStorage migrated=new PreferenceStorage(database,legacy)){
            assert migrated.name(A).equals("SyntheticA"); assert migrated.preferences(A).equals(hidden());
            assert java.util.Arrays.equals(original,Files.readAllBytes(legacy));
            for(int i=0;i<1000;i++){UUID id=new UUID(0,i+10);assert migrated.submit(id,"Test"+i,hidden());}
            assert migrated.pending()==1000; assert migrated.flush(1,true); assert migrated.pending()==0;
        }
        Files.writeString(legacy,"players: {}\n");
        try(PreferenceStorage restarted=new PreferenceStorage(database,legacy)){
            assert restarted.preferences(A).equals(hidden());
            for(int i=0;i<1000;i++)assert restarted.preferences(new UUID(0,i+10)).equals(hidden());
            assert restarted.submit(A,"RenamedA",null); assert restarted.flush(2,true);
        }
        try(PreferenceStorage restarted=new PreferenceStorage(database,legacy)){assert restarted.name(A).equals("RenamedA");}
        Path badDirectory=Files.createTempDirectory("nordchat-invalid-");
        Path bad=badDirectory.resolve("players.yml");Files.writeString(bad,"players:\n  invalid:\n    chat-visible: false\n");
        try{new PreferenceStorage(badDirectory.resolve("players.db"),bad);throw new AssertionError("Invalid YAML accepted");}
        catch(java.io.IOException expected){assert Files.readString(bad).contains("invalid");}
        System.out.println("ALL_PREFERENCE_TESTS_PASSED");
    }
}
