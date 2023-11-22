package com.jordansamhi.androlog;

import com.jordansamhi.androspecter.TmpFolder;
import com.jordansamhi.androspecter.printers.Writer;
import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

/**
 * Handles the preparation of APK files including signing and alignment.
 * This class utilizes external tools specified in a configuration file for APK processing.
 */
public class ApkPreparator {

    private final String apksignerPath;
    private final String zipalignPath;
    private final String apkPath;

    /**
     * Initializes the ApkPreparator with the path of the APK to be processed.
     * Loads the paths for the apksigner and zipalign tools from a configuration file.
     *
     * @param apkPath The file path of the APK to be prepared.
     */
    public ApkPreparator(String apkPath) {
        this.apkPath = apkPath;
        Properties props = new Properties();
        InputStream input = getClass().getClassLoader().getResourceAsStream("config.properties");
        try {
            props.load(input);
        } catch (Exception e) {
            Writer.v().perror("Problem with config file.");
        }

        this.apksignerPath = props.getProperty("apksignerPath");
        this.zipalignPath = props.getProperty("zipalignPath");
    }

    /**
     * Prepares the APK by signing and aligning it.
     * The original APK is replaced by the signed and aligned version.
     */
    public void prepareApk() {
        String signedApkPath = apkPath.replace(".apk", "_signed.apk");
        String alignedApkPath = apkPath.replace(".apk", "_aligned.apk");


        alignApk(alignedApkPath);
        replaceOriginalApk(alignedApkPath);

        signApk(signedApkPath);
        replaceOriginalApk(signedApkPath);
    }

    /**
     * Signs the APK using the apksigner tool.
     * The signed APK is saved to the specified output path.
     *
     * @param outputApk The file path where the signed APK will be saved.
     */
    private void signApk(String outputApk) {
        String keystorePath = extractKeystore();
//        String command = String.format(
//                "%s sign --ks %s --ks-pass pass:android --v2-signing-enabled true --v1-signing-enabled true --out %s --ks-key-alias android %s",
//                apksignerPath, keystorePath, outputApk, apkPath);
        String command = String.format(
                "%s sign --ks %s --ks-pass pass:android --in %s --out %s --ks-key-alias android",
                apksignerPath, keystorePath, apkPath, outputApk);

        executeCommand(command);
        deleteIdsigFile(outputApk);
    }

    /**
     * Deletes the .idsig file associated with the specified APK.
     * This method checks for the existence of a .idsig file, which shares the same base name as the APK,
     * and attempts to delete it. If the deletion process encounters any IOException, an error message
     * is logged
     *
     * @param apkPath The file path of the APK whose .idsig file is to be deleted.
     */
    private void deleteIdsigFile(String apkPath) {
        try {
            Path idsigPath = Paths.get(apkPath.replace(".apk", ".apk.idsig"));
            if (Files.exists(idsigPath)) {
                Files.delete(idsigPath);
            }
        } catch (IOException e) {
            Writer.v().perror("Problem with deleting the .idsig file: " + e.getMessage());
        }
    }


    /**
     * Aligns the APK using the zipalign tool.
     * The aligned APK is saved to the specified output path.
     *
     * @param outputApk The file path where the aligned APK will be saved.
     */
    private void alignApk(String outputApk) {
        String command = String.format("%s -v 4 %s %s", zipalignPath, apkPath, outputApk);
        executeCommand(command);
    }

    /**
     * Executes a given command in the system's runtime environment.
     *
     * @param command The command to be executed.
     */
    private void executeCommand(String command) {
        try {
            Process process = Runtime.getRuntime().exec(command);
            process.waitFor();
        } catch (Exception e) {
            Writer.v().perror(String.format("Problem with the execution of the command: %s", command));
        }
    }

    /**
     * Extracts the keystore file from the application's resources and saves it in a temporary directory.
     * This method retrieves the keystore file using a class loader, and then copies it to a specified temporary folder.
     * If the extraction process encounters any issues, an error is logged.
     *
     * @return The absolute path of the extracted keystore file.
     */
    private String extractKeystore() {
        File keystore = null;
        try {
            InputStream is_keystore = getClass().getClassLoader().getResourceAsStream("keystore.keystore");
            keystore = new File(String.format("%s/%s", TmpFolder.v().get(),
                    "keystore.keystore"));
            FileUtils.copyInputStreamToFile(is_keystore, keystore);
        } catch (Exception e) {
            Writer.v().perror("Problem with the keystore");
        }
        return keystore.getAbsolutePath();
    }


    /**
     * Replaces the original APK with a new one at a specified path.
     * If the replacement is unsuccessful, an error is logged.
     *
     * @param newPath The file path of the new APK to replace the original.
     */
    private void replaceOriginalApk(String newPath) {
        try {
            Files.move(Paths.get(newPath), Paths.get(apkPath), StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            Writer.v().perror("Problem with the replacement of the new APK");
        }
    }
}