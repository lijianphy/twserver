# TiddlyWiki Server

This is a simple Java-based HTTP server designed to host and serve TiddlyWiki5 wikis. It supports basic functionalities like serving static files, handling PUT requests for updating wiki content, backing up modified wikis, and generating directory listings.

## Features

- **Serves static files:**  Handles GET and HEAD requests for serving HTML and other static files required by TiddlyWiki.
- **Supports PUT requests:** Enables updating TiddlyWiki content through PUT requests, allowing you to save changes made in the wiki directly to the server.
- **Directory listings:**  Provides a simple HTML-based directory listing for navigating folders on the server.
- **Automatic backups:**  Creates backups of modified TiddlyWiki files (.html and .html.gz) in a designated backup directory.
- **Backup cleanup:**  Optionally cleans up old backups during server shutdown, keeping only the most recent backups for each month.
- **Gzip compression:**  Automatically compresses HTML files larger than 100KB using Gzip and serves them with appropriate headers, improving loading times.
- **Logging:** Logs server activity and errors to both the console and a log file.
- **Configuration file:**  Uses a configuration file to customize server settings such as the server address, port, TiddlyWiki root directory, and backup directory.


## Getting Started

1. **Prerequisites:**
    - Java Development Kit (JDK) 21 or later installed on your system.
    - maven build tool.

2. **Clone the repository:**
   ```
   git clone https://github.com/lijianphy/twserver.git
   ```

3. **Configuration:**
    - Create (or modify) the configuration file named `config.properties` in the root directory of the project.
    - Customize the following properties in the `config.properties` file:
      ```
      server.address=localhost  # IP address or hostname to bind the server to
      server.port=8080          # Port number to listen on
      tiddlywiki.root=/path/to/your/tiddlywiki  # Root directory for serving TiddlyWiki files
      tiddlywiki.backup=backup   # Subdirectory within the root directory for storing backups
      ```

4. **Compilation:**
    - Compile the Java code using maven:
      ```
      mvn clean package
      ```

5. **Running the server:**
    - Run the server using the following command:
      ```
      java -jar target/twserver-1.0.jar config.properties
      ```

## Usage

- Access your TiddlyWiki by navigating to `http://localhost:8080` (or the address and port you configured) in your web browser.
- Make changes to your TiddlyWiki as usual. The server will handle saving the updated content through PUT requests.


## Contributing

Contributions are welcome! Please feel free to open issues or submit pull requests.

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.
