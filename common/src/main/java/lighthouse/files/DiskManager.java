package lighthouse.files;

import com.google.common.base.*;
import com.google.common.collect.*;
import javafx.beans.*;
import javafx.collections.*;
import lighthouse.*;
import lighthouse.protocol.*;
import lighthouse.threading.*;
import net.jcip.annotations.*;
import org.bitcoinj.core.*;
import org.slf4j.*;

import javax.annotation.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

import static com.google.common.base.Preconditions.*;
import static lighthouse.protocol.LHUtils.*;

/**
 * Provides an observable list of projects and pledges that this app is managing, using the AppDirectory class.
 * Projects can be indirect: the app dir can contain text files containing the real path of the project. This is useful
 * to let the user gather pledges using the file system e.g. a shared folder on Google Drive/Dropbox/etc.
 *
 * Note that pledges can be in two places. One is the users wallet. That's the pledges they have made. The other
 * is on disk. That's the pledges other people have made, when in decentralised mode.
 *
 * The logic implemented here is a bit complicated:
 *
 *  - Projects and pledges are automatically imported when they are dropped into the app directory. The app will copy
 *    project files to the app directory for the user so if the original file is deleted it doesn't disappear.
 *  - Pledges are automatically loaded from disk from the directory that a project was originally imported from in
 *    client mode.
 *  - The user is allowed to delete / rename any directories other than the app directory at any time and we have to
 *    handle that.
 *
 * The projects.txt file stores a list of paths. If a path is a file that ends with .lighthouse-project, it is loaded.
 * Paths can be just filenames, in that case they are expected to be relative to the app directory. If it is a path
 * to a directory then all pledges there will be loaded and the directory will be watched. The app directory is
 * always watched.
 */
public class DiskManager {
    private static final Logger log = LoggerFactory.getLogger(DiskManager.class);

    public static final String PROJECT_FILE_EXTENSION = ".lighthouse-project";
    public static final String PLEDGE_FILE_EXTENSION = ".lighthouse-pledge";
    public static final String PROJECT_STATUS_FILENAME = "project-status.txt";
    // For historical reasons this is called projects.txt although it contains paths of directories to watch for
    // pledges. Older files may also contain absolute file paths to project files.
    public static final String PLEDGE_PATHS_FILENAME = "projects.txt";

    // All private methods and private variables are used from this executor.
    private final AffinityExecutor.ServiceAffinityExecutor executor;
    private final ObservableList<Project> projects;
    private final Map<Path, Project> projectsByPath;
    private final Map<Path, LHProtos.Pledge> pledgesByPath;
    // These are locked so other threads can reach in and read them without having to do cross-thread RPCs.
    @GuardedBy("this") private final ObservableMap<String, Project> projectsById;
    @GuardedBy("this") private final Map<Project, ObservableSet<LHProtos.Pledge>> pledges;
    private final List<Path> pledgePaths;
    private final NetworkParameters params;
    private DirectoryWatcher directoryWatcher;

    // Ordered map: the ordering is needed to keep the UI showing projects in import order instead of whatever order
    // is returned by the disk file system. Keys are hex hashes (project.getId()).
    private final ObservableMap<String, LighthouseBackend.ProjectStateInfo> projectStates;
    private final LinkedHashMap<String, LighthouseBackend.ProjectStateInfo> projectStatesMap;

    /**
     * Creates a disk manager that reloads data from disk when a new project path is added or the directories change.
     * This object should be owned by the thread backing owningExecutor: changes will all be queued onto this
     * thread.
     */
    public DiskManager(NetworkParameters params, AffinityExecutor.ServiceAffinityExecutor owningExecutor) {
        // Initialize projects by selecting files matching the right name pattern and then trying to load, ignoring
        // failures (nulls).
        this.params = params;
        executor = owningExecutor;
        projects = FXCollections.observableArrayList();
        projectsById = FXCollections.observableHashMap();
        projectsByPath = new HashMap<>();
        projectStatesMap = new LinkedHashMap<>();
        projectStates = FXCollections.observableMap(projectStatesMap);
        pledgesByPath = new HashMap<>();
        pledges = new HashMap<>();
        pledgePaths = new ArrayList<>();
        // Use execute() rather than executeASAP() so that if we're being invoked from the owning thread, the caller
        // has a chance to set up observers and the like before the thread event loops and starts loading stuff. That
        // way the observers will run for the newly loaded data.
        owningExecutor.execute(() -> uncheck(this::init));
    }

    private void init() throws IOException {
        executor.checkOnThread();
        if (Files.exists(getPledgePathsFile()))
            readPledgePaths();
        // Reload them on the UI thread if any files show up behind our back, i.e. from Drive/Dropbox/being put there
        // manually by the user.
        loadAll();
        directoryWatcher = createDirWatcher();
    }

    public void shutdown() {
        directoryWatcher.stop();
    }

    private DirectoryWatcher createDirWatcher() {
        Set<Path> directories = new HashSet<>();
        // We always watch the app directory.
        directories.add(AppDirectory.dir());
        // We also watch the list of origin directories where serverless projects were imported from.
        for (Path path : pledgePaths) {
            if (!path.isAbsolute()) continue;    // Old projects.txt that has names of imported projects in it.
            if (Files.isDirectory(path))
                directories.add(path);
            else if (path.toString().endsWith(PROJECT_FILE_EXTENSION))    // For backwards compat: watch origin dirs recorded as paths to project files.
                directories.add(path.getParent());
        }

        return new DirectoryWatcher(ImmutableSet.copyOf(directories), this::onDirectoryChanged, executor);
    }

    private void onDirectoryChanged(Path path, WatchEvent.Kind<Path> kind) {
        executor.checkOnThread();
        boolean isProject = path.toString().endsWith(PROJECT_FILE_EXTENSION);
        boolean isPledge = path.toString().endsWith(PLEDGE_FILE_EXTENSION);
        boolean isCreate = kind == StandardWatchEventKinds.ENTRY_CREATE;
        boolean isDelete = kind == StandardWatchEventKinds.ENTRY_DELETE;
        boolean isModify = kind == StandardWatchEventKinds.ENTRY_MODIFY;

        if (isProject || isPledge)
            log.info("{} -> {}", path, kind);

        // Project files are only auto loaded from the app directory. If the user downloads a serverless project to their
        // Downloads folder, imports it, then downloads a second project, we don't want it to automatically appear.
        //
        // TODO: This is all a load of crap. Windows especially has weird habits when it comes to reporting file changes.
        // We should just scrap file watching for projects at least, and do things the old fashioned way.
        if (isProject && path.getParent().equals(AppDirectory.dir())) {
            if (isDelete || isModify) {
                log.info("Project file deleted/modified: {}", path);
                Project project = projectsByPath.get(path);
                if (project != null) {
                    if (kind == StandardWatchEventKinds.ENTRY_MODIFY) {
                        log.info("Project file modified, reloading ...");
                        tryLoadProject(path, projects.indexOf(project));
                    } else {
                        log.info("Project file deleted, removing ...");
                        projects.remove(project);
                        projectsByPath.remove(path);
                        synchronized (this) {
                            projectsById.remove(project.getID());
                        }
                    }
                } else if (isModify) {
                    log.info("Project file modified, but we don't know about it: last load might have failed. Retrying");
                    this.tryLoadProject(path);
                }
            } else if (isCreate) {
                log.info("New project found: {}", path);
                this.tryLoadProject(path);
            }
        } else if (isPledge) {
            if (isDelete || isModify) {
                LHProtos.Pledge pledge = pledgesByPath.get(path);
                if (pledge != null) {
                    log.info("Pledge file deleted/modified: {}", path);
                    synchronized (this) {
                        Project project = projectsById.get(pledge.getPledgeDetails().getProjectId());
                        ObservableSet<LHProtos.Pledge> projectPledges = this.getPledgesFor(project);
                        checkNotNull(projectPledges);  // Project should be in both sets or neither.
                        projectPledges.remove(pledge);
                    }
                    pledgesByPath.remove(path);
                } else {
                    log.error("Got delete event for a pledge we had not loaded, maybe missing project? {}", path);
                }
            }
            if (isCreate || isModify) {
                this.tryLoadPledge(path);
            }
        }
    }

    private void loadPledgesFromDirectory(Path directory) throws IOException {
        executor.checkOnThread();
        for (Path path : LHUtils.listDir(directory)) {
            if (!path.toString().endsWith(PLEDGE_FILE_EXTENSION)) continue;
            tryLoadPledge(path);
        }
    }

    private void tryLoadPledge(Path path) {
        LHProtos.Pledge pledge = loadPledge(path);
        if (pledge != null) {
            Project project = getProjectById(pledge.getPledgeDetails().getProjectId());
            if (project != null) {
                pledgesByPath.put(path, pledge);
                getPledgesOrCreate(project).add(pledge);
            } else {
                // TODO: This can happen if we're importing a project that already has pledges next to the file.
                // In that case, the app will copy the project into the app dir, then ask us to watch the origin dir
                // for pledges, but then the project load and scanning the origin dir are racing. So we need to
                // just put these pledges to one side and try again next time we find a new project.
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

    private void readPledgePaths() throws IOException {
        executor.checkOnThread();
        pledgePaths.clear();
        Files.readAllLines(getPledgePathsFile()).forEach(line -> pledgePaths.add(Paths.get(line)));
        log.info("{} project dirs read", pledgePaths.size());
        for (Path dir : pledgePaths) {
            log.info(dir.toString());
        }
    }

    private void writePledgePaths() throws IOException {
        executor.checkOnThread();
        log.info("Writing {}", getPledgePathsFile());
        Path path = getPledgePathsFile();
        Files.write(path, (Iterable<String>) pledgePaths.stream().map(Path::toString)::iterator, Charsets.UTF_8);
    }

    private Path getPledgePathsFile() {
        return AppDirectory.dir().resolve(PLEDGE_PATHS_FILENAME);
    }

    private void loadAll() throws IOException {
        executor.checkOnThread();
        log.info("Updating all data from disk");
        loadProjectStatuses();
        List<String> ids = new ArrayList<>(projectStatesMap.keySet());
        for (Path path : LHUtils.listDir(AppDirectory.dir())) {
            if (!path.toString().endsWith(PROJECT_FILE_EXTENSION))
                continue;
            if (!Files.isRegularFile(path))
                continue;
            if (tryLoadProject(path) == null)
                log.warn("Failed to load project {}", path);
        }
        projects.sort(new Comparator<Project>() {
            @Override
            public int compare(Project o1, Project o2) {
                int o1i = ids.indexOf(o1.getID());
                int o2i = ids.indexOf(o2.getID());
                // Project might have appeared on disk when we were not running and thus not have a status. This should
                // not happen in GUI mode unless the user dicks around with our private app directory so we can just
                // allow an unstable sort in this case. For servers it is expected but they don't care about the order.
                if (o1i == -1)
                    o1i = Integer.MAX_VALUE;
                if (o2i == -1)
                    o2i = Integer.MAX_VALUE;
                return Integer.compare(o1i, o2i);
            }
        });
        // Load pledges from each project path.
        loadPledgesFromDirectory(AppDirectory.dir());
        for (Path path : pledgePaths) {
            if (!Files.isDirectory(path)) continue;    // Can be from an old version or deleted by user.
            loadPledgesFromDirectory(path);
        }
        log.info("... disk data loaded");
    }

    private void loadProjectStatuses() throws IOException {
        Path path = AppDirectory.dir().resolve(PROJECT_STATUS_FILENAME);
        projectStates.addListener((InvalidationListener) x -> saveProjectStatuses());
        if (!Files.exists(path)) return;
        // Parse, paying attention to ordering.
        List<String> lines = Files.readAllLines(path);
        for (String line : lines) {
            if (line.startsWith("#")) continue;    // Backwards compat.
            List<String> parts = Splitter.on("=").splitToList(line);
            String key = parts.get(0);
            String val = parts.get(1);
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
        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, LighthouseBackend.ProjectStateInfo> entry : projectStates.entrySet()) {
            String val = entry.getValue().state == LighthouseBackend.ProjectState.OPEN ? "OPEN" : checkNotNull(entry.getValue().claimedBy).toString();
            lines.add(entry.getKey() + "=" + val);
        }
        uncheck(() -> Files.write(path, lines));
    }

    @Nullable
    public Project tryLoadProject(Path path) {
        return tryLoadProject(path, -1);
    }

    @Nullable
    public Project tryLoadProject(Path path, int indexToReplace) {
        executor.checkOnThread();
        Project p = loadProject(path);
        if (p != null) {
            synchronized (this) {
                Project preExisting = projectsById.get(p.getID());
                if (preExisting != null) {
                    if (indexToReplace < 0) {
                        log.info("Already have project id {}, skipping load", p.getID());
                        return preExisting;
                    }
                    if (preExisting.equals(p)) {
                        // This can happen on Windows: the OS tells us the file was modified, but then we load it and
                        // discover it's really not different at all. This seems to happen a lot just after a file was
                        // created. Hack: to avoid weird races and problems elsewhere like the UI trying to update a
                        // project ui widget that is waiting for an animation to finish, we just ignore this here.
                        log.info("Got bogus project modify notification, ignoring");
                        return null;   // Not used.
                    }
                }
                projectsById.put(p.getID(), p);
            }
            if (indexToReplace >= 0) {
                projects.set(indexToReplace, p);
                log.info("Replaced project at index {} with newly loaded project", indexToReplace);
            } else {
                projects.add(p);
            }
            projectsByPath.put(path, p);
            if (!projectStates.containsKey(p.getID())) {
                // Assume new projects are open: we have no other way to tell for now: would require a block explorer
                // lookup to detect that the project came and went already. But we do remember even if the project
                // is deleted and came back.
                projectStates.put(p.getID(), new LighthouseBackend.ProjectStateInfo(LighthouseBackend.ProjectState.OPEN, null));
            }
        }
        return p;
    }

    @Nullable
    private Project loadProject(Path from) {
        log.info("Attempting to load project file {}", from);
        try (InputStream is = Files.newInputStream(from)) {
            LHProtos.Project proto = LHProtos.Project.parseFrom(is);
            Project project = new Project(proto);
            if (!project.getParams().equals(params)) {
                log.warn("Ignoring project with mismatched network params: {} vs {}", project.getParams(), params);
                return null;
            }
            return project;
        } catch (Exception e) {
            log.error("File appeared in directory but could not be read, ignoring: {}", e.getMessage());
            return null;
        }
    }

    public Project saveProject(LHProtos.Project project, String fileID) throws IOException {
        // Probably on the UI thread here. Do the IO write on the UI thread to simplify error handling.
        final Project obj = unchecked(() -> new Project(project));
        final Path filename = Paths.get(obj.getSuggestedFileName());
        final Path path = AppDirectory.dir().resolve(filename + ".tmp");
        log.info("Saving project to: {}", path);
        // Do a write to a temp file name here to ensure a project file is not partially written and becomes partially
        // visible via directory notifications.
        try (OutputStream stream = new BufferedOutputStream(Files.newOutputStream(path))) {
            project.writeTo(stream);
        }
        // This should trigger a directory change notification that loads the project.
        if (Files.exists(AppDirectory.dir().resolve(filename)))
            log.info("... and replacing");
        Files.move(path, AppDirectory.dir().resolve(filename), StandardCopyOption.REPLACE_EXISTING);
        return obj;
    }

    /** Adds a directory that will be watched for pledge files. */
    public void addPledgePath(Path dir) {
        checkArgument(Files.isDirectory(dir));
        executor.executeASAP(() -> {
            log.info("Adding pledge path {}", dir);
            pledgePaths.add(dir);
            ignoreAndLog(this::writePledgePaths);
            loadPledgesFromDirectory(dir);
            directoryWatcher.stop();
            directoryWatcher = createDirWatcher();
        });
    }

    public void observeProjects(ListChangeListener<Project> listener) {
        projects.addListener(listener);
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
}
