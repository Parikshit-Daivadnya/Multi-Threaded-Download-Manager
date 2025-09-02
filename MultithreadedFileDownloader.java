import java.util.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;

import javax.net.ssl.*;
import java.security.cert.*;


public class MultithreadedFileDownloader {

    public static void main(String[] args) {
        disableCertValidation();

        try (Scanner scanner = new Scanner(System.in)) {
            System.out.println("\n\n\t\t\t\t\t\t\t\t***********--- Multi-threaded File Downloader ---**********");

            // 1. Getting User Input
            String fileURL = getFilePath(scanner, "\n\nEnter the file URL to download: ");
            String destinationDir = getFilePath(scanner, "\nEnter the destination directory: ");

            // 2. Pre-flight Check: Get file size and check for range support
            HttpURLConnection conn = (HttpURLConnection) new URL(fileURL).openConnection();
            //creates a URL from the fileURL string and opens a connection to it
            //the openConnection() method returns a URLConnection object, which is then cast to HttpURLConnection
            //URLConnection is the parent class for all classes that represent a connection to a URL

            conn.setRequestMethod("HEAD");
            //by default, the request method is "GET", which retrieves the entire resource
            // here it is set to "HEAD", which is used to retrieve metadata (infor about the content such as size, type, response code etc.)
            // about the resource without downloading the actual content


            int responseCode = conn.getResponseCode();
            System.out.println("\nResponse Code: " + responseCode);
            long fileSize = conn.getContentLengthLong();
            String acceptRanges = conn.getHeaderField("Accept-Ranges");
            //So this line asks:
            //👉 “What is the value of the Accept-Ranges header in the server’s response?”
            //There is a header field in HTTP responses called "Accept-Ranges" that indicates whether
            //the server supports range requests for the resource being accessed.
            //It can have values like "bytes" (indicating support for byte-range requests)
            //or "none" (indicating no support for range requests).

            conn.disconnect();

            // Abort if server doesn't support range requests or file size is invalid
            if (fileSize <= 0 || !"bytes".equalsIgnoreCase(acceptRanges)) {
                System.err.println("This file is not suitable for multi-threaded downloading. Aborting.");
                return;
            }

            // Display file size in appropriate units (KB or MB)
            if (fileSize < 1024 * 1024) {
                // 1 kb = 1024 bytes
                // 1 MB = 1024 * 1024 bytes
                System.out.printf("File size: %d KB\n", fileSize / 1024);
            } else {
                System.out.printf("File size: %d MB\n", fileSize / (1024 * 1024));
            }



            // 3. Get Number of Threads
            System.out.print("\nEnter number of threads [1 to 16]: ");
            int numberOfThreads = scanner.nextInt();
            scanner.nextLine(); // Consume the rest of the line

            if (numberOfThreads < 1 || numberOfThreads > 16) {
                System.out.println("Invalid number. Defaulting to 4 threads.");
                numberOfThreads = 4;
            }

            // If the file is too small, use a single thread
            if (fileSize < numberOfThreads) {
                //because the file size is measured in bytes and the number of threads is a count of threads
                //it means that if the file size is less than the number of threads,
                // there aren't enough bytes to allocate at least one byte per thread
                //Eg. a file of 3 bytes cannot be effectively downloaded using 4 threads
                System.out.println("File is smaller than the number of threads. Using 1 thread.");
                numberOfThreads = 1;
            }
            System.out.println("\t\t\t\t\t\t\t\t\tUsing " + numberOfThreads + " threads for the download.");



            // 4. Calculate Chunks and Start Download Threads
            long chunkSize = fileSize / numberOfThreads;
            System.out.printf("\nEach thread will download approximately %d KB\n\n", chunkSize / 1024);

            DownloadThread[] threads = new DownloadThread[numberOfThreads];


            for (int i = 0; i < numberOfThreads; i++) {
                long startByte = i * chunkSize;
                //startByte is the starting position (offset) in the file where i^th thread should begin downloading.

                long endByte = (i == numberOfThreads - 1) ? fileSize - 1 : startByte + chunkSize - 1;
                //endByte is the ending position in the file till where i^th thread will download.
                //Formula: The last thread gets all remaining bytes to account for rounding.

                // Calculate the specific size for  currentChunk for display purposes.
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
                System.out.println("\n\n\t\t\t\t\t\t**********All chunks downloaded successfully!**********");
                mergeChunks(fileURL, destinationDir, numberOfThreads);
            } else {
                System.err.println("\nOne or more download threads failed. Cannot merge file.");
            }

        } catch (Exception e) {
            System.err.println("\nAn error occurred: " + e.getMessage());
        }
    }

    private static String getFilePath(Scanner scanner, String prompt) {
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
            //doesn't actually create any file, just represents the path to the file
            // i.e it creates an object that points to where the file should be located

            if (!chunkFile.exists()) {
                System.err.println("Missing chunk file: " + chunkFile.getName());
                return false;
            }
        }
        return true;
    }

    private static void mergeChunks(String fileURL, String destinationDir, int numberOfThreads) {
        System.out.println("\n\n\t\t\t\t\t\t**********Merging file chunks...**********");
        try {
            String fileName = new File(new URL(fileURL).getPath()).getName();
            Path finalFilePath = Paths.get(destinationDir, fileName);

            try (FileOutputStream fos = new FileOutputStream(finalFilePath.toFile())) {
                for (int i = 0; i < numberOfThreads; i++) {
                    System.out.println("Merging chunk " + (i + 1) + "...");
                    File chunkFile = new File(destinationDir, "chunk_" + (i + 1));
                    Files.copy(chunkFile.toPath(), fos);
                    //Effect → The contents of chunk_(i+1) are appended into the final file via the FileOutputStream (fos).
                    // The copy method reads all bytes from the chunk file and writes them to the output stream (fos).
                    // This effectively merges the chunk files into a single complete file.

//                       chunkFile.delete(); // Clean up the chunk file
                }
            }
            System.out.println("\n\n\n\n\t\t\t\t**********✅ File download and merge complete!**********");
            System.out.println("\nFile saved to: " + finalFilePath);
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
                    synchronized (System.out) {
                        System.out.println("Thread " + threadId + " is starting the download of its chunk...");
                    }
                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    while ((bytesRead = inputStream.read(buffer)) != -1) {
                        outputStream.write(buffer, 0, bytesRead);
                    }
                }
                synchronized (System.out) {
                    System.out.println("Thread " + threadId + " has completed downloading its chunk.\n");
                }
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
