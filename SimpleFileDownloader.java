import javax.net.ssl.*;
import javax.net.*;
import java.net.*;
import java.io.*;
import java.security.cert.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

public class SimpleFileDownloader {

        public static void main(String[] args) {
            disableCertValidation();

            try (Scanner scanner = new Scanner(System.in)) {
                System.out.println("\n\n\t\t\t\t\t\t********--- Simple Multi-threaded File Downloader ---**********");

                // 1. Get User Input
                String fileURL = getFileInput(scanner, "\n\nEnter the file URL to download: ");
                String destinationDir = getFileInput(scanner, "\n\nEnter the destination directory: ");

                // 2. Get File Info
                HttpURLConnection conn = (HttpURLConnection) new URL(fileURL).openConnection();
                conn.setRequestMethod("HEAD");
               // String acceptRanges = conn.getHeaderField("Accept-Ranges");
                long fileSize = conn.getContentLengthLong();



                int responseCode = conn.getResponseCode();
                String acceptRanges = conn.getHeaderField("Accept-Ranges");
                String contentRange = conn.getHeaderField("Content-Range");//for partial content (what content range is being sent out of the entire file)
                long contentLength = conn.getContentLengthLong();
                conn.disconnect();

                System.out.println("\nResponse Code: " + responseCode + " for URL: " + fileURL);
                System.out.println("\nContent-Range: " + contentRange + " bytes");
                System.out.println("Content-Length: " + contentLength + " bytes");

                if (fileSize <= 0 || !"bytes".equalsIgnoreCase(acceptRanges)) {
                    System.err.println("This file is not suitable for multi-threaded downloading. Aborting.");
                    return;
                }

                if (fileSize < 1024 * 1024) {
                    System.out.println("File size: " + fileSize / 1024 + " KB");
                } else {
                    System.out.println("File size: " + fileSize / (1024 * 1024) + " MB\n");
                }

                // 3. Automatically Determine Thread Count
                System.out.print("Enter number of threads [1 to 10]: ");
                int numberOfThreads=scanner.nextInt();
              //  int numberOfThreads = Runtime.getRuntime().availableProcessors();
                if (fileSize < numberOfThreads) {
                    numberOfThreads = 1; // Use 1 thread for very small files
                }
                System.out.println("Using " + numberOfThreads + " threads for the download.");

                // 4. Calculate Chunks and Start Download Threads
                long chunkSize = fileSize / numberOfThreads;
                System.out.println("Each thread will download approximately " + chunkSize + " MB\n\n");

                DownloadThread[] threads = new DownloadThread[numberOfThreads];

                for (int i = 0; i < numberOfThreads; i++) {
                    long startByte = i * chunkSize;
                    long endByte = (i == numberOfThreads - 1) ? fileSize - 1 : startByte + chunkSize - 1;

                    System.out.println("Thread " + (i + 1) + " will download bytes from: " + startByte + " to " + endByte);
                    threads[i] = new DownloadThread(fileURL, startByte, endByte, i, destinationDir);
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

        // Your existing method for getting user input
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

        // Simplified check to ensure all parts were downloaded
        private static boolean verifyChunks(String destinationDir, int numberOfThreads) {
            for (int i = 0; i < numberOfThreads; i++) {
                File chunkFile = new File(destinationDir, "chunk_" + (i+1));
                if (!chunkFile.exists()) {
                    System.err.println("Missing chunk file: " + chunkFile.getName());
                    return false;
                }
            }
            return true;
        }

        // Your existing method for merging the files
        private static void mergeChunks(String fileURL, String destinationDir, int numberOfThreads) {
            System.out.println("\n\nMerging file chunks...");
            try {
                String fileName = new File(new URL(fileURL).getPath()).getName();
                Path finalFilePath = Paths.get(destinationDir, fileName);

                try (FileOutputStream fos = new FileOutputStream(finalFilePath.toFile())) {
                    for (int i = 0; i < numberOfThreads; i++) {
                        File chunkFile = new File(destinationDir, "chunk_" + (i+1));
                        Files.copy(chunkFile.toPath(), fos);
                        //chunkFile.delete(); // Clean up the chunk file
                    }
                }
                System.out.println("✅ File download and merge complete!");
                System.out.println("File saved to: " + finalFilePath);
            } catch (IOException e) {
                System.err.println("Error merging files: " + e.getMessage());
            }
        }

        // Your existing method for disabling certificate validation
        private static void disableCertValidation() {
            TrustManager[] trustAllCerts = new TrustManager[]{ new X509TrustManager() {
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
            private final int threadIndex;
            private final String destinationDir;

            public DownloadThread(String fileURL, long startByte, long endByte, int threadIndex, String destinationDir) {
                this.fileURL = fileURL;
                this.startByte = startByte;
                this.endByte = endByte;
                this.threadIndex = threadIndex;
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

                    if (conn.getResponseCode() != HttpURLConnection.HTTP_PARTIAL) {
                        throw new IOException("Server returned non-partial response code: " + conn.getResponseCode());
                    }

                    String chunkFileName = destinationDir + File.separator + "chunk_" + (threadIndex+1);
                    try (InputStream inputStream = conn.getInputStream();
                         FileOutputStream outputStream = new FileOutputStream(chunkFileName)) {
                        byte[] buffer = new byte[8192];
                        int bytesRead;

                        while ((bytesRead = inputStream.read(buffer)) != -1) {
                            if(threadIndex == threadIndex - 1) {

                            }
                            outputStream.write(buffer, 0, bytesRead);
                            System.out.println("Thread " + (threadIndex+1) + " downloaded " + bytesRead + " bytes.");
                        }
                    }
                } catch (IOException e) {
                    System.err.println("Thread " + threadIndex + " failed: " + e.getMessage());
                } finally {
                    if (conn != null) {
                        conn.disconnect();
                    }
                }
            }
        }
    }

