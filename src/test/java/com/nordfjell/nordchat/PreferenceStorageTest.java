package com.nordfjell.nordchat;
import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public final class PreferenceStorageTest {
    private static int passed;
    private static final UUID A = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID B = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static Path file() throws IOException { return Files.createTempDirectory("nordchat-unit-").resolve("players.yml"); }
    private static void pass(String name) { passed++; System.out.println("PASS " + name); }
    private static PlayerPreferences.Snapshot hidden() {
        return new PlayerPreferences.Snapshot(false, false, false, Map.of(B, Long.MAX_VALUE), Set.of(B), Set.of(B));
    }
    private static void rejected(String text) throws Exception {
        Path f = file(); Files.writeString(f, text, StandardCharsets.UTF_8); byte[] before = Files.readAllBytes(f);
        try { new PreferenceStorage(f); throw new AssertionError("Invalid data accepted"); } catch (IOException expected) {}
        assert Arrays.equals(before, Files.readAllBytes(f));
    }
    public static void main(String[] args) throws Exception {
        Path f = file(); PreferenceStorage storage = new PreferenceStorage(f);
        assert !Files.exists(f); assert storage.submit(A, "SyntheticA", hidden());
        assert storage.flush(1, false); assert storage.pending() == 0;
        PreferenceStorage reloaded = new PreferenceStorage(f);
        assert reloaded.preferences(A).equals(hidden()); assert reloaded.name(A).equals("SyntheticA");
        pass("Atomic write/read preserves flags, lists, permanent expiry and names");

        assert reloaded.submit(A, "SyntheticA", hidden()); assert reloaded.pending() == 0; assert !reloaded.flush(1, false);
        pass("Identical name/preferences do not rewrite file");
        assert reloaded.submit(A, "RenamedA", null); reloaded.flush(1, false);
        assert new PreferenceStorage(f).preferences(A).equals(hidden()); pass("Name-only update preserves preference state");

        String root = "players:\n  " + A + ":\n";
        rejected(root + "    chat-visible: 'false'\n"); pass("Wrong flag type fails without overwriting");
        rejected("players:\n  invalid:\n    chat-visible: false\n"); pass("Malformed UUID fails without overwriting");
        rejected("players: [invalid]\n"); pass("Wrong root section type rejected");
        rejected("players: null\n"); pass("Explicit null state cannot silently reset preferences");
        rejected(root + "    temporary-ignored:\n      " + B + ": -1\n"); pass("Invalid ignore expiry rejected");
        rejected(root + "    hard-ignored: [" + B + ", " + B + "]\n"); pass("Duplicate list entries rejected");
        rejected(root + "    chat-visible: false\n    chat-visible: true\n"); pass("Duplicate YAML fields rejected");
        rejected("players: !!java.util.HashMap {}\n"); pass("Unsafe YAML tags rejected");
        rejected(root + "    unexpected: true\n"); pass("Unknown preference fields rejected");
        Path utf = file(); Files.write(utf, new byte[]{(byte)0xC3, 0x28});
        try { new PreferenceStorage(utf); throw new AssertionError(); } catch(IOException expected) {}
        pass("Malformed UTF-8 rejected");
        Path large = file(); Files.write(large, new byte[PreferenceStorage.MAX_BYTES + 1]);
        try { new PreferenceStorage(large); throw new AssertionError(); } catch(IOException expected) {}
        pass("File size limited before parser");

        Path legacy = file();
        Files.writeString(legacy, "names:\n  " + A + ": SyntheticA\n" + root
                + "    hard-ignored: []\n    temporary-ignored:\n      " + B + ": 1\n");
        PlayerPreferences preferences = PlayerPreferences.from(new PreferenceStorage(legacy).preferences(A));
        assert preferences.isChatVisible(); assert !preferences.isIgnoring(B, 2);
        assert preferences.temporaryIgnores().containsKey(B);
        pass("Legacy missing flags default true; expired ignore read does not mutate");
        var snapshot = preferences.snapshot(); preferences.hardIgnores().add(B);
        assert !snapshot.hardIgnores().contains(B);
        try { snapshot.temporaryIgnores().put(A, 1L); throw new AssertionError(); } catch(UnsupportedOperationException expected) {}
        pass("Snapshots are detached and immutable");

        AtomicInteger writes = new AtomicInteger();
        PreferenceStorage burst = new PreferenceStorage(file(), (path, bytes) -> writes.incrementAndGet());
        for (int i=0; i<1000; i++) assert burst.submit(new UUID(0, i+1), "Test"+i, hidden());
        assert burst.pending()==1000; assert burst.flush(1, false); assert writes.get()==1;
        for (int i=0; i<1000; i++) assert burst.submit(new UUID(0, i+1), "Test"+i, hidden());
        assert !burst.flush(2, false); pass("1000 mutations coalesce to one transaction; replay writes zero");
        PreferenceStorage bounded = new PreferenceStorage(file(), (path, bytes) -> {});
        for (int i=0; i<PreferenceStorage.MAX_PENDING; i++) assert bounded.submit(new UUID(0,i+1), "P"+i, hidden());
        assert !bounded.submit(new UUID(1,1), "Overflow", hidden());
        assert bounded.pending()==PreferenceStorage.MAX_PENDING;
        assert !bounded.problem().isEmpty(); bounded.flush(1,false);
        assert !bounded.submit(A, "AfterOverflow", hidden()); pass("Pending capacity bounded, overflow latched");

        Path fault = file(); Files.writeString(fault, "{}\n"); byte[] prior=Files.readAllBytes(fault);
        AtomicBoolean fail = new AtomicBoolean(true); AtomicInteger calls = new AtomicInteger();
        PreferenceStorage failure = new PreferenceStorage(fault,(path, bytes)->{
            calls.incrementAndGet(); if(fail.get()) throw new IOException("Synthetic fault"); Files.write(path,bytes);
        });
        failure.submit(A,"SyntheticA",hidden()); assert !failure.flush(100,false);
        assert Arrays.equals(prior,Files.readAllBytes(fault)); assert failure.pending()==1;
        assert !failure.flush(101,false); assert calls.get()==1;
        fail.set(false); assert failure.flush(5_000_000_101L,false);
        assert failure.problem().isEmpty() && failure.pending()==0; pass("Failed save retains dirty state/file and retries safely");

        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        PreferenceStorage slow = new PreferenceStorage(file(),(path, bytes)->{
            entered.countDown();
            try { if(!release.await(5,TimeUnit.SECONDS)) throw new IOException("Test timeout"); }
            catch(InterruptedException e){throw new IOException(e);}
        });
        slow.submit(A,"SyntheticA",hidden());
        Thread thread = new Thread(()->slow.flush(1,false)); thread.start();
        assert entered.await(3,TimeUnit.SECONDS);
        var visible = new PlayerPreferences().snapshot();
        long begin=System.nanoTime(); assert slow.submit(A,"SyntheticA",visible);
        assert slow.preferences(A).equals(visible); assert System.nanoTime()-begin < 1_000_000_000L;
        release.countDown(); thread.join(4000); assert !thread.isAlive();
        assert slow.pending()==1; assert slow.flush(2,false); assert slow.pending()==0;
        pass("Slow disk does not lock submissions; newer update is not acknowledged early");

        Set<UUID> oversized = new HashSet<>();
        for(int i=0;i<=PreferenceStorage.MAX_IGNORES;i++) oversized.add(new UUID(0,i));
        try { storage.submit(A,null,new PlayerPreferences.Snapshot(true,true,true,Map.of(),oversized,Set.of())); throw new AssertionError(); }
        catch(IllegalArgumentException expected){}
        pass("Runtime ignore list bound enforced");
        try(var paths=Files.list(f.getParent())){ assert paths.noneMatch(p->p.getFileName().toString().endsWith(".tmp")); }
        pass("Atomic temporary files cleaned");
        System.out.println("ALL_PREFERENCE_TESTS_PASSED count="+passed);
    }
}
