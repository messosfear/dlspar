/*
 A simple multi-threaded downloader "Limny" using java.io.File and no lambda expressions.
 Usage:
 java Limny <url>

 Behavior:
 - Queries the remote resource to get its Content-Length.
 - Splits the file into 6 (as-equal-as-possible) byte ranges.
 - Downloads each range in its own thread using HTTP Range requests.
 - If the server doesn't support Range requests or Content-Length is unknown,
 falls back to a single-threaded download.
 - Saves each part as a separate file in a temporary directory and DOES NOT
 merge or delete the parts (per request).
 - No lambda expressions or method references are used.
 */

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class Limny {
    private static final int PARTS = 6;
    private static final int BUFFER_SIZE = 64 * 1024; // 64 KB
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 30_000;

    public static void main(String[] args) {
        if (args.length != 1) {
            System.err.println("Usage: java Limny <url>");
            System.exit(2);
        }

        String urlStr = "https://freedl.samfrew.com/1e8322f7c8294296/61084feb9490d07e3d96109746cf8c444f4b43f373d6087f916a5b2bc1f089bad7f616d0b33b871ac7b9ace4caad9c86ecde39a6b320d21a5a244fa89bbf7c492ea7f150c91605b4c8822b123b1dfa0e43cef194e1f2fe4517bba002ad5f15cf2d552aaa82dd8a349cdc4cf4388af49153803e6cfb3c1db0c4a4a1769b71f5034edbf7d3cbd97db6e3e2ee58a280da35/EUX-A155FXXU7DYK1-20251127201751.zip";
        // args[0];
        try {
            URL url = new URL(urlStr);
            new Limny().download(url);
        } catch (MalformedURLException e) {
            System.err.println("Invalid URL: " + urlStr);
            System.exit(3);
        } catch (Exception e) {
            System.err.println("Download failed: " + e.getMessage());
            e.printStackTrace(System.err);
            System.exit(1);
        }
    }

    public void download(final URL url) throws Exception {
        System.out.println("Starting download: " + url);

        // 1) Obtain content length with a HEAD request
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("HEAD");
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setInstanceFollowRedirects(true);
        int responseCode = conn.getResponseCode();
        if (isRedirect(responseCode)) {
            String loc = conn.getHeaderField("Location");
            if (loc != null) {
                URL newUrl = new URL(url, loc);
                System.out.println("Redirected to: " + newUrl);
                conn = (HttpURLConnection) newUrl.openConnection();
                conn.setRequestMethod("HEAD");
                conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
                conn.setReadTimeout(READ_TIMEOUT_MS);
                conn.setInstanceFollowRedirects(true);
            }
        }

        long contentLength = -1;
        String cl = conn.getHeaderField("Content-Length");
        if (cl != null) {
            try {
                contentLength = Long.parseLong(cl);
            } catch (NumberFormatException ignored) {
                contentLength = -1;
            }
        }

        String acceptRanges = conn.getHeaderField("Accept-Ranges");
        boolean supportsRange = false;
        if (acceptRanges != null && acceptRanges.toLowerCase().contains("bytes")) {
            supportsRange = true;
        } else {
            // If content-length known, we'll attempt range test later.
            supportsRange = (contentLength > 0);
        }

        conn.disconnect();

        String fileName = guessFileName(url);
        if (contentLength <= 0) {
            System.out.println("Content-Length unknown. Will attempt single-threaded download.");
            singleThreadDownload(url, fileName);
            return;
        }

        System.out.printf("Content-Length: %d bytes. Server %s partial content.%n",
                          contentLength, (supportsRange ? "may support" : "does not advertise"));

        // Split into parts
        long basePartSize = contentLength / PARTS;
        long remainder = contentLength % PARTS;

        final long[] starts = new long[PARTS];
        final long[] ends = new long[PARTS];

        long cursor = 0;
        for (int i = 0; i < PARTS; i++) {
            long partSize = basePartSize + (i == PARTS - 1 ? remainder : 0); // put remainder in last part
            if (partSize == 0) {
                starts[i] = -1;
                ends[i] = -1;
            } else {
                starts[i] = cursor;
                ends[i] = cursor + partSize - 1;
                cursor += partSize;
            }
        }

        // Quick check: try performing one Range request to confirm server supports it.
        if (!supportsRange) {
            System.out.println("Verifying range support with a test request...");
            if (!testRangeRequest(url)) {
                System.out.println("Server does not support Range requests. Falling back to single-threaded download.");
                singleThreadDownload(url, fileName);
                return;
            } else {
                System.out.println("Range requests appear supported.");
            }
        }

        // Create a thread pool and download parts in parallel
        ExecutorService ex = Executors.newFixedThreadPool(Math.min(PARTS, 6));
        List<Future<File>> futures = new ArrayList<Future<File>>();

        final File tempDir = new File("limny_parts_/"); // + System.currentTimeMillis() + "_" + UUID.randomUUID().toString());
        if (!tempDir.mkdirs()) {
            // if it already exists or cannot be created, continue (will attempt to use it)
        }
        System.out.println("Temporary directory for parts: " + tempDir.getAbsolutePath());

        for (int i = 0; i < PARTS; i++) {
            if (starts[i] < 0) continue; // zero size part
            final int idx = i;
            Callable<File> task = new Callable<File>() {
                public File call() throws Exception {
                    return downloadPart(url, starts[idx], ends[idx], tempDir, idx);
                }
            };
            futures.add(ex.submit(task));
        }

        // Collect downloaded parts
        List<File> partFiles = new ArrayList<File>();
        try {
            for (Future<File> f : futures) {
                File p = f.get(); // propagate exceptions
                partFiles.add(p);
                System.out.println("Completed part: " + p.getName() + " (" + p.getAbsolutePath() + ")");
            }
        } catch (ExecutionException ee) {
            // Something went wrong in one of the threads
            ex.shutdownNow();
            throw new IOException("One of the part downloads failed: " + ee.getCause(), ee.getCause());
        } finally {
            ex.shutdown();
            if (!ex.awaitTermination(5, TimeUnit.SECONDS)) {
                ex.shutdownNow();
            }
        }

        // IMPORTANT: Per request, do NOT merge or delete the parts.
        System.out.println("Download completed. Parts have been saved individually and were NOT merged or deleted.");
        System.out.println("Parts directory: " + tempDir.getAbsolutePath());
        System.out.println("Part files (" + partFiles.size() + "):");
        // sort for nicer output using an anonymous Comparator (no method references)
        Collections.sort(partFiles, new Comparator<File>() {
                public int compare(File a, File b) {
                    return a.getName().compareTo(b.getName());
                }
            });
        for (File f : partFiles) {
            System.out.println(" - " + f.getName() + "  (" + f.length() + " bytes)");
        }
    }

    private static boolean isRedirect(int code) {
        return code == HttpURLConnection.HTTP_MOVED_PERM ||
            code == HttpURLConnection.HTTP_MOVED_TEMP ||
            code == HttpURLConnection.HTTP_SEE_OTHER ||
            code == 307 || code == 308;
    }

    private boolean testRangeRequest(URL url) {
        try {
            HttpURLConnection c = (HttpURLConnection) url.openConnection();
            c.setConnectTimeout(CONNECT_TIMEOUT_MS);
            c.setReadTimeout(READ_TIMEOUT_MS);
            c.setRequestProperty("Range", "bytes=0-0");
            c.connect();
            int code = c.getResponseCode();
            c.disconnect();
            return code == HttpURLConnection.HTTP_PARTIAL; // 206
        } catch (IOException e) {
            return false;
        }
    }

    private File downloadPart(URL url, long start, long end, File tempDir, int idx) throws IOException {
        String partName = String.format("part-%02d.tmp", idx);
        File partFile = new File(tempDir, partName);

        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setConnectTimeout(CONNECT_TIMEOUT_MS);
        c.setReadTimeout(READ_TIMEOUT_MS);
        c.setRequestProperty("Range", "bytes=" + start + "-" + end);
        c.setInstanceFollowRedirects(true);
        int code = c.getResponseCode();
        if (code != HttpURLConnection.HTTP_PARTIAL && code != HttpURLConnection.HTTP_OK) {
            c.disconnect();
            throw new IOException("Server did not return partial content for range " + start + "-" + end + " (response: " + code + ")");
        }

        try {

            InputStream in = new BufferedInputStream(c.getInputStream());
            OutputStream out = new BufferedOutputStream(new FileOutputStream(partFile));

            byte[] buffer = new byte[BUFFER_SIZE];
            long totalRead = 0;
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                totalRead += read;
                System.out.println(partName+"// {"+start+"//"+end+"}//"+totalRead+"");
            }
            out.flush();

            long expected = end - start + 1;
            if (code == HttpURLConnection.HTTP_PARTIAL) {
                if (totalRead != expected) {
                    System.err.printf("Warning: part %d expected %d bytes but got %d bytes%n", idx, expected, totalRead);
                }
            } else if (code == HttpURLConnection.HTTP_OK) {
                // Server returned full content despite Range header; warn user.
                System.err.printf("Warning: server responded 200 OK for part %d; it may not support ranges as expected.%n", idx);
            }
        } finally {
            c.disconnect();
        }
        return partFile;
    }

    private void singleThreadDownload(URL url, String fileName) throws IOException {
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setConnectTimeout(CONNECT_TIMEOUT_MS);
        c.setReadTimeout(READ_TIMEOUT_MS);
        c.setInstanceFollowRedirects(true);
        File outFile = new File(fileName);

        try {
            InputStream in = new BufferedInputStream(c.getInputStream());
            OutputStream out = new BufferedOutputStream(new FileOutputStream(outFile));

            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            long total = 0;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                total += read;
            }
            out.flush();
            System.out.println("Single-threaded download finished. Bytes downloaded: " + total);
            System.out.println("Saved to: " + outFile.getAbsolutePath());
        }finally {
            c.disconnect();
        }
    }

    private String guessFileName(URL url) {
        String path = url.getPath();
        if (path == null || path.isEmpty() || path.endsWith("/")) {
            return "download";
        }
        String candidate = path.substring(path.lastIndexOf('/') + 1);
        if (candidate.isEmpty()) return "download";
        // sanitize basic illegal characters for filenames
        return candidate.replaceAll("[\\\\/:*?\"<>|]", "_");
    }
}
