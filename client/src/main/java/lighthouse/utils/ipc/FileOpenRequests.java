package lighthouse.utils.ipc;

import javafx.application.*;
import lighthouse.*;
import lighthouse.files.*;

import java.io.*;
import java.net.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;

import static lighthouse.protocol.LHUtils.*;

/**
 * Code to manage what happens when a user double clicks a file in their explorer. Either we load ourselves and queue
 * the file for import, or we have to send a message to the existing app instance.
 */
public class FileOpenRequests {
    public static boolean isAlreadyRunning() {
        RandomAccessFile file = null;
        try {
            Path path = AppDirectory.dir().resolve(Main.APP_NAME + ".spvchain");
            if (!Files.exists(path))
                return false;
            file = new RandomAccessFile(path.toFile(), "rw");
            FileLock lock = file.getChannel().tryLock();
            if (lock == null)
                return true;
            lock.release();
            return false;
        } catch (IOException e) {
            return false;
        } finally {
            if (file != null) uncheck(file::close);
        }
    }

    @SuppressWarnings("InfiniteLoopStatement")
    public static boolean requestFileOpen(Application.Parameters params, List<Path> filesToOpen) {
        if (isMac()) return false;   // Mac stuff is handled later.
        for (String arg : params.getUnnamed()) {
            if (arg.startsWith("-")) continue;
            Path path = Paths.get(arg);
            if (Files.exists(path)) filesToOpen.add(path);
        }
        if (!isAlreadyRunning()) {
            // Listen for file open requests.
            Thread thread = new Thread(() -> {
                try {
                    ServerSocket ss = new ServerSocket(22661, 0, InetAddress.getLoopbackAddress());
                    while (true) {
                        Socket socket = ss.accept();
                        DataInputStream stream = new DataInputStream(socket.getInputStream());
                        try {
                            while (true) {
                                String path = stream.readUTF();
                                Platform.runLater(() -> {
                                    Main.instance.mainStage.toFront();
                                    MainWindow.overviewActivity.handleOpenedFile(new File(path));
                                });
                            }
                        } catch (EOFException e) {
                            // End of file set.
                        }
                        socket.close();
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            });
            thread.setName("File open request listener");
            thread.setDaemon(true);
            thread.start();
            return false;  // Keep loading.
        }
        // App is already running and not on Mac, so try and make the app notice.
        try {
            Socket client = new Socket(InetAddress.getLoopbackAddress(), 22661);
            DataOutputStream dos = new DataOutputStream(client.getOutputStream());
            for (Path path : filesToOpen) dos.writeUTF(path.toAbsolutePath().toString());
            client.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return true;
    }

    public static void handleMacFileOpenRequests() {
        // This is only for MacOS, where the OS single instances us by default and sends us a message at startup to ask
        // us to open a file. It requires internal APIs.
        com.sun.glass.ui.Application app = com.sun.glass.ui.Application.GetApplication();
        com.sun.glass.ui.Application.EventHandler old = app.getEventHandler();
        app.setEventHandler(new com.sun.glass.ui.Application.EventHandler() {
            @Override
            public void handleQuitAction(com.sun.glass.ui.Application app, long time) {
                old.handleQuitAction(app, time);
            }

            @Override
            public boolean handleThemeChanged(String themeName) {
                return old.handleThemeChanged(themeName);
            }

            @Override
            public void handleOpenFilesAction(com.sun.glass.ui.Application app, long time, String[] files) {
                for (String strPath : files) {
                    if (strPath.equals("com.intellij.rt.execution.application.AppMain"))
                        continue;   // Only happens in dev environment.
                    Main.log.info("OS is requesting that we open " + strPath);
                    Platform.runLater(() -> {
                        MainWindow.overviewActivity.handleOpenedFile(new File(strPath));
                    });
                }
            }
        });
    }
}
