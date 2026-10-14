package cn.homewatch;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Bounded private login history. Passwords, bearer tokens and camera addresses never enter logs. */
final class RemoteAuditLog {
    private final Path file;
    RemoteAuditLog(Path directory) { file=directory.resolve("remote-login.log"); }
    synchronized void record(String event,String username,String client) {
        try {
            if(Files.exists(file)&&Files.size(file)>1_048_576){
                Files.deleteIfExists(file.resolveSibling("remote-login.3.log"));
                for(int i=2;i>=1;i--){Path before=file.resolveSibling("remote-login."+i+".log");if(Files.exists(before))Files.move(before,file.resolveSibling("remote-login."+(i+1)+".log"),StandardCopyOption.REPLACE_EXISTING);}
                Files.move(file,file.resolveSibling("remote-login.1.log"),StandardCopyOption.REPLACE_EXISTING);
            }
            Files.writeString(file,RemoteJson.encode(Map.of("time",Instant.now().toString(),"event",event,"username",Objects.requireNonNullElse(username,""),"client",Objects.requireNonNullElse(client,"")))+"\n",StandardCharsets.UTF_8,StandardOpenOption.CREATE,StandardOpenOption.APPEND);
        } catch(IOException ignored) { /* Logging must not interrupt the recorder or authentication response. */ }
    }
    synchronized List<String> recent() throws IOException {
        if(!Files.exists(file))return List.of();List<String> lines=Files.readAllLines(file,StandardCharsets.UTF_8);
        return List.copyOf(lines.subList(Math.max(0,lines.size()-100),lines.size()));
    }
}
