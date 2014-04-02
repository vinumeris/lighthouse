package lighthouse.utils;

import com.google.protobuf.ByteString;
import javafx.concurrent.Task;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;

public class DownloadProgress extends Task<ByteString> {
    private final URL url;
    private long expectedBytes;

    public DownloadProgress(URL url) {
        this.url = url;
    }

    private class ProgressCalculatingStream extends FilterInputStream {
        private long readSoFar = 0;

        public ProgressCalculatingStream(InputStream in) {
            super(in);
        }

        private void update() {
            if (expectedBytes == -1) return;  // HTTP server doesn't tell us how big the file is :(
            updateProgress(readSoFar, expectedBytes);
        }

        @Override
        public int read() throws IOException {
            int result = super.read();
            if (result != -1) {
                readSoFar += result;
                update();
            }
            return result;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int result = super.read(b, off, len);
            if (result != -1) {
                readSoFar += result;
                update();
            }
            return result;
        }
    }

    @Override
    protected ByteString call() throws Exception {
        final URLConnection connection = url.openConnection();
        connection.connect();
        expectedBytes = connection.getContentLengthLong();
        return ByteString.readFrom(new ProgressCalculatingStream(connection.getInputStream()));
    }
}
