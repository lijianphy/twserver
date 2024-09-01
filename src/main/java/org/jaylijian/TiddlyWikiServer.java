package org.jaylijian;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.logging.*;
import java.util.zip.GZIPOutputStream;


public final class TiddlyWikiServer {

    public static void main(String[] args) {
        if (args.length == 0) {
            printUsage();
            return;
        }
        String configFilePath = args[0];
        var server = fromConfigFile(configFilePath);
        server.start();
    }

    private final InetSocketAddress serverAddress;
    private final Path root;
    private final Path backupPath;
    private final Logger logger;

    public TiddlyWikiServer(String addr,
                            int port,
                            String root,
                            String backup) {
        serverAddress = new InetSocketAddress(addr, port);
        this.root = Path.of(root).toAbsolutePath();
        this.backupPath = this.root.resolve(backup).toAbsolutePath();
        logger = Logger.getLogger(TiddlyWikiServer.class.getName());
        setupLogger();
    }

    private static TiddlyWikiServer fromConfigFile(String configFilePath) {
        Properties config = new Properties();
        try (FileReader reader = new FileReader(configFilePath)) {
            config.load(reader);
        } catch (IOException e) {
            System.err.println("Error loading configuration file: " + e.getMessage());
            System.exit(1);
        }

        String addr = config.getProperty("server.address", "localhost");
        int port = Integer.parseInt(config.getProperty("server.port", "8080"));
        String root = config.getProperty("tiddlywiki.root", System.getProperty("user.dir"));
        String backup = config.getProperty("tiddlywiki.backup", "backup");

        return new TiddlyWikiServer(addr, port, root, backup);
    }

    private static void printUsage() {
        System.out.println("Usage: java TiddlyWikiServer <config_file_path>");
    }

    public void start() {
        try {
            HttpServer server = HttpServer.create(serverAddress, 10);
            server.createContext("/", new TWiki5Handler());
            server.setExecutor(null);
            logger.info("Starting server at " + serverAddress);
            logger.info("TiddlyWiki root path: " + root);
            logger.info("TiddlyWiki backup path: " + backupPath);
            Files.createDirectories(backupPath);
            server.start();
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Error starting TiddlyWikiServer", e);
            System.exit(1);
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
                System.out.print("\nClean backups (y or Y to clean): ");
                String clean = reader.readLine();
                if ("y".equalsIgnoreCase(clean)) {
                    int cntCleaned = cleanBackup(backupPath);
                    System.out.println(cntCleaned + " backup(s) cleaned during shutdown.");
                } else {
                    System.out.println("No backups cleaned during shutdown.");
                }
            } catch (IOException e) {
                e.printStackTrace(System.err);
            }
        }));
    }

    private void setupLogger() {
        try {
            // Custom format for log messages
            SimpleFormatter formatter = new SimpleFormatter() {
                private static final String format = "[%1$tF %1$tT] [%2$s] %3$s %4$s%n";

                @Override
                public String format(LogRecord record) {
                    String throwable = "";
                    if (record.getThrown() != null) {
                        StringWriter sw = new StringWriter();
                        PrintWriter pw = new PrintWriter(sw);
                        pw.println();
                        record.getThrown().printStackTrace(pw);
                        pw.close();
                        throwable = sw.toString();
                    }
                    return String.format(format,
                            new Date(record.getMillis()),
                            record.getLevel().getLocalizedName(),
                            record.getMessage(),
                            throwable
                    );
                }
            };

            // Set up a simple console handler with a basic format
            ConsoleHandler consoleHandler = new ConsoleHandler();
            consoleHandler.setLevel(Level.ALL);
            consoleHandler.setFormatter(formatter);

            // Set up a file handler to log to a file
            FileHandler fileHandler = new FileHandler(root.resolve("server_log.txt").toString(), true);
            fileHandler.setLevel(Level.ALL);
            fileHandler.setFormatter(formatter);

            logger.addHandler(consoleHandler);
            logger.addHandler(fileHandler);
            logger.setLevel(Level.ALL);
            logger.setUseParentHandlers(false);

        } catch (IOException e) {
            logger.log(Level.SEVERE, "Failed to set up logger", e);
            System.exit(1);
        }
    }

    private class TWiki5Handler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) {
            String method = exchange.getRequestMethod();
            String requestURI = exchange.getRequestURI().getPath();
            String clientIP = exchange.getRemoteAddress().getAddress().getHostAddress();
            logger.info(String.format("Received %s request from %s for %s", method, clientIP, requestURI));

            try {
                if (requestURI.equals("/favicon.ico")) {
                    handleFaviconRequest(exchange);
                    return;
                }

                switch (method) {
                    case "GET":
                        handleGetRequest(exchange);
                        break;
                    case "HEAD":
                        handleHeadRequest(exchange);
                        break;
                    case "PUT":
                        handlePutRequest(exchange);
                        break;
                    case "OPTIONS":
                        handleOptionsRequest(exchange);
                        break;
                    default:
                        logger.warning("Received unsupported method: " + method);
                        exchange.sendResponseHeaders(405, -1); // Method Not Allowed
                }
            } catch (Exception e) {
                logger.log(Level.WARNING, "Error handling " + method + " request", e);
            }
        }

        private void handleFaviconRequest(HttpExchange exchange) throws IOException {
            Path faviconPath = root.resolve("favicon.ico");
            if (Files.exists(faviconPath)) {
                Headers headers = exchange.getResponseHeaders();
                headers.add("Content-Type", "image/x-icon");
                exchange.sendResponseHeaders(200, 0);
                try (InputStream is = Files.newInputStream(faviconPath);
                     OutputStream os = exchange.getResponseBody()) {
                    is.transferTo(os);
                }
            } else {
                exchange.sendResponseHeaders(404, -1);
            }
            exchange.close();
            logger.info("Favicon request handled");
        }

        private void handleGetRequest(HttpExchange exchange) throws IOException {
            serveStaticFile(exchange, false);
            logger.info("Handled GET request successfully.");
        }

        private void handleHeadRequest(HttpExchange exchange) throws IOException {
            serveStaticFile(exchange, true);
            logger.info("Handled HEAD request successfully.");
        }

        private void handlePutRequest(HttpExchange exchange) throws IOException {
            Path localFile = translatePath(exchange.getRequestURI().getPath());
            boolean isGzippedHtml = localFile.getFileName().toString().endsWith(".html.gz");

            // you can only replace existing files
            if (!Files.exists(localFile)) {
                exchange.sendResponseHeaders(400, -1); // Not Found
                exchange.getResponseBody().close();

                logger.info("Handled PUT request failed, file not found: " + localFile);
                return;
            }

            if (isGzippedHtml) {
                try (InputStream inputStream = exchange.getRequestBody();
                     OutputStream fileOut = Files.newOutputStream(localFile);
                     GZIPOutputStream gzipOut = new GZIPOutputStream(fileOut)) {
                    inputStream.transferTo(gzipOut);
                }
            } else {
                // For non-.html.gz files, save the content as-is
                try (InputStream inputStream = exchange.getRequestBody();
                     OutputStream fileOut = Files.newOutputStream(localFile)) {
                    inputStream.transferTo(fileOut);
                }
            }

            exchange.sendResponseHeaders(201, -1); // Created
            exchange.getResponseBody().close();

            logger.info("Handled PUT request successfully. File saved: " + localFile);

            backupFile(localFile);
        }

        private void handleOptionsRequest(HttpExchange exchange) throws IOException {
            Headers headers = exchange.getResponseHeaders();
            headers.add("dav", "tw5/put");
            headers.add("allow", "GET,HEAD,POST,OPTIONS,CONNECT,PUT,DAV,dav");
            headers.add("x-api-access-type", "file");
            exchange.sendResponseHeaders(200, 0); // OK
            exchange.getResponseBody().close();
            logger.info("Handled OPTIONS request successfully.");
        }

        private void serveStaticFile(HttpExchange exchange, boolean headOnly) throws IOException {
            String requestPath = exchange.getRequestURI().getPath();
            Path localFile = translatePath(requestPath);

            if (!Files.exists(localFile)) {
                exchange.sendResponseHeaders(404, -1); // Not Found
                exchange.getResponseBody().close();
                return;
            }

            if (!Files.isDirectory(localFile)) {
                sendStaticFile(exchange, localFile, headOnly);
            } else {
                sendDirectoryListing(exchange, localFile, headOnly);
            }
        }

        private void sendStaticFile(HttpExchange exchange, Path localFile, boolean headOnly) throws IOException {
            Headers headers = exchange.getResponseHeaders();

            String fileName = localFile.getFileName().toString();
            boolean isGzippedHtml = fileName.endsWith(".html.gz");
            boolean isHtml = fileName.endsWith(".html");
            long fileSize = Files.size(localFile);

            String contentType = isGzippedHtml || isHtml ? "text/html" : Files.probeContentType(localFile);
            if (contentType == null) {
                contentType = "application/octet-stream";
            }

            // Add charset=utf-8 for text files
            if (contentType.startsWith("text/")) {
                contentType += "; charset=UTF-8";
            }

            headers.add("Content-Type", contentType);

            boolean useGzip = isGzippedHtml || (isHtml && fileSize > 100 * 1024); // 100KB
            if (useGzip) {
                headers.add("Content-Encoding", "gzip");
            }

            if (headOnly) {
                exchange.sendResponseHeaders(200, -1);
                exchange.getResponseBody().close();
                return;
            }

            exchange.sendResponseHeaders(200, 0); // 0 indicates chunked transfer
            try (OutputStream os = exchange.getResponseBody();
                 InputStream is = Files.newInputStream(localFile)) {

                if (useGzip && !isGzippedHtml) {
                    try (GZIPOutputStream gzipOs = new GZIPOutputStream(os)) {
                        bufferedTransfer(is, gzipOs);
                    }
                } else {
                    bufferedTransfer(is, os);
                }
            }
        }

        private static void bufferedTransfer(InputStream input, OutputStream output) throws IOException {
            final int BUFFER_SIZE = 8192; // 8KB buffer
            byte[] buffer = new byte[BUFFER_SIZE];
            int bytesRead;
            while ((bytesRead = input.read(buffer)) != -1) {
                output.write(buffer, 0, bytesRead);
                output.flush();
            }
        }

        private void sendDirectoryListing(HttpExchange exchange, Path localFile, boolean headOnly) throws IOException {
            Headers headers = exchange.getResponseHeaders();
            headers.add("Content-Type", "text/html; charset=UTF-8");
            String content = formatDirectoryListing(exchange, localFile);
            byte[] contentBytes = content.getBytes(StandardCharsets.UTF_8);
            headers.add("Content-Length", String.valueOf(contentBytes.length));

            if (headOnly) {
                exchange.sendResponseHeaders(200, -1);
                exchange.getResponseBody().close();
                return;
            }

            exchange.sendResponseHeaders(200, contentBytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(contentBytes);
            }
        }

        private String formatDirectoryListing(HttpExchange exchange, Path localFile) throws IOException {
            String requestPath = exchange.getRequestURI().getPath();
            Path relativePath = root.relativize(localFile.toAbsolutePath());
            StringBuilder content = new StringBuilder();
            content.append("<!DOCTYPE html><html><head><meta charset=\"UTF-8\">");
            content.append("<style>");
            content.append("body { font-family: Arial, sans-serif; }");
            content.append("table { border-collapse: collapse; width: 100%; }");
            content.append("th, td { text-align: left; padding: 8px; border-bottom: 1px solid #ddd; }");
            content.append("tr:hover { background-color: #f5f5f5; }");
            content.append(".directory { font-weight: bold; color: #4a86e8; }");
            content.append(".file { color: #333; }");
            content.append("</style>");
            content.append("</head><body>");
            content.append("<h1>Directory: /").append(relativePath).append("</h1>");

            // Add link to parent directory
            if (!localFile.equals(root)) {
                String parentRef = requestPath.substring(0, requestPath.lastIndexOf('/'));
                parentRef = parentRef.isEmpty() ? "/" : parentRef;
                content.append("<h2><a href=\"").append(parentRef).append("\">").append("Back to Parent Directory</a></h2>");
            }

            // List current directory
            content.append("<table>");
            content.append("<tr><th>Name</th><th>Size</th><th>Last Modified</th></tr>");
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(localFile)) {
                List<Path> sortedPaths = new ArrayList<>();
                stream.forEach(sortedPaths::add);

                // Sort directories first, then files, both in alphabetical order
                sortedPaths.sort((p1, p2) -> {
                    boolean isDir1 = Files.isDirectory(p1);
                    boolean isDir2 = Files.isDirectory(p2);
                    if (isDir1 && !isDir2) {
                        return -1;
                    } else if (!isDir1 && isDir2) {
                        return 1;
                    } else {
                        return p1.getFileName().compareTo(p2.getFileName());
                    }
                });

                for (Path path : sortedPaths) {
                    String name = path.getFileName().toString();
                    String encodedName = URLEncoder.encode(name, StandardCharsets.UTF_8);
                    String href = requestPath.endsWith("/") ? requestPath + encodedName : requestPath + "/" + encodedName;
                    boolean isDirectory = Files.isDirectory(path);
                    String fileSize = isDirectory ? "-" : formatFileSize(Files.size(path));
                    String lastModified = formatLastModified(Files.getLastModifiedTime(path).toInstant());

                    content.append("<tr>");
                    content.append("<td>");
                    if (isDirectory) {
                        content.append("<span class=\"directory\">📁 ");
                    } else {
                        content.append("<span class=\"file\">📄 ");
                    }
                    content.append("<a href=\"").append(href).append("\">").append(name).append("</a></span></td>");
                    content.append("<td>").append(fileSize).append("</td>");
                    content.append("<td>").append(lastModified).append("</td>");
                    content.append("</tr>");
                }
            }
            content.append("</table></body></html>");

            return content.toString();
        }

        private static String formatFileSize(long size) {
            if (size < 1024) return size + " B";
            int z = (63 - Long.numberOfLeadingZeros(size)) / 10;
            return String.format("%.1f %sB", (double) size / (1L << (z * 10)), " KMGTPE".charAt(z));
        }

        private static String formatLastModified(Instant lastModified) {
            return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                    .withZone(ZoneId.systemDefault())
                    .format(lastModified);
        }

        private void backupFile(Path origFile) throws IOException {
            // assume file name ends with ".html or .html.gz"
            String backupFileName = formatBackupFileName(origFile.getFileName().toString());
            Path backupFile = backupPath.resolve(backupFileName);

            boolean isGzippedHtml = origFile.getFileName().toString().endsWith(".html.gz");
            if (isGzippedHtml) {
                try (InputStream in = Files.newInputStream(origFile);
                     OutputStream out = Files.newOutputStream(backupFile)) {
                    in.transferTo(out);
                }
            } else {
                try (InputStream in = Files.newInputStream(origFile);
                     OutputStream out = Files.newOutputStream(backupFile);
                     GZIPOutputStream gzip = new GZIPOutputStream(out)) {
                    in.transferTo(gzip);
                }
            }

            logger.info("Backup to: " + backupFile);
        }

        private Path translatePath(String path) {
            if (!path.startsWith("/")) {
                throw new IllegalArgumentException("Path must start with '/'");
            }
            return root.resolve(path.substring(1));
        }
    }

    private static String formatBackupFileName(String originalFileName) {
        int lastIndex = originalFileName.lastIndexOf(".html");
        if (lastIndex == -1) {
            throw new IllegalArgumentException("File name must end with html or html.gz");
        }
        String baseName = originalFileName.substring(0, lastIndex);
        return String.format("%s-%s.html.gz", baseName, timeNow());
    }

    private static String timeNow() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
    }

    private static int cleanBackup(Path backupDir) throws IOException {
        String currentYearMonth = timeNow().substring(0, 6);

        // map from baseName to fullName
        HashMap<String, ArrayList<String>> nameMap = new HashMap<>();

        // find all backup files, group by baseName
        try (DirectoryStream<Path> directoryStream = Files.newDirectoryStream(backupDir, "*.html.gz")) {
            directoryStream.forEach(filePath -> {
                String fullName = filePath.getFileName().toString();
                String baseName = fullName.substring(0, fullName.length() - 23); // remove date part
                ArrayList<String> list = nameMap.get(baseName);
                if (list != null) {
                    list.add(fullName);
                } else {
                    list = new ArrayList<>();
                    list.add(fullName);
                    nameMap.put(baseName, list);
                }
            });
        }

        int count = 0;
        for (var entry : nameMap.entrySet()) {
            var allBackups = entry.getValue();
            int baseNameLength = entry.getKey().length();
            allBackups.sort(Comparator.reverseOrder());
            String savedYearMonth = "";
            for (String backupFile : allBackups) {
                String date = backupFile.substring(baseNameLength + 1, baseNameLength + 7);
                if (date.compareTo(currentYearMonth) >= 0) {
                    continue;
                }
                if (savedYearMonth.equals(date)) {
                    Files.delete(backupDir.resolve(backupFile));
                    System.out.println("Removing " + backupFile);
                    count++;
                } else {
                    savedYearMonth = date;
                }
            }
        }
        return count;
    }
}
