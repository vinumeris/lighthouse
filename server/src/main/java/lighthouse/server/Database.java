package lighthouse.server;

import com.google.common.collect.*;
import lighthouse.protocol.*;
import org.slf4j.*;

import java.util.*;

public class Database {
    private static final Logger log = LoggerFactory.getLogger(Database.class);
    private static Database database = new Database();

    public static class Row {
        public final LHProtos.Pledge pledge;
        public boolean valid;

        public Row(LHProtos.Pledge pledge) {
            this.pledge = pledge;
        }
    }
    private final Multimap<Project, Row> pledges = HashMultimap.create();

    public synchronized Collection<Row> getPledges(Project project) {
        return new ArrayList<>(pledges.get(project));   // Returns empty collection if no such project.
    }

    public synchronized void addPledge(LHProtos.Pledge pledge, Project project) {
        log.info("Storing pledge for '{}': {}", project.getTitle(), pledge);
        pledges.put(project, new Row(pledge));
    }

    public synchronized long getPledgedValue(Project project) {
        Collection<Row> rows = getPledges(project);
        return rows.stream().mapToLong(row -> row.pledge.getPledgeDetails().getTotalInputValue()).sum();
    }

    public synchronized void removePledge(Project project, LHProtos.Pledge pledge) {
        pledges.get(project).removeIf(row -> row.pledge.equals(pledge));
    }

    public static Database getInstance() {
        return database;
    }
}
