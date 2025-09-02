import javax.net.ssl.*;
import java.net.*;
import java.io.*;
import java.security.cert.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

public class MultithreadedFileDownloader {

    public static void main(String[] args) {
        disableCertValidation();

        try (Scanner scanner = new Scanner(System.in)) {
            System.out.println("\n\n\t\t\t\t\t\t********--- Multi-threaded File Downloader ---**********");

            // 1. Get User Input
            String fileURL = getFileInput(scanner, "\n\nEnter the file URL to download: ");
            String destinationDir = getFileInput(scanner, "\n\nEnter the destination directory: ");

            // 2. Pre-flight Check: Get file size and check for range support
            HttpURLConnection conn = (HttpURLConnection) new URL(fileURL).openConnection();
            conn.setRequestMethod("HEAD");
            long fileSize = conn.getContentLengthLong();
            String acceptRanges = conn.getHeaderField("Accept-Ranges");
            conn.disconnect();

            // Abort if server doesn't support range requests or file size is invalid
            if (fileSize <= 0 || !"bytes".equalsIgnoreCase(acceptRanges)) {
                System.err.println("This file is not suitable for multi-threaded downloading. Aborting.");
                return;
            }

            // Display file size in appropriate units (KB or MB)
            if (fileSize < 1024 * 1024) {
                System.out.printf("File size: %d KB\n", fileSize / 1024);
            } else {
                System.out.printf("File size: %d MB\n", fileSize / (1024 * 1024));
            }

            // 3. Get Number of Threads
            System.out.print("Enter number of threads [1 to 16]: ");
            int numberOfThreads = scanner.nextInt();
            scanner.nextLine(); // Consume the rest of the line

            if (numberOfThreads < 1 || numberOfThreads > 16) {
                System.out.println("Invalid number. Defaulting to 4 threads.");
                numberOfThreads = 4;
            }

            // If the file is too small, use a single thread
            if (fileSize < numberOfThreads) {
                System.out.println("File is smaller than the number of threads. Using 1 thread.");
                numberOfThreads = 1;
            }
            System.out.println("Using " + numberOfThreads + " threads for the download.");

            // 4. Calculate Chunks and Start Download Threads
            long chunkSize = fileSize / numberOfThreads;
            System.out.printf("Each thread will download approximately %d KB\n\n", chunkSize / 1024);

            DownloadThread[] threads = new DownloadThread[numberOfThreads];

            // Calculate the specific size for this chunk for display purposes.



            for (int i = 0; i < numberOfThreads; i++) {
                long startByte = i * chunkSize;
                // The last thread gets all remaining bytes to account for rounding.
                long endByte = (i == numberOfThreads - 1) ? fileSize - 1 : startByte + chunkSize - 1;

                // Calculate the specific size for this chunk for display purposes.
                long currentChunkSize = endByte - startByte + 1;
                String chunkSizeString;
                if (currentChunkSize < 1024 * 1024) {
                    chunkSizeString = String.format("%d KB", currentChunkSize / 1024);
                } else {
                    chunkSizeString = String.format("%d MB", currentChunkSize / (1024 * 1024));
                }
                System.out.println("Thread " + (i + 1) + " will download " + chunkSizeString + " (bytes " + startByte + " to " + endByte + ")");
                threads[i] = new DownloadThread(fileURL, startByte, endByte, i + 1, destinationDir);
                threads[i].start();
            }
            System.out.println("\n");

            // 5. Wait for All Threads to Complete
            for (DownloadThread thread : threads) {
                thread.join();
            }

            // 6. Verify and Merge Chunks
            if (verifyChunks(destinationDir, numberOfThreads)) {
                System.out.println("\n\t\tAll chunks downloaded successfully.");
                mergeChunks(fileURL, destinationDir, numberOfThreads);
            } else {
                System.err.println("\nOne or more download threads failed. Cannot merge file.");
            }

        } catch (Exception e) {
            System.err.println("\nAn error occurred: " + e.getMessage());
        }
    }

    private static String getFileInput(Scanner scanner, String prompt) {
        String input;
        while (true) {
            System.out.print(prompt);
            input = scanner.nextLine();
            if (input != null && !input.trim().isEmpty()) {
                return input;
            }
            System.out.println("Input cannot be empty. Please try again.");
        }
    }

    private static boolean verifyChunks(String destinationDir, int numberOfThreads) {
        for (int i = 0; i < numberOfThreads; i++) {
            // Using i+1 to match the chunk file names (chunk_1, chunk_2, ...)
            File chunkFile = new File(destinationDir, "chunk_" + (i + 1));
            if (!chunkFile.exists()) {
                System.err.println("Missing chunk file: " + chunkFile.getName());
                return false;
            }
        }
        return true;
    }

    private static void mergeChunks(String fileURL, String destinationDir, int numberOfThreads) {
        System.out.println("\n\nMerging file chunks...");
        try {
            String fileName = new File(new URL(fileURL).getPath()).getName();
            Path finalFilePath = Paths.get(destinationDir, fileName);

            try (FileOutputStream fos = new FileOutputStream(finalFilePath.toFile())) {
                for (int i = 0; i < numberOfThreads; i++) {
                    File chunkFile = new File(destinationDir, "chunk_" + (i + 1));
                    Files.copy(chunkFile.toPath(), fos);
                 //   chunkFile.delete(); // Clean up the chunk file
                }
            }
            System.out.println("✅ File download and merge complete!");
            System.out.println("File saved to: " + finalFilePath);
        } catch (IOException e) {
            System.err.println("Error merging files: " + e.getMessage());
        }
    }

    private static void disableCertValidation() {
        TrustManager[] trustAllCerts = new TrustManager[]{new X509TrustManager() {
            public X509Certificate[] getAcceptedIssuers() { return null; }
            public void checkClientTrusted(X509Certificate[] certs, String authType) { }
            public void checkServerTrusted(X509Certificate[] certs, String authType) { }
        }};
        try {
            SSLContext sc = SSLContext.getInstance("SSL");
            sc.init(null, trustAllCerts, new java.security.SecureRandom());
            HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
        } catch (Exception e) {
            System.err.println("Failed to disable certificate validation: " + e.getMessage());
        }
    }

    /**
     * Inner class representing a single download thread.
     */
    private static class DownloadThread extends Thread {
        private final String fileURL;
        private final long startByte;
        private final long endByte;
        private final int threadId; // Changed from threadIndex to avoid confusion (1-based)
        private final String destinationDir;

        public DownloadThread(String fileURL, long startByte, long endByte, int threadId, String destinationDir) {
            this.fileURL = fileURL;
            this.startByte = startByte;
            this.endByte = endByte;
            this.threadId = threadId;
            this.destinationDir = destinationDir;
        }

        @Override
        public void run() {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(fileURL);
                conn = (HttpURLConnection) url.openConnection();
                String byteRange = "bytes=" + startByte + "-" + endByte;
                conn.setRequestProperty("Range", byteRange);
                conn.connect();

                // This check is crucial for multi-threaded downloading
                if (conn.getResponseCode() != HttpURLConnection.HTTP_PARTIAL) {
                    throw new IOException("Server returned non-partial response code: " + conn.getResponseCode());
                }

                String chunkFileName = destinationDir + File.separator + "chunk_" + threadId;
                try (InputStream inputStream = conn.getInputStream();
                     FileOutputStream outputStream = new FileOutputStream(chunkFileName)) {

                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    while ((bytesRead = inputStream.read(buffer)) != -1) {
                        outputStream.write(buffer, 0, bytesRead);
                    }
                }
                System.out.println("Thread " + threadId + ": Finished.");
            } catch (IOException e) {
                System.err.println("Thread " + threadId + " failed: " + e.getMessage());
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        }
    }
}
