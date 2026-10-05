package com.nordfjell.nordchat;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.events.AliasEvent;
import static org.junit.jupiter.api.Assertions.*;

class PreferenceAliasRegressionTest {
    @TempDir Path directory;
    private static UUID id(int index) { return new UUID(0,index+1); }
    private static PlayerPreferences.Snapshot preference(int index) {
        return new PlayerPreferences.Snapshot(index%2==0,index%3==0,index%5==0,Map.of(),Set.of(),Set.of());
    }
    private static void noAliases(Path file) throws Exception {
        try (var reader=Files.newBufferedReader(file)) {
            for (var event : new Yaml().parse(reader)) assertFalse(event instanceof AliasEvent);
        }
    }
    @Test void thousandEmptyListsRoundTripAndSecondSave() throws Exception {
        Path file=directory.resolve("players.yml");
        PreferenceStorage store=new PreferenceStorage(file);
        for(int i=0;i<1000;i++) assertTrue(store.submit(id(i),"Test"+i,preference(i)));
        assertTrue(store.flush(1,true)); noAliases(file);
        PreferenceStorage loaded=new PreferenceStorage(file);
        for(int i=0;i<1000;i++) {
            assertEquals(preference(i),loaded.preferences(id(i)));
            assertEquals("Test"+i,loaded.name(id(i)));
            assertTrue(loaded.submit(id(i),"Renamed"+i,null));
        }
        assertTrue(loaded.flush(2,true)); noAliases(file);
        PreferenceStorage restarted=new PreferenceStorage(file);
        for(int i=0;i<1000;i++) {
            assertEquals(preference(i),restarted.preferences(id(i)));
            assertEquals("Renamed"+i,restarted.name(id(i)));
        }
    }
    @Test void legacyEmptyAliasesPreserveAllPreferencesWithoutWriting() throws Exception {
        Path file=directory.resolve("legacy.yml");
        StringBuilder yaml=new StringBuilder("# Unicode comment: 🧊\nnames:\n");
        for(int i=0;i<183;i++) yaml.append("  ").append(id(i)).append(": Test").append(i).append('\n');
        yaml.append("players:\n");
        for(int i=0;i<183;i++) {
            var prefs=preference(i);
            yaml.append("  ").append(id(i)).append(":\n    chat-visible: ").append(prefs.chatVisible())
                .append("\n    private-messages-visible: ").append(prefs.privateMessagesVisible())
                .append("\n    death-messages-visible: ").append(prefs.deathMessagesVisible())
                .append("\n    hard-ignored: ").append(i==0?"&empty []":"*empty")
                .append("\n    ignored-death-messages: *empty\n    temporary-ignored: ").append(i==0?"&emptyMap {}":"*emptyMap").append('\n');
        }
        Files.writeString(file,yaml); byte[] original=Files.readAllBytes(file);
        PreferenceStorage loaded=new PreferenceStorage(file);
        assertArrayEquals(original,Files.readAllBytes(file));
        for(int i=0;i<183;i++) {
            assertEquals(preference(i),loaded.preferences(id(i)));
            assertEquals("Test"+i,loaded.name(id(i)));
        }
        assertTrue(loaded.submit(id(0),"Renamed0",null)); assertTrue(loaded.flush(1,true)); noAliases(file);
        PreferenceStorage restarted=new PreferenceStorage(file);
        for(int i=0;i<183;i++) assertEquals(preference(i),restarted.preferences(id(i)));
    }
    @Test void nonEmptyAliasLimitStillFailsClosedWithoutWriting() throws Exception {
        Path file=directory.resolve("nonempty.yml");
        StringBuilder yaml=new StringBuilder("players:\n");
        for(int i=0;i<30;i++) yaml.append("  ").append(id(i)).append(":\n    hard-ignored: ")
            .append(i==0?"&list ["+id(100)+"]":"*list").append('\n');
        Files.writeString(file,yaml); byte[] original=Files.readAllBytes(file);
        assertThrows(IOException.class,()->new PreferenceStorage(file));
        assertArrayEquals(original,Files.readAllBytes(file));
    }
    @Test void quotedStringsCommentsAndRedefinedAnchorsAreNotReplaced() {
        String source="# 🧊 *a\nfirst: &a []\nquoted: '*a'\ncopy: *a\nchanged: &a [x]\nlater: *a\n";
        assertEquals(source.replace("copy: *a","copy: []"),PreferenceStorage.normalizeEmptyAliases(source));
    }
    @Test void explicitUnsafeTaggedEmptyCollectionIsNotNormalized() {
        String source="first: &a !!java.util.ArrayList []\ncopy: *a\n";
        assertEquals(source,PreferenceStorage.normalizeEmptyAliases(source));
    }
    @Test void recursiveAliasesAreNotNormalized() {
        String source="first: &a [*a]\ncopy: *a\n";
        assertEquals(source,PreferenceStorage.normalizeEmptyAliases(source));
    }
}
