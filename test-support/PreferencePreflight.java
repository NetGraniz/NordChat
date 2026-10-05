import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
/** Offline constructor only; no workers, submissions or writes. Never prints names/preferences. */
public final class PreferencePreflight {
    public static void main(String[] args) throws Exception {
        Path file=Path.of(args[0]).toRealPath();
        if(Files.size(file)>16L*1024*1024)throw new IllegalStateException("File too large");
        byte[] before=Files.readAllBytes(file);
        Class<?> type=Class.forName("com.nordfjell.nordchat.PreferenceStorage");
        Constructor<?> constructor=type.getDeclaredConstructor(Path.class);constructor.setAccessible(true);
        Object storage=constructor.newInstance(file);
        Field count=type.getDeclaredField("playerCount");count.setAccessible(true);
        if(!Arrays.equals(before,Files.readAllBytes(file)))throw new IllegalStateException("File changed");
        System.out.println("STRICT_PREFERENCE_LOAD_OK accounts="+count.getInt(storage)+" fileUnchanged=true");
    }
}
