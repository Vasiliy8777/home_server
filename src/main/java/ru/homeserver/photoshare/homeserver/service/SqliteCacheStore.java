package ru.homeserver.photoshare.homeserver.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ru.homeserver.photoshare.homeserver.config.AppProperties;
import ru.homeserver.photoshare.homeserver.dto.FileItemDto;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import ru.homeserver.photoshare.homeserver.search.PhotoSearchService;
import java.util.*;

/** Local, reconstructible cache. All writes use one connection and short transactions. */
@Service
public class SqliteCacheStore {
    private static final Logger log = LoggerFactory.getLogger(SqliteCacheStore.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final Connection connection;
    private final Map<String, Integer> snapshotReaders = new HashMap<>();

    public SqliteCacheStore(AppProperties properties) throws IOException {
        if (!properties.getCache().isDatabaseEnabled()) { connection = null; return; }
        String configured = properties.getCache().getDatabasePath();
        Path path = configured == null || configured.isBlank()
                ? Path.of(properties.getMetadataCacheDir()).resolve("catalog.sqlite") : Path.of(configured);
        path = path.toAbsolutePath().normalize();
        Files.createDirectories(path.getParent());
        try {
            connection = DriverManager.getConnection("jdbc:sqlite:" + path);
            try (Statement sql = connection.createStatement()) {
                sql.execute("PRAGMA journal_mode=WAL");
                sql.execute("PRAGMA busy_timeout=10000");
                sql.execute("PRAGMA synchronous=FULL");
                sql.execute("CREATE TABLE IF NOT EXISTS folder_snapshots (folder TEXT PRIMARY KEY, signature TEXT NOT NULL, snapshot TEXT NOT NULL)");
                sql.execute("CREATE TABLE IF NOT EXISTS folder_items (folder TEXT NOT NULL, path TEXT NOT NULL, directory INTEGER NOT NULL, name_key TEXT NOT NULL, created_at INTEGER NOT NULL, item_json TEXT NOT NULL, PRIMARY KEY(folder,path))");
                sql.execute("CREATE INDEX IF NOT EXISTS folder_items_name ON folder_items(folder,directory DESC,name_key,path)");
                sql.execute("CREATE INDEX IF NOT EXISTS folder_items_date ON folder_items(folder,directory DESC,created_at,path)");
                sql.execute("CREATE TABLE IF NOT EXISTS metadata (path TEXT PRIMARY KEY, modified INTEGER NOT NULL, size INTEGER NOT NULL, value_json TEXT NOT NULL)");
                sql.execute("CREATE TABLE IF NOT EXISTS photo_entries (root TEXT NOT NULL,path TEXT NOT NULL,details_json TEXT NOT NULL,vector BLOB NOT NULL,PRIMARY KEY(root,path))");
                sql.execute("CREATE TABLE IF NOT EXISTS photo_migrations (root TEXT PRIMARY KEY)");
                boolean hasSnapshot = false;
                try (var columns = sql.executeQuery("PRAGMA table_info(folder_snapshots)")) {
                    while (columns.next()) if ("snapshot".equals(columns.getString("name"))) hasSnapshot = true;
                }
                if (!hasSnapshot) {
                    sql.execute("ALTER TABLE folder_snapshots ADD COLUMN snapshot TEXT");
                    sql.execute("UPDATE folder_snapshots SET snapshot=folder WHERE snapshot IS NULL");
                }
                sql.execute("DELETE FROM folder_items WHERE folder NOT IN (SELECT snapshot FROM folder_snapshots)");
                sql.execute("PRAGMA user_version=3");
            }
            log.info("SQLite cache ready: {}", path);
        } catch (SQLException e) { throw new IOException("Cannot open SQLite cache: " + path, e); }
    }

    public boolean enabled() { return connection != null; }

    public synchronized String signature(String folder) throws IOException {
        if (!enabled()) return null;
        try (var sql = connection.prepareStatement("SELECT signature FROM folder_snapshots WHERE folder=?")) {
            sql.setString(1, folder);
            try (var rows = sql.executeQuery()) { return rows.next() ? rows.getString(1) : null; }
        } catch (SQLException e) { throw failure(e); }
    }

    public synchronized void saveFolder(String folder, String signature, List<FileItemDto> items) throws IOException {
        if (!enabled()) return;
        String oldSnapshot = snapshot(folder);
        String snapshot = UUID.randomUUID().toString();
        try {
            connection.setAutoCommit(false);
            try (var insert = connection.prepareStatement("INSERT INTO folder_items VALUES(?,?,?,?,?,?)")) {
                for (FileItemDto item : items) {
                    insert.setString(1, snapshot); insert.setString(2, item.relativePath());
                    insert.setInt(3, item.directory() ? 1 : 0);
                    insert.setString(4, item.name().toLowerCase(Locale.ROOT));
                    insert.setLong(5, item.createdAt() > 0 ? item.createdAt() : item.lastModified());
                    insert.setString(6, mapper.writeValueAsString(item)); insert.addBatch();
                }
                insert.executeBatch();
            }
            try (var insert = connection.prepareStatement("INSERT INTO folder_snapshots VALUES(?,?,?) ON CONFLICT(folder) DO UPDATE SET signature=excluded.signature,snapshot=excluded.snapshot")) {
                insert.setString(1, folder); insert.setString(2, signature); insert.setString(3, snapshot); insert.executeUpdate();
            }
            if (oldSnapshot != null && !snapshotReaders.containsKey(oldSnapshot)) deleteSnapshot(oldSnapshot);
            connection.commit();
        } catch (SQLException | IOException e) {
            try { connection.rollback(); } catch (SQLException rollback) { e.addSuppressed(rollback); }
            throw e instanceof IOException io ? io : failure((SQLException) e);
        } finally {
            try { connection.setAutoCommit(true); } catch (SQLException e) { log.error("Cannot reset cache transaction", e); }
        }
    }

    public synchronized List<FileItemDto> page(String folder, String field, String direction, int offset, int limit) throws IOException {
        if (!enabled()) return List.of();
        if (offset < 0 || limit < 1) throw new IllegalArgumentException("Invalid page");
        String column = "lastModified".equals(field) ? "created_at" : "size".equals(field)
                ? "CAST(json_extract(item_json,'$.size') AS INTEGER)" : "name_key";
        String order = "desc".equals(direction) ? "DESC" : "ASC";
        String directoryOrder = "desc".equals(direction) ? "ASC" : "DESC";
        try (var sql = connection.prepareStatement("SELECT item_json FROM folder_items WHERE folder=? ORDER BY directory " + directoryOrder + "," + column + " " + order + ",path " + order + " LIMIT ? OFFSET ?")) {
            sql.setString(1, resolveSnapshot(folder)); sql.setInt(2, limit); sql.setInt(3, offset);
            List<FileItemDto> result = new ArrayList<>();
            try (var rows = sql.executeQuery()) {
                while (rows.next()) result.add(mapper.readValue(rows.getString(1), FileItemDto.class));
            }
            return result;
        } catch (SQLException e) { throw failure(e); }
    }

    public synchronized int count(String folder) throws IOException {
        if (!enabled()) return 0;
        try (var sql = connection.prepareStatement("SELECT count(*) FROM folder_items WHERE folder=?")) {
            sql.setString(1, resolveSnapshot(folder));
            try (var rows = sql.executeQuery()) { return rows.next() ? rows.getInt(1) : 0; }
        } catch (SQLException e) { throw failure(e); }
    }

    public synchronized Map<String, Object> metadata(Path path, long modified, long size) throws IOException {
        if (!enabled()) return null;
        try (var sql = connection.prepareStatement("SELECT value_json FROM metadata WHERE path=? AND modified=? AND size=?")) {
            sql.setString(1, path.toString()); sql.setLong(2, modified); sql.setLong(3, size);
            try (var rows = sql.executeQuery()) { return rows.next() ? mapper.readValue(rows.getString(1), Map.class) : null; }
        } catch (SQLException e) { throw failure(e); }
    }

    public synchronized void saveMetadata(Path path, long modified, long size, Map<String, Object> value) throws IOException {
        if (!enabled()) return;
        try (var sql = connection.prepareStatement("INSERT INTO metadata VALUES(?,?,?,?) ON CONFLICT(path) DO UPDATE SET modified=excluded.modified,size=excluded.size,value_json=excluded.value_json")) {
            sql.setString(1, path.toString()); sql.setLong(2, modified); sql.setLong(3, size);
            sql.setString(4, mapper.writeValueAsString(value)); sql.executeUpdate();
        } catch (SQLException e) { throw failure(e); }
    }

    public synchronized void invalidateFolder(String folder) throws IOException {
        if (!enabled()) return;
        // Retain the last snapshot's items until an atomic replacement is committed.
        try (var sql = connection.prepareStatement("UPDATE folder_snapshots SET signature='' WHERE folder=?")) {
            sql.setString(1, folder); sql.executeUpdate();
        } catch (SQLException e) { throw failure(e); }
    }

    private String snapshot(String folder) throws IOException {
        try (var sql = connection.prepareStatement("SELECT snapshot FROM folder_snapshots WHERE folder=?")) {
            sql.setString(1, folder);
            try (var rows = sql.executeQuery()) { return rows.next() ? rows.getString(1) : null; }
        } catch (SQLException e) { throw failure(e); }
    }

    private String resolveSnapshot(String key) throws IOException {
        String snapshot = snapshot(key);
        return snapshot == null ? key : snapshot;
    }

    public synchronized String acquireSnapshot(String folder) throws IOException {
        String snapshot = snapshot(folder);
        if (snapshot == null) throw new IOException("Folder snapshot not found");
        snapshotReaders.merge(snapshot, 1, Integer::sum);
        return snapshot;
    }

    public synchronized void releaseSnapshot(String snapshot) {
        if (snapshot == null || !enabled()) return;
        int remaining = snapshotReaders.getOrDefault(snapshot, 0) - 1;
        if (remaining > 0) { snapshotReaders.put(snapshot, remaining); return; }
        snapshotReaders.remove(snapshot);
        try (var sql = connection.prepareStatement("SELECT 1 FROM folder_snapshots WHERE snapshot=?")) {
            sql.setString(1, snapshot);
            try (var rows = sql.executeQuery()) { if (!rows.next()) deleteSnapshot(snapshot); }
        } catch (SQLException e) { log.warn("Cannot release folder snapshot", e); }
    }

    private void deleteSnapshot(String snapshot) throws SQLException {
        try (var sql = connection.prepareStatement("DELETE FROM folder_items WHERE folder=?")) {
            sql.setString(1, snapshot); sql.executeUpdate();
        }
    }

    private IOException failure(SQLException e) { return new IOException("SQLite cache error: " + e.getMessage(), e); }

    public synchronized boolean photosImported(String root) throws IOException {
        try (var sql = connection.prepareStatement("SELECT 1 FROM photo_migrations WHERE root=?")) {
            sql.setString(1, root);
            try (var rows = sql.executeQuery()) { return rows.next(); }
        } catch (SQLException e) { throw failure(e); }
    }

    public synchronized void markPhotosImported(String root) throws IOException {
        try (var sql = connection.prepareStatement("INSERT OR IGNORE INTO photo_migrations VALUES(?)")) {
            sql.setString(1, root); sql.executeUpdate();
        } catch (SQLException e) { throw failure(e); }
    }

    public synchronized List<PhotoSearchService.Entry> photos(String root) throws IOException {
        try (var sql = connection.prepareStatement("SELECT path,details_json,vector FROM photo_entries WHERE root=?")) {
            sql.setString(1, root);
            List<PhotoSearchService.Entry> result = new ArrayList<>();
            try (var rows = sql.executeQuery()) {
                while (rows.next()) {
                    try {
                    var entry = mapper.readValue(rows.getString(2), PhotoSearchService.Entry.class);
                    if (entry == null) throw new IOException("Missing photo entry");
                    byte[] bytes = rows.getBytes(3);
                    if (bytes == null || bytes.length == 0 || bytes.length % Float.BYTES != 0) throw new IOException("Invalid stored photo vector");
                    var buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
                    float[] vector = new float[bytes.length / Float.BYTES];
                    for (int i = 0; i < vector.length; i++) vector[i] = buffer.getFloat();
                    result.add(new PhotoSearchService.Entry(entry.relativePath(), entry.size(), entry.modified(),
                            entry.signature(), entry.description(), vector));
                    } catch (IOException | IllegalArgumentException invalid) {
                        log.warn("Skipping damaged photo cache entry path={} reason={}", rows.getString(1), invalid.toString());
                    }
                }
            }
            return result;
        } catch (SQLException e) { throw failure(e); }
    }

    public synchronized void savePhoto(String root, PhotoSearchService.Entry entry) throws IOException {
        var metadata = new PhotoSearchService.Entry(entry.relativePath(), entry.size(), entry.modified(),
                entry.signature(), entry.description(), null);
        var vector = ByteBuffer.allocate(entry.vector().length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (float value : entry.vector()) vector.putFloat(value);
        try (var sql = connection.prepareStatement("INSERT INTO photo_entries VALUES(?,?,?,?) ON CONFLICT(root,path) DO UPDATE SET details_json=excluded.details_json,vector=excluded.vector")) {
            sql.setString(1, root); sql.setString(2, entry.relativePath());
            sql.setString(3, mapper.writeValueAsString(metadata)); sql.setBytes(4, vector.array()); sql.executeUpdate();
        } catch (SQLException e) { throw failure(e); }
    }

    public synchronized void removePhoto(String root, String relative) throws IOException {
        try (var sql = connection.prepareStatement("DELETE FROM photo_entries WHERE root=? AND path=?")) {
            sql.setString(1, root); sql.setString(2, relative); sql.executeUpdate();
        } catch (SQLException e) { throw failure(e); }
    }

    @PreDestroy public synchronized void close() {
        if (connection != null) try { connection.close(); } catch (SQLException e) { log.warn("SQLite close failed", e); }
    }
}
