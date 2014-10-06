package lighthouse.files;

import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.protocols.payments.PaymentProtocolException;
import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableSet;
import javafx.beans.InvalidationListener;
import javafx.collections.*;
import lighthouse.LighthouseBackend;
import lighthouse.protocol.LHProtos;
import lighthouse.protocol.LHUtils;
import lighthouse.protocol.Project;
import lighthouse.threading.AffinityExecutor;
import lighthouse.threading.MarshallingObservers;
import lighthouse.threading.ObservableMirrors;
import net.jcip.annotations.GuardedBy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Executor;

import static com.google.common.base.Preconditions.*;
import static lighthouse.protocol.LHUtils.*;

/**
 * <p>Provides an observable list of projects and pledges that this app is managing, using the AppDirectory class.
 * Projects can be indirect: the app dir can contain text files containing the real path of the project. This is useful
 * to let the user gather pledges using the file system e.g. a shared folder on Google Drive/Dropbox/etc.</p>
 *
 * <p>Note that pledges can be in two places. One is the users wallet. That's the pledges they have made. The other
 * is on disk. That's the pledges other people have made, when in decentralised mode.</p>
 */
public class DiskManager {
    private static final Logger log = LoggerFactory.getLogger(DiskManager.class);

    public static final String PROJECT_FILE_EXTENSION = ".lighthouse-project";
    public static final String PLEDGE_FILE_EXTENSION = ".lighthouse-pledge";
    public static final String PROJECT_STATUS_FILENAME = "project-status.txt";

    // All private methods and private variables are used from this executor.
    private final AffinityExecutor executor;
    private final ObservableList<Project> projects;
    private final Map<Path, Project> projectsByPath;
    private final Map<Path, LHProtos.Pledge> pledgesByPath;
    // These are locked so other threads can reach in and read them without having to do cross-thread RPCs.
    @GuardedBy("this") private final ObservableMap<String, Project> projectsById;
    @GuardedBy("this") private final Map<Project, ObservableSet<LHProtos.Pledge>> pledges;
    private final List<Path> projectFiles;
    private final List<Path> projectsDirs;
    private DirectoryWatcher directoryWatcher;
    private final ObservableMap<String, LighthouseBackend.ProjectStateInfo> projectStates;
    private final boolean autoLoadProjects;

    /**
     * Creates a disk manager that reloads data from disk when a new project path is added or the directories change.
     * This object should be owned by the thread backing owningExecutor: changes will all be queued onto this
     * thread.
     */
    public DiskManager(AffinityExecutor owningExecutor, boolean autoLoadProjects) {
        // Initialize projects by selecting files matching the right name pattern and then trying to load, ignoring
        // failures (nulls).
        executor = owningExecutor;
        projects = FXCollections.observableArrayList();
        projectsById = FXCollections.observableHashMap();
        projectsByPath = new HashMap<>();
        projectStates = FXCollections.observableHashMap();
        pledgesByPath = new HashMap<>();
        pledges = new HashMap<>();
        projectFiles = new ArrayList<>();
        projectsDirs = new ArrayList<>();
        this.autoLoadProjects = autoLoadProjects;
        // Use execute() rather than executeASAP() so that if we're being invoked from the owning thread, the caller
        // has a chance to set up observers and the like before the thread event loops and starts loading stuff. That
        // way the observers will run for the newly loaded data.
        owningExecutor.execute(() -> uncheck(this::init));
    }

    private void init() throws IOException {
        executor.checkOnThread();
        if (Files.exists(getProjectPathsFile()))
            readProjectPathsFromDisk();
        // Reload them on the UI thread if any files show up behind our back, i.e. from Drive/Dropbox/being put there
        // manually by the user.
        directoryWatcher = createDirWatcher();
        loadAll();
    }

    private DirectoryWatcher createDirWatcher() {
        Set<Path> directories = new HashSet<>();
        for (Path path : projectFiles) {
            if (path.isAbsolute()) {
                if (Files.isRegularFile(path))
                    directories.add(path.getParent().normalize());
                else
                    log.error("Project path was not found, ignoring: {}", path);
            } else {
                // File name of a project stored in the app dir, so we don't watch that.
            }
        }
        directories.addAll(projectsDirs);
        return new DirectoryWatcher(ImmutableSet.copyOf(directories), this::onDirectoryChanged, executor);
    }

    private void onDirectoryChanged(Path path, WatchEvent.Kind<Path> kind) {
        executor.checkOnThread();
        boolean isProject = path.toString().endsWith(PROJECT_FILE_EXTENSION);
        boolean isPledge = path.toString().endsWith(PLEDGE_FILE_EXTENSION);
        // We model a change as a delete followed by an add.
        boolean isCreate = kind == StandardWatchEventKinds.ENTRY_CREATE || kind == StandardWatchEventKinds.ENTRY_MODIFY;
        boolean isDelete = kind == StandardWatchEventKinds.ENTRY_DELETE || kind == StandardWatchEventKinds.ENTRY_MODIFY;

        if (isProject && autoLoadProjects) {
            if (isDelete) {
                log.info("Project file deleted/modified: {}", path);
                Project project = projectsByPath.get(path);
                if (project != null) {
                    projects.remove(project);
                    projectsByPath.remove(path);
                    synchronized (this) {
                        projectsById.remove(project.getID());
                    }
                }
                if (kind == StandardWatchEventKinds.ENTRY_MODIFY) {
                    log.info("Reloading ...");
                    this.tryLoadProject(path);
                }
            }
            if (isCreate) {
                if (projectsDirs.contains(path.getParent())) {
                    log.info("New project found: {}", path);
                    this.tryLoadProject(path);
                }
            }
        } else if (isPledge) {
            if (isDelete) {
                LHProtos.Pledge pledge = pledgesByPath.get(path);
                if (pledge != null) {
                    log.info("Pledge file deleted/modified: {}", path);
                    synchronized (this) {
                        Project project = projectsById.get(pledge.getProjectId());
                        ObservableSet<LHProtos.Pledge> projectPledges = this.getPledgesFor(project);
                        checkState(projectPledges != null);  // Project should be in both sets or neither.
                        projectPledges.remove(pledge);
                    }
                    pledgesByPath.remove(path);
                } else {
                    log.error("Got delete event for a pledge we had not loaded, maybe missing project? {}", path);
                }
            }
            if (isCreate) {
                this.tryLoadPledge(path);
            }
        }
    }

    private void loadPledgesFromDisk(List<Path> files) throws IOException {
        executor.checkOnThread();
        for (Path path : files) {
            if (!path.toString().endsWith(PLEDGE_FILE_EXTENSION)) continue;
            tryLoadPledge(path);
        }
    }

    private void tryLoadPledge(Path path) {
        LHProtos.Pledge pledge = loadPledge(path);
        if (pledge != null) {
            Project project = getProjectById(pledge.getProjectId());
            if (project != null) {
                log.info("Loaded pledge from {}", path);
                pledgesByPath.put(path, pledge);
                getPledgesOrCreate(project).add(pledge);
            } else {
                // Projects are loaded first so this should not happen.
                log.error("Found pledge on disk we don't have the project for: {}", path);
            }
        } else {
            log.error("Unable to load pledge from {}", path);
        }
    }

    @Nullable
    private LHProtos.Pledge loadPledge(Path file) {
        executor.checkOnThread();
        log.info("Attempting to load {}", file);
        try (InputStream stream = Files.newInputStream(file)) {
            return LHProtos.Pledge.parseFrom(stream);
        } catch (IOException e) {
            log.error("Failed to load pledge from " + file, e);
            return null;
        }
    }

    private void readProjectPathsFromDisk() throws IOException {
        executor.checkOnThread();
        projectFiles.clear();
        Files.readAllLines(getProjectPathsFile()).forEach(line -> projectFiles.add(Paths.get(line)));
        log.info("{} project dirs read", projectFiles.size());
        for (Path dir : projectFiles) {
            log.info(dir.toString());
        }
    }

    private void writeProjectPathsFromDisk() throws IOException {
        executor.checkOnThread();
        log.info("Writing {}", getProjectPathsFile());
        Path path = getProjectPathsFile();
        Files.write(path, (Iterable<String>) projectFiles.stream().map(Path::toString)::iterator, Charsets.UTF_8);
    }

    private Path getProjectPathsFile() {
        return AppDirectory.dir().resolve("projects.txt");
    }

    private void loadAll() throws IOException {
        executor.checkOnThread();
        log.info("Updating all data from disk");
        for (Path file : projectFiles)
            loadProjectAndPledges(file);
        loadProjectStatuses();
        log.info("... disk data loaded");
    }

    private void loadProjectStatuses() throws IOException {
        Path path = AppDirectory.dir().resolve(PROJECT_STATUS_FILENAME);
        projectStates.addListener((InvalidationListener) x -> saveProjectStatuses());
        if (!Files.exists(path)) return;
        Properties properties = new Properties();
        try (InputStream stream = Files.newInputStream(path)) {
            properties.load(stream);
        }
        for (Object o : properties.keySet()) {
            String key = (String) o;
            String val = properties.getProperty(key);
            if (val.equals("OPEN")) {
                projectStates.put(key, new LighthouseBackend.ProjectStateInfo(LighthouseBackend.ProjectState.OPEN, null));
            } else {
                Sha256Hash claimedBy = new Sha256Hash(val);   // Treat as hex string.
                log.info("Project {} is marked as claimed by {}", key, claimedBy);
                projectStates.put(key, new LighthouseBackend.ProjectStateInfo(LighthouseBackend.ProjectState.CLAIMED, claimedBy));
            }
        }
    }

    private void saveProjectStatuses() {
        log.info("Saving project statuses");
        Path path = AppDirectory.dir().resolve(PROJECT_STATUS_FILENAME);
        Properties properties = new Properties();
        for (Map.Entry<String, LighthouseBackend.ProjectStateInfo> entry : projectStates.entrySet()) {
            String val = entry.getValue().state == LighthouseBackend.ProjectState.OPEN ? "OPEN" :
                    checkNotNull(entry.getValue().claimedBy).toString();
            properties.setProperty(entry.getKey(), val);
        }
        if (properties.isEmpty()) return;
        try (OutputStream stream = Files.newOutputStream(path)) {
            properties.store(stream, "Records which projects have been claimed by which transactions");
        } catch (IOException e) {
            // Should this be surfaced to the user? Probably they're out of disk space?
            log.error("Failed to save project status file!", e);
        }
    }

    private void loadProjectAndPledges(Path path) throws IOException {
        path = AppDirectory.dir().resolve(path);
        log.info("Loading project and associated pledges: {}", path.toString());
        if (!path.toString().endsWith(PROJECT_FILE_EXTENSION)) {
            log.error("Project path does not end in correct extension: {}", path.toString());
            return;
        }
        if (!tryLoadProject(path))
            return;
        List<Path> files = listDir(path.getParent().normalize());
        loadPledgesFromDisk(files);
    }

    private boolean tryLoadProject(Path path) {
        Project p = loadProject(path);
        if (p != null) {
            synchronized (this) {
                if (projectsById.containsKey(p.getID())) {
                    log.info("Already have project id {}, skipping load", p.getID());
                    return false;
                }
                projectsById.put(p.getID(), p);
            }
            projects.add(p);
            projectsByPath.put(path, p);
            if (!projectStates.containsKey(p.getID())) {
                // Assume new projects are open: we have no other way to tell for now (would require a block explorer
                // lookup to detect that the project came and went already. But we do remember even if the project
                // is deleted and came back.
                projectStates.put(p.getID(), new LighthouseBackend.ProjectStateInfo(LighthouseBackend.ProjectState.OPEN, null));
            }
        }
        return p != null;
    }

    @Nullable
    private static Project loadProject(Path from) {
        log.info("Attempting to load project file {}", from);
        try (InputStream is = Files.newInputStream(from)) {
            LHProtos.Project proto = LHProtos.Project.parseFrom(is);
            return new Project(proto);
        } catch (IOException e) {
            log.error("File appeared in directory but could not be read, ignoring: {}", e.getMessage());
            return null;
        } catch (PaymentProtocolException e) {
            // Don't know how to load this file!
            log.error("Failed reading file", e);
            return null;
        }
    }

    public Project saveProject(LHProtos.Project project, String fileID) throws IOException {
        // Probably on the UI thread here. Do the IO write on the UI thread to simplify error handling.
        final Project obj = unchecked(() -> new Project(project));
        final Path filename = Paths.get(fileID + PROJECT_FILE_EXTENSION);
        final Path path = AppDirectory.dir().resolve(filename + ".tmp");
        log.info("Saving project to: {}", path);
        // Do a write to a temp file name here to ensure a project file is not partially written and becomes partially
        // visible via directory notifications.
        try (OutputStream stream = new BufferedOutputStream(Files.newOutputStream(path))) {
            project.writeTo(stream);
        }
        Files.move(path, AppDirectory.dir().resolve(filename));
        addProjectFile(filename);
        return obj;
    }

    public void addProjectFile(Path file) {
        // file may be relative to the appdir (just a file name).
        final Path absolute = AppDirectory.dir().resolve(file);
        checkArgument(Files.isRegularFile(absolute));
        executor.executeASAP(() -> {
            projectFiles.add(file);
            ignoreAndLog(this::writeProjectPathsFromDisk);
            loadProjectAndPledges(absolute);
            directoryWatcher.stop();
            directoryWatcher = createDirWatcher();
        });
    }

    public ListChangeListener<Project> observeProjects(ListChangeListener<Project> listener, Executor e) {
        return MarshallingObservers.addListener(projects, listener, e);
    }

    public ObservableList<Project> mirrorProjects(AffinityExecutor executor) {
        if (executor == this.executor)
            return projects;
        else
            return ObservableMirrors.mirrorList(projects, executor);
    }

    @Nullable
    public synchronized Project getProjectById(String id) {
        return projectsById.get(id);
    }

    /** Returns an observable set of pledges for the project. */
    public synchronized ObservableSet<LHProtos.Pledge> getPledgesOrCreate(Project forProject) {
        ObservableSet<LHProtos.Pledge> result = pledges.get(forProject);
        if (result == null) {
            result = FXCollections.observableSet();
            pledges.put(forProject, result);
        }
        return result;
    }

    /** Returns an observable set of pledges if this project was found on disk, otherwise null. */
    @Nullable
    public synchronized ObservableSet<LHProtos.Pledge> getPledgesFor(Project forProject) {
        return pledges.get(forProject);
    }

    @Nullable
    public Project getProjectFromClaim(Transaction claim) {
        executor.checkOnThread();
        for (Project project : projects) {
            if (LHUtils.compareOutputsStructurally(claim, project))
                return project;
        }
        return null;
    }

    // TODO: Remove me when a new block doesn't require checking every new project.
    public Set<Project> getProjects() {
        return new HashSet<>(projects);
    }

    public void setProjectState(Project project, LighthouseBackend.ProjectStateInfo state) {
        executor.executeASAP(() -> projectStates.put(project.getID(), state));
    }

    public LighthouseBackend.ProjectStateInfo getProjectState(Project project) {
        executor.checkOnThread();
        return projectStates.get(project.getID());
    }

    public ObservableMap<String, LighthouseBackend.ProjectStateInfo> mirrorProjectStates(AffinityExecutor runChangesIn) {
        if (executor == runChangesIn)
            return projectStates;
        else
            return executor.fetchFrom(() -> ObservableMirrors.mirrorMap(projectStates, runChangesIn));
    }

    public void addProjectsDir(Path dir) {
        checkArgument(Files.isDirectory(dir));
        executor.executeASAP(() -> {
            projectsDirs.add(dir);
            for (Path path : LHUtils.listDir(dir)) {
                if (path.toString().endsWith(PROJECT_FILE_EXTENSION))
                    loadProjectAndPledges(path.toAbsolutePath());
            }
            directoryWatcher.stop();
            directoryWatcher = createDirWatcher();
        });
    }
}
