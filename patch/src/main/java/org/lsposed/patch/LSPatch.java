package org.lsposed.patch;

import static org.lsposed.lspatch.share.Constants.CONFIG_ASSET_PATH;
import static org.lsposed.lspatch.share.Constants.LOADER_DEX_PATH;
import static org.lsposed.lspatch.share.Constants.ORIGINAL_APK_ASSET_PATH;
import static org.lsposed.lspatch.share.Constants.PROXY_APP_COMPONENT_FACTORY;

import com.android.tools.build.apkzlib.sign.SigningExtension;
import com.android.tools.build.apkzlib.sign.SigningOptions;
import com.android.tools.build.apkzlib.zip.AlignmentRules;
import com.android.tools.build.apkzlib.zip.NestedZip;
import com.android.tools.build.apkzlib.zip.StoredEntry;
import com.android.tools.build.apkzlib.zip.ZFile;
import com.android.tools.build.apkzlib.zip.ZFileOptions;
import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.gson.Gson;
import com.wind.meditor.core.ManifestEditor;
import com.wind.meditor.property.AttributeItem;
import com.wind.meditor.property.ModificationProperty;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.lsposed.lspatch.share.Constants;
import org.lsposed.lspatch.share.ConstantsM;
import org.lsposed.lspatch.share.ExtraConfig;
import org.lsposed.lspatch.share.PatchConfig;
import org.lsposed.patch.util.ApkSignatureHelper;
import org.lsposed.patch.util.ManifestParser;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@SuppressWarnings({"FieldCanBeLocal", "FieldMayBeFinal", "ResultOfMethodCallIgnored"})
public final class LSPatch {
    @SuppressWarnings("unused")
    static class PatchError extends Error {
        public PatchError(String message, Throwable cause) {
            super(message, cause);
        }

        PatchError(String message) {
            super(message);
        }
    }

    @Parameter(description = "apks") @SuppressWarnings("MismatchedQueryAndUpdateOfCollection")
    private List<String> apkPaths = new ArrayList<>();

    @Parameter(names = {"-h", "--help"}, order = 0, help = true, description = "Print this message")
    private boolean help = false;

    @Parameter(names = {"-o", "--output"}, order = 1, help = true, description = "Output directory")
    private String outputPath;

    @Parameter(names = {"-f", "--force"}, order = 2, help = true, description = "Force overwrite output file")
    private boolean forceOverwrite = false;

    @Parameter(names = {"-ks", "--keystore"}, order = 3, help = true, description = "Sign with external keystore file")
    private String userKey = null;

    @Parameter(names = {"-ksp", "--ks-prompt"}, order = 4, help = true, description = "Prompt for keystore alias details")
    private boolean userKeyPrompt = false;

    @Parameter(names = {"-p", "--patch"}, order = 5, help = true, description = "Patch forcibly. Enable patch for all fb apps.")
    private boolean patchForcibly = false;

    @Parameter(names = {"--out-file"}, hidden = true, description = "[Internal Option] absolute path for output")
    private String internalOutputPath;

    @Parameter(names = {"--temp-dir"}, hidden = true, description = "[Internal Option] temp directory path")
    private String internalTempDir;

    private boolean isExtraApp = false;

    private static final List<String> DEFAULT_PATCHABLE_PACKAGE = ImmutableList.of(
        "com.facebook.orca",
        "com.facebook.katana"
    );

    private static final String ANDROID_MANIFEST_XML = "AndroidManifest.xml";

    private static final Map<String, String> ARCH_LIBRARY_MAP = ImmutableMap.of(
        "arm",    "armeabi-v7a",
        "arm64",  "arm64-v8a",
        "x86",    "x86",
        "x86_64", "x86_64"
    );

    private static final String DEFAULT_SIGNING_KEY = "assets/mrvkey";

    private static final ZFileOptions Z_FILE_OPTIONS = new ZFileOptions().setAlignmentRule(
        AlignmentRules.compose(
            AlignmentRules.constantForSuffix(".so", 4096),
            AlignmentRules.constantForSuffix(ORIGINAL_APK_ASSET_PATH, 4096)
        )
    );

    private final static String PATCHED_SUFFIX = "-mrv.apk";
    private final static String SIGNED_SUFFIX = "-signed.apk";

    private KeyStore.PrivateKeyEntry signingKey;
    private static final String DEFAULT_SIGNER_NAME = "facebook";
    private static final char[] DEFAULT_KEYPASS = "123456".toCharArray();

    private static OutputLogger logger = new OutputLogger() {
        @Override
        public void v(String msg) { System.out.println(msg); }
        @Override
        public void d(String msg) { System.out.println(" -> " + msg); }
        @Override
        public void e(String msg) { System.err.println("\nError: " + msg + "\n"); }
    };

    private final JCommander jCommander;

    public LSPatch(String... args) {
        jCommander = JCommander.newBuilder().addObject(this).build();
        jCommander.setProgramName("MRVPatcher");
        jCommander.parse(args);
    }

    public static void main(String... args) {
        try {
            LSPatch lsPatch = new LSPatch(args);
            lsPatch.help |= args.length == 0;
            lsPatch.doCommandLine();
        } catch (Throwable throwable) {
            logger.e(getError(throwable));
        }
    }

    public void doCommandLine() {
        if (help) {
            jCommander.usage();
            return;
        }
        if (apkPaths == null || apkPaths.isEmpty()) {
            logger.e(" Please provide apk files");
            jCommander.usage();
            return;
        }
        try {
            setupSigningKey();
        } catch (Exception exception) {
            logger.e(getError(exception));
            return;
        }
        final boolean multiple = apkPaths.size() > 1;
        if (multiple && internalOutputPath != null) {
            throw new AssertionError();
        }
        final Map<String, String> results = new HashMap<>();
        for (var apk : apkPaths) {
            logger.v("\nSource: " + apk);
            results.put(apk, "[failed!] " + apk);

            final File srcApkFile = new File(apk).getAbsoluteFile();
            final String srcFileName = srcApkFile.getName();

            if (!srcApkFile.isFile()) {
                logger.e("'" + srcFileName + "' does not exist");
                if (multiple) logger.v("Skipping...");
                continue;
            }

            if (outputPath == null) outputPath = ".";
            final File outputDir = new File(outputPath);
            if (!outputDir.exists()) outputDir.mkdirs();

            logger.v("\nProgress:");
            logger.d("parsing manifest");

            String packageName;
            String appComponentFactory;
            try (var zFile = ZFile.openReadOnly(srcApkFile)) {
                var manifestEntry = zFile.get(ANDROID_MANIFEST_XML);
                if (manifestEntry == null) {
                    logger.e("'" + srcFileName + "' is not a valid apk file");
                    if (multiple) logger.v("Skipping...");
                    continue;
                }
                try (var is = manifestEntry.open()) {
                    var pair = ManifestParser.parseManifestFile(is);
                    if (pair == null || pair.appComponentFactory == null || pair.packageName == null ||
                        pair.appComponentFactory.isEmpty() || pair.packageName.isEmpty()) {
                        logger.e("Failed to parse Manifest");
                        if (multiple) logger.v("Skipping...");
                        continue;
                    }
                    packageName = pair.packageName;
                    appComponentFactory = pair.appComponentFactory;
                }
            } catch (IOException exception) {
                logger.e(getError(exception));
                if (multiple) logger.v("Skipping...");
                continue;
            }
            isExtraApp = !ConstantsM.DEFAULT_FB_PACKAGES.contains(packageName);

            if (!packageName.startsWith(ConstantsM.VALID_FB_PACKAGE_PREFIX)) {
                logger.e("'" + packageName + "' is not a facebook app");
                if (multiple) logger.v("Skipping...");
                continue;
            }

            if (appComponentFactory.equals(PROXY_APP_COMPONENT_FACTORY)) {
                logger.e(srcFileName + " file is already patched");
                if (multiple) logger.v("Skipping...");
                continue;
            }

            final boolean signOnly = !patchForcibly && !DEFAULT_PATCHABLE_PACKAGE.contains(packageName);

            final File outputFile = (internalOutputPath != null ?
                new File(internalOutputPath) :
                new File(outputDir, FilenameUtils.getBaseName(srcFileName) + (signOnly ? SIGNED_SUFFIX : PATCHED_SUFFIX))
            ).getAbsoluteFile();

            if (outputFile.exists() && (!forceOverwrite || !outputFile.delete())) {
                if (forceOverwrite) {
                    logger.e("Couldn't overwrite '" + outputFile.getName() + "'. Delete manually.");
                } else {
                    logger.e("'" + outputFile.getName() + "' already exists. Use -f to overwrite.");
                }
                logger.v("Aborting...");
                break;
            }

            try {
                var relative = getRelativePath(outputFile);
                if (signOnly) {
                    sign(srcApkFile, outputFile);
                    results.replace(apk, "[rsigned] " + relative);
                } else {
                    patch(srcApkFile, outputFile, appComponentFactory);
                    results.replace(apk, "[patched] " + relative);
                }
                logger.v("Finished");
            } catch (PatchError error) {
                String err = error.getMessage();
                if (error.getCause() != null) {
                    err += " [" + error.getCause().getClass().getSimpleName();
                    err += " - " + error.getCause().getMessage() + "]";
                }
                logger.e(err);
                if (multiple) logger.v("Skipping...");
            }
        }

        if (results.values().stream().anyMatch(r -> !r.startsWith("[failed!]"))) {
            logger.v("\nOutput:");
            results.values().stream().sorted().forEach(r -> logger.v(" " + r));
            logger.v("");
        }
    }

    public void patch(File srcApkFile, File outputFile, String appComponentFactory) throws PatchError {
        final File internalApk;
        try {
            internalApk = getTempFile(internalTempDir, srcApkFile.getName());
            internalApk.delete();
        } catch (IOException e) {
            throw new PatchError("Failed to create temp file", e);
        }

        logger.d("patching files");
        try (var dstZFile = ZFile.openReadWrite(internalApk, Z_FILE_OPTIONS);
             var srcZFile = dstZFile.addNestedZip((ignore) -> ORIGINAL_APK_ASSET_PATH, srcApkFile, false)
        ) {
            var manifest = Objects.requireNonNull(srcZFile.get(ANDROID_MANIFEST_XML));
            try (var is = new ByteArrayInputStream(patchManifest(srcApkFile, manifest.open()))) {
                dstZFile.add(ANDROID_MANIFEST_XML, is);
            } catch (Exception e) {
                throw new PatchError("Failed to attach manifest", e);
            }

            ARCH_LIBRARY_MAP.forEach((arch, lib) -> {
                String asset = Constants.getLibraryPath(lib);
                String entry = Constants.getLibrarySoPath(arch);
                try (var is = getClass().getClassLoader().getResourceAsStream(asset)) {
                    dstZFile.add(entry, is, false);
                } catch (Exception e) {
                    throw new PatchError("Failed to attach native libs", e);
                }
            });

            try (var is = getClass().getClassLoader().getResourceAsStream(Constants.META_LOADER_DEX_PATH)) {
                dstZFile.add("classes.dex", is);
            } catch (Exception e) {
                throw new PatchError("Failed to attach dex", e);
            }

            try (var is = getClass().getClassLoader().getResourceAsStream(LOADER_DEX_PATH)) {
                dstZFile.add(LOADER_DEX_PATH, is);
            } catch (Exception e) {
                throw new PatchError("Failed to attach assets", e);
            }

            var config = new PatchConfig(appComponentFactory);
            var configBytes = new Gson().toJson(config).getBytes(StandardCharsets.UTF_8);
            try (var is = new ByteArrayInputStream(configBytes)) {
                dstZFile.add(CONFIG_ASSET_PATH, is);
            } catch (Exception e) {
                throw new PatchError("Failed to save config", e);
            }

            try {
                registerApkSigner(dstZFile);
            } catch (IOException | GeneralSecurityException e) {
                throw new PatchError("Failed to register apk signer", e);
            }
            NestedZip nested = (NestedZip) srcZFile;
            for (StoredEntry entry : nested.entries()) {
                String name = entry.getCentralDirectoryHeader().getName();
                if (name.startsWith("classes") && name.endsWith(".dex")) continue;
                if (dstZFile.get(name) != null) continue;
                if (name.equals("AndroidManifest.xml")) continue;
                if (name.startsWith("META-INF") && (name.endsWith(".SF") || name.endsWith(".MF") || name.endsWith(".RSA"))) continue;
                nested.addFileLink(name, name);
            }

            logger.d("generating apk");
            dstZFile.realign();
            dstZFile.close();
            try {
                outputFile.delete();
                FileUtils.moveFile(internalApk, outputFile);
            } catch (IOException e) {
                throw new PatchError("Failed to generate apk", e);
            }
        } catch (IOException e) {
            throw new PatchError("Failed to patch apk", e);
        } finally {
            internalApk.delete();
        }
    }

    public void sign(File srcApkFile, File outputFile) throws PatchError {
        File internalApk;
        try {
            internalApk = getTempFile(internalTempDir, srcApkFile.getName());
            internalApk.delete();
        } catch (IOException e) {
            throw new PatchError("Failed to create temp file", e);
        }
        logger.d("generating apk");
        try {
            FileUtils.copyFile(srcApkFile, internalApk);
            try (var dstZFile = ZFile.openReadWrite(internalApk)) {
                registerApkSigner(dstZFile);
                dstZFile.realign();
                dstZFile.close();
                outputFile.delete();
                FileUtils.moveFile(internalApk, outputFile);
            }
        } catch (IOException | GeneralSecurityException e) {
            throw new PatchError("Failed to sign apk", e);
        } finally {
            internalApk.delete();
        }
    }

    private byte[] patchManifest(File srcApkFile, InputStream is) throws IOException {
        ModificationProperty property = new ModificationProperty();
        property.addUsesPermission("android.permission.QUERY_ALL_PACKAGES");
        property.addApplicationAttribute(new AttributeItem("appComponentFactory", PROXY_APP_COMPONENT_FACTORY));
        if (isExtraApp) {
            logger.d("adding metadata");
            try {
                var signature = ApkSignatureHelper.getApkSignInfo(srcApkFile.getAbsolutePath());
                var config = new Gson().toJson(new ExtraConfig(signature)).getBytes(StandardCharsets.UTF_8);
                var metadata = Base64.getEncoder().encodeToString(config);
                property.addMetaData(new ModificationProperty.MetaData(ExtraConfig.KEY, metadata));
            } catch (Throwable ignored) {}
        }
        var os = new ByteArrayOutputStream();
        new ManifestEditor(is, os, property).processManifest();
        is.close(); os.flush(); os.close();
        return os.toByteArray();
    }

    private void setupSigningKey() throws IOException, GeneralSecurityException {
        String keyAlias = null;
        char[] keyPass, keyAliasPass;
        if (userKey != null) {
            if (!new File(userKey).exists()) {
                throw new KeyStoreException("Keystore file doesn't exist");
            }
            keyPass = System.console().readPassword("\nKeystore password: ");
            if (userKeyPrompt) {
                keyAlias = System.console().readLine("Keystore alias: ");
                keyAliasPass = System.console().readPassword("Keystore alias password: ");
                if (keyAliasPass.length == 0) keyAliasPass = keyPass;
            } else {
                keyAliasPass = keyPass;
            }
        } else {
            keyPass = DEFAULT_KEYPASS;
            keyAlias = DEFAULT_SIGNER_NAME;
            keyAliasPass = keyPass;
        }
        var keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
        try (var is = userKey != null ?
            new FileInputStream(new File(userKey).getAbsoluteFile()) :
            getClass().getClassLoader().getResourceAsStream(getDefaultKey())) {
            keyStore.load(is, keyPass);
        }
        if (keyAlias == null) keyAlias = keyStore.aliases().nextElement();
        signingKey = (KeyStore.PrivateKeyEntry) keyStore.getEntry(keyAlias, new KeyStore.PasswordProtection(keyAliasPass));
        if (signingKey == null) throw new KeyStoreException("Keystore entry not found: " + keyAlias);
    }

    private void registerApkSigner(ZFile zFile) throws IOException, GeneralSecurityException {
        new SigningExtension(SigningOptions.builder()
            .setMinSdkVersion(28)
            .setV1SigningEnabled(true)
            .setV2SigningEnabled(true)
            .setCertificates((X509Certificate[]) signingKey.getCertificateChain())
            .setKey(signingKey.getPrivateKey())
            .build()
        ).register(zFile);
    }

    private static String getDefaultKey() {
        try {
            Class.forName("android.os.Build");
            return DEFAULT_SIGNING_KEY + ".bks";
        } catch (ClassNotFoundException e) {
            return DEFAULT_SIGNING_KEY + ".jks";
        }
    }

    private static File getTempFile(String dir, String name) throws IOException {
        return dir == null ? Files.createTempFile("mrv-" + name, "-internal").toFile() :
            Files.createTempFile(new File(dir).toPath(), "mrv-" + name, "-internal").toFile() ;
    }

    private static String getRelativePath(File file) {
        try {
           return new File("").getAbsoluteFile().toPath().relativize(file.toPath()).toString();
        } catch (Throwable throwable) { return file.getPath(); }
    }

    private static String getError(Throwable t) {
        return t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
    }

    @SuppressWarnings("unused")
    public static void setOutputLogger(OutputLogger logger) {
        LSPatch.logger = Objects.requireNonNull(logger);
    }
}
