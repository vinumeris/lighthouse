package lighthouse;

import com.google.common.collect.*;

import java.util.*;

/**
 * Hard-coded list of project servers so the app can randomly pick between them and load balance the work.
 */
public class ServerList {
    // This would be so much easier and less verbose with Kotlin ...

    public enum SubmitType {
        EMAIL,
        WEB
    }

    public static class Entry {
        public final String hostName;
        public final String submitAddress;
        public final String instructions;
        public final SubmitType submitType;

        public Entry(String hostName, String submitAddress, String instructions, SubmitType submitType) {
            this.hostName = hostName;
            this.submitAddress = submitAddress;
            this.instructions = instructions;
            this.submitType = submitType;
        }
    }

    public static final List<Entry> servers = ImmutableList.of(
            new Entry("vinumeris.com", "project-hosting@vinumeris.com", "Submission via email. Project must be legal under Swiss and UK law.", SubmitType.EMAIL),
            new Entry("lighthouse.onetapsw.com", "lighthouse-projects@onetapsw.com", "Submission via email. Project must be legal under US law.", SubmitType.EMAIL),
            new Entry("lighthouseprojects.io", "projects@lighthouseprojects.io", "Submission via email. Project must be legal under New Zealand law.", SubmitType.EMAIL),
            new Entry("lighthouse.bitseattle.com", "https://lighthouse.bitseattle.com/lighthouse-projects/upload/", "Submission via the web. Project must be legal under US law.", SubmitType.WEB)
    );
    public static final Map<String, Entry> hostnameToServer;

    static {
        ImmutableMap.Builder<String, Entry> builder = ImmutableMap.builder();
        for (Entry server : servers) builder.put(server.hostName, server);
        hostnameToServer = builder.build();
    }

    public static Entry pickRandom() {
        return servers.get((int) (Math.random() * servers.size()));
    }
}
