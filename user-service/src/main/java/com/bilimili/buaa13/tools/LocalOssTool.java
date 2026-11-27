package com.bilimili.buaa13.tools;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.UUID;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

/** 实验环境的持久化文件存储；云环境仍使用原 OSS 实现。2026.11.26 */
@Component
@Profile("local-storage")
public class LocalOssTool extends OssTool {
    @Value("${storage.local-root:/data/media}") private String root;
    @Value("${oss.bucketUrl:/media/}") private String publicBase;
    @Value("${directory.chunk:/data/chunks/}") private String chunks;

    private Path resolve(String key) {
        Path base = Paths.get(root).toAbsolutePath().normalize();
        Path path = base.resolve(key).normalize();
        if (key.isBlank() || !path.startsWith(base) || path.equals(base))
            throw new IllegalArgumentException("Invalid storage key");
        return path;
    }
    private String key(String category, String filename) {
        String extension = "";
        if (filename != null && filename.lastIndexOf('.') >= 0) {
            String candidate = filename.substring(filename.lastIndexOf('.'));
            if (candidate.matches("\\.[a-zA-Z0-9]{1,10}")) extension = candidate;
        }
        return LocalDate.now() + "/" + category + "/" + UUID.randomUUID() + extension;
    }
    private String write(String key, InputStream input) throws IOException {
        Path path = resolve(key);
        Files.createDirectories(path.getParent());
        try (InputStream stream = input) { Files.copy(stream, path, StandardCopyOption.REPLACE_EXISTING); }
        return publicBase + key;
    }
    @Override public String upload(String folder, String type, String name, InputStream input) {
        try { return write(folder + "/" + name, input); }
        catch (IOException ex) { throw new UncheckedIOException(ex); }
    }
    @Override public String uploadImage(MultipartFile file, String type) throws IOException {
        return write(key("img/" + type, file.getOriginalFilename()), file.getInputStream());
    }
    @Override public String uploadVideo(MultipartFile file) throws IOException {
        return write(key("video", file.getOriginalFilename()), file.getInputStream());
    }
    @Override public String uploadArticle(MultipartFile file) throws IOException {
        return write(key("article", file.getOriginalFilename()), file.getInputStream());
    }
    @Override public String uploadArticle(String content) throws IOException {
        return write(key("article", "content.md"), new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
    }
    @Override public String readText(String url) throws IOException {
        if (!url.startsWith(publicBase)) throw new IllegalArgumentException("Unknown media URL");
        return Files.readString(resolve(url.substring(publicBase.length())), StandardCharsets.UTF_8);
    }
    @Override public boolean uploadFragment(MultipartFile file, String name) throws IOException {
        if (Files.exists(resolve("chunk/" + name))) return false;
        write("chunk/" + name, file.getInputStream()); return true;
    }
    @Override public String appendUploadVideo(String hash) throws IOException {
        if (!hash.matches("[a-zA-Z0-9_-]+")) throw new IllegalArgumentException("Invalid upload hash");
        Path chunkRoot = Paths.get(chunks).toAbsolutePath().normalize();
        if (!Files.exists(chunkRoot.resolve(hash + "-0"))) return null;
        String key = key("video", "video.mp4");
        Path target = resolve(key); Files.createDirectories(target.getParent());
        int count = 0;
        try (OutputStream output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW)) {
            while (Files.exists(chunkRoot.resolve(hash + "-" + count))) {
                Files.copy(chunkRoot.resolve(hash + "-" + count), output); count++;
            }
        }
        for (int i = 0; i < count; i++) Files.delete(chunkRoot.resolve(hash + "-" + i));
        return publicBase + key;
    }
    @Override public int countFiles(String prefix) {
        Path base = Paths.get(root).toAbsolutePath().normalize();
        if (!Files.exists(base)) return 0;
        try (Stream<Path> paths = Files.walk(base)) {
            return (int) paths.filter(Files::isRegularFile)
                    .filter(p -> base.relativize(p).toString().replace('\\','/').startsWith(prefix)).count();
        } catch (IOException ex) { throw new UncheckedIOException(ex); }
    }
    @Override public void deleteFiles(String prefix) {
        if (prefix.isBlank()) throw new IllegalArgumentException("Prefix required");
        resolve(prefix);
        Path base = Paths.get(root).toAbsolutePath().normalize();
        if (!Files.exists(base)) return;
        try (Stream<Path> paths = Files.walk(base)) {
            for (Path path : paths.filter(Files::isRegularFile)
                    .filter(p -> base.relativize(p).toString().replace('\\','/').startsWith(prefix)).toList())
                Files.delete(path);
        } catch (IOException ex) { throw new UncheckedIOException(ex); }
    }
}
