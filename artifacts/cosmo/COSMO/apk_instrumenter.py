#!/usr/bin/env python3

import io
import logging
import os
import platform
import shutil
import subprocess
import tempfile
import zipfile

logger = logging.getLogger(__name__)


SCRIPT_DIR = os.path.dirname(os.path.realpath(__file__))


class ApkInstrumenter(object):

    if platform.system() == "Windows":
        DEX2JAR_PATH = "d2j-dex2jar.bat"
    else:
        DEX2JAR_PATH = "/home/jordan/experiments/androlog/cosmo/COSMO/dex2jar/dex-tools/build/distributions/dex-tools-2.x/d2j-dex2jar.sh"

    JAVA_PATH = "java"
    DX_PATH = "/home/jordan/android-sdk-linux/build-tools/27.0.3/dx"
    ZIPALIGN_PATH = "/home/jordan/android-sdk-linux/build-tools/33.0.0/zipalign"
    APKSIGNER_PATH = "/home/jordan/android-sdk-linux/build-tools/33.0.0/apksigner"

    def __init__(self, apk_path: str):
        self.apk_path = os.path.normpath(apk_path)
        self.jacoco_cli_path = os.path.join(
            os.path.dirname(os.path.realpath(__file__)), "lib", "jacococli.jar"
        )
        self.jacoco_agent_path = os.path.join(
            os.path.dirname(os.path.realpath(__file__)), "lib", "jacocoagent.jar"
        )

    def run_instrumentation(self):
        try:
            logger.info('Instrumenting apk file "{0}"'.format(self.apk_path))
            self.check_requirements()
            self.parse_android_apk()
            self.instrument_apk()
        except Exception as e:
            logger.critical(
                "Error during apk file instrumentation: {0}".format(e), exc_info=True
            )
            raise

    def check_requirements(self):
        """
        Make sure all the needed tools are available and ready to be used.
        """

        # Make sure to use the full path of the executable (needed for cross-platform
        # compatibility).
        full_java_path = shutil.which(self.JAVA_PATH)
        full_dex2jar_path = shutil.which(self.DEX2JAR_PATH)
        full_dx_path = shutil.which(self.DX_PATH)
        full_zipalign_path = shutil.which(self.ZIPALIGN_PATH)
        full_apksigner_path = shutil.which(self.APKSIGNER_PATH)

        if full_java_path is None:
            raise RuntimeError(
                'Something is wrong with executable "{0}", please make sure Java 8 or '
                "greater is properly installed and configured".format(self.JAVA_PATH)
            )
        else:
            self.JAVA_PATH = full_java_path

        if full_dex2jar_path is None:
            raise RuntimeError(
                'Something is wrong with executable "{0}", please make sure dex2jar '
                "(https://github.com/pxb1988/dex2jar) is properly installed and "
                "configured".format(self.DEX2JAR_PATH)
            )
        else:
            self.DEX2JAR_PATH = full_dex2jar_path

        if full_dx_path is None:
            raise RuntimeError(
                'Something is wrong with executable "{0}", please make sure Android '
                "SDK is properly installed and configured, and the latest version of "
                "build-tools directory is added to PATH".format(self.DX_PATH)
            )
        else:
            self.DX_PATH = full_dx_path

        if full_zipalign_path is None:
            raise RuntimeError(
                'Something is wrong with executable "{0}", please make sure Android '
                "SDK is properly installed and configured, and the latest version of "
                "build-tools directory is added to PATH".format(self.ZIPALIGN_PATH)
            )
        else:
            self.ZIPALIGN_PATH = full_zipalign_path

        if full_apksigner_path is None:
            raise RuntimeError(
                'Something is wrong with executable "{0}", please make sure Android '
                "SDK is properly installed and configured, and the latest version of "
                "build-tools directory is added to PATH".format(self.APKSIGNER_PATH)
            )
        else:
            self.APKSIGNER_PATH = full_apksigner_path

    def parse_android_apk(self):
        """
        Check if a file is a valid Android application (contains a manifest file).

        :return: True if the provided file contains an "AndroidManifest.xml" file, otherwise
                 an exception is raised.
        """
        if not os.path.isfile(self.apk_path):
            raise FileNotFoundError(
                'Invalid Android application "{0}"'.format(self.apk_path)
            )

        with zipfile.ZipFile(self.apk_path, "r") as current_apk:
            # Check if the current apk contains an "AndroidManifest.xml" file.
            if not any(
                entry.filename == "AndroidManifest.xml"
                for entry in current_apk.infolist()
            ):
                raise ValueError(
                    'Invalid Android application "{0}"'.format(self.apk_path)
                )

        return True

    def instrument_apk(self):
        # Use a temp dir to save the intermediate files.
        working_dir = tempfile.mkdtemp()

        target_apk_path = shutil.copy2(
            self.apk_path,
            os.path.join(working_dir, os.path.basename(self.apk_path)),
        )
        # Use the apk name also for the jar file.
        classes_jar_path = os.path.join(
            working_dir,
            "{0}.jar".format(os.path.splitext(os.path.basename(self.apk_path))[0]),
        )
        instrumented_jacoco_path = os.path.join(working_dir, "jacoco")
        instrumented_dex_path = os.path.join(working_dir, "dex")

        try:
            dex2jar_cmd = [
                self.DEX2JAR_PATH,
                "-f",
                "-e",
                os.devnull,  # Don't save the zip archive with the errors.
                target_apk_path,
                "-o",
                classes_jar_path,
            ]

            logger.info(
                'Converting to Java bytecode with dex2jar command "{0}"'.format(
                    " ".join(dex2jar_cmd)
                )
            )
            subprocess.check_output(dex2jar_cmd, stderr=subprocess.STDOUT)

        except subprocess.CalledProcessError as e:
            logger.error(
                "Error during dex2jar command: {0}".format(
                    e.output.decode(errors="replace") if e.output else e
                )
            )
            raise
        except Exception as e:
            logger.error("Error during dex2jar command: {0}".format(e))
            raise

        try:
            instrument_cmd = [
                self.JAVA_PATH,
                "-jar",
                self.jacoco_cli_path,
                "instrument",
                classes_jar_path,
                "--dest",
                instrumented_jacoco_path,
            ]

            logger.info(
                'Instrumenting with Java command "{0}"'.format(" ".join(instrument_cmd))
            )
            subprocess.check_output(instrument_cmd, stderr=subprocess.STDOUT)

        except subprocess.CalledProcessError as e:
            logger.error(
                "Error during Java instrumentation command: {0}".format(
                    e.output.decode(errors="replace") if e.output else e
                )
            )
            raise
        except Exception as e:
            logger.error("Error during Java instrumentation command: {0}".format(e))
            raise

        try:
            if not os.path.isdir(instrumented_dex_path):
                os.makedirs(instrumented_dex_path)

            java2dalvik_cmd = [
                self.DX_PATH,
                "-JXmx4g",
                "--dex",
                "--multi-dex",
                "--output={0}".format(instrumented_dex_path),
                instrumented_jacoco_path,
                self.jacoco_agent_path,
            ]

            logger.info(
                'Converting Java to Dalvik with command "{0}"'.format(
                    " ".join(java2dalvik_cmd)
                )
            )
            subprocess.check_output(java2dalvik_cmd, stderr=subprocess.STDOUT)

        except subprocess.CalledProcessError as e:
            logger.error(
                "Error during Java to Dalvik conversion command: {0}".format(
                    e.output.decode(errors="replace") if e.output else e
                )
            )
            raise
        except Exception as e:
            logger.error(
                "Error during Java to Dalvik conversion command: {0}".format(e)
            )
            raise

        try:
            # Since Python doesn't allow directly modifying a file inside an archive, an
            # OS independent solution is to create a new archive with the changes. Since
            # the apk will be resigned, this step also removes the existing signature
            # (if present).

            repackaged_apk_buffer = io.BytesIO()

            with zipfile.ZipFile(target_apk_path, "r") as current_apk:
                # Create a new in-memory archive without the signature and by replacing
                # the old dex files with the instrumented dex files.
                with zipfile.ZipFile(
                    repackaged_apk_buffer, "w"
                ) as unsigned_apk_zip_buffer:
                    for entry in current_apk.infolist():
                        if entry.filename.startswith("META-INF/"):
                            # Signature file, don't include in the new apk.
                            continue

                        elif entry.filename.startswith(
                            "classes"
                        ) and entry.filename.endswith(".dex"):
                            # dex file, skip it for now as the instrumented version of
                            # this file will be included later in the new apk.
                            continue

                        else:
                            # Other file, copy it unchanged in the new apk.
                            unsigned_apk_zip_buffer.writestr(
                                entry, current_apk.read(entry.filename)
                            )

                    # Copy the instrumented dex file(s) into the new apk.
                    for instrumented_dex in os.listdir(instrumented_dex_path):
                        with open(
                            os.path.join(instrumented_dex_path, instrumented_dex), "rb"
                        ) as dex_file:
                            unsigned_apk_zip_buffer.writestr(
                                instrumented_dex, dex_file.read()
                            )

                # Write the in-memory archive to disk.
                with open(target_apk_path, "wb") as unsigned_apk:
                    unsigned_apk.write(repackaged_apk_buffer.getvalue())

        except Exception as e:
            logger.error("Error during apk repackaging: {0}".format(e))
            raise

        try:
            sign_cmd = [
                self.APKSIGNER_PATH,
                "sign",
                "--ks-pass",
                "pass:android",
                "--ks",
                os.path.join(
                    os.path.dirname(os.path.realpath(__file__)), "lib", "debug.keystore"
                ),
                target_apk_path,
            ]

            logger.info(
                'Running sign command with default debug key "{0}"'.format(
                    " ".join(sign_cmd)
                )
            )
            subprocess.check_output(sign_cmd, stderr=subprocess.STDOUT)

        except subprocess.CalledProcessError as e:
            logger.error(
                "Error during sign command: {0}".format(
                    e.output.decode(errors="replace") if e.output else e
                )
            )
            raise
        except Exception as e:
            logger.error("Error during sign command: {0}".format(e))
            raise

        try:
            # Since zipalign cannot be run inplace, a temp file will be created.
            apk_copy_path = shutil.copy2(
                target_apk_path, "{0}.copy".format(target_apk_path)
            )

            align_cmd = [
                self.ZIPALIGN_PATH,
                "-v",
                "-p",
                "-f",
                "4",
                apk_copy_path,
                target_apk_path,
            ]

            logger.info('Running align command "{0}"'.format(" ".join(align_cmd)))
            subprocess.check_output(align_cmd, stderr=subprocess.STDOUT)

        except subprocess.CalledProcessError as e:
            logger.error(
                "Error during align command: {0}".format(
                    e.output.decode(errors="replace") if e.output else e
                )
            )
            raise
        except Exception as e:
            logger.error("Error during aligning: {0}".format(e))
            raise
        finally:
            # Remove the temp file used for zipalign.
            if os.path.isfile("{0}.copy".format(target_apk_path)):
                os.remove("{0}.copy".format(target_apk_path))

        # Copy the instrumented apk and the jar file in the output directory.
        output_dir = os.path.join(
            os.path.dirname(os.path.realpath(__file__)), "output_apks"
        )
        shutil.copy2(target_apk_path, output_dir)
        shutil.copy2(classes_jar_path, output_dir)

        logger.info(
            'Files for apk "{0}" saved in "{1}" directory'.format(
                os.path.splitext(os.path.basename(self.apk_path))[0], output_dir
            )
        )
