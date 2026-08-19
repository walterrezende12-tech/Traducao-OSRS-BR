package com.osrstranslate;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

final class RemoteTranslationService {
    static final String DEFAULT_MANIFEST_URL =
        "https://raw.githubusercontent.com/walterrezende12-tech/"
            + "osrs-translate-translations/main/manifest.json";

    static final List<String> REQUIRED_FILES = Collections.unmodifiableList(Arrays.asList(
        "translations.json",
        "translations_skills.json",
        "translations_quests.json",
        "translations_items.json",
        "translations_menu.json",
        "translations_overhead.json",
        "translations_game_message.json",
        "translations_welcome.json",
        "translations_settings.json"
    ));

    private static final int SUPPORTED_SCHEMA_VERSION = 1;
    private static final int MAX_MANIFEST_BYTES = 1_000_000;
    private static final long MAX_TRANSLATION_BYTES = 64L * 1024L * 1024L;
    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9._-]+");
    private static final Pattern SHA_256 = Pattern.compile("[a-fA-F0-9]{64}");
    private final OkHttpClient httpClient;
    private final Gson gson;
    private final File cacheRoot;

    RemoteTranslationService(OkHttpClient httpClient, Gson gson, File cacheRoot) {
        this.httpClient = httpClient;
        this.gson = gson;
        this.cacheRoot = cacheRoot;
    }

    File getActiveDirectory(String language) {
        String safeLanguage;
        try {
            safeLanguage = requireSafeName(language, "idioma");
        } catch (IOException e) {
            return null;
        }

        File languageRoot = new File(cacheRoot, safeLanguage);
        File activeFile = new File(languageRoot, "active.txt");
        if (!activeFile.isFile()) {
            return null;
        }

        try {
            String releaseId = Files.readString(activeFile.toPath(), StandardCharsets.UTF_8).trim();
            if (!SAFE_NAME.matcher(releaseId).matches()) {
                return null;
            }

            File releaseDirectory = new File(new File(languageRoot, "releases"), releaseId);
            return isCompleteRelease(releaseDirectory) ? releaseDirectory : null;
        } catch (IOException e) {
            return null;
        }
    }

    UpdateResult update(String manifestUrl, String language) throws IOException {
        URI manifestUri = requireHttpsUri(manifestUrl, "manifesto");
        RemoteManifest manifest = readManifest(manifestUri);
        validateManifest(manifest);

        String safeLanguage = requireSafeName(language, "idioma");
        LanguageManifest languageManifest = manifest.languages.get(safeLanguage);
        if (languageManifest == null || languageManifest.files == null) {
            throw new IOException("Idioma nao encontrado no manifesto: " + safeLanguage);
        }

        String releaseId = buildReleaseId(manifest.version, languageManifest);
        File languageRoot = new File(cacheRoot, safeLanguage);
        File releasesRoot = new File(languageRoot, "releases");
        File releaseDirectory = new File(releasesRoot, releaseId);
        File previousDirectory = getActiveDirectory(safeLanguage);
        boolean installed = false;

        if (!isValidRelease(releaseDirectory, languageManifest)) {
            deleteDirectory(releaseDirectory.toPath());
            installRelease(manifestUri, languageManifest, releasesRoot, releaseDirectory);
            installed = true;
        }

        activateRelease(languageRoot, releaseId);
        deleteOldReleases(releasesRoot, releaseId);
        boolean changed = installed
            || previousDirectory == null
            || !previousDirectory.equals(releaseDirectory);
        return new UpdateResult(changed, releaseDirectory, manifest.version);
    }

    private RemoteManifest readManifest(URI manifestUri) throws IOException {
        byte[] bytes = downloadBytes(manifestUri, MAX_MANIFEST_BYTES);
        try {
            RemoteManifest manifest = gson.fromJson(
                new String(bytes, StandardCharsets.UTF_8),
                RemoteManifest.class
            );
            if (manifest == null) {
                throw new IOException("Manifesto remoto vazio");
            }
            return manifest;
        } catch (JsonParseException e) {
            throw new IOException("Manifesto remoto invalido", e);
        }
    }

    private void validateManifest(RemoteManifest manifest) throws IOException {
        if (manifest.schemaVersion != SUPPORTED_SCHEMA_VERSION) {
            throw new IOException("Versao de schema nao suportada: " + manifest.schemaVersion);
        }
        if (manifest.version == null || manifest.version.trim().isEmpty()) {
            throw new IOException("Manifesto sem versao");
        }
        if (manifest.languages == null || manifest.languages.isEmpty()) {
            throw new IOException("Manifesto sem idiomas");
        }
    }

    private void installRelease(
        URI manifestUri,
        LanguageManifest languageManifest,
        File releasesRoot,
        File releaseDirectory
    ) throws IOException {
        Files.createDirectories(releasesRoot.toPath());
        Path stagingDirectory = Files.createTempDirectory(releasesRoot.toPath(), ".staging-");
        boolean installed = false;

        try {
            for (String fileName : REQUIRED_FILES) {
                TranslationFile remoteFile = languageManifest.files.get(fileName);
                validateFileEntry(fileName, remoteFile);

                URI fileUri = requireHttpsUri(
                    manifestUri.resolve(remoteFile.url).toString(),
                    fileName
                );
                Path target = stagingDirectory.resolve(fileName);
                downloadAndValidate(fileUri, target, remoteFile.sha256);
            }

            Files.writeString(stagingDirectory.resolve(".complete"), "ok\n", StandardCharsets.UTF_8);
            moveDirectory(stagingDirectory, releaseDirectory.toPath());
            installed = true;
        } finally {
            if (!installed) {
                try {
                    deleteDirectory(stagingDirectory);
                } catch (IOException ignored) {
                    // Preserve the original download or validation failure.
                }
            }
        }
    }

    private void validateFileEntry(String fileName, TranslationFile remoteFile) throws IOException {
        if (remoteFile == null || remoteFile.url == null || remoteFile.url.trim().isEmpty()) {
            throw new IOException("URL ausente para " + fileName);
        }
        if (remoteFile.sha256 == null || !SHA_256.matcher(remoteFile.sha256).matches()) {
            throw new IOException("SHA-256 invalido para " + fileName);
        }
    }

    private void downloadAndValidate(URI uri, Path target, String expectedSha256) throws IOException {
        MessageDigest digest = sha256Digest();
        Request request = request(uri);

        try (Response response = httpClient.newCall(request).execute()) {
            ResponseBody body = requireSuccessfulBody(response, uri, MAX_TRANSLATION_BYTES);
            long total = 0;

            try (InputStream input = body.byteStream();
                 OutputStream output = Files.newOutputStream(target)) {
                byte[] buffer = new byte[16 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    total += read;
                    if (total > MAX_TRANSLATION_BYTES) {
                        throw new IOException("Arquivo remoto excede o limite: " + uri);
                    }
                    digest.update(buffer, 0, read);
                    output.write(buffer, 0, read);
                }
            }
        }

        String actualSha256 = toHex(digest.digest());
        if (!actualSha256.equals(expectedSha256.toLowerCase(Locale.ROOT))) {
            throw new IOException("SHA-256 divergente para " + target.getFileName());
        }

        try (InputStream input = new FileInputStream(target.toFile())) {
            TranslationLookupHelper.parseJsonMapStrict(input);
        } catch (Exception e) {
            throw new IOException("JSON de traducao invalido: " + target.getFileName(), e);
        }
    }

    private byte[] downloadBytes(URI uri, int maximumBytes) throws IOException {
        Request request = request(uri);
        try (Response response = httpClient.newCall(request).execute()) {
            ResponseBody body = requireSuccessfulBody(response, uri, maximumBytes);
            try (InputStream input = body.byteStream();
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8 * 1024];
                int total = 0;
                int read;
                while ((read = input.read(buffer)) != -1) {
                    total += read;
                    if (total > maximumBytes) {
                        throw new IOException("Resposta remota excede o limite: " + uri);
                    }
                    output.write(buffer, 0, read);
                }
                return output.toByteArray();
            }
        }
    }

    private Request request(URI uri) {
        return new Request.Builder()
            .get()
            .header("Accept", "application/vnd.github.raw+json, application/json")
            .header("User-Agent", "OSRS-Translate-PT-BR/1.0")
            .url(uri.toString())
            .build();
    }

    private ResponseBody requireSuccessfulBody(Response response, URI uri, long maximumBytes)
        throws IOException {
        if (!response.isSuccessful()) {
            throw new IOException("HTTP " + response.code() + " ao baixar " + uri);
        }
        if (!response.request().url().isHttps()) {
            throw new IOException("Redirecionamento para URL nao segura: " + response.request().url());
        }

        ResponseBody body = response.body();
        if (body == null) {
            throw new IOException("Resposta remota vazia: " + uri);
        }
        if (body.contentLength() > maximumBytes) {
            throw new IOException("Resposta remota excede o limite: " + uri);
        }
        return body;
    }

    private void activateRelease(File languageRoot, String releaseId) throws IOException {
        Files.createDirectories(languageRoot.toPath());
        Path temporary = Files.createTempFile(languageRoot.toPath(), "active-", ".tmp");
        try {
            Files.writeString(temporary, releaseId + "\n", StandardCharsets.UTF_8);
            moveFile(temporary, new File(languageRoot, "active.txt").toPath());
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private boolean isCompleteRelease(File directory) {
        if (!new File(directory, ".complete").isFile()) {
            return false;
        }
        for (String fileName : REQUIRED_FILES) {
            if (!new File(directory, fileName).isFile()) {
                return false;
            }
        }
        return true;
    }

    private boolean isValidRelease(File directory, LanguageManifest languageManifest) {
        if (!isCompleteRelease(directory)) {
            return false;
        }

        try {
            for (String fileName : REQUIRED_FILES) {
                TranslationFile remoteFile = languageManifest.files.get(fileName);
                validateFileEntry(fileName, remoteFile);
                if (!sha256(new File(directory, fileName))
                    .equals(remoteFile.sha256.toLowerCase(Locale.ROOT))) {
                    return false;
                }
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private String buildReleaseId(String version, LanguageManifest languageManifest)
        throws IOException {
        MessageDigest digest = sha256Digest();
        for (String fileName : REQUIRED_FILES) {
            TranslationFile remoteFile = languageManifest.files.get(fileName);
            validateFileEntry(fileName, remoteFile);
            digest.update(fileName.getBytes(StandardCharsets.UTF_8));
            digest.update(remoteFile.sha256.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
        }

        String safeVersion = version.replaceAll("[^A-Za-z0-9._-]", "_");
        if (safeVersion.isEmpty()) {
            safeVersion = "release";
        }
        return safeVersion + "-" + toHex(digest.digest()).substring(0, 12);
    }

    private URI requireHttpsUri(String value, String label) throws IOException {
        try {
            URI uri = new URI(value == null ? "" : value.trim());
            if (!uri.isAbsolute() || !"https".equalsIgnoreCase(uri.getScheme())) {
                throw new IOException("Apenas URL HTTPS e permitida para " + label);
            }
            return uri;
        } catch (URISyntaxException e) {
            throw new IOException("URL invalida para " + label, e);
        }
    }

    private String requireSafeName(String value, String label) throws IOException {
        if (value == null || !SAFE_NAME.matcher(value.trim()).matches()) {
            throw new IOException(label + " invalido");
        }
        return value.trim();
    }

    private MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponivel", e);
        }
    }

    private String toHex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format("%02x", value & 0xff));
        }
        return result.toString();
    }

    private String sha256(File file) throws IOException {
        MessageDigest digest = sha256Digest();
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return toHex(digest.digest());
    }

    private void moveDirectory(Path source, Path target) throws IOException {
        if (Files.exists(target)) {
            deleteDirectory(source);
            return;
        }
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target);
        }
    }

    private void moveFile(Path source, Path target) throws IOException {
        try {
            Files.move(
                source,
                target,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            );
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void deleteDirectory(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(directory)) {
            List<Path> orderedPaths = paths
                .sorted(Comparator.reverseOrder())
                .collect(Collectors.toList());
            for (Path path : orderedPaths) {
                Files.deleteIfExists(path);
            }
        }
    }

    private void deleteOldReleases(File releasesRoot, String activeReleaseId) {
        File[] releases = releasesRoot.listFiles(File::isDirectory);
        if (releases == null) {
            return;
        }
        for (File release : releases) {
            if (!release.getName().equals(activeReleaseId)
                && !release.getName().startsWith(".staging-")) {
                try {
                    deleteDirectory(release.toPath());
                } catch (IOException ignored) {
                    // Old cache cleanup must not invalidate an activated release.
                }
            }
        }
    }

    static final class UpdateResult {
        private final boolean changed;
        private final File activeDirectory;
        private final String version;

        private UpdateResult(boolean changed, File activeDirectory, String version) {
            this.changed = changed;
            this.activeDirectory = activeDirectory;
            this.version = version;
        }

        boolean isChanged() {
            return changed;
        }

        File getActiveDirectory() {
            return activeDirectory;
        }

        String getVersion() {
            return version;
        }
    }

    private static final class RemoteManifest {
        private int schemaVersion;
        private String version;
        private Map<String, LanguageManifest> languages;
    }

    private static final class LanguageManifest {
        private Map<String, TranslationFile> files;
    }

    private static final class TranslationFile {
        private String url;
        private String sha256;
    }
}
